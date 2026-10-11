package com.sva.debug;

import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleLights;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Aircraft round 2, stage D2 world test (★6 the engine start, ★17 the overhead switches, ★7 failures), run by
 * WorldTestSVA alongside its other phases from tick START in its own area (x spawn + 1200 .. + 1300, z spawn + 1200 ..
 * + 1270, four stone lanes at Y 150 on air) with its own test owner. Four light planes, 20 blocks apart:
 * - «start»: through the real sequence (debugEngineManual): pushing forward with the engine off starts it by itself -
 *   the starter (CRANK, no thrust, the plane stands), the catch at 1.5 s (WARM), RUN at 4.5 s; then it rolls;
 * - «dark»: АКБ off - pushing forward does nothing (OFF, no lift, it stands), «ЗАПУСК» answers R_NO_MASTER; its nav
 *   lights switched off are off (lightOn), the landing lights' switch follows ПОС. ФАРЫ;
 * - «sound» / «failed»: both running at once (the world tests' instant engine), the second with an engine failure
 *   injected - full throttle from rest for 50 ticks: the failed one is clearly slower (power x0.6), then the garage's
 *   repair (garageRepaired, what TileEntityGarageSVA calls) clears the failure - power 1 again.
 */
final class WorldTestAirD2SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5d2";
    static final int START = 700;
    private static final int Y = 150, LEN = 90, LANE = 20, MAX_TICKS = 260;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA start, dark, sound, failed;
    private int crankAt = -1, warmAt = -1, runAt = -1;
    private boolean stoodCranking = true, darkStill = true;
    private double crankX, crankMove;
    private double darkX;
    boolean done;

    WorldTestAirD2SVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = start == null || dark == null || sound == null || failed == null ? 9 : 1;
            if (phase == 1) {
                begin();
            }
            return;
        }
        int n = ticks - t0;
        if (phase == 1) {
            run(n);
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void begin() {
        start.debugEngineManual = true;
        start.debugDrive(EntityVehicleSVA.IN_FWD);                  // the engine off: pushing forward starts it
        dark.debugEngineManual = true;
        dark.debugSwitch(SystemsMath.SW_BAT, false);
        dark.debugSwitch(SystemsMath.SW_NAV, false);
        dark.debugDrive(EntityVehicleSVA.IN_FWD);
        darkX = dark.posX;
        failed.debugFail(SystemsMath.F_ENGINE);
        sound.debugDrive(EntityVehicleSVA.IN_FWD);
        failed.debugDrive(EntityVehicleSVA.IN_FWD);
    }

    private void run(int n) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {start, dark, sound, failed}) {
            if (v.isDead) {
                check(false, "D2 world: a test plane was lost");
                phase = 9;
                return;
            }
        }
        // «start»: the states' ticks
        int es = start.engineState();
        if (es == SystemsMath.CRANK && crankAt < 0) {
            crankAt = n;
        }
        if (es == SystemsMath.WARM && warmAt < 0) {
            warmAt = n;
        }
        if (es == SystemsMath.RUN && runAt < 0) {
            runAt = n;
        }
        if (es == SystemsMath.CRANK) {
            // no lift while the starter turns; it doesn't roll forward (from 5 ticks in - the spawn's first tick is a drop
            // onto the lane, which the unpowered physics takes as a glide's nudge)
            stoodCranking &= !start.isLifting();
            if (crankAt >= 0 && n == crankAt + 5) {
                crankX = start.posX;
            } else if (crankAt >= 0 && n > crankAt + 5) {
                crankMove = Math.max(crankMove, start.posX - crankX);
            }
        }
        // «dark»
        darkStill &= dark.engineState() == SystemsMath.OFF && !dark.isLifting() && Math.abs(dark.posX - darkX) < 0.05;
        if (n == 40) {
            int r = dark.debugStart();
            check(r == SystemsMath.R_NO_MASTER && dark.engineState() == SystemsMath.OFF && !dark.powered() && !dark.boardOn(),
                    "D2 world: АКБ off - «ЗАПУСК» answers R_NO_MASTER (" + r + "), the engine stays off, the board dark");
            check(!dark.lightOn(VehicleLights.RED) && !dark.lightOn(VehicleLights.GREEN) && dark.lightOn(VehicleLights.STROBE)
                            && dark.landingLampSwitch() && sound.lightOn(VehicleLights.RED),
                    "D2 world: НАВ. ОГНИ off - the red / green nav lights off, the strobe still on its own switch; ПОС. ФАРЫ on");
        }
        if (n == 50) {
            // full throttle from rest for 50 ticks: the failed engine (power x0.6) is clearly behind
            double vs = sound.speed, vf = failed.speed;
            check(Math.abs(sound.enginePower() - 1) < 1e-9 && Math.abs(failed.enginePower() - SystemsMath.ENGINE_POWER) < 1e-9 && vs > 0.05
                            && vf > 0 && vf < vs * 0.85,
                    String.format(java.util.Locale.ROOT, "D2 world: an engine failure - power x%.1f, %.0f km/h after 50 ticks against the sound engine's %.0f",
                            failed.enginePower(), vf * 72, vs * 72));
            sound.debugDrive(0);
            failed.debugDrive(0);
            failed.garageRepaired(false);
            check(failed.failures() == 0 && Math.abs(failed.enginePower() - 1) < 1e-9 && !failed.caution(),
                    "D2 world: the garage's repair clears the failure (power 1 again, no «ВНИМАНИЕ»)");
        }
        if (runAt >= 0 && n >= runAt + 20 || n >= MAX_TICKS) {
            int crank = SystemsMath.crankTicks(VehicleType.PLANE, false), warm = SystemsMath.warmTicks(VehicleType.PLANE);
            check(crankAt >= 0 && crankAt <= 2 && warmAt - crankAt >= crank - 1 && warmAt - crankAt <= crank + 2 && runAt - warmAt >= warm - 1
                            && runAt - warmAt <= warm + 2 && stoodCranking && crankMove < 0.1,
                    "D2 world: pushing forward with the engine off starts it - the starter at tick " + crankAt + " (the plane stands, no lift), it"
                            + " catches at " + warmAt + ", runs at " + runAt + " (" + crank + " + " + warm + " ticks; rolled "
                            + Math.round(crankMove * 1000) / 1000.0 + " blocks while cranking)");
            check(runAt >= 0 && start.isLifting() && start.speed > 0.02,
                    "D2 world: running, the plane rolls (" + Math.round(start.speed * 72) + " km/h 20 ticks later)");
            check(darkStill, "D2 world: АКБ off - pushing forward does nothing (the engine off, no lift, the plane stands)");
            phase = 9;
        }
    }

    // ---------------------------------------------------------------- setup

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 1200;
        z0 = sp.posZ + 1200;
        keepLoaded(w);
        area(w, true);
        start = plane(w, 0, "start");
        dark = plane(w, 1, "dark");
        sound = plane(w, 2, "sound");
        failed = plane(w, 3, "failed");
        check(start != null && dark != null && sound != null && failed != null && start.engineState() == SystemsMath.OFF
                        && start.switches() == SystemsMath.ALL_ON,
                "D2 world: four light planes placed - a new one has every switch on and its engine off");
    }

    private EntityVehicleSVA plane(World w, int lane, String name) {
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy() * 0.9;
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x0 + 10.5, Y + 1, z0 + lane * LANE + 0.5, -90F, OWNER, "WorldTestAirD2" + name);
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        // 48 blocks round the lanes: an entity is updated only with the chunks 32 blocks round it loaded
        for (int cx = (x0 - 48) >> 4; cx <= (x0 + LEN + 48) >> 4; cx++) {
            for (int cz = (z0 - 48) >> 4; cz <= (z0 + 3 * LANE + 48) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    /** Four stone lanes (7 wide, LEN long, heading east - yaw -90) at Y; build false: air again. */
    private void area(World w, boolean build) {
        for (int lane = 0; lane < 4; lane++) {
            for (int x = x0; x < x0 + LEN; x++) {
                for (int z = z0 + lane * LANE - 3; z <= z0 + lane * LANE + 3; z++) {
                    w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
                }
            }
        }
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {start, dark, sound, failed}) {
            if (v != null && !v.isDead) {
                v.debugDrive(0);
                v.dismountAll();
                v.setDead();
            }
        }
        start = dark = sound = failed = null;
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
