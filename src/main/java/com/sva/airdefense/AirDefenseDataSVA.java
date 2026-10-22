package com.sva.airdefense;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/**
 * Air-defence wave: what has to outlive the blocks' chunks - every command post's zones (the pilots see them wherever the
 * post's chunk is) and every air-defence block placed (the per-player limits). One per save (the overworld's map storage,
 * as VehicleRegistrySVA); keys are "dim:x:y:z".
 * <p>
 * Audit 2026-10-22 (the friend / foe trust model) - what the trust needs beyond a post's chunk:
 * <ul>
 * <li>bug 18: ONE «ключ дня» secret per owner (secret(); every post of the owner gives the same day code; an older save's
 * first post loaded hands its own secret over);</li>
 * <li>bug 2 / 19: each command post's trust data (KpInfo: the day key on, its own codes, the Mode S white list, the trusted
 * airports, its access list) - a launcher whose post is off or unloaded still knows its friends;</li>
 * <li>bug 20: the operator's decisions - «свой на 10 минут», «только вести», the assignments, the 7600 decisions and
 * episodes - kept by the vehicle's persistent id (VehicleState.vehId) with an absolute expiry (total world ticks), saved;
 * the towers' IFF handoffs the same (one per vehicle and tower: two towers no longer overwrite each other); the launchers'
 * own 7600 memory (no post: the 2-minute grace).</li>
 * </ul>
 * Everything expired goes in prune() (AirDefenseSVA's cleanup, every 10 s).
 */
