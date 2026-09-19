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

# Apache Storm

The Ranger Storm plugin authorizes operations on Apache Storm topologies: submitting, killing,
rebalancing, activating and deactivating topologies, and reading their configuration and runtime
information. Storm's own `SimpleACLAuthorizer` keeps these permissions in `storm.yaml`; with the
plugin they become Ranger policies on topology names, with audit.

The plugin is a Storm *Nimbus authorizer* (`RangerStormAuthorizer`) configured through
`nimbus.authorizer`. Nimbus calls its `permit()` method for every incoming Thrift request with the
authenticated principal, the operation name and the topology configuration. The plugin maps the
operation to a Ranger access type, evaluates the cached policies and returns allow or deny. Policies
are refreshed from Ranger Admin by polling. Storm must run with authentication (Kerberos) enabled for
the principal to be meaningful.

## Requirements

- A Ranger Admin instance that every Nimbus host can reach over HTTP or HTTPS, with a service of type
  `storm` defined in it.
- An audit destination reachable from Nimbus, if auditing is enabled.
- Apache Storm with authentication enabled. Ranger master builds the plugin against Storm **1.2.4**
  (`storm.version` in the root `pom.xml`).
- The plugin jars. The Ranger build produces `ranger-<version>-storm-plugin.tar.gz` (see
  [Build from source](../dev/build.md)); its `lib/` directory holds the plugin shim jars and the
  `ranger-storm-plugin-impl` directory with the implementation and its dependencies.

## Configuration

Activating the plugin on Nimbus takes three things: the plugin jars on the classpath of the Storm
daemons, the authorizer set in `storm.yaml`, and the Ranger configuration files in the Storm
configuration directory. Repeat this on every Nimbus host, then restart Nimbus.

Copy the content of the archive's `lib/` directory, including the `ranger-storm-plugin-impl`
sub-directory, to `$STORM_HOME/extlib-daemon`. This directory is on the classpath of the daemons, not
of the workers. Then set the authorizer in `storm.yaml`, replacing any previous value:

```yaml title="storm.yaml"
nimbus.authorizer: "org.apache.ranger.authorization.storm.authorizer.RangerStormAuthorizer"
```

The plugin reads `ranger-storm-security.xml`, `ranger-storm-audit.xml` and `ranger-policymgr-ssl.xml`
from the classpath. Place them in `$STORM_HOME/conf`, readable by the user that runs Nimbus.

### ranger-storm-security.xml

This file tells the plugin which Ranger service it enforces, how to reach Ranger Admin and where to cache
policies. Place it in `$STORM_HOME/conf`. `ranger.plugin.storm.service.name` and
`ranger.plugin.storm.policy.rest.url` are mandatory; the policy cache directory lets this Nimbus start and
keep enforcing policies when Ranger Admin is unreachable.

```xml title="ranger-storm-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.storm.service.name</name>
    <value>dev_storm</value>
    <description>MANDATORY: Name of the Ranger service whose policies this Nimbus
      enforces.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.rest.ssl.config.file</name>
    <value>/etc/storm/conf/ranger-policymgr-ssl.xml</value>
    <description>Path of ranger-policymgr-ssl.xml. Read when the Ranger Admin URL uses https.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.rest.client.username</name>
    <value></value>
    <description>User name sent with HTTP basic authentication when the plugin downloads policies.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.rest.client.password</name>
    <value></value>
    <description>Password for policy.rest.client.username. Basic authentication is used only when
      both are set. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connection timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.rest.client.retry.interval.ms</name>
    <value>1000</value>
    <description>Wait time between retries. Unit: milliseconds.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.storm.policy.cache.dir</name>
    <value>/etc/ranger/dev_storm/policycache</value>
    <description>Directory for the policy cache file. It must be writable by the user that runs the
      process. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>How often the plugin asks Ranger Admin for policy changes. Unit:
      milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.storm.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies.</description>
  </property>
</configuration>
```

### ranger-storm-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-storm-security.xml`. Each
destination is switched on with `xasecure.audit.destination.<name>=true` and configured with properties
under the same prefix. No property is mandatory: without an enabled destination, no audit events are
stored. The example sends audits to Solr.

