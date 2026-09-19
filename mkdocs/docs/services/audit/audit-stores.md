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

# Audit stores

An audit store is where audit records end up and where Ranger Admin reads them from. Ranger supports two
kinds of stores: **searchable** stores (Solr, OpenSearch, Elasticsearch, Amazon CloudWatch) that back the
Audit tab in the Admin UI, and **archive** stores (HDFS and other Hadoop-compatible file systems and object
stores) for long-term retention and offline analysis. A typical production setup writes to one of each: a
searchable store with a retention of a few weeks or months, and HDFS or object storage kept for years.

For each store this page describes what the store itself needs (collection, index, directory), the plugin
configuration that writes to it, and the Ranger Admin configuration that reads from it. The full list of
plugin-side properties is in [Audit framework](index.md).

## Choosing a store

| Store | Admin UI can read | Typical use |
| --- | --- | --- |
| Solr (SolrCloud) | Yes | Searchable store; the default `ranger.audit.source.type`. |
| OpenSearch | Yes | Searchable store; default in the docker stack. |
| Elasticsearch | Yes | Searchable store. |
| Amazon CloudWatch Logs | Yes | Searchable store on AWS. |
| HDFS, ABFS, S3 and other Hadoop file systems | No | Archive, compliance, batch analytics. |

Plugins can write to several stores at once, each configured in `ranger-<component>-audit.xml` with
`xasecure.audit.destination.*` properties. Ranger Admin reads from exactly one store, selected in
`ranger-admin-site.xml`:

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.source.type` | `solr` | Enum | Store behind the Audit tab: `solr`, `opensearch`, `elasticsearch` or `cloudwatch`. |

With the [Audit Server](../audit-server/service.md), plugins write only to the ingestor and the dispatchers
write to the stores; the store requirements and the Admin configuration below stay the same.

## Solr

### Collection requirements

Ranger needs one collection (or core, for standalone Solr), named `ranger_audits` by default, created from
the configset in
[`security-admin/contrib/solr_for_audit_setup/conf`](https://github.com/apache/ranger/blob/master/security-admin/contrib/solr_for_audit_setup/conf).
The Ranger Admin distribution carries the same directory under `contrib/solr_for_audit_setup/conf`.

`managed-schema`
:   Declares the audit fields listed in [Audit schema](audit-schema.md), with `id` as the `uniqueKey`.
    String fields use the case-insensitive `key_lower_case` type; `reqData`, `reason` and `tags_str` are
    tokenized text; `evtTime` is a `tdate`. The schema also declares `_ttl_` and `_expire_at_` for
    document expiry.

`solrconfig.xml`
:   Uses the managed schema factory, hard auto-commit every 60 seconds and soft auto-commit every
    15 seconds (overridable with `solr.autoCommit.maxTime` and `solr.autoSoftCommit.maxTime`), and an
    update processor chain that expires old documents (see below).

Retention is part of the configset. The default update chain stamps every document with a time-to-live
and lets Solr delete expired documents once a day:

```xml title="solrconfig.xml"
<processor class="solr.DefaultValueUpdateProcessorFactory">
  <str name="fieldName">_ttl_</str>
  <str name="value">+90DAYS</str>
</processor>
<processor class="solr.processor.DocExpirationUpdateProcessorFactory">
  <int name="autoDeletePeriodSeconds">86400</int>
  <str name="ttlFieldName">_ttl_</str>
  <str name="expirationFieldName">_expire_at_</str>
