package com.sva.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sc.init.ModItems;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AdUpgradeMath;
import com.sva.airdefense.AirportCodeMath;
import com.sva.airdefense.CoverageMath;
import com.sva.airdefense.IffMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneEditMath;
import com.sva.airdefense.ZoneMath;
import com.sva.airport.Cpdlc;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Wave A self-test (plan-next §9 A: the air defence finished) - pure logic, the dedicated server runs it:
 * <ul>
 * <li>A1 the zone editor: corners / edges / centre / the turn knob / the radius dragged (the opposite corner stays), the
 * snap, the turned rectangle's geometry (inside, distance, ahead, corners, the NBT), check(), paste, undo;</li>
 * <li>A2 the time to the border on the track, its levels;</li>
 * <li>A3 the RCS extras (gear, stores, doors, the bump) on the detection range;</li>
 * <li>A4 the airport's codes (assignment, the friendly airports, near the airport, the trusted list);</li>
 * <li>A5 the 7600 decision flow;</li>
 * <li>A7 the tubes' reload with overclockers, the modules' NBT round trip through the item;</li>
 * <li>A9 the coverage grid on a synthetic terrain (a hill's dead zone);</li>
 * <li>A10 the IFF reasons and its state machine (interrogate - no reply - warn - handoff / remind - resolved / answered);</li>
 * <li>the config defaults and the dynamic lang keys.</li>
 * </ul>
 */
final class SelfTestAdWaveASVA {

    private SelfTestAdWaveASVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    static void run() throws Exception {
        zoneGeometry();
        zoneEdit();
        prediction();
        rcs();
        codes();
        noRadio();
        reloadAndModules();
        coverage();
        iff();
        config();
        lang();
    }

    private static ZoneMath.Zone rect(int x0, int z0, int x1, int z1, int rot) {
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "R";
        z.shape = ZoneMath.RECT;
        z.x0 = x0;
        z.z0 = z0;
        z.x1 = x1;
        z.z1 = z1;
        z.rot = rot;
        return z;
    }

    private static ZoneMath.Zone circle(int cx, int cz, int r) {
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "C";
        z.shape = ZoneMath.CIRCLE;
        z.cx = cx;
        z.cz = cz;
        z.r = r;
        return z;
    }

    // ---------------------------------------------------------------- A1: the turned rectangle

    private static void zoneGeometry() {
        // a 100 x 20 box (x 0..99, z 0..19), middle (50, 10); turned 90: it lies along z, 20 wide in x
        ZoneMath.Zone z0 = rect(0, 0, 99, 19, 0), z90 = rect(0, 0, 99, 19, 90);
        check(ZoneMath.contains2D(z0, 90, 10) && !ZoneMath.contains2D(z90, 90, 10) && ZoneMath.contains2D(z90, 50, 50)
                && ZoneMath.contains2D(z90, 55, -35) && !ZoneMath.contains2D(z90, 65, 10),
                "wave A zones: a rectangle turned 90 deg lies across - (90,10) out, (50,50) and (55,-35) in, (65,10) out");
        double[][] c = ZoneMath.corners(z90);
        check(near(c[0][0], 60, 1e-6) && near(c[0][1], -40, 1e-6) && near(c[2][0], 40, 1e-6) && near(c[2][1], 60, 1e-6),
                "wave A zones: the turned corners - NW goes to (60,-40), SE to (40,60) (clockwise on the map)");
        check(near(ZoneMath.distance2D(z90, 50, 70), 10, 1e-6) && near(ZoneMath.distance2D(z90, 75, 10), 15, 1e-6) && ZoneMath.distance2D(z90, 50, 0) == 0,
                "wave A zones: the distance to a turned rectangle (10 south of its end, 15 east of its side, 0 inside)");
        // ahead: from the east flying west (270) - the turned box's east side is x 60
        check(near(ZoneMath.ahead(z90, 100, 10, 270, 500), 40, 1e-6) && ZoneMath.ahead(z90, 100, 100, 270, 500) < 0
                && near(ZoneMath.ahead(rect(0, 0, 99, 19, 45), 50 + 100 * Math.sin(Math.toRadians(135)), 10 - 100 * Math.cos(Math.toRadians(135)), 315, 500),
                        100 - 50, 1e-6),
                "wave A zones: ahead() on a turned rectangle (40 blocks flying west; a miss; turned 45 - along its long axis, 50 to its end)");
        NBTTagCompound t = new NBTTagCompound();
        rect(0, 0, 9, 9, 400).write(t);
        ZoneMath.Zone r = new ZoneMath.Zone();
        r.read(t);
        ZoneMath.Zone cz = circle(0, 0, 5);
        cz.rot = 30;
        NBTTagCompound t2 = new NBTTagCompound();
        cz.write(t2);
        ZoneMath.Zone cr = new ZoneMath.Zone();
        cr.read(t2);
        NBTTagCompound old = new NBTTagCompound();
        rect(0, 0, 9, 9, 0).write(old);
        old.removeTag("Rot");
        ZoneMath.Zone o = new ZoneMath.Zone();
        o.read(old);
        check(r.rot == 40 && cr.rot == 0 && o.rot == 0 && rect(0, 0, 9, 9, 33).copy().rot == 33,
                "wave A zones: the turn in NBT - 400 read as 40, a circle never turned, an old save without it 0, copy() keeps it");
        double[] np = ZoneMath.nearestPoint(circle(0, 0, 10), 30.5, 0.5), nr = ZoneMath.nearestPoint(z90, 50, 100);
        check(near(np[0], 10.5, 1e-6) && near(np[1], 0.5, 1e-6) && near(nr[0], 50, 1e-6) && near(nr[1], 60, 1e-6),
                "wave A zones: the nearest point of the outline (circle; turned rectangle) - the HUD's pointer");
        check(ZoneMath.outline(circle(0, 0, 10), 16).length == 16 && ZoneMath.outline(z90, 16).length == 4, "wave A zones: the outline polyline (circle n, rectangle 4)");
    }

    // ---------------------------------------------------------------- A1: the editor's handles

    private static void zoneEdit() {
        ZoneMath.Zone z = rect(0, 0, 99, 19, 0);
        // the SE corner dragged out by (+20, +10): the NW corner stays
        ZoneMath.Zone a = ZoneEditMath.drag(z, ZoneEditMath.H_CORNER + 2, 100, 20, 120, 30, false);
        check(a.x0 == 0 && a.z0 == 0 && a.x1 == 119 && a.z1 == 29 && z.x1 == 99, "wave A editor: the SE corner dragged - 120 x 30, the NW corner and the original stay");
        // a turned rectangle's corner: the opposite corner stays in the world (within a block of rounding)
        ZoneMath.Zone t = rect(0, 0, 99, 19, 30);
        double[] nw = ZoneMath.corners(t)[0], se = ZoneMath.corners(t)[2];
        ZoneMath.Zone b = ZoneEditMath.drag(t, ZoneEditMath.H_CORNER + 2, se[0], se[1], se[0] + 15, se[1] + 25, false);
        double[] nw2 = ZoneMath.corners(b)[0];
        check(Math.hypot(nw2[0] - nw[0], nw2[1] - nw[1]) <= 1.2 && b.rot == 30 && b.x1 - b.x0 != t.x1 - t.x0,
                "wave A editor: a turned rectangle's corner dragged - the turn kept, its size changed, the opposite corner stays ("
                        + String.format(java.util.Locale.ROOT, "%.2f", Math.hypot(nw2[0] - nw[0], nw2[1] - nw[1])) + " bl)");
        ZoneMath.Zone e = ZoneEditMath.drag(z, ZoneEditMath.H_EDGE, 50, 0, 50, -30, false);
        check(e.z0 == -30 && e.z1 == 19 && e.x0 == 0 && e.x1 == 99, "wave A editor: the north edge dragged up 30 - only that side moves");
        ZoneMath.Zone m = ZoneEditMath.drag(z, ZoneEditMath.H_CENTRE, 50, 10, 250, -90, false);
        check(m.x0 == 200 && m.z0 == -100 && m.x1 - m.x0 == 99 && m.z1 - m.z0 == 19, "wave A editor: the centre dragged - the whole zone moves, its size kept");
        ZoneMath.Zone rt = ZoneEditMath.drag(z, ZoneEditMath.H_ROTATE, 50, -2, 150, 10, false), rs = ZoneEditMath.drag(z, ZoneEditMath.H_ROTATE, 50, -2, 150, 3, true);
        check(rt.rot == 90 && rs.rot % ZoneEditMath.SNAP_DEG == 0 && rs.rot == 90 && ZoneEditMath.snapDeg(-10, false) == 350 && ZoneEditMath.snapDeg(359.6, false) == 0,
                "wave A editor: the turn knob - the mouse east of the middle: 90 deg; with snap the 15 deg steps; angles wrap");
        ZoneMath.Zone c = circle(0, 0, 32), cr = ZoneEditMath.drag(c, ZoneEditMath.H_RADIUS, 32.5, 0.5, 80.5, 0.5, false),
                cs = ZoneEditMath.drag(c, ZoneEditMath.H_RADIUS, 32.5, 0.5, 83.5, 0.5, true), cb = ZoneEditMath.drag(c, ZoneEditMath.H_RADIUS, 0, 0, 5000, 0, false);
        check(cr.r == 80 && cs.r == 80 && cb.r == ZoneMath.MAX_HALF, "wave A editor: the radius knob (80; 83 snapped to 80 on the 8 grid; capped at 512)");
        ZoneMath.Zone sn = ZoneEditMath.drag(z, ZoneEditMath.H_CENTRE, 50, 10, 53, 13, true);
        check(sn.x0 % ZoneEditMath.SNAP == 0 && sn.z0 % ZoneEditMath.SNAP == 0 && ZoneEditMath.snap(13, true) == 16 && ZoneEditMath.snap(13.4, false) == 13,
                "wave A editor: snap - positions to the 8 grid, off - whole blocks");
        ZoneMath.Zone tiny = ZoneEditMath.drag(z, ZoneEditMath.H_CORNER + 2, 100, 20, -50, -50, false);
        check(tiny.x1 - tiny.x0 + 1 >= ZoneEditMath.MIN_SIDE && tiny.z1 - tiny.z0 + 1 >= ZoneEditMath.MIN_SIDE,
                "wave A editor: a corner dragged past the opposite one - the zone never turns inside out (" + ZoneEditMath.MIN_SIDE + " at least)");
        check(ZoneEditMath.pick(z, 100, 20, 3) == ZoneEditMath.H_CORNER + 2 && ZoneEditMath.pick(z, 50, 0.5, 3) == ZoneEditMath.H_EDGE
                && ZoneEditMath.pick(z, 30, 10, 3) == ZoneEditMath.H_CENTRE && ZoneEditMath.pick(z, 300, 300, 3) == ZoneEditMath.H_NONE
                && ZoneEditMath.pick(z, 50, -ZoneEditMath.ROT_KNOB, 3) == ZoneEditMath.H_ROTATE && ZoneEditMath.pick(c, 32.5, 0.5, 3) == ZoneEditMath.H_RADIUS,
                "wave A editor: the handle under the mouse (a corner, an edge, inside - the centre, far - none, the turn knob, the radius knob)");
        check(ZoneEditMath.handles(z).size() == 10 && ZoneEditMath.handles(c).size() == 2, "wave A editor: a rectangle has 10 handles, a circle 2");
        ZoneMath.Zone big = rect(0, 0, 2000, 10, 0), bad = rect(0, 0, 10, 10, 0), noName = rect(0, 0, 10, 10, 0);
        bad.lo = 100;
        bad.hi = 50;
        noName.name = "";
        check(ZoneEditMath.check(z) == ZoneEditMath.OK && ZoneEditMath.check(big) == ZoneEditMath.E_TOO_BIG && ZoneEditMath.check(bad) == ZoneEditMath.E_BAND
                && ZoneEditMath.check(noName) == ZoneEditMath.E_NAME, "wave A editor: check() says what the server would cut (too big, the band, no name)");
        ZoneMath.Zone p = ZoneEditMath.pasteAt(z, 1000, 1000, false);
        check(p.name.equals("R-2") && near(ZoneMath.middle(p)[0], 1000, 0.6) && near(ZoneMath.middle(p)[1], 1000, 0.6) && p.x1 - p.x0 == 99,
                "wave A editor: paste - the copy at the map's centre, its size kept, -2 after the name");
        ZoneEditMath.History h = new ZoneEditMath.History();
        h.push(z);
        h.push(a);
        ZoneMath.Zone u1 = h.undo(), u2 = h.undo(), u3 = h.undo();
        for (int i = 0; i < ZoneEditMath.UNDO_MAX + 5; i++) {
            h.push(z);
        }
        check(u1 != null && u1.x1 == 119 && u2 != null && u2.x1 == 99 && u3 == null && h.size() == ZoneEditMath.UNDO_MAX,
                "wave A editor: undo - the last edit first, then the one before, then nothing; " + ZoneEditMath.UNDO_MAX + " deep");
        // the server's read() is the validation: a client's turned zone goes through it unchanged
        NBTTagCompound tt = new NBTTagCompound();
        b.write(tt);
        ZoneMath.Zone back = new ZoneMath.Zone();
        back.read(tt);
        check(back.rot == b.rot && back.x0 == b.x0 && back.z1 == b.z1, "wave A editor: an edited zone survives the server's sanitising read unchanged");
    }

    // ---------------------------------------------------------------- A2: the prediction

    private static void prediction() {
        ZoneMath.Zone c = circle(0, 0, 50);
        // 150 blocks from the centre flying straight at it at 2 b/t: 100 blocks to the border = 2.5 s
        double s = ZoneMath.secondsToEntry(c, 150.5, 100, 0.5, -2, 0, true, 30);
        check(near(s, 2.5, 1e-6), "wave A prediction: 100 blocks to the border at 2 b/t - 2.5 s (" + s + ")");
        check(ZoneMath.secondsToEntry(c, 150.5, 100, 0.5, 0, 2, true, 30) < 0 && ZoneMath.secondsToEntry(c, 150.5, 100, 0.5, -0.01, 0, true, 30) < 0
                && ZoneMath.secondsToEntry(c, 150.5, 100, 0.5, -0.5, 0, true, 5) < 0 && ZoneMath.secondsToEntry(c, 10, 100, 10, 0, 0, true, 30) == 0,
                "wave A prediction: a track that misses - none, too slow - none, farther than the horizon - none, inside - 0");
        ZoneMath.Zone band = circle(0, 0, 50);
        band.lo = 0;
        band.hi = 60;
        check(ZoneMath.secondsToEntry(band, 150.5, 100, 0.5, -2, 0, true, 30) < 0 && ZoneMath.secondsToEntry(band, 150.5, 40, 0.5, -2, 0, true, 30) > 0,
                "wave A prediction: above the zone's band - nothing; in it - the seconds");
        // the track, not the nose: flying west (vx < 0) at a zone east of it - nothing, though the nose may point east
        check(ZoneMath.secondsToEntry(c, -150.5, 100, 0.5, -2, 0, true, 30) < 0, "wave A prediction: the track counts (moving away - nothing)");
        check(ZoneMath.predictLevel(-1, 30) == 0 && ZoneMath.predictLevel(25, 30) == 1 && ZoneMath.predictLevel(9, 30) == 2 && ZoneMath.predictLevel(0, 30) == 3
                && ZoneMath.predictLevel(31, 30) == 0, "wave A prediction: the levels - amber within 30 s, red within 10 s, inside");
        ZoneMath.Zone r = rect(0, 0, 99, 19, 90);
        double sr = ZoneMath.secondsToEntry(r, 100, 100, 10, -1, 0, true, 60);
        check(near(sr, 2.0, 1e-6), "wave A prediction: a turned rectangle - 40 blocks to its side at 1 b/t is 2 s (" + sr + ")");
    }

    // ---------------------------------------------------------------- A3: the RCS extras

    private static void rcs() {
        double base = RadarMath.detectRange(RadarMath.SURVEY, RadarMath.rcs(VehicleType.PLANE), 1, 1);
        double gear = RadarMath.detectRange(RadarMath.SURVEY, RadarMath.rcs(VehicleType.PLANE), RadarMath.extrasMul(true, false, false, false), 1);
        check(near(gear / base, 1.2, 1e-9) && near(RadarMath.extrasMul(false, true, false, false), 1.2, 1e-9)
                && near(RadarMath.extrasMul(false, false, true, false), 1.1, 1e-9) && near(RadarMath.extrasMul(false, false, false, true), 1.15, 1e-9)
                && RadarMath.extrasMul(false, false, false, false) == 1,
                "wave A RCS: gear out / stores +20 %, cargo doors +10 %, the reverser / afterburner bump +15 % of the range (light plane "
                        + Math.round(base) + " -> " + Math.round(gear) + " with gear)");
        double all = RadarMath.extrasMul(true, true, true, true), coated = RadarMath.stealthMul(new int[] {com.sva.vehicle.TuningMath.RAM_COAT, -1, -1, -1}, true);
        check(near(all, 1.2 * 1.2 * 1.1 * 1.15, 1e-9) && near(coated * all, 0.6 * all, 1e-9),
                "wave A RCS: the extras multiply together and with the coating / hull (x" + String.format(java.util.Locale.ROOT, "%.3f", all) + ")");
        check(!RadarMath.gearOut(false, 1, 0) && RadarMath.gearOut(true, AnimMath.EXTEND_HEIGHT - 1, 2) && RadarMath.gearOut(true, 50, AnimMath.EXTEND_SPEED / 2)
                && !RadarMath.gearOut(true, 50, 2), "wave A RCS: retracting gear out low or slow, up in fast flight; fixed gear - no extra");
        double liner = RadarMath.detectRange(RadarMath.SURVEY, RadarMath.rcs(VehicleType.LINER), 1, 1);
        check(RadarMath.rcs(VehicleType.LINER) == 20 && near(liner, 600 * Math.pow(0.5, 0.25), 1e-6),
                "wave A RCS: the liner has an RCS now (20 - the surveillance radar sees it at " + Math.round(liner) + " blocks)");
        check(RadarMath.bumpOn(100, 100 + RadarMath.BUMP_TICKS) && !RadarMath.bumpOn(100, 101 + RadarMath.BUMP_TICKS) && !RadarMath.bumpOn(-1, 5),
                "wave A RCS: the bump lasts " + RadarMath.BUMP_TICKS + " ticks after the reverser / afterburner was last seen");
    }

    // ---------------------------------------------------------------- A4: the airport's codes

    private static ZoneMath.Zone apt(String icao, String tower, String owner) {
        ZoneMath.Zone z = circle(0, 0, 200);
        z.type = ZoneMath.AERODROME;
        z.name = icao;
        z.kp = tower;
        z.owner = owner;
        z.codeLo = 04100;
        z.codeHi = 04177;
        return z;
    }

    private static void codes() {
        Set<Integer> used = new HashSet<Integer>(Arrays.asList(Integer.valueOf(04100), Integer.valueOf(04101)));
        check(AirportCodeMath.assign(04100, 04177, used, -1) == 04102 && AirportCodeMath.assign(04100, 04177, used, 04150) == 04150
                && AirportCodeMath.assign(04100, 04177, used, 04101) == 04102 && AirportCodeMath.assign(04177, 04100, null, 07000) == 04100,
                "wave A codes: the first free code of the range (4102), its own one kept (4150), a used one not (4101), a code outside not");
        Set<Integer> full = new HashSet<Integer>();
        for (int c = 04100; c <= 04103; c++) {
            full.add(Integer.valueOf(c));
        }
        check(AirportCodeMath.assign(04100, 04103, full, -1) == -1 && AirportCodeMath.assign(07476, 07501, null, -1) == 07476
                && AirportCodeMath.assign(07500, 07500, null, -1) == -1 && !AirportCodeMath.assignable(07700) && AirportCodeMath.assignable(04123),
                "wave A codes: a full range - none; the special codes (7500 / 7600 / 7700 / 7000) never given");
        ZoneMath.Zone own = apt("UBTW", "Северный", "Bob");
        List<String> acc = Arrays.asList("Alice"), trust = AirportCodeMath.parseTrusted("ubtw, Южный;ZZZZ");
        check(AirportCodeMath.friendly(own, "bob", null, null) && AirportCodeMath.friendly(apt("XXXX", "", "Alice"), "Bob", acc, null)
                && AirportCodeMath.friendly(apt("UBTW", "", "Eve"), "Bob", acc, trust) && AirportCodeMath.friendly(apt("QQQQ", "ЮЖНЫЙ", "Eve"), "Bob", acc, trust)
                && !AirportCodeMath.friendly(apt("QQQQ", "Город", "Eve"), "Bob", acc, trust),
                "wave A codes: a friendly airport - the post's owner's, a friend's, trusted by ICAO or by the tower's name; a stranger's not");
        ZoneMath.Zone forb = apt("UBTW", "", "Bob");
        forb.type = ZoneMath.FORBIDDEN;
        check(!AirportCodeMath.friendly(forb, "Bob", null, null), "wave A codes: only an aerodrome zone is an airport");
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>(Arrays.asList(own));
        check(AirportCodeMath.friendOf(zs, 04120, 100, 80, 0, true, "Bob", null, null) == own
                && AirportCodeMath.friendOf(zs, 04120, 200 + AirportCodeMath.NEAR - 5, 80, 0, true, "Bob", null, null) == own
                && AirportCodeMath.friendOf(zs, 04120, 200 + AirportCodeMath.NEAR + 20, 80, 0, true, "Bob", null, null) == null
                && AirportCodeMath.friendOf(zs, 02345, 100, 80, 0, true, "Bob", null, null) == null
                && AirportCodeMath.friendOf(zs, 04120, 100, 80, 0, true, "Eve", null, null) == null,
                "wave A codes: the friend - its code in the zone or within " + (int) AirportCodeMath.NEAR + " of it; farther, another code, another owner - not");
        check(trust.size() == 3 && trust.get(0).equals("UBTW") && trust.get(1).equals("ЮЖНЫЙ") && AirportCodeMath.joinTrusted(trust).equals("UBTW, ЮЖНЫЙ, ZZZZ"),
                "wave A codes: the trusted list parsed (commas, spaces, semicolons; upper case) and shown back");
        // the classes: a friendly airport's code makes it a friend (no fire, no warnings)
        int cls = RoeMath.classify(false, true, 04120, true, false, false, false, true);
        check(cls == RoeMath.FRIEND && RoeMath.fireBlock(true, false, false, false, false, false, true, false, true) == RoeMath.B_SPECIAL
                && !RoeMath.warnsAllowed(RoeMath.B_SPECIAL), "wave A codes: the airport's code is FRIEND - no fire, no warnings, even in a forbidden zone");
        Cpdlc.Msg m = new Cpdlc.Msg();
        m.up = true;
        m.type = Cpdlc.U_SQUAWK;
        m.arg = 04123;
        check(Cpdlc.args(m)[0].equals("4123") && Cpdlc.needsResponse(Cpdlc.U_SQUAWK) && Cpdlc.needsResponse(Cpdlc.U_IFF) && Cpdlc.key(m).equals("sva.cpdlc.u.13"),
                "wave A codes: the uplink КОД ОТВЕТЧИКА 4123 wants a response (WILCO sets the code); the IFF call too");
    }

    // ---------------------------------------------------------------- A5: 7600

    private static void noRadio() {
        int timer = 2400, friend = 12000;
        NoRadioMath.Entry e = new NoRadioMath.Entry(1000);
        check(NoRadioMath.state(e, 1000, timer) == NoRadioMath.S_PENDING && NoRadioMath.holdFire(NoRadioMath.S_PENDING) && NoRadioMath.listed(NoRadioMath.S_PENDING)
                && NoRadioMath.secondsLeft(e, 1000, timer) == 120 && NoRadioMath.secondsLeft(e, 3390, timer) == 1,
                "wave A 7600: pending - no fire, in the decision list, the timer runs (120 s, then 1 s)");
        check(NoRadioMath.state(e, 1000 + timer, timer) == NoRadioMath.S_DEFAULT && !NoRadioMath.holdFire(NoRadioMath.S_DEFAULT)
                && NoRadioMath.secondsLeft(e, 1000 + timer, timer) == 0, "wave A 7600: no decision in time - the default (the usual rules)");
        NoRadioMath.Entry f = new NoRadioMath.Entry(1000);
        NoRadioMath.decide(f, NoRadioMath.D_FRIEND, 1100, friend);
        check(NoRadioMath.state(f, 1200, timer) == NoRadioMath.S_FRIEND && NoRadioMath.friend(NoRadioMath.S_FRIEND)
                && NoRadioMath.state(f, 1100 + friend, timer) == NoRadioMath.S_DEFAULT && NoRadioMath.secondsLeft(f, 1200, timer) == 0,
                "wave A 7600: «свой» - a friend for its time, then the usual rules");
        NoRadioMath.Entry c = new NoRadioMath.Entry(1000);
        NoRadioMath.decide(c, NoRadioMath.D_CHECK, 1100, friend);
        NoRadioMath.Entry n = new NoRadioMath.Entry(1000);
        NoRadioMath.decide(n, 7, 1100, friend);
        check(NoRadioMath.state(c, 999999, timer) == NoRadioMath.S_CHECK && NoRadioMath.holdFire(NoRadioMath.S_CHECK) && !NoRadioMath.listed(NoRadioMath.S_CHECK)
                && n.decision == NoRadioMath.D_NONE, "wave A 7600: «проверить» - track only, no fire for as long as it squawks 7600; a bad answer ignored");
        // through the classes: pending -> NO_RADIO, default -> stranger / violator, friend -> FRIEND
        check(RoeMath.classify(false, true, XpdrMath.NO_RADIO, false, false, false, true, true) == RoeMath.NO_RADIO
                && RoeMath.classify(false, true, XpdrMath.NO_RADIO, false, false, false, false, true) == RoeMath.VIOLATOR
                && RoeMath.classify(false, true, XpdrMath.NO_RADIO, false, false, true, false, true) == RoeMath.FRIEND,
                "wave A 7600: the classes - pending NO RADIO, the default a violator in a forbidden zone, «свой» a friend");
        check(NoRadioMath.state(e, 1000, 0) == NoRadioMath.S_DEFAULT && NoRadioMath.state(null, 0, timer) == NoRadioMath.S_DEFAULT,
                "wave A 7600: a timer of 0 - no waiting; no entry - the default");
    }

    // ---------------------------------------------------------------- A7: the reload, the modules in the item

    private static void reloadAndModules() {
        int oc2 = AdUpgradeMath.interval(com.sva.airdefense.BallisticsMath.MISSILE_RELOAD, 2, AdUpgradeMath.MIN_RELOAD);
        long[] fired = {1000 - 40, -1000, -1000, 1000 - 5};
        check(oc2 == 29 && AdUpgradeMath.tubesReloading(fired, 1000, com.sva.airdefense.BallisticsMath.MISSILE_RELOAD) == 2
                && AdUpgradeMath.tubesReloading(fired, 1000, oc2) == 1 && AdUpgradeMath.tubesReloading(fired, 1000, 1) == 0
                && AdUpgradeMath.tubesReloading(null, 1000, 60) == 0,
                "wave A reload: two overclockers - 29 ticks; a tube fired 40 ticks ago is still reloading at 60, refilled at 29");
        ItemStack oc = ModItems.upgrade.stackOf(UpgradeType.OVERCLOCKER), eco = ModItems.upgrade.stackOf(UpgradeType.ECONOMIZER);
        oc.stackSize = 3;
        ItemStack[] slots = {oc, null, eco, null};
        NBTTagCompound t = new NBTTagCompound();
        int n = TileEntityAirDefenseSVA.writeModules(t, slots);
        ItemStack[] back = TileEntityAirDefenseSVA.readModules(t);
        check(n == 2 && back[0] != null && back[0].stackSize == 3 && ItemStack.areItemStacksEqual(back[0], oc) && back[1] == null && back[2] != null
                && ItemStack.areItemStacksEqual(back[2], eco) && back[3] == null, "wave A modules: the slots through NBT and back (3 overclockers in 1, an economizer in 3)");
        NBTTagCompound e = new NBTTagCompound();
        e.setTag("AdUpg", new net.minecraft.nbt.NBTTagList());
        check(TileEntityAirDefenseSVA.writeModules(e, new ItemStack[4]) == 0 && !e.hasKey("AdUpg") && TileEntityAirDefenseSVA.readModules(null).length == 4,
                "wave A modules: empty slots write nothing (a plain item that stacks)");
        TileEntityRadarSVA r = new TileEntityRadarSVA();
        r.debugUpgrade(0, oc.copy());
        r.debugUpgrade(3, eco.copy());
        ItemStack item = new ItemStack(net.minecraft.init.Items.paper);
        int kept = r.keepUpgradesIn(item);
        boolean emptied = r.upgrades.getStackInSlot(0) == null && r.upgrades.getStackInSlot(3) == null;
        TileEntityRadarSVA r2 = new TileEntityRadarSVA();
        r2.restoreUpgrades(item, null, 0, 0, 0);
        check(kept == 2 && emptied && item.hasTagCompound() && r2.upgrades.getStackInSlot(0) != null && r2.upgrades.getStackInSlot(0).stackSize == 3
                && r2.upgrades.getStackInSlot(3) != null, "wave A modules: broken - into the item (the slots emptied); placed - back into the new block");
    }

    // ---------------------------------------------------------------- A9: the coverage grid

    /** Flat ground at 64, a hill 40..55 east of the antenna (|z| <= 30) up to hillTop; unknown west of -150. */
    private static CoverageMath.Terrain synthetic(final int hillTop) {
        return new CoverageMath.Terrain() {
            @Override
            public int ground(int x, int z) {
                if (x < -150) {
                    return Integer.MIN_VALUE;
                }
                return x >= 40 && x <= 55 && Math.abs(z) <= 30 ? hillTop : 64;
            }

            @Override
            public boolean blocks(int x, int y, int z) {
                int g = ground(x, z);
                return g != Integer.MIN_VALUE && y < g;
            }
        };
    }

    private static void coverage() {
        CoverageMath.Terrain t = synthetic(90);
        CoverageMath.Job j = new CoverageMath.Job(0.5, 68, 0.5, 200, 20);
        int steps = 0;
        while (!j.step(t, 300) && steps < 1000) {
            steps++;
        }
        double cell = CoverageMath.cell(200);
        int ci = (int) ((0.5 + 200) / cell), cj = (int) ((0.5 + 200) / cell);
        int behind = CoverageMath.get(j.grid, (int) ((100 + 200) / cell), cj), west = CoverageMath.get(j.grid, (int) ((-100 + 200) / cell), cj);
        int corner = CoverageMath.get(j.grid, 0, 0), unknown = CoverageMath.get(j.grid, (int) ((-170 + 200) / cell), cj);
        int side = CoverageMath.get(j.grid, (int) ((100 + 200) / cell), (int) ((100 + 200) / cell));
        check(j.done() && behind == CoverageMath.SHADOW && west == CoverageMath.COVERED && corner == CoverageMath.OUT && unknown == CoverageMath.UNKNOWN
                && side == CoverageMath.COVERED && CoverageMath.get(j.grid, ci, cj) == CoverageMath.COVERED,
                "wave A coverage: a synthetic terrain - behind the hill a dead zone at 20 bl, open ground seen, past the reach out, an unloaded chunk unknown ("
                        + steps + " slices)");
        CoverageMath.Job hi = new CoverageMath.Job(0.5, 68, 0.5, 200, 120);
        while (!hi.step(t, 5000)) {
            // to the end
        }
        int behindHi = CoverageMath.get(hi.grid, (int) ((100 + 200) / cell), cj);
        check(behindHi == CoverageMath.COVERED && CoverageMath.count(hi.grid, CoverageMath.SHADOW) < CoverageMath.count(j.grid, CoverageMath.SHADOW),
                "wave A coverage: at 120 bl the beam clears the hill - the dead zone shrinks (" + CoverageMath.count(j.grid, CoverageMath.SHADOW) + " -> "
                        + CoverageMath.count(hi.grid, CoverageMath.SHADOW) + " cells)");
        CoverageMath.Job sl = new CoverageMath.Job(0.5, 68, 0.5, 200, 20);
        sl.step(t, 10);
        check(!sl.done() && sl.percent() < 100, "wave A coverage: a budget per tick - the map is done in slices");
        byte[] g = new byte[CoverageMath.BYTES];
        CoverageMath.put(g, 5, 7, CoverageMath.SHADOW);
        CoverageMath.put(g, 6, 7, CoverageMath.OUT);
        CoverageMath.put(g, 5, 7, CoverageMath.COVERED);
        check(CoverageMath.get(g, 5, 7) == CoverageMath.COVERED && CoverageMath.get(g, 6, 7) == CoverageMath.OUT && CoverageMath.get(g, 4, 7) == CoverageMath.UNKNOWN
                && CoverageMath.get(g, -1, 0) == CoverageMath.UNKNOWN && CoverageMath.BYTES == 4096, "wave A coverage: 2 bits a cell, 4 KB a map, neighbours untouched");
        check(CoverageMath.signature(t, 0.5, 0.5, 200) != CoverageMath.signature(synthetic(120), 0.5, 0.5, 200)
                && CoverageMath.signature(t, 0.5, 0.5, 200) == CoverageMath.signature(synthetic(90), 0.5, 0.5, 200),
                "wave A coverage: the ground's signature changes with the hill (the map recomputed), not without");
        check(CoverageMath.nextAlt(-1) == 20 && CoverageMath.nextAlt(20) == 60 && CoverageMath.nextAlt(60) == 120 && CoverageMath.nextAlt(120) == -1
                && CoverageMath.validAlt(60) && !CoverageMath.validAlt(61), "wave A coverage: the heights 20 / 60 / 120 and off");
    }

    // ---------------------------------------------------------------- A10: IFF

    private static void iff() {
        check(IffMath.reason(false, -1, false, false, false) == IffMath.R_NO_REPLY && IffMath.reason(true, 04120, true, false, true) == IffMath.R_MISMATCH
                && IffMath.reason(true, 04120, true, false, false) == IffMath.R_UNKNOWN && IffMath.reason(true, 04120, true, true, false) == IffMath.R_NONE
                && IffMath.reason(true, 07000, false, false, false) == IffMath.R_NONE && IffMath.reason(true, XpdrMath.EMERGENCY, false, false, false) == IffMath.R_NONE,
                "wave A IFF: no reply, another's code, an airport code never given - suspect; its own code, 7000, 7700 - not");
        IffMath.Track t = new IffMath.Track();
        int a1 = IffMath.step(t, IffMath.R_NO_REPLY, 0, 100, 600, true);
        int a2 = IffMath.step(t, IffMath.R_NO_REPLY, 60, 100, 600, true);
        int a3 = IffMath.step(t, IffMath.R_NO_REPLY, 100, 100, 600, true);
        int left = IffMath.secondsLeft(t, 200, 600);
        int a4 = IffMath.step(t, IffMath.R_NO_REPLY, 600, 100, 600, true);
        int a5 = IffMath.step(t, IffMath.R_NO_REPLY, 700, 100, 600, true);
        check(a1 == IffMath.A_NONE && a2 == IffMath.A_NONE && a3 == IffMath.A_WARN && left == 25 && a4 == IffMath.A_NONE && a5 == IffMath.A_HANDOFF
                && t.state == IffMath.HANDED, "wave A IFF: interrogated - suspect, after 5 s the call (alarm), 30 s to answer, then handed to the air defence");
        int a6 = IffMath.step(t, IffMath.R_NONE, 720, 100, 600, true);
        check(a6 == IffMath.A_RESOLVED && t.state == IffMath.CLEAR, "wave A IFF: the transponder switched on - resolved, the handoff taken back");
        IffMath.Track w = new IffMath.Track();
        IffMath.step(w, IffMath.R_NO_REPLY, 0, 100, 600, false);
        IffMath.step(w, IffMath.R_NO_REPLY, 100, 100, 600, false);
        int r1 = IffMath.step(w, IffMath.R_NO_REPLY, 700, 100, 600, false);
        check(r1 == IffMath.A_REMIND && w.state == IffMath.WARNED, "wave A IFF: the handoff off (the default) - the call is repeated, nothing handed over");
        IffMath.Track d = new IffMath.Track();
        IffMath.step(d, IffMath.R_NO_REPLY, 0, 100, 600, true);
        IffMath.step(d, IffMath.R_NO_REPLY, 100, 100, 600, true);
        IffMath.answer(d);
        int d1 = IffMath.step(d, IffMath.R_NO_REPLY, 900, 100, 600, true);
        check(d.state == IffMath.ANSWERED && d1 == IffMath.A_NONE && IffMath.step(d, IffMath.R_NONE, 950, 100, 600, true) == IffMath.A_RESOLVED,
                "wave A IFF: a liner answered by CPDLC - known, never handed over; the transponder on later - resolved");
        IffMath.Track s = new IffMath.Track();
        IffMath.step(s, IffMath.R_NO_REPLY, 0, 100, 600, true);
        check(IffMath.step(s, IffMath.R_NONE, 40, 100, 600, true) == IffMath.A_NONE && s.state == IffMath.CLEAR,
                "wave A IFF: a blip that replies within the confirm time - no alarm at all");
        check(IffMath.intruder(IffMath.WARNED) && IffMath.intruder(IffMath.HANDED) && !IffMath.intruder(IffMath.ANSWERED) && !IffMath.intruder(IffMath.SUSPECT),
                "wave A IFF: the screens' intruder mark - warned and handed");
    }

    // ---------------------------------------------------------------- config, lang

    private static void config() {
        check(ConfigSVA.adKeepModules && ConfigSVA.adRcsExtras && ConfigSVA.adAutoSquawk && ConfigSVA.adAirportFriends && ConfigSVA.adCoverage && ConfigSVA.adIff
                && !ConfigSVA.adIffHandoff && ConfigSVA.adNoRadioDecisionSeconds == 120 && ConfigSVA.adNoRadioFriendMinutes == 10 && ConfigSVA.adZoneWarnSeconds == 30
                && ConfigSVA.adIffResponseSeconds == 30 && ConfigSVA.adCoverageBudget == 32 && ConfigSVA.zoneFrames,
                "wave A config: the defaults - all on, the IFF handoff OFF (warnings only, as the air defence), 7600 120 s / 10 min, 30 s ahead");
    }

    private static void lang() throws Exception {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        List<String> keys = new ArrayList<String>();
        for (String k : ZoneEditMath.CHECK_KEYS) {
            keys.add("sva.ad.zone.check." + k);
        }
        for (String k : IffMath.REASON_KEYS) {
            keys.add("sva.iff.reason." + k);
        }
        for (String k : IffMath.STATE_KEYS) {
            keys.add("sva.iff.state." + k);
            keys.add("sva.ad.iff.mark." + k);
        }
        keys.add("sva.cpdlc.u." + Cpdlc.U_SQUAWK);
        keys.add("sva.cpdlc.u." + Cpdlc.U_IFF);
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "wave A lang: the dynamic keys (zone checks, IFF reasons / states / marks, the new uplinks) in ru and en" + miss);
    }
}
