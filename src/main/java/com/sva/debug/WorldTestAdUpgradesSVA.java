package com.sva.debug;

import java.util.List;

import com.sc.energy.Tier;
import com.sc.init.ModItems;
import com.sc.machine.UpgradeType;
import com.sva.airdefense.RadarMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.ItemBlockAirDefenseSVA;
import com.sva.block.TileEntityAdPartSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.init.ModContentSVA;
import com.sva.inventory.ContainerAdSVA;
import com.sva.inventory.ContainerLauncherSVA;
import com.sva.item.ItemAmmoSVA;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Upgrades wave world test (2026-10-12), run by WorldTestSVA alongside its other phases from tick START in its own area
 * (x spawn + 3800 chunk-aligned, z spawn - 3800, a floor at Y 150):
 * - a survey radar placed as a structure: a bare one refuses an SV packet through a part (simulated - nothing blows up);
 *   a universal transformer shift-clicked in through its equipment screen's container (a puller is refused) - then SV and
 *   EV packets go in through the part for real and it stands; the IC2 re-announce counted;
 * - overclockers: the scan interval shorter; storages: the buffer bigger, filled to it;
 * - the AA gun: an overclocker - a shorter burst interval; a puller - the shells come in from a chest beside it;
 * - breaking each structure drops its modules (and the gun its shells) with the one item.
 */
final class WorldTestAdUpgradesSVA {

    static final int START = 1500;
    private static final int Y = 150;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, rx, rz, gx, gz;
    private TileEntityRadarSVA radar;
    private TileEntityLauncherSVA gun;
    boolean done;

    WorldTestAdUpgradesSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            t0 = ticks;
            phase = setup(w) ? 1 : 9;
        }
        int dt = ticks - t0;
        if (phase == 1 && dt >= 10) {
            phase = insert(w) ? 2 : 9;
        }
        if (phase == 2 && dt >= 50) {
            effects(w);
            breaking(w);
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private FakePlayer player(World w) {
        FakePlayer p = FakePlayerFactory.getMinecraft((WorldServer) w);
        p.rotationYaw = 0F;
        p.capabilities.isCreativeMode = true;                   // the per-player limits don't apply to the test's blocks
        return p;
    }

    private boolean place(World w, BlockAirDefenseSVA b, int meta, int x, int y, int z) {
        ItemBlockAirDefenseSVA ib = (ItemBlockAirDefenseSVA) Item.getItemFromBlock(b);
        FakePlayer p = player(w);
        boolean ok = ib.placeBlockAt(new ItemStack(b, 1, meta), p, w, x, y, z, 1, 0.5F, 1F, 0.5F, meta);
        p.capabilities.isCreativeMode = false;
        return ok;
    }

    private static ItemStack up(UpgradeType t, int n) {
        ItemStack s = ModItems.upgrade.stackOf(t);
        s.stackSize = n;
        return s;
    }

    private boolean setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = (sp.posX + 3800) >> 4 << 4;
        z0 = sp.posZ - 3800;
        keepLoaded(w);
        area(w, true);
        rx = x0 + 5;
        rz = z0 + 5;
        boolean r = place(w, ModContentSVA.radar, RadarMath.SURVEY, rx, Y + 1, rz);
        gx = x0 + 14;
        gz = z0 + 5;
        boolean g = place(w, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, gx, Y + 1, gz);
        TileEntity a = w.getTileEntity(rx, Y + 1, rz), b = w.getTileEntity(gx, Y + 1, gz);
        check(r && g && a instanceof TileEntityRadarSVA && b instanceof TileEntityLauncherSVA
                && ((TileEntityAirDefenseSVA) a).structState() == TileEntityAirDefenseSVA.MULTI,
                "ad upgrades world: the survey radar and the AA gun placed as structures");
        if (!(a instanceof TileEntityRadarSVA) || !(b instanceof TileEntityLauncherSVA)) {
            return false;
        }
        radar = (TileEntityRadarSVA) a;
        gun = (TileEntityLauncherSVA) b;
        // a chest with shells by the gun's master (north of it: outside the structure)
        w.setBlock(gx, Y + 1, gz - 1, Blocks.chest, 0, 3);
        TileEntity c = w.getTileEntity(gx, Y + 1, gz - 1);
        if (c instanceof TileEntityChest) {
            ((TileEntityChest) c).setInventorySlotContents(3, ModContentSVA.ammo.stackOf(ItemAmmoSVA.SHELLS, 20));
        }
        return true;
    }

    /** After the blocks have ticked (their tier set, the IC2 key taken): the universal transformer and the rest go in. */
    private boolean insert(World w) {
        TileEntityAdPartSVA part = (TileEntityAdPartSVA) w.getTileEntity(rx - 1, Y + 1, rz);
        if (part == null || part.master() != radar) {
            check(false, "ad upgrades world: the radar's part beside the master");
            return false;
        }
        int bare = part.receiveEnergy(ForgeDirection.WEST, Tier.SV.getVoltage(), 500, true);
        check(bare == 0 && !radar.isInvalid() && radar.inputTier() == Tier.HV && !part.acceptsAnyVoltage(),
                "ad upgrades world: a bare radar refuses an SV packet through its part (simulated: " + bare + "), still standing");
        // the universal transformer shift-clicked in through the equipment screen's container; a puller is refused
        FakePlayer p = player(w);
        p.capabilities.isCreativeMode = false;
        p.inventory.setInventorySlotContents(9, up(UpgradeType.UNIVERSAL_TRANSFORMER, 1));
        p.inventory.setInventorySlotContents(10, up(UpgradeType.PULLER, 1));
        ContainerAdSVA c = new ContainerAdSVA(p.inventory, radar);
        c.transferStackInSlot(p, 4);
        c.transferStackInSlot(p, 5);
        ItemStack in0 = radar.upgrades.getStackInSlot(0);
        boolean puller = p.inventory.getStackInSlot(10) != null;
        boolean onlyUt = true;
        for (int i = 1; i < 4; i++) {
            onlyUt &= radar.upgrades.getStackInSlot(i) == null;
        }
        p.inventory.setInventorySlotContents(9, null);
        p.inventory.setInventorySlotContents(10, null);
        check(c.inventorySlots.size() == 40 && in0 != null && in0.getItemDamage() == UpgradeType.UNIVERSAL_TRANSFORMER.ordinal() && puller && onlyUt,
                "ad upgrades world: the universal transformer shift-clicks into the radar's upgrade slot, a puller stays in the inventory");
        int sv = part.receiveEnergy(ForgeDirection.WEST, Tier.SV.getVoltage(), 500, false);
        int ev = part.receiveEnergy(ForgeDirection.WEST, Tier.EV.getVoltage(), 500, false);
        Block m = w.getBlock(rx, Y + 1, rz);
        check(sv == 500 && ev == 500 && !radar.isInvalid() && m == ModContentSVA.radar && w.getTileEntity(rx, Y + 1, rz) == radar
                && part.acceptsAnyVoltage() && part.inputTier() == Tier.max() && radar.getEnergyStored() >= 1000,
                "ad upgrades world: with the universal transformer SV and EV packets go in through the part (" + sv + ", " + ev + "), nothing explodes");
        radar.upgrades.setInventorySlotContents(1, up(UpgradeType.OVERCLOCKER, 2));
        radar.upgrades.setInventorySlotContents(2, up(UpgradeType.ENERGY_STORAGE, 3));
        // the gun: an overclocker and a puller through its own screen's container
        p.inventory.setInventorySlotContents(9, up(UpgradeType.OVERCLOCKER, 1));
        p.inventory.setInventorySlotContents(10, up(UpgradeType.PULLER, 1));
        ContainerLauncherSVA lc = new ContainerLauncherSVA(p.inventory, gun);
        lc.transferStackInSlot(p, 8);
        lc.transferStackInSlot(p, 9);
        boolean bothIn = p.inventory.getStackInSlot(9) == null && p.inventory.getStackInSlot(10) == null;
        p.inventory.setInventorySlotContents(9, null);
        p.inventory.setInventorySlotContents(10, null);
        check(lc.inventorySlots.size() == 44 && bothIn && gun.upgradeCount(UpgradeType.OVERCLOCKER) == 1 && gun.upgradeCount(UpgradeType.PULLER) == 1
                && gun.ammo() == 0, "ad upgrades world: the gun's screen - 4 ammunition + 4 upgrade slots; an overclocker and a puller shift-clicked in");
        return true;
    }

    private void effects(World w) {
        check(radar.tierRefreshes() >= 1, "ad upgrades world: the universal transformer re-announced the radar to the energy net ("
                + radar.tierRefreshes() + ")");
        check(radar.scanInterval() == 59 && radar.scanInterval() < RadarMath.INTERVAL[RadarMath.SURVEY],
                "ad upgrades world: two overclockers - the survey radar scans every " + radar.scanInterval() + " ticks (was 120)");
        radar.fillForTest();
        int max = radar.getMaxEnergyStored();
        check(max == Tier.HV.getBuffer() + 30000 && radar.getEnergyStored() == max,
                "ad upgrades world: three storages - the radar holds " + max + " EU (HV 51 200 + 30 000)");
        TileEntity c = w.getTileEntity(gx, Y + 1, gz - 1);
        int left = 0;
        if (c instanceof TileEntityChest) {
            for (int i = 0; i < ((TileEntityChest) c).getSizeInventory(); i++) {
                ItemStack s = ((TileEntityChest) c).getStackInSlot(i);
                left += s == null ? 0 : s.stackSize;
            }
        }
        check(gun.fireInterval() == 7 && gun.ammo() == 20 && left == 0,
                "ad upgrades world: the gun bursts every " + gun.fireInterval() + " ticks (was 10); the puller took the 20 shells from the chest ("
                        + gun.ammo() + ", left " + left + ")");
    }

    private void breaking(World w) {
        drops(w, rx, rz, null);
        w.func_147480_a(rx - 1, Y + 1, rz, true);
        int mods = drops(w, rx, rz + 1, ModItems.upgrade), item = drops(w, rx, rz + 1, Item.getItemFromBlock(ModContentSVA.radar));
        check(mods == 6 && item == 1 && w.getBlock(rx, Y + 1, rz) == Blocks.air,
                "ad upgrades world: breaking the radar's part drops its 6 modules and the one radar (" + mods + ", " + item + ")");
        w.setBlockToAir(gx, Y + 1, gz - 1);
        drops(w, gx, gz, null);
        w.func_147480_a(gx + 1, Y + 1, gz + 1, true);
        mods = drops(w, gx, gz, ModItems.upgrade);
        int shells = drops(w, gx, gz, ModContentSVA.ammo);
        item = drops(w, gx, gz, Item.getItemFromBlock(ModContentSVA.launcher));
        check(mods == 2 && shells == 20 && item == 1, "ad upgrades world: breaking the gun drops its 2 modules, its 20 shells and the gun (" + mods + ", "
                + shells + ", " + item + ")");
    }

    /** Dropped items of `it` round (x, z) - counted, then removed (null: every item). */
    @SuppressWarnings("unchecked")
    private static int drops(World w, int x, int z, Item it) {
        List<EntityItem> l = w.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(x - 6, Y - 3, z - 6, x + 7, Y + 8, z + 7));
        int n = 0;
        for (EntityItem e : l) {
            ItemStack s = e.getEntityItem();
            if (s != null && !e.isDead && (it == null || s.getItem() == it)) {
                n += s.stackSize;
                e.setDead();
            }
        }
        return n;
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 4; x <= x0 + 24; x++) {
            for (int z = z0 - 4; z <= z0 + 16; z++) {
                for (int y = Y + 1; y <= Y + 6; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void finish(World w) {
        if (x0 != 0 || z0 != 0) {
            area(w, false);
            List<EntityItem> l = w.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(x0 - 8, Y - 6, z0 - 8, x0 + 28, Y + 10, z0 + 20));
            for (EntityItem e : l) {
                e.setDead();
            }
        }
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 24) >> 4; cx <= (x0 + 40) >> 4; cx++) {
            for (int cz = (z0 - 24) >> 4; cz <= (z0 + 32) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }
}
