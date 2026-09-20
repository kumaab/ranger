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

# Apache Kudu

Apache Kudu can delegate fine-grained authorization of its tables to Ranger. Policies on databases, tables
and columns are written in Ranger Admin; the Kudu master enforces them for every DDL and DML request, and
tablet servers enforce the resulting per-table and per-column privileges on scans and writes.

The enforcement point is the Kudu master. It starts a Java subprocess from `kudu-subprocess.jar` that embeds
Ranger's plugin runtime (`RangerBasePlugin`); the subprocess polls Ranger Admin for policies, caches them on
disk and sends audit events.

!!! note "Where the code lives"
    The enforcement code ships with **Apache Kudu** (since Kudu 1.12.0). Ranger provides the `kudu` service
    definition and the `plugin-kudu` module, which contains only `RangerServiceKudu`. That class currently
    returns empty results from `validateConfig()` and `lookupResource()` (marked TODO in the source), so
    *Test Connection* cannot report success and resource autocomplete is not available for Kudu services.

## Requirements

- A reachable Ranger Admin with a service of type `kudu`.
- An audit store if auditing is enabled.
- Kudu 1.12.0 or later. Ranger does not compile against Kudu; the Kudu documentation states that it works
  with policies defined in Ranger 2.1 and later.
- A Java runtime on the master hosts for the Ranger subprocess. The plugin jars come with Kudu.

## Configuration

Point the Kudu masters at a directory that holds the Ranger configuration files, and turn on enforcement on
the tablet servers. These flags are defined by Kudu; `--ranger_config_path` is what activates Ranger.

```text title="kudu-master flagfile"
# MANDATORY: directory that holds the Ranger configuration files described below.
--ranger_config_path=/etc/kudu/ranger

# Java executable used to start the Ranger subprocess.
--ranger_java_path=/usr/lib/jvm/java/bin/java

# Location of kudu-subprocess.jar, which embeds the Ranger plugin.
--ranger_jar_path=/opt/kudu/lib/kudu-subprocess.jar

# Users that bypass Ranger checks entirely, for example the Impala service user, which performs
# its own Ranger checks.
--trusted_user_acl=impala,kudu
```

```text title="kudu-tserver flagfile"
# Enforce the per-table and per-column privileges on scans and writes.
--tserver_enforce_access_control=true
```

Place the files below in the `--ranger_config_path` directory on every master, together with a
`core-site.xml` (Kerberos authentication, `hadoop.security.auth_to_local`) if the cluster is secured. Restart
masters and tablet servers afterwards.

### ranger-kudu-security.xml

This file tells the plugin which Ranger Admin to contact and which service's policies to enforce. Place it
in the `--ranger_config_path` directory of every master. `ranger.plugin.kudu.policy.rest.url` and
`ranger.plugin.kudu.service.name` are mandatory; every other property is shown with its default.

```xml title="ranger-kudu-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.kudu.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.kudu.service.name</name>
    <value>dev_kudu</value>
    <description>MANDATORY: Name of the Ranger service whose policies are enforced.</description>
  </property>
  <property>
    <name>ranger.plugin.kudu.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies. The default downloads them from Ranger Admin over
      REST.</description>
  </property>
  <property>
    <name>ranger.plugin.kudu.policy.rest.ssl.config.file</name>
    <value>/etc/kudu/ranger/ranger-kudu-policymgr-ssl.xml</value>
    <description>Path of the TLS client configuration file (ranger-kudu-policymgr-ssl.xml). Needed
      only when Ranger Admin uses HTTPS. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.kudu.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connect timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.kudu.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.kudu.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.kudu.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>Interval between policy refreshes. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.kudu.policy.cache.dir</name>
    <value>/var/lib/kudu/ranger/policycache</value>
    <description>Directory for the on-disk policy cache. It must be writable by the process that
      hosts the plugin. Default: not set.</description>
  </property>
</configuration>
```

