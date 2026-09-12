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

# Utility catalog

Line numbers are approximate; grep the export name.

## `src/utils/XAUtils.js`

| Area | Exports |
|---|---|
| Roles/access | `LoginUser`, `isSystemAdmin`, `isKeyAdmin`, `isUser`, `isAuditor`, `isKMSAuditor`, `getUserAccessRoleList`, `hasAccessToTab`, `hasAccessToPath`, `getLandingPageURl` |
| Tables/search | `getTableSortBy`, `getTableSortType`, `fetchSearchFilterParams`, `parseSearchFilter`, `QueryParamsName`, `CustomInfiniteScroll` |
| UI bits | `commonBreadcrumb`, `InfoIcon`, `setTimeStamp`, `currentTimeZone`, `getAllTimeZoneList`, `getServiceDefIcon` (dynamic `Images/serviceDefIcons/<name>/icon.svg`), `getSelectBoxErrorStyles`, `capitalizeFirstLetter` |
| Errors/nav | `serverError`, `navigateTo` (mutable `{ navigate }` set by `Layout`), `handleLogout`, `checkKnoxSSO`, `getBaseUrl` |
| Policy domain | `isRenderMasking`, `isRenderRowFilter`, `policyInfo`, `isPolicyExpired`, `showGroupsOrUsersOrRolesForPolicy`, `policyConditionUpdatedJSON`, `getResourcesDefVal`, `getAccessTypesByResource`, `prunePolicyItemAccessesToAllowedTypes`, `getPolicyPermissionItemDisplayLbl`, `getPolicyConditionDisplayLbl` |
| Drag and drop | `dragStart`, `dragEnter`, `dragOver`, `drop` |
| Async lookups | `getServiceNameByServiceType`, `getZoneNameByServiceID`, `getServiceDefType`, `safeJsonParse` |

`serverError(error)` toasts `error.response.data.msgDesc` when present, else `error.response.data`.

## `src/utils/XAEnums.js`

`UserRoles`, `UserSource`, `UserTypes`, `UserSyncSource`, `VisibilityStatus`, `GroupSource`, `GroupTypes`, `ClassTypes`, `AuthStatus`,
`AuthType`, `ActivationStatus`, `AccessResult`, `RangerPolicyType`, `RegexValidation`, `PathAssociateWithModule`, `DefStatus`, `QueryParams`,
`alertMessage`, `ServiceType`, `ServerAttrName`, `ResourcesOverrideInfoMsg`, `UsersyncDetailsKeyDisplayMap`, `pluginStatusColumnInfoMsg`,
`statusClassMap`, `additionalServiceConfigs`, `policyConditionDisplayLabel`; helpers `getEnumElementByValue`, `enumValueToLabel`.

Enum entries are `{ value, label, rbkey, tt }`. `rbkey`/`tt` are dead legacy resource-bundle keys; keep the shape for consistency but do not rely on them.

## `src/utils/XAMessages.js`

`RegexMessage.MESSAGE.*` (validation help text shown in `InfoIcon`), `roleChngWarning(user)`, `policyInfoMessage.*`, `udfResourceWarning()`,
`pluginStatusColumnInfo(colName)`.

## `src/components/CommonComponents.jsx`

`Loader`, `ModalLoader`, `BlockUi`, `FieldError`, `MoreLess`, `AccessMoreLess`, `AuditFilterEntries`, `Condition`, `CustomPopover`,
`CustomPopoverOnClick`, `CustomPopoverTagOnClick`, `CustomTooltip`, `useQuery`, `CommonScrollButton`, `scrollToError`, `scrollToNewData`,
`selectInputCustomStyles`, `selectInputWrappingCustomStyles`, `selectInputCustomErrorStyles`, `trimInputValue`, `ConfirmationClearIndicator`.

## `src/hooks/`

`withRouter.js` (injects `navigate`/`location`/`params` into class components), `usePrompt.js`, `usePolicyPermissionConditionContext.js`,
`usePruneStaleConditions.js`.

## `src/utils/` misc

`appState.js` (profile + service-def store), `appConstants.js`, `history.js`, `policyConditionUtils.js`, `actionRequirements/registry.js` + `ozone.json`.
