package com.sva.block;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.FormatSVA;
import com.sva.vehicle.GarageMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** Wave 2: the Repair Garage (TooltipSC: summary, Shift details, Ctrl how-to). */
public class ItemBlockGarageSVA extends ItemBlock {

    public ItemBlockGarageSVA(Block b) {
        super(b);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.garage.summary"));
        KeptDataSVA.tooltip(s, list);
        TooltipSC.more(list, Lang.tr("sva.tooltip.garage.details", FormatSVA.group((long) GarageMath.EU_PER_HP),
                        Integer.valueOf(GarageMath.STEEL_FULL), Integer.valueOf(GarageMath.IRON_PER_STEEL), FormatSVA.eu(GarageMath.BUFFER)),
                Lang.tr("sva.tooltip.howto.garage"));
    }
}
