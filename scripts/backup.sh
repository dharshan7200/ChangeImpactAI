#!/usr/bin/env sh
# Backs up the ChangeImpact AI database of the docker-compose.full.yml stack.
#   ./scripts/backup.sh [backup-dir]            (default: ./backups)
# Writes <dir>/changeimpact-<UTC timestamp>.dump (pg_dump custom format, compressed) plus a .sha256 file.
#
# What needs backing up: the PostgreSQL database (all repositories, symbols, analyses, reports, users, encrypted
# GitHub tokens) and your .env (the secrets — above all TOKEN_ENCRYPTION_KEY, without which the stored tokens in the
# dump cannot be decrypted). Store .env separately, in a secret store. Everything else is rebuildable: workspaces
# (Git clones) are re-cloned on the next index/analysis, Redis only caches, RabbitMQ only carries job messages whose
# state is in the database.
set -eu
cd "$(dirname "$0")/.."
COMPOSE="${COMPOSE:-docker compose -f docker-compose.full.yml}"
dir="${1:-backups}"
mkdir -p "$dir"
ts=$(date -u +%Y%m%dT%H%M%SZ)
out="$dir/changeimpact-$ts.dump"

# pg_dump runs inside the postgres container, so the password never leaves it.
$COMPOSE exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --compress=6 --no-owner --no-privileges' > "$out.partial"
mv "$out.partial" "$out"
# sanity check: the archive must list its table of contents
$COMPOSE exec -T postgres pg_restore --list < "$out" > /dev/null
if command -v sha256sum >/dev/null 2>&1; then (cd "$dir" && sha256sum "$(basename "$out")" > "$(basename "$out").sha256"); fi
echo "Backup written: $out ($(du -h "$out" | cut -f1))"

# optional retention: KEEP_BACKUPS=14 keeps the newest 14 dumps
if [ -n "${KEEP_BACKUPS:-}" ]; then
  ls -1t "$dir"/changeimpact-*.dump 2>/dev/null | tail -n +"$((KEEP_BACKUPS + 1))" | while read -r old; do
    rm -f "$old" "$old.sha256"; echo "Removed old backup $old"
  done
fi
