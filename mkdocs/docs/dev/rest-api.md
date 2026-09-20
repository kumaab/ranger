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

# REST API

Everything you can do in the Ranger Admin web UI you can also do over HTTP. The Admin server exposes a JSON
REST API that the UI, the plugins, UserSync, TagSync and the client libraries all use. The stable, documented
part is the *public v2 API* under `/service/public/v2/api/`: service definitions, services, policies, roles,
security zones, tags and housekeeping. Other areas (users and groups, Governed Data Sharing, audit queries,
metrics) have their own prefixes and are listed below as well.

This page explains how to reach the API and how to authenticate, then gives an endpoint reference per area,
one endpoint per row. Every path was taken from the `@Path` annotations in
`security-admin/src/main/java/org/apache/ranger/rest/`. For request and response bodies field by field, use
the [generated API reference](#generated-api-reference) that the build produces.

## Basics

Base URL
:   `http://<admin-host>:6080/service/` (HTTPS: port `6182`). The `/service/*` mapping comes from
    `security-admin/src/main/webapp/WEB-INF/web.xml`.

Format
:   JSON in and out. Send `Content-Type: application/json` on `POST`/`PUT` and `Accept: application/json`.

Paging
:   List endpoints accept `pageSize`, `startIndex`, `sortBy` and `sortType`. Public v2 endpoints return a bare
    array; internal APIs return an envelope with `startIndex`, `pageSize`, `totalCount`, `resultSize` and the
    list itself.

### Status codes

| Code | Meaning |
| --- | --- |
| `200 OK` | Success with a body (`GET`, `POST`, `PUT`). |
| `204 No Content` | Success without a body (`DELETE`). |
| `400 Bad Request` | Validation error; the body is a `VXResponse` with the reason in `msgDesc`. |
| `401 Unauthorized` | The request is not authenticated. |
| `403 Forbidden` | The authenticated user is not allowed to perform the operation. |
| `404 Not Found` | The object does not exist. |

### Authentication

All mechanisms below are active at the same time; use whichever your deployment enables
(see Admin authentication).

HTTP Basic
:   `curl -u admin:rangerR0cks! ...`. Works for Ranger-internal users and for external users whose password
    Admin verifies against LDAP, Active Directory or PAM.

Kerberos / SPNEGO
:   `curl --negotiate -u : ...` with a valid ticket, when Admin runs with `ranger.admin.kerberos.*` configured.

Session cookie
:   The UI logs in via `/login` and receives the `RANGERADMINSESSIONID` cookie; scripts can reuse it.

JWT, SSO and trusted header
:   Knox SSO, JWT bearer tokens and header-based pre-authentication are handled by filters declared in
    `security-applicationContext.xml`.

Authorization is per endpoint: most methods carry `@PreAuthorize` checks that map to the user's role
(Admin, KeyAdmin, Auditor, User) and to delegated-admin policies. A `User` can, for example, read policies
it is allowed to see but cannot create a service.

!!! note "CSRF header"
    `ranger.rest-csrf.enabled=true` by default, but the check is applied only to requests whose `User-Agent`
    matches `ranger.rest-csrf.browser-useragents-regex` (`Mozilla,Opera,Chrome`). `curl` and the client
    libraries are not challenged; a browser-based tool must send the `X-XSRF-HEADER` header
    (`ranger.rest-csrf.custom-header`). Read the current value from `GET /service/plugins/csrfconf`.

### A first call

```bash
curl -s -u admin:rangerR0cks! -H "Accept: application/json" \
     http://localhost:6080/service/public/v2/api/service | python3 -m json.tool
```

## Public API v2

Class `PublicAPIsv2` (`@Path("public/v2")`). All paths in this section are relative to
`http://<admin-host>:6080/service/public/v2`. Trailing slashes are optional.

### Service definitions

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/servicedef` | Search service definitions. Filters: `serviceType`, `isEnabled`, paging. |
| `GET` | `/api/servicedef/{id}` | Get a service definition by id. |
| `GET` | `/api/servicedef/name/{name}` | Get a service definition by name. |
| `POST` | `/api/servicedef` | Create a service definition (register a new service type). |
| `PUT` | `/api/servicedef/{id}` | Update by id. |
| `PUT` | `/api/servicedef/name/{name}` | Update by name. |
| `DELETE` | `/api/servicedef/{id}` | Delete by id. |
| `DELETE` | `/api/servicedef/name/{name}` | Delete by name. |

```bash
curl -s -u admin:rangerR0cks! http://localhost:6080/service/public/v2/api/servicedef/name/hive
```

### Services

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/service` | Search services. Filters: `serviceName`, `serviceNamePartial`, `serviceType`, `isEnabled`, paging. |
| `GET` | `/api/service/{id}` | Get a service by id. |
| `GET` | `/api/service/name/{name}` | Get a service by name. |
| `GET` | `/api/service-headers` | List service headers (id, name, type) without the full service objects. |
| `POST` | `/api/service` | Create a service. |
| `PUT` | `/api/service/{id}` | Update by id. |
| `PUT` | `/api/service/name/{name}` | Update by name. |
| `DELETE` | `/api/service/{id}` | Delete a service and its policies, by id. |
| `DELETE` | `/api/service/name/{name}` | Delete a service and its policies, by name. |
| `GET` | `/api/service/{serviceName}/tags` | Read the tags of a service (`RangerServiceTags`). |
| `PUT` | `/api/service/{serviceName}/tags` | Import the tags of a service (`RangerServiceTags`). |

```bash title="Create a Hive service"
curl -s -u admin:rangerR0cks! -X POST -H "Content-Type: application/json" \
     http://localhost:6080/service/public/v2/api/service -d '{
  "name": "dev_hive",
  "type": "hive",
  "description": "Hive in the dev cluster",
  "isEnabled": true,
  "configs": {
    "username": "hive",
    "password": "hive",
    "jdbc.driverClassName": "org.apache.hive.jdbc.HiveDriver",
    "jdbc.url": "jdbc:hive2://ranger-hadoop:10000"
  }
}'
```

The keys accepted in `configs` are the `configs[].name` entries of the service definition; `service.admin.users`
and `service.admin.groups` designate policy administrators for the service.

### Policies

Search endpoints accept `serviceName`, `serviceType`, `policyName`, `policyNamePartial`, `policyType`,
`zoneName` and `resource:<name>` filters plus the paging parameters.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/policy` | Search policies across all services. |
| `GET` | `/api/policy/{id}` | Get a policy by id. |
| `GET` | `/api/policy/guid/{guid}` | Get a policy by GUID. Query: `serviceName`, `ZoneName` (capital `Z` on this endpoint). |
| `GET` | `/api/service/{servicename}/policy` | Search the policies of one service. |
| `GET` | `/api/service/{servicename}/policy/{policyname}` | Get a policy by service and policy name. Query: `zoneName` for a zone policy. |
| `GET` | `/api/policies/{serviceDefName}/for-resource` | Policies that apply to a resource. Query: `serviceName` and one `resource:<name>=<value>` per resource level. |
| `POST` | `/api/policy` | Create a policy. |
| `POST` | `/api/policy/apply` | Create a policy, or merge into the existing policy for the same resource. |
| `PUT` | `/api/policy/{id}` | Update by id. |
| `PUT` | `/api/service/{servicename}/policy/{policyname}` | Update by service and policy name. |
| `DELETE` | `/api/policy/{id}` | Delete by id. |
| `DELETE` | `/api/policy` | Delete by name. Query: `servicename`, `policyname`, optional `zoneName`. |
| `DELETE` | `/api/policy/guid/{guid}` | Delete by GUID. Query: `serviceName`, `zoneName`. |
| `DELETE` | `/api/policies/bulk` | Delete the policies of a service that match the search filters; returns the deleted ids. Query: `serviceName` (required). |

```bash title="Create an access policy"
curl -s -u admin:rangerR0cks! -X POST -H "Content-Type: application/json" \
     http://localhost:6080/service/public/v2/api/policy -d '{
  "service": "dev_hive",
  "name": "sales-readers",
  "policyType": 0,
  "isEnabled": true,
  "isAuditEnabled": true,
  "resources": {
    "database": { "values": [ "sales" ],  "isExcludes": false, "isRecursive": false },
    "table":    { "values": [ "orders" ], "isExcludes": false, "isRecursive": false },
    "column":   { "values": [ "*" ],      "isExcludes": false, "isRecursive": false }
  },
  "policyItems": [
    { "accesses": [ { "type": "select", "isAllowed": true } ],
      "users": [ "alice" ], "groups": [ "analysts" ], "roles": [], "conditions": [], "delegateAdmin": false }
  ],
  "denyPolicyItems": [], "allowExceptions": [], "denyExceptions": []
}'
```

`policyType` is `0` for access, `1` for data masking (`dataMaskPolicyItems`) and `2` for row filtering
(`rowFilterPolicyItems`). Policy semantics are described in [Resource policies](../features/policies/resource-policies.md).

```bash title="Find, then delete, the policy"
curl -s -u admin:rangerR0cks! \
     "http://localhost:6080/service/public/v2/api/service/dev_hive/policy?policyName=sales-readers"
