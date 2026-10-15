package com.sva.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sva.airport.IlsPairing;
import com.sva.airport.RunwayLayout;
import com.sva.net.NetAptSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Airport wave B1: the client's copy of the airport (NetAptSVA D_SYNC - block.AirportDataSVA.clientData): the runways of
 * the player's dimension (their two ends, width, elevation, designators, each end's lights mode, ALS and touchdown RVR) for
 * the paint and lights renderer (AirportRenderSVA) and the planner's hologram, the stations' status for the antennas'
 * screens, the wind field's seed for the windsocks. The packet is parsed on the network thread into a fresh immutable
 * State and swapped in (one volatile reference: the render thread never sees a half-built one).
 * <p>
 * Airport wave B2: the towers (their ICAO codes, names, the charts' version - an approach chart in hand checks it - and the
 * taxiways' step), the taxi lights {x, y, z, kind} and their lit state (D_TAXI: the follow-me lights ahead of the cleared
 * aircraft, the stop bars burning - by index into the list of the same version), the open tower screen's picture (D_TOWER),
 * each end's approach lights' mode.
 */
@SideOnly(Side.CLIENT)
public final class AirportClientSVA {

    /** A runway with what the renderer needs. */
    public static final class Rwy {
        public final IlsPairing.Rwy r;
        public final int rvrA, rvrB;
        /** Airport wave B2: the approach lights' mode at each end (the tower's approach step or the end's own). */
        public int alsModeA = RunwayLayout.LIGHTS_AUTO, alsModeB = RunwayLayout.LIGHTS_AUTO;

        Rwy(IlsPairing.Rwy r, int rvrA, int rvrB) {
            this.r = r;
            this.rvrA = rvrA;
            this.rvrB = rvrB;
        }
    }

    /** Airport wave B2: a tower as the clients know it. */
    public static final class Tower {
        public final String key, icao, name;
        public final int ver, x, y, z, ltTaxi;

        Tower(String key, String icao, String name, int ver, int x, int y, int z, int ltTaxi) {
            this.key = key;
            this.icao = icao;
            this.name = name;
            this.ver = ver;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ltTaxi = ltTaxi;
        }
    }

    /** Airport wave B2: the taxi lights {x, y, z, kind (BlockTaxiLightSVA)} x n, their version, the towers. */
    public static volatile int[] taxi = new int[0];
    public static volatile int taxiVersion = -1;
    public static volatile List<Tower> towers = Collections.<Tower>emptyList();

    /** Airport wave B2: the lit state (D_TAXI) - its taxi version, follow-me on, the lit centre lights, the burning stop bars. */
    public static final class Lit {
        public final int version;
        public final boolean follow;
        public final java.util.Set<Integer> lit, stops;

        Lit(int version, boolean follow, java.util.Set<Integer> lit, java.util.Set<Integer> stops) {
            this.version = version;
            this.follow = follow;
            this.lit = lit;
            this.stops = stops;
        }
    }

    public static volatile Lit lit = new Lit(-1, false, Collections.<Integer>emptySet(), Collections.<Integer>emptySet());
    /** Airport wave B2: the open tower screen's last picture (null none) and when it came (ms). */
    public static volatile NBTTagCompound towerSnap;
    public static volatile long towerSnapAt;

    /** The charts' version of an airport (-1: no tower of that code known). */
    public static int chartVersion(String icao) {
        for (Tower t : towers) {
            if (t.icao.equalsIgnoreCase(icao)) {
                return t.ver;
            }
        }
        return -1;
    }

    static java.util.Set<Integer> set(int[] a) {
        java.util.Set<Integer> s = new java.util.HashSet<Integer>();
        for (int i : a) {
            s.add(Integer.valueOf(i));
        }
        return s;
    }

    public static final class State {
        public final List<Rwy> runways;
        /** Per localizer key: {cat, flags}; its runway designator. */
        public final Map<String, int[]> stations;
        public final long windSeed;
        public final int version;

        State(List<Rwy> runways, Map<String, int[]> stations, long windSeed, int version) {
            this.runways = runways;
            this.stations = stations;
            this.windSeed = windSeed;
            this.version = version;
        }
    }

    public static volatile State state = new State(Collections.<Rwy>emptyList(), Collections.<String, int[]>emptyMap(), 0, 0);
    private static int versions;

    private AirportClientSVA() {
    }

