---
title: "Java Client"
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

# Java client

`org.apache.ranger.RangerClient` is a Java wrapper around the Ranger Admin
[public REST API](../../dev/rest-api.md). It lets an application create and query service definitions,
services, policies, roles, security zones and tags using the same model classes (`RangerPolicy`,
`RangerService`, ...) that Ranger itself uses, so anything you can express in the Admin UI you can automate
from Java. Under the hood it reuses `RangerRESTClient` from `agents-common`, the HTTP client the plugins use
to talk to Ranger Admin, which gives you Basic, Kerberos, JWT and TLS support out of the box.

The client lives in the `intg` module and ships as the `ranger-intg` artifact. For authorization decisions
(rather than administration) use the [authorization API](../../dev/authz-api.md) instead.

## Installation

```xml
<dependency>
    <groupId>org.apache.ranger</groupId>
    <artifactId>ranger-intg</artifactId>
    <version>3.0.0-SNAPSHOT</version>   <!-- or a released version, e.g. 2.9.0 -->
</dependency>
```

`ranger-intg` depends on `ranger-plugins-common` (the `agents-common` module), which pulls in the Hadoop
`Configuration` API and Jersey. Ranger master is compiled for Java 17; use the `ranger-intg` version that
matches your Ranger Admin release. Snapshot artifacts are available after `mvn install -DskipTests` in a
source checkout (see [Building from source](../../dev/build.md)).

```java
import org.apache.ranger.RangerClient;
import org.apache.ranger.RangerServiceException;
import org.apache.ranger.plugin.model.*;
```

## Creating a client

```java
// Basic authentication
RangerClient ranger = new RangerClient("http://localhost:6080", "simple", "admin", "rangerR0cks!", null);

// Kerberos: username is the principal, password is the keytab path
RangerClient ranger = new RangerClient("https://ranger.example.com:6182", "kerberos",
                                       "svc-automation@EXAMPLE.COM", "/etc/security/keytabs/automation.keytab",
                                       "/etc/ranger/ssl-client.xml");
```

`RangerClient` has three constructors:

`RangerClient(String url, String authType, String username, String password, String sslConfigFile)`
:   `authType` `"kerberos"` logs in from the keytab and wraps every call in a privileged action; any other
    value (`"simple"`, `"basic"`) uses HTTP Basic. `sslConfigFile` may be `null` for HTTP.

`RangerClient(String url, String authType, String username, String password, String appId, String serviceType)`
:   Same, but reads the SSL config file name from the plugin property
    `ranger.plugin.<serviceType>.policy.rest.ssl.config.file`.

`RangerClient(RangerRESTClient restClient)`
:   Bring your own `RangerRESTClient`, for example to use a JWT provider (below).

### TLS

For HTTPS endpoints pass an SSL configuration file in the same format plugins use
(`ranger-examples/sample-client/conf/ssl-client.xml`):

```xml title="ssl-client.xml"
<configuration>
  <property><name>xasecure.policymgr.clientssl.truststore</name><value>/etc/ranger/truststore.p12</value></property>
  <property><name>xasecure.policymgr.clientssl.truststore.type</name><value>PKCS12</value></property>
  <property><name>xasecure.policymgr.clientssl.truststore.credential.file</name><value>jceks://file/etc/ranger/creds.jceks</value></property>
</configuration>
```

Keystore properties (`xasecure.policymgr.clientssl.keystore`, `.keystore.type`, `.keystore.credential.file`)
are added the same way when Admin requires client certificates.

### JWT bearer tokens

`RangerRESTClient` sends `Authorization: Bearer <token>` when a `JwtProvider` is set. `DefaultJwtProvider`
reads the token from an environment variable, a file (re-read when it changes) or a Hadoop credential
provider, selected by `<prefix>.jwt.source` = `env` | `file` | `cred`:

```java
import org.apache.hadoop.conf.Configuration;
import org.apache.ranger.plugin.authn.DefaultJwtProvider;
import org.apache.ranger.plugin.util.RangerRESTClient;

Configuration conf = new Configuration(false);
conf.set("ranger.client.jwt.source", "env");
conf.set("ranger.client.jwt.env",    "RANGER_JWT");         // or: jwt.source=file + ranger.client.jwt.file=/path/token
                                                            // or: jwt.source=cred + jwt.cred.file + jwt.cred.alias

RangerRESTClient restClient = new RangerRESTClient("https://ranger.example.com:6182", "/etc/ranger/ssl-client.xml", conf);
restClient.setJwtProvider(new DefaultJwtProvider("ranger.client", conf));

RangerClient ranger = new RangerClient(restClient);
```

