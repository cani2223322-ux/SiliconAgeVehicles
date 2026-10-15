package com.sva.block;

import java.util.ArrayList;
import java.util.Random;

import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Airport wave B1: a part of an ILS antenna (no item of its own) - an element of the localizer's 5 x 1 array or a section
 * of the glide slope's mast. The metadata is where its master stands (PART_OFFSETS): LOC elements 0..7 (along X: two each
 * side, along Z), the mast's sections 8, 9. Drawn by the master's model (client.RenderAptSVA), not as a cube; its box is
 * the thin post / mast. Broken, it goes ALONE - the antenna is then damaged: its monitor (IlsCat) shuts the station off
 * (LOC) or drops the glide slope (G/S) until the master is repaired (Shift + RMB on it with iron) or rebuilt. RMB opens the
 * master's screen.
 * <p>
 * Airport wave B2: the «Диспетчерская вышка»'s shaft and cab too - metas 8..12 straight up from its master (dy 1..5): a
 * part of a tower is the shaft's square (the top two the cab's floor and glass), solid; breaking one takes the tower down
 * with it (no tower stands on a gap).
 */
public class BlockAptPartSVA extends Block {

    /** The offset from a part to its master per metadata. */
    public static final int[][] TO_MASTER = {{2, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {-2, 0, 0}, {0, 0, 2}, {0, 0, 1}, {0, 0, -1}, {0, 0, -2}, {0, -1, 0},
        {0, -2, 0}, {0, -3, 0}, {0, -4, 0}, {0, -5, 0}};

    @SideOnly(Side.CLIENT)
    private IIcon icon;

    public BlockAptPartSVA() {
        super(Material.iron);
        setHardness(2.0F);
        setResistance(6.0F);
        setStepSound(soundTypeMetal);
        setLightOpacity(0);
        setBlockName(ReferenceSVA.ASSETS + ".aptPart");
    }

    /** The metadata of a part at offset (dx, dy, dz) from its master. */
    public static int metaFor(int dx, int dy, int dz) {
        for (int m = 0; m < TO_MASTER.length; m++) {
            if (TO_MASTER[m][0] == -dx && TO_MASTER[m][1] == -dy && TO_MASTER[m][2] == -dz) {
                return m;
            }
        }
        return 0;
    }

    /** The master's tile of a part, or null. */
    public static TileEntityIlsAntennaSVA master(IBlockAccess w, int x, int y, int z) {
        TileEntity te = masterTile(w, x, y, z);
        return te instanceof TileEntityIlsAntennaSVA ? (TileEntityIlsAntennaSVA) te : null;
    }

    /** Airport wave B2: the master of any kind (an ILS antenna, a tower), or null. */
    public static TileEntity masterTile(IBlockAccess w, int x, int y, int z) {
        int m = w.getBlockMetadata(x, y, z);
        if (m < 0 || m >= TO_MASTER.length) {
            return null;
        }
        TileEntity te = w.getTileEntity(x + TO_MASTER[m][0], y + TO_MASTER[m][1], z + TO_MASTER[m][2]);
        return te instanceof TileEntityIlsAntennaSVA || te instanceof TileEntityTowerSVA ? te : null;
    }

    /** A part of a tower (its shaft / cab). */
    static boolean tower(IBlockAccess w, int x, int y, int z) {
        return masterTile(w, x, y, z) instanceof TileEntityTowerSVA;
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        TileEntity m = masterTile(w, x, y, z);
        if (m == null) {
            return false;
        }
        return w.getBlock(m.xCoord, m.yCoord, m.zCoord).onBlockActivated(w, m.xCoord, m.yCoord, m.zCoord, p, side, hx, hy, hz);
    }

    /** Airport wave B2: a tower's part broken - the whole tower comes down (its master drops the item). */
    @Override
    public void breakBlock(World w, int x, int y, int z, net.minecraft.block.Block b, int meta) {
        if (!w.isRemote && meta >= 8 && meta < TO_MASTER.length) {
            int mx = x + TO_MASTER[meta][0], my = y + TO_MASTER[meta][1], mz = z + TO_MASTER[meta][2];
            if (w.getTileEntity(mx, my, mz) instanceof TileEntityTowerSVA && w.getBlock(mx, my, mz) instanceof BlockTowerSVA) {
                w.func_147480_a(mx, my, mz, true);
            }
        }
        super.breakBlock(w, x, y, z, b, meta);
    }

    @Override
    public ArrayList<ItemStack> getDrops(World w, int x, int y, int z, int meta, int fortune) {
        return new ArrayList<ItemStack>();                           // the master drops the antenna
    }

    @Override
    public int quantityDropped(Random r) {
        return 0;
    }

    @Override
    public ItemStack getPickBlock(MovingObjectPosition target, World w, int x, int y, int z) {
        TileEntity t = masterTile(w, x, y, z);
        if (t instanceof TileEntityTowerSVA) {
            return new ItemStack(w.getBlock(t.xCoord, t.yCoord, t.zCoord));
        }
        TileEntityIlsAntennaSVA m = master(w, x, y, z);
        return m == null ? null : new ItemStack(w.getBlock(m.xCoord, m.yCoord, m.zCoord), 1, m.kind());
    }

    @Override
    public int getRenderType() {
        return -1;
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
    public void setBlockBoundsBasedOnState(IBlockAccess w, int x, int y, int z) {
        int m = w.getBlockMetadata(x, y, z);
        if (m >= 8 && tower(w, x, y, z)) {
            setBlockBounds(0.1F, 0F, 0.1F, 0.9F, 1F, 0.9F);       // the tower's shaft / cab floor
            return;
        }
        float r = m >= 8 ? 0.22F : 0.16F;
        setBlockBounds(0.5F - r, 0F, 0.5F - r, 0.5F + r, 1F, 0.5F + r);
    }

    @Override
    public void setBlockBoundsForItemRender() {
        setBlockBounds(0.3F, 0F, 0.3F, 0.7F, 1F, 0.7F);
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        setBlockBoundsBasedOnState(w, x, y, z);
        return super.getCollisionBoundingBoxFromPool(w, x, y, z);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World w, int x, int y, int z) {
        setBlockBoundsBasedOnState(w, x, y, z);
        return super.getSelectedBoundingBoxFromPool(w, x, y, z);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        icon = r.registerIcon(ReferenceSVA.ASSETS + ":ils_side");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return icon;
    }
}
