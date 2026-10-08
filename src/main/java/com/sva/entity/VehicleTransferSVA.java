package com.sva.entity;

import com.sc.bridge.BridgeMathSC;
import com.sc.bridge.BridgeTeleportSC;
import com.sva.net.ServerQueueSVA;
import com.sva.vehicle.SpaceMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S1BPacketEntityAttach;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Stage E: moving a vehicle WITH its riders and cargo somewhere else - the same dimension or another one (bridge
 * docking, the Singular Ship's jump). 1.7.10's travelToDimension is not safe for an entity that has riders (the
 * riders are left behind half-mounted, a passenger seat entity can't follow, and vanilla's portal search runs), so:
 * <ol>
 * <li>the riders are noted by seat (driver 0, passenger seat N) and dismounted;</li>
 * <li>the vehicle's whole state (VehicleState NBT: EU, gases, hull, cargo, owner, id, modes) goes into a NEW vehicle
 * entity spawned at the destination (its chunks loaded first); the old one is removed (the registry keeps the id);</li>
 * <li>every rider goes through the core bridge's own teleport (BridgeTeleportSC - players by the server's transfer
 * with an exact Teleporter, other entities re-created in the target world) next to its seat;</li>
 * <li>the new vehicle mounts them again on its own ticks once its seats exist: mobs from MOUNT_MOB ticks, players from
 * MOUNT_PLAYER ticks (their client must know the new vehicle first), and repeats the attach packet to a player
 * RESEND ticks later (a client that was still loading the world keeps the seat). Meanwhile the vehicle is held
 * still (EntityVehicleSVA.isHeld) and the waiting riders kept at their seats.</li>
 * </ol>
 */
public final class VehicleTransferSVA {

    public static final int MOUNT_MOB = 3, MOUNT_PLAYER = 10, PENDING_MAX = 100, RESEND = 20;

    /** A pending rider's mark: a player still to be carried over by `later`. */
    private static final Object WAITING = new Object();

    private VehicleTransferSVA() {
    }

    /**
     * Moves `v` to (x, y, z) of `dim` facing `yaw` with its riders and cargo. @return the vehicle now there (a new
     * entity), or null if it could not go (nothing changed then).
     */
    public static EntityVehicleSVA transfer(EntityVehicleSVA v, int dim, double x, double y, double z, float yaw) {
        if (v == null || v.isDead || v.worldObj == null || v.worldObj.isRemote) {
            return null;
        }
        WorldServer to = world(v.worldObj, dim);
        if (to == null) {
            return null;
        }
        loadAround(to, x, z, 1);
        if (!to.getChunkProvider().chunkExists(MathHelper.floor_double(x) >> 4, MathHelper.floor_double(z) >> 4)) {
            return null;
        }
        VehicleType t = v.state.type;
        Entity[] riders = v.ridersBySeat();
        v.dismountAll();
        NBTTagCompound st = new NBTTagCompound();
        v.state.writeNBT(st);
        EntityVehicleSVA n = new EntityVehicleSVA(to, t);
        n.state.readNBT(st);                                       // the same id, owner, cargo, gases, modes
        n.setLocationAndAngles(x, y, z, yaw, 0F);
        n.prevRotationYaw = yaw;
        n.speed = v.speed;
        n.throttle = v.throttle;
        n.drillOn = v.drillOn;
        n.dockCooldown = SpaceMath.DOCK_COOLDOWN;
        n.syncAll();
        World from = v.worldObj;
        double ox = v.posX, oy = v.posY, oz = v.posZ;
        v.setDead();                                               // the registry forgets the id, the seats go
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(to);
        if (reg != null) {
            reg.add(n.state.vehId, n.state.ownerId);
        }
        if (!to.spawnEntityInWorld(n)) {
            // never lose it: back where it was as its item (with everything inside)
            EntityItem item = new EntityItem(from, ox, oy + 0.5, oz, n.toItemStack());
            from.spawnEntityInWorld(item);
            if (reg != null) {
                reg.remove(n.state.vehId);
            }
            return null;
        }
        for (int i = 0; i < riders.length; i++) {
            Entity e = riders[i];
            if (e == null || e.isDead) {
                continue;
            }
            double[] p = n.seatPos(i);
            double sy = y + t.seatY + t.seatDy(i);
            if (e instanceof EntityPlayerMP && e.worldObj.provider.dimensionId != dim) {
                later((EntityPlayerMP) e, n, i, dim, p[0], sy, p[2], yaw);
            } else {
                carry(e, n, i, dim, p[0], sy, p[2], yaw);
            }
        }
        return n;
    }

    /** One rider through the bridge's teleport to its seat, then waiting there for it. */
    static void carry(Entity e, EntityVehicleSVA n, int seat, int dim, double x, double y, double z, float yaw) {
        Entity moved = BridgeTeleportSC.teleport(e, dim, x, y, z, yaw);
        if (moved != null) {
            moved.getEntityData().setLong("scBridgeCd", n.worldObj.getTotalWorldTime() + BridgeMathSC.TELEPORT_COOLDOWN);
            moved.fallDistance = 0;
            n.addPending(moved, seat);
        }
    }

