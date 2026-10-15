package com.sva.airport;

import java.util.ArrayList;
import java.util.List;

/**
 * Airport wave B2 (plan-next §9, item 32): the AUTOMATIC tower - the rules a tower without a player in its controller seat
 * runs every DECIDE_TICKS (config airport.autoTower; airport.controllerOverride: a player in the seat takes over). Pure: it
 * gets the traffic of the runway in use and its own memory, gives the uplinks to send.
 * <ul>
 * <li><b>One aircraft on the runway</b>: the runway is OCCUPIED by anything on it (within its width + RWY_MARGIN, low:
 * under ON_RWY_H over it) or by an aircraft cleared for take-off / line-up that has not left yet.</li>
 * <li><b>Landing priority</b>: an arrival that asked to land is cleared when it is within LAND_WINDOW of the threshold, the
 * runway is free, no other landing clearance stands and the last movement (a take-off or a landing) is at least the
 * minimum interval ago (airport.autoInterval); else ОЖИДАЙТЕ, once.</li>
 * <li><b>Departures</b>: ready at the holding point - ЗАНИМАЙТЕ ПОЛОСУ and ВЗЛЁТ РАЗРЕШАЮ when the runway is free, no arrival
 * is closer than ARR_PROTECT (in distance) or ARR_PROTECT_TICKS (in time) to the threshold, no landing clearance stands
 * and the interval has passed; else ОЖИДАЙТЕ, once.</li>
 * <li><b>Taxi</b>: granted at once (the route is the server's - the «Огонь рулёжки» graph); ОЖИДАЙТЕ ПЕРЕД ПОЛОСОЙ to a
 * departure that reached the holding point while it may not go.</li>
 * <li><b>Go-around</b>: an arrival on the final (in the approach's corridor) below GA_HEIGHT (airport.goAroundHeight)
 * over the threshold while the runway is occupied by another - УХОДИТЕ НА ВТОРОЙ КРУГ, once; its landing clearance is gone.</li>
 * </ul>
 */
public final class AutoTower {

    /** Requests. */
    public static final int R_NONE = 0, R_TAXI = 1, R_TAKEOFF = 2, R_LAND = 3;
    /** The tower's view of an aircraft (its clearance). */
    public static final int S_NONE = 0, S_TAXI = 1, S_LINEUP = 2, S_TAKEOFF = 3, S_LAND = 4, S_GOAROUND = 5, S_HOLD = 6;
    public static final String[] STATE_KEYS = {"none", "taxi", "lineup", "takeoff", "land", "goAround", "hold"};
    /** Geometry (blocks): the runway's margin, the height of «on it», the landing window, an arrival protecting the runway. */
    public static final double RWY_MARGIN = 2, ON_RWY_H = 6, LAND_WINDOW = 2000, ARR_PROTECT = 700, CORRIDOR = 0.15, CORRIDOR_MIN = 25;
    /** An arrival this soon (ticks) at the threshold protects the runway too; the decisions' period. */
    public static final int ARR_PROTECT_TICKS = 400, DECIDE_TICKS = 20;
    /** A take-off cleared aircraft leaves the runway's occupancy this high over it; a cleared one that never went: this long. */
    public static final double AIRBORNE_H = 12;
    public static final int CLEAR_STALE = 1800;

    private AutoTower() {
    }

    /** One aircraft as the tower sees it. */
    public static final class Ac {
        public int id;
        public String call = "";
        /** Position, the ground speed (blocks a tick), on the ground. */
        public double x, y, z, gs;
        public boolean ground;
        public int req = R_NONE, st = S_NONE;
        /** When its clearance was given / its request came (ticks); a STBY told for this request. */
        public long stAt, reqAt;
        public boolean stbyTold;
        /** At the holding point (the server's taxi graph). */
        public boolean atHold;
    }

    /** The runway in use: the runway, its landing direction. */
    public static final class Rwy {
        public final IlsPairing.Rwy r;
        public final int dir;

        public Rwy(IlsPairing.Rwy r, int dir) {
            this.r = r;
            this.dir = dir;
        }

        public String name() {
            return r.name(dir);
        }
    }

    /** The tower's memory: the last movement's time. */
    public static final class Mem {
        public long lastMove = -1000000;
    }

    /** The numbers (the config's). */
    public static final class Params {
        public int interval = 400;
        public double gaHeight = 25;
    }

    /** One uplink to send. */
    public static final class Act {
        public final int id, type;
        public final String rwy;

        Act(int id, int type, String rwy) {
            this.id = id;
            this.type = type;
            this.rwy = rwy;
        }
    }

    /** The aircraft is on the runway: within its width + margin, between its ends + margin, low over it. */
    public static boolean onRunway(Rwy w, Ac a) {
        return w.r.onRunway(a.x, a.z, RWY_MARGIN) && a.y - w.r.elev < ON_RWY_H;
    }

    /** Distance along the final to the threshold (blocks, + before it) and off the centre line. */
    public static double[] final_(Rwy w, Ac a) {
        double[] l = w.r.local(w.dir, a.x, a.z);
        return new double[] {-l[0], l[1]};
    }

    /** In the approach's corridor: before the threshold, within CORRIDOR x the distance (CORRIDOR_MIN at least) of the line. */
    public static boolean onFinal(Rwy w, Ac a) {
        double[] f = final_(w, a);
        return f[0] > -5 && f[0] < LAND_WINDOW && Math.abs(f[1]) <= Math.max(CORRIDOR_MIN, f[0] * CORRIDOR) && !a.ground;
    }

