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

# Audit filters

Busy platforms produce enormous numbers of access audits, most of them from service accounts and
housekeeping operations that nobody needs to see. Audit filters let you decide, per Ranger service, which
accesses are recorded and which are not, before the record is ever created. This keeps the audit store
compact and the interesting events easy to find, without touching any policy.

Ranger offers several complementary controls, from coarse to fine:

| Control | Where | Effect |
| --- | --- | --- |
| "Audit Logging" toggle | Each policy | Accesses decided by that policy are audited or not. |
| Excluded users, groups, roles | Service or plugin configuration | Accesses by these identities are not audited. |
| Audit filters | Service configuration | Rule list matched on resource, identity, action, access type and result. |
| "Exclude Service Users" | Access Audits page of the Admin UI | Hides service-user records in the UI only; records are still stored. |

The first three are evaluated inside the plugin (or PDP), so they reduce what is written to every destination.

## Audit filters

### Format

`ranger.plugin.audit.filters` holds a JSON list. Each entry is a simplified policy: a set of match
conditions plus the decision `isAudited`. Entries are evaluated in list order and the first matching entry
wins.

```json
[
  { "accessResult": "DENIED", "isAudited": true },
  { "users": [ "hive", "hdfs" ], "groups": [ "service-accounts" ], "roles": [ "etl" ], "isAudited": false },
  { "actions": [ "listStatus", "getfileinfo" ], "accessTypes": [ "execute" ], "isAudited": false },
  { "resources": { "path": { "values": [ "/tmp" ], "isRecursive": true } }, "isAudited": false }
]
```

`resources`
:   Map of resource name to a resource value object (see below). Matches when the requested resource
    matches, using the same matcher as policies for that service type; wildcards and recursion apply.
    Omitted: any resource.

`users`, `groups`, `roles`
:   Lists of strings. Match when the requesting user is listed, belongs to a listed group, or holds a listed
    role. `users` may contain the macro `{OWNER}`, which matches the owner of the accessed resource.
    Omitted: any user.

`actions`
:   List of strings. Matches when the request's *action*, the component-specific operation such as
    `listStatus`, `METADATA OPERATION` or `QUERY`, is listed.

`accessTypes`
:   List of strings. Matches when the request's *access type* (`read`, `write`, `select`, `execute`, ...)
    is listed. `actions` and `accessTypes` are checked together: either match satisfies the entry.

`accessResult`
:   One of `ALLOWED`, `DENIED`, `NOT_DETERMINED`: the result of authorization. `NOT_DETERMINED` means no
    policy matched (the plugin fell back to native permissions). Omitted: any result.

`isAudited`
:   Boolean. The decision for matching requests.

A resource value object has the same shape as in a policy:

```json
{ "values": [ "/tmp" ], "isRecursive": true, "isExcludes": false }
```

The model class is
[`AuditFilter`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/plugin/model/AuditFilter.java);
the evaluator is `RangerAuditPolicyEvaluator`.

### Evaluation semantics

1. After authorization, the plugin evaluates the audit filters of the **tag service** first (for accesses
   that matched a tag) and then those of the **resource service**. The first matching entry decides.
