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

/** Wave 2: garage floor plates - the lift pad and the lamp plate (TooltipSC). */
public class ItemBlockGaragePlateSVA extends ItemBlock {

    public ItemBlockGaragePlateSVA(Block b) {
        super(b);
        setHasSubtypes(true);
        setMaxDamage(0);
    }

    @Override
    public int getMetadata(int damage) {
        return BlockGaragePlateSVA.variant(damage);
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return super.getUnlocalizedName() + "." + BlockGaragePlateSVA.variant(s.getItemDamage());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.garagePlate.summary"));
        if (BlockGaragePlateSVA.variant(s.getItemDamage()) == BlockGaragePlateSVA.LAMP) {
            list.add(Lang.tr("sva.tooltip.garagePlate.lamp"));
        }
        TooltipSC.more(list, Lang.tr("sva.tooltip.garagePlate.details", Integer.valueOf(com.sva.platform.PlatformMath.MAX_PLATES)),
                Lang.tr("sva.tooltip.howto.garagePlate"));
    }
}
