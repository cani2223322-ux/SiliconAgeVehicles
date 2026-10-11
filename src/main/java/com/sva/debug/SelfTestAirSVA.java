package com.sva.debug;

import java.util.List;

import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.AnimParts;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HighLiftMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.SkinMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

/**
 * Aircraft stage A self-tests (★1 flaps, ★2 slats, ★3 spoilers / airbrake, ★26 their effect; ★8 / ★9 the model) - pure
 * logic on the dedicated server ([SVA-TEST] lines, SelfTestSVA's counters): the mock-up's numbers per setting (stall,
 * take-off run, drag, EU, speed limits), the flaps-0 flight identical to before, the rules (flap steps and their
 * protection, the slats' hysteresis, the airbrake's retraction, the ground spoilers), the airbrake's descent and the
 * roll-out, the synced word, the animation's travel, the tagged parts (every moving piece taken by exactly one row and
 * no untagged row), the device rows' geometry, the passenger headrests under the roof.
 */
final class SelfTestAirSVA {

    private SelfTestAirSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    static void run() throws java.io.IOException {
        table();
        identical();
        rules();
        airbrake();
        synced();
        anim();
        parts();
        headrests();
    }

    private static PlaneParams p(VehicleType t, int f, boolean slats) {
        return HighLiftMath.params(HighLiftMath.spec(t), t.plane, f, slats, false, false);
    }

    /** The mock-up's table (veh_air2_1_highlift.png): stall, run, drag, EU, max speed per setting. */
    private static void table() {
        HighLiftMath.Spec s = HighLiftMath.PLANE;
        int[] stall = {36, 32, 30, 29}, slatStall = {33, 29, 27, 26}, maxKmh = {0, 160, 140, 120};
        double[] run = {1, 0.82, 0.74, 0.70}, drag = {1, 1.12, 1.30, 1.60}, eu = {1, 1.1, 1.25, 1.5};
        double base = PlaneMath.takeoffDistance(VehicleType.PLANE, false), baseRw = PlaneMath.takeoffDistance(VehicleType.PLANE, true);
        boolean ok = s.settings() == 4 && s.angles[1] == 10 && s.angles[2] == 20 && s.angles[3] == 35;
        StringBuilder got = new StringBuilder();
        for (int f = 0; f < 4; f++) {
            PlaneParams a = p(VehicleType.PLANE, f, false), b = p(VehicleType.PLANE, f, true);
            double r = PlaneMath.takeoffDistance(VehicleType.PLANE, b, false) / base;
            double rr = PlaneMath.takeoffDistance(VehicleType.PLANE, b, true) / baseRw;
            ok &= near(a.stall * 72, stall[f], 0.5) && near(b.stall * 72, slatStall[f], 0.5) && near(r, run[f], 0.02) && near(rr, run[f], 0.03)
                    && near(a.drag, drag[f], 1e-9) && near(a.cost, eu[f], 1e-9) && (f == 0 || near(s.maxSpeed[f] * 72, maxKmh[f], 1e-6))
                    && b.liftOff > b.stall;
            got.append(' ').append(Math.round(a.stall * 72)).append('/').append(Math.round(b.stall * 72)).append(':')
                    .append(Math.round(r * 100)).append('%');
        }
        check(ok, "stage A plane: stall 36/32/30/29 km/h (-3 with slats), run 100/82/74/70 % (also on a runway), drag +12/+30/+60 %, EU x1.1/1.25/1.5, "
                + "max 160/140/120 km/h, lift-off over the stall -" + got);
        // the jet 0 / 20 / 40 (stall x0.79 / x0.71 - 65 -> 51 -> 46 km/h), run 85 / 75 %; the tiltrotor 0 / 15 / 25, run 82 / 70 %
        double jb = PlaneMath.takeoffDistance(VehicleType.JET, false), tb = PlaneMath.takeoffDistance(VehicleType.TILT, false);
        double j1 = PlaneMath.takeoffDistance(VehicleType.JET, p(VehicleType.JET, 1, true), false) / jb;
        double j2 = PlaneMath.takeoffDistance(VehicleType.JET, p(VehicleType.JET, 2, true), false) / jb;
        double t1 = PlaneMath.takeoffDistance(VehicleType.TILT, p(VehicleType.TILT, 1, false), false) / tb;
        double t2 = PlaneMath.takeoffDistance(VehicleType.TILT, p(VehicleType.TILT, 2, false), false) / tb;
        check(near(j1, 0.85, 0.02) && near(j2, 0.75, 0.02) && near(t1, 0.82, 0.02) && near(t2, 0.70, 0.02)
                        && near(p(VehicleType.JET, 1, false).stall * 72, 65 * 0.79, 0.5) && near(p(VehicleType.JET, 2, false).stall * 72, 65 * 0.71, 0.5)
                        && HighLiftMath.JET.angles[2] == 40 && HighLiftMath.TILT.angles[2] == 25 && !HighLiftMath.TILT.slats && !HighLiftMath.TILT.airbrake
                        && HighLiftMath.TILT.flaperons && HighLiftMath.spec(VehicleType.HELI) == null && HighLiftMath.spec(VehicleType.SHUTTLE) == null,
                "stage A jet / tiltrotor: runs " + Math.round(j1 * 100) + " / " + Math.round(j2 * 100) + " % (jet), " + Math.round(t1 * 100) + " / "
                        + Math.round(t2 * 100) + " % (tiltrotor flaperons); the jet's stall 51 / 46 km/h; no devices on other types");
        // drag: the top speed at full throttle falls as 1 / sqrt(drag); EU x the setting's factor
        PlaneParams p3 = p(VehicleType.PLANE, 3, true);
        double top = VehicleType.PLANE.topSpeed;
        double a0 = PlaneMath.accel(VehicleType.PLANE, p3, top / Math.sqrt(1.6), 1, true, false, 1);
        double c0 = PlaneMath.cost(VehicleType.PLANE, 0.7, true, 0.05, 80, 0.3, true);
        double c3 = PlaneMath.cost(VehicleType.PLANE, p3, 0.7, true, 0.05, 80, 0.3, true);
        check(near(a0, 0, 1e-9) && near(c3 / c0, 1.5, 1e-9), "stage A drag / EU: flaps 35 balance full thrust at top / sqrt(1.6) ("
                + VehicleMath.kmh(top / Math.sqrt(1.6)) + " km/h), cost x1.5");
    }

