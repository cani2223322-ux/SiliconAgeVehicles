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
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CrawlerMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.DrillMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HeliMath;
import com.sva.vehicle.HighLiftMath;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.HoverMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.RoadMath;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.RunwayMath;
import com.sva.vehicle.SpaceMath;
import com.sva.vehicle.TiltMath;
import com.sva.vehicle.TrailerMath;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.AccessMath;
import com.sva.vehicle.VehicleGases;
import com.sva.vehicle.FuelMath;
import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ISidedInventory;
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
public class EntityVehicleSVA extends Entity implements ISidedInventory {

    public static final String NBT_ROOT = "SVAVehicle";
    // data watcher ids (append-only)
    private static final int DW_ENERGY = 20, DW_HP = 21, DW_HEAT = 22, DW_FLAGS = 23, DW_CARGO = 24, DW_TYPE = 25,
            DW_CRUISE = 26, DW_YAW = 27, DW_GAS = 28, DW_THR = 29, DW_COST = 30,
            // stage C: packed bits (trailer, drill, auto-delivery - X_*), blocks dug, head life left / max, auto distance, route text
            DW_EXTRA = 31, DW_DUG = 19, DW_HEAD = 18, DW_HEAD_MAX = 17, DW_AUTO_DIST = 16, DW_ROUTE = 15,
            // stage E: the space panel's line (SpaceLogicSVA.hudString)
            DW_SPACE = 14,
            // cargo screen v2: the owner's name («Машина» tab), torches the drill has put down («Бур» tab)
            DW_OWNER = 13, DW_TORCHES = 12,
            // skins: the board number on the side decal (SkinMath.boardNumber of the vehicle's id)
            DW_BOARD = 11,
            // wave 2 (★8): the tuning slots, TuningMath.pack + TUNE_ON (the server's vehicles.tuning)
            DW_TUNE = 10,
            // wave 3 (★15): the hitched trailer's kind and contents (trailerString - parsed on clients)
            DW_TRAILER = 9,
            // wave 4 (★15 рекуперация): EU/t recovered this tick + the server's efficiency (RegenMath.pack)
            DW_REGEN = 8,
            // aircraft stage A (★1-★3): flap setting, airbrake, ground spoilers, slats, overspeed warning (HighLiftMath.pack)
            DW_HILIFT = 7,
            // aircraft stage C (★17 / ★19): the autopilot panel (AutopilotMath.pack / pack2) and the HSI's «База» point
            DW_AP = 6, DW_AP2 = 5, DW_BASE_X = 4, DW_BASE_Z = 3,
            // aircraft round 2, D2 (★6 / ★7 / ★17): the board systems' word (SystemsMath.pack) - the last free id (0 / 1 are Entity's)
            DW_SYS = 2;
    private static final int TUNE_ON = 1 << 20;
    /** DW_TUNE bits: the server's vehicles.cruise / vehicles.autopilot (★4 - the driver's client follows the server). */
    private static final int TUNE_CRUISE = 1 << 21, TUNE_AP = 1 << 22;
    /** DW_TUNE bit: the server's vehicles.locks (the cargo screen shows «Доступ» only with it). */
    private static final int TUNE_LOCKS = 1 << 23;
    /** Entity NBT (beside NBT_ROOT - never in the item): the bore on, the plane's throttle, why auto-delivery stopped / paused, the jump portal. */
    private static final String K_DRILL_ON = "SVADrillOn", K_THROTTLE = "SVAThrottle", K_AUTO_REASON = "SVAAutoReason",
            K_PORTAL_TICKS = "SVAPortalTicks", K_PORTAL = "SVAPortal",
            /** Stage A: the flap setting and the pilot's airbrake (as the throttle: the entity's, not the item's). */
            K_FLAPS = "SVAFlaps", K_AIRBRAKE = "SVAAirbrake",
            /** D2: the engine's state and the pitot's ice (the entity's - picked up, the engine is off and the ice gone). */
            K_ENGINE = "SVAEngine", K_ICE = "SVAIce",
            /** D5: the INS was aligned (an entity saved before D5 has no key: taken as aligned - it keeps it if still powered). */
            K_INS = "SVAInsAligned";
    /** «Передать владельца»: the new owner must be online within this many blocks of the vehicle. */
    public static final double TRANSFER_RANGE = 16;
    private static final int X_TRAILER = 1, X_DRILL = 2, X_MODE_SHIFT = 2, X_WIDE = 16, X_HEAD = 32, X_AUTO_SHIFT = 6, X_AUTO_ON = 512,
            X_REASON_SHIFT = 10, X_ROUTE_A = 1 << 13, X_ROUTE_B = 1 << 14, X_FIELD = 1 << 15,
            // stage D: the tiltrotor's wanted mode (airplane) and its nacelle step (0..40, 6 bits)
            X_TILT_PLANE = 1 << 16, X_TILT_SHIFT = 17,
            // stage E: long-range mode on / working, an open vortex near, held still (anchor / portal / riders boarding),
            // the anchor, invisibility, the jump portal opening
            X_LONG_ON = 1 << 23, X_LONG_ACTIVE = 1 << 24, X_DOCK_NEAR = 1 << 25, X_HELD = 1 << 26, X_ANCHOR = 1 << 27,
            X_INVIS = 1 << 28, X_PORTAL = 1 << 29,
            // wave 1 (★4): cruise / the autopilot engaged (the server's judgement of the driver's IN_CRUISE)
            X_CRUISE = 1 << 30;
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
            A_MODE_SET = 14,
            /**
             * Wave 3 (★15), the cargo screen's «Прицеп» tab (owner / the «Багажник» right): the tanker's «Заправить технику
             * рядом» and «Вылить» (Shift); the platform's «Погрузить ближайшую» and «Выгрузить».
             */
            A_TANK_REFUEL = 20, A_TANK_DUMP = 21, A_PLATFORM_LOAD = 22, A_PLATFORM_UNLOAD = 23,
            /** The cargo screen's «Машина» tab, «Доступ» (owner / op - canManage): the access screen without a key card. */
            A_ACCESS = 24,
            /** Aircraft stage A (the pilot): «Закрылки −» (retract a step), «Закрылки +» (extend), «Воздушный тормоз». */
            A_FLAPS_UP = 25, A_FLAPS_DOWN = 26, A_AIRBRAKE = 27,
            /**
             * Aircraft round 2, D2 (the pilot): «Запуск двигателя» - start (off) / stop (turning); an overhead switch -
             * A_SWITCH + SystemsMath.SW_* (30..37; D5: 37 = ИНС, the helicopter too) toggles it.
             */
            A_ENGINE = 28, A_SWITCH = 30,
            /**
             * Hydrogen wave: «Снять» - the battery out into the player's inventory (the engine off, standing; the owner or
             * the «Ремонт» right - the method checks, not actionAllowed).
             */
            A_BATTERY_OUT = 40,
            /** Air-defence wave (the pilot): the transponder's IDENT - the blip flashes 18 s on every radar. */
            A_IDENT = 41,
            /**
             * Liner wave 1: «Быстрый запуск» (the pilots; the «Запуск двигателя» key does the same on the liner), the L1 door
             * (the owner / anyone with the driving right, standing by or aboard), «Снять» АКБ 2 (as A_BATTERY_OUT), the
             * gear lever (the pilots: the «Шасси» key).
             */
            A_LINER_QUICK = 42, A_LINER_DOOR = 43, A_BATTERY2_OUT = 44, A_LINER_GEAR = 45,
            /** Liner wave 4 (the pilots): the sidestick's red AP disconnect, the thrust levers' instinctive A/THR disconnect. */
            A_LINER_APDISC = 46, A_LINER_ATHRDISC = 47;
    private static final int F_WRECKED = 1, F_HEAT_STOP = 2, F_WEAR = 4, F_LIFT = 8, F_BOOST = 16, F_CHARGING = 32,
            /** Aircraft: in the air without power (heli autorotation / plane glide); plane: stalling. */
            F_AUTO = 64, F_STALL = 128;
    /**
     * Input bits. IN_BRAKE = Space (ground: brake; hover: hold higher; heli: collective up; plane: pull up),
     * IN_BOOST = sprint (hover: boost; aircraft: down, like IN_DOWN), IN_DOWN = «Снижение» (heli: collective down; plane: push down),
     * IN_AFTER = «Форсаж» (stage D: the hoverbike's boost; the jet's / tiltrotor's afterburner - no longer «down»).
     */
    public static final int IN_FWD = 1, IN_BACK = 2, IN_LEFT = 4, IN_RIGHT = 8, IN_BRAKE = 16, IN_BOOST = 32, IN_DOWN = 64, IN_AFTER = 128,
            /**
             * Wave 1 (★4): «Круиз» engaged on the driver's client - cruise (ground / hover: hold cruiseSpeed) or the
             * autopilot (planes: hold apYaw / apAlt). The targets ride in the same Input packet.
             */
            IN_CRUISE = 256,
            IN_MASK = 511;
    /** The afterburner lights only at this throttle or more. */
    public static final double AFTERBURNER_THROTTLE = 0.9;
    /** Tiltrotor: the server judges moves by the helicopter's rules for this long after the nacelles reach airplane mode. */
    private static final int TILT_GRACE = 20;
    private static final int INPUT_TIMEOUT = 40, MOVE_TIMEOUT = 10, KEEPALIVE = 10;
    static final double BRAKE = 0.05, ROLL = 0.006, REVERSE = 0.35, WATER_SPEED = 0.35;
    public static final double RENDER_DIST = 96;

    public VehicleState state;
    /**
     * ★4 targets (the driver's client sets them from client/CruiseClientSVA, the server from the Input packet): the cruise
     * speed (blocks/tick), the autopilot's yaw (deg) and height (Y). Only read while the input has IN_CRUISE.
     */
    public double cruiseSpeed, apYaw, apAlt;
    /**
     * Aircraft stage C (AutopilotMath): the autopilot's modes (M_* bits; КУРС holds apYaw, ВЫС apAlt, СКОР cruiseSpeed,
     * ВЕРТ apVs - blocks/tick, ЗАХВ the nearest runway) - the driver's client sets them with the targets, the server from
     * the Input packet. Only read while the input has IN_CRUISE (the autopilot kind).
     */
    public int apModes = AutopilotMath.DEFAULT;
    public double apVs;
    /** Server: cruise / autopilot engaged this tick (synced as X_CRUISE). */
    private boolean cruiseNow;
    /** Stage C, both sides: the runway ЗАХВ / the HSI use (RunwayMath, refreshed every RUNWAY_EVERY ticks while asked for). */
    private RunwayMath.Runway runway;
    private long runwayAt = -1000000L;
    /** Stage C: a runway is looked for again after this many ticks (none found yet: RUNWAY_RETRY - flying toward one). */
    public static final int RUNWAY_EVERY = 40, RUNWAY_RETRY = 10;
    /** Stage C, server: the «База» point (x, z; Integer.MIN_VALUE = none) - the pilot's bed, else the world spawn. */
    private int baseX = Integer.MIN_VALUE, baseZ = Integer.MIN_VALUE;
    /** Stage C, server: ЗАХВ aligned this tick (synced in DW_AP). */
    private boolean capAligned;
    /**
     * Stage D1: ЗАХВ's phase with a PAPI (AutopilotMath.PH_*; the physics' and the server's panel step keep it - the same
     * one-way state machine evaluated twice a tick lands on the same phase), PH_RWY while ЗАХВ is not flying.
     */
    private int capPhase;
    /**
     * Round 2, D4 (★22): the driver's client's copy of the flight plan (the server sends it - NetSVA.RouteInfo - when he
     * gets in and after each change; its physics advances the active point itself), the guidance this tick (apBegin /
     * the server's step; null = МАРШ not flying), the server's last driver the plan went to, and «send it again».
     */
    private RouteMath.Plan clientPlan = new RouteMath.Plan();
    private RouteMath.Guide routeNow;
    private EntityPlayerMP planSentTo;
    private boolean planDirty;
    /** Stage C, the physics: last tick's speed (СКОР's damping term). */
    private double apPrevSpeed = Double.NaN;
    /** Signed speed along the facing, blocks/tick (physics). */
    public double speed;
    private int input;
    private long lastInputTick = -1000, lastMoveTick = -1000, lastCorrectTick = -1000;
    // server, Move packets: where it stood at this tick's first Move and the tick's reach budget (ticks) - all Moves
    // of one tick share it; aircraft: the deepest sink of one client step this tick and the count of Moves taken
    private double moveBaseX, moveBaseY, moveBaseZ, moveDyMin;
    private long moveBaseDt = 1, moveBaseMs;
    /** Steps borrowed (-1) or saved (+1) between ticks: one client step of tick jitter (serverMove). */
    private long moveCredit, moveCreditAtTick;
    private int movesThisTick;
    // server measurement
    private double lastX, lastY, lastZ, lastDx, lastDz;
    private boolean hasLast;
    private double speedEma, prevMoved;
    // server, the driver's client flying: its reported airspeed (last accepted Move), the last MOVED_WIN ticks'
    // measured displacements (their mean: the Moves' uneven arrival evens out), the airspeed the rules judged this tick
    private float moveSpeed;
    private final double[] movedWin = new double[MOVED_WIN];
    private int movedAt;
    private double airspeedNow;
    private static final int MOVED_WIN = 4;
    /** Client, the local driver: its own physics is stalling now (PlanePhysicsSVA's own speed - no packet lag). */
    private boolean localStall;
    /** Server: Moves refused this vehicle's life (world test: no corrections while driving normally). */
    private int movesRefused, refuseWhy;
    private int hitCooldown;
    private boolean registered;
    /** Server: held up by lift / boosting this tick (synced as flags). */
    private boolean lifting, boosting;
    /** Plane throttle 0..1 (the driver's client and the server integrate it from the same keys). */
    public double throttle;
    // server, aircraft: in the air now / last tick, unpowered in the air, stalling, measured vertical speed, EU this tick
    private boolean wasAirborne, autoNow, stallNow;
    private double lastDy;
    /**
     * Aircraft stage A (HighLiftMath), server: the flap setting, the pilot's airbrake, the ground spoilers (up by
     * themselves at a touch-down), the slats (automatic), the overspeed warning's ticks left. Synced in DW_HILIFT.
     */
    private int flaps, flapWarn;
    private boolean airbrake, groundSpoilers, slats;
    private float costNow;
    /** Wave 4 (★15 рекуперация, RegenMath): the server's recovery pool (EU spent, not saved - a reload just empties it), EU/t recovered this tick. */
    private double regenPool;
    private float regenNow;
    // client, aircraft: rotor / propeller angle and spin rate (deg/tick), the body's visual pitch / bank
    public float rotorAngle, prevRotorAngle, renderPitch, prevRenderPitch, renderRoll, prevRenderRoll;
    private float rotorRate;
    // client: the synced gas string parsed
    private String gasSeen;
    private final int[] gasShown = new int[Gas.values().length];
    /**
     * Hydrogen wave (FuelMath / BatteryMath): the fuel part of the synced gas string ("tanks|fuel"), parsed on clients -
     * FS_* indexes: on hydrogen, the H2 flow (mB/min), the battery in, its health / limit (per mille), services, the net
     * battery flow and the generator (EU/t x 10), the field service and the jump start in progress (%, -1 none).
     */
    private static final int FS_ON = 0, FS_FLOW = 1, FS_BAT = 2, FS_HEALTH = 3, FS_LIMIT = 4, FS_SERV = 5, FS_NET = 6, FS_GEN = 7, FS_KIT = 8,
            FS_JUMP = 9, FS_COUNT = 10;
    private final int[] fuelShown = new int[FS_COUNT];
    // hydrogen wave, server: the H2 flow (mB/min, averaged), the battery's net flow and the generator this tick (EU/t),
    // the field service (ticks left, by whom, the electrolyte already taken), the jump start (from whom, EU left, ticks)
    private double h2Flow, batNet, genNow;
    private int kitTicks;
    private java.util.UUID kitBy;
    private EntityVehicleSVA jumpFrom;
    private double jumpLeft;
    private int jumpTicks;
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
    /** Client: the ★4 targets last sent with an Input packet. */
    private final float[] sentCruise = new float[4];
    private int sentModes = -1;
    private double sentX, sentY, sentZ;
    private float sentYaw;
    // stage C, server: the drill (on, dig budget, blocks driven since the last torch), auto-delivery (stuck ticks,
    // transfer timer, why it stopped, distance to the point), throttles for actions and warnings
    public boolean drillOn;
    double digBudget, torchDist;
    int autoBlocked, autoTimer, autoReason, autoDist;
    private long warnTick = -1000;
    /** Server: the last allowed action of each player (the Action packet's flood throttle). */
    private final java.util.Map<java.util.UUID, Long> actionTicks = new java.util.HashMap<java.util.UUID, Long>();
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
    /**
     * Client (★6 / ★10): the animation state - wheels rolled, steering, control surfaces, gear, bob, lean, the smoothed
     * nacelles, brake lights (vehicle/AnimMath; nothing synced - ticked from the motion every client sees).
     */
    public final com.sva.vehicle.AnimMath.State anim = new com.sva.vehicle.AnimMath.State();
    /** Liner wave 1, client: the SA-320's own animation (gear sequence by its lever, the L1 door and stairs, the fans). */
    public final com.sva.vehicle.LinerAnim linerAnim = new com.sva.vehicle.LinerAnim();
    /** Client: the local driver's keys this tick (-1: someone else drives / nobody). */
    private int animIn = -1;
    /**
     * Client: the vehicle has charge and is not a wreck - its lights work (VehicleLightsSVA, DynamicLightSVA). D2: an
     * aircraft with the board systems also needs АКБ on and the battery not flat (the instruments go dark with it).
     */
    public boolean powered() {
        if (getVehicleType() == VehicleType.LINER) {
            return !isWrecked() && liner() != null && liner().powered();   // liner wave 1: either battery or AC
        }
        return getEnergy() > 0 && !isWrecked() && (!SystemsMath.applies(getVehicleType()) || boardOn());
    }

    // aircraft round 2, D2 (SystemsMath), server: the engine, its no-pilot timer, the failure roll's timer, the pitot's ice
    private final SystemsMath.Engine engine = new SystemsMath.Engine();
    /**
     * Stage D5, server: the INS's machine and this tick's status (SystemsMath.INS_*), where it stood last tick (the
     * "standing still" check). Aligned is kept over a reload (K_INS) while the board stays powered. World tests: aligned at
     * once (as the engine) unless the vehicle's debugInsManual says it goes through the real alignment.
     */
    private final SystemsMath.Ins ins = new SystemsMath.Ins();
    private int insNow = SystemsMath.INS_READY;
    private double insX = Double.NaN, insY, insZ;
    public boolean debugInsManual;
    private int noPilot, failTimer, ice, autoWait;
    /** Ticks before the auto-start tries again after it could not crank (or misfired). */
    private static final int AUTO_RETRY = 60;
    private boolean heatingNow, coldNow;
    /**
     * World tests: every aircraft's engine is running whenever it may (the tests from before D2 fly at once) and no
     * failure is rolled at random - unless the vehicle's own debugEngineManual says it goes through the real sequence.
     */
    public static boolean testInstantEngine;
    public boolean debugEngineManual;
    // D2, client: the engine state seen last tick and the ticks in it (the warm-up's power ramp, the HUD, the puff)
    private int engineSeen = -1, engineSeenTicks;
    /**
     * Liner wave 1 (LinerLogicSVA): server - ticks left of «a station serves it» (external power: markExtPower, refreshed by
     * the charging station every tick it serves); client - the mirror of the liner's systems (from the synced DW_SPACE
     * string) and the string it was parsed from.
     */
    private int extTicks;
    private com.sva.vehicle.LinerSystems linerView;
    private String linerSeen;

    /**
     * Liner wave 1: the SA-320's systems - the server's own (VehicleState.liner), a client's mirror of the synced string;
     * null for every other type.
     */
    public com.sva.vehicle.LinerSystems liner() {
        if (getVehicleType() != VehicleType.LINER) {
            return null;
        }
        if (!remote()) {
            return state.liner();
        }
        String s = dataWatcher.getWatchableObjectString(DW_SPACE);
        if (linerView == null) {
            linerView = new com.sva.vehicle.LinerSystems();
        }
        if (s != null && !s.equals(linerSeen)) {
            linerView.unpack(s);
            linerSeen = s;
        }
        return linerView;
    }

    /** Liner wave 1, server: a charging station serves this vehicle now (the liner's ВНЕШН. ПИТ «ДОСТУП»). */
    public void markExtPower() {
        extTicks = CHARGE_HOLD;
    }

    /** Liner wave 1: external power is available (a station served it within CHARGE_HOLD ticks). */
    boolean extPowerNow() {
        return extTicks > 0;
    }

    /** Liner wave 1: in the air at the end of the last tick (the server's flight rules). */
    boolean wasAirborneNow() {
        return wasAirborne;
    }

    java.util.Random rand() {
        return rand;
    }

    /** Liner wave 1: this tick's H2 burn (mB) into the flow shown (FuelMath.flowStep, mB/min). */
    void linerFlow(double burnMb) {
        h2Flow = FuelMath.flowStep(h2Flow, burnMb);
    }

    /** Liner wave 1: the old engine word follows the liner's engines (RUN while either runs). */
    void linerEngine(boolean running) {
        if (running && engine.state != SystemsMath.RUN) {
            engine.run();
        } else if (!running && engine.state != SystemsMath.OFF) {
            engine.stop();
        }
    }

    /** Liner wave 1: the flap lever's setting and the speed brake lever out (the server's high-lift state). */
    void linerHighLift(int flapSetting, boolean brake) {
        flaps = Math.max(0, Math.min(HighLiftMath.LINER.last(), flapSetting));
        airbrake = brake;
    }

    void vanillaClick(float volume, float pitch) {
        vanillaShot("random.click", volume, pitch);
    }

    void vanillaGhast(float volume, float pitch) {
        vanillaShot("mob.ghast.fireball", volume, pitch);
    }

    void tellRidersKey(String key) {
        tellRiders(key);
    }

    void lockedMsgTo(EntityPlayer p) {
        lockedMsg(p);
    }

    void msgTo(EntityPlayer p, String key, Object... args) {
        msg(p, key, args);
    }

    /** Liner wave 1: sync now (a control clicked). */
    void syncNow() {
        syncWatcher();
    }

    // liner wave 2: the slats' automatic rule for the next tick's systems, the height over the ground, the grip under the
    // wheels, the world test's «in the air» for the systems alone
    boolean linerSlatsAuto;
    private double linerAgl, linerMuNow = com.sva.vehicle.LinerGear.MU_DRY;
    private int linerAglAt = -100, linerMuAt = -100;
    /** World tests: the liner's systems believe it flies (the gear's logic) while it stands on its test strip. */
    public boolean debugLinerAir;

    void vanillaSound(String name, float volume, float pitch) {
        vanillaShot(name, volume, pitch);
    }

    // liner wave 4: the bank (the physics' own on its side), the levers last written into the throttle, the measured vertical
    // speed, the ILS stations near (the world data, every 20 ticks), the radio altimeter's ground
    /** Liner wave 4: the bank (deg, + right) the physics flies (LinerPhysicsSVA - the driving client's / the server's own). */
    public double linerBank;
    double linerThrSet = Double.NaN, linerLastY = Double.NaN, linerVy;
    java.util.List<com.sva.vehicle.LinerIls.Station> linerIls;
    int linerIlsAt = -100;

    /**
     * Liner wave 4, server: the radio height (blocks over the solid block under the centre) - every tick (a column scan of
     * up to 64 blocks; further down the coarse linerHeight()).
     */
    double linerRadio() {
        int x = MathHelper.floor_double(posX), z = MathHelper.floor_double(posZ), y0 = MathHelper.floor_double(posY);
        for (int y = y0; y > Math.max(0, y0 - 64); y--) {
            if (!worldObj.isAirBlock(x, y, z)) {
                return Math.max(0, posY - (y + 1));
            }
        }
        return Math.max(64, linerHeight());
    }

    /** Liner wave 2, server: the height over the ground (blocks; a column scan every 10 ticks, up to 250). */
    double linerHeight() {
        if (ticksExisted - linerAglAt >= 10 || ticksExisted < linerAglAt) {
            linerAglAt = ticksExisted;
            int x = MathHelper.floor_double(posX), z = MathHelper.floor_double(posZ), y0 = MathHelper.floor_double(posY);
            double h = 250;
            for (int y = y0; y > Math.max(0, y0 - 250); y--) {
                if (!worldObj.isAirBlock(x, y, z)) {
                    h = Math.max(0, posY - (y + 1));
                    break;
                }
            }
            linerAgl = h;
        }
        return linerAgl;
    }

    /**
     * Liner wave 2, both sides: the grip under the wheels (LinerGear.MU_*) - ice / packed ice, snow, water or rain on an
     * open surface, else dry; looked at every 10 ticks.
     */
    public double linerMu() {
        if (worldObj == null) {
            return com.sva.vehicle.LinerGear.MU_DRY;
        }
        if (ticksExisted - linerMuAt >= 10 || ticksExisted < linerMuAt) {
            linerMuAt = ticksExisted;
            int x = MathHelper.floor_double(posX), z = MathHelper.floor_double(posZ), y = MathHelper.floor_double(posY + 0.01);
            net.minecraft.block.Block at = worldObj.getBlock(x, y, z), under = worldObj.getBlock(x, y - 1, z);
            net.minecraft.block.material.Material m = at.getMaterial(), mu = under.getMaterial();
            double g = com.sva.vehicle.LinerGear.MU_DRY;
            if (under == net.minecraft.init.Blocks.ice || under == net.minecraft.init.Blocks.packed_ice) {
                g = com.sva.vehicle.LinerGear.MU_ICE;
            } else if (m == net.minecraft.block.material.Material.snow || mu == net.minecraft.block.material.Material.craftedSnow
                    || mu == net.minecraft.block.material.Material.snow) {
                g = com.sva.vehicle.LinerGear.MU_SNOW;
            } else if (m == net.minecraft.block.material.Material.water || worldObj.isRaining() && worldObj.canBlockSeeTheSky(x, y, z)) {
                g = com.sva.vehicle.LinerGear.MU_WET;
            }
            linerMuNow = g;
        }
        return linerMuNow;
    }

