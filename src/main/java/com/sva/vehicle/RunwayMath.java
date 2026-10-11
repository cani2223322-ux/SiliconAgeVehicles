package com.sva.vehicle;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Aircraft stage C (★17 ЗАХВ, ★19 the HSI's runway): the nearest strip of the addon's «Взлётно-посадочная полоса» blocks
 * (block.BlockRunwaySVA) round an aircraft - pure, over a small World view (the self-test runs it on a fake grid; the
 * entity on the real world, both sides - the driver's client steers by it, the server judges by the same rule).
 * <p>
 * The scan looks at the top block of the columns within SCAN blocks (a checkerboard: every other column - a strip one
 * block wide is still hit along its length - only loaded chunks, the height map: no chunk is loaded or generated), floods
 * each runway block it meets over its 4 neighbours at the same height (at most MAX_BLOCKS) and keeps the strips at least
 * MIN_LEN long: the nearest one wins. A strip is its bounding box: the axis along its longer side, the centre line in the
 * middle of the other, the surface on top of the blocks. The entity asks again every EntityVehicleSVA.RUNWAY_EVERY ticks.
 */
public final class RunwayMath {

    /** Columns scanned this far round the aircraft (blocks, a square). */
    public static final int SCAN = 96;
    /** Shortest strip that is a runway (blocks along). */
    public static final int MIN_LEN = 12;
    /** A flood stops after this many blocks (a huge paved field is still a runway). */
    public static final int MAX_BLOCKS = 8192;

    private RunwayMath() {
    }

    /** What the scan reads: the top block's height of a column (-1: unknown / unloaded) and whether a block is a runway. */
    public interface World {
        int top(int x, int z);

        boolean runway(int x, int y, int z);
    }

    /** A runway strip (world blocks). */
    public static final class Runway {
        /** Its axis runs along X (else along Z). */
        public final boolean alongX;
        /** The centre line's cross coordinate (z for alongX, else x), the strip's extent along the axis [a0, a1], its surface Y. */
        public final double c, a0, a1, y;
        /** Its blocks, its width (blocks across). */
        public final int blocks, width;

        public Runway(boolean alongX, double c, double a0, double a1, double y, int blocks, int width) {
            this.alongX = alongX;
            this.c = c;
            this.a0 = a0;
            this.a1 = a1;
            this.y = y;
            this.blocks = blocks;
            this.width = width;
        }

        /** Length along the axis (blocks). */
        public double length() {
            return a1 - a0;
        }

        /** The threshold (start of the strip) flown in direction dir: {x, z}. */
        public double[] threshold(int dir) {
            double a = dir > 0 ? a0 : a1;
            return alongX ? new double[] {a, c} : new double[] {c, a};
        }

        /** Horizontal distance from (x, z) to the strip's box (0 over it). */
        public double distance(double x, double z) {
            double a = alongX ? x : z, k = alongX ? z : x;
            double half = width / 2.0;
            double da = a < a0 ? a0 - a : a > a1 ? a - a1 : 0, dk = Math.max(0, Math.abs(k - c) - half);
            return Math.sqrt(da * da + dk * dk);
        }
    }

    /** The nearest runway strip round (px, pz) within SCAN blocks, or null. */
    public static Runway find(World w, double px, double pz) {
        return find(w, px, pz, SCAN);
    }

    public static Runway find(World w, double px, double pz, int scan) {
        int cx = (int) Math.floor(px), cz = (int) Math.floor(pz);
        Set<Long> seen = new HashSet<Long>();
        Runway best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                if (((dx + dz) & 1) != 0) {
                    continue;                                  // a checkerboard: half the columns, no strip missed
                }
                int x = cx + dx, z = cz + dz;
                int y = w.top(x, z);
                if (y < 0 || seen.contains(Long.valueOf(key(x, y, z))) || !w.runway(x, y, z)) {
                    continue;
                }
                Runway r = flood(w, x, y, z, seen);
                if (r != null) {
                    double d = r.distance(px, pz);
                    if (d < bestD) {
                        bestD = d;
                        best = r;
                    }
                }
            }
        }
        return best;
    }

    /** The strip through (x, y, z): its blocks at that height, 4-connected; null when shorter than MIN_LEN. */
    static Runway flood(World w, int x0, int y, int z0, Set<Long> seen) {
        ArrayDeque<int[]> q = new ArrayDeque<int[]>();
        q.add(new int[] {x0, z0});
        seen.add(Long.valueOf(key(x0, y, z0)));
        int minX = x0, maxX = x0, minZ = z0, maxZ = z0, n = 0;
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && n < MAX_BLOCKS) {
            int[] p = q.poll();
            n++;
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[1]);
            maxZ = Math.max(maxZ, p[1]);
            for (int[] d : nb) {
                int x = p[0] + d[0], z = p[1] + d[1];
                Long k = Long.valueOf(key(x, y, z));
                if (!seen.contains(k) && w.runway(x, y, z)) {
                    seen.add(k);
                    q.add(new int[] {x, z});
                }
            }
        }
        int lx = maxX - minX + 1, lz = maxZ - minZ + 1;
        boolean alongX = lx >= lz;
        if (Math.max(lx, lz) < MIN_LEN) {
            return null;
        }
        return alongX ? new Runway(true, (minZ + maxZ + 1) / 2.0, minX, maxX + 1, y + 1, n, lz)
                : new Runway(false, (minX + maxX + 1) / 2.0, minZ, maxZ + 1, y + 1, n, lx);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
}
