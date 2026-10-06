package com.sva.vehicle;

/**
 * Pure numbers of the stage E vehicles (Transport Shuttle, Singular Ship) - the self-test checks them.
 * <ul>
 * <li>Cabin oxygen: a rider who needs air (under water) breathes the cabin's O2 at O2_PER_SECOND mB/s.</li>
 * <li>«Дальний режим» (long-range mode, key N): above LONG_Y and at LONG_SPEED of the top speed or more, deuterium
 * at LONG_D_PER_SECOND mB/s replaces the EU (the flight costs 0 EU/t; some charge must be left for the avionics).</li>
 * <li>The Singular Ship's «Прыжок» (own portal): a reference to the core bridge's cost formula (Ground: 200M EU + 20M
 * per 1000 blocks, SM 50 + 10 per 1000; Space: 2G EU, 500 SM) scaled for a ship-borne portal - one tenth of the
 * Ground bridge's EU, its SM per distance: JUMP_EU + JUMP_EU_PER_1000 per 1000 blocks, JUMP_SM + JUMP_SM_PER_1000;
 * another dimension (the Space bridge's job) - a flat OTHER_DIM_EU / OTHER_DIM_SM. At least MIN_JUMP blocks (the
 * bridge's own minimum), a cooldown of JUMP_COOLDOWN ticks, a PORTAL_TICKS opening during which the ship is held.</li>
 * <li>The destination must be free: a box JUMP_HALF blocks either side and JUMP_HEIGHT up from the arrival point.</li>
 * <li>«Гравитационный якорь»: holds the ship still at ANCHOR_EU EU/t (it hovers at 400).</li>
 * <li>Invisibility for mobs: INVIS_SM_PER_SECOND mB/s of singular matter.</li>
 * <li>Bridge docking: the vehicle pays the bridge's mass toll itself - DOCK_MASS tenths for the hull (a minecart's)
 * plus each rider's (BridgeMathSC.MASS_*); DOCK_COOLDOWN ticks before it can dock again (no ping-pong).</li>
 * </ul>
 */
public final class SpaceMath {

    /** Oxygen a rider who needs air breathes (mB a second). */
    public static final double O2_PER_SECOND = 1;
    /** Long-range mode: from this height, at this part of the top speed, deuterium mB a second. */
    public static final double LONG_Y = 120, LONG_SPEED = 0.5, LONG_D_PER_SECOND = 1;
    /** Jump: minimum distance (blocks), EU / SM base and per 1000 blocks, another dimension (flat). */
    public static final int MIN_JUMP = 16;
    public static final long JUMP_EU = 20000000L, JUMP_EU_PER_1000 = 2000000L, OTHER_DIM_EU = 100000000L;
    public static final int JUMP_SM = 10, JUMP_SM_PER_1000 = 10, OTHER_DIM_SM = 100;
    /** Jump cooldown (30 s) and the portal's opening (1.5 s), ticks. */
    public static final int JUMP_COOLDOWN = 600, PORTAL_TICKS = 30;
    /** The free box at the destination (the hull, 11 x 6 x 11 - the rings reach 7 blocks out): +-JUMP_HALF on x / z, JUMP_HEIGHT up. */
    public static final int JUMP_HALF = 5, JUMP_HEIGHT = 6;
    /** The gravity anchor, EU a tick. */
    public static final double ANCHOR_EU = 40;
    /** Invisibility, SM mB a second. */
    public static final double INVIS_SM_PER_SECOND = 1;
    /** Docking: the hull's mass (tenths of a unit, a minecart's) and the wait before the next docking (ticks). */
    public static final int DOCK_MASS = 40, DOCK_COOLDOWN = 60;
    /** The HUD's «Стыковка с мостом» hint: an open vortex within this many blocks. */
    public static final int DOCK_HINT_RADIUS = 6;

    /** Jump check results. */
    public static final int OK = 0, NO_TARGET = 1, NO_DIM = 2, TOO_NEAR = 3, COOLDOWN = 4, NO_EU = 5, NO_SM = 6, BLOCKED = 7, FIELD = 8,
            BUSY = 9, NOT_SHIP = 10, CHUNK = 11;

    private SpaceMath() {
    }

