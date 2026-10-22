package com.sva.airdefense;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/**
 * Audit 2026-10-22 (bug 10): the air defence's terrain memory (TerrainMath) - per dimension, per chunk the 256 columns'
 * last known ground and the bottom of its top solid layer (TerrainMath.BYTES a chunk). Saved with the air-defence data in
 * the overworld's map storage (its own file next to AirDefenseDataSVA's, so the zones and decisions stay small): per
 * dimension the chunks' x and z and their bytes packed one after another. Audit review 2026-10-22: «V» 2 - the first
 * memory (one byte a column, solid from the floor) is not read: it is taken again as the chunks load.
 */
public class AdTerrainDataSVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_adterrain";
    /** The format (audit review 2026-10-22: 2 - the layer's bottom too). */
    public static final int VERSION = 2;

    private final Map<Integer, Map<Long, byte[]>> dims = new HashMap<Integer, Map<Long, byte[]>>();
    private int total;

    public AdTerrainDataSVA(String name) {
        super(name);
    }

    public static AdTerrainDataSVA get(World w) {
        if (w == null || w.mapStorage == null) {
            return null;
        }
        AdTerrainDataSVA d = (AdTerrainDataSVA) w.mapStorage.loadData(AdTerrainDataSVA.class, NAME);
        if (d == null) {
            d = new AdTerrainDataSVA(NAME);
            w.mapStorage.setData(NAME, d);
        }
        return d;
    }

    /** A chunk's columns (null: never seen). */
    public byte[] column(int dim, int cx, int cz) {
        Map<Long, byte[]> m = dims.get(Integer.valueOf(dim));
        return m == null ? null : m.get(Long.valueOf(TerrainMath.key(cx, cz)));
    }

    public boolean has(int dim, long key) {
        Map<Long, byte[]> m = dims.get(Integer.valueOf(dim));
        return m != null && m.containsKey(Long.valueOf(key));
    }

    /** A chunk's columns as seen now. @return they changed (a new chunk past MAX_CHUNKS is not taken) */
    public boolean put(int dim, int cx, int cz, byte[] col) {
        if (col == null || col.length != TerrainMath.BYTES) {
            return false;
        }
        Map<Long, byte[]> m = dims.get(Integer.valueOf(dim));
        if (m == null) {
            m = new HashMap<Long, byte[]>();
            dims.put(Integer.valueOf(dim), m);
        }
        Long k = Long.valueOf(TerrainMath.key(cx, cz));
        byte[] old = m.get(k);
        if (old == null && total >= TerrainMath.MAX_CHUNKS || old != null && Arrays.equals(old, col)) {
            return false;
        }
        m.put(k, col);
        if (old == null) {
            total++;
        }
        markDirty();
        return true;
    }

    /** The chunk keys kept for a dimension (a copy). */
    public List<Long> keys(int dim) {
        Map<Long, byte[]> m = dims.get(Integer.valueOf(dim));
        return m == null ? new ArrayList<Long>() : new ArrayList<Long>(m.keySet());
    }

    public List<Integer> dimensions() {
        return new ArrayList<Integer>(dims.keySet());
    }

    public void remove(int dim, long key) {
        Map<Long, byte[]> m = dims.get(Integer.valueOf(dim));
        if (m != null && m.remove(Long.valueOf(key)) != null) {
            total--;
            markDirty();
        }
    }

    public int size() {
        return total;
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        dims.clear();
        total = 0;
        if (t.getInteger("V") != VERSION) {
            markDirty();                                            // an older memory: dropped, the file written anew
            return;
        }
        NBTTagList l = t.getTagList("Dims", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound c = l.getCompoundTagAt(i);
            int[] xs = c.getIntArray("X"), zs = c.getIntArray("Z");
            byte[] h = c.getByteArray("H");
            Map<Long, byte[]> m = new HashMap<Long, byte[]>();
            for (int k = 0; k < xs.length && k < zs.length && (k + 1) * TerrainMath.BYTES <= h.length && total < TerrainMath.MAX_CHUNKS; k++) {
                m.put(Long.valueOf(TerrainMath.key(xs[k], zs[k])), Arrays.copyOfRange(h, k * TerrainMath.BYTES, (k + 1) * TerrainMath.BYTES));
                total++;
            }
            if (!m.isEmpty()) {
                dims.put(Integer.valueOf(c.getInteger("D")), m);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        NBTTagList l = new NBTTagList();
        for (Map.Entry<Integer, Map<Long, byte[]>> e : dims.entrySet()) {
            Map<Long, byte[]> m = e.getValue();
            if (m.isEmpty()) {
                continue;
            }
            int[] xs = new int[m.size()], zs = new int[m.size()];
            byte[] h = new byte[m.size() * TerrainMath.BYTES];
            int k = 0;
            for (Iterator<Map.Entry<Long, byte[]>> it = m.entrySet().iterator(); it.hasNext(); k++) {
                Map.Entry<Long, byte[]> c = it.next();
                xs[k] = TerrainMath.keyX(c.getKey().longValue());
                zs[k] = TerrainMath.keyZ(c.getKey().longValue());
                System.arraycopy(c.getValue(), 0, h, k * TerrainMath.BYTES, TerrainMath.BYTES);
            }
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("D", e.getKey().intValue());
            c.setIntArray("X", xs);
            c.setIntArray("Z", zs);
            c.setByteArray("H", h);
            l.appendTag(c);
        }
        t.setTag("Dims", l);
        t.setInteger("V", VERSION);
    }
}