Implement `org.apache.ranger.plugin.authn.JwtProvider` (`String getJwt()`) yourself to fetch tokens from an
identity provider.

## Example: service and policy lifecycle

```java
public class RangerAutomation {
    public static void main(String[] args) throws RangerServiceException {
        RangerClient ranger = new RangerClient("http://localhost:6080", "simple", "admin", "rangerR0cks!", null);

        // 1. create a Hive service
        RangerService service = new RangerService();
        service.setType("hive");
        service.setName("dev_hive");
        Map<String, String> configs = new HashMap<>();
        configs.put("username", "hive");
        configs.put("password", "hive");
        configs.put("jdbc.driverClassName", "org.apache.hive.jdbc.HiveDriver");
        configs.put("jdbc.url", "jdbc:hive2://ranger-hadoop:10000");
        service.setConfigs(configs);
        RangerService created = ranger.createService(service);
        System.out.println("service id: " + created.getId());

        // 2. create a policy: analysts may select sales.orders
        RangerPolicy policy = new RangerPolicy();
        policy.setService("dev_hive");
        policy.setName("sales-readers");
        Map<String, RangerPolicy.RangerPolicyResource> resources = new HashMap<>();
        resources.put("database", new RangerPolicy.RangerPolicyResource("sales"));
        resources.put("table",    new RangerPolicy.RangerPolicyResource("orders"));
        resources.put("column",   new RangerPolicy.RangerPolicyResource("*"));
        policy.setResources(resources);

        RangerPolicy.RangerPolicyItem item = new RangerPolicy.RangerPolicyItem();
        item.setGroups(Collections.singletonList("analysts"));
        item.setAccesses(Collections.singletonList(new RangerPolicy.RangerPolicyItemAccess("select")));
        policy.setPolicyItems(Collections.singletonList(item));

        RangerPolicy createdPolicy = ranger.createPolicy(policy);
        System.out.println("policy id: " + createdPolicy.getId());

        // 3. look it up, then clean up
        RangerPolicy found = ranger.getPolicy("dev_hive", "sales-readers");
        ranger.deletePolicy("dev_hive", "sales-readers");
        ranger.deleteService("dev_hive");
    }
}
```

All methods throw `RangerServiceException`, which carries the HTTP status (`getStatus()`) and the server's
error message. Any response whose status differs from the one the API expects, including the error Ranger
Admin returns for an object that does not exist, is thrown as `RangerServiceException`.

## API reference

Method names mirror the REST paths under `service/public/v2/api/`. Filters are `Map<String, String>` of the
query parameters accepted by the search endpoints (`serviceName`, `policyName`, `pageSize`, ...).

### Service definitions

```java
RangerServiceDef       createServiceDef(RangerServiceDef serviceDef)
RangerServiceDef       updateServiceDef(long serviceDefId, RangerServiceDef serviceDef)
RangerServiceDef       updateServiceDef(String serviceDefName, RangerServiceDef serviceDef)
void                   deleteServiceDef(long serviceDefId)
void                   deleteServiceDef(String serviceDefName)
RangerServiceDef       getServiceDef(long serviceDefId)
RangerServiceDef       getServiceDef(String serviceDefName)
List<RangerServiceDef> findServiceDefs(Map<String, String> filter)
```

### Services

```java
RangerService       createService(RangerService service)
RangerService       updateService(long serviceId, RangerService service)
RangerService       updateService(String serviceName, RangerService service)
void                deleteService(long serviceId)
void                deleteService(String serviceName)
RangerService       getService(long serviceId)
RangerService       getService(String serviceName)
List<RangerService> findServices(Map<String, String> filter)
```

### Policies

