package com.sva.block;

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
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Airport wave B1: «Аппаратная ILS» - the ILS antennas' equipment shelter (power + monitor; TileEntityIlsShelterSVA). A
 * small hut drawn by client.RenderAptSVA (the model: walls, a door, an air conditioner, the status lamp - green powered,
 * amber idle, red no power); metadata = its door's facing from the placer. RMB: its screen. Broken: the upgrade modules
 * drop loose.
 */
public class BlockIlsShelterSVA extends BlockContainer {

    @SideOnly(Side.CLIENT)
    private IIcon side;

    public BlockIlsShelterSVA() {
        super(Material.iron);
        setHardness(3.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setLightOpacity(0);
        setBlockName(ReferenceSVA.ASSETS + ".ilsShelter");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityIlsShelterSVA();
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int f = e == null ? 0 : MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        w.setBlockMetadataWithNotify(x, y, z, (f + 2) & 3, 2);       // the door looks back at the placer
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int s, float hx, float hy, float hz) {
        if (p.isSneaking() && p.getHeldItem() != null) {
            return false;
        }
        if (!w.isRemote) {
            p.openGui(SVAMod.instance, GuiHandlerSVA.ILS_SHELTER, w, x, y, z);
        }
        return true;
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!w.isRemote && te instanceof TileEntityIlsShelterSVA) {
            ((TileEntityIlsShelterSVA) te).dropUpgrades(w, x, y, z);
        }
        super.breakBlock(w, x, y, z, b, meta);
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
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        side = r.registerIcon(ReferenceSVA.ASSETS + ":ils_side");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return side;
    }
}
