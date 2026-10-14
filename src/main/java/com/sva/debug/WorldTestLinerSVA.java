package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sva.block.TileEntityChargerSVA;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.entity.LinerLogicSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Liner wave 1 world test (the SA-320), run by WorldTestSVA alongside its other phases from tick START in its own area
 * (x spawn + 2400 .., z spawn + 2400 .., stone strips at Y 150 on air) with its own test owner. Five liners, each through
 * the real systems on the server (debugEngineManual - not the older tests' instant engines), the controls clicked as the
 * pilot's packets would (LinerSystems.control):
 * <ul>
 * <li>«seq»: cold and dark - АКБ 1 + 2 (the essentials) - ВСУ ГЛАВН. + ЗАПУСК - AVAIL in ~15 s - ОТБОР ВСУ, packs off,
 * РЕЖИМ ДВ ЗАПУСК - ДВ 2 MASTER: idle in ~10 s, ГЕН 2 online - ДВ 1 MASTER: idle; H2 burnt from the tanks;</li>
 * <li>«noair»: the batteries, ЗАПУСК, ДВ 2 MASTER without the APU - the start is not attempted, the caution;</li>
 * <li>«quick»: «Быстрый запуск» - both engines at idle in ~40 s;</li>
 * <li>«riders»: ten riders (villagers) seated - the captain, the F/O, eight passengers at their seats; one gets off at the
 * L1 door's stairs (the door opens for it);</li>
 * <li>«ground»: parked on a charging station - ВНЕШН. ПИТ «ДОСТУП», pressed - both AC buses on it.</li>
 * </ul>
 */
final class WorldTestLinerSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-0000001a3201";
    static final int START = 1700;
    private static final int Y = 150, LEN = 30, LANE = 26, LANES = 5, MAX_TICKS = 1400;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA seq, noair, quick, riders, ground;
    private final List<Entity> mobs = new ArrayList<Entity>();
    private int startAt = -1, availAt = -1, eng2At = -1, eng1At = -1, quickAt = -1, step;
    private double h2Before;
    /** The config's random start-fault chance, off for the test (the faults are SelfTestLinerSVA's) and restored after. */
    private double failChance = -1;
    boolean done;

    WorldTestLinerSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        keepLoaded(w);
        if (phase == 0) {
            failChance = com.sva.ConfigSVA.linerStartFailChance;
            com.sva.ConfigSVA.linerStartFailChance = 0;
            setup(w);
            t0 = ticks;
            phase = seq == null || noair == null || quick == null || riders == null || ground == null ? 9 : 1;
            return;
        }
        int n = ticks - t0;
        if (phase == 1) {
            run(w, n);
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private EntityVehicleSVA[] all() {
        return new EntityVehicleSVA[] {seq, noair, quick, riders, ground};
    }

    private static void click(EntityVehicleSVA v, LinerControls.Ctl c) {
        v.state.liner().control(c.index, LinerControls.A_PRIMARY);
    }

    private void run(World w, int n) {
        for (EntityVehicleSVA v : all()) {
            if (v.isDead) {
                check(false, "liner world: a test liner was lost");
                phase = 9;
                return;
            }
        }
        LinerSystems S = seq.state.liner();
        // «seq»: cold and dark first
        if (n == 2) {
            check(!seq.powered() && !S.powered() && S.eng[0].state == LinerSystems.E_OFF && S.apu == LinerSystems.A_OFF && seq.engineState() == SystemsMath.OFF
                            && Math.abs(S.tanks() - seq.state.gas(com.sva.vehicle.FuelMath.H2)) < 2 && S.tankC > 0,
                    "liner world: a new liner is cold and dark - no power, engines and APU off; its tanks hold its H2 (L / C / R)");
            click(seq, LinerControls.BAT1);
            click(seq, LinerControls.BAT2);
        }
        if (n == 5) {
            check(S.essOnly && seq.powered() && S.duPowered(LinerSystems.DU_PFD1) && !S.duPowered(LinerSystems.DU_ND1),
                    "liner world: АКБ 1 + 2 - the essentials on the batteries (the captain's PFD, not the ND)");
            click(seq, LinerControls.APU_MASTER);
        }
        if (n == 7) {
            click(seq, LinerControls.APU_START);
            startAt = n;
        }
        if (availAt < 0 && startAt >= 0 && S.apu == LinerSystems.A_AVAIL) {
            availAt = n;
            double s = (availAt - startAt) / 20.0;
            check(s >= 14 && s <= 18 && S.apuGenOn && S.ac1Src == LinerSystems.S_APU,
                    "liner world: the APU AVAIL " + s + " s after START (~15) - ГЕН ВСУ on both buses");
            click(seq, LinerControls.APU_BLEED);
            click(seq, LinerControls.PACK1);
            click(seq, LinerControls.PACK2);
            click(seq, LinerControls.ENG_MODE);                      // НОРМ -> ЗАПУСК
            click(seq, LinerControls.ENG_MASTER2);
            h2Before = seq.state.gas(com.sva.vehicle.FuelMath.H2);
        }
        if (availAt >= 0 && eng2At < 0 && S.eng[1].running()) {
            eng2At = n;
            double s = (eng2At - availAt) / 20.0;
            check(s >= 8 && s <= 13 && S.apuPsi > 30 && S.at(LinerControls.ENG_MODE) == 2,
                    "liner world: ДВ 2 on the APU's air - idle in " + s + " s (~10)");
            click(seq, LinerControls.ENG_MASTER1);
        }
        if (eng2At >= 0 && n == eng2At + 3) {
            check(S.gen2On && S.ac2Src == LinerSystems.S_GEN2 && seq.engineState() == SystemsMath.RUN,
                    "liner world: ГЕН 2 online at idle; the old engine word says RUN");
        }
        if (eng2At >= 0 && eng1At < 0 && S.eng[0].running()) {
            eng1At = n;
            double used = h2Before - seq.state.gas(com.sva.vehicle.FuelMath.H2);
            check((eng1At - eng2At) / 20.0 <= 14 && S.anyRunning() && S.eng[1].running() && used > 0,
                    "liner world: ДВ 1 at idle " + (eng1At - eng2At) / 20.0 + " s later - both running; " + Math.round(used) + " mB of H2 burnt");
        }
        // «noair»
        LinerSystems N = noair.state.liner();
        if (n == 3) {
            click(noair, LinerControls.BAT1);
            click(noair, LinerControls.ENG_MODE);
            click(noair, LinerControls.ENG_MASTER2);
        }
        if (n == 80) {
            check(N.eng[1].state == LinerSystems.E_WAIT && N.eng[1].n2 == 0 && (N.caut & LinerSystems.C_ENG2_AIR) != 0 && N.masterCaut,
                    "liner world: no bleed air - the start not attempted, «ЗАПУСК ДВ 2: НЕТ ДАВЛЕНИЯ ВОЗДУХА», MASTER CAUT");
        }
        // «quick»
        LinerSystems Q = quick.state.liner();
        if (n == 4) {
            Q.startQuick();
        }
        if (quickAt < 0 && n > 4 && Q.quick == LinerSystems.QUICK_OFF) {
            quickAt = n;
            double s = (quickAt - 4) / 20.0;
            check("done".equals(Q.quickWhy) && Q.eng[0].running() && Q.eng[1].running() && s >= 34 && s <= 46,
                    "liner world: «Быстрый запуск» - both engines at idle in " + s + " s (~40), " + Q.quickWhy);
        }
        // «riders»: ten seated
        if (n == 10) {
            int seated = 0;
            EntityVillager cpt = villager(w, riders);
            cpt.mountEntity(riders);
            seated += riders.riddenByEntity == cpt ? 1 : 0;
            for (int i = 0; i < 9; i++) {
                EntitySeatSVA s = riders.freeSeat();
                if (s == null) {
                    break;
                }
                EntityVillager m = villager(w, riders);
                m.mountEntity(s);
                seated += s.riddenByEntity == m ? 1 : 0;
            }
            check(seated == 10, "liner world: ten riders seated (" + seated + ")");
        }
        if (n == 14) {
            int ok = 0;
            for (Entity e : riders.riders()) {
                int idx = e.ridingEntity instanceof EntitySeatSVA ? ((EntitySeatSVA) e.ridingEntity).getIndex() : 0;
                double[] p = riders.seatPos(idx);
                double dx = e.posX - p[0], dz = e.posZ - p[2];
                ok += dx * dx + dz * dz < 0.5 ? 1 : 0;
            }
            check(riders.ridersNow() == 10 && ok == 10 && riders.freeSeat() == null,
                    "liner world: all ten at their seats (" + ok + ") - the captain, the F/O, four rows of two; no seat left");
            Entity last = riders.riders().get(riders.riders().size() - 1);
            mobs.add(last);
            last.mountEntity(null);
            step = n;
        }
        if (step > 0 && n == step + 6) {
            Entity off = mobs.get(mobs.size() - 1);
            double[] f = doorSpot(riders);
            double dx = off.posX - f[0], dz = off.posZ - f[2];
            check(off.ridingEntity == null && dx * dx + dz * dz < 4 && riders.state.liner().door,
                    "liner world: a passenger gets off at the L1 stairs' foot (" + Math.round(Math.sqrt(dx * dx + dz * dz) * 10) / 10.0
                            + " blocks off) - the door opened for him");
        }
        // «ground»: a charging station under it
        LinerSystems G = ground.state.liner();
        if (n == 60) {
            check(G.extAvail && !G.extOn, "liner world: parked on a charging station - ВНЕШН. ПИТ «ДОСТУП»");
            click(ground, LinerControls.EXT_PWR);
        }
        if (n == 63) {
            check(G.extOn && G.ac1Src == LinerSystems.S_EXT && G.ac2Src == LinerSystems.S_EXT && ground.powered() && !G.essOnly,
                    "liner world: ВНЕШН. ПИТ pressed - both AC buses on the station, the board powered without the batteries' push buttons");
        }
        boolean finished = eng1At >= 0 && quickAt >= 0 && n > 100;
        if (finished || n >= MAX_TICKS) {
            check(eng1At >= 0, "liner world: the cold-and-dark start-up finished");
            phase = 9;
        }
    }

    private double[] doorSpot(EntityVehicleSVA v) {
        double[] w = v.toWorld(LinerLogicSVA.DOOR_X, -2.9);
        return new double[] {v.posX + w[0], v.posY, v.posZ + w[1]};
    }

    private EntityVillager villager(World w, EntityVehicleSVA v) {
        EntityVillager m = new EntityVillager(w);
        m.setPosition(v.posX, v.posY + 1, v.posZ);
        w.spawnEntityInWorld(m);
        mobs.add(m);
        return m;
    }

    // ---------------------------------------------------------------- setup

    private TileEntityChargerSVA station;

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 2400;
        z0 = sp.posZ + 2400;
        keepLoaded(w);
        area(w, true);
        seq = spawn(w, 0, "seq");
        noair = spawn(w, 1, "noair");
        quick = spawn(w, 2, "quick");
        riders = spawn(w, 3, "riders");
        int bx = x0 + 15, bz = z0 + 4 * LANE;
        w.setBlock(bx, Y, bz, ModContentSVA.charger, com.sc.energy.Tier.MV.ordinal(), 3);
        TileEntity te = w.getTileEntity(bx, Y, bz);
        station = te instanceof TileEntityChargerSVA ? (TileEntityChargerSVA) te : null;
        if (station != null) {
            station.fillForTest(station.getMaxEnergyStored());
        }
        ground = spawn(w, 4, "ground");
        for (EntityVehicleSVA v : all()) {
            if (v != null) {
                v.debugEngineManual = true;                          // the real systems, not the instant engines
            }
        }
        check(seq != null && noair != null && quick != null && riders != null && ground != null && station != null
                        && seq.getVehicleType() == VehicleType.LINER && seq.state.liner() != null,
                "liner world: five liners placed (one on a charging station)");
    }

    private EntityVehicleSVA spawn(World w, int lane, String name) {
        VehicleState st = new VehicleState(VehicleType.LINER);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x0 + 15.5, Y + 1, z0 + lane * LANE + 0.5, -90F, OWNER, "WorldTestLiner" + name);
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

    /** Stone strips (11 wide, LEN long) at Y under each liner; build false: air again. */
    private void area(World w, boolean build) {
        for (int lane = 0; lane < LANES; lane++) {
            for (int x = x0; x < x0 + LEN; x++) {
                for (int z = z0 + lane * LANE - 5; z <= z0 + lane * LANE + 5; z++) {
                    if (lane == 4 && x == x0 + 15 && z == z0 + 4 * LANE && build) {
                        continue;                                    // the station's place
                    }
                    w.setBlock(x, Y, z, build ? Blocks.stone : Blocks.air, 0, 2);
                }
            }
        }
    }

    private void finish(World w) {
        for (Entity m : mobs) {
            if (m != null && !m.isDead) {
                m.mountEntity(null);
                m.setDead();
            }
        }
        mobs.clear();
        for (EntityVehicleSVA v : all()) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        seq = noair = quick = riders = ground = null;
        if (x0 != 0 || z0 != 0) {
            area(w, false);
            w.setBlock(x0 + 15, Y, z0 + 4 * LANE, Blocks.air, 0, 3);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        if (failChance >= 0) {
            com.sva.ConfigSVA.linerStartFailChance = failChance;
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
