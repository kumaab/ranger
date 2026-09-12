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

# SQL patch skeletons per vendor

All from `078-add-x_audit_config.sql` (commit `2ad565fe6`). Replace `x_audit_config` and `patch_audit_config_global_state` with your names.
Header (identical in every vendor):

```sql
-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
```

## MySQL (`db/mysql/patches/`)

```sql
DROP PROCEDURE IF EXISTS patch_audit_config_global_state;

DELIMITER ;;
CREATE PROCEDURE patch_audit_config_global_state()
BEGIN
    CREATE TABLE IF NOT EXISTS `x_audit_config`(
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `create_time` datetime NULL DEFAULT NULL,
    `update_time` datetime NULL DEFAULT NULL,
    `cfg_name` varchar(255) NOT NULL,
    `cfg_value` LONGTEXT NULL DEFAULT NULL,
    `version` bigint(20) NULL DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `x_audit_config_UK_cfg_name`(`cfg_name`)
    )ROW_FORMAT=DYNAMIC;

    IF NOT EXISTS (SELECT 1 FROM x_audit_config WHERE cfg_name = 'ingestor.url') THEN
        INSERT INTO x_audit_config (create_time, update_time, cfg_name, cfg_value, version)
        VALUES (UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'ingestor.url', '', 1);
    END IF;
END;;

DELIMITER ;
CALL patch_audit_config_global_state();
DROP PROCEDURE IF EXISTS patch_audit_config_global_state;
```

Helper procedures such as `getXportalUIdByLoginId(IN input_val VARCHAR(100), OUT myid BIGINT)` are redefined at the top of the patch with `DELIMITER $$`.

Column add (`069-add-gz_json_x_security_zone.sql`):

```sql
drop procedure if exists add_gz_jsonData_x_security_zone;

delimiter ;;
create procedure add_gz_jsonData_x_security_zone() begin
if not exists (select * from information_schema.columns where table_schema=database() and table_name = 'x_security_zone' and column_name='gz_jsonData') then
        ALTER TABLE x_security_zone ADD gz_jsonData LONGBLOB NULL DEFAULT NULL;
end if;
end;;

delimiter ;
call add_gz_jsonData_x_security_zone();
drop procedure if exists add_gz_jsonData_x_security_zone;
```

## Postgres (`db/postgres/patches/`)

jisql needs the `select 'delimiter start';` / `select 'delimiter end';` markers around multi-statement blocks. DDL uses native `IF NOT EXISTS`; DML goes in a plpgsql function
(`DO $$` is not the convention). `db_setup.py` runs `create_language_plpgsql()` before each patch.

```sql
select 'delimiter start';
CREATE TABLE IF NOT EXISTS x_audit_config (
id BIGINT,
create_time TIMESTAMP DEFAULT NULL NULL,
update_time TIMESTAMP DEFAULT NULL NULL,
cfg_name varchar(255) NOT NULL,
cfg_value TEXT DEFAULT NULL NULL,
version BIGINT DEFAULT NULL NULL,
primary key (id),
CONSTRAINT x_audit_config_UK_cfg_name UNIQUE (cfg_name)
);
CREATE SEQUENCE IF NOT EXISTS x_audit_config_seq;
ALTER SEQUENCE x_audit_config_seq OWNED BY x_audit_config.id;
ALTER TABLE x_audit_config ALTER COLUMN id SET DEFAULT nextval('x_audit_config_seq'::regclass);

CREATE OR REPLACE FUNCTION patch_audit_config_global_state()
RETURNS void AS $$
DECLARE
    v_cnt bigint;
BEGIN
    SELECT count(*) INTO v_cnt FROM x_audit_config WHERE cfg_name = 'ingestor.url';
    IF v_cnt = 0 THEN
        INSERT INTO x_audit_config (create_time, update_time, cfg_name, cfg_value, version)
        VALUES (current_timestamp, current_timestamp, 'ingestor.url', '', 1);
    END IF;
END;
$$ LANGUAGE plpgsql;
select 'delimiter end';

select patch_audit_config_global_state();
select 'delimiter end';
```

