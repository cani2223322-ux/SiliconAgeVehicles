package com.sva.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liner wave 1 (plan-next §7, mock-ups docs/mockups/veh_liner_6..8): THE control table of the SA-320 cockpit - every
 * clickable thing on the overhead panel, the glare shield, the main panel, the pedestal and the two lateral consoles. One
 * table serves everything: the server's state (LinerSystems keeps one position per row), the 3D clicking (client
 * LinerClickSVA turns a ray into a panel point and asks hit()), the flat «Пульты» screen (GuiLinerPanelsSVA draws the
 * same panels from the same rows), the runtime overlays (lit push-button segments, selector pointers, lever knobs) and
 * the self-test (unique ids, no two hitboxes of a panel overlapping, a lang key for every row and every position word).
 * <p>
 * Coordinates are PANEL UNITS - the texture painter's units (scratchpad liner/interior/itex.py: the overhead is 1800 x
 * 1400 units, 1 unit ~ 1 mm): a row's (cx, cy) is the control's centre on its panel and (w, h) its hitbox. Panel
 * placement in model space: PANEL_* below (the cockpit concept's quads, icockpit.py, moved into the exterior's frame).
 * <p>
 * Kinds: PB - an A320 two-part push button (0 out, 1 in); PBM - a momentary one (an event: APU START, MASTER WARN /
 * CAUT, the ECAM keys); ROT - a rotary selector (n positions, LMB / wheel up = next, RMB / wheel down = previous); TOG -
 * a bat toggle (positions top to bottom: LMB = up, RMB = down); LEV - a lever with detents (gear, ENG MASTER, flaps,
 * speed brake, parking brake: LMB = +1, RMB = -1); INFO - only a tooltip (the thrust levers follow the W / S throttle,
 * the trim wheels). `guard`: a red guard over it - the first click lifts the guard, the next works the switch, RMB on the
 * lifted guard closes it.
 * <p>
 * Pure data (no Minecraft classes): the dedicated server's self-test checks it. APPEND-ONLY ids: the save keeps the
 * positions by id (LinerSystems.writeNBT), not by row number.
 */
public final class LinerControls {

    // ---------------------------------------------------------------- panels

    /** Panels: overhead, glare shield (EFIS + FCU strip), main instrument panel, pedestal, lateral consoles L / R. */
    public static final int OVH = 0, GS = 1, MP = 2, PED = 3, SCL = 4, SCR = 5, PANELS = 6;
    /** Panel sizes in units (itex.py: OVH_W x OVH_H, GS_W x GS_H, MP_W x MP_H, PD_W x PD_H, SC_W x SC_H). */
    public static final int[] PANEL_W = {1800, 2000, 2000, 760, 500, 500}, PANEL_H = {1400, 180, 620, 1660, 1400, 1400};
    /** Lang key part per panel (sva.liner.panel.KEY): the flat screen's tabs, the tooltips. */
    public static final String[] PANEL_KEYS = {"ovh", "gs", "mp", "ped", "scl", "scr"};

    /**
     * The cockpit concept's frame (icockpit.py: metres = blocks, x forward, y up, z right, origin between the pilots at
     * the eye) sits in the liner's model space at this offset: the captain's eye (0, 0, -0.52) lands on (6.88, 2.78,
     * -0.52) - 0.98 behind the windscreen's lower edge (exterior: x 7.86 at y 2.74, liner3d.py's cockpit windows).
     */
    public static final float CK_X = 6.88F, CK_Y = 2.78F;

