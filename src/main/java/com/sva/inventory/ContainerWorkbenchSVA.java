package com.sva.inventory;

import com.sva.block.TileEntityWorkbenchSVA;
import com.sva.net.NetSVA;
import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;

/** Workbench screen: 25 grid + output + 4 upgrades + the player's 36. Syncs energy, buffer, progress, recipe. */
public class ContainerWorkbenchSVA extends Container {

    // layout (shared with GuiWorkbenchSVA): item positions of slot tops
    public static final int GRID_X = 131, GRID_Y = 19, OUT_X = 263, OUT_Y = 55, UPG_X = 8, UPG_Y = 181, UPG_STEP = 20,
            INV_X = 131, INV_Y = 150;
    public static final int PLAYER_START = TileEntityWorkbenchSVA.SIZE, PLAYER_END = PLAYER_START + 36;

    private final TileEntityWorkbenchSVA tile;
    private final OutputSlot output;
    /** energy lo/hi, max lo/hi, progress, total, recipe, powered. */
    private final int[] last = new int[8];

    public ContainerWorkbenchSVA(InventoryPlayer inv, TileEntityWorkbenchSVA te) {
        this.tile = te;
        for (int i = 0; i < TileEntityWorkbenchSVA.GRID_SLOTS; i++) {
            addSlotToContainer(new Slot(te, i, GRID_X + (i % 5) * 18, GRID_Y + (i / 5) * 18));
        }
        output = new OutputSlot(te, inv.player);
        addSlotToContainer(output);
        for (int i = 0; i < TileEntityWorkbenchSVA.UPGRADE_SLOTS; i++) {
            addSlotToContainer(new UpgradeSlot(te, TileEntityWorkbenchSVA.FIRST_UPGRADE + i, UPG_X + i * UPG_STEP, UPG_Y));
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlotToContainer(new Slot(inv, c + r * 9 + 9, INV_X + c * 18, INV_Y + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlotToContainer(new Slot(inv, c, INV_X + c * 18, INV_Y + 58));
        }
        for (int i = 0; i < last.length; i++) {
            last[i] = Integer.MIN_VALUE;
        }
    }

    public TileEntityWorkbenchSVA getTile() {
        return tile;
    }

    public boolean isAt(int x, int y, int z) {
        return tile.xCoord == x && tile.yCoord == y && tile.zCoord == z;
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return tile.isUseableByPlayer(p);
    }

    /** A screen button (server thread). */
    public void action(EntityPlayer p, int action, String recipeId) {
        if (action == NetSVA.Workbench.START) {
            int r = tile.start();
            if (r != TileEntityWorkbenchSVA.OK) {
                String key = r == TileEntityWorkbenchSVA.NO_RECIPE ? "sva.wb.msg.noRecipe" : r == TileEntityWorkbenchSVA.INSTANT ? "sva.wb.msg.instant"
                        : r == TileEntityWorkbenchSVA.LOW_TIER ? "sva.wb.msg.lowTier" : r == TileEntityWorkbenchSVA.OUTPUT_FULL ? "sva.wb.msg.outputFull"
                        : "sva.wb.msg.busy";
                WorkbenchRecipeSVA cur = tile.current();
                String need = cur == null ? "" : com.sva.workbench.WorkbenchMath.requiredInput(cur.tier).name();
                p.addChatComponentMessage(new ChatComponentTranslation(key, need));
            }
        } else if (action == NetSVA.Workbench.FILL) {
            WorkbenchRecipeSVA r = WorkbenchRecipesSVA.byId(recipeId);
            if (r != null && !tile.fill(p, r)) {
                p.addChatComponentMessage(new ChatComponentTranslation("sva.wb.msg.invFull"));
            }
        } else if (action == NetSVA.Workbench.CLEAR) {
            tile.clearGrid(p);
        }
        detectAndSendChanges();
    }

    // ------------------------------------------------------------------ sync

    private int[] values() {
        int e = tile.getEnergyStored(), m = tile.getMaxEnergyStored();
        return new int[] {e & 0xFFFF, e >>> 16, m & 0xFFFF, m >>> 16, tile.getProgress(), tile.getTotalTicks(), tile.syncRecipeIndex(),
                tile.isPowered() ? 1 : 0};
    }

    @Override
    public void addCraftingToCrafters(ICrafting c) {
        super.addCraftingToCrafters(c);
        int[] v = values();
        for (int i = 0; i < v.length; i++) {
            c.sendProgressBarUpdate(this, i, v[i]);
        }
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        int[] v = values();
        for (int i = 0; i < v.length; i++) {
            if (v[i] != last[i]) {
                for (Object o : crafters) {
                    ((ICrafting) o).sendProgressBarUpdate(this, i, v[i]);
                }
                last[i] = v[i];
            }
        }
    }

    private final int[] client = new int[8];

    @Override
    @SideOnly(Side.CLIENT)
    public void updateProgressBar(int id, int value) {
        if (id < 0 || id >= client.length) {
            return;
        }
        client[id] = id == 6 ? (short) value : value & 0xFFFF;
        tile.setEnergyStoredClient(client[0] | client[1] << 16);
        tile.setClientState(client[6], client[4], client[5], client[7] != 0);
        clientMax = client[2] | client[3] << 16;
    }

    private int clientMax = -1;

    /** The buffer size as the server sees it (client; upgrades decide it). */
    public int clientMaxEnergy() {
        return clientMax > 0 ? clientMax : tile.getMaxEnergyStored();
    }

    // ------------------------------------------------------------------ shift-click

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) {
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return null;
        }
        if (index == TileEntityWorkbenchSVA.OUTPUT) {
            takeAll(p);
            return null;
        }
        ItemStack s = slot.getStack();
        ItemStack copy = s.copy();
        if (index < PLAYER_START) {
            if (!mergeItemStack(s, PLAYER_START, PLAYER_END, true)) {
                return null;
            }
        } else if (TileEntityWorkbenchSVA.upgradeAccepted(s)) {
            if (!mergeItemStack(s, TileEntityWorkbenchSVA.FIRST_UPGRADE, TileEntityWorkbenchSVA.SIZE, false)) {
                return null;
            }
        } else if (index < PLAYER_START + 27) {
            if (!mergeItemStack(s, PLAYER_START + 27, PLAYER_END, false)) {
                return null;
            }
        } else if (!mergeItemStack(s, PLAYER_START, PLAYER_START + 27, false)) {
            return null;
        }
        if (s.stackSize == 0) {
            slot.putStack(null);
        } else {
            slot.onSlotChanged();
        }
        return s.stackSize == copy.stackSize ? null : copy;
    }

