package com.sva.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.vehicle.CockpitView;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerInterior;
import com.sva.vehicle.LinerModelData;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.LinerView;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleType;

/**
 * Cockpit view self-tests (plan-next §8, the user's items 1, 3, 4, 6; vehicle.LinerView, client.LinerViewSVA) - pure
 * maths on the dedicated server: the hand rule; the quick views' angles (each preset from each pilot, the F/O's the
 * mirror image), the picker's key / wheel scheme, the easing and the way back forward; the eye point against the
 * interior's own triangles (LinerInterior: the over-the-nose line and the ground it uncovers, the head under the
 * overhead, the six DUs seen whole and facing the eye, over the HUD at a glance, the FCU / EFIS controls clickable); the
 * seats against the eye; the glazing on the exterior (the painted windscreen against the interior's sill, the wipers
 * moved in the OBJ); the lang lines.
 * <p>
 * The camera is vanilla's (CockpitView.CAMERA_BACK behind the eye along the look); the screen 1920 x 1080 at the default
 * vertical FOV 70; the HUD line HUD_PX over the bottom (the hotbar, hearts, armour at GUI scale 2 - 98 px - and a margin).
 */
final class SelfTestLinerViewSVA {

    /** The HUD's top over the screen's bottom at 1080p (px). */
    static final double HUD_PX = 120;
    static final double FL = 540 / Math.tan(Math.toRadians(35));

