package com.sva.vehicle;

/**
 * Cockpit wave (docs/plan-next.md §5 / §6, mock-ups veh_cab2_C2.png and veh_cab3_heli_C2.png): the pure logic behind the
 * C+ cockpits' glass and round faces that client/GlassCockpitSVA draws - the MFD's pages (which a type has, the next one,
 * the client's per-vehicle memory key), the helicopter's gauges (the radar altimeter's compressed scale, the dual tach,
 * the power reading, the hover indicator's drift vector), the radar altimeter's callouts and the transponder code's text.
 * No Minecraft classes: the dedicated server's self-test (debug/SelfTestCockpitSVA) runs it.
 */
public final class GlassMath {

    // ---------------------------------------------------------------- the MFD pages

    /** Pages: ДВИГ (engine, H2, battery), КАРТА, МАРШ (the flight plan), ГРУЗ (the load), СПО (radar warning + transponder). */
    public static final int ENG = 0, MAP = 1, ROUTE = 2, CARGO = 3, RWR = 4;
    /** Lang key part: sva.mfd.page.KEY (the key's label), sva.mfd.title.KEY (the page's title). */
    public static final String[] PAGE_KEYS = {"eng", "map", "route", "cargo", "rwr"};
    private static final int[] PLANE_PAGES = {ENG, MAP, ROUTE, RWR}, HELI_PAGES = {ENG, MAP, CARGO, RWR}, NONE = {};

    private GlassMath() {
    }

    /** The MFD's pages of a type in key order (the light plane's: ДВИГ КАРТА МАРШ СПО; the heli's: ДВИГ КАРТА ГРУЗ СПО). */
    public static int[] pages(VehicleType t) {
        return t == VehicleType.PLANE ? PLANE_PAGES.clone() : t == VehicleType.HELI ? HELI_PAGES.clone() : NONE.clone();
    }

    /** The type has an MFD (the «Страница MFD» function applies). */
    public static boolean hasMfd(VehicleType t) {
        return pages(t).length > 0;
    }

    /** The page shown: `page` if the type has it, else its first page (-1 without an MFD). */
    public static int sanitize(VehicleType t, int page) {
        int[] p = pages(t);
        for (int k : p) {
            if (k == page) {
                return page;
            }
        }
        return p.length == 0 ? -1 : p[0];
    }

    /** The page after `page` (wrapping round; an unknown page: the first). */
    public static int next(VehicleType t, int page) {
        int[] p = pages(t);
        for (int i = 0; i < p.length; i++) {
            if (p[i] == page) {
                return p[(i + 1) % p.length];
            }
        }
        return p.length == 0 ? -1 : p[0];
    }

