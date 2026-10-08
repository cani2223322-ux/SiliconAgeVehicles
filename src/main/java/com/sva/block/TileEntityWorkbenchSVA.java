package com.sva.block;

import com.sc.energy.Tier;
import com.sc.energy.TileEntityEnergyBase;
import com.sc.item.ItemUpgradeSC;
import com.sc.machine.UpgradeType;
import com.sva.workbench.FillPlannerSVA;
import com.sva.workbench.WorkbenchMath;
import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * «Инженерный верстак»: a core MV energy consumer (IC2 / Silicon Age grid). Slots: 0-24 the 5x5 grid, 25 the
 * output, 26-29 upgrades. Components are taken from the output at once (no EU); a vehicle is assembled over
 * time for EU, the grid stays untouched until it finishes (a changed grid cancels it, nothing is lost).
 * Faces: top = into the grid (one item per empty cell), bottom = out of the output, the rest (not the front) = energy.
 */
public class TileEntityWorkbenchSVA extends TileEntityEnergyBase implements ISidedInventory {

    public static final int GRID_SLOTS = WorkbenchRecipeSVA.CELLS, OUTPUT = 25, FIRST_UPGRADE = 26, UPGRADE_SLOTS = 4, SIZE = 30;
    private static final int[] TOP = new int[GRID_SLOTS], BOTTOM = {OUTPUT}, NONE = {};

    static {
        for (int i = 0; i < GRID_SLOTS; i++) {
            TOP[i] = i;
        }
    }

    // NBT keys (append-only)
    private static final String K_ITEMS = "WbItems", K_SLOT = "Slot", K_FACING = "WbFacing", K_RECIPE = "WbRecipe", K_PROGRESS = "WbProgress",
            K_TICKS = "WbTicks", K_EU = "WbEu", K_PAID = "WbPaid";

    private final ItemStack[] slots = new ItemStack[SIZE];
    private int facing = 3;
    /** The vehicle being assembled, null when idle. */
    private WorkbenchRecipeSVA assembling;
    private int progress, totalTicks;
    private long totalEu, euPaid;
    /** Paid its full share last tick (false = waiting for energy). */
    private boolean powered = true;
    /** Client: the assembling recipe index, synced. */
    private int clientRecipe = -1;

    public TileEntityWorkbenchSVA() {
        super(WorkbenchMath.BASE_TIER);
    }

