package com.sva.debug;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Air-defence wave self-test (pure logic, the dedicated server's -Dsva.selftest): the transponder (codes, the day key,
 * modes, special codes, NBT), the radars (RCS, the mock-up's ranges, stealth tuning, weather, ground clutter, the terrain
 * beam, the RWR's bearings), the zones (shapes, bands, the border ahead, corridors, aerodromes, NBT sanitising), the rules
 * of engagement (classes, the checks' order, warnings, the stage timeline), the ballistics (lead, the missile's turn, hit
 * tests), the new tuning kinds, the config defaults, lang keys and textures.
 */
final class SelfTestAirDefenseSVA {

    private SelfTestAirDefenseSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    static void run() throws Exception {
        xpdr();
        dayKey();
        radar();
        beam();
        zones();
        roe();
        stages();
        ballistics();
        tuningAndState();
        content();
    }

    private static void xpdr() {
        check(XpdrMath.parse("2471") == 02471 && XpdrMath.parse("7777") == XpdrMath.MAX && XpdrMath.parse("0000") == 0 && XpdrMath.parse("2481") == -1
                && XpdrMath.parse("123") == -1 && XpdrMath.parse(null) == -1 && XpdrMath.parse(" 7700 ") == XpdrMath.EMERGENCY,
                "ad xpdr: codes are 4 octal digits (8 and 9 do not exist)");
        check("2471".equals(XpdrMath.format(02471)) && "0007".equals(XpdrMath.format(7)) && XpdrMath.digit(02471, 0) == 2 && XpdrMath.digit(02471, 3) == 1,
                "ad xpdr: format / digits");
        check(XpdrMath.step(02471, 0, 1) == 03471 && XpdrMath.step(07000, 0, 1) == 0 && XpdrMath.step(0, 3, -1) == 07 && XpdrMath.step(02471, 2, 8) == 02471,
                "ad xpdr: a digit steps 0..7 round, the others stay");
        check(XpdrMath.replies(XpdrMath.A) && XpdrMath.replies(XpdrMath.S) && !XpdrMath.replies(XpdrMath.STBY) && !XpdrMath.replies(XpdrMath.OFF)
                && !XpdrMath.givesAltitude(XpdrMath.A) && XpdrMath.givesAltitude(XpdrMath.C) && XpdrMath.givesBoard(XpdrMath.S) && !XpdrMath.givesBoard(XpdrMath.C)
                && XpdrMath.nextMode(XpdrMath.S) == XpdrMath.OFF, "ad xpdr: A code, C + altitude, S + board, OFF / STBY silent");
        int fitted = 0;
        for (VehicleType t : VehicleType.values()) {
            fitted += XpdrMath.fitted(t) ? 1 : 0;
        }
        check(fitted == 7 && XpdrMath.fitted(VehicleType.PLANE) && XpdrMath.fitted(VehicleType.SHIP) && XpdrMath.fitted(VehicleType.LINER)
                && !XpdrMath.fitted(VehicleType.HOVER) && !XpdrMath.fitted(VehicleType.BUGGY),
                "ad xpdr: a transponder on the 7 flying types (the liner too), none on the ground / hoverbike");
        XpdrMath.Xpdr x = new XpdrMath.Xpdr();
        check(x.mode == XpdrMath.C && x.code == XpdrMath.VFR && x.dayKey, "ad xpdr: a new vehicle: mode C, 7000, day key on");
        x.setCode(XpdrMath.NO_RADIO, 1000);
        boolean grace = x.noRadioGrace(1000 + XpdrMath.NO_RADIO_GRACE - 1) && !x.noRadioGrace(1000 + XpdrMath.NO_RADIO_GRACE);
        x.setCode(XpdrMath.VFR, 5000);
        check(grace && !x.noRadioGrace(5001) && x.noRadioSince < 0, "ad xpdr: 7600 - 2 minutes of grace from when it is set");
        x.identUntil = 100 + XpdrMath.IDENT_TICKS;
        x.lastInterrogated = 90;
        x.cardDay = 7;
        check(x.identOn(100) && !x.identOn(100 + XpdrMath.IDENT_TICKS) && x.replyLamp(100) && !x.replyLamp(90 + XpdrMath.REPLY_TICKS) && x.keyLamp(7)
                && !x.keyLamp(8), "ad xpdr: IDENT 18 s, the REPLY lamp 1 s, the DAY KEY lamp only with today's card");
        x.mode = XpdrMath.STBY;
        check(!x.identOn(100) && !x.replyLamp(95), "ad xpdr: a silent transponder neither idents nor replies");
        XpdrMath.Xpdr y = new XpdrMath.Xpdr();
        x.mode = XpdrMath.S;
        x.code = 02471;
        x.dayKey = false;
        NBTTagCompound t = new NBTTagCompound();
        x.write(t);
        y.read(t);
        NBTTagCompound bad = new NBTTagCompound();
        bad.setByte(XpdrMath.Xpdr.K_MODE, (byte) 40);
        bad.setShort(XpdrMath.Xpdr.K_CODE, (short) 9999);
        XpdrMath.Xpdr z = new XpdrMath.Xpdr();
        z.read(bad);
        check(y.mode == XpdrMath.S && y.code == 02471 && !y.dayKey && y.cardDay == 7 && z.mode == XpdrMath.S && z.code == XpdrMath.VFR,
                "ad xpdr: NBT round trip; a bad mode / code is clamped / reset");
    }

    private static void dayKey() {
        boolean reservedFree = true, inRange = true;
        int same = 0;
        Set<Integer> codes = new HashSet<Integer>();
        for (long d = 0; d < 1000; d++) {
            int c = XpdrMath.dayCode("sever", d);
            reservedFree &= !XpdrMath.reserved(c);
            inRange &= XpdrMath.valid(c);
            same += c == XpdrMath.dayCode("sever", d + 1) ? 1 : 0;
            codes.add(Integer.valueOf(c));
        }
        check(reservedFree && inRange, "ad day key: 1000 days of codes - all valid, never 0000 / 7000 / 7500 / 7600 / 77xx");
        check(same < 5 && codes.size() > 700, "ad day key: the code changes every day (" + codes.size() + " different in 1000 days, " + same + " repeats)");
        check(XpdrMath.dayCode("sever", 215) == XpdrMath.dayCode("sever", 215) && XpdrMath.dayCode("sever", 215) != XpdrMath.dayCode("yug", 215)
                && XpdrMath.valid(XpdrMath.dayCode("", 3)) && XpdrMath.valid(XpdrMath.dayCode(null, 3)),
                "ad day key: the same secret and day - the same code; another secret - another code");
        check(XpdrMath.day(0) == 0 && XpdrMath.day(23999) == 0 && XpdrMath.day(24000) == 1 && XpdrMath.day(-5) == 0, "ad day key: a game day is 24000 ticks");
        check(XpdrMath.reserved(XpdrMath.EMERGENCY) && XpdrMath.reserved(07745) && XpdrMath.reserved(XpdrMath.HIJACK) && !XpdrMath.reserved(02471)
                && XpdrMath.special(XpdrMath.EMERGENCY) && XpdrMath.special(XpdrMath.NO_RADIO) && !XpdrMath.special(XpdrMath.VFR),
                "ad day key: reserved / special codes");
    }

    private static void radar() {
        double ship = RadarMath.detectRange(RadarMath.SURVEY, RadarMath.rcs(VehicleType.SHIP), 1, 1),
                tilt = RadarMath.detectRange(RadarMath.SURVEY, RadarMath.rcs(VehicleType.TILT), 1, 1),
                heli = RadarMath.detectRange(RadarMath.SURVEY, RadarMath.rcs(VehicleType.HELI), 1, 1),
                plane = RadarMath.detectRange(RadarMath.SURVEY, RadarMath.rcs(VehicleType.PLANE), 1, 1),
                jet = RadarMath.detectRange(RadarMath.SURVEY, RadarMath.rcs(VehicleType.JET), 1, 1),
                hover = RadarMath.detectRange(RadarMath.SURVEY, RadarMath.rcs(VehicleType.HOVER), 1, 1);
        check(near(ship, 600, 0.5) && near(tilt, 401, 1) && near(heli, 373, 1) && near(plane, 337, 1) && near(jet, 313, 1) && near(hover, 200, 1),
                "ad radar: the mock-up's surveillance ranges - ship 600, tiltrotor 401, heli 373, plane 337, jet 313, hoverbike 200 ("
                        + Math.round(tilt) + "/" + Math.round(heli) + "/" + Math.round(plane) + "/" + Math.round(jet) + ")");
        check(RadarMath.rcs(VehicleType.BUGGY) == 0 && RadarMath.rcs(VehicleType.TRUCK) == 0 && RadarMath.detectRange(RadarMath.SURVEY, 0, 1, 1) == 0
                && RadarMath.detectRange(5, 4, 1, 1) == 0, "ad radar: ground vehicles have no RCS; a bad kind sees nothing");
        double r = RadarMath.detectRange(RadarMath.SURVEY, 16, 1, 1), r1 = RadarMath.detectRange(RadarMath.SURVEY, 1, 1, 1);
        check(near(r / r1, 2, 1e-9), "ad radar: 16 times the RCS - twice the range (4th root)");
        int[] ram = TuningMath.empty(), smooth = TuningMath.empty(), both = TuningMath.empty();
        ram[0] = TuningMath.RAM_COAT;
        smooth[0] = TuningMath.SMOOTH_HULL;
        both[0] = TuningMath.RAM_COAT;
        both[1] = TuningMath.SMOOTH_HULL;
        double jr = RadarMath.detectRange(RadarMath.SURVEY, 3, RadarMath.stealthMul(ram, true), 1),
                js = RadarMath.detectRange(RadarMath.SURVEY, 3, RadarMath.stealthMul(smooth, true), 1),
                jb = RadarMath.detectRange(RadarMath.SURVEY, 3, RadarMath.stealthMul(both, true), 1);
        check(near(jr, 188, 1) && near(js, 282, 1) && near(jb, 169, 1) && RadarMath.stealthMul(both, false) == 1,
                "ad radar: the jet with the coating 188, the smooth hull 282, both 169 (mock-up); tuning off - no effect");
        check(RadarMath.weatherMul(false, false) == 1 && RadarMath.weatherMul(true, false) == 0.85 && RadarMath.weatherMul(true, true) == 0.7,
                "ad radar: rain -15 %, a thunderstorm -30 %");
        check(RadarMath.visible(4, 10, false) && !RadarMath.visible(4, 2, false) && !RadarMath.visible(4, 100, true) && !RadarMath.visible(0, 100, false),
                "ad radar: lower than 4 blocks over the ground - in the clutter; the cloaked ship - not seen");
        check(RadarMath.RANGE[RadarMath.TRACK] < RadarMath.RANGE[RadarMath.HEIGHT] && RadarMath.CHANNELS[RadarMath.TRACK] == 2
                && RadarMath.INTERVAL[RadarMath.SURVEY] == 120 && RadarMath.ALTITUDE[RadarMath.HEIGHT] && !RadarMath.ALTITUDE[RadarMath.SURVEY],
                "ad radar: the kinds - survey every 6 s without altitude, tracking two targets, height finder with altitude");
        boolean jit = true, rep = true;
        for (int i = 0; i < 200; i++) {
            double j = RadarMath.jitter(i, i * 7, i % 3, 8);
            jit &= Math.abs(j) <= 8;
            rep &= j == RadarMath.jitter(i, i * 7, i % 3, 8);
        }
        check(jit && rep && RadarMath.jitter(5, 1, 0, 8) != RadarMath.jitter(5, 2, 0, 8), "ad radar: the survey blip trembles within +-8, repeatably");
        check(near(RadarMath.compass(0, -1), 0, 1e-9) && near(RadarMath.compass(1, 0), 90, 1e-9) && near(RadarMath.compass(0, 1), 180, 1e-9)
                && near(RadarMath.headingOfYaw(180), 0, 1e-9) && near(RadarMath.headingOfYaw(-90), 90, 1e-9),
                "ad radar: compass headings (north = -z) and Minecraft yaw");
        // facing north (yaw 180): a radar to the east is at 90 (3 o'clock), behind at 180
        check(near(RadarMath.relBearing(0, 0, 180, 100, 0), 90, 1e-6) && near(RadarMath.relBearing(0, 0, 180, 0, 100), 180, 1e-6)
                && near(RadarMath.relBearing(0, 0, 0, 100, 0), 270, 1e-6), "ad rwr: the threat's bearing relative to the nose, clockwise");
        check(RadarMath.rwrLevel(new int[] {RadarMath.T_SEARCH}) == 1 && RadarMath.rwrLevel(new int[] {RadarMath.T_SEARCH, RadarMath.T_LOCK}) == 2
                && RadarMath.rwrLevel(new int[] {RadarMath.T_MISSILE, RadarMath.T_HEIGHT}) == 3 && RadarMath.rwrLevel(new int[0]) == 0
                && RadarMath.rwrRadius(0) > RadarMath.rwrRadius(500), "ad rwr: levels search / lock / missile; nearer - closer to the scope's centre");
    }

    private static void beam() {
        RadarMath.Probe hill = new RadarMath.Probe() {
            @Override
            public boolean blocks(int x, int y, int z) {
                return x == 50 && y < 80;                              // a wall 80 high at x = 50
            }
        };
        check(RadarMath.losClear(0, 70, 0, 100, 80, 0, hill) == false && RadarMath.losClear(0, 100, 0, 100, 100, 0, hill)
                && RadarMath.losClear(0, 70, 0, 100, 70, 40, new RadarMath.Probe() {
                    @Override
                    public boolean blocks(int x, int y, int z) {
                        return false;
                    }
                }), "ad radar beam: a hill between hides the target, over it - seen");
        final List<int[]> asked = new ArrayList<int[]>();
        RadarMath.losClear(0.5, 10.5, 0.5, 10.5, 10.5, 0.5, new RadarMath.Probe() {
            @Override
            public boolean blocks(int x, int y, int z) {
                asked.add(new int[] {x, y, z});
                return false;
            }
        });
        boolean ends = true, once = true;
        Set<String> seen = new HashSet<String>();
        for (int[] a : asked) {
            ends &= a[0] != 0 && a[0] != 10;
            once &= seen.add(a[0] + ":" + a[1] + ":" + a[2]);
        }
        check(ends && once && asked.size() == 8, "ad radar beam: the antenna's and the target's own blocks are skipped, each block asked once ("
                + asked.size() + ")");
    }

    private static ZoneMath.Zone zone(int type, int shape, int a, int b, int c, int d, int lo, int hi) {
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.type = type;
        z.shape = shape;
        if (shape == ZoneMath.RECT) {
            z.x0 = a;
            z.z0 = b;
            z.x1 = c;
            z.z1 = d;
        } else {
            z.cx = a;
            z.cz = b;
            z.r = c;
        }
        z.lo = lo;
        z.hi = hi;
        z.name = "Z";
        return z;
    }

    private static void zones() {
        ZoneMath.Zone circle = zone(ZoneMath.FORBIDDEN, ZoneMath.CIRCLE, 0, 0, 100, 0, 0, 255);
        ZoneMath.Zone rect = zone(ZoneMath.RESTRICTED, ZoneMath.RECT, 200, 0, 299, 99, 60, 90);
        check(ZoneMath.contains2D(circle, 50, 50) && !ZoneMath.contains2D(circle, 90, 90) && ZoneMath.contains2D(rect, 299.9, 99.9)
                && !ZoneMath.contains2D(rect, 300.1, 50), "ad zones: a circle and a rectangle (block corners inclusive)");
        check(ZoneMath.contains(rect, 250, 70, 50, true) && !ZoneMath.contains(rect, 250, 120, 50, true) && !ZoneMath.contains(rect, 250, 70, 50, false)
                && ZoneMath.contains(circle, 0, 200, 0, false), "ad zones: the band works only with the altitude known; a full band always");
        check(near(ZoneMath.distance2D(circle, 150.5, 0.5), 50, 1e-6) && ZoneMath.distance2D(circle, 0, 0) == 0 && near(ZoneMath.distance2D(rect, 190, 50), 10, 1e-6),
                "ad zones: the distance to the border");
        // flying east (course 90) from x = -150 on the circle's axis: the border at 50.5; north - misses
        check(near(ZoneMath.ahead(circle, -150, 0.5, 90, 200), 50.5, 1e-6) && ZoneMath.ahead(circle, -150, 0.5, 0, 500) < 0
                && ZoneMath.ahead(circle, -150, 0.5, 90, 40) < 0 && ZoneMath.ahead(circle, 0, 0, 33, 10) == 0
                && near(ZoneMath.ahead(rect, 150, 50, 90, 100), 50, 1e-6) && ZoneMath.ahead(rect, 150, 50, 270, 100) < 0,
                "ad zones: the border ahead on the course (the HSI line, the HUD warning); behind / too far - none");
        check(ZoneMath.warnLevel(-1) == ZoneMath.W_NONE && ZoneMath.warnLevel(45) == ZoneMath.W_NONE && ZoneMath.warnLevel(40) == ZoneMath.W_FAR
                && ZoneMath.warnLevel(20) == ZoneMath.W_NEAR && ZoneMath.warnLevel(10) == ZoneMath.W_CLOSE && ZoneMath.warnLevel(0) == ZoneMath.W_INSIDE,
                "ad zones: warnings at 40 / 20 / 10 blocks and inside");
        check(near(ZoneMath.courseDiff(350, 10), 20, 1e-9) && near(ZoneMath.courseDiff(90, 270), 180, 1e-9), "ad zones: course difference wraps");
        ZoneMath.Zone corr = zone(ZoneMath.CORRIDOR, ZoneMath.RECT, -10, -200, 10, 200, 60, 90);
        corr.course = 0;
        ZoneMath.Zone aero = zone(ZoneMath.AERODROME, ZoneMath.CIRCLE, 60, 60, 15, 0, 0, 255);
        List<ZoneMath.Zone> zs = new ArrayList<ZoneMath.Zone>();
        zs.add(circle);
        zs.add(corr);
        zs.add(aero);
        check(ZoneMath.violated(zs, 30, 70, 30, 0, true) == circle && ZoneMath.violated(zs, 0, 70, 30, 10, true) == null
                && ZoneMath.violated(zs, 0, 70, 30, 90, true) == circle && ZoneMath.violated(zs, 0, 120, 30, 0, true) == circle
                && ZoneMath.violated(zs, 60, 70, 60, 0, true) == null && ZoneMath.violated(zs, 300, 70, 300, 0, true) == null,
                "ad zones: a forbidden zone - a violator, but not in a corridor at its height on its course (+-30) nor at an aerodrome");
        check(ZoneMath.aerodromeCode(aero, 04120) && !ZoneMath.aerodromeCode(aero, 04200) && !ZoneMath.aerodromeCode(circle, 04120),
                "ad zones: an aerodrome's codes (4100 - 4177 by default)");
        check(ZoneMath.exitCourse(circle, 50, 0.5) == 90 && ZoneMath.exitCourse(circle, 0.5, 50) == 180, "ad zones: the way out - straight away from the centre");
        NBTTagCompound t = new NBTTagCompound();
        circle.name = "3-1§c";
        circle.write(t);
        t.setIntArray("Circle", new int[] {5, 6, 99999});
        t.setShort("Lo", (short) 300);
        t.setShort("Course", (short) -90);
        ZoneMath.Zone back = new ZoneMath.Zone();
        back.read(t);
        check("3-1c".equals(back.name) && back.r == ZoneMath.MAX_HALF && back.lo == ZoneMath.ALT_MAX && back.hi == ZoneMath.ALT_MAX && back.course == 270
                && back.type == ZoneMath.FORBIDDEN && back.cx == 5, "ad zones: NBT read sanitises (no formatting codes, radius 512, the band, the course)");
    }

    private static void roe() {
        check(RoeMath.classify(false, true, XpdrMath.EMERGENCY, false, false, false, false, true) == RoeMath.EMERGENCY
                && RoeMath.classify(true, false, -1, false, false, false, false, true) == RoeMath.OWN
                && RoeMath.classify(false, true, 02471, true, false, false, false, true) == RoeMath.FRIEND
                && RoeMath.classify(false, true, 01200, false, true, false, false, true) == RoeMath.FRIEND
                && RoeMath.classify(false, false, -1, false, false, true, false, true) == RoeMath.FRIEND
                && RoeMath.classify(false, false, 02471, true, false, false, false, true) == RoeMath.VIOLATOR
                && RoeMath.classify(false, true, XpdrMath.NO_RADIO, false, false, false, true, true) == RoeMath.NO_RADIO
                && RoeMath.classify(false, true, XpdrMath.NO_RADIO, false, false, false, false, true) == RoeMath.VIOLATOR
                && RoeMath.classify(false, true, 01200, false, false, false, false, false) == RoeMath.FOREIGN,
                "ad roe: 7700 first, own vehicles, day code / white list / 10 min - friend (a code only counts when it replied), 7600 grace, violator, stranger");
        check(!RoeMath.roeWants(RoeMath.HOLD, RoeMath.VIOLATOR, true, true) && RoeMath.roeWants(RoeMath.VIOLATORS, RoeMath.VIOLATOR, false, false)
                && !RoeMath.roeWants(RoeMath.VIOLATORS, RoeMath.FOREIGN, false, false) && RoeMath.roeWants(RoeMath.FOREIGN_ALL, RoeMath.FOREIGN, false, false)
                && !RoeMath.roeWants(RoeMath.FOREIGN_ALL, RoeMath.FRIEND, true, true) && !RoeMath.roeWants(RoeMath.KP_MARKS, RoeMath.VIOLATOR, false, false)
                && RoeMath.roeWants(RoeMath.KP_MARKS, RoeMath.FOREIGN, true, false) && RoeMath.roeWants(RoeMath.KP_MARKS, RoeMath.VIOLATOR, false, true)
                && !RoeMath.roeWants(RoeMath.KP_MARKS, RoeMath.FOREIGN, false, true) && !RoeMath.roeWants(RoeMath.FOREIGN_ALL, RoeMath.EMERGENCY, true, true),
                "ad roe: the four rules (hold / violators / all strangers / the post's marks, with its Auto - violators)");
        check(RoeMath.roeWithoutKp(RoeMath.KP_MARKS) == RoeMath.VIOLATORS && RoeMath.roeWithoutKp(RoeMath.FOREIGN_ALL) == RoeMath.VIOLATORS
                && RoeMath.roeWithoutKp(RoeMath.HOLD) == RoeMath.HOLD, "ad roe: no command post - \"violators\" (hold stays)");
        check(RoeMath.fireBlock(false, true, true, true, true, true, true, true, true) == RoeMath.B_DISABLED
                && RoeMath.fireBlock(true, true, true, true, true, true, true, true, true) == RoeMath.B_SPAWN
                && RoeMath.fireBlock(true, false, true, true, true, true, true, true, true) == RoeMath.B_PROTECTED
                && RoeMath.fireBlock(true, false, false, true, true, true, true, true, true) == RoeMath.B_OWN
                && RoeMath.fireBlock(true, false, false, false, true, true, true, true, true) == RoeMath.B_PVP
                && RoeMath.fireBlock(true, false, false, false, false, true, true, true, true) == RoeMath.B_UNMANNED
                && RoeMath.fireBlock(true, false, false, false, false, false, true, true, true) == RoeMath.B_SPECIAL
                && RoeMath.fireBlock(true, false, false, false, false, false, false, true, true) == RoeMath.B_WARN_ONLY
                && RoeMath.fireBlock(true, false, false, false, false, false, false, false, false) == RoeMath.B_ROE
                && RoeMath.fireBlock(true, false, false, false, false, false, false, false, true) == RoeMath.OK,
                "ad roe: the checks before each shot in the mock-up's order");
        check(RoeMath.warnsAllowed(RoeMath.OK) && RoeMath.warnsAllowed(RoeMath.B_WARN_ONLY) && RoeMath.warnsAllowed(RoeMath.B_PVP)
                && RoeMath.warnsAllowed(RoeMath.B_UNMANNED) && !RoeMath.warnsAllowed(RoeMath.B_OWN) && !RoeMath.warnsAllowed(RoeMath.B_SPECIAL)
                && !RoeMath.warnsAllowed(RoeMath.B_ROE) && !RoeMath.warnsAllowed(RoeMath.B_DISABLED),
                "ad roe: warnings still go in \"warnings only\", without PvP, unmanned; never to own / 7700 / unwanted");
    }

    private static void stages() {
        int w = RoeMath.waitTicks(10, 10);
        check(w == 200 && RoeMath.waitTicks(2, 10) == 200 && RoeMath.waitTicks(30, 10) == 600 && RoeMath.waitTicks(0, 0) == 40 && RoeMath.waitTicks(500, 0) == 1200,
                "ad stages: the wait is the launcher's (2..60 s), never under the server's minimum");
        check(RoeMath.stage(0, true, true, w) == RoeMath.ST_MESSAGE && RoeMath.stage(99, true, true, w) == RoeMath.ST_MESSAGE
                && RoeMath.stage(100, true, true, w) == RoeMath.ST_LOCK && RoeMath.stage(299, true, true, w) == RoeMath.ST_LOCK
                && RoeMath.stage(300, true, true, w) == RoeMath.ST_WARN && RoeMath.stage(339, true, true, w) == RoeMath.ST_WARN
                && RoeMath.stage(340, true, true, w) == RoeMath.ST_FIRE, "ad stages: message - 5 s - lock - wait - warning shot - 2 s - fire");
        check(RoeMath.stage(0, false, false, w) == RoeMath.ST_LOCK && RoeMath.stage(199, false, false, w) == RoeMath.ST_LOCK
                && RoeMath.stage(200, false, false, w) == RoeMath.ST_FIRE && RoeMath.stage(150, true, false, w) == RoeMath.ST_LOCK
                && RoeMath.stage(300, true, false, w) == RoeMath.ST_FIRE, "ad stages: without the message / the warning shot those steps are left out");
        check(RoeMath.secondsToFire(100, true, true, w) == 12 && RoeMath.secondsToFire(340, true, true, w) == 0 && RoeMath.secondsToFire(339, true, true, w) == 1,
                "ad stages: seconds to fire (the lock's message)");
    }

    private static void ballistics() {
        double[] still = BallisticsMath.intercept(0, 0, 0, 40, 0, 0, 0, 0, 0, 4, 100);
        double[] moving = BallisticsMath.intercept(0, 0, 0, 40, 30, 0, 0, 0, 1, 4, 100);
        double t = moving[3];
        double missX = moving[0] - 40, missZ = moving[2] - t * 1;
        check(still != null && near(still[0], 40, 1e-9) && near(still[3], 10, 1e-9) && moving != null && near(missX, 0, 1e-6) && Math.abs(missZ) < 0.05
                && BallisticsMath.intercept(0, 0, 0, 400, 0, 0, 0, 0, 0, 4, 40) == null, "ad ballistics: the lead meets a moving target; out of reach - none");
        double[] v = BallisticsMath.steer(new double[] {3, 0, 0}, new double[] {0, 0, 1}, 6, 3);
        double turned = BallisticsMath.angle(new double[] {1, 0, 0}, v);
        double[] v2 = BallisticsMath.steer(new double[] {3, 0, 0}, new double[] {1, 0.05, 0}, 6, 3);
        check(near(turned, 6, 1e-6) && near(Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 3, 1e-9) && BallisticsMath.angle(v2, new double[] {1, 0.05, 0}) < 1e-6,
                "ad ballistics: the missile turns at most 6 deg a tick at its speed; a small correction at once");
        double[] back = BallisticsMath.steer(new double[] {1, 0, 0}, new double[] {-1, 0, 0}, 6, 1);
        check(near(BallisticsMath.angle(new double[] {1, 0, 0}, back), 6, 1e-6), "ad ballistics: a target straight behind - it still turns 6 deg");
        check(near(BallisticsMath.segToPoint2(0, 0, 0, 10, 0, 0, 5, 3, 0), 9, 1e-9) && near(BallisticsMath.segToPoint2(0, 0, 0, 10, 0, 0, 13, 4, 0), 25, 1e-9),
                "ad ballistics: the hit test - distance to the shell's path within the tick");
        Random rnd = new Random(5);
        boolean cone = true;
        double[] d = {0, 0.6, 0.8};
        for (int i = 0; i < 300; i++) {
            cone &= BallisticsMath.angle(d, BallisticsMath.spread(d, 2.5, rnd)) <= 2.5 + 1e-6;
        }
        check(cone, "ad ballistics: the gun's spread stays inside its cone");
        check(BallisticsMath.MISSILE_RANGE > BallisticsMath.GUN_RANGE && BallisticsMath.MISSILE_SPEED * BallisticsMath.MISSILE_LIFE > BallisticsMath.MISSILE_RANGE
                && BallisticsMath.GUN_SPEED * BallisticsMath.SHELL_LIFE >= BallisticsMath.GUN_RANGE && BallisticsMath.MISSILE_CHANNELS == 2,
                "ad ballistics: shells and missiles fly past their launcher's range; two fire channels");
    }

    private static void tuningAndState() {
        check(TuningMath.KINDS == 12 && TuningMath.KEYS.length == 12 && TuningMath.fits(VehicleType.JET, TuningMath.RAM_COAT)
                && TuningMath.fits(VehicleType.HELI, TuningMath.RWR) && !TuningMath.fits(VehicleType.BUGGY, TuningMath.RWR)
                && !TuningMath.fits(VehicleType.HOVER, TuningMath.SMOOTH_HULL) && TuningMath.conflicts(TuningMath.ARMOR, TuningMath.SMOOTH_HULL)
                && !TuningMath.conflicts(TuningMath.RAM_COAT, TuningMath.SMOOTH_HULL), "ad tuning: coating / smooth hull / RWR - aircraft only; smooth hull not with armour");
        int[] s = {TuningMath.RAM_COAT, TuningMath.SMOOTH_HULL, TuningMath.RWR, TuningMath.BATTERY};
        int[] u = TuningMath.unpack(TuningMath.pack(s));
        check(u[0] == TuningMath.RAM_COAT && u[2] == TuningMath.RWR && u[3] == TuningMath.BATTERY, "ad tuning: the new kinds survive the data watcher packing");
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.xpdr.mode = XpdrMath.A;
        st.xpdr.code = 07700;
        NBTTagCompound t = new NBTTagCompound();
        st.writeNBT(t);
        VehicleState back = new VehicleState(VehicleType.PLANE);
        back.readNBT(t);
        NBTTagCompound old = new NBTTagCompound();
        st.writeNBT(old);
        old.removeTag(VehicleState.K_XPDR);
        VehicleState fresh = new VehicleState(VehicleType.PLANE);
        fresh.readNBT(old);
        check(back.xpdr.mode == XpdrMath.A && back.xpdr.code == 07700 && fresh.xpdr.mode == XpdrMath.C && fresh.xpdr.code == XpdrMath.VFR,
                "ad state: the vehicle keeps its transponder; an older save gets mode C, 7000");
        check(VehicleFn.IDENT.appliesTo(VehicleType.PLANE) && !VehicleFn.IDENT.appliesTo(VehicleType.BUGGY) && VehicleFn.IDENT.action(VehicleType.JET) == EntityVehicleSVA.A_IDENT
                && VehicleFn.IDENT.driverOnly && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_IDENT, true, false) == false
                && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_IDENT, false, true), "ad keys: IDENT - the pilot's, aircraft only");
        check(ConfigSVA.AD_WARNINGS.equals(ConfigSVA.adMode) && !ConfigSVA.adFires() && ConfigSVA.adEnabled && ConfigSVA.adRequirePvp && !ConfigSVA.adFireOnUnmanned
                && !ConfigSVA.adFireOnFriends && !ConfigSVA.adFireInProtectedRegions && ConfigSVA.adMinWarningSeconds == 10,
                "ad config: on by default in \"warnings only\" - nothing is fired until the server allows it");
    }

    private static void content() throws Exception {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        StringBuilder miss = new StringBuilder();
        List<String> need = new ArrayList<String>();
        for (String k : RadarMath.KEYS) {
            need.add("tile.siliconagevehicles.radar." + k + ".name");
            need.add("sva.tooltip.radar." + k + ".details");
            need.add("sva.ad.radar." + k);
            need.add("sva.ad.radar.short." + k);
        }
        for (String k : new String[] {"gun", "missile"}) {
            need.add("tile.siliconagevehicles.launcher." + k + ".name");
            need.add("sva.ad.launcher." + k);
        }
        for (String k : RoeMath.ROE_KEYS) {
            need.add("sva.ad.roe." + k);
            need.add("sva.ad.roe." + k + ".tip");
            need.add("sva.ad.roe.short." + k);
        }
        for (String k : RoeMath.CLASS_KEYS) {
            need.add("sva.ad.cls." + k);
        }
        for (String k : RoeMath.STAGE_KEYS) {
            need.add("sva.ad.stage." + k);
        }
        for (String k : ZoneMath.TYPE_KEYS) {
            need.add("sva.ad.zone.type." + k);
            need.add("sva.ad.zone.type." + k + ".tip");
        }
        for (int i = 0; i < 4; i++) {
            need.add("sva.ad.rwr.letter." + i);
        }
        for (String k : XpdrMath.MODE_KEYS) {
            need.add("sva.xpdr.mode." + k);
        }
        need.add("tile.siliconagevehicles.commandPost.name");
        need.add("item.siliconagevehicles.codeCard.printed.name");
        need.add("item.siliconagevehicles.ammo.samMissile.name");
        for (String k : need) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0 && ru.equals(en), "ad lang: names, tooltips, rules, classes, stages, zone types in ru and en (the same keys)" + miss);
        String[] tex = {"blocks/adBase", "blocks/kp_front", "blocks/kp_frontAlarm", "blocks/kp_side", "blocks/kp_top", "items/aaShells", "items/samMissile",
                "items/codeCard", "items/codeCardPrinted"};
        boolean files = true;
        for (String s : tex) {
            files &= exists("/assets/siliconagevehicles/textures/" + s + ".png");
        }
        for (String k : RadarMath.KEYS) {
            files &= exists("/assets/siliconagevehicles/textures/blocks/radar_" + k + "_top.png") && exists("/assets/siliconagevehicles/textures/blocks/radar_" + k + "_side.png");
        }
        for (String k : new String[] {"gun", "missile"}) {
            files &= exists("/assets/siliconagevehicles/textures/blocks/launcher_" + k + "_top.png")
                    && exists("/assets/siliconagevehicles/textures/blocks/launcher_" + k + "_side.png");
        }
        check(files, "ad textures: every new block face and item icon is in the jar");
        check(com.sva.init.ModContentSVA.radar != null && com.sva.init.ModContentSVA.launcher != null && com.sva.init.ModContentSVA.commandPost != null
                && com.sva.init.ModContentSVA.ammo != null && com.sva.init.ModContentSVA.codeCard != null
                && com.sva.workbench.WorkbenchRecipesSVA.byId("radarSurvey") != null && com.sva.workbench.WorkbenchRecipesSVA.byId("launcherMissile") != null
                && com.sva.workbench.WorkbenchRecipesSVA.byId("commandPost") != null && com.sva.workbench.WorkbenchRecipesSVA.byId("tuningRwr") != null,
                "ad content: the blocks, items and the Engineering Workbench recipes registered");
    }

    private static boolean exists(String path) throws Exception {
        InputStream in = SelfTestAirDefenseSVA.class.getResourceAsStream(path);
        if (in == null) {
            return false;
        }
        in.close();
        return true;
    }
}
