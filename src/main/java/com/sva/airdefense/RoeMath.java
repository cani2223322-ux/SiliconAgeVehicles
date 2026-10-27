package com.sva.airdefense;

/**
 * Air-defence wave (mock-ups veh_ad_3_launchers, veh_ad_5_zones_rules): who is who on the scope, the rules of engagement,
 * the server's checks before every shot and the staged warning - pure, the self-test checks it.
 * <p>
 * Classes: СВОЙ-ВЛАДЕЛЕЦ (the air defence owner's or his friends' own vehicle - never fired at), СВОЙ (today's / an own
 * code, the Mode S white list, «свой на 10 минут»), ЧУЖОЙ, НАРУШИТЕЛЬ (a stranger in a forbidden zone), АВАРИЯ (7700 -
 * never), НЕТ СВЯЗИ (7600 - no fire during its grace), НЕИЗВЕСТЕН (audit 2026-10-22, bug 15: a command post's track no
 * surveillance radar has interrogated yet - its transponder unknown, never fired at).
 * <p>
 * Rules of engagement (each launcher): ОГОНЬ ЗАПРЕЩЁН (track only), ПО НАРУШИТЕЛЯМ ЗОНЫ, ПО ВСЕМ ЧУЖИМ, ПО ОТМЕТКАМ ИЗ КП
 * (only what the command post's operator assigned; with the post's «Авто» also violators). A launcher without a command
 * post falls back to «по нарушителям».
 * <p>
 * The checks before each shot, in the mock-up's order: the server's air defence off; the launcher too near the world spawn;
 * the target in a protected region (a private field that refuses the launcher's owner); own / friend's vehicle; PvP off
 * (airdefense.requirePvp); nobody aboard (airdefense.fireOnUnmanned); 7700 / own code / Mode S / 7600's grace; the server's
 * «только предупреждения»; the rule of engagement does not want it. Any «yes» - no fire. Some still let the warnings go to
 * the pilot (warnsAllowed): the spawn, a protected region, PvP, nobody aboard, warnings only.
 * <p>
 * The warning (each launcher, while the target stays): 1 «Сообщение» to the pilot (HUD + chat), after MSG_TICKS 2 «Захват»
 * (the RWR's lock, «N с до огня»), after the launcher's wait 3 «Предупредительный» (tracers across the nose, if on), after
 * WARN_TO_FIRE 4 «Огонь» (a burst / a missile, a line in the launch log). Leaving the zone, an own code, 7700 - it starts
 * over (the caller drops the engagement).
 * <p>
 * Code-only wave (docs/proposal-ad-strict-iff.md): a post on «Опознавание: ПО КОДУ» classes by classifyByCode - no
 * ownership in it (OWN is never given, ownBlocked holds no fire for the owner's machines, fireOnFriends does not apply), the
 * verdict's reason (ID_*) goes with the class; classify, the classes, roeWants and fireBlock stay as they are.
 */
public final class RoeMath {

    public static final int HOLD = 0, VIOLATORS = 1, FOREIGN_ALL = 2, KP_MARKS = 3, ROES = 4;
    public static final String[] ROE_KEYS = {"hold", "violators", "foreign", "kp"};

    public static final int OWN = 0, FRIEND = 1, FOREIGN = 2, VIOLATOR = 3, EMERGENCY = 4, NO_RADIO = 5, UNKNOWN = 6, CLASSES = 7;
    public static final String[] CLASS_KEYS = {"own", "friend", "foreign", "violator", "emergency", "noRadio", "unknown"};
    /** Scope colours (RGB): own / friend green, foreign yellow, violator red, emergency red (flashing), no radio orange, unknown grey. */
    public static final int[] CLASS_COLOURS = {0x62E06A, 0x62E06A, 0xF0D040, 0xF05050, 0xFF3030, 0xF09040, 0xA0A8B0};

    /** fireBlock results: OK - fire; the rest say why not. */
    public static final int OK = 0, B_DISABLED = 1, B_SPAWN = 2, B_PROTECTED = 3, B_OWN = 4, B_PVP = 5, B_UNMANNED = 6, B_SPECIAL = 7,
            B_WARN_ONLY = 8, B_ROE = 9;
    public static final String[] BLOCK_KEYS = {"ok", "disabled", "spawn", "protected", "own", "pvp", "unmanned", "special", "warnOnly", "roe"};

