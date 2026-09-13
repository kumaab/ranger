---
name: ranger-kms
description: Architecture of Ranger KMS (kms module, plugin-kms) - Hadoop KMS REST wire protocol, KeyProviderCryptoExtension chain to RangerKeyStoreProvider, RangerKMSMKI master-key providers (DB, Luna HSM, KeySecure, Azure Key Vault, AWS KMS, Tencent, GCP), ranger_masterkey/ranger_keystore tables and kms/scripts/db_setup.py, kms-site/dbks-site/ranger-kms-site config, RangerKmsAuthorizer key ACLs, migration CLIs, metrics. Use when changing anything under kms/ or plugin-kms/.
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

# Ranger KMS

`kms/` is a standalone webapp started by `kms/scripts/ranger-kms` through the shared `org.apache.ranger.server.tomcat.EmbeddedServer` (`embeddedwebserver`),
HTTP port `ranger.service.http.port` = 9292. It implements the Hadoop KMS wire protocol, so HDFS TDE clients work unchanged. Packages are deliberately
`org.apache.hadoop.crypto.key.*`; match that when adding files. Style rules: `ranger-conventions`.

## Layers

- **REST**: `kms.server.KMS` (`@Path("/v1")` via `KMSRESTConstants.SERVICE_VERSION`): key create/delete/rollover, `/keys/metadata`, `/keys/names`,
  `/key/{name}/_metadata|_currentversion|_versions|_eek|_invalidatecache|_reencryptbatch`, `/keyversion/{versionName}/_eek`. Filters/providers:
  `KMSAuthenticationFilter`, `KMSMDCFilter`, `HSTSFilter`, `KMSExceptionsProvider`, `KMSJSONReader`/`KMSJSONWriter`, `KMSJMXServlet`. `RangerKMSRestApi` adds `/api/status`;
  `MetricREST` adds `/api/metrics/prometheus|json`.
- **Provider chain** (`KMSWebApp`): `KeyProviderCryptoExtension` -> `EagerKeyGeneratorKeyProviderCryptoExtension` -> `KeyAuthorizationKeyProvider` -> `RangerKeyStoreProvider`
  (`SCHEME_NAME = "dbks"`, so URIs read `dbks://http@host:9292/kms`). EEK flow: `generateEncryptedKey` makes a DEK, encrypts it with the EZ key, returns the EDEK;
  `_eek?eek_op=decrypt` returns the DEK. `KeyAuthorizationKeyProvider.doAccessCheck(name, KeyOpType)` consults a `KeyACLs` implementation.
- **Authorization** (`plugin-kms`): `RangerKmsAuthorizer implements KeyACLs` (wired by `hadoop.kms.security.authorization.manager` in `kms-site.xml`) holds
  `RangerKMSPlugin extends RangerBasePlugin` built as `super("kms", "kms")`, mapping `KMSACLsType.Type` to access types `create`, `delete`, `rollover`, `setkeymaterial`,
  `get`, `getkeys`, `getmetadata`, `generateeek`, `decrypteek`. Service-def `agents-common/src/main/resources/service-defs/ranger-servicedef-kms.json`, resource `keyname`
  (`RangerKMSResource`). Admin-side key management is gated on `ROLE_KEY_ADMIN` / `ROLE_KEY_ADMIN_AUDITOR` (`XKeyREST`, `KmsKeyMgr`).
- **Storage**: `RangerKeyStore extends KeyStoreSpi` persists to `ranger_keystore` (entity `XXRangerKeyStore`, `RangerKMSDao`); `RangerMasterKey` uses `ranger_masterkey`
  (`XXRangerMasterKey`, `RangerMasterKeyDao`). `RangerKMSDB` builds JPA unit `persistence_ranger_server` from `ranger.ks.jpa.jdbc.{dialect,driver,url,user,password}` and
  `ranger.ks.db.ssl.*`. DAOs mirror Admin's `BaseDao`/`DaoManager` pattern in `org.apache.ranger.kms.dao`.

## Master keys (`RangerKMSMKI`)

SPI: `generateMasterKey`, `getMasterKey`, defaulted `encryptZoneKey`/`decryptZoneKey`, `onInitialization`, `reencryptMKWithFipsAlgo`, `setExternalKeyAsMK`.
`RangerKeyStoreProvider` picks exactly one, first flag wins:

