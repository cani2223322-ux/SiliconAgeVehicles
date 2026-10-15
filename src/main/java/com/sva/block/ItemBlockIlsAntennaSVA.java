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
 * Airport wave B1: the ILS antennas' item (meta LOC «Курсовой маяк (антенна 5x1)», GS «Глиссадный маяк (мачта)») - TooltipSC
 * tooltips (summary + Shift / Ctrl); placing it builds the whole antenna when its cells are free (the chat says which one
 * is in the way otherwise).
 */
public class ItemBlockIlsAntennaSVA extends ItemBlock {

    public ItemBlockIlsAntennaSVA(Block b) {
        super(b);
        setHasSubtypes(true);
        setMaxDamage(0);
    }

    @Override
    public int getMetadata(int meta) {
        return meta & 1;
    }

    @Override
    public String getUnlocalizedName(ItemStack s) {
        return super.getUnlocalizedName() + ((s.getItemDamage() & 1) == BlockIlsAntennaSVA.GS ? ".gs" : ".loc");
    }

    @Override
    public boolean placeBlockAt(ItemStack stack, EntityPlayer p, World w, int x, int y, int z, int side, float hx, float hy, float hz, int meta) {
        int kind = meta & 1;
        int facing = BlockIlsAntennaSVA.facing(p == null ? 0F : p.rotationYaw);
        int[] blocked = BlockIlsAntennaSVA.room(w, x, y, z, kind, facing);
        if (blocked != null) {
            if (!w.isRemote && p != null) {
                p.addChatComponentMessage(new ChatComponentTranslation(kind == BlockIlsAntennaSVA.GS ? "sva.apt.msg.noRoomGs" : "sva.apt.msg.noRoomLoc",
                        Integer.valueOf(blocked[0]), Integer.valueOf(blocked[1]), Integer.valueOf(blocked[2])));
            }
            return false;
        }
        return super.placeBlockAt(stack, p, w, x, y, z, side, hx, hy, hz, meta);
    }

    @SideOnly(Side.CLIENT)
    private net.minecraft.util.IIcon[] icons;

    /** The 2D icons (the blocks are drawn by their TESR - nothing for an item to show in 3D). */
    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(net.minecraft.client.renderer.texture.IIconRegister r) {
        icons = new net.minecraft.util.IIcon[] {r.registerIcon(com.sva.ReferenceSVA.ASSETS + ":ilsLoc"), r.registerIcon(com.sva.ReferenceSVA.ASSETS + ":ilsGs")};
    }

    @Override
    @SideOnly(Side.CLIENT)
    public net.minecraft.util.IIcon getIconFromDamage(int meta) {
        return icons == null ? super.getIconFromDamage(meta) : icons[meta & 1];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int getSpriteNumber() {
        return 1;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack s, EntityPlayer p, List list, boolean adv) {
        String k = (s.getItemDamage() & 1) == BlockIlsAntennaSVA.GS ? "gs" : "loc";
        list.add(Lang.tr("sva.tooltip.apt." + k + ".summary"));
        TooltipSC.more(list, Lang.tr("sva.tooltip.apt." + k + ".details"), Lang.tr("sva.tooltip.howto.apt." + k));
    }
}
