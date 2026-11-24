#!/bin/bash
# Clears a forgotten Church TV control-page PIN. Your announcements and settings are kept.
# The next visit to the control page asks you to create a new PIN.
#
#   bash reset-pin.sh 192.168.1.154
#
# Requires ADB Debugging ON (Settings -> My Fire TV -> Developer Options).
set -u
TV_IP="${1:-}"
if [ -z "${TV_IP}" ]; then echo "Usage: bash reset-pin.sh <TV IP address>"; exit 1; fi
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="$(command -v adb || true)"
[ -z "${ADB}" ] && ADB="${HERE}/platform-tools/adb"
if [ ! -x "${ADB}" ]; then
  echo "adb not found. Run setup-tv.sh once first (it downloads adb), from this same folder."
  exit 1
fi
"${ADB}" connect "${TV_IP}:5555" >/dev/null
echo ">>> If the TV asks \"Allow USB debugging?\", choose OK."
for i in $(seq 1 30); do
  [ "$("${ADB}" -s "${TV_IP}:5555" get-state 2>/dev/null)" = "device" ] && break
  sleep 2
done
R="$("${ADB}" -s "${TV_IP}:5555" shell am broadcast -n org.heartofthefather.churchtv/.ResetPinReceiver 2>&1 | tr -d '\r')"
case "${R}" in
  *"PIN cleared"*) echo "Done. Open http://${TV_IP}:8080 and create a new PIN." ;;
  *) echo "Couldn't reset the PIN:"; echo "${R}"; exit 1 ;;
esac
