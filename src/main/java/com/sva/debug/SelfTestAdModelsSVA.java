package com.sva.debug;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airdefense.AdMipMath;
import com.sva.airdefense.AdShapeMath;
import com.sva.airdefense.RadarMath;

/**
 * Multiblock wave self-test (pure logic): the footprints (cells, sizes, the master first, every cell its own), the facing
 * (from the player's yaw, the command post's old meta, the model's turn), the cell <-> world offsets and the part -> master
 * way back, the footprint's centre, the legacy decision, the animation angles (slew, wrap, aim, nod, sweep, recoil, the
 * missiles on the rails), the mipmaps, the models / textures in the jar, the lang keys, the config defaults.
 */
final class SelfTestAdModelsSVA {

    private SelfTestAdModelsSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    static void run() throws Exception {
        footprints();
        facing();
        offsets();
        legacy();
        angles();
        mips();
        resources();
    }

    private static void footprints() {
        int[] count = {11, 5, 6, 4, 10, 4};
        boolean ok = true, firstMaster = true, unique = true, inBox = true;
        StringBuilder sizes = new StringBuilder();
        for (int s = 0; s < AdShapeMath.SHAPES; s++) {
            int[][] c = AdShapeMath.cells(s);
            ok &= c.length == count[s];
            firstMaster &= c[0][0] == 0 && c[0][1] == 0 && c[0][2] == 0 && c[0][3] == AdShapeMath.FULL;
            Set<String> seen = new HashSet<String>();
            int fmin = 0, rmin = 0, rmax = 0;
            for (int[] k : c) {
                unique &= seen.add(k[0] + "," + k[1] + "," + k[2]);
                fmin = Math.min(fmin, k[0]);
                rmin = Math.min(rmin, k[2]);
                rmax = Math.max(rmax, k[2]);
                inBox &= k[0] <= 0 && k[1] >= 0 && (k[3] == AdShapeMath.FULL || k[3] == AdShapeMath.PAD) && (k[1] == 0 || k[3] == AdShapeMath.FULL);
            }
            inBox &= 1 - fmin == AdShapeMath.D[s] && rmax - rmin + 1 == AdShapeMath.W[s];
            // every ground cell of the W x D box is in (no holes in the footprint)
            int ground = 0;
            for (int[] k : c) {
                ground += k[1] == 0 ? 1 : 0;
            }
            inBox &= ground == AdShapeMath.W[s] * AdShapeMath.D[s];
            sizes.append(' ').append(AdShapeMath.W[s]).append('x').append(AdShapeMath.D[s]).append('x').append(AdShapeMath.layers(s));
        }
        check(ok && firstMaster && unique && inBox, "ad models: footprints - survey 3x3 + mast, track / height / gun 2x2, missile 3x3, "
                + "command post 2x1 x 2; the master first, no cell twice, no holes, upper layers solid (" + sizes.toString().trim() + ")");
        check(AdShapeMath.layers(AdShapeMath.SURVEY) == 3 && AdShapeMath.layers(AdShapeMath.HEIGHT) == 3 && AdShapeMath.layers(AdShapeMath.GUN) == 1
                && AdShapeMath.layers(AdShapeMath.KP) == 2, "ad models: heights - the survey mast and the height finder 3, the gun 1, the post 2");
        check(AdShapeMath.shape(0, RadarMath.SURVEY) == AdShapeMath.SURVEY && AdShapeMath.shape(0, RadarMath.TRACK) == AdShapeMath.TRACK
                && AdShapeMath.shape(0, RadarMath.HEIGHT) == AdShapeMath.HEIGHT && AdShapeMath.shape(0, 7) == AdShapeMath.SURVEY
                && AdShapeMath.shape(1, 0) == AdShapeMath.GUN && AdShapeMath.shape(1, 1) == AdShapeMath.MISSILE && AdShapeMath.shape(2, 3) == AdShapeMath.KP,
                "ad models: block kind + meta -> shape (a bad meta: the survey radar)");
        check(near(AdShapeMath.compactScale(AdShapeMath.SURVEY), 1 / 3.0, 1e-6) && near(AdShapeMath.compactScale(AdShapeMath.KP), 0.5, 1e-6),
                "ad models: a compact legacy block draws its model at 1 / (larger side)");
    }

