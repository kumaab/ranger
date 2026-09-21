#!/usr/bin/env bash
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

# Lints every Dockerfile in the repository with hadolint, using the rule set in
# .hadolint.yaml at the repository root.
#
# Usage:
#   dev-support/checks/hadolint.sh                 # lint all Dockerfiles
#   dev-support/checks/hadolint.sh <file> [<file>] # lint only the given Dockerfiles
#
# Environment:
#   HADOLINT_VERSION  hadolint release to use          (default: v2.15.1)
#   HADOLINT_FORMAT   hadolint output format           (default: tty)
#   HADOLINT_BIN      path to a local hadolint binary; when unset the pinned
#                     hadolint container image is used instead

set -euo pipefail

HADOLINT_VERSION="${HADOLINT_VERSION:-v2.15.1}"
HADOLINT_FORMAT="${HADOLINT_FORMAT:-tty}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd)"
cd "$REPO_ROOT"

# Collect the files to lint: either the ones passed on the command line, or every
# tracked Dockerfile. git ls-files keeps build output and untracked scratch dirs out.
declare -a dockerfiles
if [ "$#" -gt 0 ]; then
    dockerfiles=("$@")
else
    while IFS= read -r file; do
        dockerfiles+=("$file")
    done < <(git ls-files -- '*Dockerfile' '*Dockerfile.*' '*/Dockerfile' | sort)
fi

if [ "${#dockerfiles[@]}" -eq 0 ]; then
    echo "hadolint: no Dockerfiles found, nothing to lint"
    exit 0
fi

echo "hadolint ${HADOLINT_VERSION}: linting ${#dockerfiles[@]} Dockerfile(s)"

if [ -n "${HADOLINT_BIN:-}" ]; then
    hadolint() { "$HADOLINT_BIN" "$@"; }
elif command -v hadolint >/dev/null 2>&1; then
    : # use the hadolint already on PATH
elif command -v docker >/dev/null 2>&1; then
    # --user keeps hadolint from tripping over the bind-mounted files' ownership.
    hadolint() {
        docker run --rm \
            --user "$(id -u):$(id -g)" \
            --volume "$REPO_ROOT:/repo:ro" \
            --workdir /repo \
            "hadolint/hadolint:${HADOLINT_VERSION}" \
            hadolint "$@"
    }
else
    echo "hadolint: neither a hadolint binary nor docker is available." >&2
    echo "Install hadolint (https://github.com/hadolint/hadolint#install) or start docker." >&2
    exit 1
fi

# hadolint exits non-zero as soon as any file has a finding at or above the
# configured failure-threshold; it still reports findings for every file first.
hadolint --config .hadolint.yaml --format "$HADOLINT_FORMAT" "${dockerfiles[@]}" && status=0 || status=$?

if [ "$status" -eq 0 ]; then
    echo "hadolint: no findings at or above the configured failure threshold"
else
    echo "hadolint: found issues; fix them or suppress a rule with an inline" >&2
    echo "'# hadolint ignore=<rule>' comment plus a note explaining why." >&2
fi

exit "$status"
