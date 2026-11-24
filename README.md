# Church TV

A Fire TV app for the Heart of the Father Ministry building TV. It:

- **Starts by itself** when the TV powers up or wakes from standby.
- **Loops announcement videos and images** that you upload from any computer or phone on the building network.
- **Switches to the YouTube livestream automatically** when [@heartofthefatherministry](https://www.youtube.com/@heartofthefatherministry) goes live, and back to announcements when the stream ends.

On a normal Sunday nobody has to touch the TV.

---

## One-time setup (about 20 minutes)

You need: the TV, a phone with the **Amazon Fire TV** app (to use as the remote), and the Mac Mini (or any Mac) on the same network.

### 1. Take control of the TV with your phone
1. Install the **Amazon Fire TV** app on your phone (App Store / Google Play) and join the building Wi-Fi.
2. Open the app. It should find the TCL TV. Tap it and type the 4-digit code shown on the TV.
3. Your phone now works as the remote.

### 2. Note the TV's network address
Go to **Settings → My Fire TV → About → Network**. Write down the **IP address** (for example `192.168.1.50`).

> **Tip:** Ask whoever manages the router to give the TV a *reserved* or *static* IP address (often called a "DHCP reservation"). Otherwise the address can change after a power outage, and the control page bookmark will stop working.

### 3. Turn on Developer Options
1. Go to **Settings → My Fire TV → About**.
2. Highlight the first line (the TV's name) and press **Select 7 times**, until it says "You are now a developer."
3. Go back one screen and open **Developer Options**.
4. Turn **ADB Debugging** **ON**.

### 4. Install Church TV
**Option A — from the Mac (easiest).** Open **Terminal** on the Mac and paste the two lines below, putting your TV's IP address at the end:

```bash
curl -fsSL -o setup-tv.sh https://raw.githubusercontent.com/__GITHUB_REPO__/main/tools/setup-tv.sh
```

```bash
bash setup-tv.sh 192.168.1.50
```

When the TV shows **"Allow USB debugging?"**, tick **Always allow from this computer** and choose **OK**.

The script installs the app, turns off the screensaver, de-bloats the TV, and opens Church TV. (On newer Fire OS 8 TVs it also grants a permission Church TV needs to come on screen by itself. Your Fire OS 7 TV doesn't need it.)

**Option B — with the Downloader app on the TV.**
1. On the TV, search for and install **Downloader** (orange icon, by AFTVnews).
2. In **Developer Options**, open **Install unknown apps** and turn **Downloader** **ON**.
3. Open Downloader, type `https://github.com/__GITHUB_REPO__/raw/main/ChurchTV.apk`, press **Go**, then **Install**.
4. Open **Church TV** once from **Your Apps**. Running the Mac script afterward is optional; it does the de-bloating.

### 5. Put the tile up front (optional)
Press **Home**, go to **Your Apps**, highlight **Church TV**, press **Menu (☰) → Move to front**.

### 6. Turn off Amazon's autoplay and tracking (on the TV)
- **Settings → Preferences → Featured Content:** turn **Allow Video Autoplay** and **Allow Audio Autoplay** **OFF**.
- **Settings → Preferences → Privacy Settings:** turn **Device Usage Data**, **Collect App Usage Data** and **Interest-based Ads** **OFF**.

---

## Everyday use: the control page

On any computer or phone on the building network, open **`http://<TV IP>:8080`** (for example `http://192.168.1.50:8080`) and bookmark it.

- **Announcements:** drag videos or images in. Use ↑ ↓ to reorder and **Delete** to remove. MP4 videos work best.
- **Automatic** (normal setting): announcements loop until the channel goes live, then the TV switches to the stream. It switches back when the stream ends.
- **Show livestream / Show announcements:** a manual override. It lasts until the stream starts or stops, then the TV goes back to Automatic.
- **Check for livestream now:** the TV checks YouTube once a minute; this checks immediately.
- **Restart TV screen:** reloads the TV display if anything looks stuck.

If you forget the address, press **OK** or **Menu** on the remote while Church TV is showing. The address appears in the bottom corner.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| The TV shows the Amazon home screen after turning on | Run `bash setup-tv.sh <TV IP>` again from the Mac; it re-grants the permission. Also check the warning at the top of the control page. |
| The stream shows an error or black screen inside Church TV | On the control page, under **Settings → Play the livestream**, choose **In the YouTube app**. |
| The control page won't load | Check the TV is on and on the network, and that the IP hasn't changed (Settings → My Fire TV → About → Network). |
| The TV didn't switch to the stream | Make sure the stream is **public** (not unlisted). Click **Check for livestream now**. |
| Undo the de-bloat | `bash undo-debloat.sh <TV IP>` turns everything back on. A factory reset also restores the TV. |

## Updating the app

Run the Mac setup script again. It downloads and installs the newest version, and your announcements and settings are kept. Downloader with the same link works too.

---

### For developers
- Plain Java Android app, no external libraries. Build with `./gradlew assembleRelease` (Android SDK 34) and copy `app/build/outputs/apk/release/app-release.apk` to `ChurchTV.apk`.
- `keystore/churchtv.jks` signs the app. **Keep it**: Android only installs an update over the existing app when both are signed with the same key. It's committed on purpose; installing anything on the TV still requires the TV's ADB approval.
- Live detection loads `https://www.youtube.com/@CHANNEL/live` once a minute and reads the current video's ID and its live flag (see `LiveChecker.java`).
