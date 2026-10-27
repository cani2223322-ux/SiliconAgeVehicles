package com.sva.debug;

import java.util.Set;

import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.CmdRateMath;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.ZoneMath;
import com.sva.block.TileEntityLauncherSVA;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Code-only wave self-test (docs/proposal-ad-strict-iff.md) - the command post, the launchers and the interrogation: the
 * pure rules the integration adds on top of CodeOnlyMath / RoeMath (SelfTestAdCodeOnlySVA checks those), the dedicated
 * server runs it:
 * <ul>
 * <li>the post's readiness line takes its launchers' rule that fires most (item 32);</li>
 * <li>what counts as a guess (item 11), the ОПОЗН lamp (item 22: a miss lights it, a match / the operator's friend never),
 * the boarding warning (item 22), the owner-only launchers (item 25), «верный код у чужого борта» (item 30), «борт
 * владельца без кода» (item 33), the arming's end (item 16);</li>
 * <li>a whole round as the post / a launcher runs it: the owner's machine without a card - a check, then a target; with the
 * card - a friend at once; a code dialled by hand - a friend (the user's decision 1); the silent look before the shot finds
 * the code now (item 14);</li>
 * <li>the new commands' rate (the 7700 decisions and «unlock» - light, the mode / options / key - heavy), the launch log's
 * marks, a code-only post's zones marked «CO» for the clients (never their codes);</li>
 * <li>the lang: every line the post, the launchers and the pilot get, the families (the pilot's reasons, the log's misses, the
 * verdicts' reasons, the readiness line) in ru and en with the same placeholders.</li>
 * </ul>
 */
final class SelfTestAdCodeKpSVA {

    private SelfTestAdCodeKpSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        readiness();
        rules();
        rounds();
        commandsAndLogs();
        lang();
    }

    // ---------------------------------------------------------------- item 32

    private static void readiness() {
        int h = RoeMath.HOLD, v = RoeMath.VIOLATORS, f = RoeMath.FOREIGN_ALL, k = RoeMath.KP_MARKS;
        check(CodeOnlyMath.bestRoe(new int[0]) == h && CodeOnlyMath.bestRoe(new int[] {h, v}) == v && CodeOnlyMath.bestRoe(new int[] {v, k, h}) == k
                && CodeOnlyMath.bestRoe(new int[] {k, f, v}) == f && CodeOnlyMath.bestRoe(new int[] {f, k}) == f && CodeOnlyMath.bestRoe(new int[] {h, h}) == h,
                "ad code-only post (item 32): the readiness line takes the launchers' rule that fires most - all, then the post's marks, then violators");
        check(CodeOnlyMath.readiness(true, true, true, true, true, true, CodeOnlyMath.bestRoe(new int[] {v, f})) == CodeOnlyMath.R_READY
                && CodeOnlyMath.readiness(true, true, true, true, true, true, CodeOnlyMath.bestRoe(new int[] {v})) == CodeOnlyMath.R_VIOLATORS
                && CodeOnlyMath.readiness(true, true, true, true, true, true, CodeOnlyMath.bestRoe(new int[0])) == CodeOnlyMath.R_HOLD
                && CodeOnlyMath.readiness(true, true, true, true, false, true, f) == CodeOnlyMath.R_KP_OFF,
                "ad code-only post (item 32): READY with a launcher «по всем», «только нарушители» / «огонь запрещён» / «КП обесточен» otherwise");
    }

    // ---------------------------------------------------------------- items 11, 16, 22, 25, 30, 33

    private static void rules() {
        check(CodeOnlyMath.guessed(RoeMath.ID_NO_CARD) && CodeOnlyMath.guessed(RoeMath.ID_WRONG) && CodeOnlyMath.guessed(RoeMath.ID_EXPIRED)
                && CodeOnlyMath.guessed(RoeMath.ID_OLD_KEY) && !CodeOnlyMath.guessed(RoeMath.ID_NO_REPLY) && !CodeOnlyMath.guessed(RoeMath.ID_KEY)
                && !CodeOnlyMath.guessed(RoeMath.ID_HIJACK) && !CodeOnlyMath.guessed(RoeMath.ID_EMERGENCY_OVER),
                "ad code-only (item 11): a guess is a wrong code itself - never no reply, a match, 7500 or 7700 past its grace");
        check(CodeOnlyMath.caution(true, RoeMath.UNKNOWN, RoeMath.ID_NO_CARD) && CodeOnlyMath.caution(true, RoeMath.FOREIGN, RoeMath.ID_NO_REPLY)
                && CodeOnlyMath.caution(true, RoeMath.EMERGENCY, RoeMath.ID_NO_CARD) && !CodeOnlyMath.caution(true, RoeMath.FRIEND, RoeMath.ID_KEY)
                && !CodeOnlyMath.caution(true, RoeMath.FRIEND, RoeMath.ID_NO_CARD) && !CodeOnlyMath.caution(false, RoeMath.UNKNOWN, RoeMath.ID_NO_CARD)
                && !CodeOnlyMath.caution(true, RoeMath.UNKNOWN, RoeMath.ID_OWN_CODE),
                "ad code-only (item 22): the ОПОЗН lamp - lit by any miss (a check too, 7700 in its grace), never by a match / the operator's friend / no interrogation");
        // the lamp from the interrogation's tick: a post's older survey answer (before the card's code) never lights it over a newer match
        com.sva.airdefense.XpdrMath.Xpdr x = new com.sva.airdefense.XpdrMath.Xpdr();
        x.caution(100);
        boolean lit = x.idCaution(110);
        x.identified(130);
        boolean out = !x.idCaution(140);
        x.caution(100);
        boolean stale = !x.idCaution(150);
        x.caution(160);
        check(lit && out && stale && x.idCaution(170) && x.idCaution(160 + com.sva.airdefense.XpdrMath.ID_CAUTION_TICKS - 1),
                "ad code-only (item 22): the ОПОЗН lamp - lit by a miss, out with a newer match, never relit by an older answer, lit by a newer miss");
        check(CodeOnlyMath.boardingWarn(true, true, false) && !CodeOnlyMath.boardingWarn(true, true, true) && !CodeOnlyMath.boardingWarn(false, true, false)
                && !CodeOnlyMath.boardingWarn(true, false, false),
                "ad code-only (item 22): «Нет карты кодов» on boarding - only the post's owner / list, the mode on, no good card");
        check(CodeOnlyMath.ownerOnly(true, CodeOnlyMath.DEFAULT_OPTS) && !CodeOnlyMath.ownerOnly(true, CodeOnlyMath.OPT_LAUNCHERS_ACCESS)
                && !CodeOnlyMath.ownerOnly(false, CodeOnlyMath.DEFAULT_OPTS),
                "ad code-only (item 25): a code-only post's launchers - the rule and the link the owner's alone, unless the post trusts its list");
        check(CodeOnlyMath.strangerWithKey(RoeMath.ID_KEY, true, true, 0, 1) && !CodeOnlyMath.strangerWithKey(RoeMath.ID_KEY, true, true, 1, 0)
                && !CodeOnlyMath.strangerWithKey(RoeMath.ID_KEY, false, true, 0, 1) && !CodeOnlyMath.strangerWithKey(RoeMath.ID_NO_CARD, true, true, 0, 0)
                && CodeOnlyMath.strangerWithKey(RoeMath.ID_KEY, true, false, 1, 0) && !CodeOnlyMath.strangerWithKey(RoeMath.ID_KEY, true, false, 0, 2),
                "ad code-only (item 30): «верный код у чужого борта» - the key's card flown by a stranger (the owner's own aircraft too), never by hand");
        check(CodeOnlyMath.ownWithoutCode(1, RoeMath.VIOLATOR) && CodeOnlyMath.ownWithoutCode(2, RoeMath.FOREIGN) && !CodeOnlyMath.ownWithoutCode(0, RoeMath.FOREIGN)
                && !CodeOnlyMath.ownWithoutCode(1, RoeMath.UNKNOWN),
                "ad code-only (item 33): «борт ВЛАДЕЛЬЦА» - a target confirmed on the owner's / his list's machine (not a check yet)");
        check(CodeOnlyMath.armedNow(CodeOnlyMath.CO_ARMING, CodeOnlyMath.CO_ON) && !CodeOnlyMath.armedNow(-1, CodeOnlyMath.CO_ON)
                && !CodeOnlyMath.armedNow(CodeOnlyMath.CO_ON, CodeOnlyMath.CO_ON) && !CodeOnlyMath.armedNow(CodeOnlyMath.CO_ARMING, CodeOnlyMath.CO_OFF),
                "ad code-only (item 16): the arming's end told once (ARMING -> ON), never on the first look after a load");
    }

    // ---------------------------------------------------------------- a round as the post / the launcher runs it

    /** One interrogation of the post (identify -> classifyByCode with the track's misses), as AirDefenseSVA.classifyByCode does it. */
    private static int round(CodeOnlyMath.Confirm c, long at, boolean card, int code, int keyNow, boolean manual, boolean violator) {
        int st = card ? CodeOnlyMath.CARD_OK : CodeOnlyMath.CARD_NONE;
        int id = CodeOnlyMath.identify(true, code, keyNow, -1, false, st, card, manual, false, false);
        return RoeMath.idClass(RoeMath.classifyByCode(true, false, false, true, code, -1, false, id, violator, c, at, 100));
    }

    private static void rounds() {
        int key = 01234;
        // the owner's machine (no ownership in it): 7000, no card - «ПРОВ 1/2», then a violator a survey round (120 ticks) later
        CodeOnlyMath.Confirm c = new CodeOnlyMath.Confirm();
        int r1 = round(c, 1000, false, 07000, key, true, true), r1b = round(c, 1000, false, 07000, key, true, true), r2 = round(c, 1120, false, 07000, key, true, true);
        check(r1 == RoeMath.UNKNOWN && r1b == RoeMath.UNKNOWN && r2 == RoeMath.VIOLATOR && !RoeMath.ownBlocked(true, 1, false) && RoeMath.ownBlocked(false, 1, false),
                "ad code-only round: the owner's machine without a card - a check (the same answer twice is one miss), a violator a survey round later, not spared");
        // with the card: a friend at once; the card's code dialled by hand: a friend with «Ручной набор» (decision 1), a check without
        CodeOnlyMath.Confirm d = new CodeOnlyMath.Confirm(), e = new CodeOnlyMath.Confirm(), f = new CodeOnlyMath.Confirm();
        check(round(d, 1000, true, key, key, false, true) == RoeMath.FRIEND && round(e, 1000, false, key, key, true, true) == RoeMath.FRIEND
                && round(f, 1000, false, key, key, false, true) == RoeMath.UNKNOWN,
                "ad code-only round: the card's code - a friend at once (in a zone too); dialled by hand - a friend with «Ручной набор» (default), a check without");
        // the silent look before the shot (item 14): the target confirmed, the card taken since - a match now, the count over
        CodeOnlyMath.Confirm g = new CodeOnlyMath.Confirm();
        round(g, 1000, false, 07000, key, true, false);
        int tgt = round(g, 1120, false, 07000, key, true, false);
        int id = CodeOnlyMath.identify(true, key, key, -1, false, CodeOnlyMath.CARD_OK, true, false, false, false);
        boolean accepted = RoeMath.idMatch(id);
        if (accepted) {
            CodeOnlyMath.hit(g);
        }
        check(tgt == RoeMath.FOREIGN && accepted && !CodeOnlyMath.confirmed(g) && round(g, 1240, true, key, key, false, false) == RoeMath.FRIEND,
                "ad code-only round (item 14): a confirmed target took the card - the look before the shot finds the code, the count starts over, a friend");
        // the launchers' wait (item 13): 20 s for all in the code-only mode whatever the server's minimum below it
        check(RoeMath.waitTicks(2, CodeOnlyMath.minWarning(0, true, 20)) == 400 && RoeMath.waitTicks(2, CodeOnlyMath.minWarning(0, false, 20)) == 40
                && RoeMath.stageTimes(true, false, 400)[2] >= 500,
                "ad code-only launcher (item 13): never under 20 s to fire in the code-only mode (25 s with the message), the usual mode keeps its own");
    }

    // ---------------------------------------------------------------- the commands, the launch log, the zones

    private static void commandsAndLogs() {
        boolean light = true, heavy = true;
        for (String op : new String[] {"emFriend", "emCheck", "emHostile", "unlock"}) {
            light &= CmdRateMath.kind(op) == CmdRateMath.LIGHT;
        }
        for (String op : new String[] {"codeOnly", "codeOpts", "rekey", "coAllRoe"}) {
            heavy &= CmdRateMath.kind(op) == CmdRateMath.HEAVY;
        }
        check(light && heavy, "ad code-only commands: the 7700 decisions and «unlock» are track orders (light); the mode, options, key, «Все ПУ» heavy");
        AirDefenseSVA.Contact t = new AirDefenseSVA.Contact(7);
        String usual = TileEntityLauncherSVA.coTag(t);
        t.codeOnly = true;
        t.rel = 1;
        t.idMiss = RoeMath.ID_NO_CARD;
        String own = TileEntityLauncherSVA.coTag(t);
        t.rel = 0;
        t.idMiss = RoeMath.ID_NO_REPLY;
        String str = TileEntityLauncherSVA.coTag(t);
        check(usual.length() == 0 && own.contains("codeOnly") && own.contains("own=owner") && own.contains("reason=noCard") && str.contains("own=no")
                && str.contains("reason=noReply"), "ad code-only launch log (item 40): the fire line's marks - codeOnly, whose machine and why (none in the usual mode)");
        check(t.em == -1 && !new AirDefenseSVA.Contact(1).fromCard && new AirDefenseSVA.Contact(1).cardUntil == -1,
                "ad code-only interrogation: a new contact - no 7700 state, no card");
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "N";
        z.type = ZoneMath.AERODROME;
        z.codeLo = 01000;
        z.codeHi = 01077;
        NBTTagCompound on = AirDefenseSVA.publicZone(z, true), off = AirDefenseSVA.publicZone(z);
        check(on.getBoolean("CO") && !off.hasKey("CO") && !on.hasKey("CodeLo") && !on.hasKey("CodeHi") && "N".equals(on.getString("Name")),
                "ad code-only zones (item 22): a code-only post's zones go to the clients marked «CO», still without their codes");
    }

    // ---------------------------------------------------------------- the lang

    private static void lang() throws Exception {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        java.util.List<String> keys = new java.util.ArrayList<String>(java.util.Arrays.asList("sva.ad.msg.noCard", "sva.ad.msg.cardExpires",
                "sva.ad.msg.coOwnerOnly", "sva.ad.msg.coNoKey", "sva.ad.msg.coBanned", "sva.ad.msg.coConfirm", "sva.ad.msg.coArming", "sva.ad.msg.coOn",
                "sva.ad.msg.coOff", "sva.ad.msg.coByOp", "sva.ad.msg.coOwnNoCode", "sva.ad.msg.rekey", "sva.ad.msg.warnCode", "sva.ad.msg.warnZoneCode",
                "sva.ad.log.coArming", "sva.ad.log.coOn", "sva.ad.log.coOff", "sva.ad.log.coByOp", "sva.ad.log.coOpts", "sva.ad.log.rekey",
                "sva.ad.log.coAllRoe", "sva.ad.log.coStranger", "sva.ad.log.coProbe", "sva.ad.log.coUnlock", "sva.ad.log.coAccepted", "sva.ad.log.emGrace",
                "sva.ad.log.emOver", "sva.ad.log.emFriend", "sva.ad.log.emCheck", "sva.ad.log.emHostile",
                // review 2026-10-27: the arming done anew, 7700 held before fire, the boarding notes, the panel's key owner
                "sva.ad.log.coRearm", "sva.ad.msg.coRearm", "sva.ad.log.coEmHold", "sva.ad.msg.coKeyOff", "sva.ad.msg.coOtherCard",
                "sva.ad.xpdr.keyOwner", "sva.ad.xpdr.keyOwnerTip"));
        for (String k : CodeOnlyMath.PILOT_KEYS) {
            keys.add("sva.ad.co.pilot." + k);
            keys.add("sva.ad.log.coMiss." + k);
            keys.add("sva.ad.log.coMissOwn." + k);
        }
        for (String k : RoeMath.ID_KEYS) {
            keys.add("sva.ad.idr." + k);
        }
        for (String k : CodeOnlyMath.READY_KEYS) {
            keys.add("sva.ad.co.ready." + k);
        }
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "ad code-only lang: the post's, the launchers' and the pilot's lines and the families (reasons, misses, readiness) in ru and en"
                + miss);
        java.util.Map<String, String> rv = values(LangKeysSVA.RU), ev = values(LangKeysSVA.EN);
        StringBuilder bad = new StringBuilder();
        for (String k : keys) {
            String a = rv.get(k), b = ev.get(k);
            if (a == null || b == null || count(a) != count(b) || !GlyphSafe.ok(a) || !GlyphSafe.ok(b)) {
                bad.append(' ').append(k);
            }
        }
        check(bad.length() == 0, "ad code-only lang: ru and en take the same arguments, plain glyphs only (no guillemets, arrows, special spaces)" + bad);
    }

    private static int count(String s) {
        int n = 0;
        for (int i = s.indexOf("%s"); i >= 0; i = s.indexOf("%s", i + 2)) {
            n++;
        }
        return n;
    }

    private static java.util.Map<String, String> values(String path) throws Exception {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        java.io.InputStream in = SelfTestAdCodeKpSVA.class.getResourceAsStream(path);
        if (in == null) {
            return m;
        }
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) {
                    m.put(line.substring(0, eq).trim(), line.substring(eq + 1));
                }
            }
        } finally {
            r.close();
        }
        return m;
    }

    /** The glyphs the game's font draws for sure: printable ASCII, Cyrillic, the section sign of the colour codes. */
    static final class GlyphSafe {
        private GlyphSafe() {
        }

        static boolean ok(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!(c >= 0x20 && c < 0x7F || c >= 0x0410 && c <= 0x044F || c == 0x0401 || c == 0x0451 || c == 0xA7)) {
                    return false;
                }
            }
            return true;
        }
    }
}
