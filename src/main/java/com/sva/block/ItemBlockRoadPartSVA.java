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
 * ★11 items of the curb, the road signs, the traffic light and the guard rail: a flat icon (the block's own picture), the
 * TooltipSC tooltip from lang keys sva.tooltip.KEY.summary / .details and sva.tooltip.howto.KEY (signs: per picture -
 * KEY.N). Signs keep the picture as the item damage.
 */
public class ItemBlockRoadPartSVA extends ItemBlock {

    private final String key;
    private final boolean pictures;

    public ItemBlockRoadPartSVA(Block b) {
        super(b);
        pictures = b instanceof BlockRoadSignSVA;
        key = b instanceof BlockRoadSignSVA ? (((BlockRoadSignSVA) b).set == 0 ? "roadSign" : "roadSignArrow")
                : b instanceof BlockTrafficLightSVA ? "trafficLight" : b instanceof BlockGuardRailSVA ? "guardRail" : "curb";
        setHasSubtypes(pictures);
        setMaxDamage(0);
    }

    @Override
    public int getMetadata(int damage) {
        return pictures ? BlockRoadSignSVA.picture(damage) : 0;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return pictures ? super.getUnlocalizedName() + "." + BlockRoadSignSVA.picture(s.getItemDamage()) : super.getUnlocalizedName();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int damage) {
        return field_150939_a.getIcon(1, pictures ? BlockRoadSignSVA.picture(damage) : 0);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip." + key + ".summary"));
        String howto = "sva.tooltip.howto." + key + (pictures ? "." + BlockRoadSignSVA.picture(s.getItemDamage()) : "");
        TooltipSC.more(list, Lang.tr("sva.tooltip." + key + ".details"), Lang.tr(howto));
    }
}
