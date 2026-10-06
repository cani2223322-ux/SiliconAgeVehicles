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
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * «Взлётно-посадочная полоса» (plan §3): a plane rolling on it needs half the take-off run (PlaneMath.GROUND_EFF
 * applies off it) and brakes softly (no hull scuffs). Metadata (APPEND-ONLY): bit 0 = lit edge (lamps, light 9 -
 * they glow at night), bit 1 = the centre line runs along X (set from the placer's facing; the item keeps bit 0 only).
 */
public class BlockRunwaySVA extends Block {

    public static final int PLAIN = 0, LIT = 1, VARIANTS = 2, AXIS_X = 2;
    public static final int LIGHT = 9;

    private IIcon top, topX, lit, litX, side;

    public BlockRunwaySVA() {
        super(Material.rock);
        setHardness(1.5F);
        setResistance(10.0F);
        setStepSound(soundTypeStone);
        setHarvestLevel("pickaxe", 0);
        setBlockName(ReferenceSVA.ASSETS + ".runway");
    }

    public static int variant(int meta) {
        return meta & LIT;
    }

    @Override
    public int damageDropped(int meta) {
        return variant(meta);
    }

    @Override
    public int getLightValue(IBlockAccess w, int x, int y, int z) {
        return w.getBlock(x, y, z) == this && variant(w.getBlockMetadata(x, y, z)) == LIT ? LIGHT : 0;
    }

    /** The centre line follows the placer's look: along X when facing east / west. */
    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int facing = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int meta = variant(s.getItemDamage()) | (facing == 1 || facing == 3 ? AXIS_X : 0);
        w.setBlockMetadataWithNotify(x, y, z, meta, 2);
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
        top = r.registerIcon(a + "runway");
        topX = r.registerIcon(a + "runway_x");
        lit = r.registerIcon(a + "runwayLit");
        litX = r.registerIcon(a + "runwayLit_x");
        side = r.registerIcon(a + "runway_side");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        if (s != 1) {
            return side;
        }
        boolean x = (meta & AXIS_X) != 0;
        return variant(meta) == LIT ? (x ? litX : lit) : (x ? topX : top);
    }
}
