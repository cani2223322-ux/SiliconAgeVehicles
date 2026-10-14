package com.sva.entity;

import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

/**
 * Movement.PLANE physics (the driver's client and the server's fallback alike): W/S throttle (PlaneMath.throttleStep;
 * at zero throttle on the ground S brakes - softly on a runway), A/D turn, Space pull up (lift-off from LIFT_OFF
 * speed on the ground), «Снижение» / sprint push down, neither = level flight. The airspeed gives lift: below the
 * stall the nose drops. Without power: a 1:8 glide. It never goes backwards. Per-type numbers from VehicleType.plane;
 * the jet's afterburner («Форсаж» at full throttle, EntityVehicleSVA.afterburnerMul) multiplies thrust and top speed,
 * never past FlightMath.SPEED_CAP. Wave 1 (★4): with the autopilot engaged (IN_CRUISE, in the air) the heading and the
 * height are held by CruiseMath instead of A/D / Space / «Снижение» (any of W/S/A/D switches it off on the client).
 * Aircraft stage A: the numbers come from EntityVehicleSVA.flightParams - the type's PlaneParams with the synced
 * flaps / slats / airbrake / ground spoilers applied (HighLiftMath.params; all retracted = the type's own object): the
 * stall, the lift-off speed, the drag, the brakes, the airbrake's descent. The autopilot holds the height with them
 * (the flaps' lower stall gives it more climb at a low speed; the airbrake only adds drag under the autopilot).
 * Liner wave 1: the thrust term sees the SA-320's two engines (LinerSystems.thrust - their N1 with the spool's lag, a dead
 * engine half the thrust) instead of the throttle x the engine's power, uneven thrust yaws it (LinerLogicSVA.ASYM_*), and
 * the parking brake holds it on the ground. Liner wave 2: the wheel brakes are LinerGear's (the hydraulic mode, the
 * anti-skid on the ground's grip, the autobrake, the parking brake on the accumulator, flat tyres' drag), the ground turn is
 * the nose-wheel steering's (Yellow: the tiller slow, the pedals fast) or differential braking; in the air the turn and
 * the pitch keys work by the powered surfaces (LinerFctl's authority x the law's gain).
 */
public final class PlanePhysicsSVA {

    private PlanePhysicsSVA() {
    }

