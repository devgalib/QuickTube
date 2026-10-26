#!/usr/bin/env bash
# Stores everything the Release workflow needs as encrypted GitHub repository secrets.
# Values are read locally and sent straight to GitHub with the gh CLI; nothing is printed.
#
# Usage:  scripts/setup-release-secrets.sh
# Needs:  gh (https://cli.github.com) logged in, local.properties with RELEASE_* keys,
#         optionally app/google-services.json (Firebase).
set -euo pipefail

cd "$(dirname "$0")/.."
command -v gh >/dev/null || { echo "Install the GitHub CLI first: https://cli.github.com"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "Run 'gh auth login' first."; exit 1; }
REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
[ -f local.properties ] || { echo "local.properties not found"; exit 1; }

prop() { grep -E "^$1=" local.properties | head -1 | cut -d= -f2- | tr -d '\r'; }

KEYSTORE_PATH="$(prop RELEASE_KEYSTORE_PATH)"
[ -f "$KEYSTORE_PATH" ] || { echo "Keystore not found (RELEASE_KEYSTORE_PATH in local.properties)"; exit 1; }
for key in RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
  [ -n "$(prop "$key")" ] || { echo "$key is empty in local.properties"; exit 1; }
done

set_secret() { gh secret set "$1" --repo "$REPO" >/dev/null && echo "  ✓ $1"; }

echo "Setting secrets on $REPO:"
base64 -w0 "$KEYSTORE_PATH"                      | set_secret RELEASE_KEYSTORE_BASE64
printf '%s' "$(prop RELEASE_KEYSTORE_PASSWORD)" | set_secret RELEASE_KEYSTORE_PASSWORD
printf '%s' "$(prop RELEASE_KEY_ALIAS)"         | set_secret RELEASE_KEY_ALIAS
printf '%s' "$(prop RELEASE_KEY_PASSWORD)"      | set_secret RELEASE_KEY_PASSWORD
if [ -f app/google-services.json ]; then
  set_secret GOOGLE_SERVICES_JSON < app/google-services.json
else
  echo "  - app/google-services.json not found: releases will be built without Firebase"
fi
echo "Done. Publish a release with: git push origin main:production"
