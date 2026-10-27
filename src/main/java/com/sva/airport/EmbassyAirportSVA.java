package com.sva.airport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.sva.ConfigSVA;
import com.sva.block.AirportDataSVA;
import com.sva.block.AirportServerSVA;
import com.sva.compat.EmbassyCompatSVA;
import com.sva.compat.EmbassyFieldSVA;
import com.sva.vehicle.LinerIls;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

/**
 * PACKAGE A (docs/plan-embassy-airfield-addon.md §3.3, §3.7): the Tuners' embassies' airfields as VIRTUAL airports of block.AirportDataSVA
 * - made from EmbassyCompatSVA.fields(overworld) at the world's load and whenever EmbassyCompatSVA.revision moves (or the hook's
 * airfieldsChanged marked it dirty), never saved (AirportDataSVA / IlsDataSVA leave isVirtual keys out of their NBT):
 * <ul>
 * <li>keys "dim:x:y:z" of the API's points (EmbassyAirportMath): the runway ends at thresholdKey(low / high) (facing headingLow /
 * headingHigh, width, elev floorY + 1, lights LIGHTS_TOWER, the ALS only at a FULL end with an ILS), the tower at towerDesk() (SHORT /
 * HELI: the helipad's column - columns()'s last) - owner EmbassyCompatSVA.ownerOf(id), ICAO EmbassyAirportMath.uniqueIcao(info.icao, the
 * others'), codes 05100..05177 (EmbassyAtcMath); the localizer and the glide slope (FULL, only the ends with ilsLow / ilsHigh) at
 * localizer(low) / glideSlope(low), angle ConfigSVA.aptEmbassyGlideAngle, calibrated («облёт Наладчиков» - no FlightCheck), powered and
 * monitored; the windsock (K_SOCK) at windsock(); the taxi lights (K_TAXI along taxiway «А»'s centre abreast the core's edge lamps,
 * K_STOP at the holding point, K_STAND at each stand - the tower's taxi links join the stands to the taxiway);</li>
 * <li>the tower online (AirportDataSVA.towerReport every REPORT_EVERY ticks) while a player of the Overworld is within
 * TowerMath.ATC_RANGE of it;</li>
 * <li>per player (by UUID): the embassies he knows (EmbassyCompatSVA.knows - checked every KNOW_TICKS); a change re-syncs his airport
 * copy (AirportServerSVA.resync) - the others' embassies are left out of his copy (hiddenKeys) and of his MCDU's database
 * (NavDataSVA.db with its viewers - keyed by what they know, so an old database of his is never handed out again).</li>
 * </ul>
 * The records are made in a fixed order (the embassies by id) and the ICAO codes from the players' towers' codes alone, so a server's
 * restart gives every embassy the same code again. A player's saved record already at one of the points (never in practice: the field
 * is protected) keeps its place - the embassy's record there is left out.
 * <p>
 * AirportServerSVA.onTick calls tick(w, d, now) for every world before AirportDataSVA.tick (the coordinator's hook); clear() at the
 * server's stop. The server thread's (the maps are concurrent: a save or a client thread of the same JVM may read the registry).
 */
public final class EmbassyAirportSVA {

    public static final EmbassyAirportSVA INSTANCE = new EmbassyAirportSVA();
    /** The tower's report period while a player is near (ticks; AirportDataSVA.REPORT_TICKS is 60), the players' knowledge check (ticks). */
    public static final int REPORT_EVERY = 20, KNOW_TICKS = 100;
    /** The embassies' airfields are the Overworld's. */
    public static final int DIM = 0;

    /** Every virtual record's key -> its embassy's id (any thread reads it: the save's writer). */
    private static final Map<String, Integer> VIRTUAL = new ConcurrentHashMap<String, Integer>();

    /** The API's revision the records were made at (-1: never), marked dirty by the hook. */
    private volatile int builtAt = -1;
    private volatile boolean dirty = true;
    /** Per player (UUID string): the embassy ids he knew at the last check. */
    private final Map<String, Set<Integer>> known = new ConcurrentHashMap<String, Set<Integer>>();
    /** Per embassy id: the airfield its records were made of, their keys, its tower's key. */
    private final Map<Integer, EmbassyFieldSVA> built = new ConcurrentHashMap<Integer, EmbassyFieldSVA>();
    private final Map<Integer, Set<String>> keysOf = new ConcurrentHashMap<Integer, Set<String>>();
    private final Map<Integer, String> towerKeys = new ConcurrentHashMap<Integer, String>();