2. If no filter matches, the audit decision of the *policy* that authorized the access applies (the
   policy's "Audit Logging" flag). If the service has no policies at all, the access is audited; if policies
   exist but none matches, the framework records nothing (individual plugins may still audit their native
   fallback, for example HDFS records `hadoop-acl` decisions).
3. Excluded users, groups and roles (next section) are applied when the result is created, before policies
   and filters run: they override the policy's audit flag, but a *matching* audit filter with
   `isAudited: true` re-enables auditing for that access. Keep exclusion lists and filters consistent.
4. Filters that carry no `resources` match every resource; those that carry no `users`/`groups`/`roles`
   match every user; and so on. An entry with only `isAudited` therefore matches everything, so put such
   catch-all entries last.

### Configuring filters

In the Ranger Admin UI open **Service Manager**, edit the service, and under **Add New Configurations**
add the name `ranger.plugin.audit.filters` with the JSON list as the value. Plugins pick up the change at
their next policy download (no restart required). The same works for a tag service (for example `dev_tag`),
where the resource is `tag`.

With the REST API, update the service's `configs`:

```bash
# fetch, edit, and put back the service definition
curl -u admin:password http://ranger-admin:6080/service/public/v2/api/service/name/dev_hive > dev_hive.json
# add "ranger.plugin.audit.filters": "[ ... ]" to "configs" in dev_hive.json, then:
curl -u admin:password -X PUT -H 'Content-Type: application/json' \
  http://ranger-admin:6080/service/public/v2/api/service/name/dev_hive -d @dev_hive.json
```

Because the value is a string inside JSON, quote it carefully (the examples below show the raw value you
enter in the UI).

### Examples

HDFS: keep every denial, drop directory listings and everything under `/tmp`, but always audit `/finance`:

```json
[
  { "accessResult": "DENIED", "isAudited": true },
  { "actions": [ "listStatus", "getfileinfo" ], "accessTypes": [ "execute" ], "isAudited": false },
  { "resources": { "path": { "values": [ "/finance" ], "isRecursive": true } }, "isAudited": true },
  { "resources": { "path": { "values": [ "/tmp" ], "isRecursive": true } }, "isAudited": false }
]
```

Hive: skip metadata operations and a scratch database, and skip user `etl_user` on `sys.dump`:

```json
[
  { "accessResult": "DENIED", "isAudited": true },
  { "actions": [ "METADATA OPERATION" ], "isAudited": false },
  { "resources": { "database": { "values": [ "temp" ] }, "table": { "values": [ "*" ] }, "column": { "values": [ "*" ] } }, "isAudited": false },
  { "resources": { "database": { "values": [ "sys" ] }, "table": { "values": [ "dump" ] } }, "users": [ "etl_user" ], "isAudited": false }
]
```

Tag service: never audit resources tagged `NO_AUDIT`, always audit `PII` for everyone:

```json
[
  { "resources": { "tag": { "values": [ "NO_AUDIT" ] } }, "isAudited": false },
  { "resources": { "tag": { "values": [ "PII" ] } }, "isAudited": true }
]
```

## Excluding users, groups and roles

For service accounts that generate constant background traffic (`hbase`, `atlas`, `solr`, ...) a full
filter is unnecessary. Two property families exclude identities outright:

Set these in the **service configuration** in Ranger Admin (Add New Configurations); plugins of that
service receive them with the next policy download:

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.plugin.audit.exclude.users` | (none) | List | User names whose accesses are not audited. |
| `ranger.plugin.audit.exclude.groups` | (none) | List | Group names whose members' accesses are not audited. |
| `ranger.plugin.audit.exclude.roles` | (none) | List | Role names whose members' accesses are not audited. |

The same lists can be fixed in the plugin's own `ranger-<component>-security.xml`:

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.plugin.<serviceType>.audit.exclude.users` | (none) | List | User names whose accesses are not audited. |
| `ranger.plugin.<serviceType>.audit.exclude.groups` | (none) | List | Group names whose members' accesses are not audited. |
| `ranger.plugin.<serviceType>.audit.exclude.roles` | (none) | List | Role names whose members' accesses are not audited. |

The service-level settings apply only to the plugins of that service: excluding `hbase` on
`staging_hbase` does not affect any other service. Exclusion is checked on every access, whichever policy
matched, and on both resource and tag policies. It takes precedence over the policy audit flag but not over
a matching audit filter (see [Evaluation semantics](#evaluation-semantics)).

## Hiding service users in the Admin UI

Independently of what is stored, the Access Audits page can hide records produced by service users. Set
these properties in `ranger-admin-site.xml` and use the **Exclude Service User** toggle in the UI:

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.plugins.<serviceType>.serviceuser` | (none) | String | Service user of a component, for example `ranger.plugins.hbase.serviceuser=hbase`. |
| `ranger.accesslogs.exclude.users.list` | (none) | List | Additional users to hide, for example `yarn-ats,testUser`. |

This is a display filter only. To stop the records from being written, use the exclusion properties or
audit filters above. See [Admin UI guide](../admin/ui-guide.md) for the Audit tabs.

## Streaming audits through the logging framework

The `log4j` destination writes each audit record as a single JSON line at `INFO` level to an SLF4J logger
(`ranger.audit.log4j` by default, configurable with `xasecure.audit.destination.log4j.logger`). Whatever
logging backend the host component uses (Log4j 2 for Hadoop and Hive, Logback for others) can then route
that logger to a file, syslog, or a Kafka appender, giving you a real-time feed of audit events for
monitoring systems without a second Ranger destination.

```xml title="ranger-<component>-audit.xml"
<property><name>xasecure.audit.destination.log4j</name><value>true</value></property>
<property><name>xasecure.audit.destination.log4j.logger</name><value>ranger.audit</value></property>
```

Then, in the component's logging configuration, add an appender for the logger `ranger.audit` at level
`INFO` with a pattern that emits only the message (`%m%n`) so the JSON stays intact. Ranger does not ship
the Kafka appender itself; use the one provided by your logging framework. Consumers parse the record fields
described in [Audit schema](audit-schema.md) (`reqUser`, `resource`, `action`, `evtTime`, `cliIP`, ...).

For a supported, buffered path into Kafka use the [Audit Server](../audit-server/service.md) instead.

## Further reading

- [Audit framework](index.md), [Audit schema](audit-schema.md), [Audit stores](audit-stores.md).
- cwiki: [Ranger audit filters](https://cwiki.apache.org/confluence/display/RANGER/Ranger+Audit+Filters),
  [Blacklist for Ranger audits](https://cwiki.apache.org/confluence/display/RANGER/Blacklist+for+Ranger+Audits),
  [Exclude service user audits in the UI](https://cwiki.apache.org/confluence/display/RANGER/Feature+to+exclude+service+user+audits+in+Ranger+UI+%28Access+Audits%29),
  [Kafka log4j appender with Ranger audits](https://cwiki.apache.org/confluence/display/RANGER/Configuring+Kafka+log4j+appender+with+Apache+Ranger+Audits).
