package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.vehicle.BatteryMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

/**
 * Hydrogen wave (plan-next §3, mock-ups docs/mockups/veh_fuel_3_battery.png, veh_fuel_4_display_wear.png): the battery's
 * consumables and tools, meta APPEND-ONLY - «Электролит» (one = one service: the garage's «Обслужить АКБ» or the kit),
 * «Сервисный набор АКБ» (right-click a vehicle with its engine off: 5 s, +15 % health for an electrolyte from the
 * inventory) and «Пусковые провода» (right-click the donor, then your vehicle within 6 blocks: up to 25 % of the battery
 * in 10 s). The vehicle does the work (EntityVehicleSVA.interactFirst); the cables keep the donor in their NBT.
 */
public class ItemBatteryToolSVA extends Item {

    public static final int ELECTROLYTE = 0, KIT = 1, CABLES = 2, KINDS = 3;
    /** Lang / icon keys (APPEND-ONLY with the metas). */
    public static final String[] KEYS = {"electrolyte", "batteryKit", "jumpCables"};
    /** The cables' NBT: the donor vehicle's entity id and its vehicle id. */
    public static final String K_DONOR = "Donor", K_DONOR_ID = "DonorVeh";

    private IIcon[] icons;

    public ItemBatteryToolSVA() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".batteryTool");
    }

    /** The kind of a stack (-1: not one of these). */
    public static int kind(ItemStack s) {
        if (s == null || !(s.getItem() instanceof ItemBatteryToolSVA)) {
            return -1;
        }
        int m = s.getItemDamage();
        return m >= 0 && m < KINDS ? m : -1;
    }

    public ItemStack stackOf(int kind, int n) {
        return new ItemStack(this, n, kind);
    }

    @Override
    public int getItemStackLimit(ItemStack s) {
        return kind(s) == ELECTROLYTE ? 16 : 1;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        int k = kind(s);
        return getUnlocalizedName() + "." + KEYS[k < 0 ? 0 : k];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        int k = Math.max(0, kind(s));
        String key = "sva.tooltip." + KEYS[k];
        switch (k) {
            case KIT:
                list.add(Lang.tr(key, Integer.valueOf((int) Math.round(BatteryMath.KIT_GAIN * 100))));
                TooltipSC.more(list, Lang.tr(key + ".details", Integer.valueOf(BatteryMath.KIT_TICKS / 20),
                        Integer.valueOf((int) Math.round(BatteryMath.CAP_DROP * 100))), Lang.tr("sva.tooltip.howto." + KEYS[k]));
                break;
            case CABLES:
                list.add(Lang.tr(key, Integer.valueOf((int) Math.round(BatteryMath.JUMP_SHARE * 100)), Integer.valueOf(BatteryMath.JUMP_TICKS / 20)));
                if (s.hasTagCompound() && s.getTagCompound().hasKey(K_DONOR)) {
                    list.add(Lang.tr("sva.tooltip.jumpCables.donor"));
                }
                TooltipSC.more(list, Lang.tr(key + ".details", Integer.valueOf((int) Math.round(BatteryMath.DONOR_MIN * 100)),
                        Integer.valueOf((int) BatteryMath.JUMP_RANGE)), Lang.tr("sva.tooltip.howto." + KEYS[k]));
                break;
            default:
                list.add(Lang.tr(key));
                TooltipSC.more(list, Lang.tr(key + ".details", com.sva.FormatSVA.group(BatteryMath.GARAGE_EU)), Lang.tr("sva.tooltip.howto." + KEYS[k]));
                break;
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (int i = 0; i < KINDS; i++) {
            list.add(new ItemStack(item, 1, i));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        icons = new IIcon[KINDS];
        for (int i = 0; i < KINDS; i++) {
            icons[i] = r.registerIcon(ReferenceSVA.ASSETS + ":" + KEYS[i]);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        return icons[meta >= 0 && meta < KINDS ? meta : 0];
    }

    /** One electrolyte out of the player's inventory (creative: free). @return it was there */
    public static boolean takeElectrolyte(EntityPlayer p) {
        if (p.capabilities.isCreativeMode) {
            return true;
        }
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            ItemStack s = p.inventory.mainInventory[i];
            if (kind(s) == ELECTROLYTE) {
                s.stackSize--;
                if (s.stackSize <= 0) {
                    p.inventory.mainInventory[i] = null;
                }
                p.inventory.markDirty();
                return true;
            }
        }
        return false;
    }
}
