package com.sva.block;

import java.util.List;

import com.sva.ReferenceSVA;
import com.sva.vehicle.LinerIls;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Airport wave B1 (plan-next §9, items 13-14; mock-up veh_airport_impl_2_models): the ILS antennas - meta LOC «Курсовой
 * маяк (антенна 5x1)»: the localizer's array of five dipole elements on posts across the runway's centre line, placed
 * BEYOND the far end looking back along the runway (the elements run across the placer's facing); meta GS «Глиссадный
 * маяк (мачта)»: a three-block mast beside the touchdown zone. Each needs an «Аппаратная ILS» (power + monitor) within
 * TileEntityIlsShelterSVA.RANGE; the pairing (IlsPairing via AirportDataSVA) makes them one ILS station with a runway.
 * <p>
 * Placing builds the whole antenna (the master here + BlockAptPartSVA parts; ItemBlockIlsAntennaSVA checks the room).
 * RMB opens its screen (client.GuiAptSVA): the localizer's ident and frequency / the mast's angle (2.5..3.5° - the
 * window's ends; 2..5 allowed) and the station's state with every issue's hint. Shift + RMB with iron ingots repairs the
 * missing parts (one ingot each). Broken, it takes its parts with it and drops one item.
 */
public class BlockIlsAntennaSVA extends BlockContainer {

    public static final int LOC = TileEntityIlsAntennaSVA.LOC, GS = TileEntityIlsAntennaSVA.GS;
    /** The mast's angle in its screen: the real window. */
    public static final double GS_ANGLE_MIN = 2.5, GS_ANGLE_MAX = 3.5;

    @SideOnly(Side.CLIENT)
    private IIcon icon;

    public BlockIlsAntennaSVA() {
        super(Material.iron);
        setHardness(3.0F);
        setResistance(8.0F);
        setStepSound(soundTypeMetal);
        setLightOpacity(0);
        setBlockName(ReferenceSVA.ASSETS + ".ilsAntenna");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityIlsAntennaSVA();
    }

    @Override
    public int damageDropped(int meta) {
        return meta & 1;
    }

    /** The facing (MathHelper's 0..3) of a placer's yaw. */
    public static int facing(float yaw) {
        return MathHelper.floor_double(yaw * 4.0F / 360.0F + 0.5D) & 3;
    }

    /** The first part cell not free, or null (room for the antenna). */
    public static int[] room(World w, int x, int y, int z, int kind, int facing) {
        for (int[] o : TileEntityIlsAntennaSVA.parts(kind, facing)) {
            int px = x + o[0], py = y + o[1], pz = z + o[2];
            if (py < 0 || py > 255 || !w.blockExists(px, py, pz) || !w.getBlock(px, py, pz).isReplaceable(w, px, py, pz)) {
                return new int[] {px, py, pz};
            }
        }
        return null;
    }

    /** Puts the parts of a master in (the cells free). */
    public static void buildParts(World w, int x, int y, int z, int kind, int facing) {
        for (int[] o : TileEntityIlsAntennaSVA.parts(kind, facing)) {
            int px = x + o[0], py = y + o[1], pz = z + o[2];
            if (w.getBlock(px, py, pz).isReplaceable(w, px, py, pz)) {
                w.setBlock(px, py, pz, com.sva.init.ModContentSVA.aptPart, BlockAptPartSVA.metaFor(o[0], o[1], o[2]), 3);
            }
        }
    }

    /** World tests / the API: a whole antenna at (x, y, z) facing `facing`, set up; null when there is no room. */
    public static TileEntityIlsAntennaSVA place(World w, int x, int y, int z, int kind, int facing, String ident, int freq, double angle) {
        if (room(w, x, y, z, kind, facing) != null || !w.setBlock(x, y, z, com.sva.init.ModContentSVA.ilsAntenna, kind, 3)) {
            return null;
        }
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityIlsAntennaSVA)) {
            return null;
        }
        TileEntityIlsAntennaSVA t = (TileEntityIlsAntennaSVA) te;
        t.facing = facing & 3;
        buildParts(w, x, y, z, kind, t.facing);
        t.ident = LinerIls.cleanIdent(ident);
        t.freq = LinerIls.clampFreq(freq);
        t.angle = LinerIls.clampAngle(angle);
        t.register();
        return t;
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (w.isRemote || !(te instanceof TileEntityIlsAntennaSVA)) {
            return;
        }
        TileEntityIlsAntennaSVA t = (TileEntityIlsAntennaSVA) te;
        t.facing = facing(e == null ? 0F : e.rotationYaw);
        buildParts(w, x, y, z, t.kind(), t.facing);
        t.ident = BlockIlsSVA.defaultIdent(x, z);
        t.freq = LinerIls.FREQ_DEFAULT;
        t.angle = LinerIls.ANGLE_DEFAULT;
        t.register();
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityIlsAntennaSVA)) {
            return false;
        }
        TileEntityIlsAntennaSVA t = (TileEntityIlsAntennaSVA) te;
        ItemStack held = p.getHeldItem();
        if (p.isSneaking() && held != null && held.getItem() == Items.iron_ingot) {
            if (!w.isRemote) {
                int fixed = repair(w, t, p);
                p.addChatComponentMessage(new ChatComponentTranslation(fixed > 0 ? "sva.apt.msg.repaired" : "sva.apt.msg.whole", Integer.valueOf(fixed)));
            }
            return true;
        }
        if (p.isSneaking() && held != null) {
            return false;
        }
        if (w.isRemote) {
            com.sva.SVAMod.proxy.openApt(x, y, z);
        }
        return true;
    }

    /** Shift + RMB with iron: the missing parts back (an ingot each, none in creative). @return parts put back */
    static int repair(World w, TileEntityIlsAntennaSVA t, EntityPlayer p) {
        int n = 0;
        boolean[] have = t.present();
        int[][] parts = t.parts();
        for (int i = 0; i < parts.length; i++) {
            if (have[i]) {
                continue;
            }
            int px = t.xCoord + parts[i][0], py = t.yCoord + parts[i][1], pz = t.zCoord + parts[i][2];
            if (!w.getBlock(px, py, pz).isReplaceable(w, px, py, pz)) {
                continue;
            }
            if (!p.capabilities.isCreativeMode) {
                ItemStack held = p.getHeldItem();
                if (held == null || held.getItem() != Items.iron_ingot || held.stackSize <= 0) {
                    break;
                }
                held.stackSize--;
                if (held.stackSize <= 0) {
                    p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
                }
            }
            w.setBlock(px, py, pz, com.sva.init.ModContentSVA.aptPart, BlockAptPartSVA.metaFor(parts[i][0], parts[i][1], parts[i][2]), 3);
            n++;
        }
        return n;
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!w.isRemote) {
            if (te instanceof TileEntityIlsAntennaSVA) {
                TileEntityIlsAntennaSVA t = (TileEntityIlsAntennaSVA) te;
                for (int[] o : t.parts()) {
                    int px = x + o[0], py = y + o[1], pz = z + o[2];
                    if (w.getBlock(px, py, pz) instanceof BlockAptPartSVA) {
                        w.setBlock(px, py, pz, net.minecraft.init.Blocks.air, 0, 3);
                    }
                }
            }
            AirportDataSVA d = AirportDataSVA.get(w);
            if (d != null) {
                d.remove(AirportDataSVA.key(w, x, y, z));
            }
        }
        super.breakBlock(w, x, y, z, b, meta);
    }

    @Override
    public int getRenderType() {
        return -1;                                                  // client.RenderAptSVA (TESR) draws the antenna
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
    public void setBlockBoundsBasedOnState(IBlockAccess w, int x, int y, int z) {
        int m = w.getBlockMetadata(x, y, z) & 1;
        if (m == GS) {
            setBlockBounds(0.2F, 0F, 0.2F, 0.8F, 1F, 0.8F);
        } else {
            setBlockBounds(0.25F, 0F, 0.25F, 0.75F, 1F, 0.75F);
        }
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        setBlockBoundsBasedOnState(w, x, y, z);
        return super.getCollisionBoundingBoxFromPool(w, x, y, z);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World w, int x, int y, int z) {
        setBlockBoundsBasedOnState(w, x, y, z);
        return super.getSelectedBoundingBoxFromPool(w, x, y, z);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubBlocks(Item item, CreativeTabs tab, List list) {
        list.add(new ItemStack(item, 1, LOC));
        list.add(new ItemStack(item, 1, GS));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        icon = r.registerIcon(ReferenceSVA.ASSETS + ":ils_side");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return icon;
    }
}
