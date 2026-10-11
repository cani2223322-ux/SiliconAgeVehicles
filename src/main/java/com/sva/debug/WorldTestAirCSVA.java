package com.sva.debug;

import com.sva.block.BlockRunwaySVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.RunwayMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Aircraft stage C world test (★17 the autopilot's modes), run by WorldTestSVA alongside its other phases from tick START in
 * its own area (x spawn + 730 .. + 1020, z spawn + 370 .. + 570, a runway at Y 150) with its own test owner; the server
 * flies (debugDrive with IN_CRUISE and the targets / modes set as the Input packet would - its own physics as without a
 * player):
 * - ВЫС + КУРС: a light plane at 79 km/h, 40 deg off the set heading and 8 blocks under the set height - after 8 s it
 *   holds both;
 * - ЗАХВ: a light plane 110 blocks before an 80 x 5 strip of runway blocks placed here, 9 blocks off its centre line at 20
 *   deg, 16 blocks up (ЗАХВ + ВЫС + СКОР) - it finds the strip (RunwayMath on the real world), aligns with its axis and
 *   descends on the glide slope until the autopilot lets go low over the strip (CruiseMath.OFF_LANDING).
 */
final class WorldTestAirCSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5c7";
    static final int START = 640;
    private static final int Y = 150, RW_X = 150, RW_LEN = 80, RW_W = 5, LANE = 40;
    private static final int HOLD_TICKS = 160, CAP_TICKS = 500;
    /** The ВЫС + КУРС plane flies south-east for 8 s (~ +140 x, +80 z from its start): its loaded strip reaches this far. */
    private static final int HOLD_X = 160, HOLD_Z = 100;
    private static final int IN = EntityVehicleSVA.IN_CRUISE;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA hold, cap;
    private double holdYaw, holdAlt;
    private boolean alignedSeen, letGo, holdDone, capDone;
    private double capY0, crossAtAlign = Double.NaN, minOver = Double.MAX_VALUE;
    private int alignedAt = -1;
    boolean done;

    WorldTestAirCSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = hold == null || cap == null ? 9 : 1;
            if (phase == 1) {
                start();
            }
            return;
        }
        int n = ticks - t0;
        if (phase == 1) {
            if (!holdDone && n >= HOLD_TICKS) {
                holdDone = true;
                double alt = Math.abs(hold.posY - holdAlt), yaw = Math.abs(CruiseMath.wrap(hold.rotationYaw - holdYaw));
                check(hold.isCruiseOn() && alt < 1.0 && yaw < 2, "stage C world: AP ВЫС + КУРС - a light plane 40 deg off and 8 blocks under holds"
                        + " the set heading / height after 8 s (err " + Math.round(alt * 100) / 100.0 + " bl, " + Math.round(yaw * 10) / 10.0 + " deg, engaged "
                        + hold.isCruiseOn() + ")");
                hold.debugDrive(0);
            }
            if (!capDone) {
                capture(n);
            }
            if (holdDone && capDone) {
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void capture(int n) {
        if (cap.isDead) {
            capDone = true;
            check(false, "stage C world: ЗАХВ - the plane was lost");
            return;
        }
        RunwayMath.Runway r = cap.runway();
        AutopilotMath.Capture c = cap.capture();
        if (c != null && c.aligned && !alignedSeen) {
            alignedSeen = true;
            alignedAt = n;
            crossAtAlign = c.cross;
        }
        if (r != null) {
            minOver = Math.min(minOver, cap.posY - r.y);
        }
        if (!letGo && cap.cruiseBlocked(IN) == CruiseMath.OFF_LANDING) {
            letGo = true;
        }
        if (letGo || n >= CAP_TICKS) {
            capDone = true;
            double hdg = CruiseMath.heading(cap.rotationYaw);
            check(r != null && r.alongX && r.length() == RW_LEN && r.width == RW_W && Math.abs(r.y - (Y + 1)) < 1e-9,
                    "stage C world: ЗАХВ finds the " + RW_LEN + " x " + RW_W + " runway strip placed in the world (" + (r == null ? "none"
                            : (int) r.length() + " x " + r.width + " along " + (r.alongX ? "X" : "Z") + ", surface " + r.y) + ")");
            check(alignedSeen && letGo && Math.abs(CruiseMath.wrap(hdg - 90)) < 3 && c != null && Math.abs(c.cross) < 1.5 && capY0 - cap.posY > 8,
                    "stage C world: ЗАХВ - from 9 blocks off at 20 deg it aligns with the runway's axis (after " + alignedAt + " ticks), descends "
                            + Math.round((capY0 - cap.posY) * 10) / 10.0 + " blocks on the glide slope and lets go " + Math.round(minOver * 10) / 10.0
                            + " blocks over the strip (heading " + Math.round(hdg) + ", cross " + (c == null ? "-" : Math.round(c.cross * 100) / 100.0)
                            + ", let go " + letGo + ")");
            cap.debugDrive(0);
        }
    }

    private void start() {
        // ВЫС + КУРС: facing east (+x, yaw -90) - the set heading 40 deg right (compass 130 = yaw -50), 8 blocks higher
        hold.speed = 1.1;
        hold.throttle = 0.85;
        holdYaw = -50;
        holdAlt = hold.posY + 8;
        hold.setCruiseTargets(CruiseMath.fromKmh(72), holdYaw, holdAlt);
        hold.setApModes(AutopilotMath.DEFAULT, 0);
        hold.debugDrive(IN);
        // ЗАХВ: heading 70 (yaw -110), toward the strip's west end; holds its height until aligned, СКОР 72 km/h
        cap.speed = 1.0;
        cap.throttle = 0.6;
        cap.rotationYaw = cap.prevRotationYaw = -110;
        capY0 = cap.posY;
        cap.setCruiseTargets(CruiseMath.fromKmh(72), -110, cap.posY);
        cap.setApModes(AutopilotMath.M_CAP | AutopilotMath.M_ALT | AutopilotMath.M_SPD, 0);
        cap.debugDrive(IN);
    }

    // ---------------------------------------------------------------- setup

    private EntityVehicleSVA place(World w, VehicleType type, double x, double y, double z, float yaw) {
        VehicleState st = new VehicleState(type);
        st.energy = st.maxEnergy() * 0.9;
        if (type.gases.liftGas != null) {
            st.gas[type.gases.liftGas.ordinal()] = 2000;
        }
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x, y, z, yaw, OWNER, "WorldTestAirC");
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 760;
        z0 = sp.posZ + 400;
        keepLoaded(w);
        area(w, true);
        hold = place(w, VehicleType.PLANE, x0 + 10.5, Y + 20, z0 + LANE + 0.5, -90F);      // under the ceiling's fade (190)
        // the runway's centre line at z0 + 0.5 (blocks z0 - 2 .. z0 + 2), x0 + RW_X .. + RW_LEN; the plane 110 before it, 9 off
        cap = place(w, VehicleType.PLANE, x0 + RW_X - 110 + 0.5, Y + 17, z0 + 9.5, -110F);
        check(hold != null && cap != null && ModContentSVA.runway != null, "stage C world: two light planes and a runway placed");
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        // two strips (entities tick only with 32 blocks round them loaded): the runway lane, the ВЫС + КУРС plane's path
        load(cp, x0 - 32, x0 + RW_X + RW_LEN + 32, z0 - 32, z0 + 32);
        load(cp, x0 - 32, x0 + HOLD_X + 32, z0 + LANE - 32, z0 + LANE + HOLD_Z + 32);
    }

    private static void load(net.minecraft.world.gen.ChunkProviderServer cp, int xa, int xb, int za, int zb) {
        for (int cx = xa >> 4; cx <= xb >> 4; cx++) {
            for (int cz = za >> 4; cz <= zb >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    /** The runway strip (axis along X: meta AXIS_X) on air - only its blocks, nothing else around up to the sky. */
    private void area(World w, boolean build) {
        for (int x = x0 + RW_X; x < x0 + RW_X + RW_LEN; x++) {
            for (int z = z0 - RW_W / 2; z <= z0 + RW_W / 2; z++) {
                w.setBlock(x, Y, z, build && ModContentSVA.runway != null ? ModContentSVA.runway : Blocks.air, BlockRunwaySVA.AXIS_X, 2);
            }
        }
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {hold, cap}) {
            if (v != null && !v.isDead) {
                v.debugDrive(0);
                v.dismountAll();
                v.setDead();
            }
        }
        hold = cap = null;
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
