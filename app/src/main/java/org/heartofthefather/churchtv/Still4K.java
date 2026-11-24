package org.heartofthefather.churchtv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fire TV scales app graphics down to 1080p; only its video hardware reaches the panel at 4K.
 * So each photo is turned into a short, still 4K video (with our own H.264 encoder, since this
 * TV's hardware encoder stops at 1080p) and shown through the video player. The converted copies live in a cache folder and are
 * deleted together with the photo.
 */
public final class Still4K {

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static File dir;
    private static int W = 3840, H = 2160;

    private Still4K() { }

    public static synchronized void init(File filesDir, int panelW, int panelH) {
        dir = new File(filesDir, "still4k");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        W = panelW;
        H = panelH;
    }

    /** The converted video for a photo, or null if there isn't one (yet). */
    public static File videoFor(File photo) {
        if (dir == null) return null;
        File v = cacheFile(photo);
        return v.isFile() && v.length() > 0 ? v : null;
    }

    private static File cacheFile(File photo) {
        return new File(dir, photo.getName() + "." + photo.lastModified() + "." + W + "x" + H + ".mp4");
    }

    /** Remove converted copies whose photo is gone or has changed. */
    public static void cleanup(List<String> playlist, File mediaDir) {
        if (dir == null) return;
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (String n : playlist) keep.add(cacheFile(new File(mediaDir, n)).getName());
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (!keep.contains(f.getName())) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Converts every photo in the playlist that doesn't have a 4K video yet (in the background). */
    public static void convertAll(final List<String> playlist, final File mediaDir) {
        worker.execute(() -> {
            cleanup(playlist, mediaDir);
            for (String n : playlist) {
                if (!"image".equals(Store.typeOf(n))) continue;
                File photo = new File(mediaDir, n);
                if (photo.isFile() && videoFor(photo) == null) convert(photo);
            }
        });
    }

    // ------------------------------------------------------------------ encoder

    /**
     * Encodes the photo as a still 4K H.264 clip with our own encoder (this TV's hardware encoder
     * stops at 1080p). Verified bit-exact against a reference decoder.
     */
    private static boolean convert(File photo) {
        long t0 = System.currentTimeMillis();
        Bitmap full = AnnouncementView.decodeToFit(photo, W, H);
        if (full == null) { Diag.log("4K video for " + photo.getName() + ": can't read the photo"); return false; }
        Bitmap frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(frame);
        c.drawColor(Color.BLACK);
        c.drawBitmap(full, (W - full.getWidth()) / 2f, (H - full.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        full.recycle();
        byte[] y = new byte[W * H], u = new byte[W * H / 4], v = new byte[W * H / 4];
        {
            int[] argb = new int[W * H];
            frame.getPixels(argb, 0, W, 0, 0, W, H);
            frame.recycle();
            toYuv(argb, y, u, v);
        }
        File out = cacheFile(photo);
        File tmp = new File(out.getPath() + ".tmp");
        try {
            int qp = 18;                                   // very high quality
            H264Still.Result r = H264Still.encode(y, u, v, W, H, qp);
            if (r.idr.length > 10 * 1024 * 1024) {         // extremely detailed photo: keep the frame a sensible size
                qp = 22;
                r = H264Still.encode(y, u, v, W, H, qp);
            }
            java.util.List<StillMux.Frame> frames = new java.util.ArrayList<>();
            frames.add(StillMux.frame(annexB(r.sps, r.pps, r.idr), true));
            frames.add(StillMux.frame(annexB(r.skip), false));
            StillMux.write(frames, W, H, tmp);
            if (!tmp.renameTo(out)) throw new java.io.IOException("couldn't save");
            Diag.log("4K video for " + photo.getName() + ": done in " + (System.currentTimeMillis() - t0) + " ms, "
                    + (out.length() / 1024) + " KB (quality setting " + qp + ")");
            return true;
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            Diag.log("4K video for " + photo.getName() + " failed: " + t);
            return false;
        }
    }

    private static byte[] annexB(byte[]... nals) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] n : nals) { o.write(0); o.write(0); o.write(0); o.write(1); o.write(n, 0, n.length); }
        return o.toByteArray();
    }

    /** RGB -> BT.709 limited-range YUV 4:2:0 planes. */
    private static void toYuv(int[] argb, byte[] y, byte[] u, byte[] v) {
        for (int i = 0; i < W * H; i++) {
            int c = argb[i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            y[i] = (byte) (16 + ((47 * r + 157 * g + 16 * b + 128) >> 8));
        }
        int cw = W / 2;
        for (int yy = 0; yy < H / 2; yy++) for (int xx = 0; xx < cw; xx++) {
            int i = (2 * yy) * W + 2 * xx, r = 0, g = 0, b = 0;
            for (int k : new int[]{i, i + 1, i + W, i + W + 1}) { int c = argb[k]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; }
            r >>= 2; g >>= 2; b >>= 2;
            u[yy * cw + xx] = (byte) Math.max(16, Math.min(240, 128 + ((-26 * r - 87 * g + 112 * b + 128) >> 8)));
            v[yy * cw + xx] = (byte) Math.max(16, Math.min(240, 128 + ((112 * r - 102 * g - 10 * b + 128) >> 8)));
        }
    }

    public static String status() {
        return "built-in 4K encoder";
    }
}
