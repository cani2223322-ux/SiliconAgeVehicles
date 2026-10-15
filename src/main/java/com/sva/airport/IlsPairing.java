package com.sva.airport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.LinerIls;

/**
 * Airport wave B1 (plan-next §9, items 15, 24): pure pairing - runways from their two «Торец полосы» markers, ILS stations
 * from a localizer array and a glide slope mast over a runway end, the runways' designators («27», parallels «27L» /
 * «27C» / «27R»), the frequency conflicts. The server (block.AirportDataSVA) runs it whenever a marker / antenna changes;
 * the self-test on numbers.
 * <ul>
 * <li><b>Runway</b>: a marker is placed looking down the runway (its `facing`, compass deg). Two markers pair when each lies
 * within PAIR_TOL deg of the other's facing, MIN_LEN..MAX_LEN apart (the nearest wins). The runway's course A->B is the
 * bearing between them; its width the markers' (3..9 blocks, set in the marker's screen; the first scan of the runway blocks
 * ahead suggests it).</li>
 * <li><b>Designators</b>: the magnetic course (the true one minus airport.magneticVariation, east +) / 10, «01»..«36».
 * Runway ends with the same number and parallel courses (within PARALLEL_TOL) are named left to right as seen landing: two
 * - L / R, three - L / C / R; four or more go on with the next number (09L 09R 10L 10R..., the real practice).</li>
 * <li><b>Station</b>: a localizer array pairs with the runway end it lies beyond (along the course past the far end by
 * LOC_MIN..LOC_MAX, within LOC_PAIR_LAT of the centre line; the best fit wins). A glide slope mast pairs with that end when
 * it stands beside the runway (GS_PAIR_LAT_MIN off the edge .. GS_PAIR_LAT_MAX) in its first part (0..GS_PAIR_ALONG); the
 * nearest to the nominal place (IlsCat's) wins. Its frequency is the localizer's (the G/S's is the paired one, as the ICAO
 * table pairs them); the ident too. A localizer without a mast: a LOC-only station (F_NO_GS).</li>
 * <li><b>Conflicts</b>: two stations on one frequency closer than CONFLICT blocks (twice the LOC's coverage) - both still
 * work (the receivers pick by course), the planner's ILS check says which to change.</li>
 * </ul>
 */
public final class IlsPairing {

    public static final double PAIR_TOL = 3, MIN_LEN = 40, MAX_LEN = 6000, PARALLEL_TOL = 15;
    /** Parallel runways (named L / C / R): at most this far apart across (blocks, 3 km-eq) and along (their thresholds). */
    public static final double PARALLEL_ACROSS = 300, PARALLEL_ALONG = 500;
    public static final double LOC_MIN = 3, LOC_MAX = 300, LOC_PAIR_LAT = 6, GS_PAIR_ALONG = 90, GS_PAIR_LAT_MIN = 2, GS_PAIR_LAT_MAX = 40;
    public static final double CONFLICT = 2 * IlsBeam.LOC_NARROW_RANGE;
    /** Runway width limits (blocks). */
    public static final int WIDTH_MIN = 3, WIDTH_MAX = 9;
    /** The magnetic variation (deg, east +): ConfigSVA airport.magneticVariation. */
    public static double magVar = 0;

    private IlsPairing() {
    }

    // ---------------------------------------------------------------- inputs

    /** A «Торец полосы» marker: its key, the block's centre, the runway surface's Y, the facing (compass), the width. */
    public static final class End {
        public String key = "";
        public int dim, width = 5;
        public double x, z, elev, facing;
        /** The lights' mode (RunwayLayout.L_*) and the approach lights at this end (0 none, 1 ALS). */
        public int lights = RunwayLayout.LIGHTS_AUTO, als;

        public End(String key, int dim, double x, double z, double elev, double facing, int width) {
            this.key = key;
            this.dim = dim;
            this.x = x;
            this.z = z;
            this.elev = elev;
            this.facing = facing;
            this.width = width;
        }
    }

