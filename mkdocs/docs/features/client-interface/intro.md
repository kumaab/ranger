---
title: "Ranger Client Libraries"
---
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

# Introduction

Ranger provides client libraries in Java and Python to access Ranger REST APIs programmatically with ease.
Instead of hand-building HTTP requests, you work with typed objects such as `RangerService`, `RangerPolicy`
and `RangerRole`, and the library takes care of URLs, JSON, authentication and error handling.

Two kinds of clients exist:

- **Administration clients** talk to Ranger Admin (and Ranger KMS) to manage service definitions, services,
  policies, roles, security zones, tags, users and groups, and Governed Data Sharing objects. They are the
  right tool for automation: provisioning services, syncing policies from a source of control, or building
  your own policy UI.
- **Authorization clients** ask Ranger whether a user may perform an action, either in-process (Java) or
  through the Ranger PDP server (Java and Python). Use them when you are integrating an application with
  Ranger for access control. See [Authorization API and PDP](../../dev/authz-api.md).

## Which client to use

| Need | Java | Python |
| --- | --- | --- |
| Manage service defs, services, policies, roles, zones, tags | `RangerClient` (`org.apache.ranger:ranger-intg`) | `RangerClient` (`apache-ranger`) |
| Manage users, groups, group membership | REST only | `RangerUserMgmtClient` |
| Governed Data Sharing (datasets, projects, data shares) | REST only | `RangerGdsClient` |
| Ranger KMS keys | REST only | `RangerKMSClient` |
| Authorize an access in-process | `RangerEmbeddedAuthorizer` (`authz-embedded`) | — |
| Authorize an access through the PDP server | `RangerRemoteAuthorizer` (`authz-remote`) | `RangerPDPClient` |

Every client is a thin layer over the [REST API](../../dev/rest-api.md); anything a client does not wrap can
be called directly with the same credentials.

## Python

The `apache-ranger` package on PyPI provides `RangerClient`, `RangerUserMgmtClient`, `RangerKMSClient`,
`RangerGdsClient` and `RangerPDPClient`:

```python
from apache_ranger.client.ranger_client import RangerClient

ranger   = RangerClient("http://localhost:6080", ("admin", "rangerR0cks!"))
services = ranger.find_services()
print(f"{len(services.list)} services found")
```

See [Python client](python.md) for installation, authentication options and the API surface, or the
[Ranger Python Client Library](https://pypi.org/project/apache-ranger/) page on PyPI.

## Java

The `org.apache.ranger:ranger-intg` artifact provides `org.apache.ranger.RangerClient`, built on the same
`RangerRESTClient` that Ranger plugins use to talk to Ranger Admin:

```java
RangerClient        ranger   = new RangerClient("http://localhost:6080", "simple", "admin", "rangerR0cks!", null);
List<RangerService> services = ranger.findServices(Collections.emptyMap());
```

See [Java client](java.md) for details, or the
[Ranger Java Client Library](https://github.com/apache/ranger/blob/master/intg/src/main/java/README.md) README
in the source tree.

For applications that need to call a Ranger PDP server for authorization decisions, see the
[Ranger Authz Remote Client](https://github.com/apache/ranger/blob/master/authz-remote/README.md) and
[Authorization API and PDP](../../dev/authz-api.md).

## Authentication

Both libraries support the authentication mechanisms configured on Ranger Admin:

| Mechanism | Java `RangerClient` | Python |
| --- | --- | --- |
| Basic (username/password) | `authType` other than `kerberos`, e.g. `"simple"` | `auth=("user", "password")` tuple |
| Kerberos / SPNEGO | `authType="kerberos"`, `username`=principal, `password`=keytab path | `auth=HTTPKerberosAuth()` (`requests-kerberos`) |
| JWT bearer token | `RangerRESTClient.setJwtProvider(...)` with a `JwtProvider` | `headers={"Authorization": "Bearer <token>"}` |
| Trusted header (PDP) | `ranger.authz.remote.authn.header.<name>` in `authz-remote` | `headers={"X-Forwarded-User": "<user>"}` |
| TLS | SSL config file (`ssl-client.xml`) | `ranger.session.verify` / CA bundle |

The client supports Header-based, Kerberos/SPNEGO and JWT bearer-token authentication.

## Examples in the source tree

- Java: [`ranger-examples/sample-client`](https://github.com/apache/ranger/tree/master/ranger-examples/sample-client)
  (`SampleClient` for the Admin API, `RemoteAuthzClient` for the PDP).
- Python: [`ranger-examples/sample-client/src/main/python`](https://github.com/apache/ranger/tree/master/ranger-examples/sample-client/src/main/python)
  (`sample_client.py`, `user_mgmt.py`, `sample_kms_client.py`, `sample_gds_client.py`, `sample_pdp_client.py`,
  `security_zone_v2.py`).

Both sets ship in `ranger-<version>-sample-client.tar.gz` produced by the build.
