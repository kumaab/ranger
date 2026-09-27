---
title: "Python Client"
---
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

# Python client

`apache-ranger` is the official Python package for Apache Ranger. It wraps the Ranger Admin REST API (service
definitions, services, policies, roles, security zones, tags), user and group management, Ranger KMS,
Governed Data Sharing and the PDP authorization API in a handful of client classes built on
`requests`. Model classes are thin `dict` subclasses, so you can construct them from JSON, read attributes
as properties, and pass them straight back to the API.

Use it for automation scripts, tests and integrations in Python; the source lives in
`intg/src/main/python` and is published to [PyPI](https://pypi.org/project/apache-ranger/).

## Installation

```bash
pip install apache-ranger
pip install requests-kerberos        # only for Kerberos/SPNEGO authentication
python -m pip show apache-ranger
```

- Package version on master: `0.0.13` (`intg/src/main/python/setup.py`)
- Python: 3.13 or later
- Dependencies: `requests`, `strenum`; optional `requests-kerberos`

To use the sources from a checkout without installing: `PYTHONPATH=intg/src/main/python python your_script.py`.

## Clients

| Class | Module | Talks to | Base path |
| --- | --- | --- | --- |
| `RangerClient` | `apache_ranger.client.ranger_client` | Ranger Admin | `service/public/v2/api` |
| `RangerUserMgmtClient` | `apache_ranger.client.ranger_user_mgmt_client` | Ranger Admin (wraps a `RangerClient`) | `service/xusers` |
| `RangerGdsClient` | `apache_ranger.client.ranger_gds_client` | Ranger Admin (wraps a `RangerClient`) | `service/gds` |
| `RangerKMSClient` | `apache_ranger.client.ranger_kms_client` | Ranger KMS | `kms/v1` |
| `RangerPDPClient` | `apache_ranger.client.ranger_pdp_client` | Ranger PDP | `authz/v1` |

## Authentication

`RangerClient(url, auth, query_params=None, headers=None)` creates a `requests.Session` and assigns `auth`
to it, so any `requests` authentication object works. `headers` and `query_params` are added to every call.

```python
from apache_ranger.client.ranger_client import RangerClient, HadoopSimpleAuth

# basic authentication
ranger = RangerClient("http://localhost:6080", ("admin", "rangerR0cks!"))

# Kerberos / SPNEGO (pip install requests-kerberos; needs a ticket in the cache)
from requests_kerberos import HTTPKerberosAuth
ranger = RangerClient("https://ranger.example.com:6182", HTTPKerberosAuth())

# JWT bearer token or trusted header
ranger = RangerClient("https://ranger.example.com:6182", None, headers={"Authorization": "Bearer " + token})

# Hadoop "simple" auth for Ranger KMS (adds ?user.name=<user>)
from apache_ranger.client.ranger_kms_client import RangerKMSClient
kms = RangerKMSClient("http://localhost:9292", HadoopSimpleAuth("keyadmin"))
```

TLS verification is controlled through the underlying session:

```python
ranger.session.verify = "/etc/ranger/ca-bundle.pem"   # CA bundle; False disables verification (testing only)
```

## Ranger Admin: `RangerClient`

```python
from apache_ranger.client.ranger_client import RangerClient
from apache_ranger.model.ranger_service import RangerService
from apache_ranger.model.ranger_policy  import RangerPolicy, RangerPolicyResource, RangerPolicyItem, RangerPolicyItemAccess

ranger = RangerClient("http://localhost:6080", ("admin", "rangerR0cks!"))

# service
service = RangerService({"name": "dev_hive", "type": "hive",
                         "configs": {"username": "hive", "password": "hive",
                                     "jdbc.driverClassName": "org.apache.hive.jdbc.HiveDriver",
                                     "jdbc.url": "jdbc:hive2://ranger-hadoop:10000"}})
created = ranger.create_service(service)
print("service id", created.id)

# access policy: analysts may select sales.orders
policy = RangerPolicy()
policy.service = "dev_hive"
policy.name    = "sales-readers"
policy.resources = {"database": RangerPolicyResource({"values": ["sales"]}),
                    "table":    RangerPolicyResource({"values": ["orders"]}),
                    "column":   RangerPolicyResource({"values": ["*"]})}
policy.policyItems = [RangerPolicyItem({"groups":   ["analysts"],
                                        "accesses": [RangerPolicyItemAccess({"type": "select"})]})]
created_policy = ranger.create_policy(policy)

# read, search, delete
p = ranger.get_policy("dev_hive", "sales-readers")
for p in ranger.find_policies({"serviceName": "dev_hive"}).list:
    print(p.id, p.name)
ranger.delete_policy("dev_hive", "sales-readers")
ranger.delete_service("dev_hive")
```

Masking and row-filter policies use `policy.policyType = RangerPolicy.POLICY_TYPE_DATAMASK` (`1`) with
`dataMaskPolicyItems` (`RangerDataMaskPolicyItem`, `dataMaskInfo.dataMaskType`, e.g. `MASK_SHOW_LAST_4`) or
`RangerPolicy.POLICY_TYPE_ROWFILTER` (`2`) with `rowFilterPolicyItems` (`rowFilterInfo.filterExpr`);
`sample_client.py` shows both.

### Methods

Service definitions
:   `create_service_def`, `update_service_def_by_id`, `update_service_def`, `delete_service_def_by_id`,
    `delete_service_def`, `get_service_def_by_id`, `get_service_def`, `find_service_defs`

Services
:   `create_service`, `get_service_by_id`, `get_service`, `update_service_by_id`, `update_service`,
    `delete_service_by_id`, `delete_service`, `find_services`

Policies
:   `create_policy`, `apply_policy`, `get_policy_by_id`, `get_policy`, `get_policy_by_name_zone`,
    `get_policies_in_service`, `update_policy_by_id`, `update_policy`, `update_policy_by_name_zone`,
    `delete_policy_by_id`, `delete_policy`, `delete_policy_by_name_zone`, `find_policies`

Security zones
:   `create_security_zone`, `update_security_zone_by_id`, `delete_security_zone_by_id`,
    `delete_security_zone`, `get_security_zone_by_id`, `get_security_zone`, `get_security_zone_headers`,
    `get_security_zone_service_headers`, `get_zone_names_for_resource`, `find_security_zones`

Security zones v2
:   `create_security_zone_v2`, `update_security_zone_v2`, `partial_update_security_zone_v2`
    (`RangerSecurityZoneChangeRequest`), `get_security_zone_v2`, `get_security_zone_v2_by_id`,
    `zone_v2_get_resources`, `zone_v2_by_id_get_resources`, `find_security_zones_v2`

Roles
:   `create_role`, `update_role`, `delete_role_by_id`, `delete_role`, `get_role_by_id`, `get_role`,
    `get_all_role_names`, `get_user_roles`, `find_roles`, `grant_role`, `revoke_role`

Tags, admin
:   `import_service_tags`, `get_service_tags`, `delete_policy_deltas`, `purge_records`, `set_log_level`

`find_*` methods take a `filter` dict of query parameters and return a `PList` with `list`, `totalCount`,
`startIndex`, `pageSize` and `resultSize`.

## Users and groups: `RangerUserMgmtClient`

```python
from apache_ranger.client.ranger_user_mgmt_client import RangerUserMgmtClient
from apache_ranger.model.ranger_user_mgmt import RangerUser, RangerGroup, RangerGroupUser

user_mgmt = RangerUserMgmtClient(ranger)

user  = user_mgmt.create_user(RangerUser({"name": "alice", "password": "Alice123!", "firstName": "Alice",
                                          "userRoleList": ["ROLE_USER"]}))
group = user_mgmt.create_group(RangerGroup({"name": "analysts"}))
user_mgmt.create_group_user(RangerGroupUser({"name": group.name, "parentGroupId": group.id, "userId": user.id}))

print([u.name for u in user_mgmt.find_users().list])
user_mgmt.delete_user_by_id(user.id, is_force_delete=True)
```

Methods: `create_user`, `update_user_by_id`, `delete_user_by_id`, `get_user_by_id`, `get_user`,
`get_groups_for_user`, `find_users`; `create_group`, `update_group_by_id`, `delete_group_by_id`,
`get_group_by_id`, `get_group`, `get_users_in_group`, `find_groups`; `create_group_user`,
`update_group_user`, `delete_group_user_by_id`, `find_group_users`, `get_group_users_for_group`.

## Ranger KMS: `RangerKMSClient`

```python
from apache_ranger.client.ranger_kms_client import RangerKMSClient
from apache_ranger.model.ranger_kms import RangerKey

kms = RangerKMSClient("http://localhost:9292", HadoopSimpleAuth("keyadmin"))
print(kms.kms_status())
key = kms.create_key(RangerKey({"name": "key1", "length": 128, "cipher": "AES/CTR/NoPadding"}))
eeks = kms.generate_encrypted_key("key1", 2)
```

Methods: `create_key`, `rollover_key`, `invalidate_cache_for_key`, `delete_key`, `get_key`, `get_key_metadata`,
`get_keys_metadata`, `get_current_key`, `get_key_version`, `get_key_versions`, `get_key_names`,
`generate_encrypted_key`, `decrypt_encrypted_key`, `reencrypt_encrypted_key`, `batch_reencrypt_encrypted_keys`,
`kms_status`. See [Ranger KMS](../../services/kms/service.md).

## Governed Data Sharing: `RangerGdsClient`

```python
from apache_ranger.client.ranger_gds_client import RangerGdsClient

gds = RangerGdsClient(ranger)
for ds in gds.find_datasets().list:
    print(ds.id, ds.name)
```

Methods cover datasets (`create_dataset`, `update_dataset`, `delete_dataset`, `get_dataset`, `find_datasets`,
`get_dataset_names`, `get_dataset_summary`, dataset policies `add_dataset_policy`, `update_dataset_policy`,
`delete_dataset_policy`, `get_dataset_policy`, `get_dataset_policies`), projects (`create_project`, ...,
`add_project_policy`, ...), data shares (`create_data_share`, ..., `find_data_shares`), shared resources
(`add_shared_resource`, `update_shared_resource`, `remove_shared_resource`, `get_shared_resource`,
`find_shared_resources`), and the links `add_data_share_in_dataset` / `add_dataset_in_project` with their
`update_*`, `remove_*`, `get_*`, `find_*` variants. Concepts are explained in
[Governed Data Sharing](../gds/gds_intro.md).

## Authorization: `RangerPDPClient`

`RangerPDPClient(url, auth, query_params=None, headers=None)` calls the Ranger PDP server:

```python
from apache_ranger.client.ranger_pdp_client import RangerPDPClient
from apache_ranger.model.ranger_authz import (RangerAccessContext, RangerAccessInfo, RangerAuthzRequest,
                                              RangerResourceInfo, RangerUserInfo)

pdp = RangerPDPClient("http://localhost:6500", auth=None, headers={"X-Forwarded-User": "hive"})

request = RangerAuthzRequest({
    "requestId": "req-1",
    "user":      RangerUserInfo({"name": "alice"}),
    "access":    RangerAccessInfo({"resource": RangerResourceInfo({"name": "table:sales/orders"}),
                                   "action": "QUERY", "permissions": ["select"]}),
    "context":   RangerAccessContext({"serviceType": "hive", "serviceName": "dev_hive"}),
})
result = pdp.authorize(request)
print(result.decision)                     # ALLOW / DENY / NOT_DETERMINED
perm = result.permissions["select"]
if perm.get("rowFilter"):
    print("row filter:", perm["rowFilter"]["filterExpr"])
```

| Method | Endpoint |
| --- | --- |
| `authorize(request)` | `POST /authz/v1/authorize` |
| `authorize_multi(request)` | `POST /authz/v1/authorizeMulti` |
| `get_resource_permissions(request)` | `POST /authz/v1/permissions` |
| `filter_resources(request)` | `POST /authz/v1/filterResources` |

Requests must include `context.serviceType`, `context.serviceName` and `user.name`. If the authenticated
caller differs from `user.name`, the caller must be allowed to delegate for that service, otherwise the PDP
returns `403 FORBIDDEN`. Request and response fields are described in
[Authorization API and PDP](../../dev/authz-api.md).

## Errors and return values

- Unexpected HTTP status codes raise `apache_ranger.exceptions.RangerServiceException`, whose message
  includes the API, status and response body.
- `404 Not Found`, `204 No Content`, `304 Not Modified` and `503 Service Unavailable` return `None` (`404`
  and `503` are logged as errors).
- Models are dicts: `policy["name"]` and `policy.name` are equivalent, and `json.dumps(policy)` works.

## Samples and tests

Runnable samples live in `ranger-examples/sample-client/src/main/python/` and are shipped in the
`ranger-<version>-sample-client.tar.gz` archive. Edit `ranger_url` / `ranger_auth` at the top of a script, then:

```bash
python ranger-examples/sample-client/src/main/python/sample_client.py
```

| Script | Demonstrates |
| --- | --- |
| `sample_client.py` | Service defs, services, access/mask/row-filter policies, roles, service tags |
| `user_mgmt.py` | Users, groups, group membership |
| `sample_kms_client.py` | Key lifecycle, EEK generate/decrypt/reencrypt |
| `sample_gds_client.py` | Datasets, projects, data shares, shared resources, GDS policies |
| `sample_pdp_client.py` | `authorize`, `authorize_multi`, `get_resource_permissions` |
| `security_zone_v2.py` | Zone v2 create, get, partial update, delete |

Unit tests for the client (mocked HTTP) run from `intg/`:

```bash
PYTHONPATH=src/main/python python -B src/test/python/test_ranger_client.py
```

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `ModuleNotFoundError: requests_kerberos` | `pip install requests-kerberos`. |
| `401 Unauthorized` | Wrong credentials, no Kerberos ticket, or missing auth header for the target service. |
| `403 Forbidden` | The user lacks the Ranger role/permission, or (PDP) delegation is not allowed for that service. |
| SSL certificate errors | Set `ranger.session.verify` to a CA bundle; never disable verification in production. |
| Connection timeouts | Check the URL and network path to Admin (`6080`/`6182`), KMS (`9292`) or PDP. |

## Further reading

- [Client libraries overview](intro.md), [Java client](java.md)
- [REST API](../../dev/rest-api.md)
- Source and README: [`intg/src/main/python`](https://github.com/apache/ranger/tree/master/intg/src/main/python)
