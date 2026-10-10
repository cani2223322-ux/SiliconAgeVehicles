package com.sva.debug;

import com.sc.energy.Tier;
import com.sc.tileentity.TileEntityEnergyStorageSC;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.block.TileEntityChargerSVA;
import com.sva.block.TileEntityGasColumnSVA;
import com.sva.block.TileEntitySupercapSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemStationModuleSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.platform.PlatformMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Fast charging world test (2026-10-10), run by WorldTestSVA alongside its other phases from tick START in its own area
 * (x spawn + 116 .. + 150, z spawn + 112 .. + 140, Y 215) with its own test owner:
 * - TWO MV stations on one 3x3 platform charge an empty buggy about twice as fast as one station on its own 3x3 platform;
 *   one of the two is the plates' master, each sees the other;
 * - a station with an EMPTY buffer and no cable, an HV core energy storage beside it with its output towards it: the
 *   buggy on top charges at the station's rate straight from the storage (the storage pays exactly what the buggy got),
 *   the station refuses grid input on that face and isn't overvolted by the HV storage;
 * - a SUPERCAPACITOR under an empty station: the Singular Ship on top gets its 50M EU at 500 000 EU/t - empty in ~5 s;
 * - BOOST: x3 (384 EU/t), near the top of the heat it overheats and stops charging; with the gas module and a column
 *   of helium it stays cool (helium taken, no overheat);
 * - IC2 (when loaded): a BatBox facing the station feeds the buggy on top the same way.
 */
