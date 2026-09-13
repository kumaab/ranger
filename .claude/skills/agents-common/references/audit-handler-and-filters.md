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

# Audit handler and audit filters (plugin side)

The audit transport (`agents-audit` queues, destinations, `xasecure.audit.*` keys, the audit server) is documented in the `ranger-audit-server` skill.
This file covers what `agents-common` contributes.

## `RangerDefaultAuditHandler implements RangerAccessResultProcessor`

Set on the plugin with `plugin.setResultProcessor(new RangerDefaultAuditHandler(plugin.getConfig()))`. `processResult(result)` -> `getAuthzEvents(result)`
builds an `AuthzAuditEvent` (`agents-audit` core) and pushes it through `AuditProviderFactory.getInstance().getAuditProvider().log(...)`.
Field mapping gotcha: `setAction(request.getAccessType())` and `setAccessType(request.getAction())` are crossed on purpose. `aclEnforcer` comes from
`RangerAccessRequestUtil.getAclEnforcerOrDefault(ctx, moduleName)`; `xasecure.audit.auditid.strict.uuid` (default false) picks UUID vs `<seq>-<host>` event ids;
`result.setAuditLogId(...)` is set so callers can correlate.

Accumulate-then-flush lives in `RangerMultiResourceAuditHandler.flushAudit()`, not in the default handler. Service handlers that batch
(`RangerHiveAuditHandler`, `HbaseAuditHandlerImpl`, `RangerSolrAuditHandler`) follow that pattern.

## Audit filters

Service config `ranger.plugin.audit.filters` (`RangerPolicyEngine.PLUGIN_AUDIT_FILTER`) is a JSON array of `model/AuditFilter { accessResult, resources,
accessTypes, actions, users, groups, roles, isAudited }`. `RangerPolicyRepository.buildAuditPolicyEvaluators()` wraps each in a `RangerAuditPolicyEvaluator`
with descending priority (list order = evaluation order) indexed in `auditFilterResourceTrie`; `RangerPolicyEngineImpl.evaluateAuditPolicies(result)` applies them
after access evaluation (`POLICY_TYPE_AUDIT = 3`). Defaults were seeded by Admin patches `J10049` / `J10050`.
Companion service-config keys: `ranger.plugin.audit.exclude.users|groups|roles`, `ranger.plugin.super.users|groups`, `ranger.plugin.service.admins`.

## Audit mode

`ServicePolicies.auditMode` (`AUDIT_ALL`, `AUDIT_NONE`, `AUDIT_DEFAULT`) is applied in `RangerPolicyEngineImpl.createAccessResult`; excluded users/groups/roles get
`isAudited=false`.
