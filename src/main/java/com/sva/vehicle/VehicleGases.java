package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

import com.sc.util.ArmorGasSC.Gas;

/**
 * A vehicle type's gas tanks and what it spends them on (plan §2 "Газы"): the core's gases (ArmorGasSC.Gas),
 * tank sizes in mB, the lift gas (mB a second to stay up / on boost), the boost speed factor and the coolant.
 * Built once per VehicleType, e.g. {@code VehicleGases.of(Gas.HYDROGEN, 4000, Gas.HELIUM, 2000).lift(Gas.HYDROGEN, 2, 5)}.
 */
public final class VehicleGases {

    /** No tanks (the buggy). */
    public static final VehicleGases NONE = new VehicleGases();

    private final int[] cap = new int[Gas.values().length];
    private final List<Gas> order = new ArrayList<Gas>();
    /** Gas that keeps it up (null: none), mB a second normally and on boost. */
    public Gas liftGas;
    public double liftPerSecond, boostPerSecond;
    /** Top speed x this on boost. */
    public double boostMul = 1;
    /** Gas that cools the motor (helium, VehicleMath.heliumCooling), or null. */
    public Gas coolant;
    /** Hull field (plan §4.4 «Поле корпуса», the drill vehicle): this gas at fieldPerSecond mB/s takes fieldReduction off hull damage. */
    public Gas fieldGas;
    public double fieldPerSecond, fieldReduction;

    private VehicleGases() {
    }

    /** Pairs of (Gas, Integer mB). */
    public static VehicleGases of(Object... gasCap) {
        VehicleGases v = new VehicleGases();
        for (int i = 0; i + 1 < gasCap.length; i += 2) {
            Gas g = (Gas) gasCap[i];
            v.cap[g.ordinal()] = ((Integer) gasCap[i + 1]).intValue();
            v.order.add(g);
        }
        if (v.cap[Gas.HELIUM.ordinal()] > 0) {
            v.coolant = Gas.HELIUM;
        }
        return v;
    }

    public VehicleGases lift(Gas g, double perSecond, double boostPerSecond) {
        liftGas = g;
        liftPerSecond = perSecond;
        this.boostPerSecond = boostPerSecond;
        return this;
    }

    /** A hull field: `g` spent at `perSecond` mB/s while the motor runs, hull damage x (1 - reduction). */
    public VehicleGases field(Gas g, double perSecond, double reduction) {
        fieldGas = g;
        fieldPerSecond = perSecond;
        fieldReduction = reduction;
        return this;
    }

    public VehicleGases boost(double mul) {
        boostMul = mul;
        return this;
    }

    public int capacity(Gas g) {
        return g == null ? 0 : cap[g.ordinal()];
    }

    public boolean any() {
        return !order.isEmpty();
    }

    /** The tanks in the order given (HUD, tooltip, sync). */
    public List<Gas> gases() {
        return order;
    }

    /** Lift gas a tick, mB (boost: the boost rate). */
    public double liftPerTick(boolean boost) {
        return (boost ? boostPerSecond : liftPerSecond) / 20.0;
    }
}
