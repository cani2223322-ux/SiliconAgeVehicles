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
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

/**
 * Airport wave B2: the item of «Диспетчерская вышка» - it needs TileEntityTowerSVA.PARTS free blocks above the clicked one
 * (else a message naming the first one in the way); the TooltipSC tooltip (sva.tooltip.apt.tower.summary / .details,
 * sva.tooltip.howto.apt.tower).
 */
public class ItemBlockTowerSVA extends ItemBlock {

    public ItemBlockTowerSVA(Block b) {
        super(b);
    }

    @Override
    public boolean placeBlockAt(ItemStack stack, EntityPlayer p, World w, int x, int y, int z, int side, float hx, float hy, float hz, int meta) {
        int[] blocked = BlockTowerSVA.room(w, x, y, z);
        if (blocked != null) {
            if (!w.isRemote && p != null) {
                p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.noRoom", Integer.valueOf(blocked[0]), Integer.valueOf(blocked[1]),
                        Integer.valueOf(blocked[2])));
            }
            return false;
        }
        return super.placeBlockAt(stack, p, w, x, y, z, side, hx, hy, hz, meta);
    }

    @SideOnly(Side.CLIENT)
    private net.minecraft.util.IIcon icon;

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(net.minecraft.client.renderer.texture.IIconRegister r) {
        icon = r.registerIcon(com.sva.ReferenceSVA.ASSETS + ":aptTower");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public net.minecraft.util.IIcon getIconFromDamage(int meta) {
        return icon == null ? super.getIconFromDamage(meta) : icon;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int getSpriteNumber() {
        return 1;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        list.add(Lang.tr("sva.tooltip.apt.tower.summary"));
        TooltipSC.more(list, Lang.tr("sva.tooltip.apt.tower.details"), Lang.tr("sva.tooltip.howto.apt.tower"));
    }
}
