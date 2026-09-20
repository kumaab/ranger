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

# Nested structure

The nested structure authorizer applies Ranger policies to JSON documents — for example the response of a
microservice API. Given a *schema* name, the calling user and a JSON record, it tells the application
whether the user may read (or write) the record and returns a copy of the JSON with unauthorized fields
masked according to Ranger masking policies. It can also drop whole records with a JavaScript record filter.

It is not tied to a particular server: it is a Java library (`ranger-nestedstructure-plugin`, module
`plugin-nestedstructure`) that any JVM application embeds. The enforcement point is your application's call
to `NestedStructureAuthorizer`, which wraps Ranger's plugin runtime (`RangerBasePlugin`) and evaluates
access, masking and row-filter policies per field. Policies are pulled from Ranger Admin and cached.

## Requirements

- A reachable Ranger Admin with a service of type `nestedstructure`.
- An audit store if auditing is enabled.
- A JVM application. The library depends on `ranger-plugins-common`, Gson and `json-path`; record filters run
  on a `javax.script` JavaScript engine (GraalJS preferred, Nashorn as fallback).
- The library jar from the Ranger build (`plugin-nestedstructure/target/ranger-nestedstructure-plugin-<version>.jar`,
  see Build) and its dependencies on the application classpath.

## Configuration

There is nothing to switch on in a host component: your application calls the authorizer directly.

```java
String      schema     = "json_object.cxt.cmt.product.vnull3";
String      userName   = "beckma200";
Set<String> userGroups = new HashSet<>();
String      jsonString = ...;

AccessResult result = NestedStructureAuthorizer.getInstance()
        .authorize(schema, userName, userGroups, jsonString, NestedStructureAccessType.READ);

String authorizedJson = result.hasAccess() ? result.getJson() : null;
```

An example client is in `plugin-nestedstructure/src/test/java/.../ExampleClient.java`.

