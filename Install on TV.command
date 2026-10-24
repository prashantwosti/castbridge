#!/bin/bash
# Double-click in Finder to install CastBridge on your Samsung TV.
cd "$(dirname "$0")"
bash ./tizen/setup-tv.sh "$@"
echo
read -r -p "Press Enter to close this window."
