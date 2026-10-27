package com.sva.airport;

import java.util.ArrayList;
import java.util.List;

/**
 * PACKAGE L (docs/plan-embassy-airfield-addon.md §3.5): the pure part of an embassy runway's look in the add-on - the core lays real
 * flush lights (edge, threshold, end, taxi edge) and the white / yellow paint in its blocks, so the add-on draws only what the core has
 * not: the centre line, the touchdown zone, the approach lights with the sequenced flasher, the PAPI and the threshold's wing bars, at
 * the 5 steps and AUTO as any runway (RunwayLayout.step). No Minecraft classes (the self-test counts the lights). The virtual runway runs
 * between the displaced thresholds (its ends are the threshold rows): `ext` - the paved blocks past each threshold (FULL 20, SHORT 1).
 * Written by the coordinator with first bodies; L may refine them (the signatures stay).
 * <ul>
 * <li><b>Lights</b> (lights(), RunwayLayout's frame and Light): the centre line over the whole paving - the displaced parts too - every
 * CL_STEP, its colours counted from the PAVED far end (white, red / white over the last CL_ALT, red over the last CL_RED), none on the
 * core's threshold rows (CL_CLEAR); the touchdown zone's barrettes of the landing end (as RunwayLayout); the landing threshold's green
 * wing bars - WING_LIGHTS a side just out of the paved edge (on FULL's shoulders), the core's flush green row between them; the ALS of
 * the landing end from its threshold out (over the displaced paving first), flagged Light.approach (the approach step). Never an edge
 * light, a red end bar or a green bar across - the core's lamps.</li>
 * <li><b>PAPI</b> (papiBoxes, papiWhites, papiWhite, papiStep): four boxes PAPI_SPACING apart going out from the runway beside the
 * landing end (the server's point - AirportDataSVA.clientData "EmbPapi": the inner box PAPI_ALONG past the threshold, PAPI_OFF out of
 * the paved edge), each white above its own angle (the glide angle + PAPI_STEPS: the outer box the lowest), red under it; on the slope
 * two white (outer) and two red (inner). It burns by day too (an AUTO end's step 0 - PAPI_DAY_STEP).</li>
 * </ul>
 */
public final class EmbassyLightsMath {

    /** The PAPI's four boxes: this far beside the runway's edge (blocks), the first box this far past the threshold (blocks). */
    public static final double PAPI_OFF = 3, PAPI_ALONG = 18;
    /** Each box's angle step around the glide slope's (deg; the outer pair +-0.5, the inner +-0.17 - the real PAPI's). */
    public static final double[] PAPI_STEPS = {-0.5, -0.17, 0.17, 0.5};
    /** The boxes: this far apart across (blocks), their lenses this high over the ground (blocks); the glide angle when none came. */
    public static final double PAPI_SPACING = 1.0, PAPI_H = 0.45, PAPI_ANGLE = 3.0;
    /** An AUTO end's PAPI by a clear day (its runway lights off) still burns at this step - it is a day aid as well. TODO(balance) */
    public static final int PAPI_DAY_STEP = 3;
    /** The threshold's wing bars: lights a side, their spacing, the first this far out of the paved edge, their height (blocks). */
    public static final int WING_LIGHTS = 5;
    public static final double WING_STEP = 0.4, WING_IN = 0.4, WING_H = 0.15;
    /** The centre line keeps this far (along) off the threshold rows - the core's flush lamps there (blocks). */
    public static final double CL_CLEAR = 0.6;
    /** Heights of the centre-line and the touchdown zone's lights over the surface (RunwayLayout's inlays). */
    public static final double INLAY_H = 0.03;
    /** Paved blocks past a threshold the client believes (a bad packet can't spread the lights for ever). */
    public static final double EXT_MAX = 64;

    private EmbassyLightsMath() {
    }

