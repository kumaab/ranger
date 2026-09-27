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

# Trino

Ranger can act as the *system access control* of a Trino cluster. Every catalog, schema, table, column,
function, session-property and query-management check that Trino performs is answered from Ranger policies,
including column masking and row filtering, and every decision can be audited. Policies are edited centrally
in Ranger Admin and pulled by the Trino coordinator, so Ranger Admin is never on the query path.

The enforcement point is the Trino coordinator. The authorizer is developed and released by the Trino
project, not in the `apache/ranger` repository. Trino loads a `SystemAccessControl` implementation named
`ranger`, which wraps Ranger's plugin runtime (`RangerBasePlugin`): it downloads the policies of one Ranger
service, caches them on disk, refreshes them by polling, and sends audit events to the configured audit store.

!!! note "Where the code lives"
    The authorizer ships with **Trino** as the `ranger` access-control plugin (it moved from this repository
    to the Trino project under [RANGER-4859](https://issues.apache.org/jira/browse/RANGER-4859)); see
    [Trino's Ranger access control documentation](https://trino.io/docs/current/security/ranger-access-control.html).
    Ranger provides the `trino` service definition and `RangerServiceTrino` (module `plugin-trino`), which
    Ranger Admin uses for *Test Connection* and resource lookup.

## Requirements

- A reachable Ranger Admin with the policies you want to enforce.
- An audit store (Solr, Elasticsearch, HDFS, log4j, ...) if auditing is enabled.
- Ranger **2.5.0 or later**: Trino's documentation states that these releases include the required `trino`
  service definition. Ranger master builds its lookup client against Trino **451** (`trino.version` in the
  root `pom.xml`).
- A Trino release that bundles the `ranger` access-control plugin. The plugin jars come with Trino; nothing
  from the Ranger build has to be copied to the coordinator.

## Configuration

Activate Ranger in `etc/access-control.properties` on the Trino coordinator. These properties are defined by
Trino; `access-control.name` and `ranger.service.name` are mandatory.

```properties title="etc/access-control.properties"
# MANDATORY: selects the Ranger system access control.
access-control.name=ranger

# MANDATORY: name of the Ranger service whose policies are enforced.
ranger.service.name=dev_trino

# Comma-separated paths of the Ranger plugin configuration files described below.
# Relative paths are resolved from the classpath.
ranger.plugin.config.resource=/etc/trino/ranger-trino-security.xml,/etc/trino/ranger-trino-audit.xml,/etc/trino/ranger-policymgr-ssl.xml

# Comma-separated paths of Hadoop configuration files; needed only for Kerberos.
# Relative paths are resolved from the classpath. Default: not set.
#ranger.hadoop.config.resource=/etc/trino/core-site.xml
```

To combine Ranger with file-based or other access control systems, see *Multiple access control systems* in
the Trino documentation. Restart Trino after changing `access-control.properties` or any of the Ranger files.

### ranger-trino-security.xml

This file tells the plugin which Ranger Admin to contact and which service's policies to enforce. It can be
placed anywhere the coordinator can read, as long as its path is listed in `ranger.plugin.config.resource`.
`ranger.plugin.trino.policy.rest.url` is mandatory; every other property is shown with its default. The
service name is taken from `ranger.service.name` in `access-control.properties`.

```xml title="ranger-trino-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.trino.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.service.name</name>
    <value>dev_trino</value>
    <description>Name of the Ranger service. Trino passes ranger.service.name from
      access-control.properties to the plugin, which takes precedence over this property.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies. The default downloads them from Ranger Admin over
      REST.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.rest.ssl.config.file</name>
    <value>/etc/trino/ranger-policymgr-ssl.xml</value>
    <description>Path of the TLS client configuration file (ranger-policymgr-ssl.xml). Needed only
      when Ranger Admin uses HTTPS. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connect timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.rest.client.username</name>
    <value></value>
    <description>User for basic authentication to Ranger Admin when Kerberos is not used. Default:
      not set.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.rest.client.password</name>
    <value></value>
    <description>Password of that user. Default: not set.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.trino.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>Interval between policy refreshes. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.cache.dir</name>
    <value>/var/lib/trino/ranger/policycache</value>
    <description>Directory for the on-disk policy cache. It must be writable by the process that
      hosts the plugin. Default: not set.</description>
  </property>

  <!-- Users, groups and roles -->
  <property>
    <name>ranger.plugin.trino.use.rangerGroups</name>
    <value>false</value>
    <description>Add the groups Ranger knows for the user (from UserSync) to the groups supplied
      with the request.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.use.only.rangerGroups</name>
    <value>false</value>
    <description>Ignore the groups supplied with the request and use only the groups Ranger knows
      for the user.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.super.users</name>
    <value></value>
    <description>Comma-separated list of users that are authorized for every access without a
      policy. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.super.groups</name>
    <value></value>
    <description>Comma-separated list of groups whose members are authorized for every access
      without a policy. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.audit.exclude.users</name>
    <value></value>
    <description>Comma-separated list of users whose accesses are not audited.
      ranger.plugin.trino.audit.exclude.groups and ranger.plugin.trino.audit.exclude.roles work the
      same way for groups and roles. Default: not set.</description>
  </property>

  <!-- Kerberos -->
  <property>
    <name>ranger.plugin.trino.ugi.initialize</name>
    <value>false</value>
    <description>Log in to Kerberos (Hadoop UserGroupInformation) when the plugin starts. The login
      is used to authenticate to Ranger Admin and to the audit store.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.ugi.login.type</name>
    <value></value>
    <description>How to log in when ugi.initialize is true. One of: keytab, jaas. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.ugi.keytab.principal</name>
    <value></value>
    <description>Kerberos principal for the keytab login type. Example: trino@EXAMPLE.COM. Default:
      not set.</description>
  </property>
  <property>
    <name>ranger.plugin.trino.ugi.keytab.file</name>
    <value></value>
    <description>Keytab file for the keytab login type. Example: /etc/trino/trino.keytab. Default:
      not set.</description>
  </property>

  <!-- Audit context -->
  <property>
    <name>ranger.plugin.trino.access.cluster.name</name>
    <value></value>
    <description>Name that identifies the cluster running this Trino instance. It is recorded in the
      audit events generated by the plugin. Default: not set.</description>
  </property>
</configuration>
```

When `ranger.plugin.trino.use.only.rangerGroups` is `true`, the groups supplied by Trino are ignored
whatever the value of `ranger.plugin.trino.use.rangerGroups`. A Kerberos login (`ranger.plugin.trino.ugi.*`)
also needs the Hadoop configuration files listed in `ranger.hadoop.config.resource`.

### ranger-trino-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-trino-security.xml` and list
its path in `ranger.plugin.config.resource`. Each destination is switched on with
`xasecure.audit.destination.<name>=true` and configured with properties under the same prefix. No property
is mandatory: without an enabled destination, no audit events are stored. The example sends audits to Solr.

```xml title="ranger-trino-audit.xml"
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
    <value>/var/lib/trino/ranger/audit/solr/spool</value>
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
`ranger.plugin.trino.policy.rest.ssl.config.file`; you can also list it in `ranger.plugin.config.resource`.
No property is mandatory: without a truststore the plugin relies on the default truststore of the JVM, and
the keystore is needed only for two-way TLS. Passwords are not stored in the file: they are read from a
Hadoop credential store (JCEKS) under fixed aliases.

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
    <value>/etc/ranger/dev_trino/truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_trino/cred.jceks</value>
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

Create a service of type `trino` (service definition id 203) whose name equals `ranger.service.name`.

| Field | Required | Description |
|---|---|---|
| `username` | yes | User for *Test Connection* and resource lookup. |
| `jdbc.url` | yes | For example `jdbc:trino://trino-coordinator:8080`. |
| `jdbc.driverClassName` | yes | JDBC driver. Default: `io.trino.jdbc.TrinoDriver`. |
| `password` | no | Password of the lookup user; leave empty for a coordinator without authentication. |
| `ranger.plugin.audit.filters` | no | Default audit filters for the service. Default: see [Auditing](#auditing). |

`RangerServiceTrino` (loaded by Ranger Admin from `ranger-plugins/trino`) runs *Test Connection* and
autocompletes catalogs, schemas, tables and columns through the Trino JDBC driver.

## Resources and permissions

From `ranger-servicedef-trino.json`. All resources accept wildcards and are matched case-insensitively.

| Resource | Parent | Lookup | Access types allowed |
|---|---|---|---|
| `catalog` | — | yes | any |
| `schema` | `catalog` | yes | any |
| `table` | `schema` | yes | any |
| `column` | `table` | yes | any |
| `sessionproperty` | `catalog` | no | `alter` |
| `procedure` | `schema` | no | `execute`, `grant` |
| `schemafunction` | `schema` | no | `create`, `drop`, `execute`, `show` |
| `trinouser` | — | no | `impersonate` |
| `systemproperty` | — | no | `alter` |
| `function` | — | no | `execute`, `grant` |
| `queryid` | — | no | `execute` |
| `sysinfo` | — | no | `read_sysinfo`, `write_sysinfo` |
| `role` | — | no | `create`, `drop`, `show`, `grant`, `revoke` |

Access types: `select`, `insert`, `create`, `drop`, `delete`, `use`, `alter`, `grant`, `revoke`, `show`,
`impersonate`, `execute`, `read_sysinfo`, `write_sysinfo` and `all` (implies all of the above).

- **Data masking** on `catalog/schema/table/column` for `select`: `MASK`, `MASK_SHOW_LAST_4`,
  `MASK_SHOW_FIRST_4`, `MASK_HASH`, `MASK_NULL`, `MASK_NONE`, `MASK_DATE_SHOW_YEAR`, `CUSTOM`. Transformers
  are Trino SQL expressions (for example `MASK_HASH` is `cast(to_hex(sha256(to_utf8({col}))) as {type})`).
- **Row filtering** on `catalog/schema/table` for `select`; the filter is a Trino SQL predicate.
- No policy conditions or context enrichers are declared.

See [Row filter and column masking](../features/policies/row-filter-column-masking.md).

## Required policies

Trino asks Ranger before it runs any query and before it lets a user act under their own name, so a few
policies are needed before users can do anything at all. Trino's documentation lists them:

| Purpose | Resource | Users | Permission |
|---|---|---|---|
| Run queries | Query ID `*` | `{USER}` | `execute` |
| Act as oneself | Trino User `{USER}` | `{USER}` | `impersonate` |
| Kill one's own queries | Catalog `system`, schema `runtime`, procedure `kill_query` | `{USER}` | `execute` |
| Graceful worker shutdown (optional) | System Information `*` | administrators | `write_sysinfo` |
| Read the metrics endpoint (optional) | System Information `*` | monitoring users | `read_sysinfo` |

`{USER}` is the Ranger macro for "the requesting user". In addition, grant `use`, `select`, `show` and the
other data permissions on catalogs, schemas, tables and columns as your users need them.

When you create the service, Ranger Admin generates one *all* policy per resource hierarchy. Its users are
the service's `username`, the users in the optional `default.policy.users` service configuration and those in
Ranger Admin's `ranger.default.policy.users`. In addition, `RangerServiceTrino` adds a `select` item for the lookup user
(`username`) to each of them so that autocomplete keeps working once policies are enforced.

## Behavior notes

- Trino calls the plugin for every SQL statement, catalog listing and query-management action. There is no
  fallback to Trino's file-based access control: anything without an allowing policy is denied.
- Row filters and column masks are returned to Trino as SQL expressions and applied in the query plan, so
  query results contain only filtered and masked values.
- `execute` on `queryid`, `impersonate` on `trinouser`, `read_sysinfo`/`write_sysinfo` on `sysinfo` and the
  `role` resource cover query management, impersonation, system-information endpoints and role DDL.
- Deny items, exceptions and validity schedules work as described in [Policy model](../arch/policy-model.md).

## Auditing

Each access request produces an audit event with service type `trino`, the resource
(`catalog.schema.table.column`, or the user, query id, property or function name), the access type, the
result and the policy id. The service definition ships default audit filters: denied accesses are always
audited, while the very frequent per-query checks (`execute` on `queryid`, and `impersonate` on `trinouser`
`{USER}`) are not. Change them in the service's `ranger.plugin.audit.filters` configuration; see
[Audit filters](../services/audit/audit-filters.md).

## Try it with Docker

Ranger does not publish a Docker image for this service, so the environment is built from source with the compose
files in `dev-support/ranger-docker`, following the
[README](https://github.com/apache/ranger/blob/master/dev-support/ranger-docker/README.md) in that directory.

`docker-compose.ranger-trino.yml` builds the image `ranger-trino` from `Dockerfile.ranger-trino` (based on
`trinodb/trino:${TRINO_VERSION}`, `TRINO_VERSION` in `.env`) and starts it as container `ranger-trino` on the
`rangernw` network, with port 8080 published on the host. The image copies `scripts/trino/` —
`access-control.properties`, `ranger-trino-security.xml`, `ranger-trino-audit.xml`,
`ranger-policymgr-ssl.xml` and a catalog — into `/etc/trino/`. The authorizer itself comes with the Trino
image; no download with `download-archives.sh` is needed.

Prerequisites: Docker with Compose v2, and a Ranger build in `dev-support/ranger-docker/dist/` (see
[Run Ranger with Docker](../getting-started/docker.md)). Then, from `dev-support/ranger-docker`:

```bash
# valid values for RANGER_DB_TYPE: mysql/postgres/oracle
export RANGER_DB_TYPE=postgres

# valid values for AUDIT_INDEX_STORE: opensearch (default) | solr
export AUDIT_INDEX_STORE=opensearch
export AUDIT_DESTINATIONS=audit-store-${AUDIT_INDEX_STORE}

docker compose --profile ${AUDIT_DESTINATIONS} -f docker-compose.ranger.yml -f docker-compose.ranger-audit-service.yml -f docker-compose.ranger-trino.yml up -d
```

When Ranger Admin becomes ready, its bootstrap script `scripts/admin/create-ranger-services.py` creates the
Ranger service `dev_trino`, which the plugin in the container enforces.

To verify:

- `docker logs ranger` shows `dev_trino service created` (or `dev_trino service already exists` on a restart).
- In Ranger Admin at `http://localhost:6080` (`admin` / `rangerR0cks!`), the service appears in the
  service manager and **Audit → Plugin Status** lists `dev_trino` once the plugin has downloaded its policies.
- The Trino UI answers at `http://localhost:8080`; run a query and look for it under **Audit → Access**.

The security file points at service `dev_trino` on `http://ranger:6080`, authenticates to Ranger Admin with
`policy.rest.client.username`/`password`, and enables `use.rangerGroups` and `use.only.rangerGroups`. The
step-by-step walkthrough is in [Trino with Ranger](../getting-started/trino-with-ranger.md).

[Run Ranger with Docker](../getting-started/docker.md) describes the full environment: building Ranger, the
audit services, Kerberos, test users and cleanup.

## Further reading

- [Trino with Ranger](../getting-started/trino-with-ranger.md) — hands-on Docker walkthrough
- [Trino documentation: Ranger access control](https://trino.io/docs/current/security/ranger-access-control.html)
- [Row filter and column masking](../features/policies/row-filter-column-masking.md)
- [Presto](presto.md) — for PrestoSQL 333 deployments
