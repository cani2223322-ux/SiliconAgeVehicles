package com.sva.block;

import java.util.ArrayList;
import java.util.List;

import com.sc.energy.Tier;
import com.sc.energy.TileEntityEnergyBase;
import com.sc.item.ItemUpgradeSC;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AdUpgradeMath;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Airport wave B1 (plan-next §9, items 17-18): «Аппаратная ILS» - the equipment shelter of the ILS antennas: their POWER
 * (an EU sink of MV, airport.shelterEu EU/t for each antenna it serves, the core's upgrade modules in four slots as the
 * air-defence blocks - AdUpgradeMath with B_ILS: the universal transformer, transformers, storage, economizers) and their
 * MONITOR (IlsCat: a monitored station may shut itself off on a fault; without one it is CAT I at best). It serves every
 * antenna master within RANGE blocks (horizontally, RANGE_Y up / down) - one shelter by a localizer, one by the mast, or one
 * between them when they stand that close. Every REPORT ticks it tells the save's airport list (AirportDataSVA) which
 * antennas it serves and whether it has the power; nothing reported for a while = no shelter (the monitor's decision).
 * RMB: its screen (ContainerIlsShelterSVA / client.GuiIlsShelterSVA: power, the energy, the antennas, the slots).
 */
public class TileEntityIlsShelterSVA extends TileEntityEnergyBase {

    public static final int RANGE = 4, RANGE_Y = 2, REPORT = 20;
    /** AdUpgradeMath block kind: the ILS shelter (the modules every block takes - no overclocker, overdrive, puller). */
    public static final int B_ILS = 3;

    private final ItemStack[] upg = new ItemStack[AdUpgradeMath.SLOTS];
    public final IInventory upgrades = new UpgradeInv();
    /** Powered last tick; the antennas served (their keys); EU taken a tick now. */
    private boolean powered;
    private final List<String> served = new ArrayList<String>();
    private int lastTierKey = -1;
    /** The client's mirror of the power (the model's lamp). */
    public boolean clientPowered;
    private int sentPowered = -1;

    public TileEntityIlsShelterSVA() {
        super(Tier.MV);
    }

    public boolean isPowered() {
        return powered || !ConfigSVA.aptRequirePower;
    }

    public int servedCount() {
        return served.size();
    }

    public List<String> served() {
        return served;
    }

    /** EU a tick: per antenna served, with the economizers. */
    public int upkeep() {
        return served.isEmpty() ? 0 : AdUpgradeMath.upkeep(ConfigSVA.aptShelterEu * served.size(), 0, upgradeCount(UpgradeType.ECONOMIZER), 0);
    }

    public int upgradeCount(UpgradeType type) {
        if (!com.sva.net.ServerConfigSVA.adUpgrades() || !AdUpgradeMath.accepts(B_ILS, type)) {
            return 0;
        }
        int n = 0;
        for (ItemStack s : upg) {
            if (s != null && s.getItem() instanceof ItemUpgradeSC && ItemUpgradeSC.typeOf(s) == type) {
                n += s.stackSize;
            }
        }
        return type == UpgradeType.ECONOMIZER ? AdUpgradeMath.capGen(n) : AdUpgradeMath.cap(n);
    }

    public boolean upgradeAccepted(ItemStack s) {
        return com.sva.net.ServerConfigSVA.adUpgrades() && s != null && s.getItem() instanceof ItemUpgradeSC && AdUpgradeMath.accepts(B_ILS, ItemUpgradeSC.typeOf(s))
                && ItemUpgradeSC.typeOf(s) != UpgradeType.OVERCLOCKER;
    }

    @Override
    public boolean acceptsAnyVoltage() {
        return upgradeCount(UpgradeType.UNIVERSAL_TRANSFORMER) > 0;
    }

    @Override
    public Tier inputTier() {
        return AdUpgradeMath.inputTier(getTier(), upgradeCount(UpgradeType.TRANSFORMER), acceptsAnyVoltage());
    }

    @Override
    public int getMaxEnergyStored() {
        return AdUpgradeMath.buffer(getTier(), upgradeCount(UpgradeType.ENERGY_STORAGE), Math.max(ConfigSVA.aptShelterEu * 2, upkeep()));
    }

    /** World tests: stands in for a cable feed. */
    public void fillForTest(int eu) {
        addEnergy(eu);
    }

    /** World tests: the buffer emptied (a cut cable). */
    public void drainForTest() {
        removeEnergy(getEnergyStored());
    }

    @Override
    public void updateEntity() {
        super.updateEntity();
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        int key = acceptsAnyVoltage() ? 1000 : inputTier().ordinal();
        if (key != lastTierKey) {
            if (lastTierKey >= 0) {
                refreshEnergyNet();                                 // IC2 caches a sink's tier: announce the new one
            }
            lastTierKey = key;
        }
        long now = worldObj.getTotalWorldTime();
        int use = upkeep();
        if (use > 0) {
            boolean had = getEnergyStored() >= use;
            if (had) {
                removeEnergy(use);
            }
            powered = had;
        } else {
            powered = getEnergyStored() > 0;
        }
        if ((now + (xCoord * 7 + zCoord * 13 & 15)) % REPORT == 0) {
            report(now);
            int pw = isPowered() ? 1 : 0;
            if (pw != sentPowered) {
                sentPowered = pw;
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            }
        }
    }

    /** The antennas in range and the report to the airport list. */
    void report(long now) {
        served.clear();
        AirportDataSVA d = AirportDataSVA.get(worldObj);
        for (int x = xCoord - RANGE; x <= xCoord + RANGE; x++) {
            for (int z = zCoord - RANGE; z <= zCoord + RANGE; z++) {
                for (int y = Math.max(0, yCoord - RANGE_Y); y <= Math.min(255, yCoord + RANGE_Y); y++) {
                    if (worldObj.getBlock(x, y, z) instanceof BlockIlsAntennaSVA) {
                        String k = AirportDataSVA.key(worldObj, x, y, z);
                        served.add(k);
                        if (d != null) {
                            d.shelterReport(k, isPowered(), now);
                        }
                    }
                }
            }
        }
    }

    public void dropUpgrades(World w, int x, int y, int z) {
        for (int i = 0; i < upg.length; i++) {
            if (upg[i] != null) {
                w.spawnEntityInWorld(new EntityItem(w, x + 0.5, y + 0.5, z + 0.5, upg[i].copy()));
                upg[i] = null;
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        super.writeToNBT(t);
        NBTTagList l = new NBTTagList();
        for (int i = 0; i < upg.length; i++) {
            if (upg[i] != null) {
                NBTTagCompound e = new NBTTagCompound();
                e.setByte("Slot", (byte) i);
                upg[i].writeToNBT(e);
                l.appendTag(e);
            }
        }
        t.setTag("Upgrades", l);
        t.setBoolean("Powered", powered);
    }

    @Override
    public void readFromNBT(NBTTagCompound t) {
        super.readFromNBT(t);
        NBTTagList l = t.getTagList("Upgrades", 10);
        for (int i = 0; i < upg.length; i++) {
            upg[i] = null;
        }
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound e = l.getCompoundTagAt(i);
            int s = e.getByte("Slot");
            if (s >= 0 && s < upg.length) {
                upg[s] = ItemStack.loadItemStackFromNBT(e);
            }
        }
        powered = t.getBoolean("Powered");
    }

    @Override
    public net.minecraft.network.Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        t.setBoolean("P", isPowered());
        return new net.minecraft.network.play.server.S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(net.minecraft.network.NetworkManager net, net.minecraft.network.play.server.S35PacketUpdateTileEntity pkt) {
        clientPowered = pkt.func_148857_g().getBoolean("P");
    }

    public boolean reach(EntityPlayer p) {
        return p.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= 64;
    }

    /** The upgrade slots (the screen's), only a module that fits. */
    private final class UpgradeInv implements IInventory {
        @Override
        public int getSizeInventory() {
            return upg.length;
        }

        @Override
        public ItemStack getStackInSlot(int i) {
            return i >= 0 && i < upg.length ? upg[i] : null;
        }

        @Override
        public ItemStack decrStackSize(int i, int n) {
            if (i < 0 || i >= upg.length || upg[i] == null) {
                return null;
            }
            ItemStack out;
            if (upg[i].stackSize <= n) {
                out = upg[i];
                upg[i] = null;
            } else {
                out = upg[i].splitStack(n);
            }
            markDirty();
            return out;
        }

        @Override
        public ItemStack getStackInSlotOnClosing(int i) {
            return null;
        }

        @Override
        public void setInventorySlotContents(int i, ItemStack s) {
            if (i >= 0 && i < upg.length) {
                upg[i] = s;
                if (s != null && s.stackSize > getInventoryStackLimit()) {
                    s.stackSize = getInventoryStackLimit();
                }
                markDirty();
            }
        }

        @Override
        public String getInventoryName() {
            return "container.sva.ilsShelter";
        }

        @Override
        public boolean hasCustomInventoryName() {
            return false;
        }

        @Override
        public int getInventoryStackLimit() {
            return 64;
        }

        @Override
        public void markDirty() {
            TileEntityIlsShelterSVA.this.markDirty();
        }

        @Override
        public boolean isUseableByPlayer(EntityPlayer p) {
            TileEntity te = worldObj == null ? null : worldObj.getTileEntity(xCoord, yCoord, zCoord);
            return te == TileEntityIlsShelterSVA.this && reach(p);
        }

        @Override
        public void openInventory() {
        }

        @Override
        public void closeInventory() {
        }

        @Override
        public boolean isItemValidForSlot(int i, ItemStack s) {
            return upgradeAccepted(s);
        }
    }
}
