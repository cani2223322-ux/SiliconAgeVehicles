package com.sva.debug;

import com.sc.util.ArmorGasSC.Gas;
import com.sva.block.TileEntityGasColumnSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemBatterySVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Hydrogen wave world test, run by WorldTestSVA alongside its other phases from tick START in its own area (x spawn +
 * 1800 .. + 1880, z spawn + 1800 .. + 1852, Y 200) with its own test owner; every vehicle here forces hydrogen
 * (debugFuel) whatever the legacy suites run in:
 * - DRIVE: a buggy with a full tank and a half battery starts its engine through the real sequence (forward pressed:
 *   the starter, the catch, the warm-up), drives, burns H2 (the flow shows), and the generator charges the battery;
 * - NO BATTERY: the same buggy without its battery - no start, no electrics, it stays; another type's battery doesn't go
 *   in, its own does;
 * - OUT OF H2: a running buggy with a few mB left - the engine stops, it rolls to a stop;
 * - THE GAS COLUMN pours H2 into a buggy parked beside it; JUMP LEADS: 25 % of a flat battery from a donor that keeps its
 *   30 %; THE KIT: +15 % health in 5 s, the limit -5 %; THE HOVER: its cushion holds up on the battery with an empty tank.
 */
final class WorldTestFuelSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000f0e1";
    static final int START = 1100;
    private static final int Y = 200;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA drive, bare, dry, fill, donor, flat, kit, hover;
    private TileEntityGasColumnSVA column;
    private double e0, h0, xStart, dryX;
    private boolean ranDrive, flowSeen, netUp;
    boolean done;

    WorldTestFuelSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        int dt = ticks - t0;
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = drive == null ? 9 : 1;
            return;
        }
        if (phase == 1 && dt >= 10) {
            e0 = drive.state.energy;
            h0 = drive.state.gas(Gas.HYDROGEN);
            xStart = drive.posX;
            drive.debugDrive(EntityVehicleSVA.IN_FWD);              // the auto-start: forward with the engine off
            bare.debugDrive(EntityVehicleSVA.IN_FWD);
            dry.debugEngine(SystemsMath.RUN);
            dry.debugDrive(EntityVehicleSVA.IN_FWD);
            hover.debugDrive(EntityVehicleSVA.IN_FWD);
            donor.state.energy = donor.state.maxEnergy();
            flat.state.energy = flat.state.maxEnergy() * 0.02;
            e1 = flat.state.energy;
            d1 = donor.state.energy;
            flat.debugJump(donor);
            kit.debugBatteryHealth(0.6);
            kit.debugKit(null);
            t0 = ticks;
            phase = 2;
        } else if (phase == 2) {
            ranDrive |= drive.engineState() == SystemsMath.RUN;
            flowSeen |= drive.h2FlowPerMin() > 1;
            netUp |= drive.engineState() == SystemsMath.RUN && drive.batteryNetPerTick() > 0;
            if (dt == 25) {
                check(drive.engineState() != SystemsMath.OFF, "fuel drive: forward pressed - the starter cranks / the engine catches (state "
                        + SystemsMath.ENGINE_KEYS[drive.engineState()] + ")");
            }
            if (dt == 60) {
                hoverCheck();
            }
            if (dt == 120) {
                drive.debugDrive(0);                                // stops before the platform's end
            }
            if (dt >= 220) {
                verify();
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private double e1, d1;

    private void hoverCheck() {
        check(hover.isLifting() && hover.state.gas(Gas.HYDROGEN) == 0 && hover.engineState() == SystemsMath.OFF,
                "fuel hover: the cushion holds it up on the battery with the H2 tank empty and the engine off");
        check(Math.abs(hover.speed) < 0.01, "fuel hover: no H2 - no drive (speed " + Math.round(hover.speed * 1000) / 1000.0 + ")");
    }

    private void verify() {
        double moved = drive.posX - xStart, burnt = h0 - drive.state.gas(Gas.HYDROGEN);
        check(ranDrive && moved > 20, "fuel drive: the engine ran and the buggy drove on hydrogen (" + Math.round(moved) + " blocks)");
        check(burnt > 0 && burnt < 60 && flowSeen, "fuel drive: it burnt H2 (" + Math.round(burnt * 10) / 10.0 + " mB in ~10 s), the flow shows ("
                + Math.round(drive.h2FlowPerMin()) + " mB/min)");
        check(netUp && drive.state.energy > e0 - BatteryMath.capacity(VehicleType.BUGGY) * 0.04,
                "fuel drive: the generator charges the battery while the engine runs (the start's 3 % paid back: " + Math.round(drive.state.energy - e0) + " EU)");
        check(bare.engineState() == SystemsMath.OFF && Math.abs(bare.posX - (x0 + 2.5)) < 0.5 && !bare.powered() && bare.debugStart() == SystemsMath.R_LOW_BATTERY,
                "fuel no battery: no start, no electrics, it stays put");
        ItemStack plane = ItemBatterySVA.stackOf(ModContentSVA.battery, VehicleType.PLANE, new BatteryMath.Battery());
        BatteryMath.Battery own = new BatteryMath.Battery();
        own.charge = 20000;
        bare.debugDrive(0);
        boolean wrong = !bare.putBattery(plane);
        boolean right = bare.putBattery(ItemBatterySVA.stackOf(ModContentSVA.battery, VehicleType.BUGGY, own));
        check(wrong && right && bare.state.batIn && bare.state.energy == 20000 && bare.powered(), "fuel battery: another type's battery doesn't go in, its own does");
        check(dry.state.gas(Gas.HYDROGEN) == 0 && dry.engineState() == SystemsMath.OFF && Math.abs(dry.speed) < 0.01 && dry.posX > dryX,
                "fuel out of H2: the engine stopped, the buggy rolled to a stop (" + Math.round((dry.posX - dryX) * 10) / 10.0 + " blocks)");
        check(fill.state.gas(Gas.HYDROGEN) > 1000 && column.amount(Gas.HYDROGEN) < 16000, "fuel refuel: the gas column filled the buggy's H2 tank ("
                + fill.state.gas(Gas.HYDROGEN) + " mB)");
        double got = flat.state.energy - e1, gave = d1 - donor.state.energy;
        check(near(got, BatteryMath.capacity(VehicleType.BUGGY) * BatteryMath.JUMP_SHARE, 300) && near(got, gave, 1)
                && donor.state.energy >= donor.state.maxEnergy() * BatteryMath.DONOR_MIN && flat.jumpProgress() < 0,
                "fuel jump leads: 25 % of the battery from the donor in 10 s (+" + Math.round(got) + " EU)");
        check(near(kit.state.batHealth, 0.75, 1e-3) && near(kit.state.batLimit, 0.95, 1e-9) && kit.state.batServices == 1 && kit.batteryServiceProgress() < 0,
                "fuel service kit: +15 % health in 5 s (60 % -> " + Math.round(kit.state.batHealth * 100) + " %), the limit -5 %");
        ItemStack out = kit.takeBattery();
        check(out != null && !kit.state.batIn && kit.state.energy == 0 && near(ItemBatterySVA.batteryOf(out).health, 0.75, 1e-3)
                && !kit.powered(), "fuel battery: taken out it keeps its health and charge; the vehicle goes dark");
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    private EntityVehicleSVA place(World w, VehicleType type, double x, double z, int h2, double batFrac) {
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), new VehicleState(type));
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, s, x, Y + 1, z, -90F, OWNER, "WorldTestFuel");     // facing +x
        if (v != null) {
            v.debugFuel(1);
            v.debugEngineManual = true;                             // the real start sequence
            v.state.energy = v.state.maxEnergy() * batFrac;
            v.state.fillGas(Gas.HYDROGEN, h2, false);
        }
        return v;
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 1800;
        z0 = sp.posZ + 1800;
        keepLoaded(w);
        area(w, true);
        drive = place(w, VehicleType.BUGGY, x0 + 2.5, z0 + 0.5, 4000, 0.5);
        bare = place(w, VehicleType.BUGGY, x0 + 2.5, z0 + 6.5, 4000, 0.5);
        if (bare != null) {
            bare.takeBattery();
        }
        dry = place(w, VehicleType.BUGGY, x0 + 2.5, z0 + 12.5, 3, 0.8);
        dryX = x0 + 2.5;
        w.setBlock(x0 + 2, Y + 1, z0 + 20, ModContentSVA.gasColumn, 0, 2);
        TileEntity te = w.getTileEntity(x0 + 2, Y + 1, z0 + 20);
        column = te instanceof TileEntityGasColumnSVA ? (TileEntityGasColumnSVA) te : null;
        if (column != null) {
            column.fillGas(Gas.HYDROGEN, TileEntityGasColumnSVA.TANK, true);
        }
        fill = place(w, VehicleType.BUGGY, x0 + 5.5, z0 + 20.5, 0, 0.5);
        donor = place(w, VehicleType.TRUCK, x0 + 10.5, z0 + 30.5, 0, 1);
        flat = place(w, VehicleType.BUGGY, x0 + 10.5, z0 + 36.5, 0, 0.02);
        kit = place(w, VehicleType.BUGGY, x0 + 10.5, z0 + 42.5, 0, 0.5);
        hover = place(w, VehicleType.HOVER, x0 + 10.5, z0 + 48.5, 0, 0.8);
        check(drive != null && bare != null && dry != null && column != null && fill != null && donor != null && flat != null && kit != null && hover != null,
                "fuel: the test vehicles and the gas column placed");
        if (column == null || fill == null || donor == null || flat == null || kit == null || hover == null) {
            drive = null;
        }
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 4; x <= x0 + 80; x++) {
            for (int z = z0 - 4; z <= z0 + 52; z++) {
                for (int y = Y + 1; y <= Y + 6; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 40) >> 4; cx <= (x0 + 120) >> 4; cx++) {
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 92) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {drive, bare, dry, fill, donor, flat, kit, hover}) {
            if (v != null && !v.isDead) {
                v.debugDrive(0);
                v.dismountAll();
                v.setDead();
            }
        }
        drive = bare = dry = fill = donor = flat = kit = hover = null;
        if (x0 != 0 || z0 != 0) {
            w.setBlockToAir(x0 + 2, Y + 1, z0 + 20);
            area(w, false);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
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

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }
}
