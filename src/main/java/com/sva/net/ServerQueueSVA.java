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
 * ТН-6: the driver's stream packets (Input, Move) don't queue: only the latest of each kind per player is kept
 * (latest) and run at the start of the tick, before the queue - so a lagging server neither replays stale moves nor
 * fills the queue with them, and MAX_PENDING only limits the buttons (PickUp, Action, AccessEdit, Station, Garage...).
 */
public final class ServerQueueSVA {

    public static final int MAX_PENDING = 24;
    /** ТН-6: the kinds kept as «the latest only» (latest). */
    public static final int LATEST_INPUT = 0, LATEST_MOVE = 1, LATEST_KINDS = 2;
    public static final ServerQueueSVA INSTANCE = new ServerQueueSVA();

    private final ConcurrentLinkedQueue<Object[]> pending = new ConcurrentLinkedQueue<Object[]>();
    private final ConcurrentHashMap<UUID, AtomicInteger> counts = new ConcurrentHashMap<UUID, AtomicInteger>();
    /** ТН-6: per kind, per player: {player, task} of the newest packet (a newer one replaces it). */
    private final java.util.List<ConcurrentHashMap<UUID, Object[]>> latest = new java.util.ArrayList<ConcurrentHashMap<UUID, Object[]>>();

    private ServerQueueSVA() {
        for (int k = 0; k < LATEST_KINDS; k++) {
            latest.add(new ConcurrentHashMap<UUID, Object[]>());
        }
    }

    /** ТН-6: keeps only this task as the player's latest of `kind` (run at the next tick's start; replaces an older one). */
    public void latest(EntityPlayerMP p, int kind, Runnable task) {
        if (p == null || task == null || kind < 0 || kind >= LATEST_KINDS) {
            return;
        }
        latest.get(kind).put(p.getUniqueID(), new Object[] {p, task});
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
        for (int k = 0; k < LATEST_KINDS; k++) {                   // ТН-6: the input first, then the move, as the client sends them
            ConcurrentHashMap<UUID, Object[]> m = latest.get(k);
            for (UUID id : new java.util.ArrayList<UUID>(m.keySet())) {
                Object[] last = m.remove(id);
                if (last != null) {
                    run((EntityPlayerMP) last[0], (Runnable) last[1]);
                }
            }
        }
        Object[] job;
        while ((job = pending.poll()) != null) {
            EntityPlayerMP p = (EntityPlayerMP) job[0];
            AtomicInteger n = counts.get(p.getUniqueID());
            if (n != null) {
                n.decrementAndGet();
            }
            run(p, (Runnable) job[1]);
        }
    }

    private static void run(EntityPlayerMP p, Runnable task) {
        if (!online(p)) {
            return;                                         // logged out / dead / respawned since the packet came
        }
        try {
            task.run();
        } catch (RuntimeException ex) {
            cpw.mods.fml.common.FMLLog.warning("[SVA] packet task failed: %s", ex);
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
        for (ConcurrentHashMap<UUID, Object[]> m : latest) {
            m.clear();
        }
        NetSVA.MapRequestHandler.CACHE.clear();
    }
}
