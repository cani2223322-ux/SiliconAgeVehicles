package com.sva.block;

import java.util.ArrayList;
import java.util.List;

import com.sc.energy.Tier;
import com.sc.energy.TileEntityEnergyBase;
import com.sc.item.ItemUpgradeSC;
import com.sc.machine.UpgradeType;
import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.entity.EntityVehicleSVA;

import com.sva.airdefense.AdShapeMath;
import com.sva.airdefense.AdUpgradeMath;
import com.sva.net.NetADSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
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
 * Air-defence wave: what the radars, the launchers and the command post share - the owner and his access list (the
 * «friends» of the mock-ups: their vehicles count as own, they may open the screens), the power (an upkeep in EU a tick
 * from the grid; short of it the block is «без питания» and does nothing), the registration with AirDefenseSVA while
 * loaded and with AirDefenseDataSVA while placed (the per-player limits).
 *
 * The multiblock wave (2026-10-12): the block is the MASTER of a structure as big as the mock-ups draw it
 * (AdShapeMath: the footprint; BlockAdPartSVA fillers around it, built by BlockAirDefenseSVA.build). It keeps the
 * structure's facing and its state: MULTI (built), COMPACT (a single block of an older world with no room around it - it
 * works as before, drawn as its model scaled into the block) or LEGACY (an older world's single block not decided yet:
 * tryLegacy() converts it to the multiblock when its cells are loaded and free, once a second until it can decide). For
 * the client model it syncs (the description packet, only on a change) the facing, the state, the lamps (ST_*), the
 * target it turns to and the rounds to show; shots go as block events (EV_*). The animation angles live here too
 * (clientTick, client side only; AdShapeMath has the math).
 *
 * The upgrades wave (2026-10-12): four upgrade slots for the core's modules (AdUpgradeMath: which fit and what they do -
 * the universal transformer, transformers, storage, overclockers, economizers; overdrive on radars, the puller on
 * launchers). They live in an inventory of their own (upgrades) so no hopper or pipe reaches them, drop loose when the
 * structure goes, and count only with airdefense.upgrades on. A change of the input tier re-announces the master and
 * every part to IC2 (it caches the sink tier), as the charging station does. The equipment screen (ContainerAdSVA /
 * GuiAdEquipSVA; the launcher's own screen has the slots too) gets equipSnapshot() every 10 ticks while open.
 */
public abstract class TileEntityAirDefenseSVA extends TileEntityEnergyBase {

    public static final int MAX_ACCESS = 16;

    protected String ownerName = "", ownerId = "";
    protected final List<String> access = new ArrayList<String>();
    protected boolean powered;
    private boolean registered;

    /** Structure states (NBT "AdStruct"; missing = LEGACY: placed before the multiblocks). */
    public static final int LEGACY = 0, MULTI = 1, COMPACT = 2;
    /** Lamp bits (synced state): power, lock (захват), empty (пусто), alarm (тревога). */
    public static final int ST_POWER = 1, ST_LOCK = 2, ST_EMPTY = 4, ST_ALARM = 8;
    /** Block events: a burst (or a warning shot) of the gun, a missile launch. */
    public static final int EV_BURST = 1, EV_LAUNCH = 2;
    /** The way the front looks (AdShapeMath facing), the structure's state. */
    protected int facing, struct = LEGACY;
    /** Set while the structure is taken down (its parts must not take the master down again) / no item to drop (creative). */
    public boolean dismantling, noDrop;
    private int legacyTimer, syncTimer, syncState = -1, syncTarget = -2, syncAmmo = -1;
    /** Synced to the client: the lamps, the entity to turn to (-1 none), the rounds to show. */
    protected int cState, cTarget = -1, cAmmo;

    protected TileEntityAirDefenseSVA(Tier t) {
        super(t);
    }

    /** EU a tick while working, before the upgrades. */
    public abstract int baseUpkeep();

    /** AdUpgradeMath.B_*: which modules fit. */
    public abstract int upgradeBlock();

    /** The work interval the overclockers shorten (ticks; 0 - none) and its floor. */
    protected int ocBaseInterval() {
        return 0;
    }

    protected int ocFloor() {
        return 1;
    }

    /** EU a tick while working: the base with the useful overclockers, the economizers, the overdrives. */
    public int upkeep() {
        int oc = ocBaseInterval() > 0 ? AdUpgradeMath.usefulOc(ocBaseInterval(), upgradeCount(UpgradeType.OVERCLOCKER), ocFloor()) : 0;
        return AdUpgradeMath.upkeep(baseUpkeep(), oc, upgradeCount(UpgradeType.ECONOMIZER), upgradeCount(UpgradeType.OVERDRIVE));
    }

    // ------------------------------------------------------------------ upgrades (the core's modules)

    protected final ItemStack[] upg = new ItemStack[AdUpgradeMath.SLOTS];
    /** The upgrade slots as an inventory of their own (the screens' slots; nothing automatic reaches them). */
    public final IInventory upgrades = new UpgradeInv();
    private int lastTierKey = -1, tierRefreshes;

    /** How many of a kind sit in the slots (only modules that fit, only with airdefense.upgrades on; capped as in the core). */
    public int upgradeCount(UpgradeType type) {
        if (!ConfigSVA.adUpgrades || !AdUpgradeMath.accepts(upgradeBlock(), type)) {
            return 0;
        }
        int n = 0;
        for (ItemStack s : upg) {
            if (s != null && s.getItem() instanceof ItemUpgradeSC && ItemUpgradeSC.typeOf(s) == type) {
                n += s.stackSize;
            }
        }
        return type == UpgradeType.ECONOMIZER || type == UpgradeType.OVERDRIVE ? AdUpgradeMath.capGen(n) : AdUpgradeMath.cap(n);
    }

    /** The module fits this block's upgrade slot. */
    public boolean upgradeAccepted(ItemStack s) {
        return ConfigSVA.adUpgrades && s != null && s.getItem() instanceof ItemUpgradeSC && AdUpgradeMath.accepts(upgradeBlock(), ItemUpgradeSC.typeOf(s));
    }

    /** The universal transformer: any voltage, nothing overvolts it (the core machines', the charging station's). */
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
        return AdUpgradeMath.buffer(getTier(), upgradeCount(UpgradeType.ENERGY_STORAGE), upkeep());
    }

    /** EU of a shot with the economizers. */
    public int shotEu(int base) {
        return AdUpgradeMath.shotEu(base, upgradeCount(UpgradeType.ECONOMIZER));
    }

    /**
     * IC2 caches a sink's tier when it joins the net: after a transformer goes in or out (or the tier is set from the
     * meta) the master and every part re-announce themselves (the charging station's refresh).
     */
    private void tierRefresh() {
        int key = acceptsAnyVoltage() ? 1000 : inputTier().ordinal();
        if (key == lastTierKey) {
            return;
        }
        if (lastTierKey >= 0) {
            tierRefreshes++;
            refreshEnergyNet();
            int[][] c = cells();
            for (int i = 1; i < c.length; i++) {
                int x = xCoord + c[i][0], y = yCoord + c[i][1], z = zCoord + c[i][2];
                if (y >= 0 && y <= 255 && worldObj.blockExists(x, y, z)) {
                    TileEntity te = worldObj.getTileEntity(x, y, z);
                    if (te instanceof TileEntityAdPartSVA && ((TileEntityAdPartSVA) te).master() == this) {
                        ((TileEntityAdPartSVA) te).refreshNet();
                    }
                }
            }
        }
        lastTierKey = key;
    }

    /** World tests: how many times the input tier changed and the net was told. */
    public int tierRefreshes() {
        return tierRefreshes;
    }

    /** The structure is gone: the modules drop loose (BlockAirDefenseSVA.breakBlock). */
    public void dropUpgrades(World w, int x, int y, int z) {
        for (int i = 0; i < upg.length; i++) {
            if (upg[i] != null) {
                w.spawnEntityInWorld(new EntityItem(w, x + 0.5, y + 0.5, z + 0.5, upg[i].copy()));
                upg[i] = null;
            }
        }
    }

    /** The upgrade slots: 64 a stack as the core's, only a module that fits, the screen's reach and access rule. */
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
            return "container.sva.adUpgrades";
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
            TileEntityAirDefenseSVA.this.markDirty();
        }

        @Override
        public boolean isUseableByPlayer(EntityPlayer p) {
            return worldObj != null && worldObj.getTileEntity(xCoord, yCoord, zCoord) == TileEntityAirDefenseSVA.this && reach(p) && mayUse(p);
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

    /** Within reach of the structure (10 blocks from its centre: a 3x3 radar's far corner is still in). */
    public boolean reach(EntityPlayer p) {
        double[] c = pivotWorld();
        return p.getDistanceSq(c[0], yCoord + 0.5, c[2]) <= 100;
    }

    // ------------------------------------------------------------------ the equipment screen

    /** The equipment screen's picture (energy, tier, upkeep, power); subclasses add their own. */
    public NBTTagCompound equipSnapshot() {
        NBTTagCompound t = new NBTTagCompound();
        t.setIntArray("Pos", new int[] {xCoord, yCoord, zCoord});
        t.setInteger("E", getEnergyStored());
        t.setInteger("EMax", getMaxEnergyStored());
        t.setByte("Tier", (byte) getTier().ordinal());
        t.setByte("In", (byte) inputTier().ordinal());
        t.setBoolean("Any", acceptsAnyVoltage());
        t.setInteger("Use", upkeep());
        t.setInteger("BaseUse", baseUpkeep());
        t.setBoolean("Powered", powered);
        t.setBoolean("Upg", ConfigSVA.adUpgrades);
        t.setBoolean("Ad", ConfigSVA.adEnabled);
        t.setString("Own", ownerName);
        return t;
    }

    /** Every 10 ticks: the picture to the players with this block's equipment screen open. */
    private void sendEquip() {
        NBTTagCompound snap = null;
        for (Object o : worldObj.playerEntities) {
            if (o instanceof EntityPlayerMP && ((EntityPlayerMP) o).openContainer instanceof com.sva.inventory.ContainerAdSVA
                    && ((com.sva.inventory.ContainerAdSVA) ((EntityPlayerMP) o).openContainer).tile == this) {
                if (snap == null) {
                    snap = equipSnapshot();
                }
                NetADSVA.send((EntityPlayerMP) o, NetADSVA.D_EQUIP, snap);
            }
        }
    }

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

    // ------------------------------------------------------------------ the multiblock

    /** The AdShapeMath shape of this block (its block kind + meta). */
    public int shape() {
        Block b = worldObj == null ? null : getBlockType();
        int kind = b instanceof BlockAirDefenseSVA ? ((BlockAirDefenseSVA) b).kind : 0;
        return AdShapeMath.shape(kind, worldObj == null ? 0 : getBlockMetadata());
    }

    public int facing() {
        return facing & 3;
    }

    public int structState() {
        return struct;
    }

    /** BlockAirDefenseSVA.build: placed as a multiblock (or decided for a legacy one). */
    public void initStructure(int facing, int state) {
        this.facing = facing & 3;
        this.struct = state;
        markDirty();
    }

    /** The world cells of the structure {dx, dy, dz, type} (only the master's own for a compact / legacy block). */
    public int[][] cells() {
        if (struct != MULTI) {
            return new int[][] {{0, 0, 0, AdShapeMath.FULL}};
        }
        return AdShapeMath.worldCells(shape(), facing);
    }

    /**
     * An older world's single block: converted to the multiblock in the first facing (its own for the command post, else
     * south, west, north, east) whose cells are all loaded and free; kept COMPACT when none is; decided later while a
     * chunk is missing (AdShapeMath.legacy / legacyAll).
     */
    protected void tryLegacy() {
        int shape = shape();
        Block b = getBlockType();
        int first = b instanceof BlockAirDefenseSVA && ((BlockAirDefenseSVA) b).kind == BlockAirDefenseSVA.COMMAND_POST
                ? AdShapeMath.facingFromSide(getBlockMetadata()) : 0;
        int[] d = new int[4];
        for (int i = 0; i < 4; i++) {
            d[i] = BlockAirDefenseSVA.legacyDecision(worldObj, xCoord, yCoord, zCoord, shape, (first + i) & 3);
        }
        int pick = AdShapeMath.legacyAll(d);
        if (pick >= 0) {
            BlockAirDefenseSVA.buildParts(worldObj, xCoord, yCoord, zCoord, this, (first + pick) & 3);
        } else if (pick == AdShapeMath.KEEP_COMPACT) {
            initStructure(first, COMPACT);
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    // ------------------------------------------------------------------ the client model: sync, lamps, angles

    /** The lamps now (server). */
    protected int stateBits() {
        return powered ? ST_POWER : 0;
    }

    /** The entity the model turns to (server; -1 none). */
    protected int targetId() {
        return -1;
    }

    /** Rounds to show (server; the missile system's missiles). */
    protected int ammoShown() {
        return 0;
    }

    public int clientState() {
        return cState;
    }

    public int clientTarget() {
        return cTarget;
    }

    public int clientAmmo() {
        return cAmmo;
    }

    /** Every 10 ticks: what the model shows changed - send it (a description packet to the chunk's watchers). */
    private void syncTick() {
        if (++syncTimer % 10 != 0) {
            return;
        }
        int s = stateBits(), t = targetId(), a = ammoShown();
        if (s != syncState || t != syncTarget || a != syncAmmo) {
            syncState = s;
            syncTarget = t;
            syncAmmo = a;
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /** Extra synced data of a subclass (the command post's alarm). */
    protected void writeSync(NBTTagCompound t) {
    }

    protected void readSync(NBTTagCompound t) {
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        t.setByte("F", (byte) facing);
        t.setByte("M", (byte) struct);
        t.setByte("S", (byte) stateBits());
        t.setInteger("T", targetId());
        t.setByte("N", (byte) Math.min(127, ammoShown()));
        writeSync(t);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound t = pkt.func_148857_g();
        facing = t.getByte("F") & 3;
        struct = t.getByte("M");
        cState = t.getByte("S");
        cTarget = t.getInteger("T");
        cAmmo = t.getByte("N");
        readSync(t);
    }

    /** A shot (server): the client's recoil and muzzle flash, an emptied tube. */
    public void shotEvent(int ev, int param) {
        if (worldObj != null && !worldObj.isRemote) {
            worldObj.addBlockEvent(xCoord, yCoord, zCoord, getBlockType(), ev, param);
        }
    }

    /** Client animation: the turning parts' angles (degrees) last tick and now, the last shot, the tubes reloading. */
    public float yaw, yawPrev, pitch, pitchPrev, spin, spinPrev;
    public long lastShot = -100;
    public final long[] tubeFired = {-1000, -1000, -1000, -1000};
    private int nextTube;

    @Override
    public boolean receiveClientEvent(int ev, int param) {
        if (worldObj == null || !worldObj.isRemote) {
            return true;
        }
        long now = worldObj.getTotalWorldTime();
        double[] c = pivotWorld();
        if (ev == EV_BURST) {
            lastShot = now;
            for (int i = 0; i < 3; i++) {
                worldObj.spawnParticle("smoke", c[0] + rnd() * 0.6, c[1] + 0.4, c[2] + rnd() * 0.6, 0, 0.04, 0);
            }
        } else if (ev == EV_LAUNCH) {
            lastShot = now;
            tubeFired[nextTube] = now;
            nextTube = (nextTube + 1) % AdShapeMath.TUBES;
            for (int i = 0; i < 10; i++) {                         // the booster's cloud round the launcher
                worldObj.spawnParticle(i < 7 ? "largesmoke" : "flame", c[0] + rnd() * 1.6, c[1] + 0.2 + Math.abs(rnd()), c[2] + rnd() * 1.6,
                        rnd() * 0.05, 0.03, rnd() * 0.05);
            }
        }
        return true;
    }

    private double rnd() {
        return worldObj.rand.nextDouble() - 0.5;
    }

    /** Tubes still reloading (client): fired less than the missile system's reload ago. */
    public int reloadingTubes() {
        if (worldObj == null) {
            return 0;
        }
        long now = worldObj.getTotalWorldTime();
        int n = 0;
        for (long t : tubeFired) {
            n += now - t < com.sva.airdefense.BallisticsMath.MISSILE_RELOAD ? 1 : 0;
        }
        return n;
    }

    /** One client tick of the model's moving parts (cheap: a few floats; the target looked up by its id). */
    protected void clientTick() {
        yawPrev = yaw;
        pitchPrev = pitch;
        spinPrev = spin;
        boolean on = (cState & ST_POWER) != 0;
        int shape = shape();
        if (shape == AdShapeMath.SURVEY || shape == AdShapeMath.KP) {
            if (on) {
                spin += AdShapeMath.sweepStep() * (shape == AdShapeMath.KP ? 1.5F : 1F);
                if (spin >= 360F) {
                    spin -= 360F;
                    spinPrev -= 360F;
                }
            }
            return;
        }
        if (shape == AdShapeMath.HEIGHT) {
            if (on) {
                pitch = AdShapeMath.nod(worldObj.getTotalWorldTime());
            }
            return;
        }
        if (!on) {
            return;                                             // no power: the drives stop where they are
        }
        float[] lim = AdShapeMath.drive(shape);
        float ty = yaw, tp = lim[2];
        Entity e = cTarget >= 0 ? worldObj.getEntityByID(cTarget) : null;
        if (e != null) {
            double[] c = pivotWorld();
            float[] a = AdShapeMath.aim(facing, e.posX - c[0], e.posY + e.height / 2 - c[1], e.posZ - c[2]);
            ty = a[0];
            tp = a[1];
        } else if (shape == AdShapeMath.TRACK) {
            ty = yaw + 2F;                                      // no target: the dish keeps searching round
        }
        yaw = AdShapeMath.approach(yaw, ty, lim[0]);
        pitch = AdShapeMath.approach(pitch, AdShapeMath.clamp(tp, lim[3], lim[4]), lim[1]);
        float d = yaw - yawPrev;
        if (d > 180F) {
            yawPrev += 360F;
        } else if (d < -180F) {
            yawPrev -= 360F;
        }
    }

    /** The turning mount's centre in the world (aimed from there): the footprint's centre, 1.2 blocks up. */
    public double[] pivotWorld() {
        if (struct != MULTI) {
            return new double[] {xCoord + 0.5, yCoord + 0.8, zCoord + 0.5};
        }
        double[] c = AdShapeMath.centre(shape(), facing);
        return new double[] {xCoord + 0.5 + c[0], yCoord + 1.2, zCoord + 0.5 + c[1]};
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        int shape = shape();
        double r = Math.max(AdShapeMath.W[shape], AdShapeMath.D[shape]) / 2.0 + 2.6;
        double[] c = struct == MULTI ? AdShapeMath.centre(shape, facing) : new double[2];
        double cx = xCoord + 0.5 + c[0], cz = zCoord + 0.5 + c[1];
        return AxisAlignedBB.getBoundingBox(cx - r, yCoord, cz - r, cx + r, yCoord + AdShapeMath.layers(shape) + 2.5, cz + r);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return 160.0 * 160.0;
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
        if (worldObj == null) {
            return;
        }
        if (worldObj.isRemote) {
            clientTick();
            return;
        }
        if (struct == LEGACY && ++legacyTimer % 20 == 1) {
            tryLegacy();
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
        tierRefresh();
        syncTick();
        if (syncTimer % 10 == 5) {
            sendEquip();
        }
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
        facing = t.getByte("AdFacing") & 3;
        struct = t.hasKey("AdStruct") ? t.getByte("AdStruct") : LEGACY;
        access.clear();
        NBTTagList l = t.getTagList("AdAccess", 8);
        for (int i = 0; i < l.tagCount() && i < MAX_ACCESS; i++) {
            access.add(l.getStringTagAt(i));
        }
        for (int i = 0; i < upg.length; i++) {
            upg[i] = null;
        }
        NBTTagList u = t.getTagList("AdUpg", 10);
        for (int i = 0; i < u.tagCount(); i++) {
            NBTTagCompound c = u.getCompoundTagAt(i);
            int s = c.getByte("Slot") & 0xFF;
            if (s < upg.length) {
                upg[s] = ItemStack.loadItemStackFromNBT(c);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound t) {
        super.writeToNBT(t);
        t.setString("AdOwner", ownerName);
        t.setString("AdOwnerId", ownerId);
        t.setByte("AdFacing", (byte) facing);
        t.setByte("AdStruct", (byte) struct);
        NBTTagList l = new NBTTagList();
        for (String a : access) {
            l.appendTag(new NBTTagString(a));
        }
        t.setTag("AdAccess", l);
        NBTTagList u = new NBTTagList();
        for (int i = 0; i < upg.length; i++) {
            if (upg[i] != null) {
                NBTTagCompound c = new NBTTagCompound();
                c.setByte("Slot", (byte) i);
                upg[i].writeToNBT(c);
                u.appendTag(c);
            }
        }
        t.setTag("AdUpg", u);
    }
}
