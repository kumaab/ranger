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

# Schema Registry

Schema Registry (the Hortonworks/Cloudera registry project, `com.hortonworks.registries`) stores Avro and
other schemas that Kafka producers and consumers share. The Ranger integration lets you control who may
create, read, update or delete schema groups, schemas, branches, versions and serializer/deserializer
definitions, with every decision audited in Ranger.

The enforcement point is the Schema Registry server. It loads an *authorization agent* that wraps Ranger's
plugin runtime (`RangerBasePlugin`), which polls Ranger Admin for policies and caches them on disk.

!!! note "Where the code lives"
    The authorization agent ships with the **Schema Registry project** (module
    `schema-registry-authorizer` under `com.hortonworks.registries`). Ranger provides the `schema-registry` service definition and
    `RangerServiceSchemaRegistry` (module `plugin-schema-registry`), which gives Ranger Admin
    *Test Connection* and resource lookup.

## Requirements

- A reachable Ranger Admin with a service of type `schema-registry`.
- An audit store if auditing is enabled.
- A Schema Registry distribution that contains the Ranger authorization agent. Ranger's lookup client is
  built against Schema Registry client **0.9.1** (`schema.registry.version` in
  `plugin-schema-registry/pom.xml`) and uses the `/api/v1/schemaregistry` REST API.

## Configuration

Authorization is off by default in Schema Registry (`NOOPAuthorizationAgent`). Turn it on with the
`authorization.authorizationAgentClassName` setting in Schema Registry's `registry.yaml`. The
`DefaultAuthorizationAgent` delegates every decision to an authorizer, and when no `authorizerClassName` is
given it uses
`com.hortonworks.registries.schemaregistry.authorizer.ranger.shim.RangerSchemaRegistryAuthorizer`, which must
be present in your Schema Registry distribution.

```yaml title="registry.yaml"
authorization:
  authorizationAgentClassName: "com.hortonworks.registries.schemaregistry.authorizer.agent.DefaultAuthorizationAgent"
```

Place the Ranger files below on the Schema Registry classpath, create the policy cache directory, restart
Schema Registry and check **Audit → Plugin Status** in Ranger Admin.

### ranger-schema-registry-security.xml

This file tells the plugin which Ranger Admin to contact and which service's policies to enforce. Place it
on the Schema Registry classpath. `ranger.plugin.schema-registry.policy.rest.url` and
`ranger.plugin.schema-registry.service.name` are mandatory; every other property is shown with its default.

```xml title="ranger-schema-registry-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.schema-registry.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.schema-registry.service.name</name>
    <value>dev_schema_registry</value>
    <description>MANDATORY: Name of the Ranger service whose policies are enforced.</description>
  </property>
  <property>
    <name>ranger.plugin.schema-registry.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies. The default downloads them from Ranger Admin over
      REST.</description>
  </property>
  <property>
    <name>ranger.plugin.schema-registry.policy.rest.ssl.config.file</name>
    <value>/etc/registry/conf/ranger-schema-registry-policymgr-ssl.xml</value>
    <description>Path of the TLS client configuration file
      (ranger-schema-registry-policymgr-ssl.xml). Needed only when Ranger Admin uses HTTPS. Default:
      not set.</description>
  </property>
  <property>
    <name>ranger.plugin.schema-registry.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connect timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.schema-registry.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.schema-registry.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.schema-registry.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>Interval between policy refreshes. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.schema-registry.policy.cache.dir</name>
    <value>/etc/ranger/dev_schema_registry/policycache</value>
    <description>Directory for the on-disk policy cache. It must be writable by the process that
      hosts the plugin. Default: not set.</description>
  </property>
</configuration>
```

### ranger-schema-registry-audit.xml

This file selects where the plugin sends audit events; place it next to
`ranger-schema-registry-security.xml`. Each destination is switched on with
`xasecure.audit.destination.<name>=true` and configured with properties under the same prefix. No property
is mandatory: without an enabled destination, no audit events are stored. The example sends audits to Solr.

