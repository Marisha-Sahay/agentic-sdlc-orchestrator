#!/usr/bin/env bash
# Materialises the final service: the v1 baseline (url-shortener/) plus the patch the brownfield
# run validated and released (docs/evidence/brownfield/release.patch). There is deliberately no
# second copy of the service in the repository; this is the only way v2 is produced.
#   tools/final-service.sh                        writes target/final-service (no other target)
#   cd target/final-service && mvn verify         full build with integration tests (Docker)
#   docker compose up -d && mvn spring-boot:run   run it on :8080
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/target/final-service"
PATCH="$ROOT/docs/evidence/brownfield/release.patch"
if [[ $# -gt 0 ]]; then
  echo "usage: tools/final-service.sh   (always writes $OUT)" >&2; exit 2
fi
[[ -f "$PATCH" ]] || { echo "missing $PATCH" >&2; exit 1; }
# Only this generated directory is ever deleted, and never through a symlink.
for path in "$ROOT/target" "$OUT"; do
  if [[ -L "$path" ]]; then
    echo "refusing: $path is a symlink" >&2; exit 1
  fi
done
rm -rf "$OUT" && mkdir -p "$OUT"
# The committed v1, never local edits: the final service is exactly v1 plus the released patch.
if [[ -n "$(git -C "$ROOT" status --porcelain -- url-shortener)" ]]; then
  echo "note: uncommitted changes under url-shortener/ are not included" >&2
fi
git -C "$ROOT" archive --format=tar HEAD url-shortener | tar -x -C "$OUT" --strip-components=1
git -C "$OUT" init -q && git -C "$OUT" apply --whitespace=nowarn "$PATCH"
echo "Final service written to $OUT ($(grep -c '^diff --git' "$PATCH") files changed from v1)"
