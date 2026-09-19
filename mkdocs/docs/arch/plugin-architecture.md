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

# Plugin Architecture

A Ranger plugin is the piece of Ranger that lives inside the service being protected. When you
configure the Hive plugin, for example, you add a set of jars and configuration files to HiveServer2
so that, on every query, Hive asks Ranger's embedded policy engine whether the user may run it. The plugin
downloads policies from Ranger Admin in the background, keeps them in memory and on local disk, and
sends audit records for each decision. Nothing on the query path calls out to Ranger Admin.

All plugins share the same core, `RangerBasePlugin` from the `agents-common` module, and a
service-specific adapter that translates the host's authorization callback (Hive's
`HiveAuthorizer`, HDFS's `INodeAttributeProvider`, a Kafka `Authorizer`, an HBase coprocessor, and
so on) into Ranger's request model. This page describes that shared core: lifecycle, configuration,
policy refresh, the request and result objects, context enrichers and condition evaluators, the
service-definition model, class-loader isolation, and auditing. If you want to write a plugin for
your own application, read this page first and then [Custom plugins](../plugins/custom-plugin.md).

## Lifecycle

```mermaid
sequenceDiagram
  participant H as Host service (e.g. HiveServer2)
  participant S as Shim (RangerHiveAuthorizerFactory)
  participant P as RangerBasePlugin
  participant R as PolicyRefresher thread
  participant A as Ranger Admin
  participant AU as Audit framework

  H->>S: load authorizer class
  S->>P: new RangerBasePlugin("hive", appId) and init()
  P->>AU: AuditProviderFactory.init(ranger-hive-audit.xml)
  P->>R: start (daemon thread)
  R->>A: download roles + policies
  A-->>R: ServicePolicies
  R->>P: setPolicies() -> new RangerPolicyEngineImpl
  loop every policy.pollIntervalMs
    R->>A: getServicePoliciesIfUpdated(lastKnownVersion)
  end
  H->>P: isAccessAllowed(RangerAccessRequest)
  P->>P: enrich request, evaluate policies
  P->>AU: RangerDefaultAuditHandler.processResult()
  P-->>H: RangerAccessResult
```

