package com.sva.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.sc.energy.Tier;
import com.sc.energy.TileEntityEnergyBase;
import com.sc.item.ItemUpgradeSC;
import com.sc.machine.UpgradeType;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemStationModuleSVA;
import com.sva.platform.PlatformMath;
import com.sva.vehicle.AccessMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleMath;
import com.sva.workbench.WorkbenchMath;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;

/**
 * «Зарядная станция техники» - a core energy tile (IC2 net / the core's own net, overvoltage rules included) and the
 * controller of a charging platform (plan §9, variant 1).
 * <ul>
 * <li>No plates touching it: the old 1-block charger - a vehicle touching its top gets the tier's packet a tick.</li>
 * <li>Plates (BlockChargePlateSVA) on its level, 4-way connected to it, up to 121 (flood fill on a neighbour change,
 * a plate placed / broken nearby, and every 5 s): vehicles over a plate (or on the station) that fit the platform
 * (VehicleType.platformSize vs plate count + bounding rectangle) are served one per tick in turn (round robin).
 * Rate = PlatformMath.rate: input tier packet x 1.6 per Overclocker x (1 + 10% per plate over the vehicle's minimum).</li>
 * <li>Slots: 0-3 core upgrades (Overclocker, Transformer = +1 input tier, Energy Storage = +100k EU), 4-6 modules
 * (ItemStationModuleSVA: gas / repair / access).</li>
 * <li>Gas module: pulls each gas the vehicle has a tank for (25 mB/t) from a Gas Column touching the station or a plate
 * (the column's own owner rule). Repair module: +1% hull every 60 ticks, one core steel ingot per 10 steps, taken
 * from an inventory touching the station or a plate (only with wear on). Access module: only the station owner's vehicles,
 * and those whose driver may use one of the owner's vehicles (its access list or a valid key card - ТБ-2).</li>
 * <li>Automation reaches no slot (ТБ-3): a hopper under the station takes neither upgrades nor modules.</li>
 * <li>Comparator: the served vehicle's charge; «сигнал при полном»: strong redstone 15 while the vehicle is full;
 * «звук»: one chime when it fills up.</li>
 * </ul>
 * The plates' look (off / charging / full) is sent with the description packet (plate offsets + state) only when
 * it changes; clients keep it in PlateStatesSVA.
 * <p>Fast charging (2026-10-10):
 * <ul>
 * <li>«Быстрая зарядка» module x2 (one counts; the SV station - meta Tier.SV, SV input without transformers - has it built in).</li>
 * <li>«Форсаж» (SET_BOOST, the screen's button, vehicles.stationBoost): x3 while it charges, +1 heat a tick; at HEAT_MAX (2 min)
 * it overheats and charges nothing until cold (30 s). Helium from the gas module's column, 1 mB a second, keeps it cool.</li>
 * <li>Several stations on one platform (stations touching its plates = peers): every one charges the same vehicle in the same
 * tick from its own buffer (PlatformMath.sharedPick), the rates add up. The plates' look belongs to one of them (the
 * master: lowest x, y, z), which shows «charging» when any of them charges; only it chimes and warns «не помещается».</li>
 * <li>A core energy storage (or an IC2 one) whose output faces the station: the station takes what its rate leaves
 * straight from it into the vehicle (extractForItemCharging - the storage's own accounting and on/off switch), and
 * refuses grid input on that face (no overvoltage from a stronger storage).</li>
 * <li>A «Суперконденсатор станции» touching it empties into the vehicle at 500 000 EU/t on top of the rate.</li>
 * </ul>
 */
public class TileEntityChargerSVA extends TileEntityEnergyBase implements ISidedInventory, KeptDataSVA.Keeper {

    public static final int SCAN_EVERY = 20, ACTIVE_HOLD = 10, PLATFORM_EVERY = 100;
    public static final int FIRST_UPGRADE = 0, UPGRADE_SLOTS = 4, FIRST_MODULE = 4, MODULE_SLOTS = 3, SIZE = 7;
    /** Status bits (screen, tests). */
    public static final int S_VEHICLE = 1, S_CHARGING = 2, S_REFUEL = 4, S_REPAIR = 8, S_FULL = 16, S_NO_FIT = 32, S_NO_ACCESS = 64,
            S_NO_ENERGY = 128, S_OFF = 256, S_NO_STEEL = 512, S_WEAR_OFF = 1024, S_PLATFORM = 2048, S_OVERHEAT = 4096, S_BOOST = 8192,
            S_HELIUM = 16384;
    /** Settings bits (SET_BOOST: «Форсаж», off by default). */
    public static final int SET_REDSTONE = 1, SET_SOUND = 2, SET_GAS = 4, SET_DEFAULT = SET_REDSTONE | SET_SOUND | SET_GAS, SET_BOOST = 8,
            SET_ALL = SET_DEFAULT | SET_BOOST;

    // NBT keys (append-only)
    private static final String K_ACTIVE = "ChargerActive", K_ITEMS = "ChargerItems", K_FACING = "ChargerFacing", K_OWNER = "Owner",
            K_OWNER_NAME = "OwnerName", K_SETTINGS = "ChargerSettings", K_PAID = "RepairPaid", K_FULL = "ChargerFull",
            K_HEAT = "ChargerHeat", K_OVERHEAT = "ChargerOverheat";
    // description packet keys
    private static final String D_PLATES = "Pl", D_VISUAL = "Vs", D_FACING = "Fc", D_MASTER = "Ms";
    private static final int[] NO_SLOTS = {};

    private final ItemStack[] slots = new ItemStack[SIZE];
    private int facing = 3;
    private String ownerId = "", ownerName = "";
    private int settings = SET_DEFAULT;

