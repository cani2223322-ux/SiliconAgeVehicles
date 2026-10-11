package com.sva.debug;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.AnimParts;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.RunwayMath;
import com.sva.vehicle.VehicleLights;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

/**
 * Aircraft round 2, stage D1 self-tests (★1 PAPI, ★2 approach / runway lights and the threshold designator, ★14 the
 * retractable landing lights; mock-up docs/mockups/veh_air3_1_airfield.png) - pure logic on the dedicated server: the PAPI
 * colours per angle and unit, the row finder on a fake grid, the designator from the heading (both ends, the rounding),
 * the sequenced flasher's timing, the centre-line colours, the lights' on rule, ЗАХВ's ВПП -> ГЛИС -> ВЫРАВН in a
 * simulation through the light plane's PlaneMath with a PAPI (and unchanged without one), the synced phase, the landing
 * light's extension rule and its model row.
 */
final class SelfTestAirD1SVA {

    private SelfTestAirD1SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }

    static void run() {
        papi();
        papiFinder();
        designator();
        flasher();
        marks();
        lightsOn();
        glideSim();
        lamp();
    }

    // ---------------------------------------------------------------- PAPI

    private static void papi() {
        // nominal 3: units (nearest the runway first) 3.5 / 3.2 / 2.8 / 2.5
        int[] w3 = {AirfieldMath.whites(3.7, 3), AirfieldMath.whites(3.35, 3), AirfieldMath.whites(3.0, 3), AirfieldMath.whites(2.65, 3),
                AirfieldMath.whites(2.3, 3)};
        check(w3[0] == 4 && w3[1] == 3 && w3[2] == 2 && w3[3] == 1 && w3[4] == 0,
                "D1 PAPI: 3 deg - 3.7 / 3.35 / 3.0 / 2.65 / 2.3 deg show 4 / 3 / 2 / 1 / 0 white (" + w3[0] + w3[1] + w3[2] + w3[3] + w3[4] + ")");
        check(AirfieldMath.white(3.0, 3, 3) && AirfieldMath.white(3.0, 3, 2) && !AirfieldMath.white(3.0, 3, 1) && !AirfieldMath.white(3.0, 3, 0)
                        && AirfieldMath.white(2.6, 3, 3) && !AirfieldMath.white(2.6, 3, 2) && AirfieldMath.white(3.6, 3, 0),
                "D1 PAPI: on the slope the two outer units are white, the two inner red; the outermost turns red last, the innermost white last");
        check(AirfieldMath.whites(4.0, 4) == 2 && AirfieldMath.whites(5.0, 5) == 2 && AirfieldMath.whites(4.0, 5) == 0 && AirfieldMath.whites(5.0, 3) == 4
                        && AirfieldMath.signal(2) == 0 && AirfieldMath.signal(0) == -2,
                "D1 PAPI: a 4 / 5 deg row shows 2 + 2 at 4 / 5 deg; 4 deg on a 5 deg row is all red, 5 deg on a 3 deg row all white");
        check(AirfieldMath.nominal(0) == 3 && AirfieldMath.nominal(1) == 4 && AirfieldMath.nominal(2) == 5 && AirfieldMath.nominal(3 | 8) == 3
                        && AirfieldMath.nextNominal(0) == 1 && AirfieldMath.nextNominal(1) == 2 && AirfieldMath.nextNominal(2 | 4) == 0
                        && AirfieldMath.unitIndex(7) == 3 && AirfieldMath.unitIndex(-1) == 0,
                "D1 PAPI: metadata 0 / 1 / 2 = 3 / 4 / 5 deg, RMB cycles 3 -> 4 -> 5 -> 3 (the facing bits kept), a unit's index 0..3");
        check(Math.abs(AirfieldMath.angle(Math.tan(Math.toRadians(3)) * 100, 100) - 3) < 1e-9 && AirfieldMath.angle(-1, 10) < 0,
                "D1 PAPI: the viewing angle is the elevation over the lens");
        // a row over a runway: ahead / behind, the slope's height
        RunwayMath.Runway r = new RunwayMath.Runway(true, 0.5, 0, 100, 64, 500, 5);
        AirfieldMath.Papi p = new AirfieldMath.Papi(1, 15.5, -5.0, 64.42, 3, 4);
        double slope = p.slopeY(r, -84.5, -5.0);
        check(p.ahead(r, -50, 0.5) && !p.ahead(r, 30, 0.5) && Math.abs(slope - (64.42 + Math.tan(Math.toRadians(3)) * 100)) < 1e-9
                        && AirfieldMath.whites(p.angle(r, -84.5, slope, -5.0), 3) == 2 && Math.abs(p.fromThreshold(r) - 15.5) < 1e-9,
                "D1 PAPI: seen only from the approach side; on its slope (100 blocks out " + f(slope - 64) + " over the strip) it shows 2 + 2");
    }

    /** A fake world for findPapi: PAPI units at (x, y, z) -> metadata. */
    private static AirfieldMath.Blocks units(final Map<String, Integer> u) {
        return new AirfieldMath.Blocks() {
            @Override
            public int papi(int x, int y, int z) {
                Integer m = u.get(x + "," + y + "," + z);
                return m == null ? -1 : m.intValue();
            }
        };
    }

    private static void papiFinder() {
        // a strip along X: blocks x 0..119, z -2..2 at y 63 (surface 64); 4 units at x 15 on the left (z -4..-7, y 64)
        RunwayMath.Runway r = new RunwayMath.Runway(true, 0.5, 0, 120, 64, 600, 5);
        Map<String, Integer> u = new HashMap<String, Integer>();
        for (int z = -7; z <= -4; z++) {
            u.put("15,64," + z, Integer.valueOf(1));
        }
        // two units at the far end (x 104, right side z 4..5) serve the other direction
        u.put("104,64,4", Integer.valueOf(0));
        u.put("104,64,6", Integer.valueOf(0));
        r.withPapi(units(u));
        AirfieldMath.Papi a = r.papi(1), b = r.papi(-1);
        check(a != null && a.units == 4 && a.nominal == 4 && Math.abs(a.along - 15.5) < 1e-9 && Math.abs(a.cross - (-5.0)) < 1e-9
                        && Math.abs(a.y - (64 + AirfieldMath.LENS_Y)) < 1e-9 && b != null && b.units == 2 && b.dir == -1 && Math.abs(b.fromThreshold(r) - 15.5) < 1e-9,
                "D1 PAPI finder: the 4-unit row by the west threshold (4 deg, x 15.5, z -5) and a 2-unit row by the east one, each serving its own end");
        Map<String, Integer> none = new HashMap<String, Integer>();
        none.put("55,64,-4", Integer.valueOf(0));                          // a single unit in the west half: the west end's
        none.put("15,70,-4", Integer.valueOf(0));                          // too high over the surface
        none.put("15,64,-20", Integer.valueOf(0));                         // too far out
        RunwayMath.Runway q = new RunwayMath.Runway(true, 0.5, 0, 120, 64, 600, 5).withPapi(units(none));
        check(q.papi(1) != null && q.papi(1).units == 1 && Math.abs(q.papi(1).along - 55.5) < 1e-9 && q.papi(-1) == null,
                "D1 PAPI finder: only units beside the strip within " + AirfieldMath.PAPI_BAND + " blocks and a level or so of its surface count");
        // along Z: a north-south strip, units on the east side by the north threshold
        RunwayMath.Runway z = new RunwayMath.Runway(false, 10.5, -50, 50, 70, 300, 3);
        Map<String, Integer> uz = new HashMap<String, Integer>();
        for (int x = 13; x <= 16; x++) {
            uz.put(x + ",70,-40", Integer.valueOf(2));
        }
        z.withPapi(units(uz));
        check(z.papi(1) != null && z.papi(1).units == 4 && z.papi(1).nominal == 5 && Math.abs(z.papi(1).along - (-39.5)) < 1e-9 && z.papi(-1) == null,
                "D1 PAPI finder: a north-south strip's row (5 deg) by its north threshold, landing south");
    }

    // ---------------------------------------------------------------- the designator

    private static void designator() {
        check("27".equals(AirfieldMath.designator(268)) && "09".equals(AirfieldMath.designator(88)) && "36".equals(AirfieldMath.designator(0))
                        && "36".equals(AirfieldMath.designator(4)) && "36".equals(AirfieldMath.designator(355)) && "01".equals(AirfieldMath.designator(5))
                        && "18".equals(AirfieldMath.designator(180)) && "35".equals(AirfieldMath.designator(354)) && "04".equals(AirfieldMath.designator(-320)),
                "D1 designator: 268 -> 27, 88 -> 09, 0 / 4 / 355 -> 36, 5 -> 01, 180 -> 18, 354 -> 35, -320 -> 04 (course / 10 rounded, 0 = 36)");
        RunwayMath.Runway x = new RunwayMath.Runway(true, 0.5, 0, 80, 64, 400, 5), z = new RunwayMath.Runway(false, 0.5, 0, 80, 64, 400, 5);
        check("09".equals(AirfieldMath.designator(x, 1)) && "27".equals(AirfieldMath.designator(x, -1)) && "18".equals(AirfieldMath.designator(z, 1))
                        && "36".equals(AirfieldMath.designator(z, -1)),
                "D1 designator: an east-west strip is 09 at its west end / 27 at its east end, a north-south one 36 / 18");
        List<double[]> cells = AirfieldMath.numberCells("27", 5);
        int ones = 0;
        for (char c : "27".toCharArray()) {
            for (String row : AirfieldMath.DIGITS[c - '0']) {
                for (char k : row.toCharArray()) {
                    ones += k == '1' ? 1 : 0;
                }
            }
        }
        boolean inside = true, distinct = true;
        double vMin = 1e9;
        for (double[] c : cells) {
            inside &= c[0] >= -2.5 && c[2] <= 2.5 && c[1] > AirfieldMath.ZEBRA_V0 + AirfieldMath.ZEBRA_LEN;
            vMin = Math.min(vMin, c[1]);
        }
        for (int i = 0; i < 10; i++) {
            for (int j = i + 1; j < 10; j++) {
                distinct &= !java.util.Arrays.equals(AirfieldMath.DIGITS[i], AirfieldMath.DIGITS[j]);
            }
        }
        List<Double> zebra = AirfieldMath.zebra(5);
        boolean zok = zebra.size() >= 4;
        for (Double u : zebra) {
            zok &= Math.abs(u.doubleValue()) >= 0.4 && Math.abs(u.doubleValue()) + AirfieldMath.STRIPE_W / 2 <= 2.5;
        }
        // the local frame: right of the landing direction; landing east (+X) right is +Z, landing south (+Z) right is -X
        double[] e = AirfieldMath.world(x, 1, 1, 2), wst = AirfieldMath.world(x, -1, 1, 2), s = AirfieldMath.world(z, 1, 1, 2);
        check(cells.size() == ones && inside && distinct && zok && e[0] == 2 && e[1] == 1.5 && wst[0] == 78 && wst[1] == -0.5 && s[0] == -0.5 && s[1] == 2,
                "D1 designator: «27» painted as " + ones + " cells inside a 5-wide strip after the «зебра» (" + zebra.size() + " stripes clear of the centre"
                        + " line), digits all different, the strip's local frame (right of the landing direction)");
    }

    // ---------------------------------------------------------------- the flasher

    private static void flasher() {
        // poles every 4 blocks 4..32 before the threshold: each flashes once a run (2 a second), the far ones first
        boolean once = true, order = true, period = true;
        double prev = -1;
        for (int d = 32; d >= 4; d -= 4) {
            int rises = 0;
            boolean was = AirfieldMath.flash(-0.05, d) > 0;
            for (double t = 0; t < AirfieldMath.FLASH_PERIOD; t += 0.05) {
                boolean on = AirfieldMath.flash(t, d) > 0;
                rises += on && !was ? 1 : 0;
                was = on;
                period &= Math.abs(AirfieldMath.flash(t, d) - AirfieldMath.flash(t + AirfieldMath.FLASH_PERIOD, d)) < 1e-9;
            }
            once &= rises == 1;
            double at = AirfieldMath.flashAt(d);
            order &= prev < 0 || at > prev;
            prev = at;
        }
        double peak = AirfieldMath.flash(AirfieldMath.flashAt(12), 12);
        check(once && order && period && peak > 0.99 && AirfieldMath.FLASH_PERIOD == 10,
                "D1 approach lights: every pole flashes once a run, twice a second (10 ticks), the run goes from the far poles toward the threshold");
        RunwayMath.Runway r = new RunwayMath.Runway(true, 0.5, 0, 80, 64, 400, 5);
        double[] a = AirfieldMath.approach(r, -12.5, 0.5), b = AirfieldMath.approach(r, 95.5, 1.5);
        check(a != null && a[0] == 1 && Math.abs(a[1] - 12.5) < 1e-9 && b != null && b[0] == -1 && Math.abs(b[1] - 15.5) < 1e-9
                        && AirfieldMath.approach(r, -12.5, 6.5) == null && AirfieldMath.approach(r, 40, 0.5) == null
                        && AirfieldMath.approach(r, -80, 0.5) == null,
                "D1 approach lights: a pole on the extended centre line before either end knows its threshold and distance; off the line, over the"
                        + " strip or past " + AirfieldMath.APPROACH_MAX + " blocks - none");
    }

    // ---------------------------------------------------------------- centre line, lights on

    private static void marks() {
        double len = 100;
        int w = AirfieldMath.centreColour(len, 50, 0), alt0 = AirfieldMath.centreColour(len, 20, 0), alt1 = AirfieldMath.centreColour(len, 20, 1),
                end = AirfieldMath.centreColour(len, 5, 1);
        List<Double> cl = AirfieldMath.centreLights(new RunwayMath.Runway(true, 0.5, 0, 40, 64, 200, 5));
        check(w == AirfieldMath.WHITE && alt0 == AirfieldMath.RED && alt1 == AirfieldMath.WHITE && end == AirfieldMath.RED && cl.size() == 20
                        && cl.get(0).doubleValue() == 1 && cl.get(1).doubleValue() == 3,
                "D1 centre line: lights every 2 blocks; white, the last 30 % red / white alternating, the last 10 % red");
    }

    private static void lightsOn() {
        check(!AirfieldMath.lightsOn(0F, false, AirfieldMath.AUTO) && AirfieldMath.lightsOn(0.5F, false, AirfieldMath.AUTO)
                        && AirfieldMath.lightsOn(0.24F, false, AirfieldMath.AUTO) && !AirfieldMath.lightsOn(0.2F, false, AirfieldMath.AUTO)
                        && AirfieldMath.lightsOn(0F, true, AirfieldMath.AUTO) && AirfieldMath.lightsOn(0F, false, AirfieldMath.ON)
                        && !AirfieldMath.lightsOn(0.5F, true, AirfieldMath.OFF) && AirfieldMath.manual(false, true) == AirfieldMath.AUTO
                        && AirfieldMath.manual(true, false) == AirfieldMath.OFF && AirfieldMath.manual(true, true) == AirfieldMath.ON,
                "D1 lights: off at noon, on at midnight / at dusk (sun under " + AirfieldMath.SUN_NIGHT + ") / in rain; a block wired to redstone follows"
                        + " its signal (on by day, off at night)");
    }

    // ---------------------------------------------------------------- ЗАХВ with a PAPI

    /** The approach simulation's results. */
    static final class Approach {
        int[] phaseAt = {-1, -1, -1};
        boolean order = true, touched;
        int glideTicks, onSlope;
        double maxErr, touchAlong = Double.NaN, touchSink, touchSpeed, minSpeed = 1e9, flareH = Double.NaN, flareAlong;
    }

    /**
     * ЗАХВ from (x, z, y) heading hdg at speed (b/t), СКОР at setKmh: the autopilot's branch of PlanePhysicsSVA (the modes'
     * vertical speed, СКОР / the flare's throttle, the intercept heading), the phase kept from tick to tick, until it
     * touches the surface (or `ticks`).
     */
    static Approach approach(VehicleType t, RunwayMath.Runway r, double x, double z, double y, double hdg, double speed, double setKmh, int ticks) {
        PlaneParams p = t.plane;
        Approach o = new Approach();
        double vy = 0, thr = 0.5, prevSpeed = speed, y0 = y, set = CruiseMath.fromKmh(setKmh);
        int phase = AutopilotMath.PH_RWY, last = -1;
        o.flareAlong = Double.NaN;
        for (int i = 0; i < ticks; i++) {
            AutopilotMath.Capture c = AutopilotMath.capture(r, x, y, z, hdg, AutopilotMath.DESCENT, speed, phase);
            phase = c.out ? AutopilotMath.PH_RWY : c.phase;
            if (phase != last) {
                o.order &= phase > last;
                if (o.phaseAt[phase] < 0) {
                    o.phaseAt[phase] = i;
                }
                if (phase == AutopilotMath.PH_FLARE) {
                    o.flareH = y - r.y;
                    o.flareAlong = c.along;
                }
                last = phase;
            }
            if (phase == AutopilotMath.PH_GLIDE) {
                o.glideTicks++;
                if (i - o.phaseAt[AutopilotMath.PH_GLIDE] > 40) {
                    o.onSlope += c.whites == 2 ? 1 : 0;
                    o.maxErr = Math.max(o.maxErr, Math.abs(c.papiAngle - c.papi.nominal));
                }
            }
            thr = phase == AutopilotMath.PH_FLARE ? AutopilotMath.flareThrottle(thr, speed, p.stall)
                    : AutopilotMath.speedThrottle(thr, speed, prevSpeed, set, AutopilotMath.SPD_MAX_THROTTLE);
            prevSpeed = speed;
            double climb = PlaneMath.climbCapacity(p, speed) * FlightMath.ceilingFactor(y);
            double want = !Double.isNaN(c.vy) ? VehicleMath.clamp(c.vy, -p.dive, Math.max(0, climb))
                    : AutopilotMath.wantedVy(AutopilotMath.M_ALT, y, y0, 0, climb, p.dive);
            vy = FlightMath.approach(vy, want, PlaneMath.VACC);
            speed += PlaneMath.airAccel(t, p, speed, thr, vy, vy > 0, true, 1);
            speed = VehicleMath.clamp(speed, 0, PlaneMath.maxSpeed(t, 1));
            o.minSpeed = Math.min(o.minSpeed, speed);
            if (!c.out) {
                double yaw = CruiseMath.headingStep(hdg - 180, c.heading - 180, t.turnRate * VehicleMath.clamp(speed / p.stall, 0, 1));
                hdg = ((yaw + 180) % 360 + 360) % 360;
            }
            double rad = Math.toRadians(hdg);
            x += Math.sin(rad) * speed;
            z -= Math.cos(rad) * speed;
            y += vy;
            if (y <= r.y) {
                o.touched = true;
                o.touchAlong = r.alongX ? x - r.a0 : z - r.a0;
                o.touchSink = -vy;
                o.touchSpeed = speed;
                break;
            }
        }
        return o;
    }

    private static void glideSim() {
        VehicleType pl = VehicleType.PLANE;
        // a 120 x 5 strip along X (surface 64), a row 15 blocks in on the left; the plane 150 out, 6 off, 12 up (3 deg) / 16 up
        // (5 deg), heading 80, СКОР 65 km/h
        for (int nominal = 3; nominal <= 5; nominal += 2) {
            RunwayMath.Runway r = new RunwayMath.Runway(true, 0.5, 0, 120, 64, 600, 5);
            r.papi[0] = new AirfieldMath.Papi(1, 15.5, -5.0, 64 + AirfieldMath.LENS_Y, nominal, 4);
            Approach a = approach(pl, r, -150, 6.5, nominal == 3 ? 76 : 80, 80, 0.95, 65, 1200);
            boolean slope = a.glideTicks > 40 && a.onSlope >= (a.glideTicks - 41) * 0.9;
            check(a.order && a.phaseAt[0] == 0 && a.phaseAt[1] > 0 && a.phaseAt[2] > a.phaseAt[1] && slope && a.maxErr < 0.3,
                    "D1 ЗАХВ + PAPI " + nominal + " deg: ВПП -> ГЛИС (tick " + a.phaseAt[1] + ") -> ВЫРАВН. (tick " + a.phaseAt[2] + "), on the slope "
                            + a.onSlope + " of " + Math.max(0, a.glideTicks - 41) + " ticks of ГЛИС (2 white + 2 red), the angle within " + f(a.maxErr) + " deg");
            check(a.touched && a.touchAlong > 0 && a.touchAlong < 90 && a.touchSink < 0.04 && a.minSpeed > pl.plane.stall * 1.1
                            && a.flareH < AutopilotMath.FLARE_START && a.flareH > 0.5 && a.flareAlong > -AutopilotMath.FLARE_LEAD - 1,
                    "D1 ЗАХВ + PAPI " + nominal + " deg: ВЫРАВН. " + f(a.flareH) + " blocks over the strip, " + f(-a.flareAlong)
                            + " before the threshold, touches down " + f(a.touchAlong) + " blocks past it (sink " + f(a.touchSink * 20) + " m/s, "
                            + VehicleMath.kmh(a.touchSpeed) + " km/h, never under " + VehicleMath.kmh(a.minSpeed) + " km/h; stall "
                            + VehicleMath.kmh(pl.plane.stall) + ")");
        }
        // too low: under the slope it stays level until the slope comes down to it (all red -> 2 + 2)
        RunwayMath.Runway r2 = new RunwayMath.Runway(true, 0.5, 0, 120, 64, 600, 5);
        r2.papi[0] = new AirfieldMath.Papi(1, 15.5, -5.0, 64 + AirfieldMath.LENS_Y, 4, 4);
        AutopilotMath.Capture low = AutopilotMath.capture(r2, -100, 66, 0.5, 90, 0.1, 1.0, AutopilotMath.PH_RWY);
        AutopilotMath.Capture high = AutopilotMath.capture(r2, -100, 90, 0.5, 90, 0.1, 1.0, AutopilotMath.PH_RWY);
        AutopilotMath.Capture on = AutopilotMath.capture(r2, -84.5, r2.papi[0].slopeY(r2, -84.5, 0.5), 0.5, 90, 0.1, 1.0, AutopilotMath.PH_RWY);
        AutopilotMath.Capture steep = AutopilotMath.capture(r2, -84.5, r2.papi[0].slopeY(r2, -84.5, 0.5) + 1, 0.5, 90, 0.1, 1.0, AutopilotMath.PH_GLIDE),
                shallow = AutopilotMath.capture(r2, -84.5, r2.papi[0].slopeY(r2, -84.5, 0.5) - 1, 0.5, 90, 0.1, 1.0, AutopilotMath.PH_GLIDE);
        check(low.whites == 0 && low.vy == 0 && low.phase == AutopilotMath.PH_RWY && high.whites == 4 && high.vy < 0 && on.phase == AutopilotMath.PH_GLIDE
                        && steep.vy < shallow.vy && steep.whites > shallow.whites,
                "D1 ЗАХВ + PAPI: all red - level, all white - down to the slope, 2 + 2 - ГЛИС; on ГЛИС more white = steeper (" + f(steep.vy * 20)
                        + " m/s), more red = shallower (" + f(shallow.vy * 20) + " m/s)");
        // without a PAPI: stage C as it was (phase stays ВПП, lets go at OFF_HEIGHT)
        RunwayMath.Runway bare = new RunwayMath.Runway(true, 0.5, 0, 80, 64, 400, 5);
        AutopilotMath.Capture c0 = AutopilotMath.capture(bare, -50, 90, 0.5, 90, 0.1, 0, AutopilotMath.PH_GLIDE);
        check(c0.phase == AutopilotMath.PH_RWY && c0.papi == null && c0.vy == -0.1 && AutopilotMath.capture(bare, 10, 66, 0.5, 90, 0.1, 0).done,
                "D1 ЗАХВ: without a PAPI it is stage C's capture (ВПП, the glide slope to the threshold, let go 4 blocks over the strip)");
        // the panel word keeps the phase
        AutopilotMath.Panel p = new AutopilotMath.Panel();
        p.on = true;
        p.modes = AutopilotMath.M_CAP;
        p.runway = true;
        p.alt = 1023;
        p.hdg = 359;
        p.phase = AutopilotMath.PH_FLARE;
        AutopilotMath.Panel q = AutopilotMath.unpack(AutopilotMath.pack(p), AutopilotMath.pack2(p));
        check(q.phase == AutopilotMath.PH_FLARE && q.alt == 1023 && q.hdg == 359 && q.on && AutopilotMath.PHASE_KEYS.length == 3
                        && AutopilotMath.flareThrottle(0.6, 1.0, 0.5) < 0.6 && AutopilotMath.flareThrottle(0.2, 0.55, 0.5) > 0.2
                        && AutopilotMath.flareThrottle(0.3, 0.62, 0.5) == 0.3,
                "D1 ЗАХВ: the synced panel word keeps the phase; ВЫРАВН. closes the throttle, opens it again under 1.2 x the stall");
    }

    // ---------------------------------------------------------------- ★14 the landing light

    private static void lamp() {
        check(AirfieldMath.lampOut(false, false, 10, true, 80, 0) && !AirfieldMath.lampOut(false, false, 0, false, 0, 0)
                        && AirfieldMath.lampOut(false, false, 0, true, 20, -0.05) && !AirfieldMath.lampOut(false, false, 0, true, 40, -0.05)
                        && !AirfieldMath.lampOut(false, false, 0, true, 20, 0.02) && AirfieldMath.lampOut(true, false, 0, true, 31, 0)
                        && !AirfieldMath.lampOut(true, false, 0, true, 31, 0.05) && AirfieldMath.lampOut(false, true, 0, true, 100, 0.2)
                        && AirfieldMath.lampOut(false, false, 20, false, 0, 0),
                "D1 landing light: out with flaps >= 10 deg (also on the ground), under 30 blocks descending, by the switch; stays out on the"
                        + " approach (no flapping at 30), in when climbing away / flaps up on the ground");
        AnimMath.State s = new AnimMath.State();
        s.agl = 20;
        int ticks = 0;
        while (s.lamp < 1F && ticks < 100) {
            AnimMath.landingLamp(s, false, 0F, true, -0.05F, true);
            ticks++;
        }
        AnimMath.State j = new AnimMath.State();
        AnimMath.landingLamp(j, false, 20F, false, 0F, false);
        check(ticks == AirfieldMath.LAMP_TICKS && s.lampOut && j.lamp == 1F && j.prevLamp == 1F,
                "D1 landing light: swings out in " + ticks + " ticks (1 s); animations off - at once");
        // the model rows: the door swings 105 deg front edge down, the lens then looks forward 15 deg down - where the light shines from
        AnimParts.Def d = null;
        for (AnimParts.Def x : AnimParts.of("plane")) {
            if ("landingLampR".equals(x.name)) {
                d = x;
            }
        }
        float[] lensIn = {0.7952F, 0.9531F, 0.8125F};
        float[] out = d == null ? null : turn(lensIn, d.pivot, d.axis1, d.ang1);
        double[] n = turnDir(new double[] {0, 1, 0}, d == null ? new float[] {0, 0, 1} : d.axis1, d == null ? 0 : d.ang1);
        double pitch = Math.toDegrees(Math.atan2(-n[1], n[0]));
        VehicleLights.Light lamp = null;
        for (VehicleLights.Light l : VehicleLights.of("plane")) {
            if (l.has(VehicleLights.F_LAMP) && l.z > 0) {
                lamp = l;
            }
        }
        check(d != null && d.device == AnimParts.LAMP && d.tag != null && Math.abs(pitch - AirfieldMath.LAMP_PITCH) < 0.5 && lamp != null
                        && Math.abs(lamp.pitch - pitch) < 0.5 && Math.abs(lamp.x - out[0]) < 0.02 && Math.abs(lamp.y - out[1]) < 0.02
                        && Math.abs(lamp.z - out[2]) < 0.02 && VehicleLights.hasLamp("plane") && !VehicleLights.hasLamp("jet"),
                "D1 landing light: the plane's row swings the lens forward " + f(pitch) + " deg down to where its beam starts ("
                        + (out == null ? "-" : f(out[0]) + ", " + f(out[1])) + ")");
    }

    /** p turned by deg about the axis k through c (Rodrigues). */
    private static float[] turn(float[] p, float[] c, float[] k, float deg) {
        double[] v = turnDir(new double[] {p[0] - c[0], p[1] - c[1], p[2] - c[2]}, k, deg);
        return new float[] {(float) v[0] + c[0], (float) v[1] + c[1], (float) v[2] + c[2]};
    }

    private static double[] turnDir(double[] v, float[] k, float deg) {
        double a = Math.toRadians(deg), cs = Math.cos(a), sn = Math.sin(a);
        double dot = k[0] * v[0] + k[1] * v[1] + k[2] * v[2];
        double cx = k[1] * v[2] - k[2] * v[1], cy = k[2] * v[0] - k[0] * v[2], cz = k[0] * v[1] - k[1] * v[0];
        return new double[] {v[0] * cs + cx * sn + k[0] * dot * (1 - cs), v[1] * cs + cy * sn + k[1] * dot * (1 - cs),
            v[2] * cs + cz * sn + k[2] * dot * (1 - cs)};
    }
}
