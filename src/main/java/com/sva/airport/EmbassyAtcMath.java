package com.sva.airport;

/**
 * PACKAGE C (docs/plan-embassy-airfield-addon.md §3.4): the pure rules of an embassy tower's air-traffic services - the transponder codes,
 * who hears its ATIS in the chat, which request it refuses, when taxiway «А» waits, how far the paving runs past the virtual runway's
 * ends. No Minecraft classes (the self-test runs them). First bodies by the coordinator, refined by C (the signatures stay).
 */
public final class EmbassyAtcMath {

    /** The embassy towers' transponder codes (octal 5100..5177; the players' default 4100..4177) - everybody's but the embassy's «Враг». */
    public static final int CODE_LO = 05100, CODE_HI = 05177;
    /** Cpdlc.U_REFUSED's reasons (Cpdlc.args: "#sva.cpdlc.refused." + key). */
    public static final int REFUSE_ENEMY = 0, REFUSE_LINER = 1;
    public static final String[] REFUSE_KEYS = {"enemy", "liner"};
    /** No refusal. */
    public static final int REFUSE_NONE = -1;
    /** screen()'s third answer: a landing over the dome - Cpdlc.U_DOME (no REFUSE_KEYS entry: U_DOME has no argument). */
    public static final int REFUSE_DOME = 2;
    /**
     * Taxiway «А» waits while a landing over the high end is within this far of its threshold (blocks; an arrival there is low over the
     * +a end's pad and the taxiway 7..13 off the axis). TODO(balance)
     */
    public static final double TAXI_HOLD_WINDOW = 400;
    /** A refusal of a GUESSED request (an aircraft without an MCDU is guessed again every few ticks) is told again only this late (ticks). */
    public static final int REFUSE_GAP = 1200;

    private EmbassyAtcMath() {
    }

    /**
     * An embassy tower's ATIS goes into a pilot's chat: within `range` (ConfigSVA.aptEmbassyAtisRange, 600) of the airport, or its ICAO
     * is the destination in the MCDU (`destination`). A player tower's: as before (TowerMath.ATC_RANGE).
     */
    public static boolean atisToChat(double dist, boolean destination, int range) {
        return destination || dist <= range;
    }

    /**
     * Why the embassy tower refuses an aircraft's landing (REFUSE_NONE: it may land): the embassy's «Враг» at the controls (`enemy`;
     * 7700 - never refused: `emergency`), the liner on a SHORT runway (`linerShortAllowed` - ConfigSVA.aptEmbassyLinerShort). The dome is
     * told apart (Cpdlc.U_DOME - domeRefused).
     */
    public static int landingRefusal(boolean enemy, boolean emergency, boolean liner, boolean shortRunway, boolean linerShortAllowed) {
        if (emergency) {
            return REFUSE_NONE;
        }
        if (enemy) {
            return REFUSE_ENEMY;
        }
        return liner && shortRunway && !linerShortAllowed ? REFUSE_LINER : REFUSE_NONE;
    }

    /** An aircraft asking to land over the dome's cylinder (not on the runway's final) is refused: ПОСАДКА В КУПОЛЕ ЗАПРЕЩЕНА. */
    public static boolean domeRefused(boolean inDome, boolean onFinal) {
        return inDome && !onFinal;
    }

    /**
     * What an embassy tower answers a request before its automatic rules see it: REFUSE_NONE (the rules go on), REFUSE_ENEMY / REFUSE_LINER
     * (Cpdlc.U_REFUSED) or REFUSE_DOME (Cpdlc.U_DOME). `landing` - a landing request (else a taxi / take-off one: only the «Враг» is
     * refused there). 7700 (`emergency`) - nothing refused, the dome neither (the clearance it gets is the runway's). `onFinal` - on the
     * final of either end of the embassy's runway.
     */
    public static int screen(boolean landing, boolean enemy, boolean emergency, boolean liner, boolean shortRunway, boolean linerShortAllowed,
            boolean inDome, boolean onFinal) {
        if (emergency) {
            return REFUSE_NONE;
        }
        if (!landing) {
            return enemy ? REFUSE_ENEMY : REFUSE_NONE;
        }
        int r = landingRefusal(enemy, false, liner, shortRunway, linerShortAllowed);
        if (r != REFUSE_NONE) {
            return r;
        }
        return domeRefused(inDome, onFinal) ? REFUSE_DOME : REFUSE_NONE;
    }

    /**
     * The refusal `code` goes out: an explicit request (`asked` - the MCDU's downlink) always gets its answer; a guessed one when it is
     * another refusal than the last (`last`, told at `lastAt`) or `gap` ticks past it (a clock gone back - again).
     */
    public static boolean tellRefusal(boolean asked, int last, long lastAt, int code, long now, int gap) {
        return asked || last != code || now - lastAt >= gap || now < lastAt;
    }

    /**
     * Risk 2 of the core's plan: taxiway «А» leaves the +a end's pad 7..13 off the axis under the approach over the HIGH end - a taxi
     * clearance along it waits while a landing over the high end is cleared or on its final within `window` blocks of the threshold.
     */
    public static boolean holdTaxi(boolean landingOverHigh, double finalDist, double window) {
        return landingOverHigh && finalDist > -5 && finalDist < window;
    }

    /**
     * How far the paving runs on past a virtual runway end (AutoTower.Rwy.ext): from the threshold lights' row (the add-on's end key) to
     * the paved end's middle block, across (FULL 20 - the displaced threshold, SHORT 1). 0 for a missing point.
     */
    public static double ext(int[] end, int[] thresholdKey) {
        if (end == null || thresholdKey == null || end.length < 3 || thresholdKey.length < 3) {
            return 0;
        }
        return Math.hypot(end[0] - thresholdKey[0], end[2] - thresholdKey[2]);
    }
}
