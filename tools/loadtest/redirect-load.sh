#!/usr/bin/env bash
# Repeatable redirect load test with ApacheBench (ab ships with macOS; apt install apache2-utils).
# Measures one cached short link on a running service: the hot path the ambiguous scenario's
# 2,000 redirects/s with p99 < 50 ms target is about. Client and server share the machine, so the
# result is a lower bound on what the service does, not a capacity claim for production.
#   BASE=http://localhost:8080 REQUESTS=100000 CONCURRENCY=50 tools/loadtest/redirect-load.sh
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
REQUESTS="${REQUESTS:-100000}"
CONCURRENCY="${CONCURRENCY:-50}"
ALIAS="load-$(date +%s)"
curl -fsS -X POST "$BASE/api/v1/links" -H 'Content-Type: application/json' \
  -d "{\"url\": \"https://example.com/landing\", \"customAlias\": \"$ALIAS\"}" > /dev/null
ab -q -n 2000 -c "$CONCURRENCY" -k "$BASE/$ALIAS" > /dev/null   # warm-up: JIT and cache
echo "== $REQUESTS redirects, concurrency $CONCURRENCY, keep-alive, $(uname -sm), $(date -u +%FT%TZ)"
ab -n "$REQUESTS" -c "$CONCURRENCY" -k "$BASE/$ALIAS" \
  | grep -E 'Complete requests|Failed requests|Non-2xx|Requests per second|^ +(50|90|99|100)%'
sleep 2
echo "== clicks recorded for the run (best effort; compare with the request count):"
curl -fsS "$BASE/api/v1/links/$ALIAS/stats?days=1"; echo
curl -fsS "$BASE/actuator/metrics/shortener.clicks.dropped" | grep -o '"value":[0-9.E]*' || true
