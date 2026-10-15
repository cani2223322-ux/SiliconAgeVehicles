package com.sva.airdefense;

/**
 * Wave A (plan-next §9 A9, mock-up veh_ad_impl_a_1_kp_map): a radar's coverage map - pure, the self-test checks it on a
 * synthetic terrain. A GRID x GRID square (the radar in its middle, its side twice the radar's reach for the reference
 * target - a light plane - with the overdrives); for each cell a target AGL blocks above the ground there: is the beam from
 * the antenna to it clear (RadarMath.losClear - the hills' «мёртвые зоны» behind them come out SHADOW), out of reach (OUT)
 * or the ground not known (an unloaded chunk: UNKNOWN). Three heights to pick: 20 / 60 / 120 blocks.
 * <p>
 * The server computes it in slices (Job.step - a budget of cells a tick, airdefense.coverageBudget), keeps it per radar and
 * height, takes it as stale when the ground under it changed (the heights' signature) or the radar moved (another key),
 * and sends it packed (2 bits a cell, 4 KB) only to the player who asked.
 */
public final class CoverageMath {

    public static final int GRID = 128;
    public static final int[] ALTS = {20, 60, 120};
    public static final int UNKNOWN = 0, COVERED = 1, SHADOW = 2, OUT = 3;
    /** A packed grid's bytes (2 bits a cell). */
    public static final int BYTES = GRID * GRID / 4;

    private CoverageMath() {
    }

    /** The world under the map: the ground's height (the top solid block's y + 1; Integer.MIN_VALUE - unknown) and the beam. */
    public interface Terrain extends RadarMath.Probe {
        int ground(int x, int z);
    }

    /** The cell's side (blocks) for a reach. */
    public static double cell(double range) {
        return 2 * Math.max(1, range) / GRID;
    }

    /** The world centre (x, z) of cell (i, j) of a map round (ax, az). */
    public static double[] centre(int i, int j, double ax, double az, double range) {
        double c = cell(range);
        return new double[] {ax - range + (i + 0.5) * c, az - range + (j + 0.5) * c};
    }

    /** One cell: its state for an antenna at (ax, ay, az), the reach, the target `agl` over the ground there. */
    public static int cellState(double ax, double ay, double az, double tx, double tz, int ground, int agl, double range, RadarMath.Probe p) {
        double dx = tx - ax, dz = tz - az;
        if (dx * dx + dz * dz > range * range) {
            return OUT;
        }
        if (ground == Integer.MIN_VALUE) {
            return UNKNOWN;
        }
        double ty = Math.min(255, ground + agl);
        double dy = ty - ay;
        if (dx * dx + dy * dy + dz * dz > range * range) {
            return OUT;
        }
        return RadarMath.losClear(ax, ay, az, tx, ty, tz, p) ? COVERED : SHADOW;
    }

    /** Puts a cell's state into a packed grid. */
    public static void put(byte[] g, int i, int j, int st) {
        int k = j * GRID + i, b = k >> 2, sh = (k & 3) * 2;
        g[b] = (byte) (g[b] & ~(3 << sh) | (st & 3) << sh);
    }

    public static int get(byte[] g, int i, int j) {
        if (g == null || g.length < BYTES || i < 0 || j < 0 || i >= GRID || j >= GRID) {
            return UNKNOWN;
        }
        int k = j * GRID + i;
        return g[k >> 2] >> ((k & 3) * 2) & 3;
    }

    /** How many cells of a state (the world test, the screen's legend). */
    public static int count(byte[] g, int st) {
        int n = 0;
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                n += get(g, i, j) == st ? 1 : 0;
            }
        }
        return n;
    }

    /** The heights' signature of the map's cells - every cell's ground (a changed ground under it - recompute). */
    public static long signature(Terrain t, double ax, double az, double range) {
        long h = 0xcbf29ce484222325L;
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                double[] c = centre(i, j, ax, az, range);
                h ^= t.ground((int) Math.floor(c[0]), (int) Math.floor(c[1]));
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    /** A map being computed in slices. */
    public static final class Job {
        public final double ax, ay, az, range;
        public final int agl;
        public final byte[] grid = new byte[BYTES];
        private int next;

        public Job(double ax, double ay, double az, double range, int agl) {
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.range = range;
            this.agl = agl;
        }

        /** Up to `budget` beams (cells out of reach are free). @return done */
        public boolean step(Terrain t, int budget) {
            int beams = 0;
            while (next < GRID * GRID && beams < Math.max(1, budget)) {
                int i = next % GRID, j = next / GRID;
                next++;
                double[] c = centre(i, j, ax, az, range);
                double dx = c[0] - ax, dz = c[1] - az;
                if (dx * dx + dz * dz > range * range) {
                    put(grid, i, j, OUT);
                    continue;
                }
                int gy = t.ground((int) Math.floor(c[0]), (int) Math.floor(c[1]));
                put(grid, i, j, cellState(ax, ay, az, c[0], c[1], gy, agl, range, t));
                beams++;
            }
            return done();
        }

        public boolean done() {
            return next >= GRID * GRID;
        }

        /** 0..100 % done. */
        public int percent() {
            return next * 100 / (GRID * GRID);
        }
    }

    /** The next height on the screen's button: off (-1) - 20 - 60 - 120 - off. */
    public static int nextAlt(int agl) {
        if (agl < 0) {
            return ALTS[0];
        }
        for (int i = 0; i < ALTS.length - 1; i++) {
            if (ALTS[i] == agl) {
                return ALTS[i + 1];
            }
        }
        return -1;
    }

    /** A height a client may ask for (one of ALTS). */
    public static boolean validAlt(int agl) {
        for (int a : ALTS) {
            if (a == agl) {
                return true;
            }
        }
        return false;
    }
}
