package com.sva.entity;

import com.sva.vehicle.LinerFlight;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.VehicleType;

/**
 * Liner wave 4 (plan-next §7): the SA-320's physics step for the entity - the pure model is vehicle.LinerFlight (the bank,
 * the autopilot's sticks, the protections, the reversers, the roll-out); this wraps it: the entity's state in, the move
 * out (PlanePhysicsSVA.move - collisions, the fall distance). The driving client and the server (no driver, the world
 * tests) run it alike from the synced systems (LinerSystems: the engines' N1, the AFS' modes and targets, the levers).
 */
public final class LinerPhysicsSVA {

    private LinerPhysicsSVA() {
    }

    static void step(EntityVehicleSVA v, VehicleType t, int in, LinerSystems L) {
        LinerFlight.Keys k = new LinerFlight.Keys();
        k.fwd = (in & EntityVehicleSVA.IN_FWD) != 0;
        k.back = (in & EntityVehicleSVA.IN_BACK) != 0;
        k.left = (in & EntityVehicleSVA.IN_LEFT) != 0;
        k.right = (in & EntityVehicleSVA.IN_RIGHT) != 0;
        k.up = (in & EntityVehicleSVA.IN_BRAKE) != 0;
        k.down = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        PlaneParams p = v.flightParams(t);
        v.throttle = L.afs.lever;                                   // the levers (the server's; a client sees the synced ones)
        LinerFlight.Body b = body(v, L);
        double vy = LinerFlight.step(b, t, p, L, k, v.hasLift() && v.canThrust(), v.onRunway(), v.linerMu());
        v.rotationYaw = (float) b.yaw;
        v.linerBank = b.bank;
        PlanePhysicsSVA.move(v, b.speed, vy);
    }

    /** The entity as LinerFlight's body: position, yaw, speed, vertical speed, bank, on the ground, the radio height, NAV's heading. */
    public static LinerFlight.Body body(EntityVehicleSVA v, LinerSystems L) {
        LinerFlight.Body b = new LinerFlight.Body();
        b.x = v.posX;
        b.y = v.posY;
        b.z = v.posZ;
        b.yaw = v.rotationYaw;
        b.speed = v.speed;
        b.motionY = v.motionY;
        b.bank = v.linerBank;
        b.ground = v.onGround;
        boolean remote = v.worldObj != null && v.worldObj.isRemote;
        b.ra = !remote && L.lastAir != null ? L.lastAir.ra : Math.max(0, v.anim.height);
        if (L.afs.lat == com.sva.vehicle.LinerAfs.L_NAV && L.afs.apOn()) {
            RouteMath.Guide r = v.routeGuide();
            b.routeHdg = r == null ? Double.NaN : r.heading;
        }
        return b;
    }
}
