package com.sva.debug;

import com.sva.ConfigSVA;
import com.sva.block.IlsDataSVA;
import com.sva.block.NavDataSVA;
import com.sva.block.TileEntityIlsSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.entity.LinerLogicSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerFms;
import com.sva.vehicle.LinerGuide;
import com.sva.vehicle.LinerMcdu;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.NavDb;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Liner wave 5 world test (the SA-320's MCDU / FMS on the real entity, driven through the server-side MCDU API - the
 * very handler the McduKey packets call, LinerLogicSVA.mcduKeyServer), from tick START in its own area (x spawn + 2400
 * .., z spawn + 4600 ..) with its own owner:
 * <ul>
 * <li>two «Курсо-глиссадный маяк» blocks 4000 apart, ГЛВН and ОЗРН, both landing east - the MCDU's database makes them
 * airports;</li>
 * <li>«nav»: in the air past ГЛВН, the plan typed on the MCDU (FROM / TO, cruise, Т1 with «-176», Т2, ВСТАВИТЬ), AP 1 +
 * A/THR, NAV and managed CLB: Т1 and Т2 sequenced on the line, the climb held at Т1's constraint (ALT CST), then the
 * cruise; the FCU set lower at the cruise, the managed descent from T/D on its path; RAD NAV tunes ОЗРН by its ident,
 * APPR - the approach captured;</li>
 * <li>«dir»: NAV on another plan, DIR TO Т2 from the plan's list and ВСТАВИТЬ - the route from «ТП» straight to Т2, the
 * aircraft turns onto it;</li>
 * <li>«to»: on a stone pad with the engines running - a plan and no V-speeds: T.O. CONFIG fails («V1/VR/V2 НЕ ВВЕДЕНЫ»);
 * PERF's ВЫЧИСЛИТЬ - T.O. CONFIG passes, the AFS (the PFD's marks) has the speeds;</li>
 * <li>«irs»: on the pad with the real INS alignment and the config's automatic IRS INIT off - it waits at its end (the
 * amber memo) until the MCDU's ВЫСТАВИТЬ, then aligns.</li>
 * </ul>
 */
