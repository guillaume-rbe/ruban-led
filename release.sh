#!/usr/bin/env bash
# Publishes the app as a GitHub release of guillaume-rbe/ruban-led, which the app's "Mise à jour"
# button installs.
#   1. bump android:versionCode (and versionName) in AndroidManifest.xml
#   2. write its "## <versionName>" section in CHANGELOG.md (the release's text, in French)
#   3. commit and push main
#   4. bash release.sh
# The release is tagged ruban-led-v<versionCode> (the app looks for that prefix) and marked "latest", and
# carries ruban-led.apk and its SHA-256. Always sign with the same key (this PC's debug keystore),
# or Android refuses the update.
set -euo pipefail
cd "$(dirname "$0")"

REPO=guillaume-rbe/ruban-led
GH="${GH:-gh}"
command -v "$GH" >/dev/null || GH="/c/Program Files/GitHub CLI/gh.exe"
CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' AndroidManifest.xml)
NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
TAG="ruban-led-v$CODE"

if "$GH" release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "$TAG already exists: bump android:versionCode first"; exit 1
fi

# the release's text: this version's section of CHANGELOG.md, up to the next "## "
NOTES=$(awk -v v="## $NAME" '$0 == v {on=1; next} /^## / {on=0} on' CHANGELOG.md | sed '/./,$!d')
if [ -z "$NOTES" ]; then
    echo "no \"## $NAME\" section in CHANGELOG.md: write what's new first"; exit 1
fi
NOTES="$NOTES

Installée depuis l'app : bouton « Mise à jour »."

bash build.sh
sha256sum build/ruban-led.apk | cut -d' ' -f1 > build/ruban-led.apk.sha256

"$GH" release create "$TAG" build/ruban-led.apk build/ruban-led.apk.sha256 \
    --repo "$REPO" --target main --latest \
    --title "Ruban LED $NAME" \
    --notes "$NOTES"
echo "published $TAG"