### ranger-kudu-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-kudu-security.xml` in the
`--ranger_config_path` directory. Each destination is switched on with
`xasecure.audit.destination.<name>=true` and configured with properties under the same prefix. No property
is mandatory: without an enabled destination, no audit events are stored. The example sends audits to Solr.

```xml title="ranger-kudu-audit.xml"
<configuration>
  <!-- General -->
  <property>
    <name>xasecure.audit.is.enabled</name>
    <value>true</value>
    <description>Master switch for auditing in this plugin.</description>
  </property>
  <property>
    <name>xasecure.audit.provider.summary.enabled</name>
    <value>false</value>
    <description>Collapse events that differ only in time into one event with a count.</description>
  </property>

  <!-- Audit Server destination -->
  <property>
    <name>xasecure.audit.destination.auditserver</name>
    <value>false</value>
    <description>Send audits to the Ranger Audit Server.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.auditserver.url</name>
    <value></value>
    <description>Audit Server URL. Default: not set.</description>
  </property>

  <!-- Solr destination -->
  <property>
    <name>xasecure.audit.destination.solr</name>
    <value>true</value>
    <description>Send audits to Apache Solr. Default: false.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.solr.urls</name>
    <value>http://solr:8983/solr/ranger_audits</value>
    <description>Solr collection URLs, separated by commas. Ignored when
      xasecure.audit.destination.solr.zookeepers is set. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.solr.zookeepers</name>
    <value></value>
    <description>ZooKeeper connect string of a SolrCloud cluster. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.solr.batch.filespool.dir</name>
    <value>/var/log/kudu/audit/solr/spool</value>
    <description>Local directory where events are spooled while Solr is unreachable. Every enabled
      destination has the same property under its own prefix. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.solr.collection</name>
    <value>ranger_audits</value>
    <description>Collection name when ZooKeeper is used.</description>
  </property>

  <!-- Elasticsearch destination -->
  <property>
    <name>xasecure.audit.destination.elasticsearch</name>
    <value>false</value>
    <description>Send audits to Elasticsearch.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.elasticsearch.urls</name>
    <value></value>
    <description>Elasticsearch host names, separated by commas. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.elasticsearch.port</name>
    <value>9200</value>
    <description>REST port of the Elasticsearch cluster.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.elasticsearch.protocol</name>
    <value>http</value>
    <description>One of: http, https.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.elasticsearch.index</name>
    <value>ranger_audits</value>
    <description>Index that receives the events.</description>
  </property>

  <!-- HDFS destination -->
  <property>
    <name>xasecure.audit.destination.hdfs</name>
    <value>false</value>
    <description>Write audits as files to HDFS or a Hadoop-compatible object store.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.hdfs.dir</name>
    <value></value>
    <description>Base directory, for example hdfs://namenode:8020/ranger/audit. Default: not
      set.</description>
  </property>

  <!-- Log4j destination -->
  <property>
    <name>xasecure.audit.destination.log4j</name>
    <value>false</value>
    <description>Write audits as JSON to a logger of the host process.</description>
  </property>
  <property>
    <name>xasecure.audit.destination.log4j.logger</name>
    <value>ranger.audit.log4j</value>
    <description>Logger name used by the log4j destination.</description>
  </property>
</configuration>
```

Give every enabled destination a spool directory (`xasecure.audit.destination.<name>.batch.filespool.dir`)
so that events survive an outage of the audit store. The queue, spool, Kerberos and TLS options of each
destination, and the Audit Server client settings, are in the
Audit framework reference.

### ranger-kudu-policymgr-ssl.xml

This file is needed only when Ranger Admin is reached over `https`. The plugin loads it from the path set in
`ranger.plugin.kudu.policy.rest.ssl.config.file`; a file with this name on the classpath is picked up
automatically. No property is mandatory: without a truststore the plugin relies on the default truststore of
the JVM, and the keystore is needed only for two-way TLS. Passwords are not stored in the file: they are
read from a Hadoop credential store (JCEKS) under fixed aliases.