    public static void step(EntityVehicleSVA v, VehicleType t, int in) {
        boolean fwd = (in & EntityVehicleSVA.IN_FWD) != 0, back = (in & EntityVehicleSVA.IN_BACK) != 0,
                left = (in & EntityVehicleSVA.IN_LEFT) != 0, right = (in & EntityVehicleSVA.IN_RIGHT) != 0,
                up = (in & EntityVehicleSVA.IN_BRAKE) != 0, down = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        PlaneParams p = v.flightParams(t);
        boolean powered = v.hasLift() && v.canThrust();
        boolean air = !v.onGround;
        v.throttle = PlaneMath.throttleStep(v.throttle, fwd, back, powered);
        double thr = v.throttle;
        double mul = v.afterburnerMul(in);                    // the jet's afterburner (1 = off)
        // round 2, D2: the engine's power (the warm-up's ramp, a failed engine x0.6) scales the throttle the thrust sees -
        // a weaker engine accelerates slower and tops out lower (thrust x (thr - drag x r^2))
        double pw = v.enginePower();
        double speed = Math.max(0, v.speed);
        com.sva.vehicle.LinerSystems liner = t == VehicleType.LINER ? v.liner() : null;
        if (liner != null) {
            // liner wave 1: the engines make the thrust (the throttle is their thrust levers): thr x pw = their mean thrust
            double th = liner.thrust();
            pw = thr > 0.001 ? th / thr : 1;
            if (thr <= 0.001) {
                thr = th;
            }
        }
        double vy;
        boolean auto = air && v.cruiseActive(in);
        if (auto) {
            // ★4 autopilot: the height held by steering the vertical speed (CruiseMath), the throttle as the pilot left it;
            // stage C: the modes (AutopilotMath) - ВЫС / ВЕРТ / ЗАХВ's glide slope steer the vertical speed (none: level
            // flight as without keys), СКОР the throttle
            v.apBegin();
            v.throttle = v.apThrottle(v.throttle, speed);
            thr = v.throttle;
            double climb = PlaneMath.climbCapacity(p, speed) * FlightMath.ceilingFactor(v.posY);
            double want = v.apWantedVy(climb, p.dive);
            vy = Double.isNaN(want) ? PlaneMath.airVy(p, speed, v.motionY, false, false, powered, v.posY)
                    : FlightMath.approach(v.motionY, want, PlaneMath.VACC);
            speed += PlaneMath.airAccel(t, p, speed, thr * pw, vy, vy > 0, powered, mul);
        } else if (air) {
            vy = PlaneMath.airVy(p, speed, v.motionY, up, down, powered, v.posY);
            if (liner != null && (up || down)) {
                // liner wave 2: the elevators / THS that work give the pitch keys their share (all lost - the trim wheel's)
                double level = PlaneMath.airVy(p, speed, v.motionY, false, false, powered, v.posY);
                vy = level + (vy - level) * Math.min(1, liner.fctl.pitchAuthority() * liner.fctl.lawGain());
            }
            speed += PlaneMath.airAccel(t, p, speed, thr * pw, vy, up, powered, mul);
        } else {
            boolean runway = v.onRunway();
            speed += PlaneMath.accel(t, p, speed, thr * pw, false, runway, mul);
            if (liner != null) {
                // liner wave 2: the hydraulic brakes (the pedals = S at idle, the autobrake, the parking brake), flat tyres
                com.sva.vehicle.LinerGear g = liner.gear;
                if (thr <= 0 && speed > 0) {
                    speed -= PlaneMath.ROLL * p.brake * (g.rollingMul() - 1);
                }
                speed -= g.decel(back && thr <= 0, speed, v.linerMu(), liner.on(com.sva.vehicle.LinerControls.PARK_BRK));
                speed -= g.diffDrag((right ? 1 : 0) - (left ? 1 : 0));
            } else if (back && thr <= 0) {
                speed -= PlaneMath.brake(p, runway);
            }
            speed = Math.max(0, speed);
            vy = up && powered && speed >= p.liftOff ? p.rotateVy : -0.08;
        }
        speed = VehicleMath.clamp(speed, 0, PlaneMath.maxSpeed(t, mul));
        int steer = (right ? 1 : 0) - (left ? 1 : 0);
        double wantYaw = auto ? v.apWantedYaw() : Double.NaN;
        if (auto && !Double.isNaN(wantYaw)) {
            v.rotationYaw = (float) CruiseMath.headingStep(v.rotationYaw, wantYaw, t.turnRate * VehicleMath.clamp(speed / p.stall, 0, 1));
        } else if (auto) {
            // stage C: no heading mode - wings level, no turning
        } else if (liner != null && !air) {
            v.rotationYaw += (float) liner.gear.turnRate(steer, speed, t.turnRate);   // liner wave 2: the nose-wheel steering
        } else if (steer != 0 && speed > 0) {
            double rate = air ? t.turnRate * VehicleMath.clamp(speed / p.stall, 0, 1)
                    : t.turnRate * 0.7 * Math.min(1, speed / 0.12);
            if (liner != null) {
                rate *= liner.fctl.rollAuthority() * liner.fctl.lawGain();      // liner wave 2: what the powered surfaces give
            }
            v.rotationYaw += steer * rate;
        }
        if (liner != null && speed > 0.02) {
            // liner wave 1: one engine stronger yaws it to the weaker side's opposite (the left engine pushes the nose right)
            v.rotationYaw += (float) liner.asymYaw(air ? LinerLogicSVA.ASYM_AIR : LinerLogicSVA.ASYM_GROUND * Math.min(1, speed / 0.3));
        }
        move(v, speed, vy);
    }

    /** Moves along the heading at `speed` with `vy` (a wall takes the rest of the speed); keeps fall distance 0. */
    static void move(EntityVehicleSVA v, double speed, double vy) {
        double r = Math.toRadians(v.rotationYaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        v.motionX = fx * speed;
        v.motionZ = fz * speed;
        v.fallDistance = 0;                                   // the server judges touch-downs itself
        double px = v.posX, pz = v.posZ;
        v.moveEntity(v.motionX, vy, v.motionZ);
        v.motionY = v.isCollidedVertically ? 0 : vy;
        v.fallDistance = 0;
        if (v.isCollidedHorizontally) {
            speed = Math.max(0, (v.posX - px) * fx + (v.posZ - pz) * fz);
        }
        v.speed = speed;
    }
}