    /** A runway: ends A and B (landing A -> B is course `course`), length, width, elevation, the designators. */
    public static final class Rwy {
        public End a, b;
        public double course, len, elev;
        public int width;
        public String nameA = "", nameB = "";
        /** The airport's ident (ICAO placeholder - the tower of wave B2 names it). */
        public String apt = "";

        public String key() {
            return a.key;
        }

        /** The threshold of landing direction dir (+1: at A, landing toward B). */
        public double[] threshold(int dir) {
            End e = dir > 0 ? a : b;
            return new double[] {e.x, e.z};
        }

        /** The landing course of direction dir (compass). */
        public double course(int dir) {
            return dir > 0 ? course : LinerIls.wrap360(course + 180);
        }

        public String name(int dir) {
            return dir > 0 ? nameA : nameB;
        }

        public End end(int dir) {
            return dir > 0 ? a : b;
        }

        /** World (x, z) -> {along from the threshold of dir, across + right of its landing direction}. */
        public double[] local(int dir, double x, double z) {
            double[] t = threshold(dir);
            double c = Math.toRadians(course(dir));
            double dx = x - t[0], dz = z - t[1];
            return new double[] {dx * Math.sin(c) - dz * Math.cos(c), dx * Math.cos(c) + dz * Math.sin(c)};
        }

        /** The runway-local point (along, across) of direction dir -> world {x, z}. */
        public double[] world(int dir, double along, double across) {
            double[] t = threshold(dir);
            double c = Math.toRadians(course(dir));
            return new double[] {t[0] + along * Math.sin(c) + across * Math.cos(c), t[1] - along * Math.cos(c) + across * Math.sin(c)};
        }

        /** (x, z) lies on the runway (within its width, between the thresholds; `margin` blocks more each way). */
        public boolean onRunway(double x, double z, double margin) {
            double[] l = local(1, x, z);
            return l[0] >= -margin && l[0] <= len + margin && Math.abs(l[1]) <= width / 2.0 + margin;
        }
    }

    /** A localizer array: its key, centre, ground Y, ident, frequency, and its state (intact, monitored, powered). */
    public static final class LocAnt {
        public String key = "", ident = "";
        public int dim, freq = LinerIls.FREQ_DEFAULT;
        public double x, z, y;
        public boolean intact = true, monitor, power = true;
    }

    /** A glide slope mast: its key, foot, ground Y, angle, state. */
    public static final class GsAnt {
        public String key = "";
        public int dim;
        public double x, z, y, angle = 3;
        public boolean intact = true, monitor, power = true;
    }

    /** A station: the runway end (dir), its localizer and its mast (null: LOC only) with their places in the runway's frame. */
    public static final class Pair {
        public Rwy r;
        public int dir;
        public LocAnt loc;
        public GsAnt gs;
        /** The array along from the threshold / across; beyond the far end; the mast along (setback) / across (offset). */
        public double la, lc, beyond, ga, gc;

        public String key() {
            return loc.key;
        }
    }

    // ---------------------------------------------------------------- runways

    /** The compass bearing (0 north = -z, 90 east = +x) from (x0, z0) to (x1, z1). */
    public static double bearing(double x0, double z0, double x1, double z1) {
        return LinerIls.wrap360(Math.toDegrees(Math.atan2(x1 - x0, -(z1 - z0))));
    }

