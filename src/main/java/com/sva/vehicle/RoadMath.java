package com.sva.vehicle;

/**
 * Wave 1, ★11 (mock-up veh_more_5_roads.png): what a road surface gives a vehicle - pure numbers (the world lookup is
 * block/RoadsSVA.surfaceUnder; the self-test checks these).
 * <p>
 * Asphalt (with or without markings): top speed x1.3, hull wear x0.5 (wall hits and hard landings while on it - the only
 * wear a ground vehicle takes from driving); the concrete slab: x1.2 and x0.75 (cheaper to make). Only for wheels and
 * tracks (Movement GROUND / CRAWLER) - the hoverbike floats over it, aircraft roll on runways instead. Applied on the
 * driver's client (its physics) and on the server alike (the move check's reach, the fallback physics, the wear).
 */
public final class RoadMath {

    public static final int NONE = 0, ASPHALT = 1, CONCRETE = 2;
    public static final double ASPHALT_SPEED = 1.3, CONCRETE_SPEED = 1.2;
    public static final double ASPHALT_WEAR = 0.5, CONCRETE_WEAR = 0.75;

    private RoadMath() {
    }

    /** Does this movement drive on the surface (wheels / tracks)? */
    public static boolean onWheels(Movement m) {
        return m == Movement.GROUND || m == Movement.CRAWLER;
    }

    /** Top speed factor of a surface for a movement (1 off-road or for anything that doesn't roll on it). */
    public static double speedMul(int surface, Movement m) {
        if (!onWheels(m)) {
            return 1;
        }
        return surface == ASPHALT ? ASPHALT_SPEED : surface == CONCRETE ? CONCRETE_SPEED : 1;
    }

    /** Hull wear factor (wall hits, hard landings) of a surface for a movement. */
    public static double wearMul(int surface, Movement m) {
        if (!onWheels(m)) {
            return 1;
        }
        return surface == ASPHALT ? ASPHALT_WEAR : surface == CONCRETE ? CONCRETE_WEAR : 1;
    }
}
