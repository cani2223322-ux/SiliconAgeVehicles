package com.sva.debug;

import java.util.Set;

import com.sva.vehicle.LinerChecklist;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerFuel;
import com.sva.vehicle.LinerSystems;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 3 self-tests (plan-next §7: fuel, ECAM, checklists) - pure logic on the dedicated server, driven tick by tick:
 * the centre tank first and its pumps' automatic stop, the outer -> inner transfer, the refuel order, the crossfeed, the
 * gravity feed and the flame-out above its ceiling, a side run dry, the imbalance and its roll bias, the fuel used; the
 * ECAM's priorities, CLR / RCL / STS / EMER CANC, the masters, the action lines, the phases and their inhibition, the
 * T.O. CONFIG checks, T.O. / LDG MEMO, the automatic SD page; the checklists' ticks; the sync / save and the lang keys.
 */
final class SelfTestLinerFuelSVA {

    private SelfTestLinerFuelSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        centreFirst();
        transfer();
        refuel();
        crossfeed();
        gravity();
        sideEmpty();
        imbalance();
        fuelUsed();
        priority();
        panel();
        masters();
        actions();
        phases();
        toConfig();
        memos();
        pages();
        checklists();
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

    static LinerSystems.Env running(LinerSystems L) {
        return SelfTestLinerHydSVA.running(L);
    }

    static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    static boolean has(long bits, int id) {
        return (bits & 1L << id) != 0;
    }

    static void pump(LinerSystems L, int p, int pos) {
        L.set(LinerEcam.pump(p), pos);
    }

    /** In flight at this height / altitude, the thrust levers at `thr`. */
    static void fly(LinerSystems.Env e, double height, double alt, double thr) {
        e.ground = false;
        e.airspeed = 1.6;
        e.speed = 1.6;
        e.height = height;
        e.alt = alt;
        e.throttle = thr;
    }

    // ---------------------------------------------------------------- fuel

