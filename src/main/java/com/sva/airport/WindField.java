package com.sva.airport;

import com.sva.vehicle.LinerIce;
import com.sva.vehicle.LinerIls;

/**
 * Airport wave B1 (plan-next §9, item 22): the weather a «Метеостанция» measures - Minecraft has no wind, so it is a
 * DETERMINISTIC field from the world's seed, its time and its weather: every side (the server's station, the windsock on
 * every client, the self-test) computes the same wind for the same tick without a packet.
 * <ul>
 * <li>Direction (where it blows FROM, compass deg): a base from the seed, a slow swing over a few days (DIR_SWING over
 * DIR_DAYS days) and a wander of DIR_WANDER deg over WANDER_TICKS; thunder adds a quicker veer.</li>
 * <li>Speed (m/s at 10 m-eq): BASE_MIN..BASE_MAX by a two-day cycle, + RAIN_ADD in rain, + THUNDER_ADD in a thunderstorm;
 * gusts - smooth value noise over GUST_TICKS: up to GUST_CALM x in fair weather, GUST_STORM x in thunder.</li>
 * <li>Height: the log wind profile, v(h) = v10 ln(h / Z0) / ln(10 / Z0), h in m-eq over the ground (AptScale).</li>
 * <li>QNH (hPa): 1013 + QNH_SWING over QNH_DAYS days from the seed's phase, - QNH_RAIN in rain, - QNH_THUNDER in thunder;
 * the station follows it with a lag (lag(): QNH_LAG per tick) so a storm's front reads as a falling pressure.</li>
 * <li>Temperature (deg C): LinerIce's model (the biome, the height) + a day / night swing (DIURNAL).</li>
 * <li>Components on a runway: head wind (+ on the nose) and the cross wind (+ from the right).</li>
 * </ul>
 * Physics (airport.windPhysics, default off): the liner drifts with WIND_DRIFT x the wind in the air.
 */
public final class WindField {

    public static final double DIR_SWING = 70, DIR_DAYS = 3, DIR_WANDER = 25, WANDER_TICKS = 9000, BASE_MIN = 2, BASE_MAX = 8, RAIN_ADD = 4,
            THUNDER_ADD = 9, GUST_TICKS = 50, GUST_CALM = 0.25, GUST_STORM = 0.7, Z0 = 0.1;
    public static final double QNH_SWING = 9, QNH_DAYS = 5, QNH_RAIN = 6, QNH_THUNDER = 11, QNH_LAG = 0.002;
    public static final double DIURNAL = 4;
    /** The liner's drift per m/s of wind (b/t) when airport.windPhysics is on. */
    public static final double WIND_DRIFT = 0.02;

    private WindField() {
    }

    /** The wind: from (compass deg), speed (m/s, mean), the gust's speed now (m/s). */
    public static final class Wind {
        public double from, speed, gust;

        /** The wind's motion as a vector (m/s): it blows TOWARD from + 180. {x, z}. */
        public double[] vector() {
            double to = Math.toRadians(from + 180);
            return new double[] {Math.sin(to) * gust, -Math.cos(to) * gust};
        }
    }

    static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    /** 0..1 from a seed and a lane. */
    static double unit(long seed, int lane) {
        return (mix(seed * 31 + lane) >>> 11) / (double) (1L << 53);
    }

    /** Smooth value noise 0..1 over integer steps of x (a cosine blend between the lattice's values). */
    public static double noise(long seed, int lane, double x) {
        long i = (long) Math.floor(x);
        double f = x - i;
        double a = unit(seed ^ i * 0x9E3779B97F4A7C15L, lane), b = unit(seed ^ (i + 1) * 0x9E3779B97F4A7C15L, lane);
        double k = (1 - Math.cos(f * Math.PI)) / 2;
        return a + (b - a) * k;
    }

    /** The wind at world time `time` (ticks) `height` blocks over the ground. */
    public static Wind wind(long seed, double time, boolean rain, boolean thunder, double height) {
        Wind w = new Wind();
        double base = unit(seed, 1) * 360;
        double dir = base + DIR_SWING * Math.sin(2 * Math.PI * time / (DIR_DAYS * 24000) + unit(seed, 2) * 6.283)
                + DIR_WANDER * (2 * noise(seed, 3, time / WANDER_TICKS) - 1);
        if (thunder) {
            dir += 30 * (2 * noise(seed, 4, time / 600) - 1);
        }
        w.from = LinerIls.wrap360(dir);
        double v = BASE_MIN + (BASE_MAX - BASE_MIN) * (0.5 + 0.5 * Math.sin(2 * Math.PI * time / 48000 + unit(seed, 5) * 6.283));
        if (rain) {
            v += RAIN_ADD;
        }
        if (thunder) {
            v += THUNDER_ADD;
        }
        double hm = Math.max(2, AptScale.metres(Math.max(0.2, height)));
        double prof = Math.log(hm / Z0) / Math.log(10 / Z0);
        w.speed = v * Math.max(0.5, Math.min(2.2, prof));
        double g = noise(seed, 6, time / GUST_TICKS);
        w.gust = w.speed * (1 + (thunder ? GUST_STORM : GUST_CALM) * g);
        return w;
    }

    /** The QNH the weather heads for (hPa). */
    public static double qnhTarget(long seed, double time, boolean rain, boolean thunder) {
        double q = 1013 + QNH_SWING * Math.sin(2 * Math.PI * time / (QNH_DAYS * 24000) + unit(seed, 7) * 6.283);
        if (rain) {
            q -= QNH_RAIN;
        }
        if (thunder) {
            q -= QNH_THUNDER;
        }
        return q;
    }

    /** The station's QNH one tick on toward the target (NaN: starts at it). */
    public static double lag(double now, double target, int ticks) {
        if (Double.isNaN(now)) {
            return target;
        }
        double k = 1 - Math.pow(1 - QNH_LAG, Math.max(1, ticks));
        return now + (target - now) * k;
    }

    /** The air temperature (deg C) at Y in a biome (its temperature 0..2), time of day (ticks): LinerIce + the day's swing. */
    public static double temperature(double y, double biomeTemp, long dayTime) {
        double t = ((dayTime % 24000) + 24000) % 24000;
        // warmest at 8000 (mid-afternoon), coldest at 20000 (before dawn)
        return LinerIce.oat(y, biomeTemp) + DIURNAL * Math.cos(2 * Math.PI * (t - 8000) / 24000);
    }

    /** {head wind (+ on the nose), cross wind (+ from the right)} (m/s) for a runway course (compass). */
    public static double[] components(double from, double speed, double course) {
        double a = Math.toRadians(from - course);
        return new double[] {speed * Math.cos(a), speed * Math.sin(a)};
    }

    /** m/s -> km/h (the liner's units). */
    public static int kmh(double ms) {
        return (int) Math.round(ms * 3.6);
    }
}
