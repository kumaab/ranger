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

# Row filtering and column masking

Access policies answer "may this user read this table?". Two more policy types refine the answer
for SQL-like services:

- A **row filter** policy lets a user query a table but only see the rows that match a filter
  expression, for example `addr_country = 'US'` for US employees.
- A **column mask** (data mask) policy lets a user select a column but returns a transformed value:
  redacted, hashed, nulled, the last four characters only, or a custom expression.

The query engine (HiveServer2, Trino, ...) asks the Ranger plugin for the applicable filter and
masks while it compiles the query and rewrites the query accordingly, so the restriction applies to
every client and every query shape. Users need no separate view per audience, and the underlying
data is never changed.

## Concepts

### Policy types

Row filter and mask policies are `RangerPolicy` documents with `policyType` `2` (row filter) or `1`
(data mask). Instead of `policyItems` they carry `rowFilterPolicyItems` or `dataMaskPolicyItems`.
Each item has the usual `accesses`, `users`, `groups`, `roles` and `conditions`, plus one of:

`rowFilterInfo`
:   Has one field, `filterExpr`: a boolean SQL expression. An empty expression means "no filter".

`dataMaskInfo`
:   Has `dataMaskType` (the mask type name) and `valueExpr` (the expression for a `CUSTOM` mask). The model
    also has a `conditionExpr` field, which the shipped plugins do not use.

```json
"rowFilterInfo": { "filterExpr": "region = 'EU'" }
"dataMaskInfo":  { "dataMaskType": "MASK_HASH" }
```

### Which services support them

A service definition enables the feature by declaring `rowFilterDef` and `dataMaskDef`: the
resources the policy may name, the access types it applies to and the available mask types.
Shipped definitions:

| Service | Row filter resources | Mask resources | Access type |
| --- | --- | --- | --- |
| `hive` | `database`, `table` | `database`, `table`, `column` | `select` |
| `trino`, `presto` | `catalog`, `schema`, `table` | `catalog`, `schema`, `table`, `column` | `select` |
| `nestedstructure` | `schema` | `schema`, `field` | `read` (`write` also for row filters) |

In all of them the resources carry the `singleValue` UI hint: a row filter or mask policy names one
table (and one column for masks) rather than a list, because a filter written for one table rarely
makes sense for another. `hive` and `nestedstructure` also declare these resources with
`wildCard=false`, so their values are matched literally; `trino` and `presto` declare `wildCard=true`.

