package com.sva.block;

import java.util.Random;

import com.sva.ReferenceSVA;
import com.sva.vehicle.TrafficLightMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Wave 1, ★11: «Светофор» - redstone power = green, none = red, every change through 1 s of amber (TrafficLightMath; a
 * scheduled block update ends the amber). Metadata: bits 0-1 the facing (the lamps look 0 south, 1 west, 2 north,
 * 3 east - toward the placer), bits 2-3 the state (TrafficLightMath RED / AMBER / GREEN). The housing glows (light LIGHT);
 * the lit lamp is drawn full-bright by client/RenderRoadSVA. Put it on a fence / wall for a tall post.
 */
public class BlockTrafficLightSVA extends Block {

    public static final int LIGHT = 8;

    @SideOnly(Side.CLIENT)
    public IIcon body, lamp, item;

    public BlockTrafficLightSVA() {
        super(Material.iron);
        setHardness(1.5F);
        setResistance(5.0F);
        setStepSound(soundTypeMetal);
        setBlockName(ReferenceSVA.ASSETS + ".trafficLight");
        setLightOpacity(0);
        setLightLevel(LIGHT / 15F);
    }

    public static int facing(int meta) {
        return meta & 3;
    }

    public static int state(int meta) {
        return (meta >> 2) & 3;
    }

    private static int meta(int facing, int state) {
        return (facing & 3) | (state & 3) << 2;
    }

    @Override
    public int damageDropped(int meta) {
        return 0;
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

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int look = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        boolean powered = w.isBlockIndirectlyGettingPowered(x, y, z);
        w.setBlockMetadataWithNotify(x, y, z, meta((look + 2) & 3, powered ? TrafficLightMath.GREEN : TrafficLightMath.RED), 2);
    }

    @Override
    public void onNeighborBlockChange(World w, int x, int y, int z, Block b) {
        if (w.isRemote) {
            return;
        }
        int m = w.getBlockMetadata(x, y, z);
        int s = state(m), n = TrafficLightMath.onPower(s, w.isBlockIndirectlyGettingPowered(x, y, z));
        if (n != s) {
            w.setBlockMetadataWithNotify(x, y, z, meta(facing(m), n), 2);
            w.scheduleBlockUpdate(x, y, z, this, TrafficLightMath.AMBER_TICKS);
        }
    }

    @Override
    public void updateTick(World w, int x, int y, int z, Random r) {
        int m = w.getBlockMetadata(x, y, z);
        if (state(m) == TrafficLightMath.AMBER) {
            w.setBlockMetadataWithNotify(x, y, z, meta(facing(m), TrafficLightMath.amberDone(w.isBlockIndirectlyGettingPowered(x, y, z))), 2);
        }
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        return AxisAlignedBB.getBoundingBox(x + 0.3, y, z + 0.3, x + 0.7, y + 1.0, z + 0.7);
    }

    @Override
    public void setBlockBoundsBasedOnState(IBlockAccess w, int x, int y, int z) {
        setBlockBounds(0.3F, 0F, 0.3F, 0.7F, 1F, 0.7F);
    }

    @Override
    public void setBlockBoundsForItemRender() {
        setBlockBounds(0F, 0F, 0F, 1F, 1F, 1F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        body = r.registerIcon(ReferenceSVA.ASSETS + ":tl_body");
        lamp = r.registerIcon(ReferenceSVA.ASSETS + ":tl_lamp");
        item = r.registerIcon(ReferenceSVA.ASSETS + ":trafficLight");
    }

    /** The flat item icon / break particles. */
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return item;
    }
}
