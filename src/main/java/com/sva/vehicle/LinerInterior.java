package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * Liner wave 1 (plan-next §7, mock-ups veh_liner_6 / 9): the SA-320's interior as plain triangles - the cockpit of the
 * concept (scratchpad liner/interior/icockpit.py) and the passenger cabin (icabin.py) - pure geometry, no GL: the client
 * (LinerCockpitSVA) compiles it into display lists, the standalone preview rasterises the same triangles, the self-test
 * counts them.
 * <p>
 * <b>Frames.</b> The cockpit is built in its concept frame (metres = blocks; x forward, y up, z right; the origin between
 * the pilots at the eye) and moved by (LinerControls.CK_X, CK_Y, 0) into the model; its panels are textured from the
 * atlas (LinerTexMath) at LinerControls' panel placements. Changes against the concept for the game: the pilots' seats
 * under the eye (a Minecraft rider's eye is over his hips: cushion top 0.87 under the eye, the concept's 0.65 was a
 * real seat's), the rear wall at -1.10 (the cabin's bulkhead - the L1 door is right behind it). The cabin is built in
 * icabin.py's frame (x 0 = the front bulkhead, floor y 0) scaled CAB_S across (y, z) to fit the 2.4-block fuselage and
 * put at (CAB_X, CAB_Y): its seats under the passengers' seat positions (VehicleType.LINER).
 * <p>
 * Each triangle: positions (model units), atlas UVs (or none), an RGB colour (baked with a face shade - the interior is
 * drawn unlit, the world light and the dome light come through the light map), an emitter flag (the signs, the cove
 * light) and its part: COCKPIT / CABIN (the cabin is drawn only near it) / GLOW (the panels that get the integral lighting
 * pass).
 */
public final class LinerInterior {

    /** The cabin: front bulkhead at model x CAB_X (= the cockpit's rear wall), floor at CAB_Y, cross-section x CAB_S. */
    public static final float CAB_X = LinerControls.CK_X - 1.10F, CAB_Y = 1.40F, CAB_S = 0.88F;
    /** The cockpit's rear wall in its frame. */
    public static final float CK_REAR = -1.10F;
    /** Parts. */
    public static final int COCKPIT = 0, CABIN = 1;

    /** One triangle. */
    public static final class Tri {
        public final float[] p = new float[9];
        /** UVs (6) in the atlas, or null (untextured). */
        public float[] uv;
        public int rgb;
        public boolean emit, glow;
        public int part;
        /** The face normal (unit). */
        public final float[] n = new float[3];
    }

    private final List<Tri> tris = new ArrayList<Tri>();
    private int part;
    /** The frame: model = (ox + x, oy + y * sy, z * sz) (x scaled by sx). */
    private float ox, oy, sx = 1, sy = 1, sz = 1;

    public List<Tri> tris() {
        return tris;
    }

    /** The whole interior (cockpit + cabin). */
    public static LinerInterior build() {
        LinerInterior b = new LinerInterior();
        b.cockpit();
        b.cabin();
        return b;
    }

    // ---------------------------------------------------------------- primitives

    private void frame(int part, float ox, float oy, float sx, float sy, float sz) {
        this.part = part;
        this.ox = ox;
        this.oy = oy;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
    }

