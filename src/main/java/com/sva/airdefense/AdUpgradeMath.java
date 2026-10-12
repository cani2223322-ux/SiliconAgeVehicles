package com.sva.airdefense;

import com.sc.energy.Tier;
import com.sc.machine.UpgradeType;

/**
 * Air-defence upgrades wave (2026-10-12, pure - the self-test checks it): what the core's (Silicon Age) upgrade modules do
 * in the four upgrade slots of a radar, a launcher and the command post. The numbers are the core machines'
 * (TileEntityMachineSC, UpgradeType's javadoc) where the core has them:
 * <ul>
 * <li>Universal Transformer - one is enough: any line voltage LV..SV, never overvolts, the input tier the top one (the core
 * machines, the charging station).</li>
 * <li>Transformer - +1 input tier each, stacking (capped at UpgradeType.MAX_EFFECTIVE as every core count).</li>
 * <li>Energy Storage - +UpgradeType.STORAGE_PER_UPGRADE (10 000) EU of buffer each over the block's own tier buffer, and
 * never less than two working ticks (the core's guard: an overclocked block must hold its own upkeep).</li>
 * <li>Overclocker - the work interval x0.7 each (the radar's scan, the gun's burst, the missile system's reload), the upkeep
 * x1.6 each. Our own floors keep a scan / a burst / a reload from going under MIN_* ticks; overclockers past the floor do
 * nothing and so cost nothing (usefulOc) - a stack of 16 doesn't burn x1844 for no gain.</li>
 * <li>Economizer - the EU used (upkeep and every shot) x0.8 each, at most UpgradeType.MAX_GENERATOR_EFFECTIVE count (the
 * Engineering Workbench's convention for a consumer; the core's own one is the generators' fuel).</li>
 * <li>Overdrive - radars only, the generators' trade turned into reach: range x1.15 each for upkeep x1.75 each, at most
 * MAX_GENERATOR_EFFECTIVE (four: x1.75 range for x9.4 power).</li>
 * <li>Puller - launchers only: every PULL_INTERVAL ticks up to PULL_ITEMS rounds from the inventories beside the structure
 * (the core machines' numbers).</li>
 * </ul>
 * Not taken: the adaptive transformer and the output splitter (energy storages only in the core), heat sink, quality,
 * ejector, tank extension, charge booster, radiation shielding - nothing for them to act on here.
 */
public final class AdUpgradeMath {

    /** Upgrade slots per block: the core machines' four (TileEntityMachineSC.UPGRADE_SLOTS). */
    public static final int SLOTS = 4;
    /** The block kinds (which modules fit). */
    public static final int B_RADAR = 0, B_LAUNCHER = 1, B_KP = 2;
    public static final double OC_TIME = 0.7, OC_EU = 1.6, ECO_EU = 0.8, OD_RANGE = 1.15, OD_EU = 1.75;
    /** Floors (ticks): a scan, a gun burst, a missile reload. */
    public static final int MIN_SCAN = 5, MIN_BURST = 2, MIN_RELOAD = 10;
    public static final int PULL_INTERVAL = 4, PULL_ITEMS = 16;

    private AdUpgradeMath() {
    }

    /** A stacking count (overclockers, transformers, storages): 0 .. MAX_EFFECTIVE. */
    public static int cap(int n) {
        return Math.max(0, Math.min(UpgradeType.MAX_EFFECTIVE, n));
    }

    /** The generators' kinds (economizer, overdrive): 0 .. MAX_GENERATOR_EFFECTIVE. */
    public static int capGen(int n) {
        return Math.max(0, Math.min(UpgradeType.MAX_GENERATOR_EFFECTIVE, n));
    }

    /** The module fits this block kind's upgrade slot. */
    public static boolean accepts(int block, UpgradeType t) {
        if (t == null) {
            return false;
        }
        switch (t) {
            case UNIVERSAL_TRANSFORMER:
            case TRANSFORMER:
            case ENERGY_STORAGE:
            case ECONOMIZER:
                return true;
            case OVERCLOCKER:
                return block == B_RADAR || block == B_LAUNCHER;      // the command post has no work interval to speed up
            case OVERDRIVE:
                return block == B_RADAR;
            case PULLER:
                return block == B_LAUNCHER;
            default:
                return false;
        }
    }

