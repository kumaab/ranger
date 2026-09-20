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

# Import and export

Policy import and export move policies between Ranger Admin instances, or between services of the
same type on one instance, as a JSON file. Typical uses are promoting policies from a test cluster to
production, cloning a service's policies for a new service, keeping a versioned backup of policies in
source control, and migrating policies between security zones.

Export produces a file with a metadata header and the list of policies. Import reads such a file,
optionally remaps source service names and zone names to destination names, and creates (or updates,
or replaces) the policies. Roles have their own export and import endpoints with the same file
approach.

## Concepts

**Export file**
:   JSON with two top-level members: `metaDataInfo` (host name, exporting user, export time, Ranger
    version) and `policies`, an array of `RangerPolicy` objects exactly as the policy API returns them,
    including `service`, `zoneName`, `policyType`, `resources` and policy items.

**Service map**
:   For import, a JSON object `{ "<source service>": "<destination service>" }`. Source and
    destination must be of the same service type. Without a map, policies are imported into services
    with the same names as in the file.

**Zone map**
:   A JSON object `{ "<source zone>": "<destination zone>" }`. An empty source or destination means
    the unzoned (default) zone, which allows zone → zone, zone → unzoned, unzoned → zone and unzoned →
    unzoned imports.

**Override**
:   When `isOverride=true`, all existing policies of each destination service (in the destination
    zone) are deleted before the file's policies are created.

## Export

### Admin UI

On the **Service Manager** page, click **Export** in the top-right corner. Select the services to
export (all services are listed, grouped by type), or first pick a zone in the zone selector to export
only that zone's policies. The browser downloads `Ranger_Policies_<timestamp>.json`; the roles
export described below is named `Ranger_Roles_<timestamp>.json`.

### REST API

`GET /service/plugins/policies/exportJson` returns the file. It accepts the same search parameters as
the policy list endpoint, so you can narrow the export:

| Parameter | Description |
| --- | --- |
| `serviceName` | Comma-separated service names to export |
| `serviceType` | Comma-separated service types (`hive`, `hdfs`, ...) |
| `zoneName` | Export only policies of this zone; omit for unzoned policies |
| `policyType` | `0` access, `1` masking, `2` row filter |
| `checkPoliciesExists` | `true` returns `204 No Content` instead of a file when nothing matches |

Further filters are `policyName`, `policyLabel`, `user`, `group`, `role`, `polResource` and `resource:<name>`.
The UI uses `checkPoliciesExists` to validate the selection before it downloads the file.

```bash title="Export all Hive and HDFS policies"
curl -u admin:password -o ranger-policies.json \
  'http://localhost:6080/service/plugins/policies/exportJson?serviceName=cl1_hive,cl1_hadoop'
```

```bash title="Export the finance zone's Hive policies"
curl -u admin:password -o finance-hive.json \
  'http://localhost:6080/service/plugins/policies/exportJson?serviceName=cl1_hive&zoneName=finance'
```

```json title="Shape of the exported file"
{
  "metaDataInfo": {
    "Host name":             "ranger-admin.example.com",
    "Exported by":           "admin",
    "Export time":           "Sep 15, 2026 10:03:21 AM",
    "Ranger apache version": "3.0.0-SNAPSHOT"
  },
  "policies": [
    {
      "service":     "cl1_hive",
      "name":        "finance-analysts-select",
      "policyType":  0,
      "zoneName":    "finance",
      "resources":   { "database": { "values": ["finance"] }, "table": { "values": ["*"] }, "column": { "values": ["*"] } },
      "policyItems": [ { "groups": ["finance-analysts"], "accesses": [ { "type": "select", "isAllowed": true } ] } ]
    }
  ]
}
```

The same endpoint family also offers `GET /service/plugins/policies/csv` and
`GET /service/plugins/policies/downloadExcel` for reports; those files cannot be imported.

## Import

### Admin UI

On the **Service Manager** page, click **Import**. The dialog asks for:

