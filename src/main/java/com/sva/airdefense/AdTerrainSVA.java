package com.sva.airdefense;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sva.ConfigSVA;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

/**
 * Audit 2026-10-22 (bug 10): the air defence's terrain memory on the server (TerrainMath, AdTerrainDataSVA). A chunk near
 * an air-defence site (AirDefenseDataSVA.sites - a radar within airdefense.terrainCacheRadius, a launcher within its
 * missile's reach) leaves its 256 columns' ground behind:
 * <ul>
 * <li>when it loads and when it unloads (ChunkEvent - the Forge bus);</li>
 * <li>when a block in it changes (the world's IWorldAccess: markBlockForUpdate - a change in a chunk near a player - and
 * markBlockForRenderUpdate - a change of light, so a block placed or broken out in the open far from players too; these
 * are read again first, a few chunks a tick);</li>
 * <li>when the sites change (a radar / launcher placed), the chunks loaded round them not remembered yet are read in what
 * the budget leaves;</li>
 * <li>and slowly round all the time: one loaded remembered chunk a tick is read again anyway (a change no hook saw).</li>
 * </ul>
 * The beams (AirDefenseSVA.BeamProbe), the shells (its ShotProbe) and the coverage map read an unloaded column from it.
 * Chunks no site reaches any more are forgotten every PRUNE_TICKS.
 */
public final class AdTerrainSVA {

    public static final AdTerrainSVA INSTANCE = new AdTerrainSVA();
    /** Chunks read again a tick at most (all dimensions); the forgetting's period. */
    public static final int BUDGET = 8, PRUNE_TICKS = 6000;

    /** Per dimension: the chunks a block changed in (read first), the chunks round new sites (then). */
    private final Map<Integer, Set<Long>> dirty = new HashMap<Integer, Set<Long>>(), sweep = new HashMap<Integer, Set<Long>>();
    private final Map<Integer, Integer> sitesSeen = new HashMap<Integer, Integer>();
    /** The slow round per dimension: the remembered chunks' keys and how far through them it is. */
    private final Map<Integer, List<Long>> round = new HashMap<Integer, List<Long>>();
    private final Map<Integer, Integer> roundAt = new HashMap<Integer, Integer>();

    private AdTerrainSVA() {
    }

    public static boolean on() {
        return ConfigSVA.adTerrainCache && ConfigSVA.adTerrainRadius > 0;
    }

    public void clear() {
        dirty.clear();
        sweep.clear();
        sitesSeen.clear();
        round.clear();
        roundAt.clear();
    }

    /** An unloaded chunk's columns (null: not known, or the memory off). */
    public byte[] column(World w, int cx, int cz) {
        if (!on() || w == null || w.isRemote) {
            return null;
        }
        AdTerrainDataSVA d = AdTerrainDataSVA.get(w);
        return d == null ? null : d.column(w.provider.dimensionId, cx, cz);
    }

    /** World tests: a column's remembered ground (-1 none). */
    public int remembered(World w, int x, int z) {
        return TerrainMath.height(column(w, x >> 4, z >> 4), x, z);
    }

    /** The chunks a dimension's sites reach (the cache radius from the config). */
    private static boolean wanted(World w, int cx, int cz) {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        int r = ConfigSVA.adTerrainRadius;
        return d != null && TerrainMath.near(cx, cz, d.sites(w.provider.dimensionId, r), r);
    }

    /** Reads a loaded chunk's columns into the memory (when a site reaches it). @return it was read */
    public boolean capture(World w, Chunk c) {
        if (!on() || w == null || w.isRemote || c == null || !wanted(w, c.xPosition, c.zPosition)) {
            return false;
        }
        AdTerrainDataSVA d = AdTerrainDataSVA.get(w);
        if (d == null) {
            return false;
        }
        d.put(w.provider.dimensionId, c.xPosition, c.zPosition, columns(w, c));
        return true;
    }

