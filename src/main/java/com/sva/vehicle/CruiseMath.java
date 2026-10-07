package com.sva.vehicle;

/**
 * Wave 1, ★4 (mock-up veh_more_2_camera_cruise.png): cruise control and the aircraft autopilot - pure control laws (the
 * self-test simulates them on the dedicated server). Speeds in blocks/tick, heights in world Y, yaw in Minecraft degrees.
 * <p>
 * CRUISE (Movement GROUND / CRAWLER / HOVER): «Круиз» (key O) holds the speed it was switched on at; the mouse wheel moves
 * the set speed by STEP_KMH; W still accelerates (letting go raises the set speed to what it reached, as a real car's
 * «resume / accel»); S, Space (brake) or no thrust cancel it. It drives through the normal physics - the motor pushes
 * with the type's accel up to the set speed and coasts down to it (downhill), so EU is spent as by a driver holding W.
 * <p>
 * AUTOPILOT (Movement PLANE, the tiltrotor in airplane mode; only in the air): holds the heading and the height it was
 * switched on at - the yaw is turned toward the heading by YAW_GAIN of the error a tick (never faster than the type's turn
 * rate), the vertical speed is steered toward ALT_GAIN x the height error (capped by the climb the airspeed allows and the
 * type's dive) at the physics' own vertical acceleration; the throttle stays where the pilot left it. Any of W / S / A / D,
 * a charge under MIN_CHARGE, a stall or touching the ground switches it off.
 */
public final class CruiseMath {

    /** What «Круиз» does on a type now. */
    public static final int NONE = 0, CRUISE = 1, AUTOPILOT = 2;
    /** Why it switched off (HUD / chat): keys, low charge, stall, landed, no thrust (overheat / empty / no lift). */
    public static final int OFF_KEYS = 1, OFF_CHARGE = 2, OFF_STALL = 3, OFF_GROUND = 4, OFF_THRUST = 5, OFF_MODE = 6;
    /** The wheel's step of the set speed (km/h) and the lowest set speed. */
    public static final double STEP_KMH = 5, MIN_KMH = 5;
    /** blocks/tick per km/h (VehicleMath.kmh: b/t x 20 x 3.6). */
    public static final double BPT_PER_KMH = 1 / 72.0;
    /** The autopilot gives up under this charge (fraction of the buffer). */
    public static final double MIN_CHARGE = 0.05;
    /** Heading hold: the share of the error turned a tick; height hold: wanted vertical speed per block of error. */
    public static final double YAW_GAIN = 0.12, ALT_GAIN = 0.06;
    /** Under this height error (blocks) the wanted vertical speed is 0 - it doesn't hunt over a block. */
    public static final double ALT_DEADBAND = 0.15;

    private CruiseMath() {
    }

    /** What the key does on this movement / mode: CRUISE on the ground types and the hoverbike, AUTOPILOT on planes. */
    public static int kind(Movement m, boolean planeMode) {
        switch (m) {
            case GROUND:
            case CRAWLER:
            case HOVER:
                return CRUISE;
            case PLANE:
                return AUTOPILOT;
            case TILT:
                return planeMode ? AUTOPILOT : NONE;
            default:
                return NONE;                                   // helicopter, shuttle, ship: nothing to hold
        }
    }

    /** km/h -> blocks/tick. */
    public static double fromKmh(double kmh) {
        return kmh * BPT_PER_KMH;
    }

    /**
     * The set speed after `steps` wheel notches (+ faster): moves by STEP_KMH, snapped to the 5 km/h grid, kept in
     * MIN_KMH..top (blocks/tick).
     */
    public static double adjust(double set, int steps, double top) {
        double kmh = Math.round(set / BPT_PER_KMH / STEP_KMH) * STEP_KMH + steps * STEP_KMH;
        double max = Math.max(fromKmh(MIN_KMH), top);
        return VehicleMath.clamp(fromKmh(kmh), fromKmh(MIN_KMH), max);
    }

    /** The speed cruise is switched on at: the current forward speed, at least MIN_KMH, at most top. */
    public static double engageSpeed(double speed, double top) {
        return VehicleMath.clamp(speed, fromKmh(MIN_KMH), Math.max(fromKmh(MIN_KMH), top));
    }

    /** Cruise is cancelled by S (reverse) or the brake. W only overrides it. */
    public static boolean cruiseCancelled(boolean back, boolean brake) {
        return back || brake;
    }

    /**
     * One tick of cruise on the ground / hovering: speed toward `set` - up by `accel` with thrust, down to it by `coast`
     * (overspeed rolls off); no thrust - it rolls down as with the keys let go.
     */
    public static double groundStep(double speed, double set, double accel, double coast, boolean thrust) {
        if (!thrust) {
            return speed > 0 ? Math.max(0, speed - coast) : Math.min(0, speed + coast);
        }
        if (speed < set) {
            return Math.min(set, speed + accel);
        }
        return Math.max(set, speed - coast);
    }

    /** -180..180: how far `to` is from `from` (deg). */
    public static double wrap(double deg) {
        double d = deg % 360;
        if (d >= 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    /** The yaw after one tick of heading hold: YAW_GAIN of the error, never more than `maxRate` deg. */
    public static double headingStep(double yaw, double target, double maxRate) {
        double err = wrap(target - yaw);
        return yaw + VehicleMath.clamp(err * YAW_GAIN, -maxRate, maxRate);
    }

    /** The wanted vertical speed for a height error: ALT_GAIN a block, 0 inside the dead band, within [-dive, climb]. */
    public static double wantedVy(double alt, double y, double climb, double dive) {
        double err = alt - y;
        if (Math.abs(err) < ALT_DEADBAND) {
            return 0;
        }
        return VehicleMath.clamp(err * ALT_GAIN, -dive, Math.max(0, climb));
    }

    /** Vertical speed after one tick of height hold (the plane's VACC a tick toward wantedVy). */
    public static double holdVy(double vy, double alt, double y, double climb, double dive, double vacc) {
        return FlightMath.approach(vy, wantedVy(alt, y, climb, dive), vacc);
    }

    /**
     * Autopilot off-switch: 0 = keep flying, else OFF_*. keys = W / S / A / D pressed; charge 0..1.
     */
    public static int autopilotCancel(boolean keys, double charge, boolean stalled, boolean airborne, boolean powered) {
        if (keys) {
            return OFF_KEYS;
        }
        if (!airborne) {
            return OFF_GROUND;
        }
        if (stalled) {
            return OFF_STALL;
        }
        if (charge < MIN_CHARGE) {
            return OFF_CHARGE;
        }
        return powered ? 0 : OFF_THRUST;
    }

    /** The compass heading shown on the HUD (0 = north, 90 = east) from a Minecraft yaw (0 = south). */
    public static int heading(double yaw) {
        int h = (int) Math.round(yaw + 180) % 360;
        return h < 0 ? h + 360 : h;
    }
}
