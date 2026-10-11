package com.sva.debug;

import java.util.List;
import java.util.Set;

import com.sva.vehicle.AnimMath;
import com.sva.vehicle.AnimParts;
import com.sva.vehicle.CockpitView;
import com.sva.vehicle.GearMath;
import com.sva.vehicle.HudMath;
import com.sva.vehicle.NozzleMath;
import com.sva.vehicle.VehicleType;

/**
 * Aircraft round 2, stage D3 self-tests (★12 gear doors and struts, ★13 the nozzle petals and the afterburner flame, ★18 the
 * jet's HUD; mock-ups docs/mockups/veh_air3_3_gear_nozzle.png, veh_air3_4_cockpit.png) - pure logic on the dedicated
 * server: the door / leg sequence and its timings (both ways, a reversal, doors off), the strut's spring response (the
 * touchdown peak, the rebound, the settling, a bump, bottoming out), the torque links staying joined over the stroke, the
 * light plane's flexing legs and the airframe's sink, the gear rows (retracted wheels above the closed doors - posed from
 * the OBJ's own triangles), the nozzle's diameter / petal mapping and the flame, the HUD's projection (pitch ladder, the
 * flight-path vector, the tapes), its glass box and clip planes, the distance line, the lang lines.
 */
final class SelfTestAirD3SVA {