```xml title="ranger-schema-registry-audit.xml"
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
    <value>/var/log/registry/audit/solr/spool</value>
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

### ranger-schema-registry-policymgr-ssl.xml

This file is needed only when Ranger Admin is reached over `https`. The plugin loads it from the path set in
`ranger.plugin.schema-registry.policy.rest.ssl.config.file`; a file with this name on the classpath is
picked up automatically. No property is mandatory: without a truststore the plugin relies on the default
truststore of the JVM, and the keystore is needed only for two-way TLS. Passwords are not stored in the
file: they are read from a Hadoop credential store (JCEKS) under fixed aliases.

```xml title="ranger-schema-registry-policymgr-ssl.xml"
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
    <value>/etc/ranger/dev_schema_registry/truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_schema_registry/cred.jceks</value>
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

Create a service of type `schema-registry` (service definition id 202) whose name equals
`ranger.plugin.schema-registry.service.name`.

| Field | Required | Description |
|---|---|---|
| `schema.registry.url` | yes | Base URL(s) of Schema Registry, for example `http://schema-registry:9090` (the lookup client appends `/api/v1/schemaregistry`); comma-separated for several instances. |
| `schema-registry.authentication` | yes | `NONE` or `KERBEROS`. With `KERBEROS`, Ranger Admin uses its own login (`RegistryClient` JAAS section). Default: `KERBEROS`. |
| `commonNameForCertificate` | no | Common name of the plugin's certificate for TLS-authenticated policy download. |
| `ranger.plugin.audit.filters` | no | Default audit filters for the service. Default: `[]`. |

For HTTPS registries the lookup client uses the `javax.net.ssl.keyStore*` and `javax.net.ssl.trustStore*`
system properties of Ranger Admin's JVM when explicit values are not supplied. *Test Connection* calls the
registry's `/api/v1/schemaregistry/version` endpoint; a failure is reported as a warning and you can still
save the service, without autocomplete. Autocomplete lists schema groups, schema names and branches from
`/api/v1/schemaregistry/schemas` with a five-second lookup timeout; `schema-version`, `serde` and
`registry-service` return `*`.

## Resources and permissions

From `ranger-servicedef-schema-registry.json`. All resources support wildcards, case-insensitive matching,
excludes and lookup.

| Resource | Parent | Description |
|---|---|---|
| `registry-service` | — | The registry as a whole (operations not tied to a schema). |
| `schema-group` | — | Schema group. |
| `schema-metadata` | `schema-group` | Schema name; a policy may end at this level. |
| `schema-branch` | `schema-metadata` | Branch of a schema; a policy may end at this level. |
| `schema-version` | `schema-branch` | Version of a schema. |
| `serde` | — | Serializer/deserializer definitions. |

Access types: `create`, `read`, `update`, `delete`.

Policy conditions: `ip-range` (`RangerIpMatcher`). No masking, row filtering or context enrichers are
declared.

## Required policies

Ranger Admin creates one *all* policy per resource hierarchy for the service's default policy users.
Everybody else needs explicit policies.

## Behavior notes

- Each Schema Registry REST operation is mapped by the agent to one of the resources above and one of
  `create`/`read`/`update`/`delete`; for example reading a schema version is `read` on
  `schema-group/schema-metadata/schema-branch/schema-version`.
- `registry-service` guards operations that are not tied to a specific schema; `serde` guards
  serializer/deserializer registration and download.
- Deny items and exceptions are available (the service definition does not disable them).
- The `ip-range` condition lets a policy item apply only to clients from given IP ranges; see
  [Policy conditions](../features/policies/policy-conditions.md).

## Auditing

Audit events carry service type `schema-registry`, the resource path and access type. The default audit
filter list (`ranger.plugin.audit.filters`) is empty.

## Try it with Docker

`dev-support/ranger-docker` has no Schema Registry compose file.

## Further reading

- [Kafka](kafka.md)
- [Plugin architecture](../arch/plugin-architecture.md)
- [Schema Registry project](https://github.com/hortonworks/registry)
