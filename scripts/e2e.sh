#!/usr/bin/env bash
# Stage 1 end-to-end proof: build the frontend, start the backend with a fresh
# data directory, `npub push` the reference bundle, open the returned project
# in Chromium, and assert two overlays render. Usage: scripts/e2e.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${NP_PORT:-8090}"
DATA="$(mktemp -d "${TMPDIR:-/tmp}/np-e2e.XXXXXX")"
export NP_OWNER_EMAIL="owner@example.org" NP_OWNER_PASSWORD="owner-dev-password"
export NPUB_CONFIG_DIR="$DATA/npub-config"
unset NP_TOKEN
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
  NP_OWNER_EMAIL="$NP_OWNER_EMAIL" NP_OWNER_PASSWORD="$NP_OWNER_PASSWORD" \
  java -cp "$(cat "$DATA/cp.txt")" neuropublish.backend.Main > "$DATA/backend.log" 2>&1 &
BACKEND_PID=$!
trap 'kill $BACKEND_PID 2>/dev/null || true; [ -n "${NP_KEEP_DATA:-}" ] || rm -rf "$DATA"' EXIT
for i in $(seq 1 60); do
  curl -fs "http://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -fs "http://127.0.0.1:$PORT/api/v1/health" >/dev/null || { echo "backend did not start"; cat "$DATA/backend.log"; exit 1; }

echo "== npub login (device flow, approved from a separate browser session)"
CLI="java -cp $(cat "$DATA/cli-cp.txt") neuropublish.npub.Main"
$CLI login --server "http://127.0.0.1:$PORT" > "$DATA/login.log" 2>&1 &
LOGIN_PID=$!
for i in $(seq 1 30); do grep -q "^Code" "$DATA/login.log" 2>/dev/null && break; sleep 1; done
CODE=$(grep "^Code" "$DATA/login.log" | awk '{print $2}')
[ -n "$CODE" ] || { echo "no device code printed"; cat "$DATA/login.log"; exit 1; }
# the "other browser session": sign in with the local provider and approve the code
curl -fs -c "$DATA/cookies.txt" -H 'content-type: application/json' \
  -d "{\"email\":\"$NP_OWNER_EMAIL\",\"password\":\"$NP_OWNER_PASSWORD\"}" "http://127.0.0.1:$PORT/api/v1/auth/login" >/dev/null
curl -fs -b "$DATA/cookies.txt" -H 'content-type: application/json' -d "{\"userCode\":\"$CODE\"}" \
  "http://127.0.0.1:$PORT/api/v1/auth/device/approve"
wait $LOGIN_PID
grep -q "Signed in as $NP_OWNER_EMAIL" "$DATA/login.log" || { cat "$DATA/login.log"; exit 1; }

echo "== project-scoped credential cannot cross projects"
SECRET=$($CLI credential create --server "http://127.0.0.1:$PORT" --project rotman/sherlock --name e2e | grep -oE '[A-Za-z0-9_-]{32,}' | head -1)
[ -n "$SECRET" ] || { echo "no credential secret"; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $SECRET" -H 'content-type: application/json' \
  -d '{"manifestDigest":"sha256:'$(printf '0%.0s' $(seq 1 64))'","manifestSize":1,"parent":null,"assets":[]}' \
  "http://127.0.0.1:$PORT/api/v1/workspaces/rotman/projects/other/upload-sessions")
[ "$code" = "403" ] || { echo "credential crossed projects (expected 403, got HTTP $code)"; exit 1; }

echo "== npub push"
$CLI push modules/conformance/fixtures/reference \
  --server "http://127.0.0.1:$PORT" --project rotman/sherlock --message "e2e" | tee "$DATA/push.log"
grep -q "^view " "$DATA/push.log"

echo "== second push with a stale parent must be rejected"
if $CLI push modules/conformance/fixtures/reference \
  --server "http://127.0.0.1:$PORT" --project rotman/sherlock > "$DATA/push2.log" 2>&1; then
  echo "stale push unexpectedly succeeded"; cat "$DATA/push2.log"; exit 1
fi
grep -q "current head is" "$DATA/push2.log"

echo "== digest equals sha256(manifest.json)"
grep "^digest " "$DATA/push.log" | grep -q "$(shasum -a 256 modules/conformance/fixtures/reference/manifest.json | cut -d' ' -f1)"

echo "== browser"
(cd modules/frontend && NP_BASE_URL="http://127.0.0.1:$PORT" npx playwright test -c playwright.e2e.config.mjs)

echo "== julia producer publishes a second revision with no Neuropublish code (ADR 0001 gate)"
command -v julia >/dev/null || { echo "julia is required on PATH for the neutrality proof"; exit 1; }
HEAD=$(curl -fs -H "Authorization: Bearer $SECRET" "http://127.0.0.1:$PORT/api/v1/workspaces/rotman/projects/sherlock" \
  | grep -oE '"head":"[^"]+"' | cut -d'"' -f4)
[ -n "$HEAD" ] || { echo "project has no head after the first push"; exit 1; }
julia modules/conformance/julia/producer.jl --out "$DATA/julia-bundle" \
  --server "http://127.0.0.1:$PORT" --project rotman/sherlock --token "$SECRET" \
  --parent "$HEAD" --message "julia e2e" | tee "$DATA/julia.log"
grep -q "^revision " "$DATA/julia.log"
grep "^server-digest " "$DATA/julia.log" | grep -q "$(shasum -a 256 "$DATA/julia-bundle/manifest.json" | cut -d' ' -f1)"
! grep "^rendition " "$DATA/julia.log" | grep -qv " ready$"

echo "== e2e ok"
