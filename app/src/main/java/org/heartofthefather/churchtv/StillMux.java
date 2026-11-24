package org.heartofthefather.churchtv;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Packages a still 4K H.264 picture (encoded in the uploader's browser) into a small MP4 the
 * TV's video hardware can play. Pure Java (no Android classes) so it can be tested anywhere.
 *
 * Upload format ("CTV1"): "CTV1", u32 width, u32 height, u32 frameCount, then per frame:
 * u8 isKey, u32 length, then that many bytes of Annex-B H.264 (start codes, may include SPS/PPS).
 * Frames are one second apart.
 */
public final class StillMux {

    private StillMux() { }

    public static final class Frame {
        final byte[] annexB; final boolean key;
        Frame(byte[] a, boolean k) { annexB = a; key = k; }
    }

    public static Frame frame(byte[] annexB, boolean key) { return new Frame(annexB, key); }

    /** Parses an upload and writes the MP4. Throws with a readable message if anything is wrong. */
    public static void writeFromUpload(byte[] body, File out) throws IOException {
        if (body.length < 16 || !"CTV1".equals(new String(body, 0, 4, StandardCharsets.US_ASCII)))
            throw new IOException("Not a 4K still upload");
        int p = 4;
        int w = u32(body, p); p += 4;
        int h = u32(body, p); p += 4;
        int n = u32(body, p); p += 4;
        if (w < 16 || h < 16 || w > 8192 || h > 8192 || n < 1 || n > 10) throw new IOException("Bad still header");
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (p + 5 > body.length) throw new IOException("Truncated still upload");
            boolean key = body[p] != 0; p += 1;
            int len = u32(body, p); p += 4;
            if (len <= 0 || p + len > body.length) throw new IOException("Truncated still upload");
            byte[] f = new byte[len];
            System.arraycopy(body, p, f, 0, len);
            p += len;
            frames.add(new Frame(f, key));
        }
        write(frames, w, h, out);
    }

    /** Writes an MP4 with one H.264 video track, frames one second apart. */
    public static void write(List<Frame> frames, int width, int height, File out) throws IOException {
        byte[] sps = null, pps = null;
        List<byte[]> samples = new ArrayList<>();
        List<Boolean> keys = new ArrayList<>();
        for (Frame f : frames) {
            ByteArrayOutputStream sample = new ByteArrayOutputStream();
            DataOutputStream ds = new DataOutputStream(sample);
            for (byte[] nal : splitAnnexB(f.annexB)) {
                int type = nal[0] & 0x1f;
                if (type == 7) { if (sps == null) sps = nal; continue; }
                if (type == 8) { if (pps == null) pps = nal; continue; }
                if (type == 9) continue;                 // access unit delimiter: not needed in MP4
                ds.writeInt(nal.length);
                ds.write(nal);
            }
            if (sample.size() == 0) throw new IOException("A frame had no picture data");
            samples.add(sample.toByteArray());
            keys.add(f.key);
        }
        if (sps == null || pps == null) throw new IOException("The picture is missing its H.264 headers (SPS/PPS)");
        if (!keys.get(0)) throw new IOException("The first frame must be a key frame");

        final int timescale = 1000, dur = 1000;   // 1 second per frame
        // ---- moov
        ByteArrayOutputStream avcC = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(avcC);
        a.writeByte(1); a.writeByte(sps[1]); a.writeByte(sps[2]); a.writeByte(sps[3]);
        a.writeByte(0xFF);                 // 4-byte NAL lengths
        a.writeByte(0xE1); a.writeShort(sps.length); a.write(sps);
        a.writeByte(1); a.writeShort(pps.length); a.write(pps);

        byte[] avc1 = box("avc1", cat(
                new byte[6], s16(1),                 // reserved, data_reference_index
                new byte[16],                        // pre_defined/reserved
                s16(width), s16(height),
                i32(0x00480000), i32(0x00480000),    // 72 dpi
                i32(0), s16(1),                      // reserved, frame_count
                new byte[32],                        // compressor name
                s16(0x18), s16(-1),                  // depth, pre_defined
                box("avcC", avcC.toByteArray()),
                box("colr", cat("nclx".getBytes(StandardCharsets.US_ASCII), s16(1), s16(1), s16(1), new byte[]{0}))));
        byte[] stsd = fullBox("stsd", 0, cat(i32(1), avc1));
        byte[] stts = fullBox("stts", 0, cat(i32(1), i32(samples.size()), i32(dur)));
        ByteArrayOutputStream ss = new ByteArrayOutputStream();
        int keyCount = 0;
        for (int i = 0; i < keys.size(); i++) if (keys.get(i)) { ss.write(i32(i + 1)); keyCount++; }
        byte[] stss = fullBox("stss", 0, cat(i32(keyCount), ss.toByteArray()));
        byte[] stsc = fullBox("stsc", 0, cat(i32(1), i32(1), i32(samples.size()), i32(1)));
        ByteArrayOutputStream sz = new ByteArrayOutputStream();
        for (byte[] s : samples) sz.write(i32(s.length));
        byte[] stsz = fullBox("stsz", 0, cat(i32(0), i32(samples.size()), sz.toByteArray()));

        // mdat comes after ftyp+moov; compute where it starts to fill in the chunk offset.
        byte[] ftyp = box("ftyp", cat("isom".getBytes(StandardCharsets.US_ASCII), i32(512),
                "isomiso2avc1mp41".getBytes(StandardCharsets.US_ASCII)));
        long total = 0;
        for (byte[] s : samples) total += s.length;
        int movieDur = dur * samples.size();

        byte[] moovNoOffset = moov(width, height, timescale, movieDur, stsd, stts, stss, stsc, stsz, 0);
        long mdatStart = ftyp.length + moovNoOffset.length + 8;   // + mdat header
        byte[] moov = moov(width, height, timescale, movieDur, stsd, stts, stss, stsc, stsz, mdatStart);

        try (FileOutputStream fo = new FileOutputStream(out)) {
            fo.write(ftyp);
            fo.write(moov);
            fo.write(i32((int) (total + 8)));
            fo.write("mdat".getBytes(StandardCharsets.US_ASCII));
            for (byte[] s : samples) fo.write(s);
        }
    }

    private static byte[] moov(int w, int h, int timescale, int dur, byte[] stsd, byte[] stts, byte[] stss,
                               byte[] stsc, byte[] stsz, long mdatStart) throws IOException {
        byte[] stco = fullBox("stco", 0, cat(i32(1), i32((int) mdatStart)));
        byte[] stbl = box("stbl", cat(stsd, stts, stss, stsc, stsz, stco));
        byte[] dinf = box("dinf", fullBox("dref", 0, cat(i32(1), fullBox("url ", 1, new byte[0]))));
        byte[] vmhd = fullBox("vmhd", 1, new byte[8]);
        byte[] minf = box("minf", cat(vmhd, dinf, stbl));
        byte[] hdlr = fullBox("hdlr", 0, cat(i32(0), "vide".getBytes(StandardCharsets.US_ASCII), new byte[12],
                "VideoHandler\0".getBytes(StandardCharsets.US_ASCII)));
        byte[] mdhd = fullBox("mdhd", 0, cat(i32(0), i32(0), i32(timescale), i32(dur), s16(0x55c4), s16(0)));
        byte[] mdia = box("mdia", cat(mdhd, hdlr, minf));
        byte[] tkhd = fullBox("tkhd", 3, cat(i32(0), i32(0), i32(1), i32(0), i32(dur), new byte[8],
                s16(0), s16(0), s16(0), s16(0), matrix(), i32(w << 16), i32(h << 16)));
        byte[] trak = box("trak", cat(tkhd, mdia));
        byte[] mvhd = fullBox("mvhd", 0, cat(i32(0), i32(0), i32(timescale), i32(dur), i32(0x00010000), s16(0x0100),
                new byte[10], matrix(), new byte[24], i32(2)));
        return box("moov", cat(mvhd, trak));
    }

    static List<byte[]> splitAnnexB(byte[] b) {
        List<byte[]> out = new ArrayList<>();
        int i = 0, start = -1;
        while (i + 3 <= b.length) {
            boolean sc3 = b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1;
            boolean sc4 = i + 4 <= b.length && b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1;
            if (sc3 || sc4) {
                if (start >= 0) addNal(out, b, start, i);
                i += sc4 ? 4 : 3;
                start = i;
            } else i++;
        }
        if (start >= 0) addNal(out, b, start, b.length);
        return out;
    }

    private static void addNal(List<byte[]> out, byte[] b, int from, int to) {
        while (to > from && b[to - 1] == 0) to--;   // trailing zero bytes belong to the next start code
        if (to > from) { byte[] n = new byte[to - from]; System.arraycopy(b, from, n, 0, n.length); out.add(n); }
    }

    // ---- byte helpers
    private static int u32(byte[] b, int p) {
        return ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16) | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255);
    }
    private static byte[] i32(int v) { return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v}; }
    private static byte[] s16(int v) { return new byte[]{(byte) (v >>> 8), (byte) v}; }
    private static byte[] matrix() {
        return cat(i32(0x00010000), i32(0), i32(0), i32(0), i32(0x00010000), i32(0), i32(0), i32(0), i32(0x40000000));
    }
    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
    private static byte[] box(String type, byte[] payload) {
        return cat(i32(payload.length + 8), type.getBytes(StandardCharsets.US_ASCII), payload);
    }
    private static byte[] fullBox(String type, int flags, byte[] payload) {
        return box(type, cat(i32(flags & 0xFFFFFF), payload));
    }
}
