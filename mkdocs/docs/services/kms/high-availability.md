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

# Ranger KMS high availability

If the KMS is down, no client can open a file in an encryption zone and the NameNode cannot create new
ones, so a production KMS should not be a single process. Ranger KMS instances are stateless apart from
the database: run two or more of them against the same database with the same master key, and put a load
balancer (or a client-side host list) in front. This page shows the KMS settings that must agree between
instances, an Apache HTTP Server load balancer configuration with and without TLS, and the client-side
changes.

## Requirements

Every instance must:

1. Use the **same database** (`ranger.ks.jpa.jdbc.url`, `.user` and password in `dbks-site.xml`). Keys are
   stored in `ranger_keystore`; an instance loads keys it has not seen yet from the database on demand and
   caches them in memory, so there is no local key state to replicate.
2. Use the **same master key** - the same master key password (`ranger.db.encrypt.key.password`, or the
   alias named by `ranger.ks.masterkey.credential.alias`) and the same `ranger.kms.service.masterkey.*`
   parameters for the database provider, or the same HSM partition / cloud key for the other providers.
   The master key is created by whichever instance starts first; the others load it.
3. Share the **authentication cookie secret**. The Hadoop auth filter signs its cookie with a secret that is
   random per process by default, so a request that lands on a different instance would be rejected. Set in
   `kms-site.xml` on all instances:

    ```xml
    <property>
      <name>hadoop.kms.authentication.signer.secret.provider</name>
      <value>zookeeper</value>
    </property>
    <property>
      <name>hadoop.kms.authentication.signer.secret.provider.zookeeper.path</name>
      <value>/hadoop-kms/hadoop-auth-signature-secret</value>
    </property>
    <property>
      <name>hadoop.kms.authentication.signer.secret.provider.zookeeper.connection.string</name>
      <value>zk1.example.com:2181,zk2.example.com:2181,zk3.example.com:2181</value>
    </property>
    <property>
      <name>hadoop.kms.authentication.signer.secret.provider.zookeeper.auth.type</name>
      <value>kerberos</value>   <!-- or none -->
    </property>
    <property>
      <name>hadoop.kms.authentication.signer.secret.provider.zookeeper.kerberos.keytab</name>
      <value>/etc/security/keytabs/rangerkms.keytab</value>
    </property>
    <property>
      <name>hadoop.kms.authentication.signer.secret.provider.zookeeper.kerberos.principal</name>
      <value>rangerkms/kms1.example.com@EXAMPLE.COM</value>
    </property>
    ```

    Sticky sessions on the load balancer (shown below) reduce, but do not remove, the need for this.

4. Register with the **same Ranger KMS service** (`ranger.plugin.kms.service.name` in
   `ranger-kms-security.xml`) so that policies are identical, and
   use the same `hadoop.kms.blacklist.DECRYPT_EEK` and proxy-user settings.
5. With Kerberos, have a keytab that contains the `HTTP/<load-balancer-host>` principal clients will
   negotiate against (they address the balancer by name, so the SPNEGO service ticket is issued for that
   host), and set `hadoop.kms.authentication.kerberos.principal` accordingly on every instance.

## Client-side host list

Hadoop's KMS client (`LoadBalancingKMSClientProvider`) accepts several hosts in one URI and fails over
between them, which avoids a separate load balancer:

```xml title="core-site.xml"
<property>
  <name>hadoop.security.key.provider.path</name>
  <value>kms://http@kms1.example.com;kms2.example.com:9292/kms</value>
</property>
```

Use this when all clients are Hadoop components. A load balancer is still useful for non-Hadoop clients,
for the Ranger Admin Key Manager UI (its `provider` setting holds one URL), and for a stable name.

## Load balancer with Apache HTTP Server

The example uses `mod_proxy_balancer` with sticky sessions. Install `httpd` with `mod_proxy`,
`mod_proxy_http`, `mod_proxy_balancer`, `mod_slotmem_shm` and `mod_lbmethod_byrequests` enabled (they are
part of the standard build; enable the `LoadModule` lines in `httpd.conf`), then include a file such as:

