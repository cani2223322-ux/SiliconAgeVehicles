package com.sva.airport;

/**
 * Airport wave B1 (plan-next §9, item 21): the runway visual range a «Трансмиссометр RVR» measures and the category it
 * asks for - pure (the self-test runs it; block.AirportDataSVA feeds it the world).
 * <ul>
 * <li>The meteorological visibility (m-eq): clear air VIS_CLEAR; rain VIS_RAIN (snow in a cold biome VIS_SNOW); thunder
 * VIS_THUNDER; fog - a humid biome's (rainfall over FOG_HUMID) early morning and night (FOG_MORNING), a swamp's always
 * thicker (FOG_SWAMP), and the server's airport.fog (0 none .. 3 dense: FOG_CONFIG); inside the cloud layer (the sensor
 * that high: LinerIce.CLOUD_Y +- CLOUD_BAND) VIS_CLOUD. The worst of them wins.</li>
 * <li>RVR from the visibility (an Allard's-law flavour): by day the runway's markings are seen ~DAY_K further than the
 * meteorological visibility; at night only the lights - with them on, LIGHT_K per brightness step, off NIGHT_OFF_K.</li>
 * <li>A sensor's own reading wanders a little (JITTER, slow, from its position) - TDZ / MID / END differ like real ones.</li>
 * <li>Reported RVR_MIN..RVR_MAX (above: «P2000»), rounded to the reporting steps (25 m under 400, 50 under 800, 100).</li>
 * <li>The category it needs: RVR >= 550 - CAT I, >= 300 - CAT II, under - CAT III (the real minimums; the RVR is already
 * in m-eq, so they apply as they are).</li>
 * </ul>
 */
public final class RvrMath {

    public static final double VIS_CLEAR = 10000, VIS_RAIN = 1800, VIS_SNOW = 700, VIS_THUNDER = 600, VIS_CLOUD = 150, FOG_MORNING = 450,
            FOG_SWAMP = 250, FOG_HUMID = 0.8;
    /** airport.fog 1..3: the visibility it forces (m-eq). */
    public static final double[] FOG_CONFIG = {Double.MAX_VALUE, 1500, 500, 200};
    public static final double DAY_K = 1.5, LIGHT_K = 0.2, NIGHT_OFF_K = 0.7, JITTER = 0.06;
    public static final double RVR_MIN = 50, RVR_MAX = 2000;
    /** The categories' least RVR (m-eq): CAT I, CAT II (under: CAT III). */
    public static final double CAT1_RVR = 550, CAT2_RVR = 300;
    /** The cloud layer (Y) and its half thickness: LinerIce's. */
    public static final double CLOUD_Y = 128, CLOUD_BAND = 6;

    private RvrMath() {
    }

    /** The weather where the sensor stands. */
    public static final class Wx {
        public boolean rain, thunder, cold, swamp, night;
        /** The biome's rainfall (0..1+), the time of day (ticks 0..23999), airport.fog (0..3), the sensor's Y. */
        public double humidity = 0.4, y = 64;
        public long dayTime = 6000;
        public int fog;
    }

    /** The meteorological visibility (m-eq). */
    public static double visibility(Wx w) {
        double v = VIS_CLEAR;
        if (w.rain) {
            v = Math.min(v, w.cold ? VIS_SNOW : VIS_RAIN);
        }
        if (w.thunder) {
            v = Math.min(v, VIS_THUNDER);
        }
        long t = ((w.dayTime % 24000) + 24000) % 24000;
        boolean morning = t >= 22500 || t <= 1500, nightTime = t >= 13000 && t < 22500;
        if (w.swamp && (morning || nightTime)) {
            v = Math.min(v, FOG_SWAMP);
        } else if (w.humidity >= FOG_HUMID && morning) {
            v = Math.min(v, FOG_MORNING);
        }
        if (w.fog > 0) {
            v = Math.min(v, FOG_CONFIG[Math.min(3, w.fog)]);
        }
        if (Math.abs(w.y - CLOUD_Y) <= CLOUD_BAND) {
            v = Math.min(v, VIS_CLOUD);
        }
        return v;
    }

    /** RVR (m-eq, unrounded) from the visibility: by day the markings, at night the lights (brightness step 0..5). */
    public static double rvr(double vis, boolean night, int brightness) {
        double k = !night ? DAY_K : brightness > 0 ? 1 + LIGHT_K * Math.min(5, brightness) : NIGHT_OFF_K;
        return vis * k;
    }

    /** A sensor's slow wander (-1..1) from its position and the time. */
    public static double wander(long seed, double time) {
        double a = (seed & 0xFFFF) / 65536.0 * Math.PI * 2, b = ((seed >> 16) & 0xFFFF) / 65536.0 * Math.PI * 2;
        return 0.6 * Math.sin(time / 1300.0 + a) + 0.4 * Math.sin(time / 470.0 + b);
    }

    /** The reported value (m-eq): clamped, with the wander, rounded to the reporting steps. */
    public static int report(double rvr, long seed, double time) {
        double v = rvr * (1 + JITTER * wander(seed, time));
        v = Math.max(RVR_MIN, Math.min(RVR_MAX, v));
        int step = v < 400 ? 25 : v < 800 ? 50 : 100;
        return (int) (Math.round(v / step) * step);
    }

    /** The category this RVR needs (1..3). */
    public static int requiredCat(double rvr) {
        return rvr >= CAT1_RVR ? 1 : rvr >= CAT2_RVR ? 2 : 3;
    }

    /** "P2000" over the top, else the number. */
    public static String text(int rvr) {
        return rvr >= RVR_MAX ? "P2000" : rvr <= RVR_MIN ? "M0050" : String.valueOf(rvr);
    }
}
