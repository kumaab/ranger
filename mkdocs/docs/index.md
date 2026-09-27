---
template: home.html
title: Apache Ranger - the open source authz framework for data processing services
hide:
  - toc
  - navigation
  - feedback
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

# Goals Overview

- Centralized security administration to manage all authorization related tasks in a central UI or using REST APIs.
- Fine-grained authorization to do a specific action and/or operation with 20+ data processing services and managed through a central administration tool.
- Standardized authorization method across different data processing services.
- Enhanced support for different authorization methods like:
    - Role based access control (RBAC)
    - Attribute based access control (ABAC)
    - Tag based access control (TBAC)
- Centralized auditing of user access and administrative actions (security related) for all services.

# Find your way around

<div class="grid cards" markdown>

-   :material-rocket-launch:{ .lg .middle } __Getting started__

    ---

    What Ranger is, how to run it with Docker, and a first policy walkthrough.

    [:octicons-arrow-right-24: Introduction](getting-started/introduction.md)

-   :material-sitemap:{ .lg .middle } __Architecture__

    ---

    Components, data flows, the policy model and how plugins enforce policies inside each service.

    [:octicons-arrow-right-24: Overview](arch/architecture.md)

-   :material-puzzle:{ .lg .middle } __Plugins__

    ---

    Integration guides for Polaris, Trino, Ozone, Kafka, Hive, HDFS and every other supported service.

    [:octicons-arrow-right-24: Plugin overview](plugins/index.md)

-   :material-server:{ .lg .middle } __Services__

    ---

    Install, configure and operate Ranger Admin, UserSync, TagSync, KMS, PDP and the audit pipeline.

    [:octicons-arrow-right-24: Ranger Admin](services/admin/service.md)

-   :material-shield-check:{ .lg .middle } __Features__

    ---

    Resource and tag policies, masking and row filtering, ABAC, roles, security zones and data sharing.

    [:octicons-arrow-right-24: Policies](features/policies/resource-policies.md)

-   :material-code-braces:{ .lg .middle } __Developer guide__

    ---

    Build from source, call the REST API, embed the authorization API and write custom plugins.

    [:octicons-arrow-right-24: Building from source](dev/build.md)

-   :material-package-variant:{ .lg .middle } __Releases__

    ---

    Release notes for every version, download locations and how to verify signatures.

    [:octicons-arrow-right-24: Releases](release-notes/index.md)

-   :material-account-group:{ .lg .middle } __Project__

    ---

    How to contribute, community channels, security policy and the release process.

    [:octicons-arrow-right-24: Contributing](project/contributing.md)

</div>