curl -s -u admin:rangerR0cks! -X DELETE \
     "http://localhost:6080/service/public/v2/api/policy?servicename=dev_hive&policyname=sales-readers"
```

### Roles

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/roles` | List roles. |
| `GET` | `/api/roles/{id}` | Get a role by id. |
| `GET` | `/api/roles/name/{name}` | Get a role by name. Query: `serviceName`, `execUser`. |
| `GET` | `/api/roles/names` | List role names. Query: `serviceName`, `execUser`. |
| `GET` | `/api/roles/user/{user}` | Roles a user belongs to. |
| `POST` | `/api/roles` | Create a role. Query: `serviceName`; `createNonExistUserGroup=true` creates missing members. |
| `PUT` | `/api/roles/{id}` | Update a role. |
| `PUT` | `/api/roles/{id}/addUsersAndGroups` | Add members. Query: `users`, `groups`, `isAdmin`. |
| `PUT` | `/api/roles/{id}/removeUsersAndGroups` | Remove members. Query: `users`, `groups`. |
| `PUT` | `/api/roles/{id}/removeAdminFromUsersAndGroups` | Revoke the role-admin flag from members. Query: `users`, `groups`. |
| `PUT` | `/api/roles/grant/{serviceName}` | `GRANT ROLE` as issued by plugins (`GrantRevokeRoleRequest`). |
| `PUT` | `/api/roles/revoke/{serviceName}` | `REVOKE ROLE` as issued by plugins (`GrantRevokeRoleRequest`). |
| `DELETE` | `/api/roles/{id}` | Delete by id. |
| `DELETE` | `/api/roles/name/{name}` | Delete by name. Query: `serviceName`, `execUser`. |

