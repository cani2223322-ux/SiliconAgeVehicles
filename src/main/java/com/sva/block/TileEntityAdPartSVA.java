package com.sva.block;

import com.sc.energy.Tier;
import com.sc.energy.TileEntityEnergyBase;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Multiblock wave: a part (filler) of an air-defence structure - it knows where its master is (an offset, so the master
 * may sit across a chunk border) and passes on what touches it. Energy: it is an energy sink on our net and IC2's like any
 * machine, but its demand, its voltage and every EU it is given are the master's (a cable on any block of the structure
 * feeds it; a too strong line blows up the master - the structure goes with it). No ticking of its own.
 */
public class TileEntityAdPartSVA extends TileEntityEnergyBase {

    /** Offset to the master; `linked` false until BlockAirDefenseSVA.buildParts has set it. */
    private int dx, dy, dz;
    private boolean linked;

    public TileEntityAdPartSVA() {
        super();
    }

    public void link(int mx, int my, int mz, Tier tier) {
        dx = mx - xCoord;
        dy = my - yCoord;
        dz = mz - zCoord;
        linked = true;
        if (tier != null) {
            setTier(tier);
        }
        markDirty();
    }

    public boolean isLinked() {
        return linked;
    }

    public int masterX() {
        return xCoord + dx;
    }

    public int masterY() {
        return yCoord + dy;
    }

    public int masterZ() {
        return zCoord + dz;
    }

    /** The master's tile if its chunk is loaded and it is still there, else null. */
    public TileEntityAirDefenseSVA master() {
        if (!linked || worldObj == null) {
            return null;
        }
        int x = masterX(), y = masterY(), z = masterZ();
        if (y < 0 || y > 255 || !worldObj.blockExists(x, y, z)) {
            return null;
        }
        TileEntity te = worldObj.getTileEntity(x, y, z);
        return te instanceof TileEntityAirDefenseSVA && !te.isInvalid() ? (TileEntityAirDefenseSVA) te : null;
    }

    /**
     * The master is gone for good (its chunk loaded, the block not an air-defence master any more): the part is an orphan
     * and removes itself (BlockAdPartSVA). Unlinked parts (being built) and unloaded masters are not orphans.
     */
    public boolean orphan() {
        if (!linked || worldObj == null) {
            return false;
        }
        int x = masterX(), y = masterY(), z = masterZ();
        if (y < 0 || y > 255) {
            return true;
        }
        if (!worldObj.blockExists(x, y, z)) {
            return false;
        }
        return !(worldObj.getTileEntity(x, y, z) instanceof TileEntityAirDefenseSVA);
    }

    @Override
    public boolean canUpdate() {
        return false;
    }

    // ------------------------------------------------------------------ energy: all the master's

    @Override
    public Tier inputTier() {
        TileEntityAirDefenseSVA m = master();
        return m != null ? m.inputTier() : super.inputTier();
    }

    @Override
    public int demandedEnergy() {
        TileEntityAirDefenseSVA m = master();
        return m != null ? m.demandedEnergy() : 0;
    }

    @Override
    public int receiveEnergy(ForgeDirection from, int voltage, int amount, boolean simulate) {
        TileEntityAirDefenseSVA m = master();
        return m != null ? m.receiveEnergy(ForgeDirection.UNKNOWN, voltage, amount, simulate) : 0;
    }

    @Override
    public int getEnergyStored() {
        TileEntityAirDefenseSVA m = master();
        return m != null ? m.getEnergyStored() : 0;
    }

    @Override
    public int getMaxEnergyStored() {
        TileEntityAirDefenseSVA m = master();
        return m != null ? m.getMaxEnergyStored() : super.getMaxEnergyStored();
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        super.readFromNBT(t);
        dx = t.getInteger("AdMx");
        dy = t.getInteger("AdMy");
        dz = t.getInteger("AdMz");
        linked = t.getBoolean("AdLinked");
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        super.writeToNBT(t);
        t.setInteger("AdMx", dx);
        t.setInteger("AdMy", dy);
        t.setInteger("AdMz", dz);
        t.setBoolean("AdLinked", linked);
    }
}
