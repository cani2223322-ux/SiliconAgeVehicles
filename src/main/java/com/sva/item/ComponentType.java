package com.sva.item;

import com.sc.energy.Tier;

/**
 * Vehicle components. APPEND-ONLY: the ordinal is the item meta. `key` = texture + lang key
 * (item.siliconagevehicles.component.KEY.name, sva.component.for.KEY).
 */
public enum ComponentType {

    WHEEL("wheel", Tier.MV),
    TRACK("track", Tier.HV),
    MOTOR_I("motorI", Tier.MV),
    MOTOR_II("motorII", Tier.HV),
    TURBO_ENGINE("turboEngine", Tier.EV),
    ANTIGRAV_ENGINE("antigravEngine", Tier.QV),
    SINGULAR_ENGINE("singularEngine", Tier.SV),
    ROTOR("rotor", Tier.HV),
    WING("wing", Tier.HV),
    FLIGHT_COMPUTER("flightComputer", Tier.MV),
    GAS_TANK("gasTank", Tier.HV),
    CABIN("cabin", Tier.MV),
    HULL_I("hull1", Tier.MV),
    HULL_II("hull2", Tier.HV),
    HULL_III("hull3", Tier.EV),
    HULL_IV("hull4", Tier.QV),
    HULL_V("hull5", Tier.SV),
    ENERGY_BLOCK_MV("energyBlockMV", Tier.MV),
    ENERGY_BLOCK_HV("energyBlockHV", Tier.HV),
    ENERGY_BLOCK_EV("energyBlockEV", Tier.EV),
    ENERGY_BLOCK_QV("energyBlockQV", Tier.QV),
    ENERGY_BLOCK_XV("energyBlockXV", Tier.XV),
    ENERGY_BLOCK_SV("energyBlockSV", Tier.SV),
    /** Stage C: the drill vehicle's «Расширитель бура» - a 5x5 bore instead of 3x3 (its module slot). */
    DRILL_WIDENER("drillWidener", Tier.EV);

    public final String key;
    /** Shown in the workbench list and the tooltip. */
    public final Tier tier;

    ComponentType(String key, Tier tier) {
        this.key = key;
        this.tier = tier;
    }

    public int meta() {
        return ordinal();
    }

    public static ComponentType byMeta(int meta) {
        ComponentType[] v = values();
        return v[Math.max(0, Math.min(v.length - 1, meta))];
    }
}
