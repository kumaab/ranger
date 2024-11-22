#!/usr/bin/env python

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from apache_ranger.model.ranger_service import *
from apache_ranger.client.ranger_client import *
from apache_ranger.model.ranger_policy import *


class TestPolicyManagement:
    ROBOT_LIBRARY_SCOPE = 'SUITE'

    def __init__(self, ranger_url, username, password):
        self.ranger = RangerClient(ranger_url, (username, password))
        self.ranger.session.verify = False
        return

    def get_hive_policy(self, service_name, policy_name):
        return self.ranger.get_policy(service_name, policy_name)

    def delete_hive_policy(self, service_name, policy_name):
        return self.ranger.delete_policy(service_name, policy_name)

    def create_hive_policy(self, service_name, policy_name, db_name):
        policy = RangerPolicy()
        policy.service = service_name
        policy.name = policy_name
        policy.resources = {'database': RangerPolicyResource({'values': [db_name]}),
                            'table': RangerPolicyResource({'values': ['test_tbl']}),
                            'column': RangerPolicyResource({'values': ['*']})}

        allowItem1 = RangerPolicyItem()
        allowItem1.users = ['admin']
        allowItem1.accesses = [RangerPolicyItemAccess({'type': 'create'}),
                               RangerPolicyItemAccess({'type': 'alter'})]

        denyItem1 = RangerPolicyItem()
        denyItem1.users = ['admin']
        denyItem1.accesses = [RangerPolicyItemAccess({'type': 'drop'})]

        policy.policyItems = [allowItem1]
        policy.denyPolicyItems = [denyItem1]

        print(f'Creating policy: name={policy.name}')

        created_policy = self.ranger.create_policy(policy)

        print(f'Created policy: name={created_policy.name}, id={created_policy.id}')
        return created_policy

    def get_all_policies(self):
        all_policies = self.ranger.find_policies()
        return all_policies


class TestServiceManagement:
    ROBOT_LIBRARY_SCOPE = 'SUITE'

    def __init__(self, ranger_url, username, password):
        self.ranger = RangerClient(ranger_url, (username, password))
        self.ranger.session.verify = False
        return

    def create_service(self, service_name, service_type, configs):
        service = RangerService()
        service.name = service_name
        service.type = service_type
        service.configs = configs
        return self.ranger.create_service(service)

    def delete_service(self, service_name):
        return self.ranger.delete_service(service_name)

