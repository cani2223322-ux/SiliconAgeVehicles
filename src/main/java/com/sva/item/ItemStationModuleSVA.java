package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

/**
 * Charging station modules (plan §9 ★). Meta APPEND-ONLY: 0 gas («Модуль газа»: refuels from a Gas Column touching the
 * station or a plate), 1 repair («Модуль ремонта»: steel ingots from a chest beside it), 2 access («Модуль доступа»:
 * only the station owner's vehicles), 3 fast charge («Быстрая зарядка»: the station charges x2; one counts, the SV station
 * has it built in). Made on the Engineering Workbench (category «Модули»).
 */
public class ItemStationModuleSVA extends Item {

    public static final int GAS = 0, REPAIR = 1, ACCESS = 2, FAST = 3;
    public static final String[] KEYS = {"gas", "repair", "access", "fast"};
    private static final String[] ICONS = {"moduleGas", "moduleRepair", "moduleAccess", "moduleFast"};

    private IIcon[] icons;

    public ItemStationModuleSVA() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(1);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".module");
    }

    public static int kind(ItemStack s) {
        int m = s == null ? -1 : s.getItemDamage();
        return m >= 0 && m < KEYS.length ? m : -1;
    }

    public ItemStack stackOf(int kind) {
        return new ItemStack(this, 1, kind);
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        int k = kind(s);
        return getUnlocalizedName() + "." + (k < 0 ? KEYS[0] : KEYS[k]);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        int k = Math.max(0, kind(s));
        list.add(Lang.tr("sva.tooltip.module." + KEYS[k]));
        TooltipSC.more(list, Lang.tr("sva.tooltip.module." + KEYS[k] + ".details"), Lang.tr("sva.tooltip.howto.module"));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (int i = 0; i < KEYS.length; i++) {
            list.add(new ItemStack(item, 1, i));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        icons = new IIcon[ICONS.length];
        for (int i = 0; i < ICONS.length; i++) {
            icons[i] = r.registerIcon(ReferenceSVA.ASSETS + ":" + ICONS[i]);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        return icons[meta >= 0 && meta < icons.length ? meta : 0];
    }
}
