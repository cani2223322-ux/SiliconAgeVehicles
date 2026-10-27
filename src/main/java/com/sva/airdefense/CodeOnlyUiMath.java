package com.sva.airdefense;

import java.util.ArrayList;
import java.util.List;

/**
 * Code-only wave, the screens (docs/proposal-ad-strict-iff.md section 6, items 38-42, and the screen parts of 24, 27, 32):
 * what the command post's, the launcher's and the cockpits' screens show of the «ПО КОДУ» mode - pure, the self-test
 * (SelfTestAdCodeUiSVA) checks it; the client classes draw with it (the dedicated server cannot load them).
 * <ul>
 * <li>the post's map header plate (item 38): «ПО КОДУ - смена ключа через 12 мин», while arming «ВКЛЮЧЕНИЕ ЧЕРЕЗ 45 с»,
 * forbidden by the server / no day key - grey;</li>
 * <li>the tracks' tags (item 39) - what the proposal wrote as КОД✓ / КАРТА✗: the 1.7.10 font has no tick nor cross, so the
 * tags are words («КОД ОК», «НЕТ КАРТЫ», «НЕТ ОТВ», «ПРОВ 1/2», «7700 1:20», «ПОДБОР») - and the grey ВЛ / ДР of the
 * owner's and his list's machines (information only: it changes nothing about the fire);</li>
 * <li>the track form's «Опознавание» row (status - reason, whose board), the 7700 decision rows beside the 7600 ones (the
 * same AdLayoutMath.nrButtons), the post's five tabs, the confirmation window's layout and its summary (item 27), the
 * «Сменить ключ» two-step;</li>
 * <li>the launcher's banner («КП Север: ПО КОДУ» / «обычное (нет привязки)») and its rules' labels in the mode («По всем без
 * кода (и ваши)», item 41);</li>
 * <li>the cockpits (item 42): the ОПОЗН lamp's flashing, its sound once when it lights, the RWR panel's fourth lamp (the
 * transponder's ОПОЗН - lit without the RWR module too: it is the transponder's, not the receiver's).</li>
 * </ul>
 */
public final class CodeOnlyUiMath {

    // ------------------------------------------------------------------ the tracks' tags (item 39)

    /** A code-only track's tag on the map's data block and in the track list (lang sva.ad.co.tag.*). */
    public static final int TAG_NONE = 0, TAG_KEY = 1, TAG_OWN_CODE = 2, TAG_AIRPORT = 3, TAG_CHECK = 4, TAG_NO_REPLY = 5, TAG_NO_CARD = 6,
            TAG_EXPIRED = 7, TAG_OLD_KEY = 8, TAG_WRONG = 9, TAG_EMERGENCY = 10, TAG_EM_OVER = 11, TAG_PROBE = 12, TAG_HIJACK = 13,
            TAG_NOT_ASKED = 14, TAG_NR_OVER = 15, TAGS = 16;
    public static final String[] TAG_KEYS = {"none", "key", "ownCode", "airport", "check", "noReply", "noCard", "expired", "oldKey", "wrong",
        "emergency", "emOver", "probe", "hijack", "notAsked", "nrOver"};
    /** A tag's colour kind: none, a match (green), on its way (amber: «ПРОВ», 7700 in its grace, not asked), a miss (red). */
    public static final int TC_NONE = 0, TC_OK = 1, TC_WAIT = 2, TC_MISS = 3;
    /** The relation tag (grey): none, ВЛ - the owner's own machine, ДР - a machine of his access list (lang sva.ad.co.rel.*). */
    public static final int REL_NONE = 0, REL_OWN = 1, REL_LIST = 2;
    public static final String[] REL_KEYS = {"none", "own", "list"};

    private CodeOnlyUiMath() {
    }

