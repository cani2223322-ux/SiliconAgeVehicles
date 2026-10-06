package com.sva.entity;

import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HeliMath;
import com.sva.vehicle.TiltMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

/**
 * Movement.TILT physics (the driver's client and the server's fallback alike), by the nacelle position (synced):
 * vertical mode - HeliPhysicsSVA with the type's vtolTop / vtolAccel; airplane mode - PlanePhysicsSVA (VehicleType.plane);
 * while the nacelles turn - the collective as on a helicopter (Space up, «Снижение» / sprint down, neither holds the
 * height), A/D yaw, and the tilting rotors pull the speed toward TiltMath.transitionSpeed (the airplane stall x 1.15
 * going over, the vertical top speed coming back). No power during the turn: autorotation, as a helicopter.
 */
public final class TiltPhysicsSVA {

    private TiltPhysicsSVA() {
    }

    public static void step(EntityVehicleSVA v, VehicleType t, int in) {
        double pos = v.getTiltPos();
        if (TiltMath.verticalMode(pos)) {
            HeliPhysicsSVA.step(v, t, in, t.vtolTop, t.vtolAccel);
            return;
        }
        if (TiltMath.planeMode(pos)) {
            PlanePhysicsSVA.step(v, t, in);
            return;
        }
        boolean left = (in & EntityVehicleSVA.IN_LEFT) != 0, right = (in & EntityVehicleSVA.IN_RIGHT) != 0,
                up = (in & EntityVehicleSVA.IN_BRAKE) != 0, down = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        boolean toPlane = v.getTiltTarget();
        boolean lift = v.hasLift() && v.canThrust();
        boolean air = !v.onGround;
        int steer = (right ? 1 : 0) - (left ? 1 : 0);
        double speed = Math.max(0, v.speed);
        double vy;
        if (lift) {
            speed = FlightMath.approach(speed, TiltMath.transitionSpeed(t, toPlane), TiltMath.CONVERT_ACC);
            v.rotationYaw += steer * t.turnRate * 0.6;
            double target = HeliMath.targetVy(up, down, v.posY);
            vy = air || target > 0 ? HeliMath.poweredVy(v.motionY, up, down, v.posY) : -0.08;
            if (toPlane) {
                v.throttle = Math.max(v.throttle, TiltMath.CONVERT_THROTTLE);   // holds the conversion speed once it is a plane
            }
        } else if (air) {
            speed = FlightMath.approach(speed, HeliMath.AUTO_FWD, 0.01);
            v.rotationYaw += HeliMath.AUTO_TURN + steer * t.turnRate * 0.5;
            vy = HeliMath.autoVy(v.motionY);
        } else {
            speed = HeliPhysicsSVA.toward0(speed, EntityVehicleSVA.BRAKE);
            vy = -0.08;
        }
        PlanePhysicsSVA.move(v, VehicleMath.clamp(speed, 0, t.topSpeed), vy);
    }
}
