package com.sva.debug;

import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerIce;
import com.sva.vehicle.LinerLights;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Liner wave 6 world test (the SA-320's lights, anti-ice and icing on the real entity), from tick START on a stone pad in
 * its own area (x spawn - 2600, z spawn - 4800) with its own owner; six liners side by side:
 * <ul>
 * <li>«lit»: engines running - НАВ, МАЯК, СТРОБ ВКЛ, ПОСАД. Л ВКЛ, НОС РУЛЁЖ set through the systems' control path: the lights'
 * bits in the very string the clients get (DW_SPACE), the landing light out of its well;</li>
 * <li>«nobleed»: batteries only - WING A.ICE refused (its valves shut, ОТКАЗ, «ПОС КРЫЛА ОТКАЗ»);</li>
 * <li>«test»: engines running - WING A.ICE on: its ground test, the valves shut by themselves after ~30 s;</li>
 * <li>«ice»: «in the air» (the systems' debug flag) in cloud at a cold biome's -8 C, on the batteries (the generators off - the
 * probe heat unpowered): the probes ice - «НЕДОСТ. СКОРОСТЬ», «ОБОГРЕВ ПВД ОТКАЗ»;</li>
 * <li>«heat»: the same air with the generators on - the probe heat keeps the probes clear (the wing ices: «ЛЁД ОБНАРУЖЕН»);</li>
 * <li>«gen»: «in the air» on one generator - the galley shed, the cabin lights a step dimmer.</li>
 * </ul>
 */
final class WorldTestLinerW6SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-0000001a3206";
    static final int START = 1900;
    private static final int Y = 150, END = 760;
    private static final int PILOT = EntityVehicleSVA.IN_CRUISE;
    /** A biome temperature giving -8 C at Y 170 (LinerIce.oat). */
    private static final double COLD_BIOME = 0.8 + (LinerIce.ICING_PEAK - LinerIce.SEA_T + (170 - LinerIce.SEA_Y) * LinerIce.LAPSE) / LinerIce.BIOME_K;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA lit, nobleed, test, ice, heat, gen;
    private int testOff = -1;
    boolean done;

    WorldTestLinerW6SVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = lit == null || nobleed == null || test == null || ice == null || heat == null || gen == null ? 9 : 1;
            return;
        }
        int n = ticks - t0;
        keepLoaded(w);
        if (phase == 1) {
            run(n);
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private EntityVehicleSVA[] all() {
        return new EntityVehicleSVA[] {lit, nobleed, test, ice, heat, gen};
    }

    private static LinerSystems L(EntityVehicleSVA v) {
        return v.state.liner();
    }

    /** A control set through the systems' click path (A_SET + the position), as the pilot's packet does. */
    private static void set(EntityVehicleSVA v, LinerControls.Ctl c, int p) {
        L(v).control(c.index, LinerControls.A_SET + p);
    }

    private static boolean has(long bits, int id) {
        return (bits & 1L << id) != 0;
    }

    private void run(int n) {
        for (EntityVehicleSVA v : all()) {
            if (v.isDead) {
                check(false, "liner W6 world: a test liner was lost");
                phase = 9;
                return;
            }
        }
        if (n == 40) {
            set(lit, LinerControls.NAV_LOGO, 0);
            set(lit, LinerControls.BEACON, 0);
            set(lit, LinerControls.STROBE, 0);
            set(lit, LinerControls.LAND_L, 0);
            set(lit, LinerControls.NOSE_LT, 1);
            set(nobleed, LinerControls.AI_WING, 1);
            set(test, LinerControls.AI_WING, 1);
        }
        if (n == 60) {
            check(L(test).ice.wingOn && L(test).ice.wingTest > 0, "liner W6 world «test»: WING A.ICE on the ground with the engines' bleed - its test runs");
        }
        if (n == 160) {
            LinerSystems mirror = new LinerSystems();
            mirror.unpack(lit.getSpaceInfo());
            int want = LinerLights.X_NAV | LinerLights.X_LOGO | LinerLights.X_BEACON | LinerLights.X_STROBE | LinerLights.X_LAND_L | LinerLights.X_TAXI;
            check((mirror.lights.ext & want) == want && (mirror.lights.ext & LinerLights.X_LAND_R) == 0 && mirror.lights.landL == 1 && mirror.lights.landR == 0
                            && mirror.lights.ext == L(lit).lights.ext,
                    "liner W6 world «lit»: НАВ / ЛОГО / МАЯК / СТРОБ / ПОСАД. Л (out of its well) / НОС РУЛЁЖ in the synced string the clients get"
                            + " (ext " + mirror.lights.ext + ")");
            LinerSystems nb = L(nobleed);
            check(!nb.ice.wingOn && nb.ice.wingFault && nb.faultLamp(LinerControls.AI_WING) && has(nb.ecam.active, LinerEcam.M_WING_AI)
                    && nb.on(LinerControls.AI_WING), "liner W6 world «nobleed»: WING A.ICE without bleed refused - its valves shut, ОТКАЗ, «ПОС КРЫЛА ОТКАЗ»");
            LinerSystems g = L(gen);
            check(g.galleyShed && (g.memos(false) & LinerSystems.M_GALLEY) != 0 && LinerLights.cabinStep(g, g.acPowered(), g.galleyShed) == 2
                    && g.gen1On && !g.gen2On, "liner W6 world «gen»: one generator in the air - the galley shed («КУХНЯ ОТКЛ»), the cabin lights a step"
                    + " dimmer");
        }
        if (testOff < 0 && n > 60 && !L(test).ice.wingOn) {
            testOff = n;
        }
        if (n == 560) {
            LinerSystems a = L(ice), b = L(heat);
            check(a.ice.unreliable() && has(a.ecam.active, LinerEcam.M_IAS) && has(a.ecam.active, LinerEcam.M_PROBE_HEAT) && !a.ice.probeHeat
                            && a.ice.oatNow < -5 && a.ice.wet,
                    "liner W6 world «ice»: in cloud at " + Math.round(a.ice.oatNow) + " C with the probe heat unpowered - the probes iced,"
                            + " «НЕДОСТ. СКОРОСТЬ», «ОБОГРЕВ ПВД ОТКАЗ»");
            check(b.ice.probeHeat && b.ice.probe[0] == 0 && b.ice.probe[1] == 0 && !b.ice.unreliable() && b.ice.wing > LinerIce.DETECT
                    && has(b.ecam.active, LinerEcam.M_ICE), "liner W6 world «heat»: the probe heat on - the probes clear; the wing ices («ЛЁД"
                    + " ОБНАРУЖЕН», " + Math.round(b.ice.wing * 100) + " %)");
        }
        if (n >= END) {
            check(testOff > 600 && testOff < 720 && !L(test).ice.wingOn && !L(test).ice.wingFault,
                    "liner W6 world «test»: the ground test ended by itself after " + (testOff - 40) / 20 + " s (valves shut, no fault)");
            phase = 9;
        }
    }

    // ---------------------------------------------------------------- setup

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX - 2600;
        z0 = sp.posZ - 4800;
        keepLoaded(w);
        pad(w, true);
        lit = liner(w, 0, true, false);
        nobleed = liner(w, 1, false, false);
        test = liner(w, 2, true, false);
        ice = liner(w, 3, true, true);
        heat = liner(w, 4, true, true);
        gen = liner(w, 5, true, false);
        if (nobleed != null) {
            L(nobleed).set(LinerControls.BAT1, 1);
            L(nobleed).set(LinerControls.BAT2, 1);
        }
        if (ice != null) {
            L(ice).set(LinerControls.GEN1, 0);                     // on the batteries: the probe heat unpowered
            L(ice).set(LinerControls.GEN2, 0);
        }
        if (gen != null) {
            gen.debugLinerAir = true;
            L(gen).set(LinerControls.GEN2, 0);
        }
        check(lit != null && nobleed != null && test != null && ice != null && heat != null && gen != null,
                "liner W6 world: six liners on the pad (lights, no bleed, the ground test, icing without / with the probe heat, one generator)");
    }

    private void pad(World w, boolean on) {
        for (int x = x0 - 10; x < x0 + 30; x++) {
            for (int z = z0 - 10; z <= z0 + 6 * 26 + 10; z++) {
                w.setBlock(x, Y, z, on ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    /** A liner on the pad, lane i: engines (the instant start) or cold; `icing` - in the air in a cold cloud for its systems. */
    private EntityVehicleSVA liner(World w, int i, boolean engines, boolean icing) {
        VehicleState st = new VehicleState(VehicleType.LINER);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, s, x0 + 0.5, Y + 1, z0 + i * 26 + 0.5, -90F, OWNER, "WorldTestLinerW6");
        if (v == null) {
            return null;
        }
        if (engines) {
            v.debugDrive(PILOT);
        } else {
            v.debugEngineManual = true;
        }
        if (icing) {
            v.debugLinerAir = true;
            v.debugLinerIcing = true;
            v.debugLinerWet = true;
            v.debugLinerAirY = 170;
            v.debugLinerOat = COLD_BIOME;
        }
        return v;
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        // the entities update only with the chunks 32 blocks round them loaded (World.updateEntityWithOptionalForce)
        for (int cx = (x0 - 48) >> 4; cx <= (x0 + 56) >> 4; cx++) {
            for (int cz = (z0 - 48) >> 4; cz <= (z0 + 6 * 26 + 48) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
    }

    private void finish(World w) {
        for (EntityVehicleSVA v : all()) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        lit = nobleed = test = ice = heat = gen = null;
        if (x0 != 0 || z0 != 0) {
            pad(w, false);
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
