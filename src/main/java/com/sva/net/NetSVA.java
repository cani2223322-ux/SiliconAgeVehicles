package com.sva.net;

import com.sva.SVAMod;
import com.sva.entity.EntityVehicleSVA;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Channel "SAVehicles".
 * Input (C->S): the driver's keys as bit flags, sent on change + a keepalive every 10 ticks.
 * Move (C->S): the driver's simulated position / yaw / speed, every tick while it changes (+ keepalive).
 * Correct (S->C): the server refused a move - the driver snaps back to the server's position.
 * Workbench (C->S): a workbench screen button (start / lay out a recipe / clear), checked against the open container.
 * PickUp (C->S): the cargo screen's «Убрать в инвентарь» - owner / op, nobody seated, within 8 blocks (tryPickUp).
 * Station (C->S): a charging station setting toggled - the open screen's station, its owner / an op only.
 * StationInfo (S->C): the station screen's served vehicle owner name (sent when the vehicle changes).
 * Action (C->S): a vehicle action - the drill keys (its driver) or a cargo screen button (owner / op): the sender must
 * sit in it or stand within 8 blocks; the vehicle throttles repeats (EntityVehicleSVA.serverAction).
 */
public final class NetSVA {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("SAVehicles");

    private NetSVA() {
    }

    public static void init() {
        CHANNEL.registerMessage(InputHandler.class, Input.class, 0, Side.SERVER);
        CHANNEL.registerMessage(MoveHandler.class, Move.class, 1, Side.SERVER);
        CHANNEL.registerMessage(CorrectHandler.class, Correct.class, 2, Side.CLIENT);
        CHANNEL.registerMessage(WorkbenchHandler.class, Workbench.class, 3, Side.SERVER);
        CHANNEL.registerMessage(PickUpHandler.class, PickUp.class, 4, Side.SERVER);
        CHANNEL.registerMessage(StationHandler.class, Station.class, 5, Side.SERVER);
        CHANNEL.registerMessage(StationInfoHandler.class, StationInfo.class, 6, Side.CLIENT);
        CHANNEL.registerMessage(ActionHandler.class, Action.class, 7, Side.SERVER);
    }

    // ---------------------------------------------------------------- input

    public static class Input implements IMessage {
        public int entity;
        public byte flags;

        public Input() {
        }

        public Input(int entity, int flags) {
            this.entity = entity;
            this.flags = (byte) flags;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            flags = buf.readByte();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
            buf.writeByte(flags);
        }
    }

