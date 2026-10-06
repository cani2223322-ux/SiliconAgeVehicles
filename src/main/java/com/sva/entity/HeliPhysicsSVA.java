package com.sva.entity;

import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HeliMath;
import com.sva.vehicle.VehicleType;

/**
 * Movement.HELI physics (the driver's client and the server's fallback alike): Space = collective up, «Снижение» /
 * sprint = down, neither = hold the height (HeliMath.targetVy, fading under the ceiling); W/S forward / back (only a
 * slow taxi on the ground), A/D yaw - also in a hover. Without power in the air: autorotation - a slow spiral down
 * (HeliMath.autoVy, AUTO_FWD, AUTO_TURN), A/D still steer a little. Riders take no fall damage (the server zeroes it).
 */
public final class HeliPhysicsSVA {

    private HeliPhysicsSVA() {
    }

    public static void step(EntityVehicleSVA v, VehicleType t, int in) {
        boolean fwd = (in & EntityVehicleSVA.IN_FWD) != 0, back = (in & EntityVehicleSVA.IN_BACK) != 0,
                left = (in & EntityVehicleSVA.IN_LEFT) != 0, right = (in & EntityVehicleSVA.IN_RIGHT) != 0,
                up = (in & EntityVehicleSVA.IN_BRAKE) != 0, down = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        boolean lift = v.hasLift() && v.canThrust();
        boolean air = !v.onGround;
        double speed = v.speed;
        int steer = (right ? 1 : 0) - (left ? 1 : 0);
        double vy;
        if (lift) {
            double top = air ? t.topSpeed : HeliMath.TAXI;
            if (fwd && !back) {
                speed += t.accel;
            } else if (back && !fwd) {
                speed -= t.accel;
            } else {
                speed = toward0(speed, HeliMath.DRAG);
            }
            if (speed > top) {
                speed = Math.max(top, speed - EntityVehicleSVA.BRAKE);
            }
            if (speed < -top * HeliMath.REVERSE) {
                speed = Math.min(-top * HeliMath.REVERSE, speed + EntityVehicleSVA.BRAKE);
            }
            v.rotationYaw += steer * t.turnRate;
            double target = HeliMath.targetVy(up, down, v.posY);
            vy = air || target > 0 ? HeliMath.poweredVy(v.motionY, up, down, v.posY) : -0.08;   // grounded: stay down
        } else if (air) {
            // autorotation: settle to a slow forward drift and spiral down
            speed = FlightMath.approach(speed, HeliMath.AUTO_FWD, 0.01);
            v.rotationYaw += HeliMath.AUTO_TURN + steer * t.turnRate * 0.5;
            vy = HeliMath.autoVy(v.motionY);
        } else {
            speed = toward0(speed, EntityVehicleSVA.BRAKE);
            vy = -0.08;
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
            speed = (v.posX - px) * fx + (v.posZ - pz) * fz;
        }
        v.speed = speed;
    }

    private static double toward0(double v, double step) {
        return v > 0 ? Math.max(0, v - step) : Math.min(0, v + step);
    }
}
