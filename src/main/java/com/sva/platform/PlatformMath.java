package com.sva.platform;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

import com.sc.energy.Tier;
import com.sva.vehicle.PlatformSize;
import com.sva.workbench.WorkbenchMath;

/**
 * Charging platform numbers (plan §9; pure - the self-test checks them).
 * <ul>
 * <li>Plates: orthogonally connected to the station, same Y, at most MAX_PLATES (flood fill from its 4 sides).</li>
 * <li>Fits: plates >= the class minimum and the bounding rectangle holds w x h (either way round).</li>
 * <li>Rate = input voltage x config x 1.6 per Overclocker x (1 + 10% per plate over the vehicle's minimum, at most +200%).</li>
 * <li>Buffer = input tier's buffer + 100 000 EU per Energy Storage; the Transformer raises the input tier (MV base).</li>
 * <li>Repair module: +1% hull every 60 ticks, a steel ingot pays 10 steps (the same 10% an ingot gives by hand).</li>
 * <li>Fast charging (2026-10-10): the «Быстрая зарядка» module (built into the SV station) x2, the «Форсаж» mode x3 (heats
 * the station: 2 min of boosting overheats it, it pauses until cooled - 30 s; helium from the gas module's column, 1 mB/s,
 * keeps it cool); several stations on one platform add up; a core energy storage touching the station feeds the vehicle
 * directly (bounded by the station's rate); the «Суперконденсатор станции» (50M EU, 2 048 EU/t from the grid) empties into
 * the vehicle at 500 000 EU/t - 50M in 5 s.</li>
 * </ul>
 */
public final class PlatformMath {

    /** 11 x 11: the Singular Ship (7x7 = 49) gets its +200% bonus at 69 plates. The flood fill is 121 x 4 lookups every 5 s. */
    public static final int MAX_PLATES = 121;
    public static final double BONUS_PER_PLATE = 0.10, BONUS_CAP = 2.0, OC_RATE = 1.6;
    public static final int STORAGE_PER_UPGRADE = 100000;
    public static final int REPAIR_EVERY = 60, STEPS_PER_INGOT = 10;
    public static final double REPAIR_STEP = 0.01;
    /** mB a tick of each gas the gas module moves (the Gas Column's own pump rate). */
    public static final int GAS_RATE = 25;
    /** «Быстрая зарядка» (module; the SV station has it built in) and «Форсаж» (the boost mode) rate multipliers. */
    public static final int FAST_MULT = 2, BOOST_MULT = 3;
    /**
     * Boost heat: +1 a tick of boosting while it charges, overheated at HEAT_MAX (2 min) - then the station pauses until it
     * is cold again (-HEAT_COOL a tick: 30 s from the top). Helium (1 mB every HELIUM_EVERY ticks of boosting) keeps it cool.
     */
    public static final int HEAT_MAX = 2400, HEAT_COOL = 4, HELIUM_EVERY = 20;
    /** «Суперконденсатор станции»: buffer, grid input a tick (any voltage), dump into the station's vehicle a tick. */
    public static final int CAP_BUFFER = 50000000, CAP_INPUT = 2048, CAP_DUMP = 500000;

    private PlatformMath() {
    }

    /** What the flood fill asks the world. */
    public interface Plates {
        boolean isPlate(int x, int z);
    }

    public static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static int keyX(long k) {
        return (int) (k >> 32);
    }

    public static int keyZ(long k) {
        return (int) k;
    }

