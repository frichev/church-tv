package org.heartofthefather.churchtv;

import java.io.ByteArrayOutputStream;

/**
 * A small, self-contained H.264 encoder for one still picture (any size that is a multiple of 16,
 * e.g. 3840x2160). The TV's own hardware encoder stops at 1080p, so 4K photos are encoded here.
 *
 * Intra 16x16 prediction (DC / vertical / horizontal), 4x4 integer transform, CAVLC, Main profile,
 * level 5.1, one slice, deblocking off. Produces SPS, PPS, an IDR picture and a second "all skip"
 * picture (so the clip lasts two seconds). Pure Java so it can be verified with desktop tools.
 */
public final class H264Still {

    public static final class Result {
        public byte[] sps, pps, idr, skip;       // NAL units without start codes
        public byte[] reconY, reconU, reconV;    // what a decoder will show (for testing)
    }

    private final int W, H, mbW, mbH, qp;
    private final byte[] srcY, srcU, srcV;
    private final int[] recY, recU, recV;        // reconstructed planes (decoder's view)
    private final int[] nzY;                     // total_coeff per luma 4x4 block (for nC)
    private final int[] nzU, nzV;                // per chroma 4x4 block
    private final BitWriter bw = new BitWriter();

    private H264Still(byte[] y, byte[] u, byte[] v, int w, int h, int qp) {
        W = w; H = h; mbW = w / 16; mbH = h / 16; this.qp = qp;
        srcY = y; srcU = u; srcV = v;
        recY = new int[w * h]; recU = new int[w * h / 4]; recV = new int[w * h / 4];
        nzY = new int[(w / 4) * (h / 4)];
        nzU = new int[(w / 8) * (h / 8)];
        nzV = new int[(w / 8) * (h / 8)];
    }

    /** qp 0..29 (chroma QP equals luma QP in that range). Lower = better quality, bigger file. */
    public static Result encode(byte[] y, byte[] u, byte[] v, int w, int h, int qp) {
        if (w % 16 != 0 || h % 16 != 0) throw new IllegalArgumentException("size must be a multiple of 16");
        if (qp < 0 || qp > 29) throw new IllegalArgumentException("qp 0..29");
        H264Still e = new H264Still(y, u, v, w, h, qp);
        Result r = new Result();
        r.sps = e.sps();
        r.pps = e.pps();
        r.idr = e.idrSlice();
        r.skip = e.skipSlice();
        r.reconY = toBytes(e.recY); r.reconU = toBytes(e.recU); r.reconV = toBytes(e.recV);
        return r;
    }

    private static byte[] toBytes(int[] a) {
        byte[] b = new byte[a.length];
        for (int i = 0; i < a.length; i++) b[i] = (byte) a[i];
        return b;
    }

    // =====================================================================================
    // Parameter sets and slice headers

    private byte[] sps() {
        BitWriter b = new BitWriter();
        b.u(8, 77);            // profile_idc: Main
        b.u(8, 0x40);          // constraint_set1_flag (Main-compatible)
        b.u(8, 51);            // level_idc 5.1
        b.ue(0);               // seq_parameter_set_id
        b.ue(0);               // log2_max_frame_num_minus4
        b.ue(2);               // pic_order_cnt_type 2 (no B-frames)
        b.ue(1);               // max_num_ref_frames
        b.u(1, 0);             // gaps_in_frame_num_value_allowed_flag
        b.ue(mbW - 1);
        b.ue(mbH - 1);
        b.u(1, 1);             // frame_mbs_only_flag
        b.u(1, 1);             // direct_8x8_inference_flag
        b.u(1, 0);             // frame_cropping_flag
        b.u(1, 1);             // vui_parameters_present_flag
        b.u(1, 0);             //   aspect_ratio_info_present_flag
        b.u(1, 0);             //   overscan_info_present_flag
        b.u(1, 1);             //   video_signal_type_present_flag
        b.u(3, 5);             //     video_format: unspecified
        b.u(1, 0);             //     video_full_range_flag: limited (16-235)
        b.u(1, 1);             //     colour_description_present_flag
        b.u(8, 1); b.u(8, 1); b.u(8, 1);   // BT.709 primaries, transfer, matrix
        b.u(1, 0);             //   chroma_loc_info_present_flag
        b.u(1, 0);             //   timing_info_present_flag
        b.u(1, 0);             //   nal_hrd_parameters_present_flag
        b.u(1, 0);             //   vcl_hrd_parameters_present_flag
        b.u(1, 0);             //   pic_struct_present_flag
        b.u(1, 0);             //   bitstream_restriction_flag
        b.trailing();
        return nal(3, 7, b.bytes());
    }

