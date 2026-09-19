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

# HSM and key stores

Ranger KMS protects every zone key with a single *master key*. Where that master key lives decides how
strong the whole deployment is: by default it is stored, encrypted with a password, in the KMS database, which
needs no extra infrastructure but means that anyone with the database and the password can recover all keys. For stronger
guarantees the master key can be created inside a hardware security module (SafeNet Luna, SafeNet
KeySecure) or a cloud key service (Azure Key Vault, AWS KMS, Google Cloud KMS, Tencent KMS), where it never
leaves the device and every use is logged by the provider.

This page explains each option, the properties that enable it, and the utilities that move a master key
between the database and a provider.

## How the master key is used

`RangerKeyStoreProvider` (`kms/src/main/java/org/apache/hadoop/crypto/key/RangerKeyStoreProvider.java`)
picks exactly one master key provider at start, in this order of precedence:

| Order | Provider | Enabled by | Class |
|---|---|---|---|
| 1 | Luna HSM | `ranger.ks.hsm.enabled` | `RangerHSM` |
| 2 | SafeNet KeySecure | `ranger.kms.keysecure.enabled` | `RangerSafenetKeySecure` |
| 3 | Azure Key Vault | `ranger.kms.azurekeyvault.enabled` | `RangerAzureKeyVaultKeyGenerator` |
| 4 | AWS KMS | `ranger.kms.awskms.enabled` | `RangerAWSKMSProvider` |
| 5 | Tencent KMS | `ranger.kms.tencentkms.enabled` | `RangerTencentKMSProvider` |
| 6 | Google Cloud KMS | `ranger.kms.gcp.enabled` | `RangerGoogleCloudHSMProvider` |
| 7 | Database | none of the above | `RangerMasterKey` |

Zone keys always stay in the `ranger_keystore` table; only the way they are wrapped changes. With the
database, Luna and KeySecure providers the KMS process holds the master key and wraps zone keys itself.
With the cloud providers the wrapped material is produced by the remote service, so the KMS needs
network access to it for every key creation, rollover and EEK operation on an uncached key version.

All provider settings are properties in `dbks-site.xml`. Every secret has two properties: the secret itself
and an `.alias` property. At startup the KMS looks the alias up in the credential store named by
`ranger.ks.jpa.jdbc.credential.provider.path` and uses the clear-text property only if the alias yields
nothing, so set the clear-text property to `_` and add the secret with `ranger_credential_helper.py`
(shipped in the KMS directory):

```bash
python3 ranger_credential_helper.py -l "cred/lib/*" \
  -f /etc/ranger/kms/rangerkms.jceks \
  -k ranger.kms.hsm.partition.password -v '<partition password>' -c 1
```

## Database (default)

| Key | Default | Type | Description |
|---|---|---|---|
| `ranger.db.encrypt.key.password` | `Str0ngPassw0rd` | Password | Master key password, from which the key that wraps the master key is derived |
| `ranger.ks.masterkey.credential.alias` | `ranger.ks.masterkey.password` | String | Credential-store alias of the master key password |
| `ranger.kms.service.masterkey.password.cipher` | `AES` | String | Master key cipher |
| `ranger.kms.service.masterkey.password.size` | `256` | Integer | Master key size in bits |
| `ranger.kms.service.masterkey.password.encryption.algorithm` | `PBEWithMD5AndDES` | String | PBE algorithm wrapping the master key |
| `ranger.kms.service.masterkey.password.md.algorithm` | `SHA` | String | Digest for the PBE key |
| `ranger.kms.service.masterkey.password.salt` | `abcdefghijklmnopqrstuvwxyz01234567890` | String | PBE salt |
| `ranger.kms.service.masterkey.password.salt.size` | `8` | Integer | PBE salt size |
| `ranger.kms.service.masterkey.password.iteration.count` | `1000` | Integer | PBE iteration count |

The master key is generated once (`ranger_masterkey` table) on the first start; later starts verify the
password against it. `VerifyIsDBMasterkeyCorrect.sh <password>` checks a password without starting the
server. When `ranger.keystore.file.type` is `bcfks` (FIPS builds), `RangerKeyStoreProvider` re-encrypts an
existing master key with a FIPS-compliant algorithm on start. The master key password must be set even
when another provider is enabled; the KMS refuses to start without it.

## Luna HSM

Ranger KMS uses the Luna JSP provider (`com.safenetinc.luna.provider.LunaProvider`) and loads a `Luna`
`KeyStore` for the configured partition; the master key is an AES key (`ranger.kms.hsm.masterkey.size`,
256 bits) with alias `ranger.ks.hsm.masterkey.alias` (`RangerKMSKey`) inside the partition.

