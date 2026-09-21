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

# Apache Knox

Apache Knox is a REST gateway in front of cluster services. Knox decides which users may reach which
backend service through its `AclsAuthz` provider, configured per topology in an XML file on the
gateway host. The Ranger Knox plugin replaces that provider so the same decisions are made from
Ranger policies on **topology** and **service**, managed centrally and audited.

The plugin is a Knox *authorization provider* named `XASecurePDPKnox`. It contributes a servlet filter
(`RangerPDPKnoxFilter`) to every topology that references it. For each HTTP request the filter reads
the authenticated user and groups from the Knox subject, derives the topology name from the request
URL (`/gateway/<topology>/<service>/...`) and the service name from the filter's `resource.role`
parameter, and asks the Ranger policy engine whether access is allowed. Policies are cached inside the
gateway process and refreshed by polling Ranger Admin.

## Requirements

- A Ranger Admin instance that every Knox gateway can reach over HTTP or HTTPS, with a service of type
  `knox` defined in it.
- An audit destination reachable from the gateway, if auditing is enabled.
- Apache Knox. Ranger master compiles the plugin against Knox **2.0.0** (`knox.gateway.version` in the
  root `pom.xml`); the Docker environment runs Knox **2.1.0** on JDK 17.
- The plugin jars. The Ranger build produces `ranger-<version>-knox-plugin.tar.gz` (see
  [Build from source](../dev/build.md)); its `lib/` directory holds the plugin shim jars and the
  `ranger-knox-plugin-impl` directory with the implementation and its dependencies.

## Configuration

Activating the plugin on a gateway takes three things: the plugin jars in the gateway's extension
directory, the Ranger authorization provider in each topology, and the Ranger configuration files in
the Knox configuration directory. Repeat this on every gateway host, then restart the gateway.

Copy the content of the archive's `lib/` directory, including the `ranger-knox-plugin-impl`
sub-directory, to `$KNOX_HOME/ext`. Then, in every topology under `$KNOX_HOME/conf/topologies` that
Ranger should protect, set the name of the authorization provider to `XASecurePDPKnox` (it replaces
`AclsAuthz`):

```xml title="conf/topologies/sandbox.xml (excerpt)"
<provider>
  <role>authorization</role>
  <name>XASecurePDPKnox</name>
  <enabled>true</enabled>
</provider>
```

!!! note
    A topology is protected only if it references `XASecurePDPKnox` itself. Remember this when you add
    topologies later; a topology without an authorization provider is not checked by Ranger.

The plugin reads `ranger-knox-security.xml`, `ranger-knox-audit.xml` and `ranger-policymgr-ssl.xml`
from the classpath. Place them in `$KNOX_HOME/conf`, readable by the user that runs the gateway.

### ranger-knox-security.xml

This file tells the plugin which Ranger service it enforces, how to reach Ranger Admin and where to cache
policies. Place it in `$KNOX_HOME/conf`. `ranger.plugin.knox.service.name` and
`ranger.plugin.knox.policy.rest.url` are mandatory; the policy cache directory lets this gateway start and
keep enforcing policies when Ranger Admin is unreachable.

```xml title="ranger-knox-security.xml"
<configuration>
  <!-- Connection to Ranger Admin -->
  <property>
    <name>ranger.plugin.knox.service.name</name>
    <value>dev_knox</value>
    <description>MANDATORY: Name of the Ranger service whose policies this gateway
      enforces.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
    <description>MANDATORY: URL of Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.policy.rest.ssl.config.file</name>
    <value>/etc/knox/conf/ranger-policymgr-ssl.xml</value>
    <description>Path of ranger-policymgr-ssl.xml. Read when the Ranger Admin URL uses https.
      Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.policy.rest.client.connection.timeoutMs</name>
    <value>120000</value>
    <description>Connection timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.policy.rest.client.read.timeoutMs</name>
    <value>30000</value>
    <description>Read timeout for calls to Ranger Admin. Unit: milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.policy.rest.client.max.retry.attempts</name>
    <value>3</value>
    <description>Number of retries for a failed call to Ranger Admin.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.policy.rest.client.retry.interval.ms</name>
    <value>1000</value>
    <description>Wait time between retries. Unit: milliseconds.</description>
  </property>

  <!-- Policy refresh and cache -->
  <property>
    <name>ranger.plugin.knox.policy.cache.dir</name>
    <value>/etc/ranger/dev_knox/policycache</value>
    <description>Directory for the policy cache file. It must be writable by the user that runs the
      process. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.policy.pollIntervalMs</name>
    <value>30000</value>
    <description>How often the plugin asks Ranger Admin for policy changes. Unit:
      milliseconds.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.policy.source.impl</name>
    <value>org.apache.ranger.admin.client.RangerAdminJersey2RESTClient</value>
    <description>Class that retrieves policies. Knox uses the Jersey 2 based policy client to avoid
      conflicts with the gateway's own libraries; keep this value.</description>
  </property>

  <!-- Kerberos -->
  <property>
    <name>ranger.plugin.knox.ugi.initialize</name>
    <value>false</value>
    <description>Log in with Kerberos before the plugin starts.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.ugi.login.type</name>
    <value></value>
    <description>How to log in. One of: keytab, jaas. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.ugi.keytab.principal</name>
    <value></value>
    <description>Principal for the keytab login type. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.ugi.keytab.file</name>
    <value></value>
    <description>Keytab for the keytab login type. Default: not set.</description>
  </property>
  <property>
    <name>ranger.plugin.knox.ugi.jaas.appconfig</name>
    <value></value>
    <description>JAAS application name for the jaas login type. Default: not set.</description>
  </property>
</configuration>
```