    // ---- server: the platform
    private Set<Long> plates = Collections.emptySet();
    private int[] bounds;
    private boolean platformDirty = true;
    private int platformTimer;
    private final List<ChunkCoordinates> columns = new ArrayList<ChunkCoordinates>(), chests = new ArrayList<ChunkCoordinates>();
    private int edgePlates, aviaPlates;
    // ---- server: fast charging - stations on the same plates, storages / supercapacitors touching the station
    private final List<ChunkCoordinates> peers = new ArrayList<ChunkCoordinates>(), storages = new ArrayList<ChunkCoordinates>(),
            caps = new ArrayList<ChunkCoordinates>();
    /** Faces (ForgeDirection ordinal bits) a linked storage's output touches: no grid input there. */
    private int storageFaces;
    private boolean master = true;
    private int heat, heliumTicks;
    private boolean overheated;
    // ---- server: vehicles
    private final List<EntityVehicleSVA> served = new ArrayList<EntityVehicleSVA>();
    private EntityVehicleSVA current;
    private int scan;
    private int sinceCharge = 1000;
    private boolean active, full;
    private int status, repairTimer, repairPaid, lastTransformers = -1;
    private int lastComparator, lastPower, visual = -1, sentHash;
    private int lastWarned = -1;
    // ---- server: measured rates (screen)
    private long euWindow;
    private int gasWindow, windowTicks, measuredEu, measuredGas;
    // ---- client
    private int[] clientPlates = new int[0];
    private int clientVisual;
    private boolean clientMaster = true;

    public TileEntityChargerSVA() {
        super(Tier.MV);
    }

    public TileEntityChargerSVA(Tier tier) {
        super(tier);
    }

    // ------------------------------------------------------------------ upgrades / modules / energy

    public int upgradeCount(UpgradeType type) {
        int n = 0;
        for (int i = FIRST_UPGRADE; i < FIRST_UPGRADE + UPGRADE_SLOTS; i++) {
            ItemStack s = slots[i];
            if (s != null && s.getItem() instanceof ItemUpgradeSC && ItemUpgradeSC.typeOf(s) == type) {
                n += s.stackSize;
            }
        }
        return WorkbenchMath.cap(n);
    }

    public static boolean upgradeAccepted(ItemStack s) {
        if (s == null || !(s.getItem() instanceof ItemUpgradeSC)) {
            return false;
        }
        UpgradeType t = ItemUpgradeSC.typeOf(s);
        return t == UpgradeType.OVERCLOCKER || t == UpgradeType.TRANSFORMER || t == UpgradeType.ENERGY_STORAGE
                || t == UpgradeType.UNIVERSAL_TRANSFORMER;
    }

    /**
     * The core's Universal Transformer upgrade (one is enough): any line voltage, LV to SV, never overvolts the station,
     * like the Engineering Workbench, and the input tier (the charging rate and buffer) is the top one.
     */
    @Override
    public boolean acceptsAnyVoltage() {
        return upgradeCount(UpgradeType.UNIVERSAL_TRANSFORMER) > 0;
    }

    public static boolean moduleAccepted(ItemStack s) {
        return s != null && s.getItem() instanceof ItemStationModuleSVA && ItemStationModuleSVA.kind(s) >= 0;
    }

    public boolean hasModule(int kind) {
        for (int i = FIRST_MODULE; i < FIRST_MODULE + MODULE_SLOTS; i++) {
            if (moduleAccepted(slots[i]) && ItemStationModuleSVA.kind(slots[i]) == kind) {
                return true;
            }
        }
        return false;
    }

    /** «Быстрая зарядка»: the module, or the SV station's own. */
    public boolean hasFastCharge() {
        return getTier() == Tier.SV || hasModule(ItemStationModuleSVA.FAST);
    }

    /** «Форсаж» switched on (the button; vehicles.stationBoost). */
    public boolean boostOn() {
        return ConfigSVA.stationBoost && (settings & SET_BOOST) != 0;
    }

    /** x3 now: switched on and not overheated. */
    public boolean boostActive() {
        return boostOn() && !overheated;
    }

    public int getHeat() {
        return heat;
    }

    public boolean isOverheated() {
        return overheated;
    }

    @Override
    public Tier inputTier() {
        // the Universal Transformer: the top tier, like the Engineering Workbench (WorkbenchMath.inputTier) - the
        // station then charges at SV voltage (and holds an SV buffer) from any line
        return acceptsAnyVoltage() ? com.sc.energy.Tier.max() : PlatformMath.inputTier(getTier(), upgradeCount(UpgradeType.TRANSFORMER));
    }

    @Override
    public int getMaxEnergyStored() {
        return PlatformMath.buffer(inputTier(), upgradeCount(UpgradeType.ENERGY_STORAGE));
    }

    /** EU a tick with no plate bonus (the lone charger, tooltips). */
    public int rate() {
        return rateFor(0);
    }

    /** EU a tick to one vehicle: the plate bonus, the fast-charge module and the boost (when not overheated) included. */
    public int rateFor(double plateBonus) {
        return PlatformMath.rate(inputTier(), upgradeCount(UpgradeType.OVERCLOCKER), plateBonus, ConfigSVA.chargerRate, hasFastCharge(),
                boostActive());
    }

    /** The speed bonus a vehicle gets here (0 without plates). */
    public double bonusFor(EntityVehicleSVA v) {
        return plates.isEmpty() ? 0 : PlatformMath.plateBonus(plates.size(), v.getVehicleType().platformSize);
    }

    // ------------------------------------------------------------------ state for screens / tests

    public boolean isActive() {
        return active;
    }

    public int getFacing() {
        return facing;
    }

    public void setFacing(int f) {
        facing = f < 2 || f > 5 ? 3 : f;
    }