    /**
     * Flat panels as a parallelogram in the COCKPIT frame: top-left corner, the full U (right along the texture's x) and V
     * (down along its y) - p(tx, ty) = TL + tx / W x U + ty / H x V. The pedestal is not flat (a sloping front part over
     * the throttle quadrant's flat top): its two faces are PED_FRONT (ty 0..600) and PED_TOP (600..1660).
     */
    public static final float[][] FLAT = {
            // overhead: rear-left (-0.02, 0.53, -0.43) .. front (0.60, 0.385); its top reads aft (looking up and back)
            {-0.02F, 0.53F, -0.43F, 0F, 0F, 0.86F, 0.62F, -0.145F, 0F},
            // glare shield's FCU strip: x 0.74, y -0.04 (top) .. -0.215, z +-0.95
            {0.74F, -0.04F, -0.95F, 0F, 0F, 1.90F, 0F, -0.175F, 0F},
            // main panel: top edge (0.80, -0.20) .. bottom (0.68, -0.80), z +-0.95 (tilted back toward the crew)
            {0.80F, -0.20F, -0.95F, 0F, 0F, 1.90F, -0.12F, -0.60F, 0F},
            null,
            // lateral consoles: top (-0.56), forward edge x 0.62 .. aft -0.20, 0.28 wide at the outer walls
            {0.62F, -0.56F, -0.95F, 0F, 0F, 0.28F, -0.82F, 0F, 0F},
            {0.62F, -0.56F, 0.95F, 0F, 0F, -0.28F, -0.82F, 0F, 0F}};
    /** The pedestal's faces (cockpit frame): the sloping front (ty 0..600) and the flat top (600..1660), z +-0.21. */
    public static final float PED_Z = 0.21F, PED_SPLIT = 600F;
    public static final float[] PED_FRONT = {0.675F, -0.79F}, PED_MID = {0.37F, -0.86F};
    public static final float PED_REAR_X = -0.26F;

    /** A panel point (tx, ty) -> the cockpit frame {x, y, z} (`out` filled and returned). */
    public static float[] point(int panel, float tx, float ty, float[] out) {
        if (panel == PED) {
            float z = -PED_Z + tx / PANEL_W[PED] * 2 * PED_Z;
            if (ty <= PED_SPLIT) {
                float k = ty / PED_SPLIT;
                out[0] = PED_FRONT[0] + (PED_MID[0] - PED_FRONT[0]) * k;
                out[1] = PED_FRONT[1] + (PED_MID[1] - PED_FRONT[1]) * k;
            } else {
                out[0] = PED_MID[0] - (ty - PED_SPLIT) / (PANEL_H[PED] - PED_SPLIT) * (PED_MID[0] - PED_REAR_X);
                out[1] = PED_MID[1];
            }
            out[2] = z;
            return out;
        }
        float[] f = FLAT[panel];
        float u = tx / PANEL_W[panel], v = ty / PANEL_H[panel];
        out[0] = f[0] + u * f[3] + v * f[6];
        out[1] = f[1] + u * f[4] + v * f[7];
        out[2] = f[2] + u * f[5] + v * f[8];
        return out;
    }

    // ---------------------------------------------------------------- rows

    public static final int PB = 0, PBM = 1, ROT = 2, TOG = 3, LEV = 4, INFO = 5;

    /** One control. */
    public static final class Ctl {
        public final int index;
        public final String id;
        public final int panel, kind;
        /** Position words (lang sva.liner.pos.WORD); PB: {off, on}-like pairs; INFO: none. */
        public final String[] pos;
        /** The position of a new (cold and dark) liner. */
        public final int def;
        /** Hitbox centre and size, panel units. */
        public final float cx, cy, w, h;
        public boolean guard;
        /**
         * Push buttons: the A320 lamp words - the upper half (a fault / FAULT-like or an availability: ОТКАЗ, ГОТОВ,
         * ДОСТУП, ОТКР...) and the lower one (ВЫКЛ / ВКЛ / АВТО...) as lang words, and whether the lower one lights
         * when the button is OUT (pos 0 - «ВЫКЛ» white) or IN (pos 1 - «ВКЛ» blue). null: no such half.
         */
        public String top, bottom;
        public boolean bottomWhenOut = true;

        Ctl(int index, String id, int panel, int kind, String[] pos, int def, float cx, float cy, float w, float h) {
            this.index = index;
            this.id = id;
            this.panel = panel;
            this.kind = kind;
            this.pos = pos;
            this.def = def;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
        }

        /** Number of positions (INFO / PBM: 1). */
        public int n() {
            return pos == null || pos.length == 0 ? 1 : pos.length;
        }

