package com.sva.block;

import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Wave 2, ★7: «Ремонтный гараж» - the garage controller (HV, Silicon Age / IC2 net). Its status screen (the front,
 * garageFront / On) faces the player who placed it and lights up while a vehicle stands on the garage floor; the
 * floor is «Плиты гаража» touching it on its level (BlockGaragePlateSVA). Right-click: the garage screen (vehicle
 * condition, repair, tuning slots). Comparator: the repair's progress.
 */
public class BlockGarageSVA extends BlockContainer {

    private IIcon top, side, bottom, front, frontOn;

    public BlockGarageSVA() {
        super(Material.iron);
        setHardness(3.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 1);
        setBlockName(ReferenceSVA.ASSETS + ".garage");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityGarageSVA();
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int dir = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int[] faces = {2, 5, 3, 4};                     // the screen looks at the player
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityGarageSVA) {
            TileEntityGarageSVA g = (TileEntityGarageSVA) te;
            g.setFacing(faces[dir]);
            if (!w.isRemote && e instanceof EntityPlayer) {
                g.setOwner(e.getUniqueID().toString(), ((EntityPlayer) e).getCommandSenderName());
            }
            w.markBlockForUpdate(x, y, z);
        }
        KeptDataSVA.restore(w, x, y, z, s);
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int s, float hx, float hy, float hz) {
        if (p.isSneaking()) {
            return false;
        }
        if (!w.isRemote && w.getTileEntity(x, y, z) instanceof TileEntityGarageSVA) {
            p.openGui(com.sva.SVAMod.instance, com.sva.inventory.GuiHandlerSVA.GARAGE, w, x, y, z);
        }
        return true;
    }

    @Override
    public void onNeighborBlockChange(World w, int x, int y, int z, Block b) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityGarageSVA) {
            ((TileEntityGarageSVA) te).platformChanged();
        }
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityGarageSVA && !w.isRemote) {
            TileEntityGarageSVA g = (TileEntityGarageSVA) te;
            g.interruptRepair();                            // ТБ-8: the unused share of the metal goes back into the slots (spilt below)
            for (int i = 0; i < g.getSizeInventory(); i++) {
                ItemStack st = g.getStackInSlot(i);
                if (st != null) {
                    w.spawnEntityInWorld(new EntityItem(w, x + 0.5, y + 0.5, z + 0.5, st.copy()));
                    g.setInventorySlotContents(i, null);
                }
            }
        }
        super.breakBlock(w, x, y, z, b, meta);
    }

    // ------------------------------------------------------------------ ТБ-1: the item keeps the buffer (KeptDataSVA)

    @Override
    public boolean removedByPlayer(World w, EntityPlayer p, int x, int y, int z, boolean willHarvest) {
        if (willHarvest) {
            return true;                                    // harvestBlock drops (reading the tile), then removes the block
        }
        return super.removedByPlayer(w, p, x, y, z, willHarvest);
    }

    @Override
    public void harvestBlock(World w, EntityPlayer p, int x, int y, int z, int meta) {
        super.harvestBlock(w, p, x, y, z, meta);
        w.setBlockToAir(x, y, z);
    }

    /** An explosion drops it whole, as the core's machines: what it keeps rides in the item. */
    @Override
    public void dropBlockAsItemWithChance(World w, int x, int y, int z, int meta, float chance, int fortune) {
        super.dropBlockAsItemWithChance(w, x, y, z, meta, 1.0F, fortune);
    }

    @Override
    public java.util.ArrayList<ItemStack> getDrops(World w, int x, int y, int z, int meta, int fortune) {
        return KeptDataSVA.drops(this, damageDropped(meta), w, x, y, z);
    }

    @Override
    public boolean hasComparatorInputOverride() {
        return true;
    }

    @Override
    public int getComparatorInputOverride(World w, int x, int y, int z, int side) {
        TileEntity te = w.getTileEntity(x, y, z);
        return te instanceof TileEntityGarageSVA ? ((TileEntityGarageSVA) te).comparatorLevel() : 0;
    }

    @Override
    public int getLightValue(IBlockAccess w, int x, int y, int z) {
        TileEntity te = w.getTileEntity(x, y, z);
        return te instanceof TileEntityGarageSVA && ((TileEntityGarageSVA) te).isLit() ? 7 : 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        String a = ReferenceSVA.ASSETS + ":";
        top = r.registerIcon(a + "garageTop");
        side = r.registerIcon(a + "garageSide");
        bottom = r.registerIcon("siliconage:machineCasingHV");
        front = r.registerIcon(a + "garageFront");
        frontOn = r.registerIcon(a + "garageFrontOn");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return s == 1 ? top : s == 0 ? bottom : s == 3 ? frontOn : side;      // the item: the screen towards the viewer
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess w, int x, int y, int z, int s) {
        TileEntity te = w.getTileEntity(x, y, z);
        boolean lit = te instanceof TileEntityGarageSVA && ((TileEntityGarageSVA) te).isLit();
        int f = te instanceof TileEntityGarageSVA ? ((TileEntityGarageSVA) te).getFacing() : 3;
        if (s == f) {
            return lit ? frontOn : front;
        }
        return s == 1 ? top : s == 0 ? bottom : side;
    }
}