```apache title="conf/ranger-kms-cluster.conf"
Listen 9292
<VirtualHost *:9292>
    ProxyRequests off
    ProxyPreserveHost on
    Header add Set-Cookie "ROUTEID=.%{BALANCER_WORKER_ROUTE}e; path=/" env=BALANCER_ROUTE_CHANGED

    <Proxy balancer://rangerkmscluster>
        BalancerMember http://kms1.example.com:9292 loadfactor=1 route=1
        BalancerMember http://kms2.example.com:9292 loadfactor=1 route=2
        ProxySet lbmethod=byrequests scolonpathdelim=On stickysession=ROUTEID maxattempts=1 failonstatus=500,501,502,503 nofailover=Off
    </Proxy>

    <Location /balancer-manager>
        SetHandler balancer-manager
        Require ip 10.0.0.0/8
    </Location>
    ProxyPass /balancer-manager !
    ProxyPass / balancer://rangerkmscluster/
    ProxyPassReverse / balancer://rangerkmscluster/
</VirtualHost>
```

Add `Include conf/ranger-kms-cluster.conf` to `httpd.conf`, restart `httpd`, and check
`curl http://lb.example.com:9292/kms/v1/keys/names?user.name=keyadmin` returns from both members (the
balancer-manager page shows the routes).

### TLS on the load balancer

Build or install `httpd` with `mod_ssl`, create a key and certificate for the balancer host, and terminate
TLS at the balancer:

```bash
openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr
openssl x509 -req -days 365 -in server.csr -signkey server.key -out server.crt   # or use your CA
cp server.crt server.key /usr/local/apache2/conf/
```

```apache title="conf/ranger-kms-lb-ssl.conf"
Listen 9393
<VirtualHost *:9393>
    SSLEngine On
    SSLProxyEngine On
    SSLCertificateFile    /usr/local/apache2/conf/server.crt
    SSLCertificateKeyFile /usr/local/apache2/conf/server.key
    SSLVerifyClient optional
    SSLOptions +ExportCertData
    ProxyRequests off
    Header add Set-Cookie "ROUTEID=.%{BALANCER_WORKER_ROUTE}e; path=/" env=BALANCER_ROUTE_CHANGED

    <Proxy balancer://rangerkmscluster>
        BalancerMember http://kms1.example.com:9292 loadfactor=1 route=1
        BalancerMember http://kms2.example.com:9292 loadfactor=1 route=2
        ProxySet lbmethod=byrequests scolonpathdelim=On stickysession=ROUTEID maxattempts=1 failonstatus=500,501,502,503 nofailover=Off
    </Proxy>

    ProxyPass /balancer-manager !
    ProxyPass / balancer://rangerkmscluster/
    ProxyPassReverse / balancer://rangerkmscluster/
</VirtualHost>
```

If the KMS instances themselves run HTTPS (`ranger.service.https.attrib.ssl.enabled=true`, port `9393`), use `https://`
members and either import their certificates into the balancer's trust store or, for a private network,
`SSLProxyVerify none`. Export the balancer certificate (`server.crt`) and import it into the truststore of
every client:

```bash
keytool -import -file server.crt -alias kms-lb -keystore /etc/security/clientKeys/truststore.jks
```

The Ranger KMS plugin inside each KMS talks to Ranger Admin, not to the balancer, so it needs no change;
only if Ranger Admin itself sits behind a TLS balancer must the truststore named by
`xasecure.policymgr.clientssl.truststore` (in the file that `ranger.plugin.kms.policy.rest.ssl.config.file`
points to) trust that certificate.

## Client and Ranger Admin changes

After the balancer is up:

1. `core-site.xml` on all Hadoop nodes: `hadoop.security.key.provider.path=kms://http@lb.example.com:9292/kms`
   (or `kms://https@lb.example.com:9393/kms`); older Hadoop also `dfs.encryption.key.provider.uri` in
   `hdfs-site.xml`. Restart the NameNode(s) and any long-running clients.
2. Ranger Admin: edit the KMS service and set `provider` to the balancer URL so **Test Connection** and the
   Key Manager UI go through it.
3. Restart the KMS instances; then verify in **Audit > Plugins** that each KMS host reports HTTP `200` for
   policy downloads, and run `hadoop key list` from a client.

## HSM and cloud providers

With Luna HSM, all instances must point at the same partition or, better, an HSM HA group (see
[HSM and key stores](hsm-and-key-stores.md#luna-hsm)). With KeySecure, Azure Key Vault, AWS KMS, Google
Cloud KMS or Tencent KMS, all instances use the same master key name/id. Do not mix providers between
instances of one cluster.

## Further reading

- [Ranger KMS overview](service.md)
- [Ranger Admin high availability](../admin/high-availability.md) for the same pattern on the admin side
- cwiki: [Configuring Ranger KMS in HA](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=113709676)
