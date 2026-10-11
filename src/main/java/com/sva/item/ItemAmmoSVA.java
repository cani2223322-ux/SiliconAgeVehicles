package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.FormatSVA;
import com.sva.ReferenceSVA;
import com.sva.airdefense.BallisticsMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

/**
 * Air-defence wave: the launchers' ammunition (meta, APPEND-ONLY): SHELLS «Снаряды ПВО» (the AA gun: one item a burst
 * of BallisticsMath.GUN_BURST rounds; crafting table: iron, gunpowder, the core's steel) and MISSILE «Зенитная ракета»
 * (the missile system: one a launch, one a tube; crafting table: the core's steel casings, controller, sensor,
 * gunpowder, blaze powder). Into a launcher: its screen's slots or Shift+RMB on it, hoppers too.
 */
public class ItemAmmoSVA extends Item {

    public static final int SHELLS = 0, MISSILE = 1, KINDS = 2;
    public static final String[] KEYS = {"aaShells", "samMissile"};

    private IIcon[] icons;

    public ItemAmmoSVA() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".ammo");
    }

    public ItemStack stackOf(int kind, int n) {
        return new ItemStack(this, n, kind);
    }

    @Override
    public int getItemStackLimit(ItemStack s) {
        return s != null && s.getItemDamage() == MISSILE ? 16 : 64;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        int m = s.getItemDamage();
        return getUnlocalizedName() + "." + KEYS[m >= 0 && m < KINDS ? m : 0];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        if (s.getItemDamage() == MISSILE) {
            list.add(Lang.tr("sva.tooltip.ammo.samMissile", FormatSVA.group(Math.round(BallisticsMath.MISSILE_RANGE)),
                    FormatSVA.group(Math.round(BallisticsMath.MISSILE_DAMAGE))));
            TooltipSC.more(list, Lang.tr("sva.tooltip.ammo.samMissile.details"), Lang.tr("sva.tooltip.howto.ammo"));
        } else {
            list.add(Lang.tr("sva.tooltip.ammo.aaShells", Integer.valueOf(BallisticsMath.GUN_BURST), FormatSVA.group(Math.round(BallisticsMath.GUN_RANGE))));
            TooltipSC.more(list, Lang.tr("sva.tooltip.ammo.aaShells.details", Integer.valueOf(Math.round(BallisticsMath.SHELL_DAMAGE))),
                    Lang.tr("sva.tooltip.howto.ammo"));
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
}
