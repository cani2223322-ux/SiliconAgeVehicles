package com.sva.debug;

import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerSystems;

/**
 * The AP OFF fix (the user's «АВТОПИЛОТ ОТКЛ мигает и не гаснет» after a landing): an involuntary AP / A/THR disconnect
 * keeps its red / amber warning (and the AP's cavalry charge) until the instinctive button OR the master light is pressed -
 * either cancels it, on the ground and in the air; the power that runs the FCU / FMGC lost (the E/WD dark) cancels it too,
 * and it does not come back with the power; the client's mirror (the sync string) shows the cleared state.
 */
final class SelfTestLinerApWarnSVA {

    private SelfTestLinerApWarnSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() {
        afterRollout();
        masterWarn();
        power();
        athr();
    }

    private static boolean apShown(LinerSystems L) {
        return (L.ecam.shown & 1L << LinerEcam.M_AP_OFF) != 0;
    }

    private static boolean athrShown(LinerSystems L) {
        return (L.ecam.shown & 1L << LinerEcam.M_ATHR_OFF) != 0;
    }

    /** The client's mirror of these systems: the sync string through a fresh LinerSystems (EntityVehicleSVA.liner()). */
    private static LinerSystems mirror(LinerSystems L) {
        LinerSystems m = new LinerSystems();
        m.unpack(L.pack());
        return m;
    }

