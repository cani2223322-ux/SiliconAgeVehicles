package com.sva.debug;

import java.util.Random;

import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HeliMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.RegenMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

/**
 * Wave 4 self-tests (★15 рекуперация, RegenMath) - pure logic, run by SelfTestSVA on the dedicated server ([SVA-TEST]
 * lines, the same counters): the efficiency and shares by kind, the kinetic / potential models, the per-tick cap, the
 * buffer's room, the pool (never more than share x spent - a long random fuzz too), braking a buggy from top speed, a
 * drive up and down a hill that must end with less energy than it started, the plane's glide, the autorotation, the
 * synced word.
 */
final class SelfTestRegenSVA {

    private SelfTestRegenSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) < eps;
    }

    static void run() {
        effAndShares();
        models();
        bounds();
        brakeBuggy();
        hillCycle();
        fuzz();
        aircraft();
        synced();
    }

    private static void effAndShares() {
        check(RegenMath.eff(Double.NaN) == 0 && RegenMath.eff(-1) == 0 && RegenMath.eff(0.2) == 0.2 && RegenMath.eff(0.9) == RegenMath.MAX_EFF,
                "regen: the efficiency is sanitised to 0..0.5 (NaN -> 0)");
        check(near(RegenMath.share(VehicleType.BUGGY, false, 0.2), 0.2, 1e-9) && near(RegenMath.share(VehicleType.TRUCK, false, 0.2), 0.16, 1e-9)
                        && near(RegenMath.share(VehicleType.HOVER, false, 0.2), 0.1, 1e-9),
                "regen shares: wheels 20 %, tracks 16 %, hoverbike 10 % (at 0.2)");
        check(near(RegenMath.share(VehicleType.PLANE, true, 0.2), 0.2, 1e-9) && near(RegenMath.share(VehicleType.JET, true, 0.2), 0.12, 1e-9)
                        && near(RegenMath.share(VehicleType.HELI, false, 0.2), 0.15, 1e-9)
                        && near(RegenMath.share(VehicleType.TILT, true, 0.2), 0.2, 1e-9) && near(RegenMath.share(VehicleType.TILT, false, 0.2), 0.15, 1e-9),
                "regen shares: plane 20 %, jet 12 %, helicopter 15 %, tiltrotor by mode (at 0.2)");
        boolean capped = true;
        for (VehicleType t : VehicleType.values()) {
            double s = RegenMath.share(t, true, 0.5);
            capped &= s >= 0 && s <= RegenMath.MAX_EFF && (!t.movement.flies || s <= RegenMath.AIR_MAX + 1e-9);
            capped &= RegenMath.share(t, false, 0) == 0;
        }
        check(capped, "regen shares: every type 0..0.5, aircraft at most 0.3, 0 at efficiency 0");
    }

    private static void models() {
        VehicleType b = VehicleType.BUGGY;
        double kTop = RegenMath.kinetic(b, b.topSpeed, 0, 1);
        // integrate the cost formula over the run-up (accel a tick, cost by the speed) - the closed form must match it
        double sum = 0;
        int n = 100000;
        for (int i = 0; i < n; i++) {
            double v = (i + 0.5) / n * b.topSpeed;
            sum += VehicleMath.cost(b, v, 0, true, 1) * (b.topSpeed / n) / b.accel;
        }
        check(near(kTop, sum, 0.01) && near(kTop, 183.33, 0.01), "regen kinetic: the buggy's K(top) = the run-up's EU from the cost formula ("
                + Math.round(kTop) + " EU)");
        check(RegenMath.kinetic(b, 0, 0, 1) == 0 && RegenMath.kinetic(b, 0.3, 0, 1) < RegenMath.kinetic(b, 0.4, 0, 1)
                        && RegenMath.kinetic(b, 0.4, 1, 1) > RegenMath.kinetic(b, 0.4, 0, 1) && RegenMath.kinetic(b, 0.4, 0, 0) == 0
                        && RegenMath.kinetic(b, -0.3, 0, 1) == RegenMath.kinetic(b, 0.3, 0, 1),
                "regen kinetic: 0 at rest, grows with speed and cargo, 0 without consumption, reverse alike");
        check(near(RegenMath.potential(20, 1), 20, 1e-9) && RegenMath.potential(20, -1) == 0,
                "regen potential: a block of height = one tick at the cruise cost (buggy 20 EU)");
        check(RegenMath.braking(Movement.GROUND, false, true, false, 0.3) && RegenMath.braking(Movement.GROUND, false, false, true, 0.3)
                        && RegenMath.braking(Movement.GROUND, true, false, false, -0.1) && !RegenMath.braking(Movement.GROUND, true, false, false, 0.3)
                        && !RegenMath.braking(Movement.GROUND, false, false, false, 0.3) && !RegenMath.braking(Movement.GROUND, false, false, true, 0)
                        && RegenMath.braking(Movement.HOVER, false, true, false, 0.3) && !RegenMath.braking(Movement.HOVER, false, false, true, 0.3)
                        && !RegenMath.braking(Movement.PLANE, false, true, true, 0.3),
                "regen braking: S against the motion or Space (the hoverbike: S only), never W, never aircraft");
        double c = 20;
        check(RegenMath.groundSource(b, 0.5, 0.45, false, 0, 0.45, 0, 1, c) == 0
                        && RegenMath.groundSource(b, 0.45, 0.5, true, 0, 0.5, 0, 1, c) == 0
                        && RegenMath.groundSource(b, 0.5, 0.45, true, 0, 0.45, 0, 1, c) > 0,
                "regen ground: speed lost only while braking counts, speeding up gives nothing");
        check(near(RegenMath.groundSource(b, 0.3, 0.3, false, -0.5, 0.3, 0, 1, c), 10, 1e-9)
                        && RegenMath.groundSource(b, 0.3, 0.3, false, -1.5, 0.3, 0, 1, c) == 0
                        && RegenMath.groundSource(b, 0, 0, false, -0.5, 0, 0, 1, c) == 0
                        && RegenMath.groundSource(b, 0.3, 0.3, false, 0.5, 0.3, 0, 1, c) == 0,
                "regen ground: rolling downhill counts the height, a fall / standing still / climbing doesn't");
    }

    private static void bounds() {
        check(RegenMath.recover(0.2, 1000, 0, 20, 1e9) == 0, "regen: an empty pool gives nothing back (no energy from nothing)");
        check(near(RegenMath.recover(0.2, 1e6, 1e9, 20, 1e9), 20, 1e-9), "regen: at most one cruise tick a tick (20 EU/t for the buggy)");
        check(near(RegenMath.recover(0.2, 100, 1e9, 20, 3), 3, 1e-9) && RegenMath.recover(0.2, 100, 1e9, 20, 0) == 0,
                "regen: never past the buffer (room 3 -> 3, full -> 0)");
        check(near(RegenMath.recover(0.2, 100, 50, 20, 1e9), 10, 1e-9) && near(RegenMath.drain(50, 10, 0.2), 0, 1e-9),
                "regen: the pool limits it to share x pool and is used up by regen / share");
        check(RegenMath.bank(0, 50, 30) == 30 && RegenMath.bank(10, -5, 30) == 10 && RegenMath.bank(-3, 5, 30) == 5,
                "regen pool: banks the EU spent, capped, never negative");
    }

    /** One buggy tick: cost of the measured speed like the server, the pool, the recovery. Returns the regen. */
    private static double[] tick(VehicleType t, double[] s, double vBefore, double vAfter, boolean braking, double dy, double share) {
        // s = {energy, pool, spent, regen}
        double fill = 0, mul = 1;
        double cost = VehicleMath.cost(t, vAfter, fill, s[0] > 0, mul);
        s[0] = Math.max(0, s[0] - cost);
        s[2] += cost;
        double cruise = VehicleMath.cost(t, t.topSpeed, fill, true, mul);
        s[1] = RegenMath.bank(s[1], cost, RegenMath.poolCap(cruise, RegenMath.kinetic(t, t.topSpeed, fill, mul), false));
        double src = RegenMath.groundSource(t, vBefore, vAfter, braking, dy, Math.abs(vAfter), fill, mul, cruise);
        double r = RegenMath.recover(share, src, s[1], cruise, t.buffer - s[0]);
        s[1] = RegenMath.drain(s[1], r, share);
        s[0] += r;
        s[3] += r;
        return s;
    }

    private static void brakeBuggy() {
        VehicleType b = VehicleType.BUGGY;
        double share = RegenMath.share(b, false, 0.2);
        double[] s = {1000, 0, 0, 0};
        double v = 0;
        for (int i = 0; i < 40; i++) {                              // W: up to top speed
            double nv = Math.min(b.topSpeed, v + b.accel);
            tick(b, s, v, nv, false, 0, share);
            v = nv;
        }
        double spentRun = s[2], before = s[3];
        double[] coast = s.clone();
        for (int i = 0; i < 20; i++) {                              // Space: BRAKE 0.05 a tick to a stop
            double nv = Math.max(0, v - 0.05);
            tick(b, s, v, nv, true, 0, share);
            v = nv;
        }
        double got = s[3] - before;
        double kTop = RegenMath.kinetic(b, b.topSpeed, 0, 1);
        check(near(got, share * kTop, 0.5) && got > 30 && got <= share * spentRun,
                "regen buggy: braking from top speed gives back ~20 % of the run-up's EU (+" + Math.round(got * 10) / 10.0 + " of "
                        + Math.round(kTop) + " EU)");
        double cv = b.topSpeed;
        for (int i = 0; i < 20; i++) {                              // the same 20 ticks just rolling (no brake): nothing back
            double nv = Math.max(0, cv - 0.006);
            tick(b, coast, cv, nv, false, 0, share);
            cv = nv;
        }
        check(coast[3] == 0 && s[0] > coast[0], "regen buggy: rolling without the brake gives nothing back, braking ends with more EU ("
                + Math.round(s[0]) + " vs " + Math.round(coast[0]) + ")");
    }

    private static void hillCycle() {
        for (VehicleType t : new VehicleType[] {VehicleType.BUGGY, VehicleType.TRUCK, VehicleType.HOVER}) {
            double share = RegenMath.share(t, false, 0.5);          // the most the config allows
            double e0 = 100000;
            double[] s = {e0, 0, 0, 0};
            double v = 0;
            for (int i = 0; i < 200; i++) {
                double nv = Math.min(t.topSpeed, v + t.accel);
                double dy = i >= 60 && i < 100 ? 0.25 : i >= 120 && i < 160 ? -0.25 : 0;      // up 10 blocks, down 10 blocks
                boolean brake = i >= 160;
                if (brake) {
                    nv = Math.max(0, v - 0.05);
                }
                tick(t, s, v, nv, brake, dy, share);
                v = nv;
            }
            check(s[0] < e0 && s[3] > 0 && s[3] <= share * s[2] + 1e-6,
                    "regen hill (" + t.key + ", efficiency 0.5): up and down and a stop ends with less EU (" + Math.round(s[0] - e0)
                            + ", recovered " + Math.round(s[3]) + " of " + Math.round(s[2]) + " spent)");
        }
    }

    private static void fuzz() {
        Random rnd = new Random(1504);
        boolean ok = true;
        String why = "";
        for (VehicleType t : VehicleType.values()) {
            double eff = rnd.nextDouble() * 0.6;
            double share = RegenMath.share(t, rnd.nextBoolean(), eff);
            double cruise = Math.max(1, t.cruiseCost);
            double cap = RegenMath.poolCap(cruise, t.movement.flies ? 0 : RegenMath.kinetic(t, t.topSpeed, 0, 1), t.movement.flies);
            double pool = 0, spent = 0, got = 0, energy = t.buffer * rnd.nextDouble();
            for (int i = 0; i < 20000; i++) {
                double c = rnd.nextInt(4) == 0 ? 0 : rnd.nextDouble() * cruise * 1.5;
                energy = Math.max(0, energy - c);
                spent += c;
                pool = RegenMath.bank(pool, c, cap);
                double src = t.movement.flies ? RegenMath.airSource(-rnd.nextDouble() * 3, rnd.nextBoolean(), rnd.nextBoolean(), cruise)
                        : RegenMath.groundSource(t, rnd.nextDouble() * 3, rnd.nextDouble() * 3, rnd.nextBoolean(), rnd.nextGaussian(),
                                rnd.nextDouble(), rnd.nextDouble(), 1, cruise);
                double r = RegenMath.recover(share, src, pool, cruise, t.buffer - energy);
                if (r < 0 || r > cruise * RegenMath.TICK_CAP + 1e-9 || energy + r > t.buffer + 1e-6) {
                    ok = false;
                    why = t.key + " tick " + i;
                }
                pool = RegenMath.drain(pool, r, share);
                energy += r;
                got += r;
            }
            if (got > share * spent + 1e-6) {
                ok = false;
                why = t.key + " got " + got + " > " + share * spent;
            }
        }
        check(ok, "regen fuzz: 20 000 random ticks a type - each tick capped, never past the buffer, never more than share x spent " + why);
    }

    private static void aircraft() {
        VehicleType p = VehicleType.PLANE;
        double cruise = FlightMath.cruiseCost(p, 0, 80, true);
        double sink = PlaneMath.GLIDE_SPEED / PlaneMath.GLIDE_RATIO;
        double share = RegenMath.share(p, true, 0.2);
        double glide = share * RegenMath.airSource(-sink, true, true, cruise);
        check(glide > 0.5 && glide < 0.25 * cruise, "regen plane glide (1:8 at " + PlaneMath.GLIDE_SPEED + " b/t): +" + RegenMath.fmt(glide)
                + " EU/t - modest, under a quarter of the cruise cost");
        VehicleType h = VehicleType.HELI;
        double hc = FlightMath.cruiseCost(h, 0, 80, false);
        double auto = RegenMath.share(h, false, 0.2) * RegenMath.airSource(-HeliMath.AUTO_SINK, true, true, hc);
        check(auto > 0.5 && auto < 0.25 * hc, "regen heli autorotation (" + HeliMath.AUTO_SINK + " b/t down): +" + RegenMath.fmt(auto) + " EU/t");
        check(RegenMath.airSource(-0.2, false, true, cruise) == 0 && RegenMath.airSource(-0.2, true, false, cruise) == 0
                        && RegenMath.airSource(0.2, true, true, cruise) == 0
                        && near(RegenMath.airSource(-5, true, true, cruise), RegenMath.potential(cruise, RegenMath.AIR_DY_CAP), 1e-9),
                "regen aircraft: nothing on the ground, under power or climbing; a dive counts at most 0.5 b/t");
        // climb 100 blocks on the motor (the pool fills with what it spent), glide all the way down: less back than spent
        double pool = 0, spent = 0, got = 0;
        double cap = RegenMath.poolCap(cruise, 0, true);
        for (int i = 0; i < 400; i++) {                             // climb: peak cost, 0.25 b/t
            double c = PlaneMath.cost(p, 1, true, 0.25, 80, 0, true);
            spent += c;
            pool = RegenMath.bank(pool, c, cap);
        }
        for (int i = 0; i < 100 / sink; i++) {
            double r = RegenMath.recover(share, RegenMath.airSource(-sink, true, true, cruise), pool, cruise, 1e12);
            pool = RegenMath.drain(pool, r, share);
            got += r;
        }
        check(got > 0 && got < 0.3 * spent, "regen plane: a 100-block glide gives back " + Math.round(got) + " EU of the climb's "
                + Math.round(spent) + " (" + Math.round(got * 100 / spent) + " %)");
    }

    private static void synced() {
        int w = RegenMath.pack(3.44, 0.2, true);
        check(near(RegenMath.rate(w), 3.4, 1e-9) && RegenMath.pct(w) == 20, "regen sync: 3.44 EU/t at 20 % -> 3.4 / 20");
        int off = RegenMath.pack(0, 0.2, false);
        int big = RegenMath.pack(1e9, 0.5, true);
        check(RegenMath.pct(off) == 0 && RegenMath.rate(off) == 0 && RegenMath.pct(big) == 50 && RegenMath.rate(big) > 1e6
                        && RegenMath.pack(Double.NaN, Double.NaN, true) == 0,
                "regen sync: off -> 0 %, huge rates clamp, NaN -> 0");
        check("3,4".equals(RegenMath.fmt(3.44)) && "0,8".equals(RegenMath.fmt(0.81)) && "27".equals(RegenMath.fmt(27.4)),
                "regen text: 3,4 / 0,8 / 27 (EU/t)");
    }
}
