#!/usr/bin/env bash
# =============================================================
#  Guardian — daily database backup
#
#  Install as a cron job:
#    sudo install -m 755 deploy/backup.sh /usr/local/bin/guardian-backup
#    sudo crontab -e
#    # 03:15 every day
#    15 3 * * * /usr/local/bin/guardian-backup >> /var/log/guardian-backup.log 2>&1
# =============================================================
set -euo pipefail

GUARDIAN_DIR="${GUARDIAN_DIR:-/opt/guardian}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/guardian}"
KEEP_DAYS="${KEEP_DAYS:-14}"
DB_FILE="$GUARDIAN_DIR/server/data/guardian.db"

[[ -f "$DB_FILE" ]] || { echo "[backup] no database at $DB_FILE — nothing to do"; exit 0; }

mkdir -p "$BACKUP_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="$BACKUP_DIR/guardian-$STAMP.db"

# .backup is safe to run while the server is writing — unlike a plain file copy.
if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "$DB_FILE" ".backup '$TARGET'"
  gzip -9 "$TARGET"
  echo "[backup] wrote $TARGET.gz"
else
  echo "[backup] sqlite3 CLI missing — falling back to a plain copy"
  cp "$DB_FILE" "$TARGET"
  gzip -9 "$TARGET"
fi

# Prune old backups.
find "$BACKUP_DIR" -name 'guardian-*.db.gz' -type f -mtime "+$KEEP_DAYS" -delete
echo "[backup] pruned backups older than $KEEP_DAYS days"

# Keep at least one recent copy even if the disk is nearly full.
ls -1t "$BACKUP_DIR"/guardian-*.db.gz 2>/dev/null | tail -n +4 | xargs -r rm -f
