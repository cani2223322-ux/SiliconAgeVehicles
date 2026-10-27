package com.sva.debug;

import java.util.Map;

import com.sva.ConfigSVA;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.RoeMath;

/**
 * The user's request 2026-10-27 (airdefense.codeOnlyFire): «пусть ПВО по умолчанию стреляет, если не совпадает код
 * самолётного ответчика» - a launcher linked to an ARMED code-only post fires after the warnings in the "warnings" mode too;
 * the usual mode keeps obeying airdefense.mode. Pure logic, the config's defaults and the lang, the dedicated server runs it
 * (WorldTestAdCoFireSVA fires a real launcher):
 * <ul>
 * <li>the defaults: mode warnings, codeOnlyFire on - a code-only launcher fires, a usual one does not;</li>
 * <li>CodeOnlyMath.fires / modeKey's truth table, the ConfigSVA wrappers on it (the fields flipped and put back);</li>
 * <li>the readiness line: «только предупреждения» only with codeOnlyFire off, else the post's own reason;</li>
 * <li>the lang: the server line «огонь только ПО КОДУ» in ru and en, the launcher's tip no longer says «огня нет».</li>
 * </ul>
 */
final class SelfTestAdCoFireSVA {

    private SelfTestAdCoFireSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        defaults();
        truthTable();
        wrappers();
        readiness();
        lang();
    }

    private static void defaults() {
        check(ConfigSVA.adCodeOnlyFire && ConfigSVA.AD_WARNINGS.equals(ConfigSVA.adMode) && ConfigSVA.adEnabled && !ConfigSVA.adFires()
                && ConfigSVA.adFiresFor(true) && !ConfigSVA.adFiresFor(false),
                "ad code-only fire: by default (mode warnings, codeOnlyFire on) a code-only post's launcher fires, a usual one only warns");
    }

    private static void truthTable() {
        StringBuilder bad = new StringBuilder();
        for (int m = 0; m < 16; m++) {
            boolean en = (m & 1) != 0, fire = (m & 2) != 0, cof = (m & 4) != 0, on = (m & 8) != 0;
            boolean want = en && (fire || cof && on);
            if (CodeOnlyMath.fires(en, fire, cof, on) != want) {
                bad.append(" fires").append(m);
            }
            for (int a = 0; a < 2; a++) {
                boolean allowed = a == 1;
                String k = CodeOnlyMath.modeKey(en, fire, cof, allowed, on);
                String w = !en ? CodeOnlyMath.M_OFF : want ? CodeOnlyMath.M_FIRE : cof && allowed ? CodeOnlyMath.M_CODE : CodeOnlyMath.M_WARN;
                if (!w.equals(k)) {
                    bad.append(" mode").append(m).append('/').append(a).append('=').append(k);
                }
            }
        }
        check(bad.length() == 0 && !CodeOnlyMath.fires(true, false, true, false) && CodeOnlyMath.fires(true, false, true, true)
                && !CodeOnlyMath.fires(true, false, false, true) && CodeOnlyMath.fires(true, true, false, false) && !CodeOnlyMath.fires(false, true, true, true),
                "ad code-only fire: fire = enabled && (mode fire || codeOnlyFire && the post code-only now); the line fire / code / warn / off" + bad);
    }

    private static void wrappers() {
        boolean en0 = ConfigSVA.adEnabled, cof0 = ConfigSVA.adCodeOnlyFire, co0 = ConfigSVA.adCodeOnly, dk0 = ConfigSVA.adDayKey;
        String mode0 = ConfigSVA.adMode;
        try {
            ConfigSVA.adEnabled = true;
            ConfigSVA.adCodeOnly = true;
            ConfigSVA.adDayKey = true;
            ConfigSVA.adMode = ConfigSVA.AD_WARNINGS;
            ConfigSVA.adCodeOnlyFire = true;
            boolean warnOn = ConfigSVA.adFiresFor(true) && !ConfigSVA.adFiresFor(false) && CodeOnlyMath.M_FIRE.equals(ConfigSVA.adModeKey(true))
                    && CodeOnlyMath.M_CODE.equals(ConfigSVA.adModeKey(false));
            ConfigSVA.adCodeOnly = false;                           // the mode forbidden: nothing can fire by the code - «только предупреждения»
            boolean banned = CodeOnlyMath.M_WARN.equals(ConfigSVA.adModeKey(false));
            ConfigSVA.adCodeOnly = true;
            ConfigSVA.adCodeOnlyFire = false;
            boolean warnOff = !ConfigSVA.adFiresFor(true) && !ConfigSVA.adFiresFor(false) && CodeOnlyMath.M_WARN.equals(ConfigSVA.adModeKey(true));
            ConfigSVA.adMode = ConfigSVA.AD_FIRE;
            boolean fire = ConfigSVA.adFiresFor(true) && ConfigSVA.adFiresFor(false) && CodeOnlyMath.M_FIRE.equals(ConfigSVA.adModeKey(false));
            ConfigSVA.adEnabled = false;
            ConfigSVA.adCodeOnlyFire = true;
            boolean off = !ConfigSVA.adFiresFor(true) && CodeOnlyMath.M_OFF.equals(ConfigSVA.adModeKey(true));
            check(warnOn && banned && warnOff && fire && off, "ad code-only fire: ConfigSVA.adFiresFor / adModeKey - warnings + codeOnlyFire (" + warnOn
                    + "), the mode forbidden (" + banned + "), codeOnlyFire off (" + warnOff + "), mode fire (" + fire + "), the air defence off (" + off + ")");
        } finally {
            ConfigSVA.adEnabled = en0;
            ConfigSVA.adCodeOnlyFire = cof0;
            ConfigSVA.adCodeOnly = co0;
            ConfigSVA.adDayKey = dk0;
            ConfigSVA.adMode = mode0;
        }
    }

    private static void readiness() {
        int f = RoeMath.FOREIGN_ALL;
        boolean on = CodeOnlyMath.fires(true, false, true, true), offCf = CodeOnlyMath.fires(true, false, false, true);
        check(CodeOnlyMath.readiness(true, on, true, true, true, true, f) == CodeOnlyMath.R_READY
                && CodeOnlyMath.readiness(true, on, true, true, true, false, f) == CodeOnlyMath.R_NOT_CODE_ONLY
                && CodeOnlyMath.readiness(true, offCf, true, true, true, true, f) == CodeOnlyMath.R_WARN_ONLY,
                "ad code-only fire: the readiness line with mode warnings - READY for a code-only post (codeOnlyFire), «КП в режиме ОБЫЧНОЕ» for a usual "
                        + "one, «только предупреждения» only with codeOnlyFire off");
    }

    private static void lang() throws Exception {
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        StringBuilder bad = new StringBuilder();
        for (String m : new String[] {CodeOnlyMath.M_FIRE, CodeOnlyMath.M_CODE, CodeOnlyMath.M_WARN, CodeOnlyMath.M_OFF}) {
            String k = "sva.ad.l.mode." + m;
            if (ru.get(k) == null || en.get(k) == null) {
                bad.append(' ').append(k);
            }
        }
        String rc = ru.get("sva.ad.l.mode." + CodeOnlyMath.M_CODE), ec = en.get("sva.ad.l.mode." + CodeOnlyMath.M_CODE);
        if (rc == null || !rc.contains("ПО КОДУ") || ec == null || !ec.contains("CODE-ONLY")) {
            bad.append(" code-line");
        }
        String tr = ru.get("sva.tooltip.howto.launcher"), te = en.get("sva.tooltip.howto.launcher");
        // review 2026-10-27: the tip is fixed text - it names the switch that turns that fire off (codeOnlyFire=false)
        if (tr == null || tr.contains("Огня нет, пока") || !tr.contains("ПО КОДУ") || !tr.contains("codeOnlyFire") || te == null || te.contains("No fire while")
                || !te.contains("CODE-ONLY") || !te.contains("codeOnlyFire")) {
            bad.append(" launcher-tip");
        }
        check(bad.length() == 0, "ad code-only fire: the server line «огонь только ПО КОДУ» in ru and en, the launcher's tip says a code-only post's launchers fire "
                + "unless airdefense.codeOnlyFire is off" + bad);
    }
}
