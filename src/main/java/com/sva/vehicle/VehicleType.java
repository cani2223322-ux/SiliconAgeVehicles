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
            VehicleGases.of(Gas.HELIUM, Integer.valueOf(1000))),
    // Cargo Crawler (plan §2, HV): 2M EU, 60 EU/t at speed / 2 idle, x1.4 sprint (0.392 b/t), 2 seats, 54 cargo + the
    // trailer's 54 (trailerSlots), helium under load (one gas tank: 2000 mB), tracks (Movement.CRAWLER: turns on the
    // spot, 2-block steps, swims). truck.obj holds the crawler AND its trailer (x < -1.25, ObjModelSVA part "trailer",
    // drawn only while one is hitched). Model scale 1.25: the bench cushion (x 2.04..2.30, top 0.84, z +-0.49) is then
    // 1.05 over the ground and the cab's headliner (1.70) 1.07 over it; the crawler alone (x -1.2..3.31) is centred by
    // modelOffsetX 1.055, so seats x = (2.17 - 1.055) x 1.25 = 1.39, z +-0.375 (driver left, by the wheel), seatY 0.80.
    TRUCK("truck", "truck", Tier.HV, 2000000, 60, 2, 0.392, 0.012, 3.5, 2, 54, 400, 2.8F, 1.9F,
            new double[][] {{1.39, -0.375}, {1.39, 0.375}}, 1.25F, 0.80, Movement.CRAWLER,
            VehicleGases.of(Gas.HELIUM, Integer.valueOf(2000))),
    // Drill Vehicle (plan §2, EV): 8M EU, 120 EU/t at speed / 4 idle + EU a block dug (DrillMath), x0.6 sprint
    // (0.168 b/t), 1 seat, 108 cargo, He + Ar (two gas tanks, 2000 mB each; argon feeds the hull field: 1 mB/s, -40 %
    // hull damage), tracks. Model scale 1.1 - NOT "fits the cab": in its own 3x3 bore the operator's eyes must stay
    // under the tunnel's ceiling (else he suffocates in the rock): cushion top 1.72 x 1.1 = 1.89, seatY 1.64, eyes
    // 1.64 + 1.12 = 2.76 < 3 (2.86 with the 0.1 margin of the in-wall check). The cab roof (2.27 x 1.1 = 2.50) is
    // lower than his head - he looks out over it like out of a hatch. Body 6.4 x 2.6 x 2.85 blocks, the bore cone
    // 2.0 across; seat at the cushion centre (0.36, -0.31) x 1.1. Collision box 2.4 x 2.7 - it fits the 3x3 tunnel.
    DRILL("drill", "drill", Tier.EV, 8000000, 120, 4, 0.168, 0.008, 2.5, 1, 108, 600, 2.4F, 2.7F,
            new double[][] {{0.40, -0.34}}, 1.1F, 1.64, Movement.CRAWLER,
            VehicleGases.of(Gas.HELIUM, Integer.valueOf(2000), Gas.ARGON, Integer.valueOf(2000)).field(Gas.ARGON, 1, 0.4)),
    // Jet (plan §2, QV): 16M EU, 90 EU/t cruising (72 at Y >= 100) / 250 on the take-off run and climbing (peakCost) / 3
    // idle, x6 sprint (1.68 b/t), the AFTERBURNER on «Форсаж» at full throttle: x1.5 speed and thrust, H2 4 mB/s, x2 heat
    // (FlightMath.SPEED_CAP 2.5 b/t caps it), 9 cargo, H2 4000 + He 2000 (two gas tanks in the recipe). Stall 0.9 b/t,
    // take-off run ~50 blocks / ~25 on a runway (PlaneParams.JET). Model scale 2.5: jet.obj's ejection seat (cushion x
    // 1.02..1.30, top 0.64, z +-0.11) sits under a canopy only 1.04 high - at x2.5 the cushion top is 1.60 and the canopy
    // 0.99 over it (the light plane's 1.0); the jet is then 14.9 long, 10.8 across the wings, 3.9 to the fin tip.
    // ONE seat (the plan says 2): behind the pilot the fuselage holds the H2 / He tanks and is lower than the cushion -
    // a second rider would sit outside on the spine. Seat x = 1.16 x 2.5 + 0.05, seatY = 1.60 - 0.25.
    JET("jet", "jet", Tier.QV, 16000000, 90, 3, 1.68, PlaneParams.JET.thrust, 2.5, 1, 9, 350, 3.0F, 2.0F,
            new double[][] {{2.95, 0}}, 2.5F, 1.35, Movement.PLANE,
            VehicleGases.of(Gas.HYDROGEN, Integer.valueOf(4000), Gas.HELIUM, Integer.valueOf(2000)).afterburner(Gas.HYDROGEN, 4, 1.5)),
    // Tiltrotor (plan §2, XV): 32M EU, two modes (Movement.TILT, key «Режим» N): VERTICAL - a helicopter (vtolTop 0.6 b/t,
    // hovering 150 EU/t / 120 forward) and AIRPLANE - x5 sprint (1.4 b/t), 110 EU/t cruising / 160 climbing; a 2 s
    // transition (TiltMath) with the nacelles turning. «Форсаж» in airplane mode: x1.25 on H2 3 mB/s. 27 cargo, H2 3000 +
    // He 2000. Model scale 2.2: tilt.obj's cockpit (two pilot cushions x 1.50..1.78, top 0.53, z +-0.03..0.24) under a
    // cabin ceiling of 1.03 - at x2.2 the cushion top is 1.17 and the ceiling 1.10 over it; 10.0 long, 15.8 across the
    // rotor tips, 4.3 high. SIX seats (the plan says 4; the model has 2 pilot + 8 fold-down troop seats): the two pilots
    // (driver right) and the front four troop seats (back to back along the centre line, rows at x 0.41 / -0.03);
    // the rear four stay decorative. Seats x2.2 (+0.05 forward for the pilots), seatY = 1.17 - 0.25.
    TILT("tilt", "tilt", Tier.XV, 32000000, 110, 3, 1.4, PlaneParams.TILT.thrust, 2.0, 6, 27, 400, 3.0F, 2.0F,
            new double[][] {{3.66, 0.30}, {3.66, -0.30}, {0.89, 0.38}, {0.89, -0.38}, {-0.06, 0.38}, {-0.06, -0.38}}, 2.2F, 0.92,
            Movement.TILT,
            VehicleGases.of(Gas.HYDROGEN, Integer.valueOf(3000), Gas.HELIUM, Integer.valueOf(2000)).afterburner(Gas.HYDROGEN, 3, 1.25));

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
    /** Rough body half-length, for getting out behind / in front (set per type in the static block where it differs). */
    public double halfLength;
    /** Blocks it steps up (tracks: 2). */
    public float stepHeight = 1.0F;
    /** The model is drawn moved back by this (model units): truck.obj's crawler sits forward of its trailer. */
    public float modelOffsetX;
    /** Extra cargo slots a hitched trailer adds (the truck: 54), 0: no trailer. */
    public int trailerSlots;
    /** Has the bore (drill head + module slots, digging). */
    public boolean hasDrill;
    /** Takes a Coordinator route and drives it by itself (the crawler's auto-delivery). */
    public boolean autoDelivery;
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
    /** Aeroplane numbers (Movement.PLANE, the tiltrotor's airplane mode): stall, lift-off, thrust, climb... */
    public PlaneParams plane = PlaneParams.LIGHT;
    /** Tiltrotor, vertical mode (a helicopter): top speed, acceleration, EU/t at that speed / hovering. */
    public double vtolTop, vtolAccel, vtolCruise, vtolPeak;

    static {
        BUGGY.platformSize = PlatformSize.P3x3;
        HOVER.platformSize = PlatformSize.P3x3;
        HELI.platformSize = PlatformSize.P5x5;
        PLANE.platformSize = PlatformSize.P5x5;
        HELI.peakCost = 110;
        PLANE.peakCost = 120;
        TRUCK.platformSize = PlatformSize.P3x5;
        DRILL.platformSize = PlatformSize.P3x5;
        TRUCK.stepHeight = CrawlerMath.STEP;
        DRILL.stepHeight = CrawlerMath.STEP;
        TRUCK.halfLength = 2.8;                     // crawler (x -1.2..3.31) x 1.25 / 2
        DRILL.halfLength = 3.2;                     // x -2.81..3.0 x 1.1 / 2
        TRUCK.modelOffsetX = 1.055F;
        TRUCK.trailerSlots = 54;
        DRILL.hasDrill = true;
        TRUCK.autoDelivery = true;
        // stage D
        JET.platformSize = PlatformSize.P7x7;               // 14.9 blocks long
        TILT.platformSize = PlatformSize.P5x5;
        JET.peakCost = 250;
        TILT.peakCost = 160;
        JET.plane = PlaneParams.JET;
        TILT.plane = PlaneParams.TILT;
        TILT.vtolTop = 0.6;
        TILT.vtolAccel = 0.02;
        TILT.vtolCruise = 120;
        TILT.vtolPeak = 150;
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

    /** Cargo slots with a trailer hitched (or not). */
    public int cargoSlots(boolean trailer) {
        return cargoSlots + (trailer ? trailerSlots : 0);
    }

    public static VehicleType byOrdinal(int i) {
        VehicleType[] v = values();
        return v[Math.max(0, Math.min(v.length - 1, i))];
    }
}
