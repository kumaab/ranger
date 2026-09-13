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

# Verifying schema changes with `dev-support/ranger-docker`

No automated test applies SQL patches. Verify by hand with the docker stack. `docker-compose.ranger-db.yml` provides `postgres`, `mysql` (MariaDB), `oracle`,
`sqlserver` on host `ranger-db.rangernw`. There is no SQL Anywhere image.

## Build and start

```bash
cd dev-support/ranger-docker
./download-archives.sh hadoop hive hbase kafka knox ozone      # once
cd ../.. && mvn clean package -DskipTests
cp target/ranger-* dev-support/ranger-docker/dist/ && cp target/version dev-support/ranger-docker/dist/
cd dev-support/ranger-docker
export RANGER_DB_TYPE=postgres                                  # mysql | postgres | oracle | sqlserver
export AUDIT_INDEX_STORE=opensearch
export AUDIT_DESTINATIONS=audit-store-${AUDIT_INDEX_STORE}
docker compose --profile ${AUDIT_DESTINATIONS} -f docker-compose.ranger.yml -f docker-compose.ranger-audit-service.yml up -d
```

Published ports: ranger 6080, kdc 88 (+udp) / 749, zk 2181, postgres 5432, mysql 3306, oracle 1521, sqlserver 1433, solr 8983, opensearch 9200/9300,
audit-ingestor 7081/7182, dispatcher-solr 7091, dispatcher-hdfs 7092, dispatcher-opensearch 7093. Containers: `ranger`, `ranger-kdc`, `ranger-zk`,
`ranger-postgres|mysql|oracle|sqlserver` (all reachable as `ranger-db.rangernw`, network `rangernw`). Compose `--profile` values are audit stores only
(`audit-store-solr|opensearch|hdfs`); everything else is chosen with `-f <file>`. `.env` holds image/version pins (`RANGER_BASE_VERSION`, component versions),
`*_MAX_HEAP=256m`, `KERBEROS_ENABLED=true`, `KERBEROS_REALM=EXAMPLE.COM`, `DEBUG_*=false`; `RANGER_DB_TYPE` is exported by `ranger_in_docker` (default `postgres`), not in `.env`.

`Dockerfile.ranger` picks the JDBC driver via `ARG RANGER_DB_TYPE`. The entrypoint `scripts/admin/ranger.sh` runs `setup.sh` on first start (guarded by
`${RANGER_HOME}/.setupDone`), which runs `db_setup.py` and `db_setup.py -javapatch`. Per-flavor properties: `scripts/admin/ranger-admin-install-<db>.properties`.

## Two scenarios

1. **Fresh install**: exercises `optimized/current/ranger_core_db_<vendor>.sql` only (patches are skipped because the optimized script already inserts the
   `DB_PATCHES`/`JAVA_PATCHES` rows). Confirms the mirror is correct.
2. **Upgrade**: start the stack at the parent commit, keep the DB volume (`docker-compose.ranger-db-mounted.yml`, postgres today), rebuild the admin image at HEAD,
   remove `.setupDone` in the admin container (or recreate it), restart. Confirms the patch itself.

Manual re-run inside the admin container:

```bash
docker exec -it ranger bash -lc 'cd /opt/ranger/admin && python3 db_setup.py && python3 db_setup.py -javapatch'
```

Assert:

```sql
select version, inst_by, updated_by, active from x_db_version_h where version in ('078','DB_PATCHES','JAVA_PATCHES');
```

Expect `078 | Ranger 3.0.0 | <host> | Y`. Run `db_setup.py` a second time: it must log that the patch is already applied and change nothing.
That second run is the real idempotency test.

CI (`.github/workflows/upgrade-ranger.yaml`, manual dispatch) runs upgrades from 2.6.0/2.7.0 on postgres/mysql/oracle; use it for release-level checks.
