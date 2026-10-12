package com.sva.debug;

import java.util.List;

import com.sc.energy.Tier;
import com.sva.airdefense.AdShapeMath;
import com.sva.airdefense.RadarMath;
import com.sva.block.BlockAirDefenseSVA;
import com.sva.block.ItemBlockAirDefenseSVA;
import com.sva.block.TileEntityAdPartSVA;
import com.sva.block.TileEntityAirDefenseSVA;
import com.sva.block.TileEntityLauncherSVA;
import com.sva.block.TileEntityRadarSVA;
import com.sva.init.ModContentSVA;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Multiblock wave world test, run by WorldTestSVA alongside its other phases from tick START in its own area (x spawn +
 * 3400 chunk-aligned, z spawn - 3400, a floor at Y 150), placing as a (fake) player through the item:
 * - the survey radar fills its 3x3 + mast, every part linked to the master with its collision type;
 * - a cell taken -> nothing placed; right-click and energy through a part reach the master; breaking a part takes the whole
 *   structure down and drops exactly one item; across a chunk border the same; a creative break drops nothing; pistons
 *   can't move it; an explosion takes it all and drops one item;
 * - older worlds: a single block set the old way converts to the multiblock when there is room (the command post keeps its
 *   screen's side), stays a working compact 1x1 when there isn't.
 */
final class WorldTestAdModelsSVA {

    static final int START = 1400;
    private static final int Y = 150;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private TileEntityRadarSVA legacyTrack, legacyHeight;
    private TileEntityAirDefenseSVA legacyKp;
    boolean done;

    WorldTestAdModelsSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            t0 = ticks;
            phase = setup(w) ? 1 : 9;
        }
        int dt = ticks - t0;
        if (phase == 1 && dt >= 40) {
            legacyChecks(w);
            explosion(w);
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private FakePlayer player(World w, float yaw) {
        FakePlayer p = FakePlayerFactory.getMinecraft((WorldServer) w);
        p.rotationYaw = yaw;
        p.capabilities.isCreativeMode = false;
        return p;
    }

    private boolean place(World w, BlockAirDefenseSVA b, int meta, int x, int y, int z, float yaw) {
        ItemBlockAirDefenseSVA ib = (ItemBlockAirDefenseSVA) Item.getItemFromBlock(b);
        return ib.placeBlockAt(new ItemStack(b, 1, meta), player(w, yaw), w, x, y, z, 1, 0.5F, 1F, 0.5F, meta);
    }

    /** Every cell of the master at (x, y, z) present: the master, the parts linked to it with their collision type. */
    private static boolean whole(World w, int x, int y, int z) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityAirDefenseSVA) || ((TileEntityAirDefenseSVA) te).structState() != TileEntityAirDefenseSVA.MULTI) {
            return false;
        }
        int[][] c = ((TileEntityAirDefenseSVA) te).cells();
        for (int i = 1; i < c.length; i++) {
            int cx = x + c[i][0], cy = y + c[i][1], cz = z + c[i][2];
            TileEntity p = w.getTileEntity(cx, cy, cz);
            if (w.getBlock(cx, cy, cz) != ModContentSVA.adPart || w.getBlockMetadata(cx, cy, cz) != c[i][3] || !(p instanceof TileEntityAdPartSVA)
                    || ((TileEntityAdPartSVA) p).master() != te) {
                return false;
            }
        }
        return true;
    }

    /** No block of a structure left in the box. */
    private static boolean gone(World w, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Block b = w.getBlock(x, y, z);
                    if (b == ModContentSVA.adPart || b instanceof BlockAirDefenseSVA) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Dropped items of block `b` / `meta` round (x, z) - counted, then removed. */
    @SuppressWarnings("unchecked")
    private static int drops(World w, Block b, int meta, int x, int z) {
        List<EntityItem> l = w.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(x - 6, Y - 3, z - 6, x + 7, Y + 8, z + 7));
        int n = 0;
        for (EntityItem e : l) {
            ItemStack s = e.getEntityItem();
            if (s != null && s.getItem() == Item.getItemFromBlock(b) && (b == ModContentSVA.commandPost || s.getItemDamage() == meta)) {
                n += s.stackSize;
                e.setDead();
            }
        }
        return n;
    }

    private boolean setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = (sp.posX + 3400) >> 4 << 4;
        z0 = sp.posZ - 3400;
        keepLoaded(w);
        area(w, true);
        // the survey radar placed by a player looking south (yaw 0): the front looks north, the rows go south, right = east
        int sx = x0 + 5, sy = Y + 1, sz = z0 + 5;
        boolean placed = place(w, ModContentSVA.radar, RadarMath.SURVEY, sx, sy, sz, 0F);
        TileEntity te = w.getTileEntity(sx, sy, sz);
        check(placed && whole(w, sx, sy, sz) && ((TileEntityAirDefenseSVA) te).facing() == 2 && w.getBlock(sx, sy + 2, sz + 1) == ModContentSVA.adPart
                && w.getBlock(sx, sy + 3, sz + 1) == Blocks.air && w.getBlockMetadata(sx + 1, sy, sz + 2) == AdShapeMath.PAD && w.getBlock(sx - 1, sy, sz + 2) == ModContentSVA.adPart
                && w.getBlock(sx, sy, sz - 1) != ModContentSVA.adPart, "ad models world: the survey radar fills its 3x3 + the 3-block mast behind the "
                + "clicked block, every part linked to it, pads and solid cells");
        if (!(te instanceof TileEntityRadarSVA)) {
            return false;
        }
        TileEntityRadarSVA survey = (TileEntityRadarSVA) te;
        // a taken cell: nothing placed
        w.setBlock(x0 + 12, Y + 1, z0 + 6, Blocks.stone, 0, 2);
        boolean refused = !place(w, ModContentSVA.radar, RadarMath.TRACK, x0 + 11, Y + 1, z0 + 5, 0F);
        check(refused && w.getBlock(x0 + 11, Y + 1, z0 + 5) == Blocks.air && w.getBlock(x0 + 12, Y + 1, z0 + 5) == Blocks.air
                && w.getBlock(x0 + 11, Y + 1, z0 + 6) == Blocks.air, "ad models world: a taken cell - the tracking radar is not placed, nothing left behind");
        w.setBlockToAir(x0 + 12, Y + 1, z0 + 6);
        // right-click and energy through a part
        int px = sx - 1, py = sy, pz = sz;
        boolean click = ModContentSVA.adPart.onBlockActivated(w, px, py, pz, player(w, 0F), 1, 0.5F, 0.1F, 0.5F);
        TileEntityAdPartSVA part = (TileEntityAdPartSVA) w.getTileEntity(px, py, pz);
        int e0 = survey.getEnergyStored(), want = survey.demandedEnergy(), partWant = part.demandedEnergy();
        int sim = part.receiveEnergy(ForgeDirection.WEST, Tier.HV.getVoltage(), 500, true);
        int got = part.receiveEnergy(ForgeDirection.WEST, Tier.HV.getVoltage(), 500, false);
        check(click && want > 0 && partWant == want && sim == 500 && got == 500 && survey.getEnergyStored() == e0 + 500
                && part.inputTier() == survey.inputTier(), "ad models world: right-click and energy through a part reach the master (demand "
                        + want + " / " + partWant + ", in " + sim + " / " + got + ", click " + click + ")");
        // pistons
        check(ModContentSVA.adPart.getMobilityFlag() == 2 && ModContentSVA.radar.getMobilityFlag() == 2,
                "ad models world: pistons can't move a part or the master");
        // breaking a pad part: the whole structure goes, one item
        drops(w, ModContentSVA.radar, RadarMath.SURVEY, sx, sz);
        w.func_147480_a(sx + 1, sy, sz + 2, true);
        int n = drops(w, ModContentSVA.radar, RadarMath.SURVEY, sx, sz + 1);
        check(gone(w, sx - 1, sy, sz, sx + 1, sy + 3, sz + 2) && n == 1, "ad models world: breaking a part takes the whole radar down, one item drops ("
                + n + ")");
        // across a chunk border (x0 is a chunk's first column): the gun's cells x0 - 1 .. x0
        int gx = x0 - 1, gz = z0 + 12;
        boolean gun = place(w, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, gx, Y + 1, gz, 0F);
        boolean whole = gun && whole(w, gx, Y + 1, gz) && w.getBlock(x0, Y + 1, gz + 1) == ModContentSVA.adPart;
        w.func_147480_a(gx, Y + 1, gz, true);
        n = drops(w, ModContentSVA.launcher, TileEntityLauncherSVA.GUN, gx, gz);
        check(whole && gone(w, gx, Y + 1, gz, gx + 1, Y + 2, gz + 1) && n == 1,
                "ad models world: the gun across a chunk border - built whole, broken at the master all gone, one item (" + n + ")");
        // creative: no item
        int kx = x0 + 15, kz = z0 + 12;
        boolean kp = place(w, ModContentSVA.commandPost, 0, kx, Y + 1, kz, 0F);
        boolean kpWhole = kp && whole(w, kx, Y + 1, kz) && w.getBlock(kx + 1, Y + 2, kz) == ModContentSVA.adPart;
        FakePlayer c = player(w, 0F);
        c.capabilities.isCreativeMode = true;
        ModContentSVA.adPart.onBlockHarvested(w, kx + 1, Y + 2, kz, w.getBlockMetadata(kx + 1, Y + 2, kz), c);
        w.setBlockToAir(kx + 1, Y + 2, kz);
        c.capabilities.isCreativeMode = false;
        n = drops(w, ModContentSVA.commandPost, 0, kx, kz);
        check(kpWhole && gone(w, kx, Y + 1, kz, kx + 1, Y + 2, kz) && n == 0, "ad models world: the command post (2x1, 2 high) broken in creative - all gone, "
                + "no item (" + n + ")");
        // older worlds: single blocks set the old way
        w.setBlock(x0 + 20, Y + 1, z0 + 5, ModContentSVA.radar, RadarMath.TRACK, 3);
        legacyTrack = (TileEntityRadarSVA) w.getTileEntity(x0 + 20, Y + 1, z0 + 5);
        w.setBlock(x0 + 22, Y + 1, z0 + 16, ModContentSVA.commandPost, 3, 3);       // its screen to the south: facing 0
        legacyKp = (TileEntityAirDefenseSVA) w.getTileEntity(x0 + 22, Y + 1, z0 + 16);
        int hx = x0 + 28, hz = z0 + 22;
        for (int x = hx - 1; x <= hx + 1; x++) {
            for (int z = hz - 1; z <= hz + 1; z++) {
                for (int y = Y + 1; y <= Y + 3; y++) {
                    if (x != hx || z != hz || y != Y + 1) {
                        w.setBlock(x, y, z, Blocks.stone, 0, 2);
                    }
                }
            }
        }
        w.setBlock(hx, Y + 1, hz, ModContentSVA.radar, RadarMath.HEIGHT, 3);
        legacyHeight = (TileEntityRadarSVA) w.getTileEntity(hx, Y + 1, hz);
        check(legacyTrack != null && legacyKp != null && legacyHeight != null && legacyTrack.structState() == TileEntityAirDefenseSVA.LEGACY,
                "ad models world: three single blocks set the old way (a legacy world's)");
        return legacyTrack != null && legacyKp != null && legacyHeight != null;
    }

    private void legacyChecks(World w) {
        check(legacyTrack.structState() == TileEntityAirDefenseSVA.MULTI && whole(w, legacyTrack.xCoord, legacyTrack.yCoord, legacyTrack.zCoord),
                "ad models world: a legacy tracking radar with room around converted to the 2x2 multiblock");
        check(legacyKp.structState() == TileEntityAirDefenseSVA.MULTI && legacyKp.facing() == 0 && whole(w, legacyKp.xCoord, legacyKp.yCoord, legacyKp.zCoord)
                && w.getBlockMetadata(legacyKp.xCoord, legacyKp.yCoord, legacyKp.zCoord) == 3,
                "ad models world: a legacy command post converted keeping its screen's side (facing south)");
        check(legacyHeight.structState() == TileEntityAirDefenseSVA.COMPACT && legacyHeight.cells().length == 1 && legacyHeight.kind() == RadarMath.HEIGHT
                && w.getBlock(legacyHeight.xCoord, legacyHeight.yCoord + 1, legacyHeight.zCoord) == Blocks.stone,
                "ad models world: a legacy height finder walled in stays a compact 1x1 radar, nothing around it touched");
    }

    private void explosion(World w) {
        int mx = x0 + 5, mz = z0 + 18;
        boolean placed = place(w, ModContentSVA.launcher, TileEntityLauncherSVA.MISSILE, mx, Y + 1, mz, 0F) && whole(w, mx, Y + 1, mz);
        drops(w, ModContentSVA.launcher, TileEntityLauncherSVA.MISSILE, mx, mz);
        w.createExplosion(null, mx + 1.5, Y + 1.5, mz + 2.5, 6F, true);
        int n = drops(w, ModContentSVA.launcher, TileEntityLauncherSVA.MISSILE, mx, mz + 1);
        check(placed && gone(w, mx - 1, Y + 1, mz, mx + 1, Y + 2, mz + 2) && n == 1,
                "ad models world: an explosion at a corner of the missile system takes it all down, one item drops (" + n + ")");
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 4; x <= x0 + 32; x++) {
            for (int z = z0 - 2; z <= z0 + 28; z++) {
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
            List<EntityItem> l = w.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(x0 - 8, Y - 6, z0 - 6, x0 + 36, Y + 10, z0 + 32));
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
        for (int cx = (x0 - 24) >> 4; cx <= (x0 + 48) >> 4; cx++) {
            for (int cz = (z0 - 24) >> 4; cz <= (z0 + 48) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }
}
