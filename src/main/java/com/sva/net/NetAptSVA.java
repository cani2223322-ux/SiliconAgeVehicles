package com.sva.net;

import com.sva.SVAMod;
import com.sva.block.TileEntityIlsAntennaSVA;
import com.sva.block.TileEntityRunwayEndSVA;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/**
 * Airport wave B1: channel "SAVehiclesApt" - as NetADSVA, two messages with a small NBT payload each.
 * Cmd (C->S): an antenna's screen saved (SET_ANTENNA: ident, frequency, angle), a runway end's screen saved (SET_END:
 * course, width, lights, ALS). Run on the server thread through ServerQueueSVA, the player within reach of the block.
 * Data (S->C): the airport's copy for the clients (D_SYNC: block.AirportDataSVA.clientData - the runways for their paint and
 * lights, the stations' status, the wind's seed) - on login, on a dimension change and when something changes.
 */
public final class NetAptSVA {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("SAVehiclesApt");

    public static final int SET_ANTENNA = 1, SET_END = 2;
    public static final int D_SYNC = 1;

    private NetAptSVA() {
    }

    public static void init() {
        CHANNEL.registerMessage(CmdHandler.class, Cmd.class, 0, Side.SERVER);
        CHANNEL.registerMessage(DataHandler.class, Data.class, 1, Side.CLIENT);
    }

    public static void send(EntityPlayerMP p, int kind, NBTTagCompound t) {
        if (p != null && p.playerNetServerHandler != null) {
            CHANNEL.sendTo(new Data(kind, t), p);
        }
    }

    public static void cmd(int op, int x, int y, int z, NBTTagCompound data) {
        CHANNEL.sendToServer(new Cmd(op, x, y, z, data));
    }

    public static class Cmd implements IMessage {
        public int op, x, y, z;
        public NBTTagCompound data;

        public Cmd() {
        }

        public Cmd(int op, int x, int y, int z, NBTTagCompound data) {
            this.op = op;
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data == null ? new NBTTagCompound() : data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            op = buf.readByte();
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
            data = ByteBufUtils.readTag(buf);
            if (data == null) {
                data = new NBTTagCompound();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(op);
            buf.writeInt(x);
            buf.writeInt(y);
            buf.writeInt(z);
            ByteBufUtils.writeTag(buf, data);
        }
    }

    public static class CmdHandler implements IMessageHandler<Cmd, IMessage> {
        @Override
        public IMessage onMessage(final Cmd m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    command(p, m);
                }
            });
            return null;
        }
    }

    /** Server: a screen's values for the block (only within 8 blocks of it). */
    static void command(EntityPlayerMP p, Cmd m) {
        if (p == null || p.worldObj == null || p.getDistanceSq(m.x + 0.5, m.y + 0.5, m.z + 0.5) > 100 || !p.worldObj.blockExists(m.x, m.y, m.z)) {
            return;
        }
        TileEntity te = p.worldObj.getTileEntity(m.x, m.y, m.z);
        if (m.op == SET_ANTENNA && te instanceof TileEntityIlsAntennaSVA) {
            ((TileEntityIlsAntennaSVA) te).set(m.data.getString("Ident"), m.data.getInteger("Freq"), m.data.getDouble("Angle"));
        } else if (m.op == SET_END && te instanceof TileEntityRunwayEndSVA) {
            ((TileEntityRunwayEndSVA) te).set(m.data.getDouble("Course"), m.data.getInteger("Width"), m.data.getInteger("Lights"), m.data.getInteger("Als"));
        }
    }

    public static class Data implements IMessage {
        public int kind;
        public NBTTagCompound data;

        public Data() {
        }

        public Data(int kind, NBTTagCompound data) {
            this.kind = kind;
            this.data = data == null ? new NBTTagCompound() : data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
            data = ByteBufUtils.readTag(buf);
            if (data == null) {
                data = new NBTTagCompound();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
            ByteBufUtils.writeTag(buf, data);
        }
    }

    public static class DataHandler implements IMessageHandler<Data, IMessage> {
        @Override
        public IMessage onMessage(Data m, MessageContext ctx) {
            SVAMod.proxy.aptData(m.kind, m.data);
            return null;
        }
    }
}