    private SelfTestLinerViewSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static String f(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    static void run() throws java.io.IOException {
        hand();
        presets();
        picker();
        blend();
        eye();
        view();
        dus();
        fcu();
        glazing();
        lang();
    }

    // ---------------------------------------------------------------- the hand

    private static void hand() {
        boolean pilot = LinerView.hideHand(true, true, true, true);
        boolean passenger = !LinerView.hideHand(true, true, false, true);
        boolean other = !LinerView.hideHand(true, false, true, true) && !LinerView.hideHand(true, false, false, true);
        boolean third = !LinerView.hideHand(true, true, true, false);
        boolean off = !LinerView.hideHand(false, true, true, true);
        // the pilot seats (the autopilot-fix wave: the hand was still drawn) - the captain rides the vehicle itself, the F/O
        // its seat entity FO_SEAT; any other seat (a passenger), another vehicle's seat, nothing - not a pilot
        boolean seats = LinerView.pilotSeat(true, false, -1) && LinerView.pilotSeat(false, true, LinerView.FO_SEAT) && !LinerView.pilotSeat(false, true, 0)
                && !LinerView.pilotSeat(false, true, 2) && !LinerView.pilotSeat(false, false, LinerView.FO_SEAT) && !LinerView.pilotSeat(false, false, -1);
        check(seats, "liner pilot seats (the hand, the quick views, the server's control rule): the captain on the vehicle itself, the F/O on seat "
                + LinerView.FO_SEAT + "; passengers / other seats not");
        check(pilot && passenger && other && third && off && ConfigSVA.linerHideHand && ConfigSVA.linerQuickViews,
                "liner view: the hand hidden only for a liner pilot in first person with the option on (passengers, other vehicles, third"
                        + " person keep it); linerHideHand / linerQuickViews on by default");
    }

    // ---------------------------------------------------------------- the presets

    private static void presets() {
        StringBuilder rep = new StringBuilder(), bad = new StringBuilder();
        for (int p = 0; p < LinerView.PRESETS; p++) {
            float[] c = LinerView.angles(p, false), o = LinerView.angles(p, true);
            rep.append(' ').append(LinerView.KEYS[p]).append(' ').append(Math.round(c[0])).append('/').append(Math.round(c[1]));
            boolean mirror;
            if (p == LinerView.WIN_L || p == LinerView.WIN_R) {
                float[] m = LinerView.angles(p == LinerView.WIN_L ? LinerView.WIN_R : LinerView.WIN_L, true);
                mirror = Math.abs(c[0] + m[0]) < 0.01 && Math.abs(c[1] - m[1]) < 0.01;
            } else {
                mirror = Math.abs(c[0] + o[0]) < 0.01 && Math.abs(c[1] - o[1]) < 0.01;
            }
            if (!mirror) {
                bad.append(" mirror:").append(LinerView.KEYS[p]);
            }
        }
        float[] pfd = LinerView.angles(LinerView.PFD_ND, false), ecam = LinerView.angles(LinerView.ECAM, false), ov = LinerView.angles(LinerView.OVHD, false);
        float[] ped = LinerView.angles(LinerView.PED, false), wl = LinerView.angles(LinerView.WIN_L, false), wr = LinerView.angles(LinerView.WIN_R, false);
        float[] fw = LinerView.angles(LinerView.FWD, false);
        boolean ranges = fw[0] == 0 && fw[1] == 0 && pfd[0] < -3 && pfd[0] > -20 && pfd[1] > 20 && pfd[1] < 40 && ecam[0] > 15 && ecam[0] < 45
                && ecam[1] > 25 && ecam[1] < 50 && ov[1] < -30 && ov[1] > -70 && ped[1] > 40 && ped[1] < 75 && ped[0] > 15 && wl[0] < -80
                && wl[0] > -130 && wr[0] > 30 && wr[0] < 130 && Math.abs(wl[1] - LinerView.WIN_PITCH) < 1e-4;
        boolean zoom = LinerView.ZOOM[LinerView.FWD] == 1 && LinerView.ZOOM[LinerView.PFD_ND] < LinerView.ZOOM[LinerView.ECAM]
                && LinerView.ZOOM[LinerView.PFD_ND] >= 0.5F && LinerView.ZOOM[LinerView.WIN_L] == 1 && LinerView.KEYS.length == LinerView.PRESETS;
        // the offset from a look: the preset minus the rider's own relative look, the short way round; forward none
        float[] off = LinerView.offset(LinerView.PFD_ND, false, 10F, 5F), back = LinerView.offset(LinerView.FWD, false, 40F, -20F);
        float[] round = LinerView.offset(LinerView.WIN_R, true, -170F, 0F);
        boolean offs = Math.abs(off[0] - (pfd[0] - 10F)) < 1e-3 && Math.abs(off[1] - (pfd[1] - 5F)) < 1e-3 && back[0] == 0 && back[1] == 0
                && Math.abs(round[0]) <= 180F;
        check(bad.length() == 0 && ranges && zoom && offs, "liner view presets (yaw / pitch deg from the captain: + right / + down):" + rep
                + "; the F/O's the mirror image; zoom PFD/ND x" + LinerView.ZOOM[LinerView.PFD_ND] + ", ECAM x" + LinerView.ZOOM[LinerView.ECAM]
                + "; the offset = preset - own look, forward none" + bad);
    }

    private static void picker() {
        LinerView.Picker k = new LinerView.Picker();
        boolean ok = k.current == LinerView.FWD;
        k.press();                                                  // a tap from forward: the favourite (PFD / ND)
        ok &= k.current == LinerView.PFD_ND;
        k.release();
        ok &= k.current == LinerView.PFD_ND;
        k.press();                                                  // a tap again: back forward on the release
        ok &= k.current == LinerView.PFD_ND;
        k.release();
        boolean tapBack = ok && k.current == LinerView.FWD;
        k.press();                                                  // hold + the wheel: PFD/ND -> ECAM -> overhead, then back one
        boolean w = k.wheel(1) && k.wheel(1) && k.wheel(-1);
        boolean held = k.current == LinerView.ECAM && k.held();
        k.release();                                                // let go after the wheel: stays
        boolean stays = k.current == LinerView.ECAM && k.favourite == LinerView.ECAM && !k.held();
        k.press();
        k.release();
        boolean back2 = k.current == LinerView.FWD;
        k.press();                                                  // the next tap goes to the last picked
        k.release();
        boolean fav = k.current == LinerView.ECAM;
        boolean noWheel = !k.wheel(1) && k.current == LinerView.ECAM;     // not held: the wheel is the hotbar's
        k.press();
        k.wheel(-LinerView.ECAM);                                   // the wheel back to «вперёд» itself
        k.release();
        boolean toFwd = k.current == LinerView.FWD && k.favourite == LinerView.ECAM;
        k.press();
        k.reset();                                                  // out of the seat
        boolean reset = k.current == LinerView.FWD && !k.held();
        boolean ring = LinerView.step(LinerView.WIN_R, 1) == LinerView.FWD && LinerView.step(LinerView.FWD, -1) == LinerView.WIN_R
                && LinerView.step(2, 14) == 2;
        check(tapBack && w && held && stays && back2 && fav && noWheel && toFwd && reset && ring,
                "liner view key: a tap - the last picked view (first PFD/ND), the next tap - forward; held + wheel picks live (forward in the"
                        + " ring) and letting go keeps it; the wheel is the hotbar's when not held; leaving the seat - forward");
    }

    private static void blend() {
        boolean ease = LinerView.ease(0) == 0 && LinerView.ease(1) == 1 && Math.abs(LinerView.ease(0.5) - 0.5) < 1e-9 && LinerView.ease(-1) == 0
                && LinerView.ease(2) == 1 && LinerView.ease(0.1) < 0.1 && LinerView.ease(0.9) > 0.9;
        boolean mono = true;
        for (int i = 1; i <= 100; i++) {
            mono &= LinerView.ease(i / 100.0) >= LinerView.ease((i - 1) / 100.0);
        }
        LinerView.Blend b = new LinerView.Blend();
        boolean idle0 = b.idle() && b.yaw() == 0 && b.zoom() == 1;
        b.retarget(40F, 30F, 0.62F);
        b.advance(LinerView.EASE_S / 2);
        boolean half = Math.abs(b.yaw() - 20F) < 1e-3 && Math.abs(b.pitch() - 15F) < 1e-3 && Math.abs(b.zoom() - 0.81F) < 1e-3 && !b.done();
        b.advance(LinerView.EASE_S);
        boolean there = b.done() && Math.abs(b.yaw() - 40F) < 1e-4 && Math.abs(b.zoom() - 0.62F) < 1e-5 && !b.idle();
        b.retarget(0F, 0F, 1F);                                     // back forward
        b.advance(0.1);
        float mid = b.yaw();
        b.advance(0.25);
        boolean home = mid > 0 && mid < 40 && b.idle() && b.yaw() == 0 && b.pitch() == 0 && b.zoom() == 1;
        b.retarget(170F, 0F, 1F);
        b.advance(1);
        b.retarget(-170F, 0F, 1F);                                  // the short way: +20, not -340
        b.advance(LinerView.EASE_S / 2);
        boolean shortWay = Math.abs(b.yaw() - 180F) < 1e-3;
        b.snap(0F, 0F, 1F);
        check(ease && mono && idle0 && half && there && home && shortWay && b.idle(),
                "liner view easing: smoothstep over " + LinerView.EASE_S + " s (half-way at half time), the zoom along, back to forward the same"
                        + " way, a new turn from where it is, the short way round");
    }

    // ---------------------------------------------------------------- the geometry

    /** The cockpit's triangles in the cockpit frame (9 floats each). */
    static List<float[]> cockpit() {
        List<float[]> out = new ArrayList<float[]>();
        for (LinerInterior.Tri t : LinerInterior.build().tris()) {
            if (t.part != LinerInterior.COCKPIT) {
                continue;
            }
            float[] q = t.p.clone();
            for (int i = 0; i < 3; i++) {
                q[i * 3] -= LinerControls.CK_X;
                q[i * 3 + 1] -= LinerControls.CK_Y;
            }
            out.add(q);
        }
        return out;
    }

    /** The nearest hit of the ray o + t d (t > tMin) on the triangles, or +inf. */
    static double hit(List<float[]> tris, double[] o, double[] d, double tMin) {
        double best = Double.POSITIVE_INFINITY;
        for (float[] t : tris) {
            double e1x = t[3] - t[0], e1y = t[4] - t[1], e1z = t[5] - t[2], e2x = t[6] - t[0], e2y = t[7] - t[1], e2z = t[8] - t[2];
            double px = d[1] * e2z - d[2] * e2y, py = d[2] * e2x - d[0] * e2z, pz = d[0] * e2y - d[1] * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-12) {
                continue;
            }
            double inv = 1 / det, sx = o[0] - t[0], sy = o[1] - t[1], sz = o[2] - t[2];
            double u = (sx * px + sy * py + sz * pz) * inv;
            if (u < 0 || u > 1) {
                continue;
            }
            double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            double v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv;
            if (v < 0 || u + v > 1) {
                continue;
            }
            double tt = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (tt > tMin && tt < best) {
                best = tt;
            }
        }
        return best;
    }

