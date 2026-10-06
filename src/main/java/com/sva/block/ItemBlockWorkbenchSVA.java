package com.sva.block;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.workbench.WorkbenchMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

public class ItemBlockWorkbenchSVA extends ItemBlock {

    public ItemBlockWorkbenchSVA(Block b) {
        super(b);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.workbench.summary", WorkbenchMath.BASE_TIER.name(), Integer.valueOf(WorkbenchMath.BASE_TIER.getVoltage()),
                com.sva.FormatSVA.eu(WorkbenchMath.BASE_BUFFER)));
        TooltipSC.more(list, Lang.tr("sva.tooltip.workbench.details1") + "\n" + Lang.tr("sva.tooltip.workbench.details2"),
                Lang.tr("sva.tooltip.howto.workbench1") + "\n" + Lang.tr("sva.tooltip.howto.workbench2"));
    }
}