    private static void facing() {
        // the player looks south (yaw 0) -> the structure lies south of him, its front looks north at him
        check(AdShapeMath.facingFromYaw(0F) == 2 && AdShapeMath.facingFromYaw(90F) == 3 && AdShapeMath.facingFromYaw(180F) == 0
                && AdShapeMath.facingFromYaw(-90F) == 1 && AdShapeMath.facingFromYaw(270F) == 1 && AdShapeMath.facingFromYaw(-44F) == 2
                && AdShapeMath.facingFromYaw(46F) == 3, "ad models: the facing from the player's yaw - the front looks back at him");
        boolean same = true;
        for (int look = 0; look < 4; look++) {
            int old = look == 0 ? 2 : look == 1 ? 5 : look == 2 ? 3 : 4;              // the command post's meta before the wave
            int f = AdShapeMath.facingFromYaw(look * 90F);
            same &= AdShapeMath.sideFromFacing(f) == old && AdShapeMath.facingFromSide(old) == f;
        }
        check(same, "ad models: the command post's meta (its screen's side) is the same as before and turns back into the facing");
        boolean turn = true;
        for (int f = 0; f < 4; f++) {
            double a = Math.toRadians(AdShapeMath.modelYaw(f));
            // GL rotation about y: x -> (cos a, -sin a), z -> (sin a, cos a)
            turn &= near(Math.cos(a), AdShapeMath.fx(f), 1e-6) && near(-Math.sin(a), AdShapeMath.fz(f), 1e-6)
                    && near(Math.sin(a), -AdShapeMath.fz(f), 1e-6) && near(Math.cos(a), AdShapeMath.fx(f), 1e-6);
        }
        check(turn, "ad models: the model's turn puts its x on the front and its z on the right (-fz, fx)");
    }

    private static void offsets() {
        boolean inverse = true, back = true, right = true;
        for (int s = 0; s < AdShapeMath.SHAPES; s++) {
            for (int f = 0; f < 4; f++) {
                int[][] w = AdShapeMath.worldCells(s, f);
                int[][] c = AdShapeMath.cells(s);
                for (int i = 0; i < w.length; i++) {
                    int[] l = AdShapeMath.toLocal(f, w[i][0], w[i][2]);
                    inverse &= l[0] == c[i][0] && l[1] == c[i][2] && w[i][1] == c[i][1] && w[i][3] == c[i][3];
                    // a part at master + (dx, dy, dz) finds the master at part - (dx, dy, dz)
                    int px = 100 + w[i][0], py = 64 + w[i][1], pz = -50 + w[i][2];
                    back &= px - w[i][0] == 100 && py - w[i][1] == 64 && pz - w[i][2] == -50;
                    // the rows go away from the front: a row back is against the front's direction
                    if (c[i][0] == -1 && c[i][2] == 0) {
                        back &= w[i][0] == -AdShapeMath.fx(f) && w[i][2] == -AdShapeMath.fz(f);
                    }
                }
            }
        }
        int[] r = AdShapeMath.toWorld(0, 0, 1);                                      // front south: right is west
        right &= r[0] == -1 && r[1] == 0;
        r = AdShapeMath.toWorld(3, 0, 1);                                            // front east: right is south
        right &= r[0] == 0 && r[1] == 1;
        check(inverse && back && right, "ad models: cell <-> world offsets invert, a part's offset leads back to its master, rows away from the "
                + "front, columns to its right");
        double[] t = AdShapeMath.centre(AdShapeMath.TRACK, 0), sv = AdShapeMath.centre(AdShapeMath.SURVEY, 2), k = AdShapeMath.centre(AdShapeMath.KP, 1);
        check(near(t[0], -0.5, 1e-9) && near(t[1], -0.5, 1e-9) && near(sv[0], 0, 1e-9) && near(sv[1], 1, 1e-9) && near(k[0], 0, 1e-9)
                && near(k[1], -0.5, 1e-9), "ad models: the model's centre from the master (2x2 half a block back and right, 3x3 a row back)");
        check(near(AdShapeMath.antennaHeight(AdShapeMath.SURVEY, true), 3.9, 1e-9) && near(AdShapeMath.antennaHeight(AdShapeMath.SURVEY, false), 1.5, 1e-9)
                && near(AdShapeMath.antennaHeight(AdShapeMath.TRACK, true), 1.5, 1e-9), "ad models: the survey radar looks from its mast top, the rest as before");
    }

    private static void legacy() {
        check(AdShapeMath.legacy(true, true) == AdShapeMath.CONVERT && AdShapeMath.legacy(true, false) == AdShapeMath.COMPACT
                && AdShapeMath.legacy(false, true) == AdShapeMath.WAIT && AdShapeMath.legacy(false, false) == AdShapeMath.WAIT,
                "ad models: a legacy block - converts if loaded and free, stays compact if taken, waits for a missing chunk");
        int c = AdShapeMath.COMPACT, w = AdShapeMath.WAIT, v = AdShapeMath.CONVERT;
        check(AdShapeMath.legacyAll(new int[] {c, w, v, c}) == 2 && AdShapeMath.legacyAll(new int[] {v, v, v, v}) == 0
                && AdShapeMath.legacyAll(new int[] {c, w, c, c}) == AdShapeMath.KEEP_WAITING
                && AdShapeMath.legacyAll(new int[] {c, c, c, c}) == AdShapeMath.KEEP_COMPACT,
                "ad models: legacy over the four facings - the first that fits, else wait for chunks, else compact");
    }

