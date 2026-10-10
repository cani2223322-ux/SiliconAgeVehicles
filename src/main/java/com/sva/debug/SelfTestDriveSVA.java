package com.sva.debug;

import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

/**
 * Self-tests of the 2026-10-10 fixes (pure logic, run by SelfTestSVA - [SVA-TEST] lines, the same counters).
 * <p>
 * The stall in level flight (user report: «СВАЛИВАНИЕ» at 52 km/h, throttle 50 %, 3 blocks over a superflat): a
 * deterministic run of the light plane's own physics (PlaneMath, as PlanePhysicsSVA steps it) - take-off run, lift-off,
 * a climb to 3 blocks, then level at 50 % throttle - and the server's measurement of the same flight with the Moves
 * arriving unevenly (some ticks none, the next one two). The old rule (the stall by the EMA of the per-tick displacement)
 * flags a stall in level flight; the new one (PlaneMath.reportedAirspeed: the client's airspeed capped by the measured)
 * never does there, and still flags a real stall. The same for the jet and the tiltrotor's airplane mode.
 */
final class SelfTestDriveSVA {

    /** Ticks the Move arrives in (false: none - the next tick takes the latest of two), as WorldTestDriveSVA.JITTER. */
    private static final boolean[] JITTER = {true, true, false, true, true, false, true, true, true, false, true, false, true, true};

    private SelfTestDriveSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() {
        reportedAirspeed();
        levelFlight(VehicleType.PLANE, 0.5);
        levelFlight(VehicleType.JET, 0.45);
        levelFlight(VehicleType.TILT, 0.4);
        realStall();
    }

    private static void reportedAirspeed() {
        check(PlaneMath.reportedAirspeed(0.79, 0.79) == 0.79 && PlaneMath.reportedAirspeed(0.79, 0.6) == 0.79,
                "airspeed: the client's own speed counts (also when the 4-tick mean dipped to 3/4 on a late Move)");
        check(Math.abs(PlaneMath.reportedAirspeed(2.0, 0.5) - (0.5 * PlaneMath.AIR_MARGIN + PlaneMath.AIR_SLACK)) < 1e-9,
                "airspeed: a client can't claim more than " + PlaneMath.AIR_MARGIN + " x the measured + " + PlaneMath.AIR_SLACK);
        check(PlaneMath.reportedAirspeed(Double.NaN, 0.6) == 0.6 && PlaneMath.reportedAirspeed(Double.POSITIVE_INFINITY, 0.6) == 0.6
                && PlaneMath.reportedAirspeed(-0.3, 0.6) == 0 && PlaneMath.reportedAirspeed(0.5, -1) == PlaneMath.AIR_SLACK,
                "airspeed: NaN / infinite -> the measured, never negative");
    }

