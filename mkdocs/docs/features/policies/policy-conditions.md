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

# Policy conditions

A policy normally matches on *who* (users, groups, roles), *what* (resources) and *which operation*
(access types). Policy conditions add a fourth dimension: *under which circumstances*. The client's IP
address, the time of day, the cluster the request comes from, the tags on the resource, the
attributes of the user, or an arbitrary script can all decide whether a policy or a policy item
applies.

Conditions are pluggable. A service definition declares which conditions its policies may use and
which Java class evaluates each one; Ranger ships a set of evaluators in `agents-common`, and you can
add your own (see [Custom conditions and enrichers](../../dev/custom-conditions-enrichers.md)).
Context enrichers, the companion mechanism, add data to a request before evaluation so that
conditions have something to look at: the tag enricher adds tags, the geo-location enricher adds the
country and city of the client IP.

## Concepts

### Where conditions appear in a policy

A condition instance is `{ "type": "<condition name>", "values": [ "..." ] }`. It can be attached in
two places of a [policy](resource-policies.md):

- **Policy level** (`conditions`): the whole policy is skipped unless every condition passes.
- **Policy item level** (`conditions` inside an entry of `policyItems`, `denyPolicyItems`,
  `allowExceptions`, `denyExceptions`, `dataMaskPolicyItems` or `rowFilterPolicyItems`): the item
  matches only if all of its conditions pass.

`type` must be the `name` of a condition declared in the service definition. `values` is a list of
strings whose meaning depends on the evaluator (IP ranges, time ranges, tag names, a script, ...).

### How a condition is declared

The `policyConditions` array of a service definition
([`RangerServiceDef.RangerPolicyConditionDef`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/plugin/model/RangerServiceDef.java))
lists the available conditions:

| Field | Type | Description |
| --- | --- | --- |
| `itemId` | Long | Unique numeric id within the service definition. |
| `name` | String | The condition name used as `type` in policies. |
| `evaluator` | String | Fully qualified class implementing `RangerConditionEvaluator`. |
| `evaluatorOptions` | Map | String map passed to the evaluator (`attributeName`, `scriptTemplate`, `engineName`, ...). |
| `label` | String | Name shown in the policy editor. |
| `description` | String | Help text shown in the policy editor. |
| `uiHint` | String | JSON hint for the editor: `isMultiValue`, `singleValue` or `isMultiline` set to `true`. |

The service definitions that ship with Ranger declare these conditions:

| Service definition | Condition name | Evaluator |
| --- | --- | --- |
| `kafka`, `knox`, `solr`, `schema-registry`, `abfs`, `ozone` | `ip-range` | `RangerIpMatcher` |
| `ozone` | `action-matches` | `RangerActionMatcher` |
| `tag` | `accessed-after-expiry` | `RangerScriptTemplateConditionEvaluator` |
| `tag`, `gds` | `expression` | `RangerScriptConditionEvaluator` |
| `gds` | `validitySchedule` | `RangerValidityScheduleConditionEvaluator` |

