package com.sva.entity;

import java.util.ArrayList;
import java.util.List;

import com.sva.ConfigSVA;
import com.sva.SVAMod;
import com.sva.init.ModContentSVA;
import com.sva.inventory.GuiHandlerSVA;
import com.sva.net.NetSVA;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HeliMath;
import com.sva.vehicle.HoverMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.PlaneMath;
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
            DW_CRUISE = 26, DW_YAW = 27, DW_GAS = 28, DW_THR = 29, DW_COST = 30;
    private static final int F_WRECKED = 1, F_HEAT_STOP = 2, F_WEAR = 4, F_LIFT = 8, F_BOOST = 16, F_CHARGING = 32,
            /** Aircraft: in the air without power (heli autorotation / plane glide); plane: stalling. */
            F_AUTO = 64, F_STALL = 128;
    /**
     * Input bits. IN_BRAKE = Space (ground: brake; hover: hold higher; heli: collective up; plane: pull up),
     * IN_BOOST = sprint / «Форсаж» (aircraft: down, like IN_DOWN), IN_DOWN = «Снижение» (heli: collective down; plane: push down).
     */
    public static final int IN_FWD = 1, IN_BACK = 2, IN_LEFT = 4, IN_RIGHT = 8, IN_BRAKE = 16, IN_BOOST = 32, IN_DOWN = 64, IN_MASK = 127;
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
        dataWatcher.updateObject(DW_CRUISE, Float.valueOf((float) (t.movement.flies ? FlightMath.cruiseCost(t, state.cargoFill(), posY) * ConfigSVA.consumption
                : VehicleMath.cost(t, t.topSpeed, state.cargoFill(), true, ConfigSVA.consumption))));
        dataWatcher.updateObject(DW_THR, Float.valueOf((float) throttle));
        dataWatcher.updateObject(DW_COST, Float.valueOf(costNow));
        dataWatcher.updateObject(DW_YAW, Float.valueOf(rotationYaw));
        dataWatcher.updateObject(DW_GAS, gasString(state));
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
        return new double[] {posX + w[0], posY, posZ + w[1]};
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
        // vehicles never go through portals
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
        boolean motorOn = riddenByEntity != null || debugDriver;
        if (!debugDriver && now - lastInputTick > INPUT_TIMEOUT) {
            input = 0;                                              // keys went stale: let go
        }
        boolean clientDriven = riddenByEntity instanceof EntityPlayerMP && now - lastMoveTick <= MOVE_TIMEOUT;
        // aircraft: also while the last tick ended in the air (a driver who got out mid-air - onGround is stale then)
        if (!clientDriven && (motorOn || Math.abs(speed) > 0.0005 || !onGround || ticksExisted < 3 || t.movement.flies && wasAirborne)) {
            physicsStep(t, motorOn ? input : 0);
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
        // lift gas (hover): 2 mB/s held up, 5 on boost - fractions kept like the core armour
        lifting = t.movement.lifts && hasLift() && (!t.movement.flies || canThrust());
        boosting = lifting && t.movement == Movement.HOVER && (input & IN_BOOST) != 0 && (input & IN_FWD) != 0;
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
            load = working ? FlightMath.load(t, base) : 0;
        } else {
            cost = VehicleMath.cost(t, v, fill, working, ConfigSVA.consumption);
            load = working ? VehicleMath.load(t, v, fill) : 0;
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
        boolean plane = t.movement == Movement.PLANE;
        if (clientDriven) {
            onGround = !air;                                        // the server's fallback physics starts from the truth
            if (plane) {
                throttle = PlaneMath.throttleStep(throttle, (input & IN_FWD) != 0, (input & IN_BACK) != 0, lifting);
            }
        }
        if (wasAirborne && !air && ticksExisted > 5) {
            double impact = Math.max(0, -Math.min(dy, lastDy));
            double dmg = plane ? PlaneMath.landingDamage(t.maxHp, impact, v, lifting) : HeliMath.landingDamage(t.maxHp, impact, !lifting);
            if (dmg > 0) {
                damageHull((float) dmg);
                worldObj.playSoundAtEntity(this, "random.anvil_land", 0.5F, 0.6F);
            }
        }
        boolean wasAuto = autoNow;
        autoNow = air && !lifting;
        stallNow = plane && air && PlaneMath.stalled(v);
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
        return HeliMath.cost(t, air, v, dy, fill, working);
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

    /** Engine may push: not wrecked, not overheated, not in deep water. */
    public boolean canThrust() {
        return !isWrecked() && !isHeatStopped() && (getVehicleType().movement == Movement.HOVER || !inDeepWater());
    }

    public boolean inDeepWater() {
        int x = MathHelper.floor_double(posX), y = MathHelper.floor_double(posY + 1.0), z = MathHelper.floor_double(posZ);
        return worldObj.getBlock(x, y, z).getMaterial().isLiquid();
    }

    /** One tick of ground physics from the input bits (driver client, or the server's fallback). */
    public void physicsStep(VehicleType t, int in) {
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
        boolean fwd = (in & IN_FWD) != 0, back = (in & IN_BACK) != 0, left = (in & IN_LEFT) != 0,
                right = (in & IN_RIGHT) != 0, brake = (in & IN_BRAKE) != 0;
        boolean thrust = canThrust();
        double top = VehicleMath.maxSpeed(t, getEnergy());
        boolean water = isInWater();
        if (water) {
            top *= WATER_SPEED;
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
        if (steer != 0 && speed != 0) {
            rotationYaw += steer * t.turnRate * Math.min(1, Math.abs(speed) / 0.12) * Math.signum(speed);
        }
        double r = Math.toRadians(rotationYaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        motionX = fx * speed;
        motionZ = fz * speed;
        motionY -= 0.08;
        motionY *= water ? 0.6 : 0.98;
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
        boolean flies = t.movement.flies;
        boolean hover = t.movement.lifts && !flies;
        boolean lift = t.movement.lifts && hasLift() && canThrust();
        double coast = Math.max(speedEma * 1.1, 0.02);
        double limit = hover ? (lift ? HoverMath.maxSpeed(t, true, (input & IN_BOOST) != 0) : coast)
                : canThrust() ? VehicleMath.maxSpeed(t, state.energy) : coast;      // coasting only
        // aircraft: reach, climbing only with power / airspeed, the ceiling, no hanging in the air unpowered (FlightMath)
        boolean ok = flies ? FlightMath.moveAllowed(t, h, dy, dt, y, lift, airborneAt(posX, posY, posZ), airborneAt(x, y, z), speedEma)
                : moveAllowed(h, dy, dt, limit);
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
            } else if (dy > 0.05 && worldObj.getCollidingBoundingBoxes(this, box.getOffsetBoundingBox(0, -0.6, 0)).isEmpty()) {
                ok = false;                                         // climbing with nothing under it
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

    /** Pure move check: horizontal reach, step / fall limits, no teleports. */
    public static boolean moveAllowed(double horizontal, double dy, long dt, double speedLimit) {
        return horizontal < 10 && horizontal <= speedLimit * 1.3 * dt + 0.2 && dy <= 1.1 * dt + 0.3 && dy >= -(4.0 * dt + 1);
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
        boolean heli = t.movement == Movement.HELI;
        boolean lift = isLifting(), auto = isAutorotating();
        float target = heli ? (lift ? 42F : auto ? 30F : 0F) : (lift ? 18F + 50F * (float) getThrottle() : auto ? 8F : 0F);
        rotorRate += MathHelper.clamp_float(target - rotorRate, -0.8F, 1.2F);
        rotorAngle = (rotorAngle + rotorRate) % 360F;
        double dx = posX - prevPosX, dy = posY - prevPosY, dz = posZ - prevPosZ;
        double r = Math.toRadians(rotationYaw);
        double fwd = dx * -Math.sin(r) + dz * Math.cos(r);
        float yawRate = MathHelper.wrapAngleTo180_float(rotationYaw - prevRotationYaw);
        boolean air = airborneAt(posX, posY, posZ);
        float tp, tr;
        if (heli) {
            tp = (float) (-fwd / t.topSpeed * 10);
            tr = air ? MathHelper.clamp_float(yawRate * 3F, -12F, 12F) : 0F;
        } else {
            tp = air ? (float) Math.toDegrees(Math.atan2(dy, Math.max(0.15, Math.abs(fwd)))) : 0F;
            tp = isStalling() ? -12F : MathHelper.clamp_float(tp, -20F, 20F);
            tr = air ? MathHelper.clamp_float(yawRate * 10F, -25F, 25F) : 0F;
        }
        renderPitch += (tp - renderPitch) * 0.2F;
        renderRoll += (tr - renderRoll) * 0.2F;
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

    @Override
    public int getSizeInventory() {
        return state.cargo.length;
    }

    @Override
    public ItemStack getStackInSlot(int i) {
        return i >= 0 && i < state.cargo.length ? state.cargo[i] : null;
    }

    @Override
    public ItemStack decrStackSize(int i, int n) {
        ItemStack s = getStackInSlot(i);
        if (s == null) {
            return null;
        }
        if (s.stackSize <= n) {
            state.cargo[i] = null;
            markDirty();
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
        if (i >= 0 && i < state.cargo.length) {
            state.cargo[i] = s;
            if (s != null && s.stackSize > getInventoryStackLimit()) {
                s.stackSize = getInventoryStackLimit();
            }
            markDirty();
        }
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
        return s == null || !(s.getItem() instanceof com.sva.item.ItemVehicleSVA);      // no vehicles inside vehicles
    }
}
