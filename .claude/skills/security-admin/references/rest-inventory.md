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

# REST resource inventory

All under `security-admin/src/main/java/org/apache/ranger/rest/`, mounted at `/service/*` (Jersey, `WEB-INF/web.xml`, non-recursive scan).

| Class | `@Path` | Primary collaborators |
|---|---|---|
| `ServiceREST` | `plugins` | `ServiceDBStore`, `ServiceMgr`, `TagDBStore`, `RangerPolicyAdminCache` |
| `PublicAPIsv2` | `public/v2` | facade over `ServiceREST`, `TagREST`, `SecurityZoneREST`, `RoleREST`, `SecurityZoneDBStore` |
| `PublicAPIs` | `public` | facade over `ServiceREST`, `AssetREST` (v1, legacy) |
| `XUserREST` | `xusers` | `XUserMgr`, `XGroupService`, `XModuleDefService`, permission services |
| `UserREST` | `users` | `UserMgr`, `XUserMgr` |
| `AssetREST` | `assets` | `AssetMgr` (pre-`RangerService` model, audit read APIs) |
| `SecurityZoneREST` | `zones` | `SecurityZoneDBStore`, `RangerSecurityZoneServiceService`, `ServiceMgr` |
| `GdsREST` | `gds` | `GdsDBStore`, six `RangerGds*Service` |
| `RoleREST` | `roles` | `RoleDBStore`, `RangerRoleService` |
| `TagREST` | `tags` (`TagRESTConstants.TAGDEF_NAME_AND_VERSION`) | `TagDBStore` |
| `XKeyREST` | `keys` | `KmsKeyMgr` (provider is a `?provider=` query param, not a path segment) |
| `XAuditREST` | `xaudit` | `XAuditMgr`, `XAccessAuditService` |
| `MetricsREST` | `metrics` | `RangerAdminMetricsWrapper` (no `@PreAuthorize`) |
| `AuditMetricsREST` | `audit` | `SolrAccessAuditsService` / `SolrAuditMetricsHelper` |
| `AdminREST` | `admin` | `POST /admin/set-logger-level` -> `RangerLogLevelService` |
| `RangerHealthREST` | `actuator` | `/health`, `/health/readiness`, `/health/liveness`; per-method `@Transactional(NOT_SUPPORTED)` |

Non-resource helpers in the same package: `ServiceRESTUtil`, `ServiceTagsProcessor`, `TagRESTConstants`.

## `ServiceREST` endpoint families (`/service/plugins`)

`/definitions`, `/definitions/{id}`, `/definitions/name/{name}`; `/services`, `/services/{id}`, `/services/name/{name}`, `/services/count`,
`/services/validateConfig`, `/services/lookupResource/{serviceName}`, `/services/grant|revoke/{serviceName}` (+ `/secure/...` Kerberos twins);
`/policies`, `/policies/apply`, `/policies/{id}`, `/policies/guid/{guid}`, `/policies/service/{id}`, `/policies/service/name/{name}`, `/policies/count`,
`/policies/eventTime`, `/policyLabels`, `/policies/cache/reset[-all]`, `/policies/csv|downloadExcel|exportJson|importPoliciesFromFile`,
`/policies/download/{serviceName}` (+ `/secure/...`), `/server/policydeltas`, `/server/purgepolicies/{serviceName}`, `/server/purge/records`,
`/policy/{policyId}/versionList`, `/policy/{policyId}/version/{versionNo}`, `/plugins/info`, `/checksso`, `/csrfconf`, `/metrics/type/{type}`,
`/cluster-services/{clusterName}`.

## `PublicAPIsv2` is a facade

It autowires the other `*REST` beans and delegates; it adds no business logic. To expose something on v2, add the method to the owning `*REST`
and a one-line delegate here. Path families: `/api/zones`, `/api/zones-v2` (partial update, per-service resource views), `/api/servicedef`,
`/api/service`, `/api/service-headers`, `/api/policy`, `/api/policies/bulk`, `/api/roles`, `/api/plugins/info`, `/api/server/{policydeltas,tagdeltas}`,
`/api/server/purgepolicies/{serviceName}`.

Every method, facade or not, needs its own `RangerAPIList` constant mapped in `RangerAPIMapping`; delegation does not inherit the callee's mapping.

## Other surfaces

- `UserREST` (`users`): `{userId}`, `/default`, `/{userId}/roles`, `{userId}/deactivate`, `/profile`, `{userId}/passwordchange`, `{userId}/emailchange`.
  There is no top-level `/service/groups`; groups live under `/service/xusers/groups*`.
- `XUserREST` (`xusers`): `/users*`, `/groups*`, `/groupusers*`, `/permission*`, `/modules*`, `/ugsync/*` (usersync writes), `/download/{serviceName}` (user store for plugins), `/lookup/*`.
- Download twins for plugins: `/plugins/policies/download`, `/tags/download`, `/roles/download`, `/xusers/download`, `/gds/download`, each with a `/secure/` variant.
