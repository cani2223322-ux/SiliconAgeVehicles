package com.sva.airdefense;

import java.util.List;

/**
 * Audit 2026-10-22 (bug 10): the terrain the air defence remembers for the chunks that are not loaded - pure, the self-test
 * checks it. A radar sees up to 600 blocks, a missile flies 280, but the server keeps loaded only the chunks round its
 * players: an unloaded ridge used to be transparent (a radar saw through it, a launcher fired over it, a missile flew
 * through it, the coverage map coloured the land behind it «seen»).
 * <p>
 * Now every chunk near an air-defence site (a radar or a launcher of AirDefenseDataSVA, within its reach and
 * airdefense.terrainCacheRadius) leaves its columns' heights behind (AdTerrainSVA: when it loads, when it unloads, when a
 * block in it changes) - two bytes a column: the ground as the coverage map sees it (the top beam-stopping block + 1, or
 * the surface of the water over it) and the bottom of that top solid layer. An unloaded column is opaque between the two;
 * a column never seen stays unknown (transparent for a beam, UNKNOWN on the coverage map - as before).
 * <p>
 * Audit review 2026-10-22: the column was opaque all the way down from its top (one byte): a line of single blocks or
 * slabs at y 250 across a few chunks 300-400 blocks from another's radar, remembered when they unloaded, was a solid wall
 * 250 high - the radar, the launchers' eyes and the missiles' seekers lost anything flying under it; overhangs, bridges, sky
 * bases and the Nether's roof (every Nether column solid up to 128) the same. Now only the top layer (down to its first
 * air gap) is solid: what is under an overhang is not known, so it is clear - as an unloaded chunk was before.
 */
public final class TerrainMath {

    /** Columns a chunk; a chunk's bytes (the grounds, then the layers' bottoms). */
    public static final int COLUMNS = 256, BYTES = 2 * COLUMNS;
    /** Chunks kept in all dimensions together (two bytes a column: 16 MB at most). */
    public static final int MAX_CHUNKS = 32768;
    /** A launcher's site needs the terrain this far (its missile's range and a little). */
    public static final int LAUNCHER_REACH = (int) BallisticsMath.MISSILE_RANGE + 16;

    private TerrainMath() {
    }

    /** A chunk's key (its chunk x, z). */
    public static long key(int cx, int cz) {
        return (long) cx << 32 | cz & 0xFFFFFFFFL;
    }

    public static int keyX(long k) {
        return (int) (k >> 32);
    }

    public static int keyZ(long k) {
        return (int) k;
    }

    /** A column's index in its chunk's 256 (any world x, z - the low 4 bits). */
    public static int index(int x, int z) {
        return (z & 15) << 4 | x & 15;
    }

    /** The column's ground (0..255; -1 nothing known). */
    public static int height(byte[] col, int x, int z) {
        return col == null || col.length < BYTES ? -1 : col[index(x, z)] & 0xFF;
    }

    /** The bottom of the column's top solid layer (0..255; -1 nothing known). */
    public static int bottom(byte[] col, int x, int z) {
        return col == null || col.length < BYTES ? -1 : col[COLUMNS + index(x, z)] & 0xFF;
    }

    /**
     * An unloaded column known: is the block at y inside its top solid layer as last seen (it stops a beam / a shell)? Under
     * the layer's bottom - an overhang's, a sky base's, a roof's air - it is clear (audit review 2026-10-22).
     */
    public static boolean opaque(byte[] col, int x, int y, int z) {
        return y >= 0 && y < height(col, x, z) && y >= bottom(col, x, z);
    }

    /** One column into a chunk's bytes (its ground and its top layer's bottom). */
    public static void put(byte[] col, int x, int z, int ground, int bottom) {
        col[index(x, z)] = pack(ground);
        col[COLUMNS + index(x, z)] = pack(Math.min(ground, bottom));
    }

    public static byte pack(int ground) {
        return (byte) Math.max(0, Math.min(255, ground));
    }

    /** One column as the capture reads it. */
    public interface Column {
        /** The block at y stops a beam (AirDefenseSVA.stops). */
        boolean stops(int y);

        /** The block at y is water. */
        boolean water(int y);
    }

    /**
     * A column's ground from `top` (the top block that blocks movement or is a liquid, + 1) down: the first beam stopper's
     * y + 1 - or the water's surface over it (a low flyer over the sea has the sea under him). The coverage map's rule.
     */
    public static int ground(int top, Column c) {
        int y = Math.max(0, Math.min(255, top));
        while (y > 0 && !c.stops(y - 1)) {
            if (c.water(y - 1) && y < 255) {
                break;
            }
            y--;
        }
        return y;
    }

    /**
     * Audit review 2026-10-22: the bottom of the column's top solid layer under its ground (ground's result): down through the
     * water to the first beam stopper, then down while they stop a beam - the y of the lowest one (the layer is [bottom,
     * ground); no stopper at all: 0 - the water down to the floor, as before). A slab on a pole high up is a layer of one block.
     */
    public static int bottom(int ground, Column c) {
        int y = Math.max(0, Math.min(255, ground));
        while (y > 0 && !c.stops(y - 1)) {
            y--;
        }
        while (y > 0 && c.stops(y - 1)) {
            y--;
        }
        return y;
    }

    /**
     * Is a chunk within reach of a site? `sites`: {x, z, reach} (reach capped by `radius`); measured to the chunk's nearest
     * column. radius 0: the cache is off.
     */
    public static boolean near(int cx, int cz, List<int[]> sites, int radius) {
        if (radius <= 0 || sites == null) {
            return false;
        }
        int x0 = cx << 4, z0 = cz << 4;
        for (int[] s : sites) {
            long r = s.length > 2 ? Math.min(radius, s[2]) : radius;
            long dx = s[0] < x0 ? x0 - s[0] : s[0] > x0 + 15 ? s[0] - x0 - 15 : 0;
            long dz = s[1] < z0 ? z0 - s[1] : s[1] > z0 + 15 ? s[1] - z0 - 15 : 0;
            if (dx * dx + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }
}
