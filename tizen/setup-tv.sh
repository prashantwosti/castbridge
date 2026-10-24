#!/bin/bash
# One-command installer for the CastBridge TV app (macOS and Linux).
#   ./tizen/setup-tv.sh [tv-ip]
# Walks through: finding the TV, Developer Mode, Tizen Studio, the Samsung certificate,
# then builds and installs the app. Safe to re-run; finished steps are skipped.
set -euo pipefail
cd "$(dirname "$0")"

TIZEN_STUDIO=${TIZEN_STUDIO:-$HOME/tizen-studio}
INSTALLER_BASE="https://download.tizen.org/sdk/Installer/tizen-studio_6.1"
TIZEN="$TIZEN_STUDIO/tools/ide/bin/tizen"
SDB="$TIZEN_STUDIO/tools/sdb"
CERT_DIR="$TIZEN_STUDIO/tools/certificate-manager"

bold=$'\033[1m'; blue=$'\033[1;34m'; green=$'\033[1;32m'; red=$'\033[1;31m'; off=$'\033[0m'
step() { printf '\n%s==> %s%s\n' "$blue" "$*" "$off"; }
ok() { printf '%s✓ %s%s\n' "$green" "$*" "$off"; }
fail() { printf '\n%s✗ %s%s\n' "$red" "$*" "$off"; exit 1; }
wait_enter() { read -r -p "   Press Enter when done… " _ </dev/tty; }

# Value of "key":"value" in the TV's device-info JSON.
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }
tv_info() { curl -s -m 2 "http://$1:8001/api/v2/" || true; }

case "$(uname)" in
  Darwin) OS=macos ;;
  Linux) OS=linux ;;
  *) fail "This installer supports macOS and Linux only." ;;
esac

# This computer's LAN address (the TV's Developer Mode must point at it).
local_ip() {
  if [ "$OS" = macos ]; then
    local iface
    iface=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')
    ipconfig getifaddr "${iface:-en0}" 2>/dev/null
  else
    ip route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") print $(i + 1)}'
  fi
}

open_cert_manager() {
  if [ "$OS" = macos ]; then
    open "$CERT_DIR/certificate-manager.app"
  else
    nohup "$CERT_DIR/certificate-manager" >/dev/null 2>&1 &
  fi
}

# --- 1. This computer's address ------------------------------------------------------------
MY_IP=$(local_ip || true)
[ -n "$MY_IP" ] || fail "This computer doesn't seem to be on a network. Connect to the same Wi-Fi as the TV."

# --- 2. Find the TV ------------------------------------------------------------------------
step "Looking for your Samsung TV (turn it on)…"
TV_IP=${1:-}
if [ -z "$TV_IP" ]; then
  PREFIX=${MY_IP%.*}
  FOUND=$(seq 1 254 | xargs -P 64 -I{} sh -c \
    'r=$(curl -s -m 1 http://'"$PREFIX"'.{}:8001/api/v2/ 2>/dev/null); case "$r" in *Tizen*) echo '"$PREFIX"'.{};; esac' || true)
  COUNT=$(printf '%s' "$FOUND" | grep -c . || true)
  if [ "$COUNT" -eq 0 ]; then
    fail "No Samsung TV found. Make sure it's on and on the same network as this computer, or run: $0 <tv-ip>"
  elif [ "$COUNT" -eq 1 ]; then
    TV_IP=$FOUND
  else
    echo "   Found several TVs:"
    i=1; for ip in $FOUND; do echo "   $i) $(tv_info "$ip" | field name) ($ip)"; i=$((i + 1)); done
    read -r -p "   Which one? [1-$COUNT] " n </dev/tty
    TV_IP=$(echo "$FOUND" | sed -n "${n}p")
  fi
fi
TV_NAME=$(tv_info "$TV_IP" | field name)
[ -n "$TV_NAME" ] || fail "No Samsung TV answers at $TV_IP."
ok "$TV_NAME ($TV_IP)"

# --- 3. Developer Mode ---------------------------------------------------------------------
step "Checking the TV's Developer Mode…"
while true; do
  INFO=$(tv_info "$TV_IP")
  MODE=$(echo "$INFO" | field developerMode)
  DEV_IP=$(echo "$INFO" | field developerIP)
  if [ "$MODE" = 1 ] && [ "$DEV_IP" = "$MY_IP" ]; then break; fi
  cat <<EOF
   Developer Mode needs to point at this computer. With the TV remote:
     1. Open ${bold}Apps${off} on the TV, then press ${bold}1 2 3 4 5${off}.
     2. Turn ${bold}Developer mode${off} On and set ${bold}Host PC IP${off} to ${bold}$MY_IP${off}.
     3. Press OK, then restart the TV (hold the power button until it turns off and on).
   (Now: Developer mode $([ "$MODE" = 1 ] && echo on || echo off), Host PC IP ${DEV_IP:-unset})