### Client setup

1. Install the Luna client (Luna SDK, JSP and JCProv components) on the KMS host, register the appliance
   (`vtl addServer`), create the client certificate (`vtl createCert`), register the client on the
   appliance and assign it a partition (`client register`, `client assignPartition`). `vtl verify` must list
   the partition. Give the account Ranger KMS runs as read access to `/usr/safenet/lunaclient/cert/*/*.pem`.
2. Make the provider available to the JVM: copy `libLunaAPI.so` and the `Luna*.jar` files from
   `/usr/safenet/lunaclient/jsp/lib/` to a directory on the KMS classpath / `java.library.path`, and add to
   the JDK `java.security`:

    ```properties
    security.provider.<n>=com.safenetinc.luna.provider.LunaProvider
    com.safenetinc.luna.provider.createExtractableKeys=true
    ```

    `createExtractableKeys=true` is required: the KMS must be able to read the master key bytes to wrap
    zone keys.

3. Use a **separate partition per KMS cluster**.

### Configuration

| Key | Default | Type | Description |
|---|---|---|---|
| `ranger.ks.hsm.enabled` | `false` | Boolean | Keep the master key in the HSM |
| `ranger.ks.hsm.type` | `LunaProvider` | String | Provider name |
| `ranger.ks.hsm.partition.name` | (none) | String | Partition label, or the HA group label |
| `ranger.ks.hsm.partition.password` | (none) | Password | Partition password; use `_` and the credential store |
| `ranger.ks.hsm.partition.password.alias` | `ranger.kms.hsm.partition.password` | String | Credential-store alias of the partition password |
| `ranger.kms.hsm.masterkey.size` | `256` | Integer | AES key size in bits |
| `ranger.ks.hsm.masterkey.alias` | `RangerKMSKey` | String | Object label of the master key in the partition |

On the first start with `ranger.ks.hsm.enabled=true` the KMS creates the master key in the partition; `partition
showContents` on the appliance (or `lunacm` > `par con`) lists it. `VerifyIsHSMMasterkeyCorrect.sh
LunaProvider <partition>` validates the connection and password without starting the server.

### HSM high availability

Two Luna appliances with the same cloning domain can form an HA group: create a partition on each with
the same password, register the client with both, then on the client create the group and add members
(`lunacm` > `hagroup creategroup`, `hagroup addMember`, `hagroup synchronize -enable`, `hagroup HAOnly
-enable`). Use the group label as `ranger.ks.hsm.partition.name`; nothing else changes. After enabling
synchronization, confirm the master key object is present in both partitions before relying on failover.

## SafeNet KeySecure

Ranger KMS talks to Gemalto/Thales SafeNet KeySecure with the NAE-XML protocol through the SunPKCS11
provider and the Ingrian client library (RANGER-2331).

1. On KeySecure: add a device with protocol **NAE-XML** on port `9000` (with or without SSL) and a local user
   with *User Administration* and *Change Password* permissions.
2. On the KMS host: copy `IngrianNAE.properties`, `libIngPKCS11.so` and `sunpkcs11.cfg` from the KeySecure
   client package into a directory such as `/opt/safenetConf/64/8.3.1/` readable by the account Ranger KMS runs as. In
   `IngrianNAE.properties` set `NAE_IP`, `NAE_Port=9000`, `Protocol=tcp` (or `ssl` with `CA_File` pointing at
   the KeySecure CA certificate). Export for the KMS process:

    ```bash
    export IngrianNAE_Properties_Conf_Slot_ID_Max=100
    export IngrianNAE_Properties_Conf_SessionID_Max=100
    export NAE_Properties_Conf_Filename=/opt/safenetConf/64/8.3.1/IngrianNAE.properties
    ```

3. Configure the KMS:

| Key | Default | Type | Description |
|---|---|---|---|
| `ranger.kms.keysecure.enabled` | `false` | Boolean | Keep the master key on KeySecure |
| `ranger.kms.keysecure.login.username` | `user1` | String | KeySecure user |
| `ranger.kms.keysecure.login.password` | `t1e2s3t4` | Password | KeySecure password; use `_` and the credential store |
| `ranger.kms.keysecure.login.password.alias` | `ranger.ks.login.password` | String | Credential-store alias of the KeySecure password |
| `ranger.kms.keysecure.masterkey.name` | `safenetmasterkey` | String | Name of the key created on KeySecure |
| `ranger.kms.keysecure.masterkey.size` | `256` | Integer | Key size in bits |
| `ranger.kms.keysecure.sunpkcs11.cfg.filepath` | `/opt/safenetConf/64/8.3.1/sunpkcs11.cfg` | Path | SunPKCS11 configuration file |
| `ranger.kms.keysecure.hostname` | `SunPKCS11-keysecurehn` | String | Provider instance name |
| `ranger.kms.keysecure.provider.type` | `SunPKCS11` | String | Security provider |
| `ranger.kms.keysecure.UserPassword.Authentication` | `true` | Boolean | Authenticate with user name and password |