1. The host service instantiates the plugin's entry class (through the shim; see
   [Shim and class loader](#shim-and-class-loader-isolation)).
2. The entry class creates a `RangerBasePlugin(serviceType, appId)` and calls `init()`. The
   constructor loads `ranger-<type>-security.xml`, `ranger-<type>-audit.xml`, and
   `ranger-<type>-policymgr-ssl.xml` from the classpath into a `RangerPluginConfig`.
3. `init()` initializes the audit framework, then starts a `PolicyRefresher` thread that performs
   an initial synchronous download of roles and policies (falling back to the local cache) and
   schedules periodic refreshes.
4. Each downloaded policy set becomes a new `RangerPolicyEngineImpl`, which is swapped in
   atomically; in-flight requests finish on the old engine.
5. The host calls `isAccessAllowed()` (and `evalDataMaskPolicies()` / `evalRowFilterPolicies()`
   for services that support them) for each operation. The configured
   `RangerAccessResultProcessor`, normally `RangerDefaultAuditHandler`, turns each result into an
   audit event.
6. `cleanup()` stops the refresher and releases the engine when the host shuts down.

## RangerBasePlugin API

The methods a host adapter uses most, from
[`RangerBasePlugin`](https://github.com/apache/ranger/blob/master/agents-common/src/main/java/org/apache/ranger/plugin/service/RangerBasePlugin.java):

`RangerBasePlugin(String serviceType, String appId)`
:   Create a plugin for a service type (`hive`, `hdfs`, your custom type). `appId` distinguishes
    several hosts of the same type (for example `hiveServer2` and `hiveMetastore`) in audit and
    cache file names.

`init()`
:   Load audit configuration, start policy refresh, initialize chained plugins.

`setResultProcessor(RangerAccessResultProcessor)`
:   Install the audit handler.

`isAccessAllowed(RangerAccessRequest)`, `isAccessAllowed(Collection<RangerAccessRequest>)`
:   Evaluate access policies (policy type 0). Overloads take an explicit result processor.

`evalDataMaskPolicies(request, resultProcessor)`
:   Evaluate data-mask policies; the result carries the mask type and expression.

`evalRowFilterPolicies(request, resultProcessor)`
:   Evaluate row-filter policies; the result carries the filter expression.

`getResourceACLs(request)`
:   Return the effective allow/deny per user, group, and role for a resource (used by Hive
    `SHOW GRANT`, the HBase coprocessor's `getUserPermissions`, and similar).

`grantAccess(GrantRevokeRequest, ...)`, `revokeAccess(...)`
:   Forward SQL `GRANT`/`REVOKE` statements to Ranger Admin so they become policies.

`refreshPoliciesAndTags()`
:   Force an immediate refresh.

`getServiceDef()`, `getPolicyVersion()`, `getConfig()`
:   Introspection.

`cleanup()`
:   Stop background threads.

The minimal host adapter looks like the sample in
[`ranger-examples/plugin-sampleapp`](https://github.com/apache/ranger/blob/master/ranger-examples/plugin-sampleapp/src/main/java/org/apache/ranger/examples/sampleapp/RangerAuthorizer.java):

```java title="RangerAuthorizer.java (excerpt)"
plugin = new RangerBasePlugin("sampleapp", "sampleapp");
plugin.setResultProcessor(new RangerDefaultAuditHandler(plugin.getConfig()));
plugin.init();

RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
resource.setValue("path", fileName); // "path" must be a resource name in the servicedef JSON

RangerAccessRequest request = new RangerAccessRequestImpl(resource, accessType, user, userGroups, null);
RangerAccessResult  result  = plugin.isAccessAllowed(request);

return result != null && result.getIsAllowed();
```

## Configuration

`RangerPluginConfig` reads three XML files for the service type from the classpath of the protected
service, and optionally three more for the specific service name
(`ranger-<type>-<serviceName>-security.xml` and so on) that override them. Keys in
`ranger-<type>-security.xml` start with `ranger.plugin.<type>`, where `<type>` is the service type
(`hive`, `trino`, `kafka`, ...). The tables list the properties read by `agents-common`; individual
plugin pages list the properties specific to their service.

### Connection

How the plugin finds Ranger Admin and which service's policies it enforces. The first two keys are
required.

| Key | Default | Type | Description |
|-----|---------|------|-------------|
| `ranger.plugin.<type>.service.name` | (none) | String | Name of the service in Ranger Admin whose policies this plugin enforces. |
| `ranger.plugin.<type>.policy.rest.url` | (none) | URL | Ranger Admin URL, for example `http://ranger-admin:6080`. Comma-separated list for HA. |
| `ranger.plugin.<type>.policy.rest.ssl.config.file` | (none) | Path | `ranger-<type>-policymgr-ssl.xml` with keystore and truststore settings for HTTPS to Admin. |
| `ranger.plugin.<type>.policy.source.impl` | `org.apache.ranger.admin.client.RangerAdminRESTClient` | Class | Class that fetches policies. Replace it to load policies from another source. |
| `ranger.plugin.<type>.policy.rest.client.connection.timeoutMs` | `120000` | Duration (ms) | HTTP connect timeout to Admin. |
| `ranger.plugin.<type>.policy.rest.client.read.timeoutMs` | `30000` | Duration (ms) | HTTP read timeout. |
| `ranger.plugin.<type>.policy.rest.client.max.retry.attempts` | `3` | Integer | Retries per download attempt. |
| `ranger.plugin.<type>.policy.rest.client.retry.interval.ms` | `1000` | Duration (ms) | Delay between retries. |
| `ranger.plugin.<type>.policy.rest.client.cookie.enabled` | `true` | Boolean | Reuse the Admin session cookie between requests. |

```xml title="ranger-<type>-security.xml (minimal, for a Trino service)"
<configuration>
  <property>
    <name>ranger.plugin.trino.service.name</name>
    <value>dev_trino</value>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.rest.url</name>
    <value>http://ranger-admin:6080</value>
  </property>
  <property>
    <name>ranger.plugin.trino.policy.cache.dir</name>
    <value>/etc/ranger/dev_trino/policycache</value>
  </property>
</configuration>
```

### Policy refresh and cache

The refresher polls Ranger Admin and keeps a copy of what it downloaded on local disk, so the
service can start while Admin is unreachable.

| Key | Default | Type | Description |
|-----|---------|------|-------------|
| `ranger.plugin.<type>.policy.cache.dir` | (none) | Path | Directory for the cache file `<appId>_<serviceName>.json`, plus roles, tags, and user-store caches. |
| `ranger.plugin.<type>.policy.pollIntervalMs` | `30000` | Duration (ms) | How often the refresher asks Admin for updates. |
| `ranger.plugin.<type>.supports.policy.deltas` | `false` | Boolean | Ask Admin for incremental policy changes instead of the full set. |
| `ranger.plugin.<type>.supports.tag.deltas` | `false` | Boolean | Ask Admin for incremental tag changes. |
| `ranger.plugin.<type>.preserve.deltas` | `false` | Boolean | Keep versioned copies of the cache file. |
| `ranger.plugin.<type>.dedup.strings` | `true` | Boolean | Intern strings in downloaded policies to reduce heap use. |

### Request context

These keys describe where the plugin runs and how it determines the client address.

| Key | Default | Type | Description |
|-----|---------|------|-------------|
| `ranger.plugin.<type>.access.cluster.name` | `""` | String | Cluster name sent with requests and audits; used by cluster-based conditions. |
| `ranger.plugin.<type>.access.cluster.type` | `""` | String | Cluster type, used the same way. |
| `ranger.plugin.<type>.use.x-forwarded-for.ipaddress` | `false` | Boolean | Take the client IP from `X-Forwarded-For` when the request passed through a proxy. |
| `ranger.plugin.<type>.trusted.proxy.ipaddresses` | (none) | List | Proxies trusted for the `X-Forwarded-For` header. |

### Users and groups

Principals with special treatment, and how the plugin resolves a user's groups.

| Key | Default | Type | Description |
|-----|---------|------|-------------|
| `ranger.plugin.<type>.super.users` | (none) | List | Users always allowed, bypassing policies. |
| `ranger.plugin.<type>.super.groups` | (none) | List | Groups always allowed, bypassing policies. |
| `ranger.plugin.<type>.service.admins` | (none) | List | Users treated as service admins by the engine. |
| `ranger.plugin.<type>.audit.exclude.users` | (none) | List | Users whose requests are not audited. |
| `ranger.plugin.<type>.audit.exclude.groups` | (none) | List | Groups whose requests are not audited. |
| `ranger.plugin.<type>.audit.exclude.roles` | (none) | List | Roles whose requests are not audited. |
| `ranger.plugin.<type>.use.rangerGroups` | `false` | Boolean | Add the user's groups from the Ranger user store to the request. |
| `ranger.plugin.<type>.use.only.rangerGroups` | `false` | Boolean | Ignore groups supplied by the host and use only Ranger's. |
| `ranger.plugin.<type>.convert.emailToUser` | `false` | Boolean | Map an email-address principal to a Ranger user name through the user store. |

### Enrichers, fallback and chaining

| Key | Default | Type | Description |
|-----|---------|------|-------------|
| `ranger.plugin.<type>.is.fallback.supported` | `false` | Boolean | Return "undetermined" to the host instead of deny when no policy matches. HDFS sets this from `xasecure.add-hadoop-authorization`. |
| `ranger.plugin.<type>.enable.implicit.userstore.enricher` | `false` | Boolean | Load the user store even if the service definition does not declare the enricher. Turned on automatically by the Ranger-groups and email options above. |
| `ranger.plugin.<type>.enable.implicit.gdsinfo.enricher` | `true` | Boolean | Load [GDS](../features/gds/gds_intro.md) dataset information. |
| `ranger.plugin.<type>.chained.services` | (none) | List | Other services whose policies are consulted after this one; see [Chained plugins](#chained-plugins). |
| `ranger.plugin.<type>.chained.services.<name>.impl` | (none) | Class | `RangerChainedPlugin` implementation for each chained service. |

### Policy engine options

These tune `RangerPolicyEngineOptions`. All keys start with
`ranger.plugin.<type>.policyengine.option.`; the table shows the rest of the key. The defaults shown
are for plugins (Ranger Admin uses different defaults for its own embedded engine).

| Key (after the prefix) | Default | Type | Description |
|------------------------|---------|------|-------------|
| `disable.context.enrichers` | `false` | Boolean | Skip tag, user-store, and GDS enrichers. |
| `disable.custom.conditions` | `false` | Boolean | Ignore policy conditions. |
| `disable.tagpolicy.evaluation` | `false` | Boolean | Skip tag-based policies. |
| `disable.policy.refresher` | `false` | Boolean | Do not start the refresher; policies must be set programmatically. |
| `disable.tag.retriever` | `false` | Boolean | Skip the background download of tags. |
| `disable.userstore.retriever` | `false` | Boolean | Skip the background download of the user store. |
| `disable.gdsinfo.retriever` | `false` | Boolean | Skip the background download of GDS information. |
| `disable.role.resolution` | `true` | Boolean | When `false`, roles named in a policy are expanded to their users and groups when the policy's ACL summary is built. |
| `disable.trie.lookup.prefilter` | `false` | Boolean | Evaluate every policy instead of trie-selected candidates (debugging only). |
| `cache.audit.results` | `true` | Boolean | Cache the audit-enabled decision per resource. |
| `enable.resourcematcher.reuse` | `true` | Boolean | Share resource matchers between policies with identical resources. |
| `optimize.trie.for.retrieval` | `false` | Boolean | Use more memory for faster lookups in the resource trie. |
| `optimize.trie.for.space` | `false` | Boolean | Use less memory at the cost of slower lookups in the resource trie. |
| `optimize.tag.trie.for.retrieval` | `false` | Boolean | Same as `optimize.trie.for.retrieval`, for the tag trie. |
| `optimize.tag.trie.for.space` | `false` | Boolean | Same as `optimize.trie.for.space`, for the tag trie. |

### Audit (`ranger-<type>-audit.xml`)

Audit properties keep the `xasecure.audit` prefix. `<name>` is a destination: `auditserver`, `solr`,
`elasticsearch`, `opensearch`, `hdfs`, `log4j`, and others provided by `agents-audit`.

| Key | Default | Type | Description |
|-----|---------|------|-------------|
| `xasecure.audit.is.enabled` | `true` | Boolean | Master switch for auditing in this plugin. |
| `xasecure.audit.destination.<name>` | (none) | Boolean | Set to `true` to enable the destination. |
| `xasecure.audit.destination.<name>.batch.filespool.dir` | (none) | Path | Local spool directory used when the destination is unavailable. |
| `xasecure.audit.provider.filecache.is.enabled` | `false` | Boolean | Write events to a local file cache first and forward from there. |

Each destination has its own settings under `xasecure.audit.destination.<name>.`, for example
`xasecure.audit.destination.auditserver.url` and `xasecure.audit.destination.hdfs.dir`.

```xml title="ranger-<type>-audit.xml (audit server destination)"
<configuration>
  <property>
    <name>xasecure.audit.is.enabled</name>
    <value>true</value>
  </property>
  <property>
    <name>xasecure.audit.destination.auditserver</name>
    <value>true</value>
  </property>
  <property>
    <name>xasecure.audit.destination.auditserver.url</name>
    <value>http://ranger-audit-ingestor:7081</value>
  </property>
</configuration>
```

The full property reference is on the [Audit framework](../services/audit/index.md) page.

## Policy refresher

`PolicyRefresher` is a daemon thread plus a timer. At startup it calls `loadRoles()` and
`loadPolicy()` synchronously so the plugin has policies before serving its first request; then a
timer enqueues a download trigger every `policy.pollIntervalMs`.

* **Download.** `RangerAdminRESTClient.getServicePoliciesIfUpdated(lastKnownVersion,
  lastActivationTime)` calls `/service/plugins/secure/policies/download/{serviceName}` (or the
  non-`secure` path when Kerberos is off) with `lastKnownVersion`, `pluginId`, `clusterName`,
  `supportsPolicyDeltas`, and `pluginCapabilities`. Admin answers with `ServicePolicies` only when
  the version changed.
* **Apply.** `RangerBasePlugin.setPolicies()` builds a new `RangerPolicyEngineImpl` (or applies
  deltas to a copy of the current one), attaches enrichers, and swaps it in.
* **Cache.** The applied policies are written to `<policy.cache.dir>/<appId>_<serviceName>.json`.
  If Admin cannot be reached at startup, the cache is read instead. If Admin reports that the
  service no longer exists, the cache file is renamed aside and the plugin runs with no policies.
* **Failure.** Any other error is logged and the plugin keeps the last known policies; the next
  poll tries again.
* **Roles, tags, user store, GDS.** `RangerRolesProvider`, `RangerTagEnricher`,
  `RangerUserStoreEnricher`, and `RangerGdsEnricher` each run the same download-and-cache pattern
  against their own endpoints, using the polling intervals from their enricher options.
* **Force refresh.** The plugin exposes `refreshPoliciesAndTags()` for hosts that want to refresh
  after a `GRANT`.

Every download is reported back to Admin, which is what populates the **Audit > Plugins** and
**Plugin Status** tabs in the UI.

## Request and result

`RangerAccessRequest` describes what is being attempted. The host adapter fills a
`RangerAccessRequestImpl`:

| Field | Meaning |
|-------|---------|
| `resource` | A `RangerAccessResource` (map of resource level to value, for example `database=finance, table=orders`), optionally with an owner. |
| `accessType` | The access type from the service definition (`select`, `write`, ...). A special value `_any` asks "any access at all", used for existence checks. |
| `user`, `userGroups`, `userRoles` | The principal. Roles are normally resolved by the engine from Ranger roles. |
| `accessTime` | When the access happens (drives validity schedules and time-of-day conditions). |
| `clientIPAddress`, `remoteIPAddress`, `forwardedAddresses` | Network origin, used by IP conditions and audits. |
| `clientType`, `action`, `requestData`, `sessionId` | Host-specific details recorded in audits (for Hive: `HIVESERVER2`, the command type, and the query text). |
| `clusterName`, `clusterType` | From configuration; used by cluster conditions. |
| `context` | A map populated by context enrichers (tags, user attributes, GDS info) and by the host (for example resources accessed together). |
| `resourceMatchingScope` | `SELF` or `SELF_OR_DESCENDANTS`, for checks such as "may the user access anything under this database". |

`RangerAccessResult` is what comes back:

| Field | Meaning |
|-------|---------|
| `isAccessDetermined`, `isAllowed` | The decision. `isAccessDetermined=false` means no policy matched (see fallback in [Policy model](policy-model.md#evaluation-order)). |
| `policyId`, `policyVersion`, `policyPriority`, `zoneName` | Which policy decided, from which zone. |
| `isAudited`, `auditPolicyId`, `auditLogId` | Whether an audit record is produced and which policy's audit flag decided that. |
| `reason` | Free text, for example `superuser` or `matched deny-all-else policy`. |
| `maskType`, `maskedValue`, `maskCondition` | For data-mask evaluations. |
| `filterExpr` | For row-filter evaluations. |
| `datasets`, `projects`, `allowedByDatasets`, `allowedByProjects` | GDS information. |
| `evaluatedPoliciesCount` | Diagnostics. |

## Context enrichers and condition evaluators

**Context enrichers** run before evaluation and add information to `request.context`. They are
declared in the service definition (`contextEnrichers`) or enabled implicitly by configuration:

`RangerTagEnricher`
:   Adds the tags attached to the resource, from the tag service linked to this service. Options:
    `tagRetrieverClassName` (`RangerAdminTagRetriever` in the shipped tag service definition;
    `RangerFileBasedTagRetriever` reads a file), `tagRefresherPollingInterval` (ms, default 60000), `disableTrieLookupPrefilter`.

`RangerUserStoreEnricher`
:   Adds user and group attributes, Ranger groups, and the email-to-user mapping. Options:
    `userStoreRetrieverClassName` (default `RangerAdminUserStoreRetriever`),
    `userStoreRefresherPollingInterval` (ms, default 3600000).

`RangerGdsEnricher`
:   Adds the Governed Data Sharing datasets and projects that include the resource. Options:
    `retrieverClassName` (default `RangerAdminGdsInfoRetriever`), `refresherPollingInterval`
    (ms, default 60000).

Geolocation providers (`RangerFileBasedGeolocationProvider`)
:   Add location attributes for the client IP. Options are provider-specific.

**Condition evaluators** implement `RangerConditionEvaluator` and are referenced from the
service definition's `policyConditions` (by class name in `evaluator`). Shipped evaluators in
`org.apache.ranger.plugin.conditionevaluator` include `RangerIpMatcher`, `RangerTimeOfDayMatcher`,
`RangerAccessedFromClusterCondition`, `RangerAccessedFromClusterTypeCondition` (and their
`Not` variants), `RangerContextAttributeValueInCondition` / `NotInCondition`,
`RangerTagsAllPresentConditionEvaluator`, `RangerAnyOfExpectedTagsPresentConditionEvaluator`,
`RangerNoneOfExpectedTagsPresentConditionEvaluator`, `RangerHiveResourcesAccessedTogetherCondition`,
`RangerScriptConditionEvaluator` (JavaScript expressions over `USER`, `TAG`, `RESOURCE`, and
request attributes), and `RangerScriptTemplateConditionEvaluator`. See
[Policy conditions](../features/policies/policy-conditions.md) for usage and
[Custom conditions and enrichers](../dev/custom-conditions-enrichers.md) for writing your own.

## Service definition model

A service definition (`RangerServiceDef`) is the JSON contract between a plugin, Ranger Admin's
UI, and the policy engine. Top-level fields:

`name`, `displayName`, `label`, `description`
:   Identity of the service type. `name` is what plugins pass as `serviceType`.

`implClass`
:   `RangerBaseService` subclass that Ranger Admin loads for test-connection and resource lookup.

`options`
:   Map of service-wide switches: `enableDenyAndExceptionsInPolicies`, `enableTagBasedPolicies`,
    `enableImplicitConditionExpression`, `rrnResourceSepChar`.

`configs`
:   List of `RangerServiceConfigDef`: the properties an admin fills in when creating a service.
    Each has `name`, `type`, `subType`, `mandatory`, `defaultValue`, `validationRegEx`, `uiHint`,
    `label`.

`resources`
:   List of `RangerResourceDef`: the resource hierarchy. Each has `name`, `type`, `level`, `parent`,
    `mandatory`, `lookupSupported`, `recursiveSupported`, `excludesSupported`, `matcher`
    (`RangerDefaultResourceMatcher`, `RangerPathResourceMatcher`, `RangerURLResourceMatcher`),
    `matcherOptions` (`wildCard`, `ignoreCase`, `pathSeparatorChar`), `validationRegEx`, `uiHint`,
    `accessTypeRestrictions`, `isValidLeaf`.

`accessTypes`
:   List of `RangerAccessTypeDef`: the permissions. Each has `name`, `label`, `impliedGrants` (for
    example Hive `all` implies every other type), and `category` (`CREATE`, `READ`, `UPDATE`,
    `DELETE`, `MANAGE`).

`policyConditions`
:   List of `RangerPolicyConditionDef`: conditions available in the policy editor. Each has `name`,
    `evaluator` class, `evaluatorOptions`, `uiHint`, `label`.

`contextEnrichers`
:   List of `RangerContextEnricherDef`: enrichers to run. Each has `name`, `enricher` class,
    `enricherOptions`.

`enums`
:   List of `RangerEnumDef`: named value lists (`name`, `elements`, `defaultIndex`) usable by
    `configs` of type `enum`.

`dataMaskDef`
:   `RangerDataMaskDef`: `maskTypes` (each with `name`, `label`, `transformer` expression,
    `dataMaskOptions`), plus the subset of `accessTypes` and `resources` that masking applies to.

`rowFilterDef`
:   `RangerRowFilterDef`: the `accessTypes` and `resources` that row filtering applies to.

Every item carries an `itemId` that must stay stable across versions of the definition; Ranger
Admin uses it to migrate existing policies when a definition is updated. The Hive definition,
[`ranger-servicedef-hive.json`](https://github.com/apache/ranger/blob/master/agents-common/src/main/resources/service-defs/ranger-servicedef-hive.json),
is a good reference for a full definition with masking and row filtering; `ranger-servicedef-tag.json`
shows a definition whose only purpose is enrichers and conditions.

## Shim and class loader isolation

Plugins bring their own dependency versions (Jersey, Jackson, HTTP client, and so on) that may
conflict with the host service's. To avoid this, each plugin archive contains two layers:

* `lib/ranger-<type>-plugin-shim-<version>.jar` and `ranger-plugin-classloader-<version>.jar` go
  on the host's normal classpath. The shim contains only thin proxy classes.
* `lib/ranger-<type>-plugin-impl/` holds the real plugin (`ranger-<type>-plugin`,
  `ranger-plugins-common`, `ranger-audit-core`, `ranger-audit-dest-auditserver`,
  `ranger-authz-api`, `ranger-plugins-cred`, `ranger-common-utils`, `ugsync-util`) and all their
  dependencies.

The shim class (for Hive, `RangerHiveAuthorizerFactory` in `ranger-hive-plugin-shim`) creates a
`RangerPluginClassLoader` for the plugin type, which locates the `ranger-<type>-plugin-impl`
directory next to the shim jar and loads every jar in it. The class loader is child-first: it looks
in the impl directory before delegating to the host's class loader, so the plugin sees its own
dependency versions while still being able to load host classes (`HiveConf`, Hadoop
`UserGroupInformation`). Around every call into the implementation, the shim calls
`activate()` (which sets the thread context class loader to the plugin loader) and `deactivate()`
(which restores it):

```java title="RangerHiveAuthorizerFactory.java (shim, excerpt)"
rangerPluginClassLoader = RangerPluginClassLoader.getInstance("hive", this.getClass());

Class<HiveAuthorizerFactory> cls = (Class<HiveAuthorizerFactory>) Class.forName(
        "org.apache.ranger.authorization.hive.authorizer.RangerHiveAuthorizerFactory", true, rangerPluginClassLoader);

activatePluginClassLoader();
rangerHiveAuthorizerFactoryImpl = cls.newInstance();
deactivatePluginClassLoader();
```

The shim and the implementation use the same fully qualified class name; only the class loader
differs. Plugins that run in a process without dependency conflicts (or that you embed in your own
application) can skip the shim and depend on `ranger-plugins-common` directly.

## Audit handler

`RangerDefaultAuditHandler` implements `RangerAccessResultProcessor`. For every result with
`isAudited=true` it builds an `AuthzAuditEvent` and hands it to the audit framework
(`AuditProviderFactory` in `agents-audit`). The event fields, as serialized to the audit store,
are: `repoType`, `repo` (service name), `reqUser`, `evtTime`, `access`, `resource`, `resType`,
`action`, `result`, `agent`, `policy`, `policy_version`, `reason`, `enforcer`, `sess`, `cliType`,
`cliIP`, `reqData`, `agentHost`, `logType`, `id`, `seq_num`, `event_count`, `event_dur_ms`, `tags`,
`datasets`, `projects`, `cluster_name`, `zone_name`, and `additional_info`. When the summary
queue (`AuditSummaryQueue`, `summary.interval.ms`) is enabled, repeated identical events within
the interval are collapsed into one record with `event_count` and `event_dur_ms`.

The framework pipeline is asynchronous so audit never blocks the request:

```mermaid
flowchart LR
  H[RangerDefaultAuditHandler] --> Q[AuditAsyncQueue]
  Q --> B[AuditBatchQueue]
  B --> S[AuditFileSpool<br/>local disk on failure]
  S --> D1[Solr / OpenSearch /<br/>Elasticsearch destination]
  S --> D2[HDFS / S3 / ADLS<br/>destination]
  S --> D3[Audit server<br/>REST destination]
  S --> D4[Log4j destination]
```

Hosts that need custom audit behavior (HDFS writes one event for the several checks of one file-system operation, Hive attaches the query
text, multi-resource requests log once) subclass `RangerDefaultAuditHandler` or use
`RangerMultiResourceAuditHandler`. Users, groups, or roles listed in the
`ranger.plugin.<type>.audit.exclude.*` properties are never audited; audit filters defined in the
service configuration (`ranger.plugin.audit.filters`) give finer control. See
[Audit filters](../services/audit/audit-filters.md).

## Chained plugins

A plugin can consult the policies of another service after its own. The Hive plugin, for example,
can chain the HDFS plugin so that a Hive URL grant is checked against HDFS policies. Configure
`ranger.plugin.<type>.chained.services=<otherService>` and
`ranger.plugin.<type>.chained.services.<otherService>.impl=<RangerChainedPlugin subclass>`. The
chained plugin has its own refresher and policy engine; `RangerBasePlugin` merges its result with
the primary result. Set `ranger.plugin.<type>.bypass.chained.plugin.evaluation.if.access.is.determined=true`
to skip the chained evaluation once the primary engine has decided.

## Further reading

* [Policy model](policy-model.md): how the engine orders and combines policies.
* [Ranger architecture](architecture.md): where plugins sit in the overall system.
* [Custom plugins](../plugins/custom-plugin.md): building a plugin for your own service.
* [Plugins overview](../plugins/index.md): the plugins shipped with Ranger.
* [authz-api and Ranger PDP](../dev/authz-api.md): using Ranger without embedding a plugin.
* [Java client](../features/client-interface/java.md): managing policies programmatically.
* Source: [`agents-common`](https://github.com/apache/ranger/tree/master/agents-common),
  [`ranger-plugin-classloader`](https://github.com/apache/ranger/tree/master/ranger-plugin-classloader),
  [`agents-audit`](https://github.com/apache/ranger/tree/master/agents-audit),
  [`ranger-examples/plugin-sampleapp`](https://github.com/apache/ranger/tree/master/ranger-examples/plugin-sampleapp).