    private static final Comparator<EmbassyFieldSVA> BY_ID = new Comparator<EmbassyFieldSVA>() {
        @Override
        public int compare(EmbassyFieldSVA a, EmbassyFieldSVA b) {
            return a.id < b.id ? -1 : a.id == b.id ? 0 : 1;
        }
    };

    private EmbassyAirportSVA() {
    }

    // ------------------------------------------------------------------ the virtual keys (AirportDataSVA / IlsDataSVA ask)

    /** The record of `key` is an embassy's virtual one (never saved). */
    public static boolean isVirtual(String key) {
        return key != null && VIRTUAL.containsKey(key);
    }

    /** The embassy (id) a virtual record belongs to (-1: not virtual). */
    public static int embassyOf(String key) {
        Integer i = key == null ? null : VIRTUAL.get(key);
        return i == null ? -1 : i.intValue();
    }

    /** A virtual record put in (A's rebuild). */
    public static void markVirtual(String key, int embassyId) {
        if (key != null) {
            VIRTUAL.put(key, Integer.valueOf(embassyId));
        }
    }

    /** Every virtual key (a copy). */
    public static Set<String> virtualKeys() {
        return new HashSet<String>(VIRTUAL.keySet());
    }

    /**
     * Self-tests: a key they marked taken out again (their own keys only - never clear() in a running world: the data would then save
     * the real embassies' records).
     */
    public static void unmarkVirtual(String key) {
        if (key != null) {
            VIRTUAL.remove(key);
        }
    }

    // ------------------------------------------------------------------ the life

    /** The hook (from the world generation): only marked - tick() rebuilds. */
    public void markDirty(World w, int revision) {
        dirty = true;
    }

    /** Every server tick of every world, before AirportDataSVA.tick (AirportServerSVA). */
    public void tick(World w, AirportDataSVA d, long now) {
        if (w == null || w.isRemote || d == null || w.provider == null || w.provider.dimensionId != DIM) {
            return;
        }
        if (!ConfigSVA.aptEmbassyAirports || !EmbassyCompatSVA.on()) {
            if (!VIRTUAL.isEmpty() || !built.isEmpty()) {
                takeDown(d);                                        // switched off (or the core's API went away): the records go
            }
            return;
        }
        int rev = EmbassyCompatSVA.revision(w);
        if (dirty || rev != builtAt) {
            dirty = false;
            builtAt = rev;
            rebuild(w, d);
        }
        if (built.isEmpty()) {
            return;
        }
        if (now % REPORT_EVERY == 0) {
            report(w, d, now);
        }
        if (now % KNOW_TICKS == 0) {
            knowledge(w);
        }
    }

    /** The server stopped: the records' registry, the players' knowledge. */
    public void clear() {
        VIRTUAL.clear();
        known.clear();
        built.clear();
        keysOf.clear();
        towerKeys.clear();
        builtAt = -1;
        dirty = true;
    }

    // ------------------------------------------------------------------ what the others ask

    /**
     * The embassy ids `p` knows now (the last check; empty: none, off, a FakePlayer, a client's player). Never checked yet (a login, a
     * rebuild) - asked now; out of the Overworld the core answers nothing (its embassies are another world's), so the last check stands.
     */
    public Set<Integer> knownBy(EntityPlayer p) {
        if (p == null || p instanceof FakePlayer || p.worldObj == null || p.worldObj.isRemote || p.worldObj.provider == null || built.isEmpty()) {
            return Collections.<Integer>emptySet();
        }
        String u = p.getUniqueID().toString();
        Set<Integer> s = known.get(u);
        if (s == null) {
            if (p.worldObj.provider.dimensionId != DIM) {
                return Collections.<Integer>emptySet();
            }
            s = ask(p);
            known.put(u, s);
        }
        return s;
    }

