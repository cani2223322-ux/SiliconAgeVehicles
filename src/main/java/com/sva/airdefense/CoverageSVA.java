package com.sva.airdefense;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sva.ConfigSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.net.NetADSVA;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

/**
 * Wave A (plan-next §9 A9): the radars' coverage maps on the server - CoverageMath.Job per radar and height, run in slices
 * from AirDefenseSVA's tick (airdefense.coverageBudget beams a tick for all of them together, the oldest request first), kept
 * while the ground under the map is the same (its heights' signature, checked when the map is asked for again, at most once
 * in RECHECK ticks) and the radar stands where it stood (its key is its place), at most MAX_AGE old. A map goes, packed, only
 * to the players who asked (the command post's / the tower's open screen - NetADSVA D_COVER).
 */
public final class CoverageSVA {

    public static final CoverageSVA INSTANCE = new CoverageSVA();
    /** A finished map is checked against the ground at most this often (ticks), rebuilt anyway after MAX_AGE. */
    public static final int RECHECK = 100, MAX_AGE = 12000;
    /** Maps kept at most (the oldest go). */
    public static final int MAX_MAPS = 48;

    private static final class Entry {
        String key;
        World w;
        int agl;
        CoverageMath.Job job;
        byte[] grid;
        long sig, builtAt, checkedAt;
        int version;
        final List<EntityPlayerMP> waiting = new ArrayList<EntityPlayerMP>();
    }

    private final Map<String, Entry> maps = new LinkedHashMap<String, Entry>();

    private CoverageSVA() {
    }

    public void clear() {
        maps.clear();
    }

    /** The radar's reach for the map: a light plane's range with its overdrives, clear weather. */
    public static double reach(TileEntityRadarSVA r) {
        return RadarMath.detectRange(r.kind(), RadarMath.rcs(VehicleType.PLANE), 1, 1) * r.rangeMul();
    }

    /** The world as the map sees it: the ground (the top block that stops a beam, + 1) and the beam's blocks. */
    public static CoverageMath.Terrain terrain(final World w) {
        return new CoverageMath.Terrain() {
            @Override
            public int ground(int x, int z) {
                if (!w.blockExists(x, 64, z)) {
                    return Integer.MIN_VALUE;
                }
                int y = Math.min(255, w.getHeightValue(x, z));
                while (y > 0 && !AirDefenseSVA.beamStopper(w, x, y - 1, z)) {
                    if (w.getBlock(x, y - 1, z).getMaterial() == Material.water && y < 255) {
                        break;                                      // the sea's surface is the ground for a low flyer
                    }
                    y--;
                }
                return y;
            }

            @Override
            public boolean blocks(int x, int y, int z) {
                return AirDefenseSVA.beamStopper(w, x, y, z);
            }
        };
    }

    /** The antenna's point (as the radar's scan). */
    static double[] antenna(TileEntityRadarSVA r) {
        return new double[] {r.xCoord + 0.5, r.yCoord + AdShapeMath.antennaHeight(r.shape(), r.structState() == com.sva.block.TileEntityAirDefenseSVA.MULTI),
            r.zCoord + 0.5};
    }

    /**
     * A player wants the map of a radar at `agl`: sent at once when a fresh one is kept, else (re)computed and sent when
     * done (a progress note meanwhile). @return the map's state: 2 sent, 1 computing, 0 refused
     */
    public int request(EntityPlayerMP p, TileEntityRadarSVA r, int agl) {
        if (!ConfigSVA.adCoverage || r == null || r.isInvalid() || !CoverageMath.validAlt(agl)) {
            return 0;
        }
        World w = r.getWorldObj();
        long now = w.getTotalWorldTime();
        String key = r.key() + "|" + agl;
        Entry e = maps.get(key);
        double[] a = antenna(r);
        double range = reach(r);
        if (e != null && e.grid != null && e.job == null) {
            boolean stale = now - e.builtAt > MAX_AGE || now < e.builtAt;
            if (!stale && now - e.checkedAt >= RECHECK) {
                e.checkedAt = now;
                stale = CoverageMath.signature(terrain(w), a[0], a[2], range) != e.sig;
            }
            if (!stale) {
                send(p, e, r, range);
                return 2;
            }
        }
        if (e == null) {
            e = new Entry();
            e.key = key;
            maps.put(key, e);
            while (maps.size() > MAX_MAPS) {
                Iterator<String> it = maps.keySet().iterator();
                it.next();
                it.remove();
            }
        }
        e.w = w;
        e.agl = agl;
        if (e.job == null) {
            e.job = new CoverageMath.Job(a[0], a[1], a[2], range, agl);
        }
        if (!e.waiting.contains(p)) {
            e.waiting.add(p);
        }
        progress(p, r, agl, e.job.percent());
        return 1;
    }

    /** The world test: the finished map of a radar at a height (null while computing / none). */
    public byte[] grid(TileEntityRadarSVA r, int agl) {
        Entry e = maps.get(r.key() + "|" + agl);
        return e == null || e.job != null ? null : e.grid;
    }

    /** One slice of the oldest map being computed (`budget` beams); the world test calls it too. */
    public void run(int budget) {
        for (Iterator<Entry> it = maps.values().iterator(); it.hasNext();) {
            Entry e = it.next();
            if (e.job == null) {
                continue;
            }
            if (e.w == null) {
                it.remove();
                continue;
            }
            CoverageMath.Terrain t = terrain(e.w);
            boolean done = e.job.step(t, Math.max(1, budget));
            if (done) {
                e.grid = e.job.grid;
                e.sig = CoverageMath.signature(t, e.job.ax, e.job.az, e.job.range);
                e.builtAt = e.checkedAt = e.w.getTotalWorldTime();
                e.version++;
                CoverageMath.Job j = e.job;
                e.job = null;
                for (EntityPlayerMP p : e.waiting) {
                    if (p != null && !p.isDead) {
                        sendRaw(p, e, j.ax, j.az, j.range);
                    }
                }
                e.waiting.clear();
            }
            return;                                                 // one map a tick: the oldest request first
        }
    }

    /** AirDefenseSVA's tick. */
    public void tick() {
        if (!ConfigSVA.adCoverage || maps.isEmpty()) {
            return;
        }
        run(ConfigSVA.adCoverageBudget);
    }

    private void send(EntityPlayerMP p, Entry e, TileEntityRadarSVA r, double range) {
        double[] a = antenna(r);
        sendRaw(p, e, a[0], a[2], range);
    }

    private static void sendRaw(EntityPlayerMP p, Entry e, double ax, double az, double range) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString("K", e.key);
        t.setInteger("A", e.agl);
        t.setDouble("X", ax);
        t.setDouble("Z", az);
        t.setDouble("R", range);
        t.setByteArray("G", e.grid);
        t.setInteger("V", e.version);
        NetADSVA.send(p, NetADSVA.D_COVER, t);
    }

    private static void progress(EntityPlayerMP p, TileEntityRadarSVA r, int agl, int percent) {
        NBTTagCompound t = new NBTTagCompound();
        t.setString("K", r.key() + "|" + agl);
        t.setInteger("A", agl);
        t.setInteger("P", percent);
        NetADSVA.send(p, NetADSVA.D_COVER, t);
    }
}
