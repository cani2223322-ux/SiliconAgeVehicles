package com.sva.vehicle;

import java.util.UUID;

import com.sc.util.ArmorGasSC.Gas;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Everything a vehicle keeps between entity and item: type, EU, hull, heat, cargo, owner, id.
 * NBT keys are APPEND-ONLY.
 */
public class VehicleState {

    public static final String K_TYPE = "Type", K_ENERGY = "Energy", K_HP = "Hp", K_HEAT = "Heat", K_HEAT_STOP = "HeatStop",
            K_CARGO = "Cargo", K_OWNER = "OwnerUUID", K_OWNER_NAME = "OwnerName", K_ID = "VehId",
            /** Gas tanks: a compound of gas key -> mB and "frac_" + key -> the spent part below one mB. */
            K_GASES = "Gases",
            // stage C: the hitched trailer, the drill's head / module / mode / count, the auto-delivery route and its state
            K_TRAILER = "Trailer", K_HEAD = "DrillHead", K_MODULE = "DrillModule", K_DRILL_MODE = "DrillMode", K_DUG = "Dug",
            K_ROUTE_A = "RouteA", K_ROUTE_B = "RouteB", K_ROUTE_NEXT = "RouteNextB", K_AUTO_ON = "AutoOn", K_AUTO_STATE = "AutoState",
            // stage D: the tiltrotor's wanted mode (airplane) and its nacelles (0 vertical .. TiltMath.TRANSITION_TICKS airplane)
            K_TILT_PLANE = "TiltPlane", K_TILT_STEP = "TiltStep",
            // stage E: long-range mode switched on; the ship's jump target «дом», its cooldown (ticks), anchor, invisibility
            K_LONG_RANGE = "LongRange", K_JUMP_HOME = "JumpHome", K_JUMP_CD = "JumpCooldown", K_ANCHOR = "Anchor", K_INVIS = "Invisible",
            // cargo screen v2: torches the drill has put down
            K_TORCHES = "Torches",
            // wave 2: the tuning slots (TuningMath kinds, -1 empty) and the access list / lock (AccessMath)
            K_TUNING = "Tuning", K_ACCESS = "Access";

    public VehicleType type;
    public double energy;
    public float hp;
    public double heat;
    public boolean heatStop;
    public ItemStack[] cargo;
    public String ownerId = "";
    public String ownerName = "";
    public String vehId;
    /** mB of each gas (by Gas ordinal) and the part below a whole mB already spent (like the core's GasFrac_). */
    public final int[] gas = new int[Gas.values().length];
    public final float[] gasFrac = new float[Gas.values().length];
    /** A trailer is hitched (types with trailerSlots): the cargo is cargoSlots + trailerSlots long, the trailer's part last. */
    public boolean trailer;
    /** Drill vehicle: the core quarry drill head (ItemDrillHeadSC, wears) and the bore module («Расширитель бура»), or null. */
    public ItemStack drillHead, drillModule;
    /** Drill vehicle: DrillMath.MODE_*; blocks dug so far. */
    public int drillMode;
    public long dug;
    /** Drill vehicle: torches it has put down (the cargo screen's «Бур» tab). */
    public int torches;
    /** Auto-delivery (the crawler): points A / B as {x, y, z, dim} or null; the next coordinator click sets B; on / state. */
    public int[] routeA, routeB;
    public boolean routeNextB, autoOn;
    public int autoState;
    /** Tiltrotor: the mode it is turning to / in (true: airplane) and the nacelles, 0 (vertical) .. TRANSITION_TICKS (airplane). */
    public boolean tiltPlane;
    public int tiltStep;
    /** Stage E: «Дальний режим» switched on (it works only high and fast - SpaceMath.longRangeActive). */
    public boolean longRange;
    /** Singular Ship: jump «домой» (else to the Coordinator in the cargo), the jump's cooldown (ticks), anchor, invisibility. */
    public boolean jumpHome, anchor, invisible;
    public int jumpCooldown;
    /** Wave 2 (★8): the installed upgrades, MAX_SLOTS kinds (-1 = empty slot); changed only in the garage. */
    public int[] tuning = TuningMath.empty();
    /** Wave 2 (★10): who may drive / ride / open the cargo / repair, the lock mode and its version (key cards). */
    public final AccessMath.Access access = new AccessMath.Access();