        /** The control's name (lang). */
        public String nameKey() {
            return "sva.liner.ctl." + id;
        }

        public boolean contains(float tx, float ty) {
            return Math.abs(tx - cx) <= w / 2 && Math.abs(ty - cy) <= h / 2;
        }
    }

    private static final List<Ctl> ROWS = new ArrayList<Ctl>();
    private static final Map<String, Ctl> BY_ID = new HashMap<String, Ctl>();

    private static final String[] OFF_ON = {"off", "on"};
    private static final String[] OFF_AUTO = {"off", "auto"};

    private static Ctl add(String id, int panel, int kind, String[] pos, int def, float cx, float cy, float w, float h) {
        Ctl c = new Ctl(ROWS.size(), id, panel, kind, pos, def, cx, cy, w, h);
        ROWS.add(c);
        BY_ID.put(id, c);
        return c;
    }

    /** An A320 push button (54 units square unless given): lamp words, the default (1 = pushed in). */
    private static Ctl pb(String id, int panel, float cx, float cy, float b, String top, String bottom, boolean bottomWhenOut, int def) {
        Ctl c = add(id, panel, PB, bottomWhenOut ? OFF_ON : OFF_ON, def, cx, cy, b + 4, b + 4);
        c.top = top;
        c.bottom = bottom;
        c.bottomWhenOut = bottomWhenOut;
        return c;
    }

    private static Ctl pbm(String id, int panel, float cx, float cy, float w, float h) {
        return add(id, panel, PBM, null, 0, cx, cy, w, h);
    }

    private static Ctl rot(String id, int panel, float cx, float cy, float r, String[] pos, int def) {
        return add(id, panel, ROT, pos, def, cx, cy, 2 * r + 12, 2 * r + 12);
    }

    /** A bat toggle (z = the painter's size factor: its guard is 26z x 60z). */
    private static Ctl tog(String id, int panel, float cx, float cy, float z, String[] pos, int def) {
        return add(id, panel, TOG, pos, def, cx, cy, 30 * z, 62 * z);
    }

    // ---------------------------------------------------------------- the table (itex.py's layout, panel units)

