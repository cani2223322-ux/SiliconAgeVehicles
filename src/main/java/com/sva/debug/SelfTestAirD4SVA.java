package com.sva.debug;

import java.util.Set;

import com.sva.vehicle.AccessMath;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Aircraft round 2, stage D4 self-tests (★22 the flight plan, mock-up docs/mockups/veh_air3_5_flightplan.png) - pure logic
 * on the dedicated server: the turn's lead, the cross / along track maths, a point-mass flight through a plan (leg switching
 * with anticipation, every point passed close, the hold after the last), a point behind on the first leg, the plan's edits
 * (max points, order, heights, delete keeping the active one), its NBT round trip and sanitising, the synced target text, the
 * AP panel word with МАРШ, the edit right (AccessMath -> RouteMath.mayEdit), the lang lines.
 */
final class SelfTestAirD4SVA {

    private SelfTestAirD4SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        lead();
        track();
        flight();
        firstLeg();
        edits();
        nbt();
        panel();
        rights();
        lang();
    }

    private static String f(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    // ---------------------------------------------------------------- the turn's lead

    private static void lead() {
        double r = RouteMath.turnRadius(1.12, 1.8);                 // the light plane at its top speed: ~36 blocks
        double l90 = RouteMath.lead(1.12, 1.8, 90), l0 = RouteMath.lead(1.12, 1.8, 0), lHair = RouteMath.lead(1.68, 1.0, 170);
        double l60 = RouteMath.lead(1.12, 1.8, -60);
        check(Math.abs(r - 35.65) < 0.1 && Math.abs(l90 - r) < 1e-6 && l0 == RouteMath.LEAD_MIN && lHair == RouteMath.LEAD_MAX
                        && Math.abs(l60 - r * Math.tan(Math.toRadians(30))) < 1e-6,
                "D4 route: the turn's lead = r x tan(turn / 2) - plane r " + f(r) + ", 90 deg " + f(l90) + ", 60 deg (left) " + f(l60)
                        + ", straight on " + f(l0) + " (min), a hairpin " + f(lHair) + " (max)");
    }

    // ---------------------------------------------------------------- cross / along track

    private static void track() {
        // east (90): right of it is south (+Z); north (0): right is east (+X); a 45 deg leg
        double c1 = RouteMath.crossTrack(0, 0, 90, 10, 5), a1 = RouteMath.alongTrack(0, 0, 90, 10, 5);
        double c2 = RouteMath.crossTrack(0, 0, 0, 3, -10), a2 = RouteMath.alongTrack(0, 0, 0, 3, -10);
        double crs = RouteMath.course(0, 0, 10, -10);
        double c3 = RouteMath.crossTrack(0, 0, crs, 10, 0), a3 = RouteMath.alongTrack(0, 0, crs, 10, 0);
        check(Math.abs(c1 - 5) < 1e-9 && Math.abs(a1 - 10) < 1e-9 && Math.abs(c2 - 3) < 1e-9 && Math.abs(a2 - 10) < 1e-9 && Math.abs(crs - 45) < 1e-9
                        && Math.abs(c3 - Math.sqrt(50)) < 1e-9 && Math.abs(a3 - Math.sqrt(50)) < 1e-9
                        && AutopilotMath.cdiDots(c1) < 0 && AutopilotMath.cdiDots(-c1) > 0,
                "D4 route: cross-track (+ right of the leg) and along-track - east leg (" + f(c1) + ", " + f(a1) + "), north (" + f(c2) + ", " + f(a2)
                        + "), 45 deg (" + f(c3) + ", " + f(a3) + "); right of the line: the CDI bar to the left");
    }

    // ---------------------------------------------------------------- a point-mass flight

    /** The plane's own turn law (PlanePhysicsSVA: CruiseMath.headingStep at the type's rate), level, at a steady speed. */
    private static void flight() {
        RouteMath.Plan p = new RouteMath.Plan();
        p.add(150, 0, 90);
        p.add(250, 120, 110);
        p.add(120, 220, 80);
        double x = 0.5, z = 0.5, yaw = -90, speed = 1.12, rate = VehicleType.PLANE.turnRate;    // facing east (yaw -90)
        double[] minD = {1e9, 1e9, 1e9}, switchD = new double[3];
        int doneAt = -1, switches = 0;
        double maxCrossLate = 0, holdHdg0 = Double.NaN, holdErr = 0;
        boolean altOk = true;
        for (int tick = 0; tick < 1400; tick++) {
            int was = p.active;
            RouteMath.Guide g = RouteMath.guide(p, x, z, CruiseMath.heading(yaw), speed, rate);
            if (g.switched) {
                for (int k = was; k < p.active && k < 3; k++) {
                    int[] w = p.pts.get(k);
                    switchD[k] = AutopilotMath.distance(x, z, w[0] + 0.5, w[1] + 0.5);
                    switches++;
                }
            }
            for (int k = 0; k < 3; k++) {
                int[] w = p.pts.get(k);
                minD[k] = Math.min(minD[k], AutopilotMath.distance(x, z, w[0] + 0.5, w[1] + 0.5));
            }
            if (!g.done && g.active == 1) {
                // the leg's height between its two points' (90 -> 110), evenly along it
                altOk &= g.alt >= 90 - 1e-9 && g.alt <= 110 + 1e-9;
            }
            if (!g.done && g.active == 2 && g.dist < 60) {
                maxCrossLate = Math.max(maxCrossLate, Math.abs(g.cross));   // settled on the last leg's line near its end
            }
            if (g.done && doneAt < 0) {
                doneAt = tick;
            }
            if (doneAt >= 0 && tick > doneAt + 120) {
                if (Double.isNaN(holdHdg0)) {
                    holdHdg0 = CruiseMath.heading(yaw);
                }
                holdErr = Math.max(holdErr, Math.abs(CruiseMath.wrap(CruiseMath.heading(yaw) - g.course)));
                altOk &= g.alt == 80;
            }
            double want = Double.isNaN(g.heading) ? CruiseMath.heading(yaw) : g.heading;
            yaw = CruiseMath.headingStep(yaw, want - 180, rate);
            double r = Math.toRadians(yaw);
            x += -Math.sin(r) * speed;
            z += Math.cos(r) * speed;
            if (doneAt >= 0 && tick > doneAt + 240) {
                break;
            }
        }
        double lead2 = RouteMath.lead(speed, rate, CruiseMath.wrap(RouteMath.course(250.5, 120.5, 120.5, 220.5) - RouteMath.course(150.5, 0.5, 250.5, 120.5)));
        check(doneAt > 0 && switches == 3 && minD[0] < 25 && minD[1] < 30 && minD[2] < RouteMath.ARRIVE + 2 && switchD[1] > RouteMath.ARRIVE
                        && switchD[1] <= lead2 + speed + 1e-6 && maxCrossLate < 3 && holdErr < 2 && altOk,
                "D4 route: a point-mass light plane flies a 3-point plan - points passed within " + f(minD[0]) + " / " + f(minD[1]) + " / " + f(minD[2])
                        + " blocks, T2 switched " + f(switchD[1]) + " blocks before it (lead " + f(lead2) + "), on the last leg's line (" + f(maxCrossLate)
                        + " off), done at tick " + doneAt + ", then the last course held (" + f(holdErr) + " deg) at the last height");
    }

    /** Direct to the first point: a point behind but outside half the turn radius is flown to, not skipped. */
    private static void firstLeg() {
        RouteMath.Plan p = new RouteMath.Plan();
        p.add(-40, 0, 80);                                          // 40 blocks behind an east-bound plane
        p.add(200, 0, 80);
        RouteMath.Guide g = RouteMath.guide(p, 0.5, 0.5, 90, 1.12, 1.8);
        RouteMath.Plan q = new RouteMath.Plan();
        q.add(-5, 3, 80);                                           // just behind, inside the turn's circle: passed
        q.add(200, 0, 80);
        RouteMath.Guide h = RouteMath.guide(q, 0.5, 0.5, 90, 1.12, 1.8);
        check(g.active == 0 && !g.switched && Math.abs(g.course - 270) < 1 && h.active == 1 && h.switched && RouteMath.guide(new RouteMath.Plan(), 0, 0, 0, 1, 1) == null
                        && RouteMath.reach(1.12, 1.8, 170, 500, 500) == RouteMath.ARRIVE && RouteMath.reach(1.12, 1.8, 90, 500, 30) == 15,
                "D4 route: direct to the first point - one 40 blocks behind is turned back to (course " + f(g.course) + "), one inside the turn's circle"
                        + " behind counts as passed; a hairpin is flown over the point, a short leg caps the lead at half; an empty plan gives no guidance");
    }

    // ---------------------------------------------------------------- edits, NBT, the synced target

    private static void edits() {
        RouteMath.Plan p = new RouteMath.Plan();
        boolean all = true;
        for (int i = 0; i < RouteMath.MAX; i++) {
            all &= p.add(i * 10, -i * 10, 60 + i);
        }
        boolean over = p.add(999, 999, 80);
        p.active = 5;
        p.remove(2);                                                // before the active: it moves up with its point
        boolean actKept = p.active == 4 && p.size() == RouteMath.MAX - 1;
        p.remove(4);                                                // the active one itself: the next becomes active
        boolean actNext = p.active == 4 && p.pts.get(4)[0] == 60;
        boolean moved = p.move(0, 1) && p.pts.get(0)[0] == 10 && p.pts.get(1)[0] == 0 && !p.move(0, -1) && !p.move(p.size() - 1, 1);
        p.addAlt(0, 1000);
        p.addAlt(1, -1000);
        boolean alts = p.pts.get(0)[2] == RouteMath.ALT_MAX && p.pts.get(1)[2] == RouteMath.ALT_MIN;
        RouteMath.Plan c = p.copy();
        c.pts.get(0)[0] = 12345;
        boolean deep = p.pts.get(0)[0] != 12345 && !p.samePoints(c) && p.samePoints(p.copy());
        check(all && !over && actKept && actNext && moved && alts && deep,
                "D4 route: at most " + RouteMath.MAX + " points (the 17th refused), delete keeps the active point (" + actKept + " / " + actNext
                        + "), up / down within the list, heights within " + RouteMath.ALT_MIN + ".." + RouteMath.ALT_MAX + ", copies are deep");
    }

    private static void nbt() {
        RouteMath.Plan p = new RouteMath.Plan();
        p.add(262, -1436, 60);
        p.add(452, -1616, 90);
        p.add(712, -1456, 80);
        p.active = 2;
        NBTTagCompound t = new NBTTagCompound();
        RouteMath.write(p, t);
        RouteMath.Plan q = RouteMath.read(t);
        // a hostile array: 20 points, out-of-range heights / coordinates, the active one past the end
        int[] bad = new int[20 * 3];
        for (int i = 0; i < 20; i++) {
            bad[i * 3] = i == 0 ? Integer.MAX_VALUE : i;
            bad[i * 3 + 1] = -i;
            bad[i * 3 + 2] = i == 1 ? -50 : 9999;
        }
        RouteMath.Plan s = RouteMath.sanitize(bad, 99);
        NBTTagCompound e = new NBTTagCompound();
        RouteMath.write(new RouteMath.Plan(), e);
        RouteMath.Plan none = RouteMath.read(new NBTTagCompound());
        check(q.samePoints(p) && q.active == 2 && s.size() == RouteMath.MAX && s.active == RouteMath.MAX && s.pts.get(0)[0] == RouteMath.COORD_MAX
                        && s.pts.get(1)[2] == RouteMath.ALT_MIN && s.pts.get(2)[2] == RouteMath.ALT_MAX && e.hasKey(RouteMath.K_POINTS)
                        && RouteMath.read(e).isEmpty() && none.isEmpty() && none.active == 0,
                "D4 route: NBT round trip (3 points, active T3); a bad array sanitised - 16 points, coordinates / heights clamped, active within 0..size;"
                        + " an empty plan is written (the key kept) and read back empty");
        // the synced target: the active point and the leg's start; the first leg has none; flown through: the last
        p.active = 1;
        int[] a = RouteMath.parseTarget(RouteMath.targetText(p));
        p.active = 0;
        int[] b = RouteMath.parseTarget(RouteMath.targetText(p));
        p.active = 3;
        int[] c = RouteMath.parseTarget(RouteMath.targetText(p));
        check(a != null && a[0] == 1 && a[1] == 3 && a[2] == 452 && a[3] == -1616 && a[4] == 90 && a[5] == 262 && a[6] == -1436 && b != null
                        && b[5] == Integer.MIN_VALUE && c != null && c[0] == 3 && c[2] == 712 && c[5] == 452 && RouteMath.targetText(new RouteMath.Plan()).isEmpty()
                        && RouteMath.parseTarget("") == null && RouteMath.parseTarget("1,2,x,4,5,6,7") == null,
                "D4 route: the synced target text (DW_ROUTE's third part) round trips - active, count, the point, the leg's start; junk refused");
    }

    private static void panel() {
        AutopilotMath.Panel p = new AutopilotMath.Panel();
        p.on = true;
        p.modes = AutopilotMath.M_ROUTE | AutopilotMath.M_SPD | AutopilotMath.M_CAP;
        p.runway = true;
        p.phase = AutopilotMath.PH_FLARE;
        p.hdg = 359;
        p.alt = 1023;
        p.kmh = 80;
        p.vs = 0.5;
        p.routeN = 16;
        p.routeAct = 16;
        AutopilotMath.Panel q = AutopilotMath.unpack(AutopilotMath.pack(p), AutopilotMath.pack2(p));
        p.modes = AutopilotMath.M_HDG;
        p.routeAct = 2;
        p.routeN = 5;
        AutopilotMath.Panel r = AutopilotMath.unpack(AutopilotMath.pack(p), AutopilotMath.pack2(p));
        check(q.modes == (AutopilotMath.M_ROUTE | AutopilotMath.M_SPD | AutopilotMath.M_CAP) && q.phase == AutopilotMath.PH_FLARE && q.hdg == 359
                        && q.alt == 1023 && q.routeN == 16 && q.routeAct == 16 && q.routeDone() && r.modes == AutopilotMath.M_HDG && r.routeAct == 2
                        && r.routeN == 5 && !r.routeDone() && AutopilotMath.bit(AutopilotMath.G_ROUTE) == AutopilotMath.M_ROUTE
                        && AutopilotMath.GROUPS == AutopilotMath.GROUP_KEYS.length && (AutopilotMath.M_ALL & 0xFF) == AutopilotMath.M_ALL,
                "D4 AP panel: МАРШ in the synced word (bit 29) beside the stage C / D1 fields, the active point / count in the second word, «КОН» when"
                        + " flown through; the mode fits the Input packet's byte");
    }

    private static void rights() {
        int owner = AccessMath.rights(true, true, false, AccessMath.OWNER, -1, 0, -1);
        int stranger = AccessMath.rights(true, false, false, AccessMath.OWNER, -1, AccessMath.ALL, -1);
        int friend = AccessMath.rights(true, false, false, AccessMath.FRIENDS, AccessMath.DRIVE, 0, -1);
        int rider = AccessMath.rights(true, false, false, AccessMath.FRIENDS, AccessMath.PASSENGER, 0, -1);
        int card = AccessMath.rights(true, false, false, AccessMath.FRIENDS, -1, 0, AccessMath.DRIVE);
        boolean planeKinds = CruiseMath.kind(VehicleType.PLANE.movement, true) == CruiseMath.AUTOPILOT
                && CruiseMath.kind(VehicleType.JET.movement, true) == CruiseMath.AUTOPILOT && CruiseMath.kind(VehicleType.TILT.movement, true) == CruiseMath.AUTOPILOT
                && CruiseMath.kind(VehicleType.HELI.movement, true) != CruiseMath.AUTOPILOT;
        check(RouteMath.mayEdit(true, owner) && !RouteMath.mayEdit(true, stranger) && RouteMath.mayEdit(true, friend) && !RouteMath.mayEdit(true, rider)
                        && RouteMath.mayEdit(true, card) && !RouteMath.mayEdit(false, owner) && planeKinds,
                "D4 route: who may set it - the owner, a friend / key card with «водить»; not a passenger-only friend, not a stranger on an owner-only"
                        + " lock; only aircraft (plane, jet, tiltrotor - not the helicopter)");
    }

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        boolean ok = ru != null && en != null;
        String[] keys = {"sva.ap.group.route", "sva.ap.tip.route", "sva.ap.route.wp", "sva.ap.route.end", "sva.ap.route.line", "sva.hud.ap.route",
                "sva.map.tab.vehicles", "sva.map.tab.route", "sva.map.route.send", "sva.map.route.total", "sva.msg.route.set"};
        for (String k : keys) {
            ok &= ru.contains(k) && en.contains(k);
        }
        check(ok, "D4 route: the panel / map / message lines in ru_RU and en_US");
    }
}
