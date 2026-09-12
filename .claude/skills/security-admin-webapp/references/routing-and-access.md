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

# Routing, access control, session

## Routes: `src/App.jsx`

- `HashRouter`, so URLs are `#/…`. One top-level `<Route path="/" element={<LayoutComp />}>` with nested routes.
- Every page is `React.lazy(() => import("Views/…"))`, wrapped in `<Suspense fallback={<Loader />}>` inside `<ErrorBoundary history={history}>`.
- `App` (class) sets `axios.defaults.baseURL = <origin + context path> + "/service/"` and bootstraps `users/profile` + `plugins/definitions`
  (and `plugins/definitions/name/gds`) into `Utils/appState`.

```jsx
const MetricsLogs = lazy(() => import("Views/AuditEvent/Metrics/MetricsLogs"));
...
<Route path="/reports/audit" element={<AuditLayout />}>
  <Route path="bigData" element={<AccessLogs />} />
  <Route path="metric" element={<MetricsLogs />} />
</Route>
```

## The access gate: `src/views/Layout.jsx`

There are no per-route guards. `Layout` matches `location.pathname` against every path listed in `PathAssociateWithModule`
(`Utils/XAEnums`), then checks `hasAccessToPath(pathname)`:

```jsx
{matchRoutes(flatMap(values(PathAssociateWithModule)).map((val) => ({ path: val })), location.pathname)
  ? (hasAccessToPath(location.pathname)
      ? <Suspense fallback={<Loader />}><Outlet /></Suspense>
      : <ErrorPage errorCode="401" />)
  : <ErrorPage errorCode="404" />}
```

`PathAssociateWithModule` keys are Ranger module names (`Audit`, `Users/Groups`, `Security Zone`, `Permission`, `Profile`, ...),
matching what `users/profile` returns in `userPermList`. A route missing from this map renders 404.

`Layout` also assigns `navigateTo.navigate = useNavigate()` so non-component code (`fetchAPI.js`) can redirect, redirects `/` to
`getLandingPageURl()`, and runs the `react-idle-timer` session-expiry modal (`userProfile.configProperties.inactivityTimeout`, default 900s).

## Role helpers (`Utils/XAUtils`)

```js
export const isSystemAdmin = () => LoginUser("ROLE_SYS_ADMIN");
export const isKeyAdmin    = () => LoginUser("ROLE_KEY_ADMIN");
export const isUser        = () => LoginUser("ROLE_USER");
export const isAuditor     = () => LoginUser("ROLE_ADMIN_AUDITOR");
export const isKMSAuditor  = () => LoginUser("ROLE_KEY_ADMIN_AUDITOR");
export const hasAccessToTab  = (tabName)  => /* module in userPermList/groupPermissions */;
export const hasAccessToPath = (pathName) => /* matchRoutes against the user's modules */;
```

Gate UI inline: `{isSystemAdmin() && (<Button …>Add New User</Button>)}`; sidebar items use `hasAccessToTab("Audit")`.

## `Utils/fetchAPI.js`

`fetchApi(axiosConfig, otherConf)`:

- Adds `X-Requested-With: XMLHttpRequest`; adds the CSRF header (token from `localStorage.csrfToken`, fetched by `fetchCSRFConf()` from `plugins/csrfconf`).
- `otherConf.cancelRequest === true` attaches a `CancelToken` and exposes `otherConf.source`.
- Error routing, in order: 419 -> toast + `login.jsp?sessionTimeout=true`; if `config.skipNavigate` rethrow; 400 with
  `DATA_NOT_FOUND`/`INVALID_INPUT_DATA` -> `/dataNotFound`; 404 -> `/pageNotFound`; 403 -> `/forbidden`; then rethrow.
- Views call it directly. There is no service/repository layer; keep it that way.

## Session / global state (`Utils/appState.js`)

Module-scoped object, not React state:

```js
getUserProfile() / setUserProfile(profile)
getServiceDef()  / setServiceDef(serviceDef, tagServiceDef, gdsServiceDef, allServiceDefs)
```

Consumers clone: `const { allServiceDefs } = cloneDeep(getServiceDef());`.
`localStorage` holds only UI preferences: `csrfToken`, `showHideTableCol`, `newDataAdded`, and per-audit-tab saved filters.

## Breadcrumbs

`commonBreadcrumb(["Users", "UserEdit"], params.userID)` renders `views/CustomBreadcrumb.jsx` from the `links` map in `XAUtils.js`
(entries are literal `{ href, text }` or functions of the options argument). Add a key there for a new page.

## Tabbed layouts

`views/AuditEvent/AuditLayout.jsx`, `views/Home.jsx`, `views/UserGroupRoleListing/UserGroupRoleListing.jsx` derive the active tab from
`location.pathname`, navigate on `onSelect`, and pass shared data down with `<Outlet context={…} />`. `views/PolicyListing/PolicyFormContext.jsx`
does the same for `{ serviceDetails, serviceCompDetails, policyData, serviceDefs }`.
