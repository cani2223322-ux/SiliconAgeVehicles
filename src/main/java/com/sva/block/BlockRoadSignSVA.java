package com.sva.block;

import java.util.List;

import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Wave 1, ★11: «Дорожный знак» - a thin pole with a sign plate (drawn by client/RenderRoadSVA), its face turned to the
 * one who placed it. Two blocks of four pictures each (metadata: bits 0-1 the picture, bits 2-3 the facing - 0 south,
 * 1 west, 2 north, 3 east): «roadSign» - stop, speed limit 30, speed limit 60, parking; «roadSignArrow» - straight on,
 * turn left, turn right, give way. Decorative (vehicles are not told to obey). Fits in one block: put it on a fence or a
 * wall for a tall post.
 */
public class BlockRoadSignSVA extends Block {

    public static final int PICTURES = 4;
    /** Picture names by set and picture (textures sign_NAME, lang sva.sign.NAME). */
    public static final String[][] NAMES = {{"stop", "30", "60", "parking"}, {"ahead", "left", "right", "yield"}};

    /** 0 = «roadSign», 1 = «roadSignArrow». */
    public final int set;
    @SideOnly(Side.CLIENT)
    public IIcon[] faces;
    @SideOnly(Side.CLIENT)
    public IIcon back, pole;

    public BlockRoadSignSVA(int set) {
        super(Material.iron);
        this.set = set;
        setHardness(1.0F);
        setResistance(5.0F);
        setStepSound(soundTypeMetal);
        setBlockName(ReferenceSVA.ASSETS + (set == 0 ? ".roadSign" : ".roadSignArrow"));
        setLightOpacity(0);
    }

    public static int picture(int meta) {
        return meta & 3;
    }

    public static int facing(int meta) {
        return (meta >> 2) & 3;
    }

    /** The picture's name (sign_NAME / sva.sign.NAME). */
    public String name(int meta) {
        return NAMES[set & 1][picture(meta)];
    }

    @Override
    public int damageDropped(int meta) {
        return picture(meta);
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
    public int getRenderType() {
        return RoadsSVA.renderId;
    }

    /** The face turns to the placer: the facing opposite his look. */
    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int look = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        w.setBlockMetadataWithNotify(x, y, z, picture(s.getItemDamage()) | ((look + 2) & 3) << 2, 2);
    }

    /** The pole only collides (a vehicle bumps into it like into a fence post). */
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        return AxisAlignedBB.getBoundingBox(x + 0.4375, y, z + 0.4375, x + 0.5625, y + 1.0, z + 0.5625);
    }

    @Override
    public void setBlockBoundsBasedOnState(IBlockAccess w, int x, int y, int z) {
        int f = facing(w.getBlockMetadata(x, y, z));
        if (f == 0 || f == 2) {
            setBlockBounds(0.15F, 0F, 0.4F, 0.85F, 1F, 0.6F);
        } else {
            setBlockBounds(0.4F, 0F, 0.15F, 0.6F, 1F, 0.85F);
        }
    }

    @Override
    public void setBlockBoundsForItemRender() {
        setBlockBounds(0F, 0F, 0F, 1F, 1F, 1F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubBlocks(Item item, CreativeTabs tab, List list) {
        for (int i = 0; i < PICTURES; i++) {
            list.add(new ItemStack(item, 1, i));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        faces = new IIcon[PICTURES];
        for (int i = 0; i < PICTURES; i++) {
            faces[i] = r.registerIcon(ReferenceSVA.ASSETS + ":sign_" + NAMES[set & 1][i]);
        }
        back = r.registerIcon(ReferenceSVA.ASSETS + ":sign_back");
        pole = r.registerIcon(ReferenceSVA.ASSETS + ":sign_pole");
    }

    /** The picture (the flat item icon, break particles). */
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return faces[picture(meta)];
    }
}
