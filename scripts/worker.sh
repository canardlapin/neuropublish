#!/usr/bin/env bash
# Run the ingestion worker beside a backend started with NP_INGESTION=worker.
# Shares NP_DATA_DIR and the NP_S3_* variables with the backend; polls every
# NP_WORKER_POLL seconds (default 2). `scripts/worker.sh --once` drains and exits.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec sbt -batch "ingestion/run $*"