    private static void centreFirst() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        double l0 = L.tankL, r0 = L.tankR, c0 = L.tankC;
        run(L, e, 200, null);
        boolean ctr = L.fuel.runs(LinerFuel.P_C1) && L.fuel.runs(LinerFuel.P_C2) && L.tankL == l0 && L.tankR == r0 && L.tankC < c0
                && (L.fuel.src & (LinerFuel.S_CTR_L | LinerFuel.S_CTR_R)) == (LinerFuel.S_CTR_L | LinerFuel.S_CTR_R);
        double used = c0 - L.tankC;
        // the centre runs dry: its pumps stop by themselves (no ОТКАЗ), the wings feed evenly
        L.tankC = 0.5;
        run(L, e, 30, null);
        double l1 = L.tankL, r1 = L.tankR;
        run(L, e, 200, null);
        boolean stopped = L.tankC == 0 && !L.fuel.runs(LinerFuel.P_C1) && !L.fuel.runs(LinerFuel.P_C2) && L.fuel.lowPr == 0
                && !L.faultLamp(LinerControls.PUMP_C1) && L.tankL < l1 && L.tankR < r1 && near(l1 - L.tankL, r1 - L.tankR, 1e-6)
                && (L.ecam.active & levelBits(LinerEcam.M_FUEL_PUMP, 6)) == 0 && L.anyRunning();
        check(ctr && used > 15 && used < 25 && stopped,
                "liner FUEL: АВТО - the centre feeds both engines first (" + Math.round(used * 10) / 10.0 + " mB in 10 s at idle, the wings"
                        + " untouched); empty - Ц1 / Ц2 stop by themselves (no ОТКАЗ, no caution), the wings feed evenly");
        // the slats out: the centre pumps stay off (the A320's take-off on the wings)
        LinerSystems S = cold();
        LinerSystems.Env es = running(S);
        S.set(LinerControls.FLAPS, 1);
        run(S, es, 250, null);
        double cs = S.tankC, ls = S.tankL;
        run(S, es, 40, null);
        boolean slats = S.fctl.slatPos > 0.5 && !S.fuel.runs(LinerFuel.P_C1) && S.tankC == cs && S.tankL < ls;
        // ВРУЧН. on an empty centre: the pumps run dry - ОТКАЗ and the caution
        L.set(LinerControls.FUEL_MODE, 1);
        run(L, e, 3, null);
        boolean manual = L.faultLamp(LinerControls.PUMP_C1) && L.faultLamp(LinerControls.PUMP_C2) && has(L.ecam.active, LinerEcam.M_FUEL_PUMP + 2);
        check(slats && manual, "liner FUEL: slats out - the centre pumps wait (the wings feed); ВРУЧН. with the centre dry - ОТКАЗ on Ц1 / Ц2,"
                + " «ТОПЛ Ц НАСОС 1 НИЗК ДАВЛ»");
    }

    static long levelBits(int from, int n) {
        long m = 0;
        for (int i = 0; i < n; i++) {
            m |= 1L << (from + i);
        }
        return m;
    }

    private static void transfer() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.tankL = 2500;
        L.fuel.outerL = 2000;                                       // the inner cell at 500 - under INNER_LOW
        run(L, e, 1, null);
        boolean opened = L.fuel.xferL && !L.fuel.xferR && L.fuel.outerL < 2000;
        int t = run(L, e, 1200, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.fuel.outerL <= 0;
            }
        });
        boolean kept = near(L.tankL, 2500, 1e-6) && near(L.fuel.inner(LinerFuel.L), 2500, 1e-6);
        // a refuel shuts the valves and fills the outer cell first
        L.reconcile(L.tanks() + 4000, 48000);
        boolean shut = !L.fuel.xferL && L.fuel.outerL > 1900 && L.fuel.refuel > 0;
        check(opened && t > 400 && t < 600 && kept && shut,
                "liner FUEL: the left inner cell under " + Math.round(LinerFuel.INNER_LOW) + " mB - the transfer valve opens, the outer cell"
                        + " drains into the inner in " + t / 20 + " s (the wing's total kept); a refuel shuts it and fills the outer first");
    }

    private static void refuel() {
        LinerSystems L = new LinerSystems();
        L.reconcile(10000, 48000);
        boolean wings = near(L.tankL, 5000, 1e-6) && near(L.tankR, 5000, 1e-6) && L.tankC == 0 && near(L.fuel.outerL, 2520, 1e-6)
                && near(L.fuel.outerR, 2520, 1e-6);
        boolean memo = (L.memos(true) & LinerSystems.M_REFUEL) != 0 && (L.events & LinerSystems.E_REFUEL) != 0;
        L.reconcile(40000, 48000);
        boolean full = near(L.tankL, 16800, 1e-6) && near(L.tankR, 16800, 1e-6) && near(L.tankC, 6400, 1e-6);
        check(wings && memo && full, "liner FUEL: a refuel fills the wings first (each wing's outer cell first, then the inner), then the"
                + " centre; «ЗАПРАВКА H2» while it flows");
    }

    private static void crossfeed() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.tankC = 0;
        pump(L, LinerFuel.P_L1, 0);
        pump(L, LinerFuel.P_L2, 0);
        run(L, e, 5, null);
        double l0 = L.tankL;
        run(L, e, 40, null);
        boolean grav = L.tankL < l0 && (L.fuel.src & LinerFuel.S_GRAV_L) != 0 && (L.memos(true) & LinerSystems.M_GRAV_FEED) != 0;
        L.set(LinerControls.XFEED, 1);
        run(L, e, LinerFuel.XFEED_TICKS + 2, null);
        double l1 = L.tankL, r1 = L.tankR;
        run(L, e, 100, null);
        boolean x = L.fuel.xfeedOpen && L.greenLamp(LinerControls.XFEED) && L.tankL == l1 && L.tankR < r1 && L.eng[0].running() && L.eng[1].running()
                && (L.fuel.src & LinerFuel.S_XFEED_FLOW) != 0 && (L.memos(true) & LinerSystems.M_XFEED) != 0;
        double burn = r1 - L.tankR;
        check(grav && x && burn > 9 && burn < 11, "liner FUEL: the left pumps off - gravity feed (memo); КОЛЬЦЕВ. on - the valve open in "
                + LinerFuel.XFEED_TICKS / 20.0 + " s, the right wing feeds both engines (" + Math.round(burn * 10) / 10.0 + " mB in 5 s), the left"
                + " untouched");
    }

    private static void gravity() {
        // left pumps off, centre dry, above the gravity ceiling at take-off thrust: engine 1 starves and flames out
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.tankC = 0;
        pump(L, LinerFuel.P_L1, 0);
        pump(L, LinerFuel.P_L2, 0);
        fly(e, 500, 200, 1);
        int t = run(L, e, 400, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[0].fault == LinerSystems.F_FLAMEOUT;
            }
        });
        run(L, e, 2, null);
        boolean fail = t > 0 && L.eng[1].running() && has(L.ecam.shown, LinerEcam.M_ENG1_FAIL) && L.masterWarn;
        // the same under the ceiling - gravity holds; above it at idle - holds too
        LinerSystems U = cold();
        LinerSystems.Env u = running(U);
        U.tankC = 0;
        pump(U, LinerFuel.P_L1, 0);
        pump(U, LinerFuel.P_L2, 0);
        fly(u, 500, 120, 1);
        run(U, u, 400, null);
        LinerSystems I = cold();
        LinerSystems.Env i = running(I);
        I.tankC = 0;
        pump(I, LinerFuel.P_L1, 0);
        pump(I, LinerFuel.P_L2, 0);
        fly(i, 500, 200, 0);
        run(I, i, 400, null);
        check(fail && U.eng[0].running() && I.eng[0].running(),
                "liner FUEL: pumps off on a side - gravity feed works under the ceiling (Y " + Math.round(u.gravAlt) + "); above it at take-off"
                        + " thrust the engine flames out in " + t / 20.0 + " s («ДВ 1 ОТКАЗ», MASTER WARN), at idle it holds");
    }

    private static void sideEmpty() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.tankL = 0;
        pump(L, LinerFuel.P_C1, 0);
        pump(L, LinerFuel.P_C2, 0);
        int t = run(L, e, 200, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[0].fault == LinerSystems.F_FLAMEOUT;
            }
        });
        run(L, e, 2, null);
        boolean out = t > 0 && t <= LinerFuel.STARVE_TICKS + 2 && L.eng[1].running() && has(L.ecam.active, LinerEcam.M_FUEL_OUT_L);
        LinerSystems X = cold();
        LinerSystems.Env x = running(X);
        X.tankL = 0;
        pump(X, LinerFuel.P_C1, 0);
        pump(X, LinerFuel.P_C2, 0);
        X.set(LinerControls.XFEED, 1);
        x.instant = true;                                            // the valve at once (the starve counter is faster than its travel)
        run(X, x, 1, null);
        x.instant = false;
        run(X, x, 200, null);
        check(out && X.eng[0].running() && X.eng[1].running() && has(X.ecam.active, LinerEcam.M_FUEL_OUT_L),
                "liner FUEL: the left wing dry, the centre pumps off, КОЛЬЦЕВ. shut - engine 1 flames out in " + t + " ticks («ТОПЛ Л"
                        + " ВЫРАБОТКА»); with КОЛЬЦЕВ. open the right side feeds it");
    }

    private static void imbalance() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.tankR = 14000;
        run(L, e, 2, null);
        boolean caut = has(L.ecam.shown, LinerEcam.M_FUEL_IMBAL) && L.masterCaut && L.fuelRoll() < -0.4 && L.fuelRoll() > -0.5;
        int[] a0 = L.ecam.openActions(LinerEcam.M_FUEL_IMBAL);
        L.set(LinerControls.XFEED, 1);
        run(L, e, 1, null);
        int[] a1 = L.ecam.openActions(LinerEcam.M_FUEL_IMBAL);
        pump(L, LinerFuel.P_R1, 0);
        pump(L, LinerFuel.P_R2, 0);
        run(L, e, 1, null);
        int[] a2 = L.ecam.openActions(LinerEcam.M_FUEL_IMBAL);
        LinerSystems B = cold();
        boolean even = B.fuelRoll() == 0;
        check(caut && even && a0.length == 2 && a1.length == 1 && a1[0] == LinerEcam.A_LIGHT_PUMPS_OFF && a2.length == 0,
                "liner FUEL: L 16 800 / R 14 000 mB - «ТОПЛ ДИСБАЛАНС», the left wing heavy: roll bias " + Math.round(L.fuelRoll() * 100) / 100.0
                        + "; its actions «КОЛЬЦЕВ. ВКЛ», «НАСОСЫ ЛЁГК КРЫЛА ВЫКЛ» vanish as done");
    }

    private static void fuelUsed() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        double t0 = L.tanks(), u0 = L.fuel.usedTotal(), a0 = L.fuel.used[0], b0 = L.fuel.used[1];
        run(L, e, 600, null);
        double burnt = t0 - L.tanks(), fu = L.fuel.usedTotal() - u0;
        boolean match = near(burnt, fu, 1e-6) && near(L.fuel.used[0] - a0, 30, 0.5) && near(L.fuel.used[1] - b0, 30, 0.5);
        // a start on the ground counts from zero
        L.set(LinerControls.ENG_MASTER1, 0);
        run(L, e, 400, null);
        L.set(LinerControls.ENG_MODE, 2);
        L.set(LinerControls.ENG_MASTER1, 1);
        run(L, e, 2, null);
        check(match && L.fuel.used[0] == 0 && L.fuel.used[1] > 30, "liner FUEL: FU per engine = what the tanks lost (" + Math.round(fu)
                + " mB in 30 s at idle, 1 mB/s each); an engine start on the ground resets its FU");
    }

    // ---------------------------------------------------------------- ECAM

    private static void priority() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.set(LinerControls.GEN1, 0);
        L.fuel.faults = 1 << LinerFuel.P_R1 | 1 << LinerFuel.P_L1;
        L.set(LinerControls.HYD_ENG1, 0);
        L.set(LinerControls.HYD_ENG2, 0);
        L.set(LinerControls.HYD_PTU, 0);
        run(L, e, 300, null);
        int[] l = L.ecam.list();
        boolean red = l.length > 4 && l[0] == LinerEcam.M_HYD_GY && LinerEcam.LEVEL[l[0]] == LinerEcam.WARN;
        boolean sorted = true;
        for (int i = 1; i < l.length; i++) {
            int a = l[i - 1], b = l[i];
            sorted &= LinerEcam.LEVEL[a] > LinerEcam.LEVEL[b] || LinerEcam.LEVEL[a] == LinerEcam.LEVEL[b] && a < b;
        }
        boolean legacy = (L.warn & LinerSystems.W_HYD_GY) != 0 && (L.caut & LinerSystems.C_GEN1) != 0 && (L.caut & LinerSystems.C_HYD_G_LO) != 0;
        boolean table = LinerEcam.KEYS.length == LinerEcam.N && LinerSystems.WARN_KEYS.length == LinerEcam.LEGACY_WARN_END
                && LinerSystems.CAUT_KEYS.length == LinerEcam.LEGACY_CAUT_END - LinerEcam.FIRST_CAUT;
        for (int i = 0; i < LinerSystems.WARN_KEYS.length; i++) {
            table &= LinerSystems.WARN_KEYS[i].equals(LinerEcam.KEYS[i]);
        }
        for (int i = 0; i < LinerSystems.CAUT_KEYS.length; i++) {
            table &= LinerSystems.CAUT_KEYS[i].equals(LinerEcam.KEYS[LinerEcam.FIRST_CAUT + i]);
        }
        for (int id = 0; id < LinerEcam.N; id++) {
            // wave 4: the AUTO FLT messages have no SD page (-1)
            table &= LinerEcam.KEYS[id] != null && LinerEcam.ACT[id] != null && (LinerEcam.PAGE[id] >= 0 || id >= LinerEcam.M_AP_OFF)
                    && ((LinerEcam.LEVEL[id] == LinerEcam.WARN) == (id < LinerEcam.FIRST_CAUT) || id >= LinerEcam.M_AP_OFF);
        }
        check(red && sorted && legacy && table, "liner ECAM: one engine for all " + LinerEcam.N + " messages - red first («ГИДР З+Ж НИЗК ДАВЛ»), then"
                + " amber in the table's order (" + l.length + " lines); the wave 1-2 bits kept");
    }

    /** Two pump faults: CLR advances, STATUS after the last, CLR back; RCL; STS; EMER CANC. */
    private static void panel() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.fuel.faults = 1 << LinerFuel.P_L1 | 1 << LinerFuel.P_R1;
        run(L, e, 2, null);
        int top0 = L.ecam.top(), page0 = L.autoPage();
        L.control(LinerControls.ECAM_CLR.index, LinerControls.A_PRIMARY);
        run(L, e, 1, null);
        int top1 = L.ecam.top();
        L.control(LinerControls.ECAM_CLR.index, LinerControls.A_PRIMARY);
        run(L, e, 1, null);
        boolean sts = L.ecam.top() < 0 && L.ecam.stsAuto && L.autoPage() == LinerEcam.P_STS && L.ecam.stsSeen;
        L.control(LinerControls.ECAM_CLR.index, LinerControls.A_PRIMARY);
        run(L, e, 1, null);
        boolean back = !L.ecam.stsAuto && L.autoPage() == LinerEcam.P_WHEEL;
        check(top0 == LinerEcam.M_FUEL_PUMP && page0 == LinerEcam.P_FUEL && top1 == LinerEcam.M_FUEL_PUMP + 4 && sts && back,
                "liner ECAM: CLR takes the top message off - the next comes up (with its page ТОПЛ), after the last STATUS, CLR again - the"
                        + " automatic page");
        L.control(LinerControls.ECAM_RCL.index, LinerControls.A_PRIMARY);
        run(L, e, 1, null);
        boolean rcl = L.ecam.list().length == 2;
        L.control(LinerControls.ECAM[LinerEcam.P_STS].index, LinerControls.A_PRIMARY);
        boolean stsKey = L.autoPage() == LinerEcam.P_STS;
        L.control(LinerControls.ECAM[LinerEcam.P_STS].index, LinerControls.A_PRIMARY);
        L.control(LinerControls.ECAM_EMER.index, LinerControls.A_PRIMARY);
        run(L, e, 1, null);
        boolean canc = L.ecam.list().length == 1 && has(L.ecam.cancelled, LinerEcam.M_FUEL_PUMP) && L.ecam.hasStatus();
        L.control(LinerControls.ECAM_RCL.index, LinerControls.A_PRIMARY);
        run(L, e, 1, null);
        boolean restored = L.ecam.list().length == 2 && L.ecam.cancelled == 0;
        // nothing at all: RCL shows «НОРМА»
        LinerSystems N = cold();
        LinerSystems.Env n = running(N);
        N.control(LinerControls.ECAM_RCL.index, LinerControls.A_PRIMARY);
        run(N, n, 1, null);
        boolean normal = (N.memos(true) & LinerSystems.M_NORMAL) != 0;
        // ВСЕ cycles the pages, the checklists among them, then back to automatic
        int[] seen = new int[LinerEcam.ALL_CYCLE.length + 1];
        for (int i = 0; i < seen.length; i++) {
            N.control(LinerControls.ECAM[LinerEcam.P_ALL].index, LinerControls.A_PRIMARY);
            seen[i] = N.sdPage;
        }
        boolean cycle = seen[0] == LinerEcam.P_ENG + 1 && seen[LinerEcam.ALL_CYCLE.length - 1] == LinerEcam.P_CHECK + 1
                && seen[LinerEcam.ALL_CYCLE.length] == 0;
        check(rcl && stsKey && canc && restored && normal && cycle,
                "liner ECAM: RCL brings the cleared back; СТАТУС shows STATUS; АВАР ОТМЕНА cancels the top caution (STATUS lists it, RCL"
                        + " restores); RCL with nothing - «НОРМА»; ВСЕ cycles the pages (the checklist last), then automatic");
    }

    private static void masters() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.fuel.faults = 1 << LinerFuel.P_C1;
        run(L, e, 1, null);
        boolean caut = L.masterCaut && (L.events & LinerSystems.E_CHIME_CAUT) != 0 && L.faultLamp(LinerControls.PUMP_C1);
        L.control(LinerControls.MC_R.index, LinerControls.A_PRIMARY);
        run(L, e, 5, null);
        boolean out = !L.masterCaut && has(L.ecam.shown, LinerEcam.M_FUEL_PUMP + 2);
        L.fuel.faults |= 1 << LinerFuel.P_C2;
        run(L, e, 1, null);
        boolean again = L.masterCaut;
        // red: the chime repeats until MASTER WARN is pressed
        LinerSystems W = cold();
        LinerSystems.Env w = running(W);
        W.tankL = 0;
        pump(W, LinerFuel.P_C1, 0);
        fly(w, 300, 100, 0);
        run(W, w, 40, null);
        boolean warn = W.masterWarn && !W.ecam.silenced && has(W.ecam.shown, LinerEcam.M_ENG1_FAIL);
        W.control(LinerControls.MW_L.index, LinerControls.A_PRIMARY);
        run(W, w, 1, null);
        check(caut && out && again && warn && !W.masterWarn && W.ecam.silenced,
                "liner ECAM: a pump fault - MASTER CAUT and one chime; pressed - out, the message stays; a new caution lights it again; a red"
                        + " message - MASTER WARN with the repeating chime until pressed");
    }

    private static void actions() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.tankL = 0;
        pump(L, LinerFuel.P_C1, 0);
        fly(e, 300, 100, 0);
        run(L, e, 40, null);
        int[] a0 = L.ecam.openActions(LinerEcam.M_ENG1_FAIL);
        L.set(LinerControls.ENG_MODE, 2);
        run(L, e, 1, null);
        int[] a1 = L.ecam.openActions(LinerEcam.M_ENG1_FAIL);
        L.set(LinerControls.ENG_MASTER1, 0);
        run(L, e, 1, null);
        boolean gone = !has(L.ecam.active, LinerEcam.M_ENG1_FAIL);
        check(a0.length == 2 && a0[0] == LinerEcam.A_MODE_START && a0[1] == LinerEcam.A_MASTER1_OFF && a1.length == 1
                        && a1[0] == LinerEcam.A_MASTER1_OFF && gone && LinerEcam.ACT[LinerEcam.M_ENG1_FAIL].length == 3,
                "liner ECAM: «ДВ 1 ОТКАЗ» - its actions (РУД МГ already done at idle) «- РЕЖИМ ДВ ... ЗАПУСК», «- ДВ 1 MASTER ... ВЫКЛ» vanish"
                        + " as the pilot does them; the master off ends the message");
    }

    private static void phases() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        int p2 = L.ecam.phase;
        LinerSystems C = cold();
        LinerSystems.Env c = env();
        C.set(LinerControls.BAT1, 1);
        run(C, c, 2, null);
        int p1 = C.ecam.phase;
        // take-off power and the fast run: a pump fault waits (no chime) until the phase is over
        e.throttle = 1;
        e.speed = 1.0;
        L.fuel.faults = 1 << LinerFuel.P_R2;
        run(L, e, 2, null);
        boolean held = L.ecam.phase == LinerEcam.PH_TO && L.ecam.toFast && has(L.ecam.active, LinerEcam.M_FUEL_PUMP + 5)
                && !has(L.ecam.shown, LinerEcam.M_FUEL_PUMP + 5) && !L.masterCaut;
        // lift-off: phase 4 (still held), the climb-out over - 5 (shown, MASTER CAUT)
        e.ground = false;
        e.height = 10;
        e.airspeed = 1.5;
        run(L, e, 2, null);
        boolean p4 = L.ecam.phase == LinerEcam.PH_CLIMB_OUT && !has(L.ecam.shown, LinerEcam.M_FUEL_PUMP + 5);
        L.set(LinerControls.GEAR, 0);
        e.height = 200;
        run(L, e, 2, null);
        boolean p5 = L.ecam.phase == LinerEcam.PH_CRUISE && has(L.ecam.shown, LinerEcam.M_FUEL_PUMP + 5) && L.masterCaut;
        L.fuel.faults = 0;
        // the approach (gear down under the LDG MEMO height), the final, touch-down, the roll, taxi
        L.set(LinerControls.GEAR, 1);
        e.height = 40;
        run(L, e, 2, null);
        int p6 = L.ecam.phase;
        e.height = 8;
        run(L, e, 2, null);
        int p7 = L.ecam.phase;
        e.ground = true;
        e.height = 0;
        e.throttle = 0;
        e.speed = 1.0;
        run(L, e, 2, null);
        int p8 = L.ecam.phase;
        e.speed = 0.2;
        run(L, e, 2, null);
        int back = L.ecam.phase;
        check(p1 == LinerEcam.PH_POWER && p2 == LinerEcam.PH_TAXI && held && p4 && p5 && p6 == LinerEcam.PH_APPR && p7 == LinerEcam.PH_FINAL
                        && p8 == LinerEcam.PH_ROLLOUT && back == LinerEcam.PH_TAXI,
                "liner ECAM phases: 1 power, 2 taxi, 3 take-off power (above V80 the cautions wait), 4 lift-off (still), 5 climb / cruise (shown,"
                        + " MASTER CAUT), 6 approach, 7 final, 8 the landing roll, back to 2 under V80");
    }

    private static void toConfig() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        run(L, e, 1, null);
        boolean flaps = has(L.ecam.shown, LinerEcam.M_CFG_FLAPS) && L.masterWarn && !L.ecam.toCfgOk;
        L.set(LinerControls.FLAPS, 1);
        L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        run(L, e, 2, null);
        boolean good = !has(L.ecam.active, LinerEcam.M_CFG_FLAPS) && L.ecam.toCfgOk && (L.memos(true) & LinerSystems.M_TO_NORMAL) != 0;
        run(L, e, LinerEcam.TO_TEST_TICKS + 5, null);
        boolean kept = L.ecam.toCfgOk && !has(L.ecam.active, LinerEcam.M_CFG_FLAPS);
        // the speed brake out: the test lost; take-off thrust raises the warning by itself; the parking brake on at thrust
        L.set(LinerControls.SPD_BRK, 3);
        e.throttle = 1;
        run(L, e, 2, null);
        boolean spd = !L.ecam.toCfgOk && has(L.ecam.shown, LinerEcam.M_CFG_SPDBRK) && has(L.ecam.shown, LinerEcam.M_CFG_PARK);
        // the THS off its take-off setting
        LinerSystems T = cold();
        LinerSystems.Env t = running(T);
        T.set(LinerControls.FLAPS, 2);
        run(T, t, 200, null);
        T.fctl.angle[com.sva.vehicle.LinerFctl.THS] = 9;
        T.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        run(T, t, 1, null);
        check(flaps && good && kept && spd && has(T.ecam.active, LinerEcam.M_CFG_TRIM) && !has(T.ecam.active, LinerEcam.M_CFG_FLAPS),
                "liner ECAM T.O. CONFIG: flaps up - «КОНФИГ ВЗЛЁТ ЗАКР» (MASTER WARN); flaps 1 - «ТО КОНФИГ НОРМ», kept; the speed brake out at"
                        + " take-off thrust - «КОНФИГ ВЗЛЁТ ИНТЕРЦ» by itself, the parking brake - «... СТОЯН ТОРМ»; the THS off - «... СТАБ»");
    }

    private static void memos() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        run(L, e, 2, null);
        boolean shown = L.ecam.toMemoShown(L) && !L.ecam.ldgMemoShown();
        int m0 = L.ecam.toMemo(L);
        L.control(LinerControls.ABRK_MAX.index, LinerControls.A_PRIMARY);
        L.set(LinerControls.SEAT_BELTS, 0);
        L.set(LinerControls.SPD_BRK, 0);
        L.set(LinerControls.FLAPS, 1);
        L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        run(L, e, 2, null);
        int m1 = L.ecam.toMemo(L);
        // LDG MEMO on the approach
        LinerSystems D = cold();
        LinerSystems.Env d = running(D);
        fly(d, 40, 100, 0);
        run(D, d, 2, null);
        boolean ldg = D.ecam.ldgMemoShown() && !D.ecam.toMemoShown(D);
        int l0 = LinerEcam.ldgMemo(D);
        D.set(LinerControls.FLAPS, 4);
        D.set(LinerControls.SEAT_BELTS, 0);
        D.set(LinerControls.SPD_BRK, 0);
        D.gear.abMode = 2;                                          // wave 4: the LDG MEMO's autobrake line (LO / MED)
        int l1 = LinerEcam.ldgMemo(D);
        check(shown && m0 == 1 << LinerEcam.TM_NOSMOKE && m1 == (1 << LinerEcam.TO_KEYS.length) - 1 && ldg
                        && l0 == (1 << LinerEcam.LM_GEAR | 1 << LinerEcam.LM_NOSMOKE) && l1 == (1 << LinerEcam.LDG_KEYS.length) - 1,
                "liner ECAM memos: T.O. MEMO with both engines on the ground - АВТОТОРМ МАКС, РЕМНИ, НЕ КУРИТЬ, ИНТЕРЦ ВЗВЕД, ЗАКР, ТО КОНФИГ tick"
                        + " as set; LDG MEMO on the approach - ШАССИ, РЕМНИ, НЕ КУРИТЬ, ИНТЕРЦ, ЗАКРЫЛКИ 3 / FULL");
    }

    private static void pages() {
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        run(L, e, 3, null);
        int door = L.autoPage();
        L.set(LinerControls.APU_MASTER, 1);
        L.control(LinerControls.APU_START.index, LinerControls.A_PRIMARY);
        run(L, e, 80, null);
        int apu = L.autoPage();
        L.set(LinerControls.ENG_MODE, 2);
        run(L, e, 1, null);
        int start = L.autoPage();
        LinerSystems R = cold();
        LinerSystems.Env r = running(R);
        int taxi = R.autoPage();
        R.set(LinerControls.FLAPS, 1);                              // the take-off configuration (else the CONFIG warnings' page)
        R.set(LinerControls.PARK_BRK, 0);
        r.throttle = 1;
        run(R, r, 2, null);
        int to = R.autoPage();
        r.throttle = 0;
        R.fuel.faults = 1 << LinerFuel.P_L2;
        run(R, r, 2, null);
        int fail = R.autoPage();
        R.fuel.faults = 0;
        fly(r, 500, 150, 0.5);
        R.set(LinerControls.GEAR, 0);
        run(R, r, 2, null);
        int crz = R.autoPage();
        check(door == LinerEcam.P_DOOR && apu == LinerEcam.P_APU && start == LinerEcam.P_ENG && taxi == LinerEcam.P_WHEEL && to == LinerEcam.P_ENG
                        && fail == LinerEcam.P_FUEL && crz == LinerEcam.P_CRZ,
                "liner SD auto pages (sheet 13a): engines off on the ground - ДВЕРИ; the APU starting - ВСУ; ЗАПУСК - ДВИГ; taxi - КОЛЁСА;"
                        + " take-off thrust - ДВИГ; a fuel pump fault - ТОПЛ; flying - the cruise page");
    }

    // ---------------------------------------------------------------- checklists

    private static void checklists() {
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        int list0 = LinerChecklist.current(L);
        int d0 = LinerChecklist.doneBits(LinerChecklist.BEFORE_START, L);
        e = SelfTestLinerSVA.apuUp(L, false);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        L.set(LinerControls.BEACON, 0);
        run(L, e, 1, null);
        boolean before = LinerChecklist.complete(LinerChecklist.BEFORE_START, L);
        // engines running: after start; done by the pilot's settings
        L.set(LinerControls.ENG_MASTER1, 1);
        L.set(LinerControls.ENG_MASTER2, 1);
        e.instant = true;
        run(L, e, 2, null);
        e.instant = false;
        int list1 = LinerChecklist.current(L);
        L.set(LinerControls.APU_BLEED, 0);
        L.set(LinerControls.APU_MASTER, 0);
        L.set(LinerControls.PACK1, 1);
        L.set(LinerControls.PACK2, 1);
        L.set(LinerControls.FLAPS, 1);
        run(L, e, 300, null);
        boolean after = LinerChecklist.complete(LinerChecklist.AFTER_START, L);
        int list2 = LinerChecklist.current(L);
        int t0 = LinerChecklist.doneBits(LinerChecklist.BEFORE_TO, L);
        L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        L.control(LinerControls.ABRK_MAX.index, LinerControls.A_PRIMARY);
        L.set(LinerControls.SPD_BRK, 0);
        L.set(LinerControls.SEAT_BELTS, 0);
        L.set(LinerControls.STROBE, 0);
        L.set(LinerControls.LAND_L, 0);
        L.set(LinerControls.LAND_R, 0);
        L.set(LinerControls.PARK_BRK, 0);
        L.set(LinerControls.NOSE_LT, 0);
        run(L, e, 2, null);
        boolean to = LinerChecklist.complete(LinerChecklist.BEFORE_TO, L);
        int items = 0;
        for (String[] l : LinerChecklist.ITEMS) {
            items += l.length;
        }
        check(list0 == LinerChecklist.BEFORE_START && d0 == (1 << 4 | 1 << 6 | 1 << 7) && before && list1 == LinerChecklist.AFTER_START && after
                        && list2 == LinerChecklist.BEFORE_TO && (t0 & 1 << 0) == 0 && to && items == 24,
                "liner checklists: ПЕРЕД ЗАПУСКОМ (cold: pumps, parking brake, door ticked), ПОСЛЕ ЗАПУСКА, ПЕРЕД ВЗЛЁТОМ - " + items
                        + " items tick by themselves as the cockpit is set; the page shows the list for now");
    }

    // ---------------------------------------------------------------- sync / save / lang

    private static void syncSave() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.fuel.faults = 1 << LinerFuel.P_L1 | 1 << LinerFuel.P_R2;
        L.set(LinerControls.XFEED, 1);
        L.tankL = 2400;
        L.fuel.outerL = 2000;
        run(L, e, 60, null);
        L.control(LinerControls.ECAM_CLR.index, LinerControls.A_PRIMARY);
        run(L, e, 1, null);
        String s = L.pack();
        LinerSystems C = new LinerSystems();
        C.unpack(s);
        boolean same = C.pack().equals(s) && java.util.Arrays.equals(C.ecam.list(), L.ecam.list()) && C.ecam.cleared == L.ecam.cleared
                && C.ecam.phase == L.ecam.phase && C.fuel.running == L.fuel.running && C.fuel.lowPr == L.fuel.lowPr && C.fuel.xfeedOpen
                && C.fuel.xferL && near(C.fuel.outerL, L.fuel.outerL, 0.6) && near(C.fuel.used[0], L.fuel.used[0], 0.6)
                && C.faultLamp(LinerControls.PUMP_L1) && C.greenLamp(LinerControls.XFEED) && C.autoPage() == L.autoPage()
                && java.util.Arrays.equals(C.ecam.openActions(LinerEcam.M_FUEL_PUMP + 5), L.ecam.openActions(LinerEcam.M_FUEL_PUMP + 5));
        NBTTagCompound t = new NBTTagCompound();
        L.writeNBT(t);
        LinerSystems R = new LinerSystems();
        R.readNBT(t);
        boolean saved = near(R.fuel.outerL, L.fuel.outerL, 0.6) && near(R.fuel.used[1], L.fuel.used[1], 0.6) && R.fuel.xferL;
        // a wave-2 string (three parts) still unpacks
        String old = s.substring(0, s.lastIndexOf('|'));
        LinerSystems O = new LinerSystems();
        O.unpack(old);
        check(same && saved && O.tankL == C.tankL && s.length() < SelfTestLinerW7SVA.SYNC_LIMIT, "liner ECAM / FUEL sync: the messages, the cleared ones, the phase, the"
                + " pumps, the cells, FU, the lamps round-trip (" + s.length() + " chars); saved: the cells, FU, the transfer; a wave-2 string reads");
    }

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (String k : LinerEcam.KEYS) {
            keys.add("sva.liner.msg." + k);
        }
        for (String k : LinerEcam.ACT_KEYS) {
            keys.add("sva.liner.act." + k);
        }
        for (String k : LinerEcam.INOP_KEYS) {
            keys.add("sva.liner.inop." + k);
        }
        for (String k : LinerEcam.LIM_KEYS) {
            keys.add("sva.liner.lim." + k);
        }
        for (String k : LinerEcam.PAGE_KEYS) {
            keys.add("sva.liner.sd." + k);
        }
        for (String k : LinerEcam.TO_KEYS) {
            keys.add("sva.liner.tom." + k);
        }
        for (String k : LinerEcam.LDG_KEYS) {
            keys.add("sva.liner.ldm." + k);
        }
        for (String k : LinerChecklist.LIST_KEYS) {
            keys.add("sva.liner.chk." + k);
        }
        for (String[] l : LinerChecklist.ITEMS) {
            for (String k : l) {
                keys.add("sva.liner.chk." + k);
            }
        }
        for (String k : LinerSystems.MEMO_KEYS) {
            keys.add("sva.liner.memo." + k);
        }
        for (String k : new String[] {"fu", "more", "toMemo", "ldgMemo", "inop", "m", "ms", "fuel.used", "fuel.xfeed", "fuel.left", "fuel.ctr",
                "fuel.right", "fuel.transfer", "fuel.gravity", "fuel.refuel", "fuel.fob", "fuel.imbal", "fuel.usedEng", "fuel.manual", "cond.temp",
                "cond.ck", "cond.fwd", "cond.aft", "cond.hot", "cond.cabAlt", "cond.vs", "cond.note", "door.oxy", "door.cockpit", "door.l1", "door.r1",
                "door.emer", "door.cargo", "door.aft", "door.stairs", "door.slides", "door.slidesArmed", "door.cabin", "door.pressed", "door.noPress",
                "sts.normal", "sts.cancelled", "chk.complete", "chk.count", "chk.hint", "crz.eng", "crz.air"}) {
            keys.add("sva.liner.du." + k);
        }
        StringBuilder miss = new StringBuilder();
        boolean pairs = true;
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        // the action / item lines are «WHAT|SETTING»
        java.util.Map<String, String> ruMap = LangKeysSVA.map(LangKeysSVA.RU);
        for (String k : keys) {
            if ((k.startsWith("sva.liner.act.") || k.startsWith("sva.liner.tom.") || k.startsWith("sva.liner.ldm.")
                    || k.startsWith("sva.liner.chk.") && !java.util.Arrays.asList(LinerChecklist.LIST_KEYS).contains(k.substring(14)))
                    && ruMap != null && ruMap.get(k) != null) {
                pairs &= ruMap.get(k).indexOf('|') > 0;
            }
        }
        check(miss.length() == 0 && pairs, "liner lang (wave 3): every ECAM message, action line, STATUS word, page, T.O. / LDG MEMO item,"
                + " checklist item, memo and display word in ru and en; the action / item lines as WHAT|SETTING" + miss);
    }
}
