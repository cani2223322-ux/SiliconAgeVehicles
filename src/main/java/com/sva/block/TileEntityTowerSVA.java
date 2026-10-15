package com.sva.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.sc.energy.Tier;
import com.sc.energy.TileEntityEnergyBase;
import com.sc.item.ItemUpgradeSC;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AdUpgradeMath;
import com.sva.airport.TowerMath;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetAptSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * Airport wave B2 (plan-next §9, items 29-34; mock-up veh_airport_impl_b2_1_tower): the master block of the «Диспетчерская
 * вышка» - a shaft of PARTS blocks (BlockAptPartSVA) with the glazed cab on top (client.RenderAptSVA's model). It is the
 * airport's tower: its record lives in the save's airport list (AirportDataSVA.Tower - the ICAO code and name, the runway in
 * use, the lights, ILS on / off, the codes, ATIS, the charts' version), this block keeps what is the block's own:
 * <ul>
 * <li>the owner and his access list (as the air-defence blocks: they may open the screens; the owner alone renames the
 * airport, sets the codes and the list);</li>
 * <li>the POWER: an EU sink of MV, airport.towerEu EU/t, the core's upgrade modules in four slots (AdUpgradeMath with the
 * ILS shelter's kind: the universal transformer, transformers, storage, economizers) - no power: the tower is OFFLINE (no
 * ATIS, no datalink, no automatic tower, the stop bars out);</li>
 * <li>its report to the airport list every REPORT ticks (power, the player in the controller seat);</li>
 * <li>the screen's viewers (client.GuiTowerSVA, no container): the picture (AtcServerSVA.snapshot) every 10 ticks while
 * open; the CONTROLLER SEAT - a viewer who takes it is the controller while his screen stays open (its heartbeat);</li>
 * <li>the commands of the screen (command()): the airport's settings, the lights, ILS, the runway in use, clearances to an
 * aircraft, printing an approach chart (a sheet of paper), the taxi graph's links.</li>
 * </ul>
 */
public class TileEntityTowerSVA extends TileEntityEnergyBase {

    /** The shaft's parts above the master (dy 1..PARTS); the cab is the top two. */
    public static final int PARTS = 5;
    public static final int REPORT = 20, MAX_ACCESS = 16, SNAP_EVERY = 10;
    /** A viewer / the controller is gone after this many ticks without a heartbeat. */
    public static final int VIEW_TIMEOUT = 60;

    private final ItemStack[] upg = new ItemStack[AdUpgradeMath.SLOTS];
    public final IInventory upgrades = new UpgradeInv();
    private String ownerName = "", ownerId = "";
    private final List<String> access = new ArrayList<String>();
    /** The placer's facing (MathHelper: 0 south .. 3 east) - the cab's front. */
    public int facing;
    private boolean powered;
    private int lastTierKey = -1, timer;
    /** The ICAO code / name the block remembers (the airport list's record made again if it went missing). */
    private String icao = "", name = "";
    // transient
    private final Map<EntityPlayer, Long> viewers = new HashMap<EntityPlayer, Long>();
    private String controller = "";
    private long controllerAt;
    /** Client: powered (the cab's lights), the airport's code (the model's sign). */
    public boolean clientPowered;
    public String clientIcao = "";
    private int sentPowered = -1;

    public TileEntityTowerSVA() {
        super(Tier.MV);
    }

    public String key() {
        return AirportDataSVA.key(worldObj, xCoord, yCoord, zCoord);
    }

    // ------------------------------------------------------------------ power and upgrades

    public boolean isPowered() {
        return powered;
    }

    public int upkeep() {
        return AdUpgradeMath.upkeep(ConfigSVA.aptTowerEu, 0, upgradeCount(UpgradeType.ECONOMIZER), 0);
    }

    public int upgradeCount(UpgradeType type) {
        if (!ConfigSVA.adUpgrades || !AdUpgradeMath.accepts(TileEntityIlsShelterSVA.B_ILS, type)) {
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
        return ConfigSVA.adUpgrades && s != null && s.getItem() instanceof ItemUpgradeSC
                && AdUpgradeMath.accepts(TileEntityIlsShelterSVA.B_ILS, ItemUpgradeSC.typeOf(s)) && ItemUpgradeSC.typeOf(s) != UpgradeType.OVERCLOCKER;
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
        return AdUpgradeMath.buffer(getTier(), upgradeCount(UpgradeType.ENERGY_STORAGE), Math.max(ConfigSVA.aptTowerEu * 2, upkeep()));
    }

    /** World tests: stands in for a cable feed. */
    public void fillForTest() {
        addEnergy(getMaxEnergyStored());
    }

    public void drainForTest() {
        removeEnergy(getEnergyStored());
    }

    public void dropUpgrades(World w, int x, int y, int z) {
        for (int i = 0; i < upg.length; i++) {
            if (upg[i] != null) {
                w.spawnEntityInWorld(new EntityItem(w, x + 0.5, y + 0.5, z + 0.5, upg[i].copy()));
                upg[i] = null;
            }
        }
    }

    // ------------------------------------------------------------------ owner and access

    public String getOwnerName() {
        return ownerName;
    }

    public List<String> getAccess() {
        return access;
    }

    public void setOwner(EntityPlayer p) {
        if (p != null) {
            ownerName = p.getCommandSenderName();
            ownerId = p.getUniqueID().toString();
            markDirty();
        }
    }

    public void setOwnerName(String n) {
        ownerName = n == null ? "" : n;
        markDirty();
    }

    /** Opens the screens: the owner, his access list, an op. */
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

    public boolean isOwner(EntityPlayer p) {
        return p != null && (ownerName.length() == 0 || ownerName.equalsIgnoreCase(p.getCommandSenderName()) || EntityVehicleSVA.isOp(p));
    }

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

    public boolean reach(EntityPlayer p) {
        return p.getDistanceSq(xCoord + 0.5, yCoord + 3, zCoord + 0.5) <= 144;
    }

    // ------------------------------------------------------------------ the airport record

    /** The airport list's record of this tower (made when missing: the ICAO code kept, else a free one). */
    public AirportDataSVA.Tower record() {
        if (worldObj == null || worldObj.isRemote) {
            return null;
        }
        AirportDataSVA d = AirportDataSVA.get(worldObj);
        if (d == null) {
            return null;
        }
        AirportDataSVA.Tower t = d.tower(key());
        if (t == null) {
            t = new AirportDataSVA.Tower();
            t.key = key();
            t.dim = worldObj.provider.dimensionId;
            t.x = xCoord;
            t.y = yCoord;
            t.z = zCoord;
            List<String> others = d.icaosExcept(t.key);
            t.icao = icao.length() == 4 && TowerMath.unique(icao, others) ? icao : TowerMath.defaultIcao(xCoord, zCoord, others);
            t.name = name;
            t.owner = ownerName;
            d.putTower(t);
            icao = t.icao;
            markDirty();
        }
        return t;
    }

    /** The record changed through the screen (the block's copy of the code / name, the clients' model sign). */
    public void recordChanged(AirportDataSVA.Tower t) {
        icao = t.icao;
        name = t.name;
        AirportDataSVA d = AirportDataSVA.get(worldObj);
        if (d != null) {
            d.towerChanged();
        }
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    // ------------------------------------------------------------------ the tick

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
        int use = upkeep();
        if (use > 0) {
            boolean had = getEnergyStored() >= use;
            if (had) {
                removeEnergy(use);
            }
            powered = had;
        } else {
            powered = true;
        }
        long now = worldObj.getTotalWorldTime();
        if (controller.length() > 0 && now - controllerAt > VIEW_TIMEOUT) {
            controller = "";
        }
        if (++timer % REPORT == 0) {
            AirportDataSVA.Tower t = record();
            AirportDataSVA d = AirportDataSVA.get(worldObj);
            if (t != null && d != null) {
                if (!t.owner.equals(ownerName)) {
                    t.owner = ownerName;
                    d.markDirty();
                }
                d.towerReport(t.key, powered, controller, now);
            }
            int pw = powered ? 1 : 0;
            if (pw != sentPowered) {
                sentPowered = pw;
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            }
        }
        if (timer % SNAP_EVERY == 3 && !viewers.isEmpty()) {
            sendViewers(now);
        }
    }

    // ------------------------------------------------------------------ the screen's viewers and the controller seat

    /** A heartbeat of an open screen (the first one: the picture at once). */
    public void viewer(EntityPlayer p, boolean seat) {
        long now = worldObj.getTotalWorldTime();
        boolean first = !viewers.containsKey(p);
        viewers.put(p, Long.valueOf(now));
        if (seat && p.getCommandSenderName().equals(controller)) {
            controllerAt = now;
        }
        if (first) {
            sendViewers(now);
        }
    }

    public void closed(EntityPlayer p) {
        viewers.remove(p);
        if (p.getCommandSenderName().equals(controller)) {
            controller = "";
            report();
        }
    }

    /** Take / leave the controller seat. @return the seat's holder now */
    public String seat(EntityPlayer p, boolean take) {
        long now = worldObj.getTotalWorldTime();
        if (take && (controller.length() == 0 || controller.equals(p.getCommandSenderName()))) {
            controller = p.getCommandSenderName();
            controllerAt = now;
        } else if (!take && controller.equals(p.getCommandSenderName())) {
            controller = "";
        }
        report();
        return controller;
    }

    public String controller() {
        return controller;
    }

    /** World tests: the controller seat held by a name (heartbeats kept by the test). */
    public void debugController(String n) {
        controller = n == null ? "" : n;
        controllerAt = worldObj.getTotalWorldTime();
        report();
    }

    void report() {
        AirportDataSVA d = AirportDataSVA.get(worldObj);
        AirportDataSVA.Tower t = record();
        if (d != null && t != null) {
            d.towerReport(t.key, powered, controller, worldObj.getTotalWorldTime());
        }
    }

    private void sendViewers(long now) {
        AirportDataSVA d = AirportDataSVA.get(worldObj);
        AirportDataSVA.Tower t = record();
        if (d == null || t == null) {
            return;
        }
        for (Iterator<Map.Entry<EntityPlayer, Long>> it = viewers.entrySet().iterator(); it.hasNext();) {
            Map.Entry<EntityPlayer, Long> e = it.next();
            EntityPlayer p = e.getKey();
            if (now - e.getValue().longValue() > VIEW_TIMEOUT || p.isDead || p.worldObj != worldObj || !(p instanceof EntityPlayerMP)) {
                it.remove();
                continue;
            }
            NBTTagCompound snap = TowerScreenSVA.snapshot(worldObj, d, this, t, p, now);
            NetAptSVA.send((EntityPlayerMP) p, NetAptSVA.D_TOWER, snap);
        }
    }

    // ------------------------------------------------------------------ save and sync

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
        t.setString("Owner", ownerName);
        t.setString("OwnerId", ownerId);
        NBTTagList a = new NBTTagList();
        for (String s : access) {
            a.appendTag(new NBTTagString(s));
        }
        t.setTag("Access", a);
        t.setInteger("Facing", facing);
        t.setBoolean("Powered", powered);
        t.setString("Icao", icao);
        t.setString("Name", name);
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
        ownerName = t.getString("Owner");
        ownerId = t.getString("OwnerId");
        access.clear();
        NBTTagList a = t.getTagList("Access", 8);
        for (int i = 0; i < a.tagCount() && i < MAX_ACCESS; i++) {
            access.add(a.getStringTagAt(i));
        }
        facing = t.getInteger("Facing") & 3;
        powered = t.getBoolean("Powered");
        icao = TowerMath.cleanIcao(t.getString("Icao"));
        name = TowerMath.cleanName(t.getString("Name"));
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        t.setBoolean("P", powered);
        t.setInteger("F", facing);
        t.setString("I", icao);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound t = pkt.func_148857_g();
        clientPowered = t.getBoolean("P");
        facing = t.getInteger("F") & 3;
        clientIcao = t.getString("I");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return 512.0 * 512.0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return AxisAlignedBB.getBoundingBox(xCoord - 2, yCoord, zCoord - 2, xCoord + 3, yCoord + PARTS + 3, zCoord + 3);
    }

    /** The upgrade slots (the equipment screen's), only a module that fits. */
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
            return "container.sva.tower";
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
            TileEntityTowerSVA.this.markDirty();
        }

        @Override
        public boolean isUseableByPlayer(EntityPlayer p) {
            TileEntity te = worldObj == null ? null : worldObj.getTileEntity(xCoord, yCoord, zCoord);
            return te == TileEntityTowerSVA.this && reach(p) && mayUse(p);
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
