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

public class ItemBlockRunwaySVA extends ItemBlock {

    public ItemBlockRunwaySVA(Block b) {
        super(b);
        setHasSubtypes(true);
        setMaxDamage(0);
    }

    @Override
    public int getMetadata(int damage) {
        return BlockRunwaySVA.variant(damage);
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return super.getUnlocalizedName() + "." + BlockRunwaySVA.variant(s.getItemDamage());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.runway.summary"));
        if (BlockRunwaySVA.variant(s.getItemDamage()) == BlockRunwaySVA.LIT) {
            list.add(Lang.tr("sva.tooltip.runway.lit", Integer.valueOf(BlockRunwaySVA.LIGHT)));
        }
        TooltipSC.more(list, Lang.tr("sva.tooltip.runway.details"), Lang.tr("sva.tooltip.howto.runway"));
    }
}
