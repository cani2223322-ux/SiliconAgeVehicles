package com.sva.debug;

import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.PlaneMath;

/**
 * The autopilot fix's self-tests (the user's «не снижается» / «регулятор скорости не работает»): the flights of LinerSimSVA
 * from a cruise level - DES and OP DES with the A/THR at CL (THR IDLE, the descent within 2 s, the rate, the speed held,
 * ALT* / ALT at the FCU altitude), from an overspeed, with the levers above CL («РУД НА CL», still descending), in TOGA LK
 * (still descending, the ECAM's caution, the instinctive button out of it); alpha floor's filter and the airspeed through
 * the driver's Move gaps (the false TOGA LK's cause); the A/THR's SPEED (selected changed, managed) and the levers on CL
 * from the keys; the ALT knob's indicated altitude.
 */
final class SelfTestLinerApFixSVA {

    private SelfTestLinerApFixSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static String f(double v) {
        return SelfTestLinerAfsSVA.f(v);
    }

    static void run() {
        descent(true);
        descent(false);
        overspeed();
        manualThrust();
        togaLk();
        alphaFloorFilter();
        moveGaps();
        speedKnob();
        leversToCl();
        altKnobQnh();
    }

    /** Level at Y 170 (106 over the ground), 125 km/h, AP 1, the A/THR at CL, settled in ALT / SPEED. */
    private static LinerSimSVA cruise(double speed) {
        LinerSimSVA s = LinerSimSVA.flying(106, speed, 90, 0);
        s.click(LinerControls.FCU_AP1);
        s.run(200);
        return s;
    }

    // ---------------------------------------------------------------- DES / OP DES from the cruise

    private static void descent(boolean push) {
        LinerSimSVA s = cruise(125 / 72.0);
        LinerAfs F = s.L.afs;
        boolean level = F.ver == LinerAfs.V_ALT && F.thr == LinerAfs.T_SPEED && Math.abs(s.b.motionY) < 0.005;
        F.alt = 100;
        s.click(LinerControls.FCU_ALT, push ? LinerControls.A_PRIMARY : LinerControls.A_SECONDARY);
        int mode = F.ver;
        int startTick = -1;
        double maxVy = -1, minRate = 0, maxRate = -1e9, spdErr = 0, minY = 1e9;
        boolean idle = false, capSeen = false;
        for (int t = 1; t <= 1200; t++) {
            s.tick();
            if (startTick < 0 && s.b.motionY <= -0.05) {
                startTick = t;                                      // 1 m/s down
            }
            idle |= F.thr == LinerAfs.T_THR_IDLE;
            capSeen |= F.ver == LinerAfs.V_ALT_CAP;
            minY = Math.min(minY, s.b.y);
            if (F.ver == mode) {
                maxVy = Math.max(maxVy, s.b.motionY);
                if (t > 150 && s.b.y > 115) {                     // the steady descent (the level-off's own shaping after)
                    minRate = Math.min(minRate, s.b.motionY * 20);
                    maxRate = Math.max(maxRate, s.b.motionY * 20);
                    spdErr = Math.max(spdErr, Math.abs(s.b.speed - F.spdT) * 72);
                }
            }
        }
        int want = push ? LinerAfs.V_DES : LinerAfs.V_OP_DES;
        check(level && mode == want && idle && startTick > 0 && startTick <= 40 && maxVy <= 1e-9 && minRate > -5.5 && maxRate < -1.5 && spdErr < 3
                        && capSeen && F.ver == LinerAfs.V_ALT && Math.abs(s.b.y - 100) < 0.3 && 100 - minY < 1.0,
                "liner AP " + (push ? "DES (pushed)" : "OP DES (pulled)") + " from the cruise at Y 170, the A/THR at CL: THR IDLE, down within "
                        + f(startTick / 20.0) + " s, " + f(minRate) + " .. " + f(maxRate) + " m/s, never up, the speed within " + f(spdErr)
                        + " km/h; ALT* -> ALT at 100 (undershoot " + f(100 - minY) + ")");
    }

