package com.sva.vehicle;

/**
 * Pure helicopter formulas (Movement.HELI). Space = collective up, «Снижение» (or the sprint key) = down, neither =
 * hold the height; W/S fly forward / back, A/D yaw. Hovering costs more than flying forward (plan §2: 110 vs 90 EU/t).
 * No power (EU, overheat, wreck, nobody at the controls) in the air = AUTOROTATION: a slow spiral down at AUTO_SINK
 * and a landing that takes at most AUTO_LAND_CAP of the hull and nothing from the riders.
 */
public final class HeliMath {

    /** Collective: climb / descent rates (blocks/tick) and the vertical acceleration a tick. */
    public static final double CLIMB = 0.25, DESCEND = 0.3, VACC = 0.02;
    /** Ground taxi speed, horizontal drag a tick with W/S let go, reverse x top speed. */
    public static final double TAXI = 0.1, DRAG = 0.012, REVERSE = 0.4;
    /** Autorotation: sink start / top rate and its gain a tick, the forward speed it settles to, the spiral turn (deg/tick). */
    public static final double AUTO_START = 0.05, AUTO_SINK = 0.15, AUTO_ACC = 0.01, AUTO_FWD = 0.2, AUTO_TURN = 1.5;
    /** Powered touch-downs up to this sink rate are free; HP per b/t over it. */
    public static final double SAFE_LANDING = 0.35, HARD_DAMAGE = 150;
    /** Autorotation landing: this part of the hull, never more than AUTO_LAND_CAP. */
    public static final double AUTO_LAND_FRAC = 0.05, AUTO_LAND_CAP = 0.10;
    /** Climbing at the full rate adds this much EU/t; descending at the full rate saves this part. */
    public static final double CLIMB_EXTRA = 20, DESCENT_SAVING = 0.3;

    private HeliMath() {
    }

    /** Vertical speed the collective asks for (the climb fades under the ceiling; above it, it settles back down). */
    public static double targetVy(boolean up, boolean down, double y) {
        if (up && !down) {
            return y >= FlightMath.CEILING ? -0.05 : CLIMB * FlightMath.ceilingFactor(y);
        }
        if (down && !up) {
            return -DESCEND;
        }
        return y > FlightMath.CEILING ? -0.05 : 0;
    }

    /** Powered vertical speed this tick: toward the collective's target by VACC. */
    public static double poweredVy(double vy, boolean up, boolean down, double y) {
        return FlightMath.approach(vy, targetVy(up, down, y), VACC);
    }

    /** Autorotation vertical speed this tick: at once AUTO_START down, then AUTO_ACC more a tick, AUTO_SINK at most. */
    public static double autoVy(double vy) {
        double v = Math.min(vy, -AUTO_START) - AUTO_ACC;
        return Math.max(v, -AUTO_SINK);
    }

    /**
     * EU a tick before the config multiplier. On the ground with the collective down: the rotor idles (idleCost).
     * In the air: peakCost hovering .. cruiseCost at top speed, + CLIMB_EXTRA climbing, - DESCENT_SAVING descending,
     * x the cargo factor. Not working (no power, nobody at the controls): 0.
     */
    public static double cost(VehicleType t, boolean airborne, double speed, double vy, double fill, boolean working) {
        return cost(t.idleCost, t.peakCost, t.cruiseCost, t.topSpeed, airborne, speed, vy, fill, working);
    }

    /** The tiltrotor's vertical mode (and its transition): its vtolPeak hovering .. vtolCruise at vtolTop. */
    public static double vtolCost(VehicleType t, boolean airborne, double speed, double vy, double fill, boolean working) {
        return cost(t.idleCost, t.vtolPeak, t.vtolCruise, t.vtolTop, airborne, speed, vy, fill, working);
    }

    /** The same from the numbers: idle on the ground, `peak` hovering .. `cruise` at `top` speed. */
    public static double cost(double idle, double peak, double cruise, double top, boolean airborne, double speed, double vy, double fill,
                              boolean working) {
        if (!working) {
            return 0;
        }
        if (!airborne && vy <= 0.01) {
            return idle;
        }
        double s = VehicleMath.clamp(Math.abs(speed) / top, 0, 1);
        double c = peak - (peak - cruise) * s;
        if (vy > 0) {
            c += CLIMB_EXTRA * VehicleMath.clamp(vy / CLIMB, 0, 1);
        } else {
            c *= 1 - DESCENT_SAVING * VehicleMath.clamp(-vy / DESCEND, 0, 1);
        }
        return c * FlightMath.cargoMul(fill);
    }

    /** Hull HP a touch-down at `impact` (b/t down) takes; autorotation: AUTO_LAND_FRAC of the hull, capped at AUTO_LAND_CAP. */
    public static double landingDamage(float maxHp, double impact, boolean autorotation) {
        double over = Math.max(0, impact - (autorotation ? AUTO_SINK : SAFE_LANDING)) * HARD_DAMAGE;
        if (autorotation) {
            return Math.min(AUTO_LAND_CAP * maxHp, AUTO_LAND_FRAC * maxHp + over);
        }
        return over;
    }
}
