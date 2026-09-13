---
name: security-admin
description: Architecture and coding patterns for the Ranger Admin Java backend (security-admin module) - JAX-RS rest -> biz -> service -> db/entity -> view layering, @PreAuthorize API access wiring, JPA entities/DAOs/named queries, RangerBaseModelService mapping, transaction logs, Java DB patches, Spring wiring, JUnit 5/Mockito tests. Use when adding or changing any Java under security-admin/src (REST endpoint, table-backed feature, validator, patch, metrics).
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

# Ranger Admin backend (`security-admin`)

Java root: `security-admin/src/main/java/org/apache/ranger/`. Spring 6 XML contexts + component scan, Jersey JAX-RS under `/service/*`,
EclipseLink JPA (`defaultPU`), Spring Security with `@PreAuthorize`. Java 17. Style rules and license header: see `ranger-conventions`.
SQL schema patches and installer scripts: see `security-admin-db`. React UI: see `security-admin-webapp`.

## Layers and who owns what

| Package | Owns | Never does |
|---|---|---|
| `rest/` `*REST` | HTTP contract: annotations, `@PreAuthorize`, arg defaults, `RangerPerfTracer`, exception -> `restErrorUtil.createRESTException` | touch `EntityManager`, business rules |
| `biz/` `*Mgr`, `*DBStore`, `RangerBizUtil` | orchestration across services/DAOs, authorization (`hasAdminPermissions`, `blockAuditorRoleUser`), versioning, cache invalidation, `TransactionTemplate` retries | JSON shaping |
| `service/` `*Service`, `*ServiceBase` | one aggregate: `mapViewToEntityBean`/`mapEntityToViewBean`, `validateForCreate/Update`, `searchFields`/`sortFields`, trx log (`XXTrxLogV2`) | cross-aggregate logic |
| `db/` `XX*Dao`, `RangerDaoManager(Base)` | named-query wrappers over `BaseDao<T>` | business logic |
| `entity/` `XX*` | JPA state, extends `XXDBBase` (create/update time, added/upd by) | logic |
| `view/` `VX*`, `Ranger*List` | Jackson DTOs; lists extend `VList` | logic |
| `security/` | context holders, `RangerPreAuthSecurityHandler`, `RangerAPIList`, `RangerAPIMapping`, auth filters | |
| `patch/` `Patch*_J10xxx` | one-off data migrations run by `db_setup.py` | |

Two parallel service hierarchies: `RangerBaseModelService<XX, Ranger*>` (newer, models from `agents-common`) and
`AbstractBaseResourceService<XX, VX*>` (legacy views). Follow whichever the neighbouring code uses.
Full flow walk-through: [references/request-flow.md](references/request-flow.md). Policy write -> version bump -> change log -> plugin download:
[references/policy-lifecycle-end-to-end.md](references/policy-lifecycle-end-to-end.md).

## Non-negotiables

- Logger: `private static final Logger LOG = LoggerFactory.getLogger(X.class);` (slf4j). Entry/exit debug lines with `{}` placeholders and no `isDebugEnabled` guard:
  `LOG.debug("==> ServiceREST.getService({})", id);` ... `LOG.debug("<== ServiceREST.getService({}): {}", id, ret);`
  Errors: `LOG.error("getService({}) failed", id, excp);`. No string concatenation in log calls.
- REST exceptions: `catch (WebApplicationException excp) { throw excp; } catch (Throwable excp) { LOG.error(...); throw restErrorUtil.createRESTException(excp.getMessage()); }`
- Every new API constant in `security/context/RangerAPIList.java` must also be added to a tab bucket in `RangerAPIMapping.java`.
  An unmapped API is open to every authenticated user.
- New JAX-RS resource classes must sit directly in `org.apache.ranger.rest` (Jersey scanning is non-recursive, see `WEB-INF/web.xml`).
- Beans are discovered by `<context:component-scan base-package="org.apache.ranger"/>`: annotate `@Component`/`@Service`, autowire by field. No XML bean entries.
- `RangerDaoManagerBase` is hand-maintained: add a `getXXFoo()` factory for each new DAO.
- Register every new entity in `src/main/resources/META-INF/persistence.xml`; named queries go in `META-INF/jpa_named_queries.xml` as `EntityName.methodName`.
- Null checks + `StringUtils`/`CollectionUtils` over `Optional` (checkstyle even bans static-importing `Optional` members).
- Import order: third-party, blank, `javax`, `java`, static imports last. No wildcard imports.

## Checklist: new REST endpoint

