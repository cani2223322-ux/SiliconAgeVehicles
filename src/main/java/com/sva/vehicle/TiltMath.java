package com.sva.vehicle;

/**
 * Pure tiltrotor formulas (Movement.TILT). The nacelle position `pos` runs 0 (VERTICAL mode: rotors up, a helicopter)
 * .. 1 (AIRPLANE mode: rotors forward, an aeroplane); a switch («Режим», N) sets the target and the nacelles turn over
 * TRANSITION_TICKS (2 s). To airplane mode only in the air, at TO_PLANE_SPEED or MIN_HEIGHT blocks over the ground;
 * back to vertical only slow enough (at most BACK_MUL x the airplane stall speed; on the ground too, after a rolling
 * landing). During the
 * transition it flies like a helicopter (the collective holds the height) while the tilting rotors pull it up to
 * the airplane stall speed x CONVERT_MUL (to airplane) or let it slow to the vertical mode's top speed (back).
 */
public final class TiltMath {

    /** Nacelle position: vertical / airplane. */
    public static final double VERTICAL = 0, PLANE = 1;
    /** The turn takes this long (ticks). */
    public static final int TRANSITION_TICKS = 40;
    /** To airplane mode: in the air with this airspeed (b/t), or this high over the ground. */
    public static final double TO_PLANE_SPEED = 0.45, MIN_HEIGHT = 6;
    /** Back to vertical: airspeed at most the airplane stall x this. */
    public static final double BACK_MUL = 1.2;
    /** During the transition to airplane mode the speed is pulled up to stall x CONVERT_MUL at CONVERT_ACC a tick. */
    public static final double CONVERT_MUL = 1.15, CONVERT_ACC = 0.02;
    /** After the nacelles reach airplane mode the throttle starts at least here (enough to hold the conversion speed). */
    public static final double CONVERT_THROTTLE = 0.6;
    /** Switch results. */
    public static final int OK = 0, NOT_TILT = 1, ON_GROUND = 2, TOO_SLOW_LOW = 3, TOO_FAST = 4, BUSY = 5, SAME = 6;

    private TiltMath() {
    }

    /** One tick of the nacelles toward `target` (0 / 1). */
    public static double step(double pos, double target) {
        return FlightMath.approach(VehicleMath.clamp(pos, 0, 1), target >= 0.5 ? PLANE : VERTICAL, 1.0 / TRANSITION_TICKS);
    }

    /** Fully in airplane mode (aeroplane physics). */
    public static boolean planeMode(double pos) {
        return pos >= PLANE;
    }

    /** Fully in vertical mode (helicopter physics). */
    public static boolean verticalMode(double pos) {
        return pos <= VERTICAL;
    }

    /** The nacelles are turning. */
    public static boolean transition(double pos) {
        return pos > VERTICAL && pos < PLANE;
    }

    /**
     * May the pilot switch now? @param pos nacelles now, @param toPlane the wanted mode, @param air in the air,
     * @param speed airspeed (b/t), @param height blocks over the ground (capped by the caller), @param stall the
     * airplane mode's stall speed. @return OK or why not.
     */
    public static int switchCheck(double pos, boolean toPlane, boolean air, double speed, double height, double stall) {
        if (transition(pos)) {
            return BUSY;
        }
        if (toPlane == planeMode(pos)) {
            return SAME;
        }
        if (toPlane) {
            if (!air) {
                return ON_GROUND;                                   // a tiltrotor takes off vertically
            }
            return speed >= TO_PLANE_SPEED || height >= MIN_HEIGHT ? OK : TOO_SLOW_LOW;
        }
        return speed <= stall * BACK_MUL ? OK : TOO_FAST;           // also on the ground after a rolling landing
    }

    /** Nacelle angle for the model (degrees, 0 rotors up .. 90 rotors forward), eased at both ends. */
    public static float nacelleAngle(double pos) {
        double p = VehicleMath.clamp(pos, 0, 1);
        return (float) (90 * (p * p * (3 - 2 * p)));
    }

    /** Transition speed target: to airplane - the conversion speed, back - the vertical mode's top speed. */
    public static double transitionSpeed(VehicleType t, boolean toPlane) {
        return toPlane ? t.plane.stall * CONVERT_MUL : t.vtolTop;
    }
}
