package com.sva.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The drill vehicle's numbers and geometry (plan §2 «Буровая машина»). Pure - the self-test checks it; DrillLogicSVA
 * asks the world.
 * <ul>
 * <li>The face: blocks whose centre lies ahead of the hull (local forward in (near, far]) within `half` sideways,
 * rows 0..rows-1 up from the floor the vehicle stands on. 3x3 (half 1.7: three columns when it runs on the block
 * grid, a fourth when it is off it - the hull must always fit), 5x5 with the «Расширитель бура» module.</li>
 * <li>Modes: TUNNEL - everything in the face; ORE - only ore-dictionary ores ("ore*"), in the face and a ring one
 * block round it (walls, ceiling, floor), rock stays; LEVEL - everything above the floor ahead up to LEVEL_ROWS high
 * (flattens hills, never digs below the floor).</li>
 * <li>Speed: the core quarry head's blocks a second x DIG_MUL (the vehicle's motor). EU a block as the core quarry:
 * 30 + 15 x hardness, Exo head x1.5. Wear: 1 a block, the Exo head never wears.</li>
 * <li>Never: bedrock / unbreakable (hardness &lt; 0), harder than the head, tile entities, another player's private
 * field or claim (BreakEvent), spawn protection, unloaded chunks.</li>
 * <li>Torches every TORCH_EVERY blocks driven where the light is under DARK; liquids in the face and its ring become
 * cobblestone from the cargo.</li>
 * </ul>
 */
public final class DrillMath {

    public static final int MODE_TUNNEL = 0, MODE_ORE = 1, MODE_LEVEL = 2, MODES = 3;
    public static final int DIG_MUL = 8;
    public static final int ROWS = 3, ROWS_WIDE = 5, LEVEL_ROWS = 8;
    public static final double HALF = 1.7, HALF_WIDE = 2.7;
    /** Forward window of the face: block centres beyond NEAR, up to the bore cone's tip + 0.5 (3.3 + 0.5). */
    public static final double NEAR = 0.6, FAR = 3.8;
    public static final int TORCH_EVERY = 8, DARK = 8;
    public static final double EXO_COST = 1.5;
    /** What judge says about a block. */
    public static final int DIG = 0, SKIP_AIR = 1, LIQUID = 2, SKIP_HARD = 3, SKIP_TILE = 4, SKIP_CLAIM = 5, SKIP_FILTER = 6;

    private DrillMath() {
    }

    public static int nextMode(int mode) {
        return (Math.max(0, mode) + 1) % MODES;
    }

    /** Sideways reach of the zone: the bore (3 / 5 wide), + 1 for the ore mode's ring. */
    public static double half(int mode, boolean wide) {
        return (wide ? HALF_WIDE : HALF) + (mode == MODE_ORE ? 1 : 0);
    }

    /** Lowest row of the zone (the ore ring takes the floor row too). */
    public static int rowFrom(int mode) {
        return mode == MODE_ORE ? -1 : 0;
    }

    /** Rows above the floor (exclusive top). */
    public static int rowTo(int mode, boolean wide) {
        if (mode == MODE_LEVEL) {
            return LEVEL_ROWS;
        }
        return (wide ? ROWS_WIDE : ROWS) + (mode == MODE_ORE ? 1 : 0);
    }