    private byte[] pps() {
        BitWriter b = new BitWriter();
        b.ue(0); b.ue(0);      // pps id, sps id
        b.u(1, 0);             // entropy_coding_mode_flag: CAVLC
        b.u(1, 0);             // bottom_field_pic_order_in_frame_present_flag
        b.ue(0);               // num_slice_groups_minus1
        b.ue(0); b.ue(0);      // num_ref_idx_l0/l1_default_active_minus1
        b.u(1, 0); b.u(2, 0);  // weighted_pred_flag, weighted_bipred_idc
        b.se(0); b.se(0);      // pic_init_qp_minus26, pic_init_qs_minus26
        b.se(0);               // chroma_qp_index_offset
        b.u(1, 1);             // deblocking_filter_control_present_flag
        b.u(1, 0);             // constrained_intra_pred_flag
        b.u(1, 0);             // redundant_pic_cnt_present_flag
        b.trailing();
        return nal(3, 8, b.bytes());
    }

    private byte[] skipSlice() {
        BitWriter b = new BitWriter();
        b.ue(0);               // first_mb_in_slice
        b.ue(5);               // slice_type P (all slices)
        b.ue(0);               // pps id
        b.u(4, 1);             // frame_num
        b.u(1, 0);             // num_ref_idx_active_override_flag
        b.u(1, 0);             // ref_pic_list_modification_flag_l0
        // nal_ref_idc == 0: no dec_ref_pic_marking
        b.se(qp - 26);         // slice_qp_delta
        b.ue(1);               // disable_deblocking_filter_idc
        b.ue(mbW * mbH);       // mb_skip_run: every macroblock copies the previous picture
        b.trailing();
        return nal(0, 1, b.bytes());
    }

    private byte[] idrSlice() {
        BitWriter b = bw;
        b.ue(0);               // first_mb_in_slice
        b.ue(7);               // slice_type I (all slices)
        b.ue(0);               // pps id
        b.u(4, 0);             // frame_num
        b.ue(0);               // idr_pic_id
        b.u(1, 0); b.u(1, 0);  // no_output_of_prior_pics_flag, long_term_reference_flag
        b.se(qp - 26);         // slice_qp_delta
        b.ue(1);               // disable_deblocking_filter_idc: off
        for (int my = 0; my < mbH; my++)
            for (int mx = 0; mx < mbW; mx++)
                macroblock(mx, my);
        b.trailing();
        return nal(3, 5, b.bytes());
    }

    private static byte[] nal(int refIdc, int type, byte[] rbsp) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(rbsp.length + rbsp.length / 64 + 8);
        o.write((refIdc << 5) | type);
        int zeros = 0;
        for (byte x : rbsp) {
            int v = x & 255;
            if (zeros >= 2 && v <= 3) { o.write(3); zeros = 0; }   // emulation prevention
            o.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return o.toByteArray();
    }

    // =====================================================================================
    // Macroblock coding

    // 4x4 frame zig-zag scan, as raster indices (y*4+x).
    static final int[] ZIGZAG = {0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15};
    // Quantisation multipliers and dequantisation scales per qp%6 and coefficient position class.
    private static final int[][] MF = {{13107, 5243, 8066}, {11916, 4660, 7490}, {10082, 4194, 6554},
            {9362, 3647, 5825}, {8192, 3355, 5243}, {7282, 2893, 4559}};
    private static final int[][] V = {{10, 16, 13}, {11, 18, 14}, {13, 20, 16}, {14, 23, 18}, {16, 25, 20}, {18, 29, 23}};

    private static int posClass(int idx) {          // idx = y*4+x
        int x = idx & 3, y = idx >> 2;
        if ((x & 1) == 0 && (y & 1) == 0) return 0;
        if ((x & 1) == 1 && (y & 1) == 1) return 1;
        return 2;
    }

