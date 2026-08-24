#!/bin/sh
set -eu
export LC_ALL=C LANG=C

if [ "$#" -ne 2 ]; then
  echo "usage: backup-alpha.sh /absolute/path/alpha.env /absolute/new/backup-directory" >&2
  exit 64
fi

env_file=$1
backup_dir=$2
compose_file=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/compose.alpha.yml

case "$env_file" in
  /*) ;;
  *) echo "the environment file path must be absolute" >&2; exit 64 ;;
esac
case "$backup_dir" in
  /*) ;;
  *) echo "the backup directory path must be absolute" >&2; exit 64 ;;
esac
if [ ! -f "$env_file" ]; then
  echo "no environment file at $env_file" >&2
  exit 66
fi
if [ -e "$backup_dir" ]; then
  echo "refusing to overwrite existing backup path $backup_dir" >&2
  exit 73
fi

mkdir -p "$backup_dir/objects"

docker compose --env-file "$env_file" -f "$compose_file" exec -T postgres \
  pg_dump --format=custom --no-owner --username=neuropublish --dbname=neuropublish \
  > "$backup_dir/postgres.dump"

docker compose --env-file "$env_file" -f "$compose_file" run --rm --no-deps \
  --entrypoint /bin/sh -v "$backup_dir/objects:/backup" minio-config -c \
  'mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc mirror --overwrite local/neuropublish /backup'

(
  cd "$backup_dir"
  find objects -type f -print | LC_ALL=C sort | while IFS= read -r file; do
    shasum -a 256 "$file"
  done
  shasum -a 256 postgres.dump
) > "$backup_dir/checksums.sha256"

printf '%s\n' \
  "created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  "compose_file=$compose_file" \
  "database=postgres.dump" \
  "objects=objects/" \
  "checksums=checksums.sha256" \
  > "$backup_dir/receipt.txt"

echo "backup written to $backup_dir"
