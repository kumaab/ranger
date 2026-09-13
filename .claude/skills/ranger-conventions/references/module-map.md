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

# Module map

Root `pom.xml` lists ~70 modules under profiles `all` and `linux` (Linux auto-activates; macOS needs `-Pall`). `distro` must be last.

## Core and shared

| Module | Artifact | Purpose / entry points |
|---|---|---|
| `common-utils` | `ranger-common-utils` | low-level helpers in `org.apache.ranger.plugin.util`: `RangerPerfTracer`, `JsonUtilsV2`, `RangerCache`, `RangerReadWriteLock` |
| `ranger-util` | | `RangerVersionInfo`, `RangerVersionAnnotation` (build-time version stamp) |
| `agents-common` | `ranger-plugins-common` | plugin framework: `RangerBasePlugin`, policy engine, models, service-defs, validators (skill `agents-common`) |
| `agents-audit` | `ranger-audit-core`, `ranger-audit-dest-*` | `AuditProviderFactory`, `AuditDestination` impls for solr/es/os/hdfs/kafka/log4j/cloudwatch/auditserver; skill `ranger-audit-server` |
| `agents-cred` | `ranger-plugins-cred` | credential providers (`RangerCredentialProvider`, Kerberos JAAS helpers) |
| `credentialbuilder` | | `buildks` CLI for jceks stores |
| `ranger-plugin-classloader` | | `RangerPluginClassLoader`, `PluginClassLoaderActivator` (child-first isolation for shims) |
| `agents-installer` | `ranger-plugins-installer` | `XmlConfigChanger`, applies `*-changes.cfg` |
| `embeddedwebserver` | | embedded Tomcat `EmbeddedServer` used by admin/kms/usersync/tagsync, index bootstrappers |
| `ranger-authn` | | JWT/auth handlers for the newer HTTP services |
| `ranger-common-ha` | | Curator leader election; depended on by `ugsync` and `tagsync` only (Admin HA is LB + shared DB) |
| `ranger-metrics` | | Hadoop-metrics2 wrapper, JSON/Prometheus sinks |
| `jisql` | | vendored JDBC script runner used by the DB installer |

## Admin and servers

| Module | Purpose |
|---|---|
| `security-admin` | Ranger Admin: REST, biz, JPA, React UI, DB schema, installer (skills `security-admin`, `security-admin-webapp`, `security-admin-db`) |
| `kms` | Ranger KMS (`org.apache.hadoop.crypto.key.*`, tables `ranger_keystore`/`ranger_masterkey`, own `kms/scripts/db_setup.py`); skill `ranger-kms` |
| `ugsync`, `ugsync-util`, `ugsync/ldapconfigchecktool/ldapconfigcheck` | user/group sync, skill `ranger-sync-services` (`UserGroupSync`, `LdapUserGroupBuilder`, `PolicyMgrUserGroupBuilder`) |
| `tagsync` | Atlas tag sync (`RangerTagSyncServer`, `TagSynchronizer`, `AtlasTagSource` Kafka / `AtlasRESTTagSource`, `TagAdminRESTSink`); jar module that also builds a WAR served by its own `EmbeddedServer` with a metrics REST endpoint since RANGER PR #1107 |
| `unixauthservice`, `unixauthclient`, `unixauthnative`, `unixauthpam` | Unix password authentication (disabled by default since RANGER-5698) |
| `pdp` | standalone Policy Decision Point server (`RangerPdpServer`, `RangerPdpREST` at `/authz/v1/*`, port 6500; tarball bundles `authz-embedded`); skill `ranger-authz` |
| `audit-server` | `audit-common`, `audit-ingestor` (`AuditREST` -> Kafka), `audit-dispatcher/{dispatcher-app,dispatcher-common,dispatcher-hdfs,dispatcher-opensearch,dispatcher-solr}`; skill `ranger-audit-server` |
| `authz-api`, `authz-embedded`, `authz-remote` | provider-agnostic authorization API (abstract `RangerAuthorizer`, `RangerEmbeddedAuthorizer`, `RangerRemoteAuthorizer`); artifacts `ranger-authz-api`, `authz-embedded`, `authz-remote`; skill `ranger-authz` |
| `intg` | Java `RangerClient` and the `apache-ranger` Python package (`intg/src/main/python`); skill `ranger-clients` |

## Plugins

Impl modules `hdfs-agent`, `hive-agent`, `hbase-agent`, `knox-agent`, `storm-agent`, `plugin-{atlas,elasticsearch,kafka,kms,kudu,kylin,nestedstructure,nifi,nifi-registry,ozone,presto,schema-registry,solr,sqoop,trino,yarn}`
and shims `ranger-<svc>-plugin-shim` (15). Structure: skill `ranger-plugin`. `ranger-examples` holds the minimal `plugin-sampleapp`, `sampleapp`, `conditions-enrichers`, `sample-client` (Java `SampleClient`, `RemoteAuthzClient`; Python `sample_client.py`, `sample_gds_client.py`, `sample_kms_client.py`, `sample_pdp_client.py`, `user_mgmt.py`, `security_zone_v2.py`).

## Packaging, tooling, docs

| Path | Purpose |
|---|---|
| `distro` | assembly descriptors (`distro/src/main/assembly/*.xml`) for every tarball |
| `ranger-tools` | policy engine perf and memory sizing harnesses |
| `dev-support/` | checkstyle/PMD/spotbugs configs, IntelliJ scheme, `ranger-docker/` (Dockerfiles, compose files, `README.md`), `checks/coverage.sh`, legacy `test-patch.sh` |
| `ranger_in_docker`, `build_ranger_using_docker.sh` | local stack and containerized build entry points |
| `functional-tests/` | pytest e2e suites, not a Maven module |
| `mkdocs/` | documentation site; `docs/` is the legacy Maven/Enunciate site |
| `migration-util/` | Ambari-era migration scripts, packaged by distro but not a Maven module |
| `AGENTS.md`, `SECURITY.md`, `THREAT_MODEL.md` | agent guidance, security process, PMC-reviewed threat model |
| `.cursor/rules/` | `ranger-checkstyle.mdc`, `ranger-python.mdc` |
