<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to You under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Ranger Authz Remote Client

The `authz-remote` module implements the `ranger-authz-api` authorizer interface and forwards authorization checks to Ranger PDP Service over HTTP(S).

For an end-to-end example (load configuration from a properties file, build a request, and call the authorizer), see:

`ranger-examples/sample-client/src/main/java/org/apache/ranger/examples/pdpclient/RemoteAuthzClient.java`

Add the dependency (for standalone applications):

```xml
<dependency>
  <groupId>org.apache.ranger</groupId>
  <artifactId>authz-remote</artifactId>
  <version>${ranger.version}</version>
</dependency>
```

## PDP endpoint

Configure the PDP base URL with **`ranger.authz.remote.pdp.url`**.  
Other timeouts, TLS, and optional HTTP headers are documented under the same prefix in **`authz-remote/src/conf/ranger-authz-remote.properties`** and **`RangerRemoteAuthzConfig`**.

## Authentication modes

Client authentication is controlled by **`ranger.authz.remote.authn.type`**. Three modes are supported:

- **`none`** — No client-added authentication (`Authorization`/SPNEGO). Omit the property or set it to `none` when the PDP does not expect authenticated clients for these calls.

- **`jwt`** 
  - Set **`ranger.authz.remote.authn.jwt.source`** to **`env`** or **`file`**.  
  - For **`env`**, set **`ranger.authz.remote.authn.jwt.env`** as the name of the environment variable containing JWT.  
  - For **`file`**, set **`ranger.authz.remote.authn.jwt.file`** to the token file path.

- **`kerberos`** — SPNEGO from a keytab. Set **`ranger.authz.remote.authn.kerberos.principal`** and **`ranger.authz.remote.authn.kerberos.keytab`**. Optionally set **`ranger.authz.remote.authn.kerberos.debug`** to `true` for JDK Kerberos diagnostics.
