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

# Apache Hive

The Ranger Hive plugin (service type `hive`, shown as **Hadoop SQL** in the Admin UI) authorizes SQL
statements executed through HiveServer2. Policies can be as coarse as a whole database or as fine as
a single column, and the plugin also supports **row-level filtering** and **column masking**, so
different users can run the same query and see different data.

The plugin is a Hive *authorizer* (`RangerHiveAuthorizerFactory`) that HiveServer2 loads through
`hive.security.authorization.manager`. Hive calls it for every statement with the list of input and
output objects (databases, tables, columns, functions, URIs); the plugin evaluates each object against
the policies cached in the HiveServer2 process and rejects the statement if any object is denied.
Policies are refreshed from Ranger Admin by polling, so HiveServer2 keeps working when Admin is down.

Beyond tables and columns the service definition has three special resources: `url` for file-system
locations referenced by statements, `hiveservice` for service-level operations such as killing a
query, and `global` for temporary UDF administration.

## Requirements

- A Ranger Admin instance that every HiveServer2 can reach over HTTP or HTTPS, with a service of type
  `hive` (**Hadoop SQL**) defined in it.
- An audit destination reachable from HiveServer2, if auditing is enabled.
- Apache Hive. Ranger master builds the plugin against Hive **4.0.1** (`hive.version` in the root
  `pom.xml`); the Docker environment runs Hive 4.0.1 on Hadoop 3.4.2 with Tez 0.10.4.
- The plugin jars. The Ranger build produces `ranger-<version>-hive-plugin.tar.gz` (see
  [Build from source](../dev/build.md)); its `lib/` directory holds the plugin shim jars and the
  `ranger-hive-plugin-impl` directory with the implementation and its dependencies.

## Configuration

Activating the plugin on a HiveServer2 takes three things: the plugin jars on the HiveServer2
classpath, the authorizer switched on in `hiveserver2-site.xml`, and the Ranger configuration files in
the Hive configuration directory. Repeat this on every HiveServer2 instance, then restart them.

Copy the content of the archive's `lib/` directory, including the `ranger-hive-plugin-impl`
sub-directory, to `$HIVE_HOME/lib`. Then set the following in `hiveserver2-site.xml`:

```xml title="hiveserver2-site.xml"
<property>
  <name>hive.security.authorization.enabled</name>
  <value>true</value>
</property>
<property>
  <name>hive.security.authorization.manager</name>
  <value>org.apache.ranger.authorization.hive.authorizer.RangerHiveAuthorizerFactory</value>
</property>
<property>
  <name>hive.security.authenticator.manager</name>
  <value>org.apache.hadoop.hive.ql.security.SessionStateUserAuthenticator</value>
</property>
```

Also append the three property names to `hive.conf.restricted.list`, so that a session cannot
override them with `SET`:

```text
hive.security.authorization.enabled,hive.security.authorization.manager,hive.security.authenticator.manager
```

The plugin reads `ranger-hive-security.xml`, `ranger-hive-audit.xml` and `ranger-policymgr-ssl.xml`
from the classpath. Place them in the Hive configuration directory (`$HIVE_CONF_DIR`, usually
`$HIVE_HOME/conf`), readable by the user that runs HiveServer2.

