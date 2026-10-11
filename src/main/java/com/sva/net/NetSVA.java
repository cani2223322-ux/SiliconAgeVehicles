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
 * ТН-6: Input and Move are not queued - only the latest of each per player runs at the next tick's start
 * (ServerQueueSVA.latest), so they never crowd the buttons out of the per-player queue.
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
 * Wave 3, ★19: MapRequest (C->S) - the «Моя техника» screen asks (on open and every MapMath.REFRESH_EVERY ticks);
 * MapInfo (S->C) - the vehicles the sender may see (MapMath.visible: his own, or listed / a key card), nearest first.
 * Round 2, D4 (★22 the flight plan): RouteRequest (C->S) - the map's «Маршрут» tab asks for an aircraft's plan (loaded
 * in the sender's world); RouteSet (C->S) - its «В автопилот»: the new plan (EntityVehicleSVA.serverSetPlan: the right to
 * drive it - RouteMath.mayEdit); RouteInfo (S->C) - the plan (points, active one, may the receiver edit it) to the tab
 * and to the driver (when he gets in and after a change - his client's physics flies it).
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
        CHANNEL.registerMessage(MapRequestHandler.class, MapRequest.class, 11, Side.SERVER);
        CHANNEL.registerMessage(MapInfoHandler.class, MapInfo.class, 12, Side.CLIENT);
        CHANNEL.registerMessage(RouteRequestHandler.class, RouteRequest.class, 13, Side.SERVER);
        CHANNEL.registerMessage(RouteSetHandler.class, RouteSet.class, 14, Side.SERVER);
        CHANNEL.registerMessage(RouteInfoHandler.class, RouteInfo.class, 15, Side.CLIENT);
    }

    // ---------------------------------------------------------------- input

    public static class Input implements IMessage {
        public int entity;
        /** EntityVehicleSVA.IN_* bits (9 since wave 1: IN_CRUISE). */
        public int flags;
        /** ★4, only with IN_CRUISE: the cruise speed (b/t), the autopilot's yaw and height. */
        public float cruise, yaw, alt;
        /** Aircraft stage C, only with IN_CRUISE: the autopilot's modes (AutopilotMath.M_*, a byte) and set vertical speed (b/t). */
        public int modes = com.sva.vehicle.AutopilotMath.DEFAULT;
        public float vs;

        public Input() {
        }

        public Input(int entity, int flags) {
            this(entity, flags, 0F, 0F, 0F);
        }

        public Input(int entity, int flags, float cruise, float yaw, float alt) {
            this(entity, flags, cruise, yaw, alt, com.sva.vehicle.AutopilotMath.DEFAULT, 0F);
        }

        public Input(int entity, int flags, float cruise, float yaw, float alt, int modes, float vs) {
            this.entity = entity;
            this.flags = flags & EntityVehicleSVA.IN_MASK;
            this.cruise = cruise;
            this.yaw = yaw;
            this.alt = alt;
            this.modes = modes & com.sva.vehicle.AutopilotMath.M_ALL;
            this.vs = vs;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            flags = buf.readShort() & EntityVehicleSVA.IN_MASK;
            if ((flags & EntityVehicleSVA.IN_CRUISE) != 0) {
                cruise = buf.readFloat();
                yaw = buf.readFloat();
                alt = buf.readFloat();
                modes = buf.readByte() & com.sva.vehicle.AutopilotMath.M_ALL;
                vs = buf.readFloat();
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
                buf.writeByte(modes);
                buf.writeFloat(vs);
            }
        }
    }

    public static class InputHandler implements IMessageHandler<Input, IMessage> {
        @Override
        public IMessage onMessage(final Input m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.latest(p, ServerQueueSVA.LATEST_INPUT, new Runnable() {
                @Override
                public void run() {
                    EntityVehicleSVA v = driven(p, m.entity);
                    if (v != null) {
                        v.serverInput(m.flags, m.cruise, m.yaw, m.alt, m.modes, m.vs);
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
            ServerQueueSVA.INSTANCE.latest(p, ServerQueueSVA.LATEST_MOVE, new Runnable() {
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
        public boolean open;                                        // true = (re)open the screen, false = refresh only if open
        public String owner = "";
        public final java.util.List<String> names = new java.util.ArrayList<String>();
        public final java.util.List<Integer> rights = new java.util.ArrayList<Integer>();

        public AccessInfo() {
        }

        public AccessInfo(EntityVehicleSVA v, boolean open) {
            this.open = open;
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
            open = buf.readBoolean();
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
            buf.writeBoolean(open);
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

    // ---------------------------------------------------------------- wave 3, ★19: «Моя техника»

    public static class MapRequest implements IMessage {
        public MapRequest() {
        }

        @Override
        public void fromBytes(ByteBuf buf) {
        }

        @Override
        public void toBytes(ByteBuf buf) {
        }
    }

    public static class MapRequestHandler implements IMessageHandler<MapRequest, IMessage> {
        /** Rebuild at most once per this many ticks per player; in between the last map is resent (once per tick). */
        static final int MIN_INTERVAL = 20;
        /** Per player: {built tick, sent tick, MapInfo}. Server thread only; cleared by ServerQueueSVA.clear(). */
        static final java.util.Map<java.util.UUID, Object[]> CACHE = new java.util.HashMap<java.util.UUID, Object[]>();

        @Override
        public IMessage onMessage(MapRequest m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    if (p.worldObj != null && p.playerNetServerHandler != null) {
                        long now = net.minecraft.server.MinecraftServer.getServer().getTickCounter();
                        Object[] c = CACHE.get(p.getUniqueID());
                        if (c != null && now >= (Long) c[0] && now - (Long) c[0] < MIN_INTERVAL) {
                            if ((Long) c[1] != now) {
                                c[1] = Long.valueOf(now);
                                CHANNEL.sendTo((MapInfo) c[2], p);
                            }
                            return;
                        }
                        MapInfo info = MapInfo.build(p);
                        CACHE.put(p.getUniqueID(), new Object[] {Long.valueOf(now), Long.valueOf(now), info});
                        CHANNEL.sendTo(info, p);
                    }
                }
            });
            return null;
        }
    }

    /** One player's map: whether the map is on, the world time, and the rows. */
    public static class MapInfo implements IMessage {
        /**
         * One vehicle: type, board, block position, dimension, charge / hull %, seconds since seen, owner, own, position
         * known; D4: its entity id when loaded in the player's world (-1: not) and whether he may drive it (the «Маршрут» tab).
         */
        public static final class Row {
            public int type, board, x, y, z, dim, charge, hull, age, entity = -1;
            public String owner = "";
            public boolean own, placed, drive;
        }

        public boolean enabled;
        public final java.util.List<Row> rows = new java.util.ArrayList<Row>();

        public MapInfo() {
        }

        /** Server: the rows `p` may see, his dimension first, nearest first, at most MapMath.MAX_LIST. */
        public static MapInfo build(EntityPlayerMP p) {
            MapInfo m = new MapInfo();
            m.enabled = com.sva.ConfigSVA.vehicleMap;
            com.sva.vehicle.VehicleRegistrySVA reg = com.sva.vehicle.VehicleRegistrySVA.get(p.worldObj);
            if (!m.enabled || reg == null) {
                return m;
            }
            String id = p.getUniqueID().toString(), name = p.getCommandSenderName();
            long now = p.worldObj.getTotalWorldTime();
            final int dim = p.worldObj.provider.dimensionId;
            final double px = p.posX, pz = p.posZ;
            java.util.List<com.sva.vehicle.VehicleRegistrySVA.Entry> list = reg.all();
            // D4: the loaded vehicles of the player's world by their id (the route tab edits loaded aircraft only)
            java.util.Map<String, EntityVehicleSVA> loaded = new java.util.HashMap<String, EntityVehicleSVA>();
            for (Object o : p.worldObj.loadedEntityList) {
                if (o instanceof EntityVehicleSVA && !((EntityVehicleSVA) o).isDead && ((EntityVehicleSVA) o).state.vehId != null) {
                    loaded.put(((EntityVehicleSVA) o).state.vehId, (EntityVehicleSVA) o);
                }
            }
            java.util.Collections.sort(list, new java.util.Comparator<com.sva.vehicle.VehicleRegistrySVA.Entry>() {
                @Override
                public int compare(com.sva.vehicle.VehicleRegistrySVA.Entry a, com.sva.vehicle.VehicleRegistrySVA.Entry b) {
                    double da = (a.dim == dim ? 0 : 1e12) + (a.x - px) * (a.x - px) + (a.z - pz) * (a.z - pz);
                    double db = (b.dim == dim ? 0 : 1e12) + (b.x - px) * (b.x - px) + (b.z - pz) * (b.z - pz);
                    return Double.compare(da, db);
                }
            });
            for (com.sva.vehicle.VehicleRegistrySVA.Entry e : list) {
                boolean own = e.owner.equals(id);
                int card = -1;
                for (net.minecraft.item.ItemStack s : p.inventory.mainInventory) {
                    int r = com.sva.item.ItemKeyCardSVA.rightsFor(s, e.id, e.access);         // ТН-1: a revoked friend's card doesn't count
                    if (r >= 0) {
                        card = card < 0 ? r : card | r;
                    }
                }
                if (!com.sva.vehicle.MapMath.visible(own, com.sva.ConfigSVA.locks, e.access.mode, e.access.listed(id, name), card)) {   // ТН-4
                    continue;
                }
                Row r = new Row();
                r.type = e.type;
                r.board = e.board;
                r.x = net.minecraft.util.MathHelper.floor_double(e.x);
                r.y = net.minecraft.util.MathHelper.floor_double(e.y);
                r.z = net.minecraft.util.MathHelper.floor_double(e.z);
                r.dim = e.dim;
                r.charge = Math.round(e.charge * 100);
                r.hull = Math.round(e.hull * 100);
                r.age = com.sva.vehicle.MapMath.ageSeconds(now, e.seen);
                r.owner = e.ownerName;
                r.own = own;
                r.placed = e.placed;
                EntityVehicleSVA ev = loaded.get(e.id);
                if (ev != null) {
                    r.entity = ev.getEntityId();
                    r.drive = ev.canDrive(p);
                }
                m.rows.add(r);
                if (m.rows.size() >= com.sva.vehicle.MapMath.MAX_LIST) {
                    break;
                }
            }
            return m;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            enabled = buf.readBoolean();
            int n = Math.min(com.sva.vehicle.MapMath.MAX_LIST, buf.readUnsignedByte());
            for (int i = 0; i < n; i++) {
                Row r = new Row();
                r.type = buf.readUnsignedByte();
                r.board = buf.readUnsignedByte();
                r.x = buf.readInt();
                r.y = buf.readShort();
                r.z = buf.readInt();
                r.dim = buf.readInt();
                r.charge = buf.readUnsignedByte();
                r.hull = buf.readUnsignedByte();
                r.age = buf.readInt();
                int f = buf.readUnsignedByte();
                r.own = (f & 1) != 0;
                r.placed = (f & 2) != 0;
                r.drive = (f & 4) != 0;
                r.entity = buf.readInt();
                r.owner = cut(cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf), 32);
                rows.add(r);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBoolean(enabled);
            int n = Math.min(com.sva.vehicle.MapMath.MAX_LIST, rows.size());
            buf.writeByte(n);
            for (int i = 0; i < n; i++) {
                Row r = rows.get(i);
                buf.writeByte(r.type);
                buf.writeByte(r.board);
                buf.writeInt(r.x);
                buf.writeShort(Math.max(-32768, Math.min(32767, r.y)));
                buf.writeInt(r.z);
                buf.writeInt(r.dim);
                buf.writeByte(Math.max(0, Math.min(100, r.charge)));
                buf.writeByte(Math.max(0, Math.min(100, r.hull)));
                buf.writeInt(r.age);
                buf.writeByte((r.own ? 1 : 0) | (r.placed ? 2 : 0) | (r.drive ? 4 : 0));
                buf.writeInt(r.entity);
                cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, cut(r.owner, 32));
            }
        }
    }

    public static class MapInfoHandler implements IMessageHandler<MapInfo, IMessage> {
        @Override
        public IMessage onMessage(MapInfo m, MessageContext ctx) {
            SVAMod.proxy.mapInfo(m);                                // shown on the client thread
            return null;
        }
    }

    // ---------------------------------------------------------------- round 2, D4 (★22): the flight plan

    /** The aircraft `id` loaded in p's world (server thread), or null. */
    static EntityVehicleSVA aircraft(EntityPlayerMP p, int id) {
        if (p == null || p.worldObj == null) {
            return null;
        }
        Entity e = p.worldObj.getEntityByID(id);
        return e instanceof EntityVehicleSVA && !e.isDead && ((EntityVehicleSVA) e).isAircraft() ? (EntityVehicleSVA) e : null;
    }

    public static class RouteRequest implements IMessage {
        public int entity;

        public RouteRequest() {
        }

        public RouteRequest(int entity) {
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

    public static class RouteRequestHandler implements IMessageHandler<RouteRequest, IMessage> {
        @Override
        public IMessage onMessage(final RouteRequest m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    EntityVehicleSVA v = aircraft(p, m.entity);
                    if (v != null) {
                        v.sendPlan(p);
                    }
                }
            });
            return null;
        }
    }

    /** The points of a plan on the wire: count, then {x, z, alt} each (at most RouteMath.MAX). */
    static void writePoints(ByteBuf buf, int[] flat) {
        int n = Math.min(com.sva.vehicle.RouteMath.MAX, flat.length / 3);
        buf.writeByte(n);
        for (int i = 0; i < n * 3; i++) {
            buf.writeInt(flat[i]);
        }
    }

    static int[] readPoints(ByteBuf buf) {
        int n = Math.min(com.sva.vehicle.RouteMath.MAX, buf.readUnsignedByte());
        int[] a = new int[n * 3];
        for (int i = 0; i < a.length; i++) {
            a[i] = buf.readInt();
        }
        return a;
    }

    public static class RouteSet implements IMessage {
        public int entity;
        public int[] points = new int[0];

        public RouteSet() {
        }

        public RouteSet(int entity, com.sva.vehicle.RouteMath.Plan plan) {
            this.entity = entity;
            this.points = com.sva.vehicle.RouteMath.flatten(plan);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            points = readPoints(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
            writePoints(buf, points);
        }
    }

    public static class RouteSetHandler implements IMessageHandler<RouteSet, IMessage> {
        @Override
        public IMessage onMessage(final RouteSet m, MessageContext ctx) {
            final EntityPlayerMP p = ctx.getServerHandler().playerEntity;
            ServerQueueSVA.INSTANCE.queue(p, new Runnable() {
                @Override
                public void run() {
                    EntityVehicleSVA v = aircraft(p, m.entity);
                    if (v != null) {
                        v.serverSetPlan(p, m.points);
                    }
                }
            });
            return null;
        }
    }

    public static class RouteInfo implements IMessage {
        public int entity, active;
        public boolean editable;
        public int[] points = new int[0];

        public RouteInfo() {
        }

        public RouteInfo(int entity, com.sva.vehicle.RouteMath.Plan plan, boolean editable) {
            this.entity = entity;
            this.points = com.sva.vehicle.RouteMath.flatten(plan);
            this.active = plan.active;
            this.editable = editable;
        }

        /** The plan received (sanitised). */
        public com.sva.vehicle.RouteMath.Plan plan() {
            return com.sva.vehicle.RouteMath.sanitize(points, active);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entity = buf.readInt();
            active = buf.readUnsignedByte();
            editable = buf.readBoolean();
            points = readPoints(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(entity);
            buf.writeByte(Math.max(0, Math.min(255, active)));
            buf.writeBoolean(editable);
            writePoints(buf, points);
        }
    }

    public static class RouteInfoHandler implements IMessageHandler<RouteInfo, IMessage> {
        @Override
        public IMessage onMessage(RouteInfo m, MessageContext ctx) {
            SVAMod.proxy.routeInfo(m);                              // applied on the client thread
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
