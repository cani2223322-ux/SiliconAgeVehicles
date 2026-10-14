package com.sva.entity;

import com.sva.ConfigSVA;
import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.FuelMath;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerGuide;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Liner wave 1 (plan-next §7): the SA-320's server side next to EntityVehicleSVA (like SpaceLogicSVA for stage E) - one
 * tick of its systems (LinerSystems) in place of the D2 engine machine and the hydrogen wave's electrics, the cockpit's
 * control packets (validated: only the two pilots), the quick start, the L1 door. The vehicle's old words follow the
 * liner's state, so everything that reads them keeps working: the D2 engine state (RUN while either engine runs - the
 * HUD, the sounds, hasLift), the switch word (the lights, the INS), the board battery word (powered()).
 * <p>
 * Wave 2: the pilot's keys become the stick (A / D roll and pedals, Space / «Снижение» pitch) and the brake pedals (S at
 * idle on the ground) for the hydraulics / flight controls / gear (LinerHyd, LinerFctl, LinerGear); the ground's grip and
 * the height; the PTU's bark, the RAT, the gear and a melted fuse plug as sounds; «Гидрожидкость» on the aircraft (refill).
 */
public final class LinerLogicSVA {

    /** The asymmetric thrust's yaw (deg a tick at one engine full, the other dead): in the air / rolling on the ground. */
    public static final double ASYM_AIR = 0.35, ASYM_GROUND = 0.15;
    /** The parking brake's deceleration on the ground (b/t a tick). */
    public static final double PARK_BRAKE = 0.06;
    /** The red chime repeats this often while MASTER WARN is lit (the A320's continuous repetitive chime). */
    private static final int WARN_REPEAT = 10;
    /** The fuel imbalance's yaw (deg a tick at a full LinerFuel.ROLL_FULL imbalance, in the air): the heavy wing drops. */
    public static final double IMBAL_YAW = 0.08;

    private LinerLogicSVA() {
    }