1. Constant in `RangerAPIList` (`"ClassName.methodName"`), added to the right `mapXxxWithAPIs()` in `RangerAPIMapping`.
2. Method on an existing `*REST` (or a new `@Path @Component @Scope("request") @Transactional(propagation = REQUIRES_NEW)` class).
3. `@GET|@POST|@PUT|@DELETE`, `@Path`, `@Produces("application/json")`, `@Consumes` for bodies,
   `@PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.X + "\")")`.
4. Body: entry log -> perf tracer -> validator (`validatorFactory.getServiceValidator(svcStore).validate(obj, Action.CREATE)`) -> biz call -> exception mapping -> exit log.
5. Module gate if relevant: `bizUtil.hasModuleAccess(RangerConstants.MODULE_TAG_BASED_POLICIES)`.
6. Test in `src/test/java/org/apache/ranger/rest/Test<Class>.java`.
Template with all pieces: [references/add-rest-endpoint.md](references/add-rest-endpoint.md).

## Checklist: new table-backed feature

SQL patch x5 vendors + `optimized/current` schema (see `security-admin-db`) -> `entity/XXFoo` (+ `XXFooBase` `@MappedSuperclass` if an
`WithAssignedId` variant is needed) -> `persistence.xml` -> `jpa_named_queries.xml` -> `db/XXFooDao extends BaseDao<XXFoo>` (`@Service`, swallow
`NoResultException`) -> `RangerDaoManagerBase.getXXFoo()` -> `service/RangerFooServiceBase` (search/sort fields, `trxLogAttrs`, mapping) +
`RangerFooService` -> `view/RangerFooList extends VList` -> biz store method -> REST. Recipe: [references/add-entity-dao-service.md](references/add-entity-dao-service.md).

## Tests

JUnit 5 + Mockito 5, no shared base class. Header: `@ExtendWith(MockitoExtension.class) @MockitoSettings(strictness = Strictness.LENIENT)`,
`@InjectMocks` the class under test, `@Mock(answer = Answers.RETURNS_DEEP_STUBS) RangerDaoManager daoManager`. Prefer descriptive names
(`testGetLatestAuditMetrics_Success`) over the legacy numbered `test10getServiceById`. Run one class:

```bash
mvn -pl security-admin test -Dtest=TestServiceREST -DfailIfNoTests=false
```

Details: [references/testing.md](references/testing.md).

## Reference model commits

- RANGER-3905 (`37888b1bf`) + RANGER-3917 (`2382a4817`): new `AuditMetricsREST` resource, models in `agents-common`, `RangerAPIList`/`RangerAPIMapping` wiring, Solr-backed service, tests, React tab. Best end-to-end template for a new read API.
- RANGER-5720 (`2ad565fe6`): schema-first change (SQL patch 078 across all vendors, no Java yet).

## References (load on demand)

- [references/request-flow.md](references/request-flow.md): GET/POST walk-throughs across layers, the two service hierarchies, transactions and post-commit hooks.
- [references/policy-lifecycle-end-to-end.md](references/policy-lifecycle-end-to-end.md): `createPolicy` -> ref tables -> async `ServiceVersionUpdater` -> `x_policy_change_log` -> `/policies/download`, Admin's own policy engine.
- [references/add-rest-endpoint.md](references/add-rest-endpoint.md): full endpoint template, `RangerAPIList`/`RangerAPIMapping`, web.xml, arg validation idioms.
- [references/add-entity-dao-service.md](references/add-entity-dao-service.md): entity/DAO/named-query/service/view recipe, `SearchField`/`SortField`, `trxLogAttrs`, `RangerAuditFields`.
- [references/rest-inventory.md](references/rest-inventory.md): every `*REST` class, its `@Path`, collaborators, `ServiceREST` endpoint families, `PublicAPIsv2` facade rule.
- [references/service-management.md](references/service-management.md): `ServiceMgr` plugin classloader, connection test / lookup timeouts via `TimedExecutor`, zone admin checks.
- [references/security-zones-and-gds.md](references/security-zones-and-gds.md): zone and GDS layers, entities, constants, `gds` def vs `_gds` service.
- [references/user-group-model.md](references/user-group-model.md): `x_portal_user` vs `x_user` (joined by name), `XUserMgr`/`UserMgr` split, user-store version bump, assignable roles.
- [references/security-and-authz.md](references/security-and-authz.md): `RangerContextHolder`, `RangerPreAuthSecurityHandler`, module/tab names, filters, Spring XML wiring.
- [references/java-patches.md](references/java-patches.md): `Patch*_J10xxx` template, `BaseLoader`, how `db_setup.py` runs them, `patch/cliutil`.
- [references/utilities.md](references/utilities.md): `common/` utility catalog, `MessageEnums`, constants, naming prefix table, DTO annotations, metrics package.
- [references/testing.md](references/testing.md): test conventions and mocking idioms.
