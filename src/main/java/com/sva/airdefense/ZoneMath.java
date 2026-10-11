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
        /** The height band (Y). */
        public int lo = 0, hi = ALT_MAX;
        /** Aerodrome: its codes (octal ints, inclusive); corridor: its course (compass deg). */
        public int codeLo = 04100, codeHi = 04177, course = 0;
        /** Shown to pilots; the command post's alarm on entry. */
        public boolean show = true, alarm = true;
        /** The command post: its name, owner and block. */
        public String kp = "", owner = "";
        public int kx, ky, kz, dim;

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
                x0 = Math.min(b[0], b[2]);
                x1 = Math.max(b[0], b[2]);
                z0 = Math.min(b[1], b[3]);
                z1 = Math.max(b[1], b[3]);
                if (x1 - x0 > 2 * MAX_HALF) {
                    x1 = x0 + 2 * MAX_HALF;
                }
                if (z1 - z0 > 2 * MAX_HALF) {
                    z1 = z0 + 2 * MAX_HALF;
                }
            }
            int[] c = t.getIntArray("Circle");
            if (c.length == 3) {
                cx = c[0];
                cz = c[1];
                r = clampI(c[2], 1, MAX_HALF);
            }
            lo = clampI(t.getShort("Lo"), 0, ALT_MAX);
            hi = clampI(t.getShort("Hi"), lo, ALT_MAX);
            codeLo = clampI(t.getShort("CodeLo"), 0, XpdrMath.MAX);
            codeHi = clampI(t.getShort("CodeHi"), codeLo, XpdrMath.MAX);
            course = ((t.getShort("Course") % 360) + 360) % 360;
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

    /** Inside the zone's outline (x, z: world coordinates, block centres at +0.5). */
    public static boolean contains2D(Zone z, double x, double zz) {
        if (z.shape == RECT) {
            return x >= z.x0 && x <= z.x1 + 1 && zz >= z.z0 && zz <= z.z1 + 1;
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
            double dx = Math.max(0, Math.max(z.x0 - x, x - (z.x1 + 1))), dz = Math.max(0, Math.max(z.z0 - zz, zz - (z.z1 + 1)));
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
        double a = Math.toRadians(course), dx = Math.sin(a), dz = -Math.cos(a);
        double t;
        if (z.shape == RECT) {
            double tmin = 0, tmax = max;
            double[] lo = {z.x0, z.z0}, hi = {z.x1 + 1, z.z1 + 1}, p = {x, zz}, d = {dx, dz};
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
        double cx = z.shape == RECT ? (z.x0 + z.x1 + 1) / 2.0 : z.cx + 0.5, cz = z.shape == RECT ? (z.z0 + z.z1 + 1) / 2.0 : z.cz + 0.5;
        if (Math.abs(x - cx) < 1e-6 && Math.abs(zz - cz) < 1e-6) {
            return 0;
        }
        return (int) Math.round(RadarMath.compass(x - cx, zz - cz)) % 360;
    }
}
