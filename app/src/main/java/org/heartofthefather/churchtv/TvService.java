package org.heartofthefather.churchtv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Always-running background service:
 *  - runs the control-page web server
 *  - checks every minute whether the channel is live
 *  - brings the Church TV screen back to the front when the TV wakes up,
 *    and when the stream starts or ends.
 */
public class TvService extends Service implements WebServer.Host, Store.Listener {

    private static final String TAG = "ChurchTV";
    private static final long CHECK_EVERY_SECONDS = 60;

    private Store store;
    private WebServer web;
    private ScheduledExecutorService scheduler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int notLiveStreak = 0;

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // TV woke from standby: wait a moment for the Fire TV home screen, then take over.
            main.postDelayed(() -> bringToFront("screen on"), 2500);
            main.postDelayed(() -> bringToFront("screen on (retry)"), 8000);
            checkLiveNow();
        }
    };

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, TvService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
        store = Store.get(this);
        store.setListener(this);
        web = new WebServer(this, store, this);
        web.start();
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::runCheck, 2, CHECK_EVERY_SECONDS, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        try { unregisterReceiver(screenOn); } catch (Exception ignored) { }
        if (scheduler != null) scheduler.shutdownNow();
        if (web != null) web.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------- live checking ----------

    private void runCheck() {
        try {
            LiveChecker.Result r = LiveChecker.check(store.channel());
            if (!r.ok) { store.setCheckNote(r.note + " — will retry"); return; }
            if (r.live) {
                notLiveStreak = 0;
                store.setLiveStatus(true, r.videoId, r.note);
            } else if (store.isLive() && ++notLiveStreak < 2) {
                // Don't drop the stream on a single hiccup; confirm on the next check.
                store.setCheckNote(r.note + " — confirming…");
            } else {
                store.setLiveStatus(false, null, r.note);
            }
        } catch (Throwable t) {
            Log.w(TAG, "live check failed", t);
        }
    }

    @Override public void checkLiveNow() {
        if (scheduler != null && !scheduler.isShutdown()) scheduler.execute(this::runCheck);
    }

    // ---------- reacting to mode changes ----------

    @Override public void onModeChanged(String effectiveMode) {
        main.post(() -> bringToFront("mode → " + effectiveMode));
    }

    @Override public void reloadPlayer() {
        main.post(() -> {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.putExtra("reload", true);
            try { startActivity(i); } catch (Exception e) { Log.w(TAG, "reload failed", e); }
        });
    }

    private void bringToFront(String why) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return; // TV is off; nothing to show
        Log.i(TAG, "bringing to front: " + why);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(i); } catch (Exception e) { Log.w(TAG, "could not start activity", e); }
    }

    // ---------- WebServer.Host ----------

    @Override public String controlUrl() {
        String ip = WebServer.localIp();
        return ip == null ? "(not connected to network)" : "http://" + ip + ":" + WebServer.PORT;
    }

    @Override public boolean autoStartReady() {
        return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this);
    }

    private final java.util.concurrent.ConcurrentHashMap<String, String> dimsCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override public String mediaDims(java.io.File f) {
        String key = f.getAbsolutePath() + "@" + f.lastModified();
        String cached = dimsCache.get(key);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String d = null;
        try {
            if ("video".equals(Store.typeOf(f.getName()))) {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try {
                    r.setDataSource(f.getAbsolutePath());
                    int w = Integer.parseInt(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    int hh = Integer.parseInt(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    String rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    if ("90".equals(rot) || "270".equals(rot)) { int t = w; w = hh; hh = t; }
                    d = w + "x" + hh;
                } finally { r.release(); }
            } else {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (o.outWidth > 0) {
                    int rot = AnnouncementView.exifRotation(f);
                    d = rot % 180 == 0 ? o.outWidth + "x" + o.outHeight : o.outHeight + "x" + o.outWidth;
                }
            }
        } catch (Throwable ignored) { }
        dimsCache.put(key, d == null ? "" : d);
        return d;
    }

    @Override public String displayInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            android.view.Display d = wm.getDefaultDisplay();
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            d.getRealMetrics(dm);
            int[] panel = AnnouncementView.panelSize(this);
            sb.append("TV panel ").append(panel[0]).append("x").append(panel[1]);
            sb.append(" · app screen ").append(dm.widthPixels).append("x").append(dm.heightPixels);
            sb.append(" · photos and videos shown at ").append(panel[0]).append("x").append(panel[1]);
            if (Build.VERSION.SDK_INT >= 24 && d.getHdrCapabilities() != null) {
                StringBuilder hdr = new StringBuilder();
                for (int t : d.getHdrCapabilities().getSupportedHdrTypes()) {
                    String n = t == 1 ? "Dolby Vision" : t == 2 ? "HDR10" : t == 3 ? "HLG" : t == 4 ? "HDR10+" : null;
                    if (n != null) hdr.append(hdr.length() > 0 ? ", " : "").append(n);
                }
                if (hdr.length() > 0) sb.append(" · HDR: ").append(hdr);
            }
        } catch (Throwable t) {
            sb.append("unknown");
        }
        return sb.toString();
    }

    // ---------- notification (required for an always-on service) ----------

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("svc", "Church TV", NotificationManager.IMPORTANCE_MIN);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "svc");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Church TV is running")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }
}
