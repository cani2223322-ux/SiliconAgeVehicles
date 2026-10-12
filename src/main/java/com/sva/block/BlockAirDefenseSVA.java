package com.sva.block;

import java.util.ArrayList;
import java.util.List;

import com.sva.ConfigSVA;
import com.sva.ReferenceSVA;
import com.sva.airdefense.AdShapeMath;
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
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.Explosion;
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
 *
 * Multiblock wave (2026-10-12): the block is the master of a structure the size of the mock-ups (AdShapeMath; the
 * survey radar and the missile system 3x3, the tracking radar, the height finder and the gun 2x2, the command post 2x1),
 * the rest filled with BlockAdPartSVA parts. build() puts it all in (ItemBlockAirDefenseSVA after room() said the cells
 * are free; the world tests), breaking any block of it takes the whole down and drops the one item here in breakBlock
 * (not through getDrops - so a part broken, an explosion or a creative break all come through the same path: one item,
 * none in creative). Drawn by client/RenderAirDefenseSVA (the TESR), not as a cube.
 */
public class BlockAirDefenseSVA extends BlockContainer {

    public static final int RADAR = 0, LAUNCHER = 1, COMMAND_POST = 2;

    public final int kind;
    private IIcon[] top, side;
    private IIcon bottom, front, frontAlarm;

    public BlockAirDefenseSVA(int kind) {
        super(Material.iron);
        this.kind = kind;
        setLightOpacity(0);
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

    // ------------------------------------------------------------------ the multiblock

    /** A cell one may build in: loaded, inside the world, replaceable (air, grass, snow...), no living thing in a FULL one. */
    private static boolean free(World w, int x, int y, int z, int type) {
        if (y < 0 || y > 255 || !w.blockExists(x, y, z)) {
            return false;
        }
        Block b = w.getBlock(x, y, z);
        if (!b.isReplaceable(w, x, y, z) || b instanceof BlockAdPartSVA || b instanceof BlockAirDefenseSVA) {
            return false;
        }
        return type != AdShapeMath.FULL || w.checkNoEntityCollision(AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1));
    }

    /** The first cell (other than the master's) that is not free, {x, y, z}, or null: room for the structure. */
    public static int[] room(World w, int x, int y, int z, int shape, int facing) {
        int[][] c = AdShapeMath.worldCells(shape, facing);
        for (int i = 1; i < c.length; i++) {
            if (!free(w, x + c[i][0], y + c[i][1], z + c[i][2], c[i][3])) {
                return new int[] {x + c[i][0], y + c[i][1], z + c[i][2]};
            }
        }
        return null;
    }

    /** A legacy single block in this facing: AdShapeMath.legacy over its cells (WAIT while one isn't loaded). */
    public static int legacyDecision(World w, int x, int y, int z, int shape, int facing) {
        int[][] c = AdShapeMath.worldCells(shape, facing);
        boolean loaded = true, free = true;
        for (int i = 1; i < c.length; i++) {
            int cx = x + c[i][0], cy = y + c[i][1], cz = z + c[i][2];
            if (cy >= 0 && cy <= 255 && !w.blockExists(cx, cy, cz)) {
                loaded = false;
            } else if (!free(w, cx, cy, cz, c[i][3])) {
                free = false;
            }
        }
        return AdShapeMath.legacy(loaded, free);
    }

    /**
     * Puts a whole structure in: the master (meta = the radar / launcher kind; the command post's = its screen's side as
     * before), its state MULTI and facing, then the parts. The caller has checked room(). @return false if the master
     * could not be set
     */
    public static boolean build(World w, int x, int y, int z, BlockAirDefenseSVA block, int meta, int facing) {
        int m = block.kind == COMMAND_POST ? AdShapeMath.sideFromFacing(facing) : meta;
        if (!w.setBlock(x, y, z, block, m, 3)) {
            return false;
        }
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityAirDefenseSVA)) {
            return false;
        }
        buildParts(w, x, y, z, (TileEntityAirDefenseSVA) te, facing);
        return true;
    }

    /** The parts around a master (a new one, or a legacy one converting): linked to it, the state MULTI. */
    public static void buildParts(World w, int x, int y, int z, TileEntityAirDefenseSVA master, int facing) {
        master.initStructure(facing, TileEntityAirDefenseSVA.MULTI);
        int[][] c = AdShapeMath.worldCells(master.shape(), facing);
        for (int i = 1; i < c.length; i++) {
            int cx = x + c[i][0], cy = y + c[i][1], cz = z + c[i][2];
            w.setBlock(cx, cy, cz, com.sva.init.ModContentSVA.adPart, c[i][3], 2);
            TileEntity te = w.getTileEntity(cx, cy, cz);
            if (te instanceof TileEntityAdPartSVA) {
                ((TileEntityAdPartSVA) te).link(x, y, z, master.getTier());
            }
        }
        for (int i = 0; i < c.length; i++) {
            w.notifyBlocksOfNeighborChange(x + c[i][0], y + c[i][1], z + c[i][2], i == 0 ? master.getBlockType() : com.sva.init.ModContentSVA.adPart);
        }
        w.markBlockForUpdate(x, y, z);
    }

    /** Takes the parts of a master down (its breakBlock): only parts that are its own. */
    private static void removeParts(World w, int x, int y, int z, TileEntityAirDefenseSVA master) {
        int[][] c = master.cells();
        for (int i = 1; i < c.length; i++) {
            int cx = x + c[i][0], cy = y + c[i][1], cz = z + c[i][2];
            if (cy < 0 || cy > 255 || !w.blockExists(cx, cy, cz) || !(w.getBlock(cx, cy, cz) instanceof BlockAdPartSVA)) {
                continue;
            }
            TileEntity te = w.getTileEntity(cx, cy, cz);
            if (te instanceof TileEntityAdPartSVA && ((TileEntityAdPartSVA) te).masterX() == x && ((TileEntityAdPartSVA) te).masterY() == y
                    && ((TileEntityAdPartSVA) te).masterZ() == z) {
                w.setBlockToAir(cx, cy, cz);
            }
        }
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase e, ItemStack s) {
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
            ((TileEntityAirDefenseSVA) te).noDrop = true;          // the item comes back below, not as a drop
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
    public void onBlockHarvested(World w, int x, int y, int z, int meta, EntityPlayer p) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityAirDefenseSVA && p != null && p.capabilities.isCreativeMode) {
            ((TileEntityAirDefenseSVA) te).noDrop = true;
        }
        super.onBlockHarvested(w, x, y, z, meta, p);
    }

    /** The item comes from breakBlock (one for the whole structure), never from the harvest / an explosion's roll. */
    @Override
    public ArrayList<ItemStack> getDrops(World w, int x, int y, int z, int meta, int fortune) {
        return new ArrayList<ItemStack>();
    }

    @Override
    public boolean canDropFromExplosion(Explosion e) {
        return false;
    }

    @Override
    public int getMobilityFlag() {
        return 2;                                               // pistons: immovable
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!w.isRemote && te instanceof TileEntityAirDefenseSVA) {
            TileEntityAirDefenseSVA t = (TileEntityAirDefenseSVA) te;
            t.dismantling = true;
            if (!t.noDrop) {
                dropBlockAsItem(w, x, y, z, new ItemStack(this, 1, damageDropped(meta)));
            }
            removeParts(w, x, y, z, t);
            t.removed();
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

    // ------------------------------------------------------------------ looks: the model (client/RenderAirDefenseSVA)

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
