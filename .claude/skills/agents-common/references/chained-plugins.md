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

# Chained plugins

`RangerChainedPlugin` (`plugin/service/`) lets one plugin consult a second service's policies in the same request. It is a framework hook only:
the single subclass in tree is `NoopChainedPlugin` inside `agents-common/src/test/java/.../service/TestRangerChainedPlugin.java`. There is no
shipped Hive -> HDFS chained implementation in this repository.

## Configuration (`RangerBasePlugin.initChainedPlugins()`)

```
ranger.plugin.<svc>.chained.services                                        = svcA,svcB
ranger.plugin.<svc>.chained.services.svcA.impl                              = com.example.MyChainedPlugin
ranger.plugin.<svc>.bypass.chained.plugin.evaluation.if.access.is.determined = false
```

The impl class is instantiated reflectively with a two-arg constructor `(RangerBasePlugin rootPlugin, String serviceName)`; it must chain up to
`RangerChainedPlugin(rootPlugin, serviceType, serviceName)`. The bypass flag is read from the root plugin's prefix into `skipAccessCheckIfAlreadyDetermined`.

## Contract

Abstract: `isAccessAllowed(RangerAccessRequest)`, `isAccessAllowed(Collection<RangerAccessRequest>)`, `getResourceACLs(request)`, `getResourceACLs(request, policyType)`.
Overridable, not abstract: `init()` (delegates to the inner plugin), `evalDataMaskPolicies` / `evalRowFilterPolicies` (default `null`),
`isAuthorizeOnlyWithChainedPlugin()` (false), `buildChainedPlugin(serviceType, serviceName, appId)` (default `new RangerBasePlugin(...)`).
`RangerBasePlugin.init()` calls `init()` on every chained plugin after starting its own `PolicyRefresher`.

## Merge rules

`RangerBasePlugin.isAccessAllowed(...)` evaluates its own repositories, then each chained plugin (skipped when the result is already determined and the
bypass flag is set), folding with `updateResultFromChainedResult(result, chainedResult)`: a chained result is ignored unless `getIsAccessDetermined()`;
it wins on higher policy priority, or when the base is undetermined, or when the base is "not allowed with no matching policy"; deny beats allow at equal priority.
`evalDataMaskPolicies` / `evalRowFilterPolicies` fold the same way. `getResourceACLs` merges through `getMergedResourceACLs(base, chained)`: USER/GROUP/ROLE
entries from the chained ACLs override the base entries while `datasets`/`projects` are unioned; `isAuthorizeOnlyWithChainedPlugin()` replaces the base ACLs.

Tests: `TestRangerChainedPlugin`, `TestRangerBasePlugin`, `TestPolicyACLs`.
