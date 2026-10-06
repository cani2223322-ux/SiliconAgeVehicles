package com.sva.vehicle;

import com.sc.energy.Tier;

/**
 * Every vehicle kind. APPEND-ONLY: the ordinal is the item meta and is saved in NBT.
 * Speeds in blocks/tick (sprint = 0.28 b/t), energy in EU.
 */
public enum VehicleType {

    // name, model, tier, buffer, EU/t at top speed, idle EU/t, top speed, accel, turn deg/t, seats, cargo, hull HP, width, height
    // model scale 1.45: the concept was drawn too small for a 1.8-block player - at x1.45 the seats are
    // shoulder-wide and the roll cage clears a sitting player's head (seat cushion top 1.05 above the ground)
    BUGGY("buggy", "buggy", Tier.MV, 400000, 20, 1, 0.5, 0.02, 4.5, 2, 9, 200, 3.2F, 2.0F,
            // seat offsets in blocks (x forward, z right), already scaled: driver first
            new double[][] {{-0.50, -0.38}, {-0.50, 0.38}}, 1.45F, 0.80);

    public final String key;
    public final String model;
    public final Tier tier;
    public final int buffer;
    /** EU/t at top speed with empty cargo (idle included). */
    public final double cruiseCost;
    public final double idleCost;
    public final double topSpeed;
    public final double accel;
    public final double turnRate;
    public final int seats;
    public final int cargoSlots;
    public final float maxHp;
    /** Collision box (square in 1.7.10). Kept under seated eye height so riders can still aim past the body. */
    public final float width, height;
    public final double[][] seatOffsets;
    /** Seat height above the vehicle origin (rider's mounted offset): cushion top - 0.25 (the sitting player's hip). */
    public final double seatY;
    /** The OBJ model is drawn at this scale (blocks). */
    public final float modelScale;
    /** Rough body half-length, for getting out behind / in front. */
    public final double halfLength;
    public final float stepHeight = 1.0F;
    // future: gases, flying flags, fuel tanks - appended as fields here

    VehicleType(String key, String model, Tier tier, int buffer, double cruiseCost, double idleCost, double topSpeed, double accel,
                double turnRate, int seats, int cargoSlots, float maxHp, float width, float height, double[][] seatOffsets,
                float modelScale, double seatY) {
        this.key = key;
        this.model = model;
        this.tier = tier;
        this.buffer = buffer;
        this.cruiseCost = cruiseCost;
        this.idleCost = idleCost;
        this.topSpeed = topSpeed;
        this.accel = accel;
        this.turnRate = turnRate;
        this.seats = seats;
        this.cargoSlots = cargoSlots;
        this.maxHp = maxHp;
        this.width = width;
        this.height = height;
        this.seatOffsets = seatOffsets;
        this.modelScale = modelScale;
        this.seatY = seatY;
        this.halfLength = 1.9 * modelScale;
    }

    /** k in cost = idle + k * v^2 * (1 + cargo): top speed with empty cargo costs cruiseCost. */
    public double speedCoef() {
        return (cruiseCost - idleCost) / (topSpeed * topSpeed);
    }

    public static VehicleType byOrdinal(int i) {
        VehicleType[] v = values();
        return v[Math.max(0, Math.min(v.length - 1, i))];
    }
}
