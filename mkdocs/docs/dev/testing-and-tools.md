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

# Testing and tools

Ranger has three layers of automated testing, and a set of developer tools for sizing and load-testing
the policy engine. Unit tests run inside Maven and cover each module in isolation; the policy engine in
particular is exercised by data-driven JSON test cases that you can extend without writing Java. Functional
tests are Python (pytest) suites that talk to a real Ranger stack started with Docker Compose. Continuous
integration runs the unit tests on every push and then boots the Docker stack to make sure the packaged
services and plugins still start.

This page shows how to run each layer, how to add a policy-engine test case, and how to use the tools in
`ranger-tools/` and `dev-support/`.

## Unit tests

Unit tests use JUnit 5 (`junit.jupiter.version` in the root `pom.xml`; the Maven enforcer bans JUnit 4 outside
test scope) and run through `maven-surefire-plugin`. The surefire `argLine` adds the JaCoCo agent and the
`--add-opens`
flags the code base needs on JDK 17, so run tests through Maven rather than a bare JVM.

```bash
# everything, plus Checkstyle/PMD/SpotBugs (what CI runs)
mvn -T 8 clean verify --no-transfer-progress -B -V

# one module (dependencies resolved from ~/.m2 — build them first with `mvn install -DskipTests`)
mvn -pl agents-common test

# one module and everything it depends on
mvn -pl agents-common -am test

# one test class / one test method
mvn -pl agents-common test -Dtest=TestPolicyEngine
mvn -pl agents-common test -Dtest=TestPolicyEngine#testPolicyEngine_hdfs

# skip the quality gates while iterating
mvn -pl security-admin verify -Dcheckstyle.skip=true -Dpmd.skip=true -Dspotbugs.skip=true
```