    public static class InputHandler implements IMessageHandler<Input, IMessage> {
        @Override
        public IMessage onMessage(final Input m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    EntityVehicleSVA v = driven(p, m.entity);
                    if (v != null) {
                        v.serverInput(m.flags & 0xFF);
                    }
                }
            });
            return null;
        }
    }

    // ---------------------------------------------------------------- move

    public static class Move implements IMessage {
        public int entity;
        public double x, y, z;
        public float yaw, speed;

        public Move() {
        }

        public Move(int entity, double x, double y, double z, float yaw, float speed) {
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.speed = speed;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            x = buf.readDouble();
            y = buf.readDouble();
            z = buf.readDouble();
            yaw = buf.readFloat();
            speed = buf.readFloat();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(yaw);
            buf.writeFloat(speed);
        }
    }

    public static class MoveHandler implements IMessageHandler<Move, IMessage> {
        @Override
        public IMessage onMessage(final Move m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    EntityVehicleSVA v = driven(p, m.entity);
                    if (v != null) {
                        v.serverMove(p, m.x, m.y, m.z, m.yaw, m.speed);
                    }
                }
            });
            return null;
        }
    }

    // ---------------------------------------------------------------- correction

    public static class Correct implements IMessage {
        public int entity;
        public double x, y, z;
        public float yaw;

        public Correct() {
        }

        public Correct(int entity, double x, double y, double z, float yaw) {
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            x = buf.readDouble();
            y = buf.readDouble();
            z = buf.readDouble();
            yaw = buf.readFloat();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(yaw);
        }
    }

    public static class CorrectHandler implements IMessageHandler<Correct, IMessage> {
        @Override
        public IMessage onMessage(Correct m, MessageContext ctx) {
            SVAMod.proxy.queueCorrection(m);       // applied on the client thread
            return null;
        }
    }

    // ---------------------------------------------------------------- workbench buttons

    public static class Workbench implements IMessage {
        public static final int START = 0, FILL = 1, CLEAR = 2;
        public int x, y, z, action;
        public String recipe = "";

        public Workbench() {
        }

        public Workbench(int x, int y, int z, int action, String recipe) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.action = action;
            this.recipe = recipe == null ? "" : recipe;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
            action = buf.readByte();
            recipe = cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf);
            if (recipe.length() > 64) {
                recipe = "";
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x);
            buf.writeInt(y);
            buf.writeInt(z);
            buf.writeByte(action);
            cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, recipe);
        }
    }

    public static class WorkbenchHandler implements IMessageHandler<Workbench, IMessage> {
        @Override
        public IMessage onMessage(final Workbench m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    com.sva.inventory.ContainerWorkbenchSVA c = p.openContainer instanceof com.sva.inventory.ContainerWorkbenchSVA
                            ? (com.sva.inventory.ContainerWorkbenchSVA) p.openContainer : null;
                    if (c == null || !c.isAt(m.x, m.y, m.z) || !c.canInteractWith(p)) {
                        return;
                    }
                    c.action(p, m.action, m.recipe);
                }
            });
            return null;
        }
    }

    // ---------------------------------------------------------------- cargo screen: pick the vehicle up

    public static class PickUp implements IMessage {
        public int entity;

        public PickUp() {
        }

        public PickUp(int entity) {
            this.entity = entity;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
        }
    }

    public static class PickUpHandler implements IMessageHandler<PickUp, IMessage> {
        @Override
        public IMessage onMessage(final PickUp m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    if (p.worldObj == null) {
                        return;
                    }
                    Entity e = p.worldObj.getEntityByID(m.entity);
                    if (e instanceof EntityVehicleSVA && !e.isDead
                            && ((EntityVehicleSVA) e).tryPickUp(p) == EntityVehicleSVA.PICK_OK) {
                        p.closeScreen();
                    }
                }
            });
            return null;
        }
    }

    // ---------------------------------------------------------------- charging station settings

    public static class Station implements IMessage {
        public int x, y, z, bit;

        public Station() {
        }

        public Station(int x, int y, int z, int bit) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.bit = bit;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
            bit = buf.readByte();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x);
            buf.writeInt(y);
            buf.writeInt(z);
            buf.writeByte(bit);
        }
    }

    public static class StationHandler implements IMessageHandler<Station, IMessage> {
        @Override
        public IMessage onMessage(final Station m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    com.sva.inventory.ContainerChargerSVA c = p.openContainer instanceof com.sva.inventory.ContainerChargerSVA
                            ? (com.sva.inventory.ContainerChargerSVA) p.openContainer : null;
                    if (c == null || !c.isAt(m.x, m.y, m.z) || !c.canInteractWith(p)) {
                        return;
                    }
                    if (!c.getTile().mayConfigure(p)) {
                        p.addChatComponentMessage(new net.minecraft.util.ChatComponentTranslation("sva.station.notOwner", c.getTile().getOwnerName()));
                        return;
                    }
                    c.getTile().toggleSetting(m.bit);
                    c.detectAndSendChanges();
                }
            });
            return null;
        }
    }

    public static class StationInfo implements IMessage {
        public String owner = "";

        public StationInfo() {
        }

        public StationInfo(String owner) {
            this.owner = owner == null ? "" : owner;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            owner = cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf);
            if (owner.length() > 64) {
                owner = owner.substring(0, 64);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, owner);
        }
    }

    public static class StationInfoHandler implements IMessageHandler<StationInfo, IMessage> {
        @Override
        public IMessage onMessage(StationInfo m, MessageContext ctx) {
            SVAMod.proxy.stationInfo(m.owner);
            return null;
        }
    }

    // ---------------------------------------------------------------- vehicle actions (drill keys, cargo screen buttons)

    public static class Action implements IMessage {
        public int entity, action;

        public Action() {
        }

        public Action(int entity, int action) {
            this.entity = entity;
            this.action = action;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            action = buf.readByte();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
            buf.writeByte(action);
        }
    }

    public static class ActionHandler implements IMessageHandler<Action, IMessage> {
        @Override
        public IMessage onMessage(final Action m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    if (p.worldObj == null) {
                        return;
                    }
                    Entity e = p.worldObj.getEntityByID(m.entity);
                    if (!(e instanceof EntityVehicleSVA) || e.isDead) {
                        return;
                    }
                    EntityVehicleSVA v = (EntityVehicleSVA) e;
                    if (v.carries(p) || p.getDistanceSqToEntity(v) < 64) {
                        v.serverAction(p, m.action);
                    }
                }
            });
            return null;
        }
    }

    /** The vehicle `id` if `p` drives it (server thread). */
    static EntityVehicleSVA driven(EntityPlayerMP p, int id) {
        if (p == null || p.worldObj == null) {
            return null;
        }
        Entity e = p.worldObj.getEntityByID(id);
        if (e instanceof EntityVehicleSVA && !e.isDead && e.riddenByEntity == p) {
            return (EntityVehicleSVA) e;
        }
        return null;
    }
}
