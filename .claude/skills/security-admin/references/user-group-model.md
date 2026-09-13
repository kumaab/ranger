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

# User and group model

## Two tables, no foreign key

| Entity | Table | Owner | Holds |
|---|---|---|---|
| `XXPortalUser` | `x_portal_user` | `UserMgr` | login id, password hash, email, `status` (default `ACT_STATUS_DISABLED`), `userSource` (default `USER_APP`), `syncSource`; roles in `XXPortalUserRole` (`x_portal_user_role`) |
| `XXUser` | `x_user` | `XUserMgr` | `USER_NAME`, `DESCR`, `STATUS`, `IS_VISIBLE`, `CRED_STORE_ID`, `OTHER_ATTRIBUTES`, `SYNC_SOURCE`; referenced by policies and shipped to plugins |

There is no `portal_user_id` column. `XXUserDao.findByPortalUserId(Long)` runs the named query `XXUser.findByPortalUserId`, which joins on
`obj.name = portalUser.loginId`. Renaming one side breaks the link (hence `patch/cliutil/ChangeUserNameUtil`).

`RangerCommonEnums`: `USER_APP = 0` (internal, password editable), `USER_EXTERNAL = 1` (LDAP/AD/Unix, password change blocked), `USER_FEDERATED`;
`GROUP_INTERNAL = 0`, `GROUP_EXTERNAL = 1`. Groups: `XXGroup` (`x_group`), membership `XXGroupUser` (`x_group_users`). Module permissions:
`XXModuleDef` (`x_modules_master`), `XXUserPermission`, `XXGroupPermission`.

## `XUserMgr`: with vs without portal login

| Creates portal + business user | Business user only (usersync path) |
|---|---|
| `createXUser(VXUser)` | `createXUserWithOutLogin(VXUser)` |
| `createXGroup(VXGroup)` | `createXGroupWithoutLogin(VXGroup)` |
| `createXGroupUser(VXGroupUser)` | `createXUserGroupFromMap`, `createXGroupUserFromMap` (bulk, from `/xusers/ugsync/*`) |

`deleteXUser(Long id, boolean force)` is `synchronized`; force removes memberships, roles, zone refs, policy refs and module permissions.
`assignPermissionToUser(VXPortalUser, boolean isCreate)` maps roles to `RangerConstants.MODULE_*` rows. `checkAccessRoles(List<String>)` enforces
that a key admin cannot grant sys-admin roles.

Every mutating method calls `updateUserStoreVersion("<caller>")`, which bumps `XXGlobalState` under `XXGlobalStateDao.RANGER_GLOBAL_STATE_NAME_USER_GROUP`
(`"RangerUserStore"`). Siblings: `RANGER_GLOBAL_STATE_NAME_ROLE`, `RANGER_GLOBAL_STATE_NAME_GDS`. Plugins poll this version through `/xusers/download`,
so a new mutation path that forgets the call leaves plugin user stores stale.

## `UserMgr`: authentication side

`encrypt(loginId, password)` uses `util/Pbkdf2PasswordEncoderCust` salted with the login id; `encryptWithOlderAlgo` is the legacy fallback checked on
password/email change. `updateRoles(Long, Collection<String>)` rewrites `XXPortalUserRole`; `addUserRole(Long, String)` adds one.

Assignable roles are `RangerConstants.VALID_USER_ROLE_LIST`: `ROLE_USER`, `ROLE_SYS_ADMIN`, `ROLE_KEY_ADMIN`, `ROLE_ADMIN_AUDITOR`, `ROLE_KEY_ADMIN_AUDITOR`.
`ROLE_ADMIN` and `ROLE_OTHER` exist as constants but are not assignable; do not offer them in new APIs or UI.

## REST surface

`XUserREST` (`xusers`): `/users*`, `/groups*`, `/groupusers*`, `/permission*`, `/ugsync/*`, `/download/{serviceName}`, `/lookup/*`.
`UserREST` (`users`): `{userId}`, `/default`, `/{userId}/roles`, `{userId}/deactivate`, `/profile`, `{userId}/passwordchange`, `{userId}/emailchange`.
