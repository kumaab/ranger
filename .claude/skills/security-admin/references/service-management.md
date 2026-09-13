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

# Service management: defs, connection test, resource lookup

## Service-def bootstrap

`agents-common/.../plugin/store/EmbeddedServiceDefsUtil` loads `service-defs/ranger-servicedef-*.json` at startup and creates anything missing.
Gated by `ranger.supportedcomponents` (empty means all). It also creates the singleton `_gds` service for the `gds` def.

## `ServiceMgr` and the Admin-side plugin classloader

`biz/ServiceMgr` resolves a `RangerServiceDef` to its `RangerBaseService` implementation:

```java
Class<? extends RangerBaseService> cls = serviceTypeClassMap.get(serviceType);   /* static memo, synchronized double-check */
String clsName = serviceDef.getImplClass();                                      /* empty -> RangerDefaultService.class */
URL[] pluginFiles = getPluginFilesForServiceType(serviceType);                   /* classpath dir ranger-plugins/<serviceType> */
URLClassLoader clsLoader = new URLClassLoader(pluginFiles, Thread.currentThread().getContextClassLoader());
cls = Class.forName(clsName, true, clsLoader);
```

Any failure logs a WARN and falls back to `RangerDefaultService`. A missing plugin jar under `ranger-plugins/<svc>` degrades silently to
"no lookup" rather than an error, which is the usual cause of empty resource autocomplete in the UI.

## Connection test and lookup

Both run through `common/TimedExecutor` with `ServiceMgr`'s inner `TimedCallable<T>` subclasses `LookupCallable` / `ValidateCallable`.
`TimedCallable.call()` swaps the thread context classloader to `svc.getClass().getClassLoader()` and restores it in `finally`; do the same
whenever you touch plugin classes from Admin.

| Operation | REST entry | `ServiceMgr` method | Default timeout | Per-service config override |
|---|---|---|---|---|
| Test connection | `POST /plugins/services/validateConfig` | `validateConfig(RangerService, ServiceStore)` | 10000 ms | `validate.config.timeout.value.in.ms` |
| Resource lookup | `POST /plugins/services/lookupResource/{serviceName}` | `lookupResource(serviceName, ResourceLookupContext, ServiceStore)` | 1000 ms | `resource.lookup.timeout.value.in.ms` |

Override keys are read from the **service instance's config map**, not from `ranger-admin-site.xml`. Executor sizing in `common/TimedExecutorConfigurator`:
`ranger.timed.executor.max.threadpool.size` (default 10), `ranger.timed.executor.queue.size` (default 100).

## Zone-scoped admin checks

`ServiceMgr.isZoneAdmin(zoneName)` / `isZoneAuditor(zoneName)` (not on `RangerBizUtil`): `adminUsers` contains the login id, else group membership
via `XXGroupUserDao.findByUserId` (with `GROUP_PUBLIC` always added), else `adminRoles` via `isUserOrUserGroupsInRole`.

## Accessors

`getRangerServiceByName(String, ServiceStore)`, `getRangerServiceByService(RangerService, ServiceStore)`. Use these instead of instantiating a `RangerBaseService`.
Service create/update/delete and the `x_service_version_info` bookkeeping live in `ServiceDBStore` (`createService`, `updateService`, `deleteService`).
