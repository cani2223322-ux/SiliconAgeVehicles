package com.sva.net;

import com.sva.SVAMod;

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

/**
 * Air-defence wave: channel "SAVehiclesAD" - two messages carrying a small NBT payload each, so the air defence's
 * screens and the pilots' displays need no new message class per field.
 * Cmd (C->S): the transponder panel (XPDR_SET / XPDR_IDENT, the sender must drive the vehicle), the command post's screen
 * (KP_CMD - its owner / access list; KP_CLOSE), the launcher's screen (LAUNCHER_SET). Run on the server thread through
 * ServerQueueSVA (flood-limited per player); airdefense.AirDefenseSVA.command checks everything again.
 * Data (S->C): the command post's picture to its open screens (KP, every 10 ticks while open), a launcher's state to its
 * open screen (LAUNCHER), a vehicle's transponder to its riders (XPDR, on change), the RWR threats to its riders (RWR, on
 * change / every 5 ticks with a missile in the air), the zones pilots see (ZONES, on login and on change), a message to the
 * pilot (MSG). Nothing is sent every tick.
 */
public final class NetADSVA {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("SAVehiclesAD");

    public static final int XPDR_SET = 1, XPDR_IDENT = 2, KP_CMD = 3, KP_CLOSE = 4, LAUNCHER_SET = 5,
            /** Upgrades wave: open a block's equipment screen (the command post's «Оборудование» tab) / back to the post's screen. */
            AD_EQUIP = 6, KP_OPEN = 7;
    public static final int D_KP = 1, D_LAUNCHER = 2, D_XPDR = 3, D_RWR = 4, D_ZONES = 5, D_MSG = 6,
            /** Upgrades wave: an equipment screen's picture (every 10 ticks while open). */
            D_EQUIP = 7;

    private NetADSVA() {
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

    public static void sendToAll(int kind, NBTTagCompound t) {
        CHANNEL.sendToAll(new Data(kind, t));
    }

    /** Client: a command to the server. */
    public static void cmd(int op, int x, int y, int z, int entity, NBTTagCompound data) {
        CHANNEL.sendToServer(new Cmd(op, x, y, z, entity, data));
    }

    public static class Cmd implements IMessage {
        public int op, x, y, z, entity;
        public NBTTagCompound data;

        public Cmd() {
        }

        public Cmd(int op, int x, int y, int z, int entity, NBTTagCompound data) {
            this.op = op;
            this.x = x;
            this.y = y;
            this.z = z;
            this.entity = entity;
            this.data = data == null ? new NBTTagCompound() : data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            op = buf.readByte();
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
            entity = buf.readInt();
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
            buf.writeInt(entity);
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
                    com.sva.airdefense.AirDefenseSVA.INSTANCE.command(p, m);
                }
            });
            return null;
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
            SVAMod.proxy.adData(m.kind, m.data);
            return null;
        }
    }
}