Column guard: `select count(*) into v_column_exists from pg_attribute where attrelid in (select oid from pg_class where relname='x_security_zone') and attname='gz_jsonData';`

## Oracle (`db/oracle/patches/`)

One anonymous block, uppercase names in the `user_tables`/`user_sequences` guards, DDL and any query against the new table through `EXECUTE IMMEDIATE`,
explicit `COMMIT;`, file ends with `END;/` (slash on the same line, no trailing newline). Oracle treats `''` as NULL, so NOT NULL string columns get `' '`.

```sql
DECLARE
    v_table_count NUMBER := 0;
    v_seq_count NUMBER := 0;
    v_cfg_count NUMBER := 0;
BEGIN
    SELECT count(*) INTO v_table_count FROM user_tables WHERE table_name = 'X_AUDIT_CONFIG';
    IF (v_table_count = 0) THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE x_audit_config (
                id NUMBER(20) NOT NULL,
                create_time DATE DEFAULT NULL NULL,
                update_time DATE DEFAULT NULL NULL,
                cfg_name varchar(255) NOT NULL,
                cfg_value CLOB DEFAULT NULL NULL,
                version NUMBER(20) DEFAULT NULL NULL,
                PRIMARY KEY (id),
                CONSTRAINT x_audit_config_UK_cfg_name UNIQUE (cfg_name)
            )';
        SELECT count(*) INTO v_seq_count FROM user_sequences WHERE sequence_name = 'X_AUDIT_CONFIG_SEQ';
        IF (v_seq_count = 0) THEN
            EXECUTE IMMEDIATE 'CREATE SEQUENCE X_AUDIT_CONFIG_SEQ START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE';
        END IF;
    END IF;

    EXECUTE IMMEDIATE 'SELECT count(*) FROM x_audit_config WHERE cfg_name = :1' INTO v_cfg_count USING 'ingestor.url';
    IF (v_cfg_count = 0) THEN
        EXECUTE IMMEDIATE 'INSERT INTO x_audit_config (id, create_time, update_time, cfg_name, cfg_value, version) VALUES (X_AUDIT_CONFIG_SEQ.nextval, sys_extract_utc(systimestamp), sys_extract_utc(systimestamp), :1, :2, 1)' USING 'ingestor.url', '';
    END IF;
    COMMIT;
END;/
```

## SQL Server (`db/sqlserver/patches/`)

```sql
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
IF NOT EXISTS (SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'x_audit_config')
BEGIN
    CREATE TABLE [dbo].[x_audit_config](
        [id] [bigint] IDENTITY(1,1) NOT NULL,
        [create_time] [datetime2] DEFAULT NULL NULL,
        [update_time] [datetime2] DEFAULT NULL NULL,
        [cfg_name] [varchar](255) NOT NULL,
        [cfg_value] NVARCHAR(MAX) DEFAULT NULL NULL,
        [version] [bigint] DEFAULT NULL NULL,
        PRIMARY KEY CLUSTERED ([id] ASC),
        CONSTRAINT [x_audit_config$x_audit_config_UK_cfg_name] UNIQUE NONCLUSTERED ([cfg_name] ASC)
    ) ON [PRIMARY];
END;
GO
IF NOT EXISTS (SELECT 1 FROM [dbo].[x_audit_config] WHERE cfg_name = 'ingestor.url')
BEGIN
    INSERT INTO [dbo].[x_audit_config] (create_time, update_time, cfg_name, cfg_value, version)
    VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, N'ingestor.url', N'', 1);
END;
GO
EXIT
```

Functions are guarded with `IF EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'dbo.fn') AND type IN (N'FN', N'IF', N'TF', N'FS', N'FT')) DROP FUNCTION dbo.fn`.
Constraint names carry the `<table>$` prefix; this is the only vendor that does so.

