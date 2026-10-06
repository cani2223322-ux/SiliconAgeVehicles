package com.sva.block;

import java.util.List;

import com.sc.energy.Tier;
import com.sva.ReferenceSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * «Зарядная станция техники». Metadata = the core's Tier ordinal (append-only, like the core's pads);
 * stage 1 ships MV only. Right-click: its buffer in the chat.
 */
public class BlockChargerSVA extends BlockContainer {

    /** Tiers shown in creative / with recipes. */
    public static final Tier[] TIERS = {Tier.MV};

    private IIcon top, topOn, side, sideOn, bottom;

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
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int s, float hx, float hy, float hz) {
        if (p.isSneaking()) {
            return false;
        }
        if (!w.isRemote) {
            TileEntity te = w.getTileEntity(x, y, z);
            if (te instanceof TileEntityChargerSVA) {
                TileEntityChargerSVA c = (TileEntityChargerSVA) te;
                p.addChatComponentMessage(new ChatComponentTranslation("sva.charger.status", c.getTier().name(),
                        Integer.valueOf(c.getEnergyStored()), Integer.valueOf(c.getMaxEnergyStored()), Integer.valueOf(c.rate())));
            }
        }
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
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        return s == 1 ? top : s == 0 ? bottom : side;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess w, int x, int y, int z, int s) {
        TileEntity te = w.getTileEntity(x, y, z);
        boolean on = te instanceof TileEntityChargerSVA && ((TileEntityChargerSVA) te).isActive();
        return s == 1 ? (on ? topOn : top) : s == 0 ? bottom : (on ? sideOn : side);
    }
}