    private static void angles() {
        check(near(AdShapeMath.approach(170F, -170F, 5F), 175F, 1e-4) && near(AdShapeMath.approach(10F, 12F, 5F), 12F, 1e-6)
                && near(AdShapeMath.approach(0F, -90F, 6F), -6F, 1e-6) && near(AdShapeMath.wrap180(540F), -180F, 1e-4)
                && near(AdShapeMath.wrap180(-190F), 170F, 1e-4), "ad models: slewing the short way round, at most the rate a tick");
        check(near(AdShapeMath.wrap180(AdShapeMath.lerpAngle(350F, 10F, 0.5F)), 0F, 1e-4) && near(AdShapeMath.lerpAngle(10F, 20F, 0.25F), 12.5F, 1e-4),
                "ad models: partial-tick angles across 360");
        boolean aim = true;
        for (int f = 0; f < 4; f++) {
            int ax = AdShapeMath.fx(f), az = AdShapeMath.fz(f);
            float[] a = AdShapeMath.aim(f, ax * 10, 10, az * 10);                    // straight ahead, 45 deg up
            aim &= near(a[0], 0, 1e-3) && near(a[1], 45, 1e-3);
            float[] b = AdShapeMath.aim(f, -az * 10, 0, ax * 10);                    // to the right (-fz, fx)
            double ry = Math.toRadians(b[0]);
            // the part turned by the yaw: its x (cos, -sin) in local (x, z) must be (0, 1) - the right
            aim &= near(Math.cos(ry), 0, 1e-3) && near(-Math.sin(ry), 1, 1e-3) && near(b[1], 0, 1e-3);
        }
        check(aim, "ad models: aiming - a target ahead gives yaw 0, to the right the turn that brings the barrel there, the elevation");
        check(near(AdShapeMath.sweepStep() * AdShapeMath.SWEEP_TICKS, 360, 1e-3) && AdShapeMath.SWEEP_TICKS == RadarMath.INTERVAL[RadarMath.SURVEY]
                && AdShapeMath.NOD_TICKS == RadarMath.INTERVAL[RadarMath.HEIGHT] && near(AdShapeMath.nod(15), 20, 1e-3)
                && near(AdShapeMath.nod(45), -20, 1e-3) && near(AdShapeMath.nod(60), 0, 1e-3),
                "ad models: the survey antenna turns once a scan (6 s), the height finder nods +-20 deg once a scan (3 s)");
        check(near(AdShapeMath.recoil(0), AdShapeMath.RECOIL, 1e-6) && AdShapeMath.recoil(2) < AdShapeMath.recoil(1) && AdShapeMath.recoil(4) == 0F
                && AdShapeMath.recoil(-1) == 0F && near(AdShapeMath.GUN_YAW_RATE * 20, 120, 1e-3),
                "ad models: the gun's recoil comes home in 4 ticks; its mount turns 120 deg/s (mock-up *3)");
        check(AdShapeMath.visibleMissiles(10, 1) == 3 && AdShapeMath.visibleMissiles(2, 0) == 2 && AdShapeMath.visibleMissiles(0, 0) == 0
                && AdShapeMath.visibleMissiles(4, 5) == 0 && AdShapeMath.visibleMissiles(64, 0) == 4,
                "ad models: missiles on the rails - one a loaded tube while the stock lasts, a fired tube empty until the reload");
        float[] g = AdShapeMath.drive(AdShapeMath.GUN), m = AdShapeMath.drive(AdShapeMath.MISSILE);
        check(g[2] >= g[3] && g[2] <= g[4] && m[2] >= m[3] && m[2] <= m[4] && m[3] > 0 && AdShapeMath.blink(0) != AdShapeMath.blink(10),
                "ad models: rest poses inside the elevation limits, the tube box never flat, the alarm lamp blinks");
    }

    private static void mips() {
        int[] src = {0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFFFFFFFF};
        int[] h = AdMipMath.half(src, 2, 2);
        int[] odd = AdMipMath.half(new int[] {0x80FF0000, 0x8000FF00, 0x800000FF}, 3, 1);
        check(h.length == 1 && h[0] == 0xFF808080 && AdMipMath.levels(512, 512) == 10 && AdMipMath.levels(1, 1) == 1 && odd.length == 1
                && (odd[0] >>> 24) == 0x80, "ad models: mipmaps - the 2x2 average, alpha kept, 512 px -> 10 levels");
    }

