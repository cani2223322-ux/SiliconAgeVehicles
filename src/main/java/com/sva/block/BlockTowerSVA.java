package com.sva.block;

import com.sva.ReferenceSVA;

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
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Airport wave B2 (plan-next §9, item 29; mock-up veh_airport_impl_b2_1_tower): «Диспетчерская вышка» - the airport's
 * control tower. Placing builds the whole tower: this master at the foot and TileEntityTowerSVA.PARTS shaft / cab parts
 * straight up (BlockAptPartSVA metas 8..12; ItemBlockTowerSVA checks the room); its model (client.RenderAptSVA): a concrete
 * foot, the shaft with its lit stair windows, the overhanging glazed cab with the consoles inside, the roof with its
 * antennas, the obstruction light and the aerodrome beacon (green / white at night). RMB: its screen (the owner, his access
 * list, ops); without access - a message. Broken (the master or any part): the whole tower goes, the upgrade modules drop,
 * the airport loses its name and its air-defence zone.
 */
public class BlockTowerSVA extends BlockContainer {

    @SideOnly(Side.CLIENT)
    private IIcon icon;

    public BlockTowerSVA() {
        super(Material.iron);
        setHardness(4.0F);
        setResistance(12.0F);
        setStepSound(soundTypeStone);
        setLightOpacity(0);
        setBlockName(ReferenceSVA.ASSETS + ".tower");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityTowerSVA();
    }

    /** The first part cell not free, or null (room for the tower). */
    public static int[] room(World w, int x, int y, int z) {
        for (int dy = 1; dy <= TileEntityTowerSVA.PARTS; dy++) {
            int py = y + dy;
            if (py > 255 || !w.blockExists(x, py, z) || !w.getBlock(x, py, z).isReplaceable(w, x, py, z)) {
                return new int[] {x, py, z};
            }
        }
        return null;
    }

    static void buildParts(World w, int x, int y, int z) {
        for (int dy = 1; dy <= TileEntityTowerSVA.PARTS; dy++) {
            if (w.getBlock(x, y + dy, z).isReplaceable(w, x, y + dy, z)) {
                w.setBlock(x, y + dy, z, com.sva.init.ModContentSVA.aptPart, BlockAptPartSVA.metaFor(0, dy, 0), 3);
            }
        }
    }

    /** World tests / the API: a whole tower at (x, y, z), its owner; null when there is no room. */
    public static TileEntityTowerSVA place(World w, int x, int y, int z, int facing, String owner) {
        if (room(w, x, y, z) != null || !w.setBlock(x, y, z, com.sva.init.ModContentSVA.tower, 0, 3)) {
            return null;
        }
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityTowerSVA)) {
            return null;
        }
        TileEntityTowerSVA t = (TileEntityTowerSVA) te;
        t.facing = facing & 3;
        t.setOwnerName(owner);
        buildParts(w, x, y, z);
        t.record();
        return t;
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (w.isRemote || !(te instanceof TileEntityTowerSVA)) {
            return;
        }
        TileEntityTowerSVA t = (TileEntityTowerSVA) te;
        t.facing = BlockIlsAntennaSVA.facing(e == null ? 0F : e.rotationYaw) + 2 & 3;    // the cab's front looks at the placer
        if (e instanceof EntityPlayer) {
            t.setOwner((EntityPlayer) e);
        }
        buildParts(w, x, y, z);
        t.record();
        w.markBlockForUpdate(x, y, z);
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityTowerSVA)) {
            return false;
        }
        if (p.isSneaking() && p.getHeldItem() != null) {
            return false;
        }
        TileEntityTowerSVA t = (TileEntityTowerSVA) te;
        if (!w.isRemote) {
            if (!t.mayUse(p)) {
                p.addChatComponentMessage(new ChatComponentTranslation("sva.tower.msg.noAccess", t.getOwnerName()));
            }
            return true;
        }
        com.sva.SVAMod.proxy.openTower(x, y, z);
        return true;
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!w.isRemote) {
            if (te instanceof TileEntityTowerSVA) {
                ((TileEntityTowerSVA) te).dropUpgrades(w, x, y, z);
            }
            for (int dy = 1; dy <= TileEntityTowerSVA.PARTS; dy++) {
                if (w.getBlock(x, y + dy, z) instanceof BlockAptPartSVA) {
                    w.setBlock(x, y + dy, z, net.minecraft.init.Blocks.air, 0, 3);
                }
            }
            String key = AirportDataSVA.key(w, x, y, z);
            AirportDataSVA d = AirportDataSVA.get(w);
            if (d != null) {
                d.remove(key);
            }
            AtcServerSVA.INSTANCE.forgetTower(w, key);
        }
        super.breakBlock(w, x, y, z, b, meta);
    }

    @Override
    public int getRenderType() {
        return -1;                                                  // client.RenderAptSVA (TESR) draws the tower
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
        setBlockBounds(0F, 0F, 0F, 1F, 1F, 1F);
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        setBlockBoundsBasedOnState(w, x, y, z);
        return super.getCollisionBoundingBoxFromPool(w, x, y, z);
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