    public void setOwner(String id, String name) {
        ownerId = id == null ? "" : id;
        ownerName = name == null ? "" : name;
        markDirty();
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    /** May change the settings: the owner, an op with vehicles.opManage, anyone when it has no owner. */
    public boolean mayConfigure(EntityPlayer p) {
        if (ownerId.length() == 0 || ownerId.equals(p.getUniqueID().toString())) {
            return true;
        }
        return com.sva.ConfigSVA.opManage && com.sva.entity.EntityVehicleSVA.isOp(p);
    }

    public int getSettings() {
        return settings;
    }

    public void toggleSetting(int bit) {
        if (bit == SET_REDSTONE || bit == SET_SOUND || bit == SET_GAS || bit == SET_BOOST) {
            settings ^= bit;
            markDirty();
        }
    }

    public int getStatus() {
        return status;
    }

    public int plateCount() {
        return plates.size();
    }

    /** {spanX, spanZ} of the platform (0, 0 without plates). */
    public int[] span() {
        return bounds == null ? new int[] {0, 0} : new int[] {bounds[2] - bounds[0] + 1, bounds[3] - bounds[1] + 1};
    }

    public Set<Long> getPlates() {
        return Collections.unmodifiableSet(plates);
    }

    public EntityVehicleSVA getCurrent() {
        return current;
    }

    public List<EntityVehicleSVA> getServed() {
        return Collections.unmodifiableList(served);
    }

    public int getMeasuredEu() {
        return measuredEu;
    }

    public int getMeasuredGas() {
        return measuredGas;
    }

    public int getEdgePlates() {
        return edgePlates;
    }

    public int getAviaPlates() {
        return aviaPlates;
    }

    public int getRepairPaid() {
        return repairPaid;
    }

    /** The first Gas Column touching the platform, or null. */
    public TileEntityGasColumnSVA column() {
        for (ChunkCoordinates c : columns) {
            TileEntity te = worldObj.getTileEntity(c.posX, c.posY, c.posZ);
            if (te instanceof TileEntityGasColumnSVA && !te.isInvalid()) {
                return (TileEntityGasColumnSVA) te;
            }
        }
        return null;
    }

    /** Core steel ingots in the inventories touching the platform. */
    public int steelAvailable() {
        int n = 0;
        for (ChunkCoordinates c : chests) {
            TileEntity te = worldObj.getTileEntity(c.posX, c.posY, c.posZ);
            if (te instanceof IInventory && !te.isInvalid()) {
                IInventory inv = (IInventory) te;
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    ItemStack s = inv.getStackInSlot(i);
                    if (ModContentSVA.isSteelIngot(s)) {
                        n += s.stackSize;
                    }
                }
            }
        }
        return n;
    }

    public int chestCount() {
        return chests.size();
    }

    public int comparatorLevel() {
        return lastComparator;
    }

    public int redstonePower() {
        return lastPower;
    }

    public boolean isFull() {
        return full;
    }

    /** The other stations on this platform (loaded, still there). */
    public List<TileEntityChargerSVA> peerStations() {
        List<TileEntityChargerSVA> out = new ArrayList<TileEntityChargerSVA>();
        for (ChunkCoordinates c : peers) {
            TileEntity te = worldObj.blockExists(c.posX, c.posY, c.posZ) ? worldObj.getTileEntity(c.posX, c.posY, c.posZ) : null;
            if (te instanceof TileEntityChargerSVA && te != this && !te.isInvalid()) {
                out.add((TileEntityChargerSVA) te);
            }
        }
        return out;
    }

    /** Shows the plates' look for the platform (no other station on it has lower coordinates). */
    public boolean isMaster() {
        return master;
    }

    /** EU a tick this station and the others on its platform give a vehicle together (each for its current one). */
    public int summedRate() {
        EntityVehicleSVA v = current;
        long t = v == null ? rate() : rateFor(bonusFor(v));
        for (TileEntityChargerSVA p : peerStations()) {
            EntityVehicleSVA pv = p.getCurrent();
            t += pv == null ? p.rate() : p.rateFor(p.bonusFor(pv));
        }
        return (int) Math.min(Integer.MAX_VALUE, t);
    }

    /** EU a tick measured into vehicles by this station and the others on its platform. */
    public int summedMeasured() {
        long t = measuredEu;
        for (TileEntityChargerSVA p : peerStations()) {
            t += p.getMeasuredEu();
        }
        return (int) Math.min(Integer.MAX_VALUE, t);
    }

    /** Linked energy storages (core / IC2) still there. */
    public int storageCount() {
        int n = 0;
        for (ChunkCoordinates c : storages) {
            if (storageAt(c) != null) {
                n++;
            }
        }
        return n;
    }

    /** EU in the linked storages (screen). */
    public long storageEnergy() {
        long t = 0;
        for (ChunkCoordinates c : storages) {
            TileEntity te = storageAt(c);
            if (te instanceof TileEntityEnergyBase) {
                t += ((TileEntityEnergyBase) te).getEnergyStored();
            } else if (te != null && ic2Loaded()) {
                t += Ic2StorageLinkSVA.stored(te);
            }
        }
        return t;
    }

    /** Supercapacitors touching the station. */
    public List<TileEntitySupercapSVA> supercaps() {
        List<TileEntitySupercapSVA> out = new ArrayList<TileEntitySupercapSVA>();
        for (ChunkCoordinates c : caps) {
            TileEntity te = worldObj.blockExists(c.posX, c.posY, c.posZ) ? worldObj.getTileEntity(c.posX, c.posY, c.posZ) : null;
            if (te instanceof TileEntitySupercapSVA && !te.isInvalid()) {
                out.add((TileEntitySupercapSVA) te);
            }
        }
        return out;
    }

    private TileEntity storageAt(ChunkCoordinates c) {
        TileEntity te = worldObj.blockExists(c.posX, c.posY, c.posZ) ? worldObj.getTileEntity(c.posX, c.posY, c.posZ) : null;
        return te == null || te.isInvalid() ? null : te;
    }

    private static boolean ic2Loaded() {
        return cpw.mods.fml.common.Loader.isModLoaded(com.sc.Reference.IC2_MODID);
    }

    /** No grid input on a face a linked storage's output touches (a stronger storage would overvolt the station). */
    @Override
    public boolean acceptsFrom(net.minecraftforge.common.util.ForgeDirection side) {
        return side == null || side.ordinal() >= 6 || (storageFaces & 1 << side.ordinal()) == 0;
    }