    // overhead: ADIRS (wave 1: ИНС 1's selector is the old single ИНС switch; the rest is kept, for wave 7)
    public static final Ctl IR1 = pb("ir1", OVH, 250, 110, 54, "fault", "off", true, 1);
    public static final Ctl IR3 = pb("ir3", OVH, 385, 110, 54, "fault", "off", true, 1);
    public static final Ctl IR2 = pb("ir2", OVH, 520, 110, 54, "fault", "off", true, 1);
    public static final Ctl ADR1 = pb("adr1", OVH, 250, 220, 54, "fault", "off", true, 1);
    public static final Ctl ADR3 = pb("adr3", OVH, 385, 220, 54, "fault", "off", true, 1);
    public static final Ctl ADR2 = pb("adr2", OVH, 520, 220, 54, "fault", "off", true, 1);
    public static final Ctl ADIRS1 = rot("adirs1", OVH, 256, 318, 19, new String[] {"off", "nav", "att"}, 0);
    public static final Ctl ADIRS3 = rot("adirs3", OVH, 391, 318, 19, new String[] {"off", "nav", "att"}, 0);
    public static final Ctl ADIRS2 = rot("adirs2", OVH, 526, 318, 19, new String[] {"off", "nav", "att"}, 0);
    // flight control computers (wave 2)
    public static final Ctl ELAC1 = pb("elac1", OVH, 1340, 120, 54, "fault", "off", true, 1);
    public static final Ctl SEC1 = pb("sec1", OVH, 1460, 120, 54, "fault", "off", true, 1);
    public static final Ctl FAC1 = pb("fac1", OVH, 1580, 120, 54, "fault", "off", true, 1);
    public static final Ctl ELAC2 = pb("elac2", OVH, 1320, 270, 54, "fault", "off", true, 1);
    public static final Ctl SEC2 = pb("sec2", OVH, 1430, 270, 54, "fault", "off", true, 1);
    public static final Ctl SEC3 = pb("sec3", OVH, 1540, 270, 54, "fault", "off", true, 1);
    public static final Ctl FAC2 = pb("fac2", OVH, 1660, 270, 54, "fault", "off", true, 1);
    // hydraulics (wave 2)
    public static final Ctl HYD_ENG1 = pb("hydEng1", OVH, 100, 540, 54, "fault", "off", true, 1);
    public static final Ctl HYD_BLUE = pb("hydBlue", OVH, 205, 540, 54, "fault", "auto", false, 1);
    public static final Ctl HYD_PTU = pb("hydPtu", OVH, 300, 540, 54, "fault", "auto", false, 1);
    public static final Ctl HYD_YEL = pb("hydYellow", OVH, 395, 540, 54, "fault", "on", false, 0);
    public static final Ctl HYD_ENG2 = pb("hydEng2", OVH, 490, 540, 54, "fault", "off", true, 1);
    public static final Ctl RAT = pb("rat", OVH, 300, 700, 40, null, "manual", false, 0);
    // fuel (wave 3)
    public static final Ctl XFEED = pb("xfeed", OVH, 880, 500, 54, "open", "on", false, 0);
    public static final Ctl FUEL_MODE = pb("fuelMode", OVH, 1090, 500, 54, "centre", "manual", false, 0);
    public static final Ctl PUMP_L1 = pb("pumpL1", OVH, 670, 682, 48, "fault", "off", true, 1);
    public static final Ctl PUMP_L2 = pb("pumpL2", OVH, 730, 682, 48, "fault", "off", true, 1);
    public static final Ctl PUMP_C1 = pb("pumpC1", OVH, 850, 682, 48, "fault", "off", true, 1);
    public static final Ctl PUMP_C2 = pb("pumpC2", OVH, 910, 682, 48, "fault", "off", true, 1);
    public static final Ctl PUMP_R1 = pb("pumpR1", OVH, 1030, 682, 48, "fault", "off", true, 1);
    public static final Ctl PUMP_R2 = pb("pumpR2", OVH, 1090, 682, 48, "fault", "off", true, 1);
    // electrics (wave 1)
    public static final Ctl BAT1 = pb("bat1", OVH, 1300, 575, 54, "fault", "off", true, 0);
    public static final Ctl BAT2 = pb("bat2", OVH, 1440, 575, 54, "fault", "off", true, 0);
    public static final Ctl EXT_PWR = pb("extPwr", OVH, 1650, 545, 54, "avail", "on", false, 0);
    public static final Ctl GEN1 = pb("gen1", OVH, 1265, 692, 54, "fault", "off", true, 1);
    public static final Ctl BUS_TIE = pb("busTie", OVH, 1375, 692, 54, null, "off", true, 1);
    public static final Ctl APU_GEN = pb("apuGen", OVH, 1485, 692, 54, "fault", "off", true, 1);
    public static final Ctl GEN2 = pb("gen2", OVH, 1595, 692, 54, "fault", "off", true, 1);
    public static final Ctl GALLEY = pb("galley", OVH, 1705, 692, 54, "fault", "off", true, 1);
    // air bleed (wave 1)
    public static final Ctl PACK1 = pb("pack1", OVH, 110, 880, 54, "fault", "off", true, 1);
    public static final Ctl XBLEED = rot("xbleed", OVH, 330, 880, 22, new String[] {"shut", "auto", "open"}, 1);
    public static final Ctl PACK2 = pb("pack2", OVH, 550, 880, 54, "fault", "off", true, 1);
    public static final Ctl BLEED1 = pb("bleed1", OVH, 110, 1015, 54, "fault", "off", true, 1);
    public static final Ctl APU_BLEED = pb("apuBleed", OVH, 330, 1015, 54, "fault", "on", false, 0);
    public static final Ctl BLEED2 = pb("bleed2", OVH, 550, 1015, 54, "fault", "off", true, 1);
    // anti-ice (wave 6)
    public static final Ctl AI_WING = pb("aiWing", OVH, 745, 880, 54, "fault", "on", false, 0);
    public static final Ctl AI_PROBE = pb("aiProbe", OVH, 875, 880, 54, null, "on", false, 0);
    public static final Ctl AI_ENG1 = pb("aiEng1", OVH, 745, 1005, 54, "fault", "on", false, 0);
    public static final Ctl AI_ENG2 = pb("aiEng2", OVH, 875, 1005, 54, "fault", "on", false, 0);
    // APU (wave 1)
    public static final Ctl APU_MASTER = pb("apuMaster", OVH, 1060, 920, 54, "fault", "on", false, 0);
    public static final Ctl APU_START = pbm("apuStart", OVH, 1165, 920, 58, 58);
    // interior lights / signs (wave 1: the dome and the panel brightness work; the signs - wave 6)
    public static final Ctl DOME = tog("dome", OVH, 1325, 895, 1, new String[] {"bright", "dim", "off"}, 2);
    public static final Ctl ANN_BRT = rot("panelBrt", OVH, 1450, 895, 22, new String[] {"0", "1", "2", "3", "4"}, 2);
    public static final Ctl SEAT_BELTS = tog("seatBelts", OVH, 1555, 895, 1, new String[] {"on", "auto", "off"}, 2);
    public static final Ctl NO_SMOKING = tog("noSmoking", OVH, 1680, 895, 1, new String[] {"on", "auto", "off"}, 1);
    public static final Ctl EMER_EXIT = tog("emerExit", OVH, 1325, 1010, 1, new String[] {"on", "armed", "off"}, 1);
    public static final Ctl CABIN_LT = rot("cabinLt", OVH, 1520, 1005, 18, new String[] {"night", "1", "2", "3"}, 3);
    public static final Ctl ANN_LT = tog("annLt", OVH, 1660, 1010, 1, new String[] {"test", "bright", "dim"}, 1);
    // exterior lights (wave 1: the beacon, strobes, nav, landing lights drive the existing lights; the rest wave 6)
    public static final Ctl STROBE = tog("strobe", OVH, 100, 1265, 1.6F, new String[] {"on", "auto", "off"}, 2);
    public static final Ctl BEACON = tog("beacon", OVH, 312, 1265, 1.6F, new String[] {"on", "off"}, 1);
    public static final Ctl WING_LT = tog("wingLt", OVH, 524, 1265, 1.6F, new String[] {"on", "off"}, 1);
    public static final Ctl NAV_LOGO = tog("navLogo", OVH, 736, 1265, 1.6F, new String[] {"1", "off", "2"}, 1);
    public static final Ctl RWY_TURN = tog("rwyTurn", OVH, 948, 1265, 1.6F, new String[] {"on", "off"}, 1);
    public static final Ctl LAND_L = tog("landL", OVH, 1160, 1265, 1.6F, new String[] {"on", "off", "retract"}, 2);
    public static final Ctl LAND_R = tog("landR", OVH, 1372, 1265, 1.6F, new String[] {"on", "off", "retract"}, 2);
    public static final Ctl NOSE_LT = tog("noseLt", OVH, 1584, 1265, 1.6F, new String[] {"to", "taxi", "off"}, 2);
    /** «Быстрый запуск» under a red guard (mock-up 15: «кнопка на верхней панели под колпачком»): up = run. */
    public static final Ctl QUICK = tog("quickStart", OVH, 1700, 1265, 1.6F, new String[] {"run", "off"}, 1);