    /** Liner wave 2: the garage's / the service item's work - hydraulic fluid in (refill), the brakes and tyres. */
    public boolean linerNeedsService() {
        com.sva.vehicle.LinerSystems L = state.liner();
        return L != null && (L.gear.flat != 0 || L.hyd.needsFluid());
    }

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
        dataWatcher.addObject(DW_BOARD, Integer.valueOf(0));
        dataWatcher.addObject(DW_TUNE, Integer.valueOf(0));
        dataWatcher.addObject(DW_TRAILER, "");
        dataWatcher.addObject(DW_REGEN, Integer.valueOf(0));
        dataWatcher.addObject(DW_HILIFT, Integer.valueOf(0));
        dataWatcher.addObject(DW_AP, Integer.valueOf(0));
        dataWatcher.addObject(DW_AP2, Integer.valueOf(0));
        dataWatcher.addObject(DW_BASE_X, Integer.valueOf(Integer.MIN_VALUE));
        dataWatcher.addObject(DW_BASE_Z, Integer.valueOf(Integer.MIN_VALUE));
        dataWatcher.addObject(DW_SYS, Integer.valueOf(SystemsMath.INITIAL));
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
        parseGas();
        return gasShown[g.ordinal()];
    }

    /** Client: the synced "tanks|fuel" string parsed when it changed (the tanks in FuelMath.gases order, the FS_* numbers). */
    private void parseGas() {
        String s = dataWatcher.getWatchableObjectString(DW_GAS);
        if (s.equals(gasSeen)) {
            return;
        }
        gasSeen = s;
        java.util.Arrays.fill(gasShown, 0);
        java.util.Arrays.fill(fuelShown, 0);
        fuelShown[FS_KIT] = fuelShown[FS_JUMP] = -1;
        int bar = s.indexOf('|');
        String tanks = bar < 0 ? s : s.substring(0, bar);
        if (bar >= 0) {
            String[] f = s.substring(bar + 1).split(",");
            for (int i = 0; i < f.length && i < FS_COUNT; i++) {
                fuelShown[i] = num(f[i]);
            }
        }
        List<Gas> order = FuelMath.gases(getVehicleType(), fuelShown[FS_ON] != 0);
        String[] p = tanks.length() == 0 ? new String[0] : tanks.split(",");
        for (int i = 0; i < p.length && i < order.size(); i++) {
            gasShown[order.get(i).ordinal()] = num(p[i]);
        }
    }

    private static int num(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;                                               // a garbled value shows 0
        }
    }

    /** Client: a FS_* number (parsed on demand). */
    private int fuelNum(int i) {
        parseGas();
        return fuelShown[i];
    }

    // ---- stage C views (clients: synced)

    private int extra() {
        return dataWatcher.getWatchableObjectInt(DW_EXTRA);
    }

    /** A trailer is hitched. */
    public boolean hasTrailer() {
        return worldObj != null && worldObj.isRemote ? (extra() & X_TRAILER) != 0 : state.trailer;
    }

    /** Cargo slots now (a hitched cargo trailer adds 54; ★15 the other kinds none). */
    public int cargoCapacity() {
        return getVehicleType().cargoSlots(hasTrailer() && TrailerMath.addsCargo(trailerKind()));
    }

    // ---- wave 3, ★15: the trailer kinds (clients: the synced DW_TRAILER line, parsed once per change)

    private String trailerSeen;
    /** Client: {kind, amount, carried type (-1 none), carried board, carried charge %, charger EU, solar 0/1}. */
    private final int[] trailerShown = {0, 0, -1, 0, 0, 0, 0};
    private String trailerFluidShown = "";
    private final List<int[]> chargerServedShown = new ArrayList<int[]>();
    /** Server: the charger's vehicles (scanned every 20 ticks), the round robin, the carried vehicle's card. */
    private final List<EntityVehicleSVA> chargerServed = new ArrayList<EntityVehicleSVA>();
    private int chargerRr, chargerScan;
    private boolean solarNow;
    private int[] carriedCard;

    /** "kind|fluid|mB|carried type|board|charge %|charger EU|solar|type:board,type:board" (clients parse it). */
    private String trailerString() {
        if (!state.trailer) {
            return "";
        }
        int[] c = carriedCard();
        StringBuilder b = new StringBuilder();
        b.append(state.trailerType).append('|').append(state.tankFluid == null ? "" : state.tankFluid).append('|').append(state.tankAmount)
                .append('|').append(c[0]).append('|').append(c[1]).append('|').append(c[2]).append('|')
                .append((long) Math.floor(state.chargerEnergy / 100) * 100).append('|').append(solarNow ? 1 : 0).append('|');
        for (int i = 0; i < chargerServed.size() && i < 4; i++) {
            EntityVehicleSVA v = chargerServed.get(i);
            b.append(i > 0 ? "," : "").append(v.state.type.ordinal()).append(':').append(v.getBoardNumber());
        }
        return b.toString();
    }

    private void parseTrailer() {
        String s = dataWatcher.getWatchableObjectString(DW_TRAILER);
        if (s.equals(trailerSeen)) {
            return;
        }
        trailerSeen = s;
        trailerShown[0] = 0;
        trailerShown[1] = 0;
        trailerShown[2] = -1;
        trailerFluidShown = "";
        chargerServedShown.clear();
        String[] p = s.split("\\|", -1);
        if (p.length < 9) {
            return;
        }
        try {
            trailerShown[0] = TrailerMath.valid(Integer.parseInt(p[0]));
            trailerFluidShown = p[1];
            trailerShown[1] = Integer.parseInt(p[2]);
            trailerShown[2] = Integer.parseInt(p[3]);
            trailerShown[3] = Integer.parseInt(p[4]);
            trailerShown[4] = Integer.parseInt(p[5]);
            trailerShown[5] = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(p[6]));
            trailerShown[6] = Integer.parseInt(p[7]);
            for (String e : p[8].split(",")) {
                String[] q = e.split(":");
                if (q.length == 2) {
                    chargerServedShown.add(new int[] {Integer.parseInt(q[0]), Integer.parseInt(q[1])});
                }
            }
        } catch (NumberFormatException e) {
            // a garbled line shows an empty trailer
        }
    }

    /** ★15: the hitched trailer's kind (TrailerMath; CARGO when none). */
    public int trailerKind() {
        if (worldObj != null && worldObj.isRemote) {
            parseTrailer();
            return hasTrailer() ? trailerShown[0] : TrailerMath.CARGO;
        }
        return state.trailer ? state.trailerType : TrailerMath.CARGO;
    }

    /** ★15: a trailer of this kind is hitched (both sides). */
    public boolean hasTrailerKind(int kind) {
        return hasTrailer() && trailerKind() == kind;
    }

    /** ★15 tanker: the fluid's registry name ("" empty) and its mB (both sides). */
    public String tankFluid() {
        if (worldObj != null && worldObj.isRemote) {
            parseTrailer();
            return trailerFluidShown;
        }
        return state.tankFluid == null ? "" : state.tankFluid;
    }

    public int tankAmount() {
        if (worldObj != null && worldObj.isRemote) {
            parseTrailer();
            return trailerShown[1];
        }
        return state.tankAmount;
    }

    /** ★15 platform: {type ordinal (-1 none), board number, charge %} of the carried vehicle (both sides). */
    public int[] carried() {
        if (worldObj != null && worldObj.isRemote) {
            parseTrailer();
            return new int[] {trailerShown[2], trailerShown[3], trailerShown[4]};
        }
        return carriedCard();
    }

    /** ★15 mobile charger: EU in its buffer (clients: in hundreds) and the sun working (both sides). */
    public double chargerEnergy() {
        if (worldObj != null && worldObj.isRemote) {
            parseTrailer();
            return trailerShown[5];
        }
        return state.chargerEnergy;
    }

    public boolean chargerSolar() {
        if (worldObj != null && worldObj.isRemote) {
            parseTrailer();
            return trailerShown[6] != 0;
        }
        return solarNow;
    }

    /** ★15 mobile charger: the vehicles it charges now, {type ordinal, board} (clients: the synced first four). */
    public List<int[]> chargerServed() {
        if (worldObj != null && worldObj.isRemote) {
            parseTrailer();
            return chargerServedShown;
        }
        List<int[]> out = new ArrayList<int[]>();
        for (EntityVehicleSVA v : chargerServed) {
            out.add(new int[] {v.state.type.ordinal(), v.getBoardNumber()});
        }
        return out;
    }

    /** ★15: the crawler's top speed x this with its trailer (a full tank / a loaded platform - both sides). */
    public double trailerSpeedMul() {
        if (!hasTrailer()) {
            return 1;
        }
        int k = trailerKind();
        return TrailerMath.speedMul(k, k == TrailerMath.TANK ? tankAmount() : 0, k == TrailerMath.PLATFORM && carried()[0] >= 0);
    }

    /** Server: the carried vehicle's {type, board, charge %} (cached; -1 type when the platform is empty). */
    private int[] carriedCard() {
        if (state.carried == null) {
            carriedCard = null;
            return new int[] {-1, 0, 0};
        }
        if (carriedCard == null) {
            ItemStack it = ItemStack.loadItemStackFromNBT(state.carried);
            if (it == null || !(it.getItem() instanceof com.sva.item.ItemVehicleSVA)) {
                return new int[] {-1, 0, 0};
            }
            VehicleState st = com.sva.item.ItemVehicleSVA.stateOf(it);
            carriedCard = new int[] {st.type.ordinal(), com.sva.vehicle.SkinMath.boardNumber(st.vehId),
                    (int) Math.floor(st.energy * 100 / Math.max(1, st.maxEnergy()))};
        }
        return carriedCard;
    }

    /** ★15: where the trailer stands (world x, z) - TrailerMath.TRAILER_BACK behind the crawler. */
    public double[] trailerPos() {
        double[] o = toWorld(-TrailerMath.TRAILER_BACK, 0);
        return new double[] {posX + o[0], posZ + o[1]};
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

    /**
     * The board number on the side decal (1..99, stable per vehicle: from its id in the item NBT), synced; before the
     * first sync a stand-in from the entity id.
     */
    public int getBoardNumber() {
        if (!worldObj.isRemote) {
            return com.sva.vehicle.SkinMath.boardNumber(state.vehId);
        }
        int n = dataWatcher.getWatchableObjectInt(DW_BOARD);
        return n > 0 ? n : getEntityId() % 99 + 1;
    }

    /** The owner's name ("" - nobody's), synced. */
    public String getOwnerName() {
        return worldObj.isRemote ? dataWatcher.getWatchableObjectString(DW_OWNER) : state.ownerName == null ? "" : state.ownerName;
    }

    // ---- wave 2 views (clients: synced)

    /** ★8: the installed upgrades (TuningMath kinds, -1 empty), synced. */
    public int[] getTuning() {
        return worldObj != null && worldObj.isRemote ? TuningMath.unpack(dataWatcher.getWatchableObjectInt(DW_TUNE)) : state.tuning;
    }

    /** ★8: the upgrades work (the server's vehicles.tuning; clients: synced). */
    public boolean tuningOn() {
        return worldObj != null && worldObj.isRemote ? (dataWatcher.getWatchableObjectInt(DW_TUNE) & TUNE_ON) != 0 : ConfigSVA.tuning;
    }

    /** ★8: top speed x this (Мотор+, Лёгкий корпус, Броня - wheels, tracks, the hoverbike). */
    public double tuneSpeed() {
        return tuningOn() ? TuningMath.speedMul(getVehicleType(), getTuning()) : 1;
    }

    /** ★8: headlight reach x this («Люстра фар»). */
    public double tuneLight() {
        return tuningOn() ? TuningMath.lightMul(getTuning()) : 1;
    }

    /** Max hull HP with the upgrades («Лёгкий корпус»), both sides. */
    public float getMaxHp() {
        if (worldObj == null || !worldObj.isRemote) {
            return state.maxHp();
        }
        return (float) (getVehicleType().maxHp * (tuningOn() ? TuningMath.hpMul(getTuning()) : 1));
    }

    /** EU buffer with the upgrades («Аккумулятор+»), both sides. */
    public double getMaxEnergy() {
        if (worldObj == null || !worldObj.isRemote) {
            return state.maxEnergy();
        }
        VehicleType t = getVehicleType();
        double base = onHydrogen() ? (batteryPresent() ? BatteryMath.capacity(t) : 0) : t.buffer;
        return base * (tuningOn() ? TuningMath.bufferMul(getTuning()) : 1);
    }

    /** A gas tank's size with the upgrades («Доп. бак»), both sides. */
    public int getGasCapacity(Gas g) {
        if (worldObj == null || !worldObj.isRemote) {
            return state.gasCapacity(g);
        }
        int c = FuelMath.capacity(getVehicleType(), g, onHydrogen());
        return tuningOn() ? (int) Math.round(c * TuningMath.gasMul(getTuning())) : c;
    }

    // ------------------------------------------------------------------ hydrogen wave: fuel and the board battery (both sides)
    // The accessors the HUD, the «Техника» menu, the cockpit instruments (voltmeter / ammeter / H2 gauge / lamps) read.

    /** The vehicle burns hydrogen now (FuelMath; server: its state and the config, clients: synced). */
    public boolean onHydrogen() {
        return remote() ? fuelNum(FS_ON) != 0 : state.hydrogen();
    }

    /** The tanks it has now, in order (hydrogen mode: H2 first). */
    public List<Gas> tankList() {
        return FuelMath.gases(getVehicleType(), onHydrogen());
    }

    /** H2 in the tank (mB). */
    public int h2Mb() {
        return getGas(FuelMath.H2);
    }

    /** The H2 tank's size (mB, «Доп. бак» included). */
    public int h2Max() {
        return getGasCapacity(FuelMath.H2);
    }

    /** The H2 flow (mB / min, averaged over ~10 s). */
    public double h2FlowPerMin() {
        return remote() ? fuelNum(FS_FLOW) : h2Flow;
    }

    /** Minutes the H2 lasts at the current flow (parked: at cruise); infinity when nothing burns. */
    public double h2RangeMinutes() {
        return FuelMath.minutesLeft(getVehicleType(), h2Mb(), h2FlowPerMin(), remote() ? com.sva.net.ServerConfigSVA.consumption() : ConfigSVA.consumption);
    }

    /** Blocks the H2 lasts at the current speed (parked: at cruise speed). */
    public double h2RangeBlocks() {
        double dx = posX - prevPosX, dz = posZ - prevPosZ;
        return FuelMath.blocksLeft(getVehicleType(), h2RangeMinutes(), Math.sqrt(dx * dx + dz * dz));
    }

    /** The fuel lamp: FuelMath.LAMP_OK / LAMP_LOW («МАЛО ТОПЛИВА») / LAMP_RESERVE («РЕЗЕРВ», blinking) / LAMP_EMPTY. */
    public int fuelLamp() {
        return FuelMath.lamp(h2Mb(), h2Max());
    }

    /** A board battery is in (EU mode: always true - the battery does nothing there). */
    public boolean batteryPresent() {
        if (!onHydrogen()) {
            return true;
        }
        return remote() ? fuelNum(FS_BAT) != 0 : state.batIn;
    }

    /** The battery's charge (EU) - on hydrogen it is the vehicle's charge. */
    public double batteryEu() {
        return getEnergy();
    }

    /** Its capacity (EU, «Аккумулятор+» included; 0 without one). */
    public double batteryMax() {
        return getMaxEnergy();
    }

    /** Its charge 0..1. */
    public double batteryFrac() {
        double m = batteryMax();
        return m <= 0 ? 0 : Math.max(0, Math.min(1, batteryEu() / m));
    }

    /** Its health 0..1 (1 in EU mode). */
    public double batteryHealth() {
        if (!onHydrogen()) {
            return 1;
        }
        return remote() ? fuelNum(FS_HEALTH) / 1000.0 : state.batHealth;
    }

    /** The health a service brings it to (drops BatteryMath.CAP_DROP a service). */
    public double batteryLimit() {
        return remote() ? fuelNum(FS_LIMIT) / 1000.0 : state.batLimit;
    }

    public int batteryServices() {
        return remote() ? fuelNum(FS_SERV) : state.batServices;
    }

    /** The battery's net flow this tick (EU/t; + charging). */
    public double batteryNetPerTick() {
        return remote() ? fuelNum(FS_NET) / 10.0 : batNet;
    }

    /** The generator's output this tick (EU/t; 0 with the engine off). */
    public double generatorPerTick() {
        return remote() ? fuelNum(FS_GEN) / 10.0 : genNow;
    }

    /** The HUD's arrow: BatteryMath.UP / DOWN / FLAT_TREND. */
    public int batteryTrend() {
        return BatteryMath.trend(batteryNetPerTick());
    }

    /** The voltmeter (V; 0 without a battery). */
    public float batteryVolts() {
        return BatteryMath.volts(batteryPresent() && onHydrogen(), batteryFrac(), batteryNetPerTick(), batteryMax());
    }

    /** The ammeter (A; + charging). */
    public float batteryAmps() {
        return BatteryMath.amps(batteryNetPerTick(), batteryVolts());
    }

    /** The «АКБ» lamp: no battery, or under BatteryMath.LOW. */
    public boolean batteryLamp() {
        return onHydrogen() && (!batteryPresent() || batteryFrac() < BatteryMath.LOW);
    }

    /** «ОБСЛУЖИТЕ АКБ»: the health under BatteryMath.SERVICE_AT. */
    public boolean batteryNeedsService() {
        return onHydrogen() && batteryPresent() && BatteryMath.needsService(batteryHealth());
    }

    /** The field service in progress (%), -1 none. */
    public int batteryServiceProgress() {
        return remote() ? fuelNum(FS_KIT) : kitTicks > 0 ? 100 - kitTicks * 100 / BatteryMath.KIT_TICKS : -1;
    }

    /** A jump start in progress (%), -1 none. */
    public int jumpProgress() {
        return remote() ? fuelNum(FS_JUMP) : jumpTicks > 0 ? 100 - jumpTicks * 100 / BatteryMath.JUMP_TICKS : -1;
    }

    /** The engine's crank / warm-up ticks (the D2 aircraft's, else FuelMath's). */
    public int crankTicksFor(VehicleType t, boolean windmill) {
        return SystemsMath.applies(t) ? SystemsMath.crankTicks(t, windmill) : FuelMath.GROUND_CRANK;
    }

    public int warmTicksFor(VehicleType t) {
        return SystemsMath.applies(t) ? SystemsMath.warmTicks(t) : FuelMath.GROUND_WARM;
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

    /** Auto-delivery waits at the edge of the loaded area: still on its way, the chunk ahead not loaded yet (AutoPilotSVA). */
    public boolean isAutoPaused() {
        return AutoDelivery.driving(getAutoState()) && getAutoReason() == AutoDelivery.R_UNLOADED;
    }

    /** The server's vehicles.locks (clients: synced) - the cargo screen's «Доступ» button. */
    public boolean locksOn() {
        return worldObj.isRemote ? (dataWatcher.getWatchableObjectInt(DW_TUNE) & TUNE_LOCKS) != 0 : ConfigSVA.locks;
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

    /**
     * DW_ROUTE: "A;B" of auto-delivery; round 2, D4 - no data watcher id was left - an aircraft adds ";" + its flight
     * plan's synced target (RouteMath.targetText: the active point, the leg's start, the count) for every rider's HSI / HUD.
     */
    private String routeText() {
        String r = pointText(state.routeA) + ";" + pointText(state.routeB);
        return isAircraft() ? r + ";" + RouteMath.targetText(state.plan) : r;
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
                | (state.anchor ? X_ANCHOR : 0) | (state.invisible ? X_INVIS : 0) | (portalTicks > 0 ? X_PORTAL : 0)
                | (cruiseNow ? X_CRUISE : 0);
    }

    // ------------------------------------------------------------------ wave 1: cruise / autopilot, roads, sounds

    /** ★4: cruise or the autopilot engaged (the server's judgement; clients: synced) - the cockpit lamp / line. */
    public boolean isCruiseOn() {
        return worldObj.isRemote ? (extra() & X_CRUISE) != 0 : cruiseNow;
    }

    /** ★4: what «Круиз» does on this vehicle now (CruiseMath.CRUISE / AUTOPILOT / NONE), the configs applied. */
    public int cruiseKind() {
        VehicleType t = getVehicleType();
        int k = CruiseMath.kind(t.movement, planeNow());
        if (t == VehicleType.LINER) {
            return CruiseMath.NONE;                                 // liner wave 4: its own FCU / AP (LinerAfs), not the panel's
        }
        boolean remote = worldObj != null && worldObj.isRemote;
        int tune = remote ? dataWatcher.getWatchableObjectInt(DW_TUNE) : 0;
        boolean cOn = remote ? (tune & TUNE_CRUISE) != 0 : ConfigSVA.cruise;
        boolean aOn = remote ? (tune & TUNE_AP) != 0 : ConfigSVA.autopilot;
        if (k == CruiseMath.CRUISE && !cOn || k == CruiseMath.AUTOPILOT && !aOn) {
            return CruiseMath.NONE;
        }
        return k;
    }

    /**
     * ★4: why cruise / the autopilot can't hold now with these keys (CruiseMath.OFF_*), 0 = it can. The same rule on
     * the driver's client (it switches its state off) and the server (it ignores the flag).
     */
    public int cruiseBlocked(int in) {
        int k = cruiseKind();
        if (k == CruiseMath.NONE) {
            return CruiseMath.OFF_MODE;
        }
        VehicleType t = getVehicleType();
        if (k == CruiseMath.CRUISE) {
            if (CruiseMath.cruiseCancelled((in & IN_BACK) != 0, (in & IN_BRAKE) != 0)) {
                return CruiseMath.OFF_KEYS;
            }
            boolean thrust = t.movement == Movement.HOVER ? hasLift() && canThrust() : canThrust();
            return thrust ? 0 : CruiseMath.OFF_THRUST;
        }
        if (!insReady()) {
            return CruiseMath.OFF_INS;                              // D5: no attitude / heading without the aligned INS
        }
        boolean keys = (in & (IN_FWD | IN_BACK | IN_LEFT | IN_RIGHT)) != 0;
        double charge = getEnergy() / Math.max(1.0, getMaxEnergy());
        // the server judges the airspeed it measured last tick (a driver's client's: its own, capped) - its per-tick
        // `speed` read 0 on a tick without a Move and cancelled the autopilot as a stall
        double air = worldObj.isRemote ? Math.max(0, speed) : airspeedNow;
        int why = CruiseMath.autopilotCancel(keys, charge, PlaneMath.stalled(flightParams(t), air), !onGround,
                hasLift() && canThrust());
        if (why == 0 && (in & IN_CRUISE) != 0 && (apModes & AutopilotMath.M_CAP) != 0) {
            // stage C: ЗАХВ brought it down low over the runway - the pilot lands
            AutopilotMath.Capture c = capture();
            if (c != null && c.done) {
                return CruiseMath.OFF_LANDING;
            }
        }
        return why;
    }

    /** ★4: cruise / the autopilot works this tick with these keys. */
    public boolean cruiseActive(int in) {
        return (in & IN_CRUISE) != 0 && cruiseBlocked(in) == 0;
    }

    // ------------------------------------------------------------------ aircraft stage C: autopilot modes, runway, HSI

    /**
     * The nearest runway strip (RunwayMath) within RunwayMath.SCAN blocks, looked for again every RUNWAY_EVERY ticks while
     * asked for (ЗАХВ on both sides, the HSI on the riders' client); null = none near. Only loaded chunks are read.
     */
    public RunwayMath.Runway runway() {
        long now = worldObj.getTotalWorldTime();
        if (now - runwayAt >= (runway == null ? RUNWAY_RETRY : RUNWAY_EVERY) || now < runwayAt) {
            runwayAt = now;
            runway = ModContentSVA.runway == null ? null : RunwayMath.find(new RunwayMath.World() {
                @Override
                public int top(int x, int z) {
                    int h = worldObj.getHeightValue(x, z);
                    return h <= 0 ? -1 : h - 1;
                }

                @Override
                public boolean runway(int x, int y, int z) {
                    return worldObj.blockExists(x, y, z) && worldObj.getBlock(x, y, z) == ModContentSVA.runway;
                }
            }, posX, posZ);
            if (runway != null) {
                runway.withPapi(com.sva.block.AirfieldSVA.papiBlocks(worldObj));     // stage D1: the PAPI rows at its ends
            }
        }
        return runway;
    }

    /** ЗАХВ's guidance now (null: no runway near). The descent from the set vertical speed (AutopilotMath.descent). */
    public AutopilotMath.Capture capture() {
        RunwayMath.Runway r = runway();
        if (r == null) {
            return null;
        }
        return AutopilotMath.capture(r, posX, posY, posZ, CruiseMath.heading(rotationYaw), AutopilotMath.descent(apModes, apVs), Math.max(0, speed),
                capPhase);
    }

    /**
     * The autopilot panel as this side knows it: the server's own judgement, clients the synced DW_AP (the driver's client
     * draws its own CruiseClientSVA state - client.CockpitInstrumentsSVA).
     */
    public AutopilotMath.Panel apPanel() {
        if (worldObj.isRemote) {
            return AutopilotMath.unpack(dataWatcher.getWatchableObjectInt(DW_AP), dataWatcher.getWatchableObjectInt(DW_AP2));
        }
        AutopilotMath.Panel p = new AutopilotMath.Panel();
        p.on = cruiseNow && cruiseKind() == CruiseMath.AUTOPILOT;
        p.modes = apModes;
        p.hdg = CruiseMath.heading(apYaw);
        p.alt = (int) Math.round(apAlt);
        p.kmh = VehicleMath.kmh(cruiseSpeed);
        p.vs = apVs * 20;
        p.runway = (apModes & AutopilotMath.M_CAP) != 0 && runway != null;
        p.aligned = capAligned;
        p.phase = capPhase;
        p.routeN = state.plan.size();                       // D4
        p.routeAct = state.plan.active;
        return p;
    }

    // ------------------------------------------------------------------ round 2, D4 (★22): the flight plan

    /** An autopilot kind (plane, jet, tiltrotor - its airplane mode or not): has a flight plan. */
    public boolean isAircraft() {
        return CruiseMath.kind(getVehicleType().movement, true) == CruiseMath.AUTOPILOT;
    }

    /** The flight plan this side flies by: the server's (VehicleState), the driver's client's copy (RouteInfo). */
    public RouteMath.Plan plan() {
        return worldObj.isRemote ? clientPlan : state.plan;
    }

    /** Client (NetSVA.RouteInfo): the server's plan for this vehicle. */
    public void setClientPlan(RouteMath.Plan p) {
        clientPlan = p == null ? new RouteMath.Plan() : p;
    }

    /**
     * МАРШ's guidance now from this side's plan (RouteMath.guide - it advances the active point; null: no plan). The
     * turn's lead from the speed and the turn rate the physics turns at (PlanePhysicsSVA: turnRate x speed / stall).
     */
    public RouteMath.Guide routeGuide() {
        RouteMath.Plan p = plan();
        if (p.isEmpty()) {
            return null;
        }
        VehicleType t = getVehicleType();
        double sp = Math.max(0, worldObj.isRemote ? speed : Math.max(speed, airspeedNow));
        double stall = t.plane == null ? 0.5 : flightParams(t).stall;
        double rate = t.turnRate * VehicleMath.clamp(sp / Math.max(1e-6, stall), 0, 1);
        int was = p.active;
        RouteMath.Guide g = RouteMath.guide(p, posX, posZ, CruiseMath.heading(rotationYaw), sp, rate);
        if (g != null && g.switched && worldObj.isRemote && p.active != was) {
            routeDing = true;                               // the driver's client: a chime for the next point
        }
        return g;
    }

    /** Set by routeGuide on the driver's client when a point is reached (CockpitInstrumentsSVA plays the chime once). */
    public boolean routeDing;

    /** МАРШ flew through the plan and a runway lies by its last point: ЗАХВ takes over (RouteMath.handOver). */
    public boolean routeHandOver() {
        RouteMath.Plan p = plan();
        if ((apModes & AutopilotMath.M_ROUTE) == 0 || (apModes & AutopilotMath.M_CAP) != 0 || !p.done()) {
            return false;
        }
        RunwayMath.Runway r = runway();
        int[] last = p.pts.get(p.size() - 1);
        return r != null && RouteMath.handOver(true, r.distance(last[0] + 0.5, last[1] + 0.5));
    }

    /**
     * The plan's synced target {active, size, tx, tz, alt, fx, fz} (RouteMath.parseTarget; fx = Integer.MIN_VALUE on the
     * first leg) or null: the server and the driver's client from their own plan (it advances there first), other
     * clients from DW_ROUTE.
     */
    public int[] routeTarget() {
        if (!isAircraft()) {
            return null;
        }
        if (!worldObj.isRemote || riddenByEntity != null && riddenByEntity == SVAMod.proxy.localPlayer() && !clientPlan.isEmpty()) {
            return RouteMath.parseTarget(RouteMath.targetText(plan()));
        }
        String r = dataWatcher.getWatchableObjectString(DW_ROUTE);
        int a = r.indexOf(';'), k = a < 0 ? -1 : r.indexOf(';', a + 1);
        return k < 0 ? null : RouteMath.parseTarget(r.substring(k + 1));
    }

    /**
     * Server, NetSVA.RouteSet: player p sets the plan (RouteMath.mayEdit - the right to drive an aircraft); the active
     * point back to the first. The sender (and the driver, next tick) get the plan back. @return accepted
     */
    public boolean serverSetPlan(EntityPlayerMP p, int[] flat) {
        if (!RouteMath.mayEdit(isAircraft(), rightsOf(p))) {
            msg(p, isAircraft() ? "sva.msg.route.denied" : "sva.msg.route.notAir");
            return false;
        }
        state.plan = RouteMath.sanitize(flat, 0);
        planDirty = true;
        syncWatcher();
        sendPlan(p);
        msg(p, state.plan.isEmpty() ? "sva.msg.route.cleared" : "sva.msg.route.set", Integer.valueOf(state.plan.size()));
        return true;
    }

    /** Server: the plan to player p (the map's «Маршрут» tab asks; the driver gets it on boarding / a change). */
    public void sendPlan(EntityPlayerMP p) {
        if (p != null && p.playerNetServerHandler != null) {
            NetSVA.CHANNEL.sendTo(new NetSVA.RouteInfo(getEntityId(), state.plan, RouteMath.mayEdit(isAircraft(), rightsOf(p))), p);
        }
    }

    /** Server, every tick: МАРШ steps with the driver's client's flight (its physics runs there), the plan to a new driver. */
    private void routeServerTick() {
        if (!isAircraft()) {
            return;
        }
        if (cruiseNow && (apModes & AutopilotMath.M_ROUTE) != 0) {
            RouteMath.Guide g = routeGuide();
            if (g != null && g.switched) {
                syncWatcher();
            }
        }
        EntityPlayerMP d = riddenByEntity instanceof EntityPlayerMP ? (EntityPlayerMP) riddenByEntity : null;
        if (d != null && (d != planSentTo || planDirty)) {
            sendPlan(d);
        }
        planSentTo = d;
        planDirty = false;
    }

    /** World test: the plan set directly (as the RouteSet packet would, without a player). */
    public void debugSetPlan(RouteMath.Plan p) {
        state.plan = p == null ? new RouteMath.Plan() : p;
        syncWatcher();
    }

    /** The HSI's «База» (world x, z) or null: the pilot's bed (overworld), else the world's spawn - synced from the server. */
    public int[] basePoint() {
        int x = worldObj.isRemote ? dataWatcher.getWatchableObjectInt(DW_BASE_X) : baseX;
        int z = worldObj.isRemote ? dataWatcher.getWatchableObjectInt(DW_BASE_Z) : baseZ;
        return x == Integer.MIN_VALUE ? null : new int[] {x, z};
    }

    /** Server, every RUNWAY_EVERY ticks: the «База» point - the pilot's bed in the overworld (as the jump's «дом»), else the spawn. */
    private void updateBase() {
        net.minecraft.util.ChunkCoordinates c = null;
        if (riddenByEntity instanceof EntityPlayer && worldObj.provider.dimensionId == 0) {
            c = ((EntityPlayer) riddenByEntity).getBedLocation(0);
        }
        if (c == null) {
            c = worldObj.getSpawnPoint();
        }
        baseX = c == null ? Integer.MIN_VALUE : c.posX;
        baseZ = c == null ? Integer.MIN_VALUE : c.posZ;
    }

    /** Stage C, the physics: ЗАХВ's guidance this tick (apBegin; null = no capture now). */
    private AutopilotMath.Capture capNow;

    /** Stage C: the autopilot's step begins (PlanePhysicsSVA) - ЗАХВ's guidance looked up once for the tick. */
    public void apBegin() {
        routeNow = (apModes & AutopilotMath.M_ROUTE) != 0 ? routeGuide() : null;
        if (routeHandOver()) {
            apModes |= AutopilotMath.M_CAP;                 // D4: the last point by a runway - ЗАХВ takes over by itself
        }
        // D4: while the plan is flown ЗАХВ waits (a runway passed on the way is not captured)
        boolean capWait = routeNow != null && !routeNow.done;
        capNow = (apModes & AutopilotMath.M_CAP) != 0 && !capWait ? capture() : null;
        if (capNow != null && capNow.out) {
            capNow = null;
        }
        capAligned = capNow != null && capNow.aligned;
        capPhase = capNow == null ? AutopilotMath.PH_RWY : capNow.phase;
    }

    /**
     * Stage C: the heading (Minecraft yaw) the modes want this tick, NaN = don't turn - ЗАХВ's intercept when a runway is
     * near, else КУРС's set heading.
     */
    public double apWantedYaw() {
        if (capNow != null) {
            return capNow.heading - 180;
        }
        if (routeNow != null && !Double.isNaN(routeNow.heading)) {
            return routeNow.heading - 180;                  // D4: МАРШ - the active leg (after the last point: its course)
        }
        return (apModes & AutopilotMath.M_HDG) != 0 ? apYaw : Double.NaN;
    }

    /** Stage C: the vertical speed the modes want (b/t; NaN = level flight as without keys) - ЗАХВ's glide slope when aligned. */
    public double apWantedVy(double climb, double dive) {
        if (capNow != null && !Double.isNaN(capNow.vy)) {
            // stage C: aligned on the glide slope; stage D1: the PAPI's slope (ГЛИС) / the flare (ВЫРАВН.)
            return VehicleMath.clamp(capNow.vy, -dive, Math.max(0, climb));
        }
        if (routeNow != null) {
            // D4: МАРШ - ВЫС's law toward the leg's height (with ВЕРТ: no faster than the set vertical speed)
            return AutopilotMath.wantedVy(apModes | AutopilotMath.M_ALT, posY, routeNow.alt, apVs, climb, dive);
        }
        return AutopilotMath.wantedVy(apModes, posY, apAlt, apVs, climb, dive);
    }

    /** Stage C: СКОР's throttle this tick (the throttle as it is without СКОР). */
    public double apThrottle(double thr, double speedNow) {
        double prev = Double.isNaN(apPrevSpeed) ? speedNow : apPrevSpeed;
        apPrevSpeed = speedNow;
        if (capNow != null && capNow.phase == AutopilotMath.PH_FLARE) {
            // stage D1, ВЫРАВН.: the throttle closed, never under the stall's margin
            return AutopilotMath.flareThrottle(thr, speedNow, flightParams(getVehicleType()).stall);
        }
        if ((apModes & AutopilotMath.M_SPD) == 0) {
            return thr;
        }
        return AutopilotMath.speedThrottle(thr, speedNow, prev, cruiseSpeed, AutopilotMath.SPD_MAX_THROTTLE);
    }

    /** ★11: the road surface under the vehicle's wheels / tracks (RoadMath.NONE / ASPHALT / CONCRETE). */
    public int roadSurface() {
        return com.sva.block.BlockRoadSVA.surfaceAt(worldObj, posX, posY, posZ);
    }

    /** ★11: top speed factor of the surface under it for this type (1 for anything that doesn't roll on wheels / tracks). */
    public double roadSpeedMul(VehicleType t) {
        return RoadMath.onWheels(t.movement) ? RoadMath.speedMul(roadSurface(), t.movement) : 1;
    }

    /**
     * ★1: plays one of this addon's one-shot sounds (sounds.json) at the vehicle for everyone near; with vehicles.sounds
     * off the vanilla stand-ins of before (a hit, the charger's chime, a broken block) or nothing.
     */
    public void oneShot(String event, float volume, float pitch) {
        String name = soundName(event);
        if (name != null) {
            worldObj.playSoundAtEntity(this, name, volume, pitch);
        }
    }

    /** The sound event for a one-shot: this addon's, or with vehicles.sounds off its vanilla stand-in / null. */
    public static String soundName(String event) {
        if (ConfigSVA.sounds) {
            return com.sva.ReferenceSVA.ASSETS + ":" + event;
        }
        return "impact".equals(event) ? "random.anvil_land" : "ding".equals(event) ? "note.pling" : "drill_ore".equals(event)
                ? "random.break" : null;
    }

    /** The synced gas string: the tank amounts in VehicleState.tanks order, comma separated (the fuel part: gasSync). */
    static String gasString(VehicleState st) {
        StringBuilder b = new StringBuilder();
        for (Gas g : st.tanks()) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(st.gas(g));
        }
        return b.toString();
    }

    /** Server: the gas string + "|" + the hydrogen wave's numbers (FS_* order) - one watcher string for both. */
    private String gasSync() {
        boolean h2 = state.hydrogen();
        if (!h2) {
            return gasString(state);
        }
        return gasString(state) + "|1," + Math.round(h2Flow) + "," + (state.batIn ? 1 : 0) + "," + Math.round(state.batHealth * 1000) + ","
                + Math.round(state.batLimit * 1000) + "," + state.batServices + "," + Math.round(batNet * 10) + "," + Math.round(genNow * 10) + ","
                + (kitTicks > 0 ? 100 - kitTicks * 100 / BatteryMath.KIT_TICKS : -1) + "," + (jumpTicks > 0 ? 100 - jumpTicks * 100 / BatteryMath.JUMP_TICKS : -1);
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
        state.hp = Math.min(state.maxHp(), state.hp + amount);
        if (state.hp >= state.maxHp()) {
            state.fails = 0;                                        // D2: the hull whole again - the failures mended too
        }
        syncWatcher();
    }

    /** Wave 2, ★8 (the garage): new upgrades (sanitised), limits re-applied, synced to everyone. */
    public void setTuning(int[] slots) {
        state.setTuning(slots);
        syncWatcher();
    }

    /** Wave 2, ★7 (the garage finished a job): the hull whole, and for «Чинить всё» the engine cooled down. */
    public void garageRepaired(boolean all) {
        state.hp = state.maxHp();
        state.fails = 0;                                            // D2 (★7): the failures repaired
        if (all && state.liner() != null) {
            state.liner().gear.service();                           // liner wave 2: new tyres, the brakes cooled
        }
        ice = 0;
        if (all) {
            state.heat = 0;
            state.heatStop = false;
        }
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
        boolean h2 = onHydrogen();
        // hydrogen wave: an aircraft flies on its engine (the battery only runs the board), the hover's cushion on the battery
        if (!motor || isWrecked() || isHeatStopped() || (!h2 || !t.movement.flies) && getEnergy() <= 0) {
            return false;
        }
        if ((engineGated(t) || h2) && t.movement.flies && !SystemsMath.lifts(engineState())) {
            return false;                                           // D2: the engine is off / cranking; hydrogen: out of H2 too
        }
        return h2 || t.gases.liftGas == null || getGas(t.gases.liftGas) > 0;      // aircraft fly on EU alone
    }

    /** Aircraft in the air without power: the heli autorotates, the plane glides (server: this tick; clients: synced). */
    public boolean isAutorotating() {
        return worldObj.isRemote ? flag(F_AUTO) : autoNow;
    }

    /** A plane below its stall speed in the air (server: this tick; clients: synced). */
    public boolean isStalling() {
        if (worldObj.isRemote) {
            return localDriver() ? localStall : flag(F_STALL);     // the pilot: from the own physics, as the nose drops
        }
        return stallNow;
    }

    private boolean localDriver() {
        return riddenByEntity != null && riddenByEntity == SVAMod.proxy.localPlayer();
    }

    // ------------------------------------------------------------------ aircraft stage A: flaps, slats, spoilers

    private int hiLiftBits() {
        return dataWatcher.getWatchableObjectInt(DW_HILIFT);
    }

    private boolean remote() {
        return worldObj != null && worldObj.isRemote;
    }

    /** The flap setting (0 .. the type's last; 0 without high-lift devices). Server: now; clients: synced. Stage C's indicator. */
    public int getFlaps() {
        if (remote() && getVehicleType() == VehicleType.LINER && liner() != null) {
            return liner().fctl.flapSetting();                       // liner wave 1: five settings (the word has 2 bits); wave 2: reached
        }
        return remote() ? hiLiftBits() & HighLiftMath.P_FLAPS : flaps;
    }

    /** The flap setting's nominal deflection (deg; the tiltrotor's flaperons in airplane mode). */
    public float getFlapAngle() {
        HighLiftMath.Spec s = HighLiftMath.spec(getVehicleType());
        return s == null ? 0F : s.angles[Math.min(getFlaps(), s.last())];
    }

    /** The pilot's «Воздушный тормоз» is on. */
    public boolean isAirbrake() {
        return remote() ? (hiLiftBits() & HighLiftMath.P_AIRBRAKE) != 0 : airbrake;
    }

    /** The spoilers / the jet's speed brake are up: the pilot's airbrake or the ground spoilers after a touch-down. */
    public boolean spoilersUp() {
        return isAirbrake() || groundSpoilersUp();
    }

    /** The ground spoilers alone (up by themselves at a touch-down at low throttle). */
    public boolean groundSpoilersUp() {
        return remote() ? (hiLiftBits() & HighLiftMath.P_GROUND) != 0 : groundSpoilers;
    }

    /** The automatic slats are out. */
    public boolean slatsOut() {
        return remote() ? (hiLiftBits() & HighLiftMath.P_SLATS) != 0 : slats;
    }

    /** The flaps' overspeed warning («V>120 ЗАКР»): a refused / blown-back setting a moment ago. */
    public boolean flapWarning() {
        return remote() ? (hiLiftBits() & HighLiftMath.P_WARN) != 0 : flapWarn > 0;
    }

    /** The jet's speed brake opens only part way now (over its speed). */
    public boolean brakeLimited() {
        HighLiftMath.Spec s = HighLiftMath.spec(getVehicleType());
        return remote() ? (hiLiftBits() & HighLiftMath.P_FAST) != 0 : s != null && HighLiftMath.brakeDeg(s, airspeedNow) < s.brakeDeg;
    }

    /** The stall speed now (b/t): the type's, lowered by the flaps / slats (the HUD's mark). */
    public double stallSpeed() {
        return flightParams(getVehicleType()).stall;
    }

    /**
     * The PlaneParams the aeroplane physics flies with now (both sides alike - the client from the synced bits): the
     * type's own (flaps 0, slats in, nothing up - the old flight exactly), or HighLiftMath.params with the flaps, the
     * slats, the airbrake (in the air) and the ground spoilers (on the ground; the pilot's airbrake there works as them).
     */
    public PlaneParams flightParams(VehicleType t) {
        HighLiftMath.Spec s = HighLiftMath.spec(t);
        if (s == null || t.plane == null) {
            return t.plane;
        }
        boolean ab = isAirbrake(), air = !onGround;
        return HighLiftMath.params(s, t.plane, getFlaps(), slatsOut(), ab && air, !air && (ab || groundSpoilersUp()));
    }

    /**
     * Server, aircraft, once a tick (flightTick, before wasAirborne moves on): the protection blows the flaps back over
     * their speed (warning + beep), the slats follow the airspeed / angle of attack, the pilot's airbrake retracts over
     * half throttle, the ground spoilers come up at a touch-down at low throttle and go down on the throttle / stopped.
     * Off (config vehicles.highLift false) or a type without devices: all retracted - the old flight.
     */
    private void highLiftTick(VehicleType t, boolean air, boolean plane, double dy) {
        HighLiftMath.Spec s = HighLiftMath.spec(t);
        if (s == null || !ConfigSVA.highLift) {
            flaps = flapWarn = 0;
            airbrake = groundSpoilers = slats = false;
            return;
        }
        double as = airspeedNow;
        if (flapWarn > 0) {
            flapWarn--;
        }
        if (t == VehicleType.LINER && state.liner() != null) {
            // liner wave 1: the flap and speed brake levers set them (no blow-back - the A320's flaps stay where the lever is);
            // wave 2: the hydraulics move them (LinerFctl: the flaps / slats where they have got to, the speed brake and the
            // ground spoilers - armed + touch-down or the RTO - only with their spoilers powered); the slats' automatic rule
            // goes to the next tick's systems
            com.sva.vehicle.LinerSystems L = state.liner();
            LinerLogicSVA.applyLevers(this, L);
            linerSlatsAuto = plane && HighLiftMath.slats(s, linerSlatsAuto, as, HighLiftMath.aoa(t.plane.stall, as, dy), flaps);
            slats = plane && L.fctl.slatsOut();
            boolean gs0 = plane && L.fctl.groundSpoilersWork();
            if (gs0 && !groundSpoilers) {
                oneShot("door", 0.3F, 1.5F);
            }
            groundSpoilers = gs0;
            return;
        }
        int f = (state.fails & SystemsMath.F_FLAP) != 0 ? flaps : HighLiftMath.blowBack(s, flaps, as);   // D2: jammed - stays
        if (f != flaps) {
            flaps = f;
            flapWarn = HighLiftMath.WARN_TICKS;
            oneShot("ding", 0.5F, 1.8F);                            // the overspeed beep
            oneShot("door", 0.3F, 1.7F);                            // the flap motor
            tellRiders("sva.msg.flaps.blowBack");
        }
        slats = plane && HighLiftMath.slats(s, slats, as, HighLiftMath.aoa(t.plane.stall, as, dy), flaps);
        boolean ab = HighLiftMath.airbrakeHeld(airbrake, throttle);
        if (airbrake && !ab) {
            oneShot("door", 0.25F, 1.9F);
        }
        airbrake = ab;
        boolean gs = plane && HighLiftMath.groundSpoilers(s, groundSpoilers, wasAirborne, air, throttle, as);
        if (gs && !groundSpoilers) {
            oneShot("door", 0.3F, 1.5F);                            // the spoilers come up on the roll-out
        }
        groundSpoilers = gs;
    }

    /** Server: «Закрылки +» / «−» from the pilot (serverAction): a step, or refused over the next setting's speed. */
    private void flapCommand(EntityPlayer p, HighLiftMath.Spec s, int dir) {
        if (!ConfigSVA.highLift) {
            msg(p, "sva.msg.highLift.disabled");
            return;
        }
        if ((state.fails & SystemsMath.F_FLAP) != 0) {
            oneShot("ding", 0.5F, 0.9F);
            msg(p, "sva.msg.flaps.jammed");                         // D2 (★7): «ЗАКР. ЗАКЛИН.» until the garage
            return;
        }
        int r = HighLiftMath.flapStep(s, flaps, dir, airspeedNow);
        if (r == HighLiftMath.REFUSED_SPEED) {
            flapWarn = HighLiftMath.WARN_TICKS;
            oneShot("ding", 0.5F, 1.8F);
            msg(p, "sva.msg.flaps.overspeed", Integer.valueOf(Math.round(s.angles[flaps + 1])), Integer.valueOf(HighLiftMath.limitKmh(s, flaps + 1)));
        } else if (r >= 0) {
            flaps = r;
            oneShot("door", 0.3F, 1.7F);                            // the flap motor (a step takes 1.5 s)
        }
        syncWatcher();
    }

    /** Server: «Воздушный тормоз» from the pilot - on / off (not over half throttle: it would retract at once). */
    private void airbrakeCommand(EntityPlayer p) {
        if (!ConfigSVA.highLift) {
            msg(p, "sva.msg.highLift.disabled");
            return;
        }
        if (!airbrake && throttle > HighLiftMath.AIRBRAKE_RETRACT) {
            msg(p, "sva.msg.airbrake.throttle", Integer.valueOf((int) Math.round(HighLiftMath.AIRBRAKE_RETRACT * 100)));
            return;
        }
        airbrake = !airbrake;
        oneShot("door", 0.3F, airbrake ? 1.5F : 1.9F);
        syncWatcher();
    }

    /** World test: set the high-lift state directly (flaps, the pilot's airbrake, the ground spoilers). */
    public void debugHighLift(int flapSetting, boolean brake, boolean ground) {
        flaps = flapSetting;
        airbrake = brake;
        groundSpoilers = ground;
        syncWatcher();
    }

    /** World test: the pilot's flap key as the server takes it at `airspeed` (no chat). Returns the setting after it. */
    public int debugFlapKey(int dir, double airspeed) {
        HighLiftMath.Spec s = HighLiftMath.spec(getVehicleType());
        if (s == null) {
            return -1;
        }
        int r = HighLiftMath.flapStep(s, flaps, dir, airspeed);
        if (r >= 0) {
            flaps = r;
        } else if (r == HighLiftMath.REFUSED_SPEED) {
            flapWarn = HighLiftMath.WARN_TICKS;
        }
        return flaps;
    }

    /** Server: the airspeed the stall / flying-move rules judged this tick (PlaneMath.reportedAirspeed for a driver's client). */
    // ------------------------------------------------------------------ aircraft round 2, D2: board systems

    /** The synced board word (SystemsMath.pack). */
    private int sysWord() {
        return dataWatcher.getWatchableObjectInt(DW_SYS);
    }

    /** The overhead switches (SystemsMath.SW_* bits). Server: the state's; clients: synced. */
    public int switches() {
        return remote() ? SystemsMath.switches(sysWord()) : SystemsMath.effective(state.type, state.switches);
    }

    public boolean switchOn(int i) {
        return SystemsMath.on(switches(), i);
    }

    /** Stage D5: this type has the INS (SystemsMath.insApplies - the D2 aircraft and the helicopter). */
    public boolean insApplies() {
        return SystemsMath.insApplies(getVehicleType());
    }

    /** Stage D5: the INS status (SystemsMath.INS_*); a type without one: INS_READY. Server: this tick's; clients: synced. */
    public int insStatus() {
        if (!insApplies()) {
            return SystemsMath.INS_READY;
        }
        return remote() ? SystemsMath.insStatus(sysWord()) : insNow;
    }

    /** Stage D5: the INS is aligned - attitude, heading, the map and the autopilot are there. */
    public boolean insReady() {
        return insStatus() == SystemsMath.INS_READY;
    }

    /** Stage D5: whole seconds left of the INS alignment (0 when not aligning). */
    public int insSecondsLeft() {
        if (insStatus() != SystemsMath.INS_ALIGN) {
            return 0;
        }
        return remote() ? SystemsMath.insSeconds(hiLiftBits())
                : SystemsMath.secondsLeft(ins.done, SystemsMath.alignTicks(ConfigSVA.insAlignSeconds));
    }

    /** Stage D5: the alignment just started over (moved while aligning) - the ВЫСТ. lamp flashes. */
    public boolean insRestarted() {
        return insApplies() && (remote() ? SystemsMath.insFlash(hiLiftBits()) : ins.flash > 0);
    }

    /** The board has power: АКБ on and the battery not flat. */
    public boolean boardOn() {
        return remote() ? switchOn(SystemsMath.SW_BAT) && SystemsMath.batteryPct(sysWord()) > 0 : SystemsMath.electric(state.switches, state.battery);
    }

    /** The battery 0..100 %. */
    public int batteryPct() {
        return remote() ? SystemsMath.batteryPct(sysWord()) : (int) Math.round(state.battery * 100 / SystemsMath.BAT_MAX);
    }

    /** The engine's state machine works for this type: the D2 aircraft, and (hydrogen wave) every vehicle on hydrogen. */
    public boolean engineApplies(VehicleType t) {
        return SystemsMath.applies(t) || FuelMath.uses(t) && onHydrogen();
    }

    /** The engine's state (SystemsMath.OFF .. RUN); a type without the board systems: RUN. */
    public int engineState() {
        if (!engineApplies(getVehicleType())) {
            return SystemsMath.RUN;
        }
        return remote() ? SystemsMath.engine(sysWord()) : engine.state;
    }

    /** Ticks in the engine's current state (server: the machine's; clients: counted since the synced state changed). */
    public int engineTicks() {
        return remote() ? engineSeenTicks : engine.ticks;
    }

    /** The start sequence must be flown (vehicles.engineStart on the server, the type has the board systems). */
    public boolean engineGated(VehicleType t) {
        return engineApplies(t) && (remote() ? (sysWord() & SystemsMath.P_CFG_ENGINE) != 0 : ConfigSVA.engineStart);
    }

    /** The failures (SystemsMath.F_*). */
    public int failures() {
        return remote() ? SystemsMath.fails(sysWord()) : state.fails;
    }

    /** The failed instrument's slot (meaningful with F_INSTR; the client takes it modulo its dials). */
    public int failedInstrument() {
        return remote() ? SystemsMath.instr(sysWord()) : state.failInstr;
    }

    /** The windscreen's frost 0..3 (ОБОГРЕВ off in the cold). */
    public int frost() {
        return remote() ? SystemsMath.frostLevel(sysWord()) : SystemsMath.frost(ice);
    }

    /** The pitot iced: the airspeed indicator frozen. */
    public boolean pitotIced() {
        return remote() ? (sysWord() & SystemsMath.P_ICED) != 0 : SystemsMath.iced(ice);
    }

    /** ОБОГРЕВ working now (on, the board on, in the cold). */
    public boolean heating() {
        return remote() ? (sysWord() & SystemsMath.P_HEATING) != 0 : heatingNow;
    }

    /** The air is cold here (over SystemsMath.COLD_Y or in falling snow). */
    public boolean coldAir() {
        return remote() ? (sysWord() & SystemsMath.P_COLD) != 0 : coldNow;
    }

    /** The master caution «ВНИМАНИЕ». */
    public boolean caution() {
        return SystemsMath.caution(failures(), pitotIced());
    }

    /**
     * The engine's power factor now: the warm-up's ramp x a failed engine's ENGINE_POWER (1 for a type without the board
     * systems; vehicles.engineStart off: only the failure).
     */
    public double enginePower() {
        VehicleType t = getVehicleType();
        if (!SystemsMath.applies(t)) {
            return 1;
        }
        float p = engineGated(t) ? SystemsMath.power(engineState(), engineTicks(), SystemsMath.warmTicks(t)) : 1F;
        return p * SystemsMath.failPower(failures());
    }

    /**
     * Hydrogen wave: the drive has power - on hydrogen the engine turns (warm-up or running), in EU mode charge left
     * (an empty EU vehicle still crawls - driveCharge).
     */
    public boolean motorDrives() {
        return onHydrogen() ? SystemsMath.lifts(engineState()) : getEnergy() > 0;
    }

    /** The «charge» the ground physics' top speed is judged by (VehicleMath.maxSpeed): on hydrogen 1 / 0 by the engine. */
    public double driveCharge() {
        return onHydrogen() ? (motorDrives() ? 1 : 0) : getEnergy();
    }

    /** A light of this kind (VehicleLights.*) is switched on (aircraft: its overhead switch; others: always). */
    public boolean lightOn(int kind) {
        return SystemsMath.lightOn(SystemsMath.applies(getVehicleType()), switches(), kind);
    }

    /**
     * Server, once a tick before the physics (serverUpdate): the board systems - the battery, the engine's machine
     * (auto-start from the keys, the crank / catch / warm-up, stops), the heat and the ice, the failure roll.
     * @return EU the board took this tick (ГЕН's charging, the heat) - added to the tick's cost
     */
    private double systemsTick(VehicleType t, boolean motorOn, int in, boolean pilot) {
        if (t == VehicleType.LINER) {
            return LinerLogicSVA.tick(this, pilot, motorOn ? in : 0);   // liner wave 1: its own systems drive the old words
        }
        boolean d2 = SystemsMath.applies(t), h2 = state.hydrogen();
        if (!d2 && !h2) {
            return 0;
        }
        // hydrogen wave: the engine burns H2 (the «cells» are the tank), the D2 battery word mirrors the removable battery
        boolean cells = (h2 ? state.gas(FuelMath.H2) > 0 : state.energy > 0) && !isWrecked();
        if (h2) {
            state.battery = batteryUnits();
        }
        int sw = d2 ? state.switches : SystemsMath.ALL_ON;
        boolean air = wasAirborne;
        boolean busy = pilot || engine.state != SystemsMath.OFF;
        // the engine: no pilot for IDLE_STOP ticks - stopped; auto-start from the keys; the world tests' instant engine
        noPilot = pilot ? 0 : noPilot + 1;
        if (noPilot > (d2 ? SystemsMath.IDLE_STOP : FuelMath.IDLE_STOP) && engine.state != SystemsMath.OFF) {
            engine.stop();
        }
        boolean gated = ConfigSVA.engineStart;
        if (!gated || testInstantEngine && !debugEngineManual) {
            if (engine.state != SystemsMath.RUN && pilot && cells && SystemsMath.on(sw, SystemsMath.SW_BAT) && (!h2 || batteryStart() == BatteryMath.OK)) {
                engine.run();
            }
        } else if (autoWait > 0) {
            autoWait--;
        } else if (pilot && SystemsMath.autoStart(engine.state, sw, (in & IN_FWD) != 0 || !d2 && (in & IN_BACK) != 0, (in & IN_BRAKE) != 0,
                t.movement == Movement.TILT && !state.tiltPlane || t.movement == Movement.HELI)) {
            int r0 = startEngine(null);
            if (r0 != SystemsMath.R_STARTED) {
                autoWait = AUTO_RETRY;                              // a flat battery / no charge: said once, tried again in 3 s
            }
        }
        boolean windmill = air && airspeedNow > (t.plane != null ? t.plane.stall : 0.5);
        double misfire = d2 && ConfigSVA.failures && !(testInstantEngine && !debugEngineManual)
                ? SystemsMath.MISFIRE_K * SystemsMath.chancePerMinute(wear()) : 0;
        if (h2 && !(testInstantEngine && !debugEngineManual)) {
            misfire = Math.max(misfire, BatteryMath.misfire(state.batHealth));     // a worn battery: it catches on the 2nd-3rd try
        }
        boolean wasTurning = SystemsMath.lifts(engine.state);
        int r = engine.tick(sw, state.battery, cells, crankTicksFor(t, windmill), warmTicksFor(t), rand.nextDouble(), misfire);
        if (r != SystemsMath.R_NONE) {
            engineResult(r, null);
            if (r == SystemsMath.R_MISFIRE || r == SystemsMath.R_STALLED) {
                autoWait = AUTO_RETRY;
            }
            if (r == SystemsMath.R_STOPPED && h2 && wasTurning && state.gas(FuelMath.H2) <= 0) {
                tellRiders("sva.msg.fuel.out");                    // «НЕТ ВОДОРОДА - ДВИГАТЕЛЬ ЗАГЛОХ»
            }
        }
        if (!d2) {
            return 0;                                               // the switches, the heat, the ice and the failures are the aircraft's
        }
        // the battery (hydrogen: serverUpdate's electrics step charges / drains the removable one)
        double eu = 0;
        if (!h2) {
            double before = state.battery;
            state.battery = SystemsMath.batteryStep(before, state.switches, cells, busy, engine.state == SystemsMath.CRANK, chargeTicks > 0);
            eu = SystemsMath.batteryEu(before, state.battery, state.switches, cells, chargeTicks > 0);
        }
        // the heat: in the cold it costs EU (the board on, someone aboard / the engine turning); off - the pitot ices
        int x = MathHelper.floor_double(posX), z = MathHelper.floor_double(posZ);
        coldNow = SystemsMath.cold(posY, worldObj.isRaining() && worldObj.getBiomeGenForCoords(x, z).getEnableSnow()
                && worldObj.canBlockSeeTheSky(x, MathHelper.floor_double(posY + 1), z));
        boolean board = SystemsMath.electric(state.switches, state.battery);
        heatingNow = coldNow && board && busy && SystemsMath.on(state.switches, SystemsMath.SW_HEAT);
        if (heatingNow) {
            eu += t.cruiseCost * SystemsMath.HEAT_EU_SHARE;
        }
        ice = busy ? SystemsMath.iceStep(ice, coldNow, heatingNow) : SystemsMath.iceStep(ice, false, false);
        // the failures: a roll a minute of flight with the engine running (two at most); not in the world tests
        if (ConfigSVA.failures && ConfigSVA.wear && air && SystemsMath.lifts(engine.state) && !(testInstantEngine && !debugEngineManual)) {
            if (++failTimer >= SystemsMath.CHECK_TICKS) {
                failTimer = 0;
                if (rand.nextDouble() < SystemsMath.chancePerMinute(wear())) {
                    fail(SystemsMath.pick(rand.nextDouble(), state.fails, HighLiftMath.spec(t) != null), rand.nextInt(SystemsMath.INSTR_SLOTS));
                }
            }
        }
        return eu * Math.max(0, ConfigSVA.consumption);
    }

    /** Wear 0..1 (1 - hp / max). */
    private double wear() {
        return 1 - state.hp / Math.max(1F, state.maxHp());
    }

    /**
     * Stage D5, server, once a tick (serverUpdate, before the autopilot's judgement): the INS - on with the board powered
     * it aligns standing still; the switch off / the power lost drops the alignment (an engaged autopilot lets go by
     * cruiseBlocked - OFF_INS); vehicles.ins off - always aligned. The riders hear when it is aligned.
     */
    private void insTick(VehicleType t) {
        boolean moving = false;
        if (!Double.isNaN(insX)) {
            double dx = posX - insX, dy = posY - insY, dz = posZ - insZ;
            moving = dx * dx + dy * dy + dz * dz > SystemsMath.ALIGN_SPEED * SystemsMath.ALIGN_SPEED;
        }
        insX = posX;
        insY = posY;
        insZ = posZ;
        if (!SystemsMath.insApplies(t)) {
            insNow = SystemsMath.INS_READY;
            return;
        }
        boolean on = SystemsMath.on(state.switches, SystemsMath.SW_INS), power = powered();
        int was = insNow;
        if (!ConfigSVA.ins) {
            ins.align();
            insNow = SystemsMath.INS_READY;
            return;
        }
        if (on && power && testInstantEngine && !debugInsManual && !ins.ready) {
            ins.align();                                            // world tests: the INS aligned at once (as the engine)
        }
        insNow = ins.tick(on, power, moving, SystemsMath.alignTicks(ConfigSVA.insAlignSeconds));
        if (was != insNow) {
            if (insNow == SystemsMath.INS_READY && was == SystemsMath.INS_ALIGN) {
                tellRiders("sva.msg.ins.ready");                     // «ИНС выставлена»
            } else if (was == SystemsMath.INS_READY && on && !power) {
                tellRiders("sva.msg.ins.lost");                      // the power gone: «ИНС - нет питания, выставка сброшена»
            }
        }
    }

    /** Server: a failure comes on (F_* bit, the instrument's slot): the riders are told, the master caution sounds. */
    private void fail(int f, int slot) {
        if (f == 0 || (state.fails & f) != 0) {
            return;
        }
        state.fails |= f;
        if (f == SystemsMath.F_INSTR) {
            state.failInstr = Math.max(0, Math.min(SystemsMath.INSTR_SLOTS - 1, slot));
        }
        oneShot("ding", 0.6F, 0.7F);
        tellRiders(f == SystemsMath.F_ENGINE ? "sva.msg.fail.engine" : f == SystemsMath.F_FLAP ? "sva.msg.fail.flap" : "sva.msg.fail.instr");
        syncWatcher();
    }

    /** A vanilla one-shot at the vehicle (the starter's relay, the catch's bang) - only with vehicles.sounds. */
    private void vanillaShot(String name, float volume, float pitch) {
        if (ConfigSVA.sounds) {
            worldObj.playSoundAtEntity(this, name, volume, pitch);
        }
    }

    /** Server: what a start / the engine's tick came to - sounds, a chat line to the pilot (p, else the riders). */
    private void engineResult(int r, EntityPlayer p) {
        boolean jet = SystemsMath.jet(state.type);
        switch (r) {
            case SystemsMath.R_STARTED:
                vanillaShot("random.click", 0.5F, 1.3F);                // the starter's relay
                break;
            case SystemsMath.R_CATCH:
                vanillaShot(jet ? "mob.ghast.fireball" : "random.explode", jet ? 0.5F : 0.3F, jet ? 0.8F : 1.8F);
                break;
            case SystemsMath.R_READY:
                oneShot("ding", 0.35F, 1.6F);
                break;
            case SystemsMath.R_MISFIRE:
                vanillaShot("random.explode", 0.2F, 2.0F);
                tellOrMsg(p, "sva.msg.engine.misfire");
                break;
            case SystemsMath.R_STALLED:
                vanillaShot("random.click", 0.4F, 0.7F);
                tellOrMsg(p, "sva.msg.engine.lowBattery");
                break;
            case SystemsMath.R_NO_MASTER:
                vanillaShot("random.click", 0.3F, 0.8F);
                tellOrMsg(p, "sva.msg.engine.noMaster");
                break;
            case SystemsMath.R_LOW_BATTERY:
                vanillaShot("random.click", 0.3F, 0.8F);
                tellOrMsg(p, "sva.msg.engine.lowBattery");
                break;
            case SystemsMath.R_NO_CHARGE:
                tellOrMsg(p, "sva.msg.engine.noCharge");
                break;
            default:
                break;
        }
        syncWatcher();
    }

    private void tellOrMsg(EntityPlayer p, String key) {
        if (p != null) {
            msg(p, key);
        } else {
            tellRiders(key);
        }
    }

    /**
     * Hydrogen wave: the battery's word for the D2 machine (units 0..BAT_MAX of its charge; 0 with none in) - the
     * overhead АКБ / ГЕН logic, the start's BAT_START and the crank's stall read it.
     */
    private double batteryUnits() {
        double m = state.maxEnergy();
        return !state.batIn || m <= 0 ? 0 : Math.max(0, Math.min(SystemsMath.BAT_MAX, state.energy / m * SystemsMath.BAT_MAX));
    }

    /** Hydrogen wave: may the battery start the engine now (BatteryMath.OK / NONE / DEAD / FLAT). */
    private int batteryStart() {
        return BatteryMath.start(state.batIn, state.batHealth, state.energy, state.maxEnergy());
    }

    /**
     * Server: a start asked for (the key, the menu, the auto-start; p - who to tell, null: the riders). On hydrogen the
     * battery is asked first («НЕТ АКБ», «АКБ НЕ ДЕРЖИТ ЗАРЯД», «АКБ РАЗРЯЖЕН»), the tank is the engine's «cells».
     * @return SystemsMath.R_* (R_LOW_BATTERY for any battery refusal)
     */
    private int startEngine(EntityPlayer p) {
        boolean h2 = state.hydrogen();
        if (h2 && engine.state == SystemsMath.OFF) {
            int b = batteryStart();
            if (b != BatteryMath.OK) {
                vanillaShot("random.click", 0.3F, 0.8F);
                tellOrMsg(p, b == BatteryMath.NONE ? "sva.msg.bat.none" : b == BatteryMath.DEAD ? "sva.msg.bat.dead" : "sva.msg.bat.flat");
                return SystemsMath.R_LOW_BATTERY;
            }
            if (state.gas(FuelMath.H2) <= 0) {
                tellOrMsg(p, "sva.msg.fuel.empty");
                return SystemsMath.R_NO_CHARGE;
            }
        }
        boolean cells = (h2 ? state.gas(FuelMath.H2) > 0 : state.energy > 0) && !isWrecked();
        if (h2) {
            state.battery = batteryUnits();
        }
        int r = engine.start(SystemsMath.applies(state.type) ? state.switches : SystemsMath.ALL_ON, state.battery, cells);
        engineResult(r, p);
        return r;
    }

    /** Server: «Запуск двигателя» from the pilot (serverAction): off - start; turning - stop. */
    private void engineCommand(EntityPlayer p) {
        if (!ConfigSVA.engineStart) {
            msg(p, "sva.msg.engine.always");
            return;
        }
        if (engine.state == SystemsMath.OFF) {
            startEngine(p);
        } else {
            engine.stop();
            vanillaShot("random.click", 0.4F, 0.9F);
            msg(p, "sva.msg.engine.stopped");
            syncWatcher();
        }
    }

    /** Server: an overhead switch flipped by the pilot (serverAction). АКБ off stops the engine (its tick does). */
    private void switchCommand(EntityPlayer p, int i) {
        if (i == SystemsMath.SW_INS && !ConfigSVA.ins) {
            msg(p, "sva.msg.ins.always");                          // D5: vehicles.ins off - always aligned
            return;
        }
        state.switches = SystemsMath.toggle(state.switches, i);
        vanillaShot("random.click", 0.35F, SystemsMath.on(state.switches, i) ? 1.6F : 1.2F);
        if (i == SystemsMath.SW_BAT && !SystemsMath.on(state.switches, i) && engine.state != SystemsMath.OFF) {
            engine.stop();
        }
        syncWatcher();
    }

    /** World test: the engine's state set directly (SystemsMath.OFF .. RUN). */
    public void debugEngine(int st) {
        if (st == SystemsMath.OFF) {
            engine.stop();
        } else if (st == SystemsMath.RUN) {
            engine.run();
        } else {
            engine.state = st;
            engine.ticks = 0;
        }
        syncWatcher();
    }

    /** World test: «Запуск двигателя» as the server takes it (no chat). Returns the start's SystemsMath.R_*. */
    public int debugStart() {
        int r;
        if (state.hydrogen()) {
            state.battery = batteryUnits();
            r = batteryStart() != BatteryMath.OK ? SystemsMath.R_LOW_BATTERY
                    : engine.start(SystemsMath.applies(state.type) ? state.switches : SystemsMath.ALL_ON, state.battery, state.gas(FuelMath.H2) > 0 && !isWrecked());
        } else {
            r = engine.start(state.switches, state.battery, state.energy > 0 && !isWrecked());
        }
        syncWatcher();
        return r;
    }

    /** World test: a switch set. */
    public void debugSwitch(int i, boolean on) {
        if (SystemsMath.on(state.switches, i) != on) {
            state.switches = SystemsMath.toggle(state.switches, i);
            if (i == SystemsMath.SW_BAT && !on) {
                engine.stop();
            }
        }
        syncWatcher();
    }

    /** World test (D5): the INS through its real alignment (`manual`) or the tests' instant one; the alignment restarts. */
    public void debugIns(boolean manual) {
        debugInsManual = manual;
        ins.reset();
        insNow = SystemsMath.on(state.switches, SystemsMath.SW_INS) ? SystemsMath.INS_FAULT : SystemsMath.INS_OFF;
        syncWatcher();
    }

    /** World test: a failure injected (F_* bits; the instrument's slot 0). */
    public void debugFail(int f) {
        state.fails |= f & SystemsMath.F_ALL;
        syncWatcher();
    }

    public double airspeedNow() {
        return airspeedNow;
    }

    /** World test: the server's measured speed EMA (blocks/tick). */
    public double measuredSpeed() {
        return speedEma;
    }

    /** World test: why the last Move was refused (0 taken; 1 reach / climb rules, 2 into blocks, 3 path, 4-5 hover, 6 rising unsupported). */
    public int refuseWhy() {
        return refuseWhy;
    }

    /** World test: Moves the server refused (a Correct is sent for them, at most one per 5 ticks). */
    public int movesRefused() {
        return movesRefused;
    }

    /** The mean of the last MOVED_WIN ticks' measured horizontal displacement (blocks/tick). */
    private double movedAvg() {
        double s = 0;
        for (double d : movedWin) {
            s += d;
        }
        return s / MOVED_WIN;
    }

    /**
     * The driver's client's reported airspeed, capped by the measured one (PlaneMath.reportedAirspeed): the EMA, the
     * last ticks' mean or `covered` (a Move's own displacement a tick - the reach rule bounds it) - whichever is most.
     */
    private double clientAirspeed(double reported, double covered) {
        return PlaneMath.reportedAirspeed(reported, Math.max(Math.max(speedEma, movedAvg()), covered));
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

    /** Wave 4 (★15): EU/t recovered this tick (braking, downhill, windmilling; clients: synced, tenths). */
    public double getRegenNow() {
        return worldObj.isRemote ? com.sva.vehicle.RegenMath.rate(dataWatcher.getWatchableObjectInt(DW_REGEN)) : regenNow;
    }

    /** Wave 4 (★15): the server's recovery efficiency in % (0: vehicles.regen off). */
    public int getRegenPct() {
        return worldObj.isRemote ? com.sva.vehicle.RegenMath.pct(dataWatcher.getWatchableObjectInt(DW_REGEN))
                : ConfigSVA.regen ? (int) Math.round(com.sva.vehicle.RegenMath.eff(ConfigSVA.regenEfficiency) * 100) : 0;
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
        dataWatcher.updateObject(DW_GAS, gasSync());
        dataWatcher.updateObject(DW_EXTRA, Integer.valueOf(extraBits()));
        dataWatcher.updateObject(DW_DUG, Integer.valueOf((int) Math.min(Integer.MAX_VALUE, state.dug)));
        dataWatcher.updateObject(DW_HEAD, Integer.valueOf(headLeft()));
        dataWatcher.updateObject(DW_HEAD_MAX, Integer.valueOf(state.drillHead == null ? 0 : state.drillHead.getMaxDamage()));
        dataWatcher.updateObject(DW_AUTO_DIST, Integer.valueOf(autoDist));
        dataWatcher.updateObject(DW_ROUTE, routeText());
        dataWatcher.updateObject(DW_OWNER, state.ownerName == null ? "" : state.ownerName);
        dataWatcher.updateObject(DW_TORCHES, Integer.valueOf(state.torches));
        dataWatcher.updateObject(DW_BOARD, Integer.valueOf(com.sva.vehicle.SkinMath.boardNumber(state.vehId)));
        dataWatcher.updateObject(DW_TUNE, Integer.valueOf(TuningMath.pack(state.tuning) | (ConfigSVA.tuning ? TUNE_ON : 0)
                | (ConfigSVA.cruise ? TUNE_CRUISE : 0) | (ConfigSVA.autopilot ? TUNE_AP : 0) | (ConfigSVA.locks ? TUNE_LOCKS : 0)));
        dataWatcher.updateObject(DW_TRAILER, trailerString());
        dataWatcher.updateObject(DW_REGEN, Integer.valueOf(com.sva.vehicle.RegenMath.pack(regenNow, ConfigSVA.regenEfficiency, ConfigSVA.regen)));
        dataWatcher.updateObject(DW_HILIFT, Integer.valueOf(HighLiftMath.pack(flaps, airbrake, groundSpoilers, slats, flapWarn > 0,
                HighLiftMath.spec(t) != null && HighLiftMath.brakeDeg(HighLiftMath.spec(t), airspeedNow) < HighLiftMath.spec(t).brakeDeg)
                // D5: the INS's seconds left / restart flash in the word's spare bits (changes once a second while aligning)
                | SystemsMath.packInsAux(insNow == SystemsMath.INS_ALIGN ? SystemsMath.secondsLeft(ins.done,
                        SystemsMath.alignTicks(ConfigSVA.insAlignSeconds)) : 0, ins.flash > 0)));
        if (SystemsMath.insApplies(t) || state.hydrogen()) {
            dataWatcher.updateObject(DW_SYS, Integer.valueOf(SystemsMath.pack(SystemsMath.effective(t, state.switches), engine.state, state.fails, state.failInstr,
                    state.battery, SystemsMath.frost(ice), (ConfigSVA.engineStart ? SystemsMath.P_CFG_ENGINE : 0)
                            | (ConfigSVA.failures ? SystemsMath.P_CFG_FAILS : 0) | (heatingNow ? SystemsMath.P_HEATING : 0)
                            | (coldNow ? SystemsMath.P_COLD : 0) | (SystemsMath.iced(ice) ? SystemsMath.P_ICED : 0)
                            | (SystemsMath.on(state.switches, SystemsMath.SW_GEN) && state.energy > 0 ? SystemsMath.P_CHARGING : 0),
                    insNow)));
        }
        if (CruiseMath.kind(t.movement, true) == CruiseMath.AUTOPILOT) {
            // aircraft stage C: the autopilot panel for every rider (the server's judgement) and the HSI's «База»
            boolean cap = cruiseNow && (apModes & AutopilotMath.M_CAP) != 0;
            if (cap) {
                AutopilotMath.Capture c = capture();
                capAligned = c != null && !c.out && c.aligned;
                capPhase = c == null || c.out ? AutopilotMath.PH_RWY : c.phase;
            } else {
                capAligned = false;
                capPhase = AutopilotMath.PH_RWY;
            }
            AutopilotMath.Panel ap = apPanel();
            dataWatcher.updateObject(DW_AP, Integer.valueOf(AutopilotMath.pack(ap)));
            dataWatcher.updateObject(DW_AP2, Integer.valueOf(AutopilotMath.pack2(ap)));
            if (ticksExisted % RUNWAY_EVERY == 1 || baseX == Integer.MIN_VALUE) {
                updateBase();
            }
            dataWatcher.updateObject(DW_BASE_X, Integer.valueOf(baseX));
            dataWatcher.updateObject(DW_BASE_Z, Integer.valueOf(baseZ));
        }
        if (t.bridgeDock && (ticksExisted % 10 == 0 || spaceDirty)) {
            spaceDirty = false;
            dataWatcher.updateObject(DW_SPACE, SpaceLogicSVA.hudString(this, t));
        } else if (t == VehicleType.LINER && state.liner() != null) {
            dataWatcher.updateObject(DW_SPACE, state.liner().pack());        // liner wave 1: its systems (the space line's id)
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
        stepHeight = tuningOn() ? TuningMath.stepHeight(t, getTuning()) : t.stepHeight;     // ★8 «Большие колёса»
        if (worldObj.isRemote) {
            clientUpdate(t);
        } else {
            serverUpdate(t);
        }
        placeSeats();
    }

    private void serverUpdate(VehicleType t) {
        long now = worldObj.getTotalWorldTime();
        if (!registered || ticksExisted % com.sva.vehicle.MapMath.UPDATE_EVERY == 0) {
            updateRegistry();                                       // ★19: the map's card (and the per-player count)
            registered = true;
        }
        maintainSeats(t);
        if (chargeTicks > 0) {
            chargeTicks--;
        }
        if (extTicks > 0) {
            extTicks--;                                             // liner wave 1: the station's external power
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
        autoDrive = autoDrive && AutoDelivery.target(state.autoState) >= 0 && autoReason != AutoDelivery.R_UNLOADED;   // paused: motor off
        boolean motorOn = riddenByEntity != null || debugDriver || autoDrive;
        if (!debugDriver && now - lastInputTick > INPUT_TIMEOUT) {
            input = 0;                                              // keys went stale: let go
        }
        int in = autoDrive ? autoIn : input;
        double sysEu = systemsTick(t, motorOn, in, riddenByEntity != null || debugDriver || autoDrive);   // D2: battery, engine, heat, failures
        insTick(t);                                                 // D5: the INS alignment (before the autopilot judges)
        cruiseNow = !autoDrive && motorOn && cruiseActive(in);
        routeServerTick();                                          // D4: МАРШ's points, the plan to the driver
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
        double emaBefore = speedEma;                                // ★15 рекуперация: the speed lost this tick
        speedEma = speedEma * 0.5 + moved * 0.5;
        movedWin[movedAt] = moved;
        movedAt = (movedAt + 1) % MOVED_WIN;
        // the aircraft rules' airspeed: the driver's client's own (capped by the measured), else the server's physics'
        airspeedNow = clientDriven ? clientAirspeed(moveSpeed, 0) : Math.max(0, speed);
        if (hitCooldown > 0) {
            hitCooldown--;
        }
        if (ConfigSVA.wear && hitCooldown == 0 && prevMoved > VehicleMath.HIT_SAFE && moved < prevMoved * 0.4 && blockedToward(lastDx, lastDz)) {
            float dmg = VehicleMath.hitDamage(Math.min(prevMoved, t.topSpeed * roadSpeedMul(t) * tuneSpeed()) - moved);   // capped: bunched packets aren't speed
            dmg *= (float) RoadMath.wearMul(roadSurface(), t.movement);                         // ★11: a road halves the wear
            if (railToward(lastDx, lastDz)) {
                dmg *= (float) com.sva.block.BlockGuardRailSVA.RAIL_WEAR;                        // ★11: the guard rail gives
            }
            if (dmg > 0) {
                damageHull(dmg);
                oneShot("impact", 0.7F, 0.9F);
                hitCooldown = 10;
            }
        }
        if (moved > 0.01) {
            lastDx = dx;
            lastDz = dz;
        }
        prevMoved = moved;
        if (state.trailer && state.trailerType != TrailerMath.CARGO) {
            trailerTick(motorOn);                                   // ★15: the tank's slots, the charger, the carried vehicle
        }
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
        boolean h2 = state.hydrogen();
        double boostMb = 0;
        if (boosting && t.gases.boostGas != null) {
            boostMb = t.gases.boostGasPerSecond / 20.0;
            state.drainGasFraction(t.gases.boostGas, boostMb);
        }
        if (lifting && t.gases.liftGas != null && !h2) {           // hydrogen wave: the hover's cushion runs on the battery
            state.drainGasFraction(t.gases.liftGas, t.gases.liftPerTick(boosting));
            if (riddenByEntity != null && riddenByEntity.isBurning()) {
                riddenByEntity.extinguish();                        // the antigravity field keeps the heat off the rider
            }
        }
        // energy and heat
        double fill = state.cargoFill();
        double v = Math.min(speedEma, t.topSpeed * tuneSpeed() * (boosting ? t.gases.boostMul : 1) * 1.2);
        boolean working = motorOn && (h2 ? SystemsMath.lifts(engine.state) && !isWrecked() : state.energy > 0) && (!t.movement.lifts || lifting);
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
        cost += sysEu;
        if (h2) {
            cost = hydrogenTick(t, v, fill, boostMb, sysEu);        // hydrogen wave: H2 for the drive, the battery for the electrics
            costNow = (float) cost;
        } else {
            costNow = (float) cost;
            state.energy = Math.max(0, state.energy - cost);
            regenTick(t, motorOn, in, emaBefore, moved, fill, cost);    // wave 4 (★15): braking / downhill / windmilling
        }
        batteryServiceTick();                                       // the field kit, the jump leads
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
        movesThisTick = 0;
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
            if (plane && t != VehicleType.LINER) {                  // liner wave 4: its levers are LinerAfs' (LinerLogicSVA)
                throttle = PlaneMath.throttleStep(throttle, (input & IN_FWD) != 0, (input & IN_BACK) != 0, lifting);
            } else if (tilt && TiltMath.transition(state.tiltPos()) && state.tiltPlane && lifting) {
                throttle = Math.max(throttle, TiltMath.CONVERT_THROTTLE);   // as TiltPhysicsSVA does on the client
            }
        }
        // the touch-down speed: the client's own steps when it drives (two Moves bunched into one tick are no harder hit)
        double stepDy = clientDriven && movesThisTick > 0 ? moveDyMin : dy;
        if (wasAirborne && !air && ticksExisted > 5) {
            double impact = Math.max(0, -Math.min(stepDy, lastDy));
            double dmg = plane ? PlaneMath.landingDamage(t.plane, state.maxHp(), impact, v, lifting) : heliLandingDamage(t, impact, !lifting);
            if (ConfigSVA.wear && (state.fails & SystemsMath.F_ENGINE) != 0 && SystemsMath.applies(t)) {
                dmg += SystemsMath.LANDING_WEAR * state.maxHp();     // D2 (★7): a landing on a failed engine
            }
            if (t == VehicleType.LINER && state.liner() != null && state.liner().gear.pos > 0.5) {
                // liner wave 1: the gear lever UP at the touch-down - on the belly (PlaneMath's belly share + the speed)
                dmg += PlaneMath.BELLY_FRAC * 2 * state.maxHp() + Math.max(0, v - t.plane.glideSpeed) * PlaneMath.BELLY_SPEED;
                tellRiders("sva.liner.msg.belly");
            }
            if (dmg > 0) {
                damageHull((float) dmg);
                oneShot("impact", 0.8F, 0.7F);
            } else if (plane && lifting && v > 0.2) {
                oneShot("squeak", 0.6F, 1F);     // ★1: the tyres touch the ground
            }
        }
        boolean wasAuto = autoNow;
        autoNow = air && !lifting && !isHeld();
        // the stall by the airspeed the flying physics really has (the client's when it drives - PlaneMath.reportedAirspeed;
        // the measured EMA blinked under the stall in level flight whenever a Move came a tick late)
        stallNow = plane && air && PlaneMath.stalled(flightParams(t), clientDriven ? airspeedNow : v);
        if (autoNow && !wasAuto && ticksExisted > 5) {
            tellRiders(plane ? "sva.msg.glide" : "sva.msg.autorotation");
        }
        if (plane && !air && ConfigSVA.wear && (input & IN_BACK) != 0 && throttle <= 0 && v > PlaneMath.BRAKE_WEAR_SPEED && !onRunway()) {
            damageHull(PlaneMath.BRAKE_WEAR);                       // skidding on bare ground - a runway brakes softly
        }
        for (Entity r : riders()) {
            r.fallDistance = 0;                                     // seated: no fall damage; off in the air - a normal fall
        }
        highLiftTick(t, air, plane, dy);
        wasAirborne = air;
        lastDy = stepDy;
        if (plane) {
            double thr = lifting ? Math.max(throttle, PlaneMath.throttleFloor(t, v)) : 0;
            return PlaneMath.cost(t, flightParams(t), thr, air, dy, posY, fill, working);
        }
        return tilt ? HeliMath.vtolCost(t, air, v, dy, fill, working) : HeliMath.cost(t, air, v, dy, fill, working);
    }

    /**
     * Wave 4 (★15 рекуперация, RegenMath), server, after the tick's EU was charged: the spent EU goes into the pool,
     * then a ground vehicle with a driver gets back a share of the speed it lost braking (measured, speedEma) and of the
     * height it lost rolling downhill; an aircraft in the air of the height it lost windmilling (glide / autorotation,
     * plane at idle throttle, helicopter's collective down) - only with charge left: an empty aircraft's generator has
     * no field (and the motor would restart and stop every tick). Never past the buffer, never more than share x spent.
     */
    private void regenTick(VehicleType t, boolean motorOn, int in, double vBefore, double moved, double fill, double spent) {
        regenNow = 0;
        double eff = com.sva.vehicle.RegenMath.eff(ConfigSVA.regenEfficiency);
        double mul = Math.max(0, ConfigSVA.consumption);
        if (!ConfigSVA.regen || eff <= 0 || mul <= 0 || isWrecked()) {
            regenPool = 0;
            return;
        }
        boolean plane = t.movement.flies && planeNow();
        double cruise = t.movement.flies ? FlightMath.cruiseCost(t, fill, posY, plane) * mul : VehicleMath.cost(t, t.topSpeed, fill, true, mul);
        double kTop = t.movement.flies ? 0 : com.sva.vehicle.RegenMath.kinetic(t, t.topSpeed, fill, mul);
        regenPool = com.sva.vehicle.RegenMath.bank(regenPool, spent, com.sva.vehicle.RegenMath.poolCap(cruise, kTop, t.movement.flies));
        double dy = posY - lastY, src;
        if (t.movement.flies) {
            boolean windmill = autoNow || (plane ? throttle <= com.sva.vehicle.RegenMath.IDLE_THROTTLE : (in & (IN_DOWN | IN_BOOST)) != 0);
            src = state.energy > 0 ? com.sva.vehicle.RegenMath.airSource(dy, wasAirborne, windmill, cruise) : 0;
        } else {
            boolean braking = motorOn && com.sva.vehicle.RegenMath.braking(t.movement, (in & IN_FWD) != 0, (in & IN_BACK) != 0,
                    (in & IN_BRAKE) != 0, speed);
            src = motorOn ? com.sva.vehicle.RegenMath.groundSource(t, vBefore, speedEma, braking, dy, moved, fill, mul, cruise) : 0;
        }
        double share = com.sva.vehicle.RegenMath.share(t, plane, eff);
        double r = com.sva.vehicle.RegenMath.recover(share, src, regenPool, cruise, state.maxEnergy() - state.energy);
        if (r > 0) {
            regenPool = com.sva.vehicle.RegenMath.drain(regenPool, r, share);
            state.energy += r;
            regenNow = (float) r;
        }
    }

    /**
     * Hydrogen wave, server, once a tick in place of the EU cost: the engine (warming up or running) burns H2 - cruise x
     * FuelMath.mul (the throttle share: the plane's throttle, a rotorcraft hovering at least CRUISE_SHARE, the rest its
     * speed against the top; cargo, a trailer, climbing, height, Мотор+, the tiltrotor vertical); the battery feeds the
     * board (someone aboard or the engine turning; D2: АКБ on), the hover's cushion, the starter and the D2 heat, the
     * generator charges it while the engine turns (D2: ГЕН and АКБ on). Charging wears the battery a cycle at a time, a
     * run down to empty once (BatteryMath). @return the battery's net draw (EU/t, + discharging - the HUD's cost)
     */
    private double hydrogenTick(VehicleType t, double v, double fill, double boostMb, double sysEu) {
        if (t == VehicleType.LINER) {
            genNow = 0;                                             // liner wave 1: LinerLogicSVA burnt the H2 and ran the batteries
            batNet = 0;
            return 0;
        }
        boolean d2 = SystemsMath.applies(t), plane = t.movement.flies && planeNow();
        boolean turning = SystemsMath.lifts(engine.state) && !isWrecked();
        double burnMb = 0;
        if (turning) {
            double top = Math.max(1e-3, t.topSpeed * tuneSpeed());
            double thr = plane ? throttle : t.movement.flies && wasAirborne ? Math.max(FuelMath.CRUISE_SHARE, v / top) : v / top;
            double mul = FuelMath.mul(thr, fill, state.trailer, t.movement.flies, posY - lastY, posY, ConfigSVA.tuning ? state.tuning : null,
                    t.movement == Movement.TILT && !planeNow() && wasAirborne);
            burnMb = FuelMath.burn(t, mul, ConfigSVA.consumption);
            state.drainGasFraction(FuelMath.H2, burnMb);
        }
        h2Flow = FuelMath.flowStep(h2Flow, burnMb + boostMb);
        double cap = state.maxEnergy();
        boolean master = !d2 || SystemsMath.on(state.switches, SystemsMath.SW_BAT);
        double load = sysEu;
        if (state.batIn && master && (riddenByEntity != null || debugDriver || engine.state != SystemsMath.OFF)) {
            load += FuelMath.boardLoad(cap) * Math.max(0, ConfigSVA.consumption);
        }
        if (lifting && t.movement == Movement.HOVER) {
            load += FuelMath.cushionLoad(cap, boosting) * Math.max(0, ConfigSVA.consumption);
        }
        if (engine.state == SystemsMath.CRANK) {
            load += FuelMath.starterPerTick(cap, crankTicksFor(t, false));
        }
        genNow = turning && master && (!d2 || SystemsMath.on(state.switches, SystemsMath.SW_GEN)) ? FuelMath.generator(cap, enginePowerRamp(t)) : 0;
        double before = state.energy;
        state.energy = Math.max(0, Math.min(cap, state.energy - load + genNow));
        batNet = state.energy - before;
        batteryWear(before, state.energy);
        state.battery = batteryUnits();
        return load - genNow;
    }

    /** The warm-up's ramp of the engine (0..1; the D2 aircraft's, the others at once). */
    private double enginePowerRamp(VehicleType t) {
        return SystemsMath.applies(t) ? SystemsMath.power(engine.state, engine.ticks, SystemsMath.warmTicks(t)) : SystemsMath.lifts(engine.state) ? 1 : 0;
    }

    /**
     * Hydrogen wave, server: the battery's wear from a change of its charge (before -> after, this tick - the generator,
     * a station, a jump start): every full capacity charged in is a cycle, a run down to empty a deep discharge (once,
     * until it is charged over DEEP_RESET again). Nothing with vehicles.batteryWear off.
     */
    void batteryWear(double before, double after) {
        double cap = BatteryMath.capacity(state.type);
        if (!state.batIn || cap <= 0) {
            return;
        }
        if (after > before && ConfigSVA.batteryWear) {
            state.batHealth = BatteryMath.cycleWear(state.batHealth, after - before, cap);
            state.batCycle += after - before;
        }
        if (after < 1 && before >= 1 && !state.batDeep) {
            state.batDeep = true;
            if (ConfigSVA.batteryWear) {
                state.batHealth = Math.max(0, state.batHealth - BatteryMath.DEEP_WEAR);
            }
        } else if (state.batDeep && after > cap * BatteryMath.DEEP_RESET) {
            state.batDeep = false;
        }
    }

    // ------------------------------------------------------------------ hydrogen wave: the battery in / out, the kit, the jump leads

    /** May p take the battery out / put one in now: on hydrogen, the engine off, standing still, the owner or the «Ремонт» right. */
    public boolean mayHandleBattery(EntityPlayer p) {
        if (!onHydrogen() || engineState() != SystemsMath.OFF || Math.abs(speed) > 0.05) {
            return false;
        }
        return p == null || canManage(p) || hasRight(p, AccessMath.REPAIR);
    }

    /** Why p may not (a lang key) - the engine running, moving, no right; null when he may. */
    public String batteryRefusal(EntityPlayer p) {
        if (engineState() != SystemsMath.OFF) {
            return "sva.msg.bat.engineOn";
        }
        if (Math.abs(speed) > 0.05) {
            return "sva.msg.bat.moving";
        }
        return p == null || canManage(p) || hasRight(p, AccessMath.REPAIR) ? null : "sva.msg.bat.noRight";
    }

    /** Server: the battery as an item (its charge, health...), null with none in / not on hydrogen. Nothing changes. */
    public ItemStack batteryStack() {
        if (!state.hydrogen() || !state.batIn || ModContentSVA.battery == null) {
            return null;
        }
        BatteryMath.Battery b = new BatteryMath.Battery();
        b.charge = Math.max(0, Math.min(state.energy + state.energyHeld, BatteryMath.capacity(state.type)));
        b.health = state.batHealth;
        b.limit = state.batLimit;
        b.services = state.batServices;
        b.cycle = state.batCycle;
        b.deep = state.batDeep;
        return com.sva.item.ItemBatterySVA.stackOf(ModContentSVA.battery, state.type, b);
    }

    /** Server: takes the battery out (null with none in); the board goes dark. */
    public ItemStack takeBattery() {
        ItemStack s = batteryStack();
        if (s != null) {
            state.takeBattery();
            engine.stop();
            syncWatcher();
        }
        return s;
    }

    /** Server: puts this type's battery in (false: not this type's, one is in already, not on hydrogen). */
    public boolean putBattery(ItemStack s) {
        if (!state.hydrogen() || state.batIn || !com.sva.item.ItemBatterySVA.fits(s, state.type)) {
            return false;
        }
        state.putBattery(com.sva.item.ItemBatterySVA.batteryOf(s));
        syncWatcher();
        return true;
    }

    /** The «Снять» button / action: the battery into p's inventory (or dropped at his feet when it is full). */
    private void batteryOut(EntityPlayer p) {
        if (!state.hydrogen()) {
            return;
        }
        if (!state.batIn) {
            msg(p, "sva.msg.bat.none");
            return;
        }
        String why = batteryRefusal(p);
        if (why != null) {
            msg(p, why);
            return;
        }
        ItemStack s = takeBattery();
        if (s != null && !p.inventory.addItemStackToInventory(s)) {
            p.dropPlayerItemWithRandomChoice(s, false);
        }
        oneShot("cargo", 0.4F, 1.4F);
        msg(p, "sva.msg.bat.out");
    }

    /** Liner wave 1: «Снять» АКБ 2 - into p's inventory (the same rules as АКБ 1). */
    private void battery2Out(EntityPlayer p) {
        com.sva.vehicle.LinerSystems L = state.liner();
        if (L == null) {
            return;
        }
        if (!L.bat2In) {
            msg(p, "sva.msg.bat.none");
            return;
        }
        String why = batteryRefusal(p);
        if (why != null) {
            msg(p, why);
            return;
        }
        ItemStack s = LinerLogicSVA.takeBattery2(this);
        if (s != null && !p.inventory.addItemStackToInventory(s)) {
            p.dropPlayerItemWithRandomChoice(s, false);
        }
        oneShot("cargo", 0.4F, 1.4F);
        msg(p, "sva.liner.msg.bat2.out");
    }

    /** Liner wave 1: АКБ 2 is in (clients: synced). */
    public boolean battery2Present() {
        return liner() != null && liner().bat2In;
    }

    /**
     * Server, right-click with something in hand (hydrogen): a battery of this type goes in (another type's: «не
     * подходит»), the service kit starts its 5 s, the jump leads take this vehicle as the donor or start the jump from
     * the donor they hold, a charged core battery pours its charge in. @return handled
     */
    private boolean batteryInteract(EntityPlayer p, ItemStack held) {
        if (held == null) {
            return false;
        }
        if (com.sva.item.ItemBatterySVA.isBattery(held)) {
            VehicleType bt = com.sva.item.ItemBatterySVA.typeOf(held);
            if (bt != state.type) {
                msg(p, "sva.msg.bat.wrongType", new ChatComponentTranslation("item.siliconagevehicles.vehicle." + bt.key + ".name"),
                        new ChatComponentTranslation("item.siliconagevehicles.battery." + state.type.key + ".name"));
            } else if (state.batIn && state.type == VehicleType.LINER && !state.liner().bat2In) {
                // liner wave 1: АКБ 1 is in - the second goes into АКБ 2
                if (batteryRefusal(p) != null) {
                    msg(p, batteryRefusal(p));
                } else if (LinerLogicSVA.putBattery2(this, held)) {
                    p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
                    oneShot("cargo", 0.4F, 1.2F);
                    msg(p, "sva.liner.msg.bat2.in", Integer.valueOf((int) Math.round(state.liner().bat2.health * 100)));
                }
            } else if (state.batIn) {
                msg(p, "sva.msg.bat.already");
            } else if (batteryRefusal(p) != null) {
                msg(p, batteryRefusal(p));
            } else if (putBattery(held)) {
                p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
                oneShot("cargo", 0.4F, 1.2F);
                msg(p, "sva.msg.bat.in", Integer.valueOf((int) Math.round(state.batHealth * 100)));
            }
            return true;
        }
        int k = com.sva.item.ItemBatteryToolSVA.kind(held);
        if (k == com.sva.item.ItemBatteryToolSVA.HYD_FLUID) {
            LinerLogicSVA.refill(this, p, held);                    // liner wave 2: «Гидрожидкость»
            return true;
        }
        if (k == com.sva.item.ItemBatteryToolSVA.KIT) {
            startKit(p);
            return true;
        }
        if (k == com.sva.item.ItemBatteryToolSVA.CABLES) {
            jumpCables(p, held);
            return true;
        }
        if (held.getItem() instanceof com.sc.item.ItemBatterySC && state.batIn) {
            // a charged core battery: its charge into the vehicle's (as much as fits)
            long have = com.sc.item.ItemBatterySC.chargeOf(held);
            double was = state.energy;
            double got = state.addEnergy(Math.min(have, energyRoom()));
            if (got >= 1) {
                com.sc.item.ItemBatterySC.setCharge(held, have - (long) Math.floor(got));
                batteryWear(was, state.energy);
                msg(p, "sva.msg.bat.fromCell", com.sva.FormatSVA.group((long) got), Integer.valueOf(batteryPctNow()));
                syncWatcher();
            } else {
                msg(p, have <= 0 ? "sva.msg.bat.cellEmpty" : "sva.msg.bat.full");
            }
            return true;
        }
        return false;
    }

    private int batteryPctNow() {
        return (int) Math.floor(state.energy * 100 / Math.max(1, state.maxEnergy()));
    }

    /** The service kit (server): an electrolyte from p's inventory, then KIT_TICKS standing by - +KIT_GAIN health. */
    private void startKit(EntityPlayer p) {
        if (!state.batIn) {
            msg(p, "sva.msg.bat.none");
            return;
        }
        String why = batteryRefusal(p);
        if (why != null) {
            msg(p, why);
            return;
        }
        if (kitTicks > 0) {
            msg(p, "sva.msg.bat.kitBusy");
            return;
        }
        if (!BatteryMath.serviceUseful(state.batHealth, state.batLimit)) {
            msg(p, "sva.msg.bat.atLimit", Integer.valueOf((int) Math.round(state.batLimit * 100)));
            return;
        }
        if (!com.sva.item.ItemBatteryToolSVA.takeElectrolyte(p)) {
            msg(p, "sva.msg.bat.noElectrolyte");
            return;
        }
        kitTicks = BatteryMath.KIT_TICKS;
        kitBy = p.getUniqueID();
        oneShot("cargo", 0.4F, 0.8F);
        msg(p, "sva.msg.bat.kitStart", Integer.valueOf(BatteryMath.KIT_TICKS / 20));
        syncWatcher();
    }

    /** The jump leads (server): the first vehicle clicked is the donor, the second one gets the jump start from it. */
    private void jumpCables(EntityPlayer p, ItemStack cables) {
        NBTTagCompound t = cables.getTagCompound();
        Entity d = t != null && t.hasKey(com.sva.item.ItemBatteryToolSVA.K_DONOR) ? worldObj.getEntityByID(t.getInteger(com.sva.item.ItemBatteryToolSVA.K_DONOR)) : null;
        boolean same = d instanceof EntityVehicleSVA && ((EntityVehicleSVA) d).state.vehId.equals(t.getString(com.sva.item.ItemBatteryToolSVA.K_DONOR_ID));
        if (!same || d == this) {
            // this one is the donor
            if (!state.batIn || state.energy < state.maxEnergy() * BatteryMath.DONOR_MIN) {
                msg(p, "sva.msg.jump.weakDonor", Integer.valueOf((int) Math.round(BatteryMath.DONOR_MIN * 100)));
                return;
            }
            NBTTagCompound n = t == null ? new NBTTagCompound() : t;
            n.setInteger(com.sva.item.ItemBatteryToolSVA.K_DONOR, getEntityId());
            n.setString(com.sva.item.ItemBatteryToolSVA.K_DONOR_ID, state.vehId);
            cables.setTagCompound(n);
            msg(p, "sva.msg.jump.donor");
            return;
        }
        EntityVehicleSVA donor = (EntityVehicleSVA) d;
        cables.setTagCompound(null);
        if (!state.batIn) {
            msg(p, "sva.msg.bat.none");
        } else if (getDistanceToEntity(donor) > BatteryMath.JUMP_RANGE + (width + donor.width) / 2) {
            msg(p, "sva.msg.jump.far", Integer.valueOf((int) BatteryMath.JUMP_RANGE));
        } else if (!donor.state.batIn || donor.state.energy < donor.state.maxEnergy() * BatteryMath.DONOR_MIN) {
            msg(p, "sva.msg.jump.weakDonor", Integer.valueOf((int) Math.round(BatteryMath.DONOR_MIN * 100)));
        } else {
            jumpFrom = donor;
            jumpLeft = BatteryMath.capacity(state.type) * BatteryMath.JUMP_SHARE;
            jumpTicks = BatteryMath.JUMP_TICKS;
            oneShot("cargo", 0.4F, 1.6F);
            msg(p, "sva.msg.jump.start", Integer.valueOf(BatteryMath.JUMP_TICKS / 20));
            syncWatcher();
        }
    }

    /** World test: a jump start from `donor` as the leads would begin it (no checks but the batteries). */
    public void debugJump(EntityVehicleSVA donor) {
        jumpFrom = donor;
        jumpLeft = BatteryMath.capacity(state.type) * BatteryMath.JUMP_SHARE;
        jumpTicks = BatteryMath.JUMP_TICKS;
    }

    /** World test: the field kit started by p (the electrolyte taken by the caller). */
    public void debugKit(EntityPlayer p) {
        kitTicks = BatteryMath.KIT_TICKS;
        kitBy = p == null ? null : p.getUniqueID();
    }

    /** Server, a tick: the field kit (done after KIT_TICKS; off when the engine starts or its man walks away) and a jump start. */
    private void batteryServiceTick() {
        if (kitTicks > 0) {
            EntityPlayer p = kitBy == null ? null : kitPlayer();
            if (!state.batIn || engine.state != SystemsMath.OFF || kitBy != null && (p == null || p.getDistanceSqToEntity(this) > 64)) {
                kitTicks = 0;
                if (p != null) {
                    msg(p, "sva.msg.bat.kitCancel");
                }
            } else if (--kitTicks == 0) {
                int was = (int) Math.round(state.batHealth * 100);
                double[] r = BatteryMath.kitService(state.batHealth, state.batLimit);
                state.batHealth = r[0];
                state.batLimit = r[1];
                state.batServices++;
                oneShot("ding", 0.4F, 1.4F);
                if (p != null) {
                    msg(p, "sva.msg.bat.serviced", Integer.valueOf(was), Integer.valueOf((int) Math.round(state.batHealth * 100)),
                            Integer.valueOf((int) Math.round(state.batLimit * 100)));
                }
            }
        }
        if (jumpTicks > 0) {
            EntityVehicleSVA d = jumpFrom;
            if (d == null || d.isDead || d.worldObj != worldObj || !state.batIn
                    || getDistanceToEntity(d) > BatteryMath.JUMP_RANGE + (width + d.width) / 2 + 1) {
                jumpTicks = 0;
                jumpFrom = null;
                tellRiders("sva.msg.jump.broken");
            } else {
                double m = BatteryMath.jumpStep(BatteryMath.jumpPerTick(BatteryMath.capacity(state.type)), jumpLeft, d.state.energy,
                        d.state.maxEnergy(), energyRoom());
                d.state.energy -= m;
                double was = state.energy;
                state.energy += m;
                batteryWear(was, state.energy);
                jumpLeft -= m;
                if (--jumpTicks <= 0 || jumpLeft <= 0 || m <= 0 && d.state.energy <= d.state.maxEnergy() * BatteryMath.DONOR_MIN) {
                    jumpTicks = 0;
                    jumpFrom = null;
                    oneShot("ding", 0.35F, 1.6F);
                    tellRiders("sva.msg.jumpStart.done");
                }
            }
        }
    }

    private EntityPlayer kitPlayer() {
        for (Object o : worldObj.playerEntities) {
            EntityPlayer p = (EntityPlayer) o;
            if (kitBy.equals(p.getUniqueID())) {
                return p;
            }
        }
        return null;
    }

    /**
     * The repair garage's «Обслужить АКБ» (server; the garage took the EU and the electrolyte): the health up to the
     * limit, the limit a step down. @return {health before, after} in %
     */
    public int[] garageServiceBattery() {
        int was = (int) Math.round(state.batHealth * 100);
        double[] r = BatteryMath.garageService(state.batHealth, state.batLimit);
        state.batHealth = r[0];
        state.batLimit = r[1];
        state.batServices++;
        syncWatcher();
        return new int[] {was, (int) Math.round(state.batHealth * 100)};
    }

    /** World test: the battery's health (0..1) set directly. */
    public void debugBatteryHealth(double h) {
        state.batHealth = Math.max(0, Math.min(1, h));
        syncWatcher();
    }

    /** World test: run on hydrogen (1) / EU (0) whatever the config (-1: the config). */
    public void debugFuel(int force) {
        state.fuelForce = force;
        syncWatcher();
    }

    /** World test: the recovery pool (EU spent the vehicle may still get a share of back). */
    public double regenPool() {
        return regenPool;
    }

    /**
     * HeliMath.landingDamage with the hull's real max HP («Лёгкий корпус»): the share-of-hull parts (autorotation,
     * its cap) from state.maxHp(); a type that comes down faster than SAFE_LANDING keeps its own flat rule.
     */
    private double heliLandingDamage(VehicleType t, double impact, boolean autorotation) {
        if (!autorotation && t.descend + 0.05 > HeliMath.SAFE_LANDING) {
            return HeliMath.landingDamage(t, impact, false);
        }
        return HeliMath.landingDamage(state.maxHp(), impact, autorotation);
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

    /** ★11: is the wall right ahead in the direction (dx, dz) a guard rail? */
    private boolean railToward(double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6 || ModContentSVA.guardRail == null) {
            return false;
        }
        AxisAlignedBB box = boundingBox.getOffsetBoundingBox(dx / len * 0.4, 0, dz / len * 0.4).contract(0.05, 0.05, 0.05);
        for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX); x++) {
            for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ); z++) {
                for (int y = MathHelper.floor_double(box.minY) - 1; y <= MathHelper.floor_double(box.maxY); y++) {
                    if (worldObj.getBlock(x, y, z) == ModContentSVA.guardRail) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        boolean thrust = canThrust() && (!onHydrogen() || motorDrives());     // hydrogen: no engine - it rolls to a stop
        double top = VehicleMath.maxSpeed(t, driveCharge()) * roadSpeedMul(t) * tuneSpeed()      // ★11: asphalt / concrete; ★8 tuning
                * trailerSpeedMul();                                                       // ★15: a full tank / a loaded platform
        boolean water = isInWater();
        boolean crawler = t.movement == Movement.CRAWLER;
        if (water) {
            top *= crawler ? CrawlerMath.WATER_SPEED : WATER_SPEED;
        }
        if (!fwd && cruiseActive(in)) {
            speed = CruiseMath.groundStep(speed, Math.min(cruiseSpeed, top), t.accel, ROLL, thrust);   // ★4: cruise
        } else if (thrust && fwd && !back) {
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
            rotationYaw += CrawlerMath.turn(t.turnRate, steer, speed, thrust, driveCharge() > 0);   // tracks: on the spot too
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
            damageHull((float) ((distance - 4) * 5 * RoadMath.wearMul(roadSurface(), state.type.movement)));
        }
    }

    // ------------------------------------------------------------------ server: network input

    /** Input packet (server thread, sender verified as the driver) - and the world test's path. */
    public void serverInput(int flags) {
        input = flags & IN_MASK;
        lastInputTick = worldObj.getTotalWorldTime();
    }

    /** The Input packet with the ★4 targets (sanitised: finite, speed within reach, height within the world). */
    public void serverInput(int flags, float cruise, float yaw, float alt) {
        serverInput(flags, cruise, yaw, alt, AutopilotMath.DEFAULT, 0F);
    }

    /** Stage C: the Input packet with the autopilot's modes and set vertical speed (b/t) too. */
    public void serverInput(int flags, float cruise, float yaw, float alt, int modes, float vs) {
        serverInput(flags);
        if ((flags & IN_CRUISE) != 0) {
            setCruiseTargets(cruise, yaw, alt);
            setApModes(modes, vs);
        }
    }

    /** Stage C: the autopilot's modes (AutopilotMath.M_*) and set vertical speed (b/t, within +-VS_MAX blocks/s), sanitised. */
    public void setApModes(int modes, double vs) {
        apModes = modes & AutopilotMath.M_ALL;
        apVs = finite(vs) ? VehicleMath.clamp(vs, -AutopilotMath.VS_MAX / 20, AutopilotMath.VS_MAX / 20) : 0;
    }

    /** ★4 targets, sanitised (the server's packet path and the world test). */
    public void setCruiseTargets(double cruise, double yaw, double alt) {
        VehicleType t = state.type;
        cruiseSpeed = finite(cruise) ? VehicleMath.clamp(cruise, 0, t.topSpeed * RoadMath.ASPHALT_SPEED * tuneSpeed()) : 0;
        apYaw = finite(yaw) ? CruiseMath.wrap(yaw) : rotationYaw;
        apAlt = finite(alt) ? VehicleMath.clamp(alt, 1, FlightMath.CEILING) : posY;
        if (t.movement.flies && t.plane != null) {
            // stage C: СКОР's set speed (the same field) up to the aircraft's own top speed
            cruiseSpeed = finite(cruise) ? VehicleMath.clamp(cruise, 0, PlaneMath.maxSpeed(t, 1)) : 0;
        }
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
        long ms = System.nanoTime() / 1000000L;
        if (now != lastMoveTick) {
            // the tick's first Move: the ticks since the last one (after a silence the server drove - one step), from here;
            // a lagging server (TPS < 20) drains more than one client step a tick - the real time counts then
            long ticks = now - lastMoveTick, real = (ms - moveBaseMs + 25) / 50;
            moveBaseDt = ticks > MOVE_TIMEOUT ? 1 : Math.max(1, Math.min(MOVE_TIMEOUT, Math.max(ticks, real)));
            moveCreditAtTick = ticks > MOVE_TIMEOUT ? 0 : moveCredit;
            moveBaseMs = ms;
            moveBaseX = posX;
            moveBaseY = posY;
            moveBaseZ = posZ;
            lastMoveTick = now;
        }
        long dt = moveBaseDt;
        VehicleType t = state.type;
        double dx = x - posX, dy = y - posY, dz = z - posZ;
        double h = Math.sqrt(dx * dx + dz * dz);
        // the reach / climb rules judge the whole tick's displacement: more Moves in one tick are no more speed
        double bx = x - moveBaseX, by = y - moveBaseY, bz = z - moveBaseZ;
        double bh = Math.sqrt(bx * bx + bz * bz);
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
        double limit = hover ? (lift && motorDrives() ? HoverMath.maxSpeed(t, true, (input & (IN_BOOST | IN_AFTER)) != 0) * tuneSpeed() : coast)
                : canThrust() && (!onHydrogen() || motorDrives()) ? VehicleMath.maxSpeed(t, driveCharge()) * tuneSpeed() * Math.max(roadSpeedMul(t),
                RoadMath.speedMul(com.sva.block.BlockRoadSVA.surfaceAt(worldObj, x, y, z), t.movement)) : coast;   // coasting only; ★11 roads
        // aircraft: reach, climbing only with power / airspeed, the ceiling, no hanging in the air unpowered (FlightMath)
        // The client and server ticks run unsynchronised and only the newest Move of a tick is kept (ServerQueueSVA): a
        // tick may get two client steps and the next none. One step may be borrowed (moveCredit -1..1, repaid by the
        // empty tick), so that jitter is no snap-back, while a steady 2x pace is still refused after one tick.
        long dtMax = Math.max(1, Math.min(MOVE_TIMEOUT, dt + moveCreditAtTick + 1));
        boolean ok = false;
        long used = dt;
        boolean wasAir = flies && airborneAt(posX, posY, posZ), nowAir = flies && airborneAt(x, y, z);
        for (long d = 1; d <= dtMax && !ok; d++) {
            ok = flies ? FlightMath.moveAllowed(t, moveRules(t, now), flightParams(t), bh, by, d, y, lift, wasAir, nowAir,
                    clientAirspeed(clientSpeed, bh / d))        // not the bare EMA: it halved on a tick without a Move (and
                                                                // a Move after a refused one must count what it covers)
                    : moveAllowed(bh, by, d, limit, CrawlerMath.climbLimit(stepHeight));          // ★8: big wheels step 1.5
            used = d;
        }
        if (ok) {
            moveCredit = Math.max(-1, Math.min(1, moveCreditAtTick + dt - used));
        }
        refuseWhy = ok ? 0 : 1;
        if (ok && (Math.abs(dx) > 1e-4 || Math.abs(dy) > 1e-4 || Math.abs(dz) > 1e-4)) {
            AxisAlignedBB box = boundingBox.getOffsetBoundingBox(dx, dy, dz).contract(0.05, 0.05, 0.05);
            if (!worldObj.getCollidingBoundingBoxes(this, box).isEmpty() || !pathClear(dx, dy, dz)) {
                ok = false;                                         // into blocks / through a wall
                refuseWhy = worldObj.getCollidingBoundingBoxes(this, box).isEmpty() ? 3 : 2;
            } else if (flies) {
                ok = true;                                          // FlightMath.moveAllowed checked it
            } else if (hover) {
                // no free flight: climbing only with lift and under the cap; in the air without lift (or far over
                // the cap) it must be coming down
                double hNew = HoverPhysicsSVA.heightAt(worldObj, x, y, z);
                if (by > 0.05 && !HoverMath.climbAllowed(lift, hNew)) {
                    ok = false;
                    refuseWhy = 4;
                } else if (HoverMath.mustDescend(lift, hNew) && by > -0.03 * dt) {
                    ok = false;
                    refuseWhy = 5;
                }
            } else if (by > 0.05 && worldObj.getCollidingBoundingBoxes(this, supportBox(dx, dy, dz)).isEmpty()
                    && !(t.movement == Movement.CRAWLER && by <= CrawlerMath.MAX_RISE * dt + 0.05
                    && worldObj.isMaterialInBB(box.expand(0, 0.6, 0), net.minecraft.block.material.Material.water))) {
                ok = false;                                         // climbing with nothing under it (a crawler may float up)
                refuseWhy = 6;
            }
        }
        if (ok) {
            setPosition(x, y, z);
            rotationYaw = yaw;
            moveSpeed = clientSpeed;                                // aircraft: the client's airspeed (clientAirspeed caps it)
            moveDyMin = movesThisTick == 0 ? dy : Math.min(moveDyMin, dy);     // aircraft: one client step's sink (flightTick)
            movesThisTick++;
            clientFall(dy, flies || hover);
        } else {
            movesRefused++;
            if (now - lastCorrectTick >= 5 && p.playerNetServerHandler != null) {
                lastCorrectTick = now;
                // DIAG (liner snap-back, temporary): why the server refused the driver's move
                System.out.println(String.format(java.util.Locale.ROOT,
                        "[SVA-MOVE] refuse type=%s why=%d bh=%.3f by=%.3f dt=%d y=%.1f lift=%b thrust=%b motor=%b eng=%d spd=%.3f ema=%.3f air=%b/%b",
                        t.name(), refuseWhy, bh, by, dt, y, lift, canThrust(), motorDrives(), engineState(), clientSpeed, speedEma,
                        airborneAt(posX, posY, posZ), airborneAt(x, y, z)));
                NetSVA.CHANNEL.sendTo(new NetSVA.Correct(getEntityId(), posX, posY, posZ, rotationYaw), p);
            }
        }
    }

    /**
     * A Move longer than the hull (a fast aircraft, a lag burst, a forged packet) can't pass a wall between its two
     * points: checked the way the client's moveEntity goes - up / down over the start, then across at the new height,
     * in half-block steps. Shorter Moves: the start and end boxes overlap, the end check is enough.
     */
    private boolean pathClear(double dx, double dy, double dz) {
        if (Math.abs(dx) < width * 0.9 && Math.abs(dz) < width * 0.9 && Math.abs(dy) < height * 0.9) {
            return true;
        }
        int vs = (int) Math.ceil(Math.abs(dy) / 0.5), hs = (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / 0.5);
        for (int i = 1; i <= vs; i++) {
            if (blockedAt(0, dy * i / vs, 0)) {
                return false;
            }
        }
        for (int i = 1; i < hs; i++) {
            if (blockedAt(dx * i / hs, dy, dz * i / hs)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The box under a ground vehicle's new position that must hold a block when a Move rises (no climbing on air): 0.6
     * deep, the hull's FULL width (+0.02) - not the 0.05-contracted collision probe. A step-up lands the box overlapping
     * the step's top by only what was left of that tick's move (0 .. the speed); with the probe narrowed by 0.05 a
     * slow step (reversing: 0.175 b/t for the buggy - every third step-up) found no support, the Move was refused and
     * the Correct threw the vehicle back down off the step (user report «при езде назад откидывает»).
     */
    private AxisAlignedBB supportBox(double dx, double dy, double dz) {
        AxisAlignedBB b = boundingBox.getOffsetBoundingBox(dx, dy - 0.6, dz);
        return AxisAlignedBB.getBoundingBox(b.minX - 0.02, b.minY + 0.05, b.minZ - 0.02, b.maxX + 0.02, b.maxY - 0.05, b.maxZ + 0.02);
    }

    private boolean blockedAt(double ox, double oy, double oz) {
        return !worldObj.getCollidingBoundingBoxes(this, boundingBox.getOffsetBoundingBox(ox, oy, oz).contract(0.05, 0.05, 0.05)).isEmpty();
    }

    /**
     * Server, the driver's client moving it: the fall is summed from the accepted Moves and the hull takes the landing
     * (fall), as the server's own physics does through moveEntity. Aircraft (flightTick), the hoverbike, water: none.
     */
    private void clientFall(double dy, boolean none) {
        if (none || worldObj.isMaterialInBB(boundingBox, net.minecraft.block.material.Material.water)) {
            fallDistance = 0;
            return;
        }
        if (dy < 0) {
            fallDistance -= (float) dy;
        }
        if (!airborneAt(posX, posY, posZ)) {
            if (fallDistance > 0) {
                fall(fallDistance);
            }
            fallDistance = 0;
        } else if (dy > 0) {
            fallDistance = 0;
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
            animIn = in;
            physicsStep(t, in);
            lerpSteps = 0;
            // the pilot's stall warnings follow the own physics (the speed PlanePhysicsSVA drops the nose by), not the
            // synced flag a tick or two late
            localStall = planeNow() && t.plane != null && airborneAt(posX, posY, posZ) && PlaneMath.stalled(flightParams(t), Math.max(0, speed));
            boolean targets = (in & IN_CRUISE) != 0
                    && ((float) cruiseSpeed != sentCruise[0] || (float) apYaw != sentCruise[1] || (float) apAlt != sentCruise[2]
                    || (float) apVs != sentCruise[3] || apModes != sentModes);
            if (in != lastSentInput || targets || ++inputTimer >= KEEPALIVE) {
                NetSVA.CHANNEL.sendToServer(new NetSVA.Input(getEntityId(), in, (float) cruiseSpeed, (float) apYaw, (float) apAlt, apModes, (float) apVs));
                lastSentInput = in;
                inputTimer = 0;
                sentCruise[0] = (float) cruiseSpeed;
                sentCruise[1] = (float) apYaw;
                sentCruise[2] = (float) apAlt;
                sentCruise[3] = (float) apVs;
                sentModes = apModes;
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
            animIn = -1;
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
            if (ConfigSVA.animations && drillRate > 20F && isDrillOn()) {
                drillSparks(t);
            }
        }
        animTick(t);
        if (t == VehicleType.LINER && liner() != null) {
            // liner wave 1: its gear sequence (the lever), the L1 door and stairs, the fans by N1, the oleos (LinerAnim)
            com.sva.vehicle.LinerSystems L = liner();
            linerAnim.tick((float) L.gear.pos, L.gear.gravity, anim.height < 0.6, L.door, L.eng[0].n1, L.eng[1].n1, anim.height);
            // liner wave 2: the surfaces move on every client from the stick it sees and the synced pressures; the high lift
            // and the wheels' temperatures follow the synced values
            double mx = posX - prevPosX, mz = posZ - prevPosZ;
            L.clientTick(anim.ctlRoll, anim.ctlPitch, anim.ctlYaw, Math.sqrt(mx * mx + mz * mz), anim.height < 0.6, anim.trim * 0.6F);
            linerAnim.tickHighLift((float) L.fctl.flapPos, (float) L.fctl.slatPos);
            linerAnim.tickReversers((float) L.afs.revPos[0], (float) L.afs.revPos[1]);   // liner wave 4
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
        if (SystemsMath.applies(t) && engineState() == SystemsMath.CRANK) {
            target = Math.max(target, SystemsMath.CRANK_SPIN);      // D2: the starter turns it slowly
        }
        engineEffects(t);
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
            if (t == VehicleType.LINER) {
                // liner wave 4: the bank of a coordinated turn at this yaw rate (LinerGuide's model), up to the 67 deg limit
                double sp = Math.sqrt(dx * dx + dz * dz);
                tr = air ? (float) MathHelper.clamp_double(com.sva.vehicle.LinerGuide.bankOf(yawRate, sp, flightParams(t).stall, t.turnRate), -67, 67) : 0F;
            }
        }
        renderPitch += (tp - renderPitch) * (t == VehicleType.LINER ? 0.1F : 0.2F);
        renderRoll += (tr - renderRoll) * (t == VehicleType.LINER ? 0.06F : 0.2F);  // liner wave 1: a heavy airliner rolls slowly
    }

    /**
     * Client (★10): one tick of the part animations (AnimMath.tick) from this tick's motion - the speed along the facing
     * and the yaw rate (interpolated positions for other players' vehicles, the own physics for the driver), the driver's
     * own A/D and brake keys when it is the local player's, the height over the ground (aircraft, every 4 ticks).
     */
    private void animTick(VehicleType t) {
        double dx = posX - prevPosX, dz = posZ - prevPosZ, r = Math.toRadians(rotationYaw);
        float fwd = (float) (dx * -Math.sin(r) + dz * Math.cos(r));
        float dy = (float) (posY - prevPosY);
        float yawRate = MathHelper.wrapAngleTo180_float(rotationYaw - prevRotationYaw);
        boolean flies = t.movement.flies;
        // stage D3: every tick near the ground (the struts feel the touchdown), every 4 ticks higher up
        if (flies && ((ticksExisted & 3) == 0 || anim.height < 3)) {
            anim.height = heightAboveGround(10);
        } else if (!flies) {
            anim.height = 0;
        }
        float steerIn = Float.NaN;
        boolean brakeIn = false;
        if (animIn >= 0) {
            steerIn = ((animIn & IN_RIGHT) != 0 ? 1F : 0F) - ((animIn & IN_LEFT) != 0 ? 1F : 0F);
            brakeIn = !flies && ((animIn & IN_BRAKE) != 0 || (animIn & IN_BACK) != 0 && fwd > 0.02F);
        }
        // stage D3 (★12): the jet's and the tiltrotor's legs retract behind doors, the shuttle's draw up; the light plane's
        // gear is fixed now (spring legs)
        boolean gearType = com.sva.vehicle.GearMath.retracts(t);
        anim.doorSeq = ConfigSVA.gearDoors && t != VehicleType.SHUTTLE;
        boolean ground = !flies || anim.height < 0.6;
        boolean bounce = t.movement == Movement.GROUND || t.movement == Movement.CRAWLER;
        double tilt = t.movement == Movement.TILT ? getTiltPos() : Double.NaN;
        com.sva.vehicle.AnimMath.tick(anim, fwd, dy, yawRate, (float) t.turnRate, t.modelScale, ground, steerIn, brakeIn, gearType,
                planeNow() && flies, t.movement == Movement.HOVER, bounce, tilt, ConfigSVA.animations);
        // cockpit stage 1: the cabin controls - the driver's own keys (W/S cyclic or nothing, Space / «Снижение» the
        // collective or the yoke, A/D roll and pedals), else guessed from the motion (cyclic forward with speed, the
        // surfaces' deflection for the yoke, the climb for the collective); the throttle lever from the synced throttle
        float cp, cr, cyw, cl;
        boolean heliNow = t.movement == Movement.HELI || t.movement == Movement.TILT && !planeNow();
        if (animIn >= 0) {
            boolean up = (animIn & IN_BRAKE) != 0, down = (animIn & (IN_DOWN | IN_BOOST)) != 0;
            float side = ((animIn & IN_RIGHT) != 0 ? 1F : 0F) - ((animIn & IN_LEFT) != 0 ? 1F : 0F);
            float fb = ((animIn & IN_BACK) != 0 ? 1F : 0F) - ((animIn & IN_FWD) != 0 ? 1F : 0F);
            cp = heliNow ? fb : (up ? 1F : 0F) - (down ? 1F : 0F);
            cr = side;
            cyw = side;
            cl = (up ? 1F : 0F) - (down ? 1F : 0F);
        } else {
            float m = com.sva.vehicle.AnimMath.MAX_SURFACE, st = anim.steer / com.sva.vehicle.AnimMath.MAX_STEER;
            cp = heliNow ? -fwd / (float) Math.max(0.05, t.topSpeed) : anim.elevator / m;
            cr = heliNow ? st : anim.aileron / m;
            cyw = heliNow ? st : anim.rudder / m;
            cl = dy / 0.15F;
        }
        com.sva.vehicle.AnimMath.controls(anim, cp, cr, cyw, cl, (float) getThrottle(), ConfigSVA.animations);
        if (com.sva.vehicle.GearMath.struts(t)) {
            // stage D3 (★12): the oleo struts / spring legs - the touchdown's sink, the bumps while rolling
            com.sva.vehicle.AnimMath.struts(anim, !flies || anim.height < com.sva.vehicle.GearMath.CONTACT, dy,
                    ConfigSVA.animations && ConfigSVA.gearStruts);
        }
        if (t == VehicleType.JET) {
            // stage D3 (★13): the nozzle's petals follow the throttle (idle open, full dry narrowest, afterburner wide)
            com.sva.vehicle.AnimMath.nozzle(anim, getThrottle(), isBoosting() && t.gases.boostGas != null, isLifting(),
                    ConfigSVA.animations && ConfigSVA.jetNozzle);
        }
        if (HighLiftMath.spec(t) != null) {
            // aircraft stage A: the flaps / slats / spoilers follow the synced state (RenderVehicleSVA's DEVICE parts)
            com.sva.vehicle.AnimMath.highLift(anim, getFlaps(), slatsOut(), spoilersUp(), ConfigSVA.animations);
        }
        if (com.sva.vehicle.VehicleLights.hasLamp(t.model)) {
            // stage D1, ★14: the retractable landing lights - out with the flaps (>= 10 deg), low on the approach or by the
            // switch (stage D2's overhead panel: landingLampSwitch())
            if ((ticksExisted & 7) == 0) {
                anim.agl = posY - worldObj.getHeightValue(MathHelper.floor_double(posX), MathHelper.floor_double(posZ));
            }
            HighLiftMath.Spec hs = HighLiftMath.spec(t);
            float flapDeg = hs == null ? 0F : hs.angleAt(anim.flapPos);
            boolean on = ConfigSVA.landingLamps && landingLampSwitch();         // D2: «ПОС. ФАРЫ» off - in
            com.sva.vehicle.AnimMath.landingLamp(anim, false, on ? flapDeg : 0F, on && anim.height >= 0.6, on ? dy : 1F,
                    ConfigSVA.animations);
        }
    }

    /** World test (stage D3): one tick of the part animations as the client runs it (AnimMath state, gear, struts). */
    public void debugAnimTick() {
        animTick(getVehicleType());
    }

    /**
     * Stage D3 (★18): the flight-plan waypoint the jet's HUD shows the distance to - {x, z, number}; null = none (the
     * runway ЗАХВ flies to is shown instead). The hook for stage D4's flight plan (МАРШ).
     */
    public double[] hudWaypoint() {
        int[] r = routeTarget();
        if (r == null || r[0] >= r[1] || !apPanel().mode(AutopilotMath.M_ROUTE)) {
            return null;                                    // D4: МАРШ not set / flown through - the runway, if any
        }
        return new double[] {r[2] + 0.5, r[3] + 0.5, r[0] + 1};
    }

    /**
     * Stage D1, ★14 / D2 ★17: the overhead «ПОС. ФАРЫ» switch (synced). ON (the default) = the landing lights work as in
     * D1 - out with the flaps / low on the approach, beams at night; OFF = they stay in and dark.
     */
    public boolean landingLampSwitch() {
        return lightOn(com.sva.vehicle.VehicleLights.HEAD);
    }

    /**
     * Client (★10): sparks and rock chips off the bore's tip while it drills (drill.obj's tip x 3.0, y 1.031 x the
     * model scale, the model drawn moved back by modelOffsetX).
     */
    private void drillSparks(VehicleType t) {
        double[] w = toWorld((3.0 - t.modelOffsetX) * t.modelScale + 0.1, 0);
        double x = posX + w[0], y = posY + 1.031 * t.modelScale, z = posZ + w[1];
        for (int i = 0; i < 2; i++) {
            worldObj.spawnParticle("crit", x + (rand.nextDouble() - 0.5) * 0.4, y + (rand.nextDouble() - 0.5) * 0.4,
                    z + (rand.nextDouble() - 0.5) * 0.4, (rand.nextDouble() - 0.5) * 0.6, rand.nextDouble() * 0.3, (rand.nextDouble() - 0.5) * 0.6);
        }
        if (ticksExisted % 3 == 0) {
            double[] f = toWorld((3.0 - t.modelOffsetX) * t.modelScale + 0.6, 0);
            int bx = MathHelper.floor_double(posX + f[0]), by = MathHelper.floor_double(y), bz = MathHelper.floor_double(posZ + f[1]);
            net.minecraft.block.Block b = worldObj.getBlock(bx, by, bz);
            if (b.getMaterial() != net.minecraft.block.material.Material.air) {
                worldObj.spawnParticle("blockcrack_" + net.minecraft.block.Block.getIdFromBlock(b) + "_" + worldObj.getBlockMetadata(bx, by, bz),
                        x, y, z, (rand.nextDouble() - 0.5) * 0.3, 0.15, (rand.nextDouble() - 0.5) * 0.3);
            }
        }
    }

    /**
     * Client, D2: the engine's state seen (ticks counted for the warm-up ramp / the HUD), a puff of smoke from the
     * exhaust when it catches, a smoke trail from the cowling (the jet: the nozzle) while it has failed and turns.
     */
    private void engineEffects(VehicleType t) {
        if (!engineApplies(t)) {
            return;
        }
        int st = engineState();
        if (st != engineSeen) {
            if (engineSeen == SystemsMath.CRANK && st == SystemsMath.WARM && ConfigSVA.animations) {
                double[] p = exhaust(t);
                for (int i = 0; i < 12; i++) {
                    worldObj.spawnParticle(SystemsMath.jet(t) ? "flame" : "largesmoke", p[0] + (rand.nextDouble() - 0.5) * 0.5, p[1] + rand.nextDouble() * 0.3,
                            p[2] + (rand.nextDouble() - 0.5) * 0.5, (rand.nextDouble() - 0.5) * 0.05, 0.04, (rand.nextDouble() - 0.5) * 0.05);
                }
            }
            engineSeen = st;
            engineSeenTicks = 0;
        } else {
            engineSeenTicks++;
        }
        if ((failures() & SystemsMath.F_ENGINE) != 0 && SystemsMath.lifts(st) && ConfigSVA.animations && ticksExisted % 2 == 0) {
            double[] p = exhaust(t);
            worldObj.spawnParticle(ticksExisted % 6 == 0 ? "largesmoke" : "smoke", p[0], p[1], p[2], 0, 0.03, 0);
        }
    }

    /** World position of the exhaust / cowling (model units: the plane's cowling x 1.85, y 0.72; the jet's nozzle; the tiltrotor's left nacelle). */
    private double[] exhaust(VehicleType t) {
        double mx = t == VehicleType.JET ? NOZZLE_X : t == VehicleType.TILT ? 0.6 : 1.85;
        double my = t == VehicleType.JET ? NOZZLE_Y : t == VehicleType.TILT ? 1.35 : 0.72;
        double mz = t == VehicleType.TILT ? -2.2 : 0;
        double[] w = toWorld((mx - t.modelOffsetX) * t.modelScale, mz * t.modelScale);
        return new double[] {posX + w[0], posY + my * t.modelScale, posZ + w[1]};
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
            EntitySeatSVA old = seats[i];
            if (!inWorld(old)) {
                if (old != null && !old.isDead) {
                    // its chunk unloaded it (not dead, but out of the world): a rider of the ghost gets off, a new seat
                    if (old.riddenByEntity != null) {
                        old.riddenByEntity.mountEntity(null);
                    }
                    old.setDead();
                }
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
            if (worldObj.isRemote) {
                s.followBody();                     // a passenger's view stays smooth (EntitySeatSVA.followBody)
            } else if (s.riddenByEntity != null) {
                s.updateRiderPosition();
            }
        }
    }

    public EntitySeatSVA freeSeat() {
        if (seats != null) {
            for (EntitySeatSVA s : seats) {
                if (inWorld(s) && s.riddenByEntity == null) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Alive and still in the world (a chunk unload removes an entity without killing it). */
    private boolean inWorld(Entity e) {
        return e != null && !e.isDead && worldObj.getEntityByID(e.getEntityId()) == e;
    }

    /** Is `e` in this vehicle (driver or a seat)? */
    public boolean carries(Entity e) {
        return e != null && (e.ridingEntity == this || e.ridingEntity instanceof EntitySeatSVA && ((EntitySeatSVA) e.ridingEntity).getParent() == this);
    }

    /** Moves an entity that just got off to a free spot beside the vehicle (side -1 left, +1 right), from the driver's seat. */
    void placeBeside(Entity e, int side) {
        placeBeside(e, side, 0);
    }

    /**
     * Moves an entity that just got off seat `seat` to a free spot: beside that seat (its side; heights -1..+2), behind /
     * in front of the hull; then rings round the vehicle a block apart; nothing free - on the roof (not inside the
     * model). Water counts as free when the vehicle itself is in water (a sealed shuttle, the ship); lava never.
     */
    void placeBeside(Entity e, int side, int seat) {
        VehicleType t = getVehicleType();
        if (t == VehicleType.LINER && !worldObj.isRemote) {
            // liner wave 1: out through the L1 door - at the foot of the airstairs (the door opens for it on the ground)
            com.sva.vehicle.LinerSystems L = state.liner();
            if (L != null && !wasAirborne && Math.abs(speed) < 0.3) {
                if (!L.door) {
                    L.door = true;
                    oneShot("door", 0.6F, 1.0F);
                }
                double[] f = LinerLogicSVA.doorSpot(this);
                for (int up = 0; up <= 3; up++) {
                    int dy = up == 3 ? -1 : up;
                    if (tryPlace(e, f[0], Math.floor(posY) + dy + 0.01, f[2], false)) {
                        return;
                    }
                }
            }
        }
        double[] o = t.seatOffsets[Math.max(0, Math.min(seat, t.seatOffsets.length - 1))];
        if (o.length > 1 && Math.abs(o[1]) > 0.05) {
            side = o[1] > 0 ? 1 : -1;                               // the seat's own side of the hull
        }
        boolean wet = worldObj.isMaterialInBB(boundingBox, net.minecraft.block.material.Material.water);
        double sx = o[0], baseY = Math.floor(posY);
        double[][] spots = {{sx, side * (t.width * 0.5 + 0.6)}, {sx, -side * (t.width * 0.5 + 0.6)}, {-t.halfLength - 0.7, 0}, {t.halfLength + 0.7, 0}};
        for (double[] sp : spots) {
            double[] w = toWorld(sp[0], sp[1]);
            for (int up = 0; up <= 3; up++) {
                int dy = up == 3 ? -1 : up;                         // 0, +1, +2, then -1
                if (tryPlace(e, posX + w[0], baseY + dy + 0.01, posZ + w[1], wet)) {
                    return;
                }
            }
        }
        // fallback: square rings round the hull, a block apart
        int r0 = (int) Math.ceil(Math.max(t.halfLength, t.width * 0.5)) + 1;
        for (int r = r0; r <= r0 + 3; r++) {
            for (int i = -r; i <= r; i++) {
                for (int k = 0; k < 4; k++) {
                    double fx = k == 0 ? i : k == 1 ? i : k == 2 ? -r : r;
                    double fz = k == 0 ? -r : k == 1 ? r : i;
                    if (k >= 2 && (i == -r || i == r)) {
                        continue;                                   // the corners came with k 0 / 1
                    }
                    for (int up = 0; up <= 3; up++) {
                        int dy = up == 3 ? -1 : up;
                        if (tryPlace(e, Math.floor(posX + fx) + 0.5, baseY + dy + 0.01, Math.floor(posZ + fz) + 0.5, wet)) {
                            return;
                        }
                    }
                }
            }
        }
        putAt(e, posX, boundingBox.maxY + 0.1, posZ);              // boxed in: on the roof, not inside the model
    }

    /** Puts e at (x, y, z) if its box is free there (no blocks; liquid only water and only when `wet`). */
    private boolean tryPlace(Entity e, double x, double y, double z, boolean wet) {
        double hw = e.width / 2;
        AxisAlignedBB bb = AxisAlignedBB.getBoundingBox(x - hw, y, z - hw, x + hw, y + e.height, z + hw);
        if (!worldObj.getCollidingBoundingBoxes(e, bb).isEmpty() || worldObj.isMaterialInBB(bb, net.minecraft.block.material.Material.lava)
                || !wet && worldObj.isAnyLiquid(bb)) {
            return false;
        }
        putAt(e, x, y, z);
        return true;
    }

    private static void putAt(Entity e, double x, double y, double z) {
        if (e instanceof EntityPlayerMP && ((EntityPlayerMP) e).playerNetServerHandler != null) {
            ((EntityPlayerMP) e).playerNetServerHandler.setPlayerLocation(x, y, z, e.rotationYaw, e.rotationPitch);
        } else {
            e.setPosition(x, y + e.yOffset, z);
        }
    }

    /**
     * Server, FML PlayerLoggedOutEvent (before the player is saved): a rider of a vehicle or a seat gets off beside it,
     * so vanilla neither writes the vehicle into his playerdata («Riding») nor removes it from the world.
     */
    public static void riderLoggedOut(EntityPlayer p) {
        if (p == null || p.worldObj == null || p.worldObj.isRemote) {
            return;
        }
        Entity m = p.ridingEntity;
        if (!(m instanceof EntityVehicleSVA || m instanceof EntitySeatSVA)) {
            return;
        }
        EntityVehicleSVA v = m instanceof EntityVehicleSVA ? (EntityVehicleSVA) m : ((EntitySeatSVA) m).getParent();
        p.mountEntity(null);
        if (v != null && !v.isDead) {
            v.placeBeside(p, -1);
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
        if (!state.tanks().isEmpty() && isGasContainer(held)) {
            // anyone may refuel (harmless): a full cell / bucket / tank of a gas pours into its tank
            com.sc.util.FluidHandSC.use(worldObj, MathHelper.floor_double(posX), MathHelper.floor_double(posY),
                    MathHelper.floor_double(posZ), p, tanks(), held);
            syncWatcher();
            return true;
        }
        if (state.hydrogen() && batteryInteract(p, held)) {
            return true;                                            // hydrogen wave: a battery, the kit, the jump leads, a core battery
        }
        if (held != null && held.getItem() instanceof com.sc.item.ItemWrenchSC && p.isSneaking()) {
            if (!canManage(p)) {
                msg(p, "sva.msg.notOwner");
            } else if (occupied()) {
                msg(p, "sva.msg.occupied");                          // as the cargo screen's button: nobody is dropped mid-air
            } else {
                pickUp(p);
            }
            return true;
        }
        if (held != null && ModContentSVA.isSteelIngot(held) && ConfigSVA.wear && state.hp < state.maxHp()) {
            // field repair: a steel ingot mends 10 % - anyone may (it only helps the owner; the howto says so); the
            // repair garage does the whole job and asks for the «Ремонт» right (wave 2, ★7)
            state.hp = VehicleMath.repair(state.hp, state.maxHp());
            if (!p.capabilities.isCreativeMode) {
                held.stackSize--;
                if (held.stackSize <= 0) {
                    p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
                }
            }
            worldObj.playSoundAtEntity(this, "random.anvil_use", 0.6F, 1.2F);
            msg(p, "sva.msg.repaired", Integer.valueOf(Math.round(state.hp * 100 / state.maxHp())));
            syncWatcher();
            return true;
        }
        if (com.sva.item.ItemKeyCardSVA.isCard(held) && keyCard(p, held)) {
            return true;                                            // ★10: bind / the access screen
        }
        if (held != null && held.getItem() instanceof com.sva.item.ItemVehicleSVA && state.hasTrailer(TrailerMath.PLATFORM)) {
            loadItem(p, held);                                      // ★15: the platform takes a small vehicle item
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
            if (canOpenCargo(p)) {
                p.openGui(SVAMod.instance, GuiHandlerSVA.CARGO, worldObj, getEntityId(), 0, 0);
                oneShot("cargo", 0.5F, 1F);
            } else {
                lockedMsg(p);
            }
            return true;
        }
        int rights = rightsOf(p);
        boolean driver = (rights & AccessMath.DRIVE) != 0;
        if (state.type == VehicleType.LINER && state.liner() != null) {
            // liner wave 1: in and out through the L1 door (left, forward): by it - the first click opens it, then you board
            if (!LinerLogicSVA.byDoor(this, p)) {
                msg(p, "sva.liner.msg.useDoor");
                return true;
            }
            if (!state.liner().door) {
                if ((rights & (AccessMath.DRIVE | AccessMath.PASSENGER)) == 0) {
                    lockedMsg(p);
                } else {
                    LinerLogicSVA.door(this, p);
                }
                return true;
            }
        }
        if (driver && riddenByEntity == null) {
            p.mountEntity(this);
            oneShot("door", 0.6F, 1F);       // ★1
            if (isWrecked()) {
                msg(p, "sva.msg.wrecked");
            }
            return true;
        }
        if ((rights & AccessMath.PASSENGER) == 0) {
            lockedMsg(p);                                           // ★10: locked for this player
            return true;
        }
        EntitySeatSVA s = freeSeat();
        if (s != null) {
            p.mountEntity(s);
            oneShot("door", 0.6F, 1.1F);
            if (!driver && riddenByEntity == null) {
                msg(p, "sva.msg.passengerOnly");
            }
            return true;
        }
        msg(p, "sva.msg.noSeat");
        return true;
    }

    /** Pick up / open cargo / the access screen: the owner, or an op in creative with vehicles.opManage. */
    public boolean canManage(EntityPlayer p) {
        if (state.ownerId == null || state.ownerId.length() == 0 || state.isOwner(p.getUniqueID())) {
            return true;
        }
        return ConfigSVA.opManage && p.capabilities.isCreativeMode && isOp(p);
    }

    /** A server operator (server side; false on a client or without a server). */
    public static boolean isOp(EntityPlayer p) {
        if (!(p instanceof EntityPlayerMP)) {
            return false;
        }
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        return s != null && s.getConfigurationManager() != null
                && s.getConfigurationManager().func_152596_g(((EntityPlayerMP) p).getGameProfile());
    }

    /** Driver's seat: the owner, or (★10) whoever the access list / a key card lets drive. */
    public boolean canDrive(EntityPlayer p) {
        return (rightsOf(p) & AccessMath.DRIVE) != 0;
    }

    // ------------------------------------------------------------------ wave 2, ★10: access rights and key cards

    /** The owner (or a vehicle nobody owns). */
    public boolean isOwnedBy(EntityPlayer p) {
        return state.ownerId == null || state.ownerId.length() == 0 || p != null && state.isOwner(p.getUniqueID());
    }

    /** A server op with vehicles.lockOpBypass. */
    public static boolean opBypass(EntityPlayer p) {
        return ConfigSVA.lockOpBypass && isOp(p);
    }

    /** The rights (AccessMath bits) a valid key card in p's inventory gives here, -1 without one. */
    public int cardRights(EntityPlayer p) {
        int best = -1;
        if (p == null || p.inventory == null) {
            return best;
        }
        for (ItemStack s : p.inventory.mainInventory) {
            int r = com.sva.item.ItemKeyCardSVA.rightsFor(s, state.vehId, state.access);
            if (r >= 0) {
                best = best < 0 ? r : best | r;
            }
        }
        return best;
    }

    /** ★10: what p may do with this vehicle (AccessMath bits; server). With vehicles.locks off: the old rules. */
    public int rightsOf(EntityPlayer p) {
        if (p == null) {
            return 0;
        }
        AccessMath.Access a = state.access;
        String uuid = p.getUniqueID().toString();
        if (!worldObj.isRemote && ConfigSVA.locks && a.refresh(uuid, p.getCommandSenderName())) {
            updateRegistry();                                       // ТН-4: the row gets his UUID / follows his new name
        }
        return AccessMath.rights(ConfigSVA.locks, isOwnedBy(p), opBypass(p), a.mode, a.listed(uuid, p.getCommandSenderName()), a.others,
                ConfigSVA.locks ? cardRights(p) : -1);
    }

    public boolean hasRight(EntityPlayer p, int right) {
        return (rightsOf(p) & right) != 0;
    }

    /** The cargo screen: the owner / an op in creative (as before), or ★10 the «Багажник» right. */
    public boolean canOpenCargo(EntityPlayer p) {
        return canManage(p) || ConfigSVA.locks && hasRight(p, AccessMath.CARGO);
    }

    /** «Машина заперта (владелец: NAME)». */
    public void lockedMsg(EntityPlayer p) {
        msg(p, ConfigSVA.locks ? "sva.msg.locked" : "sva.msg.notOwner", state.ownerName == null ? "" : state.ownerName);
    }

    /**
     * A key card in the hand (server): a blank one binds to the owner's vehicle (the owner's card: every right);
     * Shift+RMB with this vehicle's card opens the access screen (owner / op). @return handled (else: on to the
     * normal boarding - the card's rights count there)
     */
    private boolean keyCard(EntityPlayer p, ItemStack held) {
        if (!ConfigSVA.locks) {
            return false;
        }
        if (!com.sva.item.ItemKeyCardSVA.isBound(held)) {
            if (!canManage(p) || state.ownerId == null || state.ownerId.length() == 0) {
                msg(p, "sva.msg.notOwner");
                return true;
            }
            ItemStack card = com.sva.item.ItemKeyCardSVA.write(held, state.vehId, getBoardNumber(), state.type, state.ownerName,
                    state.ownerName, state.access.lockVersion, AccessMath.ALL);
            held.stackSize--;
            if (held.stackSize <= 0) {
                p.inventory.setInventorySlotContents(p.inventory.currentItem, card);
            } else if (!p.inventory.addItemStackToInventory(card)) {
                p.dropPlayerItemWithRandomChoice(card, false);
            }
            p.inventoryContainer.detectAndSendChanges();
            msg(p, "sva.msg.card.bound", Integer.valueOf(getBoardNumber()));
            oneShot("ding", 0.5F, 1.4F);
            return true;
        }
        boolean ours = state.vehId.equals(com.sva.item.ItemKeyCardSVA.vehicleOf(held));
        if (!p.isSneaking()) {
            return false;
        }
        if (!ours) {
            msg(p, "sva.msg.card.other");
            return true;
        }
        if (!canManage(p)) {
            msg(p, "sva.msg.notOwner");
            return true;
        }
        if (p instanceof EntityPlayerMP && ((EntityPlayerMP) p).playerNetServerHandler != null) {
            NetSVA.CHANNEL.sendTo(new NetSVA.AccessInfo(this, true), (EntityPlayerMP) p);   // opens the screen
        }
        return true;
    }

    /** Access screen edits (AccessEdit packet, server; the sender is the owner / an op, near). */
    public static final int AE_ADD = 0, AE_REMOVE = 1, AE_SET = 2, AE_OTHERS = 3, AE_MODE = 4, AE_RELOCK = 5, AE_ISSUE = 6,
            /** «Передать владельца»: name = the new owner, value 1 = also «Сменить замок» (old cards stop working). */
            AE_TRANSFER = 7;

    public void accessEdit(EntityPlayerMP p, int op, String name, int value) {
        if (!ConfigSVA.locks || !canManage(p)) {
            return;
        }
        if (!(carries(p) || p.getDistanceSqToEntity(this) < 64)) {
            if (p.playerNetServerHandler != null) {
                NetSVA.CHANNEL.sendTo(new NetSVA.AccessInfo(this, false), p);   // too far: undo the client's optimistic view
            }
            return;
        }
        AccessMath.Access a = state.access;
        if (op == AE_ADD) {
            EntityPlayerMP online = net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().func_152612_a(name);
            String uuid = online == null ? null : online.getUniqueID().toString();
            if (!a.add(name, uuid)) {
                if (a.find(name) >= 0 || uuid != null && a.findPlayer(uuid, null) >= 0) {
                    msg(p, "sva.msg.access.already", name);
                } else {
                    msg(p, AccessMath.validName(name) ? "sva.msg.access.full" : "sva.msg.access.badName", Integer.valueOf(AccessMath.MAX_FRIENDS));
                }
            }
        } else if (op == AE_REMOVE) {
            a.remove(name);
        } else if (op == AE_SET) {
            a.set(name, value);
        } else if (op == AE_OTHERS) {
            a.others = AccessMath.norm(value);
        } else if (op == AE_MODE) {
            a.mode = Math.max(0, Math.min(AccessMath.MODES - 1, value));
        } else if (op == AE_RELOCK) {
            a.lockVersion++;
            ItemStack held = p.getCurrentEquippedItem();
            if (state.vehId != null && state.vehId.equals(com.sva.item.ItemKeyCardSVA.vehicleOf(held))) {
                com.sva.item.ItemKeyCardSVA.relock(held, a.lockVersion);                     // the owner's own card follows (only this vehicle's)
            }
            p.inventoryContainer.detectAndSendChanges();
            msg(p, "sva.msg.access.relocked");
        } else if (op == AE_ISSUE) {
            issueCard(p, name);
        } else if (op == AE_TRANSFER) {
            transferOwner(p, name, value != 0);
        }
        evictUnauthorized();
        if (p.playerNetServerHandler != null) {
            NetSVA.CHANNEL.sendTo(new NetSVA.AccessInfo(this, false), p);   // refresh only (never reopens a closed screen)
        }
    }

    /** After an access edit: a driver without «Водить» and passengers without «Пассажир» get off (open cargo screens: isUseableByPlayer). */
    private void evictUnauthorized() {
        if (riddenByEntity instanceof EntityPlayer && !canDrive((EntityPlayer) riddenByEntity)) {
            EntityPlayer d = (EntityPlayer) riddenByEntity;
            d.mountEntity(null);
            lockedMsg(d);
        }
        if (seats != null) {
            for (EntitySeatSVA s : seats) {
                if (s != null && s.riddenByEntity instanceof EntityPlayer && !hasRight((EntityPlayer) s.riddenByEntity, AccessMath.PASSENGER)) {
                    EntityPlayer r = (EntityPlayer) s.riddenByEntity;
                    r.mountEntity(null);
                    lockedMsg(r);
                }
            }
        }
    }

    /**
     * «Передать владельца» (server): only the owner himself (not an op), to a player online within TRANSFER_RANGE of the
     * vehicle, within his vehicles.maxPerPlayer. The vehId stays (the map card, the board number); `relock` bumps the
     * lock version so every card issued so far - the old owner's too - stops working.
     */
    private void transferOwner(EntityPlayerMP p, String name, boolean relock) {
        if (state.ownerId == null || state.ownerId.length() == 0 || !state.isOwner(p.getUniqueID())) {
            msg(p, "sva.msg.owner.onlyOwner");
            return;
        }
        EntityPlayerMP to = null;
        for (Object o : worldObj.playerEntities) {
            if (o instanceof EntityPlayerMP && !(o instanceof net.minecraftforge.common.util.FakePlayer)
                    && ((EntityPlayerMP) o).getCommandSenderName().equalsIgnoreCase(name)) {
                to = (EntityPlayerMP) o;
            }
        }
        if (to == null || to == p || to.getDistanceSqToEntity(this) > TRANSFER_RANGE * TRANSFER_RANGE) {
            msg(p, "sva.msg.owner.noPlayer", name == null ? "" : name, Integer.valueOf((int) TRANSFER_RANGE));
            return;
        }
        String id = to.getUniqueID().toString();
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(worldObj);
        if (reg != null && !VehicleMath.limitAllows(reg.count(id), ConfigSVA.maxPerPlayer)) {
            msg(p, "sva.msg.owner.limit", to.getCommandSenderName(), Integer.valueOf(ConfigSVA.maxPerPlayer));
            return;
        }
        String old = state.ownerName == null ? "" : state.ownerName;
        state.ownerId = id;
        state.ownerName = to.getCommandSenderName();
        state.access.remove(state.ownerName);                      // the owner needs no row of his own
        if (relock) {
            state.access.lockVersion++;
        }
        updateRegistry();
        syncWatcher();
        oneShot("ding", 0.5F, 1.2F);
        msg(p, "sva.msg.owner.given", state.ownerName, Integer.valueOf(getBoardNumber()));
        msg(to, "sva.msg.owner.received", old, Integer.valueOf(getBoardNumber()));
    }

    /** «Выдать ключ-карту»: a blank card from p's inventory becomes a card for `name` with that player's rights. */
    private void issueCard(EntityPlayerMP p, String name) {
        int r = state.access.listed(name);
        if (r < 0) {
            msg(p, "sva.msg.access.notListed", name);
            return;
        }
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            ItemStack s = p.inventory.mainInventory[i];
            if (com.sva.item.ItemKeyCardSVA.isCard(s) && !com.sva.item.ItemKeyCardSVA.isBound(s)) {
                int issue = state.access.reissue(name);             // ТН-1: his older cards stop working
                ItemStack card = com.sva.item.ItemKeyCardSVA.write(s, state.vehId, getBoardNumber(), state.type, state.ownerName, name,
                        state.access.lockVersion, r, state.access.idOf(name), issue);
                p.inventory.decrStackSize(i, 1);
                if (!p.inventory.addItemStackToInventory(card)) {
                    p.dropPlayerItemWithRandomChoice(card, false);
                }
                p.inventoryContainer.detectAndSendChanges();
                updateRegistry();
                msg(p, "sva.msg.card.issued", name);
                return;
            }
        }
        msg(p, "sva.msg.card.noBlank");
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
        if (r == PICK_FAR) {
            msg(p, "sva.msg.tooFar");
        } else if (r == PICK_NOT_OWNER) {
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
            closeCargoScreens();                                    // no clicks into the state of a vehicle that became an item
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

    /** Server: this vehicle's cargo screen closes for everyone who has it open (it leaves the world / its slots change). */
    public void closeCargoScreens() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        for (Object o : new ArrayList<Object>(worldObj.playerEntities)) {
            if (o instanceof EntityPlayerMP && ((EntityPlayerMP) o).openContainer instanceof com.sva.inventory.ContainerCargoSVA
                    && ((com.sva.inventory.ContainerCargoSVA) ((EntityPlayerMP) o).openContainer).vehicle() == this) {
                EntityPlayerMP mp = (EntityPlayerMP) o;
                if (mp.playerNetServerHandler != null) {
                    mp.closeScreen();                               // later clicks with the old window id are dropped
                } else {
                    mp.closeContainer();
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
        if (a instanceof EntityPlayer && !playerDamageAllowed(ConfigSVA.playerDamage, isOwnedBy((EntityPlayer) a), pvpOn())) {
            return false;                                           // vehicles.playerDamage: not this player's to break
        }
        damageHull(amount);
        worldObj.playSoundAtEntity(this, "mob.irongolem.hit", 0.5F, 1.3F);
        return true;
    }

    /**
     * Pure: does a player's hit (hand, arrow, his explosion) dent the hull? vehicles.playerDamage: "all" - anyone;
     * "pvp" (default, also anything unknown) - the owner, others only with the server's PvP on; "owner" - only the
     * owner; "none" - no player at all. Mobs, falls and explosions without a player are not asked.
     */
    public static boolean playerDamageAllowed(String mode, boolean owner, boolean pvp) {
        if (PD_NONE.equalsIgnoreCase(mode)) {
            return false;
        }
        if (owner || PD_ALL.equalsIgnoreCase(mode)) {
            return true;
        }
        return !PD_OWNER.equalsIgnoreCase(mode) && pvp;
    }

    public static final String PD_ALL = "all", PD_PVP = "pvp", PD_OWNER = "owner", PD_NONE = "none";

    private static boolean pvpOn() {
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        return s != null && s.isPVPEnabled();
    }

    /** Hull damage (server). */
    public void damageHull(float amount) {
        if (!ConfigSVA.wear) {
            return;
        }
        amount = DrillMath.fieldDamage(amount, fieldNow, state.type.gases.fieldReduction);   // the argon field
        if (ConfigSVA.tuning) {
            amount *= (float) TuningMath.damageMul(state.tuning);                             // ★8 «Броня»: -40 %
        }
        boolean was = VehicleMath.wrecked(state.hp);
        float max = state.maxHp();
        boolean worn = state.hp < max * com.sva.vehicle.CockpitLamps.WORN_HP;
        state.hp = VehicleMath.damage(state.hp, amount);
        if (!worn && state.hp < max * com.sva.vehicle.CockpitLamps.WORN_HP && state.hp > 0) {
            oneShot("alarm", 0.8F, 1F);      // ★1: the hull is worn - the wear lamp lights
        }
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

    /**
     * Hitches a trailer item (its cargo goes into the slots after the body's; ★15 the other kinds bring their tank /
     * carried vehicle / charge). @return CrawlerMath.OK / why not
     */
    public int attachTrailer(EntityPlayer p, ItemStack item) {
        int r = CrawlerMath.attachCheck(state.type.trailerSlots, state.trailer, canManage(p));
        if (r != CrawlerMath.OK) {
            msg(p, r == CrawlerMath.NOT_OWNER ? "sva.msg.notOwner" : "sva.msg.trailer.already");
            return r;
        }
        int kind = com.sva.item.ItemTrailerSVA.kindOf(item);
        if (kind != TrailerMath.CARGO && !ConfigSVA.trailerKinds) {
            msg(p, "sva.msg.trailer.disabled");
            return CrawlerMath.NO_HITCH;
        }
        state.setTrailer(true, kind);
        if (kind == TrailerMath.CARGO) {
            ItemStack[] c = com.sva.item.ItemTrailerSVA.cargoOf(item);
            int base = state.type.cargoSlots;
            for (int i = 0; i < c.length && base + i < state.cargo.length; i++) {
                state.cargo[base + i] = c[i];
            }
        } else {
            state.readTrailerData(com.sva.item.ItemTrailerSVA.dataOf(item));
        }
        carriedCard = null;
        chargerServed.clear();
        worldObj.playSoundAtEntity(this, "random.anvil_land", 0.4F, 1.6F);
        if (kind == TrailerMath.CARGO) {
            msg(p, "sva.msg.trailer.attached", Integer.valueOf(state.cargoSize()));
        } else {
            msg(p, "sva.msg.trailer.attachedKind", new ChatComponentTranslation("item.siliconagevehicles.trailer_" + TrailerMath.KEYS[kind] + ".name"));
        }
        syncWatcher();
        return r;
    }

    /** Unhitches the trailer (no checks): @return the trailer item holding its cargo (★15: or its contents). */
    public ItemStack detachTrailer() {
        int kind = state.trailer ? state.trailerType : TrailerMath.CARGO;
        ItemStack out;
        if (kind == TrailerMath.CARGO) {
            int base = state.type.cargoSlots;
            ItemStack[] c = new ItemStack[state.type.trailerSlots];
            for (int i = 0; i < c.length && base + i < state.cargo.length; i++) {
                c[i] = state.cargo[base + i];
            }
            out = com.sva.item.ItemTrailerSVA.withCargo(new ItemStack(ModContentSVA.trailer), c);
        } else {
            out = com.sva.item.ItemTrailerSVA.withData(new ItemStack(ModContentSVA.trailer, 1, kind), state.writeTrailerData());
        }
        state.setTrailer(false);
        carriedCard = null;
        chargerServed.clear();
        syncWatcher();
        return out;
    }

    // ------------------------------------------------------------------ wave 3, ★15: the trailer kinds at work

    /** Server, every tick with a tank / platform / charger hitched. */
    private void trailerTick(boolean motorOn) {
        int k = state.trailerType;
        if (k == TrailerMath.TANK) {
            if (ticksExisted % 5 == 0) {
                tankSlots();
            }
        } else if (k == TrailerMath.PLATFORM) {
            if (state.carried != null && ticksExisted % 20 == 0) {
                chargeCarried();
            }
        } else if (k == TrailerMath.CHARGER) {
            chargerTick(motorOn);
        }
    }

    /** ★15 tanker: the «in» slot pours a full container in, the «out» slot fills an empty one (whole containers). */
    private void tankSlots() {
        ItemStack in = state.tankIn;
        if (in != null && in.stackSize == 1) {
            FluidStack f = FluidContainerRegistry.getFluidForFilledItem(in);
            if (f != null && f.getFluid() != null && f.amount > 0) {
                String name = f.getFluid().getName();
                if (TrailerMath.tankRoom(state.tankFluid, state.tankAmount, name, f.amount) == f.amount) {
                    tankAdd(name, f.amount);
                    state.tankIn = FluidContainerRegistry.drainFluidContainer(in);
                }
            } else if (in.getItem() instanceof IFluidContainerItem) {
                IFluidContainerItem c = (IFluidContainerItem) in.getItem();
                FluidStack cf = c.getFluid(in);
                if (cf != null && cf.getFluid() != null && cf.amount > 0) {
                    int room = TrailerMath.tankRoom(state.tankFluid, state.tankAmount, cf.getFluid().getName(), cf.amount);
                    FluidStack d = room > 0 ? c.drain(in, room, true) : null;
                    if (d != null && d.amount > 0) {
                        tankAdd(cf.getFluid().getName(), d.amount);
                    }
                }
            }
        }
        ItemStack out = state.tankOut;
        Fluid fl = state.tankAmount > 0 ? net.minecraftforge.fluids.FluidRegistry.getFluid(state.tankFluid) : null;
        if (out != null && out.stackSize == 1 && fl != null) {
            if (FluidContainerRegistry.isEmptyContainer(out)) {
                ItemStack filled = FluidContainerRegistry.fillFluidContainer(new FluidStack(fl, state.tankAmount), out);
                FluidStack got = filled == null ? null : FluidContainerRegistry.getFluidForFilledItem(filled);
                if (got != null && got.amount > 0 && got.amount <= state.tankAmount) {
                    tankTake(got.amount);
                    state.tankOut = filled;
                }
            } else if (out.getItem() instanceof IFluidContainerItem) {
                int put = ((IFluidContainerItem) out.getItem()).fill(out, new FluidStack(fl, state.tankAmount), true);
                if (put > 0) {
                    tankTake(Math.min(put, state.tankAmount));
                }
            }
        }
    }

    /** ★15 tanker: mB of `fluid` in (the caller checked the room). */
    public void tankAdd(String fluid, int mb) {
        if (mb <= 0) {
            return;
        }
        if (state.tankAmount <= 0) {
            state.tankFluid = fluid;
        }
        state.tankAmount = Math.min(TrailerMath.TANK_CAP, state.tankAmount + mb);
        syncWatcher();
    }

    private void tankTake(int mb) {
        state.tankAmount = Math.max(0, state.tankAmount - mb);
        if (state.tankAmount == 0) {
            state.tankFluid = "";
        }
        syncWatcher();
    }

    /**
     * ★15 «Заправить технику рядом»: a gas in the tank goes into every vehicle within TrailerMath.REFUEL_RADIUS blocks of
     * the trailer that has a tank for it (this crawler too), as much as each takes. @return mB moved
     */
    public int refuelNearby(EntityPlayer p) {
        Fluid fl = state.tankAmount > 0 ? net.minecraftforge.fluids.FluidRegistry.getFluid(state.tankFluid) : null;
        Gas g = Gas.of(fl);
        if (g == null) {
            if (p != null) {
                msg(p, state.tankAmount > 0 ? "sva.msg.tank.notGas" : "sva.msg.tank.empty");
            }
            return 0;
        }
        double[] tp = trailerPos();
        double r = TrailerMath.REFUEL_RADIUS;
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(tp[0] - r, posY - 4, tp[1] - r, tp[0] + r, posY + 6, tp[1] + r);
        int total = 0, served = 0;
        for (Object o : worldObj.getEntitiesWithinAABB(EntityVehicleSVA.class, box)) {
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            if (v.isDead || state.tankAmount <= 0) {
                continue;
            }
            double dx = v.posX - tp[0], dz = v.posZ - tp[1];
            if (dx * dx + dz * dz > r * r && v != this) {
                continue;
            }
            int put = v.addGas(g, Math.min(state.tankAmount, v.gasRoom(g)));
            if (put > 0) {
                tankTake(put);
                total += put;
                served++;
                v.markCharging();
                if (p != null) {
                    msg(p, "sva.msg.tank.refuelled", new ChatComponentTranslation("item.siliconagevehicles.vehicle." + v.state.type.key + ".name"),
                            com.sva.vehicle.SkinMath.boardText(v.getBoardNumber()), com.sva.FormatSVA.group(put), new ChatComponentTranslation("sva.gas." + g.key()));
                }
            }
        }
        if (p != null && served == 0) {
            msg(p, "sva.msg.tank.noTarget", Integer.valueOf((int) r), new ChatComponentTranslation("sva.gas." + g.key()));
        }
        if (total > 0) {
            oneShot("ding", 0.5F, 1.2F);
        }
        return total;
    }

    /** ★15: may the platform take this vehicle (the world one: empty, the player's to manage, near the deck)? */
    private boolean loadable(EntityVehicleSVA v, EntityPlayer p, double[] tp) {
        if (v == this || v.isDead || !TrailerMath.platformFits(v.state.type) || v.occupied() || p != null && !v.canManage(p)) {
            return false;
        }
        double dx = v.posX - tp[0], dz = v.posZ - tp[1];
        return dx * dx + dz * dz <= TrailerMath.LOAD_RADIUS * TrailerMath.LOAD_RADIUS && Math.abs(v.posY - posY) < 4;
    }

    /** ★15 «Погрузить ближайшую»: the nearest empty small vehicle of the player's goes onto the platform. @return loaded */
    public boolean loadNearest(EntityPlayer p) {
        if (!state.hasTrailer(TrailerMath.PLATFORM)) {
            return false;
        }
        if (state.carried != null) {
            msg(p, "sva.msg.platform.full");
            return false;
        }
        double[] tp = trailerPos();
        double r = TrailerMath.LOAD_RADIUS;
        EntityVehicleSVA best = null;
        double bestD = Double.MAX_VALUE;
        for (Object o : worldObj.getEntitiesWithinAABB(EntityVehicleSVA.class,
                AxisAlignedBB.getBoundingBox(tp[0] - r, posY - 4, tp[1] - r, tp[0] + r, posY + 4, tp[1] + r))) {
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            double d = (v.posX - tp[0]) * (v.posX - tp[0]) + (v.posZ - tp[1]) * (v.posZ - tp[1]);
            if (loadable(v, p, tp) && d < bestD) {
                best = v;
                bestD = d;
            }
        }
        if (best == null) {
            msg(p, "sva.msg.platform.none", Integer.valueOf((int) r));
            return false;
        }
        loadVehicle(best);
        msg(p, "sva.msg.platform.loaded", new ChatComponentTranslation("item.siliconagevehicles.vehicle." + best.state.type.key + ".name"),
                com.sva.vehicle.SkinMath.boardText(best.getBoardNumber()));
        return true;
    }

    /** ★15 (server; the world test): the vehicle leaves the world and rides the platform as its item (everything kept). */
    public void loadVehicle(EntityVehicleSVA v) {
        ItemStack it = v.toItemStack();
        v.dismountAll();
        v.setDead();
        state.carried = it.writeToNBT(new NBTTagCompound());
        carriedCard = null;
        worldObj.playSoundAtEntity(this, "random.anvil_land", 0.5F, 1.2F);
        syncWatcher();
    }

    /** ★15: RMB on the crawler with a vehicle item - it goes onto the platform (a small one, the platform empty). */
    private void loadItem(EntityPlayer p, ItemStack held) {
        if (!canOpenCargo(p)) {
            lockedMsg(p);
            return;
        }
        VehicleType vt = com.sva.item.ItemVehicleSVA.typeOf(held);
        if (state.carried != null) {
            msg(p, "sva.msg.platform.full");
            return;
        }
        if (!TrailerMath.platformFits(vt)) {
            msg(p, "sva.msg.platform.tooBig", new ChatComponentTranslation("item.siliconagevehicles.vehicle." + vt.key + ".name"));
            return;
        }
        String owner = p.getUniqueID().toString();
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(worldObj);
        if (reg != null && !VehicleMath.limitAllows(reg.count(owner), ConfigSVA.maxPerPlayer)) {
            msg(p, "sva.msg.limit", Integer.valueOf(ConfigSVA.maxPerPlayer));     // else it could never be unloaded
            return;
        }
        ItemStack one = held.splitStack(1);
        if (held.stackSize <= 0) {
            p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
        }
        p.inventoryContainer.detectAndSendChanges();
        // an item is whoever holds it (as placing it - ItemVehicleSVA.spawn): the loader owns it, a fresh id
        VehicleState ks = com.sva.item.ItemVehicleSVA.stateOf(one);
        ks.ownerId = owner;
        ks.ownerName = p.getCommandSenderName();
        ks.vehId = java.util.UUID.randomUUID().toString();
        com.sva.item.ItemVehicleSVA.withState(one, ks);
        state.carried = one.writeToNBT(new NBTTagCompound());
        carriedCard = null;
        worldObj.playSoundAtEntity(this, "random.anvil_land", 0.5F, 1.2F);
        msg(p, "sva.msg.platform.loadedItem", new ChatComponentTranslation("item.siliconagevehicles.vehicle." + vt.key + ".name"));
        syncWatcher();
    }

    /**
     * ★15 «Выгрузить»: the carried vehicle is placed beside the crawler (behind the trailer, left, right, in front - the
     * first free spot) with its whole state and id (its key cards stay valid). @return the placed vehicle, or null
     */
    public EntityVehicleSVA unloadCarried(EntityPlayer p) {
        if (state.carried == null) {
            if (p != null) {
                msg(p, "sva.msg.platform.empty");
            }
            return null;
        }
        ItemStack it = ItemStack.loadItemStackFromNBT(state.carried);
        if (it == null || !(it.getItem() instanceof com.sva.item.ItemVehicleSVA)) {
            state.carried = null;
            carriedCard = null;
            syncWatcher();
            return null;
        }
        VehicleState st = com.sva.item.ItemVehicleSVA.stateOf(it);
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(worldObj);
        if (reg != null && st.ownerId.length() > 0 && !VehicleMath.limitAllows(reg.count(st.ownerId), ConfigSVA.maxPerPlayer)) {
            if (p != null) {
                msg(p, "sva.msg.limit", Integer.valueOf(ConfigSVA.maxPerPlayer));
            }
            return null;
        }
        VehicleType t = state.type;
        double side = t.width / 2.0 + st.type.width / 2.0 + 0.6;
        double back = TrailerMath.TRAILER_BACK + 2.4 + st.type.halfLength;
        double[][] spots = {{-back, 0}, {-TrailerMath.TRAILER_BACK, -side}, {-TrailerMath.TRAILER_BACK, side}, {0, -side}, {0, side},
                {t.halfLength + st.type.halfLength + 0.8, 0}};
        for (double[] o : spots) {
            double[] w = toWorld(o[0], o[1]);
            for (int up = 0; up <= 2; up++) {
                EntityVehicleSVA v = com.sva.item.ItemVehicleSVA.spawnKept(worldObj, it, posX + w[0], Math.floor(posY) + up, posZ + w[1],
                        rotationYaw);
                if (v != null) {
                    state.carried = null;
                    carriedCard = null;
                    worldObj.playSoundAtEntity(this, "random.anvil_land", 0.5F, 1.0F);
                    if (p != null) {
                        msg(p, "sva.msg.platform.unloaded", new ChatComponentTranslation("item.siliconagevehicles.vehicle." + st.type.key + ".name"),
                                com.sva.vehicle.SkinMath.boardText(v.getBoardNumber()));
                    }
                    syncWatcher();
                    return v;
                }
            }
        }
        if (p != null) {
            msg(p, "sva.msg.platform.noSpace");
        }
        return null;
    }

    /** ★15: the carried vehicle charges from the crawler, TrailerMath.CARRY_EU a tick (a second at once), not under 20 %. */
    private void chargeCarried() {
        ItemStack it = ItemStack.loadItemStackFromNBT(state.carried);
        if (it == null || !(it.getItem() instanceof com.sva.item.ItemVehicleSVA)) {
            return;
        }
        VehicleState st = com.sva.item.ItemVehicleSVA.stateOf(it);
        double room = st.maxEnergy() - st.energy;
        int eu = TrailerMath.carryCharge(state.energy, state.maxEnergy(), room / 20.0) * 20;
        if (eu <= 0) {
            return;
        }
        eu = (int) Math.min(eu, Math.max(0, state.energy - state.maxEnergy() * TrailerMath.FEED_FLOOR));
        if (eu <= 0) {
            return;
        }
        st.energy += eu;
        state.energy -= eu;
        state.carried = com.sva.item.ItemVehicleSVA.withState(it, st).writeToNBT(new NBTTagCompound());
        carriedCard = null;
    }

    /**
     * ★15 mobile charger: the sun (day, open sky over the trailer, no rain), the crawler's feed while driving (not under
     * 20 % of its own charge), and one vehicle within TrailerMath.CHARGE_RADIUS blocks charged at the HV station rate
     * (round robin, rescanned every second; never the crawler itself).
     */
    private void chargerTick(boolean motorOn) {
        double[] tp = trailerPos();
        int bx = MathHelper.floor_double(tp[0]), bz = MathHelper.floor_double(tp[1]), by = MathHelper.floor_double(posY) + 2;
        double room = TrailerMath.CHARGER_BUFFER - state.chargerEnergy;
        int sun = TrailerMath.solar(worldObj.isDaytime(), worldObj.canBlockSeeTheSky(bx, by, bz), worldObj.isRaining(), room);
        solarNow = sun > 0;
        state.chargerEnergy += sun;
        room -= sun;
        int fed = TrailerMath.feed(motorOn && speedEma > 0.02, state.energy, state.maxEnergy(), room, TrailerMath.FEED_EU);
        if (fed > 0) {
            state.chargerEnergy += fed;
            state.energy -= fed;
        }
        if (--chargerScan <= 0) {
            chargerScan = 20;
            chargerServed.clear();
            double r = TrailerMath.CHARGE_RADIUS;
            for (Object o : worldObj.getEntitiesWithinAABB(EntityVehicleSVA.class,
                    AxisAlignedBB.getBoundingBox(tp[0] - r, posY - 3, tp[1] - r, tp[0] + r, posY + 5, tp[1] + r))) {
                EntityVehicleSVA v = (EntityVehicleSVA) o;
                double dx = v.posX - tp[0], dz = v.posZ - tp[1];
                if (v != this && !v.isDead && dx * dx + dz * dz <= r * r) {
                    chargerServed.add(v);
                }
            }
        }
        int n = chargerServed.size();
        int rate = com.sva.platform.PlatformMath.rate(com.sc.energy.Tier.HV, 0, 0, ConfigSVA.chargerRate);
        for (int i = 0; i < n; i++) {
            int idx = com.sva.platform.PlatformMath.nextIndex(chargerRr + i, n);
            EntityVehicleSVA v = chargerServed.get(idx);
            if (!inWorld(v) || v.energyRoom() < 1) {
                continue;                                           // unloaded since the scan: its EU would be lost
            }
            int moved = VehicleMath.chargeTransfer((int) Math.min(Integer.MAX_VALUE, state.chargerEnergy), rate, v.energyRoom());
            if (moved > 0) {
                state.chargerEnergy -= moved;
                v.addEnergy(moved);
                v.markCharging();
                chargerRr = idx;
            }
            break;
        }
    }

    /** ★19 (server): this vehicle's card in the registry - where it is, charge, hull, who may see it. */
    private void updateRegistry() {
        VehicleRegistrySVA r = VehicleRegistrySVA.get(worldObj);
        if (r == null) {
            return;
        }
        r.update(state.vehId, state.ownerId, state.ownerName, state.type.ordinal(), getBoardNumber(), posX, posY, posZ,
                worldObj.provider.dimensionId, (float) (state.energy / Math.max(1.0, state.maxEnergy())),
                (float) (state.hp / Math.max(1F, state.maxHp())), worldObj.getTotalWorldTime(), state.access);
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
        if (action == A_FLIGHT_MODE || action >= A_LONG_RANGE && action <= A_INVIS || action >= A_FLAPS_UP && action <= A_AIRBRAKE
                || action == A_ENGINE || action >= A_SWITCH && action < A_SWITCH + SystemsMath.SWITCHES || action == A_IDENT
                || action == A_LINER_QUICK || action == A_LINER_GEAR || action == A_LINER_APDISC || action == A_LINER_ATHRDISC) {
            return driver;                                          // only the pilot turns the nacelles / works the ship / the flaps
        }
        return manage;
    }

    /** ★10: the same, with the «Багажник» right: it opens the cargo screen and sorts / throws out junk like the owner. */
    public static boolean actionAllowed(int action, boolean manage, boolean driver, boolean cargo) {
        if (action == A_CARGO || action == A_SORT || action == A_JUNK || action >= A_TANK_REFUEL && action <= A_PLATFORM_UNLOAD) {
            return manage || cargo;
        }
        return actionAllowed(action, manage, driver);
    }

    /** The Action packet (server thread; the sender is near or seated): a cargo screen button or a drill key. */
    public void serverAction(EntityPlayerMP p, int a) {
        if (a == A_BATTERY_OUT) {
            batteryOut(p);                                          // its own rule: the owner or «Ремонт»
            return;
        }
        if (a == A_BATTERY2_OUT) {
            battery2Out(p);                                         // liner wave 1: АКБ 2, the same rule
            return;
        }
        if (state.type == VehicleType.LINER && (a == A_LINER_QUICK || a == A_ENGINE || a == A_LINER_GEAR || a == A_LINER_APDISC
                || a == A_LINER_ATHRDISC)) {
            if (!LinerLogicSVA.pilot(this, p)) {
                lockedMsg(p);                                       // liner wave 1: either pilot (the F/O too)
            } else if (a == A_LINER_APDISC || a == A_LINER_ATHRDISC) {
                // liner wave 4: the instinctive disconnects (the keys)
                if (a == A_LINER_APDISC) {
                    state.liner().afs.apDisc();
                } else {
                    state.liner().afs.athrDisc();
                }
                vanillaClick(0.3F, 1.9F);
                syncWatcher();
            } else if (a == A_LINER_GEAR) {
                state.liner().control(com.sva.vehicle.LinerControls.GEAR.index, state.liner().at(com.sva.vehicle.LinerControls.GEAR) == 0
                        ? com.sva.vehicle.LinerControls.A_PRIMARY : com.sva.vehicle.LinerControls.A_SECONDARY);
                oneShot("door", 0.4F, 0.7F);
                syncWatcher();
            } else {
                LinerLogicSVA.quick(this, p);
            }
            return;
        }
        if (a == A_LINER_DOOR && state.type == VehicleType.LINER) {
            if (carries(p) || (rightsOf(p) & AccessMath.DRIVE) != 0) {
                LinerLogicSVA.door(this, p);
            } else {
                lockedMsg(p);
            }
            return;
        }
        if (state.type == VehicleType.LINER && a >= A_SWITCH && a < A_SWITCH + SystemsMath.SWITCHES) {
            return;                                                 // liner wave 1: its switches are the cockpit's, not the «Пульт» row
        }
        if (!actionAllowed(a, canManage(p), riddenByEntity == p, canOpenCargo(p))) {
            lockedMsg(p);
            return;
        }
        long now = worldObj.getTotalWorldTime();
        Long last = actionTicks.get(p.getUniqueID());
        if (last != null && now - last.longValue() < 4 && now >= last.longValue()) {
            return;                                                 // one press, not a flood (per player: nobody else's is lost)
        }
        if (actionTicks.size() > 16) {
            actionTicks.clear();
        }
        actionTicks.put(p.getUniqueID(), Long.valueOf(now));
        VehicleType t = state.type;
        if (a == A_IDENT) {
            com.sva.airdefense.AirDefenseSVA.INSTANCE.ident(this);   // air-defence wave
            return;
        }
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
        if (a == A_TANK_REFUEL && state.hasTrailer(TrailerMath.TANK)) {
            refuelNearby(p);
            return;
        }
        if (a == A_TANK_DUMP && state.hasTrailer(TrailerMath.TANK)) {
            int was = state.tankAmount;
            tankTake(was);
            msg(p, "sva.msg.tank.dumped", com.sva.FormatSVA.group(was));
            return;
        }
        if (a == A_PLATFORM_LOAD && state.hasTrailer(TrailerMath.PLATFORM)) {
            loadNearest(p);
            return;
        }
        if (a == A_PLATFORM_UNLOAD && state.hasTrailer(TrailerMath.PLATFORM)) {
            unloadCarried(p);
            return;
        }
        if (a == A_ACCESS) {
            if (ConfigSVA.locks && p.playerNetServerHandler != null) {
                if (p.openContainer instanceof com.sva.inventory.ContainerCargoSVA) {
                    p.closeContainer();                             // the access screen replaces the cargo screen
                }
                NetSVA.CHANNEL.sendTo(new NetSVA.AccessInfo(this, true), p);   // opens the screen
            }
            return;
        }
        if (a == A_CARGO) {
            if (carries(p)) {                                       // the key works from a seat only
                p.openGui(SVAMod.instance, GuiHandlerSVA.CARGO, worldObj, getEntityId(), 0, 0);
                oneShot("cargo", 0.5F, 1F);
            }
            return;
        }
        if (a == A_DRILL_TOGGLE && t.hasDrill) {
            if (!drillOn && !ConfigSVA.drill) {
                msg(p, "sva.msg.drill.disabled");                  // vehicles.drill off
            } else if (!drillOn && !DrillLogicSVA.isHead(state.drillHead)) {
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
        } else if ((a == A_FLAPS_UP || a == A_FLAPS_DOWN) && HighLiftMath.spec(t) != null) {
            flapCommand(p, HighLiftMath.spec(t), a == A_FLAPS_DOWN ? 1 : -1);
        } else if (a == A_AIRBRAKE && HighLiftMath.spec(t) != null && HighLiftMath.spec(t).airbrake) {
            airbrakeCommand(p);
        } else if (a == A_ENGINE && (SystemsMath.applies(t) || state.hydrogen())) {
            engineCommand(p);
        } else if (a >= A_SWITCH && a < A_SWITCH + SystemsMath.SWITCHES && (SystemsMath.applies(t)
                || a == A_SWITCH + SystemsMath.SW_INS && SystemsMath.insApplies(t))) {     // D5: the helicopter has only its ИНС
            switchCommand(p, a - A_SWITCH);
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
            if (!state.invisible && !ConfigSVA.mobCloak) {
                msg(p, "sva.msg.invis.disabled");                  // vehicles.mobCloak off
            } else {
                int r = toggleInvisible();
                msg(p, r == 1 ? "sva.msg.invis.on" : r == 0 ? "sva.msg.invis.off" : "sva.msg.invis.empty");
            }
        } else if (a == A_TRAILER_DETACH && t.trailerSlots > 0) {
            if (CrawlerMath.detachCheck(state.trailer, true) == CrawlerMath.OK) {
                closeCargoScreens();                                // everyone's: the trailer's slots leave with it
                ItemStack item = detachTrailer();
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
            dataWatcher.updateObject(DW_GAS, gasSync());
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
            List<Gas> gs = state.tanks();
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
        double was = state.energy;
        double got = state.addEnergy(eu);
        if (got > 0 && state.hydrogen()) {
            batteryWear(was, state.energy);                         // a station's charge wears the battery like the generator's
        }
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
        // the entity's own working state (not the item's): the bore, the throttle, auto-delivery's reason, the portal
        drillOn = t.hasDrill && tag.getBoolean(K_DRILL_ON) && DrillLogicSVA.isHead(state.drillHead);
        double thr = tag.getDouble(K_THROTTLE);
        throttle = Double.isNaN(thr) ? 0 : Math.max(0, Math.min(1, thr));
        HighLiftMath.Spec hl = HighLiftMath.spec(t);
        flaps = hl == null ? 0 : Math.max(0, Math.min(hl.last(), tag.getByte(K_FLAPS)));
        airbrake = hl != null && hl.airbrake && tag.getBoolean(K_AIRBRAKE);
        if (SystemsMath.applies(t) || state.hydrogen()) {
            // D2: a running engine keeps running over a reload (a pilot who logged out mid-flight); a start in progress starts over
            int es = tag.getByte(K_ENGINE);
            if (es == SystemsMath.RUN || es == SystemsMath.WARM) {
                engine.run();
            }
            ice = Math.max(0, Math.min(SystemsMath.ICE_TICKS, tag.getInteger(K_ICE)));
        }
        if (SystemsMath.insApplies(t) && (!tag.hasKey(K_INS) || tag.getBoolean(K_INS))) {
            // D5: aligned over a reload (a pilot who logged out mid-flight can't align in the air); the first tick drops it
            // if the board has no power or ИНС is off
            ins.align();
            insNow = SystemsMath.INS_READY;
        }
        autoReason = state.autoState == AutoDelivery.STOPPED || AutoDelivery.driving(state.autoState)
                ? Math.max(0, Math.min(7, tag.getByte(K_AUTO_REASON))) : AutoDelivery.R_NONE;
        if (t.singular && tag.hasKey(K_PORTAL) && tag.getInteger(K_PORTAL_TICKS) > 0) {
            NBTTagCompound pt = tag.getCompoundTag(K_PORTAL);
            portalTicks = Math.min(SpaceMath.PORTAL_TICKS, tag.getInteger(K_PORTAL_TICKS));
            portalTarget = new SpaceLogicSVA.Target(pt.getInteger("X"), pt.getInteger("Y"), pt.getInteger("Z"), pt.getInteger("Dim"),
                    pt.getString("Name"), pt.getBoolean("Home"));
        }
        syncWatcher();
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
        NBTTagCompound st = new NBTTagCompound();
        state.writeNBT(st);
        tag.setTag(NBT_ROOT, st);
        tag.setBoolean(K_DRILL_ON, drillOn);
        tag.setDouble(K_THROTTLE, throttle);
        tag.setByte(K_FLAPS, (byte) flaps);
        tag.setBoolean(K_AIRBRAKE, airbrake);
        tag.setByte(K_ENGINE, (byte) engine.state);
        tag.setInteger(K_ICE, ice);
        tag.setBoolean(K_INS, ins.ready);
        tag.setByte(K_AUTO_REASON, (byte) autoReason);
        if (portalTicks > 0 && portalTarget != null) {
            NBTTagCompound pt = new NBTTagCompound();
            pt.setInteger("X", portalTarget.x);
            pt.setInteger("Y", portalTarget.y);
            pt.setInteger("Z", portalTarget.z);
            pt.setInteger("Dim", portalTarget.dim);
            pt.setString("Name", portalTarget.name);
            pt.setBoolean("Home", portalTarget.home);
            tag.setInteger(K_PORTAL_TICKS, portalTicks);
            tag.setTag(K_PORTAL, pt);
        }
    }

    // ------------------------------------------------------------------ IInventory (cargo)

    /** The drill vehicle's head and module slots come after the cargo; ★15 the tanker's «in» / «out» container slots too. */
    public int extraSlots() {
        return getVehicleType().hasDrill || tankSlotsOn() ? 2 : 0;
    }

    /** ★15: the tanker's two container slots exist (a tank trailer is hitched). */
    public boolean tankSlotsOn() {
        return worldObj != null && worldObj.isRemote ? hasTrailerKind(TrailerMath.TANK) : state.hasTrailer(TrailerMath.TANK);
    }

    /** Index of the drill head slot (the module slot is the next one). */
    public int headSlot() {
        clientState();
        return state.cargo.length;
    }

    /** Client: the local state follows the synced type and trailer (the cargo screen's slots; ★15 the trailer's kind). */
    private void clientState() {
        if (worldObj != null && worldObj.isRemote) {
            VehicleType t = getVehicleType();
            boolean tr = hasTrailer();
            int kind = trailerKind();
            if (state.type != t || state.trailer != tr || tr && state.trailerType != kind) {
                VehicleState n = new VehicleState(t);
                n.setTrailer(tr, kind);
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
        boolean tank = tankSlotsOn();
        if (extraSlots() > 0 && i == n) {
            return tank ? state.tankIn : state.drillHead;
        }
        if (extraSlots() > 0 && i == n + 1) {
            return tank ? state.tankOut : state.drillModule;
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
        } else if (tankSlotsOn() && i == n) {
            state.tankIn = s;                                       // ★15 the tanker's slots
        } else if (tankSlotsOn() && i == n + 1) {
            state.tankOut = s;
        } else if (extraSlots() > 0 && i == n) {
            state.drillHead = s;
            if (s == null && worldObj != null && !worldObj.isRemote) {
                drillStop("sva.msg.drill.noHead");
            }
        } else if (extraSlots() > 0 && i == n + 1) {
            state.drillModule = s;
        } else {
            if (s != null && worldObj != null && !worldObj.isRemote) {
                entityDropItem(s, 0.5F);                            // a slot that is gone (a stale screen): not lost
            }
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
        return !isDead && p.getDistanceSqToEntity(this) < 64 && (worldObj.isRemote || canOpenCargo(p));     // ★10: a right taken away closes it
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
        if (tankSlotsOn() && (i == n || i == n + 1)) {
            return s == null || com.sc.util.FluidHandSC.isContainer(s);       // ★15 buckets, cells, tanks-in-an-item
        }
        if (extraSlots() > 0 && i == n) {
            return s == null || DrillLogicSVA.isHead(s);
        }
        if (extraSlots() > 0 && i == n + 1) {
            return s == null || DrillLogicSVA.isWidener(s);
        }
        return cargoAllows(s);
    }

    /** No vehicles inside vehicles, nor trailers holding something (cargo, fluid, a vehicle, charge): no nesting. */
    public static boolean cargoAllows(ItemStack s) {
        return s == null || !(s.getItem() instanceof com.sva.item.ItemVehicleSVA)
                && !(s.getItem() instanceof com.sva.item.ItemTrailerSVA && com.sva.item.ItemTrailerSVA.hasContents(s));
    }

    // ---- ISidedInventory: hoppers and pipes see no slots (they would bypass the locks); the cargo screen works through its Slots

    private static final int[] NO_SLOTS = new int[0];

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        return NO_SLOTS;
    }

    @Override
    public boolean canInsertItem(int slot, ItemStack s, int side) {
        return false;
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack s, int side) {
        return false;
    }
}
