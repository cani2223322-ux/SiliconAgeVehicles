package com.sva.vehicle;

import com.sc.energy.Tier;
import com.sc.util.ArmorGasSC.Gas;

/**
 * Every vehicle kind. APPEND-ONLY: the ordinal is the item meta and is saved in NBT.
 * Speeds in blocks/tick (sprint = 0.28 b/t), energy in EU.
 */
public enum VehicleType {

    // RIDER FIT (fix pass 2026-10, the user's screenshots): the 1.7.10 player model sits with the hip (leg pivot) at
    // seatY + 0.203, the head top at seatY + 1.375 (x0.9375 model: torso 0.703, head 0.469). Every closed type was
    // checked against its OBJ hull (shell + glass, debug/RiderFitSVA, the self-test "rider fit"): the rider's chest and
    // head must stay inside. The older numbers assumed ~1.0 block of headroom over the cushion and let the heads of the
    // helicopter, plane, jet, tiltrotor / shuttle pilots and the ship's front rows stick out. Fixed by scaling (heli
    // 1.5 -> 1.7, plane 2.3 -> 2.9, jet 2.5 -> 2.8), moving seats back / inward and an optional per-seat height change
    // (third number of a seatOffsets row, VehicleType.seatDy). Numbers per type below.

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
    // Rider fit: at x1.5 the heads went through the roof where the nose curves down (0.23 deep) - now x1.7 (rotor 7.7
    // blocks, body 8.5 long), seats 0.2 back from the scaled cushion centre and 0.1 inward (x 1.41, z +-0.32), seatY
    // 0.704 (hip 0.08 into the 0.99-high seat surface); eyes 1.82, the roof 0.34 over them.
    HELI("heli", "heli", Tier.HV, 3000000, 90, 3, 0.70, 0.02, 3.0, 2, 9, 250, 3.0F, 1.7F,
            new double[][] {{1.41, 0.32}, {1.41, -0.32}}, 1.7F, 0.704, Movement.HELI,
            VehicleGases.of(Gas.HELIUM, Integer.valueOf(1000))),
    // Light Plane (plan §2, HV): 4M EU, 50 EU/t cruising (40 at Y >= 100) / 120 on the take-off run and climbing
    // (peakCost) / 2 idling, x4 sprint (1.12 b/t), 3 seats (the model's pilot + 2 passengers, staggered), 9 cargo,
    // helium cooling (1000 mB). Model scale 2.3: plane.obj's cockpit is tiny (cushion top 0.64, canopy top 1.07) - at
    // x2.3 the cushion top is 1.47 and the canopy 1.0 over it; wingspan 11.5, length 10.7 blocks. Seats (cushion
    // centres x 0.84 / 0.34 / -0.16, z -0.09 / +0.13 / -0.09) x 2.3 (+0.05 forward), seatY = 1.47 - 0.25.
    // Rider fit: at x2.3 the fuselage tube is only ~1.12 blocks high inside - the heads stood out over it. Now x2.9
    // (13.5 long, 14.5 across the wings): pilot x 2.50, passengers x 1.06 / -0.39 (z +-0.28), seatY 1.26 (hips 0.12-0.14
    // into the seat surface at 1.60); eyes 2.38, the roof 0.34-0.36 over them.
    // Cockpit view (2026-10): the pilot looked at the instrument cluster, under the fuselage top - his seat now stands on a
    // pedestal 0.48 higher (seatDy; cushion top 2.08), the canopy was raised to 3.33 (glass top 1.15) and the dash lowered
    // to 0.84 (2.42): eyes 2.86, over the dash and the nose (look-down over the nose ~7 deg), head top 0.18 under the glass.
    PLANE("plane", "plane", Tier.HV, 4000000, 50, 2, 1.12, PlaneMath.THRUST, 1.8, 3, 9, 250, 3.0F, 2.0F,
            new double[][] {{2.50, -0.28, 0.48}, {1.06, 0.28}, {-0.39, -0.28}}, 2.9F, 1.26, Movement.PLANE,
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
            new double[][] {{0.40, -0.34}}, 1.1F, 0.90, Movement.CRAWLER,
            VehicleGases.of(Gas.HELIUM, Integer.valueOf(2000), Gas.ARGON, Integer.valueOf(2000)).field(Gas.ARGON, 1, 0.4)),
    // Jet (plan §2, QV): 16M EU, 90 EU/t cruising (72 at Y >= 100) / 250 on the take-off run and climbing (peakCost) / 3
    // idle, x6 sprint (1.68 b/t), the AFTERBURNER on «Форсаж» at full throttle: x1.5 speed and thrust, H2 4 mB/s, x2 heat
    // (FlightMath.SPEED_CAP 2.5 b/t caps it), 9 cargo, H2 4000 + He 2000 (two gas tanks in the recipe). Stall 0.9 b/t,
    // take-off run ~50 blocks / ~25 on a runway (PlaneParams.JET). Model scale 2.5: jet.obj's ejection seat (cushion x
    // 1.02..1.30, top 0.64, z +-0.11) sits under a canopy only 1.04 high - at x2.5 the cushion top is 1.60 and the canopy
    // 0.99 over it (the light plane's 1.0); the jet is then 14.9 long, 10.8 across the wings, 3.9 to the fin tip.
    // ONE seat (the plan says 2): behind the pilot the fuselage holds the H2 / He tanks and is lower than the cushion -
    // a second rider would sit outside on the spine. Seat x = 1.16 x 2.5 + 0.05, seatY = 1.60 - 0.25.
    // Rider fit: under the x2.5 canopy the head stuck 0.18 out - now x2.8 (16.8 long, 12.1 across the wings), seat x 3.40
    // (0.1 forward, under the canopy's top), seatY 1.45 (hip 0.14 into the 1.79 seat surface); eyes 2.57.
    JET("jet", "jet", Tier.QV, 16000000, 90, 3, 1.68, PlaneParams.JET.thrust, 2.5, 1, 9, 350, 3.0F, 2.0F,
            new double[][] {{3.40, 0}}, 2.8F, 1.45, Movement.PLANE,
            VehicleGases.of(Gas.HYDROGEN, Integer.valueOf(4000), Gas.HELIUM, Integer.valueOf(2000)).afterburner(Gas.HYDROGEN, 4, 1.5)),
    // Tiltrotor (plan §2, XV): 32M EU, two modes (Movement.TILT, key «Режим» N): VERTICAL - a helicopter (vtolTop 0.6 b/t,
    // hovering 150 EU/t / 120 forward) and AIRPLANE - x5 sprint (1.4 b/t), 110 EU/t cruising / 160 climbing; a 2 s
    // transition (TiltMath) with the nacelles turning. «Форсаж» in airplane mode: x1.25 on H2 3 mB/s. 27 cargo, H2 3000 +
    // He 2000. Model scale 2.2: tilt.obj's cockpit (two pilot cushions x 1.50..1.78, top 0.53, z +-0.03..0.24) under a
    // cabin ceiling of 1.03 - at x2.2 the cushion top is 1.17 and the ceiling 1.10 over it; 10.0 long, 15.8 across the
    // rotor tips, 4.3 high. SIX seats (the plan says 4; the model has 2 pilot + 8 fold-down troop seats): the two pilots
    // (driver right) and the front four troop seats (back to back along the centre line, rows at x 0.41 / -0.03);
    // the rear four stay decorative. Seats x2.2 (+0.05 forward for the pilots), seatY = 1.17 - 0.25.
    // Rider fit: the pilots' heads went through the nose roof - pilots now x 3.46, z +-0.25 and 0.22 lower (seatDy), the
    // rear troop row 0.05 lower; the front troop row as before. Eyes: pilots 1.82, troops 2.04 / 1.99.
    TILT("tilt", "tilt", Tier.XV, 32000000, 110, 3, 1.4, PlaneParams.TILT.thrust, 2.0, 6, 27, 400, 3.0F, 2.0F,
            new double[][] {{3.46, 0.25, -0.22}, {3.46, -0.25, -0.22}, {0.89, 0.38}, {0.89, -0.38}, {-0.06, 0.38, -0.05}, {-0.06, -0.38, -0.05}}, 2.2F, 0.92,
            Movement.TILT,
            VehicleGases.of(Gas.HYDROGEN, Integer.valueOf(3000), Gas.HELIUM, Integer.valueOf(2000)).afterburner(Gas.HYDROGEN, 3, 1.25)),
    // Transport Shuttle (plan §2, XV): 32M EU, 200 EU/t in the air (hovering and flying alike - antigravity) / 8 idle, x4
    // sprint (1.12 b/t), free 3D flight like a heavy VTOL (Movement.HELI with its own climb 0.35 / descent 0.4 b/t), the
    // «Форсаж» on H2 3 mB/s x1.5 (1.68 b/t). Sealed cabin: O2 for the riders (SpaceMath.O2_PER_SECOND each when they need
    // air), flies under water; «Дальний режим» on deuterium (SpaceMath); docks with the core's bridge vortices. 27 cargo,
    // H2 3000 + He 3000 + O2 2000 + D 4000 (three gas tanks in the recipe). Model scale 1.4: shuttle.obj's seat cushions
    // (top 0.85, floor 0.71) under the cabin ceiling (its light strip at 1.71) - at x1.4 the cushion top is 1.19 and the
    // ceiling 1.20 over it; the body is 8.2 long, 5.9 across the solar wings, 2.9 high. EIGHT seats (the plan says 4; the
    // model has 2 pilot + 6 passenger seats - a transport for a group): the pilots (cushion centre x 1.88, z +-0.22; driver
    // right) and three rows of two (x 0.26 / -0.30 / -0.86, z +-0.31), all x1.4 (+0.05 forward for the pilots), seatY 0.94.
    // Rider fit: the pilots' heads went 0.13 into the cockpit roof - pilots now x 2.48, z +-0.26, 0.13 lower (seatDy;
    // further back the eye would sit in the headrest); the passenger rows fit as they were. Eyes: pilots 1.93, others 2.06.
    SHUTTLE("shuttle", "shuttle", Tier.XV, 32000000, 200, 8, 1.12, 0.03, 3.0, 8, 27, 500, 3.0F, 2.0F,
            new double[][] {{2.48, 0.26, -0.13}, {2.48, -0.26, -0.13}, {0.36, 0.43}, {0.36, -0.43}, {-0.42, 0.43}, {-0.42, -0.43}, {-1.20, 0.43},
                    {-1.20, -0.43}}, 1.4F, 0.94, Movement.HELI,
            VehicleGases.of(Gas.HYDROGEN, Integer.valueOf(3000), Gas.HELIUM, Integer.valueOf(3000), Gas.OXYGEN, Integer.valueOf(2000),
                    Gas.DEUTERIUM, Integer.valueOf(4000)).afterburner(Gas.HYDROGEN, 3, 1.5)),
    // Singular Ship (plan §2, SV): 256M EU, 400 EU/t in the air / 20 idle, x6 sprint (1.68 b/t; «Форсаж» H2 4 mB/s x1.4 =
    // 2.35 b/t, under FlightMath.SPEED_CAP), climb 0.45 / descent 0.5 b/t, 6 seats, 54 cargo; the shuttle's cabin, long-range
    // mode and docking plus its own portal «Прыжок» (SpaceMath), the gravity anchor and invisibility for mobs (SM). Tanks:
    // H2 4000, He 4000, O2 4000, Ar 2000 (hull field 1 mB/s, -40 % hull damage, as the drill vehicle), D 4000, singular
    // matter 4000 - every gas it spends (krypton / heavy water have no use on it). Model scale 2.0: ship.obj's bridge is
    // low - cushions top 0.57 (floor 0.43) under a ceiling of 1.10; at x2 the cushion top is 1.14 and the ceiling 1.06 over
    // it; the ship is 14.5 long, 14.1 across its rings, 6.2 high (the jet-sized P7x7 platform class). Seats: the two pilots (cushion centre x 2.33, z +-0.22)
    // and the four of the two rows behind (x 1.49 / 1.05, z +-0.40), x2 (+0.05 forward for the pilots), seatY 0.89.
    // Rider fit: the old ship.obj bridge was too low (the riders had to sit behind the chairs). Bridge v2 (2026-10, mock-up
    // docs/mockups/veh_ship_bridge_v2.png): the upper deck rises to 1.44 (was 1.22) and a bubble canopy covers the pilots
    // (top 1.34, was 0.875), inner ceiling ~1.23 over the pilots / ~1.33 over the rows (was 1.10); same size, scale x2.0.
    // All six riders sit ON their chairs again - the measured cushion centres x 2.374 / 1.499 / 1.061, z +-0.219 / +-0.406
    // (model) x2; cushion top 0.575 x 2 = 1.15, seatY 0.90; head tops 2.275, 0.18 under the canopy / 0.39 under the roof.
    SHIP("ship", "ship", Tier.SV, 256000000, 400, 20, 1.68, 0.04, 2.5, 6, 54, 800, 3.0F, 2.0F,
            new double[][] {{4.75, 0.44}, {4.75, -0.44}, {3.00, 0.81}, {3.00, -0.81}, {2.12, 0.81}, {2.12, -0.81}}, 2.0F, 0.90, Movement.HELI,
            VehicleGases.of(Gas.HYDROGEN, Integer.valueOf(4000), Gas.HELIUM, Integer.valueOf(4000), Gas.OXYGEN, Integer.valueOf(4000),
                    Gas.ARGON, Integer.valueOf(2000), Gas.DEUTERIUM, Integer.valueOf(4000), Gas.SINGULAR_MATTER, Integer.valueOf(4000))
                    .afterburner(Gas.HYDROGEN, 4, 1.4).field(Gas.ARGON, 1, 0.4));

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
    /** Movement.HELI: collective climb / descent rates (blocks/tick) - the helicopters' HeliMath ones unless set. */
    public double climb = HeliMath.CLIMB, descend = HeliMath.DESCEND;
    /** Stage E: a sealed cabin - O2 for the riders, the engine works under water. */
    public boolean sealed;
    /** Stage E: «Дальний режим» on deuterium instead of EU (SpaceMath). */
    public boolean longRange;
    /** Stage E: docks with an open bridge vortex - carried through with its riders and cargo. */
    public boolean bridgeDock;
    /** Stage E: the Singular Ship's own portal «Прыжок», the gravity anchor and invisibility for mobs. */
    public boolean singular;
    /**
     * The config fallback vehicles.seeThroughHull (default off): the local rider's first-person view draws the body shell
     * (ObjModelSVA.isShell) see-through with this alpha instead of cutting the cockpit windows (cabWindows).
     */
    public float cabShellAlpha = CAB_SHELL_ALPHA;
    /**
     * COCKPIT WINDOWS (2026-10, the user: «вид должен быть только через лобовое стекло, как в реальных самолётах»): for
     * the local rider in first person the body shell is drawn OPAQUE except inside these boxes {x0, x1, y0, y1, z0, z1}
     * (model units, x forward, y up, z right; OPEN = an endless side) - the windscreen / canopy / side window openings,
     * cut with GL clip planes (ObjModelSVA.renderShellClipped); the glass over them stays lightly tinted. Every box is
     * derived from the type's glass pieces in its OBJ (the windscreen box from the glass ahead of the eye, its floor at
     * the glass bottom or over the dash, reaching forward through the shell in front of an inset windscreen; side boxes
     * over the side glass, open sideways so both walls get the opening). null: an open seat (buggy, hoverbike) - the
     * shell is simply drawn opaque. Numbers and the measured view per type: the static block below.
     */
    public float[][] cabWindows;
    /** An endless side of a cabWindows box. */
    public static final float OPEN = Float.MAX_VALUE;
    /**
     * An open seat (the buggy's roll cage, the hoverbike's saddle): the rider's head is outside the body by design, so
     * the self-test's «rider inside the hull» check (RiderFitSVA) skips it.
     */
    public boolean openCab;
    /** See-through alpha of the shell from inside with vehicles.seeThroughHull (see cabShellAlpha). */
    public static final float CAB_SHELL_ALPHA = 0.18F;

