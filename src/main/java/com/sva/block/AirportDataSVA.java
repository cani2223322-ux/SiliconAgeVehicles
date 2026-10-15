package com.sva.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.airport.FlightCheck;
import com.sva.airport.IlsBeam;
import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.airport.MarkerMath;
import com.sva.airport.RunwayLayout;
import com.sva.airport.RvrMath;
import com.sva.airport.WindField;
import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.LinerIls;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.biome.BiomeGenBase;

/**
 * Airport wave B1 (plan-next §9, items 13-26, 39, 40): every airport thing placed in the save - the «Торец полосы» markers,
 * the localizer arrays and glide slope masts, the marker beacons, the RVR transmissometers, the weather stations and
 * windsocks - one per save (the overworld's map storage, as IlsDataSVA), keyed "dim:x:y:z", so the stations work and the
 * aircraft find them wherever their chunks are. The blocks put themselves in when placed / set and take themselves out
 * when broken.
 * <p>
 * From it (rebuild(), pure airport.IlsPairing): the runways (two markers each, named «27L»...), the airports (ICAO
 * placeholders), the ILS stations (a localizer over a runway end + its glide slope mast) - published into IlsDataSVA as
 * FULL stations (the liners' tuning, the MCDU's database read that list as before); the markers' types; the RVR sensors'
 * places (TDZ / MID / END).
 * <p>
 * tick() (block.AirportServerSVA, the server's tick, per dimension):
 * <ul>
 * <li>the monitor (airport.IlsCat) every STATUS_TICKS: the placement, the shelters' reports (power + monitor; an antenna
 * whose chunk is unloaded keeps its last state), the critical / sensitive areas - their blocks scanned every SCAN_TICKS
 * (staggered, only loaded chunks), the entities by AABB queries every STATUS_TICKS - the category, the flags, the beam's
 * bends; a change goes into IlsDataSVA (only then: no save churn) and to the clients;</li>
 * <li>the flight inspection (airport.FlightCheck) every FlightCheck.SAMPLE_TICKS for the stations that wait for one: the
 * aircraft with a transponder on in the beam;</li>
 * <li>the RVR (airport.RvrMath) and the weather stations' QNH every WX_TICKS.</li>
 * </ul>
 * The stored status (the category, the flags, the inspection, the RVR) is what the wave B2 tower will read (status()).
 */
