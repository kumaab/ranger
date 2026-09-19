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

# UNIX and file sources

The UNIX source reads the accounts and groups that exist on the machine UserSync runs on. It needs no
configuration beyond the Ranger Admin URL and the source selection, which makes it the usual choice for single-node
evaluations and for deployments where accounts are managed with tools such as SSSD that expose directory
users as local accounts. The file source reads a CSV or JSON file instead; it is handy for tests, for loading users
exported from another system, or for hosts that have no directory access.

Both sources feed the same sink and get the same incremental-upload behavior described on the
[UserSync overview](service.md#how-it-works).

## UNIX source

Set `ranger.usersync.sync.source` to `unix` in `ranger-ugsync-site.xml` and leave
`ranger.usersync.source.impl.class` unset. UserSync then uses
`org.apache.ranger.unixusersync.process.UnixUserGroupBuilder` and, unless you set an interval, syncs every
minute (which is also the shortest interval this source accepts).

```xml title="conf/ranger-ugsync-site.xml"
<property>
  <name>ranger.usersync.sync.source</name>
  <value>unix</value>
</property>
<property>
  <name>ranger.usersync.unix.backend</name>
  <value>nss</value>
</property>
<property>
  <name>ranger.usersync.unix.minUserId</name>
  <value>1000</value>
</property>
```

### Backends

`UnixUserGroupBuilder` can read accounts in two ways, selected by `ranger.usersync.unix.backend`:

`nss`
:   Runs `getent passwd` and `getent group` (on macOS, `dscl`), so every NSS source the host knows about -
    local files, SSSD, LDAP via nslcd - is visible. Use this backend unless you have a reason not to.

`passwd`
:   Parses the files named by `ranger.usersync.unix.password.file` and `ranger.usersync.unix.group.file`
    directly. This is what you get when the property is not set; UserSync logs it as deprecated at startup.

With the `nss` backend and `ranger.usersync.group.enumerate=true`, UserSync also runs
`getent group <name>` for every group to find members that are not listed in the `getent passwd` output.
Independently of that flag, `ranger.usersync.group.enumerategroup` names additional groups (comma-separated)
that are queried individually with `getent group <name>`.

### Properties

| Key | Default | Type | Description |
|---|---|---|---|
| `ranger.usersync.unix.backend` | `passwd` | Enum | `nss` or `passwd`, see above |
| `ranger.usersync.unix.minUserId` | `500` | Integer | Users with a lower UID are skipped, which keeps system accounts out of Ranger |
| `ranger.usersync.unix.minGroupId` | `0` | Integer | Groups with a lower GID are skipped |
| `ranger.usersync.unix.password.file` | `/etc/passwd` | Path | User file for the `passwd` backend |
| `ranger.usersync.unix.group.file` | `/etc/group` | Path | Group file for the `passwd` backend |
| `ranger.usersync.unix.updatemillismin` | `60000` | Duration (ms) | `nss` backend: the source re-reads the accounts at most this often; a cycle that fires earlier is skipped. Values below `60000` are raised to `60000` |
| `ranger.usersync.group.enumerate` | `false` | Boolean | Query each group individually to find members missing from `getent passwd` |
| `ranger.usersync.group.enumerategroup` | (none) | List | Additional groups to query individually (`nss` backend) |

With the `passwd` backend the source re-reads the accounts only when the modification time of the password
or group file changed; with `nss` it re-reads whenever `ranger.usersync.unix.updatemillismin` has elapsed.
It hands the complete list to the sink, which uploads only what changed (see
[Incremental sync and deletes](#incremental-sync-and-deletes)).

!!! tip "Same accounts on every node"
    UserSync only sees the host it runs on. If your nodes have different local accounts, run it on the
    node whose accounts you want in Ranger, or switch to the [LDAP source](ldap-ad.md).

## File source

The file source has no `ranger.usersync.sync.source` value of its own; name the source class instead (it
takes precedence over `ranger.usersync.sync.source`):

```xml title="conf/ranger-ugsync-site.xml"
<property>
  <name>ranger.usersync.source.impl.class</name>
  <value>org.apache.ranger.unixusersync.process.FileSourceUserGroupBuilder</value>
</property>
<property>
  <name>ranger.usersync.filesource.file</name>
  <value>/etc/ranger/usersync/ranger-usergroups.csv</value>
</property>
<property>
  <name>ranger.usersync.filesource.text.delimiter</name>
  <value>,</value>
</property>
```

| Key | Default | Type | Description |
|---|---|---|---|
| `ranger.usersync.filesource.file` | (none) | Path | Input file; required, and must be readable by the account UserSync runs as |
| `ranger.usersync.filesource.text.delimiter` | `,` | String | Field separator for non-JSON files |

The Docker setup in `dev-support/ranger-docker` uses exactly this configuration when you start it with
`ENABLE_FILE_SYNC_SOURCE=true`; the input is `scripts/usersync/ugsync-file-source.csv`.

### File formats

A file whose name ends in `.json` is parsed as a JSON object mapping each user to its groups:

```json title="ranger-usergroups.json"
{
  "user1": ["group-1", "group-2", "group-3"],
  "user2": ["group-x", "group-y"],
  "user3": []
}
```

Any other file is read line by line as delimited text; the first field is the user, the rest are groups.
Quoted fields are allowed:

```text title="ranger-usergroups.csv"
user1,group-1,group-2,group-3
"user2","group-x","group-y"
user3,
```

The source records the file's modification time and only re-reads and re-syncs when the file changes, so
touching the file is enough to trigger an upload on the next cycle. Users synced this way get
`sync_source=File` in their attributes.

### One-shot upload from the command line

For a bulk load without running the service, the distribution contains `filesourceusersynctool/run-filesource-usersync.sh`,
which runs `FileSourceUserGroupBuilder` once with the given file and the configuration in `conf/`:

```bash
cd /path/to/ranger-<version>-usersync
./filesourceusersynctool/run-filesource-usersync.sh -i /tmp/users.json
```

The equivalent direct invocation is
`java -Dlogdir=/var/log/ranger/usersync -cp "dist/*:lib/*:conf" org.apache.ranger.unixusersync.process.FileSourceUserGroupBuilder /tmp/users.json`.

## Incremental sync and deletes

Whatever the source, the sink (`PolicyMgrUserGroupBuilder`) keeps an in-memory cache of the users, groups
and memberships it last saw in Ranger Admin (loaded from `/service/xusers/users/`, `/service/xusers/groups/`
and `/service/xusers/ugsync/groupusers` at startup). On every cycle it computes the delta and only calls
`/service/xusers/ugsync/users`, `/service/xusers/ugsync/groups/` and `/service/xusers/ugsync/groupusers` for
records that are new or changed. A cycle with no changes uploads no users, groups or memberships.

Users and groups that disappear from the source are left untouched unless `ranger.usersync.deletes.enabled`
is `true`; then, every `ranger.usersync.deletes.frequency` cycles, they are marked hidden (visibility `0`)
through `/service/xusers/ugsync/users/visibility` and `/service/xusers/ugsync/groups/visibility`. Nothing is
ever physically deleted by UserSync; see [Operations](operations.md#deleting-users-and-groups).

## Further reading

- [UserSync overview](service.md)
- [LDAP and Active Directory](ldap-ad.md)
- Source: [`UnixUserGroupBuilder.java`](https://github.com/apache/ranger/blob/master/ugsync/src/main/java/org/apache/ranger/unixusersync/process/UnixUserGroupBuilder.java),
  [`FileSourceUserGroupBuilder.java`](https://github.com/apache/ranger/blob/master/ugsync/src/main/java/org/apache/ranger/unixusersync/process/FileSourceUserGroupBuilder.java)
- cwiki: [File source user group sync process](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=61323585)
