package com.sva.block;

import com.sc.util.ArmorGasSC.Gas;
import com.sva.ReferenceSVA;
import com.sva.entity.EntityVehicleSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * «Газовая колонка» (HV). Metadata = the front's side (2..5, like a furnace). Right-click: a full gas container
 * pours in; anything else - the tanks in the chat. Refuelling is automatic (TileEntityGasColumnSVA).
 */
public class BlockGasColumnSVA extends BlockContainer {

    private IIcon front, side, top, bottom;

    public BlockGasColumnSVA() {
        super(Material.iron);
        setHardness(3.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 1);
        setBlockName(ReferenceSVA.ASSETS + ".gasColumn");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityGasColumnSVA();
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int dir = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int[] faces = {2, 5, 3, 4};                     // the front looks at the player
        w.setBlockMetadataWithNotify(x, y, z, faces[dir], 2);
        TileEntity te = w.getTileEntity(x, y, z);
        if (!w.isRemote && te instanceof TileEntityGasColumnSVA && e instanceof EntityPlayer) {
            ((TileEntityGasColumnSVA) te).setOwner(e.getUniqueID().toString(), ((EntityPlayer) e).getCommandSenderName());
        }
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int s, float hx, float hy, float hz) {
        if (w.isRemote) {
            return true;
        }
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityGasColumnSVA)) {
            return true;
        }
        TileEntityGasColumnSVA c = (TileEntityGasColumnSVA) te;
        ItemStack held = p.getCurrentEquippedItem();
        if (EntityVehicleSVA.isGasContainer(held)) {
            com.sc.util.FluidHandSC.use(w, x, y, z, p, c, held);
            return true;
        }
        p.addChatComponentMessage(new ChatComponentTranslation("sva.column.head",
                c.getOwnerName().length() > 0 ? c.getOwnerName() : "-", Integer.valueOf(TileEntityGasColumnSVA.RATE)));
        boolean any = false;
        for (Gas g : Gas.values()) {
            if (c.amount(g) > 0) {
                any = true;
                p.addChatComponentMessage(new ChatComponentTranslation("sva.column.tank",
                        new ChatComponentTranslation("sva.gas." + g.key()), Integer.valueOf(c.amount(g)), Integer.valueOf(TileEntityGasColumnSVA.TANK)));
            }
        }
        if (!any) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.column.empty"));
        }
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        String a = ReferenceSVA.ASSETS + ":";
        front = r.registerIcon(a + "gasColumnFront");
        side = r.registerIcon(a + "gasColumnSide");
        top = r.registerIcon(a + "gasColumnTop");
        bottom = r.registerIcon("siliconage:machineCasingHV");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        int f = meta < 2 || meta > 5 ? 3 : meta;        // the item: front to the south (towards the viewer)
        return s == 1 ? top : s == 0 ? bottom : s == f ? front : side;
    }
}
