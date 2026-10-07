package com.sva.vehicle;

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
 * Placed vehicles by owner (the world's shared map storage, so all dimensions): vehicle id -> owner UUID.
 * An entry is added when a vehicle is placed (or a loaded entity finds itself missing) and removed when it
 * is picked up or destroyed. Used for vehicles.maxPerPlayer.
 *
 * Wave 3 (★19, «Моя техника»): each entry also keeps the vehicle's last known card - type, board number, position and
 * dimension, charge and hull (0..1), the owner's name, when it was last seen (world time) and a copy of its access list
 * (who else may see it on the map: MapMath.visible). A loaded vehicle refreshes it every MapMath.UPDATE_EVERY ticks
 * (EntityVehicleSVA), an unloaded one keeps its last known place. Vehicles carried as items (inventories, the platform
 * trailer) are not placed and are not listed. NBT: the old "Id" / "Owner" pairs plus appended keys - an entry saved
 * before wave 3 has no position until its vehicle loads again ("Placed" false).
 */
public class VehicleRegistrySVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_registry";

    /** One vehicle's card (the map's list row). */
    public static final class Entry {
        public final String id;
        public String owner = "", ownerName = "";
        public int type, board, dim;
        public double x, y, z;
        public float charge, hull;
        public long seen;
        /** A position is known (false: saved before wave 3 and not loaded since). */
        public boolean placed;
        public final AccessMath.Access access = new AccessMath.Access();

        Entry(String id) {
            this.id = id;
        }

        void write(NBTTagCompound e) {
            e.setString("Id", id);
            e.setString("Owner", owner);
            e.setString("Name", ownerName);
            e.setInteger("Type", type);
            e.setInteger("Board", board);
            e.setInteger("Dim", dim);
            e.setDouble("X", x);
            e.setDouble("Y", y);
            e.setDouble("Z", z);
            e.setFloat("Charge", charge);
            e.setFloat("Hull", hull);
            e.setLong("Seen", seen);
            e.setBoolean("Placed", placed);
            NBTTagCompound a = new NBTTagCompound();
            access.write(a);
            e.setTag("Access", a);
        }

        void read(NBTTagCompound e) {
            owner = e.getString("Owner");
            ownerName = e.getString("Name");
            type = Math.max(0, e.getInteger("Type"));
            board = e.getInteger("Board");
            dim = e.getInteger("Dim");
            x = finite(e.getDouble("X"));
            y = finite(e.getDouble("Y"));
            z = finite(e.getDouble("Z"));
            charge = clamp01(e.getFloat("Charge"));
            hull = clamp01(e.getFloat("Hull"));
            seen = e.getLong("Seen");
            placed = e.getBoolean("Placed");
            if (e.hasKey("Access")) {
                access.read(e.getCompoundTag("Access"));
            } else {
                access.mode = AccessMath.OWNER;                     // nothing known yet: the owner alone sees it
            }
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

    public VehicleRegistrySVA(String name) {
        super(name);
    }

    public static VehicleRegistrySVA get(World w) {
        if (w == null || w.mapStorage == null) {
            return null;
        }
        VehicleRegistrySVA r = (VehicleRegistrySVA) w.mapStorage.loadData(VehicleRegistrySVA.class, NAME);
        if (r == null) {
            r = new VehicleRegistrySVA(NAME);
            w.mapStorage.setData(NAME, r);
        }
        return r;
    }

    public int count(String owner) {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.owner.equals(owner)) {
                n++;
            }
        }
        return n;
    }

    public boolean has(String vehId) {
        return entries.containsKey(vehId);
    }

    public void add(String vehId, String owner) {
        if (vehId == null) {
            return;
        }
        String o = owner == null ? "" : owner;
        Entry e = entries.get(vehId);
        if (e == null) {
            e = new Entry(vehId);
            e.owner = o;
            e.access.mode = AccessMath.OWNER;
            entries.put(vehId, e);
            markDirty();
        } else if (!o.equals(e.owner)) {
            e.owner = o;
            markDirty();
        }
    }

    /**
     * ★19: a loaded vehicle's card now (server): adds the entry if missing. `charge` / `hull` 0..1, `seen` the world
     * time, `access` copied (the map's visibility rule reads it while the vehicle is unloaded).
     */
    public void update(String vehId, String owner, String ownerName, int type, int board, double x, double y, double z, int dim,
            float charge, float hull, long seen, AccessMath.Access access) {
        if (vehId == null) {
            return;
        }
        add(vehId, owner);
        Entry e = entries.get(vehId);
        e.ownerName = ownerName == null ? "" : ownerName;
        e.type = type;
        e.board = board;
        e.x = finite(x);
        e.y = finite(y);
        e.z = finite(z);
        e.dim = dim;
        e.charge = clamp01(charge);
        e.hull = clamp01(hull);
        e.seen = seen;
        e.placed = true;
        if (access != null) {
            NBTTagCompound a = new NBTTagCompound();
            access.write(a);
            e.access.read(a);
        }
        markDirty();
    }

    /** ★19: one entry, or null. */
    public Entry entry(String vehId) {
        return vehId == null ? null : entries.get(vehId);
    }

    /** ★19: every entry (a copy of the list). */
    public List<Entry> all() {
        return new ArrayList<Entry>(entries.values());
    }

    /** Forgets every vehicle of `owner` (the world test's leftovers from an interrupted run). @return how many */
    public int removeOwner(String owner) {
        int n = 0;
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().owner.equals(owner)) {
                it.remove();
                n++;
            }
        }
        if (n > 0) {
            markDirty();
        }
        return n;
    }

    public void remove(String vehId) {
        if (vehId != null && entries.remove(vehId) != null) {
            markDirty();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        entries.clear();
        NBTTagList l = t.getTagList("Vehicles", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound c = l.getCompoundTagAt(i);
            String id = c.getString("Id");
            if (id.length() == 0) {
                continue;
            }
            Entry e = new Entry(id);
            e.read(c);
            entries.put(id, e);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        NBTTagList l = new NBTTagList();
        for (Entry en : entries.values()) {
            NBTTagCompound e = new NBTTagCompound();
            en.write(e);
            l.appendTag(e);
        }
        t.setTag("Vehicles", l);
    }

    private static double finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0 : v;
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0F : Math.max(0F, Math.min(1F, v));
    }
}
