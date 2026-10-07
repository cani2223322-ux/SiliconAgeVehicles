package com.sva.vehicle;

/**
 * Wave 2, ★7 repair garage: the repair price and its pace, the garage's buffer and comparator - pure.
 *
 * What a vehicle really keeps about its condition is the hull (HP of max: «износ» is the missing share) and the
 * engine heat (0..100 %, it stops at 100). The garage shows those plus the charge (and the drill head's life) and
 * repairs the first two:
 * - «Только корпус»: the hull to full - EU_PER_HP EU per missing HP and STEEL_FULL steel ingots for a whole hull
 *   (rounded up; IRON_PER_STEEL iron ingots stand in for one steel ingot);
 * - «Чинить всё»: the same plus the engine cooled down to 0 (EU_PER_HEAT EU per % of heat) - also lifts an overheat stop.
 * The materials are taken when the job starts, the EU over the job: DURATION_MIN..DURATION_MAX ticks, EU_RATE a tick.
 * Example: a buggy (200 HP) at 62 % -> 76 HP x 200 = 15 200 EU + ceil(0.38 x 8) = 4 steel (or 8 iron).
 */
public final class GarageMath {

    public static final double EU_PER_HP = 200, EU_PER_HEAT = 100;
    public static final int STEEL_FULL = 8, IRON_PER_STEEL = 2;
    public static final int DURATION_MIN = 100, DURATION_MAX = 600;
    /** EU a tick a repair draws at most before it gets slower (the job's length grows up to DURATION_MAX). */
    public static final int EU_RATE = 2000;
    /** The garage's buffer and input (HV: 512 EU/t). */
    public static final int BUFFER = 2000000;
    /** Repair modes (the GUI buttons). */
    public static final int ALL = 0, HULL = 1;

    private GarageMath() {
    }

    /** HP missing (0 when full or the max is bad). */
    public static double missing(float hp, float max) {
        return max > 0 && hp < max ? max - Math.max(0, hp) : 0;
    }

    /** EU for a repair. */
    public static long euCost(float hp, float max, double heat, int mode) {
        double eu = missing(hp, max) * EU_PER_HP + (mode == ALL ? Math.max(0, heat) * EU_PER_HEAT : 0);
        return (long) Math.ceil(eu - 1e-9);
    }

    /** Steel ingots for a repair (0 when the hull is whole: cooling alone needs no metal). */
    public static int steelCost(float hp, float max) {
        double m = missing(hp, max);
        if (m <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(m / max * STEEL_FULL - 1e-9));
    }

    /** Is there anything to do in this mode? */
    public static boolean needed(float hp, float max, double heat, int mode) {
        return missing(hp, max) > 0 || mode == ALL && heat > 0;
    }

    /** The job's length in ticks for its EU. */
    public static int duration(long eu) {
        long d = (eu + EU_RATE - 1) / EU_RATE;
        return (int) Math.max(DURATION_MIN, Math.min(DURATION_MAX, d));
    }

    /** EU a tick for a job (the whole cost over its duration, at least 1 while anything is left). */
    public static long perTick(long eu) {
        if (eu <= 0) {
            return 0;
        }
        return Math.max(1, (eu + duration(eu) - 1) / duration(eu));
    }

    /**
     * Materials: how many steel and iron ingots to take for `steel` steel (steel first, the rest as iron x2).
     * @return {steel, iron} or null when there isn't enough
     */
    public static int[] pay(int steel, int haveSteel, int haveIron) {
        if (steel <= 0) {
            return new int[] {0, 0};
        }
        int s = Math.min(steel, Math.max(0, haveSteel));
        int iron = (steel - s) * IRON_PER_STEEL;
        if (iron > Math.max(0, haveIron)) {
            return null;
        }
        return new int[] {s, iron};
    }

    /** Comparator 0..15 from the job's progress (0 idle; any progress at least 1, 15 when done). */
    public static int comparator(boolean working, double frac) {
        if (!working) {
            return 0;
        }
        return frac >= 1 ? 15 : Math.max(1, Math.min(14, 1 + (int) Math.floor(Math.max(0, frac) * 14)));
    }
}
