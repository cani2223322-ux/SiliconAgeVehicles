package com.sva.book;

import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;

/**
 * Wave C, item 46: «Обучение» - the guided first flight in the SA-320, a step machine over the liner's REAL state (the
 * client's synced LinerSystems and the entity's motion - nothing is faked or set for the pilot): cold and dark -> the
 * start -> taxi -> take-off -> a circuit -> the ILS approach -> an autoland or a hand landing -> stop. Each step has its
 * prompt (lang sva.book.tut.KEY / KEY.hint), the control to outline (client.LinerCockpitSVA.highlight) and a condition;
 * update() moves on when the condition holds (one step an update, so every step is seen). Pause stops the checks, skip
 * moves on without them, restart goes back to the first step. The state lives on the client (client.BookClientSVA), the
 * world test drives the start-up steps on a real liner run by the API.
 * <p>
 * Pure (LinerSystems + an Obs snapshot): the self-test runs the machine.
 */
public final class TutorialMath {

    /** The steps (lang keys sva.book.tut.KEY and sva.book.tut.KEY.hint). */
    public static final String[] STEPS = {
        "bat", "adirs", "apu", "apuBleed", "beacon", "engMode", "eng2", "eng1", "afterStart", "flaps", "toConfig", "lights", "parkOff",
        "taxi", "takeoff", "gearUp", "climb", "ap", "flapsUp", "turn", "ls", "appr", "established", "gearDown", "flapsFull", "land",
        "touchdown", "stop"};
    public static final int BAT = 0, ADIRS = 1, APU = 2, APU_BLEED = 3, BEACON = 4, ENG_MODE = 5, ENG2 = 6, ENG1 = 7, AFTER_START = 8, FLAPS = 9,
            TO_CONFIG = 10, LIGHTS = 11, PARK_OFF = 12, TAXI = 13, TAKEOFF = 14, GEAR_UP = 15, CLIMB = 16, AP = 17, FLAPS_UP = 18, TURN = 19,
            LS = 20, APPR = 21, ESTABLISHED = 22, GEAR_DOWN = 23, FLAPS_FULL = 24, LAND = 25, TOUCHDOWN = 26, STOP = 27, DONE = 28;
    /** The steps of the start-up (the world test's part). */
    public static final int STARTUP_END = AFTER_START;
    /** Taxi: on the ground faster than this (km/h); stopped: slower; the circuit's climb (blocks over the take-off) and turn (deg). */
    public static final double TAXI_KMH = 10, STOP_KMH = 3, CLIMB_H = 40, TURN_DEG = 160;

    private TutorialMath() {
    }

    /** What the tutorial sees each update: the systems, the entity's ground contact, speed, height and heading. */
    public static final class Obs {
        public LinerSystems L;
        public boolean ground = true;
        public double kmh, y, hdg;
    }

    /** The tutorial's state (the client keeps one). */
    public static final class State {
        public int step = BAT;
        public boolean active, paused;
        /** Skipped steps (bits). */
        public long skipped;
        /** The take-off's height, the heading turned since the climb, the last heading; airborne seen. */
        public double y0 = Double.NaN, turned, lastHdg = Double.NaN;
        public boolean airborne;
        /** Updates in the current step (the prompt's fade-in). */
        public int ticks;

        public boolean done() {
            return step >= DONE;
        }
    }

    /** Starts (or restarts) the tutorial. */
    public static void restart(State s) {
        s.step = BAT;
        s.active = true;
        s.paused = false;
        s.skipped = 0;
        s.y0 = Double.NaN;
        s.turned = 0;
        s.lastHdg = Double.NaN;
        s.airborne = false;
        s.ticks = 0;
    }

    public static void stop(State s) {
        s.active = false;
        s.paused = false;
    }

    public static void pause(State s, boolean on) {
        s.paused = on && s.active;
    }

    /** Skips the current step (it counts as skipped, not done). */
    public static void skip(State s) {
        if (s.active && !s.done()) {
            s.skipped |= 1L << s.step;
            s.step++;
            s.ticks = 0;
        }
    }

    /**
     * One update: the bookkeeping (the take-off height, airborne, the heading turned once climbing) and, when the current
     * step's condition holds, the next step. Paused / inactive / done: nothing. @return the step moved on
     */
    public static boolean update(State s, Obs o) {
        if (!s.active || s.paused || s.done() || o == null || o.L == null) {
            return false;
        }
        s.ticks++;
        if (!o.ground) {
            s.airborne = true;
        } else if (Double.isNaN(s.y0) || s.step <= TAKEOFF) {
            s.y0 = o.y;                                             // the runway's height: the last one on the ground before the take-off
        }
        if (s.step >= TURN && !Double.isNaN(s.lastHdg) && !o.ground) {
            double d = o.hdg - s.lastHdg;
            d = ((d % 360) + 540) % 360 - 180;
            s.turned += Math.abs(d) < 30 ? d : 0;
        }
        s.lastHdg = o.hdg;
        if (cond(s.step, s, o)) {
            s.step++;
            s.ticks = 0;
            if (s.step == TURN) {
                s.turned = 0;
            }
            return true;
        }
        return false;
    }

