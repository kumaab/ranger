---
name: ranger-sync-services
description: Architecture and extension patterns for Ranger user/group sync and tag sync (ugsync, ugsync-util, unixauthservice, tagsync) - UserGroupSync loop and UserGroupSource/UserGroupSink SPI, LDAP/Unix/file builders, PolicyMgrUserGroupBuilder REST endpoints, TagSynchronizer and TagSource/TagSink, Atlas resource mappers, config keys, HA via ranger-common-ha, metrics endpoints. Use when changing anything under ugsync/, ugsync-util/, unixauthservice/, or tagsync/.
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

# Ranger synchronization services (ugsync, ugsync-util, unixauthservice, tagsync)

`ugsync-util` holds the wire model shared with Ranger Admin (`XUserInfo`, `XGroupInfo`, `GroupUserInfo`, `UsersGroupRoleAssignments`, `UgsyncAuditInfo`,
`{Ldap,Unix,File}SyncSourceInfo`, `UgsyncCommonConstants`, the `Mapper`/`AbstractMapper`/`RegEx` name transformers). Admin depends on it, so a model change
there is a two-module change. `unixauthservice` is the webapp/daemon that hosts usersync (and the optional Unix auth service, off by default since RANGER-5698).
Style rules: `ranger-conventions`. HA keys and metrics tables: `ranger-conventions/references/ha-and-metrics.md`.

## User/group sync

`UserGroupSync implements Runnable` is the whole loop: resolve sink then source from config, `init()` both, one initial `syncUserGroup()`, then sleep
`getSleepTimeInMillisBetweenCycle()` forever. Each cycle is gated on `UserGroupSyncConfig.isUgsyncServiceActive()`; a passive HA node logs and sleeps.
`syncUserGroup()` calls `ugSource.updateSink(ugSink)` only when `ranger.usersync.enabled` is true (default true).

SPI: `UserGroupSource` (`init`, `isChanged`, `updateSink(UserGroupSink)`) and `UserGroupSink` (`init`, `addOrUpdateUsersGroups(sourceGroups, sourceUsers,
sourceGroupUsers, computeDeletes)`, `postUserGroupAuditInfo`). Sources extend `AbstractUserGroupSource`, which owns the regex mappers.

| Source | Class | Notes |
|---|---|---|
| Unix (default) | `unixusersync.process.UnixUserGroupBuilder` | `ranger.usersync.unix.backend` = `passwd` (default, `/etc/passwd`, `/etc/group`) or `nss` (`getent`); `.unix.minUserId` (500), `.minGroupId` |
| LDAP/AD | `ldapusersync.process.LdapUserGroupBuilder` | paged search; `ranger.usersync.ldap.deltasync` tracks AD `uSNChanged` and OpenLDAP `modifyTimestamp` |
| File | `unixusersync.process.FileSourceUserGroupBuilder` | `.json` -> `Map<String, List<String>>`, otherwise CSV via `ranger.usersync.filesource.text.delimiter` |

Sink `PolicyMgrUserGroupBuilder` posts to Admin `/service/xusers/ugsync/users`, `/ugsync/groups/`, `/ugsync/groupusers`, `/ugsync/auditinfo/`,
`/ugsync/users/visibility`, `/ugsync/groups/visibility`, and `/service/xusers/users/roleassignments`, over `RangerUgSyncRESTClient` (user `rangerusersync`).
Deletes are soft: with `ranger.usersync.deletes.enabled` (default false) every `ranger.usersync.deletes.frequency` cycles (default 10) the builder passes
`computeDeletes=true`, which drives the two `/visibility` endpoints (`is_visible` flag on `x_user`/`x_group`).

Config singleton `UserGroupSyncConfig` loads `ranger-ugsync-default.xml`, `core-site.xml`, `ranger-ugsync-site.xml`. Key properties: `ranger.usersync.enabled`,
`.source.impl.class`, `.sink.impl.class`, `.policymanager.baseURL`, `.policymgr.username`/`.password`, `.sleeptimeinmillisbetweensynccycle` (floor 60s unix, 3600s ldap),
`.ldap.url`/`.binddn`/`.searchBase`, `.unix.auth.enabled`, `.metrics.enabled`/`.filepath`/`.filename`, `.whitelist.users.role.assignment.rules`.
Installer: `unixauthservice/scripts/setup.py` (Python, not the Admin `setup.sh` flow) with `templates/installprop2xml.properties` + `ranger-ugsync-template.xml`.

## Tag sync

`RangerTagSyncServer` boots a Spring webapp (`tagsync/src/main/webapp`, Jersey at `/api/*`) via its own `tagsync.server.EmbeddedServer`; `RangerTagSyncStarter`
and `TagSynchronizer` drive the sources. SPI: `TagSource` (`initialize`, `setTagSink`, `start`, `stop`) and `TagSink` (`initialize`, `upload(ServiceTags)`,
`start`, `stop`); sources extend `AbstractTagSource`.

