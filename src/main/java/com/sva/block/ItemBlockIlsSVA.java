package com.sva.block;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/**
 * Liner wave 4: the item of «Курсо-глиссадный маяк» - the TooltipSC tooltip (sva.tooltip.ils.summary / .details,
 * sva.tooltip.howto.ils). The item keeps no setting (the beacon's screen sets it once placed).
 */
public class ItemBlockIlsSVA extends ItemBlock {

    public ItemBlockIlsSVA(Block b) {
        super(b);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.ils.summary"));
        TooltipSC.more(list, Lang.tr("sva.tooltip.ils.details"), Lang.tr("sva.tooltip.howto.ils"));
    }
}
