package org.heartofthefather.churchtv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays the announcement loop with the TV's own (hardware) video player, so phone videos
 * in HEVC/H.265 work, with cross-dissolve or fade-through-black transitions.
 *
 * Layers, bottom to top:
 *   image   – the current photo
 *   video   – TextureView the current video draws into
 *   overlay – frozen picture of the outgoing item, faded out for a cross dissolve
 *   black   – faded in/out for "fade through black"
 */
public class AnnouncementView extends FrameLayout implements TextureView.SurfaceTextureListener {

    private static final String TAG = "ChurchTV.Ann";

    private final Store store;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();

    private final ImageView image, overlay;
    private final TextureView video;
    private final View black;

    private Surface surface;
    private MediaPlayer mp;
    private Bitmap currentBitmap;
    private boolean showingVideo = false, showingSomething = false;
    private boolean running = false, paused = false;
    private int idx = -1;
    private String playlistKey = "";
    private int generation = 0;              // invalidates callbacks from older items
    private Runnable pendingVideo;           // waiting for the video surface

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

        image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        video = new TextureView(ctx);
        video.setSurfaceTextureListener(this);
        video.setAlpha(0f);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        overlay = new ImageView(ctx);
        overlay.setScaleType(ImageView.ScaleType.FIT_XY);   // snapshot is already screen-sized
        overlay.setVisibility(GONE);
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        black = new View(ctx);
        black.setBackgroundColor(Color.BLACK);
        black.setAlpha(0f);
        addView(black, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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
        if (showingVideo && mp != null) {
            try { mp.start(); return; } catch (Exception ignored) { }
        }
        if (showingSomething && !showingVideo) h.postDelayed(advance, store.imageSeconds() * 1000L);
        else next();
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
        pendingVideo = null;
        releasePlayer();
        image.animate().cancel();
        overlay.animate().cancel();
        black.animate().cancel();
        image.setImageDrawable(null);
        overlay.setImageDrawable(null);
        overlay.setVisibility(GONE);
        black.setAlpha(0f);
        video.setAlpha(0f);
        currentBitmap = null;
        showingVideo = showingSomething = false;
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

        if (!showingSomething || "none".equals(t) || ms <= 0) {
            show(name, single, gen, () -> { });
        } else if ("black".equals(t)) {
            black.animate().cancel();
            black.animate().alpha(1f).setDuration(ms / 2).withEndAction(() -> {
                if (gen != generation) return;
                show(name, single, gen, () -> black.animate().alpha(0f).setDuration(ms / 2));
            });
        } else { // cross dissolve
            Bitmap snap = snapshot();
            if (snap != null) {
                overlay.animate().cancel();
                overlay.setImageBitmap(snap);
                overlay.setAlpha(1f);
                overlay.setVisibility(VISIBLE);
            }
            show(name, single, gen, () -> overlay.animate().alpha(0f).setDuration(ms).withEndAction(() -> {
                if (gen != generation) return;
                overlay.setVisibility(GONE);
                overlay.setImageDrawable(null);
            }));
        }
    }

    /** Puts the item on screen, then calls onShown once its first frame is visible. */
    private void show(final String name, final boolean single, final int gen, final Runnable onShown) {
        final File f = new File(store.mediaDir(), name);
        if ("video".equals(Store.typeOf(name))) {
            playVideo(f, single, gen, onShown);
        } else {
            final int w = Math.max(getWidth(), 1280), hgt = Math.max(getHeight(), 720);
            decoder.execute(() -> {
                final Bitmap bmp = decodeImage(f, w, hgt);
                h.post(() -> {
                    if (gen != generation) return;
                    if (bmp == null) { h.postDelayed(advance, 1000); return; }
                    releasePlayer();
                    video.setAlpha(0f);
                    showingVideo = false;
                    currentBitmap = bmp;
                    image.setImageBitmap(bmp);
                    showingSomething = true;
                    onShown.run();
                    if (!single) h.postDelayed(advance, store.imageSeconds() * 1000L);
                });
            });
        }
    }