    static {
        BUGGY.platformSize = PlatformSize.P3x3;
        BUGGY.openCab = HOVER.openCab = true;
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
        // stage E
        SHUTTLE.platformSize = PlatformSize.P7x7;
        SHIP.platformSize = PlatformSize.P7x7;
        SHUTTLE.peakCost = 200;                             // antigravity: hovering costs what flying does
        SHIP.peakCost = 400;
        SHUTTLE.climb = 0.35;
        SHUTTLE.descend = 0.4;
        SHIP.climb = 0.45;
        SHIP.descend = 0.5;
        SHUTTLE.halfLength = 4.1;                           // x -2.92..2.92 x 1.4
        SHIP.halfLength = 7.3;                              // x -3.63..3.63 x 2.0
        SHUTTLE.sealed = SHIP.sealed = true;
        SHUTTLE.longRange = SHIP.longRange = true;
        SHUTTLE.bridgeDock = SHIP.bridgeDock = true;
        SHIP.singular = true;
        // cockpit windows (cabWindows; model units). Eyes = the driver seat's eye (seatY + seatDy + 1.12) in model units;
        // "visible" = the share of the forward view cone (yaw +-60, pitch +-35) through the glass that reaches the world
        // (measured by ray-casting the OBJ with the cut shell; the rest is the dash, pillars, the nose, rotors...).
        // heli: eye (0.83, 1.07, 0.19); the nose bubble (glass x 0.93..1.75, y 0.35..1.30) and the cabin door windows
        // (x -0.01..0.68, y 0.67..1.17) - both open sideways: the shell around the bubble and the doors.
        // Cockpit stage 1: the nose box now reaches down without a floor - the CHIN WINDOW: under the pilot's feet (x 0.96..1.19,
        // z 0.05..0.41) the floor has a glass pane in a dark frame instead of carpet, and the belly shell under the whole
        // bubble (y < 0.35, x >= 0.90) is cut, so the ground shows through it (the co-pilot's side keeps its carpet).
        HELI.cabWindows = new float[][] {{0.90F, 1.80F, -OPEN, 1.30F, -OPEN, OPEN}, {-0.02F, 0.69F, 0.66F, 1.18F, -OPEN, OPEN}};
        // plane: eye (0.86, 0.99, -0.10) - the pilot sits 0.48 higher (seatDy) on a raised seat under the raised canopy
        // (glass x 0.36..1.48, y 0.78..1.15, |z| 0.24) and looks over the lowered dash (top 0.84) and the nose; the
        // passengers' side windows (x -0.58..-0.02, y 0.57..0.71) open sideways.
        PLANE.cabWindows = new float[][] {{0.36F, 1.50F, 0.84F, 1.16F, -0.24F, 0.24F}, {-0.58F, -0.01F, 0.565F, 0.71F, -OPEN, OPEN}};
        // jet: eye (1.21, 0.92, 0); the canopy (x 0.61..1.74, y 0.71..1.04, |z| 0.20).
        JET.cabWindows = new float[][] {{0.61F, 1.75F, 0.71F, 1.04F, -0.21F, 0.21F}};
        // tilt: pilots' eye (1.57, 0.83, 0.11); the cockpit glass (x 1.09..2.03, y 0.71..1.05, |z| 0.26) over the
        // clusters lowered to 0.73.
        TILT.cabWindows = new float[][] {{1.09F, 2.05F, 0.70F, 1.06F, -0.27F, 0.27F}};
        // truck: eye (2.17, 1.54, -0.30); the windscreen glass is inset (x 2.36..2.63, y 1.20..1.64) behind the cab's
        // sloped front - the box reaches through the slope (x to 3.20) over the dash hump (from 1.40); the door windows
        // (x 1.87..2.43, y 1.27..1.64) open sideways.
        TRUCK.cabWindows = new float[][] {{2.30F, 3.20F, 1.40F, 1.70F, -0.70F, 0.70F}, {1.86F, 2.44F, 1.26F, 1.65F, -OPEN, OPEN}};
        // drill: eye (0.36, 1.84, -0.31); the front glass (x 0.78..0.86, y 1.09..2.03, z -0.66..0.03) up to the cab's
        // right wall (z 0) and the left side window (x -0.06..0.69, y 1.22..1.97) - the corner pillar stays.
        DRILL.cabWindows = new float[][] {{0.76F, 0.95F, 1.09F, 2.04F, -0.66F, 0F}, {-0.07F, 0.70F, 1.21F, 1.98F, -OPEN, -0.6F}};
        // shuttle: pilots' eye (1.77, 1.38, 0.19); the front glass (x 2.17..2.73, y 1.01..1.57, |z| 0.44) and the lit side
        // windows (the "window" strip x -1.65..1.17, y 1.19..1.44) open sideways for the passengers.
        SHUTTLE.cabWindows = new float[][] {{2.10F, 2.80F, 1.01F, 1.57F, -0.44F, 0.44F}, {-1.70F, 1.20F, 1.18F, 1.45F, -OPEN, OPEN}};
        // ship: pilots' eye (2.38, 1.01, 0.22); the bubble canopy (x 1.77..3.39, y 0.22..1.38, |z| 0.54) from over the
        // console (0.40).
        SHIP.cabWindows = new float[][] {{1.76F, 3.40F, 0.40F, 1.38F, -0.54F, 0.54F}};
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

    /**
     * A seat's height change from seatY (an optional third number in its seatOffsets row, blocks; 0 without): a seat
     * under a lower roof (a pilot in the nose) sits a little deeper so the rider's head stays inside the hull.
     */
    public double seatDy(int seat) {
        double[] o = seatOffsets[Math.max(0, Math.min(seat, seatOffsets.length - 1))];
        return o.length > 2 ? o[2] : 0;
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
