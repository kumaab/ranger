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

# Apache NiFi

Apache NiFi authorizes every UI and REST action against a *resource identifier* such as `/flow`,
`/provenance` or `/process-groups/<id>`, with an action of `READ` or `WRITE`. With the Ranger authorizer
enabled, NiFi asks Ranger for each of those decisions instead of its own file-based policy store, so NiFi
access policies are managed centrally alongside your other services and every decision is audited.

The enforcement point is NiFi itself (each node in a cluster). NiFi loads an `Authorizer` implementation
configured in `conf/authorizers.xml`; it wraps Ranger's plugin runtime (`RangerBasePlugin`), which polls
Ranger Admin for policies and caches them on disk.

!!! note "Where the code lives"
    The authorizer (`RangerNiFiAuthorizer` and `ManagedRangerAuthorizer` in NiFi's `nifi-ranger-bundle`)
    ships with **Apache NiFi**, built on Ranger's `ranger-plugins-common`. The `nifi-ranger-bundle` is present
    on the NiFi 1.x line; it is not in the NiFi 2.x source tree (`nifi-extension-bundles`), so check what your
    NiFi release ships. Ranger provides the `nifi` service definition and `RangerServiceNiFi` (module
    `plugin-nifi`), which implements *Test Connection* and resource lookup by calling NiFi's
    `/nifi-api/resources` endpoint.

## Requirements

- A reachable Ranger Admin with a service of type `nifi`.
- An audit store if auditing is enabled.
- A NiFi release that includes the Ranger authorizer. Ranger does not compile against NiFi; make sure the
  `ranger-plugins-common` version bundled by NiFi is compatible with your Ranger Admin.
- NiFi running with HTTPS and client authentication. Ranger identifies users by the identity NiFi has
  authenticated (typically the certificate DN).

## Configuration

Declare the authorizer in NiFi's `conf/authorizers.xml` and select it in `conf/nifi.properties`. The
authorizer properties are defined by NiFi; the two configuration paths are mandatory.

```xml title="conf/authorizers.xml"
<authorizer>
  <identifier>ranger-provider</identifier>
  <class>org.apache.nifi.ranger.authorization.RangerNiFiAuthorizer</class>

  <!-- MANDATORY: location of ranger-nifi-security.xml. -->
  <property name="Ranger Security Config Path">./conf/ranger-nifi-security.xml</property>

  <!-- MANDATORY: location of ranger-nifi-audit.xml. -->
  <property name="Ranger Audit Config Path">./conf/ranger-nifi-audit.xml</property>

  <!-- Must match the service definition name. Default: nifi. -->
  <property name="Ranger Service Type">nifi</property>

  <!-- Application id reported in audit events and plugin status. Default: nifi. -->
  <property name="Ranger Application Id">nifi</property>

  <!-- Certificate DN that Ranger Admin uses for resource lookup. Requests from it to /resources are
       approved without a policy. Suffixed variants ("Ranger Admin Identity 1", ...) allow several
       identities. Default: not set. -->
  <property name="Ranger Admin Identity">CN=ranger-admin, OU=Ranger</property>

  <!-- Log in with NiFi's Kerberos principal and keytab before contacting Ranger Admin.
       Default: false. -->
  <property name="Ranger Kerberos Enabled">false</property>

  <!-- ManagedRangerAuthorizer only: identifier of a configured user-group provider.
       Default: not set. -->
  <!-- <property name="User Group Provider">file-user-group-provider</property> -->
</authorizer>
```

```properties title="conf/nifi.properties"
nifi.security.user.authorizer=ranger-provider
```

Use `org.apache.nifi.ranger.authorization.ManagedRangerAuthorizer` with `User Group Provider` if NiFi should
see users and groups from its own provider while Ranger evaluates policies.

Put the Ranger files at the paths named in `authorizers.xml` (NiFi's `conf/` directory in the example),
readable only by the NiFi process user, and restart NiFi.

### ranger-nifi-security.xml

This file tells the plugin which Ranger Admin to contact and which service's policies to enforce. NiFi reads
it from the path given as `Ranger Security Config Path` in `authorizers.xml`.
`ranger.plugin.nifi.policy.rest.url` and `ranger.plugin.nifi.service.name` are mandatory; every other
property is shown with its default.

```xml title="ranger-nifi-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.nifi.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi.service.name</name>
    <value>dev_nifi</value>
    <description>MANDATORY: Name of the Ranger service whose policies are enforced.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies. The default downloads them from Ranger Admin over
      REST.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi.policy.rest.ssl.config.file</name>
    <value>./conf/ranger-policymgr-ssl.xml</value>
    <description>Path of the TLS client configuration file (ranger-policymgr-ssl.xml). Needed only
      when Ranger Admin uses HTTPS. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connect timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.nifi.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>Interval between policy refreshes. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi.policy.cache.dir</name>
    <value>/etc/ranger/dev_nifi/policycache</value>
    <description>Directory for the on-disk policy cache. It must be writable by the process that
      hosts the plugin. Default: not set.</description>
  </property>
</configuration>
```

### ranger-nifi-audit.xml

This file selects where the plugin sends audit events; place it at the path given as `Ranger Audit Config
Path` in `authorizers.xml`. Each destination is switched on with `xasecure.audit.destination.<name>=true`
and configured with properties under the same prefix. No property is mandatory: without an enabled
destination, no audit events are stored. The example sends audits to Solr.

```xml title="ranger-nifi-audit.xml"
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
    <value>/var/log/nifi/audit/solr/spool</value>
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

For a Kerberized Solr, add the `xasecure.audit.jaas.Client.*` properties and
`xasecure.audit.destination.solr.force.use.inmemory.jaas.config=true`.

Give every enabled destination a spool directory (`xasecure.audit.destination.<name>.batch.filespool.dir`)
so that events survive an outage of the audit store. The queue, spool, Kerberos and TLS options of each
destination, and the [Audit Server](../services/audit-server/service.md) client settings, are in the
[Audit framework](../services/audit/index.md) reference.

### ranger-policymgr-ssl.xml

This file is needed only when Ranger Admin is reached over `https`. The plugin loads it from the path set in
`ranger.plugin.nifi.policy.rest.ssl.config.file`; a file named `ranger-nifi-policymgr-ssl.xml` on the
classpath is picked up automatically. No property is mandatory: without a truststore the plugin relies on
the default truststore of the JVM, and the keystore is needed only for two-way TLS. Passwords are not stored
in the file: they are read from a Hadoop credential store (JCEKS) under fixed aliases.

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
    <value>/etc/ranger/dev_nifi/truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_nifi/cred.jceks</value>
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

Create a service of type `nifi` (service definition id 10) whose name equals
`ranger.plugin.nifi.service.name`.

| Field | Required | Description |
|---|---|---|
| `nifi.url` | yes | NiFi resources endpoint used for lookup; `https://<host>:<port>/nifi-api/resources` for a secured NiFi. Default: `http://localhost:8080/nifi-api/resources`. |
| `nifi.authentication` | yes | `NONE` or `SSL`. Default: `NONE`. |
| `nifi.ssl.use.default.context` | yes | With `SSL`, use the default SSL context of Ranger Admin's JVM instead of the stores below. Default: `false`. |
| `nifi.ssl.keystore` | no | Keystore with the client certificate Ranger Admin presents to NiFi. Its DN must be listed as `Ranger Admin Identity`. |
| `nifi.ssl.keystoreType` | no | Keystore type. |
| `nifi.ssl.keystorePassword` | no | Keystore password. |
| `nifi.ssl.truststore` | no | Truststore that contains NiFi's certificate. |
| `nifi.ssl.truststoreType` | no | Truststore type. |
| `nifi.ssl.truststorePassword` | no | Truststore password. |
| `ranger.plugin.audit.filters` | no | Default audit filters for the service. Default: `[]`. |

When Ranger Admin is Kerberized, add `policy.download.auth.users` with the NiFi process user (or the identity
used for policy download) so that Ranger Admin lets it download policies.

*Test Connection* calls `nifi.url`; autocomplete returns the `identifier` values from the response, filtered
by the text typed so far. Ranger Admin loads `RangerServiceNiFi` from
`ews/webapp/WEB-INF/classes/ranger-plugins/nifi`.

## Resources and permissions

From `ranger-servicedef-nifi.json`. There is one resource, `nifi-resource`: a NiFi resource identifier such
as `/flow`, `/controller`, `/provenance`, `/policies`, `/process-groups/<uuid>` or
`/data/process-groups/<uuid>`. It accepts wildcards, is matched case-insensitively and supports lookup.

Access types: `READ`, `WRITE`.

The definition sets `enableDenyAndExceptionsInPolicies=false`, so NiFi policies contain allow items only. It
declares no data masking, row filtering, policy conditions or context enrichers.

## Required policies

Ranger Admin creates one *all* policy on `nifi-resource` for the service's default policy users. Beyond
that, every NiFi identity needs explicit policies:

- each NiFi node identity, for the resources NiFi nodes must access on each other's behalf (for example
  `/proxy`), as described in the NiFi administration guide;
- users, typically `READ` on `/flow` to open the UI plus the component-level resources they work on.

The identity configured as `Ranger Admin Identity` needs no policy for `/resources`.

## Behavior notes

- NiFi resources form a hierarchy. When Ranger has **no policy** at all for a requested identifier, the
  authorizer returns *resource not found* so NiFi walks up to the parent resource (for example from
  `/process-groups/<id>` to `/process-groups/<parent-id>`). When a policy exists but does not grant the
  access, the request is denied. Create broad policies on parents and narrow ones on children accordingly.
- The action name (`READ` or `WRITE`) is used verbatim as the Ranger access type; the user's groups come
  from NiFi's identity.
- Users in Ranger must match NiFi identities exactly — typically the full certificate DN, or the mapped
  identity if `nifi.security.identity.mapping.*` is configured in NiFi.

## Auditing

NiFi asks the authorizer to audit the requests it marks as *access attempts*; each produces an audit event
with service type `nifi`, the originally requested resource identifier, the action (`READ`/`WRITE`), the
requesting identity and the result. Checks that NiFi makes only to decide what to show in the UI are not
audited. The default audit filter list is empty;
add [audit filters](../services/audit/audit-filters.md) (for example to drop `READ` on `/flow` for service
identities) in the service's `ranger.plugin.audit.filters` configuration.

## Further reading

- [NiFi Registry](nifi-registry.md)
- [Plugin architecture](../arch/plugin-architecture.md)
- cwiki: [NiFi Plugin](https://cwiki.apache.org/confluence/display/RANGER/NiFi+Plugin)