public class AirportDataSVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_airport";
    /** Device kinds (block.BlockAptDeviceSVA's metadata). */
    public static final int K_MARKER = 0, K_RVR = 1, K_WX = 2, K_SOCK = 3, KINDS = 4;
    public static final int STATUS_TICKS = 20, SCAN_TICKS = 100, WX_TICKS = 100;
    /** A shelter's report is good this long (ticks). */
    public static final int REPORT_TICKS = 60;
    /** Markers / weather stations are looked for this far (blocks). */
    public static final double MARKER_RANGE = 1200, WX_RANGE = 3000;
    /** The anemometer's height (blocks over the ground). */
    public static final double WX_HEIGHT = 1;

    /** A device: kind, place; derived: a marker's type and its course, an RVR sensor's runway / along. */
    public static final class Device {
        public String key = "";
        public int dim, kind;
        public double x, y, z;
        /** Derived (rebuild): a marker's type (MarkerMath, NONE: not on a centre line) and course; an RVR's runway and along. */
        public int type = MarkerMath.NONE;
        public double course, along;
        public String rwy = "";
        /** RVR (m-eq, -1 not yet), the weather station's QNH (lagged, NaN not yet) and its last readings. */
        public int rvr = -1;
        public double qnh = Double.NaN, windFrom, windSpeed, windGust, temp;
    }

    private final Map<String, IlsPairing.End> ends = new LinkedHashMap<String, IlsPairing.End>();
    private final Map<String, IlsPairing.LocAnt> locs = new LinkedHashMap<String, IlsPairing.LocAnt>();
    private final Map<String, IlsPairing.GsAnt> gss = new LinkedHashMap<String, IlsPairing.GsAnt>();
    private final Map<String, Device> devices = new LinkedHashMap<String, Device>();
    /** Per localizer key: its flight inspection and the geometry it was flown on. */
    private final Map<String, FlightCheck> checks = new HashMap<String, FlightCheck>();
    private final Map<String, Integer> checkedGeo = new HashMap<String, Integer>();

    // derived / transient
    private transient boolean dirty = true;
    private transient int version;
    private transient List<IlsPairing.Rwy> runways = new ArrayList<IlsPairing.Rwy>();
    private transient List<IlsPairing.Pair> pairs = new ArrayList<IlsPairing.Pair>();
    private transient final Map<String, LinerIls.Station> stations = new LinkedHashMap<String, LinerIls.Station>();
    private transient final Map<String, IlsCat.Result> results = new HashMap<String, IlsCat.Result>();
    /** Per station: {LOC critical blocks, G/S critical blocks, LOC sensitive, G/S sensitive} and the same of entities. */
    private transient final Map<String, int[]> occBlocks = new HashMap<String, int[]>(), occEntities = new HashMap<String, int[]>();
    /** Per antenna key: the last shelter report's tick and whether it was powered. */
    private transient final Map<String, long[]> reports = new HashMap<String, long[]>();
    private transient final Set<String> conflicts = new HashSet<String>();

    public AirportDataSVA(String name) {
        super(name);
    }

    public static AirportDataSVA get(World w) {
        if (w == null || w.mapStorage == null) {
            return null;
        }
        AirportDataSVA d = (AirportDataSVA) w.mapStorage.loadData(AirportDataSVA.class, NAME);
        if (d == null) {
            d = new AirportDataSVA(NAME);
            w.mapStorage.setData(NAME, d);
        }
        return d;
    }

    public static String key(World w, int x, int y, int z) {
        return (w == null ? 0 : w.provider.dimensionId) + ":" + x + ":" + y + ":" + z;
    }

    /** Bumped on every change the clients should see (AirportServerSVA syncs them). */
    public int version() {
        return version;
    }

    private void changed() {
        dirty = true;
        version++;
        markDirty();
    }

    // ---------------------------------------------------------------- registration (the blocks)

    public void putEnd(IlsPairing.End e) {
        ends.put(e.key, e);
        changed();
    }

    public void putLoc(IlsPairing.LocAnt l) {
        IlsPairing.LocAnt old = locs.get(l.key);
        if (old != null) {
            l.monitor = old.monitor;
            l.power = old.power;
        }
        locs.put(l.key, l);
        changed();
    }

    public void putGs(IlsPairing.GsAnt g) {
        IlsPairing.GsAnt old = gss.get(g.key);
        if (old != null) {
            g.monitor = old.monitor;
            g.power = old.power;
        }
        gss.put(g.key, g);
        changed();
    }

    public void putDevice(Device d) {
        devices.put(d.key, d);
        changed();
    }

    /** A block of any kind gone (its key). */
    public void remove(String key) {
        boolean any = ends.remove(key) != null | locs.remove(key) != null | gss.remove(key) != null | devices.remove(key) != null;
        if (any) {
            checks.remove(key);
            checkedGeo.remove(key);
            changed();
        }
    }

    public IlsPairing.End end(String key) {
        return ends.get(key);
    }

    public IlsPairing.LocAnt loc(String key) {
        return locs.get(key);
    }

    public IlsPairing.GsAnt gs(String key) {
        return gss.get(key);
    }

    public Device device(String key) {
        return devices.get(key);
    }

    /** An antenna's shelter reported in (powered or not) at world tick `now`. */
    public void shelterReport(String antennaKey, boolean powered, long now) {
        reports.put(antennaKey, new long[] {now, powered ? 1 : 0});
    }

    // ---------------------------------------------------------------- derived

    public List<IlsPairing.Rwy> runways() {
        return runways;
    }

    public List<IlsPairing.Pair> pairs() {
        return pairs;
    }

    /** The FULL station of a localizer key (null: not paired). */
    public LinerIls.Station station(String locKey) {
        return stations.get(locKey);
    }

    /** The monitor's last result for a localizer key (null none). */
    public IlsCat.Result result(String locKey) {
        return results.get(locKey);
    }

    public FlightCheck check(String locKey) {
        FlightCheck c = checks.get(locKey);
        if (c == null) {
            c = new FlightCheck();
            checks.put(locKey, c);
        }
        return c;
    }

    /** The pair of a localizer key, or null. */
    public IlsPairing.Pair pair(String locKey) {
        for (IlsPairing.Pair p : pairs) {
            if (p.key().equals(locKey)) {
                return p;
            }
        }
        return null;
    }

    /** The runway a marker end belongs to (null: unpaired). */
    public IlsPairing.Rwy runwayOf(String endKey) {
        for (IlsPairing.Rwy r : runways) {
            if (r.a.key.equals(endKey) || r.b.key.equals(endKey)) {
                return r;
            }
        }
        return null;
    }

    public boolean freqConflict(String locKey) {
        return conflicts.contains(locKey);
    }

    public List<Device> devices(int dim, int kind) {
        List<Device> l = new ArrayList<Device>();
        for (Device d : devices.values()) {
            if (d.dim == dim && d.kind == kind) {
                l.add(d);
            }
        }
        return l;
    }

    /** The geometry's signature of a pair (a change resets the flight inspection). */
    static int geoHash(IlsPairing.Pair p) {
        long h = Math.round(p.r.a.x * 2) * 31 + Math.round(p.r.a.z * 2) * 37 + Math.round(p.r.b.x * 2) * 41 + Math.round(p.r.b.z * 2) * 43
                + Math.round(p.loc.x * 2) * 47 + Math.round(p.loc.z * 2) * 53 + p.dir * 59;
        if (p.gs != null) {
            h += Math.round(p.gs.x * 2) * 61 + Math.round(p.gs.z * 2) * 67 + Math.round(p.gs.angle * 10) * 71;
        }
        return (int) (h ^ h >>> 32);
    }

    /** The pairing again (a block changed): runways, airports, stations, markers' types, RVR places; IlsDataSVA updated. */
    public void rebuild(World w) {
        dirty = false;
        IlsPairing.magVar = ConfigSVA.aptMagVar;
        runways = IlsPairing.runways(new ArrayList<IlsPairing.End>(ends.values()));
        IlsPairing.airports(runways);
        pairs = IlsPairing.pair(runways, new ArrayList<IlsPairing.LocAnt>(locs.values()), new ArrayList<IlsPairing.GsAnt>(gss.values()));
        conflicts.clear();
        for (String[] c : IlsPairing.conflicts(pairs)) {
            conflicts.add(c[0]);
            conflicts.add(c[1]);
        }
        // the markers: the type of the window they stand in before a paired threshold (any runway end)
        for (Device d : devices.values()) {
            d.type = MarkerMath.NONE;
            d.rwy = "";
            if (d.kind == K_MARKER) {
                for (IlsPairing.Rwy r : runways) {
                    if (r.a.dim != d.dim) {
                        continue;
                    }
                    for (int dir = 1; dir >= -1; dir -= 2) {
                        double[] l = r.local(dir, d.x, d.z);
                        int t = MarkerMath.classify(-l[0], l[1]);
                        if (t != MarkerMath.NONE && d.type == MarkerMath.NONE) {
                            d.type = t;
                            d.course = r.course(dir);
                            d.rwy = r.end(dir).key;
                        }
                    }
                }
            } else if (d.kind == K_RVR) {
                for (IlsPairing.Rwy r : runways) {
                    double[] l = r.local(1, d.x, d.z);
                    if (r.a.dim == d.dim && l[0] >= -10 && l[0] <= r.len + 10 && Math.abs(l[1]) <= r.width / 2.0 + 25) {
                        d.rwy = r.key();
                        d.along = l[0];
                        break;
                    }
                }
            }
        }
        // the stations
        Map<String, LinerIls.Station> fresh = new LinkedHashMap<String, LinerIls.Station>();
        for (IlsPairing.Pair p : pairs) {
            LinerIls.Station s = IlsPairing.station(p);
            for (Device d : devices.values()) {
                if (d.kind == K_MARKER && d.type != MarkerMath.NONE && d.rwy.equals(p.r.end(p.dir).key)) {
                    double[] l = p.r.local(p.dir, d.x, d.z);
                    s.markers[d.type] = (int) Math.round(-l[0]);
                }
            }
            int geo = geoHash(p);
            Integer was = checkedGeo.get(p.key());
            if (was == null || was.intValue() != geo) {
                check(p.key()).reset();                             // a new / changed station: a new flight inspection
                checkedGeo.put(p.key(), Integer.valueOf(geo));
            }
            LinerIls.Station old = stations.get(p.key());
            if (old != null) {
                s.cat = old.cat;
                s.flags = old.flags | (s.flags & LinerIls.F_NO_GS);
                s.bendLoc = old.bendLoc;
                s.bendGs = old.bendGs;
            } else {
                s.cat = 0;
                s.flags |= LinerIls.F_UNCAL;
            }
            s.calib = check(p.key()).pack();
            fresh.put(p.key(), s);
        }
        stations.clear();
        stations.putAll(fresh);
        publish(w, true);
    }

    /** Puts the FULL stations into IlsDataSVA and takes out the gone ones (all = every station; else the changed only). */
    private void publish(World w, boolean all) {
        IlsDataSVA d = IlsDataSVA.get(w);
        if (d == null) {
            return;
        }
        for (String k : d.fullKeys()) {
            if (!stations.containsKey(k)) {
                d.remove(k);
            }
        }
        for (LinerIls.Station s : stations.values()) {
            LinerIls.Station o = d.get(s.key());
            if (all || o == null || o.cat != s.cat || o.flags != s.flags || Math.abs(o.bendLoc - s.bendLoc) > 0.005 || Math.abs(o.bendGs - s.bendGs) > 0.005
                    || o.calib != s.calib) {
                d.put(s);
            }
        }
    }

    // ---------------------------------------------------------------- the tick

    /** One server tick of world w's dimension (AirportServerSVA). */
    public void tick(World w, long now) {
        if (dirty) {
            rebuild(w);
        }
        int dim = w.provider.dimensionId;
        boolean any = false;
        if (now % STATUS_TICKS == 0) {
            for (IlsPairing.Pair p : pairs) {
                if (p.loc.dim == dim) {
                    any |= status(w, p, now);
                }
            }
            if (any) {
                publish(w, false);
                version++;
            }
        }
        if (now % FlightCheck.SAMPLE_TICKS == 0) {
            inspections(w, now);
        }
        if (now % WX_TICKS == 0) {
            weather(w, now);
        }
    }

    /** The monitor of one station: what it is made of now -> IlsCat -> the station. @return changed */
    boolean status(World w, IlsPairing.Pair p, long now) {
        LinerIls.Station s = stations.get(p.key());
        if (s == null) {
            return false;
        }
        IlsCat.Setup u = new IlsCat.Setup();
        // the antennas: intact (the array / the mast whole), their shelters (monitor + power)
        boolean locLoaded = loaded(w, p.loc.x, p.loc.z);
        if (locLoaded) {
            p.loc.intact = TileEntityIlsAntennaSVA.intact(w, p.loc.key);
            long[] r = reports.get(p.loc.key);
            p.loc.monitor = r != null && now - r[0] <= REPORT_TICKS;
            p.loc.power = p.loc.monitor && r[1] != 0;
        }
        u.locIntact = p.loc.intact;
        u.locMonitor = p.loc.monitor;
        boolean powered = !ConfigSVA.aptRequirePower || p.loc.power;
        u.gsPresent = p.gs != null;
        if (p.gs != null) {
            if (loaded(w, p.gs.x, p.gs.z)) {
                p.gs.intact = TileEntityIlsAntennaSVA.intact(w, p.gs.key);
                long[] r = reports.get(p.gs.key);
                p.gs.monitor = r != null && now - r[0] <= REPORT_TICKS;
                p.gs.power = p.gs.monitor && r[1] != 0;
            }
            u.gsIntact = p.gs.intact && (!ConfigSVA.aptRequirePower || p.gs.power);
            u.gsMonitor = p.gs.monitor;
            u.gsSetback = p.ga;
            u.gsOffset = p.gc;
            u.angle = p.gs.angle;
        }
        u.power = powered;
        u.locLat = p.lc;
        u.locBeyond = p.beyond;
        // the areas
        int[] b = occBlocks.get(p.key());
        if (b == null || (now + (p.key().hashCode() & 0x7F)) % SCAN_TICKS < STATUS_TICKS) {
            int[] nb = scanBlocks(w, p);
            if (nb != null) {
                b = nb;
                occBlocks.put(p.key(), b);
            }
        }
        int[] e = scanEntities(w, p);
        if (e != null) {
            occEntities.put(p.key(), e);
        } else {
            e = occEntities.get(p.key());
        }
        int[] o = new int[4];
        for (int i = 0; i < 4 && ConfigSVA.aptCriticalAreas; i++) {
            o[i] = (b == null ? 0 : b[i]) + (e == null ? 0 : e[i]);
        }
        u.locCrit = o[0];
        u.gsCrit = o[1];
        u.locSens = o[2];
        u.gsSens = o[3];
        FlightCheck fc = check(p.key());
        u.calibCat = ConfigSVA.aptFlightCheck ? fc.calibCat() : 3;
        IlsCat.Result res = IlsCat.compute(u);
        if (conflicts.contains(p.key())) {
            res.issues.add(Integer.valueOf(IlsCat.I_FREQ));
        }
        results.put(p.key(), res);
        int flags = res.flags;
        boolean ch = s.cat != res.cat || s.flags != flags || Math.abs(s.bendLoc - res.bendLoc) > 0.005 || Math.abs(s.bendGs - res.bendGs) > 0.005
                || s.calib != fc.pack();
        s.cat = res.cat;
        s.flags = flags;
        s.bendLoc = res.bendLoc;
        s.bendGs = res.bendGs;
        s.calib = fc.pack();
        return ch;
    }

    static boolean loaded(World w, double x, double z) {
        return w.blockExists((int) Math.floor(x), 64, (int) Math.floor(z));
    }

    /** The world cells of an area (the runway frame of the pair's landing direction), each once. */
    static List<int[]> cells(IlsPairing.Pair p, IlsCat.Area a) {
        List<int[]> out = new ArrayList<int[]>();
        Set<Long> seen = new HashSet<Long>();
        for (double al = a.a0; al <= a.a1 + 1e-6; al += 0.5) {
            for (double cr = a.c0; cr <= a.c1 + 1e-6; cr += 0.5) {
                double[] wp = p.r.world(p.dir, al, cr);
                int x = (int) Math.floor(wp[0]), z = (int) Math.floor(wp[1]);
                if (seen.add(Long.valueOf(((long) x << 32) ^ (z & 0xFFFFFFFFL)))) {
                    out.add(new int[] {x, z});
                }
            }
        }
        return out;
    }

    /** The pair's four areas: LOC critical, G/S critical (null without a mast), LOC sensitive, G/S sensitive. */
    public static IlsCat.Area[] areas(IlsPairing.Pair p) {
        IlsCat.Area[] a = new IlsCat.Area[4];
        a[0] = IlsCat.locCritical(p.la, p.lc);
        a[2] = IlsCat.locSensitive(p.la, p.lc);
        if (p.gs != null) {
            a[1] = IlsCat.gsCritical(p.ga, p.gc, p.r.width);
            a[3] = IlsCat.gsSensitive(p.ga, p.gc, p.r.width);
        }
        return a;
    }

    /** The solid blocks over the surface in the areas (a sensitive count excludes its critical part); null: not loaded. */
    int[] scanBlocks(World w, IlsPairing.Pair p) {
        IlsCat.Area[] a = areas(p);
        int y0 = (int) Math.round(p.r.end(p.dir).elev);
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (a[i] == null) {
                continue;
            }
            for (int[] c : cells(p, a[i])) {
                if (!w.blockExists(c[0], y0, c[1])) {
                    return null;
                }
                if (i >= 2) {
                    double[] l = p.r.local(p.dir, c[0] + 0.5, c[1] + 0.5);
                    if (a[i - 2] != null && a[i - 2].contains(l[0], l[1])) {
                        continue;                                   // the critical part counted already
                    }
                }
                for (int y = y0; y < y0 + IlsCat.CRIT_HEIGHT && y < 256; y++) {
                    if (obstacle(w, c[0], y, c[1])) {
                        n[i]++;
                    }
                }
                if (n[i] > 200) {
                    break;
                }
            }
        }
        return n;
    }

    /** A block that bends the beam: solid, not the airport's own (antennas, shelters, devices, runway, markers). */
    public static boolean obstacle(World w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        if (b.isAir(w, x, y, z) || !b.getMaterial().blocksMovement()) {
            return false;
        }
        return !(b instanceof BlockIlsAntennaSVA || b instanceof BlockAptPartSVA || b instanceof BlockIlsShelterSVA || b instanceof BlockAptDeviceSVA
                || b instanceof BlockRunwayEndSVA || b instanceof BlockRunwaySVA || b instanceof BlockPapiSVA || b instanceof BlockApproachLightSVA);
    }

    /** The entities in the areas (vehicles IlsCat.OCC_VEHICLE, creatures 1); null: not loaded. */
    int[] scanEntities(World w, IlsPairing.Pair p) {
        IlsCat.Area[] a = areas(p);
        int[] n = new int[4];
        double y0 = p.r.end(p.dir).elev;
        for (int i = 0; i < 4; i++) {
            if (a[i] == null) {
                continue;
            }
            double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (double[] c : new double[][] {{a[i].a0, a[i].c0}, {a[i].a1, a[i].c0}, {a[i].a0, a[i].c1}, {a[i].a1, a[i].c1}}) {
                double[] wp = p.r.world(p.dir, c[0], c[1]);
                minX = Math.min(minX, wp[0]);
                maxX = Math.max(maxX, wp[0]);
                minZ = Math.min(minZ, wp[1]);
                maxZ = Math.max(maxZ, wp[1]);
            }
            if (!w.blockExists((int) Math.floor(minX), (int) y0, (int) Math.floor(minZ)) || !w.blockExists((int) Math.floor(maxX), (int) y0, (int) Math.floor(maxZ))) {
                return null;
            }
            List<?> l = w.getEntitiesWithinAABB(Entity.class, AxisAlignedBB.getBoundingBox(minX, y0 - 1, minZ, maxX, y0 + IlsCat.CRIT_HEIGHT + 3, maxZ));
            for (Object o : l) {
                Entity en = (Entity) o;
                double[] lc = p.r.local(p.dir, en.posX, en.posZ);
                if (!a[i].contains(lc[0], lc[1]) || i >= 2 && a[i - 2] != null && a[i - 2].contains(lc[0], lc[1])) {
                    continue;
                }
                if (en instanceof com.sva.entity.EntityVehicleSVA) {
                    n[i] += IlsCat.OCC_VEHICLE;
                } else if (en instanceof EntityLivingBase) {
                    n[i]++;
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- the flight inspection

    private void inspections(World w, long now) {
        int dim = w.provider.dimensionId;
        List<com.sva.entity.EntityVehicleSVA> air = null;
        for (IlsPairing.Pair p : pairs) {
            if (p.loc.dim != dim) {
                continue;
            }
            LinerIls.Station s = stations.get(p.key());
            FlightCheck fc = check(p.key());
            if (s == null || fc.state == FlightCheck.DONE || s.off()) {
                continue;
            }
            if (air == null) {
                air = aircraft(w);
            }
            if (fc.state == FlightCheck.WAITING) {
                for (com.sva.entity.EntityVehicleSVA v : air) {
                    LinerIls.Dev d = LinerIls.dev(s, v.posX, v.posY, v.posZ, ConfigSVA.linerIlsRange, now);
                    if (fc.offer(v.getEntityId(), d.along, d.locValid && !d.backCourse, transponder(v))) {
                        v.tellAboard("sva.apt.check.started", s.ident);
                        break;
                    }
                }
            } else if (fc.state == FlightCheck.RUNNING) {
                Entity e = w.getEntityByID(fc.aircraft);
                if (!(e instanceof com.sva.entity.EntityVehicleSVA) || e.isDead) {
                    fc.reset();
                    continue;
                }
                com.sva.entity.EntityVehicleSVA v = (com.sva.entity.EntityVehicleSVA) e;
                LinerIls.Dev d = LinerIls.dev(s, v.posX, v.posY, v.posZ, ConfigSVA.linerIlsRange, now);
                double locShift = FlightCheck.locShift(p.lc, IlsBeam.bend(s.bendLoc, now, s.phase, 0), p.la);
                double gsErr = IlsBeam.bend(s.bendGs, now, s.phase, 0.25);
                IlsCat.Result r = results.get(p.key());
                double tch = p.gs == null ? 0 : IlsBeam.tch(p.ga, p.gc, s.angle);
                int st = fc.sample(v.getEntityId(), d.along, d.inCover && !d.backCourse, locShift, gsErr, tch, s.angle);
                if (st == FlightCheck.DONE) {
                    String hint = fc.hint < 0 ? "" : "@sva.apt.hint." + IlsCat.ISSUE_KEYS[fc.hint];
                    v.tellAboard(fc.result >= 3 ? "sva.apt.check.done3" : "sva.apt.check.doneLow", s.ident, IlsCat.catText(fc.result), hint);
                    s.calib = fc.pack();
                    version++;
                    markDirty();
                } else if (st == FlightCheck.WAITING) {
                    v.tellAboard("sva.apt.check.aborted", s.ident);
                }
                if (r != null && r.cat > 0) {
                    markDirty();
                }
            }
        }
    }

    static List<com.sva.entity.EntityVehicleSVA> aircraft(World w) {
        List<com.sva.entity.EntityVehicleSVA> l = new ArrayList<com.sva.entity.EntityVehicleSVA>();
        for (Object o : w.loadedEntityList) {
            if (o instanceof com.sva.entity.EntityVehicleSVA) {
                com.sva.entity.EntityVehicleSVA v = (com.sva.entity.EntityVehicleSVA) o;
                if (!v.isDead && com.sva.airdefense.XpdrMath.fitted(v.getVehicleType())) {
                    l.add(v);
                }
            }
        }
        return l;
    }

    static boolean transponder(com.sva.entity.EntityVehicleSVA v) {
        return com.sva.airdefense.XpdrMath.fitted(v.getVehicleType()) && v.state.xpdr.replying();
    }

    // ---------------------------------------------------------------- RVR and the weather

    /** The weather where a device stands (RvrMath's view of the world). */
    static RvrMath.Wx wx(World w, Device d) {
        RvrMath.Wx x = new RvrMath.Wx();
        int bx = (int) Math.floor(d.x), bz = (int) Math.floor(d.z);
        BiomeGenBase b = w.getBiomeGenForCoords(bx, bz);
        x.rain = w.isRaining() && b != null && (b.canSpawnLightningBolt() || b.getEnableSnow());
        x.thunder = w.isThundering() && x.rain;
        x.cold = b != null && b.getEnableSnow();
        x.swamp = b == BiomeGenBase.swampland;
        x.humidity = b == null ? 0.4 : b.rainfall;
        x.dayTime = w.getWorldTime();
        x.fog = ConfigSVA.aptFog;
        x.y = d.y;
        x.night = AirfieldMath.night(w.getCelestialAngle(1F));
        return x;
    }

    private void weather(World w, long now) {
        int dim = w.provider.dimensionId;
        long seed = windSeed(w);
        for (Device d : devices.values()) {
            if (d.dim != dim) {
                continue;
            }
            if (d.kind == K_RVR) {
                RvrMath.Wx x = wx(w, d);
                double vis = RvrMath.visibility(x);
                int step = 0;
                IlsPairing.Rwy r = null;
                for (IlsPairing.Rwy q : runways) {
                    if (q.key().equals(d.rwy)) {
                        r = q;
                    }
                }
                if (r != null) {
                    int prev = d.rvr < 0 ? (int) RvrMath.RVR_MAX : d.rvr;
                    step = RunwayLayout.step(r.a.lights, x.night, x.rain, prev);
                }
                d.rvr = RvrMath.report(RvrMath.rvr(vis, x.night, step), d.key.hashCode(), now);
            } else if (d.kind == K_WX) {
                RvrMath.Wx x = wx(w, d);
                d.qnh = WindField.lag(d.qnh, WindField.qnhTarget(seed, now, x.rain, x.thunder), WX_TICKS);
                WindField.Wind wd = WindField.wind(seed, now, x.rain, x.thunder, WX_HEIGHT);
                d.windFrom = wd.from;
                d.windSpeed = wd.speed;
                d.windGust = wd.gust;
                BiomeGenBase b = w.getBiomeGenForCoords((int) Math.floor(d.x), (int) Math.floor(d.z));
                d.temp = WindField.temperature(d.y, b == null ? 0.8 : b.temperature, w.getWorldTime());
            }
        }
    }

    /** A client's copy of the wind field's seed (client.AirportClientSVA sets it from the sync). */
    public static volatile long syncedWindSeed;

    /** The wind field's seed: derived from the world's seed (the clients get this, never the seed itself). */
    public static long windSeed(World w) {
        long s = w.getSeed() * 0x9E3779B97F4A7C15L + 0x5DEECE66DL;
        s = (s ^ s >>> 30) * 0xBF58476D1CE4E5B9L;
        return s ^ s >>> 31;
    }

    /** The RVR of a station's runway {TDZ, MID, END} in its landing direction (m-eq, -1 none). */
    public int[] rvr(String locKey) {
        int[] out = {-1, -1, -1};
        IlsPairing.Pair p = pair(locKey);
        return p == null ? out : rvr(p.r, p.dir);
    }

    public int[] rvr(IlsPairing.Rwy r, int dir) {
        int[] out = {-1, -1, -1};
        List<Device> l = new ArrayList<Device>();
        for (Device d : devices.values()) {
            if (d.kind == K_RVR && d.rwy.equals(r.key()) && d.rvr >= 0) {
                l.add(d);
            }
        }
        if (l.isEmpty()) {
            return out;
        }
        java.util.Collections.sort(l, new java.util.Comparator<Device>() {
            @Override
            public int compare(Device p, Device q) {
                return Double.compare(p.along, q.along);
            }
        });
        if (dir < 0) {
            java.util.Collections.reverse(l);
        }
        out[0] = l.get(0).rvr;
        if (l.size() >= 2) {
            out[2] = l.get(l.size() - 1).rvr;
        }
        if (l.size() >= 3) {
            out[1] = l.get(l.size() / 2).rvr;
        }
        return out;
    }

    /** The nearest weather station of a dimension within WX_RANGE of (x, z), or null. */
    public Device weatherNear(int dim, double x, double z) {
        Device best = null;
        double bd = WX_RANGE * WX_RANGE;
        for (Device d : devices.values()) {
            if (d.dim == dim && d.kind == K_WX && !Double.isNaN(d.qnh)) {
                double dd = (d.x - x) * (d.x - x) + (d.z - z) * (d.z - z);
                if (dd < bd) {
                    bd = dd;
                    best = d;
                }
            }
        }
        return best;
    }

    /** The marker whose fan the point is in (MarkerMath type, the innermost of several), NONE none. */
    public int markerAt(int dim, double x, double y, double z) {
        int best = MarkerMath.NONE;
        for (Device d : devices.values()) {
            if (d.dim != dim || d.kind != K_MARKER || d.type == MarkerMath.NONE) {
                continue;
            }
            double dx = x - d.x, dz = z - d.z;
            if (dx * dx + dz * dz > MARKER_RANGE * MARKER_RANGE) {
                continue;
            }
            double c = Math.toRadians(d.course);
            double da = dx * Math.sin(c) - dz * Math.cos(c), dc = dx * Math.cos(c) + dz * Math.sin(c);
            if (MarkerMath.inFan(y - d.y - 1, da, dc) && d.type > best) {
                best = d.type;
            }
        }
        return best;
    }

    /** Everything the wave B2 tower (and the planner's check) reads of a station: its status word {cat, flags, calib, freq conflict}. */
    public int[] status(String locKey) {
        LinerIls.Station s = stations.get(locKey);
        return s == null ? null : new int[] {s.cat, s.flags, s.calib, conflicts.contains(locKey) ? 1 : 0};
    }

    // ---------------------------------------------------------------- the clients' copy (AirportServerSVA sends it)

    /** The runways (the renderers' paint and lights), the stations' status, the wind's seed - of one dimension. */
    public NBTTagCompound clientData(World w, int dim) {
        if (dirty) {
            rebuild(w);
        }
        NBTTagCompound t = new NBTTagCompound();
        t.setLong("WindSeed", windSeed(w));
        NBTTagList rl = new NBTTagList();
        for (IlsPairing.Rwy r : runways) {
            if (r.a.dim != dim) {
                continue;
            }
            NBTTagCompound e = new NBTTagCompound();
            e.setString("K", r.key());
            e.setString("Kb", r.b.key);
            e.setDouble("Ax", r.a.x);
            e.setDouble("Az", r.a.z);
            e.setDouble("Bx", r.b.x);
            e.setDouble("Bz", r.b.z);
            e.setDouble("Y", r.elev);
            e.setInteger("W", r.width);
            e.setString("Na", r.nameA);
            e.setString("Nb", r.nameB);
            e.setInteger("La", r.a.lights);
            e.setInteger("Lb", r.b.lights);
            e.setBoolean("Sa", r.a.als != 0);
            e.setBoolean("Sb", r.b.als != 0);
            int[] ra = rvr(r, 1), rb = rvr(r, -1);
            e.setInteger("Ra", ra[0]);
            e.setInteger("Rb", rb[0]);
            e.setString("Apt", r.apt);
            rl.appendTag(e);
        }
        t.setTag("Rwy", rl);
        NBTTagList sl = new NBTTagList();
        for (LinerIls.Station s : stations.values()) {
            if (s.dim != dim) {
                continue;
            }
            NBTTagCompound e = new NBTTagCompound();
            e.setString("K", s.key());
            e.setInteger("Cat", s.cat);
            e.setInteger("F", s.flags);
            e.setString("Id", s.ident);
            e.setString("Rw", s.rwy);
            sl.appendTag(e);
        }
        t.setTag("St", sl);
        return t;
    }

    // ---------------------------------------------------------------- save

    @Override
    public void readFromNBT(NBTTagCompound t) {
        ends.clear();
        locs.clear();
        gss.clear();
        devices.clear();
        checks.clear();
        checkedGeo.clear();
        NBTTagList l = t.getTagList("Ends", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound e = l.getCompoundTagAt(i);
            IlsPairing.End en = new IlsPairing.End(e.getString("K"), e.getInteger("Dim"), e.getDouble("X"), e.getDouble("Z"), e.getDouble("Elev"),
                    e.getDouble("Facing"), e.getInteger("W"));
            en.lights = e.hasKey("Lights") ? e.getInteger("Lights") : RunwayLayout.LIGHTS_AUTO;
            en.als = e.getInteger("Als");
            ends.put(en.key, en);
        }
        l = t.getTagList("Locs", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound e = l.getCompoundTagAt(i);
            IlsPairing.LocAnt a = new IlsPairing.LocAnt();
            a.key = e.getString("K");
            a.dim = e.getInteger("Dim");
            a.x = e.getDouble("X");
            a.z = e.getDouble("Z");
            a.y = e.getDouble("Y");
            a.ident = e.getString("Id");
            a.freq = LinerIls.clampFreq(e.getInteger("Freq"));
            a.intact = !e.getBoolean("Broken");
            a.monitor = e.getBoolean("Mon");
            a.power = e.getBoolean("Pow");
            locs.put(a.key, a);
        }
        l = t.getTagList("Gss", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound e = l.getCompoundTagAt(i);
            IlsPairing.GsAnt a = new IlsPairing.GsAnt();
            a.key = e.getString("K");
            a.dim = e.getInteger("Dim");
            a.x = e.getDouble("X");
            a.z = e.getDouble("Z");
            a.y = e.getDouble("Y");
            a.angle = LinerIls.clampAngle(e.getDouble("Ang"));
            a.intact = !e.getBoolean("Broken");
            a.monitor = e.getBoolean("Mon");
            a.power = e.getBoolean("Pow");
            gss.put(a.key, a);
        }
        l = t.getTagList("Devs", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound e = l.getCompoundTagAt(i);
            Device d = new Device();
            d.key = e.getString("K");
            d.dim = e.getInteger("Dim");
            d.kind = Math.max(0, Math.min(KINDS - 1, e.getInteger("Kind")));
            d.x = e.getDouble("X");
            d.y = e.getDouble("Y");
            d.z = e.getDouble("Z");
            d.rvr = e.hasKey("Rvr") ? e.getInteger("Rvr") : -1;
            devices.put(d.key, d);
        }
        l = t.getTagList("Checks", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound e = l.getCompoundTagAt(i);
            FlightCheck c = new FlightCheck();
            c.unpack(e.getInteger("W"));
            checks.put(e.getString("K"), c);
            checkedGeo.put(e.getString("K"), Integer.valueOf(e.getInteger("Geo")));
        }
        dirty = true;
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        NBTTagList l = new NBTTagList();
        for (IlsPairing.End en : ends.values()) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("K", en.key);
            e.setInteger("Dim", en.dim);
            e.setDouble("X", en.x);
            e.setDouble("Z", en.z);
            e.setDouble("Elev", en.elev);
            e.setDouble("Facing", en.facing);
            e.setInteger("W", en.width);
            e.setInteger("Lights", en.lights);
            e.setInteger("Als", en.als);
            l.appendTag(e);
        }
        t.setTag("Ends", l);
        l = new NBTTagList();
        for (IlsPairing.LocAnt a : locs.values()) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("K", a.key);
            e.setInteger("Dim", a.dim);
            e.setDouble("X", a.x);
            e.setDouble("Z", a.z);
            e.setDouble("Y", a.y);
            e.setString("Id", a.ident);
            e.setInteger("Freq", a.freq);
            e.setBoolean("Broken", !a.intact);
            e.setBoolean("Mon", a.monitor);
            e.setBoolean("Pow", a.power);
            l.appendTag(e);
        }
        t.setTag("Locs", l);
        l = new NBTTagList();
        for (IlsPairing.GsAnt a : gss.values()) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("K", a.key);
            e.setInteger("Dim", a.dim);
            e.setDouble("X", a.x);
            e.setDouble("Z", a.z);
            e.setDouble("Y", a.y);
            e.setDouble("Ang", a.angle);
            e.setBoolean("Broken", !a.intact);
            e.setBoolean("Mon", a.monitor);
            e.setBoolean("Pow", a.power);
            l.appendTag(e);
        }
        t.setTag("Gss", l);
        l = new NBTTagList();
        for (Device d : devices.values()) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("K", d.key);
            e.setInteger("Dim", d.dim);
            e.setInteger("Kind", d.kind);
            e.setDouble("X", d.x);
            e.setDouble("Y", d.y);
            e.setDouble("Z", d.z);
            e.setInteger("Rvr", d.rvr);
            l.appendTag(e);
        }
        t.setTag("Devs", l);
        l = new NBTTagList();
        for (Map.Entry<String, FlightCheck> c : checks.entrySet()) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("K", c.getKey());
            e.setInteger("W", c.getValue().pack());
            Integer g = checkedGeo.get(c.getKey());
            e.setInteger("Geo", g == null ? 0 : g.intValue());
            l.appendTag(e);
        }
        t.setTag("Checks", l);
    }

    /** World tests: the counts of what is registered {ends, localizers, masts, devices}. */
    public int[] counts() {
        return new int[] {ends.size(), locs.size(), gss.size(), devices.size()};
    }
}
