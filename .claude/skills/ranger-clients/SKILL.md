---
name: ranger-clients
description: Ranger client libraries in the intg module - Java RangerClient (API constants over /service/public/v2/api, auth types, RangerServiceException, JsonUtilsV2) and the apache-ranger Python package (client/ and model/ layout, RangerClient/RangerGdsClient/RangerKMSClient/RangerPDPClient/RangerUserMgmtClient, type_coerce, unittest wired into mvn test), plus how to add an API to both. Use when changing anything under intg/ or ranger-examples/sample-client/.
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

# Client libraries (`intg`)

Artifact `ranger-intg`, two independent clients: Java and the `apache-ranger` Python package. Python style is specified in `.cursor/rules/ranger-python.mdc`
(layout, Python 3.13+, `type_coerce`, vertical `=` alignment, unittest shape); read that rather than a restatement here. Java style: `ranger-conventions`.

## Java (`intg/src/main/java`)

Exactly two classes: `org.apache.ranger.RangerClient` and `org.apache.ranger.RangerServiceException`. There is no Java KMS or PDP client
(`RemoteAuthzClient` in `ranger-examples/sample-client` uses `RangerAuthorizerFactory` from `authz-api`).

Constructors: `(hostName, authType, username, password, configFile)`, `(hostname, authType, username, password, appId, serviceType)` (SSL config from
`ranger.plugin.<serviceType>.policy.rest.ssl.config.file`), `(RangerRESTClient)`. Auth: `AUTH_KERBEROS = "kerberos"` -> `MiscUtil.loginWithKeyTab`; any other string falls
through to basic auth.

Endpoints are `public static final API` constants over URI constants rooted at `URI_BASE = "/service/public/v2/api"` (`URI_SERVICEDEF`, `URI_SERVICE`, `URI_POLICY`,
`URI_ROLE`, `URI_ZONE`), with `%s`/`%d` placeholders filled by `API.applyUrlFormat(...)`:

```java
public static final API GET_SERVICEDEF_BY_NAME = new API(URI_SERVICEDEF_BY_NAME, HttpMethod.GET,    Response.Status.OK);
public static final API DELETE_SERVICE_BY_ID   = new API(URI_SERVICE_BY_ID,      HttpMethod.DELETE, Response.Status.NO_CONTENT);
```

`public static class API { path, method, expectedStatus, consumes, produces }`. Call chain `callAPI` -> `responseHandler` -> `invokeREST` (`restClient.get/post/put/delete`),
wrapped in `MiscUtil.executePrivilegedAction` when secure; bodies via `JsonUtilsV2`; list responses use `TYPE_LIST_*` `TypeReference` constants. Unexpected status throws
`RangerServiceException(API, Response)`; 503 is logged and returned. Test: `intg/src/test/java/org/apache/ranger/TestRangerClient.java` (mocks `RangerRESTClient`).

## Python (`intg/src/main/python`, package `apache_ranger`)

`setup.py` only: `name="apache-ranger"`, `version="0.0.13"`, `python_requires='>=3.13'`, requires `requests`, `strenum`.

- `client/`: `RangerClient` (+ `RangerClientHttp`, `RangerClientPrivate`, `HadoopSimpleAuth`), `RangerUserMgmtClient`, `RangerGdsClient`, `RangerKMSClient`, `RangerPDPClient`
  (capitalised; unrelated to the package-private Java `RangerPdpClient` in `authz-remote`). `__init__.py` re-exports via `__all__`.
- `model/`: one module per area (`ranger_policy`, `ranger_service`, `ranger_service_def`, `ranger_gds`, `ranger_kms`, `ranger_authz`, `ranger_role`, `ranger_security_zone`,
  `ranger_tag`, `ranger_user_mgmt`, ...) on `ranger_base.RangerBase`.
- `utils.py`: `API`, `HttpMethod`, `HTTPStatus`, `non_null`, `type_coerce*` helpers. `exceptions.py`: `RangerServiceException`.
- Auth is a `requests` auth object: `HadoopSimpleAuth` (`user.name=`), or `requests_kerberos.HTTPKerberosAuth()` (optional dependency).

Method pattern: `URI_<X>` off the client's `URI_BASE`, an `API(URI_<X>, HttpMethod.POST, HTTPStatus.OK)` class constant (declared after the methods by convention),
then `type_coerce(request)` -> `self.client_http.call_api(...)` -> `type_coerce(resp, ResultClass)`; every model gets `type_coerce_attrs()`.

## Adding an API to both clients

Java: `URI_*`, `public static final API`, method, `TYPE_LIST_*` if a collection, `TestRangerClient` case. Python: model classes, `URI_*`/`API`, snake_case method,
`client/__init__.py` export, `test_ranger_client.py` case. Keep paths identical to each other and to the Admin `*REST` class (`security-admin` `references/rest-inventory.md`).

## Tests and packaging

```bash
mvn -pl intg test -Dtest=TestRangerClient
cd intg && PYTHONPATH=src/main/python python3 -B src/test/python/test_ranger_client.py
```

The Python suite (`TestRangerClient`, `TestGDSClient`, `TestPDPClient`; mocks `Session` for Admin, `client_http.call_api` for GDS/PDP) is also wired into `intg/pom.xml` as
an `exec-maven-plugin` execution at `test`, so `mvn verify` runs it unless `-DskipTests`. The `pypi-dist` execution is hard-coded `<skip>true</skip>`; PyPI releases are manual.
Examples: `ranger-examples/sample-client/src/main/python/` (`sample_client.py`, `sample_gds_client.py`, `sample_kms_client.py`, `sample_pdp_client.py`, `user_mgmt.py`, `security_zone_v2.py`).
