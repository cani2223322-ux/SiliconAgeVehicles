package com.sva.debug;

import java.util.List;
import java.util.Map;

import com.sva.airdefense.AdLayoutMath;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.CodeOnlyUiMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.ZoneMath;
import com.sva.inventory.ContainerLauncherSVA;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Code-only wave self-test, the screens (proposal-ad-strict-iff section 6, items 38-42, and the screen parts of 24, 27, 32):
 * CodeOnlyUiMath - pure, the dedicated server runs it (the client classes draw with it):
 * <ul>
 * <li>the tracks' tags for every verdict reason (words for КОД✓ / КАРТА✗ - the font has neither), their colours, «ПРОВ n/2»,
 * the grey ВЛ / ДР, the 7700 clock; the form's status and the reason it names;</li>
 * <li>the post's header plate and «смена ключа через N мин», the five tabs (no overlap, room for the server's mode line),
 * the confirmation window (its buttons inside it, apart, under the text), its summary, «Сменить ключ» in two presses;</li>
 * <li>the decision rows (7600 and the code-only 7700 - the post's commands), the options' and the cards' term's buttons;</li>
 * <li>the launcher's banner and its rules' labels in the mode, the window's new rows over the slots;</li>
 * <li>the ОПОЗН lamp (flashing, its tone once, the RWR panel's fourth lamp lit without the module), the card's HUD line;</li>
 * <li>the log's colours for the new lines, the zones' «ПО КОДУ» mark on the client (not saved), every lang family the
 * screens build at run time in ru and en, all of it glyph-safe (ASCII and Cyrillic only - no tick, cross, guillemets, arrows).</li>
 * </ul>
 */
final class SelfTestAdCodeUiSVA {

    private SelfTestAdCodeUiSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        tags();
        form();
        plateAndTabs();
        dialog();
        decisions();
        launcher();
        cockpit();
        logAndZones();
        lang();
    }

    // ---------------------------------------------------------------- the tracks' tags (item 39)

    private static void tags() {
        boolean range = true;
        for (int id = 0; id < RoeMath.IDS; id++) {
            int t = CodeOnlyUiMath.tag(true, id);
            range &= t >= 0 && t < CodeOnlyUiMath.TAGS && CodeOnlyUiMath.tag(false, id) == CodeOnlyUiMath.TAG_NONE;
            // a match is green, a miss red - never the other way round
            range &= !RoeMath.idMatch(id) || CodeOnlyUiMath.tagColour(t) == CodeOnlyUiMath.TC_OK;
            range &= !RoeMath.idMiss(id) || CodeOnlyUiMath.tagColour(t) == CodeOnlyUiMath.TC_MISS;
        }
        check(range && CodeOnlyUiMath.TAG_KEYS.length == CodeOnlyUiMath.TAGS, "ad code ui: every verdict reason has a tag in range (none outside the mode), "
                + "a match's green, a miss's red");
        check(CodeOnlyUiMath.tag(true, RoeMath.ID_KEY) == CodeOnlyUiMath.TAG_KEY && CodeOnlyUiMath.tag(true, RoeMath.ID_NO_CARD) == CodeOnlyUiMath.TAG_NO_CARD
                && CodeOnlyUiMath.tag(true, RoeMath.ID_NO_REPLY) == CodeOnlyUiMath.TAG_NO_REPLY && CodeOnlyUiMath.tag(true, RoeMath.ID_CHECK) == CodeOnlyUiMath.TAG_CHECK
                && CodeOnlyUiMath.tag(true, RoeMath.ID_EMERGENCY) == CodeOnlyUiMath.TAG_EMERGENCY
                && CodeOnlyUiMath.tag(true, RoeMath.ID_PROBE) == CodeOnlyUiMath.TAG_PROBE
                && CodeOnlyUiMath.tag(true, RoeMath.ID_GRANTED) == CodeOnlyUiMath.TAG_NONE && CodeOnlyUiMath.tag(true, RoeMath.ID_NO_RADIO) == CodeOnlyUiMath.TAG_NONE
                && CodeOnlyUiMath.tagColour(CodeOnlyUiMath.TAG_CHECK) == CodeOnlyUiMath.TC_WAIT
                && CodeOnlyUiMath.tagColour(CodeOnlyUiMath.TAG_EMERGENCY) == CodeOnlyUiMath.TC_WAIT
                && CodeOnlyUiMath.tagColour(CodeOnlyUiMath.TAG_PROBE) == CodeOnlyUiMath.TC_MISS
                && CodeOnlyUiMath.tagColour(CodeOnlyUiMath.TAG_NONE) == CodeOnlyUiMath.TC_NONE,
                "ad code ui (item 39): КОД ОК / НЕТ КАРТЫ / НЕТ ОТВ / ПРОВ / 7700 / ПОДБОР by the reason; «свой» and 7600 keep their own marks");
        check(CodeOnlyUiMath.checkCount(0) == 1 && CodeOnlyUiMath.checkCount(1) == 1 && CodeOnlyUiMath.checkCount(2) == 2 && CodeOnlyUiMath.checkCount(9) == 2,
                "ad code ui: «ПРОВ n/2» counts 1..2");
        check(CodeOnlyUiMath.relTag(true, 1) == CodeOnlyUiMath.REL_OWN && CodeOnlyUiMath.relTag(true, 2) == CodeOnlyUiMath.REL_LIST
                && CodeOnlyUiMath.relTag(true, 0) == CodeOnlyUiMath.REL_NONE && CodeOnlyUiMath.relTag(false, 1) == CodeOnlyUiMath.REL_NONE,
                "ad code ui (item 33): the grey ВЛ / ДР only in the mode, only the owner's / his list's machines");
        check("1:20".equals(CodeOnlyUiMath.clock(80)) && "0:05".equals(CodeOnlyUiMath.clock(5)) && "3:00".equals(CodeOnlyUiMath.clock(180))
                && "0:00".equals(CodeOnlyUiMath.clock(-3)), "ad code ui: the 7700 clock (1:20, 0:05, 3:00; never negative)");
    }

    private static void form() {
        int miss = CodeOnlyUiMath.formStatus(true, RoeMath.FOREIGN, RoeMath.ID_NO_CARD), chk = CodeOnlyUiMath.formStatus(true, RoeMath.UNKNOWN, RoeMath.ID_CHECK);
        int ok = CodeOnlyUiMath.formStatus(true, RoeMath.FRIEND, RoeMath.ID_KEY), em = CodeOnlyUiMath.formStatus(true, RoeMath.EMERGENCY, RoeMath.ID_EMERGENCY);
        check(miss == CodeOnlyUiMath.FS_MISS && chk == CodeOnlyUiMath.FS_CHECK && ok == CodeOnlyUiMath.FS_OK && em == CodeOnlyUiMath.FS_EMERGENCY
                && CodeOnlyUiMath.formStatus(true, RoeMath.UNKNOWN, RoeMath.ID_NOT_ASKED) == CodeOnlyUiMath.FS_WAIT
                && CodeOnlyUiMath.formStatus(true, RoeMath.FRIEND, RoeMath.ID_GRANTED) == CodeOnlyUiMath.FS_GRANT
                && CodeOnlyUiMath.formStatus(true, RoeMath.NO_RADIO, RoeMath.ID_NO_RADIO) == CodeOnlyUiMath.FS_NO_RADIO
                && CodeOnlyUiMath.formStatus(false, RoeMath.FOREIGN, RoeMath.ID_NO_CARD) == CodeOnlyUiMath.FS_NONE,
                "ad code ui (item 39): the form's status - не опознан / проверка / опознан / авария / не опрошен / свой по решению КП / 7600");
        check(CodeOnlyUiMath.formReason(miss, RoeMath.ID_NO_CARD, RoeMath.ID_NO_CARD) == RoeMath.ID_NO_CARD
                && CodeOnlyUiMath.formReason(chk, RoeMath.ID_CHECK, RoeMath.ID_NO_REPLY) == RoeMath.ID_NO_REPLY
                && CodeOnlyUiMath.formReason(ok, RoeMath.ID_KEY, RoeMath.ID_NONE) == RoeMath.ID_KEY
                && CodeOnlyUiMath.formReason(CodeOnlyUiMath.FS_GRANT, RoeMath.ID_GRANTED, RoeMath.ID_NONE) == RoeMath.ID_NONE
                && CodeOnlyUiMath.statusColour(miss) == CodeOnlyUiMath.TC_MISS && CodeOnlyUiMath.statusColour(ok) == CodeOnlyUiMath.TC_OK
                && CodeOnlyUiMath.statusColour(chk) == CodeOnlyUiMath.TC_WAIT,
                "ad code ui: the form names the miss behind «проверка» («нет ответа»), the reason of a verdict, nothing after «свой по решению КП»");
    }

    // ---------------------------------------------------------------- the plate, the tabs (items 24, 38)

    private static void plateAndTabs() {
        check(CodeOnlyUiMath.plate(CodeOnlyMath.CO_OFF) == CodeOnlyUiMath.PL_NONE && CodeOnlyUiMath.plate(CodeOnlyMath.CO_ON) == CodeOnlyUiMath.PL_ON
                && CodeOnlyUiMath.plate(CodeOnlyMath.CO_ARMING) == CodeOnlyUiMath.PL_ARMING && CodeOnlyUiMath.plate(CodeOnlyMath.CO_BANNED) == CodeOnlyUiMath.PL_BANNED
                && CodeOnlyUiMath.plate(CodeOnlyMath.CO_NO_KEY) == CodeOnlyUiMath.PL_NO_KEY && CodeOnlyUiMath.PL_KEYS.length == 5,
                "ad code ui (item 38): the plate - none / ПО КОДУ / ВКЛЮЧЕНИЕ ЧЕРЕЗ / запрещено сервером / нет ключа дня");
        check(CodeOnlyUiMath.minutes(0) == 0 && CodeOnlyUiMath.minutes(1) == 1 && CodeOnlyUiMath.minutes(1200) == 1 && CodeOnlyUiMath.minutes(1201) == 2
                && CodeOnlyUiMath.minutes(12 * 1200) == 12, "ad code ui: «смена ключа через N мин» rounds up (never 0 while time is left)");
        int[][] t = CodeOnlyUiMath.tabs();
        boolean apart = t.length == CodeOnlyUiMath.TABS && CodeOnlyUiMath.TAB_KEYS.length == CodeOnlyUiMath.TABS;
        for (int i = 1; i < t.length; i++) {
            apart &= t[i][0] >= t[i - 1][0] + t[i - 1][1];
        }
        int room = 420 - 6 - CodeOnlyUiMath.tabsRight() - 4;
        check(apart && t[0][0] == 6 && room >= 110 && CodeOnlyUiMath.TAB_EQUIP == CodeOnlyUiMath.TABS - 1 && CodeOnlyUiMath.mapTab(CodeOnlyUiMath.TAB_MAP)
                && CodeOnlyUiMath.mapTab(CodeOnlyUiMath.TAB_ZONES) && !CodeOnlyUiMath.mapTab(CodeOnlyUiMath.TAB_SETTINGS)
                && !CodeOnlyUiMath.mapTab(CodeOnlyUiMath.TAB_IDENT), "ad code ui (item 24): five tabs apart (+ «Опознавание»), " + room
                        + " px left for the server's mode line, the map only on «Карта» / «Зоны»");
        check(CodeOnlyUiMath.optEnabled(CodeOnlyMath.OPT_MANUAL, true, true) && !CodeOnlyUiMath.optEnabled(CodeOnlyMath.OPT_MANUAL, true, false)
                && CodeOnlyUiMath.optEnabled(CodeOnlyMath.OPT_AIRPORT, true, false) && !CodeOnlyUiMath.optEnabled(CodeOnlyMath.OPT_OWN_CODES, false, true)
                && CodeOnlyUiMath.optOn(CodeOnlyMath.DEFAULT_OPTS, CodeOnlyMath.OPT_MANUAL, true)
                && !CodeOnlyUiMath.optOn(CodeOnlyMath.DEFAULT_OPTS, CodeOnlyMath.OPT_MANUAL, false)
                && !CodeOnlyUiMath.optOn(CodeOnlyMath.DEFAULT_OPTS, CodeOnlyMath.OPT_OWN_CODES, true),
                "ad code ui (item 24): the options are the owner's; «Ручной набор кода» on by default (decision 1), off and greyed while the server forbids it");
        check(CodeOnlyUiMath.periodAllowed(1, 7) && CodeOnlyUiMath.periodAllowed(7, 7) && !CodeOnlyUiMath.periodAllowed(7, 3) && CodeOnlyUiMath.periodAllowed(3, 3)
                && CodeOnlyUiMath.periodAllowed(1, 0), "ad code ui: the cards' term buttons within the server's codeCardMaxPeriods (1 always)");
        long a = 1000;
        check(!CodeOnlyUiMath.confirmed(-1, a) && CodeOnlyUiMath.confirmed(a, a + 10) && CodeOnlyUiMath.confirmed(a, a + CodeOnlyUiMath.CONFIRM_TICKS)
                && !CodeOnlyUiMath.confirmed(a, a + CodeOnlyUiMath.CONFIRM_TICKS + 1) && !CodeOnlyUiMath.confirmed(a, a - 5),
                "ad code ui (item 24): «Сменить ключ» - the second press within 5 s sends it, a late one starts over");
    }

    // ---------------------------------------------------------------- the confirmation window (item 27)

    private static void dialog() {
        int left = 50, top = 20, w = 420, h = 266;
        int[][] d = CodeOnlyUiMath.dialog(left, top, w, h);
        boolean inside = d.length == 4 && d[0][0] >= left && d[0][2] <= left + w && d[0][1] >= top && d[0][3] <= top + h;
        for (int i = 1; i < d.length; i++) {
            int[] b = d[i];
            inside &= b[0] >= d[0][0] + 4 && b[0] + b[2] <= d[0][2] - 4 && b[1] + b[3] <= d[0][3] - 2 && b[2] > 40;
            // the text has room above: the title and seven 10 px lines
            inside &= b[1] >= d[0][1] + 5 + 13 + 7 * 10;
            if (i > 1) {
                inside &= b[0] >= d[i - 1][0] + d[i - 1][2] + 2 && b[1] == d[i - 1][1];
            }
        }
        check(inside, "ad code ui (item 27): the confirmation window inside the post's screen, «Включить» / «Все ПУ: по всем без кода» / «Отмена» in a row "
                + "inside it, apart, under room for the summary");
        List<int[]> l = CodeOnlyUiMath.summaryRoes(new int[] {1, 2, 1, 0, 3, 1});
        check(l.size() == 3 && l.get(0)[0] == RoeMath.VIOLATORS && l.get(0)[1] == 2 && l.get(1)[0] == RoeMath.FOREIGN_ALL && l.get(1)[1] == 1
                && l.get(2)[0] == RoeMath.HOLD && CodeOnlyUiMath.summaryRoes(null).isEmpty() && CodeOnlyUiMath.summaryRoes(new int[] {0, 0, 0, 0, 0, 0}).isEmpty()
                && CodeOnlyUiMath.withoutCode(new int[] {1, 2, 1, 0, 3, 1})[0] == 3 && CodeOnlyUiMath.withoutCode(new int[] {1, 2, 1, 0, 3, 1})[1] == 1
                && CodeOnlyUiMath.withoutCode(new int[] {1, 2})[0] == 0, "ad code ui (item 27): the summary «ПУ: 2 - по нарушителям, 1 - по всем, 1 - огонь "
                        + "запрещён; без кода 3, из них ваших 1» - zero rules skipped, an older picture gives nothing");
    }

    // ---------------------------------------------------------------- the decisions (items 7, 39)

    private static void decisions() {
        check(CodeOnlyUiMath.decision(NoRadioMath.S_PENDING, false, -1) == CodeOnlyUiMath.DEC_NO_RADIO
                && CodeOnlyUiMath.decision(-1, true, NoRadioMath.S_PENDING) == CodeOnlyUiMath.DEC_EMERGENCY
                && CodeOnlyUiMath.decision(-1, false, NoRadioMath.S_PENDING) == CodeOnlyUiMath.DEC_NONE
                && CodeOnlyUiMath.decision(-1, true, NoRadioMath.S_CHECK) == CodeOnlyUiMath.DEC_NONE
                && CodeOnlyUiMath.decision(-1, true, -1) == CodeOnlyUiMath.DEC_NONE,
                "ad code ui (item 7): the decision rows - 7600 waiting, a code-only post's 7700 in its grace; decided / the usual mode's 7700 not");
        String[] known = {"nrFriend", "nrCheck", "nrHostile", "emFriend", "emCheck", "emHostile"};
        boolean ops = CodeOnlyUiMath.DEC_OPS.length == 3 && CodeOnlyUiMath.DEC_OPS[0].length == 0;
        int k = 0;
        for (int kind = 1; kind <= 2; kind++) {
            for (String op : CodeOnlyUiMath.DEC_OPS[kind]) {
                ops &= op.equals(known[k++]);
            }
        }
        // a 7700 row «07 7700 1:20» (~60 px) leaves the three buttons room on the map's 200 px panel
        int[][] b = AdLayoutMath.nrButtons(10 + 3 + 66, 210);
        boolean fit = true;
        for (int[] r : b) {
            fit &= r[1] >= 24 && r[0] + r[1] <= 210;
        }
        check(ops && fit && k == 6, "ad code ui (item 39): the rows send the post's commands (7600: nr*, 7700: em* «Свой / Проверить / Враждебный»), "
                + "their buttons fit right of «07 7700 1:20»");
    }

    // ---------------------------------------------------------------- the launcher (item 41)

    private static void launcher() {
        check(CodeOnlyUiMath.banner("", CodeOnlyMath.CO_ON) == CodeOnlyUiMath.BN_NO_LINK && CodeOnlyUiMath.banner(null, CodeOnlyMath.CO_OFF) == CodeOnlyUiMath.BN_NO_LINK
                && CodeOnlyUiMath.banner("Север", CodeOnlyMath.CO_ON) == CodeOnlyUiMath.BN_ON
                && CodeOnlyUiMath.banner("Север", CodeOnlyMath.CO_OFF) == CodeOnlyUiMath.BN_NORMAL
                && CodeOnlyUiMath.banner("Север", CodeOnlyMath.CO_ARMING) == CodeOnlyUiMath.BN_ARMING
                && CodeOnlyUiMath.banner("Север", CodeOnlyMath.CO_BANNED) == CodeOnlyUiMath.BN_BANNED
                && CodeOnlyUiMath.banner("Север", CodeOnlyMath.CO_NO_KEY) == CodeOnlyUiMath.BN_NO_KEY,
                "ad code ui (item 41): the launcher's banner - «КП Север: ПО КОДУ» / обычное / через N с / запрещено; no link - «обычное (нет привязки)»");
        boolean labels = true;
        for (int r = 0; r < RoeMath.ROES; r++) {
            labels &= CodeOnlyUiMath.roeKey(r, false).equals(RoeMath.ROE_KEYS[r]) && CodeOnlyUiMath.roeKey(r, true).equals("co." + RoeMath.ROE_KEYS[r]);
        }
        check(labels && CodeOnlyUiMath.strictLabels(CodeOnlyMath.CO_ON) && CodeOnlyUiMath.strictLabels(CodeOnlyMath.CO_ARMING)
                && !CodeOnlyUiMath.strictLabels(CodeOnlyMath.CO_BANNED) && !CodeOnlyUiMath.strictLabels(CodeOnlyMath.CO_OFF),
                "ad code ui (item 12): the rules' labels in the mode (and while arming) - «По всем без кода (и ваши)»; the usual ones otherwise");
        // the banner (y 120) and the readiness line (y 133) over the slots' labels; review batch 3: the slots in columns beside
        // the inventory - the window 240 px again (SelfTestAdCodeReview3SVA checks the columns)
        check(ContainerLauncherSVA.SLOT_Y - 11 >= 133 + 9 && ContainerLauncherSVA.INV_Y >= ContainerLauncherSVA.SLOT_Y
                && ContainerLauncherSVA.INV_Y + 58 + 18 <= 240, "ad code ui (item 41): the launcher's banner and readiness rows over the slots, "
                        + "the inventory inside the 240 px window");
    }

    // ---------------------------------------------------------------- the cockpits (item 42)

    private static void cockpit() {
        check(CodeOnlyUiMath.idLampOn(true, 0, false) && !CodeOnlyUiMath.idLampOn(true, CodeOnlyUiMath.ID_BLINK_MS, false)
                && CodeOnlyUiMath.idLampOn(true, 2 * CodeOnlyUiMath.ID_BLINK_MS + 5, false) && !CodeOnlyUiMath.idLampOn(false, 0, false)
                && CodeOnlyUiMath.idLampOn(false, CodeOnlyUiMath.ID_BLINK_MS, true), "ad code ui (item 42): the ОПОЗН lamp flashes; the lamp test lights it steady");
        check(CodeOnlyUiMath.idLampSound(false, true) && !CodeOnlyUiMath.idLampSound(true, true) && !CodeOnlyUiMath.idLampSound(true, false)
                && !CodeOnlyUiMath.idLampSound(false, false), "ad code ui (item 22): the lamp's tone once, as it lights");
        int s = RadarMath.LAMP_SEARCH, l = RadarMath.LAMP_LAUNCH;
        check(CodeOnlyUiMath.rwrPanelLamps(s | l, true, true, true, false) == (s | l | CodeOnlyUiMath.LAMP_ID)
                && CodeOnlyUiMath.rwrPanelLamps(s, false, true, true, false) == CodeOnlyUiMath.LAMP_ID
                && CodeOnlyUiMath.rwrPanelLamps(s, true, false, true, false) == s && CodeOnlyUiMath.rwrPanelLamps(0, false, true, false, true) == 15
                && CodeOnlyUiMath.rwrPanelLamps(0xFF, true, true, false, false) == 7 && CodeOnlyUiMath.RWR_PANEL_LAMPS == 4
                && CodeOnlyUiMath.LAMP_ID == 1 << 3, "ad code ui (item 42): the RWR panel's fourth lamp ОПОЗН - the transponder's: lit without the RWR "
                        + "module, dark without power; the test lights all four");
        check(CodeOnlyUiMath.cardLine(5) == 5 && CodeOnlyUiMath.cardLine(1) == 1 && CodeOnlyUiMath.cardLine(0) == 0 && CodeOnlyUiMath.cardLine(7) == 0
                && CodeOnlyUiMath.cardLine(CodeOnlyMath.cardWarnMinutes(4 * 1200)) == 5 && CodeOnlyUiMath.cardLine(CodeOnlyMath.cardWarnMinutes(30 * 20)) == 1
                && CodeOnlyUiMath.cardLine(CodeOnlyMath.cardWarnMinutes(20 * 1200)) == 0, "ad code ui (item 22): «КАРТА КОДОВ: 5 / 1 МИН» from the server's minutes");
    }

    // ---------------------------------------------------------------- the log, the zones (items 22, 40)

    private static void logAndZones() throws Exception {
        check(CodeOnlyUiMath.logKind("sva.ad.log.coMiss.noCard") == CodeOnlyUiMath.TC_MISS && CodeOnlyUiMath.logKind("sva.ad.log.coMissOwn.noReply")
                == CodeOnlyUiMath.TC_MISS && CodeOnlyUiMath.logKind("sva.ad.log.coArming") == CodeOnlyUiMath.TC_WAIT
                && CodeOnlyUiMath.logKind("sva.ad.log.coAccepted") == CodeOnlyUiMath.TC_OK && CodeOnlyUiMath.logKind("sva.ad.log.emergency") == CodeOnlyUiMath.TC_MISS
                && CodeOnlyUiMath.logKind("sva.ad.log.grant") == CodeOnlyUiMath.TC_OK && CodeOnlyUiMath.logKind("sva.ad.log.newDay") == CodeOnlyUiMath.TC_NONE
                && CodeOnlyUiMath.logKind("sva.ad.log.printedN") == CodeOnlyUiMath.TC_OK && CodeOnlyUiMath.logKind(null) == CodeOnlyUiMath.TC_NONE,
                "ad code ui (item 40): the log's colours - «не опознан» red, «взводится» amber, «код принят перед огнём» / «напечатал» green, the old ones kept");
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU);
        StringBuilder plain = new StringBuilder();
        // the families' prefixes built at run time: a literal "sva.ad.log.co" would read as a missing key to the lang test
        String log = "sva.ad.log.", co = log + "co", em = log + "em";
        for (String k : ru.keySet()) {
            if ((k.startsWith(co) || k.startsWith(em) && !k.equals("sva.ad.log.emergency") || k.equals("sva.ad.log.rekey"))
                    && CodeOnlyUiMath.logKind(k) == CodeOnlyUiMath.TC_NONE) {
                plain.append(' ').append(k);
            }
        }
        check(plain.length() == 0, "ad code ui (item 40): every code-only / 7700 log line has its colour" + plain);
        ZoneMath.Zone z = new ZoneMath.Zone();
        z.name = "3-1";
        NBTTagCompound pub = AirDefenseSVA.publicZone(z, true), saved = new NBTTagCompound();
        z.codeOnly = true;
        z.write(saved);
        ZoneMath.Zone back = new ZoneMath.Zone();
        back.read(pub);
        check(pub.getBoolean("CO") && !saved.hasKey("CO") && !z.copy().codeOnly && !back.codeOnly && !AirDefenseSVA.publicZone(z, false).hasKey("CO"),
                "ad code ui (item 22): a code-only post's zone reaches the client marked «CO» (the client sets codeOnly from it); the mark is never saved "
                        + "nor copied with the zone");
    }

    // ---------------------------------------------------------------- the lang

    private static boolean safe(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (!(c >= ' ' && c < 127 || c >= 'Ѐ' && c <= 'ӿ' || c == '§')) {
                return false;
            }
        }
        return true;
    }

    private static void lang() throws Exception {
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (String k : CodeOnlyUiMath.TAG_KEYS) {
            keys.add("sva.ad.co.tag." + k);
        }
        for (String k : CodeOnlyUiMath.REL_KEYS) {
            keys.add("sva.ad.co.rel." + k);
            keys.add("sva.ad.co.relWord." + k);
        }
        for (String k : CodeOnlyUiMath.FS_KEYS) {
            keys.add("sva.ad.co.fs." + k);
        }
        for (String k : CodeOnlyUiMath.PL_KEYS) {
            keys.add("sva.ad.co.plate." + k);
        }
        for (String k : CodeOnlyMath.CO_KEYS) {
            keys.add("sva.ad.co.state." + k);
        }
        for (String k : CodeOnlyMath.OPT_KEYS) {
            keys.add("sva.ad.co.set.opt." + k);
            keys.add("sva.ad.co.set.opt." + k + ".tip");
        }
        for (int p : CodeOnlyMath.CARD_PERIODS) {
            keys.add("sva.ad.co.set.period." + p);
        }
        for (String k : RoeMath.ROE_KEYS) {
            keys.add("sva.ad.co.dlg.roe." + k);
            keys.add("sva.ad.roe.co." + k);
            keys.add("sva.ad.roe.co." + k + ".tip");
        }
        for (String k : CodeOnlyUiMath.BN_KEYS) {
            keys.add("sva.ad.co.banner." + k);
        }
        for (String k : CodeOnlyUiMath.TAB_KEYS) {
            keys.add("sva.ad.kp.tab." + k);
        }
        for (String k : RoeMath.ID_KEYS) {
            keys.add("sva.ad.idr." + k);
        }
        for (String k : CodeOnlyMath.READY_KEYS) {
            keys.add("sva.ad.co.ready." + k);
        }
        keys.add("sva.ad.rwr.lamp.id");
        StringBuilder miss = new StringBuilder(), bad = new StringBuilder();
        for (String k : keys) {
            String r = ru.get(k), e = en.get(k);
            if (r == null || e == null) {
                miss.append(' ').append(k);
            } else if (!safe(r) || !safe(e)) {
                bad.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "ad code ui lang: the " + keys.size() + " keys the screens build at run time in ru and en" + miss);
        // every value this wave added (sva.ad.co.*, the tags, the lamps, the HUD lines) - no tick, cross, guillemets, arrows, stars
        String menu = "sva.ad.kp.menu.", menuEm = menu + "em";
        for (Map<String, String> m : new Map[] {ru, en}) {
            for (Map.Entry<String, String> x : m.entrySet()) {
                String k = x.getKey();
                if ((k.startsWith("sva.ad.co.") || k.startsWith("sva.ad.roe.co.") || k.equals("sva.ad.hud.idCaution") || k.equals("sva.ad.hud.card")
                        || k.equals("sva.ad.xpdr.idLamp") || k.equals("sva.ad.xpdr.idTip") || k.equals("sva.liner.du.idCaution") || k.equals("sva.ad.zone.codeOnly")
                        || k.equals("sva.ad.l.alwaysCo") || k.equals("sva.ad.l.targetWhy") || k.startsWith(menuEm)) && !safe(x.getValue())) {
                    bad.append(' ').append(k);
                }
            }
        }
        check(bad.length() == 0, "ad code ui lang: glyph-safe - ASCII and Cyrillic only (the proposal's КОД✓ / КАРТА✗ are words here)" + bad);
        String t = ru.get("sva.ad.co.tag.noCard"), tk = ru.get("sva.ad.co.tag.key"), lamp = ru.get("sva.ad.xpdr.idLamp"), hud = ru.get("sva.ad.hud.idCaution");
        check("НЕТ КАРТЫ".equals(t) && "КОД ОК".equals(tk) && "ОПОЗН".equals(lamp) && "ОПОЗН: НЕТ".equals(hud)
                && ru.get("sva.ad.roe.co.foreign").contains("(и ваши)") && ru.get("sva.ad.co.plate.on").startsWith("ПО КОДУ")
                && ru.get("sva.ad.co.banner.tip").contains("Сломанный КП") && ru.get("sva.ad.co.dlg.warn").contains("по вашим машинам"),
                "ad code ui lang: «НЕТ КАРТЫ», «КОД ОК», the lamp «ОПОЗН», «ОПОЗН: НЕТ», «По всем без кода (и ваши)», the plate, the launcher says a broken post "
                        + "drops it back to the usual mode, the window warns «по вашим машинам»");
    }
}
