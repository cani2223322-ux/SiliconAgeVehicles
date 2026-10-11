package com.sva.block;

import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.RunwayMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;

/**
 * Stage D1: the tile of the «Огни ПАПИ» and «Огни приближения» blocks - it keeps nothing (their settings are the
 * metadata) and does not tick; on the client it only caches what its renderer (client.RenderAirfieldSVA) needs, looked up
 * again every REFRESH ticks (staggered by position): the runway strip it belongs to (AirfieldSVA.near), for a PAPI unit
 * the landing direction it serves and its index in the row (units between it and the runway), for an approach pole the
 * direction and its distance before the threshold; and the redstone rule's state (AirfieldMath.manual).
 */
public class TileEntityAirfieldSVA extends TileEntity {

    /** Ticks between the client's look-ups. */
    public static final int REFRESH = 100, MANUAL_REFRESH = 10;
    /** A PAPI unit looks for its runway this far, an approach pole that far (blocks). */
    public static final int PAPI_SCAN = AirfieldMath.PAPI_BAND + 6, POLE_SCAN = AirfieldMath.APPROACH_MAX;

    public RunwayMath.Runway runway;
    /** The landing direction served (+1 / -1; 0 = no runway), a PAPI unit's index, an approach pole's distance before the threshold. */
    public int dir, index;
    public double dist;
    /** AirfieldMath.AUTO / OFF / ON. */
    public int manual = AirfieldMath.AUTO;
    private long at = Long.MIN_VALUE, manualAt = Long.MIN_VALUE;

    @Override
    public boolean canUpdate() {
        return false;
    }

    /** Client: the cached look-ups, refreshed when due. */
    @SideOnly(Side.CLIENT)
    public void refresh() {
        if (worldObj == null) {
            return;
        }
        long now = worldObj.getTotalWorldTime();
        if (now - manualAt >= MANUAL_REFRESH || now < manualAt) {
            manualAt = now;
            manual = AirfieldSVA.manual(worldObj, xCoord, yCoord, zCoord);
        }
        int stagger = (xCoord * 31 + zCoord * 17) & 31;
        if (now - at < REFRESH + stagger && now >= at) {
            return;
        }
        at = now;
        Block b = getBlockType();
        boolean papi = b instanceof BlockPapiSVA;
        double cx = xCoord + 0.5, cz = zCoord + 0.5;
        RunwayMath.Runway r = AirfieldSVA.near(worldObj, cx, cz, papi ? PAPI_SCAN : POLE_SCAN);
        runway = null;
        dir = 0;
        index = 0;
        dist = 0;
        if (r == null) {
            return;
        }
        if (papi) {
            double a = r.alongX ? cx : cz, k = r.alongX ? cz : cx;
            double out = Math.abs(k - r.c) - r.width / 2.0;
            if (a < r.a0 || a > r.a1 || out < 0 || out > AirfieldMath.PAPI_BAND + 1) {
                return;
            }
            runway = r;
            dir = a - r.a0 <= r.a1 - a ? 1 : -1;
            // the units between this one and the runway's edge, in the same row (same position along, same level)
            int step = k < r.c ? 1 : -1, n = 0;
            for (int j = 1; j < (int) Math.ceil(out) + 1 && n < AirfieldMath.UNITS; j++) {
                int x = r.alongX ? xCoord : xCoord + step * j, z = r.alongX ? zCoord + step * j : zCoord;
                if (worldObj.getBlock(x, yCoord, z) instanceof BlockPapiSVA) {
                    n++;
                }
            }
            index = AirfieldMath.unitIndex(n);
        } else {
            double[] ap = AirfieldMath.approach(r, cx, cz);
            if (ap == null) {
                return;
            }
            runway = r;
            dir = (int) ap[0];
            dist = ap[1];
        }
    }

    /** Client: the next look-up happens on the next frame (a neighbour changed). */
    public void invalidateCache() {
        at = Long.MIN_VALUE;
        manualAt = Long.MIN_VALUE;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return 256.0 * 256.0;                               // seen from the approach, far out
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return AxisAlignedBB.getBoundingBox(xCoord - 1.5, yCoord - 0.5, zCoord - 1.5, xCoord + 2.5, yCoord + 2.5, zCoord + 2.5);
    }
}
