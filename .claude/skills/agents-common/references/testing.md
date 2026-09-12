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

# Testing in agents-common

JUnit 5, Mockito 5. Test classes are `Test*.java` (dominant) or `*Test.java` (some matcher tests). Fixtures under `src/test/resources/`.

## JSON-driven policy engine tests

`src/test/java/org/apache/ranger/plugin/policyengine/TestPolicyEngine.java` loads `src/test/resources/policyengine/test_policyengine_*.json` with Gson and
custom deserializers (`RangerAccessRequestDeserializer`, `RangerResourceDeserializer`).

Case shape (`PolicyEngineTestCase`):

```
serviceName, serviceDef (minimal: name, id, resources, accessTypes[, policyConditions]), policies[],
tagPolicyInfo, securityZones, userRoles, groupRoles, roleRoles, auditMode, serviceConfig,
superUsers, superGroups, auditExcludedUsers/Groups/Roles,
tests[] { name, request, result, dataMaskResult, rowFilterResult, resourceAccessInfo, userAttributes, groupAttributes },
updatedPolicies, updatedTests[]
```

```json
{ "name": "DENY 'create or write for org;' for john",
  "request": {
    "resource": { "elements": { "database": "org" } },
    "accessType": "create", "user": "john", "userGroups": [],
    "requestData": "create org",
    "context": { "ISANYACCESS": true, "ACCESSTYPES": [ "create", "write" ] }
  },
  "result": { "isAudited": false, "isAllowed": false, "policyId": -1 } }
```

Condition wiring uses the test-only `conditionevaluator/RangerSimpleMatcher` (`evaluatorOptions.CONTEXT_NAME`) in `test_policyengine_conditions.json`.
Prefer adding a case to an existing file over a new Java test when the change is in matching or evaluation.

## Other families

- `TestPolicyACLs` -> `policyengine/test_aclprovider_*.json` (`{ "testCases": [ { name, servicePolicies { serviceName, serviceDef, policies }, ... } ] }`).
- `TestPolicyEngineForDeltas` (`*_incremental_{add,update,delete}.json`), `TestPolicyEngineComparison` (`policyengine/comparison/{success,fail}/`), `gds/TestGdsPolicyEngine`.
- Resource matchers: `resourcematcher/*Test.java` against `resourcematcher/test_resourcematcher_{default,path,dynamic,wildcards_as_delimiters}.json`:

```json
{ "testCases": [ {
  "name": "values={USER}_simple",
  "resourceDef": { "matcher": "org.apache.ranger.plugin.resourcematcher.RangerDefaultResourceMatcher",
                   "matcherOptions": { "wildCard": true, "ignoreCase": true, "replaceTokens": true } },
  "policyResource": { "values": [ "{USER}_simple" ] },
  "tests": [ { "name": "all-lower", "input": "admin_simple", "evalContext": { "token:USER": "admin" }, "result": true } ] } ] }
```

- Plugin level: `service/TestRangerBasePlugin`, `TestRangerBasePluginRaceCondition` with `src/test/resources/plugin/{hive_policies,hive_tags,hive_roles,hive_user_store}.json`;
  test admin client `policyengine/RangerAdminClientImpl`.
- Validation: `model/validation/Test*Validator`, `ValidationTestUtils`, fixtures in `src/test/resources/admin/service-defs/`.
- Enrichers: `contextenricher/TestRangerTagEnricher`, `TestRangerUserStoreEnricher`; fixtures `src/test/resources/contextenricher/`.
- Service defs: `store/TestEmbeddedServiceDefsUtil`.

## Running

```bash
mvn -pl agents-common test -Dtest=TestPolicyEngine
mvn -pl agents-common test -Dtest=RangerPathResourceMatcherTest
mvn -pl agents-common -DskipTests verify        # checkstyle + PMD + RAT + spotbugs
```

Test JSON under `src/test/resources` is RAT-excluded; Java tests need the license header and pass checkstyle.
