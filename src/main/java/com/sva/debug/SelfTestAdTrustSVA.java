package com.sva.debug;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirportCodeMath;
import com.sva.airdefense.CmdRateMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Audit 2026-10-22 self-test (the friend / foe trust model) - pure logic, the dedicated server runs it:
 * <ul>
 * <li>bug 8: the 7600 episode memory - a code changed away and back pauses the timer, never restarts it; the memory is
 * forgotten only after the cooldown; «враждебный»; the launchers' own 2-minute grace counts the episodes together;</li>
 * <li>bugs 6 / 15: an airport's code is no friend over a violation / a handoff; a never interrogated track is UNKNOWN;</li>
 * <li>bugs 5 / 7 / 9: who a tower gives its codes to, what it never replaces; the trusted list (commas / semicolons only, the
 * ICAO bound to its owner);</li>
 * <li>bugs 18 / 20, Mode S: the owner's one secret (an older save's first post hands its own over), the trust data's NBT (the
 * decisions by the vehicle's persistent id with their expiry, the handoffs of two towers side by side, the launchers' 7600
 * memory), the expiry; the white list only with the owner;</li>
 * <li>the config default, the command rate of the new orders, the new lang keys.</li>
 * </ul>
 */
final class SelfTestAdTrustSVA {

    private SelfTestAdTrustSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        noRadioMemory();
        classes();
        codes();
        data();
        configAndLang();
    }

    // ---------------------------------------------------------------- bug 8: 7600 episodes

    private static void noRadioMemory() {
        int timer = 2400;
        NoRadioMath.Entry e = new NoRadioMath.Entry(1000);
        for (long t = 1100; t < 2000; t += 100) {
            NoRadioMath.seen(e, t);                                 // seen on 7600 all the while (the post's rounds)
        }
        NoRadioMath.off(e, 2000);                                   // 1000 ticks of 7600, then 7000
        NoRadioMath.seen(e, 2130);                                  // back on 7600 one surveillance cycle later
        check(e.used == 1000 && e.since == 2130 && NoRadioMath.elapsed(e, 2130) == 1000 && NoRadioMath.secondsLeft(e, 2130, timer) == 70
                && NoRadioMath.state(e, 2130, timer) == NoRadioMath.S_PENDING && NoRadioMath.state(e, 2130 + 1400, timer) == NoRadioMath.S_DEFAULT,
                "ad trust (bug 8): 7600 off for a cycle and back - the timer goes on from 1000 ticks (70 s left), it does not start afresh");
        NoRadioMath.Entry f = new NoRadioMath.Entry(0);
        for (int i = 0; i < 20; i++) {                              // 20 times 7600 for 100 ticks, 7000 for 100 - the old trick
            NoRadioMath.off(f, i * 200 + 100);
            NoRadioMath.seen(f, i * 200 + 200);
        }
        check(NoRadioMath.elapsed(f, 4000) == 2000 && NoRadioMath.state(f, 4500, timer) == NoRadioMath.S_DEFAULT,
                "ad trust (bug 8): toggling 7600 / 7000 every 5 s - the time adds up, the default comes after 2 min of 7600 in all");
        NoRadioMath.Entry g = new NoRadioMath.Entry(0);
        NoRadioMath.seen(g, 400);
        NoRadioMath.off(g, 500);
        long mem = 12000;
        check(!NoRadioMath.expired(g, 500 + mem, mem) && NoRadioMath.expired(g, 501 + mem, mem) && !NoRadioMath.expired(e, 2130 + mem, mem)
                && NoRadioMath.expired(e, 999999, mem) && NoRadioMath.expired(null, 0, mem),
                "ad trust (bug 8): the memory is forgotten only after the cooldown off 7600 or out of sight (review: a running one too - it stayed for good)");
        NoRadioMath.Entry h = new NoRadioMath.Entry(100);
        NoRadioMath.decide(h, NoRadioMath.D_HOSTILE, 200, 12000);
        check(NoRadioMath.state(h, 200, timer) == NoRadioMath.S_HOSTILE && !NoRadioMath.holdFire(NoRadioMath.S_HOSTILE) && !NoRadioMath.friend(NoRadioMath.S_HOSTILE)
                && NoRadioMath.secondsLeft(h, 200, timer) == 0 && NoRadioMath.STATE_KEYS[NoRadioMath.S_HOSTILE].equals("hostile")
                && RoeMath.classify(false, true, XpdrMath.NO_RADIO, false, false, false, false, true) == RoeMath.VIOLATOR,
                "ad trust (bug 8): «враждебный» - no grace, no wait: classed as any other track (a violator in a zone)");
        NoRadioMath.Entry k = new NoRadioMath.Entry(0);
        k.used = XpdrMath.NO_RADIO_GRACE - 100;
        NoRadioMath.off(k, 50);
        boolean in = NoRadioMath.grace(k, 1000, XpdrMath.NO_RADIO_GRACE);
        NoRadioMath.seen(k, 1000);
        check(in && NoRadioMath.grace(k, 1049, XpdrMath.NO_RADIO_GRACE) && !NoRadioMath.grace(k, 1050, XpdrMath.NO_RADIO_GRACE)
                && !NoRadioMath.grace(null, 0, XpdrMath.NO_RADIO_GRACE),
                "ad trust (bug 2 / 8): a launcher without a post - 7600's 2 minutes counted over the episodes together (" + XpdrMath.NO_RADIO_GRACE + " ticks)");
    }

    // ---------------------------------------------------------------- bugs 6, 15: the classes

    private static void classes() {
        check(RoeMath.classify(false, true, 04120, false, true, false, false, false, false) == RoeMath.FRIEND
                && RoeMath.classify(false, true, 04120, false, true, false, false, false, true) == RoeMath.VIOLATOR
                && RoeMath.classify(false, false, -1, false, true, false, false, false, false) == RoeMath.FOREIGN
                && RoeMath.classify(false, true, 01234, true, false, false, false, false, true) == RoeMath.FRIEND
                && RoeMath.classify(false, true, XpdrMath.EMERGENCY, false, true, false, false, false, true) == RoeMath.EMERGENCY,
                "ad trust (bug 6): an airport's code - a friend outside a zone, not over a violation / a handoff; own codes still; 7700 always");
        check(RoeMath.unknownUntilAsked(RoeMath.VIOLATOR, false) == RoeMath.UNKNOWN && RoeMath.unknownUntilAsked(RoeMath.FOREIGN, false) == RoeMath.UNKNOWN
                && RoeMath.unknownUntilAsked(RoeMath.OWN, false) == RoeMath.OWN && RoeMath.unknownUntilAsked(RoeMath.VIOLATOR, true) == RoeMath.VIOLATOR
                && !RoeMath.hostile(RoeMath.UNKNOWN) && !RoeMath.roeWants(RoeMath.FOREIGN_ALL, RoeMath.UNKNOWN, true, true)
                && RoeMath.CLASS_KEYS.length == RoeMath.CLASSES && RoeMath.CLASS_COLOURS.length == RoeMath.CLASSES,
                "ad trust (bug 15): a track never interrogated is UNKNOWN - no rule (not even an assignment) fires at it; the owner's own stays own");
    }

    // ---------------------------------------------------------------- bugs 5, 7, 9: the airport's codes

    private static void codes() {
        List<String> t = AirportCodeMath.parseTrusted("Северный аэропорт, ubtw : Alice ; QQQQ, ubtw");
        check(t.size() == 3 && t.get(0).equals("СЕВЕРНЫЙ АЭРОПОРТ") && t.get(1).equals("UBTW:Alice") && t.get(2).equals("QQQQ")
                && AirportCodeMath.icaoOf(t.get(1)).equals("UBTW") && AirportCodeMath.ownerOf(t.get(1)).equals("Alice") && AirportCodeMath.ownerOf(t.get(2)).equals(""),
                "ad trust (bug 7): the trusted list splits on commas / semicolons only (a name with a space is one entry), the owner after the colon, no doubles");
        check(AirportCodeMath.bind("QQQQ", "Bob").equals("QQQQ:Bob") && AirportCodeMath.bind("UBTW:Alice", "Bob").equals("UBTW:Alice")
                && AirportCodeMath.bind("QQQQ", "").equals("QQQQ") && AirportCodeMath.trustedTower(t, "ubtw", "alice")
                && !AirportCodeMath.trustedTower(t, "UBTW", "Mallory") && !AirportCodeMath.trustedTower(t, "QQQQ", "Bob")
                && !AirportCodeMath.trustedTower(t, "QQQQ", ""),
                "ad trust (bug 7): a bare ICAO is bound to its tower's owner; trust holds only for that ICAO of that owner (unbound - nobody)");
        List<String> acc = Arrays.asList("Carol"), trust = Arrays.asList(" Dave ");
        check(AirportCodeMath.mayAssign("Alice", acc, trust, "alice") && AirportCodeMath.mayAssign("Alice", acc, trust, "Carol")
                && AirportCodeMath.mayAssign("Alice", acc, trust, "dave") && !AirportCodeMath.mayAssign("Alice", acc, trust, "Mallory")
                && !AirportCodeMath.mayAssign("Alice", null, null, "") && !AirportCodeMath.mayAssign("Alice", null, null, null),
                "ad trust (bug 5): a tower gives its codes on its own only to its owner's, his access list's and its trusted owners' aircraft");
        check(AirportCodeMath.mayReplace(XpdrMath.VFR, false) && AirportCodeMath.mayReplace(02345, false) && !AirportCodeMath.mayReplace(XpdrMath.EMERGENCY, false)
                && !AirportCodeMath.mayReplace(XpdrMath.NO_RADIO, false) && !AirportCodeMath.mayReplace(XpdrMath.HIJACK, false)
                && !AirportCodeMath.mayReplace(02345, true),
                "ad trust (bug 9): a tower's code never replaces 7500 / 7600 / 7700 nor a running day key");
    }

    // ---------------------------------------------------------------- bugs 18, 20: the trust data

    private static void data() {
        AirDefenseDataSVA d = new AirDefenseDataSVA("selftest");
        String a = d.ownerSecret("Alice", "old1", "fresh"), b = d.ownerSecret("alice", "old2", "fresh2");
        String c = d.ownerSecret("Bob", "", "fresh3");
        check(a.equals("old1") && b.equals("old1") && c.equals("fresh3") && XpdrMath.dayCode(a, 7) == XpdrMath.dayCode(d.secret("ALICE"), 7),
                "ad trust (bug 18): one secret per owner - the first post loaded hands its own over, the next posts of his take it (one day code)");
        AirDefenseDataSVA.KpInfo i = d.info("0:1:2:3", true);
        i.owner = "Alice";
        i.dim = 0;
        i.codes = new int[] {01234, -1};
        i.whiteBoard.add(Integer.valueOf(23));
        i.whiteOwner.add("Carol");
        i.whiteBoard.add(Integer.valueOf(42));
        i.whiteOwner.add("");
        i.trusted.add("UBTW:Bob");
        i.grants.put("veh-1", Long.valueOf(5000));
        i.holds.put("veh-2", Long.valueOf(6000));
        i.assigned.put("veh-3", new AirDefenseDataSVA.Asg("0:9:9:9", 7000));
        NoRadioMath.Entry e = new NoRadioMath.Entry(100);
        NoRadioMath.off(e, 300);
        NoRadioMath.decide(e, NoRadioMath.D_CHECK, 300, 0);
        i.noRadio.put("veh-4", e);
        d.handoff("veh-5", "0:10:64:10", "Bob", "UBTW", 8000);
        d.handoff("veh-5", "0:90:64:90", "Eve", "UBTX", 9000);
        NoRadioMath.Entry fr = d.freeNoRadio("veh-6", true, 1000);
        d.freeNoRadio("veh-6", true, 1200);                         // still in sight
        d.freeNoRadio("veh-6", false, 1300);
        NBTTagCompound tag = new NBTTagCompound();
        d.writeToNBT(tag);
        AirDefenseDataSVA r = new AirDefenseDataSVA("selftest2");
        r.readFromNBT(tag);
        AirDefenseDataSVA.KpInfo j = r.info("0:1:2:3", false);
        NoRadioMath.Entry e2 = j == null ? null : j.noRadio.get("veh-4");
        NoRadioMath.Entry fr2 = r.freeNoRadioEntry("veh-6");
        check(j != null && j.owner.equals("Alice") && j.ownCode(01234) && !j.ownCode(-1) && j.granted("veh-1", 4999) && !j.granted("veh-1", 5000)
                && j.held("veh-2", 100) && j.assigned.get("veh-3").launcher.equals("0:9:9:9") && j.assigned.get("veh-3").until == 7000
                && e2 != null && e2.used == 200 && e2.since < 0 && e2.decision == NoRadioMath.D_CHECK && j.trusted.contains("UBTW:Bob")
                && r.handoffs("veh-5", 7000).size() == 2 && r.handoffs("veh-5", 8500).size() == 1 && r.secret("alice").equals("old1")
                && fr != null && fr.used == 300 && fr2 != null && fr2.used == 300 && fr2.since < 0,
                "ad trust (bug 20): the decisions by the vehicle's persistent id with their expiry, the 7600 memory, two towers' handoffs, "
                        + "the owner's secret - through the save and back");
        check(j != null && j.whitelisted(23, "carol") && !j.whitelisted(23, "Mallory") && !j.whitelisted(42, "Mallory") && !j.whitelisted(42, ""),
                "ad trust (Mode S): a white-list entry matches only its owner - a board number alone (they repeat) matches nobody");
        r.release("veh-5", "0:10:64:10");
        boolean one = r.handoffs("veh-5", 0).size() == 1 && r.handoffs("veh-5", 0).get(0).icao.equals("UBTX");
        r.prune(8000, 12000);
        boolean pruned = j != null && !j.grants.containsKey("veh-1") && !j.holds.containsKey("veh-2") && !j.assigned.containsKey("veh-3")
                && j.noRadio.containsKey("veh-4") && r.handoffs("veh-5", 0).size() == 1;
        r.prune(1300 + 12001, 12000);
        check(one && pruned && j.noRadio.isEmpty() && r.freeNoRadioEntry("veh-6") == null && r.handoffs("veh-5", 0).isEmpty(),
                "ad trust (bug 20): one tower's handoff taken back leaves the other's; the expired decisions go, the 7600 memory after its cooldown");
        r.removeKp("0:1:2:3");
        check(r.info("0:1:2:3", false) == null && r.infosOf("Alice", 0).isEmpty(), "ad trust: a post broken - its trust data goes with it");
    }

    // ---------------------------------------------------------------- the config, the orders, the lang

    private static void configAndLang() throws Exception {
        check(ConfigSVA.adNoRadioMemoryMinutes == 10 && CmdRateMath.kind("nrHostile") == CmdRateMath.LIGHT && CmdRateMath.kind(CmdRateMath.LINK) == CmdRateMath.HEAVY,
                "ad trust config: the 7600 memory 10 min by default; «враждебный» a light order, a link change a heavy one");
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        String[] keys = {"sva.ad.cls.unknown", "sva.ad.kp.menu.nrHostile", "sva.ad.nr.hostile", "sva.ad.nr.hostileMark", "sva.ad.kp.holdMark",
            "sva.ad.log.noRadioHostile", "sva.ad.log.noRadioAgain", "sva.ad.log.hijack", "sva.ad.msg.hijack", "sva.ad.msg.whiteUnbound",
            "sva.ad.msg.trustUnknown", "sva.ad.msg.trustBound", "sva.ad.msg.whiteBound", "sva.ad.log.legacyTrust", "sva.ad.log.legacyWhite", "sva.ad.l.kpOff", "sva.ad.l.kpNext.tip", "sva.atc.squawkKey", "sva.tower.gui.up.squawk",
            "sva.tower.gui.sqTrust", "sva.tower.gui.sqTrustHint", "sva.tower.msg.noSquawk"};
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (!ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        for (String k : RoeMath.CLASS_KEYS) {
            if (!ru.contains("sva.ad.cls." + k) || !en.contains("sva.ad.cls." + k)) {
                miss.append(" sva.ad.cls.").append(k);
            }
        }
        check(miss.length() == 0, "ad trust lang: the new lines in ru and en" + miss);
    }
}
