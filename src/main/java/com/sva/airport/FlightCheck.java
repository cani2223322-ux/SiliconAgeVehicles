package com.sva.airport;

/**
 * Airport wave B1 (plan-next §9, item 40): the flight inspection («облёт-проверка») of a new or changed ILS - a pure state
 * machine (block.AirportDataSVA keeps one per station and feeds it; the self-test drives it).
 * <ul>
 * <li>WAITING (a new station, or its antennas / angle / runway changed - reset()): limited to CAT I (IlsCat). The first
 * aircraft with its transponder on that comes inbound in the beam's coverage between START_MAX and START_MIN blocks
 * before the threshold starts it (RUNNING, that aircraft only).</li>
 * <li>RUNNING: every sample (the server's, every SAMPLE_TICKS) brings the beam's error now - the localizer's course shifted
 * at the threshold (blocks: the array's offset + its bend there) and the glide slope's angle error (deg: its bend) - while
 * the aircraft stays in the coverage. Leaving it, or a station change, aborts back to WAITING. Over the threshold it
 * finishes: with at least MIN_SAMPLES over MIN_SPAN blocks the result is the category those errors allow (CAT III: the
 * course within LOC_III, the glide within GS_III x θ, the TCH in IlsCat's CAT III window; CAT II within the _II ones;
 * else CAT I) and a hint - the worst error's cause (IlsCat.I_*); too few - WAITING again.</li>
 * <li>DONE: the station is «ОТКАЛИБРОВАН, CAT x» (the pilot is told in the chat and on the MCDU; the world data keeps it for
 * the wave B2 tower).</li>
 * </ul>
 */
public final class FlightCheck {

    public static final int WAITING = 0, RUNNING = 1, DONE = 2;
    public static final double START_MAX = 1200, START_MIN = 100, MIN_SPAN = 150, LOC_III = 0.3, LOC_II = 0.75, GS_III = 0.075, GS_II = 0.12;
    public static final int MIN_SAMPLES = 12, SAMPLE_TICKS = 10;

    public int state = WAITING, aircraft = -1, samples, result, hint = -1;
    public double maxLoc, maxGs, firstAlong, lastAlong;

    /** A station change: back to WAITING (limited to CAT I until a new inspection). */
    public void reset() {
        state = WAITING;
        aircraft = -1;
        samples = 0;
        result = 0;
        hint = -1;
        maxLoc = 0;
        maxGs = 0;
    }

    /**
     * An aircraft `id` `along` blocks from the threshold (negative before it), in the beam's coverage or not, its transponder
     * on or not: starts the inspection when waiting. @return started now
     */
    public boolean offer(int id, double along, boolean inCover, boolean transponder) {
        if (state != WAITING || !inCover || !transponder || along > -START_MIN || along < -START_MAX) {
            return false;
        }
        state = RUNNING;
        aircraft = id;
        samples = 0;
        maxLoc = 0;
        maxGs = 0;
        firstAlong = along;
        lastAlong = along;
        return true;
    }

    /**
     * A sample of the running inspection from aircraft `id`: its place, the coverage, the beam's errors now. Over the
     * threshold it finishes (finish()). @return the state after it
     */
    public int sample(int id, double along, boolean inCover, double locShift, double gsErr, double tch, double angle) {
        if (state != RUNNING || id != aircraft) {
            return state;
        }
        if (along >= 0) {
            return finish(tch, angle);
        }
        if (!inCover) {
            reset();
            return state;
        }
        samples++;
        lastAlong = along;
        maxLoc = Math.max(maxLoc, Math.abs(locShift));
        maxGs = Math.max(maxGs, Math.abs(gsErr));
        return state;
    }

    /** The inspection's end: the category and the hint, or back to WAITING with too few samples. */
    public int finish(double tch, double angle) {
        if (samples < MIN_SAMPLES || lastAlong - firstAlong < MIN_SPAN) {
            reset();
            return state;
        }
        double th = Math.max(0.5, angle);
        boolean tch3 = tch >= IlsCat.TCH_III_LO && tch <= IlsCat.TCH_III_HI, tch2 = tch >= IlsCat.TCH_II_LO && tch <= IlsCat.TCH_II_HI;
        if (maxLoc <= LOC_III && maxGs <= GS_III * th && tch3) {
            result = 3;
        } else if (maxLoc <= LOC_II && maxGs <= GS_II * th && tch2) {
            result = 2;
        } else {
            result = 1;
        }
        // the hint: the worst error relative to its CAT III limit
        double l = maxLoc / LOC_III, g = maxGs / (GS_III * th);
        hint = result == 3 ? -1 : !tch3 && l <= 1 && g <= 1 ? IlsCat.I_TCH : l >= g ? IlsCat.I_LOC_CRIT : IlsCat.I_GS_CRIT;
        state = DONE;
        aircraft = -1;
        return state;
    }

    /** The calibrated category (0: none yet). */
    public int calibCat() {
        return state == DONE ? result : 0;
    }

    /** The world data's / sync word: state | result << 2 | (hint + 1) << 4. */
    public int pack() {
        return state & 3 | (result & 3) << 2 | (hint + 1 & 63) << 4;
    }

    public void unpack(int w) {
        state = w & 3;
        result = w >> 2 & 3;
        hint = (w >> 4 & 63) - 1;
        if (state == RUNNING) {
            state = WAITING;                                        // a save in the middle of one: start again
        }
    }

    /** The localizer's course shift at the threshold (blocks): the array's offset + its bend (deg) over its distance there. */
    public static double locShift(double arrayLateral, double bendDeg, double arrayAlong) {
        return arrayLateral + Math.tan(Math.toRadians(bendDeg)) * Math.max(0, arrayAlong);
    }
}