    /** Ticks for an arrival to reach the threshold (its ground speed; slow: MAX). */
    public static double eta(Rwy w, Ac a) {
        double d = final_(w, a)[0];
        return a.gs > 0.05 ? d / a.gs : Double.MAX_VALUE;
    }

    /** Who keeps the runway occupied (null: free), other than `except`. */
    public static Ac occupant(Rwy w, List<Ac> t, Ac except, long now) {
        for (Ac a : t) {
            if (a == except) {
                continue;
            }
            boolean on = onRunway(w, a);
            boolean cleared = (a.st == S_TAKEOFF || a.st == S_LINEUP) && a.y - w.r.elev < AIRBORNE_H && now - a.stAt < CLEAR_STALE;
            if (on || cleared) {
                return a;
            }
        }
        return null;
    }

    /** The decisions of one period (see the class comment); the aircraft's states are updated. */
    public static List<Act> decide(List<Ac> traffic, Rwy w, Mem m, long now, Params p) {
        List<Act> out = new ArrayList<Act>();
        if (w == null) {
            return out;
        }
        String rn = w.name();
        // a take-off / landing finished: the clearance is spent, the movement time noted
        for (Ac a : traffic) {
            if (a.st == S_TAKEOFF && !a.ground && a.y - w.r.elev >= AIRBORNE_H) {
                a.st = S_NONE;
                a.req = R_NONE;
                m.lastMove = now;
            } else if (a.st == S_LAND && a.ground && !onRunway(w, a)) {
                a.st = S_NONE;
                a.req = R_NONE;
                m.lastMove = now;
            } else if (a.st == S_LAND && a.ground && a.gs < 0.15) {
                a.st = S_NONE;                                       // stopped on the runway: still occupies it, no clearance
                a.req = R_NONE;
                m.lastMove = now;
            } else if (a.st == S_GOAROUND && (a.y - w.r.elev > p.gaHeight * 2 || a.ground)) {
                a.st = S_NONE;
            }
        }
        // go-arounds first
        for (Ac a : traffic) {
            if ((a.st == S_LAND || a.req == R_LAND) && onFinal(w, a) && a.y - w.r.elev < p.gaHeight && occupant(w, traffic, a, now) != null) {
                a.st = S_GOAROUND;
                a.stAt = now;
                a.req = R_NONE;
                a.stbyTold = false;
                out.add(new Act(a.id, Cpdlc.U_GOAROUND, rn));
            }
        }
        boolean intervalOk = now - m.lastMove >= p.interval;
        Ac landing = null;
        for (Ac a : traffic) {
            if (a.st == S_LAND) {
                landing = a;
            }
        }
        // landings: the nearest asking arrival
        Ac next = null;
        for (Ac a : traffic) {
            if (a.req == R_LAND && a.st != S_LAND && a.st != S_GOAROUND && !a.ground) {
                double d = final_(w, a)[0];
                if (d > -5 && d < LAND_WINDOW && (next == null || d < final_(w, next)[0])) {
                    next = a;
                }
            }
        }
        if (next != null) {
            if (landing == null && occupant(w, traffic, next, now) == null && intervalOk) {
                next.st = S_LAND;
                next.stAt = now;
                next.stbyTold = false;
                landing = next;
                out.add(new Act(next.id, Cpdlc.U_LAND, rn));
            } else if (!next.stbyTold) {
                next.stbyTold = true;
                out.add(new Act(next.id, Cpdlc.U_STBY, rn));
            }
        }
        // any other asking arrival far out: a standby once
        for (Ac a : traffic) {
            if (a.req == R_LAND && a != next && a.st == S_NONE && !a.stbyTold) {
                a.stbyTold = true;
                out.add(new Act(a.id, Cpdlc.U_STBY, rn));
            }
        }
        // departures: the one waiting longest
        boolean arrivalNear = false;
        for (Ac a : traffic) {
            if (!a.ground && (a.req == R_LAND || a.st == S_LAND) && onFinal(w, a) && (final_(w, a)[0] < ARR_PROTECT || eta(w, a) < ARR_PROTECT_TICKS)) {
                arrivalNear = true;
            }
        }
        Ac dep = null;
        for (Ac a : traffic) {
            if (a.req == R_TAKEOFF && a.ground && a.st != S_TAKEOFF && (dep == null || a.reqAt < dep.reqAt)) {
                dep = a;
            }
        }
        if (dep != null) {
            if (landing == null && !arrivalNear && occupant(w, traffic, dep, now) == null && intervalOk) {
                dep.st = S_TAKEOFF;
                dep.stAt = now;
                dep.stbyTold = false;
                out.add(new Act(dep.id, Cpdlc.U_LINEUP, rn));
                out.add(new Act(dep.id, Cpdlc.U_TAKEOFF, rn));
            } else if (!dep.stbyTold) {
                dep.stbyTold = true;
                dep.st = S_HOLD;
                out.add(new Act(dep.id, Cpdlc.U_HOLDSHORT, rn));
            }
        }
        // taxi: at once
        for (Ac a : traffic) {
            if (a.req == R_TAXI && a.st != S_TAXI) {
                a.st = S_TAXI;
                a.stAt = now;
                a.req = R_NONE;
                out.add(new Act(a.id, Cpdlc.U_TAXI, rn));
            }
        }
        return out;
    }
}