    /**
     * The tag of a track: none outside the mode (`co` false); else by the verdict's reason (RoeMath.ID_*) - a match, «ПРОВ»
     * (a miss waiting for its second), the confirmed miss, 7700 in its grace / past it, the «ПОДБОР» block, not asked yet. The
     * operator's «Свой» and 7600 in its grace carry marks of their own (the grant's minutes, «НЕТ СВЯЗИ») - no tag; 7600 past
     * it is a miss - «7600 ВЫШЛА».
     */
    public static int tag(boolean co, int idr) {
        if (!co) {
            return TAG_NONE;
        }
        switch (idr) {
            case RoeMath.ID_KEY:
                return TAG_KEY;
            case RoeMath.ID_OWN_CODE:
                return TAG_OWN_CODE;
            case RoeMath.ID_AIRPORT:
                return TAG_AIRPORT;
            case RoeMath.ID_CHECK:
                return TAG_CHECK;
            case RoeMath.ID_NO_REPLY:
                return TAG_NO_REPLY;
            case RoeMath.ID_NO_CARD:
                return TAG_NO_CARD;
            case RoeMath.ID_EXPIRED:
                return TAG_EXPIRED;
            case RoeMath.ID_OLD_KEY:
                return TAG_OLD_KEY;
            case RoeMath.ID_WRONG:
                return TAG_WRONG;
            case RoeMath.ID_EMERGENCY:
                return TAG_EMERGENCY;
            case RoeMath.ID_EMERGENCY_OVER:
                return TAG_EM_OVER;
            case RoeMath.ID_NO_RADIO_OVER:
                return TAG_NR_OVER;
            case RoeMath.ID_PROBE:
                return TAG_PROBE;
            case RoeMath.ID_HIJACK:
                return TAG_HIJACK;
            case RoeMath.ID_NOT_ASKED:
                return TAG_NOT_ASKED;
            default:
                return TAG_NONE;                                    // NONE, GRANTED, NO_RADIO: their own marks
        }
    }

    /** A tag's colour kind (TC_*). */
    public static int tagColour(int tag) {
        if (tag == TAG_KEY || tag == TAG_OWN_CODE || tag == TAG_AIRPORT) {
            return TC_OK;
        }
        if (tag == TAG_CHECK || tag == TAG_EMERGENCY || tag == TAG_NOT_ASKED) {
            return TC_WAIT;
        }
        return tag == TAG_NONE || tag < 0 || tag >= TAGS ? TC_NONE : TC_MISS;
    }

    /** «ПРОВ n/2»: the misses in a row heard so far (1 while it waits for the second; never 0 nor over 2). */
    public static int checkCount(int misses) {
        return Math.max(1, Math.min(2, misses));
    }

    /** The grey ВЛ / ДР tag: only in the mode, the vehicle owner's relation to the air defence (1 the owner, 2 his list). */
    public static int relTag(boolean co, int rel) {
        return !co ? REL_NONE : rel == 1 ? REL_OWN : rel == 2 ? REL_LIST : REL_NONE;
    }

