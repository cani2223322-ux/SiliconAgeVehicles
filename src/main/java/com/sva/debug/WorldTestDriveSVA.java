package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.TiltMath;
import com.sva.vehicle.TrailerMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * World test of the driver-client path (2026-10-10 fixes), run by WorldTestSVA alongside its other phases from tick
 * START in its own area (x spawn + 260 .. + 410, z spawn + 300 .. + 380, floor Y 150) with its own test owner. A fake
 * player sits at the wheel and the test plays the driver's client: each tick the vehicle's own physicsStep runs on a
 * copy of the client's state (position, yaw, speed, vertical motion, throttle), then the Move goes through
 * serverMove exactly as NetSVA's handler calls it - with the Moves arriving unevenly (JITTER: some ticks none, the next
 * one the latest of two, as ServerQueueSVA keeps only the latest). A refused Move snaps the "client" back like a
 * Correct (at most every 5 ticks, as the server sends them).
 * - STALL (user report: «СВАЛИВАНИЕ» at 52 km/h, 3 blocks over a superflat): the light plane, the jet and the
 *   tiltrotor (airplane mode) cruise level a few blocks over the floor above their stall speed - the stall flag never
 *   comes on, no Move is refused; the old measure (the EMA of the per-tick displacement) is logged for comparison.
 *   A light plane slowed under its stall speed high up still gets the flag (and its nose drops).
 * - REVERSE (user report: «при езде назад откидывает»): the buggy (also on asphalt), the cargo crawler (also with a
 *   full tanker), the drill vehicle and the hoverbike drive forward, then hold S - braking, then reversing: no Move
 *   refused, the server's position never jumps forward while it reverses, the server and the "client" end together.
 */
