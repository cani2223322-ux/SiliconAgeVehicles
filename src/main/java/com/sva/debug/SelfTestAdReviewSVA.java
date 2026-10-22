package com.sva.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.sva.airdefense.AdLayoutMath;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.AirportCodeMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;

/**
 * Audit review 2026-10-22 self-test (the findings on the audit's own fixes) - pure logic, the dedicated server runs it:
 * <ul>
 * <li>7600: an episode nobody sees ends at its last sighting (a launcher without a post: an aircraft that flew away still on
 * 7600 had its 2 minutes used up for good; a post off / unloaded: the outage counted, the track came back at its default);
 * a running entry out of sight is forgotten after the memory; a code change takes «проверить» with it; a post-less
 * launcher's «свой» only while it squawks 7600;</li>
 * <li>the post's own aerodrome range is public - an airport's code (no friend over a violation / a handoff), never a tower's
 * zone by range;</li>
 * <li>a bare trusted ICAO / white-list number is bound only when newly typed; an older save's white list (any owner) until
 * saved;</li>
 * <li>7500 told once in 5 minutes a vehicle; the warnings «heard» for the whole engagement;</li>
 * <li>the 7600 panel's buttons right of the countdown; the transponder's HUD line under the space panel; the new lang keys.</li>
 * </ul>
 */
final class SelfTestAdReviewSVA {