    /** «1:20» - minutes and seconds (the 7700 grace, the 7600 timer). */
    public static String clock(int seconds) {
        int s = Math.max(0, seconds);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    // ------------------------------------------------------------------ the track form's «Опознавание» row (item 39)

    /** The form's status word (lang sva.ad.co.fs.*): none (the usual mode), опознан, проверка n/2, не опознан, ... */
    public static final int FS_NONE = 0, FS_OK = 1, FS_CHECK = 2, FS_MISS = 3, FS_EMERGENCY = 4, FS_GRANT = 5, FS_WAIT = 6, FS_NO_RADIO = 7;
    public static final String[] FS_KEYS = {"none", "ok", "check", "miss", "emergency", "grant", "wait", "noRadio"};

    /** The status of a code-only track by its class and reason (FS_NONE outside the mode). */
    public static int formStatus(boolean co, int cls, int idr) {
        if (!co) {
            return FS_NONE;
        }
        if (idr == RoeMath.ID_NOT_ASKED) {
            return FS_WAIT;
        }
        if (idr == RoeMath.ID_GRANTED) {
            return FS_GRANT;
        }
        if (RoeMath.idMatch(idr)) {
            return FS_OK;
        }
        if (idr == RoeMath.ID_CHECK) {
            return FS_CHECK;
        }
        if (cls == RoeMath.EMERGENCY || idr == RoeMath.ID_EMERGENCY) {
            return FS_EMERGENCY;
        }
        if (cls == RoeMath.NO_RADIO || idr == RoeMath.ID_NO_RADIO) {
            return FS_NO_RADIO;
        }
        return FS_MISS;
    }

    /**
     * Which reason the form names after the status (RoeMath.ID_*; ID_NONE - nothing more to say): the miss behind a «проверка»
     * (the verdict itself is only «ПРОВ»), the reason of a match or a miss, nothing for «не опрошен» / «свой по решению КП».
     */
    public static int formReason(int status, int idr, int idMiss) {
        if (status == FS_CHECK) {
            return idMiss;
        }
        if (status == FS_NONE || status == FS_WAIT || status == FS_GRANT || status == FS_EMERGENCY || status == FS_NO_RADIO) {
            return RoeMath.ID_NONE;
        }
        return idr;
    }

    /** A status's colour kind: OK green, MISS red, the rest amber. */
    public static int statusColour(int status) {
        return status == FS_OK || status == FS_GRANT ? TC_OK : status == FS_MISS ? TC_MISS : status == FS_NONE ? TC_NONE : TC_WAIT;
    }

    // ------------------------------------------------------------------ the post's map header plate (item 38)

    /** The plate: none (the usual mode), «ПО КОДУ», «ВКЛЮЧЕНИЕ ЧЕРЕЗ», forbidden by the server, no day key (lang sva.ad.co.plate.*). */
    public static final int PL_NONE = 0, PL_ON = 1, PL_ARMING = 2, PL_BANNED = 3, PL_NO_KEY = 4;
    public static final String[] PL_KEYS = {"none", "on", "arming", "banned", "noKey"};

    /** The plate for the post's mode state (CodeOnlyMath.CO_*). */
    public static int plate(int coState) {
        switch (coState) {
            case CodeOnlyMath.CO_ON:
                return PL_ON;
            case CodeOnlyMath.CO_ARMING:
                return PL_ARMING;
            case CodeOnlyMath.CO_BANNED:
                return PL_BANNED;
            case CodeOnlyMath.CO_NO_KEY:
                return PL_NO_KEY;
            default:
                return PL_NONE;
        }
    }

    /** «смена ключа через N мин»: whole minutes up (at least 1 while any time is left; 0 none). */
    public static int minutes(long ticks) {
        return ticks <= 0 ? 0 : (int) ((ticks + 1199) / 1200);
    }

    /** The mode switched on or about to be (the labels of the launchers' rules change with it, item 12). */
    public static boolean strictLabels(int coState) {
        return coState == CodeOnlyMath.CO_ON || coState == CodeOnlyMath.CO_ARMING;
    }

    // ------------------------------------------------------------------ the post's tabs and the confirmation window

    /** The post's tabs: Карта, Зоны, Настройки, Опознавание, Оборудование (the last opens the equipment screen). */
    public static final int TAB_MAP = 0, TAB_ZONES = 1, TAB_SETTINGS = 2, TAB_IDENT = 3, TAB_EQUIP = 4, TABS = 5;
    public static final String[] TAB_KEYS = {"map", "zones", "settings", "ident", "equip"};
    /** Their widths (the long words get the room) and the gap; they start 6 px in. */
    public static final int[] TAB_W = {44, 44, 58, 68, 68};
    public static final int TAB_GAP = 2, TAB_X = 6;

    /** {x, w} of each tab from the window's left - worked out once (review batch 3: the screen asked every frame). Read only. */
    private static final int[][] TABS_XW = layTabs();

    private static int[][] layTabs() {
        int[][] out = new int[TABS][];
        int x = TAB_X;
        for (int i = 0; i < TABS; i++) {
            out[i] = new int[] {x, TAB_W[i]};
            x += TAB_W[i] + TAB_GAP;
        }
        return out;
    }

    /** {x, w} of each tab from the window's left (the same array every time - not to be changed). */
    public static int[][] tabs() {
        return TABS_XW;
    }

    /** The tabs' right edge (the server mode's line takes the room from there to the window's right). */
    public static int tabsRight() {
        return TABS_XW[TABS - 1][0] + TABS_XW[TABS - 1][1];
    }

    /**
     * Review 2026-10-27 (batch 3): why «ПО КОДУ» may not be switched on now (the button greyed out, the line beside it) -
     * EL_NONE it may; the server's codeOnly=false, the server's dayKey=false (the switch was offered and then refused with
     * «coNoKey» in the chat), the post's own day key off. As CodeOnlyMath.mayEnable, the first that applies.
     */
    public static final int EL_NONE = 0, EL_BANNED = 1, EL_SERVER_KEY = 2, EL_POST_KEY = 3;
    public static final String[] EL_KEYS = {"", "serverBanned", "serverNoKey", "noDayKey"};

    public static int enableLimit(boolean serverAllows, boolean serverDayKey, boolean postDayKey) {
        return !serverAllows ? EL_BANNED : !serverDayKey ? EL_SERVER_KEY : !postDayKey ? EL_POST_KEY : EL_NONE;
    }

    /** A tab with the map (the zones' and the tracks'): the coverage button, the map's clicks, the wheel's zoom. */
    public static boolean mapTab(int tab) {
        return tab == TAB_MAP || tab == TAB_ZONES;
    }

    /** The confirmation window's size and its buttons («Включить», «Все ПУ: по всем без кода», «Отмена»): widths and gap. */
    public static final int DLG_W = 300, DLG_H = 132, DLG_BTN_H = 14, DLG_GAP = 6;
    public static final int[] DLG_BTN_W = {84, 136, 60};

    /**
     * The confirmation window centred in a `w` x `h` screen at (left, top): {{x0, y0, x1, y1} the box, then {x, y, w, h} of each
     * button}, the buttons in a row at the box's bottom, centred.
     */
    public static int[][] dialog(int left, int top, int w, int h) {
        int x0 = left + (w - DLG_W) / 2, y0 = top + (h - DLG_H) / 2;
        int[][] out = new int[1 + DLG_BTN_W.length][];
        out[0] = new int[] {x0, y0, x0 + DLG_W, y0 + DLG_H};
        int row = 0;
        for (int bw : DLG_BTN_W) {
            row += bw;
        }
        row += DLG_GAP * (DLG_BTN_W.length - 1);
        int x = x0 + (DLG_W - row) / 2, y = y0 + DLG_H - DLG_BTN_H - 6;
        for (int i = 0; i < DLG_BTN_W.length; i++) {
            out[1 + i] = new int[] {x, y, DLG_BTN_W[i], DLG_BTN_H};
            x += DLG_BTN_W[i] + DLG_GAP;
        }
        return out;
    }

    /**
     * Item 27: the summary's launchers (the snapshot's CoSum: {hold, violators, all, marks, without the code, of them the
     * owner's / his list's}) - {rule, count} for each rule that has any, in the order the operator reads them: по нарушителям,
     * по всем, по отметкам КП, огонь запрещён.
     */
    public static List<int[]> summaryRoes(int[] coSum) {
        List<int[]> out = new ArrayList<int[]>();
        int[][] order = {{RoeMath.VIOLATORS, 1}, {RoeMath.FOREIGN_ALL, 2}, {RoeMath.KP_MARKS, 3}, {RoeMath.HOLD, 0}};
        for (int[] o : order) {
            int n = coSum != null && o[1] < coSum.length ? coSum[o[1]] : 0;
            if (n > 0) {
                out.add(new int[] {o[0], n});
            }
        }
        return out;
    }

    /** Item 27: {in the air without the key's code, of them the owner's / his list's} from CoSum ({0, 0} for an older picture). */
    public static int[] withoutCode(int[] coSum) {
        return coSum != null && coSum.length >= 6 ? new int[] {coSum[4], coSum[5]} : new int[] {0, 0};
    }

    /** «Сменить ключ» (item 24) in two steps: the second press counts within this many ticks of the first. */
    public static final int CONFIRM_TICKS = 100;

    /** The second press of a two-step button at `now` (the first at `armedAt`, -1 never). */
    public static boolean confirmed(long armedAt, long now) {
        return armedAt >= 0 && now >= armedAt && now - armedAt <= CONFIRM_TICKS;
    }

    // ------------------------------------------------------------------ the decisions (items 7, 39) and the options (item 24)

    /** A track's row in the decision panel: none, 7600 (НЕТ СВЯЗИ) or 7700 (code-only: its grace running). */
    public static final int DEC_NONE = 0, DEC_NO_RADIO = 1, DEC_EMERGENCY = 2;
    /** The panel's ops («Свой», «Проверить», «Враждебный») per row kind - the post's commands. */
    public static final String[][] DEC_OPS = {{}, {"nrFriend", "nrCheck", "nrHostile"}, {"emFriend", "emCheck", "emHostile"}};
    /** The rows the panel shows at most. */
    public static final int DEC_ROWS = 3;

    /** The row kind of a track: 7600 waiting (NoRadioMath.S_PENDING), or 7700 in its grace on a code-only post. */
    public static int decision(int nr, boolean co, int em) {
        if (nr == NoRadioMath.S_PENDING) {
            return DEC_NO_RADIO;
        }
        return co && em == NoRadioMath.S_PENDING ? DEC_EMERGENCY : DEC_NONE;
    }

    /** The option's row may be pressed: the owner only; «Ручной набор кода» not while the server forbids it (codeOnlyManual). */
    public static boolean optEnabled(int bit, boolean owner, boolean serverManual) {
        return owner && (bit != CodeOnlyMath.OPT_MANUAL || serverManual);
    }

    /** The option's row shows it on (the manual code: only while the server allows it - else it does not count). */
    public static boolean optOn(int opts, int bit, boolean serverManual) {
        return (opts & bit) != 0 && (bit != CodeOnlyMath.OPT_MANUAL || serverManual);
    }

    /**
     * Review 2026-10-27 (batch 2): the screens' 7700 phrase in the CODE-ONLY mode - `base` («льгота %s с»: the server's
     * codeOnlyEmergencySeconds, synced) or, with 0 (7700 is never a target there), `base` + "Never" («тоже не стреляет»).
     * The tips used to say 180 s whatever the server set - a pilot read 180 s and was shot after 60.
     */
    public static String emKey(int seconds, String base) {
        return seconds > 0 ? base : base + "Never";
    }

    /** Review 2026-10-27 (batch 2): the transponder window's hint - at most this many lines this wide (GuiTransponderSVA). */
    public static final int XPDR_HINT_LINES = 5, XPDR_HINT_W = 214;

    /**
     * The vanilla 1.7.10 font's width of a character (ascii.png, the gap included) - the self-test's measure of a text's
     * lines; any other character (Cyrillic comes from the unicode pages, about 5-6) counts 7: an upper bound.
     */
    public static int glyphWidth(char c) {
        switch (c) {
            case ' ':
            case 't':
            case 'I':
            case '[':
            case ']':
                return 4;
            case '!':
            case ',':
            case '.':
            case ':':
            case ';':
            case 'i':
            case '|':
                return 2;
            case '\'':
            case '`':
            case 'l':
                return 3;
            case '"':
            case '(':
            case ')':
            case '*':
            case '<':
            case '>':
            case 'f':
            case 'k':
            case '{':
            case '}':
                return 5;
            case '@':
            case '~':
                return 7;
            default:
                return c < 128 ? 6 : 7;
        }
    }

    /**
     * The lines the FontRenderer's word wrap (sizeStringToWidth: the last space before the width, else a cut) makes of `s`
     * at `w` px by glyphWidth - exact for ASCII text, an upper bound for Cyrillic.
     */
    public static int lines(String s, int w) {
        int n = 0;
        String t = s == null ? "" : s;
        while (t.length() > 0) {
            n++;
            int px = 0, space = -1, i = 0;
            for (; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == ' ') {
                    space = i;
                }
                px += glyphWidth(c);
                if (px > w) {
                    break;
                }
            }
            if (i >= t.length()) {
                break;
            }
            int cut = space >= 0 && space < i ? space : Math.max(1, i);
            t = t.substring(cut);
            if (t.startsWith(" ")) {
                t = t.substring(1);
            }
        }
        return n;
    }

