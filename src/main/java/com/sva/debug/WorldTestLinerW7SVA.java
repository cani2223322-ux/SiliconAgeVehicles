package com.sva.debug;

import com.mojang.authlib.GameProfile;
import com.sva.ConfigSVA;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.entity.LinerGroundSVA;
import com.sva.entity.LinerLogicSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerAdirs;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerFctl;
import com.sva.vehicle.LinerMcdu;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Liner wave 7 world test (the SA-320's ADIRS, laws, doors, chocks, tow and seat belts on the real entity), from tick START
 * on a stone pad in its own area (x spawn - 2600, z spawn - 9000) with its own owner:
 * <ul>
 * <li>«cold»: the INS aligned for real, the automatic IRS INIT off - all three НАВ: «НА АКБ» at the start, aligned once
 * ВЫСТАВИТЬ* is pressed on the MCDU;</li>
 * <li>«adr»: ADR 1 failed - the captain's air data gone in the string the clients get, back with ВОЗД. ДАННЫЕ КВС НА 3;</li>
 * <li>«noadr» (the systems «in the air»): all three ADRs off - the ISIS still powered, the AP refused, no mode C altitude;</li>
 * <li>«altn» (in the air): the ELACs off - ALTN; the gear lever down - DIRECT;</li>
 * <li>«doors»: the cargo door open - the T.O. CONFIG warning; a player looking at it gets the hold, shut - not;</li>
 * <li>«chocks»: at take-off thrust it stays;</li>
 * <li>«tow»: a truck standing at the nose gear with the tow switch ON couples and pushes it back;</li>
 * <li>«belts»: a flying liner with the seat-belt sign - a passenger's Shift is taken back.</li>
 * </ul>
 */
final class WorldTestLinerW7SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-0000001a3207";
    static final int START = 2900;
    private static final int Y = 150, END = 520;
    private static final int PILOT = EntityVehicleSVA.IN_CRUISE;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA cold, adr, noadr, altn, doors, chocks, tow, truck, belts;
    private FakePlayer pax;
    private boolean savedAuto;
    private double towX0, chocksX0;
    boolean done;

    WorldTestLinerW7SVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = all()[0] == null ? 9 : 1;
            for (EntityVehicleSVA v : all()) {
                if (v == null) {
                    phase = 9;
                }
            }
            return;
        }
        int n = ticks - t0;
        keepLoaded(w);
        if (phase == 1) {
            run(w, n);
        }
        if (phase == 9) {
            finish(w);
        }
    }

    private EntityVehicleSVA[] all() {
        return new EntityVehicleSVA[] {cold, adr, noadr, altn, doors, chocks, tow, truck, belts};
    }

    private static LinerSystems L(EntityVehicleSVA v) {
        return v.state.liner();
    }

    private static void set(EntityVehicleSVA v, LinerControls.Ctl c, int p) {
        L(v).control(c.index, LinerControls.A_SET + p);
    }

    private static void click(EntityVehicleSVA v, LinerControls.Ctl c) {
        L(v).control(c.index, LinerControls.A_PRIMARY);
    }

    private void run(World w, int n) {
        for (EntityVehicleSVA v : all()) {
            if (v != null && v.isDead) {
                check(false, "liner W7 world: a test vehicle was lost");
                phase = 9;
                return;
            }
        }
        int total = SystemsMath.alignTicks(ConfigSVA.insAlignSeconds);
        LinerSystems C = L(cold);
        if (n == 20) {
            for (LinerControls.Ctl c : LinerSystems.ADIRS_SEL) {
                set(cold, c, 1);
            }
            L(adr).adirs.fail(0, false);
            for (LinerControls.Ctl c : LinerSystems.ADR_PB) {
                set(noadr, c, 0);
            }
            set(altn, LinerControls.GEAR, 0);
            set(altn, LinerControls.ELAC1, 0);
            set(altn, LinerControls.ELAC2, 0);
            set(doors, LinerControls.FLAPS, 1);
            click(doors, LinerControls.DOOR_CF);
            L(chocks).chocks = true;
            chocks.throttle = 1;
            chocksX0 = chocks.posX;
            set(tow, LinerControls.TOW, 0);
            set(belts, LinerControls.SEAT_BELTS, 0);
        }
        if (n == 23) {
            check(C.adirs.onBat && (C.memos(true) & LinerSystems.M_IRS_ON_BAT) != 0 && C.adirs.state[0] == LinerAdirs.S_ALIGN,
                    "liner W7 world «cold»: three НАВ - aligning, «НА АКБ» lit at the start (the battery test), «ИНС НА АКБ»");
        }
        if (n == 20 + total + 40) {
            boolean waiting = C.adirs.waitInit && C.afs.irsState == 2 && !cold.insReady();
            LinerLogicSVA.mcduKeyServer(cold, 0, LinerMcdu.K_INIT);
            LinerLogicSVA.mcduKeyServer(cold, 0, LinerMcdu.K_R1 + 1);
            LinerLogicSVA.mcduKeyServer(cold, 0, LinerMcdu.K_R1 + 5);   // ВЫСТАВИТЬ*
            check(waiting && C.fms.irsDone, "liner W7 world «cold»: the alignment held for the IRS INIT; ВЫСТАВИТЬ* on the MCDU");
        }
        if (n == 20 + total + 44) {
            check(C.adirs.navOk(0) && C.adirs.navOk(1) && C.adirs.navOk(2) && cold.insReady() && !C.adirs.onBat,
                    "liner W7 world «cold»: all three IRs aligned after the IRS INIT (the INS ready)");
            ConfigSVA.linerIrsAutoInit = savedAuto;
        }
        if (n == 60) {
            LinerSystems m = new LinerSystems();
            m.unpack(adr.getSpaceInfo());
            boolean lost = !m.adirs.sideAdr(0, m.at(LinerControls.AIR_DATA)) && m.adirs.sideAdr(1, m.at(LinerControls.AIR_DATA))
                    && m.ecam.isActive(LinerEcam.M_ADR1) && m.faultLamp(LinerControls.ADR1);
            set(adr, LinerControls.AIR_DATA, LinerAdirs.SW_CAPT3);
            check(lost, "liner W7 world «adr»: ADR 1 failed - the captain's PFD speed / altitude flags (synced), «НАВ ADR 1 ОТКАЗ», ОТКАЗ lit");
            LinerSystems N = L(noadr);
            click(noadr, LinerControls.FCU_AP1);
            check(N.duPowered(LinerSystems.DU_ISIS) && !N.afs.apOn() && N.adrCount() == 0 && !noadr.xpdrAltitude() && N.fctl.law == LinerFctl.DIRECT,
                    "liner W7 world «noadr»: all three ADRs off - the ISIS still reads (its own probe, the battery bus), the AP refused, no mode C"
                            + " altitude");
            LinerSystems A = L(altn);
            check(A.fctl.law == LinerFctl.ALTN && A.ecam.isActive(LinerEcam.M_LAW_ALTN), "liner W7 world «altn»: the ELACs lost in the air - ALTN law");
            set(altn, LinerControls.GEAR, 1);
            click(doors, LinerControls.TO_CONFIG);
        }
        if (n == 65) {
            LinerSystems m = new LinerSystems();
            m.unpack(adr.getSpaceInfo());
            check(m.adirs.sideAdr(0, m.at(LinerControls.AIR_DATA)) && m.at(LinerControls.AIR_DATA) == LinerAdirs.SW_CAPT3,
                    "liner W7 world «adr»: ВОЗД. ДАННЫЕ КВС НА 3 - the captain's air data back (ADIRU 3)");
            LinerSystems A = L(altn);
            check(A.fctl.law == LinerFctl.DIRECT && A.afs.msg == LinerAfs.MSG_TRIM, "liner W7 world «altn»: the gear lever down - DIRECT («ТРИММ ВРУЧНУЮ»)");
            LinerSystems D = L(doors);
            check(D.cargoF && D.ecam.isActive(LinerEcam.M_CFG_DOOR) && D.masterWarn, "liner W7 world «doors»: the cargo door open - T.O. CONFIG"
                    + " gives «КОНФИГ ВЗЛЁТ ДВЕРИ ОТКР»");
            // a player beside the forward cargo door looking at it: the hold; the aft one (shut): not
            FakePlayer p = FakePlayerFactory.getMinecraft((WorldServer) w);
            aim(p, doors, LinerGroundSVA.DOORS[2]);
            int open = LinerGroundSVA.cargoAccess(doors, p);
            aim(p, doors, LinerGroundSVA.DOORS[3]);
            int shut = LinerGroundSVA.cargoAccess(doors, p);
            check(open == LinerGroundSVA.CARGO_OPEN && shut == LinerGroundSVA.CARGO_SHUT, "liner W7 world «doors»: looking at the open cargo door"
                    + " - the hold from outside; at the shut one - not");
        }
        if (n == 160) {
            check(Math.abs(chocks.posX - chocksX0) < 0.05 && L(chocks).afs.lever >= 0.99 && L(chocks).anyRunning(),
                    "liner W7 world «chocks»: at take-off thrust it stays on its chocks (moved " + Math.round(Math.abs(chocks.posX - chocksX0) * 100) / 100.0
                            + ")");
            check(L(tow).towed && L(tow).tugId == truck.getEntityId(), "liner W7 world «tow»: the truck at the nose gear with the tow switch ON"
                    + " couples");
            towX0 = tow.posX;
        }
        if (n > 160 && n <= 260 && L(tow).towed) {
            truck.setPosition(truck.posX + 0.05, truck.posY, truck.posZ);   // its driver backs away (east): it pulls the liner
        }
        if (n == 262) {
            double moved = towX0 - tow.posX;
            check(L(tow).towed && Math.abs(moved) > 3 && (L(tow).memos(true) & LinerSystems.M_TOW) != 0,
                    "liner W7 world «tow»: the tug pulls it " + Math.round(Math.abs(moved) * 10) / 10.0 + " blocks (the nose gear on the hitch)");
            set(tow, LinerControls.TOW, 1);
        }
        if (n == 266) {
            check(!L(tow).towed, "liner W7 world «tow»: the tow switch OFF - uncoupled");
        }
        // the seat belts: a passenger in seat 3 of the flying liner
        if (n == 30) {
            EntitySeatSVA s = belts.seatAt(3);
            pax = FakePlayerFactory.get((WorldServer) w, new GameProfile(java.util.UUID.fromString("00000000-5bad-4e57-0000-0000001a3270"), "W7Pax"));
            if (s != null) {
                pax.setPosition(belts.posX, belts.posY, belts.posZ);
                ride(pax, s);                                       // (a fake player has no connection for mountEntity's packet)
            }
        }
        if (n == 80 && pax != null) {
            pax.setSneaking(true);
        }
        if (n == 82 && pax != null) {
            boolean held = pax.ridingEntity instanceof EntitySeatSVA && !pax.isSneaking() && LinerGroundSVA.fastened(belts, L(belts));
            check(held, "liner W7 world «belts»: in flight with the seat-belt sign the passenger's Shift is taken back (fastened)");
            pax.setSneaking(false);
        }
        if (n == 84 && pax != null && pax.ridingEntity instanceof EntitySeatSVA) {
            // the second press within 3 s: the rule lets it through (checked here at once - a fake player has no connection,
            // vanilla's dismount on its own update would fail on it)
            pax.setSneaking(true);
            boolean held = LinerGroundSVA.belts((EntitySeatSVA) pax.ridingEntity, pax);
            check(!held && pax.isSneaking(), "liner W7 world «belts»: the second Shift within 3 s is let through (unbuckled)");
            pax.setSneaking(false);
            ride(pax, null);
            belts.dismountAll();
            belts.setDead();                                        // done: it would fly out of the loaded area
            belts = null;
        }
        if (n >= END) {
            phase = 9;
        }
    }

    /** Mounts / unmounts a fake player by the fields (EntityPlayerMP.mountEntity sends a packet it has no connection for). */
    private static void ride(FakePlayer p, net.minecraft.entity.Entity m) {
        if (p.ridingEntity != null) {
            p.ridingEntity.riddenByEntity = null;
        }
        p.ridingEntity = m;
        if (m != null) {
            m.riddenByEntity = p;
        }
    }

    /** A player beside the liner (2.5 blocks out to the right of the point, standing on the pad) looking at a model point {x, z, y}. */
    private static void aim(FakePlayer p, EntityVehicleSVA v, double[] m) {
        double[] q = v.toWorld(m[0], m[1] + 2.5);
        double[] c = v.toWorld(m[0], m[1]);
        double px = v.posX + q[0], pz = v.posZ + q[1], py = v.posY;
        double tx = v.posX + c[0], tz = v.posZ + c[1], ty = v.posY + m[2];
        double dx = tx - px, dz = tz - pz, dy = ty - (py + p.getEyeHeight());
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setPositionAndRotation(px, py, pz, yaw, pitch);
        p.rotationYawHead = yaw;
    }

    // ---------------------------------------------------------------- setup

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX - 2600;
        z0 = sp.posZ - 9000;
        keepLoaded(w);
        pad(w, true);
        savedAuto = ConfigSVA.linerIrsAutoInit;
        ConfigSVA.linerIrsAutoInit = false;
        cold = liner(w, 0, true, false);
        if (cold != null) {
            cold.debugInsManual = true;
        }
        adr = liner(w, 1, true, false);
        noadr = liner(w, 2, true, true);
        altn = liner(w, 3, true, true);
        doors = liner(w, 4, true, false);
        chocks = liner(w, 5, true, false);
        tow = liner(w, 6, true, false);
        belts = flyer(w, 7);
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {adr, noadr, altn, doors, chocks, tow}) {
            if (v != null) {
                for (LinerControls.Ctl c : LinerSystems.ADIRS_SEL) {
                    L(v).set(c, 1);
                }
            }
        }
        if (tow != null) {
            // the truck facing the liner (west), its front hitch on the nose gear
            VehicleState st = new VehicleState(VehicleType.TRUCK);
            st.fillAllGases();
            st.energy = st.maxEnergy();
            ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.TRUCK.ordinal()), st);
            double[] n = tow.toWorld(LinerGroundSVA.NOSE_GEAR[0], 0);
            truck = ItemVehicleSVA.spawn(w, s, tow.posX + n[0] + VehicleType.TRUCK.halfLength + 0.3, Y + 1, tow.posZ + n[1], 90F, OWNER,
                    "WorldTestLinerW7");
            if (truck != null) {
                truck.debugDriver = true;
            }
        }
        check(cold != null && adr != null && noadr != null && altn != null && doors != null && chocks != null && tow != null && truck != null
                && belts != null, "liner W7 world: eight liners and a truck on the pad (ADIRS cold, ADR 1, no ADRs, ALTN, doors, chocks, tow, belts)");
    }

    private void pad(World w, boolean on) {
        for (int x = x0 - 12; x < x0 + 34; x++) {
            for (int z = z0 - 10; z <= z0 + 8 * 26 + 10; z++) {
                w.setBlock(x, Y, z, on ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private EntityVehicleSVA liner(World w, int i, boolean engines, boolean air) {
        VehicleState st = new VehicleState(VehicleType.LINER);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, s, x0 + 0.5, Y + 1, z0 + i * 26 + 0.5, -90F, OWNER, "WorldTestLinerW7");
        if (v == null) {
            return null;
        }
        if (engines) {
            v.debugDrive(PILOT);
        } else {
            v.debugEngineManual = true;
        }
        v.debugLinerAir = air;
        return v;
    }

    /** A liner flying east high over the pad (the instant start, gear up, the levers at CL). */
    private EntityVehicleSVA flyer(World w, int i) {
        VehicleState st = new VehicleState(VehicleType.LINER);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, s, x0 - 400 + 0.5, Y + 70, z0 + i * 26 + 0.5, -90F, OWNER, "WorldTestLinerW7");
        if (v == null) {
            return null;
        }
        LinerSystems L = L(v);
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.PARK_BRK, 0);
        L.set(LinerControls.GEAR, 0);
        L.gear.pos = 1;
        v.speed = 1.8;
        v.motionY = 0;
        v.throttle = LinerAfs.DET_CL;
        v.debugDrive(PILOT);
        return v;
    }

    private void keepLoaded(World w) {
        if (!(w instanceof WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 460) >> 4; cx <= (x0 + 60) >> 4; cx++) {
            for (int cz = (z0 - 48) >> 4; cz <= (z0 + 8 * 26 + 48) >> 4; cz++) {
                if (cx >= (x0 - 48) >> 4 || cz >= (z0 + 7 * 26 - 40) >> 4 && cz <= (z0 + 7 * 26 + 40) >> 4) {
                    cp.loadChunk(cx, cz);
                }
            }
        }
    }

    private void finish(World w) {
        if (pax != null && pax.ridingEntity != null) {
            ride(pax, null);
        }
        for (EntityVehicleSVA v : all()) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        cold = adr = noadr = altn = doors = chocks = tow = truck = belts = null;
        ConfigSVA.linerIrsAutoInit = savedAuto;
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
