package com.sva.debug;

import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Stage D5 world test (the INS, A320 ADIRS style), run by WorldTestSVA alongside its other phases from tick START in its
 * own area (x spawn + 2200 .. + 2290, z spawn + 2200 .. + 2260, stone lanes at Y 150 on air) with its own test owner.
 * Three light planes and a helicopter through the REAL alignment (debugIns(true); the default 10 s = 200 ticks):
 * - «align»: powered, ИНС on (a new one) - aligning, the autopilot refused (CruiseMath.OFF_INS) even with КУРС / МАРШ
 *   asked for; aligned after ~200 ticks standing still, the autopilot's INS block gone;
 * - «reset»: 5 s into its alignment ИНС off - INS_OFF; on again - the full 10 s; АКБ off - INS_FAULT (the alignment
 *   lost); АКБ on - aligning from the start again;
 * - «move»: rolled forward while aligning - the alignment starts over (the ВЫСТ. flash), still 10 s left;
 * - «heli»: the helicopter aligns too and its ИНС switch (the only switch it has) works.
 */
final class WorldTestAirD5SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5d5";
    static final int START = 1600;
    private static final int Y = 150, LEN = 90, LANE = 20, LANES = 4, MAX_TICKS = 330;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA align, reset, move, heli;
    private int readyAt = -1, heliReadyAt = -1;
    private boolean apRefused, earlyReady;
    boolean done;

    WorldTestAirD5SVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = align == null || reset == null || move == null || heli == null ? 9 : 1;
            if (phase == 1) {
                for (EntityVehicleSVA v : all()) {
                    v.debugIns(true);                               // the real alignment, not the tests' instant one
                }
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

    private EntityVehicleSVA[] all() {
        return new EntityVehicleSVA[] {align, reset, move, heli};
    }

    private void run(int n) {
        for (EntityVehicleSVA v : all()) {
            if (v.isDead) {
                check(false, "D5 world: a test aircraft was lost");
                phase = 9;
                return;
            }
        }
        int total = SystemsMath.alignTicks(com.sva.ConfigSVA.insAlignSeconds);
        // «align»
        if (align.insReady() && readyAt < 0) {
            readyAt = n;
        }
        if (heli.insReady() && heliReadyAt < 0) {
            heliReadyAt = n;
        }
        earlyReady |= n < total - 5 && align.insReady();
        if (n == 20) {
            align.apModes = AutopilotMath.M_HDG | AutopilotMath.M_ROUTE | AutopilotMath.M_ALT;
            apRefused = align.cruiseBlocked(EntityVehicleSVA.IN_CRUISE) == CruiseMath.OFF_INS;
            int left = align.insSecondsLeft();
            check(align.insStatus() == SystemsMath.INS_ALIGN && left >= total / 20 - 2 && left <= total / 20 && apRefused,
                    "D5 world: a powered plane with ИНС on aligns (" + left + " s left at tick 20); the autopilot (КУРС / МАРШ / ВЫС)"
                            + " is refused meanwhile - OFF_INS");
        }
        // «reset»: off at 100, on at 101; АКБ off at 140, on at 141
        if (n == 100) {
            boolean half = reset.insStatus() == SystemsMath.INS_ALIGN && reset.insSecondsLeft() < total / 20;
            reset.debugSwitch(SystemsMath.SW_INS, false);
            check(half, "D5 world: halfway through - " + reset.insSecondsLeft() + " s left");
        }
        if (n == 101) {
            boolean off = reset.insStatus() == SystemsMath.INS_OFF && !reset.switchOn(SystemsMath.SW_INS);
            reset.debugSwitch(SystemsMath.SW_INS, true);
            check(off, "D5 world: ИНС switched off - INS_OFF, the alignment dropped");
        }
        if (n == 103) {
            check(reset.insStatus() == SystemsMath.INS_ALIGN && reset.insSecondsLeft() == total / 20,
                    "D5 world: ИНС on again - the full alignment from the start (" + reset.insSecondsLeft() + " s)");
        }
        if (n == 140) {
            reset.debugSwitch(SystemsMath.SW_BAT, false);
        }
        if (n == 141) {
            boolean fault = reset.insStatus() == SystemsMath.INS_FAULT && !reset.powered();
            reset.debugSwitch(SystemsMath.SW_BAT, true);
            check(fault, "D5 world: АКБ off - the INS has no power (INS_FAULT), the alignment lost");
        }
        if (n == 143) {
            check(reset.insStatus() == SystemsMath.INS_ALIGN && reset.insSecondsLeft() == total / 20,
                    "D5 world: the board powered again - the alignment starts over (" + reset.insSecondsLeft() + " s)");
        }
        // «move»: rolled forward from tick 60 to 120 (full throttle from rest: over 1 block/s within ~2 s)
        if (n == 60) {
            move.debugDrive(EntityVehicleSVA.IN_FWD);
        }
        if (n == 120) {
            boolean restarted = move.insStatus() == SystemsMath.INS_ALIGN && move.insSecondsLeft() == total / 20 && move.insRestarted();
            double sp = move.speed;
            move.debugDrive(0);
            check(restarted && sp > SystemsMath.ALIGN_SPEED,
                    "D5 world: moving while aligning (" + Math.round(sp * 20 * 10) / 10.0 + " blocks/s) restarts it - the ВЫСТ. flash, "
                            + move.insSecondsLeft() + " s left");
        }
        if (n == 30) {
            check(heli.insApplies() && heli.insStatus() == SystemsMath.INS_ALIGN && heli.switchOn(SystemsMath.SW_INS)
                            && heli.switchOn(SystemsMath.SW_NAV),
                    "D5 world: the helicopter has the INS too - aligning; its display-only switches stay on");
        }
        if (readyAt >= 0 && heliReadyAt >= 0 && n >= Math.max(readyAt, heliReadyAt) + 2 || n >= MAX_TICKS) {
            check(readyAt >= total - 2 && readyAt <= total + 6 && !earlyReady,
                    "D5 world: aligned after " + readyAt + " ticks standing still (" + total + " = vehicles.insAlignSeconds x 20)");
            int why = align.cruiseBlocked(EntityVehicleSVA.IN_CRUISE);
            check(align.insReady() && why != CruiseMath.OFF_INS,
                    "D5 world: aligned - the autopilot is no longer blocked by the INS (now: " + why + ")");
            check(heliReadyAt >= total - 2 && heliReadyAt <= total + 6, "D5 world: the helicopter aligned after " + heliReadyAt + " ticks");
            heli.debugSwitch(SystemsMath.SW_INS, false);
            check(!heli.switchOn(SystemsMath.SW_INS), "D5 world: the helicopter's ИНС switch turns it off");
            phase = 9;
        }
    }

    // ---------------------------------------------------------------- setup

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 2200;
        z0 = sp.posZ + 2200;
        keepLoaded(w);
        area(w, true);
        align = spawn(w, VehicleType.PLANE, 0, "align");
        reset = spawn(w, VehicleType.PLANE, 1, "reset");
        move = spawn(w, VehicleType.PLANE, 2, "move");
        heli = spawn(w, VehicleType.HELI, 3, "heli");
        check(align != null && reset != null && move != null && heli != null && align.switchOn(SystemsMath.SW_INS),
                "D5 world: three light planes and a helicopter placed - a new one has its ИНС on");
    }

    private EntityVehicleSVA spawn(World w, VehicleType type, int lane, String name) {
        VehicleState st = new VehicleState(type);
        st.energy = st.maxEnergy() * 0.9;
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x0 + 10.5, Y + 1, z0 + lane * LANE + 0.5, -90F, OWNER, "WorldTestAirD5" + name);
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 48) >> 4; cx <= (x0 + LEN + 48) >> 4; cx++) {
            for (int cz = (z0 - 48) >> 4; cz <= (z0 + (LANES - 1) * LANE + 48) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    /** Stone lanes (9 wide, LEN long, heading east - yaw -90) at Y; build false: air again. */
    private void area(World w, boolean build) {
        for (int lane = 0; lane < LANES; lane++) {
            for (int x = x0; x < x0 + LEN; x++) {
                for (int z = z0 + lane * LANE - 4; z <= z0 + lane * LANE + 4; z++) {
                    w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
                }
            }
        }
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : all()) {
            if (v != null && !v.isDead) {
                v.debugDrive(0);
                v.dismountAll();
                v.setDead();
            }
        }
        align = reset = move = heli = null;
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