On start the KMS creates the master key on KeySecure under `ranger.kms.keysecure.masterkey.name` (KeySecure shows a
second, internally generated key next to it). For SSL, create a local CA and a server certificate on
KeySecure, download the CA certificate to the KMS host and reference it as `CA_File`.

## Azure Key Vault

| Key | Default | Type | Description |
|---|---|---|---|
| `ranger.kms.azurekeyvault.enabled` | `false` | Boolean | Wrap zone keys with a key in Azure Key Vault |
| `ranger.kms.azurekeyvault.url` | (none) | URL | `https://<vault>.vault.azure.net/` |
| `ranger.kms.azure.client.id` | (none) | String | Service principal (application) id |
| `ranger.kms.azure.keyvault.ssl.enabled` | `false` | Boolean | `false`: authenticate with the client secret; `true`: authenticate with a certificate |
| `ranger.kms.azure.client.secret` | (none) | Password | Client secret; use `_` and the credential store |
| `ranger.kms.azure.client.secret.alias` | `ranger.ks.azure.client.secret` | String | Credential-store alias of the client secret |
| `ranger.kms.azure.keyvault.certificate.path` | (none) | Path | `.pfx` or `.pem` certificate for certificate mode |
| `ranger.kms.azure.keyvault.certificate.password` | (none) | Password | Certificate password, if any |
| `ranger.kms.azure.masterkey.name` | (none) | String | Name of the key created in the vault |
| `ranger.kms.azure.masterkey.type` | (none) | Enum | `RSA`, `RSA_HSM`, `EC`, `EC_HSM` or `OCT`; any other value is treated as `RSA` |
| `ranger.kms.azure.zonekey.encryption.algorithm` | (none) | Enum | `RSA_OAEP`, `RSA_OAEP_256` or `RSA1_5`; any other value is treated as `RSA_OAEP` |

The service principal needs *get*, *create*, *wrapKey* and *unwrapKey* permissions on the vault. Zone keys
are wrapped by the vault key with the configured algorithm and stored in the database.

## AWS KMS

| Key | Default | Type | Description |
|---|---|---|---|
| `ranger.kms.awskms.enabled` | `false` | Boolean | Wrap zone keys with a key in AWS KMS |
| `ranger.kms.awskms.masterkey.id` | (none) | String | Key id or `alias/...` of the customer master key |
| `ranger.kms.aws.client.region` | (none) | String | Region of the key |
| `ranger.kms.aws.client.accesskey` | (none) | String | Access key; leave empty to use the default AWS credential chain (instance profile, environment) |
| `ranger.kms.aws.client.secretkey` | (none) | Password | Secret key; use `_` and the credential store |
| `ranger.kms.aws.client.secretkey.alias` | `ranger.ks.aws.client.secretkey` | String | Credential-store alias of the secret key |

## Google Cloud KMS

| Key | Default | Type | Description |
|---|---|---|---|
| `ranger.kms.gcp.enabled` | `false` | Boolean | Wrap zone keys with a key in Google Cloud KMS |
| `ranger.kms.gcp.project.id` | (none) | String | Project |
| `ranger.kms.gcp.location.id` | (none) | String | Key ring location (`us-east1`, `global`, ...) |
| `ranger.kms.gcp.keyring.id` | (none) | String | Key ring |
| `ranger.kms.gcp.masterkey.name` | (none) | String | Name of the key created in the ring |
| `ranger.kms.gcp.cred.file` | (none) | Path | Service-account JSON credentials |

## Tencent KMS

| Key | Default | Type | Description |
|---|---|---|---|
| `ranger.kms.tencentkms.enabled` | `false` | Boolean | Wrap zone keys with a key in Tencent KMS |
| `ranger.kms.tencent.masterkey.id` | (none) | String | Master key id |
| `ranger.kms.tencent.client.id` | (none) | String | Secret id |
| `ranger.kms.tencent.client.secret` | (none) | Password | Secret key; use `_` and the credential store |
| `ranger.kms.tencent.client.secret.alias` | `ranger.ks.tencent.client.secret` | String | Credential-store alias of the secret key |
| `ranger.kms.tencent.client.region` | `ap-beijing` | String | Region |

