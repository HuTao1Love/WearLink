#!/usr/bin/env bash
# Builds signed release APKs and publishes them as a GitHub release; the in-app updater
# reads update.json from releases/latest. Bump wearlink.versionCode/versionName in
# gradle.properties first.
#
#   scripts/release.sh "Что нового"          build + publish
#   scripts/release.sh --local "Что нового"  build into build/release only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
REPO="${WEARLINK_REPO:-HuTao1Love/WearLink}"
PUBLISH=1
if [ "${1:-}" = "--local" ]; then PUBLISH=0; shift; fi
NOTES="${1:-}"

prop() { grep "^$1=" gradle.properties | cut -d= -f2-; }
CODE="$(prop wearlink.versionCode)"
NAME="$(prop wearlink.versionName)"
BUILD_TYPE="${BUILD_TYPE:-release}"

[ -f keystore.properties ] || { echo "keystore.properties not found: updates must be signed with the release key" >&2; exit 1; }

export JAVA_HOME="${JAVA_HOME:-$LOCALAPPDATA/Programs/Android Studio/jbr}"
./gradlew :shared:test ":mobile:assemble${BUILD_TYPE^}" ":wear:assemble${BUILD_TYPE^}"

OUT="build/release"
rm -rf "$OUT" && mkdir -p "$OUT"
cp "mobile/build/outputs/apk/$BUILD_TYPE/mobile-$BUILD_TYPE.apk" "$OUT/wearlink-phone.apk"
cp "wear/build/outputs/apk/$BUILD_TYPE/wear-$BUILD_TYPE.apk" "$OUT/wearlink-watch.apk"

PHONE_SHA="$(sha256sum "$OUT/wearlink-phone.apk" | cut -d' ' -f1)"
WATCH_SHA="$(sha256sum "$OUT/wearlink-watch.apk" | cut -d' ' -f1)"
python - "$OUT/update.json" "$CODE" "$NAME" "$NOTES" "$PHONE_SHA" "$WATCH_SHA" <<'PY'
import json, sys
out, code, name, notes, phone_sha, watch_sha = sys.argv[1:]
json.dump({
    "versionCode": int(code),
    "versionName": name,
    "notes": notes,
    "phoneApk": "wearlink-phone.apk",
    "watchApk": "wearlink-watch.apk",
    "phoneSha256": phone_sha,
    "watchSha256": watch_sha,
}, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
echo "Built $NAME ($CODE) into $OUT"

if [ "$PUBLISH" = 1 ]; then
  gh release create "v$NAME" "$OUT"/* --repo "$REPO" --title "WearLink $NAME" --notes "${NOTES:-WearLink $NAME}"
fi
