package com.sva.block;

import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;

/**
 * Wave 1, ★11: «Бордюр» - a half block of concrete along the road's edge (a wheeled vehicle steps up on it; it gives no
 * speed bonus - it is not road).
 */
public class BlockCurbSVA extends Block {

    @SideOnly(Side.CLIENT)
    private IIcon top, side;

    public BlockCurbSVA() {
        super(Material.rock);
        setHardness(1.5F);
        setResistance(10.0F);
        setStepSound(soundTypeStone);
        setHarvestLevel("pickaxe", 0);
        setBlockName(ReferenceSVA.ASSETS + ".curb");
        setBlockBounds(0F, 0F, 0F, 1F, 0.5F, 1F);
        setLightOpacity(0);
        useNeighborBrightness = true;
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean shouldSideBeRendered(IBlockAccess w, int x, int y, int z, int s) {
        return s == 1 || super.shouldSideBeRendered(w, x, y, z, s);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        top = r.registerIcon(ReferenceSVA.ASSETS + ":curb_top");
        side = r.registerIcon(ReferenceSVA.ASSETS + ":curb_side");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return s == 1 || s == 0 ? top : side;
    }
}
