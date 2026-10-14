package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HighLiftMath;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerFlight;
import com.sva.vehicle.LinerGuide;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.VehicleType;

/**
 * Liner wave 4: a pure flight simulation of the SA-320 for the self-test (SelfTestLinerAfsSVA) - the real LinerSystems
 * (engines, hydraulics, flight controls, the AFS) ticked with the real flight model (LinerFlight - the same step the entity
 * runs) over a flat ground at GROUND_Y, in still air, without the world: the AP's convergence, the captures, the ILS
 * tracking, the flare and the roll-out are measured on the very code the game flies.
 */
final class LinerSimSVA {

    static final double GROUND_Y = 64;

    final LinerSystems L;
    final LinerSystems.Env e;
    final LinerFlight.Body b = new LinerFlight.Body();
    final LinerFlight.Keys k = new LinerFlight.Keys();
    final List<LinerIls.Station> ils = new ArrayList<LinerIls.Station>();
    /** The last touch-down's sink rate (b/t), the tick it happened (-1 none); the ticks flown. */
    double touchSink = -1;
    int touchAt = -1, ticks;
    boolean insReady = true;
    double mass = 17500;

    LinerSimSVA() {
        L = new LinerSystems();
        L.tankL = L.tankR = 16800;
        L.tankC = 14400;
        L.bat2.charge = BatteryMath.capacity(VehicleType.LINER);
        e = new LinerSystems.Env();
        e.bat1In = true;
        e.bat1Cap = BatteryMath.capacity(VehicleType.LINER);
        e.bat1 = e.bat1Cap;
        e.random = 0.9;
        e.failChance = 0;
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.ENG_MASTER1, 1);
        L.set(LinerControls.ENG_MASTER2, 1);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        L.set(LinerControls.PARK_BRK, 0);
        e.instant = true;
        e.instantHyd = true;
        b.y = GROUND_Y;
        b.ground = true;
        b.yaw = -90;                                                // facing east (+x): compass 90
        systems();
        systems();
        e.instant = false;
        e.instantHyd = false;
        for (int i = 0; i < 40; i++) {
            systems();
        }
    }

    /** In the air at height h over the ground, at speed (b/t), facing compass `hdg`, flaps, gear up, levers at CL with the A/THR. */
    static LinerSimSVA flying(double h, double speed, double hdg, int flaps) {
        LinerSimSVA s = new LinerSimSVA();
        s.b.y = GROUND_Y + h;
        s.b.ground = false;
        s.b.speed = speed;
        s.b.yaw = hdg - 180;
        s.L.set(LinerControls.GEAR, 0);
        s.L.gear.pos = 1;
        s.setFlaps(flaps);
        s.L.afs.lever = LinerAfs.DET_CL;
        s.L.afs.athr = true;
        s.L.afs.alt = (int) Math.round(s.b.y);
        // a tick to settle the AFS' aircraft
        s.tick();
        return s;
    }

    void setFlaps(int f) {
        L.set(LinerControls.FLAPS, f);
        L.fctl.flapPos = f;
        L.fctl.slatPos = f >= 1 ? 1 : 0;
    }

    void gearDown() {
        L.set(LinerControls.GEAR, 1);
        L.gear.pos = 0;
    }

    PlaneParams params() {
        HighLiftMath.Spec s = HighLiftMath.LINER;
        boolean brake = L.at(LinerControls.SPD_BRK) >= 2 && L.fctl.speedBrakeWorks();
        return HighLiftMath.params(s, VehicleType.LINER.plane, L.fctl.flapSetting(), L.fctl.slatsOut(), brake && !b.ground,
                b.ground && (brake || L.fctl.groundSpoilersWork()));
    }

    void systems() {
        e.ground = b.ground;
        e.airspeed = b.speed;
        e.speed = b.speed;
        e.alt = b.y;
        e.height = Math.max(0, b.y - GROUND_Y);
        e.throttle = L.afs.lever;
        e.pedal = b.ground && k.back && L.afs.lever <= 0;
        LinerAfs.Air a = e.air;
        PlaneParams p = params();
        a.x = b.x;
        a.y = b.y;
        a.z = b.z;
        a.hdg = LinerGuide.compass(b.yaw);
        a.vy = b.motionY;
        a.stall = p.stall;
        a.liftOff = p.liftOff;
        a.drag = p.drag;
        a.thrustK = p.thrust;
        a.top = VehicleType.LINER.topSpeed;
        a.cap = PlaneMath.climbCapacity(p, b.speed) * FlightMath.ceilingFactor(b.y);
        a.dive = p.dive;
        a.mass = mass;
        a.insReady = insReady;
        a.stick = !b.ground && (k.left || k.right || k.up || k.down);
        a.ils = ils;
        a.ilsRange = 4000;
        a.accelH = 30;
        a.callouts = true;
        a.hasRoute = !Double.isNaN(b.routeHdg);
        a.routeHdg = b.routeHdg;
        L.tick(e);
        e.bat1 = Math.max(0, Math.min(e.bat1Cap, e.bat1 - L.bat1Eu));
    }

    /** One tick: the systems (with the AFS), then the flight model; the touch-down's sink rate kept. */
    void tick() {
        systems();
        b.ra = Math.max(0, b.y - GROUND_Y);
        PlaneParams p = params();
        double vy = LinerFlight.step(b, VehicleType.LINER, p, L, k, L.anyRunning(), true, 1);
        double r = Math.toRadians(b.yaw);
        b.x += -Math.sin(r) * b.speed;
        b.z += Math.cos(r) * b.speed;
        if (b.y + vy <= GROUND_Y) {
            if (!b.ground) {
                touchSink = -vy;
                touchAt = ticks;
            }
            b.y = GROUND_Y;
            b.motionY = 0;
            b.ground = true;
        } else {
            b.y += vy;
            b.motionY = vy;
            b.ground = false;
        }
        ticks++;
    }

    void run(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    double hdg() {
        return LinerGuide.compass(b.yaw);
    }

    /** A pilot's click on a row (LMB / RMB / wheel ...). */
    void click(LinerControls.Ctl c, int action) {
        L.control(c.index, action);
    }

    void click(LinerControls.Ctl c) {
        L.control(c.index, LinerControls.A_PRIMARY);
    }

    /** An ILS station: threshold block at (x, z), surface Y elev, course, angle. */
    LinerIls.Station station(int x, int z, double course, String ident) {
        LinerIls.Station s = new LinerIls.Station();
        s.bx = x;
        s.by = (int) GROUND_Y - 1;
        s.bz = z;
        s.elev = GROUND_Y;
        s.course = course;
        s.angle = 3;
        s.ident = ident;
        s.freq = 11030;
        ils.add(s);
        return s;
    }
}
