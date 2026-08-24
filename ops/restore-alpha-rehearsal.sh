#!/bin/sh
set -eu
export LC_ALL=C LANG=C

if [ "$#" -ne 3 ]; then
  echo "usage: restore-alpha-rehearsal.sh /absolute/path/restore.env /absolute/backup-directory isolated-compose-project" >&2
  exit 64
fi

env_file=$1
backup_dir=$2
project=$3
compose_file=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/compose.alpha.yml

case "$env_file" in
  /*) ;;
  *) echo "the environment file path must be absolute" >&2; exit 64 ;;
esac
case "$backup_dir" in
  /*) ;;
  *) echo "the backup directory path must be absolute" >&2; exit 64 ;;
esac
case "$project" in
  neuropublish-restore-*) ;;
  *) echo "the isolated project name must begin neuropublish-restore-" >&2; exit 64 ;;
esac
if [ ! -f "$env_file" ] || [ ! -f "$backup_dir/postgres.dump" ] || \
   [ ! -f "$backup_dir/checksums.sha256" ]; then
  echo "restore inputs are incomplete" >&2
  exit 66
fi

(
  cd "$backup_dir"
  shasum -a 256 -c checksums.sha256
)

docker compose -p "$project" --env-file "$env_file" -f "$compose_file" \
  up -d --wait postgres minio minio-config

docker compose -p "$project" --env-file "$env_file" -f "$compose_file" exec -T postgres \
  pg_restore --clean --if-exists --no-owner --username=neuropublish --dbname=neuropublish \
  < "$backup_dir/postgres.dump"

docker compose -p "$project" --env-file "$env_file" -f "$compose_file" run --rm --no-deps \
  --entrypoint /bin/sh -v "$backup_dir/objects:/backup:ro" minio-config -c \
  'mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc mirror --overwrite /backup local/neuropublish'

docker compose -p "$project" --env-file "$env_file" -f "$compose_file" \
  up -d backend worker
docker compose -p "$project" --env-file "$env_file" -f "$compose_file" \
  run --rm --no-deps backend reindex

echo "restore rehearsal is running as $project; inspect it, record the result, then remove it with:"
echo "docker compose -p $project --env-file $env_file -f $compose_file down --volumes"
