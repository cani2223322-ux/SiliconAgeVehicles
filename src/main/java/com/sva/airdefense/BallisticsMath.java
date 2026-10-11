package com.sva.airdefense;

import java.util.Random;

/**
 * Air-defence wave (mock-up veh_ad_3_launchers): the AA gun's lead and the missile's guidance - pure, the self-test checks
 * it. The shells and missiles themselves are simple server objects (AirDefenseSVA.Projectile), not entities: each tick
 * they move along their velocity and are tested against the target's hull (segToPoint) - nothing for the client to
 * simulate, the client sees particles.
 * <p>
 * Numbers (balanced from the mock-up): the AA gun (MV) - 110 blocks, a burst of 3 shells every 10 ticks, 4 b/t, 1.2 deg
 * spread with precise data (own eyes / a tracking radar), 2.5 deg on the surveillance radar's +-8 blocks; 10 hull points a
 * shell. The missile system (HV / EV) - 280 blocks, needs a tracking radar's lock (or the target within its own eyes),
 * 3 b/t, lives 8 s, turns up to 6 deg a tick, proximity fuse 3 blocks, 110 hull points; a launch costs 20 000 EU and
 * reloads 3 s; two missiles in the air at most (two fire channels). A missile whose target goes behind the terrain loses
 * it and flies straight on until it self-destructs.
 */
public final class BallisticsMath {

    public static final double GUN_RANGE = 110, GUN_SPEED = 4.0, GUN_SPREAD = 1.2, GUN_SPREAD_SURVEY = 2.5;
    public static final int GUN_BURST = 3, GUN_PERIOD = 10, SHELL_LIFE = 40;
    public static final float SHELL_DAMAGE = 10F;
    /** Shells a «Снаряды ПВО» item holds (one burst a shell item: x GUN_BURST rounds). */
    public static final int GUN_EU_BURST = 1500;

    public static final double MISSILE_RANGE = 280, MISSILE_SPEED = 3.0, MISSILE_TURN = 6.0, MISSILE_FUSE = 3.0;
    public static final int MISSILE_LIFE = 160, MISSILE_RELOAD = 60, MISSILE_CHANNELS = 2, MISSILE_EU = 20000;
    public static final float MISSILE_DAMAGE = 110F;

    /** A launcher's own eyes (no command post): this far, precise; its link to a command post. */
    public static final double EYES = 48, LINK = 64;

    private BallisticsMath() {
    }

    /**
     * Where to aim from p to meet a target at t moving with v (blocks / tick) with a projectile of `speed`: the intercept
     * point (5 iterations of time-of-flight), or null when it can't catch it within `maxTicks`.
     */
    public static double[] intercept(double px, double py, double pz, double tx, double ty, double tz, double vx, double vy, double vz,
            double speed, int maxTicks) {
        if (!(speed > 0)) {
            return null;
        }
        double t = dist(px, py, pz, tx, ty, tz) / speed;
        double ax = tx, ay = ty, az = tz;
        for (int i = 0; i < 5; i++) {
            ax = tx + vx * t;
            ay = ty + vy * t;
            az = tz + vz * t;
            t = dist(px, py, pz, ax, ay, az) / speed;
        }
        return t <= maxTicks ? new double[] {ax, ay, az, t} : null;
    }

    public static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The unit vector from a to b ({0, 1, 0} if they coincide). */
    public static double[] dir(double ax, double ay, double az, double bx, double by, double bz) {
        double l = dist(ax, ay, az, bx, by, bz);
        return l < 1e-9 ? new double[] {0, 1, 0} : new double[] {(bx - ax) / l, (by - ay) / l, (bz - az) / l};
    }

    /** A unit direction scattered inside a cone of `deg` (uniform on the cone's cap). */
    public static double[] spread(double[] d, double deg, Random rnd) {
        double cosMax = Math.cos(Math.toRadians(Math.max(0, deg)));
        double cz = 1 - rnd.nextDouble() * (1 - cosMax), phi = rnd.nextDouble() * Math.PI * 2, s = Math.sqrt(Math.max(0, 1 - cz * cz));
        // an orthonormal base round d
        double[] a = Math.abs(d[1]) < 0.9 ? new double[] {0, 1, 0} : new double[] {1, 0, 0};
        double[] u = norm(cross(d, a)), w = cross(d, u);
        double[] o = new double[3];
        for (int i = 0; i < 3; i++) {
            o[i] = d[i] * cz + u[i] * s * Math.cos(phi) + w[i] * s * Math.sin(phi);
        }
        return norm(o);
    }

    /**
     * The missile's new velocity: its direction turned towards `want` by at most maxDeg (in the plane of the two), at
     * `speed`.
     */
    public static double[] steer(double[] vel, double[] want, double maxDeg, double speed) {
        double[] a = norm(vel), b = norm(want);
        double cos = Math.max(-1, Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]));
        double ang = Math.acos(cos), max = Math.toRadians(maxDeg);
        double[] out;
        if (ang <= max || ang < 1e-6) {
            out = b;
        } else {
            // slerp a -> b by max / ang
            double k = max / ang, so = Math.sin(ang);
            double ka = Math.sin((1 - k) * ang) / so, kb = Math.sin(k * ang) / so;
            if (so < 1e-6) {
                // opposite: turn about any perpendicular
                double[] p = norm(cross(a, Math.abs(a[1]) < 0.9 ? new double[] {0, 1, 0} : new double[] {1, 0, 0}));
                out = norm(new double[] {a[0] * Math.cos(max) + p[0] * Math.sin(max), a[1] * Math.cos(max) + p[1] * Math.sin(max),
                        a[2] * Math.cos(max) + p[2] * Math.sin(max)});
            } else {
                out = norm(new double[] {a[0] * ka + b[0] * kb, a[1] * ka + b[1] * kb, a[2] * ka + b[2] * kb});
            }
        }
        return new double[] {out[0] * speed, out[1] * speed, out[2] * speed};
    }

    /** The squared distance from point p to the segment a-b. */
    public static double segToPoint2(double ax, double ay, double az, double bx, double by, double bz, double px, double py, double pz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az, l2 = dx * dx + dy * dy + dz * dz;
        double t = l2 < 1e-12 ? 0 : ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / l2;
        t = Math.max(0, Math.min(1, t));
        double qx = ax + dx * t - px, qy = ay + dy * t - py, qz = az + dz * t - pz;
        return qx * qx + qy * qy + qz * qz;
    }

    /** The angle (deg) between two vectors. */
    public static double angle(double[] a, double[] b) {
        double[] x = norm(a), y = norm(b);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, x[0] * y[0] + x[1] * y[1] + x[2] * y[2]))));
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return l < 1e-12 ? new double[] {0, 1, 0} : new double[] {a[0] / l, a[1] / l, a[2] / l};
    }
}
