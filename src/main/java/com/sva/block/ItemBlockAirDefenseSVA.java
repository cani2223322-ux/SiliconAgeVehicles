package com.sva.block;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.RadarMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** Air-defence wave: the radars' / launchers' / command post's item - one per meta, TooltipSC tooltips (summary + Shift / Ctrl). */
public class ItemBlockAirDefenseSVA extends ItemBlock {

    public ItemBlockAirDefenseSVA(Block b) {
        super(b);
        setHasSubtypes(true);
        setMaxDamage(0);
    }

    private int kind() {
        return field_150939_a instanceof BlockAirDefenseSVA ? ((BlockAirDefenseSVA) field_150939_a).kind : 0;
    }

    @Override
    public int getMetadata(int meta) {
        return kind() == BlockAirDefenseSVA.COMMAND_POST ? 0 : meta;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        int k = kind();
        int m = s.getItemDamage();
        if (k == BlockAirDefenseSVA.RADAR) {
            return super.getUnlocalizedName() + "." + RadarMath.KEYS[RadarMath.validKind(m) ? m : 0];
        }
        if (k == BlockAirDefenseSVA.LAUNCHER) {
            return super.getUnlocalizedName() + "." + TileEntityLauncherSVA.KEYS[m == TileEntityLauncherSVA.MISSILE ? 1 : 0];
        }
        return super.getUnlocalizedName();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        int k = kind();
        int m = s.getItemDamage();
        if (k == BlockAirDefenseSVA.RADAR) {
            int r = RadarMath.validKind(m) ? m : 0;
            String key = RadarMath.KEYS[r];
            list.add(Lang.tr("sva.tooltip.radar.summary", Integer.valueOf((int) RadarMath.RANGE[r]),
                    Integer.valueOf((int) Math.round(RadarMath.detectRange(r, RadarMath.rcs(VehicleType.PLANE), 1, 1))),
                    Integer.valueOf(RadarMath.EU_PER_TICK[r]), r == RadarMath.HEIGHT ? "MV" : "HV"));
            TooltipSC.more(list, Lang.tr("sva.tooltip.radar." + key + ".details"), Lang.tr("sva.tooltip.howto.radar"));
        } else if (k == BlockAirDefenseSVA.LAUNCHER) {
            boolean missile = m == TileEntityLauncherSVA.MISSILE;
            list.add(Lang.tr(missile ? "sva.tooltip.launcher.missile" : "sva.tooltip.launcher.gun",
                    Integer.valueOf((int) (missile ? BallisticsMath.MISSILE_RANGE : BallisticsMath.GUN_RANGE)),
                    Integer.valueOf(TileEntityLauncherSVA.UPKEEP[missile ? 1 : 0])));
            TooltipSC.more(list, Lang.tr(missile ? "sva.tooltip.launcher.missile.details" : "sva.tooltip.launcher.gun.details"),
                    Lang.tr("sva.tooltip.howto.launcher"));
        } else {
            list.add(Lang.tr("sva.tooltip.kp.summary", Integer.valueOf((int) BallisticsMath.LINK), Integer.valueOf(TileEntityCommandPostSVA.UPKEEP)));
            TooltipSC.more(list, Lang.tr("sva.tooltip.kp.details"), Lang.tr("sva.tooltip.howto.kp"));
        }
    }
}