final class WorldTestLinerFmsSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-0000001a3205";
    static final int START = 1800;
    private static final int Y = 150, FLY_Y = 168, CRZ = 186, CST = 176, LOW = 175, MAX_TICKS = 3400;
    private static final int PILOT = EntityVehicleSVA.IN_CRUISE;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0;
    private EntityVehicleSVA nav, dir, to, irs;
    private boolean navDone, dirDone, toDone, irsDone;
    private boolean savedAuto = true;
    // «nav» measurements
    private double maxBeforeT1, maxCross, desX = Double.NaN, tdXAt = Double.NaN, pathErr;
    private int seq1 = -1, seq2 = -1, cruiseAt = -1, capAt = -1;
    private boolean cstSeen, lowered, approach;
    boolean done;

    WorldTestLinerFmsSVA(WorldTestSVA t) {
        this.t = t;
    }

    void tick(World w, int ticks) {
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = nav == null || dir == null || to == null || irs == null ? 9 : 1;
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
        return new EntityVehicleSVA[] {nav, dir, to, irs};
    }

    private static LinerSystems L(EntityVehicleSVA v) {
        return v.state.liner();
    }

    // ---------------------------------------------------------------- the MCDU through the server's handler

    static void key(EntityVehicleSVA v, int code) {
        LinerLogicSVA.mcduKeyServer(v, 0, code);
    }

    static void type(EntityVehicleSVA v, String s) {
        for (int i = 0; i < s.length(); i++) {
            key(v, LinerMcdu.K_CHAR + s.charAt(i));
        }
    }

    /** Types an entry and presses a line key (1..6). */
    static void enter(EntityVehicleSVA v, String s, boolean right, int lsk) {
        type(v, s);
        key(v, (right ? LinerMcdu.K_R1 : LinerMcdu.K_L1) + lsk - 1);
    }

    private static void click(EntityVehicleSVA v, LinerControls.Ctl c, int action) {
        L(v).control(c.index, action);
    }

    /** INIT and the plan on MCDU 1: FROM / TO, the cruise, CI 50, Т1 (x1, z1) and Т2 (x2, z2) inserted. */
    private static void plan(EntityVehicleSVA v, int x1, int z1, int x2, int z2) {
        key(v, LinerMcdu.K_INIT);
        enter(v, "ГЛВН/ОЗРН", true, 1);
        enter(v, String.valueOf(CRZ), false, 5);
        enter(v, "50", false, 4);
        key(v, LinerMcdu.K_FPLN);
        enter(v, x1 + "/" + z1, false, 2);                         // over the discontinuity after the origin
        enter(v, x2 + "/" + z2, false, 3);                         // before the destination
        key(v, LinerMcdu.K_R1 + 5);                                 // ВСТАВИТЬ
    }

    // ---------------------------------------------------------------- the run

    private void run(int n) {
        for (EntityVehicleSVA v : all()) {
            if (v.isDead) {
                check(false, "liner FMS world: a test liner was lost");
                phase = 9;
                return;
            }
        }
        if (n == 3) {
            nav.debugEngineManual = true;
            dir.debugEngineManual = true;
        }
        if (n == 6) {
            // the plans through the MCDU (the database has the beacons: their airports)
            plan(nav, x0 + 1000, z0, x0 + 2500, z0);
            enter(nav, "/-" + CST, true, 2);                        // Т1: at or below
            plan(dir, x0 + 1500, z0 + 200, x0 + 2500, z0 + 600);
            LinerFms f = L(nav).fms;
            check(f.pts.size() == 5 && "ГЛВН09".equals(f.pts.get(0).id) && f.pts.get(1).cst == LinerFms.C_BELOW && f.pts.get(1).alt == CST
                            && "ОЗРН09".equals(f.pts.get(3).id) && f.crz == CRZ && f.tmp == null && L(dir).fms.pts.size() == 5,
                    "liner FMS world: the plans built on the MCDU (the server's handler) - FROM / TO from the two beacons' airports, the"
                            + " cruise, two waypoints by X/Z, Т1 \"-" + CST + "\", ВСТАВИТЬ");
            engage(nav);
            engage(dir);
        }
        if (n > 6) {
            navTick(n);
            dirTick(n);
        }
        toTick(n);
        irsTick(n);
        if (navDone && dirDone && toDone && irsDone || n >= MAX_TICKS) {
            if (!navDone) {
                LinerAfs F = L(nav).afs;
                check(false, "liner FMS world «nav»: did not finish (x " + Math.round(nav.posX - x0) + ", y " + Math.round(nav.posY) + ", "
                        + F.verText() + " / " + F.latText() + ", active " + L(nav).fms.active + ")");
            }
            if (!dirDone || !toDone || !irsDone) {
                check(false, "liner FMS world: «dir» / «to» / «irs» did not finish " + dirDone + toDone + irsDone);
            }
            phase = 9;
        }
    }

    /** A/THR, AP 1, the FCU at the cruise; NAV (HDG pushed) and managed CLB (ALT pushed). */
    private static void engage(EntityVehicleSVA v) {
        click(v, LinerControls.FCU_ATHR, LinerControls.A_PRIMARY);
        click(v, LinerControls.FCU_AP1, LinerControls.A_PRIMARY);
        L(v).afs.alt = CRZ;
        click(v, LinerControls.FCU_HDG, LinerControls.A_PRIMARY);
        click(v, LinerControls.FCU_ALT, LinerControls.A_PRIMARY);
    }

    private void navTick(int n) {
        if (navDone) {
            return;
        }
        LinerSystems S = L(nav);
        LinerAfs F = S.afs;
        LinerFms M = S.fms;
        if (n == 10) {
            check(F.lat == LinerAfs.L_NAV && F.ver == LinerAfs.V_CLB && nav.state.plan.size() >= 2 && nav.state.plan.active == 1,
                    "liner FMS world «nav»: NAV and managed CLB engaged on the FMS' route (the vehicle's route is its bridge: " + nav.state.plan.size()
                            + " points, from the origin)");
        }
        if (M.active <= 1) {
            maxBeforeT1 = Math.max(maxBeforeT1, nav.posY);
            cstSeen |= F.cst && (F.ver == LinerAfs.V_ALT || F.ver == LinerAfs.V_ALT_CAP);
        }
        maxCross = Math.max(maxCross, Math.abs(nav.posZ - (z0 + 0.5)));
        seq1 = seq1 < 0 && M.active == 2 ? n : seq1;
        seq2 = seq2 < 0 && M.active == 3 ? n : seq2;
        if (!lowered && M.phase == LinerFms.PH_CRZ && F.ver == LinerAfs.V_ALT && Math.abs(nav.posY - CRZ) < 1) {
            lowered = true;
            cruiseAt = n;
            F.alt = LOW;                                            // the pilot sets the FCU lower before T/D
            check(seq1 > 0 && maxBeforeT1 < CST + 0.6 && cstSeen, "liner FMS world «nav»: managed CLB held at Т1's \"-" + CST + "\" (ALT CST, max "
                    + f(maxBeforeT1) + ") until Т1 was sequenced (" + seq1 + "), then on to the cruise " + CRZ);
        }
        if (Double.isNaN(desX) && F.ver == LinerAfs.V_DES) {
            desX = nav.posX;
            tdXAt = M.tdX;
        }
        if (!Double.isNaN(desX) && F.ver == LinerAfs.V_DES && !Double.isNaN(F.pathAlt)) {
            pathErr = Math.max(pathErr, Math.abs(nav.posY - F.pathAlt));
        }
        if (!approach && !Double.isNaN(desX) && nav.posX > Math.max(desX + 40, x0 + 3350)) {
            approach = true;
            key(nav, LinerMcdu.K_RAD);
            enter(nav, "ОЗРН", false, 1);                           // RAD NAV: the ILS by its ident
            click(nav, LinerControls.FCU_APPR, LinerControls.A_PRIMARY);
        }
        if (approach && capAt < 0 && (F.ver == LinerAfs.V_GS_CAP || F.ver == LinerAfs.V_GS || F.ver == LinerAfs.V_LAND)) {
            capAt = n;
        }
        if (capAt > 0 && n >= capAt + 5) {
            navDone = true;
            check(seq1 > 0 && seq2 > seq1 && maxCross < 3 && lowered, "liner FMS world «nav»: NAV flew the plan - Т1 sequenced at " + seq1 + ", Т2 at "
                    + seq2 + " ticks, " + f(maxCross) + " blocks off the line at most; cruise reached at " + cruiseAt);
            check(!Double.isNaN(desX) && Math.abs(desX - tdXAt) < 20 && pathErr < 4, "liner FMS world «nav»: the managed descent began at T/D (x "
                    + f(desX - x0) + ", T/D " + f(tdXAt - x0) + ") and kept its path within " + f(pathErr) + " blocks");
            check(F.ils != null && "ОЗРН".equals(F.ils.ident) && F.ilsChosen.length() > 0 && (F.lat == LinerAfs.L_LOC || F.lat == LinerAfs.L_LOC_CAP
                            || F.lat == LinerAfs.L_LAND), "liner FMS world «nav»: RAD NAV tuned ОЗРН by hand, APPR - LOC and G/S captured at " + capAt
                            + " ticks (" + F.latText() + " / " + F.verText() + ")");
            String nd = M.ndText();
            check(nd.length() > 0 && LinerFms.parseNd(nd) != null && LinerFms.parseNd(nd).pts.size() >= 2, "liner FMS world «nav»: the ND's plan"
                    + " data (sent to the pilots) parses: " + LinerFms.parseNd(nd).pts.size() + " entries");
            nav.debugDrive(0);
        }
    }

    private void dirTick(int n) {
        if (dirDone) {
            return;
        }
        LinerSystems S = L(dir);
        if (n == 60) {
            key(dir, LinerMcdu.K_DIR);
            key(dir, LinerMcdu.K_L1 + 2);                           // the second waypoint ahead: Т2
            boolean tmpy = S.fms.tmp != null && "ТП".equals(S.fms.tmp.get(0).id) && "Т2".equals(S.fms.tmp.get(1).id);
            key(dir, LinerMcdu.K_R1 + 5);                           // ВСТАВИТЬ
            check(tmpy && S.fms.tmp == null && S.fms.active == 1 && "Т2".equals(S.fms.pts.get(1).id), "liner FMS world «dir»: DIR TO Т2 from the plan's"
                    + " list - the TMPY from «ТП», ВСТАВИТЬ - active");
        }
        if (n == 400) {
            dirDone = true;
            LinerFms.Wp t2 = S.fms.pts.get(1);
            double brg = RouteMath.course(dir.posX, dir.posZ, t2.x, t2.z);
            double hdg = LinerGuide.compass(dir.rotationYaw);
            RouteMath.Plan p = dir.state.plan;
            check(S.afs.lat == LinerAfs.L_NAV && Math.abs(com.sva.vehicle.CruiseMath.wrap(hdg - brg)) < 6 && p.size() >= 2 && p.pts.get(1)[0]
                            == (int) Math.floor(t2.x), "liner FMS world «dir»: NAV flies straight to Т2 (heading " + f(hdg) + ", bearing " + f(brg)
                            + "), the route from the turn point");
            dir.debugDrive(0);
        }
    }

    private void toTick(int n) {
        if (toDone) {
            return;
        }
        LinerSystems S = L(to);
        if (n == 40) {
            plan(to, x0 + 1000, z0, x0 + 2500, z0);
            click(to, LinerControls.TO_CONFIG, LinerControls.A_PRIMARY);
        }
        if (n == 44) {
            boolean missing = (S.ecam.shown & 1L << LinerEcam.M_TO_SPEEDS) != 0 && !S.ecam.toCfgOk && !S.afs.vsEntered;
            check(missing, "liner FMS world «to»: a plan and no V-speeds - T.O. CONFIG shows \"V1/VR/V2 НЕ ВВЕДЕНЫ\", no \"ТО КОНФИГ НОРМ\" (top "
                    + S.ecam.top() + ")");
            key(to, LinerMcdu.K_PERF);
            key(to, LinerMcdu.K_R1 + 4);                            // ВЫЧИСЛИТЬ*
        }
        if (n == 48) {
            click(to, LinerControls.TO_CONFIG, LinerControls.A_PRIMARY);
        }
        if (n == 52) {
            toDone = true;
            LinerFms f = S.fms;
            boolean good = S.ecam.toCfgOk && (S.ecam.active & 1L << LinerEcam.M_TO_SPEEDS) == 0 && S.afs.vsEntered && Math.abs(S.afs.v1 * 72 - f.v1)
                    < 1e-6 && Math.abs(S.afs.vr * 72 - f.vr) < 1e-6 && f.v1 > 0 && f.v1 <= f.vr && f.vr < f.v2;
            check(good, "liner FMS world «to»: PERF's ВЫЧИСЛИТЬ - V1 " + f.v1 + " / VR " + f.vr + " / V2 " + f.v2 + " km/h in the AFS (the PFD's marks),"
                    + " T.O. CONFIG passes");
            to.debugDrive(0);
        }
    }

    private void irsTick(int n) {
        if (irsDone) {
            return;
        }
        int total = SystemsMath.alignTicks(ConfigSVA.insAlignSeconds);
        LinerSystems S = L(irs);
        if (n == total + 60) {
            boolean waiting = irs.insStatus() == SystemsMath.INS_ALIGN && S.afs.irsState == 2 && (S.memos(true) & LinerSystems.M_IRS_INIT) != 0
                    && !S.fms.irsDone;
            key(irs, LinerMcdu.K_INIT);
            key(irs, LinerMcdu.K_R1 + 1);                           // ИНС ИНИЦ>
            key(irs, LinerMcdu.K_R1 + 5);                           // ВЫСТАВИТЬ*
            check(waiting && S.fms.irsDone, "liner FMS world «irs»: the automatic IRS INIT off - the INS waits at the alignment's end (the amber"
                    + " memo) " + (total + 60) + " ticks on; ВЫСТАВИТЬ* on the MCDU");
        }
        if (n == total + 63) {
            irsDone = true;
            check(irs.insReady() && S.afs.irsState == 0, "liner FMS world «irs»: aligned the tick after the confirmation");
            ConfigSVA.linerIrsAutoInit = savedAuto;
            irs.debugDrive(0);
        }
    }

    // ---------------------------------------------------------------- setup

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX + 2400;
        z0 = sp.posZ + 4600;
        keepLoaded(w);
        boolean b1 = beacon(w, x0, z0, "ГЛВН", 10910), b2 = beacon(w, x0 + 4000, z0, "ОЗРН", 11030);
        // the pad for the ground liners
        pad(w, true);
        NavDataSVA nd = NavDataSVA.get(w);
        NavDb db = nd == null ? null : nd.db(w, w.provider.dimensionId, null);
        boolean airports = db != null && db.airport("ГЛВН") != null && db.airport("ОЗРН") != null && db.airport("ОЗРН").rwys.get(0).ilsKey.length() > 0;
        nav = flyer(w, x0 + 200, z0, FLY_Y, 1.6);
        dir = flyer(w, x0 + 200, z0 + 200, FLY_Y, 1.6);
        to = grounded(w, x0 + 120, z0 - 300, false);
        irs = grounded(w, x0 + 120, z0 - 330, true);
        check(b1 && b2 && airports && nav != null && dir != null && to != null && irs != null, "liner FMS world: two beacons 4000 apart (ГЛВН, ОЗРН)"
                + " - airports in the MCDU's database at once; two liners in the air, two on a pad");
    }

    private static boolean beacon(World w, int x, int z, String ident, int freq) {
        w.setBlock(x, Y, z, ModContentSVA.ils, 0, 3);
        TileEntity te = w.getTileEntity(x, Y, z);
        if (!(te instanceof TileEntityIlsSVA)) {
            return false;
        }
        TileEntityIlsSVA b = (TileEntityIlsSVA) te;
        b.st.elev = Y + 1;
        b.set(90, 3, ident, freq, 0);
        return IlsDataSVA.get(w) != null;
    }

    private void pad(World w, boolean on) {
        for (int x = x0 + 100; x < x0 + 145; x++) {
            for (int z = z0 - 340; z <= z0 - 290; z++) {
                w.setBlock(x, Y, z, on ? Blocks.stone : Blocks.air, 0, 2);
            }
        }
    }

    private static EntityVehicleSVA spawn(World w, double x, double y, double z) {
        VehicleState st = new VehicleState(VehicleType.LINER);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), st);
        return ItemVehicleSVA.spawn(w, s, x + 0.5, y, z + 0.5, -90F, OWNER, "WorldTestLinerFms");
    }

    /** A liner in the air heading east: engines (the instant start), gear up, the levers at CL. */
    private static EntityVehicleSVA flyer(World w, int x, int z, double y, double speed) {
        EntityVehicleSVA v = spawn(w, x, y, z);
        if (v == null) {
            return null;
        }
        LinerSystems L = L(v);
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        L.set(LinerControls.PARK_BRK, 0);
        L.set(LinerControls.GEAR, 0);
        L.gear.pos = 1;
        v.debugLinerAir = false;
        v.speed = speed;
        v.motionY = 0;
        v.throttle = LinerAfs.DET_CL;
        L.afs.alt = (int) Math.round(y);
        v.debugDrive(PILOT);
        return v;
    }

    /** A liner on the pad: the engines running (the instant start), flaps 1; `realIns` - the INS aligns for real. */
    private EntityVehicleSVA grounded(World w, int x, int z, boolean realIns) {
        EntityVehicleSVA v = spawn(w, x, Y + 1, z);
        if (v == null) {
            return null;
        }
        LinerSystems L = L(v);
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        L.set(LinerControls.FLAPS, 1);
        L.fctl.flapPos = 1;
        L.fctl.slatPos = 1;
        if (realIns) {
            v.debugInsManual = true;
            savedAuto = ConfigSVA.linerIrsAutoInit;
            ConfigSVA.linerIrsAutoInit = false;
        }
        v.debugDrive(PILOT);
        return v;
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        if (phase == 0) {
            for (int cx = (x0 - 32) >> 4; cx <= (x0 + 4032) >> 4; cx += 1) {
                for (int cz = (z0 - 16) >> 4; cz <= (z0 + 16) >> 4; cz++) {
                    cp.loadChunk(cx, cz);
                }
            }
            for (int cx = (x0 + 90) >> 4; cx <= (x0 + 150) >> 4; cx++) {
                for (int cz = (z0 - 350) >> 4; cz <= (z0 - 280) >> 4; cz++) {
                    cp.loadChunk(cx, cz);
                }
            }
            return;
        }
        for (EntityVehicleSVA v : all()) {
            if (v == null || v.isDead) {
                continue;
            }
            int cx = (int) Math.floor(v.posX) >> 4, cz = (int) Math.floor(v.posZ) >> 4;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    cp.loadChunk(cx + dx, cz + dz);
                }
            }
        }
    }

    private void finish(World w) {
        ConfigSVA.linerIrsAutoInit = savedAuto;
        for (EntityVehicleSVA v : all()) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        nav = dir = to = irs = null;
        if (x0 != 0 || z0 != 0) {
            w.setBlock(x0, Y, z0, Blocks.air, 0, 3);
            w.setBlock(x0 + 4000, Y, z0, Blocks.air, 0, 3);
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

    static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }
}
