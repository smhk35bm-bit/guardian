#!/usr/bin/env bash
#
# ينشئ قاعدة البيانات على Turso (مجانية، بلا بطاقة) ويخزّن رابطها وتوكنها
# محلياً في server/.env.turso — لن يُرفع إلى GitHub أبداً.
#
#   ./deploy/setup-turso.sh [اسم-القاعدة]
#
set -euo pipefail

export PATH="$HOME/.turso:$PATH"

cd "$(dirname "$0")/.."

DB_NAME="${1:-${TURSO_DB_NAME:-guardian}}"
LOCATION="${TURSO_LOCATION:-lhr}"   # London — أقرب نقطة إلى الجزائر
OUT="server/.env.turso"

strip_ansi() { sed 's/\x1b\[[0-9;]*m//g'; }

if ! command -v turso >/dev/null 2>&1; then
  echo "✗ أداة Turso غير مثبّتة. ثبّتها بـ:"
  echo "    curl -sSfL https://get.tur.so/install.sh | bash"
  exit 1
fi

if ! turso db list >/dev/null 2>&1; then
  echo "✗ لست مسجّل دخولاً في Turso. نفّذ:"
  echo "    turso auth login"
  exit 1
fi

if turso db show "$DB_NAME" --http-url >/dev/null 2>&1; then
  echo "• القاعدة «$DB_NAME» موجودة مسبقاً — سنستخدمها."
else
  echo "• إنشاء القاعدة «$DB_NAME» في منطقة $LOCATION ..."
  turso db create "$DB_NAME" --location "$LOCATION" --wait
fi

URL="$(turso db show "$DB_NAME" --http-url | strip_ansi | tail -1)"
TOKEN="$(turso db tokens create "$DB_NAME" 2>/dev/null | strip_ansi | grep -Eo 'eyJ[A-Za-z0-9_.-]+' | tail -1)"

if [ -z "$URL" ] || [ -z "$TOKEN" ]; then
  echo "✗ لم أتمكن من قراءة الرابط أو التوكن — جرّب يدوياً:"
  echo "    turso db show $DB_NAME --http-url"
  echo "    turso db tokens create $DB_NAME"
  exit 1
fi

umask 077
cat > "$OUT" <<EOF
# Turso credentials — ملف محلي، لا يُرفع إلى GitHub
LIBSQL_URL=$URL
LIBSQL_TOKEN=$TOKEN
EOF
chmod 600 "$OUT"

echo "✓ القاعدة    : $DB_NAME"
echo "✓ الرابط     : $URL"
echo "✓ التوكن     : حُفظ في $OUT (بصلاحيات 600)"
echo
echo "下一步 — انسخ هذه القيمتين إلى Render عند طلبها:"
echo "  LIBSQL_URL   = $URL"
echo "  LIBSQL_TOKEN = (اعرضه بـ: cat $OUT)"
