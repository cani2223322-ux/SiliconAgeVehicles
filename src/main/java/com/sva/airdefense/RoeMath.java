package com.sva.airdefense;

/**
 * Air-defence wave (mock-ups veh_ad_3_launchers, veh_ad_5_zones_rules): who is who on the scope, the rules of engagement,
 * the server's checks before every shot and the staged warning - pure, the self-test checks it.
 * <p>
 * Classes: СВОЙ-ВЛАДЕЛЕЦ (the air defence owner's or his friends' own vehicle - never fired at), СВОЙ (today's / an own
 * code, the Mode S white list, «свой на 10 минут»), ЧУЖОЙ, НАРУШИТЕЛЬ (a stranger in a forbidden zone), АВАРИЯ (7700 -
 * never), НЕТ СВЯЗИ (7600 - no fire during its grace).
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
 */
public final class RoeMath {

    public static final int HOLD = 0, VIOLATORS = 1, FOREIGN_ALL = 2, KP_MARKS = 3, ROES = 4;
    public static final String[] ROE_KEYS = {"hold", "violators", "foreign", "kp"};

    public static final int OWN = 0, FRIEND = 1, FOREIGN = 2, VIOLATOR = 3, EMERGENCY = 4, NO_RADIO = 5, CLASSES = 6;
    public static final String[] CLASS_KEYS = {"own", "friend", "foreign", "violator", "emergency", "noRadio"};
    /** Scope colours (RGB): own / friend green, foreign yellow, violator red, emergency red (flashing), no radio orange. */
    public static final int[] CLASS_COLOURS = {0x62E06A, 0x62E06A, 0xF0D040, 0xF05050, 0xFF3030, 0xF09040};

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
        if (replied && code == XpdrMath.EMERGENCY) {
            return EMERGENCY;
        }
        if (ownOrFriend) {
            return OWN;
        }
        if (granted || replied && ownCode || whitelisted) {
            return FRIEND;
        }
        if (replied && code == XpdrMath.NO_RADIO && noRadioGrace) {
            return NO_RADIO;
        }
        return violator ? VIOLATOR : FOREIGN;
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

    /** Whole seconds left to fire (0 once firing). */
    public static int secondsToFire(long elapsed, boolean message, boolean warnShot, int waitTicks) {
        int[] t = stageTimes(message, warnShot, waitTicks);
        return (int) Math.max(0, (t[2] - elapsed + 19) / 20);
    }
}
