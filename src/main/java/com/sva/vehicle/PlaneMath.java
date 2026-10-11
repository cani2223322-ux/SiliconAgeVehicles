package com.sva.vehicle;

/**
 * Pure aeroplane formulas (Movement.PLANE). W/S = throttle up / down (at zero throttle on the ground S brakes),
 * A/D = turn (with a visual bank), Space = pull up (lift-off at LIFT_OFF on the ground), «Снижение» / the sprint key =
 * push down, neither = hold the height. Lift comes from airspeed: below STALL the nose drops (it sinks and gains
 * speed); it can't hover. Take-off run ~30 blocks, on Runway blocks half (the ground acceleration is x GROUND_EFF
 * off a runway). No power in the air = a 1:8 glide at GLIDE_SPEED; an unpowered touch-down is a belly landing.
 * The constants are the Light Plane's; the jet and the tiltrotor's airplane mode take theirs from VehicleType.plane
 * (PlaneParams; PlaneParams.LIGHT = these, so the light plane flies exactly as in stage B).
 */
public final class PlaneMath {

    /** Stall and lift-off speeds (blocks/tick; 36 / 40 km/h). */
    public static final double STALL = 0.5, LIFT_OFF = 0.55;
    /** Thrust at full throttle (b/t² against the drag that balances it at top speed) and the throttle change a tick. */
    public static final double THRUST = 0.0115, THR_STEP = 0.05;
    /** Off a runway the ground acceleration is x this - the take-off run doubles (runway: x1). */
    public static final double GROUND_EFF = 0.5;
    /** Rolling friction at zero throttle, wheel brakes (off a runway / on one) a tick. */
    public static final double ROLL = 0.003, BRAKE = 0.03, BRAKE_RUNWAY = 0.02;
    /** Braking off a runway faster than this scuffs the hull (HP a tick) - a runway brakes softly. */
    public static final double BRAKE_WEAR_SPEED = 0.25;
    public static final float BRAKE_WEAR = 0.25F;
    /** Climb (at full airspeed), dive and stall sink rates, vertical acceleration, the rate at lift-off. */
    public static final double CLIMB = 0.2, DIVE = 0.3, STALL_SINK = 0.35, VACC = 0.02, ROTATE_VY = 0.1;
    /** Climbing slows it (b/t² per b/t of climb), a dive speeds it up; a stalled nose-down gains this a tick. */
    public static final double CLIMB_DRAG = 0.05, STALL_GAIN = 0.01;
    /** Glide: 1 : GLIDE_RATIO at GLIDE_SPEED (settles at GLIDE_ACC a tick), the sink starts at once at GLIDE_START. */
    public static final double GLIDE_RATIO = 8, GLIDE_SPEED = 0.65, GLIDE_ACC = 0.004, GLIDE_START = 0.04;
    /** Touch-downs up to this sink rate are free; HP per b/t over it; belly landing: part of the hull + HP per b/t over the glide speed. */
    public static final double SAFE_LANDING = 0.3, HARD_DAMAGE = 120, BELLY_FRAC = 0.05, BELLY_SPEED = 40, LANDING_CAP = 0.6;
    /** Fastest it gets (a dive): top speed x this. */
    public static final double MAX_SPEED_MUL = 1.25;

    private PlaneMath() {
    }

    /** Throttle 0..1 after one tick of W / S; without power it winds down. */
    public static double throttleStep(double thr, boolean up, boolean down, boolean powered) {
        if (!powered) {
            return Math.max(0, thr - THR_STEP);
        }
        if (up && !down) {
            return Math.min(1, thr + THR_STEP);
        }
        if (down && !up) {
            return Math.max(0, thr - THR_STEP);
        }
        return VehicleMath.clamp(thr, 0, 1);
    }

    /** Thrust minus drag along the heading (b/t²); on the ground x GROUND_EFF off a runway, rolling friction at zero throttle. */
    public static double accel(VehicleType t, double speed, double thr, boolean air, boolean runway) {
        return accel(t, speed, thr, air, runway, 1);
    }

    /**
     * The same with an afterburner factor `mul` (the jet: x1.5 - thrust and top speed alike; 1 = none). The thrust and
     * the top speed are the type's (VehicleType.plane).
     */
    public static double accel(VehicleType t, double speed, double thr, boolean air, boolean runway, double mul) {
        return accel(t, t.plane, speed, thr, air, runway, mul);
    }

    /**
     * Aircraft stage A: with the PlaneParams the high-lift devices give (HighLiftMath.params): the drag on the top-speed
     * term (flaps, airbrake, ground spoilers), the rolling friction x p.brake (ground spoilers). The type's own params =
     * the formula above exactly (x1.0).
     */
    public static double accel(VehicleType t, PlaneParams p, double speed, double thr, boolean air, boolean runway, double mul) {
        double top = t.topSpeed * mul;
        double r = speed / top;
        double a = p.thrust * mul * (VehicleMath.clamp(thr, 0, 1) - p.drag * r * r);
        if (!air) {
            a *= runway ? 1 : GROUND_EFF;
            if (thr <= 0 && speed > 0) {
                a -= ROLL * p.brake;
            }
        }
        return a;
    }

