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

# High availability and metrics

## Admin HA

There is no leader election in `security-admin`. `ranger-common-ha` is a dependency of `ugsync` and `tagsync` only, and no `ranger.ha.*` or `ranger.admin.ha`
property exists. Admin HA is N stateless instances behind an external load balancer sharing one database; usersync (`ranger.usersync.policymanager.baseURL`),
tagsync (`ranger.tagsync.dest.ranger.endpoint`) and plugins (`ranger.plugin.<svc>.policy.rest.url`) point at the VIP.

## usersync / tagsync HA (`ranger-common-ha`)

Curator leader-latch active/passive. Classes: `RangerHAInitializer` (abstract), `ActiveInstanceElectorService implements HARangerService`, `ActiveInstanceState`,
`ActiveStateChangeHandler`, `ServiceState`, `CuratorFactory`, `HAConfiguration`, `RangerServiceServerIdSelector`, `ZookeeperSecurityProperties`.
Concrete: `unixusersync.ha.UserSyncHAInitializerImpl`, `tagsync.ha.TagSyncHAInitializerImpl`, both singletons with `isActive()`.

Keys are prefixed with the value of `ranger.service.name` (`HAConfiguration.getPrefix`), so templates read `ranger-ugsync.server.ha.*` / `ranger-tagsync.server.ha.*`:

| Suffix | Default |
|---|---|
| `.server.ha.enabled` | true when `.server.ha.ids` has more than one entry |
| `.server.ha.ids`, `.server.ha.address.<id>` | required |
| `.server.ha.ssl.enabled` | false |
| `.server.ha.zookeeper.connect` | required |
| `.server.ha.zookeeper.zkroot` | `/apacheranger.service.name_zkroot` (a malformed literal; always set it explicitly) |
| `.server.ha.zookeeper.retry.sleeptime.ms` / `.num.retries` | 1000 / 3 |
| `.server.ha.zookeeper.session.timeout.ms` | 20000 |
| `.server.ha.zookeeper.acl` / `.auth` | none (schemes `sasl`, `world`, `auth`, `digest`, `ip`) |

Znodes under zkRoot: `/leader_elector_path`, `/active_server_info`, `/setup_lock`. Templates: `unixauthservice/scripts/templates/ranger-ugsync-template.xml`,
`tagsync/conf/templates/ranger-tagsync-template.xml`.

## Metrics (`ranger-metrics`, Hadoop metrics2)

`RangerMetricsSystemWrapper` (`init(serviceName, sourceWrappers, sinkWrappers)`, `getRangerMetrics()`, `getRangerMetricsInPrometheusFormat()`), `RangerMetricsInfo`,
`wrapper/RangerMetricsSourceWrapper`, `wrapper/RangerMetricsSinkWrapper`, `source/RangerMetricsSource` + `RangerMetricsJvmSource` + `RangerMetricsContainerSource`,
`sink/RangerMetricsJsonSink`, `sink/RangerMetricsPrometheusSink`.

| Service | Wrapper | Endpoints | Sources |
|---|---|---|---|
| Admin | `metrics/RangerAdminMetricsWrapper` + `RangerMetricsFetcher` | `/service/metrics/{status,prometheus,json}` (`rest/MetricsREST`) | `RangerAdminMetricsSource{UserGroup,Service,PolicyResourceAccess,PolicyRowFiltering,PolicyMasking,ContextEnricher,DenyConditions,Summary,Gds}` extending `RangerAdminMetricsSourceBase` |
| usersync | `UserSyncMetricsWrapper` + `UserSyncMetricsFetcher` | `/api/metrics/{status,prometheus,json}` | `RangerUserSyncSource{Apis,Cache,SyncSource,RoleStatus}` |
| tagsync | `TagSyncMetricsWrapper` | `/api/metrics/{status,prometheus,json}` | `RangerTagSyncMetricsSourceTags` |
| KMS | `KMSMetricWrapper` + `KMSMetricsCollector` | `/api/metrics/{prometheus,json}` | `KMSMetricSource` driven by `KMSMetrics.KMSMetric` |

Adding a metric: new `RangerAdminMetricsSourceFoo extends RangerAdminMetricsSourceBase` (or the service's base), `@Autowired` into the wrapper, then
`sourceWrappers.add(new RangerMetricsSourceWrapper("Foo", "<desc>", context, fooSource))` before `init()`.
Plugin-side audit throughput metrics are a different pipeline: `AuditMetricsREST` reads them from Solr (see `security-admin`).
