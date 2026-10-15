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
 * Airport wave B2: the item of «Огонь рулёжки» (one per kind: centre line, edge, stop bar, stand) with its TooltipSC
 * tooltip (sva.tooltip.apt.taxi.KEY.summary / .details, sva.tooltip.howto.apt.taxi).
 */
public class ItemBlockTaxiLightSVA extends ItemBlock {

    public ItemBlockTaxiLightSVA(Block b) {
        super(b);
        setHasSubtypes(true);
        setMaxDamage(0);
    }

    static String key(ItemStack s) {
        return BlockTaxiLightSVA.KEYS[Math.max(0, Math.min(BlockTaxiLightSVA.KINDS - 1, s.getItemDamage()))];
    }

    @Override
    public int getMetadata(int meta) {
        return meta;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return super.getUnlocalizedName() + "." + key(s);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        String k = key(s);
        list.add(Lang.tr("sva.tooltip.apt.taxi." + k + ".summary"));
        TooltipSC.more(list, Lang.tr("sva.tooltip.apt.taxi." + k + ".details"), Lang.tr("sva.tooltip.howto.apt.taxi"));
    }
}
