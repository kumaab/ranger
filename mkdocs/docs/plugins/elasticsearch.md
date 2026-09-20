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

# Elasticsearch

The Ranger Elasticsearch plugin controls which users may read, write, create, delete and manage
Elasticsearch *indices*. It is packaged as a regular Elasticsearch plugin
(`org.apache.ranger.authorization.elasticsearch.plugin.RangerElasticsearchPlugin`) and must be installed
on every node of the cluster. Inside the node it hooks into two places:

- a **REST handler wrapper** (`RangerSecurityRestFilter`) that reads the user name from the request and
  stores it, with the client IP, in the thread context;
- an **action filter** (`RangerSecurityActionFilter`) that intercepts every transport action, works out
  the indices the action touches, maps the action name to an index privilege and asks Ranger whether the
  user may perform it.

Policies are downloaded from Ranger Admin on a schedule, cached locally and enforced even when Ranger
Admin is unavailable.

!!! warning "The plugin does not authenticate users"
    `RangerSecurityRestFilter` takes the user name from the HTTP `Authorization: Basic ...` header and
    does **not** verify the password. Requests without the header are rejected with HTTP 401, but any
    caller who can reach the REST port can claim any user name. Put a trusted authentication layer
    (a reverse proxy or an authentication plugin that sets the same header) in front of Elasticsearch, and
    restrict network access to the cluster.

## Requirements

