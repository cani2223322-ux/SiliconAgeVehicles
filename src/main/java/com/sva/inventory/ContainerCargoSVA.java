package com.sva.inventory;

import com.sva.entity.DrillLogicSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CrawlerMath;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Vehicle cargo + the player's inventory for the cargo screen v2 (GuiCargoSVA, mock-ups docs/mockups/veh_12_cargo_*):
 * a 214 x 236 window, the cargo as a 9-wide grid of up to 6 rows a page (54 / 108 slots: the crawler's body and its
 * trailer, the drill's two bunker halves), the drill's head and module slots on its «Бур» tab. Slots that are not on
 * the shown tab / page are moved off screen (setView, client); the server keeps them where they are.
 */
public class ContainerCargoSVA extends Container {

    /** Window size and where things are (px from its top left). */
    public static final int W = 214, H = 236, GRID_X = 30, GRID_Y = 46, INV_Y = 157, HOTBAR_Y = 215, HEAD_X = 35, HEAD_Y = 23,
            MODULE_X = 35, MODULE_Y = 57;
    /** Tabs: the cargo, the drill / auto-delivery, the vehicle. */
    public static final int TAB_CARGO = 0, TAB_SPECIAL = 1, TAB_VEHICLE = 2;
    private static final int OFF = -10000;
    private final EntityVehicleSVA vehicle;
    private final int cargo, extra;
    private int page, tab;

    public ContainerCargoSVA(InventoryPlayer inv, EntityVehicleSVA v) {
        this.vehicle = v;
        int size = v.getSizeInventory();
        this.extra = v.getVehicleType().hasDrill ? 2 : 0;
        this.cargo = size - extra;
        for (int i = 0; i < cargo; i++) {
            int k = i % CrawlerMath.PAGE;
            addSlotToContainer(new Cargo(v, i, GRID_X + (k % 9) * 18, GRID_Y + (k / 9) * 18));
        }
        if (extra > 0) {
            addSlotToContainer(new Cargo(v, cargo, HEAD_X, HEAD_Y));         // drill head
            addSlotToContainer(new Cargo(v, cargo + 1, MODULE_X, MODULE_Y)); // bore module
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlotToContainer(new Slot(inv, c + r * 9 + 9, GRID_X + c * 18, INV_Y + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlotToContainer(new Slot(inv, c, GRID_X + c * 18, HOTBAR_Y));
        }
        setView(TAB_CARGO, 0);
    }

    public EntityVehicleSVA vehicle() {
        return vehicle;
    }

    public int cargoSlots() {
        return cargo;
    }

    public boolean hasDrillSlots() {
        return extra > 0;
    }

    /** Rows of the grid on a page (1..6). */
    public int rows() {
        return Math.max(1, Math.min(6, (Math.min(cargo, CrawlerMath.PAGE) + 8) / 9));
    }

    public int pages() {
        return CrawlerMath.pages(cargo);
    }

    public int page() {
        return page;
    }

    public int tab() {
        return tab;
    }

    /** Shows tab t, cargo page p (client): the cargo slots of the cargo tab's page, the drill slots on the drill tab. */
    public void setView(int t, int p) {
        tab = t;
        page = Math.max(0, Math.min(pages() - 1, p));
        for (int i = 0; i < cargo; i++) {
            Slot s = (Slot) inventorySlots.get(i);
            int k = i % CrawlerMath.PAGE;
            boolean shown = tab == TAB_CARGO && i / CrawlerMath.PAGE == page;
            s.xDisplayPosition = shown ? GRID_X + (k % 9) * 18 : OFF;
            s.yDisplayPosition = shown ? GRID_Y + (k / 9) * 18 : OFF;
        }
        if (extra > 0) {
            boolean drill = tab == TAB_SPECIAL;
            Slot h = (Slot) inventorySlots.get(cargo), m = (Slot) inventorySlots.get(cargo + 1);
            h.xDisplayPosition = drill ? HEAD_X : OFF;
            h.yDisplayPosition = drill ? HEAD_Y : OFF;
            m.xDisplayPosition = drill ? MODULE_X : OFF;
            m.yDisplayPosition = drill ? MODULE_Y : OFF;
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
            boolean headFree = extra > 0 && !((Slot) inventorySlots.get(cargo)).getHasStack();
            boolean moduleFree = extra > 0 && !((Slot) inventorySlots.get(cargo + 1)).getHasStack();
            int to = CargoToolsSVA.transferTarget(extra > 0, DrillLogicSVA.isHead(s), DrillLogicSVA.isWidener(s), headFree, moduleFree,
                    s.getItem() instanceof com.sva.item.ItemVehicleSVA);
            if (to == CargoToolsSVA.TO_HEAD || to == CargoToolsSVA.TO_MODULE) {
                ((Slot) inventorySlots.get(to == CargoToolsSVA.TO_HEAD ? cargo : cargo + 1)).putStack(s.splitStack(1));   // one into its slot
            } else if (to == CargoToolsSVA.TO_NONE || !mergeItemStack(s, 0, cargo, false)) {
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
