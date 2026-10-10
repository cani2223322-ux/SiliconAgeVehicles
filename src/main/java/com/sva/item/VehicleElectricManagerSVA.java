package com.sva.item;

import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItemManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

/**
 * ТБ-4: IC2's view of a vehicle item (only loaded with IC2 - see ItemVehicleSVA.getManager), so IC2's chargers and
 * the core's energy storages / charge pads (they charge foreign items through IC2) top it up like a suit. The charge
 * stays the vehicle state's own (ItemVehicleSVA.charge writes only that key). IC2's rules: a charger below the
 * vehicle's energy block tier gives nothing, a call moves at most that tier's packet. A vehicle is not a battery:
 * nothing is taken out of it.
 */
public class VehicleElectricManagerSVA implements IElectricItemManager {

    @Override
    public double charge(ItemStack stack, double amount, int tier, boolean ignoreTransferLimit, boolean simulate) {
        if (!ItemVehicleSVA.isVehicle(stack) || amount <= 0 || tier < ItemVehicleSVA.ic2Tier(stack)) {
            return 0;
        }
        double limit = ignoreTransferLimit ? amount : Math.min(amount, ItemVehicleSVA.transferLimit(stack));
        return ItemVehicleSVA.charge(stack, limit, simulate);
    }

    @Override
    public double discharge(ItemStack stack, double amount, int tier, boolean ignoreTransferLimit, boolean externally, boolean simulate) {
        return 0;
    }

    @Override
    public double getCharge(ItemStack stack) {
        return ItemVehicleSVA.isVehicle(stack) ? ItemVehicleSVA.chargeOf(stack) : 0;
    }

    @Override
    public boolean canUse(ItemStack stack, double amount) {
        return false;
    }

    @Override
    public boolean use(ItemStack stack, double amount, EntityLivingBase entity) {
        return false;
    }

    @Override
    public void chargeFromArmor(ItemStack stack, EntityLivingBase entity) {
        if (ElectricItem.rawManager != null && ElectricItem.rawManager != this) {
            ElectricItem.rawManager.chargeFromArmor(stack, entity);
        }
    }

    @Override
    public String getToolTip(ItemStack stack) {
        return "";                                          // the item's own tooltip shows the charge
    }
}
