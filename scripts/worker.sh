#!/usr/bin/env bash
# Run the ingestion worker beside a backend started with NP_INGESTION=worker.
# Shares NP_DATA_DIR, the NP_S3_* and the NP_DATABASE_* variables with the
# backend (the queue is `ingestion_jobs` with NP_DATABASE_URL, `<data>/queue`
# without); polls every NP_WORKER_POLL seconds (default 2). Several workers may
# run at once; a claim lapses after a 10-minute lease. `scripts/worker.sh --once`
# drains and exits.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec sbt -batch "ingestion/run $*"
