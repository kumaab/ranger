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

# Adding a REST endpoint

## 1. API constant and permission mapping

`security/context/RangerAPIList.java`, grouped per REST class, value is strictly `"ClassName.methodName"`:

```java
/** List of APIs for AuditMetricsREST */
public static final String GET_LATEST_AUDIT_METRICS = "AuditMetricsREST.getLatestAuditMetrics";
```

`security/context/RangerAPIMapping.java`: add the constant to the matching `mapXxxWithAPIs()` bucket
(`mapResourceBasedPoliciesWithAPIs`, `mapAuditWithAPIs`, `mapUGWithAPIs`, `mapPermissionsWithAPIs`, `mapKeyManagerWithAPIs`,
`mapTagBasedPoliciesWithAPIs`, `mapReportsWithAPIs`, `mapGDSWithAPIs`). The tab names are the `x_modules_master` module names.

`RangerPreAuthSecurityHandler.isAPIAccessible(name)` returns `true` for admins, `true` for any authenticated user when the API is unmapped,
else requires the user to hold one of the mapped modules. So an unmapped constant silently opens the endpoint.

## 2. Resource class

New class only when no existing `*REST` fits. It must live directly in `org.apache.ranger.rest`.

```java
@Path("audit")
@Component
@Scope("request")
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class AuditMetricsREST {
    private static final Logger LOG      = LoggerFactory.getLogger(AuditMetricsREST.class);
    private static final Logger PERF_LOG = RangerPerfTracer.getPerfLogger("rest.AuditMetricsREST");

    @Autowired
    RESTErrorUtil restErrorUtil;

    @Autowired
    RangerSearchUtil searchUtil;
```

## 3. Method template (from `ServiceREST.getService`)

```java
@GET
@Path("/services/{id}")
@Produces("application/json")
@PreAuthorize("@rangerPreAuthSecurityHandler.isAPIAccessible(\"" + RangerAPIList.GET_SERVICE + "\")")
public RangerService getService(@PathParam("id") Long id) {
    LOG.debug("==> ServiceREST.getService({})", id);

    RangerService    ret;
    RangerPerfTracer perf = null;

    try {
        if (RangerPerfTracer.isPerfTraceEnabled(PERF_LOG)) {
            perf = RangerPerfTracer.getPerfTracer(PERF_LOG, "ServiceREST.getService(serviceId=" + id + ")");
        }

        ret = svcStore.getService(id);
    } catch (WebApplicationException excp) {
        throw excp;
    } catch (Throwable excp) {
        LOG.error("getService({}) failed", id, excp);

        throw restErrorUtil.createRESTException(excp.getMessage());
    } finally {
        RangerPerfTracer.log(perf);
    }

    if (ret == null) {
        throw restErrorUtil.createRESTException(HttpServletResponse.SC_NOT_FOUND, "Not found", true);
    }

    LOG.debug("<== ServiceREST.getService({}): {}", id, ret);

    return ret;
}
```

Write endpoints add `@Consumes("application/json")`, run the validator first
(`validatorFactory.getServiceValidator(svcStore).validate(service, Action.CREATE)`), and gate with `bizUtil.hasAdminPermissions(...)`,
`bizUtil.blockAuditorRoleUser()`, or `bizUtil.hasModuleAccess(RangerConstants.MODULE_TAG_BASED_POLICIES)` as the neighbours do.

Alternative guards seen in tree: `@PreAuthorize("hasRole('ROLE_SYS_ADMIN')")`, `isAPISpnegoAccessible()`, `isAdminOrKeyAdminRole()`.

## 4. Argument validation and errors

```java
if (olderThanInDays <= 0 || olderThanInDays > maxAllowedDays) {
    throw restErrorUtil.createRESTException("Invalid parameter: olderThanInDays must be between 1 and " + maxAllowedDays, MessageEnums.INVALID_INPUT_DATA);
}
```

`RESTErrorUtil` overloads: `createRESTException(String)`, `(String, MessageEnums)`, `(int status, String msg, boolean close)`,
`(String, MessageEnums, Long id, String fieldName, String logMsg)`, `generateRESTException(VXResponse)`, `createGrantRevokeRESTException(...)`.
`MessageEnums` values: `DATA_NOT_FOUND`, `OPER_NO_PERMISSION`, `INVALID_INPUT_DATA`, `ERROR_CREATING_OBJECT`, `OPER_NOT_ALLOWED_FOR_STATE`, ...

## 5. Paged list responses

Search: `SearchFilter filter = searchUtil.getSearchFilter(request, xService.sortFields);` then a `*List extends VList` view (`@JsonInclude(NON_EMPTY)`)
that overrides `getListSize()` and `getList()`. New filter keys are constants on `agents-common` `org.apache.ranger.plugin.util.SearchFilter`.

## 6. Config

Prefer `RangerAdminConfig.getInstance().getInt("ranger.audit.metrics.max.supported.days", 90)` for new keys; legacy code uses static `PropertiesUtil.getIntProperty(...)`.
Declare new keys in `security-admin/src/main/resources/conf.dist/ranger-admin-default-site.xml` (defaults) and, if operator-tunable, `ranger-admin-site.xml`.

## 7. Test

`src/test/java/org/apache/ranger/rest/Test<Class>REST.java`: `@ExtendWith(MockitoExtension.class)`, `@InjectMocks` the resource, `@Mock` collaborators,
descriptive method names (`testGetLatestAuditMetrics_Success`, `..._InvalidDays`). See [testing.md](testing.md).

## Read-only feature that skips `biz`

`AuditMetricsREST` -> `solr/SolrAccessAuditsService` -> `solr/SolrAuditMetricsHelper` (no JPA, data is in Solr). Skipping `biz` is acceptable when there is
no DB aggregate to orchestrate; still keep REST free of query-building logic.
