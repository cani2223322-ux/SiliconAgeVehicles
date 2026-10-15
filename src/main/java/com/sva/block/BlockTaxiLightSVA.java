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
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

/**
 * Airport wave B2 (plan-next §9, item 27; mock-up veh_airport_impl_b2_5_taxi): «Огонь рулёжки» - a flush light set into the
 * taxiway (a plate 1/16 high, walked and taxied over), one block by metadata:
 * <ul>
 * <li>CENTRE (green) - the taxiway's centre line; the lights of a line link into the taxi graph by themselves
 * (airport.TaxiGraph: the nearest in each direction within LINK blocks) and light up ahead of a cleared aircraft
 * («следуй за мной»);</li>
 * <li>EDGE (blue) - the taxiway's edges, burning at the tower's taxi step;</li>
 * <li>STOP (red) - a stop bar across the taxiway at a runway holding point (a node of the graph: a departure's route ends
 * here); it burns while the tower is on and goes out for the line-up / take-off clearance;</li>
 * <li>STAND (yellow) - a parking stand (an arrival's route ends at the nearest one).</li>
 * </ul>
 * Placed / broken it puts itself into / takes itself out of the save's airport list (AirportDataSVA kinds K_TAXI..K_STAND).
 * The lights themselves are sprites drawn in one batch (client.AirportRenderSVA), the plate is an ordinary thin block.
 */
public class BlockTaxiLightSVA extends Block {

    public static final int CENTRE = 0, EDGE = 1, STOP = 2, STAND = 3, KINDS = 4;
    public static final String[] KEYS = {"centre", "edge", "stop", "stand"};

    @SideOnly(Side.CLIENT)
    private IIcon[] icons;

    public BlockTaxiLightSVA() {
        super(Material.iron);
        setHardness(1.0F);
        setResistance(6.0F);
        setStepSound(soundTypeMetal);
        setLightOpacity(0);
        setLightLevel(0.25F);
        setBlockBounds(0F, 0F, 0F, 1F, 1F / 16F, 1F);
        setBlockName(ReferenceSVA.ASSETS + ".taxiLight");
    }

    @Override
    public int damageDropped(int meta) {
        return Math.max(0, Math.min(KINDS - 1, meta));
    }

    /** Server: into the save's airport list. */
    public static void register(World w, int x, int y, int z) {
        if (w == null || w.isRemote) {
            return;
        }
        AirportDataSVA d = AirportDataSVA.get(w);
        if (d == null) {
            return;
        }
        AirportDataSVA.Device dev = new AirportDataSVA.Device();
        dev.key = AirportDataSVA.key(w, x, y, z);
        dev.dim = w.provider.dimensionId;
        dev.kind = AirportDataSVA.K_TAXI + Math.max(0, Math.min(KINDS - 1, w.getBlockMetadata(x, y, z)));
        dev.x = x + 0.5;
        dev.y = y;
        dev.z = z + 0.5;
        d.putDevice(dev);
    }

    @Override
    public void onBlockAdded(World w, int x, int y, int z) {
        super.onBlockAdded(w, x, y, z);
        register(w, x, y, z);
    }

    @Override
    public void onPostBlockPlaced(World w, int x, int y, int z, int meta) {
        register(w, x, y, z);                                       // the metadata is set by now
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        if (!w.isRemote) {
            AirportDataSVA d = AirportDataSVA.get(w);
            if (d != null) {
                d.remove(AirportDataSVA.key(w, x, y, z));
            }
        }
        super.breakBlock(w, x, y, z, b, meta);
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
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        return AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1F / 16F, z + 1);
    }

    @Override
    public void setBlockBoundsForItemRender() {
        setBlockBounds(0F, 0.4F, 0F, 1F, 0.6F, 1F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubBlocks(Item item, CreativeTabs tab, List list) {
        for (int i = 0; i < KINDS; i++) {
            list.add(new ItemStack(item, 1, i));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        icons = new IIcon[KINDS];
        for (int i = 0; i < KINDS; i++) {
            icons[i] = r.registerIcon(ReferenceSVA.ASSETS + ":taxi_" + KEYS[i]);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return icons[Math.max(0, Math.min(KINDS - 1, meta))];
    }
}
