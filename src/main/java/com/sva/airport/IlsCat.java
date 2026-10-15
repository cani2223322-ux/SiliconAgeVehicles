package com.sva.airport;

import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.LinerIls;

/**
 * Airport wave B1 (plan-next §9, items 17-18): the ILS category a FULL station earns and its monitor - pure, the server
 * (block.AirportDataSVA) feeds it what it found in the world, the planner (PlannerMath) feeds it the ideal layout, the
 * self-test feeds it numbers. Scale: AptScale (1 block = 10 m-equivalent).
 * <p>
 * <b>Placement</b> (the windows below, CAT III / II / I): the localizer array on the extended centre line (LOC_LAT: 5 m-eq
 * for CAT III, 15 for CAT II) beyond the far end (LOC_BEYOND: 150..800 m-eq); the glide slope mast 120..180 m-eq off the
 * centre line (GS_OFFSET) and 150..450 m-eq past the threshold (GS_SETBACK) so the path crosses the threshold at 12..19
 * m-eq (TCH, IlsBeam.tch - the A320's 15 m). Outside the CAT I windows the station still works - as CAT I, with the issue.
 * <p>
 * <b>Monitor</b>: an «Аппаратная ILS» (the equipment shelter) beside each antenna. Without it the station is CAT I at best
 * AND cannot shut itself off - a bent beam stays on the air (that is why the real ones have one). With it the station
 * switches itself OFF (F_OFF, the receivers' red flags, the ECAM «ILS ОТКАЗ») when the localizer's course is out of
 * tolerance (the array more than LOC_LAT_MAX off the centre line, a bend over LOC_TRIP) or its array is broken, and drops
 * the glide slope alone (F_GS_FAIL, a LOC-only approach) on a G/S bend over GS_TRIP or a broken mast. No power: off.
 * <p>
 * <b>Critical / sensitive areas</b> (rectangles in the runway's frame - critical(), sensitive()): the LOC's in front of
 * the array toward the runway (300 x 120 m-eq; sensitive 600 x 200), the G/S's beside the runway in front of the mast
 * toward the approach. Every solid block or vehicle (OCC_VEHICLE each) / creature (1) in a CRITICAL area bends the beam
 * (BEND_LOC / BEND_GS deg each, oscillating - IlsBeam.bend) and caps the category at I; in a SENSITIVE one (outside the
 * critical) it caps it at II without a bend.
 * <p>
 * <b>Flight inspection</b> (FlightCheck): until a calibration approach the station is limited to CAT I (F_UNCAL); after
 * one its category is at most the calibrated one.
 */
public final class IlsCat {

    /** The localizer array's lateral offset (blocks): CAT III, CAT II, the most a monitor tolerates. */
    public static final double LOC_LAT_III = 0.5, LOC_LAT_II = 1.5, LOC_LAT_MAX = 4;
    /** Beyond the far end (blocks): CAT III window, the CAT II / I window. */
    public static final double LOC_BEYOND_MIN = 15, LOC_BEYOND_MAX = 80, LOC_BEYOND_LO = 5, LOC_BEYOND_HI = 150;
    /** The G/S mast's offset from the centre line (blocks): CAT III, CAT II, CAT I windows. */
    public static final double GS_OFF_III_LO = 12, GS_OFF_III_HI = 18, GS_OFF_II_LO = 10, GS_OFF_II_HI = 20, GS_OFF_I_LO = 6, GS_OFF_I_HI = 30;
    /** The G/S mast past the threshold (blocks): the good window, the usable one. */
    public static final double GS_SET_LO = 15, GS_SET_HI = 45, GS_SET_MIN = 5, GS_SET_MAX = 80;
    /** The threshold crossing height (blocks = 10 m-eq): CAT III, CAT II windows (the rest CAT I). */
    public static final double TCH_III_LO = 1.2, TCH_III_HI = 1.9, TCH_II_LO = 1.0, TCH_II_HI = 2.2, TCH_NOMINAL = 1.5;
    /** Critical areas (blocks): the LOC's length toward the runway and half width; the G/S's length toward the approach. */
    public static final double LOC_CRIT_LEN = 30, LOC_CRIT_HALF = 6, GS_CRIT_LEN = 25, GS_CRIT_PAST = 2, GS_CRIT_OUT = 3;
    /** Sensitive areas (blocks). */
    public static final double LOC_SENS_LEN = 60, LOC_SENS_HALF = 10, GS_SENS_LEN = 40, GS_SENS_OUT = 8;
    /** The scan's height over the surface (blocks). */
    public static final int CRIT_HEIGHT = 5;
    /** Occupancy: a vehicle counts as this many blocks. */
    public static final int OCC_VEHICLE = 3;
    /** A bend per occupant (deg), the monitor's trips (deg). */
    public static final double BEND_LOC = 0.08, BEND_GS = 0.04, LOC_TRIP = 0.4, GS_TRIP = 0.2;

