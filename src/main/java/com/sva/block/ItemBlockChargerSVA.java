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
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        com.sc.energy.Tier t = BlockChargerSVA.tierFor(s.getItemDamage());
        list.add(Lang.tr("sva.tooltip.charger.summary", t.name(), Integer.valueOf(Math.round(t.getVoltage() * com.sva.net.ServerConfigSVA.chargerRate()))));
        KeptDataSVA.tooltip(s, list);
        TooltipSC.more(list, Lang.tr("sva.tooltip.charger.details", t.name(), Integer.valueOf(t.getVoltage())), Lang.tr("sva.tooltip.howto.charger"));
    }
}