```xml title="ranger-storm-audit.xml"
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
    <value>/var/log/storm/audit/solr/spool</value>
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
`ranger.plugin.storm.policy.rest.ssl.config.file`; a file named `ranger-storm-policymgr-ssl.xml` on the
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
    <value>/etc/storm/conf/ranger-plugin-truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_storm/cred.jceks</value>
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

Choose **Storm** in Service Manager and create a service. Its name must match
`ranger.plugin.storm.service.name`.

| Field | Required | Description |
|-------|----------|-------------|
| `username` | yes | User that Ranger Admin connects as for Test Connection and resource lookup. |
| `password` | yes | Password of that user. |
| `nimbus.url` | yes | Storm UI server URL, for example `http://<storm-ui-host>:8080`. |
| `commonNameForCertificate` | no | Expected CN of the plugin's client certificate when Ranger Admin runs with two-way TLS. |

**Test Connection** and **resource lookup** call the Storm UI REST API `/api/v1/topology/summary`
to list running topologies.

## Resources and permissions

Source:
[`ranger-servicedef-storm.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-storm.json).
It has a single resource:

`topology`
:   Topology name as passed to `storm jar ... <name>`. Matching is case-sensitive, wildcards and
    *exclude* are supported, and resource lookup is available.

The access types correspond one-to-one to Nimbus operation names:

| Access type | Category | Storm operation |
|-------------|----------|-----------------|
| `submitTopology` | UPDATE | Submit a topology. Implies `fileUpload` and `fileDownload`. |
| `fileUpload` | UPDATE | Upload the topology jar to Nimbus |
| `fileDownload` | READ | Download a topology jar from Nimbus |
| `killTopology` | MANAGE | Kill a topology |
| `rebalance` | MANAGE | Rebalance a topology |
| `activate` | MANAGE | Activate a topology |
| `deactivate` | MANAGE | Deactivate a topology |
| `getTopologyConf` | READ | Read the topology configuration |
| `getTopology` | READ | Read the topology structure |
| `getUserTopology` | READ | Read the user-visible topology structure |
| `getTopologyInfo` | READ | Read runtime information |
| `uploadNewCredentials` | MANAGE | Upload new credentials to a running topology |

Operations that have no access type of their own are mapped to `getTopologyInfo`:
`getTopologyPageInfo`, `getComponentPageInfo`, `setWorkerProfiler`, `getWorkerProfileActionExpiry`,
`getComponentPendingProfileActions`, `startProfiling`, `stopProfiling`, `dumpProfile`, `dumpJstack`,
`dumpHeap`, `setLogConfig`, `getLogConfig` and `debug`. Any other operation name is used as the access
type as-is.

The Storm service definition has no data-masking, row-filter, policy-condition or context-enricher
definitions.

## Default policies

When the service is created Ranger Admin generates the `all - topology` policy and adds the lookup
user (`username` of the service configuration) to it with `getTopology`, `getTopologyConf`,
`getUserTopology` and `getTopologyInfo`. There is no fallback to `SimpleACLAuthorizer`, so create
policies for the users who submit and manage topologies before you restart Nimbus with the plugin.

## Behavior notes

- The user name is the short name of the Kerberos principal in the request context; the plugin
  sets Hadoop's `KerberosName` rules to `DEFAULT` if none are configured. Groups are resolved with
  Hadoop's group mapping on the Nimbus host.
- `getNimbusConf` and `getClusterInfo` (the cluster summary) are always allowed without a policy check.
  Other operations that are not tied to a topology arrive with an empty topology name. Grant the
  corresponding access type on topology `*` to allow them.
- A user who submits a topology typically also needs `killTopology`, `rebalance`, `activate`,
  `deactivate` and the `get*` access types on the same topology name to manage it afterwards.
- There is no fallback to `SimpleACLAuthorizer`; a request that matches no policy is denied.
- Only Nimbus enforces the plugin. Supervisors and workers are not affected, and the Storm UI
  authorizes its own requests through Nimbus with the same policies.

## Auditing

Each Nimbus request produces an audit event with:

- `resource`: the topology name.
- `accessType`: the Ranger access type (`submitTopology`, `killTopology`, ...).
- `action`: the raw Nimbus operation name (for example `getTopologyPageInfo`).
- `clientIP`: the remote address of the Thrift client.

The Storm service definition does not define a default audit filter, so every operation is audited
unless you add one in the service configuration.

## Try it with Docker

The `dev-support/ranger-docker` environment does not include a Storm container. To try the plugin,
configure it on a Storm installation as described under [Configuration](#configuration) and point it
at a Ranger Admin started with [Docker](../getting-started/docker.md).

## Further reading

- [Policy model](../arch/policy-model.md)
- [Resource-based policies](../features/policies/resource-policies.md)
- Source: [`storm-agent`](https://github.com/apache/ranger/tree/master/storm-agent),
  [`ranger-storm-plugin-shim`](https://github.com/apache/ranger/tree/master/ranger-storm-plugin-shim)
