package com.sva.block;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.sc.energy.Tier;
import com.sc.energy.TileEntityEnergyBase;
import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemTuningSVA;
import com.sva.platform.PlatformMath;
import com.sva.vehicle.AccessMath;
import com.sva.vehicle.GarageMath;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.MathHelper;

/**
 * Wave 2, ★7: «Ремонтный гараж» - an HV energy tile (Silicon Age / IC2 net, like the charging station) and the
 * controller of a garage floor: «Плиты гаража» (BlockGaragePlateSVA: hazard lift pads, lamp plates) on its level,
 * 4-way connected to it, up to 64 - the same flood fill and «does it fit» rule (VehicleType.platformSize) as the
 * charging platform. The first vehicle standing on the floor that fits is the garage's.
 * <ul>
 * <li>Repair (the screen's «Чинить всё» / «Только корпус», GarageMath): the hull to full (and the engine cooled for
 * «всё»); the steel ingots (or iron x2) come out of the two material slots when it starts, the EU over the job from
 * the buffer. Needs the vehicle's «Ремонт» right (AccessMath; the owner always). Comparator: the job's progress.</li>
 * <li>Tuning (★8): the screen's four tuning slots ARE the vehicle's upgrade slots (TuningInv): an upgrade put in is
 * installed (TuningMath rules), one taken out is removed - only the owner / an op (EntityVehicleSVA.canManage).</li>
 * </ul>
 * The client learns the vehicle, the job and its progress from the description packet (sent on change, the progress in
 * 5 % steps): the screen, the lamp front and the hologram ring (RenderGarageSVA).
 */
public class TileEntityGarageSVA extends TileEntityEnergyBase implements IInventory {

    public static final int SCAN_EVERY = 20, PLATFORM_EVERY = 100;
    public static final int MATERIAL_SLOTS = 2, SIZE = MATERIAL_SLOTS;
    /** Status bits (screen, tests). */
    public static final int S_VEHICLE = 1, S_REPAIR = 2, S_DONE = 4, S_NO_ENERGY = 8, S_NO_FIT = 16, S_PLATFORM = 32, S_OFF = 64;
    /** startRepair results. */
    public static final int R_OK = 0, R_NO_VEHICLE = 1, R_BUSY = 2, R_NOTHING = 3, R_NO_RIGHT = 4, R_NO_STEEL = 5;

    private static final String K_ITEMS = "GarageItems", K_FACING = "GarageFacing", K_OWNER = "Owner", K_OWNER_NAME = "OwnerName",
            K_JOB_VEH = "JobVeh", K_JOB_MODE = "JobMode", K_JOB_EU = "JobEu", K_JOB_PAID = "JobPaid";
    private static final String D_FACING = "Fc", D_VEH = "Vh", D_JOB = "Jb", D_PROG = "Pg", D_DONE = "Dn";

    private final ItemStack[] slots = new ItemStack[SIZE];
    private int facing = 3;
    private String ownerId = "", ownerName = "";
    // ---- server: the floor and the vehicle
    private Set<Long> plates = Collections.emptySet();
    private int[] bounds;
    private boolean platformDirty = true, floorPartial;
    private int platformTimer, scan;
    private EntityVehicleSVA current;
    private int status, lastComparator = -1, doneTicks;
    // ---- server: the repair job (the vehicle's id, the mode, its EU and what is paid)
    private String jobVeh;
    private int jobMode;
    private long jobEu, jobPaid;
    private int sentVeh = Integer.MIN_VALUE, sentProg = -1, sentFacing = -1;
    private boolean sentJob, sentDone;
    // ---- client
    private int clientVeh = -1, clientProg;
    private boolean clientJob, clientDone;
    /** The tuning slots as an inventory (both sides: the server's view of the vehicle, the client's mirror). */
    public final TuningInv tuning = new TuningInv();

    public TileEntityGarageSVA() {
        super(Tier.HV);
    }

    @Override
    public int getMaxEnergyStored() {
        return GarageMath.BUFFER;
    }

    // ------------------------------------------------------------------ state

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

    public String getOwnerName() {
        return ownerName;
    }

    public int getStatus() {
        return status;
    }

    public EntityVehicleSVA getCurrent() {
        return current;
    }

    public int plateCount() {
        return plates.size();
    }

    public Set<Long> getPlates() {
        return Collections.unmodifiableSet(plates);
    }

