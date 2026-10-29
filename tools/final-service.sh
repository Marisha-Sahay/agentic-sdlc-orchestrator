#!/usr/bin/env bash
# Materialises the final service: the v1 baseline (url-shortener/) plus the patch the brownfield
# run validated and released (docs/evidence/brownfield/release.patch). There is deliberately no
# second copy of the service in the repository; this is the only way v2 is produced.
#   tools/final-service.sh [target-dir]          default: target/final-service
#   cd target/final-service && mvn verify         full build with integration tests (Docker)
#   docker compose up -d && mvn spring-boot:run   run it on :8080
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/target/final-service}"
PATCH="$ROOT/docs/evidence/brownfield/release.patch"
[[ -f "$PATCH" ]] || { echo "missing $PATCH" >&2; exit 1; }
rm -rf "$OUT" && mkdir -p "$OUT"
(cd "$ROOT" && git ls-files -z url-shortener | while IFS= read -r -d '' f; do
  mkdir -p "$OUT/$(dirname "${f#url-shortener/}")" && cp "$f" "$OUT/${f#url-shortener/}"
done)
git -C "$OUT" init -q && git -C "$OUT" apply --whitespace=nowarn "$PATCH"
echo "Final service written to $OUT ($(grep -c '^diff --git' "$PATCH") files changed from v1)"
