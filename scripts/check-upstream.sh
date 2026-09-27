#!/usr/bin/env bash
# Detect upstream Freebuff drift in the load-bearing host contracts.
# Fails loudly (exit 1) on tool-list or schema changes (§26, §10).
set -euo pipefail
UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/CodebuffAI/freebuff}"
PIN_FILE="$(dirname "$0")/../tools/upstream/upstream-pin.txt"
CLONE="${CLONE_DIR:-/tmp/freebuff_probe}"
CONTRACT_FILES=(
  common/src/tools/constants.ts
  common/src/tools/list.ts
  common/src/types/session-state.ts
  common/src/actions.ts
)

if [ ! -d "$CLONE/.git" ]; then
  git clone --depth 1 "$UPSTREAM_URL" "$CLONE"
else
  git -C "$CLONE" fetch --depth 1 origin && git -C "$CLONE" reset --hard origin/HEAD
fi

NEW_HEAD="$(git -C "$CLONE" rev-parse HEAD)"
OLD_HEAD="$(cat "$PIN_FILE" 2>/dev/null || echo none)"
echo "pinned=$OLD_HEAD"
echo "upstream=$NEW_HEAD"
if [ "$OLD_HEAD" = "$NEW_HEAD" ]; then
  echo "UPSTREAM_UNCHANGED"; exit 0
fi

CHANGED=0
for f in "${CONTRACT_FILES[@]}"; do
  if ! git -C "$CLONE" diff --quiet "$OLD_HEAD" "$NEW_HEAD" -- "$f" 2>/dev/null; then
    echo "DRIFT: $f"; CHANGED=1
  fi
done

if [ "$CHANGED" = 1 ]; then
  echo "CONTRACT DRIFT DETECTED — regenerate protocol-ref + UPSTREAM_COMPATIBILITY.md via scripts/sync-upstream-contracts.sh, then update adapters/tests."
  exit 1
fi
echo "upstream moved but host contracts stable; update pin to $NEW_HEAD"
echo "$NEW_HEAD" > "$PIN_FILE"
