package com.sva.block;

import java.util.ArrayList;
import java.util.Random;

import com.sva.ReferenceSVA;
import com.sva.airdefense.AdShapeMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.Explosion;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Multiblock wave: the invisible part block that fills an air-defence structure's footprint around its master (the model
 * is drawn by the master's renderer). Meta = AdShapeMath cell type: FULL (a whole block) or PAD (only the concrete pad,
 * 2 / 16 high - walked over). It hands everything to the master: right-click (the screens), energy (TileEntityAdPartSVA),
 * the pick block; breaking any part - by hand, an explosion, anything that replaces it - takes the whole structure down
 * through the master, which drops its one item. Pistons can't move it; a part whose master is gone removes itself the
 * next time a neighbour changes. Never in the creative tab, never dropped.
 */
public class BlockAdPartSVA extends BlockContainer {

    private IIcon icon;

    public BlockAdPartSVA() {
        super(Material.iron);
        setHardness(4.0F);
        setResistance(15.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 1);
        setLightOpacity(0);
        setBlockName(ReferenceSVA.ASSETS + ".adPart");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityAdPartSVA();
    }

    public static TileEntityAirDefenseSVA masterOf(IBlockAccess w, int x, int y, int z) {
        TileEntity te = w.getTileEntity(x, y, z);
        return te instanceof TileEntityAdPartSVA ? ((TileEntityAdPartSVA) te).master() : null;
    }

    // ------------------------------------------------------------------ hand it to the master

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        TileEntityAirDefenseSVA m = masterOf(w, x, y, z);
        if (m == null) {
            return false;
        }
        Block b = m.getBlockType();
        return b.onBlockActivated(w, m.xCoord, m.yCoord, m.zCoord, p, side, hx, hy, hz);
    }

    @Override
    public void onBlockHarvested(World w, int x, int y, int z, int meta, EntityPlayer p) {
        TileEntityAirDefenseSVA m = masterOf(w, x, y, z);
        if (m != null && p != null && p.capabilities.isCreativeMode) {
            m.noDrop = true;                                    // creative: the structure goes, no item
        }
        super.onBlockHarvested(w, x, y, z, meta, p);
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        if (!w.isRemote) {
            TileEntityAirDefenseSVA m = masterOf(w, x, y, z);
            if (m != null && !m.dismantling) {
                w.setBlockToAir(m.xCoord, m.yCoord, m.zCoord);   // the master drops the item and removes the other parts
            }
        }
        super.breakBlock(w, x, y, z, b, meta);
    }

    @Override
    public void onNeighborBlockChange(World w, int x, int y, int z, Block b) {
        if (w.isRemote) {
            return;
        }
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityAdPartSVA && ((TileEntityAdPartSVA) te).orphan()) {
            w.setBlockToAir(x, y, z);
        }
    }

    @Override
    public ArrayList<ItemStack> getDrops(World w, int x, int y, int z, int meta, int fortune) {
        return new ArrayList<ItemStack>();
    }

    @Override
    public int quantityDropped(Random r) {
        return 0;
    }

    @Override
    public ItemStack getPickBlock(MovingObjectPosition target, World w, int x, int y, int z) {
        TileEntityAirDefenseSVA m = masterOf(w, x, y, z);
        if (m == null) {
            return null;
        }
        Block b = m.getBlockType();
        return new ItemStack(b, 1, b.damageDropped(w.getBlockMetadata(m.xCoord, m.yCoord, m.zCoord)));
    }

    @Override
    public int getMobilityFlag() {
        return 2;                                               // pistons: immovable
    }

    @Override
    public boolean canEntityDestroy(IBlockAccess w, int x, int y, int z, Entity e) {
        return true;
    }

    @Override
    public boolean canDropFromExplosion(Explosion e) {
        return false;
    }

    // ------------------------------------------------------------------ shape: invisible, a full block or the pad

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
        if (w.getBlockMetadata(x, y, z) == AdShapeMath.PAD) {
            setBlockBounds(0F, 0F, 0F, 1F, AdShapeMath.PAD_HEIGHT, 1F);
        } else {
            setBlockBounds(0F, 0F, 0F, 1F, 1F, 1F);
        }
    }

    @Override
    public void setBlockBoundsForItemRender() {
        setBlockBounds(0F, 0F, 0F, 1F, 1F, 1F);
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
        icon = r.registerIcon(ReferenceSVA.ASSETS + ":adBase");     // break particles
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return icon;
    }
}
