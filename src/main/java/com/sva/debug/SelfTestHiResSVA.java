package com.sva.debug;

import java.util.HashMap;
import java.util.Map;

import com.sva.ConfigSVA;
import com.sva.vehicle.HiResMath;

/**
 * 2K / 4K wave self-tests (client/HiResSVA, HiFontSVA, FaceArtSVA - the sharp cockpit) - pure logic on the dedicated
 * server (the AWT painting and GL uploads are client only): the quality setting and AUTO's thresholds, the glyph cell's
 * metrics (the vanilla 8-unit line inside the 10-unit cell), the atlas packing (no overlaps, inside, a power of two,
 * ~square), the mip levels against the padding, the premultiplied mip filter, the VRAM figures quoted in the config
 * comment, the face cache key, the config defaults.
 */
final class SelfTestHiResSVA {

    private SelfTestHiResSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() {
        quality();
        metrics();
        packing();
        mips();
        vram();
        keys();
        config();
    }

    private static void quality() {
        boolean parse = HiResMath.parse("auto") == HiResMath.AUTO && HiResMath.parse(" 2k ") == HiResMath.Q2K && HiResMath.parse("4K") == HiResMath.Q4K
                && HiResMath.parse("1K") == HiResMath.Q1K && HiResMath.parse("8K") == HiResMath.AUTO && HiResMath.parse(null) == HiResMath.AUTO
                && HiResMath.parse("") == HiResMath.AUTO;
        check(parse, "hi-res: the quality setting parses AUTO / 1K / 2K / 4K (case, blanks), anything else -> AUTO");
        int a = HiResMath.AUTO;
        boolean auto = HiResMath.level(a, 720) == HiResMath.Q1K && HiResMath.level(a, 1080) == HiResMath.Q1K && HiResMath.level(a, 1200) == HiResMath.Q1K
                && HiResMath.level(a, 1201) == HiResMath.Q2K && HiResMath.level(a, 1440) == HiResMath.Q2K && HiResMath.level(a, 1800) == HiResMath.Q2K
                && HiResMath.level(a, 1801) == HiResMath.Q4K && HiResMath.level(a, 2160) == HiResMath.Q4K && HiResMath.level(a, 0) == HiResMath.Q1K;
        check(auto, "hi-res: AUTO by the window height - <= 1200 1K, <= 1800 2K (1440p), else 4K (2160p)");
        boolean forced = HiResMath.level(HiResMath.Q1K, 2160) == HiResMath.Q1K && HiResMath.level(HiResMath.Q4K, 720) == HiResMath.Q4K
                && HiResMath.level(HiResMath.Q2K, 1080) == HiResMath.Q2K;
        check(forced, "hi-res: a fixed 1K / 2K / 4K ignores the window");
        boolean sizes = HiResMath.glyphPx(HiResMath.Q1K) == 32 && HiResMath.glyphPx(HiResMath.Q2K) == 64 && HiResMath.glyphPx(HiResMath.Q4K) == 128
                && HiResMath.facePx(HiResMath.Q1K) == 256 && HiResMath.facePx(HiResMath.Q2K) == 512 && HiResMath.facePx(HiResMath.Q4K) == 1024;
        check(sizes, "hi-res: glyphs 32 / 64 / 128 px, dial faces 256 / 512 / 1024 px by level");
    }

