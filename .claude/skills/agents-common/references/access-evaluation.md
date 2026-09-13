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

# `RangerBasePlugin` and access evaluation

## Construction and config

```java
public RangerBasePlugin(String serviceType, String appId)
public RangerBasePlugin(String serviceType, String serviceName, String appId)
public RangerBasePlugin(RangerPluginConfig pluginConfig)
public RangerBasePlugin(RangerPluginConfig cfg, ServicePolicies policies, ServiceTags tags, RangerRoles roles, RangerUserStore userStore, ServiceGdsInfo gdsInfo)
```

`RangerPluginConfig` (`org.apache.ranger.authorization.hadoop.config`): `propertyPrefix = "ranger.plugin." + serviceType`; loads `ranger-<svc>-audit.xml`,
`ranger-<svc>-security.xml`, `ranger-<svc>-policymgr-ssl.xml`, then `ranger-<svc>-<serviceName>-*.xml` overlays.
Keys under the prefix: `.service.name`, `.policy.source.impl` (default `RangerAdminRESTClient`), `.policy.rest.url`, `.policy.pollIntervalMs`, `.policy.cache.dir`,
`.super.users/groups`, `.audit.exclude.users/groups/roles`, `.service.admins`, `.is.fallback.supported`, `.use.x-forwarded-for.ipaddress`, `.trusted.proxy.ipaddresses`,
`.access.cluster.name/type`, `.use.rangerGroups`, `.use.only.rangerGroups`, `.convert.emailToUser`, `.enable.implicit.userstore.enricher`,
`.enable.implicit.gdsinfo.enricher`, `.preserve.deltas`, `.policyengine.option.*` (see `RangerPolicyEngineOptions.configureForPlugin`), `.ugi.initialize`, `.ugi.login.type`.

## Lifecycle

```java
public void init() {
    cleanup();

    AuditProviderFactory providerFactory = AuditProviderFactory.getInstance();

    if (!providerFactory.isInitDone()) {
        providerFactory.init(pluginConfig.getProperties(), getAppId());
    }

    if (!pluginConfig.getPolicyEngineOptions().disablePolicyRefresher) {
        refresher = new PolicyRefresher(this);
        refresher.setDaemon(true);
        refresher.startRefresher();
    }

    for (RangerChainedPlugin chainedPlugin : chainedPlugins) {
        chainedPlugin.init();
    }
}
```

`setPolicies(ServicePolicies)` builds a new `RangerPolicyEngineImpl` (or applies deltas via `RangerPolicyEngineImpl.getPolicyEngine(other, policies)`) and swaps the
`volatile policyEngine`. There is no public `setPolicyEngine`. `cleanup()` stops the refresher and releases engine resources.

## Public API

```java
RangerAccessResult             isAccessAllowed(RangerAccessRequest request)
Collection<RangerAccessResult> isAccessAllowed(Collection<RangerAccessRequest> requests)
RangerAccessResult             isAccessAllowed(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor)
RangerAccessResult             evalDataMaskPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor)
RangerAccessResult             evalRowFilterPolicies(RangerAccessRequest request, RangerAccessResultProcessor resultProcessor)
void                           evalAuditPolicies(RangerAccessResult result)
RangerResourceACLs             getResourceACLs(RangerAccessRequest request[, Integer policyType])
RangerResourceAccessInfo       getResourceAccessInfo(RangerAccessRequest request)
void                           grantAccess/revokeAccess(GrantRevokeRequest request, RangerAccessResultProcessor resultProcessor)
RangerRole                     createRole/dropRole/getRole/getUserRoles/getAllRoles/grantRole/revokeRole(...)
void                           setResultProcessor(RangerAccessResultProcessor)     /* usually new RangerDefaultAuditHandler(getConfig()) */
```

Minimal enforcement loop (`ranger-examples/plugin-sampleapp`):

```java
plugin = new RangerBasePlugin("sampleapp", "sampleapp");
plugin.setResultProcessor(new RangerDefaultAuditHandler(plugin.getConfig()));
plugin.init();

RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
resource.setValue("path", fileName);                      /* "path" must be a resource name in the service-def */

RangerAccessRequest request = new RangerAccessRequestImpl(resource, accessType, user, userGroups, null);
RangerAccessResult  result  = plugin.isAccessAllowed(request);
boolean             allowed = result != null && result.getIsAllowed();
```

`RangerAccessRequestImpl.setResourceMatchingScope(ResourceMatchingScope.SELF | SELF_OR_DESCENDANTS)` widens matching to descendants (used by `authz-embedded` and HDFS).
Full `.policyengine.option.*` table, trie mechanics, evaluator selection and `getResourceACLs`: [policy-engine-internals.md](policy-engine-internals.md).

## Engine flow

