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

# Core schema map

85 tables in `db/postgres/optimized/current/ranger_core_db_postgres.sql`. `id` is always the PK; `create_time`/`update_time`/`added_by_id`/`upd_by_id`
come from `XXDBBase` and **always FK to `x_portal_user`**, never `x_user`. Entities live in `security-admin/src/main/java/org/apache/ranger/entity/`.

## Service and policy

| Table | Entity | Notes |
|---|---|---|
| `x_service_def` | `XXServiceDef`, `XXServiceDefWithAssignedId` | `name`, `impl_class_name`, `def_options`. Children: `x_resource_def`, `x_access_type_def` (+`_grants`), `x_policy_condition_def`, `x_context_enricher_def`, `x_enum_def`/`x_enum_element_def`, `x_datamask_type_def`, `x_service_config_def` |
| `x_service` | `XXService`, `XXServiceWithAssignedId` | `type` -> `x_service_def`, `tag_service` -> self, `policy_version`, `tag_version`; UK `x_service_name`. Configs in `x_service_config_map` |
| `x_policy` | `XXPolicy` (`XXPolicyBase`), `XXPolicyWithAssignedId` | `service` -> `x_service`, `zone_id` -> `x_security_zone` (default 1), `policy_type` 0 access / 1 datamask / 2 rowfilter / 3 audit, `policy_priority`, `resource_signature`, **`policy_text`**; UKs `(name, service, zone_id)`, `(guid, service, zone_id)`, `(service, resource_signature)` |
| `x_policy_label`, `x_policy_label_map` | `XXPolicyLabel`, `XXPolicyLabelMap` | labels; UK `(policy_id, policy_label_id)` |

**`policy_text` is the source of truth.** `RangerPolicyServiceBase.mapViewToEntityBean` stores the whole `RangerPolicy` as JSON and
`RangerPolicyRetriever` deserializes it back. The normalized `x_policy_item`, `x_policy_item_{access,user_perm,group_perm,condition,datamask,rowfilter}`,
`x_policy_resource`, `x_policy_resource_map` tables (`XXPolicyItem*`, `XXPolicyResource*`) are **legacy**; only old Java patches still write them.
Do not model new work on them.

## Policy reference tables

`x_policy_ref_{user,group,role,resource,access_type,condition,datamask_type}` -> `XXPolicyRef*`. Shape after patch 077: `(id, policy_id, <fk>_id, <name>)`,
no audit columns. Column pairs: `user_id/user_name`, `group_id/group_name`, `role_id/role_name`, `resource_def_id/resource_name`, `access_def_id/access_type_name`,
`condition_def_id/condition_name`, `datamask_def_id/datamask_type_name`; UK on `(policy_id, <fk>_id)`. They exist so search, validation and delete-cascade
never parse `policy_text`. Maintained by `biz/PolicyRefUpdater`; `RoleRefUpdater` and `SecurityZoneRefUpdater` mirror it for `x_role_ref_*` and `x_security_zone_ref_*`.

## Versioning and change logs

| Table | Entity | Notes |
|---|---|---|
| `x_service_version_info` | `XXServiceVersionInfo` | `policy_version`, `tag_version`, `role_version`, `gds_version` (+ `*_update_time`). What plugins compare `lastKnownVersion` against |
| `x_policy_change_log` | `XXPolicyChangeLog` | `service_id`, `change_type` (`RangerPolicyDelta` constants), `policy_version`, `policy_type`, `zone_name`, `policy_id`, `policy_guid`; UK `(service_id, policy_version)`. Written by `ServiceDBStore` via `ServiceVersionUpdater`; read by `XXPolicyChangeLogDao.findLaterThan/findGreaterThan`; pruned by `deleteOlderThan` |
| `x_tag_change_log` | `XXTagChangeLog` | `service_tags_version`, `service_resource_id`, `tag_id`; UK `(service_id, service_tags_version)` |
| `x_ranger_global_state` | `XXGlobalState` (`Base`) | `state_name` UK + `app_data` + `@Version`. Global counters `RangerRole`, `RangerUserStore`, `RangerGDS`, `RangerSecurityZone` |
| `x_db_version_h` | none | patch bookkeeping; see `db-setup-flow.md` |