    /** The cards' term button (1 / 3 / 7 periods) may be chosen: within the server's codeCardMaxPeriods. */
    public static boolean periodAllowed(int periods, int max) {
        return periods >= 1 && periods <= Math.max(1, max);
    }

    // ------------------------------------------------------------------ the launcher (item 41)

    /** The launcher's banner: no link (the usual mode), linked - usual, arming, «ПО КОДУ», forbidden, no day key (lang sva.ad.co.banner.*). */
    public static final int BN_NO_LINK = 0, BN_NORMAL = 1, BN_ARMING = 2, BN_ON = 3, BN_BANNED = 4, BN_NO_KEY = 5;
    public static final String[] BN_KEYS = {"noLink", "normal", "arming", "on", "banned", "noKey"};

    /** The banner of a launcher linked to a post (its name "" - none) in the post's mode state (CodeOnlyMath.CO_*). */
    public static int banner(String kp, int coState) {
        if (kp == null || kp.length() == 0) {
            return BN_NO_LINK;
        }
        switch (coState) {
            case CodeOnlyMath.CO_ON:
                return BN_ON;
            case CodeOnlyMath.CO_ARMING:
                return BN_ARMING;
            case CodeOnlyMath.CO_BANNED:
                return BN_BANNED;
            case CodeOnlyMath.CO_NO_KEY:
                return BN_NO_KEY;
            default:
                return BN_NORMAL;
        }
    }

