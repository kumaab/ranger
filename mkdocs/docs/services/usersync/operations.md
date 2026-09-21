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

# UserSync operations

This page covers the day-to-day tasks of running UserSync: starting and stopping it, reading its logs and
metrics, rotating the credentials it uses to talk to Ranger Admin, understanding what happens when users
disappear from the source or when two sync sources overlap, and diagnosing the usual failures.

## Start, stop, status

```bash
./ranger-usersync-services.sh start      # also: stop | restart | version
```

The script sources every `conf/ranger-usersync-env*` file, builds the classpath from `dist/`, `conf/`,
`ews/lib/` and the Hadoop configuration directory, and starts
`org.apache.ranger.authentication.server.RangerUserSyncServer` with `nohup`. It writes the PID to
`${USERSYNC_PID_DIR_PATH}/usersync.pid` (default `/var/run/ranger/usersync.pid`) and refuses to start if
that PID is alive. `stop` sends `SIGTERM`, waits up to 30 seconds and then `SIGKILL`s.

There is no `status` action; use `ps -ef | grep Dproc_rangerusersync` or the
[metrics endpoint](#metrics).

With Docker, the container starts the service for you; use `docker logs -f ranger-usersync`,
`docker restart ranger-usersync` and `docker compose ... down` from `dev-support/ranger-docker` (see
[Running UserSync](service.md#running-usersync)).

| Environment variable | Default | Purpose |
|---|---|---|
| `RANGER_USERSYNC_MAX_HEAP` | `1g` | `-Xmx`/`-Xms` |
| `RANGER_JVM_METASPACE` | `100m` | Initial metaspace size |
| `RANGER_JVM_MAX_METASPACE` | `200m` | Maximum metaspace size |
| `JAVA_OPTS` | (none) | Extra JVM flags (truststore, GC, debug agent) |
| `RANGER_USERSYNC_LOG_DIR` | `/var/log/ranger/usersync` | Where `catalina.out` and the application logs go |
| `USERSYNC_PID_DIR_PATH` | `/var/run/ranger` | PID directory |
| `USERSYNC_CONF_DIR` | (none) | Configuration directory that holds `logback.xml` |
| `RANGER_USERSYNC_HADOOP_CONF_DIR` | (none) | Added to the classpath for `core-site.xml` (Kerberos) |

Put permanent overrides in a `conf/ranger-usersync-env-<name>.sh` file. The Docker setup sets
`RANGER_USERSYNC_MAX_HEAP=256m` through `dev-support/ranger-docker/.env`.

## Logs

- `${RANGER_USERSYNC_LOG_DIR}/catalina.out` - stdout/stderr of the JVM, including startup failures.
- Application logs are configured by `conf/logback.xml`; the root level is `info`. Set it to `debug` to see
  every LDAP search, the computed deltas and each REST call to Ranger Admin (the Docker setup does this
  when `DEBUG_USERSYNC=true`).
- Each cycle logs `Begin: update user/group from source ==> sink` and `End: ...`; a passive HA instance logs
  `Sleeping ... as this server is running in passive mode` instead.

The result of each cycle is also posted to Ranger Admin (`/service/xusers/ugsync/auditinfo/`) and shown under
**Audit > User Sync** with the sync source, counts of users and groups synced, and for LDAP the search bases
and filters that were used. This is the first place to look when Ranger Admin does not show the users you
expect.

## Changing the Ranger Admin credentials

UserSync logs in to Ranger Admin as `ranger.usersync.policymgr.username` (default `rangerusersync`,
default password `rangerusersync`), which must have the Admin role. Change the password in Ranger Admin
first, then update UserSync:

```bash
python3 updatepolicymgrpassword.py       # prompts for user name and password
./ranger-usersync-services.sh restart
```

The script stores the new password in the credential store named by `ranger.usersync.credstore.filename`
under the alias `ranger.usersync.policymgr.password`, and writes `ranger.usersync.policymgr.username`,
`ranger.usersync.policymgr.keystore` and `ranger.usersync.policymgr.alias` into
`conf/ranger-ugsync-site.xml` to match.

!!! note
    The LDAP bind password lives in the same file under `ranger.usersync.ldap.bindalias`; never point
    `ranger.usersync.policymgr.alias` at that alias. To use a different Ranger Admin user, create it in Ranger
    Admin with the Admin role (an external user works too) before running the script.

With Kerberos (`ranger.usersync.kerberos.principal` and `ranger.usersync.kerberos.keytab` set, and
`hadoop.security.authentication=kerberos` in the `core-site.xml` on the classpath), UserSync authenticates
with SPNEGO and the password is not used.
The principal's short name must still map to a Ranger Admin user with the Admin role; in the Docker setup
the principal `rangerusersync/ranger-usersync.rangernw@EXAMPLE.COM` maps to `rangerusersync`.

## Sync source enforcement

Every user and group synced by UserSync records its `sync_source` (`Unix`, `LDAP/AD` or `File`) in
`other_attributes`. When `ranger.usersync.syncsource.validation.enabled` is `true` (the default), the sink
does not modify a user or group that already exists in Ranger Admin with a different `sync_source`, which
stops two UserSync instances with different sources from overwriting each other's records. Set it to
`false` on purpose when you migrate from one source to another - for example from UNIX to LDAP - so that the
first cycle of the new instance can take over the existing records; then set it back.

Users created manually in the Ranger Admin UI or through the REST API are *internal* users and are never
touched by UserSync.

With `ranger.usersync.name.validation.enabled=true`, names that do not match the pattern Ranger Admin
accepts (letters, digits, `_`, and after the first character also spaces, `,`, `.`, `-`, `+`, `/`, `@`,
`=`) are skipped with a log message instead of failing the whole batch.

## Deleting users and groups

UserSync never deletes anything. With `ranger.usersync.deletes.enabled=true`, every
`ranger.usersync.deletes.frequency` cycles (every cycle when the property is unset; an explicit value has a
minimum of 10) it compares the full source list with the sink and
marks users and groups that are gone as hidden (`isVisible=0`) via
`/service/xusers/ugsync/users/visibility` and `/service/xusers/ugsync/groups/visibility`. Hidden users no
longer appear in policy editors but their policy references stay intact. Only records whose `sync_source`
matches this instance are considered, so a UNIX-sourced instance never hides LDAP users.

To remove a user or group permanently, delete it in Ranger Admin (**Settings > Users/Groups**) or through
the REST API; see [Users, groups and roles](../admin/users-groups-roles.md). If the account still exists in
the source, UserSync recreates it on the next cycle, so remove it from the source first (or exclude it with
the search filter or `ranger.usersync.unix.minUserId`).

## Metrics

The embedded web server (port `ranger.usersync.service.http.port`, default `8280`) serves:

- `GET /metrics/status` - liveness check
- `GET /metrics/prometheus` - metrics in Prometheus text format
- `GET /metrics/json` - the same metrics as JSON

When `ranger.usersync.metrics.enabled=true`, `UserSyncMetricsProducer` additionally writes JVM and sync metrics every
`ranger.usersync.metrics.frequencytimeinmillis` (10 s) to
`${ranger.usersync.metrics.filepath}/${ranger.usersync.metrics.filename}` (default
`<logdir>/ranger_usersync_metric.json`).

## SSL

- **Towards Ranger Admin**: use an `https://` URL in `ranger.usersync.policymanager.baseURL` and point
  `ranger.usersync.truststore.file` at a truststore containing the Ranger Admin certificate. Keep its
  password in the credential store under the alias `usersync.ssl.truststore.password`; see
  [TLS towards Ranger Admin](service.md#tls-towards-ranger-admin).
- **Towards LDAP**: `ldaps://` URLs use the truststore in `ranger.usersync.truststore.file` when that
  property is set, otherwise the JVM default truststore (import the directory CA into `cacerts` or pass
  `-Djavax.net.ssl.trustStore=...` in `JAVA_OPTS`). `ranger.usersync.ldap.starttls=true` upgrades a
  plain connection instead.
- **Inbound** (the embedded web server): see the `ranger.usersync.service.https.*` properties on the
  [overview](service.md#embedded-web-server).

## Upgrade

1. Stop UserSync.
2. Deploy the new version (new container image, or the new distribution next to the old one) and carry
   over `conf/ranger-ugsync-site.xml`, `conf/core-site.xml` if you use Kerberos, and any
   `conf/ranger-usersync-env-*.sh` files.
3. Keep the credential stores named by `ranger.usersync.credstore.filename` and
   `ranger.usersync.policymgr.keystore`; keeping them outside the installation directory (for example under
   `/etc/ranger/usersync/conf/`) makes this automatic.
4. Start the new version and confirm the first cycle in **Audit > User Sync**.

Delta-sync bookmarks are kept in memory only, so the first cycle after any restart is a full sync.

## Troubleshooting

`Apache Ranger Usersync Service failed to start!`
:   `catalina.out`; typically `JAVA_HOME` missing, the port in `ranger.usersync.service.http.port` in use, or the credential store not readable by the UserSync account

HTTP 401 from Ranger Admin
:   Password of `rangerusersync` changed in Ranger Admin but not in UserSync; run `updatepolicymgrpassword.py`

HTTP 403 from Ranger Admin
:   The sync user lost the Admin role, or Kerberos principal maps to a different user

Users appear but no groups
:   Group search disabled and `memberOf` empty; see [LDAP](ldap-ad.md#groups)

Users missing after switching source
:   `ranger.usersync.syncsource.validation.enabled` protects records of the previous source

First cycle very slow
:   Normal for large directories; with `ranger.usersync.ldap.deltasync=true` later cycles only read changed entries

`This userGroupSync server is not in active state`
:   HA is enabled and this instance is passive; check ZooKeeper connectivity if no instance becomes active

Names differ from Kerberos short names
:   Adjust case conversion or the regex mapping so that plugin-reported names match

## Further reading

- [UserSync overview](service.md)
- [Ranger Admin UI guide](../admin/ui-guide.md) (Audit > User Sync tab)
- cwiki: [Change policy manager password in UserSync](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=65866816)
