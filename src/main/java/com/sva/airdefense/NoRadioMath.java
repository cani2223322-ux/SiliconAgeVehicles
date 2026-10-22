package com.sva.airdefense;

/**
 * Wave A (plan-next §9 A5, mock-up veh_ad_impl_a_4_7600): 7600 («нет связи») through the command post - pure, the self-test
 * checks it. A track that starts squawking 7600 gets the mark «НЕТ СВЯЗИ», a timer (airdefense.noRadioDecisionSeconds) and a
 * line in the post's decision list; the operator answers:
 * <ul>
 * <li>«Свой» - a friend for airdefense.noRadioFriendMinutes (as «свой на 10 минут»);</li>
 * <li>«Проверить» - track only: no fire, no warnings while it squawks 7600 (the operator watches it);</li>
 * <li>«Враждебный» (audit 2026-10-22, bug 8) - no grace at all: classed at once as any other track (a stranger, a violator
 * in a forbidden zone), the launchers take it by their rules and it may be assigned;</li>
 * <li>nothing before the timer runs out - the default: it is classed as any other track and the launchers take it by their
 * rules.</li>
 * </ul>
 * Until then (PENDING) it is NO_RADIO - never fired at.
 * <p>
 * Audit 2026-10-22 (bug 8): an entry is an EPISODE MEMORY of one vehicle (kept by its persistent id, VehicleState.vehId) -
 * the timer counts the time it squawked 7600 in all (used + the current episode). Switching to 7000 / standby for a cycle
 * and back no longer starts a fresh timer: the episode only pauses, and the entry is forgotten only after it has not
 * squawked 7600 for the memory (airdefense.noRadioMemoryMinutes). The operator's decision stays with it too. Launchers
 * without a command post give the same memory (AirDefenseDataSVA's, not a post's) the documented 2-minute grace
 * (XpdrMath.NO_RADIO_GRACE, grace()) - the transponder's own timer, reset by any code change, is no longer what counts.
 */
public final class NoRadioMath {

    /** The operator's decision. */
    public static final int D_NONE = 0, D_FRIEND = 1, D_CHECK = 2, D_HOSTILE = 3;
    /** The state now. */
    public static final int S_PENDING = 0, S_FRIEND = 1, S_CHECK = 2, S_DEFAULT = 3, S_HOSTILE = 4;
    public static final String[] STATE_KEYS = {"pending", "friend", "check", "default", "hostile"};

    private NoRadioMath() {
    }

    /** One vehicle's 7600 episodes on a post (or for the launchers without one). */
    public static final class Entry {
        /** The current episode began (ticks; -1: not squawking 7600 now); the earlier episodes' ticks; last seen squawking it. */
        public long since, used, lastSeen;
        /** The decision and when the friend's time ends. */
        public long until = -1;
        public int decision = D_NONE;
        /** The default already logged. */
        public boolean defaultLogged;

        public Entry(long since) {
            this.since = since;
            this.lastSeen = since;
        }
    }

    /** Ticks squawked 7600 in all (the earlier episodes and the current one). */
    public static long elapsed(Entry e, long now) {
        if (e == null) {
            return 0;
        }
        return Math.max(0, e.used) + (e.since >= 0 ? Math.max(0, now - e.since) : 0);
    }

    /**
     * Audit review 2026-10-22: a running episode nobody has seen for longer than this (ticks) ended at its last sighting - the
     * aircraft flew out of sight still on 7600, the post lost its power or its chunk, the server stopped. Before, the time
     * unseen counted on: a post back after 5 minutes (or any launcher without a post, days later) put a returning 7600 at
     * its default at once, with no decision window / no grace.
     */
    public static final long GAP = RadarMath.TRACK_TIMEOUT;

    /** The moment a running episode ended for the clock: now, or its last sighting when that is more than GAP ago. */
    static long endOf(Entry e, long now) {
        return e.since >= 0 && now - e.lastSeen > GAP ? Math.max(e.since, e.lastSeen) : now;
    }

    /**
     * Seen squawking 7600 now: a paused memory goes on (the time so far kept); a running one not seen for longer than GAP is
     * closed at its last sighting first (audit review: the time unseen does not count).
     */
    public static void seen(Entry e, long now) {
        if (e == null) {
            return;
        }
        if (e.since >= 0 && now - e.lastSeen > GAP) {
            off(e, now);
        }
        if (e.since < 0) {
            e.since = now;
        }
        e.lastSeen = now;
    }

    /** Lost (or the post off): the episode pauses, its time added - up to its last sighting when that is more than GAP ago. */
    public static void off(Entry e, long now) {
        if (e == null || e.since < 0) {
            return;
        }
        long end = endOf(e, now);
        e.used += Math.max(0, end - e.since);
        e.since = -1;
        e.lastSeen = end;
    }

    /**
     * Seen squawking another code: the episode pauses (off) and a «проверить» goes with it (audit review 2026-10-22: it held
     * the fire for good - 7000 between the launchers' warnings, 7600 again when they came, no new timer, no new question; it
     * is the decision about one radio failure, «свой» already lasted only while it squawked 7600). «Враждебный» stays.
     */
    public static void changed(Entry e, long now) {
        if (e == null) {
            return;
        }
        off(e, now);
        if (e.decision == D_CHECK) {
            e.decision = D_NONE;
        }
    }

    /**
     * Not seen squawking 7600 for longer than `memory` ticks: forgotten (a later 7600 starts a fresh timer). Audit review
     * 2026-10-22: a running episode too (it left still on 7600 and nobody saw it again) - before, it stayed for good.
     */
    public static boolean expired(Entry e, long now, long memory) {
        return e == null || now - e.lastSeen > Math.max(Math.max(0, memory), GAP);
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
        if (e.decision == D_HOSTILE) {
            return S_HOSTILE;
        }
        return elapsed(e, now) < Math.max(0, timer) ? S_PENDING : S_DEFAULT;
    }

    /** The operator decides (a default already taken may still be turned into «свой» / «проверить» / «враждебный»). */
    public static void decide(Entry e, int decision, long now, int friendTicks) {
        if (e == null || decision != D_FRIEND && decision != D_CHECK && decision != D_HOSTILE) {
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
        return (int) Math.max(0, (timer - elapsed(e, now) + 19) / 20);
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

    /**
     * A launcher without a command post: still in the 2-minute grace (`grace` ticks of 7600 in all, the episodes of its
     * memory together) and no post called it hostile.
     */
    public static boolean grace(Entry e, long now, int grace) {
        return e != null && e.decision != D_HOSTILE && elapsed(e, now) < Math.max(0, grace);
    }
}
