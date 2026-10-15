package com.sva.airport;

import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.LinerIls;

/**
 * Airport wave B2 (plan-next §9, item 33): the instrument approach the tower publishes for each ILS runway end of its
 * airport (NavDb - the MCDU's «ЗАХОД» page, the approach chart, the tower's map). Pure; at the B1 scale (1 block = 10 m-eq,
 * real angles):
 * <ul>
 * <li><b>FAF</b> (final approach fix, «FFxx»): FAF_DIST before the threshold on the extended centre line (5,5 km-eq), its
 * altitude where the glide slope passes: the threshold's elevation + the threshold crossing height + FAF_DIST x tan(the
 * G/S angle), rounded up - an AT constraint (the glide slope is intercepted from below / level there);</li>
 * <li><b>IF</b> (intermediate fix, «CIxx»): IF_DIST before the threshold on the line, at the FAF's altitude or above - the
 * level segment that captures the localizer before the glide slope;</li>
 * <li><b>MAP</b>: the threshold itself (the plan's destination entry; the decision height is the category's);</li>
 * <li><b>Missed approach</b>: climb on the runway's course to MA_DIST past the threshold, at or above MA_H over it («MAxx»),
 * then back to the IF at its altitude (the return fix - the approach can be flown again);</li>
 * <li><b>Transitions</b>: from the airport's entry fixes (entries(): four, ENTRY_DIST north / east / south / west of the
 * airport's reference point, «ICAO» + N / E / S / W, at or above ENTRY_H over the airport) straight to the IF.</li>
 * </ul>
 * The altitudes are world Y (the liner's metres on its altimeter); the plan's constraint kinds are LinerFms' (C_AT /
 * C_ABOVE).
 */
public final class ApproachMath {

    /** Distances before the threshold (blocks): FAF 5,5 km-eq, IF 10 km-eq; the missed approach's climb point past it. */
    public static final double FAF_DIST = 550, IF_DIST = 1000, MA_DIST = 1200;
    /** Heights over the threshold: the missed approach's, the entry fixes' (blocks). */
    public static final int MA_H = 80, ENTRY_H = 110;
    /** The entry fixes this far from the airport's reference point (blocks, 20 km-eq). */
    public static final double ENTRY_DIST = 2000;
    /** The entry fixes' suffixes and bearings (deg). */
    public static final String[] ENTRY_SFX = {"N", "E", "S", "W"};
    public static final int[] ENTRY_BRG = {0, 90, 180, 270};
    /** Fix kinds. */
    public static final int F_TRANS = 0, F_IF = 1, F_FAF = 2, F_MAP = 3, F_MA = 4, F_RETURN = 5;
    /** The constraint kinds (LinerFms.C_*: AT, AT OR ABOVE). */
    public static final int C_AT = 1, C_ABOVE = 2;
    /** The world's top for a constraint. */
    public static final int ALT_MAX = 255;

    private ApproachMath() {
    }

    /** One fix of a procedure. */
    public static final class Fix {
        public String id = "";
        public double x, z;
        public int alt, cst = C_AT, kind;

        Fix(String id, double x, double z, int alt, int cst, int kind) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.alt = alt;
            this.cst = cst;
            this.kind = kind;
        }

