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

# Extension point templates

## Policy condition evaluator

Contract (`conditionevaluator/RangerConditionEvaluator`): `setConditionDef`, `setPolicyItemCondition`, `setServiceDef`, `init()`, `isMatched(RangerAccessRequest)`.
`RangerAbstractConditionEvaluator` stores `serviceDef`, `conditionDef`, `condition`. Policy-item data is `condition.getValues()`; evaluator configuration is
`conditionDef.getEvaluatorOptions()`.

```java
public class RangerAccessedFromClusterCondition extends RangerAbstractConditionEvaluator {
    private static final Logger LOG = LoggerFactory.getLogger(RangerAccessedFromClusterCondition.class);

    private boolean isAlwaysTrue;

    @Override
    public void init() {
        LOG.debug("==> RangerAccessedFromClusterCondition.init({})", condition);

        super.init();

        isAlwaysTrue = condition == null || CollectionUtils.isEmpty(condition.getValues());

        LOG.debug("<== RangerAccessedFromClusterCondition.init({})", condition);
    }

    @Override
    public boolean isMatched(RangerAccessRequest request) {
        final boolean ret;

        if (isAlwaysTrue || request.getClusterName() == null) {
            ret = isAlwaysTrue;
        } else {
            ret = condition.getValues().contains(request.getClusterName());
        }

        return ret;
    }
}
```

Instantiated by `policyevaluator/RangerCustomConditionEvaluator.newConditionEvaluator(className)` via reflection: public no-arg constructor required.
Disabled globally by `RangerPolicyEngineOptions.disableCustomConditions`. Script conditions: `RangerScriptConditionEvaluator` (options `engineName`, JSON context flag).

## Context enricher

Contract (`contextenricher/RangerContextEnricher`): `setEnricherDef`, `setServiceName`, `setServiceDef`, `setAppId`, `init()`, `enrich(request)`,
`enrich(request, dataStore)`, `preCleanup()`, `cleanup()`, `getName()`.

`RangerAbstractContextEnricher` gives: `init()` registers with `RangerAuthContext`; `preCleanup()` unregisters; option readers `getOption(name[, default])`,
`getBooleanOption`, `getCharOption`, `getLongOption` (from `enricherDef.getEnricherOptions()`); config readers `getConfig`, `getIntConfig`, `getBooleanConfig`,
`getPropertyPrefix()` (falls back to `ranger.plugin.<serviceDefName>`); `getPluginContext()`, `getAuthContext()`, `notifyAuthContextChanged()`.

Minimal enricher (`ranger-examples/conditions-enrichers/.../RangerSampleCountryProvider`):

```java
public class RangerSampleCountryProvider extends RangerAbstractContextEnricher {
    private static final Logger LOG = LoggerFactory.getLogger(RangerSampleCountryProvider.class);

    private String     contextName = "COUNTRY";
    private Properties userCountryMap;

    @Override
    public void init() {
        LOG.debug("==> RangerSampleCountryProvider.init({})", enricherDef);

        super.init();

        contextName = getOption("contextName", "COUNTRY");

        String dataFile = getOption("dataFile", "/etc/ranger/data/userCountry.txt");

        userCountryMap = readProperties(dataFile);

        LOG.debug("<== RangerSampleCountryProvider.init({})", enricherDef);
    }

    @Override
    public void enrich(RangerAccessRequest request) {
        if (request != null && userCountryMap != null) {
            Map<String, Object> context = request.getContext();
            String              country = userCountryMap.getProperty(request.getUser());

            if (context != null && !StringUtils.isEmpty(country)) {
                context.put(contextName, country);
            }
        }
    }
}
```

Refresher pattern (`RangerTagEnricher`, mirrored by `RangerUserStoreEnricher`, `RangerGdsEnricher`) for enrichers that pull data from Admin:

