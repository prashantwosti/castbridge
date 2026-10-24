#!/bin/bash
# Packages receiver/ as the CastBridge Tizen TV app and installs it on a TV in Developer Mode.
#   ./build.sh <tv-ip>        package, install and launch on a TV
#   ./build.sh --store        package only, for Samsung Seller Office (→ dist/CastBridge-<ver>.wgt)
# Needs Tizen Studio (TIZEN_STUDIO, default ~/tizen-studio) and a Samsung certificate profile
# (CERT_PROFILE, default "castbridge") whose distributor cert includes the TV's DUID.
set -euo pipefail
cd "$(dirname "$0")"

TV_IP=${1:?usage: build.sh <tv-ip> | --store}
TIZEN_STUDIO=${TIZEN_STUDIO:-$HOME/tizen-studio}
PROFILE=${CERT_PROFILE:-castbridge}
TIZEN="$TIZEN_STUDIO/tools/ide/bin/tizen"
SDB="$TIZEN_STUDIO/tools/sdb"
APP_ID=CstBrdg001.CastBridge

rm -rf build && mkdir build
cp config.xml icon.png ../receiver/index.html ../receiver/receiver.js ../receiver/jmuxer.min.js ../receiver/qrcode.js build/

# DEBUG_REPORT=http://<mac-ip>:8080/report makes the app post diagnostics there
# (receive them with tools/test-sender/server.js).
if [ -n "${DEBUG_REPORT:-}" ]; then
  echo "window.CASTBRIDGE_REPORT = '$DEBUG_REPORT';" > build/debug.js
  # DEBUG_WS=ws://host:port/stream pins the stream source instead of scanning for the phone.
  [ -n "${DEBUG_WS:-}" ] && echo "window.CASTBRIDGE_WS = '$DEBUG_WS';" >> build/debug.js
  sed -i.bak 's|<script src="receiver.js">|<script src="debug.js"></script><script src="receiver.js">|' build/index.html
  rm build/index.html.bak
fi

"$TIZEN" package -t wgt -s "$PROFILE" -- build
WGT=$(ls build/*.wgt)

if [ "$TV_IP" = --store ]; then
  [ -z "${DEBUG_REPORT:-}" ] || { echo "Refusing to make a store package with DEBUG_REPORT set." >&2; exit 1; }
  VERSION=$(sed -n 's/.*version="\([0-9.]*\)" viewmodes.*/\1/p' config.xml)
  mkdir -p ../dist
  cp "$WGT" "../dist/CastBridge-$VERSION.wgt"
  echo "Store package: dist/CastBridge-$VERSION.wgt"
  exit 0
fi

"$SDB" connect "$TV_IP"
SERIAL="$TV_IP:26101"
"$TIZEN" install -s "$SERIAL" -n "$(basename "$WGT")" -- build
"$TIZEN" run -s "$SERIAL" -p "$APP_ID"
