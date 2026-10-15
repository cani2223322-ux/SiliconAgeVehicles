package com.sva.airdefense;

/**
 * Wave A (plan-next §9 A5, mock-up veh_ad_impl_a_4_7600): 7600 («нет связи») through the command post - pure, the self-test
 * checks it. A track that starts squawking 7600 gets the mark «НЕТ СВЯЗИ», a timer (airdefense.noRadioDecisionSeconds) and a
 * line in the post's decision list; the operator answers:
 * <ul>
 * <li>«Свой» - a friend for airdefense.noRadioFriendMinutes (as «свой на 10 минут»);</li>
 * <li>«Проверить» - track only: no fire, no warnings while it squawks 7600 (the operator watches it);</li>
 * <li>nothing before the timer runs out - the default: it is classed as any other track (a stranger, a violator in a
 * forbidden zone) and the launchers take it by their rules.</li>
 * </ul>
 * Until then (PENDING) it is NO_RADIO - never fired at. The code changed away from 7600 - the entry is forgotten. Launchers
 * without a command post keep the transponder's own 2-minute grace (XpdrMath.NO_RADIO_GRACE) as before.
 */
public final class NoRadioMath {

    /** The operator's decision. */
    public static final int D_NONE = 0, D_FRIEND = 1, D_CHECK = 2;
    /** The state now. */
    public static final int S_PENDING = 0, S_FRIEND = 1, S_CHECK = 2, S_DEFAULT = 3;
    public static final String[] STATE_KEYS = {"pending", "friend", "check", "default"};

    private NoRadioMath() {
    }

    /** One 7600 track on a post. */
    public static final class Entry {
        /** First seen squawking 7600 (ticks); the decision and when the friend's time ends. */
        public long since, until = -1;
        public int decision = D_NONE;
        /** The default already logged. */
        public boolean defaultLogged;

        public Entry(long since) {
            this.since = since;
        }
    }

    /** The state at `now` with a decision timer of `timer` ticks. */
    public static int state(Entry e, long now, int timer) {
        if (e == null) {
            return S_DEFAULT;
        }
        if (e.decision == D_FRIEND) {
            return now < e.until ? S_FRIEND : S_DEFAULT;
        }
        if (e.decision == D_CHECK) {
            return S_CHECK;
        }
        return now - e.since < Math.max(0, timer) ? S_PENDING : S_DEFAULT;
    }

    /** The operator decides (PENDING only: a default already taken may still be turned into «свой» / «проверить»). */
    public static void decide(Entry e, int decision, long now, int friendTicks) {
        if (e == null || decision != D_FRIEND && decision != D_CHECK) {
            return;
        }
        e.decision = decision;
        e.until = decision == D_FRIEND ? now + Math.max(0, friendTicks) : -1;
    }

    /** Seconds left to decide (0 once the default is taken or a decision made). */
    public static int secondsLeft(Entry e, long now, int timer) {
        if (e == null || e.decision != D_NONE) {
            return 0;
        }
        return (int) Math.max(0, (e.since + timer - now + 19) / 20);
    }

    /** Never fired at, no warnings: pending and «проверить» (the friend - through its class). */
    public static boolean holdFire(int state) {
        return state == S_PENDING || state == S_CHECK;
    }

    /** It is a friend now. */
    public static boolean friend(int state) {
        return state == S_FRIEND;
    }

    /** It waits in the decision list (pending). */
    public static boolean listed(int state) {
        return state == S_PENDING;
    }
}
