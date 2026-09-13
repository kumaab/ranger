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

# Policy engine internals: tries, evaluator selection, ACL computation

## Tries

`RangerPolicyRepository` holds four `Map<String, RangerResourceTrie<RangerPolicyResourceEvaluator>>` keyed by resource-def name: `policyResourceTrie`,
`dataMaskResourceTrie`, `rowFilterResourceTrie`, `auditFilterResourceTrie`. All are `null` when `disableTrieLookupPrefilter` is set, which turns lookups
into a linear scan over `getPolicyEvaluators()`.

`RangerResourceTrie` (`policyengine/`): `DEFAULT_WILDCARD_CHARS = "*?"`; `getEvaluatorsForResource(resource, ResourceElementMatchingScope, filter, Predicate)`;
`traverse(...)` with `TraverseMatchHandler`/`EvalCollector`; incremental `add`/`delete`/`wrapUpUpdate` for delta application; `dumpTrie()`.
Perf loggers `resourcetrie.init`, `resourcetrie.op`. Global (unprefixed) `ranger.policyengine.trie.builder.thread.count` (default 1) parallelizes construction.

`getLikelyMatchPolicyEvaluators(trieMap, request)` -> `util/RangerResourceEvaluatorsRetriever.getEvaluators()`: counts candidates per resource element,
starts from the element with the smallest candidate set, intersects the remaining elements' sets, bails to empty on the first empty intersection,
de-dupes by policy id and sorts with `RangerPolicyEvaluator.EVAL_ORDER_COMPARATOR`.

## Evaluator selection

`RangerPolicyRepository.buildPolicyEvaluator()` instantiates `RangerCachedPolicyEvaluator` only when `options.evaluatorType` is `EVALUATOR_TYPE_CACHED`;
otherwise always `RangerOptimizedPolicyEvaluator` (`RangerDefaultPolicyEvaluator` is its superclass and is never instantiated directly). Types: `auto`
(default), `optimized`, `cached`. `RangerOptimizedPolicyEvaluator.computeEvalOrder()` = `RANGER_POLICY_EVAL_SCORE_DEFAULT` + dynamic-resource penalty minus
discounts for resources, users/groups, access-type ratio and custom conditions; the score is `getEvalOrder()`.

## Engine options (`RangerPolicyEngineOptions.configureForPlugin`)

Prefix `ranger.plugin.<svc>.policyengine.option.`:

| Key | Default |
|---|---|
| `disable.context.enrichers`, `disable.custom.conditions`, `disable.tagpolicy.evaluation`, `disable.trie.lookup.prefilter`, `disable.policy.refresher`, `disable.tag.retriever`, `disable.userstore.retriever`, `disable.gdsinfo.retriever` | false |
| `cache.audit.results` | true, forced false unless trie prefilter is disabled |
| `enable.resourcematcher.reuse` | true |
| `optimize.trie.for.retrieval`, `optimize.trie.for.space`, `optimize.tag.trie.for.retrieval`, `optimize.tag.trie.for.space` | false |
| `disable.role.resolution` | true |

Admin uses `configureDefaultRangerAdmin` / `configureDelegateAdmin` variants.

## Service-config keys (unprefixed)

`RangerPolicyEngineImpl.ServiceConfig` reads these from the **service instance config** first, then `RangerPluginConfig`: `ranger.plugin.audit.filters`
(`RangerPolicyEngine.PLUGIN_AUDIT_FILTER`), `ranger.plugin.audit.exclude.users|groups|roles`, `ranger.plugin.super.users|groups`, `ranger.plugin.service.admins`.
Unlike file keys they carry no `<svc>` segment.

## `getResourceACLs`

`RangerPolicyEngineImpl.getResourceACLs(request, policyType)` runs under the read lock, `requestProcessor.preProcess(request)`, walks matched evaluators calling
`RangerAbstractPolicyEvaluator.getResourceACLs(request, acls, isConditional, targetAccessTypes, matchType, policyEngine)`, then `RangerResourceACLs.finalizeAcls()`
(propagates `public` group grants into user/group entries unless the result `isFinal`). `RangerResourceACLs` = `userACLs`/`groupACLs`/`roleACLs`
(`Map<name, Map<accessType, AccessResult>>`) + `rowFilters` (`RowFilterResult`) + `dataMasks` (`DataMaskResult`) + `datasets`/`projects`.
Per-policy summaries come from `PolicyACLSummary` built by `createPolicyACLSummary()`; role resolution inside it is gated by `disableRoleResolution`.
Perf logger `policyengine.getResourceACLs`. Consumers: HDFS `getResourceAccessInfo`, `authz-embedded` `getResourcePermissions`, Admin delegated-admin checks.

## Config resource loading (`RangerPluginConfig`)

Order (later wins, Hadoop `Configuration` semantics): `addResourcesForServiceType()` (`ranger-<svc>-audit.xml`, `-security.xml`, `-policymgr-ssl.xml`),
`addResourcesForServiceName()` (`ranger-<svc>-<serviceName>-*.xml`), then any `additionalConfigFiles` (e.g. `RangerHdfsAuthorizer(Path)`).
When a file is unreadable, `addSecurityResource`/`addAuditResource`/`addSslConfigResource` synthesize config via `RangerLegacyConfigBuilder`.
`RangerConfiguration.getFileLocation()` tries classloader resource, `/`-prefixed resource, then filesystem path. Cluster fallbacks:
`.access.cluster.name|type` -> `.ambari.cluster.name|type`.
