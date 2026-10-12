package com.sva.block;

import java.util.List;

import com.sc.manual.Lang;
import com.sc.util.TooltipSC;
import com.sva.airdefense.AdShapeMath;
import com.sva.airdefense.BallisticsMath;
import com.sva.airdefense.RadarMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

/**
 * Air-defence wave: the radars' / launchers' / command post's item - one per meta, TooltipSC tooltips (summary + Shift / Ctrl).
 * Multiblock wave: placing it puts the whole structure in (BlockAirDefenseSVA.build) facing the player, the clicked block
 * the master in its front row; with a cell taken (a block, a mob or a player in the way, an unloaded chunk, the world's top)
 * nothing is placed and the chat says how much room it needs.
 */
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
    public boolean placeBlockAt(ItemStack stack, EntityPlayer p, World w, int x, int y, int z, int side, float hx, float hy, float hz, int meta) {
        if (!(field_150939_a instanceof BlockAirDefenseSVA)) {
            return false;
        }
        BlockAirDefenseSVA b = (BlockAirDefenseSVA) field_150939_a;
        int facing = AdShapeMath.facingFromYaw(p == null ? 0F : p.rotationYaw);
        int shape = AdShapeMath.shape(b.kind, meta);
        int[] blocked = BlockAirDefenseSVA.room(w, x, y, z, shape, facing);
        if (blocked != null) {
            if (!w.isRemote && p != null) {
                p.addChatComponentMessage(new ChatComponentTranslation("sva.ad.msg.noRoom", Integer.valueOf(AdShapeMath.W[shape]),
                        Integer.valueOf(AdShapeMath.D[shape]), Integer.valueOf(AdShapeMath.layers(shape)), Integer.valueOf(blocked[0]),
                        Integer.valueOf(blocked[1]), Integer.valueOf(blocked[2])));
            }
            return false;
        }
        if (!BlockAirDefenseSVA.build(w, x, y, z, b, meta, facing)) {
            return false;
        }
        if (w.getBlock(x, y, z) == b) {
            b.onBlockPlacedBy(w, x, y, z, p, stack);
            b.onPostBlockPlaced(w, x, y, z, w.getBlockMetadata(x, y, z));
        }
        return true;
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
        int shape = AdShapeMath.shape(k, m);                        // multiblock wave: its size first
        list.add(Lang.tr("sva.tooltip.ad.size", Integer.valueOf(AdShapeMath.W[shape]), Integer.valueOf(AdShapeMath.D[shape]),
                Integer.valueOf(AdShapeMath.layers(shape))));
        // upgrades wave: the slots for the core's modules, which fit (Shift)
        int blk = k == BlockAirDefenseSVA.RADAR ? com.sva.airdefense.AdUpgradeMath.B_RADAR
                : k == BlockAirDefenseSVA.LAUNCHER ? com.sva.airdefense.AdUpgradeMath.B_LAUNCHER : com.sva.airdefense.AdUpgradeMath.B_KP;
        list.add("§7" + Lang.tr("sva.tooltip.ad.slots", Integer.valueOf(com.sva.airdefense.AdUpgradeMath.SLOTS)));
        StringBuilder mods = new StringBuilder();
        for (com.sc.machine.UpgradeType u : com.sva.airdefense.AdUpgradeMath.accepted(blk)) {
            mods.append(mods.length() > 0 ? ", " : "").append(com.sc.init.ModItems.upgrade.stackOf(u).getDisplayName());
        }
        String fit = "\n" + Lang.tr("sva.tooltip.ad.modules", mods.toString());
        if (k == BlockAirDefenseSVA.RADAR) {
            int r = RadarMath.validKind(m) ? m : 0;
            String key = RadarMath.KEYS[r];
            list.add(Lang.tr("sva.tooltip.radar.summary", Integer.valueOf((int) RadarMath.RANGE[r]),
                    Integer.valueOf((int) Math.round(RadarMath.detectRange(r, RadarMath.rcs(VehicleType.PLANE), 1, 1))),
                    Integer.valueOf(RadarMath.EU_PER_TICK[r]), r == RadarMath.HEIGHT ? "MV" : "HV"));
            TooltipSC.more(list, Lang.tr("sva.tooltip.radar." + key + ".details") + fit, Lang.tr("sva.tooltip.howto.radar"));
        } else if (k == BlockAirDefenseSVA.LAUNCHER) {
            boolean missile = m == TileEntityLauncherSVA.MISSILE;
            list.add(Lang.tr(missile ? "sva.tooltip.launcher.missile" : "sva.tooltip.launcher.gun",
                    Integer.valueOf((int) (missile ? BallisticsMath.MISSILE_RANGE : BallisticsMath.GUN_RANGE)),
                    Integer.valueOf(TileEntityLauncherSVA.UPKEEP[missile ? 1 : 0])));
            TooltipSC.more(list, Lang.tr(missile ? "sva.tooltip.launcher.missile.details" : "sva.tooltip.launcher.gun.details") + fit,
                    Lang.tr("sva.tooltip.howto.launcher"));
        } else {
            list.add(Lang.tr("sva.tooltip.kp.summary", Integer.valueOf((int) BallisticsMath.LINK), Integer.valueOf(TileEntityCommandPostSVA.UPKEEP)));
            TooltipSC.more(list, Lang.tr("sva.tooltip.kp.details") + fit, Lang.tr("sva.tooltip.howto.kp"));
        }
    }
}
