package com.sva.inventory;

import com.sva.entity.EntityVehicleSVA;
import com.sva.item.ItemBatterySVA;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * Hydrogen wave (mock-up docs/mockups/veh_fuel_3_battery.png): the «Аккумулятор» slot on the cargo screen's «Машина»
 * tab - a one-slot inventory over the vehicle's battery. On the server it reads / writes the vehicle (the battery out as
 * an item - EntityVehicleSVA.takeBattery / putBattery); on a client it only mirrors what the server's container sends
 * (the client never touches the vehicle's battery). Only this type's battery goes in; it comes out (and goes in) with the
 * engine off and standing, for the owner or the «Ремонт» right - EntityVehicleSVA.mayHandleBattery.
 */
public class BatterySlotSVA implements IInventory {

    private final EntityVehicleSVA vehicle;
    /** Client: the stack the server last sent. */
    private ItemStack shown;

    public BatterySlotSVA(EntityVehicleSVA v) {
        this.vehicle = v;
    }

    private boolean remote() {
        return vehicle.worldObj == null || vehicle.worldObj.isRemote;
    }

    @Override
    public int getSizeInventory() {
        return 1;
    }

    @Override
    public ItemStack getStackInSlot(int i) {
        return remote() ? shown : vehicle.batteryStack();
    }

    @Override
    public ItemStack decrStackSize(int i, int n) {
        if (n <= 0) {
            return null;
        }
        if (remote()) {
            ItemStack s = shown;
            shown = null;
            return s;
        }
        return vehicle.takeBattery();
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int i) {
        return null;                                         // it stays in the vehicle
    }

    @Override
    public void setInventorySlotContents(int i, ItemStack s) {
        if (remote()) {
            shown = s;
            return;
        }
        if (s == null) {
            vehicle.takeBattery();                            // taken by the container (its stack already moved)
        } else if (!vehicle.putBattery(s)) {
            vehicle.entityDropItem(s, 1F);                    // never lost
        }
    }

    @Override
    public String getInventoryName() {
        return "sva.battery";
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
        return vehicle.isUseableByPlayer(p);
    }

    @Override
    public void openInventory() {
    }

    @Override
    public void closeInventory() {
    }

    @Override
    public boolean isItemValidForSlot(int i, ItemStack s) {
        return ItemBatterySVA.fits(s, vehicle.getVehicleType()) && vehicle.onHydrogen();
    }

    /** The slot: this type's battery only, one, in / out by the rule above. */
    public static class BatterySlot extends Slot {
        private final EntityVehicleSVA vehicle;
        /** Who has the screen open (the container's player). */
        private final EntityPlayer player;

        public BatterySlot(BatterySlotSVA inv, EntityVehicleSVA v, EntityPlayer p, int x, int y) {
            super(inv, 0, x, y);
            this.vehicle = v;
            this.player = p;
        }

        @Override
        public boolean isItemValid(ItemStack s) {
            return getStack() == null && inventory.isItemValidForSlot(0, s) && vehicle.mayHandleBattery(player);
        }

        @Override
        public boolean canTakeStack(EntityPlayer p) {
            return vehicle.mayHandleBattery(p);
        }

        @Override
        public int getSlotStackLimit() {
            return 1;
        }
    }
}
