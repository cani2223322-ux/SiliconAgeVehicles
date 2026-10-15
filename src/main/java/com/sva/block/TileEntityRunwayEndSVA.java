package com.sva.block;

import com.sva.airport.IlsPairing;
import com.sva.airport.RunwayLayout;
import com.sva.vehicle.LinerIls;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

/**
 * Airport wave B1 (plan-next §9, items 24-25): the «Торец полосы» marker's settings - the facing (the compass course down
 * the runway, from the placer's look; «по взгляду» in its screen), the runway's width (3..9; the first scan of the runway
 * blocks ahead suggests it), the lights' mode of this end (RunwayLayout: AUTO or a step 0..5 - until the wave B2 tower,
 * this screen is the lights' control) and the approach lights (ALS) before it; the runway surface's Y. Into the save's
 * airport list (AirportDataSVA - two markers facing each other make a runway: IlsPairing).
 * <p>
 * Airport wave B2: a new marker's lights are «ВЫШКА» (RunwayLayout.LIGHTS_TOWER: its tower's steps; without a tower AUTO);
 * AUTO or a step set here is a local override (the tower's screen can set them all back).
 */
public class TileEntityRunwayEndSVA extends TileEntity {

    public double facing;
    public int width = 5, lights = RunwayLayout.LIGHTS_TOWER, als;
    public double elev;
    public boolean ready;

    @Override
    public boolean canUpdate() {
        return false;
    }

    public String key() {
        return AirportDataSVA.key(worldObj, xCoord, yCoord, zCoord);
    }

    /** Server: into the save's airport list (placed / set). */
    public void register() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        AirportDataSVA d = AirportDataSVA.get(worldObj);
        if (d != null) {
            IlsPairing.End e = new IlsPairing.End(key(), worldObj.provider.dimensionId, xCoord + 0.5, zCoord + 0.5, elev, facing, width);
            e.lights = lights;
            e.als = als;
            d.putEnd(e);
        }
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /** Server: the screen's values (sanitised). */
    public void set(double course, int newWidth, int newLights, int newAls) {
        if (!Double.isNaN(course)) {
            facing = LinerIls.wrap360(Math.round(course));
        }
        width = Math.max(IlsPairing.WIDTH_MIN, Math.min(IlsPairing.WIDTH_MAX, newWidth));
        lights = Math.max(RunwayLayout.LIGHTS_TOWER, Math.min(RunwayLayout.STEPS, newLights));
        als = newAls != 0 ? 1 : 0;
        ready = true;
        register();
    }

    private void writeCommon(NBTTagCompound t) {
        t.setDouble("Facing", facing);
        t.setInteger("Width", width);
        t.setInteger("Lights", lights);
        t.setInteger("Als", als);
        t.setDouble("Elev", elev);
        t.setBoolean("Ready", ready);
    }

    private void readCommon(NBTTagCompound t) {
        facing = LinerIls.wrap360(t.getDouble("Facing"));
        width = t.hasKey("Width") ? Math.max(IlsPairing.WIDTH_MIN, Math.min(IlsPairing.WIDTH_MAX, t.getInteger("Width"))) : 5;
        lights = t.hasKey("Lights") ? Math.max(RunwayLayout.LIGHTS_TOWER, Math.min(RunwayLayout.STEPS, t.getInteger("Lights"))) : RunwayLayout.LIGHTS_AUTO;
        als = t.getInteger("Als") != 0 ? 1 : 0;
        elev = t.getDouble("Elev");
        ready = t.getBoolean("Ready");
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        super.writeToNBT(t);
        writeCommon(t);
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        super.readFromNBT(t);
        readCommon(t);
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        writeCommon(t);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readCommon(pkt.func_148857_g());
    }
}
