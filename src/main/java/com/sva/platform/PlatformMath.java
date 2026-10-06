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
 * </ul>
 */
public final class PlatformMath {

    public static final int MAX_PLATES = 64;
    public static final double BONUS_PER_PLATE = 0.10, BONUS_CAP = 2.0, OC_RATE = 1.6;
    public static final int STORAGE_PER_UPGRADE = 100000;
    public static final int REPAIR_EVERY = 60, STEPS_PER_INGOT = 10;
    public static final double REPAIR_STEP = 0.01;
    /** mB a tick of each gas the gas module moves (the Gas Column's own pump rate). */
    public static final int GAS_RATE = 25;

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
        double r = input.getVoltage() * config * Math.pow(OC_RATE, WorkbenchMath.cap(overclockers)) * (1 + Math.max(0, Math.min(BONUS_CAP, plateBonus)));
        if (Double.isNaN(r)) {
            return 1;
        }
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(r)));
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