The library reads the three files below from the application classpath. Templates are in
[`plugin-nestedstructure/conf`](https://github.com/apache/ranger/tree/master/plugin-nestedstructure/conf); the
template names the service `privacera_nestedstructure`, uses `/tmp` as policy cache directory and enables
the Solr audit destination.

### ranger-nestedstructure-security.xml

This file tells the plugin which Ranger Admin to contact and which service's policies to enforce. Place it
on the application classpath. `ranger.plugin.nestedstructure.policy.rest.url` and
`ranger.plugin.nestedstructure.service.name` are mandatory; every other property is shown with its default.

```xml title="ranger-nestedstructure-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.nestedstructure.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.nestedstructure.service.name</name>
    <value>dev_nestedstructure</value>
    <description>MANDATORY: Name of the Ranger service whose policies are enforced.</description>
  </property>
  <property>
    <name>ranger.plugin.nestedstructure.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies. The default downloads them from Ranger Admin over
      REST.</description>
  </property>
  <property>
    <name>ranger.plugin.nestedstructure.policy.rest.ssl.config.file</name>
    <value>ranger-nestedstructure-policymgr-ssl.xml</value>
    <description>Path of the TLS client configuration file
      (ranger-nestedstructure-policymgr-ssl.xml). Needed only when Ranger Admin uses HTTPS. Default:
      not set.</description>
  </property>
  <property>
    <name>ranger.plugin.nestedstructure.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connect timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.nestedstructure.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.nestedstructure.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.nestedstructure.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>Interval between policy refreshes. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.nestedstructure.policy.cache.dir</name>
    <value>/etc/ranger/dev_nestedstructure/policycache</value>
    <description>Directory for the on-disk policy cache. It must be writable by the process that
      hosts the plugin. Default: not set.</description>
  </property>
</configuration>
```

### ranger-nestedstructure-audit.xml

This file selects where the plugin sends audit events; place it next to
`ranger-nestedstructure-security.xml`. Each destination is switched on with
`xasecure.audit.destination.<name>=true` and configured with properties under the same prefix. No property
is mandatory: without an enabled destination, no audit events are stored. The example sends audits to Solr.

```xml title="ranger-nestedstructure-audit.xml"
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
    <value>/var/log/myapp/audit/solr/spool</value>
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

### ranger-nestedstructure-policymgr-ssl.xml

This file is needed only when Ranger Admin is reached over `https`. The plugin loads it from the path set in
`ranger.plugin.nestedstructure.policy.rest.ssl.config.file`; a file with this name on the classpath is
picked up automatically. No property is mandatory: without a truststore the plugin relies on the default
truststore of the JVM, and the keystore is needed only for two-way TLS. Passwords are not stored in the
file: they are read from a Hadoop credential store (JCEKS) under fixed aliases.

```xml title="ranger-nestedstructure-policymgr-ssl.xml"
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
    <value>/etc/ranger/dev_nestedstructure/truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_nestedstructure/cred.jceks</value>
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

### JVM system properties

`ranger.nestedstructure.recordfilter.js.allowHostAccess` (Boolean, default `false`) controls whether
record-filter scripts running on GraalJS may access Java classes. Leave it off.

## Service definition in Ranger Admin

Create a service of type `nestedstructure` whose name equals `ranger.plugin.nestedstructure.service.name`.

| Field | Required | Description |
|---|---|---|
| `commonNameForCertificate` | no | Common name of the plugin's client certificate for TLS-authenticated policy download. |
| `policy.download.auth.users` | no | Users allowed to download policies when Ranger Admin is Kerberized. |

The service definition has no `implClass`, so there is no *Test Connection* and no autocomplete; type schema
and field names by hand.

## Resources and permissions

From `ranger-servicedef-nestedstructure.json`. Both resources accept wildcards, are matched
case-insensitively, and may end a policy. Deny items and exceptions are enabled.

| Resource | Parent | Description |
|---|---|---|
| `schema` | — | Logical name of the JSON structure, for example `json_object.cxt.cmt.product.vnull3`. |
| `field` | `schema` | Dot path of a field, for example `store.bicycle.color`. |

Access types: `read`, `write`.

- **Data masking** on `schema/field` for `read`: `MASK`, `MASK_SHOW_LAST_4`, `MASK_SHOW_FIRST_4`,
  `MASK_HASH`, `MASK_NULL`, `MASK_NONE`, `MASK_DATE_SHOW_YEAR`, `CUSTOM`. Masking is done in Java by the
  library (`DataMasker`), not by a query engine.
- **Row filter** on `schema` for `read` and `write`: the filter expression is a JavaScript boolean
  expression evaluated against each record.
- No policy conditions or context enrichers are declared.

## Behavior notes

`NestedStructureAuthorizer.authorize()` evaluates a record in three steps:

1. **Schema access** — an access request on `schema` with matching scope *self or descendants*: the user
   must have `read`/`write` on the schema or on at least one field in it. Otherwise access is denied.
2. **Record filter** — row-filter policies for the schema are evaluated. If a filter applies, its JavaScript
   expression is run against the record; a `false` result denies the record (and is audited as a denial).
3. **Field access and masking** — every field in the document is checked individually. If any field is not
   allowed, the whole record is denied. For allowed fields the masking policies are evaluated and the value
   is rewritten in the returned JSON.

Field path syntax in policies:

- Maps use dot notation: `store.bicycle.color`.
- Arrays require an explicit wildcard: `store.book[*]price` or `store.book.*.price`.
- Only primitive values (strings, numbers, booleans) can be masked; containers cannot be masked as a whole.
  If a mask type does not support the data type of the value (*not supported* in the table below), or a
  date cannot be parsed, `DataMasker` throws a `MaskingException` and `authorize()` returns a result without
  access and with the error attached.

Mask semantics implemented by `DataMasker`:

| Mask type | Strings | Numbers | Booleans |
|---|---|---|---|
| `MASK` | replaced by `*` (5 to 30 characters) | `-11111` | `false` |
| `MASK_SHOW_LAST_4` / `MASK_SHOW_FIRST_4` | all but the last/first four characters replaced by `x` | not supported | not supported |
| `MASK_HASH` | SHA-256 hex of the value | not supported | not supported |
| `MASK_DATE_SHOW_YEAR` | parsable ISO/RFC-1123 dates reduced to the year | not supported | not supported |
| `MASK_NULL` | `null` | `null` | `null` |
| `MASK_NONE` | unchanged | unchanged | unchanged |
| `CUSTOM` | the literal from the policy | the literal parsed as a long integer | the literal parsed as a boolean |

## Auditing

`NestedStructureAuditHandler` buffers the events of one `authorize()` call and flushes them together:
access decisions per field, a masking event for each field that was masked, and a denial when a record
filter rejects the record. When the call produced at least one denial, only the denied events are written. Service type is `nestedstructure`; the resource is `schema/field`.

## Further reading

- [`plugin-nestedstructure/README.md`](https://github.com/apache/ranger/blob/master/plugin-nestedstructure/README.md)
- [Writing a custom plugin](custom-plugin.md) — the same embedding pattern for your own resources
