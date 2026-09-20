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

# Apache Sqoop

Sqoop2 (the 1.99.x server-based line of Apache Sqoop) moves data between relational databases and Hadoop.
Its server keeps three kinds of objects: *connectors* (drivers for a data source), *links* (connections
built on a connector) and *jobs* (transfers between two links). The Ranger Sqoop plugin controls which
users and groups may read or write each of these objects.

The plugin runs inside the Sqoop2 server as its authorization validator
(`org.apache.ranger.authorization.sqoop.authorizer.RangerSqoopAuthorizer`, registered through
`sqoop.properties`). Sqoop asks it to validate the privileges a request needs; the plugin answers from
policies downloaded from Ranger Admin, cached locally and enforced even when Ranger Admin is down.

!!! note
    Apache Sqoop was retired to the Apache Attic in 2021. Ranger keeps this plugin for existing Sqoop2
    deployments; it targets Sqoop **1.99.7**, the last Sqoop2 release.

## Requirements

- A Ranger Admin instance that the Sqoop2 server can reach over HTTP or HTTPS.
- An audit store if auditing is enabled.
- A Sqoop2 server. Ranger master builds the plugin against Sqoop **1.99.7** (`sqoop.version` in the root
  [`pom.xml`](https://github.com/apache/ranger/blob/master/pom.xml)). Sqoop 1.x is a client-side tool with no
  server to authorize, so the plugin does not apply to it.
- The plugin jars from the `ranger-<version>-sqoop-plugin` archive built by Ranger. Copy the contents of its
  `lib/` directory (the shim jars and the `ranger-sqoop-plugin-impl/` directory) into the `server/lib`
  directory of the Sqoop2 installation.

## Configuration

Activate the plugin by registering the Ranger class as the authorization validator in `sqoop.properties`:

```properties title="sqoop.properties"
# MANDATORY: makes the Ranger plugin the authorization validator of the Sqoop2 server.
org.apache.sqoop.security.authorization.validator=org.apache.ranger.authorization.sqoop.authorizer.RangerSqoopAuthorizer
```

Place the Ranger configuration files described below in the Sqoop2 `conf/` directory, which is on the
server's classpath, and restart the Sqoop2 server. The plugin is working when the policy cache file
`sqoop_<service>.json` appears in the cache directory.

### ranger-sqoop-security.xml

This file names the Ranger service whose policies are enforced, tells the plugin where Ranger Admin is, and
controls how policies are downloaded and cached. The plugin loads it from the classpath, so place it in
the Sqoop2 `conf/` directory. `ranger.plugin.sqoop.service.name` and
`ranger.plugin.sqoop.policy.rest.url` are mandatory; every other property has a working default.

```xml title="ranger-sqoop-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.sqoop.service.name</name>
    <value>dev_sqoop</value>
    <description>MANDATORY: Name of the service in Ranger Admin whose policies this plugin
      enforces.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.rest.ssl.config.file</name>
    <value>/opt/sqoop/conf/ranger-policymgr-ssl.xml</value>
    <description>Path to ranger-policymgr-ssl.xml. Needed only when the Ranger Admin URL uses https.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.rest.client.username</name>
    <value></value>
    <description>User for HTTP Basic authentication to Ranger Admin when Kerberos is not used.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.rest.client.password</name>
    <value></value>
    <description>Password for that user. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connection timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.rest.client.retry.interval.ms</name>
    <value>1000</value>
    <description>Wait between retries. Unit: milliseconds.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.sqoop.policy.cache.dir</name>
    <value>/etc/ranger/dev_sqoop/policycache</value>
    <description>Directory for the local policy cache (sqoop_&lt;service&gt;.json), writable by the
      process user. Lets the plugin start with the last known policies when Ranger Admin is
      unreachable. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>How often the plugin asks Ranger Admin for policy changes. Unit:
      milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies from Ranger Admin.</description>
  </property>

  <!-- Authorization behavior -->
  <property>
    <name>ranger.plugin.sqoop.super.users</name>
    <value></value>
    <description>Comma-separated users that are allowed without policy evaluation. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.super.groups</name>
    <value></value>
    <description>Comma-separated groups whose members are allowed without policy evaluation.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.audit.exclude.users</name>
    <value></value>
    <description>Comma-separated users whose accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.audit.exclude.groups</name>
    <value></value>
    <description>Comma-separated groups whose members' accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.audit.exclude.roles</name>
    <value></value>
    <description>Comma-separated roles whose members' accesses are not audited. Default: not
      set.</description>
  </property>

  <!-- Users, groups and roles -->
  <property>
    <name>ranger.plugin.sqoop.use.rangerGroups</name>
    <value>false</value>
    <description>Add the groups Ranger knows for the user (from UserSync) to each
      request.</description>
  </property>
  <property>
    <name>ranger.plugin.sqoop.use.only.rangerGroups</name>
    <value>false</value>
    <description>Ignore the groups supplied by the component and use only the groups Ranger knows
      for the user.</description>
  </property>
</configuration>
```

### ranger-sqoop-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-sqoop-security.xml`. Each
destination is switched on with `xasecure.audit.destination.<name>=true` and configured with properties
under the same prefix. No property is mandatory: without an enabled destination, no audit events are
stored. The example sends audits to Solr.

```xml title="ranger-sqoop-audit.xml"
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
    <value>/var/log/sqoop/audit/solr/spool</value>
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
`ranger.plugin.sqoop.policy.rest.ssl.config.file`; a file named
`ranger-sqoop-policymgr-ssl.xml` on the classpath is picked up automatically. No property is mandatory:
without a truststore the plugin relies on the default truststore of the JVM, and the keystore is needed only
for two-way TLS. Passwords are not stored in the file: they are read from a Hadoop credential store (JCEKS)
under fixed aliases.

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
    <value>/opt/sqoop/conf/ranger-plugin-truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_sqoop/cred.jceks</value>
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

Create a service of type **sqoop** in Ranger Admin. Its name must equal `ranger.plugin.sqoop.service.name`
on the Sqoop2 server.

| Field | Required | Description |
|---|---|---|
| `username` | Yes | Sqoop user for the connection test and resource lookup. |
| `sqoop.url` | Yes | Sqoop2 server URL, for example `http://sqoop-host:12000`. Separate several URLs with `,` or `;`. |
| `commonNameForCertificate` | No | Expected CN of the plugin's client certificate. |

There is no password: the lookup client identifies itself with the `user.name` query parameter
(Hadoop pseudo authentication). **Test Connection** and **lookup** call `/sqoop/v1/connector/all`,
`/sqoop/v1/link/all` and `/sqoop/v1/job/all` on the server to autocomplete connector, link and job
names (`SqoopClient`).

## Resources and permissions

The service definition is
[`ranger-servicedef-sqoop.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-sqoop.json).
It sets `enableDenyAndExceptionsInPolicies=false`, so only allow policies can be written.

There are three independent single-level resources, and a policy targets one of them:

- `connector` (label *Connector*)
- `link` (label *Link*)
- `job` (label *Job*)

Each accepts wildcards, is matched case-insensitively and offers lookup. None supports the *exclude* flag.

There are two access types, `READ` and `WRITE`; neither implies the other. No policy conditions, masking,
row filters or context enrichers are defined.

### How Sqoop requests map to policies

Sqoop calls `checkPrivileges(principal, privileges)` with the principal of the request and the list of
`MPrivilege` objects it requires. `RangerSqoopAuthorizer` builds one Ranger request per privilege:

- the resource is `connector`, `link` or `job` depending on the `MResource` type, with the object name
  as the value;
- a `USER` principal becomes the request user, a `GROUP` principal becomes the request's group;
- the privilege action (`READ` or `WRITE`) becomes the access type.

If any request is denied the plugin throws `SqoopException` (`AUTH_0014`) and the operation fails.

## Default policies

When the service is created, Ranger Admin generates one "all" policy per resource (`all - connector`,
`all - link`, `all - job`). The Sqoop service class adds nothing to them. Grant `READ` and `WRITE` on the
connectors, links and jobs each user or group works with.

## Behavior notes

- **No fallback.** When the validator is registered, Sqoop's own authorization handler is not consulted;
  only Ranger policies grant access.
- **Allow-only.** Deny policies and exceptions are disabled for this service type, and resources do not
  support the exclude flag. Model restrictions by granting narrowly.
- **Client IP.** The plugin cannot see the caller's address; it records the Sqoop server's own address
  (`InetAddress.getLocalHost()`) as the client IP, so IP-based conditions are not meaningful here.
- **Groups.** When Sqoop passes a `GROUP` principal the request carries only that group and no user.

## Auditing

Each privilege check produces an audit event with the user or group, the resource type and name
(`connector`, `link` or `job`), the access type (`READ`/`WRITE`), the policy id and the result. The Sqoop
service definition has no default `ranger.plugin.audit.filters`, so everything is audited unless you add
filters in the service configuration.

## Further reading

- Ranger wiki: <https://cwiki.apache.org/confluence/display/RANGER/Sqoop2+Plugin>
- Plugin sources: [`plugin-sqoop`](https://github.com/apache/ranger/tree/master/plugin-sqoop),
  [`ranger-sqoop-plugin-shim`](https://github.com/apache/ranger/tree/master/ranger-sqoop-plugin-shim)
