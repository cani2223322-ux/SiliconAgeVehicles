package com.sva.debug;

import java.util.HashSet;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CockpitDials;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.GlassMath;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleType;

/**
 * Cockpit wave self-tests (docs/plan-next.md §5 / §6 - the light plane's C+ «Гибрид + MFD» and the helicopter's C+
 * «Гибрид + потолочный пульт»; veh_real12.py, client/GlassCockpitSVA) - pure logic on the dedicated server: the MFD's
 * pages and the client's page memory key, the heli gauges' scales (the radar altimeter's compressed one, the dual tach,
 * the power reading), the hover indicator's drift in the machine's frame, the radar altimeter's callouts, the
 * transponder's squawk, the route page's rows (the same RouteMath.Plan the autopilot's МАРШ flies), the lamp strips'
 * roles and their states; the real models' layouts (every new face found, where it sits from the pilot's eye: the PFD
 * and MFD sizes, the AP bar's digits >= 19 px at 1920 x 1080, the throttle corner empty, the heli's overhead panel over
 * the head out of the forward view); «Страница MFD»; the config defaults and the lang lines.
 */
final class SelfTestCockpitSVA {

    private SelfTestCockpitSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        pages();
        scales();
        drift();
        callouts();
        squawk();
        routeRows();
        strips();
        planeLayout();
        heliLayout();
        function();
        configAndLang();
    }

    private static String f(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    // ---------------------------------------------------------------- the MFD pages

    private static void pages() {
        VehicleType p = VehicleType.PLANE, h = VehicleType.HELI;
        int[] pp = GlassMath.pages(p), hp = GlassMath.pages(h);
        boolean order = pp.length == 4 && pp[0] == GlassMath.ENG && pp[1] == GlassMath.MAP && pp[2] == GlassMath.ROUTE && pp[3] == GlassMath.RWR
                && hp.length == 4 && hp[0] == GlassMath.ENG && hp[1] == GlassMath.MAP && hp[2] == GlassMath.CARGO && hp[3] == GlassMath.RWR;
        boolean cycle = GlassMath.next(p, GlassMath.ENG) == GlassMath.MAP && GlassMath.next(p, GlassMath.ROUTE) == GlassMath.RWR
                && GlassMath.next(p, GlassMath.RWR) == GlassMath.ENG && GlassMath.next(h, GlassMath.MAP) == GlassMath.CARGO
                && GlassMath.next(h, GlassMath.ROUTE) == GlassMath.ENG && GlassMath.sanitize(h, GlassMath.ROUTE) == GlassMath.ENG
                && GlassMath.sanitize(p, GlassMath.RWR) == GlassMath.RWR && GlassMath.slot(p, GlassMath.ROUTE) == 2 && GlassMath.slot(h, GlassMath.ROUTE) == -1;
        boolean others = true;
        for (VehicleType t : VehicleType.values()) {
            others &= GlassMath.hasMfd(t) == (t == p || t == h);
            others &= t == p || t == h || GlassMath.next(t, 0) == -1 && GlassMath.sanitize(t, 0) == -1;
        }
        // the remembered pages: the key from synced, stable values only; junk read back as "none"
        String k = GlassMath.memoryKey(p, 42, "Ste ve=:x"), k2 = GlassMath.memoryKey(h, 42, "Steve");
        boolean mem = "plane_42_Ste_ve__x".equals(k) && !k.equals(k2) && GlassMath.parsePage(" 2 ") == 2 && GlassMath.parsePage("9") == -1
                && GlassMath.parsePage("x") == -1 && GlassMath.parsePage(null) == -1 && GlassMath.PAGE_KEYS.length == 5;
        check(order && cycle && others && mem, "cockpit wave MFD: the plane ДВИГ / КАРТА / МАРШ / СПО, the heli ДВИГ / КАРТА / ГРУЗ / СПО, the key flips"
                + " round, an unknown page shows the first, no other type has one; the client's memory key " + k + " (no '=', ':' or spaces), junk = none");
    }

    // ---------------------------------------------------------------- the heli gauges

    private static void scales() {
        boolean ra = GlassMath.radaltFrac(0) == 0F && GlassMath.radaltFrac(-3) == 0F && Math.abs(GlassMath.radaltFrac(5) - 0.25F) < 1e-6
                && Math.abs(GlassMath.radaltFrac(10) - 0.5F) < 1e-6 && Math.abs(GlassMath.radaltFrac(30) - 0.75F) < 1e-6
                && GlassMath.radaltFrac(50) == 1F && GlassMath.radaltFrac(200) == 1F && GlassMath.radaltFrac(Float.NaN) == 0F;
        boolean mono = true;
        for (float x = 0; x < 60; x += 0.5F) {
            mono &= GlassMath.radaltFrac(x + 0.5F) >= GlassMath.radaltFrac(x);
        }
        boolean ang = GlassMath.angle(0) == -135F && GlassMath.angle(1) == 135F && GlassMath.angle(0.5F) == 0F && GlassMath.angle(2) == 135F
                && Math.abs(GlassMath.tachFrac(60) - 0.5F) < 1e-6 && GlassMath.tachFrac(200) == 1F;
        boolean rpm = Math.abs(GlassMath.engineRpm(100, false) - 97F) < 1e-4 && GlassMath.engineRpm(100, true) == GlassMath.IDLE
                && GlassMath.engineRpm(40, true) == 40F;
        float idle = GlassMath.power(0, 0, 0), cruise = GlassMath.power(0.6F, 0.3F, 0), worst = GlassMath.power(1, 1, 0.5F);
        boolean pw = idle == GlassMath.POWER_BASE && cruise > 50 && cruise < 85 && worst > 100 && GlassMath.power(1, 0, 0) <= 100
                && GlassMath.power(2, 2, -1) == GlassMath.power(1, 1, 0);
        check(ra && mono && ang && rpm && pw, "cockpit wave heli gauges: РВ 0..10 blocks on half the sweep, 10..50 the rest (5 -> 1/4, 30 -> 3/4),"
                + " clamped, rising; the 270 deg sweep; the engine 97 % of the rotor, idle in autorotation; power idle " + f(idle) + " %, cruise " + f(cruise)
                + " %, full collective + full hold climbing " + f(worst) + " % (over the red line), full collective empty <= 100 %");
    }

    private static void drift() {
        float[] a = GlassMath.drift(0, 0.1, 0F), b = GlassMath.drift(-0.1, 0, 0F), c = GlassMath.drift(-0.1, 0, 90F), d = GlassMath.drift(0, 0.05, 90F);
        float[] e = GlassMath.clampDrift(new float[] {3F, 4F}), g = GlassMath.clampDrift(new float[] {1F, -1F});
        boolean ok = Math.abs(a[0] - 2) < 1e-4 && Math.abs(a[1]) < 1e-4 && Math.abs(b[0]) < 1e-4 && Math.abs(b[1] - 2) < 1e-4
                && Math.abs(c[0] - 2) < 1e-4 && Math.abs(c[1]) < 1e-4 && Math.abs(d[1] + 1) < 1e-4
                && Math.abs(Math.sqrt(e[0] * e[0] + e[1] * e[1]) - GlassMath.DRIFT_MAX) < 1e-4 && Math.abs(e[0] / e[1] - 0.75F) < 1e-4 && g[0] == 1F && g[1] == -1F;
        check(ok, "cockpit wave hover indicator: the drift in the machine's frame - facing south, moving south = 2 m/s forward, moving west = 2 m/s"
                + " right; facing west, moving west = forward, moving south = 1 m/s left; clamped to the outer ring keeping its direction");
    }

    private static void callouts() {
        boolean ok = GlassMath.callout(55, 48) == 50 && GlassMath.callout(55, 25) == 30 && GlassMath.callout(41, 40) == 40 && GlassMath.callout(40, 39) == -1
                && GlassMath.callout(25, 35) == -1 && GlassMath.callout(30, 30) == -1 && GlassMath.callout(12, 9) == 10 && GlassMath.callout(99, 0) == 10
                && GlassMath.callout(9, 1) == -1 && GlassMath.CALLOUTS[0] == 50;
        check(ok, "cockpit wave РВ callouts: «50.. 40.. 30.. 20.. 10» each crossed on the way down (the lowest when several in one frame),"
                + " none climbing or level, none under 10");
    }

    private static void squawk() {
        check("7000".equals(GlassMath.squawk(07000)) && "0010".equals(GlassMath.squawk(8)) && "7777".equals(GlassMath.squawk(07777))
                        && "----".equals(GlassMath.squawk(-1)) && "----".equals(GlassMath.squawk(010000)),
                "cockpit wave transponder: the squawk as four octal digits (7000, 0010, 7777), out of range dashes");
    }

    private static void routeRows() {
        RouteMath.Plan p = new RouteMath.Plan();
        p.add(0, -1000, 80);
        p.add(1000, -1000, 80);
        p.add(1000, 0, 80);
        p.active = 1;
        double[][] r = GlassMath.routeRows(p, 0.5, -999.5, 1.0);         // at the first point, 1 block / tick (72 km/h)
        boolean ok = r.length == 3 && Double.isNaN(r[0][1]) && Math.abs(r[1][0] - 90) < 1e-6 && Math.abs(r[1][1] - 1000) < 1e-6
                && Math.abs(r[2][0] - 180) < 1e-6 && Math.abs(r[2][1] - 2000) < 1e-6 && Math.abs(r[2][2] - 2000 / 20.0 / 60.0) < 1e-6;
        double[][] slow = GlassMath.routeRows(p, 0.5, -999.5, 0);
        p.active = 3;
        double[][] done = GlassMath.routeRows(p, 0.5, -999.5, 1.0);
        ok &= Double.isNaN(slow[1][2]) && !Double.isNaN(slow[1][1]) && Double.isNaN(done[2][1]) && Math.abs(done[2][0] - 180) < 1e-6;
        check(ok, "cockpit wave МАРШ page: from the vehicle along the plan - the active point east 1,0 km course 090, the next south 2,0 km in all"
                + " course 180, minutes at the speed (none parked); points behind: their leg's course, no distance");
    }

    // ---------------------------------------------------------------- the lamp strips

    private static void strips() throws java.io.IOException {
        int[] ps = CockpitLamps.strip(VehicleType.PLANE), hs = CockpitLamps.strip(VehicleType.HELI);
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        boolean ok = ps.length == 13 && hs.length == 9 && ps[0] == CockpitLamps.ENG_FAIL && ps[12] == CockpitLamps.STALL && hs[0] == CockpitLamps.AUTOROT
                && hs[8] == CockpitLamps.ICING && CockpitLamps.strip(VehicleType.JET).length == 0 && CockpitLamps.KEYS.length == CockpitLamps.ICING + 1;
        for (int[] s : new int[][] {ps, hs}) {
            Set<Integer> seen = new HashSet<Integer>();
            for (int r : s) {
                ok &= seen.add(Integer.valueOf(r)) && r > 0 && r < CockpitLamps.KEYS.length && ru != null && en != null
                        && ru.contains("sva.cockpit.lamp." + CockpitLamps.KEYS[r]) && en.contains("sva.cockpit.lamp." + CockpitLamps.KEYS[r]);
            }
        }
        check(ok, "cockpit wave lamp strips: the plane's 13 (failures, fuel, battery, AP, the high-lift annunciators), the heli's 9, no repeats,"
                + " every one labelled ru + en; no other type has a strip");
        CockpitLamps.Snapshot s = new CockpitLamps.Snapshot();
        s.powered = true;
        boolean quiet = true;
        for (int r : hs) {
            quiet &= CockpitLamps.state(r, s) == CockpitLamps.OFF;
        }
        s.charge = 0.1F;                                           // EU mode: the charge is the fuel
        boolean euLow = CockpitLamps.state(CockpitLamps.LOW_FUEL, s) == CockpitLamps.ON;
        s.charge = 1F;
        s.hydrogen = true;                                         // hydrogen: the H2 tank, the removable battery
        s.fuel = 0.1F;
        boolean low = euLow && CockpitLamps.state(CockpitLamps.LOW_FUEL, s) == CockpitLamps.ON && CockpitLamps.state(CockpitLamps.RESERVE, s) == CockpitLamps.OFF;
        s.fuel = 0.03F;
        boolean res = CockpitLamps.state(CockpitLamps.RESERVE, s) == CockpitLamps.BLINK;
        s.batCharge = 0.05F;
        boolean bat = CockpitLamps.state(CockpitLamps.BAT, s) == CockpitLamps.ON;
        s.hydrogen = false;                                        // EU mode: the board battery
        s.battery = 0.1F;
        bat &= CockpitLamps.state(CockpitLamps.BAT, s) == CockpitLamps.ON;
        s.battery = 0.01F;
        bat &= CockpitLamps.state(CockpitLamps.BAT, s) == CockpitLamps.BLINK;
        s.rotor = 80F;
        boolean rotGround = CockpitLamps.state(CockpitLamps.ROTOR_RPM, s) == CockpitLamps.OFF;
        s.heliMode = true;
        s.airborne = true;
        boolean rot = CockpitLamps.state(CockpitLamps.ROTOR_RPM, s) == CockpitLamps.BLINK;
        s.rotor = 100F;
        rot &= CockpitLamps.state(CockpitLamps.ROTOR_RPM, s) == CockpitLamps.OFF;
        s.power = 101F;
        boolean over = CockpitLamps.state(CockpitLamps.OVERLOAD, s) == CockpitLamps.BLINK;
        s.genOn = false;
        s.agl = 4F;
        s.iced = true;
        boolean rest = CockpitLamps.state(CockpitLamps.GEN_OFF, s) == CockpitLamps.ON && CockpitLamps.state(CockpitLamps.GROUND, s) == CockpitLamps.ON
                && CockpitLamps.state(CockpitLamps.ICING, s) == CockpitLamps.ON && CockpitLamps.colour(CockpitLamps.ICING) == CockpitLamps.BLUE
                && CockpitLamps.colour(CockpitLamps.OVERLOAD) == CockpitLamps.RED && CockpitLamps.colour(CockpitLamps.LOW_FUEL) == CockpitLamps.AMBER;
        s.agl = 20F;
        rest &= CockpitLamps.state(CockpitLamps.GROUND, s) == CockpitLamps.OFF;
        s.powered = false;
        boolean dark = true;
        for (int r : hs) {
            dark &= CockpitLamps.state(r, s) == CockpitLamps.OFF;
        }
        check(quiet && low && res && bat && rotGround && rot && over && rest && dark,
                "cockpit wave lamp states: all quiet by default; МАЛО ТОПЛ. under 15 % (H2, EU: the charge), РЕЗЕРВ blinking under 5 %; АКБ under"
                        + " 10 % on hydrogen, EU: the board battery under 20 % (blinking under 5);"
                        + " ОБОРОТЫ ВИНТА blinking only in the air out of 90..110 %; ПЕРЕГРУЗ over 100 %; ГЕН off; ЗЕМЛЯ within 6 blocks;"
                        + " ОБЛЕДЕН. blue; all dark unpowered");
    }

    // ---------------------------------------------------------------- the models

    /** From the eye: {yaw deg (+ right), deg below the horizon} of a point. */
    private static double[] look(float[] e, float[] p) {
        double dx = p[0] - e[0], dy = p[1] - e[1], dz = p[2] - e[2];
        return new double[] {Math.toDegrees(Math.atan2(dz, dx)), -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))};
    }

    private static float[] at(CockpitLayout.Screen s, float a, float b) {
        return new float[] {s.c[0] + s.right[0] * a + s.up[0] * b, s.c[1] + s.right[1] * a + s.up[1] * b, s.c[2] + s.right[2] * a + s.up[2] * b};
    }

    /** {width deg, height deg} of a face seen from the eye (through its centre lines). */
    private static double[] size(float[] e, CockpitLayout.Screen s) {
        double[] l = look(e, at(s, -s.w / 2, 0)), r = look(e, at(s, s.w / 2, 0)), t = look(e, at(s, 0, s.h / 2)), b = look(e, at(s, 0, -s.h / 2));
        return new double[] {Math.abs(r[0] - l[0]), Math.abs(b[1] - t[1])};
    }

    /** Pixels at 1920 x 1080 (vertical FOV 70) of `deg` near the screen's middle. */
    private static double px(double deg) {
        return 1080 / (2 * Math.tan(Math.toRadians(35))) * 2 * Math.tan(Math.toRadians(deg / 2));
    }

    private static void planeLayout() {
        VehicleType t = VehicleType.PLANE;
        CockpitLayout l = CockpitLayout.of(t);
        float[] e = CockpitLayout.eye(t);
        String[] want = {"lampbar", "apanel", "pfd", "mfd", "mfdkeys", "xpdr", "rwr", "flapind", "swpanel", "trimind", "flapgate"};
        boolean all = l.panels.size() == want.length && l.panel("hsi") == null && l.panel(CockpitLayout.OVERHEAD) == null;
        for (String w : want) {
            all &= l.panel(w) != null;
        }
        check(all && l.dials.size() == 3 && l.screens.isEmpty() && l.lamps.isEmpty() && l.okLamps.isEmpty(),
                "cockpit wave plane layout: the C+ faces found - lamp strip, AP bar, PFD, MFD + keys, transponder, RWR, flaps, the wall switch panel"
                        + " (+ the pedestal's trim strip and the flap gate); 3 dials; the old six-pack, HSI, screens, gear lamps and overhead console gone ("
                        + l.panels.size() + " panels, " + l.dials.size() + " dials)");
        if (!all || l.dials.size() != 3) {
            return;
        }
        CockpitLayout.Screen lb = l.panel("lampbar"), ap = l.panel("apanel"), pfd = l.panel("pfd"), mfd = l.panel("mfd"), keys = l.panel("mfdkeys"),
                xp = l.panel("xpdr"), rwr = l.panel("rwr"), wall = l.panel("swpanel");
        CockpitLayout.Dial spd = l.dials.get(0), vsi = l.dials.get(1), alt = l.dials.get(2);
        double[] ps = size(e, pfd), ms = size(e, mfd), as = size(e, ap);
        double altDeg = 2 * Math.toDegrees(Math.atan(alt.r / CockpitLayout.len(CockpitLayout.sub(alt.c, e))));
        // the AP bar's digits: 0.3 of its height (AvionicsSVA.apPanel without annunciators: 0.42 window x 0.72)
        double digits = px(as[1] * 0.42 * 0.72);
        int[] table = CockpitDials.table(t);
        boolean order = table.length == 3 && table[0] == CockpitDials.SPEED && table[1] == CockpitDials.VSPEED && table[2] == CockpitDials.ALT
                && Math.abs(spd.c[2] - vsi.c[2]) < 0.01F && spd.c[1] > vsi.c[1] && spd.c[2] < pfd.c[2] && alt.c[2] > pfd.c[2] && mfd.c[2] > alt.c[2]
                && xp.c[2] > mfd.c[2] && rwr.c[2] > xp.c[2] && lb.c[1] > ap.c[1] && ap.c[1] > pfd.c[1] && keys.c[1] < mfd.c[1];
        boolean sizes = ps[0] > 15.5 && ps[0] < 18 && ps[1] > 10.5 && ps[1] < 12.5 && ms[0] > 9 && ms[0] < 11 && ms[1] > 9 && ms[1] < 11 && altDeg > 5.6
                && altDeg < 7 && digits >= 19;
        // the throttle corner (right, more than 27 deg aside and 22 down) holds nothing of the main panel
        boolean corner = true;
        for (CockpitLayout.Screen s : l.panels) {
            if (s == wall || "trimind".equals(s.mat) || "flapgate".equals(s.mat)) {
                continue;
            }
            for (int a = -1; a <= 1; a += 2) {
                for (int b = -1; b <= 1; b += 2) {
                    double[] lk = look(e, at(s, a * s.w / 2, b * s.h / 2));
                    corner &= !(lk[0] > 27.2 && lk[1] > 22.2);
                }
            }
        }
        double[] wl = look(e, wall.c);
        boolean wallOk = wl[0] < -15 && wl[0] > -35 && wl[1] > 20 && wl[1] < 45 && wall.w > wall.h;
        check(order && sizes && corner && wallOk, "cockpit wave plane layout: airspeed over vario left of the PFD, the altimeter right of it, the MFD,"
                + " transponder, RWR on to the right, the lamp strip over the AP bar over the PFD, the keys under the MFD; PFD " + f(ps[0]) + " x "
                + f(ps[1]) + " deg, MFD " + f(ms[0]) + " x " + f(ms[1]) + " deg, altimeter " + f(altDeg) + " deg, AP digits " + Math.round(digits)
                + " px at 1080p (>= 19); the throttle corner empty; the wall panel left-down (" + f(wl[0]) + " / " + f(wl[1]) + " deg)");
    }

    private static void heliLayout() {
        VehicleType t = VehicleType.HELI;
        CockpitLayout l = CockpitLayout.of(t);
        float[] e = CockpitLayout.eye(t);
        String[] want = {"lampbar", "pfd", "mfd", "tach2", "torque", "hoverind", "radalt", "rwr", CockpitLayout.OVERHEAD};
        boolean all = l.panels.size() == want.length;
        for (String w : want) {
            all &= l.panel(w) != null;
        }
        int[] lr = CockpitLamps.table(t);
        check(all && l.dials.size() == 1 && l.screens.isEmpty() && lr.length == l.lamps.size() && CockpitDials.table(t).length == 1,
                "cockpit wave heli layout: lamp strip, PFD, MFD, dual tach, power, hover indicator, РВ, RWR and the overhead panel found; the airspeed"
                        + " the one dial; the old dashes, main screen and annunciator row gone (" + l.panels.size() + " panels, " + l.dials.size() + " dials, "
                        + l.lamps.size() + " lamps)");
        if (!all) {
            return;
        }
        CockpitLayout.Screen pfd = l.panel("pfd"), mfd = l.panel("mfd"), ov = l.panel(CockpitLayout.OVERHEAD), lb = l.panel("lampbar"),
                tach = l.panel("tach2"), tq = l.panel("torque"), hv = l.panel("hoverind"), ra = l.panel("radalt"), rwr = l.panel("rwr");
        double[] ol = look(e, ov.c), ps = size(e, pfd);
        boolean sides = pfd.c[2] < e[2] && mfd.c[2] > e[2] && tach.c[2] > mfd.c[2] && hv.c[2] > tach.c[2] && tq.c[1] < tach.c[1] && rwr.c[1] < hv.c[1]
                && ra.c[2] < pfd.c[2] && lb.c[1] > pfd.c[1] && lb.w > 0.45F;
        // the centre cut-out: nothing of the dash straight ahead under the nose (+-5 deg of the eye's line)
        boolean cut = Math.abs(look(e, at(pfd, pfd.w / 2, 0))[0]) > 5 && Math.abs(look(e, at(mfd, -mfd.w / 2, 0))[0]) > 5;
        boolean over = ol[1] < -35 && ov.w > 0.16F && ov.w < 0.2F && ov.h > 0.28F && ov.h < 0.33F && ov.n[1] < -0.8F;
        check(sides && cut && over && ps[0] > 15, "cockpit wave heli layout: the PFD left, the MFD right, the tach / power and hover / RWR columns"
                + " beyond it, the РВ left of the PFD, the lamp strip over them; the centre cut-out (+-5 deg free); the overhead panel " + f(ov.w * 16) + " x "
                + f(ov.h * 16) + " concept units over the head (" + f(-ol[1]) + " deg up), facing down");
    }

    // ---------------------------------------------------------------- the function, config, lang

    private static void function() {
        VehicleFn f = VehicleFn.MFD_PAGE;
        boolean ok = f.appliesTo(VehicleType.PLANE) && f.appliesTo(VehicleType.HELI) && !f.appliesTo(VehicleType.JET) && !f.appliesTo(VehicleType.BUGGY)
                && f.action(VehicleType.PLANE) == -1 && !f.hold && !f.driverOnly && f.defaultKey == 40 && VehicleFn.byKey("mfdPage") == f
                && EntityVehicleSVA.A_SWITCH > 0;
        check(ok, "cockpit wave «Страница MFD»: the light plane and the helicopter only, the client's own (no packet), any rider, ' by default");
    }

    private static void configAndLang() throws java.io.IOException {
        boolean cfg = ConfigSVA.glassCockpit && ConfigSVA.radarAltCallouts && "both".equals(ConfigSVA.radarAltCalloutStyle);
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        StringBuilder miss = new StringBuilder();
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (String p : GlassMath.PAGE_KEYS) {
            keys.add("sva.mfd.page." + p);
            keys.add("sva.mfd.title." + p);
            keys.add("sva.mfd.title." + p + ".heli");
        }
        for (String k : new String[] {"off", "stby", "a", "c", "s"}) {
            keys.add("sva.xpdr.mode." + k);
        }
        for (String k : new String[] {"genOff", "batLow", "pump", "hook", "search", "heat"}) {
            keys.add("sva.ovhd.lamp." + k);
        }
        keys.add("sva.fn.mfdPage");
        keys.add("sva.fn.mfdPage.tip");
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(cfg && miss.length() == 0, "cockpit wave config (glassCockpit, radarAltCallouts on, style both) and the dynamic lang lines (pages,"
                + " titles, transponder modes, overhead lamps, the function) in ru + en" + miss);
    }
}
