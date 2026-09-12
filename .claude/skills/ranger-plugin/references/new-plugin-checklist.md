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

# New plugin checklist

Replace `<svc>` (lowercase, e.g. `kafka`) and `<Svc>` (`Kafka`). Copy from `plugin-kafka` / `ranger-kafka-plugin-shim` unless noted.

## 1. Impl module `plugin-<svc>/`

`pom.xml`:

```xml
<parent>
    <groupId>org.apache.ranger</groupId>
    <artifactId>ranger</artifactId>
    <version>3.0.0-SNAPSHOT</version>
    <relativePath>..</relativePath>
</parent>
<artifactId>ranger-<svc>-plugin</artifactId>
<packaging>jar</packaging>
<name><SVC> Security Plugin</name>
<dependencies>            <!-- sorted scope, groupId, artifactId (sortpom) -->
    <dependency><groupId>org.apache.ranger</groupId><artifactId>ranger-plugins-common</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>org.apache.ranger</groupId><artifactId>ranger-audit-dest-auditserver</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>org.apache.ranger</groupId><artifactId>credentialbuilder</artifactId><version>${project.version}</version></dependency>
    <!-- host service API with <scope>provided</scope>, JUnit 5 + mockito test deps -->
</dependencies>
```

Java (`src/main/java/org/apache/ranger/`):

- `authorization/<svc>/authorizer/Ranger<Svc>Authorizer.java`: implements the host's authorizer SPI; holds `private static volatile RangerBasePlugin rangerPlugin`
  (or a nested `Ranger<Svc>Plugin extends RangerBasePlugin`); `init()` does `new RangerBasePlugin("<svc>", "<svc>")`, `setResultProcessor(new Ranger<Svc>AuditHandler(...))`, `init()`;
  per request builds `Ranger<Svc>Resource` + `Ranger<Svc>AccessRequest`, calls `isAccessAllowed`, returns `result.getIsAllowed()`.
- `Ranger<Svc>Resource extends RangerAccessResourceImpl` with `KEY_<RESOURCE>` constants matching the service-def resource names.
- `Ranger<Svc>AccessRequest extends RangerAccessRequestImpl` (sets `accessType`, `action`, `user`, `userGroups`, `accessTime`, `clientIPAddress`, `clusterName`, `requestData`).
- `Ranger<Svc>AuditHandler extends RangerDefaultAuditHandler` (batching, `flushAudit()`).
- `services/<svc>/RangerService<Svc>.java extends RangerBaseService` and `client/{<Svc>Client, <Svc>ConnectionMgr, <Svc>ResourceMgr}.java`.

Conf (`conf/`), copied and renamed from a sibling:

```
ranger.plugin.<svc>.service.name          %REPOSITORY_NAME%                                       mod create-if-not-exists
ranger.plugin.<svc>.policy.source.impl    org.apache.ranger.admin.client.RangerAdminRESTClient    mod create-if-not-exists
ranger.plugin.<svc>.policy.rest.url       %POLICY_MGR_URL%                                        mod create-if-not-exists
ranger.plugin.<svc>.policy.pollIntervalMs 30000                                                   mod create-if-not-exists
ranger.plugin.<svc>.policy.cache.dir      %POLICY_CACHE_DIR%                                      mod create-if-not-exists
```

`scripts/install.properties`: `POLICY_MGR_URL`, `REPOSITORY_NAME`, `COMPONENT_INSTALL_DIR_NAME`, `XAAUDIT.*`, `SSL_KEYSTORE_*`, `SSL_TRUSTSTORE_*`, `CUSTOM_USER`, `CUSTOM_GROUP`.

Tests: `src/test/java/...` (JUnit 5), `src/test/resources/{logback.xml, ranger-<svc>-security.xml, <svc>-policies.json}`; filter `*.xml` test resources in the pom like `plugin-kafka`.

## 2. Service definition

`agents-common/src/main/resources/service-defs/ranger-servicedef-<svc>.json` (`implClass` = `org.apache.ranger.services.<svc>.RangerService<Svc>`) and
`agents-common/.../store/EmbeddedServiceDefsUtil.java` constants, field, `getOrCreateServiceDef` call, accessor, bootstrap list entry.

## 3. Shim module `ranger-<svc>-plugin-shim/`

`pom.xml`: `<artifactId>ranger-<svc>-plugin-shim</artifactId>`, depends on `ranger-plugin-classloader` and the host API (`provided`). One class; see [shim.md](shim.md).

## 4. Distro

`distro/src/main/assembly/plugin-<svc>.xml` (copy `plugin-kafka.xml`): `<id><svc>-plugin</id>`, `baseDirectory ${project.parent.name}-${project.version}-<svc>-plugin`, moduleSets:

| target | modules |
|---|---|
| `lib/` | `ranger-<svc>-plugin-shim`, `ranger-plugin-classloader` (`includeDependencies=false`) |
| `lib/ranger-<svc>-plugin-impl/` | `ranger-<svc>-plugin`, `ranger-plugins-common`, `ranger-plugins-cred`, `ranger-audit-core`, `ranger-audit-dest-auditserver`, `ranger-authz-api`, `ranger-common-utils`, `ugsync-util` + explicit third-party include/exclude list |
| `install/lib/` | `ranger-plugins-installer`, `credentialbuilder` |

Plus fileSets `install/conf.templates/{enable,disable,default}` from `../plugin-<svc>/{conf,disable-conf,template}` and files mapping `agents-common/scripts/enable-agent.sh`
to `enable-<svc>-plugin.sh` and `disable-<svc>-plugin.sh`, `security-admin/scripts/ranger_credential_helper.py`, `plugin-<svc>/scripts/install.properties`.

`distro/pom.xml`: `<dependency>` on `ranger-<svc>-plugin` and `ranger-<svc>-plugin-shim` (alphabetical), `<descriptor>src/main/assembly/plugin-<svc>.xml</descriptor>` in the `all`
profile, and a `<profile><id>ranger-<svc>-plugin</id>` block.

## 5. Root `pom.xml`

Add `plugin-<svc>` and `ranger-<svc>-plugin-shim` to the `<modules>` of profiles `all`, `linux`, `sign-artifacts` in alphabetical position (`distro` stays last), and a profile:

```xml
<profile>
    <id>ranger-<svc>-plugin</id>
    <modules>
        <module>agents-audit</module>
        <module>agents-common</module>
        <module>agents-cred</module>
        <module>agents-installer</module>
        <module>common-utils</module>
        <module>credentialbuilder</module>
        <module>plugin-<svc></module>
        <module>ranger-<svc>-plugin-shim</module>
        <module>ranger-plugin-classloader</module>
        <module>ranger-util</module>
    </modules>
</profile>
```

Run `mvn com.github.ekryd.sortpom:sortpom-maven-plugin:sort` if `validate` complains about ordering.

## 6. Optional

- `dev-support/ranger-docker/Dockerfile.ranger-<svc>`, `docker-compose.ranger-<svc>.yml`, `.env` versions, `scripts/<svc>/` install script.
- `mkdocs/docs/plugins/<svc>.md` and a `nav:` entry in `mkdocs/mkdocs.yml`.
- `.github/workflows/ci.yml` plugin docker build list.

## Later service-def changes

Editing the JSON only affects new installs. Ship a `security-admin/.../patch/PatchFor<Svc>..._J<n>.java` (plus a `TypeName` entry in
`dev-support/checkstyle-suppressions.xml`) to update existing deployments.
