package com.sva.block;

import com.sva.vehicle.LinerIls;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

/**
 * Liner wave 4: the «Курсо-глиссадный маяк»'s settings - the landing course, the glide slope's angle, the ident, the
 * frequency, the centre line's offset and the touchdown elevation (LinerIls.Station). The block's screen (client.GuiIlsSVA)
 * changes them through NetSVA.IlsSet; every change goes into the save's ILS list (IlsDataSVA - the aircraft read that,
 * not the chunk). Nothing ticks.
 */
public class TileEntityIlsSVA extends TileEntity {

    public final LinerIls.Station st = new LinerIls.Station();
    /** Set up (placed and given a course) - a placed block without it takes the placer's facing. */
    public boolean ready;

    @Override
    public boolean canUpdate() {
        return false;
    }

    /** The station with this block's place (dimension, coordinates). */
    public LinerIls.Station station() {
        LinerIls.Station s = st.copy();
        s.dim = worldObj == null ? 0 : worldObj.provider.dimensionId;
        s.bx = xCoord;
        s.by = yCoord;
        s.bz = zCoord;
        return s;
    }

    /** Server: the settings changed (or placed) - into the save's list, to the clients. */
    public void publish() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        IlsDataSVA d = IlsDataSVA.get(worldObj);
        if (d != null) {
            d.put(station());
        }
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /** Server: the screen's values (sanitised). */
    public void set(double course, double angle, String ident, int freq, int offset) {
        st.course = Double.isNaN(course) ? st.course : LinerIls.wrap360(Math.round(course));
        st.angle = LinerIls.clampAngle(angle);
        String id = LinerIls.cleanIdent(ident);
        if (id.length() > 0) {
            st.ident = id;
        }
        st.freq = LinerIls.clampFreq(freq);
        st.offset = Math.max(-LinerIls.OFFSET_MAX, Math.min(LinerIls.OFFSET_MAX, offset));
        ready = true;
        publish();
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        super.writeToNBT(t);
        NBTTagCompound s = new NBTTagCompound();
        st.write(s);
        t.setTag("Ils", s);
        t.setBoolean("Ready", ready);
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        super.readFromNBT(t);
        if (t.hasKey("Ils")) {
            st.read(t.getCompoundTag("Ils"));
        }
        ready = t.getBoolean("Ready");
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        st.write(t);
        t.setBoolean("Ready", ready);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound t = pkt.func_148857_g();
        st.read(t);
        ready = t.getBoolean("Ready");
    }
}
