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

# Testing in security-admin

JUnit 5 (`5.14`), Mockito (`5.x`), no shared base class, tests mirror the main package layout under `src/test/java/org/apache/ranger/`.
Checkstyle runs on test sources too.

## Class header

```java
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TestAuditMetricsREST {
    @InjectMocks
    AuditMetricsREST auditMetricsREST;

    @Mock
    RESTErrorUtil restErrorUtil;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RangerDaoManager daoManager;
```

Legacy tests add `@TestMethodOrder(MethodOrderer.MethodName.class)` and number methods (`test1createServiceDef`, `test10getServiceById`). New tests should use
descriptive names (`testGetLatestAuditMetrics_Success`, `testGetDaysAuditMetrics_InvalidDays`) and static imports for
`assertEquals`, `assertNotNull`, `assertThrows`, `when`, `verify`, `times`, `never`, `eq`, `any`.

## Mocking DAOs

Either deep-stub the manager, or stub explicit DAOs:

```java
XXServiceDefDao xServiceDefDao = Mockito.mock(XXServiceDefDao.class);

Mockito.when(daoManager.getXXServiceDef()).thenReturn(xServiceDefDao);
Mockito.when(xServiceDefDao.getById(Id)).thenReturn(xServiceDef);
```

Mock `RESTErrorUtil` to return a `WebApplicationException` when asserting error paths:

```java
when(restErrorUtil.createRESTException(anyString(), any())).thenReturn(new WebApplicationException());
assertThrows(WebApplicationException.class, () -> rest.getDaysAuditMetrics(request, 0));
```

## Fixtures

Private factory methods on the test class (`rangerService()`, `rangerServiceDef()`, `createTestAuditMetrics()`); JSON fixtures under
`src/test/java/org/apache/ranger/rest/importPolicy/` and `importRole/` (RAT-excluded).

## Running

```bash
mvn -pl security-admin test -Dtest=TestServiceREST -DfailIfNoTests=false
mvn -pl security-admin -DskipTests verify     # checkstyle + PMD + RAT + spotbugs, what CI runs
```

Patch tests: `src/test/java/org/apache/ranger/patch/TestPatch*_J100xx.java`. Frontend has no unit tests. SQL patches have no automated tests
(verify with `dev-support/ranger-docker`, see `security-admin-db`).
