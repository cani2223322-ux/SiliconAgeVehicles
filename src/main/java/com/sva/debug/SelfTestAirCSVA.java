package com.sva.debug;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sva.vehicle.AnimMath;
import com.sva.vehicle.AnimParts;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CockpitDials;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.RunwayMath;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

/**
 * Aircraft stage C self-tests (★17 the autopilot panel's modes, ★18 the flap / trim indicators and the annunciators, ★19
 * the HSI; mock-up docs/mockups/veh_air2_5_cockpit.png) - pure logic on the dedicated server: each autopilot mode holds
 * its target in a simulation through the planes' own PlaneMath (КУРС, ВЫС, ВЕРТ, ВЫС + ВЕРТ, СКОР, ЗАХВ onto a runway from
 * an offset and an angle, both axes), the knobs' steps, the synced panel word, the runway finder on a fake grid, the HSI's
 * bearing / deviation / card maths, the annunciator roles, the light plane's panels found and readable from the pilot's
 * eye (CockpitCheckSVA), the flap lever / trim wheel rows, the cosmetic trim, the «Панель автопилота» function.
 */
final class SelfTestAirCSVA {

    private SelfTestAirCSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }

    static void run() throws java.io.IOException {
        modes();
        speed();
        capture();
        knobs();
        runways();
        hsi();
        lamps();
        layout();
        controls();
        function();
    }

    // ---------------------------------------------------------------- the modes in a simulation

    /** One aircraft state of the simulation (compass heading; PlanePhysicsSVA's autopilot branch, no keys). */
    static final class Sim {
        double x, y, z, hdg, speed, vy, thr, prevSpeed = Double.NaN;
        double minSpeed = 1e9, maxSpeed, maxVy, maxAbsVy;
    }

    /**
     * `ticks` of the autopilot with these modes / targets on type t (flaps up): the vertical speed (AutopilotMath.wantedVy,
     * or level flight), the throttle (СКОР), the heading (КУРС: CruiseMath.headingStep), the move. Returns the state.
     */
    static Sim fly(VehicleType t, Sim s, int modes, double hdg, double alt, double vsBpt, double setSpeed, int ticks) {
        PlaneParams p = t.plane;
        for (int i = 0; i < ticks; i++) {
            if ((modes & AutopilotMath.M_SPD) != 0) {
                double prev = Double.isNaN(s.prevSpeed) ? s.speed : s.prevSpeed;
                s.thr = AutopilotMath.speedThrottle(s.thr, s.speed, prev, setSpeed, AutopilotMath.SPD_MAX_THROTTLE);
            }
            s.prevSpeed = s.speed;
            double climb = PlaneMath.climbCapacity(p, s.speed) * FlightMath.ceilingFactor(s.y);
            double want = AutopilotMath.wantedVy(modes, s.y, alt, vsBpt, climb, p.dive);
            s.vy = Double.isNaN(want) ? PlaneMath.airVy(p, s.speed, s.vy, false, false, true, s.y) : FlightMath.approach(s.vy, want, PlaneMath.VACC);
            s.speed += PlaneMath.airAccel(t, p, s.speed, s.thr, s.vy, s.vy > 0, true, 1);
            s.speed = VehicleMath.clamp(s.speed, 0, PlaneMath.maxSpeed(t, 1));
            if ((modes & AutopilotMath.M_HDG) != 0) {
                double yaw = CruiseMath.headingStep(s.hdg - 180, hdg - 180, t.turnRate * VehicleMath.clamp(s.speed / p.stall, 0, 1));
                s.hdg = ((yaw + 180) % 360 + 360) % 360;
            }
            move(s);
        }
        return s;
    }

    private static void move(Sim s) {
        double r = Math.toRadians(s.hdg);
        s.x += Math.sin(r) * s.speed;
        s.z -= Math.cos(r) * s.speed;
        s.y += s.vy;
        s.minSpeed = Math.min(s.minSpeed, s.speed);
        s.maxSpeed = Math.max(s.maxSpeed, s.speed);
        s.maxVy = Math.max(s.maxVy, s.vy);
        s.maxAbsVy = Math.max(s.maxAbsVy, Math.abs(s.vy));
    }

    private static Sim start(double y, double hdg, double speed, double thr) {
        Sim s = new Sim();
        s.y = y;
        s.hdg = hdg;
        s.speed = speed;
        s.thr = thr;
        return s;
    }

    private static void modes() {
        VehicleType pl = VehicleType.PLANE;
        // КУРС + ВЫС (the old O key): 40 deg and 12 blocks off
        Sim a = fly(pl, start(100, 0, 1.2, 0.6), AutopilotMath.DEFAULT, 40, 112, 0, 0, 500);
        check(Math.abs(a.y - 112) < 0.3 && Math.abs(CruiseMath.wrap(a.hdg - 40)) < 0.5,
                "stage C AP: КУРС + ВЫС (key O) turns 40 deg and climbs 12 blocks onto the set heading / height (err " + f(a.y - 112) + " bl, "
                        + f(CruiseMath.wrap(a.hdg - 40)) + " deg)");
        // КУРС alone: the height left free (level flight as without keys), the heading held
        Sim b = fly(pl, start(100, 350, 1.2, 0.6), AutopilotMath.M_HDG, 20, 0, 0, 0, 400);
        check(Math.abs(CruiseMath.wrap(b.hdg - 20)) < 0.5 && Math.abs(b.y - 100) < 1.5, "stage C AP: КУРС alone turns the short way across north (350 -> 20) and"
                + " holds it; no vertical mode - level flight (" + f(b.y - 100) + " bl)");
        // ВЕРТ alone: -1.5 blocks/s held
        Sim c = fly(pl, start(150, 90, 1.2, 0.6), AutopilotMath.M_VS, 0, 0, -1.5 / 20, 0, 200);
        check(Math.abs(c.vy - -0.075) < 1e-3 && c.y < 150 - 12, "stage C AP: ВЕРТ alone holds -1,5 m/s (vy " + f(c.vy * 20) + " m/s, "
                + f(150 - c.y) + " blocks down in 10 s)");
        // ВЫС + ВЕРТ: up to the height no faster than the set 1 block/s, then held
        Sim d = fly(pl, start(100, 90, 1.4, 0.7), AutopilotMath.M_ALT | AutopilotMath.M_VS, 0, 115, 1.0 / 20, 0, 600);
        check(d.maxVy <= 0.05 + 1e-9 && Math.abs(d.y - 115) < 0.3, "stage C AP: ВЫС + ВЕРТ climbs to the set height at the set 1 m/s at most (max "
                + f(d.maxVy * 20) + " m/s) and holds it (err " + f(d.y - 115) + ")");
        // the jet the same way
        Sim j = fly(VehicleType.JET, start(120, 180, 2.0, 0.7), AutopilotMath.DEFAULT, 230, 108, 0, 0, 500);
        check(Math.abs(j.y - 108) < 0.3 && Math.abs(CruiseMath.wrap(j.hdg - 230)) < 0.5, "stage C AP: the jet's КУРС + ВЫС (err " + f(j.y - 108) + " bl, "
                + f(CruiseMath.wrap(j.hdg - 230)) + " deg)");
        // the vertical law's pieces
        check(Double.isNaN(AutopilotMath.wantedVy(0, 100, 120, 0.05, 0.2, 0.2))
                        && Double.isNaN(AutopilotMath.wantedVy(AutopilotMath.M_HDG | AutopilotMath.M_SPD, 100, 120, 0.05, 0.2, 0.2))
                        && AutopilotMath.wantedVy(AutopilotMath.M_VS, 100, 0, 0.3, 0.1, 0.2) == 0.1
                        && AutopilotMath.wantedVy(AutopilotMath.M_VS, 100, 0, -0.3, 0.1, 0.2) == -0.2
                        && AutopilotMath.wantedVy(AutopilotMath.M_ALT, 100, 100.1, 0.3, 0.1, 0.2) == 0,
                "stage C AP: no vertical mode = level flight; ВЕРТ within the climb the airspeed allows / the dive; ВЫС's dead band");
    }

    private static void speed() {
        // the light plane flies 36 (stall) .. 80 km/h (its top at full throttle; faster only diving): СКОР 45 -> 70 and 78 -> 55
        VehicleType pl = VehicleType.PLANE;
        double set = CruiseMath.fromKmh(70);
        Sim s = fly(pl, start(120, 90, CruiseMath.fromKmh(45), 0.3), AutopilotMath.M_SPD | AutopilotMath.M_ALT, 0, 120, 0, set, 800);
        double over = VehicleMath.kmh(s.maxSpeed) - 70;
        check(Math.abs(s.speed - set) < CruiseMath.fromKmh(1.5) && over < 4 && s.thr <= AutopilotMath.SPD_MAX_THROTTLE + 1e-9,
                "stage C AP: СКОР 45 -> 70 km/h with the throttle (now " + VehicleMath.kmh(s.speed) + " km/h, overshoot " + over + " km/h, throttle "
                        + f(s.thr) + ")");
        Sim d = fly(pl, start(120, 90, CruiseMath.fromKmh(78), 0.9), AutopilotMath.M_SPD | AutopilotMath.M_ALT, 0, 120, 0, CruiseMath.fromKmh(55), 900);
        check(Math.abs(VehicleMath.kmh(d.speed) - 55) <= 1.5 && d.minSpeed > CruiseMath.fromKmh(50), "stage C AP: СКОР 78 -> 55 km/h by closing the"
                + " throttle, no dip under 50 (now " + VehicleMath.kmh(d.speed) + ", min " + VehicleMath.kmh(d.minSpeed) + ")");
        Sim j = fly(VehicleType.JET, start(140, 90, CruiseMath.fromKmh(80), 0.5), AutopilotMath.M_SPD | AutopilotMath.M_ALT, 0, 140, 0,
                CruiseMath.fromKmh(110), 1200);
        check(Math.abs(VehicleMath.kmh(j.speed) - 110) <= 2 && j.thr < EntityAfterburner.LIMIT, "stage C AP: the jet's СКОР 80 -> 110 km/h, the throttle kept"
                + " out of the afterburner (" + VehicleMath.kmh(j.speed) + " km/h, throttle " + f(j.thr) + ")");
        check(AutopilotMath.speedThrottle(0.5, 1, 1, 2, 0.89) == 0.5 + AutopilotMath.THR_RATE && AutopilotMath.speedThrottle(0.88, 1, 1, 2, 0.89) == 0.89
                        && AutopilotMath.speedThrottle(0.01, 2, 2, 1, 0.89) == 0,
                "stage C AP: СКОР moves the throttle at most THR_RATE a tick, within 0..0.89");
    }

    /** The afterburner's throttle (EntityVehicleSVA.AFTERBURNER_THROTTLE without loading the entity class's world bits). */
    private static final class EntityAfterburner {
        static final double LIMIT = com.sva.entity.EntityVehicleSVA.AFTERBURNER_THROTTLE;
    }

    /** ЗАХВ from (x, z, y) heading hdg over r: {aligned tick, final cross, final heading error, along at let-go, y over the strip at let-go}. */
    static double[] captureSim(VehicleType t, RunwayMath.Runway r, double x, double z, double y, double hdg, double speed, int ticks) {
        PlaneParams p = t.plane;
        Sim s = start(y, hdg, speed, 0.5);
        s.x = x;
        s.z = z;
        double aligned = -1, along = Double.NaN, over = Double.NaN;
        AutopilotMath.Capture c = null;
        for (int i = 0; i < ticks; i++) {
            c = AutopilotMath.capture(r, s.x, s.y, s.z, s.hdg, AutopilotMath.DESCENT, s.speed);
            if (c.done) {
                along = c.along;
                over = s.y - r.y;
                break;
            }
            if (c.aligned && aligned < 0) {
                aligned = i;
            }
            double climb = PlaneMath.climbCapacity(p, s.speed) * FlightMath.ceilingFactor(s.y);
            double want = c.aligned ? VehicleMath.clamp(c.vy, -p.dive, climb) : AutopilotMath.wantedVy(AutopilotMath.M_ALT, s.y, y, 0, climb, p.dive);
            s.vy = FlightMath.approach(s.vy, want, PlaneMath.VACC);
            if (!c.out) {
                double yaw = CruiseMath.headingStep(s.hdg - 180, c.heading - 180, t.turnRate * VehicleMath.clamp(s.speed / p.stall, 0, 1));
                s.hdg = ((yaw + 180) % 360 + 360) % 360;
            }
            move(s);
        }
        return new double[] {aligned, c == null ? 99 : c.cross, c == null ? 99 : CruiseMath.wrap(s.hdg - c.course), along, over};
    }

    private static void capture() {
        // a runway 80 x 5 along X at z 0.5 (blocks z -2..2), surface Y 64
        RunwayMath.Runway rx = new RunwayMath.Runway(true, 0.5, 0, 80, 64, 400, 5);
        double[] a = captureSim(VehicleType.PLANE, rx, -110, 9.5, 80, 70, 1.0, 600);
        check(a[0] >= 0 && Math.abs(a[1]) < 1 && Math.abs(a[2]) < 3 && !Double.isNaN(a[3]) && a[3] > -80 && a[3] < 80 && a[4] < AutopilotMath.OFF_HEIGHT,
                "stage C AP: ЗАХВ - from 9 blocks off the line at 20 deg, 110 out, the light plane is aligned after " + (int) a[0]
                        + " ticks, flies the glide slope and lets go " + f(a[4]) + " blocks over the strip, " + f(a[3])
                        + " blocks from the threshold (cross " + f(a[1]) + ", heading " + f(a[2]) + " deg)");
        // along Z, landing north (heading 0 -> threshold at the strip's +Z end), from the other side
        RunwayMath.Runway rz = new RunwayMath.Runway(false, 10.5, -40, 60, 70, 300, 3);
        double[] b = captureSim(VehicleType.JET, rz, 2.5, 200, 90, 330, 1.3, 600);
        check(b[0] >= 0 && Math.abs(b[1]) < 1 && Math.abs(b[2]) < 3 && !Double.isNaN(b[3]),
                "stage C AP: ЗАХВ - the jet onto a north-south strip, landing north from 8 blocks west (aligned after " + (int) b[0] + " ticks, cross "
                        + f(b[1]) + ")");
        // the guidance's signs and limits
        AutopilotMath.Capture r1 = AutopilotMath.capture(rx, -50, 80, 5.5, 90, 0.1, 0), l1 = AutopilotMath.capture(rx, -50, 80, -4.5, 90, 0.1, 0);
        AutopilotMath.Capture w = AutopilotMath.capture(rx, 130, 80, 0.5, 270, 0.1, 0), far = AutopilotMath.capture(rx, 120, 80, 0.5, 90, 0.1, 0);
        check(r1.course == 90 && r1.cross > 0 && r1.heading < 90 && l1.cross < 0 && l1.heading > 90 && w.course == 270 && w.along == -50
                        && far.out && Math.abs(AutopilotMath.capture(rx, -500, 80, 60, 90, 0.1, 0).heading - (90 - AutopilotMath.MAX_INTERCEPT)) < 1e-9,
                "stage C AP: ЗАХВ - right of the line turns left, left turns right, the course nearest the heading (east 90 / west 270), at most a "
                        + (int) AutopilotMath.MAX_INTERCEPT + " deg intercept, nothing past the far end");
        AutopilotMath.Capture hi = AutopilotMath.capture(rx, -50, 90, 0.5, 90, 0.1, 0), lo = AutopilotMath.capture(rx, -50, 69, 0.5, 90, 0.1, 0);
        check(hi.aligned && hi.vy == -0.1 && lo.vy == 0 && !lo.done && AutopilotMath.capture(rx, 10, 66, 0.5, 90, 0.1, 0).done
                        && AutopilotMath.descent(AutopilotMath.M_VS, -0.05) == 0.05 && AutopilotMath.descent(0, -0.05) == AutopilotMath.DESCENT,
                "stage C AP: ЗАХВ - over the glide slope it descends at the set rate, under it level, lets go under "
                        + (int) AutopilotMath.OFF_HEIGHT + " blocks over the strip");
    }

    // ---------------------------------------------------------------- the knobs, the synced word

    private static void knobs() {
        check(AutopilotMath.adjustHeading(355, 1) == 0 && AutopilotMath.adjustHeading(2, -1) == 355 && AutopilotMath.adjustHeading(268, 5) == 295
                        && AutopilotMath.adjustAlt(254, 1, 254) == 254 && AutopilotMath.adjustAlt(3, -1, 254) == 1 && AutopilotMath.adjustKmh(23, -2, 160) == 20
                        && AutopilotMath.adjustKmh(158, 3, 160) == 160 && AutopilotMath.adjustVs(9.8, 2) == 10 && AutopilotMath.adjustVs(0.2, -3) == -1.5,
                "stage C AP: knobs - heading 5 deg steps round north, height 5 within 1..ceiling, speed 5 km/h within 20..top, vertical 0,5 m/s within +-10");
        AutopilotMath.Panel p = new AutopilotMath.Panel();
        p.on = true;
        p.modes = AutopilotMath.M_HDG | AutopilotMath.M_CAP;
        p.runway = true;
        p.hdg = 268;
        p.alt = 1000;
        p.kmh = 90;
        p.vs = -1.5;
        AutopilotMath.Panel q = AutopilotMath.unpack(AutopilotMath.pack(p), AutopilotMath.pack2(p));
        check(q.on && q.modes == p.modes && q.runway && !q.aligned && q.hdg == 268 && q.alt == 1000 && q.kmh == 90 && Math.abs(q.vs + 1.5) < 1e-9
                        && AutopilotMath.bit(AutopilotMath.G_AP) == 0 && AutopilotMath.bit(AutopilotMath.G_CAP) == AutopilotMath.M_CAP
                        && AutopilotMath.DEFAULT == (AutopilotMath.M_HDG | AutopilotMath.M_ALT),
                "stage C AP: the synced panel word round trip (modes, runway, heading, height, speed, vertical speed); O = КУРС + ВЫС");
    }

    // ---------------------------------------------------------------- the runway finder

    /** A fake world: a map of runway blocks (x, z) -> y; the ground at 60 elsewhere; columns past `unloaded` x are not loaded. */
    private static RunwayMath.World grid(final Map<Long, Integer> blocks, final int unloaded) {
        return new RunwayMath.World() {
            @Override
            public int top(int x, int z) {
                if (x >= unloaded) {
                    return -1;
                }
                Integer y = blocks.get(Long.valueOf((long) x << 32 | (z & 0xFFFFFFFFL)));
                return y == null ? 60 : y.intValue();
            }

            @Override
            public boolean runway(int x, int y, int z) {
                Integer h = blocks.get(Long.valueOf((long) x << 32 | (z & 0xFFFFFFFFL)));
                return h != null && h.intValue() == y;
            }
        };
    }

    private static void strip(Map<Long, Integer> m, int x0, int z0, int lx, int lz, int y) {
        for (int x = x0; x < x0 + lx; x++) {
            for (int z = z0; z < z0 + lz; z++) {
                m.put(Long.valueOf((long) x << 32 | (z & 0xFFFFFFFFL)), Integer.valueOf(y));
            }
        }
    }

    private static void runways() {
        Map<Long, Integer> m = new HashMap<Long, Integer>();
        strip(m, 20, -3, 60, 5, 70);                                 // 60 x 5 along X, 20 blocks east
        strip(m, -70, 40, 3, 30, 64);                                // 3 x 30 along Z, farther
        strip(m, 5, 30, 8, 2, 64);                                   // too short (8)
        RunwayMath.Runway r = RunwayMath.find(grid(m, 1000), 0, 0);
        check(r != null && r.alongX && r.c == -0.5 && r.a0 == 20 && r.a1 == 80 && r.y == 71 && r.width == 5 && r.blocks == 300,
                "stage C runway: the nearest strip - 60 x 5 along X, centre line z -0.5, x 20..80, surface 71 (" + (r == null ? "none"
                        : r.alongX + " " + r.c + " " + r.a0 + ".." + r.a1 + " y " + r.y + " w " + r.width + " n " + r.blocks) + ")");
        RunwayMath.Runway r2 = RunwayMath.find(grid(m, 1000), -70, 60);
        check(r2 != null && !r2.alongX && r2.c == -68.5 && r2.a0 == 40 && r2.a1 == 70 && r2.width == 3, "stage C runway: from the other one's side the"
                + " north-south strip 3 x 30 is nearer (centre x -68.5, z 40..70)");
        Map<Long, Integer> one = new HashMap<Long, Integer>();
        strip(one, -30, 11, 40, 1, 64);                              // one block wide, at an odd z
        RunwayMath.Runway r3 = RunwayMath.find(grid(one, 1000), 0, 0);
        check(r3 != null && r3.width == 1 && r3.length() == 40, "stage C runway: a strip one block wide is found (the checkerboard scan hits it along its length)");
        check(RunwayMath.find(grid(m, 15), 0, 0) == null || RunwayMath.find(grid(m, 15), 0, 0).alongX == false,
                "stage C runway: columns in unloaded chunks are not read (the eastern strip past x 15 is unseen)");
        check(RunwayMath.find(grid(new HashMap<Long, Integer>(), 1000), 0, 0) == null && rx(r).distance(50, 0) == 0
                        && Math.abs(rx(r).distance(10, -0.5) - 10) < 1e-9,
                "stage C runway: none on bare ground; the distance to a strip is 0 over it");
    }

    private static RunwayMath.Runway rx(RunwayMath.Runway r) {
        return r == null ? new RunwayMath.Runway(true, 0, 0, 1, 0, 0, 1) : r;
    }

    // ---------------------------------------------------------------- the HSI

    private static void hsi() {
        check(AutopilotMath.bearing(0, 0, 0, -10) == 0 && AutopilotMath.bearing(0, 0, 10, 0) == 90 && AutopilotMath.bearing(0, 0, 0, 10) == 180
                        && AutopilotMath.bearing(0, 0, -10, 0) == 270 && Math.abs(AutopilotMath.bearing(5, 5, 15, -5) - 45) < 1e-9
                        && AutopilotMath.distance(0, 0, 3, -4) == 5,
                "stage C HSI: bearing as a compass (north -Z 0, east +X 90, south 180, west 270), distance");
        check(AutopilotMath.cardAngle(268, 268) == 0 && AutopilotMath.cardAngle(0, 268) == 92 && AutopilotMath.cardAngle(250, 268) == -18
                        && CruiseMath.heading(-90) == 90,
                "stage C HSI: the card turns with the heading - the heading on top, a direction to the right clockwise; Minecraft yaw -90 = east");
        check(AutopilotMath.cdiDots(4) == -1 && AutopilotMath.cdiDots(-2) == 0.5 && AutopilotMath.cdiDots(40) == -AutopilotMath.DOTS
                        && "3,8".equals(AutopilotMath.km(3800)) && "0,4".equals(AutopilotMath.km(420)) && "12,0".equals(AutopilotMath.km(11990)),
                "stage C HSI: right of the line the bar stands left (one dot = " + (int) AutopilotMath.DOT_BLOCKS + " blocks, at most 2); km with a comma");
    }

    // ---------------------------------------------------------------- the annunciators

    private static void lamps() throws java.io.IOException {
        int[] pl = CockpitLamps.annunciators(VehicleType.PLANE), jet = CockpitLamps.annunciators(VehicleType.JET), tilt = CockpitLamps.annunciators(VehicleType.TILT);
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        boolean keys = ru != null && en != null;
        for (int r : pl) {
            keys &= r < CockpitLamps.KEYS.length && ru.contains("sva.cockpit.lamp." + CockpitLamps.KEYS[r]) && en.contains("sva.cockpit.lamp." + CockpitLamps.KEYS[r]);
        }
        check(pl.length == 5 && pl[0] == CockpitLamps.FLAPS_EXT && pl[1] == CockpitLamps.SLATS && pl[2] == CockpitLamps.AIRBRAKE
                        && pl[3] == CockpitLamps.FLAP_LIMIT && pl[4] == CockpitLamps.STALL && jet.length == 5 && tilt.length == 3
                        && CockpitLamps.annunciators(VehicleType.HELI).length == 0 && CockpitLamps.annunciators(VehicleType.BUGGY).length == 0 && keys,
                "stage C lamps: the annunciator column ЗАКР. ВЫП / ПРЕДКР. / ВОЗД. ТОРМ / V>N ЗАКР / СВАЛ. (plane, jet; the tiltrotor 3), labelled ru + en");
        CockpitLamps.Snapshot s = new CockpitLamps.Snapshot();
        s.powered = true;
        boolean off = CockpitLamps.state(CockpitLamps.FLAPS_EXT, s) == CockpitLamps.OFF && CockpitLamps.state(CockpitLamps.FLAP_LIMIT, s) == CockpitLamps.OFF;
        s.flaps = 2;
        s.slatsOut = true;
        s.airbrake = true;
        boolean on = CockpitLamps.state(CockpitLamps.FLAPS_EXT, s) == CockpitLamps.ON && CockpitLamps.state(CockpitLamps.SLATS, s) == CockpitLamps.ON
                && CockpitLamps.state(CockpitLamps.AIRBRAKE, s) == CockpitLamps.ON;
        s.flapsMoving = true;
        s.flapWarning = true;
        boolean blink = CockpitLamps.state(CockpitLamps.FLAPS_EXT, s) == CockpitLamps.BLINK && CockpitLamps.state(CockpitLamps.FLAP_LIMIT, s) == CockpitLamps.BLINK;
        s.powered = false;
        check(off && on && blink && CockpitLamps.state(CockpitLamps.SLATS, s) == CockpitLamps.OFF && CockpitLamps.colour(CockpitLamps.FLAPS_EXT) == CockpitLamps.GREEN
                        && CockpitLamps.colour(CockpitLamps.SLATS) == CockpitLamps.BLUE && CockpitLamps.colour(CockpitLamps.AIRBRAKE) == CockpitLamps.AMBER
                        && CockpitLamps.colour(CockpitLamps.FLAP_LIMIT) == CockpitLamps.RED,
                "stage C lamps: flaps out green (blinking while they travel), slats blue, airbrake amber, the overspeed red blinking; dark unpowered");
    }

    // ---------------------------------------------------------------- the light plane's panels

    private static void layout() throws java.io.IOException {
        CockpitLayout l = CockpitLayout.of(VehicleType.PLANE);
        CockpitLayout.Screen ap = l.panel("apanel"), hsi = l.panel("hsi"), fl = l.panel("flapind"), tr = l.panel("trimind"), gate = l.panel("flapgate");
        boolean all = ap != null && hsi != null && fl != null && tr != null && gate != null && l.panels.size() == 5;
        check(all, "stage C layout: the light plane's 5 panels found (AP panel, HSI, flap indicator, trim indicator, flap gate): " + l.panels.size());
        if (!all) {
            return;
        }
        CockpitLayout.Screen att = null;
        for (CockpitLayout.Screen s : l.screens) {
            if (Math.abs(s.w / s.h - 1) < 0.2F && s.w >= CockpitDials.ATT_MIN) {
                att = s;
            }
        }
        boolean over = true;
        for (CockpitLayout.Dial d : l.dials) {
            over &= ap.c[1] > d.c[1] + d.r;
        }
        check(att != null && Math.abs(hsi.c[2] - att.c[2]) < 0.01F && hsi.c[1] < att.c[1] - 0.1F && Math.abs(hsi.w - hsi.h) < 0.005F && hsi.w > 0.1F
                        && over && ap.w > 0.5F && ap.w / ap.h > 7 && fl.c[2] > 0.2F && tr.c[1] < fl.c[1] && tr.c[0] < fl.c[0] - 0.1F && tr.h > 2 * tr.w
                        && gate.n[1] > 0.7F && gate.up[0] > 0.9F,
                "stage C layout: the HSI square under the attitude screen (the old heading dial's place), the AP panel over the six-pack, the flap"
                        + " indicator right of it (inside the fuselage), the trim strip on the pedestal by its wheel, the gate beside the flap lever (up = forward)");
        ViewCheckSVA.Mesh m = ViewCheckSVA.load(VehicleType.PLANE.model);
        StringBuilder bad = new StringBuilder();
        int n = 0;
        double worst = 0;
        if (m != null) {
            for (CockpitCheckSVA.Inst i : CockpitCheckSVA.instruments(VehicleType.PLANE, m)) {
                if (i.what.startsWith("panel")) {
                    n++;
                    worst = Math.max(worst, i.blocked);
                    if (!i.ok()) {
                        bad.append(' ').append(i);
                    }
                }
            }
        }
        check(n == 5 && bad.length() == 0, "stage C layout: every panel readable from the pilot's camera (hidden at most " + Math.round(worst * 100)
                + " %, facing, in view)" + bad);
        List<CockpitLayout.Screen> js = CockpitLayout.of(VehicleType.JET).screens;
        int[] jr = CockpitDials.screenRoles(js, true, false, true), jo = CockpitDials.screenRoles(js, true, false, false);
        int hsiN = 0, small = 0;
        for (int i = 0; i < jr.length; i++) {
            hsiN += jr[i] == CockpitDials.SCREEN_HSI ? 1 : 0;
            small += jo[i] == CockpitDials.SCREEN_SMALL ? 1 : 0;
        }
        check(hsiN == 1 && small == 1 && contains(jr, CockpitDials.SCREEN_MAIN) && contains(jr, CockpitDials.SCREEN_ATTITUDE),
                "stage C layout: the jet's small side screen becomes its HSI (the main text and attitude screens stay)");
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the flap lever, the trim wheel, the trim

    private static void controls() {
        AnimParts.Def lever = null, wheel = null;
        for (AnimParts.Def d : AnimParts.of("plane")) {
            if ("flapLever".equals(d.name)) {
                lever = d;
            } else if ("trimWheel".equals(d.name)) {
                wheel = d;
            }
        }
        check(lever != null && wheel != null && lever.kind == AnimParts.CONTROL && lever.in1 == AnimParts.FLAPS && wheel.in1 == AnimParts.TRIM
                        && lever.pivot == AnimParts.FLAP_LEVER_PIVOT && lever.ang1 * 2 == AnimParts.FLAP_LEVER_SWING,
                "stage C controls: the plane's flap lever moves with the flap position, the trim wheel with the trim");
        AnimMath.State s = new AnimMath.State();
        s.flapLast = 3;
        s.flapPos = s.prevFlapPos = 0;
        float a = s.control(AnimParts.FLAPS, 1F);
        s.flapPos = s.prevFlapPos = 3;
        float b = s.control(AnimParts.FLAPS, 1F);
        s.flapPos = s.prevFlapPos = 1.5F;
        float c = s.control(AnimParts.FLAPS, 1F);
        double fwd0 = AnimParts.FLAP_LEVER_FWD, fwdL = AnimParts.FLAP_LEVER_FWD - AnimParts.FLAP_LEVER_SWING;
        check(a == -1F && b == 1F && c == 0F && fwd0 == 12 && fwdL == -32, "stage C controls: the lever input -1 retracted .. 1 at the last setting"
                + " (12 deg forward .. 32 deg back, modelled at the middle)");
        AnimMath.State t = new AnimMath.State();
        for (int i = 0; i < 400; i++) {
            AnimMath.trim(t, 5F, 0F, true);
        }
        float climb = t.trim;
        for (int i = 0; i < 400; i++) {
            AnimMath.trim(t, 0F, 2F, true);
        }
        float flaps = t.trim;
        AnimMath.trim(t, -20F, 0F, false);
        t.prevTrim = t.trim;
        check(Math.abs(climb - AnimMath.TRIM_CLIMB) < 0.05F && Math.abs(flaps - 2 * AnimMath.TRIM_FLAP) < 0.05F && t.trim == -AnimMath.TRIM_CLIMB
                        && Math.abs(t.control(AnimParts.TRIM, 1F) + AnimMath.TRIM_CLIMB / AnimMath.TRIM_MAX) < 1e-6,
                "stage C trim: nose up in a climb (" + f(climb) + "), with the flaps (" + f(flaps) + "), down in a dive; the wheel follows");
    }

    private static void function() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        boolean lang = ru != null && en != null;
        for (String k : AutopilotMath.GROUP_KEYS) {
            lang &= ru.contains("sva.ap.group." + k) && en.contains("sva.ap.group." + k) && ru.contains("sva.ap.tip." + k) && en.contains("sva.ap.tip." + k);
        }
        for (int i = 0; i < 4; i++) {
            lang &= ru.contains("sva.hsi.card." + i) && en.contains("sva.hsi.card." + i);
        }
        lang &= ru.contains("sva.hud.cruise.why." + CruiseMath.OFF_LANDING) && en.contains("sva.hud.cruise.why." + CruiseMath.OFF_LANDING);
        check(VehicleFn.AP_PANEL.appliesTo(VehicleType.PLANE) && VehicleFn.AP_PANEL.appliesTo(VehicleType.JET) && VehicleFn.AP_PANEL.appliesTo(VehicleType.TILT)
                        && !VehicleFn.AP_PANEL.appliesTo(VehicleType.HELI) && !VehicleFn.AP_PANEL.appliesTo(VehicleType.BUGGY)
                        && VehicleFn.AP_PANEL.action(VehicleType.PLANE) == -1 && !VehicleFn.AP_PANEL.driverOnly && VehicleFn.AP_PANEL.defaultKey == 23 && lang,
                "stage C: «Панель автопилота» (I) on the plane, jet, tiltrotor - a screen, a passenger may look; the groups, tips, card letters, the"
                        + " landing let-go translated");
    }
}