    private static void metrics() {
        boolean ok = true;
        for (int lv = HiResMath.Q1K; lv <= HiResMath.Q4K; lv++) {
            int g = HiResMath.glyphPx(lv);
            float u = HiResMath.unitsPerPx(g);
            // the cap top (-4) and the baseline (+3) in the cell's pixels: 0.1 g and 0.8 g - the cap 7 units = 0.7 g
            float capTop = (-4F - HiResMath.CELL_TOP) / u, base = HiResMath.baselinePx(g);
            ok &= Math.abs(u * g - HiResMath.CELL_UNITS) < 1e-4F && Math.abs(capTop - 0.1F * g) < 1e-3F && Math.abs(base - 0.8F * g) < 1e-3F
                    && Math.abs((base - capTop) * u - HiResMath.CAP_UNITS) < 0.05F && HiResMath.pad(g) >= 2;
        }
        boolean fit = HiResMath.fit(40F, 8F, 100F) == 1F && HiResMath.fit(40F, 8F, 20F) == 0.5F && HiResMath.fit(0F, 4F, 1F) == 0.5F;
        check(ok && fit, "hi-res: the glyph cell keeps the vanilla 8-unit line (cap top -4, baseline +3, 10 units a cell), fit = min(h / 8, maxW / w)");
    }

    private static void packing() {
        boolean ok = true;
        String why = "";
        for (int lv = HiResMath.Q1K; lv <= HiResMath.Q4K; lv++) {
            int g = HiResMath.glyphPx(lv), pad = HiResMath.pad(g), cellH = g + 2 * pad, aw = HiResMath.atlasWidth(g);
            // ~170 glyphs (ASCII + Cyrillic + a few), widths like a bold sans: 0.25 g (i, l) .. 0.95 g (Ш, Щ, Ж)
            int n = 170;
            int[] w = new int[n];
            for (int i = 0; i < n; i++) {
                w[i] = Math.round(g * (0.25F + 0.7F * ((i * 37) % 100) / 100F)) + 2 * pad;
            }
            int[] lay = HiResMath.pack(w, cellH, aw);
            if (lay == null) {
                ok = false;
                why += " null@" + g;
                continue;
            }
            int W = lay[0], H = lay[1];
            ok &= W == aw && (H & (H - 1)) == 0 && H <= W;
            for (int i = 0; i < n && ok; i++) {
                int x = lay[2 + i * 2], y = lay[3 + i * 2];
                ok &= x >= 0 && y >= 0 && x + w[i] <= W && y + cellH <= H;
                for (int j = 0; j < i && ok; j++) {
                    int xj = lay[2 + j * 2], yj = lay[3 + j * 2];
                    boolean apart = x >= xj + w[j] || xj >= x + w[i] || y >= yj + cellH || yj >= y + cellH;
                    if (!apart) {
                        ok = false;
                        why += " overlap " + i + "/" + j + "@" + g;
                    }
                }
            }
        }
        boolean tooWide = HiResMath.pack(new int[] {10, 600}, 40, 512) == null;
        check(ok && tooWide, "hi-res: the atlas packs ~170 glyph cells in rows - inside, no overlaps, a power-of-two square at most; a cell wider"
                + " than the atlas -> null" + why);
    }

    private static void mips() {
        boolean levels = HiResMath.atlasLevels(32) == 3 && HiResMath.atlasLevels(64) == 4 && HiResMath.atlasLevels(128) == 5
                && HiResMath.faceLevels(256) == 7 && HiResMath.faceLevels(1024) == 9;
        boolean pads = true;
        for (int lv = HiResMath.Q1K; lv <= HiResMath.Q4K; lv++) {
            int g = HiResMath.glyphPx(lv);
            pads &= HiResMath.pad(g) >> (HiResMath.atlasLevels(g) - 1) >= 1;     // the smallest mip still has a texel of padding
        }
        check(levels && pads, "hi-res: mips down to an 8 px glyph (3 / 4 / 5 levels), the padding lasts to the last one; faces down to 4 px");
        byte[] one = {(byte) 255, (byte) 255, (byte) 255, 0, (byte) 255, 0, (byte) 255, 0};      // 2 x 2: one white opaque texel
        byte[] r1 = HiResMath.downLA(one, 2, 2);
        byte[] grey = {100, (byte) 255, (byte) 200, (byte) 255, 0, 0, 0, 0};                     // 100 and 200 opaque, two clear (black)
        byte[] r2 = HiResMath.downLA(grey, 2, 2);
        byte[] clear = new byte[8];
        byte[] r3 = HiResMath.downLA(clear, 2, 2);
        byte[] odd = HiResMath.downLA(new byte[3 * 3 * 2], 3, 3);
        boolean filt = (r1[0] & 255) == 255 && (r1[1] & 255) == 64 && (r2[0] & 255) == 150 && (r2[1] & 255) == 128
                && (r3[0] & 255) == 255 && r3[1] == 0 && odd.length == 2;
        check(filt, "hi-res: the mip filter averages alpha and weights the grey by it (clear texels don't darken edges); odd sizes");
    }

