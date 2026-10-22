#!/usr/bin/env bash
# Prints the README.personal.md section titled exactly "## <versionName>"; fails when it is missing or empty.
set -euo pipefail
name="${1:?versionName required}"
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
notes="$(awk -v header="## $name" '
  $0 == header { found = 1; next }
  found && /^## / { exit }
  found { print }
' "$root/README.personal.md" | sed -e '/./,$!d')"
if [ -z "${notes//[[:space:]]/}" ]; then
  echo "README.personal.md has no non-empty \"## $name\" section; add release notes before bumping the version." >&2
  exit 1
fi
printf '%s\n' "$notes"
