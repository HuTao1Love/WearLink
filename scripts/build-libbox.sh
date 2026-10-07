#!/usr/bin/env bash
# Builds a slim sing-box libbox.aar (VLESS/Reality/Hysteria2 only) into core/libs/.
# Requires: Go, Android SDK + NDK 28.x, JDK (Android Studio JBR).
set -euo pipefail

SING_BOX_TAG="${SING_BOX_TAG:-v1.14.2}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/sing-box"

export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
export JAVA_HOME="${JAVA_HOME:-$LOCALAPPDATA/Programs/Android Studio/jbr}"
export PATH="/c/Program Files/Go/bin:$HOME/go/bin:$JAVA_HOME/bin:$PATH"

if [ ! -d "$SRC" ]; then
  git clone --depth 1 --branch "$SING_BOX_TAG" https://github.com/SagerNet/sing-box.git "$SRC"
fi

go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13

cd "$SRC"
TAGS="with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0"
LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_TAG#v} -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid="

gomobile bind -v \
  -o libbox.aar \
  -target "${LIBBOX_TARGETS:-android/arm64,android/arm,android/amd64}" \
  -androidapi 24 \
  -javapkg=io.nekohasekai \
  -libname=box \
  -trimpath -buildvcs=false \
  -ldflags "$LDFLAGS" \
  -tags "$TAGS" \
  ./experimental/libbox

mkdir -p "$ROOT/core/libs"
cp libbox.aar "$ROOT/core/libs/libbox.aar"
echo "libbox.aar -> $ROOT/core/libs/libbox.aar"
