package com.sva.debug;

import java.util.Set;

import com.sva.block.IlsDataSVA;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerFctl;
import com.sva.vehicle.LinerGuide;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.VehicleType;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 4 self-tests (plan-next §7: the FCU, AP / FD, A/THR, the levers, the protections, the ILS, the autoland) -
 * pure logic on the dedicated server: the FCU's managed / selected windows and steps, the mode transitions, the AP / A/THR
 * engagement rules and their disconnects (aurals, warnings), the levers' detents and the reverse, the A/THR's modes per
 * lever, alpha floor, the bank protection, the speed bands, the V-speeds, the ILS geometry and capture cones, the
 * callouts, the ILS world data's save / load, the sync / save and the lang keys - and the flights of LinerSimSVA (the real
 * systems + LinerFlight): the heading, the altitude capture, V/S, the A/THR's speed, an approach with the autoland and the
 * roll-out, a go-around.
 */
final class SelfTestLinerAfsSVA {

    private SelfTestLinerAfsSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        fcu();
        engagement();
        disconnects();
        levers();
        athr();
        alphaFloor();
        bank();
        bands();
        ils();
        worldData();
        transitions();
        heading();
        altitude();
        vs();
        speed();
        approach();
        goAround();
        reversers();
        syncSave();
        lang();
    }

    static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }

    // ---------------------------------------------------------------- the FCU

    private static void fcu() {
        LinerSimSVA s = LinerSimSVA.flying(80, 1.6, 90, 0);
        LinerAfs F = s.L.afs;
        boolean dashes = F.spdMan && F.spdPrev == 0;
        int managed = (int) Math.round(F.spdT * 72);
        s.click(LinerControls.FCU_SPD, LinerControls.A_WHEEL_UP);
        int afterTurn = F.spd;
        boolean preview = F.spdMan && F.spdPrev > 0;
        s.click(LinerControls.FCU_SPD, LinerControls.A_FINE_UP);
        int fine = F.spd;
        s.click(LinerControls.FCU_SPD, LinerControls.A_SECONDARY);
        boolean selected = !F.spdMan;
        s.tick();
        boolean target = Math.abs(F.spdT * 72 - fine) < 0.01;
        s.click(LinerControls.FCU_SPD, LinerControls.A_PRIMARY);
        s.tick();
        check(dashes && afterTurn == managed + LinerAfs.SPD_STEP && preview && fine == afterTurn + 1 && selected && target && F.spdMan,
                "liner FCU SPD: managed - dashes and the dot; a turn shows the value (5 km/h a click, Shift 1), pulled - selected "
                        + fine + " km/h flown, pushed - managed again");
        // HDG: in HDG the turn changes the target at once; pushed with no route - nothing; ALT: the step selector 10 / 50
        s.click(LinerControls.FCU_AP1);
        int h0 = F.hdg;
        s.click(LinerControls.FCU_HDG, LinerControls.A_WHEEL_UP);
        s.click(LinerControls.FCU_HDG, LinerControls.A_FINE_DOWN);
        boolean hdg = F.lat == LinerAfs.L_HDG && F.hdg == (h0 + 4) % 360 && F.hdgPrev == 0;
        int a0 = F.alt;
        s.click(LinerControls.FCU_ALT, LinerControls.A_WHEEL_UP);
        int a1 = F.alt;
        s.L.control(LinerControls.FCU_ALT_STEP.index, LinerControls.A_PRIMARY);
        s.click(LinerControls.FCU_ALT, LinerControls.A_WHEEL_UP);
        int a2 = F.alt;
        s.click(LinerControls.FCU_ALT, LinerControls.A_FINE_DOWN);
        int a3 = F.alt;
        for (int i = 0; i < 20; i++) {
            s.click(LinerControls.FCU_ALT, LinerControls.A_WHEEL_UP);
        }
        boolean altMax = F.alt == LinerAfs.ALT_MAX;
        check(hdg && a1 == (a0 / 10 + 1) * 10 && a2 == (a1 / 50 + 1) * 50 && a3 == a2 - 1 && altMax,
                "liner FCU HDG / ALT: in HDG the knob turns the target (5 deg, Shift 1: " + h0 + " -> " + F.hdg + "); ALT steps to the next 10 ("
                        + a0 + " -> " + a1 + "), with the selector at 50 to the next 50 (" + a2 + "), Shift 1, at most " + LinerAfs.ALT_MAX);
        // V/S: pulled - V/S at the rate flown, turned 0,5 m/s, pushed - level off (V/S 0); the window's dashes otherwise
        boolean vsDash = F.ver != LinerAfs.V_VS && F.vsPrev == 0;
        s.click(LinerControls.FCU_VS, LinerControls.A_SECONDARY);
        boolean vsMode = F.ver == LinerAfs.V_VS;
        s.click(LinerControls.FCU_VS, LinerControls.A_WHEEL_DOWN);
        s.click(LinerControls.FCU_VS, LinerControls.A_WHEEL_DOWN);
        int v2 = F.vs10;
        s.click(LinerControls.FCU_VS, LinerControls.A_FINE_DOWN);
        int v3 = F.vs10;
        s.click(LinerControls.FCU_VS, LinerControls.A_PRIMARY);
        check(vsDash && vsMode && v2 == -10 && v3 == -11 && F.vs10 == 0 && F.ver == LinerAfs.V_VS,
                "liner FCU V/S: dashes until pulled; pulled - V/S at the rate flown (0), two clicks -1,0 m/s, Shift -0,1, pushed - level off (0)");
        // the baro: QNH turns 1 hPa, pulled STD, pushed QNH - the altitude shown
        s.click(LinerControls.BARO_L, LinerControls.A_WHEEL_UP);
        double shown = F.shownAlt(100);
        s.click(LinerControls.BARO_L, LinerControls.A_SECONDARY);
        boolean std = F.std && F.shownAlt(100) == 100;
        s.click(LinerControls.BARO_R, LinerControls.A_PRIMARY);
        check(Math.abs(shown - (100 + LinerAfs.QNH_M)) < 1e-9 && std && !F.std && F.qnh == 1014,
                "liner FCU baro: QNH 1014 shows " + f(shown) + " m at Y 100 (8,3 m a hPa), pulled - STD (Y), pushed - QNH");
        // TRK-FPA: the V/S window in FPA; the FMA's words
        s.click(LinerControls.FCU_TRK);
        check(F.trk && "FPA".equals(F.verText()) && "TRACK".equals(F.latText()), "liner FCU HDG-V/S / TRK-FPA: TRACK and FPA on the FMA");
        s.click(LinerControls.FCU_TRK);
    }

    // ---------------------------------------------------------------- engagement

    private static void engagement() {
        // on the ground: no AP; in the air without the INS: none; DIRECT law: none; ALTN: engages
        LinerSimSVA g = new LinerSimSVA();
        g.tick();
        g.click(LinerControls.FCU_AP1);
        boolean ground = !g.L.afs.apOn();
        LinerSimSVA n = LinerSimSVA.flying(80, 1.6, 90, 0);
        n.insReady = false;
        n.tick();
        n.click(LinerControls.FCU_AP1);
        boolean noIns = !n.L.afs.apOn();
        LinerSimSVA d = LinerSimSVA.flying(80, 1.6, 90, 0);
        for (LinerControls.Ctl c : LinerSystems.computers()) {
            d.L.set(c, 0);
        }
        d.tick();
        d.click(LinerControls.FCU_AP1);
        boolean direct = d.L.fctl.law == LinerFctl.DIRECT && !d.L.afs.apOn();
        LinerSimSVA a = LinerSimSVA.flying(80, 1.6, 90, 0);
        a.L.set(LinerControls.ELAC1, 0);
        a.L.set(LinerControls.ELAC2, 0);
        a.tick();
        a.click(LinerControls.FCU_AP1);
        boolean altn = a.L.fctl.law == LinerFctl.ALTN && a.L.afs.apOn();
        check(ground && noIns && direct && altn, "liner AP engagement: not on the ground, not without the INS aligned, not in DIRECT law; ALTN law - it engages");
        // AP 1 + AP 2 only with the approach armed; else AP 2 takes over from AP 1
        LinerSimSVA s = LinerSimSVA.flying(80, 1.6, 90, 0);
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_AP2);
        boolean swap = !s.L.afs.ap1 && s.L.afs.ap2 && s.L.afs.apWarn == 0;
        s.click(LinerControls.FCU_APPR);
        s.click(LinerControls.FCU_AP1);
        boolean both = s.L.afs.ap1 && s.L.afs.ap2 && s.L.afs.capability() == LinerAfs.CAP_CAT3_DUAL;
        s.click(LinerControls.FCU_APPR);
        boolean drop = s.L.afs.ap1 != s.L.afs.ap2 && s.L.afs.capability() == LinerAfs.CAP_NONE;
        // the engaged AP flies the modes it found: HDG (the heading flown) + ALT (level)
        LinerSimSVA e = LinerSimSVA.flying(80, 1.6, 47, 0);
        e.click(LinerControls.FCU_AP1);
        check(swap && both && drop && e.L.afs.lat == LinerAfs.L_HDG && e.L.afs.hdg == 47 && e.L.afs.ver == LinerAfs.V_ALT,
                "liner AP 1 + 2: only with APPR armed (CAT 3 DUAL) - else AP 2 takes over AP 1 silently; APPR off - one AP left; engaged in HDG"
                        + " (the heading flown) and ALT");
    }

    // ---------------------------------------------------------------- disconnects

    private static void disconnects() {
        // instinctive: the cavalry charge's minimum and the red warning (not the chime), the second press clears it
        LinerSimSVA s = LinerSimSVA.flying(80, 1.6, 90, 0);
        s.click(LinerControls.FCU_AP1);
        s.tick();
        s.click(LinerControls.AP_DISC_L);
        LinerAfs F = s.L.afs;
        boolean off = !F.apOn() && F.apWarn == LinerAfs.WARN_TICKS && F.cavalry == LinerAfs.CAVALRY_MIN;
        s.tick();
        boolean warn = (s.L.ecam.shown & 1L << LinerEcam.M_AP_OFF) != 0 && s.L.masterWarn && s.L.ecam.silenced
                && (s.L.events & LinerSystems.E_CHIME_WARN) == 0 && F.cavNote >= 0;
        int notes = 0;
        for (int i = 0; i < LinerAfs.CAVALRY_MIN + 10; i++) {
            s.tick();
            notes += s.L.afs.cavNote == 0 ? 1 : 0;
        }
        s.click(LinerControls.AP_DISC_R);
        s.tick();
        boolean cleared = F.apWarn == 0 && (s.L.ecam.shown & 1L << LinerEcam.M_AP_OFF) == 0 && F.cavalry == 0;
        check(off && warn && notes >= 1 && cleared, "liner AP off (the sidestick's button): the cavalry charge (" + notes + " cycle, "
                + LinerAfs.CAVALRY_MIN + " ticks at least) and the red «АВТОПИЛОТ ОТКЛ» with MASTER WARN - no chime; the second press clears it");
        // the FCU's button / a failure: the aural repeats until the red button
        LinerSimSVA u = LinerSimSVA.flying(80, 1.6, 90, 0);
        u.click(LinerControls.FCU_AP1);
        u.click(LinerControls.FCU_AP1);
        u.run(200);
        boolean rep = u.L.afs.cavalry == -1 && u.L.afs.apWarn == -1 && (u.L.ecam.shown & 1L << LinerEcam.M_AP_OFF) != 0;
        u.click(LinerControls.AP_DISC_L);
        boolean stop = u.L.afs.cavalry == 0 && u.L.afs.apWarn == 0;
        LinerSimSVA w = LinerSimSVA.flying(80, 1.6, 90, 0);
        w.click(LinerControls.FCU_AP1);
        w.insReady = false;
        w.run(2);
        boolean fail = !w.L.afs.apOn() && w.L.afs.cavalry == -1;
        // the override: a stick key held OVERRIDE_TICKS
        LinerSimSVA o = LinerSimSVA.flying(80, 1.6, 90, 0);
        o.click(LinerControls.FCU_AP1);
        o.k.left = true;
        o.run(LinerAfs.OVERRIDE_TICKS - 2);
        boolean still = o.L.afs.apOn();
        o.run(3);
        boolean over = !o.L.afs.apOn();
        o.k.left = false;
        check(rep && stop && fail && still && over, "liner AP off: by the FCU's button or a failure (the INS lost) the aural repeats until the red"
                + " button; an override (a stick key held " + LinerAfs.OVERRIDE_TICKS + " ticks) disconnects it");
        // A/THR: the instinctive button - amber caution, single chime; the second press clears; the levers to idle in flight - caution
        LinerSimSVA t = LinerSimSVA.flying(80, 1.6, 90, 0);
        t.tick();
        boolean active = t.L.afs.athrActive();
        t.click(LinerControls.ATHR_DISC_L);
        t.tick();
        boolean caut = !t.L.afs.athr && (t.L.ecam.shown & 1L << LinerEcam.M_ATHR_OFF) != 0 && t.L.masterCaut;
        t.click(LinerControls.ATHR_DISC_R);
        t.tick();
        boolean cl = (t.L.ecam.shown & 1L << LinerEcam.M_ATHR_OFF) == 0;
        LinerSimSVA i = LinerSimSVA.flying(80, 1.6, 90, 0);
        i.tick();
        i.L.afs.lever = 0;
        i.tick();
        check(active && caut && cl && !i.L.afs.athr && i.L.afs.athrWarn == -1, "liner A/THR off: the levers' button - «А/ТЯГА ОТКЛ» (MASTER CAUT),"
                + " the second press clears it; the levers to idle in flight - off with the caution");
    }

    // ---------------------------------------------------------------- the levers

    private static void levers() {
        LinerAfs F = new LinerAfs();
        int t = 0;
        while (F.lever < LinerAfs.DET_CL - 1e-9 && t < 100) {
            F.leverKeys(true, false, false, true);
            t++;
        }
        int toCl = t;
        int held = 0;
        while (Math.abs(F.lever - LinerAfs.DET_CL) < 1e-9 && held < 100) {
            F.leverKeys(true, false, false, true);
            held++;
        }
        boolean passed = F.lever > LinerAfs.DET_CL;
        // released between detents it stays; S back down stops at CL again
        F.lever = 0.9;
        F.leverKeys(false, false, false, true);
        boolean stays = Math.abs(F.lever - 0.9) < 1e-9;
        F.leverKeys(false, true, false, true);
        F.leverKeys(false, true, false, true);
        boolean stopCl = Math.abs(F.lever - LinerAfs.DET_CL) < 1e-9;
        check(toCl == (int) Math.ceil(LinerAfs.DET_CL / LinerAfs.LEVER_STEP) && held == LinerAfs.DETENT_HOLD + 1 && passed && stays && stopCl,
                "liner thrust levers: W moves them " + LinerAfs.LEVER_STEP + " a tick and stops at CL (" + toCl + " ticks); held " + LinerAfs.DETENT_HOLD
                        + " ticks more it passes the gate; released between detents they stay; S stops at CL again");
        // clicks: the next detent up / down; the reverse only on the ground (REV IDLE, REV MAX), W brings it back
        LinerAfs C = new LinerAfs();
        C.leverClick(true, true);
        double c1 = C.lever;
        C.leverClick(true, true);
        C.leverClick(true, true);
        double c3 = C.lever;
        C.leverClick(false, true);
        double c2 = C.lever;
        C.lever = 0;
        C.leverClick(false, false);
        boolean noRevAir = C.rev == 0;
        C.leverClick(false, true);
        double r1 = C.rev;
        C.leverClick(false, true);
        double r2 = C.rev;
        C.leverClick(true, true);
        C.leverClick(true, true);
        boolean out = C.rev == 0 && C.lever == 0;
        LinerAfs K = new LinerAfs();
        for (int i = 0; i < 30; i++) {
            K.leverKeys(false, false, true, true);
        }
        double revKeys = K.rev;
        K.leverKeys(true, false, false, true);
        boolean back = K.rev < revKeys;
        check(c1 == LinerAfs.DET_CL && c3 == LinerAfs.DET_TOGA && c2 == LinerAfs.DET_FLX && noRevAir && r1 == LinerAfs.REV_IDLE && r2 == 1 && out
                        && revKeys == 1 && back && LinerAfs.detent(0.93) == 2 && LinerAfs.detent(0.9) == -1,
                "liner thrust levers: clicks step IDLE - CL - FLX/MCT - TOGA and back; the reverse only on the ground (REV IDLE, REV MAX) -"
                        + " «Снижение» at idle pulls into it, W brings it back first");
    }

    // ---------------------------------------------------------------- A/THR modes

    private static void athr() {
        // take-off: the levers to FLX on the ground arm the A/THR - MAN FLX 45, SRS, RWY; V-speeds set
        LinerSimSVA g = new LinerSimSVA();
        g.setFlaps(1);
        g.L.fms.flex = 45;                                          // wave 5: FLEX comes from the MCDU's PERF TAKE OFF
        g.L.afs.lever = LinerAfs.DET_FLX;
        g.tick();
        LinerAfs F = g.L.afs;
        boolean to = F.athr && !F.athrActive() && F.thr == LinerAfs.T_MAN_FLX && "MAN FLX 45".equals(F.thrText()) && F.ver == LinerAfs.V_SRS
                && F.lat == LinerAfs.L_RWY && F.v1 > 0 && F.v1 < F.vr && F.vr < F.v2;
        g.L.afs.lever = LinerAfs.DET_TOGA;
        g.tick();
        boolean toga = F.thr == LinerAfs.T_MAN_TOGA && F.thrCmd == 1;
        // off the detents at take-off thrust: «РУД НЕ НА УПОРАХ»
        g.L.afs.lever = 0.9;
        g.tick();
        boolean notSet = F.leversNotSet && (g.L.ecam.active & 1L << LinerEcam.M_THR_LVRS) != 0;
        check(to && toga && notSet, "liner A/THR at take-off: the levers to FLX arm it - «MAN FLX 45», SRS, RWY, V1 " + f(F.v1 * 72) + " < VR "
                + f(F.vr * 72) + " < V2 " + f(F.v2 * 72) + " km/h; TOGA - MAN TOGA; between the detents - «РУД НЕ НА УПОРАХ»");
        // in flight at CL: THR CLB (climb), SPEED (ALT), THR IDLE (descent); under CL - A/THR LIMITED
        LinerSimSVA s = LinerSimSVA.flying(80, 1.6, 90, 0);
        s.click(LinerControls.FCU_AP1);
        s.tick();
        boolean speed = s.L.afs.thr == LinerAfs.T_SPEED && s.L.afs.athrActive();
        s.L.afs.alt += 50;
        s.click(LinerControls.FCU_ALT, LinerControls.A_SECONDARY);
        s.tick();
        boolean clb = s.L.afs.thr == LinerAfs.T_THR_CLB && Math.abs(s.L.afs.thrCmd - LinerAfs.DET_CL) < 1e-9;
        s.L.afs.alt -= 100;
        s.click(LinerControls.FCU_ALT, LinerControls.A_SECONDARY);
        s.tick();
        boolean idle = s.L.afs.ver == LinerAfs.V_OP_DES && s.L.afs.thr == LinerAfs.T_THR_IDLE && s.L.afs.thrCmd == 0;
        s.L.afs.lever = 0.6;
        s.tick();
        boolean lim = s.L.afs.athrLimited && (s.L.ecam.shown & 1L << LinerEcam.M_ATHR_LIM) != 0;
        s.L.afs.lever = 0.9;
        s.tick();
        boolean man = s.L.afs.thr == LinerAfs.T_MAN_THR && !s.L.afs.athrActive();
        check(speed && clb && idle && lim && man, "liner A/THR modes: SPEED in ALT, THR CLB (the CL limit) in OP CLB, THR IDLE in OP DES; the"
                + " levers under CL - «А/ТЯГА ОГРАНИЧ.»; over CL - MAN THR (armed)");
        // LVR CLB: after the take-off at the thrust reduction height with the levers still at FLX
        LinerSimSVA l = LinerSimSVA.flying(40, 1.4, 90, 1);
        l.L.afs.lever = LinerAfs.DET_FLX;
        l.L.afs.ver = LinerAfs.V_SRS;
        l.L.afs.lat = LinerAfs.L_RWY_TRK;
        l.L.afs.alt = 200;
        l.tick();
        boolean lvr = l.L.afs.lvrClb && (l.L.afs.ver == LinerAfs.V_CLB || l.L.afs.ver == LinerAfs.V_OP_CLB);
        l.L.afs.lever = LinerAfs.DET_CL;
        l.tick();
        check(lvr && !l.L.afs.lvrClb && l.L.afs.thr == LinerAfs.T_THR_CLB, "liner A/THR: «LVR CLB» flashes over the acceleration height with the"
                + " levers at FLX (SRS -> CLB); to CL - THR CLB, active");
    }

    // ---------------------------------------------------------------- alpha floor

    private static void alphaFloor() {
        LinerSimSVA s = LinerSimSVA.flying(80, 1.15 * 1.05, 90, 0);
        s.L.afs.athr = false;
        s.L.afs.lever = 0.3;
        s.tick();
        LinerAfs F = s.L.afs;
        boolean floor = F.aFloor && F.athr && F.thrCmd == 1 && F.thr == LinerAfs.T_AFLOOR;
        s.b.speed = 1.6;
        s.tick();
        boolean lk = !F.aFloor && F.togaLk && F.thr == LinerAfs.T_TOGA_LK && F.thrCmd == 1;
        s.click(LinerControls.ATHR_DISC_L);
        s.tick();
        boolean off = !F.togaLk && !F.athr && F.thrCmd < 0.5;
        // not in ALTN law
        LinerSimSVA a = LinerSimSVA.flying(80, 1.15 * 1.05, 90, 0);
        a.L.set(LinerControls.ELAC1, 0);
        a.L.set(LinerControls.ELAC2, 0);
        a.L.afs.athr = false;
        a.L.afs.lever = 0.3;
        a.run(3);
        check(floor && lk && off && !a.L.afs.aFloor, "liner alpha floor (NORMAL law): under " + f(F.aFloorV * 72) + " km/h the A/THR forces TOGA -"
                + " «A.FLOOR», recovered «TOGA LK» until the levers' button; not in ALTN law");
    }

    // ---------------------------------------------------------------- the bank protection

    private static void bank() {
        double b = 50;
        for (int i = 0; i < 60; i++) {
            b = LinerGuide.bankStep(b, 0, 1, true);
        }
        double held = 20;
        for (int i = 0; i < 60; i++) {
            held = LinerGuide.bankStep(held, 0, 1, true);
        }
        double full = 0;
        for (int i = 0; i < 200; i++) {
            full = LinerGuide.bankStep(full, 1, 1, true);
        }
        double altn = 50;
        for (int i = 0; i < 60; i++) {
            altn = LinerGuide.bankStep(altn, 0, 1, false);
        }
        double r33 = LinerGuide.turnRate(33, 1.6, 1.15, 1.1), r25 = LinerGuide.turnRate(25, 1.6, 1.15, 1.1);
        double back = LinerGuide.bankOf(r25, 1.6, 1.15, 1.1);
        check(b == LinerGuide.BANK_HOLD && held == 20 && full == LinerGuide.BANK_MAX && altn == 50 && Math.abs(r33 - 1.1) < 1e-9 && r25 > 0.7
                        && r25 < 0.85 && Math.abs(back - 25) < 1e-6,
                "liner bank protection (NORMAL): released at 50 deg it rolls back to 33, 20 holds, full stick stops at 67; ALTN holds 50; the turn"
                        + " at 33 deg = the old manual rate (1.1 deg/t), at the AP's 25 deg " + f(r25) + " deg/t; bankOf inverts it");
    }

    // ---------------------------------------------------------------- the speed bands, V-speeds

    private static void bands() {
        LinerSimSVA s = LinerSimSVA.flying(80, 1.6, 90, 0);
        LinerAfs F = s.L.afs;
        boolean order = F.aMax < F.aFloorV && F.aFloorV < F.aProt && F.aProt < F.vls && F.vls < LinerAfs.VMO && F.aMax > s.params().stall;
        double light = LinerAfs.vls(1.15, 16000), heavy = LinerAfs.vls(1.15, 19400);
        s.setFlaps(4);
        s.tick();
        boolean flaps = F.vls < light * 0.9;
        double[] v1 = LinerAfs.vspeeds(1.3 * 0.9, 1.0, 16000), v2 = LinerAfs.vspeeds(1.3 * 0.9, 1.0, 19400);
        check(order && heavy > light && flaps && v1[1] >= 1.3 * 0.9 && v2[1] > v1[1] && v2[2] > v2[1] && v2[0] < v2[1],
                "liner speed bands: alpha-max " + f(F.aMax * 72) + " < alpha floor < alpha-prot < VLS < VMO " + f(LinerAfs.VMO * 72)
                        + " km/h; VLS rises with the mass (" + f(light * 72) + " -> " + f(heavy * 72) + "), falls with the flaps; V1 < VR (>= lift-off) < V2,"
                        + " heavier - faster");
    }

    // ---------------------------------------------------------------- ILS geometry

    private static void ils() {
        LinerIls.Station s = new LinerIls.Station();
        s.bx = 1000;
        s.bz = 0;
        s.elev = 64;
        s.course = 90;                                              // landing east (+x)
        s.angle = 3;
        // 500 blocks before, on the centre line, on the beam
        double beam = 64 + 500 * Math.tan(Math.toRadians(3));
        LinerIls.Dev on = LinerIls.dev(s, 500.5, beam, 0.5, 4000);
        LinerIls.Dev right = LinerIls.dev(s, 500.5, beam + 3, 10.5, 4000);
        LinerIls.Dev behind = LinerIls.dev(s, 1500, 70, 0.5, 4000);
        LinerIls.Dev far = LinerIls.dev(s, -5000, 300, 0.5, 4000);
        boolean geo = Math.abs(on.along + 500) < 1e-6 && Math.abs(on.cross) < 1e-6 && Math.abs(on.loc) < 1e-6 && Math.abs(on.gs) < 1e-3 && on.locValid
                && on.gsValid && right.cross > 9.9 && right.loc > 0 && right.gs > 0 && right.above > 2.9 && !behind.locValid && !far.locValid;
        // capture cones: LOC - 30 blocks off at a 20 deg intercept the lead is ~14, at 5 blocks it captures; G/S - from 1,5 under the beam
        LinerIls.Dev d30 = LinerIls.dev(s, 0, 60, -30, 4000), d5 = LinerIls.dev(s, 0, 60, -5, 4000);
        boolean loc = !LinerIls.locCapture(d30, s, 110, 83, 1.15) && LinerIls.locCapture(d5, s, 110, 83, 1.15)
                && !LinerIls.locCapture(d5, s, 70, 83, 1.15);
        LinerIls.Dev under = LinerIls.dev(s, 0, 64 + 1000.5 * Math.tan(Math.toRadians(3)) - 1, 0.5, 4000);
        LinerIls.Dev low = LinerIls.dev(s, 0, 64 + 1000.5 * Math.tan(Math.toRadians(3)) - 3, 0.5, 4000);
        boolean gs = LinerIls.gsCapture(under, 1.5) && !LinerIls.gsCapture(low, 1.5);
        // the tuning: the aligned one ahead, not a reciprocal; a hand-picked one wins; ident / frequency rules
        LinerIls.Station rec = s.copy();
        rec.bx = 2000;
        rec.course = 270;
        java.util.List<LinerIls.Station> l = new java.util.ArrayList<LinerIls.Station>();
        l.add(rec);
        l.add(s);
        boolean pick = LinerIls.pick(l, "", 0, 100, 0, 90, 4000) == s && LinerIls.pick(l, "", 0, 100, 0, 270, 4000) == null
                && LinerIls.pick(l, rec.key(), 0, 100, 0, 90, 4000) == rec;
        int[] p = LinerIls.packIdent("ИЛС1");
        boolean words = "ИЛС1".equals(LinerIls.unpackIdent(p[0], p[1])) && LinerIls.clampFreq(11033) == 11035 && LinerIls.clampFreq(10000) == 10810
                && LinerIls.clampFreq(12000) == 11195 && "".equals(LinerIls.cleanIdent("ab")) && "ABCD".equals(LinerIls.cleanIdent("abcde"))
                && "110.30".equals(LinerIls.freqText(11030)) && LinerIls.suggestCourse(-90F) == 90 && LinerIls.suggestCourse(0F) == 180;
        check(geo && loc && gs && pick && words, "liner ILS geometry: along / across, LOC / G/S deviations (+ right / above), the coverage (inbound,"
                + " the range); LOC* on the turn's lead (not diverging), G/S* from under the beam; the aligned station tuned, a picked one wins;"
                + " the ident (3-4 letters), 108.10..111.95 in 0.05, the course by the player's look");
    }

    // ---------------------------------------------------------------- the ILS world data

    private static void worldData() {
        IlsDataSVA d = new IlsDataSVA(IlsDataSVA.NAME);
        LinerIls.Station a = new LinerIls.Station();
        a.dim = 0;
        a.bx = 100;
        a.by = 64;
        a.bz = 200;
        a.elev = 65;
        a.course = 123;
        a.angle = 3.5;
        a.ident = "ЛЕС";
        a.freq = 10955;
        a.offset = -4;
        LinerIls.Station b = a.copy();
        b.bx = 9000;
        LinerIls.Station c = a.copy();
        c.dim = -1;
        d.put(a);
        d.put(b);
        d.put(c);
        NBTTagCompound t = new NBTTagCompound();
        d.writeToNBT(t);
        IlsDataSVA e = new IlsDataSVA(IlsDataSVA.NAME);
        e.readFromNBT(t);
        java.util.List<LinerIls.Station> near = e.near(0, 0, 0, 4000);
        LinerIls.Station r = e.get(a.key());
        boolean same = r != null && r.course == 123 && r.angle == 3.5 && "ЛЕС".equals(r.ident) && r.freq == 10955 && r.offset == -4 && r.elev == 65
                && Math.abs(r.tx() - a.tx()) < 1e-9;
        e.remove(a.key());
        check(e.size() == 2 && near.size() == 1 && same, "liner ILS world data: the beacons saved and read back (course, angle, ident, frequency,"
                + " offset, elevation); near() - this dimension within the range only; removed when broken");
    }

    // ---------------------------------------------------------------- mode transitions

    private static void transitions() {
        // SRS -> CLB at the acceleration height (30 over the runway), RWY -> RWY TRK at lift-off
        LinerSimSVA s = new LinerSimSVA();
        s.setFlaps(1);
        s.L.afs.alt = 120;
        s.click(LinerControls.FCU_ALT, LinerControls.A_PRIMARY);
        s.L.afs.lever = LinerAfs.DET_TOGA;
        int lift = -1, clb = -1, apAt = -1;
        double vr = 0;
        for (int i = 0; i < 2400 && clb < 0; i++) {
            // the pilot rotates at VR and holds it to 8 blocks, then the AP (it engages 5 s after the lift-off)
            s.k.up = s.b.speed >= s.L.afs.vr && (s.b.ground || s.b.ra < 8);
            if (s.b.ground && s.k.up && vr == 0) {
                vr = s.b.speed;
            }
            if (!s.b.ground && !s.k.up && !s.L.afs.apOn()) {
                s.click(LinerControls.FCU_AP1);
                apAt = s.L.afs.apOn() ? i : -1;
            }
            s.tick();
            if (!s.b.ground && lift < 0) {
                lift = i;
            }
            if (s.L.afs.ver == LinerAfs.V_CLB && clb < 0) {
                clb = i;
            }
        }
        double hClb = s.b.y - LinerSimSVA.GROUND_Y;
        boolean trk = s.L.afs.lat == LinerAfs.L_RWY_TRK;
        boolean lvr = s.L.afs.lvrClb;
        check(lift > 0 && clb > lift && hClb >= 29 && hClb < 33 && trk && lvr && apAt - lift >= LinerAfs.AP_MIN_TICKS - 1, "liner take-off: TOGA -"
                + " SRS / RWY; lift-off at VR " + f(vr * 72) + " km/h (" + lift + " ticks), RWY TRK, the AP " + (apAt - lift) + " ticks after the lift-off;"
                + " SRS -> CLB at " + f(hClb) + " blocks (30), «LVR CLB»");
        s.L.afs.lever = LinerAfs.DET_CL;
        int alt = -1;
        double top = 0;
        for (int i = 0; i < 6000; i++) {
            s.tick();
            top = Math.max(top, s.b.y);
            if (s.L.afs.ver == LinerAfs.V_ALT && alt < 0) {
                alt = i;
                break;
            }
        }
        check(alt > 0 && s.L.afs.thr == LinerAfs.T_SPEED && top - 120 < 1.0, "liner climb: CL - THR CLB / CLB, ALT* near " + 120 + ", ALT (overshoot "
                + f(top - 120) + " blocks), SPEED");
    }

    // ---------------------------------------------------------------- flights

    private static void heading() {
        LinerSimSVA s = LinerSimSVA.flying(80, 1.6, 90, 0);
        s.click(LinerControls.FCU_AP1);
        for (int i = 0; i < 18; i++) {
            s.click(LinerControls.FCU_HDG, LinerControls.A_WHEEL_UP);
        }
        double over = 0, maxBank = 0;
        int at = -1;
        for (int t = 0; t < 600; t++) {
            s.tick();
            double e = CruiseMath.wrap(s.hdg() - 180);
            over = Math.max(over, e);
            maxBank = Math.max(maxBank, Math.abs(s.b.bank));
            if (at < 0 && Math.abs(e) < 1) {
                at = t;
            }
        }
        check(at > 0 && over < 1 && maxBank <= LinerGuide.AP_BANK + 1e-6 && Math.abs(CruiseMath.wrap(s.hdg() - 180)) < 0.2 && Math.abs(s.b.y - 144) < 0.5,
                "liner AP HDG: 90 -> 180 within 1 deg in " + at + " ticks, bank <= 25 (" + f(maxBank) + "), overshoot " + f(over) + " deg, the altitude held");
    }

    private static void altitude() {
        LinerSimSVA s = LinerSimSVA.flying(60, 1.6, 90, 0);
        s.click(LinerControls.FCU_AP1);
        for (int i = 0; i < 4; i++) {
            s.click(LinerControls.FCU_ALT, LinerControls.A_WHEEL_UP);
        }
        double target = s.L.afs.alt;
        s.click(LinerControls.FCU_ALT, LinerControls.A_SECONDARY);
        boolean op = s.L.afs.ver == LinerAfs.V_OP_CLB;
        double maxY = 0;
        boolean capSeen = false;
        for (int t = 0; t < 2400; t++) {
            s.tick();
            maxY = Math.max(maxY, s.b.y);
            capSeen |= s.L.afs.ver == LinerAfs.V_ALT_CAP;
        }
        check(op && capSeen && s.L.afs.ver == LinerAfs.V_ALT && maxY - target < 1.0 && Math.abs(s.b.y - target) < 0.2,
                "liner AP OP CLB -> ALT* -> ALT: level at " + f(s.b.y) + " (FCU " + target + "), overshoot " + f(maxY - target) + " blocks");
        for (int i = 0; i < 6; i++) {
            s.click(LinerControls.FCU_ALT, LinerControls.A_WHEEL_DOWN);
        }
        target = s.L.afs.alt;
        s.click(LinerControls.FCU_ALT, LinerControls.A_PRIMARY);
        boolean des = s.L.afs.ver == LinerAfs.V_DES;
        double minY = 1e9;
        for (int t = 0; t < 2400; t++) {
            s.tick();
            minY = Math.min(minY, s.b.y);
        }
        check(des && s.L.afs.ver == LinerAfs.V_ALT && target - minY < 1.0, "liner AP DES (managed) -> ALT at " + target + ", undershoot " + f(target - minY));
    }

    private static void vs() {
        LinerSimSVA s = LinerSimSVA.flying(100, 1.6, 90, 0);
        s.click(LinerControls.FCU_AP1);
        for (int i = 0; i < 6; i++) {
            s.click(LinerControls.FCU_ALT, LinerControls.A_WHEEL_DOWN);
        }
        s.click(LinerControls.FCU_VS, LinerControls.A_SECONDARY);
        for (int i = 0; i < 6; i++) {
            s.click(LinerControls.FCU_VS, LinerControls.A_WHEEL_DOWN);
        }
        s.run(100);
        double vsNow = s.b.motionY * 20;
        boolean armed = (s.L.afs.verArm & LinerAfs.AV_ALT) != 0;
        s.run(600);
        check(Math.abs(vsNow + 3.0) < 0.05 && armed && s.L.afs.ver == LinerAfs.V_ALT && Math.abs(s.b.y - s.L.afs.alt) < 0.3,
                "liner AP V/S -3,0 m/s: flown " + f(vsNow) + " m/s, ALT armed (blue), captured at the FCU altitude " + s.L.afs.alt);
    }

    private static void speed() {
        LinerSimSVA s = LinerSimSVA.flying(100, 1.5, 90, 0);
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_SPD, LinerControls.A_SECONDARY);
        s.L.afs.spd = 120;
        double maxErr = 0;
        for (int t = 0; t < 1200; t++) {
            s.tick();
            if (t > 600) {
                maxErr = Math.max(maxErr, Math.abs(s.b.speed * 72 - 120));
            }
        }
        check(s.L.afs.thr == LinerAfs.T_SPEED && maxErr < 1.0, "liner A/THR SPEED: 108 -> 120 km/h selected, held within " + f(maxErr) + " km/h");
    }

    /**
     * The approach: 1000 blocks before the threshold, 30 off the centre line at a 20 deg intercept, 45 over the ground (under
     * the glide slope), flaps 3, gear down, spoilers armed, autobrake MED; APPR + AP 1 + AP 2 - LOC* / LOC, G/S* / G/S, LAND,
     * FLARE, RETARD (the levers to idle as the pilot would), ROLL OUT, stopped by the autobrake on the centre line.
     */
    private static void approach() {
        LinerSimSVA s = LinerSimSVA.flying(45, 1.3, 110, 3);
        s.gearDown();
        s.L.set(LinerControls.SPD_BRK, 0);
        s.L.control(LinerControls.ABRK_MED.index, LinerControls.A_PRIMARY);
        LinerIls.Station st = s.station(1000, 0, 90, "ILS");
        s.b.z = -30;
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_APPR);
        s.click(LinerControls.FCU_AP2);
        boolean dual = s.L.afs.ap1 && s.L.afs.ap2;
        int locCap = -1, loc = -1, gsCap = -1, gs = -1, land = -1, flare = -1, retard = -1, roll = -1, stop = -1;
        double maxCrossLoc = 0, maxAboveGs = 0, crossTd = 0, xTd = 0;
        java.util.Set<Integer> calls = new java.util.TreeSet<Integer>();
        int lastSeq = s.L.afs.callSeq;
        for (int t = 0; t < 3000; t++) {
            s.tick();
            LinerAfs F = s.L.afs;
            if (F.callSeq != lastSeq) {
                lastSeq = F.callSeq;
                calls.add(Integer.valueOf(F.call));
            }
            if (F.thr == LinerAfs.T_RETARD && F.lever > 0) {
                F.lever = 0;                                        // the pilot: RETARD - the levers to idle
            }
            LinerIls.Dev d = LinerIls.dev(st, s.b.x, s.b.y, s.b.z, 4000);
            locCap = locCap < 0 && F.lat == LinerAfs.L_LOC_CAP ? t : locCap;
            loc = loc < 0 && F.lat == LinerAfs.L_LOC ? t : loc;
            gsCap = gsCap < 0 && F.ver == LinerAfs.V_GS_CAP ? t : gsCap;
            gs = gs < 0 && F.ver == LinerAfs.V_GS ? t : gs;
            land = land < 0 && F.lat == LinerAfs.L_LAND ? t : land;
            flare = flare < 0 && F.ver == LinerAfs.V_FLARE ? t : flare;
            retard = retard < 0 && F.thr == LinerAfs.T_RETARD ? t : retard;
            roll = roll < 0 && F.lat == LinerAfs.L_ROLLOUT ? t : roll;
            if (gs >= 0 && !s.b.ground && s.b.ra > 12) {
                maxCrossLoc = Math.max(maxCrossLoc, Math.abs(d.cross));
                maxAboveGs = Math.max(maxAboveGs, Math.abs(d.above));
            }
            if (s.touchAt == s.ticks - 1) {
                crossTd = d.cross;
                xTd = s.b.x - 1000;
            }
            if (s.b.ground && s.b.speed < 0.005 && stop < 0) {
                stop = t;
                break;
            }
        }
        LinerIls.Dev end = LinerIls.dev(st, s.b.x, s.b.y, s.b.z, 4000);
        check(dual && locCap > 0 && loc > locCap && gsCap > 0 && gs > gsCap && land > gs && flare > land && retard > flare && roll > retard,
                "liner autoland: APPR + AP 1 + 2 (CAT 3 DUAL) - LOC* " + locCap + ", LOC " + loc + ", G/S* " + gsCap + ", G/S " + gs + ", LAND " + land
                        + ", FLARE " + flare + ", RETARD " + retard + ", ROLL OUT " + roll + " (ticks)");
        check(maxCrossLoc < 1.0 && maxAboveGs < 1.0, "liner autoland: on G/S the LOC held within " + f(maxCrossLoc) + " blocks and the glide slope within "
                + f(maxAboveGs) + " blocks (1 dot at 500 blocks ~3)");
        check(s.touchSink > 0 && s.touchSink < 0.06 && xTd > 0 && xTd < 80 && Math.abs(crossTd) < 1.0, "liner autoland: touch-down "
                + f(xTd) + " blocks past the threshold, " + f(crossTd) + " off the centre line, sinking " + f(s.touchSink * 20) + " m/s");
        check(stop > 0 && Math.abs(end.cross) < 1.0 && s.L.fctl.gndSpl && s.L.afs.apOn() && calls.contains(Integer.valueOf(40))
                        && calls.contains(Integer.valueOf(10)) && calls.contains(Integer.valueOf(LinerAfs.CALL_RETARD)),
                "liner roll-out: the ground spoilers and the autobrake MED stop it " + f(s.b.x - 1000 - xTd) + " blocks after the touch-down, "
                        + f(end.cross) + " off the centre line; the callouts " + calls);
    }

    /** A go-around from G/S: the levers to TOGA - SRS / GA TRK, MAN TOGA, the AP stays, the flaps' reminder; then the climb. */
    private static void goAround() {
        LinerSimSVA s = LinerSimSVA.flying(45, 1.3, 90, 3);
        s.gearDown();
        s.station(1000, 0, 90, "ILS");
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_APPR);
        s.click(LinerControls.FCU_AP2);
        for (int t = 0; t < 400 && s.L.afs.ver != LinerAfs.V_GS; t++) {
            s.tick();
        }
        double y0 = s.b.y;
        s.L.afs.lever = LinerAfs.DET_TOGA;
        s.tick();
        LinerAfs F = s.L.afs;
        boolean ga = F.ver == LinerAfs.V_SRS && F.lat == LinerAfs.L_GA_TRK && F.thr == LinerAfs.T_MAN_TOGA && F.apOn() && F.msg == LinerAfs.MSG_GA_FLAPS;
        s.run(200);
        check(ga && s.b.y > y0 + 3 && s.L.afs.ver == LinerAfs.V_SRS, "liner go-around: TOGA on the approach - SRS / GA TRK, MAN TOGA, the AP stays,"
                + " «УХОД: ЗАКРЫЛКИ НА 1 ПОЗ.»; climbing " + f(s.b.y - y0) + " blocks in 10 s");
    }

    // ---------------------------------------------------------------- reversers

    private static void reversers() {
        LinerSimSVA s = new LinerSimSVA();
        s.b.speed = 1.0;
        s.L.afs.rev = 1;
        int t = 0;
        while (s.L.afs.revPos[0] < 0.95 && t < 200) {
            s.L.afs.rev = 1;
            s.systems();
            t++;
        }
        boolean memo = (s.L.memos(true) & LinerSystems.M_REV) != 0;
        for (int i = 0; i < 60; i++) {
            s.L.afs.rev = 1;
            s.systems();
        }
        double rev = s.L.revThrust(), fwd = s.L.thrust(), n1 = s.L.eng[0].n1;
        // no Blue: reverser 1 stays stowed
        LinerSimSVA b = new LinerSimSVA();
        b.L.hyd.psi[com.sva.vehicle.LinerHyd.B] = 0;
        b.L.set(LinerControls.HYD_BLUE, 0);
        for (int i = 0; i < 80; i++) {
            b.L.afs.rev = 1;
            b.L.hyd.psi[com.sva.vehicle.LinerHyd.B] = 0;
            b.systems();
        }
        check(t >= LinerAfs.REV_TICKS - 2 && t <= LinerAfs.REV_TICKS + 2 && memo && rev > 0.2 && fwd == 0 && n1 > 60 && b.L.afs.revPos[1] > 0.9
                        && b.L.afs.revPos[0] < 0.1,
                "liner reversers: on the ground REV MAX - the cowls out in " + t + " ticks (Blue / Yellow), «РЕВЕРС», N1 " + f(n1) + " %, reverse "
                        + f(rev) + ", no forward thrust; reverser 2 on Yellow out without Blue");
    }

    // ---------------------------------------------------------------- sync, save, lang

    private static void syncSave() {
        LinerSimSVA s = LinerSimSVA.flying(80, 1.6, 90, 0);
        s.station(1000, 0, 90, "ВПП");
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_APPR);
        s.click(LinerControls.FCU_SPD, LinerControls.A_SECONDARY);
        s.tick();
        LinerSystems c = new LinerSystems();
        c.unpack(s.L.pack());
        LinerAfs a = s.L.afs, b = c.afs;
        boolean sync = b.ap1 == a.ap1 && b.lat == a.lat && b.latArm == a.latArm && b.verArm == a.verArm && b.spd == a.spd && !b.spdMan
                && b.ils != null && "ВПП".equals(b.ils.ident) && Math.abs(b.ils.tx() - a.ils.tx()) < 0.06 && b.ilsNear.size() == 1
                && Math.abs(b.vls - a.vls) < 1e-3 && b.thr == a.thr && Math.abs(b.lever - a.lever) < 1e-3;
        NBTTagCompound t = new NBTTagCompound();
        a.alt = 170;
        a.qnh = 1000;
        a.std = true;
        s.L.writeNBT(t);
        LinerSystems r = new LinerSystems();
        r.readNBT(t);
        boolean save = r.afs.alt == 170 && r.afs.qnh == 1000 && r.afs.std && !r.afs.spdMan && !r.afs.apOn() && Math.abs(r.afs.lever - a.lever) < 1e-9;
        check(sync && save, "liner AFS sync / save: the modes, armed modes, FCU, bands, levers and the tuned ILS to the clients; the FCU and the"
                + " levers saved (the AP off after a reload)");
    }

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (String k : LinerAfs.MSG_KEYS) {
            if (k.length() > 0) {
                keys.add("sva.liner.fma." + k);
            }
        }
        for (String k : new String[] {"title", "course", "angle", "ident", "freq", "offset", "blocks", "deg", "look", "save", "cancel", "elev", "hint"}) {
            keys.add("sva.ils.gui." + k);
        }
        for (String k : new String[] {"idle", "cl", "flx", "toga", "revIdle", "revMax", "step10", "step50"}) {
            keys.add("sva.liner.pos." + k);
        }
        for (String k : new String[] {"tile.siliconagevehicles.ils.name", "sva.tooltip.ils.summary", "sva.tooltip.ils.details", "sva.tooltip.howto.ils",
                "sva.fn.apDisc", "sva.fn.apDisc.tip", "sva.fn.athrDisc", "sva.fn.athrDisc.tip", "sva.liner.du.noIls", "sva.liner.memo.rev",
                "sva.liner.ldm.autoBrk"}) {
            keys.add(k);
        }
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0 && VehicleType.LINER.plane != null, "liner wave 4 lang: the FMA's messages, the ILS screen, the levers' words, the"
                + " block, the keys in ru and en" + miss);
    }
}
