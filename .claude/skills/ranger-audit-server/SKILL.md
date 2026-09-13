---
name: ranger-audit-server
description: The Ranger audit pipeline - agents-audit in-process framework (AuditProviderFactory queue chain, AuditDestination SPI, xasecure.audit.destination.* keys, file spool, AuthzAuditEvent) and the standalone audit-server (audit-ingestor AuditREST to Kafka, audit-dispatcher hdfs/opensearch/solr, config keys, DB patch 078 bootstrap, docker services), plus the checklist for a new audit destination. Use when changing anything under agents-audit/, audit-server/, a plugin's *-audit-changes.cfg, or xasecure.audit.* properties.
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

# Audit pipeline (`agents-audit`, `audit-server`)

`agents-audit` is the in-process framework every plugin links against; `audit-server` is the standalone REST -> Kafka -> store pipeline that offloads writes.
The read path (Admin UI querying Solr/OpenSearch/DB through `XAuditREST`, `AssetREST`, `SolrAccessAuditsService`) is `security-admin`, not here. The plugin-side
handler and audit filters are in `agents-common` `references/audit-handler-and-filters.md`. Style: `ranger-conventions`.

## `agents-audit` (in-process)

Modules: `core` (`ranger-audit-core`), `dest-{solr,es,os,hdfs,kafka,log4j,cloudwatch,auditserver}` (`ranger-audit-dest-*`), `orc-util`. Packages
`org.apache.ranger.audit.{provider,queue,destination,model,utils}`. Plugins depend on `ranger-audit-core` + `ranger-audit-dest-auditserver`; other destinations
are added by the distro assembly.

`AuditProviderFactory.getInstance().init(props, appType)` (lazy singleton; `RangerBasePlugin.init()` calls it once) builds the v3 pipeline in reverse:

```
AuditAsyncQueue -> [AuditSummaryQueue] -> [MultiDestAuditProvider] -> AuditBatchQueue [-> AuditFileQueue] -> AuditDestination
```

`xasecure.audit.provider.filecache.is.enabled=true` swaps the head for `AuditFileCacheProvider`. A Hadoop `ShutdownHookManager` hook (priority 30) flushes on exit,
except for appTypes `hbaseMaster`/`hbaseRegional`. With no `xasecure.audit.destination.*` the factory falls back to legacy v2 keys (`xasecure.audit.<dest>.is.enabled`
+ `AsyncAuditProvider`), then `DummyAuditProvider`.

### Property model

- Enable: `xasecure.audit.destination.<name>` = `enable|enabled|true`. Names -> classes: `file` `FileAuditDestination`, `hdfs` `HDFSAuditDestination`, `solr` `SolrAuditDestination`,
  `elasticsearch` `ElasticSearchAuditDestination`, `opensearch` `OpenSearchAuditDestination`, `amazon_cloudwatch` `AmazonCloudWatchAuditDestination`, `kafka` `KafkaAuditProvider`,
  `log4j` `Log4JAuditDestination`, `auditserver` `RangerAuditServerDestination`. Custom class via `<prefix>.classname`.
- Destination settings under `xasecure.audit.destination.<name>.*` (`.urls`, `.user`, `.password`, `.zookeepers`, `.dir`, `.log_group`, `.region`); `<prefix>.config.<k>` passes raw config.
- Queue: `<prefix>.queue` (default `batch`), settings under `<prefix>.batch.*`: `queue.size` (1048576), `batch.size` (1000), `batch.interval.ms` (3000), `filespool.enable`,
  `filespool.drain.full.wait.ms`, `filespool.drain.threshold.percent`; `AuditFileSpool` keys `filespool.dir`, `.filename.format`, `.file.prefix`, `.file.rollover.sec`,
  `.index.filename`, `.archive.dir`, `.archive.max.files`, `.destination.retry.ms`. `queue=filequeue` inserts an `AuditFileQueue`.
- Global: `xasecure.audit.is.enabled`, `xasecure.audit.provider.async.*`, `xasecure.audit.provider.summary.enabled` + `.summary.summary.interval.ms`,
  `xasecure.audit.shutdown.hook.max.wait.seconds` (30), `xasecure.audit.log.status.log.enabled`, `xasecure.audit.log.status.log.interval.sec`, `xasecure.audit.auditid.strict.uuid`.
- Kerberos: `xasecure.audit.jaas.Client.*` (`InMemoryJAASConfiguration`); SSL reuses `xasecure.policymgr.clientssl.*` via `BaseAuditHandler`.

These keys are generated into `ranger-<svc>-audit.xml` from `conf/ranger-<svc>-audit-changes.cfg` (`%XAAUDIT.<DEST>.*%` tokens from `install.properties`).

### SPI and event

`AuditDestination extends BaseAuditHandler`: `init(Properties, basePropertyName)`, `log(Collection<AuditEventBase>)` (return `false` to trigger spooling), `start()`,
`stop()`, `flush()`, `waitToComplete()`. Event type `model/AuthzAuditEvent` (`repositoryName/Type`, `user`, `eventTime`, `accessType`, `action`, `accessResult` 0/1,
`resourcePath`, `resourceType`, `policyId`, `policyVersion`, `aclEnforcer`, `clientIP`, `clientType`, `sessionId`, `requestData`, `agentId`, `agentHostname`, `eventId`,
`seqNum`, `eventCount`, `eventDurationMS`, `tags`, `datasets`, `projects`, `zoneName`, `clusterName`, `additionalInfo`; `action`/`requestData` truncated at 1800 chars).

