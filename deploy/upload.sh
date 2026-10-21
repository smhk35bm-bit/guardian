#!/usr/bin/env bash
# =============================================================
#  Guardian — push the project to a VPS over SSH
#
#    ./deploy/upload.sh ubuntu@203.0.113.10
#    ./deploy/upload.sh ubuntu@203.0.113.10 --run-setup
#
#  The script copies the `server/` and `deploy/` folders to the VPS
#  and leaves the runtime data (database, uploads, .env) untouched.
# =============================================================
set -euo pipefail

REMOTE_TARGET="${1:-}"
RUN_SETUP="${2:-}"

if [[ -z "$REMOTE_TARGET" ]]; then
  echo "Usage: $0 user@host-or-ip [--run-setup]" >&2
  exit 1
fi

SSH_KEY="${SSH_KEY:-$HOME/.ssh/guardian_vps}"
REMOTE_DIR="${REMOTE_DIR:-/tmp/guardian-upload}"
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ ! -f "$SSH_KEY" ]]; then
  echo "[upload] SSH key not found at $SSH_KEY" >&2
  echo "[upload] generate one with: ssh-keygen -t ed25519 -N '' -f $SSH_KEY" >&2
  exit 1
fi

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)

log() { printf '\033[1;36m==> %s\033[0m\n' "$*"; }

log "Packing the project (excluding runtime data)"
PACKAGE="$(mktemp -t guardian-XXXXXX.tar.gz)"
trap 'rm -f "$PACKAGE"' EXIT

tar -czf "$PACKAGE" \
  -C "$SOURCE_DIR" \
  --exclude='node_modules' \
  --exclude='server/data' \
  --exclude='server/uploads' \
  --exclude='server/.env' \
  --exclude='.git' \
  --exclude='android/.gradle' \
  --exclude='android/build' \
  --exclude='android/app/build' \
  server deploy docs README.md

log "Package size: $(du -h "$PACKAGE" | cut -f1)"

log "Uploading to $REMOTE_TARGET:$REMOTE_DIR"
scp "${SSH_OPTS[@]}" "$PACKAGE" "$REMOTE_TARGET:$REMOTE_DIR.tar.gz"

log "Extracting on the remote host"
ssh "${SSH_OPTS[@]}" "$REMOTE_TARGET" <<REMOTE
set -e
mkdir -p $REMOTE_DIR
tar -xzf $REMOTE_DIR.tar.gz -C $REMOTE_DIR
rm -f $REMOTE_DIR.tar.gz
echo "--- uploaded files ---"
ls $REMOTE_DIR
REMOTE

if [[ "$RUN_SETUP" == "--run-setup" ]]; then
  log "Running deploy/setup.sh on the remote host"
  ssh "${SSH_OPTS[@]}" "$REMOTE_TARGET" <<REMOTE
set -e
cd $REMOTE_DIR
chmod +x deploy/setup.sh
sudo ./deploy/setup.sh
REMOTE
fi

cat <<'BANNER'

============================================================
 Upload complete.

 On the VPS, finish with:

   cd /opt/guardian && sudo ./deploy/setup.sh

 ...or re-run it with a domain:

   cd /opt/guardian && sudo DOMAIN=guardian.example.com ./deploy/setup.sh

============================================================

BANNER