    public boolean isRepairing() {
        return jobVeh != null;
    }

    public int getJobMode() {
        return jobMode;
    }

    /** 0..1 of the running job (0 idle). */
    public double progress() {
        return jobVeh == null || jobEu <= 0 ? 0 : Math.min(1, jobPaid / (double) jobEu);
    }

    public int comparatorLevel() {
        return Math.max(0, lastComparator);
    }

    /** Client: the garage's vehicle (entity id, -1 none), a job running, its progress 0..100, just finished. */
    public int getClientVehicle() {
        return clientVeh;
    }

    public boolean isClientRepairing() {
        return clientJob;
    }

    public int getClientProgress() {
        return clientProg;
    }

    public boolean isClientDone() {
        return clientDone;
    }

    /** Lit front: a vehicle in, a job running or just done (client: from the description packet). */
    public boolean isLit() {
        return worldObj != null && worldObj.isRemote ? clientVeh >= 0 : current != null;
    }

    /** Steel / iron ingots in the material slots. */
    public int[] materials() {
        int steel = 0, iron = 0;
        for (ItemStack s : slots) {
            if (ModContentSVA.isSteelIngot(s)) {
                steel += s.stackSize;
            } else if (isIron(s)) {
                iron += s.stackSize;
            }
        }
        return new int[] {steel, iron};
    }

    static boolean isIron(ItemStack s) {
        return s != null && s.getItem() == Items.iron_ingot;
    }

    public static boolean materialAccepted(ItemStack s) {
        return ModContentSVA.isSteelIngot(s) || isIron(s);
    }

    /** The repair price for the current vehicle in a mode: {EU, steel ingots}; {0, 0} without one. */
    public long[] cost(int mode) {
        EntityVehicleSVA v = current;
        if (v == null) {
            return new long[] {0, 0};
        }
        return new long[] {GarageMath.euCost(v.state.hp, v.state.maxHp(), v.state.heat, mode), GarageMath.steelCost(v.state.hp, v.state.maxHp())};
    }

    // ------------------------------------------------------------------ the floor

    public void platformChanged() {
        platformDirty = true;
    }

    private void recomputePlatform() {
        platformDirty = false;
        platformTimer = PLATFORM_EVERY;
        final Block plate = ModContentSVA.garagePlate;
        final int y = yCoord;
        floorPartial = false;
        plates = PlatformMath.floodFill(new PlatformMath.Plates() {
            @Override
            public boolean isPlate(int x, int z) {
                if (!worldObj.blockExists(x, y, z)) {
                    floorPartial = true;                        // a chunk of the floor isn't loaded (yet)
                    return false;
                }
                return worldObj.getBlock(x, y, z) == plate;
            }
        }, xCoord, zCoord, PlatformMath.MAX_PLATES);
        bounds = PlatformMath.bounds(plates);
    }

    /** {spanX, spanZ} of the floor. */
    public int[] span() {
        return bounds == null ? new int[] {0, 0} : new int[] {bounds[2] - bounds[0] + 1, bounds[3] - bounds[1] + 1};
    }

    /** The vehicle's centre over a floor plate, at most 4.5 blocks above it. */
    public boolean onFloor(EntityVehicleSVA v) {
        if (v == null || v.isDead || v.worldObj != worldObj || plates.isEmpty()) {
            return false;
        }
        double dy = v.posY - (yCoord + 1);
        if (dy < -0.3 || dy > 4.5) {
            return false;
        }
        return plates.contains(Long.valueOf(PlatformMath.key(MathHelper.floor_double(v.posX), MathHelper.floor_double(v.posZ))));
    }

    public boolean fits(EntityVehicleSVA v) {
        int[] s = span();
        return PlatformMath.fits(v.getVehicleType().platformSize, plates.size(), s[0], s[1]);
    }

    private void scanVehicle() {
        status &= ~S_NO_FIT;
        current = null;
        if (bounds == null) {
            return;
        }
        AxisAlignedBB zone = AxisAlignedBB.getBoundingBox(bounds[0] - 0.5, yCoord + 0.5, bounds[1] - 0.5, bounds[2] + 1.5, yCoord + 6,
                bounds[3] + 1.5);
        List list = worldObj.getEntitiesWithinAABB(EntityVehicleSVA.class, zone);
        for (Object o : list) {
            EntityVehicleSVA v = (EntityVehicleSVA) o;
            if (!onFloor(v)) {
                continue;
            }
            if (!fits(v)) {
                status |= S_NO_FIT;
                continue;
            }
            if (current == null || jobVeh != null && jobVeh.equals(v.state.vehId)) {
                current = v;                                    // the one being repaired keeps its place
            }
        }
    }

