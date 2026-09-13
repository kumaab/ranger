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

# Service definitions

Files: `agents-common/src/main/resources/service-defs/ranger-servicedef-<type>.json` (26 today). JSON carries no license header (RAT-excluded).

## Schema (mirrors `model/RangerServiceDef`)

```
id, name, displayName, implClass, label, description, guid, rbKeyLabel, rbKeyDescription,
options            Map<String,String>            e.g. "enableDenyAndExceptionsInPolicies": "true", "ui.pages", "security.allowed.roles"
resources[]        RangerResourceDef             itemId, name, type, level, parent, mandatory, lookupSupported, recursiveSupported,
                                                 excludesSupported, matcher, matcherOptions, validationRegEx, validationMessage, uiHint,
                                                 label, description, accessTypeRestrictions, isValidLeaf
accessTypes[]      RangerAccessTypeDef           itemId, name, label, impliedGrants[], category (CREATE|READ|UPDATE|DELETE|MANAGE)
policyConditions[] RangerPolicyConditionDef      itemId, name, evaluator, evaluatorOptions, validationRegEx, validationMessage, uiHint, label, description
contextEnrichers[] RangerContextEnricherDef      itemId, name, enricher, enricherOptions
configs[]          RangerServiceConfigDef        itemId, name, type (bool|enum|int|string|password|path), subType, mandatory, defaultValue, label, uiHint
enums[]            RangerEnumDef                 itemId, name, defaultIndex, elements[]
dataMaskDef        { accessTypes[], resources[], maskTypes[] { itemId, name, label, description, transformer } }
rowFilterDef       { accessTypes[], resources[] }
```

`itemId` values are stable integers; never renumber existing ones.

## Canonical examples

- `ranger-servicedef-hdfs.json` (id 1): single `path` resource with `RangerPathResourceMatcher`, `recursiveSupported`.
- `ranger-servicedef-kms.json` (id 7): single string resource, `options.ui.pages`, `security.allowed.roles`.
- `ranger-servicedef-hive.json` (id 3): 7-level hierarchy, 14 access types, `dataMaskDef` + `rowFilterDef`.
- `ranger-servicedef-tag.json` (id 100): the only def with `contextEnrichers`; `_expression` script condition.

```json
"resources": [
  { "itemId": 1, "name": "path", "parent": "", "level": 10, "type": "path",
    "mandatory": true, "lookupSupported": true, "recursiveSupported": true,
    "matcher": "org.apache.ranger.plugin.resourcematcher.RangerPathResourceMatcher",
    "matcherOptions": { "wildCard": true, "ignoreCase": false },
    "label": "Resource Path", "description": "HDFS file or directory path" }
],
"accessTypes": [
  { "itemId": 1, "name": "read", "label": "Read", "category": "READ" },
  { "itemId": 4, "name": "admin", "label": "Admin", "category": "MANAGE", "impliedGrants": [ "read", "write", "create" ] }
],
"policyConditions": [
  { "itemId": 1, "name": "ip-range",
    "evaluator": "org.apache.ranger.plugin.conditionevaluator.RangerIpMatcher",
    "label": "IP Address Range", "uiHint": "{ \"isMultiValue\":true }" }
],
"contextEnrichers": [
  { "itemId": 1, "name": "TagEnricher",
    "enricher": "org.apache.ranger.plugin.contextenricher.RangerTagEnricher",
    "enricherOptions": { "tagRetrieverClassName": "org.apache.ranger.plugin.contextenricher.RangerAdminTagRetriever",
                         "tagRefresherPollingInterval": 60000 } }
]
```

Hierarchy: `parent` names the parent resource, `level` orders them; multiple leaf paths are allowed (Hive `database/table/column` vs `database/udf`).
Matcher options: `wildCard`, `ignoreCase`, `pathSeparatorChar`, `replaceTokens`, `quotedCaseSensitive`, `quoteChars`, `tokenDelimiter*`, `replaceReqExpressions`.

## Registration (`store/EmbeddedServiceDefsUtil`)

```java
public static final String DEFAULT_BOOTSTRAP_SERVICEDEF_LIST = "tag,gds,hdfs,hbase,hive,kms,knox,storm,yarn,kafka,solr,atlas,nifi,nifi-registry,sqoop,kylin,elasticsearch,presto,trino,ozone,kudu,schema-registry,nestedstructure,polaris";
public static final String EMBEDDED_SERVICEDEF_KAFKA_NAME     = "kafka";
public static final String KAFKA_IMPL_CLASS_NAME              = "org.apache.ranger.services.kafka.RangerServiceKafka";
```

For a new type: constant, impl class name, a `RangerServiceDef` field, a `getOrCreateServiceDef(store, EMBEDDED_SERVICEDEF_X_NAME)` call in `init`, a `getXServiceDefId()`
accessor, and the name appended to the bootstrap list. Defs are normalized with `ServiceDefUtil.normalize` before `store.createServiceDef`. `tag` must be created first, `gds` last.
Properties: `ranger.service.store.create.embedded.service-defs` (default true), `ranger.supportedcomponents` (filter).

Reserved ids in shipped JSON: hdfs 1, hbase 2, hive 3, yarn 4, knox 5, storm 6, kms 7, solr 8, kafka 9, nifi 10, kylin 12, nifi-registry 13, sqoop 14, atlas 15,
elasticsearch 16, presto 17, tag 100, wasb 101, abfs 103, kudu 105, ozone 201, schema-registry 202, trino 203. `gds`, `nestedstructure`, `polaris` have no id.
Pick an unused id above 203 for a new embedded def, or omit it.

## Resource hierarchy as a path

`RangerServiceDefHelper.getRrnTemplate(resourceType)` returns the `/`-joined hierarchy (`database/table/column`, `bucket/path`) computed from the resource
graph; `getAllResourceNames()` lists leaves. `authz-api`'s `RangerResourceNameParser` parses `table:db/tbl` against it (see `ranger-authz`). There is no
`rrnTemplate` key in the JSON.

## Updating an existing def

Editing the JSON only affects fresh installs. Existing deployments need a Java patch in `security-admin` (`Patch*_J<n>`) that loads the def from the store,
mutates it, and calls `svcDBStore.updateServiceDef`. Example: `PatchForOzoneServiceDefPolicyConditionUpdate_J10066`.

## Implicit additions at runtime

`RangerBasePlugin.setPolicies` injects a UserStore enricher (`ranger.plugin.<svc>.enable.implicit.userstore.enricher`) and GDS-info enricher
(`...enable.implicit.gdsinfo.enricher`, default true) via `ServiceDefUtil`. An implicit `_expression` policy condition bound to `RangerScriptConditionEvaluator`
is available to every def (`ServiceDefUtil.IMPLICIT_CONDITION_EXPRESSION_NAME`).
