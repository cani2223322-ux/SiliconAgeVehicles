package com.sva.debug;

import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.HighLiftMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Aircraft stage A world test (★1 / ★3 / ★26), run by WorldTestSVA alongside its other phases from tick START in its own
 * area (x spawn + 440 .. + 660, z spawn + 400 .. + 500, floor Y 140) with its own test owner; the server drives
 * (debugDrive, its own physics as without a player):
 * - TAKE-OFF: two light planes on bare stone hold W + Space - the one with flaps 10 lifts off in a shorter run;
 * - ROLL-OUT: two light planes dive onto the floor at zero throttle and brake - the ground spoilers come up by
 *   themselves at the touch-down and the roll is shorter than the other's (spoilers kept down);
 * - PROTECTION: at 130 km/h flaps 35 are refused (the warning on) and at 115 km/h they extend; a jet at 144 km/h with
 *   flaps 40 has them blown back to 20;
 * - AIRBRAKE: a light plane at 58 km/h, 40 % throttle, no keys, airbrake on - it descends without speeding up (slats out).
 */
final class WorldTestAirSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5a6";
    static final int START = 620;
    private static final int Y = 140, LANE = 16, LEN = 220, LANES = 6;
    private static final int RUN_MAX = 300, ROLL_MAX = 300, AB_TICKS = 40, BLOW_TICKS = 3;
    /** Motor on, no keys: left + right cancel out (debugDrive needs a flag). */
    private static final int IDLE = EntityVehicleSVA.IN_LEFT | EntityVehicleSVA.IN_RIGHT;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA to0, to1, ld0, ld1, jet, ab;
    private double toX0, toX1, toDist0 = -1, toDist1 = -1, ldStart0 = Double.NaN, ldStart1 = Double.NaN, ldDist0 = -1, ldDist1 = -1;
    private double abY, abSpeed, abMaxSpeed;
    private boolean spoilersSeen, blowDone, abDone;
    boolean done;

    WorldTestAirSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = to0 == null || to1 == null || ld0 == null || ld1 == null || jet == null || ab == null ? 9 : 1;
            if (phase == 1) {
                protection();
                start();
            }
            return;
        }
        int n = ticks - t0;
        if (phase == 1) {
            takeoff(to0, toX0, 0);
            takeoff(to1, toX1, 1);
            landing(ld0, 0);
            landing(ld1, 1);
            if (!blowDone && n >= BLOW_TICKS) {
                blowDone = true;
                check(jet.getFlaps() == 1 && jet.flapWarning(), "stage A world: a jet at " + com.sva.vehicle.VehicleMath.kmh(jet.speed)
                        + " km/h with flaps 40 has them blown back to 20 (now " + Math.round(jet.getFlapAngle()) + " deg), the warning on");
                jet.debugDrive(0);
            }
            if (!abDone) {
                abMaxSpeed = Math.max(abMaxSpeed, ab.speed);
                if (n >= AB_TICKS) {
                    abDone = true;
                    check(ab.isAirbrake() && ab.slatsOut() && ab.posY < abY - 2 && abMaxSpeed <= abSpeed + 0.005,
                            "stage A world: airbrake at " + com.sva.vehicle.VehicleMath.kmh(abSpeed) + " km/h, 40 % throttle - "
                                    + Math.round((abY - ab.posY) * 10) / 10.0 + " blocks down in 2 s, no faster (max "
                                    + com.sva.vehicle.VehicleMath.kmh(abMaxSpeed) + " km/h), slats out");
                    ab.debugDrive(0);
                }
            }
            boolean all = toDist0 >= 0 && toDist1 >= 0 && ldDist0 >= 0 && ldDist1 >= 0;
            if (all || n >= Math.max(RUN_MAX, ROLL_MAX)) {
                verify();
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    /** The overspeed rule through the entity: refused at 130 km/h, taken at 115. */
    private void protection() {
        EntityVehicleSVA v = ab;
        int a = v.debugFlapKey(1, 130 / 72.0), b = v.debugFlapKey(1, 130 / 72.0), c = v.debugFlapKey(1, 130 / 72.0);
        boolean warn = v.flapWarning();
        int d = v.debugFlapKey(1, 115 / 72.0);
        check(a == 1 && b == 2 && c == 2 && warn && d == 3, "stage A world: at 130 km/h flaps 10 / 20 extend, 35 is refused (warning), at 115 km/h it extends ("
                + a + " " + b + " " + c + " " + d + ")");
        v.debugHighLift(0, true, false);                            // the airbrake plane: flaps up, airbrake on
    }

    private void start() {
        to0.debugHighLift(0, false, false);
        to1.debugHighLift(1, false, false);
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {to0, to1}) {
            v.debugDrive(EntityVehicleSVA.IN_FWD | EntityVehicleSVA.IN_BRAKE);    // W + Space: run up, rotate at lift-off speed
        }
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {ld0, ld1}) {
            v.speed = 0.7;
            v.throttle = 0;
            v.debugDrive(EntityVehicleSVA.IN_DOWN);                 // push down onto the floor
        }
        jet.debugHighLift(2, false, false);
        jet.speed = 2.0;
        jet.throttle = 1;
        jet.debugDrive(IDLE);
        abY = ab.posY;
        ab.speed = abSpeed = 0.8;
        ab.throttle = 0.4;
        ab.debugDrive(IDLE);
    }

    private void takeoff(EntityVehicleSVA v, double x0v, int k) {
        double d = k == 0 ? toDist0 : toDist1;
        if (d >= 0 || v.isDead) {
            return;
        }
        if (v.posY > Y + 1 + 0.3) {
            d = v.posX - x0v;
            if (k == 0) {
                toDist0 = d;
            } else {
                toDist1 = d;
            }
            v.debugDrive(0);
        }
    }

    private void landing(EntityVehicleSVA v, int k) {
        if ((k == 0 ? ldDist0 : ldDist1) >= 0 || v.isDead) {
            return;
        }
        double st = k == 0 ? ldStart0 : ldStart1;
        if (Double.isNaN(st)) {
            if (v.onGround && v.posY < Y + 1.2) {
                if (k == 0) {
                    ldStart0 = v.posX;
                } else {
                    ldStart1 = v.posX;
                }
                v.debugDrive(EntityVehicleSVA.IN_BACK);             // zero throttle + S: the wheel brakes
            }
            return;
        }
        if (k == 0) {
            spoilersSeen |= v.groundSpoilersUp();
        } else {
            v.debugHighLift(0, false, false);                       // the control plane: spoilers kept down
        }
        if (v.speed < 0.01) {
            if (k == 0) {
                ldDist0 = v.posX - st;
            } else {
                ldDist1 = v.posX - st;
            }
            v.debugDrive(0);
        }
    }

    private void verify() {
        check(toDist0 > 0 && toDist1 > 0 && toDist1 < toDist0 * 0.92, "stage A world: take-off run on stone - flaps 0 "
                + Math.round(toDist0 * 10) / 10.0 + " blocks, flaps 10 " + Math.round(toDist1 * 10) / 10.0 + " (x"
                + (toDist0 > 0 ? Math.round(toDist1 / toDist0 * 100) / 100.0 : 0) + ", the table: x0.82)");
        check(spoilersSeen && ldDist0 > 0 && ldDist1 > 0 && ldDist0 < ldDist1 * 0.8, "stage A world: roll-out after a low-throttle touch-down - "
                + "the spoilers came up by themselves (" + spoilersSeen + "), " + Math.round(ldDist0 * 10) / 10.0 + " blocks vs "
                + Math.round(ldDist1 * 10) / 10.0 + " with them down (x" + (ldDist1 > 0 ? Math.round(ldDist0 / ldDist1 * 100) / 100.0 : 0) + ")");
    }

    // ---------------------------------------------------------------- setup

    private EntityVehicleSVA place(World w, VehicleType type, double x, double y, double z) {
        VehicleState st = new VehicleState(type);
        st.energy = st.maxEnergy() * 0.8;
        if (type.gases.liftGas != null) {
            st.gas[type.gases.liftGas.ordinal()] = 2000;
        }
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x, y, z, -90F, OWNER, "WorldTestAir");      // facing +x
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 440;
        z0 = sp.posZ + 400;
        keepLoaded(w);
        area(w, true);
        to0 = place(w, VehicleType.PLANE, x0 + 8.5, Y + 1, z0 + 0.5);
        to1 = place(w, VehicleType.PLANE, x0 + 8.5, Y + 1, z0 + LANE + 0.5);
        ld0 = place(w, VehicleType.PLANE, x0 + 8.5, Y + 4, z0 + 2 * LANE + 0.5);
        ld1 = place(w, VehicleType.PLANE, x0 + 8.5, Y + 4, z0 + 3 * LANE + 0.5);
        jet = place(w, VehicleType.JET, x0 + 8.5, Y + 60, z0 + 4 * LANE + 0.5);
        ab = place(w, VehicleType.PLANE, x0 + 8.5, Y + 40, z0 + 5 * LANE + 0.5);
        toX0 = to0 == null ? 0 : to0.posX;
        toX1 = to1 == null ? 0 : to1.posX;
        check(to0 != null && to1 != null && ld0 != null && ld1 != null && jet != null && ab != null && HighLiftMath.spec(VehicleType.PLANE) != null,
                "stage A world: five light planes and a jet placed");
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 40) >> 4; cx <= (x0 + LEN + 40) >> 4; cx++) {     // entities tick only with 32 blocks around loaded
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + LANES * LANE + 40) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void area(World w, boolean build) {
        for (int x = x0 - 4; x <= x0 + LEN; x++) {
            for (int z = z0 - 8; z <= z0 + LANES * LANE; z++) {
                for (int y = Y + 1; y <= Y + 8; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {to0, to1, ld0, ld1, jet, ab}) {
            if (v != null && !v.isDead) {
                v.debugDrive(0);
                v.dismountAll();
                v.setDead();
            }
        }
        to0 = to1 = ld0 = ld1 = jet = ab = null;
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
            done = true;
        }
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }
}
