package com.sva.debug;

import java.util.Map;
import java.util.Set;

import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.CodeOnlyUiMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.item.ItemCodeCardSVA;

import net.minecraft.item.ItemStack;

/**
 * Code-only wave, the review of 2026-10-27 (batch 2) - pure logic, data and the lang, the dedicated server runs it:
 * <ul>
 * <li>the 7700 memory all of an owner's posts share takes an answer only when it is newer than the last opposite one
 * (NoRadioMath.fresh): a track holding 7000 from an older survey no longer wipes the operator's «Проверить» nor chops the
 * grace into pieces; a fresher 7000 still pauses it, an older 7700 does not resume it;</li>
 * <li>cards of several owners: an owner whose code-only post is within CARD_REACH comes first, between equal cards the
 * pilot's inventory order (no longer the longest-running card);</li>
 * <li>the texts with the server's numbers: 7700's grace (and «0 - never»), the shortest wait, the arming time, the guessing
 * guard - placeholders, no fixed 180 / 20 / 60 / 4 / 5;</li>
 * <li>an older card (with its own code) gives nothing, its tooltip says so;</li>
 * <li>the transponder window's hint fits its lines (the en text wrapped to 6 of 4 and lost the 7700 grace).</li>
 * </ul>
 */
final class SelfTestAdCodeReview2SVA {