1. Options: `<x>RetrieverClassName`, `<x>RefresherPollingInterval` (default 60000 ms).
2. `Class.forName(retrieverClassName).newInstance()` -> `Ranger<X>Retriever`; set `serviceName/serviceDef/appId/pluginConfig/pluginContext`; `retriever.init(options)`.
3. Cache file `String.format("%s_%s_tag.json", appId, serviceName)` under `<propertyPrefix>.policy.cache.dir`.
4. `Ranger<X>Refresher extends Thread` (daemon) blocks on a `BlockingQueue<DownloadTrigger>`, loads, calls `trigger.signalCompletion()` in `finally`.
5. `Timer("policyDownloadTimer", true).schedule(new DownloaderTask(queue), pollingIntervalMs, pollingIntervalMs)`.
6. Guard the swapped data with `RangerReadWriteLock` (`try (RangerReadWriteLock.RangerLock readLock = lock.getReadLock()) { ... }`).
7. `enrich` writes through `RangerAccessRequestUtil.setRequestTagsInContext(request.getContext(), matchedTags)` (or the `UserStore` equivalent).

Retriever base: `abstract void init(Map<String, String> options)` and `abstract ServiceTags retrieveTags(long lastKnownVersion, long lastActivationTimeInMillis)`;
`RangerAdminTagRetriever` uses `pluginContext.getAdminClient()` or `createAdminClient(pluginConfig)` and rethrows `ClosedByInterruptException` as `InterruptedException`.

## Resource matcher

Contract (`resourcematcher/RangerResourceMatcher`): `setResourceDef`, `setPolicyResource`, `init()`, `isMatchAny()`,
`getMatchType(Object resource, ResourceElementMatchingScope scope, Map<String, Object> evalContext)`, `isMatch(...)`, `isCompleteMatch(String, evalContext)`, `getNeedsDynamicEval()`.

`RangerAbstractResourceMatcher` option constants and defaults: `OPTION_IGNORE_CASE` (`ignoreCase`, true), `OPTION_WILD_CARD` (`wildCard`, true),
`OPTION_QUOTED_CASE_SENSITIVE` (false), `OPTION_QUOTE_CHARS` (`"`), `OPTION_REPLACE_TOKENS` (true), `OPTION_TOKEN_DELIMITER_START/END/ESCAPE/PREFIX` (`{`, `}`, `\`, empty),
`OPTION_REPLACE_REQ_EXPRESSIONS` (true); `WILDCARD_ASTERISK`, `WILDCARD_QUESTION_MARK`. `RangerPathResourceMatcher.OPTION_PATH_SEPARATOR` (`pathSeparatorChar`).

`init()` reads options, copies `policyResource.getValues()` into `policyValues`, records `policyIsExcludes`, validates delimiters, then
`resourceMatchers = buildResourceMatchers()` and `isMatchAny = CollectionUtils.isEmpty(resourceMatchers)`.

```java
public class RangerDefaultResourceMatcher extends RangerAbstractResourceMatcher {
    private static final Logger LOG = LoggerFactory.getLogger(RangerDefaultResourceMatcher.class);

    @Override
    public boolean isMatch(Object resource, ResourceElementMatchingScope matchingScope, Map<String, Object> evalContext) {
        ResourceElementMatchType matchType = getMatchType(resource, matchingScope, evalContext);
        boolean                  ret       = ResourceMatcher.isMatch(matchType, matchingScope);

        return ret;
    }

    @Override
    public ResourceElementMatchType getMatchType(Object resource, ResourceElementMatchingScope matchingScope, Map<String, Object> evalContext) {
        ResourceElementMatchType ret = ResourceElementMatchType.NONE;
        ...
        ret = applyExcludes(allValuesRequested, ret);   /* always the last step */

        return ret;
    }

    public StringBuilder toString(StringBuilder sb) { ... }
}
```

Per-value matchers are chosen by `RangerAbstractResourceMatcher.getMatcher(policyValue)` from `{CaseSensitive,CaseInsensitive,QuotedCaseSensitive} x
{StringMatcher, StartsWithMatcher, EndsWithMatcher, WildcardMatcher}` based on `*`/`?` position, sorted by `ResourceMatcher.PriorityComparator`, wrapped in
`ResourceMatcherWrapper`. Override `buildResourceMatchers()` to add matcher kinds (see `RangerPathResourceMatcher`: `Path*`, `RecursiveWildcard`, `RecursivePath`).
Package-private `ResourceMatcher` provides `isMatch/isPrefixMatch/isChildMatch(resourceValue, evalContext)`, `getPriority()`, token/expression expansion.

Register per resource element: `"matcher": "<fqcn>", "matcherOptions": { ... }`.

## Chained plugin

See [chained-plugins.md](chained-plugins.md): abstract surface (`init()` is concrete), two-arg reflective constructor, the three config keys, merge rules.