    private SelfTestAirD3SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        sequence();
        struts();
        links();
        rows();
        nozzle();
        hudProjection();
        hudGlass();
        hudText();
        lang();
    }

    // ---------------------------------------------------------------- ★12 the door / leg sequence

    private static void sequence() {
        AnimMath.State s = new AnimMath.State();
        int doorsOpen = -1, legsUp = -1, doorsShut = -1;
        boolean legsWaited = true, order = true;
        int last = 0;
        for (int i = 1; i <= 80; i++) {
            GearMath.sequence(s, true, true);
            if (s.door < 1F && s.gear > 0F && s.gear < 1F) {
                order = false;                                       // the legs move only with the doors fully open
            }
            if (doorsOpen < 0 && s.door >= 1F) {
                doorsOpen = i;
            }
            if (doorsOpen < 0) {
                legsWaited &= s.gear == 0F;
            }
            if (legsUp < 0 && s.gear >= 1F) {
                legsUp = i;
            }
            if (doorsShut < 0 && s.gear >= 1F && s.door <= 0F) {
                doorsShut = i;
            }
            int ph = GearMath.phase(s.gear, s.door, true);
            order &= ph >= last;
            last = ph;
        }
        int d = GearMath.DOOR_TICKS, g = GearMath.GEAR_TICKS;
        check(legsWaited && order && doorsOpen == 1 && legsUp == g && doorsShut == g + d && last == 4,
                "D3 gear: retracting from the parked look (doors open) - the legs swing aft at once (up at " + legsUp + ", 2 s), then the doors shut flush ("
                        + doorsShut + ")");
        int open2 = -1, down = -1;
        boolean stayShut = true;
        for (int i = 1; i <= 80; i++) {
            GearMath.sequence(s, false, true);
            if (open2 < 0 && s.door >= 1F) {
                open2 = i;
            }
            if (open2 < 0) {
                stayShut &= s.gear == 1F;
            }
            if (down < 0 && s.gear <= 0F) {
                down = i;
            }
        }
        check(stayShut && open2 == d && down == d + g && s.door == 1F,
                "D3 gear: extending - the doors open (" + open2 + "), the legs come down (" + down + "), the doors stay open (the parked look)");
        // a reversal half way: the legs simply go back (the doors are open already)
        AnimMath.State r = new AnimMath.State();
        for (int i = 0; i < d + g / 2; i++) {
            GearMath.sequence(r, true, true);
        }
        float mid = r.gear;
        GearMath.sequence(r, false, true);
        boolean back = r.gear < mid && r.door == 1F;
        // doors off: the legs travel at once, the doors follow them
        AnimMath.State o = new AnimMath.State();
        GearMath.sequence(o, true, false);
        boolean now = o.gear > 0F && o.door == 1F;
        for (int i = 0; i < g; i++) {
            GearMath.sequence(o, true, false);
        }
        check(back && now && o.gear == 1F && o.door == 0F && GearMath.retracts(VehicleType.JET) && GearMath.retracts(VehicleType.TILT)
                        && !GearMath.retracts(VehicleType.PLANE) && GearMath.retracts(VehicleType.SHUTTLE),
                "D3 gear: a reversal half way goes straight back; vehicles.gearDoors off - the doors follow the legs; jet / tiltrotor / shuttle"
                        + " retract, the light plane's gear is fixed");
    }

    // ---------------------------------------------------------------- ★12 the strut

    /** Runs a touchdown at `sink` b/t: {peak, the least after the peak, compression after 30 ticks, the peak's tick}. */
    private static float[] touchdown(float sink) {
        AnimMath.State s = new AnimMath.State();
        for (int i = 0; i < 5; i++) {
            GearMath.strut(s, false, -sink, true);                   // falling
        }
        float peak = 0, min = 9, at30 = 0;
        int peakAt = -1;
        for (int i = 0; i < 40; i++) {
            GearMath.strut(s, true, 0F, true);
            if (s.strut > peak) {
                peak = s.strut;
                peakAt = i;
            }
            if (peakAt >= 0 && i > peakAt) {
                min = Math.min(min, s.strut);
            }
            if (i == 29) {
                at30 = s.strut;
            }
        }
        return new float[] {peak, min, at30, peakAt};
    }

    private static void struts() {
        float[] mid = touchdown(0.15F), soft = touchdown(0.03F), hard = touchdown(0.5F);
        check(mid[0] > 0.75F && mid[0] < 0.95F && mid[3] <= 4 && mid[1] > 0.1F && mid[1] < 0.35F && Math.abs(mid[2] - GearMath.REST) < 0.03F,
                String.format(java.util.Locale.ROOT, "D3 strut: a 3 m/s touchdown peaks at %.0f %% (tick %d), rebounds to %.0f %%, settles at %.0f %% (parking %.0f %%)",
                        mid[0] * 100, (int) mid[3], mid[1] * 100, mid[2] * 100, GearMath.REST * 100));
        check(soft[0] < mid[0] && soft[0] > GearMath.REST && hard[0] == 1F && Math.abs(hard[2] - GearMath.REST) < 0.05F,
                String.format(java.util.Locale.ROOT, "D3 strut: softer landing - a smaller peak (%.0f %%), a hard one bottoms out at 100 %% and still settles",
                        soft[0] * 100));
        // in the air it extends; a bump on the ground compresses it; animations off = parking / extended
        AnimMath.State s = new AnimMath.State();
        for (int i = 0; i < 40; i++) {
            GearMath.strut(s, true, 0F, true);
        }
        float rest = s.strut;
        GearMath.strut(s, true, 0.1F, true);
        boolean bumped = s.strut > rest + 0.05F;
        for (int i = 0; i < 40; i++) {
            GearMath.strut(s, false, 0.2F, true);
        }
        float air = s.strut;
        AnimMath.State off = new AnimMath.State();
        GearMath.strut(off, true, -0.3F, false);
        boolean offOk = off.strut == GearMath.REST && off.prevStrut == GearMath.REST;
        GearMath.strut(off, false, 0F, false);
        offOk &= off.strut == 0F;
        check(Math.abs(rest - GearMath.REST) < 0.01F && bumped && air < 0.01F && offOk && GearMath.bump(0F, 0.01F) == 0F,
                "D3 strut: parked at 40 %, a bump (a step up) compresses it, in the air it extends to 0; vehicles.gearStruts off - parking / extended");
        // the airframe's sink and the light plane's spring legs
        float jet = GearMath.sink(VehicleType.JET, 0.85F), plane = GearMath.sink(VehicleType.PLANE, 0.85F);
        float rise = GearMath.flexRise(0.457F, 0.17F, 0.144F, 0.375F, GearMath.flex(0.85F));
        check(GearMath.sink(VehicleType.JET, GearMath.REST) == 0F && GearMath.sink(VehicleType.JET, 0.1F) == 0F && Math.abs(jet - 0.45F * GearMath.STROKE_JET) < 1e-6
                        && GearMath.flex(GearMath.REST) == 0F && GearMath.flex(1F) < 0F && plane > 0.01F && plane < 0.04F && Math.abs(plane - rise) < 1e-6
                        && GearMath.sink(VehicleType.HELI, 1F) == 0F,
                String.format(java.util.Locale.ROOT, "D3 strut: the airframe sinks by what is over the parking stroke (jet %.4f, the plane's flexing legs %.4f model units at 85 %%)",
                        jet, plane));
    }

    // ---------------------------------------------------------------- ★12 the torque links

    private static void links() {
        StringBuilder bad = new StringBuilder();
        int pairs = 0;
        for (String m : new String[] {"jet", "tilt"}) {
            for (AnimParts.Def up : AnimParts.of(m)) {
                if (up.link <= 0) {
                    continue;
                }
                for (AnimParts.Def lo : AnimParts.of(m)) {
                    if (lo.link >= 0 || Math.signum(lo.pivot[2]) != Math.signum(up.pivot[2]) || !up.name.substring(0, 5).equals(lo.name.substring(0, 5))) {
                        continue;
                    }
                    pairs++;
                    float d0 = up.pivot[1] - lo.pivot[1], l = up.linkLen, st = up.linkStroke;
                    float m0 = (float) Math.sqrt(l * l - d0 * d0 / 4);
                    float[] joint = {up.pivot[0] + m0, (up.pivot[1] + lo.pivot[1]) / 2};
                    double worst = 0;
                    for (float c = 0F; c <= 1.0001F; c += 0.05F) {
                        float a = GearMath.linkTurn(up.linkD0, l, st, c);
                        float[] u = rot(joint, up.pivot, a);
                        float[] w = rot(joint, lo.pivot, -a);
                        w[1] += GearMath.travel(c, lo.stroke);
                        worst = Math.max(worst, Math.hypot(u[0] - w[0], u[1] - w[1]));
                    }
                    if (worst > 1e-4 || Math.abs(d0 - up.linkD0) > 1e-3 || lo.stroke != st || GearMath.linkTurn(up.linkD0, l, st, GearMath.REST) != 0F) {
                        bad.append(' ').append(m).append('/').append(up.name).append(String.format(java.util.Locale.ROOT, " %.5f", worst));
                    }
                }
            }
        }
        check(pairs == 5 && bad.length() == 0 && GearMath.linkTurn(0.1488F, 0.0986F, 0.075F, 1F) > 0F && GearMath.linkTurn(0.1488F, 0.0986F, 0.075F, 0F) < 0F,
                "D3 struts: the torque links stay joined over the whole stroke (" + pairs + " pairs: jet main x2, nose, tiltrotor x2), fold out compressed" + bad);
    }

    /** (x, y) of p turned by deg about +z through c (only x / y used). */
    private static float[] rot(float[] p, float[] c, float deg) {
        double a = Math.toRadians(deg), cs = Math.cos(a), sn = Math.sin(a);
        double x = p[0] - c[0], y = p[1] - c[1];
        return new float[] {(float) (c[0] + x * cs - y * sn), (float) (c[1] + x * sn + y * cs)};
    }

    // ---------------------------------------------------------------- ★12 the rows posed on the OBJ

    private static void rows() throws java.io.IOException {
        StringBuilder bad = new StringBuilder();
        int legs = 0, doors = 0;
        for (String m : new String[] {"jet", "tilt"}) {
            List<Object[]> tris = SelfTestSVA.objTris(m);
            List<AnimParts.Def> defs = AnimParts.of(m);
            for (AnimParts.Def w : defs) {
                if (w.kind != AnimParts.WHEEL || w.gearPivot == null || w.tag == null) {
                    continue;
                }
                legs++;
                // the retracted wheel (extended, as in the air) and the same side's closed doors: nothing of the wheel hangs
                // under the doors' lowest edge
                float lowWheel = 9F, lowDoor = 9F;
                for (Object[] o : tris) {
                    String tag = (String) o[2];
                    float[] t = (float[]) o[1];
                    float cz = (t[2] + t[5] + t[8]) / 3F;
                    if (tag == null || Math.signum(cz) != Math.signum(w.pivot[2]) && w.pivot[2] != 0F) {
                        continue;
                    }
                    for (AnimParts.Def d : defs) {
                        if (!d.takesTag(tag) || !d.holds((String) o[0], (t[0] + t[3] + t[6]) / 3F, (t[1] + t[4] + t[7]) / 3F, cz)) {
                            continue;
                        }
                        for (int k = 0; k < 9; k += 3) {
                            float[] p = {t[k], t[k + 1], t[k + 2]};
                            if (d == w) {
                                p[1] += GearMath.travel(0F, d.stroke);
                                float[] q = rot(p, d.gearPivot, d.gearAngle);
                                lowWheel = Math.min(lowWheel, q[1]);
                            } else if (d.kind == AnimParts.DEVICE && d.device == AnimParts.DOOR && (w.pivot[2] == 0F) == (d.tag.startsWith("n"))) {
                                float[] q = turn(p, d.pivot, d.axis1, d.ang1);
                                lowDoor = Math.min(lowDoor, q[1]);
                            }
                        }
                    }
                }
                if (!(lowWheel > lowDoor && lowDoor < 9F && w.gearAngle == -90F && w.stroke > 0F)) {
                    bad.append(' ').append(m).append('/').append(w.name).append(String.format(java.util.Locale.ROOT, " wheel %.3f door %.3f", lowWheel, lowDoor));
                }
            }
            for (AnimParts.Def d : defs) {
                if (d.kind == AnimParts.DEVICE && d.device == AnimParts.DOOR) {
                    doors++;
                    float l = (float) Math.sqrt(d.axis1[0] * d.axis1[0] + d.axis1[1] * d.axis1[1] + d.axis1[2] * d.axis1[2]);
                    if (Math.abs(l - 1F) > 0.01F || d.tag == null || Math.abs(d.ang1) != 90F) {
                        bad.append(' ').append(m).append('/').append(d.name).append(" axis");
                    }
                }
            }
        }
        // the light plane: fixed, flexing
        int flex = 0;
        for (AnimParts.Def d : AnimParts.of("plane")) {
            flex += d.flex && d.gearAngle == 0F && d.gearLift == 0F ? 1 : 0;
        }
        check(legs == 5 && doors == 10 && flex == 6 && bad.length() == 0,
                "D3 gear rows: " + legs + " retracting wheels (jet main x2 + nose, tiltrotor x2) - retracted they lie inside the well, above their closed doors; " + doors
                        + " doors with unit hinge axes; the light plane's 6 gear parts flex, none retracts" + bad);
    }

    /** p turned by deg about the axis k through c (Rodrigues). */
    private static float[] turn(float[] p, float[] c, float[] k, float deg) {
        double a = Math.toRadians(deg), cs = Math.cos(a), sn = Math.sin(a);
        double x = p[0] - c[0], y = p[1] - c[1], z = p[2] - c[2];
        double dot = k[0] * x + k[1] * y + k[2] * z;
        double cx = k[1] * z - k[2] * y, cy = k[2] * x - k[0] * z, cz = k[0] * y - k[1] * x;
        return new float[] {(float) (c[0] + x * cs + cx * sn + k[0] * dot * (1 - cs)), (float) (c[1] + y * cs + cy * sn + k[1] * dot * (1 - cs)),
                (float) (c[2] + z * cs + cz * sn + k[2] * dot * (1 - cs))};
    }

    // ---------------------------------------------------------------- ★13 the nozzle

    private static void nozzle() throws java.io.IOException {
        float idle = NozzleMath.diameter(0, false), cruise = NozzleMath.diameter(0.5, false), max = NozzleMath.diameter(1, false),
                ab = NozzleMath.diameter(1, true);
        boolean mono = true;
        for (double t = 0.05; t <= 1.0; t += 0.05) {
            mono &= NozzleMath.diameter(t, false) < NozzleMath.diameter(t - 0.05, false);
        }
        check(idle == 6.3F && Math.abs(cruise - 5.4F) < 0.1F && Math.abs(max - 4.7F) < 1e-4 && ab == 6.9F && mono,
                String.format(java.util.Locale.ROOT, "D3 nozzle: exit diameter idle %.1f, cruise %.1f, full dry %.1f (narrowest), afterburner %.1f (widest)",
                        idle, cruise, max, ab));
        float pIdle = NozzleMath.petalDeg(idle), pMax = NozzleMath.petalDeg(max), pAb = NozzleMath.petalDeg(ab);
        AnimMath.State s = new AnimMath.State();
        int ticks = 0;
        while (s.nozzle < pMax - 1e-3 && ticks < 100) {
            AnimMath.nozzle(s, 1, false, true, true);
            ticks++;
        }
        AnimMath.State off = new AnimMath.State();
        AnimMath.nozzle(off, 1, true, true, false);
        AnimMath.State still = new AnimMath.State();
        AnimMath.nozzle(still, 1, true, false, true);
        float ratio = NozzleMath.exitRadius(pMax) / NozzleMath.exitRadius(pIdle);
        check(Math.abs(pIdle - NozzleMath.REST_DEG) < 0.05F && pAb < pIdle && pMax > 20F && Math.abs(ratio - max / idle) < 0.01F && ticks <= 10
                        && off.nozzle == NozzleMath.REST_DEG && still.nozzle <= NozzleMath.REST_DEG,
                String.format(java.util.Locale.ROOT, "D3 nozzle: petals idle %.1f deg (as modelled), full dry %.1f, afterburner %.1f in from the axis - the exit"
                        + " keeps the mock-up's ratio (%.3f); idle to full in %d ticks; animations off - as modelled", pIdle, pMax, pAb, ratio, ticks));
        // the 18 petals: one row each, tagged, turning about the root's tangent (unit, square to the radius)
        int rows = 0;
        boolean geo = true;
        for (AnimParts.Def d : AnimParts.of("jet")) {
            if (d.kind == AnimParts.DEVICE && d.device == AnimParts.PETAL) {
                rows++;
                float ry = d.pivot[1] - NozzleMath.AXIS_Y, rz = d.pivot[2];
                geo &= Math.abs(Math.sqrt(ry * ry + rz * rz) - NozzleMath.R_ROOT) < 1e-4 && Math.abs(d.axis1[1] * ry + d.axis1[2] * rz) < 1e-5
                        && d.axis1[0] == 0F && d.tag.equals("pet" + d.name.substring(5));
                // opening (+ deg) moves the petal's tip outward
                float[] tip = {d.pivot[0] - NozzleMath.LEN, d.pivot[1] - ry / NozzleMath.R_ROOT * 0.02F, d.pivot[2] - rz / NozzleMath.R_ROOT * 0.02F};
                float[] q = turn(tip, d.pivot, d.axis1, 5F);
                double r0 = Math.hypot(tip[1] - NozzleMath.AXIS_Y, tip[2]), r1 = Math.hypot(q[1] - NozzleMath.AXIS_Y, q[2]);
                geo &= r1 > r0;
            }
        }
        // the flame: idle glow, longer with the throttle, the afterburner's cone with its diamonds downstream, shrinking
        boolean flame = NozzleMath.flameLen(0, false) == NozzleMath.IDLE_LEN && NozzleMath.flameLen(0.5, false) > NozzleMath.IDLE_LEN
                && NozzleMath.flameLen(1, false) == NozzleMath.DRY_LEN && NozzleMath.flameLen(0.2, true) == NozzleMath.AB_LEN && NozzleMath.flameBlue(0) == 0F
                && NozzleMath.flameBlue(1) == 1F;
        float lastT = 0, lastS = 9;
        for (int k = 0; k < NozzleMath.DIAMONDS; k++) {
            float[] dm = NozzleMath.diamond(k);
            flame &= dm[0] > lastT && dm[0] < 1F && dm[1] < lastS && dm[1] > 0.2F;
            lastT = dm[0];
            lastS = dm[1];
        }
        check(rows == NozzleMath.PETALS && geo && flame && Math.abs(NozzleMath.AB_LEN * 16 - 30.4F) < 0.5F,
                "D3 nozzle: 18 petal rows (tagged, hinged on the root's tangent, opening outward); the flame - idle glow 0.1, the dry flame longer"
                        + " and bluer with the throttle, the afterburner 1.9 (30 concept units) with " + NozzleMath.DIAMONDS + " shock diamonds shrinking downstream");
    }

    // ---------------------------------------------------------------- ★18 the HUD

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-4;
    }

    private static void hudProjection() {
        float D = HudMath.DIST;
        boolean ladder = near(HudMath.ladderY(5, 0), D * Math.tan(Math.toRadians(5))) && near(HudMath.ladderY(0, 10), -D * Math.tan(Math.toRadians(10)))
                && near(HudMath.ladderY(10, 10), 0) && HudMath.ladderY(-5, 0) < 0 && HudMath.ladderY(15, 0) > HudMath.ladderY(10, 0);
        int[] r0 = HudMath.rungs(0F), r20 = HudMath.rungs(20F);
        boolean rungs = r0.length > 0 && r0[0] == -5 && r0[r0.length - 1] == 5 && contains(r0, 0) && contains(r20, 25) && !contains(r20, 0);
        check(ladder && rungs, "D3 HUD: the pitch ladder - rungs every 5 deg at DIST x tan(rung - pitch) (nose up 10: the horizon "
                + String.format(java.util.Locale.ROOT, "%.4f", HudMath.ladderY(0, 10)) + " under the axis), on view +-9 deg round the nose");
        // the flight-path vector: level flight at pitch 0 sits on the axis; climbing 10 deg with the nose 10 up too; a drift right
        float[] lv = HudMath.fpv(1, 0, 0, 0F), cl = HudMath.fpv(1, 0, Math.tan(Math.toRadians(10)), 10F), dr = HudMath.fpv(1, 0.1, 0, 0F),
                dn = HudMath.fpv(1, 0, -0.1, 0F), sink = HudMath.fpv(1, 0, 0, 5F);
        double[] b = HudMath.body(0, 1, 0F), br = HudMath.body(-1, 0, 0F);
        check(near(lv[0], 0) && near(lv[1], 0) && near(cl[1], 0) && dr[0] > 0 && dn[1] < 0 && sink[1] < 0 && HudMath.fpv(0.01, 0, 0, 0F) == null
                        && near(b[0], 1) && near(b[1], 0) && near(br[1], 1) && near(br[0], 0),
                "D3 HUD: the flight-path vector from the motion - on the axis in level flight, on it climbing at the pitch, right with a drift right,"
                        + " under it descending or nose high; none standing still (yaw 0 faces +z, its right is -x)");
        boolean hdg = near(HudMath.headingX(270, 268), HudMath.at(2)) && HudMath.headingX(250, 268) < 0 && Float.isNaN(HudMath.headingX(90, 268))
                && near(HudMath.headingX(5, 355), HudMath.at(10));
        boolean tapes = HudMath.tapeY(180, 165, HudMath.SPEED_DEG) > 0 && near(HudMath.tapeY(165, 165, HudMath.SPEED_DEG), 0)
                && Float.isNaN(HudMath.tapeY(300, 165, HudMath.SPEED_DEG)) && near(HudMath.tapeY(96, 86, HudMath.ALT_DEG), HudMath.at(2));
        check(hdg && tapes, "D3 HUD: the heading tape in true degrees across (270 two degrees right of 268, wraps over north), the tapes "
                + HudMath.SPEED_DEG + " deg per km/h / per block, +-" + HudMath.TAPE_HALF + " deg");
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static void hudGlass() {
        double[] eye = CockpitView.eye(VehicleType.JET, 0);
        float[] box = HudMath.glass(VehicleType.JET.cabWindows, eye);
        boolean ok = box != null;
        String info = "none";
        if (ok) {
            float[] reach = HudMath.reach(box, eye);
            double[][] pl = HudMath.clipPlanes(box);
            float x = HudMath.planeX(eye);
            ok = reach[1] >= HudMath.HDG_Y + 2.5F && reach[0] <= HudMath.ROW2_Y - 1F && -reach[2] >= -HudMath.SPEED_X + 6F && reach[3] >= HudMath.ALT_X + 6F
                    && HudMath.kept(pl, x, eye[1], eye[2]) && HudMath.kept(pl, x, eye[1] + HudMath.at(HudMath.HDG_Y), eye[2] + HudMath.at(HudMath.ALT_X))
                    && !HudMath.kept(pl, x, box[3] + 0.01, eye[2]) && !HudMath.kept(pl, x, eye[1], box[5] + 0.01) && !HudMath.kept(pl, x, box[2] - 0.01, eye[2]);
            info = String.format(java.util.Locale.ROOT, "plane x %.3f inside the canopy box %.2f..%.2f; the glass reaches %.1f / %.1f deg up / down, %.1f / %.1f"
                    + " left / right", x, box[0], box[1], reach[1], reach[0], -reach[2], reach[3]);
        }
        check(ok && HudMath.glass(new float[][] {{0F, 0.5F, 0F, 2F, -1F, 1F}}, eye) == null && HudMath.glass(null, eye) == null,
                "D3 HUD: the symbols' plane lies in the jet's canopy glass box and every symbol row fits it - " + info
                        + "; the clip planes keep the boresight, cut outside the glass");
    }

    private static void hudText() {
        HudMath.Target w = HudMath.target(new double[] {100, 0, 3}, 100, 450, "27", -3200);
        HudMath.Target r = HudMath.target(null, 0, 0, "27", -3200);
        HudMath.Target past = HudMath.target(null, 0, 0, "09", 40);
        HudMath.Target none = HudMath.target(null, 0, 0, null, Double.NaN);
        String[] d1 = HudMath.distance(450), d2 = HudMath.distance(3200), d3 = HudMath.distance(999.6);
        check(w.kind == HudMath.T_WPT && near(w.dist, 450) && "3".equals(w.label) && r.kind == HudMath.T_RWY && near(r.dist, 3200) && "27".equals(r.label)
                        && past.dist == 0 && none.kind == HudMath.T_NONE && "450".equals(d1[0]) && "sva.jethud.m".equals(d1[1]) && "3,2".equals(d2[0])
                        && "sva.jethud.km".equals(d2[1]) && "1000".equals(d3[0]),
                "D3 HUD: the distance line - a flight-plan waypoint (stage D4's hook) before the runway ЗАХВ flies to; 450 м, 3,2 км");
        check(HudMath.stallWarn(true, false, 0.9, 0.9) && !HudMath.stallWarn(true, false, 1.2, 0.9) && HudMath.stallWarn(true, true, 2, 0.9)
                        && !HudMath.stallWarn(false, true, 0.1, 0.9) && HudMath.brightness(0F) == 1F && near(HudMath.brightness(1F), HudMath.NIGHT_BRIGHT)
                        && near(HudMath.g(0F), 1) && near(HudMath.g(60F), 2) && HudMath.g(85F) <= 3F,
                "D3 HUD: the stall warning under 1.1 x the stall speed in the air; dimmer at night; G 1 / cos(bank)");
    }

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        StringBuilder miss = new StringBuilder();
        for (String k : new String[] {"kmh", "alt", "thr", "ab", "vs", "g", "rwy", "wpt", "stall", "m", "km"}) {
            String key = "sva.jethud." + k;
            if (ru == null || en == null || !ru.contains(key) || !en.contains(key)) {
                miss.append(' ').append(key);
            }
        }
        check(miss.length() == 0, "D3 lang: the HUD's labels in ru + en" + miss);
    }
}
