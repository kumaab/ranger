---
name: ranger-plugin
description: How a Ranger service plugin module is structured and wired - plugin-<svc> impl module (Ranger<Svc>Authorizer, RangerService<Svc>, client lookup), ranger-<svc>-plugin-shim with RangerPluginClassLoader delegation, conf/*-changes.cfg, scripts/install.properties, service-def registration, distro assembly and root/distro pom profiles. Use when creating a new plugin, adding a shim, or changing plugin packaging/install.
---
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

# Ranger service plugins

A plugin for service `<svc>` is up to three Maven modules plus packaging: the impl module (`plugin-<svc>`, or legacy `<svc>-agent`), the shim
(`ranger-<svc>-plugin-shim`), and the shared `ranger-plugin-classloader`. The framework it builds on is documented in `agents-common`.
Reference implementations: `plugin-kafka` (plain `RangerBasePlugin`), `hive-agent` (nested `RangerHivePlugin extends RangerBasePlugin`, masking, row filter),
`hdfs-agent` (top-level `RangerHdfsPlugin`), `ranger-examples/plugin-sampleapp` (minimal). How each attaches to its host: [references/host-hook-points.md](references/host-hook-points.md).
Audit transport and `xasecure.audit.*` keys: `ranger-audit-server`.

## Impl module layout

```
plugin-<svc>/
  pom.xml                                  artifactId ranger-<svc>-plugin, jar
  conf/ranger-<svc>-security-changes.cfg   ranger.plugin.<svc>.* keys, %TOKEN% substitution
  conf/ranger-<svc>-audit-changes.cfg      xasecure.audit.* destinations (copy from a sibling plugin)
  conf/ranger-policymgr-ssl-changes.cfg    4 keystore/truststore lines
  [disable-conf/, template/]
  scripts/install.properties               COMPONENT_INSTALL_DIR_NAME, POLICY_MGR_URL, REPOSITORY_NAME, XAAUDIT.*, SSL_*, CUSTOM_USER/GROUP
  src/main/java/org/apache/ranger/
    authorization/<svc>/authorizer/
      Ranger<Svc>Authorizer.java           the enforcement point the host service loads
      Ranger<Svc>AccessRequest.java        extends RangerAccessRequestImpl
      Ranger<Svc>Resource.java             extends RangerAccessResourceImpl
      Ranger<Svc>AuditHandler.java         extends RangerDefaultAuditHandler
      [Ranger<Svc>Plugin]                  nested or top-level, extends RangerBasePlugin when extra config is needed
    services/<svc>/
      RangerService<Svc>.java              extends RangerBaseService: validateConfig(), lookupResource(), getDefaultRangerPolicies()
      client/<Svc>Client.java              connection + resource listing (extends BaseClient when JDBC-like)
      client/<Svc>ConnectionMgr.java, <Svc>ResourceMgr.java
  src/test/resources/{logback.xml, ranger-<svc>-security.xml, <svc>-policies.json}
```

## Non-negotiables

- `Ranger<Svc>Authorizer` in the shim has the **same FQCN** as the impl class and delegates every public method through
  `try (PluginClassLoaderActivator ignored = new PluginClassLoaderActivator(pluginClassLoader, "<method>"))`. One Java file per shim module.
- Plugin type string (`RangerPluginClassLoader.getInstance("<svc>", getClass())`) must equal the service-def `name` and the `lib/ranger-<svc>-plugin-impl` folder name.
- Config keys are `ranger.plugin.<svc>.*`; files `ranger-<svc>-security.xml`, `ranger-<svc>-audit.xml`, `ranger-<svc>-policymgr-ssl.xml` generated from the `*-changes.cfg` by
  `agents-installer` (`XmlConfigChanger`) using `enable-agent.sh`. Never hand-write the XML into `conf/`.
- Resource names set on `RangerAccessResourceImpl.setValue(name, value)` must match `resources[].name` in the service-def.
- Audit through `RangerDefaultAuditHandler` (or a subclass); set with `plugin.setResultProcessor(...)` before `init()`.
- Excluded from the impl assembly (host provides them): jackson, jersey, hk2, slf4j, log4j. Check `distro/src/main/assembly/plugin-kafka.xml` before adding a dependency.
- `plugin-kms` is the odd one: `RangerKmsAuthorizer implements KeyACLs` is loaded by Hadoop KMS itself (`hadoop.kms.security.authorization.manager`), with
  `RangerKMSPlugin` built as `super("kms", "kms")`; see `ranger-kms`.
- No shim for Trino (its authorizer moved to the Trino repo in RANGER-4859; only `RangerServiceTrino` remains here), NiFi, NiFi Registry, Kudu, Schema Registry
  (Admin-side lookup only) or nestedstructure (library API). Every other plugin has one.

## `RangerService<Svc>` (Admin-side lookup and test-connection)

```java
public class RangerServiceKafka extends RangerBaseService {
    private static final Logger LOG = LoggerFactory.getLogger(RangerServiceKafka.class);

    @Override public void init(RangerServiceDef serviceDef, RangerService service) { super.init(serviceDef, service); }
    @Override public Map<String, Object> validateConfig() throws Exception { return ServiceKafkaConnectionMgr.connectionTest(serviceName, configs); }
    @Override public List<String> lookupResource(ResourceLookupContext context) throws Exception { return serviceKafkaClient.getResources(context); }
    @Override public List<RangerPolicy> getDefaultRangerPolicies() throws Exception { /* super + lookUpUser / GROUP_PUBLIC items */ }
}
```

This class runs inside Ranger Admin (referenced by `implClass` in the service-def), so it may only depend on what Admin ships.

## Checklist: new plugin

Full list with file paths in [references/new-plugin-checklist.md](references/new-plugin-checklist.md). Short form:

1. `plugin-<svc>/` module (pom, authorizer classes, service class, conf `*-changes.cfg`, `scripts/install.properties`, tests).
2. `agents-common/src/main/resources/service-defs/ranger-servicedef-<svc>.json` + `EmbeddedServiceDefsUtil` registration (see `agents-common`).
3. `ranger-<svc>-plugin-shim/` (pom + one delegating class). [references/shim.md](references/shim.md).
4. `distro/src/main/assembly/plugin-<svc>.xml`; `distro/pom.xml` dependencies (alphabetical), descriptor in the `all` profile, new `ranger-<svc>-plugin` profile.
5. Root `pom.xml`: both modules in `all`, `linux`, `sign-artifacts` module lists (alphabetical; sortpom fails `validate` otherwise) and a `ranger-<svc>-plugin` profile copied from `ranger-kafka-plugin`.
6. Optional: `dev-support/ranger-docker/Dockerfile.ranger-<svc>` + compose file, `mkdocs/docs/plugins/<svc>.md` + nav entry.

## Verify

```bash
mvn -pl plugin-<svc>,ranger-<svc>-plugin-shim -am -DskipTests verify
mvn -pl distro -Pranger-<svc>-plugin -DskipTests package      # produces target/ranger-<ver>-<svc>-plugin.tar.gz
```

Then untar, edit `install.properties`, run `enable-<svc>-plugin.sh` on a host with the service, and check `ranger.plugin.<svc>.service.name` policies download
into `ranger.plugin.<svc>.policy.cache.dir`.

## References (load on demand)

- [references/new-plugin-checklist.md](references/new-plugin-checklist.md): every file to create or edit, with the pom/assembly snippets.
- [references/shim.md](references/shim.md): shim class template and classloader mechanics, `.cfg` format, `enable-agent.sh` flow.
- [references/host-hook-points.md](references/host-hook-points.md): per-service entry class, host property, and behaviour keys (HDFS ACL fallback, Hive grant/revoke, HBase, YARN).
