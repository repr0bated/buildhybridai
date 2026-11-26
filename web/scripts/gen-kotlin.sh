#!/usr/bin/env bash
# Generates Kotlin (kotlinx.serialization) data classes for the sync documents from the shared JSON Schemas.
# Output: docs/sync/SyncTypes.kt  -> copy into the Android app under .../callguardian/sync/generated/
set -euo pipefail
cd "$(dirname "$0")/../.."
S=app/src/main/assets/schemas/sync
args=()
for f in "$S"/*.schema.json; do args+=(--src "$f"); done
npx --yes quicktype@23 --src-lang schema --lang kotlin --framework kotlinx \
  --package io.github.repr0bated.callguardian.sync.generated "${args[@]}" -o docs/sync/SyncTypes.kt
echo "wrote docs/sync/SyncTypes.kt"
