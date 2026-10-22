package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sva.ConfigSVA;
import com.sva.airdefense.AdTerrainDataSVA;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.CoverageMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.TerrainMath;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Audit 2026-10-22 self-test (combat effectiveness and terrain) - pure logic, the dedicated server runs it:
 * <ul>
 * <li>bug 3: a radar's mark led by its age - a gun aiming at the led mark meets a crossing plane, at the mark itself it
 * misses by the plane's speed times the age;</li>
 * <li>bug 4: a shell's step walked block by block - a wall the step's middle crosses stops it (the step's end alone would
 * have missed it), corners, both directions, the launcher's own blocks skipped;</li>
 * <li>bug 10: the terrain memory - a column's ground (the water's surface, a fence on top, leaves over the ground), an
 * unloaded column opaque below its last known height (unknown - transparent as before), a beam over an unloaded ridge,
 * the sites' reach, the saved data's NBT;</li>
 * <li>bug 16: the RWR's paint list - the expired go first, a full list makes room for the newest;</li>
 * <li>the low items: the weather of the radar's biome, warnings that went to an empty cockpit start over, a kept coverage
 * map fits only its own antenna point and reach, the config defaults.</li>
 * </ul>
 */
final class SelfTestAdCombatSVA {

    private SelfTestAdCombatSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        lead();
        walk();
        terrain();
        paint();
        low();
    }

    // ---------------------------------------------------------------- bug 3

    /** The closest a shell fired from the origin along `dir` (GUN_SPEED) comes to a plane at p moving with v, over 60 ticks. */
    private static double miss(double[] dir, double[] p, double[] v) {
        double best = Double.MAX_VALUE;
        for (double k = 0.05; k <= 60; k += 0.05) {
            double sx = dir[0] * BallisticsMath.GUN_SPEED * k, sy = dir[1] * BallisticsMath.GUN_SPEED * k, sz = dir[2] * BallisticsMath.GUN_SPEED * k;
            double tx = p[0] + v[0] * k, ty = p[1] + v[1] * k, tz = p[2] + v[2] * k;
            best = Math.min(best, BallisticsMath.dist(sx, sy, sz, tx, ty, tz));
        }
        return best;
    }

    private static void lead() {
        double[] l = BallisticsMath.led(10, 70, -5, 1.12, 0.1, 0, 15);
        check(Math.abs(l[0] - 26.8) < 1e-9 && Math.abs(l[1] - 71.5) < 1e-9 && l[2] == -5,
                "ad combat (bug 3): a mark 15 ticks old moved on by the velocity (" + l[0] + ", " + l[1] + ")");
        double[] far = BallisticsMath.led(0, 0, 0, 1, 0, 0, 100000), back = BallisticsMath.led(0, 0, 0, 1, 0, 0, -5);
        check(far[0] == BallisticsMath.MAX_LEAD_AGE && back[0] == 0, "ad combat (bug 3): the age clamped to 0 .. the track's timeout");
        // a plane crossing 60 blocks off at 1.12 b/t, the post's mark 15 ticks old
        double[] now = {-20, 20, 60}, vel = {1.12, 0, 0};
        long age = 15;
        double[] mark = {now[0] - vel[0] * age, now[1], now[2]};
        double[] led = BallisticsMath.led(mark[0], mark[1], mark[2], vel[0], vel[1], vel[2], age);
        double[] good = BallisticsMath.intercept(0, 0, 0, led[0], led[1], led[2], vel[0], vel[1], vel[2], BallisticsMath.GUN_SPEED, BallisticsMath.SHELL_LIFE);
        double[] old = BallisticsMath.intercept(0, 0, 0, mark[0], mark[1], mark[2], vel[0], vel[1], vel[2], BallisticsMath.GUN_SPEED, BallisticsMath.SHELL_LIFE);
        double mGood = miss(BallisticsMath.dir(0, 0, 0, good[0], good[1], good[2]), now, vel);
        double mOld = miss(BallisticsMath.dir(0, 0, 0, old[0], old[1], old[2]), now, vel);
        check(mGood < 0.5 && mOld > 10, "ad combat (bug 3): the led mark meets a crossing plane (" + Math.round(mGood * 100) / 100.0
                + " blocks), the mark itself misses by " + Math.round(mOld * 10) / 10.0 + " (the hit radius 2.1)");
        // the server's order: a shell steps at the end of the tick it is fired in, the plane moves at the next tick's start
        double worstAim = 0, worstPlain = 0;
        for (double x = -24; x < -20; x += 0.05) {
            double[] p = {x, 20, 60};
            double[] a1 = BallisticsMath.gunAim(0, 0, 0, p[0], p[1], p[2], vel[0], vel[1], vel[2], BallisticsMath.GUN_SPEED, BallisticsMath.SHELL_LIFE);
            double[] a0 = BallisticsMath.intercept(0, 0, 0, p[0], p[1], p[2], vel[0], vel[1], vel[2], BallisticsMath.GUN_SPEED, BallisticsMath.SHELL_LIFE);
            worstAim = Math.max(worstAim, steps(BallisticsMath.dir(0, 0, 0, a1[0], a1[1], a1[2]), p, vel));
            worstPlain = Math.max(worstPlain, steps(BallisticsMath.dir(0, 0, 0, a0[0], a0[1], a0[2]), p, vel));
        }
        check(worstAim < 0.5 && worstAim < worstPlain - 0.2, "ad combat (bug 3): the gun's aim from half a tick back - step by step the shells pass the plane at most "
                + Math.round(worstAim * 100) / 100.0 + " blocks off (the plain intercept up to " + Math.round(worstPlain * 100) / 100.0 + ")");
    }

    /** Shell steps (GUN_SPEED at the end of ticks 0, 1, ...) vs a plane at p moving with v from tick 1 on: the closest a step's segment comes. */
    private static double steps(double[] dir, double[] p, double[] v) {
        double best = Double.MAX_VALUE;
        for (int j = 1; j <= 40; j++) {
            double s0 = BallisticsMath.GUN_SPEED * (j - 1), s1 = BallisticsMath.GUN_SPEED * j;
            double tx = p[0] + v[0] * (j - 1), ty = p[1] + v[1] * (j - 1), tz = p[2] + v[2] * (j - 1);
            double d2 = BallisticsMath.segToPoint2(dir[0] * s0, dir[1] * s0, dir[2] * s0, dir[0] * s1, dir[1] * s1, dir[2] * s1, tx, ty, tz);
            best = Math.min(best, Math.sqrt(d2));
        }
        return best;
    }

    // ---------------------------------------------------------------- bug 4

    /** A probe of a few solid blocks {x, y, z}; `skip` - a block that never stops (the launcher's own). */
    private static RadarMath.Probe blocks(final int[][] solid, final int[] skip) {
        return new RadarMath.Probe() {
            @Override
            public boolean blocks(int x, int y, int z) {
                if (skip != null && x == skip[0] && y == skip[1] && z == skip[2]) {
                    return false;
                }
                for (int[] b : solid) {
                    if (b[0] == x && b[1] == y && b[2] == z) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private static void walk() {
        RadarMath.Probe wall = blocks(new int[][] {{6, 0, 0}}, null);
        double clear = BallisticsMath.firstHit(0.5, 0.5, 0.5, 4.5, 0.5, 0.5, wall);
        double t = BallisticsMath.firstHit(4.5, 0.5, 0.5, 8.5, 0.5, 0.5, wall);
        boolean endOnly = wall.blocks(8, 0, 0);                       // the old rule: the step's end block only
        check(clear < 0 && Math.abs(t - 0.375) < 1e-9 && !endOnly,
                "ad combat (bug 4): a 4-block step whose middle crosses a 1-block wall stops where it enters it (t " + t + "), the end block alone never saw it");
        double back = BallisticsMath.firstHit(9.5, 0.5, 0.5, 5.5, 0.5, 0.5, wall);
        check(Math.abs(back - 0.625) < 1e-9, "ad combat (bug 4): the same wall flown at backwards (t " + back + ")");
        double corner = BallisticsMath.firstHit(0.5, 0.5, 0.5, 2.5, 2.5, 0.5, blocks(new int[][] {{1, 1, 0}}, null));
        double diag = BallisticsMath.firstHit(0.2, 0.5, 0.5, 3.2, 3.5, 0.5, blocks(new int[][] {{1, 1, 0}}, null));
        check(corner >= 0 && corner <= 1 && diag > 0 && diag < 1, "ad combat (bug 4): a diagonal step through a block's corner / side is stopped");
        RadarMath.Probe home = blocks(new int[][] {{0, 1, 0}, {3, 1, 0}}, new int[] {0, 1, 0});
        double out = BallisticsMath.firstHit(0.5, 1.5, 0.5, 2.5, 1.5, 0.5, home);
        double far = BallisticsMath.firstHit(0.5, 1.5, 0.5, 4.5, 1.5, 0.5, home);
        check(out < 0 && Math.abs(far - 0.625) < 1e-9, "ad combat (bug 4): the launcher's own block it starts in is passed, the next wall is not");
        check(BallisticsMath.firstHit(0.5, 0.5, 0.5, 0.9, 0.5, 0.5, blocks(new int[][] {{0, 0, 0}}, null)) == 0
                && BallisticsMath.firstHit(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, wall) < 0, "ad combat (bug 4): a step that starts inside a block is stopped at once; a still one passes");
    }

    // ---------------------------------------------------------------- bug 10

    /** A synthetic column: stone below `stone`, water stone..water-1, a stopper at `extra` (-1 none), leaves leafFrom..leafTo. */
    private static TerrainMath.Column column(final int stone, final int water, final int extra, final int leafFrom, final int leafTo) {
        return new TerrainMath.Column() {
            @Override
            public boolean stops(int y) {
                return y < stone || y == extra;
            }

            @Override
            public boolean water(int y) {
                return y >= stone && y < water && !(y >= leafFrom && y <= leafTo);
            }
        };
    }

    private static void terrain() {
        int sea = TerrainMath.ground(70, column(64, 70, -1, -1, -1));
        int fence = TerrainMath.ground(82, column(80, 80, 81, -1, -1));
        int forest = TerrainMath.ground(76, column(65, 65, -1, 70, 75));
        int hill = TerrainMath.ground(255, column(120, 120, -1, -1, -1));
        check(sea == 70 && fence == 82 && forest == 65 && hill == 120,
                "ad combat (bug 10): a column's ground - the sea's surface (" + sea + "), a fence on top (" + fence + "), the ground under leaves (" + forest + ")");
        byte[] col = new byte[TerrainMath.BYTES];
        TerrainMath.put(col, 37, -3, 90, 0);
        check(TerrainMath.opaque(col, 37, 89, -3) && !TerrainMath.opaque(col, 37, 90, -3) && !TerrainMath.opaque(col, 38, 0, -3)
                && !TerrainMath.opaque(null, 37, 10, -3) && TerrainMath.height(col, 37 + 16, -3 - 32) == 90 && TerrainMath.pack(300) == (byte) 255
                && !TerrainMath.opaque(new byte[TerrainMath.COLUMNS], 37, 10, -3),
                "ad combat (bug 10): an unloaded column is opaque below its last known ground; never seen (or the old one-byte memory) - transparent");
        // audit review 2026-10-22: a slab on a pole at 250 over the ground at 64, an overhang 90..95 over a valley at 70, the
        // Nether's roof 123..127 over its caverns - only the top layer is solid (it was the whole column from the floor)
        TerrainMath.Column slab = column(64, 64, 250, -1, -1), over = new TerrainMath.Column() {
            @Override
            public boolean stops(int y) {
                return y < 70 || y >= 90 && y <= 95;
            }

            @Override
            public boolean water(int y) {
                return false;
            }
        }, nether = new TerrainMath.Column() {
            @Override
            public boolean stops(int y) {
                return y < 31 || y >= 100 && y <= 127;
            }

            @Override
            public boolean water(int y) {
                return false;
            }
        };
        int gs = TerrainMath.ground(251, slab), go = TerrainMath.ground(96, over), gn = TerrainMath.ground(128, nether);
        byte[] lay = new byte[TerrainMath.BYTES];
        TerrainMath.put(lay, 1, 1, gs, TerrainMath.bottom(gs, slab));
        TerrainMath.put(lay, 2, 1, go, TerrainMath.bottom(go, over));
        TerrainMath.put(lay, 3, 1, gn, TerrainMath.bottom(gn, nether));
        int sb = TerrainMath.bottom(TerrainMath.ground(70, column(64, 70, -1, -1, -1)), column(64, 70, -1, -1, -1));
        check(gs == 251 && TerrainMath.bottom(lay, 1, 1) == 250 && TerrainMath.opaque(lay, 1, 250, 1) && !TerrainMath.opaque(lay, 1, 150, 1)
                && !TerrainMath.opaque(lay, 1, 60, 1) && go == 96 && TerrainMath.opaque(lay, 2, 92, 1) && !TerrainMath.opaque(lay, 2, 80, 1)
                && gn == 128 && TerrainMath.opaque(lay, 3, 110, 1) && !TerrainMath.opaque(lay, 3, 60, 1) && sb == 0 && TerrainMath.height(lay, 1, 1) == 251,
                "ad combat (review, bug 10): the memory keeps a column's top solid layer only - a slab at 250 is no wall from the floor (a beam at 150 "
                        + "passes), under an overhang / the Nether's roof is clear; the sea stays solid to its floor (" + gs + "/" + TerrainMath.bottom(lay, 1, 1)
                        + ", " + go + "/" + TerrainMath.bottom(lay, 2, 1) + ", " + gn + "/" + TerrainMath.bottom(lay, 3, 1) + ")");
        long k = TerrainMath.key(-3, 7);
        check(TerrainMath.keyX(k) == -3 && TerrainMath.keyZ(k) == 7 && TerrainMath.key(1, -1) != TerrainMath.key(-1, 1), "ad combat (bug 10): chunk keys");
        // a beam over a ridge 40..44 blocks out, its chunks unloaded: the memory hides the plane, without it the beam went through
        final byte[] ridge = new byte[TerrainMath.BYTES];
        for (int i = 0; i < TerrainMath.COLUMNS; i++) {
            ridge[i] = TerrainMath.pack(i % 16 >= 8 && i % 16 <= 12 ? 100 : 0);
        }
        // audit review: the same line of slabs at 100 over flat ground - a beam under it passes
        final byte[] slabs = ridge.clone();
        for (int i = 0; i < TerrainMath.COLUMNS; i++) {
            slabs[TerrainMath.COLUMNS + i] = TerrainMath.pack(i % 16 >= 8 && i % 16 <= 12 ? 99 : 0);
        }
        RadarMath.Probe slabMem = new RadarMath.Probe() {
            @Override
            public boolean blocks(int x, int y, int z) {
                return x >= 32 && x < 48 && TerrainMath.opaque(slabs, x, y, z);
            }
        };
        check(RadarMath.losClear(0.5, 80, 0.5, 90.5, 82, 0.5, slabMem) && !RadarMath.losClear(0.5, 99.5, 0.5, 90.5, 99.5, 0.5, slabMem),
                "ad combat (review, bug 10): a remembered line of slabs at 100 hides nothing under it - only what crosses the slabs");
        RadarMath.Probe mem = new RadarMath.Probe() {
            @Override
            public boolean blocks(int x, int y, int z) {
                return x >= 32 && x < 48 && TerrainMath.opaque(ridge, x, y, z);   // the chunk 32..47 unloaded, remembered
            }
        };
        RadarMath.Probe none = new RadarMath.Probe() {
            @Override
            public boolean blocks(int x, int y, int z) {
                return false;                                       // the old rule: an unloaded block never stops
            }
        };
        check(!RadarMath.losClear(0.5, 80, 0.5, 90.5, 82, 0.5, mem) && RadarMath.losClear(0.5, 80, 0.5, 90.5, 82, 0.5, none)
                && RadarMath.losClear(0.5, 110, 0.5, 90.5, 112, 0.5, mem),
                "ad combat (bug 10): a plane behind an unloaded ridge stays hidden (before - seen through it); over the ridge it is seen");
        List<int[]> sites = new ArrayList<int[]>();
        sites.add(new int[] {100, 100, 640});
        sites.add(new int[] {-5000, 0, TerrainMath.LAUNCHER_REACH});
        check(TerrainMath.near(6, 6, sites, 640) && TerrainMath.near((100 + 630) >> 4, 6, sites, 640) && !TerrainMath.near((100 + 700) >> 4, 6, sites, 640)
                && TerrainMath.near((-5000 + 290) >> 4, 0, sites, 640) && !TerrainMath.near((-5000 + 330) >> 4, 0, sites, 640)
                && !TerrainMath.near(6, 6, sites, 0) && TerrainMath.near(6, 6, sites, 64),
                "ad combat (bug 10): the chunks a radar (its radius) and a launcher (its missile's reach) need remembered; radius 0 - off");
        AdTerrainDataSVA d = new AdTerrainDataSVA(AdTerrainDataSVA.NAME);
        byte[] a = new byte[TerrainMath.BYTES], b = new byte[TerrainMath.BYTES];
        a[5] = 70;
        b[255] = (byte) 200;
        b[TerrainMath.COLUMNS + 255] = (byte) 180;
        boolean p1 = d.put(0, -2, 3, a), p2 = d.put(0, 9, -9, b), p3 = d.put(-1, 0, 0, a), same = d.put(0, -2, 3, a.clone());
        boolean oldSize = d.put(0, 4, 4, new byte[TerrainMath.COLUMNS]);
        NBTTagCompound t = new NBTTagCompound();
        d.writeToNBT(t);
        AdTerrainDataSVA r = new AdTerrainDataSVA(AdTerrainDataSVA.NAME);
        r.readFromNBT(t);
        NBTTagCompound old = (NBTTagCompound) t.copy();
        old.removeTag("V");                                         // the first memory (one byte a column) - not read
        AdTerrainDataSVA r0 = new AdTerrainDataSVA(AdTerrainDataSVA.NAME);
        r0.readFromNBT(old);
        check(p1 && p2 && p3 && !same && !oldSize && r.size() == 3 && r.column(0, -2, 3)[5] == 70 && (r.column(0, 9, -9)[255] & 0xFF) == 200
                && TerrainMath.bottom(r.column(0, 9, -9), 15, 15) == 180 && r.column(-1, 0, 0) != null && r.column(1, 0, 0) == null
                && r.column(0, 0, 0) == null && r0.size() == 0 && TerrainMath.BYTES * TerrainMath.MAX_CHUNKS <= 16 * 1024 * 1024,
                "ad combat (bug 10): the terrain memory saved and read back per dimension (" + r.size() + " chunks; the same heights again - no change; "
                        + "the review's two bytes a column, an older memory dropped, 16 MB at most)");
        check(ConfigSVA.adTerrainCache && ConfigSVA.adTerrainRadius == 640, "ad combat (bug 10): the terrain memory on by default, 640 blocks round a radar");
    }

    // ---------------------------------------------------------------- bug 16

    private static boolean has(List<RadarMath.Paint> l, double x) {
        for (RadarMath.Paint p : l) {
            if (p.x == x) {
                return true;
            }
        }
        return false;
    }

    private static void paint() {
        List<RadarMath.Paint> l = new ArrayList<RadarMath.Paint>();
        for (int i = 0; i < RadarMath.PAINT_CAP; i++) {
            l.add(new RadarMath.Paint(RadarMath.T_SEARCH, i * 10, 0, 90));   // 16 emitters of a long flight, all long gone
        }
        RadarMath.paint(l, new RadarMath.Paint(RadarMath.T_LOCK, 500, 0, 130), 100);
        check(l.size() == 1 && has(l, 500), "ad combat (bug 16): the expired paint goes first - the 17th emitter's lock is on the RWR (" + l.size() + ")");
        l.clear();
        for (int i = 0; i < RadarMath.PAINT_CAP; i++) {
            l.add(new RadarMath.Paint(RadarMath.T_SEARCH, i * 10, 0, 200 + i));
        }
        RadarMath.paint(l, new RadarMath.Paint(RadarMath.T_LOCK, 500, 0, 130), 100);
        check(l.size() == RadarMath.PAINT_CAP && has(l, 500) && !has(l, 0) && has(l, 10),
                "ad combat (bug 16): a full list of live paint drops the one ending soonest for the newest");
        RadarMath.paint(l, new RadarMath.Paint(RadarMath.T_LOCK, 500.2, 0, 140), 100);
        int locks = 0;
        for (RadarMath.Paint p : l) {
            locks += p.kind == RadarMath.T_LOCK ? 1 : 0;
        }
        check(locks == 1 && l.size() == RadarMath.PAINT_CAP, "ad combat: the same emitter painting again replaces its own entry");
    }

    // ---------------------------------------------------------------- the low items

    private static void low() {
        check(RadarMath.weatherAt(true, true, false) == 1 && RadarMath.weatherAt(true, false, true) == RadarMath.RAIN_MUL
                && RadarMath.weatherAt(true, true, true) == RadarMath.STORM_MUL && RadarMath.weatherAt(false, true, true) == 1
                && RadarMath.weatherAt(false, false, true) == 1,
                "ad combat: the weather where the radar stands - a desert under the world's storm stays clear, a wet biome's rain / storm count");
        check(!RoeMath.restartWarnings(true, true, RoeMath.ST_FIRE) && RoeMath.restartWarnings(false, true, RoeMath.ST_FIRE)
                && RoeMath.restartWarnings(false, true, RoeMath.ST_LOCK) && !RoeMath.restartWarnings(false, false, RoeMath.ST_FIRE)
                && !RoeMath.restartWarnings(false, true, RoeMath.ST_NONE),
                "ad combat: warnings that went to an empty cockpit start over when somebody boards (no fire without a word)");
        double[] built = {10.5, 73.9, -4.5, 337.0};
        check(CoverageMath.sameJob(built, 10.5, 73.9, -4.5, 337.0) && !CoverageMath.sameJob(built, 10.5, 73.9, -4.5, 387.6)
                && !CoverageMath.sameJob(built, 11.5, 73.9, -4.5, 337.0) && !CoverageMath.sameJob(null, 0, 0, 0, 0),
                "ad combat: a kept coverage map fits only the antenna point and reach it was computed with");
    }
}
