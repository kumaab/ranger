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

# Admin UI guide

The Ranger Admin web UI is where most people meet Ranger: it is used to register the services you want
to protect, write policies, look at audit events and manage users. This page is a tour of the UI as it
exists on the master branch (the React application under `security-admin/src/main/webapp/react-webapp`).
It describes what each screen is for and points to the feature pages that explain the underlying
concepts.

Open `http://<admin-host>:6080/` (or the HTTPS port 6182) and log in. What you see depends on your role:
Admin users get the full navigation, KeyAdmin users get the Key Manager, Auditor and KMS Auditor users see
everything read-only, and plain users see only the modules granted to them in the permissions module.

## Layout

The left sidebar groups the application into sections. Its last entry is the logged-in user, which opens
a menu with **Profile**, **API Documentation** and **Log Out**. The bar at the top of the policy screens
shows the *Last Response Time* and the **Manage Service** menu.

| Sidebar section | Screens | Route |
| --- | --- | --- |
| Resource Policies | Service Manager for resource-based services, per-service policy list | `/policymanager/resource` |
| Tag Policies | Service Manager for tag services and tag policies | `/policymanager/tag` |
| Governed Data Sharing | My Datasets, My Datashares, My Requests, Datasets, Datashares | `/gds/...` |
| Audits | Access, Admin, Login Sessions, Plugins, Plugin Status, User Sync, Metrics | `/reports/audit/<tab>` |
| Security Zone | Zone list and editor | `/zones/zone/list` |
| Settings | Users/Groups/Roles, Permissions | `/users/usertab`, `/permissions/models` |
| Reports | User access report | `/reports/userAccess` |
| Key Manager | KMS keys (users with the Key Manager module, that is KeyAdmin and KMS Auditor) | `/kms/keys/...` |

## Service Manager

**Resource Policies** opens the Service Manager: one card per *service type* (Trino, Ozone, Kafka, Hive,
HDFS, …) listing the *services* (sometimes called repositories) defined for it. Only the service types
whose service definition is registered are shown; `ranger.supportedcomponents` in `ranger-admin-site.xml`
limits the types that are registered.

- **Add a service** with the `+` on a card. The form asks for a name, display name, description, an
  optional security zone, the *active* flag, *select tag service* (to attach tag-based policies), the
  *audit filters* table, and the type-specific *config properties* that come from the service definition
  (for Hive, for example, the JDBC URL and the username Admin uses for lookups). Every plugin page under
  Plugins lists these properties.
- **Test Connection** validates the config properties by connecting to the component with the
  service-specific jars in `ews/webapp/WEB-INF/classes/ranger-plugins/<type>/`. A failure here
  does not block saving; it only means that resource lookup (autocomplete in the policy editor) will not
  work.
- **Audit filters** let you exclude, for example, service users or `SELECT` on a scratch database from
  the audit stream; see [Audit filters](../audit/audit-filters.md).
- **Export** and **Import** buttons on the Service Manager exchange policies as JSON, for all services or
  a selection; The REST equivalents are
  `GET /service/plugins/policies/exportJson` and `POST /service/plugins/policies/importPoliciesFromFile`.
- The pencil and trash icons edit or delete a service. Deleting a service deletes all of its policies.

## Policies

Clicking a service name opens its policy list, with tabs for the policy types the service supports:
**Access**, **Masking** and **Row Level Filter** (for services whose definition includes
`dataMaskDef`/`rowFilterDef`, such as Hive and Trino).

The list shows id, name, labels, status, audit logging flag, roles/groups/users, and actions. The search
bar supports filters on policy name, label, resource, user, group, role, status and policy id; searches
are also reflected in the URL so they can be shared.

**Add New Policy** opens the editor:

1. *Policy details*: type, name, labels, description, *Audit Logging*, *Policy Priority* (normal or
   override), and the *validity schedule* dialog for time-bound policies.
2. *Resources*: the resource fields of the service definition (database/table/column, path, topic, …)
   with autocomplete from resource lookup, wildcards, and per-level flags such as *recursive* or
   *include/exclude*.
3. *Allow conditions*, *exclude from allow*, *deny conditions* and *exclude from deny*: each row names
   roles, groups and users, the permissions to grant, an optional *delegate admin* flag, and any policy
   conditions defined for the service (IP range, time of day, custom expressions).

Every save increments the *policy version* shown in the list; older versions remain in the database and
are referenced from audit events. Concepts are explained in Resource policies,
Tag-based policies,
Row filter and column masking and
Policy conditions.

### Policy labels

Labels are free-form tags attached to policies. They are global across services, so once you have used
`pci` on a Hive policy it is offered as a suggestion in every other editor. Use them to:

- group related policies across services and search for them on the policy list;
- narrow the *Reports* page and export exactly that set of policies.

## Audits

The **Audits** screen has seven tabs.