## Tags, zones, GDS

`x_tag_def` -> `x_tag` (`type` FK, `tag_attrs_text` JSON, `owned_by`, `policy_options`) -> `x_tag_resource_map` (`tag_id`, `res_id`) -> `x_service_resource`
(`service_id`, `resource_signature`, `service_resource_elements_text`, `tags_text`); `guid` columns are VARCHAR(64). Entities `XXTagDef`, `XXTag`, `XXTagResourceMap`,
`XXServiceResource`. `x_security_zone` (`XXSecurityZone`) holds `jsonData` + `gz_jsonData` (BYTEA/LONGBLOB, patch 069) with `x_security_zone_ref_{service,tag_srvc,resource,user,group,role}`.
Eight `x_gds_*` tables (`dataset`, `project`, `data_share`, `shared_resource`, `data_share_in_dataset`, `dataset_in_project`, `dataset_policy_map`, `project_policy_map`) -> `XXGds*`.

## Users, groups, modules

`x_portal_user` (`XXPortalUser`, UI login: `login_id` UK, `password`, `email`, `status`, `user_src`, `old_passwords`, `sync_source`) + `x_portal_user_role`
(`XXPortalUserRole`) are separate from `x_user` (`XXUser`, policy principal, `is_visible` soft delete, `cred_store_id`) and `x_group` (`XXGroup`, `group_name` UK,
`group_type`, `group_src`). Membership `x_group_users` (`XXGroupUser`, denormalized `group_name`, UK `(user_id, group_name)`), nesting `x_group_groups` (`XXGroupGroup`).
UI gating: `x_modules_master` (`XXModuleDef`) with `x_user_module_perm` (`XXUserPermission`, `user_id` -> **`x_portal_user`**) and `x_group_module_perm`
(`XXGroupPermission`, `group_id` -> `x_group`). Roles: `x_role` (`XXRole`) + `x_role_ref_{user,group,role}`.

## Audit and history

`xa_access_audit` (`XXAccessAudit`, `XXAccessAuditV4/V5`) only when `audit_store=db`; `x_trx_log_v2` (`XXTrxLogV2`, `change_info` JSON; `x_trx_log`/`XXTrxLog` is dead);
`x_data_hist` (`XXDataHist`, versioned `content` JSON); `x_policy_export_audit` (`XXPolicyExportAudit`, one row per plugin download, `http_ret_code` 200 new / 304 unchanged);
`x_plugin_info` (`XXPluginInfo`, `info` VARCHAR(1024) JSON with `policyDownloadedVersion`, `policyActiveVersion`, `tagActiveVersion`; UK `(service_name, host_name, app_type)`);
`x_auth_sess` (`XXAuthSession`); `x_ugsync_audit_info` (`XXUgsyncAuditInfo`); `x_audit_config` (patch 078, no entity yet).

## Caveats

- Vendor widths differ: `x_portal_user.first_name` is 1022 on mysql, 256 on postgres; `x_group_users.group_name` 740 vs 767; `x_db_version_h.active` is `ENUM('Y','N')`
  on mysql, `VARCHAR(1) CHECK` on postgres. Copy from the vendor file you are editing.
- Entities with no table in the current schema, not templates: `XXServiceResourceElement`, `XXServiceResourceElementValue`, `XXTagAttribute`, `XXTagAttributeDef`, `XXTrxLog`.
- RMS: `x_rms_service_resource`, `x_rms_resource_mapping`, `x_rms_mapping_provider`, `x_rms_notification` -> `XXRMS*`.
- Legacy pre-0.5 tables still created: `x_asset`, `x_resource`, `x_perm_map`, `x_audit_map`, `x_cred_store`.
- KMS has its own schema (`ranger_masterkey`, `ranger_keystore`) under `kms/scripts/db/<vendor>/kms_core_db.sql`, applied by `kms/scripts/db_setup.py`. See `ranger-kms`.