    public VehicleState(VehicleType type) {
        this.type = type;
        this.hp = type.maxHp;
        this.cargo = new ItemStack[type.cargoSlots];
        this.vehId = UUID.randomUUID().toString();
    }

    /** Cargo slots now (a hitched trailer adds its own). */
    public int cargoSize() {
        return type.cargoSlots(trailer);
    }

    /**
     * Hitches / unhitches the trailer: the cargo array grows by the trailer's slots (empty) or shrinks to the body's -
     * the caller takes the trailer's items out first (they are dropped from the array here).
     */
    public void setTrailer(boolean on) {
        trailer = on && type.trailerSlots > 0;
        ItemStack[] n = new ItemStack[cargoSize()];
        System.arraycopy(cargo, 0, n, 0, Math.min(cargo.length, n.length));
        cargo = n;
    }

    public double maxEnergy() {
        return type.buffer * (com.sva.ConfigSVA.tuning ? TuningMath.bufferMul(tuning) : 1);
    }

    /** Max hull HP (the type's; «Лёгкий корпус» -20 %). */
    public float maxHp() {
        return (float) (type.maxHp * (com.sva.ConfigSVA.tuning ? TuningMath.hpMul(tuning) : 1));
    }

    /**
     * Wave 2: new upgrades in (already checked by TuningMath.canInstall / sanitize); the charge, hull and gases are cut
     * to the new limits (taking out «Аккумулятор+» or «Доп. бак» loses what no longer fits).
     */
    public void setTuning(int[] slots) {
        tuning = TuningMath.sanitize(type, slots);
        energy = Math.min(energy, maxEnergy());
        hp = Math.min(hp, maxHp());
        for (Gas g : Gas.values()) {
            gas[g.ordinal()] = Math.min(gas[g.ordinal()], gasCapacity(g));
        }
    }

    /** @return EU taken */
    public double addEnergy(double eu) {
        double room = Math.max(0, maxEnergy() - energy);
        double got = Math.max(0, Math.min(room, eu));
        energy += got;
        return got;
    }

    // ------------------------------------------------------------------ gas tanks

    public int gasCapacity(Gas g) {
        int c = type.gases.capacity(g);
        return com.sva.ConfigSVA.tuning ? (int) Math.round(c * TuningMath.gasMul(tuning)) : c;
    }

    public int gas(Gas g) {
        return g == null ? 0 : Math.max(0, Math.min(gasCapacity(g), gas[g.ordinal()]));
    }

    /** Puts up to `mb` in; @return how much went (or would go) in. */
    public int fillGas(Gas g, int mb, boolean simulate) {
        if (g == null) {
            return 0;
        }
        int put = Math.max(0, Math.min(gasCapacity(g) - gas(g), mb));
        if (!simulate && put > 0) {
            gas[g.ordinal()] = gas(g) + put;
        }
        return put;
    }

    /** Takes up to `mb` out; @return how much came (or would come) out. */
    public int drainGas(Gas g, int mb, boolean simulate) {
        if (g == null) {
            return 0;
        }
        int took = Math.max(0, Math.min(gas(g), mb));
        if (!simulate && took > 0) {
            gas[g.ordinal()] = gas(g) - took;
            if (gas[g.ordinal()] == 0) {
                gasFrac[g.ordinal()] = 0;
            }
        }
        return took;
    }

    /**
     * Spends a fraction of a mB (the core armour's drainFraction): the part below a whole mB is kept until it adds
     * up. @return whole mB taken now. An empty tank keeps no fraction.
     */
    public int drainGasFraction(Gas g, double mb) {
        if (g == null || mb <= 0 || Double.isNaN(mb) || gas(g) <= 0) {
            return 0;
        }
        int i = g.ordinal();
        double frac = gasFrac[i] + mb;
        int whole = (int) Math.min(Integer.MAX_VALUE, frac);
        int took = whole > 0 ? drainGas(g, whole, false) : 0;
        gasFrac[i] = gas(g) > 0 ? (float) (frac - whole) : 0F;
        return took;
    }

