package com.sva.vehicle;

/**
 * How a vehicle moves - the physics strategy EntityVehicleSVA.physicsStep dispatches on, and the server's move
 * check. APPEND-ONLY (stages C-E add more with their own physics class next to HoverPhysicsSVA / HeliPhysicsSVA).
 */
public enum Movement {

    /** Wheels / tracks on the ground (EntityVehicleSVA's own ground physics). */
    GROUND(false, false),
    /** Antigravity: 1-3 blocks over ground / water / lava while it has lift gas and EU (HoverPhysicsSVA). */
    HOVER(true, false),
    /** Helicopter: vertical take-off, hover, ceiling Y 200; no power - autorotation (HeliPhysicsSVA, HeliMath). */
    HELI(true, true),
    /** Aeroplane: lift from speed, stall, take-off run (halved on runways); no power - a 1:8 glide (PlanePhysicsSVA, PlaneMath). */
    PLANE(true, true),
    /**
     * Tracks (stage C: cargo crawler, drill vehicle): EntityVehicleSVA's ground physics with turning on the spot,
     * 2-block steps (VehicleType.stepHeight), no wheel slip, and it swims across water at half speed (CrawlerMath).
     */
    CRAWLER(false, false);

    /** Needs lift (power, a lift gas) to stay up; without it it comes down gently. */
    public final boolean lifts;
    /** Really flies (any height up to FlightMath.CEILING): the server's flying-move check, no fall damage for riders. */
    public final boolean flies;

    Movement(boolean lifts, boolean flies) {
        this.lifts = lifts;
        this.flies = flies;
    }
}