public class AirDefenseDataSVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_airdefense";
    /** Node kinds (the limits). */
    public static final int N_RADAR = 0, N_LAUNCHER = 1, N_KP = 2;

    private final Map<String, List<ZoneMath.Zone>> zones = new LinkedHashMap<String, List<ZoneMath.Zone>>();
    private final Map<String, Object[]> nodes = new LinkedHashMap<String, Object[]>();
    /** Audit 2026-10-22: the owners' day-key secrets (lower-case name), the posts' trust data, the IFF handoffs, the launchers' 7600 memory. */
    private final Map<String, String> secrets = new LinkedHashMap<String, String>();
    private final Map<String, KpInfo> infos = new LinkedHashMap<String, KpInfo>();
    private final Map<String, Handoff> handoffs = new LinkedHashMap<String, Handoff>();
    private final Map<String, NoRadioMath.Entry> freeNoRadio = new LinkedHashMap<String, NoRadioMath.Entry>();
    /** Bumped on every zone change (the pilots' copies are resent). */
    private int version;

    /** An assignment of a vehicle to a launcher (its key), until when; who gave it (audit 2026-10-22: the launch log says). */
    public static final class Asg {
        public final String launcher;
        public long until;
        public final String by;

        public Asg(String launcher, long until) {
            this(launcher, until, "");
        }

        public Asg(String launcher, long until, String by) {
            this.launcher = launcher;
            this.until = until;
            this.by = by == null ? "" : by;
        }
    }

    /**
     * A command post's trust data: its settings (mirrored by the post) and the operator's decisions by the vehicle's persistent
     * id (absolute expiry in total world ticks).
     */
    public static final class KpInfo {
        public String owner = "", name = "";
        public int dim;
        public boolean dayKey = true;
        public int[] codes = new int[0];
        public final List<Integer> whiteBoard = new ArrayList<Integer>();
        public final List<String> whiteOwner = new ArrayList<String>();
        public final List<String> trusted = new ArrayList<String>(), access = new ArrayList<String>();
        /** «свой на 10 минут» (and 7600 «свой»), «только вести»: vehId -> until. */
        public final Map<String, Long> grants = new LinkedHashMap<String, Long>(), holds = new LinkedHashMap<String, Long>();
        public final Map<String, Asg> assigned = new LinkedHashMap<String, Asg>();
        /** The 7600 episodes and decisions: vehId -> the entry (forgotten after the memory, NoRadioMath.expired). */
        public final Map<String, NoRadioMath.Entry> noRadio = new LinkedHashMap<String, NoRadioMath.Entry>();

        public boolean granted(String vid, long now) {
            Long g = grants.get(vid);
            return g != null && g.longValue() > now;
        }

        public boolean held(String vid, long now) {
            Long g = holds.get(vid);
            return g != null && g.longValue() > now;
        }

        /**
         * Mode S: the board on the white list - only with its owner (an entry not bound to an owner matches nobody; an older
         * save's - AirportCodeMath.ANY_OWNER - any owner until the post's settings are saved).
         */
        public boolean whitelisted(int board, String owner) {
            for (int i = 0; i < whiteBoard.size(); i++) {
                if (AirportCodeMath.whiteMatch(whiteBoard.get(i).intValue(), whiteOwner.get(i), board, owner)) {
                    return true;
                }
            }
            return false;
        }

        public boolean ownCode(int code) {
            if (!XpdrMath.valid(code)) {
                return false;
            }
            for (int c : codes) {
                if (c == code) {
                    return true;
                }
            }
            return false;
        }

        /** Drops the expired decisions; @return something went */
        boolean prune(long now, long nrMemory) {
            boolean ch = false;
            for (Map<String, Long> m : java.util.Arrays.asList(grants, holds)) {
                for (Iterator<Map.Entry<String, Long>> it = m.entrySet().iterator(); it.hasNext();) {
                    if (it.next().getValue().longValue() <= now) {
                        it.remove();
                        ch = true;
                    }
                }
            }
            for (Iterator<Map.Entry<String, Asg>> it = assigned.entrySet().iterator(); it.hasNext();) {
                if (it.next().getValue().until <= now) {
                    it.remove();
                    ch = true;
                }
            }
            for (Iterator<Map.Entry<String, NoRadioMath.Entry>> it = noRadio.entrySet().iterator(); it.hasNext();) {
                NoRadioMath.Entry e = it.next().getValue();
                if (NoRadioMath.expired(e, now, nrMemory) && (e.decision != NoRadioMath.D_FRIEND || e.until <= now)) {
                    it.remove();
                    ch = true;
                }
            }
            return ch;
        }

        void write(NBTTagCompound t) {
            t.setString("Own", owner);
            t.setString("Name", name);
            t.setInteger("Dim", dim);
            t.setBoolean("DayKey", dayKey);
            t.setIntArray("Codes", codes.clone());
            int[] wb = new int[whiteBoard.size()];
            NBTTagList wo = new NBTTagList();
            for (int i = 0; i < wb.length; i++) {
                wb[i] = whiteBoard.get(i).intValue();
                wo.appendTag(new net.minecraft.nbt.NBTTagString(whiteOwner.get(i)));
            }
            t.setIntArray("WB", wb);
            t.setTag("WO", wo);
            t.setTag("Trust", strings(trusted));
            t.setTag("Access", strings(access));
            t.setTag("Grants", times(grants));
            t.setTag("Holds", times(holds));
            NBTTagList al = new NBTTagList();
            for (Map.Entry<String, Asg> e : assigned.entrySet()) {
                NBTTagCompound c = new NBTTagCompound();
                c.setString("V", e.getKey());
                c.setString("L", e.getValue().launcher);
                c.setLong("U", e.getValue().until);
                c.setString("B", e.getValue().by);
                al.appendTag(c);
            }
            t.setTag("Asg", al);
            t.setTag("Nr", entries(noRadio));
        }

        void read(NBTTagCompound t) {
            owner = t.getString("Own");
            name = t.getString("Name");
            dim = t.getInteger("Dim");
            dayKey = !t.hasKey("DayKey") || t.getBoolean("DayKey");
            codes = t.getIntArray("Codes").clone();
            whiteBoard.clear();
            whiteOwner.clear();
            int[] wb = t.getIntArray("WB");
            NBTTagList wo = t.getTagList("WO", 8);
            for (int i = 0; i < wb.length; i++) {
                whiteBoard.add(Integer.valueOf(wb[i]));
                whiteOwner.add(i < wo.tagCount() ? wo.getStringTagAt(i) : "");
            }
            readStrings(t.getTagList("Trust", 8), trusted);
            readStrings(t.getTagList("Access", 8), access);
            readTimes(t.getTagList("Grants", 10), grants);
            readTimes(t.getTagList("Holds", 10), holds);
            assigned.clear();
            NBTTagList al = t.getTagList("Asg", 10);
            for (int i = 0; i < al.tagCount(); i++) {
                NBTTagCompound c = al.getCompoundTagAt(i);
                assigned.put(c.getString("V"), new Asg(c.getString("L"), c.getLong("U"), c.getString("B")));
            }
            readEntries(t.getTagList("Nr", 10), noRadio);
        }
    }

    /** A tower's IFF handoff of an aircraft (bug 20: by its persistent id and the tower; until when). */
    public static final class Handoff {
        public final String vid, tower, owner, icao;
        public final long until;

        public Handoff(String vid, String tower, String owner, String icao, long until) {
            this.vid = vid == null ? "" : vid;
            this.tower = tower == null ? "" : tower;
            this.owner = owner == null ? "" : owner;
            this.icao = icao == null ? "" : icao;
            this.until = until;
        }
    }

    public AirDefenseDataSVA(String name) {
        super(name);
    }

    public static AirDefenseDataSVA get(World w) {
        if (w == null || w.mapStorage == null) {
            return null;
        }
        AirDefenseDataSVA d = (AirDefenseDataSVA) w.mapStorage.loadData(AirDefenseDataSVA.class, NAME);
        if (d == null) {
            d = new AirDefenseDataSVA(NAME);
            w.mapStorage.setData(NAME, d);
        }
        return d;
    }

    public static String key(int dim, int x, int y, int z) {
        return dim + ":" + x + ":" + y + ":" + z;
    }

    // ------------------------------------------------------------------ zones

    /** A command post's zones (copies; each already carries the post's name, owner and position). */
    public void setZones(String kpKey, List<ZoneMath.Zone> list) {
        List<ZoneMath.Zone> c = new ArrayList<ZoneMath.Zone>();
        if (list != null) {
            for (ZoneMath.Zone z : list) {
                ZoneMath.Zone k = z.copy();
                k.tower = kpKey.startsWith(TOWER_PREFIX);          // audit 2026-10-22 (bug 7): only these trusted by name
                c.add(k);
            }
        }
        if (c.isEmpty()) {
            zones.remove(kpKey);
        } else {
            zones.put(kpKey, c);
        }
        version++;
        markDirty();
    }

    public void removeKp(String kpKey) {
        if (zones.remove(kpKey) != null) {
            version++;
            markDirty();
        }
        if (infos.remove(kpKey) != null) {
            markDirty();                                            // its trust data and decisions go with it
        }
    }

    /** Every zone of every command post. */
    public List<ZoneMath.Zone> all() {
        List<ZoneMath.Zone> out = new ArrayList<ZoneMath.Zone>();
        for (List<ZoneMath.Zone> l : zones.values()) {
            out.addAll(l);
        }
        return out;
    }

    /** Airport wave B2: every zone but those kept under a key prefix (the towers' «twr:» - the MCDU's database names their airports itself). */
    public List<ZoneMath.Zone> allExcept(String prefix) {
        List<ZoneMath.Zone> out = new ArrayList<ZoneMath.Zone>();
        for (Map.Entry<String, List<ZoneMath.Zone>> e : zones.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                out.addAll(e.getValue());
            }
        }
        return out;
    }

    /**
     * The zones of one owner's command posts in a dimension (a launcher without a command post uses them). Audit review
     * 2026-10-22: not his towers' aerodrome zones (they are kept here too, under «twr:») - their codes count only when the
     * tower gave them (AirportCodeMath.friendOf), and the launcher took them as its posts' own ranges.
     */
    public List<ZoneMath.Zone> ofOwner(String owner, int dim) {
        List<ZoneMath.Zone> out = new ArrayList<ZoneMath.Zone>();
        for (List<ZoneMath.Zone> l : zones.values()) {
            for (ZoneMath.Zone z : l) {
                if (z.dim == dim && !z.tower && z.owner.equalsIgnoreCase(owner == null ? "" : owner)) {
                    out.add(z);
                }
            }
        }
        return out;
    }

    /**
     * Audit 2026-10-22 (bug 1): the zones of one owner's command posts in every dimension, but those of the post `exceptKey`
     * (it counts its own list as edited) and the towers' («twr:», they keep their own aerodrome zone) - the
     * airdefense.maxZonesPerPlayer limit.
     */
    public int countOwnerZones(String owner, String exceptKey) {
        int n = 0;
        for (Map.Entry<String, List<ZoneMath.Zone>> e : zones.entrySet()) {
            if (e.getKey().equals(exceptKey) || e.getKey().startsWith("twr:")) {
                continue;
            }
            for (ZoneMath.Zone z : e.getValue()) {
                if (z.owner.equalsIgnoreCase(owner == null ? "" : owner)) {
                    n++;
                }
            }
        }
        return n;
    }

    public int version() {
        return version;
    }

    /** The towers' zone keys' prefix (AtcServerSVA.ZONE_KEY). */
    public static final String TOWER_PREFIX = "twr:";

    // ------------------------------------------------------------------ audit 2026-10-22: the trust model

    /** The owner's «ключ дня» secret ("" none yet). */
    public String secret(String owner) {
        String s = secrets.get(lower(owner));
        return s == null ? "" : s;
    }

    public void setSecret(String owner, String secret) {
        secrets.put(lower(owner), secret == null ? "" : secret);
        markDirty();
    }

    /**
     * The owner's secret, made when he has none: an older save's post hands its own (`legacy`, the first one loaded wins),
     * else `fresh`.
     */
    public String ownerSecret(String owner, String legacy, String fresh) {
        String s = secret(owner);
        if (s.length() == 0) {
            s = legacy != null && legacy.length() > 0 ? legacy : fresh == null ? "" : fresh;
            setSecret(owner, s);
        }
        return s;
    }

    /** A command post's trust data (made when missing and `make`). */
    public KpInfo info(String kpKey, boolean make) {
        KpInfo i = infos.get(kpKey);
        if (i == null && make) {
            i = new KpInfo();
            infos.put(kpKey, i);
            markDirty();
        }
        return i;
    }

    /** The trust data of an owner's posts in a dimension (keys with them). */
    public Map<String, KpInfo> infosOf(String owner, int dim) {
        Map<String, KpInfo> out = new LinkedHashMap<String, KpInfo>();
        for (Map.Entry<String, KpInfo> e : infos.entrySet()) {
            if (e.getValue().dim == dim && e.getValue().owner.equalsIgnoreCase(owner == null ? "" : owner)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** A tower hands an aircraft to the air defence (renewed while it lasts). */
    public void handoff(String vid, String tower, String owner, String icao, long until) {
        handoffs.put(vid + "|" + tower, new Handoff(vid, tower, owner, icao, until));
        markDirty();
    }

    /** One tower's handoff taken back (tower null: every tower's). */
    public void release(String vid, String tower) {
        boolean ch = false;
        for (Iterator<Map.Entry<String, Handoff>> it = handoffs.entrySet().iterator(); it.hasNext();) {
            Handoff h = it.next().getValue();
            if (h.vid.equals(vid) && (tower == null || h.tower.equals(tower))) {
                it.remove();
                ch = true;
            }
        }
        if (ch) {
            markDirty();
        }
    }

    /** The handoffs of a vehicle still in force. */
    public List<Handoff> handoffs(String vid, long now) {
        List<Handoff> out = new ArrayList<Handoff>();
        for (Handoff h : handoffs.values()) {
            if (h.vid.equals(vid) && h.until >= now) {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * The launchers' own 7600 memory of a vehicle (no command post - the 2-minute grace): squawking 7600 now - the episode
     * runs on (made when missing); another code - it pauses. Audit review 2026-10-22: only the launchers' own eyes (48
     * blocks) see it, so an episode not seen for NoRadioMath.GAP ended at its last sighting (seen) - it flew away still on
     * 7600 and its grace was gone for good, the entry kept in the save for ever. @return the entry (null: none and not
     * squawking 7600)
     */
    public NoRadioMath.Entry freeNoRadio(String vid, boolean squawking, long now) {
        NoRadioMath.Entry e = freeNoRadio.get(vid);
        if (squawking) {
            if (e == null) {
                e = new NoRadioMath.Entry(now);
                freeNoRadio.put(vid, e);
                markDirty();
            } else {
                boolean resumed = e.since < 0 || now - e.lastSeen > NoRadioMath.GAP;
                NoRadioMath.seen(e, now);
                if (resumed) {
                    markDirty();
                }
            }
        } else if (e != null && e.since >= 0) {
            NoRadioMath.changed(e, now);
            markDirty();
        }
        return e;
    }

    /** World tests: the launchers' 7600 memory of a vehicle as is (null none). */
    public NoRadioMath.Entry freeNoRadioEntry(String vid) {
        return freeNoRadio.get(vid);
    }

    /** Everything expired goes (`nrMemory`: how long a 7600 memory outlives its last episode, ticks). */
    public void prune(long now, long nrMemory) {
        boolean ch = false;
        for (KpInfo i : infos.values()) {
            ch |= i.prune(now, nrMemory);
        }
        for (Iterator<Map.Entry<String, Handoff>> it = handoffs.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().until < now) {
                it.remove();
                ch = true;
            }
        }
        for (Iterator<Map.Entry<String, NoRadioMath.Entry>> it = freeNoRadio.entrySet().iterator(); it.hasNext();) {
            if (NoRadioMath.expired(it.next().getValue(), now, nrMemory)) {
                it.remove();
                ch = true;
            }
        }
        if (ch) {
            markDirty();
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    static NBTTagList strings(List<String> l) {
        NBTTagList t = new NBTTagList();
        for (String s : l) {
            t.appendTag(new net.minecraft.nbt.NBTTagString(s));
        }
        return t;
    }

    static void readStrings(NBTTagList t, List<String> into) {
        into.clear();
        for (int i = 0; i < t.tagCount(); i++) {
            into.add(t.getStringTagAt(i));
        }
    }

    static NBTTagList times(Map<String, Long> m) {
        NBTTagList l = new NBTTagList();
        for (Map.Entry<String, Long> e : m.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("V", e.getKey());
            c.setLong("U", e.getValue().longValue());
            l.appendTag(c);
        }
        return l;
    }

    static void readTimes(NBTTagList l, Map<String, Long> into) {
        into.clear();
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound c = l.getCompoundTagAt(i);
            into.put(c.getString("V"), Long.valueOf(c.getLong("U")));
        }
    }

    static NBTTagList entries(Map<String, NoRadioMath.Entry> m) {
        NBTTagList l = new NBTTagList();
        for (Map.Entry<String, NoRadioMath.Entry> e : m.entrySet()) {
            NoRadioMath.Entry n = e.getValue();
            NBTTagCompound c = new NBTTagCompound();
            c.setString("V", e.getKey());
            c.setLong("S", n.since);
            c.setLong("Us", n.used);
            c.setLong("L", n.lastSeen);
            c.setLong("U", n.until);
            c.setByte("D", (byte) n.decision);
            c.setBoolean("Dl", n.defaultLogged);
            l.appendTag(c);
        }
        return l;
    }

    static void readEntries(NBTTagList l, Map<String, NoRadioMath.Entry> into) {
        into.clear();
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound c = l.getCompoundTagAt(i);
            NoRadioMath.Entry n = new NoRadioMath.Entry(c.getLong("S"));
            n.used = c.getLong("Us");
            n.lastSeen = c.getLong("L");
            n.until = c.getLong("U");
            n.decision = c.getByte("D");
            n.defaultLogged = c.getBoolean("Dl");
            into.put(c.getString("V"), n);
        }
    }

    // ------------------------------------------------------------------ the blocks placed (limits)

    public void addNode(String key, int kind, String owner) {
        nodes.put(key, new Object[] {Integer.valueOf(kind), owner == null ? "" : owner});
        nodesVersion++;
        markDirty();
    }

    public void removeNode(String key) {
        if (nodes.remove(key) != null) {
            nodesVersion++;
            markDirty();
        }
    }

    /** Audit 2026-10-22 (bug 10): bumped on every node placed / removed (the terrain memory's sites change). */
    private int nodesVersion;
    private final Map<Integer, List<int[]>> sitesCache = new java.util.HashMap<Integer, List<int[]>>();
    private int sitesAt = -1;

    public int nodesVersion() {
        return nodesVersion;
    }

    /**
     * Audit 2026-10-22 (bug 10): the air-defence sites of a dimension the terrain memory serves - {x, z, reach}: a radar
     * reaches `radarReach`, a launcher TerrainMath.LAUNCHER_REACH; a command post sees nothing itself.
     */
    public List<int[]> sites(int dim, int radarReach) {
        if (sitesAt != nodesVersion) {
            sitesCache.clear();
            sitesAt = nodesVersion;
        }
        Integer d = Integer.valueOf(dim);
        List<int[]> l = sitesCache.get(d);
        if (l == null || l.size() > 0 && l.get(0)[3] != radarReach) {
            l = new ArrayList<int[]>();
            for (Map.Entry<String, Object[]> e : nodes.entrySet()) {
                int kind = ((Integer) e.getValue()[0]).intValue();
                String[] p = e.getKey().split(":");
                if (kind == N_KP || p.length != 4) {
                    continue;
                }
                try {
                    if (Integer.parseInt(p[0]) == dim) {
                        l.add(new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[3]), kind == N_RADAR ? radarReach : TerrainMath.LAUNCHER_REACH,
                            radarReach});
                    }
                } catch (NumberFormatException ex) {
                    // not a block's key (a test's): no site
                }
            }
            sitesCache.put(d, l);
        }
        return l;
    }

    public boolean hasNode(String key) {
        return nodes.containsKey(key);
    }

    public int count(String owner, int kind) {
        int n = 0;
        for (Object[] o : nodes.values()) {
            if (((Integer) o[0]).intValue() == kind && ((String) o[1]).equalsIgnoreCase(owner == null ? "" : owner)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public void readFromNBT(NBTTagCompound t) {
        zones.clear();
        nodes.clear();
        NBTTagList zl = t.getTagList("Zones", 10);
        for (int i = 0; i < zl.tagCount(); i++) {
            NBTTagCompound e = zl.getCompoundTagAt(i);
            List<ZoneMath.Zone> l = new ArrayList<ZoneMath.Zone>();
            NBTTagList in = e.getTagList("List", 10);
            for (int k = 0; k < in.tagCount() && k < ZoneMath.MAX_ZONES; k++) {
                ZoneMath.Zone z = new ZoneMath.Zone();
                z.read(in.getCompoundTagAt(k));
                z.tower = e.getString("Kp").startsWith(TOWER_PREFIX);
                l.add(z);
            }
            if (!l.isEmpty()) {
                zones.put(e.getString("Kp"), l);
            }
        }
        NBTTagList nl = t.getTagList("Nodes", 10);
        for (int i = 0; i < nl.tagCount(); i++) {
            NBTTagCompound e = nl.getCompoundTagAt(i);
            nodes.put(e.getString("K"), new Object[] {Integer.valueOf(e.getInteger("T")), e.getString("O")});
        }
        nodesVersion++;
        // audit 2026-10-22: the trust model
        secrets.clear();
        NBTTagList sl = t.getTagList("Secrets", 10);
        for (int i = 0; i < sl.tagCount(); i++) {
            secrets.put(sl.getCompoundTagAt(i).getString("O"), sl.getCompoundTagAt(i).getString("S"));
        }
        infos.clear();
        NBTTagList il = t.getTagList("KpInfo", 10);
        for (int i = 0; i < il.tagCount(); i++) {
            KpInfo k = new KpInfo();
            k.read(il.getCompoundTagAt(i));
            infos.put(il.getCompoundTagAt(i).getString("K"), k);
        }
        handoffs.clear();
        NBTTagList hl = t.getTagList("Handoffs", 10);
        for (int i = 0; i < hl.tagCount(); i++) {
            NBTTagCompound c = hl.getCompoundTagAt(i);
            Handoff h = new Handoff(c.getString("V"), c.getString("T"), c.getString("O"), c.getString("I"), c.getLong("U"));
            handoffs.put(h.vid + "|" + h.tower, h);
        }
        readEntries(t.getTagList("FreeNr", 10), freeNoRadio);
        version++;
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        NBTTagList zl = new NBTTagList();
        for (Map.Entry<String, List<ZoneMath.Zone>> e : zones.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("Kp", e.getKey());
            NBTTagList in = new NBTTagList();
            for (ZoneMath.Zone z : e.getValue()) {
                NBTTagCompound zt = new NBTTagCompound();
                z.write(zt);
                in.appendTag(zt);
            }
            c.setTag("List", in);
            zl.appendTag(c);
        }
        t.setTag("Zones", zl);
        NBTTagList nl = new NBTTagList();
        for (Map.Entry<String, Object[]> e : nodes.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("K", e.getKey());
            c.setInteger("T", ((Integer) e.getValue()[0]).intValue());
            c.setString("O", (String) e.getValue()[1]);
            nl.appendTag(c);
        }
        t.setTag("Nodes", nl);
        NBTTagList sl = new NBTTagList();
        for (Map.Entry<String, String> e : secrets.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("O", e.getKey());
            c.setString("S", e.getValue());
            sl.appendTag(c);
        }
        t.setTag("Secrets", sl);
        NBTTagList il = new NBTTagList();
        for (Map.Entry<String, KpInfo> e : infos.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("K", e.getKey());
            e.getValue().write(c);
            il.appendTag(c);
        }
        t.setTag("KpInfo", il);
        NBTTagList hl = new NBTTagList();
        for (Handoff h : handoffs.values()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("V", h.vid);
            c.setString("T", h.tower);
            c.setString("O", h.owner);
            c.setString("I", h.icao);
            c.setLong("U", h.until);
            hl.appendTag(c);
        }
        t.setTag("Handoffs", hl);
        t.setTag("FreeNr", entries(freeNoRadio));
    }
}
