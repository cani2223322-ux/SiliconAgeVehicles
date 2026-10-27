package com.sva.debug;

import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.item.ItemCodeCardSVA;
import com.sva.vehicle.VehicleType;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Code-only wave self-test (docs/proposal-ad-strict-iff.md, plan-next §10) - the core: pure logic and data, the dedicated
 * server runs it:
 * <ul>
 * <li>the key's clock (item 19): the total world time by default, /time set changes nothing; the overlap window (item 18);</li>
 * <li>the day code with the server's pepper and the key generation (items 24, 36), the reserved codes never own codes (item
 * 35);</li>
 * <li>the card (items 17, 18, 30): its NBT carries no code, its state for a key (owner, generation, periods, overlap), what it
 * offers the transponder, the time left and the HUD's 5 / 1 minute warning; an older card nothing (review batch 2);</li>
 * <li>the verdict table (items 1-9; the user's decisions 1 and 2): identify, classifyByCode in the proposal's order, the two
 * misses in a row, ownBlocked, the 7700 grace and decisions, the pilot's reasons;</li>
 * <li>guessing (item 11), arming (item 16), the readiness line (item 32), the code-only warning floor (item 13);</li>
 * <li>the transponder's card fields, the ОПОЗН lamp, the reset when the card is gone; the trust data's NBT (the post's
 * switch and options, the owners' keys, 7700, guesses, the pepper; an older save - the usual mode);</li>
 * <li>the config defaults (item 28 and the user's decisions), the transponder of every vehicle (decision 3), the lang.</li>
 * </ul>
 */
final class SelfTestAdCodeOnlySVA {

    private SelfTestAdCodeOnlySVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        clock();
        codes();
        cards();
        verdicts();
        confirmAndEmergency();
        probes();
        armingAndLauncher();
        transponder();
        data();
        configAndLang();
    }

    // ---------------------------------------------------------------- the key's clock (items 18, 19)

    private static void clock() {
        int tot = XpdrMath.CLOCK_TOTAL, day = XpdrMath.CLOCK_DAY;
        check(XpdrMath.keyClock("total") == tot && XpdrMath.keyClock("DAY") == day && XpdrMath.keyClock(null) == tot && XpdrMath.keyClock("x") == tot
                && XpdrMath.PERIOD_TICKS == 24000, "ad code-only clock: keyClock total by default, day on request; a period is 24000 ticks (20 min)");
        // /time set 0 after 10 days of play: the time of day goes back, the total time does not
        long total = 10 * 24000L + 500, world = 30;
        check(XpdrMath.period(tot, world, total) == 10 && XpdrMath.period(day, world, total) == 0 && CodeOnlyMath.period(tot, world, total) == 10
                && XpdrMath.period(tot, 5 * 24000L, total) == XpdrMath.period(tot, 9 * 24000L, total),
                "ad code-only clock (item 19): the total clock ignores /time set and sleeping; the day clock follows the time of day");
        check(XpdrMath.period(tot, 0, -50) == 0 && XpdrMath.intoPeriod(tot, 0, total) == 500 && XpdrMath.toNextPeriod(tot, 0, total) == 23500,
                "ad code-only clock: never a negative period; 500 ticks into the period, 23500 to the next («смена ключа через»)");
        check(CodeOnlyMath.inOverlap(tot, 0, total, 1200) && !CodeOnlyMath.inOverlap(tot, 0, total, 500) && !CodeOnlyMath.inOverlap(tot, 0, 100, 1200)
                && !CodeOnlyMath.inOverlap(tot, 0, total, 0),
                "ad code-only overlap (item 18): the first 60 s of a period accept the last one's code; never in period 0, nor with 0 s");
    }

    // ---------------------------------------------------------------- the day code, the pepper, the own codes (items 24, 35, 36)

    private static void codes() {
        boolean legacy = true, reserved = false;
        int differ = 0, genDiffer = 0;
        for (long d = 0; d < 200; d++) {
            legacy &= XpdrMath.dayCode("sever", d) == XpdrMath.dayCode("sever", "", 0, d);
            int a = XpdrMath.dayCode("sever", "00ff00ff00ff00ff00ff00ff00ff00ff", 1, d);
            int b = XpdrMath.dayCode("sever", "00ff00ff00ff00ff00ff00ff00ff00fe", 1, d);
            int c = XpdrMath.dayCode("sever", "00ff00ff00ff00ff00ff00ff00ff00ff", 2, d);
            reserved |= XpdrMath.reserved(a) || !XpdrMath.valid(a) || XpdrMath.reserved(c);
            differ += a != b ? 1 : 0;
            genDiffer += a != c ? 1 : 0;
        }
        check(legacy, "ad code-only codes (item 36): no pepper and generation 0 - the old day code to the bit (old cards, old tests)");
        check(!reserved && differ > 190 && genDiffer > 190,
                "ad code-only codes: another pepper or another key generation - other codes (" + differ + "/" + genDiffer + " of 200), never a reserved one");
        check(XpdrMath.dayCode("ab", "c", 1, 7) != XpdrMath.dayCode("a", "bc", 1, 7), "ad code-only codes: the secret and the pepper do not run together");
        check(XpdrMath.ownCodeAllowed(01234) && XpdrMath.ownCodeAllowed(04100) && !XpdrMath.ownCodeAllowed(0) && !XpdrMath.ownCodeAllowed(XpdrMath.VFR)
                && !XpdrMath.ownCodeAllowed(XpdrMath.HIJACK) && !XpdrMath.ownCodeAllowed(XpdrMath.NO_RADIO) && !XpdrMath.ownCodeAllowed(XpdrMath.EMERGENCY)
                && !XpdrMath.ownCodeAllowed(07701) && !XpdrMath.ownCodeAllowed(-1) && !XpdrMath.ownCodeAllowed(010000),
                "ad code-only own codes (item 35): 0000 / 7000 / 7500 / 7600 / 77xx and invalid codes are never own codes");
        AirDefenseDataSVA.KpInfo k = new AirDefenseDataSVA.KpInfo();
        k.codes = new int[] {07000, 01234, 07700, 0};
        check(k.ownCode(01234) && !k.ownCode(07000) && !k.ownCode(07700) && !k.ownCode(0),
                "ad code-only own codes: an older save's 7000 among the post's codes no longer makes every new aircraft a friend");
    }

    // ---------------------------------------------------------------- the card (items 17, 18, 30, 42)

    private static void cards() {
        check(CodeOnlyMath.cardPeriods(3, 7) == 3 && CodeOnlyMath.cardPeriods(7, 3) == 3 && CodeOnlyMath.cardPeriods(0, 7) == 1 && CodeOnlyMath.cardPeriods(9, 0) == 1
                && CodeOnlyMath.DEFAULT_CARD_PERIODS == 3, "ad code-only card: 1..codeCardMaxPeriods periods, 3 by default");
        check(CodeOnlyMath.nextCardPeriods(1, 7) == 3 && CodeOnlyMath.nextCardPeriods(3, 7) == 7 && CodeOnlyMath.nextCardPeriods(7, 7) == 1
                && CodeOnlyMath.nextCardPeriods(3, 3) == 1 && CodeOnlyMath.nextCardPeriods(1, 1) == 1 && CodeOnlyMath.nextCardPeriods(5, 7) == 7,
                "ad code-only card: the post's «Срок карты» 1 - 3 - 7 - 1, the values over the server's maximum skipped");
        // Alice's card, generation 2, periods [10, 13)
        check(CodeOnlyMath.cardState("Alice", 2, 10, 13, "alice", 2, 10, false) == CodeOnlyMath.CARD_OK
                && CodeOnlyMath.cardState("Alice", 2, 10, 13, "Alice", 2, 12, false) == CodeOnlyMath.CARD_OK
                && CodeOnlyMath.cardState("Alice", 2, 10, 13, "Alice", 2, 13, true) == CodeOnlyMath.CARD_PREV
                && CodeOnlyMath.cardState("Alice", 2, 10, 13, "Alice", 2, 13, false) == CodeOnlyMath.CARD_EXPIRED
                && CodeOnlyMath.cardState("Alice", 2, 10, 13, "Alice", 2, 14, true) == CodeOnlyMath.CARD_EXPIRED
                && CodeOnlyMath.cardState("Alice", 2, 10, 13, "Alice", 2, 9, true) == CodeOnlyMath.CARD_EXPIRED
                && CodeOnlyMath.cardState("Alice", 2, 10, 13, "Alice", 3, 11, false) == CodeOnlyMath.CARD_OLD_KEY
                && CodeOnlyMath.cardState("Alice", 2, 10, 13, "Bob", 2, 11, false) == CodeOnlyMath.CARD_NONE
                && CodeOnlyMath.cardState("", 0, -1, -1, "Bob", 1, 11, false) == CodeOnlyMath.CARD_NONE
                && CodeOnlyMath.cardGood(CodeOnlyMath.CARD_PREV) && !CodeOnlyMath.cardGood(CodeOnlyMath.CARD_OLD_KEY),
                "ad code-only card: good in its periods (owner case-blind), the last one's code in the overlap, past them expired, another generation "
                        + "worthless («Сменить ключ»), another owner's - none");
        int tot = XpdrMath.CLOCK_TOTAL;
        check(CodeOnlyMath.cardTicksLeft(13, tot, 0, 12 * 24000L + 18000) == 6000 && CodeOnlyMath.cardTicksLeft(13, tot, 0, 14 * 24000L) == 0
                && CodeOnlyMath.cardWarnMinutes(6000) == 5 && CodeOnlyMath.cardWarnMinutes(6001) == 0 && CodeOnlyMath.cardWarnMinutes(1200) == 1
                && CodeOnlyMath.cardWarnMinutes(1) == 1 && CodeOnlyMath.cardWarnMinutes(0) == 0,
                "ad code-only card (item 22): the time left on the key's clock, the HUD's «КАРТА КОДОВ: 5 МИН» from 5 min, «1 МИН» from 1 min");
        // the item's NBT: no code on a key's card; an older card keeps its code
        ItemStack s = new ItemStack(com.sva.init.ModContentSVA.codeCard, 16);
        ItemCodeCardSVA.print(s, "Север", "Alice", 2, 10, 3);
        NBTTagCompound t = s.getTagCompound();
        check(ItemCodeCardSVA.keyed(s) && ItemCodeCardSVA.printed(s) && !ItemCodeCardSVA.legacy(s) && !t.hasKey(ItemCodeCardSVA.K_CODE) && !t.hasKey("Secret")
                && ItemCodeCardSVA.code(s) == -1 && "Alice".equals(ItemCodeCardSVA.owner(s)) && ItemCodeCardSVA.gen(s) == 2 && ItemCodeCardSVA.first(s) == 10
                && ItemCodeCardSVA.periods(s) == 3 && ItemCodeCardSVA.until(s) == 13 && "Север".equals(ItemCodeCardSVA.post(s)) && s.stackSize == 16,
                "ad code-only card NBT (item 17): Own / Gen / First / Periods and the post's name - never a code; the whole stack printed");
        ItemStack old = new ItemStack(com.sva.init.ModContentSVA.codeCard);
        ItemCodeCardSVA.printLegacy(old, "Юг", 5, 01234);
        ItemStack blank = new ItemStack(com.sva.init.ModContentSVA.codeCard);
        check(ItemCodeCardSVA.legacy(old) && ItemCodeCardSVA.printed(old) && !ItemCodeCardSVA.keyed(old) && ItemCodeCardSVA.code(old) == 01234
                && ItemCodeCardSVA.day(old) == 5 && "".equals(ItemCodeCardSVA.owner(old)) && !ItemCodeCardSVA.printed(blank) && ItemCodeCardSVA.until(blank) == -1,
                "ad code-only card NBT: an older card (Kp / Day / Code) is still read; a blank is neither");
        // what a card offers the transponder (AirDefenseSVA.cardOffer: {rank, code})
        AirDefenseDataSVA d = new AirDefenseDataSVA("test");
        d.setSecret("Alice", "aliceKey");
        d.ownerKey("Alice", true).gen = 2;
        int[] ok = AirDefenseSVA.cardOffer(d, s, 11, 0, false), prev = AirDefenseSVA.cardOffer(d, s, 13, 0, true);
        int[] over = AirDefenseSVA.cardOffer(d, s, 13, 0, false), oldDay = AirDefenseSVA.cardOffer(d, old, 99, 5, false);
        int[] oldNot = AirDefenseSVA.cardOffer(d, old, 99, 6, false), none = AirDefenseSVA.cardOffer(d, blank, 11, 0, false);
        check(ok[0] == 3 && ok[1] == d.keyCode("Alice", 11) && ok[1] >= 0 && prev[0] == 2 && prev[1] == d.keyCode("Alice", 12) && over[0] == 0
                && oldDay[0] == 0 && oldDay[1] == -1 && oldNot[0] == 0 && none[0] == 0,
                "ad code-only card: a good card offers this period's key code, in the overlap the last one's, then nothing; an older card nothing "
                        + "(review batch 2: not even on its day)");
        d.rekey("Alice");
        ItemStack bob = new ItemStack(com.sva.init.ModContentSVA.codeCard);
        ItemCodeCardSVA.print(bob, "Б", "Bob", 1, 10, 3);
        check(AirDefenseSVA.cardOffer(d, s, 11, 0, false)[0] == 0 && AirDefenseSVA.cardOffer(d, bob, 11, 0, false)[0] == 0,
                "ad code-only card (item 24): «Сменить ключ» voids every card printed before; an owner without a secret gives no code");
    }

    // ---------------------------------------------------------------- the verdict (items 1-9; decisions 1, 2)

    private static int id(boolean replied, int code, int card, boolean fromCard, boolean manual) {
        return CodeOnlyMath.identify(replied, code, 02345, 03456, true, card, fromCard, manual, false, false);
    }

    private static void verdicts() {
        int ok = CodeOnlyMath.CARD_OK, none = CodeOnlyMath.CARD_NONE;
        check(id(true, 02345, ok, true, false) == RoeMath.ID_KEY && id(true, 02345, none, false, true) == RoeMath.ID_KEY
                && id(true, 02345, none, false, false) == RoeMath.ID_NO_CARD && id(true, 03456, ok, true, false) == RoeMath.ID_KEY
                && CodeOnlyMath.identify(true, 03456, 02345, 03456, false, ok, true, true, false, false) == RoeMath.ID_WRONG,
                "ad code-only identify (items 1, 18; decision 1): the card's key code; by hand only with «Ручной набор кода»; the last period's code only in the overlap");
        check(id(false, 02345, ok, true, true) == RoeMath.ID_NO_REPLY && id(true, 01111, ok, true, true) == RoeMath.ID_WRONG
                && id(true, 01111, CodeOnlyMath.CARD_EXPIRED, false, true) == RoeMath.ID_EXPIRED
                && id(true, 02345, CodeOnlyMath.CARD_OLD_KEY, true, false) == RoeMath.ID_OLD_KEY && id(true, XpdrMath.VFR, none, false, true) == RoeMath.ID_NO_CARD,
                "ad code-only identify (item 6): no answer, a wrong code with a good card, an expired card, an old key's card, 7000 - all misses with their reason");
        check(CodeOnlyMath.identify(true, 01234, 02345, -1, false, none, false, false, true, false) == RoeMath.ID_OWN_CODE
                && CodeOnlyMath.identify(true, 07000, 02345, -1, false, none, false, false, true, false) == RoeMath.ID_NO_CARD
                && CodeOnlyMath.identify(true, 04101, 02345, -1, false, none, false, false, false, true) == RoeMath.ID_AIRPORT
                && CodeOnlyMath.identify(true, 02345, -1, -1, true, ok, true, true, false, false) == RoeMath.ID_WRONG,
                "ad code-only identify (item 3): own codes (3а, never 7000) and airport codes (3в) as options; no key (-1) matches nothing");
        check(CodeOnlyMath.pilotReason(RoeMath.ID_WRONG) == CodeOnlyMath.pilotReason(RoeMath.ID_NO_CARD) && CodeOnlyMath.pilotReason(RoeMath.ID_NO_CARD) == CodeOnlyMath.P_NO_CARD
                && CodeOnlyMath.pilotReason(RoeMath.ID_EXPIRED) == CodeOnlyMath.P_EXPIRED && CodeOnlyMath.pilotReason(RoeMath.ID_OLD_KEY) == CodeOnlyMath.P_EXPIRED
                && CodeOnlyMath.pilotReason(RoeMath.ID_NO_REPLY) == CodeOnlyMath.P_NO_REPLY,
                "ad code-only (items 11, 22): the pilot hears the same for any wrong code - no card / expired / no answer, never the code");
        // classifyByCode in the proposal's order (item 4)
        int key = RoeMath.ID_KEY, miss = RoeMath.ID_NO_CARD, P = NoRadioMath.S_PENDING;
        int notAsked = RoeMath.classifyByCode(false, true, true, true, 02345, -1, false, key, true, true);
        int granted = RoeMath.classifyByCode(true, true, true, true, 01111, -1, false, miss, true, true);
        int probe = RoeMath.classifyByCode(true, false, true, true, XpdrMath.EMERGENCY, P, false, key, false, false);
        int emGrace = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, P, false, miss, true, true);
        int emFriend = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, NoRadioMath.S_FRIEND, false, miss, true, true);
        int emOver = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, NoRadioMath.S_DEFAULT, false, miss, true, false);
        int emHostile = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, NoRadioMath.S_HOSTILE, false, miss, false, false);
        int matchViol = RoeMath.classifyByCode(true, false, false, true, 02345, -1, false, key, true, true);
        int nr = RoeMath.classifyByCode(true, false, false, true, XpdrMath.NO_RADIO, -1, true, miss, true, true);
        int nrOver = RoeMath.classifyByCode(true, false, false, true, XpdrMath.NO_RADIO, -1, false, miss, true, false);
        int check1 = RoeMath.classifyByCode(true, false, false, false, -1, -1, false, RoeMath.ID_NO_REPLY, false, true);
        int viol = RoeMath.classifyByCode(true, false, false, false, -1, -1, false, RoeMath.ID_NO_REPLY, true, true);
        int foreign = RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, RoeMath.ID_WRONG, true, false);
        int hijack = RoeMath.classifyByCode(true, false, false, true, XpdrMath.HIJACK, -1, false, miss, true, false);
        check(RoeMath.idClass(notAsked) == RoeMath.UNKNOWN && RoeMath.idReason(notAsked) == RoeMath.ID_NOT_ASKED
                && RoeMath.idClass(granted) == RoeMath.FRIEND && RoeMath.idReason(granted) == RoeMath.ID_GRANTED
                && RoeMath.idClass(probe) == RoeMath.FOREIGN && RoeMath.idReason(probe) == RoeMath.ID_PROBE,
                "ad code-only verdict (item 4): never asked - UNKNOWN; «свой на 10 мин» first; the ПОДБОР block a target at once, before 7700 and a match");
        check(RoeMath.idClass(emGrace) == RoeMath.EMERGENCY && RoeMath.idClass(emFriend) == RoeMath.FRIEND
                && RoeMath.idClass(emOver) == RoeMath.FOREIGN && RoeMath.idReason(emOver) == RoeMath.ID_EMERGENCY_OVER
                && RoeMath.idClass(emHostile) == RoeMath.UNKNOWN && RoeMath.idReason(emHostile) == RoeMath.ID_CHECK && RoeMath.idMissOf(emHostile) == RoeMath.ID_EMERGENCY_OVER,
                "ad code-only verdict (item 7; decision 2): 7700 in its grace - EMERGENCY (in a forbidden zone too); «свой» - FRIEND; used up / «враждебный» - a miss");
        check(RoeMath.idClass(matchViol) == RoeMath.FRIEND && RoeMath.idReason(matchViol) == RoeMath.ID_KEY
                && RoeMath.idClass(nr) == RoeMath.NO_RADIO && RoeMath.idClass(nrOver) == RoeMath.FOREIGN && RoeMath.idReason(nrOver) == RoeMath.ID_NO_RADIO_OVER
                && RoeMath.idReason(hijack) == RoeMath.ID_HIJACK && RoeMath.hostile(RoeMath.idClass(hijack)),
                "ad code-only verdict: the key's code is a friend even in a forbidden zone / after a handoff; 7600 as now; 7500 a target");
        check(RoeMath.idClass(check1) == RoeMath.UNKNOWN && RoeMath.idReason(check1) == RoeMath.ID_CHECK && RoeMath.idMissOf(check1) == RoeMath.ID_NO_REPLY
                && RoeMath.idClass(viol) == RoeMath.VIOLATOR && RoeMath.idReason(viol) == RoeMath.ID_NO_REPLY
                && RoeMath.idClass(foreign) == RoeMath.FOREIGN && RoeMath.idReason(foreign) == RoeMath.ID_WRONG
                && !RoeMath.roeWants(RoeMath.FOREIGN_ALL, RoeMath.idClass(check1), true, true) && RoeMath.roeWants(RoeMath.FOREIGN_ALL, RoeMath.idClass(foreign), false, false),
                "ad code-only verdict (items 5, 6): one miss - UNKNOWN «ПРОВ 1/2» (no fire, the miss kept); confirmed - VIOLATOR in a zone, else FOREIGN");
        check(RoeMath.ownBlocked(false, 1, true) && RoeMath.ownBlocked(false, 2, false) && !RoeMath.ownBlocked(false, 2, true) && !RoeMath.ownBlocked(false, 0, false)
                && !RoeMath.ownBlocked(true, 1, false) && !RoeMath.ownBlocked(true, 2, false),
                "ad code-only (item 2): the owner's / friends' machines spared only in the usual mode - fireOnFriends does not apply in the code-only one");
        check(RoeMath.ID_KEYS.length == RoeMath.IDS && RoeMath.idMatch(RoeMath.ID_AIRPORT) && !RoeMath.idMatch(RoeMath.ID_GRANTED) && RoeMath.idMiss(RoeMath.ID_HIJACK)
                && !RoeMath.idMiss(RoeMath.ID_CHECK) && CodeOnlyMath.OPT_KEYS.length == 4 && CodeOnlyMath.CARD_KEYS.length == 5
                && CodeOnlyMath.READY_KEYS.length == CodeOnlyMath.R_VIOLATORS + 1 && CodeOnlyMath.CO_KEYS.length == CodeOnlyMath.CO_NO_KEY + 1,
                "ad code-only: the reasons' and states' key tables match their constants");
    }

    // ---------------------------------------------------------------- two misses in a row, 7700 (items 5, 7, 31)

    private static void confirmAndEmergency() {
        CodeOnlyMath.Confirm c = new CodeOnlyMath.Confirm();
        int gap = 100, noCard = RoeMath.ID_NO_CARD;
        int r1 = RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, noCard, false, c, 1000, gap);
        int r2 = RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, noCard, false, c, 1000, gap);   // the same answer again
        int r3 = RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, noCard, false, c, 1099, gap);
        int r4 = RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, noCard, false, c, 1120, gap);
        check(RoeMath.idReason(r1) == RoeMath.ID_CHECK && RoeMath.idReason(r2) == RoeMath.ID_CHECK && RoeMath.idReason(r3) == RoeMath.ID_CHECK
                && c.misses == 2 && RoeMath.idClass(r4) == RoeMath.FOREIGN,
                "ad code-only (item 5): a target only after two misses in a row at least confirmSeconds apart - the same interrogation twice is one miss");
        RoeMath.classifyByCode(true, false, false, true, 02345, -1, false, RoeMath.ID_KEY, false, c, 1200, gap);
        boolean resetByMatch = c.misses == 0;
        RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, noCard, false, c, 1300, gap);
        RoeMath.classifyByCode(false, false, false, true, 01111, -1, false, noCard, false, c, 1500, gap);           // not asked: kept
        boolean keptNotAsked = c.misses == 1;
        int r5 = RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, noCard, false, c, 1250, gap);   // a clock gone back
        check(resetByMatch && keptNotAsked && c.misses == 1 && RoeMath.idReason(r5) == RoeMath.ID_CHECK,
                "ad code-only (item 5): a match starts the count over; «not asked yet» keeps it; a clock gone back starts over");
        // 7700: the grace in all, over the episodes (7700 - 7000 - 7700 does not reset it), decisions; 0 - never a target
        int grace = 180 * 20;
        NoRadioMath.Entry e = new NoRadioMath.Entry(0);
        for (long t = 100; t <= 2000; t += 100) {
            NoRadioMath.seen(e, t);
        }
        NoRadioMath.changed(e, 2000);                               // 7000 for a while
        NoRadioMath.seen(e, 2200);                                  // 7700 again
        check(CodeOnlyMath.emergencyState(e, 2200, grace) == NoRadioMath.S_PENDING && CodeOnlyMath.emergencyLeft(e, 2200, grace) == 80
                && CodeOnlyMath.emergencyState(e, 3800, grace) == NoRadioMath.S_DEFAULT && CodeOnlyMath.emergencyState(e, 999999, 0) == NoRadioMath.S_PENDING
                && CodeOnlyMath.emergencyLeft(e, 0, 0) == -1,
                "ad code-only 7700 (item 7): 180 s in all over the episodes (100 s used, 80 left after 7000 and back), then a miss; 0 - never a target");
        NoRadioMath.decide(e, NoRadioMath.D_FRIEND, 3800, 600);
        int fr = CodeOnlyMath.emergencyState(e, 3900, grace), frEnd = CodeOnlyMath.emergencyState(e, 4400, grace);
        NoRadioMath.decide(e, NoRadioMath.D_CHECK, 4400, 600);
        int ch = CodeOnlyMath.emergencyState(e, 9000, grace);
        NoRadioMath.decide(e, NoRadioMath.D_HOSTILE, 9000, 600);
        check(fr == NoRadioMath.S_FRIEND && frEnd == NoRadioMath.S_DEFAULT && ch == NoRadioMath.S_CHECK && NoRadioMath.holdFire(ch)
                && CodeOnlyMath.emergencyState(e, 9001, grace) == NoRadioMath.S_HOSTILE,
                "ad code-only 7700 (item 7): the post's «Свой» (its time), «Проверить» (no fire), «Враждебный» (no grace) as for 7600");
        // one owner's posts share the grace (item 31): two posts seeing it are one episode, not two
        AirDefenseDataSVA d = new AirDefenseDataSVA("test");
        for (long t = 0; t <= 1000; t += 100) {
            d.emergency("Alice", "veh-1", true, t);                 // post 1
            d.emergency("ALICE", "veh-1", true, t + 5);             // post 2 of the same owner
        }
        NoRadioMath.Entry a = d.emergencyEntry("alice", "veh-1");
        d.emergency("Bob", "veh-1", true, 1000);
        check(a != null && NoRadioMath.elapsed(a, 1005) == 1005 && d.emergencyEntry("Bob", "veh-1") != a && d.emergencyEntry("Bob", "veh-1") != null
                && d.emergency("Carol", "veh-1", false, 1000) == null && d.ownerKey("Carol", false) == null,
                "ad code-only 7700 (item 31): the grace per vehicle and air defence owner - his posts together count once, another owner has his own");
        d.emergency("Alice", "veh-1", false, 1100);
        d.prune(1100 + CodeOnlyMath.EMERGENCY_MEMORY / 2, 12000);
        boolean kept = d.emergencyEntry("Alice", "veh-1") != null;
        d.prune(1100 + CodeOnlyMath.EMERGENCY_MEMORY + 200, 12000);
        check(kept && d.emergencyEntry("Alice", "veh-1") == null, "ad code-only 7700: the memory outlives the 7600 one (an hour), then goes in prune()");
    }

    // ---------------------------------------------------------------- guessing (item 11)

    private static void probes() {
        long now = 10000;
        check(CodeOnlyMath.probeCounts(01111, now - 60, now, 02222, false) && !CodeOnlyMath.probeCounts(01111, now - 59, now, 02222, false)
                && CodeOnlyMath.probeCounts(01111, -1, now, 02222, false) && !CodeOnlyMath.probeCounts(XpdrMath.VFR, -1, now, 02222, false)
                && !CodeOnlyMath.probeCounts(XpdrMath.EMERGENCY, -1, now, 02222, false) && !CodeOnlyMath.probeCounts(0, -1, now, 02222, false)
                && !CodeOnlyMath.probeCounts(02222, -1, now, 02222, false) && !CodeOnlyMath.probeCounts(04101, -1, now, 02222, true),
                "ad code-only guessing (item 11): a code counts after standing 3 s; never 7000 / special / the last period's / a tower's 41xx");
        check(CodeOnlyMath.probeGuard(CodeOnlyMath.DEFAULT_OPTS) && CodeOnlyMath.probeGuard(CodeOnlyMath.OPT_OWN_CODES) && !CodeOnlyMath.probeGuard(CodeOnlyMath.OPT_AIRPORT)
                && !CodeOnlyMath.probeGuard(0) && CodeOnlyMath.manualCounts(CodeOnlyMath.DEFAULT_OPTS, true) && !CodeOnlyMath.manualCounts(CodeOnlyMath.DEFAULT_OPTS, false)
                && !CodeOnlyMath.manualCounts(0, true) && CodeOnlyMath.opts(0xFF) == CodeOnlyMath.OPT_ALL,
                "ad code-only options (item 3; decision 1): «Ручной набор кода» on by default - so the guessing guard is on by default; the server may forbid it");
        CodeOnlyMath.Probe p = new CodeOnlyMath.Probe();
        long block = 5 * 1200;
        boolean b1 = CodeOnlyMath.wrong(p, 01111, now, 4, block), b2 = CodeOnlyMath.wrong(p, 01112, now + 20, 4, block);
        boolean b3 = CodeOnlyMath.wrong(p, 01111, now + 40, 4, block), b4 = CodeOnlyMath.wrong(p, 01113, now + 60, 4, block);
        boolean before = CodeOnlyMath.blocked(p, now + 60);
        boolean b5 = CodeOnlyMath.wrong(p, 01114, now + 80, 4, block);
        check(!b1 && !b2 && !b3 && !b4 && !before && b5 && CodeOnlyMath.blocked(p, now + 80) && CodeOnlyMath.blocked(p, now + 80 + block - 1)
                && !CodeOnlyMath.blocked(p, now + 80 + block) && !CodeOnlyMath.wrong(p, 01115, now + 100, 4, block),
                "ad code-only guessing: 4 different wrong codes (the same one twice is one) - ПОДБОР for 5 min; nothing counted while blocked");
        CodeOnlyMath.Probe q = new CodeOnlyMath.Probe();
        boolean slow = false;
        for (int i = 0; i < 8; i++) {
            slow |= CodeOnlyMath.wrong(q, 01200 + i, now + i * 1300L, 4, block);    // one every 65 s: never 4 within 2 min
        }
        check(!slow && q.codes.size() <= 2, "ad code-only guessing: the codes count within 2 minutes only - a slow one never gets blocked");
        CodeOnlyMath.unblock(p);
        check(!CodeOnlyMath.blocked(p, now + 100) && p.codes.isEmpty() && CodeOnlyMath.forgotten(p, now + 100) && !CodeOnlyMath.forgotten(q, now + 8 * 1300L)
                && CodeOnlyMath.forgotten(q, now + 20 * 1300L), "ad code-only guessing: the operator lifts the block («Свой» / «Снять»); old guesses are forgotten");
        // the data: per owner, by the aircraft and by the pilot (he is blocked in another aircraft too)
        AirDefenseDataSVA d = new AirDefenseDataSVA("test");
        boolean got = false;
        for (int i = 0; i < 4; i++) {
            got = d.probeWrong("Alice", "veh-1", "Mallory", 01300 + i, now + i * 20, 4, block);
        }
        check(got && d.probeBlocked("Alice", "veh-1", "Mallory", now + 100) && d.probeBlocked("alice", "veh-2", "Mallory", now + 100)
                && d.probeBlocked("Alice", "veh-1", "Bob", now + 100) && !d.probeBlocked("Alice", "veh-2", "Bob", now + 100)
                && !d.probeBlocked("Carol", "veh-1", "Mallory", now + 100),
                "ad code-only guessing (item 11): the block by the aircraft and by the pilot, shared by one owner's posts, not another owner's");
        d.probeUnblock("Alice", "veh-1", "Mallory");
        boolean lifted = !d.probeBlocked("Alice", "veh-2", "Mallory", now + 100);
        NBTTagCompound t = new NBTTagCompound();
        CodeOnlyMath.Probe r = new CodeOnlyMath.Probe();
        CodeOnlyMath.wrong(r, 01400, now, 4, block);
        CodeOnlyMath.wrong(r, 01401, now + 5, 4, block);
        r.blockedUntil = now + 77;
        r.write(t);
        CodeOnlyMath.Probe r2 = new CodeOnlyMath.Probe();
        r2.read(t);
        check(lifted && r2.codes.equals(r.codes) && r2.at.equals(r.at) && r2.blockedUntil == now + 77, "ad code-only guessing: lifted; the memory through NBT");
    }

    // ---------------------------------------------------------------- arming, the launcher (items 13, 16, 32)

    private static void armingAndLauncher() {
        long at = CodeOnlyMath.armAt(1000, 60);
        check(at == 2200 && CodeOnlyMath.state(true, at, 1000, true, true) == CodeOnlyMath.CO_ARMING && CodeOnlyMath.armLeft(true, at, 1000) == 60
                && CodeOnlyMath.armLeft(true, at, 2181) == 1 && CodeOnlyMath.state(true, at, 2200, true, true) == CodeOnlyMath.CO_ON
                && CodeOnlyMath.active(true, at, 2200, true, true) && !CodeOnlyMath.active(true, at, 2199, true, true)
                && CodeOnlyMath.state(false, at, 9999, true, true) == CodeOnlyMath.CO_OFF && CodeOnlyMath.armLeft(false, at, 1000) == 0
                && CodeOnlyMath.state(true, at, 9999, false, true) == CodeOnlyMath.CO_BANNED && CodeOnlyMath.state(true, at, 9999, true, false) == CodeOnlyMath.CO_NO_KEY
                && CodeOnlyMath.state(true, -1, 9999, true, true) == CodeOnlyMath.CO_ARMING,
                "ad code-only arming (item 16): on after 60 s (the usual classification meanwhile), off at once; the server's codeOnly=false / no day key - not on");
        check(CodeOnlyMath.mayEnable(true, true, true) && !CodeOnlyMath.mayEnable(false, true, true) && !CodeOnlyMath.mayEnable(true, false, true)
                && !CodeOnlyMath.mayEnable(true, true, false), "ad code-only (item 16): never switched on without the server's leave and the day key");
        check(CodeOnlyMath.minWarning(10, true, 20) == 20 && CodeOnlyMath.minWarning(30, true, 20) == 30 && CodeOnlyMath.minWarning(10, false, 20) == 10
                && RoeMath.waitTicks(2, CodeOnlyMath.minWarning(10, true, 20)) == 400,
                "ad code-only (item 13): the wait to fire at least 20 s in the code-only mode, for everybody alike");
        int F = RoeMath.FOREIGN_ALL;
        check(CodeOnlyMath.readiness(true, true, true, true, true, true, F) == CodeOnlyMath.R_READY
                && CodeOnlyMath.readiness(false, true, true, true, true, true, F) == CodeOnlyMath.R_DISABLED
                && CodeOnlyMath.readiness(true, false, true, true, true, true, F) == CodeOnlyMath.R_WARN_ONLY
                && CodeOnlyMath.readiness(true, true, false, true, true, true, F) == CodeOnlyMath.R_PVP
                && CodeOnlyMath.readiness(true, true, true, false, true, true, F) == CodeOnlyMath.R_NO_KP
                && CodeOnlyMath.readiness(true, true, true, true, false, true, F) == CodeOnlyMath.R_KP_OFF
                && CodeOnlyMath.readiness(true, true, true, true, true, false, F) == CodeOnlyMath.R_NOT_CODE_ONLY
                && CodeOnlyMath.readiness(true, true, true, true, true, true, RoeMath.HOLD) == CodeOnlyMath.R_HOLD
                && CodeOnlyMath.readiness(true, true, true, true, true, true, RoeMath.VIOLATORS) == CodeOnlyMath.R_VIOLATORS
                && CodeOnlyMath.readiness(true, true, true, true, true, true, RoeMath.KP_MARKS) == CodeOnlyMath.R_READY,
                "ad code-only (item 32): «Огонь по коду: ГОТОВ» or why not - server off / warnings only / PvP / no post / post unpowered / not code-only / the rule");
    }

    // ---------------------------------------------------------------- the transponder (items 20, 22, 30)

    private static void transponder() {
        XpdrMath.Xpdr x = new XpdrMath.Xpdr();
        x.setCode(02345, 100);
        x.setCode(02345, 200);
        boolean since = x.codeSince == 100;
        x.takeCard(03456, "Alice", 2, 10, 13, 11, 300);
        boolean took = x.code == 03456 && x.codeSince == 300 && x.fromCard() && x.keyLamp(11) && !x.keyLamp(12) && "Alice".equals(x.cardOwner) && x.cardGen == 2
                && x.cardFirst == 10 && x.cardUntil == 13;
        NBTTagCompound t = new NBTTagCompound();
        x.write(t);
        XpdrMath.Xpdr y = new XpdrMath.Xpdr();
        y.read(t);
        check(since && took && "Alice".equals(y.cardOwner) && y.cardGen == 2 && y.cardFirst == 10 && y.cardUntil == 13 && y.cardDay == 11 && y.code == 03456,
                "ad code-only xpdr: codeSince only when the code changes; the card's code, whose key, generation and periods - kept through NBT");
        XpdrMath.Xpdr z = new XpdrMath.Xpdr();
        z.read(new NBTTagCompound());
        check("".equals(z.cardOwner) && z.cardFirst == -1 && z.cardUntil == -1 && z.codeSince == -1, "ad code-only xpdr: an older save - no card fields");
        x.dropCard(true, 400);
        boolean reset = x.code == XpdrMath.VFR && !x.fromCard() && "".equals(x.cardOwner) && x.cardDay == -1;
        XpdrMath.Xpdr m = new XpdrMath.Xpdr();
        m.takeCard(03456, "Alice", 2, 10, 13, 11, 300);
        m.setCode(XpdrMath.EMERGENCY, 310);                         // the pilot squawks 7700 (the panel would turn the key off; here not)
        m.dropCard(true, 400);
        XpdrMath.Xpdr h = new XpdrMath.Xpdr();
        h.dayKey = false;
        h.setCode(01111, 100);
        h.dropCard(true, 400);
        XpdrMath.Xpdr k = new XpdrMath.Xpdr();
        k.takeCard(03456, "Alice", 2, 10, 13, 11, 300);
        k.dropCard(false, 400);
        check(reset && m.code == XpdrMath.EMERGENCY && h.code == 01111 && k.code == 03456 && !k.fromCard(),
                "ad code-only xpdr (items 20, 30): the card gone - its code back to 7000 (a hijacker loses the key); 7700 and a code dialled by hand stay");
        XpdrMath.Xpdr p = new XpdrMath.Xpdr();
        p.takeCard(03456, "Alice", 2, 10, 13, 11, 300);
        XpdrMath.applyPanel(p, XpdrMath.CH_CODE, XpdrMath.C, 01234, true, 500);
        check(p.code == 01234 && !p.dayKey && "".equals(p.cardOwner) && p.cardDay == -1 && p.codeSince == 500,
                "ad code-only xpdr: a code dialled by hand - the key off, the card forgotten (only «Ручной набор кода» counts it)");
        XpdrMath.Xpdr c = new XpdrMath.Xpdr();
        boolean dark = !c.idCaution(100);
        c.caution(100);
        check(dark && c.idCaution(100) && c.idCaution(100 + XpdrMath.ID_CAUTION_TICKS - 1) && !c.idCaution(100 + XpdrMath.ID_CAUTION_TICKS) && !c.idCaution(50),
                "ad code-only xpdr (item 22): the ОПОЗН lamp lit by a code-only interrogation without a match for 15 s; a clock gone back puts it out");
        int all = 0, flying = 0;
        for (VehicleType v : VehicleType.values()) {
            all += XpdrMath.fitted(v, true) ? 1 : 0;
            flying += XpdrMath.fitted(v, false) == v.movement.flies ? 1 : 0;   // fitted(t) itself follows the server (SelfTestAdXpdrAllSVA)
        }
        check(all == VehicleType.values().length && flying == VehicleType.values().length && XpdrMath.fitted(VehicleType.BUGGY, true)
                && XpdrMath.fitted(VehicleType.HOVER, true) && !XpdrMath.fitted(VehicleType.BUGGY, false) && !XpdrMath.fitted(null, true),
                "ad code-only (decision 3): with allTransponders every vehicle has a transponder (the buggy, the hoverbike...), off - only aircraft");
    }

    // ---------------------------------------------------------------- the trust data (items 7, 11, 16, 24, 36)

    private static void data() {
        AirDefenseDataSVA d = new AirDefenseDataSVA("test");
        d.setSecret("Alice", "aliceKey");
        String pep = d.pepper();
        boolean hex = pep.length() == 32;
        for (int i = 0; i < pep.length(); i++) {
            hex &= Character.digit(pep.charAt(i), 16) >= 0;
        }
        int c1 = d.keyCode("Alice", 40), peppered = 0;
        int[] before = new int[20];
        for (int k = 0; k < 20; k++) {
            before[k] = d.keyCode("Alice", 40 + k);
            peppered += before[k] != XpdrMath.dayCode("aliceKey", 40 + k) ? 1 : 0;
        }
        check(hex && pep.equals(d.pepper()) && !pep.equals(new AirDefenseDataSVA("other").pepper()) && c1 == XpdrMath.dayCode("aliceKey", pep, 1, 40)
                && peppered >= 18 && d.keyCode("Nobody", 40) == -1 && d.gen("Alice") == 1,
                "ad code-only data (item 36): a 128-bit pepper made once, kept, mixed into every key code; no secret - no code; generation 1 at first");
        int g = d.rekey("alice"), changed = 0;
        for (int k = 0; k < 20; k++) {
            changed += d.keyCode("Alice", 40 + k) != before[k] ? 1 : 0;
        }
        check(g == 2 && d.gen("ALICE") == 2 && changed >= 18, "ad code-only data (item 24): «Сменить ключ» - the generation +1, the codes change");
        AirDefenseDataSVA.KpInfo i = d.info("0:1:2:3", true);
        i.owner = "Alice";
        i.codeOnly = true;
        i.armAt = 5000;
        i.coOpts = CodeOnlyMath.OPT_OWN_CODES | CodeOnlyMath.OPT_AIRPORT;
        i.cardPeriods = 7;
        d.emergency("Alice", "veh-9", true, 100);
        d.probeWrong("Alice", "veh-9", "Mallory", 01111, 100, 4, 6000);
        NBTTagCompound t = new NBTTagCompound();
        d.writeToNBT(t);
        AirDefenseDataSVA e = new AirDefenseDataSVA("test");
        e.readFromNBT(t);
        AirDefenseDataSVA.KpInfo j = e.info("0:1:2:3", false);
        CodeOnlyMath.Probe pr = e.probe("Alice", AirDefenseDataSVA.probeKey("veh-9", null), false);
        check(j != null && j.codeOnly && j.armAt == 5000 && j.coOpts == (CodeOnlyMath.OPT_OWN_CODES | CodeOnlyMath.OPT_AIRPORT) && j.cardPeriods == 7
                && e.gen("Alice") == 2 && pep.equals(e.pepper()) && e.keyCode("Alice", 40) == d.keyCode("Alice", 40)
                && e.emergencyEntry("Alice", "veh-9") != null && pr != null && pr.codes.size() == 1
                && e.probe("Alice", AirDefenseDataSVA.probeKey(null, "MALLORY"), false) != null,
                "ad code-only data NBT: the post's switch, arming, options, periods; the owner's generation, 7700 and guesses; the pepper - all back as saved");
        check(j != null && j.codeOnlyActive(5000, true, true) && !j.codeOnlyActive(4999, true, true) && !j.codeOnlyActive(6000, false, true)
                && !j.codeOnlyActive(6000, true, false), "ad code-only data (items 15, 16): a launcher reads the post's mode from its data - armed, allowed, with the day key");
        // an older save's post: none of the keys - the usual mode, the default options and periods
        NBTTagCompound old = new NBTTagCompound();
        old.setString("Own", "Alice");
        old.setBoolean("DayKey", true);
        NBTTagCompound wrap = new NBTTagCompound();
        net.minecraft.nbt.NBTTagList l = new net.minecraft.nbt.NBTTagList();
        old.setString("K", "0:9:9:9");
        l.appendTag(old);
        wrap.setTag("KpInfo", l);
        AirDefenseDataSVA o = new AirDefenseDataSVA("test");
        o.readFromNBT(wrap);
        AirDefenseDataSVA.KpInfo k = o.info("0:9:9:9", false);
        check(k != null && !k.codeOnly && k.armAt == -1 && k.coOpts == CodeOnlyMath.DEFAULT_OPTS && k.cardPeriods == CodeOnlyMath.DEFAULT_CARD_PERIODS
                && !k.codeOnlyActive(99999, true, true) && o.gen("Alice") == 1,
                "ad code-only data: an older save's post - the usual mode, «Ручной набор кода» on, cards for 3 periods; key generation 1");
        // prune: the guesses go after their window, a block after its time
        d.prune(100 + CodeOnlyMath.PROBE_WINDOW_TICKS + 1, 12000);
        check(d.probe("Alice", AirDefenseDataSVA.probeKey("veh-9", null), false) == null && d.emergencyEntry("Alice", "veh-9") != null,
                "ad code-only data: prune() forgets the old guesses (the 7700 memory still kept)");
    }

    // ---------------------------------------------------------------- the config, the lang

    private static void configAndLang() throws Exception {
        check(ConfigSVA.adCodeOnly && ConfigSVA.adCodeOnlyArmSeconds == 60 && ConfigSVA.adCodeOnlyConfirmSeconds == 5 && ConfigSVA.adCodeOnlyMinWarningSeconds == 20
                && ConfigSVA.adCodeOnlyEmergencySeconds == 180 && ConfigSVA.adCodeOnlyOverlapSeconds == 60 && ConfigSVA.adCodeCardMaxPeriods == 7
                && ConfigSVA.adCodeOnlyProbeCodes == 4 && ConfigSVA.adCodeOnlyProbeMinutes == 5 && "total".equals(ConfigSVA.adKeyClock),
                "ad code-only config (item 28): codeOnly, arm 60 s, confirm 5 s, warning 20 s, 7700 180 s, overlap 60 s, cards 7 periods, guessing 4 / 5 min, keyClock total");
        check(ConfigSVA.adCodeOnlyManual && ConfigSVA.adAllTransponders && AirDefenseDataSVA.keyClock() == XpdrMath.CLOCK_TOTAL,
                "ad code-only config (decisions 1, 3): a code dialled by hand may count; every vehicle has a transponder");
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        String[] keys = {"sva.tooltip.codeCard.key", "sva.tooltip.codeCard.left", "sva.tooltip.codeCard.expired", "sva.tooltip.codeCard.pilot",
            "sva.tooltip.codeCard.legacy", "sva.tooltip.codeCard.details", "sva.tooltip.howto.codeCard", "sva.ad.msg.printedKey", "sva.ad.log.printedN"};
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (!ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "ad code-only lang: the card's tooltip and the printing lines in ru and en" + miss);
    }
}
