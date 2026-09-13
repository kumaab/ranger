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

# Where each plugin attaches to its host

Each row: the class the host loads, the host-side property (from `<module>/conf/*-changes.cfg` or `agents-common/scripts/enable-agent.sh`), and anything
unusual about the request path.

| Service | Entry class | Host wiring |
|---|---|---|
| HDFS | `RangerHdfsAuthorizer extends INodeAttributeProvider` (`hdfs-agent/.../authorization/hadoop/`) | `dfs.namenode.inode.attributes.provider.class` (`hdfs-site-changes.cfg`) |
| Hive | `RangerHiveAuthorizerFactory implements HiveAuthorizerFactory` -> `RangerHiveAuthorizer extends RangerHiveAuthorizerBase extends AbstractHiveAuthorizer` | `hive.security.authorization.manager` (`hiveserver2-site-changes.cfg`, also appends to `hive.conf.restricted.list`) |
| HBase | `RangerAuthorizationCoprocessor` (Master/Region/RegionServer/Endpoint/BulkLoad observers + `AccessControlService.Interface`) | `hbase.coprocessor.master.classes`, `hbase.coprocessor.region.classes` (cfg removes HBase `AccessController`) |
| KMS | `RangerKmsAuthorizer implements KeyACLs` | `hadoop.kms.security.authorization.manager` in `kms-site.xml` (skill `ranger-kms`) |
| YARN | `RangerYarnAuthorizer extends YarnAuthorizationProvider` | `yarn.authorization-provider` (`yarn-site-changes.cfg`) |
| Kafka | `RangerKafkaAuthorizer implements org.apache.kafka.server.authorizer.Authorizer` | `authorizer.class.name` in `server.properties` |
| Knox | `RangerPDPKnoxFilter implements javax.servlet.Filter` + `KnoxRangerPlugin` | topology XML provider `AclsAuthz` -> `XASecurePDPKnox` |
| Storm | `RangerStormAuthorizer implements IAuthorizer` + `StormRangerPlugin` | `nimbus.authorizer` in `storm.yaml` |
| Atlas | `RangerAtlasAuthorizer implements AtlasAuthorizer` | `atlas.authorizer.impl` in `atlas-application.properties` |
| Solr | `RangerSolrAuthorizer extends SearchComponent implements AuthorizationPlugin` | `security.json` authorization class; `SubsetQueryPlugin` for document-level filtering |
| Ozone | `RangerOzoneAuthorizer implements IAccessAuthorizer` | Ozone ACL authorizer class property; resources `volume` -> `bucket` -> `key` |
| Elasticsearch | `RangerElasticsearchAuthorizer implements RangerElasticsearchAccessControl` | ES plugin, conf under `config/ranger-elasticsearch-plugin` |
| Kylin | `RangerKylinAuthorizer extends ExternalAclProvider` | `kylin.server.external-acl-provider` |
| Sqoop | `RangerSqoopAuthorizer extends AuthorizationValidator` | `org.apache.sqoop.security.authorization.validator` |
| Presto | `RangerSystemAccessControl implements SystemAccessControl` | Presto access-control config; shim adds `RangerConfig` |

Admin-side only in this repo (the enforcement half lives in the host project): `RangerServiceNiFi`, `RangerServiceNiFiRegistry`, `RangerServiceKudu`,
`RangerServiceSchemaRegistry`, `RangerServiceTrino`. Trino's authorizer was removed by RANGER-4859 and moved into the Trino repo; the leftover
`plugin-trino/src/main/resources/META-INF/services/io.trino.spi.Plugin` names a class that no longer exists. `plugin-nestedstructure` has no host:
`NestedStructureAuthorizer` is a library API (`JsonManipulator`, `DataMasker`, `MaskTypes`, `RecordFilterJavaScript`).

## Host-specific behaviour keys (`org.apache.ranger.authorization.hadoop.constants.RangerHadoopConstants`)

- HDFS: `xasecure.add-hadoop-authorization` (fall back to HDFS ACLs, default false), `ranger.optimize-subaccess-authorization` (default false),
  `ranger.plugin.hdfs.use.legacy.subaccess.authorization` (default true), `ranger.plugin.hdfs.filename.extension.separator` (`.`, splits `FILENAME` / `BASE_FILENAME`).
  `RangerAccessControlEnforcer` keeps the NameNode's default enforcer and calls `checkDefaultEnforcer(...)` when its own result is `AuthzStatus.NOT_DETERMINED`;
  `OperationOptimizer.optimize()` can short-circuit whole operations (`OPT_BYPASS_AUTHZ`). Denials throw `RangerAccessControlException extends AccessControlException`.
- Hive: `xasecure.hive.update.xapolicies.on.grant.revoke`, `xasecure.hive.block.update.if.rowfilter.columnmask.specified`,
  `xasecure.hive.describetable.showcolumns.authorization.option`, `xasecure.hive.uri.permission.coarse.check`. Masking/row filtering: `agents-common` `references/masking-and-rowfilter.md`.
- HBase: `ranger.plugin.hbase.column.auth.optimized`, `xasecure.hbase.update.xapolicies.on.grant.revoke`; `AuthorizationSession` builds requests, `RangerAuthorizationFilter` filters cells.
- YARN: `ranger.add-yarn-authorization` (default true).