    /** A look direction: yaw (+ right), pitch (+ down). */
    static double[] dir(double yaw, double pitch) {
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch);
        return new double[] {Math.cos(b) * Math.cos(a), -Math.sin(b), Math.cos(b) * Math.sin(a)};
    }

    /** Vanilla's camera for the eye e looking along d. */
    static double[] camera(float[] e, double[] d) {
        return new double[] {e[0] - CockpitView.CAMERA_BACK * d[0], e[1] - CockpitView.CAMERA_BACK * d[1], e[2] - CockpitView.CAMERA_BACK * d[2]};
    }

    /** Is the way from the camera (looking at p) to p clear of the triangles? */
    static boolean clear(List<float[]> tris, float[] e, float[] p) {
        double[] d = {p[0] - e[0], p[1] - e[1], p[2] - e[2]};
        double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double[] u = {d[0] / l, d[1] / l, d[2] / l};
        double[] c = camera(e, u);
        return hit(tris, c, u, 1e-4) >= l + CockpitView.CAMERA_BACK - 1e-3;
    }

    /**
     * The over-the-nose cut-off straight ahead (deg below the horizon; negative: over it - no ground at all): the first
     * pitch, from 20 deg over the horizon down, at which the camera's ray meets the cockpit within 3 blocks.
     */
    static double overNose(List<float[]> tris, float[] e) {
        for (double p = -20; p <= 45; p += 0.05) {
            double[] d = dir(0, p);
            if (hit(tris, camera(e, d), d, 1e-4) < 3) {
                return p;
            }
        }
        return 90;
    }

    private static void eye() {
        VehicleType t = VehicleType.LINER;
        double[] c = CockpitView.eye(t, 0), o = CockpitView.eye(t, 1);
        float[] ec = LinerView.eye(false), eo = LinerView.eye(true);
        boolean seats = Math.abs(c[0] - LinerControls.CK_X - ec[0]) < 1e-4 && Math.abs(c[1] - LinerControls.CK_Y - ec[1]) < 1e-4
                && Math.abs(c[2] - ec[2]) < 1e-4 && Math.abs(o[0] - LinerControls.CK_X - eo[0]) < 1e-4 && Math.abs(o[2] - eo[2]) < 1e-4;
        float[] old = LinerView.oldEyeModel(false);
        boolean moved = c[0] > old[0] + 0.05 && c[1] > old[1] + 0.03 && Math.abs(c[2]) <= Math.abs(old[2]) && Math.abs(c[1] - LinerView.eyeHeight()) < 1e-4;
        // the head (the rider's 0.469 cube on the eye: top eye + 0.255) under the overhead / the roof lining: rays straight up
        List<float[]> tris = cockpit();
        double minUp = Double.POSITIVE_INFINITY;
        for (int s = -1; s <= 1; s += 2) {
            float[] e = LinerView.eye(s > 0);
            for (double dx = -0.214; dx <= 0.215; dx += 0.107) {
                for (double dz = -0.214; dz <= 0.215; dz += 0.107) {
                    double[] p = {e[0] + dx, e[1] + 0.255, e[2] + dz};
                    minUp = Math.min(minUp, hit(tris, p, new double[] {0, 1, 0}, 0));
                }
            }
        }
        check(seats && moved && minUp >= 0.05 && minUp < 1, "liner view eye: the pilots' seats put the eyes at the cockpit frame's ("
                + ec[0] + ", " + ec[1] + ", +-" + Math.abs(ec[2]) + ") = model (" + f(c[0]) + ", " + f(c[1]) + ") - forward, up and inboard of wave 1's ("
                + old[0] + ", " + old[1] + ", +-" + Math.abs(old[2]) + "); the head's top "
                + f(minUp) + " under the overhead / lining (>= 0.05)");
    }

    private static void view() {
        List<float[]> tris = cockpit();
        float[] e = LinerView.eye(false), eo = LinerView.eye(true);
        double cut = overNose(tris, e), cutFo = overNose(tris, eo);
        double ground = LinerView.eyeHeight() / Math.tan(Math.toRadians(cut));
        // the windows: the side-window preset's own ray leaves through its window (both pilots, both windows)
        boolean win = true;
        for (int s = 0; s < 2; s++) {
            for (int p = LinerView.WIN_L; p <= LinerView.WIN_R; p++) {
                float[] a = LinerView.angles(p, s == 1), ee = LinerView.eye(s == 1);
                double[] d = dir(a[0], a[1]);
                win &= hit(tris, camera(ee, d), d, 1e-4) > 2.2;
            }
        }
        float[][] sw = LinerInterior.sideWindow(-1);
        double sill = Math.min(sw[2][1], sw[3][1]), top = Math.max(sw[0][1], sw[1][1]);
        check(cut > 5 && cut < 15 && Math.abs(cut - cutFo) < 0.2 && ground > 10 && ground < 32 && win && sill < e[1] - 0.05 && top > e[1] + 0.25,
                "liner view: over the nose " + f(cut) + " deg down straight ahead (wave 1: the glare shield over the eye - none), the ground from "
                        + f(ground) + " blocks ahead (eye " + f(LinerView.eyeHeight()) + " over it); the side windows from " + f(sill - e[1]) + " to +"
                        + f(top - e[1]) + " of the eye, each window view looks out through its glass");
    }

    /** {x, y} on the 1920 x 1080 screen of p for the eye looking straight ahead pitched `pitch` down (vanilla's camera). */
    static double[] screen(float[] e, float[] p, double pitch) {
        double[] f = dir(0, pitch), up = {Math.sin(Math.toRadians(pitch)), Math.cos(Math.toRadians(pitch)), 0};
        double[] c = camera(e, f);
        double dx = p[0] - c[0], dy = p[1] - c[1], dz = p[2] - c[2];
        double d = dx * f[0] + dy * f[1] + dz * f[2];
        return new double[] {960 + dz / d * FL, 540 - (dx * up[0] + dy * up[1]) / d * FL};
    }

    /** The MP's normal toward the crew. */
    static float[] mpNormal() {
        float[] fl = LinerControls.FLAT[LinerControls.MP];
        float[] u = {fl[3], fl[4], fl[5]}, v = {fl[6], fl[7], fl[8]};
        float[] n = {v[1] * u[2] - v[2] * u[1], v[2] * u[0] - v[0] * u[2], v[0] * u[1] - v[1] * u[0]};
        float l = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        n = new float[] {n[0] / l, n[1] / l, n[2] / l};
        return n[0] < 0 ? n : new float[] {-n[0], -n[1], -n[2]};
    }

    static float[] duPoint(int du, double a, double b, float lift) {
        float[] r = LinerView.DU_RECTS[du];
        float[] p = LinerControls.point(LinerControls.MP, (float) (r[0] + (r[2] - r[0]) * a), (float) (r[1] + (r[3] - r[1]) * b), new float[3]);
        float[] n = mpNormal();
        return new float[] {p[0] + n[0] * lift, p[1] + n[1] * lift, p[2] + n[2] * lift};
    }

    /** Pixels at 1080p (vertical FOV 70 x zoom) of `deg` near the screen's middle. */
    static double px(double deg, double zoom) {
        double fov = 70 * zoom;
        return 1080 / (2 * Math.tan(Math.toRadians(fov / 2))) * 2 * Math.tan(Math.toRadians(deg / 2));
    }

    private static void dus() {
        List<float[]> tris = cockpit();
        int[] capt = {LinerSystems.DU_PFD1, LinerSystems.DU_ND1, LinerSystems.DU_EWD, LinerSystems.DU_SD};
        int[] fo = {LinerSystems.DU_EWD, LinerSystems.DU_SD, LinerSystems.DU_ND2, LinerSystems.DU_PFD2};
        StringBuilder bad = new StringBuilder(), sizes = new StringBuilder();
        float[] n = mpNormal();
        for (int side = 0; side < 2; side++) {
            float[] e = LinerView.eye(side == 1);
            for (int du : side == 0 ? capt : fo) {
                int blocked = 0, all = 0;
                for (int i = 0; i < 5; i++) {
                    for (int j = 0; j < 5; j++) {
                        all++;
                        if (!clear(tris, e, duPoint(du, 0.05 + 0.225 * i, 0.05 + 0.225 * j, 0.006F))) {
                            blocked++;
                        }
                    }
                }
                float[] cc = duPoint(du, 0.5, 0.5, 0);
                double[] w = {e[0] - cc[0], e[1] - cc[1], e[2] - cc[2]};
                double wl = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
                double face = Math.toDegrees(Math.acos((w[0] * n[0] + w[1] * n[1] + w[2] * n[2]) / wl));
                if (blocked > 1 || face > 60) {
                    bad.append(" du").append(du).append(side == 0 ? "/capt" : "/fo").append(": hidden ").append(blocked).append('/').append(all)
                            .append(", facing ").append(Math.round(face));
                }
            }
        }
        // the captain's sizes (deg / px at 1080p; the PFD also at the preset's zoom)
        float[] e = LinerView.eye(false);
        for (int du = 0; du < 6; du++) {
            float[] l = LinerView.look(e, duPoint(du, 0, 0.5, 0)), r = LinerView.look(e, duPoint(du, 1, 0.5, 0));
            float[] t = LinerView.look(e, duPoint(du, 0.5, 0, 0)), b = LinerView.look(e, duPoint(du, 0.5, 1, 0));
            double wd = Math.abs(r[0] - l[0]), hd = Math.abs(b[1] - t[1]);
            sizes.append(' ').append(new String[] {"PFD1", "ND1", "EWD", "SD", "ND2", "PFD2"}[du]).append(' ').append(f(wd)).append('x').append(f(hd))
                    .append(" deg ").append(Math.round(px(wd, 1))).append('x').append(Math.round(px(hd, 1))).append(" px");
            if (du == LinerSystems.DU_PFD1) {
                sizes.append(" (zoomed ").append(Math.round(px(wd, LinerView.ZOOM[LinerView.PFD_ND]))).append(" px)");
            }
            sizes.append(';');
        }
        // over the HUD: the pitch at which the top row (PFD / ND / E/WD) and then all four own DUs clear the HUD line, the
        // over-the-nose line still on the screen
        double cut = overNose(tris, e);
        double topRow = -1, all = -1;
        for (double p = -10; p <= 45 && all < 0; p += 0.1) {
            boolean row = true, four = true;
            for (int du : capt) {
                for (int i = 0; i <= 4; i++) {
                    double[] s = screen(e, duPoint(du, i / 4.0, 1, 0), p);
                    boolean above = s[1] < 1080 - HUD_PX && s[1] > 0 && s[0] > 0 && s[0] < 1920;
                    four &= above;
                    if (du != LinerSystems.DU_SD) {
                        row &= above;
                    }
                }
            }
            if (row && topRow < 0) {
                topRow = p;
            }
            if (four) {
                all = p;
            }
        }
        double cutTopAll = 540 - FL * Math.tan(Math.toRadians(all - cut)), cutTopRow = 540 - FL * Math.tan(Math.toRadians(topRow - cut));
        check(bad.length() == 0 && topRow >= 0 && topRow <= 15 && all > 0 && all <= 30 && cutTopAll > 40 && cutTopRow > 40,
                "liner view DUs: the six seen whole from their pilots (<= 1 of 25 samples hidden, facing the eye within 60 deg);" + sizes
                        + " over the HUD (" + Math.round(HUD_PX) + " px at 1080p): the PFD / ND / E/WD row from " + f(topRow) + " deg down, all four with"
                        + " the SD from " + f(all) + " deg - the over-the-nose line still " + Math.round(cutTopRow) + " / " + Math.round(cutTopAll)
                        + " px under the screen's top" + bad);
    }

    /** Every control on the glare shield is clickable: nothing of the cockpit between the camera and its face. */
    private static void fcu() {
        List<float[]> tris = cockpit();
        StringBuilder bad = new StringBuilder();
        int n = 0;
        for (LinerControls.Ctl c : LinerControls.all()) {
            if (c.panel != LinerControls.GS || c.kind == LinerControls.VIRT) {
                continue;
            }
            float[] p = LinerControls.point(LinerControls.GS, c.cx, c.cy, new float[3]);
            p[0] -= 0.006F;                                         // just in front of the face (toward the crew)
            boolean capt = clear(tris, LinerView.eye(false), p), fo = clear(tris, LinerView.eye(true), p);
            boolean hitOk = LinerControls.hit(LinerControls.GS, c.cx, c.cy) == c;
            // each pilot must reach his half and the FCU in the middle; the far EFIS is the other pilot's
            boolean mine = c.cx < 1100 ? capt : fo, middle = c.cx > 640 && c.cx < 1360;
            if (!mine || middle && !(capt && fo) || !hitOk) {
                bad.append(' ').append(c.id);
            }
            n++;
        }
        float[] top = LinerControls.point(LinerControls.GS, 1000, 0, new float[3]);
        check(bad.length() == 0 && n >= 20 && Math.abs(top[1] - LinerInterior.GS_Y1) < 1e-5 && Math.abs(top[0] - LinerInterior.GS_X) < 1e-5,
                "liner view FCU / EFIS: all " + n + " glare-shield controls in clear view of their pilot (the FCU of both), hit() finds each;"
                        + " the strip's top on the glare shield's edge" + bad);
    }

    /** fpt of liner3d.py: the skin at x, theta (deg from the top), side, dr. */
    static float[] skin(float x, double th, int side, float dr) {
        double r = LinerModelData.fr(x) + dr, a = Math.toRadians(th);
        return new float[] {x, (float) (LinerModelData.fcy(x) + r * Math.cos(a)), (float) (side * r * Math.sin(a))};
    }

    private static void glazing() throws java.io.IOException {
        // the painted windscreen's lower edge against the interior's sill (model y at the sill's front x)
        double[][] ws = LinerView.PAINT_WINDOWS[0];
        float sx = LinerControls.CK_X + LinerInterior.FRONT_X;
        double k = (sx - ws[2][0]) / (ws[3][0] - ws[2][0]);
        double th = ws[2][1] + (ws[3][1] - ws[2][1]) * k;
        double paintY = skin(sx, th, 1, 0)[1], sillY = LinerControls.CK_Y + LinerInterior.SILL_Y;
        double[][] sl = LinerView.PAINT_WINDOWS[1], aft = LinerView.PAINT_WINDOWS[2];
        double post1 = ws[2][0] - sl[0][0], post2 = sl[2][0] - aft[0][0];
        boolean bigger = sl[2][1] - sl[0][1] > 68 - 44 && aft[2][1] - aft[0][1] > 66 - 47 && ws[2][1] > 37 && ws[3][1] > 43;
        // the wipers in the OBJ: at the new sill, none left at wave 1's place (both sides; LOD 0, 1)
        String dir = "/assets/siliconagevehicles/" + LinerModelData.DIR;
        boolean wipers = true;
        for (int lod = 0; lod < 2; lod++) {
            List<float[]> v = verts(dir + LinerModelData.objName(lod), "details");
            for (int s = -1; s <= 1; s += 2) {
                wipers &= near(v, skin(8.16F, 54, s, 0.015F), 0.002) && !near(v, skin(8.18F, 35, s, 0.015F), 0.02);
            }
        }
        // the interior's glass (the frost rim) is the shell's own panes; the frames thinner than wave 1's
        float[][][] l = LinerInterior.panes(-1, 0F);
        boolean panes = Math.abs(l[0][2][1] - LinerInterior.SILL_Y) < 1e-6 && Math.abs(l[0][3][2] + LinerInterior.FOOT_Z) < 1e-6
                && LinerInterior.FRAME_W < 0.045F && LinerInterior.CORNER_W < 0.05F && LinerInterior.SIDE_W < 0.025F;
        check(Math.abs(paintY - sillY) < 0.06 && bigger && post1 > 0 && post1 < 0.06 && post2 > 0 && post2 < 0.06 && wipers && panes,
                "liner glazing: the painted windscreen's lower edge " + f(paintY) + " at x " + f(sx) + " on the interior's sill " + f(sillY)
                        + " (wave 1: 2.81 vs 2.74); the side windows taller, the posts " + f(post1) + " / " + f(post2) + "; the wipers moved in LOD 0 / 1;"
                        + " the frames " + LinerInterior.FRAME_W + " / " + LinerInterior.CORNER_W + " / " + LinerInterior.SIDE_W + " (wave 1: 0.045 / 0.05 / 0.025)");
    }

    static boolean near(List<float[]> v, float[] p, double r) {
        for (float[] q : v) {
            double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    /** The vertices listed under group g of an OBJ resource. */
    static List<float[]> verts(String path, String g) throws java.io.IOException {
        List<float[]> out = new ArrayList<float[]>();
        java.io.InputStream in = SelfTestLinerViewSVA.class.getResourceAsStream(path);
        if (in == null) {
            return out;
        }
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"), 1 << 16);
        try {
            String line;
            boolean take = false;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("g ")) {
                    take = g.equals(line.substring(2).trim());
                } else if (take && line.startsWith("v ")) {
                    String[] p = line.trim().split("\\s+");
                    out.add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                }
            }
        } finally {
            r.close();
        }
        return out;
    }

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        StringBuilder miss = new StringBuilder();
        List<String> keys = new ArrayList<String>();
        keys.add("sva.liner.view.title");
        keys.add(VehicleFn.VIEW.nameKey(VehicleType.LINER));
        keys.add(VehicleFn.VIEW.tipKey(VehicleType.LINER));
        for (String k : LinerView.KEYS) {
            keys.add("sva.liner.view." + k);
        }
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "liner view lang: the key, its hint, the view names and the title line in ru and en" + miss);
    }
}
