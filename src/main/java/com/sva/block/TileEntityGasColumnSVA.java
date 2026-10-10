package com.sva.block;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.sc.util.ArmorGasSC.Gas;
import com.sva.entity.EntityVehicleSVA;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

/**
 * «Газовая колонка» (plan §3, HV): one inner tank per core gas (TANK mB each), filled by the core's pipes / any
 * IFluidHandler from every side or by hand (a full cell / bucket); nothing drains it from outside.
 * Refuelling is automatic: every SCAN_EVERY ticks it looks for vehicles within RADIUS blocks that are standing
 * still (not driving past) and belong to the column's owner (a column without an owner serves everyone), and
 * moves up to RATE mB a tick of each gas they have a tank for. No EU needed - it is a pressure-fed pump.
 * ТБ-9: a vehicle that a charging station with the gas module is fuelling from this column right now is skipped -
 * the station already pours RATE mB a tick into it, so it never gets twice the rate.
 */
public class TileEntityGasColumnSVA extends TileEntity implements IFluidHandler, KeptDataSVA.Keeper {

    public static final int TANK = 16000, RATE = 25, SCAN_EVERY = 10;
    public static final double RADIUS = 4;
    public static final String K_TANKS = "ColumnTanks", K_OWNER = "Owner", K_OWNER_NAME = "OwnerName";

    /** ТБ-9: vehicles a station's gas module fuels (server), with the world time of the last tick it did. */
    private static final Map<EntityVehicleSVA, Long> STATION_FED = new WeakHashMap<EntityVehicleSVA, Long>();

    private final FluidTank[] tanks = new FluidTank[Gas.values().length];
    private String ownerId = "", ownerName = "";
    private int scan;
    /** mB moved into vehicles in the last scan (status, tests). */
    private int lastMoved;

    public TileEntityGasColumnSVA() {
        for (int i = 0; i < tanks.length; i++) {
            tanks[i] = new FluidTank(TANK);
        }
    }

    public void setOwner(String id, String name) {
        ownerId = id == null ? "" : id;
        ownerName = name == null ? "" : name;
        markDirty();
    }

    public String getOwnerName() {
        return ownerName;
    }

    public int amount(Gas g) {
        return tanks[g.ordinal()].getFluidAmount();
    }

    public int getLastMoved() {
        return lastMoved;
    }

    /** Puts up to `mb` of `g` in. @return mB that went (or would go) in */
    public int fillGas(Gas g, int mb, boolean doFill) {
        Fluid f = g == null ? null : g.fluidOf();
        if (f == null || mb <= 0) {
            return 0;
        }
        int n = tanks[g.ordinal()].fill(new FluidStack(f, mb), doFill);
        if (doFill && n > 0) {
            markDirty();
        }
        return n;
    }

    /** Takes up to `mb` of `g` out (a charging station's boost cooling: helium). @return mB taken */
    public int takeGas(Gas g, int mb) {
        if (g == null || mb <= 0) {
            return 0;
        }
        FluidStack out = tanks[g.ordinal()].drain(mb, true);
        int n = out == null ? 0 : out.amount;
        if (n > 0) {
            markDirty();
        }
        return n;
    }

    /** ТБ-9, the station (server): its gas module fuels this vehicle now. */
    public static void stationFeeds(EntityVehicleSVA v, long worldTime) {
        if (v != null) {
            STATION_FED.put(v, Long.valueOf(worldTime));
        }
    }

    /** ТБ-9: a station's gas module fuelled it within the last scan (columns leave it to the station). */
    public static boolean fedByStation(EntityVehicleSVA v, long worldTime) {
        Long at = v == null ? null : STATION_FED.get(v);
        return at != null && worldTime - at.longValue() <= SCAN_EVERY && worldTime >= at.longValue();
    }

    /** May this column serve the vehicle (its owner's, or the column has none)? */
    public boolean serves(EntityVehicleSVA v) {
        return ownerId.length() == 0 || ownerId.equals(v.state.ownerId);
    }

    public AxisAlignedBB zone() {
        return AxisAlignedBB.getBoundingBox(xCoord + 0.5 - RADIUS, yCoord - 2, zCoord + 0.5 - RADIUS,
                xCoord + 0.5 + RADIUS, yCoord + 4, zCoord + 0.5 + RADIUS);
    }

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote || --scan > 0) {
            return;
        }
        scan = SCAN_EVERY;
        lastMoved = 0;
        List list = worldObj.getEntitiesWithinAABB(EntityVehicleSVA.class, zone());
        long now = worldObj.getTotalWorldTime();
        for (Object o : list) {
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            if (v.isDead || !v.state.type.gases.any() || !serves(v) || Math.abs(v.speed) > 0.05 || fedByStation(v, now)) {
                continue;
            }
            for (Gas g : v.state.type.gases.gases()) {
                lastMoved += refuel(v, g, RATE * SCAN_EVERY);
            }
        }
        if (lastMoved > 0) {
            worldObj.playSoundEffect(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, "random.fizz", 0.2F, 1.6F);
        }
    }

    /** Moves up to `max` mB of `g` into the vehicle. @return mB moved */
    public int refuel(EntityVehicleSVA v, Gas g, int max) {
        int n = Math.min(Math.min(max, amount(g)), v.gasRoom(g));
        if (n <= 0) {
            return 0;
        }
        FluidStack out = tanks[g.ordinal()].drain(n, true);
        int got = out == null ? 0 : out.amount;
        int put = v.addGas(g, got);
        if (put < got) {
            fillGas(g, got - put, true);                    // never lost
        }
        markDirty();
        return put;
    }

    // ------------------------------------------------------------------ IFluidHandler: pipes push in, nothing drains

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        Gas g = resource == null ? null : Gas.of(resource.getFluid());
        return g == null ? 0 : fillGas(g, resource.amount, doFill);
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        return null;
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        return null;
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        return Gas.of(fluid) != null;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        return false;
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        FluidTankInfo[] out = new FluidTankInfo[tanks.length];
        for (int i = 0; i < tanks.length; i++) {
            out[i] = tanks[i].getInfo();
        }
        return out;
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        readTanks(nbt.getCompoundTag(K_TANKS));
        ownerId = nbt.getString(K_OWNER);
        ownerName = nbt.getString(K_OWNER_NAME);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag(K_TANKS, tanksTag());
        nbt.setString(K_OWNER, ownerId);
        nbt.setString(K_OWNER_NAME, ownerName);
    }

    private void readTanks(NBTTagCompound t) {
        for (Gas g : Gas.values()) {
            Fluid f = g.fluidOf();
            int mb = Math.max(0, Math.min(TANK, t.getInteger(g.key())));
            tanks[g.ordinal()].setFluid(f == null || mb <= 0 ? null : new FluidStack(f, mb));
        }
    }

    private NBTTagCompound tanksTag() {
        NBTTagCompound t = new NBTTagCompound();
        for (Gas g : Gas.values()) {
            if (amount(g) > 0) {
                t.setInteger(g.key(), amount(g));
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ ТБ-1: the item keeps the tanks (not the owner)

    @Override
    public void writeKept(NBTTagCompound t) {
        NBTTagCompound tanksTag = tanksTag();
        if (!tanksTag.hasNoTags()) {
            t.setTag(K_TANKS, tanksTag);
        }
    }

    @Override
    public void readKept(NBTTagCompound t) {
        if (t.hasKey(K_TANKS)) {
            readTanks(t.getCompoundTag(K_TANKS));
        }
    }
}
