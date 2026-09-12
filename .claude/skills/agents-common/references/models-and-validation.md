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

# Models and validation

## `RangerBaseModelObject`

```java
@JsonAutoDetect(getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE, fieldVisibility = Visibility.ANY)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RangerBaseModelObject implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private Long    id;
    private String  guid;
    private Boolean isEnabled;
    private String  createdBy;
    private String  updatedBy;
    private Date    createTime;
    private Date    updateTime;
    private Long    version;
```

Helpers used by every subclass: `nullSafeList/Set/Map(coll)` in setters (supplier pluggable via `setNullSafeSupplier`, plugins default to `v2` through
`ranger.plugin.<svc>.null_safe.supplier`), `getUpdatableList/Set/Map(curr)` in `addXxx` methods, `updateFrom(other)`.

Subclass header:

```java
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RangerFoo extends RangerBaseModelObject implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
```

`toString` convention (all models, matchers, repositories):

```java
@Override
public String toString() {
    StringBuilder sb = new StringBuilder();

    toString(sb);

    return sb.toString();
}

public StringBuilder toString(StringBuilder sb) {
    sb.append("RangerFoo={");
    super.toString(sb);
    sb.append("name={").append(name).append("} ");
    sb.append("}");

    return sb;
}
```

`equals`/`hashCode` are written only where identity matters (`RangerPolicy`, `RangerServiceDef`, `RangerRole`, `RangerTag`, ...). `@XmlRootElement` is legacy (4 classes); do not add it.
Nested value types are `public static class ... implements java.io.Serializable` with `serialVersionUID = 1L` and the same Jackson annotations.

## `RangerPolicy`

Fields: `service`, `name`, `policyType`, `policyPriority`, `description`, `resourceSignature`, `isAuditEnabled`, `resources (Map<String, RangerPolicyResource>)`,
`additionalResources`, `conditions`, `policyItems`, `denyPolicyItems`, `allowExceptions`, `denyExceptions`, `dataMaskPolicyItems`, `rowFilterPolicyItems`, `serviceType`,
`options`, `validitySchedules`, `policyLabels`, `zoneName`, `isDenyAllElse`.

Nested: `RangerPolicyResource { values, isExcludes, isRecursive }`, `RangerPolicyItem { accesses, users, groups, roles, conditions, delegateAdmin }`,
`RangerPolicyItemAccess { type, isAllowed }`, `RangerPolicyItemCondition { type, values }`, `RangerDataMaskPolicyItem` + `RangerPolicyItemDataMaskInfo { dataMaskType, conditionExpr, valueExpr }`,
`RangerRowFilterPolicyItem` + `RangerPolicyItemRowFilterInfo { filterExpr }`. Mask constants `MASK_TYPE_NULL`, `MASK_TYPE_NONE`, `MASK_TYPE_CUSTOM`.

`RangerValiditySchedule { startTime, endTime, timeZone, recurrences }` with `VALIDITY_SCHEDULE_DATE_STRING_SPECIFICATION = "yyyy/MM/dd HH:mm:ss"`.
`RangerPolicyResourceSignature` hashes `resources` (+ `additionalResources`) into `RangerPolicy.resourceSignature`; `SignatureVersion = 1`.

Models used by Admin REST responses also live here (`RangerAuditMetrics`, `RangerAuditMetricsByDays`, ...). Search keys are constants on `util/SearchFilter`.

## Validators (`model/validation`)

`RangerValidator` (abstract) holds `ServiceStore store` / `RoleStore roleStore` and `enum Action { CREATE, UPDATE, DELETE }`. Public `validate(obj, action)` collects
`ValidationFailureDetails` and throws `Exception(serializeFailures(failures))`. Internal `isValid(...)` methods **accumulate** failures and return a boolean; never return early.

```java
if (CollectionUtils.isEmpty(serviceDef.getResources())) {
    ValidationErrorCode error = ValidationErrorCode.SERVICE_DEF_VALIDATION_ERR_MISSING_FIELD;

    failures.add(new ValidationFailureDetailsBuilder().field("resources").isMissing().errorCode(error.getErrorCode()).becauseOf(error.getMessage("resources")).build());

    valid = false;
}
```

Builder methods: `field`, `subField`, `isMissing`, `isSemanticallyIncorrect`, `isAnInternalError`, `becauseOf`, `errorCode`, `build`.

Entry points: `RangerServiceDefValidator.validate(RangerServiceDef, Action)` (normalizes first), `RangerServiceValidator.validate(RangerService, Action)`,
`RangerPolicyValidator.validate(RangerPolicy, Action, boolean isAdmin)`, `RangerSecurityZoneValidator.validate(RangerSecurityZone, Action)`,
`RangerRoleValidator.validate(RangerRole, Action)`, `RangerValidityScheduleValidator`. Admin obtains them through `RangerValidatorFactory` (security-admin).

## `ValidationErrorCode` (`errors/`)

Enum of `(int code, String template)` with `MessageFormat` placeholders:

```java
SERVICE_DEF_VALIDATION_ERR_IMPLIED_GRANT_UNKNOWN_ACCESS_TYPE(2009, "implied grant[{0}] contains an unknown access types[{1}]"),
```

Blocks: 1xxx service, 2xxx service-def, 3xxx policy, 4xxx security zone / role / GDS (last used 4133). `getMessage(Object...)`, `getErrorCode()`.
Name pattern `<AREA>_VALIDATION_ERR_<WHAT>`.
