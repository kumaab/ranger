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

# Building from source

Apache Ranger is a multi-module Apache Maven project. A full build compiles roughly seventy modules (Admin,
UserSync, TagSync, KMS, PDP, the audit server, every plugin and its shim, the client libraries, and the
examples) and packages each deployable component as a `ranger-<version>-<component>.tar.gz` archive in the
root `target/` directory. Those archives are what the Docker images in `dev-support/ranger-docker` are built
from.

You can build on your own machine with a JDK and Maven, or inside a container so that you do not have to
install a toolchain. Both paths are described below, followed by the profiles that let you build a subset of
modules, how to run tests and code checks, and how to import the project into an IDE.

## Prerequisites

The versions below are enforced by `maven-enforcer-plugin` in the root `pom.xml`.

| Requirement | Version |
| --- | --- |
| JDK | 17 (`java.version.required`) |
| Apache Maven | 3.6.3 or newer (`maven.version.required`) |
| Git | any |
| Maven heap | 4–5 GB |
| Disk | several GB |

- Source and target level are 17 (`javac.source.version`). CI builds with Temurin 17.
- You do not need Node.js. `frontend-maven-plugin` downloads Node `v20.19.5` and npm `10.8.2` into
  `security-admin/target/react-webapp` to build the Admin UI; the build only needs network access.
- CI uses `MAVEN_OPTS=-Xmx5g`; the docker build script uses `-Xms512m -Xmx5g`.
- A full build downloads the dependency trees of every component Ranger integrates with (Trino, Ozone, Kafka,
  Knox, HBase, Hive, Hadoop and others).

Set `JAVA_HOME` to the JDK 17 installation and make sure `mvn -v` reports it.

## Quick build

```bash
git clone https://github.com/apache/ranger.git
cd ranger
mvn clean package -DskipTests
ls target/ranger-*.tar.gz
```

`package` is enough to produce the tarballs; use `install` if other Maven projects on the same machine (for
example an application that depends on `ranger-intg` or `authz-remote`) need the snapshot jars in your local
repository:

```bash
mvn clean install -DskipTests
```

Expect the first build to take a long time (up to an hour on a cold `~/.m2`); later builds are much faster.
CI runs the full verification build in parallel with `mvn -T 8 clean verify --no-transfer-progress -B -V`.

## Build artifacts

After a successful build the root `target/` directory contains one archive per assembly descriptor in
`distro/src/main/assembly/` (the `all` profile in `distro/pom.xml`), plus a `version` file:

| Archive | Contents |
| --- | --- |
| `ranger-<version>-admin.tar.gz` | Ranger Admin (policy manager web application, embedded Tomcat, DB scripts). |
| `ranger-<version>-usersync.tar.gz` | UserSync. |
| `ranger-<version>-tagsync.tar.gz` | TagSync. |
| `ranger-<version>-kms.tar.gz` | Ranger KMS. |
| `ranger-<version>-pdp.tar.gz` | Ranger PDP (policy decision point) server. |
| `ranger-<version>-audit-ingestor.tar.gz` | Audit server: ingestor. |
| `ranger-<version>-audit-dispatcher.tar.gz` | Audit server: dispatcher. |
| `ranger-<version>-<name>-plugin.tar.gz` | One archive per plugin with the plugin and shim jars. |
| `ranger-<version>-ranger-tools.tar.gz` | Policy-engine performance and load tools (see [Testing and tools](testing-and-tools.md)). |
| `ranger-<version>-sample-client.tar.gz` | Java sample clients for the Admin REST API and the PDP. |
| `ranger-<version>-migration-util.tar.gz` | Migration utility. |
| `ranger-<version>-solr_audit_conf.tar.gz` | Solr collection configuration for audits. |
| `ranger-<version>-src.tar.gz` | Source archive. |

Plugin archives are built for these `<name>` values: `trino`, `ozone`, `schema-registry`, `presto`,
`elasticsearch`, `kylin`, `sqoop`, `atlas`, `kafka`, `solr`, `yarn`, `knox`, `storm`, `hbase`, `hive`
and `hdfs`. The `kms` plugin assembly (`plugin-kms.xml`) is not in the descriptor list of the `all` profile; it
is referenced only by the `ranger-kms-plugin` profile of `distro/pom.xml`.

The NiFi, NiFi Registry, Kudu and nested-structure plugins build jars in their module `target/` directories but
have no tarball assembly.

## Building in Docker

Two container-based options exist; both mount your `~/.m2` so dependency downloads are cached between runs.

