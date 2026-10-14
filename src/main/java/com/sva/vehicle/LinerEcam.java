package com.sva.vehicle;

/**
 * Liner wave 3 (plan-next §7, mock-ups veh_liner_12 E/WD, 13a / 13b SD): the SA-320's ECAM - ONE message engine for every
 * warning and caution of waves 1-3 (ELEC / APU / BLEED / ENG / HYD / F/CTL / BRAKES / FUEL / DOORS / CONFIG), pure logic,
 * server-authoritative (LinerSystems ticks it; the client gets its words through the sync string).
 * <p>
 * <b>Messages</b> (the table below, its order = the priority inside a level): an id, a level (red WARN over amber CAUT),
 * the flight phases it is inhibited in, a condition (cond: the systems' state and the tick's environment), action lines
 * (cyan «- РЕЖИМ ДВ .... ЗАПУСК», each with its completion check - a done line disappears), the related SD page, and what
 * it puts on the STATUS page (inoperative systems, limitations). The first 7 red and 27 amber keep their wave 1-2 bits
 * (LinerSystems.warn / caut - the raw conditions, the HUD and the old tests read them).
 * <p>
 * <b>Flight phases</b> (a simple A320-like model): 1 power on (on the ground, no engine), 2 engine start / taxi, 3 take-off
 * power (and the rejected take-off's roll), 4 lift-off to CLIMB_OUT_H, 5 climb / cruise, 6 approach (gear lever down under
 * Env.ldgMemoH), 7 final (under FINAL_H) to touch-down, 8 the landing roll down to V80. Most cautions are inhibited in the
 * A320's phases 4-5 and 7-8 - here: phase 3 above V80, 4, 7 and 8; they show (and chime) once the phase is over.
 * <p>
 * <b>The panel</b>: CLR takes the top message off the E/WD (the next one and its page come up; after the last, STATUS);
 * RCL brings back the cleared and cancelled ones («НОРМА» when there is nothing); STS shows STATUS; EMER CANC silences a
 * warning's chime or cancels the top caution (for the flight; RCL restores it); T.O. CONFIG tests the take-off
 * configuration (the CONFIG warnings for TO_TEST_TICKS, «ТО КОНФИГ НОРМ» when good - the same checks fire by themselves at
 * take-off thrust). MASTER WARN / MASTER CAUT light (with the chimes - the entity plays them) for every new red / amber
 * message and go out when pressed.
 * <p>
 * Memos: T.O. MEMO (on the ground with both engines running) and LDG MEMO (phases 6-7) with their items, computed on both
 * sides from the synced state; the SD's automatic page (sheet 13a): the failure's page, ДВЕРИ with the engines off, ДВИГ
 * during a start and the take-off, КОЛЁСА on the taxi and with the gear down low, УПР on the flight-control check, the
 * cruise page in flight.
 */
public final class LinerEcam {

    // ---------------------------------------------------------------- levels, phases, pages

    public static final int CAUT = 1, WARN = 2;
    public static final int PH_NONE = 0, PH_POWER = 1, PH_TAXI = 2, PH_TO = 3, PH_CLIMB_OUT = 4, PH_CRUISE = 5, PH_APPR = 6, PH_FINAL = 7,
            PH_ROLLOUT = 8;
    /** The inhibition masks' extra bit: phase 3 above V80 (the fast part of the take-off run). */
    public static final int PH_TO_FAST = 9;
    /** The cautions' inhibition: the take-off (3 fast, 4) and the landing (7, 8). */
    public static final int INH = 1 << PH_TO_FAST | 1 << PH_CLIMB_OUT | 1 << PH_FINAL | 1 << PH_ROLLOUT;
    /** V80 (b/t: ~55 % of the rotation speed), the climb-out phase's top (blocks over the ground) and its longest, the final's top. */
    public static final double V80 = 0.7, CLIMB_OUT_H = 40, FINAL_H = 15;
    public static final int CLIMB_OUT_MAX = 1200;
    /** T.O. CONFIG's test shows its result this long (ticks); RCL's «НОРМА». */
    public static final int TO_TEST_TICKS = 100, RCL_NORMAL = 100;
    /** The THS off its take-off setting by more than this (deg) - «КОНФИГ ВЗЛЁТ СТАБ». */
    public static final double TRIM_TOL = 1.2;

    /** SD pages: LinerControls.SD_PAGES (0..11: the keys; 10 ВСЕ is a cycle, not a page) and two more of the ECAM's own. */
    public static final int P_ENG = 0, P_BLEED = 1, P_COND = 2, P_ELEC = 3, P_HYD = 4, P_FUEL = 5, P_APU = 6, P_FCTL = 7, P_DOOR = 8, P_WHEEL = 9,
            P_ALL = 10, P_STS = 11, P_CHECK = 12, P_CRZ = 13, PAGES = 14;
    public static final String[] PAGE_KEYS = {"eng", "bleed", "cond", "elec", "hyd", "fuel", "apu", "fctl", "door", "wheel", "all", "sts", "check", "crz"};
    /** The ВСЕ key's cycle (each press the next; after the last - automatic). */
    public static final int[] ALL_CYCLE = {P_ENG, P_BLEED, P_COND, P_ELEC, P_HYD, P_FUEL, P_APU, P_FCTL, P_DOOR, P_WHEEL, P_CHECK};

    // ---------------------------------------------------------------- inoperative systems / limitations (STATUS)

