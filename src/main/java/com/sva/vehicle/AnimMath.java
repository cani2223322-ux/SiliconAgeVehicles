package com.sva.vehicle;

/**
 * ★6 / ★7 / ★10: pure animation and light-timing math (server-safe - the self-test runs it; the client keeps a State
 * per vehicle, EntityVehicleSVA.anim, and ticks it from data every client already has: the interpolated position and
 * yaw, the synced tiltrotor position, the driver's own keys). Nothing here is synced; config vehicles.animations off =
 * the state stays at rest (wheels still, surfaces neutral, gear down) = the old static look.
 */
public final class AnimMath {

    /** Control surfaces deflect at most this much (deg). */
    public static final float MAX_SURFACE = 20F;
    /** Front wheels steer at most this much (deg). */
    public static final float MAX_STEER = 25F;
    /** Gear retracts above this height (blocks over the ground) at RETRACT_SPEED b/t or more; extends below EXTEND_HEIGHT or under EXTEND_SPEED. */
    public static final double RETRACT_HEIGHT = 6, EXTEND_HEIGHT = 4, RETRACT_SPEED = 0.45, EXTEND_SPEED = 0.3;
    /** The gear's travel takes this many ticks (1 s). */
    public static final int GEAR_TICKS = 20;
    /** Smoothing of the surfaces / steering / lean per tick (fraction of the gap closed). */
    public static final float SMOOTH = 0.25F;
    /** Strobe: one flash of STROBE_ON ticks every STROBE_PERIOD (1/s); beacons turn once per BEACON_PERIOD. */
    public static final int STROBE_PERIOD = 20, STROBE_ON = 2, BEACON_PERIOD = 20;
    /** Brake lights stay bright this many ticks after the last braking tick. */
    public static final int BRAKE_HOLD = 6;
    /** Suspension bob: spring / damping per tick, the largest offset (blocks), the jolt that starts it (change of dy, b/t). */
    public static final float BOB_K = 0.25F, BOB_DAMP = 0.3F, BOB_MAX = 0.12F, BOB_JOLT = 0.05F;
    /** Hover bike: lean into turns (deg per deg/tick of yaw rate, at most MAX_LEAN). */
    public static final float LEAN_PER_YAW = 5F, MAX_LEAN = 10F;

    private AnimMath() {
    }

    /** One vehicle's client animation state (current + previous tick for the partial-tick lerp). */
    public static final class State {
        /** Distance rolled along the facing (model units, signed) - wheel spin and the tread scroll. */
        public double roll, prevRoll;
        public float steer, prevSteer;
        /** Commands (deg): aileron + = bank right, elevator + = pitch up, rudder + = yaw right. */
        public float aileron, prevAileron, elevator, prevElevator, rudder, prevRudder;
        /** Gear 0 down .. 1 up and where it is going. */
        public float gear, prevGear;
        public boolean gearUp;
        /** Body bob (blocks) and its speed; the hover bike's lean (deg, + = right). */
        public float bob, prevBob, bobV, lean, prevLean;
        /** Smoothed tiltrotor nacelle position (0..1). */
        public double tilt = -1, prevTilt;
        /** Height over the ground (blocks; the client looks it up every few ticks) and last tick's dy / speed. */
        public double height;
        public float lastDy, lastFwd;
        public int brakeTicks;
        public boolean inited;
        /**
         * Cockpit stage 1: the cabin controls' inputs, -1..1 (AnimParts PITCH, ROLL, YAW, LIFT, THROTTLE - the throttle
         * as -1..1), smoothed like the surfaces (controls()); prev* for the partial-tick lerp.
         */
        public float ctlPitch, prevCtlPitch, ctlRoll, prevCtlRoll, ctlYaw, prevCtlYaw, ctlLift, prevCtlLift, ctlThrottle = -1F,
                prevCtlThrottle = -1F;
        /**
         * Aircraft stage A (highLift()): the flap position in settings (fractional while moving, 1.5 s a step), the slats
         * and the spoilers / the jet's speed brake 0..1 - following the synced state; prev* for the partial-tick lerp.
         */
        public float flapPos, prevFlapPos, slat, prevSlat, spoiler, prevSpoiler;

