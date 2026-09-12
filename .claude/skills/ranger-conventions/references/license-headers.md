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

# License headers by file type

Apache RAT (`apache-rat-plugin` 0.16.1) checks every file at `verify`. Use the exact text below; the repo has minor wording variants, and any of them pass,
but new files should use the form shown for their type.

## `.java`, `.js`, `.jsx`

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
```

## `.xml`, `.md`, `.mdc`, `.html`

```xml
<!--
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
```

MkDocs pages put YAML front matter (`--- title: "..." ---`) first, then this comment with a leading ` - ` on each line. Skill files (`.claude/skills/**/SKILL.md`)
put the YAML front matter first and the comment immediately after it.

## `.sh`, `.py`, `.properties`, `.cfg`, `.yml`, `.yaml`, `.env`

```sh
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
```

Shebang first (`#!/bin/bash`, `#!/usr/bin/env python`), then the header.

## `.sql`

```sql
-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
```

## `.json`

No comments possible, so no header. RAT excludes `**/main/resources/**/*.json`, `**/test/resources/**/*.json`, `**/samples/**/*.json`, `**/testdata/*.json`,
`**/importPolicy/*.json`, `**/importRole/*.json`, `**/package.json`, `**/package-lock.json`, `**/babel.config.json`. A JSON file outside those paths will fail RAT;
add an exclude in the root `pom.xml` `apache-rat-plugin` block if it is unavoidable.

## Other RAT excludes worth knowing

`dev-support/**`, `**/target/**`, `**/node_modules/**`, `**/react-webapp/src/images/**`, `**/react-webapp/.prettierrc`, `**/robots.txt`, `**/MANIFEST.MF`,
`**/*.iml`, `*.patch`, `**/__init__.py`, `**/requirements.txt`, `mkdocs/docs/assets/**`. Files in `dev-support/` still carry headers by convention.