    /** Long-range mode works now: switched on, high enough, fast enough, deuterium left. */
    public static boolean longRangeActive(boolean on, double y, double speed, double topSpeed, int deuterium) {
        return on && y >= LONG_Y && Math.abs(speed) >= topSpeed * LONG_SPEED && deuterium > 0;
    }

    /** Seconds the cabin's oxygen lasts for `breathing` riders (infinite with nobody breathing it). */
    public static double o2Seconds(int mb, int breathing) {
        return breathing <= 0 ? Double.POSITIVE_INFINITY : Math.max(0, mb) / (O2_PER_SECOND * breathing);
    }

    /** Horizontal distance in whole blocks (the bridge measures its distance the same way). */
    public static long distance(int x0, int z0, int x1, int z1) {
        double dx = x1 - (double) x0, dz = z1 - (double) z0;
        return (long) Math.floor(Math.sqrt(dx * dx + dz * dz));
    }

    /** EU of a jump `dist` blocks (another dimension: flat). */
    public static long jumpEu(long dist, boolean otherDim) {
        if (otherDim) {
            return OTHER_DIM_EU;
        }
        return JUMP_EU + JUMP_EU_PER_1000 * Math.max(0, dist) / 1000;
    }

    /** Singular matter (mB) of a jump. */
    public static int jumpSm(long dist, boolean otherDim) {
        if (otherDim) {
            return OTHER_DIM_SM;
        }
        return (int) Math.min(Integer.MAX_VALUE, JUMP_SM + JUMP_SM_PER_1000 * Math.max(0, dist) / 1000);
    }

    /**
     * May the ship jump now (everything but the destination's space and claims)? @param hasTarget a target is set,
     * @param dimOk its dimension exists, @param otherDim it is another dimension, @param dist blocks (same dimension),
     * @param cooldown ticks left, @param eu / sm what the ship has. @return OK or why not
     */
    public static int jumpCheck(boolean hasTarget, boolean dimOk, boolean otherDim, long dist, int cooldown, double eu, int sm) {
        if (!hasTarget) {
            return NO_TARGET;
        }
        if (!dimOk) {
            return NO_DIM;
        }
        if (!otherDim && dist < MIN_JUMP) {
            return TOO_NEAR;
        }
        if (cooldown > 0) {
            return COOLDOWN;
        }
        if (eu < jumpEu(dist, otherDim)) {
            return NO_EU;
        }
        return sm < jumpSm(dist, otherDim) ? NO_SM : OK;
    }

    /** What blocks the box (world access kept out of the math). */
    public interface Solid {
        boolean blocked(int x, int y, int z);
    }

    /** Free space for the ship: nothing blocked in x +- half, z +- half, y .. y + height - 1. */
    public static boolean freeSpace(Solid s, int x, int y, int z, int half, int height) {
        for (int dy = 0; dy < height; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (s.blocked(x + dx, y + dy, z + dz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The jump's free box for the ship. */
    public static boolean freeSpace(Solid s, int x, int y, int z) {
        return freeSpace(s, x, y, z, JUMP_HALF, JUMP_HEIGHT);
    }

    /** Ticks left of a cooldown after one tick. */
    public static int cooldownStep(int left) {
        return Math.max(0, left - 1);
    }

    /** Mass (tenths) a docking pass carries: the hull plus its riders' masses. */
    public static int dockMass(int[] riderMasses) {
        int m = DOCK_MASS;
        for (int r : riderMasses) {
            m += Math.max(0, r);
        }
        return m;
    }

    /** Exit side of a ring end (the core's exitSideA): axis 0 - facing north (2) goes -Z, else +Z; axis 1 - west (4) -X, else +X. */
    public static int ringExitSide(int axis, int facing) {
        if (axis == 0) {
            return facing == 2 ? -1 : 1;
        }
        return facing == 4 ? -1 : 1;
    }

    /** Yaw facing out of a vortex plane of `axis` on side `d` (MC yaw: 0 = +Z, -90 = +X, 180 = -Z, 90 = -X). */
    public static float exitYaw(int axis, int d) {
        if (axis == 0) {
            return d > 0 ? 0F : 180F;
        }
        return d > 0 ? -90F : 90F;
    }
}