```bash
curl -s -u admin:rangerR0cks! -X POST -H "Content-Type: application/json" \
     "http://localhost:6080/service/public/v2/api/roles?serviceName=dev_hive" -d '{
  "name": "sales_readers", "description": "read sales data",
  "users":  [ { "name": "alice", "isAdmin": false } ],
  "groups": [ { "name": "analysts", "isAdmin": false } ],
  "roles":  []
}'
```

### Security zones

Zones are available in two models. The original model returns the complete `RangerSecurityZone`, including
every resource. The v2 model (`/api/zones-v2`) returns `RangerSecurityZoneV2`, pages through zone resources
and supports partial updates. See [Security zones](../features/sec-zone/intro.md).

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/zones` | List zones. |
| `GET` | `/api/zones/{id}` | Get a zone by id. |
| `GET` | `/api/zones/name/{name}` | Get a zone by name. |
| `POST` | `/api/zones` | Create a zone. |
| `PUT` | `/api/zones/{id}` | Update a zone. |
| `DELETE` | `/api/zones/{id}` | Delete by id. |
| `DELETE` | `/api/zones/name/{name}` | Delete by name. |
| `GET` | `/api/zone-headers` | List zone headers (id and name). |
| `GET` | `/api/zones/{zoneId}/service-headers` | Headers of the services that belong to a zone. |
| `GET` | `/api/zones/zone-headers/for-service/{serviceId}` | Headers of the zones a service belongs to. |
| `GET` | `/api/zone-names/{serviceName}/resource` | Names of the zones that contain a resource. |

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/zones-v2` | List zones (paged). |
| `GET` | `/api/zones-v2/{id}` | Get a zone by id. |
| `GET` | `/api/zones-v2/name/{name}` | Get a zone by name. |
| `GET` | `/api/zones-v2/{id}/resources/{serviceName}` | Page through the resources of one service in a zone, by zone id. |
| `GET` | `/api/zones-v2/name/{name}/resources/{serviceName}` | The same, by zone name. |
| `POST` | `/api/zones-v2` | Create a zone. |
| `PUT` | `/api/zones-v2/{id}` | Replace a zone. |
| `PUT` | `/api/zones-v2/{id}/partial` | Apply a `RangerSecurityZoneChangeRequest` (add or remove resources, admins, auditors). |

