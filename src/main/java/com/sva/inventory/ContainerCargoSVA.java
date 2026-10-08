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
    /** Tabs: the cargo, the drill / auto-delivery, the vehicle; ★15 the trailer (tanker / platform / charger). */
    public static final int TAB_CARGO = 0, TAB_SPECIAL = 1, TAB_VEHICLE = 2, TAB_TRAILER = 3;
    /** ★15: the tanker's «in» / «out» container slots on the «Прицеп» tab. */
    public static final int TANK_IN_X = 66, TANK_OUT_X = 112, TANK_Y = 74;
    private static final int OFF = -10000;
    private final EntityVehicleSVA vehicle;
    private final int cargo, extra;
    private int page, tab;

    public ContainerCargoSVA(InventoryPlayer inv, EntityVehicleSVA v) {
        this.vehicle = v;
        int size = v.getSizeInventory();
        this.extra = v.extraSlots();                              // the drill's head / module, ★15 the tanker's in / out
        this.cargo = size - extra;
        for (int i = 0; i < cargo; i++) {
            int k = i % CrawlerMath.PAGE;
            addSlotToContainer(new Cargo(v, i, GRID_X + (k % 9) * 18, GRID_Y + (k / 9) * 18));
        }
        if (extra > 0) {
            addSlotToContainer(new Cargo(v, cargo, HEAD_X, HEAD_Y));         // drill head / ★15 tanker «in»
            addSlotToContainer(new Cargo(v, cargo + 1, MODULE_X, MODULE_Y)); // bore module / ★15 tanker «out»
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
        return extra > 0 && !tank();
    }

    /** ★15: the extra slots are the tanker's (a crawler with a tank trailer). */
    public boolean tank() {
        return extra > 0 && !vehicle.getVehicleType().hasDrill;
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
        if (extra > 0 && tank()) {
            boolean on = tab == TAB_TRAILER;
            Slot h = (Slot) inventorySlots.get(cargo), m = (Slot) inventorySlots.get(cargo + 1);
            h.xDisplayPosition = on ? TANK_IN_X : OFF;
            h.yDisplayPosition = on ? TANK_Y : OFF;
            m.xDisplayPosition = on ? TANK_OUT_X : OFF;
            m.yDisplayPosition = on ? TANK_Y : OFF;
        } else if (extra > 0) {
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
        } else if (tank() && com.sc.util.FluidHandSC.isContainer(s)) {
            // ★15: a full container into «in», an empty one into «out» (one at a time), else into the cargo
            Slot to = (Slot) inventorySlots.get(fullContainer(s) ? cargo : cargo + 1);
            if (!to.getHasStack()) {
                to.putStack(s.splitStack(1));
            } else if (!mergeItemStack(s, 0, cargo, false)) {
                return null;
            }
        } else {
            boolean headFree = extra > 0 && !((Slot) inventorySlots.get(cargo)).getHasStack();
            boolean moduleFree = extra > 0 && !((Slot) inventorySlots.get(cargo + 1)).getHasStack();
            int to = CargoToolsSVA.transferTarget(extra > 0, DrillLogicSVA.isHead(s), DrillLogicSVA.isWidener(s), headFree, moduleFree,
                    !EntityVehicleSVA.cargoAllows(s));                // mergeItemStack skips isItemValid: no loaded trailers etc.
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

    /** ★15: a container that holds fluid (registry filled container, or a tank-in-an-item with some in it). */
    static boolean fullContainer(ItemStack s) {
        if (net.minecraftforge.fluids.FluidContainerRegistry.isFilledContainer(s)) {
            return true;
        }
        if (s.getItem() instanceof net.minecraftforge.fluids.IFluidContainerItem) {
            net.minecraftforge.fluids.FluidStack f = ((net.minecraftforge.fluids.IFluidContainerItem) s.getItem()).getFluid(s);
            return f != null && f.amount > 0;
        }
        return false;
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
            return inventory instanceof EntityVehicleSVA && ((EntityVehicleSVA) inventory).extraSlots() > 0
                    && getSlotIndex() >= ((EntityVehicleSVA) inventory).headSlot() ? 1 : super.getSlotStackLimit();      // ★15 the tanker's too
        }
    }
}
