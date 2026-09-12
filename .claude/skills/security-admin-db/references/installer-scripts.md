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

# Installer scripts: `setup.sh`, `install.properties`, Python and shell conventions

## `setup.sh` sequence (end of file)

```
init_variables -> get_distro -> check_java_version -> check_db_connector -> setup_unix_user_group -> setup_install_files ->
sanity_check_files -> copy_db_connector -> check_python_command -> check_ranger_version (validateDefaultUsersPassword on new install) ->
run_dba_steps (dba_script.py) -> update_properties -> do_authentication_setup (setup_authentication.sh) ->
db_setup.py -> db_setup.py -javapatch -> change_default_users_password (db_setup.py -changepassword)
```

## Reading properties

```bash
PROPFILE=${RANGER_ADMIN_CONF:-$PWD}/install.properties

get_prop(){            # exits 1 when the key is missing
	validateProperty=$(sed '/^\#/d' $2 | grep "^$1\s*="  | tail -n 1)
	if  test -z "$validateProperty" ; then log "[E] '$1' not found in $2 file while getting....!!"; exit 1; fi
	...
}
get_prop_or_default(){ ... }   # 3rd arg is the default; use this for new optional keys
```

## install.properties -> XML

`update_properties()` maps each key explicitly; there is no automatic name transform:

```bash
if [ "${spnego_principal}" != "" ]
then
        propertyName=ranger.spnego.kerberos.principal
        newPropertyValue="${spnego_principal}"
        updatePropertyToFilePy $propertyName "${newPropertyValue}" $to_file_ranger
fi
```

Targets: `to_file_ranger=$app_home/WEB-INF/classes/conf/ranger-admin-site.xml`, `to_file_default=.../ranger-admin-default-site.xml`.
`update_property.py` (52 lines) parses the XML and rewrites `<value>` of a matching `<name>`; **if the name is absent it does nothing and returns 0**.
So a new setting needs three edits: `install.properties` key, `setup.sh` block, `<property>` stanza in `security-admin/src/main/resources/conf.dist/ranger-admin-site.xml`.

Key naming in `install.properties` is mixed by era: `UPPER_SNAKE` for installer/environment knobs (`DB_FLAVOR`, `SQL_CONNECTOR_JAR`, `PATCH_RETRY_INTERVAL`),
`lower_snake` for functional settings (`db_host`, `audit_store`, `policymgr_external_url`), legacy camelCase (`rangerAdmin_password`, `xa_ldap_userDNpattern`).
New keys: `lower_snake`. XML names are dotted `ranger.<area>.<setting>`.

`core-site.xml` is symlinked from `${hadoop_conf}` or written blank. Env files written to `conf/`: `java_home.sh`, `ranger-admin-env-piddir.sh`,
`ranger-admin-env-dbsslparam.sh`, `ranger-admin-env-hadoopconfdir.sh`.

## `conf.dist/`

| File | Role |
|---|---|
| `ranger-admin-site.xml` | operator-overridable config, rewritten by the installer |
| `ranger-admin-default-site.xml` | Ranger-owned defaults, lower precedence |
| `security-applicationContext.xml` | Spring Security chain, rewritten by `setup_authentication.sh` |
| `logback.xml` | logging; `DEBUG_ADMIN=true` flips root level via `xmlstarlet` |

`security-admin/scripts/ranger-admin-site-template.xml` is the empty skeleton used by `upgrade_admin.py -g` for legacy (0.4.x) migrations.

## Python conventions (`db_setup.py`, `dba_script.py`, `update_property.py`)

- Python 3 (`PYTHON_COMMAND_INVOKER=python3`), no shebang on `db_setup.py`/`dba_script.py`, no py2 shims.
- Tabs for indentation in `db_setup.py` and `dba_script.py`; match the file you edit.
- `#`-comment ASF header at top.
- Logging via the module-level helper, always tagged:
  ```python
  def log(msg,type):
  	if type == 'info': logging.info(" %s",msg)
  	...
  log("[I] Patch applied","info"); log("[E] failed","error"); log("[W] ...","warning")
  ```
- `globalDict` filled by `populate_global_dict()`; required keys `globalDict['db_host']`, optional `if 'x' in globalDict:`.
- `os_name = platform.system().upper()`; `is_unix = os_name == "LINUX" or os_name == "DARWIN"`; branch `if is_unix ... elif os_name == "WINDOWS" ... else raise`.
- Subprocess only: `check_output(cmd)` (Popen + `.decode()`), `subprocess.call(shlex.split(cmd))`, `subprocessCallWithRetry(cmd)` (3 attempts).
- Exit with `sys.exit(1)` after an error log.
- File names `snake_case.py` for installer scripts.

The Python client under `intg/` has different rules; see `.cursor/rules/ranger-python.mdc`.

## Shell conventions (`security-admin/scripts/*.sh`, `agents-common/scripts/enable-agent.sh`)

- `#!/bin/bash` then the `#` ASF header.
- No `set -e`. Check explicitly: `check_ret_status $? "message"` or `if [ "$?" != "0" ]; then ... exit 1; fi`.
- `log()` writes a timestamped line to `$LOGFILE` and stdout; message prefixes `[I]`, `[W]`, `[E]`.
- Functions `name(){ ... }`; lifecycle steps `snake_case`, helpers `camelCase` (existing mix). Root check `if [ ! -w /etc/passwd ]`.
- Admin scripts resolve paths from `$PWD`; `set_globals.sh` does `cd \`dirname $0\`` first.
- Plugin side: `agents-common/scripts/enable-agent.sh` is the single implementation copied as `enable-<svc>-plugin.sh` / `disable-<svc>-plugin.sh` by the distro assembly;
  it reads properties with `getInstallProperty` over `COMPONENT_INSTALL_ARGS` then `INSTALL_ARGS`.