    private static void resources() throws Exception {
        String[][] need = {{"rot"}, {"az", "el"}, {"nod"}, {"az", "el", "rec"}, {"az", "el", "m0", "m1", "m2", "m3"}, {"rot", "beacon"}};
        String[][] pivots = {{"rot"}, {"az", "el"}, {"nod"}, {"az", "el"}, {"az", "el"}, {"rot"}};
        String[][] points = {{}, {}, {}, {"muzzle0", "muzzle1"}, {"tube0", "tube3"}, {"beacon"}};
        StringBuilder bad = new StringBuilder();
        Set<String> tex = new HashSet<String>();
        for (int s = 0; s < AdShapeMath.SHAPES; s++) {
            String n = AdShapeMath.MODEL[s];
            Set<String> parts = new HashSet<String>(), piv = new HashSet<String>(), pts = new HashSet<String>();
            int faces = 0;
            BufferedReader r = reader("/assets/siliconagevehicles/models/" + n + ".obj");
            if (r == null) {
                bad.append(' ').append(n).append(".obj");
                continue;
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("g ")) {
                    parts.add(line.substring(2).trim().split("/")[0]);
                } else if (line.startsWith("f ")) {
                    faces++;
                }
            }
            r.close();
            float[] bounds = null;
            r = reader("/assets/siliconagevehicles/models/" + n + ".groups.txt");
            if (r == null) {
                bad.append(' ').append(n).append(".groups.txt");
                continue;
            }
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t[0].equals("mat") && t.length >= 7 && !t[6].equals("-")) {
                    tex.add(t[6]);
                } else if (t[0].equals("pivot")) {
                    piv.add(t[1]);
                } else if (t[0].equals("point")) {
                    pts.add(t[1]);
                } else if (t[0].equals("bounds") && t.length >= 7) {
                    bounds = new float[6];
                    for (int i = 0; i < 6; i++) {
                        bounds[i] = Float.parseFloat(t[i + 1]);
                    }
                }
            }
            r.close();
            for (String p : need[s]) {
                if (!parts.contains(p)) {
                    bad.append(' ').append(n).append(":part ").append(p);
                }
            }
            for (String p : new String[] {"base", "lamp_pow", "lamp_lock", "lamp_empty", "lamp_alarm"}) {
                if (!parts.contains(p)) {
                    bad.append(' ').append(n).append(":part ").append(p);
                }
            }
            for (String p : pivots[s]) {
                if (!piv.contains(p)) {
                    bad.append(' ').append(n).append(":pivot ").append(p);
                }
            }
            for (String p : points[s]) {
                if (!pts.contains(p)) {
                    bad.append(' ').append(n).append(":point ").append(p);
                }
            }
            // the model stands on the ground and its base fits the footprint (moving parts may reach out a little)
            float hd = AdShapeMath.D[s] / 2F, hw = AdShapeMath.W[s] / 2F;
            if (bounds == null || bounds[1] < -0.01F || bounds[0] < -hd - 0.05F || bounds[2] < -hw - 1.1F || bounds[5] > hw + 1.1F
                    || bounds[4] > AdShapeMath.layers(s) + 1.5F || faces < 1000 || faces > 12000) {
                bad.append(' ').append(n).append(":bounds/size ").append(faces);
            }
        }
        for (String t : tex) {
            if (SelfTestAdModelsSVA.class.getResourceAsStream("/assets/siliconagevehicles/textures/models/" + t + ".png") == null) {
                bad.append(' ').append(t).append(".png");
            }
        }
        if (SelfTestAdModelsSVA.class.getResourceAsStream("/assets/siliconagevehicles/textures/models/ad_hazard.png") == null) {
            bad.append(" ad_hazard.png");
        }
        check(bad.length() == 0 && tex.size() >= 10, "ad models: the six models in the jar - their moving parts, pivots, muzzles / tubes, lamps, "
                + "on the ground within the footprint, every texture (" + tex.size() + ")" + bad);
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        boolean lang = ru != null && en != null;
        for (String k : new String[] {"sva.ad.msg.noRoom", "sva.tooltip.ad.size", "tile.siliconagevehicles.adPart.name"}) {
            lang &= ru != null && en != null && ru.contains(k) && en.contains(k);
        }
        check(lang, "ad models: the no-room message, the size tooltip and the part's name in ru and en");
        check(ConfigSVA.adModels && ConfigSVA.adModelTextures && ConfigSVA.adModelDetailDistance == 64,
                "ad models: config defaults - models on, textures on, details within 64 blocks");
    }

    private static BufferedReader reader(String path) throws Exception {
        InputStream in = SelfTestAdModelsSVA.class.getResourceAsStream(path);
        return in == null ? null : new BufferedReader(new InputStreamReader(in, "UTF-8"));
    }
}
