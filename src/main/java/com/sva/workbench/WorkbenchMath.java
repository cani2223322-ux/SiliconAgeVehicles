package com.sva.workbench;

import com.sc.energy.Tier;
import com.sc.machine.UpgradeType;

/**
 * Engineering Workbench numbers (pure - the self-test checks them). Upgrades follow the core machines' rules:
 * counts stack, capped at UpgradeType.MAX_EFFECTIVE; Overclocker = time x0.7 and EU a tick x1.6 (so an
 * assembly costs x1.12 EU); Transformer = +1 input tier; Energy Storage = a bigger buffer; Economizer = EU x0.8
 * (at most UpgradeType.MAX_GENERATOR_EFFECTIVE count, as the generators' economizer).
 */
public final class WorkbenchMath {

    public static final Tier BASE_TIER = Tier.MV;
    public static final int BASE_BUFFER = 2000000, STORAGE_PER_UPGRADE = 1000000;
    public static final double OC_TIME = 0.7, OC_EU = 1.6, ECO_EU = 0.8;

    private WorkbenchMath() {
    }

    public static int cap(int n) {
        return Math.max(0, Math.min(UpgradeType.MAX_EFFECTIVE, n));
    }

    public static int ticks(int base, int overclockers) {
        if (base <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(base * Math.pow(OC_TIME, cap(overclockers))));
    }

    public static long eu(long base, int overclockers, int economizers) {
        if (base <= 0) {
            return 0;
        }
        double e = base * Math.pow(OC_TIME * OC_EU, cap(overclockers))
                * Math.pow(ECO_EU, Math.min(UpgradeType.MAX_GENERATOR_EFFECTIVE, Math.max(0, economizers)));
        return Math.max(1L, Math.round(e));
    }

    public static int buffer(int storageUpgrades) {
        return BASE_BUFFER + cap(storageUpgrades) * STORAGE_PER_UPGRADE;
    }

    public static Tier inputTier(int transformers, boolean universal) {
        if (universal) {
            return Tier.max();
        }
        return Tier.byOrdinal(BASE_TIER.ordinal() + cap(transformers));
    }

    /**
     * The input tier a vehicle of `tier` needs: up to EV the bare MV workbench builds it; QV wants HV, XV EV,
     * SV IV - one Transformer upgrade per tier above EV (the plan's «для QV–SV нужен трансформатор»).
     */
    public static Tier requiredInput(Tier tier) {
        if (tier == null || tier.ordinal() <= Tier.EV.ordinal()) {
            return BASE_TIER;
        }
        return Tier.byOrdinal(tier.ordinal() - 3);
    }

    /** Transformer upgrades a bare workbench needs for a recipe of `tier` (0 up to EV, QV 1, XV 2, SV 3). */
    public static int transformersFor(Tier tier) {
        return requiredInput(tier).ordinal() - BASE_TIER.ordinal();
    }

    public static boolean canBuild(Tier recipeTier, Tier input) {
        return input.ordinal() >= requiredInput(recipeTier).ordinal();
    }

    /** EU to pay this tick: an even share of what is left over the ticks left. */
    public static long share(long totalEu, long paid, int totalTicks, int progress) {
        long left = Math.max(0, totalEu - paid);
        int ticksLeft = Math.max(1, totalTicks - progress);
        return (left + ticksLeft - 1) / ticksLeft;
    }

    /** Comparator signal 0..15 for the assembly progress. */
    public static int comparator(int progress, int total) {
        if (total <= 0 || progress <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(15, progress * 15 / total));
    }
}
