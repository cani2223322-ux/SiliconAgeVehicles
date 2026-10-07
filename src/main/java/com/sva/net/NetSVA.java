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
 * Input (C->S): the driver's keys as bit flags (+ the cruise / autopilot targets while engaged), sent on change + a
 * keepalive every 10 ticks.
 * Move (C->S): the driver's simulated position / yaw / speed, every tick while it changes (+ keepalive).
 * Correct (S->C): the server refused a move - the driver snaps back to the server's position.
 * Workbench (C->S): a workbench screen button (start / lay out a recipe / clear), checked against the open container.
 * PickUp (C->S): the cargo screen's «Убрать в инвентарь» - owner / op, nobody seated, within 8 blocks (tryPickUp).
 * Station (C->S): a charging station setting toggled - the open screen's station, its owner / an op only.
 * StationInfo (S->C): the station screen's served vehicle owner name (sent when the vehicle changes).
 * Action (C->S): a vehicle action - the drill keys (its driver) or a cargo screen button (owner / op): the sender must
 * sit in it or stand within 8 blocks; the vehicle throttles repeats (EntityVehicleSVA.serverAction).
 * Wave 2: Garage (C->S) - a repair garage screen button (repair all / hull only), checked against the open container;
 * AccessInfo (S->C) - a vehicle's access list for the ★10 access screen (sent when it opens and after each edit);
 * AccessEdit (C->S) - an access screen change (owner / op, within 8 blocks: EntityVehicleSVA.accessEdit).
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
        CHANNEL.registerMessage(GarageHandler.class, Garage.class, 8, Side.SERVER);
        CHANNEL.registerMessage(AccessInfoHandler.class, AccessInfo.class, 9, Side.CLIENT);
        CHANNEL.registerMessage(AccessEditHandler.class, AccessEdit.class, 10, Side.SERVER);
    }

    // ---------------------------------------------------------------- input

    public static class Input implements IMessage {
        public int entity;
        /** EntityVehicleSVA.IN_* bits (9 since wave 1: IN_CRUISE). */
        public int flags;
        /** ★4, only with IN_CRUISE: the cruise speed (b/t), the autopilot's yaw and height. */
        public float cruise, yaw, alt;

        public Input() {
        }

        public Input(int entity, int flags) {
            this(entity, flags, 0F, 0F, 0F);
        }

        public Input(int entity, int flags, float cruise, float yaw, float alt) {
            this.entity = entity;
            this.flags = flags & EntityVehicleSVA.IN_MASK;
            this.cruise = cruise;
            this.yaw = yaw;
            this.alt = alt;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            flags = buf.readShort() & EntityVehicleSVA.IN_MASK;
            if ((flags & EntityVehicleSVA.IN_CRUISE) != 0) {
                cruise = buf.readFloat();
                yaw = buf.readFloat();
                alt = buf.readFloat();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
            buf.writeShort(flags);
            if ((flags & EntityVehicleSVA.IN_CRUISE) != 0) {
                buf.writeFloat(cruise);
                buf.writeFloat(yaw);
                buf.writeFloat(alt);
            }
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
                        v.serverInput(m.flags, m.cruise, m.yaw, m.alt);
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

    // ---------------------------------------------------------------- wave 2: the repair garage's buttons

    public static class Garage implements IMessage {
        public int x, y, z, mode;

        public Garage() {
        }

        public Garage(int x, int y, int z, int mode) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.mode = mode;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
            mode = buf.readByte();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(x);
            buf.writeInt(y);
            buf.writeInt(z);
            buf.writeByte(mode);
        }
    }

    public static class GarageHandler implements IMessageHandler<Garage, IMessage> {
        @Override
        public IMessage onMessage(final Garage m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    com.sva.inventory.ContainerGarageSVA c = p.openContainer instanceof com.sva.inventory.ContainerGarageSVA
                            ? (com.sva.inventory.ContainerGarageSVA) p.openContainer : null;
                    if (c == null || !c.isAt(m.x, m.y, m.z) || !c.canInteractWith(p)) {
                        return;
                    }
                    c.getTile().startRepair(p, m.mode);
                    c.detectAndSendChanges();
                }
            });
            return null;
        }
    }

    // ---------------------------------------------------------------- wave 2, ★10: the access screen

    public static class AccessInfo implements IMessage {
        public int entity, type, board, mode, others, lock;
        public String owner = "";
        public final java.util.List<String> names = new java.util.ArrayList<String>();
        public final java.util.List<Integer> rights = new java.util.ArrayList<Integer>();

        public AccessInfo() {
        }

        public AccessInfo(EntityVehicleSVA v) {
            entity = v.getEntityId();
            type = v.state.type.ordinal();
            board = v.getBoardNumber();
            owner = v.state.ownerName == null ? "" : v.state.ownerName;
            com.sva.vehicle.AccessMath.Access a = v.state.access;
            mode = a.mode;
            others = a.others;
            lock = a.lockVersion;
            names.addAll(a.names);
            rights.addAll(a.rights);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            type = buf.readByte();
            board = buf.readByte();
            mode = buf.readByte();
            others = buf.readByte();
            lock = buf.readInt();
            owner = cut(cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf), 64);
            int n = Math.min(com.sva.vehicle.AccessMath.MAX_FRIENDS, buf.readByte());
            for (int i = 0; i < n; i++) {
                names.add(cut(cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf), 16));
                rights.add(Integer.valueOf(buf.readByte()));
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
            buf.writeByte(type);
            buf.writeByte(board);
            buf.writeByte(mode);
            buf.writeByte(others);
            buf.writeInt(lock);
            cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, owner);
            int n = Math.min(com.sva.vehicle.AccessMath.MAX_FRIENDS, names.size());
            buf.writeByte(n);
            for (int i = 0; i < n; i++) {
                cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, names.get(i));
                buf.writeByte(rights.get(i).intValue());
            }
        }
    }

    static String cut(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
    }

    public static class AccessInfoHandler implements IMessageHandler<AccessInfo, IMessage> {
        @Override
        public IMessage onMessage(AccessInfo m, MessageContext ctx) {
            SVAMod.proxy.accessInfo(m);                             // opened / refreshed on the client thread
            return null;
        }
    }

    public static class AccessEdit implements IMessage {
        public int entity, op, value;
        public String name = "";

        public AccessEdit() {
        }

        public AccessEdit(int entity, int op, String name, int value) {
            this.entity = entity;
            this.op = op;
            this.name = name == null ? "" : name;
            this.value = value;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            op = buf.readByte();
            value = buf.readByte();
            name = cut(cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf), 16);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
            buf.writeByte(op);
            buf.writeByte(value);
            cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, cut(name, 16));
        }
    }

    public static class AccessEditHandler implements IMessageHandler<AccessEdit, IMessage> {
        @Override
        public IMessage onMessage(final AccessEdit m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    if (p.worldObj == null) {
                        return;
                    }
                    Entity e = p.worldObj.getEntityByID(m.entity);
                    if (e instanceof EntityVehicleSVA && !e.isDead) {
                        ((EntityVehicleSVA) e).accessEdit(p, m.op, m.name, m.value);
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