    /** NetAptSVA.Data (the network thread). */
    public static void receive(int kind, NBTTagCompound t) {
        if (t == null) {
            return;
        }
        if (kind == NetAptSVA.D_TOWER) {
            towerSnap = t;
            towerSnapAt = System.currentTimeMillis();
            return;
        }
        if (kind == NetAptSVA.D_TAXI) {
            lit = new Lit(t.getInteger("V"), t.getBoolean("F"), set(t.getIntArray("L")), set(t.getIntArray("S")));
            return;
        }
        if (kind != NetAptSVA.D_SYNC) {
            return;
        }
        List<Rwy> rws = new ArrayList<Rwy>();
        NBTTagList l = t.getTagList("Rwy", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound e = l.getCompoundTagAt(i);
            IlsPairing.End a = new IlsPairing.End(e.getString("K"), 0, e.getDouble("Ax"), e.getDouble("Az"), e.getDouble("Y"), 0, e.getInteger("W"));
            IlsPairing.End b = new IlsPairing.End(e.getString("Kb"), 0, e.getDouble("Bx"), e.getDouble("Bz"), e.getDouble("Y"), 0, e.getInteger("W"));
            a.lights = e.getInteger("La");
            b.lights = e.getInteger("Lb");
            a.als = e.getBoolean("Sa") ? 1 : 0;
            b.als = e.getBoolean("Sb") ? 1 : 0;
            IlsPairing.Rwy r = new IlsPairing.Rwy();
            r.a = a;
            r.b = b;
            r.course = IlsPairing.bearing(a.x, a.z, b.x, b.z);
            r.len = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
            r.elev = e.getDouble("Y");
            r.width = Math.max(IlsPairing.WIDTH_MIN, Math.min(IlsPairing.WIDTH_MAX, e.getInteger("W")));
            r.nameA = e.getString("Na");
            r.nameB = e.getString("Nb");
            r.apt = e.getString("Apt");
            Rwy rw = new Rwy(r, e.getInteger("Ra"), e.getInteger("Rb"));
            rw.alsModeA = e.hasKey("Pa") ? e.getInteger("Pa") : a.lights;
            rw.alsModeB = e.hasKey("Pb") ? e.getInteger("Pb") : b.lights;
            rws.add(rw);
        }
        Map<String, int[]> st = new HashMap<String, int[]>();
        NBTTagList sl = t.getTagList("St", 10);
        for (int i = 0; i < sl.tagCount(); i++) {
            NBTTagCompound e = sl.getCompoundTagAt(i);
            st.put(e.getString("K"), new int[] {e.getInteger("Cat"), e.getInteger("F")});
        }
        com.sva.block.AirportDataSVA.syncedWindSeed = t.getLong("WindSeed");
        // airport wave B2: the towers, the taxi lights
        List<Tower> tw = new ArrayList<Tower>();
        NBTTagList tl = t.getTagList("Twr", 10);
        for (int i = 0; i < tl.tagCount(); i++) {
            NBTTagCompound e = tl.getCompoundTagAt(i);
            int[] p = e.getIntArray("Pos");
            if (p.length == 3) {
                tw.add(new Tower(e.getString("K"), e.getString("Icao"), e.getString("Name"), e.getInteger("Ver"), p[0], p[1], p[2], e.getInteger("Lt")));
            }
        }
        towers = Collections.unmodifiableList(tw);
        taxi = t.getIntArray("Tx");
        taxiVersion = t.getInteger("TxV");
        state = new State(Collections.unmodifiableList(rws), Collections.unmodifiableMap(st), t.getLong("WindSeed"), ++versions);
    }

    /** The runway an end marker key belongs to ({runway, dir}) or null. */
    public static Object[] runwayOfEnd(String key) {
        for (Rwy w : state.runways) {
            if (w.r.a.key.equals(key)) {
                return new Object[] {w, Integer.valueOf(1)};
            }
            if (w.r.b.key.equals(key)) {
                return new Object[] {w, Integer.valueOf(-1)};
            }
        }
        return null;
    }

    /** A defined runway covers the point (the old automatic marks of RunwayLightsSVA step aside there). */
    public static boolean covers(double x, double z) {
        for (Rwy w : state.runways) {
            if (w.r.onRunway(x, z, 2)) {
                return true;
            }
        }
        return false;
    }

    /** The lights' step of a runway end now (its mode, the night / rain, its RVR). */
    public static int step(Rwy w, int dir, boolean night, boolean rain) {
        IlsPairing.End e = w.r.end(dir);
        int rvr = dir > 0 ? w.rvrA : w.rvrB;
        return RunwayLayout.step(e.lights, night, rain, rvr < 0 ? 10000 : rvr);
    }

    /** Airport wave B2: the approach lights' step of a runway end now. */
    public static int alsStep(Rwy w, int dir, boolean night, boolean rain) {
        int rvr = dir > 0 ? w.rvrA : w.rvrB;
        return RunwayLayout.step(dir > 0 ? w.alsModeA : w.alsModeB, night, rain, rvr < 0 ? 10000 : rvr);
    }

    /** Airport wave B2: the taxiway lights' step at (x, z): the nearest tower's (within TowerMath.RANGE), else AUTO. */
    public static int taxiStep(double x, double z, boolean night, boolean rain) {
        Tower best = null;
        double bd = com.sva.airport.TowerMath.RANGE * com.sva.airport.TowerMath.RANGE;
        for (Tower t : towers) {
            double d = (t.x - x) * (t.x - x) + (t.z - z) * (t.z - z);
            if (d <= bd) {
                bd = d;
                best = t;
            }
        }
        return RunwayLayout.step(best == null ? RunwayLayout.LIGHTS_AUTO : best.ltTaxi, night, rain, 10000);
    }
}
