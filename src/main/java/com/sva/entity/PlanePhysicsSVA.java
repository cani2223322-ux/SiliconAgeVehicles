package com.sva.entity;

import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

/**
 * Movement.PLANE physics (the driver's client and the server's fallback alike): W/S throttle (PlaneMath.throttleStep;
 * at zero throttle on the ground S brakes - softly on a runway), A/D turn, Space pull up (lift-off from LIFT_OFF
 * speed on the ground), «Снижение» / sprint push down, neither = level flight. The airspeed gives lift: below the
 * stall the nose drops. Without power: a 1:8 glide. It never goes backwards.
 */
public final class PlanePhysicsSVA {

    private PlanePhysicsSVA() {
    }

    public static void step(EntityVehicleSVA v, VehicleType t, int in) {
        boolean fwd = (in & EntityVehicleSVA.IN_FWD) != 0, back = (in & EntityVehicleSVA.IN_BACK) != 0,
                left = (in & EntityVehicleSVA.IN_LEFT) != 0, right = (in & EntityVehicleSVA.IN_RIGHT) != 0,
                up = (in & EntityVehicleSVA.IN_BRAKE) != 0, down = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        boolean powered = v.hasLift() && v.canThrust();
        boolean air = !v.onGround;
        v.throttle = PlaneMath.throttleStep(v.throttle, fwd, back, powered);
        double thr = v.throttle;
        double speed = Math.max(0, v.speed);
        double vy;
        if (air) {
            vy = PlaneMath.airVy(speed, v.motionY, up, down, powered, v.posY);
            speed += PlaneMath.airAccel(t, speed, thr, vy, up, powered);
        } else {
            boolean runway = v.onRunway();
            speed += PlaneMath.accel(t, speed, thr, false, runway);
            if (back && thr <= 0) {
                speed -= PlaneMath.brake(runway);
            }
            speed = Math.max(0, speed);
            vy = up && powered && speed >= PlaneMath.LIFT_OFF ? PlaneMath.ROTATE_VY : -0.08;
        }
        speed = VehicleMath.clamp(speed, 0, t.topSpeed * PlaneMath.MAX_SPEED_MUL);
        int steer = (right ? 1 : 0) - (left ? 1 : 0);
        if (steer != 0 && speed > 0) {
            double rate = air ? t.turnRate * VehicleMath.clamp(speed / PlaneMath.STALL, 0, 1)
                    : t.turnRate * 0.7 * Math.min(1, speed / 0.12);
            v.rotationYaw += steer * rate;
        }
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
