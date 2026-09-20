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

# Apache Solr

The Ranger Solr plugin decides who may query or update Solr collections, and who may read or change
configs, schemas and the administrative APIs (collections, cores, security, metrics, autoscaling). It runs
inside every Solr node as Solr's *authorization plugin*: you name the class
`org.apache.ranger.authorization.solr.authorizer.RangerSolrAuthorizer` in Solr's `security.json`, and
Solr calls it for every request after the authentication plugin has established the user.

The same class can optionally act as a Solr *search component* to add document-level filtering to
queries, based on the Ranger roles or user attributes of the caller.

Policies are written in Ranger Admin, downloaded by each node on a schedule, cached locally and enforced
even when Ranger Admin is down. Decisions are audited to the store you configure.

!!! note
    Ranger authorizes; it does not authenticate. Solr must run an authentication plugin (Kerberos, Basic,
    ...) so that requests carry a user principal. The plugin takes the short name of that principal
    (`MiscUtil.getShortNameFromPrincipalName`) and resolves groups with the Hadoop group mapping.

## Requirements

- A Ranger Admin instance that every Solr node can reach over HTTP or HTTPS.
- An audit store if auditing is enabled. It can be a different Solr cluster, or the same one.
- Solr with an authentication plugin configured. Ranger master compiles the plugin against Solr **8.11.3**
  (`solr.version` in the root [`pom.xml`](https://github.com/apache/ranger/blob/master/pom.xml)); the Docker
  environment runs the same plugin build in a `solr:9.4.1` image.
- The plugin jars from the `ranger-<version>-solr-plugin` archive built by Ranger. Copy the contents of its
  `lib/` directory (the shim jars and the `ranger-solr-plugin-impl/` directory) into
  `server/solr-webapp/webapp/WEB-INF/lib` on every Solr node.

## Configuration

Activate the plugin by naming the Ranger class as the authorization plugin in Solr's `security.json`. In
SolrCloud mode upload the file to ZooKeeper; for a standalone node place it in `SOLR_HOME`.

```json title="security.json"
{
  "authentication": {
    "class": "org.apache.solr.security.hadoop.KerberosPlugin",
    "kerberos.principal": "HTTP/solr-host@EXAMPLE.COM",
    "kerberos.keytab": "/etc/keytabs/HTTP.keytab",
    "kerberos.name.rules": "DEFAULT"
  },
  "authorization": {
    "class": "org.apache.ranger.authorization.solr.authorizer.RangerSolrAuthorizer"
  }
}
```

The `authentication` block is an example; keep the authentication plugin your cluster already uses.

Place the Ranger configuration files described below in `server/resources`, which is on Solr's classpath.
The plugin reads and writes its policy cache outside the Solr directories; if Solr's `allowPaths` protection
is on, add the cache directory, for example `-Dsolr.allowPaths=/etc/ranger`. The Java system property
`solr.authorization.superuser` (default `solr`) names the user that bypasses document-level checks. Restart
every node after changing these settings.

### ranger-solr-security.xml

This file names the Ranger service whose policies are enforced, tells the plugin where Ranger Admin is, and
controls how policies are downloaded and cached. The plugin loads it from the classpath, so place it in
`server/resources` on every Solr node. `ranger.plugin.solr.service.name` and
`ranger.plugin.solr.policy.rest.url` are mandatory; every other property has a working default.

```xml title="ranger-solr-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.solr.service.name</name>
    <value>dev_solr</value>
    <description>MANDATORY: Name of the service in Ranger Admin whose policies this plugin
      enforces.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.rest.ssl.config.file</name>
    <value>/opt/solr/server/resources/ranger-policymgr-ssl.xml</value>
    <description>Path to ranger-policymgr-ssl.xml. Needed only when the Ranger Admin URL uses https.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.rest.client.username</name>
    <value></value>
    <description>User for HTTP Basic authentication to Ranger Admin when Kerberos is not used.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.rest.client.password</name>
    <value></value>
    <description>Password for that user. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connection timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.rest.client.retry.interval.ms</name>
    <value>1000</value>
    <description>Wait between retries. Unit: milliseconds.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.solr.policy.cache.dir</name>
    <value>/etc/ranger/dev_solr/policycache</value>
    <description>Directory for the local policy cache (solr_&lt;service&gt;.json), writable by the
      process user. Lets the plugin start with the last known policies when Ranger Admin is
      unreachable. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>How often the plugin asks Ranger Admin for policy changes. Unit:
      milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies from Ranger Admin.</description>
  </property>

  <!-- Authorization behavior -->
  <property>
    <name>ranger.plugin.solr.super.users</name>
    <value></value>
    <description>Comma-separated users that are allowed without policy evaluation. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.super.groups</name>
    <value></value>
    <description>Comma-separated groups whose members are allowed without policy evaluation.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.audit.exclude.users</name>
    <value></value>
    <description>Comma-separated users whose accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.audit.exclude.groups</name>
    <value></value>
    <description>Comma-separated groups whose members' accesses are not audited. Default: not
      set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.audit.exclude.roles</name>
    <value></value>
    <description>Comma-separated roles whose members' accesses are not audited. Default: not
      set.</description>
  </property>

  <!-- Users, groups and roles -->
  <property>
    <name>ranger.plugin.solr.use.rangerGroups</name>
    <value>false</value>
    <description>Add the groups Ranger knows for the user (from UserSync) to each
      request.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.use.only.rangerGroups</name>
    <value>false</value>
    <description>Ignore the groups supplied by the component and use only the groups Ranger knows
      for the user.</description>
  </property>

  <!-- Kerberos -->
  <property>
    <name>ranger.plugin.solr.ugi.initialize</name>
    <value>false</value>
    <description>Log in when the plugin starts. Set to true when the plugin itself must log in to
      reach a Kerberized Ranger Admin or audit store.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.ugi.login.type</name>
    <value></value>
    <description>How the plugin logs in. One of: keytab, jaas. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.ugi.keytab.principal</name>
    <value></value>
    <description>Principal for the keytab login type. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.ugi.keytab.file</name>
    <value></value>
    <description>Keytab for the keytab login type. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.solr.ugi.jaas.appconfig</name>
    <value></value>
    <description>JAAS application name for the jaas login type. Default: not set.</description>
  </property>

  <!-- Solr-specific properties -->
  <property>
    <name>xasecure.solr.use_proxy_ip</name>
    <value>false</value>
    <description>Take the client IP from a request header instead of the connection.</description>
  </property>
  <property>
    <name>xasecure.solr.proxy_ip_header</name>
    <value>HTTP_X_FORWARDED_FOR</value>
    <description>Header to read when xasecure.solr.use_proxy_ip is true.</description>
  </property>
  <property>
    <name>xasecure.solr.app.name</name>
    <value>Client</value>
    <description>JAAS application name the plugin logs in with. When unset, the
      solr.kerberos.jaas.appname system property is used if present.</description>
  </property>
</configuration>
```

The `ugi` properties are only needed when the plugin itself must log in to reach a Kerberized Ranger Admin
or audit store; with `ugi.login.type` set to `keytab` both `ugi.keytab.principal` and `ugi.keytab.file`
are required, and with `jaas` the `ugi.jaas.appconfig` property is required. The `xasecure.solr.*`
properties are read by `RangerSolrAuthorizer` from the same file.

### ranger-solr-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-solr-security.xml`. Each
destination is switched on with `xasecure.audit.destination.<name>=true` and configured with properties
under the same prefix. No property is mandatory: without an enabled destination, no audit events are
stored. The example sends audits to Solr.

```xml title="ranger-solr-audit.xml"
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
    <value>/var/log/solr/audit/solr/spool</value>
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
`ranger.plugin.solr.policy.rest.ssl.config.file`; a file named
`ranger-solr-policymgr-ssl.xml` on the classpath is picked up automatically. No property is mandatory:
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
    <value>/opt/solr/server/resources/ranger-plugin-truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_solr/cred.jceks</value>
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

### Document-level authorization

`RangerSolrAuthorizer` also extends `SearchComponent`. When it is registered in a collection's
`solrconfig.xml` and enabled, it adds an `fq` filter to each query so that a user only sees documents whose
authorization field contains one of the user's Ranger roles (see roles), or, with
`attrs_enabled`, values derived from the user's attributes in the Ranger user store (see
ABAC). The component is configured in `solrconfig.xml` with the parameters below, read
in `RangerSolrAuthorizer.init(NamedList)`; the component name is your choice, and the values shown are the
defaults unless the comment says otherwise.

```xml title="solrconfig.xml"
<searchComponent name="rangerDocAuth"
                 class="org.apache.ranger.authorization.solr.authorizer.RangerSolrAuthorizer">
  <!-- Turn document-level filtering on. Default: false. -->
  <bool name="enabled">true</bool>
  <!-- Document field holding the roles allowed to see the document. -->
  <str name="rangerAuthField">ranger_auth</str>
  <!-- Token in the authorization field that means "visible to everyone". Default: not set. -->
  <str name="allRolesToken"></str>
  <!-- One of: DISJUNCTIVE (any role matches), CONJUNCTIVE (the document's roles must be a subset
       of the user's roles). -->
  <str name="matchMode">DISJUNCTIVE</str>

  <!-- Conjunctive mode only -->
  <!-- Query parser used in conjunctive mode (the plugin ships SubsetQueryPlugin). -->
  <str name="qParser">subset</str>
  <!-- Also match documents without an authorization field. -->
  <bool name="allow_missing_val">false</bool>
  <!-- Field holding the number of tokens in the authorization field. -->
  <str name="tokenCountField">ranger_auth_count</str>

  <!-- Attribute-based filtering -->
  <!-- Filter on user attributes instead of roles. When true, andQParser is MANDATORY. -->
  <bool name="attrs_enabled">false</bool>
  <!-- Per-field attribute mappings used when attrs_enabled is true: one <lst> per Solr field.
       Default: not set. -->
  <lst name="field_attr_mappings"/>
  <!-- Query parser for attribute filters that are combined with AND. Default: not set. -->
  <str name="andQParser"></str>
</searchComponent>
```

Each entry of `field_attr_mappings` is named after a Solr field and requires `attr_names` and `filter_type`
(one of `AND`, `OR`, `LTE`, `GTE`); it also accepts `permit_empty`, `all_users_value`, `value_filter_regex`
and `extra_opts`. The component needs
`requestDispatcher/requestParsers/@addHttpRequestToContext="true"` in `solrconfig.xml` so that it can read
the caller's IP; requests without a Ranger role (and no attribute match) are rejected with HTTP 401.

## Service definition in Ranger Admin

Create a service of type **solr** in Ranger Admin. Its name must equal `ranger.plugin.solr.service.name`
on the Solr nodes.

| Field | Required | Description |
|---|---|---|
| `username` | Yes | User for the connection test and resource lookup. |
| `password` | Yes | Password for that user. |
| `solr.url` | Yes | Solr base URL, for example `http://solr-host:8983/solr`. |
| `solr.zookeeper.quorum` | No | ZooKeeper quorum. When set, lookup uses a `CloudSolrClient` instead of an HTTP client. |
| `commonNameForCertificate` | No | Expected CN of the plugin's client certificate. |
| `ranger.plugin.audit.filters` | No | Default audit filters delivered to the plugin. The default value comes from the service definition. |

**Test Connection** lists collections. **Resource lookup** autocompletes collection, config, schema and
admin names (`ServiceSolrClient`), with a 5 second timeout per lookup.

## Resources and permissions

The service definition is
[`ranger-servicedef-solr.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-solr.json).
There are four independent single-level resources; a policy targets one of them. All of them accept
wildcards, are matched case-insensitively, support the *exclude* flag and offer lookup.

| Resource | Label | Values |
|---|---|---|
| `collection` | Solr Collection | Collection names |
| `config` | Solr Config | Config set names |
| `schema` | Schema of a collection | Schema (collection) names |
| `admin` | Solr Admin | `collections`, `cores`, `security`, `metrics`, `autoscaling` |

There are two access types, neither of which implies the other:

- `query` (label *Query*): read operations such as select, get, list, reading a config or schema, and the
  read side of the admin APIs.
- `update` (label *Update*): write operations such as the update handlers, create/modify/delete, editing a
  config or schema, and the write side of the admin APIs.

One policy condition is defined: `ip-range` (`RangerIpMatcher`, multiple values). There is no masking, row
filtering or context enricher.

### How Solr requests map to policies

Solr tells the authorizer which *permission* a request handler needs (`PermissionNameProvider`). The plugin
turns that into one or more Ranger requests (`RangerSolrAuthorizer.authorize`) and denies the request if
any of them is denied. `read` and `*-read` permissions are checked as `query`; `update`, `*-edit` and
`*-write` permissions as `update`.

| Solr permission | Ranger resource checked |
|---|---|
| `read`, `update` | `collection=<each collection in the request>` |
| `security-read`, `security-edit` | `admin=security` |
| `collection-admin-read`, `collection-admin-edit` | `admin=collections`, plus `collection=<name>` for each collection touched |
| `core-admin-read`, `core-admin-edit` | `admin=cores`, plus the affected collections |
| `config-read`, `config-edit` | `config=<name>` |
| `schema-read`, `schema-edit` | `schema=<name>` |
| `metrics-read`, `metrics-history-read` | `admin=metrics` |
| `autoscaling-read`, `autoscaling-history-read`, `autoscaling-write` | `admin=autoscaling` |
| `all` | No check |

Request handlers that do not implement `PermissionNameProvider` cannot tell Solr what they need; such
requests are allowed and a warning is logged (Solr limitation, SOLR-11623).

## Default and required policies

When the service is created, Ranger Admin generates one "all" policy per resource. `RangerServiceSolr` adds
an item that grants `query` to the service's lookup user, when one is configured, so that resource lookup
keeps working.

## Behavior notes

- **No fallback.** Once `security.json` names the Ranger authorizer, Solr's rule-based authorization is
  not consulted. A request is allowed only if every Ranger request it maps to is allowed.
- **Startup.** Until the plugin has initialized, requests are answered with HTTP 503.
- **Admin operations touch collections.** A `CREATE`/`DELETE`/`RELOAD` on the collections API needs
  `update` on `admin=collections` **and** `update` on the collection being changed.
- **Shutdown.** Solr does not always run JVM shutdown hooks, so the plugin forces an audit flush when
  Solr closes the authorizer.

## Auditing

One audit event is written per Ranger request (`RangerSolrAuditHandler`) with user, client IP,
resource type and name, access type, policy id and result. Document-level denials are audited too
(policy id `-1`). The default filter in the service definition always audits denials and does not audit the
`hive`, `hdfs`, `kafka`, `hbase`, `solr`, `rangerraz`, `knox`, `atlas`, `yarn` and `impala` service users.

## Further reading

- Plugin sources: [`plugin-solr`](https://github.com/apache/ranger/tree/master/plugin-solr),
  [`ranger-solr-plugin-shim`](https://github.com/apache/ranger/tree/master/ranger-solr-plugin-shim)