!!! note
    These settings apply to HiveServer2 only. Clients that talk to the metastore directly (Spark,
    Impala, ...) are not authorized by this configuration; see
    [Metastore-side authorization](#metastore-side-authorization).

### ranger-hive-security.xml

This file tells the plugin which Ranger service it enforces, how to reach Ranger Admin and where to cache
policies. Place it in `$HIVE_CONF_DIR`. `ranger.plugin.hive.service.name` and
`ranger.plugin.hive.policy.rest.url` are mandatory; the policy cache directory lets this HiveServer2 start
and keep enforcing policies when Ranger Admin is unreachable. The last group controls how the plugin treats
SQL `GRANT`/`REVOKE`, locations in statements, and tables that are only partly visible to a user.

```xml title="ranger-hive-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.hive.service.name</name>
    <value>dev_hive</value>
    <description>MANDATORY: Name of the Ranger service whose policies this HiveServer2
      enforces.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.rest.ssl.config.file</name>
    <value>/etc/hive/conf/ranger-policymgr-ssl.xml</value>
    <description>Path of ranger-policymgr-ssl.xml. Read when the Ranger Admin URL uses https.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.rest.client.username</name>
    <value></value>
    <description>User name sent with HTTP basic authentication when the plugin downloads policies.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.rest.client.password</name>
    <value></value>
    <description>Password for policy.rest.client.username. Basic authentication is used only when
      both are set. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connection timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.rest.client.retry.interval.ms</name>
    <value>1000</value>
    <description>Wait time between retries. Unit: milliseconds.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.hive.policy.cache.dir</name>
    <value>/etc/ranger/dev_hive/policycache</value>
    <description>Directory for the policy cache file. It must be writable by the user that runs the
      process. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>How often the plugin asks Ranger Admin for policy changes. Unit:
      milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies.</description>
  </property>

  <!-- Authorization behavior -->
  <property>
    <name>xasecure.hive.update.xapolicies.on.grant.revoke</name>
    <value>true</value>
    <description>Let SQL GRANT and REVOKE create or update Ranger policies. When false these
      statements fail.</description>
  </property>
  <property>
    <name>ranger.plugin.hive.urlauth.filesystem.schemes</name>
    <value>hdfs:,file:</value>
    <description>Comma-separated URI schemes that are authorized with file-system permissions
      instead of Ranger url policies.</description>
  </property>
  <property>
    <name>xasecure.hive.uri.permission.coarse.check</name>
    <value>false</value>
    <description>When true, the file-system permission check on a URI does not descend into
      sub-directories.</description>
  </property>
  <property>
    <name>xasecure.hive.block.update.if.rowfilter.columnmask.specified</name>
    <value>true</value>
    <description>Deny UPDATE/ALTER on a table when a row filter or column mask applies to the user,
      to avoid writes based on partial data.</description>
  </property>
  <property>
    <name>xasecure.hive.describetable.showcolumns.authorization.option</name>
    <value>NONE</value>
    <description>Access required for DESCRIBE/SHOW COLUMNS. One of: NONE (select on the table
      columns), show-all (any access on the table), show-allowed (currently behaves like
      NONE).</description>
  </property>
</configuration>
```

### ranger-hive-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-hive-security.xml`. Each
destination is switched on with `xasecure.audit.destination.<name>=true` and configured with properties
under the same prefix. No property is mandatory: without an enabled destination, no audit events are
stored. The example sends audits to Solr.

```xml title="ranger-hive-audit.xml"
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
    <value>/var/log/hive/audit/solr/spool</value>
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
destination, and the [Audit Server](../services/audit-server/service.md) client settings, are in the
[Audit framework](../services/audit/index.md) reference.

### ranger-policymgr-ssl.xml

This file is needed only when Ranger Admin is reached over `https`. The plugin loads it from the path set in
`ranger.plugin.hive.policy.rest.ssl.config.file`; a file named `ranger-hive-policymgr-ssl.xml` on the
classpath is picked up automatically. No property is mandatory: without a truststore the plugin relies on
the default truststore of the JVM, and the keystore is needed only for two-way TLS. Passwords are not
stored in the file: they are read from a Hadoop credential store (JCEKS) under fixed aliases.

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
    <value>/etc/hive/conf/ranger-plugin-truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_hive/cred.jceks</value>
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
to the CN of the plugin's certificate. See [Security hardening](../services/admin/security-hardening.md).

## Service definition in Ranger Admin

Choose **Hadoop SQL** in Service Manager and create a service. Its name must match
`ranger.plugin.hive.service.name` on HiveServer2.

| Field | Required | Description |
|-------|----------|-------------|
| `username` | yes | User that Ranger Admin connects as for Test Connection and resource lookup. |
| `password` | yes | Password of that user. |
| `jdbc.driverClassName` | yes | JDBC driver class. Default: `org.apache.hive.jdbc.HiveDriver`. |
| `jdbc.url` | yes | HiveServer2 JDBC URL; see the examples below. |
| `commonNameForCertificate` | no | Expected CN of the plugin's client certificate when Ranger Admin runs with two-way TLS. |
| `ranger.plugin.audit.filters` | no | Default audit filters, downloaded by the plugin together with the policies. Default: see [Auditing](#auditing). |

```text title="jdbc.url examples"
jdbc:hive2://<host>:10000
jdbc:hive2://<host>:10000/;transportMode=http;httpPath=<path>
jdbc:hive2://<host>/;serviceDiscoveryMode=zooKeeper;zooKeeperNamespace=hiveserver2
jdbc:hive2://<host>:10000/;principal=hive/_HOST@REALM
```

**Test Connection** opens a JDBC connection and lists the databases. **Resource lookup** queries
databases, tables, columns and functions through the same connection, so the lookup user needs
`select` on the objects that should be suggested in the policy editor.

## Resources and permissions

Source:
[`ranger-servicedef-hive.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-hive.json).

