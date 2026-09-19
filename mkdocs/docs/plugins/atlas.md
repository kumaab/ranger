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

# Apache Atlas

Apache Atlas is a metadata catalog: it stores *types* (the schema of the catalog), *entities* (tables,
topics, processes, ...), *classifications* (tags such as `PII`), *labels*, *business metadata* and
*relationships* between entities. The Ranger Atlas plugin controls who may read, create, change or delete
each of these, who may attach classifications to entities, and who may run administrative operations such
as import, export and purge.

The plugin runs inside the Atlas server as its `AtlasAuthorizer` implementation
(`org.apache.ranger.authorization.atlas.authorizer.RangerAtlasAuthorizer`). Atlas calls it on every
REST request that needs authorization, and also uses it to *filter* search results and type definitions
instead of failing the whole request. Policies are downloaded from Ranger Admin on a schedule, cached
locally and enforced even if Ranger Admin is unavailable.

Ranger and Atlas also work together in the other direction: Ranger TagSync reads Atlas classifications to
drive [tag-based policies](../features/policies/tag-based-policies.md) in other services. That is
independent of this plugin; see [TagSync](../services/tagsync/service.md).

## Requirements

- A Ranger Admin instance that the Atlas server can reach over HTTP or HTTPS.
- An audit store if auditing is enabled.
- Apache Atlas. Ranger master builds the plugin against Atlas **2.4.0** (`atlas.version` in the root
  [`pom.xml`](https://github.com/apache/ranger/blob/master/pom.xml)).
- The plugin jars from the `ranger-<version>-atlas-plugin` archive built by Ranger. Copy the contents of its
  `lib/` directory (the shim jars and the `ranger-atlas-plugin-impl/` directory) into the `libext/` directory
  of the Atlas installation.

## Configuration

Activate the plugin by setting the authorizer implementation in `atlas-application.properties`:

```properties title="atlas-application.properties"
# MANDATORY: makes the Ranger plugin the Atlas authorizer.
atlas.authorizer.impl=org.apache.ranger.authorization.atlas.authorizer.RangerAtlasAuthorizer
```

To return to Atlas's file-based authorization, set the property back to
`org.apache.atlas.authorize.SimpleAtlasAuthorizer`.

Place the Ranger configuration files described below in the Atlas `conf/` directory, which is on the server's
classpath, and restart Atlas. The plugin is working when the policy cache file
`atlas_<service>.json` appears in the cache directory and the plugin is listed under
**Audit → Plugin Status** in Ranger Admin.

### ranger-atlas-security.xml

This file names the Ranger service whose policies are enforced, tells the plugin where Ranger Admin is, and
controls how policies are downloaded and cached. The plugin loads it from the classpath, so place it in
the Atlas `conf/` directory. `ranger.plugin.atlas.service.name` and
`ranger.plugin.atlas.policy.rest.url` are mandatory; every other property has a working default.

```xml title="ranger-atlas-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.atlas.service.name</name>
    <value>dev_atlas</value>
    <description>MANDATORY: Name of the service in Ranger Admin whose policies this plugin
      enforces.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.rest.ssl.config.file</name>
    <value>/opt/atlas/conf/ranger-policymgr-ssl.xml</value>
    <description>Path to ranger-policymgr-ssl.xml. Needed only when the Ranger Admin URL uses https.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.rest.client.username</name>
    <value></value>
    <description>User for HTTP Basic authentication to Ranger Admin when Kerberos is not used.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.rest.client.password</name>
    <value></value>
    <description>Password for that user. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connection timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.rest.client.retry.interval.ms</name>
    <value>1000</value>
    <description>Wait between retries. Unit: milliseconds.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.atlas.policy.cache.dir</name>
    <value>/etc/ranger/dev_atlas/policycache</value>
    <description>Directory for the local policy cache (atlas_&lt;service&gt;.json), writable by the
      process user. Lets the plugin start with the last known policies when Ranger Admin is
      unreachable. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>How often the plugin asks Ranger Admin for policy changes. Unit:
      milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies from Ranger Admin.</description>
  </property>

  <!-- Authorization behavior -->
  <property>
    <name>ranger.plugin.atlas.super.users</name>
    <value></value>
    <description>Comma-separated users that are allowed without policy evaluation. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.super.groups</name>
    <value></value>
    <description>Comma-separated groups whose members are allowed without policy evaluation.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.audit.exclude.users</name>
    <value></value>
    <description>Comma-separated users whose accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.audit.exclude.groups</name>
    <value></value>
    <description>Comma-separated groups whose members' accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.audit.exclude.roles</name>
    <value></value>
    <description>Comma-separated roles whose members' accesses are not audited. Default: not
      set.</description>
  </property>

  <!-- Users, groups and roles -->
  <property>
    <name>ranger.plugin.atlas.use.rangerGroups</name>
    <value>false</value>
    <description>Add the groups Ranger knows for the user (from UserSync) to each
      request.</description>
  </property>
  <property>
    <name>ranger.plugin.atlas.use.only.rangerGroups</name>
    <value>false</value>
    <description>Ignore the groups supplied by the component and use only the groups Ranger knows
      for the user.</description>
  </property>
</configuration>
```

### ranger-atlas-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-atlas-security.xml`. Each
destination is switched on with `xasecure.audit.destination.<name>=true` and configured with properties
under the same prefix. No property is mandatory: without an enabled destination, no audit events are
stored. The example sends audits to Solr.

```xml title="ranger-atlas-audit.xml"
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
    <value>/var/log/atlas/audit/solr/spool</value>
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
`ranger.plugin.atlas.policy.rest.ssl.config.file`; a file named
`ranger-atlas-policymgr-ssl.xml` on the classpath is picked up automatically. No property is mandatory:
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
    <value>/opt/atlas/conf/ranger-plugin-truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_atlas/cred.jceks</value>
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

Create a service of type **atlas** (shown as *Atlas Metadata Server*) in Ranger Admin. Its name must equal
`ranger.plugin.atlas.service.name` on the Atlas server.

| Field | Required | Description |
|---|---|---|
| `username` | Yes | Atlas user for the connection test and resource lookup. |
| `password` | Yes | Password for that user. |
| `atlas.rest.address` | Yes | Atlas REST URL. Default: `http://localhost:21000`. |
| `commonNameForCertificate` | No | Expected CN of the plugin's client certificate. |
| `ranger.plugin.audit.filters` | No | Default audit filters delivered to the plugin. The default value comes from the service definition. |

**Test Connection** logs in through `/j_spring_security_check` and reads
`/api/atlas/v2/types/typedefs/headers`. **Lookup** autocompletes type categories, type names, entity
types, classifications and entity ids (entities are searched by `qualifiedName`).

## Resources and permissions

The service definition is
[`ranger-servicedef-atlas.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-atlas.json).
It enables deny policies and exceptions (`enableDenyAndExceptionsInPolicies=true`) and disables tag-based
policies for this service type (`enableTagBasedPolicies=false`). Every resource accepts wildcards, supports
the *exclude* flag and offers lookup; none is recursive.

The definition contains four resource hierarchies. A policy follows one hierarchy from its root to a leaf.

**Types**

| Resource | Parent | Case sensitive | Leaf access types |
|---|---|---|---|
| `type-category` | (none) | No | Not a leaf |
| `type` | `type-category` | Yes | `type-read`, `type-create`, `type-update`, `type-delete` |

`type-category` is one of `classification`, `enum`, `entity`, `relationship`, `struct` or
`business_metadata`; `type` is the type name.

**Entities**

| Resource | Parent | Case sensitive | Leaf access types |
|---|---|---|---|
| `entity-type` | (none) | Yes | Not a leaf |
| `entity-classification` | `entity-type` | Yes | Not a leaf |
| `entity` | `entity-classification` | No | `entity-read`, `entity-create`, `entity-update`, `entity-delete` |
| `entity-label` | `entity` | No | `entity-add-label`, `entity-remove-label` |
| `entity-business-metadata` | `entity` | No | `entity-update-business-metadata` |
| `classification` | `entity` | Yes | `entity-add-classification`, `entity-update-classification`, `entity-remove-classification` |

- `entity-type` is matched against the entity's type and all of its super types.
- `entity-classification` is matched against the classifications on the entity, with their super types;
  use `_NOT_CLASSIFIED` for entities without any classification.
- `entity` is the entity identifier as passed by Atlas, typically the `qualifiedName`.
- A policy ends at `entity`, or continues to exactly one of `entity-label`, `entity-business-metadata` or
  `classification` (the classification being added, updated or removed).

**Relationships**

| Resource | Parent | Case sensitive | Leaf access types |
|---|---|---|---|
| `relationship-type` | (none) | Yes | Not a leaf |
| `end-one-entity-type` | `relationship-type` | Yes | Not a leaf |
| `end-one-entity-classification` | `end-one-entity-type` | Yes | Not a leaf |
| `end-one-entity` | `end-one-entity-classification` | No | Not a leaf |
| `end-two-entity-type` | `end-one-entity` | Yes | Not a leaf |
| `end-two-entity-classification` | `end-two-entity-type` | Yes | Not a leaf |
| `end-two-entity` | `end-two-entity-classification` | No | `add-relationship`, `update-relationship`, `remove-relationship` |

**Administration.** The single resource `atlas-service` (not case sensitive) carries the access types
`admin-import`, `admin-export`, `admin-purge` and `admin-audits`.

### Access types

| Access type | Label |
|---|---|
| `type-read` | Read Type |
| `type-create` | Create Type |
| `type-update` | Update Type |
| `type-delete` | Delete Type |
| `entity-read` | Read Entity |
| `entity-create` | Create Entity |
| `entity-update` | Update Entity |
| `entity-delete` | Delete Entity |
| `entity-add-classification` | Add Classification |
| `entity-update-classification` | Update Classification |
| `entity-remove-classification` | Remove Classification |
| `entity-add-label` | Add Label |
| `entity-remove-label` | Remove Label |
| `entity-update-business-metadata` | Update Business Metadata |
| `add-relationship` | Add Relationship |
| `update-relationship` | Update Relationship |
| `remove-relationship` | Remove Relationship |
| `admin-import` | Admin Import |
| `admin-export` | Admin Export |
| `admin-purge` | Admin Purge |
| `admin-audits` | Admin Audits |

`type-create`, `type-update` and `type-delete` each imply `type-read`; no other access type has implied
grants. There are no policy conditions, masking or row-filter definitions for Atlas.

### How Atlas builds the request

`RangerAtlasAuthorizer` receives typed requests from Atlas and fills in the resource:

- **Type requests** (`AtlasTypeAccessRequest`): `type-category` and `type`.
- **Entity requests** (`AtlasEntityAccessRequest`): `entity-type` is set to the entity's type *and all
  of its super types*; `entity-classification` to every classification on the entity with their super
  types (or `_NOT_CLASSIFIED`); `entity` to the entity id. For classification operations the target
  classification (with super types) goes into `classification`; label and business-metadata operations
  fill `entity-label` / `entity-business-metadata`. The entity's `owner` attribute is passed as the
  resource owner, so the `{OWNER}` macro works in policies.
- **Relationship requests**: relationship type, both end entities' types (with super types),
  classifications (with super types) and ids.
- **Admin requests**: `atlas-service=*`.
- **Search results** (`scrubSearchResults`): each entity in a result for which `entity-read` is denied
  is *scrubbed* (its attributes are removed) rather than the request failing.
- **Type definitions** (`filterTypesDef`): enum, struct, entity, classification, relationship and
  business-metadata definitions the user cannot `type-read` are dropped from the response.

Because entity types and classifications are matched against super types, a policy on
`entity-classification=PII` also covers entities tagged with a classification that extends `PII`.

## Default and required policies

When the service is created, Ranger Admin generates one "all" policy per hierarchy, and
`RangerServiceAtlas` adjusts them:

- The Atlas admin user is added to every policy item.
- `rangertagsync` and the `public` group receive `entity-read` on the entity hierarchy.
- The service's lookup user (`username`), when configured, receives `entity-read` on the entity hierarchy.
- `public` receives `type-read` on all types and, by default, access to all relationship types.
- A policy named "Allow users to manage favorite searches" grants the current user (`{USER}`)
  `entity-read`, `entity-create`, `entity-update` and `entity-delete` on the `__AtlasUserProfile` and
  `__AtlasUserSavedSearch` entity types so that saved searches keep working.

Three optional service configs change these defaults; add them under *Add New Configurations*:

| Service config | Default | Description |
|---|---|---|
| `atlas.admin.user` | `admin` | User added to every default policy item. |
| `atlas.rangertagsync.user` | `rangertagsync` | User that receives `entity-read` so that TagSync can read entities. |
| `atlas.default-policy.relationship-type.allow.public` | `true` | Whether `public` is granted access to all relationship types. |

## Behavior notes

- **No fallback.** With `atlas.authorizer.impl` pointing at Ranger, Atlas's file-based authorizer is not
  used.
- **Deny policies** are enabled for this service type; the usual order applies (deny, deny-exception,
  allow, allow-exception). See [policy model](../arch/policy-model.md).
- **Search and type listing degrade gracefully**: instead of a 403, entities you may not read are
  scrubbed and types you may not read are omitted.
- **Delegated admin** on a policy item lets those users manage policies below that resource, as in all
  plugins.

## Auditing

Audit events are produced by `RangerAtlasAuditHandler`, which collapses events with the same policy id
and access type within one Atlas request into a single record. `type-read` checks are not audited. The
default audit filter in the service definition always audits denials, never audits the `atlas` user, and
skips allowed `entity-read` by `nifi`. See [audit filters](../services/audit/audit-filters.md).

## Try it with Docker

`dev-support/ranger-docker` does not include an Atlas container. To experiment, start Ranger Admin with
Docker (see [Running Ranger with Docker](../getting-started/docker.md)) and point an Atlas server that can
reach it at `http://<docker-host>:6080` using the configuration above.
[`dev-support/README-TAGSYNC-ATLAS-KAFKA-CONFIG.md`](https://github.com/apache/ranger/blob/master/dev-support/README-TAGSYNC-ATLAS-KAFKA-CONFIG.md)
describes wiring TagSync to an external Atlas.

## Further reading

- [Plugin architecture](../arch/plugin-architecture.md), [tag-based policies](../features/policies/tag-based-policies.md), [TagSync](../services/tagsync/service.md)
- Ranger wiki: <https://cwiki.apache.org/confluence/display/RANGER/ATLAS+Plugin> (describes an older model, kept for history)
- Plugin sources: [`plugin-atlas`](https://github.com/apache/ranger/tree/master/plugin-atlas),
  [`ranger-atlas-plugin-shim`](https://github.com/apache/ranger/tree/master/ranger-atlas-plugin-shim)
