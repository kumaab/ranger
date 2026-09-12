---
name: agents-common
description: Architecture and extension patterns for the Ranger plugin framework (agents-common module, artifact ranger-plugins-common) - RangerBasePlugin lifecycle, policy engine evaluation flow, service-def JSON, policy condition evaluators, context enrichers, resource matchers, Ranger* model conventions, validators and ValidationErrorCode, PolicyRefresher caching, JSON-driven TestPolicyEngine tests. Use when changing anything under agents-common/ or when a plugin needs a new condition, enricher, matcher, or service-def.
---
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

# Ranger plugin framework (`agents-common`)

Java root: `agents-common/src/main/java/org/apache/ranger/plugin/` plus `org.apache.ranger.admin.client` and `org.apache.ranger.authorization.hadoop.config`.
Resources: `agents-common/src/main/resources/service-defs/ranger-servicedef-*.json`. Style and license header: `ranger-conventions`.
Building a whole new plugin module on top of this: `ranger-plugin`.

Not here, despite the package name: `RangerPerfTracer`, `JsonUtilsV2`, `RangerCache`, `RangerReadWriteLock` (`common-utils`), `RangerPluginClassLoader`
(`ranger-plugin-classloader`), `RangerAuthorizer` (`authz-api`).

## Package map

| Package | Purpose | Key types |
|---|---|---|
| `model` | wire/domain objects shared with Admin | `RangerBaseModelObject`, `RangerPolicy`, `RangerService`, `RangerServiceDef`, `RangerRole`, `RangerSecurityZone`, `RangerGds`, `RangerTag*`, `RangerPolicyDelta`, `RangerValiditySchedule` |
| `model/validation` | validators used by Admin | `RangerValidator` (`Action` enum), `RangerServiceDefValidator`, `RangerServiceValidator`, `RangerPolicyValidator`, `RangerSecurityZoneValidator`, `RangerRoleValidator`, `ValidationFailureDetails(Builder)` |
| `service` | plugin entry point and service impl base | `RangerBasePlugin`, `RangerBaseService`, `RangerDefaultService`, `RangerAuthContext`, `RangerChainedPlugin`, `RangerDefaultRequestProcessor`, `ResourceLookupContext` |
| `policyengine` | evaluation | `RangerPolicyEngine(Impl)`, `PolicyEngine`, `RangerPolicyRepository`, `RangerAccessRequest(Impl)`, `RangerAccessResource(Impl)`, `RangerAccessResult`, `RangerResourceACLs`, `RangerPolicyEngineOptions`, `RangerPluginContext`, `gds/` |
| `policyevaluator` | per-policy logic | `RangerDefaultPolicyEvaluator`, `RangerOptimizedPolicyEvaluator`, `Ranger*PolicyItemEvaluator`, `RangerValidityScheduleEvaluator`, `RangerCustomConditionEvaluator` |
| `conditionevaluator` | policy conditions | `RangerAbstractConditionEvaluator`, `RangerIpMatcher`, `RangerTimeOfDayMatcher`, `RangerScriptConditionEvaluator`, `RangerAccessedFromCluster*Condition` |
| `contextenricher` | request enrichment | `RangerAbstractContextEnricher`, `RangerTagEnricher`, `RangerUserStoreEnricher`, `RangerGdsEnricher`, `Ranger*Retriever` |
| `resourcematcher`, `policyresourcematcher` | matching | `RangerAbstractResourceMatcher`, `RangerDefaultResourceMatcher`, `RangerPathResourceMatcher`, `RangerURLResourceMatcher`, `RangerDefaultPolicyResourceMatcher` |
| `util` | refresh, REST, helpers | `PolicyRefresher`, `ServicePolicies`, `ServiceTags`, `ServiceDefUtil`, `RangerPolicyDeltaUtil`, `RangerAccessRequestUtil`, `RangerRESTClient`, `RangerRESTUtils`, `SearchFilter`, `RangerRoles`, `RangerUserStore` |
| `store` | store SPI used by Admin | `ServiceStore`, `AbstractServiceStore`, `EmbeddedServiceDefsUtil`, `TagStore`, `RoleStore`, `SecurityZoneStore`, `GdsStore`, `PList` |
| `audit`, `client`, `errors`, `geo`, `authn` | `RangerDefaultAuditHandler`, `BaseClient`/`HadoopConfigHolder`, `ValidationErrorCode`, geolocation, JWT |

## Non-negotiables

- `private static final Logger LOG`; `LOG.debug("==> Class.method({})", arg)` / `"<== ...: ret={}"` with placeholders; no `isDebugEnabled` guard unless the message is expensive.
- Perf tracing on hot paths: `private static final Logger PERF_X_LOG = RangerPerfTracer.getPerfLogger("policyengine.request");` then `isPerfTraceEnabled` / `getPerfTracer` / `RangerPerfTracer.log(perf)`.
- Models: `@JsonAutoDetect(fieldVisibility = ANY) @JsonInclude(NON_EMPTY) @JsonIgnoreProperties(ignoreUnknown = true)`, `implements java.io.Serializable`, `serialVersionUID = 1L`,
  setters go through `nullSafeList/Set/Map`, chainable `toString(StringBuilder sb)` delegated from `toString()`. No `@XmlRootElement` on new models.