```xml title="ranger-kudu-policymgr-ssl.xml"
<configuration>
  <!-- Keystore (client certificate, two-way TLS) -->
  <property>
    <name>xasecure.policymgr.clientssl.keystore</name>
    <value></value>
    <description>Keystore with the plugin's client certificate. Needed only when Ranger Admin
      requires client certificates. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.keystore.credential.file</name>
    <value></value>
    <description>Hadoop credential store (JCEKS) that holds the keystore password under the alias
      sslKeyStore. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.keystore.type</name>
    <value>jks</value>
    <description>Keystore type.</description>
  </property>

  <!-- Truststore -->
  <property>
    <name>xasecure.policymgr.clientssl.truststore</name>
    <value>/etc/ranger/dev_kudu/truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_kudu/cred.jceks</value>
    <description>Hadoop credential store (JCEKS) that holds the truststore password under the alias
      sslTrustStore. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.type</name>
    <value>jks</value>
    <description>Truststore type.</description>
  </property>
</configuration>
```

When Ranger Admin validates client certificates, set `commonNameForCertificate` in the service configuration
to the CN of the plugin's certificate.

## Service definition in Ranger Admin

Create a service of type `kudu` (service definition id 105) whose name equals
`ranger.plugin.kudu.service.name`. The definition declares no connection settings, only
`ranger.plugin.audit.filters` (default `[]`). For Kerberized deployments, add `policy.download.auth.users`
with the short name of the Kudu master principal so Ranger Admin allows policy download. *Test Connection*
does not report success and autocomplete returns nothing because `RangerServiceKudu` does not implement them.

## Resources and permissions

From `ranger-servicedef-kudu.json`. All resources support wildcards, case-insensitive matching and excludes.

| Resource | Parent | Description |
|---|---|---|
| `database` | — | Kudu has no native databases; the database is the prefix of the table name in `<database>.<table>`. |
| `table` | `database` | Table. |
| `column` | `table` | Column. |

| Access type | Implied grants |
|---|---|
| `select`, `insert`, `update`, `delete`, `alter`, `create`, `drop` | `metadata` |
| `metadata` | — (required to open a table and read its schema) |
| `all` | every access type above |

No data masking, row filtering, policy conditions or context enrichers are declared.

## Required policies

Ranger Admin creates one *all* policy on `database/table/column` for the service's default policy users.
Grant the Kudu service users and administrators what they need explicitly, or list trusted service users in
`--trusted_user_acl`.

## Behavior notes

- Table names are split at the first dot into `database` and `table`; a name without a dot falls into
  the database named by the Kudu master flag `--ranger_default_database` (default `default`). Kudu treats other
  characters as part of the name, so Impala-managed tables such as `impala::bar.foo` map to database
  `impala::bar`, table `foo`.
- A scan needs `select` on the table, or `metadata` on the table plus `select` on every projected and
  predicate column. Column-level policies therefore work for reads; Kudu exposes a table's schema on an
  all-or-nothing basis rather than hiding unauthorized columns.
- Because every specific privilege implies `metadata`, granting `select` alone is enough to open a table.
- Delegated administration (`delegateAdmin` in a policy item) lets a user grant privileges on the same
  resource to others.
- Kudu applies Ranger authorization only when `--ranger_config_path` is set; without it, only Kudu's
  coarse-grained `--user_acl`/`--superuser_acl` checks apply.

## Auditing

The subprocess sends audit events with service type `kudu`, resource `database/table[/column]`, the access
type and the result to the destinations configured in `ranger-kudu-audit.xml`. The default audit filter list
is empty.

## Further reading

- [Apache Kudu security documentation](https://kudu.apache.org/docs/security.html) — fine-grained
  authorization with Ranger, flag reference