### Housekeeping

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/plugins/info` | Plugin status: which host downloaded which policy, tag and role version, and when. |
| `DELETE` | `/api/server/policydeltas` | Purge policy deltas older than `days` (default `7`). |
| `DELETE` | `/api/server/tagdeltas` | Purge tag deltas older than `days` (default `7`). |
| `DELETE` | `/api/server/purgepolicies/{serviceName}` | Delete the empty policies of a service. |
| `DELETE` | `/api/server/purge/records` | Purge login sessions, transaction logs or policy export audits. Query: `type`, `retentionDays` (default `180`). |

## Other API areas

The public v2 API delegates to the classes below; they expose additional, mostly UI-oriented, operations.
Paths in this section are relative to `http://<admin-host>:6080/service`.

### Plugin downloads

Plugins poll these endpoints through `RangerAdminRESTClient`. Every download takes the last version the plugin
already has, plus `pluginId` and (except for tags) `clusterName`, and answers `304 Not Modified` when nothing changed.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/plugins/policies/download/{serviceName}` | Policies. Query: `lastKnownVersion`, `lastActivationTime`, `zoneName`, `supportsPolicyDeltas`, `pluginCapabilities`. |
| `GET` | `/tags/download/{serviceName}` | Tags. Query: `lastKnownVersion`, `lastActivationTime`, `supportsTagDeltas`. |
| `GET` | `/roles/download/{serviceName}` | Roles. Query: `lastKnownRoleVersion`, `lastActivationTime`. |
| `GET` | `/xusers/download/{serviceName}` | User store (users, groups and their attributes). Query: `lastKnownUserStoreVersion`, `lastActivationTime`. |
| `GET` | `/gds/download/{serviceName}` | Governed Data Sharing information. Query: `lastKnownGdsVersion`, `lastActivationTime`. |

Each path has a `secure` variant (`/tags/secure/download/{serviceName}`, `/roles/secure/download/{serviceName}`,
`/xusers/secure/download/{serviceName}`, `/gds/secure/download/{serviceName}` and, for policies,
`/plugins/secure/policies/download/{serviceName}`) that requires authentication and is used in Kerberized
deployments. The plain download paths are declared `security="none"` in `security-applicationContext.xml`.

### Services and policies (`/plugins`)

`ServiceREST`. It mirrors the service definition, service and policy CRUD of the public API under
`/plugins/definitions`, `/plugins/services` and `/plugins/policies`, and adds:

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/plugins/services/validateConfig` | Test the connection of a service configuration. |
| `POST` | `/plugins/services/lookupResource/{serviceName}` | Resource lookup (auto-complete in the policy form). |
| `POST` | `/plugins/services/grant/{serviceName}` | `GRANT` forwarded by a plugin. `/plugins/secure/services/grant/{serviceName}` when secured. |
| `POST` | `/plugins/services/revoke/{serviceName}` | `REVOKE` forwarded by a plugin. `/plugins/secure/services/revoke/{serviceName}` when secured. |
| `GET` | `/plugins/policies/exportJson` | Export policies as JSON (served as `text/json`). Query: `serviceName`. |
| `GET` | `/plugins/policies/csv` | Export policies as CSV. |
| `GET` | `/plugins/policies/downloadExcel` | Export policies as an Excel workbook. |
| `POST` | `/plugins/policies/importPoliciesFromFile` | Multipart policy import; see [Import and export](../features/import-export.md). |
| `GET` | `/plugins/policies/cache/reset` | Rebuild the policy cache of one service. Query: `serviceName`. |
| `GET` | `/plugins/policies/cache/reset-all` | Rebuild the policy cache of all services. |
| `GET` | `/plugins/policy/{policyId}/versionList` | Version numbers of a policy. |
| `GET` | `/plugins/policy/{policyId}/version/{versionNo}` | A policy as it was at a given version. |
| `GET` | `/plugins/policyLabels` | Known policy labels. |
| `GET` | `/plugins/plugins/info` | Plugin status records. |
| `DELETE` | `/plugins/plugins/info/{id}` | Delete a plugin status record. |
| `GET` | `/plugins/checksso` | Whether SSO is enabled. |
| `GET` | `/plugins/csrfconf` | CSRF header configuration. |
| `GET` | `/plugins/metrics/type/{type}` | Admin metrics of one type (users, services, policies, ...). |

