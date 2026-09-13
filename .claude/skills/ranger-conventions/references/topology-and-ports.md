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

# Component topology and ports

Every server process is an embedded Tomcat launched by its own `EmbeddedServer` class; only Admin and KMS share `embeddedwebserver/.../server/tomcat/EmbeddedServer.java`.
Usersync (`unixauthservice/.../authentication/server/EmbeddedServer.java`), tagsync (`tagsync/.../tagsync/server/EmbeddedServer.java`), the audit ingestor
(`audit-server/audit-ingestor/.../audit/server/EmbeddedServer.java`) and the audit dispatcher (`dispatcher-common/.../audit/dispatcher/EmbeddedServer.java`) have their own.

| Process | Property | Default | Defined in |
|---|---|---|---|
| Admin HTTP | `ranger.service.http.port` | 6080 | `security-admin/src/main/resources/conf.dist/ranger-admin-site.xml` |
| Admin HTTPS | `ranger.service.https.port` | 6182 | same |
| Admin shutdown | `ranger.service.shutdown.port` | 6085 | `conf.dist/ranger-admin-default-site.xml` |
| KMS HTTP | `ranger.service.http.port` | 9292 | `kms/config/webserver/ranger-kms-site.xml` (provider URI `dbks://http@localhost:9292/kms`) |
| Unix auth service (in usersync) | `ranger.usersync.port` | 5151 | `unixauthservice/conf.dist/ranger-ugsync-default.xml` |
| Audit ingestor | `ranger.audit.ingestor.http.port` / `.https.port` | 7081 / 7182 | `audit-server/audit-ingestor/src/main/resources/conf/ranger-audit-ingestor-site.xml` |
| PDP | `RangerPdpConfig.getPort()` | 6500 | `pdp/src/main/resources/ranger-pdp-default.xml` |

Daemon control scripts: `security-admin/scripts/ranger-admin-services.sh`, `unixauthservice/scripts/ranger-usersync-services.sh`,
`tagsync/scripts/ranger-tagsync-services.sh`, `kms/scripts/ranger-kms`. Each server also has its own installer: Admin `security-admin/scripts/setup.sh`
(see `security-admin-db`), usersync `unixauthservice/scripts/setup.py`, tagsync `tagsync/scripts/setup.sh`, KMS `kms/scripts/setup.sh` with its **own**
`kms/scripts/db_setup.py` (do not point the Admin recipe at it).

## Data flows

- **Policies**: Admin DB -> `ServiceREST /policies/download/{svc}` (deltas from `x_policy_change_log`) -> plugin `PolicyRefresher` -> local cache -> engine.
  Same shape for tags (`/tags/download`), roles (`/roles/download`), user store (`/xusers/download`), GDS (`/gds/download`).
- **Users/groups**: LDAP / Unix / file -> `ugsync` `UserGroupSync` -> `PolicyMgrUserGroupBuilder` -> Admin `XUserREST` (`/xusers/ugsync/*`) -> `x_user`, `x_group`, `x_group_users`.
- **Tags**: Atlas (Kafka notifications or REST) -> `tagsync` `TagSynchronizer` -> `TagAdminRESTSink` -> Admin `TagREST` -> `x_tag*`, `x_service_resource`; plugins pull via `RangerTagEnricher`.
- **Audit**: plugin `RangerDefaultAuditHandler` -> `agents-audit` queue chain -> destinations (Solr, OpenSearch, Elasticsearch, HDFS, Kafka, log4j, CloudWatch) or
  `RangerAuditServerDestination` -> audit ingestor (7081) -> Kafka -> dispatchers -> stores. Admin reads audits back through `XAuditREST`/`AssetREST` from Solr/ES/OS.
- **Grant/revoke**: Hive/HBase `GRANT`/`REVOKE` -> `RangerBasePlugin.grantAccess/revokeAccess` -> Admin `/plugins/services/grant|revoke/{svc}` -> policy rows
  (plugin-side toggle `UPDATE_XAPOLICIES_ON_GRANT_REVOKE` in `install.properties`).
- **Authz API**: apps -> `RangerEmbeddedAuthorizer` (in-process engine) or `RangerRemoteAuthorizer` -> PDP (6500) -> engine.

Docker stack for the whole topology: `dev-support/ranger-docker` (`ranger`, `ranger-db`, `ranger-usersync`, `ranger-tagsync`, `ranger-kms`, `ranger-pdp`,
`ranger-audit-ingestor`, `ranger-audit-dispatcher-<store>`, plus per-service plugin containers). Admin UI at `http://localhost:6080`, `admin/rangerR0cks!`.