    private void tri(float[] a, float[] b, float[] c, float[] ua, float[] ub, float[] uc, int rgb, boolean emit, boolean glow) {
        Tri t = new Tri();
        float[][] v = {a, b, c};
        for (int i = 0; i < 3; i++) {
            t.p[i * 3] = ox + v[i][0] * sx;
            t.p[i * 3 + 1] = oy + v[i][1] * sy;
            t.p[i * 3 + 2] = v[i][2] * sz;
        }
        if (ua != null) {
            t.uv = new float[] {ua[0], ua[1], ub[0], ub[1], uc[0], uc[1]};
        }
        float ex = t.p[3] - t.p[0], ey = t.p[4] - t.p[1], ez = t.p[5] - t.p[2], fx = t.p[6] - t.p[0], fy = t.p[7] - t.p[1], fz = t.p[8] - t.p[2];
        float nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-9F) {
            return;
        }
        t.n[0] = nx / l;
        t.n[1] = ny / l;
        t.n[2] = nz / l;
        t.rgb = rgb;
        t.emit = emit;
        t.glow = glow;
        t.part = part;
        tris.add(t);
    }

    /** A textured quad: atlas rect `tex` (or -1 for plain colour), its sub-rect (u0, v0, u1, v1) in 0..1 of the rect. */
    void quad4(float[] a, float[] b, float[] c, float[] d, int rgb, int tex, float[] uv, boolean emit, boolean glow) {
        if (tex < 0) {
            tri(a, b, c, null, null, null, rgb, emit, glow);
            tri(a, c, d, null, null, null, rgb, emit, glow);
            return;
        }
        float[] u = uv == null ? new float[] {0, 0, 1, 1} : uv;
        int[] r = LinerTexMath.RECTS[tex];
        float[] ta = {LinerTexMath.u(tex, u[0] * r[2]), LinerTexMath.v(tex, u[1] * r[3])};
        float[] tb = {LinerTexMath.u(tex, u[2] * r[2]), LinerTexMath.v(tex, u[1] * r[3])};
        float[] tc = {LinerTexMath.u(tex, u[2] * r[2]), LinerTexMath.v(tex, u[3] * r[3])};
        float[] td = {LinerTexMath.u(tex, u[0] * r[2]), LinerTexMath.v(tex, u[3] * r[3])};
        tri(a, b, c, ta, tb, tc, rgb, emit, glow);
        tri(a, c, d, ta, tc, td, rgb, emit, glow);
    }

    void quad4(float[] a, float[] b, float[] c, float[] d, int rgb) {
        quad4(a, b, c, d, rgb, -1, null, false, false);
    }

    /** irender.quad: p0 = bottom-left, ex along u, ey up the texture. */
    void quad(float[] p0, float[] ex, float[] ey, int rgb, int tex, float[] uv, boolean emit, boolean glow) {
        float[] a = add(p0, ey), b = add(add(p0, ex), ey), c = add(p0, ex);
        quad4(a, b, c, p0, rgb, tex, uv, emit, glow);
    }

    void quad(float[] p0, float[] ex, float[] ey, int rgb) {
        quad(p0, ex, ey, rgb, -1, null, false, false);
    }

    /** A box (irender.box): centre, size, colour; turned by R (3x3 rows) or null; top / front / back textures. */
    void box(float[] c, float[] size, int rgb, float[][] R, int topTex, float[] topUv, int frontTex, float[] frontUv, int backTex, float[] backUv) {
        float sx2 = size[0] / 2, sy2 = size[1] / 2, sz2 = size[2] / 2;
        float[] ex = col(R, 0, sx2), ey = col(R, 1, sy2), ez = col(R, 2, sz2);
        float[] ex2 = scale(ex, 2), ey2 = scale(ey, 2), ez2 = scale(ez, 2);
        quad(sub(sub(add(c, ey), ex), ez), ez2, ex2, topTex < 0 ? rgb : 0xFFFFFF, topTex, topUv, false, false);
        quad(sub(sub(sub(c, ex), ez), ey), ez2, ex2, shade(rgb, 0.7));
        quad(sub(sub(add(c, ex), ez), ey), ez2, ey2, frontTex < 0 ? shade(rgb, 0.92) : 0xFFFFFF, frontTex, frontUv, false, false);
        quad(sub(add(sub(c, ex), ez), ey), scale(ez2, -1), ey2, backTex < 0 ? shade(rgb, 0.85) : 0xFFFFFF, backTex, backUv, false, false);
        quad(sub(add(sub(c, ex), ez), ey), ex2, ey2, shade(rgb, 0.88));
        quad(sub(sub(add(c, ex), ez), ey), scale(ex2, -1), ey2, shade(rgb, 0.88));
    }

    void box(float[] c, float[] size, int rgb) {
        box(c, size, rgb, null, -1, null, -1, null, -1, null);
    }

    void box(float[] c, float[] size, int rgb, float[][] R) {
        box(c, size, rgb, R, -1, null, -1, null, -1, null);
    }

    /** A cylinder p0 -> p1 (radius r / r1 at the ends), shaded round its side, capped. */
    void cyl(float[] p0, float[] p1, float r, int rgb, int seg, float r1, boolean caps, int capRgb) {
        float[] ax = norm(sub(p1, p0));
        float[] t = norm(cross(ax, Math.abs(ax[1]) < 0.9F ? new float[] {0, 1, 0} : new float[] {1, 0, 0}));
        float[] b = cross(ax, t);
        float[][] r0 = new float[seg][], rr = new float[seg][];
        for (int i = 0; i < seg; i++) {
            double a = 2 * Math.PI * i / seg;
            float[] d = add(scale(t, (float) Math.cos(a)), scale(b, (float) Math.sin(a)));
            r0[i] = add(p0, scale(d, r));
            rr[i] = add(p1, scale(d, r1));
        }
        for (int i = 0; i < seg; i++) {
            int j = (i + 1) % seg;
            double lv = 0.78 + 0.22 * Math.cos(2 * Math.PI * (i + 0.5) / seg - 1.0);
            int cc = shade(rgb, lv);
            tri(r0[i], r0[j], rr[j], null, null, null, cc, false, false);
            tri(r0[i], rr[j], rr[i], null, null, null, cc, false, false);
        }
        if (caps) {
            for (int i = 0; i < seg; i++) {
                tri(p0, r0[(i + 1) % seg], r0[i], null, null, null, capRgb, false, false);
                tri(p1, rr[i], rr[(i + 1) % seg], null, null, null, capRgb, false, false);
            }
        }
    }

    void cyl(float[] p0, float[] p1, float r, int rgb, int seg) {
        cyl(p0, p1, r, rgb, seg, r, true, rgb);
    }

    void disk(float[] c, float[] n, float r, int rgb, int seg, boolean emit) {
        float[] nn = norm(n);
        float[] t = norm(cross(nn, Math.abs(nn[1]) < 0.9F ? new float[] {0, 1, 0} : new float[] {1, 0, 0}));
        float[] b = cross(nn, t);
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float[] p = add(c, add(scale(t, (float) (r * Math.cos(a0))), scale(b, (float) (r * Math.sin(a0)))));
            float[] q = add(c, add(scale(t, (float) (r * Math.cos(a1))), scale(b, (float) (r * Math.sin(a1)))));
            tri(c, p, q, null, null, null, rgb, emit, false);
        }
    }

    // ---------------------------------------------------------------- the cockpit (icockpit.py)

    static final int LINING = rgb(176, 180, 182), POST = rgb(118, 124, 130), DARKG = rgb(54, 58, 64), FLOOR = rgb(52, 56, 66);
    static final float HW = 0.95F, GS_Y0 = -0.215F, GS_Y1 = -0.04F, GS_X = 0.74F;

    /** A panel's flat face as a textured quad (LinerControls.FLAT): its rect in the atlas, lifted `off` toward the crew. */
    private void panelQuad(int panel, float off) {
        float[] f = LinerControls.FLAT[panel];
        float[] tl = {f[0], f[1], f[2]}, u = {f[3], f[4], f[5]}, v = {f[6], f[7], f[8]};
        float[] n = norm(cross(v, u));
        float[] lift = scale(n, off);
        float[] a = add(tl, lift), b = add(add(tl, u), lift), c = add(add(add(tl, u), v), lift), d = add(add(tl, v), lift);
        quad4(a, b, c, d, 0xFFFFFF, panel, null, false, true);
    }

    private void cockpit() {
        frame(COCKPIT, LinerControls.CK_X, LinerControls.CK_Y, 1, 1, 1);
        // glare shield: the top, the FCU strip (textured), its underside
        quad(new float[] {GS_X, GS_Y1, -HW}, new float[] {0, 0, 2 * HW}, new float[] {0.24F, 0, 0}, DARKG);
        panelQuad(LinerControls.GS, 0);
        quad(new float[] {GS_X, GS_Y0, -HW}, new float[] {0, 0, 2 * HW}, new float[] {0.80F - GS_X + 0.01F, -0.20F - GS_Y0, 0}, rgb(36, 38, 42));
        // main panel (the DUs are their own quads on top, LinerCockpitSVA) and the knee panel under it
        panelQuad(LinerControls.MP, 0);
        float[] mb = LinerControls.point(LinerControls.MP, 0, LinerControls.PANEL_H[LinerControls.MP], new float[3]);
        float[] mb2 = LinerControls.point(LinerControls.MP, LinerControls.PANEL_W[LinerControls.MP], LinerControls.PANEL_H[LinerControls.MP], new float[3]);
        quad4(mb, mb2, new float[] {0.55F, -1.15F, HW}, new float[] {0.55F, -1.15F, -HW}, rgb(40, 44, 50));
        // pedestal: the sloping front and the flat top (textured), its sides and rear
        float ph = LinerControls.PANEL_H[LinerControls.PED], pw = LinerControls.PANEL_W[LinerControls.PED], sp = LinerControls.PED_SPLIT;
        float[] a = pp(0, 0), b = pp(pw, 0), c = pp(pw, sp), d = pp(0, sp);
        quad4(a, b, c, d, 0xFFFFFF, LinerControls.PED, new float[] {0, 0, 1, sp / ph}, false, true);
        float[] a2 = pp(0, sp), b2 = pp(pw, sp), c2 = pp(pw, ph), d2 = pp(0, ph);
        quad4(a2, b2, c2, d2, 0xFFFFFF, LinerControls.PED, new float[] {0, sp / ph, 1, 1}, false, true);
        for (int s = -1; s <= 1; s += 2) {
            float zz = s * LinerControls.PED_Z;
            float tx = s < 0 ? 0 : pw;
            float[] q0 = pp(tx, 0), q1 = pp(tx, sp), q2 = pp(tx, ph);
            quad4(q0, q1, new float[] {q1[0], -1.15F, zz}, new float[] {q0[0], -1.15F, zz}, rgb(66, 78, 90));
            quad4(q1, q2, new float[] {q2[0], -1.15F, zz}, new float[] {q1[0], -1.15F, zz}, rgb(66, 78, 90));
        }
        float[] rc = pp(0, ph), rd = pp(pw, ph);
        quad4(rc, rd, new float[] {rd[0], -1.15F, rd[2]}, new float[] {rc[0], -1.15F, rc[2]}, rgb(60, 72, 84));
        // trim wheels (static), the parking brake's housing
        for (float tx : new float[] {150, 575}) {
            float[] cp = LinerControls.point(LinerControls.PED, tx, 835, new float[3]);
            cp[1] -= 0.06F;
            cyl(new float[] {cp[0], cp[1], cp[2] - 0.011F}, new float[] {cp[0], cp[1], cp[2] + 0.011F}, 0.095F, rgb(34, 35, 38), 28, 0.095F, true,
                    rgb(48, 50, 54));
            box(new float[] {cp[0], cp[1] + 0.096F, cp[2]}, new float[] {0.012F, 0.004F, 0.024F}, rgb(235, 235, 235));
        }
        // lateral consoles (textured tops), sidesticks, tillers, cup holders
        float cx0 = 0.62F, cx1 = -0.20F, cw = 0.28F;
        for (int s = -1; s <= 1; s += 2) {
            int panel = s < 0 ? LinerControls.SCL : LinerControls.SCR;
            float z0 = s < 0 ? -HW : HW - cw, z1 = s < 0 ? -HW + cw : HW;
            box(new float[] {(cx0 + cx1) / 2, -0.80F, (z0 + z1) / 2}, new float[] {cx0 - cx1, 0.48F, z1 - z0}, rgb(70, 82, 94));
            panelQuad(panel, 0.001F);
            float[] spt = LinerControls.point(panel, s < 0 ? 370 : 130, 500, new float[3]);
            cyl(spt, add(spt, new float[] {0, 0.045F, 0}), 0.034F, rgb(24, 24, 26), 16, 0.024F, true, rgb(24, 24, 26));
            // wave 2: the grips (with the red AP-disconnect button) move with the stick - LinerCockpitSVA.sticks draws them live
            // on this base (STICK_TX / STICK_TY, STICK_BASE over the console)
            float[] tl = LinerControls.point(panel, s < 0 ? 140 : 360, 470, new float[3]);
            tl[1] += 0.006F;
            cyl(tl, add(tl, new float[] {0, 0.012F, 0}), 0.062F, rgb(30, 31, 34), 28, 0.062F, true, rgb(44, 46, 50));
            box(add(tl, new float[] {0, 0.02F, 0}), new float[] {0.024F, 0.016F, 0.10F}, rgb(52, 54, 58));
            float[] cup = LinerControls.point(panel, s < 0 ? 110 : 390, 1340, new float[3]);
            cyl(add(cup, new float[] {0, -0.02F, 0}), add(cup, new float[] {0, 0.012F, 0}), 0.028F, rgb(28, 28, 30), 18, 0.028F, true, rgb(10, 10, 12));
        }
        // the shell: windshield and corner-pane frames, side walls with the sliding windows, the roof, the floor
        float[][] wb = {{0.98F, -0.04F, -0.62F}, {0.98F, -0.04F, 0}, {0.98F, -0.04F, 0.62F}};
        float[][] wt = {{0.66F, 0.36F, -0.45F}, {0.66F, 0.36F, 0}, {0.66F, 0.36F, 0.45F}};
        for (int i = 0; i < 2; i++) {
            frameStrips(wt[i], wt[i + 1], wb[i + 1], wb[i], 0.045F, POST);
        }
        for (int s = -1; s <= 1; s += 2) {
            float[] fb = {0.98F, -0.04F, s * 0.62F}, ft = {0.66F, 0.36F, s * 0.45F}, rb = {0.70F, -0.04F, s * HW}, rt = {0.45F, 0.36F, s * 0.80F};
            if (s < 0) {
                frameStrips(rt, ft, fb, rb, 0.05F, POST);
            } else {
                frameStrips(ft, rt, rb, fb, 0.05F, POST);
            }
            float[] wa = {0.45F, 0.36F, s * 0.80F}, wbk = {CK_REAR, 0.40F, s * 0.80F}, wc = {CK_REAR, -0.04F, s * HW}, wd = {0.70F, -0.04F, s * HW};
            if (s < 0) {
                wallWithHole(wbk, wa, wd, wc, new float[] {0.32F, 0.1F, 0.88F, 0.82F}, LINING);
            } else {
                wallWithHole(wa, wbk, wc, wd, new float[] {0.12F, 0.1F, 0.68F, 0.82F}, LINING);
            }
            quad4(wd, wc, new float[] {CK_REAR, -1.15F, s * HW}, new float[] {0.70F, -1.15F, s * HW}, rgb(150, 154, 156));
            quad4(new float[] {GS_X, GS_Y1, s * HW}, new float[] {0.98F, GS_Y1, s * HW}, new float[] {0.68F, -0.80F, s * HW},
                    new float[] {0.68F, -0.80F, s * HW}, rgb(60, 64, 70));
            // under the side windows' sills: closed down to the console
            quad4(new float[] {0.70F, -0.04F, s * HW}, new float[] {0.98F, -0.04F, s * 0.62F}, new float[] {0.98F, -0.8F, s * 0.62F},
                    new float[] {0.70F, -0.8F, s * HW}, rgb(70, 76, 84));
        }
        // the roof: the header over the windshield, the overhead panel (textured), the lining, the dome light
        quad4(new float[] {0.66F, 0.36F, -0.45F}, new float[] {0.66F, 0.36F, 0.45F}, new float[] {0.60F, 0.385F, 0.45F}, new float[] {0.60F, 0.385F, -0.45F},
                POST);
        panelQuad(LinerControls.OVH, 0);
        float ovw = 0.43F;
        for (int s = -1; s <= 1; s += 2) {
            if (s < 0) {
                quad4(new float[] {0.66F, 0.36F, -0.45F}, new float[] {0.45F, 0.36F, -0.80F}, new float[] {CK_REAR, 0.40F, -0.80F},
                        new float[] {CK_REAR, 0.56F, -ovw}, LINING);
            } else {
                quad4(new float[] {0.45F, 0.36F, 0.80F}, new float[] {0.66F, 0.36F, 0.45F}, new float[] {CK_REAR, 0.56F, ovw},
                        new float[] {CK_REAR, 0.40F, 0.80F}, LINING);
            }
            quad4(new float[] {0.60F, 0.385F, s * ovw}, new float[] {-0.02F, 0.53F, s * ovw}, new float[] {-0.02F, 0.56F, s * (ovw + 0.02F)},
                    new float[] {0.60F, 0.415F, s * (ovw + 0.02F)}, rgb(40, 44, 50));
        }
        quad4(new float[] {-0.02F, 0.53F, -ovw}, new float[] {-0.02F, 0.53F, ovw}, new float[] {CK_REAR, 0.56F, ovw}, new float[] {CK_REAR, 0.56F, -ovw},
                rgb(190, 192, 190));
        disk(new float[] {-0.35F, 0.545F, 0}, new float[] {0, -1, 0}, 0.07F, rgb(240, 236, 220), 20, false);
        quad(new float[] {1.0F, -1.15F, -HW}, new float[] {CK_REAR - 1.0F, 0, 0}, new float[] {0, 0, 2 * HW}, FLOOR);
        // the rear wall (the cockpit side of the bulkhead): the lining texture with the door
        quad4(new float[] {CK_REAR, 0.56F, HW}, new float[] {CK_REAR, 0.56F, -HW}, new float[] {CK_REAR, -1.15F, -HW},
                new float[] {CK_REAR, -1.15F, HW}, rgb(150, 152, 150));
        box(new float[] {CK_REAR + 0.01F, -0.35F, 0}, new float[] {0.02F, 1.5F, 0.62F}, rgb(132, 134, 132));
        // pilots' seats: under the eye (the rider's eye is over his hips), cushion top 0.87 under it
        for (int s = -1; s <= 1; s += 2) {
            float cz = s * 0.52F;
            int col = rgb(60, 66, 80);
            box(new float[] {-0.10F, -0.92F, cz}, new float[] {0.46F, 0.10F, 0.46F}, col, null, LinerTexMath.T_FABRIC, null, -1, null, -1, null);
            box(new float[] {-0.40F, -0.50F, cz}, new float[] {0.12F, 0.76F, 0.46F}, col, rotZ(8));
            box(new float[] {-0.47F, 0.02F, cz}, new float[] {0.10F, 0.22F, 0.30F}, rgb(200, 196, 186), rotZ(8));
            for (int k = -1; k <= 1; k += 2) {
                box(new float[] {-0.12F, -0.74F, cz + k * 0.26F}, new float[] {0.36F, 0.05F, 0.05F}, rgb(40, 42, 48));
            }
            box(new float[] {-0.12F, -1.06F, cz}, new float[] {0.30F, 0.18F, 0.20F}, rgb(40, 42, 46));
        }
    }

    /** A pedestal point (the cockpit frame). */
    private static float[] pp(float tx, float ty) {
        return LinerControls.point(LinerControls.PED, tx, ty, new float[3]);
    }

    /** Window frame strips inside the quad a (top-left) b c d: the pane stays open. */
    private void frameStrips(float[] a, float[] b, float[] c, float[] d, float w, int rgb) {
        float lu = (len(sub(b, a)) + len(sub(c, d))) / 2, lv = (len(sub(d, a)) + len(sub(c, b))) / 2;
        float fu = w / lu, fv = w / lv;
        float[] ia = bil(a, b, c, d, fu, fv), ib = bil(a, b, c, d, 1 - fu, fv), ic = bil(a, b, c, d, 1 - fu, 1 - fv), id = bil(a, b, c, d, fu, 1 - fv);
        quad4(a, b, ib, ia, rgb);
        quad4(id, ic, c, d, rgb);
        quad4(a, ia, id, d, rgb);
        quad4(ib, b, c, ic, rgb);
    }

    /** A wall a b c d with a window hole (s0, t0, s1, t1) framed by posts. */
    private void wallWithHole(float[] a, float[] b, float[] c, float[] d, float[] h, int rgb) {
        float s0 = h[0], t0 = h[1], s1 = h[2], t1 = h[3];
        quad4(bil(a, b, c, d, 0, 0), bil(a, b, c, d, 1, 0), bil(a, b, c, d, 1, t0), bil(a, b, c, d, 0, t0), rgb);
        quad4(bil(a, b, c, d, 0, t1), bil(a, b, c, d, 1, t1), bil(a, b, c, d, 1, 1), bil(a, b, c, d, 0, 1), rgb);
        quad4(bil(a, b, c, d, 0, t0), bil(a, b, c, d, s0, t0), bil(a, b, c, d, s0, t1), bil(a, b, c, d, 0, t1), rgb);
        quad4(bil(a, b, c, d, s1, t0), bil(a, b, c, d, 1, t0), bil(a, b, c, d, 1, t1), bil(a, b, c, d, s1, t1), rgb);
        frameStrips(bil(a, b, c, d, s0, t0), bil(a, b, c, d, s1, t0), bil(a, b, c, d, s1, t1), bil(a, b, c, d, s0, t1), 0.025F, POST);
    }

    private static float[] bil(float[] a, float[] b, float[] c, float[] d, float s, float t) {
        float[] top = lerp(a, b, s), bot = lerp(d, c, s);
        return lerp(top, bot, t);
    }

    // ---------------------------------------------------------------- the cabin (icabin.py)

    static final float X_FRONT = 0, X_REAR = -5.6F, GALLEY_X = -4.65F, SEAT_Z = 0.70F;
    static final float[] ROWS = {-1.30F, -2.15F, -3.00F, -3.85F}, WINDOWS = {-1.15F, -1.85F, -2.55F, -3.25F, -3.95F};
    /** Half cross-section (left wall), floor edge to the ceiling centre: (z, y); parts dado, belt, upper, psu, bin, cove, ceiling. */
    static final float[][] SEC = {{-1.02F, 0}, {-1.10F, 0.68F}, {-1.10F, 1.43F}, {-1.00F, 1.56F}, {-0.72F, 1.62F}, {-0.66F, 1.90F}, {-0.48F, 2.06F},
            {0, 2.08F}};

    private void cabin() {
        frame(CABIN, CAB_X, CAB_Y, 1, CAB_S, CAB_S);
        // the shell, bay by bay along x
        List<Float> xs = new ArrayList<Float>();
        xs.add(X_FRONT);
        xs.add(-0.85F);
        for (int i = 1; i < WINDOWS.length; i++) {
            xs.add(WINDOWS[i] - 0.35F);
        }
        xs.add(WINDOWS[WINDOWS.length - 1] - 0.35F);
        xs.add(GALLEY_X);
        xs.add(X_REAR);
        java.util.Collections.sort(xs, java.util.Collections.reverseOrder());
        for (int k = 0; k + 1 < xs.size(); k++) {
            float xa = xs.get(k), xb = xs.get(k + 1);
            if (Math.abs(xa - xb) < 1e-4F) {
                continue;
            }
            boolean bay = xa <= -0.85F + 1e-4F && xb >= GALLEY_X - 1e-4F;
            for (int s = -1; s <= 1; s += 2) {
                for (int i = 0; i + 1 < SEC.length; i++) {
                    int tex = -1;
                    int rgb = rgb(232, 230, 224);
                    float[] uv = null;
                    boolean emit = false;
                    switch (i) {
                        case 0:                                  // dado
                            tex = LinerTexMath.T_WALL;
                            uv = new float[] {0, 600 / 700F, 1, 1};
                            break;
                        case 1:                                  // window belt
                            if (bay) {
                                tex = LinerTexMath.T_WALL;
                                uv = new float[] {0.15F, 0, 0.85F, 600 / 700F};
                            }
                            break;
                        case 3:                                  // the PSU strip
                            if (bay) {
                                tex = LinerTexMath.T_BINS;
                                uv = new float[] {s < 0 ? 0 : 0.5F, 240 / 360F, s < 0 ? 0.5F : 1, 1};
                            } else {
                                rgb = rgb(206, 206, 202);
                            }
                            break;
                        case 4:                                  // bin door
                            if (bay) {
                                tex = LinerTexMath.T_BINS;
                                uv = new float[] {s < 0 ? 0 : 0.5F, 0, s < 0 ? 0.5F : 1, 240 / 360F};
                            } else {
                                rgb = rgb(226, 226, 222);
                            }
                            break;
                        case 5:                                  // the cove light
                            rgb = rgb(236, 232, 218);
                            emit = true;
                            break;
                        case 6:                                  // ceiling
                            tex = LinerTexMath.T_CEIL;
                            break;
                        default:
                            break;
                    }
                    secQuad(xa, xb, s, i, tex, uv, rgb, emit);
                }
            }
        }
        // floor: the carpet, the emergency path
        for (int k = 0; k < 6; k++) {
            float x0 = -k * 1.0F;
            quad(new float[] {x0, 0, -1.02F}, new float[] {0, 0, 2.04F}, new float[] {-1.0F, 0, 0}, 0xFFFFFF, LinerTexMath.T_CARPET, null, false, false);
        }
        for (float z : new float[] {-0.30F, 0.30F}) {
            for (int k = 0; k < 28; k++) {
                float x = -0.3F - k * 0.16F;
                if (x < GALLEY_X + 0.1F) {
                    break;
                }
                quad(new float[] {x, 0.004F, z - 0.012F}, new float[] {0, 0, 0.024F}, new float[] {-0.06F, 0, 0}, rgb(230, 240, 230), -1, null, true, false);
            }
        }
        // the front bulkhead (its cabin side: the cockpit door, signs, EXIT) and the rear wall
        quad4(new float[] {X_FRONT - 0.002F, 2.04F, -1.10F}, new float[] {X_FRONT - 0.002F, 2.04F, 1.10F}, new float[] {X_FRONT - 0.002F, 0, 1.10F},
                new float[] {X_FRONT - 0.002F, 0, -1.10F}, 0xFFFFFF, LinerTexMath.T_BULK, null, false, false);
        quad4(new float[] {X_REAR, 2.04F, 1.10F}, new float[] {X_REAR, 2.04F, -1.10F}, new float[] {X_REAR, 0, -1.10F}, new float[] {X_REAR, 0, 1.10F},
                rgb(200, 196, 186));
        // the entry doors in the vestibule (L1 left, R1 right): the wall's plain panels
        for (int s = -1; s <= 1; s += 2) {
            float z = s * 1.095F;
            quad4(new float[] {-0.15F, 1.54F, z}, new float[] {-0.80F, 1.54F, z}, new float[] {-0.80F, 0.04F, z}, new float[] {-0.15F, 0.04F, z},
                    rgb(220, 218, 212));
            box(new float[] {-0.475F, 0.80F, z - s * 0.02F}, new float[] {0.30F, 0.04F, 0.03F}, rgb(150, 152, 156));
        }
        // the galley (left rear) and the lavatory (right rear)
        float gz0 = -1.08F, gz1 = -0.32F;
        box(new float[] {(GALLEY_X + X_REAR) / 2, 1.0F, (gz0 + gz1) / 2}, new float[] {GALLEY_X - X_REAR, 2.0F, gz1 - gz0}, rgb(190, 192, 196));
        quad4(new float[] {X_REAR + 0.05F, 2.0F, gz1 + 0.003F}, new float[] {GALLEY_X, 2.0F, gz1 + 0.003F}, new float[] {GALLEY_X, 0, gz1 + 0.003F},
                new float[] {X_REAR + 0.05F, 0, gz1 + 0.003F}, 0xFFFFFF, LinerTexMath.T_GALLEY, null, false, false);
        float wz0 = 0.32F, wz1 = 1.08F;
        box(new float[] {(GALLEY_X + X_REAR) / 2, 1.0F, (wz0 + wz1) / 2}, new float[] {GALLEY_X - X_REAR, 2.0F, wz1 - wz0}, rgb(214, 206, 190));
        // the seats: the fabric cushion, the seat back (the front its fabric, the back its shell), arms, legs, belts
        for (float xr : ROWS) {
            for (int s = -1; s <= 1; s += 2) {
                float zc = s * SEAT_Z;
                box(new float[] {xr, 0.45F, zc}, new float[] {0.50F, 0.13F, 0.50F}, rgb(30, 66, 150), null, LinerTexMath.T_FABRIC, null, -1, null, -1, null);
                box(new float[] {xr + 0.24F, 0.43F, zc}, new float[] {0.06F, 0.12F, 0.50F}, rgb(24, 52, 120));
                box(new float[] {xr - 0.27F, 0.87F, zc}, new float[] {0.10F, 0.72F, 0.50F}, rgb(70, 74, 82), rotZ(9), -1, null,
                        LinerTexMath.T_SEAT, new float[] {1, 0, 0, 590 / 900F}, LinerTexMath.T_SEAT, new float[] {1, 600 / 900F, 0, 1});
                for (int a = -1; a <= 1; a += 2) {
                    box(new float[] {xr - 0.02F, 0.66F, zc + a * 0.27F}, new float[] {0.44F, 0.05F, 0.05F}, rgb(60, 62, 70));
                    box(new float[] {xr - 0.02F, 0.56F, zc + a * 0.27F}, new float[] {0.08F, 0.16F, 0.04F}, rgb(60, 62, 70));
                }
                box(new float[] {xr, 0.19F, zc}, new float[] {0.36F, 0.38F, 0.05F}, rgb(40, 42, 48));
                for (int a = -1; a <= 1; a += 2) {
                    box(new float[] {xr + 0.02F, 0.518F, zc + a * 0.11F}, new float[] {0.05F, 0.006F, 0.20F}, rgb(120, 124, 132));
                }
                box(new float[] {xr + 0.02F, 0.523F, zc}, new float[] {0.07F, 0.01F, 0.06F}, rgb(196, 200, 206));
            }
        }
    }

    /** One segment i of the cross-section between xa and xb on side s (left wall seen from the aisle). */
    private void secQuad(float xa, float xb, int s, int i, int tex, float[] uv, int rgb, boolean emit) {
        float z0 = SEC[i][0], y0 = SEC[i][1], z1 = SEC[i + 1][0], y1 = SEC[i + 1][1];
        float[] a, b, c, d;
        if (s < 0) {
            a = new float[] {xb, y1, z1};
            b = new float[] {xa, y1, z1};
            c = new float[] {xa, y0, z0};
            d = new float[] {xb, y0, z0};
        } else {
            a = new float[] {xa, y1, -z1};
            b = new float[] {xb, y1, -z1};
            c = new float[] {xb, y0, -z0};
            d = new float[] {xa, y0, -z0};
        }
        quad4(a, b, c, d, tex < 0 ? rgb : 0xFFFFFF, tex, uv, emit, false);
    }

    // ---------------------------------------------------------------- vector maths

    static int rgb(int r, int g, int b) {
        return r << 16 | g << 8 | b;
    }

    static int shade(int rgb, double k) {
        int r = (int) Math.min(255, (rgb >> 16 & 255) * k), g = (int) Math.min(255, (rgb >> 8 & 255) * k), b = (int) Math.min(255, (rgb & 255) * k);
        return r << 16 | g << 8 | b;
    }

    /** A turn about +z (deg): the seat backs' rake. */
    static float[][] rotZ(double deg) {
        double a = Math.toRadians(deg);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new float[][] {{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
    }

    /** Column i of R (or the axis) times h. */
    private static float[] col(float[][] R, int i, float h) {
        if (R == null) {
            float[] v = new float[3];
            v[i] = h;
            return v;
        }
        return new float[] {R[0][i] * h, R[1][i] * h, R[2][i] * h};
    }

    static float[] add(float[] a, float[] b) {
        return new float[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static float[] scale(float[] a, float k) {
        return new float[] {a[0] * k, a[1] * k, a[2] * k};
    }

    static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static float len(float[] a) {
        return (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    static float[] norm(float[] a) {
        float l = len(a);
        return l < 1e-9F ? a : scale(a, 1 / l);
    }

    static float[] lerp(float[] a, float[] b, float t) {
        return new float[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }
}
