package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.MarkMath;
import com.sva.vehicle.SkinMath;
import com.sva.vehicle.VehicleType;
import com.sva.vehicle.WeatherMath;

/**
 * Aircraft stage B self-tests (★13 weathering by hull wear, ★14 markings; mock-up docs/mockups/veh_air2_4_textures.png) -
 * pure logic on the dedicated server: the wear buckets, the generated weathering images (soot right behind the plane's
 * exhaust stubs and not ahead of them, mud at the bottom, chips at the door and behind a leading edge, oil on the belly,
 * more of everything with more wear, nothing when clean), the outer-face test, the stencil font (every letter used, no
 * forbidden glyph), the marking images (clear rim, readable on both sides) and every marking's box lying on the model's
 * outermost painted faces, seen from its own side (like the board decal's coverage test).
 */
final class SelfTestAirBSVA {

    private SelfTestAirBSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        buckets();
        weathering();
        outer();
        font();
        images();
        coverage();
    }

    // ---------------------------------------------------------------- ★13

    private static void buckets() {
        check(WeatherMath.bucket(100F, 100F, true) == 0 && WeatherMath.bucket(0F, 100F, true) == 4 && WeatherMath.bucket(50F, 100F, true) == 2
                && WeatherMath.bucket(70F, 100F, true) == 1 && WeatherMath.bucket(10F, 100F, true) == 4 && WeatherMath.bucket(90F, 100F, true) == 0,
                "weathering: wear buckets 0 / 25 / 50 / 75 / 100 % (rounded) from hp / maxHp");
        check(WeatherMath.bucket(0F, 100F, false) == 0 && WeatherMath.bucket(10F, 0F, true) == 0 && WeatherMath.bucket(Float.NaN, 100F, true) == 0
                && WeatherMath.bucket(150F, 100F, true) == 0 && WeatherMath.bucket(-5F, 100F, true) == 4,
                "weathering: clean without wear (server option off), with a bad max, clamped");
        check(WeatherMath.level(0) == 0F && WeatherMath.level(2) == 0.5F && WeatherMath.level(4) == 1F && WeatherMath.level(9) == 1F,
                "weathering: bucket -> wear level");
        int n = 0;
        for (VehicleType t : VehicleType.values()) {
            if (WeatherMath.specOf(t.model) != null) {
                n++;
            }
        }
        check(n >= 9 && WeatherMath.specOf("ship") == null && WeatherMath.specOf("plane") != null && WeatherMath.specOf("buggy") != null,
                "weathering: a spec for every vehicle but the Singular ship (" + n + ")");
    }

    private static int at(int[] px, float[] box, float x, float y) {
        float[] uv = new float[2];
        SkinMath.overlayUv(box, x, y, uv);
        int w = SkinMath.overlayW(box), h = SkinMath.overlayH(box);
        return px[Math.min(h - 1, (int) (uv[1] * h)) * w + Math.min(w - 1, (int) (uv[0] * w))];
    }

    /** Texels of colour `c` (0: any lit one) in the square of +-n texels around (x, y). */
    private static int near(int[] px, float[] box, float x, float y, int n, int c) {
        int k = 0;
        for (int dx = -n; dx <= n; dx++) {
            for (int dy = -n; dy <= n; dy++) {
                int p = at(px, box, x + dx / 16F, y + dy / 16F);
                if (c == 0 ? p != 0 : p == c) {
                    k++;
                }
            }
        }
        return k;
    }

    private static int count(int[] px) {
        int k = 0;
        if (px != null) {
            for (int p : px) {
                if (p != 0) {
                    k += (p >>> 24) == 0xFF ? 1 : 1000000;            // a half-transparent texel breaks "grows" below
                }
            }
        }
        return k;
    }

    private static void weathering() {
        WeatherMath.Spec s = WeatherMath.specOf("plane");
        float[] side = {-2.328F, 0F, 2.328F, 1.569F}, top = {-2.328F, -2.538F, 2.328F, 2.538F};
        check(WeatherMath.side(s, side, 1, 0, true) == null && WeatherMath.top(s, top, 0, null) == null && WeatherMath.bottom(s, top, 0, true) == null,
                "weathering: nothing at wear 0 (a repaired vehicle is clean)");
        int[] p4 = WeatherMath.side(s, side, 1, 4, true);
        int[] p1 = WeatherMath.side(s, side, 1, 1, true);
        check(p4 != null && near(p4, side, 1.55F, 0.42F, 2, WeatherMath.SOOT_A) + near(p4, side, 1.55F, 0.42F, 2, WeatherMath.SOOT_B) >= 7,
                "weathering: soot right behind the plane's exhaust stubs (1.76, 0.42)");
        check(p4 != null && near(p4, side, 2.10F, 0.42F, 1, WeatherMath.SOOT_A) == 0 && near(p4, side, 0.4F, 1.30F, 1, WeatherMath.SOOT_A) == 0,
                "weathering: no soot ahead of the stubs nor high on the fuselage");
        check(p1 != null && near(p1, side, 0.2F, 0.42F, 1, WeatherMath.SOOT_A) <= near(p4, side, 0.2F, 0.42F, 1, WeatherMath.SOOT_A),
                "weathering: the soot trail grows with wear");
        check(WeatherMath.side(s, side, 0, 4, true) == null || near(WeatherMath.side(s, side, 0, 4, true), side, 1.55F, 0.42F, 2, WeatherMath.SOOT_A) == 0,
                "weathering: the stubs' soot stays in the fuselage's z zone (not on the wing / struts)");
        check(near(p4, side, 0.80F, 0.88F, 2, WeatherMath.CHIP) + near(p4, side, 0.80F, 0.88F, 2, WeatherMath.CHIP_RIM) >= 3,
                "weathering: chipped paint along the door's outline");
        int[] other = WeatherMath.side(s, side, 1, 4, false);
        check(other != null && near(other, side, 0.80F, 0.47F, 2, WeatherMath.CHIP) == 0 && near(other, side, 0.5F, 0.03F, 3, 0) > 20,
                "weathering: no chips on unpainted parts, mud low on the gear");
        check(near(p4, side, 0F, 1.45F, 2, WeatherMath.MUD_A) == 0, "weathering: no mud high up");
        int[] b = WeatherMath.bottom(s, top, 4, false);
        check(b != null && near(b, top, 1.0F, 0F, 3, WeatherMath.OIL) > 4 && near(b, top, 2.1F, 0F, 2, WeatherMath.OIL) == 0
                && near(b, top, 1.0F, 1.5F, 2, WeatherMath.OIL) == 0, "weathering: oil streaks on the belly aft of the cowling only");
        check(near(WeatherMath.bottom(s, top, 4, true), top, 0.5F, 1.5F, 2, WeatherMath.MUD_A) + near(WeatherMath.bottom(s, top, 4, true), top, 0.5F, 1.5F, 2, WeatherMath.MUD_B) > 3
                && near(b, top, 0.5F, 1.5F, 2, WeatherMath.MUD_A) == 0, "weathering: mud only on the low bottom faces");
        // a leading edge at x 0.5 .. 0.55 over |z| <= 1 (synthetic): chips right behind it, none far behind
        int w = SkinMath.overlayW(top), h = SkinMath.overlayH(top);
        boolean[] edge = new boolean[w * h];
        float[] uv = new float[2];
        for (float z = -1F; z <= 1F; z += 0.03F) {
            for (float x = 0.5F; x <= 0.55F; x += 0.03F) {
                SkinMath.overlayUv(top, x, z, uv);
                edge[(int) (uv[1] * h) * w + (int) (uv[0] * w)] = true;
            }
        }
        WeatherMath.Spec clean = new WeatherMath.Spec();
        clean.scratch = 0F;
        int[] t4 = WeatherMath.top(clean, top, 4, edge);
        check(t4 != null && near(t4, top, 0.42F, 0F, 1, 0) >= 4 && near(t4, top, 0.1F, 0F, 2, 0) == 0 && near(t4, top, 0.7F, 0F, 2, 0) == 0,
                "weathering: chips just behind a leading-edge strip, none further aft or ahead");
        int prev = -1;
        boolean grows = true;
        for (int k = 1; k < WeatherMath.BUCKETS; k++) {
            int c = count(WeatherMath.side(s, side, 1, k, true)) + count(WeatherMath.bottom(s, top, k, true));
            grows &= c > prev;
            prev = c;
        }
        check(grows, "weathering: more dirt with every wear step (" + prev + " texels at 100 %)");
        String[] ground = {"buggy", "truck", "drill", "hover"};
        for (String m : ground) {
            WeatherMath.Spec g = WeatherMath.specOf(m);
            int[] px = WeatherMath.side(g, side, 0, 4, false);
            check(g != null && g.mudTop > 0F && px != null && near(px, side, 0F, 0.04F, 3, 0) > 20, "weathering: " + m + " gets mud on the wheels / tracks");
        }
    }

    private static void outer() {
        // two boxes: an outer shell |z| <= 1 and a wall inside it at z 0.5 facing out - the wall is not outer, the shell is
        float[] mn = {-1F, 0F, -1F}, mx = {1F, 1F, 1F};
        WeatherMath.Outer o = new WeatherMath.Outer(mn, mx);
        float[][] shell = {{-1F, 0F, 1F}, {1F, 0F, 1F}, {1F, 1F, 1F}};
        float[][] wall = {{-0.5F, 0.1F, 0.5F}, {0.5F, 0.1F, 0.5F}, {0.5F, 0.9F, 0.5F}};
        float[][] back = {{-1F, 0F, -1F}, {1F, 1F, -1F}, {1F, 0F, -1F}};
        o.add(shell[0], shell[1], shell[2]);
        o.add(wall[0], wall[1], wall[2]);
        o.add(back[0], back[1], back[2]);
        float[] pz = {0F, 0F, 1F}, nzv = {0F, 0F, -1F};
        check(o.outer(shell[0], shell[1], shell[2], pz) && !o.outer(wall[0], wall[1], wall[2], pz) && o.outer(back[0], back[1], back[2], nzv),
                "weathering / markings: only the outermost faces (no soot on cabin walls)");
        float[] f = o.facing(back[0], back[1], back[2], pz);
        check(f != null && f[2] < 0, "weathering / markings: a both-sided plate with an inward OBJ normal is seen from outside");
    }

    // ---------------------------------------------------------------- ★14

    private static void font() {
        boolean all = true;
        StringBuilder miss = new StringBuilder();
        for (String t : MarkMath.TEXTS) {
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (!MarkMath.has(c) || GlyphCheckSVA.forbidden(c)) {
                    all = false;
                    miss.append(c);
                }
            }
        }
        check(all, "markings: the stencil font has every letter used, no forbidden glyph" + (all ? "" : " (" + miss + ")"));
        boolean cyr = true;
        for (int i = 0; i < MarkMath.CHARS.length(); i++) {
            char c = MarkMath.CHARS.charAt(i);
            cyr &= MarkMath.has(c) && !GlyphCheckSVA.forbidden(c);
        }
        check(cyr, "markings: the font atlas (stencil_font.png) has all " + MarkMath.CHARS.length() + " glyphs (Cyrillic A..Я, digits, signs)");
        check(MarkMath.textWidth("Н") == 3 && MarkMath.textWidth("М") == 5 && MarkMath.textWidth(" ") == 2 && MarkMath.textWidth("НЕ") == 7,
                "markings: glyph widths (3, wide letters 5, space 2, 1 between)");
        check(MarkMath.registration(23).equals("СА-0723") && MarkMath.registration(5).equals("СА-0705"),
                "markings: registration «СА-07» + the board number");
    }

    private static void images() {
        String[] keys = {"antiglare", "red", "walk", "corners", "arrow", "rescue", "prop", "danger", "bore", "tow", "nostep", "jack", "reg", "finno", "emblem"};
        boolean ok = true;
        for (String k : keys) {
            MarkMath.Img im = MarkMath.image(k, 23, 0x2E5288);
            boolean lit = false;
            if (im == null) {
                ok = false;
                continue;
            }
            for (int p : im.px) {
                lit |= p != 0;
                ok &= p == 0 || (p >>> 24) == 0xFF;
            }
            ok &= lit;
            MarkMath.Img pd = MarkMath.padded(im);
            for (int x = 0; x < pd.w; x++) {
                ok &= pd.px[x] == 0 && pd.px[(pd.h - 1) * pd.w + x] == 0;
            }
            for (int y = 0; y < pd.h; y++) {
                ok &= pd.px[y * pd.w] == 0 && pd.px[y * pd.w + pd.w - 1] == 0;
            }
        }
        check(ok, "markings: every image drawn, opaque texels, a clear rim when padded");
        check(MarkMath.image("reg", 23, 0x112233).w == MarkMath.image("reg", 7, 0x112233).w, "markings: registration size independent of the number");
        MarkMath.Mark m = null;
        for (MarkMath.Mark k : MarkMath.marks("plane")) {
            if ("reg".equals(k.key)) {
                m = k;
            }
        }
        float[] a = new float[2], b = new float[2];
        if (m != null) {
            MarkMath.uv(m, m.box[0] + 0.01F, m.box[1] + 0.01F, 0.4F, 1F, a);
            MarkMath.uv(m, m.box[0] + 0.01F, m.box[1] + 0.01F, -0.4F, -1F, b);
        }
        check(m != null && a[0] < 0.2F && b[0] > 0.8F && Math.abs(a[1] - b[1]) < 1e-6F,
                "markings: a readable stencil is mirrored on the left side (reads left to right on both)");
        int top = 0;
        for (MarkMath.Mark k : MarkMath.marks("plane")) {
            top += k.proj == MarkMath.TOP ? 1 : 0;
        }
        check(MarkMath.marks("plane").size() >= 14 && top >= 6 && !MarkMath.marks("jet").isEmpty() && !MarkMath.marks("tilt").isEmpty()
                && !MarkMath.marks("drill").isEmpty() && MarkMath.marks("buggy").isEmpty(),
                "markings: the plane / jet / tiltrotor sets (side + top), the drill's hazard stencil, the buggy keeps its decal only");
    }

    /**
     * Every marking (with a cover share) lies on the outermost painted faces turned its way: looked at along its
     * projection (SIDE from both sides, TOP from above), the first triangle over each sample point is painted and passes
     * MarkMath.on with the normal seen from there.
     */
    private static void coverage() throws java.io.IOException {
        String[] models = {"plane", "jet", "tilt", "heli", "drill"};
        for (String model : models) {
            float[][] tris = tris(model);
            for (MarkMath.Mark mk : MarkMath.marks(model)) {
                if (mk.cover <= 0F) {
                    continue;
                }
                int tot = 0, ok0 = 0, ok1 = 0;
                for (int xi = 0; xi < 8; xi++) {
                    for (int yi = 0; yi < 3; yi++) {
                        float u = mk.box[0] + (mk.box[2] - mk.box[0]) * (0.1F + 0.8F * xi / 7F);
                        float v = mk.box[1] + (mk.box[3] - mk.box[1]) * (0.2F + 0.3F * yi);
                        tot++;
                        if (mk.proj == MarkMath.SIDE) {
                            ok0 += hit(tris, mk, u, v, 1F) ? 1 : 0;
                            ok1 += hit(tris, mk, u, v, -1F) ? 1 : 0;
                        } else {
                            ok0 += hit(tris, mk, u, v, 0F) ? 1 : 0;
                            ok1 = ok0;
                        }
                    }
                }
                check(ok0 >= tot * mk.cover && ok1 >= tot * mk.cover, "markings: " + model + " '" + mk.key + "' on the outer painted faces ("
                        + ok0 + "/" + ok1 + " of " + tot + ")");
            }
        }
    }

    /** sgn +1 / -1: looking at the right / left side along -+z; 0: from above. */
    private static boolean hit(float[][] tris, MarkMath.Mark mk, float u, float v, float sgn) {
        float best = -1e9F;
        float[] front = null;
        for (float[] t : tris) {
            float d = sgn == 0F ? depth(t, u, v, 2, 1) : depth(t, u, v, 1, 2);
            if (d != d) {
                continue;
            }
            float key = sgn == 0F ? d : d * sgn;
            if (key > best) {
                best = key;
                front = t;
            }
        }
        if (front == null || front[12] < 1F) {
            return false;
        }
        float[] fn = {front[9], front[10], front[11]};
        float toward = sgn == 0F ? fn[1] : fn[2] * sgn;
        if (toward < 0F) {
            fn = new float[] {-fn[0], -fn[1], -fn[2]};                    // both-sided: the side we look at
        }
        float[] a = {front[0], front[1], front[2]}, b = {front[3], front[4], front[5]}, c = {front[6], front[7], front[8]};
        return MarkMath.on(mk, fn, a, b, c);
    }

    /** The triangle's coordinate `out` (1 y, 2 z) over (x, w) where w is coordinate `in`; NaN outside. */
    private static float depth(float[] t, float x, float w, int in, int out) {
        float ax = t[0], aw = t[in], bx = t[3], bw = t[3 + in], cx = t[6], cw = t[6 + in];
        float det = (bw - cw) * (ax - cx) + (cx - bx) * (aw - cw);
        if (Math.abs(det) < 1e-12F) {
            return Float.NaN;
        }
        float l0 = ((bw - cw) * (x - cx) + (cx - bx) * (w - cw)) / det, l1 = ((cw - aw) * (x - cx) + (ax - cx) * (w - cw)) / det, l2 = 1F - l0 - l1;
        if (l0 < -1e-5F || l1 < -1e-5F || l2 < -1e-5F) {
            return Float.NaN;
        }
        return l0 * t[out] + l1 * t[3 + out] + l2 * t[6 + out];
    }

    /** The model's triangles but the glass: 9 positions, the face normal along the smooth normals, 1 when painted. */
    private static float[][] tris(String model) throws java.io.IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                SelfTestAirBSVA.class.getResourceAsStream("/assets/siliconagevehicles/models/" + model + ".obj"), "UTF-8"), 1 << 16);
        List<float[]> v = new ArrayList<float[]>(), vn = new ArrayList<float[]>(), out = new ArrayList<float[]>();
        boolean take = false, paint = false;
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ") || line.startsWith("vn ")) {
                    String[] p = line.trim().split("\\s+");
                    (line.startsWith("v ") ? v : vn).add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                } else if (line.startsWith("g ")) {
                    String g = line.substring(2).trim();
                    paint = SkinMath.paintable(g);
                    take = !"glass".equals(g) && !"canopy".equals(g);
                } else if (take && line.startsWith("f ")) {
                    String[] p = line.substring(2).trim().split("\\s+");
                    for (int i = 1; i + 1 < p.length; i++) {
                        int[] idx = {0, i, i + 1};
                        float[] t = new float[13];
                        float sx = 0, sy = 0, sz = 0;
                        for (int k = 0; k < 3; k++) {
                            String[] ix = p[idx[k]].split("/");
                            System.arraycopy(v.get(Integer.parseInt(ix[0]) - 1), 0, t, k * 3, 3);
                            if (ix.length >= 3 && ix[2].length() > 0) {
                                float[] n = vn.get(Integer.parseInt(ix[2]) - 1);
                                sx += n[0];
                                sy += n[1];
                                sz += n[2];
                            }
                        }
                        float ux = t[3] - t[0], uy = t[4] - t[1], uz = t[5] - t[2], wx = t[6] - t[0], wy = t[7] - t[1], wz = t[8] - t[2];
                        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
                        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (l < 1e-9F) {
                            continue;
                        }
                        float s = nx * sx + ny * sy + nz * sz < 0 ? -1F : 1F;
                        t[9] = s * nx / l;
                        t[10] = s * ny / l;
                        t[11] = s * nz / l;
                        t[12] = paint ? 1F : 0F;
                        out.add(t);
                    }
                }
            }
        } finally {
            r.close();
        }
        return out.toArray(new float[out.size()][]);
    }
}
