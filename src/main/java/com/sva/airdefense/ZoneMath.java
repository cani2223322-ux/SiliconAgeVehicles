package com.sva.airdefense;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Air-defence wave (mock-up veh_ad_5_zones_rules): airspace zones - pure, the self-test checks it.
 * <p>
 * Four types: ЗАПРЕТНАЯ (a stranger inside it, and within its heights, is a violator - the launchers on «по нарушителям»
 * fire after the warnings), ОГРАНИЧЕННАЯ (only a warning to the pilot and a line in the command post's log; no fire),
 * АЭРОДРОМ (no fire inside; the codes of its range count as the air defence's own), КОРИДОР (a permitted way through a
 * forbidden zone at its heights and on its course, +-CORRIDOR_TOL). Shapes: a rectangle (block corners, inclusive) or a
 * circle. The height band «от - до» works only where the altitude is known (a height finder, a tracking radar or a Mode C
 * / S reply); with the altitude unknown only a zone from the ground to the sky (0 - 255) counts.
 * <p>
 * Pilots (map, HSI, HUD) are warned before the border: 40 blocks - the zone lights up on the map, 20 - a tone and the line
 * «Через 20 блоков запретная зона», 10 - again and the HSI / HUD flash; inside - the air defence's stages (RoeMath).
 */
public final class ZoneMath {

    public static final int FORBIDDEN = 0, RESTRICTED = 1, AERODROME = 2, CORRIDOR = 3, TYPES = 4;
    public static final String[] TYPE_KEYS = {"forbidden", "restricted", "aerodrome", "corridor"};
    /** Map / HUD colours (RGB) of the types. */
    public static final int[] COLOURS = {0xF05050, 0xF0B040, 0x5EC8F0, 0x62E06A};
    public static final int RECT = 0, CIRCLE = 1;
    /** Zones per command post; the largest radius / half side; the top of the height band. */
    public static final int MAX_ZONES = 16, MAX_HALF = 512, ALT_MAX = 255;
    /**
     * Audit 2026-10-22, bug 1: the world's border (vanilla: 30 000 000) - every coordinate a zone keeps is clamped to it, so
     * a box from a modified client ({-2e9, 2e9}) can no longer overflow the size check into a zone over the whole world.
     */
    public static final int WORLD_LIMIT = 30000000;
    /** Warning distances to the border (blocks). */
    public static final int WARN_FAR = 40, WARN_NEAR = 20, WARN_CLOSE = 10;
    /** A corridor's course tolerance (deg). */
    public static final int CORRIDOR_TOL = 30;
    /** warnLevel results. */
    public static final int W_NONE = 0, W_FAR = 1, W_NEAR = 2, W_CLOSE = 3, W_INSIDE = 4;

    private ZoneMath() {
    }

    /** One zone. Its command post's position and owner travel with it (the pilots' list, the launchers' checks). */
    public static final class Zone {
        public String name = "";
        public int type = FORBIDDEN, shape = CIRCLE;
        /** Rectangle: the corner blocks (x0 <= x1, z0 <= z1). Circle: the centre block and the radius. */
        public int x0, z0, x1, z1, cx, cz, r = 32;
        /**
         * Wave A (zone editing on the map): a rectangle turned clockwise by this many degrees (0..359) round its middle - the
         * box x0..x1 / z0..z1 is its shape before the turn. Circles: always 0. Old saves have none: 0, the box as it was.
         */
        public int rot;
        /** The height band (Y). */
        public int lo = 0, hi = ALT_MAX;
        /** Aerodrome: its codes (octal ints, inclusive); corridor: its course (compass deg). */
        public int codeLo = 04100, codeHi = 04177, course = 0;
        /** Shown to pilots; the command post's alarm on entry. */
        public boolean show = true, alarm = true;
        /** The command post: its name, owner and block. */
        public String kp = "", owner = "";
        public int kx, ky, kz, dim;
        /**
         * Audit 2026-10-22 (bug 7): a control tower's aerodrome zone (AirDefenseDataSVA keeps it under «twr:»; its name is the
         * tower's unique ICAO code) - only these may be trusted by name. Set by AirDefenseDataSVA; not saved, not copied.
         */
        public boolean tower;

        public Zone copy() {
            Zone z = new Zone();
            NBTTagCompound t = new NBTTagCompound();
            write(t);
            z.read(t);
            return z;
        }

        public void write(NBTTagCompound t) {
            t.setString("Name", name == null ? "" : name);
            t.setByte("Type", (byte) type);
            t.setByte("Shape", (byte) shape);
            t.setIntArray("Box", new int[] {x0, z0, x1, z1});
            t.setIntArray("Circle", new int[] {cx, cz, r});
            t.setShort("Lo", (short) lo);
            t.setShort("Hi", (short) hi);
            t.setShort("CodeLo", (short) codeLo);
            t.setShort("CodeHi", (short) codeHi);
            t.setShort("Course", (short) course);
            t.setShort("Rot", (short) rot);
            t.setBoolean("Show", show);
            t.setBoolean("Alarm", alarm);
            t.setString("Kp", kp == null ? "" : kp);
            t.setString("Owner", owner == null ? "" : owner);
            t.setIntArray("KpPos", new int[] {kx, ky, kz, dim});
        }

        /** Reads and sanitises (sizes, band, codes, the name's length) - also what a client's edit goes through. */
        public void read(NBTTagCompound t) {
            name = clip(t.getString("Name"), 12);
            type = clampI(t.getByte("Type"), 0, TYPES - 1);
            shape = t.getByte("Shape") == RECT ? RECT : CIRCLE;
            int[] b = t.getIntArray("Box");
            if (b.length == 4) {
                // bug 1: inside the world's border, the sizes in long (x1 - x0 of {-2e9, 2e9} overflowed an int below the cap)
                int ax = clampI(b[0], -WORLD_LIMIT, WORLD_LIMIT), az = clampI(b[1], -WORLD_LIMIT, WORLD_LIMIT);
                int bx = clampI(b[2], -WORLD_LIMIT, WORLD_LIMIT), bz = clampI(b[3], -WORLD_LIMIT, WORLD_LIMIT);
                x0 = Math.min(ax, bx);
                x1 = Math.max(ax, bx);
                z0 = Math.min(az, bz);
                z1 = Math.max(az, bz);
                if ((long) x1 - x0 > 2L * MAX_HALF) {
                    x1 = x0 + 2 * MAX_HALF;
                }
                if ((long) z1 - z0 > 2L * MAX_HALF) {
                    z1 = z0 + 2 * MAX_HALF;
                }
            }
            int[] c = t.getIntArray("Circle");
            if (c.length == 3) {
                cx = clampI(c[0], -WORLD_LIMIT, WORLD_LIMIT);
                cz = clampI(c[1], -WORLD_LIMIT, WORLD_LIMIT);
                r = clampI(c[2], 1, MAX_HALF);
            }
            lo = clampI(t.getShort("Lo"), 0, ALT_MAX);
            hi = clampI(t.getShort("Hi"), lo, ALT_MAX);
            codeLo = clampI(t.getShort("CodeLo"), 0, XpdrMath.MAX);
            codeHi = clampI(t.getShort("CodeHi"), codeLo, XpdrMath.MAX);
            course = ((t.getShort("Course") % 360) + 360) % 360;
            rot = shape == RECT ? ((t.getShort("Rot") % 360) + 360) % 360 : 0;
            show = !t.hasKey("Show") || t.getBoolean("Show");
            alarm = !t.hasKey("Alarm") || t.getBoolean("Alarm");
            kp = clip(t.getString("Kp"), 24);
            owner = clip(t.getString("Owner"), 40);
            int[] k = t.getIntArray("KpPos");
            if (k.length == 4) {
                kx = k[0];
                ky = k[1];
                kz = k[2];
                dim = k[3];
            }
        }

        /** «3-1 0-256»: the name and the band as the map shows them. */
        public String label() {
            return name + " " + lo + "-" + hi;
        }

        /** The zone runs from the ground to the sky (its band needs no altitude). */
        public boolean fullBand() {
            return lo <= 0 && hi >= ALT_MAX;
        }
    }

    static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < n; i++) {
            char c = s.charAt(i);
            if (c >= ' ' && c != '§') {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The middle of a rectangle (its turning point) / the centre of a circle (world x, z). */
    public static double[] middle(Zone z) {
        return z.shape == RECT ? new double[] {(z.x0 + z.x1 + 1) / 2.0, (z.z0 + z.z1 + 1) / 2.0} : new double[] {z.cx + 0.5, z.cz + 0.5};
    }

    /**
     * Wave A: a world point in a turned rectangle's own frame (turned back by rot round its middle) - there the box tests
     * hold as for an unturned one. Clockwise on the map (x east, z south): world = R(rot) local, local = R(-rot) world.
     */
    public static double[] local(Zone z, double x, double zz) {
        if (z.shape != RECT || z.rot == 0) {
            return new double[] {x, zz};
        }
        double[] m = middle(z);
        double a = Math.toRadians(-z.rot), c = Math.cos(a), s = Math.sin(a), dx = x - m[0], dz = zz - m[1];
        return new double[] {m[0] + dx * c - dz * s, m[1] + dx * s + dz * c};
    }

    /** The inverse of local(): a point of the rectangle's own frame in the world. */
    public static double[] world(Zone z, double lx, double lz) {
        if (z.shape != RECT || z.rot == 0) {
            return new double[] {lx, lz};
        }
        double[] m = middle(z);
        double a = Math.toRadians(z.rot), c = Math.cos(a), s = Math.sin(a), dx = lx - m[0], dz = lz - m[1];
        return new double[] {m[0] + dx * c - dz * s, m[1] + dx * s + dz * c};
    }

    /** A rectangle's four corners in the world, clockwise from the north-west one (turned): {{x, z} x 4}. */
    public static double[][] corners(Zone z) {
        double[][] l = {{z.x0, z.z0}, {z.x1 + 1, z.z0}, {z.x1 + 1, z.z1 + 1}, {z.x0, z.z1 + 1}};
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            out[i] = world(z, l[i][0], l[i][1]);
        }
        return out;
    }

    /**
     * The outline as a closed polyline in the world (the ND's and the HUD's frames): a rectangle's four corners, a circle's
     * `n` points (n >= 8). The last point is not repeated.
     */
    public static double[][] outline(Zone z, int n) {
        if (z.shape == RECT) {
            return corners(z);
        }
        n = Math.max(8, n);
        double[][] out = new double[n][];
        for (int k = 0; k < n; k++) {
            double a = k * Math.PI * 2 / n;
            out[k] = new double[] {z.cx + 0.5 + Math.cos(a) * z.r, z.cz + 0.5 + Math.sin(a) * z.r};
        }
        return out;
    }

    /** Wave A: the point of the outline nearest to (x, z) from outside (inside: the point itself) - the HUD's pointer. */
    public static double[] nearestPoint(Zone z, double x, double zz) {
        if (z.shape == RECT) {
            double[] p = local(z, x, zz);
            return world(z, Math.max(z.x0, Math.min(z.x1 + 1, p[0])), Math.max(z.z0, Math.min(z.z1 + 1, p[1])));
        }
        double dx = x - (z.cx + 0.5), dz = zz - (z.cz + 0.5), d = Math.sqrt(dx * dx + dz * dz);
        if (d <= z.r || d < 1e-9) {
            return new double[] {x, zz};
        }
        return new double[] {z.cx + 0.5 + dx / d * z.r, z.cz + 0.5 + dz / d * z.r};
    }

    /** Inside the zone's outline (x, z: world coordinates, block centres at +0.5). */
    public static boolean contains2D(Zone z, double x, double zz) {
        if (z.shape == RECT) {
            double[] p = local(z, x, zz);
            return p[0] >= z.x0 && p[0] <= z.x1 + 1 && p[1] >= z.z0 && p[1] <= z.z1 + 1;
        }
        double dx = x - (z.cx + 0.5), dz = zz - (z.cz + 0.5);
        return dx * dx + dz * dz <= (double) z.r * z.r;
    }

    /** Within the height band; altitude unknown - only a full band counts. */
    public static boolean altIn(Zone z, double y, boolean altKnown) {
        return altKnown ? y >= z.lo && y <= z.hi + 1 : z.fullBand();
    }

    public static boolean contains(Zone z, double x, double y, double zz, boolean altKnown) {
        return contains2D(z, x, zz) && altIn(z, y, altKnown);
    }

    /** The distance from (x, z) to the outline (0 inside). */
    public static double distance2D(Zone z, double x, double zz) {
        if (z.shape == RECT) {
            double[] p = local(z, x, zz);
            double dx = Math.max(0, Math.max(z.x0 - p[0], p[0] - (z.x1 + 1))), dz = Math.max(0, Math.max(z.z0 - p[1], p[1] - (z.z1 + 1)));
            return Math.sqrt(dx * dx + dz * dz);
        }
        double dx = x - (z.cx + 0.5), dz = zz - (z.cz + 0.5);
        return Math.max(0, Math.sqrt(dx * dx + dz * dz) - z.r);
    }

    /**
     * The distance flown on the compass course from (x, z) until the outline is crossed into the zone (0: inside already),
     * -1 if the course misses it or it is further than `max`.
     */
    public static double ahead(Zone z, double x, double zz, double course, double max) {
        if (contains2D(z, x, zz)) {
            return 0;
        }
        double a = Math.toRadians(course - (z.shape == RECT ? z.rot : 0)), dx = Math.sin(a), dz = -Math.cos(a);
        double t;
        if (z.shape == RECT) {
            double tmin = 0, tmax = max;
            double[] lp = local(z, x, zz);
            double[] lo = {z.x0, z.z0}, hi = {z.x1 + 1, z.z1 + 1}, p = {lp[0], lp[1]}, d = {dx, dz};
            for (int i = 0; i < 2; i++) {
                if (Math.abs(d[i]) < 1e-9) {
                    if (p[i] < lo[i] || p[i] > hi[i]) {
                        return -1;
                    }
                    continue;
                }
                double t1 = (lo[i] - p[i]) / d[i], t2 = (hi[i] - p[i]) / d[i];
                tmin = Math.max(tmin, Math.min(t1, t2));
                tmax = Math.min(tmax, Math.max(t1, t2));
                if (tmin > tmax) {
                    return -1;
                }
            }
            t = tmin;
        } else {
            double ox = x - (z.cx + 0.5), oz = zz - (z.cz + 0.5);
            double b = ox * dx + oz * dz, c = ox * ox + oz * oz - (double) z.r * z.r, disc = b * b - c;
            if (disc < 0) {
                return -1;
            }
            t = -b - Math.sqrt(disc);
            if (t < 0) {
                return -1;
            }
        }
        return t <= max ? t : -1;
    }

    /**
     * Audit 2026-10-22, bug 1: the zone's farthest point from (x, z) - a rectangle's farthest corner (turned), a circle's
     * centre distance plus its radius.
     */
    public static double farthest(Zone z, double x, double zz) {
        if (z.shape == RECT) {
            double best = 0;
            for (double[] c : corners(z)) {
                best = Math.max(best, Math.hypot(c[0] - x, c[1] - zz));
            }
            return best;
        }
        return Math.hypot(z.cx + 0.5 - x, z.cz + 0.5 - zz) + z.r;
    }

    /**
     * Bug 1: the whole zone lies within `max` blocks of its command post's block (kx, kz) - the server takes no zone from a
     * screen that reaches further (airdefense.zoneMaxDistance; max <= 0: no limit).
     */
    public static boolean withinReach(Zone z, int kx, int kz, int max) {
        return max <= 0 || farthest(z, kx + 0.5, kz + 0.5) <= max;
    }

    /**
     * Bug 1 (the liner's ND): the part of the segment a - b inside the circle (cx, cz, r) as {t0, t1} (0..1 along the
     * segment), null when it misses - an edge is clipped to the screen before it is cut into dashes, so a long one (a zone
     * from an old save) costs only the pieces on the screen.
     */
    public static double[] clipToCircle(double ax, double az, double bx, double bz, double cx, double cz, double r) {
        double dx = bx - ax, dz = bz - az, fx = ax - cx, fz = az - cz;
        double a = dx * dx + dz * dz, c = fx * fx + fz * fz - r * r;
        if (a < 1e-12) {
            return c <= 0 ? new double[] {0, 1} : null;
        }
        double b = 2 * (fx * dx + fz * dz), disc = b * b - 4 * a * c;
        if (disc < 0) {
            return null;
        }
        double sq = Math.sqrt(disc), t0 = Math.max(0, (-b - sq) / (2 * a)), t1 = Math.min(1, (-b + sq) / (2 * a));
        return t0 <= t1 ? new double[] {t0, t1} : null;
    }

    /** The pilot's warning level for the distance to the border. */
    public static int warnLevel(double dist) {
        if (dist < 0) {
            return W_NONE;
        }
        return dist <= 0 ? W_INSIDE : dist <= WARN_CLOSE ? W_CLOSE : dist <= WARN_NEAR ? W_NEAR : dist <= WARN_FAR ? W_FAR : W_NONE;
    }

    /** The difference of two compass courses, 0..180. */
    public static double courseDiff(double a, double b) {
        double d = Math.abs(((a - b) % 360 + 360) % 360);
        return d > 180 ? 360 - d : d;
    }

    /** A corridor lets this flight through: inside it, in its band (altitude known or a full band), on its course. */
    public static boolean corridorPass(Zone c, double x, double y, double zz, double course, boolean altKnown) {
        return c.type == CORRIDOR && contains(c, x, y, zz, altKnown) && courseDiff(course, c.course) <= CORRIDOR_TOL;
    }

    /**
     * The forbidden zone this flight violates, or null: inside a forbidden zone (and its band) and not passing through a
     * corridor; an aerodrome zone it is in also clears it (no fire at an aerodrome).
     */
    public static Zone violated(List<Zone> zones, double x, double y, double zz, double course, boolean altKnown) {
        if (zones == null) {
            return null;
        }
        Zone hit = null;
        for (Zone z : zones) {
            if (z.type == FORBIDDEN && contains(z, x, y, zz, altKnown)) {
                hit = hit == null ? z : hit;
            }
        }
        if (hit == null) {
            return null;
        }
        for (Zone z : zones) {
            if (corridorPass(z, x, y, zz, course, altKnown) || z.type == AERODROME && contains(z, x, y, zz, altKnown)) {
                return null;
            }
        }
        return hit;
    }

    /** Two zones of the same command post (its block and dimension travel with every zone it publishes). */
    public static boolean sameKp(Zone a, Zone b) {
        return a.dim == b.dim && a.kx == b.kx && a.ky == b.ky && a.kz == b.kz;
    }

    /**
     * Audit 2026-10-22 (bug 14): the pilots' copy of violated(), per command post - a zone the flight need not be warned
     * about here: a forbidden zone whose OWN post's corridor lets this flight through, or whose own post's aerodrome zone
     * it is in. The client used to go quiet inside any corridor at all, while every post judges only its own zones
     * (TileEntityCommandPostSVA) - a corridor of Alice's post across Bob's forbidden zone hid Bob's zone from the pilot
     * (no frame, no prediction, no tone) while Bob's post took him for a violator. A restricted zone is never opened (the
     * post warns in it whatever the corridors).
     */
    public static boolean opened(List<Zone> zones, Zone z, double x, double y, double zz, double course, boolean altKnown) {
        if (zones == null || z == null || z.type != FORBIDDEN) {
            return false;
        }
        for (Zone c : zones) {
            if (c != z && sameKp(c, z) && (corridorPass(c, x, y, zz, course, altKnown) || c.type == AERODROME && contains(c, x, y, zz, altKnown))) {
                return true;
            }
        }
        return false;
    }

    /** The first zone of `type` the point is in (altitude rule as above), or null. */
    public static Zone inside(List<Zone> zones, int type, double x, double y, double zz, boolean altKnown) {
        if (zones != null) {
            for (Zone z : zones) {
                if (z.type == type && contains(z, x, y, zz, altKnown)) {
                    return z;
                }
            }
        }
        return null;
    }

    /** An aerodrome's code range holds this code. */
    public static boolean aerodromeCode(Zone z, int code) {
        return z.type == AERODROME && code >= z.codeLo && code <= z.codeHi;
    }

    /** The compass course out of a zone from (x, z) - straight away from its centre (the message «ПОКИНЬТЕ ЗОНУ: КУРС 270»). */
    public static int exitCourse(Zone z, double x, double zz) {
        double[] m = middle(z);
        double cx = m[0], cz = m[1];
        if (Math.abs(x - cx) < 1e-6 && Math.abs(zz - cz) < 1e-6) {
            return 0;
        }
        return (int) Math.round(RadarMath.compass(x - cx, zz - cz)) % 360;
    }
    // ------------------------------------------------------------------ wave A: the pilots' prediction

    /** A ground speed under this (blocks a tick) predicts nothing (hovering, taxiing). */
    public static final double PREDICT_MIN_SPEED = 0.05;

    /**
     * Wave A (plan-next §9 A2): the seconds until the flight crosses into the zone on its TRACK (where it really goes, not
     * where the nose points) at its ground speed (vx, vz in blocks a tick): 0 inside already, -1 when the track misses it,
     * it is slower than PREDICT_MIN_SPEED or the crossing is more than maxSeconds away. The height band as contains().
     */
    public static double secondsToEntry(Zone z, double x, double y, double zz, double vx, double vz, boolean altKnown, double maxSeconds) {
        if (!altIn(z, y, altKnown)) {
            return -1;
        }
        if (contains2D(z, x, zz)) {
            return 0;
        }
        double gs = Math.sqrt(vx * vx + vz * vz);
        if (gs < PREDICT_MIN_SPEED || !(maxSeconds > 0)) {
            return -1;
        }
        double d = ahead(z, x, zz, RadarMath.compass(vx, vz), gs * 20 * maxSeconds);
        return d < 0 ? -1 : d / (gs * 20);
    }

    /** Wave A: the prediction's level - 0 none, 1 within `warnSeconds` (amber), 2 within a third of it (red, the tone again), 3 inside. */
    public static int predictLevel(double seconds, double warnSeconds) {
        if (seconds < 0) {
            return 0;
        }
        if (seconds == 0) {
            return 3;
        }
        return seconds <= warnSeconds / 3 ? 2 : seconds <= warnSeconds ? 1 : 0;
    }
}
