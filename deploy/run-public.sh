#!/usr/bin/env bash
# ============================================================
#  Guardian — نشر سريع للاستخدام على شبكة 4G
#
#  يشغّل السيرفر محلياً ثم يفتح نفق HTTPS عاماً عبر cloudflared،
#  ويطبع الرابط الذي يُوضع في تطبيق الأندرويد.
#
#  الاستخدام:
#      ./deploy/run-public.sh            # المنفذ 3000
#      PORT=8080 ./deploy/run-public.sh
#
#  ملاحظة: نفق trycloudflare مؤقت (بلا حساب، بلا ضمان استمرارية).
#  للاستخدام الدائم استخدم Render أو VPS مع Nginx + Let's Encrypt.
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-3000}"
SERVER_DIR="$ROOT/server"
BIN_DIR="$ROOT/deploy/bin"
LOG_DIR="${TMPDIR:-/tmp}/guardian"
CLOUDFLARED="$BIN_DIR/cloudflared"

mkdir -p "$BIN_DIR" "$LOG_DIR"

# ---------- 1) تحقق من التبعيات -------------------------------------------
command -v node >/dev/null 2>&1 || { echo "✗ Node.js غير مثبّت." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "✗ curl غير مثبّت." >&2; exit 1; }

if [ ! -d "$SERVER_DIR/node_modules" ]; then
  echo "→ تثبيت تبعيات السيرفر…"
  (cd "$SERVER_DIR" && npm install --omit=dev)
fi

if [ ! -f "$SERVER_DIR/.env" ]; then
  echo "✗ لا يوجد server/.env — انسخ .env.example وعدّل JWT_SECRET أولاً." >&2
  exit 1
fi

# ---------- 2) تشغيل السيرفر ----------------------------------------------
SERVER_PID=""
if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
  echo "→ السيرفر يعمل مسبقاً على المنفذ $PORT."
else
  echo "→ تشغيل السيرفر على المنفذ $PORT…"
  (cd "$SERVER_DIR" && PORT="$PORT" nohup node src/index.js >"$LOG_DIR/server.log" 2>&1) &
  SERVER_PID=$!
  for _ in $(seq 1 40); do
    if curl -fsS --max-time 2 "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
      break
    fi
    sleep 0.5
  done
  if ! curl -fsS --max-time 2 "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
    echo "✗ لم يبدأ السيرفر. راجع $LOG_DIR/server.log" >&2
    exit 1
  fi
  echo "✓ السيرفر جاهز."
fi

# ---------- 3) تنزيل cloudflared ------------------------------------------
if [ ! -x "$CLOUDFLARED" ]; then
  case "$(uname -m)" in
    x86_64|amd64) ARCH=amd64 ;;
    aarch64|arm64) ARCH=arm64 ;;
    armv7l) ARCH=arm ;;
    *) echo "✗ معمارية غير مدعومة: $(uname -m)" >&2; exit 1 ;;
  esac
  echo "→ تنزيل cloudflared ($ARCH)…"
  curl -fsSL -o "$CLOUDFLARED" \
    "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$ARCH"
  chmod +x "$CLOUDFLARED"
fi

# ---------- 4) فتح النفق ---------------------------------------------------
TUNNEL_LOG="$LOG_DIR/tunnel.log"
: >"$TUNNEL_LOG"
"$CLOUDFLARED" tunnel --url "http://127.0.0.1:$PORT" --no-autoupdate >"$TUNNEL_LOG" 2>&1 &
TUNNEL_PID=$!

cleanup() {
  echo
  echo "→ إيقاف النفق…"
  kill "$TUNNEL_PID" 2>/dev/null || true
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

PUBLIC_URL=""
for _ in $(seq 1 60); do
  PUBLIC_URL="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$TUNNEL_LOG" | head -1 || true)"
  [ -n "$PUBLIC_URL" ] && break
  sleep 1
done

if [ -z "$PUBLIC_URL" ]; then
  echo "✗ تعذّر إنشاء النفق. راجع $TUNNEL_LOG" >&2
  exit 1
fi

# انتظر حتى يصبح الرابط قابلاً للوصول فعلاً (يسجّل cloudflared الاتصال بعد ثوان).
for _ in $(seq 1 30); do
  if curl -fsS --max-time 3 "$PUBLIC_URL/api/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

cat <<EOF

╭──────────────────────────────────────────────────────────────╮
│  الرابط العام جاهز — ضعه في تطبيق الأندرويد:                 │
╰──────────────────────────────────────────────────────────────╯

    $PUBLIC_URL

  عنوان الخادم في التطبيق:   $PUBLIC_URL/api
  لوحة التحكم:               $PUBLIC_URL

  الخطوات:
    1. افتح اللوحة وأنشئ حساباً، ثم «+ جهاز» وخذ كود الربط.
    2. على الهاتف: افتح Guardian ← الصق العنوان ← أدخل الكود.
    3. اسمح بالأذونات، ثم من اللوحة ← «البث المباشر».

  عند الانتهاء اضغط Ctrl+C لإيقاف السيرفر والنفق.

  السجلات:
    server : $LOG_DIR/server.log
    tunnel : $TUNNEL_LOG

EOF

wait "$TUNNEL_PID"
