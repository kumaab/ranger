---
name: ranger-authz
description: The provider-agnostic authorization API and PDP (authz-api, authz-embedded, authz-remote, pdp) - RangerAuthorizer abstract class and factory, RangerAuthzRequest/Result model, resource-name (RRN) syntax and RangerResourceNameParser, RangerEmbeddedAuthorizer to RangerAuthzPlugin mapping, RangerRemoteAuthorizer/RangerPdpClient, RangerPdpServer/RangerPdpREST endpoints on port 6500, config keys, error codes, JSON-driven tests, and the checklist for adding an API method across all layers plus the Python client. Use when changing anything under authz-api/, authz-embedded/, authz-remote/, or pdp/.
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

# Provider-agnostic authorization API (`authz-api`, `authz-embedded`, `authz-remote`, `pdp`)

Four modules let any application ask Ranger "may this user do this?" without embedding the policy engine. Artifacts: `ranger-authz-api`, `authz-embedded`,
`authz-remote`, `ranger-pdp` (the middle two are not prefixed). Engine internals: `agents-common`. Style: `ranger-conventions`. Template commit for an
end-to-end API addition: RANGER-5784 (`b405486ed`, `filterResources`).

## Model (`org.apache.ranger.authz.model`)

| Type | Shape |
|---|---|
| `RangerAuthzRequest` | `requestId`, `RangerUserInfo user`, `RangerAccessInfo access`, `RangerAccessContext context` |
| `RangerMultiAuthzRequest` | same with `List<RangerAccessInfo> accesses` |
| `RangerFilterResourcesRequest` | `user`, `List<RangerResourceInfo> resources`, `Set<String> permissions`, `action`, `context` |
| `RangerResourcePermissionsRequest` | `resource`, `context` (no user: "who can do what here?") |
| `RangerUserInfo` | `name`, `groups`, `roles`, `attributes` |
| `RangerResourceInfo` | `name` (RRN), `subResources`, `ResourceMatchScope { SELF, SELF_OR_ANY_DESCENDANT }`, `attributes` |
| `RangerAccessInfo` | `RangerResourceInfo resource`, `action`, `Set<String> permissions` |
| `RangerAccessContext` | `serviceType`, `serviceName`, `accessTime`, `clientIpAddress`, `forwardedIpAddresses`, `additionalInfo` (`CONTEXT_INFO_CLIENT_TYPE`, `_CLUSTER_NAME`, `_CLUSTER_TYPE`, `_REQUEST_DATA`) |

Results: `RangerAuthzResult { requestId, AccessDecision decision, Map<String, PermissionResult> permissions }`, `AccessDecision { ALLOW, DENY, NOT_DETERMINED, PARTIAL }`
(`PARTIAL` only on `RangerMultiAuthzResult`); `PermissionResult` -> `ResultInfo { AccessResult, DataMaskResult, RowFilterResult, additionalInfo }` + `subResources`;
`RangerFilterResourcesResult { requestId, resources }`; `RangerResourcePermissions { resource, users, groups, roles }`. Row-filter and mask results are fields, not endpoints.

## `RangerAuthorizer` and factory

`public abstract class RangerAuthorizer` (constructed with `Properties`): abstract `init()`, `close()`, `authorize(RangerAuthzRequest)`, `authorize(RangerMultiAuthzRequest)`,
`getResourcePermissions(...)`; concrete `filterResources(...)` (loops `authorize()`, keeps `ALLOW`; both subclasses override it). Protected `validateRequest(...)` overloads:
**call `validateRequest()` first in every override**; it also defaults `accessTime` and `additionalInfo`. Failures throw `RangerAuthzException(RangerAuthzErrorCode, params...)`.
`RangerAuthorizerFactory.createAuthorizer(props)` reflects on `ranger.authorizer.impl.class` (default `org.apache.ranger.authz.embedded.RangerEmbeddedAuthorizer`), requiring a `(Properties)` constructor.

## Resource names (RRN), `RangerResourceNameParser`

