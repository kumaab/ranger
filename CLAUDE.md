<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Instructions for Claude

Read these before writing or modifying code in this repository, in order:

1. [.cursor/rules/ranger-pr-review.mdc](./.cursor/rules/ranger-pr-review.mdc) — what the build
   enforces, Java and configuration conventions, recurring Ranger committer review feedback, and
   an anti-pattern checklist to run your own diff against.
2. [.cursor/rules/ranger-checkstyle.mdc](./.cursor/rules/ranger-checkstyle.mdc) — Checkstyle detail.
3. [.cursor/rules/kumaab-fork-preferences.mdc](./.cursor/rules/kumaab-fork-preferences.mdc) —
   personal preferences for this fork. Where it conflicts with the two above, it wins; everything
   it does not mention is governed by them.

Apply them to every change, not only when asked to review.

See [AGENTS.md](./AGENTS.md) for the security model and the wider agent guide.

## This is a fork

This is `kumaab/ranger`, a fork of `apache/ranger` synced periodically from upstream.
`CLAUDE.md` and `.cursor/rules/kumaab-fork-preferences.mdc` are fork-only and are **not** part of
upstream. When preparing a PR against `apache/ranger`, keep both files out of the diff.
