package org.heartofthefather.churchtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays the announcement loop at the TV panel's full resolution (4K on a 4K TV).
 *
 * Fire TV draws normal app screens at 1920x1080 and stretches them. Only SurfaceViews
 * are composed at the panel's real resolution, so photos and videos go there:
 *
 *   videoSurf – the TV's hardware video player draws here (handles 4K HEVC/HDR phone video)
 *   photoSurf – photos are drawn here into a 3840x2160 buffer (sits above videoSurf)
 *   overlay   – (normal 1080p layer) the outgoing picture, faded out for a cross dissolve
 *   black     – (normal layer) faded in/out for "fade through black"
 */
public class AnnouncementView extends FrameLayout {

    private static final String TAG = "ChurchTV.Ann";
    /** Resolution photos are drawn at; reported on the control page. */
    public static volatile String photoResolution = "?";
    /** The on-screen instance (for the control page's test-pattern button). */
    public static volatile AnnouncementView current;

    private final Store store;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final int panelW, panelH;

    private final SurfaceView videoSurf, photoSurf;
    private final ImageView overlay;
    private final View black;

    private boolean videoSurfaceReady = false, photoSurfaceReady = false;
    private Runnable pendingVideo, pendingPhoto;

    private MediaPlayer mp;
    private Bitmap currentPhoto;       // full-resolution photo currently on photoSurf
    private Bitmap currentPhotoSmall;  // screen-sized copy, used as the "outgoing" picture
    private static final int NONE = 0, PHOTO = 1, VIDEO = 2;
    private int showing = NONE;
    private boolean running = false, paused = false;
    private int idx = -1;
    private String playlistKey = "";
    private int generation = 0;

