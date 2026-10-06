package com.sva.vehicle;

/**
 * How a vehicle moves - the physics strategy EntityVehicleSVA.physicsStep dispatches on, and the server's move
 * check. APPEND-ONLY (stages B-E add HELI, PLANE ... with their own physics class next to HoverPhysicsSVA).
 */
public enum Movement {

    /** Wheels / tracks on the ground (EntityVehicleSVA's own ground physics). */
    GROUND(false),
    /** Antigravity: 1-3 blocks over ground / water / lava while it has lift gas and EU (HoverPhysicsSVA). */
    HOVER(true);

    /** Needs lift (gas + EU) to stay up; without it it comes down gently. */
    public final boolean lifts;

    Movement(boolean lifts) {
        this.lifts = lifts;
    }
}