```java
RangerPolicy       createPolicy(RangerPolicy policy)
RangerPolicy       applyPolicy(RangerPolicy policy)                      // create or merge
RangerPolicy       updatePolicy(long policyId, RangerPolicy policy)
RangerPolicy       updatePolicy(String serviceName, String policyName, RangerPolicy policy)
RangerPolicy       updatePolicyByNameAndZone(String serviceName, String policyName, String zoneName, RangerPolicy policy)
void               deletePolicy(long policyId)
void               deletePolicy(String serviceName, String policyName)
void               deletePolicyByNameAndZone(String serviceName, String policyName, String zoneName)
RangerPolicy       getPolicy(long policyId)
RangerPolicy       getPolicy(String serviceName, String policyName)
RangerPolicy       getPolicyByNameAndZone(String serviceName, String policyName, String zoneName)
List<RangerPolicy> getPoliciesInService(String serviceName)
List<RangerPolicy> findPolicies(Map<String, String> filter)
```

### Security zones

```java
RangerSecurityZone                 createSecurityZone(RangerSecurityZone securityZone)
RangerSecurityZone                 updateSecurityZone(long zoneId, RangerSecurityZone securityZone)
void                               deleteSecurityZone(long zoneId)
void                               deleteSecurityZone(String zoneName)
RangerSecurityZone                 getSecurityZone(long zoneId)
RangerSecurityZone                 getSecurityZone(String zoneName)
List<RangerSecurityZoneHeaderInfo> getSecurityZoneHeaders(Map<String, String> filter)
List<RangerServiceHeaderInfo>      getSecurityZoneServiceHeaders(Map<String, String> filter)
Set<String>                        getSecurityZoneNamesForResource(String serviceName, Map<String, String> resource)
List<RangerSecurityZone>           findSecurityZones(Map<String, String> filter)
```

(The zones-v2 endpoints are not wrapped by the Java client; call them through the REST API or the Python client.)

### Roles

```java
RangerRole       createRole(String serviceName, RangerRole role)
RangerRole       updateRole(long roleId, RangerRole role)
void             deleteRole(long roleId)
void             deleteRole(String roleName, String execUser, String serviceName)
RangerRole       getRole(long roleId)
RangerRole       getRole(String roleName, String execUser, String serviceName)
List<String>     getAllRoleNames(String execUser, String serviceName)
List<String>     getUserRoles(String user)
List<RangerRole> findRoles(Map<String, String> filter)
RESTResponse     grantRole(String serviceName, GrantRevokeRoleRequest request)
RESTResponse     revokeRole(String serviceName, GrantRevokeRoleRequest request)
```

### Tags and administration

```java
void                    importServiceTags(String serviceName, RangerServiceTags svcTags)
RangerServiceTags       getServiceTags(String serviceName)
List<RangerPluginInfo>  getPluginsInfo()
void                    deletePolicyDeltas(int days, boolean reloadServicePoliciesCache)
List<RangerPurgeResult> purgeRecords(String recordType, int retentionDays)
String                  setLogLevel(String loggerName, String logLevel)
```

`importServiceTags` takes a `RangerServiceTags` object (`op` = `set`, `delete` or `replace`, plus
`tagDefinitions`, `tags`, `serviceResources` and `resourceToTagIds`) — the same payload TagSync sends. Users
and groups, GDS objects and KMS keys have no Java wrapper; use the [REST API](../../dev/rest-api.md) or the
[Python client](python.md).

## Sample client

`ranger-examples/sample-client` contains `SampleClient`, which exercises most of the API (service def,
service, policy, tags, roles) and is packaged as `ranger-<version>-sample-client.tar.gz`:

```bash
tar xzf ranger-<version>-sample-client.tar.gz && cd ranger-<version>-sample-client
./scripts/run-sample-client.sh -n http://localhost:6080
# prompts: Kerberos login (y/n), user name / password or principal / keytab; SSL config file for https URLs
```

The script runs `org.apache.ranger.examples.sampleclient.SampleClient -h <url> -k <basic|kerberos> -u <user> -p <password|keytab> [-c <ssl-config>]`.
The same tarball contains `RemoteAuthzClient` for the PDP; see [Authorization API and PDP](../../dev/authz-api.md).

## Further reading

- [Client libraries overview](intro.md), [Python client](python.md)
- [REST API](../../dev/rest-api.md)
- Source: [`intg/src/main/java/org/apache/ranger/RangerClient.java`](https://github.com/apache/ranger/blob/master/intg/src/main/java/org/apache/ranger/RangerClient.java),
  [`intg/src/main/java/README.md`](https://github.com/apache/ranger/blob/master/intg/src/main/java/README.md),
  [`SampleClient.java`](https://github.com/apache/ranger/blob/master/ranger-examples/sample-client/src/main/java/org/apache/ranger/examples/sampleclient/SampleClient.java)
