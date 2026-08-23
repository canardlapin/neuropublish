#!/usr/bin/env bash
# End-to-end proof: build the frontend, start the backend on a fresh data
# directory, sign in with `npub login` (device flow approved from a second
# session), mint a project credential and prove it cannot cross projects,
# `npub push` the reference bundle, reject a stale-parent push, wait for
# ingestion, drive the browser scenarios in Chromium, and publish a second
# revision from the Julia producer (ADR 0001).
#
#   scripts/e2e.sh                  local mode: local-fs stores, inline ingestion
#   NP_E2E_MODE=full scripts/e2e.sh PostgreSQL + MinIO containers (Docker), presigned
#                                   transfers, and the separate ingestion worker
#   NP_PORT=8090                    backend port (default 8090; must be free)
#   NP_KEEP_DATA=1                  keep the temp data dir and logs on exit
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

MODE="${NP_E2E_MODE:-local}"   # local | full (PostgreSQL + MinIO + separate ingestion worker)
if [ "$MODE" = "full" ]; then
  command -v docker >/dev/null && docker info >/dev/null 2>&1 || { echo "full mode needs Docker"; exit 1; }
  echo "== starting PostgreSQL and MinIO containers"
  freeport() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'; }
  PG_PORT=$(freeport); MINIO_PORT=$(freeport)
  PG_ID=$(docker run -d --rm -e POSTGRES_USER=np -e POSTGRES_PASSWORD=np -e POSTGRES_DB=neuropublish -p 127.0.0.1:$PG_PORT:5432 postgres:16-alpine)
  MINIO_ID=$(docker run -d --rm -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio-secret -p 127.0.0.1:$MINIO_PORT:9000 minio/minio server /data)
  trap 'kill ${BACKEND_PID:-} ${WORKER_PID:-} 2>/dev/null || true; docker stop "$PG_ID" "$MINIO_ID" >/dev/null 2>&1 || true; [ -n "${NP_KEEP_DATA:-}" ] || rm -rf "$DATA"' EXIT
  for i in $(seq 1 60); do docker exec "$PG_ID" pg_isready -U np >/dev/null 2>&1 && curl -fs http://127.0.0.1:$MINIO_PORT/minio/health/live >/dev/null 2>&1 && break; sleep 1; done
  export NP_DATABASE_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/neuropublish" NP_DATABASE_USER=np NP_DATABASE_PASSWORD=np
  export NP_S3_BUCKET=neuropublish NP_S3_ENDPOINT=http://127.0.0.1:$MINIO_PORT NP_S3_REGION=us-east-1 \
         NP_S3_ACCESS_KEY=minio NP_S3_SECRET_KEY=minio-secret NP_S3_PATH_STYLE=true NP_INGESTION=worker NP_WORKER_POLL=1
fi

echo "== staging backend classpath"
sbt -batch "export backend/Runtime/fullClasspath" "export publisherCli/Runtime/fullClasspath" "export ingestion/Runtime/fullClasspath" \
  | sed 's/\x1b\[[0-9;]*m//g' | grep -v '^\[' | grep -E '(^|:)/.*\.jar' > "$DATA/cps.txt"
sed -n 1p "$DATA/cps.txt" > "$DATA/cp.txt"
sed -n 2p "$DATA/cps.txt" > "$DATA/cli-cp.txt"
sed -n 3p "$DATA/cps.txt" > "$DATA/worker-cp.txt"
test -s "$DATA/cp.txt" && test -s "$DATA/cli-cp.txt" && test -s "$DATA/worker-cp.txt"

if curl -fs "http://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1; then
  echo "port $PORT already serving something; set NP_PORT to a free port"; exit 1
fi
echo "== starting backend on :$PORT (data $DATA)"
NP_DATA_DIR="$DATA/data" NP_PORT="$PORT" NP_STATIC_DIR="$ROOT/modules/frontend/dist" \
  NP_OWNER_EMAIL="$NP_OWNER_EMAIL" NP_OWNER_PASSWORD="$NP_OWNER_PASSWORD" \
  java -cp "$(cat "$DATA/cp.txt")" neuropublish.backend.Main > "$DATA/backend.log" 2>&1 &
BACKEND_PID=$!
if [ "$MODE" = "full" ]; then
  echo "== starting ingestion worker"
  NP_DATA_DIR="$DATA/data" java -cp "$(cat "$DATA/worker-cp.txt")" neuropublish.ingestion.Main > "$DATA/worker.log" 2>&1 &
  WORKER_PID=$!
else
  trap 'kill $BACKEND_PID 2>/dev/null || true; [ -n "${NP_KEEP_DATA:-}" ] || rm -rf "$DATA"' EXIT
fi
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

echo "== re-pushing the identical bundle is a no-op (already published as the head)"
$CLI push modules/conformance/fixtures/reference \
  --server "http://127.0.0.1:$PORT" --project rotman/sherlock > "$DATA/push-same.log" 2>&1
grep -q "^unchanged " "$DATA/push-same.log" || { cat "$DATA/push-same.log"; exit 1; }

echo "== a changed bundle pushed with a stale parent must be rejected"
rm -rf "$DATA/bundle2"; cp -R modules/conformance/fixtures/reference "$DATA/bundle2"
python3 - "$DATA/bundle2/manifest.json" <<'PY'
import json, sys
p = sys.argv[1]; m = json.load(open(p)); m["title"] = m["title"] + " (stale-parent probe)"
json.dump(m, open(p, "w"), indent=2)
PY
if $CLI push "$DATA/bundle2" \
  --server "http://127.0.0.1:$PORT" --project rotman/sherlock > "$DATA/push2.log" 2>&1; then
  echo "stale push unexpectedly succeeded"; cat "$DATA/push2.log"; exit 1
fi
grep -q "current head is" "$DATA/push2.log" || { cat "$DATA/push2.log"; exit 1; }

echo "== wait for ingestion (worker mode reports pending until renditions exist)"
REV=$(grep "^revision " "$DATA/push.log" | grep -oE '/r/[a-z0-9]+' | head -1 | cut -c4-)
for i in $(seq 1 90); do
  st=$(curl -fs -H "Authorization: Bearer $SECRET" "http://127.0.0.1:$PORT/api/v1/revisions/$REV" | python3 -c 'import sys,json; d=json.load(sys.stdin); print((d.get("ingestion") or {}).get("status","ready") if d.get("renditions") and all(r["status"]=="ready" for r in d["renditions"]) else (d.get("ingestion") or {}).get("status","pending"))' 2>/dev/null || echo pending)
  [ "$st" = "ready" ] && break
  [ "$st" = "failed" ] && { echo "ingestion failed"; cat "$DATA/worker.log" 2>/dev/null | tail -20; exit 1; }
  sleep 1
done
[ "$st" = "ready" ] || { echo "renditions not ready after 90s (status $st)"; tail -20 "$DATA/worker.log" 2>/dev/null; exit 1; }

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
