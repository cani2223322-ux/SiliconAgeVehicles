package com.sva.block;

import java.util.List;

import com.sva.ReferenceSVA;
import com.sva.airport.AptScale;
import com.sva.airport.IlsPairing;
import com.sva.airport.MarkerMath;
import com.sva.airport.RvrMath;
import com.sva.airport.WindField;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Airport wave B1 (plan-next §9, items 19, 21, 22): the airport's small devices, one block by metadata (AirportDataSVA.K_*):
 * <ul>
 * <li>MARKER «Маркерный маяк» - on the extended centre line before a threshold; its place makes it the OM / MM / IM
 * (MarkerMath.classify);</li>
 * <li>RVR «Трансмиссометр RVR» - beside a runway: the one nearest the landing threshold reads TDZ, the farthest END, the
 * middle one MID (RvrMath);</li>
 * <li>WX «Метеостанция» - the wind (WindField), the QNH, the temperature for the aircraft near (the liner's PFD / ND /
 * MCDU);</li>
 * <li>SOCK «Ветроуказатель» - the windsock: it swings and fills with the same wind (client.RenderAptSVA).</li>
 * </ul>
 * RMB tells its reading in the chat. Drawn by client.RenderAptSVA (models), not as cubes.
 */
public class BlockAptDeviceSVA extends BlockContainer {

    public static final String[] KEYS = {"marker", "rvr", "weather", "windsock"};

    @SideOnly(Side.CLIENT)
    private IIcon icon;

    public BlockAptDeviceSVA() {
        super(Material.iron);
        setHardness(1.5F);
        setResistance(6.0F);
        setStepSound(soundTypeMetal);
        setLightOpacity(0);
        setBlockName(ReferenceSVA.ASSETS + ".aptDevice");
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return new TileEntityAptDeviceSVA();
    }

    @Override
    public int damageDropped(int meta) {
        return Math.max(0, Math.min(KEYS.length - 1, meta));
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!w.isRemote && te instanceof TileEntityAptDeviceSVA) {
            ((TileEntityAptDeviceSVA) te).register();
        }
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        if (p.isSneaking() && p.getHeldItem() != null) {
            return false;
        }
        if (!w.isRemote) {
            AirportDataSVA d = AirportDataSVA.get(w);
            AirportDataSVA.Device dev = d == null ? null : d.device(AirportDataSVA.key(w, x, y, z));
            if (dev == null) {
                TileEntity te = w.getTileEntity(x, y, z);
                if (te instanceof TileEntityAptDeviceSVA) {
                    ((TileEntityAptDeviceSVA) te).register();       // an old block: into the list now
                }
                p.addChatComponentMessage(new ChatComponentTranslation("sva.apt.dev.wait"));
                return true;
            }
            p.addChatComponentMessage(reading(d, dev, w));
        }
        return true;
    }

    /** The chat line of a device's reading. */
    static ChatComponentTranslation reading(AirportDataSVA d, AirportDataSVA.Device dev, World w) {
        switch (dev.kind) {
            case AirportDataSVA.K_MARKER:
                if (dev.type == MarkerMath.NONE) {
                    return new ChatComponentTranslation("sva.apt.dev.marker.none");
                }
                return new ChatComponentTranslation("sva.apt.dev.marker", MarkerMath.NAMES[dev.type], rwyName(d, dev.rwy),
                        Integer.valueOf((int) Math.round(AptScale.metres(MarkerMath.NOMINAL[dev.type]))));
            case AirportDataSVA.K_RVR:
                if (dev.rvr < 0 || dev.rwy.length() == 0) {
                    return new ChatComponentTranslation("sva.apt.dev.rvr.none");
                }
                return new ChatComponentTranslation("sva.apt.dev.rvr", RvrMath.text(dev.rvr), "CAT " + roman(RvrMath.requiredCat(dev.rvr)));
            default:
                if (Double.isNaN(dev.qnh)) {
                    return new ChatComponentTranslation("sva.apt.dev.wait");
                }
                String dir = String.format("%03d", Integer.valueOf((int) Math.round(dev.windFrom) % 360));
                if (dev.kind == AirportDataSVA.K_SOCK) {
                    return new ChatComponentTranslation("sva.apt.dev.windsock", dir, Integer.valueOf(WindField.kmh(dev.windSpeed)),
                            Integer.valueOf(WindField.kmh(dev.windGust)));
                }
                return new ChatComponentTranslation("sva.apt.dev.weather", dir, Integer.valueOf(WindField.kmh(dev.windSpeed)),
                        Integer.valueOf(WindField.kmh(dev.windGust)), Integer.valueOf((int) Math.round(dev.qnh)), Integer.valueOf((int) Math.round(dev.temp)));
        }
    }

    /** The designator of the runway end a key belongs to ("--" none). */
    static String rwyName(AirportDataSVA d, String endKey) {
        for (IlsPairing.Rwy r : d.runways()) {
            if (r.a.key.equals(endKey)) {
                return r.nameA;
            }
            if (r.b.key.equals(endKey)) {
                return r.nameB;
            }
        }
        return "--";
    }

    static String roman(int cat) {
        return cat >= 3 ? "III" : cat == 2 ? "II" : "I";
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
        return -1;
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
        int m = w.getBlockMetadata(x, y, z);
        if (m == AirportDataSVA.K_MARKER) {
            setBlockBounds(0.15F, 0F, 0.15F, 0.85F, 0.45F, 0.85F);
        } else if (m == AirportDataSVA.K_SOCK) {
            setBlockBounds(0.4F, 0F, 0.4F, 0.6F, 1F, 0.6F);
        } else {
            setBlockBounds(0.2F, 0F, 0.2F, 0.8F, 1F, 0.8F);
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
        for (int i = 0; i < KEYS.length; i++) {
            list.add(new ItemStack(item, 1, i));
        }
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
