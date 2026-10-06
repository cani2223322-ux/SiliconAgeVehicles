package com.sva.vehicle;

import com.sc.energy.Tier;
import com.sc.util.ArmorGasSC.Gas;

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
            new double[][] {{-0.50, -0.38}, {-0.50, 0.38}}, 1.45F, 0.80),
    // Hoverbike (plan §2, QV): x3 sprint speed (0.84 b/t), 4M EU, 80 EU/t at speed / 4 hovering, 1 seat, 9 cargo.
    // model scale 1.15: the hover.obj saddle (x -1.0..-0.2, cushion top 0.69) is then 0.79 above the bottom and
    // the handlebar grips (x 0.40, y 0.85) 1.0 block ahead of the rider's hips; the body is 4.1 x 1.9 blocks.
    // Seat: x -0.50 (on the saddle, in front of the backrest), seatY = 0.79 - 0.25.
    // H2: 2 mB/s to hover, 5 on boost (x1.35 speed); helium cools the motor (2 gas tanks in the recipe).
    HOVER("hover", "hover", Tier.QV, 4000000, 80, 4, 0.84, 0.03, 5.0, 1, 9, 300, 2.0F, 1.2F,
            new double[][] {{-0.50, 0}}, 1.15F, 0.54, Movement.HOVER,
            VehicleGases.of(Gas.HYDROGEN, Integer.valueOf(4000), Gas.HELIUM, Integer.valueOf(2000)).lift(Gas.HYDROGEN, 2, 5).boost(1.35)),
    // Light Helicopter (plan §2, HV): 3M EU, 90 EU/t flying forward / 110 hovering (peakCost) / 3 idling on the ground,
    // x2.5 sprint (0.70 b/t), 2 seats, 9 cargo, helium cooling (1000 mB). Model scale 1.5: heli.obj's front seats
    // (cushion x 0.77..1.05, top 0.66, z +-0.25) end up shoulder-wide (0.75 apart) with the cushion top 0.99 over the
    // skids and the cabin roof 1.09 over it; the rotor is 6.8 blocks across, the body 7.5 long. Seats: the two front
    // (pilot) seats - the driver right, the co-pilot left; x = cushion centre 0.91 x 1.5 + 0.05, seatY = 0.99 - 0.25.
    // The rear bench of the model stays decorative (plan: 2 seats).
    HELI("heli", "heli", Tier.HV, 3000000, 90, 3, 0.70, 0.02, 3.0, 2, 9, 250, 3.0F, 1.7F,
            new double[][] {{1.42, 0.375}, {1.42, -0.375}}, 1.5F, 0.74, Movement.HELI,
            VehicleGases.of(Gas.HELIUM, Integer.valueOf(1000))),
    // Light Plane (plan §2, HV): 4M EU, 50 EU/t cruising (40 at Y >= 100) / 120 on the take-off run and climbing
    // (peakCost) / 2 idling, x4 sprint (1.12 b/t), 3 seats (the model's pilot + 2 passengers, staggered), 9 cargo,
    // helium cooling (1000 mB). Model scale 2.3: plane.obj's cockpit is tiny (cushion top 0.64, canopy top 1.07) - at
    // x2.3 the cushion top is 1.47 and the canopy 1.0 over it; wingspan 11.5, length 10.7 blocks. Seats (cushion
    // centres x 0.84 / 0.34 / -0.16, z -0.09 / +0.13 / -0.09) x 2.3 (+0.05 forward), seatY = 1.47 - 0.25.
    PLANE("plane", "plane", Tier.HV, 4000000, 50, 2, 1.12, PlaneMath.THRUST, 1.8, 3, 9, 250, 3.0F, 2.0F,
            new double[][] {{1.98, -0.22}, {0.84, 0.30}, {-0.31, -0.22}}, 2.3F, 1.22, Movement.PLANE,
            VehicleGases.of(Gas.HELIUM, Integer.valueOf(1000)));

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
    /** Physics strategy (ground, hover; later heli, plane). */
    public final Movement movement;
    /** Gas tanks and their use (VehicleGases.NONE: no tanks). */
    public final VehicleGases gases;
    /**
     * Charging platform size class (plan §9): 3x3 hover / buggy, 3x5 truck / drill, 5x5 heli / plane / tilt, 7x7 shuttle /
     * ship. Not final on purpose: set per type in the static block below (append-safe), and the world test fakes a bigger
     * class for a moment.
     */
    public PlatformSize platformSize = PlatformSize.P3x3;
    /**
     * EU/t in the hardest mode (aircraft): the helicopter hovering, the plane on its take-off run / climbing at full
     * throttle. 0 for the others. Set in the static block below (append-safe, like platformSize).
     */
    public double peakCost;

    static {
        BUGGY.platformSize = PlatformSize.P3x3;
        HOVER.platformSize = PlatformSize.P3x3;
        HELI.platformSize = PlatformSize.P5x5;
        PLANE.platformSize = PlatformSize.P5x5;
        HELI.peakCost = 110;
        PLANE.peakCost = 120;
    }

    VehicleType(String key, String model, Tier tier, int buffer, double cruiseCost, double idleCost, double topSpeed, double accel,
                double turnRate, int seats, int cargoSlots, float maxHp, float width, float height, double[][] seatOffsets,
                float modelScale, double seatY) {
        this(key, model, tier, buffer, cruiseCost, idleCost, topSpeed, accel, turnRate, seats, cargoSlots, maxHp, width, height,
                seatOffsets, modelScale, seatY, Movement.GROUND, VehicleGases.NONE);
    }

    VehicleType(String key, String model, Tier tier, int buffer, double cruiseCost, double idleCost, double topSpeed, double accel,
                double turnRate, int seats, int cargoSlots, float maxHp, float width, float height, double[][] seatOffsets,
                float modelScale, double seatY, Movement movement, VehicleGases gases) {
        this.movement = movement;
        this.gases = gases;
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