</processor>
```

Change the `_ttl_` value before you upload the configset to keep audits for a shorter or longer period.

You can create the collection yourself with Solr's own tools (upload the configset to ZooKeeper, then create
the collection from it), or let Ranger Admin do it at start-up, as described next.

### Collection bootstrap by Ranger Admin

When `ranger.audit.source.type` is `solr`, bootstrap is enabled and `ranger.audit.solr.zookeepers` is set,
Ranger Admin connects to SolrCloud at start-up, uploads the configset if it is missing, and creates the
collection if it does not exist. It retries in the background until it succeeds or the retry limit is
reached. Bootstrap does not run for a standalone Solr addressed only through `ranger.audit.solr.urls`.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.solr.bootstrap.enabled` | `true` | Boolean | Upload the configset and create the collection at start-up. |
| `ranger.audit.solr.collection.name` | `ranger_audits` | String | Collection to create and to query. |
| `ranger.audit.solr.config.name` | `ranger_audits` | String | Name of the configset in ZooKeeper. |
| `ranger.audit.solr.configset.location` | (none) | Path | Directory holding the configset; when empty, `contrib/solr_for_audit_setup/conf` of the Admin distribution. |
| `ranger.audit.solr.no.shards` | live nodes | Integer | Number of shards; defaults to the number of live Solr nodes. |
| `ranger.audit.solr.no.replica` | `1` | Integer | Replication factor. |
| `ranger.audit.solr.max.shards.per.node` | `1` | Integer | Maximum shards per node. |
| `ranger.audit.solr.max.retry` | `30` | Integer | Bootstrap attempts before giving up. |
| `ranger.audit.solr.time.interval` | `60000` | Duration (ms) | Wait between bootstrap attempts. |

### Plugins writing to Solr

```xml title="ranger-<component>-audit.xml"
<property>
  <name>xasecure.audit.destination.solr</name>
  <value>true</value>
</property>
<property>
  <name>xasecure.audit.destination.solr.zookeepers</name>
  <value>zk1:2181,zk2:2181,zk3:2181/ranger_audits</value>
</property>
<property>
  <name>xasecure.audit.destination.solr.batch.filespool.dir</name>
  <value>/var/log/hive/audit/solr/spool</value>
</property>
```

For a standalone Solr, set `xasecure.audit.destination.solr.urls` (for example
`http://solr.example.com:6083/solr/ranger_audits`) instead of `zookeepers`.

