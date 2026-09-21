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

# Deployment and configuration

This page explains how to run Ranger Admin and how to configure it. Ranger Admin is a Java web application
with an embedded Tomcat server. It needs three things to run: a relational database for policies and
users, an audit store it can query for the **Audits** screens, and a configuration directory that tells it
where those are.

All settings live in one file, `ranger-admin-site.xml`. The first half of this page covers running Admin
with Docker (released Docker Hub images, or the compose files in `dev-support/ranger-docker`) and with the
service script `ranger-admin-services.sh`. The second half is a reference for `ranger-admin-site.xml`, grouped by topic.

## Requirements

- **Java.** The master branch is compiled for Java 17 (`javac.source.version` / `javac.target.version` in
  the root `pom.xml`). `JAVA_HOME` must be set for the service script.
- **Database.** MySQL/MariaDB, PostgreSQL, Oracle, SQL Server or SQL Anywhere, reachable from the Admin
  host, with its JDBC driver jar on the Admin classpath (`ews/webapp/WEB-INF/lib`). See
  [Database](database.md).
- **Audit store.** Solr, Elasticsearch, OpenSearch or Amazon CloudWatch Logs. Admin only *reads* from it;
  plugins write to it. See [Audit stores](../audit/audit-stores.md).
- **Network.** Plugins, UserSync, TagSync and browsers must reach the Admin HTTP port (6080) or HTTPS port
  (6182).
- **Python 3** for the maintenance utilities shipped in the distribution (password change, credential
  store helper).

## Run with Docker

