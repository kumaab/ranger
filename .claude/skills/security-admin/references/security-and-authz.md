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

# Security, authorization, and Spring wiring

## Request context (`security/context/`)

- `RangerContextHolder`: two `ThreadLocal`s, `RangerSecurityContext` (user session + `RequestContext`) and `RangerAdminOpContext`
  (`bulkModeContext`, `createPrincipalsIfAbsent`). Populated by `security/web/filter/RangerSecurityContextFormationFilter` (last filter in the chain).
- `common/ContextUtil`: `getCurrentUserSession()`, `getCurrentUserId()`, `getCurrentUserLoginId()`, `isBulkModeContext()`.
- `common/UserSessionBase`: `isUserAdmin()`, `isKeyAdmin()`, `isAuditUserAdmin()`, `getRangerUserPermission().getUserPermissions()` (module names).

## API access control

`RangerPreAuthSecurityHandler` (`@Component("rangerPreAuthSecurityHandler")`):

```java
public boolean isAPIAccessible(String methodName) {
    UserSessionBase userSession = ContextUtil.getCurrentUserSession();

    if (userSession == null) { return false; }
    if (userSession.isUserAdmin()) { return true; }

    Set<String> associatedTabs = rangerAPIMapping.getAssociatedTabsWithAPI(methodName);

    if (CollectionUtils.isEmpty(associatedTabs)) { return true; }   /* unmapped API is open to any authenticated user */
    if (associatedTabs.contains(RangerAPIMapping.TAB_PERMISSIONS) && userSession.isAuditUserAdmin()) { return true; }

    return isAPIAccessible(associatedTabs);                          /* refreshPermissionsIfNeeded + intersect with user modules */
}
```

Failure throws `restErrorUtil.generateRESTException(...)` with 403 and "User is not allowed to access the API".

Tab names in `RangerAPIMapping` equal module names in `x_modules_master` (`entity/XXModuleDef`) and `RangerConstants.MODULE_*`:
`Resource Based Policies`, `Users/Groups`, `Reports`, `Audit`, `Permissions`, `Key Manager`, `Tag Based Policies`, `Security Zone`, `Governed Data Sharing`.
Assignable roles (`RangerConstants.VALID_USER_ROLE_LIST`): `ROLE_SYS_ADMIN`, `ROLE_KEY_ADMIN`, `ROLE_USER`, `ROLE_ADMIN_AUDITOR`, `ROLE_KEY_ADMIN_AUDITOR`.
`ROLE_ADMIN` and `ROLE_OTHER` are constants only. Zone-scoped checks: `ServiceMgr.isZoneAdmin/isZoneAuditor`.
Permissions are cached per session and refreshed by `SessionMgr.refreshPermissionsIfNeeded` after `SESSION_UPDATE_INTERVAL_IN_MILLIS`.

Biz-level guards in `RangerBizUtil`: `hasAdminPermissions(objName)`, `hasKMSPermissions(objName, implClass)`, `blockAuditorRoleUser()`,
`hasModuleAccess(moduleName)`, `hasAccess(xxObject, xxUser)`, `isAdmin()`, `isKeyAdmin()`, `isAuditAdmin()`, `checkUserAccessible(...)`.

## Authentication

`security/handler/RangerAuthenticationProvider implements AuthenticationProvider` dispatches on `ranger.authentication.method`
(`getLdapAuthentication`, `getADBindAuthentication`, `getLdapBindAuthentication`, `getJDBCAuthentication`, `getSSOAuthentication`).
Filters in `security/web/filter/`: `RangerKRBAuthenticationFilter`, `RangerSSOAuthenticationFilter`, `RangerJwtAuthFilter`/`RangerJwtAuthWrapper`,
`RangerHeaderPreAuthFilter`, `RangerCSRFPreventionFilter`, `RangerMDCFilter`, `RangerUsernamePasswordAuthenticationFilter`, `RangerSecurityContextFormationFilter`.
Chain order in `src/main/resources/conf.dist/security-applicationContext.xml` (which also enables `@PreAuthorize` via
`<security:global-method-security pre-post-annotations="enabled" />`):

```
PRE_AUTH_FILTER                -> headerPreAuthFilter          (RangerHeaderPreAuthFilter)
after BASIC_AUTH_FILTER        -> ssoAuthenticationFilter      (RangerSSOAuthenticationFilter)
before SERVLET_API_SUPPORT     -> rangerJwtAuthWrapper         (RangerJwtAuthWrapper)
after SERVLET_API_SUPPORT      -> krbAuthenticationFilter      (RangerKRBAuthenticationFilter)
after REMEMBER_ME_FILTER       -> CSRFPreventionFilter         (RangerCSRFPreventionFilter)
FORM_LOGIN_FILTER              -> customUsernamePasswordAuthenticationFilter
LAST                           -> userContextFormationFilter   (RangerSecurityContextFormationFilter)
```

`RangerMDCFilter` is a bean but is wired in `web.xml`, not in this chain. CSRF: `ranger.rest-csrf.enabled` (default **true**),
`ranger.rest-csrf.custom-header` (`X-XSRF-HEADER`), `ranger.rest-csrf.methods-to-ignore`, `ranger.rest-csrf.browser-useragents-regex`; the UI fetches
them from `GET /plugins/csrfconf`.

## Spring wiring

- `src/main/webapp/WEB-INF/web.xml`: `contextConfigLocation` = `META-INF/applicationContext.xml`, `WEB-INF/classes/conf/security-applicationContext.xml`,
  `META-INF/scheduler-applicationContext.xml`; `springSecurityFilterChain` via `DelegatingFilterProxy`; Jersey servlet on `/service/*`.
- `src/main/webapp/META-INF/applicationContext.xml`: `default-lazy-init="true"`, `<context:component-scan base-package="org.apache.ranger"/>`,
  `<tx:annotation-driven/>`, `defaultEntityManagerFactory` (EclipseLink, unit `defaultPU`), `transactionManager` (`JpaTransactionManager`),
  `defaultDataSource` (Hikari), `propertyConfigurer` (`PropertiesUtil`, loads `core-site.xml`, `ranger-admin-default-site.xml`, `ranger-admin-site.xml`),
  `CustomScopeConfigurer` registering `request`/`session` scopes.
- `src/main/resources/META-INF/persistence.xml`: unit `defaultPU`, `jpa_named_queries.xml` mapping file, one `<class>` per entity, `eclipselink.weaving=false`.
- Other contexts: `META-INF/asynctask-applicationContext.xml`, `scheduler-applicationContext.xml`, `infinispan-cache-config.xml`.

Never add `<bean>` entries for your own classes. Annotate and autowire.

## Standalone (CLI) context

`util/CLIUtil.getBean(Class)` boots `applicationContext.xml` + `security-applicationContext.xml` + `asynctask-applicationContext.xml` outside Tomcat and,
for classes whose name starts with `Patch`, injects an admin `UserSessionBase` so patches bypass `@PreAuthorize`.