    public static final int I_ENG1 = 1, I_ENG2 = 2, I_GEN1 = 4, I_GEN2 = 8, I_APU = 16, I_BLEED1 = 32, I_BLEED2 = 64, I_G = 128, I_B = 256,
            I_Y = 512, I_FUEL_L = 1024, I_FUEL_R = 2048, I_PUMP = 4096, I_FCTL = 8192;
    public static final String[] INOP_KEYS = {"eng1", "eng2", "gen1", "gen2", "apu", "bleed1", "bleed2", "hydG", "hydB", "hydY", "fuelL", "fuelR",
            "fuelPump", "fctl"};
    public static final int LIM_LAND = 1, LIM_GRAV = 2, LIM_ALTN = 4, LIM_DIRECT = 8, LIM_IMBAL = 16, LIM_FLAPS = 32, LIM_FUEL = 64;
    public static final String[] LIM_KEYS = {"land", "grav", "altn", "direct", "imbal", "flaps", "fuel"};

    // ---------------------------------------------------------------- action lines

    public static final int A_MODE_START = 0, A_THR_IDLE = 1, A_MASTER1_OFF = 2, A_MASTER2_OFF = 3, A_PTU_OFF = 4, A_BRK_FAN_ON = 5, A_GEAR_DOWN = 6,
            A_FLAPS_TO = 7, A_SPDBRK_RET = 8, A_PARK_OFF = 9, A_RAT_MAN = 10, A_APU_BLEED_ON = 11, A_GEN1_ON = 12, A_GEN2_ON = 13,
            A_APU_MASTER_OFF = 14, A_XBLEED_OPEN = 15, A_BUS_TIE_ON = 16, A_EDP1_OFF = 17, A_BLUE_OFF = 18, A_EDP2_OFF = 19, A_PUMP_OFF = 20,
            A_XFEED_ON = 26, A_LIGHT_PUMPS_OFF = 27, A_CTR_MAN = 28, A_DOOR_SHUT = 29, ACTS = 30;
    /** Lang sva.liner.act.KEY = «WHAT|SETTING» (drawn «- WHAT ...... SETTING»). */
    public static final String[] ACT_KEYS = {"modeStart", "thrIdle", "master1Off", "master2Off", "ptuOff", "brkFanOn", "gearDown", "flapsTo",
            "spdBrkRet", "parkOff", "ratMan", "apuBleedOn", "gen1On", "gen2On", "apuMasterOff", "xbleedOpen", "busTieOn", "edp1Off", "blueOff",
            "edp2Off", "pumpL1Off", "pumpL2Off", "pumpC1Off", "pumpC2Off", "pumpR1Off", "pumpR2Off", "xfeedOn", "lightPumpsOff", "ctrMan",
            "doorShut"};

    // ---------------------------------------------------------------- the messages

    public static final int M_ENG1_FAIL = 0, M_ENG2_FAIL = 1, M_HYD_GB = 2, M_HYD_GY = 3, M_HYD_BY = 4, M_TO_BRK = 5, M_GEAR_NOT_DOWN = 6,
            M_CFG_FLAPS = 7, M_CFG_SPDBRK = 8, M_CFG_TRIM = 9, M_CFG_PARK = 10, M_ELEC_EMER = 11,
            M_ENG1_START = 12, M_ENG2_START = 13, M_ENG1_AIR = 14, M_ENG2_AIR = 15, M_GEN1 = 16, M_GEN2 = 17, M_APU = 18, M_BLEED = 19, M_BUS1 = 20,
            M_BUS2 = 21, M_HYD_G_LO = 22, M_HYD_B_LO = 23, M_HYD_Y_LO = 24, M_HYD_G_QTY = 25, M_HYD_B_QTY = 26, M_HYD_Y_QTY = 27, M_HYD_G_HOT = 28,
            M_HYD_B_HOT = 29, M_HYD_Y_HOT = 30, M_BRK_HOT = 31, M_FLAP_JAM = 32, M_SLAT_JAM = 33, M_GEAR_DISAGREE = 34, M_TYRE = 35, M_LAW_ALTN = 36,
            M_LAW_DIRECT = 37, M_FCTL_COMP = 38, M_FUEL_PUMP = 39, M_FUEL_IMBAL = 45, M_FUEL_LO_L = 46, M_FUEL_LO_R = 47, M_FUEL_OUT_L = 48,
            M_FUEL_OUT_R = 49, M_FUEL_AUTO = 50, M_DOOR = 51, N = 52;
    /** The first red / amber with a wave 1-2 bit (LinerSystems.warn bit = id; caut bit = id - FIRST_CAUT for ids under LEGACY_CAUT_END). */
    public static final int LEGACY_WARN_END = 7, FIRST_CAUT = 12, LEGACY_CAUT_END = 39;

    /** Lang sva.liner.msg.KEY. */
    public static final String[] KEYS = new String[N];
    public static final int[] LEVEL = new int[N], PAGE = new int[N], INHIBIT = new int[N], INOP = new int[N], LIMIT = new int[N];
    public static final int[][] ACT = new int[N][];

    private static void def(int id, String key, int level, int page, int inh, int inop, int lim, int... acts) {
        KEYS[id] = key;
        LEVEL[id] = level;
        PAGE[id] = page;
        INHIBIT[id] = inh;
        INOP[id] = inop;
        LIMIT[id] = lim;
        ACT[id] = acts;
    }