### Resources

A policy uses exactly one of these hierarchies: `database` → `table` → `column`, `database` → `udf`,
`url`, `hiveservice` or `global`. All resources accept wildcards. `database`, `table`, `column` and
`udf` are matched case-insensitively and support *exclude*; the other three are case-sensitive.

| Resource | Parent | Lookup | Description |
|----------|--------|--------|-------------|
| `database` | — | yes | Hive database. A policy may stop at this level. |
| `table` | `database` | yes | Table or view. A policy may stop at this level. |
| `column` | `table` | yes | Column of a table. |
| `udf` | `database` | yes | Permanent function registered in a database. |
| `url` | — | no | File-system URI such as `s3a://bucket/path`. Supports the *recursive* flag; matched by `RangerURLResourceMatcher`. |
| `hiveservice` | — | no | The HiveServer2 service, for `serviceadmin` operations. |
| `global` | — | no | Used for temporary UDF administration (`tempudfadmin`). |

### Access types

| Access type | Category | Typical statements |
|-------------|----------|--------------------|
| `select` | READ | SELECT, DESCRIBE, SHOW COLUMNS/PARTITIONS/CREATE TABLE, ANALYZE |
| `update` | UPDATE | INSERT, UPDATE, DELETE, LOAD, TRUNCATE |
| `create` | CREATE | CREATE DATABASE/TABLE/VIEW, IMPORT |
| `drop` | DELETE | DROP DATABASE/TABLE/VIEW |
| `alter` | CREATE | ALTER TABLE/VIEW/DATABASE, MSCK |
| `index` | MANAGE | Index operations |
| `lock` | MANAGE | LOCK/UNLOCK |
| `read` | READ | Read a `url` location |
| `write` | UPDATE | Write to a `url` location |
| `repladmin` | MANAGE | REPL DUMP / REPL STATUS / REPL LOAD |
| `serviceadmin` | MANAGE | KILL QUERY and other `hiveservice` operations |
| `tempudfadmin` | MANAGE | CREATE/DROP TEMPORARY FUNCTION (on the `global` resource) |
| `refresh` | MANAGE | Refresh operations |
| `all` | — | Implies every access type above except `tempudfadmin` |

### Data masking and row filtering

`dataMaskDef` allows masking policies on `database` → `table` → `column` (single values, no
wildcards) for the `select` access type, with these mask types:

| Mask type | Label | Transformer |
|-----------|-------|-------------|
| `MASK` | Redact | `mask({col})`: letters become `x`/`X`, digits `0` |
| `MASK_SHOW_LAST_4` | Partial mask: show last 4 | `mask_show_last_n({col}, 4, 'x', 'x', 'x', -1, '1')` |
| `MASK_SHOW_FIRST_4` | Partial mask: show first 4 | `mask_show_first_n({col}, 4, 'x', 'x', 'x', -1, '1')` |
| `MASK_HASH` | Hash | `mask_hash({col})` |
| `MASK_NULL` | Nullify | replaces the value with NULL |
| `MASK_NONE` | Unmasked (retain original value) | keeps the original value (use to exempt users) |
| `MASK_DATE_SHOW_YEAR` | Date: show only year | `mask({col}, 'x', 'x', 'x', -1, '1', 1, 0, -1)` |
| `CUSTOM` | Custom | any Hive expression, `{col}` is replaced by the column name |

`rowFilterDef` allows row-filter policies on `database` → `table` for `select`; the filter is a Hive
boolean expression that is appended to the query as a WHERE clause. Hive calls
`applyRowFilterAndColumnMasking()` during query compilation, so filters and masks are applied before
the query runs and cannot be bypassed by views or CTEs. See
[Row-level filtering and column masking](../features/policies/row-filter-column-masking.md).