    public static final int ST_NONE = 0, ST_MESSAGE = 1, ST_LOCK = 2, ST_WARN = 3, ST_FIRE = 4;
    public static final String[] STAGE_KEYS = {"none", "message", "lock", "warn", "fire"};
    /** From the message to the lock (5 s); from the warning shot to fire (2 s). */
    public static final int MSG_TICKS = 100, WARN_TO_FIRE = 40;
    /** The launcher's wait before fire: 2..60 s (the server's airdefense.minWarningSeconds is the floor). */
    public static final int WAIT_MIN = 2, WAIT_MAX = 60;
    /** «Свой на 10 минут». */
    public static final int GRANT_TICKS = 10 * 60 * 20;

    private RoeMath() {
    }

    /**
     * Who the contact is (see the class comment).
     * @param ownOrFriend the vehicle's owner is the air defence's owner or on its access list
     * @param replied the transponder answered (A, C, S)
     * @param ownCode its code is today's / one of the own codes / an aerodrome's it is in
     * @param whitelisted Mode S and its board on the white list
     * @param granted «свой на 10 минут»
     * @param noRadioGrace 7600 still in its grace
     * @param violator in a forbidden zone (ZoneMath.violated)
     */
    public static int classify(boolean ownOrFriend, boolean replied, int code, boolean ownCode, boolean whitelisted, boolean granted,
            boolean noRadioGrace, boolean violator) {
        return classify(ownOrFriend, replied, code, ownCode, false, whitelisted, granted, noRadioGrace, violator);
    }

    /**
     * Audit 2026-10-22 (bug 6): as above with a friendly airport's code apart - `airportCode` (the code a friendly tower gave
     * this very aircraft, near that airport) makes it a friend only while it is no violator: a forbidden zone entered or a
     * tower's IFF handoff (both come as `violator`) is not cancelled by an aerodrome code. The own codes, today's code, the
     * white list and «свой на 10 минут» still are (the owner's own word).
     */
    public static int classify(boolean ownOrFriend, boolean replied, int code, boolean ownCode, boolean airportCode, boolean whitelisted,
            boolean granted, boolean noRadioGrace, boolean violator) {
        if (replied && code == XpdrMath.EMERGENCY) {
            return EMERGENCY;
        }
        if (ownOrFriend) {
            return OWN;
        }
        if (granted || replied && ownCode || whitelisted || replied && airportCode && !violator) {
            return FRIEND;
        }
        if (replied && code == XpdrMath.NO_RADIO && noRadioGrace) {
            return NO_RADIO;
        }
        return violator ? VIOLATOR : FOREIGN;
    }

    /**
     * Audit 2026-10-22 (bug 15): a command post's track no surveillance radar (nor the post itself) has interrogated yet - a
     * class fire may be opened on becomes UNKNOWN (its transponder may well say 7700): no warnings, no fire.
     */
    public static int unknownUntilAsked(int cls, boolean asked) {
        return !asked && hostile(cls) ? UNKNOWN : cls;
    }

    /** A class fire may ever be opened on (foreign, violator). */
    public static boolean hostile(int cls) {
        return cls == FOREIGN || cls == VIOLATOR;
    }

    /** Does the rule of engagement want this target? */
    public static boolean roeWants(int roe, int cls, boolean assigned, boolean kpAuto) {
        if (!hostile(cls)) {
            return false;
        }
        switch (roe) {
            case VIOLATORS:
                return cls == VIOLATOR;
            case FOREIGN_ALL:
                return true;
            case KP_MARKS:
                return assigned || kpAuto && cls == VIOLATOR;
            default:
                return false;
        }
    }

    /** A launcher with no command post: «по отметкам КП» and «по всем чужим» fall back to «по нарушителям»; HOLD stays. */
    public static int roeWithoutKp(int roe) {
        return roe == HOLD ? HOLD : VIOLATORS;
    }

