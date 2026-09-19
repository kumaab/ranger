---
title: "Trino with Ranger"
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

[DockerHub]: https://hub.docker.com/r/apache/ranger

# Trino with Ranger

This guide walks you through the steps to run Trino with Apache Ranger as the access control
enforcer. Trino ships its own Ranger access control plugin, so the Trino container only needs to be
pointed at a Ranger service and given the Ranger plugin configuration files. Ranger Admin is assumed
to be running on the `rangernw` Docker network, either from the [DockerHub] images or from the
compose setup described in [Run Ranger with Docker](docker.md).

!!! tip

    `dev-support/ranger-docker/docker-compose.ranger-trino.yml` starts a ready-configured Trino
    container (`ranger-trino`) with the files below already in place, and the Docker bootstrap
    creates the `dev_trino` service in Ranger Admin. The manual steps here are useful when you run
    your own Trino image.

## Run the Trino container

```shell title="Run Trino in Ranger's network"
docker pull trinodb/trino
docker run -p 8080:8080 --name trino --network rangernw trinodb/trino

# for more details: https://hub.docker.com/r/trinodb/trino
```

## Create the Trino service in Ranger Admin

In **Service Manager**, add a new service of type Trino named `dev_trino` (the name referenced by
`ranger.service.name` below). See the [Trino plugin](../plugins/trino.md) page for the service
configuration properties.

## Configure the Ranger plugin in the Trino container

```properties title="Update access-control.properties in /etc/trino/ in the Trino container"
access-control.name=ranger
ranger.service.name=dev_trino
ranger.plugin.config.resource=/etc/trino/ranger-trino-security.xml,/etc/trino/ranger-trino-audit.xml,/etc/trino/ranger-policymgr-ssl.xml
ranger.hadoop.config.resource=

# For details to configure Apache Ranger: https://trino.io/docs/current/security/ranger-access-control.html
```

Copy `ranger-trino-security.xml`, `ranger-trino-audit.xml` and `ranger-policymgr-ssl.xml` into
`/etc/trino/`. Working examples, pointing at `http://ranger:6080` and the `dev_trino` service, are in
[`dev-support/ranger-docker/scripts/trino`](https://github.com/apache/ranger/blob/master/dev-support/ranger-docker/scripts/trino).

## Restart the Trino container

```shell
docker restart trino
```

After the restart, Trino downloads the `dev_trino` policies and every query is authorized by Ranger.
Confirm the plugin is connected under **Audit → Plugin Status** in Ranger Admin, then create
policies for catalogs, schemas, tables and columns as described in
[Resource-based policies](../features/policies/resource-policies.md).