    /**
     * Server, once a tick from systemsTick (before the physics): the environment, the systems' tick, their outputs - АКБ 1's
     * charge, the H2 burnt, the old engine / switch / battery words, the sounds. @return EU for the tick's cost (0: the
     * liner's energy is its batteries, handled here)
     */
    static double tick(EntityVehicleSVA v, boolean pilot, int in) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return 0;
        }
        LinerSystems.Env e = new LinerSystems.Env();
        e.bat1In = v.state.batIn;
        e.bat1 = v.state.batIn ? v.state.energy : 0;
        e.bat1Cap = v.state.maxEnergy();
        e.extAvail = v.extPowerNow();
        e.ground = !v.wasAirborneNow();
        e.ground = e.ground && !v.debugLinerAir;
        // wave 4: the thrust levers are the AFS' (detents, the reverse zone): a throttle set from outside (an older test, a
        // reload) moves them; the pilot's W / S step them; the old throttle follows them
        LinerAfs F = L.afs;
        if (Double.isNaN(v.linerThrSet) || Math.abs(v.throttle - v.linerThrSet) > 1e-9) {
            F.lever = Math.max(0, Math.min(1, v.throttle));
        }
        boolean keyUp = (in & EntityVehicleSVA.IN_FWD) != 0, keyDown = (in & EntityVehicleSVA.IN_BACK) != 0;
        boolean keyRev = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        if (pilot) {
            F.leverKeys(keyUp, keyDown, keyRev && e.ground, e.ground);
        } else if (!e.ground) {
            F.rev = 0;
        }
        v.throttle = F.lever;
        v.linerThrSet = F.lever;
        e.throttle = v.throttle;
        e.airspeed = v.airspeedNow();
        e.random = v.rand().nextDouble();
        e.failChance = Math.max(0, Math.min(0.5, ConfigSVA.linerStartFailChance));
        e.quickAllowed = ConfigSVA.linerQuickStart;
        // wave 2: the stick, the pedals, the ground speed, the grip, the height, the leak chance
        boolean left = (in & EntityVehicleSVA.IN_LEFT) != 0, right = (in & EntityVehicleSVA.IN_RIGHT) != 0;
        boolean up = (in & EntityVehicleSVA.IN_BRAKE) != 0, down = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        e.roll = e.yaw = (right ? 1F : 0F) - (left ? 1F : 0F);
        e.pitch = (up ? 1F : 0F) - (down ? 1F : 0F);
        e.pedal = e.ground && (in & EntityVehicleSVA.IN_BACK) != 0 && v.throttle <= 0 && F.rev <= 0;
        e.speed = Math.max(0, v.speed);
        e.mu = v.linerMu();
        e.height = v.debugLinerAir ? 100 : v.linerRadio();          // wave 4: the radio altimeter every tick
        e.leakChance = Math.max(0, ConfigSVA.linerHydLeakChance);
        // wave 3: the altitude (the gravity fuel feed's ceiling), the LDG MEMO's height
        e.alt = v.posY;
        e.gravAlt = ConfigSVA.linerFuelGravityAlt;
        e.ldgMemoH = ConfigSVA.linerLdgMemoHeight;
        boolean instant = EntityVehicleSVA.testInstantEngine && !v.debugEngineManual;
        if (instant && pilot) {
            // the older world tests fly every aircraft at once: the liner's batteries and masters on, the engines at idle
            L.set(LinerControls.BAT1, 1);
            L.set(LinerControls.BAT2, 1);
            L.set(LinerControls.ENG_MASTER1, 1);
            L.set(LinerControls.ENG_MASTER2, 1);
            L.set(LinerControls.PARK_BRK, 0);
            e.instant = true;
            e.instantHyd = !v.debugLinerAir;                        // wave 2's gear test wants the real timing
        }
        air(v, L, e, in);                                           // wave 4: the aircraft for the AFS
        // the tanks against the vehicle's H2 (a station / a cell / the column filled it)
        L.reconcile(h2(v), FuelMath.tank(VehicleType.LINER));
        int quickBefore = L.quick;
        L.tick(e);
        // АКБ 1: the vehicle's own battery (its charge is state.energy on hydrogen)
        if (v.state.batIn) {
            double before = v.state.energy;
            v.state.energy = Math.max(0, Math.min(v.state.maxEnergy(), v.state.energy - L.bat1Eu));
            v.batteryWear(before, v.state.energy);
        }
        // the H2 the engines and the APU burnt (the tanks already took it; the vehicle's gas follows)
        double burn = (L.burnEng + L.burnApu) * Math.max(0, ConfigSVA.consumption);
        if (burn > 0) {
            v.state.drainGasFraction(FuelMath.H2, burn);
        }
        v.linerFlow(burn);
        // the old words
        v.linerEngine(L.anyRunning());
        v.state.switches = L.switchWord();
        v.state.battery = L.powered() ? SystemsMath.BAT_MAX : 0;
        // the L1 door shuts by itself once it rolls fast or flies
        if (L.door && (v.wasAirborneNow() || Math.abs(v.speed) > 0.3)) {
            L.door = false;
            v.oneShot("door", 0.5F, 0.8F);
        }
        // sounds of this tick (wave 3, vanilla): MASTER WARN - the continuous repetitive chime until pressed (or EMER CANC);
        // MASTER CAUT - a single chime
        if ((L.events & LinerSystems.E_CHIME_WARN) != 0 || L.masterWarn && !L.ecam.silenced && v.ticksExisted % WARN_REPEAT == 0) {
            v.vanillaSound("note.pling", 0.7F, 1.6F);
            v.vanillaSound("note.pling", 0.5F, 1.25F);
        }
        if ((L.events & LinerSystems.E_CHIME_CAUT) != 0) {
            v.vanillaSound("note.harp", 0.8F, 0.9F);
        }
        if ((L.events & LinerSystems.E_APU_AVAIL) != 0) {
            v.oneShot("ding", 0.3F, 1.6F);
        }
        if ((L.events & LinerSystems.E_STARTER) != 0) {
            v.vanillaClick(0.5F, 1.3F);
        }
        if ((L.events & LinerSystems.E_LIGHT_OFF) != 0) {
            v.vanillaGhast(0.35F, 0.8F);
        }
        if ((L.events & LinerSystems.E_CLICK) != 0) {
            v.vanillaClick(0.3F, 1.5F);
        }
        // wave 2: the PTU's bark (single-engine taxi), the RAT dropping out, the gear's thump, a fuse plug blowing
        if ((L.events & LinerSystems.E_PTU) != 0 && ConfigSVA.linerPtuSound) {
            v.vanillaSound("mob.wolf.bark", 0.18F, 0.55F);
        }
        if ((L.events & LinerSystems.E_RAT) != 0) {
            v.vanillaSound("random.door_open", 0.7F, 0.6F);
            v.tellRidersKey("sva.liner.msg.ratOut");
        }
        if ((L.events & LinerSystems.E_GEAR) != 0) {
            v.oneShot("door", 0.4F, 0.6F);
        }
        if ((L.events & LinerSystems.E_FUSE) != 0) {
            v.vanillaSound("random.fizz", 0.8F, 0.7F);
            v.tellRidersKey("sva.liner.msg.fusePlug");
        }
        // wave 4: the AP's cavalry charge (a rising arpeggio, repeating), the callouts (the heli's radar-altimeter tones),
        // RETARD (two low notes), V1
        if (F.cavNote >= 0) {
            int k = F.cavNote;
            if (k == 0 || k == 3 || k == 6 || k == 9) {
                float[] pitch = {1.0F, 1.26F, 1.5F, 2.0F};
                v.vanillaSound("note.harp", 0.9F, pitch[k / 3]);
            }
        }
        if (F.soundCall != 0) {
            int c = F.soundCall;
            if (c == LinerAfs.CALL_RETARD) {
                v.vanillaSound("note.bass", 1.0F, 0.6F);
                v.vanillaSound("note.bass", 1.0F, 0.5F);
            } else if (c == LinerAfs.CALL_V1) {
                v.vanillaSound("note.pling", 0.6F, 1.0F);
            } else {
                v.vanillaSound("note.pling", 0.45F, (float) (0.8F + (100 - Math.min(100, c)) / 100.0));
            }
        }
        if (quickBefore != LinerSystems.QUICK_OFF && L.quick == LinerSystems.QUICK_OFF) {
            v.tellRidersKey("done".equals(L.quickWhy) ? "sva.liner.quick.done" : "sva.liner.quick.stopped." + L.quickWhy);
        }
        return 0;
    }

    /**
     * Wave 4: the aircraft the AFS sees this tick - position, compass heading, the measured vertical speed, the flight
     * parameters of the configuration (stall, lift-off, drag, thrust, climb / dive), the gross mass (the SD's GW), the INS,
     * a stick key held (the AP override), the route's guidance (NAV), the ILS stations near (the world data) and the configs.
     */
    static void air(EntityVehicleSVA v, LinerSystems L, LinerSystems.Env e, int in) {
        LinerAfs.Air a = e.air;
        VehicleType t = VehicleType.LINER;
        a.x = v.posX;
        a.y = v.posY;
        a.z = v.posZ;
        a.hdg = LinerGuide.compass(v.rotationYaw);
        double dy = Double.isNaN(v.linerLastY) ? 0 : v.posY - v.linerLastY;
        if (Math.abs(dy) > 4) {
            dy = 0;                                                 // a teleport
        }
        v.linerVy = v.linerVy * 0.5 + dy * 0.5;
        v.linerLastY = v.posY;
        a.vy = v.linerVy;
        PlaneParams p = v.flightParams(t);
        a.stall = p.stall;
        a.liftOff = p.liftOff;
        a.drag = p.drag;
        a.thrustK = p.thrust;
        a.top = t.topSpeed;
        double sp = Math.max(e.airspeed, e.speed);
        a.cap = PlaneMath.climbCapacity(p, sp) * FlightMath.ceilingFactor(v.posY);
        a.dive = p.dive;
        a.mass = LinerAfs.MASS0 + L.tanks() / 48000.0 * 2600 + v.ridersNow() * 80;
        a.insReady = v.insReady();
        a.stick = !e.ground && (in & (EntityVehicleSVA.IN_LEFT | EntityVehicleSVA.IN_RIGHT | EntityVehicleSVA.IN_BRAKE | EntityVehicleSVA.IN_DOWN
                | EntityVehicleSVA.IN_BOOST)) != 0;
        RouteMath.Plan plan = v.plan();
        a.hasRoute = plan != null && !plan.isEmpty() && !plan.done();
        a.routeHdg = Double.NaN;
        a.routeAlt = Double.NaN;
        if (a.hasRoute && (L.afs.lat == LinerAfs.L_NAV || (L.afs.latArm & LinerAfs.AL_NAV) != 0 || L.afs.hdgMan)) {
            RouteMath.Guide g = v.routeGuide();
            if (g != null) {
                a.routeHdg = g.heading;
                a.routeAlt = g.alt;
                if (g.switched) {
                    v.syncNow();
                }
            }
        }
        a.ilsRange = Math.max(500, ConfigSVA.linerIlsRange);
        a.accelH = ConfigSVA.linerAccelHeight;
        a.callouts = ConfigSVA.linerCallouts;
        if (v.ticksExisted - v.linerIlsAt >= 20 || v.ticksExisted < v.linerIlsAt || v.linerIls == null) {
            v.linerIlsAt = v.ticksExisted;
            com.sva.block.IlsDataSVA d = com.sva.block.IlsDataSVA.get(v.worldObj);
            v.linerIls = d == null ? new java.util.ArrayList<LinerIls.Station>() : d.near(v.worldObj.provider.dimensionId, v.posX, v.posZ, a.ilsRange * 1.2);
        }
        a.ils = v.linerIls;
    }

    /** The vehicle's H2 now (mB, the spent part below one mB taken off). */
    static double h2(EntityVehicleSVA v) {
        int g = v.state.gas(FuelMath.H2);
        return Math.max(0, g - v.state.gasFrac[FuelMath.H2.ordinal()]);
    }

    /** May this player work the cockpit's controls: the captain (the driver) or the first officer (seat 1). */
    public static boolean pilot(EntityVehicleSVA v, EntityPlayer p) {
        if (p == null) {
            return false;
        }
        if (v.riddenByEntity == p) {
            return true;
        }
        Entity m = p.ridingEntity;
        return m instanceof EntitySeatSVA && ((EntitySeatSVA) m).getParent() == v && ((EntitySeatSVA) m).getIndex() == 1;
    }

    /** The control packet (server): a pilot's click on a row of LinerControls. */
    public static void control(EntityVehicleSVA v, EntityPlayerMP p, int index, int action) {
        LinerSystems L = v.state.liner();
        if (L == null || LinerControls.get(index) == null) {
            return;
        }
        if (!pilot(v, p)) {
            v.lockedMsgTo(p);
            return;
        }
        if (LinerControls.get(index) == LinerControls.QUICK && !ConfigSVA.linerQuickStart) {
            v.msgTo(p, "sva.liner.quick.stopped.disabled");
            return;
        }
        boolean was = L.quick != LinerSystems.QUICK_OFF;
        if (L.control(index, action)) {
            v.vanillaClick(0.3F, LinerControls.get(index).kind == LinerControls.PBM ? 1.8F : 1.5F);
            applyLevers(v, L);
            if (!was && L.quick != LinerSystems.QUICK_OFF) {
                v.msgTo(p, "sva.liner.quick.started");
            } else if (LinerControls.get(index) == LinerControls.QUICK && L.quick == LinerSystems.QUICK_OFF && L.quickWhy.length() > 0) {
                v.msgTo(p, "sva.liner.quick.stopped." + L.quickWhy);
            }
            v.syncNow();
        }
    }

    /**
     * The old high-lift state from the liner's: the flaps where the hydraulics have moved them (wave 2), the speed brake's
     * lever at 1/2 or ПОЛН with its spoilers powered.
     */
    static void applyLevers(EntityVehicleSVA v, LinerSystems L) {
        v.linerHighLift(L.fctl.flapSetting(), L.at(LinerControls.SPD_BRK) >= 2 && L.fctl.speedBrakeWorks());
    }

    /**
     * Wave 2: «Гидрожидкость» used on the liner (server) - all three reservoirs full, the leaks stopped; one item used
     * (creative: free). Not a liner / nothing to fill: a line in the chat.
     */
    static void refill(EntityVehicleSVA v, EntityPlayer p, net.minecraft.item.ItemStack held) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            v.msgTo(p, "sva.liner.msg.fluid.notLiner");
            return;
        }
        if (!L.hyd.needsFluid()) {
            v.msgTo(p, "sva.liner.msg.fluid.full");
            return;
        }
        L.hyd.refill();
        if (!p.capabilities.isCreativeMode) {
            held.stackSize--;
            if (held.stackSize <= 0) {
                p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
            }
        }
        v.vanillaSound("liquid.water", 0.5F, 1.4F);
        v.msgTo(p, "sva.liner.msg.fluid.done");
        v.syncNow();
    }

    /** «Быстрый запуск» from the menu / the key (the pilot). */
    static void quick(EntityVehicleSVA v, EntityPlayer p) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return;
        }
        if (!ConfigSVA.linerQuickStart) {
            v.msgTo(p, "sva.liner.quick.stopped.disabled");
            return;
        }
        if (L.quick != LinerSystems.QUICK_OFF) {
            L.stopQuick("pilot");
            v.msgTo(p, "sva.liner.quick.stopped.pilot");
        } else if (L.startQuick()) {
            v.msgTo(p, "sva.liner.quick.started");
        } else {
            v.msgTo(p, "sva.liner.quick.stopped." + L.quickWhy);
        }
        v.syncNow();
    }

    /** The L1 door from the menu (open / shut; not in the air). */
    static void door(EntityVehicleSVA v, EntityPlayer p) {
        LinerSystems L = v.state.liner();
        if (L == null) {
            return;
        }
        if (!L.door && v.wasAirborneNow()) {
            v.msgTo(p, "sva.liner.door.air");
            return;
        }
        L.door = !L.door;
        v.oneShot("door", 0.6F, L.door ? 1.0F : 0.8F);
        v.syncNow();
    }

    /** АКБ 2 as an item (its charge, health...), null with none in. */
    static net.minecraft.item.ItemStack battery2Stack(EntityVehicleSVA v) {
        LinerSystems L = v.state.liner();
        if (L == null || !L.bat2In || com.sva.init.ModContentSVA.battery == null) {
            return null;
        }
        BatteryMath.Battery b = new BatteryMath.Battery();
        b.charge = L.bat2.charge;
        b.health = L.bat2.health;
        b.limit = L.bat2.limit;
        b.services = L.bat2.services;
        b.cycle = L.bat2.cycle;
        b.deep = L.bat2.deep;
        return com.sva.item.ItemBatterySVA.stackOf(com.sva.init.ModContentSVA.battery, VehicleType.LINER, b);
    }

    /** Takes АКБ 2 out (null: none in). */
    static net.minecraft.item.ItemStack takeBattery2(EntityVehicleSVA v) {
        net.minecraft.item.ItemStack s = battery2Stack(v);
        if (s != null) {
            LinerSystems L = v.state.liner();
            L.bat2In = false;
            L.bat2.charge = 0;
            v.syncNow();
        }
        return s;
    }

    /** Puts a liner battery into АКБ 2 (false: not a liner battery, one is in). */
    static boolean putBattery2(EntityVehicleSVA v, net.minecraft.item.ItemStack s) {
        LinerSystems L = v.state.liner();
        if (L == null || L.bat2In || !com.sva.item.ItemBatterySVA.fits(s, VehicleType.LINER)) {
            return false;
        }
        BatteryMath.Battery b = com.sva.item.ItemBatterySVA.batteryOf(s);
        L.bat2In = true;
        L.bat2.charge = b.charge;
        L.bat2.health = b.health;
        L.bat2.limit = b.limit;
        L.bat2.services = b.services;
        L.bat2.cycle = b.cycle;
        L.bat2.deep = b.deep;
        v.syncNow();
        return true;
    }

    /** Where a rider gets off: at the foot of the L1 airstairs (model x 5.30, 2.9 left of the centre line), else null. */
    static double[] doorSpot(EntityVehicleSVA v) {
        double[] w = v.toWorld(DOOR_X, -2.9);
        return new double[] {v.posX + w[0], v.posY, v.posZ + w[1]};
    }

    /** The L1 door's centre (model x, liner3d.py moved aft for the cabin: x 5.00..5.60) and how near a boarding player must stand. */
    public static final double DOOR_X = 5.30, BOARD_RANGE = 4.5;

    /** Is p standing by the L1 door (its side, within BOARD_RANGE of the stairs' foot or the door)? */
    static boolean byDoor(EntityVehicleSVA v, EntityPlayer p) {
        double[] f = doorSpot(v);
        double[] d = v.toWorld(DOOR_X, -1.2);
        double dx = p.posX - f[0], dz = p.posZ - f[2];
        double ex = p.posX - (v.posX + d[0]), ez = p.posZ - (v.posZ + d[1]);
        return dx * dx + dz * dz < BOARD_RANGE * BOARD_RANGE || ex * ex + ez * ez < BOARD_RANGE * BOARD_RANGE;
    }
}
