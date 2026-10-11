package com.sva.inventory;

import com.sva.block.TileEntityGarageSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.item.ItemTuningSVA;
import com.sva.vehicle.GarageMath;
import com.sva.vehicle.TuningMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Wave 2, ★7 / ★8: the repair garage screen - 2 material slots (steel / iron ingots), the 4 tuning slots (the garage
 * vehicle's upgrades, TileEntityGarageSVA.TuningInv: owner only, TuningMath rules) and the player's 36. Synced as
 * progress bars (ints in 16-bit halves): the buffer, the vehicle, status, the job's progress / mode and both repair
 * prices; the vehicle's own numbers (hull, heat, charge, drill head) the client reads from its data watcher.
 */
public class ContainerGarageSVA extends Container {

    // layout (shared with GuiGarageSVA): item positions of slot tops
    public static final int MAT_X = 196, MAT_Y = 88, TUNE_X = 196, TUNE_Y = 34, TUNE_STEP = 20, INV_X = 69, INV_Y = 152;
    public static final int FIRST_TUNE = TileEntityGarageSVA.SIZE, PLAYER_START = FIRST_TUNE + TuningMath.MAX_SLOTS,
            PLAYER_END = PLAYER_START + 36;
    // synced value ids
    public static final int V_E = 0, V_VEH = 2, V_STATUS = 4, V_PROG = 5, V_MODE = 6, V_EU_ALL = 7, V_EU_HULL = 9, V_STEEL = 11,
            V_HAVE_STEEL = 12, V_HAVE_IRON = 13, V_PLATES = 14, V_JOB = 15,
            /** Hydrogen wave: electrolyte in the slots, «Обслужить АКБ»'s check (TileEntityGarageSVA.serviceCheck, R_*). */
            V_ELYTE = 16, V_SERVICE = 17, V_COUNT = 18;

    private final TileEntityGarageSVA tile;
    private final EntityPlayer player;
    private final int[] last = new int[V_COUNT];
    private final int[] client = new int[V_COUNT];

    public ContainerGarageSVA(InventoryPlayer inv, TileEntityGarageSVA te) {
        this.tile = te;
        this.player = inv.player;
        for (int i = 0; i < TileEntityGarageSVA.MATERIAL_SLOTS; i++) {
            addSlotToContainer(new Material(te, i, MAT_X + i * 18, MAT_Y));
        }
        for (int i = 0; i < TuningMath.MAX_SLOTS; i++) {
            addSlotToContainer(new Tuning(te, i, TUNE_X + i * TUNE_STEP, TUNE_Y));
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

    public TileEntityGarageSVA getTile() {
        return tile;
    }

    public boolean isAt(int x, int y, int z) {
        return tile.xCoord == x && tile.yCoord == y && tile.zCoord == z;
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return tile.isUseableByPlayer(p);
    }

    // ------------------------------------------------------------------ sync

    private int[] values() {
        int[] v = new int[V_COUNT];
        split(v, V_E, tile.getEnergyStored());
        EntityVehicleSVA cur = tile.getCurrent();
        split(v, V_VEH, cur == null ? 0 : cur.getEntityId() + 1);
        v[V_STATUS] = tile.getStatus();
        v[V_PROG] = (int) Math.round(tile.progress() * 1000);
        v[V_MODE] = tile.getJobMode();
        long[] all = tile.cost(GarageMath.ALL), hull = tile.cost(GarageMath.HULL);
        split(v, V_EU_ALL, (int) Math.min(Integer.MAX_VALUE, all[0]));
        split(v, V_EU_HULL, (int) Math.min(Integer.MAX_VALUE, hull[0]));
        v[V_STEEL] = (int) Math.min(32767, all[1]);
        int[] m = tile.materials();
        v[V_HAVE_STEEL] = Math.min(32767, m[0]);
        v[V_HAVE_IRON] = Math.min(32767, m[1]);
        v[V_PLATES] = tile.plateCount();
        v[V_JOB] = tile.isRepairing() ? 1 : 0;
        v[V_ELYTE] = Math.min(32767, tile.electrolytes());
        v[V_SERVICE] = tile.serviceCheck(null);
        return v;
    }

    private static void split(int[] v, int at, int value) {
        v[at] = value & 0xFFFF;
        v[at + 1] = value >>> 16;
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

    @Override
    @SideOnly(Side.CLIENT)
    public void updateProgressBar(int id, int value) {
        if (id < 0 || id >= client.length) {
            return;
        }
        client[id] = value & 0xFFFF;
        tile.setEnergyStoredClient(get(V_E));
    }

    /** Client: a synced value (two-slot ones joined). */
    public int get(int id) {
        if (id == V_E || id == V_VEH || id == V_EU_ALL || id == V_EU_HULL) {
            return client[id] | client[id + 1] << 16;
        }
        return (short) client[id];
    }

    // ------------------------------------------------------------------ shift-click

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) {
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot == null || !slot.getHasStack() || !slot.canTakeStack(p)) {
            return null;
        }
        ItemStack s = slot.getStack();
        ItemStack copy = s.copy();
        if (index < PLAYER_START) {
            if (index >= FIRST_TUNE) {
                // an upgrade out of the vehicle: straight into the inventory or not at all (never half)
                ItemStack out = copy.copy();
                if (!mergeItemStack(out, PLAYER_START, PLAYER_END, true)) {
                    return null;
                }
                slot.putStack(null);
                return null;
            }
            if (!mergeItemStack(s, PLAYER_START, PLAYER_END, true)) {
                return null;
            }
        } else if (TileEntityGarageSVA.materialAccepted(s)) {
            if (!mergeItemStack(s, 0, TileEntityGarageSVA.MATERIAL_SLOTS, false)) {
                return null;
            }
        } else if (ItemTuningSVA.kind(s) >= 0) {
            boolean put = false;
            for (int i = FIRST_TUNE; i < PLAYER_START && !put; i++) {
                Slot t = (Slot) inventorySlots.get(i);
                if (!t.getHasStack() && t.isItemValid(s)) {
                    t.putStack(s.splitStack(1));
                    put = true;
                }
            }
            if (!put) {
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

    static class Material extends Slot {
        Material(TileEntityGarageSVA te, int i, int x, int y) {
            super(te, i, x, y);
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return TileEntityGarageSVA.materialAccepted(s);
        }
    }

    /** A tuning slot: the vehicle's upgrade slot; only its owner (or an op) puts in / takes out. */
    class Tuning extends Slot {
        private final TileEntityGarageSVA te;

        Tuning(TileEntityGarageSVA te, int i, int x, int y) {
            super(te.tuning, i, x, y);
            this.te = te;
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return te.mayTune(player) && inventory.isItemValidForSlot(getSlotIndex(), s);
        }

        @Override
        public boolean canTakeStack(EntityPlayer p) {
            return te.mayTune(p);
        }

        @Override
        public int getSlotStackLimit() {
            return 1;
        }
    }
}