    static {
        // red
        def(M_ENG1_FAIL, "eng1Fail", WARN, P_ENG, 0, I_ENG1 | I_GEN1 | I_BLEED1, LIM_LAND, A_MODE_START, A_THR_IDLE, A_MASTER1_OFF);
        def(M_ENG2_FAIL, "eng2Fail", WARN, P_ENG, 0, I_ENG2 | I_GEN2 | I_BLEED2, LIM_LAND, A_MODE_START, A_THR_IDLE, A_MASTER2_OFF);
        def(M_HYD_GB, "hydGB", WARN, P_HYD, 0, I_G | I_B, LIM_LAND);
        def(M_HYD_GY, "hydGY", WARN, P_HYD, 0, I_G | I_Y, LIM_LAND | LIM_GRAV, A_PTU_OFF);
        def(M_HYD_BY, "hydBY", WARN, P_HYD, 0, I_B | I_Y, LIM_LAND);
        def(M_TO_BRK, "toBrkHot", WARN, P_WHEEL, 0, 0, 0, A_BRK_FAN_ON);
        def(M_GEAR_NOT_DOWN, "gearNotDown", WARN, P_WHEEL, 0, 0, 0, A_GEAR_DOWN);
        def(M_CFG_FLAPS, "cfgFlaps", WARN, P_FCTL, 0, 0, 0, A_FLAPS_TO);
        def(M_CFG_SPDBRK, "cfgSpdBrk", WARN, P_FCTL, 0, 0, 0, A_SPDBRK_RET);
        def(M_CFG_TRIM, "cfgTrim", WARN, P_FCTL, 0, 0, 0);
        def(M_CFG_PARK, "cfgPark", WARN, P_WHEEL, 0, 0, 0, A_PARK_OFF);
        def(M_ELEC_EMER, "elecEmer", WARN, P_ELEC, 0, I_GEN1 | I_GEN2, LIM_LAND, A_RAT_MAN);
        // amber (wave 1)
        def(M_ENG1_START, "eng1Start", CAUT, P_ENG, INH, 0, 0, A_MASTER1_OFF);
        def(M_ENG2_START, "eng2Start", CAUT, P_ENG, INH, 0, 0, A_MASTER2_OFF);
        def(M_ENG1_AIR, "eng1Air", CAUT, P_BLEED, INH, 0, 0, A_APU_BLEED_ON);
        def(M_ENG2_AIR, "eng2Air", CAUT, P_BLEED, INH, 0, 0, A_APU_BLEED_ON);
        def(M_GEN1, "gen1", CAUT, P_ELEC, INH, I_GEN1, 0, A_GEN1_ON);
        def(M_GEN2, "gen2", CAUT, P_ELEC, INH, I_GEN2, 0, A_GEN2_ON);
        def(M_APU, "apuFault", CAUT, P_APU, INH, I_APU, 0, A_APU_MASTER_OFF);
        def(M_BLEED, "bleedLow", CAUT, P_BLEED, INH, 0, 0, A_XBLEED_OPEN);
        def(M_BUS1, "bus1", CAUT, P_ELEC, INH, 0, 0, A_BUS_TIE_ON);
        def(M_BUS2, "bus2", CAUT, P_ELEC, INH, 0, 0, A_BUS_TIE_ON);
        // amber (wave 2)
        def(M_HYD_G_LO, "hydGLo", CAUT, P_HYD, INH, I_G, 0, A_EDP1_OFF);
        def(M_HYD_B_LO, "hydBLo", CAUT, P_HYD, INH, I_B, 0, A_BLUE_OFF);
        def(M_HYD_Y_LO, "hydYLo", CAUT, P_HYD, INH, I_Y, 0, A_EDP2_OFF);
        def(M_HYD_G_QTY, "hydGQty", CAUT, P_HYD, INH, I_G, 0, A_PTU_OFF, A_EDP1_OFF);
        def(M_HYD_B_QTY, "hydBQty", CAUT, P_HYD, INH, I_B, 0, A_BLUE_OFF);
        def(M_HYD_Y_QTY, "hydYQty", CAUT, P_HYD, INH, I_Y, 0, A_PTU_OFF, A_EDP2_OFF);
        def(M_HYD_G_HOT, "hydGHot", CAUT, P_HYD, INH, I_G, 0, A_PTU_OFF, A_EDP1_OFF);
        def(M_HYD_B_HOT, "hydBHot", CAUT, P_HYD, INH, I_B, 0, A_BLUE_OFF);
        def(M_HYD_Y_HOT, "hydYHot", CAUT, P_HYD, INH, I_Y, 0, A_PTU_OFF, A_EDP2_OFF);
        def(M_BRK_HOT, "brkHot", CAUT, P_WHEEL, INH, 0, 0, A_BRK_FAN_ON);
        def(M_FLAP_JAM, "flapJam", CAUT, P_FCTL, INH, 0, LIM_FLAPS);
        def(M_SLAT_JAM, "slatJam", CAUT, P_FCTL, INH, 0, LIM_FLAPS);
        def(M_GEAR_DISAGREE, "gearDisagree", CAUT, P_WHEEL, INH, 0, LIM_GRAV);
        def(M_TYRE, "tyreFlat", CAUT, P_WHEEL, INH, 0, 0);
        def(M_LAW_ALTN, "lawAltn", CAUT, P_FCTL, INH, I_FCTL, LIM_ALTN);
        def(M_LAW_DIRECT, "lawDirect", CAUT, P_FCTL, INH, I_FCTL, LIM_ALTN | LIM_DIRECT);
        def(M_FCTL_COMP, "fctlComp", CAUT, P_FCTL, INH, I_FCTL, 0);
        // amber (wave 3): fuel, doors
        String[] pk = {"L1", "L2", "C1", "C2", "R1", "R2"};
        for (int p = 0; p < LinerFuel.PUMPS; p++) {
            def(M_FUEL_PUMP + p, "fuelPump" + pk[p], CAUT, P_FUEL, INH, I_PUMP, 0, A_PUMP_OFF + p);
        }
        def(M_FUEL_IMBAL, "fuelImbal", CAUT, P_FUEL, INH, 0, LIM_IMBAL, A_XFEED_ON, A_LIGHT_PUMPS_OFF);
        def(M_FUEL_LO_L, "fuelLoL", CAUT, P_FUEL, INH, 0, LIM_FUEL);
        def(M_FUEL_LO_R, "fuelLoR", CAUT, P_FUEL, INH, 0, LIM_FUEL);
        def(M_FUEL_OUT_L, "fuelOutL", CAUT, P_FUEL, INH, I_FUEL_L, LIM_FUEL, A_XFEED_ON);
        def(M_FUEL_OUT_R, "fuelOutR", CAUT, P_FUEL, INH, I_FUEL_R, LIM_FUEL, A_XFEED_ON);
        def(M_FUEL_AUTO, "fuelAutoFeed", CAUT, P_FUEL, INH, 0, 0, A_CTR_MAN);
        def(M_DOOR, "doorL1", CAUT, P_DOOR, INH, 0, 0, A_DOOR_SHUT);
    }

