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

public class ItemBlockGasColumnSVA extends ItemBlock {

    public ItemBlockGasColumnSVA(Block b) {
        super(b);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.column.summary", fmt(TileEntityGasColumnSVA.TANK), Integer.valueOf(TileEntityGasColumnSVA.RATE)));
        KeptDataSVA.tooltip(s, list);
        TooltipSC.more(list, Lang.tr("sva.tooltip.column.details", Integer.valueOf((int) TileEntityGasColumnSVA.RADIUS)),
                Lang.tr("sva.tooltip.howto.column"));
    }

    private static String fmt(long n) {
        return com.sva.FormatSVA.group(n);
    }
}