`hive`, `hdfs`, `hbase` and the others declare no conditions in their JSON files. When Ranger Admin saves a
service definition that has no `RangerScriptConditionEvaluator` condition, it adds an implicit one named
`_expression` (`RangerServiceDefServiceBase.addImplicitConditionExpressionIfNeeded`), unless
`ranger.servicedef.enableImplicitConditionExpression` is `false` in the Admin configuration or the service
definition option `enableImplicitConditionExpression` is `false`. Add any other condition you need by updating
the service definition (see [Adding a condition to a service definition](#adding-a-condition-to-a-service-definition)).

## Built-in condition evaluators

All evaluators live in
[`org.apache.ranger.plugin.conditionevaluator`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/plugin/conditionevaluator)
and extend `RangerAbstractConditionEvaluator`, which gives them the service definition, the
condition definition (with `evaluatorOptions`) and the condition instance (with `values`). Each
implements `boolean isMatched(RangerAccessRequest request)`.

### Request evaluators

These evaluators compare a property of the request with the condition values.

| Evaluator | Values | Matches when |
| --- | --- | --- |
| `RangerIpMatcher` | IPv4/IPv6 addresses, exact (`10.1.2.3`) or with a trailing wildcard (`10.1.*`, `fe80:*`) | The client IP equals a value or starts with a wildcard prefix. No values, or `*`, always match. |
| `RangerTimeOfDayMatcher` | Time ranges such as `9am-5pm` or `8:30AM-6:15PM`; a range may wrap midnight (`10pm-6am`) | The access time (plugin clock) falls inside one of the ranges. |
| `RangerActionMatcher` | Action names, for example an Ozone operation | The request's `action` matches one of the values. |
| `RangerValidityScheduleConditionEvaluator` | `RangerValiditySchedule` objects serialized as JSON strings | The access time is inside one of the schedules (same format as a policy's `validitySchedules`). |

### Cluster evaluators

| Evaluator | Values | Matches when |
| --- | --- | --- |
| `RangerAccessedFromClusterCondition` | Cluster names | The request's cluster name is in the list. An empty list always matches. |
| `RangerAccessedNotFromClusterCondition` | Cluster names | The request's cluster name is not in the list, or is unknown. |
| `RangerAccessedFromClusterTypeCondition` | Cluster types | The request's cluster type is in the list. |
| `RangerAccessedNotFromClusterTypeCondition` | Cluster types | The request's cluster type is not in the list. |

### Context attribute evaluators

Both read the request-context attribute named by the `attributeName` evaluator option. An absent attribute
counts as a match.

| Evaluator | Values | Matches when |
| --- | --- | --- |
| `RangerContextAttributeValueInCondition` | Allowed values | The attribute has one of the values. |
| `RangerContextAttributeValueNotInCondition` | Disallowed values | The attribute's value is not in the list. |

### Tag evaluators

| Evaluator | Values | Matches when |
| --- | --- | --- |
| `RangerTagsAllPresentConditionEvaluator` | Tag names | The resource carries all listed tags. |
| `RangerAnyOfExpectedTagsPresentConditionEvaluator` | Tag names | The resource carries at least one listed tag. |
| `RangerNoneOfExpectedTagsPresentConditionEvaluator` | Tag names | The resource carries none of the listed tags. |

### Hive evaluators

The values are Hive resources written as `db`, `db.table` or `db.table.column`.

| Evaluator | Matches when |
| --- | --- |
| `RangerHiveResourcesAccessedTogetherCondition` | The query touches at least one of the listed resources together with the policy's resource. |
| `RangerHiveResourcesNotAccessedTogetherCondition` | The query does not touch any of the listed resources. Use it to forbid joining two tables. |

### Script evaluators

| Evaluator | Values | Matches when |
| --- | --- | --- |
| `RangerScriptConditionEvaluator` | A single script | The script evaluates to `true`. See [Script conditions](#script-conditions-and-dynamic-expressions). |
| `RangerScriptTemplateConditionEvaluator` | `yes`/`true` or `no`/`false` | The script in the `scriptTemplate` option evaluates to `true`; `no`/`false` inverts the result. |

Both script evaluators accept the option `enableJsonCtx`. The shipped definitions also set `engineName` to
`JavaScript`; JavaScript is the only engine `ScriptEngineUtil` creates. The `tag`
service definition uses the template evaluator for `accessed-after-expiry`.

Notes:

- Cluster name and type come from the plugin configuration:
  `ranger.plugin.<serviceType>.access.cluster.name` and `ranger.plugin.<serviceType>.access.cluster.type`
  ([`RangerPluginConfig`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/authorization/hadoop/config/RangerPluginConfig.java)).
  They are also available to scripts as `REQ.clusterName` and `REQ.clusterType`.
- The tag-presence evaluators read the tags added by `RangerTagEnricher`, so they only work in
  services linked to a tag service.
- The Hive "accessed together" evaluators rely on the Hive plugin recording every resource referenced
  by a query in the request context (`REQUESTED_RESOURCES`).

An example declaring the IP and time-of-day conditions for Kafka (`ip-range` already exists in the
shipped definition):

```json
"policyConditions": [
  {
    "itemId": 1,
    "name": "ip-range",
    "evaluator": "org.apache.ranger.plugin.conditionevaluator.RangerIpMatcher",
    "label": "IP Address Range",
    "description": "IP Address Range",
    "uiHint": "{ \"isMultiValue\":true }"
  },
  {
    "itemId": 2,
    "name": "time-of-day",
    "evaluator": "org.apache.ranger.plugin.conditionevaluator.RangerTimeOfDayMatcher",
    "label": "Time of day",
    "description": "Ranges such as 9am-5pm",
    "uiHint": "{ \"isMultiValue\":true }"
  }
]
```

And a Kafka policy item that uses both:

```json
{
  "accesses": [ { "type": "publish", "isAllowed": true } ],
  "groups": ["ingest"],
  "conditions": [
    { "type": "ip-range",    "values": ["10.20.*", "192.168.1.100"] },
    { "type": "time-of-day", "values": ["6am-8pm"] }
  ],
  "delegateAdmin": false
}
```

## Script conditions and dynamic expressions

`RangerScriptConditionEvaluator` runs a JavaScript expression in a sandboxed engine
([`ScriptEngineUtil`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/plugin/util/ScriptEngineUtil.java)
tries GraalVM JavaScript first, then the JDK's built-in JavaScript engine). The same engine and the
same variables are used for `${{ ... }}` dynamic expressions in resource names and row filters, so
what you learn here applies to [ABAC](../abac.md) as well. The `expression` condition of the `tag`
and `gds` service definitions and the implicit `_expression` condition of the other service definitions
are instances of this evaluator.

### Variables

[`RangerRequestScriptEvaluator`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/plugin/policyengine/RangerRequestScriptEvaluator.java)
binds these names (constants in `RangerCommonConstants`):

| Variable | Description |
| --- | --- |
| `REQ` | Request map; its keys are listed below the table. |
| `RES` | Resource map keyed by resource name (`database`, `table`, `column`, ...), plus `_ownerUser` when known. |
| `USER` | The user's attributes from the user store, plus `_name`. |
| `UGNAMES` | List of the user's group names. |
| `URNAMES` | List of the user's role names. |
| `UG` | Map of group name → group attributes. |
| `UGA` | Map of attribute name → list of values, merged over all groups of the user. |
| `TAG` | The tag currently being evaluated (`_type` plus its attributes). Tag-based policies only. |
| `TAGS` | Map of tag type → tag attributes, for every tag on the resource. |
| `TAGNAMES` | List of tag types on the resource. |
| `ctx` | The evaluator object itself, with the helper methods below. |

`REQ` has the keys `accessType`, `action`, `accessTime`, `user`, `userGroups`, `userRoles`, `clientIPAddress`,
`remoteIPAddress`, `forwardedAddresses`, `clientType`, `clusterName`, `clusterType`, `requestData`,
`resourceMatchingScope`, `resource`, `userAttributes`, `userGroupAttributes` and `uga`.

### Macros and helpers

Macros are rewritten into calls on `ctx` before the script runs, so both spellings work:

| Macro | `ctx` helper | Returns |
| --- | --- | --- |
| `GET_TAG_NAMES()` | `ctx.tagNames()` | Tag names as CSV. |
| `GET_TAG_ATTR_NAMES()` | `ctx.tagAttrNames()` | Tag attribute names as CSV. |
| `GET_TAG_ATTR(name)` | `ctx.tagAttr(name)` | Values of a tag attribute as CSV. |
| `GET_UG_NAMES()` | `ctx.ugNames()` | Group names as CSV. |
| `GET_UG_ATTR_NAMES()` | `ctx.ugAttrNames()` | Group attribute names as CSV. |
| `GET_UG_ATTR(name)` | `ctx.ugAttr(name)` | Values of a group attribute as CSV. |
| `GET_UR_NAMES()` | `ctx.urNames()` | Role names as CSV. |
| `GET_USER_ATTR_NAMES()` | `ctx.userAttrNames()` | User attribute names as CSV. |
| `GET_USER_ATTR(name)` | `ctx.userAttr(name)` | Value of a user attribute. |

Boolean macros:

| Macro | `ctx` helper | True when |
| --- | --- | --- |
| `HAS_TAG(name)` | `ctx.hasTag(name)` | The resource has the tag. |
| `HAS_ANY_TAG` | `ctx.hasAnyTag()` | The resource has at least one tag. |
| `HAS_NO_TAG` | `!ctx.hasAnyTag()` | The resource has no tag. |
| `HAS_TAG_ATTR(name)` | `ctx.hasTagAttr(name)` | The current tag has the attribute. |
| `HAS_USER_ATTR(name)` | `ctx.hasUserAttr(name)` | The user has the attribute. |
| `HAS_UG_ATTR(name)` | `ctx.hasUgAttr(name)` | One of the user's groups has the attribute. |
| `IS_IN_GROUP(name)` | `ctx.isInGroup(name)` | The user is in the group. |
| `IS_IN_ANY_GROUP` | `ctx.isInAnyGroup()` | The user is in at least one group. |
| `IS_NOT_IN_ANY_GROUP` | `!ctx.isInAnyGroup()` | The user is in no group. |
| `IS_IN_ROLE(name)` | `ctx.isInRole(name)` | The user has the role. |
| `IS_IN_ANY_ROLE` | `ctx.isInAnyRole()` | The user has at least one role. |
| `IS_NOT_IN_ANY_ROLE` | `!ctx.isInAnyRole()` | The user has no role. |

Time macros compare the access time with timestamps written as `yyyy/MM/dd HH:mm:ss`, `yyyy/MM/dd HH:mm` or
`yyyy/MM/dd`; the time zone argument is optional.

| Macro | `ctx` helper |
| --- | --- |
| `IS_ACCESS_TIME_AFTER(time[, tz])` | `ctx.isAccessTimeAfter(time[, tz])` |
| `IS_ACCESS_TIME_BEFORE(time[, tz])` | `ctx.isAccessTimeBefore(time[, tz])` |
| `IS_ACCESS_TIME_BETWEEN(from, to[, tz])` | `ctx.isAccessTimeBetween(from, to[, tz])` |

Some helpers have no macro:

- `ctx.isAccessedAfter(attr)` and `ctx.isAccessedBefore(attr)` compare the access time with a date attribute
  of the current tag.
- `ctx.getUser()`, `ctx.getUserGroups()`, `ctx.getUserRoles()`, `ctx.getClientIPAddress()`, `ctx.getAction()`,
  `ctx.getResource()` and `ctx.getRequestContextAttribute(name)` return raw request details.

Every CSV-returning macro has a `_Q` variant that quotes values, and accepts an optional default
value and separator. Date attributes are parsed with `yyyy/MM/dd` or Atlas' ISO-8601 format by default;
additional `SimpleDateFormat` patterns can be listed, separated by `||`, in the plugin property
`ranger.plugin.tag.attr.additional.date.formats`.

Examples of conditions:

```javascript
TAG.sensitiveLevel >= 10                                  // tag attribute
GET_USER_ATTR('clearance', 0) >= TAG.sensitiveLevel       // user attribute with a default
IS_IN_GROUP('finance') && IS_IN_ROLE('analyst')            // membership
REQ.clientIPAddress.startsWith('10.20.') && !HAS_TAG('PII') // request detail and tags
RES._ownerUser == REQ.user                                // owner check
```

Set `enableJsonCtx` to `true` in `evaluatorOptions` when a script needs the uppercase variables
(`REQ`, `RES`, `USER`, ...); when the option is absent, the evaluator enables it automatically if the
script references them. Scripts that only use `ctx` helpers run without building the JSON context and
are cheaper.

The [Dynamic expressions](../../blog/dynamic-expressions.md) blog post walks through more examples.

## Geo-location conditions

Authorization by location combines a context enricher and a condition:

1. **Data file.** A CSV whose first line names the fields and whose rows give a location for an
   inclusive IP range (`IP_FROM` and `IP_TO` come first). IPs may be dotted (`IPInDotFormat=true`)
   or long integers:

    ```text title="/etc/ranger/geo/geo.txt"
    IP_FROM,IP_TO,COUNTRY_CODE,COUNTRY_NAME,REGION,CITY
    10.0.0.255,10.0.3.0,US,United States,California,Santa Clara
    20.0.100.80,20.0.100.89,US,United States,Colorado,Broomfield
    20.0.100.110,20.0.100.119,US,United States,Texas,Irving
    ```

    The format matches commercial IP-to-location feeds; the file must be readable by every process
    that hosts the plugin.

2. **Enricher.** [`RangerFileBasedGeolocationProvider`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/plugin/contextenricher/RangerFileBasedGeolocationProvider.java)
   looks up the request's client IP and adds one context entry per field, named `LOCATION_<FIELD>`
   (`LOCATION_COUNTRY_CODE=US`, `LOCATION_CITY=Broomfield`, ...). Options
   ([`GeolocationFileStore`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/plugin/store/file/GeolocationFileStore.java)):

    | Option | Description |
    | --- | --- |
    | `FilePath` | Location of the data file. |
    | `IPInDotFormat` | `true` if IP ranges are written in dot notation. |
    | `ForceRead` | `true` to re-read the file on every initialization. |
    | `geolocation.meta.prefix` | Optional string inserted after `LOCATION_` in the context names. |

3. **Condition.** `RangerContextAttributeValueNotInCondition` (or `...InCondition`) with
   `attributeName` set to one of the context entries.

Add both to the Hive service definition:

```json
"contextEnrichers": [
  {
    "itemId": 1,
    "name": "GeoEnricher",
    "enricher": "org.apache.ranger.plugin.contextenricher.RangerFileBasedGeolocationProvider",
    "enricherOptions": { "FilePath": "/etc/ranger/geo/geo.txt", "IPInDotFormat": "true" }
  }
],
"policyConditions": [
  {
    "itemId": 1,
    "name": "location-outside",
    "evaluator": "org.apache.ranger.plugin.conditionevaluator.RangerContextAttributeValueNotInCondition",
    "evaluatorOptions": { "attributeName": "LOCATION_COUNTRY_CODE" },
    "label": "Accessed from outside of country?",
    "description": "Country codes; the condition matches when the client is outside all of them",
    "uiHint": "{ \"isMultiValue\":true }"
  }
]
```

Then deny access to a table from outside the US:

```json title="deny-outside-us.json"
{
  "service": "dev_hive",
  "name": "customer-us-only",
  "policyType": 0,
  "isEnabled": true,
  "isAuditEnabled": true,
  "resources": {
    "database": { "values": ["cust"] },
    "table":    { "values": ["customer"] },
    "column":   { "values": ["*"] }
  },
  "policyItems": [],
  "denyPolicyItems": [
    {
      "accesses": [ { "type": "all", "isAllowed": true } ],
      "groups": ["public"],
      "conditions": [ { "type": "location-outside", "values": ["US"] } ],
      "delegateAdmin": false
    }
  ]
}
```

Requests whose IP is not in the data file have no `LOCATION_*` entries; both context-attribute
evaluators treat a missing attribute as a match, so the deny above applies to unknown locations too.

## Adding a condition to a service definition

Service definitions are updated as a whole. Fetch the current definition, add entries to
`policyConditions` (and `contextEnrichers` if needed) with unused `itemId`s, and put it back:

```bash
curl -u admin:rangerR0cks! http://localhost:6080/service/public/v2/api/servicedef/name/hive > hive-def.json
# edit hive-def.json
curl -u admin:rangerR0cks! -H 'Content-Type: application/json' \
     -X PUT http://localhost:6080/service/public/v2/api/servicedef/name/hive -d @hive-def.json
```

The policy editor shows the new condition under **Policy Conditions** (policy level) and in the
**Rule Conditions** column of every policy item, and plugins receive the updated definition together with the next policy download. Custom
evaluator and enricher classes must be on the plugin's classpath; the `ranger-examples`
module shows how to package them (see [Custom conditions and enrichers](../../dev/custom-conditions-enrichers.md)).

Then create a policy that uses it:

```bash
curl -u admin:rangerR0cks! -H 'Content-Type: application/json' \
     -X POST http://localhost:6080/service/public/v2/api/policy -d @deny-outside-us.json
```

## Evaluation semantics

- Policy-level conditions are checked after validity schedules and resource matching, before any
  policy item is looked at; a failing condition removes the whole policy from consideration for that
  request.
- Item-level conditions are checked after the principal and access type of the item match. All
  conditions on an item must pass (logical AND); to express OR, list several values in one
  condition (where the evaluator supports it) or create separate policy items.
- Conditions never grant access by themselves: a matching deny item still denies, a matching allow
  item still allows, and a non-matching item is simply skipped.
- Evaluators are instantiated once per policy (item) when the plugin loads policies and are called
  for every request, so keep scripts short. Setting
  `ranger.plugin.<serviceType>.policyengine.option.disable.custom.conditions=true` in the plugin
  configuration makes the engine ignore all conditions. Script conditions are the slowest; prefer a purpose-built
  evaluator for high-volume checks.
- A condition whose `type` is not declared in the service definition, or whose evaluator class
  cannot be instantiated, is ignored (the plugin logs an error and the item behaves as if the
  condition were absent). A script that fails to evaluate counts as *not matched*; if no JavaScript
  engine is available at all, script conditions count as *matched* and an error is logged.
- Conditions on mask and row filter items decide which item supplies the mask or filter; see
  [Row filtering and column masking](row-filter-column-masking.md#evaluation-semantics).

## Related features

- [Resource-based policies](resource-policies.md) — where conditions fit in the policy model.
- [Tag-based policies](tag-based-policies.md) — `accessed-after-expiry`, `expression` and tag attributes.
- [Attribute-based access control](../abac.md) — user/group attributes in expressions.
- [Custom conditions and enrichers](../../dev/custom-conditions-enrichers.md) — writing your own evaluator.
- [Dynamic expressions](../../blog/dynamic-expressions.md) blog post.
- Further reading on cwiki: [Geo-location based policies](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=61323045).
