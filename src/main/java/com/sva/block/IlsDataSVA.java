package com.sva.block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sva.vehicle.LinerIls;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/**
 * Liner wave 4: every «Курсо-глиссадный маяк» placed - one per save (the overworld's map storage, as AirDefenseDataSVA),
 * keyed "dim:x:y:z" - so an airliner finds the beacons within its ILS range wherever their chunks are (nothing is loaded).
 * The block writes its station here when placed / set in its screen and takes it out when broken.
 */
public class IlsDataSVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_ils";

    private final Map<String, LinerIls.Station> stations = new LinkedHashMap<String, LinerIls.Station>();

    public IlsDataSVA(String name) {
        super(name);
    }

    public static IlsDataSVA get(World w) {
        if (w == null || w.mapStorage == null) {
            return null;
        }
        IlsDataSVA d = (IlsDataSVA) w.mapStorage.loadData(IlsDataSVA.class, NAME);
        if (d == null) {
            d = new IlsDataSVA(NAME);
            w.mapStorage.setData(NAME, d);
        }
        return d;
    }

    /** Liner wave 5: bumped on every change (the MCDU's database - NavDataSVA - rebuilds after one). */
    private transient int version;

    public int version() {
        return version;
    }

    /** A station placed or changed (a copy is kept). */
    public void put(LinerIls.Station s) {
        stations.put(s.key(), s.copy());
        version++;
        markDirty();
    }

    public void remove(String key) {
        if (stations.remove(key) != null) {
            version++;
            markDirty();
        }
    }

    public LinerIls.Station get(String key) {
        LinerIls.Station s = stations.get(key);
        return s == null ? null : s.copy();
    }

    /** The stations of a dimension within `range` blocks (horizontally) of (x, z). */
    public List<LinerIls.Station> near(int dim, double x, double z, double range) {
        List<LinerIls.Station> out = new ArrayList<LinerIls.Station>();
        for (LinerIls.Station s : stations.values()) {
            double dx = s.tx() - x, dz = s.tz() - z;
            if (s.dim == dim && dx * dx + dz * dz <= range * range) {
                out.add(s);
            }
        }
        return out;
    }

    public int size() {
        return stations.size();
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        stations.clear();
        NBTTagList l = t.getTagList("Stations", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            LinerIls.Station s = new LinerIls.Station();
            s.read(l.getCompoundTagAt(i));
            stations.put(s.key(), s);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        NBTTagList l = new NBTTagList();
        for (LinerIls.Station s : stations.values()) {
            NBTTagCompound e = new NBTTagCompound();
            s.write(e);
            l.appendTag(e);
        }
        t.setTag("Stations", l);
    }
}
