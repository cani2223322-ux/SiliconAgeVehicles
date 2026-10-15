package com.sva.item;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.ReferenceSVA;
import com.sva.airport.ChartMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * Airport wave B2 (plan-next §9, item 34; mock-up veh_airport_impl_b2_4_chart): «Карта подхода» - printed by a tower on a
 * sheet of paper (TowerScreenSVA.print): its NBT holds the whole chart (airport.ChartMath: the airport, the runway, the ILS,
 * the fixes, the minimums, the lights, the tower's chart version then). RMB in hand opens it (client.GuiChartSVA); the
 * tooltip and the sheet say «УСТАРЕЛА» when the tower's version is no longer the chart's (the clients know the towers'
 * versions from the airport's sync) or the airport is gone. No recipe: only the tower makes them.
 */
public class ItemApproachChartSVA extends Item {

    public ItemApproachChartSVA() {
        setMaxStackSize(16);
        setUnlocalizedName(ReferenceSVA.ASSETS + ".approachChart");
        setTextureName(ReferenceSVA.ASSETS + ":approachChart");
    }

    public static ChartMath.Chart chart(ItemStack s) {
        return s == null ? null : ChartMath.read(s.getTagCompound());
    }

    @Override
    public ItemStack onItemRightClick(ItemStack s, World w, EntityPlayer p) {
        if (w.isRemote && chart(s) != null) {
            com.sva.SVAMod.proxy.openChart(s);
        }
        return s;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        ChartMath.Chart c = chart(s);
        if (c == null) {
            list.add(Lang.tr("sva.tooltip.apt.chart.blank"));
            return;
        }
        list.add(Lang.tr("sva.tooltip.apt.chart.of", c.icao, "ILS " + c.rwy, String.valueOf(c.version)));
        if (ChartMath.outdated(c.version, com.sva.SVAMod.proxy.chartVersion(c.icao))) {
            list.add("§c" + Lang.tr("sva.chart.outdated"));
        }
        list.add(Lang.tr("sva.tooltip.apt.chart.summary"));
        TooltipSC.more(list, Lang.tr("sva.tooltip.apt.chart.details"), Lang.tr("sva.tooltip.howto.apt.chart"));
    }
}
