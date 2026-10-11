package com.sva.block;

import java.util.List;

import com.sva.ConfigSVA;
import com.sva.ReferenceSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.AirDefenseSVA;
import com.sva.airdefense.RadarMath;
import com.sva.item.ItemCodeCardSVA;
import com.sva.net.NetADSVA;

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
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Air-defence wave: the three air-defence blocks - RADAR (meta = RadarMath kind: «Обзорная РЛС», «РЛС сопровождения»,
 * «Высотомер»), LAUNCHER (meta TileEntityLauncherSVA.GUN «Зенитная установка» / MISSILE «Ракетный комплекс») and
 * COMMAND_POST («Командный пункт ПВО»; meta = the facing of its screen). Placing one makes the placer its owner (the
 * airdefense.maxRadarsPerPlayer / maxLaunchersPerPlayer limits are checked: over them the block comes back). RMB: the
 * radar tells its state in the chat, the launcher opens its screen (Shift+RMB with ammunition: loads it), the command
 * post opens its screen (with a code card in hand: prints today's code on it). Broken: the launcher drops its ammunition,
 * the post's zones are gone. The command post gives a redstone signal 15 on every side while its alarm is on.
 */
public class BlockAirDefenseSVA extends BlockContainer {

    public static final int RADAR = 0, LAUNCHER = 1, COMMAND_POST = 2;

    public final int kind;
    private IIcon[] top, side;
    private IIcon bottom, front, frontAlarm;

    public BlockAirDefenseSVA(int kind) {
        super(Material.iron);
        this.kind = kind;
        setHardness(4.0F);
        setResistance(15.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 1);
        setBlockName(ReferenceSVA.ASSETS + "." + (kind == RADAR ? "radar" : kind == LAUNCHER ? "launcher" : "commandPost"));
    }

    public int subtypes() {
        return kind == RADAR ? RadarMath.KINDS : kind == LAUNCHER ? TileEntityLauncherSVA.KINDS : 1;
    }

    @Override
    public TileEntity createNewTileEntity(World w, int meta) {
        return kind == RADAR ? new TileEntityRadarSVA() : kind == LAUNCHER ? new TileEntityLauncherSVA() : new TileEntityCommandPostSVA();
    }

    @Override
    public int damageDropped(int meta) {
        return kind == COMMAND_POST ? 0 : meta;
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
        if (kind == COMMAND_POST) {
            int f = MathHelper.floor_double(e.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
            w.setBlockMetadataWithNotify(x, y, z, f == 0 ? 2 : f == 1 ? 5 : f == 2 ? 3 : 4, 2);
        }
        TileEntity te = w.getTileEntity(x, y, z);
        if (w.isRemote || !(te instanceof TileEntityAirDefenseSVA) || !(e instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer p = (EntityPlayer) e;
        int max = kind == RADAR ? ConfigSVA.adMaxRadars : kind == LAUNCHER ? ConfigSVA.adMaxLaunchers : 0;
        AirDefenseDataSVA d = AirDefenseDataSVA.get(w);
        int node = kind == RADAR ? AirDefenseDataSVA.N_RADAR : AirDefenseDataSVA.N_LAUNCHER;
        if (max > 0 && d != null && kind != COMMAND_POST && d.count(p.getCommandSenderName(), node) >= max && !p.capabilities.isCreativeMode) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.ad.msg.limit", Integer.valueOf(max)));
            w.setBlockToAir(x, y, z);
            ItemStack back = s.copy();
            back.stackSize = 1;
            if (!p.inventory.addItemStackToInventory(back)) {
                p.dropPlayerItemWithRandomChoice(back, false);
            }
            return;
        }
        ((TileEntityAirDefenseSVA) te).setOwner(p);
        if (kind == COMMAND_POST) {
            ((TileEntityCommandPostSVA) te).log("sva.ad.log.placed", p.getCommandSenderName());
        }
    }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int s, float hx, float hy, float hz) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityAirDefenseSVA)) {
            return false;
        }
        if (w.isRemote) {
            return true;
        }
        TileEntityAirDefenseSVA t = (TileEntityAirDefenseSVA) te;
        if (!t.mayUse(p)) {
            p.addChatComponentMessage(new ChatComponentTranslation("sva.ad.msg.notYours", t.getOwnerName()));
            return true;
        }
        ItemStack held = p.getHeldItem();
        if (te instanceof TileEntityLauncherSVA) {
            TileEntityLauncherSVA l = (TileEntityLauncherSVA) te;
            if (p.isSneaking() && l.isAmmo(held)) {
                int n = l.insert(held);
                if (held.stackSize <= 0) {
                    p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
                }
                p.addChatComponentMessage(new ChatComponentTranslation("sva.ad.msg.loaded", Integer.valueOf(n), Integer.valueOf(l.ammo())));
                return true;
            }
            p.openGui(com.sva.SVAMod.instance, com.sva.inventory.GuiHandlerSVA.LAUNCHER, w, x, y, z);
            return true;
        }
        if (te instanceof TileEntityCommandPostSVA) {
            TileEntityCommandPostSVA k = (TileEntityCommandPostSVA) te;
            if (held != null && held.getItem() instanceof ItemCodeCardSVA) {
                if (k.printCard(p, held)) {
                    p.addChatComponentMessage(new ChatComponentTranslation("sva.ad.msg.printed",
                            com.sva.airdefense.XpdrMath.format(ItemCodeCardSVA.code(held))));
                    p.inventoryContainer.detectAndSendChanges();
                } else {
                    p.addChatComponentMessage(new ChatComponentTranslation("sva.ad.msg.noDayKey"));
                }
                return true;
            }
            if (p instanceof EntityPlayerMP) {
                k.addViewer(p);
                net.minecraft.nbt.NBTTagCompound snap = k.snapshot();
                snap.setBoolean("Open", true);
                snap.setBoolean("Owner", k.isOwner(p));
                NetADSVA.send((EntityPlayerMP) p, NetADSVA.D_KP, snap);
            }
            return true;
        }
        TileEntityRadarSVA r = (TileEntityRadarSVA) te;
        TileEntityCommandPostSVA kp = AirDefenseSVA.INSTANCE.commandPost(w, r.getOwnerName(), x, y, z, com.sva.airdefense.BallisticsMath.LINK);
        p.addChatComponentMessage(new ChatComponentTranslation("sva.ad.msg.radar", new ChatComponentTranslation(r.nameKey()),
                new ChatComponentTranslation(r.isPowered() ? "sva.ad.st.powered" : "sva.ad.st.noPower"), Integer.valueOf(r.contacts().size()),
                kp == null ? new ChatComponentTranslation("sva.ad.st.noKp") : kp.name()));
        return true;
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!w.isRemote && te instanceof TileEntityAirDefenseSVA) {
            ((TileEntityAirDefenseSVA) te).removed();
            if (te instanceof TileEntityLauncherSVA) {
                TileEntityLauncherSVA l = (TileEntityLauncherSVA) te;
                for (int i = 0; i < l.getSizeInventory(); i++) {
                    ItemStack s = l.getStackInSlot(i);
                    if (s != null) {
                        w.spawnEntityInWorld(new EntityItem(w, x + 0.5, y + 0.5, z + 0.5, s.copy()));
                        l.setInventorySlotContents(i, null);
                    }
                }
            }
        }
        super.breakBlock(w, x, y, z, b, meta);
    }

    // ------------------------------------------------------------------ redstone: the alarm

    @Override
    public boolean canProvidePower() {
        return kind == COMMAND_POST;
    }

    @Override
    public int isProvidingWeakPower(IBlockAccess w, int x, int y, int z, int side) {
        if (kind != COMMAND_POST) {
            return 0;
        }
        TileEntity te = w.getTileEntity(x, y, z);
        return te instanceof TileEntityCommandPostSVA && ((TileEntityCommandPostSVA) te).isAlarm() ? 15 : 0;
    }

    // ------------------------------------------------------------------ looks

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubBlocks(Item item, CreativeTabs tab, List list) {
        for (int i = 0; i < subtypes(); i++) {
            list.add(new ItemStack(item, 1, i));
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister r) {
        String a = ReferenceSVA.ASSETS + ":";
        bottom = r.registerIcon(a + "adBase");
        int n = subtypes();
        top = new IIcon[n];
        side = new IIcon[n];
        for (int i = 0; i < n; i++) {
            String k = kind == RADAR ? "radar_" + RadarMath.KEYS[i] : kind == LAUNCHER ? "launcher_" + TileEntityLauncherSVA.KEYS[i] : "kp";
            top[i] = r.registerIcon(a + k + "_top");
            side[i] = r.registerIcon(a + k + "_side");
        }
        if (kind == COMMAND_POST) {
            front = r.registerIcon(a + "kp_front");
            frontAlarm = r.registerIcon(a + "kp_frontAlarm");
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        if (s == 0) {
            return bottom;
        }
        if (kind == COMMAND_POST) {
            int f = meta < 2 ? 3 : meta;
            return s == 1 ? top[0] : s == f ? front : side[0];
        }
        int m = meta >= 0 && meta < subtypes() ? meta : 0;
        return s == 1 ? top[m] : side[m];
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess w, int x, int y, int z, int s) {
        int meta = w.getBlockMetadata(x, y, z);
        if (kind == COMMAND_POST && s == (meta < 2 ? 3 : meta)) {
            TileEntity te = w.getTileEntity(x, y, z);
            return te instanceof TileEntityCommandPostSVA && ((TileEntityCommandPostSVA) te).clientAlarm() ? frontAlarm : front;
        }
        return getIcon(s, meta);
    }
}
