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

public class ItemBlockChargerSVA extends ItemBlock {

    public ItemBlockChargerSVA(Block b) {
        super(b);
        setHasSubtypes(true);
        setMaxDamage(0);
    }

    @Override
    public int getMetadata(int damage) {
        return damage;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        String k = BlockChargerSVA.nameKey(BlockChargerSVA.tierFor(s.getItemDamage()));
        return k.substring(0, k.length() - ".name".length());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        com.sc.energy.Tier t = BlockChargerSVA.tierFor(s.getItemDamage());
        boolean sv = t == com.sc.energy.Tier.SV;
        // the SV station has «Быстрая зарядка» built in: x2
        long rate = Math.round(t.getVoltage() * (double) com.sva.net.ServerConfigSVA.chargerRate() * (sv ? com.sva.platform.PlatformMath.FAST_MULT : 1));
        list.add(Lang.tr("sva.tooltip.charger.summary", t.name(), com.sva.FormatSVA.group(rate)));
        if (sv) {
            list.add(Lang.tr("sva.tooltip.charger.sv"));
        }
        KeptDataSVA.tooltip(s, list);
        TooltipSC.more(list, Lang.tr("sva.tooltip.charger.details", t.name(), com.sva.FormatSVA.group(t.getVoltage())) + " "
                + Lang.tr("sva.tooltip.charger.fast", com.sva.FormatSVA.group(com.sva.platform.PlatformMath.MAX_PLATES)),
                Lang.tr("sva.tooltip.howto.charger"));
    }
}
