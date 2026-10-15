package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.SVAMod;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * Wave C, item 41: «Справочник: Кремниевый век - Техника» - the addon's handbook. RMB opens it (client.GuiBookSVA: two-page
 * spreads, the contents, the search, bookmarks, the live recipes / models / schematics / checklists / the tutorial). Given
 * once on a player's first join (book.BookGiveSVA, config book.giveOnJoin); crafted from a vanilla book and the core's
 * metallurgical silicon.
 */
public class ItemBookSVA extends Item {

    public ItemBookSVA() {
        setMaxStackSize(1);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".handbook");
        setTextureName(ReferenceSVA.ASSETS + ":handbook");
    }

    @Override
    public ItemStack onItemRightClick(ItemStack s, World w, EntityPlayer p) {
        if (w.isRemote) {
            SVAMod.proxy.openBook(null);
        }
        return s;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.book.summary"));
        TooltipSC.more(list, Lang.tr("sva.tooltip.book.details"), Lang.tr("sva.tooltip.howto.book"));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister r) {
        itemIcon = r.registerIcon(ReferenceSVA.ASSETS + ":handbook");
    }
}
