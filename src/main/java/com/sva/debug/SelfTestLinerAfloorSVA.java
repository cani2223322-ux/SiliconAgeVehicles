package com.sva.debug;

import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HighLiftMath;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.MoveBudget;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.VehicleType;

/**
 * The false alpha floor, second fix (2026-10-15, the user's TOGA LK at 144 km/h, Y 40, A/THR at CL after 0bf757c): the
 * Moves' time budget (MoveBudget - bursts, a steady 2x pace, a silence, a lagging server), the liner's judged airspeed
 * (PlaneMath.judgedAirspeed - the client's, sanity-capped, refused / gap ticks are no measurement), alpha floor's speed
 * and AoA at the user's state, and the whole chain replayed: the driver's client at 144 km/h, its Moves through the
 * latest-only queue into a server with lag spikes (the catch-up ticks run back to back), refused Moves and Corrects -
 * through the 0bf757c rules (one borrowed step, gapAirspeed, the tick-only MOVE_TIMEOUT: ten catch-up ticks without a
 * Move hand the aircraft to the server's physics from the refused tick's zero displacement) TOGA LK latches, through the
 * new ones it never does.
 */
final class SelfTestLinerAfloorSVA {

    private SelfTestLinerAfloorSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static String f(double v) {
        return SelfTestLinerAfsSVA.f(v);
    }

    static void run() {
        budgetBursts();
        budgetSteady2x();
        budgetSilence();
        budgetLag();
        judged();
        userState();
        afloorInputs();
        replay();
    }

    // ---------------------------------------------------------------- MoveBudget

