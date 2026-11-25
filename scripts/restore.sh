#!/usr/bin/env sh
# Restores a backup made by scripts/backup.sh into the docker-compose.full.yml stack.
#   ./scripts/restore.sh backups/changeimpact-<timestamp>.dump [--yes]     (--yes may also come first)
# REPLACES the current database. Use the same TOKEN_ENCRYPTION_KEY (.env) as when the backup was taken.
# The backend is stopped during the restore and started again afterwards; Flyway then applies any newer
# migrations, so a backup from an older version can be restored into a newer one (not the other way round).
set -eu
cd "$(dirname "$0")/.."
COMPOSE="${COMPOSE:-docker compose -f docker-compose.full.yml}"
file=""; yes=""
for arg in "$@"; do
  case "$arg" in
    --yes) yes=1 ;;
    *) [ -z "$file" ] && file="$arg" || { echo "usage: $0 <backup.dump> [--yes]" >&2; exit 1; } ;;
  esac
done
[ -n "$file" ] && [ -f "$file" ] || { echo "usage: $0 <backup.dump> [--yes]" >&2; exit 1; }
if [ -f "$file.sha256" ] && command -v sha256sum >/dev/null 2>&1; then
  (cd "$(dirname "$file")" && sha256sum -c "$(basename "$file").sha256") || { echo "checksum mismatch" >&2; exit 1; }
fi
if [ -z "$yes" ]; then
  printf 'This replaces the current ChangeImpact AI database with %s. Type "restore" to continue: ' "$file"
  read -r answer
  [ "$answer" = "restore" ] || { echo "aborted"; exit 1; }
fi

$COMPOSE up -d postgres
$COMPOSE stop frontend backend >/dev/null 2>&1 || true
# wait for postgres
i=0; until $COMPOSE exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null 2>&1; do
  i=$((i + 1)); [ $i -gt 60 ] && { echo "postgres not ready" >&2; exit 1; }; sleep 2; done

$COMPOSE exec -T postgres sh -c '
  set -e
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -q \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"'"'$POSTGRES_DB'"'"' AND pid <> pg_backend_pid();" \
    -c "DROP DATABASE IF EXISTS \"$POSTGRES_DB\";" \
    -c "CREATE DATABASE \"$POSTGRES_DB\" OWNER \"$POSTGRES_USER\";"
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges --exit-on-error' < "$file"

$COMPOSE up -d backend frontend
echo "Restored $file. The backend is starting (docker compose -f docker-compose.full.yml logs -f backend)."