`TagSynchronizer` scans `ranger.tagsync.source.<name>` properties; a value of `enable`/`enabled`/`true` activates it, and `getTagSourceFromConfig()` knows
three names: `atlas` -> `source.atlas.AtlasTagSource` (Atlas Kafka `ENTITIES` notifications, `AtlasNotificationMapper`, `EntityNotificationWrapper`),
`atlasrest` -> `source.atlasrest.AtlasRESTTagSource` (polls `ranger.tagsync.source.atlasrest.endpoint` every `.download.interval.millis`, batches
`.entities.batch.size`, via `AtlasRESTHttpClient`), `file` -> `source.file.FileTagSource`.

Entity -> Ranger resource translation: `AtlasResourceMapper` subclasses registered in `AtlasResourceMapperUtil` (`AtlasHiveResourceMapper`, `AtlasHdfsResourceMapper`,
`AtlasHbaseResourceMapper`, `AtlasKafkaResourceMapper`, `AtlasOzoneResourceMapper`, `AtlasAdlsResourceMapper`, `AtlasStormResourceMapper`, `AtlasTrinoResourceMapper`,
`AtlasNestedStructureResourceMapper`). Atlas cluster -> Ranger service is `ranger.tagsync.atlas.<component>.instance.<atlasInstance>.ranger.service`
(HDFS may add `.nameservice.<ns>`), fallback `ranger.tagsync.atlas.default.cluster.name` + `_` + component; custom mappers in `ranger.tagsync.atlas.custom.resource.mappers`.

Sink `TagAdminRESTSink implements TagSink, Runnable` PUTs `ServiceTags` to `/service/tags/importservicetags/` through `RangerRESTClient`, batched by
`ranger.tagsync.dest.ranger.max.batch.size`. `TagSyncConfig` loads `ranger-tagsync-default.xml`, `core-site.xml`, `ranger-tagsync-site.xml`
(`ranger.tagsync.enabled` default true; `.dest.ranger.endpoint`, `.username`, `.ssl.config.filename`). Installer `tagsync/scripts/setup.sh`, templates `tagsync/conf/templates/`.

## Metrics and HA

usersync: `UserSyncMetricsWrapper` + `RangerUserSyncSource{Apis,Cache,SyncSource,RoleStatus}`, served at `/api/metrics/{status,prometheus,json}` by
`authentication/rest/MetricsREST`. tagsync: `TagSyncMetricsWrapper` + `RangerTagSyncMetricsSourceTags`, same three paths on `tagsync/rest/MetricsREST`.
Legacy JSON-file producers (`UserSyncMetricsProducer`, `TagSyncMetricsProducer`) still write `/tmp/ranger_{usersync,tagsync}_metric.json`.
HA: `UserSyncHAInitializerImpl` / `TagSyncHAInitializerImpl extends RangerHAInitializer` (`ranger-common-ha`, Curator leader latch), `isActive()`.
Keys are prefixed with `ranger.service.name` (`ranger-ugsync.server.ha.*`, `ranger-tagsync.server.ha.*`).

## Adding a source or sink

1. Implement the SPI (extend `AbstractUserGroupSource` or `AbstractTagSource`) in `org.apache.ranger.{unix,ldap}usersync.process` or `org.apache.ranger.tagsync.source.<name>`.
2. ugsync has no registry: wire by FQCN in `ranger.usersync.source.impl.class` / `.sink.impl.class`. tagsync: add the short name to `TagSynchronizer.getTagSourceFromConfig()`
   and define `ranger.tagsync.source.<name>`.
3. Add defaults to `unixauthservice/conf.dist/ranger-ugsync-default.xml` or `tagsync/src/main/resources/ranger-tagsync-default.xml`, getters on `UserGroupSyncConfig`/`TagSyncConfig`,
   installer template entries.
4. New Atlas entity type: new `AtlasResourceMapper` subclass registered in `AtlasResourceMapperUtil` plus a `Test<X>ResourceMapper`.
5. Crossing into Admin: update the `ugsync-util` model, the matching `XUserREST` `/ugsync/...` method, and a `RangerAPIList` constant mapped in `RangerAPIMapping`.

## Tests

```bash
mvn -pl ugsync-util,ugsync,tagsync -DskipTests verify
mvn -pl ugsync test -Dtest=TestUserGroupSync        # also TestLdapUserGroupBuilder, TestUnixUserGroupBuilder, TestPolicyMgrUserGroupBuilder, TestFileSourceUserGroupBuilder
mvn -pl tagsync test -Dtest=TestTagSynchronizer     # also Test{Hive,Hdfs,Hbase,Kafka,Ozone,Adls,Trino}ResourceMapper, AtlasTagSourceConfigTest
```