### Roles (`/roles`)

`RoleREST` offers the same role operations as the public API under `/roles/roles`, plus:

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/roles/roles/exportJson` | Export roles as JSON. |
| `POST` | `/roles/roles/importRolesFromFile` | Multipart role import. Query: `updateIfExists`, `createNonExistUserGroupRole`. |
| `GET` | `/roles/lookup/roles` | Roles visible to the calling user. |
| `GET` | `/roles/lookup/roles/names` | Role names for auto-complete. |

### Security zones (`/zones`)

`SecurityZoneREST` offers the original zone model under `/zones/zones`, plus:

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/zones/zone-names/{serviceName}/resource` | Names of the zones that contain a resource. |
| `GET` | `/zones/zones/zone-headers/for-service/{serviceId}` | Headers of the zones a service belongs to. |
| `GET` | `/zones/summary` | Zone summaries for the UI. |

### Tags (`/tags`)

`TagREST` stores what TagSync uploads: tag definitions, tag instances, service resources and the maps between
tags and resources. The object types follow a common pattern, shown here for tag definitions.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/tags/tagdefs` | List tag definitions (`/tags/tagdefs/paginated` for a paged envelope). |
| `POST` | `/tags/tagdefs` | Create a tag definition. |
| `GET` | `/tags/tagdef/{id}` | Get by id. |
| `GET` | `/tags/tagdef/guid/{guid}` | Get by GUID. |
| `GET` | `/tags/tagdef/name/{name}` | Get by name. |
| `PUT` | `/tags/tagdef/{id}` | Update. |
| `DELETE` | `/tags/tagdef/{id}` | Delete by id. |
| `DELETE` | `/tags/tagdef/guid/{guid}` | Delete by GUID. |

The other object types use these collection and item paths:

| Object | Collection path | Item path |
| --- | --- | --- |
| Tag | `/tags/tags` | `/tags/tag/{id}`, `/tags/tag/guid/{guid}` |
| Service resource | `/tags/resources` | `/tags/resource/{id}`, `/tags/resource/guid/{guid}` |
| Tag-resource map | `/tags/tagresourcemaps` | `/tags/tagresourcemap/{id}`, `/tags/tagresourcemap/guid/{guid}` |

Tag-resource maps can be created and deleted but not updated. Further tag endpoints:

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/tags/types` | Names of all tag types. |
| `GET` | `/tags/tags/type/{type}` | Tags of one type. |
| `GET` | `/tags/resources/service/{serviceName}` | Service resources of one service. |
| `PUT` | `/tags/importservicetags` | Bulk import of a `ServiceTags` document; used by TagSync. |
| `GET` | `/tags/tags/cache/reset` | Rebuild the tag cache of one service. Query: `serviceName`. |
| `GET` | `/tags/tags/cache/reset-all` | Rebuild the tag cache of all services. |
| `DELETE` | `/tags/server/tagdeltas` | Purge tag deltas older than `days` (default `3`). |

### Users (`/xusers`)

