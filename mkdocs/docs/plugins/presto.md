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

# Presto

Ranger can decide who may query, change or administer data that is reachable through a Presto (PrestoSQL)
coordinator. The integration plugs into Presto's *system access control* extension point, so every catalog,
schema, table and column check that Presto performs is answered from Ranger policies, and every decision can
be audited. Column masking and row filtering are supported as well.

The enforcement point is the Presto coordinator. Presto loads the shim `PrestoRangerPlugin` (module
`ranger-presto-plugin-shim`, registered in `META-INF/services/io.prestosql.spi.Plugin`), which uses
`ranger-plugin-classloader` to load `RangerSystemAccessControl` (module `plugin-presto`) from
`lib/ranger-presto-plugin-impl`, so Ranger's dependencies do not clash with Presto's. The authorizer
implements `io.prestosql.spi.security.SystemAccessControl`, polls Ranger Admin for policies and caches them
on disk.

!!! warning "PrestoSQL 333"
    Ranger master builds this plugin against PrestoSQL **333** (`presto.version` in the root `pom.xml`,
    group `io.prestosql`). The PrestoSQL project was renamed Trino at release 351. If you run Trino, use the
    [Trino](trino.md) integration instead.

## Requirements

- A reachable Ranger Admin with a service of type `presto`.
- An audit store if auditing is enabled.
- PrestoSQL 333. The plugin also builds against Airlift 0.192 and Guice 4.2.2 (`presto.airlift.version`,
  `presto.guice.version`).
- The plugin jars from the Ranger build: the plugin archive `ranger-<version>-presto-plugin.tar.gz` contains
  the shim jars in `lib/` and the implementation in `lib/ranger-presto-plugin-impl/`. They belong in a plugin
  directory of the coordinator (for example `<presto>/plugin/ranger/`).

## Configuration

Activate Ranger in Presto's `etc/access-control.properties`. The properties are read by the shim
(`RangerConfig`); only `access-control.name` is mandatory.

```properties title="etc/access-control.properties"
# MANDATORY: selects the Ranger system access control.
access-control.name=ranger

# Kerberos principal. When set together with ranger.keytab, the plugin logs in with
# UserGroupInformation.loginUserFromKeytab before it contacts Ranger Admin or the audit store.
# Default: not set.
ranger.principal=presto/_HOST@EXAMPLE.COM

# Keytab for ranger.principal. Default: not set.
ranger.keytab=/etc/security/keytabs/presto.service.keytab

# Resolve the user's groups through Hadoop UserGroupInformation instead of the groups in
# Presto's identity.
ranger.use_ugi=false

# Hadoop configuration resource to load from the classpath.
ranger.hadoop_config=presto-ranger-site.xml
```

