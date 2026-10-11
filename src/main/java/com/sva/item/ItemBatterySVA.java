package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.FuelMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;

/**
 * Hydrogen wave (plan-next §3, mock-up docs/mockups/veh_fuel_3_battery.png): a vehicle's removable board battery. The
 * meta is the VehicleType ordinal - one kind per hydrogen vehicle, no levels, another type's does not fit. Out of the
 * vehicle the NBT keeps the battery (BatteryMath.Battery: charge, health, limit, services); in the vehicle the state
 * does. Like the vehicle item it is an IC2 electric item (BatteryElectricManagerSVA) - the core's energy storages and
 * IC2's chargers top it up at its tier's packet a tick; it never gives charge back to a network.
 */
@cpw.mods.fml.common.Optional.Interface(iface = "ic2.api.item.ISpecialElectricItem", modid = com.sc.Reference.IC2_MODID)
public class ItemBatterySVA extends Item implements ic2.api.item.ISpecialElectricItem {

    private IIcon[] icons;
    /** The IC2 manager (Object: IC2's type is only loaded with IC2). */
    private static Object ic2Manager;

    public ItemBatterySVA() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(1);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".battery");
    }

    /** The vehicle type a battery stack is for, or null (not a battery / not a hydrogen type). */
    public static VehicleType typeOf(ItemStack s) {
        if (s == null || !(s.getItem() instanceof ItemBatterySVA)) {
            return null;
        }
        VehicleType t = VehicleType.byOrdinal(s.getItemDamage());
        return FuelMath.uses(t) && t.ordinal() == s.getItemDamage() ? t : null;
    }

    public static boolean isBattery(ItemStack s) {
        return typeOf(s) != null;
    }

    /** It is this type's battery (the slot's rule). */
    public static boolean fits(ItemStack s, VehicleType t) {
        return t != null && typeOf(s) == t;
    }

    /** The battery a stack holds (a plain crafted one: empty, 100 %). */
    public static BatteryMath.Battery batteryOf(ItemStack s) {
        VehicleType t = typeOf(s);
        BatteryMath.Battery b = new BatteryMath.Battery();
        return t == null ? b : b.read(s.getTagCompound(), BatteryMath.capacity(t));
    }

    /** A stack of type t's battery holding b. */
    public static ItemStack stackOf(Item item, VehicleType t, BatteryMath.Battery b) {
        ItemStack s = new ItemStack(item, 1, t.ordinal());
        NBTTagCompound tag = new NBTTagCompound();
        b.write(tag);
        s.setTagCompound(tag);
        return s;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        VehicleType t = typeOf(s);
        return getUnlocalizedName() + "." + (t == null ? "buggy" : t.key);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        VehicleType t = typeOf(s);
        if (t == null) {
            return;
        }
        BatteryMath.Battery b = batteryOf(s);
        double cap = BatteryMath.capacity(t);
        list.add(Lang.tr("sva.tooltip.bat.charge", fmt((long) b.charge), fmt((long) cap)));
        list.add(Lang.tr("sva.tooltip.bat.tier", BatteryMath.tier(t).name()));
        list.add(Lang.tr("sva.tooltip.bat.for", Lang.tr("item.siliconagevehicles.vehicle." + t.key + ".name")));
        int hp = (int) Math.round(b.health * 100);
        list.add(Lang.tr(BatteryMath.needsService(b.health) ? "sva.tooltip.bat.healthLow" : "sva.tooltip.bat.health", Integer.valueOf(hp)));
        if (b.services > 0) {
            list.add(Lang.tr("sva.tooltip.bat.services", Integer.valueOf(b.services), Integer.valueOf((int) Math.round(b.limit * 100))));
        }
        TooltipSC.more(list, Lang.tr("sva.tooltip.bat.details", Lang.tr("item.siliconagevehicles.vehicle." + t.key + ".name"),
                BatteryMath.tier(t).name()), Lang.tr("sva.tooltip.howto.bat"));
    }

    static String fmt(long n) {
        return com.sva.FormatSVA.group(n);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (VehicleType t : VehicleType.values()) {
            if (FuelMath.uses(t)) {
                BatteryMath.Battery full = new BatteryMath.Battery();
                full.charge = BatteryMath.capacity(t);
                list.add(stackOf(item, t, full));
            }
        }
    }

    @Override
    public boolean showDurabilityBar(ItemStack s) {
        return isBattery(s);
    }

    @Override
    public double getDurabilityForDisplay(ItemStack s) {
        VehicleType t = typeOf(s);
        return t == null ? 1 : 1 - batteryOf(s).charge / Math.max(1, BatteryMath.capacity(t));
    }

    // ---- charged as an item (IC2: the core's storages and IC2's chargers)

    /** EU it holds. */
    public static double chargeOf(ItemStack s) {
        return isBattery(s) ? batteryOf(s).charge : 0;
    }

    public static double capacityOf(ItemStack s) {
        return BatteryMath.capacity(typeOf(s));
    }

    /** EU one call may move: its tier's packet. */
    public static int transferLimit(ItemStack s) {
        VehicleType t = typeOf(s);
        return t == null ? 0 : BatteryMath.tier(t).getVoltage();
    }

    public static int ic2Tier(ItemStack s) {
        VehicleType t = typeOf(s);
        return t == null ? 1 : BatteryMath.tier(t).toIc2Tier();
    }

    /**
     * Adds up to `max` EU (server): the charge and the cycle wear (BatteryMath.cycleWear, with vehicles.batteryWear) in
     * the stack's NBT. @return EU taken
     */
    public static long charge(ItemStack s, double max, boolean simulate) {
        VehicleType t = typeOf(s);
        if (t == null || s.stackSize != 1 || !(max > 0)) {
            return 0;
        }
        BatteryMath.Battery b = batteryOf(s);
        double cap = BatteryMath.capacity(t);
        long moved = (long) Math.max(0, Math.min(Math.floor(cap - b.charge), Math.floor(max)));
        if (!simulate && moved > 0) {
            b.charge += moved;
            if (com.sva.ConfigSVA.batteryWear) {
                b.health = BatteryMath.cycleWear(b.health, moved, cap);
            }
            b.cycle += moved;
            if (b.deep && b.charge > cap * BatteryMath.DEEP_RESET) {
                b.deep = false;
            }
            if (!s.hasTagCompound()) {
                s.setTagCompound(new NBTTagCompound());
            }
            b.write(s.getTagCompound());
        }
        return moved;
    }

    // ---- IC2 (IElectricItem: plain methods, harmless without IC2; the manager only with it)

    @Override
    public boolean canProvideEnergy(ItemStack s) {
        return false;                                       // a vehicle battery does not feed the network
    }

    @Override
    public Item getChargedItem(ItemStack s) {
        return this;
    }

    @Override
    public Item getEmptyItem(ItemStack s) {
        return this;
    }

    @Override
    public double getMaxCharge(ItemStack s) {
        return capacityOf(s);
    }

    @Override
    public int getTier(ItemStack s) {
        return ic2Tier(s);
    }

    @Override
    public double getTransferLimit(ItemStack s) {
        return transferLimit(s);
    }

    @Override
    @cpw.mods.fml.common.Optional.Method(modid = com.sc.Reference.IC2_MODID)
    public ic2.api.item.IElectricItemManager getManager(ItemStack s) {
        if (ic2Manager == null) {
            ic2Manager = new BatteryElectricManagerSVA();
        }
        return (ic2.api.item.IElectricItemManager) ic2Manager;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        icons = new IIcon[VehicleType.values().length];
        for (VehicleType t : VehicleType.values()) {
            if (FuelMath.uses(t)) {
                icons[t.ordinal()] = r.registerIcon(ReferenceSVA.ASSETS + ":battery_" + t.key);
            }
        }
        icons[VehicleType.SHUTTLE.ordinal()] = icons[VehicleType.SHIP.ordinal()] = icons[VehicleType.BUGGY.ordinal()];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        IIcon i = icons[VehicleType.byOrdinal(meta).ordinal()];
        return i != null ? i : icons[0];
    }
}