    private void macroblock(int mx, int my) {
        final int x0 = mx * 16, y0 = my * 16;
        final boolean left = mx > 0, top = my > 0;

        // ---------- luma prediction: choose DC / vertical / horizontal by SAD
        int[][] preds = new int[3][];
        preds[2] = predDC16(x0, y0, left, top);
        if (top) preds[0] = predV16(x0, y0);
        if (left) preds[1] = predH16(x0, y0);
        int best = 2; long bestSad = Long.MAX_VALUE;
        for (int m = 0; m < 3; m++) {
            if (preds[m] == null) continue;
            long sad = 0;
            for (int yy = 0; yy < 16; yy++) for (int xx = 0; xx < 16; xx++)
                sad += Math.abs((srcY[(y0 + yy) * W + x0 + xx] & 255) - preds[m][yy * 16 + xx]);
            if (sad < bestSad) { bestSad = sad; best = m; }
        }
        int[] pred = preds[best];

        // ---------- luma transform + quantisation
        int[][] ac = new int[16][16];   // per 4x4 block (blkIdx order), raster coefficients (DC slot unused)
        int[] dcIn = new int[16];       // 4x4 matrix of block DCs, raster by block position
        for (int blk = 0; blk < 16; blk++) {
            int bx = blkX(blk), by = blkY(blk);
            int[] r = new int[16];
            for (int yy = 0; yy < 4; yy++) for (int xx = 0; xx < 4; xx++) {
                int px = bx + xx, py = by + yy;
                r[yy * 4 + xx] = (srcY[(y0 + py) * W + x0 + px] & 255) - pred[py * 16 + px];
            }
            int[] c = fdct(r);
            dcIn[(by / 4) * 4 + bx / 4] = c[0];
            int qbits = 15 + qp / 6, f = (1 << qbits) / 3;
            for (int i = 1; i < 16; i++) ac[blk][i] = quant(c[i], MF[qp % 6][posClass(i)], f, qbits);
        }
        int[] dcT = hadamard4(dcIn);
        int[] dcQ = new int[16];
        {
            int qbits = 15 + qp / 6, f = (1 << (qbits + 1)) / 3;
            for (int i = 0; i < 16; i++) dcQ[i] = quant(dcT[i] / 2, MF[qp % 6][0], f, qbits + 1) ;
        }
        boolean anyAc = false;
        for (int blk = 0; blk < 16 && !anyAc; blk++) for (int i = 1; i < 16; i++) if (ac[blk][i] != 0) { anyAc = true; break; }

        // ---------- chroma (DC prediction), both components
        int[][] chAc = new int[8][16];  // Cb blocks 0..3 then Cr 0..3
        int[][] chDc = new int[2][4];
        int[][] chPred = new int[2][];
        boolean chAnyDc = false, chAnyAc = false;
        for (int comp = 0; comp < 2; comp++) {
            byte[] src = comp == 0 ? srcU : srcV;
            int[] rec = comp == 0 ? recU : recV;
            int cw = W / 2, cx0 = mx * 8, cy0 = my * 8;
            int[] p = predChromaDC(rec, cw, cx0, cy0, left, top);
            chPred[comp] = p;
            int[] dcs = new int[4];
            for (int b = 0; b < 4; b++) {
                int bx = (b & 1) * 4, by = (b >> 1) * 4;
                int[] r = new int[16];
                for (int yy = 0; yy < 4; yy++) for (int xx = 0; xx < 4; xx++)
                    r[yy * 4 + xx] = (src[(cy0 + by + yy) * cw + cx0 + bx + xx] & 255) - p[(by + yy) * 8 + bx + xx];
                int[] c = fdct(r);
                dcs[b] = c[0];
                int qbits = 15 + qp / 6, f = (1 << qbits) / 3;
                for (int i = 1; i < 16; i++) {
                    chAc[comp * 4 + b][i] = quant(c[i], MF[qp % 6][posClass(i)], f, qbits);
                    if (chAc[comp * 4 + b][i] != 0) chAnyAc = true;
                }
            }
            // 2x2 Hadamard of the DCs
            int a = dcs[0], b1 = dcs[1], c1 = dcs[2], d = dcs[3];
            int[] t = {a + b1 + c1 + d, a - b1 + c1 - d, a + b1 - c1 - d, a - b1 - c1 + d};
            int qbits = 15 + qp / 6, f = (1 << (qbits + 1)) / 3;
            for (int i = 0; i < 4; i++) {
                chDc[comp][i] = quant(t[i], MF[qp % 6][0], f, qbits + 1);
                if (chDc[comp][i] != 0) chAnyDc = true;
            }
        }
        int cbpChroma = chAnyAc ? 2 : (chAnyDc ? 1 : 0);
        int cbpLuma = anyAc ? 15 : 0;

        // ---------- write macroblock syntax
        int mbType = 1 + best + 4 * cbpChroma + (cbpLuma == 15 ? 12 : 0);
        bw.ue(mbType);
        bw.ue(0);                 // intra_chroma_pred_mode: DC
        bw.se(0);                 // mb_qp_delta

        // Intra16x16 DC levels (zig-zag of the 4x4 DC matrix)
        int[] dcScan = new int[16];
        for (int i = 0; i < 16; i++) dcScan[i] = dcQ[ZIGZAG[i]];
        residualBlock(dcScan, 16, nCLuma(x0 / 4, y0 / 4));
        int bw4 = W / 4;
        for (int blk = 0; blk < 16; blk++) {
            int gx = (x0 + blkX(blk)) / 4, gy = (y0 + blkY(blk)) / 4;
            int tc = 0;
            if (cbpLuma == 15) {
                int[] scan = new int[15];
                for (int i = 1; i < 16; i++) scan[i - 1] = ac[blk][ZIGZAG[i]];
                tc = residualBlock(scan, 15, nCLuma(gx, gy));
            }
            nzY[gy * bw4 + gx] = tc;
        }
        if (cbpChroma != 0) {
            for (int comp = 0; comp < 2; comp++) residualBlock(chDc[comp], 4, -1);
        }
        int bw8 = W / 8;
        for (int comp = 0; comp < 2; comp++) {
            int[] nz = comp == 0 ? nzU : nzV;
            for (int b = 0; b < 4; b++) {
                int gx = mx * 2 + (b & 1), gy = my * 2 + (b >> 1);
                int tc = 0;
                if (cbpChroma == 2) {
                    int[] scan = new int[15];
                    for (int i = 1; i < 16; i++) scan[i - 1] = chAc[comp * 4 + b][ZIGZAG[i]];
                    tc = residualBlock(scan, 15, nCChroma(nz, gx, gy));
                }
                nz[gy * bw8 + gx] = tc;
            }
        }

        // ---------- reconstruct exactly as a decoder does (needed to predict the next macroblocks)
        // Luma DC: inverse Hadamard + scaling
        int[] dcF = hadamard4(dcQ);
        int[] dcY = new int[16];
        int ls0 = 16 * V[qp % 6][0];
        for (int i = 0; i < 16; i++) {
            if (qp >= 36) dcY[i] = (dcF[i] * ls0) << (qp / 6 - 6);
            else dcY[i] = (dcF[i] * ls0 + (1 << (5 - qp / 6))) >> (6 - qp / 6);
        }
        for (int blk = 0; blk < 16; blk++) {
            int bx = blkX(blk), by = blkY(blk);
            int[] d = new int[16];
            d[0] = dcY[(by / 4) * 4 + bx / 4];
            if (cbpLuma == 15) for (int i = 1; i < 16; i++) d[i] = dequant(ac[blk][i], i, qp);
            int[] res = idct(d);
            for (int yy = 0; yy < 4; yy++) for (int xx = 0; xx < 4; xx++) {
                int px = bx + xx, py = by + yy;
                recY[(y0 + py) * W + x0 + px] = clip(pred[py * 16 + px] + res[yy * 4 + xx]);
            }
        }
        for (int comp = 0; comp < 2; comp++) {
            int[] rec = comp == 0 ? recU : recV;
            int cw = W / 2, cx0 = mx * 8, cy0 = my * 8;
            int[] c = chDc[comp];
            int[] f = {c[0] + c[1] + c[2] + c[3], c[0] - c[1] + c[2] - c[3], c[0] + c[1] - c[2] - c[3], c[0] - c[1] - c[2] + c[3]};
            for (int b = 0; b < 4; b++) {
                int bx = (b & 1) * 4, by = (b >> 1) * 4;
                int[] d = new int[16];
                d[0] = ((f[b] * 16 * V[qp % 6][0]) << (qp / 6)) >> 5;
                if (cbpChroma == 2) for (int i = 1; i < 16; i++) d[i] = dequant(chAc[comp * 4 + b][i], i, qp);
                int[] res = idct(d);
                for (int yy = 0; yy < 4; yy++) for (int xx = 0; xx < 4; xx++)
                    rec[(cy0 + by + yy) * cw + cx0 + bx + xx] = clip(chPred[comp][(by + yy) * 8 + bx + xx] + res[yy * 4 + xx]);
            }
        }
    }

