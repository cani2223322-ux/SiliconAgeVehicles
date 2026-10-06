package com.sva.block;

import java.util.List;

import com.sc.energy.TileEntityEnergyBase;
import com.sc.energy.Tier;
import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.VehicleMath;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.util.AxisAlignedBB;

/**
 * Vehicle charging station: a core energy tile (IC2 net / the core's own net, overvoltage rules included),
 * input tier = its tier. Once a second it looks for a vehicle touching its top; each tick it hands that vehicle
 * up to voltage x chargerRate EU from the buffer.
 */
public class TileEntityChargerSVA extends TileEntityEnergyBase {

    public static final int SCAN_EVERY = 20, ACTIVE_HOLD = 10;

    private EntityVehicleSVA target;
    private int scan;
    private int sinceCharge = 1000;
    private boolean active;

    public TileEntityChargerSVA() {
        super(Tier.MV);
    }

    public TileEntityChargerSVA(Tier tier) {
        super(tier);
    }

    public boolean isActive() {
        return active;
    }

    /** EU a tick at most. */
    public int rate() {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(getTier().getVoltage() * (double) ConfigSVA.chargerRate)));
    }

    public AxisAlignedBB topZone() {
        return AxisAlignedBB.getBoundingBox(xCoord, yCoord + 1, zCoord, xCoord + 1, yCoord + 1.25, zCoord + 1);
    }

    private boolean touches(EntityVehicleSVA v) {
        return v != null && !v.isDead && v.worldObj == worldObj && v.boundingBox.intersectsWith(topZone());
    }

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        if (--scan <= 0) {
            scan = SCAN_EVERY;
            target = null;
            List list = worldObj.getEntitiesWithinAABB(EntityVehicleSVA.class, topZone());
            for (Object o : list) {
                EntityVehicleSVA v = (EntityVehicleSVA) o;
                if (!v.isDead && v.energyRoom() >= 1) {
                    target = v;
                    break;
                }
            }
        }
        int moved = 0;
        if (target != null && touches(target) && switchedOn()) {
            moved = VehicleMath.chargeTransfer(getEnergyStored(), rate(), target.energyRoom());
            if (moved > 0) {
                removeEnergy(moved);
                target.addEnergy(moved);
            }
        }
        sinceCharge = moved > 0 ? 0 : Math.min(1000, sinceCharge + 1);
        boolean now = sinceCharge < ACTIVE_HOLD;
        if (now != active) {
            active = now;
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /** World test / commands: fill the buffer. */
    public void fillForTest(int eu) {
        addEnergy(eu);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        active = nbt.getBoolean("ChargerActive");
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean("ChargerActive", active);
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        t.setBoolean("ChargerActive", active);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        boolean was = active;
        active = pkt.func_148857_g().getBoolean("ChargerActive");
        if (was != active && worldObj != null) {
            worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
        }
    }
}