    /** Issues (lang sva.apt.issue.KEY, hint sva.apt.hint.KEY). */
    public static final int I_NO_POWER = 0, I_LOC_BROKEN = 1, I_GS_BROKEN = 2, I_NO_GS = 3, I_LOC_LAT = 4, I_LOC_BEYOND = 5, I_GS_OFFSET = 6,
            I_GS_SETBACK = 7, I_TCH = 8, I_LOC_MON = 9, I_GS_MON = 10, I_LOC_CRIT = 11, I_GS_CRIT = 12, I_LOC_SENS = 13, I_GS_SENS = 14,
            I_UNCAL = 15, I_CALIB = 16, I_LOC_TRIP = 17, I_GS_TRIP = 18, I_FREQ = 19, I_UNPAIRED = 20, I_NO_RWY = 21, ISSUES = 22;
    public static final String[] ISSUE_KEYS = {"noPower", "locBroken", "gsBroken", "noGs", "locLat", "locBeyond", "gsOffset", "gsSetback", "tch",
        "locMon", "gsMon", "locCrit", "gsCrit", "locSens", "gsSens", "uncal", "calib", "locTrip", "gsTrip", "freq", "unpaired", "noRwy"};

    private IlsCat() {
    }

    /** What the station is made of now. */
    public static final class Setup {
        public boolean power = true, locIntact = true, gsPresent = true, gsIntact = true, locMonitor = true, gsMonitor = true;
        /** The array's lateral offset and distance beyond the far end, the mast's setback and offset (blocks), the angle (deg). */
        public double locLat, locBeyond = 30, gsSetback = 30, gsOffset = 15, angle = 3;
        /** Occupants of the critical / sensitive areas (blocks + OCC_VEHICLE per vehicle + creatures). */
        public int locCrit, gsCrit, locSens, gsSens;
        /** The flight inspection's category (0 none yet). */
        public int calibCat;
    }

    /** The category, the status bits (LinerIls.F_*), the bends' amplitudes, the issues (I_*). */
    public static final class Result {
        public int cat, flags;
        public double bendLoc, bendGs, tch;
        public final List<Integer> issues = new ArrayList<Integer>();
        /** The category the placement, the monitors and the areas allow (before the flight inspection's limit). */
        public int placementCat;

        void issue(int i) {
            Integer k = Integer.valueOf(i);
            if (!issues.contains(k)) {
                issues.add(k);
            }
        }

        public boolean has(int i) {
            return issues.contains(Integer.valueOf(i));
        }
    }

    /** The category by the placement alone (3..1) - the windows above. */
    public static int placement(Setup u, Result r) {
        int c = 3;
        double lat = Math.abs(u.locLat);
        if (lat > LOC_LAT_III) {
            c = Math.min(c, lat > LOC_LAT_II ? 1 : 2);
            r.issue(I_LOC_LAT);
        }
        if (u.locBeyond < LOC_BEYOND_MIN || u.locBeyond > LOC_BEYOND_MAX) {
            c = Math.min(c, u.locBeyond < LOC_BEYOND_LO || u.locBeyond > LOC_BEYOND_HI ? 1 : 2);
            r.issue(I_LOC_BEYOND);
        }
        if (u.gsPresent) {
            double off = Math.abs(u.gsOffset);
            if (off < GS_OFF_III_LO || off > GS_OFF_III_HI) {
                c = Math.min(c, off >= GS_OFF_II_LO && off <= GS_OFF_II_HI ? 2 : 1);
                r.issue(I_GS_OFFSET);
            }
            if (u.gsSetback < GS_SET_LO || u.gsSetback > GS_SET_HI) {
                c = Math.min(c, 1);
                r.issue(I_GS_SETBACK);
            }
            r.tch = IlsBeam.tch(u.gsSetback, u.gsOffset, u.angle);
            if (r.tch < TCH_III_LO || r.tch > TCH_III_HI) {
                c = Math.min(c, r.tch >= TCH_II_LO && r.tch <= TCH_II_HI ? 2 : 1);
                r.issue(I_TCH);
            }
        } else {
            c = Math.min(c, 1);                                     // a localizer alone: a LOC approach (no autoland)
            r.issue(I_NO_GS);
        }
        return c;
    }

