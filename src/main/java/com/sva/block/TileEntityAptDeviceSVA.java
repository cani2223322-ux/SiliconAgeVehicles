package com.sva.block;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;

/**
 * Airport wave B1: the tile of an airport device (block.BlockAptDeviceSVA: a marker beacon, an RVR transmissometer, a
 * weather station, a windsock) - it keeps nothing (the kind is the metadata; what it measures lives in the save's airport
 * list, AirportDataSVA) and does not tick: placed it puts itself into the list, broken it takes itself out. It exists for
 * its model (client.RenderAptSVA: the windsock and the anemometer animate with the wind field).
 */
public class TileEntityAptDeviceSVA extends TileEntity {

    @Override
    public boolean canUpdate() {
        return false;
    }

    public int kind() {
        return worldObj == null ? 0 : Math.max(0, Math.min(AirportDataSVA.KINDS - 1, getBlockMetadata()));
    }

    /** Server: into the save's airport list. */
    public void register() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        AirportDataSVA d = AirportDataSVA.get(worldObj);
        if (d == null) {
            return;
        }
        AirportDataSVA.Device dev = new AirportDataSVA.Device();
        dev.key = AirportDataSVA.key(worldObj, xCoord, yCoord, zCoord);
        dev.dim = worldObj.provider.dimensionId;
        dev.kind = kind();
        dev.x = xCoord + 0.5;
        dev.y = yCoord;
        dev.z = zCoord + 0.5;
        d.putDevice(dev);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return 192.0 * 192.0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return AxisAlignedBB.getBoundingBox(xCoord - 1, yCoord, zCoord - 1, xCoord + 2, yCoord + 4, zCoord + 2);
    }
}
