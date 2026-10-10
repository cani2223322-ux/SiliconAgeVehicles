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
 * «Плита зарядной платформы» (plan §9). Metadata (APPEND-ONLY): 0 plain (marking off / blue charging / green full),
 * 1 «H» aviation plate, 2 edge plate with a lamp (light 7 - glows at night). Plates touching a Charging Station
 * on its level (4-way, up to 121) make its platform; the station tells the client their look (PlateStatesSVA).
 * A plate placed or broken nudges the stations within NOTIFY blocks to re-scan at once (they also re-scan every 5 s).
 */
public class BlockChargePlateSVA extends Block {

    public static final int PLAIN = 0, AVIA = 1, EDGE = 2, VARIANTS = 3;
    public static final int NOTIFY = 12;                 // an 11x11 platform: its far corner is 11 + 1 blocks away

    private IIcon off, on, full, avia, edge, side;

    public BlockChargePlateSVA() {
        super(Material.iron);
        setHardness(2.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 0);
        setBlockName(ReferenceSVA.ASSETS + ".chargePlate");
    }

    public static int variant(int meta) {
        return meta < 0 || meta >= VARIANTS ? PLAIN : meta;
    }

    @Override
    public int damageDropped(int meta) {
        return variant(meta);
    }

    @Override
    public int getLightValue(IBlockAccess w, int x, int y, int z) {
        return variant(w.getBlockMetadata(x, y, z)) == EDGE ? 7 : 0;
    }

    @Override
    public void onBlockAdded(World w, int x, int y, int z) {
        super.onBlockAdded(w, x, y, z);
        nudgeStations(w, x, y, z);
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        super.breakBlock(w, x, y, z, b, meta);
        nudgeStations(w, x, y, z);
    }

    /** Stations on this level nearby re-scan their platform next tick. */
    static void nudgeStations(World w, int x, int y, int z) {
        if (w == null || w.isRemote) {
            return;
        }
        for (int dx = -NOTIFY; dx <= NOTIFY; dx++) {
            for (int dz = -NOTIFY; dz <= NOTIFY; dz++) {
                if (!w.blockExists(x + dx, y, z + dz)) {
                    continue;
                }
                TileEntity te = w.getTileEntity(x + dx, y, z + dz);
                if (te instanceof TileEntityChargerSVA) {
                    ((TileEntityChargerSVA) te).platformChanged();
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
        off = r.registerIcon(a + "chargePlate_off");
        on = r.registerIcon(a + "chargePlate_on");
        full = r.registerIcon(a + "chargePlate_full");
        avia = r.registerIcon(a + "chargePlate_H");
        edge = r.registerIcon(a + "chargePlate_edge");
        side = r.registerIcon(a + "chargePlate_side");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        if (s != 1) {
            return side;
        }
        int v = variant(meta);
        return v == AVIA ? avia : v == EDGE ? edge : off;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess w, int x, int y, int z, int s) {
        int meta = w.getBlockMetadata(x, y, z);
        if (s != 1 || variant(meta) != PLAIN) {
            return getIcon(s, meta);
        }
        int st = PlateStatesSVA.get(x, y, z);
        return st == PlateStatesSVA.CHARGING ? on : st == PlateStatesSVA.FULL ? full : off;
    }
}
