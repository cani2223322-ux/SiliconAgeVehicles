package com.sva.block;

import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * «Суперконденсатор станции» (TileEntitySupercapSVA): placed against a charging station (any face), it adds a 50M EU
 * burst to the vehicle the station charges. Right-click: its screen (charge, grid input, output, the station beside it).
 * The sides show the charge in 5 steps; comparator: the fill. Broken, the item keeps the charge (KeptDataSVA).
 */
public class BlockSupercapSVA extends BlockContainer {

    private IIcon top, bottom;
    private final IIcon[] sides = new IIcon[TileEntitySupercapSVA.LEVELS];

    public BlockSupercapSVA() {
        super(Material.iron);
        setHardness(3.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 1);
        setBlockName(ReferenceSVA.ASSETS + ".supercap");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntitySupercapSVA();
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        KeptDataSVA.restore(w, x, y, z, s);
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int s, float hx, float hy, float hz) {
        if (p.isSneaking()) {
            return false;
        }
        if (!w.isRemote && w.getTileEntity(x, y, z) instanceof TileEntitySupercapSVA) {
            p.openGui(com.sva.SVAMod.instance, com.sva.inventory.GuiHandlerSVA.SUPERCAP, w, x, y, z);
        }
        return true;
    }

    // ------------------------------------------------------------------ ТБ-1: the item keeps the charge (KeptDataSVA)

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

    @Override
    public void dropBlockAsItemWithChance(World w, int x, int y, int z, int meta, float chance, int fortune) {
        super.dropBlockAsItemWithChance(w, x, y, z, meta, 1.0F, fortune);
    }

    @Override
    public java.util.ArrayList<ItemStack> getDrops(World w, int x, int y, int z, int meta, int fortune) {
        return KeptDataSVA.drops(this, 0, w, x, y, z);
    }

    @Override
    public boolean hasComparatorInputOverride() {
        return true;
    }

    @Override
    public int getComparatorInputOverride(World w, int x, int y, int z, int side) {
        TileEntity te = w.getTileEntity(x, y, z);
        return te instanceof TileEntitySupercapSVA ? ((TileEntitySupercapSVA) te).comparatorLevel() : 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        String a = ReferenceSVA.ASSETS + ":";
        top = r.registerIcon(a + "supercapTop");
        bottom = r.registerIcon("siliconage:machineCasingXV");
        for (int i = 0; i < sides.length; i++) {
            sides[i] = r.registerIcon(a + "supercapSide" + i);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return s == 1 ? top : s == 0 ? bottom : sides[2];                  // the item: half charged
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess w, int x, int y, int z, int s) {
        if (s == 1) {
            return top;
        }
        if (s == 0) {
            return bottom;
        }
        TileEntity te = w.getTileEntity(x, y, z);
        int lv = te instanceof TileEntitySupercapSVA ? ((TileEntitySupercapSVA) te).getClientLevel() : 0;
        return sides[Math.max(0, Math.min(sides.length - 1, lv))];
    }
}