- Extension classes need a public no-arg constructor (instantiated by `Class.forName(...).newInstance()`).
- Condition evaluators and matchers fail open on missing config: empty `condition`/options means "always match".
- Read request context only through `RangerAccessRequestUtil.set*InContext/get*FromContext`, never by raw key.
- Hot-swapped references are `volatile`; shared state is guarded with `RangerReadWriteLock` in try-with-resources.
- New `ValidationErrorCode` entries take a free number in the right block: 1xxx service, 2xxx service-def, 3xxx policy, 4xxx zone/role/GDS.
- `CollectionUtils`/`StringUtils`/`MapUtils` for null checks; `Collections.emptyList()` etc. for empty returns; the result local is named `ret`; one `return` per method.

## Extension points at a glance

| Adding | Extend | Implement | Register in service-def | Test |
|---|---|---|---|---|
| policy condition | `RangerAbstractConditionEvaluator` | `init()`, `isMatched(RangerAccessRequest)` | `policyConditions[] { itemId, name, evaluator, evaluatorOptions, label, uiHint }` | JSON case with `policyItems[].conditions`, or a `Test*Condition` class |
| context enricher | `RangerAbstractContextEnricher` | `init()`, `enrich(request)`, optional refresher thread + `Ranger*Retriever` | `contextEnrichers[] { itemId, name, enricher, enricherOptions }` | `TestRangerTagEnricher` pattern |
| resource matcher | `RangerAbstractResourceMatcher` | `getMatchType`, `isMatch`, optional `buildResourceMatchers()` | resource element `matcher` + `matcherOptions` | `src/test/resources/resourcematcher/*.json` |
| service-def | JSON file | `RangerService<X> extends RangerBaseService` in the plugin module | `EmbeddedServiceDefsUtil` constants + bootstrap list | `TestEmbeddedServiceDefsUtil`, JSON case under `policyengine/` |

Templates: [references/extension-points.md](references/extension-points.md). Service-def schema: [references/service-def.md](references/service-def.md).

## Evaluation flow (one screen)

`RangerBasePlugin.isAccessAllowed(request, resultProcessor)` -> `RangerPolicyEngineImpl.evaluatePolicies(request, POLICY_TYPE_ACCESS, null)` under a read lock ->
`RangerDefaultRequestProcessor.preProcess` (client IP, cluster, user/groups/roles into context, run all enrichers) -> zone selection
(`RangerAccessRequestUtil.getResourceZoneNamesFromContext`) -> per repository: super-user short-circuit, tag policies via `RangerTagAccessRequest`, then
`policyRepository.getLikelyMatchPolicyEvaluators(request, policyType)` -> `RangerPolicyEvaluator.evaluate(request, result)` (deny -> deny-exceptions -> allow -> allow-exceptions,
priority/override rules) -> GDS and inline policy -> chained plugins -> `evaluateAuditPolicies` -> `resultProcessor.processResult(ret)` (`RangerDefaultAuditHandler`).
Policy types: `POLICY_TYPE_ACCESS=0`, `DATAMASK=1`, `ROWFILTER=2`, `AUDIT=3`. Details: [references/access-evaluation.md](references/access-evaluation.md).

## Tests

`TestPolicyEngine` runs JSON cases from `src/test/resources/policyengine/test_policyengine_*.json` (Gson, custom deserializers). Add a case rather than a
Java test when the change is in matching or evaluation. Run one class:

```bash
mvn -pl agents-common test -Dtest=TestPolicyEngine
```

Shape and other test families: [references/testing.md](references/testing.md).

## References (load on demand)

- [references/service-def.md](references/service-def.md): JSON schema, canonical defs (hdfs, hive, kms, tag), `EmbeddedServiceDefsUtil` registration, reserved ids.
- [references/extension-points.md](references/extension-points.md): condition evaluator, context enricher (with refresher pattern), resource matcher templates and option constants.
- [references/access-evaluation.md](references/access-evaluation.md): `RangerBasePlugin` API and config, engine flow, zones/tags/priority, `RangerAccessResult`, context keys.
- [references/models-and-validation.md](references/models-and-validation.md): `RangerBaseModelObject`, `RangerPolicy` nested types, Jackson conventions, validators and `ValidationErrorCode`.
- [references/policy-refresh.md](references/policy-refresh.md): `PolicyRefresher`, cache files, deltas, `RangerAdminClient`, `RangerRESTClient`/`RangerRESTUtils`.
- [references/testing.md](references/testing.md): JSON test-case shapes and test families.
