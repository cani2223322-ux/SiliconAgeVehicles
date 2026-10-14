package com.sva.debug;

import java.util.Set;

import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerFctl;
import com.sva.vehicle.LinerGear;
import com.sva.vehicle.LinerHyd;
import com.sva.vehicle.LinerSystems;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 2 self-tests (plan-next §7: hydraulics, flight controls, gear and brakes) - pure logic on the dedicated
 * server, driven tick by tick: the pumps / PTU / RAT pressure model, the system-loss combinations against the surfaces
 * that work, the unpowered droop's timing, the spoilers (roll on the down-going wing, the speed brake, the ground
 * spoilers armed / touch-down / RTO), the gear on Green and the gravity extension, the brake systems' switch-over, the
 * parking brake on the accumulator, the autobrake LO / MED / MAX with the RTO, the anti-skid's stopping distances, the
 * brake temperatures' thresholds, the steering per hydraulics, the laws, the SD pages' data, the sync / save and the
 * lang keys.
 */
final class SelfTestLinerHydSVA {

    private SelfTestLinerHydSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        pumps();
        ptuRat();
        faults();
        losses();
        droop();
        spoilers();
        gear();
        brakes();
        autobrake();
        antiskid();
        temps();
        steering();
        laws();
        pages();
        syncSave();
        lang();
    }

    // ---------------------------------------------------------------- helpers

    static LinerSystems cold() {
        return SelfTestLinerSVA.cold();
    }

    static LinerSystems.Env env() {
        return SelfTestLinerSVA.env();
    }

    static int run(LinerSystems L, LinerSystems.Env e, int n, SelfTestLinerSVA.Cond c) {
        return SelfTestLinerSVA.run(L, e, n, c);
    }

    /** Both engines running at once (the old tests' instant start), the batteries and the ADIRS on. */
    static LinerSystems.Env running(LinerSystems L) {
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.ENG_MASTER1, 1);
        L.set(LinerControls.ENG_MASTER2, 1);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        e.instant = true;
        run(L, e, 2, null);
        e.instant = false;
        run(L, e, 40, null);
        return e;
    }

    /** A LinerHyd with these pressures (no pump ticked). */
    static LinerHyd hyd(double g, double b, double y) {
        LinerHyd h = new LinerHyd();
        h.psi[0] = g;
        h.psi[1] = b;
        h.psi[2] = y;
        return h;
    }

    /** LinerFctl inputs: everything working, the given hydraulics. */
    static LinerFctl.In fin(LinerHyd h) {
        LinerFctl.In f = new LinerFctl.In();
        f.hyd = h;
        f.ess = f.ac = true;
        return f;
    }

    static int ticksTo(LinerFctl f, LinerFctl.In in, int s, float target, int max) {
        for (int i = 1; i <= max; i++) {
            f.tick(in);
            if (Math.abs(f.angle[s] - target) < 0.05F) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- pumps

    private static void pumps() {
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        run(L, e, 5, null);
        boolean dark = L.hyd.psi[0] == 0 && L.hyd.psi[1] == 0 && L.hyd.psi[2] == 0 && L.hyd.accu > 2800 && L.hyd.parkHolds() && L.gear.parkHold;
        // ground power + the Yellow electric pump (no engines): Yellow up, Blue not (its pump wants an engine), Green by the PTU
        e.extAvail = true;
        L.set(LinerControls.EXT_PWR, 1);
        L.set(LinerControls.HYD_YEL, 1);
        int t = run(L, e, 200, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.hyd.psi[LinerHyd.Y] >= LinerHyd.NOMINAL;
            }
        });
        run(L, e, 60, null);
        check(dark && t > 0 && t <= 30 && L.hyd.elecY && L.hyd.psi[LinerHyd.B] == 0 && L.hyd.psi[LinerHyd.G] > 2500 && !L.hyd.edp1,
                "liner HYD: cold - no pressure, the accumulator holds the parking brake (" + Math.round(L.hyd.accu) + " psi); ground power +"
                        + " ЭЛ. НАСОС ЖЁЛТАЯ - Yellow 3000 psi in " + t + " ticks, Green " + Math.round(L.hyd.psi[0]) + " by the PTU, Blue 0 (no engine)");
        // both engines: the EDPs and the Blue electric pump
        LinerSystems R = cold();
        running(R);
        boolean all = R.hyd.edp1 && R.hyd.edp2 && R.hyd.elecB && R.hyd.psi[0] >= LinerHyd.NOMINAL && R.hyd.psi[1] >= LinerHyd.NOMINAL
                && R.hyd.psi[2] >= LinerHyd.NOMINAL && R.hyd.mask() == 7 && R.hyd.faults == 0 && !R.hyd.ptu;
        // an EDP's pressure follows its N2 (0 under 20 %, full from 40 %)
        boolean share = LinerHyd.edpShare(15) == 0 && Math.abs(LinerHyd.edpShare(30) - 0.5) < 1e-9 && LinerHyd.edpShare(58) == 1;
        // the pump's push button out: its system bleeds off at DECAY a tick
        R.set(LinerControls.HYD_ENG1, 0);
        R.set(LinerControls.HYD_PTU, 0);
        LinerSystems.Env e2 = env();
        int bleed = run(R, e2, 600, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.hyd.psi[LinerHyd.G] < LinerHyd.LO_PSI;
            }
        });
        check(all && share && bleed > 100 && bleed < 160 && (R.hyd.faults & LinerHyd.F_EDP1) == 0 && (R.caut & LinerSystems.C_HYD_G_LO) != 0,
                "liner HYD: both engines - EDP 1 Green, EDP 2 Yellow, the Blue electric pump: 3000 / 3000 / 3000 psi; ДВ 1 НАСОС out - Green"
                        + " bleeds under 1450 psi in " + bleed + " ticks, «ГИДР З НИЗК ДАВЛ»");
    }

    private static void ptuRat() {
        // single-engine taxi (engine 2: Yellow): the PTU cycles - the A320's bark
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.set(LinerControls.ENG_MASTER1, 0);
        int barks = 0, on = 0;
        boolean memo = false;
        double gMin = 9999, gMax = 0;
        for (int i = 0; i < 600; i++) {
            L.tick(e);
            if (i > 200) {
                barks += (L.events & LinerSystems.E_PTU) != 0 ? 1 : 0;
                on += L.hyd.ptu ? 1 : 0;
                memo |= (L.memos(true) & LinerSystems.M_PTU) != 0;
                gMin = Math.min(gMin, L.hyd.psi[LinerHyd.G]);
                gMax = Math.max(gMax, L.hyd.psi[LinerHyd.G]);
            }
        }
        double period = barks > 0 ? 400.0 / barks : 0;
        check(barks >= 8 && on > 20 && on < 200 && gMin > LinerHyd.LO_PSI && gMax <= LinerHyd.NOMINAL && memo,
                "liner HYD: one engine (2) running - the PTU keeps Green " + Math.round(gMin) + ".." + Math.round(gMax) + " psi in bursts: " + barks
                        + " barks in 20 s (every " + Math.round(period) / 20.0 + " s, " + on + " ticks on)");
        // the RAT: in flight with every generator lost - out by itself, Blue by the airspeed, the emergency electrics
        LinerSystems R = cold();
        LinerSystems.Env r = env();
        R.set(LinerControls.BAT1, 1);
        R.set(LinerControls.BAT2, 1);
        r.ground = false;
        r.airspeed = 1.3;
        r.height = 200;
        int out = run(R, r, 5, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.hyd.rat;
            }
        });
        boolean ev = (R.events & LinerSystems.E_RAT) != 0;
        int elec = run(R, r, 100, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.hyd.ratElec;
            }
        });
        double b = R.hyd.psi[LinerHyd.B];
        double bat = r.bat1;
        run(R, r, 40, null);
        boolean noDrain = r.bat1 >= bat && R.essOnly && R.powered();
        r.airspeed = 0.6;
        run(R, r, 200, null);
        double slow = R.hyd.psi[LinerHyd.B];
        // on the ground and stopped, the ground crew stows it
        r.ground = true;
        r.airspeed = 0;
        run(R, r, 2, null);
        check(out == 1 && ev && elec > 0 && elec < 40 && b >= 1500 && noDrain && slow < 600 && !R.hyd.rat,
                "liner HYD: all generators lost in flight - the RAT out at once, Blue " + Math.round(b) + " psi at 1.3 b/t (" + Math.round(slow)
                        + " at 0.6), its emergency generator keeps the essentials in " + elec + " ticks (the batteries not drained); stowed on the ground");
    }

    private static void faults() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.hyd.leak[LinerHyd.G] = LinerHyd.LEAK_RATE;
        int low = run(L, e, 2000, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return (s.caut & LinerSystems.C_HYD_G_QTY) != 0;
            }
        });
        int lp = run(L, e, 400, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return (s.caut & LinerSystems.C_HYD_G_LO) != 0;
            }
        });
        boolean lamps = L.faultLamp(LinerControls.HYD_ENG1) && L.faultLamp(LinerControls.HYD_PTU) && L.autoPage() == 4 && L.masterCaut;
        L.hyd.refill();
        run(L, e, 40, null);
        boolean back = L.hyd.psi[LinerHyd.G] >= LinerHyd.NOMINAL && (L.caut & (LinerSystems.C_HYD_G_QTY | LinerSystems.C_HYD_G_LO)) == 0
                && !L.hyd.needsFluid();
        check(low > 500 && low < 1000 && lp > 0 && lp < 160 && lamps && back,
                "liner HYD: a Green leak - «ГИДР З НИЗК УРОВ» after " + Math.round(low / 2.0) / 10.0 + " s, the pump cavitates - «НИЗК ДАВЛ» "
                        + lp + " ticks later, ОТКАЗ on ДВ 1 НАСОС and PTU, the SD shows ГИДР; «Гидрожидкость» refills - pressure back");
        // overheat: a pump on a low reservoir heats the fluid
        LinerSystems H = cold();
        LinerSystems.Env h = running(H);
        H.hyd.qty[LinerHyd.Y] = 2;
        int hot = run(H, h, 6000, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return (s.caut & LinerSystems.C_HYD_Y_HOT) != 0;
            }
        });
        check(hot > 0 && H.faultLamp(LinerControls.HYD_ENG2) && H.faultLamp(LinerControls.HYD_YEL),
                "liner HYD: Yellow pumping on a low reservoir - «ГИДР Ж ПЕРЕГРЕВ» after " + hot / 20 + " s, ОТКАЗ on its pumps");
        // two systems lost: the red warning
        LinerSystems D = cold();
        LinerSystems.Env d = running(D);
        D.set(LinerControls.HYD_ENG1, 0);
        D.set(LinerControls.HYD_ENG2, 0);
        D.set(LinerControls.HYD_PTU, 0);
        run(D, d, 300, null);
        check((D.warn & LinerSystems.W_HYD_GY) != 0 && D.masterWarn && (D.warn & LinerSystems.W_HYD_GB) == 0,
                "liner HYD: Green and Yellow lost - the red «ГИДР З+Ж НИЗК ДАВЛ», MASTER WARN");
    }

    // ---------------------------------------------------------------- the flight controls

    private static String powered(LinerFctl f) {
        StringBuilder b = new StringBuilder();
        String[] n = {"AL", "AR", "EL", "ER", "R", "T", "s1", "s2", "s3", "s4", "s5", "S1", "S2", "S3", "S4", "S5"};
        for (int s = 0; s < LinerFctl.N; s++) {
            if (f.on(s)) {
                b.append(n[s]).append(' ');
            }
        }
        return b.toString().trim();
    }

    private static void losses() {
        double[][] cases = {{3000, 3000, 3000}, {3000, 0, 0}, {0, 3000, 0}, {0, 0, 3000}, {0, 3000, 3000}, {0, 0, 0}};
        String[] want = {"AL AR EL ER R T s1 s2 s3 s4 s5 S1 S2 S3 S4 S5", "AL AR EL R T s1 s5 S1 S5", "AL AR EL ER R s3 S3",
                "ER R T s2 s4 S2 S4", "AL AR EL ER R T s2 s3 s4 S2 S3 S4", ""};
        StringBuilder bad = new StringBuilder(), info = new StringBuilder();
        double[] roll = new double[cases.length], pitch = new double[cases.length];
        for (int i = 0; i < cases.length; i++) {
            LinerFctl f = new LinerFctl();
            f.tick(fin(hyd(cases[i][0], cases[i][1], cases[i][2])));
            String got = powered(f);
            roll[i] = f.rollAuthority();
            pitch[i] = f.pitchAuthority();
            if (!got.equals(want[i])) {
                bad.append(" [").append(i).append(": ").append(got).append(']');
            }
        }
        info.append("roll ").append(r2(roll[0])).append(" / G only ").append(r2(roll[1])).append(" / none ").append(r2(roll[5])).append(", pitch ")
                .append(r2(pitch[0])).append(" / Y only ").append(r2(pitch[3])).append(" / none ").append(r2(pitch[5]));
        check(bad.length() == 0 && Math.abs(roll[0] - 1) < 1e-9 && roll[5] == 0.2 && pitch[5] == 0.15 && pitch[3] < pitch[0] && roll[3] < roll[1],
                "liner F/CTL: system losses -> the powered surfaces (all / G / B / Y / B+Y / none) as the A320's map; authority " + info + bad);
        // the computers: both ELACs off - the ailerons droop (the SECs keep the elevator); SEC 1 off - spoilers 3 / 4 flat
        LinerFctl f = new LinerFctl();
        LinerFctl.In in = fin(hyd(3000, 3000, 3000));
        in.pb[0] = in.pb[1] = false;
        in.pb[2] = false;
        f.tick(in);
        check(!f.on(LinerFctl.AIL_L) && f.on(LinerFctl.ELEV_L) && !f.on(LinerFctl.SPL_L + 2) && !f.on(LinerFctl.SPL_R + 3) && f.on(LinerFctl.SPL_L + 4)
                        && f.law == LinerFctl.ALTN,
                "liner F/CTL: ELAC 1 + 2 off - no ailerons, the elevator on SEC 2, ALTN law; SEC 1 off - spoilers 3 / 4 lost");
    }

    static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static void droop() {
        LinerFctl f = new LinerFctl();
        LinerHyd h = hyd(3000, 3000, 3000);
        LinerFctl.In in = fin(h);
        in.instant = true;
        f.tick(in);
        in.instant = false;
        boolean neutral = f.angle[LinerFctl.AIL_L] == 0 && f.angle[LinerFctl.ELEV_R] == 0;
        h.psi[0] = h.psi[1] = h.psi[2] = 0;
        int dAil = ticksTo(f, in, LinerFctl.AIL_L, LinerFctl.DROOP, 200);
        boolean elevDown = Math.abs(f.angle[LinerFctl.ELEV_L] - LinerFctl.DROOP) < 0.05F && Math.abs(f.angle[LinerFctl.ELEV_R] - LinerFctl.DROOP) < 0.05F;
        // pressure back: they rise to neutral (fast at full pressure, slow at low)
        h.psi[0] = h.psi[1] = h.psi[2] = 3000;
        int up = ticksTo(f, in, LinerFctl.AIL_L, 0, 200);
        h.psi[0] = h.psi[1] = h.psi[2] = 0;
        ticksTo(f, in, LinerFctl.AIL_L, LinerFctl.DROOP, 200);
        h.psi[0] = h.psi[1] = h.psi[2] = 1100;
        int upLow = ticksTo(f, in, LinerFctl.AIL_L, 0, 200);
        // full deflection at pressure: the F/CTL check
        h.psi[0] = h.psi[1] = h.psi[2] = 3000;
        in.roll = 1;
        int full = ticksTo(f, in, LinerFctl.AIL_R, -LinerFctl.AIL_MAX, 200);
        check(neutral && dAil >= 38 && dAil <= 42 && elevDown && up > 0 && up <= 10 && upLow > up * 2 && full > 0 && full <= 16
                        && Math.abs(f.angle[LinerFctl.AIL_L] - LinerFctl.AIL_MAX) < 0.05F,
                "liner F/CTL: no pressure - ailerons / elevators droop 15 deg TE down in " + dAil + " ticks (~2 s); pressure back - neutral in " + up
                        + " ticks (" + upLow + " at 1100 psi); full roll - the right aileron up 25, the left down 25 in " + full + " ticks");
    }

    private static void spoilers() {
        LinerFctl f = new LinerFctl();
        LinerFctl.In in = fin(hyd(3000, 3000, 3000));
        in.instant = true;
        in.roll = 1;
        f.tick(in);
        boolean roll = f.angle[LinerFctl.SPL_R] == 0 && f.angle[LinerFctl.SPL_R + 1] == LinerFctl.ROLL_SPL && f.angle[LinerFctl.SPL_R + 4] == LinerFctl.ROLL_SPL
                && f.angle[LinerFctl.SPL_L + 2] == 0;
        in.roll = -0.5F;
        f.tick(in);
        boolean rollL = f.angle[LinerFctl.SPL_L + 3] == LinerFctl.ROLL_SPL / 2 && f.angle[LinerFctl.SPL_R + 3] == 0;
        in.roll = 0;
        in.spdBrk = 2;
        f.tick(in);
        boolean half = f.angle[LinerFctl.SPL_L + 1] == LinerFctl.SPD_HALF && f.angle[LinerFctl.SPL_R + 3] == LinerFctl.SPD_HALF && f.angle[LinerFctl.SPL_L] == 0
                && f.angle[LinerFctl.SPL_R + 4] == 0 && f.speedBrakeWorks();
        in.spdBrk = 3;
        f.tick(in);
        boolean full = f.angle[LinerFctl.SPL_L + 2] == LinerFctl.SPD_FULL;
        // Yellow lost: 2 / 4 stay flat under the speed brake
        LinerFctl g = new LinerFctl();
        LinerFctl.In gi = fin(hyd(3000, 3000, 0));
        gi.instant = true;
        gi.spdBrk = 3;
        g.tick(gi);
        boolean y = g.angle[LinerFctl.SPL_L + 1] == 0 && g.angle[LinerFctl.SPL_L + 3] == 0 && g.angle[LinerFctl.SPL_L + 2] == LinerFctl.SPD_FULL;
        check(roll && rollL && half && full && y,
                "liner spoilers: roll on 2..5 of the DOWN-going wing (right roll - the right wing's), speed brake 1/2 - 2..4 at 20, ПОЛН 40;"
                        + " without Yellow 2 / 4 stay flat");
        // ground spoilers: armed + touch-down at idle; not armed - nothing; the RTO
        LinerFctl t = new LinerFctl();
        LinerFctl.In ti = fin(hyd(3000, 3000, 3000));
        ti.ground = false;
        ti.spdBrk = 0;
        t.tick(ti);
        ti.ground = true;
        ti.touchdown = true;
        ti.speed = 1.3;
        t.tick(ti);
        boolean td = t.gndSpl;
        ti.touchdown = false;
        for (int i = 0; i < 40; i++) {
            t.tick(ti);
        }
        boolean all50 = t.angle[LinerFctl.SPL_L] == LinerFctl.GND_SPL && t.angle[LinerFctl.SPL_R + 4] == LinerFctl.GND_SPL && t.groundSpoilersWork();
        ti.spdBrk = 1;
        t.tick(ti);
        boolean disarm = !t.gndSpl;
        LinerFctl n = new LinerFctl();
        LinerFctl.In ni = fin(hyd(3000, 3000, 3000));
        ni.ground = false;
        n.tick(ni);
        ni.ground = true;
        ni.touchdown = true;
        n.tick(ni);
        boolean notArmed = !n.gndSpl;
        LinerFctl r = new LinerFctl();
        LinerFctl.In ri = fin(hyd(3000, 3000, 3000));
        ri.spdBrk = 0;
        ri.throttle = 1;
        ri.speed = 0.5;
        r.tick(ri);
        ri.speed = 1.0;
        r.tick(ri);
        ri.throttle = 0;
        r.tick(ri);
        boolean rto = r.gndSpl && r.rto;
        LinerFctl q = new LinerFctl();
        LinerFctl.In qi = fin(hyd(3000, 3000, 3000));
        qi.spdBrk = 0;
        qi.throttle = 1;
        qi.speed = 0.5;
        q.tick(qi);
        qi.throttle = 0;
        q.tick(qi);
        boolean slow = !q.gndSpl;
        check(td && all50 && disarm && notArmed && rto && slow,
                "liner ground spoilers: armed (ВЗВЕД) + touch-down at idle - all five at 50; the lever out of ВЗВЕД - down; not armed - none;"
                        + " the RTO (idle from take-off power over 0.75 b/t) - out, under it - not");
    }

    // ---------------------------------------------------------------- gear

    private static void gear() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.set(LinerControls.GEAR, 0);
        run(L, e, 20, null);
        boolean wow = L.gear.pos == 0;                              // weight on wheels: stays down
        e.ground = false;
        e.airspeed = 1.5;
        e.height = 100;
        int up = run(L, e, 400, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.gear.pos >= 1;
            }
        });
        // Green lost: the lever down does nothing; the crank's 3 turns drop it in ~4 s, and it won't go up again
        L.set(LinerControls.HYD_ENG1, 0);
        L.set(LinerControls.HYD_PTU, 0);
        run(L, e, 300, null);
        L.set(LinerControls.GEAR, 1);
        run(L, e, 100, null);
        boolean stuck = L.gear.pos >= 1 && (L.caut & LinerSystems.C_GEAR_DISAGREE) != 0 && L.gearRedLamp();
        for (int i = 0; i < 3; i++) {
            L.control(LinerControls.GEAR_GRAV.index, LinerControls.A_PRIMARY);
        }
        int drop = run(L, e, 200, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.gear.downLocked();
            }
        });
        boolean memo = (L.memos(false) & LinerSystems.M_GRAVITY) != 0 && L.gear.gravity;
        L.set(LinerControls.HYD_ENG1, 1);
        L.set(LinerControls.GEAR, 0);
        run(L, e, 200, null);
        boolean stays = L.gear.downLocked();
        // with Green but no lever change on a cold liner: Green needed
        LinerSystems C = cold();
        LinerSystems.Env c = env();
        c.ground = false;
        C.set(LinerControls.GEAR, 0);
        run(C, c, 300, null);
        check(wow && up >= 199 && up <= 202 && stuck && drop >= 79 && drop <= 82 && memo && stays && C.gear.pos == 0,
                "liner gear: on Green - up in " + up + " ticks (10 s) only off the ground; Green lost - the lever down does nothing («ШАССИ НЕ ПО"
                        + " РЫЧАГУ», the red lamp); 3 crank turns - down by gravity in " + drop + " ticks, can't be retracted; no Green - never up");
    }

    // ---------------------------------------------------------------- brakes

    private static void brakes() {
        LinerGear g = new LinerGear();
        LinerGear.In in = new LinerGear.In();
        int[] modes = new int[5];
        double[][] p = {{3000, 3000, 3000}, {0, 3000, 3000}, {0, 0, 3000}, {0, 3000, 0}, {0, 0, 0}};
        double[] accu = {3000, 3000, 3000, 2900, 900};
        for (int i = 0; i < 5; i++) {
            in.hyd = hyd(p[i][0], p[i][1], p[i][2]);
            in.hyd.accu = accu[i];
            g.tick(in);
            modes[i] = g.mode;
        }
        in.hyd = hyd(3000, 3000, 3000);
        in.antiskidSw = false;
        g.tick(in);
        int noAs = g.mode;
        boolean asOff = !g.antiskid;
        check(modes[0] == LinerGear.B_NORM && modes[1] == LinerGear.B_ALTN && modes[2] == LinerGear.B_ALTN && modes[3] == LinerGear.B_ACCU
                        && modes[4] == LinerGear.B_NONE && noAs == LinerGear.B_ALTN && asOff,
                "liner brakes: NORMAL on Green, ALTERNATE on Yellow when Green is lost (or the anti-skid off - then without it), the"
                        + " accumulator alone, none under its pre-charge");
        // the parking brake: holds on the accumulator without any pump; drained - not; the Yellow electric pump recharges it
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        run(L, e, 3, null);
        boolean holds = L.gear.parkHold && L.gear.decel(false, 0.1, 1, true) == LinerGear.PARK_DECEL && L.gear.brakeL > 1500;
        L.hyd.accu = 300;
        run(L, e, 3, null);
        boolean drained = !L.gear.parkHold && L.gear.decel(false, 0.1, 1, true) == 0;
        e.extAvail = true;
        L.set(LinerControls.EXT_PWR, 1);
        L.set(LinerControls.HYD_YEL, 1);
        int t = run(L, e, 300, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.hyd.accu >= 2900;
            }
        });
        L.set(LinerControls.HYD_YEL, 0);
        run(L, e, 400, null);
        check(holds && drained && t > 0 && t < 100 && L.gear.parkHold && L.hyd.accu > 2900 && L.hyd.psi[LinerHyd.Y] < 1000,
                "liner parking brake: holds on the Yellow accumulator with no pump; drained (300 psi) - it doesn't; the Yellow electric pump"
                        + " recharges it in " + t + " ticks and it keeps holding after the pump stops");
        // alternate braking on the accumulator alone uses it up
        LinerGear a = new LinerGear();
        LinerGear.In ai = new LinerGear.In();
        ai.hyd = hyd(0, 0, 0);
        ai.hyd.accu = 3000;
        ai.speed = 1.0;
        ai.pedal = true;
        int stops = 0;
        do {
            a.tick(ai);
            stops++;
        } while (a.mode != LinerGear.B_NONE && stops < 2000);
        check(stops > 100 && stops < 2000 && ai.hyd.accu < LinerHyd.ACCU_PRE + 201,
                "liner brakes: on the accumulator alone - " + stops + " ticks of full braking (~" + stops / 20 + " s) before it is spent");
    }

    private static void autobrake() {
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        run(L, e, 2, null);
        L.control(LinerControls.ABRK_MED.index, LinerControls.A_PRIMARY);
        boolean refused = !L.on(LinerControls.ABRK_MED) && L.gear.abMode == 0;
        e = running(L);
        L.control(LinerControls.ABRK_LO.index, LinerControls.A_PRIMARY);
        L.control(LinerControls.ABRK_MED.index, LinerControls.A_PRIMARY);
        boolean one = L.on(LinerControls.ABRK_MED) && !L.on(LinerControls.ABRK_LO) && L.gear.abMode == 2 && (L.memos(true) & LinerSystems.M_AUTOBRK) != 0;
        // a landing: armed spoilers, the touch-down at idle - MED brakes 2 s later
        L.set(LinerControls.SPD_BRK, 0);
        L.set(LinerControls.PARK_BRK, 0);
        e.ground = false;
        e.airspeed = 1.4;
        run(L, e, 5, null);
        e.ground = true;
        e.speed = 1.35;
        e.throttle = 0;
        int act = run(L, e, 200, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.gear.abActive;
            }
        });
        run(L, e, 2, null);
        double med = L.gear.decel(false, 1.3, 1, false);
        boolean lamp = L.greenLamp(LinerControls.ABRK_MED) && L.gear.abDecel;
        e.pedal = true;
        run(L, e, 1, null);
        e.pedal = false;
        boolean cancel = L.gear.abMode == 0 && !L.on(LinerControls.ABRK_MED) && !L.gear.abActive;
        check(refused && one && act >= 40 && act <= 43 && Math.abs(med - LinerGear.AB_DECEL[2]) < 1e-9 && lamp && cancel,
                "liner autobrake: no arming without Green; one mode at a time; MED after the touch-down's ground spoilers in " + act
                        + " ticks at " + med + " b/t a tick (ТОРМОЖ. lit); the pedals disarm it");
        // MAX: the rejected take-off
        LinerSystems R = cold();
        LinerSystems.Env r = running(R);
        R.set(LinerControls.SPD_BRK, 0);
        R.control(LinerControls.ABRK_MAX.index, LinerControls.A_PRIMARY);
        R.set(LinerControls.PARK_BRK, 0);
        r.throttle = 1;
        r.speed = 0.3;
        run(R, r, 10, null);
        r.speed = 1.0;
        run(R, r, 5, null);
        boolean noRto = !R.gear.abActive && !R.fctl.gndSpl;
        r.throttle = 0;
        run(R, r, 1, null);
        check(noRto && R.fctl.gndSpl && R.gear.abActive && R.gear.decel(false, 1.0, 1, false) == LinerGear.AB_DECEL[3],
                "liner autobrake MAX: the take-off roll - nothing; the thrust to idle at 1.0 b/t - the ground spoilers and MAX braking at once (RTO)");
    }

    /** The roll-out (blocks) from v0 with this brake state on a grip mu: the gear's decel + the rolling friction. */
    static double stopRun(LinerGear g, double v0, double mu, boolean pedal) {
        double v = v0, d = 0;
        for (int i = 0; i < 20000 && v > 0.001; i++) {
            v -= g.decel(pedal, v, mu, false) + com.sva.vehicle.PlaneMath.ROLL;
            d += Math.max(0, v);
        }
        return d;
    }

    private static void antiskid() {
        LinerGear g = new LinerGear();
        LinerGear.In in = new LinerGear.In();
        in.hyd = hyd(3000, 3000, 3000);
        g.tick(in);
        double dry = stopRun(g, 1.3, LinerGear.MU_DRY, true), wet = stopRun(g, 1.3, LinerGear.MU_WET, true), ice = stopRun(g, 1.3, LinerGear.MU_ICE, true);
        in.antiskidSw = false;
        in.hyd = hyd(0, 3000, 3000);
        g.tick(in);
        double dryNo = stopRun(g, 1.3, LinerGear.MU_DRY, true), wetNo = stopRun(g, 1.3, LinerGear.MU_WET, true), iceNo = stopRun(g, 1.3, LinerGear.MU_ICE, true);
        check(dry < wet && wet < ice && dryNo > dry && wetNo / wet > dryNo / dry && iceNo / ice > dryNo / dry && dry > 30 && dry < 60,
                "liner anti-skid: full braking from 1.3 b/t (94 km/h) - dry " + Math.round(dry) + " / wet " + Math.round(wet) + " / ice " + Math.round(ice)
                        + " blocks; without anti-skid " + Math.round(dryNo) + " / " + Math.round(wetNo) + " / " + Math.round(iceNo)
                        + " (x" + Math.round(dryNo / dry * 100) / 100.0 + " / x" + Math.round(wetNo / wet * 100) / 100.0 + " / x"
                        + Math.round(iceNo / ice * 100) / 100.0 + " - it matters more off the dry: on ice the rolling friction carries much of it)");
    }

    /** A stop from v0 with the autobrake mode (0: the pedals) on a dry surface: the brakes' peak temperature. */
    static double hardStop(LinerGear g, LinerGear.In in, double v0, boolean pedal) {
        double v = v0;
        in.speed = v;
        in.pedal = pedal;
        for (int i = 0; i < 2000 && v > 0.001; i++) {
            in.speed = v;
            g.tick(in);
            v -= g.decel(pedal, v, 1, false) + com.sva.vehicle.PlaneMath.ROLL;
        }
        in.speed = 0;
        in.pedal = false;
        return g.hottest();
    }

    private static void temps() {
        LinerGear g = new LinerGear();
        LinerGear.In in = new LinerGear.In();
        in.hyd = hyd(3000, 3000, 3000);
        g.tick(in);
        // a MED landing roll, a MAX RTO from 1.25 b/t, a pedal stop
        g.abMode = 2;
        g.abActive = true;
        in.gndSpl = true;
        double med = hardStop(g, in, 1.35, false);
        g.service();
        g.abMode = 3;
        g.abActive = true;
        double rto = hardStop(g, in, 1.25, false);
        boolean hot = g.hot();
        // cooling from the RTO: to under 300 without the fans / with them (on AC)
        LinerGear c1 = new LinerGear(), c2 = new LinerGear();
        for (int w = 0; w < 4; w++) {
            c1.temp[w] = c2.temp[w] = 400;
        }
        LinerGear.In ci = new LinerGear.In();
        ci.hyd = hyd(3000, 3000, 3000);
        int t1 = 0, t2 = 0;
        while (c1.hot() && t1 < 100000) {
            c1.tick(ci);
            t1++;
        }
        ci.fan = ci.ac = true;
        while (c2.hot() && t2 < 100000) {
            c2.tick(ci);
            t2++;
        }
        // three hard stops in a row: the fuse plugs
        LinerGear f = new LinerGear();
        LinerGear.In fi = new LinerGear.In();
        fi.hyd = hyd(3000, 3000, 3000);
        f.tick(fi);
        double peak = 0;
        int stops = 0;
        while (f.flat == 0 && stops < 6) {
            peak = hardStop(f, fi, 1.3, true);
            stops++;
        }
        double roll = f.rollingMul();
        check(med > 150 && med < LinerGear.HOT_C && rto > LinerGear.HOT_C && rto < LinerGear.FUSE_C && hot && t2 * 3 < t1 && f.flat == 15 && stops >= 2
                        && stops <= 4 && roll > 2.5,
                "liner brake temps: a MED landing " + Math.round(med) + " C, a MAX RTO from 1.25 b/t " + Math.round(rto) + " C («ТОРМ ГОРЯЧ» over 300);"
                        + " back under 300 in " + t1 / 20 + " s, with the fans " + t2 / 20 + " s; " + stops + " full stops in a row - " + Math.round(peak)
                        + " C, the fuse plugs melt: all four tyres flat (rolling drag x" + roll + ")");
        // the take-off config warning: hot brakes and take-off thrust
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        for (int w = 0; w < 4; w++) {
            L.gear.temp[w] = 350;
        }
        e.throttle = 0.9;
        run(L, e, 2, null);
        check((L.warn & LinerSystems.W_TO_BRK) != 0 && (L.caut & LinerSystems.C_BRK_HOT) != 0 && L.faultLamp(LinerControls.BRK_FAN),
                "liner brake temps: hot brakes and the thrust levers up - the red «КОНФ ВЗЛ: ТОРМ ГОРЯЧ», ГОРЯЧ on ВЕНТ. ТОРМ.");
    }

    private static void steering() {
        LinerGear g = new LinerGear();
        LinerGear.In in = new LinerGear.In();
        in.hyd = hyd(3000, 3000, 3000);
        g.tick(in);
        double tiller = g.steerAngle(1, 0.1), pedals = g.steerAngle(1, 1.0), slow = g.turnRate(1, 0.1, 1.1), fast = g.turnRate(1, 1.0, 1.1);
        boolean nws = g.nws;
        in.tow = true;
        g.tick(in);
        double tow = g.turnRate(1, 0.1, 1.1);
        boolean towOff = !g.nws && g.steerAngle(1, 0.1) == 0;
        in.tow = false;
        in.hyd = hyd(3000, 3000, 0);
        g.tick(in);
        double noY = g.turnRate(1, 0.1, 1.1);
        in.hyd = hyd(0, 0, 0);
        in.hyd.accu = 0;
        g.tick(in);
        double none = g.turnRate(1, 0.1, 1.1);
        check(nws && tiller == 75 && pedals == 6 && slow > 1.5 && fast < 1.0 && towOff && Math.abs(tow - slow * LinerGear.DIFF_SHARE) < 1e-9
                        && Math.abs(noY - tow) < 1e-9 && none == 0 && g.diffDrag(1) == 0,
                "liner steering: on Yellow - the tiller 75 deg slow (" + Math.round(slow * 100) / 100.0 + " deg/t at 0.1 b/t), the pedals 6 deg fast ("
                        + Math.round(fast * 100) / 100.0 + " at 1.0); the tow switch or no Yellow - differential braking only ("
                        + Math.round(noY * 100) / 100.0 + "); no brakes - none");
    }

    private static void laws() {
        int all = LinerFctl.C_ELAC1 | LinerFctl.C_ELAC2 | LinerFctl.C_SEC1 | LinerFctl.C_SEC2 | LinerFctl.C_SEC3;
        boolean ok = LinerFctl.law(all, 3, 3, 0, false) == LinerFctl.NORMAL && LinerFctl.law(all & ~3, 3, 3, 0, false) == LinerFctl.ALTN
                && LinerFctl.law(all, 1, 3, 0, false) == LinerFctl.ALTN && LinerFctl.law(all, 3, 1, 0, false) == LinerFctl.ALTN
                && LinerFctl.law(all, 3, 3, 2, false) == LinerFctl.ALTN && LinerFctl.law(all, 3, 3, 2, true) == LinerFctl.DIRECT
                && LinerFctl.law(all, 0, 3, 0, false) == LinerFctl.DIRECT && LinerFctl.law(LinerFctl.C_SEC3, 3, 3, 0, false) == LinerFctl.DIRECT
                && LinerFctl.law(all, 3, 3, 1, true) == LinerFctl.NORMAL;
        LinerFctl f = new LinerFctl();
        LinerFctl.In in = fin(hyd(3000, 3000, 3000));
        f.tick(in);
        double n = f.lawGain();
        in.adr = 0;
        f.tick(in);
        // on a real liner: the ADIRS off - DIRECT; after the quick start's ADIRS - NORMAL
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        e.extAvail = true;
        L.set(LinerControls.EXT_PWR, 1);
        run(L, e, 3, null);
        int cold = L.fctl.law;
        running(L);
        check(ok && n == 1 && f.law == LinerFctl.DIRECT && f.lawGain() > n && cold == LinerFctl.DIRECT && L.fctl.law == LinerFctl.NORMAL,
                "liner laws: NORMAL; ALTN without both ELACs / with one ADR or IR / two hydraulics lost (DIRECT with the gear down);"
                        + " DIRECT without ADRs / pitch computers; ADIRS off - DIRECT, aligned - NORMAL; ALTN / DIRECT respond more directly");
    }

    // ---------------------------------------------------------------- SD pages, sync, save, lang

    private static void pages() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        int taxi = L.autoPage();
        e.roll = 1;
        run(L, e, 1, null);
        int check1 = L.autoPage();
        e.roll = 0;
        run(L, e, LinerSystems.FCTL_CHECK_TICKS, null);
        int after = L.autoPage();
        e.pitch = 1;
        run(L, e, 1, null);
        int again = L.autoPage();
        e.pitch = 0;
        e.ground = false;
        e.height = 500;
        e.airspeed = 1.6;
        run(L, e, 2, null);
        int high = L.autoPage();
        e.height = 120;
        run(L, e, 2, null);
        int low = L.autoPage();
        check(taxi == 9 && check1 == 7 && after == 9 && again == 9 && high == com.sva.vehicle.LinerEcam.P_CRZ && low == 9,
                "liner SD auto pages: taxiing - КОЛЁСА; the first stick deflection on the ground - УПР (the F/CTL check) for 30 s, not again;"
                        + " flying high - the cruise page (wave 3; wave 2 had ДВИГ); the gear lever down under 200 blocks - КОЛЁСА");
    }

    private static void syncSave() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.set(LinerControls.FLAPS, 2);
        run(L, e, 100, null);
        L.gear.temp[2] = 333;
        L.gear.flat = 4;
        L.hyd.qty[1] = 3.3;
        L.control(LinerControls.ABRK_LO.index, LinerControls.A_PRIMARY);
        run(L, e, 1, null);
        String s = L.pack();
        LinerSystems C = new LinerSystems();
        C.unpack(s);
        boolean same = C.pack().equals(s) && Math.abs(C.hyd.psi[0] - L.hyd.psi[0]) <= 5 && Math.abs(C.fctl.flapPos - L.fctl.flapPos) < 0.006
                && Math.abs(C.gear.temp[2] - L.gear.temp[2]) <= 3 && C.gear.flat == 4 && C.gear.abMode == 1 && C.hyd.edp1 && C.gear.nws
                && C.autoPage() == L.autoPage() && Math.abs(C.hyd.qty[1] - 3.3) < 0.06 && C.fctl.law == L.fctl.law;
        // the client's surfaces from the mirror: the same as the server's for the same stick
        C.clientTick(0.5F, 0, 0, 0, true, 0);
        for (int i = 0; i < 30; i++) {
            C.clientTick(0.5F, 0, 0, 0, true, 0);
        }
        boolean mirror = Math.abs(C.fctl.angle[LinerFctl.AIL_L] - 12.5F) < 0.01F && C.fctl.on(LinerFctl.SPL_R + 2);
        check(same && mirror && s.length() < SelfTestLinerW7SVA.SYNC_LIMIT,                // wave 7: the bound raised (was 760)
                "liner sync wave 2: the third part (" + s.length() + " chars in all) round-trips - pressures, flaps, brake temps, flat tyres, the"
                        + " autobrake, the steering, the SD page; a client's surfaces move from the mirror like the server's");
        NBTTagCompound t = new NBTTagCompound();
        L.writeNBT(t);
        LinerSystems R = new LinerSystems();
        R.readNBT(t);
        boolean back = Math.abs(R.hyd.qty[1] - 3.3) < 0.01 && R.gear.flat == 4 && Math.abs(R.gear.temp[2] - 333) < 1 && Math.abs(R.fctl.flapPos - L.fctl.flapPos) < 0.01
                && R.hyd.psi[0] == LinerHyd.NOMINAL;
        // a wave-1 save: the speed brake lever's positions move up one (ВЗВЕД in front)
        NBTTagCompound old = new NBTTagCompound();
        NBTTagCompound p = new NBTTagCompound();
        p.setByte(LinerControls.SPD_BRK.id, (byte) 1);
        p.setByte(LinerControls.FLAPS.id, (byte) 3);
        old.setTag(LinerSystems.K_POS, p);
        LinerSystems O = new LinerSystems();
        O.readNBT(old);
        check(back && O.at(LinerControls.SPD_BRK) == 2 && O.fctl.flapPos == 3 && LinerControls.SPD_BRK.def == 1,
                "liner save wave 2: quantities, flat tyres, brake temps, flaps; a wave-1 save's speed brake «1/2» stays 1/2 (ВЗВЕД added in front)");
    }

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (String k : LinerFctl.LAW_KEYS) {
            keys.add("sva.liner.law." + k);
        }
        for (String k : LinerGear.AB_KEYS) {
            keys.add("sva.liner.du.ab." + k);
        }
        for (String k : LinerGear.MODE_KEYS) {
            keys.add("sva.liner.du.bm." + k);
        }
        String[] du = {"gear", "accu", "manTrim", "hyd.g", "hyd.b", "hyd.y", "hyd.eng", "hyd.elec", "hyd.elecShort", "hyd.accu", "hyd.hot", "hyd.leak", "l",
                "fctl.spoilers", "fctl.ail", "fctl.law", "fctl.elev", "fctl.ths", "fctl.up", "fctl.dn", "fctl.rud", "wheel.antiskid", "wheel.nws",
                "wheel.nwsNorm", "wheel.nwsTow", "wheel.nwsOff", "wheel.nose", "wheel.left", "wheel.right", "wheel.down", "wheel.up",
                "wheel.transit", "wheel.gravity", "wheel.brakes", "wheel.flat", "wheel.hotLine", "wheel.fansOn", "wheel.fansOff", "wheel.autobrk",
                "wheel.decel", "wheel.fans", "wheel.mode", "wheel.park", "wheel.accu"};
        for (String k : du) {
            keys.add("sva.liner.du." + k);
        }
        for (String k : new String[] {"sva.liner.msg.ratOut", "sva.liner.msg.fusePlug", "sva.liner.msg.fluid.notLiner", "sva.liner.msg.fluid.full",
                "sva.liner.msg.fluid.done", "item.siliconagevehicles.batteryTool.hydFluid.name", "sva.tooltip.hydFluid", "sva.tooltip.hydFluid.details",
                "sva.tooltip.howto.hydFluid"}) {
            keys.add(k);
        }
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "liner lang wave 2: the laws, the SD pages' words, the messages and «Гидрожидкость» in ru and en" + miss);
    }
}
