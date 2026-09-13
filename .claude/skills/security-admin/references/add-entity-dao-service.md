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

# Adding an entity, DAO, service, and view

SQL side (five vendor patches + optimized schema) is covered by the `security-admin-db` skill. Java side, in order:

## 1. Entity

Split into a `@MappedSuperclass` holding the columns and a concrete `@Entity` holding the id, when an `...WithAssignedId` variant is or may be needed
(`XXService`/`XXServiceWithAssignedId`, `XXPolicy`, `XXServiceDef`). Simple tables use a single class.

```java
@MappedSuperclass
public abstract class XXGlobalStateBase extends XXDBBase {
    @Version
    @Column(name = "version")
    protected Long version;

    @Column(name = "state_name")
    protected String stateName;
}

@Entity
@Cacheable
@Table(name = "x_ranger_global_state")
public class XXGlobalState extends XXGlobalStateBase implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @SequenceGenerator(name = "x_ranger_global_state_SEQ", sequenceName = "x_ranger_global_state_SEQ", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.AUTO, generator = "x_ranger_global_state_SEQ")
    @Column(name = "id")
    protected Long id;
}
```

`XXDBBase` (`entity/XXDBBase.java`) supplies `createTime`, `updateTime`, `addedByUserId`, `updatedByUserId` mapped to `CREATE_TIME`, `UPDATE_TIME`,
`ADDED_BY_ID`, `UPD_BY_ID`. `sequenceName` must equal the Oracle/Postgres sequence created by the SQL patch (`<table>_SEQ`). Override `equals`,
`hashCode`, and `toString` like neighbours. No `@NamedQuery` annotations anywhere.

Table-by-table map with entity names: `security-admin-db` `references/schema-map.md`.

## 2. persistence.xml

`security-admin/src/main/resources/META-INF/persistence.xml`, unit `defaultPU`: one `<class>` per entity class (Base classes too).

## 3. Named queries

`security-admin/src/main/resources/META-INF/jpa_named_queries.xml`, grouped under an `<!-- XXEntity -->` comment, alias `obj`, params `:camelCase`:

```xml
<named-query name="XXGlobalState.findByStateName">
    <query>select obj from XXGlobalState obj where obj.stateName = :stateName</query>
</named-query>
```

## 4. DAO

```java
@Service
public class XXGlobalStateDao extends BaseDao<XXGlobalState> {
    public XXGlobalStateDao(RangerDaoManagerBase daoManager) {
        super(daoManager);
    }

    public XXGlobalState findByStateName(String stateName) {
        if (StringUtils.isBlank(stateName)) {
            return null;
        }

        try {
            return getEntityManager().createNamedQuery("XXGlobalState.findByStateName", tClass).setParameter("stateName", stateName).getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
}
```

`BaseDao` gives `create`, `batchCreate`, `update`, `remove`, `getById`, `getAll`, `batchDeleteByIds(namedQuery, ids, paramName)`, `executeQueryInSecurityContext`.
Return `null`/empty list on `NoResultException`; never throw from a finder.

## 5. DAO registration

`db/RangerDaoManagerBase.java` is hand-written. Add:

```java
public XXGlobalStateDao getXXGlobalState() {
    return new XXGlobalStateDao(this);
}
```

## 6. Service (`RangerBaseModelService` flavour)

`service/RangerFooServiceBase<T extends XXFoo, V extends RangerFoo> extends RangerAuditedModelService<T, V>` declares search/sort fields and trx-log attrs
in its constructor; `RangerFooService extends RangerFooServiceBase<XXFoo, RangerFoo>` implements the mapping.

