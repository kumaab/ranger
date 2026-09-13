---
name: ranger-conventions
description: Cross-cutting Apache Ranger conventions that every change must follow - Java 17 style enforced by checkstyle/PMD/RAT on mvn verify, ASF license headers per file type, slf4j logging idioms, JUnit 5/Mockito test rules, pom.xml sortpom ordering, commit/PR format (RANGER-NNNN), build and CI commands, and the module map. Use at the start of any task in this repo and before creating a new file in any language.
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

# Apache Ranger repository conventions

Apache Ranger 3.0.0-SNAPSHOT, a Maven multi-module repo (~70 modules) in Java 17 with a React UI, Python installers/clients, shell installers, and SQL for
five databases. Module-specific skills: `security-admin` (Admin backend), `security-admin-webapp` (React UI), `security-admin-db` (schema + installer),
`agents-common` (plugin framework), `ranger-plugin` (plugin modules), `ranger-sync-services` (ugsync/tagsync), `ranger-kms` (KMS), `ranger-authz` (authz-api/embedded/remote/pdp), `ranger-audit-server` (agents-audit/audit-server),
`ranger-clients` (intg). Existing editor rules: `.cursor/rules/ranger-checkstyle.mdc`, `.cursor/rules/ranger-python.mdc`.

## Build and verify

```bash
mvn -Pall -DskipTests clean install                       # full build (macOS needs -Pall; the linux profile only auto-activates on Linux)
mvn -pl <module> -am -DskipTests install                  # one module and what it depends on
mvn -pl <module> -DskipTests verify                       # checkstyle + PMD + RAT + spotbugs on that module, what CI runs
mvn -pl <module> test -Dtest=ClassName                    # one test class
./build_ranger_using_docker.sh                            # containerized build, no local JDK/Maven needed
./ranger_in_docker up                                     # full local stack
```

Requires JDK 17 and Maven 3.6.3+ (enforcer). CI (`.github/workflows/ci.yml`) runs `mvn -T 8 clean verify` on JDK 17 only, then docker builds. `distro` must stay the last module.
`-DskipTests` must be passed explicitly (`${skipTests}` is undefined otherwise).

## Every new file

1. ASF license header in the comment syntax of the file type. Full text per type: [references/license-headers.md](references/license-headers.md).
   RAT fails `verify` without it. Exempt: `.json` under `main/resources` and `test/resources`, `dev-support/**`, `node_modules`, `package*.json`, images.
2. LF line endings, no tabs (except the tab-indented legacy Python installers), newline at EOF, no trailing whitespace.
3. Match the neighbouring files in the same module for naming and layout.

## Java

- Java 17 source/target; no `var`, records, or sealed types in existing code. `assert` is banned by checkstyle.
- File order: license, package, imports, one top-level class. Imports: `*` group, blank, `javax`, blank, `java`, blank, static imports. No wildcards, no unused.
- K&R braces, braces always, `else` on its own line after `}`. 4-space indent, wrapped lines +8. No consecutive blank lines, none after `{` or before `}`.
- Column-aligned field declarations and consecutive assignments (IntelliJ scheme `dev-support/RangerCodeScheme-IntelliJ.xml`; checkstyle allows it, reviewers expect it).
- Members: loggers first, then static final, static, final, instance; public before protected before private.
- Naming: `UpperCamelCase` classes, `lowerCamelCase` methods/fields, `UPPER_SNAKE` constants, lowercase-only packages. Result local is `ret`.
- One `return` per method in production code; assign into a local and return at the end.
- `private static final Logger LOG = LoggerFactory.getLogger(X.class);` (slf4j). `LOG.debug("==> X.m({})", a)` on entry, `LOG.debug("<== X.m({}): ret={}", a, ret)` on exit.
  Placeholders only, never string concatenation in log calls; `isDebugEnabled` only around expensive message construction. `LOG.error("m({}) failed", a, excp)`.
- Null checks with `StringUtils`/`CollectionUtils`/`MapUtils`; avoid `Optional` (checkstyle bans static-importing its members).
- Perf tracing with `RangerPerfTracer` on hot paths (`PERF_<AREA>_LOG` fields).
- Checkstyle rule inventory and the static-import allow/deny list: [references/java-style.md](references/java-style.md).

## Tests

JUnit 5 and Mockito 5 only (JUnit 4 and Jersey 1 are banned by the enforcer). `@ExtendWith(MockitoExtension.class)`, static-imported `Assertions`
and `Mockito` helpers, `Test*.java` or `*Test.java` matching the module's majority. Checkstyle runs on test sources. Fixtures in `src/test/resources`.
Details and per-module specifics: [references/testing.md](references/testing.md).

## pom.xml

`sortpom` verifies at `validate`: properties, modules, dependencies (by scope, groupId, artifactId) alphabetical; 4-space indent. Version properties live in the root pom.
Fix ordering with `mvn com.github.ekryd.sortpom:sortpom-maven-plugin:sort`.

## Other languages

- **JavaScript/JSX**: see `security-admin-webapp` (Prettier, function components, alias imports).
- **Python installers** (`security-admin/scripts`, `agents-common/scripts`): Python 3, `log("[I] ...", "info")`, `globalDict`, jisql; see `security-admin-db`.
- **Python client** (`intg/`): Python 3.13+, vertical `=` alignment, `type_coerce`; see `.cursor/rules/ranger-python.mdc`.
- **Shell**: `#!/bin/bash`, no `set -e`, explicit status checks, `log()` helper; see `security-admin-db`.
- **SQL**: five vendor variants per change, `--` header, idempotent guards; see `security-admin-db`.

## Commits and PRs

Subject `RANGER-NNNN: <description>` (JIRA required, `autolink_jira` on). PR title starts with the JIRA id; template asks "What changes were proposed" and "How was this patch tested"
(screenshot for UI). `master` is protected and squash-merge is the only merge mode. No commits without an explicit request in agent sessions.

## Documentation

MkDocs Material site in `mkdocs/` (page under `mkdocs/docs/<section>/`, entry in `mkdocs/mkdocs.yml` `nav:`), deployed to `gh-pages` by `docs.yml`.
Module docs as `README.md` inside the module. No ADRs. Security reporting: `SECURITY.md`, threat model: `THREAT_MODEL.md`, agent notes: `AGENTS.md`.

## References (load on demand)

- [references/java-style.md](references/java-style.md): checkstyle rules, PMD/spotbugs posture, IntelliJ scheme highlights, suppressions file.
- [references/license-headers.md](references/license-headers.md): exact header text for `.java/.js/.jsx`, `.xml/.md`, `.sh/.py/.properties/.yml`, `.sql`, and RAT exclusions.
- [references/testing.md](references/testing.md): test framework rules, surefire config, running tests, Python and functional tests.
- [references/module-map.md](references/module-map.md): one paragraph per module and how they fit together.
- [references/ha-and-metrics.md](references/ha-and-metrics.md): Admin HA is LB + shared DB only; usersync/tagsync Curator HA keys; the shared `ranger-metrics` pipeline per service.
- [references/topology-and-ports.md](references/topology-and-ports.md): processes, ports, daemon/installer scripts per server, and the data flows between them.