    /** Step `step`'s condition. */
    public static boolean cond(int step, State s, Obs o) {
        LinerSystems L = o.L;
        switch (step) {
            case BAT:
                return L.on(LinerControls.BAT1) && L.on(LinerControls.BAT2);
            case ADIRS:
                return L.at(LinerControls.ADIRS1) == 1 && L.at(LinerControls.ADIRS2) == 1 && L.at(LinerControls.ADIRS3) == 1;
            case APU:
                return L.apuRunning();
            case APU_BLEED:
                return L.apuBleedOpen;
            case BEACON:
                return L.at(LinerControls.BEACON) == 0;
            case ENG_MODE:
                return L.at(LinerControls.ENG_MODE) == 2;
            case ENG2:
                return L.eng[1].running();
            case ENG1:
                return L.eng[0].running();
            case AFTER_START:
                return L.at(LinerControls.ENG_MODE) == 1 && !L.on(LinerControls.APU_BLEED);
            case FLAPS:
                return L.at(LinerControls.FLAPS) >= 1 && L.at(LinerControls.FLAPS) <= 3;
            case TO_CONFIG:
                return L.ecam.toCfgOk;
            case LIGHTS:
                return L.at(LinerControls.STROBE) == 0 && L.at(LinerControls.NOSE_LT) == 0;
            case PARK_OFF:
                return !L.on(LinerControls.PARK_BRK);
            case TAXI:
                return o.ground && o.kmh > TAXI_KMH;
            case TAKEOFF:
                return !o.ground;
            case GEAR_UP:
                return L.at(LinerControls.GEAR) == 0;
            case CLIMB:
                return !o.ground && !Double.isNaN(s.y0) && o.y - s.y0 > CLIMB_H;
            case AP:
                return L.afs.ap1 || L.afs.ap2;
            case FLAPS_UP:
                return L.at(LinerControls.FLAPS) == 0;
            case TURN:
                return Math.abs(s.turned) >= TURN_DEG;
            case LS:
                return (L.on(LinerControls.LS_L) || L.on(LinerControls.LS_R)) && L.afs.ils != null;
            case APPR:
                return L.afs.approachArmedOrOn();
            case ESTABLISHED:
                return (L.afs.lat == LinerAfs.L_LOC || L.afs.lat == LinerAfs.L_LAND) && (L.afs.ver == LinerAfs.V_GS || L.afs.ver == LinerAfs.V_LAND);
            case GEAR_DOWN:
                return L.at(LinerControls.GEAR) == 1;
            case FLAPS_FULL:
                return L.at(LinerControls.FLAPS) >= 3;
            case LAND:
                // the autoland (both APs - CAT 3 DUAL) or a hand landing (the APs off)
                return L.afs.ap1 && L.afs.ap2 || !L.afs.apOn();
            case TOUCHDOWN:
                return s.airborne && o.ground;
            case STOP:
                return o.ground && o.kmh < STOP_KMH;
            default:
                return false;
        }
    }

    /** The control a step is about (the outline), or null (flying, not a control). */
    public static LinerControls.Ctl control(int step) {
        switch (step) {
            case BAT:
                return LinerControls.BAT1;
            case ADIRS:
                return LinerControls.ADIRS1;
            case APU:
                return LinerControls.APU_START;
            case APU_BLEED:
                return LinerControls.APU_BLEED;
            case BEACON:
                return LinerControls.BEACON;
            case ENG_MODE:
            case AFTER_START:
                return LinerControls.ENG_MODE;
            case ENG2:
                return LinerControls.ENG_MASTER2;
            case ENG1:
                return LinerControls.ENG_MASTER1;
            case FLAPS:
            case FLAPS_UP:
            case FLAPS_FULL:
                return LinerControls.FLAPS;
            case TO_CONFIG:
                return LinerControls.TO_CONFIG;
            case LIGHTS:
                return LinerControls.STROBE;
            case PARK_OFF:
                return LinerControls.PARK_BRK;
            case TAKEOFF:
                return LinerControls.TL1;
            case GEAR_UP:
            case GEAR_DOWN:
                return LinerControls.GEAR;
            case AP:
            case LAND:
                return LinerControls.FCU_AP1;
            case TURN:
                return LinerControls.FCU_HDG;
            case LS:
                return LinerControls.LS_L;
            case APPR:
                return LinerControls.FCU_APPR;
            default:
                return null;
        }
    }

    /** The steps done (not skipped) so far. */
    public static int doneCount(State s) {
        int n = 0;
        for (int i = 0; i < Math.min(s.step, DONE); i++) {
            n += (s.skipped & (1L << i)) == 0 ? 1 : 0;
        }
        return n;
    }

    /** The key of the current step ("done" when finished). */
    public static String key(State s) {
        return s.done() ? "done" : STEPS[s.step];
    }
}
