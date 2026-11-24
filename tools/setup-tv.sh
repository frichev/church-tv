#!/bin/bash
# Church TV — one-time TV setup, run from a Mac (or Linux) computer on the same network as the TV.
#
#   bash setup-tv.sh 192.168.1.50
#
# What it does:
#   1. Connects to the Fire TV over the network (ADB debugging must be ON on the TV).
#   2. Installs Church TV if it isn't installed yet (or updates it).
#   3. Grants Church TV permission to bring itself on screen when the TV wakes up
#      and when the livestream starts or ends (Fire TV has no menu for this).
#   4. Turns off the screensaver.
#   5. De-bloats: turns off Amazon apps and ad/tracking services we don't use.
#      Nothing is deleted. Run  bash undo-debloat.sh <TV-IP>  to turn them all back on.
#   6. Opens Church TV.

set -u
TV_IP="${1:-}"
REPO="frichev/church-tv"
PKG="org.heartofthefather.churchtv"
APK_URL="https://github.com/$REPO/raw/main/ChurchTV.apk"
HERE="$(cd "$(dirname "$0")" && pwd)"

if [ -z "$TV_IP" ]; then
  echo "Usage: bash setup-tv.sh <TV IP address>"
  echo "Find the IP on the TV: Settings → My Fire TV → About → Network."
  exit 1
fi

# ---------- get adb (Android's remote-control tool) ----------
ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  TOOLS="$HERE/platform-tools"
  if [ ! -x "$TOOLS/adb" ]; then
    case "$(uname -s)" in
      Darwin) ZIP=platform-tools-latest-darwin.zip ;;
      Linux)  ZIP=platform-tools-latest-linux.zip ;;
      *) echo "Please install Android platform-tools (adb) first."; exit 1 ;;
    esac
    echo "Downloading Android platform-tools (adb) from Google…"
    curl -fsSL -o "$HERE/$ZIP" "https://dl.google.com/android/repository/$ZIP" || { echo "Download failed."; exit 1; }
    (cd "$HERE" && unzip -q -o "$ZIP" && rm -f "$ZIP")
  fi
  ADB="$TOOLS/adb"
fi

echo
echo "Connecting to the TV at $TV_IP…"
echo ">>> If the TV asks \"Allow USB debugging?\", tick \"Always allow from this computer\" and choose OK."
"$ADB" disconnect "$TV_IP:5555" >/dev/null 2>&1
"$ADB" connect "$TV_IP:5555"
for i in $(seq 1 30); do
  STATE="$("$ADB" -s "$TV_IP:5555" get-state 2>/dev/null || true)"
  [ "$STATE" = "device" ] && break
  sleep 2
done
if [ "$STATE" != "device" ]; then
  echo
  echo "Couldn't connect. Check that:"
  echo "  • the TV is on and on the same network as this computer"
  echo "  • Settings → My Fire TV → Developer Options → ADB Debugging is ON"
  echo "  • you accepted the \"Allow USB debugging?\" prompt on the TV"
  echo "Then run this script again."
  exit 1
fi
A() { "$ADB" -s "$TV_IP:5555" "$@"; }
SH() { A shell "$@" 2>&1 | tr -d '\r'; }

echo "Connected. TV software: Fire OS based on Android $(SH getprop ro.build.version.release) (API $(SH getprop ro.build.version.sdk)), model $(SH getprop ro.product.model)"

# ---------- install / update Church TV ----------
LOCAL_APK="$HERE/../ChurchTV.apk"
[ -f "$LOCAL_APK" ] || LOCAL_APK="$HERE/ChurchTV.apk"
if [ ! -f "$LOCAL_APK" ]; then
  echo "Downloading the latest Church TV app…"
  curl -fsSL -o "$HERE/ChurchTV.apk" "$APK_URL" && LOCAL_APK="$HERE/ChurchTV.apk"
fi
if [ -f "$LOCAL_APK" ]; then
  echo "Installing Church TV…"
  A install -r "$LOCAL_APK" | tail -1
fi
if ! SH pm list packages "$PKG" | grep -q "$PKG"; then
  echo "Church TV is not installed and couldn't be installed automatically. Install it with Downloader, then run this again."
  exit 1
fi

# ---------- permissions ----------
echo "Allowing Church TV to come on screen by itself…"
SH appops set "$PKG" SYSTEM_ALERT_WINDOW allow
SH cmd deviceidle whitelist +"$PKG" >/dev/null || true   # don't let battery/idle optimisation stop it

echo "Turning off the screensaver…"
SH settings put secure screensaver_enabled 0
SH settings put secure screensaver_activate_on_sleep 0

# ---------- de-bloat ----------
echo
echo "De-bloating (turning off apps we don't use; nothing is deleted)…"
# Each line: package  |  what it is.  Only ones present on this TV are touched.
BLOAT="
com.amazon.avod|Prime Video
com.amazon.imdb.tv.android.app|Freevee
com.amazon.bueller.music|Amazon Music
com.amazon.bueller.photos|Amazon Photos
com.amazon.shoptv.firetv.client|Amazon Shopping
com.amazon.shoptv.client|Amazon Shopping
com.amazon.device.sale.service|Deals and sales service
com.amazon.kso|Sponsored content / special offers
com.amazon.ags.app|Amazon GameCircle
com.amazon.tv.csapp|Help and tutorials app
com.amazon.tmm.tutorial|Tutorial videos
com.amazon.device.crashmanager|Crash report uploader
com.amazon.dp.logger|Usage logger
com.amazon.cloud9|Silk Browser
com.amazon.tv.ottssocompanionapp|Cable provider sign-in companion
"
echo "$BLOAT" | while IFS='|' read -r P WHAT; do
  [ -z "$P" ] && continue
  if SH pm list packages "$P" | grep -qx "package:$P"; then
    R="$(SH pm disable-user --user 0 "$P")"
    case "$R" in *disabled*) echo "  off: $WHAT ($P)";; *) echo "  skipped (TV wouldn't allow): $WHAT";; esac
  fi
done

# ---------- start ----------
echo
echo "Opening Church TV…"
SH am start -n "$PKG/.MainActivity" >/dev/null
echo
echo "Done! The control page is:  http://$TV_IP:8080"
echo "Open it in a browser on any computer or phone on the building network."