    // ------------------------------------------------------------------ repair

    /** The screen's button (server): starts a job on the garage's vehicle. @return R_* */
    public int startRepair(EntityPlayer p, int mode) {
        int r = repairCheck(p, mode);
        if (r == R_OK) {
            EntityVehicleSVA v = current;
            int[] pay = GarageMath.pay(GarageMath.steelCost(v.state.hp, v.state.maxHp()), materials()[0], materials()[1]);
            take(true, pay[0]);
            take(false, pay[1]);
            jobVeh = v.state.vehId;
            jobMode = mode == GarageMath.HULL ? GarageMath.HULL : GarageMath.ALL;
            jobEu = GarageMath.euCost(v.state.hp, v.state.maxHp(), v.state.heat, jobMode);
            jobPaid = 0;
            doneTicks = 0;
            markDirty();
            if (p != null) {
                p.addChatComponentMessage(new ChatComponentTranslation("sva.garage.started",
                        com.sva.FormatSVA.group(jobEu), Integer.valueOf(pay[0]), Integer.valueOf(pay[1])));
            }
        } else if (p != null) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.garage.refused." + r,
                    current == null || current.state.ownerName == null ? "" : current.state.ownerName));
        }
        return r;
    }

    /** May `p` start a repair in this mode now? (R_*) */
    public int repairCheck(EntityPlayer p, int mode) {
        EntityVehicleSVA v = current;
        if (v == null || !onFloor(v)) {
            return R_NO_VEHICLE;
        }
        if (jobVeh != null) {
            return R_BUSY;
        }
        if (!GarageMath.needed(v.state.hp, v.state.maxHp(), v.state.heat, mode)) {
            return R_NOTHING;
        }
        if (p != null && !v.hasRight(p, AccessMath.REPAIR)) {
            return R_NO_RIGHT;
        }
        int[] m = materials();
        return GarageMath.pay(GarageMath.steelCost(v.state.hp, v.state.maxHp()), m[0], m[1]) == null ? R_NO_STEEL : R_OK;
    }

    private void take(boolean steel, int n) {
        for (int i = 0; i < SIZE && n > 0; i++) {
            ItemStack s = slots[i];
            if (steel ? ModContentSVA.isSteelIngot(s) : isIron(s)) {
                int k = Math.min(n, s.stackSize);
                decrStackSize(i, k);
                n -= k;
            }
        }
    }

    private void jobTick() {
        if (jobVeh == null) {
            return;
        }
        EntityVehicleSVA v = current;
        if (v == null || !jobVeh.equals(v.state.vehId) || !onFloor(v)) {
            if (floorPartial) {
                status |= S_REPAIR;                             // part of the floor isn't loaded: wait, don't cancel
                return;
            }
            jobVeh = null;                                      // driven away: the job is off (the metal is spent)
            markDirty();
            return;
        }
        status |= S_REPAIR;
        if (!switchedOn()) {
            status |= S_OFF;
            return;
        }
        long need = Math.min(GarageMath.perTick(jobEu), jobEu - jobPaid);
        int took = (int) Math.min(need, getEnergyStored());
        if (took > 0) {
            removeEnergy(took);
            jobPaid += took;
        } else if (need > 0) {
            status |= S_NO_ENERGY;
        }
        if (jobPaid >= jobEu) {
            v.garageRepaired(jobMode == GarageMath.ALL);
            jobVeh = null;
            doneTicks = 60;
            worldObj.playSoundEffect(xCoord + 0.5, yCoord + 1, zCoord + 0.5, EntityVehicleSVA.soundName("ding"), 0.8F,
                    ConfigSVA.sounds ? 1.2F : 1.6F);
            if (v.riddenByEntity instanceof EntityPlayer) {
                ((EntityPlayer) v.riddenByEntity).addChatComponentMessage(new ChatComponentTranslation("sva.garage.done"));
            }
            markDirty();
        }
    }

    // ------------------------------------------------------------------ tuning (★8)

    /** May `p` change the garage vehicle's upgrades (the owner / an op)? Server: canManage; client: by the synced owner name. */
    public boolean mayTune(EntityPlayer p) {
        EntityVehicleSVA v = vehicleAny();
        if (v == null || p == null) {
            return false;
        }
        if (worldObj.isRemote) {
            String o = v.getOwnerName();
            return o.length() == 0 || o.equalsIgnoreCase(p.getCommandSenderName()) || p.capabilities.isCreativeMode;
        }
        return v.canManage(p);
    }

    /** The garage's vehicle on either side (client: by the synced entity id). */
    public EntityVehicleSVA vehicleAny() {
        if (worldObj == null) {
            return null;
        }
        if (!worldObj.isRemote) {
            return current;
        }
        Entity e = clientVeh < 0 ? null : worldObj.getEntityByID(clientVeh);
        return e instanceof EntityVehicleSVA && !e.isDead ? (EntityVehicleSVA) e : null;
    }

    /** May `kind` go into tuning slot `i` of the garage's vehicle now (both sides, no player check)? */
    public boolean canInstall(int i, int kind) {
        EntityVehicleSVA v = vehicleAny();
        if (v == null || i < 0 || i >= TuningMath.MAX_SLOTS || !ConfigSVA.tuning && !worldObj.isRemote) {
            return false;
        }
        int[] t = v.getTuning();
        return !TuningMath.valid(t[i]) && TuningMath.canInstall(v.getVehicleType(), t, kind) == TuningMath.OK;
    }

    /** The 4 tuning slots: the server reads / writes the garage vehicle's VehicleState.tuning; the client mirrors. */
    public class TuningInv implements IInventory {
        private final ItemStack[] mirror = new ItemStack[TuningMath.MAX_SLOTS];

        private boolean client() {
            return worldObj == null || worldObj.isRemote;
        }

        @Override
        public int getSizeInventory() {
            return TuningMath.MAX_SLOTS;
        }

        @Override
        public ItemStack getStackInSlot(int i) {
            if (i < 0 || i >= TuningMath.MAX_SLOTS) {
                return null;
            }
            if (client()) {
                return mirror[i];
            }
            if (current == null || ModContentSVA.tuning == null) {
                return null;
            }
            int k = current.state.tuning[i];
            return TuningMath.valid(k) ? ModContentSVA.tuning.stackOf(k) : null;
        }

        @Override
        public ItemStack decrStackSize(int i, int n) {
            ItemStack s = getStackInSlot(i);
            if (s == null || n <= 0) {
                return null;
            }
            setInventorySlotContents(i, null);
            return s;
        }

        @Override
        public ItemStack getStackInSlotOnClosing(int i) {
            return null;
        }

        @Override
        public void setInventorySlotContents(int i, ItemStack s) {
            if (i < 0 || i >= TuningMath.MAX_SLOTS) {
                return;
            }
            if (client()) {
                mirror[i] = s;
                return;
            }
            if (current == null) {
                drop(s);
                return;
            }
            int[] t = current.state.tuning.clone();
            if (s == null) {
                t[i] = -1;
            } else {
                int k = ItemTuningSVA.kind(s);
                if (k < 0 || TuningMath.valid(t[i]) || TuningMath.canInstall(current.state.type, t, k) != TuningMath.OK) {
                    drop(s);                                    // never lose an item the slot refused late
                    return;
                }
                t[i] = k;
            }
            current.setTuning(t);
        }

        private void drop(ItemStack s) {
            if (s != null && worldObj != null && !worldObj.isRemote) {
                worldObj.spawnEntityInWorld(new EntityItem(worldObj, xCoord + 0.5, yCoord + 1.2, zCoord + 0.5, s.copy()));
            }
        }

        @Override
        public String getInventoryName() {
            return "sva.garage.tuning";
        }

        @Override
        public boolean hasCustomInventoryName() {
            return false;
        }

        @Override
        public int getInventoryStackLimit() {
            return 1;
        }

        @Override
        public void markDirty() {
        }

        @Override
        public boolean isUseableByPlayer(EntityPlayer p) {
            return TileEntityGarageSVA.this.isUseableByPlayer(p);
        }

        @Override
        public void openInventory() {
        }

        @Override
        public void closeInventory() {
        }

        @Override
        public boolean isItemValidForSlot(int i, ItemStack s) {
            int k = ItemTuningSVA.kind(s);
            return k >= 0 && canInstall(i, k);
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        if (platformDirty || --platformTimer <= 0) {
            recomputePlatform();
            scan = 0;
        }
        if (--scan <= 0) {
            scan = SCAN_EVERY;
            scanVehicle();
        }
        if (current != null && !onFloor(current)) {
            current = null;
        }
        status &= S_NO_FIT;
        if (!plates.isEmpty()) {
            status |= S_PLATFORM;
        }
        if (current != null) {
            status |= S_VEHICLE;
        }
        jobTick();
        if (doneTicks > 0) {
            doneTicks--;
            status |= S_DONE;
        }
        int cmp = GarageMath.comparator(jobVeh != null || doneTicks > 0, jobVeh != null ? progress() : 1);
        if (cmp != lastComparator) {
            lastComparator = cmp;
            worldObj.func_147453_f(xCoord, yCoord, zCoord, getBlockType());
        }
        int veh = current == null ? -1 : current.getEntityId();
        int prog = (int) Math.floor(progress() * 20) * 5;
        boolean job = jobVeh != null, done = doneTicks > 0;
        if (veh != sentVeh || prog != sentProg || job != sentJob || done != sentDone || facing != sentFacing) {
            if ((veh >= 0) != (sentVeh >= 0)) {
                worldObj.func_147451_t(xCoord, yCoord, zCoord);    // isLit changed: the light level 7 / 0
            }
            sentVeh = veh;
            sentProg = prog;
            sentJob = job;
            sentDone = done;
            sentFacing = facing;
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /** World test: fill the buffer / re-scan now. */
    public void fillForTest(int eu) {
        addEnergy(eu);
    }

    public void rescanForTest() {
        recomputePlatform();
        scanVehicle();
        scan = SCAN_EVERY;
    }

    // ------------------------------------------------------------------ NBT / sync

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        setFacing(nbt.hasKey(K_FACING) ? nbt.getInteger(K_FACING) : 3);
        ownerId = nbt.getString(K_OWNER);
        ownerName = nbt.getString(K_OWNER_NAME);
        String jv = nbt.getString(K_JOB_VEH);
        jobVeh = jv.length() > 0 ? jv : null;
        jobMode = nbt.getInteger(K_JOB_MODE) == GarageMath.HULL ? GarageMath.HULL : GarageMath.ALL;
        jobEu = Math.max(0, nbt.getLong(K_JOB_EU));
        jobPaid = Math.max(0, Math.min(jobEu, nbt.getLong(K_JOB_PAID)));
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
        nbt.setInteger(K_FACING, facing);
        nbt.setString(K_OWNER, ownerId);
        nbt.setString(K_OWNER_NAME, ownerName);
        nbt.setString(K_JOB_VEH, jobVeh == null ? "" : jobVeh);
        nbt.setInteger(K_JOB_MODE, jobMode);
        nbt.setLong(K_JOB_EU, jobEu);
        nbt.setLong(K_JOB_PAID, jobPaid);
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
        t.setInteger(D_FACING, facing);
        t.setInteger(D_VEH, current == null ? -1 : current.getEntityId());
        t.setBoolean(D_JOB, jobVeh != null);
        t.setInteger(D_PROG, (int) Math.round(progress() * 100));
        t.setBoolean(D_DONE, doneTicks > 0);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound t = pkt.func_148857_g();
        boolean wasLit = clientVeh >= 0;
        int wasFacing = facing;
        setFacing(t.getInteger(D_FACING));
        clientVeh = t.getInteger(D_VEH);
        clientJob = t.getBoolean(D_JOB);
        clientProg = t.getInteger(D_PROG);
        clientDone = t.getBoolean(D_DONE);
        if (worldObj != null && (wasLit != (clientVeh >= 0) || wasFacing != facing)) {
            if (wasLit != (clientVeh >= 0)) {
                worldObj.func_147451_t(xCoord, yCoord, zCoord);
            }
            worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
        }
    }

    /** The hologram ring is drawn over the vehicle, outside the block. */
    @Override
    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return INFINITE_EXTENT_AABB;
    }

    // ------------------------------------------------------------------ IInventory: 2 material slots

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
        return "tile.siliconagevehicles.garage.name";
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
        return i >= 0 && i < SIZE && materialAccepted(s);
    }

    /** VehicleType of the garage vehicle on either side (null without one). */
    public VehicleType vehicleType() {
        EntityVehicleSVA v = vehicleAny();
        return v == null ? null : v.getVehicleType();
    }
}
