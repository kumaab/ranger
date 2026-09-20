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

# Apache Kylin

Apache Kylin is an OLAP engine that organizes cubes and models into *projects*. Kylin's own access control
works at the project level with four permission levels: query, operation, management and admin. The Ranger
Kylin plugin lets you manage those project permissions centrally in Ranger, for users and groups, with
Ranger auditing.

The plugin runs inside the Kylin server as an *external ACL provider*
(`org.apache.ranger.authorization.kylin.authorizer.RangerKylinAuthorizer`, registered in
`kylin.properties`). Whenever Kylin checks a project permission it calls the plugin, which answers from
policies downloaded from Ranger Admin, cached locally and enforced even if Ranger Admin is unavailable.

## Requirements

- A Ranger Admin instance that every Kylin server can reach over HTTP or HTTPS.
- An audit store if auditing is enabled.
- Apache Kylin with the `ExternalAclProvider` extension point, which exists in Kylin 2.x to 4.x. Ranger
  master builds the plugin against Kylin **4.0.4** (`kylin.version` in the root
  [`pom.xml`](https://github.com/apache/ranger/blob/master/pom.xml)).
- The plugin jars from the `ranger-<version>-kylin-plugin` archive built by Ranger. Copy the contents of its
  `lib/` directory (the shim jars and the `ranger-kylin-plugin-impl/` directory) into
  `tomcat/webapps/kylin/WEB-INF/lib` of every Kylin server.

## Configuration

Activate the plugin by registering the Ranger class as the external ACL provider in `kylin.properties`:

```properties title="kylin.properties"
# MANDATORY: makes the Ranger plugin the external ACL provider of Kylin.
kylin.server.external-acl-provider=org.apache.ranger.authorization.kylin.authorizer.RangerKylinAuthorizer
```

Place the Ranger configuration files described below in the Kylin `conf/` directory, which is on the
server's classpath, and restart Kylin. The plugin is working when the policy cache file
`kylin_<service>.json` appears in the cache directory and the plugin is listed under
**Audit → Plugin Status** in Ranger Admin.

### ranger-kylin-security.xml

This file names the Ranger service whose policies are enforced, tells the plugin where Ranger Admin is, and
controls how policies are downloaded and cached. The plugin loads it from the classpath, so place it in
the Kylin `conf/` directory. `ranger.plugin.kylin.service.name` and
`ranger.plugin.kylin.policy.rest.url` are mandatory; every other property has a working default.

```xml title="ranger-kylin-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.kylin.service.name</name>
    <value>dev_kylin</value>
    <description>MANDATORY: Name of the service in Ranger Admin whose policies this plugin
      enforces.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.rest.ssl.config.file</name>
    <value>/opt/kylin/conf/ranger-policymgr-ssl.xml</value>
    <description>Path to ranger-policymgr-ssl.xml. Needed only when the Ranger Admin URL uses https.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.rest.client.username</name>
    <value></value>
    <description>User for HTTP Basic authentication to Ranger Admin when Kerberos is not used.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.rest.client.password</name>
    <value></value>
    <description>Password for that user. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connection timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.rest.client.retry.interval.ms</name>
    <value>1000</value>
    <description>Wait between retries. Unit: milliseconds.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.kylin.policy.cache.dir</name>
    <value>/etc/ranger/dev_kylin/policycache</value>
    <description>Directory for the local policy cache (kylin_&lt;service&gt;.json), writable by the
      process user. Lets the plugin start with the last known policies when Ranger Admin is
      unreachable. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>How often the plugin asks Ranger Admin for policy changes. Unit:
      milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies from Ranger Admin.</description>
  </property>

  <!-- Authorization behavior -->
  <property>
    <name>ranger.plugin.kylin.super.users</name>
    <value></value>
    <description>Comma-separated users that are allowed without policy evaluation. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.super.groups</name>
    <value></value>
    <description>Comma-separated groups whose members are allowed without policy evaluation.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.audit.exclude.users</name>
    <value></value>
    <description>Comma-separated users whose accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.audit.exclude.groups</name>
    <value></value>
    <description>Comma-separated groups whose members' accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.audit.exclude.roles</name>
    <value></value>
    <description>Comma-separated roles whose members' accesses are not audited. Default: not
      set.</description>
  </property>

  <!-- Users, groups and roles -->
  <property>
    <name>ranger.plugin.kylin.use.rangerGroups</name>
    <value>false</value>
    <description>Add the groups Ranger knows for the user (from UserSync) to each
      request.</description>
  </property>
  <property>
    <name>ranger.plugin.kylin.use.only.rangerGroups</name>
    <value>false</value>
    <description>Ignore the groups supplied by the component and use only the groups Ranger knows
      for the user.</description>
  </property>
</configuration>
```

### ranger-kylin-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-kylin-security.xml`. Each
destination is switched on with `xasecure.audit.destination.<name>=true` and configured with properties
under the same prefix. No property is mandatory: without an enabled destination, no audit events are
stored. The example sends audits to Solr.

```xml title="ranger-kylin-audit.xml"
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
    <value>/var/log/kylin/audit/solr/spool</value>
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
`ranger.plugin.kylin.policy.rest.ssl.config.file`; a file named
`ranger-kylin-policymgr-ssl.xml` on the classpath is picked up automatically. No property is mandatory:
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
    <value>/opt/kylin/conf/ranger-plugin-truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_kylin/cred.jceks</value>
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

Create a service of type **kylin** in Ranger Admin. Its name must equal `ranger.plugin.kylin.service.name`
on the Kylin servers.

| Field | Required | Description |
|---|---|---|
| `username` | Yes | Kylin user for the connection test and resource lookup. |
| `password` | Yes | Password for that user, sent with HTTP Basic authentication. |
| `kylin.url` | Yes | Kylin URL, for example `http://kylin-host:7070`. Separate several URLs with `,` or `;`. |
| `commonNameForCertificate` | No | Expected CN of the plugin's client certificate. |

**Test Connection** and **lookup** call `GET /kylin/api/projects` to autocomplete project names
(`KylinClient`).

## Resources and permissions

The service definition is
[`ranger-servicedef-kylin.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-kylin.json).
It sets `enableDenyAndExceptionsInPolicies=false`, so only allow policies can be written.

There is one resource, `project` (label *Kylin Project*). It accepts wildcards, is matched
case-insensitively and offers lookup; it does not support the *exclude* flag.

| Access type | Kylin permission |
|---|---|
| `QUERY` | Run queries in the project |
| `OPERATION` | Build and refresh cubes |
| `MANAGEMENT` | Edit models and cubes |
| `ADMIN` | Administer the project |

No access type implies another. No policy conditions, masking, row filters or context enrichers are
defined.

### How Kylin requests map to policies

Kylin calls `checkPermission(user, groups, entityType, entityUuid, permission)`. The plugin:

1. resolves the project name from the entity UUID when the entity type is a project instance
   (`ProjectManager.getPrjByUuid`); if the project cannot be found the resource is set to `*`;
2. converts the Spring Security ACL permission with Kylin's `ExternalAclProvider.transformPermission`
   into `QUERY`, `OPERATION`, `MANAGEMENT` or `ADMIN`;
3. evaluates a Ranger request with the user, the groups Kylin supplies and that access type.

`getAcl()` (listing ACL entries) is not supported and returns `null`; manage grants in Ranger.

## Default policies

When the service is created, Ranger Admin generates the `all - project` policy. The Kylin service class adds
nothing to it. Grant each user or group the access type that matches the Kylin permission level they need
on their projects.

## Behavior notes

- **Scope.** Only project-level permissions go through the external ACL provider. Kylin's own
  authentication (and its system-level admin role) still apply.
- **Allow-only.** Deny policies, exceptions and resource excludes are not available for this service
  type.
- **Client IP.** The plugin records the Kylin server's own address (`InetAddress.getLocalHost()`) as the
  client IP, so IP-based conditions are not useful.
- **Unknown project UUIDs** are evaluated against `project=*`; a policy on all projects therefore also
  covers such requests.

## Auditing

Each permission check writes one audit event with user, groups, `project`, access type, policy id and
result. The Kylin service definition ships no default audit filters, so add
`ranger.plugin.audit.filters` to the service configuration if you need to reduce volume.

## Further reading

- Ranger wiki: <https://cwiki.apache.org/confluence/display/RANGER/Kylin+Plugin>
- Plugin sources: [`plugin-kylin`](https://github.com/apache/ranger/tree/master/plugin-kylin),
  [`ranger-kylin-plugin-shim`](https://github.com/apache/ranger/tree/master/ranger-kylin-plugin-shim)
