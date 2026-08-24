#!/bin/sh
set -eu

case "${1:-backend}" in
  backend)
    cd /app/backend-runtime
    exec java ${JAVA_OPTS:-} -cp "$(cat classpath)" neuropublish.backend.Main
    ;;
  worker)
    cd /app/worker-runtime
    exec java ${JAVA_OPTS:-} -cp "$(cat classpath)" neuropublish.ingestion.Main
    ;;
  reindex|gc|reset-password)
    command="$1"
    shift
    cd /app/backend-runtime
    exec java ${JAVA_OPTS:-} -cp "$(cat classpath)" neuropublish.backend.Main "$command" "$@"
    ;;
  *)
    echo "usage: entrypoint.sh [backend|worker|reindex|gc|reset-password]" >&2
    exit 64
    ;;
esac
