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

# Shim, classloader, and enable script

## Why a shim

The host service (Kafka, HBase, Hive, ...) loads one small jar from `lib/`; that class creates a child-first `RangerPluginClassLoader` over
`lib/ranger-<svc>-plugin-impl/` so Ranger's dependency versions never clash with the host's. Every call is executed with the plugin classloader as the
thread context classloader.

## Template (`ranger-kafka-plugin-shim/.../RangerKafkaAuthorizer.java`)

```java
public class RangerKafkaAuthorizer implements Authorizer {
    private static final Logger logger = LoggerFactory.getLogger(RangerKafkaAuthorizer.class);

    private static final String RANGER_PLUGIN_TYPE                     = "kafka";
    private static final String RANGER_KAFKA_AUTHORIZER_IMPL_CLASSNAME = "org.apache.ranger.authorization.kafka.authorizer.RangerKafkaAuthorizer";

    private Authorizer              rangerKafkaAuthorizerImpl;
    private RangerPluginClassLoader pluginClassLoader;

    public RangerKafkaAuthorizer() {
        this.init();
    }

    private void init() {
        logger.debug("==> RangerKafkaAuthorizer.init()");

        try {
            pluginClassLoader = RangerPluginClassLoader.getInstance(RANGER_PLUGIN_TYPE, this.getClass());

            @SuppressWarnings("unchecked")
            Class<Authorizer> cls = (Class<Authorizer>) Class.forName(RANGER_KAFKA_AUTHORIZER_IMPL_CLASSNAME, true, pluginClassLoader);

            try (PluginClassLoaderActivator ignored = new PluginClassLoaderActivator(pluginClassLoader, "init")) {
                rangerKafkaAuthorizerImpl = cls.newInstance();
            }
        } catch (Exception e) {
            logger.error("Error Enabling RangerKafkaPlugin", e);

            throw new IllegalStateException("Error Enabling RangerKafkaPlugin", e);
        }

        logger.debug("<== RangerKafkaAuthorizer.init()");
    }

    @Override
    public List<AuthorizationResult> authorize(AuthorizableRequestContext requestContext, List<Action> actions) {
        try (PluginClassLoaderActivator ignored = new PluginClassLoaderActivator(pluginClassLoader, "authorize")) {
            return rangerKafkaAuthorizerImpl.authorize(requestContext, actions);
        }
    }
}
```

Rules: same FQCN as the impl class; plugin type equals the service-def name; shim modules use the `logger` field name historically (either is fine); only
`ranger-plugin-classloader` and the host API (`provided`) as dependencies.

`ranger-plugin-classloader` classes: `RangerPluginClassLoader.getInstance(pluginType, callerClass)`, `RangerPluginClassLoaderUtil` (locates
`ranger-<svc>-plugin-impl` next to the shim jar), `PluginClassLoaderActivator` (`AutoCloseable` that swaps and restores the TCCL).

## `*-changes.cfg` format

`propertyName  value  mod create-if-not-exists` (or `var` for variables), tokens `%NAME%` substituted from `install.properties` by
`agents-installer/src/main/java/org/apache/ranger/utils/install/XmlConfigChanger.java`. Three files per plugin: `ranger-<svc>-security-changes.cfg`,
`ranger-<svc>-audit-changes.cfg`, `ranger-policymgr-ssl-changes.cfg`, plus optional host-config edits like `hiveserver2-site-changes.cfg`.

## `enable-agent.sh`

`agents-common/scripts/enable-agent.sh` is the single implementation for all plugins; the distro assembly copies it as `enable-<svc>-plugin.sh` and
`disable-<svc>-plugin.sh`, and the script derives component and action from its own filename. It reads `install.properties` with `getInstallProperty`
(component file first, then global), requires root and `JAVA_HOME`, links the shim jar and `ranger-<svc>-plugin-impl` into the host's lib dir, runs
`XmlConfigChanger` over the `conf.templates/enable/*.cfg`, and creates the jceks credential store with `credentialbuilder`.
`upgrade-plugin.sh` / `upgrade-plugin.py` live beside it. Do not write per-plugin enable scripts unless the host cannot use the shared one
(the two exceptions in tree are `plugin-kms` and `plugin-trino`).
