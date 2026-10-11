package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Aircraft round 2, stage D4 (★22 the flight plan; mock-up docs/mockups/veh_air3_5_flightplan.png): the route of an
 * aircraft - up to MAX waypoints {x, z, height} set on the vehicle map's «Маршрут» tab (client.GuiMapSVA), kept in the
 * vehicle itself (VehicleState NBT), flown leg by leg by the autopilot's «МАРШ» mode (AutopilotMath.M_ROUTE). Pure maths,
 * simulated by the dedicated server's self-test (debug/SelfTestAirD4SVA). Headings as a compass (0 = north = -Z, 90 = east
 * = +X; CruiseMath.heading), distances in blocks, speeds in blocks/tick, turn rates in deg/tick.
 * <ul>
 * <li>the active leg runs from the previous waypoint to the active one (the first leg: straight from wherever the aircraft
 * is - «direct to»); the heading is the leg's course less XTK_GAIN deg per block off it (at most MAX_INTERCEPT: as ЗАХВ
 * intercepts a centre line), so the aircraft flies the line drawn on the map, not just toward the point;</li>
 * <li>turn anticipation: a real FMS turns before the point so the next leg starts on its line - the turn of radius
 * r = speed / rate needs r x tan(turn / 2) blocks before the point (lead()); within that (at least ARRIVE, at most half
 * of either leg; a turn over FLYOVER deg: over the point - reach()) the next waypoint becomes the active one. A point already passed (over the perpendicular to its leg at it; on the first leg:
 * behind and inside half the turn's radius) counts as reached too - a sharp corner never makes it circle round a point;</li>
 * <li>the height: from the previous point's to the active point's, evenly along the leg (the first leg: the point's
 * own at once) - the autopilot's ВЫС law flies toward it (with ВЕРТ: no faster than the set vertical speed);</li>
 * <li>after the last point: the last leg's course and the last point's height are held (one point only: the heading it
 * has); with a runway near the last point (HANDOVER blocks from its strip) ЗАХВ takes over by itself.</li>
 * </ul>
 */
public final class RouteMath {

    /** Waypoints at most (the map's list «5/16»). */
    public static final int MAX = 16;
    /** Waypoint heights: 1..ceiling - 2 (as the ВЫС knob), the - / + step (5 blocks as the knob; Shift: x10), a new point's default. */
    public static final int ALT_MIN = 1, ALT_MAX = (int) FlightMath.CEILING - 2, ALT_STEP = 5, ALT_DEFAULT = 100;
    /** Coordinates a waypoint may have (the world border of 1.7.10). */
    public static final int COORD_MAX = 30000000;
    /** Leg switching: the shortest lead (blocks; a straight-on point), the longest (a hairpin at the jet's speed). */
    public static final double LEAD_MIN = 8, LEAD_MAX = 80;
    /** The last point counts as reached this close (blocks). */
    public static final double ARRIVE = 8;
    /**
     * A turn sharper than this (deg) is flown over the point (reached within ARRIVE, then turned): its lead would be most
     * of a leg (r x tan 60+) and the point itself would be cut far off.
     */
    public static final double FLYOVER = 120;
    /** Off the leg: heading change per block (deg) and the largest intercept angle (deg). */
    public static final double XTK_GAIN = 2.5, MAX_INTERCEPT = 40;
    /** ЗАХВ takes over after the last point when a runway's strip is this close to it (blocks). */
    public static final double HANDOVER = 120;

    private RouteMath() {
    }

    // ---------------------------------------------------------------- the plan

    /** A route: the waypoints {x, z, alt} in order and the active one (0..size; size = flown through). */
    public static final class Plan {
        public final List<int[]> pts = new ArrayList<int[]>();
        public int active;

        public int size() {
            return pts.size();
        }

        public boolean isEmpty() {
            return pts.isEmpty();
        }

        /** Flown through: the last point is behind (holding its course and height). */
        public boolean done() {
            return !pts.isEmpty() && active >= pts.size();
        }

        public Plan copy() {
            Plan p = new Plan();
            for (int[] w : pts) {
                p.pts.add(w.clone());
            }
            p.active = active;
            return p;
        }

        /** A point added at the end (false: MAX reached). */
        public boolean add(int x, int z, int alt) {
            if (pts.size() >= MAX) {
                return false;
            }
            pts.add(new int[] {clampCoord(x), clampCoord(z), clampAlt(alt)});
            return true;
        }

        public void remove(int i) {
            if (i >= 0 && i < pts.size()) {
                pts.remove(i);
                if (active > i) {
                    active--;
                }
                active = Math.min(active, pts.size());
            }
        }

        /** Point i moved one place (dir -1 up the list, +1 down); false if it can't. */
        public boolean move(int i, int dir) {
            int j = i + dir;
            if (i < 0 || j < 0 || i >= pts.size() || j >= pts.size()) {
                return false;
            }
            int[] a = pts.get(i);
            pts.set(i, pts.get(j));
            pts.set(j, a);
            return true;
        }

        /** Point i's height changed by `delta` blocks (within ALT_MIN..ALT_MAX). */
        public void addAlt(int i, int delta) {
            if (i >= 0 && i < pts.size()) {
                int[] w = pts.get(i);
                w[2] = clampAlt(w[2] + delta);
            }
        }

        public void clear() {
            pts.clear();
            active = 0;
        }

        /** Same points (the active one not compared). */
        public boolean samePoints(Plan o) {
            if (o == null || o.pts.size() != pts.size()) {
                return false;
            }
            for (int i = 0; i < pts.size(); i++) {
                if (!java.util.Arrays.equals(pts.get(i), o.pts.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    public static int clampAlt(int alt) {
        return Math.max(ALT_MIN, Math.min(ALT_MAX, alt));
    }

    public static int clampCoord(int c) {
        return Math.max(-COORD_MAX, Math.min(COORD_MAX, c));
    }

    /** A plan from a packet / NBT: at most MAX points, each clamped; the active one within 0..size. */
    public static Plan sanitize(int[] flat, int active) {
        Plan p = new Plan();
        if (flat != null) {
            for (int i = 0; i + 2 < flat.length && p.size() < MAX; i += 3) {
                p.add(flat[i], flat[i + 1], flat[i + 2]);
            }
        }
        p.active = Math.max(0, Math.min(p.size(), active));
        return p;
    }

    /** The points as {x0, z0, alt0, x1, ...}. */
    public static int[] flatten(Plan p) {
        int[] a = new int[p.size() * 3];
        for (int i = 0; i < p.size(); i++) {
            int[] w = p.pts.get(i);
            a[i * 3] = w[0];
            a[i * 3 + 1] = w[1];
            a[i * 3 + 2] = w[2];
        }
        return a;
    }

    /** NBT keys (VehicleState): the flattened points and the active one. */
    public static final String K_POINTS = "FlightPlan", K_ACTIVE = "FlightPlanActive";

    /** Written always (empty: no points) - VehicleState.readNBT keeps the current plan only when the key is missing. */
    public static void write(Plan p, NBTTagCompound t) {
        if (p == null) {
            p = new Plan();
        }
        t.setIntArray(K_POINTS, flatten(p));
        t.setInteger(K_ACTIVE, p.active);
    }

    public static Plan read(NBTTagCompound t) {
        return sanitize(t.hasKey(K_POINTS) ? t.getIntArray(K_POINTS) : null, t.getInteger(K_ACTIVE));
    }

    /**
     * May this player set the route: an aircraft (the autopilot kind - plane, jet, tiltrotor) and the right to drive it
     * (AccessMath.rights - the owner, an op, a friend with «водить», a key card with it).
     */
    public static boolean mayEdit(boolean aircraft, int rights) {
        return aircraft && (rights & AccessMath.DRIVE) != 0;
    }

    // ---------------------------------------------------------------- the leg geometry

    /** The leg's course (compass deg) from (x0, z0) to (x1, z1). */
    public static double course(double x0, double z0, double x1, double z1) {
        return AutopilotMath.bearing(x0, z0, x1, z1);
    }

    /** Off the line through (x0, z0) along compass `course`: + = right of it looking along the course (blocks). */
    public static double crossTrack(double x0, double z0, double course, double px, double pz) {
        double r = Math.toRadians(course);
        // along = (sin c, -cos c) in (x, z); right of it (c + 90) = (cos c, sin c)
        return (px - x0) * Math.cos(r) + (pz - z0) * Math.sin(r);
    }

    /** Along the line from (x0, z0) on compass `course` (blocks; + = ahead of the start). */
    public static double alongTrack(double x0, double z0, double course, double px, double pz) {
        double r = Math.toRadians(course);
        return (px - x0) * Math.sin(r) - (pz - z0) * Math.cos(r);
    }

    /** The turn radius (blocks) at `speed` b/t turning `rate` deg/tick. */
    public static double turnRadius(double speed, double rate) {
        return rate <= 1e-6 ? LEAD_MAX : Math.max(0, speed) / Math.toRadians(rate);
    }

    /** How far before a point the next leg is taken: r x tan(turn / 2), within LEAD_MIN..LEAD_MAX (turn in deg). */
    public static double lead(double speed, double rate, double turn) {
        double t = Math.min(150, Math.abs(turn));
        return VehicleMath.clamp(turnRadius(speed, rate) * Math.tan(Math.toRadians(t / 2)), LEAD_MIN, LEAD_MAX);
    }

    /** The guidance this tick (guide()). */
    public static final class Guide {
        /** The active point (0..size; size = done), whether it changed this tick, whether the plan is flown through. */
        public int active;
        public boolean switched, done;
        /** The leg's course (compass deg; NaN after one lone point: hold the heading), the heading wanted (NaN = hold). */
        public double course = Double.NaN, heading = Double.NaN;
        /** Off the leg (blocks, + = right), the distance to the active point (blocks), the height wanted (Y). */
        public double cross, dist, alt;
        /** The active point (x, z centre of its block) and the leg's start (the previous point; NaN on the first leg). */
        public double tx, tz, fx = Double.NaN, fz = Double.NaN;
    }

    /**
     * One tick of the route at (x, z) flying compass `heading` at `speed` b/t turning at most `rate` deg/tick: the active
     * point advanced (into p.active) when reached / within its turn's lead / passed, then the leg's guidance. null for
     * an empty plan.
     */
    public static Guide guide(Plan p, double x, double z, double heading, double speed, double rate) {
        if (p == null || p.isEmpty()) {
            return null;
        }
        int n = p.size();
        p.active = Math.max(0, Math.min(n, p.active));
        Guide g = new Guide();
        for (int guard = 0; guard < n && p.active < n; guard++) {
            int[] t = p.pts.get(p.active);
            double tx = t[0] + 0.5, tz = t[1] + 0.5;
            double d = AutopilotMath.distance(x, z, tx, tz);
            boolean last = p.active == n - 1;
            double reach = ARRIVE;
            if (!last) {
                int[] nx = p.pts.get(p.active + 1);
                double in = legCourse(p, p.active, x, z), out = course(tx, tz, nx[0] + 0.5, nx[1] + 0.5);
                double inLen = p.active == 0 ? Double.MAX_VALUE : legLength(p, p.active), outLen = legLength(p, p.active + 1);
                reach = reach(speed, rate, CruiseMath.wrap(out - in), inLen, outLen);
            }
            boolean passed;
            if (p.active > 0) {
                // over the perpendicular at the point: past it, however far off the line
                int[] f = p.pts.get(p.active - 1);
                double fx = f[0] + 0.5, fz = f[1] + 0.5, c = course(fx, fz, tx, tz);
                passed = alongTrack(fx, fz, c, x, z) >= AutopilotMath.distance(fx, fz, tx, tz);
            } else {
                // direct to the first point: only one inside the turn's own circle, behind - it would be circled for ever
                double rel = Math.abs(CruiseMath.wrap(AutopilotMath.bearing(x, z, tx, tz) - heading));
                passed = rel > 90 && d < 0.5 * turnRadius(speed, rate);
            }
            if (d <= reach || passed) {
                p.active++;
                g.switched = true;
            } else {
                break;
            }
        }
        g.active = p.active;
        if (p.active >= n) {
            g.done = true;
            int[] t = p.pts.get(n - 1);
            g.tx = t[0] + 0.5;
            g.tz = t[1] + 0.5;
            g.alt = t[2];
            g.dist = AutopilotMath.distance(x, z, g.tx, g.tz);
            if (n >= 2) {
                int[] f = p.pts.get(n - 2);
                g.fx = f[0] + 0.5;
                g.fz = f[1] + 0.5;
                g.course = course(g.fx, g.fz, g.tx, g.tz);
                g.heading = g.course;
            }
            return g;
        }
        int[] t = p.pts.get(p.active);
        g.tx = t[0] + 0.5;
        g.tz = t[1] + 0.5;
        g.dist = AutopilotMath.distance(x, z, g.tx, g.tz);
        if (p.active == 0) {
            // the first leg: direct to the point, its own height at once
            g.course = course(x, z, g.tx, g.tz);
            g.heading = g.course;
            g.alt = t[2];
            return g;
        }
        int[] f = p.pts.get(p.active - 1);
        g.fx = f[0] + 0.5;
        g.fz = f[1] + 0.5;
        g.course = course(g.fx, g.fz, g.tx, g.tz);
        g.cross = crossTrack(g.fx, g.fz, g.course, x, z);
        g.heading = (g.course - VehicleMath.clamp(XTK_GAIN * g.cross, -MAX_INTERCEPT, MAX_INTERCEPT)) % 360;
        if (g.heading < 0) {
            g.heading += 360;
        }
        double len = AutopilotMath.distance(g.fx, g.fz, g.tx, g.tz);
        double k = len < 1e-6 ? 1 : VehicleMath.clamp(alongTrack(g.fx, g.fz, g.course, x, z) / len, 0, 1);
        g.alt = f[2] + (t[2] - f[2]) * k;
        return g;
    }

    /**
     * How close to a point the next leg is taken: the turn's lead, but never more than half of either leg (short legs keep
     * their points) and never under ARRIVE; a turn over FLYOVER deg is flown over the point (ARRIVE).
     */
    public static double reach(double speed, double rate, double turn, double inLen, double outLen) {
        if (Math.abs(turn) > FLYOVER) {
            return ARRIVE;
        }
        return Math.max(ARRIVE, Math.min(lead(speed, rate, turn), 0.5 * Math.min(inLen, outLen)));
    }

    /** The length of the leg into point i (from point i - 1; blocks). */
    private static double legLength(Plan p, int i) {
        int[] t = p.pts.get(i), f = p.pts.get(i - 1);
        return AutopilotMath.distance(f[0], f[1], t[0], t[1]);
    }

    /** The course into point i: from point i - 1, or (the first) from where the aircraft is. */
    private static double legCourse(Plan p, int i, double x, double z) {
        int[] t = p.pts.get(i);
        if (i == 0) {
            return course(x, z, t[0] + 0.5, t[1] + 0.5);
        }
        int[] f = p.pts.get(i - 1);
        return course(f[0] + 0.5, f[1] + 0.5, t[0] + 0.5, t[1] + 0.5);
    }

    /** The route's length (blocks): from (x, z) to the active point, then the legs on. */
    public static double length(Plan p, double x, double z) {
        double s = 0, px = x, pz = z;
        for (int i = Math.max(0, p.active); i < p.size(); i++) {
            int[] w = p.pts.get(i);
            s += AutopilotMath.distance(px, pz, w[0] + 0.5, w[1] + 0.5);
            px = w[0] + 0.5;
            pz = w[1] + 0.5;
        }
        return s;
    }

    /** ЗАХВ takes over after the last point: the runway's strip within HANDOVER blocks of it (distToStrip: RunwayMath.Runway.distance). */
    public static boolean handOver(boolean done, double distToStrip) {
        return done && !Double.isNaN(distToStrip) && distToStrip <= HANDOVER;
    }

    // ---------------------------------------------------------------- the synced target (EntityVehicleSVA.DW_ROUTE's third part)

    /** "active,size,tx,tz,alt,fx,fz" of the guidance (fx / fz: Integer.MIN_VALUE on the first leg), "" without a route. */
    public static String targetText(Plan p) {
        if (p == null || p.isEmpty()) {
            return "";
        }
        int n = p.size(), a = Math.max(0, Math.min(n, p.active));
        int[] t = p.pts.get(Math.min(a, n - 1));
        int[] f = a >= 1 && n >= 2 ? p.pts.get(Math.min(a, n - 1) - 1) : null;
        return a + "," + n + "," + t[0] + "," + t[1] + "," + t[2] + "," + (f == null ? Integer.MIN_VALUE : f[0]) + "," + (f == null ? Integer.MIN_VALUE : f[1]);
    }

    /** targetText parsed: {active, size, tx, tz, alt, fx, fz} or null. */
    public static int[] parseTarget(String s) {
        if (s == null || s.length() == 0) {
            return null;
        }
        String[] a = s.split(",");
        if (a.length != 7) {
            return null;
        }
        int[] r = new int[7];
        try {
            for (int i = 0; i < 7; i++) {
                r[i] = Integer.parseInt(a[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return r;
    }
}
