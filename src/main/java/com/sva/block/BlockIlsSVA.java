package com.sva.block;

import com.sva.ReferenceSVA;
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
 * Liner wave 4 (plan-next §7): «Курсо-глиссадный маяк» - the ILS of a runway for the SA-320's autopilot: one block at the
 * runway's threshold (best set flush into the surface on the centre line - the top is a runway-grey slab with threshold
 * bars; or beside the strip with the centre line's offset set in its screen). Placed, it takes the placer's facing as the
 * landing course (look down the runway when placing) and a 3° glide slope; RMB opens its screen (client.GuiIlsSVA): the
 * course (and «по взгляду»), the angle, the ident (3-4 letters), the frequency (108.10..111.95), the offset. The save keeps
 * every beacon (IlsDataSVA) - the airliners find them within vehicles.linerIlsRange without loading their chunks.
 */
public class BlockIlsSVA extends BlockContainer {

    @SideOnly(Side.CLIENT)
    private IIcon top, side;

    public BlockIlsSVA() {
        super(Material.iron);
        setHardness(2.0F);
        setResistance(6.0F);
        setStepSound(soundTypeMetal);
        setBlockName(ReferenceSVA.ASSETS + ".ils");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityIlsSVA();
    }

    @Override
    public int getRenderType() {
        return 0;
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (w.isRemote || !(te instanceof TileEntityIlsSVA)) {
            return;
        }
        TileEntityIlsSVA t = (TileEntityIlsSVA) te;
        t.st.course = LinerIls.suggestCourse(e.rotationYaw);
        t.st.angle = LinerIls.ANGLE_DEFAULT;
        t.st.elev = flush(w, x, y, z) ? y + 1 : y;
        t.st.ident = defaultIdent(x, z);
        t.st.freq = LinerIls.FREQ_DEFAULT;
        t.ready = true;
        t.publish();
    }

    /** Set into the surface: at least two of its four sides are solid blocks (the touchdown elevation is then its top). */
    static boolean flush(World w, int x, int y, int z) {
        int n = 0;
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            Block b = w.getBlock(x + o[0], y, z + o[1]);
            n += b.getMaterial().isSolid() && b.isOpaqueCube() ? 1 : 0;
        }
        return n >= 2;
    }

    /** "I" + two letters from the place (a different ident for each beacon, the player renames it). */
    static String defaultIdent(int x, int z) {
        int h = Math.abs(x * 31 + z * 17);
        return "I" + (char) ('A' + h % 26) + (char) ('A' + h / 26 % 26);
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        if (p.isSneaking() && p.getHeldItem() != null) {
            return false;
        }
        TileEntity te = w.getTileEntity(x, y, z);
        if (w.isRemote && te instanceof TileEntityIlsSVA) {
            com.sva.SVAMod.proxy.openIls((TileEntityIlsSVA) te);
        }
        return true;
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        if (!w.isRemote) {
            IlsDataSVA d = IlsDataSVA.get(w);
            if (d != null) {
                d.remove(w.provider.dimensionId + ":" + x + ":" + y + ":" + z);
            }
        }
        super.breakBlock(w, x, y, z, b, meta);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        top = r.registerIcon(ReferenceSVA.ASSETS + ":ils_top");
        side = r.registerIcon(ReferenceSVA.ASSETS + ":ils_side");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return s == 1 ? top : s == 0 ? side : side;
    }
}