`XUserREST`. The `secure` variants enforce role checks appropriate for API clients; prefer them.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/xusers/users` | Search users. Filters: `name`, `userSource`, `userRole`, `syncSource`, paging. |
| `GET` | `/xusers/secure/users/{id}` | Get a user by id. |
| `GET` | `/xusers/users/userName/{userName}` | Get a user by name. |
| `POST` | `/xusers/secure/users` | Create a user (`name`, `password`, `firstName`, `userRoleList`, ...). |
| `PUT` | `/xusers/secure/users/{id}` | Update a user. |
| `PUT` | `/xusers/secure/users/activestatus` | Enable or disable users. |
| `PUT` | `/xusers/secure/users/visibility` | Show or hide users in the UI. |
| `GET` | `/xusers/secure/users/roles/userName/{userName}` | Get a user's roles. |
| `PUT` | `/xusers/secure/users/roles/userName/{userName}` | Set a user's roles. |
| `DELETE` | `/xusers/secure/users/{userName}` | Delete a user by name. `forceDelete=true` also removes policy references. |
| `DELETE` | `/xusers/secure/users/id/{userId}` | Delete a user by id. |
| `DELETE` | `/xusers/secure/users/delete` | Delete several users; the body lists the names. |

```bash
curl -s -u admin:rangerR0cks! -X POST -H "Content-Type: application/json" \
     http://localhost:6080/service/xusers/secure/users -d '{
  "name": "alice", "password": "Alice123!", "firstName": "Alice",
  "userRoleList": [ "ROLE_USER" ], "userSource": 0
}'
```

`/service/users` (`UserREST`) manages *portal* user profiles (`/users/profile`, `/users/{userId}/passwordchange`,
`/users/{userId}/roles`).

### Groups and membership (`/xusers`)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/xusers/groups` | Search groups. |
| `GET` | `/xusers/secure/groups/{id}` | Get a group by id. |
| `GET` | `/xusers/groups/groupName/{groupName}` | Get a group by name. |
| `POST` | `/xusers/secure/groups` | Create a group. |
| `PUT` | `/xusers/secure/groups/{id}` | Update a group. |
| `PUT` | `/xusers/secure/groups/visibility` | Show or hide groups in the UI. |
| `DELETE` | `/xusers/secure/groups/{groupName}` | Delete a group by name. `forceDelete=true` also removes policy references. |
| `DELETE` | `/xusers/secure/groups/id/{groupId}` | Delete a group by id. |
| `GET` | `/xusers/groupusers` | Search group memberships. |
| `GET` | `/xusers/groupusers/groupName/{groupName}` | Members of a group, by group name. |
| `POST` | `/xusers/groupusers` | Add a user to a group. |
| `DELETE` | `/xusers/group/{groupName}/user/{userName}` | Remove a user from a group. |
| `GET` | `/xusers/{userId}/groups` | Groups of a user. |
| `GET` | `/xusers/{groupId}/users` | Users of a group. |

### Lookups, permissions and sessions (`/xusers`)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/xusers/lookup/users` | User names for auto-complete. |
| `GET` | `/xusers/lookup/groups` | Group names for auto-complete. |
| `GET` | `/xusers/lookup/principals` | Users, groups and roles in one lookup. |
| `GET` | `/xusers/permission` | Search module permissions (which UI modules users and groups may use). |
| `GET` | `/xusers/permission/{id}` | Get a module permission. |
| `PUT` | `/xusers/permission/{id}` | Update a module permission. |
| `GET` | `/xusers/authSessions` | Search login sessions. |
| `GET` | `/xusers/authSessions/info` | Details of one login session. |

### UserSync uploads (`/xusers/ugsync`)

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/xusers/ugsync/users` | Add or update a batch of users. |
| `POST` | `/xusers/ugsync/groups` | Add or update a batch of groups. |
| `POST` | `/xusers/ugsync/groupusers` | Add or update group memberships. |
| `GET` | `/xusers/ugsync/groupusers` | Read all group memberships. |
| `POST` | `/xusers/ugsync/users/visibility` | Mark users that were deleted at the source. |
| `POST` | `/xusers/ugsync/groups/visibility` | Mark groups that were deleted at the source. |
| `POST` | `/xusers/ugsync/auditinfo` | Record a sync audit event. |

### Governed Data Sharing (`/gds`)

`GdsREST`. Datasets, projects, data shares and shared resources all follow the same CRUD pattern:
`POST /gds/<object>`, `GET /gds/<object>` (search), and `GET`, `PUT`, `DELETE` on `/gds/<object>/{id}`, where
`<object>` is `dataset`, `project`, `datashare` or `resource`. See
[Governed Data Sharing](../features/gds/gds_intro.md) for the model.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/gds/dataset/summary` | Dataset summaries for list views. |
| `GET` | `/gds/dataset/names` | Dataset names. |
| `GET` | `/gds/dataset/{id}/policy` | Policies of a dataset. |
| `POST` | `/gds/dataset/{id}/policy` | Add a policy to a dataset. |
| `PUT` | `/gds/dataset/{id}/policy/{policyId}` | Update a dataset policy. |
| `DELETE` | `/gds/dataset/{id}/policy/{policyId}` | Delete a dataset policy. |
| `GET` | `/gds/dataset/{id}/grants` | Grants of a dataset. |
| `PUT` | `/gds/dataset/{id}/grant` | Update the grants of a dataset. |
| `GET` | `/gds/project/{id}/policy` | Policies of a project. |
| `POST` | `/gds/project/{id}/policy` | Add a policy to a project. |
| `GET` | `/gds/datashare/summary` | Data share summaries. |
| `POST` | `/gds/resources` | Add several shared resources in one call. |
| `POST` | `/gds/datashare/dataset` | Request that a data share be added to a dataset. |
| `PUT` | `/gds/datashare/dataset/{id}` | Update the request (approve, activate, deny). |
| `DELETE` | `/gds/datashare/dataset/{id}` | Remove a data share from a dataset. |
| `POST` | `/gds/dataset/project` | Request that a dataset be added to a project. |
| `PUT` | `/gds/dataset/project/{id}` | Update the request. |
| `DELETE` | `/gds/dataset/project/{id}` | Remove a dataset from a project. |