The Hive service definition has no policy conditions or context enrichers of its own; tag-based
policies and the built-in conditions apply.

## Default policies

When a Hive service is created, Ranger Admin adds these policies:

- `all` policies on every hierarchy. The lookup user (`username` of the service configuration) is added
  to them with `select`/`read`, so resource lookup keeps working.
- In the `all` policies for databases, tables, columns and UDFs, the `{OWNER}` macro gets `all`, and the
  `public` group gets `create` on all databases. Users can therefore create their own tables and manage
  them without extra policies. `{OWNER}` matches the owner of the database or table being accessed.
- `default database tables columns`: `create` on the `default` database for `public`.
- `Information_schema database tables columns`: `select` on `information_schema` for `public`.

Edit or disable these policies to tighten the environment.

## Behavior notes

### Hive statements to Ranger permissions

The table summarizes which access type the plugin requires for common statements.

| Statement | Required access |
|-----------|-----------------|
| `SELECT`, `DESCRIBE`, `SHOW COLUMNS`, `SHOW PARTITIONS`, `SHOW CREATE TABLE`, `ANALYZE TABLE` | `select` on the tables and columns read |
| `CREATE DATABASE`, `CREATE TABLE`, `CREATE VIEW`, `IMPORT` | `create` |
| `CREATE TABLE AS SELECT` | `create` on the target, `select` on the sources |
| `DROP DATABASE`, `DROP TABLE`, `DROP VIEW` | `drop` |
| `ALTER TABLE`, `ALTER VIEW`, `ALTER DATABASE`, `MSCK REPAIR` | `alter` |
| `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `LOAD` | `update` |
| `SHOW DATABASES`, `SHOW TABLES` | any access type; other objects are filtered out of the result |
| `CREATE FUNCTION` (permanent) | `create` on `database`/`udf` |
| `CREATE TEMPORARY FUNCTION`, `ADD FILE|JAR|ARCHIVE`, `COMPILE` | `tempudfadmin` on `global` |
| `REPL DUMP`, `REPL STATUS`, `REPL LOAD` | `repladmin` |
| `KILL QUERY` | `serviceadmin` on `hiveservice` |
| `GRANT`, `REVOKE` | Ranger admin, or *delegated admin* on the resource |

- Statements that carry a location also check the URI: `CREATE EXTERNAL TABLE ... LOCATION`,
  `ALTER TABLE ... ADD PARTITION`/`SET LOCATION` and `LOAD DATA INPATH` (see the next section).
- `CREATE ROLE`, `DROP ROLE`, `GRANT ROLE`, `REVOKE ROLE` and `SHOW ROLES` are handled by the plugin and
  mapped to [Ranger roles](../features/roles.md). `SHOW GRANT` is supported for Hive resources, not for
  principals.
- The plugin requires no Ranger permission for these statements: `SET`, `RESET`, `EXPLAIN`,
  `SHOW CONF`, `SHOW FUNCTIONS`, `DESCRIBE FUNCTION`, `SHOW LOCKS`, `SHOW TRANSACTIONS`,
  `DELETE FILE|JAR|ARCHIVE`, `CREATE/DROP MACRO`.
- `DFS` commands are always denied by the plugin; the denial is audited with access type and action
  `DFS`.

### URL resources and file-system permissions

Statements that reference locations (`CREATE EXTERNAL TABLE ... LOCATION`, `LOAD DATA INPATH`,
`ALTER TABLE ... SET LOCATION`, `INSERT OVERWRITE DIRECTORY`, `EXPORT`/`IMPORT`) produce URI objects.
The plugin handles them in two ways:

- If the URI scheme is listed in `ranger.plugin.hive.urlauth.filesystem.schemes` (default `hdfs:`
  and `file:`), the plugin checks that the HiveServer2 user, impersonating the caller, has the
  required file-system permission on the path (read for inputs, write for outputs; ownership of the
  hierarchy also passes). On HDFS this check is itself subject to the
  [HDFS plugin](hdfs.md), so HDFS policies govern these locations. With
  `xasecure.hive.uri.permission.coarse.check=true` the check does not recurse into sub-directories.
- For every other scheme (for example `s3a:`, `abfs:`, `gs:`), the plugin authorizes the URI with the
  Ranger `url` resource and the `read` or `write` access type. Use a recursive `url` policy such as
  `s3a://bucket/warehouse/*` to grant access.

