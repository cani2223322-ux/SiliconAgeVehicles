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
import net.minecraft.util.IIcon;

/**
 * Stage D1: the items of «Огни ПАПИ» and «Огни приближения» - a flat icon, the TooltipSC tooltip (sva.tooltip.KEY.summary
 * / .details, sva.tooltip.howto.KEY). The item never keeps a setting (the block's metadata is set on placement).
 */
public class ItemBlockAirfieldSVA extends ItemBlock {

    private final String key;

    public ItemBlockAirfieldSVA(Block b) {
        super(b);
        key = b instanceof BlockPapiSVA ? "papi" : "approachLight";
        setMaxDamage(0);
    }

    @Override
    public int getMetadata(int damage) {
        return 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int damage) {
        return field_150939_a.getIcon(1, 0);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int getSpriteNumber() {
        return 0;                                            // the icon is on the blocks atlas
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip." + key + ".summary"));
        TooltipSC.more(list, Lang.tr("sva.tooltip." + key + ".details"), Lang.tr("sva.tooltip.howto." + key));
    }
}
