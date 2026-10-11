package com.sva.block;

import java.util.ArrayList;
import java.util.List;

import com.sc.energy.Tier;
import com.sc.energy.TileEntityEnergyBase;
import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.entity.EntityVehicleSVA;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/**
 * Air-defence wave: what the radars, the launchers and the command post share - the owner and his access list (the
 * «friends» of the mock-ups: their vehicles count as own, they may open the screens), the power (an upkeep in EU a tick
 * from the grid; short of it the block is «без питания» and does nothing), the registration with AirDefenseSVA while
 * loaded and with AirDefenseDataSVA while placed (the per-player limits).
 */
public abstract class TileEntityAirDefenseSVA extends TileEntityEnergyBase {

    public static final int MAX_ACCESS = 16;

    protected String ownerName = "", ownerId = "";
    protected final List<String> access = new ArrayList<String>();
    protected boolean powered;
    private boolean registered;

    protected TileEntityAirDefenseSVA(Tier t) {
        super(t);
    }

    /** EU a tick while working. */
    public abstract int upkeep();

    /** AirDefenseDataSVA.N_* (limits). */
    public abstract int nodeKind();

    /** The block's display name ("ПУ-1", "Р2"...: the command post names its linked blocks; here the lang key). */
    public abstract String nameKey();

    public String getOwnerName() {
        return ownerName;
    }

    public List<String> getAccess() {
        return access;
    }

    public boolean isPowered() {
        return powered;
    }

    public void setOwner(EntityPlayer p) {
        if (p != null) {
            ownerName = p.getCommandSenderName();
            ownerId = p.getUniqueID().toString();
            markDirty();
        }
    }

    /** World tests: an owner by name. */
    public void setOwnerName(String name) {
        ownerName = name == null ? "" : name;
        markDirty();
    }

    /** Opens the screens / changes settings: the owner, his access list, an op. */
    public boolean mayUse(EntityPlayer p) {
        if (p == null) {
            return false;
        }
        if (ownerName.length() == 0 || ownerName.equalsIgnoreCase(p.getCommandSenderName()) || ownerId.equals(p.getUniqueID().toString())) {
            return true;
        }
        for (String a : access) {
            if (a.equalsIgnoreCase(p.getCommandSenderName())) {
                return true;
            }
        }
        return EntityVehicleSVA.isOp(p);
    }

    /** The owner (or an op): the access list and the secret are his alone. */
    public boolean isOwner(EntityPlayer p) {
        return p != null && (ownerName.length() == 0 || ownerName.equalsIgnoreCase(p.getCommandSenderName()) || EntityVehicleSVA.isOp(p));
    }

    /** A vehicle owner's relation to this air defence: 1 - its owner, 2 - on the access list, 0 - a stranger. */
    public int relation(String vehOwner) {
        if (vehOwner == null || vehOwner.length() == 0) {
            return 0;
        }
        if (vehOwner.equalsIgnoreCase(ownerName)) {
            return 1;
        }
        for (String a : access) {
            if (a.equalsIgnoreCase(vehOwner)) {
                return 2;
            }
        }
        return 0;
    }

    /** Sets the access list (names; the owner's screen). */
    public void setAccess(List<String> names) {
        access.clear();
        for (String n : names) {
            n = n == null ? "" : n.trim();
            if (n.length() > 0 && n.length() <= 32 && access.size() < MAX_ACCESS && !access.contains(n)) {
                access.add(n);
            }
        }
        markDirty();
    }

    /** World tests / commands: a full buffer. */
    public void fillForTest() {
        addEnergy(getMaxEnergyStored());
    }

    public String key() {
        return AirDefenseDataSVA.key(worldObj == null ? 0 : worldObj.provider.dimensionId, xCoord, yCoord, zCoord);
    }

    @Override
    public boolean canUpdate() {
        return true;
    }

    /** Pays the upkeep (server, every tick): powered while the buffer holds it. */
    protected void upkeepTick() {
        int u = ConfigSVA.adEnabled ? upkeep() : 0;
        if (u <= 0) {
            powered = ConfigSVA.adEnabled;
            return;
        }
        if (getEnergyStored() >= u) {
            removeEnergy(u);
            powered = true;
        } else {
            powered = false;
        }
    }

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        if (!registered) {
            registered = true;
            AirDefenseSVA.INSTANCE.register(this);
            AirDefenseDataSVA d = AirDefenseDataSVA.get(worldObj);
            if (d != null && !d.hasNode(key())) {
                d.addNode(key(), nodeKind(), ownerName);
            }
        }
        upkeepTick();
        serverTick();
    }

    /** The block's own work (server, every tick, after the upkeep). */
    protected abstract void serverTick();

    @Override
    public void invalidate() {
        super.invalidate();
        if (registered) {
            AirDefenseSVA.INSTANCE.unregister(this);
            registered = false;
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (registered) {
            AirDefenseSVA.INSTANCE.unregister(this);
            registered = false;
        }
    }

    /** The block was broken (not unloaded): forget it in the limits. */
    public void removed() {
        AirDefenseDataSVA d = AirDefenseDataSVA.get(worldObj);
        if (d != null) {
            d.removeNode(key());
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        super.readFromNBT(t);
        ownerName = t.getString("AdOwner");
        ownerId = t.getString("AdOwnerId");
        access.clear();
        NBTTagList l = t.getTagList("AdAccess", 8);
        for (int i = 0; i < l.tagCount() && i < MAX_ACCESS; i++) {
            access.add(l.getStringTagAt(i));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        super.writeToNBT(t);
        t.setString("AdOwner", ownerName);
        t.setString("AdOwnerId", ownerId);
        NBTTagList l = new NBTTagList();
        for (String a : access) {
            l.appendTag(new NBTTagString(a));
        }
        t.setTag("AdAccess", l);
    }
}
