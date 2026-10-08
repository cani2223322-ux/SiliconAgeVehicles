package com.sva.vehicle;

/**
 * Pure formulas shared by the aircraft (Movement.HELI / PLANE): the ceiling, cargo and cruise factors, heat load and
 * the server's check of a driver's flying move. Speeds in blocks/tick, heights in world Y.
 */
public final class FlightMath {

    /** Ceiling (plan: "потолок ~Y 200"): the climb rate fades over the last CEILING_FADE blocks and is 0 at the top. */
    public static final double CEILING = 200, CEILING_FADE = 10;
    /** A gap this big under the collision box = in the air. */
    public static final double AIRBORNE = 0.3;
    /** Plane cruise height: at or above it -20 % EU (plan §2 «Крейсерская высота»). */
    public static final double CRUISE_Y = 100, CRUISE_MUL = 0.8;
    /** Full cargo costs this much more (x1.25). */
    public static final double CARGO_COST = 0.25;
    /** Heat load at the type's peak cost (1.2: hovering / full-throttle climbing overheats in the end without helium). */
    public static final double LOAD_K = 1.2;
    /** Unpowered in the air: a move must come down at least this fast (blocks/tick). */
    public static final double MUST_SINK = 0.015;
    /** Horizontal reach of a move: the type's top speed x this (a dive is a bit faster than level flight). */
    public static final double SPEED_MARGIN = 1.25;
    /**
     * Stage D: no aircraft ever goes faster than this (blocks/tick; 50 blocks/s, 180 km/h on the HUD) - the jet on
     * its afterburner (1.68 x 1.5 = 2.52) and in a dive is capped here. Why: the server loads (and, in fresh terrain,
     * generates) the chunks ahead of a player synchronously; at 2.5 b/t a view distance of 10 needs ~66 new chunks a
     * second to be sent (vanilla sends up to 5 a tick = 100/s) - faster than that the world ahead stays empty.
     */
    public static final double SPEED_CAP = 2.5;

    private FlightMath() {
    }

    /** 1 below CEILING - CEILING_FADE, 0 at the ceiling and above: how much of the climb rate is left. */
    public static double ceilingFactor(double y) {
        return VehicleMath.clamp((CEILING - y) / CEILING_FADE, 0, 1);
    }

    /** Cost factor of the cargo: 1 empty .. 1 + CARGO_COST full. */
    public static double cargoMul(double fill) {
        return 1 + CARGO_COST * VehicleMath.clamp(fill, 0, 1);
    }

    /** Engine heat load from the EU a tick (without the config multiplier): LOAD_K at the type's peak cost. */
    public static double load(VehicleType t, double baseCost) {
        return t.peakCost <= 0 ? 0 : LOAD_K * Math.max(0, baseCost) / t.peakCost;
    }

    /** EU/t at cruise (the HUD range and the item tooltip): heli forward at top speed, plane level at full throttle. */
    public static double cruiseCost(VehicleType t, double fill, double y) {
        return cruiseCost(t, fill, y, t.movement != Movement.HELI);
    }

    /** The same; the tiltrotor: planeMode = its airplane mode (else its vertical mode's forward-flight cost). */
    public static double cruiseCost(VehicleType t, double fill, double y, boolean planeMode) {
        boolean plane = t.movement == Movement.PLANE || t.movement == Movement.TILT && planeMode;
        double base = t.movement == Movement.TILT && !planeMode ? t.vtolCruise : t.cruiseCost;
        double c = base * cargoMul(fill);
        return plane && y >= CRUISE_Y ? c * CRUISE_MUL : c;
    }

    /** The rules a tiltrotor's move is judged by: its vertical mode and the transition fly like a helicopter. */
    public static Movement rules(VehicleType t) {
        return t.movement == Movement.TILT ? Movement.HELI : t.movement;
    }

    /** Highest climb rate (blocks/tick) a move may show: no power or (plane) no airspeed - none. */
    public static double maxClimb(VehicleType t, boolean powered, double speed, double y) {
        return maxClimb(t, rules(t), powered, speed, y);
    }