    /** Flaps 0, slats in, nothing up: the type's own params object - every formula as before. */
    private static void identical() {
        boolean same = true;
        java.util.Random rnd = new java.util.Random(7);
        for (VehicleType t : new VehicleType[] {VehicleType.PLANE, VehicleType.JET, VehicleType.TILT}) {
            PlaneParams b = HighLiftMath.params(HighLiftMath.spec(t), t.plane, 0, false, false, false);
            same &= b == t.plane && !b.airbrake && b.drag == 1 && b.cost == 1 && b.brake == 1;
            for (int i = 0; i < 200; i++) {
                double v = rnd.nextDouble() * t.topSpeed * 1.3, thr = rnd.nextDouble(), vy = rnd.nextDouble() * 0.6 - 0.3, y = 60 + rnd.nextDouble() * 150;
                boolean air = rnd.nextBoolean(), rw = rnd.nextBoolean(), up = rnd.nextBoolean(), dn = rnd.nextBoolean(), pw = rnd.nextBoolean();
                same &= PlaneMath.accel(t, v, thr, air, rw, 1.5) == PlaneMath.accel(t, b, v, thr, air, rw, 1.5)
                        && PlaneMath.airAccel(t, v, thr, vy, up, pw, 1) == PlaneMath.airAccel(t, b, v, thr, vy, up, pw, 1)
                        && PlaneMath.airVy(t.plane, v, vy, up, dn, pw, y) == PlaneMath.airVy(b, v, vy, up, dn, pw, y)
                        && PlaneMath.cost(t, thr, air, vy, y, 0.5, true) == PlaneMath.cost(t, b, thr, air, vy, y, 0.5, true)
                        && PlaneMath.brake(rw) == PlaneMath.brake(b, rw)
                        && FlightMath.maxClimb(t, Movement.PLANE, pw, v, y) == FlightMath.maxClimb(t, Movement.PLANE, b, pw, v, y)
                        && FlightMath.moveAllowed(t, Movement.PLANE, v, vy, 1, y, pw, air, air, v)
                        == FlightMath.moveAllowed(t, Movement.PLANE, b, v, vy, 1, y, pw, air, air, v);
            }
            same &= PlaneMath.takeoffDistance(t, false) == PlaneMath.takeoffDistance(t, b, false);
        }
        check(same, "stage A: flaps 0 / slats in / nothing up = the types' own PlaneParams - thrust, glide, climb, EU, brakes, take-off run and "
                + "the server's move check exactly as before (random states)");
    }

