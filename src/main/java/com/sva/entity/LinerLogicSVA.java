package com.sva.entity;

import com.sva.ConfigSVA;
import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.FuelMath;
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
        e.throttle = v.throttle;
        e.ground = !v.wasAirborneNow();
        e.airspeed = v.airspeedNow();
        e.random = v.rand().nextDouble();
        e.failChance = Math.max(0, Math.min(0.5, ConfigSVA.linerStartFailChance));
        e.quickAllowed = ConfigSVA.linerQuickStart;
        // wave 2: the stick, the pedals, the ground speed, the grip, the height, the leak chance
        boolean left = (in & EntityVehicleSVA.IN_LEFT) != 0, right = (in & EntityVehicleSVA.IN_RIGHT) != 0;
        boolean up = (in & EntityVehicleSVA.IN_BRAKE) != 0, down = (in & (EntityVehicleSVA.IN_DOWN | EntityVehicleSVA.IN_BOOST)) != 0;
        e.roll = e.yaw = (right ? 1F : 0F) - (left ? 1F : 0F);
        e.pitch = (up ? 1F : 0F) - (down ? 1F : 0F);
        e.ground = e.ground && !v.debugLinerAir;
        e.pedal = e.ground && (in & EntityVehicleSVA.IN_BACK) != 0 && v.throttle <= 0;
        e.speed = Math.max(0, v.speed);
        e.mu = v.linerMu();
        e.height = v.debugLinerAir ? 100 : v.linerHeight();
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
        if (quickBefore != LinerSystems.QUICK_OFF && L.quick == LinerSystems.QUICK_OFF) {
            v.tellRidersKey("done".equals(L.quickWhy) ? "sva.liner.quick.done" : "sva.liner.quick.stopped." + L.quickWhy);
        }
        return 0;
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
