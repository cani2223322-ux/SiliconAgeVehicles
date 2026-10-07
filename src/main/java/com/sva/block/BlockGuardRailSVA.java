package com.sva.block;

import java.util.List;

import com.sva.ReferenceSVA;
import com.sva.entity.EntityVehicleSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Wave 1, ★11: «Отбойник» - a steel W-beam on posts along the placer's look (metadata 0 = along Z, 1 = along X; drawn by
 * client/RenderRoadSVA). It STOPS vehicles: for a vehicle that rolls on the ground (EntityVehicleSVA, not an aircraft)
 * its collision box is VEHICLE_HEIGHT tall - higher than any step (tracks climb 2 blocks) - so the move into it ends at
 * the rail, the speed drops to what the wall left (EntityVehicleSVA.physicsStep) and the server refuses moves into it;
 * the hit wears the hull at a quarter (RAIL_WEAR, the rail gives). For everything else it is fence-high (1.5).
 */
public class BlockGuardRailSVA extends Block {

    public static final double VEHICLE_HEIGHT = 3.0, OTHERS_HEIGHT = 1.5;
    /** Half the thickness of the collision box across the rail. */
    public static final double HALF = 0.15;
    /** A vehicle's hit into a guard rail wears the hull x this (the rail gives). */
    public static final double RAIL_WEAR = 0.25;

    @SideOnly(Side.CLIENT)
    public IIcon steel;

    public BlockGuardRailSVA() {
        super(Material.iron);
        setHardness(2.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 0);
        setBlockName(ReferenceSVA.ASSETS + ".guardRail");
        setLightOpacity(0);
    }

    public static boolean alongX(int meta) {
        return (meta & 1) != 0;
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
        w.setBlockMetadataWithNotify(x, y, z, look == 1 || look == 3 ? 1 : 0, 2);
    }

    /** Does an entity get the tall box: a vehicle on wheels / tracks / the hoverbike (not an aircraft). */
    public static boolean tallFor(Entity e) {
        return e instanceof EntityVehicleSVA && !((EntityVehicleSVA) e).getVehicleType().movement.flies;
    }

    /** The collision box at (x, y, z) for that height. */
    public static AxisAlignedBB box(int x, int y, int z, int meta, double height) {
        if (alongX(meta)) {
            return AxisAlignedBB.getBoundingBox(x, y, z + 0.5 - HALF, x + 1, y + height, z + 0.5 + HALF);
        }
        return AxisAlignedBB.getBoundingBox(x + 0.5 - HALF, y, z, x + 0.5 + HALF, y + height, z + 1);
    }

    @Override
    public void addCollisionBoxesToList(World w, int x, int y, int z, AxisAlignedBB mask, List list, Entity e) {
        AxisAlignedBB b = box(x, y, z, w.getBlockMetadata(x, y, z), tallFor(e) ? VEHICLE_HEIGHT : OTHERS_HEIGHT);
        if (mask.intersectsWith(b)) {
            list.add(b);
        }
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        return box(x, y, z, w.getBlockMetadata(x, y, z), OTHERS_HEIGHT);
    }

    @Override
    public void setBlockBoundsBasedOnState(IBlockAccess w, int x, int y, int z) {
        if (alongX(w.getBlockMetadata(x, y, z))) {
            setBlockBounds(0F, 0F, 0.3F, 1F, 0.85F, 0.7F);
        } else {
            setBlockBounds(0.3F, 0F, 0F, 0.7F, 0.85F, 1F);
        }
    }

    @Override
    public void setBlockBoundsForItemRender() {
        setBlockBounds(0F, 0F, 0F, 1F, 1F, 1F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        steel = r.registerIcon(ReferenceSVA.ASSETS + ":guardRail");
        blockIcon = r.registerIcon(ReferenceSVA.ASSETS + ":guardRail_item");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return blockIcon;
    }
}