```
RangerBasePlugin.isAccessAllowed(request, resultProcessor)
  RangerPolicyEngineImpl.evaluatePolicies(request, POLICY_TYPE_ACCESS, null)         [read lock]
    RangerDefaultRequestProcessor.preProcess(request)
      set serviceDef on resource, client IP (x-forwarded-for + trusted proxies), cluster name/type,
      user/group name transformation, email->user, Ranger groups merge, KEY_USER/KEY_OWNER/KEY_ROLES, run all context enrichers
    zoneAwareAccessEvaluationWithNoAudit(request, policyType)
      zones from RangerAccessRequestUtil.getResourceZoneNamesFromContext
      repository = policyEngine.getRepositoryForZone(zone | null)
      evaluatePoliciesNoAudit -> evaluatePoliciesForOneAccessTypeNoAudit
        super-user short circuit (ret.policyId = -1, priority = MAX, reason "superuser")
        evaluateTagPolicies (RangerTagAccessRequest per tag from context, zone-aware)
        for evaluator in policyRepository.getLikelyMatchPolicyEvaluators(request, policyType): evaluator.evaluate(request, ret)
      updateFromGdsResult(ret); evaluateInlinePolicy(request, ret)
  chained plugins (skip when access determined and skipAccessCheckIfAlreadyDetermined)
  policyEngine.evaluateAuditPolicies(ret)
  resultProcessor.processResult(ret)
```

`policyRepository.getLikelyMatchPolicyEvaluators` is backed by one `RangerResourceTrie` per resource-def level (`policyengine/RangerResourceTrie.java`), a prefix
trie over policy resource values that pre-filters evaluators before matching; `ranger.plugin.<svc>.policyengine.option.optimize.trie.for.retrieval` (default false)
trades build time for lookup speed. Wildcard and dynamic (`{USER}`, `${{...}}`) values fall into a catch-all bucket.

Rules inside the loop: after tag policies `isAccessDetermined` is reset so resource policies may override; a tag DENY is final unless a resource policy has strictly
higher `policyPriority`; for ACCESS an earlier allow is final when `ret.getPolicyPriority() > evaluator.getPolicyPriority()`, for mask/row-filter when `>=`;
loop breaks once both audit and access are determined; without `isFallbackSupported` an undetermined result becomes deny.
`RangerDefaultPolicyEvaluator.evaluate` orders items deny -> deny-exceptions -> allow -> allow-exceptions and checks `matchPolicyCustomConditions` first.

Constants on `RangerPolicyEngine`: `GROUP_PUBLIC = "public"`, `ANY_ACCESS = "_any"`, `ADMIN_ACCESS = "_admin"`, `SUPER_USER_ACCESS = "_super_user"`,
`AUDIT_ALL/NONE/DEFAULT`, `USER_CURRENT = "{USER}"`, `RESOURCE_OWNER = "{OWNER}"`.
`RangerPolicy`: `POLICY_TYPE_ACCESS = 0`, `DATAMASK = 1`, `ROWFILTER = 2`, `AUDIT = 3`; `POLICY_PRIORITY_NORMAL = 0`, `OVERRIDE = 1`.

## `RangerAccessResult`

`isAllowed`, `isAccessDetermined`, `isAudited`, `isAuditedDetermined`, `policyId`, `auditPolicyId`, `policyPriority`, `zoneName`, `policyVersion`,
`evaluatedPoliciesCount`, `reason`, `additionalInfo`; mask data `KEY_MASK_TYPE`, `KEY_MASK_CONDITION`, `KEY_MASKED_VALUE`; `filterExpr`; GDS `datasets`/`projects`.
Audit mode and `isAuditExcludedUser` are applied in `createAccessResult`.

## Request context keys (`util/RangerAccessRequestUtil`)

`TAGS`, `TAG_OBJECT`, `RESOURCE`, `REQUESTED_RESOURCES`, `USERSTORE`, `token:` namespace, `USER`, `OWNER`, `ROLES`, `ISANYACCESS`, `ALLACCESSTYPEGROUPS`, `ALLACCESSTYPES`,
`IGNOREIFNOTDENIEDACCESSTYPES`, `ALL_ACCESS_TYPE_RESULTS`, `ALL_ACCESS_TYPE_ACL_RESULTS`, `_REQUEST`, `_GDS_RESULT`, `ISREQUESTPREPROCESSED`, `RESOURCE_ZONE_NAMES`, `_ACL_ENFORCER`.
Use the static `set*InContext` / `get*FromContext` helpers only.

## Perf loggers in use

`policyengine.request`, `policyengine.audit`, `policyengine.getResourceACLs`, `policyengine.init`, `policyengine.rebalance`, `contextenricher.init`,
`contextenricher.request`, `tagenricher.setservicetags`, `tagenricher.tags.retrieval`, `policy.init`, `policyitem.init`, `policycondition.init`, `policy.delta`.
Name new ones `<area>.<operation>` and hold them in `PERF_<AREA>_<OP>_LOG` fields.