    // ---------------------------------------------------------------- state

    /** Raw conditions now, shown (not inhibited, not cancelled), taken off by CLR, cancelled by EMER CANC; last tick's shown. */
    public long active, shown, cleared, cancelled, prevShown;
    /** Action lines done now (bits by A_*). */
    public long done;
    /** The flight phase (PH_*), its ticks, the fast take-off run. */
    public int phase = PH_POWER, phaseTicks;
    public boolean toFast;
    /** The approach was above FINAL_H (so going under it is the final). */
    boolean apprHigh;
    /** T.O. CONFIG: the test's ticks left; the configuration found good (kept while it stays good on the ground). */
    public int toTest;
    public boolean toCfgOk;
    /** STATUS shown by itself after the last CLR; STATUS seen (the checklist); the red chime silenced; RCL's «НОРМА» ticks. */
    public boolean stsAuto, stsSeen, silenced;
    public int rclNormal;

    // ---------------------------------------------------------------- the tick

    /** One server tick (after the systems): the phase, the conditions, the masters and the chimes (LinerSystems.events). */
    public void tick(LinerSystems L, LinerSystems.Env e) {
        int p = phaseOf(L, e);
        phaseTicks = p == phase ? phaseTicks + 1 : 0;
        phase = p;
        toFast = phase == PH_TO && e.speed > V80;
        if (toTest > 0) {
            toTest--;
        }
        if (rclNormal > 0) {
            rclNormal--;
        }
        if (phase == PH_POWER && !L.anyRunning()) {
            stsSeen = false;                                         // a new flight: the STATUS is to be looked at again
        }
        // the conditions
        long a = 0;
        for (int id = 0; id < N; id++) {
            if (cond(id, L, e)) {
                a |= 1L << id;
            }
        }
        active = a;
        // the configuration found good by the test (and kept while it stays good, on the ground, engines running)
        boolean cfgBad = (a & (1L << M_CFG_FLAPS | 1L << M_CFG_SPDBRK | 1L << M_CFG_TRIM | 1L << M_TO_BRK)) != 0 || !configGood(L);
        if (toTest > 0 && !cfgBad) {
            toCfgOk = true;
        }
        if (cfgBad || !e.ground || !L.anyRunning()) {
            toCfgOk = toCfgOk && toTest > 0 && !cfgBad;
        }
        // shown: not inhibited in this phase, not cancelled; the cleared ones forgotten once their condition goes
        int bit = 1 << (toFast ? PH_TO_FAST : phase);
        long s = 0;
        for (int id = 0; id < N; id++) {
            if ((a & 1L << id) != 0 && (INHIBIT[id] & bit) == 0) {
                s |= 1L << id;
            }
        }
        cleared &= a;
        cancelled &= a;
        s &= ~cancelled;
        shown = s;
        // new red / amber: the masters and the chimes; gone: their lamps out
        long fresh = s & ~prevShown;
        if ((fresh & levelMask(WARN)) != 0) {
            L.masterWarn = true;
            silenced = false;
            L.events |= LinerSystems.E_CHIME_WARN;
        }
        if ((fresh & levelMask(CAUT)) != 0) {
            L.masterCaut = true;
            L.events |= LinerSystems.E_CHIME_CAUT;
        }
        if ((s & levelMask(WARN)) == 0) {
            L.masterWarn = false;
        }
        if ((s & levelMask(CAUT)) == 0) {
            L.masterCaut = false;
        }
        prevShown = s;
        // the old bits (raw)
        int w = 0, c = 0;
        for (int id = 0; id < LEGACY_WARN_END; id++) {
            w |= (a & 1L << id) != 0 ? 1 << id : 0;
        }
        for (int id = FIRST_CAUT; id < LEGACY_CAUT_END; id++) {
            c |= (a & 1L << id) != 0 ? 1 << (id - FIRST_CAUT) : 0;
        }
        L.warn = w;
        L.caut = c;
        // the action lines
        long d = 0;
        for (int i = 0; i < ACTS; i++) {
            if (actionDone(i, L, e)) {
                d |= 1L << i;
            }
        }
        done = d;
        if (stsAuto && !hasStatus()) {
            stsAuto = false;
        }
    }

    /** The bits of every message of this level. */
    public static long levelMask(int level) {
        long m = 0;
        for (int id = 0; id < N; id++) {
            if (LEVEL[id] == level) {
                m |= 1L << id;
            }
        }
        return m;
    }