    /** Wheel brakes a tick (on the ground at zero throttle with S held). */
    public static double brake(boolean runway) {
        return runway ? BRAKE_RUNWAY : BRAKE;
    }

    /** Stage A: x p.brake - the ground spoilers dump the lift and the brakes grip (the roll-out x HighLiftMath.ROLLOUT). */
    public static double brake(PlaneParams p, boolean runway) {
        return brake(runway) * p.brake;
    }

    /** How much climb the airspeed allows (0 at the stall speed, the full CLIMB from 0.25 b/t over it) - the light plane. */
    public static double climbCapacity(double speed) {
        return climbCapacity(PlaneParams.LIGHT, speed);
    }

    /** Per type: 0 at its stall speed, its full climb from climbBand over it. */
    public static double climbCapacity(PlaneParams p, double speed) {
        return p.climb * VehicleMath.clamp((speed - p.stall) / p.climbBand, 0, 1);
    }

    /** Is it stalling (in the air below the stall speed)? The light plane. */
    public static boolean stalled(double speed) {
        return speed < STALL;
    }

    public static boolean stalled(PlaneParams p, double speed) {
        return speed < p.stall;
    }

    /**
     * Vertical speed in the air this tick (the light plane). Powered above the stall: Space climbs (by the airspeed,
     * fading under the ceiling), «down» dives, neither holds the height. Below the stall the nose drops (STALL_SINK).
     * Unpowered: a 1:GLIDE_RATIO glide (Space flares: half the sink), below the stall it drops too.
     */
    public static double airVy(double speed, double vy, boolean up, boolean down, boolean powered, double y) {
        return airVy(PlaneParams.LIGHT, speed, vy, up, down, powered, y);
    }

    /** The same with the type's numbers. */
    public static double airVy(PlaneParams p, double speed, double vy, boolean up, boolean down, boolean powered, double y) {
        if (stalled(p, speed)) {
            return FlightMath.approach(Math.min(vy, -GLIDE_START), -p.stallSink, VACC);
        }
        if (!powered) {
            double target = -speed / GLIDE_RATIO;
            if (up) {
                target *= 0.5;
            }
            if (p.airbrake) {
                target *= 2;                                    // stage A: the airbrake steepens the glide
            }
            return FlightMath.approach(Math.min(vy, -GLIDE_START), target, VACC);
        }
        double target;
        if (p.airbrake) {
            // stage A, «Воздушный тормоз»: a steep descent - no keys sinks, «down» dives harder, Space climbs at half
            if (up && !down) {
                target = y >= FlightMath.CEILING ? -0.05 : climbCapacity(p, speed) * FlightMath.ceilingFactor(y) * HighLiftMath.AIRBRAKE_CLIMB;
            } else if (down && !up) {
                target = -p.dive * HighLiftMath.AIRBRAKE_DIVE;
            } else {
                target = -p.dive * HighLiftMath.AIRBRAKE_SINK;
            }
            return FlightMath.approach(vy, target, VACC);
        }
        if (up && !down) {
            target = y >= FlightMath.CEILING ? -0.05 : climbCapacity(p, speed) * FlightMath.ceilingFactor(y);
        } else if (down && !up) {
            target = -p.dive;
        } else {
            target = y > FlightMath.CEILING ? -0.05 : 0;
        }
        return FlightMath.approach(vy, target, VACC);
    }

    /** Airspeed change in the air: powered - thrust/drag and the climb's toll; stalled - the dive gains; unpowered - toward the glide speed. */
    public static double airAccel(VehicleType t, double speed, double thr, double vy, boolean up, boolean powered) {
        return airAccel(t, speed, thr, vy, up, powered, 1);
    }

    /** The same with the afterburner factor (1 = none). */
    public static double airAccel(VehicleType t, double speed, double thr, double vy, boolean up, boolean powered, double mul) {
        return airAccel(t, t.plane, speed, thr, vy, up, powered, mul);
    }

    /** Stage A: with the high-lift params; the airbrake's descent gains no speed (the climb's toll stays). */
    public static double airAccel(VehicleType t, PlaneParams p, double speed, double thr, double vy, boolean up, boolean powered, double mul) {
        if (!powered) {
            if (stalled(p, speed)) {
                return STALL_GAIN;
            }
            double a = speed < p.glideSpeed ? GLIDE_ACC : -GLIDE_ACC;
            if (p.airbrake) {
                a = -GLIDE_ACC;                                 // the airbrake bleeds it toward the stall
            }
            return up ? a - 0.006 : a;                          // a flare bleeds speed
        }
        double a = accel(t, p, speed, thr, true, false, mul) - (p.airbrake ? Math.max(0, vy) : vy) * CLIMB_DRAG;
        return stalled(p, speed) ? a + STALL_GAIN : a;
    }

