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

# Apache NiFi Registry

NiFi Registry stores versioned flows in *buckets*. Like NiFi, it authorizes every request against a resource
identifier (`/buckets`, `/buckets/<id>`, `/policies`, `/tenants`, `/proxy`, `/actuator`, `/swagger`) with an
action of `READ`, `WRITE` or `DELETE`. With the Ranger authorizer, those decisions come from Ranger policies
and are audited centrally.

The enforcement point is the NiFi Registry server. It loads an `Authorizer` implementation configured in
`conf/authorizers.xml`, which wraps Ranger's plugin runtime (`RangerBasePlugin`).

!!! note "Where the code lives"
    The authorizer (`org.apache.nifi.registry.ranger.RangerAuthorizer` in
    `nifi-registry-extensions/nifi-registry-ranger`) ships with **Apache NiFi**. The extension is present on
    the NiFi 1.x line; check what your NiFi Registry release ships. Ranger provides the `nifi-registry`
    service definition and `RangerServiceNiFiRegistry` (module `plugin-nifi-registry`) for *Test Connection*
    and resource lookup.

## Requirements

- A reachable Ranger Admin with a service of type `nifi-registry`.
- An audit store if auditing is enabled.
- A NiFi Registry release that includes the Ranger extension. Ranger does not compile against NiFi Registry;
  the lookup client only uses its REST API (`/nifi-registry-api/policies/resources`).
- NiFi Registry running with HTTPS and client authentication.

## Configuration

Declare the authorizer in `conf/authorizers.xml` and select it in `conf/nifi-registry.properties`. The
authorizer properties are defined by NiFi Registry; the two configuration paths are mandatory.

```xml title="conf/authorizers.xml"
<authorizer>
  <identifier>ranger-authorizer</identifier>
  <class>org.apache.nifi.registry.ranger.RangerAuthorizer</class>

  <!-- MANDATORY: location of ranger-nifi-registry-security.xml. -->
  <property name="Ranger Security Config Path">./conf/ranger-nifi-registry-security.xml</property>

  <!-- MANDATORY: location of ranger-nifi-registry-audit.xml. -->
  <property name="Ranger Audit Config Path">./conf/ranger-nifi-registry-audit.xml</property>

  <!-- Must match the service definition name. Default: nifi-registry. -->
  <property name="Ranger Service Type">nifi-registry</property>

  <!-- Application id reported in audit events and plugin status. Default: nifi-registry. -->
  <property name="Ranger Application Id">nifi-registry</property>

  <!-- DN of Ranger Admin's client certificate; requests from it to /policies are approved so that
       lookup works. Suffixed variants allow several identities. Default: not set. -->
  <property name="Ranger Admin Identity">CN=ranger-admin, OU=Ranger</property>

  <!-- Log in with a Kerberos keytab before contacting Ranger Admin. Default: false. -->
  <property name="Ranger Kerberos Enabled">false</property>

  <!-- Identifier of the user-group provider NiFi Registry uses for tenants. Default: not set. -->
  <property name="User Group Provider">file-user-group-provider</property>
</authorizer>
```

```properties title="conf/nifi-registry.properties"
nifi.registry.security.authorizer=ranger-authorizer
```

Put the Ranger files at the paths named in `authorizers.xml`, readable only by the NiFi Registry user, and
restart NiFi Registry.

### ranger-nifi-registry-security.xml

This file tells the plugin which Ranger Admin to contact and which service's policies to enforce. NiFi
Registry reads it from the path given as `Ranger Security Config Path` in `authorizers.xml`.
`ranger.plugin.nifi-registry.policy.rest.url` and `ranger.plugin.nifi-registry.service.name` are mandatory;
every other property is shown with its default.

```xml title="ranger-nifi-registry-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.nifi-registry.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin. Separate several URLs with commas for Ranger Admin
      high availability.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi-registry.service.name</name>
    <value>dev_nifi_registry</value>
    <description>MANDATORY: Name of the Ranger service whose policies are enforced.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi-registry.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminRESTClient</value>
    <description>Class that retrieves policies. The default downloads them from Ranger Admin over
      REST.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi-registry.policy.rest.ssl.config.file</name>
    <value>./conf/ranger-policymgr-ssl.xml</value>
    <description>Path of the TLS client configuration file (ranger-policymgr-ssl.xml). Needed only
      when Ranger Admin uses HTTPS. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi-registry.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connect timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi-registry.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi-registry.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.nifi-registry.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>Interval between policy refreshes. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.nifi-registry.policy.cache.dir</name>
    <value>/etc/ranger/dev_nifi_registry/policycache</value>
    <description>Directory for the on-disk policy cache. It must be writable by the process that
      hosts the plugin. Default: not set.</description>
  </property>
</configuration>
```