    /**
     * The 256 columns' ground of a loaded chunk (AirDefenseSVA.BeamProbe.ground: the coverage map's rule) and the bottom of
     * each one's top solid layer (audit review 2026-10-22: what is under it is not known - clear).
     */
    static byte[] columns(World w, Chunk c) {
        byte[] out = new byte[TerrainMath.BYTES];
        AirDefenseSVA.BeamProbe p = new AirDefenseSVA.BeamProbe(w);
        int bx = c.xPosition << 4, bz = c.zPosition << 4;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int[] g = p.layer(bx + lx, bz + lz);
                TerrainMath.put(out, lx, lz, g == null ? 0 : g[0], g == null ? 0 : g[1]);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the events

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load e) {
        if (e.world instanceof WorldServer && !e.world.isRemote) {
            e.world.addWorldAccess(new Watcher(e.world));
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load e) {
        if (e.world != null && !e.world.isRemote) {
            capture(e.world, e.getChunk());
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload e) {
        if (e.world != null && !e.world.isRemote) {
            capture(e.world, e.getChunk());
            Long k = Long.valueOf(TerrainMath.key(e.getChunk().xPosition, e.getChunk().zPosition));
            for (Map<Integer, Set<Long>> m : java.util.Arrays.asList(dirty, sweep)) {
                Set<Long> q = m.get(Integer.valueOf(e.world.provider.dimensionId));
                if (q != null) {
                    q.remove(k);
                }
            }
        }
    }

    private static Set<Long> queue(Map<Integer, Set<Long>> m, int dim) {
        Set<Long> q = m.get(Integer.valueOf(dim));
        if (q == null) {
            q = new LinkedHashSet<Long>();
            m.put(Integer.valueOf(dim), q);
        }
        return q;
    }

    /** A block changed in a remembered chunk: read it again soon. */
    void changed(World w, int dim, int x, int z) {
        if (!on() || w.isRemote) {
            return;
        }
        AdTerrainDataSVA d = AdTerrainDataSVA.get(w);
        long k = TerrainMath.key(x >> 4, z >> 4);
        if (d != null && d.has(dim, k)) {
            queue(dirty, dim).add(Long.valueOf(k));
        }
    }

    /** AirDefenseSVA's tick: the sites' change, a few chunks read again, now and then the forgetting. */
    public void tick(int ticks) {
        MinecraftServer s = MinecraftServer.getServer();
        if (!on() || s == null || s.worldServers == null) {
            return;
        }
        int budget = BUDGET;
        for (WorldServer w : s.worldServers) {
            if (w == null) {
                continue;
            }
            int dim = w.provider.dimensionId;
            AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
            if (d == null) {
                continue;
            }
            Integer seen = sitesSeen.get(Integer.valueOf(dim));
            if (seen == null || seen.intValue() != d.nodesVersion()) {
                sitesSeen.put(Integer.valueOf(dim), Integer.valueOf(d.nodesVersion()));
                enqueueLoaded(w);
            }
            budget = read(w, dirty.get(Integer.valueOf(dim)), budget);
            budget = read(w, sweep.get(Integer.valueOf(dim)), budget);
            roundOnce(w);
            if (ticks % PRUNE_TICKS == 0) {
                prune(w);
            }
        }
    }

    /** Reads the queue's loaded chunks while the budget lasts (an unloaded one was read when it unloaded). @return the budget left */
    private int read(WorldServer w, Set<Long> q, int budget) {
        for (Iterator<Long> it = q == null ? null : q.iterator(); it != null && it.hasNext() && budget > 0;) {
            long k = it.next().longValue();
            it.remove();
            int cx = TerrainMath.keyX(k), cz = TerrainMath.keyZ(k);
            if (w.getChunkProvider().chunkExists(cx, cz)) {
                capture(w, w.getChunkFromChunkCoords(cx, cz));
                budget--;
            }
        }
        return budget;
    }

    /** The slow round: the next loaded remembered chunk of this world read again (up to 64 unloaded ones passed over). */
    private void roundOnce(WorldServer w) {
        AdTerrainDataSVA d = AdTerrainDataSVA.get(w);
        if (d == null) {
            return;
        }
        Integer dim = Integer.valueOf(w.provider.dimensionId);
        List<Long> r = round.get(dim);
        Integer at = roundAt.get(dim);
        int i = at == null ? 0 : at.intValue();
        if (r == null || i >= r.size()) {
            r = d.keys(dim.intValue());
            round.put(dim, r);
            i = 0;
        }
        for (int n = 0; n < 64 && i < r.size(); n++) {
            long k = r.get(i++).longValue();
            int cx = TerrainMath.keyX(k), cz = TerrainMath.keyZ(k);
            if (w.getChunkProvider().chunkExists(cx, cz)) {
                capture(w, w.getChunkFromChunkCoords(cx, cz));
                break;
            }
        }
        roundAt.put(dim, Integer.valueOf(i));
    }

    /** The loaded chunks a site reaches and not remembered yet go into the sweep (a site placed: its surroundings). */
    private void enqueueLoaded(WorldServer w) {
        AdTerrainDataSVA d = AdTerrainDataSVA.get(w);
        if (d == null || !(w.getChunkProvider() instanceof ChunkProviderServer)) {
            return;
        }
        int dim = w.provider.dimensionId;
        Set<Long> q = queue(sweep, dim);
        for (Object o : ((ChunkProviderServer) w.getChunkProvider()).loadedChunks) {
            Chunk c = (Chunk) o;
            long k = TerrainMath.key(c.xPosition, c.zPosition);
            if (!d.has(dim, k) && wanted(w, c.xPosition, c.zPosition)) {
                q.add(Long.valueOf(k));
            }
        }
    }

    /** Forgets the chunks of this world no site reaches any more. */
    public void prune(World w) {
        AdTerrainDataSVA d = AdTerrainDataSVA.get(w);
        if (d == null) {
            return;
        }
        int dim = w.provider.dimensionId;
        List<Long> keys = d.keys(dim);
        for (Long k : keys) {
            if (!wanted(w, TerrainMath.keyX(k.longValue()), TerrainMath.keyZ(k.longValue()))) {
                d.remove(dim, k.longValue());
            }
        }
    }

    /** World tests: the chunks waiting to be read in a dimension. */
    public int queued(int dim) {
        Set<Long> q = dirty.get(Integer.valueOf(dim)), r = sweep.get(Integer.valueOf(dim));
        return (q == null ? 0 : q.size()) + (r == null ? 0 : r.size());
    }

    /** A world's block changes (the server's): only markBlockForUpdate counts - every change a player can see goes through it. */
    private final class Watcher implements IWorldAccess {
        private final World w;

        Watcher(World w) {
            this.w = w;
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {
            changed(w, w.provider.dimensionId, x, z);
        }

        @Override
        public void markBlockForRenderUpdate(int x, int y, int z) {
            changed(w, w.provider.dimensionId, x, z);               // a light change: a block placed / broken in the open
        }

        @Override
        public void markBlockRangeForRenderUpdate(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int cx = Math.min(x0, x1) >> 4, n = 0; cx <= Math.max(x0, x1) >> 4 && n < 64; cx++) {
                for (int cz = Math.min(z0, z1) >> 4; cz <= Math.max(z0, z1) >> 4 && n < 64; cz++, n++) {
                    changed(w, w.provider.dimensionId, cx << 4, cz << 4);
                }
            }
        }

        @Override
        public void playSound(String sound, double x, double y, double z, float volume, float pitch) {
        }

        @Override
        public void playSoundToNearExcept(EntityPlayer p, String sound, double x, double y, double z, float volume, float pitch) {
        }

        @Override
        public void spawnParticle(String name, double x, double y, double z, double vx, double vy, double vz) {
        }

        @Override
        public void onEntityCreate(Entity e) {
        }

        @Override
        public void onEntityDestroy(Entity e) {
        }

        @Override
        public void playRecord(String record, int x, int y, int z) {
        }

        @Override
        public void broadcastSound(int id, int x, int y, int z, int data) {
        }

        @Override
        public void playAuxSFX(EntityPlayer p, int type, int x, int y, int z, int data) {
        }

        @Override
        public void destroyBlockPartially(int breaker, int x, int y, int z, int progress) {
        }

        @Override
        public void onStaticEntitiesChanged() {
        }
    }
}
