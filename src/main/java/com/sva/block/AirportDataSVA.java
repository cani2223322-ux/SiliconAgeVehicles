package com.sva.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.sva.ConfigSVA;
import com.sva.airport.ApproachMath;
import com.sva.airport.AtisMath;
import com.sva.airport.EmbassyAirportMath;
import com.sva.airport.EmbassyAirportSVA;
import com.sva.airport.FlightCheck;
import com.sva.airport.IlsBeam;
import com.sva.airport.IlsCat;
import com.sva.airport.IlsPairing;
import com.sva.airport.MarkerMath;
import com.sva.airport.RunwayLayout;
import com.sva.airport.RvrMath;
import com.sva.airport.TaxiGraph;
import com.sva.airport.TowerMath;
import com.sva.airport.WindField;
import com.sva.compat.EmbassyCompatSVA;
import com.sva.compat.EmbassyFieldSVA;
import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.LinerIls;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
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
 * <p>
 * Airport wave B2 (plan-next §9, items 27, 29-34): the «Диспетчерская вышка» towers too (Tower: the airport's ICAO code
 * and name, the runway in use, the lights' steps, the ILS switched off, the transponder code range, the ATIS letter and
 * its text, the charts' version, the taxi graph's hand-made links / cuts) - so the airport keeps its name, approaches and
 * lights while the tower's chunk sleeps; the tower's block reports in (power, the controller in its seat) every REPORT_TICKS
 * as the shelters do, and its air-traffic services (block.AtcServerSVA) run only while it is ONLINE. rebuild() names the
 * runways within TowerMath.RANGE after their tower (instead of «ZZxx»), the taxi lights («Огонь рулёжки», kinds K_TAXI..
 * K_STAND) are devices of their own.
 * <p>
 * «Лётное поле посольства» (docs/plan-embassy-airfield-addon.md §3.3, §3.7): airport.EmbassyAirportSVA puts the Tuners' embassies'
 * airfields in as VIRTUAL records (its registry: isVirtual / embassyOf) - runway ends, a tower, the ILS antennas, the windsock, the taxi
 * lights - never saved (writeToNBT, IlsDataSVA.writeToNBT and the inspections' list leave them out). Each embassy's ends and antennas
 * pair only among themselves (a player's marker never takes an embassy's end), named by the core's painted numbers (the true heading);
 * an embassy's runway belongs to its tower only and its tower takes no player's runway (TowerMath.assign); its antennas are always
 * whole, monitored and powered and calibrated at CAT III («облёт Наладчиков» - no flight inspection); the core's own blocks bend no
 * beam (obstacle). The clients' copy is per player (clientData with the hidden keys: the embassies he does not know left out) and
 * carries the embassy runways' "Emb" / "Ext" / "Ils" and the root's "EmbSock" / "EmbPapi" for the clients' lights.
 */
public class AirportDataSVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_airport";
    /** Device kinds (block.BlockAptDeviceSVA's metadata). */
    public static final int K_MARKER = 0, K_RVR = 1, K_WX = 2, K_SOCK = 3,
            /** Airport wave B2: the taxi lights (block.BlockTaxiLightSVA meta + K_TAXI): centre line, edge, stop bar, stand. */
            K_TAXI = 4, K_EDGE = 5, K_STOP = 6, K_STAND = 7, KINDS = 8;
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

    /** Airport wave B2: a tower - what outlives its chunk (see the class comment). */
    public static final class Tower {
        public String key = "", icao = "", name = "", owner = "";
        public int dim, x, y, z;
        /** The runway in use: an end's key ("" AUTO - by the wind). */
        public String rwySel = "";
        /** The lights' steps (RunwayLayout: AUTO or 0..5): the runways, the approach lights, the taxiways. */
        public int ltRwy = RunwayLayout.LIGHTS_AUTO, ltAppr = RunwayLayout.LIGHTS_AUTO, ltTaxi = RunwayLayout.LIGHTS_AUTO;
        /** The ILS stations (localizer keys) the tower has switched off. */
        public final Set<String> ilsOff = new HashSet<String>();
        /** The transponder code range of its arrivals / departures (octal). */
        public int codeLo = TowerMath.CODE_LO, codeHi = TowerMath.CODE_HI;
        /**
         * Audit 2026-10-22 (bug 5): «коды: доверенные» - the players whose aircraft get its codes on their own besides its
         * owner and his access list (saved); the block's access list (its copy, refreshed by the block's report - transient).
         */
        public final List<String> sqTrust = new ArrayList<String>();
        public transient List<String> access = new ArrayList<String>();
        /** ATIS: the letter (-1: none yet), the observation it was made of, its text (AtisMath.pack), when. */
        public int letter = -1;
        public AtisMath.Obs atis;
        public String atisText = "";
        public long atisAt;
        /** The charts' version and the signature of what they show (a change: version + 1). */
        public int chartVer = 1, chartSig;
        /** The taxi graph: the tower map's added links and cut ones (TaxiGraph.link keys). */
        public final Set<String> taxiExtra = new HashSet<String>(), taxiCut = new HashSet<String>();
        // derived / transient
        /** Its runways (rebuild), the elevation of its airport, the runway in use now (an end key; AtcServerSVA). */
        public transient List<IlsPairing.Rwy> rwys = new ArrayList<IlsPairing.Rwy>();
        public transient int elev;
        public transient String inUse = "";
        /** The block's last report (world ticks), powered then, the player in its controller seat ("" none). */
        public transient long reportAt = -100000;
        public transient boolean powered;
        public transient String controller = "";

        /** Online: reported lately and powered (its services run). */
        public boolean online(long now) {
            return powered && now - reportAt <= REPORT_TICKS && now >= reportAt;
        }

        void write(NBTTagCompound e) {
            e.setString("K", key);
            e.setInteger("Dim", dim);
            e.setIntArray("Pos", new int[] {x, y, z});
            e.setString("Icao", icao);
            e.setString("Name", name);
            e.setString("Own", owner);
            e.setString("Sel", rwySel);
            e.setIntArray("Lt", new int[] {ltRwy, ltAppr, ltTaxi});
            NBTTagList off = new NBTTagList();
            for (String k : ilsOff) {
                off.appendTag(new net.minecraft.nbt.NBTTagString(k));
            }
            e.setTag("IlsOff", off);
            e.setIntArray("Codes", new int[] {codeLo, codeHi});
            e.setInteger("Letter", letter);
            if (atis != null) {
                e.setIntArray("Atis", atis.pack());
                e.setString("AtisRwy", atis.rwy);
            }
            e.setString("AtisText", atisText);
            e.setLong("AtisAt", atisAt);
            e.setIntArray("Chart", new int[] {chartVer, chartSig});
            NBTTagList ex = new NBTTagList();
            for (String k : taxiExtra) {
                ex.appendTag(new net.minecraft.nbt.NBTTagString(k));
            }
            e.setTag("TaxiAdd", ex);
            NBTTagList cu = new NBTTagList();
            for (String k : taxiCut) {
                cu.appendTag(new net.minecraft.nbt.NBTTagString(k));
            }
            e.setTag("TaxiCut", cu);
            NBTTagList sq = new NBTTagList();
            for (String k : sqTrust) {
                sq.appendTag(new net.minecraft.nbt.NBTTagString(k));
            }
            e.setTag("SqTrust", sq);
        }

        static Tower read(NBTTagCompound e) {
            Tower t = new Tower();
            t.key = e.getString("K");
            t.dim = e.getInteger("Dim");
            int[] p = e.getIntArray("Pos");
            if (p.length == 3) {
                t.x = p[0];
                t.y = p[1];
                t.z = p[2];
            }
            t.icao = TowerMath.cleanIcao(e.getString("Icao"));
            t.name = TowerMath.cleanName(e.getString("Name"));
            t.owner = e.getString("Own");
            t.rwySel = e.getString("Sel");
            int[] lt = e.getIntArray("Lt");
            if (lt.length == 3) {
                t.ltRwy = clampLights(lt[0]);
                t.ltAppr = clampLights(lt[1]);
                t.ltTaxi = clampLights(lt[2]);
            }
            NBTTagList off = e.getTagList("IlsOff", 8);
            for (int i = 0; i < off.tagCount(); i++) {
                t.ilsOff.add(off.getStringTagAt(i));
            }
            int[] c = e.getIntArray("Codes");
            if (c.length == 2 && TowerMath.codeRange(c[0], c[1]) != null) {
                t.codeLo = c[0];
                t.codeHi = c[1];
            }
            t.letter = e.hasKey("Letter") ? Math.max(-1, Math.min(AtisMath.LETTERS - 1, e.getInteger("Letter"))) : -1;
            if (e.hasKey("Atis")) {
                t.atis = AtisMath.Obs.unpack(e.getString("AtisRwy"), e.getIntArray("Atis"));
            }
            t.atisText = e.getString("AtisText");
            t.atisAt = e.getLong("AtisAt");
            int[] ch = e.getIntArray("Chart");
            if (ch.length == 2) {
                t.chartVer = Math.max(1, ch[0]);
                t.chartSig = ch[1];
            }
            NBTTagList ex = e.getTagList("TaxiAdd", 8);
            for (int i = 0; i < ex.tagCount(); i++) {
                t.taxiExtra.add(ex.getStringTagAt(i));
            }
            NBTTagList cu = e.getTagList("TaxiCut", 8);
            for (int i = 0; i < cu.tagCount(); i++) {
                t.taxiCut.add(cu.getStringTagAt(i));
            }
            NBTTagList sq = e.getTagList("SqTrust", 8);
            for (int i = 0; i < sq.tagCount() && i < TileEntityTowerSVA.MAX_ACCESS; i++) {
                t.sqTrust.add(sq.getStringTagAt(i));
            }
            return t;
        }
    }

    /** A lights mode from a save / a screen: AUTO or 0..5. */
    public static int clampLights(int m) {
        return Math.max(RunwayLayout.LIGHTS_AUTO, Math.min(RunwayLayout.STEPS, m));
    }

    private final Map<String, IlsPairing.End> ends = new LinkedHashMap<String, IlsPairing.End>();
    private final Map<String, IlsPairing.LocAnt> locs = new LinkedHashMap<String, IlsPairing.LocAnt>();
    private final Map<String, IlsPairing.GsAnt> gss = new LinkedHashMap<String, IlsPairing.GsAnt>();
    private final Map<String, Device> devices = new LinkedHashMap<String, Device>();
    /** Airport wave B2: the towers (key "dim:x:y:z" of the master block). */
    private final Map<String, Tower> towers = new LinkedHashMap<String, Tower>();
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
    /** Airport wave B2: per runway end key - its tower (null none); the taxi graph per dimension (built on demand). */
    private transient final Map<String, Tower> endTower = new HashMap<String, Tower>();
    private transient final Map<Integer, Object[]> taxiCache = new HashMap<Integer, Object[]>();
    private transient int taxiVersion;

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
        if (d.kind >= K_TAXI) {
            taxiVersion++;
            taxiCache.clear();
        }
        changed();
    }

    // ---------------------------------------------------------------- airport wave B2: the towers

    /** A tower placed / its settings changed (its runtime report kept). */
    public void putTower(Tower t) {
        Tower old = towers.get(t.key);
        if (old != null && old != t) {
            t.reportAt = old.reportAt;
            t.powered = old.powered;
            t.controller = old.controller;
            t.inUse = old.inUse;
        }
        towers.put(t.key, t);
        changed();
    }

    public Tower tower(String key) {
        return towers.get(key);
    }

    public java.util.Collection<Tower> towers() {
        return towers.values();
    }

    /** The tower of an ICAO code (any dimension), null none. */
    public Tower towerByIcao(String icao) {
        for (Tower t : towers.values()) {
            if (t.icao.equalsIgnoreCase(icao)) {
                return t;
            }
        }
        return null;
    }

    /** The other towers' codes (the uniqueness check). */
    public List<String> icaosExcept(String key) {
        List<String> l = new ArrayList<String>();
        for (Tower t : towers.values()) {
            if (!t.key.equals(key)) {
                l.add(t.icao);
            }
        }
        return l;
    }

    /** The tower's block reported in (every REPORT_TICKS / 3): its power, the player in its controller seat. */
    public void towerReport(String key, boolean powered, String controller, long now) {
        Tower t = towers.get(key);
        if (t != null) {
            t.reportAt = now;
            t.powered = powered;
            t.controller = controller == null ? "" : controller;
        }
    }

    /** A tower's setting changed from its screen (the runways' names / lights / ILS / clients). */
    public void towerChanged() {
        changed();
    }

    /** The tower a runway belongs to (null none). */
    public Tower towerOf(IlsPairing.Rwy r) {
        return r == null ? null : endTower.get(r.a.key);
    }

    public Tower towerOfEnd(String endKey) {
        return endTower.get(endKey);
    }

    /** The runway lights' mode burning at an end: its own (a local override) or its tower's («ВЫШКА»; no tower: AUTO). */
    public int rwyMode(IlsPairing.End e) {
        if (e.lights != RunwayLayout.LIGHTS_TOWER) {
            return e.lights;
        }
        Tower t = endTower.get(e.key);
        return t == null ? RunwayLayout.LIGHTS_AUTO : t.ltRwy;
    }

    /** The approach lights' mode at an end (the tower's approach step, else as the runway's). */
    public int alsMode(IlsPairing.End e) {
        if (e.lights != RunwayLayout.LIGHTS_TOWER) {
            return e.lights;
        }
        Tower t = endTower.get(e.key);
        return t == null ? RunwayLayout.LIGHTS_AUTO : t.ltAppr;
    }

    /** The ends of a tower's runways set to a mode (the tower's «сбросить местные»: back to ВЫШКА). @return the ends changed */
    public List<String> setEndsMode(Tower t, int mode) {
        List<String> l = new ArrayList<String>();
        for (IlsPairing.Rwy r : t.rwys) {
            for (IlsPairing.End e : new IlsPairing.End[] {r.a, r.b}) {
                if (e.lights != mode) {
                    e.lights = mode;
                    l.add(e.key);
                }
            }
        }
        if (!l.isEmpty()) {
            changed();
        }
        return l;
    }

    /** The approaches of a tower's airport now (each ILS runway end; the stations' current category) and its entry fixes. */
    public List<ApproachMath.Approach> approaches(Tower t) {
        List<ApproachMath.Approach> out = new ArrayList<ApproachMath.Approach>();
        List<ApproachMath.Fix> ent = entries(t);
        for (IlsPairing.Pair p : pairs) {
            if (!t.rwys.contains(p.r)) {
                continue;
            }
            double[] th = p.r.threshold(p.dir);
            LinerIls.Station s = stations.get(p.key());
            out.add(ApproachMath.ils(p.r.name(p.dir), th[0], th[1], (int) Math.round(p.r.end(p.dir).elev), p.r.course(p.dir), s, ent));
        }
        return out;
    }

    /** The airport's reference point: the middle of its runways (the tower's place without runways). */
    public double[] centre(Tower t) {
        if (t.rwys.isEmpty()) {
            return new double[] {t.x + 0.5, t.z + 0.5};
        }
        double x = 0, z = 0;
        for (IlsPairing.Rwy r : t.rwys) {
            double[] m = TowerMath.middle(r);
            x += m[0];
            z += m[1];
        }
        return new double[] {x / t.rwys.size(), z / t.rwys.size()};
    }

    public List<ApproachMath.Fix> entries(Tower t) {
        double[] c = centre(t);
        return ApproachMath.entries(t.icao, c[0], c[1], t.elev);
    }

    /** The signature of what a tower's charts show (its code, name, runways, ILS stations and their geometry). */
    int chartSig(Tower t) {
        long h = t.icao.hashCode() * 31L + t.name.hashCode();
        for (IlsPairing.Rwy r : t.rwys) {
            h = h * 37 + Math.round(r.a.x) * 41 + Math.round(r.a.z) * 43 + Math.round(r.b.x) * 47 + Math.round(r.b.z) * 53 + Math.round(r.elev) * 59
                    + r.nameA.hashCode() + r.width * 61 + r.a.als * 67 + r.b.als * 71;
        }
        for (IlsPairing.Pair p : pairs) {
            if (t.rwys.contains(p.r)) {
                h = h * 73 + geoHash(p) + p.loc.ident.hashCode() * 79 + p.loc.freq;
            }
        }
        return (int) (h ^ h >>> 32);
    }

    // ---------------------------------------------------------------- airport wave B2: the taxi graph

    /**
     * The taxi graph of a dimension: {List of TaxiGraph.Node, the adjacency, List of Device - every taxi light of the
     * dimension (the edges too, in a stable order: the clients' index), the taxi version}; rebuilt when a light / a link
     * changes.
     */
    public Object[] taxi(int dim) {
        Object[] c = taxiCache.get(Integer.valueOf(dim));
        if (c != null && ((Integer) c[3]).intValue() == taxiVersion) {
            return c;
        }
        List<TaxiGraph.Node> nodes = new ArrayList<TaxiGraph.Node>();
        List<Device> all = new ArrayList<Device>();
        for (Device d : devices.values()) {
            if (d.dim != dim || d.kind < K_TAXI) {
                continue;
            }
            all.add(d);
            int kind = d.kind == K_TAXI ? TaxiGraph.CENTRE : d.kind == K_STOP ? TaxiGraph.STOP : d.kind == K_STAND ? TaxiGraph.STAND : -1;
            if (kind >= 0) {
                nodes.add(new TaxiGraph.Node(d.key, (int) Math.floor(d.x), (int) Math.floor(d.y), (int) Math.floor(d.z), kind));
            }
        }
        Set<String> extra = new HashSet<String>(), cut = new HashSet<String>();
        for (Tower t : towers.values()) {
            if (t.dim == dim) {
                extra.addAll(t.taxiExtra);
                cut.addAll(t.taxiCut);
            }
        }
        c = new Object[] {nodes, TaxiGraph.build(nodes, extra, cut), all, Integer.valueOf(taxiVersion)};
        taxiCache.put(Integer.valueOf(dim), c);
        return c;
    }

    /** The taxi lights' list changed (or a link): the graph again, the clients' copy again. */
    public void taxiChanged() {
        taxiVersion++;
        taxiCache.clear();
        version++;
        markDirty();
    }

    public int taxiVersion() {
        return taxiVersion;
    }

    /** A block of any kind gone (its key). */
    public void remove(String key) {
        Device dv = devices.get(key);
        if (dv != null && dv.kind >= K_TAXI) {
            taxiVersion++;
            taxiCache.clear();
        }
        boolean any = ends.remove(key) != null | locs.remove(key) != null | gss.remove(key) != null | devices.remove(key) != null
                | towers.remove(key) != null;
        if (any) {
            checks.remove(key);
            checkedGeo.remove(key);
            changed();
        }
    }

    /** Embassy airfield: any record (an end, an antenna, a device, a tower) holds `key`. */
    public boolean has(String key) {
        return key != null && (ends.containsKey(key) || locs.containsKey(key) || gss.containsKey(key) || devices.containsKey(key)
                || towers.containsKey(key));
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

    /** The flight inspection of a station (embassy airfield: a virtual one's is always done at CAT III - a fresh copy, never stored). */
    public FlightCheck check(String locKey) {
        if (EmbassyAirportSVA.isVirtual(locKey)) {
            return calibrated();
        }
        FlightCheck c = checks.get(locKey);
        if (c == null) {
            c = new FlightCheck();
            checks.put(locKey, c);
        }
        return c;
    }

    /** Embassy airfield: «облёт Наладчиков» - an embassy's station is calibrated at CAT III (no FlightCheck flown, nothing stored). */
    static FlightCheck calibrated() {
        FlightCheck c = new FlightCheck();
        c.state = FlightCheck.DONE;
        c.result = 3;
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
        // embassy airfield: the players' markers and antennas pair among themselves, each embassy's virtual ones among its own (a
        // player's marker or antenna by an embassy's runway never takes its end); an embassy's ends - the core's painted numbers
        Map<Integer, List<IlsPairing.End>> endsBy = new TreeMap<Integer, List<IlsPairing.End>>();
        for (IlsPairing.End e : ends.values()) {
            group(endsBy, EmbassyAirportSVA.embassyOf(e.key)).add(e);
        }
        Map<Integer, List<IlsPairing.LocAnt>> locsBy = new TreeMap<Integer, List<IlsPairing.LocAnt>>();
        for (IlsPairing.LocAnt l : locs.values()) {
            group(locsBy, EmbassyAirportSVA.embassyOf(l.key)).add(l);
        }
        Map<Integer, List<IlsPairing.GsAnt>> gssBy = new TreeMap<Integer, List<IlsPairing.GsAnt>>();
        for (IlsPairing.GsAnt g : gss.values()) {
            group(gssBy, EmbassyAirportSVA.embassyOf(g.key)).add(g);
        }
        runways = new ArrayList<IlsPairing.Rwy>();
        Map<Integer, List<IlsPairing.Rwy>> rwysBy = new TreeMap<Integer, List<IlsPairing.Rwy>>();
        for (Map.Entry<Integer, List<IlsPairing.End>> g : endsBy.entrySet()) {
            List<IlsPairing.Rwy> rw = IlsPairing.runways(g.getValue());
            if (g.getKey().intValue() >= 0) {
                embassyNames(rw);
            }
            rwysBy.put(g.getKey(), rw);
            runways.addAll(rw);
        }
        IlsPairing.airports(runways);
        applyTowers();                                              // airport wave B2: the towers name their runways
        pairs = new ArrayList<IlsPairing.Pair>();
        for (Map.Entry<Integer, List<IlsPairing.Rwy>> g : rwysBy.entrySet()) {
            List<IlsPairing.LocAnt> ll = locsBy.get(g.getKey());
            List<IlsPairing.GsAnt> gl = gssBy.get(g.getKey());
            if (ll != null) {
                pairs.addAll(IlsPairing.pair(g.getValue(), ll, gl == null ? new ArrayList<IlsPairing.GsAnt>() : gl));
            }
        }
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
        List<IlsPairing.Pair> newVirtual = new ArrayList<IlsPairing.Pair>();
        for (IlsPairing.Pair p : pairs) {
            LinerIls.Station s = IlsPairing.station(p);
            for (Device d : devices.values()) {
                if (d.kind == K_MARKER && d.type != MarkerMath.NONE && d.rwy.equals(p.r.end(p.dir).key)) {
                    double[] l = p.r.local(p.dir, d.x, d.z);
                    s.markers[d.type] = (int) Math.round(-l[0]);
                }
            }
            boolean virt = EmbassyAirportSVA.isVirtual(p.key());
            int geo = geoHash(p);
            Integer was = checkedGeo.get(p.key());
            if (!virt && (was == null || was.intValue() != geo)) {
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
                if (virt) {
                    newVirtual.add(p);
                }
            }
            s.calib = check(p.key()).pack();                        // embassy airfield: a virtual one's - calibrated
            fresh.put(p.key(), s);
        }
        stations.clear();
        stations.putAll(fresh);
        // embassy airfield: a new virtual station's monitor at once - its category without waiting for STATUS_TICKS
        if (w != null && w.provider != null) {
            for (IlsPairing.Pair p : newVirtual) {
                if (p.loc.dim == w.provider.dimensionId) {
                    status(w, p, w.getTotalWorldTime());
                }
            }
        }
        // airport wave B2: a tower whose airport changed - its charts' version moves on
        for (Tower t : towers.values()) {
            int sig = chartSig(t);
            if (sig != t.chartSig) {
                if (t.chartSig != 0) {
                    t.chartVer++;
                }
                t.chartSig = sig;
                markDirty();
            }
        }
        publish(w, true);
    }

    /** The list of `k` in `m` (made when missing). */
    private static <T> List<T> group(Map<Integer, List<T>> m, int k) {
        Integer key = Integer.valueOf(k);
        List<T> l = m.get(key);
        if (l == null) {
            l = new ArrayList<T>();
            m.put(key, l);
        }
        return l;
    }

    /** Embassy airfield: an embassy's runways named by the numbers painted on its ends (the core's: the TRUE heading, no aptMagVar). */
    private static void embassyNames(List<IlsPairing.Rwy> rws) {
        for (IlsPairing.Rwy r : rws) {
            EmbassyFieldSVA f = EmbassyAirportSVA.INSTANCE.fieldOf(null, r.a.key);
            if (f == null || f.numberLow.length() == 0 || f.numberHigh.length() == 0) {
                continue;
            }
            boolean aLow = r.a.key.equals(EmbassyAirportMath.key(f.dim, f.thresholdKey(true)));
            r.nameA = aLow ? f.numberLow : f.numberHigh;
            r.nameB = aLow ? f.numberHigh : f.numberLow;
        }
    }

    /**
     * Airport wave B2: each runway to its nearest tower within TowerMath.RANGE (TowerMath.assign) - named by the tower's ICAO
     * code instead of «ZZxx»; the towers' runway lists and elevations. Embassy airfield: both ways - an embassy's runway only to its
     * own virtual tower, a player's runway never to an embassy's tower (Site.emb / rwyEmb: the registry's embassy ids).
     */
    void applyTowers() {
        endTower.clear();
        List<Tower> tl = new ArrayList<Tower>(towers.values());
        List<TowerMath.Site> sites = new ArrayList<TowerMath.Site>();
        for (Tower t : tl) {
            sites.add(new TowerMath.Site(t.dim, t.x + 0.5, t.z + 0.5, EmbassyAirportSVA.embassyOf(t.key)));
            t.rwys = new ArrayList<IlsPairing.Rwy>();
        }
        int[] rwyEmb = new int[runways.size()];
        for (int i = 0; i < runways.size(); i++) {
            rwyEmb[i] = EmbassyAirportSVA.embassyOf(runways.get(i).a.key);
        }
        int[] owner = TowerMath.assign(sites, runways, rwyEmb);
        for (int i = 0; i < runways.size(); i++) {
            if (owner[i] < 0) {
                continue;
            }
            Tower t = tl.get(owner[i]);
            IlsPairing.Rwy r = runways.get(i);
            if (t.icao.length() == 4) {
                r.apt = t.icao;
            }
            t.rwys.add(r);
            endTower.put(r.a.key, t);
            endTower.put(r.b.key, t);
        }
        for (Tower t : tl) {
            double e = 0;
            for (IlsPairing.Rwy r : t.rwys) {
                e += r.elev;
            }
            t.elev = t.rwys.isEmpty() ? t.y : (int) Math.round(e / t.rwys.size());
        }
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
                if (p.loc.dim == dim && !embassyAsleep(p, now)) {
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

    /**
     * Review 2026-10-27: an embassy's virtual station whose tower is offline (no player within TowerMath.ATC_RANGE - EmbassyAirportSVA.report)
     * is not monitored: nobody flies its beams, and every embassy with a field in the world would scan its areas every STATUS_TICKS. Its
     * status stands as it was; the tower's first report again - the next STATUS_TICKS measures it.
     */
    boolean embassyAsleep(IlsPairing.Pair p, long now) {
        int id = EmbassyAirportSVA.embassyOf(p.loc.key);
        if (id < 0) {
            return false;
        }
        String tk = EmbassyAirportSVA.INSTANCE.towerKey(id);
        Tower t = tk == null ? null : towers.get(tk);
        return t == null || !t.online(now);
    }

    /** The monitor of one station: what it is made of now -> IlsCat -> the station. @return changed */
    boolean status(World w, IlsPairing.Pair p, long now) {
        LinerIls.Station s = stations.get(p.key());
        if (s == null) {
            return false;
        }
        IlsCat.Setup u = new IlsCat.Setup();
        // the antennas: intact (the array / the mast whole), their shelters (monitor + power); embassy airfield: a virtual antenna has
        // no block - always whole, monitored and powered (the Tuners keep it)
        boolean virt = EmbassyAirportSVA.isVirtual(p.loc.key);
        if (virt) {
            p.loc.intact = true;
            p.loc.monitor = true;
            p.loc.power = true;
        } else if (loaded(w, p.loc.x, p.loc.z)) {
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
            if (EmbassyAirportSVA.isVirtual(p.gs.key)) {
                p.gs.intact = true;
                p.gs.monitor = true;
                p.gs.power = true;
            } else if (loaded(w, p.gs.x, p.gs.z)) {
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
        FlightCheck fc = check(p.key());                            // embassy airfield: a virtual station's - calibrated
        u.calibCat = virt || !ConfigSVA.aptFlightCheck ? 3 : fc.calibCat();
        Tower tw = endTower.get(p.r.a.key);
        u.towerOff = tw != null && tw.ilsOff.contains(p.key());     // airport wave B2: ILS ВЫКЛ on the tower's screen
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

    /**
     * The solid blocks over the surface in the areas (a sensitive count excludes its critical part); null: not loaded. Embassy airfield
     * (integration 2026-10-27): a virtual station's areas skip the field's protected zone and the dome - nobody else builds there (the core guards them),
     * and the Tuners' own decor in it (the windsock's mast, the fence's bars, the lamps - frangible) held both FULL ends at CAT II; nor does
     * the land's LIP count for it past them (naturalLip: the Tuners' calibration flight was flown over it - a one-block lip of grass where
     * the core's levelled strip ends, 6 off the runway's edge, held a FULL end at CAT II). Review 2026-10-27: only that lip - the top one or
     * two blocks of plain ground (naturalGround: grass, dirt, mycelium, sand, gravel, a snow layer) at the runway's level or one over it;
     * stone, sandstone, clay, terracotta, ice, a hill, what players build there, and the machines, all count. The areas' corners are asked
     * loaded first (scanEntities' way) - an unloaded station's scan costs nothing.
     */
    int[] scanBlocks(World w, IlsPairing.Pair p) {
        IlsCat.Area[] a = areas(p);
        int y0 = (int) Math.round(p.r.end(p.dir).elev);
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (a[i] != null && !cornersLoaded(w, p, a[i], y0)) {
                return null;
            }
        }
        EmbassyFieldSVA ef = EmbassyAirportSVA.isVirtual(p.loc.key) ? EmbassyAirportSVA.INSTANCE.fieldOf(w, p.loc.key) : null;
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
                if (ef != null && (EmbassyCompatSVA.inZone(ef, c[0], c[1]) || ef.inDome(c[0] + 0.5, c[1] + 0.5))) {
                    continue;                                       // the embassy's guarded field / its dome: its own (frangible) things only
                }
                for (int y = y0; y < y0 + IlsCat.CRIT_HEIGHT && y < 256; y++) {
                    if (obstacle(w, c[0], y, c[1]) && (ef == null || !lipAt(w, c[0], y, c[1], y0))) {
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

    /**
     * Embassy airfield: plain ground (grass, dirt, mycelium, sand, gravel, a snow layer) - what the lip where the core's levelled strip
     * ends is made of (review 2026-10-27: not stone, sandstone, clay, terracotta, a snow block or ice - players build with those).
     */
    public static boolean naturalGround(Block b) {
        return b == Blocks.grass || b == Blocks.dirt || b == Blocks.mycelium || b == Blocks.sand || b == Blocks.gravel || b == Blocks.snow_layer;
    }

    /**
     * The lip rule (pure - the self-test runs it): a block `dy` over the runway's level (0 / 1 only) of plain ground (`natural`) is the
     * land's lip when it is its column's top (`aboveClear` - nothing solid over it), or at the level itself under one more block of plain
     * ground that is the top (`aboveNatural`, `twoAboveClear`).
     */
    public static boolean naturalLip(int dy, boolean natural, boolean aboveClear, boolean aboveNatural, boolean twoAboveClear) {
        return natural && dy >= 0 && dy <= 1 && (aboveClear || dy == 0 && aboveNatural && twoAboveClear);
    }

    /** naturalLip at block (x, y, z) of a station whose runway's level is y0. */
    static boolean lipAt(World w, int x, int y, int z, int y0) {
        int dy = y - y0;
        if (dy < 0 || dy > 1 || !naturalGround(w.getBlock(x, y, z))) {
            return false;
        }
        boolean up = obstacle(w, x, y + 1, z);
        return naturalLip(dy, true, !up, up && naturalGround(w.getBlock(x, y + 1, z)), up && !obstacle(w, x, y + 2, z));
    }

    /** The area's four corners (the runway frame) are in loaded chunks. */
    static boolean cornersLoaded(World w, IlsPairing.Pair p, IlsCat.Area a, int y0) {
        double[][] cs = {{a.a0, a.c0}, {a.a1, a.c0}, {a.a0, a.c1}, {a.a1, a.c1}};
        for (double[] c : cs) {
            double[] wp = p.r.world(p.dir, c[0], c[1]);
            if (!w.blockExists((int) Math.floor(wp[0]), y0, (int) Math.floor(wp[1]))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A block that bends the beam: solid, not the airport's own (antennas, shelters, devices, runway, markers). Embassy airfield: nor
     * an embassy's own block (its paving, lights, fence, buildings - EmbassyCompatSVA.isEmbassyBlock; else they would hold its ILS at
     * CAT I).
     */
    public static boolean obstacle(World w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        if (b.isAir(w, x, y, z) || !b.getMaterial().blocksMovement()) {
            return false;
        }
        return !(b instanceof BlockIlsAntennaSVA || b instanceof BlockAptPartSVA || b instanceof BlockIlsShelterSVA || b instanceof BlockAptDeviceSVA
                || b instanceof BlockRunwayEndSVA || b instanceof BlockRunwaySVA || b instanceof BlockPapiSVA || b instanceof BlockApproachLightSVA
                || EmbassyCompatSVA.isEmbassyBlock(b));
    }

    /**
     * The entities in the areas (vehicles IlsCat.OCC_VEHICLE, creatures 1); null: not loaded. Embassy airfield: a virtual station counts
     * the machines and the players only - the embassy's own people (the Sentries at their posts by the windsock, its villagers) and the
     * animals on its kept field do not bend the Tuners' beams.
     */
    int[] scanEntities(World w, IlsPairing.Pair p) {
        IlsCat.Area[] a = areas(p);
        boolean virt = EmbassyAirportSVA.isVirtual(p.loc.key);
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
                } else if (en instanceof EntityLivingBase && (!virt || en instanceof net.minecraft.entity.player.EntityPlayer)) {
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
            if (p.loc.dim != dim || EmbassyAirportSVA.isVirtual(p.key())) {
                continue;                                           // embassy airfield: «облёт Наладчиков» - nothing to fly
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

    /**
     * The aircraft (what really flies) - the ATC sessions, the flight inspection. Code-only wave (decision 3): no longer «what
     * has a transponder» - with airdefense.allTransponders every vehicle has one, and a tower does not clear a buggy to land.
     */
    static List<com.sva.entity.EntityVehicleSVA> aircraft(World w) {
        List<com.sva.entity.EntityVehicleSVA> l = new ArrayList<com.sva.entity.EntityVehicleSVA>();
        for (Object o : w.loadedEntityList) {
            if (o instanceof com.sva.entity.EntityVehicleSVA) {
                com.sva.entity.EntityVehicleSVA v = (com.sva.entity.EntityVehicleSVA) o;
                if (!v.isDead && v.getVehicleType().movement.flies && com.sva.airdefense.XpdrMath.fitted(v.getVehicleType())) {
                    l.add(v);
                }
            }
        }
        return l;
    }

    /**
     * Code-only wave (decision 3): the vehicles whose transponders a tower hears (its traffic list) - every aircraft, and a
     * ground vehicle / the hoverbike only while a radar could see it (XpdrMath.towerHears; the radar rules stay).
     */
    public static List<com.sva.entity.EntityVehicleSVA> heard(World w) {
        List<com.sva.entity.EntityVehicleSVA> l = new ArrayList<com.sva.entity.EntityVehicleSVA>();
        boolean all = com.sva.ConfigSVA.adAllTransponders;
        for (Object o : w.loadedEntityList) {
            if (o instanceof com.sva.entity.EntityVehicleSVA) {
                com.sva.entity.EntityVehicleSVA v = (com.sva.entity.EntityVehicleSVA) o;
                com.sva.vehicle.VehicleType t = v.getVehicleType();
                if (!v.isDead && com.sva.airdefense.XpdrMath.towerHears(t, all, !t.movement.flies && com.sva.airdefense.AirDefenseSVA.radarVisible(v))) {
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
                    step = RunwayLayout.step(rwyMode(r.a), x.night, x.rain, prev);    // wave B2: the tower's step or the end's own
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

    /** The runways (the renderers' paint and lights), the stations' status, the wind's seed - of one dimension (nothing hidden). */
    public NBTTagCompound clientData(World w, int dim) {
        return clientData(w, dim, Collections.<String>emptySet());
    }

    /** A taxi light of an embassy the player does not know: kept in its place in the list (D_TAXI's indices), drawn never, told nothing. */
    public static final int HIDDEN_Y = -4096;

    /**
     * Embassy airfield: one player's copy - the records of `hidden` (EmbassyAirportSVA.hiddenKeys: the embassies he does not know) left
     * out (their taxi lights stay as blind places: the lit set D_TAXI counts by index); an embassy runway carries "Emb" (its id), "Ext"
     * (the paved blocks past each threshold) and "Ils" (bit 1 - an ILS over its end A, 2 - over B); the root "EmbSock" {x, y, z...} (the
     * windsocks' mast feet) and "EmbPapi" {x, y, z, heading x 10, angle x 10...} (each runway end's PAPI - EmbassyAirportMath.papi) of
     * the embassies he knows.
     */
    public NBTTagCompound clientData(World w, int dim, Set<String> hidden) {
        if (hidden == null) {
            hidden = Collections.<String>emptySet();
        }
        if (dirty) {
            rebuild(w);
        }
        NBTTagCompound t = new NBTTagCompound();
        t.setLong("WindSeed", windSeed(w));
        NBTTagList rl = new NBTTagList();
        for (IlsPairing.Rwy r : runways) {
            if (r.a.dim != dim || hidden.contains(r.a.key)) {
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
            e.setInteger("La", rwyMode(r.a));                       // airport wave B2: what burns (the tower's or the end's own)
            e.setInteger("Lb", rwyMode(r.b));
            e.setInteger("Pa", alsMode(r.a));
            e.setInteger("Pb", alsMode(r.b));
            e.setBoolean("Sa", r.a.als != 0);
            e.setBoolean("Sb", r.b.als != 0);
            int[] ra = rvr(r, 1), rb = rvr(r, -1);
            e.setInteger("Ra", ra[0]);
            e.setInteger("Rb", rb[0]);
            e.setString("Apt", r.apt);
            int emb = EmbassyAirportSVA.embassyOf(r.a.key);
            if (emb >= 0) {
                embassyRunway(e, r, emb);
            }
            rl.appendTag(e);
        }
        t.setTag("Rwy", rl);
        NBTTagList sl = new NBTTagList();
        for (LinerIls.Station s : stations.values()) {
            if (s.dim != dim || hidden.contains(s.key())) {
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
        // airport wave B2: the towers (their charts' version - an approach chart in hand checks it; the tower map) and the
        // taxi lights {x, y, z, kind - K_TAXI} in the graph's stable order (the lit ones come by D_TAXI, by index)
        NBTTagList tw = new NBTTagList();
        for (Tower x : towers.values()) {
            if (x.dim != dim || hidden.contains(x.key)) {
                continue;
            }
            NBTTagCompound e = new NBTTagCompound();
            e.setString("K", x.key);
            e.setString("Icao", x.icao);
            e.setString("Name", x.name);
            e.setInteger("Ver", x.chartVer);
            e.setIntArray("Pos", new int[] {x.x, x.y, x.z});
            e.setInteger("Lt", x.ltTaxi);
            tw.appendTag(e);
        }
        t.setTag("Twr", tw);
        List<Device> tx = devicesOfTaxi(dim);
        int[] ta = new int[tx.size() * 4];
        for (int i = 0; i < tx.size(); i++) {
            Device d = tx.get(i);
            if (hidden.contains(d.key)) {
                ta[i * 4 + 1] = HIDDEN_Y;                           // embassy airfield: an unknown embassy's - a blind place (an edge light under the world)
                ta[i * 4 + 3] = K_EDGE - K_TAXI;
                continue;
            }
            ta[i * 4] = (int) Math.floor(d.x);
            ta[i * 4 + 1] = (int) Math.floor(d.y);
            ta[i * 4 + 2] = (int) Math.floor(d.z);
            ta[i * 4 + 3] = d.kind - K_TAXI;
        }
        t.setIntArray("Tx", ta);
        t.setInteger("TxV", taxiVersion);
        embassyRoot(t, dim, hidden);
        return t;
    }

    /** Embassy airfield: a virtual runway's words for the clients' lights ("Emb", "Ext", "Ils"). */
    private static void embassyRunway(NBTTagCompound e, IlsPairing.Rwy r, int emb) {
        e.setInteger("Emb", emb);
        EmbassyFieldSVA f = EmbassyAirportSVA.INSTANCE.fieldOf(null, r.a.key);
        int ext = 0, ils = 0;
        if (f != null) {
            boolean aLow = r.a.key.equals(EmbassyAirportMath.key(f.dim, f.thresholdKey(true)));
            ext = EmbassyAirportMath.ext(f.end(true), f.thresholdKey(true));
            ils = EmbassyAirportMath.ilsBits(aLow, f.ilsLow, f.ilsHigh);
        }
        e.setDouble("Ext", ext);
        e.setInteger("Ils", ils);
    }

    /** Embassy airfield: the known embassies' windsocks ("EmbSock") and PAPI points ("EmbPapi") of one dimension. */
    private static void embassyRoot(NBTTagCompound t, int dim, Set<String> hidden) {
        List<int[]> sock = new ArrayList<int[]>(), papi = new ArrayList<int[]>();
        double angle = LinerIls.clampAngle(ConfigSVA.aptEmbassyGlideAngle);
        for (EmbassyFieldSVA f : EmbassyAirportSVA.INSTANCE.built()) {
            if (f.dim != dim || EmbassyAirportSVA.INSTANCE.hiddenIn(f.id, hidden)) {
                continue;
            }
            int[] s = f.windsock();
            if (s != null) {
                sock.add(s);
            }
            if (f.hasRunway()) {
                for (int e = 0; e < 2; e++) {
                    boolean low = e == 0;
                    int[] p = EmbassyAirportMath.papi(f.thresholdKey(low), low ? f.headingLow : f.headingHigh, f.width, angle);
                    if (p != null) {
                        papi.add(p);
                    }
                }
            }
        }
        t.setIntArray("EmbSock", flat(sock, 3));
        t.setIntArray("EmbPapi", flat(papi, 5));
    }

    /** The first `n` ints of each, one after the other. */
    private static int[] flat(List<int[]> l, int n) {
        int[] out = new int[l.size() * n];
        for (int i = 0; i < l.size(); i++) {
            System.arraycopy(l.get(i), 0, out, i * n, n);
        }
        return out;
    }

    /** Every taxi light of a dimension in the graph's order (taxi(dim)[2]). */
    @SuppressWarnings("unchecked")
    public List<Device> devicesOfTaxi(int dim) {
        return (List<Device>) taxi(dim)[2];
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
        towers.clear();
        NBTTagList tl = t.getTagList("Towers", 10);
        for (int i = 0; i < tl.tagCount(); i++) {
            Tower tw = Tower.read(tl.getCompoundTagAt(i));
            if (tw.key.length() > 0) {
                towers.put(tw.key, tw);
            }
        }
        taxiVersion++;
        taxiCache.clear();
        NBTTagList l = t.getTagList("Ends", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound e = l.getCompoundTagAt(i);
            IlsPairing.End en = new IlsPairing.End(e.getString("K"), e.getInteger("Dim"), e.getDouble("X"), e.getDouble("Z"), e.getDouble("Elev"),
                    e.getDouble("Facing"), e.getInteger("W"));
            en.lights = e.hasKey("Lights") ? Math.max(RunwayLayout.LIGHTS_TOWER, Math.min(RunwayLayout.STEPS, e.getInteger("Lights")))
                    : RunwayLayout.LIGHTS_AUTO;
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

    /** Embassy airfield: the virtual records (EmbassyAirportSVA's) are never saved - in no list, the inspections' neither. */
    @Override
    public void writeToNBT(NBTTagCompound t) {
        NBTTagList l = new NBTTagList();
        for (IlsPairing.End en : ends.values()) {
            if (EmbassyAirportSVA.isVirtual(en.key)) {
                continue;
            }
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
            if (EmbassyAirportSVA.isVirtual(a.key)) {
                continue;
            }
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
            if (EmbassyAirportSVA.isVirtual(a.key)) {
                continue;
            }
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
            if (EmbassyAirportSVA.isVirtual(d.key)) {
                continue;
            }
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
            if (EmbassyAirportSVA.isVirtual(c.getKey())) {
                continue;
            }
            NBTTagCompound e = new NBTTagCompound();
            e.setString("K", c.getKey());
            e.setInteger("W", c.getValue().pack());
            Integer g = checkedGeo.get(c.getKey());
            e.setInteger("Geo", g == null ? 0 : g.intValue());
            l.appendTag(e);
        }
        t.setTag("Checks", l);
        l = new NBTTagList();
        for (Tower tw : towers.values()) {
            if (EmbassyAirportSVA.isVirtual(tw.key)) {
                continue;
            }
            NBTTagCompound e = new NBTTagCompound();
            tw.write(e);
            l.appendTag(e);
        }
        t.setTag("Towers", l);
    }

    /** World tests: the counts of what is registered {ends, localizers, masts, devices}. */
    public int[] counts() {
        return new int[] {ends.size(), locs.size(), gss.size(), devices.size(), towers.size()};
    }
}