### ranger-nifi-registry-audit.xml

This file selects where the plugin sends audit events; place it at the path given as `Ranger Audit Config
Path` in `authorizers.xml`. Each destination is switched on with `xasecure.audit.destination.<name>=true`
and configured with properties under the same prefix. No property is mandatory: without an enabled
destination, no audit events are stored. The example sends audits to Solr.

```xml title="ranger-nifi-registry-audit.xml"
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
    <value>/var/log/nifi-registry/audit/solr/spool</value>
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
`ranger.plugin.nifi-registry.policy.rest.ssl.config.file`; a file named
`ranger-nifi-registry-policymgr-ssl.xml` on the classpath is picked up automatically. No property is
mandatory: without a truststore the plugin relies on the default truststore of the JVM, and the keystore is
needed only for two-way TLS. Passwords are not stored in the file: they are read from a Hadoop credential
store (JCEKS) under fixed aliases.

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
    <value>/etc/ranger/dev_nifi_registry/truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_nifi_registry/cred.jceks</value>
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

Create a service of type `nifi-registry` (service definition id 13) whose name equals
`ranger.plugin.nifi-registry.service.name`.

| Field | Required | Description |
|---|---|---|
| `nifi.registry.url` | yes | Resources endpoint used for lookup. Default: `http://localhost:18080/nifi-registry-api/policies/resources`. |
| `nifi.registry.authentication` | yes | `NONE` or `SSL`. Default: `NONE`. |
| `nifi.registry.ssl.use.default.context` | yes | With `SSL`, use the default SSL context of Ranger Admin's JVM instead of the stores below. Default: `false`. |
| `nifi.registry.ssl.keystore` | no | Keystore with the client certificate Ranger Admin presents to NiFi Registry. Its DN must be listed as `Ranger Admin Identity`. |
| `nifi.registry.ssl.keystoreType` | no | Keystore type. |
| `nifi.registry.ssl.keystorePassword` | no | Keystore password. |
| `nifi.registry.ssl.truststore` | no | Truststore that contains NiFi Registry's certificate. |
| `nifi.registry.ssl.truststoreType` | no | Truststore type. |
| `nifi.registry.ssl.truststorePassword` | no | Truststore password. |
| `ranger.plugin.audit.filters` | no | Default audit filters for the service. Default: `[]`. |

Add `policy.download.auth.users` with the NiFi Registry process user when Ranger Admin is Kerberized.
`RangerServiceNiFiRegistry` performs *Test Connection* against `nifi.registry.url` and returns the resource
identifiers for autocomplete.

## Resources and permissions

From `ranger-servicedef-nifi-registry.json`. There is one resource, `nifi-registry-resource`: a NiFi Registry
resource identifier such as `/buckets`, `/buckets/<uuid>`, `/policies`, `/tenants` or `/proxy`. It accepts
wildcards, is matched case-insensitively and supports lookup.

Access types: `READ`, `WRITE`, `DELETE`.

The definition sets `enableDenyAndExceptionsInPolicies=false`, so policies contain allow items only. It
declares no masking, row filtering, policy conditions or context enrichers.

## Required policies

Ranger Admin creates one *all* policy on `nifi-registry-resource` for the service's default policy users.
NiFi node identities that act on behalf of users need access to `/proxy`, and users need policies on
`/buckets` or on individual buckets, as described in the NiFi Registry administration guide. The identity
configured as `Ranger Admin Identity` needs no policy for lookup.

## Behavior notes

- Resource hierarchy: if Ranger has no policy for the requested identifier the authorizer answers
  *resource not found* and NiFi Registry tries the parent resource; if a policy exists but does not allow the
  action, the request is denied.
- The action name (`READ`, `WRITE`, `DELETE`) is passed verbatim as the Ranger access type.
- Ranger user names must match NiFi Registry identities (certificate DN or mapped identity).

## Auditing

Audit events carry service type `nifi-registry`, the resource identifier, the action and the result. The
default audit filter list (`ranger.plugin.audit.filters`) is empty.

## Try it with Docker

`dev-support/ranger-docker` has no NiFi Registry compose file. Start Ranger with
[Docker](../getting-started/docker.md), attach a NiFi Registry container to the `rangernw` network and apply
the configuration above.

## Further reading

- [NiFi](nifi.md)
- [Plugin architecture](../arch/plugin-architecture.md)
