package com.sva.block;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.vehicle.RoadMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** ★11 road blocks' item: one per variant (BlockRoadSVA.VARIANTS), the TooltipSC tooltip (summary + Shift / Ctrl). */
public class ItemBlockRoadSVA extends ItemBlock {

    public ItemBlockRoadSVA(Block b) {
        super(b);
        setHasSubtypes(true);
        setMaxDamage(0);
    }

    @Override
    public int getMetadata(int damage) {
        return BlockRoadSVA.variant(damage);
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return super.getUnlocalizedName() + "." + BlockRoadSVA.variant(s.getItemDamage());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        int v = BlockRoadSVA.variant(s.getItemDamage());
        boolean concrete = v == BlockRoadSVA.CONCRETE;
        double speed = concrete ? RoadMath.CONCRETE_SPEED : RoadMath.ASPHALT_SPEED;
        double wear = concrete ? RoadMath.CONCRETE_WEAR : RoadMath.ASPHALT_WEAR;
        list.add(Lang.tr("sva.tooltip.road.summary", Integer.valueOf((int) Math.round((speed - 1) * 100)),
                Integer.valueOf((int) Math.round((1 - wear) * 100))));
        if (v != BlockRoadSVA.ASPHALT && !concrete) {
            list.add(Lang.tr("sva.tooltip.road.marking." + v));
        }
        TooltipSC.more(list, Lang.tr("sva.tooltip.road.details"), Lang.tr("sva.tooltip.howto.road." + v));
    }
}
