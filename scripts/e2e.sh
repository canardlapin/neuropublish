#!/usr/bin/env bash
# Stage 1 end-to-end proof: build the frontend, start the backend with a fresh
# data directory, `npub push` the reference bundle, open the returned project
# in Chromium, and assert two overlays render. Usage: scripts/e2e.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${NP_PORT:-8090}"
DATA="$(mktemp -d "${TMPDIR:-/tmp}/np-e2e.XXXXXX")"
export NP_TOKEN="e2e-token"
cd "$ROOT"

echo "== building frontend"
(cd modules/frontend && npm run build --silent >/dev/null)

echo "== staging backend classpath"
sbt -batch "export backend/Runtime/fullClasspath" "export publisherCli/Runtime/fullClasspath" \
  | sed 's/\x1b\[[0-9;]*m//g' | grep -v '^\[' | grep -E '(^|:)/.*\.jar' > "$DATA/cps.txt"
sed -n 1p "$DATA/cps.txt" > "$DATA/cp.txt"
sed -n 2p "$DATA/cps.txt" > "$DATA/cli-cp.txt"
test -s "$DATA/cp.txt" && test -s "$DATA/cli-cp.txt"

if curl -fs "http://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1; then
  echo "port $PORT already serving something; set NP_PORT to a free port"; exit 1
fi
echo "== starting backend on :$PORT (data $DATA)"
NP_DATA_DIR="$DATA/data" NP_PORT="$PORT" NP_STATIC_DIR="$ROOT/modules/frontend/dist" \
  java -cp "$(cat "$DATA/cp.txt")" neuropublish.backend.Main > "$DATA/backend.log" 2>&1 &
BACKEND_PID=$!
trap 'kill $BACKEND_PID 2>/dev/null || true; [ -n "${NP_KEEP_DATA:-}" ] || rm -rf "$DATA"' EXIT
for i in $(seq 1 60); do
  curl -fs "http://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -fs "http://127.0.0.1:$PORT/api/v1/health" >/dev/null || { echo "backend did not start"; cat "$DATA/backend.log"; exit 1; }

echo "== npub push"
java -cp "$(cat "$DATA/cli-cp.txt")" neuropublish.npub.Main push modules/conformance/fixtures/reference \
  --server "http://127.0.0.1:$PORT" --project rotman/sherlock --message "e2e" | tee "$DATA/push.log"
grep -q "^view " "$DATA/push.log"

echo "== second push with a stale parent must be rejected"
if java -cp "$(cat "$DATA/cli-cp.txt")" neuropublish.npub.Main push modules/conformance/fixtures/reference \
  --server "http://127.0.0.1:$PORT" --project rotman/sherlock > "$DATA/push2.log" 2>&1; then
  echo "stale push unexpectedly succeeded"; cat "$DATA/push2.log"; exit 1
fi
grep -q "current head is" "$DATA/push2.log"

echo "== digest equals sha256(manifest.json)"
grep "^digest " "$DATA/push.log" | grep -q "$(shasum -a 256 modules/conformance/fixtures/reference/manifest.json | cut -d' ' -f1)"

echo "== browser"
(cd modules/frontend && NP_BASE_URL="http://127.0.0.1:$PORT" npx playwright test -c playwright.e2e.config.mjs)
echo "== e2e ok"
