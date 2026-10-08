package com.sva.net;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Packets arrive on the netty thread: their work is queued here and run on the server thread at the start
 * of the next tick (never touching the world from netty). At most MAX_PENDING tasks per player wait at once;
 * a flood beyond that is dropped (the same pattern as the core's BladeSingularSC.queueTask).
 */
public final class ServerQueueSVA {

    public static final int MAX_PENDING = 24;
    public static final ServerQueueSVA INSTANCE = new ServerQueueSVA();

    private final ConcurrentLinkedQueue<Object[]> pending = new ConcurrentLinkedQueue<Object[]>();
    private final ConcurrentHashMap<UUID, AtomicInteger> counts = new ConcurrentHashMap<UUID, AtomicInteger>();

    private ServerQueueSVA() {
    }

    /** @return false when dropped (flood) */
    public boolean queue(EntityPlayerMP p, Runnable task) {
        if (p == null || task == null) {
            return false;
        }
        AtomicInteger n = counts.get(p.getUniqueID());
        if (n == null) {
            AtomicInteger fresh = new AtomicInteger();
            n = counts.putIfAbsent(p.getUniqueID(), fresh);
            if (n == null) {
                n = fresh;
            }
        }
        if (n.incrementAndGet() > MAX_PENDING) {
            n.decrementAndGet();
            return false;
        }
        pending.add(new Object[] {p, task});
        return true;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.START) {
            return;
        }
        Object[] job;
        while ((job = pending.poll()) != null) {
            EntityPlayerMP p = (EntityPlayerMP) job[0];
            AtomicInteger n = counts.get(p.getUniqueID());
            if (n != null) {
                n.decrementAndGet();
            }
            if (!online(p)) {
                continue;                                   // logged out / dead / respawned since the packet came
            }
            try {
                ((Runnable) job[1]).run();
            } catch (RuntimeException ex) {
                cpw.mods.fml.common.FMLLog.warning("[SVA] packet task failed: %s", ex);
            }
        }
    }

    /** Fires before vanilla saves the player and removes his mount: a rider gets off beside the vehicle / seat. */
    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        com.sva.entity.EntityVehicleSVA.riderLoggedOut(e.player);
    }

    private static boolean online(EntityPlayerMP p) {
        if (p.isDead || p.getHealth() <= 0 || p.playerNetServerHandler == null || p.worldObj == null) {
            return false;
        }
        net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer.getServer();
        return srv != null && srv.getConfigurationManager() != null
            && srv.getConfigurationManager().playerEntityList.contains(p);
    }

    public void clear() {
        pending.clear();
        counts.clear();
        NetSVA.MapRequestHandler.CACHE.clear();
    }
}
