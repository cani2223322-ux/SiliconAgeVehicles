package com.sva.vehicle;

/**
 * Liner wave 3 (mock-up veh_liner_15, step 12): the SA-320's ECAM checklists - «ПЕРЕД ЗАПУСКОМ», «ПОСЛЕ ЗАПУСКА», «ПЕРЕД
 * ВЗЛЁТОМ» - shown on the SD's own page (the ВСЕ key's cycle reaches it). Every item ticks by itself when its condition
 * holds (cyan «- WHAT .... SETTING» -> green with a tick); the page shows the first list not yet complete for the moment
 * (no engine running - before start; running - after start, then before take-off). Pure, from the synced state only: the
 * server and every client judge it alike.
 */
public final class LinerChecklist {

    public static final int BEFORE_START = 0, AFTER_START = 1, BEFORE_TO = 2, LISTS = 3;
    public static final String[] LIST_KEYS = {"beforeStart", "afterStart", "beforeTo"};
    /** Items per list (lang sva.liner.chk.KEY = «WHAT|SETTING»). */
    public static final String[][] ITEMS = {
            {"bat", "adirs", "power", "apuBleed", "pumps", "beacon", "park", "door"},
            {"engs", "mode", "apuBleedOff", "apuOff", "packs", "flaps", "trim", "sts"},
            {"config", "autoBrk", "splrs", "belts", "strobe", "land", "parkOff", "nose"}};

    private LinerChecklist() {
    }

    /** Item i of list `list` holds now. */
    public static boolean done(int list, int i, LinerSystems L) {
        switch (list * 16 + i) {
            // before start
            case 0:
                return L.on(LinerControls.BAT1) && L.on(LinerControls.BAT2);
            case 1:
                return L.at(LinerControls.ADIRS1) == 1 && L.at(LinerControls.ADIRS2) == 1 && L.at(LinerControls.ADIRS3) == 1;
            case 2:
                return L.apuRunning() || L.extOn;
            case 3:
                return L.apuBleedOpen;
            case 4:
                for (int p = 0; p < LinerFuel.PUMPS; p++) {
                    if (!L.on(LinerEcam.pump(p))) {
                        return false;
                    }
                }
                return true;
            case 5:
                return L.at(LinerControls.BEACON) == 0;
            case 6:
                return L.on(LinerControls.PARK_BRK);
            case 7:
                return !L.door;
            // after start
            case 16:
                return L.eng[0].running() && L.eng[1].running();
            case 17:
                return L.at(LinerControls.ENG_MODE) == 1;
            case 18:
                return !L.on(LinerControls.APU_BLEED);
            case 19:
                return !L.on(LinerControls.APU_MASTER);
            case 20:
                return L.on(LinerControls.PACK1) && L.on(LinerControls.PACK2);
            case 21:
                return LinerEcam.flapsTo(L);
            case 22:
                return LinerEcam.trimOk(L);
            case 23:
                return L.ecam.stsSeen || !L.ecam.hasStatus();
            // before take-off
            case 32:
                return L.ecam.toCfgOk;
            case 33:
                return L.gear.abMode == 3;
            case 34:
                return L.at(LinerControls.SPD_BRK) == 0;
            case 35:
                return L.at(LinerControls.SEAT_BELTS) == 0;
            case 36:
                return L.at(LinerControls.STROBE) == 0;
            case 37:
                return L.at(LinerControls.LAND_L) == 0 && L.at(LinerControls.LAND_R) == 0;
            case 38:
                return !L.on(LinerControls.PARK_BRK);
            case 39:
                return L.at(LinerControls.NOSE_LT) == 0;
            default:
                return false;
        }
    }

    /** The done items of a list (bits). */
    public static int doneBits(int list, LinerSystems L) {
        int m = 0;
        for (int i = 0; i < ITEMS[list].length; i++) {
            m |= done(list, i, L) ? 1 << i : 0;
        }
        return m;
    }

    public static boolean complete(int list, LinerSystems L) {
        return doneBits(list, L) == (1 << ITEMS[list].length) - 1;
    }

    /** The list for now: before start with no engine running, else after start until it is complete, then before take-off. */
    public static int current(LinerSystems L) {
        if (!L.anyRunning()) {
            return BEFORE_START;
        }
        return complete(AFTER_START, L) ? BEFORE_TO : AFTER_START;
    }
}
