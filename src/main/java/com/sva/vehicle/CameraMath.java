package com.sva.vehicle;

/**
 * Wave 1, ★3 (mock-up veh_more_2_camera_cruise.png): the third-person camera while riding - pure numbers (the client
 * applies them in client/VehicleCameraSVA; the self-test checks them).
 * <p>
 * Distance = BODY_LENGTHS x the vehicle's half length (= 1.5 x its length: the buggy ~8 blocks, the ship ~22), kept in
 * MIN..MAX, then x the player's zoom (mouse wheel, ZOOM_MIN..ZOOM_MAX) x the config's vehicles.cameraDistance, never under
 * ABS_MIN or over ABS_MAX. The camera looks PITCH_OFFSET degrees steeper down (it sits higher) and follows the vehicle's
 * turns with a lag of YAW_LAG seconds (an exponential follow: after YAW_LAG it has caught up 63 %).
 */
public final class CameraMath {

    public static final double BODY_LENGTHS = 3.0, MIN = 6, MAX = 24, ABS_MIN = 3, ABS_MAX = 48;
    public static final double ZOOM_MIN = 0.5, ZOOM_MAX = 2.0, ZOOM_STEP = 1.1;
    /** Vanilla's third-person distance (EntityRenderer.thirdPersonDistance) - put back when the ride ends. */
    public static final float VANILLA = 4.0F;
    public static final float PITCH_OFFSET = 20F;
    public static final double YAW_LAG = 0.3;
    /** The camera's distance eases toward its target with this time constant (s) - zooming and boarding glide. */
    public static final double DIST_EASE = 0.25;

    private CameraMath() {
    }

    /** The base distance for a body of this half length (blocks), before zoom and config. */
    public static double base(double halfLength) {
        return com.sva.vehicle.VehicleMath.clamp(halfLength * BODY_LENGTHS, MIN, MAX);
    }

    /** The camera distance: base x zoom (clamped) x the config factor, within ABS_MIN..ABS_MAX. */
    public static double distance(double halfLength, double zoom, double config) {
        double z = VehicleMath.clamp(zoom, ZOOM_MIN, ZOOM_MAX);
        double c = Double.isNaN(config) || config <= 0 ? 1 : config;
        return VehicleMath.clamp(base(halfLength) * z * c, ABS_MIN, ABS_MAX);
    }

    /** The zoom after `notches` of the wheel (+ = away): x / ZOOM_STEP a notch, within ZOOM_MIN..ZOOM_MAX. */
    public static double wheel(double zoom, int notches) {
        double z = zoom * Math.pow(ZOOM_STEP, notches);
        return VehicleMath.clamp(z, ZOOM_MIN, ZOOM_MAX);
    }

    /** How much of a gap an exponential follow with time constant `tau` closes in `dt` seconds (0..1). */
    public static double follow(double dt, double tau) {
        if (tau <= 0 || dt <= 0) {
            return dt <= 0 ? 0 : 1;
        }
        return 1 - Math.exp(-Math.min(dt, 1) / tau);
    }

    /** The pitch offset that still fits under the vanilla look clamp (+90): never past 89. */
    public static float pitchOffset(float pitch, float wanted) {
        return Math.max(0F, Math.min(wanted, 89F - pitch));
    }
}
