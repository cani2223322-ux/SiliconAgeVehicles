package com.sva.item;

import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItemManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

/**
 * Hydrogen wave: IC2's view of a vehicle battery item (only loaded with IC2 - see ItemBatterySVA.getManager), so the
 * core's energy storages and IC2's chargers top it up like any electric item of its tier (a charger below the tier
 * gives nothing, a call moves at most the tier's packet). It never gives its charge back (a vehicle battery, not a
 * network battery).
 */
public class BatteryElectricManagerSVA implements IElectricItemManager {

    @Override
    public double charge(ItemStack stack, double amount, int tier, boolean ignoreTransferLimit, boolean simulate) {
        if (!ItemBatterySVA.isBattery(stack) || amount <= 0 || tier < ItemBatterySVA.ic2Tier(stack)) {
            return 0;
        }
        double limit = ignoreTransferLimit ? amount : Math.min(amount, ItemBatterySVA.transferLimit(stack));
        return ItemBatterySVA.charge(stack, limit, simulate);
    }

    @Override
    public double discharge(ItemStack stack, double amount, int tier, boolean ignoreTransferLimit, boolean externally, boolean simulate) {
        return 0;
    }

    @Override
    public double getCharge(ItemStack stack) {
        return ItemBatterySVA.isBattery(stack) ? ItemBatterySVA.chargeOf(stack) : 0;
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
