---
title: Getting started with Ranger
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

[Docker Docs]: https://docs.docker.com/get-started/overview/
[Docker Image]: https://hub.docker.com/r/apache/ranger

# Installation

There are two ways to get Apache Ranger running, and both use Docker: start a released version from
the images on Docker Hub, or build the current source and run it with the Docker Compose setup in the
repository. Both end with Ranger Admin listening on port 6080.

!!! tip

    If you're new to Docker 🐳, we recommend reading [Docker Docs],
    which provides extensive documentation around how to get started with Docker.

Docker Hub images
:   Try a released version in minutes. You need Docker. See [Docker Hub images](#docker).

Build and run from source
:   Try the latest code, develop plugins, or run Trino, Ozone, Kafka, Hive, HBase or Knox with Ranger
    authorization already configured. You need Docker with Compose v2 and git; a local JDK and Maven
    are optional. See [Build and run from source](#from-source).

This page does not describe how to configure each Ranger service or plugin for your own environment.
That lives with the component: the [service pages](#configure-ranger-for-your-environment) document
the site configuration files of Ranger Admin, UserSync, TagSync, KMS, PDP and the audit server, and
each [plugin page](../plugins/index.md) documents what the protected service needs.

## Docker Hub images { #docker data-toc-label="Docker Hub images" }

The official [Docker image] is a great way to get up and running in a few
minutes, as it comes with all dependencies pre-installed. The commands below pull the images and run containers for Apache Ranger and its dependent services (`Postgres DB`, `Apache Solr` & `Apache Zookeeper`).

```bash
export RANGER_VERSION=2.7.0   # pick a tag published on Docker Hub
docker network create rangernw
docker run -d --name ranger-zk   --hostname ranger-zk.example.com   --network rangernw -p 2181:2181 apache/ranger-zk:${RANGER_VERSION}
docker run -d --name ranger-solr --hostname ranger-solr.example.com --network rangernw -p 8983:8983 apache/ranger-solr:${RANGER_VERSION} solr-precreate ranger_audits /opt/solr/server/solr/configsets/ranger_audits/
docker run -d --name ranger-db   --hostname ranger-db.example.com   --network rangernw --health-cmd='su -c "pg_isready -q" postgres' --health-interval=10s --health-timeout=2s --health-retries=30 apache/ranger-db:${RANGER_VERSION}
docker run -d --name ranger      --hostname ranger.example.com      --network rangernw -e RANGER_VERSION=${RANGER_VERSION} -e RANGER_DB_TYPE=postgres -p 6080:6080 apache/ranger:${RANGER_VERSION} /home/ranger/scripts/ranger.sh
```

Then open <http://localhost:6080> and log in as `admin` / `rangerR0cks!`. The full walkthrough is in
[Run Ranger with Docker](docker.md#docker-hub-images).

???+ warning

    The Docker container is intended for development purposes only and
    is not suitable for production based deployment.

## Build and run from source { #from-source data-toc-label="Build and run from source" }

The repository contains a Docker Compose setup in
[`dev-support/ranger-docker`](https://github.com/apache/ranger/blob/master/dev-support/ranger-docker)
and a wrapper script, `ranger_in_docker`, at the repository root. The script builds Ranger from the
checked-out source (inside a build container, so you do not need a local JDK or Maven), builds the
images and starts Ranger Admin, its database, ZooKeeper, a Kerberos KDC and UserSync. Optional
containers add TagSync, KMS, Kafka, Knox, Hadoop, Hive and HBase, each already configured for
Ranger authorization; Trino, Ozone, PDP and the audit pipeline are started with the compose files
directly.

```bash
git clone https://github.com/apache/ranger.git
cd ranger
export ENABLED_RANGER_SERVICES="hadoop,hive"   # optional extra services
./ranger_in_docker up
```

The first run takes a while because it builds Ranger and downloads the archives of the protected
services; later starts take a minute or two. Use `./ranger_in_docker down` to stop everything. See
[Run Ranger with Docker](docker.md) for the compose files, profiles, ports and the choice of database
and audit store.

To build Ranger yourself, with Maven on your machine or in the build container, and to learn which
archives the build produces, see [Building from source](../dev/build.md). Released source archives
are listed on the [download page](../release-notes/download.md).

## Prerequisites at a glance

- **Docker.** A recent Docker; Compose v2 (`docker compose`) for the source setup. Give Docker about
  4 GB of memory for the full stack: every Ranger service defaults to a 256 MB heap
  (`RANGER_*_MAX_HEAP` in `dev-support/ranger-docker/.env`).
- **Java and Maven.** Not needed on your machine; the build runs in a container based on
  `apache/ranger-base` with JDK 17. To build locally you need JDK 17 and Apache Maven 3.6.3 or later
  (`java.version.required` and `maven.version.required` in the root `pom.xml`).
- **Database.** Started by compose. `RANGER_DB_TYPE` selects `postgres` (default), `mysql`, `oracle`
  or `sqlserver`.
- **Audit store.** Started by compose. `AUDIT_INDEX_STORE` selects `opensearch` (default) or `solr`.

## Default credentials

Ranger Admin in Docker: user `admin`, password `rangerR0cks!`. These are fixed development
credentials; change the passwords of the built-in accounts before exposing Ranger Admin to anyone
else. See [Users, groups and roles](../services/admin/users-groups-roles.md).

## Configure Ranger for your environment

Each Ranger service reads its settings from site configuration files, and each plugin reads a set of
`ranger-<service>-*.xml` files from the classpath of the service it protects. The reference for each
lives on its own page:

- [Ranger Admin](../services/admin/service.md), with [Database](../services/admin/database.md) and
  [Authentication](../services/admin/authentication.md)
- [UserSync](../services/usersync/service.md) and [TagSync](../services/tagsync/service.md)
- [KMS](../services/kms/service.md), [PDP](../services/pdp/service.md) and the
  [Audit server](../services/audit-server/service.md)
- [Plugins](../plugins/index.md): one page per protected service
- [Audit stores](../services/audit/audit-stores.md)

## Next steps

- [Your first policy](first-policy.md) — create a policy and watch it being enforced.
- [Run Ranger with Docker](docker.md) — every compose file, port and option.
- [Building from source](../dev/build.md) — Maven build, profiles and artifacts.
