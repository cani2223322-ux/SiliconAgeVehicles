package com.sva.block;

import com.sva.ReferenceSVA;
import com.sva.vehicle.AirfieldMath;

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
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Stage D1, ★1: «Огни ПАПИ» - one PAPI unit (a housing with two lenses on short legs). Four of them in a row beside the
 * runway, opposite the touchdown point, make the glide-slope indicator (AirfieldMath): each unit is white or red by the
 * viewer's angle over it - client.RenderAirfieldSVA draws it for the local player's eye; the autopilot's ЗАХВ flies by the
 * row (AutopilotMath.PH_GLIDE). The row's heading is the runway strip's (RunwayMath); a unit's place in the row (its
 * angle offset) is how many units stand between it and the runway.
 * <p>
 * Metadata (APPEND-ONLY): bits 0-1 the nominal glide angle (AirfieldMath.NOMINALS: 3 / 4 / 5 deg - RMB cycles the whole
 * row), bits 2-3 the facing from placement (the lenses look at the placer until a runway gives the direction).
 */
public class BlockPapiSVA extends BlockContainer {

    /** A row is the units in a line (gaps of one block allowed) up to this far each way. */
    public static final int ROW = AirfieldMath.PAPI_BAND + 2;

    @SideOnly(Side.CLIENT)
    private IIcon icon;

    public BlockPapiSVA() {
        super(Material.iron);
        setHardness(1.5F);
        setResistance(5.0F);
        setStepSound(soundTypeMetal);
        setBlockName(ReferenceSVA.ASSETS + ".papi");
        setLightOpacity(0);
        setBlockBounds(0.08F, 0F, 0.08F, 0.92F, 0.62F, 0.92F);
    }

    /** The facing from placement (0 south, 1 west, 2 north, 3 east - the lenses look that way). */
    public static int facing(int meta) {
        return meta >> 2 & 3;
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityAirfieldSVA();
    }

    @Override
    public int getRenderType() {
        return -1;                                           // client.RenderAirfieldSVA (TESR) draws it
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
    public int damageDropped(int meta) {
        return 0;
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        int look = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        // join the row's setting when placed next to one
        int nom = 0;
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] o : d) {
            if (w.getBlock(x + o[0], y, z + o[1]) == this) {
                nom = w.getBlockMetadata(x + o[0], y, z + o[1]) & 3;
                break;
            }
        }
        w.setBlockMetadataWithNotify(x, y, z, nom | ((look + 2) & 3) << 2, 2);
    }

    /** RMB: the next nominal angle (3 -> 4 -> 5 deg) for the whole row; sneaking with a block in hand places as usual. */
    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        if (p.isSneaking() && p.getHeldItem() != null) {
            return false;
        }
        if (w.isRemote) {
            return true;
        }
        int next = AirfieldMath.nextNominal(w.getBlockMetadata(x, y, z));
        int n = 0;
        for (int[] axis : new int[][] {{1, 0}, {0, 1}}) {
            for (int s = -1; s <= 1; s += 2) {
                int gap = 0;
                for (int i = 1; i <= ROW && gap < 2; i++) {
                    int bx = x + axis[0] * s * i, bz = z + axis[1] * s * i;
                    if (w.getBlock(bx, y, bz) == this) {
                        gap = 0;
                        w.setBlockMetadataWithNotify(bx, y, bz, w.getBlockMetadata(bx, y, bz) & ~3 | next, 3);
                        n++;
                    } else {
                        gap++;
                    }
                }
            }
        }
        w.setBlockMetadataWithNotify(x, y, z, w.getBlockMetadata(x, y, z) & ~3 | next, 3);
        p.addChatComponentMessage(new ChatComponentTranslation("sva.papi.set", Integer.valueOf(AirfieldMath.NOMINALS[next]), Integer.valueOf(n + 1)));
        return true;
    }

    @Override
    public void onNeighborBlockChange(World w, int x, int y, int z, Block b) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityAirfieldSVA) {
            ((TileEntityAirfieldSVA) te).invalidateCache();
        }
    }

    @Override
    public int getLightValue(IBlockAccess w, int x, int y, int z) {
        return 4;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        icon = r.registerIcon(ReferenceSVA.ASSETS + ":papi");
    }

    /** The flat item icon / break particles. */
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return icon;
    }
}
