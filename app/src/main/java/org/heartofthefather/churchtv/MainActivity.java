package org.heartofthefather.churchtv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** The full-screen TV display: shows the player page and hands off to the YouTube app if needed. */
public class MainActivity extends Activity {

    private static final String PLAYER_URL = "http://127.0.0.1:" + WebServer.PORT + "/player";
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private AnnouncementView announcements;
    private long lastHandOffAt = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TvService.start(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Native announcement player underneath; the (transparent) web page on top handles
        // the livestream, welcome screen and on-screen messages.
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        announcements = new AnnouncementView(this, Store.get(this));
        announcements.setVisibility(View.INVISIBLE);
        root.addView(announcements, new android.widget.FrameLayout.LayoutParams(-1, -1));

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.addJavascriptInterface(new Bridge(), "ChurchTV");
        web.setWebChromeClient(new WebChromeClient() {
            // Hide the grey "play" placeholder Android shows before a video starts.
            @Override public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, int code, String desc, String url) {
                // The local server may still be starting; try again shortly.
                if (url != null && url.startsWith("http://127.0.0.1")) main.postDelayed(() -> web.loadUrl(PLAYER_URL), 2000);
            }
        });
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        hideSystemUi();
        main.postDelayed(() -> web.loadUrl(PLAYER_URL), 600);

        maybeAskForAutoStartPermission();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("reload", false) && web != null) web.reload();
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (announcements != null) announcements.resume();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.onAppResumed && window.onAppResumed()", null);
        }
    }

    @Override protected void onPause() {
        // Keep playing only while visible; the YouTube app takes the audio when it is in front.
        if (announcements != null) announcements.pause();
        if (web != null) {
            web.evaluateJavascript("window.onAppPaused && window.onAppPaused()", null);
            web.onPause();
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (announcements != null) announcements.release();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Menu button (or center/OK) briefly shows the live/automatic status (never the address).
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_INFO) {
            if (web != null) web.evaluateJavascript("window.showInfo && window.showInfo()", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** On Android 10+ the app needs "display over other apps" to bring itself to the front. */
    private void maybeAskForAutoStartPermission() {
        if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) return;
        if (getSharedPreferences("churchtv", MODE_PRIVATE).getBoolean("askedOverlay", false)) return;
        getSharedPreferences("churchtv", MODE_PRIVATE).edit().putBoolean("askedOverlay", true).apply();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException ignored) {
            // Fire TV often has no screen for this; the setup script grants it instead.
        }
    }

    /** Opens the live video in the Fire TV YouTube app. */
    void openInYouTubeApp(String videoId) {
        // Ignore repeat requests for a few seconds while the YouTube app is opening.
        long now = System.currentTimeMillis();
        if (videoId == null || now - lastHandOffAt < 8000) return;
        lastHandOffAt = now;
        Uri uri = Uri.parse("https://www.youtube.com/watch?v=" + videoId);
        String[] pkgs = {"com.amazon.firetv.youtube", "com.amazon.firetv.youtube.tv", "com.google.android.youtube.tv"};
        for (String p : pkgs) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri).setPackage(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (ActivityNotFoundException ignored) { }
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException ignored) { }
    }

    private final class Bridge {
        /** true = play the announcement loop natively underneath the page. */
        @JavascriptInterface public void setAnnouncements(final boolean on) {
            main.post(() -> { if (announcements != null) announcements.setActive(on); });
        }

        @JavascriptInterface public void openYouTube(final String videoId) {
            main.post(() -> openInYouTubeApp(videoId));
        }
    }
}