    /** A plate nearby was placed / broken, or a neighbour changed: re-scan next tick. */
    public void platformChanged() {
        platformDirty = true;
    }

    /** Client: the plates as packed offsets ((dx & 0xFFFF) << 16 | dz & 0xFFFF). */
    public int[] getClientPlates() {
        return clientPlates;
    }

    public int getClientVisual() {
        return clientVisual;
    }

    public boolean isClientMaster() {
        return clientMaster;
    }

    public AxisAlignedBB topZone() {
        return AxisAlignedBB.getBoundingBox(xCoord, yCoord + 1, zCoord, xCoord + 1, yCoord + 1.25, zCoord + 1);
    }

    // ------------------------------------------------------------------ the platform

    private void recomputePlatform() {
        platformDirty = false;
        platformTimer = PLATFORM_EVERY;
        final Block plate = ModContentSVA.chargePlate;
        final int y = yCoord;
        Set<Long> found = PlatformMath.floodFill(new PlatformMath.Plates() {
            @Override
            public boolean isPlate(int x, int z) {
                return worldObj.blockExists(x, y, z) && worldObj.getBlock(x, y, z) == plate;
            }
        }, xCoord, zCoord, PlatformMath.MAX_PLATES);
        plates = found;
        bounds = PlatformMath.bounds(found);
        edgePlates = 0;
        aviaPlates = 0;
        columns.clear();
        chests.clear();
        peers.clear();
        Set<Long> cells = new LinkedHashSet<Long>(found);
        cells.add(Long.valueOf(PlatformMath.key(xCoord, zCoord)));
        Set<String> seen = new HashSet<String>();
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        for (Long k : cells) {
            int cx = PlatformMath.keyX(k.longValue()), cz = PlatformMath.keyZ(k.longValue());
            if (found.contains(k)) {
                int v = BlockChargePlateSVA.variant(worldObj.getBlockMetadata(cx, y, cz));
                if (v == BlockChargePlateSVA.EDGE) {
                    edgePlates++;
                } else if (v == BlockChargePlateSVA.AVIA) {
                    aviaPlates++;
                }
            }
            for (int[] o : around) {
                int x = cx + o[0], yy = y + o[1], z = cz + o[2];
                if (!worldObj.blockExists(x, yy, z) || !seen.add(x + "," + yy + "," + z)) {
                    continue;
                }
                TileEntity te = worldObj.getTileEntity(x, yy, z);
                if (te == null || te == this) {
                    continue;
                }
                if (te instanceof TileEntityChargerSVA) {
                    if (o[1] == 0 && found.contains(k)) {
                        peers.add(new ChunkCoordinates(x, yy, z));      // another station on the same plates
                    }
                } else if (te instanceof TileEntityGasColumnSVA) {
                    columns.add(new ChunkCoordinates(x, yy, z));
                } else if (te instanceof IInventory && !(te instanceof TileEntityEnergyBase)) {
                    chests.add(new ChunkCoordinates(x, yy, z));
                }
            }
        }
        List<int[]> others = new ArrayList<int[]>();
        for (ChunkCoordinates c : peers) {
            others.add(new int[] {c.posX, c.posY, c.posZ});
        }
        master = PlatformMath.isMaster(xCoord, yCoord, zCoord, others);
        scanLinks();
    }

    /** Storages whose output faces the station and supercapacitors, on the station's own six faces. */
    private void scanLinks() {
        storages.clear();
        caps.clear();
        int faces = 0;
        boolean ic2 = ic2Loaded();
        for (net.minecraftforge.common.util.ForgeDirection d : net.minecraftforge.common.util.ForgeDirection.VALID_DIRECTIONS) {
            int x = xCoord + d.offsetX, y = yCoord + d.offsetY, z = zCoord + d.offsetZ;
            TileEntity te = worldObj.blockExists(x, y, z) ? worldObj.getTileEntity(x, y, z) : null;
            if (te == null || te.isInvalid()) {
                continue;
            }
            if (te instanceof TileEntitySupercapSVA) {
                caps.add(new ChunkCoordinates(x, y, z));
            } else if (ConfigSVA.stationStorageLink && (te instanceof com.sc.tileentity.TileEntityEnergyStorageSC
                    && ((com.sc.tileentity.TileEntityEnergyStorageSC) te).isOutputFace(d.getOpposite())
                    || ic2 && Ic2StorageLinkSVA.feeds(te, this, d.getOpposite()))) {
                storages.add(new ChunkCoordinates(x, y, z));
                faces |= 1 << d.ordinal();
            }
        }
        if (faces != storageFaces) {
            storageFaces = faces;
            refreshEnergyNet();                              // IC2 caches the faces a sink accepts on
        }
    }

    /** Takes up to `max` EU from the linked storages (their own accounting, their on/off switch). */
    private int pullStorages(int max) {
        int got = 0;
        boolean ic2 = ic2Loaded();
        for (int i = 0; i < storages.size() && got < max; i++) {
            TileEntity te = storageAt(storages.get(i));
            if (te instanceof com.sc.tileentity.TileEntityEnergyStorageSC) {
                got += ((com.sc.tileentity.TileEntityEnergyStorageSC) te).extractForItemCharging(max - got);
            } else if (te != null && ic2) {
                got += Ic2StorageLinkSVA.take(te, max - got);
            }
        }
        return got;
    }

    /** Over a plate (or on the station): centre column on a plate, at most a hover height above it. */
    public boolean onPlatform(EntityVehicleSVA v) {
        if (v == null || v.isDead || v.worldObj != worldObj) {
            return false;
        }
        if (v.boundingBox.intersectsWith(topZone())) {
            return true;
        }
        if (plates.isEmpty()) {
            return false;
        }
        double dy = v.posY - (yCoord + 1);
        if (dy < -0.3 || dy > 4.5) {
            return false;
        }
        return plates.contains(Long.valueOf(PlatformMath.key(MathHelper.floor_double(v.posX), MathHelper.floor_double(v.posZ))));
    }