    private SelfTestAdCodeReview2SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        emergencyStale();
        cards();
        texts();
        legacyCard();
        hintLines();
    }

    // ---------------------------------------------------------------- finding 1: stale answers and the shared 7700 memory

    private static void emergencyStale() {
        NoRadioMath.Entry n = new NoRadioMath.Entry(0);
        boolean first = NoRadioMath.fresh(n, true, -5) && NoRadioMath.fresh(n, false, -5) && NoRadioMath.fresh(null, false, 0);
        NoRadioMath.heard(n, true, 100);
        NoRadioMath.heard(n, true, 90);                             // an older one never takes the newest back
        NoRadioMath.heard(n, false, 80);
        check(first && n.heardAt == 100 && n.quietAt == 80 && !NoRadioMath.fresh(n, false, 80) && !NoRadioMath.fresh(n, false, 100)
                && NoRadioMath.fresh(n, false, 101) && NoRadioMath.fresh(n, true, 81) && !NoRadioMath.fresh(n, true, 80),
                "ad code review 2 (finding 1): an answer counts only when newer than the newest opposite one (none yet - any)");
        AirDefenseDataSVA d = new AirDefenseDataSVA("test");
        NoRadioMath.Entry e = d.emergency("Alice", "veh-s", true, 100, 100);   // post A's track: 7700 asked at 100
        NoRadioMath.decide(e, NoRadioMath.D_CHECK, 105, 600);       // the operator: «Проверить»
        for (long t = 110; t <= 300; t += 10) {
            d.emergency("Alice", "veh-s", true, 100, t);            // A goes over the same answer each round
            d.emergency("ALICE", "veh-s", false, 80, t);            // post B's track: 7000 from its survey at 80
        }
        boolean kept = e.decision == NoRadioMath.D_CHECK && e.since == 100 && NoRadioMath.elapsed(e, 300) == 200 && e.lastSeen == 300;
        d.emergency("Alice", "veh-s", false, 310, 310);             // B's next survey: 7000 - newer than A's 7700
        boolean paused = e.since < 0 && e.decision == NoRadioMath.D_NONE && NoRadioMath.elapsed(e, 999) == 210;
        d.emergency("Alice", "veh-s", true, 250, 320);              // A's track still on its answer of 250
        boolean stale = e.since < 0 && NoRadioMath.elapsed(e, 999) == 210;
        d.emergency("Alice", "veh-s", true, 330, 330);              // A's next survey: 7700 again
        boolean resumed = e.since == 330;
        d.emergency("Alice", "veh-s", false, 340);                  // a launcher's own look (now): 7000 - paused
        check(kept && paused && stale && resumed && e.since < 0 && NoRadioMath.elapsed(e, 999) == 220,
                "ad code review 2 (finding 1): an older 7000 from another post keeps «Проверить» and the grace whole (" + kept + "); a fresher one pauses it "
                        + "and drops «Проверить» (" + paused + "); an older 7700 does not resume it (" + stale + "), a fresher one does (" + resumed + ")");
    }

    // ---------------------------------------------------------------- finding 2: cards of several owners

    private static void cards() {
        check(CodeOnlyMath.cardRelevance(true, false, false) == 3 && CodeOnlyMath.cardRelevance(true, true, true) == 3
                && CodeOnlyMath.cardRelevance(false, true, true) == 2 && CodeOnlyMath.cardRelevance(false, false, true) == 1
                && CodeOnlyMath.cardRelevance(false, false, false) == 0 && CodeOnlyMath.cardRelevance(true, false) == 2
                && Math.abs(CodeOnlyMath.CARD_REACH - 664) < 1e-9,
                "ad code review 2 (finding 2): a card whose owner's code-only post is within 664 blocks (600 survey + 64 link) first, then the nearest post's, "
                        + "then a list's");
        AirDefenseDataSVA d = new AirDefenseDataSVA("test");
        String[][] posts = {{"0:100:64:100", "Bob", "1"}, {"0:1000:64:1000", "Alice", "1"}, {"0:120:64:95", "Carol", "0"},
            {"-1:110:64:100", "Dave", "1"}, {AirDefenseDataSVA.TOWER_PREFIX + "0:118:64:92", "Eve", "1"}};
        for (String[] p : posts) {
            AirDefenseDataSVA.KpInfo i = d.info(p[0], true);
            i.owner = p[1];
            i.codeOnly = "1".equals(p[2]);
            i.dim = p[0].startsWith("-1") ? -1 : 0;
        }
        Set<String> near = AirDefenseSVA.codeOnlyOwnersInReach(d, 0, 118, 92), mid = AirDefenseSVA.codeOnlyOwnersInReach(d, 0, 550, 550);
        Set<String> alice = AirDefenseSVA.codeOnlyOwnersInReach(d, 0, 600, 600), none = AirDefenseSVA.codeOnlyOwnersInReach(d, 0, 5000, 5000);
        check(near.size() == 1 && near.contains("bob") && mid.size() == 2 && mid.contains("bob") && mid.contains("alice") && alice.size() == 1
                && alice.contains("alice") && none.isEmpty() && AirDefenseSVA.codeOnlyOwnersInReach(null, 0, 0, 0).isEmpty(),
                "ad code review 2 (finding 2): the code-only posts' owners within reach (not a usual post, another dimension or a tower; 707 blocks - out)");
        // the guest (his own key's card, listed on his own post far away) crosses Bob's zone with Bob's card; a third owner's
        // post is the nearest: Bob's card (in reach) wins whatever the order; two cards in reach - the first met
        int[][] guest = {{1, 3}, {3, 3}};                           // {relevance, rank} in inventory order
        int[][] both = {{3, 3}, {3, 3}};
        int[][] prev = {{3, 2}, {3, 3}};
        check(pick(guest) == 1 && pick(new int[][] {guest[1], guest[0]}) == 0 && pick(both) == 0 && pick(prev) == 1,
                "ad code review 2 (finding 2): the guest's own card loses to the card of the post that sees him; equals - the first in the inventory; "
                        + "good now beats good only for the last period");
    }

    /** cardCode's choice over cards {relevance, rank} in inventory order. */
    private static int pick(int[][] cards) {
        int best = -1;
        for (int i = 0; i < cards.length; i++) {
            if (best < 0 || CodeOnlyMath.betterCard(cards[i][0], cards[i][1], cards[best][0], cards[best][1])) {
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- finding 3: the server's numbers in the texts

    private static void texts() throws Exception {
        check("x".equals(CodeOnlyUiMath.emKey(180, "x")) && "x".equals(CodeOnlyUiMath.emKey(1, "x")) && "xNever".equals(CodeOnlyUiMath.emKey(0, "x")),
                "ad code review 2 (finding 3): 7700's phrase - the grace, with 0 the «never» one");
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        StringBuilder bad = new StringBuilder();
        String[] em = {"sva.ad.em.grace", "sva.ad.em.graceFull", "sva.ad.em.hold"};
        for (Map<String, String> m : new Map[] {ru, en}) {
            String l = m == ru ? " ru:" : " en:";
            for (String k : em) {
                String g = m.get(k), nv = m.get(k + "Never");
                if (g == null || nv == null || !String.format(g, Integer.valueOf(61)).contains("61") || nv.contains("%")
                        || nv.replace("7700", "").matches(".*[0-9].*")) {
                    bad.append(l).append(k);
                }
            }
            // each text with one of the server's numbers: a placeholder, the value shows, no fixed number left
            String[][] one = {{"sva.ad.l.alwaysCo", "sva.ad.em.hold", "180"}, {"sva.vmenu.xpdr.tip", "sva.ad.em.graceFull", "180"},
                {"sva.ad.xpdr.hint", "sva.ad.em.grace", "180"}, {"sva.ad.xpdr.emTip", "sva.ad.em.graceFull", "180"}};
            for (String[] k : one) {
                String v = m.get(k[0]), ph = m.get(k[1]), nv = m.get(k[1] + "Never");
                if (v == null || ph == null || nv == null || v.contains(k[2]) || !String.format(v, String.format(ph, Integer.valueOf(61))).contains("61")
                        || String.format(v, nv).contains("%") || !safe(String.format(v, nv))) {
                    bad.append(l).append(k[0]);
                }
            }
            String fw = m.get("sva.ad.roe.co.foreign.tip"), mt = m.get("sva.ad.co.set.mode.tip"), mn = m.get("sva.ad.co.set.opt.manual.tip");
            if (fw == null || fw.contains("20 ") || !String.format(fw, Integer.valueOf(33)).contains("33")) {
                bad.append(l).append("roe.co.foreign.tip");
            }
            if (mt == null || mt.contains("60 ") || !String.format(mt, Integer.valueOf(45)).contains("45")) {
                bad.append(l).append("co.set.mode.tip");
            }
            String mf = mn == null ? "" : String.format(mn, Integer.valueOf(6), Integer.valueOf(9));
            if (mn == null || !mf.contains("6") || !mf.contains("9") || mn.contains(" 4 ") || mn.contains("5 ")) {
                bad.append(l).append("co.set.opt.manual.tip");
            }
            // the launcher's rules and the post's options take the same arguments: no other of them may hold a stray %
            for (Map.Entry<String, String> x : m.entrySet()) {
                String k = x.getKey();
                if ((k.startsWith("sva.ad.roe.") && k.endsWith(".tip") || k.startsWith("sva.ad.co.set.opt.") || k.equals("sva.ad.xpdr.hintSilent"))
                        && !formats(x.getValue())) {
                    bad.append(l).append(k);
                }
            }
        }
        check(bad.length() == 0, "ad code review 2 (finding 3): the server's 7700 grace (or «never»), shortest wait, arming time and guessing guard in "
                + "the texts - placeholders, no fixed 180 / 20 / 60 / 4 / 5 (ru, en)" + bad);
    }

    /** The value formats with two numbers (String.format as Lang.tr does it) - no stray %. */
    private static boolean formats(String v) {
        try {
            String.format(v, Integer.valueOf(1), Integer.valueOf(2));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean safe(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (!(c >= ' ' && c < 127 || c >= 'Ѐ' && c <= 'ӿ' || c == '§')) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- finding 4: an older card

    private static void legacyCard() throws Exception {
        AirDefenseDataSVA d = new AirDefenseDataSVA("test");
        ItemStack old = new ItemStack(com.sva.init.ModContentSVA.codeCard);
        ItemCodeCardSVA.printLegacy(old, "Юг", 5, 01234);
        int[] onDay = AirDefenseSVA.cardOffer(d, old, 40, 5, false), overlap = AirDefenseSVA.cardOffer(d, old, 40, 5, true);
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        String r = ru.get("sva.tooltip.codeCard.legacy"), e = en.get("sva.tooltip.codeCard.legacy");
        check(onDay[0] == 0 && onDay[1] == -1 && overlap[0] == 0 && ItemCodeCardSVA.legacy(old) && r != null && r.contains("больше не действует")
                && !r.contains("обычн") && e != null && e.contains("no longer valid") && !e.contains("usual"),
                "ad code review 2 (finding 4): an older card (its own code) offers nothing even on its day (no code, no КЛЮЧ ДНЯ); its tooltip - "
                        + "no longer valid, print a new one (ru, en)");
    }

    // ---------------------------------------------------------------- finding 5: the transponder window's hint

    private static void hintLines() throws Exception {
        int w = CodeOnlyUiMath.XPDR_HINT_W, max = CodeOnlyUiMath.XPDR_HINT_LINES;
        String oldEn = "A - the code, C - code and altitude, S - plus the board number. 7700 - emergency (the board needs power): a usual air defence "
                + "never fires, a CODE-ONLY one - a 180 s grace. IDENT - the blip flashes 18 s.";
        boolean measure = CodeOnlyUiMath.lines(oldEn, w) == 6 && CodeOnlyUiMath.lines("", w) == 0 && CodeOnlyUiMath.lines("abcd efgh", 30) == 2
                && CodeOnlyUiMath.lines("abcd efgh", 52) == 1
                && CodeOnlyUiMath.glyphWidth('i') == 2 && CodeOnlyUiMath.glyphWidth(' ') == 4 && CodeOnlyUiMath.glyphWidth('W') == 6
                && CodeOnlyUiMath.glyphWidth('Ж') == 7;
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        StringBuilder bad = new StringBuilder();
        for (Map<String, String> m : new Map[] {ru, en}) {
            String h = m.get("sva.ad.xpdr.hint"), g = m.get("sva.ad.em.grace"), nv = m.get("sva.ad.em.graceNever"), s = m.get("sva.ad.xpdr.hintSilent");
            if (h == null || g == null || nv == null || s == null) {
                bad.append(" missing");
                continue;
            }
            // en is ASCII - measured exactly: 4 lines; ru by the upper bound (Cyrillic 7 px) - within the window's 5
            int lim = m == en ? 4 : max;
            for (String v : new String[] {String.format(h, String.format(g, "180")), String.format(h, String.format(g, "3600")), String.format(h, nv), s}) {
                int n = CodeOnlyUiMath.lines(v, w);
                if (n > lim) {
                    bad.append(m == en ? " en:" : " ru:").append(n);
                }
            }
        }
        check(measure && max == 5 && bad.length() == 0, "ad code review 2 (finding 5): the transponder hint - the vanilla font's word wrap reproduces "
                + "the old en text's 6 lines (" + measure + "); now en fits 4 lines at 214 px with 180 s, 3600 s and «never», ru the window's 5" + bad);
    }
}
