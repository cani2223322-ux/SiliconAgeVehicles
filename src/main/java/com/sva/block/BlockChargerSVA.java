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
 * MV (ТБ-10: a higher grid takes Transformer upgrades, +1 input tier each - no HV..XV blocks) and, since fast charging
 * (2026-10-10), SV: «Сингулярная зарядная станция» - SV input without transformers, «Быстрая зарядка» built in, its own
 * purple textures (chargeStation*SV). The front
 * (screen, chargeStationFront2 / On) faces the player who placed it (kept in the tile).
 * Right-click: the station screen (platform, vehicle, upgrades, modules, settings). Comparator: the served vehicle's
 * charge; strong redstone 15 while it is full («сигнал при полном»).
 */
public class BlockChargerSVA extends BlockContainer {

    /** Tiers shown in creative / with recipes. */
    public static final Tier[] TIERS = {Tier.MV, Tier.SV};

    /** [0] MV look, [1] SV look. */
    private final IIcon[] top = new IIcon[2], topOn = new IIcon[2], side = new IIcon[2], sideOn = new IIcon[2], bottom = new IIcon[2],
            front = new IIcon[2], frontOn = new IIcon[2];

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

    /** The name key: the SV station has its own («Сингулярная зарядная станция»). */
    public static String nameKey(Tier t) {
        return t == Tier.SV ? "tile.siliconagevehicles.charger.sv.name" : "tile.siliconagevehicles.charger.name";
    }

    private static int look(int meta) {
        return tierFor(meta) == Tier.SV ? 1 : 0;
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
        KeptDataSVA.restore(w, x, y, z, s);
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
        BlockChargePlateSVA.nudgeStations(w, x, y, z);          // another station on the same plates re-scans at once
    }

    // ------------------------------------------------------------------ ТБ-1: the item keeps the buffer (KeptDataSVA)

    @Override
    public boolean removedByPlayer(World w, EntityPlayer p, int x, int y, int z, boolean willHarvest) {
        if (willHarvest) {
            return true;                                    // harvestBlock drops (reading the tile), then removes the block
        }
        return super.removedByPlayer(w, p, x, y, z, willHarvest);
    }

    @Override
    public void harvestBlock(World w, EntityPlayer p, int x, int y, int z, int meta) {
        super.harvestBlock(w, p, x, y, z, meta);
        w.setBlockToAir(x, y, z);
    }

    /** An explosion drops it whole, as the core's machines: what it keeps rides in the item. */
    @Override
    public void dropBlockAsItemWithChance(World w, int x, int y, int z, int meta, float chance, int fortune) {
        super.dropBlockAsItemWithChance(w, x, y, z, meta, 1.0F, fortune);
    }

    @Override
    public java.util.ArrayList<ItemStack> getDrops(World w, int x, int y, int z, int meta, int fortune) {
        return KeptDataSVA.drops(this, damageDropped(meta), w, x, y, z);
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
        String[] suffix = {"", "SV"};
        for (int i = 0; i < 2; i++) {
            top[i] = r.registerIcon(a + "chargeStationTop" + suffix[i]);
            topOn[i] = r.registerIcon(a + "chargeStationTopOn" + suffix[i]);
            side[i] = r.registerIcon(a + "chargeStationSide" + suffix[i]);
            sideOn[i] = r.registerIcon(a + "chargeStationSideOn" + suffix[i]);
            front[i] = r.registerIcon(a + "chargeStationFront2" + suffix[i]);
            frontOn[i] = r.registerIcon(a + "chargeStationFront2On" + suffix[i]);
        }
        bottom[0] = r.registerIcon("siliconage:machineCasingMV");
        bottom[1] = r.registerIcon("siliconage:machineCasingSV");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        int l = look(meta);
        return s == 1 ? top[l] : s == 0 ? bottom[l] : s == 3 ? frontOn[l] : side[l];      // the item: the screen towards the viewer
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess w, int x, int y, int z, int s) {
        TileEntity te = w.getTileEntity(x, y, z);
        boolean on = te instanceof TileEntityChargerSVA && ((TileEntityChargerSVA) te).isActive();
        int f = te instanceof TileEntityChargerSVA ? ((TileEntityChargerSVA) te).getFacing() : 3;
        int l = look(w.getBlockMetadata(x, y, z));
        if (s == f) {
            return on ? frontOn[l] : front[l];
        }
        return s == 1 ? (on ? topOn[l] : top[l]) : s == 0 ? bottom[l] : (on ? sideOn[l] : side[l]);
    }
}