## Migration utilities

All scripts live in the KMS directory, need `JAVA_HOME`, read the current
`ews/webapp/WEB-INF/classes/conf/dbks-site.xml`, and must be run with the KMS **stopped**. The general
procedure is: stop KMS, make sure `dbks-site.xml` describes both the database and the target provider, run
the script, enable the new provider (and disable the old one) in `dbks-site.xml`, start KMS, then delete
the old copy of the master key once the KMS runs correctly (the `ranger_masterkey` row, the HSM object via
`partition clear`, or the vault key).

| Script | Arguments | Purpose |
|---|---|---|
| `DBMK2HSM.sh` | `<HSMType> <partitionName>` | Database to Luna HSM; prompts for the partition password |
| `HSMMK2DB.sh` | `<HSMType> <partitionName>` | Luna HSM to database; prompts for the partition password |
| `DBMKTOKEYSECURE.sh` | see below | Database to KeySecure |
| `KEYSECUREMKTOKMSDB.sh` | `<masterKeyPassword>` | KeySecure to database |
| `DBMKTOAZUREKEYVAULT.sh` | see below | Database to Azure Key Vault; re-wraps every zone key |
| `MigrateMKeyStorageDbToGCP.sh` | see below | Database to Google Cloud KMS; creates the key and re-wraps every zone key |
| `VerifyIsDBMasterkeyCorrect.sh` | `<password>` | Check a database master key password |
| `VerifyIsHSMMasterkeyCorrect.sh` | `<HSMType> <partitionName>` | Check HSM access |
| `exportKeysToJCEKS.sh` | `<file> [keyStoreType]` | Export all zone keys to a JCEKS file; prompts for passwords |
| `importJCEKSKeys.sh` | `<file> [keyStoreType]` | Import zone keys from a JCEKS file, for example from the Hadoop `JavaKeyStoreProvider` |

Scripts with longer argument lists:

```bash
./DBMKTOKEYSECURE.sh <keySecureMasterKeyName> <keySecureUsername> <keySecurePassword> <sunpkcs11CfgFilePath>

./DBMKTOAZUREKEYVAULT.sh <azureMasterKeyName> <azureMasterKeyType> <zoneKeyEncryptionAlgo> \
    <azureKeyVaultUrl> <azureClientId> <isSSLEnabled> <clientSecret or certificatePath> [<certificatePassword>]

# needs RANGER_KMS_HOME, RANGER_KMS_CONF and SQL_CONNECTOR_JAR exported
./MigrateMKeyStorageDbToGCP.sh <gcpMasterKeyName> <gcpProjectName> <gcpKeyRingName> \
    <gcpKeyRingLocationName> <pathOfJsonCredFile>
```

Example - move the master key from the database into a Luna partition:

```bash
./ranger-kms stop
./DBMK2HSM.sh LunaProvider par19          # enter the partition password when prompted
# in dbks-site.xml: ranger.ks.hsm.enabled=true, ranger.ks.hsm.partition.name=par19,
# and store the partition password under the alias ranger.kms.hsm.partition.password
./ranger-kms start
# after verifying: delete the row from ranger_masterkey
```

And back:

```bash
./ranger-kms stop
./HSMMK2DB.sh LunaProvider par19
# in dbks-site.xml: ranger.ks.hsm.enabled=false; start, then "partition clear -par par19" on the appliance
```

There is no utility to migrate directly between two non-database providers; go through the database.

!!! warning
    Migration copies the master key; it does not re-wrap zone keys for the Luna and KeySecure providers
    (the same AES master key is used on both sides). Take a database backup and an
    `exportKeysToJCEKS.sh` export before migrating, and test decryption of an existing encryption zone
    file afterwards.

## Further reading

- [Ranger KMS overview](service.md)
- [High availability](high-availability.md)
- Source: [`kms/src/main/java/org/apache/hadoop/crypto/key`](https://github.com/apache/ranger/tree/master/kms/src/main/java/org/apache/hadoop/crypto/key),
  [`kms/config/kms-webapp/dbks-site.xml`](https://github.com/apache/ranger/blob/master/kms/config/kms-webapp/dbks-site.xml)
- cwiki: [Ranger KMS Luna HSM support](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=65864440),
  [Luna 7 HSM](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=143428967),
  [SafeNet KeySecure integration](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=103092110)
