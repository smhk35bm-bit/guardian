#!/usr/bin/env bash
# =============================================================
#  Guardian — one-shot installer for a free Ubuntu/Debian VPS
#
#  sudo DOMAIN=guardian.example.com ./deploy/setup.sh
#
#  Without DOMAIN it installs the app + systemd service only and
#  finishes at "how do I reach it?" — use the tunnel section for that.
# =============================================================
set -euo pipefail

GUARDIAN_DIR="${GUARDIAN_DIR:-/opt/guardian}"
SERVICE_USER="${SERVICE_USER:-guardian}"
APP_PORT="${APP_PORT:-3000}"
DOMAIN="${DOMAIN:-}"
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Run this script with sudo."

# --------------------------------------------------------------- packages
log "Installing base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates gnupg git nginx unzip jq rsync

# ----------------------------------------------------------------- node
if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | sed 's/v\([0-9]*\).*/\1/')" -lt 22 ]]; then
  log "Installing Node.js 22 (LTS)"
  mkdir -p /etc/apt/keyrings
  curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key |
    gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
  echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_22.x nodistro main" \
    > /etc/apt/sources.list.d/nodesource.list
  apt-get update -qq
  apt-get install -y -qq nodejs
fi
log "Node.js $(node -v), npm $(npm -v)"

# ------------------------------------------------------------------ user
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
  log "Creating system user '$SERVICE_USER'"
  useradd --system --home "$GUARDIAN_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
fi

# ------------------------------------------------------------------ code
log "Deploying application to $GUARDIAN_DIR"
mkdir -p "$GUARDIAN_DIR"
rsync -a --delete \
  --exclude 'node_modules' --exclude 'data' --exclude 'uploads' \
  --exclude '.env' --exclude '.git' \
  "$SOURCE_DIR/server/" "$GUARDIAN_DIR/server/"

mkdir -p "$GUARDIAN_DIR/server/data" "$GUARDIEN_DIR/server/uploads"
chown -R "$SERVICE_USER:$SERVICE_USER" "$GUARDIAN_DIR"

log "Installing npm dependencies"
cd "$GUARDIEN_DIR/server"
sudo -u "$SERVICE_USER" npm ci --omit=dev --no-audit --no-fund 2>/dev/null ||
  sudo -u "$SERVICE_USER" npm install --omit=dev --no-audit --no-fund

# ------------------------------------------------------------------ env
if [[ ! -f "$GUARDIEN_DIR/server/.env" ]]; then
  log "Generating .env with a fresh JWT secret"
  SECRET="$(node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))")"
  cat > "$GUARDIAN_DIR/server/.env" <<EOF
PORT=$APP_PORT
PUBLIC_URL=
JWT_SECRET=$SECRET
PARENT_TOKEN_DAYS=30
DEVICE_TOKEN_DAYS=365
DATA_DIR=./data
UPLOAD_DIR=./uploads
MAX_UPLOAD_BYTES=10485760
AUTH_RATE_LIMIT=10
AUTH_RATE_WINDOW_MIN=15
DEVICE_RATE_LIMIT=600
NODE_ENV=production
EOF
  chmod 600 "$GUARDIAN_DIR/server/.env"
  chown "$SERVICE_USER:$SERVICE_USER" "$GUARDIEN_DIR/server/.env"
else
  log ".env already exists — keeping it"
fi

# -------------------------------------------------------------- systemd
log "Installing systemd service"
install -m 644 "$SOURCE_DIR/deploy/systemd/guardian.service" /etc/systemd/system/guardian.service
systemctl daemon-reload
systemctl enable guardian >/dev/null
systemctl restart guardian
sleep 2
systemctl --no-pager --lines=10 status guardian || true

# --------------------------------------------------------------- nginx
if [[ -n "$DOMAIN" ]]; then
  log "Configuring Nginx for $DOMAIN"
  sed "s/guardian\.example\.com/${DOMAIN}/g" "$SOURCE_DIR/deploy/nginx/guardian.conf" \
    > /etc/nginx/sites-available/guardian
  ln -sf /etc/nginx/sites-available/guardian /etc/nginx/sites-enabled/guardian
  rm -f /etc/nginx/sites-enabled/default

  log "Obtaining a TLS certificate with Certbot"
  apt-get install -y -qq certbot python3-certbot-nginx
  certbot --nginx --non-interactive --agree-tos \
    --register-unsafely-without-email \
    --redirect -d "$DOMAIN"

  # Write the real public URL into the app config so devices get it on enroll.
  sed -i "s|^PUBLIC_URL=.*|PUBLIC_URL=https://${DOMAIN}|" "$GUARDIEN_DIR/server/.env"
  systemctl restart guardian
else
  log "Skipping Nginx/TLS (no DOMAIN given)"
fi

# ------------------------------------------------------------ firewall
log "Configuring the firewall"
ufw allow OpenSSH >/dev/null 2>&1 || true
ufw allow "Nginx Full" >/dev/null 2>&1 || true
ufw --force enable >/dev/null 2>&1 || true

cat <<'BANNER'

============================================================
 Guardian is installed.

   Status   : systemctl status guardian
   Logs     : journalctl -u guardian -f
   Dashboard: http://<your-vps-ip>:3000   (or the domain you set)
============================================================

Next steps:

 1. Open the dashboard and create the parent account.

 2. Make it reachable over HTTPS (pick one):

    a) Cloudflare quick tunnel — free, instant, URL changes on restart:
         cloudflared tunnel --url http://localhost:3000

    b) Cloudflare named tunnel — free and permanent:
         cloudflared tunnel login
         cloudflared tunnel create guardian
         cloudflared tunnel route dns guardian app.example.com
         cloudflared tunnel run guardian

    c) DuckDNS + Certbot — free subdomain on any VPS:
         https://www.duckdns.org  ->  create a token
         (Domain set above already has a working Certbot certificate.)

 3. Install the Android app on the child's phone and enroll it
    with the one-time code from the dashboard.

BANNER