    /** Everything: the monitor's decisions, the bends, the category (see the class comment). */
    public static Result compute(Setup u) {
        Result r = new Result();
        int c = placement(u, r);
        // the monitors
        if (!u.locMonitor) {
            c = Math.min(c, 1);
            r.issue(I_LOC_MON);
        }
        if (u.gsPresent && !u.gsMonitor) {
            c = Math.min(c, 1);
            r.issue(I_GS_MON);
        }
        // the areas: critical - a bend and CAT I; sensitive - CAT II
        r.bendLoc = Math.min(1.5, Math.max(0, u.locCrit) * BEND_LOC);
        r.bendGs = u.gsPresent ? Math.min(1.0, Math.max(0, u.gsCrit) * BEND_GS) : 0;
        if (u.locCrit > 0) {
            c = Math.min(c, 1);
            r.issue(I_LOC_CRIT);
        } else if (u.locSens > 0) {
            c = Math.min(c, 2);
            r.issue(I_LOC_SENS);
        }
        if (u.gsPresent && u.gsCrit > 0) {
            c = Math.min(c, 1);
            r.issue(I_GS_CRIT);
        } else if (u.gsPresent && u.gsSens > 0) {
            c = Math.min(c, 2);
            r.issue(I_GS_SENS);
        }
        // the monitor's shut-downs (only a monitored antenna can shut itself off)
        int f = 0;
        if (!u.gsPresent) {
            f |= LinerIls.F_NO_GS;
        }
        if (!u.power) {
            f |= LinerIls.F_NO_POWER | LinerIls.F_LOC_FAIL | LinerIls.F_GS_FAIL;
            r.issue(I_NO_POWER);
        }
        if (!u.locIntact) {
            f |= LinerIls.F_OFF | LinerIls.F_LOC_FAIL | LinerIls.F_GS_FAIL;
            r.issue(I_LOC_BROKEN);
        }
        if (u.locMonitor && (Math.abs(u.locLat) > LOC_LAT_MAX || r.bendLoc >= LOC_TRIP)) {
            f |= LinerIls.F_OFF | LinerIls.F_LOC_FAIL | LinerIls.F_GS_FAIL;
            r.issue(I_LOC_TRIP);
        }
        if (u.gsPresent && !u.gsIntact) {
            f |= LinerIls.F_GS_FAIL;
            r.issue(I_GS_BROKEN);
        }
        if (u.gsPresent && u.gsMonitor && r.bendGs >= GS_TRIP) {
            f |= LinerIls.F_GS_FAIL;
            r.issue(I_GS_TRIP);
        }
        if ((f & LinerIls.F_GS_FAIL) != 0) {
            c = Math.min(c, 1);                                     // the G/S off: a LOC approach at best
        }
        r.placementCat = c;
        if (u.calibCat <= 0) {
            c = Math.min(c, 1);
            f |= LinerIls.F_UNCAL;
            r.issue(I_UNCAL);
        } else if (u.calibCat < c) {
            c = u.calibCat;
            r.issue(I_CALIB);
        }
        if ((f & (LinerIls.F_OFF | LinerIls.F_NO_POWER)) != 0) {
            c = 0;
        }
        r.cat = c;
        r.flags = f;
        return r;
    }

    // ---------------------------------------------------------------- the areas (the runway's frame)

    /** A rectangle in the runway's frame: along from the threshold [a0, a1], across (+ right) [c0, c1]. */
    public static final class Area {
        public final double a0, a1, c0, c1;

        public Area(double a0, double a1, double c0, double c1) {
            this.a0 = Math.min(a0, a1);
            this.a1 = Math.max(a0, a1);
            this.c0 = Math.min(c0, c1);
            this.c1 = Math.max(c0, c1);
        }

        public boolean contains(double along, double cross) {
            return along >= a0 && along <= a1 && cross >= c0 && cross <= c1;
        }
    }

    /** The localizer's critical area: in front of the array (at along la, across lc) toward the runway. */
    public static Area locCritical(double la, double lc) {
        return new Area(la - LOC_CRIT_LEN, la - 1.5, lc - LOC_CRIT_HALF, lc + LOC_CRIT_HALF);
    }

    public static Area locSensitive(double la, double lc) {
        return new Area(la - LOC_SENS_LEN, la - 1.5, lc - LOC_SENS_HALF, lc + LOC_SENS_HALF);
    }

    /**
     * The glide slope's critical area: beside the runway (from one block off its edge out past the mast) from the mast
     * (along ga, across gc) GS_CRIT_LEN toward the approach. The runway itself is never in it (a landing aircraft does not
     * bend its own beam).
     */
    public static Area gsCritical(double ga, double gc, double width) {
        double edge = width / 2.0 + 1, s = gc >= 0 ? 1 : -1;
        return new Area(ga - GS_CRIT_LEN, ga + GS_CRIT_PAST, s * edge, gc + s * GS_CRIT_OUT);
    }

    public static Area gsSensitive(double ga, double gc, double width) {
        double edge = width / 2.0 + 1, s = gc >= 0 ? 1 : -1;
        return new Area(ga - GS_SENS_LEN, ga + GS_CRIT_PAST + 2, s * edge, gc + s * GS_SENS_OUT);
    }

    // ---------------------------------------------------------------- minimums

    /** The decision height (blocks of radio height) the autopilot lets go at for a category (0: none - CAT III autoland). */
    public static final double[] MIN_H = {30, 20, 12, 0};

    public static double minHeight(int cat) {
        return MIN_H[Math.max(0, Math.min(3, cat))];
    }

    /** "CAT III" / "CAT II" / "CAT I" / "ОТКЛ"-key (the caller's language for 0). */
    public static String catText(int cat) {
        return cat >= 3 ? "CAT III" : cat == 2 ? "CAT II" : cat == 1 ? "CAT I" : "-";
    }
}