        /** A CONTROL input (AnimParts.STEER..THROTTLE) at partial tick pt, -1..1. */
        public float control(int in, float pt) {
            switch (in) {
                case AnimParts.STEER:
                    return lerp(prevSteer, steer, pt) / MAX_STEER;
                case AnimParts.PITCH:
                    return lerp(prevCtlPitch, ctlPitch, pt);
                case AnimParts.ROLL:
                    return lerp(prevCtlRoll, ctlRoll, pt);
                case AnimParts.YAW:
                    return lerp(prevCtlYaw, ctlYaw, pt);
                case AnimParts.LIFT:
                    return lerp(prevCtlLift, ctlLift, pt);
                case AnimParts.THROTTLE:
                    return lerp(prevCtlThrottle, ctlThrottle, pt);
                default:
                    return 0F;
            }
        }

        public boolean braking() {
            return brakeTicks > 0;
        }
    }

    /**
     * One client tick. fwd / dy: this tick's motion along the facing and up (blocks); yawRate: deg/tick (+ = turning right);
     * turnRate: the type's full turn rate (deg/tick); scale: the model scale (blocks per model unit); ground: the wheels
     * touch the ground; steerIn: the driver's own A/D (-1 / 0 / 1) or NaN (someone else's vehicle: from the yaw rate);
     * brakeIn: the driver holds a brake key (false if unknown); gearType: has retracting gear; plane: flies as an
     * aeroplane now (surfaces work); hover: the hover bike (lean); bounce: a ground vehicle (suspension bob); tiltPos:
     * the synced nacelle position (NaN: not a tiltrotor); animate: config vehicles.animations.
     */
    public static void tick(State s, float fwd, float dy, float yawRate, float turnRate, float scale, boolean ground, float steerIn,
            boolean brakeIn, boolean gearType, boolean plane, boolean hover, boolean bounce, double tiltPos, boolean animate) {
        s.prevRoll = s.roll;
        s.prevSteer = s.steer;
        s.prevAileron = s.aileron;
        s.prevElevator = s.elevator;
        s.prevRudder = s.rudder;
        s.prevGear = s.gear;
        s.prevBob = s.bob;
        s.prevLean = s.lean;
        s.prevTilt = s.tilt;
        if (!Double.isNaN(tiltPos)) {
            // the synced position moves 1/40 a tick in steps; follow it at most 1.5 steps a tick so a late packet glides
            s.tilt = s.tilt < 0 || !animate ? tiltPos : approach(s.tilt, tiltPos, 1.5 / TiltMath.TRANSITION_TICKS);
            if (!s.inited || !animate) {
                s.prevTilt = s.tilt;
            }
        }
        boolean braking = brakeIn || braking(s.lastFwd, fwd);
        s.brakeTicks = braking ? BRAKE_HOLD : Math.max(0, s.brakeTicks - 1);
        if (!animate) {
            s.roll = s.prevRoll = 0;
            s.steer = s.aileron = s.elevator = s.rudder = s.gear = s.bob = s.bobV = s.lean = 0;
            s.gearUp = false;
            s.lastDy = dy;
            s.lastFwd = fwd;
            s.inited = true;
            return;
        }
        if (ground && scale > 0) {
            s.roll += fwd / scale;
        }
        s.steer = smooth(s.steer, steerTarget(steerIn, yawRate, turnRate, fwd));
        if (plane) {
            s.aileron = smooth(s.aileron, aileronTarget(yawRate, turnRate));
            s.elevator = smooth(s.elevator, elevatorTarget(dy, fwd));
            s.rudder = smooth(s.rudder, rudderTarget(yawRate, turnRate));
        } else {
            s.aileron = smooth(s.aileron, 0);
            s.elevator = smooth(s.elevator, 0);
            s.rudder = smooth(s.rudder, 0);
        }
        if (gearType) {
            s.gearUp = gearTarget(s.gearUp, s.height, Math.abs(fwd));
            s.gear = (float) approach(s.gear, s.gearUp ? 1 : 0, 1.0 / GEAR_TICKS);
        } else {
            s.gear = 0;
        }
        if (bounce && s.inited) {
            s.bobV += bobJolt(s.lastDy, dy);
        }
        s.bobV += -BOB_K * s.bob - BOB_DAMP * s.bobV;
        s.bob = clamp(s.bob + s.bobV, -BOB_MAX, BOB_MAX);
        s.lean = smooth(s.lean, hover ? clamp(yawRate * LEAN_PER_YAW, -MAX_LEAN, MAX_LEAN) : 0);
        s.lastDy = dy;
        s.lastFwd = fwd;
        if (!s.inited) {
            s.prevSteer = s.steer;
            s.prevGear = s.gear;
            s.inited = true;
        }
    }

