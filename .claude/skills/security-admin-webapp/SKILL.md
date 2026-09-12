---
name: security-admin-webapp
description: Conventions for the Ranger Admin React UI under security-admin/src/main/webapp/react-webapp (React 18, react-router 6 HashRouter, react-bootstrap 5, react-final-form, react-table v7, axios via fetchApi). Use when adding or changing a page, list table, form, modal, route, sidebar entry, or utility in any .jsx/.js file there.
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

# Ranger Admin React UI

Root: `security-admin/src/main/webapp/react-webapp/`. All paths below are relative to it unless prefixed.
The legacy login page (`../login.jsp`, `../scripts/prelogin/`, `../styles/`) is jQuery and separate from React.

## Non-negotiables

- Every `.js`/`.jsx` file starts with the ASF license block comment (see `ranger-conventions`).
- Function components + hooks for new code. Class components exist only in ~15 legacy files; do not add more.
  A class that needs routing wraps with `withRouter` from `Hooks/withRouter`.
- Import through webpack aliases: `Components/…`, `Views/…`, `Utils/…`, `Hooks/…`, `Images/…`. Never deep-relative paths.
- lodash: named imports only (`import { isEmpty, sortBy } from "lodash"`).
- No TypeScript, no PropTypes (except vendored `components/structured-filter`), no i18n, no SCSS, no CSS modules, no Redux/Context store.
- Interactive elements carry `data-id` and `data-cy` attributes.
- Format with Prettier using the repo `.prettierrc` (80 cols, double quotes, semicolons, no trailing commas). Do not commit
  `package.json`/`package-lock.json` churn from installing Prettier locally.
- There are no JS unit tests in the repo (`npm test` is a stub). Verify by running the dev server against a local admin.

## Where things live

| Path | Purpose |
|---|---|
| `src/index.jsx` | Entry; the only place global CSS is imported |
| `src/App.jsx` | axios `baseURL` = `<origin>/service/`, profile + service-def bootstrap, **the entire route table** (HashRouter, `React.lazy`) |
| `src/views/Layout.jsx` | Sidebar, footer, idle-timeout modal, and the centralized 401/404 gate via `hasAccessToPath` |
| `src/views/<Feature>/` | Page components; stateful pages keep `action.js` + `reducer.js` siblings |
| `src/components/` | View-agnostic UI: `XATableLayout.jsx`, `CommonComponents.jsx`, `Editable.jsx`, `structured-filter/` |
| `src/utils/XAUtils.js` | Role helpers, `fetchSearchFilterParams`, `parseSearchFilter`, `serverError`, `commonBreadcrumb`, `InfoIcon` |
| `src/utils/XAEnums.js` | Constant maps incl. `PathAssociateWithModule` (route -> Ranger module) and `RegexValidation` |
| `src/utils/XAMessages.js` | User-facing strings and validation help text |
| `src/utils/fetchAPI.js` | The axios wrapper; adds CSRF header, routes 419/403/404/400 to pages |
| `src/utils/appState.js` | Module-scoped store: `getUserProfile()`, `getServiceDef()` |
| `src/hooks/` | `withRouter`, `usePrompt` (unsaved-changes), policy-condition hooks |
| `src/styles/style.css` | All custom CSS, grouped under `/* Section */` banners |
| `config/` | webpack common/dev/prod; dev server on 8888 proxies `/service`, `/login`, `/logout` to `localhost:6080` |

## Adding a page: the minimum edit set

1. Create `src/views/<Feature>/<Page>.jsx` (+ `action.js`/`reducer.js` if it has table/modal state).
2. `src/App.jsx`: `const Page = lazy(() => import("Views/<Feature>/<Page>"));` and a nested `<Route>`.
3. `src/utils/XAEnums.js`: add the path under the right key of `PathAssociateWithModule`. Skipping this yields a 404 even though the route exists.
4. `src/views/SideBar/SideBarBody.jsx`: add a `NavLink`, gated with `hasAccessToTab("<Module>")` or `isSystemAdmin()`.
5. `src/utils/XAUtils.js`: add a `links` entry if the page needs `commonBreadcrumb`.
6. `src/styles/style.css`: new rules under an existing or new `/* Section */` banner.

Template commit for a new tab + page: RANGER-3917 (`2382a4817`, Audit > Metrics). It touched exactly the files above plus
`views/AuditEvent/AuditLayout.jsx` (new `<Tab>`), `action.js`/`reducer.js` (new modal flags), and two new components.

## Core idioms (one-liners; details in references)

- **Data fetch**: `await fetchApi({ url: "xusers/users", params })`, URL relative to `/service/`. Arrays serialize with
  `qs.stringify(params, { arrayFormat: "repeat" })`. Errors: `catch (error) { serverError(error); console.error(...) }`.
  Pass `skipNavigate: true` to opt out of the automatic error-page redirects.
- **Roles**: `isSystemAdmin()`, `isKeyAdmin()`, `isAuditor()`, `isKMSAuditor()`, `isUser()`, `hasAccessToTab(name)`. Idiom: `const isKMSRole = isKeyAdmin() || isKMSAuditor();`
- **List page**: `useReducer` + `useSearchParams` + `StructuredFilter` + `XATableLayout` with a `useCallback` fetcher
  `({ pageSize, pageIndex, gotoPage })`. Re-fetch trigger is `refreshTableData: moment.now()`. See [references/list-page.md](references/list-page.md).
- **Form page**: `react-final-form` `<Form validate={validateForm} keepDirtyOnReinitialize>` with render-prop `<Field>`s,
  Bootstrap `Row/Col` grid, `InfoIcon`, `scrollToError`, `PromptDialog` via `usePrompt`. See [references/form-page.md](references/form-page.md).
- **Cross-route state**: `<Outlet context={...}>` + `useOutletContext()` (e.g. `AuditLayout` shares `services`). Never a new Context provider.
- **Modals**: inline `react-bootstrap` `<Modal>` per view, visibility as reducer flags; long ops show `<BlockUi isUiBlock={state.blockUi} />`.
- **Toasts**: `react-toastify`; de-dupe with `toastId = useRef(null)` + `toast.dismiss(toastId.current)`.
- **Icons**: Font Awesome 4 class names (`fa fa-trash`), SVGs from `Images/`. Not `react-icons`.

## References (load on demand)

- [references/list-page.md](references/list-page.md): full list/table recipe, `XATableLayout` props, search-filter plumbing, audit-tab variant.
- [references/form-page.md](references/form-page.md): form recipe, Field markup, selects, validation, submit/navigate, unsaved-changes guard.
- [references/routing-and-access.md](references/routing-and-access.md): App.jsx routing, Layout gate, `PathAssociateWithModule`, session/CSRF, `fetchAPI.js` behaviour.
- [references/utils-catalog.md](references/utils-catalog.md): catalog of exports in `XAUtils.js`, `XAEnums.js`, `XAMessages.js`, `CommonComponents.jsx`.
- [references/toolchain.md](references/toolchain.md): package.json, webpack, babel, Maven `frontend-maven-plugin`, dev-server workflow, Prettier.
