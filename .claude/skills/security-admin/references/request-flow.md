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

# Request flow through the layers

Java root `security-admin/src/main/java/org/apache/ranger/`.

## Read: `GET /service/plugins/services`

1. `rest/ServiceREST.getServices(HttpServletRequest)`: builds `SearchFilter` via `searchUtil.getSearchFilter(request, svcService.sortFields)`,
   wraps in `RangerPerfTracer`, calls the store, converts `PList` to `RangerServiceList`, maps exceptions.
2. `biz/ServiceDBStore.getPaginatedServices(SearchFilter)`:
   ```java
   LOG.debug("==> ServiceDBStore.getPaginatedServices()");
   RangerServiceList serviceList = svcService.searchRangerServices(filter);
   if (StringUtils.isEmpty(filter.getParam("serviceNamePartial"))) {
       predicateUtil.applyFilter(serviceList.getServices(), filter);
   }
   LOG.debug("<== ServiceDBStore.getPaginatedServices()");
   return new PList<>(serviceList.getServices(), serviceList.getStartIndex(), ...);
   ```
3. `service/RangerServiceServiceBase.searchRangerServices(SearchFilter)` -> `RangerBaseModelService.searchResources(filter, searchFields, sortFields, retList)`,
   then per-row `bizUtil.hasAccess(xSvc, null)` and `populatePageList(...)`.
4. `db/XXServiceDao extends BaseDao<XXService>` runs JPQL built from `"SELECT obj FROM org.apache.ranger.entity.XXService obj "` plus search/sort clauses.
5. `entity/XXService` (`@Table(name = "x_service")`).
6. `view/RangerServiceList extends VList` is serialized.

## Write: `POST /service/plugins/services`

`ServiceREST.createService` -> `validatorFactory.getServiceValidator(svcStore).validate(service, Action.CREATE)` ->
`bizUtil.hasAdminPermissions("Services")` / `hasKMSPermissions` / `blockAuditorRoleUser()` -> `svcStore.createService(service)` ->
`svcService.create(service)` (`preCreate` -> `validateForCreate` -> `mapViewToEntityBean` -> `getDao().create()` -> `postCreate`/`populateViewBean`) ->
`rangerAuditFields.populateAuditFields(child, parent)` for child rows -> `onObjectChange(...)` writes `XXTrxLogV2` rows.

## Two service hierarchies

| Base | View type | Used by | Search entry |
|---|---|---|---|
| `RangerBaseModelService<T extends XXDBBase, V extends RangerBaseModelObject>` -> `RangerAuditedModelService` | `Ranger*` models from `agents-common` | `RangerServiceService`, `RangerPolicyService`, `RangerSecurityZoneService`, GDS services | `search<Type>s(SearchFilter)` on the `*ServiceBase` |
| `AbstractBaseResourceService<T extends XXDBBase, V extends VXDataObject>` -> `AbstractAuditedResourceService` | legacy `VX*` | `XUserService`, `XGroupService`, `XPortalUserService` | `search<Type>s(SearchCriteria)` |

Pick the one your neighbouring aggregate uses. `*Mgr` classes come in pairs `FooMgrBase` (CRUD scaffolding) + `FooMgr` (`XUserMgr`, `AssetMgr`, `UserMgr`, `XAuditMgr`).

## Transactions

- REST classes: class-level `@Transactional(propagation = Propagation.REQUIRES_NEW)`. `RangerHealthREST` opts out per method with `NOT_SUPPORTED`.
- Biz methods needing their own boundary: `@Transactional(readOnly = false, propagation = Propagation.REQUIRED)`.
- Retry-in-new-transaction blocks use `TransactionTemplate`:
  ```java
  TransactionTemplate txTemplate = new TransactionTemplate(txManager);
  txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  txTemplate.execute((TransactionCallback<Void>) status -> { ...; return null; });
  ```
  Examples: `biz/XUserMgr`, `biz/ServiceDBStore`, `biz/RangerPolicyRetriever`, `biz/RangerTagDBRetriever`.
- Post-commit work: `common/db/RangerTransactionSynchronizationAdapter` (`executeOnTransactionCommit(Runnable)`, `executeOnTransactionCompletion`, `executeAsyncOnTransactionComplete`). Runs inline if no transaction is active.
- Bulk mode: `RangerBizUtil.isBulkMode()` (from `RangerAdminOpContext`) suppresses `em.flush()` in `BaseDao.create/update`.

## Audit columns

`common/db/JPABeanCallbacks` (`@PrePersist`) fills `updateTime` and back-fills `addedByUserId`/`updatedByUserId` from the current session.
`service/RangerAuditFields.populateAuditFields(child, parent)` copies the parent's stamps onto child rows; `populateAuditFieldsForCreate(obj)` uses `ContextUtil.getCurrentUserId()`.

## Where the URL prefix comes from

`security-admin/src/main/webapp/WEB-INF/web.xml` maps the Jersey servlet to `/service/*` and scans packages `org.apache.ranger.rest,org.apache.ranger.common`
non-recursively. Spring contexts: `META-INF/applicationContext.xml` (component scan, JPA, tx, Hikari), `conf/security-applicationContext.xml`
(security filter chain, `global-method-security pre-post-annotations="enabled"`), `META-INF/scheduler-applicationContext.xml`.