Set the Kerberos properties when the gateway needs a Kerberos identity of its own to reach Ranger Admin or a
Kerberized audit store. In addition, the filter reads the JAAS section `com.sun.security.jgss.initiate`
from the gateway's JAAS configuration when one is present.

### ranger-knox-audit.xml

This file selects where the plugin sends audit events; place it next to `ranger-knox-security.xml`. Each
destination is switched on with `xasecure.audit.destination.<name>=true` and configured with properties
under the same prefix. No property is mandatory: without an enabled destination, no audit events are
stored. The example sends audits to Solr.

```xml title="ranger-knox-audit.xml"
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
    <value>/var/log/knox/audit/solr/spool</value>
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
`ranger.plugin.knox.policy.rest.ssl.config.file`; a file named `ranger-knox-policymgr-ssl.xml` on the
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
    <value>/etc/knox/conf/ranger-plugin-truststore.jks</value>
    <description>Truststore that contains the Ranger Admin certificate or its CA. When no truststore
      is configured, the default truststore of the JVM is used. Default: not set.</description>
  </property>
  <property>
    <name>xasecure.policymgr.clientssl.truststore.credential.file</name>
    <value>jceks://file/etc/ranger/dev_knox/cred.jceks</value>
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

Choose **Knox** in Service Manager and create a service. Its name must match
`ranger.plugin.knox.service.name`.

| Field | Required | Description |
|-------|----------|-------------|
| `username` | yes | User that Ranger Admin connects as for Test Connection and resource lookup. |
| `password` | yes | Password of that user. |
| `knox.url` | yes | Knox Admin API URL, for example `https://<knox-host>:8443/gateway/admin/api/v1/topologies`. |
| `commonNameForCertificate` | no | Expected CN of the plugin's client certificate when Ranger Admin runs with two-way TLS. |
| `ranger.plugin.audit.filters` | no | Default audit filters, downloaded by the plugin together with the policies. Default: see [Auditing](#auditing). |

**Test Connection** and **resource lookup** call the Knox Admin API with the configured credentials
to list topologies and the services defined in each of them. The `admin` topology must therefore be
deployed and the lookup user must be allowed to use it.

## Resources and permissions

Source:
[`ranger-servicedef-knox.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-knox.json).
Both resources are case-sensitive, accept wildcards, support *exclude* and offer resource lookup.

| Resource | Parent | Description |
|----------|--------|-------------|
| `topology` | — | Name of the Knox topology (`sandbox`, `default`, ...) |
| `service` | `topology` | Service role inside the topology (`WEBHDFS`, `HIVE`, `HBASE`, `YARNUI`, ...) |

There is one access type, `allow` (category READ): the request may be forwarded to the backend
service.

The Knox service definition declares one **policy condition**, `ip-range`, evaluated by
`org.apache.ranger.plugin.conditionevaluator.RangerIpMatcher`. It restricts a policy item to client IP
addresses or ranges and accepts several values, for example `10.1.0.0/16` or `192.168.1.*`. The plugin
passes both the direct client address and the `X-Forwarded-For` chain to the condition.

There are no data-masking, row-filter or context-enricher definitions. The plugin authorizes access
to a service as a whole; what the user can do inside the backend service (which HDFS paths, which
Hive tables) is decided by that service's own Ranger plugin.

## Default policies

When the service is created Ranger Admin generates the `all - topology, service` policy and adds the
lookup user (`username` of the service configuration) to it with `allow`. Every other user is denied
until you create policies, because the plugin has no fallback to `AclsAuthz`.

## Behavior notes

- **User identity**: the filter uses Knox's `PrimaryPrincipal`; if the request carries an
  `ImpersonatedPrincipal` (Knox `doAs`), the impersonated user is authorized instead. Groups come
  from the `GroupPrincipal`s that Knox's identity-assertion and group-lookup providers populate, so
  group-based policies require Knox to be configured to resolve groups.
- **Client IP**: `request.getRemoteAddr()` is the client IP; the `X-Forwarded-For` header, when
  present, is split and passed as the forwarded-address list. Both are available to the `ip-range`
  condition and are recorded in the audit.
- **Topology name** is token 2 of the request path (`/gateway/<topology>/...`); **service name** is
  the `resource.role` filter parameter that Knox sets per service (`WEBHDFS`, `HIVE`, ...).
- There is no fallback to `AclsAuthz`: once the provider is switched, Ranger policies alone decide.
  A request that matches no policy is denied.
- A request that is denied receives an HTTP 403 from the gateway.

## Auditing

Each request produces an audit event with:

- `resource`: `topology/service`.
- `accessType`: `allow`.
- `clientIP`: the client address, with forwarded addresses when present.
- `requestData`: the request URL.

The default audit filter in the service configuration audits all denials and skips requests made by
the `knox` user.

## Try it with Docker

Ranger does not publish a Docker image for this service, so the environment is built from source with the compose
files in `dev-support/ranger-docker`, following the
[README](https://github.com/apache/ranger/blob/master/dev-support/ranger-docker/README.md) in that directory.

`docker-compose.ranger-knox.yml` starts a `ranger-knox` container (gateway on port 8443) with the plugin
active and a `sandbox` topology that references `XASecurePDPKnox`. It depends on the `ranger` and `ranger-zk`
containers; the `ranger-hadoop` container is started as well so that the WebHDFS service of the topology has a
backend.

Prerequisites: Docker with Compose v2, and a Ranger build in `dev-support/ranger-docker/dist/` (see
[Run Ranger with Docker](../getting-started/docker.md)). Then, from `dev-support/ranger-docker`:

```bash
chmod +x download-archives.sh
./download-archives.sh hadoop knox

# valid values for RANGER_DB_TYPE: mysql/postgres/oracle
export RANGER_DB_TYPE=postgres

# valid values for AUDIT_INDEX_STORE: opensearch (default) | solr
export AUDIT_INDEX_STORE=opensearch
export AUDIT_DESTINATIONS=audit-store-${AUDIT_INDEX_STORE}

docker compose --profile ${AUDIT_DESTINATIONS} -f docker-compose.ranger.yml -f docker-compose.ranger-audit-service.yml -f docker-compose.ranger-hadoop.yml -f docker-compose.ranger-knox.yml up -d
```

The README starts Knox only as part of its *Bring up all containers* command; the line above is the subset
of that command needed for Knox.

When Ranger Admin becomes ready, its bootstrap script `scripts/admin/create-ranger-services.py` creates the
Ranger service `dev_knox`, which the plugin in the container enforces.

To verify:

- `docker logs ranger` shows `dev_knox service created` (or `dev_knox service already exists` on a restart).
- In Ranger Admin at `http://localhost:6080` (`admin` / `rangerR0cks!`), the service appears in the
  service manager and **Audit → Plugin Status** lists `dev_knox` once the plugin has downloaded its policies.
- Requests to `https://localhost:8443/gateway/sandbox/webhdfs/v1/...` are authorized by the policies of
  `dev_knox` and appear under **Audit → Access**.

[Run Ranger with Docker](../getting-started/docker.md) describes the full environment: building Ranger, the
audit services, Kerberos, test users and cleanup.

## Further reading

- [Policy conditions](../features/policies/policy-conditions.md): the `ip-range` condition
- [Policy model](../arch/policy-model.md)
- [Authentication](../services/admin/authentication.md): Knox SSO for the Ranger Admin UI
- Source: [`knox-agent`](https://github.com/apache/ranger/tree/master/knox-agent),
  [`ranger-knox-plugin-shim`](https://github.com/apache/ranger/tree/master/ranger-knox-plugin-shim)
