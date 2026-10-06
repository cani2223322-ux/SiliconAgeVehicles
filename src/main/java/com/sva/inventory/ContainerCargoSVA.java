package com.sva.inventory;

import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CrawlerMath;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Vehicle cargo + the player's inventory. Up to 9 slots: the dispenser layout (3x3). More: the large chest layout,
 * 6 rows of 9 a page (54 / 108 slots: two pages - the crawler's body and its trailer, the drill's two bunker halves);
 * the slots of the other page are moved off screen (setPage, client). The drill vehicle has a strip on the left with
 * its drill head and bore module slots (SIDE wide).
 */
public class ContainerCargoSVA extends Container {

    /** The drill's side strip width (px). */
    public static final int SIDE = 26;
    private static final int OFF = -10000;
    private final EntityVehicleSVA vehicle;
    private final int cargo, extra;
    /** Chest layout (else the dispenser one), rows on a page, x offset of the chest part. */
    public final boolean chest;
    public final int rows, xOff;
    private int page;

    public ContainerCargoSVA(InventoryPlayer inv, EntityVehicleSVA v) {
        this.vehicle = v;
        int size = v.getSizeInventory();
        this.extra = v.getVehicleType().hasDrill ? 2 : 0;
        this.cargo = size - extra;
        this.chest = cargo > 9 || extra > 0;
        this.rows = chest ? Math.max(1, Math.min(6, (Math.min(cargo, CrawlerMath.PAGE) + 8) / 9)) : 3;
        this.xOff = extra > 0 ? SIDE : 0;
        for (int i = 0; i < cargo; i++) {
            if (chest) {
                int k = i % CrawlerMath.PAGE;
                addSlotToContainer(new Cargo(v, i, xOff + 8 + (k % 9) * 18, 18 + (k / 9) * 18));
            } else {
                addSlotToContainer(new Cargo(v, i, 62 + (i % 3) * 18, 17 + (i / 3) * 18));
            }
        }
        if (extra > 0) {
            addSlotToContainer(new Cargo(v, cargo, 5, 18));         // drill head
            addSlotToContainer(new Cargo(v, cargo + 1, 5, 44));     // bore module
        }
        int invY = chest ? 103 + (rows - 4) * 18 : 84;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlotToContainer(new Slot(inv, c + r * 9 + 9, xOff + 8 + c * 18, invY + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlotToContainer(new Slot(inv, c, xOff + 8 + c * 18, invY + 58));
        }
        setPage(0);
    }

    public EntityVehicleSVA vehicle() {
        return vehicle;
    }

    public int cargoSlots() {
        return cargo;
    }

    public int pages() {
        return chest ? CrawlerMath.pages(cargo) : 1;
    }

    public int page() {
        return page;
    }

    /** Shows page p (client): the other page's cargo slots go off screen. */
    public void setPage(int p) {
        page = Math.max(0, Math.min(pages() - 1, p));
        if (!chest) {
            return;
        }
        for (int i = 0; i < cargo; i++) {
            Slot s = (Slot) inventorySlots.get(i);
            int k = i % CrawlerMath.PAGE;
            boolean shown = i / CrawlerMath.PAGE == page;
            s.xDisplayPosition = shown ? xOff + 8 + (k % 9) * 18 : OFF;
            s.yDisplayPosition = shown ? 18 + (k / 9) * 18 : OFF;
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer p) {
        return vehicle.isUseableByPlayer(p);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer p, int index) {
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return null;
        }
        ItemStack s = slot.getStack();
        ItemStack copy = s.copy();
        int own = cargo + extra;
        if (index < own) {
            if (!mergeItemStack(s, own, inventorySlots.size(), true)) {
                return null;
            }
        } else {
            boolean done = false;
            for (int k = cargo; k < own && !done; k++) {
                Slot x = (Slot) inventorySlots.get(k);
                if (!x.getHasStack() && vehicle.isItemValidForSlot(k, s)) {
                    x.putStack(s.splitStack(1));                // a head / module: one into its slot
                    done = true;
                }
            }
            if (!done && (!vehicle.isItemValidForSlot(0, s) || !mergeItemStack(s, 0, cargo, false))) {
                return null;
            }
        }
        if (s.stackSize == 0) {
            slot.putStack(null);
        } else {
            slot.onSlotChanged();
        }
        return copy;
    }

    /** A cargo slot that asks the vehicle what it takes (no vehicles inside vehicles; heads / modules in their slots). */
    static class Cargo extends Slot {
        Cargo(IInventory inv, int i, int x, int y) {
            super(inv, i, x, y);
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return inventory.isItemValidForSlot(getSlotIndex(), s);
        }

        @Override
        public int getSlotStackLimit() {
            return inventory instanceof EntityVehicleSVA && ((EntityVehicleSVA) inventory).getVehicleType().hasDrill
                    && getSlotIndex() >= ((EntityVehicleSVA) inventory).headSlot() ? 1 : super.getSlotStackLimit();
        }
    }
}
