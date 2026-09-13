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

# Testing across the repo

## Java

- JUnit Jupiter 5.14 and Mockito 5.23 (root pom properties `junit.jupiter.version`, `mockito.version`). JUnit 4 (`junit:junit`) and Jersey 1 are banned at
  compile/runtime by `maven-enforcer-plugin` `ban-legacy-dependencies`.
- `@ExtendWith(MockitoExtension.class)`; add `@MockitoSettings(strictness = Strictness.LENIENT)` when stubs are shared across methods (security-admin does).
- Static imports from `org.junit.jupiter.api.Assertions` (`assertEquals`, `assertTrue`, `assertNotNull`, `assertThrows`) and `org.mockito.Mockito`/`ArgumentMatchers`
  (`when`, `mock`, `verify`, `times`, `any`, `eq`). AssertJ and Hamcrest are available but rarely used.
- Naming: `Test*.java` dominates (`agents-common`, `security-admin`, `authz-*`); `*Test.java` in newer modules (`pdp`, `ranger-metrics`, `agents-audit`, matcher tests); `audit-server` mixes both. Follow the module.
- No JUnit `@Tag`/categories. Fixtures in `src/test/resources` (`logback.xml`, `ranger-<svc>-security.xml`, `*-policies.json`, `*.jks`); filter `*.xml` but not `*.jks`.
- Checkstyle runs on tests (`includeTestSourceDirectory=true`); license header required on test Java.
- Surefire (root pom `pluginManagement`): JaCoCo `${argLine}`, `--add-opens` for `java.base` reflection and crypto, system properties `logdir`, `catalina.base`,
  `polyglot.engine.WarnInterpreterOnly=false`. No `forkCount`; `plugin-kafka` sets `reuseForks=false`.

```bash
mvn -pl agents-common test -Dtest=TestPolicyEngine
mvn -pl pdp test -Dtest=RangerPdpConfigTest#testInvalidPortFallsBackToDefault
mvn -pl security-admin test -Dtest=TestServiceREST -DfailIfNoTests=false
```

Add `-am` when upstream modules are not in the local repo. Coverage: `dev-support/checks/coverage.sh` merges all `jacoco.exec` after a full build.

## Module specifics

- `agents-common`: JSON-driven `TestPolicyEngine`, `TestPolicyACLs`, matcher JSON suites (see `agents-common` skill).
- `security-admin`: Mockito over `RangerDaoManager` deep stubs, ~50 `TestPatch*_J100xx` tests, no frontend tests (see `security-admin` skill).
- SQL patches: no automated tests; verify with `dev-support/ranger-docker` (see `security-admin-db` skill).

## Python

- Client (`intg/`): `unittest`, one `TestCase` per client area in `intg/src/test/python/test_ranger_client.py`, mock at `Session` or `client_http.call_api`.
  Run from `intg/`: `PYTHONPATH=src/main/python python -B src/test/python/test_ranger_client.py`. Also wired into `intg/pom.xml` via `exec-maven-plugin` at the `test` phase, so `mvn verify` runs it.
- Functional (`functional-tests/`, pytest): suites `rolerest`, `xuserrest`, `servicerest`, `hdfs`, `kms`; markers in `pytest.ini`; `run-tests.sh <db-type> [services]`
  against docker compose. Not a Maven module and not run in CI.

## JavaScript

None. `npm test` is a stub; `babel-plugin-istanbul` instrumentation exists behind `-DskipJSCoverage=false` for an out-of-tree Cypress suite.

## Odds and ends

- `RangerPolicyResourceSignature` hashes with SHA-256 (SHA-512/SHA-384 when `RangerAdminConfig.isFipsEnabled()`); this fills `x_policy.resource_signature`.
- `build_ranger_using_docker.sh` builds image `ranger_dev` from `ubuntu:22.04` with OpenJDK 11 and Maven 3.9.9, which lags the JDK 17 requirement in the root pom; prefer a local JDK 17.
- `ranger-tools` entry points: `RangerPolicyenginePerfTester`, `PerfTestEngine`, `PerfTestClient`, `PerfTestOptions`.

## CI

`.github/workflows/ci.yml`: `mvn -T 8 clean verify` (JDK 17), coverage merge, docker image builds and smoke boot of admin, usersync, tagsync, kms, kafka,
audit ingestor/dispatcher, plus plugin containers. `upgrade-ranger.yaml` (manual) tests upgrades from 2.6.0/2.7.0 on postgres/mysql/oracle.
