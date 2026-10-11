package com.sva.airdefense;

import java.util.ArrayList;
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
 */
public class AirDefenseDataSVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_airdefense";
    /** Node kinds (the limits). */
    public static final int N_RADAR = 0, N_LAUNCHER = 1, N_KP = 2;

    private final Map<String, List<ZoneMath.Zone>> zones = new LinkedHashMap<String, List<ZoneMath.Zone>>();
    private final Map<String, Object[]> nodes = new LinkedHashMap<String, Object[]>();
    /** Bumped on every zone change (the pilots' copies are resent). */
    private int version;

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
                c.add(z.copy());
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
    }

    /** Every zone of every command post. */
    public List<ZoneMath.Zone> all() {
        List<ZoneMath.Zone> out = new ArrayList<ZoneMath.Zone>();
        for (List<ZoneMath.Zone> l : zones.values()) {
            out.addAll(l);
        }
        return out;
    }

    /** The zones of one owner's command posts in a dimension (a launcher without a command post uses them). */
    public List<ZoneMath.Zone> ofOwner(String owner, int dim) {
        List<ZoneMath.Zone> out = new ArrayList<ZoneMath.Zone>();
        for (List<ZoneMath.Zone> l : zones.values()) {
            for (ZoneMath.Zone z : l) {
                if (z.dim == dim && z.owner.equalsIgnoreCase(owner == null ? "" : owner)) {
                    out.add(z);
                }
            }
        }
        return out;
    }

    public int version() {
        return version;
    }

    // ------------------------------------------------------------------ the blocks placed (limits)

    public void addNode(String key, int kind, String owner) {
        nodes.put(key, new Object[] {Integer.valueOf(kind), owner == null ? "" : owner});
        markDirty();
    }

    public void removeNode(String key) {
        if (nodes.remove(key) != null) {
            markDirty();
        }
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
    }
}