`<resourceType>:<v1>/<v2>/...`: `table:mydb/tbl1`, `column:db1/tbl1/col1`, `path:mybucket/a/b.txt`, `url:s3a://mybucket/db1/tbl1`, `global:*`. `:` is the type separator,
`/` the value separator (overridable), `\` escapes; the last segment never splits. The template comes from `RangerServiceDefHelper.getRrnTemplate(resourceType)`
(`database/table/column`), refreshed in `RangerAuthzPlugin` on every `setPolicies()`. Errors: `INVALID_RESOURCE_TEMPLATE_EMPTY_VALUE`, `INVALID_RESOURCE_EMPTY_VALUE`, `INVALID_RESOURCE_VALUE`.

## Embedded: `RangerEmbeddedAuthorizer` -> `RangerAuthzPlugin` -> `RangerBasePlugin`

`RangerEmbeddedAuthorizer` keeps a lazy `Map<serviceName, RangerAuthzPlugin>`, inits `AuditProviderFactory` with `config.getAuditProperties()`, wraps calls in
`RangerAuthzAuditHandler` (`extends RangerDefaultAuditHandler implements AutoCloseable`). `validateAccessContext` resolves serviceName <-> serviceType both ways
(`NO_DEFAULT_SERVICE_FOR_SERVICE_TYPE`, `NO_SERVICE_TYPE_FOR_SERVICE` in `RangerEmbeddedAuthzErrorCode`).

`RangerAuthzPlugin` (package-private) owns one `RangerBasePlugin` per service. RRN -> `RangerAccessResourceImpl` -> `RangerAccessRequestImpl(resource, null, user, groups, roles)`;
one `isAccessAllowed()` per permission; `SELF_OR_ANY_DESCENDANT` -> `ResourceMatchingScope.SELF_OR_DESCENDANTS`; sub-resources overlay the parent map and fold into the
parent decision (DENY wins). Row filter / mask evaluated only when the service-def supports them; cleared on DENY. `getResourcePermissions` maps `getResourceACLs()`
(`ACCESS_CONDITIONAL` currently collapses to `DENY`, a known TODO).

`RangerAuthzConfig` maps its namespace onto `ranger.plugin.<serviceType>.*`, later keys winning: `ranger.authz.default.<x>` -> `ranger.authz.servicetype.<type>.<x>` ->
`ranger.authz.service.<name>.<x>` -> literal `ranger.plugin.<type>.*`; plus `ranger.authz.app.type` (plugin `appId`, default `ranger-authz`), `ranger.authz.init.services`,
`ranger.authz.service.<name>.servicetype`, `ranger.authz.servicetype.<type>.default.service`, `ranger.authz.audit.*` -> `xasecure.audit.*`. Sample: `authz-embedded/src/conf/ranger-authz-embedded.properties`.

## Remote: `RangerRemoteAuthorizer` -> `RangerPdpClient`

Package-private `RangerPdpClient` (Apache HttpClient, JSON only, no gRPC) posts to `/authorize`, `/authorizeMulti`, `/permissions`, `/filterResources` under the `/authz/v1`
prefix from `RangerRemoteAuthzConfig.getEndpointUrl()`. `RangerRemoteAuthType { NONE, HEADER, JWT, KERBEROS }` via `ranger.authz.remote.authn.type`. Keys:
`ranger.authz.remote.pdp.url`, `.pdp.connect.timeout.ms` (5000), `.pdp.read.timeout.ms` (30000), `.header.<Name>`, `.authn.header.<Name>`, `.authn.jwt.source|env|file`,
`.authn.kerberos.principal|keytab|debug` (+ `.jaas.*`, `.spnego.*`), `.ssl.keystore.*` / `.ssl.truststore.*` (default type PKCS12), `.ssl.disable.hostname.verification`.
Errors: `RangerRemoteAuthzErrorCode`. Sample: `authz-remote/src/conf/ranger-authz-remote.properties`.

## PDP server (`pdp`)

`RangerPdpServer` (embedded Tomcat) + Jersey `RangerPdpApplication` (HK2 binds `RangerAuthorizer`, `RangerPdpConfig`). `RangerPdpREST` is `@Path("/v1")` mapped at
`/authz/*`: `POST /authz/v1/{authorize,authorizeMulti,permissions,filterResources}`. Servlets `/health/live`, `/health/ready`, `/metrics` (`RangerPdpStatusServlet`,
`RangerPdpStats`). Filters: `RangerPdpRequestContextFilter` (`X-Request-Id` -> MDC), `RangerPdpAuthNFilter` chaining `HttpHeaderAuthNHandler` / `JwtAuthNHandler` /
`KerberosAuthNHandler`. `validateCaller()` enforces impersonation via `ranger.pdp.service.<name>.delegation.users` (`*` allowed).

Config (`RangerPdpConfig`, `RangerPdpConstants`; `ranger-pdp-default.xml` then `ranger-pdp-site.xml` from `ranger.pdp.conf.dir`): `ranger.pdp.port` (6500), `ranger.pdp.log.dir`,
`ranger.pdp.ssl.*`, `ranger.pdp.http2.enabled`, `ranger.pdp.http.connector.*`, `ranger.pdp.authn.types` (`header,jwt,kerberos`), `ranger.pdp.authn.{header,jwt,kerberos}.*`.
Everything under `ranger.authz.*` is handed to the embedded authorizer. Docker: `Dockerfile.ranger-pdp`, `docker-compose.ranger-pdp.yml` (healthcheck `/health/ready`).
Distro `pdp.xml` always bundles `authz-embedded`, `ranger-audit-dest-auditserver`, `ranger-authn`.

## Errors

`RangerAuthzErrorCode` is an interface rendered as `AUTHZ-<status>-<nn-nnn>`; implementations `RangerAuthzApiErrorCode`, `RangerEmbeddedAuthzErrorCode`, `RangerRemoteAuthzErrorCode`.
Append new codes; never renumber.

## Tests

`TestEmbeddedAuthorizer` is table-driven over `authz-embedded/src/test/resources/test_hive/` and `test_s3/`: `ranger-embedded-authz.properties`
(`policy.source.impl=EmbeddedResourcePolicySource`), policy fixtures (`dev_hive.json`, `dev_hive_tag.json`, `dev_s3_roles.json`, ...), and one file per API
(`tests_authz.json`, `tests_multi_authz.json`, `tests_resource_permissions.json`, `tests_filter_resources.json`) holding `{ "request", "result"[, "auditEvents"] }` entries.
**Add a JSON case, not a Java test.** Others: `TestRangerResourceNameParser`, `TestAuthorizerFactory` (`DummyAuthorizer`), `TestRemoteAuthorizer` (stub `HttpServer`),
`pdp` `*Test.java` (`RangerPdpRESTTest`, `RangerPdpAuthNFilterTest`, `RangerPdpConfigTest`).

```bash
mvn -pl authz-embedded test -Dtest=TestEmbeddedAuthorizer
mvn -pl pdp test -Dtest=RangerPdpRESTTest
```

## Checklist: adding an authz API method

1. `authz-api` model: `Ranger<X>Request` / `Ranger<X>Result` (null-safe setters, `equals`/`hashCode`/`toString`).
2. `RangerAuthorizer`: method + `validateRequest(Ranger<X>Request)` overload; new `RangerAuthzApiErrorCode` entries. Keep a concrete default if expressible via `authorize()`.
3. `authz-api/src/test/.../DummyAuthorizer` must implement it or the module fails to compile.
4. `authz-embedded`: override in `RangerEmbeddedAuthorizer`, mapping in `RangerAuthzPlugin`.
5. `authz-remote`: `RangerPdpClient` `PATH_<X>` + endpoint, `RangerRemoteAuthorizer` delegate.
6. `pdp`: `RangerPdpREST` `@POST @Path("/<x>")` with `validateCaller(...)` and `recordRequestMetrics(...)` in `finally`.
7. `intg` Python: `apache_ranger/model/ranger_authz.py` classes + `type_coerce_attrs`, `client/ranger_pdp_client.py` `URI_<X>` + `API(...)` + method, export in `client/__init__.py`.
8. Tests in `test_hive` and `test_s3`, `TestRemoteAuthorizer`, `RangerPdpRESTTest`, `TestPDPClient` in `intg/src/test/python/test_ranger_client.py`.
