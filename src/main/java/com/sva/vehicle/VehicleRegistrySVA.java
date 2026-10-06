package com.sva.vehicle;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/**
 * Placed vehicles by owner (the world's shared map storage, so all dimensions): vehicle id -> owner UUID.
 * An entry is added when a vehicle is placed (or a loaded entity finds itself missing) and removed when it
 * is picked up or destroyed. Used for vehicles.maxPerPlayer.
 */
public class VehicleRegistrySVA extends WorldSavedData {

    public static final String NAME = "SiliconAgeVehicles_registry";

    private final Map<String, String> owners = new HashMap<String, String>();

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
        for (String o : owners.values()) {
            if (o.equals(owner)) {
                n++;
            }
        }
        return n;
    }

    public boolean has(String vehId) {
        return owners.containsKey(vehId);
    }

    public void add(String vehId, String owner) {
        if (vehId == null) {
            return;
        }
        String o = owner == null ? "" : owner;
        if (!o.equals(owners.get(vehId))) {
            owners.put(vehId, o);
            markDirty();
        }
    }

    /** Forgets every vehicle of `owner` (the world test's leftovers from an interrupted run). @return how many */
    public int removeOwner(String owner) {
        int n = 0;
        for (java.util.Iterator<Map.Entry<String, String>> it = owners.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().equals(owner)) {
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
        if (vehId != null && owners.remove(vehId) != null) {
            markDirty();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        owners.clear();
        NBTTagList l = t.getTagList("Vehicles", 10);
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound e = l.getCompoundTagAt(i);
            owners.put(e.getString("Id"), e.getString("Owner"));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        NBTTagList l = new NBTTagList();
        for (Iterator<Map.Entry<String, String>> it = owners.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> en = it.next();
            NBTTagCompound e = new NBTTagCompound();
            e.setString("Id", en.getKey());
            e.setString("Owner", en.getValue());
            l.appendTag(e);
        }
        t.setTag("Vehicles", l);
    }
}
