package com.sva.entity;

import java.util.ArrayList;
import java.util.List;

import com.sva.ConfigSVA;
import com.sva.SVAMod;
import com.sva.init.ModContentSVA;
import com.sva.inventory.GuiHandlerSVA;
import com.sva.net.NetSVA;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.vehicle.AutoDelivery;
import com.sva.vehicle.CrawlerMath;
import com.sva.vehicle.DrillMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HeliMath;
import com.sva.vehicle.HoverMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.SpaceMath;
import com.sva.vehicle.TiltMath;
import com.sva.vehicle.VehicleGases;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidContainerItem;
import net.minecraftforge.fluids.IFluidHandler;

/**
 * A ground vehicle. Driver = riddenByEntity; other seats are EntitySeatSVA kept beside the body.
 *
 * Networking (see NetSVA):
 * - The DRIVER's client simulates the physics (physicsStep) from its own keys and sends Move packets
 *   (position, yaw) every tick it moves; its keys go as Input packets on change + keepalive.
 * - The SERVER accepts a Move only if it is within reach (top speed x 1.3 per elapsed tick + 0.2), not a
 *   teleport, not into blocks and not climbing in the air; else it sends Correct and the client snaps back.
 *   EU / heat / wear are always server-side, from the displacement the server measures.
 * - When no Move came for 10 ticks (no player driver, lag, the world test) the server simulates the same
 *   physicsStep from the last Input itself.
 * - Other clients interpolate the tracker's positions (3 steps) and the yaw from the data watcher.
 */
public class EntityVehicleSVA extends Entity implements IInventory {

    public static final String NBT_ROOT = "SVAVehicle";
    // data watcher ids (append-only)
    private static final int DW_ENERGY = 20, DW_HP = 21, DW_HEAT = 22, DW_FLAGS = 23, DW_CARGO = 24, DW_TYPE = 25,
            DW_CRUISE = 26, DW_YAW = 27, DW_GAS = 28, DW_THR = 29, DW_COST = 30,
            // stage C: packed bits (trailer, drill, auto-delivery - X_*), blocks dug, head life left / max, auto distance, route text
            DW_EXTRA = 31, DW_DUG = 19, DW_HEAD = 18, DW_HEAD_MAX = 17, DW_AUTO_DIST = 16, DW_ROUTE = 15,
            // stage E: the space panel's line (SpaceLogicSVA.hudString)
            DW_SPACE = 14,
            // cargo screen v2: the owner's name («Машина» tab), torches the drill has put down («Бур» tab)
            DW_OWNER = 13, DW_TORCHES = 12;
    private static final int X_TRAILER = 1, X_DRILL = 2, X_MODE_SHIFT = 2, X_WIDE = 16, X_HEAD = 32, X_AUTO_SHIFT = 6, X_AUTO_ON = 512,
            X_REASON_SHIFT = 10, X_ROUTE_A = 1 << 13, X_ROUTE_B = 1 << 14, X_FIELD = 1 << 15,
            // stage D: the tiltrotor's wanted mode (airplane) and its nacelle step (0..40, 6 bits)
            X_TILT_PLANE = 1 << 16, X_TILT_SHIFT = 17,
            // stage E: long-range mode on / working, an open vortex near, held still (anchor / portal / riders boarding),
            // the anchor, invisibility, the jump portal opening
            X_LONG_ON = 1 << 23, X_LONG_ACTIVE = 1 << 24, X_DOCK_NEAR = 1 << 25, X_HELD = 1 << 26, X_ANCHOR = 1 << 27,
            X_INVIS = 1 << 28, X_PORTAL = 1 << 29;
    /** Vehicle actions (the Action packet: cargo screen buttons, the drill keys). */
    public static final int A_DRILL_TOGGLE = 0, A_DRILL_MODE = 1, A_AUTO_TOGGLE = 2, A_TRAILER_DETACH = 3, A_ROUTE_CLEAR = 4,
            /** Stage D: the tiltrotor's «Режим» key - vertical <-> airplane (its driver). */
            A_FLIGHT_MODE = 5,
            /** Stage E (the pilot): «Режим» on the shuttle / ship - long-range mode; «Прыжок»; the jump target; anchor; invisibility. */
            A_LONG_RANGE = 6, A_JUMP = 7, A_JUMP_TARGET = 8, A_ANCHOR = 9, A_INVIS = 10,
            /** The «Багажник» key (L): the cargo / bunker screen from a seat (owner / op only, as Shift+RMB). */
            A_CARGO = 11,
            /** Cargo screen v2 (owner / op): «Сорт.» - merge and sort the cargo; «Мусор» - throw the junk out next to the vehicle. */
            A_SORT = 12, A_JUNK = 13,
            /** Cargo screen v2, «Бур» tab: the mode buttons - A_MODE_SET + DrillMath.MODE_* (14..16). */
            A_MODE_SET = 14;
    private static final int F_WRECKED = 1, F_HEAT_STOP = 2, F_WEAR = 4, F_LIFT = 8, F_BOOST = 16, F_CHARGING = 32,
            /** Aircraft: in the air without power (heli autorotation / plane glide); plane: stalling. */
            F_AUTO = 64, F_STALL = 128;
    /**
     * Input bits. IN_BRAKE = Space (ground: brake; hover: hold higher; heli: collective up; plane: pull up),
     * IN_BOOST = sprint (hover: boost; aircraft: down, like IN_DOWN), IN_DOWN = «Снижение» (heli: collective down; plane: push down),
     * IN_AFTER = «Форсаж» (stage D: the hoverbike's boost; the jet's / tiltrotor's afterburner - no longer «down»).
     */
    public static final int IN_FWD = 1, IN_BACK = 2, IN_LEFT = 4, IN_RIGHT = 8, IN_BRAKE = 16, IN_BOOST = 32, IN_DOWN = 64, IN_AFTER = 128,
            IN_MASK = 255;
    /** The afterburner lights only at this throttle or more. */
    public static final double AFTERBURNER_THROTTLE = 0.9;
    /** Tiltrotor: the server judges moves by the helicopter's rules for this long after the nacelles reach airplane mode. */
    private static final int TILT_GRACE = 20;
    private static final int INPUT_TIMEOUT = 40, MOVE_TIMEOUT = 10, KEEPALIVE = 10;
    static final double BRAKE = 0.05, ROLL = 0.006, REVERSE = 0.35, WATER_SPEED = 0.35;
    public static final double RENDER_DIST = 96;

    public VehicleState state;
    /** Signed speed along the facing, blocks/tick (physics). */
    public double speed;
    private int input;
    private long lastInputTick = -1000, lastMoveTick = -1000, lastCorrectTick = -1000;
    // server measurement
    private double lastX, lastY, lastZ, lastDx, lastDz;
    private boolean hasLast;
    private double speedEma, prevMoved;
    private int hitCooldown;
    private boolean registered;
    /** Server: held up by lift / boosting this tick (synced as flags). */
    private boolean lifting, boosting;
    /** Plane throttle 0..1 (the driver's client and the server integrate it from the same keys). */
    public double throttle;
    // server, aircraft: in the air now / last tick, unpowered in the air, stalling, measured vertical speed, EU this tick
    private boolean wasAirborne, autoNow, stallNow;
    private double lastDy;
    private float costNow;
    // client, aircraft: rotor / propeller angle and spin rate (deg/tick), the body's visual pitch / bank
    public float rotorAngle, prevRotorAngle, renderPitch, prevRenderPitch, renderRoll, prevRenderRoll;
    private float rotorRate;
    // client: the synced gas string parsed
    private String gasSeen;
    private final int[] gasShown = new int[Gas.values().length];
    private EntitySeatSVA[] seats;
    /** Server, tiltrotor: when the nacelles last reached airplane mode. */
    private long tiltDoneTick = -1000;
    private Entity lastRider;
    /** World test: run the motor without a rider. */
    public boolean debugDriver;
    /** Server: ticks left of the «charging» flag (stations refresh it). */
    private int chargeTicks;
    private static final int CHARGE_HOLD = 30;
    // client
    private VehicleType sizedFor;
    private double lx, ly, lz;
    private int lerpSteps;
    private int lastSentInput = -1, inputTimer, moveTimer;
    private double sentX, sentY, sentZ;
    private float sentYaw;
    // stage C, server: the drill (on, dig budget, blocks driven since the last torch), auto-delivery (stuck ticks,
    // transfer timer, why it stopped, distance to the point), throttles for actions and warnings
    public boolean drillOn;
    double digBudget, torchDist;
    int autoBlocked, autoTimer, autoReason, autoDist;
    private long lastActionTick = -100, warnTick = -1000;
    private boolean fieldNow;
    // client: the bore's spin
    public float drillAngle, prevDrillAngle;
    private float drillRate;
    // stage E, server: docking wait, the jump portal (ticks left, where to), an open vortex near, riders breathing the
    // cabin's O2, long-range mode working this tick; riders carried over by a transfer waiting for their seats
    int dockCooldown, portalTicks, breathing, pendingAge;
    SpaceLogicSVA.Target portalTarget;
    boolean dockNear, longNow;
    List<Object[]> pendingRiders;
    final List<Object[]> attachResend = new ArrayList<Object[]>();
    net.minecraft.util.IChatComponent pendingMsg;
    // client: the jump portal's swirl; the ship's singularity rings (deg, deg/tick)
    private int portalFx;
    private float coreAngle, prevCoreAngle, coreRate;
    /** Client: seat entities that found this body (positioned here each tick). */
    final List<EntitySeatSVA> clientSeats = new ArrayList<EntitySeatSVA>();

    public EntityVehicleSVA(World world) {
        super(world);
        state = new VehicleState(VehicleType.BUGGY);
        preventEntitySpawning = true;
        isImmuneToFire = true;
        ignoreFrustumCheck = true;
        renderDistanceWeight = 4.0;
        applySize(VehicleType.BUGGY);
    }

    public EntityVehicleSVA(World world, VehicleType type) {
        this(world);
        state = new VehicleState(type);
        applySize(type);
        dataWatcher.updateObject(DW_TYPE, Byte.valueOf((byte) type.ordinal()));
    }

    private void applySize(VehicleType t) {
        setSize(t.width, t.height);
        stepHeight = t.stepHeight;
        sizedFor = t;
    }

    @Override
    protected void entityInit() {
        dataWatcher.addObject(DW_ENERGY, Integer.valueOf(0));
        dataWatcher.addObject(DW_HP, Float.valueOf(0));
        dataWatcher.addObject(DW_HEAT, Float.valueOf(0));
        dataWatcher.addObject(DW_FLAGS, Byte.valueOf((byte) 0));
        dataWatcher.addObject(DW_CARGO, Byte.valueOf((byte) 0));
        dataWatcher.addObject(DW_TYPE, Byte.valueOf((byte) 0));
        dataWatcher.addObject(DW_CRUISE, Float.valueOf(0));
        dataWatcher.addObject(DW_YAW, Float.valueOf(0));
        dataWatcher.addObject(DW_GAS, "");
        dataWatcher.addObject(DW_THR, Float.valueOf(0));
        dataWatcher.addObject(DW_COST, Float.valueOf(0));
        dataWatcher.addObject(DW_EXTRA, Integer.valueOf(0));
        dataWatcher.addObject(DW_DUG, Integer.valueOf(0));
        dataWatcher.addObject(DW_HEAD, Integer.valueOf(0));
        dataWatcher.addObject(DW_HEAD_MAX, Integer.valueOf(0));
        dataWatcher.addObject(DW_AUTO_DIST, Integer.valueOf(0));
        dataWatcher.addObject(DW_ROUTE, "");
        dataWatcher.addObject(DW_SPACE, "");
        dataWatcher.addObject(DW_OWNER, "");
        dataWatcher.addObject(DW_TORCHES, Integer.valueOf(0));
    }

    // ------------------------------------------------------------------ synced views (both sides)

    public VehicleType getVehicleType() {
        return worldObj != null && worldObj.isRemote ? VehicleType.byOrdinal(dataWatcher.getWatchableObjectByte(DW_TYPE)) : state.type;
    }