    /** The phase now from the last one (see the class comment). */
    int phaseOf(LinerSystems L, LinerSystems.Env e) {
        boolean run = L.anyRunning();
        if (e.ground) {
            apprHigh = false;
            if (phase == PH_CLIMB_OUT || phase == PH_CRUISE || phase == PH_APPR || phase == PH_FINAL) {
                return PH_ROLLOUT;                                   // touch-down
            }
            if (phase == PH_ROLLOUT) {
                return e.speed > V80 ? PH_ROLLOUT : run ? PH_TAXI : PH_POWER;
            }
            if (run && e.throttle >= LinerFctl.TO_THR) {
                return PH_TO;
            }
            if (phase == PH_TO && e.speed > V80) {
                return PH_TO;                                        // the rejected take-off's roll
            }
            return run ? PH_TAXI : PH_POWER;
        }
        if (phase == PH_TO || phase == PH_CLIMB_OUT && e.height < CLIMB_OUT_H && phaseTicks < CLIMB_OUT_MAX) {
            return PH_CLIMB_OUT;
        }
        if (L.at(LinerControls.GEAR) == 1 && e.height < e.ldgMemoH) {
            // the final: descending through FINAL_H on the approach (an aircraft put into the air low is not landing)
            apprHigh |= e.height >= FINAL_H;
            return e.height < FINAL_H && apprHigh ? PH_FINAL : PH_APPR;
        }
        apprHigh = false;
        return PH_CRUISE;
    }

    /** The take-off configuration checks are on: the T.O. CONFIG test, or take-off thrust on the ground. */
    boolean cfgCheck(LinerSystems L, LinerSystems.Env e) {
        return e.ground && (toTest > 0 || e.throttle >= LinerFctl.TO_THR && L.anyRunning());
    }

    /** The take-off configuration (flaps 1..3, speed brake not out, the THS at its setting). */
    static boolean configGood(LinerSystems L) {
        return flapsTo(L) && L.at(LinerControls.SPD_BRK) <= 1 && trimOk(L);
    }

    static boolean flapsTo(LinerSystems L) {
        int f = L.at(LinerControls.FLAPS);
        return f >= 1 && f <= 3;
    }

    static boolean trimOk(LinerSystems L) {
        return Math.abs(L.fctl.angle[LinerFctl.THS] - LinerSystems.thsTrim(L.fctl.flapPos)) <= TRIM_TOL;
    }

    /** The systems' state that should have pressure / fuel flowing: an engine running or in the air. */
    static boolean live(LinerSystems L, LinerSystems.Env e) {
        return L.anyRunning() || !e.ground;
    }

    /** Message id's condition now. */
    boolean cond(int id, LinerSystems L, LinerSystems.Env e) {
        if (id >= M_FUEL_PUMP && id < M_FUEL_PUMP + LinerFuel.PUMPS) {
            int p = id - M_FUEL_PUMP;
            boolean dryWing = !LinerFuel.centre(p) && L.fuel.qty[LinerFuel.tankOf(p)] < 0.01;
            return live(L, e) && (L.fuel.lowPr & 1 << p) != 0 && !dryWing;
        }
        LinerSystems.Eng e1 = L.eng[0], e2 = L.eng[1];
        switch (id) {
            case M_ENG1_FAIL:
                return e1.fault == LinerSystems.F_FLAMEOUT && !e.ground;
            case M_ENG2_FAIL:
                return e2.fault == LinerSystems.F_FLAMEOUT && !e.ground;
            case M_HYD_GB:
                return lowHyd(L, e, LinerHyd.G) && lowHyd(L, e, LinerHyd.B);
            case M_HYD_GY:
                return lowHyd(L, e, LinerHyd.G) && lowHyd(L, e, LinerHyd.Y);
            case M_HYD_BY:
                return lowHyd(L, e, LinerHyd.B) && lowHyd(L, e, LinerHyd.Y);
            case M_TO_BRK:
                return L.gear.hot() && cfgCheck(L, e);
            case M_GEAR_NOT_DOWN:
                return !e.ground && !L.gear.downLocked() && e.height < 150 && e.throttle < 0.2 && L.at(LinerControls.FLAPS) >= 3;
            case M_CFG_FLAPS:
                return cfgCheck(L, e) && !flapsTo(L);
            case M_CFG_SPDBRK:
                return cfgCheck(L, e) && L.at(LinerControls.SPD_BRK) >= 2;
            case M_CFG_TRIM:
                return cfgCheck(L, e) && !trimOk(L);
            case M_CFG_PARK:
                return e.ground && L.anyRunning() && e.throttle >= LinerFctl.TO_THR && L.on(LinerControls.PARK_BRK);
            case M_ELEC_EMER:
                return !e.ground && !L.acPowered() && L.powered();
            case M_ENG1_START:
                return startFault(e1, e);
            case M_ENG2_START:
                return startFault(e2, e);
            case M_ENG1_AIR:
                return e1.state == LinerSystems.E_WAIT && e1.ticks > 20;
            case M_ENG2_AIR:
                return e2.state == LinerSystems.E_WAIT && e2.ticks > 20;
            case M_GEN1:
                return e1.running() && !L.on(LinerControls.GEN1);
            case M_GEN2:
                return e2.running() && !L.on(LinerControls.GEN2);
            case M_APU:
                return L.apuFault;
            case M_BLEED:
                return e1.running() && L.on(LinerControls.BLEED1) && L.ductL < LinerSystems.LOW_PSI && !L.apuBleedOpen
                        || e2.running() && L.on(LinerControls.BLEED2) && L.ductR < LinerSystems.LOW_PSI && !L.apuBleedOpen;
            case M_BUS1:
                return L.acPowered() && L.ac1Src == LinerSystems.S_NONE;
            case M_BUS2:
                return L.acPowered() && L.ac2Src == LinerSystems.S_NONE;
            case M_HYD_G_LO:
            case M_HYD_B_LO:
            case M_HYD_Y_LO:
                return lowHyd(L, e, id - M_HYD_G_LO);
            case M_HYD_G_QTY:
            case M_HYD_B_QTY:
            case M_HYD_Y_QTY:
                return L.hyd.low(id - M_HYD_G_QTY);
            case M_HYD_G_HOT:
            case M_HYD_B_HOT:
            case M_HYD_Y_HOT:
                return L.hyd.hot[id - M_HYD_G_HOT];
            case M_BRK_HOT:
                return L.gear.hot();
            case M_FLAP_JAM:
                return L.fctl.flapJam;
            case M_SLAT_JAM:
                return L.fctl.slatJam;
            case M_GEAR_DISAGREE:
                return L.gear.disagree > LinerGear.DISAGREE_TICKS;
            case M_TYRE:
                return L.gear.flat != 0;
            case M_LAW_ALTN:
                return !e.ground && L.fctl.law == LinerFctl.ALTN;
            case M_LAW_DIRECT:
                return !e.ground && L.fctl.law == LinerFctl.DIRECT;
            case M_FCTL_COMP:
                return compLost(L);
            case M_FUEL_IMBAL:
                return live(L, e) && Math.abs(L.fuel.imbalance()) > LinerFuel.IMBALANCE;
            case M_FUEL_LO_L:
                return live(L, e) && L.fuel.qty[LinerFuel.L] < LinerFuel.LOW_LEVEL && L.fuel.qty[LinerFuel.L] >= 1;
            case M_FUEL_LO_R:
                return live(L, e) && L.fuel.qty[LinerFuel.R] < LinerFuel.LOW_LEVEL && L.fuel.qty[LinerFuel.R] >= 1;
            case M_FUEL_OUT_L:
                return live(L, e) && L.fuel.qty[LinerFuel.L] < 1;
            case M_FUEL_OUT_R:
                return live(L, e) && L.fuel.qty[LinerFuel.R] < 1;
            case M_FUEL_AUTO:
                return L.fuel.modeFault;
            case M_DOOR:
                return L.door && live(L, e);
            default:
                return false;
        }
    }