    /** The key slot (0..3) of a page on the type's MFD, -1 when it has no such page. */
    public static int slot(VehicleType t, int page) {
        int[] p = pages(t);
        for (int i = 0; i < p.length; i++) {
            if (p[i] == page) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The client's memory key of a vehicle's page: the type, the board number and the owner (all three synced and
     * stable; the entity id is not across a reload), e.g. "plane:42:Steve" - no '=', ':' or spaces from the name.
     */
    public static String memoryKey(VehicleType t, int board, String owner) {
        String o = owner == null ? "" : owner.replaceAll("[^A-Za-z0-9_]", "_");
        return (t == null ? "?" : t.key) + "_" + board + "_" + o;
    }

    /** A remembered page read back ("2"): the page, or -1 for junk. */
    public static int parsePage(String s) {
        if (s == null) {
            return -1;
        }
        try {
            int p = Integer.parseInt(s.trim());
            return p >= 0 && p < PAGE_KEYS.length ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- dial scales (fractions 0..1 of the 270 deg sweep)

    /** The radar altimeter's top (blocks over the ground) and the knee of its scale: 0..10 takes half the sweep, 10..50 the rest. */
    public static final float RADALT_MAX = 50F, RADALT_KNEE = 10F;
    /** The decision height bug (blocks). */
    public static final float RADALT_BUG = 5F;

    /** The radar altimeter's needle fraction of `h` blocks (compressed, clamped 0..1). */
    public static float radaltFrac(float h) {
        if (!(h > 0)) {
            return 0F;
        }
        if (h <= RADALT_KNEE) {
            return h / RADALT_KNEE * 0.5F;
        }
        return Math.min(1F, 0.5F + (h - RADALT_KNEE) / (RADALT_MAX - RADALT_KNEE) * 0.5F);
    }

    /** The dual tach's and the power gauge's scale top (%). */
    public static final float TACH_MAX = 120F;

    /** A 0..TACH_MAX % reading's fraction. */
    public static float tachFrac(float pct) {
        return Math.max(0F, Math.min(1F, pct / TACH_MAX));
    }

    /** The needle angle (deg clockwise from up) of a scale fraction: -135 (lower left) .. +135 (lower right). */
    public static float angle(float frac) {
        return -CockpitDials.SWEEP / 2 + CockpitDials.SWEEP * Math.max(0F, Math.min(1F, frac));
    }

    /**
     * The engine's rpm % beside the rotor's (the dual tach's short needle): the free turbine runs a hair under the rotor
     * (97 %) while it drives it; in autorotation it drops to idle (IDLE %) and the rotor turns free - the needles split.
     */
    public static final float ENGINE_SHARE = 0.97F, IDLE = 60F;

    public static float engineRpm(float rotorPct, boolean autorotating) {
        return autorotating ? Math.min(IDLE, rotorPct) : rotorPct * ENGINE_SHARE;
    }

    /**
     * The power reading (% of the limit; over 100 = «ПЕРЕГРУЗ»): the helicopter's physics has no torque, so the gauge shows
     * what is asked of the rotor - BASE % to hold the spin, + the collective (throttle 0..1) x COLLECTIVE, + the load (cargo
     * fill 0..1) x LOAD, + climbing (blocks / tick up) x CLIMB. A cosmetic reading; full collective with a full hold
     * climbing hard goes past the red line.
     */
    public static final float POWER_BASE = 25F, POWER_COLLECTIVE = 55F, POWER_LOAD = 20F, POWER_CLIMB = 40F;

    public static float power(float collective, float cargoFill, float climb) {
        float c = Math.max(0F, Math.min(1F, collective)), l = Math.max(0F, Math.min(1F, cargoFill));
        return POWER_BASE + POWER_COLLECTIVE * c + POWER_LOAD * l + POWER_CLIMB * Math.max(0F, climb);
    }

    // ---------------------------------------------------------------- the hover indicator

    /** The hover indicator's outer ring (m/s = blocks / s): the vector is drawn to scale up to here, clamped beyond. */
    public static final float DRIFT_MAX = 2.4F;

    /**
     * The drift in the machine's frame {forward, right} (blocks / s) of its motion dx, dz (blocks / tick, world) at
     * Minecraft yaw `yawDeg` (0 = facing +Z / south, 90 = facing -X / west): forward = (-sin, cos), right = (-cos, -sin).
     */
    public static float[] drift(double dx, double dz, float yawDeg) {
        double a = Math.toRadians(yawDeg);
        double fx = -Math.sin(a), fz = Math.cos(a), rx = -Math.cos(a), rz = -Math.sin(a);
        return new float[] {(float) ((dx * fx + dz * fz) * 20), (float) ((dx * rx + dz * rz) * 20)};
    }

    /** The vector clamped to DRIFT_MAX (its direction kept). */
    public static float[] clampDrift(float[] d) {
        double l = Math.sqrt(d[0] * d[0] + d[1] * d[1]);
        if (l <= DRIFT_MAX || l == 0) {
            return new float[] {d[0], d[1]};
        }
        float k = (float) (DRIFT_MAX / l);
        return new float[] {d[0] * k, d[1] * k};
    }

    // ---------------------------------------------------------------- the radar altimeter's callouts («50.. 40.. 30»)

    /** The callouts (blocks over the ground), from the highest. */
    public static final int[] CALLOUTS = {50, 40, 30, 20, 10};

    /**
     * Descending from `was` to `now` blocks over the ground: the lowest callout crossed (was > c >= now), or -1. Climbing
     * or level: -1 (the callouts are for the descent only, as the real ones).
     */
    public static int callout(float was, float now) {
        if (!(now < was)) {
            return -1;
        }
        int out = -1;
        for (int c : CALLOUTS) {
            if (was > c && now <= c) {
                out = c;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- the transponder

    /** A squawk as its four octal digits ("7000"); out of 0..07777: "----". */
    public static String squawk(int code) {
        if (code < 0 || code > 07777) {
            return "----";
        }
        String o = Integer.toOctalString(code);
        while (o.length() < 4) {
            o = "0" + o;
        }
        return o;
    }

    // ---------------------------------------------------------------- the route page

    /**
     * The flight plan's rows from the vehicle at (x, z): per point {course into it (deg), distance along the plan from the
     * vehicle to it (blocks), minutes at `speed` (blocks / tick; NaN when too slow)} - the points before `active` get the
     * leg's course and NaN distances (passed). The course into the active point is from the vehicle, the next ones the
     * legs' (RouteMath.course). Same data source as the autopilot's МАРШ: RouteMath.Plan.
     */
    public static double[][] routeRows(RouteMath.Plan p, double x, double z, double speed) {
        int n = p.size();
        double[][] out = new double[n][];
        double cum = 0, px = x, pz = z;
        for (int i = 0; i < n; i++) {
            int[] w = p.pts.get(i);
            double tx = w[0] + 0.5, tz = w[1] + 0.5;
            if (i < p.active) {
                double crs = i == 0 ? Double.NaN : RouteMath.course(p.pts.get(i - 1)[0] + 0.5, p.pts.get(i - 1)[1] + 0.5, tx, tz);
                out[i] = new double[] {crs, Double.NaN, Double.NaN};
                continue;
            }
            double crs = RouteMath.course(px, pz, tx, tz);
            cum += Math.sqrt((tx - px) * (tx - px) + (tz - pz) * (tz - pz));
            double min = speed > 0.05 ? cum / speed / 20 / 60 : Double.NaN;
            out[i] = new double[] {crs, cum, min};
            px = tx;
            pz = tz;
        }
        return out;
    }
}