final class WorldTestDriveSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5a5";
    static final int START = 560;
    private static final int Y = 150, LEN = 150, LANE = 8, LANES = 16;
    /** Ticks the driver's Move arrives in (false: none this tick - the next tick takes the latest of two). */
    private static final boolean[] JITTER = {true, true, false, true, true, false, true, true, true, false, true, false, true, true};
    // ground: settle, forward, hold S, let go
    private static final int G_SETTLE = 10, G_FWD = 30, G_BACK = 135, G_END = 150;
    // aircraft: warm-up (the measurement fills), cruise, stall phase (the slow plane), end
    private static final int A_WARM = 8, A_END = 70;

    /** One vehicle and the client the test plays for it. */
    private static final class Sim {
        final String name;
        final EntityVehicleSVA v;
        final boolean air;
        double x, y, z, my, spd, thr, startY;
        float yaw, fall;
        boolean og;
        int lastSnap = -100, refused0, snaps, stallTicks, oldStallTicks, jerks, reverseTicks;
        double minAir = Double.MAX_VALUE, maxFwdJump, backDist;
        boolean wantStall;

        Sim(String name, EntityVehicleSVA v, boolean air) {
            this.name = name;
            this.v = v;
            this.air = air;
            x = v.posX;
            y = v.posY;
            z = v.posZ;
            yaw = v.rotationYaw;
            og = v.onGround;
            startY = v.posY;
            refused0 = v.movesRefused();
        }
    }

    private final WorldTestSVA t;
    private final List<Sim> sims = new ArrayList<Sim>();
    private FakePlayer owner;
    private int phase, t0, x0, z0;
    boolean done;

    WorldTestDriveSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = sims.isEmpty() ? 9 : 1;
            return;
        }
        int n = ticks - t0;
        if (phase == 1) {
            boolean deliver = JITTER[n % JITTER.length];
            for (Sim s : sims) {
                if (s.v.isDead) {
                    continue;
                }
                step(s, n, deliver, ticks);
            }
            if (n >= Math.max(G_END, A_END)) {
                verify();
                phase = 9;
            }
        }
        if (phase == 9) {
            finish(w);
        }
    }

    // ---------------------------------------------------------------- the driver's client

    private int input(Sim s, int n) {
        if (s.air) {
            return 0;                                           // level flight: no keys (throttle as set)
        }
        if (n < G_SETTLE || n >= G_BACK) {
            return 0;
        }
        return n < G_FWD ? EntityVehicleSVA.IN_FWD : EntityVehicleSVA.IN_BACK;
    }

    private void step(Sim s, int n, boolean deliver, int ticks) {
        EntityVehicleSVA v = s.v;
        // seated again each tick: the fake player isn't in the world (no ridingEntity - vanilla's updateEntity drops the
        // link after the vehicle's own update, which has seen it)
        v.riddenByEntity = owner;
        int in = input(s, n);
        v.serverInput(in);
        // the client's physics on the client's state (the entity's own physicsStep, server state put aside)
        double sx = v.posX, sy = v.posY, sz = v.posZ, smy = v.motionY, sspd = v.speed, sthr = v.throttle;
        float syaw = v.rotationYaw, sfall = v.fallDistance;
        boolean sog = v.onGround;
        v.setPosition(s.x, s.y, s.z);
        v.rotationYaw = s.yaw;
        v.motionY = s.my;
        v.speed = s.spd;
        v.throttle = s.thr;
        v.onGround = s.og;
        v.fallDistance = s.fall;
        double before = s.x * -Math.sin(Math.toRadians(s.yaw)) + s.z * Math.cos(Math.toRadians(s.yaw));
        v.physicsStep(v.getVehicleType(), in);
        s.x = v.posX;
        s.y = v.posY;
        s.z = v.posZ;
        s.yaw = v.rotationYaw;
        s.my = v.motionY;
        s.spd = v.speed;
        s.thr = v.throttle;
        s.og = v.onGround;
        s.fall = v.fallDistance;
        v.setPosition(sx, sy, sz);
        v.rotationYaw = syaw;
        v.motionY = smy;
        v.speed = sspd;
        v.throttle = sthr;
        v.onGround = sog;
        v.fallDistance = sfall;
        if (deliver) {
            int r0 = v.movesRefused();
            double fx = v.posX, fy = v.posY, fz = v.posZ;
            v.serverMove(owner, s.x, s.y, s.z, s.yaw, (float) s.spd);
            if (v.movesRefused() > r0) {
                System.out.println("[SVA-WORLDTEST] drive " + s.name + ": Move refused at n=" + n + " (rule " + v.refuseWhy() + ") from "
                        + r3(fx) + " " + r3(fy) + " " + r3(fz) + " to " + r3(s.x) + " " + r3(s.y) + " " + r3(s.z) + ", client speed "
                        + r3(s.spd) + (deliver ? "" : ""));
            }
            if (v.movesRefused() > r0 && ticks - s.lastSnap >= 5) {
                // a Correct: the client snaps to the server (ground: stopped; aircraft keep the airspeed)
                s.lastSnap = ticks;
                s.snaps++;
                s.x = v.posX;
                s.y = v.posY;
                s.z = v.posZ;
                s.yaw = v.rotationYaw;
                s.my = 0;
                if (!v.getVehicleType().movement.flies) {
                    s.spd = 0;                                  // applyCorrection: aircraft keep their airspeed
                }
            }
        }
        measure(s, n, before);
    }

    /** The server's view, once a tick after the Move (the vehicle's own update of this tick has run already). */
    private void measure(Sim s, int n, double clientAlongBefore) {
        EntityVehicleSVA v = s.v;
        if (s.air) {
            if (n >= A_WARM) {
                if (v.isStalling()) {
                    s.stallTicks++;
                }
                VehicleType ty = v.getVehicleType();
                if (PlaneMath.stalled(ty.plane, Math.min(v.measuredSpeed(), ty.topSpeed * 1.2))) {
                    s.oldStallTicks++;                          // the old rule: the measured EMA
                }
                s.minAir = Math.min(s.minAir, v.airspeedNow());
            }
            return;
        }
        double r = Math.toRadians(v.rotationYaw);
        double along = v.posX * -Math.sin(r) + v.posZ * Math.cos(r);
        if (n > G_FWD && s.spd < -0.01) {
            s.reverseTicks++;
            double d = along - s.backDist;                       // the server's step along the facing this tick
            if (d > 0.02) {
                s.jerks++;
                s.maxFwdJump = Math.max(s.maxFwdJump, d);
            }
        }
        s.backDist = along;
    }

    private static double r3(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    private void verify() {
        for (Sim s : sims) {
            EntityVehicleSVA v = s.v;
            int refused = v.movesRefused() - s.refused0;
            double gap = Math.sqrt((v.posX - s.x) * (v.posX - s.x) + (v.posZ - s.z) * (v.posZ - s.z));
            System.out.println("[SVA-WORLDTEST] drive " + s.name + ": refused " + refused + ", snaps " + s.snaps + ", stall ticks "
                    + s.stallTicks + " (old EMA rule " + s.oldStallTicks + "), min airspeed " + (s.minAir == Double.MAX_VALUE ? "-"
                    : String.valueOf(Math.round(s.minAir * 1000) / 1000.0)) + ", reverse ticks " + s.reverseTicks + ", forward jerks "
                    + s.jerks + " (max " + Math.round(s.maxFwdJump * 1000) / 1000.0 + "), end gap " + Math.round(gap * 1000) / 1000.0
                    + ", speed " + Math.round(s.spd * 1000) / 1000.0 + ", Y " + Math.round(v.posY * 100) / 100.0);
            if (s.air && s.wantStall) {
                check(s.stallTicks >= 10 && s.y < s.startY - 1, "drive " + s.name + ": under the stall speed the flag is on ("
                        + s.stallTicks + " ticks) and the nose drops (Y " + Math.round(s.startY) + " -> " + Math.round(s.y * 10) / 10.0 + ")");
            } else if (s.air) {
                check(s.stallTicks == 0 && refused == 0, "drive " + s.name + ": cruising " + com.sva.vehicle.VehicleMath.kmh(s.spd)
                        + " km/h a few blocks up with uneven Moves - no stall flag (old EMA rule: " + s.oldStallTicks + " ticks), no Move refused ("
                        + refused + "), lowest airspeed judged " + Math.round(s.minAir * 1000) / 1000.0);
            } else {
                check(refused == 0 && s.jerks == 0 && s.reverseTicks > 20 && gap < 0.3, "drive " + s.name + ": forward, then S - "
                        + s.reverseTicks + " ticks reversing with uneven Moves: no Move refused (" + refused + "), no forward jerk ("
                        + s.jerks + "), server and client together (" + Math.round(gap * 100) / 100.0 + ")");
            }
        }
    }

    // ---------------------------------------------------------------- setup

    private EntityVehicleSVA place(World w, VehicleType type, double x, double y, double z) {
        VehicleState st = new VehicleState(type);
        st.energy = st.maxEnergy() * 0.8;
        if (type.gases.liftGas != null) {
            st.gas[type.gases.liftGas.ordinal()] = 2000;
        }
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, s, x, y, z, -90F, OWNER, "WorldTestDrive");     // facing +x
        if (v != null) {
            v.riddenByEntity = owner;                           // seated without mountEntity (a fake player has no connection)
        }
        return v;
    }

    private void ground(World w, String name, VehicleType type, int lane, boolean road, boolean trailer) {
        ground(w, name, type, lane, road, trailer, false, 0);
    }

    /**
     * A ground (or taxiing / hovering) vehicle in its lane: `step` - a 1-block platform behind it (x0 + 10 .. + 44) it
     * reverses up onto; `height` - placed that high (a helicopter hovering).
     */
    private void ground(World w, String name, VehicleType type, int lane, boolean road, boolean trailer, boolean step, double height) {
        int z = z0 + lane * LANE;
        if (step) {
            for (int x = x0 + 10; x <= x0 + 44; x++) {
                for (int dz = -3; dz <= 3; dz++) {
                    w.setBlock(x, Y + 1, z + dz, Blocks.stone, 0, 2);
                }
            }
        }
        if (road) {
            for (int x = x0; x < x0 + LEN; x++) {
                for (int dz = -2; dz <= 2; dz++) {
                    w.setBlock(x, Y, z + dz, ModContentSVA.road, 0, 2);    // asphalt
                }
            }
        }
        EntityVehicleSVA v = place(w, type, x0 + (step ? 48.5 : 40.5), Y + 1 + height, z + 0.5);
        if (v == null) {
            check(false, "drive " + name + ": placed");
            return;
        }
        if (trailer) {
            int r = v.attachTrailer(owner, new ItemStack(ModContentSVA.trailer, 1, TrailerMath.TANK));
            v.state.tankFluid = "water";
            v.state.tankAmount = TrailerMath.TANK_CAP;
            check(r == com.sva.vehicle.CrawlerMath.OK && v.hasTrailer(), "drive " + name + ": a full tanker hitched");
        }
        sims.add(new Sim(name, v, false));
    }

    private void aircraft(World w, String name, VehicleType type, int lane, double height, double speed, double thr, boolean stall) {
        int z = z0 + lane * LANE;
        EntityVehicleSVA v = place(w, type, x0 + 4.5, Y + 1 + height, z + 0.5);
        if (v == null) {
            check(false, "drive " + name + ": placed");
            return;
        }
        if (type == VehicleType.TILT) {
            v.state.tiltPlane = true;
            v.state.tiltStep = TiltMath.TRANSITION_TICKS;       // airplane mode
        }
        v.throttle = thr;
        Sim s = new Sim(name, v, true);
        s.spd = speed;
        s.thr = thr;
        s.og = false;
        s.wantStall = stall;
        sims.add(s);
    }

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 260;
        z0 = sp.posZ + 300;
        keepLoaded(w);
        area(w, true);
        owner = FakePlayerFactory.get((net.minecraft.world.WorldServer) w, new com.mojang.authlib.GameProfile(java.util.UUID.fromString(OWNER),
                "WorldTestDrive"));
        // the user's case: the light plane at 52-57 km/h (throttle 50 %: 0.79 b/t level), 3 blocks over the floor
        aircraft(w, "light plane", VehicleType.PLANE, 0, 3, VehicleType.PLANE.topSpeed * Math.sqrt(0.5), 0.5, false);
        aircraft(w, "jet", VehicleType.JET, 1, 4, VehicleType.JET.topSpeed * Math.sqrt(0.45), 0.45, false);
        aircraft(w, "tiltrotor (airplane)", VehicleType.TILT, 2, 4, VehicleType.TILT.topSpeed * Math.sqrt(0.4), 0.4, false);
        aircraft(w, "light plane slowed to 22 km/h", VehicleType.PLANE, 3, 40, 0.3, 0, true);
        ground(w, "buggy", VehicleType.BUGGY, 4, false, false);
        ground(w, "buggy on asphalt", VehicleType.BUGGY, 5, true, false);
        ground(w, "cargo crawler", VehicleType.TRUCK, 6, false, false);
        ground(w, "cargo crawler + full tanker", VehicleType.TRUCK, 7, false, true);
        ground(w, "drill vehicle", VehicleType.DRILL, 8, false, false);
        ground(w, "hoverbike", VehicleType.HOVER, 9, false, false);
        ground(w, "buggy reversing up a step", VehicleType.BUGGY, 10, false, false, true, 0);
        ground(w, "cargo crawler reversing up a step", VehicleType.TRUCK, 11, false, false, true, 0);
        ground(w, "helicopter hovering backwards", VehicleType.HELI, 12, false, false, false, 5);
        ground(w, "helicopter taxiing backwards", VehicleType.HELI, 13, false, false, false, 0);
        ground(w, "transport shuttle taxiing backwards", VehicleType.SHUTTLE, 14, false, false, false, 0);
        stepEdge(w, 15);
    }

    /**
     * The step-up rule itself, deterministic: a buggy whose rear is 0.01 short of a 1-block step reverses onto it and
     * lands with only 0.02 of the hull over the step (what a slow step-up leaves) - the Move is taken; rising 1 block
     * with nothing under the hull (well clear of the step) is still refused.
     */
    private void stepEdge(World w, int lane) {
        int z = z0 + lane * LANE;
        for (int x = x0 + 10; x <= x0 + 44; x++) {
            for (int dz = -3; dz <= 3; dz++) {
                w.setBlock(x, Y + 1, z + dz, Blocks.stone, 0, 2);
            }
        }
        double half = VehicleType.BUGGY.width / 2.0, edge = x0 + 45;
        EntityVehicleSVA v = place(w, VehicleType.BUGGY, edge + half + 0.01, Y + 1, z + 0.5);
        if (v == null) {
            check(false, "drive step edge: placed");
            return;
        }
        int r0 = v.movesRefused();
        v.serverMove(owner, edge + half - 0.02, Y + 2, z + 0.5, v.rotationYaw, -0.03F);
        boolean took = v.movesRefused() == r0 && Math.abs(v.posY - (Y + 2)) < 1e-6;
        check(took, "drive step edge: reversing onto a 1-block step with 0.02 of the hull over it is taken (rule " + v.refuseWhy()
                + ", Y " + r3(v.posY) + ")");
        EntityVehicleSVA u = place(w, VehicleType.BUGGY, x0 + 80.5, Y + 1, z + 0.5);
        if (u != null) {
            int u0 = u.movesRefused();
            u.serverMove(owner, x0 + 80.3, Y + 2, z + 0.5, u.rotationYaw, -0.2F);
            check(u.movesRefused() > u0 && u.refuseWhy() == 6 && Math.abs(u.posY - (Y + 1)) < 1e-6,
                    "drive step edge: rising a block with nothing under the hull is still refused (rule " + u.refuseWhy() + ")");
            u.riddenByEntity = null;
            u.setDead();
        }
        v.riddenByEntity = null;
        v.setDead();
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
            for (int z = z0 - 4; z <= z0 + LANES * LANE; z++) {
                for (int y = Y + 1; y <= Y + 6; y++) {
                    if (w.getBlock(x, y, z) != Blocks.air) {
                        w.setBlock(x, y, z, Blocks.air, 0, 2);
                    }
                }
                w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private void finish(World w) {
        for (Sim s : sims) {
            if (!s.v.isDead) {
                s.v.debugDrive(0);
                s.v.riddenByEntity = null;
                s.v.dismountAll();
                s.v.setDead();
            }
        }
        sims.clear();
        if (owner != null) {
            owner.ridingEntity = null;
        }
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
