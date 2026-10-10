package com.sva.block;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.FormatSVA;
import com.sva.platform.PlatformMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

public class ItemBlockSupercapSVA extends ItemBlock {

    public ItemBlockSupercapSVA(Block b) {
        super(b);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.supercap.summary", FormatSVA.group(PlatformMath.CAP_BUFFER), FormatSVA.group(PlatformMath.CAP_DUMP)));
        KeptDataSVA.tooltip(s, list);
        TooltipSC.more(list, Lang.tr("sva.tooltip.supercap.details", FormatSVA.group(PlatformMath.CAP_INPUT), FormatSVA.group(PlatformMath.CAP_DUMP)),
                Lang.tr("sva.tooltip.howto.supercap"));
    }
}