## SQL Anywhere (`db/sqlanywhere/patches/`)

Watcom SQL (`IF ... THEN ... END IF;`), `dbo.` schema prefix, explicit named PK, `GO` then `EXIT`. No docker image exists; review by hand.

```sql
BEGIN
    IF NOT EXISTS(SELECT * FROM SYS.SYSTABLE WHERE table_name = 'x_audit_config') THEN
        CREATE TABLE dbo.x_audit_config(
            id bigint IDENTITY NOT NULL,
            create_time datetime DEFAULT NULL NULL,
            update_time datetime DEFAULT NULL NULL,
            cfg_name varchar(255) NOT NULL,
            cfg_value LONG VARCHAR DEFAULT NULL NULL,
            version bigint DEFAULT NULL NULL,
            CONSTRAINT x_audit_config_PK_id PRIMARY KEY CLUSTERED(id),
            CONSTRAINT x_audit_config_UK_cfg_name UNIQUE NONCLUSTERED(cfg_name)
        );
    END IF;
    IF NOT EXISTS(SELECT 1 FROM dbo.x_audit_config WHERE cfg_name = 'ingestor.url') THEN
        INSERT INTO dbo.x_audit_config (create_time, update_time, cfg_name, cfg_value, version)
        VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ingestor.url', '', 1);
    END IF;
END
GO
EXIT
```

## Optimized schema edits (`optimized/current/ranger_core_db_<vendor>.sql`)

1. Drop-list near the top, FK children first:
   - mysql `DROP TABLE IF EXISTS \`x_audit_config\`;`
   - postgres `DROP TABLE IF EXISTS x_audit_config CASCADE;` and `DROP SEQUENCE IF EXISTS x_audit_config_seq;`
   - oracle `call spdropsequence('X_AUDIT_CONFIG_SEQ');` (tables are dropped via the existing helper loop)
   - sqlserver `IF (OBJECT_ID('x_audit_config') IS NOT NULL) BEGIN DROP TABLE [dbo].[x_audit_config] END`
   - sqlanywhere `call dbo.removeForeignKeysAndTable('x_audit_config')`
2. `CREATE SEQUENCE` (oracle block of sequences; postgres immediately above the table).
3. Unguarded `CREATE TABLE` (oracle followed by `commit;`, sqlserver/sqlanywhere by `GO`). Postgres uses inline `id BIGINT DEFAULT nextval('x_audit_config_seq'::regclass)`.
4. Seed rows (mysql inside `insertRangerPrerequisiteEntries()`), then the version row after `'077'`:

```sql
INSERT INTO x_db_version_h (version,inst_at,inst_by,updated_at,updated_by,active) VALUES ('078',UTC_TIMESTAMP(),'Ranger 3.0.0',UTC_TIMESTAMP(),'localhost','Y');                                        -- mysql
INSERT INTO x_db_version_h (version,inst_at,inst_by,updated_at,updated_by,active) VALUES ('078',current_timestamp,'Ranger 3.0.0',current_timestamp,'localhost','Y');                                  -- postgres
INSERT INTO x_db_version_h (id,version,inst_at,inst_by,updated_at,updated_by,active) VALUES (X_DB_VERSION_H_SEQ.nextval,'078',sys_extract_utc(systimestamp),'Ranger 3.0.0',sys_extract_utc(systimestamp),'localhost','Y'); -- oracle
INSERT INTO x_db_version_h (version,inst_at,inst_by,updated_at,updated_by,active) VALUES ('078',CURRENT_TIMESTAMP,'Ranger 3.0.0',CURRENT_TIMESTAMP,'localhost','Y');                                  -- sqlserver, sqlanywhere (+ GO)
```

`inst_by` is the release the patch ships in. After import, `db_setup.py` rewrites `inst_by` of every `updated_by='localhost'` row to the installed version.
