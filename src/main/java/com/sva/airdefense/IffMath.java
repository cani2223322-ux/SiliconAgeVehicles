package com.sva.airdefense;

/**
 * Wave A (plan-next §9 A10, mock-up veh_ad_impl_a_3_tower_iff): IFF interrogation from the tower and the command post - pure,
 * the self-test checks it. The tower's (and its friendly posts') radars interrogate every aircraft they see near the
 * airport (its zone + IFF near); an aircraft becomes an «нарушитель» when
 * <ul>
 * <li>NO_REPLY - the radar sees it, the transponder does not answer (off / standby / none);</li>
 * <li>MISMATCH - it answers with a code the tower gave another aircraft (a mode S spoof / a wrong code dialled);</li>
 * <li>UNKNOWN - it answers with a code of the airport's own range the tower never gave anyone.</li>
 * </ul>
 * The track then runs CLEAR -> SUSPECT (seen so for CONFIRM ticks: no alarm on a blip) -> WARNED (the tower's alarm, the
 * call to the pilot «борт без ответчика, ответьте...» in the chat / by CPDLC to a liner) -> after the response time
 * HANDED to the air defence (airdefense.iffHandoff on: the friendly command posts classify it a violator - their rules of
 * engagement decide) or, with the handoff off (the default - warnings only, as the air defence's own default), the call is
 * repeated. The pilot answers by switching the transponder on (the reason goes: RESOLVED) or - the liner - by any response
 * to the tower's uplink (ANSWERED: known, no handoff while it stays near).
 */
public final class IffMath {

    public static final int R_NONE = 0, R_NO_REPLY = 1, R_MISMATCH = 2, R_UNKNOWN = 3;
    public static final String[] REASON_KEYS = {"none", "noReply", "mismatch", "unknown"};
    public static final int CLEAR = 0, SUSPECT = 1, WARNED = 2, HANDED = 3, ANSWERED = 4;
    public static final String[] STATE_KEYS = {"clear", "suspect", "warned", "handed", "answered"};
    /** step() actions. */
    public static final int A_NONE = 0, A_WARN = 1, A_REMIND = 2, A_HANDOFF = 3, A_RESOLVED = 4;
    /** Seen without a proper reply this long before the alarm (ticks; 3 interrogations of the survey radar). */
    public static final int CONFIRM = 100;

    private IffMath() {
    }

    /**
     * Why the aircraft is suspect (R_NONE: it is not).
     * @param inRange its code is in the airport's range
     * @param assignedHere the tower gave this aircraft its code
     * @param assignedOther the tower gave its code to another aircraft
     */
    public static int reason(boolean replied, int code, boolean inRange, boolean assignedHere, boolean assignedOther) {
        if (!replied) {
            return R_NO_REPLY;
        }
        if (code == XpdrMath.EMERGENCY || code == XpdrMath.NO_RADIO || code == XpdrMath.HIJACK) {
            return R_NONE;                                          // the special codes have their own handling
        }
        if (assignedOther && !assignedHere) {
            return R_MISMATCH;
        }
        if (inRange && !assignedHere) {
            return R_UNKNOWN;
        }
        return R_NONE;
    }

    /** One aircraft's IFF track at one tower. */
    public static final class Track {
        public int state = CLEAR, reason = R_NONE;
        public long since = -1, warnedAt = -1;
    }

    /**
     * One interrogation round. @param response ticks the pilot has after the call; @param handoff the handoff is on
     * @return the action (A_*): what the caller says / does now
     */
    public static int step(Track t, int reason, long now, int confirm, int response, boolean handoff) {
        t.reason = reason;
        if (reason == R_NONE) {
            int was = t.state;
            t.state = CLEAR;
            t.since = -1;
            t.warnedAt = -1;
            return was == WARNED || was == HANDED || was == ANSWERED ? A_RESOLVED : A_NONE;
        }
        switch (t.state) {
            case CLEAR:
                t.state = SUSPECT;
                t.since = now;
                return confirm <= 0 ? step(t, reason, now, confirm, response, handoff) : A_NONE;
            case SUSPECT:
                if (now - t.since >= confirm) {
                    t.state = WARNED;
                    t.warnedAt = now;
                    return A_WARN;
                }
                return A_NONE;
            case WARNED:
                if (now - t.warnedAt >= Math.max(1, response)) {
                    t.warnedAt = now;
                    if (handoff) {
                        t.state = HANDED;
                        return A_HANDOFF;
                    }
                    return A_REMIND;
                }
                return A_NONE;
            default:
                return A_NONE;                                      // HANDED / ANSWERED hold while the reason lasts
        }
    }

    /** The pilot answered by datalink: a warned (or handed) track is known now - no handoff (handed: taken back). */
    public static boolean answer(Track t) {
        if (t.state == WARNED || t.state == HANDED || t.state == SUSPECT) {
            boolean back = t.state == HANDED;
            t.state = ANSWERED;
            return back;
        }
        return false;
    }

    /** Seconds left until the handoff (WARNED only; 0 otherwise). */
    public static int secondsLeft(Track t, long now, int response) {
        return t.state == WARNED ? (int) Math.max(0, (t.warnedAt + response - now + 19) / 20) : 0;
    }

    /** The state is an intruder the screens mark (warned / handed). */
    public static boolean intruder(int state) {
        return state == WARNED || state == HANDED;
    }
}