    // glare shield: EFIS control panels (the ND's mode and range work), master warning / caution
    public static final Ctl[] EFIS_PB = new Ctl[10];
    public static final Ctl ND_MODE_L, ND_RANGE_L, ND_MODE_R, ND_RANGE_R, FD_L, LS_L, FD_R, LS_R;
    /** The ND modes and ranges (blocks across the ND's 0.5-radius ring: the mock-up's 10..320 «км» read as hundreds of blocks... kept as given). */
    public static final String[] ND_MODES = {"ils", "vor", "nav", "arc", "plan"}, ND_RANGES = {"10", "20", "40", "80", "160", "320"};
    public static final int ND_ARC = 3;

    static {
        String[] e = {"cstr", "wpt", "vord", "ndb", "arpt"};
        for (int s = 0; s < 2; s++) {
            int x0 = s == 0 ? 120 : 1480;
            for (int i = 0; i < 5; i++) {
                Ctl c = pb("efis" + (s == 0 ? "L" : "R") + "_" + e[i], GS, x0 + 150 + i * 46, 46, 34, null, "on", false, i == 4 ? 1 : 0);
                EFIS_PB[s * 5 + i] = c;
            }
        }
    }

    static {
        FD_L = pb("fdL", GS, 270, 120, 34, null, "on", false, 1);
        LS_L = pb("lsL", GS, 316, 120, 34, null, "on", false, 0);
        ND_MODE_L = rot("ndModeL", GS, 390, 124, 17, ND_MODES, ND_ARC);
        ND_RANGE_L = rot("ndRangeL", GS, 470, 124, 17, ND_RANGES, 2);
        FD_R = pb("fdR", GS, 1630, 120, 34, null, "on", false, 1);
        LS_R = pb("lsR", GS, 1676, 120, 34, null, "on", false, 0);
        ND_MODE_R = rot("ndModeR", GS, 1750, 124, 17, ND_MODES, ND_ARC);
        ND_RANGE_R = rot("ndRangeR", GS, 1830, 124, 17, ND_RANGES, 2);
    }