    /** Shift-click on the output: a finished vehicle, or as many instant crafts as the grid and the inventory allow. */
    private void takeAll(EntityPlayer p) {
        if (p.worldObj.isRemote) {
            return;                                 // the server moves / crafts; the slots sync back
        }
        ItemStack real = tile.getStackInSlot(TileEntityWorkbenchSVA.OUTPUT);
        if (real != null) {
            if (mergeItemStack(real, PLAYER_START, PLAYER_END, true)) {
                if (real.stackSize <= 0) {
                    tile.setInventorySlotContents(TileEntityWorkbenchSVA.OUTPUT, null);
                }
                tile.markDirty();
            }
            return;
        }
        for (int n = 0; n < 64; n++) {
            ItemStack preview = tile.instantPreview();
            if (preview == null || room(p.inventory, preview) < preview.stackSize) {
                break;
            }
            ItemStack made = tile.craftInstant(p);
            if (made == null) {
                break;
            }
            made.onCrafting(p.worldObj, p, made.stackSize);
            mergeItemStack(made, PLAYER_START, PLAYER_END, true);
            if (made.stackSize > 0) {
                p.dropPlayerItemWithRandomChoice(made, false);
            }
        }
    }

    /** Double-click collecting never takes from the output (it would craft). */
    @Override
    public boolean func_94530_a(ItemStack s, Slot slot) {
        return slot != output && super.func_94530_a(s, slot);
    }

    /** How many of `s` the main inventory still takes. */
    static int room(InventoryPlayer inv, ItemStack s) {
        int n = 0, max = Math.min(s.getMaxStackSize(), inv.getInventoryStackLimit());
        for (int i = 0; i < inv.mainInventory.length; i++) {
            ItemStack h = inv.mainInventory[i];
            if (h == null) {
                n += max;
            } else if (h.isItemEqual(s) && ItemStack.areItemStackTagsEqual(h, s)) {
                n += Math.max(0, max - h.stackSize);
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ slots

    /**
     * The output: a finished vehicle when there is one, else the product of the instant recipe in the grid
     * (taking it pays the grid, as a crafting table).
     */
    static class OutputSlot extends Slot {
        private final TileEntityWorkbenchSVA te;
        private final EntityPlayer player;
        private boolean previewTaken;

        OutputSlot(TileEntityWorkbenchSVA te, EntityPlayer p) {
            super(te, TileEntityWorkbenchSVA.OUTPUT, OUT_X, OUT_Y);
            this.te = te;
            this.player = p;
        }

        @Override
        public ItemStack getStack() {
            ItemStack real = te.getStackInSlot(TileEntityWorkbenchSVA.OUTPUT);
            if (real != null || te.getWorldObj() == null || te.getWorldObj().isRemote) {
                return real;                         // the client shows what the server synced
            }
            return te.instantPreview();
        }

        @Override
        public boolean getHasStack() {
            return getStack() != null;
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return false;
        }

        @Override
        public ItemStack decrStackSize(int n) {
            ItemStack real = te.getStackInSlot(TileEntityWorkbenchSVA.OUTPUT);
            if (real != null) {
                previewTaken = false;
                return te.decrStackSize(TileEntityWorkbenchSVA.OUTPUT, real.stackSize);
            }
            ItemStack p = te.instantPreview();
            previewTaken = p != null;
            return p;
        }

        @Override
        public void onPickupFromSlot(EntityPlayer p, ItemStack s) {
            if (previewTaken) {
                previewTaken = false;
                if (!p.worldObj.isRemote) {
                    te.craftInstant(p);
                }
                if (s != null) {
                    s.onCrafting(p.worldObj, p, s.stackSize);
                }
            }
            super.onPickupFromSlot(p, s);
        }
    }

    static class UpgradeSlot extends Slot {
        UpgradeSlot(TileEntityWorkbenchSVA te, int i, int x, int y) {
            super(te, i, x, y);
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return TileEntityWorkbenchSVA.upgradeAccepted(s);
        }
    }
}
