package com.sva.debug;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.RegenMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Wave 4 world test (★15 рекуперация), run by WorldTestSVA alongside its other phases from tick START in its own area
 * (x spawn + 116 .. + 210, z spawn + 196 .. + 214, Y 210) with its own test owner, through the server's input path:
 * - BRAKING: a buggy runs up to top speed and brakes (Space) to a stop - it gets EU back (~20 % of the run-up's), and
 *   the same run braked again with vehicles.regen off loses more EU by just that much;
 * - A HILL: a second buggy drives up a 5-block staircase, over and down again and stops - it recovered EU on the way
 *   down, yet ends with less energy than it started (never more than the efficiency x the EU it spent).
 */
final class WorldTestRegenSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5a4";
    static final int START = 480;
    private static final int Y = 210, SETTLE = 10, RUN = 40, BRAKE = 25, HILL_MAX = 160, HILL = 5;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA car, hill;
    // braking: energy at the brake / after it, the EU recovered meanwhile - with regen (1) and without (2)
    private double e1a, e1b, got1, e2a, e2b, got2;
    // the hill: start energy, all recovered / recovered while going down, the top reached, braking began
    private double h0, hGot, hDown, hTop;
    private int hBrakeAt = -1;
    private boolean hillDone;
    boolean done;

    WorldTestRegenSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = car == null || hill == null ? 9 : 1;
            return;
        }
        if (phase >= 2 && phase < 9 && !hillDone) {
            hillTick(ticks);
        }
        int dt = ticks - t0;
        if (phase == 1 && dt >= SETTLE) {
            h0 = hill.state.energy;
            hTop = hill.posY;
            hill.debugDrive(EntityVehicleSVA.IN_FWD);
            car.debugDrive(EntityVehicleSVA.IN_FWD);
            t0 = ticks;
            phase = 2;
        } else if (phase == 2 && dt >= RUN) {
            e1a = car.state.energy;
            got1 = 0;
            car.debugDrive(EntityVehicleSVA.IN_BRAKE);
            t0 = ticks;
            phase = 3;
        } else if (phase == 3) {
            got1 += car.getRegenNow();
            if (dt >= BRAKE) {
                e1b = car.state.energy;
                car.debugDrive(EntityVehicleSVA.IN_FWD);
                t0 = ticks;
                phase = 4;
            }
        } else if (phase == 4 && dt >= RUN) {
            ConfigSVA.regen = false;                                // the same run, recovery off
            e2a = car.state.energy;
            got2 = 0;
            car.debugDrive(EntityVehicleSVA.IN_BRAKE);
            t0 = ticks;
            phase = 5;
        } else if (phase == 5) {
            got2 += car.getRegenNow();
            if (dt >= BRAKE) {
                e2b = car.state.energy;
                ConfigSVA.regen = true;
                car.debugDrive(0);
                verifyBrake();
                phase = 6;
            }
        } else if (phase == 6 && hillDone) {
            phase = 9;
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void hillTick(int ticks) {
        double r = hill.getRegenNow();
        hGot += r;
        if (hill.posY < hTop - 0.01 && hTop > Y + 2) {
            hDown += r;                                             // coming down from the top
        }
        hTop = Math.max(hTop, hill.posY);
        if (hBrakeAt < 0 && (hill.posX > x0 + 50 || ticks - t0 > HILL_MAX)) {
            hill.debugDrive(EntityVehicleSVA.IN_BRAKE);
            hBrakeAt = ticks;
        } else if (hBrakeAt >= 0 && ticks - hBrakeAt >= BRAKE) {
            hill.debugDrive(0);
            double e1 = hill.state.energy, spent = h0 - e1 + hGot;
            double share = RegenMath.share(VehicleType.BUGGY, false, ConfigSVA.regenEfficiency);
            check(hTop >= Y + 1 + HILL - 0.1 && Math.abs(hill.posY - (Y + 1)) < 0.2 && hill.posX > x0 + 45,
                    "regen hill: the buggy drove up the 5-block hill and down again (top Y " + Math.round(hTop * 10) / 10.0 + ", now Y "
                            + Math.round(hill.posY * 10) / 10.0 + ")");
            check(hDown > 0, "regen hill: rolling downhill recovers EU (+" + RegenMath.fmt(hDown) + " EU)");
            check(e1 < h0 && hGot <= share * spent + 1e-6, "regen hill: up and down ends with less EU than it started (" + Math.round(e1 - h0)
                    + " EU; recovered " + Math.round(hGot) + " of " + Math.round(spent) + " spent)");
            hillDone = true;
        }
    }

    private void verifyBrake() {
        double loss1 = e1a - e1b, loss2 = e2a - e2b;
        check(got1 > 25 && got1 < 60, "regen braking: a buggy braking from top speed gets EU back (+" + RegenMath.fmt(got1) + " EU, ~20 % of "
                + Math.round(RegenMath.kinetic(VehicleType.BUGGY, VehicleType.BUGGY.topSpeed, 0, 1)) + ")");
        check(got2 == 0 && loss1 < loss2 && Math.abs(loss2 - loss1 - got1) < 2 + got1 * 0.1,
                "regen braking: with vehicles.regen off the same stop loses more EU (" + Math.round(loss1) + " vs " + Math.round(loss2) + ")");
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 40) >> 4; cx <= (x0 + 130) >> 4; cx++) {     // entities tick only with 32 blocks around loaded
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 52) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private int hillHeight(int x) {
        int d = x - x0;
        if (d >= 20 && d < 20 + HILL) {
            return d - 19;                                          // 1..5 up
        }
        if (d >= 20 + HILL && d < 30 + HILL) {
            return HILL;                                            // the top
        }
        if (d >= 30 + HILL && d < 30 + 2 * HILL) {
            return 30 + 2 * HILL - d - 1;                           // 4..0 down
        }
        return 0;
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 4; x <= x0 + 90; x++) {
            for (int z = z0 - 4; z <= z0 + 14; z++) {
                int h = build && z >= z0 + 6 ? hillHeight(x) : 0;
                for (int y = Y + 1; y <= Y + 8; y++) {
                    net.minecraft.block.Block b = y <= Y + h ? Blocks.stone : Blocks.air;
                    if (w.getBlock(x, y, z) != b) {
                        w.setBlock(x, y, z, b, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private EntityVehicleSVA place(World w, double x, double z) {
        VehicleState st = new VehicleState(VehicleType.BUGGY);
        st.energy = st.maxEnergy() * 0.5;
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.BUGGY.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x, Y + 1, z, -90F, OWNER, "WorldTest4");     // facing +x
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 120;
        z0 = sp.posZ + 200;
        keepLoaded(w);
        area(w, true);
        car = place(w, x0 + 2.5, z0 + 0.5);
        hill = place(w, x0 + 2.5, z0 + 10.5);
        check(car != null && hill != null, "regen: two test buggies placed");
    }

    private void finish(World w) {
        ConfigSVA.regen = true;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {car, hill}) {
            if (v != null && !v.isDead) {
                v.debugDrive(0);
                v.dismountAll();
                v.setDead();
            }
        }
        car = hill = null;
        if (x0 != 0 || z0 != 0) {
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
            ConfigSVA.regen = true;
            done = true;
        }
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }
}