    /** The user's screen: 144 km/h against a 125 target, the A/THR at CL - OP DES descends at once, the idle takes the speed off. */
    private static void overspeed() {
        LinerSimSVA s = LinerSimSVA.flying(106, 144 / 72.0, 90, 0);
        s.click(LinerControls.FCU_AP1);
        LinerAfs F = s.L.afs;
        F.alt = 100;
        s.click(LinerControls.FCU_ALT, LinerControls.A_SECONDARY);
        int startTick = -1;
        double maxVy = -1;
        for (int t = 1; t <= 300; t++) {
            s.tick();
            if (startTick < 0 && s.b.motionY <= -0.05) {
                startTick = t;
            }
            maxVy = Math.max(maxVy, s.b.motionY);
        }
        double err = Math.abs(s.b.speed - F.spdT) * 72;
        check(F.ver == LinerAfs.V_OP_DES && F.thr == LinerAfs.T_THR_IDLE && startTick > 0 && startTick <= 40 && maxVy <= 1e-9 && err < 3,
                "liner AP OP DES from an overspeed (144 km/h, target " + f(F.spdT * 72) + "): THR IDLE, down within " + f(startTick / 20.0)
                        + " s (too fast is no level-off), never up; on the speed after 15 s (" + f(err) + " km/h off)");
    }

    /** The levers above CL (MCT) with the A/THR armed: OP DES still goes down, «РУД НА CL» flashes; back to CL - THR IDLE. */
    private static void manualThrust() {
        LinerSimSVA s = cruise(125 / 72.0);
        LinerAfs F = s.L.afs;
        F.lever = LinerAfs.DET_FLX;
        s.run(100);
        F.alt = 100;
        s.click(LinerControls.FCU_ALT, LinerControls.A_SECONDARY);
        double y0 = s.b.y;
        int startTick = -1;
        boolean flag = true;
        for (int t = 1; t <= 600; t++) {
            s.tick();
            if (startTick < 0 && s.b.motionY <= -0.05) {
                startTick = t;
            }
            flag &= F.lvrDes;
        }
        double fell = y0 - s.b.y;
        boolean man = F.thr == LinerAfs.T_MAN_MCT && F.lvrDes;
        // the pilot's S brings them back: the keys stop on CL
        for (int i = 0; i < 40 && Math.abs(F.lever - LinerAfs.DET_CL) > 1e-9; i++) {
            F.leverKeys(false, true, false, false);
        }
        s.run(20);
        boolean idle = F.thr == LinerAfs.T_THR_IDLE && !F.lvrDes;
        check(man && flag && startTick > 0 && startTick <= 40 && fell > 10 && idle,
                "liner AP OP DES with the levers at MCT (manual thrust): «РУД НА CL» flashes, still down within " + f(startTick / 20.0) + " s, "
                        + f(fell) + " blocks in 30 s (no silent level-off); S to CL - THR IDLE");
    }

    /** TOGA LK (alpha floor's lock): DES still goes down, the ECAM's caution and the HUD / FMA hint; the instinctive button frees it. */
    private static void togaLk() {
        LinerSimSVA s = cruise(125 / 72.0);
        LinerAfs F = s.L.afs;
        F.togaLk = true;
        F.athr = true;
        F.lever = 0.75;
        s.run(60);
        boolean full = F.thr == LinerAfs.T_TOGA_LK && F.thrCmd == 1;
        boolean caut = has(s.L.ecam.list(), LinerEcam.M_TOGA_LK);
        F.alt = 100;
        s.click(LinerControls.FCU_ALT, LinerControls.A_PRIMARY);
        double y0 = s.b.y;
        s.run(400);
        double fell = y0 - s.b.y;
        s.click(LinerControls.ATHR_DISC_L);
        s.run(5);
        boolean freed = !F.togaLk && !F.athr && F.thrCmd < 0.9 && !has(s.L.ecam.list(), LinerEcam.M_TOGA_LK);
        check(full && caut && fell > 5 && freed && F.ver != LinerAfs.V_ALT,
                "liner TOGA LK: the thrust stays TOGA (the FMA flashes, the ECAM's «TOGA LK: ОТКЛ А/ТЯГУ»), DES still goes down (" + f(fell)
                        + " blocks in 20 s); the levers' instinctive button frees it");
    }