    public int getEnergy() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectInt(DW_ENERGY) : (int) state.energy;
    }

    public float getHp() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectFloat(DW_HP) : state.hp;
    }

    public float getHeat() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectFloat(DW_HEAT) : (float) state.heat;
    }

    private boolean flag(int f) {
        return (dataWatcher.getWatchableObjectByte(DW_FLAGS) & f) != 0;
    }

    public boolean isWrecked() {
        return worldObj.isRemote ? flag(F_WRECKED) : ConfigSVA.wear && VehicleMath.wrecked(state.hp);
    }

    public boolean isHeatStopped() {
        return worldObj.isRemote ? flag(F_HEAT_STOP) : state.heatStop;
    }

    public boolean wearOn() {
        return worldObj.isRemote ? flag(F_WEAR) : ConfigSVA.wear;
    }

    public int getCargoUsed() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectByte(DW_CARGO) : state.cargoUsed();
    }

    /** mB of a gas (synced on clients). */
    public int getGas(Gas g) {
        if (g == null) {
            return 0;
        }
        if (worldObj == null || !worldObj.isRemote) {
            return state.gas(g);
        }
        String s = dataWatcher.getWatchableObjectString(DW_GAS);
        if (!s.equals(gasSeen)) {
            gasSeen = s;
            java.util.Arrays.fill(gasShown, 0);
            List<Gas> order = getVehicleType().gases.gases();
            String[] p = s.length() == 0 ? new String[0] : s.split(",");
            for (int i = 0; i < p.length && i < order.size(); i++) {
                try {
                    gasShown[order.get(i).ordinal()] = Integer.parseInt(p[i]);
                } catch (NumberFormatException e) {
                    // a garbled value shows 0
                }
            }
        }
        return gasShown[g.ordinal()];
    }

    // ---- stage C views (clients: synced)

    private int extra() {
        return dataWatcher.getWatchableObjectInt(DW_EXTRA);
    }

    /** A trailer is hitched. */
    public boolean hasTrailer() {
        return worldObj != null && worldObj.isRemote ? (extra() & X_TRAILER) != 0 : state.trailer;
    }

    /** Cargo slots now (a hitched trailer adds 54). */
    public int cargoCapacity() {
        return getVehicleType().cargoSlots(hasTrailer());
    }

    public boolean isDrillOn() {
        return worldObj.isRemote ? (extra() & X_DRILL) != 0 : drillOn;
    }

    public int getDrillMode() {
        return worldObj.isRemote ? (extra() >> X_MODE_SHIFT) & 3 : state.drillMode;
    }

    public boolean hasWidener() {
        return worldObj.isRemote ? (extra() & X_WIDE) != 0 : DrillLogicSVA.wide(state);
    }

    public boolean hasHead() {
        return worldObj.isRemote ? (extra() & X_HEAD) != 0 : DrillLogicSVA.isHead(state.drillHead);
    }

    /** Blocks the head still lasts (-1: for ever) and its whole life (0: Exo). */
    public int getHeadLeft() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectInt(DW_HEAD) : headLeft();
    }

    public int getHeadMax() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectInt(DW_HEAD_MAX) : state.drillHead == null ? 0 : state.drillHead.getMaxDamage();
    }

    private int headLeft() {
        ItemStack h = state.drillHead;
        return h == null ? 0 : DrillMath.lifeLeft(h.getItemDamage(), h.getMaxDamage());
    }

    public int getDug() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectInt(DW_DUG) : (int) Math.min(Integer.MAX_VALUE, state.dug);
    }

    /** The owner's name ("" - nobody's), synced. */
    public String getOwnerName() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectString(DW_OWNER) : state.ownerName == null ? "" : state.ownerName;
    }

    /** Torches the drill has put down, synced. */
    public int getTorches() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectInt(DW_TORCHES) : state.torches;
    }

    /** Riders now (driver + passenger seats; clients: the seats that found this body). */
    public int ridersNow() {
        if (worldObj == null || !worldObj.isRemote) {
            return riders().size();
        }
        int n = riddenByEntity != null ? 1 : 0;
        for (EntitySeatSVA s : clientSeats) {
            if (s != null && !s.isDead && s.riddenByEntity != null) {
                n++;
            }
        }
        return n;
    }

    public boolean isAutoOn() {
        return worldObj.isRemote ? (extra() & X_AUTO_ON) != 0 : state.autoOn;
    }

    public int getAutoState() {
        return worldObj.isRemote ? (extra() >> X_AUTO_SHIFT) & 7 : state.autoState;
    }

    public int getAutoReason() {
        return worldObj.isRemote ? (extra() >> X_REASON_SHIFT) & 7 : autoReason;
    }

    public int getAutoDist() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectInt(DW_AUTO_DIST) : autoDist;
    }

    /** "x y z" of A and B ("" when unset), for the cargo screen. */
    public String[] getRoute() {
        String r = worldObj.isRemote ? dataWatcher.getWatchableObjectString(DW_ROUTE) : routeText();
        String[] p = r.split(";", -1);
        return new String[] {p.length > 0 ? p[0] : "", p.length > 1 ? p[1] : ""};
    }

    private String routeText() {
        return pointText(state.routeA) + ";" + pointText(state.routeB);
    }

    private static String pointText(int[] p) {
        return p == null ? "" : p[0] + " " + p[1] + " " + p[2];
    }

    /** Tiltrotor nacelles 0 (vertical mode) .. 1 (airplane mode) (clients: synced). */
    public double getTiltPos() {
        if (worldObj != null && worldObj.isRemote) {
            return Math.min(TiltMath.TRANSITION_TICKS, (extra() >> X_TILT_SHIFT) & 63) / (double) TiltMath.TRANSITION_TICKS;
        }
        return state.tiltPos();
    }

    /** Tiltrotor: turning to / in airplane mode (clients: synced). */
    public boolean getTiltTarget() {
        return worldObj != null && worldObj.isRemote ? (extra() & X_TILT_PLANE) != 0 : state.tiltPlane;
    }

    /** Flies as an aeroplane now: Movement.PLANE, or a tiltrotor fully in airplane mode. */
    public boolean planeNow() {
        VehicleType t = getVehicleType();
        return t.movement == Movement.PLANE || t.movement == Movement.TILT && TiltMath.planeMode(getTiltPos());
    }

    /**
     * The afterburner burns this tick (both sides, from the keys `in`): a type with one, «Форсаж» held, throttle at
     * AFTERBURNER_THROTTLE or more, powered, its gas left; the tiltrotor only in airplane mode.
     */
    public boolean afterburnerOn(int in) {
        VehicleType t = getVehicleType();
        Gas g = t.gases.boostGas;
        if (g == null || (in & IN_AFTER) == 0) {
            return false;
        }
        if (planeNow()) {
            if (throttle < AFTERBURNER_THROTTLE) {
                return false;
            }
        } else if (t.movement != Movement.HELI || (in & IN_FWD) == 0) {
            return false;                                           // stage E: the shuttle's / ship's «Форсаж» flying forward
        }
        return hasLift() && canThrust() && getGas(g) > 0;
    }

    /** Thrust / top speed factor of the afterburner now (1 = off). */
    public double afterburnerMul(int in) {
        return afterburnerOn(in) ? getVehicleType().gases.boostMul : 1;
    }

    /** The argon hull field works (server: this tick; clients: synced). */
    public boolean isFieldOn() {
        return worldObj.isRemote ? (extra() & X_FIELD) != 0 : fieldNow;
    }

    // ---- stage E views (clients: synced)

    /** Held perfectly still: the gravity anchor, the jump portal opening, riders boarding after a transfer. */
    public boolean isHeld() {
        if (worldObj != null && worldObj.isRemote) {
            return (extra() & X_HELD) != 0;
        }
        return state.anchor || portalTicks > 0 || pendingRiders != null;
    }

    public boolean isLongRangeOn() {
        return worldObj.isRemote ? (extra() & X_LONG_ON) != 0 : state.longRange;
    }

    /** Long-range mode is working (deuterium instead of EU) this tick. */
    public boolean isLongRangeActive() {
        return worldObj.isRemote ? (extra() & X_LONG_ACTIVE) != 0 : longNow;
    }

    public boolean isDockNear() {
        return worldObj.isRemote ? (extra() & X_DOCK_NEAR) != 0 : dockNear;
    }

    public boolean isAnchored() {
        return worldObj.isRemote ? (extra() & X_ANCHOR) != 0 : state.anchor;
    }

    public boolean isInvisible() {
        return worldObj.isRemote ? (extra() & X_INVIS) != 0 : state.invisible;
    }

    public boolean isPortalOpening() {
        return worldObj.isRemote ? (extra() & X_PORTAL) != 0 : portalTicks > 0;
    }

    /** The space panel's line: "riders;code;dist;eu;sm;cooldown s;dim;home;name" (SpaceLogicSVA.hudString). */
    public String getSpaceInfo() {
        return dataWatcher.getWatchableObjectString(DW_SPACE);
    }

    private int extraBits() {
        return (state.trailer ? X_TRAILER : 0) | (drillOn ? X_DRILL : 0) | ((state.drillMode & 3) << X_MODE_SHIFT)
                | (DrillLogicSVA.wide(state) ? X_WIDE : 0) | (DrillLogicSVA.isHead(state.drillHead) ? X_HEAD : 0)
                | ((state.autoState & 7) << X_AUTO_SHIFT) | (state.autoOn ? X_AUTO_ON : 0) | ((autoReason & 7) << X_REASON_SHIFT)
                | (state.routeA != null ? X_ROUTE_A : 0) | (state.routeB != null ? X_ROUTE_B : 0) | (fieldNow ? X_FIELD : 0)
                | (state.tiltPlane ? X_TILT_PLANE : 0) | ((state.tiltStep & 63) << X_TILT_SHIFT)
                | (state.longRange ? X_LONG_ON : 0) | (longNow ? X_LONG_ACTIVE : 0) | (dockNear ? X_DOCK_NEAR : 0) | (isHeld() ? X_HELD : 0)
                | (state.anchor ? X_ANCHOR : 0) | (state.invisible ? X_INVIS : 0) | (portalTicks > 0 ? X_PORTAL : 0);
    }

    /** The synced gas string: the type's tank amounts in its order, comma separated. */
    static String gasString(VehicleState st) {
        StringBuilder b = new StringBuilder();
        for (Gas g : st.type.gases.gases()) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(st.gas(g));
        }
        return b.toString();
    }

    /** Held up by its lift gas (server: this tick; clients: synced). */
    public boolean isLifting() {
        return worldObj.isRemote ? flag(F_LIFT) : lifting;
    }

    /** A charging station / platform served it in the last CHARGE_HOLD ticks (server; clients: synced). */
    public boolean isCharging() {
        return worldObj.isRemote ? flag(F_CHARGING) : chargeTicks > 0;
    }

    /** Server: a station just charged it (the HUD's «Зарядка» line). */
    public void markCharging() {
        boolean was = chargeTicks > 0;
        chargeTicks = CHARGE_HOLD;
        if (!was) {
            syncWatcher();
        }
    }

    /** Server: the hull mended by `amount` HP (the station's repair module), capped at the maximum. */
    public void repairHull(float amount) {
        if (!(amount > 0)) {
            return;
        }
        state.hp = Math.min(state.type.maxHp, state.hp + amount);
        syncWatcher();
    }

    public boolean isBoosting() {
        return worldObj.isRemote ? flag(F_BOOST) : boosting;
    }

    /**
     * Has lift now (Movement.lifts types): someone at the wheel (or the world test), not wrecked / overheated,
     * EU and lift gas left. Both sides (clients read the synced values).
     */
    public boolean hasLift() {
        VehicleType t = getVehicleType();
        if (!t.movement.lifts || !t.movement.flies && t.gases.liftGas == null) {
            return false;
        }
        boolean motor = riddenByEntity != null || debugDriver;
        if (!motor || isWrecked() || isHeatStopped() || getEnergy() <= 0) {
            return false;
        }
        return t.gases.liftGas == null || getGas(t.gases.liftGas) > 0;      // aircraft fly on EU alone
    }

    /** Aircraft in the air without power: the heli autorotates, the plane glides (server: this tick; clients: synced). */
    public boolean isAutorotating() {
        return worldObj.isRemote ? flag(F_AUTO) : autoNow;
    }

    /** A plane below its stall speed in the air (server: this tick; clients: synced). */
    public boolean isStalling() {
        return worldObj.isRemote ? flag(F_STALL) : stallNow;
    }

    /** Plane throttle 0..1 (clients: synced; the driver's own client: its simulated value). */
    public double getThrottle() {
        if (worldObj.isRemote && !(riddenByEntity != null && riddenByEntity == SVAMod.proxy.localPlayer())) {
            return dataWatcher.getWatchableObjectFloat(DW_THR);
        }
        return throttle;
    }

    /** EU a tick the server charged last (config multiplier included; clients: synced). */
    public float getCostNow() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectFloat(DW_COST) : costNow;
    }

    /** Is the block under the centre a Runway block (plane: x0.5 take-off run, soft brakes)? */
    public boolean onRunway() {
        int x = MathHelper.floor_double(posX), y = MathHelper.floor_double(posY - 0.2), z = MathHelper.floor_double(posZ);
        return ModContentSVA.runway != null && worldObj.getBlock(x, y, z) == ModContentSVA.runway;
    }

    /** No block within FlightMath.AIRBORNE under the collision box at (x, y, z) - in the air. */
    public boolean airborneAt(double x, double y, double z) {
        AxisAlignedBB box = boundingBox.getOffsetBoundingBox(x - posX, y - posY - FlightMath.AIRBORNE, z - posZ).contract(0.05, 0, 0.05);
        return worldObj.getCollidingBoundingBoxes(this, box).isEmpty();
    }

    /** EU/t at top speed with the current cargo (server config applied) - the HUD's range estimate. */
    public float getCruiseCost() {
        return dataWatcher.getWatchableObjectFloat(DW_CRUISE);
    }

    private void syncWatcher() {
        VehicleType t = state.type;
        dataWatcher.updateObject(DW_ENERGY, Integer.valueOf((int) state.energy));
        dataWatcher.updateObject(DW_HP, Float.valueOf(state.hp));
        dataWatcher.updateObject(DW_HEAT, Float.valueOf((float) Math.floor(state.heat * 10) / 10F));
        int f = (VehicleMath.wrecked(state.hp) && ConfigSVA.wear ? F_WRECKED : 0) | (state.heatStop ? F_HEAT_STOP : 0) | (ConfigSVA.wear ? F_WEAR : 0)
                | (lifting ? F_LIFT : 0) | (boosting ? F_BOOST : 0) | (chargeTicks > 0 ? F_CHARGING : 0)
                | (autoNow ? F_AUTO : 0) | (stallNow ? F_STALL : 0);
        dataWatcher.updateObject(DW_FLAGS, Byte.valueOf((byte) f));
        dataWatcher.updateObject(DW_CARGO, Byte.valueOf((byte) state.cargoUsed()));
        dataWatcher.updateObject(DW_TYPE, Byte.valueOf((byte) t.ordinal()));
        dataWatcher.updateObject(DW_CRUISE, Float.valueOf((float) (t.movement.flies ? FlightMath.cruiseCost(t, state.cargoFill(), posY, planeNow()) * ConfigSVA.consumption
                : VehicleMath.cost(t, t.topSpeed, state.cargoFill(), true, ConfigSVA.consumption))));
        dataWatcher.updateObject(DW_THR, Float.valueOf((float) throttle));
        dataWatcher.updateObject(DW_COST, Float.valueOf(costNow));
        dataWatcher.updateObject(DW_YAW, Float.valueOf(rotationYaw));
        dataWatcher.updateObject(DW_GAS, gasString(state));
        dataWatcher.updateObject(DW_EXTRA, Integer.valueOf(extraBits()));
        dataWatcher.updateObject(DW_DUG, Integer.valueOf((int) Math.min(Integer.MAX_VALUE, state.dug)));
        dataWatcher.updateObject(DW_HEAD, Integer.valueOf(headLeft()));
        dataWatcher.updateObject(DW_HEAD_MAX, Integer.valueOf(state.drillHead == null ? 0 : state.drillHead.getMaxDamage()));
        dataWatcher.updateObject(DW_AUTO_DIST, Integer.valueOf(autoDist));
        dataWatcher.updateObject(DW_ROUTE, routeText());
        dataWatcher.updateObject(DW_OWNER, state.ownerName == null ? "" : state.ownerName);
        dataWatcher.updateObject(DW_TORCHES, Integer.valueOf(state.torches));
        if (t.bridgeDock && (ticksExisted % 10 == 0 || spaceDirty)) {
            spaceDirty = false;
            dataWatcher.updateObject(DW_SPACE, SpaceLogicSVA.hudString(this, t));
        }
    }

    /** Stage E: the space panel's line is rebuilt on the next sync (a key pressed). */
    private boolean spaceDirty = true;

    /** Everything synced at once (a vehicle just made by a transfer). */
    void syncAll() {
        spaceDirty = true;
        syncWatcher();
    }

    // ------------------------------------------------------------------ geometry

    /** Model-space offset (x forward, z right) -> world {dx, dz} for the current yaw. */
    public double[] toWorld(double fx, double fz) {
        double r = Math.toRadians(rotationYaw);
        double s = Math.sin(r), c = Math.cos(r);
        return new double[] {-s * fx - c * fz, c * fx - s * fz};
    }

    public double[] seatPos(int seat) {
        VehicleType t = getVehicleType();
        double[] o = t.seatOffsets[Math.min(seat, t.seatOffsets.length - 1)];
        double[] w = toWorld(o[0], o[1]);
        return new double[] {posX + w[0], posY + t.seatDy(seat), posZ + w[1]};     // y: + the seat's own height change
    }

    @Override
    public double getMountedYOffset() {
        return getVehicleType().seatY;
    }

    @Override
    public void updateRiderPosition() {
        if (riddenByEntity != null) {
            double[] p = seatPos(0);
            riddenByEntity.setPosition(p[0], p[1] + getMountedYOffset() + riddenByEntity.getYOffset(), p[2]);
        }
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;                    // not solid: players walk through, never get stuck on dismount
    }

    @Override
    public AxisAlignedBB getCollisionBox(Entity e) {
        return null;
    }

    @Override
    public boolean canBeCollidedWith() {
        return !isDead;                 // clickable
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    protected boolean canTriggerWalking() {
        return false;
    }

    @Override
    public void travelToDimension(int dim) {
        // vehicles never go through portals (stage E moves them with VehicleTransferSVA)
    }

    @Override
    public void addVelocity(double x, double y, double z) {
        if (!isHeld()) {
            super.addVelocity(x, y, z);                             // the gravity anchor: immune to knockback
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean isInRangeToRenderDist(double d2) {
        return d2 < RENDER_DIST * RENDER_DIST;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void onUpdate() {
        super.onUpdate();
        VehicleType t = getVehicleType();
        if (worldObj.isRemote) {
            clientUpdate(t);
        } else {
            serverUpdate(t);
        }
        placeSeats();
    }

    private void serverUpdate(VehicleType t) {
        long now = worldObj.getTotalWorldTime();
        if (!registered) {
            VehicleRegistrySVA r = VehicleRegistrySVA.get(worldObj);
            if (r != null) {
                r.add(state.vehId, state.ownerId);
            }
            registered = true;
        }
        maintainSeats(t);
        if (chargeTicks > 0) {
            chargeTicks--;
        }
        // stage E: riders carried over by a docking / jump board again; the cabin, the anchor, docking, the jump portal
        if (pendingRiders != null || !attachResend.isEmpty()) {
            VehicleTransferSVA.tickPending(this);
        }
        if (pendingRiders == null && pendingMsg != null) {
            tellAll(pendingMsg);
            pendingMsg = null;
        }
        if (t.bridgeDock && SpaceLogicSVA.tick(this, t)) {
            return;                                                 // moved away: this entity is gone
        }
        // auto-delivery (the crawler): nobody at the wheel - the server drives through the same physics as the keys
        boolean autoDrive = state.autoOn && riddenByEntity == null && !debugDriver && AutoDelivery.target(state.autoState) >= 0;
        int autoIn = autoDrive ? AutoPilotSVA.tick(this, prevMoved) : 0;
        autoDrive = autoDrive && AutoDelivery.target(state.autoState) >= 0;
        boolean motorOn = riddenByEntity != null || debugDriver || autoDrive;
        if (!debugDriver && now - lastInputTick > INPUT_TIMEOUT) {
            input = 0;                                              // keys went stale: let go
        }
        int in = autoDrive ? autoIn : input;
        boolean clientDriven = riddenByEntity instanceof EntityPlayerMP && now - lastMoveTick <= MOVE_TIMEOUT;
        // aircraft: also while the last tick ended in the air (a driver who got out mid-air - onGround is stale then)
        if (!clientDriven && (motorOn || Math.abs(speed) > 0.0005 || !onGround || ticksExisted < 3 || t.movement.flies && wasAirborne)) {
            physicsStep(t, motorOn ? in : 0);
        }
        // measured displacement - the base for EU, heat and wall hits on both control paths
        if (!hasLast) {
            lastX = posX;
            lastY = posY;
            lastZ = posZ;
            hasLast = true;
        }
        double dx = posX - lastX, dz = posZ - lastZ;
        double moved = Math.sqrt(dx * dx + dz * dz);
        if (moved > 8) {
            moved = 0;                                              // a teleport, not driving
        }
        if (clientDriven) {
            double r = Math.toRadians(rotationYaw);
            speed = dx * -Math.sin(r) + dz * Math.cos(r);           // the fallback continues from here
        }
        speedEma = speedEma * 0.5 + moved * 0.5;
        if (hitCooldown > 0) {
            hitCooldown--;
        }
        if (ConfigSVA.wear && hitCooldown == 0 && prevMoved > VehicleMath.HIT_SAFE && moved < prevMoved * 0.4 && blockedToward(lastDx, lastDz)) {
            float dmg = VehicleMath.hitDamage(Math.min(prevMoved, t.topSpeed) - moved);   // capped: bunched packets aren't speed
            if (dmg > 0) {
                damageHull(dmg);
                worldObj.playSoundAtEntity(this, "random.anvil_land", 0.5F, 0.8F);
                hitCooldown = 10;
            }
        }
        if (moved > 0.01) {
            lastDx = dx;
            lastDz = dz;
        }
        prevMoved = moved;
        // the argon hull field (drill vehicle): 1 mB/s while the motor runs, hull damage -40 % (damageHull)
        Gas fg = t.gases.fieldGas;
        fieldNow = fg != null && motorOn && !isWrecked() && state.gas(fg) > 0;
        if (fieldNow) {
            state.drainGasFraction(fg, t.gases.fieldPerSecond / 20.0);
        }
        // the bore: on, driven forward, the motor able to push
        if (t.hasDrill && drillOn && motorOn && (in & IN_FWD) != 0 && canThrust() && state.energy > 0) {
            DrillLogicSVA.tick(this, moved);
        }
        // the tiltrotor's nacelles turn a step a tick toward the wanted mode
        if (t.movement == Movement.TILT) {
            tiltTick();
        }
        // lift gas (hover): 2 mB/s held up, 5 on boost - fractions kept like the core armour
        lifting = t.movement.lifts && hasLift() && (!t.movement.flies || canThrust());
        if (t.movement == Movement.HOVER) {
            boosting = lifting && (input & (IN_BOOST | IN_AFTER)) != 0 && (input & IN_FWD) != 0;
        } else {
            boosting = lifting && afterburnerOn(motorOn ? in : 0);         // the jet's / tiltrotor's afterburner
        }
        if (boosting && t.gases.boostGas != null) {
            state.drainGasFraction(t.gases.boostGas, t.gases.boostGasPerSecond / 20.0);
        }
        if (lifting && t.gases.liftGas != null) {
            state.drainGasFraction(t.gases.liftGas, t.gases.liftPerTick(boosting));
            if (riddenByEntity != null && riddenByEntity.isBurning()) {
                riddenByEntity.extinguish();                        // the antigravity field keeps the heat off the rider
            }
        }
        // energy and heat
        double fill = state.cargoFill();
        double v = Math.min(speedEma, t.topSpeed * (boosting ? t.gases.boostMul : 1) * 1.2);
        boolean working = motorOn && state.energy > 0 && (!t.movement.lifts || lifting);
        double cost, load;
        if (t.movement.flies) {
            double base = flightTick(t, clientDriven, v, fill, working);
            cost = base * Math.max(0, ConfigSVA.consumption);
            load = working ? FlightMath.load(t, base) * (boosting && t.gases.boostGas != null ? VehicleGases.AFTERBURNER_HEAT : 1) : 0;
        } else {
            cost = VehicleMath.cost(t, v, fill, working, ConfigSVA.consumption);
            load = working ? VehicleMath.load(t, v, fill) : 0;
        }
        // stage E: long-range mode - deuterium instead of EU high and fast; the gravity anchor's small upkeep
        longNow = t.longRange && working && lifting && SpaceMath.longRangeActive(state.longRange, posY, speedEma, t.topSpeed,
                state.gas(Gas.DEUTERIUM));
        if (longNow) {
            cost = 0;
            state.drainGasFraction(Gas.DEUTERIUM, SpaceMath.LONG_D_PER_SECOND / 20.0);
        }
        if (t.singular && state.anchor && state.energy > 0) {
            cost = SpaceMath.ANCHOR_EU * Math.max(0, ConfigSVA.consumption);
            load = 0;
        }
        costNow = (float) cost;
        state.energy = Math.max(0, state.energy - cost);
        boolean wasStopped = state.heatStop;
        state.heat = VehicleMath.heatStep(state.heat, load, coolingBonus());
        state.heatStop = VehicleMath.heatStopped(state.heatStop, state.heat);
        if (state.heatStop && !wasStopped) {
            tellRiders("sva.msg.overheat");
        }
        syncWatcher();
        // a rider got off: put them beside the vehicle, not on its roof
        if (lastRider != null && riddenByEntity != lastRider && lastRider.ridingEntity == null && !lastRider.isDead) {
            placeBeside(lastRider, -1);
        }
        lastRider = riddenByEntity;
        lastX = posX;
        lastY = posY;
        lastZ = posZ;
    }

    /**
     * Server, aircraft, once a tick from the measured motion: in the air or not, touch-down damage (HeliMath /
     * PlaneMath.landingDamage - never to the riders), autorotation / glide / stall flags (+ a chat line when it
     * starts), the plane's throttle when the client drives, brake scuffs off a runway, the riders' fall distance kept 0.
     * @return EU this tick before the config multiplier
     */
    private double flightTick(VehicleType t, boolean clientDriven, double v, double fill, boolean working) {
        double dy = posY - lastY;
        boolean air = airborneAt(posX, posY, posZ);
        boolean plane = planeNow();
        boolean tilt = t.movement == Movement.TILT;
        if (clientDriven) {
            onGround = !air;                                        // the server's fallback physics starts from the truth
            if (plane) {
                throttle = PlaneMath.throttleStep(throttle, (input & IN_FWD) != 0, (input & IN_BACK) != 0, lifting);
            } else if (tilt && TiltMath.transition(state.tiltPos()) && state.tiltPlane && lifting) {
                throttle = Math.max(throttle, TiltMath.CONVERT_THROTTLE);   // as TiltPhysicsSVA does on the client
            }
        }
        if (wasAirborne && !air && ticksExisted > 5) {
            double impact = Math.max(0, -Math.min(dy, lastDy));
            double dmg = plane ? PlaneMath.landingDamage(t.plane, t.maxHp, impact, v, lifting) : HeliMath.landingDamage(t, impact, !lifting);
            if (dmg > 0) {
                damageHull((float) dmg);
                worldObj.playSoundAtEntity(this, "random.anvil_land", 0.5F, 0.6F);
            }
        }
        boolean wasAuto = autoNow;
        autoNow = air && !lifting && !isHeld();
        stallNow = plane && air && PlaneMath.stalled(t.plane, v);
        if (autoNow && !wasAuto && ticksExisted > 5) {
            tellRiders(plane ? "sva.msg.glide" : "sva.msg.autorotation");
        }
        if (plane && !air && ConfigSVA.wear && (input & IN_BACK) != 0 && throttle <= 0 && v > PlaneMath.BRAKE_WEAR_SPEED && !onRunway()) {
            damageHull(PlaneMath.BRAKE_WEAR);                       // skidding on bare ground - a runway brakes softly
        }
        for (Entity r : riders()) {
            r.fallDistance = 0;                                     // seated: no fall damage; off in the air - a normal fall
        }
        wasAirborne = air;
        lastDy = dy;
        if (plane) {
            double thr = lifting ? Math.max(throttle, PlaneMath.throttleFloor(t, v)) : 0;
            return PlaneMath.cost(t, thr, air, dy, posY, fill, working);
        }
        return tilt ? HeliMath.vtolCost(t, air, v, dy, fill, working) : HeliMath.cost(t, air, v, dy, fill, working);
    }

    /** Everyone seated: the driver and the passenger seats' riders. */
    public List<Entity> riders() {
        List<Entity> out = new ArrayList<Entity>();
        if (riddenByEntity != null) {
            out.add(riddenByEntity);
        }
        if (seats != null) {
            for (EntitySeatSVA s : seats) {
                if (s != null && !s.isDead && s.riddenByEntity != null) {
                    out.add(s.riddenByEntity);
                }
            }
        }
        return out;
    }

    /** Extra cooling %/tick: helium (VehicleMath.heliumCooling), spent from the coolant tank. No tank (the buggy): 0. */
    protected double coolingBonus() {
        Gas c = state.type.gases.coolant;
        if (c == null) {
            return 0;
        }
        double b = VehicleMath.heliumCooling(state.heat, state.gas(c));
        if (b > 0) {
            state.drainGasFraction(c, VehicleMath.heliumUse(b));
        }
        return b;
    }

    /** Is there a wall (above step height) right ahead in the direction (dx, dz)? */
    private boolean blockedToward(double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            return false;
        }
        AxisAlignedBB box = boundingBox.getOffsetBoundingBox(dx / len * 0.4, stepHeight + 0.05, dz / len * 0.4).contract(0.05, 0.05, 0.05);
        return !worldObj.getCollidingBoundingBoxes(this, box).isEmpty();
    }

    /** Engine may push: not wrecked, not overheated, not in deep water (a crawler swims: lava only stops it). */
    public boolean canThrust() {
        VehicleType t = getVehicleType();
        Movement m = t.movement;
        return !isWrecked() && !isHeatStopped() && (m == Movement.HOVER || t.sealed && !inLava() || !inDeepWater() || m == Movement.CRAWLER && !inLava());
    }

    private boolean inLava() {
        int x = MathHelper.floor_double(posX), y = MathHelper.floor_double(posY + 1.0), z = MathHelper.floor_double(posZ);
        return worldObj.getBlock(x, y, z).getMaterial() == net.minecraft.block.material.Material.lava;
    }

    /** Crawler: water deeper than its draft under the hull's middle (it rises). */
    boolean submerged() {
        int x = MathHelper.floor_double(posX), y = MathHelper.floor_double(posY + CrawlerMath.DRAFT), z = MathHelper.floor_double(posZ);
        return worldObj.getBlock(x, y, z).getMaterial() == net.minecraft.block.material.Material.water;
    }

    public boolean inDeepWater() {
        int x = MathHelper.floor_double(posX), y = MathHelper.floor_double(posY + 1.0), z = MathHelper.floor_double(posZ);
        return worldObj.getBlock(x, y, z).getMaterial().isLiquid();
    }

    /** One tick of ground physics from the input bits (driver client, or the server's fallback). */
    public void physicsStep(VehicleType t, int in) {
        if (isHeld()) {
            // stage E: the gravity anchor / the portal opening / riders boarding - perfectly still, no drift, no gravity
            speed = 0;
            motionX = motionY = motionZ = 0;
            fallDistance = 0;
            return;
        }
        if (t.movement == Movement.HOVER) {
            HoverPhysicsSVA.step(this, t, in);
            return;
        }
        if (t.movement == Movement.HELI) {
            HeliPhysicsSVA.step(this, t, in);
            return;
        }
        if (t.movement == Movement.PLANE) {
            PlanePhysicsSVA.step(this, t, in);
            return;
        }
        if (t.movement == Movement.TILT) {
            TiltPhysicsSVA.step(this, t, in);
            return;
        }
        boolean fwd = (in & IN_FWD) != 0, back = (in & IN_BACK) != 0, left = (in & IN_LEFT) != 0,
                right = (in & IN_RIGHT) != 0, brake = (in & IN_BRAKE) != 0;
        boolean thrust = canThrust();
        double top = VehicleMath.maxSpeed(t, getEnergy());
        boolean water = isInWater();
        boolean crawler = t.movement == Movement.CRAWLER;
        if (water) {
            top *= crawler ? CrawlerMath.WATER_SPEED : WATER_SPEED;
        }
        if (thrust && fwd && !back) {
            speed += speed < 0 ? BRAKE : t.accel;
        } else if (thrust && back && !fwd) {
            speed -= speed > 0 ? BRAKE : t.accel * 0.6;
        } else {
            speed = toward0(speed, ROLL);
        }
        if (brake) {
            speed = toward0(speed, BRAKE);
        }
        if (speed > top) {
            speed = Math.max(top, speed - BRAKE);
        }
        if (speed < -top * REVERSE) {
            speed = Math.min(-top * REVERSE, speed + BRAKE);
        }
        int steer = (right ? 1 : 0) - (left ? 1 : 0);
        if (crawler) {
            rotationYaw += CrawlerMath.turn(t.turnRate, steer, speed, thrust, getEnergy() > 0);   // tracks: on the spot too
        } else if (steer != 0 && speed != 0) {
            rotationYaw += steer * t.turnRate * Math.min(1, Math.abs(speed) / 0.12) * Math.signum(speed);
        }
        double r = Math.toRadians(rotationYaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        motionX = fx * speed;
        motionZ = fz * speed;
        if (crawler && water) {
            motionY = CrawlerMath.floatVy(motionY, submerged());      // floats and swims across
            fallDistance = 0;
        } else {
            motionY -= 0.08;
            motionY *= water ? 0.6 : 0.98;
        }
        double px = posX, pz = posZ;
        moveEntity(motionX, motionY, motionZ);
        if (isCollidedVertically && motionY < 0) {
            motionY = 0;
        }
        if (isCollidedHorizontally) {
            speed = (posX - px) * fx + (posZ - pz) * fz;           // the wall took the rest
        }
    }

    private static double toward0(double v, double step) {
        return v > 0 ? Math.max(0, v - step) : Math.min(0, v + step);
    }

    @Override
    protected void fall(float distance) {
        // the hull takes the landing, not the riders
        if (!worldObj.isRemote && ConfigSVA.wear && distance > 4 && !state.type.movement.flies) {     // aircraft: flightTick
            damageHull((distance - 4) * 5);
        }
    }

    // ------------------------------------------------------------------ server: network input

    /** Input packet (server thread, sender verified as the driver) - and the world test's path. */
    public void serverInput(int flags) {
        input = flags & IN_MASK;
        lastInputTick = worldObj.getTotalWorldTime();
    }

    /** World test: drive without a rider through the same input path. */
    public void debugDrive(int flags) {
        debugDriver = flags != 0;
        serverInput(flags);
    }

    /** Move packet from the driver's client (server thread): accept or correct. */
    public void serverMove(EntityPlayerMP p, double x, double y, double z, float yaw, float clientSpeed) {
        long now = worldObj.getTotalWorldTime();
        if (!finite(x) || !finite(y) || !finite(z) || Float.isNaN(yaw) || Float.isInfinite(yaw)) {
            return;
        }
        long dt = Math.max(1, Math.min(MOVE_TIMEOUT, now - lastMoveTick));
        lastMoveTick = now;
        VehicleType t = state.type;
        double dx = x - posX, dy = y - posY, dz = z - posZ;
        double h = Math.sqrt(dx * dx + dz * dz);
        if (isHeld() && (h > 0.01 || Math.abs(dy) > 0.01)) {
            if (now - lastCorrectTick >= 5 && p.playerNetServerHandler != null) {
                lastCorrectTick = now;
                NetSVA.CHANNEL.sendTo(new NetSVA.Correct(getEntityId(), posX, posY, posZ, rotationYaw), p);
            }
            return;                                                 // held: it doesn't move (the yaw neither)
        }
        boolean flies = t.movement.flies;
        boolean hover = t.movement.lifts && !flies;
        boolean lift = t.movement.lifts && hasLift() && canThrust();
        double coast = Math.max(speedEma * 1.1, 0.02);
        double limit = hover ? (lift ? HoverMath.maxSpeed(t, true, (input & (IN_BOOST | IN_AFTER)) != 0) : coast)
                : canThrust() ? VehicleMath.maxSpeed(t, state.energy) : coast;      // coasting only
        // aircraft: reach, climbing only with power / airspeed, the ceiling, no hanging in the air unpowered (FlightMath)
        boolean ok = flies ? FlightMath.moveAllowed(t, moveRules(t, now), h, dy, dt, y, lift, airborneAt(posX, posY, posZ), airborneAt(x, y, z), speedEma)
                : moveAllowed(h, dy, dt, limit, CrawlerMath.climbLimit(t.stepHeight));
        if (ok && (Math.abs(dx) > 1e-4 || Math.abs(dy) > 1e-4 || Math.abs(dz) > 1e-4)) {
            AxisAlignedBB box = boundingBox.getOffsetBoundingBox(dx, dy, dz).contract(0.05, 0.05, 0.05);
            if (!worldObj.getCollidingBoundingBoxes(this, box).isEmpty()) {
                ok = false;                                         // into blocks
            } else if (flies) {
                ok = true;                                          // FlightMath.moveAllowed checked it
            } else if (hover) {
                // no free flight: climbing only with lift and under the cap; in the air without lift (or far over
                // the cap) it must be coming down
                double hNew = HoverPhysicsSVA.heightAt(worldObj, x, y, z);
                if (dy > 0.05 && !HoverMath.climbAllowed(lift, hNew)) {
                    ok = false;
                } else if (HoverMath.mustDescend(lift, hNew) && dy > -0.03 * dt) {
                    ok = false;
                }
            } else if (dy > 0.05 && worldObj.getCollidingBoundingBoxes(this, box.getOffsetBoundingBox(0, -0.6, 0)).isEmpty()
                    && !(t.movement == Movement.CRAWLER && dy <= CrawlerMath.MAX_RISE * dt + 0.05
                    && worldObj.isMaterialInBB(box.expand(0, 0.6, 0), net.minecraft.block.material.Material.water))) {
                ok = false;                                         // climbing with nothing under it (a crawler may float up)
            }
        }
        if (ok) {
            setPosition(x, y, z);
            rotationYaw = yaw;
            fallDistance = 0;
        } else if (now - lastCorrectTick >= 5 && p.playerNetServerHandler != null) {
            lastCorrectTick = now;
            NetSVA.CHANNEL.sendTo(new NetSVA.Correct(getEntityId(), posX, posY, posZ, rotationYaw), p);
        }
    }

    /**
     * The rules a flying move is judged by: the type's own; the tiltrotor - PLANE in airplane mode (from TILT_GRACE
     * ticks after the nacelles got there: the client may still be finishing the turn), HELI otherwise.
     */
    private Movement moveRules(VehicleType t, long now) {
        if (t.movement != Movement.TILT) {
            return t.movement;
        }
        return TiltMath.planeMode(state.tiltPos()) && now - tiltDoneTick > TILT_GRACE ? Movement.PLANE : Movement.HELI;
    }

    /** Server, tiltrotor: the nacelles a step toward the wanted mode; the pilot is told when they get there. */
    private void tiltTick() {
        int target = state.tiltPlane ? TiltMath.TRANSITION_TICKS : 0;
        if (state.tiltStep == target) {
            return;
        }
        state.tiltStep += state.tiltStep < target ? 1 : -1;
        if (state.tiltStep == target) {
            if (state.tiltPlane) {
                tiltDoneTick = worldObj.getTotalWorldTime();
            }
            tellRiders(state.tiltPlane ? "sva.msg.tilt.plane" : "sva.msg.tilt.vertical");
        }
    }

    /** Blocks of air under the collision box (0 on the ground), looked for down to `max`. */
    public double heightAboveGround(double max) {
        for (double d = 0; d < max; d += 0.5) {
            AxisAlignedBB box = boundingBox.getOffsetBoundingBox(0, -d - FlightMath.AIRBORNE, 0).contract(0.05, 0, 0.05);
            if (!worldObj.getCollidingBoundingBoxes(this, box).isEmpty()) {
                return d;
            }
        }
        return max;
    }

    /**
     * The tiltrotor's «Режим» (server): starts the turn to the other mode if TiltMath.switchCheck allows it (to
     * airplane mode in the air with speed or height, back only slow). @return TiltMath.OK or why not
     */
    public int toggleFlightMode() {
        VehicleType t = state.type;
        if (t.movement != Movement.TILT) {
            return TiltMath.NOT_TILT;
        }
        boolean toPlane = !state.tiltPlane;
        int r = TiltMath.switchCheck(state.tiltPos(), toPlane, airborneAt(posX, posY, posZ), Math.abs(speedEma),
                heightAboveGround(TiltMath.MIN_HEIGHT + 1), t.plane.stall);
        if (r == TiltMath.OK) {
            state.tiltPlane = toPlane;
            syncWatcher();
        }
        return r;
    }

    /** Pure move check: horizontal reach, step / fall limits, no teleports. */
    public static boolean moveAllowed(double horizontal, double dy, long dt, double speedLimit) {
        return moveAllowed(horizontal, dy, dt, speedLimit, 1.1);
    }

    /** The same with a climb limit a tick (CrawlerMath.climbLimit: tracks step 2 blocks). */
    public static boolean moveAllowed(double horizontal, double dy, long dt, double speedLimit, double climb) {
        return horizontal < 10 && horizontal <= speedLimit * 1.3 * dt + 0.2 && dy <= climb * dt + 0.3 && dy >= -(4.0 * dt + 1);
    }

    private static boolean finite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    // ------------------------------------------------------------------ client

    private void clientUpdate(VehicleType t) {
        if (sizedFor != t) {
            applySize(t);
        }
        EntityPlayer local = SVAMod.proxy.localPlayer();
        if (local != null && riddenByEntity == local) {
            int in = SVAMod.proxy.driverInput();
            physicsStep(t, in);
            lerpSteps = 0;
            if (in != lastSentInput || ++inputTimer >= KEEPALIVE) {
                NetSVA.CHANNEL.sendToServer(new NetSVA.Input(getEntityId(), in));
                lastSentInput = in;
                inputTimer = 0;
            }
            boolean changed = Math.abs(posX - sentX) + Math.abs(posY - sentY) + Math.abs(posZ - sentZ) > 1e-3 || Math.abs(rotationYaw - sentYaw) > 0.01;
            if (changed || ++moveTimer >= KEEPALIVE) {
                NetSVA.CHANNEL.sendToServer(new NetSVA.Move(getEntityId(), posX, posY, posZ, rotationYaw, (float) speed));
                sentX = posX;
                sentY = posY;
                sentZ = posZ;
                sentYaw = rotationYaw;
                moveTimer = 0;
            }
        } else {
            lastSentInput = -1;
            if (lerpSteps > 0) {
                setPosition(posX + (lx - posX) / lerpSteps, posY + (ly - posY) / lerpSteps, posZ + (lz - posZ) / lerpSteps);
                lerpSteps--;
            }
            float target = dataWatcher.getWatchableObjectFloat(DW_YAW);
            float d = MathHelper.wrapAngleTo180_float(target - rotationYaw);
            rotationYaw += Math.abs(d) > 90 ? d : d * 0.5F;
        }
        // the local rider's view turns with the vehicle every frame (ClientProxySVA.onRenderTick), not here once
        // a tick - a 20 Hz jump of the camera is what shook the screen in turns
        if (t.movement.flies) {
            animateFlight(t);
        }
        if (t.singular && isPortalOpening()) {
            portalSwirl(t);
        } else {
            portalFx = 0;
        }
        if (t.hasDrill) {
            prevDrillAngle = drillAngle;
            float target = isDrillOn() && riddenByEntity != null ? 32F : 0F;
            drillRate += MathHelper.clamp_float(target - drillRate, -1.5F, 2F);
            drillAngle = (drillAngle + drillRate) % 360F;
        }
        if (isWrecked() && ticksExisted % 4 == 0) {
            worldObj.spawnParticle("largesmoke", posX + (rand.nextDouble() - 0.5), posY + 1.0, posZ + (rand.nextDouble() - 0.5), 0, 0.05, 0);
        } else if (isHeatStopped() && ticksExisted % 6 == 0) {
            double[] w = toWorld(0.9, 0);
            worldObj.spawnParticle("smoke", posX + w[0], posY + 1.0, posZ + w[1], 0, 0.04, 0);
        }
    }

    /**
     * Client, aircraft: the rotor / propeller spin (heli: 42 deg/t powered, 30 autorotating; plane: 18 + 50 x throttle,
     * windmilling 8 in a glide; spins up / down smoothly) and the body's visual attitude - the heli dips its nose with
     * speed and banks in turns, the plane pitches with its climb (nose down in a stall) and banks into turns.
     */
    private void animateFlight(VehicleType t) {
        prevRotorAngle = rotorAngle;
        prevRenderPitch = renderPitch;
        prevRenderRoll = renderRoll;
        boolean tilt = t.movement == Movement.TILT;
        boolean heli = t.movement == Movement.HELI || tilt && !planeNow();
        boolean lift = isLifting(), auto = isAutorotating();
        float target = heli ? (lift ? 42F : auto ? 30F : 0F) : (lift ? 18F + 50F * (float) getThrottle() : auto ? 8F : 0F);
        if (tilt && lift) {
            target = Math.max(target, 36F);                         // the proprotors spin in both modes
        }
        if (isBoosting() && t.gases.boostGas != null && t.movement == Movement.PLANE) {
            afterburnerFlame(t);
        }
        if (t.singular) {
            // the singularity's rings: slowly when parked, faster with the drive powered, fast while the portal opens
            prevCoreAngle = coreAngle;
            float ct = isPortalOpening() ? 18F : lift ? 6F : 1.5F;
            coreRate += MathHelper.clamp_float(ct - coreRate, -0.25F, 0.25F);
            coreAngle = (coreAngle + coreRate) % 360F;
        }
        rotorRate += MathHelper.clamp_float(target - rotorRate, -0.8F, 1.2F);
        rotorAngle = (rotorAngle + rotorRate) % 360F;
        double dx = posX - prevPosX, dy = posY - prevPosY, dz = posZ - prevPosZ;
        double r = Math.toRadians(rotationYaw);
        double fwd = dx * -Math.sin(r) + dz * Math.cos(r);
        float yawRate = MathHelper.wrapAngleTo180_float(rotationYaw - prevRotationYaw);
        boolean air = airborneAt(posX, posY, posZ);
        float tp, tr;
        if (heli) {
            tp = (float) (-fwd / (tilt ? t.vtolTop : t.topSpeed) * 10);
            tr = air ? MathHelper.clamp_float(yawRate * 3F, -12F, 12F) : 0F;
        } else {
            tp = air ? (float) Math.toDegrees(Math.atan2(dy, Math.max(0.15, Math.abs(fwd)))) : 0F;
            tp = isStalling() ? -12F : MathHelper.clamp_float(tp, -20F, 20F);
            tr = air ? MathHelper.clamp_float(yawRate * 10F, -25F, 25F) : 0F;
        }
        renderPitch += (tp - renderPitch) * 0.2F;
        renderRoll += (tr - renderRoll) * 0.2F;
    }

    /** Jet nozzle in model units (jet.obj's thruster disc: x -2.975, y 0.561, radius 0.175). */
    public static final float NOZZLE_X = -2.975F, NOZZLE_Y = 0.561F, NOZZLE_R = 0.175F;

    /** Client: the afterburner's flame and smoke behind the nozzle (the renderer adds the glowing cone). */
    private void afterburnerFlame(VehicleType t) {
        double[] w = toWorld(NOZZLE_X * t.modelScale - 0.3, 0);
        double r = Math.toRadians(rotationYaw);
        double bx = Math.sin(r) * 0.25, bz = -Math.cos(r) * 0.25;   // blown backwards
        double y = posY + NOZZLE_Y * t.modelScale;
        for (int i = 0; i < 2; i++) {
            worldObj.spawnParticle("flame", posX + w[0] + (rand.nextDouble() - 0.5) * 0.3, y + (rand.nextDouble() - 0.5) * 0.3,
                    posZ + w[1] + (rand.nextDouble() - 0.5) * 0.3, bx, 0, bz);
        }
        if (ticksExisted % 2 == 0) {
            worldObj.spawnParticle("smoke", posX + w[0] * 1.15, y, posZ + w[1] * 1.15, bx * 0.5, 0.02, bz * 0.5);
        }
    }

    /** Client: the jump portal opening ahead of the ship - a growing ring of portal particles across its path. */
    private void portalSwirl(VehicleType t) {
        portalFx++;
        double grow = Math.min(1.0, portalFx / (double) SpaceMath.PORTAL_TICKS);
        double r = 1.0 + 4.0 * grow;
        double[] c = toWorld(t.halfLength + 3.0, 0);
        double[] side = toWorld(0, 1);
        double cy = posY + 3.0;
        for (int i = 0; i < 12; i++) {
            double a = rand.nextDouble() * Math.PI * 2;
            double ox = side[0] * Math.cos(a) * r, oz = side[1] * Math.cos(a) * r, oy = Math.sin(a) * r;
            worldObj.spawnParticle("portal", posX + c[0] + ox, cy + oy, posZ + c[1] + oz, -ox * 0.3, -oy * 0.3, -oz * 0.3);
        }
    }

    /** Client: the bore cone's angle at partial tick pt (degrees). */
    public float drillAngle(float pt) {
        return prevDrillAngle + MathHelper.wrapAngleTo180_float(drillAngle - prevDrillAngle) * pt;
    }

    /** Client: the Singular Ship's rings at partial tick pt (degrees). */
    public float coreAngle(float pt) {
        return prevCoreAngle + MathHelper.wrapAngleTo180_float(coreAngle - prevCoreAngle) * pt;
    }

    /** Client: the rotor / propeller angle at partial tick pt (degrees). */
    public float rotorAngle(float pt) {
        return prevRotorAngle + MathHelper.wrapAngleTo180_float(rotorAngle - prevRotorAngle) * pt;
    }

    /** Client: rotor spin rate, deg/tick (the renderer's motion-blur disc). */
    public float rotorRate() {
        return rotorRate;
    }

    /** Correction from the server (client thread). */
    public void applyCorrection(double x, double y, double z, float yaw) {
        setPosition(x, y, z);
        rotationYaw = yaw;
        prevRotationYaw = yaw;
        if (!getVehicleType().movement.flies) {
            speed = 0;                                              // aircraft keep their airspeed (a stop = a stall)
        }
        motionX = motionY = motionZ = 0;
        sentX = x;
        sentY = y;
        sentZ = z;
        sentYaw = yaw;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int inc) {
        EntityPlayer local = SVAMod.proxy.localPlayer();
        if (local != null && riddenByEntity == local) {
            return;                                                 // the driver's own simulation leads
        }
        lx = x;
        ly = y;
        lz = z;
        lerpSteps = 3;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void setVelocity(double x, double y, double z) {
        // positions are interpolated; velocity packets are not used
    }

    // ------------------------------------------------------------------ seats

    private void maintainSeats(VehicleType t) {
        int n = Math.max(0, t.seats - 1);
        if (seats == null || seats.length != n) {
            seats = new EntitySeatSVA[n];
        }
        if (ticksExisted < 2) {
            return;
        }
        for (int i = 0; i < n; i++) {
            if (seats[i] == null || seats[i].isDead) {
                EntitySeatSVA s = new EntitySeatSVA(worldObj, this, i + 1);
                double[] p = seatPos(i + 1);
                s.setPosition(p[0], p[1], p[2]);
                worldObj.spawnEntityInWorld(s);
                seats[i] = s;
            }
        }
    }

    private void placeSeats() {
        List<EntitySeatSVA> list;
        if (worldObj.isRemote) {
            list = clientSeats;
        } else {
            list = new ArrayList<EntitySeatSVA>();
            if (seats != null) {
                for (EntitySeatSVA s : seats) {
                    if (s != null) {
                        list.add(s);
                    }
                }
            }
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            EntitySeatSVA s = list.get(i);
            if (s.isDead) {
                if (worldObj.isRemote) {
                    list.remove(i);
                }
                continue;
            }
            double[] p = seatPos(s.getIndex());
            s.setPosition(p[0], p[1], p[2]);
            s.rotationYaw = rotationYaw;
            if (s.riddenByEntity != null) {
                s.updateRiderPosition();
            }
        }
    }

    public EntitySeatSVA freeSeat() {
        if (seats != null) {
            for (EntitySeatSVA s : seats) {
                if (s != null && !s.isDead && s.riddenByEntity == null) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Is `e` in this vehicle (driver or a seat)? */
    public boolean carries(Entity e) {
        return e != null && (e.ridingEntity == this || e.ridingEntity instanceof EntitySeatSVA && ((EntitySeatSVA) e.ridingEntity).getParent() == this);
    }

    /** Moves an entity that just got off to a free spot beside the vehicle (side -1 left, +1 right). */
    void placeBeside(Entity e, int side) {
        VehicleType t = getVehicleType();
        double sx = t.seatOffsets[0][0];
        double[][] spots = {{sx, side * (t.width * 0.5 + 0.6)}, {sx, -side * (t.width * 0.5 + 0.6)}, {-t.halfLength - 0.7, 0}, {t.halfLength + 0.7, 0}};
        for (double[] o : spots) {
            double[] w = toWorld(o[0], o[1]);
            for (int up = 0; up <= 1; up++) {
                double x = posX + w[0], y = Math.floor(posY) + up + 0.01, z = posZ + w[1];
                double hw = e.width / 2;
                AxisAlignedBB bb = AxisAlignedBB.getBoundingBox(x - hw, y, z - hw, x + hw, y + e.height, z + hw);
                if (worldObj.getCollidingBoundingBoxes(e, bb).isEmpty() && !worldObj.isAnyLiquid(bb)) {
                    if (e instanceof EntityPlayerMP && ((EntityPlayerMP) e).playerNetServerHandler != null) {
                        ((EntityPlayerMP) e).playerNetServerHandler.setPlayerLocation(x, y, z, e.rotationYaw, e.rotationPitch);
                    } else {
                        e.setPosition(x, y + e.yOffset, z);
                    }
                    return;
                }
            }
        }
    }

    public void dismountAll() {
        if (riddenByEntity != null) {
            riddenByEntity.mountEntity(null);
        }
        if (seats != null) {
            for (EntitySeatSVA s : seats) {
                if (s != null && s.riddenByEntity != null) {
                    s.riddenByEntity.mountEntity(null);
                }
            }
        }
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public boolean interactFirst(EntityPlayer p) {
        if (worldObj.isRemote) {
            return true;
        }
        if (carries(p)) {
            return true;
        }
        ItemStack held = p.getCurrentEquippedItem();
        if (state.type.gases.any() && isGasContainer(held)) {
            // anyone may refuel (harmless): a full cell / bucket / tank of a gas pours into its tank
            com.sc.util.FluidHandSC.use(worldObj, MathHelper.floor_double(posX), MathHelper.floor_double(posY),
                    MathHelper.floor_double(posZ), p, tanks(), held);
            syncWatcher();
            return true;
        }
        if (held != null && held.getItem() instanceof com.sc.item.ItemWrenchSC && p.isSneaking()) {
            if (canManage(p)) {
                pickUp(p);
            } else {
                msg(p, "sva.msg.notOwner");
            }
            return true;
        }
        if (held != null && ModContentSVA.isSteelIngot(held) && ConfigSVA.wear && state.hp < state.type.maxHp) {
            // TEMPORARY (stage 1): a steel ingot repairs 10 % - the Garage takes this over in stage 2
            state.hp = VehicleMath.repair(state.hp, state.type.maxHp);
            if (!p.capabilities.isCreativeMode) {
                held.stackSize--;
                if (held.stackSize <= 0) {
                    p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
                }
            }
            worldObj.playSoundAtEntity(this, "random.anvil_use", 0.6F, 1.2F);
            msg(p, "sva.msg.repaired", Integer.valueOf(Math.round(state.hp * 100 / state.type.maxHp)));
            syncWatcher();
            return true;
        }
        if (held != null && held.getItem() instanceof com.sva.item.ItemTrailerSVA && state.type.trailerSlots > 0) {
            if (attachTrailer(p, held) == CrawlerMath.OK) {
                p.inventory.setInventorySlotContents(p.inventory.currentItem, null);      // its cargo is in the crawler now
            }
            return true;
        }
        if (held != null && com.sc.item.ItemCoordinatorSC.isCoordinator(held) && state.type.autoDelivery) {
            if (canManage(p)) {
                routeFromCoordinator(p, held);
            } else {
                msg(p, "sva.msg.notOwner");
            }
            return true;
        }
        if (held != null && state.type.hasDrill && DrillLogicSVA.isHead(held) && !p.isSneaking()) {
            installHead(p, held);
            return true;
        }
        if (p.isSneaking()) {
            if (canManage(p)) {
                p.openGui(SVAMod.instance, GuiHandlerSVA.CARGO, worldObj, getEntityId(), 0, 0);
            } else {
                msg(p, "sva.msg.notOwner");
            }
            return true;
        }
        boolean driver = canDrive(p);
        if (driver && riddenByEntity == null) {
            p.mountEntity(this);
            if (isWrecked()) {
                msg(p, "sva.msg.wrecked");
            }
            return true;
        }
        EntitySeatSVA s = freeSeat();
        if (s != null) {
            p.mountEntity(s);
            if (!driver && riddenByEntity == null) {
                msg(p, "sva.msg.passengerOnly");
            }
            return true;
        }
        msg(p, "sva.msg.noSeat");
        return true;
    }

    /** Pick up / open cargo: the owner, or an op in creative. */
    public boolean canManage(EntityPlayer p) {
        if (state.ownerId == null || state.ownerId.length() == 0 || state.isOwner(p.getUniqueID())) {
            return true;
        }
        return p.capabilities.isCreativeMode && p instanceof EntityPlayerMP
                && net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().func_152596_g(((EntityPlayerMP) p).getGameProfile());
    }

    /** Driver's seat: the owner only (a key card widens this in stage 2). */
    public boolean canDrive(EntityPlayer p) {
        return state.ownerId == null || state.ownerId.length() == 0 || state.isOwner(p.getUniqueID());
    }

    public ItemStack toItemStack() {
        ItemStack s = new ItemStack(ModContentSVA.vehicle, 1, state.type.ordinal());
        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound st = new NBTTagCompound();
        state.writeNBT(st);
        root.setTag(NBT_ROOT, st);
        s.setTagCompound(root);
        return s;
    }

    /** Pick-up button results. */
    public static final int PICK_OK = 0, PICK_NOT_OWNER = 1, PICK_OCCUPIED = 2, PICK_FAR = 3;

    /** Pure: may the cargo screen's «Убрать в инвентарь» go ahead (wrecked vehicles may be picked up too)? */
    public static int pickUpCheck(boolean manage, boolean occupied, double dist2) {
        if (dist2 > 64 || Double.isNaN(dist2)) {
            return PICK_FAR;
        }
        if (!manage) {
            return PICK_NOT_OWNER;
        }
        return occupied ? PICK_OCCUPIED : PICK_OK;
    }

    /** Anyone seated (driver or a passenger seat)? */
    public boolean occupied() {
        if (riddenByEntity != null) {
            return true;
        }
        if (seats != null) {
            for (EntitySeatSVA s : seats) {
                if (s != null && !s.isDead && s.riddenByEntity != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The cargo screen's pick-up button (server, from the packet): owner / op only, nobody seated. */
    public int tryPickUp(EntityPlayer p) {
        if (isDead || worldObj.isRemote) {
            return PICK_FAR;
        }
        int r = pickUpCheck(canManage(p), occupied(), p.getDistanceSqToEntity(this));
        if (r == PICK_NOT_OWNER) {
            msg(p, "sva.msg.notOwner");
        } else if (r == PICK_OCCUPIED) {
            msg(p, "sva.msg.occupied");
        } else if (r == PICK_OK) {
            pickUp(p);
        }
        return r;
    }

    /** Into an item with everything (server): riders off, cargo stays inside the item. */
    public void pickUp(EntityPlayer p) {
        dismountAll();
        ItemStack s = toItemStack();
        if (!p.inventory.addItemStackToInventory(s)) {
            entityDropItem(s, 0.5F);
        }
        p.inventoryContainer.detectAndSendChanges();
        worldObj.playSoundAtEntity(this, "random.pop", 0.6F, 0.8F);
        setDead();
    }

    @Override
    public void setDead() {
        super.setDead();
        if (worldObj != null && !worldObj.isRemote) {
            VehicleRegistrySVA r = VehicleRegistrySVA.get(worldObj);
            if (r != null) {
                r.remove(state.vehId);
            }
            if (seats != null) {
                for (EntitySeatSVA s : seats) {
                    if (s != null) {
                        s.setDead();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ damage

    @Override
    public boolean attackEntityFrom(DamageSource src, float amount) {
        if (worldObj.isRemote || isDead) {
            return false;
        }
        if (src == DamageSource.outOfWorld) {
            dismountAll();
            setDead();
            return true;
        }
        if (isEntityInvulnerable() || src.isFireDamage() || !ConfigSVA.wear) {
            return false;
        }
        Entity a = src.getEntity();
        if (a != null && carries(a)) {
            return false;                                           // own riders
        }
        if (a instanceof EntityPlayer && com.sc.ShieldEventHandler.privateFieldAgainst(worldObj, (EntityPlayer) a,
                MathHelper.floor_double(posX), MathHelper.floor_double(posY), MathHelper.floor_double(posZ)) != null) {
            return false;                                           // parked in a private field that refuses this player
        }
        damageHull(amount);
        worldObj.playSoundAtEntity(this, "mob.irongolem.hit", 0.5F, 1.3F);
        return true;
    }

    /** Hull damage (server). */
    public void damageHull(float amount) {
        if (!ConfigSVA.wear) {
            return;
        }
        amount = DrillMath.fieldDamage(amount, fieldNow, state.type.gases.fieldReduction);   // the argon field
        boolean was = VehicleMath.wrecked(state.hp);
        state.hp = VehicleMath.damage(state.hp, amount);
        if (!was && VehicleMath.wrecked(state.hp)) {
            tellRiders("sva.msg.wrecked");
            worldObj.playSoundAtEntity(this, "random.explode", 0.5F, 1.4F);
        }
        syncWatcher();
    }

    private void tellRiders(String key) {
        if (riddenByEntity instanceof EntityPlayer) {
            msg((EntityPlayer) riddenByEntity, key);
        }
    }

    private static void msg(EntityPlayer p, String key, Object... args) {
        p.addChatComponentMessage(new ChatComponentTranslation(key, args));
    }

    // ------------------------------------------------------------------ stage C: trailer, route, drill, actions

    /** Hitches a trailer item (its cargo goes into the slots after the body's). @return CrawlerMath.OK / why not */
    public int attachTrailer(EntityPlayer p, ItemStack item) {
        int r = CrawlerMath.attachCheck(state.type.trailerSlots, state.trailer, canManage(p));
        if (r != CrawlerMath.OK) {
            msg(p, r == CrawlerMath.NOT_OWNER ? "sva.msg.notOwner" : "sva.msg.trailer.already");
            return r;
        }
        ItemStack[] c = com.sva.item.ItemTrailerSVA.cargoOf(item);
        int base = state.type.cargoSlots;
        state.setTrailer(true);
        for (int i = 0; i < c.length && base + i < state.cargo.length; i++) {
            state.cargo[base + i] = c[i];
        }
        worldObj.playSoundAtEntity(this, "random.anvil_land", 0.4F, 1.6F);
        msg(p, "sva.msg.trailer.attached", Integer.valueOf(state.cargoSize()));
        syncWatcher();
        return r;
    }

    /** Unhitches the trailer (no checks): @return the trailer item holding its cargo. */
    public ItemStack detachTrailer() {
        int base = state.type.cargoSlots;
        ItemStack[] c = new ItemStack[state.type.trailerSlots];
        for (int i = 0; i < c.length && base + i < state.cargo.length; i++) {
            c[i] = state.cargo[base + i];
        }
        state.setTrailer(false);
        syncWatcher();
        return com.sva.item.ItemTrailerSVA.withCargo(new ItemStack(ModContentSVA.trailer), c);
    }

    /** A Coordinator's point becomes route point A, the next one B, then A again (same dimension only). */
    public void routeFromCoordinator(EntityPlayer p, ItemStack coord) {
        int[] pt = com.sc.bridge.BridgeItemDataSC.point(coord);
        if (pt == null || pt.length < 4) {
            msg(p, "sva.msg.route.empty");
            return;
        }
        if (pt[3] != worldObj.provider.dimensionId) {
            msg(p, "sva.msg.route.otherDim");
            return;
        }
        boolean b = setRoutePoint(pt);
        msg(p, b ? "sva.msg.route.b" : "sva.msg.route.a", Integer.valueOf(pt[0]), Integer.valueOf(pt[1]), Integer.valueOf(pt[2]));
    }

    /** Sets A, then B, then A... (a new point stops the auto-delivery). @return true if it set B */
    public boolean setRoutePoint(int[] pt) {
        int[] copy = {pt[0], pt[1], pt[2], pt[3]};
        boolean b = state.routeNextB && state.routeA != null;
        if (b) {
            state.routeB = copy;
        } else {
            state.routeA = copy;
        }
        state.routeNextB = !b;
        if (state.autoOn) {
            setAuto(false);
        }
        syncWatcher();
        return b;
    }

    /** Auto-delivery on (needs both points here) / off. @return on now */
    public boolean setAuto(boolean on) {
        autoBlocked = 0;
        autoTimer = 0;
        if (on) {
            int dim = worldObj.provider.dimensionId;
            autoReason = AutoDelivery.startReason(state.routeA, state.routeB, dim);
            state.autoOn = autoReason == AutoDelivery.R_NONE;
            state.autoState = state.autoOn ? AutoDelivery.start(state.routeA, state.routeB, dim) : AutoDelivery.OFF;
        } else {
            state.autoOn = false;
            state.autoState = AutoDelivery.OFF;
            autoReason = AutoDelivery.R_NONE;
        }
        syncWatcher();
        return state.autoOn;
    }

    /** The bore stops (no head / cargo full / head worn out) and the driver is told. */
    void drillStop(String key) {
        if (drillOn) {
            drillOn = false;
            tellRiders(key);
            syncWatcher();
        }
    }

    /** A warning to the driver at most every 5 s. */
    void warnDriver(String key, Object... args) {
        long now = worldObj.getTotalWorldTime();
        if (now - warnTick >= 100) {
            warnTick = now;
            if (riddenByEntity instanceof EntityPlayer) {
                msg((EntityPlayer) riddenByEntity, key, args);
            }
        }
    }

    /** A chat line to every player aboard. */
    void tellAll(String key, Object... args) {
        tellAll(new ChatComponentTranslation(key, args));
    }

    void tellAll(net.minecraft.util.IChatComponent c) {
        for (Entity e : riders()) {
            if (e instanceof EntityPlayer) {
                ((EntityPlayer) e).addChatComponentMessage(c);
            }
        }
    }

    /** Pure: may player act on this vehicle (owner / op, or its driver for the drill keys)? */
    public static boolean actionAllowed(int action, boolean manage, boolean driver) {
        if (action == A_DRILL_TOGGLE || action == A_DRILL_MODE || action >= A_MODE_SET && action < A_MODE_SET + DrillMath.MODES) {
            return manage || driver;
        }
        if (action == A_FLIGHT_MODE || action >= A_LONG_RANGE && action <= A_INVIS) {
            return driver;                                          // only the pilot turns the nacelles / works the ship
        }
        return manage;
    }

    /** The Action packet (server thread; the sender is near or seated): a cargo screen button or a drill key. */
    public void serverAction(EntityPlayerMP p, int a) {
        long now = worldObj.getTotalWorldTime();
        if (now - lastActionTick < 4) {
            return;                                                 // one press, not a flood
        }
        lastActionTick = now;
        if (!actionAllowed(a, canManage(p), riddenByEntity == p)) {
            msg(p, "sva.msg.notOwner");
            return;
        }
        VehicleType t = state.type;
        if (a == A_SORT) {
            msg(p, "sva.msg.cargo.sorted", Integer.valueOf(sortCargo()));
            return;
        }
        if (a == A_JUNK) {
            int n = dumpJunk();
            msg(p, n > 0 ? (t.hasDrill ? "sva.msg.cargo.junkDrill" : "sva.msg.cargo.junk") : "sva.msg.cargo.noJunk", Integer.valueOf(n),
                    Integer.valueOf(JUNK_KEEP_COBBLE));
            return;
        }
        if (a == A_CARGO) {
            if (carries(p)) {                                       // the key works from a seat only
                p.openGui(SVAMod.instance, GuiHandlerSVA.CARGO, worldObj, getEntityId(), 0, 0);
            }
            return;
        }
        if (a == A_DRILL_TOGGLE && t.hasDrill) {
            if (!drillOn && !DrillLogicSVA.isHead(state.drillHead)) {
                msg(p, "sva.msg.drill.noHead");
            } else {
                drillOn = !drillOn;
                msg(p, drillOn ? "sva.msg.drill.on" : "sva.msg.drill.off");
            }
        } else if (a >= A_MODE_SET && a < A_MODE_SET + DrillMath.MODES && t.hasDrill) {
            if (state.drillMode != a - A_MODE_SET) {
                state.drillMode = a - A_MODE_SET;
                p.addChatComponentMessage(new ChatComponentTranslation("sva.msg.drill.mode", new ChatComponentTranslation("sva.drill.mode." + state.drillMode)));
            }
        } else if (a == A_DRILL_MODE && t.hasDrill) {
            state.drillMode = DrillMath.nextMode(state.drillMode);
            p.addChatComponentMessage(new ChatComponentTranslation("sva.msg.drill.mode", new ChatComponentTranslation("sva.drill.mode." + state.drillMode)));
        } else if (a == A_AUTO_TOGGLE && t.autoDelivery) {
            boolean on = setAuto(!state.autoOn);
            msg(p, on ? "sva.msg.auto.on" : state.routeA == null || state.routeB == null ? "sva.msg.auto.noRoute" : "sva.msg.auto.off");
        } else if (a == A_ROUTE_CLEAR && t.autoDelivery) {
            state.routeA = null;
            state.routeB = null;
            state.routeNextB = false;
            setAuto(false);
            msg(p, "sva.msg.route.cleared");
        } else if (a == A_FLIGHT_MODE && t.movement == Movement.TILT) {
            int r = toggleFlightMode();
            msg(p, r == TiltMath.OK ? (state.tiltPlane ? "sva.msg.tilt.toPlane" : "sva.msg.tilt.toVertical") : "sva.msg.tilt.no." + r,
                    Integer.valueOf(VehicleMath.kmh(TiltMath.TO_PLANE_SPEED)), Integer.valueOf((int) TiltMath.MIN_HEIGHT),
                    Integer.valueOf(VehicleMath.kmh(t.plane.stall * TiltMath.BACK_MUL)));
        } else if (a == A_LONG_RANGE && t.longRange) {
            msg(p, toggleLongRange() ? "sva.msg.long.on" : "sva.msg.long.off", Integer.valueOf((int) SpaceMath.LONG_Y),
                    Integer.valueOf(VehicleMath.kmh(t.topSpeed * SpaceMath.LONG_SPEED)));
        } else if (a == A_JUMP && t.singular) {
            SpaceLogicSVA.startJump(this);
        } else if (a == A_JUMP_TARGET && t.singular) {
            state.jumpHome = !state.jumpHome;
            msg(p, state.jumpHome ? "sva.msg.jump.target.home" : "sva.msg.jump.target.coord");
        } else if (a == A_ANCHOR && t.singular) {
            int r = toggleAnchor();
            msg(p, r == 1 ? "sva.msg.anchor.on" : r == 0 ? "sva.msg.anchor.off" : "sva.msg.anchor.empty");
        } else if (a == A_INVIS && t.singular) {
            int r = toggleInvisible();
            msg(p, r == 1 ? "sva.msg.invis.on" : r == 0 ? "sva.msg.invis.off" : "sva.msg.invis.empty");
        } else if (a == A_TRAILER_DETACH && t.trailerSlots > 0) {
            if (CrawlerMath.detachCheck(state.trailer, true) == CrawlerMath.OK) {
                ItemStack item = detachTrailer();
                p.closeScreen();
                if (!p.inventory.addItemStackToInventory(item)) {
                    p.dropPlayerItemWithRandomChoice(item, false);
                }
                p.inventoryContainer.detectAndSendChanges();
                msg(p, "sva.msg.trailer.detached");
            }
        }
        spaceDirty = true;
        syncWatcher();
    }

    // ------------------------------------------------------------------ stage E: shuttle / ship keys and helpers

    /** «Дальний режим» on / off (server). @return on now */
    public boolean toggleLongRange() {
        state.longRange = state.type.longRange && !state.longRange;
        syncWatcher();
        return state.longRange;
    }

    /** The gravity anchor on / off (server). @return 1 on, 0 off, -1 no EU */
    public int toggleAnchor() {
        if (!state.type.singular) {
            return -1;
        }
        if (!state.anchor && state.energy <= 0) {
            return -1;
        }
        state.anchor = !state.anchor;
        speed = 0;
        motionX = motionY = motionZ = 0;
        syncWatcher();
        return state.anchor ? 1 : 0;
    }

    /** Invisibility for mobs on / off (server). @return 1 on, 0 off, -1 no singular matter */
    public int toggleInvisible() {
        if (!state.type.singular || !state.invisible && state.gas(Gas.SINGULAR_MATTER) <= 0) {
            return -1;
        }
        state.invisible = !state.invisible;
        if (state.invisible) {
            SpaceLogicSVA.clearTargets(this);
        }
        syncWatcher();
        return state.invisible ? 1 : 0;
    }

    /** The jump (server; the «Прыжок» key, the world test). @return SpaceMath.OK or why not */
    public int startJump() {
        int r = SpaceLogicSVA.startJump(this);
        syncWatcher();
        return r;
    }

    /** Ticks left of the jump portal's opening (server). */
    public int portalTicks() {
        return portalTicks;
    }

    /** Riders by seat: [0] the driver, [i] passenger seat i (server). */
    Entity[] ridersBySeat() {
        Entity[] out = new Entity[Math.max(1, state.type.seats)];
        out[0] = riddenByEntity;
        if (seats != null) {
            for (EntitySeatSVA s : seats) {
                if (s != null && !s.isDead && s.riddenByEntity != null && s.getIndex() < out.length) {
                    out[s.getIndex()] = s.riddenByEntity;
                }
            }
        }
        return out;
    }

    /** The entity a rider of seat i mounts: this (0) or passenger seat i; null while the seats are not there yet. */
    Entity seatEntity(int i) {
        if (i <= 0) {
            return this;
        }
        if (seats == null || i - 1 >= seats.length || seats[i - 1] == null || seats[i - 1].isDead) {
            return null;
        }
        return seats[i - 1];
    }

    /** A rider carried over to this (new) vehicle, to be seated on seat i (VehicleTransferSVA). */
    void addPending(Entity e, int seat) {
        if (pendingRiders == null) {
            pendingRiders = new ArrayList<Object[]>();
            pendingAge = 0;
        }
        pendingRiders.add(new Object[] {e, Integer.valueOf(seat)});
    }

    /** Riders waiting for their seats after a transfer (server; the world test). */
    public int pendingCount() {
        return pendingRiders == null ? 0 : pendingRiders.size();
    }

    // ------------------------------------------------------------------ cargo helpers (drill drops, auto-delivery)

    /** The drill keeps this much cobblestone when the junk goes out (it walls up lava / water with it). */
    public static final int JUNK_KEEP_COBBLE = 64;

    /**
     * «Сорт.» (server): equal stacks merged, then ordered by item id / meta / name (CargoToolsSVA). The crawler sorts
     * its body and its trailer apart (the trailer's slots leave with it). @return stacks in the cargo after
     */
    public int sortCargo() {
        ItemStack[] c = state.cargo;
        int page = state.type.trailerSlots > 0 ? CrawlerMath.PAGE : c.length;
        int n = 0;
        for (int from = 0; from < c.length; from += Math.max(1, page)) {
            n += com.sva.inventory.CargoToolsSVA.sortRange(c, from, Math.min(c.length, from + page), getInventoryStackLimit());
        }
        markDirty();
        return n;
    }

    /**
     * «Мусор» (server): the ConfigSVA.junk items are dropped as items on the vehicle's right side (1 block out of the
     * hull), the drill keeps JUNK_KEEP_COBBLE cobblestone. Merged first, so it is few item entities. @return items dropped
     */
    public int dumpJunk() {
        sortCargo();
        java.util.List<ItemStack> out = com.sva.inventory.CargoToolsSVA.takeJunk(state.cargo, 0, state.cargo.length, ConfigSVA.junk,
                state.type.hasDrill ? "minecraft:cobblestone" : null, JUNK_KEEP_COBBLE);
        int n = 0;
        double[] o = toWorld(0, state.type.width / 2.0 + 1.0);
        for (ItemStack s : out) {
            n += s.stackSize;
            net.minecraft.entity.item.EntityItem e = new net.minecraft.entity.item.EntityItem(worldObj, posX + o[0], posY + 0.5, posZ + o[1], s);
            e.motionX = e.motionY = e.motionZ = 0;
            e.delayBeforeCanPickup = 20;
            worldObj.spawnEntityInWorld(e);
        }
        if (n > 0) {
            sortCargo();
        }
        markDirty();
        return n;
    }

    /**
     * RMB on the drill vehicle with a quarry drill head in hand (outside, not sneaking; owner / op): it goes into the
     * head slot, the old one into the hand. @return true when it was installed
     */
    public boolean installHead(EntityPlayer p, ItemStack held) {
        if (!state.type.hasDrill || !DrillLogicSVA.isHead(held)) {
            return false;
        }
        if (!canManage(p)) {
            msg(p, "sva.msg.notOwner");
            return false;
        }
        ItemStack old = state.drillHead;
        ItemStack put = held.splitStack(1);
        state.drillHead = put;
        if (held.stackSize <= 0) {
            p.inventory.setInventorySlotContents(p.inventory.currentItem, old);
        } else if (old != null && !p.inventory.addItemStackToInventory(old)) {
            p.dropPlayerItemWithRandomChoice(old, false);
        }
        p.inventoryContainer.detectAndSendChanges();
        markDirty();
        worldObj.playSoundAtEntity(this, "random.anvil_use", 0.5F, 1.4F);
        if (old != null) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.msg.drill.headSwapped", put.func_151000_E(), old.func_151000_E()));
        } else {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.msg.drill.headInstalled", put.func_151000_E()));
        }
        return true;
    }

    /** Puts `s` into the cargo, merging first; `s.stackSize` is left with what did not fit. @return that */
    public int storeCargo(ItemStack s) {
        ItemStack[] c = state.cargo;
        int limit = Math.min(getInventoryStackLimit(), s.getMaxStackSize());
        for (int pass = 0; pass < 2 && s.stackSize > 0; pass++) {
            for (int i = 0; i < c.length && s.stackSize > 0; i++) {
                if (pass == 0 && c[i] != null && c[i].isItemEqual(s) && ItemStack.areItemStackTagsEqual(c[i], s) && c[i].stackSize < limit) {
                    int put = Math.min(s.stackSize, limit - c[i].stackSize);
                    c[i].stackSize += put;
                    s.stackSize -= put;
                } else if (pass == 1 && c[i] == null) {
                    ItemStack put = s.copy();
                    put.stackSize = Math.min(s.stackSize, limit);
                    c[i] = put;
                    s.stackSize -= put.stackSize;
                }
            }
        }
        markDirty();
        return s.stackSize;
    }

    /** Takes `n` of `item` (any damage) out of the cargo: all or nothing. */
    public boolean takeCargo(net.minecraft.item.Item item, int n) {
        ItemStack[] c = state.cargo;
        int have = 0;
        for (ItemStack s : c) {
            if (s != null && s.getItem() == item) {
                have += s.stackSize;
            }
        }
        if (have < n) {
            return false;
        }
        for (int i = c.length - 1; i >= 0 && n > 0; i--) {
            if (c[i] != null && c[i].getItem() == item) {
                int take = Math.min(n, c[i].stackSize);
                c[i].stackSize -= take;
                n -= take;
                if (c[i].stackSize <= 0) {
                    c[i] = null;
                }
            }
        }
        markDirty();
        return true;
    }

    /** An empty cargo slot left. */
    public boolean cargoHasRoom() {
        for (ItemStack s : state.cargo) {
            if (s == null) {
                return true;
            }
        }
        return false;
    }

    public boolean cargoEmpty() {
        for (ItemStack s : state.cargo) {
            if (s != null) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ gases (refuelling)

    /** A full container of one of the core gases (FluidContainerRegistry or IFluidContainerItem). */
    public static boolean isGasContainer(ItemStack held) {
        if (held == null || !com.sc.util.FluidHandSC.isContainer(held)) {
            return false;
        }
        FluidStack f = FluidContainerRegistry.getFluidForFilledItem(held);
        if (f == null && held.getItem() instanceof IFluidContainerItem) {
            f = ((IFluidContainerItem) held.getItem()).getFluid(held);
        }
        return f != null && f.amount > 0 && Gas.of(f.getFluid()) != null;
    }

    /** The tanks as a fluid handler: filled by hand (FluidHandSC) and the Gas Column; never drained from outside. */
    public IFluidHandler tanks() {
        return new Tanks();
    }

    /** mB of `g` this vehicle still takes. */
    public int gasRoom(Gas g) {
        return state.fillGas(g, Integer.MAX_VALUE, true);
    }

    /** Puts gas in (server). @return mB taken */
    public int addGas(Gas g, int mb) {
        int put = state.fillGas(g, mb, false);
        if (put > 0) {
            dataWatcher.updateObject(DW_GAS, gasString(state));
        }
        return put;
    }

    private class Tanks implements IFluidHandler {
        @Override
        public int fill(ForgeDirection from, FluidStack res, boolean doFill) {
            Gas g = res == null ? null : Gas.of(res.getFluid());
            if (g == null || res.amount <= 0) {
                return 0;
            }
            return doFill ? addGas(g, res.amount) : state.fillGas(g, res.amount, true);
        }

        @Override
        public FluidStack drain(ForgeDirection from, FluidStack res, boolean doDrain) {
            return null;
        }

        @Override
        public FluidStack drain(ForgeDirection from, int max, boolean doDrain) {
            return null;
        }

        @Override
        public boolean canFill(ForgeDirection from, Fluid fluid) {
            Gas g = Gas.of(fluid);
            return g != null && state.gasCapacity(g) > 0;
        }

        @Override
        public boolean canDrain(ForgeDirection from, Fluid fluid) {
            return false;
        }

        @Override
        public FluidTankInfo[] getTankInfo(ForgeDirection from) {
            List<Gas> gs = state.type.gases.gases();
            FluidTankInfo[] out = new FluidTankInfo[gs.size()];
            for (int i = 0; i < out.length; i++) {
                Gas g = gs.get(i);
                Fluid f = g.fluidOf();
                out[i] = new FluidTankInfo(f == null || state.gas(g) <= 0 ? null : new FluidStack(f, state.gas(g)), state.gasCapacity(g));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ energy (charger)

    /** @return EU taken */
    public double addEnergy(double eu) {
        double got = state.addEnergy(eu);
        if (got > 0) {
            dataWatcher.updateObject(DW_ENERGY, Integer.valueOf((int) state.energy));
        }
        return got;
    }

    public double energyRoom() {
        return Math.max(0, state.maxEnergy() - state.energy);
    }

    // ------------------------------------------------------------------ NBT

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {
        NBTTagCompound st = tag.getCompoundTag(NBT_ROOT);
        VehicleType t = VehicleType.byOrdinal(st.getInteger(VehicleState.K_TYPE));
        state = new VehicleState(t);
        state.readNBT(st);
        applySize(t);
        syncWatcher();
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
        NBTTagCompound st = new NBTTagCompound();
        state.writeNBT(st);
        tag.setTag(NBT_ROOT, st);
    }

    // ------------------------------------------------------------------ IInventory (cargo)

    /** The drill vehicle's head and module slots come after the cargo. */
    private int extraSlots() {
        return getVehicleType().hasDrill ? 2 : 0;
    }

    /** Index of the drill head slot (the module slot is the next one). */
    public int headSlot() {
        clientState();
        return state.cargo.length;
    }

    /** Client: the local state follows the synced type and trailer (the cargo screen's slots). */
    private void clientState() {
        if (worldObj != null && worldObj.isRemote) {
            VehicleType t = getVehicleType();
            boolean tr = hasTrailer();
            if (state.type != t || state.trailer != tr) {
                VehicleState n = new VehicleState(t);
                n.setTrailer(tr);
                state = n;
            }
        }
    }

    @Override
    public int getSizeInventory() {
        clientState();
        return state.cargo.length + extraSlots();
    }

    @Override
    public ItemStack getStackInSlot(int i) {
        clientState();
        int n = state.cargo.length;
        if (i >= 0 && i < n) {
            return state.cargo[i];
        }
        if (extraSlots() > 0 && i == n) {
            return state.drillHead;
        }
        if (extraSlots() > 0 && i == n + 1) {
            return state.drillModule;
        }
        return null;
    }

    @Override
    public ItemStack decrStackSize(int i, int n) {
        ItemStack s = getStackInSlot(i);
        if (s == null) {
            return null;
        }
        if (s.stackSize <= n) {
            setInventorySlotContents(i, null);
            return s;
        }
        ItemStack part = s.splitStack(n);
        markDirty();
        return part;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int i) {
        return null;
    }

    @Override
    public void setInventorySlotContents(int i, ItemStack s) {
        clientState();
        int n = state.cargo.length;
        if (s != null && s.stackSize > getInventoryStackLimit()) {
            s.stackSize = getInventoryStackLimit();
        }
        if (i >= 0 && i < n) {
            state.cargo[i] = s;
        } else if (extraSlots() > 0 && i == n) {
            state.drillHead = s;
            if (s == null && worldObj != null && !worldObj.isRemote) {
                drillStop("sva.msg.drill.noHead");
            }
        } else if (extraSlots() > 0 && i == n + 1) {
            state.drillModule = s;
        } else {
            return;
        }
        markDirty();
    }

    @Override
    public String getInventoryName() {
        return "container.siliconagevehicles.cargo";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public void markDirty() {
        if (worldObj != null && !worldObj.isRemote) {
            dataWatcher.updateObject(DW_CARGO, Byte.valueOf((byte) state.cargoUsed()));
            dataWatcher.updateObject(DW_EXTRA, Integer.valueOf(extraBits()));
            dataWatcher.updateObject(DW_HEAD, Integer.valueOf(headLeft()));
            dataWatcher.updateObject(DW_HEAD_MAX, Integer.valueOf(state.drillHead == null ? 0 : state.drillHead.getMaxDamage()));
        }
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer p) {
        return !isDead && p.getDistanceSqToEntity(this) < 64;
    }

    @Override
    public void openInventory() {
    }

    @Override
    public void closeInventory() {
    }

    @Override
    public boolean isItemValidForSlot(int i, ItemStack s) {
        int n = state.cargo.length;
        if (extraSlots() > 0 && i == n) {
            return s == null || DrillLogicSVA.isHead(s);
        }
        if (extraSlots() > 0 && i == n + 1) {
            return s == null || DrillLogicSVA.isWidener(s);
        }
        return s == null || !(s.getItem() instanceof com.sva.item.ItemVehicleSVA);      // no vehicles inside vehicles
    }
}