    /**
     * A player to another dimension: not from inside the world's entity loop (we run in the old vehicle's tick) - the
     * server's transfer takes the player out of loadedEntityList, the index shifts and the loop then drops the wrong
     * entity for the dead vehicle. The player goes at the start of the next server tick; meanwhile its entry (marked
     * WAITING) already holds the new vehicle and its arrival message.
     */
    static void later(final EntityPlayerMP pl, final EntityVehicleSVA n, final int seat, final int dim, final double x,
            final double y, final double z, final float yaw) {
        final Object[] wait = new Object[] {pl, Integer.valueOf(seat), WAITING};
        n.addPending(pl, seat);
        n.pendingRiders.set(n.pendingRiders.size() - 1, wait);
        // dismounted at the vortex meanwhile: the bridge must not take it through on its own
        pl.getEntityData().setLong("scBridgeCd", pl.worldObj.getTotalWorldTime() + BridgeMathSC.TELEPORT_COOLDOWN);
        pl.fallDistance = 0;
        Runnable job = new Runnable() {
            @Override
            public void run() {
                wait[2] = null;
                if (pl.isDead || n.isDead) {
                    return;
                }
                Entity moved = BridgeTeleportSC.teleport(pl, dim, x, y, z, yaw);
                if (moved != null) {
                    moved.getEntityData().setLong("scBridgeCd", n.worldObj.getTotalWorldTime() + BridgeMathSC.TELEPORT_COOLDOWN);
                    moved.fallDistance = 0;
                    wait[0] = moved;
                }
            }
        };
        if (!ServerQueueSVA.INSTANCE.queue(pl, job)) {
            job.run();                                             // the player's queue is full: at once, as before
        }
    }

    /** The world of `dim` (loading it), the same one for its own dimension. */
    static WorldServer world(World from, int dim) {
        if (from.provider.dimensionId == dim && from instanceof WorldServer) {
            return (WorldServer) from;
        }
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null || !net.minecraftforge.common.DimensionManager.isDimensionRegistered(dim)) {
            return null;
        }
        return s.worldServerForDimension(dim);
    }

    /** Loads (generating if needed) the chunks within r of (x, z). */
    static void loadAround(WorldServer w, double x, double z, int r) {
        int cx = MathHelper.floor_double(x) >> 4, cz = MathHelper.floor_double(z) >> 4;
        for (int i = cx - r; i <= cx + r; i++) {
            for (int k = cz - r; k <= cz + r; k++) {
                w.theChunkProviderServer.loadChunk(i, k);
            }
        }
    }

    /**
     * The new vehicle's side, once a tick while riders wait: mounts each on its seat when the time has come, keeps
     * the others at their seats; repeats the attach to the players. Called by EntityVehicleSVA.serverUpdate.
     */
    static void tickPending(EntityVehicleSVA v) {
        v.pendingAge++;
        long now = v.worldObj.getTotalWorldTime();
        if (v.pendingRiders != null) {
            for (int k = v.pendingRiders.size() - 1; k >= 0; k--) {
                Object[] r = v.pendingRiders.get(k);
                Entity e = (Entity) r[0];
                int seat = ((Integer) r[1]).intValue();
                if (r.length > 2 && r[2] == WAITING && !e.isDead) {
                    continue;                                      // still in the old world (later)
                }
                if (e.isDead || e.worldObj != v.worldObj) {
                    v.pendingRiders.remove(k);
                    continue;
                }
                boolean player = e instanceof EntityPlayerMP;
                Entity mount = v.seatEntity(seat);
                if (v.pendingAge < (player ? MOUNT_PLAYER : MOUNT_MOB) || mount == null) {
                    double[] p = v.seatPos(seat);
                    if (!player) {
                        e.setPosition(p[0], p[1] + v.getMountedYOffset() + e.getYOffset(), p[2]);
                        e.motionX = e.motionY = e.motionZ = 0;
                    } else if (v.pendingAge == MOUNT_PLAYER / 2) {
                        ((EntityPlayerMP) e).playerNetServerHandler.setPlayerLocation(p[0], p[1] + v.getMountedYOffset(), p[2],
                                e.rotationYaw, e.rotationPitch);
                    }
                    e.fallDistance = 0;
                    continue;
                }
                if (mount.riddenByEntity != null && mount.riddenByEntity != e) {
                    mount = v.freeSeat();                          // taken meanwhile: any free seat
                }
                if (e.ridingEntity != null && e.ridingEntity != mount) {
                    e.mountEntity(null);
                }
                if (mount != null && e.ridingEntity == null) {
                    e.fallDistance = 0;
                    e.mountEntity(mount);
                    if (player) {
                        v.attachResend.add(new Object[] {e, Long.valueOf(now + RESEND)});
                    }
                }
                v.pendingRiders.remove(k);
            }
            if (v.pendingRiders.isEmpty() || v.pendingAge > PENDING_MAX) {
                v.pendingRiders = null;
            }
        }
        for (int k = v.attachResend.size() - 1; k >= 0; k--) {
            Object[] r = v.attachResend.get(k);
            if (((Long) r[1]).longValue() > now) {
                continue;
            }
            EntityPlayerMP p = (EntityPlayerMP) r[0];
            if (!p.isDead && p.ridingEntity != null && p.playerNetServerHandler != null) {
                p.playerNetServerHandler.sendPacket(new S1BPacketEntityAttach(0, p, p.ridingEntity));
            }
            v.attachResend.remove(k);
        }
    }
}