    /**
     * The first check that forbids fire (the class comment's order), OK if none.
     * @param ownBlocked the target is OWN (or a friend's and airdefense.fireOnFriends is off)
     * @param pvpBlocked airdefense.requirePvp and the server's PvP is off
     * @param unmannedBlocked nobody aboard and airdefense.fireOnUnmanned is off
     * @param special 7700 / own code / Mode S white list / 7600's grace (the class is EMERGENCY, FRIEND or NO_RADIO)
     */
    public static int fireBlock(boolean enabled, boolean nearSpawn, boolean protectedRegion, boolean ownBlocked, boolean pvpBlocked,
            boolean unmannedBlocked, boolean special, boolean warnOnly, boolean roeWants) {
        if (!enabled) {
            return B_DISABLED;
        }
        if (nearSpawn) {
            return B_SPAWN;
        }
        if (protectedRegion) {
            return B_PROTECTED;
        }
        if (ownBlocked) {
            return B_OWN;
        }
        if (pvpBlocked) {
            return B_PVP;
        }
        if (unmannedBlocked) {
            return B_UNMANNED;
        }
        if (special) {
            return B_SPECIAL;
        }
        if (warnOnly) {
            return B_WARN_ONLY;
        }
        return roeWants ? OK : B_ROE;
    }

    /** May the warnings (message, lock, warning shot) still go to the pilot? */
    public static boolean warnsAllowed(int block) {
        return block == OK || block == B_SPAWN || block == B_PROTECTED || block == B_PVP || block == B_UNMANNED || block == B_WARN_ONLY;
    }

    /** The launcher's wait in ticks: its own seconds, never under the server's minimum. */
    public static int waitTicks(int launcherSeconds, int minSeconds) {
        return Math.max(Math.max(0, minSeconds), Math.max(WAIT_MIN, Math.min(WAIT_MAX, launcherSeconds))) * 20;
    }

    /** The tick (after the engagement's start) each stage begins: {lock, warning shot, fire} (warning shot -1: off). */
    public static int[] stageTimes(boolean message, boolean warnShot, int waitTicks) {
        int lock = message ? MSG_TICKS : 0;
        int warn = warnShot ? lock + waitTicks : -1;
        int fire = warnShot ? warn + WARN_TO_FIRE : lock + waitTicks;
        return new int[] {lock, warn, fire};
    }

    /** The stage `elapsed` ticks after the engagement began. */
    public static int stage(long elapsed, boolean message, boolean warnShot, int waitTicks) {
        int[] t = stageTimes(message, warnShot, waitTicks);
        if (elapsed < t[0]) {
            return ST_MESSAGE;
        }
        if (t[1] >= 0 && elapsed < t[1]) {
            return ST_LOCK;
        }
        if (elapsed < t[2]) {
            return t[1] >= 0 ? ST_WARN : ST_LOCK;
        }
        return ST_FIRE;
    }

    /**
     * Audit 2026-10-22: the warnings so far went to an empty cockpit (nobody aboard at the last round) and somebody is aboard
     * now - the engagement starts over, so he hears the message, the lock and the warning shot before any fire (before, a
     * hold-fire for an unmanned vehicle lifted by a pilot boarding it at the fire stage meant fire without a word).
     */
    public static boolean restartWarnings(boolean heardBefore, boolean mannedNow, int stage) {
        return !heardBefore && mannedNow && stage > ST_NONE;
    }

    /**
     * Audit review 2026-10-22: «heard» for the next round - sticky for the whole engagement once anyone was aboard. It was
     * «aboard at the last round»: a pilot who had heard every warning stepped out for one round before the fire and back
     * in, the warnings started over, and again - the launcher never fired.
     */
    public static boolean heard(boolean heardBefore, boolean mannedNow) {
        return heardBefore || mannedNow;
    }

    /** Whole seconds left to fire (0 once firing). */
    public static int secondsToFire(long elapsed, boolean message, boolean warnShot, int waitTicks) {
        int[] t = stageTimes(message, warnShot, waitTicks);
        return (int) Math.max(0, (t[2] - elapsed + 19) / 20);
    }

    // ------------------------------------------------------------------ code-only wave: «Опознавание: ПО КОДУ»