For a Kerberized Solr, either point the JVM's `java.security.auth.login.config` at a JAAS file, or set
`xasecure.audit.destination.solr.force.use.inmemory.jaas.config=true` and supply the login module through
the `xasecure.audit.jaas.Client.*` properties. For HTTPS the plugin's `xasecure.policymgr.clientssl.*`
truststore (and keystore, for mutual TLS) settings are reused. All keys are listed in
[Audit framework](index.md#solr).

### Ranger Admin reading from Solr

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.solr.zookeepers` | (none) | String | ZooKeeper connect string (SolrCloud); takes precedence over `ranger.audit.solr.urls`. |
| `ranger.audit.solr.urls` | (none) | List | Collection URL for standalone Solr, for example `http://solr.example.com:6083/solr/ranger_audits`. |
| `ranger.audit.solr.collection.name` | `ranger_audits` | String | Collection to query, with `zookeepers`. |
| `ranger.solr.audit.user` | (none) | String | User for Solr basic authentication. |
| `ranger.solr.audit.user.password` | (none) | Password | Password for Solr basic authentication. |

```xml title="ranger-admin-site.xml"
<property>
  <name>ranger.audit.source.type</name>
  <value>solr</value>
</property>
<property>
  <name>ranger.audit.solr.zookeepers</name>
  <value>zk1:2181,zk2:2181,zk3:2181/ranger_audits</value>
</property>
```

The docker stack (`--profile audit-store-solr`) runs a `ranger-solr` container with a `ranger_audits`
configset from `dev-support/ranger-docker/scripts/solr/solr-ranger_audits`, protected by the Ranger Solr plugin.

## OpenSearch

### Index requirements

Ranger needs one index, `ranger_audits` by default, with the field mapping in
[`security-admin/contrib/opensearch_for_audit_setup/conf/ranger_opensearch_schema.json`](https://github.com/apache/ranger/blob/master/security-admin/contrib/opensearch_for_audit_setup/conf/ranger_opensearch_schema.json):
`keyword` for identifiers such as `reqUser`, `repo`, `resource` and `access`, `text` for `reqData`, `reason`
and `tags_str`, `date` for `evtTime`, and numeric types for `policy`, `result`, `repoType`, `seq_num`,
`event_count` and `event_dur_ms`.

When `ranger.audit.source.type` is `opensearch` and bootstrap is enabled, Ranger Admin creates the index with
this mapping at start-up if it does not exist. It reads the mapping from
`contrib/opensearch_for_audit_setup/conf/ranger_opensearch_schema.json` in the Admin distribution.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.opensearch.bootstrap.enabled` | `true` | Boolean | Create the index at start-up. |
| `ranger.audit.opensearch.no.shards` | `1` | Integer | Number of shards of the new index. |
| `ranger.audit.opensearch.no.replica` | `1` | Integer | Number of replicas of the new index. |
| `ranger.audit.opensearch.max.retry` | `30` | Integer | Bootstrap attempts before giving up. |
| `ranger.audit.opensearch.time.interval` | `60000` | Duration (ms) | Wait between bootstrap attempts. |

If you create the index yourself, create it with this mapping before the first audit arrives; otherwise
OpenSearch infers field types dynamically and searches from the Admin UI may not behave as expected.

### Plugins writing to OpenSearch

```xml title="ranger-<component>-audit.xml"
<property>
  <name>xasecure.audit.destination.opensearch</name>
  <value>true</value>
</property>
<property>
  <name>xasecure.audit.destination.opensearch.urls</name>
  <value>os1.example.com,os2.example.com</value>
</property>
<property>
  <name>xasecure.audit.destination.opensearch.protocol</name>
  <value>https</value>
</property>
<property>
  <name>xasecure.audit.destination.opensearch.authentication.type</name>
  <value>basic</value>
</property>
<property>
  <name>xasecure.audit.destination.opensearch.user</name>
  <value>ranger</value>
</property>
<property>
  <name>xasecure.audit.destination.opensearch.password</name>
  <value>secret</value>
</property>
<property>
  <name>xasecure.audit.destination.opensearch.batch.filespool.dir</name>
  <value>/var/log/hive/audit/opensearch/spool</value>
</property>
```

`port` defaults to `9200` and `index` to `ranger_audits`. Use `authentication.type=kerberos` with
`kerberos.principal` and `kerberos.keytab` for SPNEGO. All keys are listed in
[Audit framework](index.md#opensearch).

### Ranger Admin reading from OpenSearch

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.opensearch.urls` | (none) | List | Required. OpenSearch host names, without scheme or port. |
| `ranger.audit.opensearch.port` | `9200` | Integer | Port. |
| `ranger.audit.opensearch.protocol` | `http` | Enum | `http` or `https`. |
| `ranger.audit.opensearch.index` | `ranger_audits` | String | Index name. |
| `ranger.audit.opensearch.authentication.type` | (none) | Enum | `basic` or `kerberos`. |
| `ranger.audit.opensearch.user` | (none) | String | User for `basic`. |
| `ranger.audit.opensearch.password` | (none) | Password | Password for `basic`. |
| `ranger.audit.opensearch.kerberos.principal` | (none) | String | Principal for `kerberos`. |
| `ranger.audit.opensearch.kerberos.keytab` | (none) | Path | Keytab for `kerberos`. |

OpenSearch is the default store of the docker stack (`AUDIT_INDEX_STORE=opensearch`, profile
`audit-store-opensearch`), see [Running Ranger with Docker](../../getting-started/docker.md).

## Elasticsearch

### Index requirements

Ranger needs one index, `ranger_audits` by default, with the field mapping in
[`security-admin/contrib/elasticsearch_for_audit_setup/conf/ranger_es_schema.json`](https://github.com/apache/ranger/blob/master/security-admin/contrib/elasticsearch_for_audit_setup/conf/ranger_es_schema.json).
The mapping follows the same pattern as the OpenSearch one: `keyword` identifiers, `text` for `reqData`,
`reason` and `tags_str`, `date` for `evtTime`, numeric types for counters and ids.

When `ranger.audit.source.type` is `elasticsearch` and bootstrap is enabled, Ranger Admin creates the index
with this mapping at start-up if it does not exist. It reads the mapping from
`contrib/elasticsearch_for_audit_setup/conf/ranger_es_schema.json` in the Admin distribution.

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.elasticsearch.bootstrap.enabled` | `true` | Boolean | Create the index at start-up. |
| `ranger.audit.elasticsearch.no.shards` | `1` | Integer | Number of shards of the new index. |
| `ranger.audit.elasticsearch.no.replica` | `1` | Integer | Number of replicas of the new index. |
| `ranger.audit.elasticsearch.max.retry` | `30` | Integer | Bootstrap attempts before giving up. |
| `ranger.audit.elasticsearch.time.interval` | `60000` | Duration (ms) | Wait between bootstrap attempts. |

### Plugins writing to Elasticsearch

```xml title="ranger-<component>-audit.xml"
<property>
  <name>xasecure.audit.destination.elasticsearch</name>
  <value>true</value>
</property>
<property>
  <name>xasecure.audit.destination.elasticsearch.urls</name>
  <value>es1.example.com,es2.example.com</value>
</property>
<property>
  <name>xasecure.audit.destination.elasticsearch.protocol</name>
  <value>https</value>
</property>
<property>
  <name>xasecure.audit.destination.elasticsearch.user</name>
  <value>ranger</value>
</property>
<property>
  <name>xasecure.audit.destination.elasticsearch.password</name>
  <value>secret</value>
</property>
```

`port` defaults to `9200` and `index` to `ranger_audits`. If `password` is the path of an existing keytab
file, the client authenticates with SPNEGO using `user` as the principal. All keys are listed in
[Audit framework](index.md#elasticsearch).

### Ranger Admin reading from Elasticsearch

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.elasticsearch.urls` | `127.0.0.1` | List | Elasticsearch host names, without scheme or port. |
| `ranger.audit.elasticsearch.port` | `9200` | Integer | Port. |
| `ranger.audit.elasticsearch.protocol` | `http` | Enum | `http` or `https`. |
| `ranger.audit.elasticsearch.index` | `ranger_audits` | String | Index name. |
| `ranger.audit.elasticsearch.user` | (none) | String | Basic-auth user. |
| `ranger.audit.elasticsearch.password` | (none) | Password | Basic-auth password. |

## Amazon CloudWatch Logs

The CloudWatch destination needs a log group (`ranger_audits` by default) and AWS credentials from the
default provider chain (instance profile, environment, or profile files) on both the plugin hosts and the
Ranger Admin host.

Plugins set `xasecure.audit.destination.amazon_cloudwatch=true` together with `.region`, `.log_group`,
`.log_stream_prefix` and `.batch.filespool.dir`; see [Audit framework](index.md#amazon-cloudwatch).
Ranger Admin reads with `ranger.audit.source.type=cloudwatch` and:

| Key | Default | Type | Description |
| --- | --- | --- | --- |
| `ranger.audit.amazon_cloudwatch.region` | `us-east-2` | String | AWS region. |
| `ranger.audit.amazon_cloudwatch.log_group` | `ranger_audits` | String | Log group to query. |
| `ranger.audit.amazon_cloudwatch.log_stream_prefix` | (none) | String | Prefix of the log streams written by the plugins. |

## HDFS and object storage

The HDFS destination writes through the Hadoop `FileSystem` API, so any scheme with a Hadoop client on the
plugin's classpath works: `hdfs://`, `abfs://` / `wasb://` (Azure), `s3a://` (S3), `gs://`, and so on.
The store needs a base directory in which the plugin's service user (for example `hive`) can create
sub-directories; the writer uses the service's Kerberos login. Files are laid out as
`<dir>/<subdir>/<filename>`, by default:

```text
<dir>/<app-type>/<yyyyMMdd>/<app-type>_ranger_audit_<hostname>.log
```

Each file holds one JSON record per line, or ORC with `batch.filequeue.filetype=orc`. Files roll daily by
default (`file.rollover.sec=86400`).

```xml title="ranger-<component>-audit.xml"
<property>
  <name>xasecure.audit.destination.hdfs</name>
  <value>true</value>
</property>
<property>
  <name>xasecure.audit.destination.hdfs.dir</name>
  <value>hdfs://nn.example.com:8020/ranger/audit</value>
</property>
<property>
  <name>xasecure.audit.destination.hdfs.batch.filespool.dir</name>
  <value>/var/log/hive/audit/hdfs/spool</value>
</property>
```

The Admin UI cannot search files; use Hive, Spark, Trino or your log analytics platform on the JSON or ORC
files. All keys are listed in [Audit framework](index.md#hdfs-and-object-stores).

### Azure Blob Storage

The same destination stores audits in Azure Blob Storage through Hadoop's `wasb` support. In addition to
`dir`, pass the account settings that would otherwise live in `core-site.xml` through the `config.` prefix:

```xml title="ranger-<component>-audit.xml"
<property>
  <name>xasecure.audit.destination.hdfs.dir</name>
  <value>wasb://ranger-audit@myaccount.blob.core.windows.net/ranger/audit</value>
</property>
<property>
  <name>xasecure.audit.destination.hdfs.config.fs.azure.account.key.myaccount.blob.core.windows.net</name>
  <value>ENCRYPTED-KEY</value>
</property>
<property>
  <name>xasecure.audit.destination.hdfs.config.fs.azure.account.keyprovider.myaccount.blob.core.windows.net</name>
  <value>org.apache.hadoop.fs.azure.ShellDecryptionKeyProvider</value>
</property>
<property>
  <name>xasecure.audit.destination.hdfs.config.fs.azure.shellkeyprovider.script</name>
  <value>/path/to/decrypt.sh</value>
</property>
```

The same `config.` pass-through works for `fs.s3a.*` and other file-system properties.

## Retention and sizing

- Solr: the `_ttl_` default in the configset's `solrconfig.xml` (`+90DAYS`) sets the retention; expired
  documents are removed by Solr itself.
- OpenSearch / Elasticsearch: use index lifecycle management or delete-by-query on `evtTime`; Ranger does
  not delete documents.
- HDFS: prune date directories with your own retention job.
- Reduce volume at the source with [audit filters](audit-filters.md) and the summary queue
  (`xasecure.audit.provider.summary.enabled`).

## Further reading

- [Audit framework](index.md) — all destination and queue properties.
- [Audit schema](audit-schema.md) — field names used in Solr, OpenSearch and Elasticsearch.
- [Audit Server](../audit-server/service.md) — write to stores through Kafka.
- [Ranger Admin](../admin/service.md) — `ranger-admin-site.xml` and running the service.
- Source: [`security-admin/contrib/solr_for_audit_setup/conf`](https://github.com/apache/ranger/blob/master/security-admin/contrib/solr_for_audit_setup/conf),
  [`security-admin/contrib/elasticsearch_for_audit_setup/conf`](https://github.com/apache/ranger/blob/master/security-admin/contrib/elasticsearch_for_audit_setup/conf),
  [`security-admin/contrib/opensearch_for_audit_setup/conf`](https://github.com/apache/ranger/blob/master/security-admin/contrib/opensearch_for_audit_setup/conf).
- cwiki: [Storing audit messages in Azure Blob Storage](https://cwiki.apache.org/confluence/display/RANGER/Storing+Audit+messages+in+Azure+Blob+Storage).