    /**
     * Item 12: the rule's lang key under sva.ad.roe. - in the mode (or arming) «co.» + the rule: «По всем без кода (и ваши)»,
     * «Без кода в запретных зонах», ... (the rules themselves stay; what «чужой» means changes).
     */
    public static String roeKey(int roe, boolean strict) {
        String k = RoeMath.ROE_KEYS[Math.max(0, Math.min(RoeMath.ROES - 1, roe))];
        return strict ? "co." + k : k;
    }

    // ------------------------------------------------------------------ the cockpits (item 42)

    /** The ОПОЗН lamp flashes: on for this many ms, off as long. */
    public static final int ID_BLINK_MS = 400;
    /** The RWR panel's fourth lamp: the transponder's ОПОЗН (next to RadarMath.LAMP_SEARCH / LOCK / LAUNCH). */
    public static final int LAMP_ID = 8;
    /** The RWR panel's lamps in a row: ОБЗОР, ЗАХВАТ, ПУСК, ОПОЗН. */
    public static final int RWR_PANEL_LAMPS = 4;

    /** The ОПОЗН lamp's light at `ms`: lit and in its on phase; the lamp test lights it steady. */
    public static boolean idLampOn(boolean lit, long ms, boolean test) {
        return test || lit && ms / ID_BLINK_MS % 2 == 0;
    }

