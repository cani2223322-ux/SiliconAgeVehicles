package com.sva.vehicle;

/** Pure formulas (no world access) - shared by the entity, the HUD and the self-test. */
public final class VehicleMath {

    /** Empty buffer: top speed x this (crawl, never dead stop). */
    public static final double CRAWL = 0.1;
    /** Heat: % gained a tick at load 1, base cooling a tick, extra cooling per % of heat. */
    public static final double HEAT_GAIN = 0.035, COOL_BASE = 0.01, COOL_K = 0.0003;
    public static final double HEAT_STOP = 100, HEAT_RESTART = 50;
    /** Wall hits: speed (b/t) above which a hit hurts, HP per b/t over it. */
    public static final double HIT_SAFE = 0.2, HIT_DAMAGE = 80;
    /** Repair with one steel ingot (temporary until the Garage): fraction of max HP. */
    public static final double REPAIR_STEP = 0.1;

    private VehicleMath() {
    }

    /** EU a tick: idle + k * v^2 * (1 + cargoFill), x mul. Motor off (no driver): 0. */
    public static double cost(VehicleType t, double speed, double cargoFill, boolean motorOn, double mul) {
        if (!motorOn) {
            return 0;
        }
        double fill = clamp(cargoFill, 0, 1);
        return (t.idleCost + t.speedCoef() * speed * speed * (1 + fill)) * Math.max(0, mul);
    }

    /** Engine load 0..2: the speed part of the cost relative to top speed with empty cargo. */
    public static double load(VehicleType t, double speed, double cargoFill) {
        double v = Math.abs(speed) / t.topSpeed;
        return v * v * (1 + clamp(cargoFill, 0, 1));
    }

    /** Blocks one can still drive at top speed (no wall hits, current cargo). Crawl range is endless. */
    public static double range(VehicleType t, double energy, double cargoFill, double mul) {
        double c = cost(t, t.topSpeed, cargoFill, true, mul);
        if (c <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0, energy) / c * t.topSpeed;
    }

    /** Top speed now: crawl when the buffer is empty. */
    public static double maxSpeed(VehicleType t, double energy) {
        return energy > 0 ? t.topSpeed : t.topSpeed * CRAWL;
    }

    /** One tick of heat. coolingBonus: extra %/tick (helium later). */
    public static double heatStep(double heat, double load, double coolingBonus) {
        double h = heat + HEAT_GAIN * load - (COOL_BASE + COOL_K * heat + Math.max(0, coolingBonus));
        return clamp(h, 0, HEAT_STOP);
    }

    /**
     * Helium cooling (the core suit's loop, scaled to the vehicle's 0-100 % heat): above HE_START the pump adds up to
     * HE_PUMP %/tick of cooling (full at HE_START + 2.5 %), each mB of helium takes HE_HEAT_PER_MB %-points away.
     */
    public static final double HE_START = 40, HE_PUMP = 0.05, HE_GAIN = 0.02, HE_HEAT_PER_MB = 2.0;

    /** Extra cooling %/tick from helium (0 below HE_START or with an empty tank; never more than the tank holds). */
    public static double heliumCooling(double heat, double heliumMb) {
        if (heliumMb <= 0 || heat <= HE_START) {
            return 0;
        }
        return Math.min(Math.min(HE_PUMP, (heat - HE_START) * HE_GAIN), heliumMb * HE_HEAT_PER_MB);
    }

    /** Helium (mB) that cooling takes. */
    public static double heliumUse(double cooling) {
        return Math.max(0, cooling) / HE_HEAT_PER_MB;
    }

    /** Overheat latch: stops at 100 %, restarts once at or below 50 %. */
    public static boolean heatStopped(boolean wasStopped, double heat) {
        if (heat >= HEAT_STOP) {
            return true;
        }
        return wasStopped && heat > HEAT_RESTART;
    }

    /** HP after damage (never below 0). */
    public static float damage(float hp, float amount) {
        if (amount <= 0 || Float.isNaN(amount)) {
            return hp;
        }
        return Math.max(0, hp - amount);
    }

    public static boolean wrecked(float hp) {
        return hp <= 0;
    }

    /** HP after one repair step (capped at max). */
    public static float repair(float hp, float max) {
        return (float) Math.min(max, hp + max * REPAIR_STEP);
    }

    /** HP a wall hit at `speed` takes. */
    public static float hitDamage(double speed) {
        return speed > HIT_SAFE ? (float) ((speed - HIT_SAFE) * HIT_DAMAGE) : 0;
    }

    /** May one more vehicle be placed (max 0 = unlimited)? */
    public static boolean limitAllows(int placed, int max) {
        return max <= 0 || placed < max;
    }

    /** EU a charger moves this tick. */
    public static int chargeTransfer(int stored, int rate, double room) {
        return (int) Math.max(0, Math.min(Math.min(stored, rate), Math.floor(room)));
    }

    /** blocks/tick -> "km/h" on the HUD (blocks/s x 3.6). */
    public static int kmh(double blocksPerTick) {
        return (int) Math.round(Math.abs(blocksPerTick) * 20 * 3.6);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
