package com.sva.book;

import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerChecklist;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;

/**
 * Wave C, item 45: the handbook's interactive checklists - opened while seated in the liner's cockpit, a checklist page
 * ticks its items live from the synced LinerSystems (the client's copy - the same state the server judges) and each item
 * names the control to work, which «Показать» outlines (pulsing) in the 3D cockpit and in the «Пульты» screen
 * (client.LinerCockpitSVA.highlight). The lists:
 * <ul>
 * <li>STARTUP - the ECAM's «ПЕРЕД ЗАПУСКОМ» and «ПОСЛЕ ЗАПУСКА» (LinerChecklist, the same conditions);</li>
 * <li>BEFORE_TO - the ECAM's «ПЕРЕД ВЗЛЁТОМ» (LinerChecklist);</li>
 * <li>APPROACH - the landing set-up: LS on, the ILS tuned, APPR armed / active, seat belts, landing lights, autobrake,
 * the ground spoilers armed, gear down, flaps 3 / FULL;</li>
 * <li>AFTER_LANDING - spoilers retracted, flaps up, strobes off, landing lights off, nose light taxi, the APU started,
 * the autobrake off.</li>
 * </ul>
 * Pure (LinerSystems only): the self-test runs every condition against a simulated liner.
 */
public final class BookChecklists {

    public static final int STARTUP = 0, BEFORE_TO = 1, APPROACH = 2, AFTER_LANDING = 3, LISTS = 4;
    /** The list ids (the markup's @check ID) and their titles (lang sva.book.chk.ID). */
    public static final String[] IDS = {"startup", "beforeTo", "approach", "afterLanding"};
    /** The new lists' items (lang sva.book.chk.item.KEY = «WHAT|SETTING»); the first two lists use LinerChecklist's. */
    static final String[] APPROACH_ITEMS = {"ls", "ils", "appr", "belts", "landLt", "autoBrk", "splArm", "gear", "flaps"};
    static final String[] AFTER_ITEMS = {"splRet", "flapsUp", "strobeOff", "landOff", "taxiLt", "apu", "abrkOff"};

    private BookChecklists() {
    }