    /** Fills every tank (creative "full gases" stacks, tests). */
    public void fillAllGases() {
        for (Gas g : type.gases.gases()) {
            gas[g.ordinal()] = gasCapacity(g);
        }
    }

    public int cargoUsed() {
        int n = 0;
        for (ItemStack s : cargo) {
            if (s != null) {
                n++;
            }
        }
        return n;
    }

    /** 0..1: item count against the stack limits of the slots. */
    public double cargoFill() {
        double sum = 0;
        for (ItemStack s : cargo) {
            if (s != null && s.getMaxStackSize() > 0) {
                sum += Math.min(1.0, s.stackSize / (double) s.getMaxStackSize());
            }
        }
        return cargo.length == 0 ? 0 : sum / cargo.length;
    }

    public boolean isOwner(UUID id) {
        return id != null && id.toString().equals(ownerId);
    }

    public void writeNBT(NBTTagCompound t) {
        t.setInteger(K_TYPE, type.ordinal());
        t.setDouble(K_ENERGY, energy);
        t.setFloat(K_HP, hp);
        t.setDouble(K_HEAT, heat);
        t.setBoolean(K_HEAT_STOP, heatStop);
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < cargo.length; i++) {
            if (cargo[i] != null) {
                NBTTagCompound s = new NBTTagCompound();
                s.setByte("Slot", (byte) i);
                cargo[i].writeToNBT(s);
                list.appendTag(s);
            }
        }
        t.setTag(K_CARGO, list);
        t.setString(K_OWNER, ownerId == null ? "" : ownerId);
        t.setString(K_OWNER_NAME, ownerName == null ? "" : ownerName);
        t.setString(K_ID, vehId);
        NBTTagCompound gt = new NBTTagCompound();
        for (Gas g : Gas.values()) {
            if (gas[g.ordinal()] > 0) {
                gt.setInteger(g.key(), gas[g.ordinal()]);
                if (gasFrac[g.ordinal()] > 0) {
                    gt.setFloat("frac_" + g.key(), gasFrac[g.ordinal()]);
                }
            }
        }
        t.setTag(K_GASES, gt);
        t.setBoolean(K_TRAILER, trailer);
        if (drillHead != null) {
            t.setTag(K_HEAD, drillHead.writeToNBT(new NBTTagCompound()));
        }
        if (drillModule != null) {
            t.setTag(K_MODULE, drillModule.writeToNBT(new NBTTagCompound()));
        }
        t.setInteger(K_DRILL_MODE, drillMode);
        t.setLong(K_DUG, dug);
        t.setInteger(K_TORCHES, torches);
        if (routeA != null) {
            t.setIntArray(K_ROUTE_A, routeA);
        }
        if (routeB != null) {
            t.setIntArray(K_ROUTE_B, routeB);
        }
        t.setBoolean(K_ROUTE_NEXT, routeNextB);
        t.setBoolean(K_AUTO_ON, autoOn);
        t.setInteger(K_AUTO_STATE, autoState);
        t.setBoolean(K_TILT_PLANE, tiltPlane);
        t.setInteger(K_TILT_STEP, tiltStep);
        t.setBoolean(K_LONG_RANGE, longRange);
        t.setBoolean(K_JUMP_HOME, jumpHome);
        t.setInteger(K_JUMP_CD, jumpCooldown);
        t.setBoolean(K_ANCHOR, anchor);
        t.setBoolean(K_INVIS, invisible);
        t.setIntArray(K_TUNING, tuning);
        NBTTagCompound a = new NBTTagCompound();
        access.write(a);
        t.setTag(K_ACCESS, a);
    }

    /** Tiltrotor nacelles 0 (vertical) .. 1 (airplane). */
    public double tiltPos() {
        return tiltStep / (double) TiltMath.TRANSITION_TICKS;
    }

    /** Reads into this state; a missing key keeps the current value (a fresh item: full hull, empty). */
    public void readNBT(NBTTagCompound t) {
        if (t.hasKey(K_TYPE)) {
            type = VehicleType.byOrdinal(t.getInteger(K_TYPE));
        }
        // wave 2: the upgrades first - they set the limits the charge / hull / gases are clamped to below
        tuning = TuningMath.sanitize(type, t.hasKey(K_TUNING) ? t.getIntArray(K_TUNING) : tuning);
        if (t.hasKey(K_ACCESS)) {
            access.read(t.getCompoundTag(K_ACCESS));
        } else if (t.hasKey(K_ID)) {
            access.legacy();                                    // saved before wave 2: keep the old rules
        }
        energy = clampNum(t.hasKey(K_ENERGY) ? t.getDouble(K_ENERGY) : energy, 0, maxEnergy());
        hp = (float) clampNum(t.hasKey(K_HP) ? t.getFloat(K_HP) : hp, 0, maxHp());
        heat = clampNum(t.hasKey(K_HEAT) ? t.getDouble(K_HEAT) : heat, 0, VehicleMath.HEAT_STOP);
        heatStop = t.getBoolean(K_HEAT_STOP);
        trailer = t.getBoolean(K_TRAILER) && type.trailerSlots > 0;
        cargo = new ItemStack[cargoSize()];
        NBTTagList list = t.getTagList(K_CARGO, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound s = list.getCompoundTagAt(i);
            int slot = s.getByte("Slot") & 0xFF;
            if (slot < cargo.length) {
                cargo[slot] = ItemStack.loadItemStackFromNBT(s);
            }
        }
        if (t.hasKey(K_OWNER)) {
            ownerId = t.getString(K_OWNER);
            ownerName = t.getString(K_OWNER_NAME);
        }
        if (t.hasKey(K_ID) && t.getString(K_ID).length() > 0) {
            vehId = t.getString(K_ID);
        }
        if (t.hasKey(K_GASES)) {
            NBTTagCompound gt = t.getCompoundTag(K_GASES);
            for (Gas g : Gas.values()) {
                int i = g.ordinal();
                gas[i] = Math.max(0, Math.min(gasCapacity(g), gt.getInteger(g.key())));
                float f = gt.getFloat("frac_" + g.key());
                gasFrac[i] = gas[i] > 0 && f > 0 && f < 1 ? f : 0F;
            }
        }
        drillHead = t.hasKey(K_HEAD) ? ItemStack.loadItemStackFromNBT(t.getCompoundTag(K_HEAD)) : null;
        drillModule = t.hasKey(K_MODULE) ? ItemStack.loadItemStackFromNBT(t.getCompoundTag(K_MODULE)) : null;
        drillMode = Math.max(0, Math.min(DrillMath.MODES - 1, t.getInteger(K_DRILL_MODE)));
        dug = Math.max(0, t.getLong(K_DUG));
        torches = Math.max(0, t.getInteger(K_TORCHES));
        routeA = point(t.getIntArray(K_ROUTE_A));
        routeB = point(t.getIntArray(K_ROUTE_B));
        routeNextB = t.getBoolean(K_ROUTE_NEXT);
        autoOn = t.getBoolean(K_AUTO_ON) && routeA != null && routeB != null;
        autoState = autoOn ? Math.max(0, Math.min(AutoDelivery.STATES - 1, t.getInteger(K_AUTO_STATE))) : AutoDelivery.OFF;
        boolean tilt = type.movement == Movement.TILT;
        tiltPlane = tilt && t.getBoolean(K_TILT_PLANE);
        tiltStep = tilt ? Math.max(0, Math.min(TiltMath.TRANSITION_TICKS, t.getInteger(K_TILT_STEP))) : 0;
        longRange = type.longRange && t.getBoolean(K_LONG_RANGE);
        jumpHome = type.singular && t.getBoolean(K_JUMP_HOME);
        jumpCooldown = type.singular ? Math.max(0, Math.min(SpaceMath.JUMP_COOLDOWN, t.getInteger(K_JUMP_CD))) : 0;
        anchor = type.singular && t.getBoolean(K_ANCHOR);
        invisible = type.singular && t.getBoolean(K_INVIS);
    }

    private static int[] point(int[] a) {
        return a != null && a.length == 4 ? a : null;
    }

    private static double clampNum(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : VehicleMath.clamp(v, lo, hi);
    }
}