```java
public RangerServiceServiceBase() {
    super(AppConstants.CLASS_TYPE_XA_SERVICE);

    searchFields.add(new SearchField(SearchFilter.SERVICE_TYPE, "xSvcDef.name", DATA_TYPE.STRING, SEARCH_TYPE.FULL, "XXServiceDef xSvcDef", "obj.type = xSvcDef.id"));
    searchFields.add(new SearchField(SearchFilter.SERVICE_NAME, "obj.name", DATA_TYPE.STRING, SEARCH_TYPE.FULL));
    searchFields.add(new SearchField(SearchFilter.SERVICE_NAME_PARTIAL, "obj.name", DATA_TYPE.STRING, SEARCH_TYPE.PARTIAL));
    searchFields.add(new SearchField(SearchFilter.IS_ENABLED, "obj.isEnabled", DATA_TYPE.BOOLEAN, SEARCH_TYPE.FULL));

    sortFields.add(new SortField(SearchFilter.CREATE_TIME, "obj.createTime"));
    sortFields.add(new SortField(SearchFilter.SERVICE_ID, "obj.id", true, SORT_ORDER.ASC));

    trxLogAttrs.put("name", new VTrxLogAttr("name", "Service Name", false, true));
    trxLogAttrs.put("configs", new VTrxLogAttr("configs", "Connection Configurations"));
}
```

`SearchField(clientFieldName, jpqlFieldName, DATA_TYPE, SEARCH_TYPE[, joinTables, joinCriteria])`; helpers `SearchField.createString/createLong/createEnum`.
`VTrxLogAttr(attribName, attribUserFriendlyName[, isEnum, isObjName])`. Class-type constants live in `common/AppConstants`.

Mapping contract:

```java
@Override
protected XXService mapViewToEntityBean(RangerService vObj, XXService xObj, int operationContext) {
    String guid = StringUtils.isEmpty(vObj.getGuid()) ? guidUtil.genGUID() : vObj.getGuid();

    xObj.setGuid(guid);

    XXServiceDef xServiceDef = daoMgr.getXXServiceDef().findByName(vObj.getType());

    if (xServiceDef == null) {
        throw restErrorUtil.createRESTException("No ServiceDefinition found with name :" + vObj.getType(), MessageEnums.INVALID_INPUT_DATA);
    }

    xObj.setType(xServiceDef.getId());
    xObj.setName(vObj.getName());

    if (operationContext == OPERATION_CREATE_CONTEXT) {
        xObj.setTagVersion(vObj.getTagVersion());
    }

    return xObj;
}

@Override
protected RangerService mapEntityToViewBean(RangerService vObj, XXService xObj) { ... }
```

Operation contexts: `OPERATION_CREATE_CONTEXT=1`, `UPDATE=2`, `DELETE=3`, `IMPORT_CREATE=4`, `IMPORT_DELETE=5`. Override `validateForCreate`/`validateForUpdate`
to throw `restErrorUtil.createRESTException(...)`. `populateViewBean` already sets id, create/update time, createdBy/updatedBy before calling `mapEntityToViewBean`.
The `@Autowired protected BaseDao<T> entityDao` resolves generically to the `@Service` DAO bean.

Transaction log: `onObjectChange(current, former, action)` -> `createTransactionLog` -> `bizUtil.createTrxLog(List<XXTrxLogV2>)`. Attributes named
`"Password"` are masked with `ranger.password.hidden` (default `*****`).

## 7. View

```java
@JsonAutoDetect(getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE, fieldVisibility = Visibility.ANY)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class RangerAuditMetricsList extends VList {
    private static final long serialVersionUID = 1L;

    List<RangerAuditMetrics> rangerAuditMetricsList = new ArrayList<>();

    @Override public int getListSize() { return rangerAuditMetricsList != null ? rangerAuditMetricsList.size() : 0; }
    @Override public List<?> getList() { return rangerAuditMetricsList; }
}
```

Models themselves (`RangerFoo extends RangerBaseModelObject`) live in `agents-common/src/main/java/org/apache/ranger/plugin/model/` so plugins can share them.
Legacy `VX*` DTOs extend `VXDataObject` and add `@JsonIgnoreProperties(ignoreUnknown = true)`.

## 8. Biz + REST

Store method in `biz/*DBStore` (or `*Mgr`) using `daoMgr.getXXFoo()` and the service; then the REST method per [add-rest-endpoint.md](add-rest-endpoint.md).

## 9. Friendly constraint messages

`security-admin/src/main/resources/db_message_bundle.properties`: `<table>_UK_<column>=Message` maps a unique-constraint name to a user-facing error.
