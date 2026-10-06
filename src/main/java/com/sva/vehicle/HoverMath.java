package com.sva.vehicle;

/**
 * Pure hover formulas (Movement.HOVER): heights are blocks from the vehicle's bottom down to the surface under
 * it (ground, water or lava top). It holds HOVER_BASE (Space: HOVER_HIGH) over the highest surface under its
 * nose / centre, and is never more than HOVER_CAP over the surface under its centre - no free flight; with no
 * lift (no gas, no EU, overheated, wrecked, nobody aboard) it glides down at GLIDE at most and lands.
 */
public final class HoverMath {

    /** Normal / Space-held hover height; the plan's limit 1-3 blocks (HOVER_MIN..HOVER_MAX). */
    public static final double HOVER_BASE = 1.5, HOVER_HIGH = 2.8, HOVER_MIN = 1.0, HOVER_MAX = 3.0;
    /** Highest it may be over the surface under its centre (a hill under the nose may lift it a bit past HOVER_MAX). */
    public static final double HOVER_CAP = 4.0;
    /** Vertical speeds, blocks/tick: climb, powered sink, unpowered glide (start, top) and its gain a tick. */
    public static final double CLIMB = 0.2, SINK = 0.15, GLIDE_START = 0.05, GLIDE = 0.12, GLIDE_ACC = 0.01;
    /** How far down it looks for a surface (blocks). */
    public static final int SCAN = 8;
    /** Floats this high on a liquid when it came down on one. */
    public static final double FLOAT = 0.25;
    /** Over the surface by more than this = in the air (the server's checks). */
    public static final double AIRBORNE = 0.5;

    private HoverMath() {
    }

    /**
     * Height to hold: HOVER_BASE / HOVER_HIGH over the highest probed surface, but at most HOVER_CAP over the
     * centre's. NaN surfaces (nothing within SCAN) are ignored; no centre surface = nothing to hold (-inf).
     */
    public static double targetY(double centreSurface, double highestSurface, boolean high) {
        if (Double.isNaN(centreSurface)) {
            return Double.NEGATIVE_INFINITY;
        }
        double top = Double.isNaN(highestSurface) ? centreSurface : Math.max(centreSurface, highestSurface);
        return Math.min(top + (high ? HOVER_HIGH : HOVER_BASE), centreSurface + HOVER_CAP);
    }

    /** Vertical speed this tick: lift - toward the target (CLIMB up, SINK down at most); no lift - a glide down. */
    public static double verticalSpeed(double y, double target, boolean lift, double vy) {
        if (lift) {
            if (Double.isInfinite(target) || Double.isNaN(target)) {
                return -SINK;
            }
            return VehicleMath.clamp((target - y) * 0.2, -SINK, CLIMB);
        }
        double v = Math.min(vy, -GLIDE_START) - GLIDE_ACC;
        return Math.max(v, -GLIDE);
    }

    /** May a driver's move end at height `h` over the centre's surface (climbing: dy > 0.05)? */
    public static boolean climbAllowed(boolean lift, double h) {
        return lift && !Double.isNaN(h) && h <= HOVER_CAP + 0.35;
    }

    /** Must a driver's move go down (the server's no-free-flight rule): in the air without lift, or far over the cap. */
    public static boolean mustDescend(boolean lift, double h) {
        if (Double.isNaN(h)) {
            return true;
        }
        return lift ? h > HOVER_CAP + 1.0 : h > AIRBORNE;
    }

    /** Top speed now: no lift = no thrust (it coasts), boost x boostMul. */
    public static double maxSpeed(VehicleType t, boolean lift, boolean boost) {
        if (!lift) {
            return 0;
        }
        return t.topSpeed * (boost ? t.gases.boostMul : 1);
    }

    /** Seconds the lift gas lasts at this rate (infinite when nothing is spent). */
    public static double secondsLeft(int mb, double perSecond) {
        return perSecond <= 0 ? Double.POSITIVE_INFINITY : Math.max(0, mb) / perSecond;
    }
}