    /**
     * Code-only wave (proposal-ad-strict-iff item 4): why a track is what it is - Contact.idr (the verdict) and Contact.idMiss
     * (the miss behind a target or a «ПРОВ 1/2»); lang sva.ad.idr.*. ID_NONE: the usual mode (no reason kept). Matches: KEY
     * (the key's code - the card's, or dialled by hand with «Ручной набор кода»), OWN_CODE (option 3а), AIRPORT (3в). Misses:
     * NO_REPLY (standby, off, dark, a wreck), NO_CARD (no card of this key, the code not the key's), EXPIRED (the card's periods
     * over), OLD_KEY (a card of an older generation - «Сменить ключ»), WRONG (a good card, another code squawked),
     * EMERGENCY_OVER / NO_RADIO_OVER (7700 / 7600 past their grace or called hostile), HIJACK (7500).
     */
    public static final int ID_NONE = 0, ID_NOT_ASKED = 1, ID_GRANTED = 2, ID_PROBE = 3, ID_EMERGENCY = 4, ID_KEY = 5, ID_OWN_CODE = 6,
            ID_AIRPORT = 7, ID_NO_RADIO = 8, ID_CHECK = 9, ID_NO_REPLY = 10, ID_NO_CARD = 11, ID_EXPIRED = 12, ID_OLD_KEY = 13,
            ID_WRONG = 14, ID_EMERGENCY_OVER = 15, ID_NO_RADIO_OVER = 16, ID_HIJACK = 17, IDS = 18;
    public static final String[] ID_KEYS = {"none", "notAsked", "granted", "probe", "emergency", "key", "ownCode", "airport", "noRadio",
        "check", "noReply", "noCard", "expired", "oldKey", "wrong", "emergencyOver", "noRadioOver", "hijack"};

    /** A match: the key's code, an own code (3а), an airport's code (3в). */
    public static boolean idMatch(int id) {
        return id == ID_KEY || id == ID_OWN_CODE || id == ID_AIRPORT;
    }

    /** A miss (a target once confirmed). */
    public static boolean idMiss(int id) {
        return id >= ID_NO_REPLY && id < IDS;
    }

    /** classifyByCode's answer: the class, the reason and the miss behind it in one int (idClass / idReason / idMissOf). */
    static int pack(int cls, int idr, int miss) {
        return cls & 0xFF | (idr & 0xFF) << 8 | (miss & 0xFF) << 16;
    }

    public static int idClass(int packed) {
        return packed & 0xFF;
    }

    public static int idReason(int packed) {
        return packed >> 8 & 0xFF;
    }

    public static int idMissOf(int packed) {
        return packed >> 16 & 0xFF;
    }

    /**
     * Code-only wave (items 2, 4-9): who the contact is when the post identifies by the code alone - no ownership in it: the
     * owner's own machines, his access list, the Mode S white list, the trusted towers and the codes 7000 / 0000 / 7500 make
     * nobody a friend, and OWN is never given (fireOnFriends does not apply: ownBlocked). The first that applies:
     * <ol>
     * <li>never interrogated - UNKNOWN (as in the usual mode);</li>
     * <li>«свой на 10 минут» (or 7600 «свой» - the caller folds it in) - FRIEND;</li>
     * <li>the «ПОДБОР» block (only with the options 3а / 3б: the caller asks CodeOnlyMath.probeGuard) - a target at once;</li>
     * <li>7700: «свой» - FRIEND; within its grace or «проверить» - EMERGENCY (`emState`: CodeOnlyMath.emergencyState, -1 when
     * not squawking 7700);</li>
     * <li>a match (`id`: CodeOnlyMath.identify) - FRIEND, in a forbidden zone and after a tower's handoff too;</li>
     * <li>7600 within its grace - NO_RADIO (as now);</li>
     * <li>a miss not yet confirmed (`confirmed`: two in a row, CodeOnlyMath.Confirm) - UNKNOWN «ПРОВ 1/2»;</li>
     * <li>else VIOLATOR (in a forbidden zone or handed over by a tower) or FOREIGN.</li>
     * </ol>
     * @return pack(class, reason, miss)
     */
    public static int classifyByCode(boolean asked, boolean granted, boolean probeBlocked, boolean replied, int code, int emState,
            boolean noRadioGrace, int id, boolean confirmed, boolean violator) {
        if (!asked) {
            return pack(UNKNOWN, ID_NOT_ASKED, ID_NONE);
        }
        if (granted) {
            return pack(FRIEND, ID_GRANTED, ID_NONE);
        }
        int target = violator ? VIOLATOR : FOREIGN;
        boolean em = replied && code == XpdrMath.EMERGENCY;
        // review 2026-10-27 (batch 3, item 11): the operator's «Свой» on a 7700 beats the «ПОДБОР» block as a grant does (and
        // the post's command lifts it) - the 7700 decision row showed, «Свой» was clicked, the launchers still fired
        if (em && emState == NoRadioMath.S_FRIEND) {
            return pack(FRIEND, ID_GRANTED, ID_NONE);
        }
        if (probeBlocked) {
            return pack(target, ID_PROBE, ID_PROBE);
        }
        if (em && NoRadioMath.holdFire(emState)) {
            return pack(EMERGENCY, ID_EMERGENCY, ID_NONE);
        }
        if (idMatch(id)) {
            return pack(FRIEND, id, ID_NONE);
        }
        boolean nr = replied && code == XpdrMath.NO_RADIO;
        if (nr && noRadioGrace) {
            return pack(NO_RADIO, ID_NO_RADIO, ID_NONE);
        }
        int miss = em ? ID_EMERGENCY_OVER : nr ? ID_NO_RADIO_OVER : replied && code == XpdrMath.HIJACK ? ID_HIJACK : idMiss(id) ? id : ID_NO_CARD;
        if (!confirmed) {
            return pack(UNKNOWN, ID_CHECK, miss);
        }
        return pack(target, miss, miss);
    }

