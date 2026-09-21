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

# Security hardening

Ranger Admin decides who may access your data, so the Admin service itself must be locked down at least
as well as the systems it protects. The shipped defaults are meant to be easy to get running: Admin
listens on plain HTTP, has well-known account names and uses published defaults for several
secrets. This page is the checklist to turn that into a production deployment, with the properties behind
each item.

## Checklist

| Step | Where |
| --- | --- |
| Change the passwords of `admin`, `keyadmin`, `rangerusersync` and `rangertagsync` | UI or `changepasswordutil.py`, and the matching UserSync/TagSync configuration |
| Serve the UI and API over HTTPS only | `ranger.service.https.attrib.*` |
| Secure plugin-to-Admin traffic with TLS and, optionally, client certificates | `ranger-<type>-policymgr-ssl.xml` on the plugin, `commonNameForCertificate` on the service |
| Encrypt the database connection | `ranger.db.ssl.*` |
| Keep secrets out of clear-text files | Credential store `.jceks`, file permissions |
| Use an external identity provider and Kerberos or SSO for people | [Authentication](authentication.md) |
| Keep CSRF protection and security headers enabled | `ranger.rest-csrf.*`, Spring headers |
| Limit concurrent UI sessions per user | `ranger.session.limit.concurrency`, see [Authentication](authentication.md#concurrent-ui-sessions) |
| Leave anonymous access disabled | `ranger.admin.allow.unauthenticated.access=false` |
| Restrict network access to 6080/6182 and the shutdown port 6085 | Firewall / security groups |
| Send audits to a protected store and review **Audit > Admin** and **Login Sessions** regularly | [Audit stores](../audit/audit-stores.md) |

## Change the default passwords

The four built-in accounts are created with the database schema; change their passwords right after
the first start. `admin` and `keyadmin` are changed in the UI (user menu → Profile) or with
`python3 changepasswordutil.py admin <old> <new>` in the Admin home directory. `rangerusersync` and
`rangertagsync` are used by other services, so after changing them in Admin update the UserSync and
TagSync configuration as described in [UserSync operations](../usersync/operations.md) and
[TagSync](../tagsync/service.md).

Password rules enforced by Admin: at least 8 characters with a digit, a lower-case and an upper-case
letter, not equal to the login or the user's names, and not one of the last `ranger.password.history.count`
(default 4) passwords. Accounts lock after `ranger.admin.login.autolock.maxfailure` (5) failures in
`ranger.admin.login.autolock.window.seconds` (300).

## HTTPS for the Admin UI and API

Put the server certificate into a keystore, store the keystore password in the credential store, and
enable the HTTPS connector in `ranger-admin-site.xml`:

```xml title="ranger-admin-site.xml"
<property><name>ranger.externalurl</name><value>https://ranger.example.com:6182</value></property>
<property><name>ranger.service.https.attrib.ssl.enabled</name><value>true</value></property>
<property><name>ranger.service.https.port</name><value>6182</value></property>
<property><name>ranger.service.https.attrib.keystore.file</name><value>/etc/ranger/admin/conf/ranger-admin-keystore.jks</value></property>
<property><name>ranger.service.https.attrib.keystore.keyalias</name><value>rangeradmin</value></property>
<property><name>ranger.service.https.attrib.keystore.credential.alias</name><value>keyStoreCredentialAlias</value></property>
```

```bash
python3 ranger_credential_helper.py -l "cred/lib/*" -f /etc/ranger/admin/rangeradmin.jceks   -k keyStoreCredentialAlias -v '<keystore-password>' -c 1
```

With HTTPS enabled, the HTTPS connector replaces the HTTP connector, so port 6080 is no longer open.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.service.https.attrib.ssl.enabled` | `false` | Boolean | Serve HTTPS on `ranger.service.https.port` (6182). |
| `ranger.service.https.attrib.keystore.file` | `/etc/ranger/admin/keys/server.jks` | Path | Server keystore. |
| `ranger.service.https.attrib.keystore.keyalias` | `myKey` | String | Key alias. |
| `ranger.service.https.attrib.keystore.credential.alias` | `keyStoreCredentialAlias` | String | Credential-store alias of the keystore password. |
| `ranger.service.https.attrib.ssl.protocol` | `TLS` | String | SSL context protocol. |
| `ranger.service.https.attrib.ssl.enabled.protocols` | `TLSv1.2` | List | Protocols offered to clients. |
| `ranger.tomcat.ciphers` | (none) | List | Restrict the cipher suites. |
| `ranger.service.https.attrib.client.auth` | `false` | Enum | `false`, `want` or `true`; set `want` to request client certificates from plugins. |
| `ranger.service.http.enabled` | `true` | Boolean | When `false`, plugin policy downloads require HTTPS and a client certificate matching `commonNameForCertificate`. |
| `ranger.truststore.file` | (none) | Path | Truststore for Admin's outbound TLS connections and for validating plugin client certificates. |
| `ranger.truststore.alias` | `trustStoreAlias` | String | Credential-store alias of the truststore password. |
| `ranger.truststore.file.type` | `jks` | String | Truststore type. |

Also review the connector attributes exposed by `EmbeddedServer`
(`ranger.service.http.connector.attrib.allowTrace`, `maxPostSize`, `maxParameterCount`, `enableLookups`,
`asyncTimeout`, `URIEncoding`) and keep `ranger.valve.errorreportvalve.showserverinfo` and
`ranger.valve.errorreportvalve.showreport` at `false` so error pages do not leak server details.

## TLS between plugins and Admin

Plugins download policies from the Admin URL configured in `ranger.plugin.<type>.policy.rest.url`. With HTTPS enabled on Admin, each plugin needs a
truststore that contains the Admin certificate (or its CA), configured in the plugin's
`ranger-<type>-policymgr-ssl.xml`:

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `xasecure.policymgr.clientssl.truststore` | (none) | Path | Truststore with the Admin server certificate or CA. |
| `xasecure.policymgr.clientssl.truststore.type` | `jks` | String | Truststore type. |
| `xasecure.policymgr.clientssl.truststore.credential.file` | (none) | URL | Credential store (`jceks://file/...`) holding the truststore password under alias `sslTrustStore`. |
| `xasecure.policymgr.clientssl.keystore` | (none) | Path | Client keystore, only for mutual TLS. |
| `xasecure.policymgr.clientssl.keystore.type` | `jks` | String | Keystore type. |
| `xasecure.policymgr.clientssl.keystore.credential.file` | (none) | URL | Credential store holding the keystore password under alias `sslKeyStore`. |

For mutual TLS, set `ranger.service.https.attrib.client.auth=want` on Admin and put the plugin's
certificate (or CA) into `ranger.truststore.file`. Then set the service config property
`commonNameForCertificate` on the service in Admin to the CN of the plugin certificate: Admin rejects
policy downloads whose client certificate CN does not match (`AssetMgr`), so a stolen policy URL cannot be
used from an arbitrary host. The plugin pages under [Plugins](../../plugins/index.md) show where the SSL
file lives for each component.

!!! note
    In kerberized clusters plugins use the `/service/plugins/secure/policies/download/{serviceName}`
    endpoint and authenticate with their Kerberos identity, so the download is protected even without
    client certificates. Keep `ranger.admin.allow.unauthenticated.download.access=false` unless you have
    a specific reason.

## TLS to the database

Encrypt the JDBC connection, especially when the database is on another host:

```xml title="ranger-admin-site.xml"
<property><name>ranger.db.ssl.enabled</name><value>true</value></property>
<property><name>ranger.db.ssl.required</name><value>true</value></property>
<property><name>ranger.db.ssl.verifyServerCertificate</name><value>true</value></property>
<property><name>ranger.db.ssl.auth.type</name><value>1-way</value></property>
<property><name>ranger.truststore.file</name><value>/etc/ranger/admin/truststore.jks</value></property>
```

Use `ranger.db.ssl.auth.type=2-way` with `ranger.keystore.file` when the server requires client
certificates, and keep the store passwords in the credential store (`ranger.truststore.alias`,
`ranger.keystore.alias`). For PostgreSQL also set `ranger.db.ssl.certificateFile`. Restart Admin after
changes. Ranger KMS has the same settings under `ranger.ks.db.ssl.*`. Details are in
[Database](database.md#tls-to-the-database).

## Credential store and file permissions

Keep the database, Solr, keystore, truststore and LDAP bind passwords in the credential store named by
`ranger.credential.provider.path` instead of in clear text. The site file then only references aliases:

| Alias key | Default alias | Secret |
| --- | --- | --- |
| `ranger.jpa.jdbc.credential.alias` | `ranger.db.password` | Database password |
| `ranger.solr.audit.credential.alias` | `ranger.solr.password` | Solr basic-auth password |
| `ranger.service.https.attrib.keystore.credential.alias` | `keyStoreCredentialAlias` | HTTPS keystore password |
| `ranger.keystore.alias` | `keyStoreAlias` | Client keystore password |
| `ranger.truststore.alias` | `trustStoreAlias` | Truststore password |
| `ranger.ldap.binddn.credential.alias` | `ranger.ldap.binddn.password` | LDAP bind password |
| `ranger.ldap.ad.binddn.credential.alias` | `ranger.ad.binddn.password` | Active Directory bind password |

To add or change an entry:

```bash
python3 ranger_credential_helper.py -l "cred/lib/*" -f /etc/ranger/admin/rangeradmin.jceks \
  -k ranger.db.password -v '<new-password>' -c 1
```

Make the credential store and keystores readable only by the OS user that runs Admin (for example mode
`640`), keep the whole configuration directory readable only by that user, and do not commit files that
contain passwords to version control.

Service configuration passwords entered in the UI (for example the Hive JDBC password used for lookups)
are encrypted in the database with `ranger.password.encryption.key`, `ranger.password.salt`,
`ranger.password.iteration.count` (1000) and `ranger.password.encryption.algorithm`
(`PBEWithHmacSHA512AndAES_128`). The shipped key and salt are public; set site-specific values in
`ranger-admin-site.xml` before the first start so that stored service passwords are not decryptable
with the defaults.

## CSRF protection and security headers

Admin protects state-changing REST calls made from browsers with a per-session token that must be sent
in a custom header (`RangerCSRFPreventionFilter`):

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.rest-csrf.enabled` | `true` | Boolean | Enable the filter. |
| `ranger.rest-csrf.custom-header` | `X-XSRF-HEADER` | String | Header that must carry the session's CSRF token on non-idempotent requests from browsers. |
| `ranger.rest-csrf.methods-to-ignore` | `GET,OPTIONS,HEAD,TRACE` | List | Methods exempt from the check. |
| `ranger.rest-csrf.browser-useragents-regex` | `Mozilla,Opera,Chrome` | List | Only requests whose `User-Agent` matches are checked; `curl` and SDKs are unaffected. |
| `ranger.rest-csrf.token.length` | `20` | Integer | Length of the generated token. |

The UI obtains the token for its session from `GET /service/plugins/csrfconf` (field `_csrfToken`) and
sends it with every `POST`, `PUT` and `DELETE`; a client that sends a browser `User-Agent` has to do the
same, otherwise the request is rejected with HTTP 400. The Spring configuration additionally sets `X-Frame-Options`, `Cache-Control`,
`X-Content-Type-Options`, `X-XSS-Protection`, `Strict-Transport-Security` and a Content-Security-Policy
that only allows resources from the Admin origin; session fixation protection creates a new session on
login.

## Authentication choices

- Prefer LDAP/AD, Kerberos or Knox SSO over `NONE` for human users, and keep local accounts for
  break-glass use only. See [Authentication](authentication.md).
- Turn on `ranger.ldap.starttls` or use `ldaps://` so directory passwords are not sent in clear text.
- Give externally authenticated users the least privilege: `ranger.ldap.default.role=ROLE_USER`, and
  map administrator groups explicitly with UserSync role rules.
- Leave `ranger.admin.super.users`/`ranger.admin.super.groups` empty unless required, and audit their use.
- Enable header-based authentication only behind a proxy that overwrites the headers.
- Keep `ranger.admin.allow.unauthenticated.access=false`.
- Use HTTP Basic only over HTTPS; prefer Kerberos or bearer tokens for automation.

## Network exposure

| Port | Recommendation |
| --- | --- |
| 6080 (HTTP) | Enable HTTPS so that this port is not opened, or restrict it to the load balancer / localhost. |
| 6182 (HTTPS) | Expose to users, plugins, UserSync and TagSync only. |
| 6085 (shutdown) | Bound to the local host by Tomcat; block it externally anyway. |
| `/service/metrics/**`, `/service/actuator/health*` | Unauthenticated; restrict at the proxy if the metrics must not be public. |
| Database and audit store ports | Reachable from Admin hosts only. |

## Auditing the administrators

Every change made through Admin is recorded with the acting user in **Audit > Admin** and every login
in **Audit > Login Sessions**; policy downloads by plugins appear under **Audit > Plugins**. Send access
audits to a store with its own authentication (Solr with Kerberos, Elasticsearch/OpenSearch with
credentials or Kerberos), and set `ranger.audit.hive.query.visibility=false` if query text must not be
visible to auditors. Report vulnerabilities as described in [Security](../../project/security.md).

## Further reading

- cwiki: [Lock down Apache Ranger for production deployments](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=67640765),
  [SSL enabled MySQL](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=68717738)
- [`ranger-admin-default-site.xml`](https://github.com/apache/ranger/blob/master/security-admin/src/main/resources/conf.dist/ranger-admin-default-site.xml)
- [`RangerCSRFPreventionFilter.java`](https://github.com/apache/ranger/blob/master/security-admin/src/main/java/org/apache/ranger/security/web/filter/RangerCSRFPreventionFilter.java)