    /** A hydraulic system's low pressure while it should have it (wave 2's rule). */
    static boolean lowHyd(LinerSystems L, LinerSystems.Env e, int s) {
        return live(L, e) && L.hyd.psi[s] < LinerHyd.LO_PSI;
    }

    static boolean startFault(LinerSystems.Eng g, LinerSystems.Env e) {
        return g.fault == LinerSystems.F_HOT || g.fault == LinerSystems.F_HUNG || g.fault == LinerSystems.F_NO_AIR
                || g.fault == LinerSystems.F_FLAMEOUT && e.ground;
    }

    /** A flight-control computer out or dead with its bus up (wave 2's rule). */
    static boolean compLost(LinerSystems L) {
        if (!L.powered()) {
            return false;
        }
        LinerControls.Ctl[] pb = LinerSystems.computers();
        for (int i = 0; i < pb.length; i++) {
            if ((L.fctl.comp & 1 << i) == 0 && (L.acPowered() || i == 0 || i == 2 || i == 5)) {
                return true;
            }
        }
        return false;
    }

    /** Action line i done now. */
    static boolean actionDone(int i, LinerSystems L, LinerSystems.Env e) {
        if (i >= A_PUMP_OFF && i < A_PUMP_OFF + LinerFuel.PUMPS) {
            return !L.on(pump(i - A_PUMP_OFF));
        }
        switch (i) {
            case A_MODE_START:
                return L.at(LinerControls.ENG_MODE) == 2;
            case A_THR_IDLE:
                return e.throttle <= LinerFctl.IDLE_THR;
            case A_MASTER1_OFF:
                return !L.on(LinerControls.ENG_MASTER1);
            case A_MASTER2_OFF:
                return !L.on(LinerControls.ENG_MASTER2);
            case A_PTU_OFF:
                return !L.on(LinerControls.HYD_PTU);
            case A_BRK_FAN_ON:
                return L.on(LinerControls.BRK_FAN);
            case A_GEAR_DOWN:
                return L.at(LinerControls.GEAR) == 1;
            case A_FLAPS_TO:
                return flapsTo(L);
            case A_SPDBRK_RET:
                return L.at(LinerControls.SPD_BRK) <= 1;
            case A_PARK_OFF:
                return !L.on(LinerControls.PARK_BRK);
            case A_RAT_MAN:
                return L.hyd.rat;
            case A_APU_BLEED_ON:
                return L.on(LinerControls.APU_BLEED);
            case A_GEN1_ON:
                return L.on(LinerControls.GEN1);
            case A_GEN2_ON:
                return L.on(LinerControls.GEN2);
            case A_APU_MASTER_OFF:
                return !L.on(LinerControls.APU_MASTER);
            case A_XBLEED_OPEN:
                return L.at(LinerControls.XBLEED) == 2;
            case A_BUS_TIE_ON:
                return L.on(LinerControls.BUS_TIE);
            case A_EDP1_OFF:
                return !L.on(LinerControls.HYD_ENG1);
            case A_BLUE_OFF:
                return !L.on(LinerControls.HYD_BLUE);
            case A_EDP2_OFF:
                return !L.on(LinerControls.HYD_ENG2);
            case A_XFEED_ON:
                return L.on(LinerControls.XFEED);
            case A_LIGHT_PUMPS_OFF: {
                boolean leftLight = L.fuel.imbalance() > 0;
                return leftLight ? !L.on(LinerControls.PUMP_L1) && !L.on(LinerControls.PUMP_L2) : !L.on(LinerControls.PUMP_R1) && !L.on(LinerControls.PUMP_R2);
            }
            case A_CTR_MAN:
                return L.on(LinerControls.FUEL_MODE);
            case A_DOOR_SHUT:
                return !L.door;
            default:
                return false;
        }
    }

