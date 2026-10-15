package com.sva.debug;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.ZoneMath;
import com.sva.airport.ApproachMath;
import com.sva.airport.AtisMath;
import com.sva.airport.AutoTower;
import com.sva.airport.ChartMath;
import com.sva.airport.Cpdlc;
import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.airport.RunwayLayout;
import com.sva.airport.TaxiGraph;
import com.sva.airport.TowerMath;
import com.sva.block.BlockTaxiLightSVA;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerFms;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerMcdu;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.NavDb;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Airport wave B2 self-tests (plan-next §9, items 27, 29-34) - pure logic on the dedicated server: the tower's runways and
 * its ICAO code, the runway in use by the wind, the air-defence zone and the code range; ATIS - the letter's rules and the
 * text in Russian and English; CPDLC - the message states, the responses, the timeouts, the log, the FCU preset; the
 * automatic tower's sequencing, separation, landing priority, go-around; the approach's geometry (FAF / IF / MAP / the
 * missed approach / the transitions) and its insertion into the FMS (the MCDU's ЗАХОДЫ page) and a LinerSimSVA flight on it
 * (the ILS tuned from the plan, the CAT III autoland); the chart's data, its NBT, its version; the taxi graph, the route, the
 * progressive lights, the stop bars; the MCDU's СВЯЗЬ / ATIS / message / request pages over a datalink; the liner's
 * «ATC MSG» sync; the wrapped MCDU segments; the lang keys.
 */
final class SelfTestAirportB2SVA {

    private SelfTestAirportB2SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }

    private static Map<String, String> ru, en;

    static LinerMcdu.Resolver res(final Map<String, String> m) {
        return new LinerMcdu.Resolver() {
            @Override
            public String tr(String key, String[] args) {
                String v = m == null ? null : m.get(key);
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
    }

    static void run() throws java.io.IOException {
        ru = LangKeysSVA.map(LangKeysSVA.RU);
        en = LangKeysSVA.map(LangKeysSVA.EN);
        towerRunways();
        icao();
        runwayInUse();
        zone();
        atisLetter();
        atisText();
        cpdlcStates();
        cpdlcLog();
        autoSequence();
        autoGoAround();
        approachGeometry();
        approachFms();
        approachMcdu();
        approachFlight();
        chart();
        taxiGraph();
        taxiLights();
        mcduDatalink();
        linerSync();
        wrap();
        lang();
    }

    /** A runway along +x: end A at (ax, z) landing east («09»), B at ax + len («27»), elevation 64. */
    static IlsPairing.Rwy rwy(String key, double ax, double z, double len) {
        IlsPairing.Rwy r = new IlsPairing.Rwy();
        r.a = new IlsPairing.End(key + "a", 0, ax, z, 64, 90, 5);
        r.b = new IlsPairing.End(key + "b", 0, ax + len, z, 64, 270, 5);
        r.course = 90;
        r.len = len;
        r.width = 5;
        r.elev = 64;
        r.nameA = "09";
        r.nameB = "27";
        return r;
    }

    // ---------------------------------------------------------------- the tower

    private static void towerRunways() {
        List<TowerMath.Site> t = new ArrayList<TowerMath.Site>();
        t.add(new TowerMath.Site(0, 100, 50));
        t.add(new TowerMath.Site(0, 3000, 0));
        t.add(new TowerMath.Site(-1, 150, 0));
        List<IlsPairing.Rwy> r = new ArrayList<IlsPairing.Rwy>();
        r.add(rwy("r1", 0, 0, 300));
        r.add(rwy("r2", 2750, 0, 300));
        r.add(rwy("r3", 5850, 0, 300));
        r.add(rwy("r4", 1350, 0, 300));
        int[] a = TowerMath.assign(t, r);
        check(a[0] == 0 && a[1] == 1 && a[2] == -1 && a[3] == 0,
                "airport B2 tower: each runway to the nearest tower within 15 km-eq (its middle; same dimension only) - "
                        + java.util.Arrays.toString(a) + " (the far one keeps its ZZxx)");
        ZoneMath.Zone z = TowerMath.zone("UUEE", "Test", "Owner", 0, 100, 65, 50, r.subList(0, 1), 04100, 04177);
        check(z.type == ZoneMath.AERODROME && z.shape == ZoneMath.CIRCLE && z.cx == 150 && z.cz == 0 && z.r == 270 && z.codeLo == 04100
                        && z.codeHi == 04177 && !z.alarm && z.show && z.lo == 0 && z.hi == ZoneMath.ALT_MAX && "UUEE".equals(z.name) && "Test".equals(z.kp),
                "airport B2 tower zone: a circle round the runway's ends (centre 150 / 0, radius " + z.r + " = the farthest end + "
                        + TowerMath.ZONE_MARGIN + "), 0..255, the tower's codes 4100-4177, no alarm");
        ZoneMath.Zone none = TowerMath.zone("UUEF", "", "", 0, 10, 65, 20, new ArrayList<IlsPairing.Rwy>(), 04100, 04177);
        check(none.cx == 10 && none.cz == 20 && none.r == TowerMath.ZONE_MIN && TowerMath.sameZone(z, z.copy()) && !TowerMath.sameZone(z, none),
                "airport B2 tower zone: no runways - round the tower, the smallest radius; the same zone is not published again");
    }

    private static void icao() {
        List<String> taken = new ArrayList<String>();
        taken.add("UUEE");
        String d1 = TowerMath.defaultIcao(100, 200, taken);
        taken.add(d1);
        String d2 = TowerMath.defaultIcao(100, 200, taken);
        check("UUEE".equals(TowerMath.cleanIcao("uu-ee")) && "".equals(TowerMath.cleanIcao("ЮЮЕЕ")) && "".equals(TowerMath.cleanIcao("UUE"))
                        && "".equals(TowerMath.cleanIcao("UUEEX")) && !TowerMath.unique("uuee", taken) && TowerMath.unique("UUWW", taken)
                        && TowerMath.validIcao(d1) && d1.startsWith("Z") && !d1.startsWith("ZZ") && !d1.equals(d2) && TowerMath.validIcao(d2),
                "airport B2 ICAO: 4 Latin letters (Cyrillic look-alikes refused), unique in the world (case-insensitive), a new tower's free «Z» code ("
                        + d1 + ", then " + d2 + "), never a ZZ placeholder");
        int[] c = TowerMath.codeRange(04100, 04177), bad = TowerMath.codeRange(06000, 07777), bad2 = TowerMath.codeRange(04100, 010000);
        check(c != null && c[0] == 04100 && c[1] == 04177 && bad == null && bad2 == null && TowerMath.codeRange(04177, 04100)[0] == 04100
                        && "B-12".equals(TowerMath.callsign("", com.sva.airdefense.XpdrMath.S, 12, 01200))
                        && "B-12".equals(TowerMath.callsign(null, com.sva.airdefense.XpdrMath.C, 12, 01200))
                        && "1200".equals(TowerMath.callsign(null, com.sva.airdefense.XpdrMath.C, 0, 01200))
                        && "SA123".equals(TowerMath.callsign("SA123", com.sva.airdefense.XpdrMath.C, 12, 01200)),
                "airport B2 codes / callsigns: a code range without 7000 / 7500 / 7600 / 7700 inside; the flight number, else B-board, else the"
                        + " squawk");
    }

    private static void runwayInUse() {
        List<TowerMath.End> e = new ArrayList<TowerMath.End>();
        e.add(new TowerMath.End("09", 90, 3));
        e.add(new TowerMath.End("27", 270, 0));
        int west = TowerMath.pickRunway(e, 270, 20), cross = TowerMath.pickRunway(e, 0, 20), calm = TowerMath.pickRunway(e, -1, 0),
                light = TowerMath.pickRunway(e, 260, 4), east = TowerMath.pickRunway(e, 80, 25);
        check(west == 1 && cross == 0 && calm == 0 && light == 0 && east == 0,
                "airport B2 runway in use (AUTO): a west wind 20 km/h - 27 (head wind), a cross wind or calm - the ILS end 09, 4 km/h tail - still 09"
                        + " (within " + TowerMath.PREFER_KMH + " km/h), an east wind - 09");
    }

    private static void zone() {
        AirDefenseDataSVA ad = new AirDefenseDataSVA("test");
        List<ZoneMath.Zone> kp = new ArrayList<ZoneMath.Zone>(), tw = new ArrayList<ZoneMath.Zone>();
        ZoneMath.Zone a = new ZoneMath.Zone();
        a.name = "KPZONE";
        kp.add(a);
        tw.add(TowerMath.zone("UUEE", "UUEE", "", 0, 0, 64, 0, new ArrayList<IlsPairing.Rwy>(), 04100, 04177));
        ad.setZones("0:1:2:3", kp);
        ad.setZones(com.sva.block.AtcServerSVA.ZONE_KEY + "0:5:5:5", tw);
        int all = ad.all().size(), ex = ad.allExcept(com.sva.block.AtcServerSVA.ZONE_KEY).size();
        int v = ad.version();
        ad.removeKp(com.sva.block.AtcServerSVA.ZONE_KEY + "0:5:5:5");
        check(all == 2 && ex == 1 && ad.all().size() == 1 && ad.version() == v + 1 && ZoneMath.aerodromeCode(tw.get(0), 04123)
                        && !ZoneMath.aerodromeCode(tw.get(0), 04200),
                "airport B2 AD zone sync: the tower's «аэродром» zone sits beside the command posts' (" + all + "), the MCDU's database leaves it out ("
                        + ex + "), the tower gone - the zone gone; its codes count as the aerodrome's (4123 in, 4200 out)");
    }

    // ---------------------------------------------------------------- ATIS

    static AtisMath.Obs obs() {
        AtisMath.Obs o = new AtisMath.Obs();
        o.rwy = "09";
        o.appr = AtisMath.A_ILS;
        o.cat = 3;
        o.windFrom = 240;
        o.windKmh = 18;
        o.gustKmh = 20;
        o.vis = 10000;
        o.temp = 12;
        o.qnh = 1008;
        return o;
    }

    private static void atisLetter() {
        AtisMath.Obs o = obs();
        AtisMath.Obs dir = o.copy(), qnh = o.copy(), temp = o.copy(), vis = o.copy(), gust = o.copy(), rwy = o.copy(), cat = o.copy(), rmk = o.copy(),
                calm = o.copy(), calm2 = o.copy(), rvr = o.copy(), small = o.copy();
        small.windFrom = 255;
        small.windKmh = 22;
        dir.windFrom = 280;
        qnh.qnh = 1009;
        temp.temp = 14;
        vis.vis = 1800;
        gust.gustKmh = 34;
        rwy.rwy = "27";
        cat.cat = 1;
        rmk.rmk = AtisMath.RMK_ILS_OFF;
        calm.windKmh = 3;
        calm.gustKmh = 3;
        calm2.windKmh = 4;
        calm2.gustKmh = 4;
        calm2.windFrom = 60;
        rvr.rvr = new int[] {500, 600, 700};
        boolean rules = AtisMath.changed(null, o) && !AtisMath.changed(o, small) && AtisMath.changed(o, dir) && AtisMath.changed(o, qnh)
                && !AtisMath.changed(o, temp) && AtisMath.changed(o, vis) && AtisMath.changed(o, gust) && AtisMath.changed(o, rwy) && AtisMath.changed(o, cat)
                && AtisMath.changed(o, rmk) && !AtisMath.changed(calm, calm2) && AtisMath.changed(o, rvr);
        check(rules && AtisMath.next(0) == 1 && AtisMath.next(25) == 0 && AtisMath.visBand(10000) == 0 && AtisMath.visBand(500) == 5,
                "airport B2 ATIS letter: a new one for the runway, the approach / its category, the wind (30 deg / 10 km/h, gusts coming), the QNH (1 hPa),"
                        + " the visibility band, the RVR's category, the remarks - not for a 15 deg / 4 km/h drift, 2 deg C, a calm's direction; Z -> A");
    }

    private static void atisText() {
        AtisMath.Obs o = obs();
        o.gustKmh = 30;
        o.rvr = new int[] {550, 600, 700};
        o.vis = 800;
        o.rmk = AtisMath.RMK_LVP | AtisMath.RMK_FOLLOW;
        List<AtisMath.Line> l = AtisMath.text("UBTW", "", 1, 6000, o);
        List<String> r = AtisMath.render(l, res(ru)), e = AtisMath.render(l, res(en));
        String all = r.toString(), allE = e.toString();
        boolean ruOk = r.get(0).equals("UBTW ИНФОРМАЦИЯ БРАВО 12:00") && all.contains("ПОЛОСА В ИСПОЛЬЗОВАНИИ 09") && all.contains("ЗАХОД ПО ILS CAT III")
                && all.contains("ВЕТЕР 240 ГРАД 18 КМ/Ч ПОРЫВЫ 30") && all.contains("ВИДИМОСТЬ 800 М") && all.contains("RVR ПОЛОСА 09 550 / 600 / 700")
                && all.contains("QNH 1008") && all.contains("ТЕМПЕРАТУРА 12") && all.contains("ПРОЦЕДУРЫ ПОНИЖЕННОЙ ВИДИМОСТИ")
                && r.get(r.size() - 1).equals("СООБЩИТЕ О ПОЛУЧЕНИИ ИНФОРМАЦИИ БРАВО") && !all.contains("?sva");
        boolean enOk = e.get(0).equals("UBTW INFORMATION BRAVO 12:00") && allE.contains("RUNWAY IN USE 09") && allE.contains("WIND 240 DEG 18 KM/H GUSTS 30")
                && allE.contains("ACKNOWLEDGE INFORMATION BRAVO") && !allE.contains("?sva");
        List<AtisMath.Line> back = AtisMath.unpack(AtisMath.pack(l));
        boolean round = back.size() == l.size() && AtisMath.render(back, res(ru)).equals(r);
        AtisMath.Obs none = new AtisMath.Obs();
        String noWx = AtisMath.render(AtisMath.text("UBTW", "Name", 0, 0, none), res(ru)).toString();
        boolean glyphs = true;
        for (String s : r) {
            for (int i = 0; i < s.length(); i++) {
                glyphs &= !GlyphCheckSVA.forbidden(s.charAt(i));
            }
        }
        check(ruOk && enOk && round && glyphs && noWx.contains("Name ИНФОРМАЦИЯ АЛЬФА 06:00") && noWx.contains("ПОЛОСЫ НЕТ") && noWx.contains("ВЕТРА НЕТ ДАННЫХ")
                        && !noWx.contains("QNH"),
                "airport B2 ATIS text (ru / en): «" + r.get(0) + " / " + r.get(1) + " / " + r.get(3) + " / ... / " + r.get(r.size() - 1)
                        + "»; packed and back the same; no weather station - no wind / QNH; glyph-safe" + SelfTestLinerFmsSVA.flags(ruOk, enOk, round, glyphs));
    }

    // ---------------------------------------------------------------- CPDLC

    private static void cpdlcStates() {
        Cpdlc.Log a = new Cpdlc.Log();
        Cpdlc.Msg up = a.add(a.make(true, Cpdlc.U_CLIMB, 150, "", "", -1, 100));
        Cpdlc.Msg stby = a.add(a.make(true, Cpdlc.U_STBY, 0, "", "", -1, 100));
        boolean start = up.state == Cpdlc.S_OPEN && stby.state == Cpdlc.S_CLOSED && a.unread() == 2 && a.pendingUplink() == up;
        Cpdlc.Msg r1 = a.respond(up.id, Cpdlc.R_STBY, 200);
        boolean st1 = r1 != null && r1.type == Cpdlc.D_STBY && r1.ref == up.id && up.state == Cpdlc.S_STBY && a.respond(up.id, Cpdlc.R_STBY, 210) == null;
        a.tick(200 + Cpdlc.RESP_TICKS + 10);
        boolean longer = up.state == Cpdlc.S_STBY;
        Cpdlc.Msg r2 = a.respond(up.id, Cpdlc.R_WILCO, 300);
        int[] p = Cpdlc.preset(up);
        boolean wilco = r2 != null && r2.type == Cpdlc.D_WILCO && up.state == Cpdlc.S_WILCO && up.closed == 300 && a.respond(up.id, Cpdlc.R_UNABLE, 310) == null
                && p != null && p[0] == Cpdlc.P_ALT && p[1] == 150 && a.respond(stby.id, Cpdlc.R_WILCO, 300) == null;
        Cpdlc.Msg hdg = a.add(a.make(true, Cpdlc.U_HDG, 450, "", "", -1, 400));
        a.respond(hdg.id, Cpdlc.R_WILCO, 410);
        int[] ph = Cpdlc.preset(hdg);
        Cpdlc.Msg land = a.add(a.make(true, Cpdlc.U_LAND, 0, "27L", "", -1, 400));
        a.respond(land.id, Cpdlc.R_UNABLE, 410);
        Cpdlc.Msg late = a.add(a.make(true, Cpdlc.U_HDG, 90, "", "", -1, 500));
        a.tick(500 + Cpdlc.RESP_TICKS + 1);
        check(start && st1 && longer && wilco && ph != null && ph[0] == Cpdlc.P_HDG && ph[1] == 90 && land.state == Cpdlc.S_UNABLE && Cpdlc.preset(land) == null
                        && late.state == Cpdlc.S_TIMEOUT && Cpdlc.preset(late) == null,
                "airport B2 CPDLC uplinks: OPEN until answered; STBY once (a longer timeout), WILCO / UNABLE close it (each answered once, the response"
                        + " refers to it); unanswered - TIMEOUT; ЗАГРУЗИТЬ after WILCO of НАБОР (FCU altitude 150) / КУРС (heading 090 for 450), none"
                        + " for ПОСАДКУ / an unanswered one" + SelfTestLinerFmsSVA.flags(start, st1, longer, wilco));
        // requests: the tower's answers
        Cpdlc.Log b = new Cpdlc.Log();
        Cpdlc.Msg req = b.add(b.make(false, Cpdlc.D_LANDING, 0, "09", "", -1, 0));
        boolean open = req.state == Cpdlc.S_OPEN && b.openRequest(Cpdlc.D_LANDING) == req;
        b.answered(req.id, Cpdlc.U_STBY, 50);
        boolean sb = req.state == Cpdlc.S_STBY;
        b.answered(req.id, Cpdlc.U_LAND, 80);
        boolean closed = req.state == Cpdlc.S_CLOSED && b.openRequest(Cpdlc.D_LANDING) == null;
        Cpdlc.Msg req2 = b.add(b.make(false, Cpdlc.D_CLIMB, 200, "", "", -1, 100));
        b.answered(req2.id, Cpdlc.U_UNABLE, 120);
        Cpdlc.Msg req3 = b.add(b.make(false, Cpdlc.D_TAXI, 0, "", "", -1, 200));
        b.tick(200 + Cpdlc.REQ_TICKS + 1);
        Cpdlc.Msg free = b.add(b.make(false, Cpdlc.D_FREE, 0, Cpdlc.cleanFree("hello §tower|x"), "", -1, 0));
        check(open && sb && closed && req2.state == Cpdlc.S_UNABLE && req3.state == Cpdlc.S_TIMEOUT && free.state == Cpdlc.S_CLOSED
                        && "HELLO TOWERX".equals(free.s),
                "airport B2 CPDLC requests: OPEN until the tower answers - ОЖИДАЙТЕ keeps it (STBY), a clearance closes it, НЕ МОГУ - UNABLE, none -"
                        + " TIMEOUT; free text cleaned (upper case, no § / |)");
    }

    private static void cpdlcLog() {
        Cpdlc.Log a = new Cpdlc.Log();
        for (int i = 0; i < Cpdlc.LOG_MAX + 8; i++) {
            a.add(a.make(true, Cpdlc.U_FREE, 0, "M" + i, "", -1, i));
        }
        Cpdlc.Msg first = a.msgs.get(0);
        Cpdlc.Msg keep = a.make(true, Cpdlc.U_HDG, 10, "", "", -1, 0);
        Cpdlc.Log c = new Cpdlc.Log();
        c.add(keep);
        for (int i = 0; i < Cpdlc.LOG_MAX + 3; i++) {
            c.add(c.make(true, Cpdlc.U_STBY, 0, "", "", -1, i));
        }
        boolean kept = c.msgs.contains(keep);
        List<Cpdlc.Msg> recent = a.recent(3);
        String t = Cpdlc.text(a.make(true, Cpdlc.U_REPORT, Cpdlc.REP_VACATED, "", "", -1, 0), res(ru));
        String taxi = Cpdlc.text(a.make(true, Cpdlc.U_TAXI, 0, "27L", "840", -1, 0), res(ru));
        String plain = Cpdlc.text(a.make(true, Cpdlc.U_TAXI, 0, "27L", "", -1, 0), res(en));
        a.readAll();
        check(a.msgs.size() == Cpdlc.LOG_MAX && !"M0".equals(a.msgs.get(0).s) && first.s.equals("M8") && kept && recent.get(0).s.equals("M" + (Cpdlc.LOG_MAX + 7))
                        && a.unread() == 0 && t.equals("ДОЛОЖИТЕ ПОЛОСУ ОСВОБОДИЛИ ") && taxi.equals("РУЛИТЕ К ПОЛОСЕ 27L ПО ЗЕЛЁНЫМ ОГНЯМ, 840 М")
                        && plain.equals("TAXI TO RUNWAY 27L"),
                "airport B2 CPDLC log: " + Cpdlc.LOG_MAX + " messages kept (the oldest closed ones go first, an open one stays), newest first; the texts: «"
                        + t.trim() + "», «" + taxi + "», «" + plain + "»");
    }

    // ---------------------------------------------------------------- the automatic tower

    static AutoTower.Ac ac(int id, double x, double z, double h, double gs, boolean ground, int req) {
        AutoTower.Ac a = new AutoTower.Ac();
        a.id = id;
        a.call = "A" + id;
        a.x = x;
        a.z = z;
        a.y = 64 + h;
        a.gs = gs;
        a.ground = ground;
        a.req = req;
        return a;
    }

    static int act(List<AutoTower.Act> l, int id) {
        for (AutoTower.Act a : l) {
            if (a.id == id) {
                return a.type;
            }
        }
        return -1;
    }

    static boolean has(List<AutoTower.Act> l, int id, int type) {
        for (AutoTower.Act a : l) {
            if (a.id == id && a.type == type) {
                return true;
            }
        }
        return false;
    }

    private static void autoSequence() {
        AutoTower.Rwy w = new AutoTower.Rwy(rwy("r", 0.5, 0.5, 300), 1);
        AutoTower.Mem m = new AutoTower.Mem();
        AutoTower.Params p = new AutoTower.Params();
        p.interval = 400;
        List<AutoTower.Ac> t = new ArrayList<AutoTower.Ac>();
        AutoTower.Ac a1 = ac(1, -800, 0.5, 40, 1.3, false, AutoTower.R_LAND), a2 = ac(2, -1800, 0.5, 90, 1.3, false, AutoTower.R_LAND),
                d1 = ac(3, 10, -10, 0, 0, true, AutoTower.R_TAKEOFF), t1 = ac(4, 60, -30, 0, 0, true, AutoTower.R_TAXI);
        t.add(a1);
        t.add(a2);
        t.add(d1);
        t.add(t1);
        List<AutoTower.Act> r1 = AutoTower.decide(t, w, m, 1000, p);
        boolean first = act(r1, 1) == com.sva.airport.Cpdlc.U_LAND && act(r1, 2) == com.sva.airport.Cpdlc.U_STBY && act(r1, 3) == com.sva.airport.Cpdlc.U_HOLDSHORT
                && act(r1, 4) == com.sva.airport.Cpdlc.U_TAXI && a1.st == AutoTower.S_LAND && d1.st == AutoTower.S_HOLD;
        List<AutoTower.Act> r1b = AutoTower.decide(t, w, m, 1020, p);
        boolean quiet = r1b.isEmpty();
        // A1 on the runway (rolling): still landing; off it: the movement time
        a1.x = 120;
        a1.y = 64;
        a1.ground = true;
        a1.gs = 1;
        List<AutoTower.Act> r2 = AutoTower.decide(t, w, m, 1100, p);
        a1.z = -15;
        a1.gs = 0.3;
        AutoTower.decide(t, w, m, 1140, p);
        boolean vacated = a1.st == AutoTower.S_NONE && m.lastMove == 1140 && r2.isEmpty();
        List<AutoTower.Act> r3 = AutoTower.decide(t, w, m, 1300, p);           // within the interval: nobody
        a2.x = -1500;
        List<AutoTower.Act> r4 = AutoTower.decide(t, w, m, 1600, p);           // the interval past: the arrival first (landing priority)
        boolean priority = r3.isEmpty() && act(r4, 2) == com.sva.airport.Cpdlc.U_LAND && act(r4, 3) == -1;
        check(first && quiet && vacated && priority,
                "airport B2 automatic tower: two arrivals and a departure - the nearer one cleared to land, the other ОЖИДАЙТЕ, the departure ОЖИДАЙТЕ"
                        + " ПЕРЕД ПОЛОСОЙ, a taxi at once; nothing repeated; the runway vacated - the movement noted; " + p.interval + " ticks of separation;"
                        + " then the next arrival before the departure" + SelfTestLinerFmsSVA.flags(first, quiet, vacated, priority));
        // the arrival lands and vacates; the departure goes - unless an arrival is close
        a2.x = 150;
        a2.z = -20;
        a2.y = 64;
        a2.ground = true;
        a2.gs = 0.2;
        AutoTower.decide(t, w, m, 1700, p);
        AutoTower.Ac a3 = ac(5, -500, 0.5, 30, 1.3, false, AutoTower.R_LAND);
        t.add(a3);
        List<AutoTower.Act> r5 = AutoTower.decide(t, w, m, 2200, p);
        boolean protect = act(r5, 5) == com.sva.airport.Cpdlc.U_LAND && act(r5, 3) == -1;
        t.remove(a3);
        List<AutoTower.Act> r6 = AutoTower.decide(t, w, m, 2700, p);
        boolean go = has(r6, 3, com.sva.airport.Cpdlc.U_LINEUP) && has(r6, 3, com.sva.airport.Cpdlc.U_TAKEOFF) && d1.st == AutoTower.S_TAKEOFF;
        // the departure on the runway and not airborne yet: an arrival waits
        d1.x = 30;
        d1.z = 0.5;
        AutoTower.Ac a4 = ac(6, -900, 0.5, 45, 1.3, false, AutoTower.R_LAND);
        t.add(a4);
        List<AutoTower.Act> r7 = AutoTower.decide(t, w, m, 3200, p);
        check(protect && go && act(r7, 6) == com.sva.airport.Cpdlc.U_STBY && a4.st == AutoTower.S_NONE,
                "airport B2 automatic tower: an arrival within " + (int) AutoTower.ARR_PROTECT + " blocks keeps the departure holding; then ЗАНИМАЙТЕ ПОЛОСУ +"
                        + " ВЗЛЁТ РАЗРЕШАЮ; the departure on the runway (not airborne) - the next arrival ОЖИДАЙТЕ (one aircraft on the runway)");
    }

    private static void autoGoAround() {
        AutoTower.Rwy w = new AutoTower.Rwy(rwy("r", 0.5, 0.5, 300), 1);
        AutoTower.Mem m = new AutoTower.Mem();
        AutoTower.Params p = new AutoTower.Params();
        p.gaHeight = 25;
        List<AutoTower.Ac> t = new ArrayList<AutoTower.Ac>();
        AutoTower.Ac a = ac(1, -700, 0.5, 37, 1.3, false, AutoTower.R_LAND);
        t.add(a);
        AutoTower.decide(t, w, m, 100, p);
        boolean cleared = a.st == AutoTower.S_LAND;
        AutoTower.Ac v = ac(2, 80, 0.5, 0, 0, true, AutoTower.R_NONE);   // a vehicle onto the runway
        t.add(v);
        a.x = -500;
        a.y = 64 + 27;
        List<AutoTower.Act> high = AutoTower.decide(t, w, m, 200, p);
        a.x = -450;
        a.y = 64 + 23;
        List<AutoTower.Act> low = AutoTower.decide(t, w, m, 220, p);
        List<AutoTower.Act> again = AutoTower.decide(t, w, m, 240, p);
        boolean offFinal = !AutoTower.onFinal(w, ac(9, -500, 120, 20, 1, false, 0)) && AutoTower.onFinal(w, ac(9, -500, 40, 20, 1, false, 0));
        check(cleared && high.isEmpty() && act(low, 1) == com.sva.airport.Cpdlc.U_GOAROUND && a.st == AutoTower.S_GOAROUND && again.isEmpty() && offFinal,
                "airport B2 automatic tower: cleared to land, a vehicle rolls onto the runway - above " + (int) p.gaHeight + " over it nothing yet, below -"
                        + " УХОДИТЕ НА ВТОРОЙ КРУГ (once), the clearance gone; the final's corridor (15 % of the distance)");
    }

    // ---------------------------------------------------------------- approaches

    static ApproachMath.Approach approach(LinerIls.Station st) {
        return ApproachMath.ils("09", 1000, 0, 64, 90, st, ApproachMath.entries("ZZAA", 1150, 0, 64));
    }

    private static void approachGeometry() {
        LinerIls.Station st = SelfTestAirportSVA.full(1000, 300, 30, 25, 15, 3);
        ApproachMath.Approach a = approach(st);
        ApproachMath.Fix fi = a.fix(ApproachMath.F_IF), ff = a.fix(ApproachMath.F_FAF), ma = a.fix(ApproachMath.F_MA), rt = a.fix(ApproachMath.F_RETURN);
        int fafAlt = (int) Math.ceil(64 + IlsCat.TCH_NOMINAL + 550 * Math.tan(Math.toRadians(3)));
        boolean geo = fi != null && ff != null && ma != null && rt != null && Math.abs(ff.x - 450) < 1e-6 && Math.abs(ff.z) < 1e-6 && Math.abs(fi.x) < 1e-6
                && Math.abs(ma.x - 2200) < 1e-6 && ff.alt == fafAlt && ff.cst == ApproachMath.C_AT && fi.alt == fafAlt && fi.cst == ApproachMath.C_ABOVE
                && ma.alt == 64 + ApproachMath.MA_H && ma.cst == ApproachMath.C_ABOVE && rt.id.equals(fi.id) && rt.alt == fafAlt
                && "FF09".equals(ff.id) && "CI09".equals(fi.id) && "MA09".equals(ma.id) && "ILS09".equals(a.id) && a.cat == 3 && a.freq == 10950;
        ApproachMath.Fix n = a.trans.get(0), e = a.trans.get(1);
        boolean trans = a.trans.size() == 4 && "ZZAAN".equals(n.id) && Math.abs(n.x - 1150) < 1e-6 && Math.abs(n.z + 2000) < 1e-6 && "ZZAAE".equals(e.id)
                && Math.abs(e.x - 3150) < 1e-6 && n.alt >= fafAlt + 15 && n.cst == ApproachMath.C_ABOVE;
        // the G/S passes the FAF at its altitude (the glide path's own height there)
        double gsAt = 64 + IlsCat.TCH_NOMINAL + 550 * Math.tan(Math.toRadians(3));
        boolean onPath = ff.alt - gsAt >= 0 && ff.alt - gsAt < 1 && ApproachMath.onFinal(a, 500, 10, 30) && !ApproachMath.onFinal(a, 1100, 0, 30);
        check(geo && trans && onPath, "airport B2 approach: FF09 " + (int) ApproachMath.FAF_DIST + " blocks (5,5 km-eq) before the threshold on the centre"
                + " line at " + ff.alt + " (the 3 deg glide path + TCH, AT), CI09 at 10 km-eq (" + fi.alt + ", AT OR ABOVE), MA09 " + (int) ApproachMath.MA_DIST
                + " past it (+" + ma.alt + "), back to CI09; four entry fixes 20 km-eq out (ZZAAN / E / S / W)" + SelfTestLinerFmsSVA.flags(geo, trans, onPath));
    }

    /** A database: the FULL station's airport ZZAA with its approach, a pilot airport ОРИГ 3 km-eq west. */
    static NavDb db(LinerIls.Station st) {
        List<LinerIls.Station> l = new ArrayList<LinerIls.Station>();
        l.add(st);
        List<NavDb.Airport> pa = new ArrayList<NavDb.Airport>();
        pa.add(NavDb.pilot("ОРИГ", 0, -3000, 0, 64, 90, 300));
        NavDb db = NavDb.build(0, l, null, pa, null, null);
        NavDb.Airport a = db.airport("ZZAA");
        if (a != null) {
            a.src = NavDb.SRC_TOWER;
            a.apprs.add(approach(st));
            for (ApproachMath.Fix f : approach(st).trans) {
                db.wpts.add(NavDb.wpt(f.id, NavDb.SRC_TOWER, 0, f.x, f.z));
            }
        }
        return db;
    }

    private static void approachFms() {
        LinerIls.Station st = SelfTestAirportSVA.full(1000, 300, 30, 25, 15, 3);
        NavDb db = db(st);
        LinerFms f = new LinerFms();
        f.setFromTo(db.airport("ОРИГ"), db.airport("ZZAA"));
        ApproachMath.Approach a = db.airport("ZZAA").approach("ILS09");
        boolean ok = f.insertApproach(db.airport("ZZAA"), a, "ZZAAN");
        boolean tmp = f.tmp != null && f.pts.size() == 4;
        List<String> ids = new ArrayList<String>();
        for (LinerFms.Wp w : f.tmp) {
            ids.add(w.id + (w.kind == LinerFms.K_MISSED ? "*" : "") + (w.cst == LinerFms.C_AT ? "=" : w.cst == LinerFms.C_ABOVE ? "+" : "") + (w.cst != 0 ? w.alt : ""));
        }
        f.tmpInsert();
        LinerFms.Wp d = f.dest();
        boolean plan = ok && tmp && d != null && "09".equals(d.rwy) && d.ils.equals(st.key()) && "ILS09".equals(f.appr) && "ZZAAN".equals(f.via);
        String expect = "[ОРИГ09, , ZZAAN+" + a.trans.get(0).alt + ", CI09+" + a.fix(ApproachMath.F_IF).alt + ", FF09=" + a.fix(ApproachMath.F_FAF).alt + ", ZZAA09, MA09*+"
                + (64 + ApproachMath.MA_H) + ", CI09*=" + a.fix(ApproachMath.F_FAF).alt + "]";
        // again with another transition: the old approach's entries replaced, not doubled; the runway changed back - gone
        f.insertApproach(db.airport("ZZAA"), a, "");
        f.tmpInsert();
        int n1 = f.pts.size();
        f.setDestRwy(db.airport("ZZAA"), "09");
        int procLeft = 0;
        for (LinerFms.Wp w : f.pts) {
            procLeft += w.proc ? 1 : 0;
        }
        NBTTagCompound t = new NBTTagCompound();
        LinerFms g = new LinerFms();
        f.insertApproach(db.airport("ZZAA"), a, "ZZAAS");
        f.tmpInsert();
        f.writeNBT(t);
        g.readNBT(t);
        boolean nbt = "ILS09".equals(g.appr) && "ZZAAS".equals(g.via) && g.pts.size() == f.pts.size() && g.pts.get(2).proc;
        check(plan && ids.toString().equals(expect) && n1 == 7 && procLeft == 0 && f.pts.size() == 8 && nbt,
                "airport B2 approach into the FMS: the TMPY " + ids + " - the transition, IF, FAF with their constraints, the threshold 09 with its ILS (the"
                        + " auto-tuning), the missed approach; again without a transition - " + n1 + " entries (replaced); the runway set again - the approach"
                        + " gone; saved and read" + SelfTestLinerFmsSVA.flags(plan, ids.toString().equals(expect), n1 == 7, procLeft == 0, nbt));
    }

    static LinerMcdu.Env env(NavDb db) {
        LinerMcdu.Env e = SelfTestLinerFmsSVA.env(db);
        e.x = -3000;
        e.z = 0;
        return e;
    }

    private static void approachMcdu() {
        LinerIls.Station st = SelfTestAirportSVA.full(1000, 300, 30, 25, 15, 3);
        NavDb db = db(st);
        LinerMcdu.Env e = env(db);
        LinerSystems L = SelfTestLinerSVA.cold();
        SelfTestLinerFmsSVA.enter(L, 0, "ОРИГ/ZZAA", true, 1, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_FPLN, e);
        // the destination's line: LAT REV - ЗАХОДЫ>
        int dl = -1;
        List<LinerMcdu.Line> ls = LinerMcdu.lines(L.fms);
        for (int i = 0; i < ls.size(); i++) {
            if (ls.get(i).idx >= 0 && L.fms.shown().get(ls.get(i).idx).kind == LinerFms.K_DEST) {
                dl = i;
            }
        }
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_L1 + dl, e);
        String latrev = screen(L, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_R1 + 1, e);
        String arr0 = screen(L, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_L1, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_R1 + 2, e);              // VIA ZZAAE (the second of the list: N, E...)
        String arr = screen(L, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_R1 + 5, e);              // ВСТАВИТЬ* - the TMPY on F-PLN
        String fpln = screen(L, e);
        boolean tmpy = L.fms.tmp != null && L.mcdu.sides[0].page == LinerMcdu.P_FPLN;
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_R1 + 5, e);
        boolean ins = L.fms.tmp == null && "ILS09".equals(L.fms.appr) && "ZZAAE".equals(L.fms.via);
        check(latrev.contains("ЗАХОДЫ>") && arr0.contains("ЗАХОДЫ ZZAA") && arr0.contains("<ILS09") && arr.contains("*ILS09") && arr.contains("ZZAAE*")
                        && arr.contains("ВСТАВИТЬ*") && fpln.contains("FF09") && tmpy && ins,
                "airport B2 MCDU ЗАХОДЫ: the destination's LAT REV offers ЗАХОДЫ>, the page lists ILS09 (frequency, CAT) and its transitions; ILS09 via"
                        + " ZZAAE - ВСТАВИТЬ* - the yellow plan with FF09 - ВСТАВИТЬ" + SelfTestLinerFmsSVA.flags(latrev.contains("ЗАХОДЫ>"),
                        arr0.contains("<ILS09"), arr.contains("*ILS09"), fpln.contains("FF09"), tmpy, ins));
        if (!arr.contains("*ILS09") || !fpln.contains("FF09")) {
            System.out.println("[SVA-TEST] B2 ARR:\n" + arr + "\nF-PLN:\n" + fpln);
        }
        // without approaches: a message
        LinerSystems M = SelfTestLinerSVA.cold();
        NavDb plain = SelfTestLinerFmsSVA.navDb();
        LinerMcdu.Env e2 = SelfTestLinerFmsSVA.env(plain);
        SelfTestLinerFmsSVA.enter(M, 0, "ГЛВН/ОЗРН", true, 1, e2);
        SelfTestLinerFmsSVA.key(M, 0, LinerMcdu.K_ATC, e2);
        SelfTestLinerFmsSVA.key(M, 0, LinerMcdu.K_L1 + 5, e2);
        check(M.mcdu.sides[0].page == LinerMcdu.P_ATC && "sva.mcdu.msg.noAppr".equals(M.mcdu.sides[0].msg),
                "airport B2 MCDU ЗАХОДЫ: a destination without a tower's approaches - «НЕТ СХЕМ ЗАХОДА»");
    }

    static String screen(LinerSystems L, LinerMcdu.Env e) {
        return L.mcdu.render(L, 0, e).plain(res(ru));
    }

    /**
     * The plan with ILS09 flown by LinerSimSVA: from the IF (1000 out, 30 left, 45 up - B1's approach, a 20 deg intercept), AP
     * 1 + 2 with APPR - the ILS tuned by the plan (no RAD NAV choice), LOC / G/S, the CAT III autoland to a stop on the runway.
     */
    private static void approachFlight() {
        LinerIls.Station st = SelfTestAirportSVA.full(1000, 300, 30, 25, 15, 3);
        NavDb db = db(st);
        LinerSimSVA s = LinerSimSVA.flying(45, 1.3, 110, 3);
        s.gearDown();
        s.L.set(LinerControls.SPD_BRK, 0);
        s.L.control(LinerControls.ABRK_MED.index, LinerControls.A_PRIMARY);
        s.ils.add(st);
        s.b.x = 0;
        s.b.z = -30;
        LinerFms f = s.L.fms;
        f.setFromTo(db.airport("ОРИГ"), db.airport("ZZAA"));
        f.insertApproach(db.airport("ZZAA"), db.airport("ZZAA").approach("ILS09"), "");
        f.tmpInsert();
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_APPR);
        s.click(LinerControls.FCU_AP2);
        boolean tuned = false;
        int land = -1, stop = -1;
        for (int t = 0; t < 3000; t++) {
            s.tick();
            LinerAfs F = s.L.afs;
            if (F.thr == LinerAfs.T_RETARD && F.lever > 0) {
                F.lever = 0;
            }
            tuned |= F.ils != null && F.ils.key().equals(st.key()) && F.ilsChosen.length() == 0 && F.ilsPlan.equals(st.key());
            land = land < 0 && F.lat == LinerAfs.L_LAND ? t : land;
            if (s.b.ground && s.b.speed < 0.005) {
                stop = t;
                break;
            }
        }
        check(tuned && land > 0 && stop > 0 && s.b.x > 1000 && s.b.x < 1300 && Math.abs(s.b.z) < 1.5,
                "airport B2 approach flight: the plan's ILS09 tunes the ILS by itself (no RAD NAV choice), LAND at " + land + ", stopped " + f(s.b.x - 1000)
                        + " past the threshold, " + f(s.b.z) + " off the centre line" + SelfTestLinerFmsSVA.flags(tuned, land > 0, stop > 0));
    }

    // ---------------------------------------------------------------- the chart

    private static void chart() {
        LinerIls.Station st = SelfTestAirportSVA.full(1000, 300, 30, 25, 15, 3);
        ApproachMath.Approach a = approach(st);
        ChartMath.Chart c = ChartMath.of("UBTW", "Test", a, 300, 5, true, false, true, 7);
        NBTTagCompound t = new NBTTagCompound();
        ChartMath.write(c, t);
        ChartMath.Chart r = ChartMath.read(t);
        boolean round = r != null && "UBTW".equals(r.icao) && "Test".equals(r.name) && "09".equals(r.rwy) && "IABC".equals(r.ils) && r.freq == 10950 && r.cat == 3
                && r.version == 7 && r.als && !r.papi && r.rwyLights && r.fixes.size() == 2 && r.missed.size() == 2 && r.trans.size() == 4
                && r.fix(ApproachMath.F_FAF).alt == a.fix(ApproachMath.F_FAF).alt && r.course == 90 && r.len == 300 && r.dh() == 0 && r.rvrMin() == 75
                && ChartMath.read(new NBTTagCompound()) == null;
        boolean ver = !ChartMath.outdated(7, 7) && ChartMath.outdated(7, 8) && ChartMath.outdated(7, -1);
        List<ChartMath.Prim> l = ChartMath.layout(r, false), lo = ChartMath.layout(r, true);
        boolean stamp = false, keys = true, inside = true;
        Set<String> missing = new HashSet<String>();
        for (ChartMath.Prim p : lo) {
            stamp |= p.kind == ChartMath.TEXT && p.key && "sva.chart.outdated".equals(p.text);
            if (p.kind == ChartMath.TEXT && p.key && (ru.get(p.text) == null || en.get(p.text) == null)) {
                keys = false;
                missing.add(p.text);
            }
            inside &= p.x0 >= -1 && p.x0 <= ChartMath.W + 1 && p.y0 >= -1 && p.y0 <= ChartMath.H + 1;
        }
        boolean noStamp = true;
        for (ChartMath.Prim p : l) {
            noStamp &= !(p.kind == ChartMath.TEXT && "sva.chart.outdated".equals(p.text));
        }
        ChartMath.Chart c1 = ChartMath.of("UBTW", "", ApproachMath.ils("09", 1000, 0, 64, 90, null, null), 300, 5, false, true, false, 1);
        check(round && ver && stamp && noStamp && keys && inside && l.size() > 60 && c1.dh() == (int) IlsCat.minHeight(0) && c1.trans.isEmpty(),
                "airport B2 approach chart: the data round-trips the item's NBT (airport, runway, ILS, fixes, missed approach, transitions, lights,"
                        + " version 7); outdated when the tower's version moves or it is gone; the sheet's " + l.size() + " primitives inside it, «УСТАРЕЛА»"
                        + " stamped only when outdated, every text key in ru + en" + (missing.isEmpty() ? "" : " - missing " + missing)
                        + SelfTestLinerFmsSVA.flags(round, ver, stamp, keys, inside));
    }

    // ---------------------------------------------------------------- the taxi graph

    /** A taxiway along x (z 0) every 3 from 0 to 27, a stop bar at 30; a branch south at x 12 (3, 6, 9) to a stand at 12. */
    static List<TaxiGraph.Node> nodes() {
        List<TaxiGraph.Node> n = new ArrayList<TaxiGraph.Node>();
        for (int x = 0; x <= 27; x += 3) {
            n.add(new TaxiGraph.Node("c" + x, x, 64, 0, TaxiGraph.CENTRE));
        }
        n.add(new TaxiGraph.Node("stop", 30, 64, 0, TaxiGraph.STOP));
        for (int z = 3; z <= 9; z += 3) {
            n.add(new TaxiGraph.Node("b" + z, 12, 64, z, TaxiGraph.CENTRE));
        }
        n.add(new TaxiGraph.Node("stand", 12, 64, 12, TaxiGraph.STAND));
        return n;
    }

    static List<String> keys(List<TaxiGraph.Node> n, List<Integer> r) {
        List<String> l = new ArrayList<String>();
        for (Integer i : r) {
            l.add(n.get(i.intValue()).key);
        }
        return l;
    }

    private static void taxiGraph() {
        List<TaxiGraph.Node> n = nodes();
        List<List<Integer>> adj = TaxiGraph.build(n, null, null);
        int c3 = TaxiGraph.index(n, "c3"), c6 = TaxiGraph.index(n, "c6"), c12 = TaxiGraph.index(n, "c12"), b3 = TaxiGraph.index(n, "b3");
        boolean links = adj.get(c3).size() == 2 && adj.get(c3).contains(Integer.valueOf(TaxiGraph.index(n, "c0"))) && adj.get(c3).contains(Integer.valueOf(c6))
                && !adj.get(c3).contains(Integer.valueOf(TaxiGraph.index(n, "c9"))) && adj.get(c12).size() == 3 && adj.get(c12).contains(Integer.valueOf(b3));
        int stand = TaxiGraph.index(n, "stand"), stop = TaxiGraph.holdingPoint(n, 31, 0);
        List<Integer> r = TaxiGraph.route(n, adj, stand, stop);
        String expect = "[stand, b9, b6, b3, c15, c18, c21, c24, c27, stop]";
        boolean route = keys(n, r).toString().equals(expect) && Math.abs(TaxiGraph.length(n, r) - (24 + Math.sqrt(18))) < 1e-9
                && !adj.get(b3).contains(Integer.valueOf(TaxiGraph.index(n, "c18")));
        Set<String> cut = new HashSet<String>();
        cut.add(TaxiGraph.link("c12", "b3"));
        cut.add(TaxiGraph.link("c15", "b3"));
        cut.add(TaxiGraph.link("c9", "b3"));
        boolean noWay = TaxiGraph.route(n, TaxiGraph.build(n, null, cut), stand, stop).isEmpty();
        Set<String> extra = new HashSet<String>();
        extra.add(TaxiGraph.link("stand", "c24"));
        List<Integer> r2 = TaxiGraph.route(n, TaxiGraph.build(n, extra, cut), stand, stop);
        boolean drawn = keys(n, r2).toString().equals("[stand, c24, c27, stop]");
        check(links && route && noWay && drawn && TaxiGraph.link("a", "b").equals(TaxiGraph.link("b", "a")),
                "airport B2 taxi graph: two lights link when each is the other's nearest that way (a line neighbour to neighbour, the crossing's arms,"
                        + " the corner's diagonal, no long skips); stand -> holding point " + keys(n, r) + " (" + f(TaxiGraph.length(n, r)) + " blocks); a"
                        + " link cut on the map - no way; a link drawn - " + keys(n, r2)
                        + SelfTestLinerFmsSVA.flags(links, route, noWay, drawn));
    }

    private static void taxiLights() {
        List<TaxiGraph.Node> n = nodes();
        List<Integer> r = TaxiGraph.route(n, TaxiGraph.build(n, null, null), TaxiGraph.index(n, "stand"), TaxiGraph.index(n, "stop"));
        int p0 = TaxiGraph.progress(n, r, 0, 12.5, 12.5);
        Set<Integer> l0 = TaxiGraph.lit(r, p0, 4);
        int p1 = TaxiGraph.progress(n, r, p0, 18.4, 0.6);
        Set<Integer> l1 = TaxiGraph.lit(r, p1, 4);
        int back = TaxiGraph.progress(n, r, p1, 12.5, 9.5);                   // a glimpse of an earlier light: never back
        int lost = TaxiGraph.progress(n, r, p1, 200, 200);
        int p2 = TaxiGraph.progress(n, r, p1, 30.5, 0.5);
        boolean prog = p0 == 0 && l0.size() == 5 && l0.contains(r.get(0)) && l0.contains(r.get(4)) && !l0.contains(r.get(5)) && p1 == 5 && !l1.contains(r.get(4))
                && l1.contains(r.get(5)) && l1.contains(r.get(9)) && back == 5 && lost == 5 && p2 == r.size() - 1 && TaxiGraph.arrived(r, p2)
                && !TaxiGraph.arrived(r, p1);
        boolean stop = TaxiGraph.stopLit(true, 0, 100) && !TaxiGraph.stopLit(true, 200, 100) && TaxiGraph.stopLit(true, 200, 200) && !TaxiGraph.stopLit(false, 0, 100);
        check(prog && stop && BlockTaxiLightSVA.KINDS == 4 && com.sva.block.AirportDataSVA.K_STAND - com.sva.block.AirportDataSVA.K_TAXI == BlockTaxiLightSVA.STAND,
                "airport B2 follow-me: at the stand the next 4 lit ahead; at the crossing (light 5) the ones behind out, 5..9 lit; the progress never goes"
                        + " back (a light seen behind, the aircraft off the route); at the holding point - arrived; the stop bar: burns with the tower on,"
                        + " dropped until its time, back after" + SelfTestLinerFmsSVA.flags(prog, stop));
    }

    // ---------------------------------------------------------------- the MCDU's datalink

    /** A datalink in memory (the self-test's ATC). */
    static final class FakeAtc implements LinerMcdu.Datalink {
        final Cpdlc.Log log = new Cpdlc.Log();
        final List<String> down = new ArrayList<String>();
        long now = 1000;
        String atis = AtisMath.pack(AtisMath.text("UBTW", "", 1, 6000, obs()));

        @Override
        public String unit() {
            return "UBTW";
        }

        @Override
        public String unitName() {
            return "TEST";
        }

        @Override
        public String[] atis(String icao) {
            return "UBTW".equals(icao) ? new String[] {"1", atis} : null;
        }

        @Override
        public List<Cpdlc.Msg> msgs() {
            List<Cpdlc.Msg> l = new ArrayList<Cpdlc.Msg>();
            for (Cpdlc.Msg m : log.msgs) {
                l.add(m.copy());
            }
            return l;
        }

        @Override
        public boolean down(int type, int arg, String s) {
            down.add(type + ":" + arg + ":" + s);
            log.add(log.make(false, type, arg, s, "", -1, now));
            return true;
        }

        @Override
        public boolean respond(int id, int r) {
            return log.respond(id, r, now) != null;
        }

        @Override
        public void read(int id) {
            if (id < 0) {
                log.readAll();
            } else if (log.byId(id) != null) {
                log.byId(id).read = true;
            }
        }

        @Override
        public void loaded(int id) {
            if (log.byId(id) != null) {
                log.byId(id).loaded = true;
            }
        }
    }

    private static void mcduDatalink() {
        NavDb db = SelfTestLinerFmsSVA.navDb();
        LinerMcdu.Env e = SelfTestLinerFmsSVA.env(db);
        FakeAtc atc = new FakeAtc();
        e.atc = atc;
        LinerSystems L = SelfTestLinerSVA.cold();
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_ATC, e);
        String menu = screen(L, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_L1 + 1, e);
        String atisPage = screen(L, e);
        SelfTestLinerFmsSVA.enter(L, 0, "QQQQ", true, 6, e);
        String noAtis = screen(L, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_L1 + 5, e);
        boolean atisOk = menu.contains("СВЯЗЬ ATC") && menu.contains("UBTW TEST") && atisPage.contains("ATIS UBTW B") && atisPage.contains("ИНФОРМАЦИЯ БРАВО")
                && atisPage.contains("ПОЛОСА В ИСПОЛЬЗОВАНИИ") && noAtis.contains("НЕТ ATIS") && L.mcdu.sides[0].page == LinerMcdu.P_ATC;
        // an uplink: НАБОР ДО 150 - read on the page, WILCO, ЗАГРУЗИТЬ into the FCU
        Cpdlc.Msg up = atc.log.add(atc.log.make(true, Cpdlc.U_CLIMB, 150, "", "", -1, 990));
        up.tower = "UBTW";
        String menu2 = screen(L, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_R1 + 1, e);
        String msg = screen(L, e);
        boolean read = up.read;
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_L1 + 4, e);
        String after = screen(L, e);
        L.afs.alt = 90;
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_R1 + 5, e);
        boolean loaded = L.afs.alt == 150 && up.loaded && up.state == Cpdlc.S_WILCO && !screen(L, e).contains("ЗАГРУЗИТЬ");
        boolean msgOk = menu2.contains("НОВЫХ 1") && msg.contains("НАБОР ДО 150 М") && msg.contains("<ПРИНЯЛ") && msg.contains("НЕ МОГУ>") && read
                && after.contains("ЗАГРУЗИТЬ*") && after.contains("ПРИНЯТО") && loaded;
        // a newer uplink while the page follows the newest: read by the MCDU's tick
        Cpdlc.Msg up2 = atc.log.add(atc.log.make(true, Cpdlc.U_HDG, 270, "", "", -1, 1000));
        L.mcdu.sides[0].msgId = -1;
        L.mcdu.tick(L, e);
        boolean follow = up2.read;
        // the requests: taxi, climb 200 (the scratchpad), free text, nothing without a unit
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_ATC, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_L1 + 2, e);
        SelfTestLinerFmsSVA.key(L, 0, LinerMcdu.K_L1, e);
        SelfTestLinerFmsSVA.enter(L, 0, "200", false, 4, e);
        SelfTestLinerFmsSVA.enter(L, 0, "TEST MSG", false, 5, e);
        String req = screen(L, e);
        boolean reqOk = atc.down.toString().equals("[" + Cpdlc.D_TAXI + ":0:, " + Cpdlc.D_CLIMB + ":200:, " + Cpdlc.D_FREE + ":0:TEST MSG]")
                && req.contains("ОТПРАВЛЕНО") && req.contains("ЖДЁТ ОТВЕТА");
        check(atisOk && msgOk && follow && reqOk,
                "airport B2 MCDU СВЯЗЬ: the unit UBTW, ATIS by airport (information B, the lines wrapped; «НЕТ ATIS» for another), the new uplink «НАБОР ДО"
                        + " 150 М» read on the message page, <ПРИНЯЛ - ЗАГРУЗИТЬ* sets the FCU's altitude window (the pilot pulls / pushes); the page"
                        + " following the newest reads it; ЗАПРОС: РУЛЕНИЕ, НАБОР 200, free text" + SelfTestLinerFmsSVA.flags(atisOk, msgOk, follow, reqOk));
        if (!(atisOk && msgOk)) {
            System.out.println("[SVA-TEST] B2 MCDU:\n" + menu + "\n" + atisPage + "\n" + msg + "\n" + after);
        }
    }

    private static void linerSync() {
        LinerSystems L = SelfTestLinerSVA.cold();
        L.atcUnread = 2;
        String s = L.pack();
        LinerSystems C = new LinerSystems();
        C.unpack(s);
        LinerSystems O = new LinerSystems();
        O.atcUnread = 5;
        O.unpack(new LinerSystems().pack());
        L.control(LinerControls.ATC_MSG_L.index, LinerControls.A_PRIMARY);
        check(C.atcUnread == 2 && O.atcUnread == 0 && L.atcAck && L.atcUnread == 0 && LinerControls.ATC_MSG_R.panel == LinerControls.GS
                        && s.length() < SelfTestLinerW7SVA.SYNC_LIMIT,
                "airport B2 liner: the unread uplinks ride the sync (the «ATC MSG» lamps, the PFD / ND memo); pressing ATC MSG reads them ("
                        + s.length() + " chars)");
    }

    private static void wrap() {
        LinerMcdu.Screen sc = LinerMcdu.Screen.unpack("2\u00010\u0001" + (LinerMcdu.A_WRAP + 3) + "\u00012\u00010\u0001@sva.cpdlc.taxiRoute|27L|840");
        String p = sc.plain(res(ru));
        String[] rows = p.split("\n");
        String sev = LinerMcdu.Screen.unpack("2\u00010\u00010\u00012\u00010\u0001@sva.cpdlc.u.8|#sva.cpdlc.rep.1|").plain(res(ru)).split("\n")[2];
        check(rows[2].trim().equals("РУЛИТЕ К ПОЛОСЕ 27L ПО") && rows[3].trim().equals("ЗЕЛЁНЫМ ОГНЯМ, 840 М") && rows[4].trim().length() == 0
                        && sev.startsWith("ДОЛОЖИТЕ ПОЛОСУ ОСВОБОДИ"),
                "airport B2 MCDU text: a long message wrapped over its rows on the reader's side («" + rows[2].trim() + " / " + rows[3].trim()
                        + "»), a «#key» argument translated");
    }

    // ---------------------------------------------------------------- lang

    private static void lang() {
        List<String> missing = new ArrayList<String>();
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < AtisMath.LETTERS; i++) {
            keys.add("sva.atis.letter." + i);
        }
        for (String k : AtisMath.RMK_KEYS) {
            keys.add("sva.atis.rmk." + k);
        }
        for (int i = 0; i < Cpdlc.U_TYPES; i++) {
            keys.add("sva.cpdlc.u." + i);
        }
        for (int i = Cpdlc.D_FIRST; i <= Cpdlc.D_LAST; i++) {
            keys.add("sva.cpdlc.d." + (i - Cpdlc.D_FIRST));
        }
        for (int i = 0; i < Cpdlc.REPORTS; i++) {
            keys.add("sva.cpdlc.rep." + i);
        }
        for (String k : Cpdlc.STATE_KEYS) {
            keys.add("sva.cpdlc.state." + k);
        }
        for (String k : AutoTower.STATE_KEYS) {
            keys.add("sva.tower.gui.st." + k);
        }
        for (int i = 1; i <= 3; i++) {
            keys.add("sva.tower.gui.req." + i);
        }
        for (String k : new String[] {"traffic", "rwy", "atis", "map", "log", "set"}) {
            keys.add("sva.tower.gui.tab." + k);
        }
        for (String k : new String[] {"taxi", "lineup", "takeoff", "land", "goAround", "holdShort", "stby", "unable"}) {
            keys.add("sva.tower.gui.up." + k);
        }
        for (String k : BlockTaxiLightSVA.KEYS) {
            keys.add("tile.siliconagevehicles.taxiLight." + k + ".name");
            keys.add("sva.tooltip.apt.taxi." + k + ".summary");
            keys.add("sva.tooltip.apt.taxi." + k + ".details");
        }
        keys.add("tile.siliconagevehicles.tower.name");
        keys.add("item.siliconagevehicles.approachChart.name");
        keys.add("sva.apt.issue." + IlsCat.ISSUE_KEYS[IlsCat.I_TOWER_OFF]);
        keys.add("sva.apt.hint." + IlsCat.ISSUE_KEYS[IlsCat.I_TOWER_OFF]);
        keys.add("sva.mcdu.src." + NavDb.SRC_KEYS[NavDb.SRC_TOWER]);
        keys.add("sva.liner.ctl.atcMsgL");
        keys.add("sva.liner.ctl.atcMsgR");
        for (String k : keys) {
            if (ru == null || en == null || !ru.containsKey(k) || !en.containsKey(k)) {
                missing.add(k);
            }
        }
        // the MCDU's own lines stay within its 24 columns (the ones not wrapped)
        List<String> wide = new ArrayList<String>();
        for (String k : new String[] {"sva.mcdu.atc.title", "sva.mcdu.atc.atis", "sva.mcdu.atc.msgs", "sva.mcdu.atc.req", "sva.mcdu.atc.log",
            "sva.mcdu.atc.arr", "sva.mcdu.atcmsg.wilco", "sva.mcdu.atcmsg.unable", "sva.mcdu.atcmsg.stby", "sva.mcdu.atcmsg.load", "sva.mcdu.atcreq.taxi",
            "sva.mcdu.atcreq.ready", "sva.mcdu.atcreq.landing", "sva.mcdu.atcreq.climb", "sva.mcdu.atcreq.free", "sva.mcdu.arr.insert", "sva.mcdu.arr.noVia",
            "sva.mcdu.latrev.arr", "sva.mcdu.msg.noAtc", "sva.mcdu.msg.loaded", "sva.mcdu.msg.noAppr", "sva.mcdu.msg.sent"}) {
            for (Map<String, String> m : new Map[] {ru, en}) {
                String v = m == null ? null : m.get(k);
                if (v != null && v.length() > 22) {
                    wide.add(k + "=" + v);
                }
            }
        }
        check(missing.isEmpty() && wide.isEmpty(), "airport B2 lang: the letters, remarks, CPDLC texts / states / reports, the tower screen's families, the"
                + " blocks, the tooltips, the ILS issue, the MCDU source, the ATC MSG buttons in ru + en; the MCDU's labels within its width"
                + (missing.isEmpty() ? "" : " - missing " + missing) + (wide.isEmpty() ? "" : " - too wide " + wide));
        // the wave B1 lang check's devices still resolve (the kinds grew)
        check(RunwayLayout.LIGHTS_TOWER == -2 && RunwayLayout.step(RunwayLayout.LIGHTS_TOWER, true, false, 10000) == RunwayLayout.step(RunwayLayout.LIGHTS_AUTO,
                true, false, 10000), "airport B2 lights: an end's «ВЫШКА» mode is AUTO without a tower");
    }
}
