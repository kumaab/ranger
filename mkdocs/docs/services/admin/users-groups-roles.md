<!---
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Users, groups and roles

Ranger Admin keeps two related kinds of identity information. *Portal users* are the accounts that can log
in to Admin; each has a *user role* that decides whether the account is an administrator, an auditor or a
plain user. *Users and groups* (the ones you pick in policies) are mostly synced in from UNIX, LDAP or
Active Directory by UserSync, but can also be created by hand. This page explains both, plus the
permissions module that controls which parts of the UI a user may open, and how to delete identities
safely.

Ranger *roles* in the RBAC sense (named collections of users and groups that can be referenced in
policies) are a separate feature;

## Internal and external users

| Aspect | Internal | External |
| --- | --- | --- |
| Created by | Admin UI or REST (`POST /service/xusers/secure/users`) | UserSync (UNIX, LDAP/AD, file source) or first login through LDAP/AD/SSO/JWT |
| Password | Stored in Ranger, changeable in the UI | Not stored; validated by the external system |
| Editable in Admin | Name, email, role, groups | Role and visibility only; other attributes are owned by the sync source |
| `sync source` column | empty | `Unix`, `LDAP/AD` or `File`, with the raw attributes visible in the UI |

Users have a *visibility* flag (`Visible`/`Hidden`) and a *status* (`Active`/`Deactivated`); deactivated
users cannot log in. Groups have the same visibility flag. Group-to-user membership can come from the
sync source or be edited for internal groups.

!!! note
    Authenticating against LDAP does not create policies for anyone. Users and groups still need to be
    synced by [UserSync](../usersync/service.md) so that they can be chosen in policies and so that the
    plugins know a user's groups when evaluating policies.

## User roles

Every portal user has exactly one role, stored in `x_portal_user_role`. The list of valid roles is
`ranger.users.roles.list` in `ranger-admin-default-site.xml`.

| Role (UI name) | Internal constant | What the user can do |
| --- | --- | --- |
| Admin | `ROLE_SYS_ADMIN` | Everything except key management: services, policies, users, groups, zones, audits, reports, import/export. |
| KeyAdmin | `ROLE_KEY_ADMIN` | Manage Ranger KMS keys and the KMS service and policies; see the Key Manager UI. Cannot manage other services. |
| Auditor | `ROLE_ADMIN_AUDITOR` | Read-only view of everything an Admin sees. Can export policies but cannot create, update, delete or import. |
| KMS Auditor | `ROLE_KEY_ADMIN_AUDITOR` | Read-only view of everything a KeyAdmin sees; cannot retrieve key material. |
| User | `ROLE_USER` | Own profile; policies where the user is a *delegated admin*; other areas of the UI as granted through the permissions module. |

Only an Admin can grant Admin or Auditor; only a KeyAdmin can grant KeyAdmin or KMS Auditor. Auditor and
KMS Auditor users keep read-only access even when a policy names them as delegated admin. There is no
default Auditor account; create one from **Settings > Users/Groups/Roles > Users > Add New User** and
pick the role.

### Built-in accounts

| Login | Role | Notes |
| --- | --- | --- |
| `admin` | Admin | Initial administrator. |
| `keyadmin` | KeyAdmin | Initial key administrator. |
| `rangerusersync` | Admin | Service account used by UserSync; the password must match the one configured in UserSync. |
| `rangertagsync` | Admin | Service account used by TagSync; the password must match the one configured in TagSync. |

Change the initial passwords in the UI or with
`python3 changepasswordutil.py <loginID> <currentPassword> <newPassword>` from the Admin home directory
(needs `JAVA_HOME`). `changeusernameutil.py <loginID> <currentPassword> <newUserName>` renames an account. Passwords must be at least 8 characters with an upper-case letter, a lower-case letter
and a digit; the last `ranger.password.history.count` (default 4) passwords cannot be reused.

### Super users from configuration

`ranger.admin.super.users` and `ranger.admin.super.groups` in `ranger-admin-site.xml` give the listed
users, or members of the listed groups, full Admin and KeyAdmin capabilities at login time without a
matching role in the database. They are meant for identities managed entirely outside Ranger (for example
a platform team group in Active Directory). Leave them empty otherwise.

## Assigning roles to synced users automatically

UserSync can assign a Ranger role to users and groups as it syncs them, so that, for example, everyone in
the `ranger-admins` group becomes an Admin without manual work. The rules are configured on the UserSync
side with `ranger.usersync.group.based.role.assignment.rules` in `ranger-ugsync-site.xml`:

```xml title="ranger-ugsync-site.xml"
<property>
  <name>ranger.usersync.group.based.role.assignment.rules</name>
  <value>ROLE_SYS_ADMIN:u:alice,bob&amp;ROLE_SYS_ADMIN:g:ranger-admins&amp;ROLE_KEY_ADMIN:g:kms-admins&amp;ROLE_ADMIN_AUDITOR:g:auditors</value>
</property>
```

- `u:` introduces user names, `g:` group names; several names are separated by `,`.
- Rules are separated by `&` (written `&amp;` inside XML); the delimiters can be changed with
  `ranger.usersync.role.assignment.list.delimiter` (`&`),
  `ranger.usersync.users.groups.assignment.list.delimiter` (`:`) and
  `ranger.usersync.username.groupname.assignment.list.delimiter` (`,`).