    /**
     * The virtual keys of the embassies `p` does not know in his own dimension (his airport copy leaves them out; empty: nothing hidden).
     * Null: as one who knows nothing, in the Overworld.
     */
    public Set<String> hiddenKeys(EntityPlayer p) {
        Set<String> out = new HashSet<String>();
        if (built.isEmpty()) {
            return out;
        }
        int dim = p == null || p.worldObj == null || p.worldObj.provider == null ? DIM : p.worldObj.provider.dimensionId;
        Set<Integer> k = null;
        for (EmbassyFieldSVA f : built.values()) {
            if (f.dim != dim) {
                continue;
            }
            if (k == null) {
                k = p == null ? Collections.<Integer>emptySet() : knownBy(p);
            }
            Set<String> ks = keysOf.get(Integer.valueOf(f.id));
            if (ks != null && !k.contains(Integer.valueOf(f.id))) {
                out.addAll(ks);
            }
        }
        return out;
    }

    /** The virtual tower's key of embassy `id` (null: none). */
    public String towerKey(int id) {
        return towerKeys.get(Integer.valueOf(id));
    }

    /** The airfield a virtual record (a tower, a runway end, an antenna) belongs to (null: not virtual / gone). */
    public EmbassyFieldSVA fieldOf(World w, String key) {
        int id = embassyOf(key);
        return id < 0 ? null : built.get(Integer.valueOf(id));
    }

    /** The airfields the records are made of now, by id (AirportDataSVA.clientData's windsocks and PAPI; empty: none, off). */
    public List<EmbassyFieldSVA> built() {
        List<EmbassyFieldSVA> l = new ArrayList<EmbassyFieldSVA>(built.values());
        Collections.sort(l, BY_ID);
        return l;
    }