    /** The fuel pumps' push buttons (LinerFuel.P_* order). */
    public static LinerControls.Ctl pump(int p) {
        LinerControls.Ctl[] c = {LinerControls.PUMP_L1, LinerControls.PUMP_L2, LinerControls.PUMP_C1, LinerControls.PUMP_C2, LinerControls.PUMP_R1,
                LinerControls.PUMP_R2};
        return c[p];
    }

    // ---------------------------------------------------------------- what the E/WD shows

    /** The messages on the E/WD (shown, not cleared): red first, then amber, each in table order. */
    public int[] list() {
        long s = shown & ~cleared;
        int n = Long.bitCount(s);
        int[] r = new int[n];
        int k = 0;
        for (int lv = WARN; lv >= CAUT; lv--) {
            for (int id = 0; id < N; id++) {
                if ((s & 1L << id) != 0 && LEVEL[id] == lv) {
                    r[k++] = id;
                }
            }
        }
        return r;
    }

    /** The top message (-1: none). */
    public int top() {
        int[] l = list();
        return l.length > 0 ? l[0] : -1;
    }

    /** Message id's action lines still to do (lang keys' indices). */
    public int[] openActions(int id) {
        int[] a = ACT[id];
        int n = 0;
        for (int x : a) {
            n += (done & 1L << x) == 0 ? 1 : 0;
        }
        int[] r = new int[n];
        int k = 0;
        for (int x : a) {
            if ((done & 1L << x) == 0) {
                r[k++] = x;
            }
        }
        return r;
    }

    /** STATUS: the inoperative systems (I_* of every active message). */
    public int inop() {
        int m = 0;
        for (int id = 0; id < N; id++) {
            if ((active & 1L << id) != 0) {
                m |= INOP[id];
            }
        }
        return m;
    }

    /** STATUS: the limitations (LIM_*). */
    public int limits() {
        int m = 0;
        for (int id = 0; id < N; id++) {
            if ((active & 1L << id) != 0) {
                m |= LIMIT[id];
            }
        }
        return m;
    }

    /** Anything for the STATUS page. */
    public boolean hasStatus() {
        return inop() != 0 || limits() != 0 || cancelled != 0;
    }

    // ---------------------------------------------------------------- the panel

    /** CLR: the top message off (its next and its page come up); with none left STATUS; on STATUS back to automatic. */
    public void clr(LinerSystems L) {
        int t = top();
        if (t >= 0) {
            cleared |= 1L << t;
            L.masterCaut = false;
            if (top() < 0 && hasStatus()) {
                stsAuto = true;
            }
            return;
        }
        if (stsAuto || L.sdPage == P_STS + 1) {
            stsSeen = true;
        }
        stsAuto = false;
        L.sdPage = 0;
    }

    /** RCL: the cleared and cancelled messages back; nothing at all - «НОРМА» for a while. */
    public void rcl() {
        if (active == 0 && cleared == 0 && cancelled == 0) {
            rclNormal = RCL_NORMAL;
        }
        cleared = 0;
        cancelled = 0;
    }

    /** EMER CANC: a red message - its chime silenced; else the top caution cancelled for the flight. */
    public void emerCanc(LinerSystems L) {
        if ((shown & levelMask(WARN)) != 0) {
            silenced = true;
            return;
        }
        int t = top();
        if (t >= 0 && LEVEL[t] == CAUT) {
            cancelled |= 1L << t;
            shown &= ~(1L << t);
            prevShown = shown;
            L.masterCaut = (shown & levelMask(CAUT)) != 0 && L.masterCaut;
        }
    }

    /** T.O. CONFIG pressed (on the ground). */
    public void toConfig(boolean ground) {
        if (ground) {
            toTest = TO_TEST_TICKS;
        }
    }

    /** MASTER WARN pressed: the lamp out, the chime silenced. */
    public void masterWarn(LinerSystems L) {
        L.masterWarn = false;
        silenced = true;
    }

    /** The ВСЕ key: the next page of ALL_CYCLE (after the last - automatic). */
    public static int allNext(int sdPage) {
        int cur = sdPage - 1;
        for (int i = 0; i < ALL_CYCLE.length; i++) {
            if (ALL_CYCLE[i] == cur) {
                return i + 1 < ALL_CYCLE.length ? ALL_CYCLE[i + 1] + 1 : 0;
            }
        }
        return ALL_CYCLE[0] + 1;
    }

    /** The memo «ТО КОНФИГ НОРМ»: the test running and the configuration good. */
    public boolean toNormal() {
        return toTest > 0 && toCfgOk;
    }

    // ---------------------------------------------------------------- T.O. / LDG memos

    public static final int TM_AUTOBRK = 0, TM_BELTS = 1, TM_NOSMOKE = 2, TM_SPLRS = 3, TM_FLAPS = 4, TM_CONFIG = 5;
    public static final String[] TO_KEYS = {"autoBrk", "belts", "noSmoke", "splrs", "flaps", "config"};
    public static final int LM_GEAR = 0, LM_BELTS = 1, LM_NOSMOKE = 2, LM_SPLRS = 3, LM_FLAPS = 4;
    public static final String[] LDG_KEYS = {"gear", "belts", "noSmoke", "splrs", "flaps"};

