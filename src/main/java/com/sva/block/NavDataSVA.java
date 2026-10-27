package com.sva.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.ZoneMath;
import com.sva.airport.EmbassyAirportSVA;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.NavDb;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/**
 * Liner wave 5: the pilots' own airports and waypoints stored from the MCDU's ДАННЫЕ pages - one per save (the
 * overworld's map storage, as IlsDataSVA), kept by dimension. The MCDU's whole database (NavDb) is built from these, the
 * ILS beacons (IlsDataSVA) and the air-defence «аэродром» zones on demand and cached for CACHE_TICKS per dimension (no
 * per-tick work, nothing synced: the server renders the MCDU's pages).
 * <p>
 * «Лётное поле посольства» (docs/plan-embassy-airfield-addon.md §3.7): the whole database names every embassy's virtual airport
 * (NavDb.SRC_EMBASSY, Airport.embassy - its ILS stations and entry fixes with it); db(..., viewers) hands out a copy without the
 * embassies none of the viewers knows (EmbassyAirportSVA.knownBy) - cached by the hidden set's signature beside the whole one, so a
 * viewer's new knowledge is a new key at once.
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
    /** Embassy airfield: per dimension, the copies without some embassies - by the hidden ids' signature (made again with the whole). */
    private final transient Map<Integer, Map<String, NavDb>> filtered = new HashMap<Integer, Map<String, NavDb>>();

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

    /** The database of world w's dimension `dim` (cached; `base` the «База» point or null) - embassy airfield: no embassy in it (nobody's). */
    public NavDb db(World w, int dim, int[] base) {
        return db(w, dim, base, null);
    }

    /**
     * Embassy airfield: the database for `viewers` (the MCDU's riders; null / empty: nobody) - the embassies none of them knows left out
     * (NavDb.without), the copy cached by that signature while the whole database lives.
     */
    public NavDb db(World w, int dim, int[] base, List<? extends EntityPlayer> viewers) {
        NavDb all = whole(w, dim, base);
        Set<Integer> emb = all.embassies();                         // made once a built database (review 2026-10-27: every MCDU tick asks)
        if (emb.isEmpty()) {
            return all;
        }
        Set<Integer> k1 = null, k2 = null;                          // the viewers' knowledge (two at most in practice: LAN)
        List<Set<Integer>> more = null;
        if (viewers != null) {
            for (EntityPlayer p : viewers) {
                Set<Integer> s = p == null ? null : EmbassyAirportSVA.INSTANCE.knownBy(p);
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (k1 == null) {
                    k1 = s;
                } else if (k2 == null) {
                    k2 = s;
                } else {
                    if (more == null) {
                        more = new ArrayList<Set<Integer>>();
                    }
                    more.add(s);
                }
            }
        }
        // the hidden ids' signature, in emb's order (none hidden: the whole database, nothing made)
        StringBuilder sig = null;
        for (Integer id : emb) {
            boolean seen = k1 != null && k1.contains(id) || k2 != null && k2.contains(id);
            for (int i = 0; !seen && more != null && i < more.size(); i++) {
                seen = more.get(i).contains(id);
            }
            if (!seen) {
                if (sig == null) {
                    sig = new StringBuilder();
                }
                sig.append(id.intValue()).append(',');
            }
        }
        if (sig == null) {
            return all;
        }
        Integer k = Integer.valueOf(dim);
        Map<String, NavDb> m = filtered.get(k);
        if (m == null) {
            m = new HashMap<String, NavDb>();
            filtered.put(k, m);
        }
        String key = sig.toString();
        NavDb f = m.get(key);
        if (f == null) {
            Set<Integer> hidden = new TreeSet<Integer>();
            for (String x : key.split(",")) {
                if (x.length() > 0) {
                    hidden.add(Integer.valueOf(x));
                }
            }
            f = all.without(hidden);
            m.put(key, f);
        }
        return f;
    }

    /** The whole database of world w's dimension `dim` (cached; `base` the «База» point or null) - every embassy in. */
    private NavDb whole(World w, int dim, int[] base) {
        Integer k = Integer.valueOf(dim);
        long now = w.getTotalWorldTime();
        Long at = cachedAt.get(k);
        NavDb db = cache.get(k);
        IlsDataSVA ils = IlsDataSVA.get(w);
        AirDefenseDataSVA ad = AirDefenseDataSVA.get(w);
        // the beacons / the zones changed: at once (their versions), else every CACHE_TICKS (the base point moves)
        AirportDataSVA apt = AirportDataSVA.get(w);
        int sig = (ils == null ? 0 : ils.version()) * 31 + (ad == null ? 0 : ad.version()) + (apt == null ? 0 : apt.version()) * 1009;
        Integer was = sigs.get(k);
        if (db == null || at == null || now - at.longValue() > CACHE_TICKS || now < at.longValue() || was == null || was.intValue() != sig) {
            sigs.put(k, Integer.valueOf(sig));
            List<LinerIls.Station> st = ils == null ? new ArrayList<LinerIls.Station>() : ils.near(dim, 0, 0, Double.MAX_VALUE / 4);
            List<ZoneMath.Zone> zones = ad == null ? null : ad.allExcept(AtcServerSVA.ZONE_KEY);
            db = NavDb.build(dim, st, zones, airports, wpts, base);
            // embassy airfield: an embassy's virtual ILS stations go with it (NavDb.without)
            for (LinerIls.Station s : st) {
                int e = EmbassyAirportSVA.embassyOf(s.key());
                if (e >= 0 && s.dim == dim) {
                    db.ilsEmbassy.put(s.key(), Integer.valueOf(e));
                }
            }
            // airport wave B1: the runways of two «Торец полосы» markers (with or without an ILS)
            if (apt != null) {
                for (com.sva.airport.IlsPairing.Rwy r : apt.runways()) {
                    if (r.a.dim != dim) {
                        continue;
                    }
                    for (int dir = 1; dir >= -1; dir -= 2) {
                        double[] t = r.threshold(dir);
                        db.addRunway(r.apt, dim, r.name(dir), t[0], t[1], (int) Math.round(r.elev), r.course(dir), (int) Math.round(r.len));
                    }
                }
                // airport wave B2: the towers name their airports and publish the ILS approaches and the entry fixes
                for (AirportDataSVA.Tower tw : apt.towers()) {
                    if (tw.dim != dim || tw.icao.length() != 4) {
                        continue;
                    }
                    NavDb.Airport a = db.airport(tw.icao);
                    if (a == null) {
                        double[] c = apt.centre(tw);
                        a = new NavDb.Airport();
                        a.ident = tw.icao;
                        a.dim = dim;
                        a.x = c[0];
                        a.z = c[1];
                        a.elev = tw.elev;
                        db.airports.add(a);
                    }
                    int emb = EmbassyAirportSVA.embassyOf(tw.key);  // embassy airfield: a virtual tower's airport - SRC_EMBASSY
                    a.src = emb >= 0 ? NavDb.SRC_EMBASSY : NavDb.SRC_TOWER;
                    a.embassy = emb;
                    a.name = tw.name;
                    a.apprs.clear();
                    a.apprs.addAll(apt.approaches(tw));
                    for (com.sva.airport.ApproachMath.Fix f : apt.entries(tw)) {
                        if (db.wpt(f.id) == null) {
                            NavDb.Wpt x = NavDb.wpt(f.id, emb >= 0 ? NavDb.SRC_EMBASSY : NavDb.SRC_TOWER, dim, f.x, f.z);
                            x.embassy = emb;
                            db.wpts.add(x);
                        }
                    }
                }
                db.sort();
            }
            cache.put(k, db);
            cachedAt.put(k, Long.valueOf(now));
            filtered.remove(k);                                     // embassy airfield: the copies of the old one go with it
        }
        return db;
    }

    /** Forget the built databases (a pilot's entry stored / deleted). */
    public void invalidate() {
        cache.clear();
        cachedAt.clear();
        filtered.clear();
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
