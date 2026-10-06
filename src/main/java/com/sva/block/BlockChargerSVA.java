package com.sva.block;

import java.util.List;

import com.sc.energy.Tier;
import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * «Зарядная станция техники». Metadata = the core's Tier ordinal (append-only, like the core's pads);
 * MV only so far. The front (screen, chargeStationFront2 / On) faces the player who placed it (kept in the tile).
 * Right-click: the station screen (platform, vehicle, upgrades, modules, settings). Comparator: the served vehicle's
 * charge; strong redstone 15 while it is full («сигнал при полном»).
 */
public class BlockChargerSVA extends BlockContainer {

    /** Tiers shown in creative / with recipes. */
    public static final Tier[] TIERS = {Tier.MV};

    private IIcon top, topOn, side, sideOn, bottom, front, frontOn;

    public BlockChargerSVA() {
        super(Material.iron);
        setHardness(3.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 1);
        setBlockName(ReferenceSVA.ASSETS + ".charger");
    }

    public static Tier tierFor(int meta) {
        return Tier.byOrdinal(meta);
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityChargerSVA(tierFor(meta));
    }

    @Override
    public int damageDropped(int meta) {
        return meta;
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int dir = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int[] faces = {2, 5, 3, 4};                     // the front looks at the player
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityChargerSVA) {
            TileEntityChargerSVA c = (TileEntityChargerSVA) te;
            c.setFacing(faces[dir]);
            if (!w.isRemote && e instanceof EntityPlayer) {
                c.setOwner(e.getUniqueID().toString(), ((EntityPlayer) e).getCommandSenderName());
            }
            w.markBlockForUpdate(x, y, z);
        }
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int s, float hx, float hy, float hz) {
        if (p.isSneaking()) {
            return false;
        }
        if (!w.isRemote && w.getTileEntity(x, y, z) instanceof TileEntityChargerSVA) {
            p.openGui(com.sva.SVAMod.instance, com.sva.inventory.GuiHandlerSVA.CHARGER, w, x, y, z);
        }
        return true;
    }

    @Override
    public void onNeighborBlockChange(World w, int x, int y, int z, Block b) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityChargerSVA) {
            ((TileEntityChargerSVA) te).platformChanged();
        }
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityChargerSVA && !w.isRemote) {
            TileEntityChargerSVA c = (TileEntityChargerSVA) te;
            for (int i = 0; i < c.getSizeInventory(); i++) {
                ItemStack st = c.getStackInSlot(i);
                if (st != null) {
                    w.spawnEntityInWorld(new EntityItem(w, x + 0.5, y + 0.5, z + 0.5, st.copy()));
                    c.setInventorySlotContents(i, null);
                }
            }
        }
        super.breakBlock(w, x, y, z, b, meta);
    }

    @Override
    public boolean hasComparatorInputOverride() {
        return true;
    }

    @Override
    public int getComparatorInputOverride(World w, int x, int y, int z, int side) {
        TileEntity te = w.getTileEntity(x, y, z);
        return te instanceof TileEntityChargerSVA ? ((TileEntityChargerSVA) te).comparatorLevel() : 0;
    }

    @Override
    public boolean canProvidePower() {
        return true;
    }

    @Override
    public int isProvidingWeakPower(IBlockAccess w, int x, int y, int z, int side) {
        TileEntity te = w.getTileEntity(x, y, z);
        return te instanceof TileEntityChargerSVA ? ((TileEntityChargerSVA) te).redstonePower() : 0;
    }

    @Override
    public int isProvidingStrongPower(IBlockAccess w, int x, int y, int z, int side) {
        return isProvidingWeakPower(w, x, y, z, side);
    }

    @Override
    public boolean canConnectRedstone(IBlockAccess w, int x, int y, int z, int side) {
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubBlocks(Item item, CreativeTabs tab, List list) {
        for (Tier t : TIERS) {
            list.add(new ItemStack(item, 1, t.ordinal()));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        String a = ReferenceSVA.ASSETS + ":";
        top = r.registerIcon(a + "chargeStationTop");
        topOn = r.registerIcon(a + "chargeStationTopOn");
        side = r.registerIcon(a + "chargeStationSide");
        sideOn = r.registerIcon(a + "chargeStationSideOn");
        bottom = r.registerIcon("siliconage:machineCasingMV");
        front = r.registerIcon(a + "chargeStationFront2");
        frontOn = r.registerIcon(a + "chargeStationFront2On");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return s == 1 ? top : s == 0 ? bottom : s == 3 ? frontOn : side;      // the item: the screen towards the viewer
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess w, int x, int y, int z, int s) {
        TileEntity te = w.getTileEntity(x, y, z);
        boolean on = te instanceof TileEntityChargerSVA && ((TileEntityChargerSVA) te).isActive();
        int f = te instanceof TileEntityChargerSVA ? ((TileEntityChargerSVA) te).getFacing() : 3;
        if (s == f) {
            return on ? frontOn : front;
        }
        return s == 1 ? (on ? topOn : top) : s == 0 ? bottom : (on ? sideOn : side);
    }
}
