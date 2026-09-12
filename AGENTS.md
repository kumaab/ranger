<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Agent Guide for ranger

This file is read by automated agents (security scanners, code
analyzers, AI assistants) operating on this repository.

## Security

Security model: [SECURITY.md](./SECURITY.md)

Agents that scan this repository should consult `SECURITY.md` and the
threat model it links before reporting issues.

## Contributing code

Before writing or modifying code in this repository, read
[.cursor/rules/ranger-pr-review.mdc](./.cursor/rules/ranger-pr-review.mdc).

It covers what the build enforces (Checkstyle, sortpom and the enforcer
plugin all fail `mvn verify` on violation), the Java and configuration
conventions this codebase follows, and the recurring review feedback that
Ranger committers give on pull requests — with an anti-pattern list to
check your own diff against before opening a PR.

Supporting rules:

- [.cursor/rules/ranger-checkstyle.mdc](./.cursor/rules/ranger-checkstyle.mdc) — Checkstyle detail
- [.cursor/rules/ranger-python.mdc](./.cursor/rules/ranger-python.mdc) — Python client conventions
- [.cursor/rules/kumaab-fork-preferences.mdc](./.cursor/rules/kumaab-fork-preferences.mdc) —
  personal preferences for this fork, layered on the rules above. Fork-only: not part of
  upstream `apache/ranger`, and should be kept out of any PR raised against it.
