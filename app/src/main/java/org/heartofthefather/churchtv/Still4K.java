package org.heartofthefather.churchtv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fire TV scales app graphics down to 1080p; only its video hardware reaches the panel at 4K.
 * So each photo is turned into a short, still 4K video (using the TV's own hardware encoder)
 * and shown through the video player. The converted copies live in a cache folder and are
 * deleted together with the photo.
 */
public final class Still4K {

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static File dir;
    private static int W = 3840, H = 2160;
    private static volatile String encoderName = null;   // null = not checked yet, "" = none
    private static String[] encoder;                       // {codec name, mime, maxBitrate, cq}
    private static boolean encoderChecked = false;

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

    private static synchronized String[] findEncoder() {
        if (!encoderChecked) { encoder = searchEncoder(); encoderChecked = true; }
        return encoder;
    }

    private static String[] searchEncoder() {
        if (Build.VERSION.SDK_INT < 21) return null;
        String[] mimes = {MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC};
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        StringBuilder seen = new StringBuilder();
        for (String mime : mimes) {
            for (MediaCodecInfo info : infos) {
                if (!info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if (!t.equalsIgnoreCase(mime)) continue;
                    try {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                        MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                        boolean flexible = false;
                        for (int cf : caps.colorFormats) if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) flexible = true;
                        seen.append(info.getName()).append(" (").append(mime.substring(6)).append(", up to ")
                            .append(vc.getSupportedWidths().getUpper()).append("x").append(vc.getSupportedHeights().getUpper()).append(") ");
                        if (flexible && vc.isSizeSupported(W, H)) {
                            int maxRate = vc.getBitrateRange().getUpper();
                            boolean cq = caps.getEncoderCapabilities() != null && caps.getEncoderCapabilities()
                                    .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
                            Diag.log("4K photo encoder: using " + info.getName() + " for " + mime
                                    + " (max " + (maxRate / 1_000_000) + " Mbps" + (cq ? ", constant quality" : "") + ")");
                            return new String[]{info.getName(), mime, String.valueOf(maxRate), cq ? "cq" : ""};
                        }
                    } catch (Throwable ignored) { }
                }
            }
        }
        Diag.log("4K photo encoder: none can make " + W + "x" + H + " video. Encoders: " + (seen.length() == 0 ? "none" : seen));
        return null;
    }

    private static boolean convert(File photo) {
        String[] enc = findEncoder();
        encoderName = enc == null ? "" : enc[0];
        if (enc == null) return false;
        long t0 = System.currentTimeMillis();
        Bitmap full = AnnouncementView.decodeToFit(photo, W, H);
        if (full == null) { Diag.log("4K video for " + photo.getName() + ": can't read the photo"); return false; }
        // Letterbox onto a full-screen black frame.
        Bitmap frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(frame);
        c.drawColor(Color.BLACK);
        c.drawBitmap(full, (W - full.getWidth()) / 2f, (H - full.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        full.recycle();

        File out = cacheFile(photo);
        File tmp = new File(out.getPath() + ".tmp");
        boolean ok = false;
        MediaCodec codec = null;
        MediaMuxer mux = null;
        try {
            int[] argb = new int[W * H];
            frame.getPixels(argb, 0, W, 0, 0, W, H);
            frame.recycle();

            MediaFormat f = MediaFormat.createVideoFormat(enc[1], W, H);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            // A still picture: spend as many bits as the encoder allows on the one frame.
            f.setInteger(MediaFormat.KEY_BIT_RATE, Math.min(Integer.parseInt(enc[2]), 80_000_000));
            if ("cq".equals(enc[3])) {
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
                if (Build.VERSION.SDK_INT >= 28) f.setInteger(MediaFormat.KEY_QUALITY, 100);
            }
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 1);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (Build.VERSION.SDK_INT >= 24) {
                f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            }
            codec = MediaCodec.createByCodecName(enc[0]);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            mux = new MediaMuxer(tmp.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            final int frames = 2;            // same picture twice, one second apart
            int queued = 0;
            boolean inputDone = false, outputDone = false, muxStarted = false;
            int track = -1;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 60000;
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) throw new RuntimeException("encoder timed out");
                if (!inputDone) {
                    int ix = codec.dequeueInputBuffer(10000);
                    if (ix >= 0) {
                        long pts = queued * 1_000_000L;
                        if (queued < frames) {
                            Image img = codec.getInputImage(ix);
                            fillYuv(img, argb);
                            codec.queueInputBuffer(ix, 0, W * H * 3 / 2, pts, 0);
                            queued++;
                        } else {
                            codec.queueInputBuffer(ix, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }
                int ox = codec.dequeueOutputBuffer(bi, 10000);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(codec.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                } else if (ox >= 0) {
                    ByteBuffer data = codec.getOutputBuffer(ox);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    if (bi.size > 0 && muxStarted && data != null) {
                        data.position(bi.offset);
                        data.limit(bi.offset + bi.size);
                        mux.writeSampleData(track, data, bi);
                    }
                    codec.releaseOutputBuffer(ox, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            ok = muxStarted;
        } catch (Throwable t) {
            Diag.log("4K video for " + photo.getName() + " failed: " + t);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            try { if (mux != null) { mux.stop(); mux.release(); } } catch (Throwable ignored) { ok = false; }
        }
        if (ok && tmp.renameTo(out)) {
            Diag.log("4K video for " + photo.getName() + ": done in " + (System.currentTimeMillis() - t0) + " ms (" + (out.length() / 1024) + " KB)");
            return true;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return false;
    }

    /** RGB -> BT.709 limited-range YUV 4:2:0, written into whatever plane layout the encoder uses. */
    private static void fillYuv(Image img, int[] argb) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        byte[] rowY = new byte[W];
        for (int y = 0; y < H; y++) {
            int o = y * W;
            for (int x = 0; x < W; x++) {
                int c = argb[o + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                rowY[x] = (byte) (16 + ((47 * r + 157 * g + 16 * b + 128) >> 8));
            }
            if (yPix == 1) { yb.position(y * yRow); yb.put(rowY, 0, W); }
            else for (int x = 0; x < W; x++) yb.put(y * yRow + x * yPix, rowY[x]);
        }
        for (int y = 0; y < H / 2; y++) {
            for (int x = 0; x < W / 2; x++) {
                int i = (2 * y) * W + 2 * x;
                int r = 0, g = 0, b = 0;
                int[] idx = {i, i + 1, i + W, i + W + 1};
                for (int k : idx) { int c = argb[k]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; }
                r >>= 2; g >>= 2; b >>= 2;
                int u = 128 + ((-26 * r - 87 * g + 112 * b + 128) >> 8);
                int v = 128 + ((112 * r - 102 * g - 10 * b + 128) >> 8);
                ub.put(y * uRow + x * uPix, (byte) Math.max(16, Math.min(240, u)));
                vb.put(y * vRow + x * vPix, (byte) Math.max(16, Math.min(240, v)));
            }
        }
    }

    public static String status() {
        String e = encoderName;
        return e == null ? "not checked yet" : e.isEmpty() ? "no 4K encoder on this TV" : "using " + e;
    }
}
