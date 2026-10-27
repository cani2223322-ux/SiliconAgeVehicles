package com.sva.debug;

import java.util.Map;

import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.CodeOnlyUiMath;
import com.sva.airdefense.NoRadioMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.inventory.ContainerLauncherSVA;

/**
 * Code-only wave, the review of 2026-10-27 (batch 3, the low ones) - pure logic, data and the lang, the dedicated server runs
 * it:
 * <ul>
 * <li>the link button: refused into a code-only post too, not only out of one (item 25);</li>
 * <li>a rider's copy of the transponder: the code only to who works it, the public codes to all; «----» for none;</li>
 * <li>the operator's «Свой» on a 7700 beats the «ПОДБОР» block (item 11);</li>
 * <li>the «ПО КОДУ» zone mark and the boarding note: arming or on - not forbidden, not without the day key (item 22);</li>
 * <li>the card's real minutes on the HUD (1..5, rounded up), the chat's 5 / 1 kept;</li>
 * <li>why «ПО КОДУ» may not be switched on - the server's dayKey too; the short rule labels in the mode (item 12);</li>
 * <li>the plate's and the readiness line's English texts fit their room; the launcher's window 240 px high again;</li>
 * <li>the post screen's helpers allocate nothing per frame (the tabs, the log's colours).</li>
 * </ul>
 */
final class SelfTestAdCodeReview3SVA {