| Tab | Source | What it shows |
| --- | --- | --- |
| Access | Audit store (`ranger.audit.source.type`) | One row per authorization decision from the plugins |
| Admin | `x_trx_log_v2` | Changes made in Admin, with before/after values in a detail dialog |
| Login Sessions | `x_auth_sess` | Every login attempt, with result, authentication type, client IP and user agent |
| Plugins | `x_policy_export_audit` | Each policy download by a plugin: service, plugin id, host, HTTP status, policy version |
| Plugin Status | `x_plugin_info` | Per plugin instance, which policy, tag, role and user-store version is active |
| User Sync | `x_ugsync_audit_info` | Runs of UserSync with the number of users and groups added, updated and deleted |
| Metrics | Audit store | Aggregated access counts per service over recent days (see [Metrics](metrics.md)) |

The **Access** tab shows time, application, user, service, resource, access type, result, access enforcer,
agent host, client IP, event count, tags, cluster and zone, and can be filtered on every column and by
time range.

Clicking a row in **Access** opens the event details, including the policy that matched (with a link to
that policy version) and the full resource. For Hive events an icon in the resource column opens the
*actual query* that was executed; copy it with the button in the pop-up. This is controlled by
`ranger.audit.hive.query.visibility` (default `true`) in `ranger-admin-site.xml`; set it to `false` to hide
queries from auditors.

**Plugin Status** columns:

Service Name, Service Type, Application
:   The service whose policies the plugin uses and the application (for example `hiveServer2`) hosting
    the plugin.

Host Name, Plugin IP, Cluster Name
:   Where the plugin runs.

Policy (Time)
:   *Last Update* of policies in Admin, when the plugin performed the *Download*, and when it made them
    *Active*.

Tag (Time), GDS (Time), Role (Time)
:   The same three timestamps for tag, Governed Data Sharing and role downloads; `--` when the service has
    no such data.

A warning icon appears next to a *Download* or *Active* time that is older than the corresponding *Last
Update*, which means the plugin has not yet picked up the latest change; when it persists, the plugin
usually cannot reach Admin. `ranger.plugin.<type>.policy.pollIntervalMs` on the plugin
side decides how often it checks.

## Settings

### Users/Groups/Roles

Three tabs list **Users**, **Groups** and **Roles** with search, *Add New*, *Set Visibility* and
*Delete* actions. For synced users the *Sync Details* dialog shows the raw attributes received from
UNIX or LDAP. Roles here are RBAC roles (named sets of users, groups and other roles used in policies),
not user roles; The user role (Admin, Auditor, …) is set on the user
form. Details in [Users, groups and roles](users-groups-roles.md).

### Permissions

Lists the UI modules and, per module, the users and groups allowed to open it. Click the edit icon on a
module to add or remove users and groups.

## Security Zones

**Security Zone** lists zones and their administrators, auditors and the services and resources they
cover. *Create Zone* asks for the zone name, admin and auditor users/groups/roles, the tag services, and
for each service the resources that belong to the zone. Policies inside a zone are managed by the zone
administrators, and the Service Manager has a zone selector at the top to switch between the unzoned view
and each zone. See [Security zones](../../features/sec-zone/intro.md).

## Reports

The **Reports** page (user access report) answers "what can this user, group or role access?". Choose a
*Search By* type (user, group or role), optional policy name, labels, component and resource filters, and
the page lists matching policies grouped by service type, with the permissions granted. *Export all below
policies* downloads the matching policies as JSON that can be imported again
(`GET /service/plugins/policies/exportJson`). The CSV and Excel exports of the REST API
(`GET /service/plugins/policies/csv`, `GET /service/plugins/policies/downloadExcel`) are marked deprecated
in the source.

## Key Manager

Users with the KeyAdmin or KMS Auditor role see **Key Manager** in the sidebar. Select a KMS service to list its keys,
create keys (name, cipher, length, description, attributes), roll over to a new key version, or delete a
key. Ranger KMS itself is described in [Ranger KMS](../kms/service.md).

## Governed Data Sharing

The **Governed Data Sharing** section has five screens: **My Datasets** and **My Datashares** (the
datasets and data shares you manage, with their shared resources and the datasets they are added to),
**My Requests** (pending and past requests to add a data share to a dataset), and the **Datasets** and
**Datashares** listings. Approving a request generates the underlying policies. See
[Governed Data Sharing](../../features/gds/gds_intro.md).

## User profile

**Profile** in the user menu shows your name, email and role and lets you change your password (for
internal users). External users manage passwords in the external system.

## Knox SSO

When Knox SSO is enabled and the browser is redirected to Knox, an interstitial page (`/knoxSSOWarning`)
is shown when the SSO handshake cannot complete; `/locallogin` opens the local login form for accounts
that exist only in Ranger. See [Authentication](authentication.md#knox-sso).

## Further reading

- React sources: [`security-admin/src/main/webapp/react-webapp/src/views`](https://github.com/apache/ranger/blob/master/security-admin/src/main/webapp/react-webapp/src/views)
- cwiki: [Plugin Status tab](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=68719402),
  [Policy labels](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=75975935),
  [Show Hive query in audit](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=75975833)