Because Ranger Admin merges every component's `dataMaskDef` and `rowFilterDef` into the `tag`
service definition, the same policies can be written against tags (for example "mask every column
tagged `PII`"); see [Tag-based policies](tag-based-policies.md).

### Mask types

Mask types are defined per service definition; the Hive set
([`ranger-servicedef-hive.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-hive.json))
is:

| `dataMaskType` | UI label | Effect | Hive transformer |
| --- | --- | --- | --- |
| `MASK` | Redact | Lowercase → `x`, uppercase → `X`, digits → `0` | `mask({col})` |
| `MASK_SHOW_LAST_4` | Partial mask: show last 4 | Show the last 4 characters, mask the rest | `mask_show_last_n({col}, 4, 'x', 'x', 'x', -1, '1')` |
| `MASK_SHOW_FIRST_4` | Partial mask: show first 4 | Show the first 4 characters, mask the rest | `mask_show_first_n({col}, 4, 'x', 'x', 'x', -1, '1')` |
| `MASK_HASH` | Hash | Replace the value with its hash | `mask_hash({col})` |
| `MASK_NULL` | Nullify | Return `NULL` | — |
| `MASK_NONE` | Unmasked (retain original value) | No masking; used to exempt users | — |
| `MASK_DATE_SHOW_YEAR` | Date: show only year | Keep the year, set month and day to `01` | `mask({col}, 'x', 'x', 'x', -1, '1', 1, 0, -1)` |
| `CUSTOM` | Custom | Use the expression in `valueExpr` | the expression itself |

The plugin replaces `{col}` in the transformer with the column name (Trino and Presto definitions
also use `{type}` for the column type, and `nestedstructure` uses `{field}`). A custom expression
must return the same data type as the column; `initcap(reverse({col}))` is valid for a string column.
Trino, Presto and nestedstructure define the same mask type names with transformers written for
their SQL dialect.

### Row filter expressions

`filterExpr` is a boolean expression in the SQL dialect of the service. It is appended to the
query as if the user had written it in a `WHERE` clause, so it can reference columns of the table,
call functions, and use subqueries:

```sql
addr_country in (select e.country from emp.employee e where e.userid = current_user())
```

Expressions can also contain Ranger dynamic expressions delimited by `${{` and `}}`, which the
plugin resolves before handing the filter to the engine. `addr_country = '${{USER.country}}'` uses
a user attribute; `location_state IN (${{GET_UG_ATTR_Q('state')}})` expands to a quoted list built
from the attributes of the user's groups. See
[Policy conditions](policy-conditions.md#script-conditions-and-dynamic-expressions) and
[Attribute-based access control](../abac.md).

## How to configure

### In the Admin UI

Open the service under **Resource Based Policies** and switch to the **Row Level Filter** or
**Masking** tab. A masking policy takes a database, table and column; a row filter policy takes a
database and table. Add one policy item per audience: pick users/groups/roles, the `select`
permission, and either the mask type (with the expression for **Custom**) or the filter text. Use
the arrows to order the items; **the first matching item wins**.

### With the REST API

A masking policy that shows only the last four digits of `cust.customer.phone_num` to everyone
except the `audit` group:

```json title="phone-mask.json"
{
  "service": "dev_hive",
  "name": "customer-phone-mask",
  "policyType": 1,
  "description": "Show only the last 4 digits of phone numbers, except to the audit group",
  "isEnabled": true,
  "isAuditEnabled": true,
  "resources": {
    "database": { "values": ["cust"] },
    "table":    { "values": ["customer"] },
    "column":   { "values": ["phone_num"] }
  },
  "dataMaskPolicyItems": [
    {
      "accesses": [ { "type": "select", "isAllowed": true } ],
      "groups": ["audit"],
      "dataMaskInfo": { "dataMaskType": "MASK_NONE" }
    },
    {
      "accesses": [ { "type": "select", "isAllowed": true } ],
      "groups": ["public"],
      "dataMaskInfo": { "dataMaskType": "MASK_SHOW_LAST_4" }
    }
  ]
}
```

A custom mask that reverses and capitalizes `name_first` would use
`"dataMaskInfo": { "dataMaskType": "CUSTOM", "valueExpr": "initcap(reverse({col}))" }`.

A row filter policy on `cust.customer` that lets `falcon` see everything, gives `us-employees` the
US rows, and restricts everyone else to the country stored in their `country` user attribute:

```json title="customer-filter.json"
{
  "service": "dev_hive",
  "name": "customer-by-country",
  "policyType": 2,
  "description": "Employees see customers of their own country",
  "isEnabled": true,
  "isAuditEnabled": true,
  "resources": {
    "database": { "values": ["cust"] },
    "table":    { "values": ["customer"] }
  },
  "rowFilterPolicyItems": [
    {
      "accesses": [ { "type": "select", "isAllowed": true } ],
      "users": ["falcon"],
      "rowFilterInfo": { "filterExpr": "" }
    },
    {
      "accesses": [ { "type": "select", "isAllowed": true } ],
      "groups": ["us-employees"],
      "rowFilterInfo": { "filterExpr": "addr_country = 'US'" }
    },
    {
      "accesses": [ { "type": "select", "isAllowed": true } ],
      "groups": ["public"],
      "rowFilterInfo": { "filterExpr": "addr_country = '${{USER.country}}'" }
    }
  ]
}
```

```bash
curl -u admin:rangerR0cks! -H 'Content-Type: application/json' \
     -X POST http://localhost:6080/service/public/v2/api/policy -d @phone-mask.json
curl -u admin:rangerR0cks! -H 'Content-Type: application/json' \
     -X POST http://localhost:6080/service/public/v2/api/policy -d @customer-filter.json
```

With these two policies in place, `john` (group `us-employees`) running
`select * from cust.customer` sees only US rows, with `phone_num` values like `xxx-xxx-7890`.

## Evaluation semantics

Masking and filtering are evaluated by the same engine as access policies, but through separate
entry points (`evalDataMaskPolicies` and `evalRowFilterPolicies` on the policy engine) and with
different rules
([`RangerDefaultPolicyEvaluator`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/plugin/policyevaluator/RangerDefaultPolicyEvaluator.java)):

1. **Access first.** The plugin first checks the ordinary access policies. A user without `select`
   permission is denied; masks and filters never grant access.
2. **Resource match.** Only policies whose database/table (and column, for masks) match the
   queried resource are considered; for Hive and nestedstructure the match is literal (`wildCard=false`).
3. **Items in policy order, first match wins.** Unlike allow/deny items, mask and filter items are
   *not* reordered by the engine. The first item whose users/groups/roles, access type and
   conditions match the request supplies the mask or filter. This is why an exemption
   (`MASK_NONE`, or an empty `filterExpr`) must be listed before the broader `public` item.
4. **Across policies**, a decision from a policy of the same or higher priority is final, so two
   policies on the same column should not both match a user; keep one policy per column (or per
   table for filters) and express audiences as ordered items.
5. **Tag-based** mask and filter policies are evaluated before resource-based ones, following the
   same precedence as access policies.
6. **Only one mask per column and one filter per table** is applied. When several columns are
   masked, each column gets its own mask.
7. The audit record for a masked or filtered query carries the id of the mask/filter policy in
   addition to the access policy.

### Hive specifics

- HiveServer2 (Hive 2.1 and later) applies masks and filters by rewriting the query through its
  `HiveAuthorizer.applyRowFilterAndColumnMasking` hook; the Ranger Hive plugin implements it in
  [`RangerHiveAuthorizer`](https://github.com/apache/ranger/blob/master/hive-agent/src/main/java/org/apache/ranger/authorization/hive/authorizer/RangerHiveAuthorizer.java).
- When a row filter or mask applies to a table for the current user, `EXPORT` of that table is
  denied, and `UPDATE`/`ALTER` are denied while
  `xasecure.hive.block.update.if.rowfilter.columnmask.specified` is `true` (the default) in
  `ranger-hive-security.xml`. This prevents users from writing back masked or partial data.
- Masks use the Hive UDFs `mask`, `mask_show_last_n`, `mask_show_first_n` and `mask_hash`
  (HIVE-13568). Masked columns are still readable in `SELECT *`; only the values change.

## Related features

- [Resource-based policies](resource-policies.md) — the base policy model and access evaluation.
- [Tag-based policies](tag-based-policies.md) — apply masks and filters by classification.
- [Policy conditions](policy-conditions.md) — restrict a mask or filter item by IP, time or script.
- [Attribute-based access control](../abac.md) — user and group attributes inside filter expressions.
- Further reading on cwiki: [Row-level filtering and column-masking using Apache Ranger policies in Apache Hive](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=65868896).