    /**
     * Cockpit stage 1, one client tick of the cabin controls (after tick()): each input eases toward its target (clamped
     * to -1..1; the throttle 0..1 becomes -1..1) by SMOOTH a tick; animate off = everything at rest (the throttle lever in
     * its back stop). The targets come from the driver's keys or, for anyone else, from the motion (EntityVehicleSVA).
     */
    public static void controls(State s, float pitch, float roll, float yaw, float lift, float throttle01, boolean animate) {
        s.prevCtlPitch = s.ctlPitch;
        s.prevCtlRoll = s.ctlRoll;
        s.prevCtlYaw = s.ctlYaw;
        s.prevCtlLift = s.ctlLift;
        s.prevCtlThrottle = s.ctlThrottle;
        if (!animate) {
            s.ctlPitch = s.ctlRoll = s.ctlYaw = s.ctlLift = 0F;
            s.ctlThrottle = -1F;
            return;
        }
        s.ctlPitch = smooth(s.ctlPitch, clamp(pitch, -1, 1));
        s.ctlRoll = smooth(s.ctlRoll, clamp(roll, -1, 1));
        s.ctlYaw = smooth(s.ctlYaw, clamp(yaw, -1, 1));
        s.ctlLift = smooth(s.ctlLift, clamp(lift, -1, 1));
        s.ctlThrottle = smooth(s.ctlThrottle, clamp(throttle01, 0, 1) * 2F - 1F);
    }

    /**
     * Aircraft stage A, one client tick of the high-lift devices (after tick()): the flaps travel toward the synced setting
     * one HighLiftMath.FLAP_TICKS-th of a step a tick, the slats / spoilers toward in / out; animate off = they jump.
     */
    public static void highLift(State s, int flaps, boolean slats, boolean spoilers, boolean animate) {
        s.prevFlapPos = s.flapPos;
        s.prevSlat = s.slat;
        s.prevSpoiler = s.spoiler;
        if (!animate) {
            s.flapPos = s.prevFlapPos = flaps;
            s.slat = s.prevSlat = slats ? 1F : 0F;
            s.spoiler = s.prevSpoiler = spoilers ? 1F : 0F;
            return;
        }
        s.flapPos = HighLiftMath.flapPos(s.flapPos, flaps);
        s.slat = HighLiftMath.level(s.slat, slats, HighLiftMath.SLAT_TICKS);
        s.spoiler = HighLiftMath.level(s.spoiler, spoilers, HighLiftMath.SPOILER_TICKS);
    }

    /**
     * Gear wanted up: retracts above RETRACT_HEIGHT at RETRACT_SPEED or faster, comes down below EXTEND_HEIGHT or under
     * EXTEND_SPEED; in between it stays as it is (no flapping at the threshold).
     */
    public static boolean gearTarget(boolean up, double height, double speed) {
        if (height < EXTEND_HEIGHT || speed < EXTEND_SPEED) {
            return false;
        }
        if (height > RETRACT_HEIGHT && speed >= RETRACT_SPEED) {
            return true;
        }
        return up;
    }