    /** The same under `m` (HELI / PLANE rules; the tiltrotor by its mode). */
    public static double maxClimb(VehicleType t, Movement m, boolean powered, double speed, double y) {
        if (!powered) {
            return 0;
        }
        double c;
        if (m == Movement.HELI) {
            c = t.movement == Movement.HELI ? t.climb : HeliMath.CLIMB;     // the tiltrotor's vertical mode: the heli's
        } else if (m == Movement.PLANE) {
            c = speed >= t.plane.stall * 0.8 ? Math.max(t.plane.climb, t.plane.rotateVy) : 0;
        } else {
            c = 0;
        }
        return c * ceilingFactor(y);
    }

    /** In the air it has to be coming down: no power, or a plane far below its stall speed. */
    public static boolean mustDescend(VehicleType t, boolean powered, double speed) {
        return mustDescend(t, rules(t), powered, speed);
    }

    public static boolean mustDescend(VehicleType t, Movement m, boolean powered, double speed) {
        return !powered || m == Movement.PLANE && speed < t.plane.stall * 0.6;
    }

    /** Horizontal reach a tick the server allows: top speed (x the afterburner) x SPEED_MARGIN, at most SPEED_CAP. */
    public static double maxReach(VehicleType t) {
        return Math.min(SPEED_CAP, t.topSpeed * t.gases.boostMul * SPEED_MARGIN);
    }

    /**
     * The server's check of a flying driver's move over dt ticks: horizontal reach (top speed x SPEED_MARGIN, no
     * teleports), climbing only with power and (plane) airspeed, under the ceiling, a 1-block step allowed from the
     * ground, and in the air without lift it must be coming down.
     *
     * @param wasAirborne in the air at the old position
     * @param airborneNew in the air at the new position
     * @param speed       the server's measured horizontal speed
     */
    public static boolean moveAllowed(VehicleType t, double horizontal, double dy, long dt, double yNew, boolean powered,
                                      boolean wasAirborne, boolean airborneNew, double speed) {
        return moveAllowed(t, rules(t), horizontal, dy, dt, yNew, powered, wasAirborne, airborneNew, speed);
    }

    /** The same under `m` (the tiltrotor: PLANE rules in its airplane mode, HELI otherwise). */
    public static boolean moveAllowed(VehicleType t, Movement m, double horizontal, double dy, long dt, double yNew, boolean powered,
                                      boolean wasAirborne, boolean airborneNew, double speed) {
        // unpowered it only glides: the reach follows the airspeed (at least the type's glide speed - the speed
        // measure lags), not the top speed - no free flight at full speed losing MUST_SINK a tick
        double base = powered ? maxReach(t) : Math.min(maxReach(t), Math.max(speed, glideSpeed(t, m)) * 1.1);
        double reach = base * 1.3 * dt + 0.2;
        if (Double.isNaN(horizontal) || Double.isNaN(dy) || horizontal >= Math.max(10, reach) || horizontal > reach) {
            return false;
        }
        if (dy < -(1.5 * dt + 1)) {
            return false;
        }
        double up = maxClimb(t, m, powered, speed, yNew) * dt + 0.1;
        if (!wasAirborne) {
            up = Math.max(up, 1.1 * dt + 0.3);              // a block step from the ground, as on wheels
        }
        if (dy > up) {
            return false;
        }
        if (dy > 0.05 && yNew > CEILING + 0.5) {
            return false;
        }
        return !(airborneNew && mustDescend(t, m, powered, speed) && dy > -MUST_SINK * dt);
    }

    /** Steady airspeed without power under `m`: a plane's glide, a helicopter's autorotation (HeliMath.AUTO_FWD). */
    static double glideSpeed(VehicleType t, Movement m) {
        return m == Movement.PLANE && t.plane != null ? t.plane.glideSpeed : HeliMath.AUTO_FWD;
    }

    /** Moves `v` toward `target` by at most `step`. */
    public static double approach(double v, double target, double step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }
}