    public static int byId(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** The items of a list. */
    public static int size(int list) {
        switch (list) {
            case STARTUP:
                return LinerChecklist.ITEMS[LinerChecklist.BEFORE_START].length + LinerChecklist.ITEMS[LinerChecklist.AFTER_START].length;
            case BEFORE_TO:
                return LinerChecklist.ITEMS[LinerChecklist.BEFORE_TO].length;
            case APPROACH:
                return APPROACH_ITEMS.length;
            default:
                return AFTER_ITEMS.length;
        }
    }

    /** An item's lang key (its «WHAT|SETTING»). */
    public static String key(int list, int i) {
        switch (list) {
            case STARTUP: {
                int n = LinerChecklist.ITEMS[LinerChecklist.BEFORE_START].length;
                return "sva.liner.chk." + (i < n ? LinerChecklist.ITEMS[LinerChecklist.BEFORE_START][i] : LinerChecklist.ITEMS[LinerChecklist.AFTER_START][i - n]);
            }
            case BEFORE_TO:
                return "sva.liner.chk." + LinerChecklist.ITEMS[LinerChecklist.BEFORE_TO][i];
            case APPROACH:
                return "sva.book.chk.item." + APPROACH_ITEMS[i];
            default:
                return "sva.book.chk.item." + AFTER_ITEMS[i];
        }
    }

    /** Item i holds now. */
    public static boolean done(int list, int i, LinerSystems L) {
        switch (list) {
            case STARTUP: {
                int n = LinerChecklist.ITEMS[LinerChecklist.BEFORE_START].length;
                return i < n ? LinerChecklist.done(LinerChecklist.BEFORE_START, i, L) : LinerChecklist.done(LinerChecklist.AFTER_START, i - n, L);
            }
            case BEFORE_TO:
                return LinerChecklist.done(LinerChecklist.BEFORE_TO, i, L);
            case APPROACH:
                return approach(i, L);
            default:
                return afterLanding(i, L);
        }
    }

    static boolean approach(int i, LinerSystems L) {
        switch (i) {
            case 0:
                return L.on(LinerControls.LS_L) || L.on(LinerControls.LS_R);
            case 1:
                return L.afs.ils != null;
            case 2:
                return L.afs.approachArmedOrOn();
            case 3:
                return L.at(LinerControls.SEAT_BELTS) == 0;
            case 4:
                return L.at(LinerControls.LAND_L) == 0 && L.at(LinerControls.LAND_R) == 0;
            case 5:
                return L.gear.abMode == 1 || L.gear.abMode == 2;
            case 6:
                return L.at(LinerControls.SPD_BRK) == 0;
            case 7:
                return L.at(LinerControls.GEAR) == 1;
            case 8:
                return L.at(LinerControls.FLAPS) >= 3;
            default:
                return false;
        }
    }

    static boolean afterLanding(int i, LinerSystems L) {
        switch (i) {
            case 0:
                return L.at(LinerControls.SPD_BRK) == 1;
            case 1:
                return L.at(LinerControls.FLAPS) == 0;
            case 2:
                return L.at(LinerControls.STROBE) != 0;
            case 3:
                return L.at(LinerControls.LAND_L) != 0 && L.at(LinerControls.LAND_R) != 0;
            case 4:
                return L.at(LinerControls.NOSE_LT) == 1;
            case 5:
                return L.apuRunning();
            case 6:
                return L.gear.abMode == 0;
            default:
                return false;
        }
    }

    /** The done items (bits). */
    public static int doneBits(int list, LinerSystems L) {
        int m = 0;
        for (int i = 0; i < size(list); i++) {
            m |= done(list, i, L) ? 1 << i : 0;
        }
        return m;
    }

    /** The first item not done, or -1 (the list complete) - the one to highlight. */
    public static int next(int list, LinerSystems L) {
        for (int i = 0; i < size(list); i++) {
            if (!done(list, i, L)) {
                return i;
            }
        }
        return -1;
    }

    /** The control an item is about (the outline «Показать» draws), never null. */
    public static LinerControls.Ctl control(int list, int i) {
        String k = key(list, i);
        String item = k.substring(k.lastIndexOf('.') + 1);
        LinerControls.Ctl c = CTL.get(item);
        return c != null ? c : LinerControls.ECAM_RCL;
    }

    /** The control of each item key (LinerChecklist's and the new ones'). */
    static final java.util.Map<String, LinerControls.Ctl> CTL = new java.util.HashMap<String, LinerControls.Ctl>();

    static {
        // before start / after start / before take-off (LinerChecklist)
        CTL.put("bat", LinerControls.BAT1);
        CTL.put("adirs", LinerControls.ADIRS1);
        CTL.put("power", LinerControls.APU_START);
        CTL.put("apuBleed", LinerControls.APU_BLEED);
        CTL.put("pumps", LinerControls.PUMP_L1);
        CTL.put("beacon", LinerControls.BEACON);
        CTL.put("park", LinerControls.PARK_BRK);
        CTL.put("door", LinerControls.DOOR_L1);
        CTL.put("engs", LinerControls.ENG_MASTER2);
        CTL.put("mode", LinerControls.ENG_MODE);
        CTL.put("apuBleedOff", LinerControls.APU_BLEED);
        CTL.put("apuOff", LinerControls.APU_MASTER);
        CTL.put("packs", LinerControls.PACK1);
        CTL.put("flaps", LinerControls.FLAPS);
        CTL.put("trim", LinerControls.TRIM_L);
        CTL.put("sts", LinerControls.ECAM[LinerControls.SD_PAGES.length - 1]);
        CTL.put("config", LinerControls.TO_CONFIG);
        CTL.put("autoBrk", LinerControls.ABRK_MAX);
        CTL.put("splrs", LinerControls.SPD_BRK);
        CTL.put("belts", LinerControls.SEAT_BELTS);
        CTL.put("strobe", LinerControls.STROBE);
        CTL.put("land", LinerControls.LAND_L);
        CTL.put("parkOff", LinerControls.PARK_BRK);
        CTL.put("nose", LinerControls.NOSE_LT);
        // approach
        CTL.put("ls", LinerControls.LS_L);
        CTL.put("ils", LinerControls.MCDU1);
        CTL.put("appr", LinerControls.FCU_APPR);
        CTL.put("landLt", LinerControls.LAND_L);
        CTL.put("splArm", LinerControls.SPD_BRK);
        CTL.put("gear", LinerControls.GEAR);
        // after landing
        CTL.put("splRet", LinerControls.SPD_BRK);
        CTL.put("flapsUp", LinerControls.FLAPS);
        CTL.put("strobeOff", LinerControls.STROBE);
        CTL.put("landOff", LinerControls.LAND_L);
        CTL.put("taxiLt", LinerControls.NOSE_LT);
        CTL.put("apu", LinerControls.APU_START);
        CTL.put("abrkOff", LinerControls.ABRK_LO);
    }

    /** The approach list's autobrake: LO or MED (abMode 1 / 2); the approach's flaps: 3 or FULL. */
    public static boolean landingConfig(LinerSystems L) {
        return approach(7, L) && approach(8, L);
    }

    /** The liner's thrust levers are at FLX or TOGA (the take-off). */
    public static boolean takeOffThrust(LinerSystems L) {
        return L.afs.lever >= LinerAfs.DET_FLX - LinerAfs.DET_EPS;
    }
}
