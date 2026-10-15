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
 */
@SideOnly(Side.CLIENT)
public final class AirportClientSVA {

    /** A runway with what the renderer needs. */
    public static final class Rwy {
        public final IlsPairing.Rwy r;
        public final int rvrA, rvrB;

        Rwy(IlsPairing.Rwy r, int rvrA, int rvrB) {
            this.r = r;
            this.rvrA = rvrA;
            this.rvrB = rvrB;
        }
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
        if (kind != NetAptSVA.D_SYNC || t == null) {
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
            rws.add(new Rwy(r, e.getInteger("Ra"), e.getInteger("Rb")));
        }
        Map<String, int[]> st = new HashMap<String, int[]>();
        NBTTagList sl = t.getTagList("St", 10);
        for (int i = 0; i < sl.tagCount(); i++) {
            NBTTagCompound e = sl.getCompoundTagAt(i);
            st.put(e.getString("K"), new int[] {e.getInteger("Cat"), e.getInteger("F")});
        }
        com.sva.block.AirportDataSVA.syncedWindSeed = t.getLong("WindSeed");
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
}