    /**
     * Code-only wave (item 2): fire is held for the owner's / his friends' machines only in the usual mode - in the code-only
     * mode nobody is spared for whose vehicle it is (a hijacker of the owner's own machine gets no head start).
     * @param rel the relation to the air defence's owner (1 own, 2 on the access list, 0 none)
     */
    public static boolean ownBlocked(boolean codeOnly, int rel, boolean fireOnFriends) {
        return !codeOnly && (rel == 1 || rel == 2 && !fireOnFriends);
    }

    /**
     * classifyByCode with the track's miss count (items 5, 6): a miss heard at `at` (the interrogation's tick) counts towards
     * the two in a row (CodeOnlyMath.miss, `gapTicks` apart at least) and the verdict is a target only once confirmed; a
     * match or the operator's decision starts the count over. Not interrogated yet and the «ПОДБОР» block leave it as it is -
     * and (review 2026-10-27) so do 7700 / 7600 in their grace: they hold the fire, they do not wipe the misses (a target that
     * squawked 7700 for one interrogation came back «ПРОВ 1/2» with a fresh warning sequence, again and again).
     */
    public static int classifyByCode(boolean asked, boolean granted, boolean probeBlocked, boolean replied, int code, int emState,
            boolean noRadioGrace, int id, boolean violator, CodeOnlyMath.Confirm c, long at, int gapTicks) {
        int r = classifyByCode(asked, granted, probeBlocked, replied, code, emState, noRadioGrace, id, false, violator);
        int why = idReason(r);
        if (why == ID_CHECK) {
            CodeOnlyMath.miss(c, at, gapTicks);
            return classifyByCode(asked, granted, probeBlocked, replied, code, emState, noRadioGrace, id, CodeOnlyMath.confirmed(c), violator);
        }
        if (why != ID_NOT_ASKED && why != ID_PROBE && why != ID_EMERGENCY && why != ID_NO_RADIO) {
            CodeOnlyMath.hit(c);
        }
        return r;
    }

    /**
     * Review 2026-10-27 (the 7700 grace multiplied): a code-only track held now only by 7700 / 7600 in its grace - a launcher
     * engaged on it pauses the engagement (its stage clock stops) instead of dropping it, and goes on where it stopped once
     * the grace no longer holds it. The usual mode keeps its old rule (7700 there is a friend for good: the engagement goes).
     */
    public static boolean graceHold(boolean codeOnly, int cls) {
        return codeOnly && (cls == EMERGENCY || cls == NO_RADIO);
    }

    /**
     * Review 2026-10-27: a paused engagement {began, stage, first fire logged, heard, paused at} taken up again at `now` - the
     * same stage, its clock moved on by the pause (the time on 7700 is no warning time). @return {began, stage, logged, heard}
     */
    public static long[] resume(long[] paused, long now) {
        long pause = Math.max(0, now - paused[4]);
        return new long[] {paused[0] + pause, paused[1], paused[2], paused[3]};
    }

    /** Review 2026-10-27: an engagement {began, stage, logged, heard} paused at `now` (resume takes it up). */
    public static long[] pause(long[] st, long now) {
        return new long[] {st[0], st[1], st[2], st[3], now};
    }
}