final class WorldTestFastChargeSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-0000000fa57c";
    static final int START = 520;
    private static final int Y = 215;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private TileEntityChargerSVA s1, s2, s3, s4, s5, s6, s7, s8;
    private TileEntityEnergyStorageSC storage;
    private TileEntitySupercapSVA cap;
    private TileEntityGasColumnSVA column;
    private TileEntity batBox;
    private EntityVehicleSVA shared, single, onStorage, ship, hot, cooled, onBatBox;
    private int storage0, helium0, batBox0;
    private double hotE;
    boolean done;

    WorldTestFastChargeSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = s1 == null ? 9 : 1;
        }
        if (phase >= 1 && phase < 9) {
            for (TileEntityChargerSVA s : new TileEntityChargerSVA[] {s1, s2, s3, s6, s7}) {
                if (s != null) {
                    s.fillForTest(s.getMaxEnergyStored());          // stands in for a cable feed
                }
            }
        }
        if (phase == 1 && ticks - t0 >= 40) {
            checkShared();
            checkStorage(w);
            checkBatBox();
            checkCapMid();
            phase = 2;
        } else if (phase == 2 && ticks - t0 >= 60) {
            checkOverheat();
            phase = 3;
        } else if (phase == 3 && ticks - t0 >= 90) {
            checkPaused();
            checkHelium();
            phase = 4;
        } else if (phase == 4 && ticks - t0 >= 130) {
            checkCapDone();
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 8) >> 4; cx <= (x0 + 40) >> 4; cx++) {
            for (int cz = (z0 - 12) >> 4; cz <= (z0 + 32) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 4; x <= x0 + 34; x++) {
            for (int z = z0 - 8; z <= z0 + 22; z++) {
                for (int y = Y - 1; y <= Y + 8; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y - 2, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private TileEntityChargerSVA station(World w, int x, int z, Tier tier) {
        w.setBlock(x, Y, z, ModContentSVA.charger, tier.ordinal(), 3);
        TileEntity te = w.getTileEntity(x, Y, z);
        return te instanceof TileEntityChargerSVA ? (TileEntityChargerSVA) te : null;
    }

    private void plates(World w, int xa, int za, int xb, int zb) {
        for (int x = xa; x <= xb; x++) {
            for (int z = za; z <= zb; z++) {
                w.setBlock(x, Y, z, ModContentSVA.chargePlate, 0, 3);
            }
        }
    }

    private EntityVehicleSVA place(World w, VehicleType type, double x, double z) {
        VehicleState st = new VehicleState(type);
        st.energy = 0;
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x, Y + 1, z, 0F, OWNER, "WorldTestFC");
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 120;
        z0 = sp.posZ + 120;
        keepLoaded(w);
        area(w, true);
        // A: two stations on one 3x3 platform / a control station on its own 3x3
        s1 = station(w, x0, z0, Tier.MV);
        s2 = station(w, x0 + 4, z0, Tier.MV);
        plates(w, x0 + 1, z0 - 1, x0 + 3, z0 + 1);
        s3 = station(w, x0, z0 + 6, Tier.MV);
        plates(w, x0 + 1, z0 + 5, x0 + 3, z0 + 7);
        // B: an empty station, no cable, an HV core storage south of it with its output (front) towards it
        s4 = station(w, x0 + 10, z0, Tier.MV);
        w.setBlock(x0 + 10, Y, z0 + 1, com.sc.init.ModBlocks.energyStorageSC, Tier.HV.ordinal(), 3);
        TileEntity st = w.getTileEntity(x0 + 10, Y, z0 + 1);
        storage = st instanceof TileEntityEnergyStorageSC ? (TileEntityEnergyStorageSC) st : null;
        if (storage != null) {
            storage.setFacing(ForgeDirection.NORTH);
            storage.setStoredFromItem(2000000);
        }
        // C: an empty station, a full supercapacitor under it, the Singular Ship on top
        s5 = station(w, x0 + 26, z0 + 12, Tier.MV);
        w.setBlock(x0 + 26, Y - 1, z0 + 12, ModContentSVA.supercap, 0, 3);
        TileEntity ct = w.getTileEntity(x0 + 26, Y - 1, z0 + 12);
        cap = ct instanceof TileEntitySupercapSVA ? (TileEntitySupercapSVA) ct : null;
        if (cap != null) {
            cap.fillForTest(PlatformMath.CAP_BUFFER);
        }
        // D: boost near the top of the heat; E: boost with the gas module and a helium column under the station
        s6 = station(w, x0 + 10, z0 + 10, Tier.MV);
        s7 = station(w, x0 + 16, z0 + 10, Tier.MV);
        w.setBlock(x0 + 16, Y - 1, z0 + 10, ModContentSVA.gasColumn, 2, 3);
        TileEntity gt = w.getTileEntity(x0 + 16, Y - 1, z0 + 10);
        column = gt instanceof TileEntityGasColumnSVA ? (TileEntityGasColumnSVA) gt : null;
        if (column != null) {
            column.fillGas(Gas.HELIUM, 1000, true);
            helium0 = column.amount(Gas.HELIUM);
        }
        // F: IC2's BatBox under an empty station, facing up
        if (cpw.mods.fml.common.Loader.isModLoaded(com.sc.Reference.IC2_MODID)) {
            s8 = station(w, x0 + 4, z0 + 16, Tier.MV);
            batBox = Ic2.batBox(w, x0 + 4, Y - 1, z0 + 16);
            batBox0 = batBox == null ? 0 : Ic2.stored(batBox);
        }
        boolean ok = s1 != null && s2 != null && s3 != null && s4 != null && s5 != null && s6 != null && s7 != null && storage != null && cap != null
                && column != null;
        check(ok, "fast charge: stations, storage, supercapacitor and column placed with their tiles");
        if (!ok) {
            s1 = null;
            return;
        }
        com.sva.ConfigSVA.stationBoost = true;
        s6.toggleSetting(TileEntityChargerSVA.SET_BOOST);
        s7.toggleSetting(TileEntityChargerSVA.SET_BOOST);
        s7.setInventorySlotContents(TileEntityChargerSVA.FIRST_MODULE, ModContentSVA.module.stackOf(ItemStationModuleSVA.GAS));
        s6.setHeatForTest(PlatformMath.HEAT_MAX - 30);
        s7.setHeatForTest(PlatformMath.HEAT_MAX - 30);
        shared = place(w, VehicleType.BUGGY, x0 + 2.5, z0 + 0.5);
        single = place(w, VehicleType.BUGGY, x0 + 2.5, z0 + 6.5);
        onStorage = place(w, VehicleType.BUGGY, x0 + 10.5, z0 + 0.5);
        ship = place(w, VehicleType.SHIP, x0 + 26.5, z0 + 12.5);
        hot = place(w, VehicleType.BUGGY, x0 + 10.5, z0 + 10.5);
        cooled = place(w, VehicleType.BUGGY, x0 + 16.5, z0 + 10.5);
        if (s8 != null) {
            onBatBox = place(w, VehicleType.BUGGY, x0 + 4.5, z0 + 16.5);
        }
        storage0 = storage.getEnergyStored();
        for (TileEntityChargerSVA s : new TileEntityChargerSVA[] {s1, s2, s3, s4, s5, s6, s7, s8}) {
            if (s != null) {
                s.rescanForTest();
            }
        }
        check(shared != null && single != null && onStorage != null && ship != null && hot != null && cooled != null,
                "fast charge: test vehicles placed");
        check(s1.peerStations().size() == 1 && s2.peerStations().size() == 1 && s1.isMaster() != s2.isMaster() && s1.isMaster()
                        && s3.peerStations().isEmpty() && s3.isMaster() && s1.plateCount() == 9 && s2.plateCount() == 9,
                "shared platform: both stations find the 9 plates and each other; the west one shows the plates");
        check(s4.storageCount() == 1 && !s4.acceptsFrom(ForgeDirection.SOUTH) && s4.acceptsFrom(ForgeDirection.NORTH) && s5.supercaps().size() == 1,
                "storage link: the storage's output face found, no grid input on that face; the supercapacitor found");
        check(s6.rate() == 384 && s6.boostActive(), "boost: x3 - 384 EU/t on a bare MV station (" + s6.rate() + ")");
    }

    private void checkShared() {
        if (shared == null || single == null) {
            return;
        }
        double a = shared.state.energy, b = single.state.energy;
        check(b >= 128 * 30 && a >= 1.8 * b && a <= 2.05 * b + 256,
                "shared platform: two stations charged the buggy " + (int) a + " EU vs one station " + (int) b + " EU in 40 ticks (x" + Math.round(a / Math.max(1, b) * 100) / 100.0 + ")");
        check(s1.summedRate() == 256 && s1.getCurrent() == shared && s2.getCurrent() == shared,
                "shared platform: both serve the same buggy, 128 + 128 = " + s1.summedRate() + " EU/t together");
    }

    private void checkStorage(World w) {
        if (onStorage == null) {
            return;
        }
        double e = onStorage.state.energy;
        int took = storage0 - storage.getEnergyStored();
        check(e >= 128 * 30 && e <= 128 * 41 && took == (int) e && s4.getEnergyStored() == 0,
                "storage link: the buggy on an empty, uncabled station got " + (int) e + " EU at <= 128 EU/t straight from the storage (it paid " + took + ")");
        check(w.getBlock(x0 + 10, Y, z0) == ModContentSVA.charger && !s4.isInvalid(), "storage link: the HV storage never overvolted the MV station");
    }

    private void checkBatBox() {
        if (s8 == null) {
            return;
        }
        if (batBox == null || onBatBox == null) {
            check(false, "IC2 storage link: BatBox placed");
            return;
        }
        double e = onBatBox.state.energy;
        int took = batBox0 - Ic2.stored(batBox);
        check(s8.storageCount() == 1 && e >= 128 * 30 && took >= (int) e,
                "IC2 storage link: a BatBox facing the station fed the buggy " + (int) e + " EU (it gave " + took + ")");
    }

    private void checkCapMid() {
        if (ship == null) {
            return;
        }
        double e = ship.state.energy;
        long sum = (long) e + cap.getEnergyStored();
        check(e >= 30L * PlatformMath.CAP_DUMP && e <= 41L * PlatformMath.CAP_DUMP && Math.abs(sum - PlatformMath.CAP_BUFFER) <= 1000,
                "supercapacitor: the ship got " + (int) e + " EU in 40 ticks (500 000 EU/t), nothing lost");
    }

    private void checkCapDone() {
        if (ship == null) {
            return;
        }
        double e = ship.state.energy;
        check(cap.getEnergyStored() == 0 && e >= PlatformMath.CAP_BUFFER - 50000,
                "supercapacitor: empty, the ship has " + (int) e + " EU - 50M in ~5 s");
    }

    private void checkOverheat() {
        if (hot == null) {
            return;
        }
        check(s6.isOverheated() && (s6.getStatus() & TileEntityChargerSVA.S_OVERHEAT) != 0 && !s6.boostActive() && hot.state.energy >= 384 * 20,
                "boost: near the top of the heat it overheated (" + (int) hot.state.energy + " EU charged before)");
        hotE = hot.state.energy;
    }

    private void checkPaused() {
        if (hot == null) {
            return;
        }
        check(s6.isOverheated() && hot.state.energy == hotE, "boost: overheated - charging paused (" + (int) hotE + " EU, still)");
    }

    private void checkHelium() {
        if (cooled == null) {
            return;
        }
        int used = helium0 - column.amount(Gas.HELIUM);
        check(!s7.isOverheated() && s7.getHeat() < PlatformMath.HEAT_MAX - 30 && used >= 3 && used <= 6 && cooled.state.energy >= 384 * 70,
                "boost + helium: no overheat, heat " + s7.getHeat() + ", " + used + " mB of helium in 90 ticks, " + (int) cooled.state.energy + " EU");
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {shared, single, onStorage, ship, hot, cooled, onBatBox}) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        shared = single = onStorage = ship = hot = cooled = onBatBox = null;
        for (TileEntityChargerSVA s : new TileEntityChargerSVA[] {s1, s2, s3, s4, s5, s6, s7, s8}) {
            if (s != null) {
                for (int i = 0; i < s.getSizeInventory(); i++) {
                    s.setInventorySlotContents(i, null);                // no drops
                }
            }
        }
        if (x0 != 0 || z0 != 0) {
            area(w, false);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        check(reg == null || reg.count(OWNER) == 0, "fast charge cleanup: no test vehicles left");
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }

    /** IC2's side of the test - only touched when IC2 is loaded. */
    private static final class Ic2 {
        static TileEntity batBox(World w, int x, int y, int z) {
            ItemStack s = ic2.api.item.IC2Items.getItem("batBox");
            net.minecraft.block.Block b = s == null ? null : net.minecraft.block.Block.getBlockFromItem(s.getItem());
            if (b == null) {
                return null;
            }
            w.setBlock(x, y, z, b, s.getItemDamage(), 3);
            TileEntity te = w.getTileEntity(x, y, z);
            if (!(te instanceof ic2.api.tile.IEnergyStorage) || !(te instanceof ic2.api.tile.IWrenchable)) {
                return null;
            }
            ((ic2.api.tile.IWrenchable) te).setFacing((short) ForgeDirection.UP.ordinal());
            ((ic2.api.tile.IEnergyStorage) te).setStored(40000);
            return te;
        }

        static int stored(TileEntity te) {
            return ((ic2.api.tile.IEnergyStorage) te).getStored();
        }
    }
}