    /**
     * A full autoland (CAT 3 DUAL) with ROLL OUT to a stop; the AP still on - the pilots' disconnect after the roll-out is not
     * a fault. Then an automatic one (the AP lets go off its roll-out on the ground): the red warning and the cavalry stay
     * (also through the sync), the instinctive button clears both for good - also in the client's mirror.
     */
    private static void afterRollout() {
        LinerSimSVA s = LinerSimSVA.flying(45, 1.3, 110, 3);
        s.gearDown();
        s.L.set(LinerControls.SPD_BRK, 0);
        s.L.control(LinerControls.ABRK_MED.index, LinerControls.A_PRIMARY);
        s.station(1000, 0, 90, "ILS");
        s.b.z = -30;
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_APPR);
        s.click(LinerControls.FCU_AP2);
        boolean stopped = false;
        for (int t = 0; t < 3000 && !stopped; t++) {
            s.tick();
            if (s.L.afs.thr == LinerAfs.T_RETARD && s.L.afs.lever > 0) {
                s.L.afs.lever = 0;
            }
            stopped = s.b.ground && s.b.speed < 0.005;
        }
        s.run(40);
        LinerAfs F = s.L.afs;
        boolean quiet = stopped && F.apOn() && F.lat == LinerAfs.L_ROLLOUT && F.apWarn == 0 && !apShown(s.L);
        // the automatic disconnect on the ground: the roll-out mode gone with the AP still engaged (LinerAfs.tick's ground rule)
        F.lat = LinerAfs.L_HDG;
        s.run(2);
        boolean auto = !F.apOn() && F.apWarn == -1 && F.cavalry == -1 && apShown(s.L) && s.L.masterWarn;
        s.run(400);
        LinerSystems m1 = mirror(s.L);
        boolean stays = F.apWarn == -1 && apShown(s.L) && m1.afs.apWarn == -1 && m1.afs.cavalry == -1 && apShown(m1);
        F.apDisc();                                                 // the instinctive button («,» / the sidestick's)
        s.run(2);
        boolean cleared = F.apWarn == 0 && F.cavalry == 0 && !apShown(s.L) && !s.L.masterWarn;
        s.run(400);
        LinerSystems m2 = mirror(s.L);
        boolean gone = F.apWarn == 0 && F.cavalry == 0 && F.cavNote < 0 && !apShown(s.L) && !F.apOn() && m2.afs.apWarn == 0 && m2.afs.cavalry == 0
                && !apShown(m2) && !m2.masterWarn;
        check(quiet && auto && stays && cleared && gone, "liner AP OFF fix: autoland + ROLL OUT to a stop (AP on, no warning); an automatic"
                + " disconnect on the ground - the red «АВТОПИЛОТ ОТКЛ» + cavalry held (the mirror too); the instinctive button clears both"
                + " for good, the client's mirror cleared (" + quiet + auto + stays + cleared + gone + ")");
        // the user's sequence: then the power off and on - nothing comes back
        s.L.set(LinerControls.GEN1, 0);
        s.L.set(LinerControls.GEN2, 0);
        s.run(20);
        s.L.set(LinerControls.GEN1, 1);
        s.L.set(LinerControls.GEN2, 1);
        s.run(60);
        check(F.apWarn == 0 && F.cavalry == 0 && !apShown(s.L) && !apShown(mirror(s.L)), "liner AP OFF fix: taxiing, the power off and on"
                + " after the cancel - the warning stays gone");
    }

    /** MASTER WARN cancels the AP OFF warning and the cavalry charge, in the air (a failure) and on the ground (the FCU's button). */
    private static void masterWarn() {
        LinerSimSVA w = LinerSimSVA.flying(80, 1.6, 90, 0);
        w.click(LinerControls.FCU_AP1);
        w.insReady = false;                                         // a failure: the AP lets go with the repeating aural
        w.run(3);
        boolean held = !w.L.afs.apOn() && w.L.afs.apWarn == -1 && w.L.afs.cavalry == -1 && apShown(w.L);
        w.click(LinerControls.MW_L);
        w.run(2);
        boolean air = w.L.afs.apWarn == 0 && w.L.afs.cavalry == 0 && !apShown(w.L) && !w.L.masterWarn;
        w.run(100);
        boolean airStays = w.L.afs.apWarn == 0 && !apShown(w.L) && !apShown(mirror(w.L));
        // on the ground: the AP off by its FCU button after the roll-out (the aural repeats) - MASTER WARN (F/O's side) clears it
        LinerSimSVA g = rolledOut();
        g.click(LinerControls.FCU_AP1);
        g.run(3);
        boolean gHeld = g.L.afs.apWarn == -1 && apShown(g.L);
        g.click(LinerControls.MW_R);
        g.run(3);
        boolean ground = g.L.afs.apWarn == 0 && g.L.afs.cavalry == 0 && !apShown(g.L);
        check(held && air && airStays && gHeld && ground, "liner AP OFF fix: MASTER WARN cancels «АВТОПИЛОТ ОТКЛ» and the cavalry charge -"
                + " in the air (a failure) and on the ground (" + held + air + airStays + gHeld + ground + ")");
    }

    /** The E/WD dark (no AC: the FMGC unpowered) cancels both warnings; with the power back they stay gone. */
    private static void power() {
        LinerSimSVA g = rolledOut();
        g.click(LinerControls.FCU_AP1);
        g.run(3);
        boolean held = g.L.afs.apWarn == -1 && apShown(g.L);
        g.L.set(LinerControls.GEN1, 0);
        g.L.set(LinerControls.GEN2, 0);
        g.run(3);
        boolean dark = !g.L.acPowered() && g.L.afs.apWarn == 0 && g.L.afs.cavalry == 0 && !apShown(g.L);
        g.L.set(LinerControls.GEN1, 1);
        g.L.set(LinerControls.GEN2, 1);
        g.run(60);
        boolean back = g.L.acPowered() && g.L.afs.apWarn == 0 && g.L.afs.cavalry == 0 && !apShown(g.L) && !apShown(mirror(g.L));
        check(held && dark && back, "liner AP OFF fix: the AC lost (the E/WD dark) cancels «АВТОПИЛОТ ОТКЛ»; the power back - it does not"
                + " come back (" + held + dark + back + ")");
    }

    /** «А/ТЯГА ОТКЛ»: MASTER CAUT cancels it, the power lost cancels it, the levers' button still does. */
    private static void athr() {
        LinerSimSVA i = LinerSimSVA.flying(80, 1.6, 90, 0);
        i.tick();
        i.L.afs.lever = 0;                                          // the levers to idle in flight: off with the caution (held)
        i.run(3);
        boolean held = !i.L.afs.athr && i.L.afs.athrWarn == -1 && athrShown(i.L);
        i.click(LinerControls.MC_L);
        i.run(2);
        boolean mc = i.L.afs.athrWarn == 0 && !athrShown(i.L) && !athrShown(mirror(i.L));
        LinerSimSVA p = LinerSimSVA.flying(80, 1.6, 90, 0);
        p.tick();
        p.L.afs.lever = 0;
        p.run(3);
        boolean pHeld = p.L.afs.athrWarn == -1;
        p.L.set(LinerControls.GEN1, 0);
        p.L.set(LinerControls.GEN2, 0);
        p.run(3);
        boolean pw = p.L.afs.athrWarn == 0;
        p.L.set(LinerControls.GEN1, 1);
        p.L.set(LinerControls.GEN2, 1);
        p.run(40);
        boolean pBack = p.L.afs.athrWarn == 0 && !athrShown(p.L);
        LinerSimSVA b = LinerSimSVA.flying(80, 1.6, 90, 0);
        b.tick();
        b.L.afs.lever = 0;
        b.run(3);
        b.click(LinerControls.ATHR_DISC_R);
        b.run(2);
        boolean btn = b.L.afs.athrWarn == 0 && !athrShown(b.L);
        check(held && mc && pHeld && pw && pBack && btn, "liner A/THR OFF fix: «А/ТЯГА ОТКЛ» held - cancelled by MASTER CAUT, by the"
                + " power lost (not back with it), by the levers' button (" + held + mc + pHeld + pw + pBack + btn + ")");
    }

    /** On the ground, stopped, the AP on in ROLL OUT (a short autoland's end state without flying it: the touch-down's modes). */
    private static LinerSimSVA rolledOut() {
        LinerSimSVA s = LinerSimSVA.flying(45, 1.3, 90, 3);
        s.gearDown();
        s.station(1000, 0, 90, "ILS");
        s.click(LinerControls.FCU_AP1);
        s.click(LinerControls.FCU_APPR);
        s.click(LinerControls.FCU_AP2);
        s.b.y = LinerSimSVA.GROUND_Y;
        s.b.ground = true;
        s.b.speed = 0;
        s.L.afs.lever = 0;
        s.L.afs.lat = LinerAfs.L_ROLLOUT;
        s.L.afs.ver = LinerAfs.V_NONE;
        s.run(5);
        return s;
    }
}
