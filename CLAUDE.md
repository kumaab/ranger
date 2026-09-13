<!--
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

# Apache Ranger

Centralized authorization and audit for the Hadoop ecosystem and beyond. Maven multi-module (~70 modules), Java 17, React 18 UI, Python installers and
client, shell installers, SQL for five databases. Version `3.0.0-SNAPSHOT`, branch `master`, squash-merge only, JIRA prefix `RANGER-NNNN`.

Architecture map, ports and data flows between processes: `.claude/skills/ranger-conventions/references/topology-and-ports.md`.
Detailed guidance is split into skills under `.claude/skills/` and loaded on demand (progressive disclosure). Start with `ranger-conventions`,
then the skill for the module you are touching. Each skill's `references/` folder holds the deeper recipes; read them only when the task needs them.

| Skill | Load when |
|---|---|
| `ranger-conventions` | any task: build/verify commands, license headers, Java style, tests, commit format, module map |
| `security-admin` | Java under `security-admin/src` (REST, biz, service, JPA, security, patches, metrics) |
| `security-admin-webapp` | React UI under `security-admin/src/main/webapp/react-webapp` |
| `security-admin-db` | SQL under `security-admin/db`, installer scripts under `security-admin/scripts` |
| `agents-common` | plugin framework: policy engine, service-defs, conditions, enrichers, matchers, models, validators |
| `ranger-plugin` | creating or packaging a `plugin-<svc>` / `ranger-<svc>-plugin-shim` module |
| `ranger-sync-services` | `ugsync/`, `ugsync-util/`, `unixauthservice/`, `tagsync/` |
| `ranger-kms` | `kms/`, `plugin-kms/` |
| `ranger-authz` | `authz-api/`, `authz-embedded/`, `authz-remote/`, `pdp/` |
| `ranger-audit-server` | `agents-audit/`, `audit-server/`, `*-audit-changes.cfg`, `xasecure.audit.*` |
| `ranger-clients` | `intg/` (Java `RangerClient`, Python `apache-ranger`), `ranger-examples/sample-client/` |

## Commands

```bash
mvn -Pall -DskipTests clean install              # full build (use -Pall on macOS)
mvn -pl <module> -DskipTests verify              # checkstyle + PMD + RAT + spotbugs, same as CI
mvn -pl <module> test -Dtest=ClassName           # one test class
./ranger_in_docker up                            # full local stack via dev-support/ranger-docker
```

## Hard rules

- ASF license header on every new file in the comment syntax of its type; `.json` under `resources` is the exception.
- Java: slf4j `LOG`, `==>`/`<==` debug entry/exit with `{}` placeholders, imports `*` / `javax` / `java` / static, K&R braces, one `return` per method, no `assert`, no `Optional`.
- Tests: JUnit 5 + Mockito 5 only.
- `pom.xml` ordering is enforced by sortpom (alphabetical properties, modules, dependencies).
- Schema changes ship as five vendor SQL patches plus the five optimized schemas; patches never write `x_db_version_h`.
- New Admin REST APIs need a `RangerAPIList` constant mapped in `RangerAPIMapping`, or they are open to every authenticated user.
- Do not commit or push unless asked.

Also read `AGENTS.md`, `SECURITY.md` (vulnerability reporting), and `.cursor/rules/*.mdc` (checkstyle and Python client rules).
