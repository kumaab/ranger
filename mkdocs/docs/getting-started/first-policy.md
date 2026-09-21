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

# Your first policy

In this tutorial you create a Ranger policy for Hive, run queries as a user who is first denied and
then allowed, and read the resulting audit records. You then add a deny rule and a row filter to see
two more kinds of policy in action. Everything runs in the development setup from
[Run Ranger with Docker](docker.md#build-from-source), which starts Hive with the Ranger Hive plugin
already enabled and a Ranger service named `dev_hive` already created.

Allow about 20 minutes once the containers are up. No Hive or Ranger experience is needed.

!!! note

    This tutorial cannot be followed with the Docker Hub images alone: only Ranger Admin, its database
    and Solr are published there, and no Hadoop or Hive image with the Ranger plugin. The Hive container
    comes from `docker-compose.ranger-hadoop.yml` and `docker-compose.ranger-hive.yml` in
    `dev-support/ranger-docker`, which are built from a source build of Ranger.

## Before you start

1. Clone the repository and build Ranger into `dev-support/ranger-docker/dist`, as described in
   [Prepare the development setup](docker.md#build-from-source).
2. Download the archives that the Hadoop, Hive and Kafka images are built from, and start Ranger
   Admin, the audit server, Hadoop and Hive:

    ```bash
    cd dev-support/ranger-docker
    chmod +x download-archives.sh
    ./download-archives.sh hadoop hive kafka

    export RANGER_DB_TYPE=postgres
    export AUDIT_INDEX_STORE=opensearch
    export AUDIT_DESTINATIONS=audit-store-${AUDIT_INDEX_STORE}

    docker compose --profile ${AUDIT_DESTINATIONS} -f docker-compose.ranger.yml -f docker-compose.ranger-audit-service.yml -f docker-compose.ranger-hadoop.yml -f docker-compose.ranger-hive.yml up -d
    ```

    Kafka is needed because the audit server (not yet part of a Ranger release) uses it to carry
    audits from the ingestor to the dispatcher that indexes them in OpenSearch.

Wait until `docker logs ranger-hive` reports `HiveServer2 is ready and listening on port 10000`. The
environment you now have:

- **Ranger Admin**: <http://localhost:6080>, user `admin`, password `rangerR0cks!`.
- **Hive service in Ranger**: `dev_hive`, created by `scripts/admin/create-ranger-services.py`.
- **HiveServer2**: the `ranger-hive` container, port 10000, Kerberos authentication,
  `hive.server2.enable.doAs=false`.
- **Kerberos realm**: `EXAMPLE.COM`; keytabs are in `/etc/keytabs` inside each container.
- **Test identities**: `hive/ranger-hive.rangernw` (the Hive service user) and
  `testuser1/ranger-hive.rangernw` (an ordinary user).

Because HiveServer2 authenticates with Kerberos and `doAs` is off, the user Ranger sees is the short
name of the Kerberos principal that connected: `hive` or `testuser1`.

## Step 1: log in and look at the Hive service

1. Open <http://localhost:6080> and log in as `admin` / `rangerR0cks!`.
2. The landing page is **Service Manager**. Under **Hadoop SQL** (the Hive service type) you see
   `dev_hive`. Click it.
3. The policy list shows the default policies that Ranger created with the service:
   `all - database`, `all - database, table`, `all - database, table, column`, `all - database, udf`,
   `all - url`, `all - hiveservice` and `all - global`. They grant every access type to the user
   `hive`, which is why HiveServer2 itself can work, and to `{OWNER}`, the owner of a database or
   table. Two more default policies give the group `public` **create** in the `default` database and
   **select** on `information_schema`. No policy lets an ordinary user read a table owned by someone
   else.

## Step 2: create a table as the Hive service user

Open a shell in the Hive container, get a ticket for the `hive` principal and start Beeline:

```bash
docker exec -it ranger-hive bash

kinit -kt /etc/keytabs/hive.keytab hive/ranger-hive.rangernw@EXAMPLE.COM
beeline -u "jdbc:hive2://localhost:10000/default;principal=hive/ranger-hive.rangernw@EXAMPLE.COM"
```

In Beeline:

```sql
CREATE TABLE employees (id INT, name STRING, country STRING, salary INT);
INSERT INTO employees VALUES
  (1, 'Ana',   'US', 120000),
  (2, 'Bob',   'US',  95000),
  (3, 'Chen',  'CN', 105000),
  (4, 'Dana',  'DE',  99000);
SELECT * FROM employees;
!quit
```

(The `INSERT` runs as a Tez job on YARN and may take a minute the first time.)

## Step 3: try the query as an ordinary user

Still inside the container, switch to `testuser1` and run the same `SELECT`:

```bash
kdestroy
kinit -kt /etc/keytabs/testuser1.keytab testuser1/ranger-hive.rangernw@EXAMPLE.COM
beeline -u "jdbc:hive2://localhost:10000/default;principal=hive/ranger-hive.rangernw@EXAMPLE.COM" \
  -e "SELECT * FROM employees"
```

The query fails with a `HiveAccessControlException`:

```text
Permission denied: user [testuser1] does not have [SELECT] privilege on [default/employees/*]
```

This is the Ranger Hive plugin rejecting the request because no policy allows it.

## Step 4: create the policy

Ranger needs to know the user before you can put it in a policy. In Ranger Admin open
**Settings → Users/Groups/Roles**; if `testuser1` is not listed, click **Add New User**, enter
`testuser1` as the user name, fill in the required fields, keep the role **User** and save. (In your
own deployments UserSync creates these entries for you.)

Now create the policy:

1. Go back to **Service Manager → dev_hive** and click **Add New Policy**.
2. Fill in:
    - **Policy Name**: `employees - read`
    - **database**: `default`
    - **table**: `employees`
    - **column**: `*`
3. Under **Allow Conditions**, add `testuser1` in **Select User**, then choose the permission
   **select**.
4. Click **Add**.

The policy appears in the list with **Audit Logging** on. The plugin in HiveServer2 polls Ranger Admin
for changes every 30 seconds (`ranger.plugin.hive.policy.pollIntervalMs`), so wait up to half a minute
before the next step.

??? example "The same policy through the REST API"

    ```bash
    curl -u admin:rangerR0cks! -H 'Content-Type: application/json' \
      -X POST http://localhost:6080/service/public/v2/api/policy -d '{
        "service": "dev_hive",
        "name": "employees - read",
        "resources": {
          "database": { "values": ["default"] },
          "table":    { "values": ["employees"] },
          "column":   { "values": ["*"] }
        },
        "policyItems": [
          { "users": ["testuser1"], "accesses": [ { "type": "select", "isAllowed": true } ] }
        ]
      }'
    ```

## Step 5: run the query again

```bash
beeline -u "jdbc:hive2://localhost:10000/default;principal=hive/ranger-hive.rangernw@EXAMPLE.COM" \
  -e "SELECT name, country FROM employees"
```

The rows come back. Anything the policy does not cover is still refused; for example
`INSERT INTO employees VALUES (5, 'Eve', 'US', 1)` fails because `testuser1` only has `select`.

## Step 6: see the audit entries

In Ranger Admin open **Audit → Access**. Audits from the plugin travel through the audit ingestor and
Kafka into OpenSearch, so allow a few seconds and refresh. Filter on **User** = `testuser1`. You see one
row per access check:

| Column | Denied query (step 3) | Allowed query (step 5) |
|---|---|---|
| Result | Denied | Allowed |
| Policy ID | — | The id of `employees - read`; click it to open the policy |
| Resource name | `default/employees/<column>`, the first column that was denied | `default/employees/name,country` |
| Access type | `SELECT` | `SELECT` |
| Service name | `dev_hive` | `dev_hive` |

Each row also carries the client IP and the event time.
Click the row to see the full event, including the Hive query text. **Audit → Admin** shows the
administrative side: your login and the creation of the policy.

## Step 7: add a deny rule

Suppose `testuser1` may read everything about employees except salaries. Deny rules are evaluated
before allow rules, so a deny on the `salary` column overrides the allow on `*` from the first policy.

1. In `dev_hive` click **Add New Policy** again and fill in:
    - **Policy Name**: `employees - no salary`
    - **database**: `default`, **table**: `employees`, **column**: `salary`
2. Leave **Allow Conditions** empty. Under **Deny Conditions** add `testuser1` in **Select User** and
   the permission **select**.
3. Click **Add**, wait for the poll interval, then run:

```bash
beeline -u "jdbc:hive2://localhost:10000/default;principal=hive/ranger-hive.rangernw@EXAMPLE.COM" \
  -e "SELECT name FROM employees"          # allowed
beeline -u "jdbc:hive2://localhost:10000/default;principal=hive/ranger-hive.rangernw@EXAMPLE.COM" \
  -e "SELECT name, salary FROM employees"  # denied
```

The second query is denied and **Audit → Access** shows the id of `employees - no salary` as the
policy that made the decision. The evaluation order (deny, deny exceptions, allow, allow exceptions)
is explained in [Policy model](../arch/policy-model.md). Instead of denying the column you could also
mask it: a policy on the **Masking** tab for `default.employees.salary` with a mask type such as
*Redact* or *Hash* returns masked values to `testuser1` while other users see the real data. See
[Row filter and column masking](../features/policies/row-filter-column-masking.md).

## Step 8: add a row filter

Row filters let a user query a table but only see the rows that match a predicate. Restrict
`testuser1` to US employees:

1. In `dev_hive` switch to the **Row Level Filter** tab and click **Add New Policy**.
2. Fill in **Policy Name** `employees - us only`, **database** `default`, **table** `employees`
   (row-filter policies take exactly one database and one table, no wildcards).
3. Under **Row Filter Rules** add `testuser1`, permission **select**, and the filter expression
   `country = 'US'`.
4. Save, wait for the poll interval, then run:

```bash
beeline -u "jdbc:hive2://localhost:10000/default;principal=hive/ranger-hive.rangernw@EXAMPLE.COM" \
  -e "SELECT name, country FROM employees"
```

Only Ana and Bob are returned. The filter is applied by HiveServer2 as a rewrite of the query, so it
also applies to joins and aggregates over the table.

## What you have seen

- Enforcement happens inside HiveServer2, using policies the plugin pulled from Ranger Admin and
  cached locally (`/etc/ranger/dev_hive/policycache` in the container).
- Access is closed by default: a user without a matching allow policy is denied.
- Deny rules win over allow rules; masking and row filters change what a query returns rather than
  whether it runs.
- Every decision is audited with the policy that made it.

## Next steps

- [Resource-based policies](../features/policies/resource-policies.md): wildcards, groups and roles,
  validity periods, policy priority and delegated administration.
- [Tag-based policies](../features/policies/tag-based-policies.md): one policy for every resource
  tagged `PII`.
- [Hive plugin](../plugins/hive.md): how Hive commands map to Ranger permissions.
- [Admin UI guide](../services/admin/ui-guide.md): a tour of every screen used above.
- [REST API](../dev/rest-api.md) and the [Python client](../features/client-interface/python.md) for
  automating what you did by hand.
