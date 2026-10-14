package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Liner wave 5 (plan-next §7, mock-ups veh_liner_14 MCDU, veh_liner_11 ND): the SA-320's FMGC - the data both MCDUs
 * share (each MCDU only keeps its own page and scratchpad, LinerMcdu): INIT (from / to / alternate, flight number, cost
 * index, cruise altitude, the IRS confirmation), the fuel and weights, the flight plan with its constraints and the
 * temporary plan (TMPY), the PERF data (V-speeds, FLEX, thrust reduction / acceleration, the approach's QNH / wind / VAPP
 * / minimums / landing configuration, the go-around), the vertical profile (T/C, T/D, the descent path), the
 * predictions (times, fuel) and the flight phase. Pure logic, server-authoritative; the self-test drives it directly.
 * <p>
 * <b>The plan</b>: a list of entries - the origin (K_ORIG, the departure runway's threshold), waypoints, discontinuities
 * («РАЗРЫВ ПЛАНА»), the destination (K_DEST, the landing runway's threshold) and the missed approach point (K_MISSED,
 * MISSED_DIST straight ahead of the runway, MISSED_H over it). `active` is the TO waypoint (the leg flown runs from the
 * entry before it). Lateral edits (insert, delete, DIR TO) go to the temporary plan, drawn yellow, until ВСТАВИТЬ (or
 * СТЕРЕТЬ); vertical revisions (the constraints) act on the plan shown.
 * <p>
 * <b>NAV</b> flies it through the addon's route machinery - bridge() makes the RouteMath plan (the vehicle's own, the one
 * NAV / the ND / the driver's client already use) from the FROM entry on up to the next discontinuity; nav() keeps it
 * the same tick by tick and turns RouteMath's sequencing back into the FMS' active entry.
 * <p>
 * <b>The vertical profile</b> (profile(), every few ticks and after each change): the climb at CLB_ANGLE from where the
 * aircraft is (the origin's elevation on the ground) under the AT / BELOW constraints up to the cruise altitude - T/C;
 * the descent path built backward from PATH_H over the destination's threshold at DES_ANGLE through the constraints up
 * to the cruise altitude - T/D. Managed DES starts at T/D and follows that path (LinerAfs: the path's altitude here,
 * pathAlt). Speeds by the phase from the cost index; fuel by BURN_* a block (climb, cruise, descent).
 */
public final class LinerFms {

    // ---------------------------------------------------------------- numbers

    /** Entries at most in a plan. */
    public static final int MAX = 24;
    /** Entry kinds. */
    public static final int K_WPT = 0, K_ORIG = 1, K_DEST = 2, K_DISC = 3, K_MISSED = 4, K_TP = 5;
    /** Constraint kinds: none, AT, AT OR ABOVE (+), AT OR BELOW (-). */
    public static final int C_NONE = 0, C_AT = 1, C_ABOVE = 2, C_BELOW = 3;
    /** The cruise altitude's limits (Y = m; the flight ceiling is FlightMath.CEILING). */
    public static final int CRZ_MIN = 20, CRZ_MAX = (int) FlightMath.CEILING - 5;
    /** Constraint altitudes / speeds' limits. */
    public static final int ALT_MIN = 1, ALT_MAX = 255, SPD_MIN = 60, SPD_MAX = 175, CI_MAX = 100;
    /** The profile's angles (deg): climb, descent path. */
    public static final double CLB_ANGLE = 5, DES_ANGLE = 3;
    /**
     * The descent path's height over the destination's threshold (blocks): a few over the ILS beam (which meets the runway
     * at the threshold here), so the path flies onto the glide slope from just above it and G/S captures on the way.
     */
    public static final double PATH_H = 5;
    /** Fuel a block (mB) by the phase: climb, cruise, descent (both engines; the burn model of LinerSystems' FF). */
    public static final double BURN_CLB = 0.50, BURN_CRZ = 0.31, BURN_DES = 0.06;
    /** Default taxi fuel / final reserve (mB), the alternate's extra share. */
    public static final int TAXI = 200, RESERVE = 2000;
    /** The missed approach point: straight ahead of the runway this far, this high over it. */
    public static final double MISSED_DIST = 1500, MISSED_H = 150;
    /** «ТОРМОЗИТЕ» (DECELERATE) on the FMA: descending this close to the destination without the approach phase. */
    public static final double DECEL_DIST = 1500;
    /** No temperature entered. */
    public static final int NO_TEMP = -999;
    /** The flight phases (PROG's title, the PERF page opened by the key). */
    public static final int PH_PRE = 0, PH_TO = 1, PH_CLB = 2, PH_CRZ = 3, PH_DES = 4, PH_APPR = 5, PH_GA = 6, PH_DONE = 7;
    public static final String[] PH_KEYS = {"pre", "to", "clb", "crz", "des", "appr", "ga", "done"};
    /** The FLEX temperature's limits (deg C). */
    public static final int FLEX_MIN = 16, FLEX_MAX = 70;
    /** The empty mass (t) and a rider's (t): ZFW's default. */
    public static final double EMPTY_T = LinerAfs.MASS0 / 1000.0, RIDER_T = 0.08;
    /** A full set of tanks (mB) is this many tonnes (LinerLogicSVA.air: 2600 kg per 48 000 mB). */
    public static final double FUEL_T = 2.6 / 48000.0;

    // ---------------------------------------------------------------- an entry

    public static final class Wp {
        public String id = "";
        public double x, z;
        public int kind = K_WPT;
        public int cst = C_NONE, alt, spd;
        /** Airports: the elevation, the runway, its ILS (key); a waypoint flown over (OVFY). */
        public int elev = 64, hdg;
        public String rwy = "", ils = "";
        public boolean ovfy;
        /** Predictions (profile()): along the plan (blocks), altitude, speed (km/h), ticks from now, fuel left (mB). */
        public double s, pAlt, pSpd, pTicks, pFuel;

        public Wp copy() {
            Wp w = new Wp();
            w.id = id;
            w.x = x;
            w.z = z;
            w.kind = kind;
            w.cst = cst;
            w.alt = alt;
            w.spd = spd;
            w.elev = elev;
            w.hdg = hdg;
            w.rwy = rwy;
            w.ils = ils;
            w.ovfy = ovfy;
            w.s = s;
            w.pAlt = pAlt;
            w.pSpd = pSpd;
            w.pTicks = pTicks;
            w.pFuel = pFuel;
            return w;
        }

        public boolean point() {
            return kind != K_DISC;
        }

        void write(NBTTagCompound t) {
            t.setString("Id", id);
            t.setDouble("X", x);
            t.setDouble("Z", z);
            t.setByte("K", (byte) kind);
            t.setByte("C", (byte) cst);
            t.setShort("A", (short) alt);
            t.setShort("S", (short) spd);
            t.setShort("E", (short) elev);
            t.setShort("H", (short) hdg);
            t.setString("R", rwy);
            t.setString("I", ils);
            t.setBoolean("O", ovfy);
        }

        static Wp read(NBTTagCompound t) {
            Wp w = new Wp();
            w.id = t.getString("Id");
            w.x = t.getDouble("X");
            w.z = t.getDouble("Z");
            w.kind = Math.max(0, Math.min(K_TP, t.getByte("K")));
            w.cst = Math.max(0, Math.min(C_BELOW, t.getByte("C")));
            w.alt = clamp(t.getShort("A"), 0, ALT_MAX);
            w.spd = clamp(t.getShort("S"), 0, SPD_MAX);
            w.elev = t.getShort("E");
            w.hdg = t.getShort("H");
            w.rwy = t.getString("R");
            w.ils = t.getString("I");
            w.ovfy = t.getBoolean("O");
            if (Double.isNaN(w.x) || Double.isNaN(w.z)) {
                w.x = 0;
                w.z = 0;
            }
            return w;
        }
    }

    /** A waypoint at (x, z). */
    public static Wp wpt(String id, double x, double z) {
        Wp w = new Wp();
        w.id = id;
        w.x = x;
        w.z = z;
        return w;
    }

    static Wp disc() {
        Wp w = new Wp();
        w.kind = K_DISC;
        return w;
    }

    // ---------------------------------------------------------------- state: INIT / fuel / PERF

    public String from = "", to = "", altn = "", flt = "";
    /** Cost index (-1 none), cruise altitude (Y, -1 none), cruise temperature (NO_TEMP none). */
    public int ci = -1, crz = -1, crzTemp = NO_TEMP;
    /** IRS INIT confirmed («ВЫСТАВИТЬ»), its reference airport. */
    public boolean irsDone;
    public String irsRef = "";
    /** Zero-fuel weight (tenths of t, -1 none), block fuel (mB, -1 none), taxi, final reserve (mB). */
    public int zfw10 = -1, block = -1, taxi = TAXI, reserve = RESERVE;
    /** V1 / VR / V2 (km/h, 0 none), FLEX (deg, 0 none), the take-off flaps (0 none), the THS (tenths of deg, + nose up). */
    public int v1, vr, v2, flex, toFlaps, ths10 = 10;
    /** Thrust reduction / acceleration heights over the runway (m; -1: the config's), the go-around's. */
    public int thrRed = -1, acc = -1, gaThrRed = -1, gaAcc = -1;
    /** The approach: QNH (0 none), temperature, wind (deg / km/h, -1 none), VAPP (km/h, 0: computed), DH / MDA (-1 none), landing flaps 3 / 4. */
    public int qnh, apprTemp = NO_TEMP, windDir = -1, windSpd, vapp, dh = -1, mda = -1, ldgConf = 4;
    /** The approach phase activated (managed speed to the approach's). */
    public boolean apprActive;
    /** The take-off weight (t) the V-speeds were entered for (-1 none): a change - «ПРОВЕРЬ ДАННЫЕ ВЗЛЁТА». */
    public double towAtV = -1;

    // ---------------------------------------------------------------- state: the plan

    public final List<Wp> pts = new ArrayList<Wp>();
    public int active;
    /** The temporary plan (null none) and its active entry. */
    public List<Wp> tmp;
    public int tmpActive;
    /** Changes counter (every edit / sequencing): the bridge and the ND data are rebuilt on a change. */
    public int rev;
    /** The flight phase now (tick()). */
    public int phase = PH_PRE;

    // the profile (profile())
    /** Along-plan distances (blocks from the plan's first entry): the aircraft, T/C, T/D, the destination (NaN: none). */
    public double sAc, sTc = Double.NaN, sTd = Double.NaN, sDest = Double.NaN;
    /** T/C / T/D positions (NaN none). */
    public double tcX = Double.NaN, tcZ = Double.NaN, tdX = Double.NaN, tdZ = Double.NaN;
    /** The descent path's altitude where the aircraft is (NaN: before T/D / no path). */
    public double pathNow = Double.NaN;
    /** Predicted: fuel at the destination (mB), its ticks from now, the gross weight now (kg). */
    public double destFuel = Double.NaN, destTicks = Double.NaN, gwNow;

    // ---------------------------------------------------------------- the plan's views

    /** The plan shown / edited: the temporary one when there is one. */
    public List<Wp> shown() {
        return tmp != null ? tmp : pts;
    }

    public int shownActive() {
        return tmp != null ? tmpActive : active;
    }

    public boolean hasPlan() {
        return !pts.isEmpty();
    }

    /** The destination's index in a list (-1 none). */
    public static int destIndex(List<Wp> l) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).kind == K_DEST) {
                return i;
            }
        }
        return -1;
    }

    public Wp dest() {
        int i = destIndex(pts);
        return i < 0 ? null : pts.get(i);
    }

    public Wp origin() {
        return !pts.isEmpty() && pts.get(0).kind == K_ORIG ? pts.get(0) : null;
    }

    /** The active (TO) waypoint, null none (a discontinuity / flown through). */
    public Wp activeWp() {
        return active >= 0 && active < pts.size() && pts.get(active).point() ? pts.get(active) : null;
    }

    static List<Wp> copy(List<Wp> l) {
        List<Wp> c = new ArrayList<Wp>();
        for (Wp w : l) {
            c.add(w.copy());
        }
        return c;
    }

    // ---------------------------------------------------------------- INIT

    /**
     * FROM / TO (INIT A): a new plan - the origin (its first runway), a discontinuity, the destination (its first runway)
     * and its missed approach point; the phase back to preflight, the TMPY dropped.
     */
    public void setFromTo(NavDb.Airport f, NavDb.Airport t) {
        from = f.ident;
        to = t.ident;
        pts.clear();
        tmp = null;
        Wp o = airportWp(f, "", K_ORIG);
        pts.add(o);
        pts.add(disc());
        Wp d = airportWp(t, "", K_DEST);
        pts.add(d);
        pts.add(missed(d));
        active = 1;
        phase = PH_PRE;
        apprActive = false;
        irsRef = f.ident;
        rev++;
    }

    /** The destination's runway changed (LAT REV of the destination): the threshold, its ILS, the missed approach. */
    public boolean setDestRwy(NavDb.Airport a, String rwyId) {
        int i = destIndex(pts);
        NavDb.Rwy r = a.rwy(rwyId);
        if (i < 0 || r == null || !r.id.equals(rwyId)) {
            return false;
        }
        Wp d = airportWp(a, rwyId, K_DEST);
        pts.set(i, d);
        if (i + 1 < pts.size() && pts.get(i + 1).kind == K_MISSED) {
            pts.set(i + 1, missed(d));
        }
        rev++;
        return true;
    }

    /** An airport's entry at a runway's threshold ("" the first runway; none: the airport's point). */
    public static Wp airportWp(NavDb.Airport a, String rwyId, int kind) {
        NavDb.Rwy r = a.rwy(rwyId);
        Wp w = new Wp();
        w.kind = kind;
        w.rwy = r == null ? "" : r.id;
        w.id = a.ident + w.rwy;
        w.x = r == null ? a.x : r.x;
        w.z = r == null ? a.z : r.z;
        w.elev = r == null ? a.elev : r.elev;
        w.ils = r == null ? "" : r.ilsKey;
        w.hdg = r == null ? 0 : r.hdg;
        return w;
    }

    /** The missed approach point of a destination entry: MISSED_DIST along its runway's course, MISSED_H over it. */
    static Wp missed(Wp d) {
        double c = Math.toRadians(d.hdg);
        Wp m = wpt("УХОД", d.x + Math.sin(c) * MISSED_DIST, d.z - Math.cos(c) * MISSED_DIST);
        m.kind = K_MISSED;
        m.cst = C_AT;
        m.alt = clamp((int) Math.round(d.elev + MISSED_H), ALT_MIN, ALT_MAX);
        m.elev = d.elev;
        m.hdg = d.hdg;
        return m;
    }

    /** The cost index used (50 when none was entered). */
    public int ciUsed() {
        return ci < 0 ? 50 : ci;
    }

    /** Managed speeds by the cost index (km/h): climb, cruise, descent. */
    public static int clbSpd(int ci) {
        return (int) Math.round(105 + ci * 0.10);
    }

    public static int crzSpd(int ci) {
        return (int) Math.round(115 + ci * 0.20);
    }

    public static int desSpd(int ci) {
        return (int) Math.round(115 + ci * 0.15);
    }

    // ---------------------------------------------------------------- the lateral edits (into the TMPY)

    /** The temporary plan, made from the active one on the first edit. */
    List<Wp> edit() {
        if (tmp == null) {
            tmp = copy(pts);
            tmpActive = active;
        }
        return tmp;
    }

    /**
     * Inserts `w` before entry i of the plan shown (a discontinuity there is replaced by it); not before the active leg's
     * FROM entry, not past the plan's end, not over MAX. @return done
     */
    public boolean insertAt(int i, Wp w) {
        List<Wp> l = shown();
        int act = shownActive();
        if (i < Math.max(1, act) || i > l.size() || l.size() >= MAX && (i >= l.size() || l.get(i).kind != K_DISC)) {
            return false;
        }
        if (i < l.size() && (l.get(i).kind == K_ORIG)) {
            return false;
        }
        l = edit();
        if (i < l.size() && l.get(i).kind == K_DISC) {
            l.set(i, w);
        } else {
            l.add(i, w);
        }
        tidy(l);
        rev++;
        return true;
    }

    /**
     * Deletes entry i (CLR): a waypoint leaves a discontinuity in its place (the A320's), a discontinuity goes (the legs
     * join). The origin, the destination and the missed approach stay; entries already flown can't go. @return done
     */
    public boolean deleteAt(int i) {
        List<Wp> l = shown();
        int act = shownActive();
        if (i < 0 || i >= l.size() || i < act) {
            return false;
        }
        int k = l.get(i).kind;
        if (k == K_ORIG || k == K_DEST || k == K_MISSED) {
            return false;
        }
        l = edit();
        if (k == K_DISC) {
            l.remove(i);
        } else {
            l.set(i, disc());
        }
        tidy(l);
        rev++;
        return true;
    }

    /**
     * DIR TO entry i of the plan shown (a waypoint ahead): the TMPY starts at the present position (a turn point «ТП»)
     * and goes straight to it; the entries before it go. @return done
     */
    public boolean dirTo(int i, double x, double z) {
        List<Wp> l = shown();
        if (i < 0 || i >= l.size() || !l.get(i).point() || l.get(i).kind == K_ORIG) {
            return false;
        }
        l = edit();
        List<Wp> n = new ArrayList<Wp>();
        Wp tp = wpt("ТП", x, z);
        tp.kind = K_TP;
        n.add(tp);
        for (int k = i; k < l.size(); k++) {
            n.add(l.get(k));
        }
        l.clear();
        l.addAll(n);
        tmpActive = 1;
        tidy(l);
        rev++;
        return true;
    }

    /** DIR TO a waypoint not in the plan: ТП, it, a discontinuity, then the plan from the active entry on. */
    public boolean dirToNew(Wp w, double x, double z) {
        List<Wp> l = edit();
        int act = tmpActive;
        List<Wp> n = new ArrayList<Wp>();
        Wp tp = wpt("ТП", x, z);
        tp.kind = K_TP;
        n.add(tp);
        n.add(w);
        n.add(disc());
        for (int k = Math.max(0, act); k < l.size(); k++) {
            n.add(l.get(k));
        }
        while (n.size() > MAX) {
            n.remove(n.size() - 1);
        }
        l.clear();
        l.addAll(n);
        tmpActive = 1;
        tidy(l);
        rev++;
        return true;
    }

    /** ВСТАВИТЬ: the temporary plan becomes the active one. */
    public void tmpInsert() {
        if (tmp == null) {
            return;
        }
        pts.clear();
        pts.addAll(tmp);
        active = Math.max(0, Math.min(pts.size(), tmpActive));
        tmp = null;
        rev++;
    }

    /** СТЕРЕТЬ: the temporary plan dropped. */
    public void tmpErase() {
        if (tmp != null) {
            tmp = null;
            rev++;
        }
    }

    /** No two discontinuities in a row, none at the very end. */
    static void tidy(List<Wp> l) {
        for (int i = l.size() - 1; i > 0; i--) {
            if (l.get(i).kind == K_DISC && l.get(i - 1).kind == K_DISC) {
                l.remove(i);
            }
        }
        while (!l.isEmpty() && l.get(l.size() - 1).kind == K_DISC) {
            l.remove(l.size() - 1);
        }
    }

    /**
     * A vertical revision on entry i of the plan shown: the speed constraint (km/h, 0 off; -1 unchanged), the altitude one
     * (kind C_*, Y; kind -1 unchanged). Discontinuities and the origin take none. @return done
     */
    public boolean setCst(int i, int spd, int kind, int alt) {
        List<Wp> l = shown();
        if (i < 0 || i >= l.size() || !l.get(i).point() || l.get(i).kind == K_ORIG || l.get(i).kind == K_DEST) {
            return false;
        }
        Wp w = l.get(i);
        if (spd >= 0) {
            w.spd = spd == 0 ? 0 : clamp(spd, SPD_MIN, SPD_MAX);
        }
        if (kind >= 0) {
            w.cst = kind;
            w.alt = kind == C_NONE ? 0 : clamp(alt, ALT_MIN, ALT_MAX);
        }
        rev++;
        return true;
    }

    /** The vehicle map's «Маршрут» set on the liner: the plan becomes the origin (if any), those points, the destination (if any). */
    public void importMap(RouteMath.Plan p) {
        Wp o = origin(), d = dest();
        Wp m = null;
        int di = destIndex(pts);
        if (di >= 0 && di + 1 < pts.size() && pts.get(di + 1).kind == K_MISSED) {
            m = pts.get(di + 1);
        }
        pts.clear();
        tmp = null;
        if (o != null) {
            pts.add(o);
        }
        int first = pts.size();
        for (int i = 0; i < p.size(); i++) {
            int[] q = p.pts.get(i);
            pts.add(wpt("Т" + (i + 1), q[0] + 0.5, q[1] + 0.5));
        }
        if (d != null) {
            pts.add(d);
            if (m != null) {
                pts.add(m);
            }
        }
        active = Math.min(pts.size(), first + Math.max(0, p.active));
        rev++;
    }

    /** A new waypoint's name for a coordinate entry: «Т» + the next free number. */
    public String nextName() {
        int n = 1;
        boolean taken = true;
        while (taken && n < 1000) {
            taken = false;
            for (List<Wp> l : lists()) {
                for (Wp w : l) {
                    taken |= ("Т" + n).equals(w.id);
                }
            }
            if (taken) {
                n++;
            }
        }
        return "Т" + n;
    }

    private List<List<Wp>> lists() {
        List<List<Wp>> r = new ArrayList<List<Wp>>();
        r.add(pts);
        if (tmp != null) {
            r.add(tmp);
        }
        return r;
    }

    /** The active entry moved on (NAV sequenced a waypoint): `to` - the new TO entry. */
    public void sequence(int to) {
        active = Math.max(0, Math.min(pts.size(), to));
        if (tmp != null && tmpActive < active) {
            tmpActive = Math.min(tmp.size(), active);
        }
        rev++;
    }

    // ---------------------------------------------------------------- NAV's bridge

    /** The RouteMath plan of the active plan now and which FMS entry each of its points is (map). */
    public static final class Bridge {
        public final RouteMath.Plan plan = new RouteMath.Plan();
        public final List<Integer> map = new ArrayList<Integer>();
    }

    /**
     * The route NAV flies: from the active leg's FROM entry (its TO with RouteMath's «direct» when the FROM is a
     * discontinuity) up to the next discontinuity, at most RouteMath.MAX points; each point's height its altitude
     * constraint, else the cruise altitude. Empty: no route (NAV reverts to HDG).
     */
    public Bridge bridge() {
        Bridge b = new Bridge();
        int n = pts.size();
        if (active < 0 || active >= n || !pts.get(active).point()) {
            return b;
        }
        int start = active - 1;
        boolean fromLeg = start >= 0 && pts.get(start).point();
        if (!fromLeg) {
            start = active;
        }
        for (int i = start; i < n && b.plan.size() < RouteMath.MAX; i++) {
            Wp w = pts.get(i);
            if (w.kind == K_DISC) {
                break;
            }
            int h = w.cst != C_NONE ? w.alt : crz > 0 ? crz : RouteMath.ALT_DEFAULT;
            b.plan.add((int) Math.floor(w.x), (int) Math.floor(w.z), h);
            b.map.add(Integer.valueOf(i));
        }
        b.plan.active = fromLeg ? 1 : 0;
        return b;
    }

    private Bridge bridged;
    private int bridgedRev = -1;

    /** nav()'s result: the vehicle's route (replaced: a new object for the vehicle and its driver), RouteMath's guidance. */
    public static final class Nav {
        public RouteMath.Plan plan;
        public boolean replaced;
        public RouteMath.Guide g;
    }

    /**
     * One tick of NAV's bridge (the server; the self-test's simulation): `cur` the vehicle's route now. A new bridge when the
     * plan changed (or the route is not ours); RouteMath's guidance at (x, z) flying `hdg` at `speed` turning `rate`; a
     * point it sequenced moves the FMS' active entry (and the bridge on).
     */
    public Nav nav(RouteMath.Plan cur, double x, double z, double hdg, double speed, double rate) {
        Nav r = new Nav();
        if (bridged == null || bridgedRev != rev || cur == null || !cur.samePoints(bridged.plan)) {
            bridged = bridge();
            bridgedRev = rev;
            r.replaced = true;
            cur = bridged.plan.copy();
        }
        r.plan = cur;
        if (cur.isEmpty()) {
            return r;
        }
        r.g = RouteMath.guide(cur, x, z, hdg, speed, rate);
        // whoever moved the route's active point on (here, or the physics' own guidance the same tick): the FMS follows
        if (r.g != null && cur.active > bridged.plan.active) {
            int k = cur.active;
            int to = k < bridged.map.size() ? bridged.map.get(k).intValue() : bridged.map.get(bridged.map.size() - 1).intValue() + 1;
            if (to > active) {
                sequence(to);
                bridged = bridge();
                bridgedRev = rev;
                r.plan = bridged.plan.copy();
                r.replaced = true;
                r.g = r.plan.isEmpty() ? null : RouteMath.guide(r.plan, x, z, hdg, speed, rate);
            }
        }
        return r;
    }

    // ---------------------------------------------------------------- the profile and the predictions

    /** The along-plan position of each entry of a list (blocks; discontinuities joined straight). */
    public static double[] along(List<Wp> l) {
        double[] s = new double[l.size()];
        double acc = 0;
        Wp last = null;
        for (int i = 0; i < l.size(); i++) {
            Wp w = l.get(i);
            if (w.point()) {
                if (last != null) {
                    acc += Math.sqrt(sq(w.x - last.x) + sq(w.z - last.z));
                }
                last = w;
            }
            s[i] = acc;
        }
        return s;
    }

    /** The aircraft's environment for the profile and the predictions. */
    public static final class Ctx {
        public double x, z, y, gs;
        public boolean ground = true;
        /** Fuel on board (mB), the gross weight (kg), the consumption multiplier (config). */
        public double fob, gw = LinerAfs.MASS0 + 1500, burn = 1;
    }

    /** The vertical profile and the predictions (see the class comment) for the aircraft in `c`. */
    public void profile(Ctx c) {
        List<Wp> l = pts;
        double[] s = along(l);
        for (int i = 0; i < l.size(); i++) {
            l.get(i).s = s[i];
        }
        gwNow = c.gw;
        int di = destIndex(l);
        sDest = di >= 0 ? s[di] : Double.NaN;
        // the aircraft along the plan: before the active entry by its distance to it (on the ground at the origin: 0)
        Wp a = activeWp();
        if (a != null && !(c.ground && phase <= PH_TO)) {
            sAc = Math.max(0, s[active] - Math.sqrt(sq(c.x - a.x) + sq(c.z - a.z)));
        } else if (a == null && active >= l.size() && !l.isEmpty()) {
            sAc = s[l.size() - 1];
        } else {
            sAc = active > 0 && active <= l.size() ? s[Math.max(0, active - 1)] : 0;
        }
        double crzAlt = crz > 0 ? crz : Double.NaN;
        Wp o = origin();
        double y0 = c.ground ? (o != null ? o.elev : c.y) : c.y;
        // the climb: forward from the aircraft under AT / BELOW constraints
        sTc = Double.NaN;
        double gClb = Math.tan(Math.toRadians(CLB_ANGLE)), gDes = Math.tan(Math.toRadians(DES_ANGLE));
        double[] clb = new double[l.size()];
        if (!Double.isNaN(crzAlt) && y0 < crzAlt - 2 && phase <= PH_CLB) {
            double alt = y0, from = sAc;
            for (int i = Math.max(0, active); i < l.size(); i++) {
                Wp w = l.get(i);
                if (!w.point()) {
                    clb[i] = alt;
                    continue;
                }
                double seg = Math.max(0, s[i] - from);
                double ceil = crzAlt;
                if (w.cst == C_AT || w.cst == C_BELOW) {
                    ceil = Math.min(ceil, w.alt);                   // levelled under the constraint until the point (ALT CST)
                }
                double next = alt + seg * gClb;
                if (ceil >= crzAlt && next >= crzAlt) {
                    sTc = from + Math.max(0, crzAlt - alt) / gClb;
                    next = crzAlt;
                } else {
                    next = Math.min(next, ceil);
                }
                clb[i] = next;
                alt = next;
                from = s[i];
                if (!Double.isNaN(sTc)) {
                    for (int k = i + 1; k < l.size(); k++) {
                        clb[k] = crzAlt;
                    }
                    break;
                }
            }
        } else {
            for (int i = 0; i < l.size(); i++) {
                clb[i] = Double.isNaN(crzAlt) ? c.y : crzAlt;
            }
        }
        // the descent path: backward from the destination's threshold + PATH_H through the constraints
        sTd = Double.NaN;
        pathNow = Double.NaN;
        double[] path = new double[l.size()];
        java.util.Arrays.fill(path, Double.NaN);
        if (di >= 0 && !Double.isNaN(crzAlt)) {
            double[] unc = pathUncapped(l, s, di);
            for (int i = 0; i <= di; i++) {
                path[i] = Math.min(crzAlt, unc[i]);
            }
            for (int i = di - 1; i >= 0 && Double.isNaN(sTd); i--) {
                if (unc[i] >= crzAlt) {
                    // the path meets the cruise altitude on the leg i -> i + 1
                    int j = i + 1;
                    sTd = Math.max(s[i], Math.min(s[j], s[j] - (crzAlt - unc[j]) / gDes));
                }
            }
            if (Double.isNaN(sTd)) {
                sTd = 0;                                            // the path never reaches the cruise: descend from the start
            }
            if (!Double.isNaN(sTc) && sTc > sTd) {
                sTc = sTd = (sTc + sTd) / 2;                        // no cruise: T/C and T/D meet
            }
            if (sAc >= sTd) {
                pathNow = pathAt(l, s, unc, di, crzAlt, sAc);
            }
        }
        double[] tc = pointAt(l, s, sTc), td = pointAt(l, s, sTd);
        tcX = tc[0];
        tcZ = tc[1];
        tdX = td[0];
        tdZ = td[1];
        // predictions per entry: altitude, speed, time, fuel
        int ciU = ciUsed();
        double vClb = clbSpd(ciU) / 72.0, vCrz = crzSpd(ciU) / 72.0, vDes = desSpd(ciU) / 72.0;
        double t = 0, fuel = c.fob - (c.ground && phase <= PH_TO ? taxi : 0), at = sAc;
        destFuel = Double.NaN;
        destTicks = Double.NaN;
        for (int i = 0; i < l.size(); i++) {
            Wp w = l.get(i);
            if (i < active || !w.point()) {
                w.pTicks = i < active ? -1 : t;
                w.pFuel = fuel;
                w.pAlt = clb[i];
                continue;
            }
            double end = s[i];
            // walk the segment by phase
            double seg0 = at;
            while (seg0 < end - 1e-6) {
                double seg1 = end;
                double v, b;
                if (!Double.isNaN(sTc) && seg0 < sTc) {
                    seg1 = Math.min(seg1, sTc);
                    v = vClb;
                    b = BURN_CLB;
                } else if (!Double.isNaN(sTd) && seg0 >= sTd) {
                    v = vDes;
                    b = BURN_DES;
                } else {
                    if (!Double.isNaN(sTd)) {
                        seg1 = Math.min(seg1, sTd);
                    }
                    v = vCrz;
                    b = BURN_CRZ;
                }
                if (seg1 <= seg0) {
                    seg1 = end;
                }
                double d = seg1 - seg0;
                t += d / Math.max(0.3, v);
                fuel -= d * b * c.burn;
                seg0 = seg1;
            }
            at = Math.max(at, end);
            w.pTicks = t;
            w.pFuel = fuel;
            double pa = clb[i];
            if (!Double.isNaN(path[i])) {
                pa = Math.min(pa, path[i]);
            }
            w.pAlt = w.kind == K_DEST ? w.elev : pa;
            w.pSpd = (!Double.isNaN(sTc) && end <= sTc ? clbSpd(ciU) : !Double.isNaN(sTd) && end >= sTd ? desSpd(ciU) : crzSpd(ciU));
            if (w.spd > 0) {
                w.pSpd = Math.min(w.pSpd, w.spd);
            }
            if (i == di) {
                destFuel = fuel;
                destTicks = t;
                w.pSpd = vappKmh(c.gw);                             // the approach: VAPP at the threshold
            }
        }
    }

    /**
     * The descent path's altitude at each entry up to the destination (index di), not capped by the cruise altitude:
     * PATH_H over the threshold, then backward DES_ANGLE up, an AT / AT OR BELOW constraint holding it down, an AT OR
     * ABOVE one up.
     */
    static double[] pathUncapped(List<Wp> l, double[] s, int di) {
        double g = Math.tan(Math.toRadians(DES_ANGLE));
        double[] u = new double[l.size()];
        java.util.Arrays.fill(u, Double.NaN);
        double p = l.get(di).elev + PATH_H;
        u[di] = p;
        for (int i = di - 1; i >= 0; i--) {
            p = p + (s[i + 1] - s[i]) * g;
            Wp w = l.get(i);
            if (w.point() && w.kind != K_ORIG) {
                if (w.cst == C_AT || w.cst == C_BELOW) {
                    p = Math.min(p, w.alt);
                } else if (w.cst == C_ABOVE) {
                    p = Math.max(p, w.alt);
                }
            }
            u[i] = p;
        }
        return u;
    }

    /**
     * The descent path's altitude at along-distance sv: up from the next entry at DES_ANGLE (a level part after an AT /
     * BELOW constraint behind), never over the cruise altitude.
     */
    static double pathAt(List<Wp> l, double[] s, double[] unc, int di, double crzAlt, double sv) {
        if (sv >= s[di]) {
            return unc[di];
        }
        double g = Math.tan(Math.toRadians(DES_ANGLE));
        for (int j = 0; j <= di; j++) {
            if (!l.get(j).point() || s[j] < sv) {
                continue;
            }
            double v = unc[j] + (s[j] - sv) * g;
            for (int i = j - 1; i >= 0; i--) {
                Wp w = l.get(i);
                if (w.point()) {
                    if (w.cst == C_AT || w.cst == C_BELOW) {
                        v = Math.min(v, Math.max(unc[j], unc[i]));
                    }
                    break;
                }
            }
            return Math.min(crzAlt, v);
        }
        return Double.NaN;
    }

    /** The point at along-distance sv on the plan (NaN for NaN / off the plan). */
    static double[] pointAt(List<Wp> l, double[] s, double sv) {
        if (Double.isNaN(sv)) {
            return new double[] {Double.NaN, Double.NaN};
        }
        Wp last = null;
        double ls = 0;
        for (int i = 0; i < l.size(); i++) {
            Wp w = l.get(i);
            if (!w.point()) {
                continue;
            }
            if (last != null && sv <= s[i] + 1e-6) {
                double len = s[i] - ls;
                double k = len < 1e-6 ? 1 : (sv - ls) / len;
                k = Math.max(0, Math.min(1, k));
                return new double[] {last.x + (w.x - last.x) * k, last.z + (w.z - last.z) * k};
            }
            last = w;
            ls = s[i];
        }
        return new double[] {Double.NaN, Double.NaN};
    }

    /** T/D passed (the aircraft along the plan at or past it). */
    public boolean tdPassed() {
        return !Double.isNaN(sTd) && sAc >= sTd - 2;
    }

    /** Distance from the aircraft to the destination along the plan (blocks; NaN none). */
    public double distToDest() {
        return Double.isNaN(sDest) ? Double.NaN : Math.max(0, sDest - sAc);
    }

    /**
     * The next altitude constraint ahead for a climb (AT / AT OR BELOW - a ceiling) or a descent (AT / AT OR ABOVE - a
     * floor), NaN none (LinerAfs: ALT CST).
     */
    public double cstAhead(boolean climb) {
        for (int i = Math.max(0, active); i < pts.size(); i++) {
            Wp w = pts.get(i);
            if (!w.point() || w.kind == K_MISSED) {
                continue;
            }
            if (w.cst == C_AT || climb && w.cst == C_BELOW || !climb && w.cst == C_ABOVE) {
                return w.alt;
            }
        }
        return Double.NaN;
    }

    // ---------------------------------------------------------------- PERF

    /** The take-off weight now (t): ZFW (entered or the empty mass + riders) + block fuel - taxi. */
    public double tow(int riders, double fob) {
        double z = zfw10 > 0 ? zfw10 / 10.0 : EMPTY_T + riders * RIDER_T;
        double f = block >= 0 ? block : fob;
        return z + Math.max(0, f - taxi) * FUEL_T;
    }

    /**
     * «ВЫЧИСЛИТЬ»: V1 / VR / V2 (km/h) for the take-off weight (t), the flaps and the runway's length (blocks; 0
     * unknown): the configuration's lift-off and stall (HighLiftMath) through LinerAfs.vspeeds; a short runway takes V1
     * down (the stop must fit).
     */
    public static int[] computeV(double towT, int flaps, int rwyLen) {
        int f = Math.max(1, Math.min(3, flaps));
        PlaneParams p = HighLiftMath.params(HighLiftMath.LINER, VehicleType.LINER.plane, f, true, false, false);
        double[] v = LinerAfs.vspeeds(p.liftOff, p.stall, towT * 1000);
        double k = rwyLen > 0 ? 0.88 + 0.07 * Math.min(1, rwyLen / 400.0) : 0.95;
        int vR = (int) Math.round(v[1] * 72);
        int vOne = Math.min(vR, (int) Math.round(v[1] * k * 72));
        int vTwo = Math.max(vR + 1, (int) Math.round(v[2] * 72));
        return new int[] {vOne, vR, vTwo};
    }

    /** The FLEX temperature's thrust share at the FLX detent (idle..TOGA): 45 = the old fixed FLX, hotter - less. */
    public static double flexShare(int temp) {
        return Math.max(LinerAfs.DET_CL, Math.min(1, LinerAfs.DET_FLX - (temp - 45) * 0.005));
    }

    /** VLS (km/h) of the landing configuration for a mass (kg). */
    public double vlsLdg(double mass) {
        PlaneParams p = HighLiftMath.params(HighLiftMath.LINER, VehicleType.LINER.plane, ldgConf, true, false, false);
        return LinerAfs.vls(p.stall, mass) * 72;
    }

    /** VAPP (km/h): entered, else VLS + a third of the head wind (at least 5). */
    public int vappKmh(double mass) {
        if (vapp > 0) {
            return vapp;
        }
        double add = 5;
        if (windDir >= 0 && windSpd > 0) {
            add = Math.max(5, Math.min(15, windSpd / 3.0));
        }
        return (int) Math.round(vlsLdg(mass) + add);
    }

    /** V-speeds entered (all three, in order). */
    public boolean vEntered() {
        return v1 > 0 && vr > 0 && v2 > 0 && v1 <= vr && vr <= v2;
    }

    /** The trip fuel from the origin to the destination (mB), the time (ticks) - the INIT B / FUEL PRED figures. */
    public double[] trip(double burn) {
        List<Wp> l = pts;
        int di = destIndex(l);
        if (di < 0) {
            return new double[] {Double.NaN, Double.NaN};
        }
        double[] s = along(l);
        double dist = s[di];
        double crzAlt = crz > 0 ? crz : 150;
        Wp o = origin();
        double y0 = o != null ? o.elev : 64;
        double dClb = Math.min(dist, Math.max(0, crzAlt - y0) / Math.tan(Math.toRadians(CLB_ANGLE)));
        double dDes = Math.min(dist - dClb, Math.max(0, crzAlt - l.get(di).elev - PATH_H) / Math.tan(Math.toRadians(DES_ANGLE)));
        double dCrz = Math.max(0, dist - dClb - dDes);
        int ciU = ciUsed();
        double fuel = (dClb * BURN_CLB + dCrz * BURN_CRZ + dDes * BURN_DES) * burn;
        double ticks = dClb / (clbSpd(ciU) / 72.0) + dCrz / (crzSpd(ciU) / 72.0) + dDes / (desSpd(ciU) / 72.0);
        return new double[] {fuel, ticks};
    }

    /** The alternate's fuel (mB): from the destination to it at cruise burn + 10 %; 0 without one. */
    public double altnFuel(NavDb db, double burn) {
        Wp d = dest();
        NavDb.Airport a = db == null || altn.length() == 0 ? null : db.airport(altn);
        if (d == null || a == null) {
            return 0;
        }
        return Math.sqrt(sq(a.x - d.x) + sq(a.z - d.z)) * BURN_CRZ * 1.1 * burn;
    }

    /** The highest / optimum cruise altitude for a gross weight (kg): the ceiling's fade less the weight's toll. */
    public static int maxAlt(double gw) {
        return (int) Math.round(Math.max(CRZ_MIN, CRZ_MAX - LinerAfs.massShare(gw) * 25));
    }

    public static int optAlt(double gw) {
        return Math.max(CRZ_MIN, maxAlt(gw) - 15);
    }

    // ---------------------------------------------------------------- the tick

    /**
     * One server tick (LinerSystems, before the AFS): the phase from the AFS' modes and the aircraft, the profile now and then,
     * the IRS INIT's reset, and the AFS' inputs from the FMS (the constraints ahead, the descent path, the V-speeds, FLEX,
     * the managed speeds, the approach, the minimums, the plan's ILS).
     */
    public void tick(LinerSystems L, LinerAfs.Air a, Ctx c, int tick) {
        LinerAfs F = L.afs;
        // the phase
        if (a.ground) {
            if (F.ver == LinerAfs.V_SRS) {
                phase = PH_TO;
            } else if (phase >= PH_CLB && phase != PH_DONE) {
                phase = PH_DONE;
                apprActive = false;
            } else if (phase == PH_TO && a.speed < 0.3) {
                phase = PH_PRE;
            }
        } else {
            if (F.lat == LinerAfs.L_GA_TRK || phase == PH_GA && F.ver == LinerAfs.V_SRS) {
                if (phase != PH_GA) {
                    apprActive = false;
                }
                phase = PH_GA;
            } else if (F.ver == LinerAfs.V_SRS && phase <= PH_TO) {
                phase = PH_TO;
            } else if (apprActive) {
                phase = PH_APPR;
            } else if (phase == PH_DES || tdPassed() && (F.ver == LinerAfs.V_DES || F.ver == LinerAfs.V_OP_DES || F.ver == LinerAfs.V_EXP_DES)) {
                phase = PH_DES;
            } else if (crz > 0 && a.y >= crz - 3 && phase != PH_GA) {
                phase = PH_CRZ;
            } else if (phase <= PH_TO || phase == PH_GA) {
                phase = PH_CLB;
            }
        }
        if (tick % 5 == 0 || profRev != rev) {
            profRev = rev;
            profile(c);
        } else {
            // between the full runs: the aircraft along the active leg and the path there
            Wp w = activeWp();
            if (w != null && !a.ground) {
                sAc = Math.max(0, w.s - Math.sqrt(sq(a.x - w.x) + sq(a.z - w.z)));
            }
        }
        // the AFS' inputs
        double fcuY = F.indicatedToY(F.alt);
        a.routeAlt = hasPlan() ? cstAhead(fcuY > a.y) : a.routeAlt;
        a.pathAlt = phase == PH_DES || phase == PH_CRZ || phase == PH_CLB ? pathNowAt() : Double.NaN;
        a.tdPassed = tdPassed();
        a.vEntered = vEntered();
        a.v1In = v1 / 72.0;
        a.vrIn = vr / 72.0;
        a.v2In = v2 / 72.0;
        a.flex = flex;
        int ciU = ciUsed();
        a.clbSpd = clbSpd(ciU) / 72.0;
        a.crzSpd = crzSpd(ciU) / 72.0;
        a.desSpd = desSpd(ciU) / 72.0;
        a.apprActive = apprActive;
        a.vapp = vappKmh(a.mass) / 72.0;
        a.dh = dh;
        a.mda = mda;
        Wp d = dest();
        a.ilsPlan = d == null ? "" : d.ils;
        if (thrRed > 0 || acc > 0) {
            a.accelH = acc > 0 ? acc : a.accelH;
        }
        if (phase == PH_GA && gaAcc > 0) {
            a.accelH = gaAcc;
        }
        // the FMA's message: DECELERATE close in without the approach phase; T/D passed with the FCU altitude not set lower
        a.fmsMsg = LinerAfs.MSG_NONE;
        double toDest = distToDest();
        if (!a.ground && !apprActive && !Double.isNaN(toDest) && toDest < DECEL_DIST && (phase == PH_DES || phase == PH_CRZ)) {
            a.fmsMsg = LinerAfs.MSG_DECEL;
        } else if (!a.ground && phase == PH_CRZ && tdPassed() && fcuY >= a.y - 2) {
            a.fmsMsg = LinerAfs.MSG_FCU_ALT;
        }
    }

    private int profRev = -1;

    /** The descent path's altitude at the aircraft now (NaN before T/D). */
    double pathNowAt() {
        int di = destIndex(pts);
        if (Double.isNaN(sTd) || sAc < sTd || di < 0 || crz <= 0) {
            return Double.NaN;
        }
        double[] s = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            s[i] = pts.get(i).s;
        }
        return pathAt(pts, s, pathUncapped(pts, s, di), di, crz, sAc);
    }

    // ---------------------------------------------------------------- the IRS INIT gate

    /** May the INS finish its alignment: the config's automatic confirmation, or IRS INIT confirmed. */
    public static boolean irsGate(boolean autoInit, boolean confirmed) {
        return autoInit || confirmed;
    }

    /** The alignment's length for the INS' state machine: held (never finishing) while the gate is shut. */
    public static int alignTotal(int total, boolean gate) {
        return gate ? total : Integer.MAX_VALUE;
    }

    // ---------------------------------------------------------------- the ND's data

    /**
     * The ND's picture of the plan (the server sends it to the pilots when it changes): "active;tcX,tcZ;tdX,tdZ;tmpy?;" then
     * the entries from the FROM one "id,x,z,kind,cst,alt|..." (the TMPY's ones after a '#').
     */
    public String ndText() {
        StringBuilder b = new StringBuilder(256);
        int from = Math.max(0, active - 1);
        b.append(active - from).append(';').append(i(tcX)).append(',').append(i(tcZ)).append(';').append(i(tdX)).append(',').append(i(tdZ)).append(';');
        appendList(b, pts, from);
        if (tmp != null) {
            b.append('#');
            appendList(b, tmp, Math.max(0, tmpActive - 1));
        }
        return b.toString();
    }

    private static void appendList(StringBuilder b, List<Wp> l, int from) {
        boolean first = true;
        for (int k = from; k < l.size(); k++) {
            Wp w = l.get(k);
            if (!first) {
                b.append('|');
            }
            first = false;
            b.append(w.kind == K_DISC ? "-" : w.id).append(',').append(i(w.x)).append(',').append(i(w.z)).append(',').append(w.kind).append(',')
                    .append(w.cst).append(',').append(w.alt);
        }
    }

    static int i(double v) {
        return Double.isNaN(v) ? Integer.MIN_VALUE : (int) Math.floor(v);
    }

    /** The ND's plan parsed (client). */
    public static final class NdView {
        /** The active entry's index in pts (TO), T/C, T/D (NaN none). */
        public int active;
        public double tcX = Double.NaN, tcZ = Double.NaN, tdX = Double.NaN, tdZ = Double.NaN;
        public final List<Wp> pts = new ArrayList<Wp>(), tmp = new ArrayList<Wp>();
    }

    public static NdView parseNd(String s) {
        if (s == null || s.length() == 0) {
            return null;
        }
        try {
            NdView v = new NdView();
            String[] h = s.split(";", 4);
            if (h.length < 4) {
                return null;
            }
            v.active = Integer.parseInt(h[0]);
            String[] tc = h[1].split(","), td = h[2].split(",");
            v.tcX = d(tc[0]);
            v.tcZ = d(tc[1]);
            v.tdX = d(td[0]);
            v.tdZ = d(td[1]);
            String body = h[3];
            int hash = body.indexOf('#');
            parseList(hash < 0 ? body : body.substring(0, hash), v.pts);
            if (hash >= 0) {
                parseList(body.substring(hash + 1), v.tmp);
            }
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void parseList(String s, List<Wp> out) {
        if (s.length() == 0) {
            return;
        }
        for (String e : s.split("\\|")) {
            String[] f = e.split(",");
            if (f.length < 6) {
                continue;
            }
            Wp w = new Wp();
            w.id = f[0];
            w.x = d(f[1]) + 0.5;
            w.z = d(f[2]) + 0.5;
            w.kind = Integer.parseInt(f[3]);
            w.cst = Integer.parseInt(f[4]);
            w.alt = Integer.parseInt(f[5]);
            if ("-".equals(w.id) && w.kind == K_DISC) {
                w.id = "";
            }
            out.add(w);
        }
    }

    private static double d(String s) {
        int v = Integer.parseInt(s.trim());
        return v == Integer.MIN_VALUE ? Double.NaN : v;
    }

    // ---------------------------------------------------------------- save

    public void writeNBT(NBTTagCompound t) {
        t.setString("From", from);
        t.setString("To", to);
        t.setString("Altn", altn);
        t.setString("Flt", flt);
        t.setIntArray("Init", new int[] {ci, crz, crzTemp, irsDone ? 1 : 0, zfw10, block, taxi, reserve});
        t.setString("IrsRef", irsRef);
        t.setIntArray("Perf", new int[] {v1, vr, v2, flex, toFlaps, ths10, thrRed, acc, gaThrRed, gaAcc, qnh, apprTemp, windDir, windSpd, vapp, dh, mda,
                ldgConf, apprActive ? 1 : 0, (int) Math.round(towAtV * 100), phase});
        t.setTag("Plan", list(pts));
        t.setInteger("Active", active);
        if (tmp != null) {
            t.setTag("Tmpy", list(tmp));
            t.setInteger("TmpyActive", tmpActive);
        }
    }

    private static NBTTagList list(List<Wp> l) {
        NBTTagList n = new NBTTagList();
        for (Wp w : l) {
            NBTTagCompound e = new NBTTagCompound();
            w.write(e);
            n.appendTag(e);
        }
        return n;
    }

    public void readNBT(NBTTagCompound t) {
        from = t.getString("From");
        to = t.getString("To");
        altn = t.getString("Altn");
        flt = t.getString("Flt");
        int[] in = t.getIntArray("Init");
        if (in.length == 8) {
            ci = clamp(in[0], -1, CI_MAX);
            crz = in[1] < 0 ? -1 : clamp(in[1], CRZ_MIN, CRZ_MAX);
            crzTemp = in[2];
            irsDone = in[3] != 0;
            zfw10 = in[4];
            block = in[5];
            taxi = Math.max(0, in[6]);
            reserve = Math.max(0, in[7]);
        }
        irsRef = t.getString("IrsRef");
        int[] p = t.getIntArray("Perf");
        if (p.length == 21) {
            v1 = p[0];
            vr = p[1];
            v2 = p[2];
            flex = p[3];
            toFlaps = p[4];
            ths10 = p[5];
            thrRed = p[6];
            acc = p[7];
            gaThrRed = p[8];
            gaAcc = p[9];
            qnh = p[10];
            apprTemp = p[11];
            windDir = p[12];
            windSpd = p[13];
            vapp = p[14];
            dh = p[15];
            mda = p[16];
            ldgConf = p[17] == 3 ? 3 : 4;
            apprActive = p[18] != 0;
            towAtV = p[19] / 100.0;
            phase = clamp(p[20], 0, PH_DONE);
        }
        pts.clear();
        readList(t.getTagList("Plan", 10), pts);
        active = clamp(t.getInteger("Active"), 0, pts.size());
        tmp = null;
        if (t.hasKey("Tmpy")) {
            tmp = new ArrayList<Wp>();
            readList(t.getTagList("Tmpy", 10), tmp);
            tmpActive = clamp(t.getInteger("TmpyActive"), 0, tmp.size());
        }
        rev++;
    }

    private static void readList(NBTTagList n, List<Wp> out) {
        for (int k = 0; k < n.tagCount() && out.size() < MAX; k++) {
            out.add(Wp.read(n.getCompoundTagAt(k)));
        }
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    static double sq(double v) {
        return v * v;
    }
}