1. **Select file** — an exported `.json` file. The dialog reads the file to discover the source
   services and the source zone (taken from the first policy's `zoneName`).
2. **Override policy** — the `isOverride` flag described above.
3. **Destination zone** — optional. When a zone is selected, only services associated with that zone
   are offered as destinations.
4. **Service mapping** — one row per source service, with a drop-down of destination services of the
   same type.

The UI posts the file together with `servicesMapJson` and `zoneMapJson` to the endpoint below.

### REST API

`POST /service/plugins/policies/importPoliciesFromFile` is a multipart request. It requires the Admin
or KeyAdmin role.

| Name | Where | Description |
| --- | --- | --- |
| `file` | form part | The exported JSON file; the file name must end with `json` |
| `servicesMapJson` | form part | Optional service map |
| `zoneMapJson` | form part | Optional zone map |
| `isOverride` | query | Delete existing policies of the destination services before import (default `false`) |
| `updateIfExists` | query | `true`: a destination policy with exactly the same resources is replaced by the file policy. Forces `isOverride=false` |
| `mergeIfExists` | query | `true`: the items of the file policy are merged into the destination policy with exactly the same resources; takes precedence over `updateIfExists` |
| `deleteIfExists` | query | `true`: a destination policy that exactly matches a file policy's resources is deleted before the file policy is created |
| `polResource` | query | With `updateIfExists=true`, restricts the pre-delete step to policies on this resource |
| `serviceType` | query | Only import policies for services of these comma-separated types |

With `updateIfExists=true` the matching destination policy keeps its id and takes the content of the file
policy; with `mergeIfExists=true` the file policy's items are merged into it instead. In both cases the file
policy is created when no destination policy matches (`ServiceREST.createPolicy` and `applyPolicy`).

```bash title="Import into the same service names, replacing existing policies"
curl -u admin:password \
  -F 'file=@ranger-policies.json' \
  'http://localhost:6080/service/plugins/policies/importPoliciesFromFile?isOverride=true'
```

```bash title="Import test-cluster policies into production services"
curl -u admin:password \
  -F 'file=@ranger-policies.json' \
  -F 'servicesMapJson={"test_hive":"prod_hive","test_hadoop":"prod_hadoop"};type=application/json' \
  'http://localhost:6080/service/plugins/policies/importPoliciesFromFile?isOverride=false&updateIfExists=true'
```

```bash title="Move the finance zone's policies into the finance-eu zone"
curl -u admin:password \
  -F 'file=@finance-hive.json' \
  -F 'servicesMapJson={"cl1_hive":"eu_hive"};type=application/json' \
  -F 'zoneMapJson={"finance":"finance-eu"};type=application/json' \
  'http://localhost:6080/service/plugins/policies/importPoliciesFromFile'
```

A successful import returns `200` with an empty body. If zero policies could be created the call
fails with *"zero policy is created from provided data file!!"*.

## Import semantics

- **Type matching.** A policy is imported only into a service of the same type as its source service.
  A source zone's services and the destination zone's services must therefore have matching types
  (for example two Hive services and one HDFS service on both sides).
- **Service map completeness.** When a service map is supplied, every source service in the map must
  appear in the file; otherwise the import is rejected. Policies for services not in the map are
  skipped.
- **Zone handling.** The destination zone comes from the zone map, or from the file's `zoneName` when
  no map is given. A policy whose destination service is not associated with the destination zone is
  skipped with a warning.
- **Override scope.** `isOverride=true` deletes only the policies of the mapped destination services
  in the destination zone, not every policy in Ranger.
- **Names and ids.** Policies are created with the names from the file; ids and GUIDs are not
  preserved. Without `updateIfExists` or `isOverride`, a name or resource clash makes that policy fail.
- **Admin audit.** The import writes `IMPORT START`, `IMPORT END` and, on failure, `IMPORT ERROR`
  entries with the file's `metaDataInfo` into **Audit → Admin**; each created policy is audited as
  usual. Exports are recorded as `EXPORT JSON`.
- **Tag policies** export and import exactly like resource policies; select the tag service.
- **GDS policies are skipped.** Policies of the `gds` service type are ignored by the importer; manage
  them through the [Governed data sharing](gds/gds_intro.md) APIs.
- **Users, groups, roles and services are not exported.** Destination services must exist. Users,
  groups and roles named in an imported policy must exist in the destination, exactly as when a policy is
  created through the API; otherwise Ranger Admin answers *"Operation denied. ... specified in policy does
  not exist in ranger admin"*. With the request parameter `createPrincipalsIfAbsent=true`, and when the
  caller is a Ranger administrator, the missing principals are created instead (`PolicyRefUpdater`).
  Import roles first if you want their membership to come along.

## Roles

Roles have their own pair of endpoints on `RoleREST` (Admin role required):

```bash title="Export and import roles"
curl -u admin:password -o roles.json http://localhost:6080/service/roles/roles/exportJson

curl -u admin:password -F 'file=@roles.json' \
  'http://localhost:6080/service/roles/roles/importRolesFromFile?updateIfExists=true&createNonExistUserGroupRole=true'
```

`createNonExistUserGroupRole=true` creates users, groups and nested roles named in the file that do
not yet exist. Import roles before importing policies that reference them.

## Related features

- [Resource-based policies](policies/resource-policies.md) — the objects being moved.
- [Security zones](sec-zone/intro.md) — zone → zone and zone ↔ unzoned imports.
- [Roles](roles.md) — role export and import.
- [REST API overview](../dev/rest-api.md) — authentication and base paths.
- Further reading: [User guide for Import-Export](https://cwiki.apache.org/confluence/spaces/RANGER/pages/68715688/User+Guide+for+Import-Export) (cwiki).