    /**
     * Plates connected (4-way) to the station at (sx, sz), the station itself excluded, at most `max`, in BFS order.
     */
    public static Set<Long> floodFill(Plates world, int sx, int sz, int max) {
        Set<Long> out = new LinkedHashSet<Long>();
        Set<Long> seen = new java.util.HashSet<Long>();
        ArrayDeque<long[]> q = new ArrayDeque<long[]>();
        seen.add(Long.valueOf(key(sx, sz)));
        q.add(new long[] {sx, sz});
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && out.size() < max) {
            long[] c = q.poll();
            for (int[] o : d) {
                int x = (int) c[0] + o[0], z = (int) c[1] + o[1];
                Long k = Long.valueOf(key(x, z));
                if (!seen.add(k) || !world.isPlate(x, z)) {
                    continue;
                }
                if (out.size() >= max) {
                    break;
                }
                out.add(k);
                q.add(new long[] {x, z});
            }
        }
        return out;
    }

    /** {minX, minZ, maxX, maxZ} of the plates, or null when there are none. */
    public static int[] bounds(Set<Long> plates) {
        if (plates == null || plates.isEmpty()) {
            return null;
        }
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (Long k : plates) {
            int x = keyX(k.longValue()), z = keyZ(k.longValue());
            x0 = Math.min(x0, x);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            z1 = Math.max(z1, z);
        }
        return new int[] {x0, z0, x1, z1};
    }

    public static boolean fits(PlatformSize s, int plates, int spanX, int spanZ) {
        if (s == null || plates < s.minPlates()) {
            return false;
        }
        return spanX >= s.w && spanZ >= s.h || spanX >= s.h && spanZ >= s.w;
    }

    /** Speed bonus (0..2): +10% a plate over the class minimum, at most +200%. */
    public static double plateBonus(int plates, PlatformSize s) {
        int over = Math.max(0, plates - (s == null ? 0 : s.minPlates()));
        return Math.min(BONUS_CAP, over * BONUS_PER_PLATE);
    }

    /** The station's input tier: its own (MV) + one per Transformer upgrade. */
    public static Tier inputTier(Tier base, int transformers) {
        return Tier.byOrdinal(base.ordinal() + WorkbenchMath.cap(transformers));
    }

    public static int buffer(Tier input, int storage) {
        return input.getBuffer() + WorkbenchMath.cap(storage) * STORAGE_PER_UPGRADE;
    }

    /** EU a tick to one vehicle (at least 1). */
    public static int rate(Tier input, int overclockers, double plateBonus, double config) {
        return rate(input, overclockers, plateBonus, config, false, false);
    }

    /** EU a tick to one vehicle with the fast-charge module (x2) and the boost (x3) - at least 1. */
    public static int rate(Tier input, int overclockers, double plateBonus, double config, boolean fast, boolean boost) {
        double r = input.getVoltage() * config * Math.pow(OC_RATE, WorkbenchMath.cap(overclockers)) * (1 + Math.max(0, Math.min(BONUS_CAP, plateBonus)))
                * multiplier(fast, boost);
        if (Double.isNaN(r)) {
            return 1;
        }
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(r)));
    }

    public static int multiplier(boolean fast, boolean boost) {
        return (fast ? FAST_MULT : 1) * (boost ? BOOST_MULT : 1);
    }

    /** Rates of several stations on one platform, added up (clamped to an int). */
    public static int sum(int... rates) {
        long t = 0;
        for (int r : rates) {
            t += Math.max(0, r);
        }
        return (int) Math.min(Integer.MAX_VALUE, t);
    }

    /**
     * Which vehicle the stations of one platform serve this tick: the same index into the same list (the vehicles that need
     * something, sorted by entity id) from the world time - so they all charge the same vehicle and their rates add up.
     */
    public static int sharedPick(long worldTime, int n) {
        return n <= 0 ? -1 : (int) (Math.abs(worldTime) % n);
    }

    /**
     * Of the stations on one platform, the one that shows the plates' look (the others' packets don't touch them, so they
     * never flicker): the lowest x, then y, then z. `peers` = {x, y, z} of the others.
     */
    public static boolean isMaster(int x, int y, int z, java.util.List<int[]> peers) {
        for (int[] p : peers) {
            if (p[0] < x || p[0] == x && (p[1] < y || p[1] == y && p[2] < z)) {
                return false;
            }
        }
        return true;
    }

    /** EU a core energy storage beside the station adds this tick: what the station's rate leaves, never past the room. */
    public static int pullNeed(int rate, int fromBuffer, double roomAfter) {
        return (int) Math.max(0, Math.min((long) rate - fromBuffer, (long) Math.floor(Math.max(0, roomAfter))));
    }

    /** Next heat: +1 while boosting and charging without helium, else -HEAT_COOL; within 0..HEAT_MAX. */
    public static int heatNext(int heat, boolean heating, boolean helium) {
        if (heating && !helium) {
            return Math.min(HEAT_MAX, heat + 1);
        }
        return Math.max(0, Math.min(HEAT_MAX, heat) - HEAT_COOL);
    }

    /** Overheated at the top, until it is cold again. */
    public static boolean overheatNext(boolean over, int heat) {
        return heat >= HEAT_MAX || over && heat > 0;
    }

    /** Ticks of boosting (from `heat`) before it overheats. */
    public static int ticksToOverheat(int heat) {
        return Math.max(0, HEAT_MAX - heat);
    }

    /** Ticks an overheated (or warm) station needs to cool down. */
    public static int ticksToCool(int heat) {
        return (Math.max(0, heat) + HEAT_COOL - 1) / HEAT_COOL;
    }

    /** mB of helium `ticks` of cooled boosting burn. */
    public static int heliumFor(int ticks) {
        return (Math.max(0, ticks) + HELIUM_EVERY - 1) / HELIUM_EVERY;
    }

    /** EU the supercapacitor takes from the grid now: its room, CAP_INPUT a tick (`taken` already this tick). */
    public static int capInput(int offered, int stored, int taken) {
        return Math.max(0, Math.min(offered, Math.min(CAP_BUFFER - stored, CAP_INPUT - taken)));
    }

    /** EU the supercapacitor gives the vehicle now: CAP_DUMP a tick (`given` already this tick), its charge, the room. */
    public static int capDump(int stored, int given, double room) {
        return (int) Math.max(0, Math.min(Math.min(stored, CAP_DUMP - given), Math.floor(Math.max(0, room))));
    }

    /** Comparator 0..15 from a buffer's fill (any charge = at least 1). */
    public static int fillSignal(long stored, long max) {
        return stored <= 0 || max <= 0 ? 0 : (int) Math.min(15, 1 + 14L * stored / max);
    }

    /** The next vehicle to serve among n (round robin), -1 when there is none. */
    public static int nextIndex(int last, int n) {
        if (n <= 0) {
            return -1;
        }
        return last < 0 ? 0 : (last + 1) % n;
    }

    /** Hull after one repair step (capped). */
    public static float repairStep(float hp, float max) {
        return (float) Math.min(max, hp + max * REPAIR_STEP);
    }

    /** Ingots to take before a step, given the steps already paid for (0 or 1). */
    public static int ingotsFor(int paidSteps) {
        return paidSteps > 0 ? 0 : 1;
    }

    /** Ingots a repair from `hp` to full costs. */
    public static int ingotsToFull(float hp, float max) {
        if (max <= 0 || hp >= max) {
            return 0;
        }
        int steps = (int) Math.ceil((max - hp) / (max * REPAIR_STEP) - 1e-9);
        return (steps + STEPS_PER_INGOT - 1) / STEPS_PER_INGOT;
    }

    /** Comparator 0..15 from the served vehicle's charge (0 without one; any charge = at least 1). */
    public static int comparator(boolean vehicle, double frac) {
        if (!vehicle || !(frac > 0)) {
            return 0;
        }
        return frac >= 1 ? 15 : Math.min(14, 1 + (int) Math.floor(frac * 14));
    }

    /** Redstone the station emits: 15 when the option is on and the vehicle is full. */
    public static int fullSignal(boolean option, boolean full) {
        return option && full ? 15 : 0;
    }

    /** Seconds to full at `perTick` EU (measured); +inf when nothing comes in. */
    public static double etaSeconds(double room, double perTick) {
        if (room <= 0) {
            return 0;
        }
        return perTick > 0 ? room / perTick / 20.0 : Double.POSITIVE_INFINITY;
    }
}