    private static void rules() {
        HighLiftMath.Spec s = HighLiftMath.PLANE;
        double k = 1 / 72.0;
        // steps: + extends one, - retracts one; over the next setting's limit refused; at the ends refused; retracting always
        boolean steps = HighLiftMath.flapStep(s, 0, 1, 100 * k) == 1 && HighLiftMath.flapStep(s, 1, 1, 130 * k) == 2
                && HighLiftMath.flapStep(s, 2, 1, 125 * k) == HighLiftMath.REFUSED_SPEED && HighLiftMath.flapStep(s, 2, 1, 115 * k) == 3
                && HighLiftMath.flapStep(s, 3, 1, 50 * k) == HighLiftMath.REFUSED_END && HighLiftMath.flapStep(s, 0, -1, 50 * k) == HighLiftMath.REFUSED_END
                && HighLiftMath.flapStep(s, 3, -1, 300 * k) == 2 && HighLiftMath.flapStep(s, 0, 1, 170 * k) == HighLiftMath.REFUSED_SPEED;
        check(steps, "stage A flaps: a step a press; > 120 km/h 35 deg refused, > 160 none extend; retracting at any speed");
        // protection: over a setting's limit it blows back to the highest allowed one
        boolean blow = HighLiftMath.blowBack(s, 3, 125 * k) == 2 && HighLiftMath.blowBack(s, 2, 145 * k) == 1 && HighLiftMath.blowBack(s, 3, 150 * k) == 1
                && HighLiftMath.blowBack(s, 3, 170 * k) == 0 && HighLiftMath.blowBack(s, 2, 100 * k) == 2 && HighLiftMath.limitKmh(s, 3) == 120
                && HighLiftMath.limitKmh(s, 0) == 160;
        check(blow, "stage A flaps protection: 35 deg at 125 km/h goes to 20, 20 at 145 to 10, everything over 160 to 0; the lamp's limit 120");
        // slats: out < 70 or AoA > 10, in > 90, between as they were; always out with the flaps down; none on the tiltrotor
        boolean sl = HighLiftMath.slats(s, false, 60 * k, 3, 0) && !HighLiftMath.slats(s, true, 95 * k, 3, 0) && HighLiftMath.slats(s, true, 80 * k, 3, 0)
                && !HighLiftMath.slats(s, false, 80 * k, 3, 0) && HighLiftMath.slats(s, false, 80 * k, 11, 0) && HighLiftMath.slats(s, false, 150 * k, 0, 1)
                && !HighLiftMath.slats(HighLiftMath.TILT, true, 10 * k, 20, 2) && !HighLiftMath.slats(null, true, 0, 20, 2);
        double aStall = HighLiftMath.aoa(0.5, 0.5, 0), aFast = HighLiftMath.aoa(0.5, 1.0, 0), aClimb = HighLiftMath.aoa(0.5, 1.0, 0.2);
        check(sl && near(aStall, HighLiftMath.STALL_AOA, 1e-9) && near(aFast, HighLiftMath.STALL_AOA / 4, 1e-9) && aClimb > aFast + 10,
                "stage A slats: out < 70 km/h or AoA > 10, in > 90 (hysteresis), always with the flaps; AoA 15 deg at the stall, a quarter at twice it, + the climb");
        // airbrake: retracts over half throttle; ground spoilers: up at a low-throttle touch-down, down on the throttle / stopped / in the air
        boolean ab = HighLiftMath.airbrakeHeld(true, 0.5) && !HighLiftMath.airbrakeHeld(true, 0.55) && !HighLiftMath.airbrakeHeld(false, 0);
        boolean gs = HighLiftMath.groundSpoilers(s, false, true, false, 0.1, 0.6) && !HighLiftMath.groundSpoilers(s, false, true, false, 0.4, 0.6)
                && HighLiftMath.groundSpoilers(s, true, false, false, 0.3, 0.6) && !HighLiftMath.groundSpoilers(s, true, false, false, 0.6, 0.6)
                && !HighLiftMath.groundSpoilers(s, true, false, false, 0.1, 0.02) && !HighLiftMath.groundSpoilers(s, true, false, true, 0.1, 0.6)
                && !HighLiftMath.groundSpoilers(HighLiftMath.TILT, false, true, false, 0, 0.6);
        check(ab && gs, "stage A spoilers: the airbrake retracts over 50 % throttle; the ground spoilers come up at a touch-down at <= 25 % "
                + "and go down over 50 %, stopped or in the air; none on the tiltrotor");
        check(HighLiftMath.brakeDeg(HighLiftMath.JET, 170 * k) == 45F && HighLiftMath.brakeDeg(HighLiftMath.JET, 190 * k) == 25F
                        && HighLiftMath.brakeDeg(s, 300 * k) == 50F,
                "stage A jet: the dorsal brake opens 45 deg, only 25 over 180 km/h; the plane's spoilers always 50");
        // the server's actions: the pilot's only
        check(EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_FLAPS_DOWN, false, true) && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_FLAPS_UP, true, false)
                        && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_AIRBRAKE, true, false) && EntityVehicleSVA.A_AIRBRAKE == 27,
                "stage A: flaps / airbrake actions (25..27) are the pilot's alone");
    }

    /** The airbrake's descent, the roll-out with the ground spoilers. */
    private static void airbrake() {
        VehicleType t = VehicleType.PLANE;
        PlaneParams ab = HighLiftMath.params(HighLiftMath.PLANE, t.plane, 0, false, true, false);
        double v = 0.8, vyOld = 0, vy = 0;
        for (int i = 0; i < 60; i++) {
            vy = PlaneMath.airVy(ab, v, vy, false, false, true, 90);
            vyOld = PlaneMath.airVy(t.plane, 0.8, vyOld, false, false, true, 90);
        }
        double gain = PlaneMath.airAccel(t, ab, v, 0.3, vy, false, true, 1) - PlaneMath.airAccel(t, ab, v, 0.3, 0, false, true, 1);
        double plain = PlaneMath.airAccel(t, t.plane, v, 0.3, vy, false, true, 1) - PlaneMath.airAccel(t, t.plane, v, 0.3, 0, false, true, 1);
        check(ab.airbrake && near(vy, -t.plane.dive * HighLiftMath.AIRBRAKE_SINK, 1e-6) && vyOld == 0 && near(gain, 0, 1e-12) && plain > 0
                        && PlaneMath.airAccel(t, ab, v, 0.3, 0, false, true, 1) < PlaneMath.airAccel(t, t.plane, v, 0.3, 0, false, true, 1),
                "stage A airbrake: no keys sinks at " + Math.round(-vy * 20 * 10) / 10.0 + " m/s (level without it), the descent gains no speed "
                        + "(it would +" + Math.round(plain * 1e5) / 1e5 + " b/t2), more drag");
        // the roll-out: from 0.6 b/t at zero throttle with S, off a runway - the ground spoilers' run x ~0.6
        double[] d = new double[2];
        for (int k = 0; k < 2; k++) {
            PlaneParams pp = HighLiftMath.params(HighLiftMath.PLANE, t.plane, 3, true, false, k == 1);
            double s = 0.6;
            for (int i = 0; i < 400 && s > 0; i++) {
                s = Math.max(0, s + PlaneMath.accel(t, pp, s, 0, false, false, 1) - PlaneMath.brake(pp, false));
                d[k] += s;
            }
        }
        check(d[1] < d[0] * 0.65 && d[1] > d[0] * 0.5, "stage A roll-out: the ground spoilers cut the landing roll " + Math.round(d[0] * 10) / 10.0
                + " -> " + Math.round(d[1] * 10) / 10.0 + " blocks (x" + Math.round(d[1] / d[0] * 100) / 100.0 + ")");
    }

    private static void synced() {
        boolean ok = true;
        for (int f = 0; f < 4; f++) {
            for (int b = 0; b < 32; b++) {
                int w = HighLiftMath.pack(f, (b & 1) != 0, (b & 2) != 0, (b & 4) != 0, (b & 8) != 0, (b & 16) != 0);
                ok &= (w & HighLiftMath.P_FLAPS) == f && ((w & HighLiftMath.P_AIRBRAKE) != 0) == ((b & 1) != 0)
                        && ((w & HighLiftMath.P_GROUND) != 0) == ((b & 2) != 0) && ((w & HighLiftMath.P_SLATS) != 0) == ((b & 4) != 0)
                        && ((w & HighLiftMath.P_WARN) != 0) == ((b & 8) != 0) && ((w & HighLiftMath.P_FAST) != 0) == ((b & 16) != 0);
            }
        }
        // the derived params are cached (one object per combination) and keep the type's thrust / climb / glide
        PlaneParams a = p(VehicleType.JET, 2, true), b = p(VehicleType.JET, 2, true);
        check(ok && a == b && a.thrust == VehicleType.JET.plane.thrust && a.glideSpeed == VehicleType.JET.plane.glideSpeed
                && a.climb == VehicleType.JET.plane.climb, "stage A: the synced word round-trips every state; derived params cached, the type's thrust / climb kept");
    }

    private static void anim() {
        AnimMath.State s = new AnimMath.State();
        int steps = 0;
        while (s.flapPos < 3F && steps < 500) {
            AnimMath.highLift(s, 3, true, true, true);
            steps++;
        }
        boolean slow = steps >= 3 * HighLiftMath.FLAP_TICKS && steps <= 3 * HighLiftMath.FLAP_TICKS + 1 && s.slat == 1F && s.spoiler == 1F;
        float mid = HighLiftMath.PLANE.angleAt(1.5F), droop = HighLiftMath.flaperonAngle(HighLiftMath.TILT, 0F, 0),
                plane = HighLiftMath.flaperonAngle(HighLiftMath.TILT, 15F, 1);
        AnimMath.highLift(s, 0, false, false, false);
        check(slow && mid == 15F && droop == 25F && plane == 15F && s.flapPos == 0F && s.slat == 0F,
                "stage A anim: a flap step takes 1.5 s (0 -> 35 in " + steps + " ticks), half way 10 -> 20 = 15 deg; the flaperons droop 25 in the "
                        + "vertical mode, the setting in airplane mode; animations off = they jump");
    }

    /** Every tagged moving piece is taken by exactly one row (and no untagged row); the device rows' geometry. */
    private static void parts() throws java.io.IOException {
        StringBuilder bad = new StringBuilder();
        int devices = 0;
        for (String m : new String[] {"plane", "jet", "tilt"}) {
            List<Object[]> tris = SelfTestSVA.objTris(m);
            List<AnimParts.Def> defs = AnimParts.of(m);
            int tagged = 0, skin = 0;
            for (Object[] o : tris) {
                String tag = (String) o[2];
                if (tag == null) {
                    continue;
                }
                if (AnimParts.SKIN_TAG.equals(tag)) {
                    skin++;
                    continue;
                }
                tagged++;
                float[] t = (float[]) o[1];
                float cx = (t[0] + t[3] + t[6]) / 3F, cy = (t[1] + t[4] + t[7]) / 3F, cz = (t[2] + t[5] + t[8]) / 3F;
                int n = 0;
                for (AnimParts.Def d : defs) {
                    if (d.takesTag(tag) && d.holds((String) o[0], cx, cy, cz)) {
                        n++;
                    }
                    if (d.tag == null && d.takesTag(tag)) {
                        n += 100;                                   // an untagged row would take it
                    }
                }
                if (n != 1 && bad.length() < 200) {
                    bad.append(' ').append(m).append('/').append(tag).append('x').append(n);
                }
            }
            for (AnimParts.Def d : defs) {
                if (d.kind != AnimParts.DEVICE) {
                    continue;
                }
                devices++;
                float l = (float) Math.sqrt(d.axis1[0] * d.axis1[0] + d.axis1[1] * d.axis1[1] + d.axis1[2] * d.axis1[2]);
                boolean pivotSide = d.pivot[2] == 0 || Math.signum(d.pivot[2]) == Math.signum(d.min[2] + d.max[2]);
                if (d.tag == null || d.device < 0 || Math.abs(l - 1F) > 0.01F || d.refDeg <= 0 || d.ang1 <= 0 || d.move == null || !pivotSide) {
                    bad.append(' ').append(m).append('/').append(d.name).append(" geometry");
                }
            }
            if (tagged == 0 || skin == 0) {
                bad.append(' ').append(m).append(" untagged model");
            }
        }
        check(bad.length() == 0 && devices == 13, "stage A parts: every tagged triangle of plane / jet / tilt goes to exactly one row, no untagged row "
                + "takes one; " + devices + " device rows (plane flaps / slats / spoilers, jet flaps / slats / brake, tilt flaperons) with unit axes" + bad);
        // the plane's flap at 35 deg: its trailing edge goes down and back (about the hinge, then the move)
        AnimParts.Def fr = null;
        for (AnimParts.Def d : AnimParts.of("plane")) {
            if ("flapR".equals(d.name)) {
                fr = d;
            }
        }
        boolean down = false;
        if (fr != null) {
            float[] te = {fr.min[0], (fr.min[1] + fr.max[1]) / 2F, (fr.min[2] + fr.max[2]) / 2F};
            float[] q = turn(te, fr.pivot, fr.axis1, fr.ang1);
            down = q[1] + fr.move[1] < te[1] - 0.1F && q[0] + fr.move[0] > te[0] - 0.05F;
        }
        check(down && SkinMath.wingSkin("flap") && !SkinMath.wingSkin("brake") && !SkinMath.wingSkin(null),
                "stage A parts: the plane's flap trailing edge drops at 35 deg; the wing panel tile on the wing's pieces, not the jet's brake");
    }

    /** p turned by deg about the axis through c (Rodrigues). */
    private static float[] turn(float[] p, float[] c, float[] k, float deg) {
        double a = Math.toRadians(deg), cs = Math.cos(a), sn = Math.sin(a);
        double x = p[0] - c[0], y = p[1] - c[1], z = p[2] - c[2];
        double dot = k[0] * x + k[1] * y + k[2] * z;
        double cx = k[1] * z - k[2] * y, cy = k[2] * x - k[0] * z, cz = k[0] * y - k[1] * x;
        return new float[] {(float) (c[0] + x * cs + cx * sn + k[0] * dot * (1 - cs)), (float) (c[1] + y * cs + cy * sn + k[1] * dot * (1 - cs)),
                (float) (c[2] + z * cs + cz * sn + k[2] * dot * (1 - cs))};
    }

    /** The plane's seats under the roof: straight up from every seat vertex (fabric) the hull is hit (the headrests were over it). */
    private static void headrests() throws java.io.IOException {
        float[][] hull = RiderFitSVA.hull("plane");
        int out = 0, n = 0;
        float top = -9;
        for (Object[] o : SelfTestSVA.objTris("plane")) {
            if (!"fabric".equals(o[0])) {
                continue;
            }
            float[] t = (float[]) o[1];
            float cx = (t[0] + t[3] + t[6]) / 3F, cy = (t[1] + t[4] + t[7]) / 3F, cz = (t[2] + t[5] + t[8]) / 3F;
            n++;
            top = Math.max(top, Math.max(t[1], Math.max(t[4], t[7])));
            if (hull != null && !RiderFitSVA.hits(hull, cx, cy, cz, new float[] {0F, 1F, 0F})) {
                out++;
            }
        }
        check(hull != null && n > 0 && out == 0, "stage A: the plane's seats and headrests are all under its roof (" + out + " of " + n
                + " seat triangles see the sky; highest " + Math.round(top * 1000) / 1000.0 + ")");
    }
}
