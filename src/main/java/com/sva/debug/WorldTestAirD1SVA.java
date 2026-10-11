package com.sva.debug;

import com.sva.block.AirfieldSVA;
import com.sva.block.BlockRunwaySVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.AirfieldMath;
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
 * Aircraft round 2, stage D1 world test (★1 PAPI, ★2 the threshold designator / approach lights), run by WorldTestSVA
 * alongside its other phases from tick START in its own area (x spawn + 760 .. + 1060, z spawn + 760 +- 40, a runway at
 * Y 150 on air) with its own test owner:
 * - a 120 x 5 strip of runway blocks (lit edges), four «Огни ПАПИ» (3 deg) 15 blocks in on its left, two approach poles
 *   before its west threshold (one with a redstone block beside it, one with a lever that is off);
 * - the strip found from the world with its PAPI row (AirfieldSVA.near / the vehicle's runway()), its designators «09» /
 *   «27», the poles' threshold distance and their redstone states (AirfieldSVA.manual);
 * - ЗАХВ: a light plane 150 blocks before the threshold, 6 off the centre line at 10 deg, 7 blocks up (ЗАХВ + ВЫС + СКОР
 *   65 km/h; the server flies it as without a player) - it aligns, catches the PAPI slope (ВПП -> ГЛИС), holds it (2 white
 *   + 2 red), flares (ВЫРАВН.) and touches down on the strip, where the autopilot lets go (CruiseMath.OFF_GROUND).
 */
final class WorldTestAirD1SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-00000000a5d1";
    static final int START = 660;
    private static final int Y = 150, RW_X = 160, RW_LEN = 120, RW_W = 5, PAPI_IN = 15, MAX_TICKS = 900;
    private static final int IN = EntityVehicleSVA.IN_CRUISE;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA cap;
    private int lastPhase = -1, glideTicks, onSlope, glideAt = -1, flareAt = -1, touchAt = -1;
    private boolean order = true, letGo;
    private double maxErr, flareH = Double.NaN, touchAlong = Double.NaN;
    boolean done;

    WorldTestAirD1SVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = cap == null ? 9 : 1;
            if (phase == 1) {
                statics(w);
                start();
            }
            return;
        }
        int n = ticks - t0;
        if (phase == 1) {
            fly(n);
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private void fly(int n) {
        if (cap.isDead) {
            check(false, "D1 world: ЗАХВ + PAPI - the plane was lost");
            phase = 9;
            return;
        }
        AutopilotMath.Capture c = cap.capture();
        int ph = cap.apPanel().phase;
        if (n % 50 == 0) {
            // the flight's trace in the log (along / over the strip, heading, speed, engaged, the phase and the PAPI seen)
            RunwayMath.Runway r = cap.runway();
            System.out.println(String.format(java.util.Locale.ROOT, "[SVA-WORLDTEST] D1 trace t %d along %.1f h %.2f z %.1f hdg %.0f %.0f km/h ap %b"
                    + " block %d rwy %b papi %b phase %d aligned %b whites %d", n, cap.posX - (x0 + RW_X), cap.posY - (Y + 1), cap.posZ - z0,
                    (double) CruiseMath.heading(cap.rotationYaw), (double) com.sva.vehicle.VehicleMath.kmh(cap.speed), cap.isCruiseOn(),
                    cap.cruiseBlocked(IN), r != null, r != null && r.papi(1) != null, ph, c != null && c.aligned, c == null ? -9 : c.whites));
        }
        if (c != null && touchAt < 0) {
            if (ph != lastPhase) {
                order &= ph > lastPhase;
                lastPhase = ph;
                if (ph == AutopilotMath.PH_GLIDE && glideAt < 0) {
                    glideAt = n;
                }
                if (ph == AutopilotMath.PH_FLARE && flareAt < 0) {
                    flareAt = n;
                    flareH = cap.posY - (Y + 1);
                }
            }
            if (ph == AutopilotMath.PH_GLIDE && glideAt >= 0 && n - glideAt > 10 && c.papi != null && c.whites >= 0) {
                glideTicks++;
                onSlope += c.whites == 2 ? 1 : 0;
                maxErr = Math.max(maxErr, Math.abs(c.papiAngle - c.papi.nominal));
            }
        }
        if (touchAt < 0 && cap.onGround) {
            touchAt = n;
            touchAlong = cap.posX - (x0 + RW_X);
        }
        if (touchAt >= 0 && !letGo && cap.cruiseBlocked(IN) == CruiseMath.OFF_GROUND && !cap.isCruiseOn()) {
            letGo = true;
        }
        if (touchAt >= 0 && n - touchAt > 10 || n >= MAX_TICKS) {
            check(order && glideAt > 0 && flareAt > glideAt && glideTicks >= 10 && onSlope >= glideTicks * 0.85 && maxErr < 0.4,
                    "D1 world: ЗАХВ + PAPI - ВПП -> ГЛИС (tick " + glideAt + ") -> ВЫРАВН. (tick " + flareAt + "), on the PAPI slope " + onSlope + " of "
                            + glideTicks + " ticks (2 white + 2 red), the angle within " + Math.round(maxErr * 100) / 100.0 + " deg");
            check(touchAt > 0 && flareH < AutopilotMath.FLARE_START && touchAlong > 0 && touchAlong < RW_LEN && letGo,
                    "D1 world: ЗАХВ + PAPI - flares " + Math.round(flareH * 100) / 100.0 + " blocks over the strip, touches down "
                            + Math.round(touchAlong * 10) / 10.0 + " blocks past the threshold (tick " + touchAt + "), the autopilot lets go (" + letGo + ")");
            cap.debugDrive(0);
            phase = 9;
        }
    }

    /** The strip, its PAPI row and designators, the approach poles found from the world. */
    private void statics(World w) {
        RunwayMath.Runway r = AirfieldSVA.near(w, x0 + RW_X - 20.5, z0 + 0.5, RunwayMath.SCAN);
        AirfieldMath.Papi p = r == null ? null : r.papi(1);
        check(r != null && r.alongX && (int) r.length() == RW_LEN && r.width == RW_W && p != null && p.units == 4 && p.nominal == 3
                        && Math.abs(p.fromThreshold(r) - (PAPI_IN + 0.5)) < 1e-9 && r.papi(-1) == null,
                "D1 world: the runway finder (AirfieldSVA.near, as the vehicles' runway()) finds the " + RW_LEN + " x " + RW_W + " strip and its 4-unit 3 deg PAPI row " + PAPI_IN
                        + " blocks in by the west threshold (" + (p == null ? "none" : p.units + " units, " + p.nominal + " deg") + ")");
        check(r != null && "09".equals(AirfieldMath.designator(r, 1)) && "27".equals(AirfieldMath.designator(r, -1)),
                "D1 world: the placed strip's designators - «09» at its west threshold, «27» at its east one");
        RunwayMath.Runway near = AirfieldSVA.near(w, x0 + RW_X - 6.5, z0 + 0.5, AirfieldMath.APPROACH_MAX);
        double[] ap = near == null ? null : AirfieldMath.approach(near, x0 + RW_X - 6 + 0.5, z0 + 0.5);
        int on = AirfieldSVA.manual(w, x0 + RW_X - 6, Y + 1, z0), off = AirfieldSVA.manual(w, x0 + RW_X - 10, Y + 1, z0),
                auto = AirfieldSVA.manual(w, x0 + RW_X - 14, Y + 1, z0);
        check(ap != null && ap[0] == 1 && Math.abs(ap[1] - 5.5) < 1e-9 && on == AirfieldMath.ON && off == AirfieldMath.OFF && auto == AirfieldMath.AUTO
                        && w.getBlock(x0 + RW_X - 6, Y + 1, z0) == ModContentSVA.approachLight,
                "D1 world: an approach pole 5.5 blocks before the west threshold leads into it; a redstone block beside one turns it on, a lever"
                        + " that is off turns another off, a pole without redstone runs by the time of day");
    }

    private void start() {
        // heading 80 (MC yaw -100), 6 blocks right of the centre line, 7 up; ЗАХВ + ВЫС (the height until the slope) + СКОР
        cap.speed = 0.95;
        cap.throttle = 0.5;
        cap.rotationYaw = cap.prevRotationYaw = -100;
        cap.setCruiseTargets(CruiseMath.fromKmh(65), -100, cap.posY);
        cap.setApModes(AutopilotMath.M_CAP | AutopilotMath.M_ALT | AutopilotMath.M_SPD, 0);
        cap.debugDrive(IN);
    }

    // ---------------------------------------------------------------- setup

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 760;
        z0 = sp.posZ + 760;
        keepLoaded(w);
        area(w, true);
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.energy = st.maxEnergy() * 0.9;
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st);
        // the threshold at x0 + RW_X; the plane 150 before it, 6 to the right (+z when landing east), 7 over the surface (the strip is found 96 out - a
        // 3 deg slope is 5.5 up there)
        cap = ItemVehicleSVA.spawn(w, s, x0 + RW_X - 150 + 0.5, Y + 1 + 7, z0 + 6.5, -100F, OWNER, "WorldTestAirD1");
        check(cap != null && ModContentSVA.papi != null && ModContentSVA.approachLight != null, "D1 world: a light plane, a runway with PAPI and approach lights placed");
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 32) >> 4; cx <= (x0 + RW_X + RW_LEN + 64) >> 4; cx++) {
            for (int cz = (z0 - 40) >> 4; cz <= (z0 + 40) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    /** The strip (lit edges), the PAPI row on its left 15 in, three approach poles (redstone block / lever off / none). */
    private void area(World w, boolean build) {
        for (int x = x0 + RW_X; x < x0 + RW_X + RW_LEN; x++) {
            for (int z = z0 - RW_W / 2; z <= z0 + RW_W / 2; z++) {
                int meta = BlockRunwaySVA.AXIS_X | (Math.abs(z - z0) == RW_W / 2 ? BlockRunwaySVA.LIT : 0);
                w.setBlock(x, Y, z, build && ModContentSVA.runway != null ? ModContentSVA.runway : Blocks.air, meta, 2);
            }
        }
        for (int z = z0 - RW_W / 2 - 2; z >= z0 - RW_W / 2 - 5; z--) {
            w.setBlock(x0 + RW_X + PAPI_IN, Y + 1, z, build && ModContentSVA.papi != null ? ModContentSVA.papi : Blocks.air, 0, 2);
        }
        for (int i = 0; i < 3; i++) {
            int x = x0 + RW_X - 6 - 4 * i;
            w.setBlock(x, Y, z0, build ? Blocks.stone : Blocks.air, 0, 2);
            w.setBlock(x, Y, z0 + 1, build ? Blocks.stone : Blocks.air, 0, 2);
            w.setBlock(x, Y + 1, z0, build && ModContentSVA.approachLight != null ? ModContentSVA.approachLight : Blocks.air, 0, 2);
        }
        w.setBlock(x0 + RW_X - 6, Y + 1, z0 + 1, build ? Blocks.redstone_block : Blocks.air, 0, 3);
        w.setBlock(x0 + RW_X - 10, Y + 1, z0 + 1, build ? Blocks.lever : Blocks.air, build ? 5 : 0, 3);
    }

    private void finish(World w) {
        if (cap != null && !cap.isDead) {
            cap.debugDrive(0);
            cap.dismountAll();
            cap.setDead();
        }
        cap = null;
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