    /** The cockpit's tone for the lamp: once, as it lights (not while it stays lit, not when it goes out). */
    public static boolean idLampSound(boolean was, boolean now) {
        return now && !was;
    }

    /**
     * The RWR panel's lamps (bits: RadarMath.LAMP_* and LAMP_ID): the test lights all; the RWR's three only with the module
     * fitted and powered (`rwrLive`), the ОПОЗН lamp is the transponder's - lit whenever the board is powered and the lamp is.
     */
    public static int rwrPanelLamps(int rwrLamps, boolean rwrLive, boolean powered, boolean idLit, boolean test) {
        if (test) {
            return 15;
        }
        return (rwrLive ? rwrLamps & 7 : 0) | (powered && idLit ? LAMP_ID : 0);
    }

    // ------------------------------------------------------------------ the post's log (item 40)

    /**
     * A log line's colour kind by its lang key: a miss / a block / a fire / an alarm red, the mode's arming / a new key / a
     * 7700 grace amber, a match / a decision for a friend / the mode off green, the rest plain (TC_NONE).
     */
    public static int logKind(String key) {
        if (key == null) {
            return TC_NONE;
        }
        for (String k : LOG_MISS) {
            if (key.contains(k)) {
                return TC_MISS;
            }
        }
        for (String k : LOG_WAIT) {
            if (key.contains(k)) {
                return TC_WAIT;
            }
        }
        for (String k : LOG_OK) {
            if (key.contains(k)) {
                return TC_OK;
            }
        }
        return TC_NONE;
    }

    /** logKind's words (review batch 3: constants - the screen asks for three lines every frame). */
    private static final String[] LOG_MISS = {"alarm", "emergency", "launch", "fire", "zoneIn", "hit", "coMiss", "coProbe", "coStranger", "emOver",
        "emHostile", "coByOp"};
    /** Review 2026-10-27, batch 2: the arming anew and a launcher's fire held on 7700 (the review's new lines) amber too. */
    private static final String[] LOG_WAIT = {"coArming", "coOn", "rekey", "emGrace", "coAllRoe", "emCheck", "coOpts", "coRearm", "coEmHold"};
    private static final String[] LOG_OK = {"ident", "grant", "coAccepted", "emFriend", "coUnlock", "coOff", "printed"};

    /**
     * The HUD's card line: «КАРТА КОДОВ: N МИН» with N (1..5) minutes left - the server's D_XPDR «W» (review batch 3: the real
     * minutes, CodeOnlyMath.cardMinutesLeft); 0 - no line.
     */
    public static int cardLine(int minutes) {
        return minutes > 0 && minutes <= 5 ? minutes : 0;
    }
}
