package com.sva.item;

import java.util.ArrayList;
import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

/**
 * Wave 2, ★8: tuning upgrades (meta = TuningMath kind, APPEND-ONLY). Made on the Engineering Workbench («Модули»),
 * installed into / taken out of a vehicle only in the repair garage's tuning slots (up to 4, TuningMath rules).
 */
public class ItemTuningSVA extends Item {

    private IIcon[] icons;

    public ItemTuningSVA() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(1);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".tuning");
    }

    /** The kind of a tuning stack, -1 if it isn't one. */
    public static int kind(ItemStack s) {
        if (s == null || !(s.getItem() instanceof ItemTuningSVA)) {
            return -1;
        }
        int m = s.getItemDamage();
        return TuningMath.valid(m) ? m : -1;
    }

    public ItemStack stackOf(int kind) {
        return new ItemStack(this, 1, kind);
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        int k = kind(s);
        return getUnlocalizedName() + "." + TuningMath.KEYS[k < 0 ? 0 : k];
    }

    /** Names of the vehicles this kind fits (client tooltip). */
    @SideOnly(Side.CLIENT)
    static String fitsText(int kind) {
        List<String> n = new ArrayList<String>();
        for (VehicleType t : VehicleType.values()) {
            if (TuningMath.fits(t, kind)) {
                n.add(Lang.tr("item.siliconagevehicles.vehicle." + t.key + ".name"));
            }
        }
        if (n.size() == VehicleType.values().length) {
            return Lang.tr("sva.tooltip.tuning.fitsAll");
        }
        StringBuilder b = new StringBuilder();
        for (String s : n) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(s);
        }
        return b.toString();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        int k = Math.max(0, kind(s));
        list.add(Lang.tr("sva.tooltip.tuning." + TuningMath.KEYS[k], new Object[0]));      // formatted: "%%"
        TooltipSC.more(list, Lang.tr("sva.tooltip.tuning." + TuningMath.KEYS[k] + ".details", new Object[0]) + "\n"
                        + Lang.tr("sva.tooltip.tuning.fits", fitsText(k)),
                Lang.tr("sva.tooltip.howto.tuning", Integer.valueOf(TuningMath.MAX_SLOTS)));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (int i = 0; i < TuningMath.KINDS; i++) {
            list.add(new ItemStack(item, 1, i));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        icons = new IIcon[TuningMath.KINDS];
        for (int i = 0; i < TuningMath.KINDS; i++) {
            icons[i] = r.registerIcon(ReferenceSVA.ASSETS + ":tuning_" + TuningMath.KEYS[i]);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        return icons[TuningMath.valid(meta) ? meta : 0];
    }
}