### Metastore-side authorization

HiveServer2 is the enforcement point for the plugin described here. Clients that connect to the Hive
metastore directly bypass HiveServer2 and therefore this plugin. The historic design for a Ranger
metastore event listener (`RangerHiveMetastorePrivilegeHandle`, cwiki "Ranger plugin for Hive
MetaStore") is **not** part of the master branch; the plugin does not ship any metastore listener.

Hive itself provides a metastore-side authorizer that instantiates the class configured as
`hive.security.authorization.manager` inside the metastore process. Configuring it with
`RangerHiveAuthorizerFactory` runs the Ranger Hive plugin inside the metastore for DDL performed by
direct clients. This is a Hive feature; verify the property names against the documentation of your
Hive release before relying on it. Ranger policy updates triggered by DDL (rename, drop) are not
performed by the plugin in either mode.

### Other caveats

- `xasecure.hive.update.xapolicies.on.grant.revoke` controls whether `GRANT`/`REVOKE` reach Ranger;
  the caller still needs admin rights or delegated admin on the resource, and the statements modify
  the policy that exactly matches the resource (see the note in the [HBase plugin](hbase.md) for the
  same mechanism).
- Column-level policies apply to `select` only. `create`, `alter` and `drop` are evaluated at table
  level.
- Users with a row filter or column mask on a table cannot run `UPDATE`/`ALTER` on it while
  `xasecure.hive.block.update.if.rowfilter.columnmask.specified=true`.

## Auditing

One audit event is written per accessed object (a query on three tables produces three events unless
they are aggregated). Notable fields:

- `resource`: `database/table/column`, or the `url`, `hiveservice` or `global` value.
- `accessType`: the Ranger access type (`select`, `update`, ...).
- `action`: the Hive operation type (`QUERY`, `CREATETABLE`, `METADATA OPERATION`, `DFS`, ...).
- `requestData`: the SQL text of the query, truncated to the configured size; see *Show Hive query in
  audit* in the [UI guide](../services/admin/ui-guide.md).
- `policyId` and `policyVersion`: the policy that decided the request.

The default audit filter in the service configuration audits all denials, skips `METADATA OPERATION`
events, and skips `SHOW_ROLES` performed by the `hive` and `hue` users.

## Try it with Docker

`dev-support/ranger-docker/docker-compose.ranger-hive.yml` starts a `ranger-hive` container with
HiveServer2 (port 10000) and the metastore (port 9083) on top of the `ranger-hadoop` container.
HiveServer2 enforces the Ranger service `dev_hive`.

```bash
cd dev-support/ranger-docker
./download-archives.sh hadoop hive
export RANGER_DB_TYPE=postgres
export AUDIT_INDEX_STORE=opensearch
export AUDIT_DESTINATIONS=audit-store-${AUDIT_INDEX_STORE}

docker compose --profile ${AUDIT_DESTINATIONS} \
  -f docker-compose.ranger.yml \
  -f docker-compose.ranger-audit-service.yml \
  -f docker-compose.ranger-hadoop.yml \
  -f docker-compose.ranger-hive.yml up -d
```

Connect with `beeline -u jdbc:hive2://localhost:10000` from inside the container. The
[first policy tutorial](../getting-started/first-policy.md) walks through a complete example, and
[Running Ranger with Docker](../getting-started/docker.md) describes the full environment.

## Further reading

- [Row-level filtering and column masking](../features/policies/row-filter-column-masking.md)
- [Roles](../features/roles.md) and [Users, groups and roles](../services/admin/users-groups-roles.md)
- [Policy model](../arch/policy-model.md): deny/allow evaluation, `{OWNER}` and `{USER}` macros
- [Tag-based policies](../features/policies/tag-based-policies.md)
- Source: [`hive-agent`](https://github.com/apache/ranger/tree/master/hive-agent),
  [`ranger-hive-plugin-shim`](https://github.com/apache/ranger/tree/master/ranger-hive-plugin-shim)
- cwiki: [Hive commands to Ranger permission mapping](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=65871805),
  [Ranger plugin for Hive MetaStore (design)](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=61337276)