    /**
     * Blocks of the zone ahead of a vehicle at (x, z), floor row y0, facing `yaw`: centres with local forward in
     * (near, far], |sideways| &lt;= half, rows y0 + rowFrom .. y0 + rowTo - 1. Nearest slice first, top row first
     * (falling sand / gravel drops into the cut, then it is taken as well).
     */
    public static List<int[]> zone(double x, int y0, double z, double yaw, double near, double far, double half, int rowFrom, int rowTo) {
        double r = Math.toRadians(yaw);
        final double fx = -Math.sin(r), fz = Math.cos(r);
        double reach = far + half + 1;
        List<int[]> out = new ArrayList<int[]>();
        final List<Double> fwd = new ArrayList<Double>();
        for (int bx = (int) Math.floor(x - reach); bx <= (int) Math.floor(x + reach); bx++) {
            for (int bz = (int) Math.floor(z - reach); bz <= (int) Math.floor(z + reach); bz++) {
                double dx = bx + 0.5 - x, dz = bz + 0.5 - z;
                double f = dx * fx + dz * fz;
                double s = dx * fz - dz * fx;
                if (f > near + 1e-9 && f <= far + 1e-9 && Math.abs(s) <= half + 1e-9) {
                    for (int row = rowTo - 1; row >= rowFrom; row--) {
                        out.add(new int[] {bx, y0 + row, bz, (int) Math.round(f * 1000)});
                    }
                }
            }
        }
        Collections.sort(out, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return a[3] != b[3] ? (a[3] < b[3] ? -1 : 1) : (a[1] > b[1] ? -1 : a[1] < b[1] ? 1 : 0);
            }
        });
        return out;
    }

    /** The zone for a mode (the drill's own window). */
    public static List<int[]> zone(double x, int y0, double z, double yaw, int mode, boolean wide) {
        return zone(x, y0, z, yaw, NEAR, FAR, half(mode, wide), rowFrom(mode), rowTo(mode, wide));
    }

    /** The ring round the bore where liquids get walled up: one block wider, higher, deeper and the floor row. */
    public static List<int[]> ring(double x, int y0, double z, double yaw, boolean wide) {
        return zone(x, y0, z, yaw, NEAR, FAR + 1, (wide ? HALF_WIDE : HALF) + 1, -1, (wide ? ROWS_WIDE : ROWS) + 1);
    }

    /**
     * What to do with one block. headMax: the head's hardest block; tile: has a tile entity; allowed: claims, private
     * fields, spawn protection and the chunk all let it be broken; ore: an ore-dictionary ore.
     */
    public static int judge(boolean air, boolean liquid, float hardness, float headMax, boolean tile, boolean allowed, int mode, boolean ore) {
        if (air) {
            return SKIP_AIR;
        }
        if (liquid) {
            return LIQUID;
        }
        if (hardness < 0 || hardness > headMax) {
            return SKIP_HARD;
        }
        if (tile) {
            return SKIP_TILE;
        }
        if (!allowed) {
            return SKIP_CLAIM;
        }
        return mode == MODE_ORE && !ore ? SKIP_FILTER : DIG;
    }

    /** The world's verdict on breaking / walling up a block: all must hold. */
    public static boolean allowed(boolean loaded, boolean privateFieldRefuses, boolean breakEventCanceled, boolean spawnProtected) {
        return loaded && !privateFieldRefuses && !breakEventCanceled && !spawnProtected;
    }

    /** EU one block costs (the core quarry: 30 + 15 x hardness; the Exo head x1.5), x the config multiplier. */
    public static int euPerBlock(float hardness, boolean exo, double mul) {
        double eu = (30 + 15 * Math.max(0, hardness)) * (exo ? EXO_COST : 1) * Math.max(0, mul);
        return (int) Math.ceil(eu);
    }

    /** Blocks a tick a head of `blocksPerSecond` digs on the vehicle. */
    public static double perTick(int blocksPerSecond) {
        return Math.max(0, blocksPerSecond) * DIG_MUL / 20.0;
    }

    /** The head's damage after one block (life 0 = the Exo head: never wears). */
    public static int wear(int damage, int life) {
        return life <= 0 ? damage : damage + 1;
    }

    public static boolean wornOut(int damage, int life) {
        return life > 0 && damage >= life;
    }

    /** Blocks the head still lasts (-1: for ever). */
    public static int lifeLeft(int damage, int life) {
        return life <= 0 ? -1 : Math.max(0, life - damage);
    }

    /** Hull damage through the argon field. */
    public static float fieldDamage(float amount, boolean active, double reduction) {
        return active ? (float) (amount * (1 - Math.max(0, Math.min(1, reduction)))) : amount;
    }

    /** A torch is due: driven TORCH_EVERY blocks since the last and the light there is under DARK. */
    public static boolean torchDue(double since, int light) {
        return since >= TORCH_EVERY && light < DARK;
    }
}