    private final Runnable advance = this::next;
    private final Runnable watchPlaylist = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!key().equals(playlistKey)) restart();
            h.postDelayed(this, 3000);
        }
    };

    public AnnouncementView(Context ctx, Store store) {
        super(ctx);
        this.store = store;
        setBackgroundColor(Color.BLACK);

        int[] panel = panelSize(ctx);
        panelW = panel[0];
        panelH = panel[1];
        photoResolution = panelW + "x" + panelH;
        current = this;
        Diag.log("Photo layer will be " + panelW + "x" + panelH + "; " + describeDisplay(ctx));

        videoSurf = new SurfaceView(ctx);
        videoSurf.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                videoSurfaceReady = true;
                if (pendingVideo != null) { Runnable r = pendingVideo; pendingVideo = null; r.run(); }
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                videoSurfaceReady = false;
                releasePlayer();
            }
        });
        addView(videoSurf, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

        photoSurf = new SurfaceView(ctx);
        photoSurf.setZOrderMediaOverlay(true);               // above the video surface
        photoSurf.getHolder().setFixedSize(panelW, panelH);  // real 4K buffer on a 4K panel
        photoSurf.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { }
            @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int hh) {
                Diag.log("Photo layer surface is " + w + "x" + hh + " (view " + photoSurf.getWidth() + "x" + photoSurf.getHeight() + ")");
                photoSurfaceReady = true;
                if (pendingPhoto != null) { Runnable r = pendingPhoto; pendingPhoto = null; r.run(); }
                else if (showing == PHOTO && currentPhoto != null) drawPhoto(currentPhoto);
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { photoSurfaceReady = false; }
        });
        photoSurf.setVisibility(GONE);
        addView(photoSurf, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        overlay = new ImageView(ctx);
        overlay.setScaleType(ImageView.ScaleType.FIT_XY);
        overlay.setVisibility(GONE);
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        black = new View(ctx);
        black.setBackgroundColor(Color.BLACK);
        black.setAlpha(0f);
        addView(black, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    static String describeDisplay(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            Display d = ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            d.getRealMetrics(dm);
            sb.append("UI ").append(dm.widthPixels).append("x").append(dm.heightPixels).append(" @").append(dm.densityDpi).append("dpi");
            if (Build.VERSION.SDK_INT >= 23) {
                Display.Mode m = d.getMode();
                sb.append("; current mode ").append(m.getPhysicalWidth()).append("x").append(m.getPhysicalHeight())
                  .append("@").append(Math.round(m.getRefreshRate())).append("; modes:");
                for (Display.Mode x : d.getSupportedModes())
                    sb.append(' ').append(x.getPhysicalWidth()).append("x").append(x.getPhysicalHeight()).append("@").append(Math.round(x.getRefreshRate()));
            }
        } catch (Throwable t) { sb.append("display info error ").append(t); }
        return sb.toString();
    }

    /** The panel's physical resolution (e.g. 3840x2160), even though the app UI is 1920x1080. */
    static int[] panelSize(Context ctx) {
        int w = 1920, hh = 1080;
        try {
            Display d = ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= 23) {
                for (Display.Mode m : d.getSupportedModes()) {
                    int mw = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
                    int mh = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                    if ((long) mw * mh > (long) w * hh) { w = mw; hh = mh; }
                }
            }
        } catch (Throwable ignored) { }
        // Never larger than 4K UHD; that's what a photo buffer needs on this TV.
        if (w > 3840 || hh > 2160) { w = 3840; hh = 2160; }
        return new int[]{w, hh};
    }

    // ------------------------------------------------------------------ control

    public void setActive(boolean on) {
        if (on && !running) {
            running = true;
            setVisibility(VISIBLE);
            restart();
            h.postDelayed(watchPlaylist, 3000);
        } else if (!on && running) {
            running = false;
            h.removeCallbacks(watchPlaylist);
            clearAll();
            setVisibility(INVISIBLE);
        }
    }

    public void pause() {
        paused = true;
        h.removeCallbacks(advance);
        if (mp != null) try { if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) { }
    }

    public void resume() {
        if (!paused) return;
        paused = false;
        if (!running) return;
        if (showing == VIDEO && mp != null) {
            try { mp.start(); return; } catch (Exception ignored) { }
        }
        if (showing == PHOTO) {
            if (store.playlist().size() > 1) h.postDelayed(advance, store.imageSeconds() * 1000L);
        } else {
            next();
        }
    }

    public void release() {
        setActive(false);
        decoder.shutdownNow();
    }

    private String key() {
        StringBuilder sb = new StringBuilder();
        for (String s : store.playlist()) sb.append(s).append('/').append(new File(store.mediaDir(), s).lastModified()).append('|');
        sb.append(store.imageSeconds());
        return sb.toString();
    }

    private void restart() {
        playlistKey = key();
        idx = -1;
        next();
    }

    private void clearAll() {
        generation++;
        h.removeCallbacks(advance);
        pendingVideo = pendingPhoto = null;
        releasePlayer();
        overlay.animate().cancel();
        black.animate().cancel();
        overlay.setImageDrawable(null);
        overlay.setVisibility(GONE);
        black.setAlpha(0f);
        photoSurf.setVisibility(GONE);
        currentPhoto = currentPhotoSmall = null;
        showing = NONE;
    }

    // ------------------------------------------------------------------ loop

    private void next() {
        h.removeCallbacks(advance);
        if (!running || paused) return;
        List<String> items = store.playlist();
        if (items.isEmpty()) { clearAll(); return; }
        idx = (idx + 1) % items.size();
        final String name = items.get(idx);
        final boolean single = items.size() == 1;

        String t = store.transition();
        final long ms = store.transitionMs();
        final int gen = ++generation;

        if (showing == NONE || "none".equals(t) || ms <= 0) {
            show(name, single, gen, () -> { });
        } else if ("black".equals(t)) {
            black.animate().cancel();
            black.animate().alpha(1f).setDuration(ms / 2).withEndAction(() -> {
                if (gen != generation) return;
                show(name, single, gen, () -> black.animate().alpha(0f).setDuration(ms / 2));
            });
        } else { // cross dissolve: freeze the outgoing picture on top, put the new one underneath, fade
            snapshot(snap -> {
                if (gen != generation) return;
                overlay.animate().cancel();
                if (snap != null) overlay.setImageBitmap(snap);
                else overlay.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
                overlay.setAlpha(1f);
                overlay.setVisibility(VISIBLE);
                show(name, single, gen, () -> overlay.animate().alpha(0f).setDuration(ms).withEndAction(() -> {
                    if (gen != generation) return;
                    overlay.setVisibility(GONE);
                    overlay.setImageDrawable(null);
                }));
            });
        }
    }

    private void show(String name, boolean single, int gen, Runnable onShown) {
        File f = new File(store.mediaDir(), name);
        if ("video".equals(Store.typeOf(name))) playVideo(f, single, gen, onShown);
        else showPhoto(f, single, gen, onShown);
    }

    // ------------------------------------------------------------------ photos

    private void showPhoto(final File f, final boolean single, final int gen, final Runnable onShown) {
        final int sw = Math.max(getWidth(), 1280), sh = Math.max(getHeight(), 720);
        decoder.execute(() -> {
            final Bitmap full = decodeToFit(f, panelW, panelH);
            Diag.log("Photo " + f.getName() + (full == null ? ": could not decode" : ": drawing at " + full.getWidth() + "x" + full.getHeight()));
            final Bitmap small = full == null ? null : frame(full, sw, sh);
            h.post(() -> {
                if (gen != generation) return;
                if (full == null) { Log.w(TAG, "can't show " + f.getName()); h.postDelayed(advance, 1000); return; }
                displayBitmap(full, small, gen, onShown, () -> {
                    if (!single) h.postDelayed(advance, store.imageSeconds() * 1000L);
                });
            });
        });
    }

    /** Puts a full-resolution picture on the 4K photo layer (used for photos and the test pattern). */
    private void displayBitmap(final Bitmap full, final Bitmap small, final int gen,
                               final Runnable onShown, final Runnable afterShown) {
        Runnable draw = () -> {
            if (gen != generation) return;
            drawPhoto(full);
            releasePlayer();                       // photo now covers the video layer
            Bitmap old = currentPhoto;
            currentPhoto = full;
            currentPhotoSmall = small;
            showing = PHOTO;
            if (old != null && old != full) old.recycle();
            onShown.run();
            afterShown.run();
            // Ask the window to redraw so the photo layer's "hole" is punched through for sure.
            invalidate();
            requestLayout();
            h.postDelayed(this::checkPhotoLayer, 1500);
        };
        if (photoSurf.getVisibility() == VISIBLE && photoSurfaceReady) draw.run();
        else { pendingPhoto = draw; photoSurf.setVisibility(VISIBLE); }
    }

    /** Diagnostics: read back the photo layer and the view state, so we can tell why a picture isn't visible. */
    private void checkPhotoLayer() {
        final String state = "photo layer visible=" + (photoSurf.getVisibility() == VISIBLE) + " shown=" + photoSurf.isShown()
                + " size=" + photoSurf.getWidth() + "x" + photoSurf.getHeight()
                + " overlay=" + (overlay.getVisibility() == VISIBLE ? "on a=" + overlay.getAlpha() : "off")
                + " black a=" + black.getAlpha() + " showing=" + (showing == PHOTO ? "photo" : showing == VIDEO ? "video" : "none");
        if (Build.VERSION.SDK_INT < 24 || photoSurf.getVisibility() != VISIBLE || !photoSurfaceReady) { Diag.log(state); return; }
        try {
            final Bitmap rb = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(photoSurf, rb, result -> {
                if (result != android.view.PixelCopy.SUCCESS) { Diag.log(state + " | readback failed (" + result + ")"); return; }
                long sum = 0; int lit = 0;
                int[] px = new int[192 * 108];
                rb.getPixels(px, 0, 192, 0, 0, 192, 108);
                for (int c : px) { int l = ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255); sum += l; if (l > 60) lit++; }
                Diag.log(state + " | layer content: avg brightness " + (sum / px.length / 3) + "/255, " + (lit * 100 / px.length) + "% lit");
            }, h);
        } catch (Throwable t) {
            Diag.log(state + " | readback error " + t);
        }
    }

    /** Draws the photo centered, letterboxed in black, at the surface's full (4K) resolution. */
    private void drawPhoto(Bitmap bmp) {
        SurfaceHolder holder = photoSurf.getHolder();
        Surface s = holder.getSurface();
        if (s == null || !s.isValid()) return;
        Canvas c = null;
        boolean hw = false;
        try {
            if (Build.VERSION.SDK_INT >= 23) { c = s.lockHardwareCanvas(); hw = true; }
            else c = holder.lockCanvas();
        } catch (Throwable t) {
            c = holder.lockCanvas();
            hw = false;
        }
        if (c == null) return;
        try {
            c.drawColor(Color.BLACK);
            int left = (panelW - bmp.getWidth()) / 2, top = (panelH - bmp.getHeight()) / 2;
            c.drawBitmap(bmp, left, top, new Paint(Paint.FILTER_BITMAP_FLAG));
        } finally {
            if (hw) s.unlockCanvasAndPost(c); else holder.unlockCanvasAndPost(c);
        }
    }

    /** Decode at the best quality that fits the panel (no cropping), applying the phone's rotation. */
    static Bitmap decodeToFit(File f, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            int rot = exifRotation(f);
            int srcW = rot % 180 == 0 ? o.outWidth : o.outHeight;
            int srcH = rot % 180 == 0 ? o.outHeight : o.outWidth;
            // Size the photo will have on screen (fit inside the panel, no cropping).
            float fit = Math.min((float) maxW / srcW, (float) maxH / srcH);
            int fitW = Math.round(srcW * fit), fitH = Math.round(srcH * fit);
            // Decode at a power-of-two reduction that still has at least the on-screen size
            // (saves memory on 48 MP+ photos), then do one smooth resize to the exact size.
            int sample = 1;
            while (srcW / (sample * 2) >= fitW && srcH / (sample * 2) >= fitH) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
            if (b == null) return null;
            float scale = Math.min((float) maxW / (rot % 180 == 0 ? b.getWidth() : b.getHeight()),
                                   (float) maxH / (rot % 180 == 0 ? b.getHeight() : b.getWidth()));
            Matrix m = new Matrix();
            if (rot != 0) m.postRotate(rot);
            if (Math.abs(scale - 1f) > 0.001f) m.postScale(scale, scale);
            if (m.isIdentity()) return b;
            Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (out != b) b.recycle();
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "can't decode " + f.getName(), t);
            return null;
        }
    }

    static int exifRotation(File f) {
        if (Build.VERSION.SDK_INT < 24) return 0;
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(f.getAbsolutePath());
            switch (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
            }
        } catch (Exception ignored) { }
        return 0;
    }

    /** A screen-sized (1080p) copy of what's on screen, for the dissolve. */
    private static Bitmap frame(Bitmap src, int w, int hh) {
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        float s = Math.min((float) w / src.getWidth(), (float) hh / src.getHeight());
        int dw = Math.round(src.getWidth() * s), dh = Math.round(src.getHeight() * s);
        Rect dst = new Rect((w - dw) / 2, (hh - dh) / 2, (w - dw) / 2 + dw, (hh - dh) / 2 + dh);
        c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    // ------------------------------------------------------------------ 4K test pattern

    /** Shows a sharpness test for 30 seconds: 1-pixel stripes only look like stripes at true 4K. */
    public void showTestPattern() {
        if (!running) { Diag.log("Test pattern: announcements aren't showing right now (livestream mode?)"); return; }
        final int gen = ++generation;
        h.removeCallbacks(advance);
        black.animate().cancel();
        black.setAlpha(0f);
        final int sw = Math.max(getWidth(), 1280), sh = Math.max(getHeight(), 720);
        decoder.execute(() -> {
            final Bitmap pat = testPattern(panelW, panelH);
            final Bitmap small = frame(pat, sw, sh);
            h.post(() -> {
                if (gen != generation) return;
                // Same path a photo takes (dissolve over the outgoing picture), which is known to show.
                snapshot(snap -> {
                    if (gen != generation) return;
                    overlay.animate().cancel();
                    if (snap != null) overlay.setImageBitmap(snap);
                    else overlay.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
                    overlay.setAlpha(1f);
                    overlay.setVisibility(VISIBLE);
                    displayBitmap(pat, small, gen, () -> overlay.animate().alpha(0f).setDuration(600).withEndAction(() -> {
                        if (gen != generation) return;
                        overlay.setVisibility(GONE);
                        overlay.setImageDrawable(null);
                    }), () -> {
                        Diag.log("Test pattern shown at " + panelW + "x" + panelH);
                        h.postDelayed(() -> { if (gen == generation) restart(); }, 30000);
                    });
                });
            });
        });
    }

    private static Bitmap testPattern(int w, int hh) {
        Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        int[] row = new int[w];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < w; x++) {
                int c;
                if (y < hh / 2) {
                    // top: 1-pixel vertical stripes (left half) and 1-pixel horizontal stripes (right half)
                    c = x < w / 2 ? ((x & 1) == 0 ? 0xFFFFFFFF : 0xFF000000) : ((y & 1) == 0 ? 0xFFFFFFFF : 0xFF000000);
                } else {
                    // bottom: 2-pixel stripes, which look like stripes even at 1080p, for comparison
                    c = x < w / 2 ? (((x >> 1) & 1) == 0 ? 0xFFFFFFFF : 0xFF000000) : (((y >> 1) & 1) == 0 ? 0xFFFFFFFF : 0xFF000000);
                }
                row[x] = c;
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(hh / 22f);
        String[] labels = {"TOP: 1-pixel stripes - should look like fine stripes up close", "BOTTOM: 2-pixel stripes (for comparison)",
                "Drawn at " + w + "x" + hh};
        float y = hh / 2f - hh / 9f;
        for (String l : labels) {
            float tw = p.measureText(l);
            p.setColor(0xDD000000);
            c.drawRect((w - tw) / 2 - 30, y - p.getTextSize(), (w + tw) / 2 + 30, y + p.getTextSize() * 0.4f, p);
            p.setColor(0xFFFFD479);
            c.drawText(l, (w - tw) / 2, y, p);
            y += p.getTextSize() * 1.8f;
        }
        return b;
    }

    // ------------------------------------------------------------------ video

    private void playVideo(final File f, final boolean single, final int gen, final Runnable onShown) {
        if (!videoSurfaceReady) {
            pendingVideo = () -> playVideo(f, single, gen, onShown);
            return;
        }
        releasePlayer();
        final int[] meta = videoMeta(f);   // {width, height, rotation} from the file itself
        if (meta != null) {
            int dw = meta[2] % 180 == 0 ? meta[0] : meta[1], dh = meta[2] % 180 == 0 ? meta[1] : meta[0];
            Diag.log("Video " + f.getName() + ": file says " + meta[0] + "x" + meta[1] + " rotation " + meta[2] + " -> shows as " + dw + "x" + dh);
        }
        final MediaPlayer p = new MediaPlayer();
        mp = p;
        final boolean[] shown = {false};
        final Runnable reveal = () -> {
            if (shown[0] || gen != generation) return;
            shown[0] = true;
            photoSurf.setVisibility(GONE);   // uncover the video layer
            Bitmap old = currentPhoto;
            currentPhoto = currentPhotoSmall = null;
            if (old != null) old.recycle();
            showing = VIDEO;
            onShown.run();
        };
        try {
            p.setDisplay(videoSurf.getHolder());
            p.setDataSource(f.getAbsolutePath());
            p.setLooping(single);
            p.setScreenOnWhilePlaying(true);
            p.setOnVideoSizeChangedListener((x, vw, vh) -> {
                Diag.log("Video player reports " + vw + "x" + vh);
                fitVideo(meta, vw, vh);
            });
            p.setOnPreparedListener(x -> {
                if (gen != generation) return;
                fitVideo(meta, x.getVideoWidth(), x.getVideoHeight());
                if (!paused) x.start();
                h.postDelayed(reveal, 1500);   // fallback if the first-frame signal never comes
            });
            p.setOnInfoListener((x, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) h.post(reveal);
                return false;
            });
            p.setOnCompletionListener(x -> { if (gen == generation) next(); });
            p.setOnErrorListener((x, what, extra) -> {
                Log.w(TAG, "can't play " + f.getName() + " (" + what + "/" + extra + ")");
                if (gen == generation) { reveal.run(); h.postDelayed(advance, 1000); }
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "can't open " + f.getName(), e);
            h.postDelayed(advance, 1000);
        }
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.reset(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
    }

    /** {width, height, rotation} straight from the video file, or null. */
    static int[] videoMeta(File f) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            int w = Integer.parseInt(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int hh = Integer.parseInt(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rot = rs == null ? 0 : ((Integer.parseInt(rs) % 360) + 360) % 360;
            return new int[]{w, hh, rot};
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    /**
     * Shape the video layer from the file's own dimensions and rotation (some TV players report
     * the size swapped, which squeezed landscape video into a tall box). Falls back to what
     * the player reports.
     */
    private void fitVideo(int[] meta, int pw, int ph) {
        if (meta != null && meta[0] > 0 && meta[1] > 0) {
            boolean sideways = meta[2] % 180 != 0;
            fitVideo(sideways ? meta[1] : meta[0], sideways ? meta[0] : meta[1]);
        } else {
            fitVideo(pw, ph);
        }
    }

    /** Size the video layer to the video's shape (letterbox) instead of stretching it. */
    private void fitVideo(int vw, int vh) {
        int W = getWidth(), H = getHeight();
        if (vw <= 0 || vh <= 0 || W <= 0 || H <= 0) return;
        float s = Math.min((float) W / vw, (float) H / vh);
        int w = Math.round(vw * s), hh = Math.round(vh * s);
        LayoutParams lp = (LayoutParams) videoSurf.getLayoutParams();
        if (lp.width == w && lp.height == hh) return;
        lp.width = w;
        lp.height = hh;
        lp.gravity = Gravity.CENTER;
        videoSurf.setLayoutParams(lp);
        Diag.log("Video area set to " + w + "x" + hh + " of " + W + "x" + H);
    }

    // ------------------------------------------------------------------ snapshot for dissolves

    private interface SnapCallback { void done(Bitmap b); }

    private void snapshot(final SnapCallback cb) {
        if (showing == PHOTO) { cb.done(currentPhotoSmall); return; }
        if (showing == VIDEO && Build.VERSION.SDK_INT >= 24 && videoSurf.getWidth() > 0 && getWidth() > 0) {
            try {
                final Bitmap vb = Bitmap.createBitmap(videoSurf.getWidth(), videoSurf.getHeight(), Bitmap.Config.ARGB_8888);
                android.view.PixelCopy.request(videoSurf, vb, result -> {
                    if (result != android.view.PixelCopy.SUCCESS) { cb.done(null); return; }
                    Bitmap full = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(full);
                    c.drawColor(Color.BLACK);
                    c.drawBitmap(vb, videoSurf.getLeft(), videoSurf.getTop(), null);
                    cb.done(full);
                }, h);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "video snapshot failed", t);
            }
        }
        cb.done(null);
    }
}