    /** The runways from the markers (each used once; sorted by key for a stable order). */
    public static List<Rwy> runways(List<End> ends) {
        List<End> l = new ArrayList<End>(ends);
        Collections.sort(l, new Comparator<End>() {
            @Override
            public int compare(End p, End q) {
                return p.key.compareTo(q.key);
            }
        });
        List<Rwy> out = new ArrayList<Rwy>();
        boolean[] used = new boolean[l.size()];
        for (int i = 0; i < l.size(); i++) {
            if (used[i]) {
                continue;
            }
            End a = l.get(i);
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int j = 0; j < l.size(); j++) {
                if (j == i || used[j]) {
                    continue;
                }
                End b = l.get(j);
                if (b.dim != a.dim) {
                    continue;
                }
                double d = Math.sqrt(sq(b.x - a.x) + sq(b.z - a.z));
                if (d < MIN_LEN || d > MAX_LEN) {
                    continue;
                }
                double ab = bearing(a.x, a.z, b.x, b.z), ba = bearing(b.x, b.z, a.x, a.z);
                if (Math.abs(CruiseMath.wrap(ab - a.facing)) > PAIR_TOL || Math.abs(CruiseMath.wrap(ba - b.facing)) > PAIR_TOL) {
                    continue;
                }
                if (d < bd) {
                    bd = d;
                    best = j;
                }
            }
            if (best < 0) {
                continue;
            }
            used[i] = true;
            used[best] = true;
            Rwy r = new Rwy();
            r.a = a;
            r.b = l.get(best);
            r.course = bearing(a.x, a.z, r.b.x, r.b.z);
            r.len = bd;
            r.width = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, Math.min(a.width, r.b.width)));
            r.elev = (a.elev + r.b.elev) / 2;
            out.add(r);
        }
        name(out);
        return out;
    }

    /** The designator's number (1..36) of a true course with the magnetic variation. */
    public static int number(double course) {
        int n = (int) Math.round(LinerIls.wrap360(course - magVar) / 10.0) % 36;
        return n == 0 ? 36 : n;
    }

    public static String two(int n) {
        int k = ((n - 1) % 36 + 36) % 36 + 1;
        return k < 10 ? "0" + k : String.valueOf(k);
    }

    /** Names every runway end: the number, parallels L / C / R left to right as seen landing (see the class comment). */
    public static void name(List<Rwy> rws) {
        // every end: its runway, direction, number
        final List<Rwy> rr = new ArrayList<Rwy>();
        final List<Integer> dd = new ArrayList<Integer>();
        for (Rwy r : rws) {
            for (int dir = 1; dir >= -1; dir -= 2) {
                rr.add(r);
                dd.add(Integer.valueOf(dir));
            }
        }
        boolean[] done = new boolean[rr.size()];
        for (int i = 0; i < rr.size(); i++) {
            if (done[i]) {
                continue;
            }
            Rwy r0 = rr.get(i);
            int d0 = dd.get(i).intValue();
            final double c0 = r0.course(d0);
            int n0 = number(c0);
            List<Integer> group = new ArrayList<Integer>();
            for (int j = 0; j < rr.size(); j++) {
                Rwy r = rr.get(j);
                int d = dd.get(j).intValue();
                if (!done[j] && r.a.dim == r0.a.dim && number(r.course(d)) == n0 && Math.abs(CruiseMath.wrap(r.course(d) - c0)) <= PARALLEL_TOL) {
                    double[] t = r.threshold(d), t0r = r0.threshold(d0);
                    double cr0 = Math.toRadians(c0);
                    double dx = t[0] - t0r[0], dz = t[1] - t0r[1];
                    double along = dx * Math.sin(cr0) - dz * Math.cos(cr0), across = dx * Math.cos(cr0) + dz * Math.sin(cr0);
                    if (Math.abs(across) <= PARALLEL_ACROSS && Math.abs(along) <= PARALLEL_ALONG) {
                        group.add(Integer.valueOf(j));
                    }
                }
            }
            // left to right as seen landing: the across coordinate of each threshold in r0's frame
            final double[] t0 = r0.threshold(d0);
            final double cr = Math.toRadians(c0);
            Collections.sort(group, new Comparator<Integer>() {
                @Override
                public int compare(Integer p, Integer q) {
                    double[] a = rr.get(p.intValue()).threshold(dd.get(p.intValue()).intValue());
                    double[] b = rr.get(q.intValue()).threshold(dd.get(q.intValue()).intValue());
                    double ka = (a[0] - t0[0]) * Math.cos(cr) + (a[1] - t0[1]) * Math.sin(cr);
                    double kb = (b[0] - t0[0]) * Math.cos(cr) + (b[1] - t0[1]) * Math.sin(cr);
                    return Double.compare(ka, kb);
                }
            });
            String[] names = suffixes(n0, group.size());
            for (int k = 0; k < group.size(); k++) {
                int j = group.get(k).intValue();
                done[j] = true;
                Rwy r = rr.get(j);
                if (dd.get(j).intValue() > 0) {
                    r.nameA = names[k];
                } else {
                    r.nameB = names[k];
                }
            }
        }
    }

    /** The names of `count` parallel ends numbered n, left to right: "27"; "27L","27R"; "27L","27C","27R"; 4+: n L/R, n+1 L/R... */
    public static String[] suffixes(int n, int count) {
        String[] out = new String[count];
        if (count == 1) {
            out[0] = two(n);
        } else if (count == 2) {
            out[0] = two(n) + "L";
            out[1] = two(n) + "R";
        } else if (count == 3) {
            out[0] = two(n) + "L";
            out[1] = two(n) + "C";
            out[2] = two(n) + "R";
        } else {
            for (int k = 0; k < count; k++) {
                int pairIdx = k / 2;
                out[k] = two(n + pairIdx) + (k % 2 == 0 ? "L" : "R");
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- stations

    /** The stations: every localizer over the runway end it serves, with the best glide slope mast of that end. */
    public static List<Pair> pair(List<Rwy> rws, List<LocAnt> locs, List<GsAnt> gss) {
        List<Pair> out = new ArrayList<Pair>();
        List<String> usedGs = new ArrayList<String>();
        List<String> usedEnd = new ArrayList<String>();
        List<LocAnt> sorted = new ArrayList<LocAnt>(locs);
        Collections.sort(sorted, new Comparator<LocAnt>() {
            @Override
            public int compare(LocAnt p, LocAnt q) {
                return p.key.compareTo(q.key);
            }
        });
        for (LocAnt loc : sorted) {
            Pair best = null;
            double bs = Double.MAX_VALUE;
            for (Rwy r : rws) {
                if (r.a.dim != loc.dim) {
                    continue;
                }
                for (int dir = 1; dir >= -1; dir -= 2) {
                    if (usedEnd.contains(r.end(dir).key)) {
                        continue;
                    }
                    double[] l = r.local(dir, loc.x, loc.z);
                    double beyond = l[0] - r.len;
                    if (beyond < LOC_MIN || beyond > LOC_MAX || Math.abs(l[1]) > LOC_PAIR_LAT) {
                        continue;
                    }
                    double score = Math.abs(l[1]) + Math.abs(beyond - 30) / 20;
                    if (score < bs) {
                        bs = score;
                        best = new Pair();
                        best.r = r;
                        best.dir = dir;
                        best.loc = loc;
                        best.la = l[0];
                        best.lc = l[1];
                        best.beyond = beyond;
                    }
                }
            }
            if (best == null) {
                continue;
            }
            usedEnd.add(best.r.end(best.dir).key);
            // the glide slope mast of that end
            GsAnt g = null;
            double gd = Double.MAX_VALUE;
            for (GsAnt m : gss) {
                if (m.dim != loc.dim || usedGs.contains(m.key)) {
                    continue;
                }
                double[] l = best.r.local(best.dir, m.x, m.z);
                double out0 = Math.abs(l[1]) - best.r.width / 2.0;
                if (l[0] < 0 || l[0] > Math.min(GS_PAIR_ALONG, best.r.len / 2) || out0 < GS_PAIR_LAT_MIN || Math.abs(l[1]) > GS_PAIR_LAT_MAX) {
                    continue;
                }
                double score = Math.abs(l[0] - PlannerMath.GS_SETBACK) + Math.abs(Math.abs(l[1]) - PlannerMath.GS_OFFSET);
                if (score < gd) {
                    gd = score;
                    g = m;
                    best.ga = l[0];
                    best.gc = l[1];
                }
            }
            if (g != null) {
                usedGs.add(g.key);
                best.gs = g;
            }
            out.add(best);
        }
        return out;
    }

    /** The FULL station of a pair (the category and the status come later - AirportDataSVA with IlsCat). */
    public static LinerIls.Station station(Pair p) {
        LinerIls.Station s = new LinerIls.Station();
        s.kind = LinerIls.KIND_FULL;
        String[] k = p.loc.key.split(":");
        s.dim = p.loc.dim;
        try {
            s.bx = Integer.parseInt(k[1]);
            s.by = Integer.parseInt(k[2]);
            s.bz = Integer.parseInt(k[3]);
        } catch (RuntimeException e) {
            s.bx = (int) Math.floor(p.loc.x);
            s.by = (int) Math.floor(p.loc.y);
            s.bz = (int) Math.floor(p.loc.z);
        }
        double[] t = p.r.threshold(p.dir);
        s.fx = t[0];
        s.fz = t[1];
        s.elev = p.r.end(p.dir).elev;
        s.course = p.r.course(p.dir);
        s.ident = p.loc.ident;
        s.freq = p.loc.freq;
        s.lx = p.loc.x;
        s.lz = p.loc.z;
        if (p.gs != null) {
            s.gx = p.gs.x;
            s.gz = p.gs.z;
            s.gy = p.gs.y;
            s.angle = LinerIls.clampAngle(p.gs.angle);
        } else {
            s.angle = LinerIls.ANGLE_DEFAULT;
            s.flags |= LinerIls.F_NO_GS;
            double[] w = p.r.world(p.dir, PlannerMath.GS_SETBACK, PlannerMath.GS_OFFSET);
            s.gx = w[0];
            s.gz = w[1];
            s.gy = s.elev;
        }
        s.rwy = p.r.name(p.dir);
        s.apt = p.r.apt;
        s.rwyLen = (int) Math.round(p.r.len);
        s.phase = Math.abs(p.loc.key.hashCode()) % 1000;
        return s;
    }

    /** Frequency conflicts: {key, key} of stations on one frequency nearer than CONFLICT (same dimension). */
    public static List<String[]> conflicts(List<Pair> pairs) {
        List<String[]> out = new ArrayList<String[]>();
        for (int i = 0; i < pairs.size(); i++) {
            for (int j = i + 1; j < pairs.size(); j++) {
                LocAnt a = pairs.get(i).loc, b = pairs.get(j).loc;
                if (a.dim == b.dim && a.freq == b.freq && Math.sqrt(sq(a.x - b.x) + sq(a.z - b.z)) < CONFLICT) {
                    out.add(new String[] {a.key, b.key});
                }
            }
        }
        return out;
    }

    /**
     * The airports: runways within SAME_AIRPORT blocks of each other (chained) are one airport, named by the ICAO placeholder
     * «ZZ» + two letters from its first runway's place (the wave B2 tower names it). Sets Rwy.apt.
     */
    public static void airports(List<Rwy> rws) {
        int n = rws.size();
        int[] group = new int[n];
        for (int i = 0; i < n; i++) {
            group[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Rwy p = rws.get(i), q = rws.get(j);
                if (p.a.dim == q.a.dim && Math.sqrt(sq((p.a.x + p.b.x) / 2 - (q.a.x + q.b.x) / 2) + sq((p.a.z + p.b.z) / 2 - (q.a.z + q.b.z) / 2)) < SAME_AIRPORT) {
                    int gi = root(group, i), gj = root(group, j);
                    group[Math.max(gi, gj)] = Math.min(gi, gj);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            Rwy f = rws.get(root(group, i));
            int h = Math.abs((int) Math.floor(f.a.x) * 31 + (int) Math.floor(f.a.z) * 17);
            rws.get(i).apt = "ZZ" + (char) ('A' + h % 26) + (char) ('A' + h / 26 % 26);
        }
    }

    /** Runways this close (blocks, centre to centre) are one airport. */
    public static final double SAME_AIRPORT = 2000;

    private static int root(int[] g, int i) {
        while (g[i] != i) {
            i = g[i];
        }
        return i;
    }

    static double sq(double v) {
        return v * v;
    }
}
