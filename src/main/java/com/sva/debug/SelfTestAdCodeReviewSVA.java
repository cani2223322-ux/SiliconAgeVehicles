package com.sva.debug;

import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Code-only wave, the review of 2026-10-27 (batch 1) - pure logic and data, the dedicated server runs it:
 * <ul>
 * <li>the guessing guard bypassed by quick codes: a code dialled by hand (the key's, an own one) matches only once it stood
 * 3 s at the interrogation, and before that it is classed exactly as a wrong code would be (no oracle); the panel's codes at
 * most once a second (the latest waits, nothing is lost); a guess judged at the interrogation's tick, not the post's round;</li>
 * <li>«Ключ дня» switched off over the card's code puts 7000 back (a hijacker keeps no key);</li>
 * <li>7700 / 7600 in their grace keep the misses and pause a launcher's engagement (resumed where it stopped); the 7700 memory
 * a day;</li>
 * <li>cards of two owners: the key that matters here wins, the boarding notes (no card, the key off, another owner's card);</li>
 * <li>the arming dropped with the post's day key or the server's switch (over a restart) and done anew.</li>
 * </ul>
 */
final class SelfTestAdCodeReviewSVA {

    private SelfTestAdCodeReviewSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        handDialled();
        panelRate();
        keyOff();
        grace();
        cards();
        arming();
    }

    // ---------------------------------------------------------------- finding 1: a hand-dialled match must stand 3 s

    private static void handDialled() {
        int key = 02563, ok = CodeOnlyMath.CARD_OK, none = CodeOnlyMath.CARD_NONE;
        check(CodeOnlyMath.stood(-1, 50) && !CodeOnlyMath.stood(100, 159) && CodeOnlyMath.stood(100, 160) && !CodeOnlyMath.stood(100, 90),
                "ad code review (finding 1): a code stood 3 s at the interrogation (-1: long ago); not before, nor heard before it was set");
        int fresh = CodeOnlyMath.identify(true, key, key, -1, false, none, false, true, false, false, false);
        int stood = CodeOnlyMath.identify(true, key, key, -1, false, none, false, true, true, false, false);
        int card = CodeOnlyMath.identify(true, key, key, -1, false, ok, true, false, false, false, false);
        int freshOff = CodeOnlyMath.identify(true, key, key, -1, false, ok, false, true, false, false, false);
        int wrongOff = CodeOnlyMath.identify(true, 01234, key, -1, false, ok, false, true, false, false, false);
        int wrong = CodeOnlyMath.identify(true, 01234, key, -1, false, none, false, true, false, false, false);
        check(fresh == wrong && fresh == RoeMath.ID_NO_CARD && stood == RoeMath.ID_KEY && card == RoeMath.ID_KEY && freshOff == wrongOff
                && freshOff == RoeMath.ID_WRONG,
                "ad code review (finding 1): the key's code dialled by hand - no match until it stood 3 s, the same reason as a wrong code (no card / a good "
                        + "card with the key off); stood - a match; the card's own code at once");
        int ownFresh = CodeOnlyMath.identify(true, 01357, key, -1, false, none, false, true, false, true, false);
        int ownStood = CodeOnlyMath.identify(true, 01357, key, -1, false, none, false, true, true, true, false);
        check(ownFresh == RoeMath.ID_NO_CARD && ownStood == RoeMath.ID_OWN_CODE,
                "ad code review (finding 1): an own code (option 3a) dialled by hand counts only once it stood 3 s too");
        // the same Confirm story for the key's fresh code and a wrong one: one miss, then (a survey round later) a target - nothing
        // tells the guesser which of his quick codes was the key
        CodeOnlyMath.Confirm a = new CodeOnlyMath.Confirm(), b = new CodeOnlyMath.Confirm();
        int a1 = RoeMath.classifyByCode(true, false, false, true, 07000, -1, false, RoeMath.ID_NO_CARD, false, a, 1000, 100);
        int b1 = RoeMath.classifyByCode(true, false, false, true, 07000, -1, false, RoeMath.ID_NO_CARD, false, b, 1000, 100);
        int a2 = RoeMath.classifyByCode(true, false, false, true, key, -1, false, fresh, false, a, 1120, 100);
        int b2 = RoeMath.classifyByCode(true, false, false, true, 01234, -1, false, wrong, false, b, 1120, 100);
        check(a1 == b1 && a2 == b2 && RoeMath.idClass(a2) == RoeMath.FOREIGN && a.misses == b.misses,
                "ad code review (finding 1): the key's code caught fresh and a wrong code give the same verdict and the same miss count (no oracle)");
        // finding 2: the 3 s are the interrogation's - a code set at 100, heard at 130, weighed by the post's round at 220
        check(!CodeOnlyMath.probeCounts(01111, 100, 130, 02222, false) && CodeOnlyMath.probeCounts(01111, 100, 220, 02222, false)
                && CodeOnlyMath.probeCounts(01111, 100, 160, 02222, false),
                "ad code review (finding 2): a code caught mid-dial is no guess at the post's later round (judged at the interrogation's tick)");
    }

    // ---------------------------------------------------------------- finding 1: the panel's codes at most once a second

    private static void panelRate() {
        XpdrMath.Xpdr x = new XpdrMath.Xpdr();
        x.dayKey = false;
        XpdrMath.applyPanel(x, XpdrMath.CH_CODE, XpdrMath.C, 01111, false, 100);
        boolean first = x.code == 01111 && x.codeSince == 100 && x.heldCode < 0;
        XpdrMath.applyPanel(x, XpdrMath.CH_CODE, XpdrMath.C, 01112, false, 105);
        XpdrMath.applyPanel(x, XpdrMath.CH_CODE, XpdrMath.C, 01113, false, 110);
        XpdrMath.applyPanel(x, XpdrMath.CH_MODE, XpdrMath.S, 01113, false, 112);
        boolean waits = x.code == 01111 && x.heldCode == 01113 && x.mode == XpdrMath.S;
        boolean early = XpdrMath.releaseHeld(x, 119);
        boolean on = XpdrMath.releaseHeld(x, 120);
        check(first && waits && !early && on && x.code == 01113 && x.codeSince == 120 && x.heldCode < 0 && !XpdrMath.releaseHeld(x, 500),
                "ad code review (finding 1): the panel's code at most once a second - a quicker one waits (the latest wins, the mode at once), "
                        + "then goes on the air by itself");
        XpdrMath.applyPanel(x, XpdrMath.CH_CODE, XpdrMath.C, 01114, false, 125);
        XpdrMath.applyPanel(x, XpdrMath.CH_CODE, XpdrMath.C, 01113, false, 126);            // stepped back to the code on the air
        boolean back = x.heldCode < 0 && !XpdrMath.releaseHeld(x, 300) && x.code == 01113;
        XpdrMath.applyPanel(x, XpdrMath.CH_CODE, XpdrMath.C, 01115, false, 400);
        XpdrMath.applyPanel(x, XpdrMath.CH_CODE, XpdrMath.C, 01116, false, 405);
        XpdrMath.applyPanel(x, XpdrMath.CH_KEY, XpdrMath.C, 01116, true, 406);              // «Ключ дня» on after it: the card wins
        boolean key = x.heldCode < 0 && x.dayKey && !XpdrMath.releaseHeld(x, 600) && x.code == 01115;
        XpdrMath.Xpdr g = new XpdrMath.Xpdr();
        g.handAt = 1000;
        XpdrMath.applyPanel(g, XpdrMath.CH_CODE, XpdrMath.C, 01234, false, 900);           // a clock gone back: no wait
        check(back && key && g.code == 01234 && g.heldCode < 0,
                "ad code review (finding 1): back to the code on the air - nothing waits; «Ключ дня» pressed on - the waiting code dropped; a clock gone back - no wait");
    }

    // ---------------------------------------------------------------- finding 3: «Ключ дня» off over the card's code

    private static void keyOff() {
        XpdrMath.Xpdr x = new XpdrMath.Xpdr();
        x.takeCard(02345, "Alice", 2, 10, 13, 11, 300);
        XpdrMath.applyPanel(x, XpdrMath.CH_KEY, XpdrMath.C, 02345, false, 310);
        boolean reset = x.code == XpdrMath.VFR && !x.dayKey && !x.fromCard() && "".equals(x.cardOwner) && x.codeSince == 310;
        XpdrMath.Xpdr old = new XpdrMath.Xpdr();
        old.takeCard(02345, "Alice", 2, 10, 13, 11, 300);
        XpdrMath.applyPanel(old, XpdrMath.CH_ALL, XpdrMath.C, 02345, false, 310);          // an old client: the same code, the key off
        XpdrMath.Xpdr dial = new XpdrMath.Xpdr();
        dial.takeCard(02345, "Alice", 2, 10, 13, 11, 300);
        XpdrMath.applyPanel(dial, XpdrMath.CH_CODE, XpdrMath.C, XpdrMath.EMERGENCY, false, 310);
        XpdrMath.Xpdr tower = new XpdrMath.Xpdr();
        tower.setCode(04101, 300);
        tower.dayKey = false;                                       // as AtcServerSVA.setSquawk leaves it
        XpdrMath.applyPanel(tower, XpdrMath.CH_KEY, XpdrMath.C, XpdrMath.VFR, false, 310);
        XpdrMath.Xpdr em = new XpdrMath.Xpdr();
        em.takeCard(02345, "Alice", 2, 10, 13, 11, 300);
        em.setCode(XpdrMath.EMERGENCY, 305);
        XpdrMath.applyPanel(em, XpdrMath.CH_KEY, XpdrMath.C, XpdrMath.EMERGENCY, false, 310);
        check(reset && old.code == XpdrMath.VFR && dial.code == XpdrMath.EMERGENCY && !dial.dayKey && tower.code == 04101 && em.code == XpdrMath.EMERGENCY,
                "ad code review (finding 3): «Ключ дня» off over the card's code - 7000 (an old client's packet too); a code dialled with it, a tower's code "
                        + "and 7700 stay");
    }

    // ---------------------------------------------------------------- finding 4: the 7700 / 7600 grace holds, it clears nothing

    private static void grace() {
        int gap = 100;
        CodeOnlyMath.Confirm c = new CodeOnlyMath.Confirm();
        RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, RoeMath.ID_NO_CARD, true, c, 1000, gap);
        int t = RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, RoeMath.ID_NO_CARD, true, c, 1120, gap);
        int em = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, NoRadioMath.S_PENDING, false, RoeMath.ID_NO_CARD, true, c, 1240, gap);
        boolean kept = c.misses == 2;
        int back = RoeMath.classifyByCode(true, false, false, true, 01111, -1, false, RoeMath.ID_NO_CARD, true, c, 1250, gap);
        int nr = RoeMath.classifyByCode(true, false, false, true, XpdrMath.NO_RADIO, -1, true, RoeMath.ID_NO_CARD, true, c, 1260, gap);
        boolean keptNr = c.misses == 2;
        int chk = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, NoRadioMath.S_CHECK, false, RoeMath.ID_NO_CARD, true, c, 1270, gap);
        boolean keptChk = c.misses == 2;
        int fr = RoeMath.classifyByCode(true, false, false, true, XpdrMath.EMERGENCY, NoRadioMath.S_FRIEND, false, RoeMath.ID_NO_CARD, true, c, 1280, gap);
        check(RoeMath.idClass(t) == RoeMath.VIOLATOR && RoeMath.idClass(em) == RoeMath.EMERGENCY && kept && RoeMath.idClass(back) == RoeMath.VIOLATOR
                && RoeMath.idClass(nr) == RoeMath.NO_RADIO && keptNr && RoeMath.idClass(chk) == RoeMath.EMERGENCY && keptChk
                && RoeMath.idClass(fr) == RoeMath.FRIEND && c.misses == 0,
                "ad code review (finding 4): 7700 / 7600 in their grace (and «Проверить») hold the fire but keep the two misses - back on a wrong code it is a "
                        + "target at once, not «ПРОВ 1/2»; the operator's «Свой» starts the count over");
        check(RoeMath.graceHold(true, RoeMath.EMERGENCY) && RoeMath.graceHold(true, RoeMath.NO_RADIO) && !RoeMath.graceHold(false, RoeMath.EMERGENCY)
                && !RoeMath.graceHold(true, RoeMath.FRIEND) && !RoeMath.graceHold(true, RoeMath.VIOLATOR),
                "ad code review (finding 4): a launcher pauses an engagement only for a code-only track held by the grace");
        int wait = 400;
        long[] st = {1000, RoeMath.ST_LOCK, 0, 1};
        long[] p = RoeMath.pause(st, 1300);
        long[] r = RoeMath.resume(p, 1700);
        check(p.length == 5 && r.length == 4 && r[0] == 1400 && r[1] == RoeMath.ST_LOCK && r[3] == 1
                && RoeMath.stage(1700 - r[0], true, false, wait) == RoeMath.stage(1300 - st[0], true, false, wait)
                && RoeMath.resume(RoeMath.pause(st, 1300), 1200)[0] == 1000,
                "ad code review (finding 4): a paused engagement goes on from its stage, the time on 7700 not counted as warning time (a clock gone back: none)");
        check(CodeOnlyMath.EMERGENCY_MEMORY == 24L * 3600 * 20,
                "ad code review (finding 4): the 7700 grace is remembered a day (it was an hour - an hour away bought a fresh 180 s)");
    }

    // ---------------------------------------------------------------- finding 5: cards of two owners

    private static void cards() {
        check(CodeOnlyMath.cardRelevance(true, false) == 2 && CodeOnlyMath.cardRelevance(true, true) == 2 && CodeOnlyMath.cardRelevance(false, true) == 1
                && CodeOnlyMath.cardRelevance(false, false) == 0,
                "ad code review (finding 5): a card matters most for the nearest code-only post's owner, then for a post whose list has the pilot");
        // review batch 2: no «the longer one» any more - between equals the first met (the pilot's inventory order) stays
        check(CodeOnlyMath.betterCard(2, 3, 0, 3) && !CodeOnlyMath.betterCard(0, 3, 2, 3) && CodeOnlyMath.betterCard(2, 2, 1, 3)
                && CodeOnlyMath.betterCard(0, 3, 0, 2) && !CodeOnlyMath.betterCard(0, 3, 0, 3) && !CodeOnlyMath.betterCard(0, 2, 0, 3),
                "ad code review (finding 5): the owner's own card beats the ally's (whatever its term) at his post; then the rank; equals - the first stays");
        AirDefenseDataSVA d = new AirDefenseDataSVA("test");
        AirDefenseDataSVA.KpInfo bob = d.info("0:100:64:100", true), alice = d.info("0:1000:64:1000", true), off = d.info("0:120:64:95", true);
        AirDefenseDataSVA.KpInfo nether = d.info("-1:110:64:100", true), twr = d.info(AirDefenseDataSVA.TOWER_PREFIX + "0:118:64:92", true);
        bob.owner = "Bob";
        bob.codeOnly = true;
        alice.owner = "Alice";
        alice.codeOnly = true;
        off.owner = "Carol";                                        // the usual mode: no say
        nether.owner = "Dave";
        nether.dim = -1;
        nether.codeOnly = true;
        twr.owner = "Eve";
        twr.codeOnly = true;
        int[] xz = AirDefenseDataSVA.keyXZ("0:1:2:3");
        check("Bob".equals(AirDefenseSVA.nearestCodeOnlyOwner(d, 0, 118, 92)) && "Alice".equals(AirDefenseSVA.nearestCodeOnlyOwner(d, 0, 990, 1010))
                && "".equals(AirDefenseSVA.nearestCodeOnlyOwner(d, 7, 0, 0)) && xz != null && xz[0] == 1 && xz[1] == 3 && AirDefenseDataSVA.keyXZ("x") == null,
                "ad code review (finding 5): the nearest switched-on code-only post's owner (not a usual post, another dimension or a tower)");
        check(CodeOnlyMath.boardingNote(true, true, false, true, 1, "Bob", "") == CodeOnlyMath.B_NO_CARD
                && CodeOnlyMath.boardingNote(true, true, true, false, 1, "Bob", "") == CodeOnlyMath.B_KEY_OFF
                && CodeOnlyMath.boardingNote(true, true, true, true, 2, "Bob", "Alice") == CodeOnlyMath.B_OTHER_CARD
                && CodeOnlyMath.boardingNote(true, true, true, true, 2, "Bob", "bob") == CodeOnlyMath.B_NONE
                && CodeOnlyMath.boardingNote(true, true, true, true, 1, "Bob", "") == CodeOnlyMath.B_NONE
                && CodeOnlyMath.boardingNote(false, true, false, true, 2, "Bob", "Alice") == CodeOnlyMath.B_NONE
                && CodeOnlyMath.boardingNote(true, false, false, false, 2, "Bob", "Alice") == CodeOnlyMath.B_NONE,
                "ad code review (finding 5): on boarding - no card; a card but «Ключ дня» off; another owner's card squawked (two keyed cards); else nothing");
    }

    // ---------------------------------------------------------------- finding 6: the arming done anew

    private static void arming() {
        check(CodeOnlyMath.disarmOnKey(true, true, false) && !CodeOnlyMath.disarmOnKey(false, true, false) && !CodeOnlyMath.disarmOnKey(true, false, true)
                && !CodeOnlyMath.disarmOnKey(true, true, true) && CodeOnlyMath.disarmOnServer(false, true) && !CodeOnlyMath.disarmOnServer(true, true)
                && !CodeOnlyMath.disarmOnServer(false, false) && !CodeOnlyMath.disarmOnServer(true, false),
                "ad code review (finding 6): the arming dropped with the post's day key off, or the server's switch off then on over a restart");
        check(CodeOnlyMath.state(true, -1, 99999, true, true) == CodeOnlyMath.CO_ARMING && CodeOnlyMath.rearm(true, -1, CodeOnlyMath.CO_ARMING)
                && !CodeOnlyMath.rearm(true, 500, CodeOnlyMath.CO_ARMING) && !CodeOnlyMath.rearm(true, -1, CodeOnlyMath.CO_NO_KEY)
                && !CodeOnlyMath.rearm(false, -1, CodeOnlyMath.CO_OFF) && !CodeOnlyMath.rearm(true, -1, CodeOnlyMath.CO_BANNED),
                "ad code review (finding 6): dropped - never ON, the post arms it anew once nothing forbids it (not while NO_KEY / BANNED)");
        AirDefenseDataSVA d = new AirDefenseDataSVA("test");
        AirDefenseDataSVA.KpInfo i = d.info("0:1:2:3", true), u = d.info("0:4:5:6", true);
        i.codeOnly = true;
        i.armAt = 100;
        u.armAt = 200;                                              // switched off: untouched
        boolean noDisarm = !d.serverSwitch(false) && i.armAt == 100;
        NBTTagCompound t = new NBTTagCompound();
        d.writeToNBT(t);
        AirDefenseDataSVA e = new AirDefenseDataSVA("test");
        e.readFromNBT(t);                                           // the next start: the save says the server had it off
        boolean disarm = e.serverSwitch(true) && e.info("0:1:2:3", false).armAt == -1 && e.info("0:4:5:6", false).armAt == 200;
        boolean once = !e.serverSwitch(true);
        AirDefenseDataSVA f = new AirDefenseDataSVA("test");
        f.readFromNBT(new NBTTagCompound());                        // an older save: on - nothing dropped
        f.info("0:1:2:3", true).codeOnly = true;
        f.info("0:1:2:3", false).armAt = 100;
        check(noDisarm && disarm && once && !f.serverSwitch(true) && f.info("0:1:2:3", false).armAt == 100,
                "ad code review (finding 6): the server's switch off at the last start and on now - every switched-on post loses its arming (saved, once); "
                        + "an older save - nothing");
    }
}