    /** Steps a tick's Move covers -> accepted (the budget's verdict, as serverMove asks it; 0 = no Move that tick). */
    private static boolean[] feed(MoveBudget b, int[] steps, long t0, long msStep) {
        boolean[] ok = new boolean[steps.length];
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == 0) {
                ok[i] = true;
                continue;
            }
            long now = t0 + i;
            b.tick(now, now * msStep, 10);
            ok[i] = steps[i] <= b.maxSteps();
            if (ok[i]) {
                b.accept(steps[i]);
            }
        }
        return ok;
    }

    private static boolean all(boolean[] ok) {
        for (boolean b : ok) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    /** Bunched arrivals (2, 0, 2, 0 ...; 3, 0, 0, 3 ...; 2, 1, 2, 1, 0, 0) all pass - the old ±1 refused the last. */
    private static void budgetBursts() {
        MoveBudget a = new MoveBudget(), b = new MoveBudget(), c = new MoveBudget();
        int[] p1 = new int[40], p2 = new int[42], p3 = {1, 1, 2, 1, 2, 1, 0, 0, 1, 1, 2, 1, 2, 1, 0, 0, 1, 1, 1};
        for (int i = 0; i < p1.length; i++) {
            p1[i] = i % 2 == 0 ? 2 : 0;
        }
        for (int i = 0; i < p2.length; i++) {
            p2[i] = i % 3 == 0 ? 3 : 0;
        }
        boolean ok1 = all(feed(a, p1, 100, 50)), ok2 = all(feed(b, p2, 100, 50)), ok3 = all(feed(c, p3, 100, 50));
        boolean old3 = all(oldFeed(p3));
        check(ok1 && ok2 && ok3 && !old3, "move budget: bunched Moves (2,0,2,0..; 3,0,0,3..; 2,1,2,1,0,0) all accepted (the old one-step"
                + " borrow refused the last: " + !old3 + ")");
    }

    /** A steady 2x pace (two steps every tick) is refused within 4 ticks from the steady state. */
    private static void budgetSteady2x() {
        MoveBudget b = new MoveBudget();
        int[] warm = new int[30];
        java.util.Arrays.fill(warm, 1);
        feed(b, warm, 100, 50);
        int refusedAt = -1;
        for (int i = 0; i < 10 && refusedAt < 0; i++) {
            long now = 130 + i;
            b.tick(now, now * 50, 10);
            if (2 > b.maxSteps()) {
                refusedAt = i + 1;
            } else {
                b.accept(2);
            }
        }
        check(refusedAt > 0 && refusedAt <= 4, "move budget: a steady 2x pace refused at its tick " + refusedAt + " (<= 4)");
    }

    /** A silence hoards no credit beyond the cap: 8 ticks quiet, then the most one tick may take is 1 + 2 CAP. */
    private static void budgetSilence() {
        MoveBudget b = new MoveBudget();
        int[] warm = new int[20];
        java.util.Arrays.fill(warm, 1);
        feed(b, warm, 100, 50);
        b.tick(128, 128 * 50, 10);                                  // 8 ticks without a Move, then a 1-step Move
        b.accept(1);
        double credit = b.credit();
        b.tick(129, 129 * 50, 10);
        long most = b.maxSteps();
        int refusedAt = -1;
        b.accept(2);
        for (int i = 1; i < 12 && refusedAt < 0; i++) {
            b.tick(129 + i, (129 + i) * 50, 10);
            if (2 > b.maxSteps()) {
                refusedAt = i + 1;
            } else {
                b.accept(2);
            }
        }
        // a silence over the timeout starts fresh
        MoveBudget c = new MoveBudget();
        feed(c, warm, 100, 50);
        c.tick(200, 200 * 50, 10);
        long fresh = c.maxSteps();
        check(credit <= MoveBudget.CAP + 1e-9 && most <= 1 + 2 * (long) MoveBudget.CAP && refusedAt > 0 && refusedAt <= 2 * MoveBudget.CAP + 1
                        && fresh == 1 + (long) MoveBudget.CAP,
                "move budget: after 8 quiet ticks credit " + f(credit) + " (cap " + f(MoveBudget.CAP) + "), at most " + most + " steps next tick,"
                        + " a 2x pace then refused at tick " + refusedAt + "; after a long silence fresh (" + fresh + ")");
    }

    /** A lagging server (100 ms ticks): the real time pays - two steps a tick pass, three a tick soon don't. */
    private static void budgetLag() {
        MoveBudget b = new MoveBudget();
        int[] p = new int[30];
        java.util.Arrays.fill(p, 2);
        boolean twoOk = all(feed(b, p, 100, 100));
        MoveBudget c = new MoveBudget();
        int[] q = new int[30];
        java.util.Arrays.fill(q, 3);
        boolean threeOk = all(feed(c, q, 100, 100));
        check(twoOk && !threeOk, "move budget: a 10 TPS server - 2 client steps a tick accepted (the real time counts), a steady 3 refused");
    }

    /** The 0bf757c rule (one step borrowed / saved), for the comparison. */
    private static boolean[] oldFeed(int[] steps) {
        boolean[] ok = new boolean[steps.length];
        long credit = 0, last = 99;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == 0) {
                ok[i] = true;
                continue;
            }
            long now = 100 + i, dt = now - last, creditAt = credit;
            last = now;
            long dtMax = Math.max(1, Math.min(10, dt + creditAt + 1));
            ok[i] = steps[i] <= dtMax;
            if (ok[i]) {
                credit = Math.max(-1, Math.min(1, creditAt + dt - steps[i]));
            }
        }
        return ok;
    }

    // ---------------------------------------------------------------- the judged airspeed

    private static void judged() {
        double top = VehicleType.LINER.topSpeed;
        double ok = PlaneMath.judgedAirspeed(0, 2.0, top, 1.95, true);
        double noMeas = PlaneMath.judgedAirspeed(0, 2.0, top, 0, false);        // refused / gap: no measurement, not 0
        double liarHigh = PlaneMath.judgedAirspeed(0, 9.0, top, Double.NaN, false);
        double liarMeas = PlaneMath.judgedAirspeed(0, 2.0, top, 0.5, true);
        double nan = PlaneMath.judgedAirspeed(1.7, Double.NaN, top, 0, false);
        check(Math.abs(ok - 2.0) < 1e-9 && Math.abs(noMeas - 2.0) < 1e-9 && Math.abs(liarHigh - top * PlaneMath.TOP_MARGIN) < 1e-9
                        && liarMeas < 0.7 && Math.abs(nan - 1.7) < 1e-9,
                "liner judged airspeed: the client's 144 km/h stays 144 with an invalid measurement (refused / gap), capped at "
                        + f(top * PlaneMath.TOP_MARGIN * 72) + " km/h and by a valid measurement (" + f(liarMeas * 72) + "), NaN keeps the last");
    }

    // ---------------------------------------------------------------- the user's state

    /** Y 40 over a ground at Y 4 (RA 36), 144 km/h, CONF 0, the liner's mass: alpha floor's speed and AoA far off. */
    private static void userState() {
        LinerSimSVA s = LinerSimSVA.flying(36, 2.0, 90, 0);
        s.click(LinerControls.FCU_AP1);
        s.run(3);
        LinerAfs F = s.L.afs;
        LinerAfs.Air a = s.e.air;
        double aoa = LinerAfs.aFloorAoa(a);
        boolean far = F.aFloorV * 72 < 95 && aoa < LinerAfs.AFLOOR_AOA * 0.5 && a.speed > F.aFloorV * 1.5;
        check(far && !F.aFloor && !F.togaLk && F.thr == LinerAfs.T_SPEED,
                "liner alpha floor at the user's state (RA " + f(a.ra) + ", " + f(a.speed * 72) + " km/h, CONF 0, " + Math.round(a.mass) + " kg):"
                        + " V_AFLOOR " + f(F.aFloorV * 72) + " km/h, AoA " + f(aoa) + " deg (floor " + f(LinerAfs.AFLOOR_AOA) + "), SPEED held");
    }

    /**
     * Alpha floor's samples: invalid ones (refused / gap ticks) neither count nor reset; a low reported airspeed with the
     * measured one high (a low AoA) and steady is no alpha floor; low reported and measured alike is, after AFLOOR_TICKS.
     */
    private static void afloorInputs() {
        LinerSimSVA s = cruise144();
        LinerAfs F = s.L.afs;
        double lowV = F.aFloorV - 0.2;
        s.airOverride = lowV;
        s.spdOverride = lowV;
        s.airValid = false;
        for (int i = 0; i < 40; i++) {
            s.systems();
        }
        boolean invalidNone = !F.aFloor && !F.togaLk;
        s.airValid = true;
        s.measOverride = 2.0;                                       // the aircraft flies like 144 km/h, the report reads low
        for (int i = 0; i < 40; i++) {
            s.systems();
        }
        boolean glitchNone = !F.aFloor && !F.togaLk;
        s.measOverride = lowV;                                      // really slow: the measured agrees
        int at = -1;
        for (int i = 0; i < 20 && at < 0; i++) {
            s.systems();
            if (F.aFloor) {
                at = i + 1;
            }
        }
        check(invalidNone && glitchNone && at == LinerAfs.AFLOOR_TICKS,
                "liner alpha floor's samples: 40 invalid ones under V_AFLOOR - none; a low report with the measured 144 km/h (AoA "
                        + f(HighLiftMath.aoa(1.15, 2.0, 0)) + " deg) - none; really slow - A.FLOOR at sample " + at);
    }

    /** Level at RA 36, 144 km/h, CONF 0, AP 1, the A/THR at CL on a selected 144 km/h (ALT / SPEED). */
    private static LinerSimSVA cruise144() {
        LinerSimSVA s = LinerSimSVA.flying(36, 2.0, 90, 0);
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_SPD, LinerControls.A_SECONDARY);
        s.L.afs.spd = 144;
        s.run(20);
        return s;
    }

    // ---------------------------------------------------------------- the chain replayed

    /** The server's side of the Move link, by the 0bf757c rules (fix false) or the new ones. */
    private static final class Link {
        final boolean fix;
        final double reach1 = FlightMath.maxReach(VehicleType.LINER) * 1.3;
        double sx, prevSx;
        long lastMoveTick = -1000, lastCorrect = -100;
        double lastMoveMs = -1e9;
        int refused, moves, seen;
        // the old rule
        long credit, baseDt = 1;
        double baseMs;
        double ema, judged, speed;
        final double[] win = new double[4];
        final int[] mv = new int[4];
        int at, gap;
        // the new one
        final MoveBudget budget = new MoveBudget();
        double reported = Double.NaN, anchorX = Double.NaN, anchorMs, air;
        long anchorTick;
        final double[] md = new double[4], mspan = new double[4];
        int mAt;
        boolean validTick, valid = true;
        double meas = Double.NaN;

        Link(boolean fix, double v) {
            this.fix = fix;
            ema = judged = speed = air = v;
            for (int i = 0; i < 4; i++) {
                win[i] = v;
                mv[i] = 1;
            }
        }

        double reach(long d) {
            return reach1 * d + 0.2;
        }

        /** A Move (the client at x, its airspeed) in tick k at time ms. @return refused - send a Correct (if due). */
        boolean move(long k, double ms, double x, double spd) {
            boolean ok = false;
            long used = 0;
            double bh = x - sx;
            if (fix) {
                budget.tick(k, (long) ms, 10);
                long dtMax = Math.min(MoveBudget.MAX_ELAPSED + 6, budget.maxSteps());
                for (long d = 1; d <= dtMax && !ok; d++) {
                    ok = bh <= reach(d);
                    used = d;
                }
                if (ok) {
                    budget.accept(used);
                }
                reported = spd;
            } else {
                long ticks = k - lastMoveTick, real = (long) ((ms - baseMs + 25) / 50);
                baseDt = ticks > 10 ? 1 : Math.max(1, Math.min(10, Math.max(ticks, real)));
                long creditAt = ticks > 10 ? 0 : credit;
                baseMs = ms;
                long dtMax = Math.max(1, Math.min(10, baseDt + creditAt + 1));
                for (long d = 1; d <= dtMax && !ok; d++) {
                    ok = bh <= reach(d);
                    used = d;
                }
                if (ok) {
                    credit = Math.max(-1, Math.min(1, creditAt + baseDt - used));
                }
            }
            lastMoveTick = k;
            lastMoveMs = ms;
            seen++;
            if (ok) {
                if (fix) {
                    measure(x, k, ms);
                }
                sx = x;
                moves++;
                return false;
            }
            refused++;
            anchorX = Double.NaN;
            if (k - lastCorrect >= 5) {
                lastCorrect = k;
                return true;
            }
            return false;
        }

        void measure(double x, long k, double ms) {
            if (!Double.isNaN(anchorX) && k == anchorTick) {
                return;
            }
            if (!Double.isNaN(anchorX) && k - anchorTick <= 10) {
                md[mAt] = Math.abs(x - anchorX);
                mspan[mAt] = Math.max(0.2, Math.min(k - anchorTick, (ms - anchorMs) / 50.0));
                mAt = (mAt + 1) % 4;
                validTick = true;
            }
            anchorX = x;
            anchorTick = k;
            anchorMs = ms;
        }

        /** The tick's end (EntityVehicleSVA's measurement block): the AFS' airspeed, Env.speed, validity for the next tick. */
        void endTick(long k, double ms, double top) {
            double moved = sx - prevSx;
            prevSx = sx;
            if (fix) {
                boolean driven = k - lastMoveTick <= 10 || ms - lastMoveMs <= 500;
                double d = 0, s = 0;
                for (int i = 0; i < 4; i++) {
                    d += md[i];
                    s += mspan[i];
                }
                meas = s > 0 ? d / s : Double.NaN;
                if (driven && seen > 0) {
                    air = PlaneMath.judgedAirspeed(air, reported, top, meas, validTick);
                }
                valid = !driven || validTick;
                speed = air;
            } else if (k - lastMoveTick <= 10) {
                speed = moved;
                ema = ema * 0.5 + moved * 0.5;
                win[at] = moved;
                mv[at] = moves;
                at = (at + 1) % 4;
                gap = moves > 0 ? 0 : gap + 1;
                double mean = (win[0] + win[1] + win[2] + win[3]) / 4;
                judged = PlaneMath.gapAirspeed(judged, reported(), Math.max(ema, mean), PlaneMath.perMove(win, mv), moves, gap);
                air = judged;
            } else {
                air = Math.max(0, speed);                           // MOVE_TIMEOUT by ticks: the server's physics from the last measure
            }
            moves = 0;
            seen = 0;
            validTick = false;
        }

        double lastSpd = 2.0;

        double reported() {
            return lastSpd;
        }
    }

    /** The outcome of one replay. */
    private static final class Result {
        int refused, latchTick = -1;
        double minAir = 1e9, under;
    }

    /**
     * The user's flight replayed: the liner level at RA 36, 144 km/h, A/THR at CL; the driver's client ticks at 20 TPS
     * quantised to 30 fps frames (two steps in one server tick, then none), the server ticks with lag spikes (a long tick,
     * then the catch-up ticks back to back - MinecraftServer's loop), the latest-only queue, refused Moves with a Correct
     * at most every 5 ticks (30 ms to the client), the client thrown back to the server's position.
     */
    private static Result replay(boolean fix, int[] stallAt, int[] stallMs) {
        LinerSimSVA s = cruise144();
        LinerAfs F = s.L.afs;
        double top = VehicleType.LINER.topSpeed;
        Link link = new Link(fix, s.b.speed);
        Result r = new Result();
        int n = 900;
        double[] ct = new double[n * 2 + 200];
        double frame = 1000 / 30.0;
        for (int i = 0; i < ct.length; i++) {
            ct[i] = Math.ceil(50.0 * i / frame) * frame;         // the client's tick i runs at the first frame after its time
        }
        double cx = 0, corrX = 0, corrAt = Double.MAX_VALUE, cSpd = s.b.speed;
        int ci = 0, lastSent = -1;
        double[] sentX = new double[ct.length];
        double[] sentAt = new double[ct.length];
        double t = 0;
        int si = 0;
        for (int k = 0; k < n; k++) {
            // the client's ticks up to now (each sends a Move, 1 ms on the wire)
            while (ci < ct.length && ct[ci] + 1 <= t) {
                if (corrAt <= ct[ci]) {
                    cx = corrX;                                     // the Correct: thrown back (the airspeed kept)
                    corrAt = Double.MAX_VALUE;
                }
                cSpd = s.b.speed;
                cx += cSpd;
                sentX[ci] = cx;
                sentAt[ci] = ct[ci] + 1;
                lastSent = ci;
                ci++;
            }
            if (lastSent >= 0) {
                link.lastSpd = cSpd;
                if (link.move(k, t, sentX[lastSent], cSpd)) {
                    corrX = link.sx;
                    corrAt = t + 30;
                }
                lastSent = -1;
            }
            link.endTick(k, t, top);
            s.airOverride = link.air;
            s.spdOverride = link.speed;
            s.measOverride = fix ? link.meas : Double.NaN;
            s.airValid = link.valid;
            s.tick();
            r.minAir = Math.min(r.minAir, link.air);
            r.under = Math.max(r.under, s.b.speed - link.air);       // the judged airspeed under the aircraft's real one
            if (r.latchTick < 0 && (F.aFloor || F.togaLk)) {
                r.latchTick = k;
            }
            // the next tick's start: 2 ms of work (a light single-player world), a lag spike's long one, the catch-up back to back
            double dur = 2;
            if (si < stallAt.length && k == stallAt[si]) {
                dur = stallMs[si++];
            }
            t = Math.max(t + dur, 50.0 * (k + 1));
        }
        r.refused = link.refused;
        return r;
    }

    private static void replay() {
        int[] at = {150, 350, 550, 750};
        int[] ms = {400, 800, 1200, 1700};
        Result old = replay(false, at, ms), neu = replay(true, at, ms);
        Result oldCalm = replay(false, new int[0], new int[0]), neuCalm = replay(true, new int[0], new int[0]);
        System.out.println("[SVA-SELFTEST] afloor replay: old latch=" + old.latchTick + " refused=" + old.refused + " minAir=" + f(old.minAir * 72)
                + " | new latch=" + neu.latchTick + " refused=" + neu.refused + " minAir=" + f(neu.minAir * 72) + " under=" + f(neu.under * 72)
                + " | calm old refused=" + oldCalm.refused + " new refused=" + neuCalm.refused);
        check(old.latchTick >= 0 && neu.latchTick < 0 && neu.under * 72 < 3 && neu.refused == 0 && neuCalm.refused == 0 && neuCalm.latchTick < 0,
                "liner false alpha floor replayed (RA 36, 144 km/h, A/THR at CL, Moves bunched 2 a tick, lag spikes 0.4-1.7 s): the 0bf757c"
                        + " rules latch TOGA LK at tick " + old.latchTick + " (" + old.refused + " Moves refused, airspeed down to " + f(old.minAir * 72)
                        + " km/h); the new ones never (" + neu.refused + " refused, judged at most " + f(neu.under * 72) + " km/h under the real speed),"
                        + " none refused without lag");
    }
}
