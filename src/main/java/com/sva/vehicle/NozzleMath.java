package com.sva.vehicle;

/**
 * Aircraft round 2, stage D3 (★13, mock-up docs/mockups/veh_air3_3_gear_nozzle.png, the nozzle row): the jet's petal
 * nozzle and its flame, pure (SelfTestAirD3SVA).
 * <ul>
 * <li>The exit diameter follows the throttle as on the mock-up's strip, in its units (blocks / 16 with the petal ring's
 * root at Ø ROOT_DIAM): idle open Ø 6.3, closing to Ø 4.7 at full dry throttle (cruise ~5.4 at half), the afterburner
 * wide open Ø 6.9. diameter(throttle, ab).</li>
 * <li>The model's 18 petals (veh_real11.py: root ring of radius R_ROOT at ROOT_X, LEN long, built at REST_DEG in from the
 * axis = idle) turn about their root's tangent: petalDeg(diameter) keeps the mock-up's exit / root ratio, so the short
 * petals of the export (the bounding box ends at the old nozzle) swing a little further (idle 8, full 27 deg).</li>
 * <li>The petals travel at most RATE degrees a tick (the mock-up: 0.5 s over the whole range).</li>
 * <li>The flame (RenderVehicleSVA / ObjModelSVA.renderJetFlame): idle the D2 glow (0.1 long), then longer and bluer with
 * the throttle (flameLen / flameBlue), the afterburner a 1.9 long cone (30 concept units) with DIAMONDS shock diamonds
 * (diamond(k): along the flame, shrinking) flickering at ~8 Hz.</li>
 * </ul>
 */
public final class NozzleMath {

    /** The mock-up's diameters (blocks / 16): idle, full dry, afterburner; the root ring. */
    public static final float DIAM_IDLE = 6.3F, DIAM_MAX = 4.7F, DIAM_AB = 6.9F, ROOT_DIAM = 7.0F;
    /** The model's petal ring (model units, veh_real11.NOZ): the root's x, radius, the petal's length, the axis height. */
    public static final float ROOT_X = -2.84204F, R_ROOT = 0.203125F, LEN = 0.146875F, AXIS_Y = 0.56106F;
    /** The angle the petals are modelled at (= idle). */
    public static final float REST_DEG = 7.95F;
    public static final int PETALS = 18;
    /** Petal travel per tick (deg). */
    public static final float RATE = 2F;
    /** The afterburner flame's length (model units: 30 concept), the dry flame at full throttle, the idle glow. */
    public static final float AB_LEN = 1.9F, DRY_LEN = 0.75F, IDLE_LEN = 0.1F;
    /** Shock diamonds in the afterburner flame; the flicker (rad / tick: ~8 Hz at 20 ticks a second is 2.5). */
    public static final int DIAMONDS = 4;
    public static final float FLICKER = 2.5F;

    private NozzleMath() {
    }

    /** The exit diameter (mock-up units) at throttle 0..1, ab = the afterburner lit. */
    public static float diameter(double throttle, boolean ab) {
        if (ab) {
            return DIAM_AB;
        }
        float t = (float) Math.max(0, Math.min(1, throttle));
        return DIAM_IDLE - (DIAM_IDLE - DIAM_MAX) * (float) Math.pow(t, 0.8);
    }

    /** The petals' angle in from the axis (deg) giving that exit diameter on the model's ring. */
    public static float petalDeg(float diam) {
        double s = R_ROOT * (1 - diam / ROOT_DIAM) / LEN;
        return (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, s))));
    }

    /** The exit radius (model units) at petal angle deg. */
    public static float exitRadius(float deg) {
        return R_ROOT - LEN * (float) Math.sin(Math.toRadians(deg));
    }

    /** The petals one tick on toward target (deg). */
    public static float step(float cur, float target) {
        return GearMath.step(cur, target, RATE);
    }

    /** The flame's length (model units): idle glow .. the dry flame at full throttle; the afterburner's cone. */
    public static float flameLen(double throttle, boolean ab) {
        if (ab) {
            return AB_LEN;
        }
        float t = (float) Math.max(0, Math.min(1, throttle));
        return IDLE_LEN + (DRY_LEN - IDLE_LEN) * (float) Math.pow(t, 1.5);
    }

    /** How blue the dry flame is (0 orange at idle .. 1 blue-white at full throttle, the mock-up's frame 3). */
    public static float flameBlue(double throttle) {
        float t = (float) Math.max(0, Math.min(1, throttle));
        return t * t;
    }

    /** Shock diamond k (0..DIAMONDS-1): {position along the flame 0..1, size relative to the exit radius}. */
    public static float[] diamond(int k) {
        float t = (k + 0.8F) / (DIAMONDS + 1.5F);
        return new float[] {t, 0.55F * (1F - t * 0.6F)};
    }
}
