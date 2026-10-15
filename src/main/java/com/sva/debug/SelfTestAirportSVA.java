package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sva.airport.AptScale;
import com.sva.airport.FlightCheck;
import com.sva.airport.IlsBeam;
import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.airport.MarkerMath;
import com.sva.airport.PlannerMath;
import com.sva.airport.RunwayLayout;
import com.sva.airport.RvrMath;
import com.sva.airport.WindField;
import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.NavDb;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Airport wave B1 self-tests (plan-next §9, items 13-26, 39, 40) - pure logic on the dedicated server: the scale, the beam's
 * geometry (the LOC's full scale, its peg and coverage, the back course; the glide slope's sine with its false lobes at 2θ
 * reversed and 3θ normal; the bends), the category from the placement / the monitor / the areas, the pairing and the
 * runway names (L / C / R, the magnetic course), the DME, the markers, the RVR and the category it needs, the wind field's
 * determinism, PAPI, the ALS sequencing, the paint per width, the lights, the planner's places, the flight inspection's
 * state machine, the Morse keying, the station's save and sync, the AFS' capability - and LinerSimSVA flights on a FULL
 * station: the CAT III autoland, the CAT I disconnect at the minimum, a false glide slope captured from above, the monitor's
 * fault (the ECAM's «ILS ОТКАЗ»); the lang keys of the dynamic families.
 */
final class SelfTestAirportSVA {

    private SelfTestAirportSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(v));
    }

    static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    static void run() throws java.io.IOException {
        scale();
        beam();
        lobes();
        backCourse();
        bends();
        category();
        pairing();
        naming();
        dme();
        markers();
        rvr();
        wind();
        papi();
        als();
        paint();
        lights();
        planner();
        flightCheck();
        morse();
        station();
        capability();
        autolandCat3();
        cat1Minimum();
        falseGlideSlope();
        monitorFault();
        lang();
    }

    /** A FULL station over a runway along +x: threshold (tx, 0) at Y 64, course 090, LOC `beyond` past a `len` runway, G/S 25 / 15. */
    static LinerIls.Station full(double tx, double len, double beyond, double gsSet, double gsOff, int cat) {
        LinerIls.Station s = new LinerIls.Station();
        s.kind = LinerIls.KIND_FULL;
        s.bx = (int) (tx + len + beyond);
        s.by = 64;
        s.bz = 0;
        s.fx = tx;
        s.fz = 0;
        s.elev = 64;
        s.course = 90;
        s.angle = 3;
        s.ident = "IABC";
        s.freq = 10950;
        s.lx = tx + len + beyond;
        s.lz = 0;
        s.gx = tx + gsSet;
        s.gz = gsOff;
        s.gy = 64;
        s.cat = cat;
        s.rwy = "09";
        s.apt = "ZZAA";
        s.rwyLen = (int) len;
        return s;
    }

    // ---------------------------------------------------------------- the scale

    private static void scale() {
        check(near(AptScale.km(720), 7.2, 1e-9) && "7,2".equals(AptScale.kmText(720, ',')) && near(AptScale.metres(1.5), 15, 1e-9)
                        && near(AptScale.blocks(550), 55, 1e-9),
                "airport scale: 1 block = 10 m-eq (OM 720 blocks = 7,2 km, TCH 1.5 blocks = 15 m)");
    }

    // ---------------------------------------------------------------- the beam

    private static void beam() {
        LinerIls.Station s = full(0, 300, 30, 25, 15, 3);
        double hg = Math.sqrt(525 * 525 + 15 * 15);
        double y = 64 + hg * Math.tan(Math.toRadians(3));
        LinerIls.Dev on = LinerIls.dev(s, -500, y, 0, 4000);
        check(on.locValid && on.gsValid && Math.abs(on.loc) < 1e-6 && Math.abs(on.gs) < 1e-3 && on.lobe == 1 && near(on.locDot, IlsBeam.LOC_DOT, 1e-9),
                "airport beam: on the centre line and the glide path - LOC 0, G/S " + f(on.gs) + " deg, both valid, the main lobe");
        // LOC full scale 2.5 deg = 2 dots, seen from the array (830 blocks)
        double fs = Math.tan(Math.toRadians(2.5)) * 830;
        LinerIls.Dev r = LinerIls.dev(s, -500, y, fs, 4000);
        LinerIls.Dev l = LinerIls.dev(s, -500, y, -fs / 2, 4000);
        check(near(LinerIls.locDots(r), 2, 1e-3) && near(LinerIls.locDots(l), -1, 0.01) && near(r.cross, fs, 0.05),
                "airport beam: LOC full scale 2.5 deg = 2 dots (right +), half of it 1 dot; the indicated cross-track " + f(r.cross));
        LinerIls.Dev pegged = LinerIls.dev(s, -500, y, Math.tan(Math.toRadians(10)) * 830, 4000);
        check(pegged.locValid && near(pegged.loc, IlsBeam.LOC_SAT, 1e-9) && LinerIls.locDots(pegged) > 2.3,
                "airport beam: 10 deg off - the needle pegged (" + f(pegged.loc) + " deg), the clearance signal still valid");
        LinerIls.Dev wide = LinerIls.dev(s, -1170, 120, Math.tan(Math.toRadians(30)) * 1500, 4000);
        LinerIls.Dev out = LinerIls.dev(s, -400, 120, Math.tan(Math.toRadians(40)) * 730, 4000);
        LinerIls.Dev far = LinerIls.dev(s, -2100, 160, 0, 4000);
        LinerIls.Dev tooFar = LinerIls.dev(s, -2300, 160, 0, 4000);
        check(!LinerIls.dev(s, -1370, 120, Math.tan(Math.toRadians(30)) * 1700, 4000).inCover && wide.inCover && !out.inCover && far.inCover
                        && !tooFar.inCover,
                "airport beam: LOC coverage - 35 deg to 1800 blocks (18 km-eq), 10 deg to 2500 (25 km-eq), nothing beyond 35 deg");
        // the G/S: + above, the sine's slope 1 at θ, full scale 0.7 deg = 2 dots near it; coverage +-8 deg laterally
        double e1 = 3.1, yAbove = 64 + hg * Math.tan(Math.toRadians(e1));
        LinerIls.Dev above = LinerIls.dev(s, -500, yAbove, 0, 4000);
        LinerIls.Dev side = LinerIls.dev(s, -500, y, Math.tan(Math.toRadians(9)) * 525, 4000);
        check(near(above.gs, 0.1, 0.003) && above.above > 0 && near(LinerIls.gsDots(above), 0.1 / IlsBeam.GS_DOT, 0.02) && !side.gsValid && side.locValid,
                "airport beam: G/S 0.1 deg above - " + f(above.gs) + " deg (fly down), " + f(LinerIls.gsDots(above))
                        + " dots; 9 deg to the side of the course line - no glide slope");
        LinerIls.Dev under = LinerIls.dev(s, -500, 64 + hg * Math.tan(Math.toRadians(0.6)), 0, 4000);
        check(!under.gsValid && under.lobe == 0, "airport beam: under 0.3 θ no glide slope signal (lobe " + under.lobe + ")");
    }

    private static void lobes() {
        double th = 3;
        boolean nulls = Math.abs(IlsBeam.gsDdm(3, th)) < 1e-9 && Math.abs(IlsBeam.gsDdm(6, th)) < 1e-9 && Math.abs(IlsBeam.gsDdm(9, th)) < 1e-9;
        boolean main = IlsBeam.gsDdm(3.1, th) > 0 && IlsBeam.gsDdm(2.9, th) < 0;
        boolean rev6 = IlsBeam.gsDdm(6.1, th) < 0 && IlsBeam.gsDdm(5.9, th) > 0;
        boolean norm9 = IlsBeam.gsDdm(9.1, th) > 0 && IlsBeam.gsDdm(8.9, th) < 0;
        check(nulls && main && rev6 && norm9 && IlsBeam.lobe(6.05, th) == 2 && IlsBeam.lobe(8.9, th) == 3 && IlsBeam.reversed(2) && !IlsBeam.reversed(3)
                        && !IlsBeam.reversed(1),
                "airport G/S lobes: paths at 3, 6, 9 deg - the 6 deg one reversed (fly up above it), the 9 deg one normal (a false path an AP holds)");
        // at the 9 deg lobe the station says «on the path»: the indicated deviation ~0, the geometric one 6 deg
        LinerIls.Station s = full(0, 300, 30, 25, 15, 3);
        double hg = Math.sqrt(625 * 625 + 225);
        LinerIls.Dev f9 = LinerIls.dev(s, -600, 64 + hg * Math.tan(Math.toRadians(9)), 0, 4000);
        check(f9.gsValid && Math.abs(f9.gs) < 0.01 && Math.abs(f9.above) < 0.2 && f9.lobe == 3 && f9.elevDeg > 8.9,
                "airport G/S lobes: over the 9 deg lobe the needle centres (" + f(f9.gs) + " deg) - detectable only by the lobe / the elevation "
                        + f(f9.elevDeg) + " deg");
    }

    private static void backCourse() {
        LinerIls.Station s = full(0, 300, 30, 25, 15, 3);
        LinerIls.Dev bc = LinerIls.dev(s, 600, 90, 10, 4000);
        LinerIls.Dev fc = LinerIls.dev(s, -600, 90, 10, 4000);
        boolean capture = LinerIls.locCapture(bc, s, 270, 80, 1.3);
        check(bc.backCourse && bc.locValid && !bc.gsValid && bc.loc > 0 && fc.loc > 0 && !capture,
                "airport back course: behind the array the same signal - +z is still «right» (" + f(bc.loc) + " deg): reversed for a pilot flying 270;"
                        + " no G/S, the SA-320's LOC* never captures it");
    }

    private static void bends() {
        LinerIls.Station s = full(0, 300, 30, 25, 15, 3);
        s.bendLoc = 0.2;
        double hg = Math.sqrt(525 * 525 + 225);
        double y = 64 + hg * Math.tan(Math.toRadians(3));
        double min = 9, max = -9, sum = 0;
        int n = (int) IlsBeam.BEND_PERIOD;
        for (int t = 0; t < n; t++) {
            double v = LinerIls.dev(s, -500, y, 0, 4000, t).loc;
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        LinerIls.Dev d0 = LinerIls.dev(s, -500, y, 0, 4000, 7);
        LinerIls.Station c = s.copy();
        c.bendLoc = 0;
        check(near(max, 0.2, 0.01) && near(min, -0.2, 0.01) && Math.abs(sum / n) < 0.005 && Math.abs(LinerIls.dev(c, -500, y, 0, 4000, 7).loc) < 1e-9
                        && d0.loc != 0,
                "airport bends: a 0.2 deg LOC bend oscillates " + f(min) + ".." + f(max) + " over " + n + " ticks (mean " + f(sum / n)
                        + "); a clean station - none");
    }

    // ---------------------------------------------------------------- the category

    private static void category() {
        IlsCat.Setup u = PlannerMath.setup(3, 0, 0, 0, 0);
        IlsCat.Result r = IlsCat.compute(u);
        check(r.cat == 3 && r.flags == 0 && r.issues.isEmpty() && near(r.tch, IlsBeam.tch(25, 15, 3), 1e-9) && r.tch > 1.2 && r.tch < 1.9,
                "airport CAT: the planned layout, monitored, calibrated - CAT III, TCH " + f(r.tch) + " blocks (" + f(AptScale.metres(r.tch)) + " m-eq)");
        u.calibCat = 0;
        r = IlsCat.compute(u);
        check(r.cat == 1 && (r.flags & LinerIls.F_UNCAL) != 0 && r.placementCat == 3 && r.has(IlsCat.I_UNCAL),
                "airport CAT: before the flight inspection - CAT I (placement III)");
        u = PlannerMath.setup(3, 0, 0, 0, 0);
        u.locLat = 1.0;
        int lat1 = IlsCat.compute(u).cat;
        u.locLat = 2.0;
        int lat2 = IlsCat.compute(u).cat;
        u.locLat = 5;
        IlsCat.Result trip = IlsCat.compute(u);
        u.locMonitor = false;
        IlsCat.Result noMon = IlsCat.compute(u);
        check(lat1 == 2 && lat2 == 1 && trip.cat == 0 && (trip.flags & LinerIls.F_OFF) != 0 && trip.has(IlsCat.I_LOC_TRIP) && noMon.cat == 1
                        && (noMon.flags & LinerIls.F_OFF) == 0 && noMon.has(IlsCat.I_LOC_MON),
                "airport CAT: the array 1 block off - CAT II, 2 - CAT I, 5 - the monitor shuts it off; without a monitor CAT I and no shut-off");
        u = PlannerMath.setup(3, 1, 0, 0, 0);
        IlsCat.Result crit = IlsCat.compute(u);
        u = PlannerMath.setup(3, 6, 0, 0, 0);
        IlsCat.Result many = IlsCat.compute(u);
        u = PlannerMath.setup(3, 0, 5, 0, 0);
        IlsCat.Result gsMany = IlsCat.compute(u);
        u = PlannerMath.setup(3, 0, 0, 2, 0);
        IlsCat.Result sens = IlsCat.compute(u);
        check(crit.cat == 1 && near(crit.bendLoc, IlsCat.BEND_LOC, 1e-9) && many.cat == 0 && (many.flags & LinerIls.F_OFF) != 0 && gsMany.cat == 1
                        && (gsMany.flags & LinerIls.F_GS_FAIL) != 0 && (gsMany.flags & LinerIls.F_OFF) == 0 && sens.cat == 2 && sens.bendLoc == 0,
                "airport CAT: a block in the LOC critical area - CAT I and a " + f(crit.bendLoc) + " deg bend; six - out of tolerance, OFF; five in"
                        + " the G/S's - the glide slope off (LOC only); the sensitive area - CAT II without a bend");
        u = PlannerMath.setup(3, 0, 0, 0, 0);
        u.power = false;
        IlsCat.Result np = IlsCat.compute(u);
        u = PlannerMath.setup(3, 0, 0, 0, 0);
        u.gsSetback = 45;
        IlsCat.Result tch = IlsCat.compute(u);
        u = PlannerMath.setup(3, 0, 0, 0, 0);
        u.gsPresent = false;
        IlsCat.Result loc = IlsCat.compute(u);
        check(np.cat == 0 && (np.flags & LinerIls.F_NO_POWER) != 0 && tch.cat <= 2 && tch.has(IlsCat.I_TCH) && loc.cat == 1 && (loc.flags & LinerIls.F_NO_GS) != 0,
                "airport CAT: no power - off; the mast 45 blocks in - TCH " + f(tch.tch) + " (CAT " + tch.cat + "); no mast - a LOC approach, CAT I");
    }

    // ---------------------------------------------------------------- pairing and names

    static IlsPairing.End end(String k, double x, double z, double facing) {
        return new IlsPairing.End(k, 0, x, z, 65, facing, 5);
    }

    private static void pairing() {
        List<IlsPairing.End> ends = new ArrayList<IlsPairing.End>();
        ends.add(end("0:0:64:0", 0.5, 0.5, 90));
        ends.add(end("0:300:64:0", 300.5, 0.5, 270));
        ends.add(end("0:0:64:500", 0.5, 500.5, 92));                // looks 2 deg off - still pairs
        ends.add(end("0:300:64:500", 300.5, 500.5, 270));
        ends.add(end("0:0:64:900", 0.5, 900.5, 100));               // 10 deg off - no runway
        ends.add(end("0:300:64:900", 300.5, 900.5, 270));
        IlsPairing.magVar = 0;
        List<IlsPairing.Rwy> rws = IlsPairing.runways(ends);
        IlsPairing.Rwy r = rws.isEmpty() ? null : rws.get(0);
        check(rws.size() == 2 && r != null && near(r.course, 90, 1e-6) && near(r.len, 300, 1e-6) && "09".equals(r.nameA) && "27".equals(r.nameB),
                "airport pairing: two runway ends facing each other (+-3 deg) make a runway (" + rws.size() + " of 3 pairs), 300 long, «09» / «27»");
        // the stations: a LOC 30 beyond the east end, a mast 25 in / 15 right of the west threshold; another LOC beyond the west end
        IlsPairing.LocAnt l1 = loc("0:330:64:0", 330.5, 0.5, "IWE", 10950);
        IlsPairing.LocAnt l2 = loc("0:-30:64:0", -29.5, 0.5, "IEW", 10950);
        IlsPairing.LocAnt stray = loc("0:330:64:60", 330.5, 60.5, "IXX", 11030);
        IlsPairing.GsAnt g = new IlsPairing.GsAnt();
        g.key = "0:25:64:15";
        g.x = 25.5;
        g.z = 15.5;
        g.y = 64;
        g.angle = 3;
        List<IlsPairing.LocAnt> locs = new ArrayList<IlsPairing.LocAnt>();
        locs.add(l1);
        locs.add(l2);
        locs.add(stray);
        List<IlsPairing.GsAnt> gss = new ArrayList<IlsPairing.GsAnt>();
        gss.add(g);
        List<IlsPairing.Rwy> one = new ArrayList<IlsPairing.Rwy>();
        one.add(r);
        List<IlsPairing.Pair> pairs = IlsPairing.pair(one, locs, gss);
        IlsPairing.Pair p1 = null, p2 = null;
        for (IlsPairing.Pair p : pairs) {
            p1 = p.loc == l1 ? p : p1;
            p2 = p.loc == l2 ? p : p2;
        }
        check(pairs.size() == 2 && p1 != null && p1.dir == 1 && p1.gs == g && near(p1.beyond, 30, 1e-6) && near(p1.ga, 25, 1e-6) && near(p1.gc, 15, 1e-6)
                        && p2 != null && p2.dir == -1 && p2.gs == null,
                "airport pairing: the LOC beyond the east end serves 09 with the mast (in " + f(p1 == null ? -1 : p1.ga) + ", off "
                        + f(p1 == null ? -1 : p1.gc) + "); the one beyond the west end serves 27 (LOC only); one 60 off the centre line - none");
        LinerIls.Station st = p1 == null ? null : IlsPairing.station(p1);
        check(st != null && st.full() && near(st.tx(), 0.5, 1e-9) && near(st.course, 90, 1e-6) && "09".equals(st.rwy) && st.freq == 10950
                        && "IWE".equals(st.ident) && near(st.gx, 25.5, 1e-9) && (st.flags & LinerIls.F_NO_GS) == 0
                        && (IlsPairing.station(p2).flags & LinerIls.F_NO_GS) != 0,
                "airport pairing: the station - its threshold, course 090, «09», the LOC's ident and frequency (the G/S paired to it)");
        List<String[]> conf = IlsPairing.conflicts(pairs);
        check(conf.size() == 1, "airport pairing: two stations on 109,50 within " + (int) IlsPairing.CONFLICT + " blocks - a frequency conflict");
        // airports: runways 500 apart are one, 5000 apart another
        List<IlsPairing.Rwy> apts = new ArrayList<IlsPairing.Rwy>(rws);
        IlsPairing.Rwy farR = new IlsPairing.Rwy();
        farR.a = end("0:5000:64:0", 5000.5, 0.5, 90);
        farR.b = end("0:5300:64:0", 5300.5, 0.5, 270);
        apts.add(farR);
        IlsPairing.airports(apts);
        check(apts.get(0).apt.equals(apts.get(1).apt) && !apts.get(0).apt.equals(farR.apt) && apts.get(0).apt.startsWith("ZZ")
                        && apts.get(0).apt.length() == 4,
                "airport pairing: runways near each other - one airport " + apts.get(0).apt + " (the ICAO placeholder), far - another " + farR.apt);
    }

    static IlsPairing.LocAnt loc(String k, double x, double z, String id, int freq) {
        IlsPairing.LocAnt l = new IlsPairing.LocAnt();
        l.key = k;
        l.x = x;
        l.z = z;
        l.y = 64;
        l.ident = id;
        l.freq = freq;
        return l;
    }

    private static void naming() {
        IlsPairing.magVar = 0;
        List<IlsPairing.End> e2 = new ArrayList<IlsPairing.End>();
        e2.add(end("a1", 0.5, 0.5, 90));
        e2.add(end("a2", 300.5, 0.5, 270));
        e2.add(end("b1", 0.5, 50.5, 90));
        e2.add(end("b2", 300.5, 50.5, 270));
        List<IlsPairing.Rwy> two = IlsPairing.runways(e2);
        IlsPairing.Rwy north = null, south = null;
        for (IlsPairing.Rwy r : two) {
            if (r.a.z < 10 || r.b.z < 10) {
                north = r;
            } else {
                south = r;
            }
        }
        boolean lr = north != null && south != null && "09L".equals(north.name(north.a.x < 1 ? 1 : -1)) && "09R".equals(south.name(south.a.x < 1 ? 1 : -1))
                && "27R".equals(north.name(north.a.x < 1 ? -1 : 1)) && "27L".equals(south.name(south.a.x < 1 ? -1 : 1));
        check(lr, "airport names: two parallels 090 - the northern (left landing east) 09L / 27R, the southern 09R / 27L");
        List<IlsPairing.End> e3 = new ArrayList<IlsPairing.End>(e2);
        e3.add(end("c1", 0.5, 100.5, 90));
        e3.add(end("c2", 300.5, 100.5, 270));
        List<IlsPairing.Rwy> three = IlsPairing.runways(e3);
        java.util.Set<String> names = new java.util.TreeSet<String>();
        for (IlsPairing.Rwy r : three) {
            names.add(r.nameA);
            names.add(r.nameB);
        }
        check(names.toString().equals("[09C, 09L, 09R, 27C, 27L, 27R]"), "airport names: three parallels - L / C / R " + names);
        String[] four = IlsPairing.suffixes(9, 4);
        check("09L".equals(four[0]) && "09R".equals(four[1]) && "10L".equals(four[2]) && "10R".equals(four[3]) && "36".equals(IlsPairing.two(36))
                        && IlsPairing.number(4) == 36 && IlsPairing.number(268) == 27,
                "airport names: four parallels - 09L 09R 10L 10R; course 004 - «36», 268 - «27»");
        IlsPairing.magVar = 10;
        int n = IlsPairing.number(90);
        IlsPairing.magVar = 0;
        check(n == 8, "airport names: the magnetic course - true 090 with 10 deg east variation is runway 08");
    }

    private static void dme() {
        LinerIls.Station s = full(0, 300, 30, 25, 15, 3);
        double d = IlsBeam.dme(s, -720, 64 + 38, 0);
        check(near(d, Math.sqrt(720 * 720 + 38 * 38), 1e-6) && "7,2".equals(AptScale.kmText(d, ',')) && near(LinerIls.dev(s, -720, 102, 0, 4000).dist, d, 1e-9),
                "airport DME: the slant distance to the threshold - over the OM " + AptScale.kmText(d, ',') + " km-eq");
    }

    private static void markers() {
        boolean cls = MarkerMath.classify(720, 0) == MarkerMath.OM && MarkerMath.classify(105, 2) == MarkerMath.MM && MarkerMath.classify(30, -1) == MarkerMath.IM
                && MarkerMath.classify(300, 0) == MarkerMath.NONE && MarkerMath.classify(720, 10) == MarkerMath.NONE;
        boolean fan = MarkerMath.inFan(40, 30, 0) && !MarkerMath.inFan(40, 40, 0) && MarkerMath.inFan(40, 0, 100) && !MarkerMath.inFan(-1, 0, 0)
                && MarkerMath.inFan(2, 1, 1);
        int omOn = 0, imStarts = 0, mmStarts = 0;
        for (long t = 0; t < 60; t++) {
            omOn += MarkerMath.keyAt(MarkerMath.OM, t) != 0 ? 1 : 0;
            imStarts += MarkerMath.keyAt(MarkerMath.IM, t) == 1 ? 1 : 0;
            mmStarts += MarkerMath.keyAt(MarkerMath.MM, t) == 1 ? 1 : 0;
        }
        check(cls && fan && omOn == 36 && imStarts == 20 && mmStarts == 10 && MarkerMath.PITCH[0] < MarkerMath.PITCH[1] && MarkerMath.PITCH[1] < MarkerMath.PITCH[2],
                "airport markers: OM 720 / MM 105 / IM 30 blocks by place; the fan widens with height; OM dashes 2/s (on 60 %), MM dot-dash, IM 6-7"
                        + " dots/s; the pitches rise OM < MM < IM");
    }

    private static void rvr() {
        RvrMath.Wx w = new RvrMath.Wx();
        double clear = RvrMath.rvr(RvrMath.visibility(w), false, 0);
        w.thunder = true;
        w.rain = true;
        double storm = RvrMath.rvr(RvrMath.visibility(w), false, 0);
        double stormNight = RvrMath.rvr(RvrMath.visibility(w), true, 5);
        RvrMath.Wx f = new RvrMath.Wx();
        f.fog = 3;
        double fog = RvrMath.rvr(RvrMath.visibility(f), false, 0);
        double fogDark = RvrMath.rvr(RvrMath.visibility(f), true, 0);
        RvrMath.Wx sw = new RvrMath.Wx();
        sw.swamp = true;
        sw.dayTime = 23500;
        double swamp = RvrMath.rvr(RvrMath.visibility(sw), false, 0);
        RvrMath.Wx cloud = new RvrMath.Wx();
        cloud.y = 128;
        double inCloud = RvrMath.rvr(RvrMath.visibility(cloud), false, 0);
        int rep = RvrMath.report(clear, 1, 0);
        check(rep == 2000 && "P2000".equals(RvrMath.text(rep)) && RvrMath.requiredCat(rep) == 1 && storm < 1000 && stormNight > storm
                        && RvrMath.requiredCat(storm) == 1 && RvrMath.requiredCat(fog) == 2 && RvrMath.requiredCat(fogDark) == 3 && RvrMath.requiredCat(swamp) == 2
                        && RvrMath.requiredCat(inCloud) == 3,
                "airport RVR: clear P2000 (CAT I), a thunderstorm " + (int) storm + " (night, lights 5: " + (int) stormNight + "), dense fog " + (int) fog
                        + " - CAT II, at night without lights " + (int) fogDark + " - CAT III, a swamp's dawn " + (int) swamp + ", in cloud " + (int) inCloud);
        check(RvrMath.report(437, 7, 0) % 25 == 0 && RvrMath.report(620, 7, 0) % 50 == 0 && RvrMath.report(1234, 7, 0) % 100 == 0
                        && RvrMath.report(10, 7, 0) == 50 && RvrMath.requiredCat(550) == 1 && RvrMath.requiredCat(549) == 2 && RvrMath.requiredCat(299) == 3,
                "airport RVR: reported in 25 / 50 / 100 m steps, at least 50; the categories' limits 550 / 300");
    }

    private static void wind() {
        long seed = 12345;
        WindField.Wind a = WindField.wind(seed, 50000, false, false, 1), b = WindField.wind(seed, 50000, false, false, 1);
        WindField.Wind o = WindField.wind(seed + 1, 50000, false, false, 1);
        WindField.Wind st = WindField.wind(seed, 50000, true, true, 1), hi = WindField.wind(seed, 50000, false, false, 10);
        double maxGustRatio = 0, minGustRatio = 9;
        for (int t = 0; t < 2000; t += 7) {
            WindField.Wind s = WindField.wind(seed, t, true, true, 1);
            maxGustRatio = Math.max(maxGustRatio, s.gust / s.speed);
            WindField.Wind c = WindField.wind(seed, t, false, false, 1);
            minGustRatio = Math.min(minGustRatio, c.gust / c.speed);
        }
        check(a.from == b.from && a.speed == b.speed && a.gust == b.gust && (o.from != a.from || o.speed != a.speed) && st.speed > a.speed + 10
                        && hi.speed > a.speed && maxGustRatio > 1.4 && minGustRatio >= 1 && a.from >= 0 && a.from < 360,
                "airport wind: deterministic (seed, time) - " + (int) a.from + " deg " + f(a.speed) + " m/s; another seed differs; a thunderstorm "
                        + f(st.speed) + " m/s gusting x" + f(maxGustRatio) + "; 10 blocks up " + f(hi.speed));
        double q0 = WindField.qnhTarget(seed, 1000, false, false), q1 = WindField.qnhTarget(seed, 1000, true, true);
        double lag = Double.NaN;
        lag = WindField.lag(lag, q0, 1);
        for (int i = 0; i < 5000; i++) {
            lag = WindField.lag(lag, q1, 1);
        }
        double[] c = WindField.components(270, 10, 270), x = WindField.components(360, 10, 270);
        check(q1 < q0 - 15 && near(lag, q1, 0.01) && near(c[0], 10, 1e-9) && near(c[1], 0, 1e-9) && near(x[1], 10, 1e-9) && near(x[0], 0, 1e-9)
                        && WindField.kmh(10) == 36 && WindField.temperature(64, 0.8, 8000) > WindField.temperature(64, 0.8, 20000),
                "airport wind: QNH falls in a storm (" + f(q0) + " -> " + f(q1) + ", lagged); the components (head / cross from the right);"
                        + " afternoon warmer than before dawn");
    }

    private static void papi() {
        check(AirfieldMath.whites(3.0, 3) == 2 && AirfieldMath.whites(3.7, 3) == 4 && AirfieldMath.whites(2.3, 3) == 0 && AirfieldMath.whites(3.3, 3) == 3,
                "airport PAPI: on the slope two white + two red, high - four white, low - four red (per the viewer's angle)");
    }

    private static void als() {
        List<RunwayLayout.Light> l = RunwayLayout.als();
        int flash = 0, bars = 0, barrettes = 0;
        for (RunwayLayout.Light a : l) {
            if (a.colour == RunwayLayout.FLASH) {
                flash++;
            } else if (Math.abs(a.u) > 0.45) {
                bars++;
            } else {
                barrettes++;
            }
        }
        // the run: the brightest flasher moves toward the threshold (run 0 outer -> 1 inner) within a period
        double prev = -1;
        boolean inward = true;
        int lit = 0;
        for (int t = 0; t < 8; t++) {
            double best = -1, bestRun = -1;
            int on = 0;
            for (RunwayLayout.Light a : l) {
                if (a.colour == RunwayLayout.FLASH) {
                    double k = RunwayLayout.alsFlash(t, a.run);
                    on += k > 0.05 ? 1 : 0;
                    if (k > best) {
                        best = k;
                        bestRun = a.run;
                    }
                }
            }
            inward &= bestRun >= prev;
            prev = bestRun;
            lit = Math.max(lit, on);
        }
        check(flash == 21 && barrettes == 150 && bars > 20 && inward && lit <= 3 && RunwayLayout.FLASH_PERIOD == 10,
                "airport ALS: 30 barrettes over 900 m-eq, crossbars, 21 flashers running inward twice a second (at most " + lit + " lit at once)");
    }

    private static void paint() {
        boolean st = RunwayLayout.stripes(3) == 6 && RunwayLayout.stripes(5) == 8 && RunwayLayout.stripes(7) == 12 && RunwayLayout.stripes(9) == 16;
        boolean inside = true;
        int[] ws = {3, 5, 9};
        int countPrev = 0;
        boolean grows = true;
        for (int w : ws) {
            List<double[]> q = RunwayLayout.paint(300, w, "27", "09");
            for (double[] c : q) {
                inside &= c[0] >= -w / 2.0 - 1e-6 && c[2] <= w / 2.0 + 1e-6 && c[1] >= -1e-6 && c[3] <= 300 + 1e-6 && c[0] < c[2] && c[1] < c[3];
            }
            grows &= q.size() > countPrev;
            countPrev = q.size();
        }
        double e1 = RunwayLayout.designatorEnd("27", 5), e2 = RunwayLayout.designatorEnd("27L", 5);
        List<double[]> left = RunwayLayout.endPaint("27L", 5, 150), plain = RunwayLayout.endPaint("27", 5, 150);
        check(st && inside && grows && e2 > e1 && left.size() > plain.size() && RunwayLayout.cell(3) <= 3 * 0.8 / 7 + 1e-9,
                "airport paint: piano keys 6 / 8 / 12 / 16 by the width, every piece inside the strip for widths 3, 5, 9; «27L» - the letter"
                        + " nearer the threshold (the centre line starts after it)");
        // the touchdown zone and the aiming point at 400 m-eq (40 blocks), both ends mirrored
        List<double[]> q = RunwayLayout.paint(300, 5, "27", "09");
        boolean aimA = false, aimB = false;
        for (double[] c : q) {
            aimA |= near(c[1], 40, 1e-6) && near(c[3], 44.5, 1e-6);
            aimB |= near(c[1], 300 - 44.5, 1e-6) && near(c[3], 260, 1e-6);
        }
        check(aimA && aimB, "airport paint: the aiming point bars 400 m-eq in at both ends");
    }

    private static void lights() {
        check(RunwayLayout.edgeColour(250, 300) == RunwayLayout.YELLOW && RunwayLayout.edgeColour(200, 300) == RunwayLayout.WHITE
                        && RunwayLayout.centreColour(20, 0) == RunwayLayout.RED && RunwayLayout.centreColour(60, 0) == RunwayLayout.RED
                        && RunwayLayout.centreColour(60, 1) == RunwayLayout.WHITE && RunwayLayout.centreColour(120, 1) == RunwayLayout.WHITE,
                "airport lights: edge white, yellow over the last 600 m-eq; centre line white, red / white from 900, red over the last 300");
        List<RunwayLayout.Light> east = RunwayLayout.lights(300, 5, 1, true, false), west = RunwayLayout.lights(300, 5, -1, true, false);
        boolean greenNear = false, redFar = false, flashE = false, flashW = false;
        for (RunwayLayout.Light l : east) {
            greenNear |= l.colour == RunwayLayout.GREEN && l.v < 0;
            redFar |= l.colour == RunwayLayout.RED && l.v > 300;
            flashE |= l.colour == RunwayLayout.FLASH;
        }
        for (RunwayLayout.Light l : west) {
            flashW |= l.colour == RunwayLayout.FLASH;
        }
        check(greenNear && redFar && flashE && !flashW, "airport lights: landing east - green threshold, red far end, its ALS; landing west - no ALS there");
        check(RunwayLayout.autoStep(false, false, 10000) == 0 && RunwayLayout.autoStep(true, false, 10000) == 3 && RunwayLayout.autoStep(false, true, 10000) == 4
                        && RunwayLayout.autoStep(false, false, 700) == 5 && RunwayLayout.step(2, true, false, 10000) == 2 && RunwayLayout.step(0, true, true, 100) == 0
                        && RunwayLayout.spriteSize(1000, 5) > RunwayLayout.spriteSize(100, 5) * 5 && RunwayLayout.spriteSize(1000, 1) < RunwayLayout.spriteSize(1000, 5),
                "airport lights: AUTO off by a clear day, 3 at night, 4 in rain, 5 under 800 m-eq RVR; a fixed step stays; the sprites grow with the"
                        + " distance (the same size on the screen) and the step");
    }

    private static void planner() {
        List<PlannerMath.Item> l = PlannerMath.plan(300, 5, 3);
        PlannerMath.Item loc = null, gs = null, lc = null, gc = null, gsh = null, lsh = null;
        int markers = 0, rvr = 0, papi = 0;
        for (PlannerMath.Item i : l) {
            loc = i.kind == PlannerMath.LOC ? i : loc;
            gs = i.kind == PlannerMath.GS ? i : gs;
            lc = i.kind == PlannerMath.LOC_CRIT ? i : lc;
            gc = i.kind == PlannerMath.GS_CRIT ? i : gc;
            gsh = i.kind == PlannerMath.GS_SHELTER ? i : gsh;
            lsh = i.kind == PlannerMath.LOC_SHELTER ? i : lsh;
            markers += i.kind == PlannerMath.MARKER ? 1 : 0;
            rvr += i.kind == PlannerMath.RVR ? 1 : 0;
            papi += i.kind == PlannerMath.PAPI ? 1 : 0;
        }
        boolean places = loc != null && near(loc.along(), 330, 1e-9) && near(loc.c1 - loc.c0, 5, 1e-9) && gs != null && near(gs.along(), 25, 1e-9)
                && near(gs.across(), 15, 1e-9) && markers == 3 && rvr == 3 && papi == 4;
        boolean outside = lc != null && gc != null && gsh != null && lsh != null && !gc.contains(gsh.along(), gsh.across()) && !lc.contains(lsh.along(), lsh.across())
                && Math.hypot(gsh.along() - gs.along(), gsh.across() - gs.across()) <= 4 && Math.hypot(lsh.along() - loc.along(), lsh.across() - loc.across()) <= 4.5;
        check(places && outside && PlannerMath.expected(3, 0, 0, 0, 0) == 3 && PlannerMath.expected(3, 1, 0, 0, 0) == 1
                        && PlannerMath.expected(3, 0, 0, 0, 1) == 2,
                "airport planner: LOC 30 past the far end (5 across), G/S 25 in / 15 off, 3 markers, 3 RVR, 4 PAPI; the shelters within reach and out of"
                        + " the critical areas; CAT III expected, a block in the LOC critical area - CAT I, in the G/S sensitive - CAT II");
        int[][] c = PlannerMath.thresholdBlocks(true, 10.5, 100, 400, 63);
        int[][] z = PlannerMath.thresholdBlocks(false, 20.0, -50, 250, 70);
        check(c[0][0] == 99 && c[1][0] == 400 && c[0][2] == 10 && c[0][1] == 63 && z[0][2] == -51 && z[1][2] == 250 && z[0][0] == 19,
                "airport planner: the runway ends one block beyond each end of the strip, on its centre line (an even width: the left block)");
    }

    // ---------------------------------------------------------------- the flight inspection

    private static void flightCheck() {
        FlightCheck fc = new FlightCheck();
        boolean noXpdr = !fc.offer(5, -600, true, false);
        boolean tooClose = !fc.offer(5, -50, true, true);
        boolean started = fc.offer(5, -600, true, true) && fc.state == FlightCheck.RUNNING;
        for (double a = -600; a < 0; a += 14) {
            fc.sample(5, a, true, 0.1, 0.02, 1.55, 3);
        }
        int st = fc.sample(5, 0.5, true, 0, 0, 1.55, 3);
        check(noXpdr && tooClose && started && st == FlightCheck.DONE && fc.result == 3 && fc.hint < 0 && fc.calibCat() == 3,
                "airport flight check: needs a transponder and the beam 100..1200 blocks out; a clean approach to the threshold - CALIBRATED, CAT III");
        FlightCheck bent = new FlightCheck();
        bent.offer(6, -700, true, true);
        for (double a = -700; a < 0; a += 14) {
            bent.sample(6, a, true, FlightCheck.locShift(0, 0.08 * Math.sin(a / 30), 330), 0.01, 1.55, 3);
        }
        bent.sample(6, 1, true, 0, 0, 1.55, 3);
        FlightCheck tch = new FlightCheck();
        tch.offer(7, -700, true, true);
        for (double a = -700; a < 0; a += 14) {
            tch.sample(7, a, true, 0, 0, 2.6, 3);
        }
        tch.sample(7, 1, true, 0, 0, 2.6, 3);
        check(bent.state == FlightCheck.DONE && bent.result == 2 && bent.hint == IlsCat.I_LOC_CRIT && tch.result == 1 && tch.hint == IlsCat.I_TCH,
                "airport flight check: a bent LOC (" + f(bent.maxLoc) + " blocks at the threshold) - CAT II, hint the critical area; a TCH of 26 m-eq -"
                        + " CAT I, hint the TCH");
        FlightCheck ab = new FlightCheck();
        ab.offer(8, -600, true, true);
        ab.sample(8, -580, true, 0, 0, 1.5, 3);
        int left = ab.sample(8, -560, false, 0, 0, 1.5, 3);
        FlightCheck few = new FlightCheck();
        few.offer(9, -150, true, true);
        few.sample(9, -140, true, 0, 0, 1.5, 3);
        int short1 = few.sample(9, 2, true, 0, 0, 1.5, 3);
        boolean other = !new FlightCheck().offer(1, -600, false, true);
        FlightCheck rt = new FlightCheck();
        rt.unpack(fc.pack());
        FlightCheck running = new FlightCheck();
        running.offer(3, -600, true, true);
        FlightCheck rr = new FlightCheck();
        rr.unpack(running.pack());
        check(left == FlightCheck.WAITING && short1 == FlightCheck.WAITING && other && rt.state == FlightCheck.DONE && rt.result == 3 && rr.state == FlightCheck.WAITING,
                "airport flight check: leaving the beam aborts, too short an approach - waits again; the word round-trips (a running one restarts)");
        check(near(FlightCheck.locShift(0.5, 0.1, 330), 0.5 + Math.tan(Math.toRadians(0.1)) * 330, 1e-9),
                "airport flight check: the course shift at the threshold - the array's offset + its bend over its distance");
    }

    private static void morse() {
        int[] e = IlsBeam.keying("E");
        int[] t = IlsBeam.keying("T");
        int[] ya = IlsBeam.keying("Я");
        int on = 0;
        for (int k : IlsBeam.keying("ILS")) {
            on += k != 0 ? 1 : 0;
        }
        boolean eDot = e[0] == 1 && e[1] == 3 && e[2] == 0;
        boolean tDash = t[0] == 2 && t[5] == 3 && t[6] == 0;
        boolean yaCode = "..-..".equals(IlsBeam.morse('Э')) && ".-.-".equals(IlsBeam.morse('я')) && ya[0] == 1 && ya[4] == 2;
        // I .. L .-.. S ... = 8 dots (2 ticks) + 1 dash (6) = 22 ticks of tone
        check(eDot && tDash && yaCode && on == 22 && IlsBeam.keyAt("ILS", 0) == IlsBeam.keyAt("ILS", IlsBeam.MORSE_PERIOD) && "".equals(IlsBeam.morse('#')),
                "airport Morse: E a dot, T a dash, Cyrillic idents (Я .-.-), «ILS» 22 ticks of tone, repeating every " + IlsBeam.MORSE_PERIOD + " ticks");
    }

    // ---------------------------------------------------------------- the station's save and sync, the AFS

    private static void station() {
        LinerIls.Station s = full(0, 300, 30, 25, 15, 2);
        s.flags = LinerIls.F_UNCAL;
        s.bendLoc = 0.08;
        s.markers[0] = 720;
        s.markers[2] = 30;
        s.calib = 9;
        NBTTagCompound t = new NBTTagCompound();
        s.write(t);
        LinerIls.Station r = new LinerIls.Station();
        r.read(t);
        LinerIls.Station old = new LinerIls.Station();
        old.bx = 3;
        old.course = 90;
        NBTTagCompound to = new NBTTagCompound();
        old.write(to);
        LinerIls.Station ro = new LinerIls.Station();
        ro.read(to);
        boolean same = r.full() && near(r.tx(), 0, 1e-9) && near(r.lx, 330, 1e-9) && near(r.gz, 15, 1e-9) && r.cat == 2 && r.flags == LinerIls.F_UNCAL
                && near(r.bendLoc, 0.08, 1e-9) && r.markers[0] == 720 && r.markers[1] == -1 && r.markers[2] == 30 && r.calib == 9 && "09".equals(r.rwy)
                && "ZZAA".equals(r.apt) && r.rwyLen == 300;
        check(same && !ro.full() && !to.hasKey("Kind") && ro.category() == LinerIls.legacyCat,
                "airport station NBT: a FULL station round-trips (runway, antennas, category, flags, bends, markers, inspection); a wave-4 beacon"
                        + " writes and reads exactly as before (COMPACT, the legacy category)");
        // the liner's sync: the AFS' station and the airport words
        LinerSimSVA sim = LinerSimSVA.flying(60, 1.3, 90, 3);
        LinerIls.Station st = full(1000, 300, 30, 25, 15, 3);
        st.bendGs = 0.04;
        sim.ils.add(st);
        sim.L.afs.ilsChosen = st.key();
        sim.tick();
        sim.L.afs.marker = MarkerMath.MM;
        sim.L.afs.rvr[0] = 550;
        sim.L.afs.windFrom = 240;
        sim.L.afs.windKmh = 18;
        sim.L.afs.qnhApt = 1008;
        String packed = sim.L.pack();
        LinerSystems c = new LinerSystems();
        c.unpack(packed);
        LinerAfs C = c.afs;
        boolean round = C.ils != null && C.ils.full() && near(C.ils.lx, 1330, 0.05) && near(C.ils.gz, 15, 0.05) && C.ils.cat == 3 && near(C.ils.bendGs, 0.04, 1e-4)
                && "09".equals(C.ils.rwy) && "ZZAA".equals(C.ils.apt) && C.marker == MarkerMath.MM && C.rvr[0] == 550 && C.windFrom == 240 && C.qnhApt == 1008;
        LinerSystems e = new LinerSystems();
        e.unpack(new LinerSystems().pack());
        check(round && e.afs.marker == -1 && e.afs.windFrom == -1 && packed.length() < SelfTestLinerW7SVA.SYNC_LIMIT + 120,
                "airport sync: the FULL station's geometry / category / bends and the markers, RVR, weather ride the liner's string (" + packed.length()
                        + " chars); an empty one leaves nothing");
    }

    private static void capability() {
        LinerAfs F = new LinerAfs();
        F.latArm = LinerAfs.AL_LOC;
        F.ap1 = true;
        F.ap2 = true;
        F.ils = full(0, 300, 30, 25, 15, 3);
        int c3 = F.capability();
        F.ils.cat = 2;
        int c2 = F.capability();
        F.ils.cat = 1;
        int c1 = F.capability();
        F.ap2 = false;
        F.ils.cat = 3;
        int single = F.capability();
        F.ap2 = true;
        LinerIls.Station compact = new LinerIls.Station();
        F.ils = compact;
        int legacy = F.capability();
        boolean restricted = F.restricted();
        check(c3 == LinerAfs.CAP_CAT3_DUAL && c2 == LinerAfs.CAP_CAT2 && c1 == LinerAfs.CAP_CAT1 && single == LinerAfs.CAP_CAT1
                        && legacy == LinerAfs.CAP_CAT3_DUAL && !restricted,
                "airport AFS: AP 1 + 2 on a CAT III station - CAT 3 DUAL, on CAT II - CAT 2, CAT I - CAT 1; one AP - CAT 1; the legacy beacon as before");
    }

    // ---------------------------------------------------------------- flights on a FULL station (LinerSimSVA)

    /** An approach like SelfTestLinerAfsSVA's on a FULL station of category `cat`: 1000 out, 30 left, 45 up, APPR + AP 1 + 2. */
    static LinerSimSVA approach(int cat) {
        LinerSimSVA s = LinerSimSVA.flying(45, 1.3, 110, 3);
        s.gearDown();
        s.L.set(LinerControls.SPD_BRK, 0);
        s.L.control(LinerControls.ABRK_MED.index, LinerControls.A_PRIMARY);
        s.ils.add(full(1000, 300, 30, 25, 15, cat));
        s.b.z = -30;
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_APPR);
        s.click(LinerControls.FCU_AP2);
        return s;
    }

    private static void autolandCat3() {
        LinerSimSVA s = approach(3);
        int land = -1, flare = -1, roll = -1, stop = -1;
        boolean dual = false;
        double maxCross = 0;
        for (int t = 0; t < 3200; t++) {
            s.tick();
            LinerAfs F = s.L.afs;
            if (F.thr == LinerAfs.T_RETARD && F.lever > 0) {
                F.lever = 0;
            }
            dual |= F.capability() == LinerAfs.CAP_CAT3_DUAL;
            land = land < 0 && F.lat == LinerAfs.L_LAND ? t : land;
            flare = flare < 0 && F.ver == LinerAfs.V_FLARE ? t : flare;
            roll = roll < 0 && F.lat == LinerAfs.L_ROLLOUT ? t : roll;
            if (F.ver == LinerAfs.V_GS && !s.b.ground && s.b.ra > 12) {
                maxCross = Math.max(maxCross, Math.abs(s.b.z));
            }
            if (s.b.ground && s.b.speed < 0.005) {
                stop = t;
                break;
            }
        }
        check(dual && land > 0 && flare > land && roll > flare && stop > 0 && maxCross < 1.2 && Math.abs(s.b.z) < 1.5 && s.b.x > 1000 && s.b.x < 1600
                        && s.touchSink > 0 && s.touchSink < 0.06,
                "airport autoland on a FULL CAT III station: CAT 3 DUAL, LAND " + land + ", FLARE " + flare + ", ROLL OUT " + roll + ", stopped at x "
                        + f(s.b.x - 1000) + " past the threshold, " + f(s.b.z) + " off the centre line (the LOC held within " + f(maxCross) + "), sinking "
                        + f(s.touchSink * 20) + " m/s");
    }

    private static void cat1Minimum() {
        LinerSimSVA s = approach(1);
        boolean land = false, cap1 = false, min = false;
        int lastSeq = s.L.afs.callSeq;
        double offAt = -1;
        for (int t = 0; t < 2600; t++) {
            s.tick();
            LinerAfs F = s.L.afs;
            land |= F.lat == LinerAfs.L_LAND || F.ver == LinerAfs.V_FLARE;
            cap1 |= F.capability() == LinerAfs.CAP_CAT1 && F.ap1 && F.ap2;
            if (F.callSeq != lastSeq) {
                lastSeq = F.callSeq;
                min |= F.call == LinerAfs.CALL_MINIMUM;
            }
            if (!F.apOn() && offAt < 0 && !s.b.ground) {
                offAt = s.b.ra;
                break;
            }
        }
        LinerAfs F = s.L.afs;
        check(cap1 && !land && min && offAt > 0 && offAt <= IlsCat.minHeight(1) + 0.1 && offAt > IlsCat.minHeight(1) - 3 && F.apWarn == -1 && F.cavalry != 0,
                "airport CAT I: AP 1 + 2 show CAT 1, no LAND / FLARE; at the minimum (" + f(offAt) + " over the ground) «MINIMUM», the AP lets go with"
                        + " the red warning and the cavalry charge");
    }

    private static void falseGlideSlope() {
        // 500 blocks before the threshold on the centre line, 9.3 deg up from the mast (just over the 9 deg lobe): LOC on, G/S
        // armed from above
        LinerSimSVA s = LinerSimSVA.flying(10, 1.3, 90, 3);
        s.gearDown();
        LinerIls.Station st = full(1000, 300, 30, 25, 15, 3);
        s.ils.add(st);
        s.b.x = 500;
        s.b.z = 0;
        double hg = Math.hypot(1025 - 500, 15);
        s.b.y = 64 + hg * Math.tan(Math.toRadians(9.3));
        s.L.afs.alt = (int) Math.round(s.b.y);
        s.tick();
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_APPR);
        s.click(LinerControls.FCU_AP2);
        int gsAt = -1, lobe3 = 0;
        double steep = 0, eMin = 99;
        for (int t = 0; t < 400; t++) {
            s.tick();
            LinerAfs F = s.L.afs;
            LinerIls.Dev d = LinerIls.dev(st, s.b.x, s.b.y, s.b.z, 4000, s.ticks);
            if ((F.ver == LinerAfs.V_GS || F.ver == LinerAfs.V_GS_CAP) && gsAt < 0) {
                gsAt = t;
            }
            if ((F.ver == LinerAfs.V_GS || F.ver == LinerAfs.V_GS_CAP) && d.lobe == 3) {
                lobe3++;
                eMin = Math.min(eMin, d.elevDeg);
                steep = Math.max(steep, -s.b.motionY / Math.max(0.1, s.b.speed));
            }
            if (s.b.ra < 15) {
                break;
            }
        }
        double normal = Math.tan(Math.toRadians(3));
        check(gsAt >= 0 && lobe3 > 40 && eMin > 7.5 && steep > 2 * normal,
                "airport false glide slope: G/S captured from above (tick " + gsAt + ") follows the 9 deg lobe - " + lobe3 + " ticks in G/S* / G/S on lobe 3,"
                        + " elevation >= " + f(eMin) + " deg, descent gradient " + f(steep) + " (normal " + f(normal) + "): abnormal pitch / sink, detectable");
    }

    private static void monitorFault() {
        LinerSimSVA s = LinerSimSVA.flying(45, 1.3, 90, 3);
        LinerIls.Station st = full(1000, 300, 30, 25, 15, 3);
        s.ils.add(st);
        s.b.x = 200;
        s.click(LinerControls.FCU_APPR);
        s.L.set(LinerControls.LS_L, 1);
        for (int t = 0; t < 40; t++) {
            s.tick();
        }
        boolean before = !s.L.afs.ilsFault() && !s.L.ecam.isActive(LinerEcam.M_ILS_FAULT);
        st.cat = 0;
        st.flags = LinerIls.F_OFF | LinerIls.F_LOC_FAIL | LinerIls.F_GS_FAIL;
        for (int t = 0; t < 10; t++) {
            s.tick();
        }
        LinerIls.Dev d = LinerIls.dev(s.L.afs.ils == null ? st : s.L.afs.ils, s.b.x, s.b.y, s.b.z, 4000);
        check(before && s.L.afs.ils != null && s.L.afs.ilsFault() && s.L.ecam.isActive(LinerEcam.M_ILS_FAULT) && d.locFail && d.gsFail && !d.locValid
                        && d.inCover,
                "airport monitor: the station shut off - it stays tuned with the red LOC / G/S flags (no signal) and the ECAM's «ILS ОТКАЗ»");
    }

    // ---------------------------------------------------------------- lang

    private static void lang() throws java.io.IOException {
        java.util.Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        List<String> missing = new ArrayList<String>();
        List<String> keys = new ArrayList<String>();
        for (String k : IlsCat.ISSUE_KEYS) {
            keys.add("sva.apt.issue." + k);
            keys.add("sva.apt.hint." + k);
        }
        for (String k : PlannerMath.KEYS) {
            keys.add("sva.apt.plan." + k);
        }
        for (String k : new String[] {"loc", "gs", "shelter", "marker", "rvr", "weather", "windsock", "rwyEnd", "planner"}) {
            keys.add("sva.tooltip.apt." + k + ".summary");
            keys.add("sva.tooltip.apt." + k + ".details");
            keys.add("sva.tooltip.howto.apt." + k);
        }
        for (String k : com.sva.block.BlockAptDeviceSVA.KEYS) {
            keys.add("tile.siliconagevehicles.aptDevice." + k + ".name");
        }
        keys.add("tile.siliconagevehicles.ilsAntenna.loc.name");
        keys.add("tile.siliconagevehicles.ilsAntenna.gs.name");
        keys.add("sva.liner.msg." + LinerEcam.KEYS[LinerEcam.M_ILS_FAULT]);
        keys.add("sva.mcdu.src." + NavDb.SRC_KEYS[NavDb.SRC_RWY]);
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                missing.add(k);
            }
        }
        check(missing.isEmpty(), "airport lang: the issues / hints, the planner's names, the tooltips, the devices, the ECAM, the MCDU source in ru + en"
                + (missing.isEmpty() ? "" : " - missing " + missing));
    }
}
