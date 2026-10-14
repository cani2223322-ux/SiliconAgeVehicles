package com.sva.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.ZoneMath;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.NavDb;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/**
 * Liner wave 5: the pilots' own airports and waypoints stored from the MCDU's ДАННЫЕ pages - one per save (the
 * overworld's map storage, as IlsDataSVA), kept by dimension. The MCDU's whole database (NavDb) is built from these, the
 * ILS beacons (IlsDataSVA) and the air-defence «аэродром» zones on demand and cached for CACHE_TICKS per dimension (no
 * per-tick work, nothing synced: the server renders the MCDU's pages).
 */
public class NavDataSVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_nav";
    /** The pilots' entries at most (per save) and the cache's life (ticks). */
    public static final int MAX = 256, CACHE_TICKS = 100;

    private final List<NavDb.Airport> airports = new ArrayList<NavDb.Airport>();
    private final List<NavDb.Wpt> wpts = new ArrayList<NavDb.Wpt>();

    /** The built databases per dimension and when (world time). */
    private final transient Map<Integer, NavDb> cache = new HashMap<Integer, NavDb>();
    private final transient Map<Integer, Long> cachedAt = new HashMap<Integer, Long>();
    private final transient Map<Integer, Integer> sigs = new HashMap<Integer, Integer>();

    public NavDataSVA(String name) {
        super(name);
    }

    public static NavDataSVA get(World w) {
        if (w == null || w.mapStorage == null) {
            return null;
        }
        NavDataSVA d = (NavDataSVA) w.mapStorage.loadData(NavDataSVA.class, NAME);
        if (d == null) {
            d = new NavDataSVA(NAME);
            w.mapStorage.setData(NAME, d);
        }
        return d;
    }

    /** The database of world w's dimension `dim` (cached; `base` the «База» point or null). */
    public NavDb db(World w, int dim, int[] base) {
        Integer k = Integer.valueOf(dim);
        long now = w.getTotalWorldTime();
        Long at = cachedAt.get(k);
        NavDb db = cache.get(k);
        IlsDataSVA ils = IlsDataSVA.get(w);
        AirDefenseDataSVA ad = AirDefenseDataSVA.get(w);
        // the beacons / the zones changed: at once (their versions), else every CACHE_TICKS (the base point moves)
        int sig = (ils == null ? 0 : ils.version()) * 31 + (ad == null ? 0 : ad.version());
        Integer was = sigs.get(k);
        if (db == null || at == null || now - at.longValue() > CACHE_TICKS || now < at.longValue() || was == null || was.intValue() != sig) {
            sigs.put(k, Integer.valueOf(sig));
            List<LinerIls.Station> st = ils == null ? new ArrayList<LinerIls.Station>() : ils.near(dim, 0, 0, Double.MAX_VALUE / 4);
            List<ZoneMath.Zone> zones = ad == null ? null : ad.all();
            db = NavDb.build(dim, st, zones, airports, wpts, base);
            cache.put(k, db);
            cachedAt.put(k, Long.valueOf(now));
        }
        return db;
    }

    /** Forget the built databases (a pilot's entry stored / deleted). */
    public void invalidate() {
        cache.clear();
        cachedAt.clear();
    }

    /** A pilot's waypoint stored (its ident replaces one of the same dimension); null done, else a message key. */
    public String putWpt(NavDb.Wpt w) {
        removeWpt(w.ident, w.dim);
        if (wpts.size() + airports.size() >= MAX) {
            return "sva.mcdu.msg.dbFull";
        }
        wpts.add(w);
        markDirty();
        invalidate();
        return null;
    }

    public String putApt(NavDb.Airport a) {
        removeApt(a.ident, a.dim);
        if (wpts.size() + airports.size() >= MAX) {
            return "sva.mcdu.msg.dbFull";
        }
        airports.add(a.copy());
        markDirty();
        invalidate();
        return null;
    }

    /** A pilot's entry deleted; null done, else a message key. */
    public String delete(String ident, boolean airport, int dim) {
        boolean r = airport ? removeApt(ident, dim) : removeWpt(ident, dim);
        if (!r) {
            return "sva.mcdu.msg.notAllowed";
        }
        markDirty();
        invalidate();
        return null;
    }

    private boolean removeWpt(String id, int dim) {
        for (int i = wpts.size() - 1; i >= 0; i--) {
            if (wpts.get(i).ident.equals(id) && wpts.get(i).dim == dim) {
                wpts.remove(i);
                return true;
            }
        }
        return false;
    }

    private boolean removeApt(String id, int dim) {
        for (int i = airports.size() - 1; i >= 0; i--) {
            if (airports.get(i).ident.equals(id) && airports.get(i).dim == dim) {
                airports.remove(i);
                return true;
            }
        }
        return false;
    }

    public int size() {
        return airports.size() + wpts.size();
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        airports.clear();
        wpts.clear();
        NBTTagList a = t.getTagList("Airports", 10);
        for (int i = 0; i < a.tagCount(); i++) {
            NavDb.Airport x = NavDb.Airport.read(a.getCompoundTagAt(i));
            if (x != null) {
                airports.add(x);
            }
        }
        NBTTagList w = t.getTagList("Waypoints", 10);
        for (int i = 0; i < w.tagCount(); i++) {
            NavDb.Wpt x = NavDb.Wpt.read(w.getCompoundTagAt(i));
            if (x != null) {
                wpts.add(x);
            }
        }
        invalidate();
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        NBTTagList a = new NBTTagList();
        for (NavDb.Airport x : airports) {
            NBTTagCompound e = new NBTTagCompound();
            x.write(e);
            a.appendTag(e);
        }
        t.setTag("Airports", a);
        NBTTagList w = new NBTTagList();
        for (NavDb.Wpt x : wpts) {
            NBTTagCompound e = new NBTTagCompound();
            x.write(e);
            w.appendTag(e);
        }
        t.setTag("Waypoints", w);
    }
}
