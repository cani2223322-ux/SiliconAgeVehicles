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

public class ItemBlockChargePlateSVA extends ItemBlock {

    public ItemBlockChargePlateSVA(Block b) {
        super(b);
        setHasSubtypes(true);
        setMaxDamage(0);
    }

    @Override
    public int getMetadata(int damage) {
        return BlockChargePlateSVA.variant(damage);
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return super.getUnlocalizedName() + "." + BlockChargePlateSVA.variant(s.getItemDamage());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        int v = BlockChargePlateSVA.variant(s.getItemDamage());
        list.add(Lang.tr("sva.tooltip.plate.summary"));
        if (v != BlockChargePlateSVA.PLAIN) {
            list.add(Lang.tr("sva.tooltip.plate." + v));
        }
        TooltipSC.more(list, Lang.tr("sva.tooltip.plate.details", Integer.valueOf(com.sva.platform.PlatformMath.MAX_PLATES)),
                Lang.tr("sva.tooltip.howto.plate"));
    }
}
