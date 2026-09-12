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

# Utilities, constants, naming, DTO annotations, metrics

## `common/` catalog

| Class | Notes |
|---|---|
| `RangerConstants` (extends `RangerCommonEnums`) | roles, `MODULE_*`, `RANGER_ADMIN_SUPER_USERS/GROUPS` |
| `RangerCommonEnums` | int enums `USER_EXTERNAL`, `ACT_STATUS_DISABLED`, `GROUP_EXTERNAL`, ... |
| `AppConstants` | `CLASS_TYPE_XA_SERVICE`, `CLASS_TYPE_XA_USER`, ... (trx-log class types) |
| `RESTErrorUtil` (`@Component`) | `createRESTException(...)` overloads, `generateRESTException(VXResponse)` |
| `MessageEnums` | `(rbKey, messageDesc)` -> `VXMessage`; `DATA_NOT_FOUND`, `OPER_NO_PERMISSION`, `INVALID_INPUT_DATA`, ... |
| `StringUtil` (`@Component`) | `isEmpty`, `validateEmail`, `getValidUserName`, `toString(Collection)` |
| `PropertiesUtil` | static `getProperty/getIntProperty/getLongProperty/getBooleanProperty` |
| `RangerConfigUtil` (`@Component`) | `resourcenamemap.properties` lookups |
| `JSONUtil` (`@Component`) | Jackson helpers (`writeObjectAsString`, `readMapToString`, ...) |
| `RangerSearchUtil` / `SearchUtil` | `getSearchFilter(request, sortFields)`, `extractCommonCriteriasForFilter`, `constructSortClause` |
| `SearchField`, `SortField`, `SearchCriteria` | search registries; `SearchFilter` keys live in `agents-common` |
| `ContextUtil` | current session/user/bulk-mode |
| `DateUtil` | `getUTCDate()` for all timestamps |
| `GUIDUtil` (`@Component`) | `genGUID()`, `genLong()` |
| `RangerValidatorFactory` (`@Service`) | `getServiceValidator(store)`, `getPolicyValidator(store)`, `getSecurityZoneValidator(...)`, `getRangerRoleValidator(...)` |
| `RangerPerfTracer` (from `common-utils`) | `getPerfLogger`, `isPerfTraceEnabled`, `getPerfTracer`, `log` |

Validators themselves (`RangerServiceValidator`, `RangerPolicyValidator`, `RangerServiceDefValidator`, `RangerSecurityZoneValidator`, `RangerRoleValidator`)
live in `agents-common/.../plugin/model/validation/`. `security-admin/.../validation/` holds only the GDS validators (`RangerGdsValidator`, autowired directly).

## Naming prefixes

| Prefix | Meaning | Example |
|---|---|---|
| `XX` | JPA entity | `XXService`, `XXPolicy`, `XXTrxLogV2` |
| `XX…Base` | `@MappedSuperclass` with columns | `XXServiceBase` |
| `XX…WithAssignedId` | client-supplied PK variant (import) | `XXServiceWithAssignedId` |
| `XX…Dao` | DAO | `XXServiceDao` |
| `VX` / `VX…List` | legacy DTO / paged list | `VXUser`, `VXUserList` |
| `Ranger*` / `Ranger*List` | model from `agents-common` / paged list | `RangerService`, `RangerServiceList` |
| `X…Service`, `Ranger…Service`, `…ServiceBase` | service layer | `XUserService`, `RangerServiceServiceBase` |
| `…Mgr`, `…MgrBase`, `…DBStore` | biz layer | `XUserMgr`, `ServiceDBStore`, `GdsDBStore` |
| `…REST` | JAX-RS resource | `ServiceREST` |
| `Patch…_J10xxx` | Java patch | `PatchForOzoneServiceDefAssumeRoleUpdate_J10065` |

## DTO annotations

```java
@JsonAutoDetect(getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE, fieldVisibility = Visibility.ANY)
@JsonInclude(JsonInclude.Include.NON_NULL)      /* NON_EMPTY on newer list DTOs */
@JsonIgnoreProperties(ignoreUnknown = true)     /* on VX* concrete objects */
public class VXUser extends VXDataObject implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
```

## Metrics (`metrics/`)

`RangerAdminMetricsWrapper` (`@Component`) registers each `metrics/source/RangerAdminMetricsSource*` bean as a `RangerMetricsSourceWrapper`
into `RangerMetricsSystemWrapper` (module `ranger-metrics`). `RangerMetricsFetcher` supplies DB counts. Exposed by `rest/MetricsREST` (`/metrics/status`,
`/metrics/prometheus`, `/metrics/json`). To add a metric: new `RangerAdminMetricsSourceFoo extends RangerAdminMetricsSourceBase`, autowire it in the wrapper, register.

## Config sources

`RangerAdminConfig.getInstance()` (preferred in new code) and static `PropertiesUtil`. Declare defaults in `conf.dist/ranger-admin-default-site.xml`;
operator-tunable keys additionally in `conf.dist/ranger-admin-site.xml` (the installer only rewrites existing `<property>` names).