    private static void vram() {
        long font4k = HiResMath.vramBytes(2048, 2048, 2, HiResMath.atlasLevels(128));
        long face4k = HiResMath.vramBytes(1024, 1024, 2, HiResMath.faceLevels(1024));
        long font1k = HiResMath.vramBytes(512, 512, 2, HiResMath.atlasLevels(32)), face1k = HiResMath.vramBytes(256, 256, 2, HiResMath.faceLevels(256));
        double mb = 1024.0 * 1024.0;
        boolean ok = Math.abs(font4k / mb - 10.7) < 0.1 && Math.abs(face4k / mb - 2.7) < 0.1 && Math.abs(font1k / mb - 0.7) < 0.1
                && Math.abs(face1k / mb - 0.2) < 0.05 && (font4k + 12 * face4k) / mb < 45 && HiResMath.vramBytes(4, 4, 2, 3) == 32 + 8 + 2;
        check(ok, "hi-res: VRAM as the config comment says - 4K font " + Math.round(font4k / mb * 10) / 10.0 + " MB + " + Math.round(face4k / mb * 10) / 10.0
                + " MB a dial (12 kept: < 45 MB), 1K " + Math.round(font1k / mb * 10) / 10.0 + " + " + Math.round(face1k / mb * 100) / 100.0 + " MB");
    }

    private static void keys() {
        HiResMath.Key a = new HiResMath.Key().set(HiResMath.FACE_DIAL, 3, 0F, 120F, 0F);
        boolean eq = a.equals(a.copy()) && a.hashCode() == a.copy().hashCode();
        boolean diff = !a.equals(a.copy().set(HiResMath.FACE_SCALE, 3, 0F, 120F, 0F)) && !a.equals(a.copy().set(HiResMath.FACE_DIAL, 4, 0F, 120F, 0F))
                && !a.equals(a.copy().set(HiResMath.FACE_DIAL, 3, -1F, 120F, 0F)) && !a.equals(a.copy().set(HiResMath.FACE_DIAL, 3, 0F, 121F, 0F))
                && !a.equals(a.copy().set(HiResMath.FACE_DIAL, 3, 0F, 120F, 1F)) && !a.equals(null);
        Map<HiResMath.Key, String> m = new HashMap<HiResMath.Key, String>();
        HiResMath.Key probe = new HiResMath.Key();
        m.put(probe.set(HiResMath.FACE_DIAL, 1, 0F, 64F, 0F).copy(), "speed");
        m.put(probe.set(HiResMath.FACE_SCALE, 120, 10F, 20F, 0F).copy(), "tach");
        boolean found = "speed".equals(m.get(probe.set(HiResMath.FACE_DIAL, 1, 0F, 64F, 0F))) && "tach".equals(m.get(probe.set(HiResMath.FACE_SCALE, 120, 10F, 20F, 0F)))
                && m.get(probe.set(HiResMath.FACE_SCALE, 120, 20F, 20F, 0F)) == null && m.size() == 2;
        check(eq && diff && found, "hi-res: the face cache key - equal by kind and numbers, every field counts, one reused probe finds the stored copies");
    }

    private static void config() {
        check(ConfigSVA.cockpitHiRes && "AUTO".equals(ConfigSVA.cockpitQuality) && HiResMath.QUALITY_KEYS.length == 4,
                "hi-res: config vehicles.cockpitHiRes on, vehicles.cockpitQuality AUTO by default");
    }
}
