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

# List page recipe

Canonical file: `src/views/UserGroupRoleListing/users_details/UserListing.jsx`.
Audit-tab variant: `src/views/AuditEvent/Metrics/MetricsLogs.jsx` (newest, RANGER-3917). Also `AuditEvent/Access/AccessLogs.jsx`, `SecurityZone/ZoneListing.jsx`.

## Skeleton

```jsx
function UserListing() {
  const [state, dispatch] = useReducer(reducer, INITIAL_STATE);   /* sibling reducer.js + action.js */
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const selectedRows = useRef([]);
  const toastId = useRef(null);

  /* 1. URL -> filter state, once per location.search */
  useEffect(() => {
    const { searchFilterParam, defaultSearchFilterParam, searchParam } =
      fetchSearchFilterParams("users", searchParams, searchFilterOptions);
    dispatch({ type: ACTIONS.SET_SEARCH_FILTER_PARAMS, searchFilterParams: searchFilterParam,
               defaultSearchFilterParams: defaultSearchFilterParam, contentLoader: false });
    setSearchParams(searchParam, { replace: true });
  }, [location.search]);

  /* 2. fetcher signature is fixed by XATableLayout */
  const fetchUsers = useCallback(async ({ pageSize, pageIndex, gotoPage }) => {
    dispatch({ type: ACTIONS.SET_TABLE_LOADER, loader: true });
    const params = { ...state.searchFilterParams, pageSize, startIndex: pageIndex * pageSize };
    try {
      const resp = await fetchApi({ url: "xusers/users", params,
        paramsSerializer: (p) => qs.stringify(p, { arrayFormat: "repeat" }) });
      dispatch({ type: ACTIONS.SET_TABLE_DATA, tableListingData: resp.data.vXUsers,
                 totalCount: resp.data.totalCount, pageCount: Math.ceil(resp.data.totalCount / pageSize),
                 currentPageIndex: pageIndex, currentPageSize: pageSize, resetPage: { page: gotoPage } });
    } catch (error) {
      serverError(error);
      console.error(`Error occurred while fetching User list! ${error}`);
    }
    dispatch({ type: ACTIONS.SET_TABLE_LOADER, loader: false });
  }, [state.refreshTableData]);

  /* 3. react-table v7 columns */
  const columns = React.useMemo(() => [
    { Header: "User Name", accessor: "name", Cell: (rawValue) => rawValue.value || "--", width: 100 },
    { Header: "Actions", disableSortBy: true, Cell: ({ row }) => (isSystemAdmin() && <Button size="sm" data-cy="edit" ... />) }
  ], []);

  /* 4. filter change -> state + URL + reset page */
  const updateSearchFilter = (filter) => {
    const { searchFilterParam, searchParam } = parseSearchFilter(filter, searchFilterOptions);
    dispatch({ type: ACTIONS.SET_SEARCH_FILTER_PARAMS, searchFilterParams: searchFilterParam,
               refreshTableData: moment.now() });
    setSearchParams(searchParam, { replace: true });
    if (typeof state.resetPage?.page === "function") state.resetPage.page(0);
  };

  return state.contentLoader ? <Loader /> : (
    <>
      <BlockUi isUiBlock={state.blockUi} />
      <Row className="mb-4">
        <Col sm={8}>
          <StructuredFilter key="user-listing-search-filter" placeholder="Search for your users..."
            options={sortBy(searchFilterOptions, ["label"])} onChange={updateSearchFilter}
            defaultSelected={state.defaultSearchFilterParams} />
        </Col>
        {isSystemAdmin() && (<Col sm={4} className="text-end">
          <Button variant="primary" size="sm" data-id="addNewUser" data-cy="addNewUser"
            onClick={() => navigate("/user/create")}>Add New User</Button></Col>)}
      </Row>
      <XATableLayout data={state.tableListingData} columns={columns} fetchData={fetchUsers}
        totalCount={state.totalCount} pageCount={state.pageCount}
        currentpageIndex={state.currentPageIndex} currentpageSize={state.currentPageSize}
        pagination loading={state.loader}
        rowSelectOp={(isSystemAdmin() || isKeyAdmin()) && { position: "first", selectedRows }}
        getRowProps={(row) => ({ className: row.values.isVisible == 0 && "row-inactive" })} />
      <Modal show={state.showDeleteModal} onHide={toggleDeleteModal}>...</Modal>
    </>
  );
}
```

Imports used above:

```js
import StructuredFilter from "Components/structured-filter/react-typeahead/tokenizer";
import XATableLayout from "Components/XATableLayout";
import { Loader, BlockUi, scrollToNewData } from "Components/CommonComponents";
import { fetchApi } from "Utils/fetchAPI";
import { isSystemAdmin, isKeyAdmin, serverError, parseSearchFilter, fetchSearchFilterParams } from "Utils/XAUtils";
```

## `searchFilterOptions`

Array of `{ category, label, urlLabel, type, options? }`. `type` is `"text"` or `"textoptions"` (dropdown via `options: () => [{ value, label }]`).
`category` is the server query param, `urlLabel` is the key that appears in the browser URL.
Audit tabs persist the current filter with `localStorage.setItem("<tab>", JSON.stringify(searchParam))` and re-read it in `fetchSearchFilterParams`.

## `XATableLayout` props (`src/components/XATableLayout.jsx`)

`columns, data, loading, fetchData, showPagination=true, pageCount, currentpageIndex, currentpageSize, totalCount, defaultSort=[],
rowSelectOp ({ position, selectedRows }), columnHide ({ isVisible, tableName }), columnSort, clientSideSorting, columnResizable, getRowProps`.

- `manualPagination: true`; the table calls `fetchData({ pageIndex, pageSize, gotoPage, sortBy })` whenever those change.
- Page sizes 25/50/75/100; pagination bar renders only when `totalCount > 25`.
- Server sort strings: `getTableSortBy(sortBy)` -> `"col1,col2"`, `getTableSortType(sortBy)` -> `"asc,desc"`.
- Column visibility persists to `localStorage["showHideTableCol"][tableName]`.
- Selected rows land in `rowSelectOp.selectedRows.current`.

## Reducer conventions (`action.js` / `reducer.js`)

`action.js` exports an `ACTIONS` object of string constants (`SET_TABLE_DATA`, `SET_TABLE_LOADER`, `SET_SEARCH_FILTER_PARAMS`,
`SHOW_DELETE_MODAL`, `SET_BLOCK_UI`, ...). `reducer.js` exports `INITIAL_STATE` (audit tabs additionally export per-tab spreads like
`METRICS_INITIAL_STATE`) and a `switch` reducer that spreads the action payload. Add a flag per modal; do not use `useState` for table state.

## Audit tab variant

`views/AuditEvent/AuditLayout.jsx` renders `<Tabs>` and `<Outlet context={{ services, servicesAvailable }} />`. A new tab:
add `<Tab eventKey="x" title="X" />` + an `activeTab()` branch there, a route under `/reports/audit` in `App.jsx`, the path in
`PathAssociateWithModule.Audit`, and read shared data via `useOutletContext()`. Render `<AuditFilterEntries entries={state.entries} refreshTable={refreshTable} />`
above the table; populate `entries` with `pick(response.data, ["startIndex", "pageSize", "totalCount", "resultSize"])`.

## After create/update, land on the row

Navigate with `{ state: { showLastPage: true, addPageData: tablePageData } }`; the list page calls `scrollToNewData(state.tableListingData)`
which scrolls and flashes `table-success` for 4 seconds.