=== "docker compose (recommended)"

    The `ranger-build` service in `dev-support/ranger-docker/docker-compose.ranger-build.yml` builds an image from
    `Dockerfile.ranger-build` (based on `apache/ranger-base`, which already contains the JDK, Maven and git) and
    runs `scripts/build/ranger-build.sh` inside it. The script runs
    `mvn -P${PROFILE} ${BUILD_OPTS} -DskipTests -DskipDocs clean package` and moves `target/ranger-*` and
    `target/version` into `dev-support/ranger-docker/dist/`, where the service Dockerfiles expect them.

    ```bash
    cd dev-support/ranger-docker
    chmod +x scripts/**/*.sh

    # optional: rebuild the build image (e.g. after a base-image change)
    docker compose -f docker-compose.ranger-build.yml build

    docker compose -f docker-compose.ranger-build.yml up
    ```

    Environment variables read by `ranger-build.sh`:

    | Variable | Default | Description |
    | --- | --- | --- |
    | `BUILD_HOST_SRC` | `true` | Build the checkout mounted from the host (`RANGER_HOME`, default the repo root). Set to `false` to clone instead. |
    | `GIT_URL` | `https://github.com/apache/ranger.git` | Repository to clone when `BUILD_HOST_SRC=false`. |
    | `BRANCH` | `master` | Branch to clone when `BUILD_HOST_SRC=false`. |
    | `PROFILE` | unset | Maven profile id passed as `-P<PROFILE>` (see [Maven profiles](#maven-profiles)). |
    | `BUILD_OPTS` | unset | Extra Maven arguments. |
    | `SKIPTESTS` | unset (`-DskipTests`) | Set to `false` to run unit tests in the container. |
    | `JAVA_OPTS` | unset | Appended to `MAVEN_OPTS`. |

    After cloning, the script applies any patches placed in `dev-support/ranger-docker/patches/` with `git apply`.

    The `ranger_in_docker up` script at the repository root runs this same build automatically when
    `dev-support/ranger-docker/dist/` holds fewer than 20 tarballs (or when `RANGER_REBUILD=1`), then starts the
    services. Set `DOCKER_MAVEN_BUILD=1` to have it run `mvn clean package -DskipTests` on the host instead. See
    [Running Ranger with Docker](../getting-started/docker.md).

=== "build_ranger_using_docker.sh"

    The older `build_ranger_using_docker.sh` at the repository root builds a throw-away Ubuntu image named
    `ranger_dev` with Maven 3.9.9 and runs Maven in it, mounting the source tree at `/ranger`:

    ```bash
    # default: mvn -Pall -DskipTests=true clean compile package install
    ./build_ranger_using_docker.sh

    # custom maven command
    ./build_ranger_using_docker.sh mvn -Pall clean install -DskipTests=true

    # force re-creation of the image
    ./build_ranger_using_docker.sh -build_image mvn -Pall clean install -DskipTests=true
    ```

    Run it with `sudo` on Linux. Note that this image installs OpenJDK 11, which no longer satisfies the JDK 17
    requirement of master; prefer the docker compose build above.

## Maven profiles

Profiles are declared in the root `pom.xml` (`grep '<profile>' pom.xml`). `all` is active by default and lists
every module; the component profiles compile only one component plus the modules it depends on, which is much
faster when you are working on a single plugin. The component profiles do not include the `distro` module, so
they produce jars (and the Admin `war`) in the module `target/` directories, not tarballs.

| Profile | Modules |
| --- | --- |
| `all` (default) | Everything, including `distro`, which must remain the last module. |
| `ranger-admin` | `agents-common`, `common-utils`, `security-admin`, `ugsync-util`. |
| `ranger-<name>-plugin` | One plugin: the shared agent modules, the plugin module and its shim (Trino and nested structure have no shim). |
| `ranger-examples` | `agents-common`, `agents-cred`, `common-utils`, `intg`, `ranger-examples`. |
| `linux` | Activated automatically on Linux; same module list as `all`. It stays active when you pass `-P <profile>`, so on Linux a component profile does not narrow the build. |
| `sign-artifacts` | GPG-signs artifacts; used by release managers (`-Dsign-artifacts=true`). |

Plugin profiles exist for these `<name>` values: `trino`, `ozone`, `nestedstructure`, `presto`, `elasticsearch`,
`kylin`, `sqoop`, `atlas`, `kafka`, `solr`, `kms`, `yarn`, `knox`, `storm`, `hbase`, `hive` and `hdfs`.

```bash
# only the Hive plugin
mvn clean package -DskipTests -P ranger-hive-plugin
ls hive-agent/target/*.jar ranger-hive-plugin-shim/target/*.jar

# only Ranger Admin
mvn clean package -DskipTests -P ranger-admin
```

!!! tip "Rebuilding a single module"
    Within the default profile you can also restrict Maven to one module and what it needs, e.g.
    `mvn -pl agents-common -am clean install -DskipTests`. The tarballs are produced by `distro`, which depends
    on every packaged component, so run the full build if you need an archive.

## Useful flags

| Flag | Effect |
| --- | --- |
| `-DskipTests` | Compile tests but do not run them (`skipTests` is wired into surefire). |
| `-DskipDocs` | Passed by the docker build script. No `pom.xml` in the current tree reads this property. |
| `-Dcheckstyle.skip=true` | Skip Checkstyle (default `false`; violations fail the build). |
| `-Dspotbugs.failOnViolation=true` | Make SpotBugs findings fail the build (default `false`, report only). |
| `-T 8` | Parallel build with 8 threads, as used in CI. |
| `--no-transfer-progress -B` | Quieter, non-interactive output for CI logs. |

## Tests and code checks

Unit tests use JUnit 5 (the enforcer's `ban-legacy-dependencies` rule bans JUnit 4 in the compile, runtime
and provided scopes) and run with
`maven-surefire-plugin`. Code checks are bound to the `verify` phase:

| Check | Plugin | Configuration | Fails build? |
| --- | --- | --- | --- |
| Checkstyle | `maven-checkstyle-plugin` | `dev-support/checkstyle.xml`, suppressions in `dev-support/checkstyle-suppressions.xml` | yes |
| PMD | `maven-pmd-plugin` | `dev-support/ranger-pmd-ruleset.xml` | yes |
| SpotBugs | `spotbugs-maven-plugin` | `dev-support/spotbugsIncludeFile.xml` | no (unless `-Dspotbugs.failOnViolation=true`) |
| Coverage | `jacoco-maven-plugin` | per-module `jacoco.exec` | no |

`dev-support/checks/coverage.sh` merges the per-module coverage data into `target/coverage/all` (HTML and XML).

```bash
# full verification, as in CI
mvn clean verify

# run the tests of one module
mvn -pl agents-common test

# a single test class
mvn -pl agents-common test -Dtest=TestPolicyEngine

# merged coverage report after a `verify` build
./dev-support/checks/coverage.sh
open target/coverage/all/index.html
```

The style rules that Checkstyle enforces are explained in [Java code style](../project/java-code-style.md);
`dev-support/RangerCodeScheme-IntelliJ.xml` is an IntelliJ code-style scheme matching them. More on test
suites and tools in [Testing and tools](testing-and-tools.md).

## Continuous integration

`.github/workflows/ci.yml` runs on every push and on pull requests against `master`:

1. `build-17`: JDK 17, `mvn -T 8 clean verify`, then `dev-support/checks/coverage.sh`; uploads `target/*` as
   an artifact.
2. `services-docker-build`: downloads the tarballs into `dev-support/ranger-docker/dist`, builds the Admin,
   PDP, UserSync, TagSync, KMS and audit-service images with PostgreSQL and OpenSearch, starts them, and
   checks every container is running.
3. `plugins-docker-build`: the same for the Hadoop, HBase, Hive, Knox and Ozone plugin containers (with a KDC).

`.github/workflows/docs.yml` builds this documentation site with `mkdocs build` and publishes it on pushes to
`dev` and `master`. `upgrade-ranger.yaml` is a manually triggered workflow that installs an earlier release in Docker and
upgrades it to the freshly built version.

## Building the documentation

```bash
cd mkdocs
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
mkdocs serve --strict          # http://localhost:8000
mkdocs build
```

## IDE import

Import the root `pom.xml` as a Maven project.

- **IntelliJ IDEA**: *File → New → Project from Existing Sources* (or *Open*) on the repository root; select
  the Maven model. Import `dev-support/RangerCodeScheme-IntelliJ.xml` under *Settings → Editor → Code Style*
  so that formatting matches Checkstyle. Set the project SDK to JDK 17.
- **Eclipse**: `mvn eclipse:eclipse` from the root generates project files, then *Import → Existing Projects
  into Workspace*; or use m2e and import the root as a Maven project.

Build once from the command line (`mvn clean install -DskipTests`) before importing so that generated
sources and the Admin UI resources exist.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `Detected JDK version ... is not in the allowed range [17,)` | Enforcer rule: point `JAVA_HOME` to JDK 17. |
| `java.lang.OutOfMemoryError` during the build | Increase `MAVEN_OPTS` (e.g. `-Xmx5g`). |
| `npm` errors in `security-admin` | The UI build could not download or run Node. Run `npm cache clean --force` (as CI does) and check proxy settings. |
| Checkstyle or PMD violations | Fix them (see [Java code style](../project/java-code-style.md)); `-Dcheckstyle.skip=true` only for local iteration. |
| Stale `dev-support/ranger-docker/dist` after a rebuild | Copy `target/ranger-*` and `target/version` into `dist/` again, or set `RANGER_REBUILD=1` for `ranger_in_docker`. |

## Further reading

- [Running Ranger with Docker](../getting-started/docker.md).
- [Contributing](../project/contributing.md) — review process and patch guidelines.
- [`dev-support/ranger-docker/README.md`](https://github.com/apache/ranger/blob/master/dev-support/ranger-docker/README.md).
