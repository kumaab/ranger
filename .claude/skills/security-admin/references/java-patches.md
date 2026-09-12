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

# Java DB patches (`org.apache.ranger.patch`)

Use a Java patch only when existing rows must be transformed (service-def updates, back-fills). Pure schema changes are SQL patches (see `security-admin-db`).

## Naming and discovery

`Patch<Description>_J<5 digits>.java`, currently up to `J10066`. `db_setup.py` discovers compiled classes with `^Patch.*?J\d{5}.class$` under
`ews/webapp/WEB-INF/classes/org/apache/ranger/patch`, sorts by number, and skips those whose `J<n>` row in `x_db_version_h` has `active='Y'`.
Ordered variants: `PatchPreSql_<NNN>_<Desc>_J<n>` runs right before SQL patch `NNN`; `PatchPostSql_<NNN>_...` right after (none exist yet).

Every new patch class needs a `TypeName` suppression in `dev-support/checkstyle-suppressions.xml` (underscore in class name).

## Template (`PatchForOzoneServiceDefPolicyConditionUpdate_J10066`)

```java
@Component
public class PatchForFoo_J10067 extends BaseLoader {
    private static final Logger logger = LoggerFactory.getLogger(PatchForFoo_J10067.class);

    @Autowired
    RangerDaoManager daoMgr;

    @Autowired
    ServiceDBStore svcDBStore;

    public static void main(String[] args) {
        logger.info("main()");

        try {
            PatchForFoo_J10067 loader = (PatchForFoo_J10067) CLIUtil.getBean(PatchForFoo_J10067.class);

            loader.init();

            while (loader.isMoreToProcess()) {
                loader.load();
            }

            logger.info("Load complete. Exiting!!!");

            System.exit(0);
        } catch (Exception e) {
            logger.error("Error loading", e);

            System.exit(1);
        }
    }

    @Override
    public void init() throws Exception {
        /* Do Nothing */
    }

    @Override
    public void printStats() {
        logger.info("PatchForFoo_J10067 data loading");
    }

    @Override
    public void execLoad() {
        logger.info("==> PatchForFoo_J10067.execLoad()");

        try {
            updateFoo();
        } catch (Exception e) {
            logger.error("Error while applying PatchForFoo_J10067", e);

            throw new RuntimeException("PatchForFoo_J10067 failed", e);
        }

        logger.info("<== PatchForFoo_J10067.execLoad()");
    }
}
```

Rules:
- `BaseLoader.load()` is `@Transactional`; `execLoad()` runs inside it. A `RuntimeException` gives a non-zero exit, which makes `db_setup.py` delete the claim row and stop.
- Be idempotent inside the patch too (check whether the change is already present and return early with an info log).
- Existing patches use the `logger` field name; either `logger` or `LOG` is accepted here, match the file you copy.
- Patches never touch `x_db_version_h`; `db_setup.py` inserts `J<n>` with `active='N'`, runs `main`, then flips to `'Y'`.
- Add `src/test/java/org/apache/ranger/patch/TestPatchForFoo_J10067.java` (about 50 exist; Mockito-based, no real DB).

## How they run

`security-admin/scripts/setup.sh` ends with `db_setup.py` (schema + SQL patches, including pre/post Java hooks) then `db_setup.py -javapatch`.
Java is launched with the webapp classpath plus the JDBC connector, `-Dlogback.configurationFile=...`, heap from `ranger_admin_max_heap_size`.

## `patch/cliutil`

Operator tools that share the `BaseLoader` + `main` shape but are not numbered, so never auto-run: `ChangePasswordUtil`, `ChangeUserNameUtil`,
`RoleBasedUserSearchUtil`, `UpdateUserAndGroupNamesInJson`, `DbToSolrMigrationUtil`, `TrxLogV2MigrationUtil`, `MetricUtil`.
Driven by wrappers in `security-admin/scripts/` (`changepasswordutil.py`, `changeusernameutil.py`, `rolebasedusersearchutil.py`, `ranger-admin-transaction-log-migrate.sh`).
