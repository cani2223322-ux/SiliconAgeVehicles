package com.sva.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sva.airdefense.ZoneMath;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerFms;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerMcdu;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.NavDb;
import com.sva.vehicle.SystemsMath;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 5 self-tests (plan-next §7, the SA-320's MCDU / FMS) - pure logic on the dedicated server: the scratchpad's
 * parsing (coordinates, idents, altitudes, speeds, frequencies) and its messages, the pages' LSK actions and screens (the
 * very screen the client draws, resolved through the lang file), the F-PLN's edits (insert, delete, discontinuities, DIR
 * TO, the TMPY's insert / erase), the constraints, the T/C / T/D profile, the V-speeds, FLEX's thrust, the fuel
 * predictions, the airport database from ILS beacons / zones / the pilots, the IRS INIT gate, the saves (the FMS, the
 * pilots' world data), the ND's data, the AFS' new sync words and memos, and a whole flight in the simulation: NAV by
 * the plan through its waypoints under a constraint, the managed descent from T/D on its path, the ILS tuned on RAD NAV
 * and the approach captured.
 */
final class SelfTestLinerFmsSVA {

    private SelfTestLinerFmsSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        ru = LangKeysSVA.map(LangKeysSVA.RU);
        parse();
        db();
        pages();
        fpln();
        dirTo();
        constraints();
        profile();
        vspeeds();
        flex();
        fuel();
        irs();
        saves();
        nd();
        sync();
        ecam();
        lang();
        flight();
    }

    // ---------------------------------------------------------------- helpers

    /** Two beacons (the origin ГЛВН at 0/0, the destination ОЗРН at 4000/0, both landing east) and a pilot's waypoint. */
    static NavDb navDb() {
        List<LinerIls.Station> st = new ArrayList<LinerIls.Station>();
        st.add(station(0, 0, 90, "ГЛВН", 10910));
        st.add(station(4000, 0, 90, "ОЗРН", 11030));
        List<NavDb.Wpt> w = new ArrayList<NavDb.Wpt>();
        w.add(NavDb.wpt("ЛЕС", NavDb.SRC_PILOT, 0, 2500.5, 300.5));
        return NavDb.build(0, st, null, null, w, new int[] {100, -200});
    }

    static LinerIls.Station station(int x, int z, double course, String ident, int freq) {
        LinerIls.Station s = new LinerIls.Station();
        s.bx = x;
        s.by = 63;
        s.bz = z;
        s.elev = 64;
        s.course = course;
        s.ident = ident;
        s.freq = freq;
        return s;
    }

    static LinerMcdu.Env env(NavDb db) {
        LinerMcdu.Env e = new LinerMcdu.Env();
        e.db = db;
        e.x = 10;
        e.z = 0;
        e.y = 64;
        e.fob = 48000;
        e.gw = 18600;
        e.ils.addAll(db.ils);
        final List<String> stored = new ArrayList<String>();
        e.store = new LinerMcdu.Store() {
            @Override
            public String putWpt(NavDb.Wpt w) {
                stored.add("w:" + w.ident);
                return null;
            }

            @Override
            public String putApt(NavDb.Airport a) {
                stored.add("a:" + a.ident);
                return null;
            }

            @Override
            public String delete(String ident, boolean airport) {
                stored.add("d:" + ident);
                return null;
            }
        };
        return e;
    }

    /** Types a string into MCDU `side`'s scratchpad. */
    static void type(LinerSystems L, int side, String s, LinerMcdu.Env e) {
        for (int i = 0; i < s.length(); i++) {
            L.mcdu.key(L, side, LinerMcdu.K_CHAR + s.charAt(i), e);
        }
    }

    static void key(LinerSystems L, int side, int code, LinerMcdu.Env e) {
        L.mcdu.key(L, side, code, e);
    }

    /** Types and presses a line key (left 1..6 / right 1..6). */
    static void enter(LinerSystems L, int side, String s, boolean right, int lsk, LinerMcdu.Env e) {
        type(L, side, s, e);
        key(L, side, (right ? LinerMcdu.K_R1 : LinerMcdu.K_L1) + lsk - 1, e);
    }

    private static Map<String, String> ru;

    static final LinerMcdu.Resolver RU = new LinerMcdu.Resolver() {
        @Override
        public String tr(String key, String[] args) {
            String v = ru == null ? null : ru.get(key);
            if (v == null) {
                return "?" + key;
            }
            try {
                return String.format(v, (Object[]) args);
            } catch (RuntimeException ex) {
                return v;
            }
        }
    };

    static String screen(LinerSystems L, int side, LinerMcdu.Env e) {
        return L.mcdu.render(L, side, e).plain(RU);
    }

    /** A planned liner: ГЛВН -> ОЗРН through Т1 (1000/0, at or below 112) and Т2 (2500/0), cruise 122, CI 50. */
    static LinerSystems planned(LinerMcdu.Env e) {
        LinerSystems L = SelfTestLinerSVA.cold();
        enter(L, 0, "ГЛВН/ОЗРН", true, 1, e);
        enter(L, 0, "122", false, 5, e);
        enter(L, 0, "50", false, 4, e);
        key(L, 0, LinerMcdu.K_FPLN, e);
        enter(L, 0, "1000/0", false, 2, e);                         // over the discontinuity after the origin
        enter(L, 0, "2500/0", false, 3, e);                         // before the destination
        key(L, 0, LinerMcdu.K_R1 + 5, e);                           // ВСТАВИТЬ
        enter(L, 0, "/-112", true, 2, e);
        return L;
    }

    /** " [flags 0110...]" - which parts of a check failed. */
    static String flags(boolean... b) {
        StringBuilder s = new StringBuilder(" [");
        for (boolean x : b) {
            s.append(x ? '1' : '0');
        }
        return s.append(']').toString();
    }

    static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }

    // ---------------------------------------------------------------- parsing

    private static void parse() {
        int[] a = LinerMcdu.coords("X1234/Z-567"), b = LinerMcdu.coords("1234/-567"), c = LinerMcdu.coords("Х1234/З-567");
        boolean coords = a != null && a[0] == 1234 && a[1] == -567 && b != null && b[0] == 1234 && b[1] == -567 && c != null && c[1] == -567
                && LinerMcdu.coords("12A/3") == null && LinerMcdu.coords("1/2/3") == null && LinerMcdu.coords("1234") == null
                && LinerMcdu.coords("/5") == null;
        int[] up = LinerMcdu.altCst("+150"), dn = LinerMcdu.altCst("-150"), at = LinerMcdu.altCst("150");
        boolean alts = up[0] == LinerFms.C_ABOVE && up[1] == 150 && dn[0] == LinerFms.C_BELOW && at[0] == LinerFms.C_AT && LinerMcdu.altCst("+") == null
                && LinerMcdu.altCst("300") == null && LinerMcdu.altCst("1X0") == null;
        int[] sa = LinerMcdu.spdAlt("160/+150"), so = LinerMcdu.spdAlt("/-120"), sp = LinerMcdu.spdAlt("140/"), s1 = LinerMcdu.spdAlt("140");
        boolean spdAlt = sa[0] == 160 && sa[1] == LinerFms.C_ABOVE && sa[2] == 150 && so[0] == -1 && so[1] == LinerFms.C_BELOW && so[2] == 120
                && sp[0] == 140 && sp[1] == -1 && s1[0] == 140 && LinerMcdu.spdAlt("250/") == null && LinerMcdu.spdAlt("/") == null;
        boolean freq = LinerMcdu.freq("110.30") == 11030 && LinerMcdu.freq("110,3") == 11030 && LinerMcdu.freq("11030") == 11030
                && LinerMcdu.freq("110.33") == Integer.MIN_VALUE && LinerMcdu.freq("120.00") == Integer.MIN_VALUE && LinerMcdu.freq("abc") == Integer.MIN_VALUE;
        int[] w = LinerMcdu.wind("270/15");
        boolean nums = LinerMcdu.tenths("18,6", 100, 400) == 186 && LinerMcdu.tenths("18.6", 100, 400) == 186 && LinerMcdu.tenths("18,65", 100, 400)
                == Integer.MIN_VALUE && LinerMcdu.num("+15", -60, 60) == 15 && LinerMcdu.num("-5", -60, 60) == -5 && LinerMcdu.num("5-", -60, 60)
                == Integer.MIN_VALUE && w[0] == 270 && w[1] == 15 && LinerMcdu.wind("270") == null;
        boolean chars = LinerMcdu.norm('a') == 'A' && LinerMcdu.norm('ё') == 'Е' && LinerMcdu.norm('ж') == 'Ж' && LinerMcdu.norm('#') == 0
                && LinerMcdu.norm('/') == '/';
        check(coords && alts && spdAlt && freq && nums && chars, "liner MCDU scratchpad parsing: world X/Z \"X1234/Z-567\" / \"1234/-567\" (Latin or"
                + " Cyrillic), +150 / -150 / 150 constraints, SPD/ALT, 110.30 / 110,3 / 11030 on the 0,05 grid, 18,6 t, temperatures, wind, upper case");
        // the messages: a bad entry, an unknown airport, out of range; CLR takes the message off, then the entry back
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = SelfTestLinerSVA.cold();
        enter(L, 0, "ЖЖЖЖ/ОЗРН", true, 1, e);
        String s1s = screen(L, 0, e);
        boolean noDb = s1s.contains("НЕТ В БАЗЕ") && L.fms.from.length() == 0;
        key(L, 0, LinerMcdu.K_CLR, e);
        boolean back = L.mcdu.sides[0].msg.length() == 0 && "ЖЖЖЖ/ОЗРН".equals(L.mcdu.sides[0].sp);
        for (int i = 0; i < 12; i++) {
            key(L, 0, LinerMcdu.K_CLR, e);
        }
        enter(L, 0, "999", false, 5, e);
        boolean range = screen(L, 0, e).contains("ВНЕ ДИАПАЗОНА") && L.fms.crz < 0;
        key(L, 0, LinerMcdu.K_CLR, e);
        for (int i = 0; i < 4; i++) {
            key(L, 0, LinerMcdu.K_CLR, e);
        }
        enter(L, 0, "1/2/3", false, 5, e);
        boolean bad = screen(L, 0, e).contains("ВНЕ ДИАПАЗОНА") || screen(L, 0, e).contains("НЕВЕРНЫЙ ВВОД");
        check(noDb && back && range && bad, "liner MCDU scratchpad messages: \"НЕТ В БАЗЕ\" for an unknown airport, CLR takes the message off"
                + " (the entry stays), \"ВНЕ ДИАПАЗОНА\" / \"НЕВЕРНЫЙ ВВОД\" for a bad cruise altitude");
    }

    // ---------------------------------------------------------------- the database

    private static void db() {
        List<LinerIls.Station> st = new ArrayList<LinerIls.Station>();
        st.add(station(0, 0, 90, "ОЗРН", 11030));
        st.add(station(300, 0, 270, "ОЗРН", 11050));               // the other end of the same runway
        st.add(station(9000, 9000, 0, "ВПП", 10910));
        LinerIls.Station other = station(0, 0, 0, "ОЗРН", 10950);
        other.dim = 1;                                              // the Nether: not this database
        st.add(other);
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.type = ZoneMath.AERODROME;
        z.name = "Ястреб";
        z.shape = ZoneMath.CIRCLE;
        z.cx = 500;
        z.cz = 600;
        z.r = 200;
        z.course = 180;
        ZoneMath.Zone noName = new ZoneMath.Zone();
        noName.type = ZoneMath.AERODROME;
        noName.name = "";
        noName.shape = ZoneMath.RECT;
        noName.x0 = 0;
        noName.x1 = 400;
        noName.z0 = 0;
        noName.z1 = 40;
        ZoneMath.Zone forbidden = new ZoneMath.Zone();
        forbidden.type = ZoneMath.FORBIDDEN;
        forbidden.name = "НЕЛЬЗЯ";
        List<ZoneMath.Zone> zones = new ArrayList<ZoneMath.Zone>();
        zones.add(z);
        zones.add(noName);
        zones.add(forbidden);
        List<NavDb.Airport> pilot = new ArrayList<NavDb.Airport>();
        pilot.add(NavDb.pilot("ДОМ", 0, 50, 50, 70, 45, 250));
        NavDb d = NavDb.build(0, st, zones, pilot, null, new int[] {1, 2});
        NavDb.Airport ozrn = d.airport("ОЗРН"), y = d.airport("ЯСТРЕБ"), ad = d.airport("АД02"), home = d.airport("ДОМ");
        boolean ils = ozrn != null && ozrn.rwys.size() == 2 && "09".equals(ozrn.rwys.get(0).id) && "27".equals(ozrn.rwys.get(1).id)
                && ozrn.rwys.get(0).len == 300 && ozrn.rwys.get(0).ilsKey.length() > 0 && d.airport("ВПП") != null && d.ils.size() == 3
                && "36".equals(d.airport("ВПП").rwys.get(0).id) && d.ils("ВПП", 0, 0, 0) != null && d.ils("", 11050, 0, 0).freq == 11050;
        boolean zone = y != null && y.src == NavDb.SRC_ZONE && "18".equals(y.rwys.get(0).id) && y.rwys.get(0).len == 400 && Math.abs(y.x - 500.5) < 1e-6
                && ad != null && ad.rwys.get(0).len == 400 && d.airport("НЕЛЬЗЯ") == null;
        boolean own = home != null && home.src == NavDb.SRC_PILOT && home.elev == 70 && "05".equals(home.rwys.get(0).id) && d.wpt(NavDb.BASE) != null
                && d.wpt(NavDb.BASE).src == NavDb.SRC_BASE && "ОЗРН".equals(NavDb.cleanIdent("озрн")) && NavDb.cleanIdent("x").length() == 0;
        check(ils && zone && own, "liner MCDU database: a beacon is a runway (\"09\" / \"27\", two opposite thresholds give the length, the ILS"
                + " linked), the air-defence \"аэродром\" zones as airports (the name, else \"АД02\"; the course and the long side), the pilots' own,"
                + " the map's \"БАЗА\"; other dimensions and other zone kinds left out");
    }

    // ---------------------------------------------------------------- the pages

    private static void pages() {
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = SelfTestLinerSVA.cold();
        LinerMcdu.Screen s0 = L.mcdu.render(L, 0, e);
        LinerMcdu.Cells c0 = s0.cells(RU);
        int boxes = 0;
        for (int r = 0; r < LinerMcdu.ROWS; r++) {
            for (int k = 0; k < LinerMcdu.COLS; k++) {
                boxes += c0.col[r][k] == LinerMcdu.C_BOX ? 1 : 0;
            }
        }
        enter(L, 0, "ГЛВН/ОЗРН", true, 1, e);
        enter(L, 0, "SA320", false, 1, e);
        enter(L, 0, "50", false, 4, e);
        enter(L, 0, "122/-5", false, 5, e);
        enter(L, 0, "18,6/28600", false, 6, e);
        String init = screen(L, 0, e);
        LinerMcdu.Cells ci = L.mcdu.render(L, 0, e).cells(RU);
        boolean cyan = ci.col[2][COL(ci, 2, 'Г')] == LinerMcdu.C_CYAN;
        boolean initOk = L.fms.pts.size() == 4 && "ГЛВН09".equals(L.fms.pts.get(0).id) && L.fms.pts.get(1).kind == LinerFms.K_DISC
                && "ОЗРН09".equals(L.fms.pts.get(2).id) && L.fms.pts.get(3).kind == LinerFms.K_MISSED && L.fms.ci == 50 && L.fms.crz == 122
                && L.fms.crzTemp == -5 && L.fms.zfw10 == 186 && L.fms.block == 28600 && "SA320".equals(L.fms.flt) && init.contains("ГЛВН/ОЗРН")
                && init.contains("ИНИЦ") && cyan && boxes >= 14;
        check(initOk, "liner MCDU ИНИЦ: amber boxes on the empty mandatory fields (" + boxes + " cells), FROM/TO -> the plan (origin, РАЗРЫВ,"
                + " destination, missed approach), flight, CI, cruise / temperature, ZFW / block - all cyan");
        // the second MCDU: its own page and scratchpad, the same data
        key(L, 1, LinerMcdu.K_PROG, e);
        type(L, 1, "АБВ", e);
        boolean own = L.mcdu.sides[0].page == LinerMcdu.P_INIT && L.mcdu.sides[1].page == LinerMcdu.P_PROG && "АБВ".equals(L.mcdu.sides[1].sp)
                && L.mcdu.sides[0].sp.length() == 0 && screen(L, 1, e).contains("122");
        // every function key opens its page (title)
        int[] keys = {LinerMcdu.K_DIR, LinerMcdu.K_PROG, LinerMcdu.K_PERF, LinerMcdu.K_INIT, LinerMcdu.K_DATA, LinerMcdu.K_FPLN, LinerMcdu.K_RAD,
                LinerMcdu.K_FUEL, LinerMcdu.K_MENU};
        String[] titles = {"НАПРЯМУЮ", "ПРОГР", "ВЗЛЁТ", "ИНИЦ", "ДАННЫЕ", "ПЛАН ПОЛЁТА", "РАДИОНАВ", "ТОПЛИВО ПРОГН", "МФПУ МЕНЮ"};
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            key(L, 0, keys[i], e);
            String t = screen(L, 0, e).split("\n")[0];
            if (!t.contains(titles[i])) {
                bad.append(' ').append(titles[i]).append('=').append(t.trim());
            }
        }
        // the PERF pages turn with the arrows; LSK 6 right goes on; INIT B by the arrow
        key(L, 0, LinerMcdu.K_PERF, e);
        key(L, 0, LinerMcdu.K_NEXT, e);
        boolean clb = L.mcdu.sides[0].page == LinerMcdu.P_PERF_CLB;
        key(L, 0, LinerMcdu.K_R1 + 5, e);
        boolean crz = L.mcdu.sides[0].page == LinerMcdu.P_PERF_CRZ;
        key(L, 0, LinerMcdu.K_INIT, e);
        key(L, 0, LinerMcdu.K_NEXT, e);
        String initB = screen(L, 0, e);
        boolean b = L.mcdu.sides[0].page == LinerMcdu.P_INITB && initB.contains("18,6") && initB.contains("28 600");
        check(own && bad.length() == 0 && clb && crz && b, "liner MCDU pages: each MCDU its own page / scratchpad over the shared FMGC; НАПР,"
                + " ПРОГ, ХАР (by the phase), ИНИЦ, ДАННЫЕ, ПЛАН, РАДИО, ТОПЛ, МЕНЮ open their pages; the arrows / LSK 6R turn the PERF pages; ИНИЦ Б"
                + bad);
        // DATA: the lists and storing a pilot's waypoint / airport through the store
        key(L, 0, LinerMcdu.K_DATA, e);
        key(L, 0, LinerMcdu.K_R1 + 1, e);
        String wl = screen(L, 0, e);
        enter(L, 0, "МАЯК/100/200", false, 6, e);
        boolean stored = screen(L, 0, e).contains("СОХРАНЕНО");
        key(L, 0, LinerMcdu.K_DATA, e);
        key(L, 0, LinerMcdu.K_R1, e);
        String al = screen(L, 0, e);
        enter(L, 0, "ПОЛЕ/10/20/70/90/300", false, 6, e);
        boolean aStored = screen(L, 0, e).contains("СОХРАНЕНО");
        key(L, 0, LinerMcdu.K_L1, e);
        String apt = screen(L, 0, e);
        check(wl.contains("ЛЕС") && wl.contains("БАЗА") && stored && al.contains("ГЛВН") && al.contains("ОЗРН") && aStored && apt.contains("ВПП 09")
                        && (apt.contains("110,30") || apt.contains("109,10")),
                "liner MCDU ДАННЫЕ: the waypoints (the pilots', \"БАЗА\"), the airports (from the beacons) with their runways and ILS; a pilot's"
                        + " waypoint ID/X/Z and airport ID/X/Z/ELEV/COURSE/LENGTH stored (\"СОХРАНЕНО В БАЗЕ\")");
    }

    /** The column of the first `ch` on row r (0 none). */
    static int COL(LinerMcdu.Cells c, int r, char ch) {
        for (int k = 0; k < LinerMcdu.COLS; k++) {
            if (c.ch[r][k] == ch) {
                return k;
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- F-PLN edits

    private static void fpln() {
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = SelfTestLinerSVA.cold();
        enter(L, 0, "ГЛВН/ОЗРН", true, 1, e);
        enter(L, 0, "122", false, 5, e);
        key(L, 0, LinerMcdu.K_FPLN, e);
        // line 1 the origin (FROM), line 2 the discontinuity: a coordinate entry replaces it - into the TMPY
        enter(L, 0, "X1000/Z0", false, 2, e);
        String tmpy = screen(L, 0, e);
        LinerMcdu.Cells cells = L.mcdu.render(L, 0, e).cells(RU);
        boolean yellow = cells.col[0][COL(cells, 0, 'В')] == LinerMcdu.C_YELLOW;
        boolean t1 = L.fms.tmp != null && L.fms.tmp.size() == 4 && "Т1".equals(L.fms.tmp.get(1).id) && L.fms.pts.get(1).kind == LinerFms.K_DISC
                && tmpy.contains("ВРЕМ ПЛАН") && tmpy.contains("СТЕРЕТЬ") && tmpy.contains("ВСТАВИТЬ") && yellow;
        // СТЕРЕТЬ: gone; again and ВСТАВИТЬ
        key(L, 0, LinerMcdu.K_L1 + 5, e);
        boolean erased = L.fms.tmp == null && L.fms.pts.get(1).kind == LinerFms.K_DISC;
        enter(L, 0, "1000/0", false, 2, e);
        key(L, 0, LinerMcdu.K_R1 + 5, e);
        boolean inserted = L.fms.tmp == null && "Т1".equals(L.fms.pts.get(1).id) && L.fms.pts.size() == 4 && L.fms.active == 1;
        // a database waypoint before the destination (line 3), then CLR on Т1: a discontinuity in its place; CLR on that: joined
        enter(L, 0, "ЛЕС", false, 3, e);
        key(L, 0, LinerMcdu.K_R1 + 5, e);
        boolean les = "ЛЕС".equals(L.fms.pts.get(2).id) && Math.abs(L.fms.pts.get(2).z - 300.5) < 1e-6;
        key(L, 0, LinerMcdu.K_CLR, e);
        key(L, 0, LinerMcdu.K_L1 + 1, e);
        boolean disc = L.fms.tmp != null && L.fms.tmp.get(1).kind == LinerFms.K_DISC;
        key(L, 0, LinerMcdu.K_CLR, e);
        key(L, 0, LinerMcdu.K_L1 + 1, e);
        key(L, 0, LinerMcdu.K_R1 + 5, e);
        boolean joined = L.fms.tmp == null && L.fms.pts.size() == 4 && "ЛЕС".equals(L.fms.pts.get(1).id);
        // not allowed: deleting the origin / the destination; an unknown name
        key(L, 0, LinerMcdu.K_CLR, e);
        key(L, 0, LinerMcdu.K_L1, e);
        boolean noOrig = L.fms.tmp == null && screen(L, 0, e).contains("НЕДОСТУПНО");
        key(L, 0, LinerMcdu.K_CLR, e);
        enter(L, 0, "НЕТТАК", false, 2, e);
        boolean unknown = screen(L, 0, e).contains("НЕТ В БАЗЕ");
        // LAT REV: the next waypoint after ЛЕС
        key(L, 0, LinerMcdu.K_CLR, e);
        while (L.mcdu.sides[0].sp.length() > 0) {
            key(L, 0, LinerMcdu.K_CLR, e);
        }
        key(L, 0, LinerMcdu.K_L1 + 1, e);
        boolean latrev = L.mcdu.sides[0].page == LinerMcdu.P_LATREV && screen(L, 0, e).contains("БОК ИЗМ ЛЕС");
        enter(L, 0, "3000/100", true, 3, e);
        key(L, 0, LinerMcdu.K_R1 + 5, e);
        boolean next = L.fms.pts.size() == 5 && "ЛЕС".equals(L.fms.pts.get(1).id) && L.fms.pts.get(2).id.startsWith("Т") && L.fms.pts.get(3).kind
                == LinerFms.K_DEST;
        // the plan's lines: T/C before ЛЕС, T/D before the destination, the end
        List<LinerMcdu.Line> ls = LinerMcdu.lines(L.fms);
        boolean tc = false, td = false, end = ls.get(ls.size() - 1).kind == LinerMcdu.LN_END;
        for (LinerMcdu.Line l : ls) {
            tc |= l.kind == LinerMcdu.LN_TC;
            td |= l.kind == LinerMcdu.LN_TD;
        }
        L.fms.profile(ctx(10, 0, 64, true));
        ls = LinerMcdu.lines(L.fms);
        for (LinerMcdu.Line l : ls) {
            tc |= l.kind == LinerMcdu.LN_TC;
            td |= l.kind == LinerMcdu.LN_TD;
        }
        check(t1 && erased && inserted && les && disc && joined && noOrig && unknown && latrev && next && tc && td && end,
                "liner MCDU F-PLN: an entry over the discontinuity goes into the yellow TMPY (\"ВРЕМ ПЛАН\", СТЕРЕТЬ / ВСТАВИТЬ*); erased; inserted;"
                        + " a database waypoint before the destination; CLR leaves \"РАЗРЫВ ПЛАНА\", CLR on that joins; the origin can't go;"
                        + " unknown - \"НЕТ В БАЗЕ\"; LAT REV's next waypoint; the lines with (T/C), (T/D) and the end" + flags(t1, erased, inserted,
                        les, disc, joined, noOrig, unknown, latrev, next, tc, td, end));
    }

    static LinerFms.Ctx ctx(double x, double z, double y, boolean ground) {
        LinerFms.Ctx c = new LinerFms.Ctx();
        c.x = x;
        c.z = z;
        c.y = y;
        c.ground = ground;
        c.fob = 48000;
        c.gw = 18600;
        return c;
    }

    private static void dirTo() {
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = planned(e);
        e.x = 400;
        e.z = 50;
        // from the plan's list (line 3 = the second waypoint ahead: Т2)
        key(L, 0, LinerMcdu.K_DIR, e);
        String page = screen(L, 0, e);
        key(L, 0, LinerMcdu.K_L1 + 2, e);
        boolean tmpy = L.fms.tmp != null && "ТП".equals(L.fms.tmp.get(0).id) && "Т2".equals(L.fms.tmp.get(1).id) && L.fms.tmpActive == 1
                && "Т2".equals(L.mcdu.sides[0].dirId);
        key(L, 0, LinerMcdu.K_R1 + 5, e);
        boolean active = L.fms.tmp == null && L.fms.active == 1 && "ТП".equals(L.fms.pts.get(0).id) && "Т2".equals(L.fms.pts.get(1).id)
                && L.fms.pts.get(0).x == 400;
        // the bridge: from the turn point straight to Т2, then on to the destination and the missed approach
        LinerFms.Bridge br = L.fms.bridge();
        boolean bridge = br.plan.size() == 4 && br.plan.active == 1 && br.plan.pts.get(1)[0] == 2500 && br.map.get(1).intValue() == 1;
        // a waypoint not in the plan: ТП, it, a discontinuity, the rest
        key(L, 0, LinerMcdu.K_DIR, e);
        enter(L, 0, "ЛЕС", false, 1, e);
        boolean fresh = L.fms.tmp != null && "ЛЕС".equals(L.fms.tmp.get(1).id) && L.fms.tmp.get(2).kind == LinerFms.K_DISC;
        key(L, 0, LinerMcdu.K_L1 + 5, e);
        check(page.contains("<Т1") && page.contains("<Т2") && tmpy && active && bridge && fresh && L.fms.tmp == null,
                "liner MCDU НАПРЯМУЮ: the plan's waypoints listed; one picked - the TMPY from the present position \"ТП\" straight to it, ВСТАВИТЬ"
                        + " makes it active (NAV's bridge: ТП -> Т2 -> ...); a new waypoint - ТП, it, a discontinuity; ОТМЕНА erases"
                        + flags(page.contains("<Т1") && page.contains("<Т2"), tmpy, active, bridge, fresh, L.fms.tmp == null));
    }

    // ---------------------------------------------------------------- constraints

    private static void constraints() {
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = planned(e);
        LinerFms.Wp t1 = L.fms.pts.get(1);
        boolean below = t1.cst == LinerFms.C_BELOW && t1.alt == 112;
        // VERT REV on Т2: the speed and an AT OR ABOVE altitude
        key(L, 0, LinerMcdu.K_FPLN, e);
        key(L, 0, LinerMcdu.K_R1 + 2, e);
        boolean page = L.mcdu.sides[0].page == LinerMcdu.P_VERTREV && screen(L, 0, e).contains("ВЕРТ ИЗМ Т2");
        enter(L, 0, "150", false, 2, e);
        enter(L, 0, "+100", false, 3, e);
        LinerFms.Wp t2 = L.fms.pts.get(2);
        boolean set = t2.spd == 150 && t2.cst == LinerFms.C_ABOVE && t2.alt == 100;
        // the climb sees Т1's ceiling, the descent Т2's floor
        boolean ahead = L.fms.cstAhead(true) == 112 && L.fms.cstAhead(false) == 100;
        L.fms.sequence(2);
        boolean next = Double.isNaN(L.fms.cstAhead(true)) && L.fms.cstAhead(false) == 100;
        // CLR on the F-PLN's right key clears them; the origin / destination take none
        key(L, 0, LinerMcdu.K_FPLN, e);
        key(L, 0, LinerMcdu.K_CLR, e);
        key(L, 0, LinerMcdu.K_R1 + 1, e);
        boolean cleared = L.fms.pts.get(2).cst == LinerFms.C_NONE && L.fms.pts.get(2).spd == 0;
        boolean dest = !L.fms.setCst(3, 140, LinerFms.C_AT, 100) && !L.fms.setCst(0, 140, LinerFms.C_AT, 100);
        String fp = screen(L, 0, e);
        check(below && page && set && ahead && next && cleared && dest && fp.contains("Т2"),
                "liner MCDU constraints: \"/-112\" on the F-PLN line - AT OR BELOW; VERT REV: speed 150, \"+100\" AT OR ABOVE; the climb takes the"
                        + " next ceiling, the descent the next floor (sequenced past Т1: none for the climb); CLR clears; airports take none");
    }

    // ---------------------------------------------------------------- the profile

    private static void profile() {
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = planned(e);
        LinerFms F = L.fms;
        F.profile(ctx(0, 0, 64, true));
        double g5 = Math.tan(Math.toRadians(LinerFms.CLB_ANGLE)), g3 = Math.tan(Math.toRadians(LinerFms.DES_ANGLE));
        // the climb from the origin (64) under Т1's 112: then from Т1 (s 1000) on to 122
        double tc = 1000 + (122 - 112) / g5;
        double td = 4000 - (122 - 64 - LinerFms.PATH_H) / g3;
        double sTc0 = F.sTc, sTd0 = F.sTd;
        boolean pos = Math.abs(F.sTc - tc) < 1 && Math.abs(F.sTd - td) < 1 && Math.abs(F.tcX - tc) < 1.5 && Math.abs(F.tdX - td) < 1.5
                && Math.abs(F.tcZ) < 1;
        // the path at T/D is the cruise altitude, at the threshold PATH_H over it; the aircraft past T/D gets it
        F.phase = LinerFms.PH_CRZ;
        F.sequence(3);
        F.profile(ctx(3500, 0, 122, false));
        double p1 = F.pathNow;
        double want = 64 + LinerFms.PATH_H + (4000 - 3500) * g3;
        boolean path = Math.abs(p1 - want) < 0.5 && F.tdPassed() && Math.abs(F.distToDest() - 500) < 1;
        // an AT OR BELOW on the descent holds the path down before it
        LinerFms G = new LinerFms();
        G.setFromTo(db.airport("ГЛВН"), db.airport("ОЗРН"));
        LinerFms.Wp w = LinerFms.wpt("Т9", 3000.5, 0.5);
        w.cst = LinerFms.C_BELOW;
        w.alt = 90;
        G.insertAt(1, w);
        G.insertAt(1, LinerFms.wpt("Т8", 1000.5, 0.5));            // the climb tops out before Т9's ceiling
        G.tmpInsert();
        G.crz = 122;
        G.profile(ctx(0, 0, 64, true));
        double tdHeld = 3000 - (122 - 90) / g3;
        boolean held = Math.abs(G.sTd - tdHeld) < 1.5;
        check(pos && path && held, "liner FMS profile: T/C " + f(sTc0) + " (want " + f(tc) + ": 5 deg from the origin, held at Т1's -112), T/D "
                + f(sTd0) + " (want " + f(td) + ": 3 deg back from PATH_H over the threshold); the path " + f(p1) + " at 500 out; a -90 constraint moves"
                + " T/D to " + f(G.sTd) + " (want " + f(tdHeld) + ")");
    }

    // ---------------------------------------------------------------- V-speeds, FLEX

    private static void vspeeds() {
        int[] v = LinerFms.computeV(17.5, 1, 0), heavy = LinerFms.computeV(19.4, 1, 0), shortRwy = LinerFms.computeV(17.5, 1, 150);
        int[] flaps3 = LinerFms.computeV(17.5, 3, 0);
        boolean order = v[0] <= v[1] && v[1] < v[2] && v[1] > 60 && v[2] < 175 && heavy[1] > v[1] && shortRwy[0] < v[0] && shortRwy[1] == v[1]
                && flaps3[1] < v[1];
        // the PERF page: boxes, ВЫЧИСЛИТЬ, disagreeing entries refused; the AFS takes them on the ground (the PFD marks)
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = planned(e);
        key(L, 0, LinerMcdu.K_PERF, e);
        String empty = screen(L, 0, e);
        key(L, 0, LinerMcdu.K_R1 + 4, e);
        boolean computed = L.fms.vEntered() && L.fms.towAtV > 0;
        int keep = L.fms.v1;
        enter(L, 0, "175", false, 1, e);
        boolean disagree = L.fms.v1 == keep && screen(L, 0, e).contains("НЕ СОГЛАС");
        key(L, 0, LinerMcdu.K_CLR, e);
        for (int i = 0; i < 4; i++) {
            key(L, 0, LinerMcdu.K_CLR, e);
        }
        enter(L, 0, "80", false, 1, e);
        boolean manual = L.fms.v1 == 80;
        LinerSimSVA s = new LinerSimSVA();
        s.L.fms.v1 = 120;
        s.L.fms.vr = 126;
        s.L.fms.v2 = 132;
        s.tick();
        boolean afs = s.L.afs.vsEntered && Math.abs(s.L.afs.v1 * 72 - 120) < 1e-6 && Math.abs(s.L.afs.v2 * 72 - 132) < 1e-6;
        LinerSimSVA n = new LinerSimSVA();
        n.tick();
        boolean none = !n.L.afs.vsEntered && n.L.afs.v1 > 0;
        check(order && empty.contains("V1") && computed && disagree && manual && afs && none,
                "liner MCDU V-speeds: V1 <= VR < V2 from the take-off weight, the flaps, the runway (" + v[0] + "/" + v[1] + "/" + v[2] + " km/h;"
                        + " heavier - higher, short runway - lower V1, flaps 3 - lower); ВЫЧИСЛИТЬ*, V1 over VR refused (\"V1/VR/V2 НЕ СОГЛАС\");"
                        + " the AFS takes the entered ones on the ground (the PFD's marks), none entered - its own estimate, no marks"
                        + flags(order, empty.contains("V1"), computed, disagree, manual, afs, none));
    }

    private static void flex() {
        boolean share = Math.abs(LinerFms.flexShare(45) - LinerAfs.DET_FLX) < 1e-9 && LinerFms.flexShare(60) < LinerAfs.DET_FLX
                && LinerFms.flexShare(16) == 1 && LinerFms.flexShare(70) >= LinerAfs.DET_CL;
        LinerSimSVA g = new LinerSimSVA();
        g.setFlaps(1);
        g.L.fms.flex = 60;
        g.L.afs.lever = LinerAfs.DET_FLX;
        g.tick();
        boolean hot = "MAN FLX 60".equals(g.L.afs.thrText()) && Math.abs(g.L.afs.thrCmd - LinerFms.flexShare(60)) < 1e-9;
        LinerSimSVA m = new LinerSimSVA();
        m.setFlaps(1);
        m.L.afs.lever = LinerAfs.DET_FLX;
        m.tick();
        boolean mct = "MAN FLX".equals(m.L.afs.thrText()) && Math.abs(m.L.afs.thrCmd - LinerAfs.DET_FLX) < 1e-9;
        // the MCDU's entry: limits
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = planned(e);
        key(L, 0, LinerMcdu.K_PERF, e);
        enter(L, 0, "99", true, 4, e);
        boolean range = L.fms.flex == 0;
        key(L, 0, LinerMcdu.K_CLR, e);
        key(L, 0, LinerMcdu.K_CLR, e);
        key(L, 0, LinerMcdu.K_CLR, e);
        enter(L, 0, "52", true, 4, e);
        check(share && hot && mct && range && L.fms.flex == 52, "liner FLEX: the temperature sets the FLX detent's N1 (45 = the old fixed share,"
                + " hotter - less thrust): \"MAN FLX 60\" at " + f(g.L.afs.thrCmd) + "; none - \"MAN FLX\" at the detent's MCT; the MCDU takes 16..70");
    }

    // ---------------------------------------------------------------- fuel

    private static void fuel() {
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = planned(e);
        LinerFms F = L.fms;
        F.profile(ctx(0, 0, 64, true));
        double[] trip = F.trip(1);
        double burned = 48000 - F.taxi - F.destFuel;
        boolean model = trip[0] > 0 && Math.abs(burned - trip[0]) / trip[0] < 0.15 && F.destTicks > 0;
        // twice the consumption, twice the burn
        LinerFms.Ctx c2 = ctx(0, 0, 64, true);
        c2.burn = 2;
        F.profile(c2);
        boolean twice = Math.abs((48000 - F.taxi - F.destFuel) - 2 * burned) < 1;
        F.profile(ctx(0, 0, 64, true));
        key(L, 0, LinerMcdu.K_FUEL, e);
        String page = screen(L, 0, e);
        // too little fuel: the extra goes amber (negative)
        e.fob = 2500;
        F.profile(ctx(0, 0, 64, true));
        LinerFms.Ctx low = ctx(0, 0, 64, true);
        low.fob = 2500;
        F.profile(low);
        LinerMcdu.Cells cl = L.mcdu.render(L, 0, e).cells(RU);
        boolean amber = false;
        for (int k = 0; k < LinerMcdu.COLS; k++) {
            amber |= cl.col[12][k] == LinerMcdu.C_AMBER && cl.ch[12][k] != ' ';
        }
        check(model && twice && page.contains("ОЗРН") && page.contains("ОСТАТОК") && amber,
                "liner FMS fuel prediction: the per-leg burn (climb / cruise / descent) to the destination " + f(burned) + " mB vs the trip "
                        + f(trip[0]) + "; x2 with the consumption config; ТОПЛИВО ПРОГН shows it; short of the minimum - the extra amber");
    }

    // ---------------------------------------------------------------- IRS INIT

    private static void irs() {
        boolean gate = LinerFms.irsGate(true, false) && LinerFms.irsGate(false, true) && !LinerFms.irsGate(false, false);
        SystemsMath.Ins ins = new SystemsMath.Ins();
        int total = SystemsMath.alignTicks(10);
        int st = 0;
        for (int i = 0; i < total + 100; i++) {
            st = ins.tick(true, true, false, LinerFms.alignTotal(total, false));
            if (ins.done > total) {
                ins.done = total;
            }
        }
        boolean held = st == SystemsMath.INS_ALIGN;
        st = ins.tick(true, true, false, LinerFms.alignTotal(total, true));
        boolean ready = st == SystemsMath.INS_READY;
        // the page: ВЫСТАВИТЬ* (not automatic) confirms; the memo and the MCDU message while waiting
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        e.autoIrs = false;
        e.insStatus = SystemsMath.INS_ALIGN;
        LinerSystems L = SelfTestLinerSVA.cold();
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        L.afs.irsState = 2;
        L.mcdu.tick(L, e);
        boolean msg = L.mcdu.sides[0].msg.equals("sva.mcdu.msg.irsInit") && L.mcdu.sides[1].msgAmber;
        boolean memo = (L.memos(true) & LinerSystems.M_IRS_INIT) != 0 && (LinerSystems.MEMO_AMBER & LinerSystems.M_IRS_INIT) != 0;
        key(L, 0, LinerMcdu.K_CLR, e);
        key(L, 0, LinerMcdu.K_INIT, e);
        key(L, 0, LinerMcdu.K_R1 + 1, e);
        String page = screen(L, 0, e);
        key(L, 0, LinerMcdu.K_R1 + 5, e);
        L.afs.irsState = 1;
        boolean align = (L.memos(true) & LinerSystems.M_IRS_ALIGN) != 0;
        check(gate && held && ready && msg && memo && page.contains("ВЫСТАВИТЬ") && page.contains("ЖДЁТ") && L.fms.irsDone && align,
                "liner IRS INIT: without the config's auto-confirm the alignment waits at its end (\"ИНС ЖДЁТ ВВОДА\", the amber memo, the MCDU"
                        + " message) until ВЫСТАВИТЬ*, then aligns the next tick; aligning - the green \"ИНС ВЫСТ.\"");
    }

    // ---------------------------------------------------------------- saves

    private static void saves() {
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = planned(e);
        LinerFms F = L.fms;
        F.v1 = 120;
        F.vr = 126;
        F.v2 = 132;
        F.flex = 50;
        F.dh = 15;
        F.altn = "ГЛВН";
        F.insertAt(2, LinerFms.wpt("ТМП", 7, 8));                   // a TMPY kept too
        NBTTagCompound t = new NBTTagCompound();
        L.writeNBT(t);
        LinerSystems R = SelfTestLinerSVA.cold();
        R.readNBT(t);
        LinerFms G = R.fms;
        boolean same = G.pts.size() == F.pts.size() && G.active == F.active && "Т1".equals(G.pts.get(1).id) && G.pts.get(1).cst == LinerFms.C_BELOW
                && G.pts.get(1).alt == 112 && G.crz == 122 && G.ci == 50 && G.v1 == 120 && G.v2 == 132 && G.flex == 50 && G.dh == 15
                && "ГЛВН".equals(G.altn) && G.tmp != null && "ТМП".equals(G.tmp.get(2).id) && "ОЗРН".equals(G.to) && G.pts.get(3).hdg == 90
                && G.pts.get(3).kind == LinerFms.K_DEST;
        // the pilots' world data
        com.sva.block.NavDataSVA d = new com.sva.block.NavDataSVA("test");
        d.putWpt(NavDb.wpt("ЛЕС", NavDb.SRC_PILOT, 0, 10.5, 20.5));
        d.putApt(NavDb.pilot("ПОЛЕ", 0, 100, 200, 70, 90, 300));
        d.putWpt(NavDb.wpt("ЛЕС", NavDb.SRC_PILOT, 0, 30.5, 40.5));  // the same name replaces
        NBTTagCompound w = new NBTTagCompound();
        d.writeToNBT(w);
        com.sva.block.NavDataSVA r = new com.sva.block.NavDataSVA("test");
        r.readFromNBT(w);
        NBTTagCompound w2 = new NBTTagCompound();
        r.writeToNBT(w2);
        boolean world = r.size() == 2 && w.equals(w2) && d.delete("ЛЕС", false, 0) == null && d.size() == 1 && d.delete("ЛЕС", false, 0) != null;
        check(same && world, "liner saves: the FMS (INIT, PERF, the plan with its constraints, the TMPY) through LinerSystems' NBT; the pilots'"
                + " airports / waypoints world data round trip (a name replaces, deleted once)");
    }

    // ---------------------------------------------------------------- the ND's data, the sync

    private static void nd() {
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = planned(e);
        L.fms.profile(ctx(0, 0, 64, true));
        String s = L.fms.ndText();
        LinerFms.NdView v = LinerFms.parseNd(s);
        boolean ok = v != null && v.pts.size() == 5 && v.active == 1 && "Т1".equals(v.pts.get(1).id) && v.pts.get(1).cst == LinerFms.C_BELOW
                && Math.abs(v.tcX - L.fms.tcX) < 1 && Math.abs(v.tdX - L.fms.tdX) < 1 && v.tmp.isEmpty() && LinerFms.parseNd("garbage") == null;
        L.fms.insertAt(2, LinerFms.wpt("ТМП", 7, 8));
        LinerFms.NdView t = LinerFms.parseNd(L.fms.ndText());
        check(ok && t != null && t.tmp.size() == 6, "liner ND data: the plan from the FROM entry (names, kinds, constraints), T/C, T/D and the"
                + " TMPY - text round trip; malformed - none");
    }

    private static void sync() {
        LinerSystems L = SelfTestLinerSVA.cold();
        L.afs.flexTemp = 52;
        L.afs.vsEntered = true;
        L.afs.irsState = 2;
        L.afs.dh = 15;
        L.afs.mda = -1;
        L.afs.pathAlt = 101.5;
        LinerSystems C = new LinerSystems();
        C.unpack(L.pack());
        boolean ok = C.afs.flexTemp == 52 && C.afs.vsEntered && C.afs.irsState == 2 && C.afs.dh == 15 && C.afs.mda == -1
                && Math.abs(C.afs.pathAlt - 101.5) < 1e-9 && C.pack().equals(L.pack());
        L.afs.pathAlt = Double.NaN;
        C.unpack(L.pack());
        check(ok && Double.isNaN(C.afs.pathAlt), "liner sync, wave 5: FLEX, the V-speeds entered, the IRS state, the minimums, the descent path"
                + " ride the AFS' words");
    }

    // ---------------------------------------------------------------- ECAM

    private static void ecam() {
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = SelfTestLinerSVA.cold();
        LinerSystems.Env r = SelfTestLinerHydSVA.running(L);
        L.set(LinerControls.FLAPS, 1);
        SelfTestLinerSVA.run(L, r, 200, null);
        L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        SelfTestLinerSVA.run(L, r, 2, null);
        boolean noPlan = L.ecam.toCfgOk && (L.ecam.active & 1L << LinerEcam.M_TO_SPEEDS) == 0;
        enter(L, 0, "ГЛВН/ОЗРН", true, 1, e);
        L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        SelfTestLinerSVA.run(L, r, 2, null);
        boolean missing = !L.ecam.toCfgOk && (L.ecam.shown & 1L << LinerEcam.M_TO_SPEEDS) != 0 && L.masterCaut;
        key(L, 0, LinerMcdu.K_PERF, e);
        key(L, 0, LinerMcdu.K_R1 + 4, e);
        SelfTestLinerSVA.run(L, r, 2, null);
        L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        SelfTestLinerSVA.run(L, r, 2, null);
        boolean good = L.ecam.toCfgOk && (L.ecam.active & 1L << LinerEcam.M_TO_SPEEDS) == 0 && (L.memos(true) & LinerSystems.M_TO_NORMAL) != 0
                && L.afs.vsEntered;
        check(noPlan && missing && good, "liner ECAM: T.O. CONFIG with a flight plan and no V-speeds - \"V1/VR/V2 НЕ ВВЕДЕНЫ\" (MASTER CAUT, no"
                + " \"ТО КОНФИГ НОРМ\"); entered on PERF - \"ТО КОНФИГ НОРМ\"; without a plan the old test holds");
    }

    // ---------------------------------------------------------------- lang

    private static void lang() throws java.io.IOException {
        Map<String, String> en = LangKeysSVA.map(LangKeysSVA.EN);
        StringBuilder miss = new StringBuilder();
        List<String> keys = new ArrayList<String>();
        for (String p : LinerFms.PH_KEYS) {
            keys.add("sva.mcdu.prog." + p);
        }
        for (String p : new String[] {"clb", "crz", "des"}) {
            keys.add("sva.mcdu.perf." + p);
        }
        for (String p : NavDb.SRC_KEYS) {
            keys.add("sva.mcdu.src." + p);
        }
        for (String p : new String[] {"dir", "prog", "perf", "init", "data", "brt", "fpln", "rad", "fuel", "sec", "atc", "menu", "dim", "airport", "sp",
                "ovfy", "clr"}) {
            keys.add("sva.mcdu.key." + p);
        }
        keys.add("sva.liner.msg.toSpeeds");
        keys.add("sva.liner.memo.irsAlign");
        keys.add("sva.liner.memo.irsInit");
        keys.add("sva.liner.fma.fcuAlt");
        keys.add("sva.liner.ctl.mcdu1");
        keys.add("sva.liner.ctl.mcdu2");
        for (String k : keys) {
            if (ru == null || en == null || !ru.containsKey(k) || !en.containsKey(k)) {
                miss.append(' ').append(k);
            }
        }
        // every page of every phase renders; nothing past 24 columns comes from a key; no forbidden glyph on the screen
        NavDb db = navDb();
        LinerMcdu.Env e = env(db);
        LinerSystems L = planned(e);
        StringBuilder bad = new StringBuilder();
        for (int ph = 0; ph <= LinerFms.PH_DONE; ph++) {
            L.fms.phase = ph;
            for (int p = 0; p < LinerMcdu.PAGES; p++) {
                L.mcdu.sides[0].page = p;
                L.mcdu.sides[0].selId = "Т1";
                try {
                    LinerMcdu.Screen s = L.mcdu.render(L, 0, e);
                    for (LinerMcdu.Seg g : s.segs) {
                        String t = LinerMcdu.Screen.text(g, RU);
                        if (t.startsWith("?sva")) {
                            bad.append(" missing:").append(t);
                        }
                        if (t.length() > LinerMcdu.COLS) {
                            bad.append(" long:").append(t);
                        }
                        for (int i = 0; i < t.length(); i++) {
                            if (GlyphCheckSVA.forbidden(t.charAt(i))) {
                                bad.append(" glyph:").append(t);
                            }
                        }
                    }
                    if (!LinerMcdu.Screen.unpack(s.pack()).pack().equals(s.pack())) {
                        bad.append(" pack:").append(p);
                    }
                } catch (RuntimeException ex) {
                    bad.append(" page ").append(p).append(": ").append(ex);
                }
            }
        }
        check(miss.length() == 0 && bad.length() == 0, "liner MCDU lang: the phase / page / source / key words in ru + en; every page of every phase"
                + " renders, each text within 24 columns, glyph-safe, the packet's round trip" + miss + bad);
    }

    // ---------------------------------------------------------------- a flight

    /**
     * The simulation (the real systems and flight model) flies the planned route: from 40 over the ground east past the
     * origin, AP 1 with NAV (the HDG knob pushed) and managed CLB (the ALT knob pushed, the FCU at the cruise); Т1's
     * \"-112\" holds the climb (ALT CST) until it is sequenced, then the cruise; the FCU set lower at the cruise; DES from
     * T/D on the path; RAD NAV tunes ОЗРН by its ident, APPR - LOC and G/S captured.
     */
    private static void flight() {
        NavDb db = navDb();
        LinerSimSVA s = LinerSimSVA.flying(40, 1.6, 90, 0);
        s.b.x = 200;
        for (LinerIls.Station x : db.ils) {
            s.ils.add(x);
        }
        LinerMcdu.Env e = env(db);
        LinerSystems L = s.L;
        enter(L, 0, "ГЛВН/ОЗРН", true, 1, e);
        enter(L, 0, "122", false, 5, e);
        key(L, 0, LinerMcdu.K_FPLN, e);
        enter(L, 0, "1000/0", false, 2, e);
        enter(L, 0, "2500/0", false, 3, e);
        key(L, 0, LinerMcdu.K_R1 + 5, e);
        enter(L, 0, "/-112", true, 2, e);
        s.click(LinerControls.FCU_AP1);
        L.afs.alt = 122;
        s.click(LinerControls.FCU_HDG, LinerControls.A_PRIMARY);
        s.click(LinerControls.FCU_ALT, LinerControls.A_PRIMARY);
        double maxBefore = 0, maxCross = 0, desX = Double.NaN, pathErr = 0;
        int seq1 = -1, seq2 = -1, cruise = -1, gs = -1;
        boolean lowered = false, appr = false, tuned = false, csts = false;
        for (int t = 0; t < 3000 && gs < 0; t++) {
            s.tick();
            LinerAfs A = L.afs;
            LinerFms F = L.fms;
            if (F.active <= 1) {
                maxBefore = Math.max(maxBefore, s.b.y);
                csts |= A.cst && A.ver == LinerAfs.V_ALT;
            }
            maxCross = Math.max(maxCross, Math.abs(s.b.z));
            seq1 = seq1 < 0 && F.active == 2 ? t : seq1;
            seq2 = seq2 < 0 && F.active == 3 ? t : seq2;
            if (!lowered && F.phase == LinerFms.PH_CRZ && A.ver == LinerAfs.V_ALT) {
                lowered = true;
                cruise = t;
                A.alt = 90;
            }
            if (Double.isNaN(desX) && A.ver == LinerAfs.V_DES) {
                desX = s.b.x;
            }
            if (!Double.isNaN(desX) && A.ver == LinerAfs.V_DES && !Double.isNaN(A.pathAlt)) {
                pathErr = Math.max(pathErr, Math.abs(s.b.y - A.pathAlt));
            }
            if (!appr && s.b.x > 3300) {
                appr = true;
                e.x = s.b.x;
                key(L, 0, LinerMcdu.K_RAD, e);
                enter(L, 0, "ОЗРН", false, 1, e);
                s.click(LinerControls.FCU_APPR);
            }
            tuned |= A.ils != null && "ОЗРН".equals(A.ils.ident) && A.ilsChosen.length() > 0;
            gs = gs < 0 && (A.ver == LinerAfs.V_GS_CAP || A.ver == LinerAfs.V_GS || A.ver == LinerAfs.V_LAND) ? t : gs;
        }
        double tdX = L.fms.tdX;
        check(seq1 > 0 && seq2 > seq1 && maxBefore < 112.6 && csts && cruise > seq1 && maxCross < 2,
                "liner FMS flight: NAV flies the plan (Т1 sequenced at " + seq1 + ", Т2 at " + seq2 + " ticks, " + f(maxCross) + " off the line);"
                        + " managed CLB held at Т1's -112 (ALT CST, max " + f(maxBefore) + "), then on to the cruise 122");
        check(!Double.isNaN(desX) && Math.abs(desX - tdX) < 6 && pathErr < 3, "liner FMS flight: the managed descent begins at T/D (x " + f(desX)
                + ", T/D " + f(tdX) + ") and keeps its 3 deg path within " + f(pathErr) + " blocks");
        check(tuned && gs > 0, "liner FMS flight: RAD NAV tunes ОЗРН by its ident (by hand), APPR - G/S captured at " + gs + " ticks" + flags(tuned)
                + " " + L.afs.verText() + "/" + L.afs.verArmText() + " " + L.afs.latText() + " x " + f(s.b.x) + " y " + f(s.b.y) + " " + L.afs.ilsChosen);
    }
}
