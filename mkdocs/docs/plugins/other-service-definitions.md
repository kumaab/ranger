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

# Other service definitions

Ranger ships a few service definitions that have no matching plugin module in this repository. Some are
consumed by external products, some are used by Ranger itself. This page explains what each one is for so
you are not surprised to see them in **Service Manager** or in the `service-defs` directory.

| Service type | Created at Admin startup | Purpose |
|---|---|---|
| `tag` | yes | Backs tag-based policies; one `tag` service is linked to every resource service that uses tags. |
| `gds` | yes | Backs Governed Data Sharing (datasets, projects). |
| `abfs` | no | Azure Data Lake Storage Gen2 (ABFS) paths. |
| `wasb` | no | Azure Blob Storage (WASB) paths. |

"Created at Admin startup" follows `EmbeddedServiceDefsUtil.DEFAULT_BOOTSTRAP_SERVICEDEF_LIST`, the default
of `ranger.supportedcomponents` in `ranger-admin-site.xml`. `abfs` and `wasb` are bundled but only created
when you add them to that property, or register them through the REST API described in
[Writing a custom plugin](custom-plugin.md).

The `polaris` service definition, which used to be listed here, has its own page:
[Apache Polaris](polaris.md).

## tag

`ranger-servicedef-tag.json` (id 100, `implClass` `org.apache.ranger.services.tag.RangerServiceTag`) is
the service type behind [tag-based policies](../features/policies/tag-based-policies.md).

- Single resource `tag` (exact match, lookup supported); the access types are *not* defined here — a
  tag policy shows the access types of every service definition, because the same tag policy can apply to
  Hive tables, HDFS paths, Kafka topics and so on.
- `options.ui.pages=tag-based-policies` tells the UI to show these services on the tag-policies page.
- Context enricher `TagEnricher` (`RangerTagEnricher` with `RangerAdminTagRetriever`,
  `tagRefresherPollingInterval` 60000 ms) is what a resource plugin uses to fetch tags for the resources it
  protects when the service is linked to a tag service.
- Policy conditions `accessed-after-expiry` (`ctx.isAccessedAfter('expiry_date')`) and `expression`
  (JavaScript) are evaluated against tag attributes; see
  [Policy conditions](../features/policies/policy-conditions.md).

You normally create one tag service, associate it with resource services in **Service Manager** (the
"Select Tag Service" field), and let TagSync populate tags from Atlas.

## gds

`ranger-servicedef-gds.json` (`implClass` `org.apache.ranger.services.gds.RangerServiceGds`) supports
[Governed Data Sharing](../features/gds/gds_intro.md).

- Resources `dataset-id` and `project-id` (exact match, single value).
- Access types `_CREATE`, `_READ`, `_UPDATE`, `_DELETE`, `_MANAGE`, `_ALL`; `enableDenyInPolicies=false`.
- Policy conditions `expression` (JavaScript) and `validitySchedule`.

Ranger Admin creates the service definition and a single service named `_gds` at startup
(`GdsPolicyEngine.GDS_SERVICE_NAME`). Policies in it are generated from datasets and projects; plugins
receive them through the GDS enricher and evaluate them alongside resource and tag policies. You do not
create policies in this service by hand.

## abfs and wasb

`ranger-servicedef-abfs.json` (id 103, "Azure Blob File System") and `ranger-servicedef-wasb.json`
(id 101, "WASB File System") share one resource model:

| Resource | Parent | Recursive | Description |
|---|---|---|---|
| `storageaccount` | — | no | Storage account. |
| `container` | `storageaccount` | no | Container in the account. |
| `relativepath` | `container` | yes | Path inside the container (path type). |

All three resources accept wildcards.

Access types `read` and `write`; deny and exceptions enabled. `abfs` additionally declares the `ip-range`
condition; its configs are `username`, `password`, `commonNameForCertificate`, while `wasb` has only
`username` and `commonNameForCertificate`. Neither has an `implClass`, so there is no Test Connection or
autocomplete, and Ranger ships no authorizer for either: they exist for distributions and cloud
products that enforce Ranger policies on Azure storage paths with their own plugin.

## Further reading

- [Integrations](index.md)
- [Apache Polaris](polaris.md)
- [Policy model](../arch/policy-model.md) — how tag, resource and GDS policies are combined at evaluation time
