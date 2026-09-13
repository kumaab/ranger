<!--
 - Licensed to the Apache Software Foundation (ASF) under one or more
 - contributor license agreements.  See the NOTICE file distributed with
 - this work for additional information regarding copyright ownership.
 - The ASF licenses this file to You under the Apache License, Version 2.0
 - (the "License"); you may not use this file except in compliance with
 - the License.  You may obtain a copy of the License at
 -
 -   http://www.apache.org/licenses/LICENSE-2.0
 -
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and
 - limitations under the License.
 -->

# Policy lifecycle: Admin write to plugin enforcement

The plugin half of this flow is in `agents-common` (`references/policy-refresh.md`, `references/access-evaluation.md`). This file covers the Admin half
and the seam between them.

## 1. Write

`rest/ServiceREST.createPolicy(RangerPolicy, HttpServletRequest)` at `@Path("/policies")` (`updatePolicy`, `deletePolicy` beside it). Body order is the
standard one: entry log -> `RangerPerfTracer` -> `validatorFactory.getPolicyValidator(svcStore).validate(policy, Action.CREATE, isAdmin)`
(`RangerPolicyValidator` from `agents-common`) -> `bizUtil` permission checks (`isAdmin`, zone admin, delegated admin via `RangerPolicyAdmin`) ->
`svcStore.createPolicy(policy)`.

## 2. Persist (`ServiceDBStore.createPolicy(policy, createPrincipalsIfAbsent)`)

Order: resolve service and def -> resolve `zoneId` (`RangerSecurityZone.RANGER_UNZONED_SECURITY_ZONE_ID = 1L` when no zone) ->
`daoMgr.getXXPolicy().findByNameAndServiceIdAndZoneId(...)` duplicate-name guard -> `policy.setVersion(1L)` -> `updatePolicySignature(policy)`
(GUID if absent, `factory.createPolicyResourceSignature(policy).getSignature()`) -> `policyService.create(policy, true)` (import path uses
`assignedIdPolicyService` between `setIdentityInsert(true)` / `updateSequence()`) -> `policyRefUpdater.createNewPolMappingForRefTable(...)` ->
`createOrMapLabels(...)` (labels are attached in `executeOnTransactionCommit`, so they are invisible inside the creating transaction) ->
`handlePolicyUpdate(service, RangerPolicyDelta.CHANGE_TYPE_POLICY_CREATE, ...)` -> `dataHistService.createObjectDataHistory` -> `createTransactionLog`.
Dedup queries on `XXPolicyDao`: `findByResourceSignatureByPolicyStatus`, `findByServiceIdAndResourceSignature`, `findDuplicatePoliciesByServiceAndResourceSignature`.


`biz/ServiceDBStore` writes the rows through `service/RangerPolicyService`: `XXPolicy` (`x_policy`; the full `RangerPolicy` JSON in `policy_text` is the source of truth,
the old `x_policy_item*`/`x_policy_resource*` tables are legacy) plus the denormalized
lookup tables `XXPolicyRefUser`, `XXPolicyRefGroup`, `XXPolicyRefRole`, `XXPolicyRefAccessType`, `XXPolicyRefResource`, `XXPolicyRefCondition`,
`XXPolicyRefDataMaskType` (maintained by `biz/PolicyRefUpdater`). Patch 077 dropped audit columns from these ref tables. Trx logs go to `XXTrxLogV2`.
Duplicate-resource detection uses `RangerPolicyResourceSignature` stored in `x_policy.resource_signature`.

## 3. Version bump (post-commit, asynchronous)

`ServiceDBStore.ServiceVersionUpdater implements Runnable` is scheduled through `common/db/RangerTransactionSynchronizationAdapter`, so the version
moves only after the policy transaction commits. `ServiceDBStore.VERSION_TYPE` is `{ POLICY_VERSION, TAG_VERSION, ROLE_VERSION, GDS_VERSION }`.
The updater increments the matching counter on `XXServiceVersionInfo` (`x_service_version_info`), bumps `XXGlobalState` (`x_ranger_global_state`),
and calls `persistChangeLog(XXService, VERSION_TYPE, Long, ServiceVersionUpdater)` to append an `XXPolicyChangeLog` (`x_policy_change_log`) row
(tags: `XXTagChangeLog`, `x_tag_change_log`). Those rows back delta downloads and age out per `ranger.admin.delta.retention.time.in.days` (default 7)
and `ranger.admin.tag.delta.retention.time.in.days` (default 3).

## 4. Download

`ServiceREST.getServicePoliciesIfUpdated` at `@Path("/policies/download/{serviceName}")`, params `lastKnownVersion` (default -1),
`lastActivationTime` (0), `pluginId`, `clusterName`, `zoneName`, `supportsPolicyDeltas` (false), `pluginCapabilities`. Kerberos/SSL clients use the
parallel `@Path("/secure/policies/download/{serviceName}")` method. With `supportsPolicyDeltas=true` the store answers from `x_policy_change_log`
as a `RangerPolicyDelta` list instead of a full `ServicePolicies`. Each download also records the plugin in `x_plugin_info` (`XXPluginInfo`), which
feeds the Audit > Plugin Status page. Bulk read is `biz/RangerPolicyRetriever` (`getServicePolicies(...)`, inner `PolicyLoaderThread`, `LookupCache`, own `TransactionTemplate`) behind
`common/RangerServicePoliciesCache` (siblings `RangerRoleCache`, `RangerUserStoreCache`). Client side: `PolicyRefresher` -> `RangerAdminRESTClient`.

Equivalent download resources, each with a `/secure/download/...` twin: tags `TagREST` `/tags/download/{serviceName}`, roles `RoleREST` `/roles/download/{serviceName}`,
user store `XUserREST` `/xusers/download/{serviceName}`, GDS `GdsREST` `/gds/download/{serviceName}`.

## 5. Admin's own engine

Admin instantiates the `agents-common` policy engine for its own decisions (delegated admin, zone checks, GDS): `biz/RangerPolicyAdmin`,
`RangerPolicyAdminImpl`, `RangerPolicyAdminCache`, `RangerPolicyAdminCacheForEngineOptions`. A change to `RangerPolicyEngineOptions` or to
evaluation semantics affects Ranger Admin as well as plugins; run `security-admin` tests too.