Checkstyle, PMD and SpotBugs are bound to the `verify` phase, so `mvn test` does not run them; `mvn verify`
or `mvn install` does. See [Building from source](build.md#tests-and-code-checks) for their configuration.

### Coverage

Every module writes `target/jacoco.exec`. `dev-support/checks/coverage.sh` downloads the JaCoCo CLI, merges
all `jacoco.exec` files, unpacks the module jars (excluding `security-admin` and the `*shim*` modules) and
writes an aggregate HTML/XML report:

```bash
mvn clean verify            # or at least `mvn test` in the modules you care about
./dev-support/checks/coverage.sh
open target/coverage/all/index.html
```

## Policy engine test cases

The heart of Ranger, the policy engine in `agents-common`, is tested with JSON files under
`agents-common/src/test/resources/policyengine/`. Each file bundles a service definition, a set of policies
and a list of requests with expected results. `TestPolicyEngine` (in
`agents-common/src/test/java/org/apache/ranger/plugin/policyengine/`) has one `@Test` per file and evaluates
every request against a fresh engine.

```json title="test_policyengine_hdfs_resourcespec.json (abridged)"
{
  "serviceName": "hdfsdev",
  "serviceDef": {
    "name": "hdfs", "id": 1,
    "resources":   [ { "name": "path", "type": "path", "level": 1, "mandatory": true,
                       "matcher": "org.apache.ranger.plugin.resourcematcher.RangerPathResourceMatcher",
                       "matcherOptions": { "wildCard": true, "ignoreCase": true } } ],
    "accessTypes": [ { "name": "read" }, { "name": "write" }, { "name": "execute" } ],
    "contextEnrichers": [], "policyConditions": []
  },
  "policies": [
    { "id": 2, "name": "allow-read-to-{USER} under /home/{USER}/", "isEnabled": true, "isAuditEnabled": false,
      "resources": { "path": { "values": [ "/home/{USER}/" ], "isRecursive": true } },
      "policyItems": [ { "accesses": [ { "type": "read", "isAllowed": true } ], "users": [ "{USER}" ] } ] }
  ],
  "tests": [
    { "name": "DENY 'read /home/user1/tmp/sales.db' for user=user2",
      "request": { "resource": { "elements": { "path": "/home/user1/tmp/sales.db" } },
                   "accessType": "read", "user": "user2", "userGroups": [] },
      "result":  { "isAudited": false, "isAllowed": false, "policyId": -1 } },
    { "name": "ALLOW 'read /home/user1/tmp/sales.db' for user=user1",
      "request": { "resource": { "elements": { "path": "/home/user1/tmp/sales.db" } },
                   "accessType": "read", "user": "user1", "userGroups": [] },
      "result":  { "isAudited": false, "isAllowed": true, "policyId": 2 } }
  ]
}
```

| Top-level key | Description |
| --- | --- |
| `serviceName`, `serviceDef`, `policies` | The service and its policies loaded into the engine. |
| `tagPolicyInfo`, `securityZones` | Optional tag policies and zone definitions for tag/zone tests. |
| `userRoles`, `groupRoles`, `roleRoles` | Role membership used for role-based policy items. |
| `serviceConfig`, `auditMode`, `superUsers`, `superGroups`, `auditExcludedUsers` ... | Engine options. |
| `tests[]` | The test cases: each entry has a `name`, a `request` and the expected `result`. |
| `updatedPolicies`, `updatedTests` | Policy deltas to apply, and a second set of tests to run on the same engine afterwards. |

Masking and row-filter files use `dataMaskResult` / `rowFilterResult` instead of `result`; ACL files use
`resourceAccessInfo`.

`policyId: -1` in a result means "no policy matched". To add coverage for a new evaluation rule, add a test
entry (or a new file plus a `@Test` method in `TestPolicyEngine`). Examples worth copying from:
`test_policyengine_hive.json`, `test_policyengine_hive_mask_filter.json`, `test_policyengine_conditions.json`,
`test_policyengine_hdfs_zones.json`, `test_policyengine_priority.json`. Sibling directories hold the same style
of tests for `contextenricher/`, `policycondition/`, `policyevaluator/` and `resourcematcher/`.

## Functional tests

`functional-tests/` contains pytest suites that run against a Ranger stack started from
`dev-support/ranger-docker`. They create users in each role (admin, keyadmin, auditor, user), call the REST
APIs at `http://localhost:6080/service/...` and KMS at `http://ranger-kms.rangernw:9292/kms/v1`, and pull
container logs with `docker exec` when an assertion fails.

| Suite | Directory | What it covers |
| --- | --- | --- |
| `xuserrest` | `functional-tests/xuserrest/` | Users, groups, permissions, secure user endpoints, usersync APIs. |
| `rolerest` | `functional-tests/rolerest/` | Role management. |
| `kms` | `functional-tests/kms/` | Key CRUD, key operations, KMS policies, blacklisting. |
| `hdfs` | `functional-tests/hdfs/` | HDFS transparent encryption with Ranger KMS (needs the Hadoop container). |

`run-tests.sh` builds Ranger (optional), starts the containers and runs the selected suites:

```bash
cd functional-tests
chmod +x run-tests.sh

./run-tests.sh                        # interactive: asks for DB type and suites
./run-tests.sh postgres kms hdfs      # DB type (postgres|mysql|oracle), then suites

CLEAN_CONTAINERS=1 ./run-tests.sh     # remove existing ranger containers and rebuild first
RUN_TESTS=0 ./run-tests.sh            # only bring the stack up
```

Behind the scenes the script runs `docker compose -f docker-compose.ranger-build.yml up` (when a rebuild is
needed), `./download-archives.sh` for the required component archives, then starts
`docker-compose.ranger.yml`, `-usersync`, `-tagsync`, `-kms` (and `-hadoop` for the `hdfs` suite). Each suite is
executed in a virtualenv as `pytest -vs <suite>/ --html=report_<suite>.html`; the HTML reports land in
`functional-tests/`. Markers registered in `pytest.ini` (`positive`, `negative`, `get`, `post`, `put`,
`delete`, `secure_endpoint`, ...) let you select a subset with `pytest -m`.

!!! note
    `run-tests.sh` and `readme.md` also list a `servicerest` suite, but no such directory exists on master;
    the script prints a warning and skips it.

## Ranger tools

`ranger-tools/` builds into `ranger-<version>-ranger-tools.tar.gz` (`conf/`, `dist/`, `lib/`, `testdata/`).
Unpack it and run the scripts from that directory.

### Policy engine performance tester

`ranger-perftester.sh` loads a `ServicePolicies` JSON file and a request file into an in-memory policy
engine, replays the requests from several threads and prints timing statistics per module.

```bash
./ranger-perftester.sh -s testdata/test_servicepolicies_hive.json -r testdata/test_requests_hive.json \
    -c 2 -n 1 -t -d -f testdata/ranger-config.xml -p testdata/test_modules.txt
```

| Option | Description |
| --- | --- |
| `-s`, `--service-policies` | Policies file (same JSON as `GET /service/plugins/policies/download/<service>`). |
| `-r`, `--requests` | Request definition file (`testdata/test_requests_hive.json`). |
| `-c`, `--clients` | Number of concurrent clients. |
| `-n`, `--cycles` | Iterations per client. |
| `-f`, `--configurations` | Ranger configuration XML for the engine. |
| `-p`, `--statistics` | Modules to collect statistics for. |
| `-t`, `--trie-prefilter` | Enable the resource trie pre-filter. |
| `-d`, `--trie-lazy-setup` | Build the trie lazily. |

Output lines look like
`[RangerPolicyEngine.isAccessAllowed] execCount:64, totalTimeTaken:1873, maxTimeTaken:276, minTimeTaken:4, avgTimeTaken:29`
(milliseconds). `gen_service_policies.sh <service> <count>` and `gen_service_tags.sh <service> <count> <initial-id>`
generate large synthetic policy and tag files; `create_requests.py` derives a request file from a tags file.

### Plugin performance tester

`ranger-plugin-perftester.sh` starts a real `RangerBasePlugin` against a live Ranger Admin and reports JVM
memory usage while policies are downloaded and refreshed:

```bash
./ranger-plugin-perftester.sh -s hive -n cl1_hive -a test_hive_plugin -r http://ranger-admin-host:6080 \
    -t 30000 -p 30000 -c /tmp/hive/policycache -e nocache
```

(`-s` service type, `-n` service name, `-a` app id, `-r` Admin URL, `-t` socket read timeout ms,
`-p` polling interval ms, `-c` policy cache dir, `-e` policy evaluator type.)

### Memory sizing

`ranger-mem-sizing.sh` (`org.apache.ranger.sizing.RangerMemSizing`) loads policies, tags, roles, a user store
and GDS info from files and reports the heap each structure occupies, optionally generating and evaluating
requests:

```bash
./ranger-mem-sizing.sh -p policies.json -t tags.json -u userstore.json -r roles.json
```

Options: `-p` policies, `-t` tags, `-r` roles, `-u` userStore, `-g` gdsInfo, `-d` deDup strings/tags,
`-o` optimization mode (`space`|`retrieval`), `-m` reuse resource matchers, `-q` generate requests file,
`-e` evaluate requests file, `-c` evaluation client count, `-k` resource keys to generate requests for.

### Python performance analyzer and load generators

`ranger-tools/src/main/python/` contains a REST performance analyzer that calls policy APIs repeatedly, collects
response times and (over SSH) `vmstat` metrics from the Admin host, and writes `performance_report.html/.csv/.json`
plus `statistics_report.json/.csv` under `outputs/`:

```bash
cd ranger-tools/src/main/python          # Python 3.10/3.11
pip install -r requirements.txt
python3 setup_performance_analyzer.py    # create config/primary_config.json and secondary_config.json
python3 performance_analyzer.py          # run the APIs listed in the config

# single API run
python3 performance_analyzer.py --ranger_url http://localhost:6080 --calls 100 --api create_policy \
    --username admin --password 'rangerR0cks!' --ssh_host ranger-host --ssh_user user --ssh_password pw
```

Supported `api_list` entries are `create_policy`, `update_policy_by_id`, `get_policy_by_id` and
`delete_policy_by_id`. The Admin host needs `sysstat`, and `ranger.accesslog.pattern` should include `%D` so
that Tomcat logs execution time. The `stress/` directory has standalone load generators (`stress_policy.py`,
`stress_kms.py`, `stress-hbase-loadgenerator.py`); the policy and KMS generators use the
[Python client](../features/client-interface/python.md).

## dev-support

| Path | Description |
| --- | --- |
| `dev-support/checkstyle.xml` | Checkstyle rules (see [Java code style](../project/java-code-style.md)). |
| `dev-support/checkstyle-suppressions.xml` | Checkstyle suppressions. |
| `dev-support/ranger-pmd-ruleset.xml` | PMD rules (codestyle, bestpractices, multithreading categories). |
| `dev-support/spotbugsIncludeFile.xml` | SpotBugs include filter. |
| `dev-support/RangerCodeScheme-IntelliJ.xml` | IntelliJ code-style scheme. |
| `dev-support/checks/coverage.sh` | Aggregate JaCoCo report. |
| `dev-support/smart-apply-patch.sh` | Helper that applies a patch file to the working tree. |
| `dev-support/test-patch.sh` | Helper that runs checks against a patch file. |
| `dev-support/README-TAGSYNC-ATLAS-KAFKA-CONFIG.md` | Atlas→Kafka security settings for TagSync. |
| `dev-support/ranger-docker/` | Docker Compose stack; |

There are no version-controlled git hooks; the quality gates run in Maven and in CI.

## Continuous integration

`.github/workflows/ci.yml` runs `mvn -T 8 clean verify` on JDK 17, uploads the coverage report and the
`target/` tarballs, then builds and starts the service containers (Admin, PDP, UserSync, TagSync, KMS, audit
server with PostgreSQL and OpenSearch) and the plugin containers (Hadoop, HBase, Hive, Knox, Ozone with a KDC),
failing if any expected container is not running after start-up. Details in
[Building from source](build.md#continuous-integration).

## Further reading

- [Building from source](build.md)
- [`functional-tests/readme.md`](https://github.com/apache/ranger/blob/master/functional-tests/readme.md),
  [`ranger-tools/scripts/README.txt`](https://github.com/apache/ranger/blob/master/ranger-tools/scripts/README.txt),
  [`ranger-tools/src/main/python/README.md`](https://github.com/apache/ranger/blob/master/ranger-tools/src/main/python/README.md).