`RangerAuditServerDestination` (`dest-auditserver`): prefix `xasecure.audit.destination.auditserver`, keys `url`, `ssl.config.file`, `authn.type` (`kerberos|basic|jwt`),
`authn.basic.username|password`, `authn.jwt.env|file`, `connection.timeout.ms`, `read.timeout.ms`, `max.retry.attempts`, `retry.interval.ms`. POSTs to `/api/audit/access?serviceName=&appId=`.

## `audit-server` (standalone)

- `audit-common` (`ranger-audit-server-common`): `AuditServerConstants`, `AuditConfig`, `AuditMessageQueueUtils`, `AuditServerUtils`.
- `audit-ingestor` (`ranger-audit-ingestor`, WAR + own `EmbeddedServer`, ports 7081/7182): `org.apache.ranger.audit.rest.AuditREST` `@Path("/audit")` with `GET /health`, `GET /status`,
  `POST /access` (`List<AuthzAuditEvent>`), full path `/api/audit/access`. Filters `AuditDelegationTokenFilter` (SPNEGO), `AuditJwtAuthFilter`. Config `ranger-audit-ingestor-site.xml`:
  `ranger.audit.ingestor.{host,http.port,https.port,contextName,kerberos.*}`, `ranger.audit.jwt.auth.*`, per-service allowlist `ranger.audit.ingestor.service.<name>.allowed.users`.
  Events go to Kafka via `AuditProducer`/`AuditMessageQueue`/`AuditPartitioner`, local fallback `AuditRecoveryManager`/`AuditRecoveryWriter`. Kafka keys reuse the
  `xasecure.audit.destination.kafka` prefix (`bootstrap.servers`, `topic.name` default `ranger_audits`, `topic.partitions`, `partitioner.class`, `producer.*`, `security.protocol`, `sasl.mechanism`).
- `audit-dispatcher`: `dispatcher-app` (WAR, `AuditDispatcherHealthREST` `/health/ping|status`), `dispatcher-common` (`AuditDispatcherLauncher`, `EmbeddedServer`, `AuditDispatcher`,
  `AuditDispatcherBase`, `AuditDispatcherFactory`, `AuditDispatcherTracker`, `AuditDispatcherRebalanceListener`), backends `dispatcher-hdfs` (`AuditHDFSDispatcher`, `HdfsDispatcherManager`),
  `dispatcher-opensearch` (`AuditOpenSearchDispatcher`), `dispatcher-solr` (`AuditSolrDispatcher`). `ranger.audit.dispatcher.type` selects the manager (isolated classloader);
  consumer tuning `ranger.audit.dispatcher.{thread.count,max.poll.records,offset.commit.strategy,offset.commit.interval.ms,session.timeout.ms,partition.assignment.strategy}`;
  per-backend `conf/ranger-audit-dispatcher-<dest>-site.xml`. No CloudWatch dispatcher exists (CloudWatch is an `agents-audit` destination only).

## Admin bootstrap (DB patch 078)

`security-admin/db/*/patches/078-add-x_audit_config.sql` creates `x_audit_config(id, create_time, update_time, cfg_name UNIQUE, cfg_value, version)`, seeds the
`rangerauditserver` machine user (`x_portal_user` status 0, role `ROLE_ADMIN_AUDITOR`, `x_user`) and rows `ingestor.url`, `service.hive.allowed.users`, `audit.partition.plan`.
No Java entity exists yet (see `security-admin-db` `references/schema-map.md`).

## Docker

`docker-compose.ranger-audit-service.yml`: `ranger-kafka`, `ranger-solr` (8983), `ranger-opensearch` (9200/9300), `ranger-audit-ingestor` (7081/7182),
`ranger-audit-dispatcher-{solr,hdfs,opensearch}` (7091/7092/7093), selected by `--profile ${AUDIT_DESTINATIONS}`.

## Checklist: new audit destination

1. Module `agents-audit/dest-<x>` (artifact `ranger-audit-dest-<x>`), added to `agents-audit/pom.xml` `<modules>` (sorted).
2. `<X>AuditDestination extends AuditDestination` in `org.apache.ranger.audit.destination`; read every property off the `basePropertyName` passed to `init()`.
3. Implement `log(...)` (return `false` on failure so the queue spools), `start`, `stop`, `flush`. Test `<X>AuditDestinationTest` (`*Test.java` naming in this module).
4. Plugin wiring: `xasecure.audit.destination.<x>` block in each `ranger-<svc>-audit-changes.cfg` + `XAAUDIT.<X>.*` in `install.properties`, dependency in the plugin pom and distro assembly.
5. Server-side consumer only if needed: `dispatcher-<x>` mirroring `dispatcher-opensearch`, its `-site.xml`, assembly descriptor, compose service.