| Flag in `dbks-site.xml` | Implementation |
|---|---|
| `ranger.ks.hsm.enabled` | `RangerHSM` (SafeNet Luna) |
| `ranger.kms.keysecure.enabled` | `RangerSafenetKeySecure` |
| `ranger.kms.azurekeyvault.enabled` | `RangerAzureKeyVaultKeyGenerator` (+ `AzureKeyVaultClientAuthenticator`) |
| `ranger.kms.awskms.enabled` | `RangerAWSKMSProvider` |
| `ranger.kms.tencentkms.enabled` | `RangerTencentKMSProvider` |
| `ranger.kms.gcp.enabled` | `RangerGoogleCloudHSMProvider` |
| none | `RangerMasterKey` (PBE into `ranger_masterkey`) |

Secrets resolve from the JCEKS at `ranger.ks.jpa.jdbc.credential.provider.path` via `*.alias` keys (`ranger.ks.masterkey.credential.alias`, `ranger.ks.hsm.partition.password.alias`,
`ranger.kms.azure.client.secret.alias`, ...), falling back to the plaintext property. PBE algorithms: `SupportedPBECryptoAlgo`.

## Config files

`kms/config/kms-webapp/kms-site.xml` (`KMSConfiguration.KMS_SITE_XML`) and `kms/config/kms-webapp/dbks-site.xml` (loaded as `KMSConfiguration.KMS_ACLS_XML`), both from the
`kms.config.dir` system property; `kms/config/webserver/ranger-kms-site.xml` for the embedded Tomcat. Install-time keys in `kms/scripts/install.properties`
(`KMS_MASTER_KEY_PASSWD`, `DB_FLAVOR`, `POLICY_MGR_URL`, `REPOSITORY_NAME=kmsdev`); credentials land in `ranger-kms.jceks` via `credentialbuilder`.

## Install, schema, migration

`kms/scripts/setup.sh` -> `dba_script.py` -> KMS's **own** `kms/scripts/db_setup.py` applying `kms/scripts/db/{mysql,oracle,postgres,sqlserver,sqlanywhere}/kms_core_db.sql`.
Do not point the `security-admin-db` recipe at it. Migration CLIs in `org.apache.hadoop.crypto.key`, each with a shell wrapper in `kms/scripts/`: `JKS2RangerUtil` (`importJCEKSKeys.sh`),
`Ranger2JKSUtil` (`exportKeysToJCEKS.sh`), `DB2HSMMKUtil` (`DBMK2HSM.sh`), `HSM2DBMKUtil` (`HSMMK2DB.sh`), `DBToAzureKeyVault`, `DBToKeySecure`, `KeySecureToRangerDBMKUtil`,
`MigrateDBMKeyToGCP`, `VerifyIsDBMasterkeyCorrect`, `VerifyIsHSMMasterkeyCorrect`.

## Metrics and audit

`KMSMetricsCollector` (singleton) + `KMSMetrics.KMSMetric` enum (COUNTER/GAUGE pairs such as `KEY_CREATE_*`, `EEK_GENERATE_*`, `EEK_DECRYPT_*`, `REENCRYPT_EEK_BATCH_*`,
`UNAUTHORIZED_CALLS_COUNT`, `TOTAL_CALL_COUNT`); latency via try-with-resources `KMSMetricsCollector.APIMetric`. `KMSMetricWrapper` feeds `KMSMetricSource` into
`RangerMetricsSystemWrapper`. Audit: `KMSAudit`, `KMSAuditLogger`, `SimpleKMSAuditLogger`.

## Tests

```bash
mvn -pl kms -DskipTests verify
mvn -pl kms test -Dtest=TestRangerKeyStore    # also RangerMasterKeyTest, RangerKMSDBTest, TestFIPSRangerKeyStore, TestDB2HSMMKUtil, TestRangerAWSKMSProvider,
                                              # TestRangerAzureKeyVaultKeyGenerator, TestRangerGoogleCloudHSMProvider, TestRangerSafenetKeySecure, TestKMSMetricsWrapper
```

Fixtures: `kms/src/test/resources/kms/{kms-site,dbks-site}.xml`.
