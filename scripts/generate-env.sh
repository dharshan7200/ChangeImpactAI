#!/usr/bin/env sh
# Creates .env from .env.example with freshly generated secrets.
#   ./scripts/generate-env.sh [--force] [public-url]
# Refuses to overwrite an existing .env unless --force is given (that would orphan the database password and
# make stored GitHub tokens unreadable).
set -eu
cd "$(dirname "$0")/.."

force=0
url=""
for a in "$@"; do
  case "$a" in
    --force) force=1 ;;
    -h|--help) sed -n '2,5p' "$0"; exit 0 ;;
    *) url="$a" ;;
  esac
done

if [ -f .env ] && [ "$force" -ne 1 ]; then
  echo ".env already exists — not overwriting (use --force to replace it, after backing up its secrets)." >&2
  exit 1
fi
command -v openssl >/dev/null 2>&1 || { echo "openssl is required" >&2; exit 1; }

rand() { openssl rand -base64 "$1" | tr -d '\n'; }
# URL-safe variant for values that end up in connection strings
rand_safe() { openssl rand -base64 "$1" | tr -d '\n=' | tr '+/' '-_'; }

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
sed \
  -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(rand_safe 24)|" \
  -e "s|^RABBITMQ_PASSWORD=.*|RABBITMQ_PASSWORD=$(rand_safe 24)|" \
  -e "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$(rand_safe 24)|" \
  -e "s|^JWT_SECRET=.*|JWT_SECRET=$(rand 48)|" \
  -e "s|^TOKEN_ENCRYPTION_KEY=.*|TOKEN_ENCRYPTION_KEY=$(rand 32)|" \
  -e "s|^METRICS_SCRAPE_TOKEN=.*|METRICS_SCRAPE_TOKEN=$(rand_safe 32)|" \
  -e "s|^GRAFANA_ADMIN_PASSWORD=.*|GRAFANA_ADMIN_PASSWORD=$(rand_safe 18)|" \
  .env.example > "$tmp"
if [ -n "$url" ]; then
  sed -i.bak -e "s|^FRONTEND_URL=.*|FRONTEND_URL=$url|" "$tmp" && rm -f "$tmp.bak"
fi
umask 077
cp "$tmp" .env
chmod 600 .env
echo "Wrote .env with new random secrets (mode 600)."
echo "Review FRONTEND_URL and ADMIN_EMAILS, then: docker compose -f docker-compose.full.yml up -d --build"
