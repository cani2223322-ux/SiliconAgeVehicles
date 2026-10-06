package com.sva.inventory;

import com.sva.entity.EntityVehicleSVA;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Vehicle cargo (3x3, the dispenser layout) + the player's inventory. */
public class ContainerCargoSVA extends Container {

    private final EntityVehicleSVA vehicle;
    private final int cargo;

    public ContainerCargoSVA(InventoryPlayer inv, EntityVehicleSVA v) {
        this.vehicle = v;
        this.cargo = v.getSizeInventory();
        for (int i = 0; i < cargo; i++) {
            addSlotToContainer(new Cargo(v, i, 62 + (i % 3) * 18, 17 + (i / 3) * 18));
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlotToContainer(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 84 + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlotToContainer(new Slot(inv, c, 8 + c * 18, 142));
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
        if (index < cargo) {
            if (!mergeItemStack(s, cargo, inventorySlots.size(), true)) {
                return null;
            }
        } else {
            if (!vehicle.isItemValidForSlot(0, s) || !mergeItemStack(s, 0, cargo, false)) {
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

    /** A cargo slot that asks the vehicle what it takes (no vehicles inside vehicles). */
    static class Cargo extends Slot {
        Cargo(IInventory inv, int i, int x, int y) {
            super(inv, i, x, y);
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return inventory.isItemValidForSlot(getSlotIndex(), s);
        }
    }
}