    public boolean fits(EntityVehicleSVA v) {
        if (plates.isEmpty()) {
            return true;                                    // the lone charger: anything parked on top
        }
        int[] s = span();
        return PlatformMath.fits(v.getVehicleType().platformSize, plates.size(), s[0], s[1]);
    }

    public boolean allows(EntityVehicleSVA v) {
        return !hasModule(ItemStationModuleSVA.ACCESS) || ownerId.length() == 0 || ownerId.equals(v.state.ownerId) || driverTrusted(v);
    }

    /**
     * ТБ-2, the garage's rule: the vehicle's driver is the station's owner, or has rights on one of the owner's vehicles -
     * his name on its access list (a row with any right) or a valid key card for it in his inventory (vehicles.locks on).
     * The «Все остальные» row and an open lock don't count. A vehicle with nobody at the wheel: by its owner only.
     */
    private boolean driverTrusted(EntityVehicleSVA v) {
        if (!(v.riddenByEntity instanceof EntityPlayer)) {
            return false;
        }
        EntityPlayer p = (EntityPlayer) v.riddenByEntity;
        if (ownerId.equals(p.getUniqueID().toString())) {
            return true;
        }
        VehicleRegistrySVA reg = ConfigSVA.locks ? VehicleRegistrySVA.get(worldObj) : null;
        if (reg == null) {
            return false;
        }
        String name = p.getCommandSenderName();
        for (VehicleRegistrySVA.Entry e : reg.all()) {
            if (!ownerId.equals(e.owner) || e.access.mode == AccessMath.OWNER) {
                continue;
            }
            int card = -1;
            for (ItemStack s : p.inventory.mainInventory) {
                int r = com.sva.item.ItemKeyCardSVA.rightsFor(s, e.id, e.access);
                if (r >= 0) {
                    card = card < 0 ? r : card | r;
                }
            }
            if (AccessMath.rights(true, false, false, AccessMath.FRIENDS, e.access.listed(p.getUniqueID().toString(), name), 0, card) != 0) {
                return true;
            }
        }
        return false;
    }

    private AxisAlignedBB searchZone() {
        if (bounds == null) {
            return topZone();
        }
        int x0 = Math.min(bounds[0], xCoord), z0 = Math.min(bounds[1], zCoord), x1 = Math.max(bounds[2], xCoord), z1 = Math.max(bounds[3], zCoord);
        return AxisAlignedBB.getBoundingBox(x0 - 0.5, yCoord + 0.5, z0 - 0.5, x1 + 1.5, yCoord + 6, z1 + 1.5);
    }

    private void scanVehicles() {
        served.clear();
        status &= ~(S_NO_FIT | S_NO_ACCESS);
        List list = worldObj.getEntitiesWithinAABB(EntityVehicleSVA.class, searchZone());
        for (Object o : list) {
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            if (!onPlatform(v)) {
                continue;
            }
            if (!fits(v)) {
                status |= S_NO_FIT;
                warnNoFit(v);
                continue;
            }
            if (!allows(v)) {
                status |= S_NO_ACCESS;
                continue;
            }
            served.add(v);
        }
        // by entity id: every station of a shared platform sees the vehicles in the same order (sharedPick)
        Collections.sort(served, new java.util.Comparator<EntityVehicleSVA>() {
            @Override
            public int compare(EntityVehicleSVA a, EntityVehicleSVA b) {
                return a.getEntityId() < b.getEntityId() ? -1 : a.getEntityId() == b.getEntityId() ? 0 : 1;
            }
        });
    }

    private void warnNoFit(EntityVehicleSVA v) {
        if (v.getEntityId() == lastWarned || !master) {          // one message per platform
            return;
        }
        lastWarned = v.getEntityId();
        if (v.riddenByEntity instanceof EntityPlayer) {
            int[] s = span();
            ((EntityPlayer) v.riddenByEntity).addChatComponentMessage(new ChatComponentTranslation("sva.station.noFit",
                    v.getVehicleType().platformSize.label(), Integer.valueOf(v.getVehicleType().platformSize.minPlates()),
                    s[0] + "x" + s[1], Integer.valueOf(plates.size())));
        }
    }

    private boolean valid(EntityVehicleSVA v) {
        return v != null && !v.isDead && onPlatform(v);
    }

    private boolean gasOn() {
        return hasModule(ItemStationModuleSVA.GAS) && (settings & SET_GAS) != 0;
    }