    /** The modules that fit, in the order the screens list them. */
    public static UpgradeType[] accepted(int block) {
        UpgradeType[] all = {UpgradeType.UNIVERSAL_TRANSFORMER, UpgradeType.TRANSFORMER, UpgradeType.ENERGY_STORAGE, UpgradeType.OVERCLOCKER,
                UpgradeType.ECONOMIZER, UpgradeType.OVERDRIVE, UpgradeType.PULLER};
        int n = 0;
        for (UpgradeType t : all) {
            n += accepts(block, t) ? 1 : 0;
        }
        UpgradeType[] out = new UpgradeType[n];
        n = 0;
        for (UpgradeType t : all) {
            if (accepts(block, t)) {
                out[n++] = t;
            }
        }
        return out;
    }

    /** The highest voltage taken: the top with a universal transformer, else the own tier + one per transformer. */
    public static Tier inputTier(Tier base, int transformers, boolean universal) {
        if (universal) {
            return Tier.max();
        }
        return Tier.byOrdinal((base == null ? Tier.LV : base).ordinal() + cap(transformers));
    }

    /** The buffer: the own tier's + 10 000 per storage, at least two working ticks. */
    public static int buffer(Tier base, int storage, int upkeep) {
        long b = (long) (base == null ? Tier.LV : base).getBuffer() + (long) cap(storage) * UpgradeType.STORAGE_PER_UPGRADE;
        return (int) Math.min(Integer.MAX_VALUE / 2, Math.max(b, 2L * Math.max(0, upkeep)));
    }

    /** An interval with `oc` overclockers: x0.7 each, never under `min` (a base already under it stays). */
    public static int interval(int base, int oc, int min) {
        if (base <= 0) {
            return 0;
        }
        int floor = Math.min(base, Math.max(1, min));
        return Math.max(floor, (int) Math.round(base * Math.pow(OC_TIME, cap(oc))));
    }

    /** The overclockers that still shorten the interval (the rest change nothing and cost nothing). */
    public static int usefulOc(int base, int oc, int min) {
        int n = cap(oc);
        int best = interval(base, n, min);
        for (int k = 0; k <= n; k++) {
            if (interval(base, k, min) == best) {
                return k;
            }
        }
        return n;
    }

    /** EU a tick: the base x1.6 per useful overclocker x0.8 per economizer x1.75 per overdrive (rounded up, >= 1 if base > 0). */
    public static int upkeep(int base, int usefulOc, int eco, int overdrive) {
        if (base <= 0) {
            return 0;
        }
        double e = base * Math.pow(OC_EU, cap(usefulOc)) * Math.pow(ECO_EU, capGen(eco)) * Math.pow(OD_EU, capGen(overdrive));
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE / 4, Math.ceil(e - 1e-9)));
    }

    /** EU of one shot (a burst, a launch) with economizers. */
    public static int shotEu(int base, int eco) {
        if (base <= 0) {
            return 0;
        }
        return (int) Math.max(1, Math.round(base * Math.pow(ECO_EU, capGen(eco))));
    }

    /** The radar's range multiplier with overdrives. */
    public static double rangeMul(int overdrive) {
        return Math.pow(OD_RANGE, capGen(overdrive));
    }

    /** "6,0" / "0,5" - seconds from ticks with one decimal (the decimal sign passed: ',' ru / '.' en). Pure. */
    public static String seconds(int ticks, char point) {
        long tenths = Math.round(Math.max(0, ticks) / 2.0);
        return (tenths / 10) + String.valueOf(point) + (tenths % 10);
    }

    /** A contact on the radar's scope (PPI): its offset in pixels from the centre, clamped to the rim. Pure. */
    public static int[] scope(double dx, double dz, double range, int radius) {
        if (!(range > 0)) {
            return new int[] {0, 0};
        }
        double px = dx / range * radius, pz = dz / range * radius, d = Math.sqrt(px * px + pz * pz);
        if (d > radius) {
            px *= radius / d;
            pz *= radius / d;
        }
        return new int[] {(int) Math.round(px), (int) Math.round(pz)};
    }
}
