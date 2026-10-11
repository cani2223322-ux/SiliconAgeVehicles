package com.sva.vehicle;

import com.sc.energy.Tier;

import net.minecraft.nbt.NBTTagCompound;

/**
 * The removable board battery of a hydrogen vehicle (plan-next §3, mock-ups docs/mockups/veh_fuel_3_battery.png,
 * veh_fuel_4_display_wear.png). Pure logic (the self-test SelfTestFuelSVA runs it on the dedicated server); the entity
 * keeps the battery in its VehicleState (installed: the charge is the vehicle's EU), the item (ItemBatterySVA) carries it
 * out of the vehicle in its NBT (Battery.write / read).
 * <p>
 * <b>One kind per vehicle type</b> (the item meta = the VehicleType ordinal; another type's battery does not fit), no
 * levels: buggy / hoverbike 40 000 EU MV, crawler / drill vehicle 200 000 HV, helicopter / light plane 120 000 HV, jet /
 * tiltrotor 400 000 EV. It charges like any electric item of its tier (the core's energy storages, IC2 chargers - at most
 * the tier's packet a tick, HV 512 EU/t), in the vehicle at a charging station and from the generator while the engine
 * runs.
 * <p>
 * <b>Health</b> 0..1 (shown in %): every full capacity charged in wears CYCLE_WEAR (0.05 %), a run down to empty
 * DEEP_WEAR (0.5 %, once until it is charged over DEEP_RESET again). Under SERVICE_AT (80 %) the lamp «ОБСЛУЖИТЕ АКБ»;
 * under WEAK_AT (50 %) a start misfires at WEAK_MISFIRE (it catches on the 2nd-3rd try); under DEAD_AT (20 %) it does not
 * hold the charge for a start at all.
 * <p>
 * <b>Service</b>: the repair garage's «Обслужить АКБ» (GARAGE_EU + 1 «Электролит») brings the health up to the limit;
 * the «Сервисный набор АКБ» in the field +KIT_GAIN for each electrolyte, not over the limit. Every service lowers the
 * limit by CAP_DROP (from 100 %; never under MIN_CAP) - after a few it is time for a new battery.
 */
public final class BatteryMath {

    public static final double CYCLE_WEAR = 0.0005, DEEP_WEAR = 0.005, DEEP_RESET = 0.1;
    public static final double SERVICE_AT = 0.8, WEAK_AT = 0.5, DEAD_AT = 0.2, WEAK_MISFIRE = 0.5;
    public static final double CAP_DROP = 0.05, MIN_CAP = 0.2, KIT_GAIN = 0.15;
    /** The garage's service price (EU), the kit's time (ticks, 5 s). */
    public static final int GARAGE_EU = 20000, KIT_TICKS = 100;
    /** The lamp «АКБ» goes amber under LOW (10 %); a start needs START_MIN (5 %) - one start costs FuelMath.START_SHARE. */
    public static final double LOW = 0.1, START_MIN = 0.05;
    /** «Пусковые провода»: up to JUMP_SHARE of the receiver's capacity over JUMP_TICKS (10 s), the donor kept at DONOR_MIN, within JUMP_RANGE blocks. */
    public static final double JUMP_SHARE = 0.25, DONOR_MIN = 0.3, JUMP_RANGE = 6;
    public static final int JUMP_TICKS = 200;
    /** The voltmeter's scale (V) - the mock-up's 20..32. */
    public static final float V_MIN = 20F, V_MAX = 32F;

    /** start() results. */
    public static final int OK = 0, NONE = 1, DEAD = 2, FLAT = 3;
    /** trend() results: the HUD's drawn arrow. */
    public static final int DOWN = -1, FLAT_TREND = 0, UP = 1;

    /** Item / state NBT keys (APPEND-ONLY). */
    public static final String K_CHARGE = "BatCharge", K_HEALTH = "BatHealth", K_CAP = "BatCap", K_SERVICES = "BatServices",
            K_CYCLE = "BatCycle", K_DEEP = "BatDeep";

    private BatteryMath() {
    }

    /** The battery's capacity (EU) for a hydrogen type; 0 for the others. */
    public static double capacity(VehicleType t) {
        if (!FuelMath.uses(t)) {
            return 0;
        }
        switch (t) {
            case BUGGY:
            case HOVER:
                return 40000;
            case TRUCK:
            case DRILL:
                return 200000;
            case HELI:
            case PLANE:
                return 120000;
            default:
                return 400000;                                  // the jet, the tiltrotor
        }
    }

    /** Its voltage tier (the charging packet). */
    public static Tier tier(VehicleType t) {
        double c = capacity(t);
        return c <= 40000 ? Tier.MV : c <= 200000 ? Tier.HV : Tier.EV;
    }

