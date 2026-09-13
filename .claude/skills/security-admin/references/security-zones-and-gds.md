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

# Security zones and Governed Data Sharing

## Security zones

Model `RangerSecurityZone` (agents-common), `RANGER_UNZONED_SECURITY_ZONE_ID = 1L`: policies with zone id null or 1 are global.

| Layer | Classes |
|---|---|
| REST | `SecurityZoneREST` (`@Path("zones")`, incl. `GET /zones/summary`), `/api/zones` and `/api/zones-v2` on `PublicAPIsv2` |
| biz | `SecurityZoneDBStore implements SecurityZoneStore`, `SecurityZoneRefUpdater` |
| service | `RangerSecurityZoneServiceBase`, `RangerSecurityZoneServiceService` |
| entity | `XXSecurityZone` (+`Base`), `XXSecurityZoneRef{Service,TagService,Resource,User,Group,Role}` |
| validator | `RangerSecurityZoneValidator` (agents-common) via `RangerValidatorFactory` |

`createSecurityZone` does **not** create default policies: name dupe-check -> `onGlobalStateChange` -> `securityZoneService.create` ->
`securityZoneRefUpdater.createNewZoneMappingForRefTable` -> trx log. `updateSecurityZoneById` also calls `updateResourceSignatureWithZoneName`
when the zone is renamed, because the zone name is part of every policy's resource signature. Removing a service from a zone is driven from the
service layer: `RangerSecurityZoneServiceService` calls `serviceDBStore.deleteZonePolicies(serviceNames, zoneId)`.

Zone admin/auditor checks live on `ServiceMgr.isZoneAdmin/isZoneAuditor` (see `service-management.md`).

Plugin side: zones ride in `ServicePolicies.securityZones : Map<String, SecurityZoneInfo>`; matching by `policyengine/RangerSecurityZoneMatcher`
and `model/validation/RangerZoneResourceMatcher`; the engine keeps one `RangerPolicyRepository` per zone (`agents-common` `access-evaluation.md`).

## GDS

Service-def name is `gds` (`EmbeddedServiceDefsUtil.EMBEDDED_SERVICEDEF_GDS_NAME`, `ranger-servicedef-gds.json`); the single bootstrapped service
instance is `_gds` (`GdsPolicyEngine.GDS_SERVICE_NAME`, mirrored as `ServiceDBStore.GDS_SERVICE_NAME`).

| Layer | Classes |
|---|---|
| REST | `GdsREST` (`@Path("gds")`): `/dataset`, `/project`, `/datashare`, `/resource(s)`, `/datashare/dataset`, `/dataset/project`, `/dataset/{id}/grant(s)`, `/download/{serviceName}` (+ `/secure/`) |
| biz | `GdsDBStore extends AbstractGdsStore`, `GdsPolicyAdminCache` |
| service | `RangerGdsBaseModelService` + `RangerGds{Dataset,Project,DataShare,SharedResource,DataShareInDataset,DatasetInProject}Service` |
| entity | `XXGdsDataset`, `XXGdsProject`, `XXGdsDataShare`, `XXGdsSharedResource`, `XXGdsDataShareInDataset`, `XXGdsDatasetInProject`, `XXGdsDatasetPolicyMap`, `XXGdsProjectPolicyMap` |
| validator | `validation/RangerGdsValidator` + `RangerGdsValidationDataProvider` / `RangerGdsValidationDBProvider` (the only validators not in agents-common) |
| engine | `agents-common` `policyengine/gds/GdsPolicyEngine`, `GdsDatasetEvaluator`, `GdsProjectEvaluator`, `GdsDataShareEvaluator`, `GdsSharedResourceEvaluator` |

`GdsDBStore` constants: `RESOURCE_NAME_DATASET_ID = "dataset-id"`, `RESOURCE_NAME_PROJECT_ID = "project-id"`, `GDS_POLICY_NAME_TIMESTAMP_SEP = "@"`
(dataset/project policies are named `DATASET: <name>@<millis>`). Share lifecycle: `RangerGds.GdsShareStatus { NONE, REQUESTED, GRANTED, DENIED, ACTIVE }`.
Versioning: `XXServiceVersionInfo.gdsVersion` via `VERSION_TYPE.GDS_VERSION` plus `XXGlobalStateDao.RANGER_GLOBAL_STATE_NAME_GDS`. Module gate:
`RangerConstants.MODULE_GOVERNED_DATA_SHARING`. UI: `views/GovernedData/{Dataset,Datashare,Request}`.
