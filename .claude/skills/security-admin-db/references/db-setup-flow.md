<!--
 - Licensed to the Apache Software Foundation (ASF) under one or more
 - contributor license agreements.  See the NOTICE file distributed with
 - this work for additional information regarding copyright ownership.
 - The ASF licenses this file to You under the Apache License, Version 2.0
 - (the "License"); you may not use this file except in compliance with
 - the License.  You may obtain a copy of the License at
 -
 -   http://www.apache.org/licenses/LICENSE-2.0
 -
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and
 - limitations under the License.
 -->

# `db_setup.py` flow and `x_db_version_h`

File: `security-admin/scripts/db_setup.py`. Invoked by `setup.sh` as `$PYTHON_COMMAND_INVOKER db_setup.py [-javapatch | -changepassword | -checkupgrade]`.

## Flow

1. `populate_global_dict()` reads `install.properties` (blanks any key containing `PASSWORD`).
2. Resolve `JAVA_BIN`, then `ranger_version` (from `RangerVersionInfo` on the classpath, falling back to `ranger-admin-services.sh version`).
3. Pick the `BaseDB` subclass from `DB_FLAVOR`: `MYSQL` -> `MysqlConf`, `ORACLE` -> `OracleConf`, `POSTGRES` -> `PostgresConf`, `MSSQL` -> `SqlServerConf`, `SQLA` -> `SqlAnywhereConf`.
   Each sets `XA_DB_FLAVOR`, `commandTerminator`, and overrides `get_jisql_cmd`, `get_check_table_query`, `insert_patch_applied_query`, `get_db_server_status_query`,
   `get_unstale_patch_query`/`get_stale_patch_query`. Schema paths come from `install.properties` keys `<vendor>_core_file`.
4. `check_connection`.
5. No args: create `x_db_version_h` if absent (`create_dbversion_catalog.sql`) -> `import_core_db_schema` -> `apply_patches` if `hasPendingPatches('DB_PATCHES')`.
   `-javapatch`: `execute_java_patches` if `hasPendingPatches('JAVA_PATCHES')`, then mark `JAVA_PATCHES`.

## `apply_patches`

```python
sorted_files = sorted(files, key=lambda x: str(x.split('.')[0]))     # lexicographic: zero-pad NNN
for filename in sorted_files:
    version = filename.split('-')[0]
    pre_dict = self.get_pre_post_java_patches(pre_sql_prefix + version)    # PatchPreSql_NNN_*
    if pre_dict: self.execute_java_patches(...)
    self.import_db_patches(db_name, db_user, db_password, currentPatch)
    post_dict = self.get_pre_post_java_patches(post_sql_prefix + version)  # PatchPostSql_NNN_*
    if post_dict: self.execute_java_patches(...)
self.update_applied_patches_status(db_name, db_user, db_password, "DB_PATCHES")
```

Directories (like `patches/audit`) are skipped by the `os.path.isfile` guard in `import_db_patches`.

## `x_db_version_h`

| Column | Meaning |
|---|---|
| `version` | `'078'` SQL patch, `'J10066'` Java patch, `'CORE_DB_SCHEMA'` / `'DB_PATCHES'` / `'JAVA_PATCHES'` milestones, `'DEFAULT_ADMIN_UPDATE'` etc. password markers |
| `inst_by` | Ranger version string (`Ranger 3.0.0`) |
| `updated_by` | hostname that claimed the row (the lock owner); `'localhost'` in optimized schemas |
| `active` | `'N'` claimed / in progress, `'Y'` applied. There is no numeric flag. |
| `inst_at` | used for stale-lock detection (10 min, `STALE_PATCH_ENTRY_HOLD_TIME`) |

Locking algorithm in `import_db_patches` (mirrored for core schema and Java patches):

1. `(version, 'Y')` exists -> skip.
2. `(version, 'N')` exists: if held by another host, sleep `PATCH_RETRY_INTERVAL` (120s) and re-poll indefinitely; if held by this host and fresh, wait; if stale, delete and take over.
3. Insert claim row (`active='N'`, `updated_by=client_host`, `inst_by=ranger_version`).
4. `execute_file(patch)` through jisql. On failure re-check for `'Y'` (another host may have won); else delete the claim row and `sys.exit(1)`.
5. On success `update x_db_version_h set active='Y' where version=... and active='N' and updated_by=client_host`.

`hasPendingPatches(marker)` asks whether a `marker` row with `inst_by = <this ranger_version>` and `active='Y'` exists, so the whole loop is skipped on
repeat installs of the same version and re-armed on a version bump.

## Java patches

`java_patch_regex = "^Patch.*?J\d{5}.class$"` scanned in `ews/webapp/WEB-INF/classes/org/apache/ranger/patch`. For each unapplied `J<n>`: claim row, run

```
java -Xmx<ranger_admin_max_heap_size> -Dlogdir=... -Dlogback.configurationFile=file:<conf>/logback.xml -cp <webapp classpath>:<SQL_CONNECTOR_JAR> org.apache.ranger.patch.<Class>
```

then flip to `'Y'`. Non-zero exit deletes the claim and aborts.

## jisql

SQL runs through the vendored `jisql` module (`jisql/lib/*` in the tarball), never a Python DB driver:

```python
jisql_cmd = "%s %s -cp %s:%s/jisql/lib/* org.apache.util.sql.Jisql -driver mysqlconj -cstring jdbc:mysql://%s/%s%s -u '%s' -p '%s' -noheader -trim -c \;" % (...)
```

`execute_query`/`execute_update` append `-query "<sql>"`, `execute_file` appends `-input <file>`. `jisql_log` masks the password. Output parsing is string based.
`is_override_db_connection_string=true` swaps in `db_override_jdbc_connection_string`.

## `dba_script.py`

Runs first, as the DBA user (`run_dba_steps` in `setup.sh`): `create_rangerdb_user`, `create_db`, `create_auditdb_user`, `check_connection`, with a `dryMode`
that prints SQL for a DBA to run manually. Same `BaseDB` + per-flavor subclass structure and the same `log`/`globalDict`/jisql idioms.