### Audit

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/assets/accessAudit` | Search access audits (the Audit → Access tab). Filters: `startDate`, `endDate`, `requestUser`, `repoName`, `accessResult`, `resourcePath`, paging. |
| `GET` | `/assets/report` | Search admin (transaction) audits. `/assets/v2/report` returns the newer format. |
| `GET` | `/assets/report/{transactionId}` | All changes of one transaction. |
| `GET` | `/assets/ugsyncAudits` | UserSync audits. |
| `GET` | `/assets/exportAudit` | Policy export (plugin download) audits. |
| `GET` | `/xaudit/access_audit` | Access audits through the older API. |
| `GET` | `/xaudit/trx_log` | Transaction audits through the older API. |
| `GET` | `/audit/metrics` | Latest audit volume metrics. |
| `GET` | `/audit/dailymetrics` | Audit volume per day. |
| `GET` | `/audit/daysmetrics` | Audit volume over a number of days. |

### Metrics, health and administration

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/metrics/status` | Admin status. `/metrics/**` requires no authentication; |
| `GET` | `/metrics/json` | Admin metrics as JSON. |
| `GET` | `/metrics/prometheus` | Admin metrics in Prometheus format. |
| `GET` | `/actuator/health` | Overall health. |
| `GET` | `/actuator/health/liveness` | Liveness probe. |
| `GET` | `/actuator/health/readiness` | Readiness probe; requires authentication. |
| `POST` | `/admin/set-logger-level` | Change a logger level at runtime. Body fields: `loggerName`, `logLevel`. |

### KMS keys (`/keys`)

`XKeyREST` proxies key management to Ranger KMS. Every call takes the KMS service name in the `provider`
query parameter.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/keys/keys` | List keys. |
| `GET` | `/keys/key/{alias}` | Get a key. |
| `POST` | `/keys/key` | Create a key. |
| `PUT` | `/keys/key` | Roll over a key. |
| `DELETE` | `/keys/key/{alias}` | Delete a key. |

## Generated API reference

The Ranger build generates a browsable API reference, including a Swagger UI, from the JAX-RS annotations in
the source. The `enunciate-maven-plugin` runs its `docs` goal in the `package` phase of the `security-admin`
module, configured by
[`enunciate.xml`](https://github.com/apache/ranger/blob/master/enunciate.xml) in the repository root
(Swagger and Jackson modules enabled). After `mvn clean package` you find the output under `docs/target/`.
See [Building from source](build.md).

## Working with the API from code

- [Java client](../features/client-interface/java.md) — `RangerClient` wraps the public v2 endpoints.
- [Python client](../features/client-interface/python.md) — `RangerClient`, `RangerUserMgmtClient`,
  `RangerGdsClient`, `RangerKMSClient`, `RangerPDPClient`.
- Plugins never call the public API for policies; they use the `download` endpoints above through
  `RangerAdminRESTClient` (paths in `agents-common` `RangerRESTUtils`).
- The authorization REST API of the PDP server (`/authz/v1/authorize`) is a separate service, described in
  [Authorization API and PDP](authz-api.md).

## Further reading

- Source: [`security-admin/src/main/java/org/apache/ranger/rest`](https://github.com/apache/ranger/tree/master/security-admin/src/main/java/org/apache/ranger/rest)
- cwiki: [REST APIs for Service Definition, Service and Policy Management](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=61334321)
  (older examples; paths are unchanged)