    /**
     * The add-on's lights of an embassy runway for a viewer landing in direction `dir` (RunwayLayout's frame: v along from end A, u
     * across): RunwayLayout.lights without the edge lights and the end's red (the core's own), the centre line carried on over the
     * displaced part `ext` before each threshold, the ALS where the end has one.
     */
    public static List<RunwayLayout.Light> lights(double len, int width, int dir, boolean alsA, boolean alsB, double ext) {
        List<RunwayLayout.Light> out = new ArrayList<RunwayLayout.Light>();
        double e = Math.max(0, Math.min(EXT_MAX, ext)), paved = len + 2 * e;
        // the centre line over the whole paving, its colours from the paved far end (the far displaced part is the runway's last metres)
        int m = (int) Math.floor((paved - 1) / RunwayLayout.CL_STEP);
        for (int i = 1; i <= m; i++) {
            double v = -e + i * RunwayLayout.CL_STEP;
            if (Math.abs(v) < CL_CLEAR || Math.abs(v - len) < CL_CLEAR) {
                continue;                                           // the core's threshold row has its lamp on the axis
            }
            double toEnd = dir > 0 ? len + e - v : v + e;
            int idx = dir > 0 ? i : m - i;
            out.add(new RunwayLayout.Light(0, v, INLAY_H, RunwayLayout.centreColour(toEnd, idx), -1));
        }
        // the touchdown zone of the viewer's landing end (as RunwayLayout)
        double tu = Math.max(0.6, width * 0.2);
        for (double vd = RunwayLayout.TDZ_STEP; vd <= Math.min(RunwayLayout.TDZ_LEN, len / 2); vd += RunwayLayout.TDZ_STEP) {
            double v = dir > 0 ? vd : len - vd;
            for (int k = 0; k < 3; k++) {
                double u = tu + k * 0.15;
                out.add(new RunwayLayout.Light(u, v, INLAY_H, RunwayLayout.WHITE, -1));
                out.add(new RunwayLayout.Light(-u, v, INLAY_H, RunwayLayout.WHITE, -1));
            }
        }
        // the landing threshold's wing bars just out of the paved edges (the green row across is the core's)
        double half = width / 2.0, vt = dir > 0 ? 0 : len;
        for (int k = 0; k < WING_LIGHTS; k++) {
            double u = half + WING_IN + k * WING_STEP;
            out.add(new RunwayLayout.Light(u, vt, WING_H, RunwayLayout.GREEN, -1));
            out.add(new RunwayLayout.Light(-u, vt, WING_H, RunwayLayout.GREEN, -1));
        }
        // the approach lights of his landing end, from its (displaced) threshold out - inlays over the displaced paving, on posts past it
        if (dir > 0 ? alsA : alsB) {
            for (RunwayLayout.Light a : RunwayLayout.als()) {
                double h = a.v <= e + 0.01 ? INLAY_H + (a.colour == RunwayLayout.FLASH ? 0.25 : 0) : a.h;
                out.add(dir > 0 ? new RunwayLayout.Light(a.u, -a.v, h, a.colour, a.run, true)
                        : new RunwayLayout.Light(-a.u, len + a.v, h, a.colour, a.run, true));
            }
        }
        return out;
    }

    /** The PAPI's white boxes (0..4: 4 - far too high, 2 - on the slope, 0 - far too low) for a viewer `elevDeg` over the threshold's slope `angle`. */
    public static int papiWhites(double elevDeg, double angle) {
        int n = 0;
        for (double s : PAPI_STEPS) {
            if (elevDeg > angle + s) {
                n++;
            }
        }
        return n;
    }

    /** Box `box` (0 - nearest the runway .. 3 - the outer one) shows white when `whites` boxes do (the outer ones turn white first). */
    public static boolean papiWhite(int box, int whites) {
        return box >= 4 - Math.max(0, Math.min(4, whites));
    }

    /** The viewer's elevation angle over a lens (deg): `dy` over it, `dist` away across the ground. */
    public static double elevation(double dy, double dist) {
        return Math.toDegrees(Math.atan2(dy, Math.max(1e-3, dist)));
    }

    /** The glide angle of a PAPI point's packed value (angle x 10; nothing sensible - PAPI_ANGLE). */
    public static double angleOf(int tenths) {
        double a = tenths / 10.0;
        return a >= 1 && a <= 10 ? a : PAPI_ANGLE;
    }

    /**
     * The PAPI's step: the end's runway step `step` (its mode `mode` with the night / rain / RVR - RunwayLayout.step); an AUTO / tower end
     * by a clear day (step 0) - PAPI_DAY_STEP; a fixed «off» (0) - off.
     */
    public static int papiStep(int mode, int step) {
        if (step > 0) {
            return Math.min(RunwayLayout.STEPS, step);
        }
        return mode == RunwayLayout.LIGHTS_AUTO || mode == RunwayLayout.LIGHTS_TOWER ? PAPI_DAY_STEP : 0;
    }

    /**
     * The four boxes {along, across} in the frame of their landing end (along from its threshold, across + right) from the server's point
     * (`along`, `across` in the same frame) on a runway `width` wide: box 0 at the point, the others PAPI_SPACING further out from the
     * runway. A point on the threshold (along under 1) goes PAPI_ALONG in; a point on the paving goes out to the left, PAPI_OFF past the
     * edge - the PAPI never stands on the runway.
     */
    public static double[][] papiBoxes(double along, double across, int width) {
        double a = along < 1 ? PAPI_ALONG : along, c = across;
        double edge = width / 2.0;
        if (Math.abs(c) < edge + 0.5) {
            c = -(edge + PAPI_OFF);
        }
        double out = c < 0 ? -1 : 1;
        double[][] b = new double[4][];
        for (int k = 0; k < 4; k++) {
            b[k] = new double[] {a, c + out * k * PAPI_SPACING};
        }
        return b;
    }
}
