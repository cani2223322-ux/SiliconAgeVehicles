package com.sva.vehicle;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Everything a vehicle keeps between entity and item: type, EU, hull, heat, cargo, owner, id.
 * NBT keys are APPEND-ONLY.
 */
public class VehicleState {

    public static final String K_TYPE = "Type", K_ENERGY = "Energy", K_HP = "Hp", K_HEAT = "Heat", K_HEAT_STOP = "HeatStop",
            K_CARGO = "Cargo", K_OWNER = "OwnerUUID", K_OWNER_NAME = "OwnerName", K_ID = "VehId";

    public VehicleType type;
    public double energy;
    public float hp;
    public double heat;
    public boolean heatStop;
    public ItemStack[] cargo;
    public String ownerId = "";
    public String ownerName = "";
    public String vehId;

    public VehicleState(VehicleType type) {
        this.type = type;
        this.hp = type.maxHp;
        this.cargo = new ItemStack[type.cargoSlots];
        this.vehId = UUID.randomUUID().toString();
    }

    public double maxEnergy() {
        return type.buffer;
    }

    /** @return EU taken */
    public double addEnergy(double eu) {
        double room = Math.max(0, maxEnergy() - energy);
        double got = Math.max(0, Math.min(room, eu));
        energy += got;
        return got;
    }

    public int cargoUsed() {
        int n = 0;
        for (ItemStack s : cargo) {
            if (s != null) {
                n++;
            }
        }
        return n;
    }

    /** 0..1: item count against the stack limits of the slots. */
    public double cargoFill() {
        double sum = 0;
        for (ItemStack s : cargo) {
            if (s != null && s.getMaxStackSize() > 0) {
                sum += Math.min(1.0, s.stackSize / (double) s.getMaxStackSize());
            }
        }
        return cargo.length == 0 ? 0 : sum / cargo.length;
    }

    public boolean isOwner(UUID id) {
        return id != null && id.toString().equals(ownerId);
    }

    public void writeNBT(NBTTagCompound t) {
        t.setInteger(K_TYPE, type.ordinal());
        t.setDouble(K_ENERGY, energy);
        t.setFloat(K_HP, hp);
        t.setDouble(K_HEAT, heat);
        t.setBoolean(K_HEAT_STOP, heatStop);
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < cargo.length; i++) {
            if (cargo[i] != null) {
                NBTTagCompound s = new NBTTagCompound();
                s.setByte("Slot", (byte) i);
                cargo[i].writeToNBT(s);
                list.appendTag(s);
            }
        }
        t.setTag(K_CARGO, list);
        t.setString(K_OWNER, ownerId == null ? "" : ownerId);
        t.setString(K_OWNER_NAME, ownerName == null ? "" : ownerName);
        t.setString(K_ID, vehId);
    }

    /** Reads into this state; a missing key keeps the current value (a fresh item: full hull, empty). */
    public void readNBT(NBTTagCompound t) {
        if (t.hasKey(K_TYPE)) {
            type = VehicleType.byOrdinal(t.getInteger(K_TYPE));
        }
        energy = clampNum(t.hasKey(K_ENERGY) ? t.getDouble(K_ENERGY) : energy, 0, maxEnergy());
        hp = (float) clampNum(t.hasKey(K_HP) ? t.getFloat(K_HP) : hp, 0, type.maxHp);
        heat = clampNum(t.hasKey(K_HEAT) ? t.getDouble(K_HEAT) : heat, 0, VehicleMath.HEAT_STOP);
        heatStop = t.getBoolean(K_HEAT_STOP);
        cargo = new ItemStack[type.cargoSlots];
        NBTTagList list = t.getTagList(K_CARGO, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound s = list.getCompoundTagAt(i);
            int slot = s.getByte("Slot") & 0xFF;
            if (slot < cargo.length) {
                cargo[slot] = ItemStack.loadItemStackFromNBT(s);
            }
        }
        if (t.hasKey(K_OWNER)) {
            ownerId = t.getString(K_OWNER);
            ownerName = t.getString(K_OWNER_NAME);
        }
        if (t.hasKey(K_ID) && t.getString(K_ID).length() > 0) {
            vehId = t.getString(K_ID);
        }
    }

    private static double clampNum(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : VehicleMath.clamp(v, lo, hi);
    }
}