    public static final Ctl MW_L = pbm("masterWarnL", GS, 560, 42, 50, 50);
    public static final Ctl MC_L = pbm("masterCautL", GS, 560, 100, 50, 50);
    public static final Ctl MW_R = pbm("masterWarnR", GS, 1440, 42, 50, 50);
    public static final Ctl MC_R = pbm("masterCautR", GS, 1440, 100, 50, 50);

    // main panel: the gear lever (UP top .. DOWN bottom of its slot), autobrake (wave 2), anti-skid (wave 2)
    public static final Ctl GEAR = add("gear", MP, LEV, new String[] {"up", "down"}, 1, 1229, 465, 52, 280);
    public static final Ctl ABRK_LO = pb("abrkLo", MP, 1175, 360, 42, "decel", "on", false, 0);
    public static final Ctl ABRK_MED = pb("abrkMed", MP, 1175, 430, 42, "decel", "on", false, 0);
    public static final Ctl ABRK_MAX = pb("abrkMax", MP, 1175, 500, 42, "decel", "on", false, 0);
    public static final Ctl ANTISKID = tog("antiSkid", MP, 790, 460, 1, new String[] {"on", "off"}, 0);

    // pedestal: the ECAM control panel (the SD's page keys), CLR / RCL (wave 3: EMER CANC and T.O. CONFIG - below)
    public static final String[] SD_PAGES = {"eng", "bleed", "cond", "elec", "hyd", "fuel", "apu", "fctl", "door", "wheel", "all", "sts"};
    public static final Ctl[] ECAM = new Ctl[SD_PAGES.length];

    static {
        for (int i = 0; i < SD_PAGES.length; i++) {
            int r = i / 6, c = i % 6;
            ECAM[i] = pbm("ecam_" + SD_PAGES[i], PED, 150 + c * 80, 522 + r * 48, 70, 32);
        }
    }