- Valid role names are the internal constants from the table above.

Restart UserSync after changing the rules. Details of the sync configuration are in
[LDAP and Active Directory sync](../usersync/ldap-ad.md).

For users who log in through LDAP, AD, Knox SSO or JWT without having been synced, Admin creates the
portal record with `ranger.ldap.default.role` (default `ROLE_USER`).

## Permissions module

The permissions module decides which top-level areas of the UI (and the corresponding REST calls) a
non-admin user may use. It is managed under **Settings > Permissions** and stored in `x_modules_master`,
`x_user_module_perm` and `x_group_module_perm`.

| Module | Grants access to |
| --- | --- |
| Resource Based Policies | Service Manager and resource policies |
| Tag Based Policies | Tag services and tag policies |
| Users/Groups | Settings > Users/Groups/Roles |
| Reports | Reports page |
| Audit | Audit tabs |
| Key Manager | KMS Key Manager |
| Security Zone | Security zones |
| Governed Data Sharing | Datasets, data shares and requests |

When a user is created or its role changes, Admin grants a default set of modules for the role
(`XUserMgr.assignPermissionToUser`): every role gets Resource Based Policies and Reports; `ROLE_USER` also
gets Security Zone; the other roles get Audit, Users/Groups and Governed Data Sharing, plus Tag Based
Policies and Security Zone for Admin and Auditor, or Key Manager for KeyAdmin and KMS Auditor. To give a
`ROLE_USER` account another module, tick the user or one of its groups on the module's edit page.

REST: `GET /service/xusers/permission`, `PUT /service/xusers/permission/{id}` (module with its user and
group lists), and `POST /service/xusers/permission/user` for a single user grant.

## Managing users and groups

**UI.** Open **Settings > Users/Groups/Roles**. The *Users*, *Groups* and *Roles* tabs list, search
(by name, email, role, sync source, visibility, status) and create entries. Selecting rows enables *Set
Visibility* and *Delete*. Clicking a synced user shows the attributes received from the sync source.

**REST.** The `xusers` API is the one used by the UI and by UserSync:

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/service/xusers/users?name=<prefix>` | Search users |
| `GET` | `/service/xusers/users/userName/{userName}` | Look up a user by name |
| `POST` | `/service/xusers/secure/users` | Create an internal user |
| `PUT` | `/service/xusers/secure/users/{id}` | Update a user, including the role |
| `PUT` | `/service/xusers/secure/users/visibility` | Change the visibility of several users |
| `GET` | `/service/xusers/groups` | List groups |
| `POST` | `/service/xusers/groups` | Create a group |

A new user needs at least `name`, `password`, `firstName` and `userRoleList`; `groupIdList` is optional.

```bash
curl -u admin:'<password>' -H 'Content-Type: application/json' \
  -X POST http://localhost:6080/service/xusers/secure/users \
  -d '{"name":"alice","password":"Alice1234","firstName":"Alice","userRoleList":["ROLE_USER"]}'
```

Authoritative descriptions of every call are in the generated API docs.

## Deleting users and groups

Deleting a user or group that is referenced by policies would leave dangling references, so Admin refuses
unless you ask for a forced delete, in which case the references are removed from the policies as well.

- **UI.** Select the rows and click *Delete*. The confirmation lists what will be removed.
- **REST.** `DELETE /service/xusers/users/{id}?forceDelete=true`,
  `DELETE /service/xusers/users/userName/{userName}?forceDelete=true`, and the same for
  `/service/xusers/groups/{id}` and `/service/xusers/groups/groupName/{groupName}`.
  `DELETE /service/xusers/delete/external/users` removes external users in bulk (with query filters).
- **Bulk script.** `deleteUserGroupUtil.py` in the Admin home directory takes a file with one name per line:

    ```bash
    python3 deleteUserGroupUtil.py -users /tmp/users.txt -admin admin -url http://localhost:6080 [-force] [-sslCertPath <cert>] [-debug]
    python3 deleteUserGroupUtil.py -groups /tmp/groups.txt -admin admin -url http://localhost:6080 [-force]
    ```

!!! warning
    A user deleted from Admin but still present in the sync source is recreated on the next sync. Remove
    it from the source, or configure UserSync to delete users that disappeared from the source
    (see [UserSync operations](../usersync/operations.md)).

## Related utilities

Both scripts are in the Admin home directory.

`rolebasedusersearchutil.py -u <user> -p <password> -r <role>`
:   Lists the users that have a given role.

`updateUserAndGroupNamesInJson.py`
:   Rewrites user and group names inside an exported policy JSON, for example when names change case.

## Audit trail

Every create, update and delete of users, groups and roles is recorded as an *Admin* audit entry
(`x_trx_log_v2`, visible under **Audit > Admin**), and every login attempt is recorded under
**Audit > Login Sessions** with the authentication type (password, Kerberos, SSO or trusted proxy).

## Further reading

- [`XUserREST.java`](https://github.com/apache/ranger/blob/master/security-admin/src/main/java/org/apache/ranger/rest/XUserREST.java)
- [`RangerConstants.java`](https://github.com/apache/ranger/blob/master/security-admin/src/main/java/org/apache/ranger/common/RangerConstants.java) (role and module names)
- cwiki: [Support for read-only Ranger Admin users](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=75978232),
  [Automatically map group of external users to Administrator role](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=103092133)
