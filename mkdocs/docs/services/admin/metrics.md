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

# Metrics

Ranger Admin publishes numbers about itself (JVM memory, threads, web container connections) and about
the data it manages (how many services, policies, users, groups, zones and so on). Operators scrape them
with Prometheus or read them as JSON; the same counters are also available from the command line for
scripts. A second family of metrics, *audit metrics*, summarizes access-audit volumes for the UI.

## Endpoints

All metric endpoints are under `/service/metrics` and are served **without authentication** (the path is
excluded from the security filter chain), which makes scraping easy but also means you should not expose
the port to untrusted networks without a proxy.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/service/metrics/prometheus` | Every metric from the sources below, in Prometheus text exposition format |
| `GET` | `/service/metrics/json` | The same data as JSON, grouped by source |
| `GET` | `/service/metrics/status` | JSON with JVM name, version, vendor, uptime and heap/non-heap memory figures |

```bash
curl -s http://localhost:6080/service/metrics/prometheus | head
curl -s http://localhost:6080/service/metrics/json | jq '.Summary'
```

Prometheus scrape configuration:

```yaml
scrape_configs:
  - job_name: ranger-admin
    metrics_path: /service/metrics/prometheus
    static_configs:
      - targets: ['ranger-admin-1:6080', 'ranger-admin-2:6080']
```

## What is collected

Metrics are produced by the `ranger-metrics` module (`RangerMetricsSystemWrapper`, built on the Hadoop
metrics2 framework) with sources registered by `RangerAdminMetricsWrapper` in `security-admin`. Each
source refreshes its gauges from the database on a schedule and exposes them through the Prometheus and
JSON sinks (`RangerMetricsPrometheusSink`, `RangerMetricsJsonSink`).

| Source | Metrics | Meaning |
| --- | --- | --- |
| `UserGroup` | `UserCount` per user role, `GroupCount`, `UserSyncLastUpdated` | Identity inventory |
| `Service` | `ServiceCount` per service type, with a total | Registered services |
| `Policy` | `ResourceAccessCount`, `RowFilteringCount` and `MaskingCount` per service type, `SecurityZonePolicy` | Policies by type |
| `ContextEnrichers` | `ContextEnricherCount` per service type | Context enrichers in use |
| `DenyConditions` | `DenyConditionCount` per service type | Policies with deny conditions |
| `Summary` | `Summary`-prefixed totals, for example `TotalPolicies`, `TotalTagPolicies`, `TotalRoles`, `TotalSecurityZones`, `TotalPlugins` | One-glance overview |
| `Gds` | `GdsCount` for datasets, data shares, shared resources, projects | Governed Data Sharing inventory |
| `RangerJvm` | Memory, garbage collection, threads and system load (listed below) | JVM health |
| `RangerWebContainer` | Connections and worker threads (listed below) | Embedded Tomcat connector |

`RangerJvm` metrics
:   `MemoryCurrent`, `MemoryMax`, `GcCountTotal`, `GcTimeTotal`, `GcTimeMax`, `ThreadsBusy`,
    `ThreadsBlocked`, `ThreadsWaiting`, `ThreadsRemaining`, `ProcessorsAvailable`, `SystemLoadAvg`

`RangerWebContainer` metrics (read through `EmbeddedServerMetricsCollector`)
:   `MaxConnectionsCount`, `ActiveConnectionsCount`, `ConnectionAcceptCount`, `ConnectionTimeout`,
    `KeepAliveTimeout`, `MaxWorkerThreadsCount`, `MinSpareWorkerThreadsCount`, `ActiveWorkerThreadsCount`,
    `TotalWorkerThreadsCount`

The JVM and web-container sources live in `ranger-metrics` and are shared with Ranger KMS, UserSync and
TagSync, which expose the same `RangerJvm`/`RangerWebContainer` metrics on their own ports. The
Admin-specific sources are in
[`security-admin/src/main/java/org/apache/ranger/metrics/source`](https://github.com/apache/ranger/blob/master/security-admin/src/main/java/org/apache/ranger/metrics/source).

## Command-line metrics

`ranger-admin-services.sh metric -type <type>` runs `org.apache.ranger.patch.cliutil.MetricUtil` with the
Admin classpath and prints a JSON document. `JAVA_HOME` must be set.

| `-type` | Output |
| --- | --- |
| `policies` | Policy counts per service type, split into resource access, row filtering, masking and tag policies |
| `audits` | Access audit counts from the audit store (denied, allowed, per service type) |
| `usergroup` | User and group counts by sync source and role |
| `services` | Service counts per service type |
| `database` | Database flavor and version as seen by Admin |
| `contextenrichers` | Context enrichers per service type |
| `denyconditions` | Policies with deny conditions per service type |

```bash
ews/ranger-admin-services.sh metric -type policies
```

This is useful for cron jobs and support bundles because it needs no HTTP access, only the database and
the configuration on the host.

## Audit metrics

The **Audits > Metrics** tab and the `audit` REST resource aggregate access-audit events from the audit
store per service:

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/service/audit/metrics` | Metrics for all services over the requested range |
| `GET` | `/service/audit/metrics/{id}` | Metrics for one service by id (`timezone` query parameter) |
| `GET` | `/service/audit/metrics/servicetype/{servicetype}/servicename/{servicename}` | Latest metrics for one service |
| `GET` | `/service/audit/dailymetrics` | Per-day breakdown |
| `GET` | `/service/audit/daysmetrics` | Multi-day series for graphs |

These endpoints require an authenticated user with access to the Audit module. The look-back window is
capped by `ranger.audit.metrics.max.supported.days` (default 90). The numbers come from the audit store,
so they are only as complete as the audit pipeline; see [Audit framework](../audit/index.md).

## Health endpoints

Not metrics, but usually scraped together with them:

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/service/actuator/health` | Overall status. No authentication. |
| `GET` | `/service/actuator/health/liveness` | Process is up. No authentication. |
| `GET` | `/service/actuator/health/readiness` | Ready to serve. Requires authentication as the `ranger.admin.healthcheck.username` user. |

See [High availability](high-availability.md#setting-up-multiple-admin-instances) for how load balancers
use them.

## Further reading

- [`ranger-metrics`](https://github.com/apache/ranger/blob/master/ranger-metrics) module
- [`MetricsREST.java`](https://github.com/apache/ranger/blob/master/security-admin/src/main/java/org/apache/ranger/rest/MetricsREST.java),
  [`AuditMetricsREST.java`](https://github.com/apache/ranger/blob/master/security-admin/src/main/java/org/apache/ranger/rest/AuditMetricsREST.java),
  [`MetricUtil.java`](https://github.com/apache/ranger/blob/master/security-admin/src/main/java/org/apache/ranger/patch/cliutil/MetricUtil.java)