    private SelfTestAdCodeReview3SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        relink();
        riderCode();
        emergencyBlock();
        marked();
        cardMinutes();
        enabling();
        texts();
        launcherWindow();
        screenHelpers();
    }

    // ---------------------------------------------------------------- the link button (item 25)

    private static void relink() {
        check(CodeOnlyMath.relinkRefused(true, false) && CodeOnlyMath.relinkRefused(false, true) && CodeOnlyMath.relinkRefused(true, true)
                && !CodeOnlyMath.relinkRefused(false, false),
                "ad code review 3 (item 25): the link button refused out of a code-only post's launcher and into one (a list member's rule under a usual post)");
    }

    // ---------------------------------------------------------------- a rider's copy of the code

    private static void riderCode() {
        boolean pub = true;
        for (int c : new int[] {XpdrMath.VFR, XpdrMath.HIJACK, XpdrMath.NO_RADIO, XpdrMath.EMERGENCY}) {
            pub &= XpdrMath.riderCode(false, c) == c && XpdrMath.riderCode(true, c) == c;
        }
        check(pub && XpdrMath.riderCode(false, 02345) == -1 && XpdrMath.riderCode(true, 02345) == 02345 && XpdrMath.riderCode(false, 0) == -1
                && "----".equals(XpdrMath.format(-1)) && "----".equals(XpdrMath.format(010000)) && "7777".equals(XpdrMath.format(07777))
                && "0000".equals(XpdrMath.format(0)) && XpdrMath.show(true, XpdrMath.C, -1) == XpdrMath.SHOW_AMBER,
                "ad code review 3: a passenger's copy of the transponder - no key code (-1, the screens «----»), the public 7000 / 7500 / 7600 / 7700 kept; "
                        + "the pilot's whole");
    }

    // ---------------------------------------------------------------- 7700 «Свой» and «ПОДБОР» (item 11)

    private static void emergencyBlock() {
        int fr = RoeMath.classifyByCode(true, false, true, true, XpdrMath.EMERGENCY, NoRadioMath.S_FRIEND, false, RoeMath.ID_NO_CARD, true, true);
        int pend = RoeMath.classifyByCode(true, false, true, true, XpdrMath.EMERGENCY, NoRadioMath.S_PENDING, false, RoeMath.ID_NO_CARD, true, true);
        int key = RoeMath.classifyByCode(true, false, true, true, 02345, NoRadioMath.S_FRIEND, false, RoeMath.ID_KEY, true, true);
        int noEm = RoeMath.classifyByCode(true, false, true, false, XpdrMath.EMERGENCY, NoRadioMath.S_FRIEND, false, RoeMath.ID_NO_REPLY, true, false);
        check(RoeMath.idClass(fr) == RoeMath.FRIEND && RoeMath.idReason(fr) == RoeMath.ID_GRANTED
                && RoeMath.idClass(pend) == RoeMath.VIOLATOR && RoeMath.idReason(pend) == RoeMath.ID_PROBE
                && RoeMath.idReason(key) == RoeMath.ID_PROBE && RoeMath.hostile(RoeMath.idClass(key))
                && RoeMath.idClass(noEm) == RoeMath.FOREIGN && RoeMath.idReason(noEm) == RoeMath.ID_PROBE,
                "ad code review 3 (item 11): the operator's «Свой» on a 7700 beats «ПОДБОР» (a friend); 7700 in its grace, the right code, no answer - still blocked");
    }

    // ---------------------------------------------------------------- the «ПО КОДУ» mark (item 22)

    private static void marked() {
        boolean states = CodeOnlyMath.marked(CodeOnlyMath.CO_ARMING) && CodeOnlyMath.marked(CodeOnlyMath.CO_ON) && !CodeOnlyMath.marked(CodeOnlyMath.CO_OFF)
                && !CodeOnlyMath.marked(CodeOnlyMath.CO_BANNED) && !CodeOnlyMath.marked(CodeOnlyMath.CO_NO_KEY);
        AirDefenseDataSVA.KpInfo i = new AirDefenseDataSVA.KpInfo();
        boolean off = !i.codeOnlyMarked(true, true);
        i.codeOnly = true;
        boolean arming = i.codeOnlyMarked(true, true);              // armAt -1: arming (dropped or not yet)
        i.armAt = 100;
        boolean on = i.codeOnlyMarked(true, true);
        boolean banned = !i.codeOnlyMarked(false, true), srvKey = !i.codeOnlyMarked(true, false);
        i.dayKey = false;
        boolean postKey = !i.codeOnlyMarked(true, true);
        check(states && off && arming && on && banned && srvKey && postKey,
                "ad code review 3 (item 22): the zones' «ПО КОДУ» and «Нет карты кодов» - arming or on; not off, forbidden, without the server's or the post's day key");
    }

    // ---------------------------------------------------------------- the card's minutes (item 22)

    private static void cardMinutes() {
        check(CodeOnlyMath.cardMinutesLeft(6000) == 5 && CodeOnlyMath.cardMinutesLeft(6001) == 0 && CodeOnlyMath.cardMinutesLeft(4801) == 5
                && CodeOnlyMath.cardMinutesLeft(4800) == 4 && CodeOnlyMath.cardMinutesLeft(100 * 20) == 2 && CodeOnlyMath.cardMinutesLeft(1200) == 1
                && CodeOnlyMath.cardMinutesLeft(1) == 1 && CodeOnlyMath.cardMinutesLeft(0) == 0 && CodeOnlyMath.cardMinutesLeft(-5) == 0
                && CodeOnlyUiMath.cardLine(CodeOnlyMath.cardMinutesLeft(100 * 20)) == 2 && CodeOnlyUiMath.cardLine(CodeOnlyMath.cardMinutesLeft(3 * 1200)) == 3
                && CodeOnlyMath.cardWarnMinutes(100 * 20) == 5 && CodeOnlyMath.cardWarnMinutes(1200) == 1,
                "ad code review 3 (item 22): the HUD's «КАРТА КОДОВ: N МИН» - the real minutes (1:40 left - 2), the chat's two messages at 5 and 1 kept");
    }

    // ---------------------------------------------------------------- switching on, the short labels

    private static void enabling() throws Exception {
        check(CodeOnlyUiMath.enableLimit(true, true, true) == CodeOnlyUiMath.EL_NONE && CodeOnlyUiMath.enableLimit(false, false, false) == CodeOnlyUiMath.EL_BANNED
                && CodeOnlyUiMath.enableLimit(true, false, true) == CodeOnlyUiMath.EL_SERVER_KEY && CodeOnlyUiMath.enableLimit(true, false, false) == CodeOnlyUiMath.EL_SERVER_KEY
                && CodeOnlyUiMath.enableLimit(true, true, false) == CodeOnlyUiMath.EL_POST_KEY,
                "ad code review 3: «ПО КОДУ» may be switched on - the server's codeOnly, the server's dayKey (it was offered, then refused), the post's key");
        boolean same = true;
        for (boolean a : new boolean[] {false, true}) {
            for (boolean b : new boolean[] {false, true}) {
                for (boolean c : new boolean[] {false, true}) {
                    same &= (CodeOnlyUiMath.enableLimit(a, b, c) == CodeOnlyUiMath.EL_NONE) == CodeOnlyMath.mayEnable(a, b, c);
                }
            }
        }
        check(same, "ad code review 3: the screen's switch agrees with the server's mayEnable in all 8 cases");
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        StringBuilder miss = new StringBuilder();
        for (int k = 1; k < CodeOnlyUiMath.EL_KEYS.length; k++) {
            String key = "sva.ad.co.set." + CodeOnlyUiMath.EL_KEYS[k];
            if (ru.get(key) == null || en.get(key) == null) {
                miss.append(' ').append(key);
            }
        }
        for (int r = 0; r < RoeMath.ROES; r++) {
            String key = "sva.ad.roe.short." + CodeOnlyUiMath.roeKey(r, true);
            if (ru.get(key) == null || en.get(key) == null || !safe(ru.get(key)) || !safe(en.get(key))) {
                miss.append(' ').append(key);
            }
        }
        String f = "sva.ad.roe.short." + CodeOnlyUiMath.roeKey(RoeMath.FOREIGN_ALL, true);
        check(miss.length() == 0 && "без кода".equals(ru.get(f)) && "no code".equals(en.get(f)) && !ru.get(f).equals(ru.get("sva.ad.roe.short.foreign")),
                "ad code review 3 (item 12): the reasons' lines and the short rule labels in the mode (ru, en) - «ПУ1 без кода 12», not «чужие»" + miss);
    }

    private static boolean safe(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= ' ' && c < 127 || c >= 'Ѐ' && c <= 'ӿ')) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- the texts' room

    private static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += CodeOnlyUiMath.glyphWidth(s.charAt(i));
        }
        return w;
    }

    private static void texts() throws Exception {
        Map<String, String> en = LangKeysSVA.map(LangKeysSVA.EN);
        // the map's plate: 126 px, TextFitSC shrinks to 75% - 168 px of text; the readiness line: the map's 204 px - 272
        StringBuilder bad = new StringBuilder();
        for (String k : CodeOnlyUiMath.PL_KEYS) {
            String v = en.get("sva.ad.co.plate." + k);
            if (v != null && width(String.format(v, "12")) > 126 * 4 / 3) {
                bad.append(" plate.").append(k);
            }
        }
        for (String k : CodeOnlyMath.READY_KEYS) {
            String v = en.get("sva.ad.co.ready." + k);
            if (v == null || width(v) > 204 * 4 / 3) {
                bad.append(" ready.").append(k);
            }
        }
        check(bad.length() == 0, "ad code review 3: the English plate and readiness line fit their room by the 1.7.10 font widths (no «...in 12 ...»)" + bad);
    }

    // ---------------------------------------------------------------- the launcher's window

    private static void launcherWindow() {
        int w = 260, h = 240, rows = ContainerLauncherSVA.INV_Y + 58 + 18;
        int col = ContainerLauncherSVA.SLOT_Y + ContainerLauncherSVA.SLOT_STEP * 3 + 16;
        check(ContainerLauncherSVA.SLOT_Y - 11 >= 133 + 9 && rows <= h && col <= rows - 2
                && ContainerLauncherSVA.SLOT_X + 17 < ContainerLauncherSVA.INV_X - 1 && ContainerLauncherSVA.INV_X + 8 * 18 + 17 < ContainerLauncherSVA.UPG_X - 1
                && ContainerLauncherSVA.UPG_X + 17 <= w - 6 && ContainerLauncherSVA.SLOT_STEP >= 18,
                "ad code review 3: the launcher's window 240 px again - the ammunition and the modules in columns beside the inventory, under the banner and "
                        + "the readiness line (" + rows + ", " + col + ")");
    }

    // ---------------------------------------------------------------- the post screen's helpers

    private static void screenHelpers() {
        int[][] a = CodeOnlyUiMath.tabs(), b = CodeOnlyUiMath.tabs();
        boolean lay = a == b && a.length == CodeOnlyUiMath.TABS && a[0][0] == CodeOnlyUiMath.TAB_X;
        int x = CodeOnlyUiMath.TAB_X;
        for (int i = 0; i < CodeOnlyUiMath.TABS; i++) {
            lay &= a[i][0] == x && a[i][1] == CodeOnlyUiMath.TAB_W[i];
            x += CodeOnlyUiMath.TAB_W[i] + CodeOnlyUiMath.TAB_GAP;
        }
        lay &= CodeOnlyUiMath.tabsRight() == x - CodeOnlyUiMath.TAB_GAP;
        check(lay && CodeOnlyUiMath.logKind("sva.ad.log.coMiss.noCard") == CodeOnlyUiMath.TC_MISS && CodeOnlyUiMath.logKind("sva.ad.log.coRearm") == CodeOnlyUiMath.TC_WAIT
                && CodeOnlyUiMath.logKind("sva.ad.log.emFriend") == CodeOnlyUiMath.TC_OK && CodeOnlyUiMath.logKind("sva.ad.log.secret") == CodeOnlyUiMath.TC_NONE
                && CodeOnlyUiMath.logKind(null) == CodeOnlyUiMath.TC_NONE,
                "ad code review 3: the tabs worked out once (the same array every frame), the log's colours from constant word lists - as before");
    }
}