    /** T.O. MEMO on the E/WD: on the ground with both engines running (or the T.O. CONFIG test), below take-off power. */
    public boolean toMemoShown(LinerSystems L) {
        return (phase == PH_TAXI && L.eng[0].running() && L.eng[1].running()) || (phase == PH_TAXI || phase == PH_POWER) && toTest > 0;
    }

    /** LDG MEMO: approach and final. */
    public boolean ldgMemoShown() {
        return phase == PH_APPR || phase == PH_FINAL;
    }

    /** The T.O. MEMO items done (bits by TM_*): from the synced state, on both sides. */
    public int toMemo(LinerSystems L) {
        int m = 0;
        m |= L.gear.abMode == 3 ? 1 << TM_AUTOBRK : 0;
        m |= L.at(LinerControls.SEAT_BELTS) != 2 ? 1 << TM_BELTS : 0;
        m |= L.at(LinerControls.NO_SMOKING) != 2 ? 1 << TM_NOSMOKE : 0;
        m |= L.at(LinerControls.SPD_BRK) == 0 ? 1 << TM_SPLRS : 0;
        m |= flapsTo(L) ? 1 << TM_FLAPS : 0;
        m |= toCfgOk ? 1 << TM_CONFIG : 0;
        return m;
    }

    /** The LDG MEMO items done (bits by LM_*). */
    public static int ldgMemo(LinerSystems L) {
        int m = 0;
        m |= L.gear.downLocked() ? 1 << LM_GEAR : 0;
        m |= L.at(LinerControls.SEAT_BELTS) != 2 ? 1 << LM_BELTS : 0;
        m |= L.at(LinerControls.NO_SMOKING) != 2 ? 1 << LM_NOSMOKE : 0;
        m |= L.at(LinerControls.SPD_BRK) == 0 ? 1 << LM_SPLRS : 0;
        m |= L.at(LinerControls.FLAPS) >= 3 ? 1 << LM_FLAPS : 0;
        return m;
    }

    // ---------------------------------------------------------------- the SD's automatic page

    /**
     * Sheet 13a: STATUS after the last CLR; the shown failure's page; ДВИГ for a start (an engine starting or ЗАПУСК); ВСУ
     * while the APU starts; ОТБОР with its bleed on the ground with the engines off; УПР on the flight-control check; on the
     * ground with the engines off ДВЕРИ, taxiing КОЛЁСА; the take-off ДВИГ; the gear lever down low КОЛЁСА; else the cruise page.
     */
    public int autoPage(LinerSystems L, LinerSystems.Env e) {
        if (stsAuto) {
            return P_STS;
        }
        int t = top();
        if (t >= 0 && PAGE[t] >= 0) {
            return PAGE[t];
        }
        for (LinerSystems.Eng g : L.eng) {
            if (g.state == LinerSystems.E_WAIT || g.state == LinerSystems.E_CRANK || g.state == LinerSystems.E_LIGHT || g.state == LinerSystems.E_ABORT) {
                return P_ENG;
            }
        }
        if (L.at(LinerControls.ENG_MODE) == 2) {
            return P_ENG;
        }
        if (L.apu == LinerSystems.A_START || L.apu == LinerSystems.A_FLAP && L.apuStartLit) {
            return P_APU;
        }
        if (L.fctlCheck > 0) {
            return P_FCTL;
        }
        boolean run = L.anyRunning();
        if (e.ground) {
            if (!run) {
                return L.apuBleedOpen ? P_BLEED : P_DOOR;
            }
            return phase == PH_TO ? P_ENG : P_WHEEL;
        }
        if (L.at(LinerControls.GEAR) == 1 && e.height < LinerSystems.WHEEL_ALT) {
            return P_WHEEL;
        }
        return phase == PH_CLIMB_OUT ? P_ENG : P_CRZ;
    }

    // ---------------------------------------------------------------- sync

    /** The ECAM's words for the sync string: flags, shown / cleared / cancelled / done (two ints each). */
    public int[] pack() {
        int f = phase | (toFast ? 16 : 0) | (toCfgOk ? 32 : 0) | (stsAuto ? 64 : 0) | (stsSeen ? 128 : 0) | (silenced ? 256 : 0) | (toTest > 0 ? 512 : 0)
                | (rclNormal > 0 ? 1024 : 0);
        return new int[] {f, lo(shown), hi(shown), lo(cleared), hi(cleared), lo(cancelled), hi(cancelled), lo(done), hi(done), lo(active), hi(active)};
    }

    /** The client's mirror from those words (at `o` in w). */
    public void unpack(int[] w, int o) {
        int f = w[o];
        phase = f & 15;
        toFast = (f & 16) != 0;
        toCfgOk = (f & 32) != 0;
        stsAuto = (f & 64) != 0;
        stsSeen = (f & 128) != 0;
        silenced = (f & 256) != 0;
        toTest = (f & 512) != 0 ? 1 : 0;
        rclNormal = (f & 1024) != 0 ? 1 : 0;
        shown = join(w[o + 1], w[o + 2]);
        cleared = join(w[o + 3], w[o + 4]);
        cancelled = join(w[o + 5], w[o + 6]);
        done = join(w[o + 7], w[o + 8]);
        active = join(w[o + 9], w[o + 10]);
    }

    /** Words in pack(). */
    public static final int WORDS = 11;

    static int lo(long v) {
        return (int) v;
    }

    static int hi(long v) {
        return (int) (v >>> 32);
    }

    static long join(int lo, int hi) {
        return (lo & 0xFFFFFFFFL) | (long) hi << 32;
    }
}
