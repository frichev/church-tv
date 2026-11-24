#!/bin/bash
# Turns every app the setup script disabled back on.
#   bash undo-debloat.sh 192.168.1.50
set -u
TV_IP="${1:?Usage: bash undo-debloat.sh <TV IP address>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="$(command -v adb || echo "${HERE}/platform-tools/adb")"
"${ADB}" connect "${TV_IP}:5555" >/dev/null
for P in com.amazon.avod com.amazon.imdb.tv.android.app com.amazon.bueller.music com.amazon.bueller.photos \
         com.amazon.shoptv.firetv.client com.amazon.shoptv.client com.amazon.device.sale.service com.amazon.kso \
         com.amazon.ags.app com.amazon.tv.csapp com.amazon.tmm.tutorial com.amazon.device.crashmanager \
         com.amazon.dp.logger com.amazon.cloud9 com.amazon.tv.ottssocompanionapp; do
  "${ADB}" -s "${TV_IP}:5555" shell pm enable "${P}" >/dev/null 2>&1 && echo "on: ${P}"
done
"${ADB}" -s "${TV_IP}:5555" shell settings put secure screensaver_enabled 1
echo "Done. Everything the setup script turned off is back on."