    private static boolean needsGas(EntityVehicleSVA v, TileEntityGasColumnSVA col) {
        if (col == null || !col.serves(v)) {
            return false;
        }
        for (Gas g : v.state.type.gases.gases()) {
            if (v.gasRoom(g) > 0 && col.amount(g) > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean needsRepair(EntityVehicleSVA v) {
        return hasModule(ItemStationModuleSVA.REPAIR) && ConfigSVA.wear && v.state.hp < v.state.maxHp();
    }

    private boolean needs(EntityVehicleSVA v, TileEntityGasColumnSVA col, boolean canRepair) {
        return v.energyRoom() >= 1 && (getEnergyStored() > 0 || !storages.isEmpty() || !caps.isEmpty()) || needsGas(v, col)
                || canRepair && needsRepair(v);
    }

    /**
     * One tick of charging `v`: the rate from the buffer, what the rate leaves from the linked storages, then every
     * supercapacitor's burst on top. @return EU moved
     */
    private int charge(EntityVehicleSVA v) {
        int rate = rateFor(bonusFor(v));
        long moved = VehicleMath.chargeTransfer(getEnergyStored(), rate, v.energyRoom());
        if (moved > 0) {
            removeEnergy((int) moved);
            v.addEnergy(moved);
        }
        if (!storages.isEmpty()) {
            int need = PlatformMath.pullNeed(rate, (int) moved, v.energyRoom());
            int got = need > 0 ? pullStorages(need) : 0;
            if (got > 0) {
                v.addEnergy(got);
                moved += got;
            }
        }
        if (!caps.isEmpty()) {
            for (TileEntitySupercapSVA c : supercaps()) {
                int got = c.dump(v.energyRoom());
                if (got > 0) {
                    int took = (int) Math.round(v.addEnergy(got));
                    if (took < got) {
                        c.refund(got - took);
                    }
                    moved += took;
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, moved);
    }

    /** «Форсаж» cooling: helium from the gas module's column, 1 mB per HELIUM_EVERY ticks. @return it flows this tick */
    private boolean cool() {
        if (heliumTicks > 0) {
            heliumTicks--;
            return true;
        }
        if (!hasModule(ItemStationModuleSVA.GAS)) {
            return false;
        }
        TileEntityGasColumnSVA c = column();
        if (c != null && c.takeGas(Gas.HELIUM, 1) > 0) {
            heliumTicks = PlatformMath.HELIUM_EVERY - 1;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        // IC2 caches the sink tier: refresh on a change of the transformers or of the universal one
        int tr = upgradeCount(UpgradeType.TRANSFORMER) + (acceptsAnyVoltage() ? 1000 : 0);
        if (tr != lastTransformers) {
            if (lastTransformers >= 0) {
                refreshEnergyNet();                         // IC2 caches the sink tier
            }
            lastTransformers = tr;
        }
        if (platformDirty || --platformTimer <= 0) {
            recomputePlatform();
            scan = 0;
        }
        if (--scan <= 0) {
            scan = SCAN_EVERY;
            scanVehicles();
        }
        TileEntityGasColumnSVA col = gasOn() ? column() : null;
        long worldTime = worldObj.getTotalWorldTime();
        // the vehicles that need something (served is in entity-id order): every station of a shared platform takes the
        // same one this tick (PlatformMath.sharedPick), so their rates add up on it; else the first one there (the screen)
        current = null;
        int n = served.size();
        // a hull with no steel to mend it doesn't hold the turn
        boolean canRepair = n > 0 && hasModule(ItemStationModuleSVA.REPAIR) && ConfigSVA.wear && (repairPaid > 0 || steelAvailable() > 0);
        List<EntityVehicleSVA> needing = new ArrayList<EntityVehicleSVA>(n);
        for (EntityVehicleSVA v : served) {
            if (valid(v) && needs(v, col, canRepair)) {
                needing.add(v);
            }
        }
        if (!needing.isEmpty()) {
            current = needing.get(PlatformMath.sharedPick(worldTime, needing.size()));
        }
        // full = every vehicle there is charged, whatever else it still gets (gas, repair)
        boolean allFull = true, any = false;
        boolean feeding = switchedOn();
        for (EntityVehicleSVA v : served) {
            if (valid(v)) {
                any = true;
                if (col != null && feeding && col.serves(v)) {
                    TileEntityGasColumnSVA.stationFeeds(v, worldTime);    // ТБ-9: its gas comes from here, the column skips it
                }
                if (current == null) {
                    current = v;
                }
                allFull &= v.energyRoom() < 1;
            }
        }
        allFull &= any;
        int moved = 0, gas = 0;
        status &= S_NO_FIT | S_NO_ACCESS;
        // vehicles.stationsAddUp off: of several stations on one platform only the master charges
        boolean standBy = !ConfigSVA.stationsAddUp && !master && !peers.isEmpty();
        boolean heating = false;
        if (current != null) {
            status |= S_VEHICLE;
            if (!switchedOn()) {
                status |= S_OFF;
            } else if (overheated) {
                status |= S_OVERHEAT;                               // «Форсаж»: paused until it is cold again
            } else {
                if (!standBy) {
                    moved = charge(current);
                }
                if (moved > 0) {
                    status |= S_CHARGING;
                    heating = boostOn();
                } else if (!standBy && current.energyRoom() >= 1) {
                    status |= S_NO_ENERGY;
                }
                if (col != null && col.serves(current)) {
                    for (Gas g : current.state.type.gases.gases()) {
                        gas += col.refuel(current, g, PlatformMath.GAS_RATE);
                    }
                    if (gas > 0) {
                        status |= S_REFUEL;
                    }
                }
            }
            if (moved > 0 || gas > 0) {
                current.markCharging();
            }
        }
        // «Форсаж»: +1 heat a tick of boosted charging unless helium flows, cooling otherwise; overheated until cold
        boolean helium = heating && cool();
        heat = PlatformMath.heatNext(heat, heating, helium);
        boolean wasOver = overheated;
        overheated = PlatformMath.overheatNext(overheated, heat);
        if (overheated != wasOver) {
            markDirty();
            if (overheated) {
                worldObj.playSoundEffect(xCoord + 0.5, yCoord + 1, zCoord + 0.5, "random.fizz", 0.6F, 0.8F);
            }
        }
        if (boostOn()) {
            status |= S_BOOST;
        }
        if (helium) {
            status |= S_HELIUM;
        }
        if (overheated) {
            status |= S_OVERHEAT;
        }
        if (!plates.isEmpty()) {
            status |= S_PLATFORM;
        }
        if (repairTick()) {
            status |= S_REPAIR;
        }
        if (hasModule(ItemStationModuleSVA.REPAIR) && !ConfigSVA.wear) {
            status |= S_WEAR_OFF;
        }
        // full: the chime once, the redstone level while it lasts
        boolean wasFull = full;
        full = allFull;
        if (full) {
            status |= S_FULL;
        }
        if (full && !wasFull && (settings & SET_SOUND) != 0 && master) {      // one chime per platform
            worldObj.playSoundEffect(xCoord + 0.5, yCoord + 1, zCoord + 0.5, com.sva.entity.EntityVehicleSVA.soundName("ding"), 0.8F,
                    com.sva.ConfigSVA.sounds ? 1F : 1.6F);           // ★1: the charge-done chime
        }
        int power = PlatformMath.fullSignal((settings & SET_REDSTONE) != 0, full);
        if (power != lastPower) {
            lastPower = power;
            notifyRedstone();
        }
        int cmp = current == null ? 0 : PlatformMath.comparator(true, current.state.energy / Math.max(1.0, current.state.maxEnergy()));
        if (cmp != lastComparator) {
            lastComparator = cmp;
            worldObj.func_147453_f(xCoord, yCoord, zCoord, getBlockType());
        }
        // measured rates
        euWindow += moved;
        gasWindow += gas;
        if (++windowTicks >= 20) {
            measuredEu = (int) (euWindow / windowTicks);
            measuredGas = gasWindow;
            euWindow = 0;
            gasWindow = 0;
            windowTicks = 0;
        }
        sinceCharge = moved > 0 || gas > 0 ? 0 : Math.min(1000, sinceCharge + 1);
        boolean now = sinceCharge < ACTIVE_HOLD;
        int vis = now ? PlateStatesSVA.CHARGING : full ? PlateStatesSVA.FULL : PlateStatesSVA.OFF;
        if (master && !peers.isEmpty()) {
            // the master shows the whole platform: charging while any of its stations charges
            for (TileEntityChargerSVA p : peerStations()) {
                if (p.isActive()) {
                    vis = PlateStatesSVA.CHARGING;
                } else if (p.isFull() && vis == PlateStatesSVA.OFF) {
                    vis = PlateStatesSVA.FULL;
                }
            }
        }
        int hash = plates.hashCode() * 31 + facing + (master ? 1 << 20 : 0);
        if (now != active || vis != visual || hash != sentHash) {
            active = now;
            visual = vis;
            sentHash = hash;
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /** Every REPAIR_EVERY ticks: one step for the first vehicle there that needs one, paid by a steel ingot per 10 steps. */
    private boolean repairTick() {
        if (!hasModule(ItemStationModuleSVA.REPAIR) || !ConfigSVA.wear || ++repairTimer < PlatformMath.REPAIR_EVERY) {
            return false;
        }
        repairTimer = 0;
        EntityVehicleSVA target = current != null && valid(current) && needsRepair(current) ? current : null;
        for (int i = 0; target == null && i < served.size(); i++) {
            EntityVehicleSVA v = served.get(i);
            if (valid(v) && needsRepair(v)) {
                target = v;
            }
        }
        if (target == null) {
            return false;
        }
        if (PlatformMath.ingotsFor(repairPaid) > 0) {
            if (!takeSteel()) {
                status |= S_NO_STEEL;
                return false;
            }
            repairPaid += PlatformMath.STEPS_PER_INGOT;
        }
        float max = target.state.maxHp();
        target.repairHull(PlatformMath.repairStep(target.state.hp, max) - target.state.hp);
        repairPaid--;
        markDirty();
        return true;
    }

    private boolean takeSteel() {
        for (ChunkCoordinates c : chests) {
            TileEntity te = worldObj.getTileEntity(c.posX, c.posY, c.posZ);
            if (!(te instanceof IInventory) || te.isInvalid()) {
                continue;
            }
            IInventory inv = (IInventory) te;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                if (ModContentSVA.isSteelIngot(inv.getStackInSlot(i))) {
                    inv.decrStackSize(i, 1);
                    inv.markDirty();
                    return true;
                }
            }
        }
        return false;
    }

    private void notifyRedstone() {
        Block b = getBlockType();
        worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, b);
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            worldObj.notifyBlocksOfNeighborChange(xCoord + o[0], yCoord + o[1], zCoord + o[2], b);
        }
    }

    /** World test / commands: fill the buffer. */
    public void fillForTest(int eu) {
        addEnergy(eu);
    }

    /** World test: set the boost heat (overheat without waiting 2 minutes). */
    public void setHeatForTest(int h) {
        heat = Math.max(0, Math.min(PlatformMath.HEAT_MAX, h));
    }

    /** World test: re-scan the platform and the vehicles now. */
    public void rescanForTest() {
        recomputePlatform();
        scanVehicles();
        scan = SCAN_EVERY;
    }

    // ------------------------------------------------------------------ ТБ-1: the item keeps the charge, the settings and the paid repair

    private static final String K_KEPT_SETTINGS = "ChargerSettings", K_KEPT_PAID = "RepairPaid";

    @Override
    public void writeKept(NBTTagCompound t) {
        if (getEnergyStored() > 0) {
            t.setInteger(KeptDataSVA.K_ENERGY, getEnergyStored());
        }
        if (settings != SET_DEFAULT) {
            t.setInteger(K_KEPT_SETTINGS, settings);
        }
        if (repairPaid > 0) {
            t.setInteger(K_KEPT_PAID, repairPaid);
        }
    }

    @Override
    public void readKept(NBTTagCompound t) {
        if (t.hasKey(K_KEPT_SETTINGS)) {
            settings = t.getInteger(K_KEPT_SETTINGS) & SET_ALL;
        }
        repairPaid = Math.max(0, Math.min(PlatformMath.STEPS_PER_INGOT, t.getInteger(K_KEPT_PAID)));
        restoreEnergy(KeptDataSVA.energy(t));               // over the bare buffer until the storage upgrades are back in
    }

    // ------------------------------------------------------------------ NBT / sync

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        active = nbt.getBoolean(K_ACTIVE);
        setFacing(nbt.hasKey(K_FACING) ? nbt.getInteger(K_FACING) : 3);
        ownerId = nbt.getString(K_OWNER);
        ownerName = nbt.getString(K_OWNER_NAME);
        settings = nbt.hasKey(K_SETTINGS) ? nbt.getInteger(K_SETTINGS) & SET_ALL : SET_DEFAULT;
        heat = Math.max(0, Math.min(PlatformMath.HEAT_MAX, nbt.getInteger(K_HEAT)));
        overheated = nbt.getBoolean(K_OVERHEAT);
        repairPaid = Math.max(0, Math.min(PlatformMath.STEPS_PER_INGOT, nbt.getInteger(K_PAID)));
        full = nbt.getBoolean(K_FULL);
        for (int i = 0; i < SIZE; i++) {
            slots[i] = null;
        }
        NBTTagList list = nbt.getTagList(K_ITEMS, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound s = list.getCompoundTagAt(i);
            int slot = s.getByte("Slot") & 0xFF;
            if (slot < SIZE) {
                slots[slot] = ItemStack.loadItemStackFromNBT(s);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean(K_ACTIVE, active);
        nbt.setInteger(K_FACING, facing);
        nbt.setString(K_OWNER, ownerId);
        nbt.setString(K_OWNER_NAME, ownerName);
        nbt.setInteger(K_SETTINGS, settings);
        nbt.setInteger(K_PAID, repairPaid);
        nbt.setBoolean(K_FULL, full);
        nbt.setInteger(K_HEAT, heat);
        nbt.setBoolean(K_OVERHEAT, overheated);
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < SIZE; i++) {
            if (slots[i] != null) {
                NBTTagCompound s = new NBTTagCompound();
                s.setByte("Slot", (byte) i);
                slots[i].writeToNBT(s);
                list.appendTag(s);
            }
        }
        nbt.setTag(K_ITEMS, list);
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        t.setBoolean(K_ACTIVE, active);
        t.setInteger(D_FACING, facing);
        t.setInteger(D_VISUAL, Math.max(0, visual));
        int[] p = new int[plates.size()];
        int i = 0;
        for (Long k : plates) {
            int dx = PlatformMath.keyX(k.longValue()) - xCoord, dz = PlatformMath.keyZ(k.longValue()) - zCoord;
            p[i++] = (dx & 0xFFFF) << 16 | dz & 0xFFFF;
        }
        t.setIntArray(D_PLATES, p);
        t.setBoolean(D_MASTER, master);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound t = pkt.func_148857_g();
        boolean was = active;
        int wasFacing = facing;
        active = t.getBoolean(K_ACTIVE);
        setFacing(t.getInteger(D_FACING));
        int[] old = clientPlates;
        int oldVisual = clientVisual;
        boolean wasMaster = clientMaster;
        clientPlates = t.getIntArray(D_PLATES);
        clientVisual = t.getInteger(D_VISUAL);
        clientMaster = !t.hasKey(D_MASTER) || t.getBoolean(D_MASTER);
        if (worldObj == null) {
            return;
        }
        if (was != active || wasFacing != facing) {
            worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
        }
        // only the platform's master paints the plates (two stations on one platform never fight over them); one that
        // stops being the master leaves them to the new one
        if (oldVisual != clientVisual || !java.util.Arrays.equals(old, clientPlates) || wasMaster != clientMaster) {
            if (wasMaster && clientMaster) {
                applyClientPlates(old, PlateStatesSVA.OFF);
            }
            if (clientMaster) {
                applyClientPlates(clientPlates, clientVisual);
            }
        }
    }

    /** Writes the plates' look into the client cache and re-renders the area they cover. */
    private void applyClientPlates(int[] packed, int state) {
        if (packed == null || packed.length == 0) {
            return;
        }
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int p : packed) {
            int x = xCoord + (short) (p >> 16), z = zCoord + (short) p;
            PlateStatesSVA.put(x, yCoord, z, state);
            x0 = Math.min(x0, x);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            z1 = Math.max(z1, z);
        }
        worldObj.markBlockRangeForRenderUpdate(x0, yCoord, z0, x1, yCoord, z1);
    }

    @Override
    public void invalidate() {
        if (worldObj != null && worldObj.isRemote && clientMaster) {
            applyClientPlates(clientPlates, PlateStatesSVA.OFF);
        }
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (worldObj != null && worldObj.isRemote && clientMaster) {
            for (int p : clientPlates) {
                PlateStatesSVA.put(xCoord + (short) (p >> 16), yCoord, zCoord + (short) p, PlateStatesSVA.OFF);
            }
        }
        super.onChunkUnload();
    }

    // ------------------------------------------------------------------ IInventory: 4 upgrades + 3 modules

    @Override
    public int getSizeInventory() {
        return SIZE;
    }

    @Override
    public ItemStack getStackInSlot(int i) {
        return i >= 0 && i < SIZE ? slots[i] : null;
    }

    @Override
    public ItemStack decrStackSize(int i, int n) {
        ItemStack s = getStackInSlot(i);
        if (s == null) {
            return null;
        }
        ItemStack out;
        if (s.stackSize <= n) {
            out = s;
            slots[i] = null;
        } else {
            out = s.splitStack(n);
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
        if (i < 0 || i >= SIZE) {
            return;
        }
        slots[i] = s;
        if (s != null && s.stackSize > getInventoryStackLimit()) {
            s.stackSize = getInventoryStackLimit();
        }
        markDirty();
    }

    @Override
    public String getInventoryName() {
        return BlockChargerSVA.nameKey(getTier());
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
    public boolean isUseableByPlayer(EntityPlayer p) {
        return worldObj.getTileEntity(xCoord, yCoord, zCoord) == this && p.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= 64;
    }

    @Override
    public void openInventory() {
    }

    @Override
    public void closeInventory() {
    }

    @Override
    public boolean isItemValidForSlot(int i, ItemStack s) {
        if (i >= FIRST_UPGRADE && i < FIRST_UPGRADE + UPGRADE_SLOTS) {
            return upgradeAccepted(s);
        }
        return i >= FIRST_MODULE && i < SIZE && moduleAccepted(s);
    }

    // ---- ТБ-3: no slot for automation - upgrades and modules go in and out by hand only

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        return NO_SLOTS;
    }

    @Override
    public boolean canInsertItem(int slot, ItemStack s, int side) {
        return false;
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack s, int side) {
        return false;
    }
}
