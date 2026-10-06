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
import com.sva.vehicle.VehicleMath;
import com.sva.workbench.WorkbenchMath;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
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
 * <li>Plates (BlockChargePlateSVA) on its level, 4-way connected to it, up to 64 (flood fill on a neighbour change,
 * a plate placed / broken nearby, and every 5 s): vehicles over a plate (or on the station) that fit the platform
 * (VehicleType.platformSize vs plate count + bounding rectangle) are served one per tick in turn (round robin).
 * Rate = PlatformMath.rate: input tier packet x 1.6 per Overclocker x (1 + 10% per plate over the vehicle's minimum).</li>
 * <li>Slots: 0-3 core upgrades (Overclocker, Transformer = +1 input tier, Energy Storage = +100k EU), 4-6 modules
 * (ItemStationModuleSVA: gas / repair / access).</li>
 * <li>Gas module: pulls each gas the vehicle has a tank for (25 mB/t) from a Gas Column touching the station or a plate
 * (the column's own owner rule). Repair module: +1% hull every 60 ticks, one core steel ingot per 10 steps, taken
 * from an inventory touching the station or a plate (only with wear on). Access module: only the station owner's vehicles.</li>
 * <li>Comparator: the served vehicle's charge; «сигнал при полном»: strong redstone 15 while the vehicle is full;
 * «звук»: one chime when it fills up.</li>
 * </ul>
 * The plates' look (off / charging / full) is sent with the description packet (plate offsets + state) only when
 * it changes; clients keep it in PlateStatesSVA.
 */
public class TileEntityChargerSVA extends TileEntityEnergyBase implements IInventory {

    public static final int SCAN_EVERY = 20, ACTIVE_HOLD = 10, PLATFORM_EVERY = 100;
    public static final int FIRST_UPGRADE = 0, UPGRADE_SLOTS = 4, FIRST_MODULE = 4, MODULE_SLOTS = 3, SIZE = 7;
    /** Status bits (screen, tests). */
    public static final int S_VEHICLE = 1, S_CHARGING = 2, S_REFUEL = 4, S_REPAIR = 8, S_FULL = 16, S_NO_FIT = 32, S_NO_ACCESS = 64,
            S_NO_ENERGY = 128, S_OFF = 256, S_NO_STEEL = 512, S_WEAR_OFF = 1024, S_PLATFORM = 2048;
    /** Settings bits. */
    public static final int SET_REDSTONE = 1, SET_SOUND = 2, SET_GAS = 4, SET_DEFAULT = SET_REDSTONE | SET_SOUND | SET_GAS;

    // NBT keys (append-only)
    private static final String K_ACTIVE = "ChargerActive", K_ITEMS = "ChargerItems", K_FACING = "ChargerFacing", K_OWNER = "Owner",
            K_OWNER_NAME = "OwnerName", K_SETTINGS = "ChargerSettings", K_PAID = "RepairPaid", K_FULL = "ChargerFull";
    // description packet keys
    private static final String D_PLATES = "Pl", D_VISUAL = "Vs", D_FACING = "Fc";

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
    // ---- server: vehicles
    private final List<EntityVehicleSVA> served = new ArrayList<EntityVehicleSVA>();
    private EntityVehicleSVA current;
    private int rr = -1, scan;
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
        return t == UpgradeType.OVERCLOCKER || t == UpgradeType.TRANSFORMER || t == UpgradeType.ENERGY_STORAGE;
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

    @Override
    public Tier inputTier() {
        return PlatformMath.inputTier(getTier(), upgradeCount(UpgradeType.TRANSFORMER));
    }

    @Override
    public int getMaxEnergyStored() {
        return PlatformMath.buffer(inputTier(), upgradeCount(UpgradeType.ENERGY_STORAGE));
    }

    /** EU a tick with no plate bonus (the lone charger, tooltips). */
    public int rate() {
        return rateFor(0);
    }

    public int rateFor(double plateBonus) {
        return PlatformMath.rate(inputTier(), upgradeCount(UpgradeType.OVERCLOCKER), plateBonus, ConfigSVA.chargerRate);
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

    /** May change the settings: the owner, an op, anyone when it has no owner. */
    public boolean mayConfigure(EntityPlayer p) {
        if (ownerId.length() == 0 || ownerId.equals(p.getUniqueID().toString())) {
            return true;
        }
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        return s != null && s.getConfigurationManager().func_152596_g(p.getGameProfile());
    }

    public int getSettings() {
        return settings;
    }

    public void toggleSetting(int bit) {
        if (bit == SET_REDSTONE || bit == SET_SOUND || bit == SET_GAS) {
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
                if (te instanceof TileEntityGasColumnSVA) {
                    columns.add(new ChunkCoordinates(x, yy, z));
                } else if (te instanceof IInventory && !(te instanceof TileEntityEnergyBase)) {
                    chests.add(new ChunkCoordinates(x, yy, z));
                }
            }
        }
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
        return !hasModule(ItemStationModuleSVA.ACCESS) || ownerId.length() == 0 || ownerId.equals(v.state.ownerId);
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
    }

    private void warnNoFit(EntityVehicleSVA v) {
        if (v.getEntityId() == lastWarned) {
            return;
        }
        lastWarned = v.getEntityId();
        if (v.riddenByEntity instanceof EntityPlayer) {
            int[] s = span();
            ((EntityPlayer) v.riddenByEntity).addChatComponentMessage(new ChatComponentTranslation("sva.station.noFit",
                    v.getVehicleType().platformSize.label(), Integer.valueOf(v.getVehicleType().platformSize.minPlates()),
                    s[0] + "×" + s[1], Integer.valueOf(plates.size())));
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
        return hasModule(ItemStationModuleSVA.REPAIR) && ConfigSVA.wear && v.state.hp < v.state.type.maxHp;
    }

    private boolean needs(EntityVehicleSVA v, TileEntityGasColumnSVA col) {
        return v.energyRoom() >= 1 && getEnergyStored() > 0 || needsGas(v, col) || needsRepair(v);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        int tr = upgradeCount(UpgradeType.TRANSFORMER);
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
        // round robin: the next vehicle that needs something; else the first one there (for the screen)
        current = null;
        int n = served.size();
        for (int i = 0; i < n; i++) {
            int idx = PlatformMath.nextIndex(rr + i, n);
            EntityVehicleSVA v = served.get(idx);
            if (valid(v) && needs(v, col)) {
                current = v;
                rr = idx;
                break;
            }
        }
        boolean allFull = false;
        if (current == null) {
            allFull = true;
            for (EntityVehicleSVA v : served) {
                if (valid(v)) {
                    if (current == null) {
                        current = v;
                    }
                    allFull &= v.energyRoom() < 1;
                }
            }
            allFull &= current != null;
        }
        int moved = 0, gas = 0;
        status &= S_NO_FIT | S_NO_ACCESS;
        if (current != null) {
            status |= S_VEHICLE;
            if (!switchedOn()) {
                status |= S_OFF;
            } else {
                moved = VehicleMath.chargeTransfer(getEnergyStored(), rateFor(bonusFor(current)), current.energyRoom());
                if (moved > 0) {
                    removeEnergy(moved);
                    current.addEnergy(moved);
                    status |= S_CHARGING;
                } else if (current.energyRoom() >= 1) {
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
        if (full && !wasFull && (settings & SET_SOUND) != 0) {
            worldObj.playSoundEffect(xCoord + 0.5, yCoord + 1, zCoord + 0.5, "note.pling", 0.8F, 1.6F);
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
        int hash = plates.hashCode() * 31 + facing;
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
        float max = target.state.type.maxHp;
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

    /** World test: re-scan the platform and the vehicles now. */
    public void rescanForTest() {
        recomputePlatform();
        scanVehicles();
        scan = SCAN_EVERY;
    }

    // ------------------------------------------------------------------ NBT / sync

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        active = nbt.getBoolean(K_ACTIVE);
        setFacing(nbt.hasKey(K_FACING) ? nbt.getInteger(K_FACING) : 3);
        ownerId = nbt.getString(K_OWNER);
        ownerName = nbt.getString(K_OWNER_NAME);
        settings = nbt.hasKey(K_SETTINGS) ? nbt.getInteger(K_SETTINGS) & SET_DEFAULT : SET_DEFAULT;
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
        clientPlates = t.getIntArray(D_PLATES);
        clientVisual = t.getInteger(D_VISUAL);
        if (worldObj == null) {
            return;
        }
        if (was != active || wasFacing != facing) {
            worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
        }
        if (oldVisual != clientVisual || !java.util.Arrays.equals(old, clientPlates)) {
            applyClientPlates(old, PlateStatesSVA.OFF);
            applyClientPlates(clientPlates, clientVisual);
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
        if (worldObj != null && worldObj.isRemote) {
            applyClientPlates(clientPlates, PlateStatesSVA.OFF);
        }
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (worldObj != null && worldObj.isRemote) {
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
        return "tile.siliconagevehicles.charger.name";
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
}
