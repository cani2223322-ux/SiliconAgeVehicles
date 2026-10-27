package com.sva.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.sc.embassy.EmbassyAirRulesSC;
import com.sva.airdefense.EmbassyAdMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airport.Cpdlc;
import com.sva.airport.EmbassyAirportMath;
import com.sva.airport.EmbassyAtcMath;
import com.sva.airport.EmbassyLightsMath;
import com.sva.airport.IlsPairing;
import com.sva.airport.RunwayLayout;
import com.sva.airport.TowerMath;
import com.sva.block.EmbassyParkingDataSVA;
import com.sva.compat.EmbassyCompatSVA;
import com.sva.init.ModContentSVA;
import com.sva.vehicle.EmbassyServiceMath;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.Movement;
import com.sva.vehicle.NavDb;
import com.sva.vehicle.RunwaySurfaceSVA;
import com.sva.vehicle.SpaceMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * PACKAGE D (docs/plan-embassy-airfield-addon.md §6.1): the self-test of «Лётное поле посольства» in the add-on - pure rules only
 * (SelfTestSVA.run calls it last - the coordinator's hook): the runway surface (vehicle.RunwaySurfaceSVA - the add-on's block any meta,
 * the core's metas 0 / 3 / 5 / 6 / 7 yes, 1 / 2 / 4 / 8 no, other blocks no), the compat's switch and its mirrors (EmbassyCompatSVA:
 * on() with this core, mirrorConstants() == debugApiConstants(), the services' and payments' keys = the core's), the embassy air defence's
 * matrix (airdefense.EmbassyAdMath: airborne x in the zone x hold of any one aboard x the warning's stage x unmanned / owner; why();
 * earliest(); turnEnd(); extraWarning(); passLine(); pickPost()), the airport (airport.EmbassyAirportMath: keys, uniqueIcao,
 * playerIcaoAllowed, locIdent / locFreq; TowerMath's both-way assign), ATC (airport.EmbassyAtcMath: codes, ATIS radius, refusals, taxi
 * hold; Cpdlc U_REFUSED / U_DOME / U_EMB_AD want no response, their args), the services (vehicle.EmbassyServiceMath: stand kinds, 2
 * stands a player, parking days, emergency 25%, towing timers, offers, hints; the mirrors == the core's EmbassyAirRulesSC), the lights
 * (airport.EmbassyLightsMath: no edge / end lights, the centre line, the TDZ, the ALS only with one; PAPI), the enums appended
 * (SpaceMath.EMBASSY, NavDb.SRC_EMBASSY, Cpdlc's types), the stands' NBT there and back (block.EmbassyParkingDataSVA), every new lang key
 * in ru and en.
 * <p>
 * The only core class it names is com.sc.embassy.EmbassyAirRulesSC (the services' numbers the add-on mirrors) - read only while
 * EmbassyCompatSVA.on() (an older core without the API: those comparisons are skipped, nothing of it is loaded). The core's block is
 * looked up by its registry name. The world-side checks (a real field, the API's answers, the air defence firing, the levels' numbers,
 * the AD lines' keys) are WorldTestEmbassySVA's.
 */
final class SelfTestEmbassySVA {

    /** The core's airfield block (registry name; its runway metas: the paving, the white marks, the edge / threshold / end lights). */
    static final String CORE_BLOCK = "SiliconAge:embassyAirfieldSC";
    static final int[] RUNWAY_METAS = {0, 3, 5, 6, 7}, OTHER_METAS = {1, 2, 4, 8};

    private SelfTestEmbassySVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        runwaySurface();
        compat();
        adMatrix();
        adHelpers();
        airport();
        towerAssign();
        atc();
        cpdlc();
        services();
        lights();
        enums();
        parking();
        lang();
        SelfTestEmbassyIntSVA.run();                               // the integrator's: the packages' own pure rules
    }

    // ---------------------------------------------------------------- 1. the runway surface (§6.1.1)

    private static void runwaySurface() {
        boolean own = ModContentSVA.runway != null;
        for (int m = 0; own && m < 16; m++) {
            own &= RunwaySurfaceSVA.is(ModContentSVA.runway, m);
        }
        Object o = Block.blockRegistry.getObject(CORE_BLOCK);
        Block core = o instanceof Block && o != Blocks.air ? (Block) o : null;
        boolean coreOk;
        String coreWhy;
        if (EmbassyCompatSVA.on() && core != null) {
            coreOk = true;
            for (int m : RUNWAY_METAS) {
                coreOk &= RunwaySurfaceSVA.is(core, m) && EmbassyCompatSVA.isRunwaySurface(core, m) && EmbassyCompatSVA.isEmbassyBlock(core);
            }
            for (int m : OTHER_METAS) {
                coreOk &= !RunwaySurfaceSVA.is(core, m) && !EmbassyCompatSVA.isRunwaySurface(core, m);
            }
            coreWhy = "the core's " + CORE_BLOCK + " metas " + Arrays.toString(RUNWAY_METAS) + " a runway, " + Arrays.toString(OTHER_METAS) + " not (the apron,"
                    + " the shoulder, the yellow marks, the blue taxi lights)";
        } else {
            // an older core (no API) or no such block: nothing of the core is a runway
            coreOk = core == null || !RunwaySurfaceSVA.is(core, 0);
            coreWhy = "no embassy API (" + EmbassyCompatSVA.why() + ") - the core's block (" + (core != null) + ") is no runway";
        }
        boolean others = !RunwaySurfaceSVA.is(Blocks.stone, 0) && !RunwaySurfaceSVA.is(Blocks.air, 0) && !RunwaySurfaceSVA.is(null, 0)
                && !RunwaySurfaceSVA.is(Blocks.stone_slab, 0) && !EmbassyCompatSVA.isRunwaySurface(Blocks.stone, 0)
                && !EmbassyCompatSVA.isRunwaySurface(null, 0) && !EmbassyCompatSVA.isEmbassyBlock(Blocks.stone)
                && !EmbassyCompatSVA.isEmbassyBlock(null) && !EmbassyCompatSVA.isEmbassyBlock(ModContentSVA.runway);
        boolean noWorld = !RunwaySurfaceSVA.is(null, 0, 64, 0) && !RunwaySurfaceSVA.embassy(null, 0, 64, 0);
        check(own && coreOk && others && noWorld, "embassy runway surface (§6.1.1): the add-on's «Взлётно-посадочная полоса» any meta " + own + "; " + coreWhy
                + " " + coreOk + "; stone / air / slab / null no " + others + "; no world - no runway, no embassy runway " + noWorld);
    }

    // ---------------------------------------------------------------- 2. the compat's switch and mirrors (§6.1.2)

    private static void compat() {
        boolean on = EmbassyCompatSVA.on();
        int[] api = EmbassyCompatSVA.debugApiConstants(), mine = EmbassyCompatSVA.mirrorConstants();
        boolean owner = "[Поселение-7]".equals(EmbassyCompatSVA.ownerOf(7)) && EmbassyCompatSVA.ownerOf(0).startsWith(EmbassyCompatSVA.OWNER_PREFIX)
                && EmbassyCompatSVA.ownerOf(12).endsWith(EmbassyCompatSVA.OWNER_SUFFIX) && EmbassyCompatSVA.API_VERSION == 1
                && "com.sc.embassy.api.EmbassyAirApiSC".equals(EmbassyCompatSVA.API_CLASS);
        // the add-on's own index words agree with themselves (the services' keys / indices, the kinds, the levels, the light metas)
        boolean own = EmbassyCompatSVA.SERVICE_KEYS.length == 9 && "park".equals(EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_PARK])
                && "emergency".equals(EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_EMERGENCY])
                && "charge".equals(EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_CHARGE])
                && "hydrogen".equals(EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_HYDROGEN])
                && "armourGas".equals(EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_ARMOUR_GAS])
                && "repair".equals(EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_REPAIR])
                && "electrolyte".equals(EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_ELECTROLYTE])
                && "tow".equals(EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_TOW]) && "chart".equals(EmbassyCompatSVA.SERVICE_KEYS[EmbassyCompatSVA.S_CHART])
                && EmbassyCompatSVA.PAY_KEYS.length == EmbassyCompatSVA.PAY_UNKNOWN + 1 && EmbassyCompatSVA.AD_KEYS.length == EmbassyCompatSVA.AD_LEFT + 1
                && EmbassyCompatSVA.LEVEL_KEYS.length == EmbassyCompatSVA.HONOURED + 1 && EmbassyCompatSVA.KIND_KEYS.length == EmbassyCompatSVA.FULL + 1
                && "acquaintance".equals(EmbassyCompatSVA.LEVEL_KEYS[EmbassyCompatSVA.ACQUAINTANCE])
                && "stranger".equals(EmbassyCompatSVA.LEVEL_KEYS[EmbassyCompatSVA.STRANGER]) && "enemy".equals(EmbassyCompatSVA.LEVEL_KEYS[EmbassyCompatSVA.ENEMY])
                && EmbassyCompatSVA.LIGHT_EDGE == 5 && EmbassyCompatSVA.LIGHT_THRESHOLD == 6 && EmbassyCompatSVA.LIGHT_END == 7
                && EmbassyCompatSVA.LIGHT_TAXI == 8 && EmbassyCompatSVA.TAB_ROWS == 8 && "service".equals(EmbassyCompatSVA.TAB_ACTION_SERVICE);
        if (!on) {
            check(api == null && EmbassyCompatSVA.why().length() > 0 && owner && own && EmbassyCompatSVA.fields(null).isEmpty()
                    && EmbassyCompatSVA.adRadius() == 0 && EmbassyCompatSVA.revision(null) == -1,
                    "embassy compat (§6.1.2): this core has no embassy API (" + EmbassyCompatSVA.why() + ") - everything answers «none»; the owner"
                            + " «[Поселение-7]» " + owner + ", the add-on's index words " + own);
            return;
        }
        boolean mirrors = api != null && Arrays.equals(api, mine);
        String keys = coreKeys();
        check(EmbassyCompatSVA.why().length() == 0 && mirrors && keys.length() == 0 && owner && own,
                "embassy compat (§6.1.2): on() with this core (why \"" + EmbassyCompatSVA.why() + "\"); the mirrors " + Arrays.toString(mine)
                        + " = the API's " + Arrays.toString(api) + " " + mirrors + "; SERVICE_KEYS / PAY_KEYS / S_* / PAY_* = the core's EmbassyAirRulesSC"
                        + (keys.length() == 0 ? "" : " - BAD:" + keys) + "; ownerOf(7) «[Поселение-7]» " + owner + "; the add-on's index words " + own);
        check(EmbassyCompatSVA.adRadius() == EmbassyAirRulesSC.AD_RADIUS && EmbassyCompatSVA.warnSeconds() == EmbassyAirRulesSC.WARN_SECONDS
                && EmbassyCompatSVA.adRadius() > 0 && EmbassyCompatSVA.fields(null).isEmpty() && EmbassyCompatSVA.field(null, 1) == null
                && EmbassyCompatSVA.domeAt(null, 0, 0) == -1 && EmbassyCompatSVA.adZoneAt(null, 0, 0) == -1 && !EmbassyCompatSVA.neutralAt(null, 0, 64, 0)
                && EmbassyCompatSVA.levelOf(null, 1) == -1 && !EmbassyCompatSVA.admitted(null, 1) && EmbassyCompatSVA.requestWarn(null, 1) == 0L
                && EmbassyCompatSVA.requestPass(null, 1) == EmbassyCompatSVA.PASS_NO_FIELD && EmbassyCompatSVA.pay(null, 1, "park", 1, false) == EmbassyCompatSVA.PAY_NO_FIELD
                && EmbassyCompatSVA.quote(null, 1, "park", 1, false) == -EmbassyCompatSVA.PAY_NO_FIELD && !EmbassyCompatSVA.holdFire(null, 1)
                && !EmbassyCompatSVA.firstLanding(null, 1) && EmbassyCompatSVA.revision(null) == -1 && EmbassyCompatSVA.on(),
                "embassy compat: the API's zone " + EmbassyCompatSVA.adRadius() + " / the warning's " + EmbassyCompatSVA.warnSeconds() + " s = the core's rules; no world / no player -"
                        + " «none» everywhere (empty, null, -1, false, 0, PASS_NO_FIELD, PAY_NO_FIELD), and the API stays on (no LinkageError)");
    }

    /** The core's EmbassyAirRulesSC against the add-on's mirrors (only while on()); "" all equal. */
    private static String coreKeys() {
        StringBuilder bad = new StringBuilder();
        if (!Arrays.equals(EmbassyCompatSVA.SERVICE_KEYS, EmbassyAirRulesSC.SERVICE_KEYS)) {
            bad.append(" SERVICE_KEYS ").append(Arrays.toString(EmbassyAirRulesSC.SERVICE_KEYS));
        }
        if (!Arrays.equals(EmbassyCompatSVA.PAY_KEYS, EmbassyAirRulesSC.PAY_KEYS)) {
            bad.append(" PAY_KEYS ").append(Arrays.toString(EmbassyAirRulesSC.PAY_KEYS));
        }
        int[] mine = {EmbassyCompatSVA.S_PARK, EmbassyCompatSVA.S_EMERGENCY, EmbassyCompatSVA.S_CHARGE, EmbassyCompatSVA.S_HYDROGEN, EmbassyCompatSVA.S_ARMOUR_GAS,
            EmbassyCompatSVA.S_REPAIR, EmbassyCompatSVA.S_ELECTROLYTE, EmbassyCompatSVA.S_TOW, EmbassyCompatSVA.S_CHART, EmbassyCompatSVA.PAY_OK,
            EmbassyCompatSVA.PAY_ENEMY, EmbassyCompatSVA.PAY_LEVEL, EmbassyCompatSVA.PAY_LIMIT, EmbassyCompatSVA.PAY_CHIPS, EmbassyCompatSVA.PAY_NO_FIELD,
            EmbassyCompatSVA.PAY_UNKNOWN};
        int[] core = {EmbassyAirRulesSC.S_PARK, EmbassyAirRulesSC.S_EMERGENCY, EmbassyAirRulesSC.S_CHARGE, EmbassyAirRulesSC.S_HYDROGEN,
            EmbassyAirRulesSC.S_ARMOUR_GAS, EmbassyAirRulesSC.S_REPAIR, EmbassyAirRulesSC.S_ELECTROLYTE, EmbassyAirRulesSC.S_TOW, EmbassyAirRulesSC.S_CHART,
            EmbassyAirRulesSC.PAY_OK, EmbassyAirRulesSC.PAY_ENEMY, EmbassyAirRulesSC.PAY_LEVEL, EmbassyAirRulesSC.PAY_LIMIT, EmbassyAirRulesSC.PAY_CHIPS,
            EmbassyAirRulesSC.PAY_NO_FIELD, EmbassyAirRulesSC.PAY_UNKNOWN};
        if (!Arrays.equals(mine, core)) {
            bad.append(" S_* / PAY_* ").append(Arrays.toString(core));
        }
        for (int i = 0; i < EmbassyCompatSVA.SERVICE_KEYS.length; i++) {
            if (EmbassyAirRulesSC.service(EmbassyCompatSVA.SERVICE_KEYS[i]) != i) {
                bad.append(" service(").append(EmbassyCompatSVA.SERVICE_KEYS[i]).append(')');
            }
        }
        return bad.toString();
    }

    // ---------------------------------------------------------------- 3. the embassy air defence's matrix (§6.1.3)

    /** The warning's stages of a look. */
    private static final int ST_NEW = 0, ST_TURN = 1, ST_TIME = 2, ST_REENTRY = 3, ST_REENTRY0 = 4, ST_CLOSE = 5, ST_CLOSE_EXACT = 6, ST_DESCEND = 7,
            ST_LOW_LEVEL = 8, ST_LOW_NOISE = 9, ST_STAGES = 10;
    private static final String[] ST_KEYS = {"new", "turn", "time", "reentry", "reentry0", "close", "close=32", "descend", "low level", "low noise"};
    private static final long T0 = 100000L;

    /** A look of `stage` (the numbers of the coordinator's rules: 10 s, 32 blocks closer, below 40 and lower than at the warning). */
    private static EmbassyAdMath.Look look(boolean airborne, boolean inZone, boolean manned, boolean anyHold, boolean ownerHold, int stage) {
        EmbassyAdMath.Look k = new EmbassyAdMath.Look();
        k.airborne = airborne;
        k.inZone = inZone;
        k.manned = manned;
        k.anyHold = anyHold;
        k.ownerHold = ownerHold;
        k.closeBlocks = 32;
        k.minHeight = 40;
        k.dist0 = k.dist = 200;
        k.h0 = k.h = 80;
        k.now = T0;
        if (stage == ST_NEW) {
            return k;
        }
        k.warned = true;
        k.warnedAt = T0 - 60;
        k.end = k.warnedAt + 10 * EmbassyAdMath.TICKS;
        switch (stage) {
            case ST_TIME:
                k.now = k.end;
                break;
            case ST_REENTRY:
                k.end = k.warnedAt - 400;                       // warned earlier today: the day's end long past
                break;
            case ST_REENTRY0:
                k.end = 0;                                      // no seconds at all (embassyWarnPerDay 0)
                break;
            case ST_CLOSE:
                k.dist = k.dist0 - 33;
                break;
            case ST_CLOSE_EXACT:
                k.dist = k.dist0 - 32;
                break;
            case ST_DESCEND:
                k.h0 = 60;
                k.h = 39;
                break;
            case ST_LOW_LEVEL:
                k.h0 = k.h = 39;                                // low but level: still turning away
                break;
            case ST_LOW_NOISE:
                k.h0 = 40.5;
                k.h = 39;                                       // within DESCEND_TOL
                break;
            default:
                break;
        }
        return k;
    }

    /** The rules of §3.10 written out again (not by calling EmbassyAdMath): {verdict, why}. */
    private static int[] expected(boolean airborne, boolean inZone, boolean manned, boolean anyHold, boolean ownerHold, int stage) {
        int why = stage == ST_NEW ? EmbassyAdMath.WHY_NONE : whyOf(stage);
        if (!airborne || !inZone) {
            return new int[] {EmbassyAdMath.A_NONE, why};
        }
        if (manned ? anyHold : ownerHold) {
            return new int[] {EmbassyAdMath.A_HOLD, why};
        }
        if (stage == ST_NEW) {
            return new int[] {EmbassyAdMath.A_WARN, EmbassyAdMath.WHY_NONE};
        }
        return new int[] {why == EmbassyAdMath.WHY_NONE ? EmbassyAdMath.A_TURN : EmbassyAdMath.A_FIRE, why};
    }

    private static int whyOf(int stage) {
        switch (stage) {
            case ST_TIME:
                return EmbassyAdMath.WHY_TIME;
            case ST_REENTRY:
            case ST_REENTRY0:
                return EmbassyAdMath.WHY_REENTRY;
            case ST_CLOSE:
                return EmbassyAdMath.WHY_CLOSE;
            case ST_DESCEND:
                return EmbassyAdMath.WHY_DESCEND;
            default:
                return EmbassyAdMath.WHY_NONE;
        }
    }

    private static void adMatrix() {
        int cases = 0, bad = 0;
        StringBuilder first = new StringBuilder();
        int[] seen = new int[EmbassyAdMath.A_KEYS.length];
        for (int m = 0; m < 32; m++) {
            boolean airborne = (m & 1) != 0, inZone = (m & 2) != 0, manned = (m & 4) != 0, anyHold = (m & 8) != 0, ownerHold = (m & 16) != 0;
            for (int st = 0; st < ST_STAGES; st++) {
                EmbassyAdMath.Look k = look(airborne, inZone, manned, anyHold, ownerHold, st);
                int[] e = expected(airborne, inZone, manned, anyHold, ownerHold, st);
                int v = EmbassyAdMath.verdict(k), w = EmbassyAdMath.why(k);
                cases++;
                if (v >= 0 && v < seen.length) {
                    seen[v]++;
                }
                if (v != e[0] || w != e[1]) {
                    bad++;
                    if (first.length() < 400) {
                        first.append(" [air ").append(airborne).append(" zone ").append(inZone).append(" manned ").append(manned).append(" hold ")
                                .append(anyHold).append(" owner ").append(ownerHold).append(' ').append(ST_KEYS[st]).append(": ")
                                .append(key(EmbassyAdMath.A_KEYS, v)).append('/').append(key(EmbassyAdMath.WHY_KEYS, w)).append(", want ")
                                .append(key(EmbassyAdMath.A_KEYS, e[0])).append('/').append(key(EmbassyAdMath.WHY_KEYS, e[1])).append(']');
                    }
                }
            }
        }
        boolean all = true;
        for (int s : seen) {
            all &= s > 0;
        }
        check(bad == 0 && all && cases == 32 * ST_STAGES, "embassy AD matrix (§6.1.3): " + cases + " looks (airborne x in the zone x manned x any rider"
                + " holds x the owner holds x " + ST_STAGES + " stages) - not airborne or out of the zone NONE, a holding rider (the owner of an empty one)"
                + " HOLD, the first look WARN, then TURN until the 10 s are over (TIME), closer by > 32 (CLOSE; exactly 32 - TURN), below 40 and lower"
                + " than at the warning (DESCEND; low and level / within 2 - TURN), no seconds left (REENTRY); verdicts seen " + Arrays.toString(seen)
                + (bad == 0 ? "" : " - " + bad + " wrong:" + first));
        // the edges of a single look
        EmbassyAdMath.Look k = look(true, true, true, false, false, ST_TURN);
        k.now = k.end - 1;
        boolean beforeEnd = EmbassyAdMath.verdict(k) == EmbassyAdMath.A_TURN;
        k.now = k.end;
        boolean atEnd = EmbassyAdMath.verdict(k) == EmbassyAdMath.A_FIRE && EmbassyAdMath.why(k) == EmbassyAdMath.WHY_TIME;
        EmbassyAdMath.Look e = look(true, true, true, false, false, ST_TURN);
        e.end = e.warnedAt;                                      // the core's answer at the very moment: no seconds
        boolean sameTick = EmbassyAdMath.why(e) == EmbassyAdMath.WHY_REENTRY;
        EmbassyAdMath.Look away = look(true, true, true, false, false, ST_TURN);
        away.dist = away.dist0 + 50;                             // turning away
        away.h = 200;
        boolean leaving = EmbassyAdMath.verdict(away) == EmbassyAdMath.A_TURN;
        EmbassyAdMath.Look high = look(true, true, true, false, false, ST_TURN);
        high.h0 = 120;
        high.h = 60;                                             // descending, but still over minHeight
        boolean overMin = EmbassyAdMath.verdict(high) == EmbassyAdMath.A_TURN;
        EmbassyAdMath.Look cfg = look(true, true, true, false, false, ST_TURN);
        cfg.closeBlocks = 64;
        cfg.dist = cfg.dist0 - 40;                               // airdefense.embassyCloseBlocks 64: 40 closer is still turning
        cfg.minHeight = 0;
        cfg.h0 = 30;
        cfg.h = 5;                                               // airdefense.embassyMinHeight 0: no height rule
        boolean config = EmbassyAdMath.verdict(cfg) == EmbassyAdMath.A_TURN;
        check(beforeEnd && atEnd && sameTick && leaving && overMin && config && EmbassyAdMath.verdict(null) == EmbassyAdMath.A_NONE
                && EmbassyAdMath.why(null) == EmbassyAdMath.WHY_NONE && EmbassyAdMath.why(look(true, true, true, false, false, ST_NEW)) == EmbassyAdMath.WHY_NONE,
                "embassy AD edges: a tick before the end TURN " + beforeEnd + ", at the end FIRE (time) " + atEnd + "; an end not after the warning - REENTRY "
                        + sameTick + "; flying away and climbing TURN " + leaving + "; descending over 40 TURN " + overMin + "; the config's numbers (64 closer,"
                        + " height 0) used " + config + "; null - NONE");
    }

    private static String key(String[] keys, int i) {
        return i >= 0 && i < keys.length ? keys[i] : "?" + i;
    }

    private static void adHelpers() {
        boolean earliest = EmbassyAdMath.earliest(new long[] {0, 300, 200, 0}) == 200 && EmbassyAdMath.earliest(new long[] {0, 0}) == 0
                && EmbassyAdMath.earliest(new long[0]) == 0 && EmbassyAdMath.earliest(null) == 0 && EmbassyAdMath.earliest(new long[] {500}) == 500;
        long now = 5000;
        boolean turnEnd = EmbassyAdMath.turnEnd(now + 200, now, 10) == now + 200 && EmbassyAdMath.turnEnd(now + 1, now, 10) == now + 200
                && EmbassyAdMath.turnEnd(now + 200, now, 30) == now + 600 && EmbassyAdMath.turnEnd(now - 100, now, 10) == now - 100
                && EmbassyAdMath.turnEnd(now, now, 10) == now && EmbassyAdMath.turnEnd(0, now, 10) == 0 && EmbassyAdMath.turnEnd(-5, now, 10) == 0
                && EmbassyAdMath.turnEnd(now + 1, now, 0) == now + EmbassyAdMath.TICKS;
        boolean extra = !EmbassyAdMath.extraWarning(false, 0, 0) && !EmbassyAdMath.extraWarning(true, 0, 0) && !EmbassyAdMath.extraWarning(true, 0, 1)
                && !EmbassyAdMath.extraWarning(false, 0, 1) && !EmbassyAdMath.extraWarning(false, 0, 3) && EmbassyAdMath.extraWarning(true, 0, 3)
                && EmbassyAdMath.extraWarning(true, 1, 3) && !EmbassyAdMath.extraWarning(true, 2, 3) && !EmbassyAdMath.extraWarning(true, 5, 3);
        boolean none = EmbassyAdMath.noWarnings(0) && EmbassyAdMath.noWarnings(-1) && !EmbassyAdMath.noWarnings(1) && !EmbassyAdMath.noWarnings(3);
        boolean owner = EmbassyAdMath.ownerHolds(false, false, false) && EmbassyAdMath.ownerHolds(false, true, false) && EmbassyAdMath.ownerHolds(true, true, true)
                && !EmbassyAdMath.ownerHolds(true, true, false) && !EmbassyAdMath.ownerHolds(true, false, true) && !EmbassyAdMath.ownerHolds(true, false, false);
        boolean pass = EmbassyAdMath.passLine(EmbassyCompatSVA.PASS_GRANTED) == EmbassyCompatSVA.AD_PASS
                && EmbassyAdMath.passLine(EmbassyCompatSVA.PASS_USED) == EmbassyCompatSVA.AD_PASS_USED
                && EmbassyAdMath.passLine(EmbassyCompatSVA.PASS_RUNNING) == -1 && EmbassyAdMath.passLine(EmbassyCompatSVA.PASS_NO_FIELD) == -1
                && EmbassyAdMath.passLine(EmbassyCompatSVA.PASS_ADMITTED) == -1 && EmbassyAdMath.passLine(-1) == -1;
        double[][] posts = {{0, 70, 0}, {50, 70, 0}, {100, 70, 0}, null};
        boolean[] los = {true, false, true, true};
        int near = EmbassyAdMath.pickPost(posts, los, 60, 70, 0, 110);            // post 1 is nearest but blind: post 2 (40 away)
        int none2 = EmbassyAdMath.pickPost(posts, los, 60, 70, 0, 30);            // none within 30 with a clear line
        int edge = EmbassyAdMath.pickPost(posts, los, 140, 70, 0, 40);            // exactly the range
        int far = EmbassyAdMath.pickPost(posts, los, 400, 70, 0, 110);
        int shortLos = EmbassyAdMath.pickPost(posts, new boolean[] {true}, 60, 70, 0, 110);
        boolean pick = near == 2 && none2 == -1 && edge == 2 && far == -1 && shortLos == 0 && EmbassyAdMath.pickPost(null, los, 0, 0, 0, 100) == -1
                && EmbassyAdMath.pickPost(posts, null, 0, 70, 0, 100) == -1;
        check(earliest && turnEnd && extra && none && owner && pass && pick, "embassy AD helpers: earliest of the riders' ends (zeros skipped) " + earliest
                + "; turnEnd - a future core end gives the config's seconds from now, a past one stays (no new seconds), 0 stays 0 " + turnEnd
                + "; extraWarning (perDay 0 / 1 / 3) " + extra + "; noWarnings " + none + "; ownerHolds (rule off / owner online and admitted) " + owner
                + "; passLine (granted - AD_PASS, used - AD_PASS_USED, else none) " + pass + "; pickPost - the nearest post with a clear line within the"
                + " range (" + near + ", " + none2 + ", " + edge + ", " + far + ", " + shortLos + ") " + pick);
    }

    // ---------------------------------------------------------------- 4. the airport (§6.1.4)

    private static void airport() {
        boolean key = "0:12:65:-40".equals(EmbassyAirportMath.key(0, new int[] {12, 65, -40})) && EmbassyAirportMath.key(0, null) == null
                && EmbassyAirportMath.key(3, new int[] {1, 2}) == null && "-1:0:0:0".equals(EmbassyAirportMath.key(-1, new int[] {0, 0, 0}));
        List<String> taken = new ArrayList<String>();
        boolean free = "XTAA".equals(EmbassyAirportMath.uniqueIcao("XTAA", taken)) && "XTAA".equals(EmbassyAirportMath.uniqueIcao("xtaa", taken));
        taken.add("xtaa");
        String next = EmbassyAirportMath.uniqueIcao("XTAA", taken);
        taken.add("XTAZ");
        String carry = EmbassyAirportMath.uniqueIcao("XTAZ", taken);
        List<String> wrap = new ArrayList<String>();
        wrap.add("XTZZ");
        String wrapped = EmbassyAirportMath.uniqueIcao("XTZZ", wrap);
        List<String> all = new ArrayList<String>();
        for (int i = 0; i < 26 * 26; i++) {
            all.add("XT" + (char) ('A' + i / 26) + (char) ('A' + i % 26));
        }
        String full = EmbassyAirportMath.uniqueIcao("XTAA", all);
        boolean unique = free && "XTAB".equals(next) && "XTBA".equals(carry) && "XTAA".equals(wrapped) && "".equals(full)
                && "".equals(EmbassyAirportMath.uniqueIcao(null, taken)) && "".equals(EmbassyAirportMath.uniqueIcao("XTA", taken));
        boolean player = !EmbassyAirportMath.playerIcaoAllowed("XABC") && !EmbassyAirportMath.playerIcaoAllowed("xabc")
                && EmbassyAirportMath.playerIcaoAllowed("ZABC") && EmbassyAirportMath.playerIcaoAllowed("UUEE") && !EmbassyAirportMath.playerIcaoAllowed("ZAB")
                && !EmbassyAirportMath.playerIcaoAllowed(null) && EmbassyAirportMath.EMBASSY_PREFIX == 'X';
        // a new player tower's default code never starts with X (it is «Z...»)
        player &= Character.toUpperCase(TowerMath.defaultIcao(123, -456, new ArrayList<String>()).charAt(0)) != EmbassyAirportMath.EMBASSY_PREFIX;
        boolean ident = "ITAA".equals(EmbassyAirportMath.locIdent("XTAA")) && "IKRB".equals(EmbassyAirportMath.locIdent("xkrb"))
                && "IEMB".equals(EmbassyAirportMath.locIdent(null)) && "IEMB".equals(EmbassyAirportMath.locIdent("XT"));
        boolean grid = true, pair = true;
        for (int id = -3; id < 200; id++) {
            int lo = EmbassyAirportMath.locFreq(id, true), hi = EmbassyAirportMath.locFreq(id, false);
            grid &= LinerIls.clampFreq(lo) == lo && LinerIls.clampFreq(hi) == hi && lo >= LinerIls.FREQ_MIN && hi <= LinerIls.FREQ_MAX
                    && hi >= LinerIls.FREQ_MIN && lo <= LinerIls.FREQ_MAX;
            pair &= lo != hi;
        }
        boolean elev = EmbassyAirportMath.endElev(64) == 65 && EmbassyAirportMath.endElev(0) == 1;
        check(key && unique && player && ident && grid && pair && elev, "embassy airport (§6.1.4): keys dim:x:y:z (null - null) " + key
                + "; uniqueIcao - free XTAA its own, taken - " + next + ", XTAZ taken - " + carry + ", XTZZ taken - " + wrapped + ", all 676 taken - \"" + full
                + "\" " + unique + "; a player's tower may not take X (XABC no, ZABC yes; the default code Z...) " + player + "; localizer idents I + the"
                + " code's last three " + ident + "; frequencies on the 0.05 grid within 108.10..111.95 " + grid + ", the two ends of an embassy apart " + pair
                + "; an end's elevation the paving's top " + elev);
    }

    private static IlsPairing.Rwy rwy(String key, double x0, double x1, double z) {
        IlsPairing.Rwy r = new IlsPairing.Rwy();
        r.a = new IlsPairing.End(key + "a", 0, x0, z, 65, 90, 9);
        r.b = new IlsPairing.End(key + "b", 0, x1, z, 65, 270, 9);
        r.course = 90;
        r.len = Math.abs(x1 - x0);
        r.width = 9;
        return r;
    }

    private static void towerAssign() {
        List<TowerMath.Site> sites = new ArrayList<TowerMath.Site>();
        sites.add(new TowerMath.Site(0, 0, 0));                    // a player's tower
        sites.add(new TowerMath.Site(0, 1000, 0, 7));              // embassy 7's virtual tower
        List<IlsPairing.Rwy> rws = new ArrayList<IlsPairing.Rwy>();
        rws.add(rwy("p", 850, 950, 0));                            // a player's runway, the embassy's tower nearer
        rws.add(rwy("e", 50, 150, 0));                             // embassy 7's runway, the player's tower nearer
        rws.add(rwy("f", 5000, 5100, 0));                          // a player's runway out of every tower's reach
        rws.add(rwy("g", 950, 1050, 0));                           // embassy 8's runway (its tower missing)
        int[] both = TowerMath.assign(sites, rws, new int[] {-1, 7, -1, 8});
        boolean ways = both[0] == 0 && both[1] == 1 && both[2] == -1 && both[3] == -1;
        // the old assign: every runway a player's - never the embassy's tower
        int[] old = TowerMath.assign(sites, rws);
        boolean oldNoEmb = old[0] == 0 && old[1] == 0 && old[2] == -1 && old[3] == 0;
        // only player towers: the nearest within RANGE as before (both signatures)
        List<TowerMath.Site> players = new ArrayList<TowerMath.Site>();
        players.add(new TowerMath.Site(0, 0, 0));
        players.add(new TowerMath.Site(0, 1000, 0));
        players.add(new TowerMath.Site(1, 900, 0));                // another dimension
        int[] plain = TowerMath.assign(players, rws), plainNull = TowerMath.assign(players, rws, null), shortArr = TowerMath.assign(players, rws, new int[0]);
        boolean asBefore = plain[0] == 1 && plain[1] == 0 && plain[2] == -1 && plain[3] == 1 && Arrays.equals(plain, plainNull) && Arrays.equals(plain, shortArr)
                && new TowerMath.Site(0, 0, 0).emb == -1 && new TowerMath.Site(0, 0, 0, -9).emb == -1 && new TowerMath.Site(0, 0, 0, 3).emb == 3;
        check(ways && oldNoEmb && asBefore, "embassy airport: TowerMath.assign both ways - a player's runway never to the embassy's nearer tower, the embassy's"
                + " runway only to its own " + Arrays.toString(both) + " " + ways + "; the old assign (all runways players') never gives one to an embassy tower "
                + Arrays.toString(old) + " " + oldNoEmb + "; player towers only - the nearest within " + (int) TowerMath.RANGE + " as before " + Arrays.toString(plain)
                + " " + asBefore);
    }

    // ---------------------------------------------------------------- 5. ATC (§6.1.5)

    private static void atc() {
        int n = 0;
        boolean codes = EmbassyAtcMath.CODE_LO == 05100 && EmbassyAtcMath.CODE_HI == 05177;
        for (int c = EmbassyAtcMath.CODE_LO; c <= EmbassyAtcMath.CODE_HI; c++) {
            n++;
            boolean special = c == XpdrMath.VFR || c == XpdrMath.EMERGENCY || c == XpdrMath.NO_RADIO || c == XpdrMath.HIJACK;
            codes &= XpdrMath.valid(c) && !special && (c < TowerMath.CODE_LO || c > TowerMath.CODE_HI) && "51".equals(XpdrMath.format(c).substring(0, 2));
        }
        codes &= n == 64;
        boolean atis = EmbassyAtcMath.atisToChat(599, false, 600) && EmbassyAtcMath.atisToChat(600, false, 600) && !EmbassyAtcMath.atisToChat(601, false, 600)
                && !EmbassyAtcMath.atisToChat(700, false, 600) && EmbassyAtcMath.atisToChat(500, false, 600) && EmbassyAtcMath.atisToChat(2000, true, 600)
                && EmbassyAtcMath.atisToChat(700, false, 800);
        boolean refuse = EmbassyAtcMath.landingRefusal(true, true, false, false, false) == EmbassyAtcMath.REFUSE_NONE
                && EmbassyAtcMath.landingRefusal(true, true, true, true, false) == EmbassyAtcMath.REFUSE_NONE
                && EmbassyAtcMath.landingRefusal(false, true, true, true, false) == EmbassyAtcMath.REFUSE_NONE
                && EmbassyAtcMath.landingRefusal(true, false, false, false, false) == EmbassyAtcMath.REFUSE_ENEMY
                && EmbassyAtcMath.landingRefusal(true, false, true, true, false) == EmbassyAtcMath.REFUSE_ENEMY
                && EmbassyAtcMath.landingRefusal(false, false, true, true, false) == EmbassyAtcMath.REFUSE_LINER
                && EmbassyAtcMath.landingRefusal(false, false, true, true, true) == EmbassyAtcMath.REFUSE_NONE
                && EmbassyAtcMath.landingRefusal(false, false, true, false, false) == EmbassyAtcMath.REFUSE_NONE
                && EmbassyAtcMath.landingRefusal(false, false, false, true, false) == EmbassyAtcMath.REFUSE_NONE
                && EmbassyAtcMath.REFUSE_KEYS.length == 2 && "enemy".equals(EmbassyAtcMath.REFUSE_KEYS[EmbassyAtcMath.REFUSE_ENEMY])
                && "liner".equals(EmbassyAtcMath.REFUSE_KEYS[EmbassyAtcMath.REFUSE_LINER]) && EmbassyAtcMath.REFUSE_NONE < 0;
        boolean dome = EmbassyAtcMath.domeRefused(true, false) && !EmbassyAtcMath.domeRefused(true, true) && !EmbassyAtcMath.domeRefused(false, false)
                && !EmbassyAtcMath.domeRefused(false, true);
        boolean taxi = EmbassyAtcMath.holdTaxi(true, 100, 300) && EmbassyAtcMath.holdTaxi(true, 0, 300) && !EmbassyAtcMath.holdTaxi(false, 100, 300)
                && !EmbassyAtcMath.holdTaxi(true, 400, 300) && !EmbassyAtcMath.holdTaxi(true, -50, 300);
        check(codes && atis && refuse && dome && taxi, "embassy ATC (§6.1.5): the towers' codes 5100..5177 - " + n + " valid octal codes, none of the players'"
                + " 4100..4177 nor 7000 / 7500 / 7600 / 7700 " + codes + "; ATIS in the chat within 600 (599 / 600 yes, 601 / 700 no), bound for it - at"
                + " 2000 too " + atis + "; the landing refused - 7700 never, the «Враг», the liner on SHORT unless allowed " + refuse + "; over the dome off"
                + " the final " + dome + "; taxiway «А» held while a landing over the high end is on its final " + taxi);
    }

    private static void cpdlc() {
        boolean noResp = !Cpdlc.needsResponse(Cpdlc.U_REFUSED) && !Cpdlc.needsResponse(Cpdlc.U_DOME) && !Cpdlc.needsResponse(Cpdlc.U_EMB_AD)
                && Cpdlc.needsResponse(Cpdlc.U_LAND) && Cpdlc.needsResponse(Cpdlc.U_SQUAWK) && !Cpdlc.needsResponse(Cpdlc.U_TYPES);
        Cpdlc.Msg ad = new Cpdlc.Msg();
        ad.type = Cpdlc.U_EMB_AD;
        ad.arg = EmbassyCompatSVA.AD_FIRE;
        ad.s = "SVA-12";
        String[] a = Cpdlc.args(ad);
        ad.arg = 99;                                            // a bad line: clamped
        String[] a2 = Cpdlc.args(ad);
        Cpdlc.Msg ref = new Cpdlc.Msg();
        ref.type = Cpdlc.U_REFUSED;
        ref.s = "liner";
        String[] r = Cpdlc.args(ref);
        ref.s = "";
        String[] r2 = Cpdlc.args(ref);
        Cpdlc.Msg dome = new Cpdlc.Msg();
        dome.type = Cpdlc.U_DOME;
        String[] d = Cpdlc.args(dome);
        boolean args = a.length == 2 && "SVA-12".equals(a[0]) && "#sva.cpdlc.embAd.fire".equals(a[1]) && a2.length == 2
                && ("#sva.cpdlc.embAd." + EmbassyCompatSVA.AD_KEYS[EmbassyCompatSVA.AD_LEFT]).equals(a2[1]) && r.length == 1
                && "#sva.cpdlc.refused.liner".equals(r[0]) && r2.length == 1 && "#sva.cpdlc.refused.enemy".equals(r2[0]) && d.length == 0;
        check(noResp && args, "embassy CPDLC: ОБСЛУЖИВАНИЕ ЗАПРЕЩЕНО / ПОСАДКА В КУПОЛЕ ЗАПРЕЩЕНА / the embassy's air defence want no response (a landing"
                + " clearance still does) " + noResp + "; args - U_EMB_AD {callsign, #sva.cpdlc.embAd.<line>} " + Arrays.toString(a) + ", U_REFUSED"
                + " {#sva.cpdlc.refused.<reason>} " + Arrays.toString(r) + " / empty - enemy, U_DOME none " + args);
    }

    // ---------------------------------------------------------------- 6. the services (§6.1.6)

    private static void services() {
        boolean kinds = EmbassyServiceMath.spotKindFor(VehicleType.LINER) == EmbassyServiceMath.SPOT_LINER
                && EmbassyServiceMath.spotKindFor(VehicleType.PLANE) == EmbassyServiceMath.SPOT_SMALL
                && EmbassyServiceMath.spotKindFor(VehicleType.JET) == EmbassyServiceMath.SPOT_SMALL
                && EmbassyServiceMath.spotKindFor(VehicleType.HELI) == EmbassyServiceMath.SPOT_HELI
                && EmbassyServiceMath.spotKindFor(VehicleType.TILT) == EmbassyServiceMath.SPOT_HELI
                && EmbassyServiceMath.spotKindFor(VehicleType.BUGGY) == EmbassyServiceMath.NONE
                && EmbassyServiceMath.spotKindFor(VehicleType.TRUCK) == EmbassyServiceMath.NONE
                && EmbassyServiceMath.spotKindFor(VehicleType.DRILL) == EmbassyServiceMath.NONE && EmbassyServiceMath.spotKindFor(null) == EmbassyServiceMath.NONE
                && EmbassyServiceMath.SPOT_LINER == EmbassyCompatSVA.SPOT_LINER && EmbassyServiceMath.SPOT_SMALL == EmbassyCompatSVA.SPOT_SMALL
                && EmbassyServiceMath.SPOT_HELI == EmbassyCompatSVA.SPOT_HELI;
        // every type: it fits its own kind's stand, the liner only МС-1, nothing but the liner on МС-1, the wheeled / tracked ones nowhere
        boolean fits = true;
        StringBuilder bad = new StringBuilder();
        for (VehicleType t : VehicleType.values()) {
            int k = EmbassyServiceMath.spotKindFor(t);
            boolean own = k == EmbassyServiceMath.NONE ? !EmbassyServiceMath.fits(t, EmbassyServiceMath.SPOT_LINER)
                    && !EmbassyServiceMath.fits(t, EmbassyServiceMath.SPOT_SMALL) && !EmbassyServiceMath.fits(t, EmbassyServiceMath.SPOT_HELI)
                    : EmbassyServiceMath.fits(t, k);
            boolean liner = t == VehicleType.LINER ? !EmbassyServiceMath.fits(t, EmbassyServiceMath.SPOT_SMALL) && !EmbassyServiceMath.fits(t, EmbassyServiceMath.SPOT_HELI)
                    : !EmbassyServiceMath.fits(t, EmbassyServiceMath.SPOT_LINER);
            boolean ground = t.movement != Movement.GROUND && t.movement != Movement.CRAWLER || k == EmbassyServiceMath.NONE;
            if (!own || !liner || !ground) {
                fits = false;
                bad.append(' ').append(t.name());
            }
        }
        fits &= EmbassyServiceMath.fits(VehicleType.HELI, EmbassyServiceMath.SPOT_SMALL) && !EmbassyServiceMath.fits(VehicleType.PLANE, EmbassyServiceMath.SPOT_HELI)
                && !EmbassyServiceMath.fits(null, EmbassyServiceMath.SPOT_SMALL);
        boolean park = EmbassyServiceMath.mayPark(0) && EmbassyServiceMath.mayPark(1) && !EmbassyServiceMath.mayPark(2) && !EmbassyServiceMath.mayPark(3);
        boolean days = EmbassyServiceMath.parkDaysDue(10, -1, 10) == 0 && EmbassyServiceMath.parkDaysDue(10, -1, 11) == 1
                && EmbassyServiceMath.parkDaysDue(10, -1, 13) == 3 && EmbassyServiceMath.parkDaysDue(10, 12, 13) == 1
                && EmbassyServiceMath.parkDaysDue(10, 13, 13) == 0 && EmbassyServiceMath.parkDaysDue(10, 20, 13) == 0
                && EmbassyServiceMath.parkDaysDue(10, -1, 9) == 0;
        // a talon: the server extends the payment through today while its owner holds one - nothing owed
        days &= EmbassyServiceMath.parkDaysDue(3, 11, 11) == 0;
        boolean emergency = EmbassyServiceMath.emergencyAmount(0, 1000) == 250 && EmbassyServiceMath.emergencyAmount(100, 1000) == 150
                && EmbassyServiceMath.emergencyAmount(250, 1000) == 0 && EmbassyServiceMath.emergencyAmount(900, 1000) == 0
                && EmbassyServiceMath.emergencyAmount(-5, 1000) == 250 && EmbassyServiceMath.emergencyAmount(0, 0) == 0
                && EmbassyServiceMath.emergencyAmount(0, 48000) == 12000;
        boolean tow = EmbassyServiceMath.towDue(0, 2400, 2) && !EmbassyServiceMath.towDue(0, 2399, 2) && EmbassyServiceMath.towDue(1000, 7000, 5)
                && !EmbassyServiceMath.towDue(1000, 6999, 5) && !EmbassyServiceMath.towDue(-1, 99999, 2) && !EmbassyServiceMath.towDue(5000, 100, 2);
        boolean offer = EmbassyServiceMath.offer(EmbassyCompatSVA.S_CHARGE, 20000000L) == 16000000L
                && EmbassyServiceMath.offer(EmbassyCompatSVA.S_CHARGE, 1000000L) == 1000000L && EmbassyServiceMath.offer(EmbassyCompatSVA.S_HYDROGEN, 60000L) == 48000L
                && EmbassyServiceMath.offer(EmbassyCompatSVA.S_REPAIR, 80) == 60 && EmbassyServiceMath.offer(EmbassyCompatSVA.S_PARK, 5) == 5
                && EmbassyServiceMath.offer(EmbassyCompatSVA.S_CHARGE, 0) == 0 && EmbassyServiceMath.offer(-1, 10) == 0 && EmbassyServiceMath.offer(99, 10) == 0;
        boolean hint = EmbassyServiceMath.hint(EmbassyCompatSVA.S_CHARGE, 2000000L) == 1 && EmbassyServiceMath.hint(EmbassyCompatSVA.S_CHARGE, 2000001L) == 2
                && EmbassyServiceMath.hint(EmbassyCompatSVA.S_HYDROGEN, 48000L) == 6 && EmbassyServiceMath.hint(EmbassyCompatSVA.S_ELECTROLYTE, 1) == 2
                && EmbassyServiceMath.hint(EmbassyCompatSVA.S_REPAIR, 60) == 6 && EmbassyServiceMath.hint(EmbassyCompatSVA.S_CHARGE, 0) == 0
                && EmbassyServiceMath.hint(-1, 5) == 0;
        boolean consts = EmbassyServiceMath.SPOTS_PER_PLAYER == 2 && EmbassyServiceMath.EMERGENCY_PCT == 25 && EmbassyServiceMath.SERVE_REACH == 16
                && EmbassyServiceMath.BASE.length == EmbassyCompatSVA.SERVICE_KEYS.length && EmbassyServiceMath.UNIT.length == EmbassyCompatSVA.SERVICE_KEYS.length
                && EmbassyServiceMath.PER_SERVICE.length == EmbassyCompatSVA.SERVICE_KEYS.length && EmbassyServiceMath.PER_DAY.length == EmbassyCompatSVA.SERVICE_KEYS.length;
        check(kinds && fits && park && days && emergency && tow && offer && hint && consts, "embassy services (§6.1.6): stand kinds (the liner МС-1, planes"
                + " МС-2..4, helicopters / tiltrotors «Н», wheeled / tracked machines none) " + kinds + "; every type fits its own kind, only the liner МС-1, the"
                + " ground ones nowhere " + fits + (bad.length() == 0 ? "" : " - BAD:" + bad) + "; 2 stands a player " + park + "; parking days - the first free,"
                + " then 1 a day, paid / a talon none " + days + "; the emergency up to 25% " + emergency + "; towing after 2 / 5 min " + tow + "; the window's"
                + " offer within the per-service caps " + offer + "; the base price hint " + hint + "; the numbers' shape " + consts);
        if (!EmbassyCompatSVA.on()) {
            check(true, "embassy services: the mirrors of the core's EmbassyAirRulesSC skipped - no embassy API (" + EmbassyCompatSVA.why() + ")");
            return;
        }
        boolean mirrors = Arrays.equals(EmbassyServiceMath.BASE, EmbassyAirRulesSC.BASE) && Arrays.equals(EmbassyServiceMath.UNIT, EmbassyAirRulesSC.UNIT)
                && Arrays.equals(EmbassyServiceMath.PER_SERVICE, EmbassyAirRulesSC.PER_SERVICE) && Arrays.equals(EmbassyServiceMath.PER_DAY, EmbassyAirRulesSC.PER_DAY)
                && EmbassyServiceMath.EMERGENCY_PCT == EmbassyAirRulesSC.EMERGENCY_PCT && EmbassyServiceMath.SPOTS_PER_PLAYER == EmbassyAirRulesSC.SPOTS_PER_PLAYER
                && EmbassyServiceMath.FREE_PARK_DAYS == EmbassyAirRulesSC.FREE_PARK_DAYS && EmbassyServiceMath.FREE_TOWS == EmbassyAirRulesSC.FREE_TOWS
                && EmbassyServiceMath.FREE_CHARTS == EmbassyAirRulesSC.FREE_CHARTS;
        // the hint is the core's price at the base (no level, no spread): base x whole units
        boolean units = true;
        for (int s = 0; s < EmbassyServiceMath.UNIT.length; s++) {
            for (long amount : new long[] {1, EmbassyServiceMath.UNIT[s], EmbassyServiceMath.UNIT[s] + 1L, 3L * EmbassyServiceMath.UNIT[s]}) {
                units &= EmbassyServiceMath.hint(s, amount) == (long) EmbassyAirRulesSC.BASE[s] * EmbassyAirRulesSC.units(s, amount);
            }
        }
        check(mirrors && units, "embassy services: BASE / UNIT / PER_SERVICE / PER_DAY / EMERGENCY_PCT / SPOTS_PER_PLAYER / FREE_* = the core's EmbassyAirRulesSC "
                + mirrors + "; the hint = base x the core's units() " + units);
    }

    // ---------------------------------------------------------------- 7. the lights (§6.1.7)

    private static void lights() {
        int width = 9;
        double len = 160, eu = width / 2.0 + 0.15, tu = Math.max(0.6, width * 0.2);
        boolean ok = true;
        StringBuilder why = new StringBuilder();
        int[] counts = null;
        for (int dir : new int[] {1, -1}) {
            List<RunwayLayout.Light> on = EmbassyLightsMath.lights(len, width, dir, true, true, 20);
            List<RunwayLayout.Light> off = EmbassyLightsMath.lights(len, width, dir, false, false, 20);
            int edge = 0, red = 0, centre = 0, tdz = 0, alsOn = 0, alsOff = 0;
            for (RunwayLayout.Light l : on) {
                edge += edge(l, eu) ? 1 : 0;
                red += l.colour == RunwayLayout.RED && Math.abs(l.h - 0.15) < 1e-6 ? 1 : 0;
                centre += Math.abs(l.u) < 1e-6 && l.v > 0 && l.v < len && l.h < 0.1 ? 1 : 0;
                tdz += l.colour == RunwayLayout.WHITE && Math.abs(Math.abs(l.u) - tu) < 1e-6 && l.h < 0.1 ? 1 : 0;
                alsOn += als(l, len) ? 1 : 0;
            }
            for (RunwayLayout.Light l : off) {
                alsOff += als(l, len) ? 1 : 0;
                edge += edge(l, eu) ? 1 : 0;
            }
            boolean good = edge == 0 && red == 0 && centre > 20 && tdz > 10 && alsOn > 20 && alsOff == 0;
            if (!good) {
                why.append(" dir ").append(dir).append(": edge ").append(edge).append(" red ").append(red).append(" centre ").append(centre).append(" tdz ")
                        .append(tdz).append(" als ").append(alsOn).append(" / ").append(alsOff);
            }
            ok &= good;
            if (counts == null) {
                counts = new int[] {on.size(), centre, tdz, alsOn};
            }
        }
        // the landing end's own ALS only: landing toward B (+1) with an ALS only at B - none
        int other = 0;
        for (RunwayLayout.Light l : EmbassyLightsMath.lights(len, width, 1, false, true, 20)) {
            other += als(l, len) ? 1 : 0;
        }
        ok &= other == 0;
        double g = 3.0;
        boolean papi = EmbassyLightsMath.papiWhites(g + 1, g) == 4 && EmbassyLightsMath.papiWhites(g + 0.3, g) == 3 && EmbassyLightsMath.papiWhites(g, g) == 2
                && EmbassyLightsMath.papiWhites(g - 0.3, g) == 1 && EmbassyLightsMath.papiWhites(g - 1, g) == 0 && EmbassyLightsMath.papiWhites(3.5, 3.5) == 2 && EmbassyLightsMath.papiWhites(4.0, 3.5) == 3
                && EmbassyLightsMath.PAPI_STEPS.length == 4;
        check(ok && papi, "embassy lights (§6.1.7): a FULL runway (" + (int) len + " between the threshold rows, 9 wide) - no edge light (the core's), no red end"
                + " light (the core's), the centre line and the touchdown zone there, the ALS only where the landing end has one - lights / centre / TDZ / ALS "
                + Arrays.toString(counts) + (why.length() == 0 ? "" : " - BAD:" + why) + "; PAPI whites 4 / 3 / 2 / 1 / 0 from far too high to far too low " + papi);
    }

    /** RunwayLayout's edge light (the core lays its own): at the runway's edge + 0.15, 0.25 high. */
    private static boolean edge(RunwayLayout.Light l, double eu) {
        return Math.abs(Math.abs(l.u) - eu) < 1e-6 && Math.abs(l.h - 0.25) < 1e-6;
    }

    /**
     * A light of the approach lights: a flasher of the run, or a raised light (RunwayLayout.als: 0.3 + 1% of its distance) out before a
     * threshold - not the centre line carried over the displaced part (flush), not the threshold's own row.
     */
    private static boolean als(RunwayLayout.Light l, double len) {
        return l.colour == RunwayLayout.FLASH || l.run >= 0 || (l.v < -0.5 || l.v > len + 0.5) && l.h >= 0.29;
    }

    // ---------------------------------------------------------------- 8. the enums and the stands' NBT (§6.1.8)

    private static void enums() {
        boolean src = NavDb.SRC_EMBASSY == 6 && NavDb.SRC_KEYS.length == NavDb.SRC_EMBASSY + 1 && "embassy".equals(NavDb.SRC_KEYS[NavDb.SRC_EMBASSY])
                && NavDb.SRC_TOWER == 5 && "tower".equals(NavDb.SRC_KEYS[NavDb.SRC_TOWER]) && new NavDb.Airport().embassy == -1;
        boolean cp = Cpdlc.U_REFUSED == 15 && Cpdlc.U_DOME == 16 && Cpdlc.U_EMB_AD == 17 && Cpdlc.U_TYPES == 18 && Cpdlc.U_IFF == 14 && Cpdlc.U_SQUAWK == 13;
        boolean jump = SpaceMath.EMBASSY == 12 && SpaceMath.CHUNK == 11 && SpaceMath.EMBASSY_AD == 13;
        boolean ad = EmbassyAdMath.A_KEYS.length == EmbassyAdMath.A_FIRE + 1 && EmbassyAdMath.WHY_KEYS.length == EmbassyAdMath.WHY_REENTRY + 1
                && EmbassyAdMath.A_NONE == 0 && EmbassyAdMath.TICKS == 20 && EmbassyCompatSVA.AD_KEYS.length == 6 && "left".equals(EmbassyCompatSVA.AD_KEYS[5]);
        check(src && cp && jump && ad, "embassy enums (§6.1.8): NavDb.SRC_EMBASSY 6 appended («embassy», SRC_KEYS " + NavDb.SRC_KEYS.length + ") " + src
                + "; Cpdlc U_REFUSED 15 / U_DOME 16 / U_EMB_AD 17, U_TYPES 18 " + cp + "; SpaceMath.EMBASSY 12 after CHUNK 11, EMBASSY_AD 13 " + jump + "; the AD's verdicts"
                + " / reasons / lines " + ad);
    }

    private static void parking() {
        EmbassyParkingDataSVA d = new EmbassyParkingDataSVA(EmbassyParkingDataSVA.NAME);
        String o1 = "00000000-0000-0000-0000-0000000000a1", o2 = "00000000-0000-0000-0000-0000000000a2";
        d.take(7, 1, "veh-1", o1, 1000L, 3L);
        EmbassyParkingDataSVA.Stand s2 = d.take(7, 2, "veh-2", o1, 2000L, 4L);
        d.paid(s2, 6L);
        d.take(9, 0, "veh-3", o2, 3000L, 5L);
        d.toLot(7, "veh-4", o2, 4000L);
        boolean before = d.counts()[0] == 3 && d.counts()[1] == 1 && d.standsOf(7, o1).size() == 2 && d.standsOf(7, o2).isEmpty()
                && d.stand(7, 2) == s2 && d.stand(7, 3) == null && d.lot(7).size() == 1 && d.lot(9).isEmpty();
        NBTTagCompound t = new NBTTagCompound();
        d.writeToNBT(t);
        EmbassyParkingDataSVA r = new EmbassyParkingDataSVA(EmbassyParkingDataSVA.NAME);
        r.readFromNBT(t);
        EmbassyParkingDataSVA.Stand a = r.standOf("veh-2"), b = r.standOf("veh-1"), c = r.standOf("veh-3");
        List<EmbassyParkingDataSVA.Stand> lot = r.lot(7);
        boolean round = r.counts()[0] == 3 && r.counts()[1] == 1 && a != null && a.embassy == 7 && a.spot == 2 && o1.equals(a.owner) && a.since == 2000L
                && a.sinceDay == 4L && a.paidDay == 6L && b != null && b.paidDay == -1L && b.spot == 1 && c != null && c.embassy == 9 && c.spot == 0
                && lot.size() == 1 && "veh-4".equals(lot.get(0).vid) && o2.equals(lot.get(0).owner) && lot.get(0).since == 4000L && lot.get(0).spot == -1
                && r.standOf("veh-4") == null && t.getTagList("Stands", 10).tagCount() == 3 && t.getTagList("Lot", 10).tagCount() == 1;
        // a machine moved: its old place goes (stand -> lot -> stand), released - gone everywhere
        r.toLot(7, "veh-1", o1, 5000L);
        boolean moved = r.standOf("veh-1") == null && r.lot(7).size() == 2;
        r.take(7, 3, "veh-1", o1, 6000L, 7L);
        moved &= r.standOf("veh-1") != null && r.standOf("veh-1").spot == 3 && r.lot(7).size() == 1 && r.release("veh-1") && !r.release("veh-1")
                && r.standOf("veh-1") == null && r.counts()[0] == 2;
        // an old save without "P" (paid through): -1; a broken entry (no machine) dropped
        NBTTagCompound old = new NBTTagCompound();
        NBTTagList l = new NBTTagList();
        NBTTagCompound e = new NBTTagCompound();
        e.setInteger("E", 3);
        e.setInteger("S", 1);
        e.setString("V", "veh-9");
        e.setString("O", o1);
        l.appendTag(e);
        l.appendTag(new NBTTagCompound());
        old.setTag("Stands", l);
        EmbassyParkingDataSVA x = new EmbassyParkingDataSVA(EmbassyParkingDataSVA.NAME);
        x.readFromNBT(old);
        boolean compat = x.counts()[0] == 1 && x.counts()[1] == 0 && x.standOf("veh-9") != null && x.standOf("veh-9").paidDay == -1L;
        check(before && round && moved && compat, "embassy stands' NBT (§6.1.8): «Stands» {E, S, V, O, T, D, P} and «Отстой» {E, V, O, T} there and back "
                + round + " (before " + before + "); a machine moved between a stand and the lot keeps one place, released - none " + moved + "; an old entry"
                + " without P - unpaid, an empty one dropped " + compat);
    }

    // ---------------------------------------------------------------- 9. the lang (§6.1.9)

    /** §5 of the plan: every key the add-on's embassy airfield says (the families are built from the code's own arrays in lang()). */
    static final String[] KEYS = {"sva.mcdu.src.embassy", "sva.tower.msg.icaoX", "sva.cpdlc.u.15", "sva.cpdlc.u.16", "sva.cpdlc.u.17",
        "sva.cpdlc.refused.enemy", "sva.cpdlc.refused.liner", "sva.emb.dome.warn", "sva.emb.zoneNeutral",
        "sva.msg.jump.no.12", "sva.hud.jump.why.12", "sva.emb.gui.title", "sva.emb.gui.free", "sva.emb.gui.noPaper", "sva.emb.gui.hangarOnly",
        "sva.emb.gui.refresh", "sva.emb.gui.close", "sva.emb.msg.parked", "sva.emb.msg.limit", "sva.emb.msg.debt", "sva.emb.msg.towedStand",
        "sva.emb.msg.towedLot", "sva.emb.msg.served", "sva.emb.msg.towed", "sva.emb.tab.stand", "sva.emb.tab.prices", "sva.emb.tab.tower"};

    private static void lang() throws java.io.IOException {
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        if (ru == null || en == null) {
            check(false, "embassy lang: ru_RU / en_US missing");
            return;
        }
        List<String> keys = new ArrayList<String>(Arrays.asList(KEYS));
        for (String k : EmbassyCompatSVA.AD_KEYS) {
            keys.add("sva.cpdlc.embAd." + k);
            keys.add("sva.emb.ad.hud." + k);
        }
        for (String k : EmbassyAtcMath.REFUSE_KEYS) {
            keys.add("sva.cpdlc.refused." + k);
        }
        for (int i = Cpdlc.U_REFUSED; i < Cpdlc.U_TYPES; i++) {
            keys.add("sva.cpdlc.u." + i);
        }
        for (String k : com.sva.airport.AtisMath.RMK_KEYS) {
            if (k.startsWith("emb")) {
                keys.add("sva.atis.rmk." + k);                    // the embassy tower's ATIS remarks (package C's)
            }
        }
        keys.add("sva.mcdu.src." + NavDb.SRC_KEYS[NavDb.SRC_EMBASSY]);
        keys.add("sva.hud.jump.why." + SpaceMath.EMBASSY);
        keys.add("sva.msg.jump.no." + SpaceMath.EMBASSY);
        keys.add("sva.hud.jump.why." + SpaceMath.EMBASSY_AD);
        keys.add("sva.msg.jump.no." + SpaceMath.EMBASSY_AD);
        // every embassy key either file has (the packages' sva.emb.* too): in both, glyph-safe, the same arguments
        List<String> all = new ArrayList<String>(ru.keySet());
        all.addAll(en.keySet());
        for (String k : all) {
            if (k.startsWith("sva.emb.") && !keys.contains(k)) {
                keys.add(k);
            }
        }
        StringBuilder miss = new StringBuilder(), glyph = new StringBuilder(), args = new StringBuilder();
        for (String k : keys) {
            String r = ru.get(k), e = en.get(k);
            if (r == null || e == null || r.trim().length() == 0 || e.trim().length() == 0) {
                miss.append(' ').append(k).append(r == null ? " (ru)" : "").append(e == null ? " (en)" : "");
                continue;
            }
            for (String v : new String[] {r, e}) {
                for (int i = 0; i < v.length(); i++) {
                    char c = v.charAt(i);
                    if (GlyphCheckSVA.forbidden(c) || c == '«' || c == '»') {
                        glyph.append(' ').append(k).append(":U+").append(Integer.toHexString(0x10000 | c).substring(1).toUpperCase());
                    }
                }
            }
            if (args(r) != args(e)) {
                args.append(' ').append(k);
            }
        }
        check(miss.length() == 0 && glyph.length() == 0 && args.length() == 0, "embassy lang (§6.1.9): all " + keys.size() + " keys of §5 (the AD's lines on"
                + " the radio and the HUD, the refusals, the CPDLC types 15..17, the MCDU's source, the jump code 12, every sva.emb.*) in ru_RU and en_US"
                + (miss.length() == 0 ? "" : " - missing" + miss) + "; no « » nor a glyph of the font's table 128..255" + (glyph.length() == 0 ? "" : " - BAD" + glyph)
                + "; the same arguments in both" + (args.length() == 0 ? "" : " - BAD" + args));
    }

    /** The number of format arguments of a lang value (%s / %d / %1$s ...; %% is none). */
    private static int args(String v) {
        int n = 0;
        for (int i = 0; i + 1 < v.length(); i++) {
            if (v.charAt(i) == '%') {
                if (v.charAt(i + 1) == '%') {
                    i++;
                } else {
                    n++;
                }
            }
        }
        return n;
    }
}