    // ------------------------------------------------------------------ upgrades / energy

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
        return t == UpgradeType.OVERCLOCKER || t == UpgradeType.ENERGY_STORAGE || t == UpgradeType.TRANSFORMER || t == UpgradeType.ECONOMIZER
                || t == UpgradeType.UNIVERSAL_TRANSFORMER;
    }

    @Override
    public int getMaxEnergyStored() {
        return WorkbenchMath.buffer(upgradeCount(UpgradeType.ENERGY_STORAGE));
    }

    @Override
    public boolean acceptsAnyVoltage() {
        return upgradeCount(UpgradeType.UNIVERSAL_TRANSFORMER) > 0;
    }

    @Override
    public Tier inputTier() {
        return WorkbenchMath.inputTier(upgradeCount(UpgradeType.TRANSFORMER), acceptsAnyVoltage());
    }

    @Override
    public boolean acceptsFrom(ForgeDirection side) {
        return side == ForgeDirection.UNKNOWN || side.ordinal() != facing;
    }

    public int effectiveTicks(WorkbenchRecipeSVA r) {
        return WorkbenchMath.ticks(r.ticks, upgradeCount(UpgradeType.OVERCLOCKER));
    }

    public long effectiveEu(WorkbenchRecipeSVA r) {
        return WorkbenchMath.eu(r.eu, upgradeCount(UpgradeType.OVERCLOCKER), upgradeCount(UpgradeType.ECONOMIZER));
    }

    // ------------------------------------------------------------------ state

    public int getFacing() {
        return facing;
    }

    public void setFacing(int f) {
        int was = facing;
        facing = f < 2 || f > 5 ? 3 : f;
        if (was != facing && worldObj != null && !worldObj.isRemote) {
            refreshEnergyNet();                     // IC2 caches the faces that take energy
        }
    }

    public boolean isAssembling() {
        return worldObj != null && worldObj.isRemote ? clientRecipe >= 0 : assembling != null;
    }

    public WorkbenchRecipeSVA getAssembling() {
        return worldObj != null && worldObj.isRemote ? WorkbenchRecipesSVA.byIndex(clientRecipe) : assembling;
    }

    public int getProgress() {
        return progress;
    }

    public int getTotalTicks() {
        return totalTicks;
    }

    public long getEuPaid() {
        return euPaid;
    }

    public boolean isPowered() {
        return powered;
    }

    /** Client sync (container). */
    public void setClientState(int recipe, int progress, int total, boolean powered) {
        this.clientRecipe = recipe;
        this.progress = progress;
        this.totalTicks = total;
        this.powered = powered;
    }

    public int syncRecipeIndex() {
        return assembling == null ? -1 : WorkbenchRecipesSVA.indexOf(assembling);
    }

    public ItemStack[] grid() {
        ItemStack[] g = new ItemStack[GRID_SLOTS];
        System.arraycopy(slots, 0, g, 0, GRID_SLOTS);
        return g;
    }

    /** The recipe the grid holds now, or null. */
    public WorkbenchRecipeSVA current() {
        return WorkbenchRecipesSVA.find(grid());
    }

    /** What the output shows when it is empty: an instant recipe's product, or null. */
    public ItemStack instantPreview() {
        if (assembling != null) {
            return null;
        }
        WorkbenchRecipeSVA r = current();
        return r != null && r.instant() ? r.result() : null;
    }

    public int comparatorSignal() {
        return assembling == null ? 0 : WorkbenchMath.comparator(progress, totalTicks);
    }

    // ------------------------------------------------------------------ actions

    /** Results of start(). */
    public static final int OK = 0, NO_RECIPE = 1, INSTANT = 2, LOW_TIER = 3, OUTPUT_FULL = 4, BUSY = 5;

    /** «Собрать»: start assembling the vehicle the grid holds. */
    public int start() {
        if (assembling != null) {
            return BUSY;
        }
        WorkbenchRecipeSVA r = current();
        if (r == null) {
            return NO_RECIPE;
        }
        if (r.instant()) {
            return INSTANT;
        }
        if (!WorkbenchMath.canBuild(r.tier, inputTier())) {
            return LOW_TIER;
        }
        if (slots[OUTPUT] != null) {
            return OUTPUT_FULL;
        }
        assembling = r;
        progress = 0;
        totalTicks = effectiveTicks(r);
        totalEu = effectiveEu(r);
        euPaid = 0;
        powered = true;
        changed();
        return OK;
    }

    /** Stops the assembly (nothing taken from the grid; the EU paid is gone). */
    public void cancel() {
        if (assembling != null) {
            assembling = null;
            progress = totalTicks = 0;
            totalEu = euPaid = 0;
            changed();
        }
    }

    /** Takes an instant recipe's product: the grid pays once. @return the product or null */
    public ItemStack craftInstant(EntityPlayer p) {
        if (assembling != null) {
            return null;
        }
        WorkbenchRecipeSVA r = current();
        if (r == null || !r.instant()) {
            return null;
        }
        consume(r, r.match(grid()), p);
        return r.result();
    }

    /** One item off each used cell; container items (buckets) go back into the cell, or to the player / the floor. */
    private void consume(WorkbenchRecipeSVA r, int[] m, EntityPlayer p) {
        for (int slot : r.usedSlots(m)) {
            ItemStack s = slots[slot];
            if (s == null) {
                continue;
            }
            ItemStack back = s.getItem().hasContainerItem(s) ? s.getItem().getContainerItem(s) : null;
            s.stackSize--;
            if (s.stackSize <= 0) {
                slots[slot] = null;
            }
            if (back != null) {
                if (back.isItemStackDamageable() && back.getItemDamage() > back.getMaxDamage()) {
                    continue;
                }
                if (slots[slot] == null) {
                    slots[slot] = back;
                } else if (p == null || !p.inventory.addItemStackToInventory(back)) {
                    drop(back);
                }
            }
        }
        markDirty();
    }

    private void drop(ItemStack s) {
        if (worldObj != null && !worldObj.isRemote && s != null && s.stackSize > 0) {
            EntityItem e = new EntityItem(worldObj, xCoord + 0.5, yCoord + 1.1, zCoord + 0.5, s);
            worldObj.spawnEntityInWorld(e);
        }
    }

    /** «Очистить»: the grid into the player's inventory (what doesn't fit stays). Cancels an assembly the grid no longer matches. */
    public void clearGrid(EntityPlayer p) {
        boolean moved = false;
        for (int i = 0; i < GRID_SLOTS; i++) {
            if (slots[i] != null) {
                int before = slots[i].stackSize;
                p.inventory.addItemStackToInventory(slots[i]);
                if (slots[i].stackSize != before) {
                    moved = true;
                }
                if (slots[i].stackSize <= 0) {
                    slots[i] = null;
                }
            }
        }
        if (moved) {
            if (assembling != null && assembling.match(grid()) == null) {
                cancel();
            }
            markDirty();
            p.inventory.markDirty();
        }
    }

    /**
     * «Разложить»: empties the grid into the player's inventory, then lays recipe `r` out from it (one item per
     * cell; cells it has nothing for stay empty). @return false when the grid couldn't be emptied
     */
    public boolean fill(EntityPlayer p, WorkbenchRecipeSVA r) {
        clearGrid(p);
        for (int i = 0; i < GRID_SLOTS; i++) {
            if (slots[i] != null) {
                return false;                       // the player's inventory is full
            }
        }
        Object[] cells = r.layout();
        ItemStack[] inv = p.inventory.mainInventory;
        int[] plan = FillPlannerSVA.plan(cells, inv);
        for (int c = 0; c < cells.length; c++) {
            if (plan[c] >= 0 && inv[plan[c]] != null) {
                slots[c] = p.inventory.decrStackSize(plan[c], 1);
            }
        }
        p.inventory.markDirty();
        markDirty();
        return true;
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote || assembling == null) {
            return;
        }
        int[] m = assembling.match(grid());
        if (m == null) {
            cancel();                               // the grid changed: nothing taken
            return;
        }
        if (progress < totalTicks) {
            if (!switchedOn() || !WorkbenchMath.canBuild(assembling.tier, inputTier())) {
                powered = false;
                return;
            }
            long share = WorkbenchMath.share(totalEu, euPaid, totalTicks, progress);
            int take = (int) Math.min(Math.min(share, Integer.MAX_VALUE), getEnergyStored());
            if (take > 0) {
                removeEnergy(take);
                euPaid += take;
            }
            boolean full = take >= share;
            if (full) {
                progress++;
            }
            if (full != powered) {
                powered = full;
            }
        }
        if (progress >= totalTicks && slots[OUTPUT] == null) {
            consume(assembling, m, null);
            slots[OUTPUT] = assembling.result();
            assembling = null;
            progress = totalTicks = 0;
            totalEu = euPaid = 0;
            changed();
        }
    }

    private void changed() {
        markDirty();
        if (worldObj != null) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            worldObj.func_147453_f(xCoord, yCoord, zCoord, getBlockType());       // comparators
        }
    }

    /** World test: fill the buffer. */
    public void fillForTest(int eu) {
        addEnergy(eu);
    }

    // ------------------------------------------------------------------ NBT / sync

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        for (int i = 0; i < SIZE; i++) {
            slots[i] = null;
        }
        NBTTagList list = nbt.getTagList(K_ITEMS, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            int s = t.getByte(K_SLOT) & 0xFF;
            if (s < SIZE) {
                slots[s] = ItemStack.loadItemStackFromNBT(t);
            }
        }
        setFacing(nbt.getByte(K_FACING));
        assembling = nbt.hasKey(K_RECIPE) ? WorkbenchRecipesSVA.byId(nbt.getString(K_RECIPE)) : null;
        progress = Math.max(0, nbt.getInteger(K_PROGRESS));
        totalTicks = Math.max(0, nbt.getInteger(K_TICKS));
        totalEu = Math.max(0, nbt.getLong(K_EU));
        euPaid = Math.max(0, nbt.getLong(K_PAID));
        if (assembling == null || totalTicks <= 0) {
            assembling = null;
            progress = totalTicks = 0;
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < SIZE; i++) {
            if (slots[i] != null) {
                NBTTagCompound t = new NBTTagCompound();
                t.setByte(K_SLOT, (byte) i);
                slots[i].writeToNBT(t);
                list.appendTag(t);
            }
        }
        nbt.setTag(K_ITEMS, list);
        nbt.setByte(K_FACING, (byte) facing);
        if (assembling != null) {
            nbt.setString(K_RECIPE, assembling.id);
            nbt.setInteger(K_PROGRESS, progress);
            nbt.setInteger(K_TICKS, totalTicks);
            nbt.setLong(K_EU, totalEu);
            nbt.setLong(K_PAID, euPaid);
        }
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound t = new NBTTagCompound();
        t.setByte(K_FACING, (byte) facing);
        t.setInteger("WbSync", syncRecipeIndex());
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, t);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound t = pkt.func_148857_g();
        int was = clientRecipe, wasFacing = facing;
        setFacing(t.getByte(K_FACING));
        clientRecipe = t.getInteger("WbSync");
        if ((was >= 0) != (clientRecipe >= 0) || wasFacing != facing) {
            if (worldObj != null) {
                worldObj.markBlockRangeForRenderUpdate(xCoord, yCoord, zCoord, xCoord, yCoord, zCoord);
            }
        }
    }

    @Override
    public net.minecraft.util.AxisAlignedBB getRenderBoundingBox() {
        return net.minecraft.util.AxisAlignedBB.getBoundingBox(xCoord, yCoord, zCoord, xCoord + 1, yCoord + 2, zCoord + 1);
    }

    // ------------------------------------------------------------------ inventory

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
        if (slots[i] == null) {
            return null;
        }
        ItemStack out;
        if (slots[i].stackSize <= n) {
            out = slots[i];
            slots[i] = null;
        } else {
            out = slots[i].splitStack(n);
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
        slots[i] = s;
        if (s != null && s.stackSize > getInventoryStackLimit()) {
            s.stackSize = getInventoryStackLimit();
        }
        markDirty();
    }

    @Override
    public String getInventoryName() {
        return "tile.siliconagevehicles.workbench.name";
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
        if (i >= FIRST_UPGRADE) {
            return upgradeAccepted(s);
        }
        return i < GRID_SLOTS;
    }

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        return side == 1 ? TOP : side == 0 ? BOTTOM : NONE;
    }

    /** Automation fills empty cells only, one item each (hoppers move one at a time), never during an assembly. */
    @Override
    public boolean canInsertItem(int slot, ItemStack s, int side) {
        return side == 1 && slot < GRID_SLOTS && assembling == null && slots[slot] == null;
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack s, int side) {
        return side == 0 && slot == OUTPUT;
    }
}
