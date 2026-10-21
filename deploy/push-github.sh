#!/usr/bin/env bash
#
# يربط المشروع بمستودع GitHub ويرفعه — خطوة أخيرة قبل النشر على Render.
#
#   ./deploy/push-github.sh <اسم-الحساب>/<اسم-المستودع>
#
# المتطلّب: المفتاح SSH العمومي (يطبعه السكربت) أن يكون مضافاً في
# GitHub ← Settings ← SSH and GPG keys ← New SSH key.
#
set -euo pipefail

cd "$(dirname "$0")/.."

REPO="${1:-}"
if [ -z "$REPO" ]; then
  echo "الاستخدام: $0 <اسم-الحساب>/<اسم-المستودع>"
  echo "مثال: $0 hako/guardian"
  exit 1
fi

KEY="${HOME}/.ssh/github"
if [ ! -f "$KEY" ]; then
  ssh-keygen -t ed25519 -N "" -C "guardian-deploy" -f "$KEY" >/dev/null
  echo "• أُنشئ مفتاح SSH جديد: $KEY"
fi

mkdir -p "${HOME}/.ssh"
chmod 700 "${HOME}/.ssh"
if ! grep -q "Host github.com" "${HOME}/.ssh/config" 2>/dev/null; then
  cat >> "${HOME}/.ssh/config" <<EOF

Host github.com
  IdentityFile ${KEY}
  IdentitiesOnly yes
EOF
  chmod 600 "${HOME}/.ssh/config"
fi

# هل المفتاح مضاف على GitHub أصلاً؟
if ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -T git@github.com 2>&1 |
  grep -qi "successfully authenticated"; then
  echo "✓ الاتصال بـ GitHub يعمل بالمفتاح المضاف."
else
  cat <<EOF

════════════════════════════════════════════════════════════════
  خطوة واحدة ناقصة — أضف المفتاح العمومي إلى GitHub:
════════════════════════════════════════════════════════════════

  1) افتح:  https://github.com/settings/ssh/new
  2) Title :  guardian-deploy
  3) Key   :  انسخ ما يلي (سطر واحد)

EOF
  cat "${KEY}.pub"
  cat <<EOF

════════════════════════════════════════════════════════════════
  4) أنشئ مستودعاً فارغاً (بلا README) باسم:  ${REPO}
  5) ثم أعد تنفيذ:  $0 ${REPO}

EOF
  exit 1
fi

if ! git remote get-url origin >/dev/null 2>&1; then
  git remote add origin "git@github.com:${REPO}.git"
else
  git remote set-url origin "git@github.com:${REPO}.git"
fi

BRANCH="$(git branch --show-current)"
git push -u origin "$BRANCH"

echo
echo "✓ رُفع المشروع إلى https://github.com/${REPO}"
echo "  الخطوة التالية: Render ← New ← Blueprint ← اختر ${REPO}"
