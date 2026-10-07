package com.sva.debug;

import com.sva.block.BlockRoadSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.TrafficLightMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Wave 1 world test (★4 cruise, ★11 roads), run by WorldTestSVA alongside its other phases from tick START in its own
 * area (x spawn + 30 .. + 115, z spawn - 34 .. - 10, Y 200) with its own test owner. Four buggies drive +x through the
 * server input path for RUN ticks on four lanes: asphalt vs stone (asphalt must take it farther), stone into a guard
 * rail across the lane (it must stop at the rail), asphalt under cruise at 20 km/h (it must hold the speed with no keys).
 * A traffic light gets power and must go amber, then green. Everything is removed afterwards.
 */
final class WorldTestWave1SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5a1";
    static final int START = 140;
    private static final int Y = 200, LANE = 80, RUN = 100, RAIL_AT = 20;
    private static final double CRUISE_KMH = 20;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA asphalt, stone, railed, cruiser;
    private double cruiseX;
    boolean done;

    WorldTestWave1SVA(WorldTestSVA t) {
        this.t = t;
    }

    private int lane(int i) {
        return z0 + i * 6;
    }

    /** Called every server tick from START until done. */
    void tick(World w, int ticks) {
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = 1;
        } else if (phase == 1 && ticks - t0 == RUN - 20) {
            cruiseX = cruiser == null ? 0 : cruiser.posX;
            phase = 2;
        } else if (phase == 2 && ticks - t0 >= RUN) {
            verify(w);
            phase = 3;
        } else if (phase == 3 && ticks - t0 >= RUN + 25) {
            lightCheck(w);
            finish(w);
        }
    }

    private EntityVehicleSVA buggy(World w, int lane) {
        VehicleState full = new VehicleState(VehicleType.BUGGY);
        full.energy = full.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.BUGGY.ordinal()), full);
        return ItemVehicleSVA.spawn(w, s, x0 + 2.5, Y + 1, lane(lane) + 0.5, -90F, OWNER, "WorldTest");   // facing +x
    }

    private void area(World w, boolean build) {
        for (int i = 0; i < 4; i++) {
            int z = lane(i);
            for (int x = x0 - 2; x <= x0 + LANE; x++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int y = Y + 1; y <= Y + 4; y++) {
                        w.setBlock(x, y, z + dz, Blocks.air, 0, 2);
                    }
                    boolean road = build && (i == 0 || i == 3);
                    w.setBlock(x, Y, z + dz, !build ? Blocks.air : road ? ModContentSVA.road : Blocks.stone, road && dz == 0 ? 2 : 0, 2);
                }
            }
        }
        if (build) {
            for (int dz = -2; dz <= 2; dz++) {
                w.setBlock(x0 + RAIL_AT, Y + 1, lane(2) + dz, ModContentSVA.guardRail, 0, 2);       // across the lane (along Z)
            }
            w.setBlock(x0 - 4, Y + 1, z0, ModContentSVA.trafficLight, 0, 3);
        } else {
            w.setBlock(x0 - 4, Y + 1, z0, Blocks.air, 0, 3);
            w.setBlock(x0 - 5, Y + 1, z0, Blocks.air, 0, 3);
        }
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 34;
        z0 = sp.posZ - 34;
        area(w, true);
        check(BlockRoadSVA.surfaceAt(w, x0 + 10.5, Y + 1, lane(0) + 0.5) == com.sva.vehicle.RoadMath.ASPHALT
                        && BlockRoadSVA.surfaceAt(w, x0 + 10.5, Y + 1, lane(1) + 0.5) == com.sva.vehicle.RoadMath.NONE,
                "wave 1: the asphalt lane reads as road under a vehicle, the stone lane not");
        asphalt = buggy(w, 0);
        stone = buggy(w, 1);
        railed = buggy(w, 2);
        cruiser = buggy(w, 3);
        check(asphalt != null && stone != null && railed != null && cruiser != null, "wave 1: four test buggies placed");
        if (asphalt == null || stone == null || railed == null || cruiser == null) {
            return;
        }
        asphalt.debugDrive(EntityVehicleSVA.IN_FWD);
        stone.debugDrive(EntityVehicleSVA.IN_FWD);
        railed.debugDrive(EntityVehicleSVA.IN_FWD);
        cruiser.setCruiseTargets(CruiseMath.fromKmh(CRUISE_KMH), -90, Y + 1);
        cruiser.debugDrive(EntityVehicleSVA.IN_CRUISE);        // no keys: the cruise alone drives it
        // the traffic light: power it (a redstone block beside it)
        w.setBlock(x0 - 5, Y + 1, z0, Blocks.redstone_block, 0, 3);
    }

    private void verify(World w) {
        if (asphalt == null || stone == null || railed == null || cruiser == null) {
            return;
        }
        double a = asphalt.posX - (x0 + 2.5), s = stone.posX - (x0 + 2.5);
        check(a > s * 1.15 && s > 20, "wave 1 roads: a buggy on asphalt goes farther than on stone in " + RUN + " ticks (" + fmt(a) + " vs "
                + fmt(s) + " blocks; top " + VehicleMath.kmh(VehicleType.BUGGY.topSpeed * 1.3) + " vs " + VehicleMath.kmh(VehicleType.BUGGY.topSpeed)
                + " km/h)");
        double front = railed.posX + VehicleType.BUGGY.width / 2.0;
        check(front <= x0 + RAIL_AT + 0.7 && railed.posX > x0 + RAIL_AT - 6 && Math.abs(railed.speed) < 0.05,
                "wave 1 guard rail: the buggy drove into the rail and stopped at it (front x " + fmt(front - x0) + ", rail at " + RAIL_AT
                        + ", speed " + fmt(railed.speed) + ")");
        double per = (cruiser.posX - cruiseX) / 20.0;
        check(Math.abs(VehicleMath.kmh(per) - CRUISE_KMH) <= 1 && cruiser.isCruiseOn(),
                "wave 1 cruise: with no key held the buggy holds " + (int) CRUISE_KMH + " km/h (" + VehicleMath.kmh(per) + ", engaged "
                        + cruiser.isCruiseOn() + ")");
        check(!cruiser.cruiseActive(EntityVehicleSVA.IN_CRUISE | EntityVehicleSVA.IN_BRAKE)
                        && cruiser.cruiseBlocked(EntityVehicleSVA.IN_CRUISE | EntityVehicleSVA.IN_BACK) == CruiseMath.OFF_KEYS,
                "wave 1 cruise: the server drops it with Space (brake) or S held");
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {asphalt, stone, railed, cruiser}) {
            v.debugDrive(0);
        }
    }

    private void lightCheck(World w) {
        int m = w.getBlockMetadata(x0 - 4, Y + 1, z0);
        check(w.getBlock(x0 - 4, Y + 1, z0) == ModContentSVA.trafficLight
                        && com.sva.block.BlockTrafficLightSVA.state(m) == TrafficLightMath.GREEN,
                "wave 1 traffic light: powered by redstone it went through amber to green (state " + com.sva.block.BlockTrafficLightSVA.state(m) + ")");
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {asphalt, stone, railed, cruiser}) {
            if (v != null) {
                v.debugDrive(0);
                v.setDead();
            }
        }
        asphalt = stone = railed = cruiser = null;
        area(w, false);
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        check(reg == null || reg.count(OWNER) == 0, "wave 1 cleanup: no test vehicles left");
        done = true;
    }

    /** An exception: clean up and end the branch. */
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

    private static String fmt(double d) {
        return String.format("%.2f", Double.valueOf(d));
    }
}
