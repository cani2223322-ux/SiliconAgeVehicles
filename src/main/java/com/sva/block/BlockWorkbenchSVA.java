package com.sva.block;

import java.util.Random;

import com.sva.ReferenceSVA;
import com.sva.SVAMod;
import com.sva.inventory.GuiHandlerSVA;

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

/** «Инженерный верстак». The front (screen) faces the player who placed it; it lights up while assembling. */
public class BlockWorkbenchSVA extends BlockContainer {

    private IIcon top, front, frontOn, side, back, bottom;

    public BlockWorkbenchSVA() {
        super(Material.iron);
        setHardness(3.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 1);
        setBlockName(ReferenceSVA.ASSETS + ".workbench");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityWorkbenchSVA();
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase p, ItemStack s) {
        int d = MathHelper.floor_double(p.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int[] faces = {2, 5, 3, 4};                     // facing the player
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityWorkbenchSVA) {
            ((TileEntityWorkbenchSVA) te).setFacing(faces[d]);
            w.markBlockForUpdate(x, y, z);
        }
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int s, float hx, float hy, float hz) {
        if (p.isSneaking()) {
            return false;
        }
        if (!w.isRemote && w.getTileEntity(x, y, z) instanceof TileEntityWorkbenchSVA) {
            p.openGui(SVAMod.instance, GuiHandlerSVA.WORKBENCH, w, x, y, z);
        }
        return true;
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityWorkbenchSVA) {
            TileEntityWorkbenchSVA wb = (TileEntityWorkbenchSVA) te;
            Random r = w.rand;
            for (int i = 0; i < wb.getSizeInventory(); i++) {
                ItemStack s = wb.getStackInSlot(i);
                if (s != null) {
                    EntityItem e = new EntityItem(w, x + 0.2 + r.nextFloat() * 0.6, y + 0.5, z + 0.2 + r.nextFloat() * 0.6, s.copy());
                    w.spawnEntityInWorld(e);
                    wb.setInventorySlotContents(i, null);
                }
            }
        }
        super.breakBlock(w, x, y, z, b, meta);
    }

    @Override
    public boolean hasComparatorInputOverride() {
        return true;
    }

    @Override
    public int getComparatorInputOverride(World w, int x, int y, int z, int side) {
        TileEntity te = w.getTileEntity(x, y, z);
        return te instanceof TileEntityWorkbenchSVA ? ((TileEntityWorkbenchSVA) te).comparatorSignal() : 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        String a = ReferenceSVA.ASSETS + ":";
        top = r.registerIcon(a + "engWorkbenchTop2");
        front = r.registerIcon(a + "engWorkbenchFront2");
        frontOn = r.registerIcon(a + "engWorkbenchFront2On");
        side = r.registerIcon(a + "engWorkbenchSide2");
        back = r.registerIcon(a + "engWorkbenchBack2");
        bottom = r.registerIcon("siliconage:machineCasingMV");
    }

    private IIcon icon(int s, int facing, boolean on) {
        if (s == 1) {
            return top;
        }
        if (s == 0) {
            return bottom;
        }
        if (s == facing) {
            return on ? frontOn : front;
        }
        return s == net.minecraft.util.Facing.oppositeSide[facing] ? back : side;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return icon(s, 3, false);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess w, int x, int y, int z, int s) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityWorkbenchSVA) {
            TileEntityWorkbenchSVA wb = (TileEntityWorkbenchSVA) te;
            return icon(s, wb.getFacing(), wb.isAssembling());
        }
        return icon(s, 3, false);
    }
}