    /** May this battery start the engine? (present, healthy enough, charged enough) */
    public static int start(boolean present, double health, double charge, double cap) {
        if (!present) {
            return NONE;
        }
        if (health < DEAD_AT) {
            return DEAD;
        }
        return cap <= 0 || charge < cap * START_MIN ? FLAT : OK;
    }

    /** The misfire chance of a start for this health (on top of the engine's own). */
    public static double misfire(double health) {
        return health < WEAK_AT ? WEAK_MISFIRE : 0;
    }

    /** «ОБСЛУЖИТЕ АКБ». */
    public static boolean needsService(double health) {
        return health < SERVICE_AT;
    }

    /** Health after `eu` charged in (cycles: one per full capacity). */
    public static double cycleWear(double health, double eu, double cap) {
        if (!(eu > 0) || !(cap > 0)) {
            return health;
        }
        return Math.max(0, health - CYCLE_WEAR * eu / cap);
    }

    /** The garage's service: {health, limit} after it (up to the limit, then the limit drops). */
    public static double[] garageService(double health, double limit) {
        double h = Math.max(health, limit);
        return new double[] {h, Math.max(MIN_CAP, limit - CAP_DROP)};
    }

    /** The field kit's: +KIT_GAIN not over the limit, then the limit drops. A battery already at its limit gains nothing. */
    public static double[] kitService(double health, double limit) {
        double h = Math.max(health, Math.min(limit, health + KIT_GAIN));
        return new double[] {h, Math.max(MIN_CAP, limit - CAP_DROP)};
    }

    /** A service would help (the health under the limit). */
    public static boolean serviceUseful(double health, double limit) {
        return health < limit - 1e-6;
    }

    /** The voltmeter: 23..27 V by the charge, +1.5 charging, -1 under a heavy draw (over 1 % of the battery a minute); 0 without one. */
    public static float volts(boolean present, double frac, double netPerTick, double cap) {
        if (!present) {
            return 0F;
        }
        double v = 23 + 4 * Math.max(0, Math.min(1, frac));
        if (netPerTick > 0.01) {
            v += 1.5;
        } else if (cap > 0 && -netPerTick > cap * 0.01 / 1200) {
            v -= 1;
        }
        return (float) Math.max(frac <= 0 ? 0 : V_MIN, Math.min(V_MAX, v));
    }

    /** The ammeter (A, + charging): the net EU/s over the volts. */
    public static float amps(double netPerTick, float volts) {
        return volts <= 0 ? 0F : (float) (netPerTick * 20 / volts);
    }

    /** The HUD's arrow: up charging, down discharging (a hundredth of an EU/t is nothing). */
    public static int trend(double netPerTick) {
        return netPerTick > 0.01 ? UP : netPerTick < -0.01 ? DOWN : FLAT_TREND;
    }

    /**
     * EU a jump start moves this tick: its rate (jumpPerTick), not more than is left of the share, the donor never under
     * DONOR_MIN of its own battery, the receiver's room.
     */
    public static double jumpStep(double perTick, double left, double donor, double donorCap, double room) {
        double give = Math.max(0, donor - donorCap * DONOR_MIN);
        return Math.max(0, Math.min(Math.min(perTick, left), Math.min(give, room)));
    }

    /** The share of a jump start each tick (the whole JUMP_SHARE over JUMP_TICKS). */
    public static double jumpPerTick(double cap) {
        return cap * JUMP_SHARE / JUMP_TICKS;
    }

    /** A battery's data (the item's NBT; the state keeps the same fields). */
    public static final class Battery {
        public double charge, health = 1, limit = 1, cycle;
        public int services;
        public boolean deep;

        public void write(NBTTagCompound t) {
            t.setDouble(K_CHARGE, charge);
            t.setDouble(K_HEALTH, health);
            t.setDouble(K_CAP, limit);
            t.setInteger(K_SERVICES, services);
            t.setDouble(K_CYCLE, cycle);
            t.setBoolean(K_DEEP, deep);
        }

        /** Reads; a missing key keeps the new battery's value (a plain crafted item: empty, 100 %). */
        public Battery read(NBTTagCompound t, double cap) {
            if (t == null) {
                return this;
            }
            charge = t.hasKey(K_CHARGE) ? clamp(t.getDouble(K_CHARGE), 0, cap) : charge;
            health = t.hasKey(K_HEALTH) ? clamp(t.getDouble(K_HEALTH), 0, 1) : health;
            limit = t.hasKey(K_CAP) ? clamp(t.getDouble(K_CAP), MIN_CAP, 1) : limit;
            services = t.hasKey(K_SERVICES) ? Math.max(0, t.getInteger(K_SERVICES)) : services;
            cycle = t.hasKey(K_CYCLE) ? Math.max(0, t.getDouble(K_CYCLE)) : cycle;
            deep = t.getBoolean(K_DEEP);
            return this;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