    private SelfTestAdReviewSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        noRadio();
        codes();
        binding();
        rest();
    }

    // ---------------------------------------------------------------- 7600

    private static void noRadio() {
        int timer = 2400;
        long day = 24000;
        // a launcher without a post: 7600 seen 10 s, then gone (flew on, still 7600); a real failure days later near another one
        AirDefenseDataSVA d = new AirDefenseDataSVA("selftestReview");
        for (long t = 1000; t <= 1200; t += 5) {
            d.freeNoRadio("veh-a", true, t);
        }
        NoRadioMath.Entry fa = d.freeNoRadio("veh-a", true, 1000 + 3 * day);
        boolean graceBack = NoRadioMath.grace(fa, 1000 + 3 * day, XpdrMath.NO_RADIO_GRACE);
        check(fa != null && fa.used == 200 && fa.since == 1000 + 3 * day && graceBack
                && NoRadioMath.elapsed(fa, 1000 + 3 * day + 100) == 300,
                "ad review (7600): a post-less launcher's memory - an aircraft that flew out of sight still on 7600 keeps the rest of its 2 minutes "
                        + "(only the 10 s seen counted: " + (fa == null ? -1 : fa.used) + " ticks; it was days of them - no grace)");
        // the same entry left running and never seen again: forgotten after the memory (it stayed in the save for good)
        d.freeNoRadio("veh-b", true, 500);
        long mem = 12000;
        d.prune(500 + mem, mem);
        boolean kept = d.freeNoRadioEntry("veh-b") != null;
        d.prune(501 + mem, mem);
        check(kept && d.freeNoRadioEntry("veh-b") == null && d.freeNoRadioEntry("veh-a") != null,
                "ad review (7600): a running entry nobody sees again is forgotten after the memory (" + mem / 1200 + " min)");
        // a post: seen 10 s, then its power / chunk away 5 minutes, the aircraft still on 7600 - the timer goes on from 10 s
        NoRadioMath.Entry e = new NoRadioMath.Entry(0);
        for (long t = 10; t <= 200; t += 10) {
            NoRadioMath.seen(e, t);
        }
        NoRadioMath.Entry lost = new NoRadioMath.Entry(0);
        lost.lastSeen = 200;
        NoRadioMath.off(lost, 6200);                                // the post off at 6200 - paused at its last sighting
        NoRadioMath.seen(e, 6200);
        check(e.used == 200 && e.since == 6200 && NoRadioMath.state(e, 6200, timer) == NoRadioMath.S_PENDING
                && NoRadioMath.secondsLeft(e, 6200, timer) == 110 && lost.used == 200 && lost.since < 0 && lost.lastSeen == 200,
                "ad review (7600): a post back after 5 minutes off / unloaded - the track still PENDING with 110 s to decide (the outage counted: "
                        + "the default at once)");
        // «проверить», then 7000 between the warnings and 7600 again: no longer held for good
        NoRadioMath.Entry c = new NoRadioMath.Entry(0);
        NoRadioMath.decide(c, NoRadioMath.D_CHECK, 100, 0);
        NoRadioMath.Entry h = new NoRadioMath.Entry(0);
        NoRadioMath.decide(h, NoRadioMath.D_HOSTILE, 100, 0);
        NoRadioMath.changed(c, 150);
        NoRadioMath.changed(h, 150);
        NoRadioMath.Entry lostC = new NoRadioMath.Entry(0);
        NoRadioMath.decide(lostC, NoRadioMath.D_CHECK, 100, 0);
        NoRadioMath.off(lostC, 150);                                // only lost (out of the radar's sight) - the decision stays
        check(c.decision == NoRadioMath.D_NONE && c.since < 0 && h.decision == NoRadioMath.D_HOSTILE && lostC.decision == NoRadioMath.D_CHECK,
                "ad review (7600): another code takes «проверить» with the episode (7000 / 7600 kept the fire held for good); «враждебный» stays; "
                        + "a track lost keeps it");
        // a post-less launcher: the post's 7600 «свой» only while it squawks 7600
        AirDefenseSVA.Trust tr = new AirDefenseSVA.Trust();
        AirDefenseDataSVA.KpInfo i = d.info("0:1:2:3", true);
        NoRadioMath.Entry f = new NoRadioMath.Entry(0);
        NoRadioMath.decide(f, NoRadioMath.D_FRIEND, 100, 12000);
        i.noRadio.put("veh-f", f);
        tr.infos.add(i);
        check(tr.granted("veh-f", true, 200) && !tr.granted("veh-f", false, 200) && !tr.granted("veh-f", true, 12200),
                "ad review (7600): a launcher whose post is off keeps the post's 7600 «свой» only while it squawks 7600 (any code kept it a friend)");
    }

    // ---------------------------------------------------------------- the aerodrome range

    private static void codes() {
        ZoneMath.Zone post = new ZoneMath.Zone();
        post.type = ZoneMath.AERODROME;
        post.shape = ZoneMath.CIRCLE;
        post.cx = 0;
        post.cz = 0;
        post.r = 300;
        post.codeLo = 04100;
        post.codeHi = 04177;
        post.owner = "Alice";
        ZoneMath.Zone tower = post.copy();
        tower.tower = true;
        tower.codeLo = 04200;
        tower.codeHi = 04277;
        List<ZoneMath.Zone> zl = Arrays.asList(post, tower);
        ZoneMath.Zone own = AirportCodeMath.postRange(zl, 04123, 10, 100, 10, true);
        check(own == post && AirportCodeMath.postRange(zl, 04223, 10, 100, 10, true) == null && AirportCodeMath.postRange(zl, 04123, 900, 100, 0, true) == null
                && AirportCodeMath.postRange(zl, -1, 10, 100, 10, true) == null,
                "ad review (codes): a post's own aerodrome range inside its zone - never a tower's zone by its range (a post-less launcher took "
                        + "its owner's towers' ranges past the «given to this aircraft» check)");
        net.minecraft.nbt.NBTTagCompound pz = AirDefenseSVA.publicZone(post);
        check(!pz.hasKey("CodeLo") && !pz.hasKey("CodeHi") && pz.hasKey("Circle") && pz.hasKey("Type"),
                "ad review (codes): the zones every client gets carry no range of codes (a post's aerodrome range went to every player)");
        check(RoeMath.classify(false, true, 04123, false, own != null, false, false, false, true) == RoeMath.VIOLATOR
                && RoeMath.classify(false, true, 04123, false, own != null, false, false, false, false) == RoeMath.FRIEND,
                "ad review (codes): the post's own aerodrome range (public - every client gets the zones) is an airport's code: no friend in a "
                        + "forbidden zone or handed over by a tower (it was the post's own code - FRIEND over both)");
    }

    // ---------------------------------------------------------------- binding

    private static void binding() {
        List<String> before = Arrays.asList("UBTW", "QQQQ:Bob");
        check(!AirportCodeMath.mayBind("UBTW", before) && !AirportCodeMath.mayBind("ubtw", before) && AirportCodeMath.mayBind("ZZZZ", before)
                && AirportCodeMath.mayBind("QQQQ", before) && !AirportCodeMath.mayBind("UBTW:Eve", null) && AirportCodeMath.mayBind("UBTW", null)
                && !AirportCodeMath.mayBind("23", Arrays.asList("23")) && AirportCodeMath.mayBind("42", Arrays.asList("23")),
                "ad review (bug 7): a bare ICAO / board kept bare from an earlier save is not bound by a later save of anything else (to whoever took "
                        + "that ICAO / number since) - only when newly typed");
        check(AirportCodeMath.whiteMatch(23, "Carol", 23, "carol") && !AirportCodeMath.whiteMatch(23, "Carol", 23, "Mallory")
                && !AirportCodeMath.whiteMatch(23, "", 23, "Mallory") && AirportCodeMath.whiteMatch(23, AirportCodeMath.ANY_OWNER, 23, "Mallory")
                && !AirportCodeMath.whiteMatch(23, AirportCodeMath.ANY_OWNER, 42, "Mallory") && !AirportCodeMath.whiteMatch(23, null, 23, ""),
                "ad review (Mode S): a white-list entry - its owner; bare - nobody; an older save's (any owner, as 0.1.1) - any owner until saved");
        AirDefenseDataSVA.KpInfo i = new AirDefenseDataSVA("selftestReview2").info("0:5:5:5", true);
        i.whiteBoard.add(Integer.valueOf(42));
        i.whiteOwner.add(AirportCodeMath.ANY_OWNER);
        check(i.whitelisted(42, "Anyone") && !i.whitelisted(41, "Anyone"),
                "ad review (Mode S): a post-less launcher reads an older save's white list the same way (the post's trust data)");
    }

    // ---------------------------------------------------------------- the rest

    private static void rest() throws Exception {
        check(XpdrMath.hijackAlarm(-1, 100) && !XpdrMath.hijackAlarm(100, 100 + XpdrMath.HIJACK_REPEAT - 1)
                && XpdrMath.hijackAlarm(100, 100 + XpdrMath.HIJACK_REPEAT) && XpdrMath.hijackAlarm(5000, 100),
                "ad review (7500): the alarm told once in " + XpdrMath.HIJACK_REPEAT / 1200 + " min a vehicle (7500 / 7000 toggled flooded the list's chat)");
        // a pilot who heard everything steps out for one round before the fire and back in
        boolean heard = true, restarted = false;
        boolean[] aboard = {true, true, false, true, false, true};
        for (int r = 1; r < aboard.length; r++) {
            restarted |= RoeMath.restartWarnings(heard, aboard[r], RoeMath.ST_LOCK);
            heard = RoeMath.heard(heard, aboard[r]);
        }
        boolean empty = false, emptyRestart = false;
        boolean[] boards = {false, false, true};
        for (int r = 1; r < boards.length; r++) {
            emptyRestart |= RoeMath.restartWarnings(empty, boards[r], RoeMath.ST_LOCK);
            empty = RoeMath.heard(empty, boards[r]);
        }
        check(!restarted && emptyRestart && heard,
                "ad review (warnings): once anyone aboard heard them, hopping out and in no longer starts the warnings over (the launcher never "
                        + "fired); an empty cockpit's pilot boarding still hears them");
        // the 7600 panel: «01 7600 1:59» is 64 px in the Minecraft font from x0 + 3; x0 = mapL + 2, x1 = mapL + 202
        int x0 = 2, x1 = 202, textRight = x0 + 3 + 64;
        int[][] b = AdLayoutMath.nrButtons(textRight, x1);
        boolean apart = b[0][0] > textRight && b[0][0] + b[0][1] < b[1][0] && b[1][0] + b[1][1] < b[2][0] && b[2][0] + b[2][1] == x1;
        boolean wide = b[0][1] >= 30 && b[1][1] >= 30 && b[2][1] >= 30;
        check(apart && wide && x1 - 150 < textRight,
                "ad review (KP screen): the 7600 buttons right of the countdown (x1 - 150 covered its seconds), each still 30 px or wide ("
                        + b[0][1] + "/" + b[1][1] + "/" + b[2][1] + ")");
        int ship = AdLayoutMath.spacePanelBottom(true, true), shuttle = AdLayoutMath.spacePanelBottom(false, false);
        check(AdLayoutMath.spaceRows(false, false) == 2 && AdLayoutMath.spaceRows(true, true) == 8 && AdLayoutMath.xpdrLineY(24, shuttle) - 2 > shuttle
                && AdLayoutMath.xpdrLineY(24, ship) - 2 > ship && AdLayoutMath.xpdrLineY(24, 0) == 24 && AdLayoutMath.xpdrLineY(200, ship) == 200,
                "ad review (HUD): the transponder line of the shuttle / ship below their space panel (it was over its oxygen / jump rows: "
                        + shuttle + ", " + ship + ")");
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        List<String> miss = new ArrayList<String>();
        for (String k : new String[] {"sva.ad.msg.trustBound", "sva.ad.msg.whiteBound", "sva.ad.log.legacyTrust", "sva.ad.log.legacyWhite",
            "sva.ad.msg.trustUnknown"}) {
            if (!ru.contains(k) || !en.contains(k)) {
                miss.add(k);
            }
        }
        check(miss.isEmpty(), "ad review: the new lang keys in ru_RU and en_US" + (miss.isEmpty() ? "" : " - missing " + miss));
    }
}
