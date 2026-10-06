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

/** Vehicle components (meta = ComponentType ordinal). Made only on the Engineering Workbench. */
public class ItemComponentSVA extends Item {

    private IIcon[] icons;

    public ItemComponentSVA() {
        setHasSubtypes(true);
        setMaxDamage(0);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".component");
    }

    public static ComponentType typeOf(ItemStack s) {
        return ComponentType.byMeta(s.getItemDamage());
    }

    public ItemStack stackOf(ComponentType t, int n) {
        return new ItemStack(this, n, t.meta());
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return getUnlocalizedName() + "." + typeOf(s).key;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        ComponentType t = typeOf(s);
        list.add(Lang.tr("sva.tooltip.component.summary", t.tier.name()));
        list.add(Lang.tr("sva.tooltip.component.for", Lang.tr("sva.component.for." + t.key)));
        TooltipSC.more(list, Lang.tr("sva.tooltip.component.details"), null);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List list) {
        for (ComponentType t : ComponentType.values()) {
            list.add(new ItemStack(item, 1, t.meta()));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        icons = new IIcon[ComponentType.values().length];
        for (ComponentType t : ComponentType.values()) {
            icons[t.ordinal()] = r.registerIcon(ReferenceSVA.ASSETS + ":" + t.key);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int meta) {
        return icons[ComponentType.byMeta(meta).ordinal()];
    }
}