    // Luma 4x4 block position inside the macroblock for blkIdx (8x8 quadrants, then 4x4 inside).
    private static int blkX(int blk) { return ((blk >> 2) & 1) * 8 + (blk & 1) * 4; }
    private static int blkY(int blk) { return ((blk >> 3) & 1) * 8 + ((blk >> 1) & 1) * 4; }

    private int nCLuma(int gx, int gy) {
        int bw4 = W / 4;
        boolean a = gx > 0, b = gy > 0;
        int nA = a ? nzY[gy * bw4 + gx - 1] : 0, nB = b ? nzY[(gy - 1) * bw4 + gx] : 0;
        if (a && b) return (nA + nB + 1) >> 1;
        if (a) return nA;
        if (b) return nB;
        return 0;
    }

    private int nCChroma(int[] nz, int gx, int gy) {
        int bw8 = W / 8;
        boolean a = gx > 0, b = gy > 0;
        int nA = a ? nz[gy * bw8 + gx - 1] : 0, nB = b ? nz[(gy - 1) * bw8 + gx] : 0;
        if (a && b) return (nA + nB + 1) >> 1;
        if (a) return nA;
        if (b) return nB;
        return 0;
    }

    // ---------- prediction
    private int[] predDC16(int x0, int y0, boolean left, boolean top) {
        int s = 0, dc;
        if (left && top) {
            for (int i = 0; i < 16; i++) s += recY[(y0 - 1) * W + x0 + i] + recY[(y0 + i) * W + x0 - 1];
            dc = (s + 16) >> 5;
        } else if (left) {
            for (int i = 0; i < 16; i++) s += recY[(y0 + i) * W + x0 - 1];
            dc = (s + 8) >> 4;
        } else if (top) {
            for (int i = 0; i < 16; i++) s += recY[(y0 - 1) * W + x0 + i];
            dc = (s + 8) >> 4;
        } else dc = 128;
        int[] p = new int[256];
        java.util.Arrays.fill(p, dc);
        return p;
    }

