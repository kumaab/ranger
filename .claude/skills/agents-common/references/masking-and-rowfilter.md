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

# Data masking and row filtering evaluation

Policy types `RangerPolicy.POLICY_TYPE_DATAMASK = 1`, `POLICY_TYPE_ROWFILTER = 2`. A service-def opts in through `dataMaskDef { accessTypes[], resources[],
maskTypes[] { itemId, name, label, transformer } }` and `rowFilterDef { accessTypes[], resources[] }` (see `service-def.md`).

## Engine path

`RangerBasePlugin.evalDataMaskPolicies(request, proc)` / `evalRowFilterPolicies(request, proc)` -> `RangerPolicyEngineImpl.evaluatePolicies(request, <type>, proc)`.
Separate tries per type (`dataMaskResourceTrie`, `rowFilterResourceTrie`), same prefiltering as ACCESS. Neither type has deny lists:
`RangerDefaultPolicyEvaluator.getMatchingPolicyItem(request, result)` scans `dataMaskEvaluators` or `rowFilterEvaluators` only. Priority: an earlier result is
final when `ret.getPolicyPriority() >= evaluator.getPolicyPriority()` (ACCESS uses `>`).

## What lands on the result

`RangerDefaultDataMaskPolicyItemEvaluator.updateAccessResult()` sets `result.setMaskType(...)`, `setMaskedValue(...)`, `setMaskCondition(...)` from
`RangerPolicyItemDataMaskInfo { dataMaskType, conditionExpr, valueExpr }`, each resolved through `RangerRequestExprResolver` when it contains `${{...}}` macros.
Read back with `getMaskType()`, `getMaskTypeDef()`, `getMaskedValue()`, `getMaskCondition()` (context keys `KEY_MASK_TYPE`, `KEY_MASK_CONDITION`, `KEY_MASKED_VALUE`).
`RangerDefaultRowFilterPolicyItemEvaluator` sets `result.setFilterExpr(...)` from `RangerPolicyItemRowFilterInfo { filterExpr }`. Mask constants on `RangerPolicy`:
`MASK_TYPE_NULL`, `MASK_TYPE_NONE`, `MASK_TYPE_CUSTOM`. The ACL view exposes the same via `RangerResourceACLs.getDataMasks()` / `getRowFilters()`.

## Consuming it (Hive is the reference)

`RangerHiveAuthorizer.applyRowFilterAndColumnMasking(HiveAuthzContext, List<HivePrivilegeObject>)`: per table/view `getRowFilterExpression(...)`, per column
`addCellValueTransformerAndCheckIfTransformed(...)`, then `hiveObj.setCellValueTransformers(...)`; `needTransform()` gates the hook. Transformer resolution:
`MASK_TYPE_NULL` -> literal NULL, `MASK_TYPE_CUSTOM` -> `result.getMaskedValue()`, otherwise `RangerDataMaskTypeDef.getTransformer()` with `{col}` replaced.
`plugin-nestedstructure` (`DataMasker`, `MaskTypes`, `RecordFilterJavaScript`) is a second, non-SQL consumer.

Tests: `test_policyengine_*` JSON cases carry `dataMaskResult` / `rowFilterResult` blocks beside `result`.
