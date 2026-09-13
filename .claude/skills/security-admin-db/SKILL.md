---
name: security-admin-db
description: Conventions for Ranger Admin database schema and installer scripting - numbered SQL patches mirrored across mysql/oracle/postgres/sqlserver/sqlanywhere plus the optimized fresh-install schema, x_db_version_h bookkeeping, db_setup.py/dba_script.py/setup.sh flow, install.properties to ranger-admin-site.xml mapping, Python and shell style. Use when adding a table/column/index, writing a patch under security-admin/db, or touching security-admin/scripts.
---
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

# Ranger Admin DB schema and installer

KMS has a separate schema and its own `kms/scripts/db_setup.py` (see `ranger-kms`). Admin schema lives in `security-admin/db/<vendor>/` for `mysql`, `oracle`, `postgres`, `sqlserver`, `sqlanywhere`. Installer scripts in `security-admin/scripts/`.
JPA/Java side of a table is covered by the `security-admin` skill. Style and license headers: `ranger-conventions`.

## The invariant: 2 files x 5 vendors

A schema change is one **patch** plus one **optimized schema edit** per vendor:

| File | Role |
|---|---|
| `db/<vendor>/patches/NNN-<slug>.sql` | Upgrade path. Idempotent, vendor-idiomatic guards. Same `NNN` and slug in all five. |
| `db/<vendor>/optimized/current/ranger_core_db_<vendor>.sql` | Fresh-install path. Unguarded DDL + seed rows + a `x_db_version_h` row for `NNN`. |

Latest patch is `078` (RANGER-5720, commit `2ad565fe6`): the canonical template. Numbers are zero-padded 3 digits; `db_setup.py` sorts filenames lexicographically.
`patches/audit/` targets the separate audit DB and is not applied by `db_setup.py`.

## Non-negotiables

- Patches **never** insert into `x_db_version_h`. `db_setup.py` claims the row (`active='N'`), runs the file, then flips it to `'Y'`. Only the optimized
  schema carries `INSERT INTO x_db_version_h (...) VALUES ('NNN', ..., 'Ranger 3.0.0', ..., 'localhost', 'Y');`, appended after `'NNN-1'` and before `'DB_PATCHES'`.
- ASF header as `--` comments at the top of every `.sql` file, all vendors.
- Idempotency guard per vendor (`information_schema` / `pg_*` / `user_tables` / `sys.objects` / `SYS.SYSTABLE`). Re-running a patch must be a no-op.
- Naming: tables `x_<snake>`, PK `id`, UK `<table>_UK_<col>`, FK `<table>_FK_<col>`, index `<table>_IDX_<col>`, Oracle sequence `<TABLE>_SEQ` (upper),
  Postgres sequence `<table>_seq`. SQL Server constraints are `[<table>$<constraint>]`. Oracle names must fit 30 chars (abbreviate like `x_rngr_glbl_state_FK_updbyid`).
- Standard audit columns `create_time`, `update_time`, `added_by_id`, `upd_by_id` (FK to `x_portal_user`) only when provenance is needed; patch 077 removed them from ref tables.
- Optimized schema drop-list at the top must drop FK children before parents (and drop sequences on oracle/postgres).
- A unique constraint that should yield a friendly UI error gets a line in `security-admin/src/main/resources/db_message_bundle.properties` (`<table>_UK_<col>=Message`).
- No assembly change is needed; `distro/src/main/assembly/admin-web.xml` copies `security-admin/db` and `security-admin/scripts` wholesale.

## Type and terminator matrix

| | MySQL | Postgres | Oracle | SQL Server | SQL Anywhere |
|---|---|---|---|---|---|
| id | `bigint(20) AUTO_INCREMENT` | `BIGINT` + seq + `SET DEFAULT nextval` | `NUMBER(20)` + `<T>_SEQ` | `[bigint] IDENTITY(1,1)` | `bigint IDENTITY` |
| timestamp | `datetime` | `TIMESTAMP` | `DATE` | `[datetime2]` | `datetime` |
| large text | `LONGTEXT` | `TEXT` | `CLOB` | `NVARCHAR(MAX)` | `LONG VARCHAR` |
| now | `UTC_TIMESTAMP()` | `current_timestamp` | `sys_extract_utc(systimestamp)` | `CURRENT_TIMESTAMP` | `CURRENT_TIMESTAMP` |
| block style | `DELIMITER ;;` + procedure, `CALL`, `DROP PROCEDURE` | `select 'delimiter start';` ... `CREATE OR REPLACE FUNCTION ... $$ LANGUAGE plpgsql;` `select fn();` `select 'delimiter end';` | `DECLARE ... BEGIN ... EXECUTE IMMEDIATE ... COMMIT; END;/` | `GO`-separated batches, ends `EXIT` | `BEGIN ... END` `GO` `EXIT` |

Full per-vendor templates with guards: [references/sql-patch-by-vendor.md](references/sql-patch-by-vendor.md).

## Checklist: new table

1. Five `patches/NNN-<slug>.sql` files (copy patch 078 and adapt).
2. Five `optimized/current/ranger_core_db_<vendor>.sql` edits: drop-list, sequence (oracle/postgres), `CREATE TABLE`, seeds, version row.
3. Java: entity, `persistence.xml`, named queries, DAO, `RangerDaoManagerBase` (see `security-admin`).
4. Optional Java patch `Patch*_J<n>` for data back-fill (see `security-admin` `references/java-patches.md`).
5. Verify fresh install and 077->078 upgrade on mysql/postgres/oracle/sqlserver with `dev-support/ranger-docker`; SQL Anywhere by review only.
   [references/verify-with-docker.md](references/verify-with-docker.md).

## Installer scripts

`setup.sh` -> `dba_script.py` (as DBA: create db/user) -> `update_properties` (install.properties to `ranger-admin-site.xml` via `update_property.py`) ->
`db_setup.py` (schema + SQL patches + pre/post Java hooks) -> `db_setup.py -javapatch` -> `db_setup.py -changepassword`.

- `update_property.py` only rewrites existing `<name>` entries. A new tunable needs a `<property>` stanza in `conf.dist/ranger-admin-site.xml` **and** an `if` block in `setup.sh update_properties()`.
- New optional install.properties keys use `get_prop_or_default`, not `get_prop` (which exits when the key is missing).
- Python 3, tab-indented in `db_setup.py`/`dba_script.py`, `log("[I] ...", "info")` helper, `globalDict`, SQL via jisql command lines, no `os.system`.
- Shell: `#!/bin/bash`, no `set -e`, explicit `check_ret_status $? "msg"`, `log()` helper, `$PWD`-relative paths.
Details: [references/installer-scripts.md](references/installer-scripts.md), [references/db-setup-flow.md](references/db-setup-flow.md).

## References (load on demand)

- [references/schema-map.md](references/schema-map.md): the 85 core tables by group, their entity classes, `policy_text` vs ref tables, version/change-log tables, vendor caveats.

- [references/sql-patch-by-vendor.md](references/sql-patch-by-vendor.md): complete patch skeletons for all five vendors, column-add idiom, optimized-schema edits.
- [references/db-setup-flow.md](references/db-setup-flow.md): `db_setup.py` flow, `x_db_version_h` semantics and locking, `BaseDB` subclasses, Java patch execution.
- [references/installer-scripts.md](references/installer-scripts.md): `setup.sh`, `install.properties` naming, `update_property.py`, `conf.dist/` files, Python/shell conventions.
- [references/verify-with-docker.md](references/verify-with-docker.md): local verification with `dev-support/ranger-docker`.
