package com.sva.vehicle;

/**
 * Liner wave 4 (plan-next §7): the SA-320's flight physics as pure code - PlanePhysicsSVA's model (the same thrust / drag
 * / climb terms, the same take-off, glide and stall) with the airliner's own parts: the BANK (LinerGuide: the stick
 * commands a roll rate, the normal law holds the bank and protects it at 33° / 67°, the heading turns by tan(bank)), the
 * autopilot flying through the very same stick (LinerAfs.guide: a roll stick and a pitch stick - the pitch stick asks for a
 * vertical speed between the dive and the climb capacity exactly as the keys do at -1 / +1), the normal law's overspeed
 * protection (a nose-up bias over VMO), the reversers' thrust on the ground and the roll-out's nose-wheel steering.
 * entity.LinerPhysicsSVA runs it for the entity (the driving client and the server alike, from the synced systems) and
 * moves it; the self-test (debug.SelfTestLinerAfsSVA) flies it in a box of air over a flat ground - the same numbers.
 * <p>
 * The wave 1-3 behaviour kept: the levers are the engines' thrust (LinerSystems.thrust), the hydraulic brakes / autobrake
 * / parking brake / flat tyres (LinerGear), the nose-wheel steering, the asymmetric thrust's yaw, the fuel imbalance's
 * drift (by hand only), the elevators' authority on the pitch.
 */
public final class LinerFlight {

    private LinerFlight() {
    }

    /** The aircraft's state the step reads and writes (yaw: Minecraft's rotationYaw). */
    public static final class Body {
        public double x, y, z, yaw, speed, motionY, bank, ra = 250;
        public boolean ground;
        /** The route's wanted heading this tick (NAV; NaN none). */
        public double routeHdg = Double.NaN;
    }

    /** The keys (the pilot's; an engaged AP replaces the stick ones). */
    public static final class Keys {
        public boolean fwd, back, left, right, up, down;
    }

    /**
     * One tick: the new speed, bank and yaw in `b`; @return the vertical speed for the move. `powered` the engines can push
     * (PlanePhysicsSVA's hasLift and canThrust), `runway` on runway blocks, `mu` the ground's grip.
     */
    public static double step(Body b, VehicleType t, PlaneParams p, LinerSystems L, Keys k, boolean powered, boolean runway, double mu) {
        double th = L.thrust();
        double speed = Math.max(0, b.speed);
        LinerAfs F = L.afs;
        boolean ap = F.apOn();
        LinerAfs.Cmd cmd = ap ? F.guide(guideIn(b, t, L, p, speed)) : null;
        int steer = (k.right ? 1 : 0) - (k.left ? 1 : 0);
        double vy;
        if (!b.ground) {
            boolean protect = L.fctl.law == LinerFctl.NORMAL;
            double cap = PlaneMath.climbCapacity(p, speed) * FlightMath.ceilingFactor(b.y);
            double pitchStick = ap && !Double.isNaN(cmd.pitch) ? cmd.pitch : (k.up ? 1 : 0) - (k.down ? 1 : 0);
            if (PlaneMath.stalled(p, speed) || !powered) {
                vy = PlaneMath.airVy(p, speed, b.motionY, pitchStick > 0.5, pitchStick < -0.5, powered, b.y);
            } else {
                double target = b.y > FlightMath.CEILING && pitchStick >= 0 ? -0.05 : LinerGuide.stickVy(pitchStick, cap, p.dive);
                if (protect && speed > LinerAfs.VMO) {
                    target += (speed - LinerAfs.VMO) * LinerGuide.OVERSPEED_PITCH;   // the normal law's overspeed protection
                }
                vy = FlightMath.approach(b.motionY, target, PlaneMath.VACC);
                if (pitchStick != 0) {
                    // wave 2: the elevators / THS that work give the stick its share (all lost - the trim wheel's)
                    double level = FlightMath.approach(b.motionY, b.y > FlightMath.CEILING ? -0.05 : 0, PlaneMath.VACC);
                    vy = level + (vy - level) * Math.min(1, L.fctl.pitchAuthority() * L.fctl.lawGain());
                }
            }
            speed += PlaneMath.airAccel(t, p, speed, th, vy, vy > 0, powered, 1);
            // the bank: the stick's roll rate (the AP's or the keys'), the protections; the turn from it
            double rollStick = ap && !Double.isNaN(cmd.roll) ? cmd.roll : steer;
            double auth = L.fctl.rollAuthority() * L.fctl.lawGain();
            b.bank = LinerGuide.bankStep(b.bank, rollStick, auth, protect);
            b.yaw += LinerGuide.turnRate(b.bank, speed, p.stall, t.turnRate);
            if (!ap) {
                b.yaw += L.fuelRoll() * IMBAL_YAW;                  // wave 3: uneven wing tanks - a slow turn toward the heavy one
            }
        } else {
            b.bank = 0;
            speed += PlaneMath.accel(t, p, speed, th, false, runway, 1);
            double rev = L.revThrust();
            if (rev > 0 && speed > 0) {
                speed -= p.thrust * rev * (runway ? 1 : PlaneMath.GROUND_EFF);   // wave 4: the reversers
            }
            LinerGear g = L.gear;
            double lever = F.lever;
            if (lever <= 0 && speed > 0) {
                speed -= PlaneMath.ROLL * p.brake * (g.rollingMul() - 1);
            }
            speed -= g.decel(k.back && lever <= 0 && F.rev <= 0, speed, mu, L.on(LinerControls.PARK_BRK));
            speed -= g.diffDrag(steer);
            speed = Math.max(0, speed);
            vy = k.up && powered && speed >= p.liftOff ? p.rotateVy : -0.08;
            double steerCmd = steer;
            if (F.lat == LinerAfs.L_ROLLOUT && ap) {
                LinerAfs.Cmd c = F.guide(guideIn(b, t, L, p, speed));
                if (!Double.isNaN(c.steer)) {
                    steerCmd = c.steer;
                }
            }
            b.yaw += g.turnRate(steerCmd, speed, t.turnRate);
        }
        speed = VehicleMath.clamp(speed, 0, PlaneMath.maxSpeed(t, 1));
        if (speed > 0.02) {
            b.yaw += L.asymYaw(!b.ground ? ASYM_AIR : ASYM_GROUND * Math.min(1, speed / 0.3));
        }
        b.speed = speed;
        return vy;
    }

    /** LinerLogicSVA's numbers (the asymmetric thrust's yaw in the air / on the ground, the imbalance's). */
    public static final double ASYM_AIR = 0.35, ASYM_GROUND = 0.15, IMBAL_YAW = 0.08;

    /** The guidance's inputs of this body (the physics; the PFD's FD bars use the same). */
    public static LinerAfs.GIn guideIn(Body b, VehicleType t, LinerSystems L, PlaneParams p, double speed) {
        LinerAfs.GIn g = new LinerAfs.GIn();
        g.x = b.x;
        g.y = b.y;
        g.z = b.z;
        g.hdg = LinerGuide.compass(b.yaw);
        g.speed = speed;
        g.gs = speed;
        g.vy = b.motionY;
        g.bank = b.bank;
        g.ra = b.ra;
        g.stall = p.stall;
        g.cap = PlaneMath.climbCapacity(p, speed) * FlightMath.ceilingFactor(b.y);
        g.dive = p.dive;
        g.thrAcc = LinerGuide.thrustAcc(p.thrust, L.thrust(), p.drag, speed, t.topSpeed);
        g.ground = b.ground;
        g.routeHdg = b.routeHdg;
        return g;
    }
}