    /** Embassy `id`'s virtual keys (a copy; empty: none). */
    public Set<String> keysOf(int id) {
        Set<String> s = keysOf.get(Integer.valueOf(id));
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    /** Embassy `id` is left out of a copy made with `hidden` (hiddenKeys - all of an embassy's keys or none of them). */
    public boolean hiddenIn(int id, Set<String> hidden) {
        Set<String> s = keysOf.get(Integer.valueOf(id));
        if (s == null || hidden == null || hidden.isEmpty()) {
            return false;
        }
        for (String k : s) {
            if (hidden.contains(k)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ inside: the records

    /** One embassy's records before they go in (the ICAO and what hangs on it are set while putting). */
    private static final class Recs {
        EmbassyFieldSVA f;
        final List<IlsPairing.End> ends = new ArrayList<IlsPairing.End>();
        String towerKey;
        int[] towerAt;
        final List<IlsPairing.LocAnt> locs = new ArrayList<IlsPairing.LocAnt>();
        final List<IlsPairing.GsAnt> gss = new ArrayList<IlsPairing.GsAnt>();
        final List<AirportDataSVA.Device> devices = new ArrayList<AirportDataSVA.Device>();
        final Set<String> keys = new LinkedHashSet<String>();
    }

    /** Every embassy's records again: the gone keys out, the others put (a kept tower keeps its runtime: report, ATIS, runway in use). */
    private void rebuild(World w, AirportDataSVA d) {
        List<EmbassyFieldSVA> fs = EmbassyCompatSVA.fields(w);
        Collections.sort(fs, BY_ID);
        List<Recs> all = new ArrayList<Recs>();
        Set<String> fresh = new HashSet<String>();
        for (EmbassyFieldSVA f : fs) {
            if (f.dim != DIM) {
                continue;
            }
            Recs r = plan(d, f);
            all.add(r);
            fresh.addAll(r.keys);
        }
        // the gone ones out first (their codes are free again)
        int gone = 0;
        for (String k : virtualKeys()) {
            if (!fresh.contains(k)) {
                d.remove(k);
                VIRTUAL.remove(k);
                gone++;
            }
        }
        // the ICAO codes: from the players' towers' codes and the ones given in this pass alone (the same order - the same codes)
        List<String> taken = new ArrayList<String>();
        for (AirportDataSVA.Tower t : d.towers()) {
            if (!isVirtual(t.key)) {
                taken.add(t.icao);
            }
        }
        built.clear();
        keysOf.clear();
        towerKeys.clear();
        for (Recs r : all) {
            put(d, r, taken);
        }
        d.taxiChanged();                                            // the towers' taxi links: the graph again
        known.clear();                                              // asked again on demand (the keys' owners may have changed)
        if (!all.isEmpty() || gone > 0) {
            System.out.println("[SVA-EMB] virtual airports: " + all.size() + " embassy airfield(s), " + fresh.size() + " records, " + gone
                    + " gone (revision " + builtAt + ")");
        }
    }

    /** The records of one airfield (keys, places, kinds; a point a player's saved record holds is left out). */
    private Recs plan(AirportDataSVA d, EmbassyFieldSVA f) {
        Recs r = new Recs();
        r.f = f;
        int dim = f.dim;
        double elev = EmbassyAirportMath.endElev(f.floorY);
        // the runway's ends: the threshold lights' rows (the virtual runway runs between them)
        if (f.hasRunway()) {
            for (int e = 0; e < 2; e++) {
                boolean low = e == 0;
                int[] p = f.thresholdKey(low);
                String k = EmbassyAirportMath.key(dim, p);
                if (k == null || takenByPlayer(d, k) || r.keys.contains(k)) {
                    continue;
                }
                IlsPairing.End en = new IlsPairing.End(k, dim, p[0] + 0.5, p[2] + 0.5, elev, low ? f.headingLow : f.headingHigh, f.width);
                en.lights = RunwayLayout.LIGHTS_TOWER;
                en.als = f.full() && (low ? f.ilsLow : f.ilsHigh) ? 1 : 0;
                r.ends.add(en);
                r.keys.add(k);
            }
        }
        // the tower: the desk (FULL), else the helipad's column
        int[] tp = f.towerDesk();
        if (tp == null) {
            int[][] c = f.columns();
            tp = c.length == 0 ? null : c[c.length - 1];
        }
        String tk = EmbassyAirportMath.key(dim, tp);
        if (tk != null && !takenByPlayer(d, tk) && !r.keys.contains(tk)) {
            r.towerKey = tk;
            r.towerAt = tp;
            r.keys.add(tk);
        }
        // the ILS: FULL, only the ends the core cleared for one (and only with the tower: its code names the ident)
        if (f.full() && r.towerKey != null) {
            for (int e = 0; e < 2; e++) {
                boolean low = e == 0;
                if (!(low ? f.ilsLow : f.ilsHigh)) {
                    continue;
                }
                int[] lp = f.localizer(low), gp = f.glideSlope(low);
                String lk = EmbassyAirportMath.key(dim, lp), gk = EmbassyAirportMath.key(dim, gp);
                if (lk == null || takenByPlayer(d, lk) || r.keys.contains(lk)) {
                    continue;
                }
                IlsPairing.LocAnt l = new IlsPairing.LocAnt();
                l.key = lk;
                l.dim = dim;
                l.x = lp[0] + 0.5;
                l.z = lp[2] + 0.5;
                l.y = elev;
                l.freq = EmbassyAirportMath.locFreq(f.id, low);
                l.intact = true;
                l.monitor = true;
                l.power = true;
                r.locs.add(l);
                r.keys.add(lk);
                if (gk != null && !takenByPlayer(d, gk) && !r.keys.contains(gk)) {
                    IlsPairing.GsAnt g = new IlsPairing.GsAnt();
                    g.key = gk;
                    g.dim = dim;
                    g.x = gp[0] + 0.5;
                    g.z = gp[2] + 0.5;
                    g.y = elev;
                    g.angle = LinerIls.clampAngle(ConfigSVA.aptEmbassyGlideAngle);
                    g.intact = true;
                    g.monitor = true;
                    g.power = true;
                    r.gss.add(g);
                    r.keys.add(gk);
                }
            }
        }
        // the windsock (its cone by the wind - the clients, "EmbSock")
        device(d, r, f.windsock(), AirportDataSVA.K_SOCK);
        // taxiway «А»: the centre line abreast the core's edge lamps, the stop bar where it leaves the +a pad
        int[] taxi = f.taxiBox();
        if (taxi != null) {
            int ty = f.floorY + 1;
            for (int[] c : EmbassyAirportMath.taxiCentre(f.lights(EmbassyCompatSVA.LIGHT_TAXI), taxi, f.alongX)) {
                device(d, r, new int[] {c[0], ty, c[1]}, AirportDataSVA.K_TAXI);
            }
            int[] h = EmbassyAirportMath.holdPoint(f.padBox(false), taxi, f.alongX, EmbassyAirportMath.plusA(f.end(true), f.end(false), f.alongX));
            if (h != null) {
                device(d, r, new int[] {h[0], ty, h[1]}, AirportDataSVA.K_STOP);
            }
        }
        // the stands («МС-1..4», «Н»): a stand light in each one's middle
        for (int i = 0; i < f.spots(); i++) {
            int[] m = EmbassyAirportMath.middle(f.spotBox(i));
            if (m != null) {
                device(d, r, new int[] {m[0], f.floorY + 1, m[1]}, AirportDataSVA.K_STAND);
            }
        }
        return r;
    }

    /** A device of `kind` at block `p` {x, y, z} into the records (its key free). */
    private static void device(AirportDataSVA d, Recs r, int[] p, int kind) {
        String k = EmbassyAirportMath.key(r.f.dim, p);
        if (k == null || takenByPlayer(d, k) || r.keys.contains(k)) {
            return;
        }
        AirportDataSVA.Device dv = new AirportDataSVA.Device();
        dv.key = k;
        dv.dim = r.f.dim;
        dv.kind = kind;
        dv.x = p[0] + 0.5;
        dv.y = p[1];
        dv.z = p[2] + 0.5;
        r.devices.add(dv);
        r.keys.add(k);
    }

    /** A player's (saved) record holds `key` - the embassy's is left out there. */
    private static boolean takenByPlayer(AirportDataSVA d, String key) {
        return d.has(key) && !isVirtual(key);
    }

    /** One embassy's records into the data (keys marked first - a save between never writes one). */
    private void put(AirportDataSVA d, Recs r, List<String> taken) {
        EmbassyFieldSVA f = r.f;
        Integer id = Integer.valueOf(f.id);
        for (String k : r.keys) {
            markVirtual(k, f.id);
        }
        for (IlsPairing.End e : r.ends) {
            d.putEnd(e);
        }
        String icao = "";
        if (r.towerKey != null) {
            AirportDataSVA.Tower t = d.tower(r.towerKey);
            if (t == null) {
                t = new AirportDataSVA.Tower();
            }
            t.key = r.towerKey;
            t.dim = f.dim;
            t.x = r.towerAt[0];
            t.y = r.towerAt[1];
            t.z = r.towerAt[2];
            t.owner = EmbassyCompatSVA.ownerOf(f.id);
            icao = EmbassyAirportMath.uniqueIcao(f.icao, taken);
            if (icao.length() == 4) {
                taken.add(icao);
            }
            t.icao = icao;
            t.name = "";                                            // the clients show the ICAO (the embassy's name is each client's to translate)
            t.codeLo = EmbassyAtcMath.CODE_LO;
            t.codeHi = EmbassyAtcMath.CODE_HI;
            t.taxiExtra.clear();
            t.taxiExtra.addAll(standLinks(r));
            t.taxiCut.clear();
            d.putTower(t);
            towerKeys.put(id, r.towerKey);
        }
        for (IlsPairing.LocAnt l : r.locs) {
            l.ident = EmbassyAirportMath.locIdent(icao.length() == 4 ? icao : f.icao);
            d.putLoc(l);
            l.intact = true;                                        // putLoc keeps an old record's monitor / power: ours are always on
            l.monitor = true;
            l.power = true;
        }
        for (IlsPairing.GsAnt g : r.gss) {
            d.putGs(g);
            g.intact = true;
            g.monitor = true;
            g.power = true;
        }
        for (AirportDataSVA.Device dv : r.devices) {
            d.putDevice(dv);
        }
        built.put(id, f);
        keysOf.put(id, Collections.unmodifiableSet(new LinkedHashSet<String>(r.keys)));
    }

    /**
     * The tower's taxi links: each stand of a machine («МС-1..4»; not the helipad - nothing taxis there) to the nearest centre-line light
     * of taxiway «А» - the apron is open paving, so «Следуй за мной» leads from the taxiway straight to the stand.
     */
    private static Set<String> standLinks(Recs r) {
        Set<String> out = new LinkedHashSet<String>();
        List<AirportDataSVA.Device> centre = new ArrayList<AirportDataSVA.Device>();
        for (AirportDataSVA.Device dv : r.devices) {
            if (dv.kind == AirportDataSVA.K_TAXI) {
                centre.add(dv);
            }
        }
        if (centre.isEmpty()) {
            return out;
        }
        for (int i = 0; i < r.f.spots(); i++) {
            if (r.f.spotKind(i) == EmbassyCompatSVA.SPOT_HELI) {
                continue;
            }
            int[] m = EmbassyAirportMath.middle(r.f.spotBox(i));
            String sk = m == null ? null : EmbassyAirportMath.key(r.f.dim, new int[] {m[0], r.f.floorY + 1, m[1]});
            if (sk == null || !r.keys.contains(sk)) {
                continue;
            }
            AirportDataSVA.Device best = null;
            double bd = Double.MAX_VALUE;
            for (AirportDataSVA.Device c : centre) {
                double dd = (c.x - m[0] - 0.5) * (c.x - m[0] - 0.5) + (c.z - m[1] - 0.5) * (c.z - m[1] - 0.5);
                if (dd < bd) {
                    bd = dd;
                    best = c;
                }
            }
            if (best != null) {
                out.add(TaxiGraph.link(sk, best.key));
            }
        }
        return out;
    }

    /** Off (the config, the core's API gone): every virtual record out, nothing known. */
    private void takeDown(AirportDataSVA d) {
        for (String k : virtualKeys()) {
            d.remove(k);
        }
        VIRTUAL.clear();
        built.clear();
        keysOf.clear();
        towerKeys.clear();
        known.clear();
        d.taxiChanged();
        builtAt = -1;
        dirty = true;
        System.out.println("[SVA-EMB] virtual airports off (airport.embassyAirports / the core's API)");
    }

    // ------------------------------------------------------------------ inside: the tower online, the players' knowledge

    /** The virtual towers report in (powered, nobody at the console) while a player of the Overworld is within ATC_RANGE. */
    private void report(World w, AirportDataSVA d, long now) {
        double r2 = TowerMath.ATC_RANGE * TowerMath.ATC_RANGE;
        for (String k : towerKeys.values()) {
            AirportDataSVA.Tower t = d.tower(k);
            if (t == null) {
                continue;
            }
            double tx = t.x + 0.5, tz = t.z + 0.5;
            for (Object o : w.playerEntities) {
                if (o instanceof EntityPlayer && !(o instanceof FakePlayer)) {
                    EntityPlayer p = (EntityPlayer) o;
                    if ((p.posX - tx) * (p.posX - tx) + (p.posZ - tz) * (p.posZ - tz) <= r2) {
                        d.towerReport(k, true, "", now);
                        break;
                    }
                }
            }
        }
    }

    /** Every player of the Overworld: the embassies he knows now - a change re-syncs his airport copy. */
    private void knowledge(World w) {
        for (Object o : w.playerEntities.toArray()) {
            if (!(o instanceof EntityPlayerMP) || o instanceof FakePlayer) {
                continue;
            }
            EntityPlayerMP p = (EntityPlayerMP) o;
            Set<Integer> now = ask(p);
            Set<Integer> was = known.put(p.getUniqueID().toString(), now);
            if (was == null || !was.equals(now)) {
                AirportServerSVA.resync(p);
            }
        }
    }

    /** The core's answer for every airfield built (EmbassyCompatSVA.knows: been there, or within 1024). */
    private Set<Integer> ask(EntityPlayer p) {
        Set<Integer> s = new TreeSet<Integer>();
        for (EmbassyFieldSVA f : built.values()) {
            if (EmbassyCompatSVA.knows(p, f.id)) {
                s.add(Integer.valueOf(f.id));
            }
        }
        return Collections.unmodifiableSet(s);
    }
}
