package com.sva.debug;

import java.util.Set;

import com.sva.entity.LinerGroundSVA;
import com.sva.vehicle.LinerAdirs;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerFctl;
import com.sva.vehicle.LinerFire;
import com.sva.vehicle.LinerFms;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.SystemsMath;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 7 self-tests (plan-next §7: the SA-320's ADIRS, the flight control laws, the ground handling, the
 * passengers, the fire protection) - pure logic on the dedicated server: the ADIRUs' modes and alignment (НАВ with the
 * IRS INIT gate, movement, no alignment in flight, ПРОСТР's 3 s and the heading entry and its drift), «НА АКБ», the drift
 * and GPS, the pedestal's switching, the ADR vote (one rejected, two disagreeing), the unreliable airspeed per probe, the
 * PFD sides, ISIS, the AP / A/THR / protections / transponder's dependencies; the law matrix and the handling (ALTN's
 * low-speed stability, DIRECT's missing auto-trim, MECH, the alpha protection); the doors and the take-off block; the
 * chocks; the tow's geometry; the passengers' ZFW; the seat belts' rule; the fire; the widened ECAM; the sync; the lang.
 */
final class SelfTestLinerW7SVA {

    private SelfTestLinerW7SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        modes();
        attMode();
        power();
        driftGps();
        switching();
        vote();
        probes();
        dependencies();
        laws();
        handling();
        doors();
        chocks();
        tow();
        passengers();
        belts();
        fire();
        ecam();
        sync();
        lang();
        panels();
    }

    // ---------------------------------------------------------------- the «Пульты» screen's layout (device px)

    private static void panels() {
        double pw = LinerControls.PANEL_W[LinerControls.OVH], ph = LinerControls.PANEL_H[LinerControls.OVH];
        // 1080p at GUI scale 2: the area under the tabs; the overhead opens readable (9.5-unit labels at 12 px), larger than
        // the screen - it pans; 2160p at scale 4: it fits whole and is readable anyway
        double aw = 1920 - 16, ah = 1080 - 56 - 32;
        double k1 = com.sva.vehicle.PanelViewMath.initial(pw, ph, aw, ah, 8192);
        double fit1 = com.sva.vehicle.PanelViewMath.fit(pw, ph, aw, ah);
        boolean hd = Math.abs(k1 * com.sva.vehicle.PanelViewMath.LABEL_UNITS - 12) < 1e-6 && k1 > fit1;
        double aw4 = 3840 - 32, ah4 = 2160 - 112 - 64;
        double k4 = com.sva.vehicle.PanelViewMath.initial(pw, ph, aw4, ah4, 8192);
        boolean uhd = Math.abs(k4 - com.sva.vehicle.PanelViewMath.fit(pw, ph, aw4, ah4)) < 1e-9 && k4 * 9.5 >= 12
                && com.sva.vehicle.PanelViewMath.texSize(pw, k4) <= 8192;
        // the text floor: a 7-unit word at the fit still 12 px; never shrunk
        boolean floor = com.sva.vehicle.PanelViewMath.textPx(7, fit1, 12) == 12 && com.sva.vehicle.PanelViewMath.textPx(20, 2, 12) == 40;
        // the texture limit caps the scale; the zoom's bounds
        boolean cap = com.sva.vehicle.PanelViewMath.initial(pw, ph, 20000, 20000, 4096) * pw <= 4096 + 1e-6
                && com.sva.vehicle.PanelViewMath.zoom(k1, 40, pw, ph, aw, ah, 8192) <= com.sva.vehicle.PanelViewMath.maxZoom(pw, ph, aw, ah, 8192) + 1e-9
                && com.sva.vehicle.PanelViewMath.zoom(k1, -40, pw, ph, aw, ah, 8192) >= fit1 - 1e-9;
        // the hitboxes under zoom and pan: a control's centre on the screen maps back to it; a zoom keeps the point under the mouse
        LinerControls.Ctl ir = LinerControls.IR1;
        double[] o = com.sva.vehicle.PanelViewMath.clampPan(-300, -200, pw * k1, ph * k1, 8, 56, aw, ah);
        double[] d = com.sva.vehicle.PanelViewMath.toDevice(ir.cx, ir.cy, o[0], o[1], k1);
        double[] q = com.sva.vehicle.PanelViewMath.toPanel(d[0], d[1], o[0], o[1], k1);
        boolean hit = LinerControls.hit(LinerControls.OVH, (float) q[0], (float) q[1]) == ir;
        double k2 = com.sva.vehicle.PanelViewMath.zoom(k1, 2, pw, ph, aw, ah, 8192);
        double[] o2 = com.sva.vehicle.PanelViewMath.zoomAt(o[0], o[1], k1, k2, d[0], d[1]);
        double[] q2 = com.sva.vehicle.PanelViewMath.toPanel(d[0], d[1], o2[0], o2[1], k2);
        boolean kept = LinerControls.hit(LinerControls.OVH, (float) q2[0], (float) q2[1]) == ir && Math.abs(q2[0] - ir.cx) < 1e-6;
        // the pan: a larger panel leaves no gap (and whole pixels), a smaller one is centred
        double[] big = com.sva.vehicle.PanelViewMath.clampPan(500, 500, 3000, 2000, 8, 56, aw, ah);
        double[] small = com.sva.vehicle.PanelViewMath.clampPan(0, 0, 1000, 500, 8, 56, aw, ah);
        boolean pan = big[0] == 8 && big[1] == 56 && small[0] == Math.round(8 + (aw - 1000) / 2) && small[1] == Math.round(56 + (ah - 500) / 2)
                && o[0] == Math.round(o[0]);
        check(hd && uhd && floor && cap && hit && kept && pan, "liner panels screen: device-px scales (1080p opens readable at "
                + Math.round(k1 * 100) / 100.0 + " px/unit and pans; 2160p fits at " + Math.round(k4 * 100) / 100.0 + " and is readable); text never"
                + " under 12 px; the texture limit / zoom bounds; the hitboxes map back under zoom and pan, the zoom keeps the point under the mouse");
    }

    // ---------------------------------------------------------------- helpers

    /** Inputs: all three in НАВ, push buttons in, AC, standing on the ground, a 200-tick alignment, the gate open. */
    static LinerAdirs.In in() {
        LinerAdirs.In a = new LinerAdirs.In();
        for (int i = 0; i < 3; i++) {
            a.sel[i] = 1;
            a.kmh[i] = 200;
        }
        a.ac = true;
        a.ess = true;
        a.total = 200;
        return a;
    }

    static void ticks(LinerAdirs A, LinerAdirs.In a, int n) {
        for (int i = 0; i < n; i++) {
            A.tick(a);
        }
    }

    /** A running liner (both engines, AC, the ADIRS aligned at once as the older tests). */
    static LinerSystems.Env running(LinerSystems L) {
        LinerSystems.Env e = SelfTestLinerSVA.env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.ENG_MASTER1, 1);
        L.set(LinerControls.ENG_MASTER2, 1);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        e.instant = true;
        e.instantHyd = true;
        SelfTestLinerSVA.run(L, e, 5, null);
        e.instant = false;
        return e;
    }

    static void air(LinerSystems.Env e) {
        e.ground = false;
        e.height = 500;
        e.alt = 150;
        e.airspeed = 1.8;
        e.speed = 1.8;
        e.kmh = 130;
    }

    // ---------------------------------------------------------------- modes / alignment

    private static void modes() {
        LinerAdirs A = new LinerAdirs();
        LinerAdirs.In a = in();
        ticks(A, a, 100);
        boolean aligning = A.state[0] == LinerAdirs.S_ALIGN && A.irsState() == 1 && A.secondsLeft == 5 && A.insStatus() == SystemsMath.INS_ALIGN
                && !A.attOk(0) && A.lamp(0) == 1;
        ticks(A, a, 101);
        boolean nav = A.navOk(0) && A.navOk(1) && A.navOk(2) && A.irCount() == 3 && A.insStatus() == SystemsMath.INS_READY && A.lamp(0) == 0;
        check(aligning && nav, "liner ADIRS: НАВ aligns 10 s standing (ВЫСТ. steady, 5 s left at half way), then all three IRs give attitude,"
                + " heading and position (INS READY)");
        // the IRS INIT gate: held at the end, the amber wait, aligned the tick it is confirmed
        LinerAdirs G = new LinerAdirs();
        LinerAdirs.In g = in();
        g.gate = false;
        ticks(G, g, 260);
        boolean held = G.state[0] == LinerAdirs.S_ALIGN && G.waitInit && G.irsState() == 2 && G.lamp(0) == 2;
        g.gate = true;
        G.tick(g);
        // moving restarts; in the air no НАВ alignment
        LinerAdirs M = new LinerAdirs();
        LinerAdirs.In m = in();
        ticks(M, m, 120);
        m.moving = true;
        M.tick(m);
        boolean restart = M.align[0] == 0 && M.flash[0] > 0 && M.lamp(0) == 2;
        m.moving = false;
        m.ground = false;
        ticks(M, m, 400);
        boolean noAir = M.state[0] == LinerAdirs.S_ALIGN && !M.attOk(0);
        // the world tests' instant alignment (even in the air), the old self-tests' 0-tick one
        m.instant = true;
        M.tick(m);
        check(held && G.navOk(0) && restart && noAir && M.navOk(0), "liner ADIRS: the IRS INIT gate holds the end (\"ЖДЁТ\", the lamp flashing)"
                + " until confirmed; moving restarts it (ВЫСТ. flashes); no НАВ alignment in the air; the tests' instant one");
    }

    private static void attMode() {
        LinerAdirs A = new LinerAdirs();
        LinerAdirs.In a = in();
        a.sel[0] = 2;
        a.ground = false;
        a.hdg = 100;
        ticks(A, a, 59);
        boolean early = !A.attOk(0);
        A.tick(a);
        boolean att = A.attOk(0) && !A.hdgOk(0) && !A.navOk(0) && A.state[0] == LinerAdirs.S_ATT;
        boolean entered = A.enterHeading(0, 110, 100) && A.hdgOk(0) && Math.abs(A.hdgOff(0) - 10) < 1e-6;
        boolean refused = !A.enterHeading(1, 50, 100);              // a НАВ unit takes no heading
        ticks(A, a, 1200);
        double drift = Math.abs(A.hdgOff(0) - 10);
        check(early && att && entered && refused && Math.abs(drift - 1.5) < 0.01, "liner ADIRS ПРОСТР: the attitude after 3 s (also in the air), the"
                + " heading only once entered on IRS INIT (110 for a true 100), then it drifts " + Math.round(drift * 100) / 100.0 + " deg/min; no position");
        // a drifting ПРОСТР heading against a НАВ one: «НАВ IR РАСХОЖДЕНИЕ» once over 5 deg
        LinerAdirs D = new LinerAdirs();
        LinerAdirs.In d = in();
        d.total = 0;
        ticks(D, d, 2);
        d.sel[2] = 2;
        ticks(D, d, 61);
        D.enterHeading(2, 0, 0);
        boolean agree = !D.irDisagree;
        D.hdgOff[2] = 6;
        D.tick(d);
        check(agree && D.irDisagree, "liner ADIRS: the IRs' headings 6 deg apart - IR disagreement");
    }

    private static void power() {
        LinerAdirs A = new LinerAdirs();
        LinerAdirs.In a = in();
        a.ac = false;
        a.ess = false;
        A.tick(a);
        boolean dead = !A.anyPowered() && A.insStatus() == SystemsMath.INS_FAULT && A.state[0] == LinerAdirs.S_OFF;
        // the start on AC: «НА АКБ» for the battery test only
        LinerAdirs B = new LinerAdirs();
        LinerAdirs.In b = in();
        B.tick(b);
        boolean test = B.onBat;
        ticks(B, b, LinerAdirs.BAT_TEST + 1);
        boolean testOver = !B.onBat;
        // AC lost: on the batteries «НА АКБ»; ADIRU 2 off after 5 min
        b.ac = false;
        B.tick(b);
        boolean onBat = B.onBat && B.powered[1];
        ticks(B, b, LinerAdirs.BAT_2_LIMIT);
        boolean two = !B.powered[1] && B.powered[0] && B.powered[2];
        check(dead && test && testOver && onBat && two, "liner ADIRS power: none - off; «НА АКБ» lit at the start (the battery test) and on the"
                + " batteries; ADIRU 2 drops off them after 5 min (1 and 3 stay)");
    }

    private static void driftGps() {
        LinerAdirs A = new LinerAdirs();
        LinerAdirs.In a = in();
        a.total = 0;
        ticks(A, a, 12001);
        boolean drift = Math.abs(A.drift[0] - 1.0) < 0.01 && A.posError() == 0;
        a.gps = false;
        A.tick(a);
        check(drift && Math.abs(A.posError() - 1.0) < 0.01, "liner ADIRS: an aligned IR drifts 1 block in 10 min; GPS (always on) corrects"
                + " the position (error 0, GPS PRIMARY), without it the drift shows");
    }

    private static void switching() {
        boolean norm = LinerAdirs.source(0, LinerAdirs.SW_NORM) == 0 && LinerAdirs.source(1, LinerAdirs.SW_NORM) == 1;
        boolean capt3 = LinerAdirs.source(0, LinerAdirs.SW_CAPT3) == 2 && LinerAdirs.source(1, LinerAdirs.SW_CAPT3) == 1;
        boolean fo3 = LinerAdirs.source(0, LinerAdirs.SW_FO3) == 0 && LinerAdirs.source(1, LinerAdirs.SW_FO3) == 2;
        LinerAdirs A = new LinerAdirs();
        LinerAdirs.In a = in();
        a.total = 0;
        A.tick(a);
        A.fail(0, false);
        A.tick(a);
        boolean flags = !A.sideAdr(0, LinerAdirs.SW_NORM) && A.sideAdr(1, LinerAdirs.SW_NORM) && A.sideAdr(0, LinerAdirs.SW_CAPT3)
                && A.adrFault(0) && A.sideAtt(0, LinerAdirs.SW_NORM);
        a.sel[0] = 0;
        A.tick(a);
        boolean reset = !A.adrFail[0];
        check(norm && capt3 && fo3 && flags && reset, "liner ADIRS switching: КВС 1 / 2П 2, КВС НА 3 / 2П НА 3 put ADIRU 3 on that side; ADR 1 failed -"
                + " the captain's air data gone (ОТКАЗ), back with ВОЗД. ДАННЫЕ КВС НА 3; ВЫКЛ resets the fault");
    }

    private static void vote() {
        LinerAdirs A = new LinerAdirs();
        LinerAdirs.In a = in();
        a.total = 0;
        a.ground = false;
        A.tick(a);
        a.kmh[0] = 120;                                             // ADR 1 off the other two by 80 km/h
        ticks(A, a, LinerAdirs.VOTE_TICKS + 1);
        boolean rejected = A.rejected[0] && A.adrFault(0) && A.adrCount() == 2 && !A.disagree && !A.sideAdr(0, LinerAdirs.SW_NORM);
        a.kmh[2] = 260;                                             // the two left disagree
        ticks(A, a, LinerAdirs.VOTE_TICKS + 1);
        boolean dis = A.disagree && A.unreliable && !A.protOk() && !A.athrOk() && !A.apOk(1, 1, 1);
        a.kmh[2] = 200;
        ticks(A, a, LinerAdirs.VOTE_TICKS + 1);
        check(rejected && dis && !A.disagree, "liner ADR vote: one off by more than 30 km/h - rejected («НАВ ADR 1 ОТКАЗ»); the two left"
                + " disagreeing - «НАВ ADR РАСХОЖДЕНИЕ» (no AP / A/THR / protections), agreeing again - gone");
    }

    private static void probes() {
        // wave 6's iced probes feed their own ADR: probe 1 -> ADR 1, 3 -> ADR 3; two iced in use - unreliable
        // (the generators off: no probe heat to clear them; a cold air: no melting)
        LinerSystems L = SelfTestLinerSVA.cold();
        LinerSystems.Env e = running(L);
        L.set(LinerControls.GEN1, 0);
        L.set(LinerControls.GEN2, 0);
        e.iceAir.oat = -20;
        air(e);
        SelfTestLinerSVA.run(L, e, 3, null);
        L.ice.probe[0] = 1;
        L.ice.frozenKmh[0] = 60;
        e.kmh = 130;
        SelfTestLinerSVA.run(L, e, LinerAdirs.VOTE_TICKS + 2, null);
        boolean one = L.adirs.rejected[0] && !L.adirs.rejected[1] && L.ecam.isActive(LinerEcam.M_ADR1) && L.ecam.isActive(LinerEcam.M_IAS);
        L.ice.probe[2] = 1;
        L.ice.frozenKmh[2] = 60;
        SelfTestLinerSVA.run(L, e, LinerAdirs.VOTE_TICKS + 2, null);
        int[] acts = L.ecam.openActions(LinerEcam.M_IAS);
        boolean adr3Line = false, adr2Line = false;
        for (int x : acts) {
            adr3Line |= x == LinerEcam.A_ADR3_OFF;
            adr2Line |= x == LinerEcam.A_ADR2_OFF;
        }
        check(one && L.adirs.unreliable && adr3Line && !adr2Line, "liner unreliable airspeed: probe 1 frozen - ADR 1 rejected; probe 3 too -"
                + " «НЕДОСТ. СКОРОСТЬ» with «ADR 3 ВЫКЛ» (no line for the healthy ADR 2)");
    }

    private static void dependencies() {
        LinerSystems L = SelfTestLinerSVA.cold();
        LinerSystems.Env e = running(L);
        boolean ok = L.adirs.apOk(1, 1, 1) && L.adirs.athrOk() && L.adirs.protOk() && L.fctl.law == LinerFctl.NORMAL;
        // the ISIS lives on the batteries alone, whatever the ADIRS
        boolean isis = L.duPowered(LinerSystems.DU_ISIS);
        // all three ADRs off: no AP, no A/THR, no protections, no mode C altitude
        for (LinerControls.Ctl c : LinerSystems.ADR_PB) {
            L.set(c, 0);
        }
        SelfTestLinerSVA.run(L, e, 2, null);
        boolean none = !L.adirs.apOk(1, 1, 1) && !L.adirs.athrOk() && !L.adirs.protOk() && !L.adirs.sideAdr(0, 1) && L.fctl.law == LinerFctl.DIRECT
                && isis;
        check(ok && none, "liner ADIRS dependencies: AP / A/THR / protections with two IRs + two ADRs; all ADRs off - none, no mode C altitude"
                + " (sideAdr), DIRECT; ISIS on the battery bus regardless");
    }

    // ---------------------------------------------------------------- laws / handling

    private static void laws() {
        int all = 127;
        boolean m = LinerFctl.compute(all, 3, 3, 0, false, false, true) == LinerFctl.NORMAL
                && LinerFctl.compute(all, 3, 3, 0, false, true, true) == LinerFctl.ALTN
                && LinerFctl.compute(all, 3, 3, 0, true, true, true) == LinerFctl.DIRECT
                && LinerFctl.compute(all & ~3, 3, 3, 0, false, false, true) == LinerFctl.ALTN
                && LinerFctl.compute(all, 3, 3, 0, false, false, false) == LinerFctl.MECH
                && LinerFctl.compute(LinerFctl.C_SEC3, 3, 3, 0, false, false, true) == LinerFctl.DIRECT
                && LinerFctl.compute(all, 3, 0, 0, false, false, true) == LinerFctl.DIRECT;
        // ALTN 2 with one ADR, the computer loss on a real liner: ALTN; the gear down - DIRECT
        LinerSystems L = SelfTestLinerSVA.cold();
        LinerSystems.Env e = running(L);
        air(e);
        L.set(LinerControls.GEAR, 0);
        L.set(LinerControls.ELAC1, 0);
        L.set(LinerControls.ELAC2, 0);
        SelfTestLinerSVA.run(L, e, 3, null);
        boolean altn = L.fctl.law == LinerFctl.ALTN && !L.fctl.altn2 && L.ecam.isActive(LinerEcam.M_LAW_ALTN);
        L.set(LinerControls.ADR2, 0);
        L.set(LinerControls.ADR3, 0);
        SelfTestLinerSVA.run(L, e, 3, null);
        boolean altn2 = L.fctl.law == LinerFctl.ALTN && L.fctl.altn2;
        L.set(LinerControls.GEAR, 1);
        SelfTestLinerSVA.run(L, e, 3, null);
        boolean direct = L.fctl.law == LinerFctl.DIRECT && L.ecam.isActive(LinerEcam.M_LAW_DIRECT) && L.afs.msg == LinerAfs.MSG_TRIM
                && (L.ecam.limits() & LinerEcam.LIM_MAN_TRIM) != 0;
        check(m && altn && altn2 && direct, "liner laws: the matrix (NORMAL, ALTN by the ADR disagreement / ELACs, DIRECT, MECH without elevator"
                + " pressure); ELACs lost in flight - ALTN 1, one ADR - ALTN 2, the gear down - DIRECT («ТРИММ ВРУЧНУЮ»)");
    }

    private static void handling() {
        LinerFctl f = new LinerFctl();
        f.law = LinerFctl.NORMAL;
        double n = f.lawVy(1.0, 1.5, 0);
        f.law = LinerFctl.ALTN;
        double lowAltn = f.lawVy(1.0, 1.5, 0), heldAltn = f.lawVy(1.0, 1.5, 1);
        f.law = LinerFctl.DIRECT;
        f.trimSpeed = 1.6;
        double fast = f.lawVy(1.9, 1.5, 0), slow = f.lawVy(1.3, 1.5, 0);
        boolean trimmed = f.trimClick(true) && f.trimSpeed < 1.6 && f.manTrim > 0;
        f.law = LinerFctl.NORMAL;
        boolean noWheel = !f.trimClick(true);
        boolean alpha = LinerFctl.alphaStick(1, 1.4, 1.5, 1.3) < 1 && LinerFctl.alphaStick(1, 1.3, 1.5, 1.3) == 0 && LinerFctl.alphaStick(1, 1.6, 1.5, 1.3) == 1;
        check(n == 0 && lowAltn < 0 && heldAltn == 0 && fast > 0 && slow < 0 && trimmed && noWheel && alpha,
                "liner handling: NORMAL holds the path; ALTN noses down under alpha-prot (stick released); DIRECT: no auto-trim - faster than"
                        + " trimmed climbs, slower sinks, the trim wheel moves it; NORMAL's alpha protection takes the up stick away");
        // a DIRECT flight in the simulator: the speed up 0.3 b/t over the trimmed - it climbs by itself; NORMAL - it holds
        LinerSimSVA d = LinerSimSVA.flying(80, 1.6, 90, 0);
        for (LinerControls.Ctl c : LinerSystems.computers()) {
            d.L.set(c, 0);
        }
        d.L.afs.athr = false;
        d.run(3);
        boolean isDirect = d.L.fctl.law == LinerFctl.DIRECT;
        d.L.fctl.trimSpeed = 1.3;
        d.run(10);
        double dVy = d.b.motionY;
        LinerSimSVA nn = LinerSimSVA.flying(80, 1.6, 90, 0);
        nn.L.afs.athr = false;
        nn.run(10);
        check(isDirect && dVy > 0.01 && Math.abs(nn.b.motionY) < 0.01, "liner DIRECT in flight: trimmed 0.3 b/t slower than flown it pitches up"
                + " (vy " + Math.round(dVy * 1000) / 1000.0 + "), NORMAL holds level (" + Math.round(nn.b.motionY * 1000) / 1000.0 + ")");
    }

    // ---------------------------------------------------------------- ground

    private static void doors() {
        LinerSystems L = SelfTestLinerSVA.cold();
        LinerSystems.Env e = running(L);
        L.set(LinerControls.FLAPS, 1);
        L.fctl.flapPos = 1;
        SelfTestLinerSVA.run(L, e, 2, null);
        boolean opened = L.control(LinerControls.DOOR_CF.index, LinerControls.A_PRIMARY) && L.cargoF && L.doorsOpen() == LinerSystems.D_CARGO_F;
        L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        SelfTestLinerSVA.run(L, e, 2, null);
        boolean warn = L.ecam.isActive(LinerEcam.M_CFG_DOOR) && L.masterWarn && !L.ecam.toCfgOk && L.at(LinerControls.DOOR_CF) == 1
                && L.faultLamp(LinerControls.DOOR_CF);
        L.afs.athr = true;
        SelfTestLinerSVA.run(L, e, 2, null);
        boolean athrBlocked = !L.afs.athr;
        L.control(LinerControls.DOOR_CF.index, LinerControls.A_PRIMARY);
        L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        SelfTestLinerSVA.run(L, e, 2, null);
        boolean clear = !L.ecam.isActive(LinerEcam.M_CFG_DOOR) && L.doorsOpen() == 0;
        // no opening in the air
        air(e);
        SelfTestLinerSVA.run(L, e, 2, null);
        boolean air = !L.control(LinerControls.DOOR_R1.index, LinerControls.A_PRIMARY) && !L.doorR1;
        check(opened && warn && athrBlocked && clear && air, "liner doors: the cargo door from the cockpit (ОТКР lit); T.O. CONFIG with it open -"
                + " «КОНФИГ ВЗЛЁТ ДВЕРИ ОТКР», no A/THR; shut - clear; nothing opens in the air");
    }

    private static void chocks() {
        LinerSimSVA s = new LinerSimSVA();
        s.L.chocks = true;
        s.L.afs.lever = LinerAfs.DET_TOGA;
        s.run(100);
        double held = s.b.speed;
        s.L.control(LinerControls.TO_CONFIG.index, LinerControls.A_PRIMARY);
        s.run(2);
        boolean warn = s.L.ecam.isActive(LinerEcam.M_CFG_CHOCKS) && (s.L.memos(true) & LinerSystems.M_CHOCKS) != 0;
        s.L.chocks = false;
        s.run(60);
        check(held == 0 && warn && s.b.speed > 0.1, "liner chocks: at TOGA it does not roll (" + held + "), «КОНФИГ ВЗЛЁТ КОЛОДКИ» and the memo;"
                + " removed - it rolls (" + Math.round(s.b.speed * 100) / 100.0 + " b/t)");
    }

    private static void tow() {
        // the nose gear on the hitch, the main gear trailing: hitch 7.1 + 0.55 ahead (east) of a liner at the origin facing east
        double[] p = LinerGroundSVA.towPose(6.55, 0, -0.55, 0);
        boolean straight = Math.abs(p[0]) < 1e-6 && Math.abs(p[1]) < 1e-6 && Math.abs(com.sva.vehicle.CruiseMath.wrap(p[2] + 90)) < 1e-6;
        // the hitch moved 1 block north (-z): the nose follows, the aircraft turns toward it
        double[] q = LinerGroundSVA.towPose(7.1, -1, -0.55, 0);
        double nx = q[0] + q[3] * 6.55, nz = q[1] + q[4] * 6.55;
        boolean follows = Math.abs(nx - 7.1) < 1e-6 && Math.abs(nz + 1) < 1e-6;
        double turned = com.sva.vehicle.CruiseMath.wrap(q[2] + 90);
        boolean tug = LinerGroundSVA.tug(com.sva.vehicle.VehicleType.TRUCK) && !LinerGroundSVA.tug(com.sva.vehicle.VehicleType.LINER);
        check(straight && follows && turned < -5 && tug, "liner tow: the nose gear on the hitch, the main gear trailing at the wheelbase (pushback /"
                + " pull); the hitch 1 block left - the nose follows it, the heading turns " + Math.round(turned) + " deg; a truck is a tug");
    }

    private static void passengers() {
        LinerFms f = new LinerFms();
        double t0 = f.tow(0, 0), t8 = f.tow(8, 0);
        check(Math.abs(t8 - t0 - 8 * 0.08) < 1e-9, "liner passengers: each rider +80 kg in the ZFW / take-off weight (8 riders +"
                + Math.round((t8 - t0) * 1000) + " kg)");
    }

    private static void belts() {
        long[] a = LinerGroundSVA.beltRule(true, true, false, 100, 0);
        long[] held = LinerGroundSVA.beltRule(true, true, true, 101, a[1]);
        long[] released = LinerGroundSVA.beltRule(true, false, true, 102, a[1]);
        long[] second = LinerGroundSVA.beltRule(true, true, false, 110, a[1]);
        long[] late = LinerGroundSVA.beltRule(true, true, false, 400, a[1]);
        long[] ground = LinerGroundSVA.beltRule(false, true, false, 100, 0);
        check(a[0] == 1 && a[2] == 1 && held[0] == 1 && held[2] == 0 && released[0] == 0 && second[0] == 0 && late[0] == 1 && ground[0] == 0,
                "liner seat belts: in flight with the sign the first Shift is taken back (and told), held - still; a second press within 3 s"
                        + " unbuckles, a later one is a new first; on the ground / no sign - free");
    }

    private static void fire() {
        LinerSystems L = SelfTestLinerSVA.cold();
        LinerSystems.Env e = running(L);
        L.fire.ignite(LinerFire.ENG1);
        SelfTestLinerSVA.run(L, e, 2, null);
        boolean warn = L.ecam.isActive(LinerEcam.M_ENG1_FIRE) && L.masterWarn && L.fire.lamp(LinerFire.ENG1);
        boolean noSquib = !L.control(LinerControls.AGENT1_1.index, LinerControls.A_PRIMARY);
        L.set(LinerControls.ENG_MASTER1, 0);
        L.control(LinerControls.FIRE_ENG1.index, LinerControls.A_PRIMARY);
        SelfTestLinerSVA.run(L, e, 2, null);
        boolean armed = L.fire.pulled[LinerFire.ENG1] && L.whiteLamp(LinerControls.AGENT1_1) && !L.gen1On;
        e.random = 0.9;                                             // the first bottle fails
        L.control(LinerControls.AGENT1_1.index, LinerControls.A_PRIMARY);
        SelfTestLinerSVA.run(L, e, LinerFire.AGENT_TICKS + 2, null);
        boolean still = L.fire.burning[LinerFire.ENG1] && L.faultLamp(LinerControls.AGENT1_1);
        L.control(LinerControls.AGENT1_2.index, LinerControls.A_PRIMARY);
        SelfTestLinerSVA.run(L, e, LinerFire.AGENT_TICKS + 2, null);
        boolean out = !L.fire.burning[LinerFire.ENG1] && !L.ecam.isActive(LinerEcam.M_ENG1_FIRE);
        // the APU on the ground: shut down and its agent fired by itself
        LinerFire F = new LinerFire();
        F.ignite(LinerFire.APU);
        boolean[] h = new boolean[3];
        for (int i = 0; i < LinerFire.APU_AUTO + LinerFire.AGENT_TICKS + 3; i++) {
            F.tick(h, 0, 400, 0, true, true, 0.99);
        }
        // extreme overheat starts a fire
        LinerFire O = new LinerFire();
        O.tick(h, 1100, 0, 0, false, true, 0.5);
        check(warn && noSquib && armed && still && out && !F.burning[LinerFire.APU] && F.discharged(LinerFire.APU, 0) && O.burning[LinerFire.ENG1],
                "liner fire: «ДВ 1 ПОЖАР» (red, the handle lit); the agent needs the handle pulled (fuel / generator off, ПИРОПАТР.); a failed first"
                        + " bottle - the second puts it out; the APU on the ground discharges by itself; an EGT over 1000 C ignites");
    }

    // ---------------------------------------------------------------- ECAM, sync, lang

    private static void ecam() {
        boolean table = LinerEcam.N > 64 && LinerEcam.N <= LinerEcam.CAPACITY && LinerEcam.KEYS[LinerEcam.N - 1] != null && LinerEcam.ACTS < 64;
        LinerSystems L = SelfTestLinerSVA.cold();
        LinerSystems.Env e = running(L);
        air(e);
        L.adirs.fail(1, true);
        SelfTestLinerSVA.run(L, e, 2, null);
        boolean shown = L.ecam.isShown(LinerEcam.M_IR2) && L.ecam.shown2 != 0 && (L.ecam.inop() & LinerEcam.I_IR2) != 0 && L.masterCaut;
        int[] list = L.ecam.list();
        boolean listed = list.length > 0 && list[0] == LinerEcam.M_IR2;
        L.control(LinerControls.ECAM_CLR.index, LinerControls.A_PRIMARY);
        boolean cleared = (L.ecam.cleared2 & 1L << (LinerEcam.M_IR2 - 64)) != 0 && L.ecam.top() < 0;
        LinerSystems C = new LinerSystems();
        C.unpack(L.pack());
        check(table && shown && listed && cleared && C.ecam.isShown(LinerEcam.M_IR2) && C.ecam.cleared2 == L.ecam.cleared2,
                "liner ECAM widened: " + LinerEcam.N + " messages in two longs - «НАВ IR 2 ОТКАЗ» (id 68) shown, on STATUS, cleared by CLR,"
                        + " synced");
    }

    private static void sync() {
        LinerSystems L = SelfTestLinerSVA.cold();
        LinerSystems.Env e = running(L);
        L.cargoA = true;
        L.chocks = true;
        L.foPriority = true;
        L.adirs.fail(2, false);
        L.set(LinerControls.ATT_HDG, 0);
        SelfTestLinerSVA.run(L, e, 3, null);
        String s = L.pack();
        LinerSystems C = new LinerSystems();
        C.unpack(s);
        boolean same = C.pack().equals(s) && C.cargoA && C.chocks && C.foPriority && C.adirs.adrFault(2) && C.adirs.navOk(0)
                && C.adirs.lamp(0) == L.adirs.lamp(0) && C.at(LinerControls.ATT_HDG) == 0;
        NBTTagCompound t = new NBTTagCompound();
        L.writeNBT(t);
        LinerSystems R = new LinerSystems();
        R.readNBT(t);
        boolean saved = R.cargoA && R.chocks && R.adirs.state[0] == LinerAdirs.S_NAV && R.adirs.adrFail[2];
        check(same && saved && s.length() < SYNC_LIMIT, "liner sync wave 7: the ADIRS, doors, chocks, priority round-trip (" + s.length()
                + " chars < " + SYNC_LIMIT + "); the save keeps the aligned units, the doors, the chocks");
    }

    /** The liner's sync string's bound since wave 7 (the wave-1 test's 720 kept for its own state; 900 with everything set). */
    static final int SYNC_LIMIT = 900;

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (int id = 0; id < LinerEcam.N; id++) {
            keys.add("sva.liner.msg." + LinerEcam.KEYS[id]);
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
        for (String k : LinerSystems.MEMO_KEYS) {
            keys.add("sva.liner.memo." + k);
        }
        for (String k : LinerFctl.LAW_KEYS) {
            keys.add("sva.liner.law." + k);
        }
        for (LinerControls.Ctl c : LinerControls.all()) {
            keys.add(c.nameKey());
        }
        for (String w : LinerControls.words()) {
            keys.add("sva.liner.pos." + w);
        }
        String[] more = {"sva.liner.law.altn2", "sva.liner.memo.irsAlignSec", "sva.liner.du.fire", "sva.liner.du.flag.spd", "sva.liner.du.flag.alt",
                "sva.liner.du.spdDisagree1", "sva.liner.du.spdDisagree2", "sva.liner.du.insAlign", "sva.liner.du.insStill", "sva.liner.du.door.cargoF",
                "sva.liner.du.door.cargoA", "sva.liner.gui.trimMan", "sva.liner.gui.trimAuto", "sva.liner.gui.hint.trim", "sva.liner.msg.chocks.aim",
                "sva.liner.msg.chocks.in", "sva.liner.msg.chocks.out", "sva.liner.msg.cargoShut", "sva.liner.msg.cargoDoor", "sva.liner.msg.belts",
                "sva.liner.msg.priorityFo", "sva.liner.msg.priorityCapt", "sva.liner.msg.priorityOwn", "sva.liner.msg.towOn", "sva.liner.msg.towOff.tug",
                "sva.liner.msg.towOff.switch", "sva.liner.msg.towOff.park", "sva.liner.msg.towOff.chocks", "sva.liner.msg.towOff.thrust",
                "sva.liner.msg.towOff.bar", "sva.liner.msg.paCall", "sva.liner.msg.fire", "sva.mcdu.irs.navDrift", "sva.mcdu.irs.att",
                "sva.mcdu.irs.attHdg", "item.siliconagevehicles.batteryTool.chocks.name", "sva.tooltip.chocks", "sva.tooltip.chocks.details",
                "sva.tooltip.howto.chocks"};
        java.util.Collections.addAll(keys, more);
        StringBuilder bad = new StringBuilder();
        for (String k : keys) {
            if (!ru.contains(k) || !en.contains(k)) {
                bad.append(' ').append(k);
            }
        }
        check(bad.length() == 0, "liner wave 7 lang: every message / action / STATUS / memo / control / word in ru + en" + bad);
    }
}
