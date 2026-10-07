package com.sva.block;

import java.util.List;

import com.sva.ReferenceSVA;
import com.sva.init.ModContentSVA;
import com.sva.vehicle.RoadMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Wave 1, ★11 (mock-up veh_more_5_roads.png): «Асфальт», «Бетонная плита» and the asphalt with markings - full blocks a
 * vehicle on wheels / tracks drives faster and wears less on (RoadMath: asphalt x1.3 / wear x0.5, concrete x1.2 / x0.75).
 * Metadata (APPEND-ONLY; the item keeps the variant's first meta, the placer's look picks the turn):
 * 0 asphalt, 1 concrete, 2/3 dashed centre line along Z / X, 4/5 solid centre line along Z / X, 6..9 edge line on the
 * north / east / south / west side, 10..13 an arrow pointing north / east / south / west. Lines run along the placer's
 * look, the edge line on his right (right-hand traffic), the arrow points where he looks.
 */
public class BlockRoadSVA extends Block {

    public static final int ASPHALT = 0, CONCRETE = 1, DASH = 2, SOLID = 4, EDGE = 6, ARROW = 10, METAS = 14;
    /** The item variants (creative tab / recipes), each its first meta. */
    public static final int[] VARIANTS = {ASPHALT, CONCRETE, DASH, SOLID, EDGE, ARROW};
    /** Texture name per meta (top face). */
    static final String[] TOPS = {"asphalt", "concrete", "road_dash_z", "road_dash_x", "road_solid_z", "road_solid_x", "road_edge_n",
            "road_edge_e", "road_edge_s", "road_edge_w", "road_arrow_n", "road_arrow_e", "road_arrow_s", "road_arrow_w"};

    @SideOnly(Side.CLIENT)
    private IIcon[] tops;

    public BlockRoadSVA() {
        super(Material.rock);
        setHardness(1.5F);
        setResistance(10.0F);
        setStepSound(soundTypeStone);
        setHarvestLevel("pickaxe", 0);
        setBlockName(ReferenceSVA.ASSETS + ".road");
    }

    /** The item variant (first meta) of a placed meta. */
    public static int variant(int meta) {
        if (meta < DASH) {
            return Math.max(0, meta);
        }
        return meta < SOLID ? DASH : meta < EDGE ? SOLID : meta < ARROW ? EDGE : meta < METAS ? ARROW : ASPHALT;
    }

    /** The road surface of a meta (RoadMath): the concrete slab, else asphalt. */
    public static int surface(int meta) {
        return meta == CONCRETE ? RoadMath.CONCRETE : RoadMath.ASPHALT;
    }

    /**
     * The placed meta for a variant and the placer's horizontal facing (0 south, 1 west, 2 north, 3 east - vanilla's
     * yaw quarters): lines along the look, the edge line on the right, the arrow ahead.
     */
    public static int placed(int variant, int facing) {
        int f = facing & 3;
        boolean alongX = f == 1 || f == 3;
        switch (variant(variant)) {
            case DASH:
                return DASH + (alongX ? 1 : 0);
            case SOLID:
                return SOLID + (alongX ? 1 : 0);
            case EDGE:
                return EDGE + (f + 3) % 4;                     // N E S W: south-facing -> west, west -> north ...
            case ARROW:
                return ARROW + (f + 2) % 4;                    // facing south -> the arrow points south (index 2)
            default:
                return variant(variant);
        }
    }

    /** The surface under a vehicle standing at (x, y, z) - the block its wheels / tracks are on (RoadMath.NONE if no road). */
    public static int surfaceAt(World w, double x, double y, double z) {
        if (w == null || ModContentSVA.road == null) {
            return RoadMath.NONE;
        }
        int bx = MathHelper.floor_double(x), by = MathHelper.floor_double(y - 0.2), bz = MathHelper.floor_double(z);
        if (by < 0 || by > 255 || w.getBlock(bx, by, bz) != ModContentSVA.road) {
            return RoadMath.NONE;
        }
        return surface(w.getBlockMetadata(bx, by, bz));
    }

    @Override
    public int damageDropped(int meta) {
        return variant(meta);
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int facing = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        w.setBlockMetadataWithNotify(x, y, z, placed(s.getItemDamage(), facing), 2);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubBlocks(Item item, CreativeTabs tab, List list) {
        for (int v : VARIANTS) {
            list.add(new ItemStack(item, 1, v));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        tops = new IIcon[METAS];
        for (int i = 0; i < METAS; i++) {
            tops[i] = r.registerIcon(ReferenceSVA.ASSETS + ":" + TOPS[i]);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        int m = meta >= 0 && meta < METAS ? meta : 0;
        if (side != 1) {
            return tops[m == CONCRETE ? CONCRETE : ASPHALT];
        }
        return tops[m];
    }
}