        public Fix copy() {
            return new Fix(id, x, z, alt, cst, kind);
        }
    }

    /** An ILS approach of one runway end. */
    public static final class Approach {
        /** «ILS27L», the runway («27L»), the ILS station's key / ident / frequency / category, the G/S angle. */
        public String id = "", rwy = "", locKey = "", ils = "";
        public int freq, cat;
        public double angle = 3, course;
        /** The threshold and its elevation. */
        public double tx, tz;
        public int elev;
        /** IF, FAF (in order flown); the missed approach (MA climb, the return fix). */
        public final List<Fix> fixes = new ArrayList<Fix>(), missed = new ArrayList<Fix>();
        /** The entry fixes this approach can be joined from (each a transition straight to the IF). */
        public final List<Fix> trans = new ArrayList<Fix>();

        public Fix fix(int kind) {
            for (Fix f : fixes) {
                if (f.kind == kind) {
                    return f;
                }
            }
            for (Fix f : missed) {
                if (f.kind == kind) {
                    return f;
                }
            }
            return null;
        }

        /** The decision height for its category (blocks of radio height; CAT III: the autoland's 0). */
        public int dh() {
            return (int) Math.round(IlsCat.minHeight(cat));
        }
    }

    /** A point `along` blocks before the threshold (negative: past it) on the extended centre line of a landing course. */
    public static double[] onLine(double tx, double tz, double course, double before) {
        double c = Math.toRadians(course);
        return new double[] {tx - Math.sin(c) * before, tz + Math.cos(c) * before};
    }

    /** The glide slope's altitude `before` blocks before the threshold (rounded up to whole blocks). */
    public static int gsAlt(int elev, double angle, double before) {
        return (int) Math.ceil(elev + IlsCat.TCH_NOMINAL + before * Math.tan(Math.toRadians(angle)) - 1e-9);
    }

    static int clampAlt(int a) {
        return Math.max(1, Math.min(ALT_MAX, a));
    }

    /** The approach of a runway end (see the class comment). `entries`: the airport's entry fixes (null: none). */
    public static Approach ils(String rwy, double tx, double tz, int elev, double course, LinerIls.Station st, List<Fix> entries) {
        Approach a = new Approach();
        a.rwy = rwy;
        a.id = "ILS" + rwy;
        a.tx = tx;
        a.tz = tz;
        a.elev = elev;
        a.course = LinerIls.wrap360(course);
        if (st != null) {
            a.locKey = st.key();
            a.ils = st.ident;
            a.freq = st.freq;
            a.cat = st.category();
            a.angle = st.angle;
        }
        int faf = clampAlt(gsAlt(elev, a.angle, FAF_DIST));
        double[] pf = onLine(tx, tz, a.course, FAF_DIST), pi = onLine(tx, tz, a.course, IF_DIST), pm = onLine(tx, tz, a.course, -MA_DIST);
        Fix fi = new Fix("CI" + rwy, pi[0], pi[1], faf, C_ABOVE, F_IF);
        a.fixes.add(fi);
        a.fixes.add(new Fix("FF" + rwy, pf[0], pf[1], faf, C_AT, F_FAF));
        a.missed.add(new Fix("MA" + rwy, pm[0], pm[1], clampAlt(elev + MA_H), C_ABOVE, F_MA));
        a.missed.add(new Fix(fi.id, fi.x, fi.z, faf, C_AT, F_RETURN));
        if (entries != null) {
            for (Fix e : entries) {
                Fix t = e.copy();
                t.alt = clampAlt(Math.max(e.alt, faf + 15));
                t.cst = C_ABOVE;
                t.kind = F_TRANS;
                a.trans.add(t);
            }
        }
        return a;
    }

    /** The airport's entry fixes: ENTRY_DIST from (cx, cz) north / east / south / west, «ICAO» + N / E / S / W. */
    public static List<Fix> entries(String icao, double cx, double cz, int elev) {
        List<Fix> l = new ArrayList<Fix>();
        for (int i = 0; i < ENTRY_SFX.length; i++) {
            double b = Math.toRadians(ENTRY_BRG[i]);
            l.add(new Fix(icao + ENTRY_SFX[i], cx + Math.sin(b) * ENTRY_DIST, cz - Math.cos(b) * ENTRY_DIST, clampAlt(elev + ENTRY_H), C_ABOVE, F_TRANS));
        }
        return l;
    }

    /** The approach's path for drawing (the tower's map, the chart's plan view): the transition's fix (or null), IF, FAF, threshold, MA, return. */
    public static List<double[]> path(Approach a, Fix via) {
        List<double[]> l = new ArrayList<double[]>();
        if (via != null) {
            l.add(new double[] {via.x, via.z, via.alt});
        }
        for (Fix f : a.fixes) {
            l.add(new double[] {f.x, f.z, f.alt});
        }
        l.add(new double[] {a.tx, a.tz, a.elev});
        for (Fix f : a.missed) {
            l.add(new double[] {f.x, f.z, f.alt});
        }
        return l;
    }

    /** Is a point (x, z, y) on this approach's final - between the IF and the threshold, within `lat` of the line? */
    public static boolean onFinal(Approach a, double x, double z, double lat) {
        double c = Math.toRadians(a.course);
        double dx = x - a.tx, dz = z - a.tz;
        double along = dx * Math.sin(c) - dz * Math.cos(c), across = dx * Math.cos(c) + dz * Math.sin(c);
        return along <= 0 && -along <= IF_DIST + 50 && Math.abs(across) <= lat;
    }
}
