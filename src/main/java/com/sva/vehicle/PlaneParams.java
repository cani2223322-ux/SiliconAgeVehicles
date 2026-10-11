package com.sva.vehicle;

/**
 * Per-type aeroplane numbers (Movement.PLANE and the tiltrotor's airplane mode), read by PlaneMath. LIGHT is exactly the
 * stage B light plane (PlaneMath's constants), so the plane flies as before. Speeds in blocks/tick.
 */
public final class PlaneParams {

    /** Stall / lift-off speeds, the unpowered glide speed. */
    public final double stall, liftOff, glideSpeed;
    /** Thrust at full throttle (b/t² against the drag that balances it at top speed). */
    public final double thrust;
    /** Climb rate at full airspeed, reached this far over the stall (climbBand); dive, stall sink and lift-off climb rates. */
    public final double climb, climbBand, dive, stallSink, rotateVy;
    /**
     * Aircraft stage A (HighLiftMath.params - flaps, slats, spoilers): the drag on the top-speed term (thrust x (throttle -
     * drag x r^2)), the EU factor, the wheel brakes' / rolling friction's factor on the ground, the airbrake's descent in
     * the air. 1 / 1 / 1 / false for the types' own numbers: the flight as before.
     */
    public final double drag, cost, brake;
    public final boolean airbrake;

    /** The Light Plane (stage B): PlaneMath's constants. */
    public static final PlaneParams LIGHT = new PlaneParams(PlaneMath.STALL, PlaneMath.LIFT_OFF, PlaneMath.GLIDE_SPEED, PlaneMath.THRUST,
            PlaneMath.CLIMB, 0.25, PlaneMath.DIVE, PlaneMath.STALL_SINK, PlaneMath.ROTATE_VY);
    /**
     * The Jet (stage D): stall 0.9 b/t (65 km/h), lift-off 1.0 (72 km/h), thrust 0.025 - a take-off run of ~50 blocks,
     * ~25 on a runway; climbs 0.35 b/t (7 m/s) from 0.4 b/t over the stall, dives 0.5, a stalled nose drops 0.45.
     */
    public static final PlaneParams JET = new PlaneParams(0.9, 1.0, 1.15, 0.025, 0.35, 0.4, 0.5, 0.45, 0.12);
    /** The Tiltrotor's airplane mode (stage D): stall 0.6 b/t (43 km/h), climbs 0.25, thrust for x5 sprint (1.4 b/t). */
    public static final PlaneParams TILT = new PlaneParams(0.6, 0.65, 0.8, 0.014, 0.25, 0.3, 0.35, 0.35, 0.1);

    public PlaneParams(double stall, double liftOff, double glideSpeed, double thrust, double climb, double climbBand, double dive,
                       double stallSink, double rotateVy) {
        this.stall = stall;
        this.liftOff = liftOff;
        this.glideSpeed = glideSpeed;
        this.thrust = thrust;
        this.climb = climb;
        this.climbBand = climbBand;
        this.dive = dive;
        this.stallSink = stallSink;
        this.rotateVy = rotateVy;
        this.drag = 1;
        this.cost = 1;
        this.brake = 1;
        this.airbrake = false;
    }

    private PlaneParams(PlaneParams b, double stall, double liftOff, double drag, double cost, double brake, boolean airbrake) {
        this.stall = stall;
        this.liftOff = liftOff;
        this.glideSpeed = b.glideSpeed;
        this.thrust = b.thrust;
        this.climb = b.climb;
        this.climbBand = b.climbBand;
        this.dive = b.dive;
        this.stallSink = b.stallSink;
        this.rotateVy = b.rotateVy;
        this.drag = drag;
        this.cost = cost;
        this.brake = brake;
        this.airbrake = airbrake;
    }

    /** Stage A: a copy with the high-lift devices' stall / lift-off speeds, drag, EU and brake factors, the airbrake. */
    public PlaneParams derive(double stall, double liftOff, double drag, double cost, double brake, boolean airbrake) {
        return new PlaneParams(this, stall, liftOff, drag, cost, brake, airbrake);
    }
}