- A Ranger Admin instance that every Elasticsearch node can reach over HTTP or HTTPS.
- An audit store if auditing is enabled.
- An Elasticsearch release that matches the plugin build. Ranger master builds against Elasticsearch
  **7.17.29** (`elasticsearch.version` in the root
  [`pom.xml`](https://github.com/apache/ranger/blob/master/pom.xml)). Elasticsearch loads a plugin only if
  the `elasticsearch.version` in its `plugin-descriptor.properties` matches the node's version, so use a
  plugin build made for your release.
- The plugin directory from the `ranger-<version>-elasticsearch-plugin` archive built by Ranger. Its
  `lib/ranger-elasticsearch-plugin/` directory is laid out as an Elasticsearch plugin: the shim jars,
  `plugin-descriptor.properties`, `plugin-security.policy`, and a `ranger-elasticsearch-plugin-impl/`
  directory with the implementation jars that the shim loads in its own class loader.
- A trusted component in front of Elasticsearch that authenticates users (see the warning above).

## Configuration

Elasticsearch activates a plugin by its presence; there is no setting in `elasticsearch.yml`. On **every**
node (master and data):

1. Place the plugin directory at `<ES_HOME>/plugins/ranger-elasticsearch-plugin`. Elasticsearch reads its
   descriptor and loads the plugin class at startup:

    ```properties title="plugins/ranger-elasticsearch-plugin/plugin-descriptor.properties"
    name=ranger-elasticsearch-plugin
    classname=org.apache.ranger.authorization.elasticsearch.plugin.RangerElasticsearchPlugin
    ```

2. Create the directory `ranger-elasticsearch-plugin` inside the Elasticsearch configuration directory
   (for example `<ES_HOME>/config/ranger-elasticsearch-plugin`) and place the Ranger configuration files
   described below in it. At startup the plugin adds this directory to its classpath.

3. Elasticsearch runs plugins under the Java security manager. The plugin ships a `plugin-security.policy`
   that grants the permissions it needs (class-loader creation, reflection, socket connections, property and
   file access). Elasticsearch asks you to confirm these grants when a plugin is installed with
   `elasticsearch-plugin`; for a manually placed plugin, point the JVM at the policy:

    ```properties title="config/jvm.options"
    -Djava.security.policy=/path/to/elasticsearch/plugins/ranger-elasticsearch-plugin/plugin-security.policy
    ```

4. Restart the whole cluster. The plugin is working when the policy cache file
   `elasticsearch_<service>.json` appears in the cache directory.

### ranger-elasticsearch-security.xml

This file names the Ranger service whose policies are enforced, tells the plugin where Ranger Admin is, and
controls how policies are downloaded and cached. The plugin loads it from the classpath, so place it in
the `ranger-elasticsearch-plugin` configuration directory on every node.
`ranger.plugin.elasticsearch.service.name` and `ranger.plugin.elasticsearch.policy.rest.url` are mandatory; every other property has a working default.

```xml title="ranger-elasticsearch-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.elasticsearch.service.name</name>
    <value>dev_elasticsearch</value>
    <description>MANDATORY: Name of the service in Ranger Admin whose policies this plugin
      enforces.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.rest.ssl.config.file</name>
    <value>/etc/elasticsearch/ranger-elasticsearch-plugin/ranger-policymgr-ssl.xml</value>
    <description>Path to ranger-policymgr-ssl.xml. Needed only when the Ranger Admin URL uses https.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.rest.client.username</name>
    <value></value>
    <description>User for HTTP Basic authentication to Ranger Admin when Kerberos is not used.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.rest.client.password</name>
    <value></value>
    <description>Password for that user. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connection timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.rest.client.retry.interval.ms</name>
    <value>1000</value>
    <description>Wait between retries. Unit: milliseconds.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.elasticsearch.policy.cache.dir</name>
    <value>/etc/ranger/dev_elasticsearch/policycache</value>
    <description>Directory for the local policy cache (elasticsearch_&lt;service&gt;.json), writable
      by the process user. Lets the plugin start with the last known policies when Ranger Admin is
      unreachable. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>How often the plugin asks Ranger Admin for policy changes. Unit:
      milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies from Ranger Admin.</description>
  </property>

  <!-- Authorization behavior -->
  <property>
    <name>ranger.plugin.elasticsearch.super.users</name>
    <value></value>
    <description>Comma-separated users that are allowed without policy evaluation. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.super.groups</name>
    <value></value>
    <description>Comma-separated groups whose members are allowed without policy evaluation.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.audit.exclude.users</name>
    <value></value>
    <description>Comma-separated users whose accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.audit.exclude.groups</name>
    <value></value>
    <description>Comma-separated groups whose members' accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.audit.exclude.roles</name>
    <value></value>
    <description>Comma-separated roles whose members' accesses are not audited. Default: not
      set.</description>
  </property>

  <!-- Users, groups and roles -->
  <property>
    <name>ranger.plugin.elasticsearch.use.rangerGroups</name>
    <value>false</value>
    <description>Add the groups Ranger knows for the user (from UserSync) to each
      request.</description>
  </property>
  <property>
    <name>ranger.plugin.elasticsearch.use.only.rangerGroups</name>
    <value>false</value>
    <description>Ignore the groups supplied by the component and use only the groups Ranger knows
      for the user.</description>
  </property>
</configuration>
```

### ranger-elasticsearch-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-elasticsearch-security.xml`. Each
destination is switched on with `xasecure.audit.destination.<name>=true` and configured with properties
under the same prefix. No property is mandatory: without an enabled destination, no audit events are
stored. The example sends audits to Solr.

```xml title="ranger-elasticsearch-audit.xml"
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
    <value>/var/log/elasticsearch/audit/solr/spool</value>
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
`ranger.plugin.elasticsearch.policy.rest.ssl.config.file`; a file named
`ranger-elasticsearch-policymgr-ssl.xml` on the classpath is picked up automatically. No property is mandatory:
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
    <value>/etc/elasticsearch/ranger-elasticsearch-plugin/ranger-plugin-truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_elasticsearch/cred.jceks</value>
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

Create a service of type **elasticsearch** in Ranger Admin. Its name must equal
`ranger.plugin.elasticsearch.service.name` on the nodes.

| Field | Required | Description |
|---|---|---|
| `username` | Yes | User name sent for the connection test and resource lookup. |
| `elasticsearch.url` | Yes | Cluster URL, for example `http://es-host:9200`. |

There is no password field. **Test Connection** and **lookup** call `GET /_all` with a `userName` header
to list indices (`ElasticsearchClient`).

## Resources and permissions

The service definition is
[`ranger-servicedef-elasticsearch.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-elasticsearch.json).
It sets `enableDenyAndExceptionsInPolicies=false`, so only allow policies can be written.

There is one resource, `index` (label *Index*). It accepts wildcards, is matched case-insensitively and
offers lookup; it does not support the *exclude* flag.

Access types mirror Elasticsearch index privileges:

| Access type | Implied grants |
|---|---|
| `all` | Every other access type |
| `monitor` | (none) |
| `manage` | monitor |
| `view_index_metadata` | indices_search_shards |
| `read` | (none) |
| `read_cross_cluster` | indices_search_shards |
| `index` | indices_put, indices_bulk, indices_index |
| `create` | indices_put, indices_bulk, indices_index |
| `delete` | indices_bulk |
| `write` | indices_put |
| `delete_index` | (none) |
| `create_index` | (none) |

`indices_put`, `indices_search_shards`, `indices_bulk` and `indices_index` are internal, fine-grained
privileges: they cannot be selected in a policy, but the plugin may request them and they are satisfied
through the implied grants above.

No policy conditions, masking, row filters or context enrichers are defined.

### How actions map to access types

`IndexPrivilegeUtils.getPrivilegeFromAction` matches the Elasticsearch action name against the prefixes
below, from top to bottom; the first match wins. Unmatched or empty actions require `all`.

| Action prefix | Access type |
|---|---|
| `indices:admin/aliases/get` | `view_index_metadata` |
| `indices:admin/aliases/exists` | `view_index_metadata` |
| `indices:admin/get` | `view_index_metadata` |
| `indices:admin/exists` | `view_index_metadata` |
| `indices:admin/mappings/fields/get` | `view_index_metadata` |
| `indices:admin/mappings/get` | `view_index_metadata` |
| `indices:admin/types/exists` | `view_index_metadata` |
| `indices:admin/validate/query` | `view_index_metadata` |
| `indices:monitor/settings/get` | `view_index_metadata` |
| `indices:data/read/` | `read` |
| `internal:transport/proxy/indices:data/read/` | `read_cross_cluster` |
| `indices:data/write/update` | `index` |
| `indices:data/write/delete` | `delete` |
| `indices:admin/delete` | `delete_index` |
| `indices:admin/create` | `create_index` |
| `indices:admin/mapping/put` | `indices_put` |
| `indices:admin/shards/search_shards` | `indices_search_shards` |
| `indices:data/write/bulk` | `indices_bulk` |
| `indices:data/write/index` | `indices_index` |
| `indices:monitor/` | `monitor` |
| `indices:admin/` | `manage` |
| `indices:data/write/` | `write` |
| `indices:` | `all` |
| `internal:transport/proxy/indices:` | `all` |
| `cluster:` | `all` |

For each action `RequestUtils.getIndexFromRequest` extracts the index names (single-index requests, bulk
and multi-get requests, search requests, index-admin requests, ...) and the plugin checks every index;
one denial fails the whole request with HTTP 403.

## Default policies

When the service is created, Ranger Admin generates the `all - index` policy.
`RangerServiceElasticsearch` adds an item that grants `read` to the service's lookup user, when one is
configured, so that index lookup keeps working.

## Behavior notes

- **No fallback.** Elasticsearch OSS has no built-in index authorization; with the plugin installed a
  request is allowed only if a Ranger policy allows it.
- **Users and groups.** The shim passes only the user name and client IP. The authorizer resolves the user's
  groups on the node through Hadoop's `UserGroupInformation` group mapping; enable
  `ranger.plugin.elasticsearch.use.rangerGroups` to use the groups known to Ranger instead.
- **Allow-only.** Deny policies, exceptions and resource excludes are not available for this service
  type.
- **Cluster-level actions** (`cluster:*`) and any action the mapping does not recognize require `all`
  on the affected index (or on `*`).
- **Every node.** Because the action filter runs where the action executes, a node without the plugin
  would bypass authorization; install it everywhere and restart the cluster.

## Auditing

`RangerElasticsearchAuditHandler` writes one event per index checked, with user, client IP, index,
access type, policy id and result. The service definition ships no default audit filters; add
`ranger.plugin.audit.filters` to the service if needed.

## Further reading

- Ranger wiki: <https://cwiki.apache.org/confluence/display/RANGER/Elasticsearch+Plugin>
- Plugin sources: [`plugin-elasticsearch`](https://github.com/apache/ranger/tree/master/plugin-elasticsearch),
  [`ranger-elasticsearch-plugin-shim`](https://github.com/apache/ranger/tree/master/ranger-elasticsearch-plugin-shim)
