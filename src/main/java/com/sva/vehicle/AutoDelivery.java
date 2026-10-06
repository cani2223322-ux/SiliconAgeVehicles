package com.sva.vehicle;

import com.sva.entity.EntityVehicleSVA;

/**
 * The cargo crawler's auto-delivery between two Coordinator points (plan §2 ★, §2 «Автопилот»): a pure state
 * machine plus simple steering - the self-test checks it, AutoPilotSVA runs it in the world.
 * <ul>
 * <li>TO_A - drive to A; LOADING at A (a chest within SEARCH blocks: chest -> cargo); TO_B; UNLOADING at B
 * (cargo -> chest); back TO_A. Nothing to load and an empty cargo: it waits at A.</li>
 * <li>Steering: straight at the point - turns on the spot when it is more than TURN_ON_SPOT degrees off, drives and
 * steers below that; steps (up to 2 blocks) are the tracks' business. No path finding.</li>
 * <li>STOPPED: the way blocked for BLOCKED_TICKS (10 s), the chunk ahead not loaded, or the route lost - it stays
 * until the owner switches it off and on again.</li>
 * </ul>
 */
public final class AutoDelivery {

    public static final int OFF = 0, TO_A = 1, LOADING = 2, TO_B = 3, UNLOADING = 4, STOPPED = 5, STATES = 6;
    /** Why it stopped (synced for the HUD / the cargo screen). */
    public static final int R_NONE = 0, R_BLOCKED = 1, R_UNLOADED = 2, R_ROUTE = 3, R_DIMENSION = 4;
    /** Arrived within this (blocks, horizontal); chests searched within SEARCH of the hull. */
    public static final double ARRIVE = 2.5;
    public static final int SEARCH = 4;
    /** Stuck this long (ticks) -> STOPPED; a transfer step every TRANSFER_EVERY ticks, at most STACKS_PER_STEP stacks. */
    public static final int BLOCKED_TICKS = 200, TRANSFER_EVERY = 10, STACKS_PER_STEP = 4;
    /** Degrees off the bearing: above this it turns on the spot; below STRAIGHT it drives straight. */
    public static final double TURN_ON_SPOT = 35, STRAIGHT = 4;

    private AutoDelivery() {
    }

    /** Switched on: a route with both points in this dimension - off to A; else STOPPED (see startReason). */
    public static int start(int[] a, int[] b, int dim) {
        return startReason(a, b, dim) == R_NONE ? TO_A : STOPPED;
    }

    public static int startReason(int[] a, int[] b, int dim) {
        if (a == null || b == null) {
            return R_ROUTE;
        }
        return a[3] != dim || b[3] != dim ? R_DIMENSION : R_NONE;
    }

    /** Driving somewhere (wants to move). */
    public static boolean driving(int state) {
        return state == TO_A || state == TO_B;
    }

    /** Point the state heads for / works at: 0 A, 1 B, -1 none. */
    public static int target(int state) {
        return state == TO_A || state == LOADING ? 0 : state == TO_B || state == UNLOADING ? 1 : -1;
    }

    public static boolean arrived(double dx, double dz) {
        return dx * dx + dz * dz <= ARRIVE * ARRIVE;
    }

    /** Reached the point it drove to. */
    public static int onArrive(int state) {
        return state == TO_A ? LOADING : state == TO_B ? UNLOADING : state;
    }

    /**
     * After a transfer step at an end. moved: anything went; the cargo empty / full after it. LOADING: full - off
     * to B; moved - keep loading; nothing moved: an empty cargo waits for goods, a part-filled one goes. UNLOADING:
     * empty - back to A; moved - keep unloading; nothing moved (no chest / chest full) - back to A with the rest.
     */
    public static int afterTransfer(int state, boolean moved, boolean cargoEmpty, boolean cargoFull) {
        if (state == LOADING) {
            if (cargoFull) {
                return TO_B;
            }
            if (moved) {
                return LOADING;
            }
            return cargoEmpty ? LOADING : TO_B;
        }
        if (state == UNLOADING) {
            if (cargoEmpty) {
                return TO_A;
            }
            return moved ? UNLOADING : TO_A;
        }
        return state;
    }

    /** Ticks it has been stuck: wanting to move and moving less than 0.01 a tick counts up, anything else resets. */
    public static int blockedTicks(int ticks, boolean wantsMove, double moved) {
        return wantsMove && moved < 0.01 ? ticks + 1 : 0;
    }

    public static boolean stuck(int ticks) {
        return ticks >= BLOCKED_TICKS;
    }

    /** Minecraft yaw facing from (px, pz) toward (tx, tz). */
    public static double bearing(double px, double pz, double tx, double tz) {
        return Math.toDegrees(Math.atan2(-(tx - px), tz - pz));
    }

    public static double wrap(double deg) {
        double d = deg % 360;
        if (d >= 180) {
            d -= 360;
        }
        if (d < -180) {
            d += 360;
        }
        return d;
    }

    /**
     * Keys (EntityVehicleSVA.IN_*) to drive from (px, pz) facing `yaw` to (tx, tz): far off the bearing - turn on the
     * spot; a bit off - forward and steer; on it - forward; arrived - brake.
     */
    public static int steer(double yaw, double px, double pz, double tx, double tz) {
        if (arrived(tx - px, tz - pz)) {
            return EntityVehicleSVA.IN_BRAKE;
        }
        double diff = wrap(bearing(px, pz, tx, tz) - yaw);
        int turn = diff > STRAIGHT ? EntityVehicleSVA.IN_RIGHT : diff < -STRAIGHT ? EntityVehicleSVA.IN_LEFT : 0;
        if (Math.abs(diff) > TURN_ON_SPOT) {
            return turn;
        }
        return EntityVehicleSVA.IN_FWD | turn;
    }

    /** Blocks (horizontal) from (px, pz) to (tx, tz), rounded for the HUD. */
    public static int distance(double px, double pz, double tx, double tz) {
        return (int) Math.round(Math.sqrt((tx - px) * (tx - px) + (tz - pz) * (tz - pz)));
    }
}
