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

# Policy refresh, caching, and Admin client

## `PolicyRefresher extends Thread` (`util/`)

- Cache file `String.format("%s_%s.json", appId, serviceName)` (separators replaced by `_`) under `ranger.plugin.<svc>.policy.cache.dir`.
- `run()` blocks on `policyDownloadQueue.take()`, then `loadRoles()` and `loadPolicy()`, always `trigger.signalCompletion()`.
- `loadPolicyfromPolicyAdmin()` -> `rangerAdmin.getServicePoliciesIfUpdated(lastKnownVersion, lastActivationTimeInMillis)`; on failure `loadFromCache()` (warns on serviceName mismatch).
- `saveToCache(policies)` via `JsonUtils.objectToWriter`. Deltas go to `<cacheDir>/deltas/<file>_<policyVersion>`; `ranger.plugin.<svc>.preserve.deltas=true` keeps versioned copies.
- `syncPoliciesWithAdmin(DownloadTrigger)` is the synchronous path used by `RangerBasePlugin.refreshPoliciesAndTags()`.
- Polling interval `ranger.plugin.<svc>.policy.pollIntervalMs` (30000 in shipped `*-security-changes.cfg`).

Tag cache: `String.format("%s_%s_tag.json", appId, serviceName)` (in `RangerTagEnricher`). UserStore and GDS enrichers follow the same scheme.

## `ServicePolicies`

`serviceName`, `serviceId`, `policyVersion`, `policyUpdateTime`, `policies`, `serviceDef`, `auditMode`, `tagPolicies` (nested `TagPolicies`),
`securityZones (Map<String, SecurityZoneInfo>)`, `policyDeltas`, `serviceConfig`. `SecurityZoneInfo { zoneName, resources, policies, policyDeltas, containsAssociatedTagService }`.

## Deltas

`RangerPolicyDelta { id, changeType, policiesVersion, policy }`, change types `POLICY_CREATE=0`, `POLICY_UPDATE=1`, `POLICY_DELETE=2`, `SERVICE_CHANGE=3`,
`SERVICE_DEF_CHANGE=4`, `RANGER_ADMIN_START=5`, `LOG_ERROR=6`, `INVALIDATE_POLICY_DELTAS=7`, `ROLE_UPDATE=8`, `GDS_UPDATE=9`.
`RangerPolicyDeltaUtil.applyDeltas(policies, deltas, serviceType)`, `isValidDeltas`, `hasPolicyDeltas`. Tags: `RangerServiceTagsDeltaUtil.applyDelta(serviceTags, delta, supportsTagsDedup)`;
`ServiceTags.op` in `add_or_update | delete | replace`.

## `RangerAdminClient` (`org.apache.ranger.admin.client`)

`init(serviceName, appId, configPropertyPrefix, config)`, `getServicePoliciesIfUpdated`, `getRolesIfUpdated`, `getServiceTagsIfUpdated`, `getUserStoreIfUpdated`,
`getGdsInfoIfUpdated`, `getTagTypes`, `createRole/dropRole/getRole/getAllRoles/getUserRoles/grantRole/revokeRole`, `grantAccess/revokeAccess`.
Implementations: `AbstractRangerAdminClient`, `RangerAdminRESTClient` (default), `LocalFolderPolicySource`, `EmbeddedResourcePolicySource`.
Selected by `ranger.plugin.<svc>.policy.source.impl`.

## `RangerRESTClient` / `RangerRESTUtils`

`RangerRESTClient`: SSL from `xasecure.policymgr.clientssl.*`, JWT (`ranger.common.auth.jwt.*`), Kerberos, connection timeout / retries
(`setRestClientConnTimeOutMs`, `setMaxRetryAttempts`, `setRetryIntervalMs`), `getResource(relativeUrl)` -> JAX-RS `WebTarget`.
`RangerRESTUtils` holds every Admin download path (`/service/plugins/policies/download/`, `/service/tags/download/`, `/service/roles/download/`, `/service/xusers/download/`, ...)
and query-param names (`lastKnownVersion`, `lastActivationTime`, `pluginId`, `lastKnownRoleVersion`, `lastKnownUserStoreVersion`). Add new endpoints there, not inline.