    public static final Ctl ECAM_CLR = pbm("ecamClr", PED, 70, 526, 62, 30);
    public static final Ctl ECAM_RCL = pbm("ecamRcl", PED, 70, 574, 62, 30);
    /** The thrust levers follow the W / S throttle (a tooltip only); the trim wheels (wave 2). */
    public static final Ctl TL1 = add("tl1", PED, INFO, null, 0, 330, 835, 44, 380);
    public static final Ctl TL2 = add("tl2", PED, INFO, null, 0, 430, 835, 44, 380);
    public static final Ctl TRIM_L = add("trimL", PED, INFO, null, 0, 150, 835, 30, 370);
    public static final Ctl TRIM_R = add("trimR", PED, INFO, null, 0, 575, 835, 30, 370);
    /**
     * Speed brake: ВЗВЕД (the ground spoilers armed - the lever pulled up above УБР; RMB from УБР) / УБР / 1/2 / ПОЛН. Wave 2
     * put ВЗВЕД in front: a save from wave 1 (no LinerSystems.K_W2) has its position moved up one on loading.
     */
    public static final Ctl SPD_BRK = add("spdBrk", PED, LEV, new String[] {"arm", "ret", "half", "full"}, 1, 80, 760, 76, 300);
    /** Flaps: 0 / 1 / 2 / 3 / FULL - the liner's HighLiftMath settings. */
    public static final Ctl FLAPS = add("flaps", PED, LEV, new String[] {"0", "1", "2", "3", "full"}, 0, 657, 792, 96, 290);
    public static final Ctl ENG_MASTER1 = add("engMaster1", PED, LEV, new String[] {"off", "on"}, 0, 210, 1158, 64, 84);
    public static final Ctl ENG_MASTER2 = add("engMaster2", PED, LEV, new String[] {"off", "on"}, 0, 550, 1158, 64, 84);
    public static final Ctl ENG_MODE = rot("engMode", PED, 380, 1160, 20, new String[] {"crank", "norm", "start"}, 1);
    public static final Ctl PARK_BRK = add("parkBrk", PED, LEV, new String[] {"off", "on"}, 1, 140, 1255, 76, 60);

    // lateral consoles: the crew panels (brightness of the PFD / ND, the loudspeaker, the console / floor light)
    public static final Ctl PFD_BRT_L = rot("pfdBrtL", SCL, 95, 100, 24, new String[] {"0", "1", "2", "3", "4"}, 4);
    public static final Ctl ND_BRT_L = rot("ndBrtL", SCL, 250, 100, 24, new String[] {"0", "1", "2", "3", "4"}, 4);
    public static final Ctl VOL_L = rot("volL", SCL, 405, 100, 24, new String[] {"0", "1", "2", "3", "4"}, 3);
    public static final Ctl XFR_L = pb("xfrL", SCL, 110, 222, 42, null, "on", false, 0);
    public static final Ctl FLOOR_L = tog("floorL", SCL, 300, 225, 1, new String[] {"bright", "dim", "off"}, 2);
    public static final Ctl PFD_BRT_R = rot("pfdBrtR", SCR, 405, 100, 24, new String[] {"0", "1", "2", "3", "4"}, 4);
    public static final Ctl ND_BRT_R = rot("ndBrtR", SCR, 250, 100, 24, new String[] {"0", "1", "2", "3", "4"}, 4);
    public static final Ctl VOL_R = rot("volR", SCR, 95, 100, 24, new String[] {"0", "1", "2", "3", "4"}, 3);
    public static final Ctl XFR_R = pb("xfrR", SCR, 390, 222, 42, null, "on", false, 0);
    public static final Ctl FLOOR_R = tog("floorR", SCR, 200, 225, 1, new String[] {"bright", "dim", "off"}, 2);

    // wave 2: the gravity gear extension crank (3 turns = 3 clicks, RMB back), the brake fans, the nose-wheel steering's
    // tow switch (БУКСИР: on - no steering)
    public static final Ctl GEAR_GRAV = add("gearGrav", PED, LEV, new String[] {"0", "1", "2", "3"}, 0, 380, 1565, 250, 120);
    public static final Ctl BRK_FAN = pb("brkFan", MP, 1320, 520, 42, "hot", "on", false, 0);
    public static final Ctl TOW = tog("tow", MP, 680, 460, 1, new String[] {"on", "off"}, 1);

    // wave 3: the ECAM control panel's right-hand keys (painted «АВАР ОТМЕНА» / «ТО КОНФИГ»): EMER CANC and T.O. CONFIG
    public static final Ctl ECAM_EMER = pbm("ecamEmerCanc", PED, 692, 526, 72, 28);
    public static final Ctl TO_CONFIG = pbm("toConfig", PED, 692, 574, 72, 28);

    static {
        RAT.guard = true;
        EMER_EXIT.guard = true;
        QUICK.guard = true;
        // the engines' fire handles are drawn on the overhead (wave 2 / 3: the fire system) - no row yet
    }

