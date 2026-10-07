package com.sva.block;

import java.util.List;

import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Wave 2, ★7: «Плита гаража». Metadata (APPEND-ONLY): 0 the lift pad (steel with black-yellow hazard edges), 1 the lamp
 * plate (a recessed work lamp, light 12). Plates touching a Repair Garage on its level (4-way, up to 64) make its floor,
 * like the charging platform; the size decides which vehicles fit (VehicleType.platformSize). A plate placed or broken
 * nudges the garages within NOTIFY blocks to re-scan at once.
 */
public class BlockGaragePlateSVA extends Block {

    public static final int PAD = 0, LAMP = 1, VARIANTS = 2;
    public static final int NOTIFY = 8;

    private IIcon pad, lamp, side;

    public BlockGaragePlateSVA() {
        super(Material.iron);
        setHardness(2.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 0);
        setBlockName(ReferenceSVA.ASSETS + ".garagePlate");
    }

    public static int variant(int meta) {
        return meta < 0 || meta >= VARIANTS ? PAD : meta;
    }

    @Override
    public int damageDropped(int meta) {
        return variant(meta);
    }

    @Override
    public int getLightValue(IBlockAccess w, int x, int y, int z) {
        return variant(w.getBlockMetadata(x, y, z)) == LAMP ? 12 : 0;
    }

    @Override
    public void onBlockAdded(World w, int x, int y, int z) {
        super.onBlockAdded(w, x, y, z);
        nudge(w, x, y, z);
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        super.breakBlock(w, x, y, z, b, meta);
        nudge(w, x, y, z);
    }

    /** Garages on this level nearby re-scan their floor next tick. */
    static void nudge(World w, int x, int y, int z) {
        if (w == null || w.isRemote) {
            return;
        }
        for (int dx = -NOTIFY; dx <= NOTIFY; dx++) {
            for (int dz = -NOTIFY; dz <= NOTIFY; dz++) {
                if (!w.blockExists(x + dx, y, z + dz)) {
                    continue;
                }
                TileEntity te = w.getTileEntity(x + dx, y, z + dz);
                if (te instanceof TileEntityGarageSVA) {
                    ((TileEntityGarageSVA) te).platformChanged();
                }
            }
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubBlocks(Item item, CreativeTabs tab, List list) {
        for (int i = 0; i < VARIANTS; i++) {
            list.add(new ItemStack(item, 1, i));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        String a = ReferenceSVA.ASSETS + ":";
        pad = r.registerIcon(a + "garagePad");
        lamp = r.registerIcon(a + "garageLamp");
        side = r.registerIcon(a + "garagePlate_side");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        if (s != 1) {
            return side;
        }
        return variant(meta) == LAMP ? lamp : pad;
    }
}
