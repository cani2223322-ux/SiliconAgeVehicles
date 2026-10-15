package com.sva.block;

import com.sva.ReferenceSVA;
import com.sva.airport.IlsPairing;
import com.sva.init.ModContentSVA;
import com.sva.vehicle.LinerIls;

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
import net.minecraft.world.World;

/**
 * Airport wave B1 (plan-next §9, item 24; mock-up veh_airport_impl_3_markings): «Торец полосы» - the threshold marker. One
 * at each end of a runway on its centre line (best: just beyond the last runway block, flush with the surface - the
 * planner tablet puts them there), placed looking DOWN the runway (the facing); two of them facing each other make the
 * runway (IlsPairing): its paint (the piano keys, «27» / «09», the centre line, the touchdown zone, the aiming point, the
 * side stripes) and its lights are drawn by themselves over the runway blocks between them (client.AirportRenderSVA). RMB:
 * its screen - the course (by the look), the width, this end's lights (AUTO / 0..5) and its approach lights. A plain cube
 * with the green threshold bar on top.
 */
public class BlockRunwayEndSVA extends BlockContainer {

    @SideOnly(Side.CLIENT)
    private IIcon top, side;

    public BlockRunwayEndSVA() {
        super(Material.rock);
        setHardness(1.5F);
        setResistance(10.0F);
        setStepSound(soundTypeStone);
        setHarvestLevel("pickaxe", 0);
        setBlockName(ReferenceSVA.ASSETS + ".runwayEnd");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityRunwayEndSVA();
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (w.isRemote || !(te instanceof TileEntityRunwayEndSVA)) {
            return;
        }
        setup(w, x, y, z, (TileEntityRunwayEndSVA) te, LinerIls.suggestCourse(e == null ? 0F : e.rotationYaw));
    }

    /** A marker set up looking along `course`: the runway's surface and width found ahead (the runway blocks there). */
    public static void setup(World w, int x, int y, int z, TileEntityRunwayEndSVA t, double course) {
        t.facing = course;
        t.elev = BlockIlsSVA.flush(w, x, y, z) || runwayAt(w, x, y, z, course, 0) ? y + 1 : y;
        int wd = width(w, x, y, z, course);
        t.width = wd > 0 ? wd : 5;
        t.ready = true;
        t.register();
    }

    /** A runway block 1..2 blocks ahead along the course at the marker's level (dy 0) or under it (dy -1). */
    static boolean runwayAt(World w, int x, int y, int z, double course, int dy) {
        double c = Math.toRadians(course);
        for (int k = 1; k <= 2; k++) {
            int ax = (int) Math.floor(x + 0.5 + Math.sin(c) * k), az = (int) Math.floor(z + 0.5 - Math.cos(c) * k);
            if (w.getBlock(ax, y + dy, az) == ModContentSVA.runway) {
                return true;
            }
        }
        return false;
    }

    /** The runway blocks across the course, two blocks ahead (0: none found). */
    static int width(World w, int x, int y, int z, double course) {
        double c = Math.toRadians(course);
        for (int dy = 0; dy >= -1; dy--) {
            for (int k = 1; k <= 3; k++) {
                double ax = x + 0.5 + Math.sin(c) * k, az = z + 0.5 - Math.cos(c) * k;
                if (w.getBlock((int) Math.floor(ax), y + dy, (int) Math.floor(az)) != ModContentSVA.runway) {
                    continue;
                }
                int n = 1;
                for (int s = -1; s <= 1; s += 2) {
                    for (int j = 1; j <= IlsPairing.WIDTH_MAX; j++) {
                        double bx = ax + Math.cos(c) * j * s, bz = az + Math.sin(c) * j * s;
                        if (w.getBlock((int) Math.floor(bx), y + dy, (int) Math.floor(bz)) != ModContentSVA.runway) {
                            break;
                        }
                        n++;
                    }
                }
                return Math.max(IlsPairing.WIDTH_MIN, Math.min(IlsPairing.WIDTH_MAX, n));
            }
        }
        return 0;
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int s, float hx, float hy, float hz) {
        if (p.isSneaking() && p.getHeldItem() != null) {
            return false;
        }
        if (w.isRemote) {
            com.sva.SVAMod.proxy.openApt(x, y, z);
        }
        return true;
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
    public int getRenderType() {
        return 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        top = r.registerIcon(ReferenceSVA.ASSETS + ":runwayEnd_top");
        side = r.registerIcon(ReferenceSVA.ASSETS + ":runway_side");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return s == 1 ? top : side;
    }
}