    /** Number of rows. */
    public static int count() {
        return ROWS.size();
    }

    public static Ctl get(int i) {
        return i >= 0 && i < ROWS.size() ? ROWS.get(i) : null;
    }

    public static Ctl byId(String id) {
        return BY_ID.get(id);
    }

    public static List<Ctl> all() {
        return java.util.Collections.unmodifiableList(ROWS);
    }

    /** The rows of one panel. */
    public static List<Ctl> of(int panel) {
        List<Ctl> l = new ArrayList<Ctl>();
        for (Ctl c : ROWS) {
            if (c.panel == panel) {
                l.add(c);
            }
        }
        return l;
    }

    /** The row whose hitbox holds the panel point (tx, ty), or null. */
    public static Ctl hit(int panel, float tx, float ty) {
        for (Ctl c : ROWS) {
            if (c.panel == panel && c.contains(tx, ty)) {
                return c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- actions (client -> server)

    /** Click actions: LMB (next / up / toggle / lift the guard), RMB (previous / down / close the guard), wheel +1 / -1. */
    public static final int A_PRIMARY = 0, A_SECONDARY = 1, A_WHEEL_UP = 2, A_WHEEL_DOWN = 3,
            /** Set a lever / selector to an exact position (a drag in the flat screen): A_SET + position. */
            A_SET = 16;

    /**
     * What a click does to a control: the new position (and the guard) from the old - pure. @return {position, guard 0/1,
     * event 0/1} - event: a momentary button was pressed (the systems act on it).
     */
    public static int[] apply(Ctl c, int pos, boolean guardOpen, int action) {
        int n = c.n();
        int p = pos;
        boolean g = guardOpen;
        int event = 0;
        if (c.kind == INFO) {
            return new int[] {p, g ? 1 : 0, 0};
        }
        if (c.guard && !g) {
            if (action == A_PRIMARY) {
                g = true;                                           // lift the guard first
            }
            return new int[] {p, g ? 1 : 0, 0};
        }
        if (c.guard && action == A_SECONDARY && c.kind != TOG) {
            return new int[] {p, 0, 0};                             // close the guard (a push button keeps its state)
        }
        if (action >= A_SET) {
            p = Math.max(0, Math.min(n - 1, action - A_SET));
            return new int[] {p, g ? 1 : 0, 0};
        }
        switch (c.kind) {
            case PB:
                if (action == A_PRIMARY) {
                    p = p == 0 ? 1 : 0;
                }
                break;
            case PBM:
                if (action == A_PRIMARY) {
                    event = 1;
                }
                break;
            case ROT:
            case LEV:
                if (action == A_PRIMARY || action == A_WHEEL_UP) {
                    p = Math.min(n - 1, p + 1);
                } else if (action == A_SECONDARY || action == A_WHEEL_DOWN) {
                    p = Math.max(0, p - 1);
                }
                break;
            case TOG:
                // positions top to bottom: LMB / wheel up = up (index - 1), RMB / wheel down = down
                if (action == A_PRIMARY || action == A_WHEEL_UP) {
                    p = Math.max(0, p - 1);
                } else if (action == A_SECONDARY || action == A_WHEEL_DOWN) {
                    if (c.guard && p == n - 1) {
                        g = false;                                  // already down: RMB again closes the guard
                    } else {
                        p = Math.min(n - 1, p + 1);
                    }
                }
                if (c.guard && p == n - 1 && action == A_SECONDARY) {
                    g = false;                                      // a guarded toggle goes back under its guard when down
                }
                break;
            default:
                break;
        }
        return new int[] {p, g ? 1 : 0, event};
    }

    /** Every position word a row uses (the self-test's lang check). */
    public static java.util.Set<String> words() {
        java.util.Set<String> s = new java.util.TreeSet<String>();
        for (Ctl c : ROWS) {
            if (c.pos != null) {
                java.util.Collections.addAll(s, c.pos);
            }
            if (c.top != null) {
                s.add(c.top);
            }
            if (c.bottom != null) {
                s.add(c.bottom);
            }
        }
        return s;
    }

    private LinerControls() {
    }
}