    /** One plane flight by the physics: [speed, y] per tick; take-off at full throttle, climb `height` blocks, level at `thr`. */
    private static double[][] fly(VehicleType t, double height, double thr, int levelTicks) {
        PlaneParams p = t.plane;
        double[][] out = new double[4000][];
        int n = 0;
        double speed = 0, y = 0, vy = 0, th = 0;
        boolean air = false;
        // take-off run (W to full), lift-off with Space
        while (!air && n < 1500) {
            th = PlaneMath.throttleStep(th, true, false, true);
            speed = Math.max(0, speed + PlaneMath.accel(t, speed, th, false, false));
            if (speed >= p.liftOff) {
                vy = p.rotateVy;
                air = true;
            }
            y += air ? vy : 0;
            out[n++] = new double[] {speed, y, 0};
        }
        // climb with Space to `height`, then throttle back to `thr` (S taps) and hold the height
        while (n < 3000 && y < height) {
            vy = PlaneMath.airVy(p, speed, vy, true, false, true, 100 + y);
            speed += PlaneMath.airAccel(t, speed, th, vy, true, true, 1);
            y += vy;
            out[n++] = new double[] {speed, y, 1};
        }
        for (int i = 0; i < levelTicks && n < 4000; i++) {
            boolean down = th > thr + 1e-9;
            th = PlaneMath.throttleStep(th, false, down, true);
            vy = PlaneMath.airVy(p, speed, vy, false, false, true, 100 + y);
            speed += PlaneMath.airAccel(t, speed, th, vy, false, true, 1);
            speed = VehicleMath.clamp(speed, 0, PlaneMath.maxSpeed(t, 1));
            y += vy;
            out[n++] = new double[] {speed, y, 2};
        }
        double[][] r = new double[n][];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    private static void levelFlight(VehicleType t, double thr) {
        double[][] f = fly(t, 3, thr, 400);
        PlaneParams p = t.plane;
        boolean clientStall = false;
        int level = 0;
        double end = f[f.length - 1][0];
        for (double[] s : f) {
            if (s[2] == 2) {
                level++;
                clientStall |= PlaneMath.stalled(p, s[0]);
            }
        }
        double endY = f[f.length - 1][1];
        check(!clientStall && level == 400 && Math.abs(endY - 3) < 1.5 && end > p.stall * 1.15,
                t.name() + ": take-off, climb to 3 blocks, level at " + Math.round(thr * 100) + " % throttle - " + VehicleMath.kmh(end)
                        + " km/h (stall " + VehicleMath.kmh(p.stall) + "), Y +" + Math.round(endY * 10) / 10.0 + ", the physics never stalls");
        // the server's measurement of the same flight, the Moves arriving unevenly
        double ema = 0, win[] = new double[4], pending = 0;
        int at = 0, oldFlags = 0, newFlags = 0, ticks = 0;
        double minEma = Double.MAX_VALUE;
        for (int i = 0; i < f.length; i++) {
            pending += f[i][0];                                     // the client's step (horizontal = the airspeed)
            double moved = 0;
            if (JITTER[i % JITTER.length]) {
                moved = pending;                                    // the latest Move carries every step since the last one
                pending = 0;
            }
            ema = ema * 0.5 + moved * 0.5;                          // EntityVehicleSVA: speedEma
            win[at] = moved;
            at = (at + 1) % win.length;
            double mean = (win[0] + win[1] + win[2] + win[3]) / 4;
            if (f[i][2] == 2) {
                ticks++;
                minEma = Math.min(minEma, ema);
                if (PlaneMath.stalled(p, Math.min(ema, t.topSpeed * 1.2))) {
                    oldFlags++;
                }
                if (PlaneMath.stalled(p, PlaneMath.reportedAirspeed(f[i][0], Math.max(ema, mean)))) {
                    newFlags++;
                }
            }
        }
        check(oldFlags > 0, t.name() + ": the old rule (the measured EMA) flagged a stall in level flight on " + oldFlags + " of " + ticks
                + " ticks (EMA down to " + VehicleMath.kmh(minEma) + " km/h) - the reported bug");
        check(newFlags == 0, t.name() + ": the new rule (the client's airspeed, capped) never flags it in level flight (" + newFlags + ")");
    }

    private static void realStall() {
        VehicleType t = VehicleType.PLANE;
        PlaneParams p = t.plane;
        double speed = 0.3, vy = 0, y = 0;
        int flagged = 0;
        double ema = 0.3, pending = 0;
        for (int i = 0; i < 20; i++) {
            vy = PlaneMath.airVy(p, speed, vy, false, false, true, 100);
            speed += PlaneMath.airAccel(t, speed, 0, vy, false, true, 1);
            y += vy;
            pending += speed;
            double moved = JITTER[i % JITTER.length] ? pending : 0;
            if (moved > 0) {
                pending = 0;
            }
            ema = ema * 0.5 + moved * 0.5;
            if (PlaneMath.stalled(p, PlaneMath.reportedAirspeed(speed, Math.max(ema, speed)))) {
                flagged++;
            }
        }
        check(flagged >= 10 && y < -2, "light plane at 22 km/h, idle: still a stall - flagged " + flagged + " of 20 ticks, the nose drops ("
                + Math.round(y * 10) / 10.0 + " blocks)");
    }
}