The plugin reads the three Ranger files below from its classpath; templates are in
[`plugin-presto/conf`](https://github.com/apache/ranger/tree/master/plugin-presto/conf). Restart Presto after
changing them, then check that the plugin appears under **Audit → Plugin Status** in Ranger Admin.

### ranger-presto-security.xml

This file tells the plugin which Ranger Admin to contact and which service's policies to enforce. The plugin
reads it from its classpath. `ranger.plugin.presto.policy.rest.url` and `ranger.plugin.presto.service.name`
are mandatory; every other property is shown with its default.

```xml title="ranger-presto-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.presto.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.service.name</name>
    <value>dev_presto</value>
    <description>MANDATORY: Name of the Ranger service whose policies are enforced.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies. The default downloads them from Ranger Admin over
      REST.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.policy.rest.ssl.config.file</name>
    <value>/etc/presto/ranger-policymgr-ssl.xml</value>
    <description>Path of the TLS client configuration file (ranger-policymgr-ssl.xml). Needed only
      when Ranger Admin uses HTTPS. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connect timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.presto.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>Interval between policy refreshes. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.policy.cache.dir</name>
    <value>/etc/ranger/dev_presto/policycache</value>
    <description>Directory for the on-disk policy cache. It must be writable by the process that
      hosts the plugin. Default: not set.</description>
  </property>

  <!-- Users, groups and roles -->
  <property>
    <name>ranger.plugin.presto.use.rangerGroups</name>
    <value>false</value>
    <description>Add the groups Ranger knows for the user (from UserSync) to the groups supplied
      with the request.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.use.only.rangerGroups</name>
    <value>false</value>
    <description>Ignore the groups supplied with the request and use only the groups Ranger knows
      for the user.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.super.users</name>
    <value></value>
    <description>Comma-separated list of users that are authorized for every access without a
      policy. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.super.groups</name>
    <value></value>
    <description>Comma-separated list of groups whose members are authorized for every access
      without a policy. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.presto.audit.exclude.users</name>
    <value></value>
    <description>Comma-separated list of users whose accesses are not audited.
      ranger.plugin.presto.audit.exclude.groups and ranger.plugin.presto.audit.exclude.roles work
      the same way for groups and roles. Default: not set.</description>
  </property>
</configuration>
```

### ranger-presto-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-presto-security.xml` on the
plugin's classpath. Each destination is switched on with `xasecure.audit.destination.<name>=true` and
configured with properties under the same prefix. No property is mandatory: without an enabled destination,
no audit events are stored. The example sends audits to Solr.

```xml title="ranger-presto-audit.xml"
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
    <value>/var/log/presto/audit/solr/spool</value>
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

### ranger-policymgr-ssl.xml

This file is needed only when Ranger Admin is reached over `https`. The plugin loads it from the path set in
`ranger.plugin.presto.policy.rest.ssl.config.file`; a file named `ranger-presto-policymgr-ssl.xml` on the
classpath is picked up automatically. No property is mandatory: without a truststore the plugin relies on
the default truststore of the JVM, and the keystore is needed only for two-way TLS. Passwords are not stored
in the file: they are read from a Hadoop credential store (JCEKS) under fixed aliases.

```xml title="ranger-policymgr-ssl.xml"
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
    <value>/etc/ranger/dev_presto/truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_presto/cred.jceks</value>
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

Create a service of type `presto` (service definition id 17) whose name equals
`ranger.plugin.presto.service.name`.

| Field | Required | Description |
|---|---|---|
| `username` | yes | User for *Test Connection* and resource lookup. |
| `jdbc.url` | yes | For example `jdbc:presto://presto-coordinator:8080`. |
| `jdbc.driverClassName` | yes | JDBC driver. Default: `io.prestosql.jdbc.PrestoDriver`. |
| `password` | no | Password of the lookup user. |

`RangerServicePresto` implements *Test Connection* and autocomplete for catalogs, schemas, tables and
columns over JDBC. Ranger Admin loads it from `ews/webapp/WEB-INF/classes/ranger-plugins/presto`.

## Resources and permissions

From `ranger-servicedef-presto.json`. All resources accept wildcards and match case-insensitively;
`catalog`, `schema`, `table` and `column` support *exclude* and resource lookup.

| Resource | Parent | Access types allowed |
|---|---|---|
| `catalog` | — | any |
| `schema` | `catalog` | any |
| `table` | `schema` | any |
| `column` | `table` | any |
| `sessionproperty` | `catalog` | `alter` |
| `procedure` | `schema` | `execute`, `grant` |
| `prestouser` | — | `impersonate` |
| `systemproperty` | — | `alter` |
| `function` | — | `execute`, `grant` |

Access types: `select`, `insert`, `create`, `drop`, `delete`, `use`, `alter`, `grant`, `revoke`, `show`,
`impersonate`, `execute`, and `all` (implies every other type).

- **Data masking** on `catalog/schema/table/column` for `select`, with mask types `MASK`,
  `MASK_SHOW_LAST_4`, `MASK_SHOW_FIRST_4`, `MASK_HASH`, `MASK_NULL`, `MASK_NONE`, `MASK_DATE_SHOW_YEAR` and
  `CUSTOM`. The transformers are Presto SQL, for example `MASK_HASH` is
  `cast(to_hex(sha256(to_utf8({col}))) as {type})`.
- **Row filtering** on `catalog/schema/table` for `select`.
- No policy conditions or context enrichers are declared.

## Required policies

When you create the service, Ranger Admin generates one *all* policy per resource hierarchy for the lookup
user (`username`) and any configured default policy users. Everybody else needs explicit policies: an access
without a matching Ranger policy is denied. Users who run queries need at least `use` on the catalogs and
`select` on the schemas, tables and columns they read; query execution itself is not checked.

## Behavior notes

`RangerSystemAccessControl` maps Presto checks to Ranger access types as follows:

| Presto operation | Resource | Access type |
|---|---|---|
| Access a catalog | `catalog` | `use` |
| Filter catalogs, schemas, tables in metadata listings | `catalog`, `schema`, `table` | `select` |
| SHOW SCHEMAS, SHOW TABLES, SHOW COLUMNS, SHOW CREATE, SHOW ROLES | `catalog` / `schema` / `table` | `show` |
| CREATE SCHEMA, CREATE TABLE, CREATE VIEW | parent `catalog` / `schema` | `create` |
| DROP SCHEMA / TABLE / VIEW, DROP COLUMN | the object | `drop` |
| RENAME SCHEMA / TABLE / VIEW / COLUMN, ADD COLUMN, COMMENT ON TABLE | the object | `alter` |
| SELECT from columns | each `column` (table if no columns) | `select` |
| INSERT / DELETE | `table` | `insert` / `delete` |
| GRANT / REVOKE table privilege, SET SCHEMA AUTHORIZATION | `table` / `schema` | `grant` / `revoke` |
| SET SESSION (system) / SET SESSION (catalog) | `systemproperty` / `sessionproperty` | `alter` |
| Execute function / procedure, grant execute on function | `function` / `procedure` | `execute` / `grant` |
| Impersonate user, view or kill another user's query | `prestouser` | `impersonate` |
| Execute query | — | always allowed (no check) |

Other points:

- `filterColumns` returns all columns; column-level restrictions are enforced at `SELECT` time rather than
  by hiding columns.
- `CREATE VIEW ... AS SELECT` is authorized exactly like `CREATE VIEW` (`create` on the schema); the selected
  columns are not checked again for the view definition.
- The plugin does not fall back to Presto's own access control.
- Every request carries the Presto user, its groups (from Presto's identity or UGI) and the access time.

## Auditing

Audit records use service type `presto`, the resource path (`catalog.schema.table.column` as applicable),
the mapped access type and the allow/deny result. The service definition declares no default audit filters,
so add audit filters in the service configuration if you want to skip
frequent metadata calls.

## Further reading

- [Trino](trino.md) — for Trino (PrestoSQL 351 and later)
- cwiki: [Presto Plugin](https://cwiki.apache.org/confluence/display/RANGER/Presto+Plugin)