Ranger Admin can be run in two ways with Docker: from the released images on
[Docker Hub](https://hub.docker.com/r/apache/ranger), or from images built out of the source tree with the
compose files in
[`dev-support/ranger-docker`](https://github.com/apache/ranger/blob/master/dev-support/ranger-docker).

=== "Docker Hub images"

    Released versions of Ranger Admin are published as `apache/ranger`, together with the two images it
    needs: `apache/ranger-db` (PostgreSQL policy database) and `apache/ranger-solr` (Solr audit store with
    the `ranger_audits` configset). Tags `2.4.0` to `2.9.0` are available. In this setup Solr runs
    standalone and the `ranger_audits` core is created with `solr-precreate`, so these three images are
    all that is needed. `apache/ranger-zk` (latest tag `2.8.0`) is the ZooKeeper image used when Solr runs
    in SolrCloud mode.

    ```bash
    export RANGER_VERSION=2.9.0

    docker pull apache/ranger-solr:${RANGER_VERSION}
    docker pull apache/ranger-db:${RANGER_VERSION}
    docker pull apache/ranger:${RANGER_VERSION}

    docker network create rangernw
    ```

    Start Solr, then the database, then Ranger Admin:

    ```bash
    docker run -d --name ranger-solr --hostname ranger-solr.rangernw --network rangernw -p 8983:8983 \
      apache/ranger-solr:${RANGER_VERSION} \
      solr-precreate ranger_audits /opt/solr/server/solr/configsets/ranger_audits/

    docker run -d \
      -e POSTGRES_PASSWORD=rangerR0cks! \
      -e RANGER_DB_USER=rangeradmin \
      -e RANGER_DB_PASSWORD=rangerR0cks! \
      --name ranger-db --hostname ranger-db.rangernw --network rangernw \
      --health-cmd='su -c "pg_isready -q" postgres' --health-interval=10s --health-timeout=2s --health-retries=30 \
      apache/ranger-db:${RANGER_VERSION}

    docker run -d \
      -e POSTGRES_PASSWORD=rangerR0cks! \
      -e RANGER_DB_USER=rangeradmin \
      -e RANGER_DB_PASSWORD=rangerR0cks! \
      --name ranger-admin --hostname ranger-admin.rangernw --network rangernw -p 6080:6080 \
      apache/ranger:${RANGER_VERSION}
    ```

    Ranger Admin is then available at <http://localhost:6080/login.jsp>. Follow the start-up with
    `docker logs -f ranger-admin`. Plugins in this setup write audits directly to Solr; the
    [Audit Server](../audit-server/service.md) is not part of these images.

=== "Build from source (dev-support/ranger-docker)"

    The compose files build the images from a Ranger build of the source tree and start Ranger Admin
    together with everything it depends on. On master this is `ranger`, `ranger-db`, Kafka, OpenSearch
    (the default audit index), the audit ingestor and an audit dispatcher.

    Set `dev-support/ranger-docker` as the working directory, download the archives the images need
    (JDBC drivers are always downloaded; `kafka` is needed by the audit pipeline) and build Ranger, either
    in a container or with Maven:

    ```bash
    cd dev-support/ranger-docker
    chmod +x download-archives.sh
    ./download-archives.sh kafka

    chmod +x scripts/**/*.sh
    docker compose -f docker-compose.ranger-build.yml build
    docker compose -f docker-compose.ranger-build.yml up
    ```

    ```bash
    # or a regular build from the repository root
    mvn clean package -DskipTests
    cp target/ranger-* dev-support/ranger-docker/dist/
    cp target/version dev-support/ranger-docker/dist/
    cd dev-support/ranger-docker
    ```

    Then start Ranger Admin with the audit pipeline:

    ```bash
    # valid values for RANGER_DB_TYPE: mysql/postgres/oracle
    export RANGER_DB_TYPE=postgres

    # valid values for AUDIT_INDEX_STORE: opensearch (default) | solr
    export AUDIT_INDEX_STORE=opensearch
    export AUDIT_DESTINATIONS=audit-store-${AUDIT_INDEX_STORE}

    docker compose --profile ${AUDIT_DESTINATIONS} \
      -f docker-compose.ranger.yml \
      -f docker-compose.ranger-audit-service.yml up -d
    ```

    Ranger Admin is then available at <http://localhost:6080>; log in as `admin` with the password
    `rangerR0cks!`.

    `docker-compose.ranger.yml` defines four services on the `rangernw` network:

    `ranger`
    :   Ranger Admin, image `ranger:latest` built from `Dockerfile.ranger`, published on port 6080.

    `ranger-db`
    :   The policy database. The flavor is selected with `RANGER_DB_TYPE` (`postgres`, `mysql` or
        `oracle`) from `docker-compose.ranger-db.yml`.

    `ranger-zk`
    :   ZooKeeper, shared by the Solr audit store and by component containers such as Kafka, HBase and
        Hive.

    `ranger-kdc`
    :   A Kerberos KDC, used when `KERBEROS_ENABLED=true`.

    `docker-compose.ranger-audit-service.yml` adds Kafka (`ranger-kafka`), the audit ingestor
    (`ranger-audit-ingestor`) and, depending on the compose profile, the audit index and its dispatcher:
    `audit-store-opensearch` (default; `ranger-opensearch`, `ranger-audit-dispatcher-opensearch`) or
    `audit-store-solr` (`ranger-solr`, `ranger-audit-dispatcher-solr`). The
    [Audit Server](../audit-server/service.md) is not yet part of a release.

    The image holds the Admin distribution and the JDBC drivers for PostgreSQL, MySQL and Oracle; it is
    configured when the container starts. `scripts/admin/configs` is mounted read-only at
    `/opt/ranger/admin/configs` and, on every start, `scripts/admin/ranger.sh`:

    1. Rebuilds the configuration directory from the distribution defaults (`conf.dist`) and copies the
       mounted files over it, for example a custom `logback.xml`.
    2. Runs `scripts/admin/dba.py`, which renders `ranger-admin-site.xml` from
       `ranger-admin-site-<RANGER_DB_TYPE>.yaml`, a flat map of property name to value that is the complete
       site configuration. A `ranger-admin-site.xml` mounted in the same directory is used as it is instead.
       To override a `ranger-admin-site.xml` property, edit the YAML file and recreate the container.
    3. In the same script, stores the database password in the credential store, waits for the database,
       imports the core schema into an empty database, applies pending SQL and Java patches (tracked in
       `x_db_version_h`) and sets the passwords of the built-in users while they still have their initial
       values.
    4. Starts Admin with `ranger-admin-services.sh start`.
    5. Runs `scripts/admin/create-ranger-services.py`, which waits for the readiness endpoint
       `/service/actuator/health/readiness` to report `UP` and then creates a set of sample services.

    `docker logs ranger` shows every step followed by the Admin log.

    The variables below are read from `.env` or the shell and passed to the `ranger` container.

    | Variable | Default | Description |
    | --- | --- | --- |
    | `RANGER_VERSION` | `3.0.0-SNAPSHOT` | Version of the Admin archive in `dist/` that is built into the image. |
    | `RANGER_DB_TYPE` | (none) | Database flavor: `postgres`, `mysql` or `oracle`. Selects the `ranger-db` service and the YAML configuration file. Not set in `.env`; export it before running compose. |
    | `RANGER_ADMIN_DB_PASSWORD` | `rangerR0cks!` | Password of the database user `ranger.jpa.jdbc.user`; stored in the credential store. Required. |
    | `RANGER_ADMIN_PASSWORD` | `rangerR0cks!` | Password set for the built-in user `admin`. |
    | `RANGER_USERSYNC_PASSWORD` | `rangerR0cks!` | Password set for the built-in user `rangerusersync`. |
    | `RANGER_TAGSYNC_PASSWORD` | `rangerR0cks!` | Password set for the built-in user `rangertagsync`. |
    | `RANGER_KEYADMIN_PASSWORD` | `rangerR0cks!` | Password set for the built-in user `keyadmin`. |
    | `AUDIT_INDEX_STORE` | `opensearch` | Audit store Admin reads from, `opensearch` or `solr`; overrides `ranger.audit.source.type` of the YAML file. Must match the compose profile. |
    | `KERBEROS_ENABLED` | `true` | Wait for keytabs from `ranger-kdc` and enable Kerberos. |
    | `RANGER_ADMIN_MAX_HEAP` | `256m` | JVM heap (`-Xmx` and `-Xms`). |
    | `RANGER_JVM_METASPACE` | `100m` | Initial metaspace size. |
    | `RANGER_JVM_MAX_METASPACE` | `200m` | Maximum metaspace size. |
    | `JAVA_OPTS` | JDK 17 `--add-opens` flags | Extra JVM options. |
    | `DEBUG_ADMIN` | `false` | Switch Admin logging to `debug`. |
    | `RANGER_DB_WAIT_TIMEOUT` | `300` | Seconds `dba.py` waits for the database. Not passed by the compose file. |

    Inside the container, Admin is installed under `/opt/ranger/admin`, its configuration directory is
    `$RANGER_CONF_DIR` (`/opt/ranger/admin/ews/webapp/WEB-INF/classes/conf`) and keytabs are mounted at
    `/etc/keytabs`. The other compose files (UserSync, TagSync, KMS, PDP and the protected services) are
    covered in [Running Ranger with Docker](../../getting-started/docker.md).

    !!! warning
        The compose environment uses well-known default passwords and a self-contained KDC, and its YAML
        configuration enables [trusted header authentication](authentication.md) (`X-Forwarded-User`) so that
        the readiness endpoint can be called as the `healthcheck` user. It is meant for development
        and evaluation. For production, build your own image or host layout from the same distribution and
        supply your own `ranger-admin-site.xml`, credential store and keystores.

## Run from the distribution

The Admin distribution is `ranger-<version>-admin.tar.gz`, produced by `mvn package` in the Ranger source
tree (`target/`); for the master branch the version is `3.0.0-SNAPSHOT`.

### Directory layout

| Path | Content |
| --- | --- |
| `ews/ranger-admin-services.sh` | Service script: start, stop, restart, version, metrics |
| `ews/lib/` | Embedded web server (Tomcat) jars |
| `ews/webapp/` | The exploded web application |
| `ews/webapp/WEB-INF/classes/conf.dist/` | Configuration templates |
| `ews/webapp/WEB-INF/classes/conf/` | Active configuration directory (first entry on the classpath) |
| `ews/webapp/WEB-INF/lib/` | Application jars; place the JDBC driver here |
| `ews/webapp/WEB-INF/classes/ranger-plugins/<service>/` | Service-specific jars for *Test Connection* and resource lookup |
| `ews/webapp/apidocs/` | Generated REST API documentation, served at `/apidocs/` |
| `ews/logs/` | Default log directory |
| `db/<flavor>/` | Core schema and SQL patches per database flavor |
| `cred/lib/` | Credential-store builder used by `ranger_credential_helper.py` |
| `*.py` | Maintenance utilities, for example `changepasswordutil.py` and `deleteUserGroupUtil.py` |

### Configuration files

Admin reads its configuration from the classpath, in this order; later files override earlier ones.

`core-site.xml`
:   Optional Hadoop configuration. `hadoop.security.authentication=kerberos` turns on Kerberos, and
    `hadoop.security.auth_to_local` maps principals to user names.

`ranger-admin-default-site.xml`
:   Shipped defaults (pool sizes, CSRF, account lockout, password encryption, access log). Do not edit;
    override values in `ranger-admin-site.xml`.

`ranger-admin-site.xml`
:   Your deployment's settings: database, external URL, audit store, authentication, TLS, Kerberos.

Other files in the same directory are `security-applicationContext.xml` (Spring Security filter chain),
`logback.xml` (logging), optional `ranger-admin-env*.sh` scripts sourced by the service script, and the
credential store (`.jceks`) referenced by `ranger.credential.provider.path`.

A minimal `ranger-admin-site.xml` for PostgreSQL and OpenSearch:

```xml title="ranger-admin-site.xml"
<configuration>
  <property><name>ranger.jpa.jdbc.driver</name><value>org.postgresql.Driver</value></property>
  <property><name>ranger.jpa.jdbc.url</name><value>jdbc:postgresql://db.example.com:5432/ranger</value></property>
  <property><name>ranger.jpa.jdbc.user</name><value>rangeradmin</value></property>
  <property><name>ranger.jpa.jdbc.dialect</name><value>org.eclipse.persistence.platform.database.PostgreSQLPlatform</value></property>
  <property><name>ranger.credential.provider.path</name><value>/etc/ranger/admin/rangeradmin.jceks</value></property>
  <property><name>ranger.jpa.jdbc.credential.alias</name><value>ranger.db.password</value></property>

  <property><name>ranger.externalurl</name><value>http://ranger.example.com:6080</value></property>
  <property><name>ranger.service.host</name><value>ranger.example.com</value></property>

  <property><name>ranger.audit.source.type</name><value>opensearch</value></property>
  <property><name>ranger.audit.opensearch.urls</name><value>opensearch.example.com</value></property>
  <property><name>ranger.audit.opensearch.port</name><value>9200</value></property>
  <property><name>ranger.audit.opensearch.index</name><value>ranger_audits</value></property>
</configuration>
```

Store the database password in the credential store under the alias named by
`ranger.jpa.jdbc.credential.alias`:

```bash
python3 ranger_credential_helper.py -l "cred/lib/*" -f /etc/ranger/admin/rangeradmin.jceks \
  -k ranger.db.password -v '<db-password>' -c 1
```

### Start, stop and check

```bash
ews/ranger-admin-services.sh start
ews/ranger-admin-services.sh stop
ews/ranger-admin-services.sh restart
ews/ranger-admin-services.sh version
ews/ranger-admin-services.sh metric -type policies
```

`start` launches `org.apache.ranger.server.tomcat.EmbeddedServer` in the background with the `conf`
directory, `ews/lib` and `ews/webapp/WEB-INF/lib` on the classpath, and writes stdout to `catalina.out`.
`stop` sends the shutdown command to the Tomcat shutdown port (6085), waits up to about 30 seconds and
then kills the process. `metric` is described in [Metrics](metrics.md#command-line-metrics).

The script sources every `conf/ranger-admin-env*.sh` file before it starts the JVM, so persistent values
for the following environment variables belong in such a file.

| Variable | Default | Description |
| --- | --- | --- |
| `JAVA_HOME` | (none) | JDK used to run Admin; `$JAVA_HOME/bin` is put first on `PATH`. |
| `RANGER_ADMIN_MAX_HEAP` | `1g` | JVM heap, applied as both `-Xmx` and `-Xms`. |
| `RANGER_JVM_METASPACE` | `100m` | `-XX:MetaspaceSize`. |
| `RANGER_JVM_MAX_METASPACE` | `200m` | `-XX:MaxMetaspaceSize`. |
| `JAVA_OPTS` | (none) | Extra JVM options. `-Duser.timezone=UTC` is added when no time zone is given. |
| `RANGER_ADMIN_LOG_DIR` | `ews/logs` | Log directory, passed to the JVM as `-Dlogdir`. |
| `RANGER_ADMIN_LOGBACK_CONF_FILE` | `conf/logback.xml` | Logback configuration file. |
| `RANGER_PID_DIR_PATH` | `/var/run/ranger` | Directory of the PID file. |
| `RANGER_ADMIN_PID_NAME` | `rangeradmin.pid` | PID file name. |
| `RANGER_USER` | `ranger` | Owner given to the PID file. |
| `RANGER_HADOOP_CONF_DIR` | (none) | Hadoop configuration directory added to the classpath. |
| `DB_SSL_PARAM` | (none) | Extra `-Djavax.net.ssl.*` options for TLS to the database. |

Verify that Admin is up:

```bash
curl -s http://localhost:6080/service/actuator/health
curl -s -u admin:'<password>' http://localhost:6080/service/public/v2/api/servicedef | head -c 300
```

Then open `http://<host>:6080/` and log in as `admin`.

## Configuration reference

Every key below is a property of `ranger-admin-site.xml`. Defaults are the values shipped in
`conf.dist/ranger-admin-site.xml` and `conf.dist/ranger-admin-default-site.xml`, or the fallback in the
code that reads the key when neither file sets it. `(none)` means no default. Restart Admin after a change.

### Database

Connection to the policy database through JPA (EclipseLink) and a HikariCP pool. The flavor is detected
from the dialect and the JDBC URL. Examples for each database and the TLS options are in
[Database](database.md#connection-settings).

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.jpa.jdbc.url` | `jdbc:log4jdbc:mysql://localhost/ranger` | URL | JDBC URL of the Ranger database. |
| `ranger.jpa.jdbc.driver` | `net.sf.log4jdbc.DriverSpy` | Class | JDBC driver class. |
| `ranger.jpa.jdbc.dialect` | `org.eclipse.persistence.platform.database.MySQLPlatform` | Class | EclipseLink database platform for the flavor. |
| `ranger.jpa.jdbc.user` | `rangeradmin` | String | Database user. |
| `ranger.jpa.jdbc.password` | `rangeradmin` | Password | Database password. Replaced at start-up by the credential-store entry when one exists. |
| `ranger.credential.provider.path` | `/etc/ranger/admin/rangeradmin.jceks` | Path | Credential store (`.jceks`) holding passwords for the database, audit store, keystores and LDAP bind. |
| `ranger.jpa.jdbc.credential.alias` | `ranger.db.password` | String | Alias of the database password in the credential store. |
| `ranger.jpa.jdbc.maxpoolsize` | `40` | Integer | Maximum connections in the pool. |
| `ranger.jpa.jdbc.minpoolsize` | `5` | Integer | Minimum idle connections. |
| `ranger.jpa.jdbc.connectiontimeout` | `30000` | Duration (ms) | Wait for a connection from the pool. |
| `ranger.jpa.jdbc.idletimeout` | `300000` | Duration (ms) | Idle time before a connection is retired. |
| `ranger.jpa.jdbc.maxlifetime` | `1800000` | Duration (ms) | Maximum lifetime of a connection. |
| `ranger.jpa.jdbc.preferredtestquery` | `select 1` | String | Validation query. |
| `ranger.jpa.showsql` | `false` | Boolean | Log SQL statements. |

### Audit store

Admin queries one audit store to populate **Audits > Access** and the audit metrics. Select it with
`ranger.audit.source.type`, then configure the matching group. On start-up Admin can also create the Solr
collection or the Elasticsearch/OpenSearch index when it is missing (the *bootstrap* settings).

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.source.type` | `solr` | Enum | Audit store to read from: `solr`, `elasticsearch`, `opensearch`, `cloudwatch`. |

#### Solr

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.solr.urls` | `http://##solr_host##:6083/solr/ranger_audits` | URL | Solr collection URL. Replace the placeholder host. |
| `ranger.audit.solr.zookeepers` | (none) | String | ZooKeeper connect string of a SolrCloud cluster, for example `zk1:2181,zk2:2181/ranger_audits`. Takes precedence over the URL. |
| `ranger.audit.solr.collection.name` | `ranger_audits` | String | Collection name. |
| `ranger.solr.audit.user` | (none) | String | User for Solr basic authentication. |
| `ranger.solr.audit.user.password` | (none) | Password | Password for that user. Read from the credential store when the alias exists. |
| `ranger.solr.audit.credential.alias` | `ranger.solr.password` | String | Credential-store alias of the Solr password. |
| `ranger.audit.solr.bootstrap.enabled` | `true` | Boolean | Create the collection on start-up (SolrCloud only). |
| `ranger.audit.solr.config.name` | `ranger_audits` | String | Config set name used by the bootstrap. |
| `ranger.audit.solr.configset.location` | (none) | Path | Directory of a custom config set to upload. |
| `ranger.audit.solr.no.shards` | live node count | Integer | Number of shards for a new collection. |
| `ranger.audit.solr.no.replica` | `1` | Integer | Replication factor for a new collection. |
| `ranger.audit.solr.max.shards.per.node` | `1` | Integer | Maximum shards per node. |
| `ranger.audit.solr.time.interval` | `60000` | Duration (ms) | Wait between bootstrap attempts. |
| `ranger.audit.solr.max.retry` | `30` | Integer | Bootstrap attempts before giving up; `-1` retries until it succeeds. The shipped `ranger-admin-site.xml` carries this key with an empty value, which also results in `-1`. |

For a kerberized Solr, Admin logs in with a JAAS `Client` section built from the
`xasecure.audit.jaas.Client.*` properties (`loginModuleName`, `loginModuleControlFlag`,
`option.useKeyTab`, `option.storeKey`, `option.useTicketCache`, `option.serviceName`, `option.keyTab`,
`option.principal`); all of them are empty by default.

#### Elasticsearch

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.elasticsearch.urls` | `127.0.0.1` | List | Comma-separated host names (no scheme or port). |
| `ranger.audit.elasticsearch.port` | `9200` | Integer | REST port. |
| `ranger.audit.elasticsearch.protocol` | `http` | Enum | `http` or `https`. |
| `ranger.audit.elasticsearch.index` | `ranger_audits` | String | Index name. |
| `ranger.audit.elasticsearch.user` | (none) | String | User for basic authentication. |
| `ranger.audit.elasticsearch.password` | (none) | Password | Password for that user. |
| `ranger.audit.elasticsearch.bootstrap.enabled` | `true` | Boolean | Create the index on start-up. |
| `ranger.audit.elasticsearch.no.shards` | `1` | Integer | Shards for a new index. |
| `ranger.audit.elasticsearch.no.replica` | `1` | Integer | Replicas for a new index. |
| `ranger.audit.elasticsearch.time.interval` | `60000` | Duration (ms) | Wait between bootstrap attempts. |
| `ranger.audit.elasticsearch.max.retry` | `30` | Integer | Bootstrap attempts before giving up. |

#### OpenSearch

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.opensearch.urls` | (none) | List | Comma-separated host names. |
| `ranger.audit.opensearch.port` | `9200` | Integer | REST port. |
| `ranger.audit.opensearch.protocol` | `http` | Enum | `http` or `https`. |
| `ranger.audit.opensearch.index` | `ranger_audits` | String | Index name. |
| `ranger.audit.opensearch.authentication.type` | (none) | Enum | `kerberos`, `basic` or `none`. Inferred from the other settings when empty. |
| `ranger.audit.opensearch.user` | (none) | String | User for basic authentication. |
| `ranger.audit.opensearch.password` | (none) | Password | Password for that user. |
| `ranger.audit.opensearch.kerberos.principal` | (none) | String | Principal for Kerberos authentication. |
| `ranger.audit.opensearch.kerberos.keytab` | (none) | Path | Keytab of that principal. |
| `ranger.audit.opensearch.bootstrap.enabled` | `true` | Boolean | Create the index on start-up. |
| `ranger.audit.opensearch.no.shards` | `1` | Integer | Shards for a new index. |
| `ranger.audit.opensearch.no.replica` | `1` | Integer | Replicas for a new index. |
| `ranger.audit.opensearch.time.interval` | `60000` | Duration (ms) | Wait between bootstrap attempts. |
| `ranger.audit.opensearch.max.retry` | `30` | Integer | Bootstrap attempts before giving up. |

#### Amazon CloudWatch Logs

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.amazon_cloudwatch.region` | `us-east-2` | String | AWS region of the log group. |
| `ranger.audit.amazon_cloudwatch.log_group` | `ranger_audits` | String | Log group name. |
| `ranger.audit.amazon_cloudwatch.log_stream_prefix` | (none) | String | Prefix of the log streams to query. |

### Web server and ports

Settings of the embedded Tomcat server. When HTTPS is enabled, the HTTPS connector replaces the HTTP
connector, so Admin listens on one of the two ports.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.externalurl` | `http://localhost:6080` | URL | URL by which users and other services reach Admin. Use the load-balancer URL in an HA setup. |
| `ranger.service.host` | `localhost` | String | Host name of this instance; replaces `_HOST` in Kerberos principals. |
| `ranger.service.http.port` | `6080` | Integer | HTTP port. |
| `ranger.service.https.port` | `6182` | Integer | HTTPS port, used when `ranger.service.https.attrib.ssl.enabled=true`. |
| `ranger.service.shutdown.port` | `6085` | Integer | Tomcat shutdown port, used by `ranger-admin-services.sh stop`. |
| `ranger.service.shutdown.command` | `SHUTDOWN` | String | Command string expected on the shutdown port. |
| `ranger.contextName` | `/` | String | Web application context path. |
| `ranger.tomcat.work.dir` | (none) | Path | Tomcat work directory. |
| `ajp.enabled` | `false` | Boolean | Open an AJP connector on the HTTP port instead of HTTP/HTTPS. |
| `ranger.service.http.connector.attrib.maxPostSize` | `2097152` | Integer | Maximum POST body size in bytes parsed by the container. |
| `ranger.service.http.connector.attrib.maxParameterCount` | `10000` | Integer | Maximum number of request parameters. |
| `ranger.service.http.connector.attrib.asyncTimeout` | `10000` | Duration (ms) | Timeout for asynchronous requests. |
| `ranger.service.http.connector.attrib.allowTrace` | `false` | Boolean | Allow the HTTP `TRACE` method. |
| `ranger.service.http.connector.attrib.enableLookups` | `false` | Boolean | Resolve client host names with DNS. |
| `ranger.service.http.connector.attrib.URIEncoding` | `UTF-8` | String | Character encoding of request URIs. |

Any property named `ranger.service.http.connector.property.<name>` is passed to the Tomcat connector as
the connector property `<name>`, which gives access to settings that have no dedicated key (for example
`maxThreads`).

### TLS

HTTPS for the UI and REST API, and the keystore and truststore Admin uses for its own outbound
connections (database, LDAP, audit store, resource lookup). Step-by-step procedures are in
[Security hardening](security-hardening.md#https-for-the-admin-ui-and-api).

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.service.https.attrib.ssl.enabled` | `false` | Boolean | Serve HTTPS on `ranger.service.https.port`. |
| `ranger.service.https.attrib.keystore.file` | `/etc/ranger/admin/keys/server.jks` | Path | Server keystore. |
| `ranger.service.https.attrib.keystore.keyalias` | `myKey` | String | Alias of the server key in the keystore. |
| `ranger.service.https.attrib.keystore.credential.alias` | `keyStoreCredentialAlias` | String | Credential-store alias of the keystore password. |
| `ranger.service.https.attrib.keystore.pass` | `_` | Password | Keystore password, used only when the credential store has no entry. |
| `ranger.service.https.attrib.ssl.protocol` | `TLS` | String | SSL context protocol. |
| `ranger.service.https.attrib.ssl.enabled.protocols` | `TLSv1.2` | List | Protocol versions offered to clients. |
| `ranger.tomcat.ciphers` | (none) | List | Cipher suites to allow. Empty means the JVM defaults. |
| `ranger.service.https.attrib.client.auth` | `false` | Enum | Client certificates: `false`, `want` or `true`. |
| `ranger.service.http.enabled` | `true` | Boolean | When `false`, policy download and grant/revoke requests from plugins are accepted only over HTTPS with a client certificate that matches the service's `commonNameForCertificate`. |
| `ranger.keystore.file` | (none) | Path | Client keystore for outbound TLS; exported as `javax.net.ssl.keyStore`. |
| `ranger.keystore.file.type` | `jks` | String | Keystore type, also used for the HTTPS keystore. |
| `ranger.keystore.alias` | `keyStoreAlias` | String | Credential-store alias of the client keystore password. |
| `ranger.keystore.password` | (none) | Password | Client keystore password when not in the credential store. |
| `ranger.truststore.file` | (none) | Path | Truststore for outbound TLS and client-certificate validation; exported as `javax.net.ssl.trustStore`. |
| `ranger.truststore.file.type` | `jks` | String | Truststore type. |
| `ranger.truststore.alias` | `trustStoreAlias` | String | Credential-store alias of the truststore password. |
| `ranger.truststore.password` | (none) | Password | Truststore password when not in the credential store. |
| `ranger.db.ssl.enabled` | `false` | Boolean | Encrypt the JDBC connection (MySQL and PostgreSQL). See [Database](database.md#tls-to-the-database). |

### Authentication

`ranger.authentication.method` selects where passwords typed into the login form or sent with HTTP Basic
are checked. The properties of each method, and of header-based and JWT authentication, are documented in
[Authentication](authentication.md).

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.authentication.method` | `NONE` | Enum | `NONE` (Ranger database only), `LDAP`, `ACTIVE_DIRECTORY` or `PAM`. |
| `ranger.ldap.default.role` | `ROLE_USER` | String | Role given to externally authenticated users that have no role yet. |
| `ranger.admin.super.users` | (none) | List | Users granted full administrative rights at login. |
| `ranger.admin.super.groups` | (none) | List | Groups whose members are granted full administrative rights at login. |
| `ranger.admin.cookie.name` | `RANGERADMINSESSIONID` | String | Session cookie name. |
| `ranger.session.limit.concurrency` | `0` | Integer | Maximum concurrent UI sessions per user on one Admin instance; the oldest session is expired when a new login exceeds it. `0` or a negative value means no limit. See [Authentication](authentication.md#concurrent-ui-sessions). |
| `ranger.admin.healthcheck.username` | `healthcheck` | String | User allowed to call the readiness endpoint. |

### Kerberos

Kerberos is active when `core-site.xml` in the configuration directory sets
`hadoop.security.authentication=kerberos`. Admin then accepts SPNEGO on the REST API, logs in with its own
principal for outbound calls, and uses the lookup principal for *Test Connection* and resource lookup.
See [Authentication](authentication.md#kerberos-spnego).

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.spnego.kerberos.principal` | `HTTP/_HOST@REALM` | String | SPNEGO service principal. |
| `ranger.spnego.kerberos.keytab` | (none) | Path | Keytab of the SPNEGO principal. |
| `ranger.admin.kerberos.principal` | `rangeradmin/_HOST@REALM` | String | Principal Admin logs in with for outbound calls. |
| `ranger.admin.kerberos.keytab` | (none) | Path | Keytab of the Admin principal. |
| `ranger.lookup.kerberos.principal` | `rangerlookup/_HOST@REALM` | String | Principal used for resource lookup. |
| `ranger.lookup.kerberos.keytab` | (none) | Path | Keytab of the lookup principal. |
| `ranger.admin.kerberos.token.valid.seconds` | `30` | Integer | Validity of the `hadoop.auth` cookie, in seconds. |
| `ranger.admin.kerberos.cookie.domain` | (none) | String | Domain of that cookie. |
| `ranger.admin.kerberos.cookie.path` | `/` | String | Path of that cookie. |

### SSO

Browser single sign-on through Apache Knox. See [Authentication](authentication.md#knox-sso) for the
remaining `ranger.sso.*` keys and for JWT bearer tokens.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.sso.enabled` | `false` | Boolean | Redirect unauthenticated browsers to the SSO provider. |
| `ranger.sso.providerurl` | `https://127.0.0.1:8443/gateway/knoxsso/api/v1/websso` | URL | Knox SSO endpoint. |
| `ranger.sso.publicKey` | (none) | String | Signing certificate of the SSO provider in PEM (Base64) form, without the `BEGIN CERTIFICATE`/`END CERTIFICATE` lines. |
| `ranger.sso.cookiename` | `hadoop-jwt` | String | Cookie that carries the token. |
| `ranger.sso.browser.useragent` | `Mozilla,chrome` | List | User-agent prefixes that are redirected. |

### Logging

Application logging is configured in `logback.xml`, not in the site file. The keys below control the
Tomcat access log, which is written to the log directory.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.accesslog.enabled` | `true` | Boolean | Write the access log. |
| `ranger.accesslog.prefix` | `access-<hostname>` | String | File name prefix. |
| `ranger.accesslog.dateformat` | `-yyyy-MM-dd` | String | Date suffix of the file name; also the rotation granularity. |
| `ranger.accesslog.pattern` | `%h %l %u %t "%r" %s %b %D "%{Referer}i" "%{User-Agent}i"` | String | Tomcat access log pattern. |
| `ranger.accesslog.rotate.enabled` | `true` | Boolean | Rotate the access log. |
| `ranger.accesslog.rotate.max_days` | `15` | Integer | Days to keep rotated files. |
| `ranger.accesslog.rotate.rename_on_rotate` | `false` | Boolean | Add the date suffix only when the file is rotated. |

### Advanced

Keys that rarely need to change.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.supportedcomponents` | (none) | List | Service types whose definitions are created, for example `hive,hdfs,trino`. Empty means all. |
| `ranger.db.maxrows.default` | `200` | Integer | Default page size of list APIs. |
| `ranger.jpa.jdbc.batch-clear.enable` | `true` | Boolean | Flush and clear the persistence context during bulk policy operations. |
| `ranger.jpa.jdbc.batch-clear.size` | `10` | Integer | Policies per flush when batch-clear is enabled. |
| `ranger.jpa.jdbc.batch-persist.size` | `500` | Integer | Objects per flush during bulk persistence. |
| `ranger.timed.executor.max.threadpool.size` | `10` | Integer | Threads for *Test Connection* and resource lookup. |
| `ranger.timed.executor.queue.size` | `100` | Integer | Queue length of that executor. |
| `ranger.resource.lookup.timeout.value.in.ms` | `1000` | Duration (ms) | Timeout of a resource lookup. |
| `ranger.validate.config.timeout.value.in.ms` | `10000` | Duration (ms) | Timeout of *Test Connection*. |
| `ranger.downloadpolicy.session.log.enabled` | `false` | Boolean | Record a login session for every plugin policy download. |
| `ranger.audit.hive.query.visibility` | `true` | Boolean | Show the Hive query text in access audits. |
| `ranger.audit.metrics.max.supported.days` | `90` | Integer | Maximum look-back of the audit metrics API. |
| `ranger.servicedef.ozone.enableActionMatcherInPoliciesCondition` | `false` | Boolean | Add the action-matcher policy condition to the Ozone service definition. |
| `ranger.valve.errorreportvalve.showserverinfo` | `false` | Boolean | Show the server version on error pages. |
| `ranger.valve.errorreportvalve.showreport` | `false` | Boolean | Show stack traces on error pages. |

Account lockout, password history, CSRF and password-encryption keys are covered in
[Security hardening](security-hardening.md); start-up purge of login and change history in
[Database](database.md#purging-history-at-start-up).

## Log files

All files are written to the log directory (`RANGER_ADMIN_LOG_DIR`, default `ews/logs`).

| File | Content |
| --- | --- |
| `ranger-admin-<hostname>-<user>.log` | Application log, rotated daily |
| `ranger_admin_sql.log` | JDBC logging (errors only by default) |
| `ranger_admin_perf.log` | Performance logger `org.apache.ranger.perf` |
| `ranger_db_patch.log` | Output of Java patches (`org.apache.ranger.patch`) |
| `catalina.out` | Stdout and stderr of the server process |
| `access-<hostname>-<date>.log` | Tomcat access log |
| `gc-worker.log` | JVM garbage-collection log |

## Upgrade

1. Stop the running instance: `ews/ranger-admin-services.sh stop`.
2. Back up the database and the configuration directory, including the credential store.
3. Deploy the new distribution or image with the same `ranger-admin-site.xml`, credential store,
   keystores and JDBC driver.
4. Let the new version bring the schema up to date. Ranger ships numbered SQL and Java patches and records
   the applied ones in `x_db_version_h`, so only the missing patches run. See
   [Database](database.md#schema-patches-and-upgrades) for the mechanism, multi-instance coordination and
   the transaction-log migration.
5. Start the new version and check `ews/ranger-admin-services.sh version`.

## Troubleshooting

`Apache Ranger Admin Service failed to start!`
:   Read `catalina.out`. Typical causes are a port already in use (6080, 6182 or 6085), a missing JDBC
    driver jar, or a wrong database password in the credential store.

`Apache Ranger Admin Service is already running`
:   The PID file points to a live process. Stop it first, or remove a stale PID file from
    `RANGER_PID_DIR_PATH`.

HTTPS port does not open
:   Admin logs `HTTPS configuration validation failed` when the keystore file, key alias or password is
    wrong. Check `ranger.service.https.attrib.keystore.*` and the credential-store alias.

The UI shows no audits
:   The store named by `ranger.audit.source.type` is unreachable, or the collection or index is missing.
    Look for the bootstrap messages in `catalina.out` and the application log.

Logins work but every page reports a database error
:   The password in the credential store does not match the database user. Update the alias with
    `ranger_credential_helper.py` and restart.

## Further reading

- [`ranger-admin-site.xml`](https://github.com/apache/ranger/blob/master/security-admin/src/main/resources/conf.dist/ranger-admin-site.xml)
  and [`ranger-admin-default-site.xml`](https://github.com/apache/ranger/blob/master/security-admin/src/main/resources/conf.dist/ranger-admin-default-site.xml)
- [`embeddedwebserver/scripts/ranger-admin-services.sh`](https://github.com/apache/ranger/blob/master/embeddedwebserver/scripts/ranger-admin-services.sh)
- [`EmbeddedServer.java`](https://github.com/apache/ranger/blob/master/embeddedwebserver/src/main/java/org/apache/ranger/server/tomcat/EmbeddedServer.java)
- [`dev-support/ranger-docker/README.md`](https://github.com/apache/ranger/blob/master/dev-support/ranger-docker/README.md)
- [Running Ranger with Docker](../../getting-started/docker.md)