    /** Ailerons: full deflection at the type's full turn rate (bank right = + ). */
    public static float aileronTarget(float yawRate, float turnRate) {
        return clamp(yawRate / Math.max(0.1F, turnRate) * MAX_SURFACE, -MAX_SURFACE, MAX_SURFACE);
    }

    /** Rudder: 60 % of the aileron's travel in a turn (yaw right = +). */
    public static float rudderTarget(float yawRate, float turnRate) {
        return clamp(yawRate / Math.max(0.1F, turnRate) * MAX_SURFACE * 0.6F, -MAX_SURFACE, MAX_SURFACE);
    }

    /** Elevator: the climb angle (deg) x 1.5, pitch up = + . */
    public static float elevatorTarget(float dy, float fwd) {
        float climb = (float) Math.toDegrees(Math.atan2(dy, Math.max(0.15F, Math.abs(fwd))));
        return clamp(climb * 1.5F, -MAX_SURFACE, MAX_SURFACE);
    }

    /**
     * Front wheel angle (+ = right): the driver's own keys when known, else from the yaw rate (reversing turns the body
     * the other way, so the sign follows the direction of travel).
     */
    public static float steerTarget(float steerIn, float yawRate, float turnRate, float fwd) {
        if (!Float.isNaN(steerIn)) {
            return clamp(steerIn, -1, 1) * MAX_STEER;
        }
        float dir = fwd < -0.01F ? -1F : 1F;
        return clamp(yawRate / Math.max(0.1F, turnRate) * MAX_STEER * dir, -MAX_STEER, MAX_STEER);
    }

    /** Slowing down by more than 0.004 b/t in a tick while moving - the brake lights of someone else's vehicle. */
    public static boolean braking(float lastFwd, float fwd) {
        return Math.abs(lastFwd) > 0.02F && Math.abs(fwd) < Math.abs(lastFwd) - 0.004F && Math.signum(fwd) != -Math.signum(lastFwd);
    }

    /** A jolt to the bob's speed from a sudden change of the vertical motion (landing, a step, a bump). */
    public static float bobJolt(float lastDy, float dy) {
        float d = dy - lastDy;
        return Math.abs(d) < BOB_JOLT ? 0F : clamp(-d * 0.6F, -0.08F, 0.08F);
    }

    /** Wheel angle (deg) for `roll` model units rolled forward on a wheel of radius r: forward = the top moves forward = negative about +z. */
    public static float wheelAngle(double roll, float r) {
        if (r <= 0) {
            return 0F;
        }
        return (float) -Math.toDegrees((roll / r) % (Math.PI * 2));
    }

    // ------------------------------------------------------------------ light timing (★7)

    /** Per-vehicle blink phase (ticks) so a parked row does not flash in step. */
    public static int phase(int entityId) {
        return (entityId * 7 & 0x7fffffff) % STROBE_PERIOD;
    }

    /** White strobe: on for STROBE_ON ticks out of every STROBE_PERIOD. */
    public static boolean strobe(long ticks, int phase) {
        return ((ticks + phase) % STROBE_PERIOD + STROBE_PERIOD) % STROBE_PERIOD < STROBE_ON;
    }

    /**
     * A rotating beacon seen from the side: brightness 0.15 .. 1, a sharp peak once a turn (cos^6 of the turn angle).
     * `t` in ticks (partial tick included).
     */
    public static float beacon(double t, int phase, int period) {
        double a = (t + phase) * Math.PI * 2 / Math.max(1, period);
        double c = Math.max(0, Math.cos(a));
        return (float) (0.15 + 0.85 * Math.pow(c, 6));
    }

    // ------------------------------------------------------------------ helpers

    public static float smooth(float v, float target) {
        return v + (target - v) * SMOOTH;
    }

    public static double approach(double v, double target, double step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** Partial-tick lerp. */
    public static float lerp(float prev, float cur, float pt) {
        return prev + (cur - prev) * pt;
    }
}