    private static boolean has(int[] l, int id) {
        for (int i : l) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the false alpha floor

    /** Alpha floor wants the speed under V_AFLOOR AFLOOR_TICKS in a row: a short glitch never latches TOGA LK. */
    private static void alphaFloorFilter() {
        LinerSimSVA s = cruise(125 / 72.0);
        LinerAfs F = s.L.afs;
        double real = s.b.speed;
        boolean latched = false;
        for (int i = 0; i < LinerAfs.AFLOOR_TICKS - 2; i++) {
            s.b.speed = F.aFloorV - 0.2;                            // a bad reading of the speed (a few ticks)
            s.systems();
            latched |= F.aFloor || F.togaLk;
        }
        s.b.speed = real;
        s.run(20);
        latched |= F.aFloor || F.togaLk;
        for (int i = 0; i < LinerAfs.AFLOOR_TICKS + 1; i++) {
            s.b.speed = F.aFloorV - 0.2;
            s.systems();
        }
        boolean real2 = F.aFloor && F.thrCmd == 1;
        check(!latched && real2, "liner alpha floor's filter: " + (LinerAfs.AFLOOR_TICKS - 2) + " ticks of a bad airspeed - no A.FLOOR / TOGA LK;"
                + " held " + (LinerAfs.AFLOOR_TICKS + 1) + " ticks under " + f(F.aFloorV * 72) + " km/h - A.FLOOR");
    }

    /**
     * The server's judged airspeed while a lag spike's catch-up ticks get the driver's Moves bunched: the old rule (the EMA
     * / the window's mean of the measured displacement a TICK) fell under alpha floor's speed; gapAirspeed keeps it.
     */
    private static void moveGaps() {
        double v = 125 / 72.0, floorV = 1.15 * LinerAfs.K_AFLOOR;
        int[] pattern = {1, 1, 1, 1, 1, 1, 6, 0, 0, 0, 0, 0, 1, 0, 2, 0, 0, 1, 1, 1, 1, 1};
        double ema = v, oldMin = 1e9, newMin = 1e9, newMax = 0, judged = v;
        double[] win = {v, v, v, v};
        int[] moves = {1, 1, 1, 1};
        int at = 0, gap = 0;
        for (int n : pattern) {
            double moved = n * v;
            ema = ema * 0.5 + moved * 0.5;
            win[at] = moved;
            moves[at] = n;
            at = (at + 1) % 4;
            gap = n > 0 ? 0 : gap + 1;
            double mean = (win[0] + win[1] + win[2] + win[3]) / 4;
            double old = PlaneMath.reportedAirspeed(v, Math.max(ema, mean));
            judged = PlaneMath.gapAirspeed(judged, v, Math.max(ema, mean), PlaneMath.perMove(win, moves), n, gap);
            oldMin = Math.min(oldMin, old);
            newMin = Math.min(newMin, judged);
            newMax = Math.max(newMax, judged);
        }
        // a client lying about its speed is still capped by the displacement a Move
        double liar = PlaneMath.gapAirspeed(0, 3.0, 0.5, 0.5, 1, 0);
        check(oldMin < floorV && Math.abs(newMin - v) < 1e-9 && Math.abs(newMax - v) < 1e-9 && liar < 0.7,
                "liner airspeed through the driver's Move gaps (a lag spike's catch-up ticks): the old rule read " + f(oldMin * 72) + " km/h (under"
                        + " alpha floor's " + f(floorV * 72) + " - the false TOGA LK), now " + f(newMin * 72) + " km/h all along; a faked speed still capped");
    }

    // ---------------------------------------------------------------- the speed knob, the levers

    private static void speedKnob() {
        LinerSimSVA s = cruise(125 / 72.0);
        LinerAfs F = s.L.afs;
        s.click(LinerControls.FCU_SPD, LinerControls.A_SECONDARY);  // pulled: selected (the speed flown)
        F.spd = 120;
        s.run(600);
        double e120 = Math.abs(s.b.speed * 72 - 120);
        double n120 = s.L.eng[0].n1;
        for (int i = 0; i < 4; i++) {
            s.click(LinerControls.FCU_SPD, LinerControls.A_WHEEL_DOWN);   // 5 km/h a click: 100
        }
        s.tick();
        boolean target = Math.abs(F.spdT * 72 - 100) < 0.01 && F.spd == 100;
        s.run(800);
        double e100 = Math.abs(s.b.speed * 72 - 100);
        double n100 = s.L.eng[0].n1;
        boolean speedMode = F.thr == LinerAfs.T_SPEED && "SPEED".equals(F.thrText());
        s.click(LinerControls.FCU_SPD, LinerControls.A_PRIMARY);   // pushed: managed
        s.run(800);
        double managed = F.managedSpeed(s.L, s.e.air);
        double eMan = Math.abs(s.b.speed - managed) * 72;
        check(e120 < 1 && target && e100 < 1 && n100 < n120 - 2 && speedMode && F.spdMan && Math.abs(F.spdT - managed) < 1e-9 && eMan < 1,
                "liner A/THR SPEED: selected 120 km/h held (" + f(e120) + " off, N1 " + f(n120) + "), the knob turned to 100 - the target at once, held ("
                        + f(e100) + " off, N1 down to " + f(n100) + "); pushed - managed " + f(managed * 72) + " km/h held (" + f(eMan) + " off); FMA SPEED");
    }

    /** In the air the keys bring the levers exactly onto CL (from under it and from MCT): the A/THR active, the HUD's «РУД: CL». */
    private static void leversToCl() {
        LinerSimSVA s = cruise(125 / 72.0);
        LinerAfs F = s.L.afs;
        F.lever = 0.75;
        s.run(5);
        boolean limited = F.athrLimited && !"CL".equals(LinerAfs.detentName(F.lever));
        for (int i = 0; i < 40; i++) {
            F.leverKeys(true, false, false, false);
            if (Math.abs(F.lever - LinerAfs.DET_CL) < 1e-9) {
                break;
            }
        }
        boolean up = F.lever == LinerAfs.DET_CL;
        F.lever = LinerAfs.DET_FLX;
        for (int i = 0; i < 40 && F.lever != LinerAfs.DET_CL; i++) {
            F.leverKeys(false, true, false, false);
        }
        boolean down = F.lever == LinerAfs.DET_CL;
        s.run(5);
        check(limited && up && down && F.athrActive() && !F.athrLimited && "CL".equals(LinerAfs.detentName(F.lever)) && LinerAfs.detentName(0.8).isEmpty(),
                "liner levers in the air: 75 % - A/THR LIMITED; W stops exactly on CL, S from MCT too - the A/THR active, the HUD «РУД: CL»");
    }

    /** The ALT knob compares the FCU's INDICATED altitude (QNH) with the aircraft's: 5 under on the display is a descent. */
    private static void altKnobQnh() {
        LinerSimSVA s = cruise(125 / 72.0);
        LinerAfs F = s.L.afs;
        F.qnh = 1015;                                               // + 2 hPa: the altitude shown ~17 over Y
        int shown = (int) Math.round(F.shownAlt(s.b.y));
        F.alt = shown - 5;                                          // over Y, under the altitude shown
        s.click(LinerControls.FCU_ALT, LinerControls.A_PRIMARY);
        boolean des = F.ver == LinerAfs.V_DES;
        check(des && F.alt > s.b.y + 2, "liner FCU ALT pushed with QNH " + F.qnh + ": " + F.alt + " shown (Y " + f(F.indicatedToY(F.alt)) + ") under the "
                + shown + " flown - DES (the indicated altitude, not Y)");
    }
}