    /** Fastest it may go now: top speed (x the afterburner) x MAX_SPEED_MUL (a dive), never over FlightMath.SPEED_CAP. */
    public static double maxSpeed(VehicleType t, double mul) {
        return Math.min(t.topSpeed * mul * MAX_SPEED_MUL, FlightMath.SPEED_CAP);
    }

    /**
     * EU a tick before the config multiplier: idle + (mode - idle) x throttle, mode = peakCost on the ground (take-off
     * run) and climbing, cruiseCost in level flight; at Y >= CRUISE_Y in the air x CRUISE_MUL; x the cargo factor.
     */
    public static double cost(VehicleType t, double thr, boolean airborne, double vy, double y, double fill, boolean working) {
        return cost(t, t.plane, thr, airborne, vy, y, fill, working);
    }

    /** Stage A: x p.cost (the flaps: the mock-up's x1.1 / x1.25 / x1.5); the climb share by the type's own climb rate. */
    public static double cost(VehicleType t, PlaneParams p, double thr, boolean airborne, double vy, double y, double fill, boolean working) {
        if (!working) {
            return 0;
        }
        double mode = airborne ? t.cruiseCost + (t.peakCost - t.cruiseCost) * VehicleMath.clamp(vy / t.plane.climb, 0, 1) : t.peakCost;
        double c = t.idleCost + (mode - t.idleCost) * VehicleMath.clamp(thr, 0, 1);
        if (airborne && y >= FlightMath.CRUISE_Y) {
            c *= FlightMath.CRUISE_MUL;
        }
        return c * FlightMath.cargoMul(fill) * p.cost;
    }

    /**
     * The server's airspeed of an aircraft the driver's client flies (the stall flag, the flying-move rules, the
     * autopilot's stall cancel): the client's own physics speed from its Move packets - the speed its PlanePhysicsSVA
     * drops the nose by - but never more than AIR_MARGIN x the measured one + AIR_SLACK (a client can't claim airspeed
     * it doesn't cover). The measured speed alone is no good for this: the Moves arrive in the server's ticks unevenly
     * (a tick with none, the next with two - only the latest runs, ServerQueueSVA), so a per-tick measurement reads 0
     * and then double; its EMA (x0.5 a tick) halved on every empty tick - the light plane cruising at 57 km/h read
     * 28 km/h, under the 36 km/h stall, and the synced stall flag blinked on (title, СВАЛ. lamp, siren) in level flight.
     * NaN / infinite = the measured speed.
     *
     * @param measured the measured horizontal speed (blocks/tick), already smoothed over a few ticks
     */
    public static double reportedAirspeed(double client, double measured) {
        double m = Math.max(0, measured);
        if (Double.isNaN(client) || Double.isInfinite(client)) {
            return m;
        }
        return VehicleMath.clamp(client, 0, m * AIR_MARGIN + AIR_SLACK);
    }

    /** reportedAirspeed's cap over the measured speed (a factor and blocks/tick): one Move late in 4 ticks still passes. */
    public static final double AIR_MARGIN = 1.25, AIR_SLACK = 0.05;

    /** The throttle a measured airspeed needs at least (the server's floor against a client that hides its keys). */
    public static double throttleFloor(VehicleType t, double speed) {
        double r = speed / t.topSpeed;
        return VehicleMath.clamp(0.9 * r * r, 0, 1);
    }

    /** Hull HP a touch-down takes: hard (over SAFE_LANDING) and belly (unpowered) landings, capped at LANDING_CAP of the hull. */
    public static double landingDamage(float maxHp, double impact, double speed, boolean powered) {
        return landingDamage(PlaneParams.LIGHT, maxHp, impact, speed, powered);
    }

    /** The same with the type's glide speed (a belly landing hurts more the faster over it). */
    public static double landingDamage(PlaneParams p, float maxHp, double impact, double speed, boolean powered) {
        double d = Math.max(0, impact - SAFE_LANDING) * HARD_DAMAGE;
        if (!powered) {
            d += BELLY_FRAC * maxHp + Math.max(0, speed - p.glideSpeed) * BELLY_SPEED;
        }
        return Math.min(d, LANDING_CAP * maxHp);
    }

    /** Ground run (blocks) from standstill to the type's lift-off speed holding W, as the physics steps it. Infinite if it never gets there. */
    public static double takeoffDistance(VehicleType t, boolean runway) {
        return takeoffDistance(t, t.plane, runway);
    }

    /** Stage A: with the high-lift params (the flaps' lift-off speed and drag). */
    public static double takeoffDistance(VehicleType t, PlaneParams p, boolean runway) {
        double v = 0, thr = 0, d = 0;
        for (int i = 0; i < 4000; i++) {
            thr = throttleStep(thr, true, false, true);
            v = Math.max(0, v + accel(t, p, v, thr, false, runway, 1));
            d += v;
            if (v >= p.liftOff) {
                return d;
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}