EOF
  wait_enter
  echo "   Waiting for the TV to come back…"
  for _ in $(seq 1 60); do [ -n "$(tv_info "$TV_IP")" ] && break; sleep 3; done
done
ok "Developer Mode is on and points at this computer"

# --- 4. Tizen Studio -----------------------------------------------------------------------
step "Checking Samsung's TV developer tools (Tizen Studio)…"
if [ ! -x "$TIZEN" ]; then
  echo "   Downloading Tizen Studio (about 280 MB, this can take a while)…"
  TMP=$(mktemp -d)
  if [ "$OS" = macos ]; then INSTALLER=web-cli_Tizen_Studio_6.1_macos-64.bin
  else INSTALLER=web-cli_Tizen_Studio_6.1_ubuntu-64.bin; fi
  curl -fL --progress-bar -o "$TMP/tizen-installer.bin" "$INSTALLER_BASE/$INSTALLER"
  chmod +x "$TMP/tizen-installer.bin"
  echo "   Installing to $TIZEN_STUDIO…"
  "$TMP/tizen-installer.bin" --accept-license "$TIZEN_STUDIO" >/dev/null
  rm -f "$TMP/tizen-installer.bin"
  rmdir "$TMP"
fi
if [ ! -d "$CERT_DIR" ]; then
  echo "   Installing Samsung TV extensions (a few minutes)…"
  "$TIZEN_STUDIO/package-manager/package-manager-cli.bin" install --accept-license \
    Certificate-Manager cert-add-on TV-SAMSUNG-Extension-Tools >/dev/null
fi
ok "Tizen Studio is ready"

# --- 5. Connect and read the TV's ID ------------------------------------------------------
step "Connecting to the TV…"
"$SDB" connect "$TV_IP" >/dev/null
"$SDB" devices | grep -q "^$TV_IP:26101" || fail "Couldn't connect. Check Developer Mode's Host PC IP is $MY_IP and restart the TV."
DUID=$("$SDB" -s "$TV_IP:26101" shell 0 getduid 2>/dev/null | tr -d '\r' | tail -1)
ok "Connected. TV device ID: $DUID"

# --- 6. Samsung certificate ---------------------------------------------------------------
list_profiles() { "$TIZEN" security-profiles list 2>/dev/null | awk 'f && NF {print $1} /\[Profile Name\]/ {f=1}' | sort -u; }

step "Checking for a Samsung certificate…"
PROFILE=${CERT_PROFILE:-}
if [ -z "$PROFILE" ]; then
  PROFILES=$(list_profiles)
  if [ -z "$PROFILES" ]; then
    cat <<EOF
   Samsung TVs only run apps signed for that exact TV. Create a free certificate once:
     1. In the ${bold}Certificate Manager${off} window that opens, click ${bold}+${off}.
     2. Choose ${bold}Samsung${off} › ${bold}TV${off} › name it ${bold}castbridge${off}.
     3. ${bold}Create a new author certificate${off}: any name and password. Sign in with your
        Samsung account when the browser opens.
     4. ${bold}Create a new distributor certificate${off}: privilege ${bold}Public${off}. Make sure this TV's ID
        is in the list: ${bold}$DUID${off}
     5. Click ${bold}Finish${off}.
EOF
    open_cert_manager
    echo "   Waiting for the certificate…"
    until [ -n "$(list_profiles)" ]; do sleep 3; done
    PROFILES=$(list_profiles)
  fi
  PROFILE=$(echo "$PROFILES" | head -1)
  if [ "$(echo "$PROFILES" | grep -c .)" -gt 1 ]; then
    echo "   Certificates found: $(echo "$PROFILES" | tr '\n' ' ')"
    read -r -p "   Which one is for this TV? [$PROFILE] " pick </dev/tty
    PROFILE=${pick:-$PROFILE}
  fi
fi
ok "Using certificate \"$PROFILE\""

# --- 7. Build and install ------------------------------------------------------------------
step "Installing CastBridge on the TV…"
if ! CERT_PROFILE="$PROFILE" bash ./build.sh "$TV_IP" >/tmp/castbridge-install.log 2>&1; then
  tail -15 /tmp/castbridge-install.log
  fail "Install failed (full log: /tmp/castbridge-install.log). If it mentions a certificate, the
   certificate \"$PROFILE\" doesn't include this TV's ID ($DUID): create a new one for it."
fi
ok "Done! CastBridge is open on your TV and in its Apps list."
echo
echo "   Now open CastBridge on your phone and tap ${bold}Start casting${off}."
