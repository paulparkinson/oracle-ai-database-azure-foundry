#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
toolkit_commit=e8e067400ff93d275cb3b6c005c835f87d55e835
toolkit_dir="$PWD/.deps/oracle-ai-database-fullstack-toolkit"
if [[ ! -d "$toolkit_dir/.git" ]]; then
  git clone --no-checkout https://github.com/paulparkinson/oracle-ai-database-fullstack-toolkit.git "$toolkit_dir"
  git -C "$toolkit_dir" checkout --detach "$toolkit_commit"
fi
if [[ "$(git -C "$toolkit_dir" rev-parse HEAD)" != "$toolkit_commit" ]] || [[ -n "$(git -C "$toolkit_dir" status --porcelain)" ]]; then
  echo 'The cached toolkit differs from the pinned clean commit. Preserve any edits and use a fresh clone.' >&2
  exit 1
fi
mvn -B -f "$toolkit_dir/pom.xml" -pl core -am install
mvn -B verify "$@"