    private void playVideo(final File f, final boolean single, final int gen, final Runnable onShown) {
        if (surface == null) {               // TextureView not ready yet; try when it is
            pendingVideo = () -> playVideo(f, single, gen, onShown);
            return;
        }
        releasePlayer();
        final MediaPlayer p = new MediaPlayer();
        mp = p;
        final boolean[] shown = {false};
        final Runnable reveal = () -> {
            if (shown[0] || gen != generation) return;
            shown[0] = true;
            video.setAlpha(1f);
            image.setImageDrawable(null);
            currentBitmap = null;
            showingVideo = true;
            showingSomething = true;
            onShown.run();
        };
        try {
            p.setSurface(surface);
            p.setDataSource(f.getAbsolutePath());
            p.setLooping(single);
            p.setOnVideoSizeChangedListener((mpx, vw, vh) -> fitVideo(vw, vh));
            p.setOnPreparedListener(mpx -> {
                if (gen != generation) return;
                fitVideo(mpx.getVideoWidth(), mpx.getVideoHeight());
                if (!paused) mpx.start();
                // Fallback in case the "first frame" signal never arrives.
                h.postDelayed(reveal, 1500);
            });
            p.setOnInfoListener((mpx, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) h.post(reveal);
                return false;
            });
            p.setOnCompletionListener(mpx -> { if (gen == generation) next(); });
            p.setOnErrorListener((mpx, what, extra) -> {
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

    /** Letterbox the video inside the screen instead of stretching it. */
    private void fitVideo(int vw, int vh) {
        int W = video.getWidth(), H = video.getHeight();
        if (vw <= 0 || vh <= 0 || W <= 0 || H <= 0) return;
        float s = Math.min((float) W / vw, (float) H / vh);
        Matrix m = new Matrix();
        m.setScale(vw * s / W, vh * s / H, W / 2f, H / 2f);
        video.setTransform(m);
    }

    private Bitmap snapshot() {
        try {
            if (showingVideo) {
                Bitmap b = video.getBitmap();
                if (b == null) return null;
                // getBitmap() ignores the letterbox transform; draw it the way it looks on screen.
                Bitmap out = Bitmap.createBitmap(video.getWidth(), video.getHeight(), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(out);
                c.drawColor(Color.BLACK);
                c.drawBitmap(b, video.getTransform(null), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                return out;
            }
            if (currentBitmap != null && getWidth() > 0) {
                Bitmap out = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(out);
                c.drawColor(Color.BLACK);
                image.draw(c);
                return out;
            }
        } catch (Throwable t) {
            Log.w(TAG, "snapshot failed", t);
        }
        return null;
    }

    // ------------------------------------------------------------------ images

    /** Decodes at about screen size and applies the phone's rotation (EXIF) so photos aren't sideways. */
    static Bitmap decodeImage(File f, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            int rot = 0;
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    android.media.ExifInterface ex = new android.media.ExifInterface(f.getAbsolutePath());
                    switch (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        case android.media.ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                        case android.media.ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                        case android.media.ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
                    }
                } catch (Exception ignored) { }
            }
            int srcW = rot % 180 == 0 ? o.outWidth : o.outHeight;
            int srcH = rot % 180 == 0 ? o.outHeight : o.outWidth;
            int sample = 1;
            while (srcW / (sample * 2) >= maxW || srcH / (sample * 2) >= maxH) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
            if (b == null || rot == 0) return b;
            Matrix m = new Matrix();
            m.postRotate(rot);
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (Throwable t) {
            Log.w(TAG, "can't decode " + f.getName(), t);
            return null;
        }
    }

    // ------------------------------------------------------------------ surface

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
        surface = new Surface(st);
        if (pendingVideo != null) { Runnable r = pendingVideo; pendingVideo = null; r.run(); }
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {
        if (mp != null) try { fitVideo(mp.getVideoWidth(), mp.getVideoHeight()); } catch (Exception ignored) { }
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
}
