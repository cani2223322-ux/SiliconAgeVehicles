package com.sva.block;

import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Stage D1, ★2: «Огни приближения» - a pole with a bar of five lamps across the runway's extended centre line, put in a
 * line before the threshold (one every few blocks). Stacked poles make a taller one (the bar on the top block only). At
 * night / in rain (or by redstone - AirfieldSVA.manual) the lamps burn and a «бегущий огонь» flash runs along the line
 * toward the threshold twice a second (AirfieldMath.flash: by world time and the pole's distance before the threshold -
 * every client sees the same run, no packets). client.RenderAirfieldSVA draws it. Metadata: bit 0 = the bar along X from
 * placement (the runway's axis replaces it once the pole finds its strip).
 */
public class BlockApproachLightSVA extends BlockContainer {

    @SideOnly(Side.CLIENT)
    private IIcon icon;

    public BlockApproachLightSVA() {
        super(Material.iron);
        setHardness(1.5F);
        setResistance(5.0F);
        setStepSound(soundTypeMetal);
        setBlockName(ReferenceSVA.ASSETS + ".approachLight");
        setLightOpacity(0);
        setBlockBounds(0.38F, 0F, 0.38F, 0.62F, 1F, 0.62F);
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityAirfieldSVA();
    }

    @Override
    public int getRenderType() {
        return -1;                                           // client.RenderAirfieldSVA (TESR) draws it
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
    public int damageDropped(int meta) {
        return 0;
    }

    /** The bar across the placer's look (along X when the placer faces north / south). */
    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int facing = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        w.setBlockMetadataWithNotify(x, y, z, facing == 0 || facing == 2 ? 1 : 0, 2);
    }

    @Override
    public void onNeighborBlockChange(World w, int x, int y, int z, Block b) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityAirfieldSVA) {
            ((TileEntityAirfieldSVA) te).invalidateCache();
        }
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        return AxisAlignedBB.getBoundingBox(x + 0.38, y, z + 0.38, x + 0.62, y + 1.0, z + 0.62);
    }

    @Override
    public void setBlockBoundsForItemRender() {
        setBlockBounds(0F, 0F, 0F, 1F, 1F, 1F);
    }

    @Override
    public int getLightValue(IBlockAccess w, int x, int y, int z) {
        return w.getBlock(x, y + 1, z) == this ? 0 : 6;   // the lamp bar on the top block
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        icon = r.registerIcon(ReferenceSVA.ASSETS + ":approachLight");
    }

    /** The flat item icon / break particles. */
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return icon;
    }
}