    private int[] predV16(int x0, int y0) {
        int[] p = new int[256];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) p[y * 16 + x] = recY[(y0 - 1) * W + x0 + x];
        return p;
    }

    private int[] predH16(int x0, int y0) {
        int[] p = new int[256];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) p[y * 16 + x] = recY[(y0 + y) * W + x0 - 1];
        return p;
    }

    private static int[] predChromaDC(int[] rec, int cw, int cx0, int cy0, boolean left, boolean top) {
        int[] p = new int[64];
        for (int b = 0; b < 4; b++) {
            int xO = (b & 1) * 4, yO = (b >> 1) * 4;
            int sT = 0, sL = 0;
            if (top) for (int i = 0; i < 4; i++) sT += rec[(cy0 - 1) * cw + cx0 + xO + i];
            if (left) for (int i = 0; i < 4; i++) sL += rec[(cy0 + yO + i) * cw + cx0 - 1];
            int dc;
            if ((xO == 0 && yO == 0) || (xO > 0 && yO > 0)) {
                if (top && left) dc = (sT + sL + 4) >> 3;
                else if (left) dc = (sL + 2) >> 2;
                else if (top) dc = (sT + 2) >> 2;
                else dc = 128;
            } else if (xO > 0) {             // top-right block prefers the samples above
                if (top) dc = (sT + 2) >> 2;
                else if (left) dc = (sL + 2) >> 2;
                else dc = 128;
            } else {                         // bottom-left block prefers the samples on the left
                if (left) dc = (sL + 2) >> 2;
                else if (top) dc = (sT + 2) >> 2;
                else dc = 128;
            }
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) p[(yO + y) * 8 + xO + x] = dc;
        }
        return p;
    }

    // ---------- transforms
    private static int[] fdct(int[] r) {   // forward 4x4 core transform, raster in/out
        int[] t = new int[16], o = new int[16];
        for (int y = 0; y < 4; y++) {
            int a = r[y * 4], b = r[y * 4 + 1], c = r[y * 4 + 2], d = r[y * 4 + 3];
            int s03 = a + d, d03 = a - d, s12 = b + c, d12 = b - c;
            t[y * 4] = s03 + s12; t[y * 4 + 2] = s03 - s12;
            t[y * 4 + 1] = 2 * d03 + d12; t[y * 4 + 3] = d03 - 2 * d12;
        }
        for (int x = 0; x < 4; x++) {
            int a = t[x], b = t[4 + x], c = t[8 + x], d = t[12 + x];
            int s03 = a + d, d03 = a - d, s12 = b + c, d12 = b - c;
            o[x] = s03 + s12; o[8 + x] = s03 - s12;
            o[4 + x] = 2 * d03 + d12; o[12 + x] = d03 - 2 * d12;
        }
        return o;
    }

    /** Inverse 4x4 transform as specified (rows first, then columns), including the final (x+32)>>6. */
    private static int[] idct(int[] d) {
        int[] f = new int[16], o = new int[16];
        for (int y = 0; y < 4; y++) {
            int d0 = d[y * 4], d1 = d[y * 4 + 1], d2 = d[y * 4 + 2], d3 = d[y * 4 + 3];
            int e0 = d0 + d2, e1 = d0 - d2, e2 = (d1 >> 1) - d3, e3 = d1 + (d3 >> 1);
            f[y * 4] = e0 + e3; f[y * 4 + 1] = e1 + e2; f[y * 4 + 2] = e1 - e2; f[y * 4 + 3] = e0 - e3;
        }
        for (int x = 0; x < 4; x++) {
            int f0 = f[x], f1 = f[4 + x], f2 = f[8 + x], f3 = f[12 + x];
            int g0 = f0 + f2, g1 = f0 - f2, g2 = (f1 >> 1) - f3, g3 = f1 + (f3 >> 1);
            o[x] = (g0 + g3 + 32) >> 6; o[4 + x] = (g1 + g2 + 32) >> 6;
            o[8 + x] = (g1 - g2 + 32) >> 6; o[12 + x] = (g0 - g3 + 32) >> 6;
        }
        return o;
    }

    private static int[] hadamard4(int[] m) {   // 4x4 Hadamard (rows then columns), raster
        int[] t = new int[16], o = new int[16];
        for (int y = 0; y < 4; y++) {
            int a = m[y * 4], b = m[y * 4 + 1], c = m[y * 4 + 2], d = m[y * 4 + 3];
            t[y * 4] = a + b + c + d; t[y * 4 + 1] = a + b - c - d; t[y * 4 + 2] = a - b - c + d; t[y * 4 + 3] = a - b + c - d;
        }
        for (int x = 0; x < 4; x++) {
            int a = t[x], b = t[4 + x], c = t[8 + x], d = t[12 + x];
            o[x] = a + b + c + d; o[4 + x] = a + b - c - d; o[8 + x] = a - b - c + d; o[12 + x] = a - b + c - d;
        }
        return o;
    }

    private static int quant(int c, int mf, int f, int qbits) {
        int a = (int) (((long) Math.abs(c) * mf + f) >> qbits);
        return c < 0 ? -a : a;
    }

    private static int dequant(int level, int idx, int qp) {
        int ls = 16 * V[qp % 6][posClass(idx)];
        if (qp >= 24) return (level * ls) << (qp / 6 - 4);
        return (level * ls + (1 << (3 - qp / 6))) >> (4 - qp / 6);
    }

    private static int clip(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    // =====================================================================================
    // CAVLC residual coding

    // coeff_token tables: [nC class][totalCoeff*4 + trailingOnes] -> length / code
    private static final int[][] CT_LEN = {
        {1,0,0,0, 6,2,0,0, 8,6,3,0, 9,8,7,5, 10,9,8,6, 11,10,9,7, 13,11,10,8, 13,13,11,9, 13,13,13,10,
         14,14,13,11, 14,14,14,13, 15,15,14,14, 15,15,15,14, 16,15,15,15, 16,16,16,15, 16,16,16,16, 16,16,16,16},
        {2,0,0,0, 6,2,0,0, 6,5,3,0, 7,6,6,4, 8,6,6,4, 8,7,7,5, 9,8,8,6, 11,9,9,6, 11,11,11,7,
         12,11,11,9, 12,12,12,11, 12,12,12,11, 13,13,13,12, 13,13,13,13, 13,14,13,13, 14,14,14,13, 14,14,14,14},
        {4,0,0,0, 6,4,0,0, 6,5,4,0, 6,5,5,4, 7,5,5,4, 7,5,5,4, 7,6,6,4, 7,6,6,4, 8,7,7,5,
         8,8,7,6, 9,8,8,7, 9,9,8,8, 9,9,9,8, 10,9,9,9, 10,10,10,10, 10,10,10,10, 10,10,10,10},
        {6,0,0,0, 6,6,0,0, 6,6,6,0, 6,6,6,6, 6,6,6,6, 6,6,6,6, 6,6,6,6, 6,6,6,6, 6,6,6,6,
         6,6,6,6, 6,6,6,6, 6,6,6,6, 6,6,6,6, 6,6,6,6, 6,6,6,6, 6,6,6,6, 6,6,6,6},
    };
    private static final int[][] CT_CODE = {
        {1,0,0,0, 5,1,0,0, 7,4,1,0, 7,6,5,3, 7,6,5,3, 7,6,5,4, 15,6,5,4, 11,14,5,4, 8,10,13,4,
         15,14,9,4, 11,10,13,12, 15,14,9,12, 11,10,13,8, 15,1,9,12, 11,14,13,8, 7,10,9,12, 4,6,5,8},
        {3,0,0,0, 11,2,0,0, 7,7,3,0, 7,10,9,5, 7,6,5,4, 4,6,5,6, 7,6,5,8, 15,6,5,4, 11,14,13,4,
         15,10,9,4, 11,14,13,12, 8,10,9,8, 15,14,13,12, 11,10,9,12, 7,11,6,8, 9,8,10,1, 7,6,5,4},
        {15,0,0,0, 15,14,0,0, 11,15,13,0, 8,12,14,12, 15,10,11,11, 11,8,9,10, 9,14,13,9, 8,10,9,8, 15,14,13,13,
         11,14,10,12, 15,10,13,12, 11,14,9,12, 8,10,13,8, 13,7,9,12, 9,12,11,10, 5,8,7,6, 1,4,3,2},
        {3,0,0,0, 0,1,0,0, 4,5,6,0, 8,9,10,11, 12,13,14,15, 16,17,18,19, 20,21,22,23, 24,25,26,27, 28,29,30,31,
         32,33,34,35, 36,37,38,39, 40,41,42,43, 44,45,46,47, 48,49,50,51, 52,53,54,55, 56,57,58,59, 60,61,62,63},
    };
    private static final int[] CDC_CT_LEN = {2,0,0,0, 6,1,0,0, 6,6,3,0, 6,7,7,6, 6,8,8,7};
    private static final int[] CDC_CT_CODE = {1,0,0,0, 7,1,0,0, 4,6,1,0, 3,3,2,5, 2,3,2,0};

    // total_zeros for 4x4 blocks: [totalCoeff-1][totalZeros]
    private static final int[][] TZ_LEN = {
        {1,3,3,4,4,5,5,6,6,7,7,8,8,9,9,9}, {3,3,3,3,3,4,4,4,4,5,5,6,6,6,6}, {4,3,3,3,4,4,3,3,4,5,5,6,5,6},
        {5,3,4,4,3,3,3,4,3,4,5,5,5}, {4,4,4,3,3,3,3,3,4,5,4,5}, {6,5,3,3,3,3,3,3,4,3,6}, {6,5,3,3,3,2,3,4,3,6},
        {6,4,5,3,2,2,3,3,6}, {6,6,4,2,2,3,2,5}, {5,5,3,2,2,2,4}, {4,4,3,3,1,3}, {4,4,2,1,3}, {3,3,1,2}, {2,2,1}, {1,1}};
    private static final int[][] TZ_CODE = {
        {1,3,2,3,2,3,2,3,2,3,2,3,2,3,2,1}, {7,6,5,4,3,5,4,3,2,3,2,3,2,1,0}, {5,7,6,5,4,3,4,3,2,3,2,1,1,0},
        {3,7,5,4,6,5,4,3,3,2,2,1,0}, {5,4,3,7,6,5,4,3,2,1,1,0}, {1,1,7,6,5,4,3,2,1,1,0}, {1,1,5,4,3,3,2,1,1,0},
        {1,1,1,3,3,2,2,1,0}, {1,0,1,3,2,1,1,1}, {1,0,1,3,2,1,1}, {0,1,1,2,1,3}, {0,1,1,1,1}, {0,1,1,1}, {0,1,1}, {0,1}};
    // total_zeros for chroma DC (2x2): [totalCoeff-1][totalZeros]
    private static final int[][] CDC_TZ_LEN = {{1,2,3,3}, {1,2,2}, {1,1}};
    private static final int[][] CDC_TZ_CODE = {{1,1,1,0}, {1,1,0}, {1,0}};
    // run_before: [min(zerosLeft,7)-1][runBefore]
    private static final int[][] RB_LEN = {{1,1}, {1,2,2}, {2,2,2,2}, {2,2,2,3,3}, {2,2,3,3,3,3}, {2,3,3,3,3,3,3},
        {3,3,3,3,3,3,3,4,5,6,7,8,9,10,11}};
    private static final int[][] RB_CODE = {{1,0}, {1,1,0}, {3,2,1,0}, {3,2,1,1,0}, {3,2,3,2,1,0}, {3,0,1,3,2,5,4},
        {7,6,5,4,3,2,1,1,1,1,1,1,1,1,1}};

    /** Writes one residual block (coefficients already in scan order). Returns TotalCoeff. nC=-1: chroma DC. */
    private int residualBlock(int[] coef, int maxNum, int nC) {
        int total = 0, last = -1;
        for (int i = 0; i < maxNum; i++) if (coef[i] != 0) { total++; last = i; }
        // levels from highest frequency down
        int[] levels = new int[total];
        int[] runs = new int[total];
        int k = 0, run = 0;
        for (int i = last; i >= 0; i--) {
            if (coef[i] != 0) {
                if (k > 0) runs[k - 1] = run;
                levels[k++] = coef[i];
                run = 0;
            } else run++;
        }
        if (total > 0) runs[total - 1] = run;   // zeros below the lowest coefficient (not coded)
        int t1 = 0;
        while (t1 < total && t1 < 3 && Math.abs(levels[t1]) == 1) t1++;

        // coeff_token
        if (nC == -1) {
            bw.u(CDC_CT_LEN[total * 4 + t1], CDC_CT_CODE[total * 4 + t1]);
        } else {
            int cls = nC < 2 ? 0 : nC < 4 ? 1 : nC < 8 ? 2 : 3;
            bw.u(CT_LEN[cls][total * 4 + t1], CT_CODE[cls][total * 4 + t1]);
        }
        if (total == 0) return 0;

        // trailing ones signs
        for (int i = 0; i < t1; i++) bw.u(1, levels[i] < 0 ? 1 : 0);
        // remaining levels
        int suffixLength = (total > 10 && t1 < 3) ? 1 : 0;
        for (int i = t1; i < total; i++) {
            int level = levels[i];
            int levelCode = level > 0 ? 2 * level - 2 : -2 * level - 1;
            if (i == t1 && t1 < 3) levelCode -= 2;
            writeLevel(levelCode, suffixLength);
            if (suffixLength == 0) suffixLength = 1;
            if (Math.abs(level) > (3 << (suffixLength - 1)) && suffixLength < 6) suffixLength++;
        }
        // total_zeros
        if (total < maxNum) {
            int totalZeros = 0;
            for (int i = 0; i < last; i++) if (coef[i] == 0) totalZeros++;
            if (nC == -1) bw.u(CDC_TZ_LEN[total - 1][totalZeros], CDC_TZ_CODE[total - 1][totalZeros]);
            else bw.u(TZ_LEN[total - 1][totalZeros], TZ_CODE[total - 1][totalZeros]);
            // run_before
            int zerosLeft = totalZeros;
            for (int i = 0; i < total - 1 && zerosLeft > 0; i++) {
                int rb = runs[i];
                int row = Math.min(zerosLeft, 7) - 1;
                bw.u(RB_LEN[row][rb], RB_CODE[row][rb]);
                zerosLeft -= rb;
            }
        }
        return total;
    }

    private void writeLevel(int levelCode, int suffixLength) {
        int prefix, suffix, suffixSize;
        if (suffixLength == 0) {
            if (levelCode < 14) { prefix = levelCode; suffix = 0; suffixSize = 0; }
            else if (levelCode < 30) { prefix = 14; suffix = levelCode - 14; suffixSize = 4; }
            else { prefix = 15; suffix = levelCode - 30; suffixSize = 12; }
        } else {
            if (levelCode < (15 << suffixLength)) {
                prefix = levelCode >> suffixLength; suffix = levelCode & ((1 << suffixLength) - 1); suffixSize = suffixLength;
            } else { prefix = 15; suffix = levelCode - (15 << suffixLength); suffixSize = 12; }
        }
        if (prefix == 15 && suffix >= 4096) {
            // Escape for very large levels: level_prefix >= 16 (not needed at sensible qp; clamp instead).
            suffix = 4095;
        }
        for (int i = 0; i < prefix; i++) bw.u(1, 0);
        bw.u(1, 1);
        if (suffixSize > 0) bw.u(suffixSize, suffix);
    }

    // =====================================================================================

    static final class BitWriter {
        private byte[] buf = new byte[1 << 16];
        private int pos = 0;      // bytes written
        private long acc = 0;     // pending bits
        private int n = 0;        // number of pending bits

        void u(int bits, int value) {
            if (bits == 0) return;
            acc = (acc << bits) | (value & ((1L << bits) - 1));
            n += bits;
            while (n >= 8) { put((int) (acc >>> (n - 8)) & 255); n -= 8; }
            acc &= (1L << n) - 1;
        }
        void ue(int v) {
            int x = v + 1, len = 32 - Integer.numberOfLeadingZeros(x);
            u(len - 1, 0);
            u(len, x);
        }
        void se(int v) { ue(v <= 0 ? -2 * v : 2 * v - 1); }
        void trailing() { u(1, 1); if (n > 0) u(8 - n, 0); }
        private void put(int b) {
            if (pos == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            buf[pos++] = (byte) b;
        }
        byte[] bytes() { return java.util.Arrays.copyOf(buf, pos); }
    }
}
