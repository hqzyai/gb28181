#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$(readlink -f -- "$0")")"
umask 077
mkdir -p backups
exec 9>backups/.lock
flock -n 9 || exit 0
stamp=$(date -u +%Y%m%dT%H%M%SZ)
temporary="backups/.${stamp}.tmp"
final="backups/${stamp}"
mkdir "$temporary"
trap 'rm -rf -- "$temporary"' EXIT
docker compose exec -T mysql sh -ec 'export MYSQL_PWD="$(cat /run/secrets/mysql_root_password)"; exec mysqldump -uroot --single-transaction --routines --events --triggers --no-tablespaces wvp' | gzip > "$temporary/wvp.sql.gz"
tar -czf "$temporary/config.tar.gz" .env compose*.yml application.yml config secrets data/wvp-config
# The durable YOLO task definition is stored beside the AI screenshots.
tar -czf "$temporary/ai-history.tar.gz" data/ai-history
gzip -t "$temporary/wvp.sql.gz"
mv "$temporary" "$final"
trap - EXIT
# Delete only complete backup directories created by this script, keeping 14 days.
find backups -mindepth 1 -maxdepth 1 -type d -name '20*T*Z' -mtime +14 -exec rm -rf -- {} +
echo "Backup complete: $final"
