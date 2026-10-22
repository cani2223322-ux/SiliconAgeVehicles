package com.sva.airdefense;

/**
 * Audit 2026-10-22 (7.1, the command post's commands): how often one player may send the command post's commands - pure,
 * the self-test checks it.
 * <p>
 * Before, nothing limited them: the server ran up to 24 a player a tick, each wrote a log line (40 kept - the real events
 * went in two ticks) and sent the whole picture to every viewer; «Сообщение пилоту» flooded any pilot's chat and HUD, the
 * alarm every listed player's chat, a zone change the zones to every player. Now each player has two allowances (a
 * «generic cell rate» bucket: a burst, then one per interval): HEAVY - a message, the alarm, «Авто», the settings, the
 * secret, a zone saved or deleted, a card printed, a radar's / launcher's link to the next post (3 at once, then one a
 * second); LIGHT - the orders on a track (assign, «только вести», «свой на 10 минут», the 7600 decisions - «враждебный»
 * too), the coverage request and the way back from the equipment
 * screen (8 at once, then one in 4 ticks). Joining the open screen's viewers costs nothing.
 */
public final class CmdRateMath {

    public static final int FREE = 0, LIGHT = 1, HEAVY = 2;
    /** NetADSVA.KP_OPEN (back from the equipment screen: the whole picture to the asker) counts as this light op. */
    public static final String OPEN = "kpOpen";
    /** Audit 2026-10-22 (bug 19): NetADSVA.KP_LINK (a radar's / launcher's link to the next command post) - a heavy op. */
    public static final String LINK = "kpLink";
    /** The burst each allowance takes at once, and the ticks one more needs (FREE: none). */
    public static final int[] BURST = {0, 8, 3}, INTERVAL = {0, 4, 20};

    private CmdRateMath() {
    }

    /** The allowance a KP_CMD op («Op») draws on; unknown ops are HEAVY (a modified client's own names). */
    public static int kind(String op) {
        if (op == null || "open".equals(op)) {
            return FREE;
        }
        if ("assign".equals(op) || "track".equals(op) || "grant".equals(op) || "nrFriend".equals(op) || "nrCheck".equals(op) || "nrHostile".equals(op)
                || "cov".equals(op) || OPEN.equals(op)) {
            return LIGHT;
        }
        return HEAVY;
    }

    /**
     * One command at `now` against an allowance whose next free time is `tat` (0: never used): the new `tat`, or -1 when
     * refused (the allowance unchanged). A clock gone back (another world's time) starts it afresh.
     */
    public static long take(long tat, int kind, long now) {
        if (kind <= FREE || kind >= BURST.length) {
            return tat;
        }
        long iv = INTERVAL[kind];
        if (tat > now + BURST[kind] * iv) {
            tat = now;
        }
        long t = Math.max(tat, now);
        if (t - now > (BURST[kind] - 1) * iv) {
            return -1;
        }
        return t + iv;
    }
}
