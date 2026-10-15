package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sva.airport.FlightCheck;
import com.sva.airport.IlsPairing;
import com.sva.airport.MarkerMath;
import com.sva.block.AirportDataSVA;
import com.sva.block.BlockIlsAntennaSVA;
import com.sva.block.BlockRunwayEndSVA;
import com.sva.block.IlsDataSVA;
import com.sva.block.TileEntityAptDeviceSVA;
import com.sva.block.TileEntityIlsAntennaSVA;
import com.sva.block.TileEntityIlsSVA;
import com.sva.block.TileEntityIlsShelterSVA;
import com.sva.block.TileEntityRunwayEndSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemAptPlannerSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Airport wave B1 world test (plan-next §9): the airport built through the blocks' API in its own area (spawn - 6000 /
 * + 6000, platforms at Y 150) with its own owner:
 * <ul>
 * <li>«build»: runway R1 (5 x 301 runway blocks between two «Торец полосы» markers) with its localizer array 30 past the
 * far end, the glide slope mast 25 in / 15 off, both shelters fed with EU, OM / MM / IM on a 2700-block approach carpet,
 * three RVR sensors, a weather station and a windsock; runway R2 400 to the south with its own ILS (no markers); a strip
 * whose two ends the planner tablet places (through a fake player); an old single ILS beacon - they pair: two FULL stations
 * in the save's ILS list beside the COMPACT one, uncalibrated CAT I;</li>
 * <li>«calibration»: a light plane with its transponder on flown (moved) down R1's glide path to the threshold - the flight
 * inspection runs and calibrates R1: CAT III;</li>
 * <li>«cat1»: an SA-320 on R2 (uncalibrated, CAT I) with APPR + AP 1 + 2 - CAT 1 on the FMA, no LAND, the AP lets go at the
 * minimum with «MINIMUM» and the cavalry charge;</li>
 * <li>«autoland»: an SA-320 on R1 (CAT III) - CAT 3 DUAL, LAND / FLARE / ROLL OUT, stopped on the runway; OM, MM, IM heard
 * in order; the RVR and the weather station's wind in its systems;</li>
 * <li>«critical»: a block in R1's LOC critical area and a buggy in its G/S critical area - CAT I, the beams bend; cleared -
 * CAT III again;</li>
 * <li>«false G/S»: an SA-320 500 blocks before R2 10 deg up - G/S captured from above on the 9 deg lobe;</li>
 * <li>«monitor»: an element of R2's localizer broken - the monitor shuts the station off: that liner's LOC / G/S flags and
 * the ECAM's «ILS ОТКАЗ»; R1's localizer shelter drained - no power, off.</li>
 * </ul>
 */
final class WorldTestAirportSVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-0000a1290b01";
    static final int START = 3200;
    private static final int Y = 150, LEN = 301, W = 5, CARPET = 2700, CARPET2 = 1100, MAX_TICKS = 3400;
    private static final int PILOT = EntityVehicleSVA.IN_CRUISE;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, rx, rz, rz2;
    boolean done;
    private final List<TileEntityIlsShelterSVA> shelters = new ArrayList<TileEntityIlsShelterSVA>();
    private TileEntityIlsShelterSVA drained;
    private EntityVehicleSVA plane, cat1, appr, fgs, buggy;
    private String loc1 = "", loc2 = "", fgsLoc = "";
    // measurements
    private int calibAt = -1, apprStart = -1, critAt = -1, fgsAt = -1;
    private boolean c1Cap1, c1Land, c1Min, c1Done, apprDone, critDone, fgsDone, monDone;
    private double c1OffRa = -1;
    private int c1Seq, bSeq, land = -1, flare = -1, roll = -1, lobe3, fgsGs = -1;
    private final List<Integer> markersSeen = new ArrayList<Integer>();
    private boolean bDual, bRvr, bWind;

    WorldTestAirportSVA(WorldTestSVA t) {
        this.t = t;
    }

    private void check(boolean ok, String name) {
        t.check(ok, name);
    }

    static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }

    void tick(World w, int ticks) {
        if (phase == 0) {
            setup(w);
            t0 = ticks;
            phase = 1;
            return;
        }
        int n = ticks - t0;
        keepLoaded(w);
        for (TileEntityIlsShelterSVA s : shelters) {
            if (s != drained && !s.isInvalid()) {
                s.fillForTest(s.getMaxEnergyStored());
            }
        }
        if (phase == 1) {
            run(w, n);
        }
        if (phase == 9 || n > MAX_TICKS) {
            finish(w);
        }
    }

    private static LinerSystems L(EntityVehicleSVA v) {
        return v.state.liner();
    }

    private static void click(EntityVehicleSVA v, LinerControls.Ctl c) {
        L(v).control(c.index, LinerControls.A_PRIMARY);
    }

    private void run(World w, int n) {
        AirportDataSVA d = AirportDataSVA.get(w);
        if (d == null) {
            check(false, "airport world: no airport data");
            phase = 9;
            return;
        }
        if (n == 10) {
            // the planner tablet's «поставить торцы» on the third strip (a fake player in creative)
            net.minecraftforge.common.util.FakePlayer fp = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft((net.minecraft.world.WorldServer) w);
            fp.capabilities.isCreativeMode = true;
            ItemAptPlannerSVA.placeEnds(new ItemStack(ModContentSVA.aptPlanner), fp, w, rx + 120, Y, rz + 200);
            fp.capabilities.isCreativeMode = false;
        }
        if (n == 80) {
            build(w, d);
            spawnPlane(w);
            cat1 = flyer(w, rx - 1000, rz2 - 30, Y + 1 + 45, 1.3, 3, 110, false);
        }
        if (n > 80 && n < 600) {
            calibration(w, d, n);
            if (n % 100 == 0) {
                FlightCheck fc = d.check(loc1);
                System.out.println("[SVA-WORLDTEST] airport trace n " + n + " plane " + (plane == null ? "null" : (plane.isDead ? "dead " : "") + f(plane.posX - rx)
                        + " / " + f(plane.posY) + " / " + f(plane.posZ - rz) + " xpdr " + plane.state.xpdr.replying()) + " check " + fc.state + " samples "
                        + fc.samples + " cat " + IlsDataSVA.get(w).get(loc1).cat + " flags " + IlsDataSVA.get(w).get(loc1).flags);
            }
        }
        if (n == 250) {
            devices(w, d);
        }
        if (n == 600) {
            LinerIls.Station s = IlsDataSVA.get(w).get(loc1);
            FlightCheck fc = d.check(loc1);
            check(s != null && fc.state == FlightCheck.DONE && fc.result == 3 && s.cat == 3 && (s.flags & LinerIls.F_UNCAL) == 0,
                    "airport world «calibration»: the plane down R1's glide path - inspection done (at tick " + calibAt + "), R1 CALIBRATED: "
                            + (s == null ? "-" : "CAT " + s.cat));
            appr = flyer(w, rx - 1000, rz - 30, Y + 1 + 45, 1.3, 3, 110, true);
            apprStart = n;
        }
        cat1Tick(n);
        apprTick(w, n);
        if (apprDone && !critDone) {
            critical(w, d, n);
        }
        if (critDone && !fgsDone) {
            falseGs(w, d, n);
        }
        if (fgsDone && !monDone) {
            monitor(w, d, n);
        }
        if (monDone) {
            phase = 9;
        }
    }

    // ---------------------------------------------------------------- build

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX - 6000;
        z0 = sp.posZ + 6000;
        rx = x0 + CARPET;
        rz = z0;
        rz2 = z0 + 400;
        loadArea(w);
        lay(w, true);
        // R1 / R2: the runway ends flush at both ends, facing each other
        end(w, rx, rz, 90);
        end(w, rx + LEN, rz, 270);
        end(w, rx, rz2, 90);
        end(w, rx + LEN, rz2, 270);
        // the antennas: the LOC 30 past the far threshold (placed looking west - its elements run along z), the G/S 25 in / 15 off
        TileEntityIlsAntennaSVA l1 = BlockIlsAntennaSVA.place(w, rx + LEN + 30, Y + 1, rz, BlockIlsAntennaSVA.LOC, 1, "IRA", 10950, 3);
        TileEntityIlsAntennaSVA g1 = BlockIlsAntennaSVA.place(w, rx + 25, Y + 1, rz + 15, BlockIlsAntennaSVA.GS, 1, "", 0, 3);
        TileEntityIlsAntennaSVA l2 = BlockIlsAntennaSVA.place(w, rx + LEN + 30, Y + 1, rz2, BlockIlsAntennaSVA.LOC, 1, "IRB", 11030, 3);
        TileEntityIlsAntennaSVA g2 = BlockIlsAntennaSVA.place(w, rx + 25, Y + 1, rz2 + 15, BlockIlsAntennaSVA.GS, 1, "", 0, 3);
        loc1 = l1 == null ? "" : l1.key();
        loc2 = l2 == null ? "" : l2.key();
        shelter(w, rx + LEN + 32, Y + 1, rz + 4);
        shelter(w, rx + 29, Y + 1, rz + 15);
        shelter(w, rx + LEN + 32, Y + 1, rz2 + 4);
        shelter(w, rx + 29, Y + 1, rz2 + 15);
        // R1's markers on the carpet, its RVR sensors, the weather station and the windsock
        device(w, rx - 720, rz, AirportDataSVA.K_MARKER);
        device(w, rx - 105, rz, AirportDataSVA.K_MARKER);
        device(w, rx - 30, rz, AirportDataSVA.K_MARKER);
        device(w, rx + 30, rz - 9, AirportDataSVA.K_RVR);
        device(w, rx + 150, rz - 9, AirportDataSVA.K_RVR);
        device(w, rx + 270, rz - 9, AirportDataSVA.K_RVR);
        device(w, rx + 60, rz - 16, AirportDataSVA.K_WX);
        device(w, rx + 64, rz - 14, AirportDataSVA.K_SOCK);
        // the old single beacon (legacy)
        // (away from the approaches, landing north: no test liner tunes it)
        w.setBlock(rx + 600, Y + 1, rz - 900, ModContentSVA.ils, 0, 3);
        TileEntity te = w.getTileEntity(rx + 600, Y + 1, rz - 900);
        if (te instanceof TileEntityIlsSVA) {
            ((TileEntityIlsSVA) te).st.elev = Y + 1;
            ((TileEntityIlsSVA) te).set(0, 3, "LGC", 11100, 0);
        }
        check(l1 != null && g1 != null && l2 != null && g2 != null && shelters.size() == 4 && te instanceof TileEntityIlsSVA,
                "airport world «build»: two 5 x " + LEN + " runways with their ends, LOC arrays, G/S masts, four shelters, markers, RVR, weather, an old beacon");
    }

    private void end(World w, int x, int z, double course) {
        w.setBlock(x, Y, z, ModContentSVA.runwayEnd, 0, 3);
        TileEntity te = w.getTileEntity(x, Y, z);
        if (te instanceof TileEntityRunwayEndSVA) {
            BlockRunwayEndSVA.setup(w, x, Y, z, (TileEntityRunwayEndSVA) te, course);
            TileEntityRunwayEndSVA e = (TileEntityRunwayEndSVA) te;
            e.set(course, W, -1, course == 90 && z == rz ? 1 : 0);
        }
    }

    private void shelter(World w, int x, int y, int z) {
        w.setBlock(x, y, z, ModContentSVA.ilsShelter, 0, 3);
        TileEntity te = w.getTileEntity(x, y, z);
        if (te instanceof TileEntityIlsShelterSVA) {
            shelters.add((TileEntityIlsShelterSVA) te);
            ((TileEntityIlsShelterSVA) te).fillForTest(100000);
        }
    }

    private void device(World w, int x, int z, int kind) {
        w.setBlock(x, Y + 1, z, ModContentSVA.aptDevice, kind, 3);
        TileEntity te = w.getTileEntity(x, Y + 1, z);
        if (te instanceof TileEntityAptDeviceSVA) {
            ((TileEntityAptDeviceSVA) te).register();
        }
    }

    /** The carpets (stone, 3 wide), the runways (runway blocks) and the planner's strip at Y; on false: air again. */
    private void lay(World w, boolean on) {
        Block stone = on ? Blocks.stone : Blocks.air, rw = on ? ModContentSVA.runway : Blocks.air;
        for (int x = x0; x < rx; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.setBlock(x, Y, rz + dz, stone, 0, 2);
                if (x >= rx - CARPET2) {
                    w.setBlock(x, Y, rz2 + dz, stone, 0, 2);
                }
            }
        }
        for (int x = rx + 1; x < rx + LEN; x++) {
            for (int dz = -W / 2; dz <= W / 2; dz++) {
                w.setBlock(x, Y, rz + dz, rw, 0, 2);
                w.setBlock(x, Y, rz2 + dz, rw, 0, 2);
            }
        }
        for (int x = rx + 100; x <= rx + 160; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.setBlock(x, Y, rz + 200 + dz, rw, 0, 2);
            }
        }
    }

    /** n = 80: what the pairing made of it. */
    private void build(World w, AirportDataSVA d) {
        IlsDataSVA ils = IlsDataSVA.get(w);
        List<LinerIls.Station> all = ils.near(w.provider.dimensionId, rx, rz, 1500);
        int full = 0, compact = 0;
        for (LinerIls.Station s : all) {
            full += s.full() ? 1 : 0;
            compact += s.full() ? 0 : 1;
        }
        LinerIls.Station s1 = ils.get(loc1), s2 = ils.get(loc2);
        // (R1 and the planner's strip 200 to the south are parallels: «09L» / «09R»)
        boolean paired = s1 != null && s2 != null && s1.full() && s1.rwy.startsWith("09") && s1.freq == 10950 && (s1.flags & LinerIls.F_NO_GS) == 0
                && s1.cat == 1 && (s1.flags & LinerIls.F_UNCAL) != 0 && s2.cat == 1;
        int strips = 0;
        IlsPairing.Rwy r3 = null;
        for (IlsPairing.Rwy r : d.runways()) {
            if (r.a.dim == w.provider.dimensionId && Math.abs(r.a.x - rx) < 1000 && Math.abs(r.a.z - rz) < 1000) {
                strips++;
                if (Math.abs((r.a.z + r.b.z) / 2 - (rz + 200.5)) < 1) {
                    r3 = r;
                }
            }
        }
        check(paired && full == 2 && compact == 1 && strips == 3,
                "airport world «build»: the ends make 3 runways (" + strips + "), the LOC + G/S pair into 2 FULL stations «09» beside the old COMPACT"
                        + " beacon; uncalibrated - CAT I (" + (s1 == null ? "-" : s1.rwy + " CAT " + s1.cat + " flags " + s1.flags) + "; " + full + " FULL, "
                        + compact + " COMPACT)");
        check(r3 != null && Math.abs(r3.len - 62) < 0.01 && r3.width == 3,
                "airport world «planner»: the tablet put the two ends at the strip's ends - a runway " + (r3 == null ? -1 : f(r3.len)) + " long, "
                        + (r3 == null ? -1 : r3.width) + " wide");
        LinerIls.Station lg = null;
        for (LinerIls.Station s : all) {
            lg = !s.full() ? s : lg;
        }
        check(lg != null && "LGC".equals(lg.ident) && lg.category() == 3, "airport world «legacy»: the old beacon stays in the ILS list (COMPACT, its"
                + " category " + (lg == null ? -1 : lg.category()) + " - airport.legacyIlsCat)");
    }

    /** n = 250: the devices (the RVR and the weather are measured every AirportDataSVA.WX_TICKS). */
    private void devices(World w, AirportDataSVA d) {
        int[] types = new int[3];
        int rvr = 0;
        for (AirportDataSVA.Device dv : d.devices(w.provider.dimensionId, AirportDataSVA.K_MARKER)) {
            if (dv.type >= 0) {
                types[dv.type]++;
            }
        }
        for (AirportDataSVA.Device dv : d.devices(w.provider.dimensionId, AirportDataSVA.K_RVR)) {
            rvr += dv.rvr >= 0 && dv.rwy.length() > 0 ? 1 : 0;
        }
        int[] rv = d.rvr(loc1);
        AirportDataSVA.Device wx = d.weatherNear(w.provider.dimensionId, rx, rz);
        check(types[0] == 1 && types[1] == 1 && types[2] == 1 && rvr == 3 && rv[0] > 0 && rv[1] > 0 && rv[2] > 0 && wx != null && !Double.isNaN(wx.qnh),
                "airport world «devices»: OM / MM / IM by their places, RVR TDZ " + rv[0] + " / MID " + rv[1] + " / END " + rv[2] + ", the weather station: "
                        + (wx == null ? "-" : (int) wx.windFrom + " deg " + f(wx.windSpeed) + " m/s, QNH " + f(wx.qnh)));
    }

    // ---------------------------------------------------------------- calibration

    private void spawnPlane(World w) {
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st);
        plane = ItemVehicleSVA.spawn(w, s, rx - 900, Y + 60, rz + 0.5, -90F, OWNER, "WorldTestAirport");
        System.out.println("[SVA-WORLDTEST] airport trace plane spawned " + (plane != null));
        if (plane != null) {
            plane.state.xpdr.mode = com.sva.airdefense.XpdrMath.C;
        }
    }

    /** The plane moved 2 blocks a tick down R1's glide path (the cone round the mast), to 10 past the threshold. */
    private void calibration(World w, AirportDataSVA d, int n) {
        if (plane == null || plane.isDead) {
            return;
        }
        double along = -900 + (n - 81) * 2.0;
        double x = rx + 0.5 + along, z = rz + 0.5;
        double hg = Math.hypot(x - (rx + 25.5), z - (rz + 15.5));
        double y = Y + 1 + hg * Math.tan(Math.toRadians(3)) + 0.2;
        plane.setPosition(x, y, z);
        plane.motionX = plane.motionY = plane.motionZ = 0;
        plane.rotationYaw = -90F;
        if (calibAt < 0 && d.check(loc1).state == FlightCheck.DONE) {
            calibAt = n;
        }
        if (along > 60) {
            plane.setDead();                                        // the inspection's last sample is over the runway
        }
    }

    // ---------------------------------------------------------------- the liners

    /** A liner in the air (the AFS test's flyer), its transponder on / off, the gear down, APPR + AP 1 + 2 a few ticks later. */
    private EntityVehicleSVA flyer(World w, double x, double z, double y, double speed, int flaps, double hdg, boolean xpdr) {
        VehicleState st = new VehicleState(VehicleType.LINER);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, s, x + 0.5, y, z + 0.5, (float) (hdg - 180), OWNER, "WorldTestAirport");
        if (v == null) {
            return null;
        }
        v.rotationYaw = v.prevRotationYaw = (float) (hdg - 180);
        LinerSystems L = L(v);
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.ADIRS1, 1);
        L.set(LinerControls.ADIRS2, 1);
        L.set(LinerControls.ADIRS3, 1);
        L.set(LinerControls.PARK_BRK, 0);
        L.set(LinerControls.FLAPS, flaps);
        L.fctl.flapPos = flaps;
        L.fctl.slatPos = flaps > 0 ? 1 : 0;
        L.set(LinerControls.GEAR, 1);
        L.gear.pos = 0;
        L.set(LinerControls.SPD_BRK, 0);
        L.set(LinerControls.LS_L, 1);
        v.state.xpdr.mode = xpdr ? com.sva.airdefense.XpdrMath.C : com.sva.airdefense.XpdrMath.OFF;
        v.debugLinerAir = false;
        v.speed = speed;
        v.motionY = 0;
        v.throttle = LinerAfs.DET_CL;
        L.afs.alt = (int) Math.round(y);
        v.debugDrive(PILOT);
        return v;
    }

    /** The liner's engines on (tick 3), the APs, A/THR, APPR, the autobrake (tick 6) - from its spawn tick `born`. */
    private static void engage(EntityVehicleSVA v, int sinceBorn) {
        if (v == null) {
            return;
        }
        if (sinceBorn == 3) {
            v.debugEngineManual = true;
        }
        if (sinceBorn == 6) {
            click(v, LinerControls.FCU_ATHR);
            click(v, LinerControls.ABRK_MED);
            click(v, LinerControls.FCU_AP1);
            click(v, LinerControls.FCU_APPR);
            click(v, LinerControls.FCU_AP2);
        }
    }

    private void cat1Tick(int n) {
        if (cat1 == null || c1Done || n < 80) {
            return;
        }
        engage(cat1, n - 80);
        if (n - 80 < 7) {
            c1Seq = L(cat1).afs.callSeq;
            return;
        }
        LinerAfs F = L(cat1).afs;
        c1Cap1 |= F.capability() == LinerAfs.CAP_CAT1 && F.ap1 && F.ap2;
        c1Land |= F.lat == LinerAfs.L_LAND || F.ver == LinerAfs.V_FLARE;
        if (F.callSeq != c1Seq) {
            c1Seq = F.callSeq;
            c1Min |= F.call == LinerAfs.CALL_MINIMUM;
        }
        double ra = L(cat1).lastAir == null ? -1 : L(cat1).lastAir.ra;
        if (!F.apOn() && n - 80 > 40) {
            c1OffRa = ra;
            c1Done = true;
            check(c1Cap1 && !c1Land && c1Min && ra > 0 && ra < 21 && F.apWarn == -1 && F.cavalry != 0,
                    "airport world «cat1»: on R2 (CAT I) AP 1 + 2 show CAT 1, no LAND; at the minimum (radio height " + f(ra) + ") «MINIMUM», the AP off with"
                            + " the red warning and the cavalry charge");
            cat1.dismountAll();
            cat1.setDead();
            return;
        }
        if (n - 80 > 1500 || cat1.isDead) {
            c1Done = true;
            check(false, "airport world «cat1»: the AP did not let go (ver " + F.verText() + ", lat " + F.latText() + ", ra " + f(ra) + ", cap "
                    + F.capability() + ")");
            cat1.dismountAll();
            cat1.setDead();
        }
    }

    private void apprTick(World w, int n) {
        if (appr == null || apprDone || apprStart < 0) {
            return;
        }
        int k = n - apprStart;
        engage(appr, k);
        if (k < 7) {
            bSeq = L(appr).afs.callSeq;
            return;
        }
        LinerSystems S = L(appr);
        LinerAfs F = S.afs;
        if ((F.thr == LinerAfs.T_RETARD || F.call == LinerAfs.CALL_RETARD) && appr.throttle > 0) {
            appr.throttle = 0;                                      // RETARD: the pilot's levers to idle
        }
        bDual |= F.capability() == LinerAfs.CAP_CAT3_DUAL;
        land = land < 0 && F.lat == LinerAfs.L_LAND ? k : land;
        flare = flare < 0 && F.ver == LinerAfs.V_FLARE ? k : flare;
        roll = roll < 0 && F.lat == LinerAfs.L_ROLLOUT ? k : roll;
        if (F.marker >= 0 && (markersSeen.isEmpty() || markersSeen.get(markersSeen.size() - 1).intValue() != F.marker)) {
            markersSeen.add(Integer.valueOf(F.marker));
        }
        bRvr |= F.rvr[0] > 0;
        bWind |= F.windFrom >= 0 && F.qnhApt > 900;
        boolean stopped = roll > 0 && appr.onGround && appr.speed < 0.01;
        if (stopped || k > 1900 || appr.isDead) {
            apprDone = true;
            LinerIls.Station st = F.ils;
            LinerIls.Dev d = st == null ? null : LinerIls.dev(st, appr.posX, appr.posY, appr.posZ, 4000);
            check(stopped && bDual && land > 0 && flare > land && roll > flare && d != null && Math.abs(d.along - 150) < 150 && Math.abs(appr.posZ - rz - 0.5) < 2
                            && appr.getHp() >= appr.getMaxHp() - 1e-3,
                    "airport world «autoland»: on R1 (CAT III) - CAT 3 DUAL, LAND " + land + ", FLARE " + flare + ", ROLL OUT " + roll + ", stopped "
                            + (d == null ? "-" : f(d.along)) + " past the threshold, " + f(appr.posZ - rz - 0.5) + " off the centre line, no damage");
            check(markersSeen.toString().equals("[" + MarkerMath.OM + ", " + MarkerMath.MM + ", " + MarkerMath.IM + "]") && bRvr && bWind,
                    "airport world «autoland»: the markers in order " + markersSeen + " (OM, MM, IM), the runway's RVR and the weather station's wind / QNH"
                            + " in the liner's systems");
            appr.dismountAll();
            appr.setDead();
            critAt = n;
        }
    }

    // ---------------------------------------------------------------- critical areas

    private void critical(World w, AirportDataSVA d, int n) {
        int k = n - critAt;
        if (k == 1) {
            w.setBlock(rx + 315, Y + 1, rz + 2, Blocks.stone, 0, 3);            // in the LOC's critical area
            w.setBlock(rx + 15, Y, rz + 10, Blocks.stone, 0, 3);                // the buggy's ground (under the scanned levels)
            VehicleState st = new VehicleState(VehicleType.BUGGY);
            st.energy = st.maxEnergy();
            ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.BUGGY.ordinal()), st);
            buggy = ItemVehicleSVA.spawn(w, s, rx + 15.5, Y + 1, rz + 10.5, 0F, OWNER, "WorldTestAirport");
        }
        if (k == 160) {
            LinerIls.Station s = IlsDataSVA.get(w).get(loc1);
            boolean ok = s != null && s.cat == 1 && s.bendLoc > 0 && s.bendGs > 0 && !s.off();
            check(ok, "airport world «critical»: a block in R1's LOC critical area and a buggy in its G/S one - CAT " + (s == null ? -1 : s.cat)
                    + ", the beams bend (LOC " + (s == null ? "-" : f(s.bendLoc)) + " deg, G/S " + (s == null ? "-" : f(s.bendGs)) + " deg)");
            w.setBlock(rx + 315, Y + 1, rz + 2, Blocks.air, 0, 3);
            if (buggy != null) {
                buggy.setDead();
            }
        }
        if (k == 320) {
            LinerIls.Station s = IlsDataSVA.get(w).get(loc1);
            check(s != null && s.cat == 3 && s.bendLoc == 0 && s.bendGs == 0, "airport world «critical»: the areas clear again - CAT "
                    + (s == null ? -1 : s.cat) + ", no bends");
            w.setBlock(rx + 15, Y, rz + 10, Blocks.air, 0, 3);
            critDone = true;
            fgsAt = n;
        }
    }

    // ---------------------------------------------------------------- the false glide slope and the monitor

    private void falseGs(World w, AirportDataSVA d, int n) {
        int k = n - fgsAt;
        if (k == 1) {
            double hg = Math.hypot(445, 15);
            // R2 first, R1 if R2's approach has no room (terrain / a tree at that height)
            for (int tries = 0; tries < 8 && fgs == null; tries++) {
                int zz = tries < 4 ? rz2 : rz;
                fgsLoc = tries < 4 ? loc2 : loc1;
                fgs = flyer(w, rx - 420 + (tries % 4) * 4, zz + (tries % 2) * 2, Y + 1 + hg * Math.tan(Math.toRadians(9.3)) - (tries % 4), 1.3, 3, 90,
                        false);
            }
            System.out.println("[SVA-WORLDTEST] airport trace false G/S liner " + (fgs != null) + " on " + fgsLoc + "; terrain top R2 "
                    + w.getHeightValue(rx - 420, rz2) + ", R1 " + w.getHeightValue(rx - 420, rz));
        }
        if (k < 1) {
            return;                                                 // the tick the critical areas ended: the liner comes next tick
        }
        if (fgs == null) {
            fgsDone = true;
            check(false, "airport world «false G/S»: no liner (no room to spawn at " + (rx - 420) + " " + (rz2) + ")");
            int[] p = parse(fgsLoc);
            if (p != null) {
                w.setBlock(p[0], p[1], p[2] + 1, Blocks.air, 0, 3);
            }
            fgsAt = n;
            return;
        }
        engage(fgs, k - 1);
        LinerAfs F = L(fgs).afs;
        if (F.ils != null && (F.ver == LinerAfs.V_GS || F.ver == LinerAfs.V_GS_CAP)) {
            LinerIls.Dev dv = LinerIls.dev(F.ils, fgs.posX, fgs.posY, fgs.posZ, 4000, w.getTotalWorldTime());
            fgsGs = fgsGs < 0 ? k : fgsGs;
            lobe3 += dv.lobe == 3 ? 1 : 0;
        }
        if (k >= 200) {
            check(fgsGs > 0 && lobe3 >= 30, "airport world «false G/S»: 9.3 deg up over the glide slope, G/S captured from above (tick " + fgsGs
                    + ") holds the 9 deg lobe for " + lobe3 + " ticks - the false path, detectable");
            fgsDone = true;
            // the monitor: an element of R2's localizer broken
            int[] p = parse(fgsLoc);
            if (p != null) {
                w.setBlock(p[0], p[1], p[2] + 1, Blocks.air, 0, 3);
            }
            fgsAt = n;
        }
    }

    private void monitor(World w, AirportDataSVA d, int n) {
        int k = n - fgsAt;
        if (k == 60) {
            LinerIls.Station s = IlsDataSVA.get(w).get(fgsLoc);
            LinerAfs F = fgs == null ? null : L(fgs).afs;
            LinerIls.Dev dv = F == null || F.ils == null ? null : LinerIls.dev(F.ils, fgs.posX, fgs.posY, fgs.posZ, 4000);
            boolean ecam = fgs != null && L(fgs).ecam.isActive(LinerEcam.M_ILS_FAULT);
            check(s != null && s.off() && (s.flags & LinerIls.F_OFF) != 0 && F != null && F.ilsFault() && dv != null && dv.locFail && dv.gsFail && ecam,
                    "airport world «monitor»: an element of the localizer broken - the monitor shut it off (flags " + (s == null ? -1 : s.flags)
                            + "); the liner keeps it tuned with the red LOC / G/S flags and the ECAM's «ILS ОТКАЗ»");
            if (fgs != null) {
                fgs.dismountAll();
                fgs.setDead();
            }
            drained = shelters.get(0);                              // R1's localizer shelter
            drained.drainForTest();
        }
        if (k == 140) {
            LinerIls.Station s = IlsDataSVA.get(w).get(loc1);
            check(s != null && s.off() && (s.flags & LinerIls.F_NO_POWER) != 0 && !drained.isPowered(),
                    "airport world «monitor»: R1's localizer shelter without EU - no power, the station off (flags " + (s == null ? -1 : s.flags) + ")");
            monDone = true;
        }
    }

    static int[] parse(String key) {
        String[] k = key.split(":");
        if (k.length != 4) {
            return null;
        }
        return new int[] {Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3])};
    }

    // ---------------------------------------------------------------- chunks, cleanup

    private void loadArea(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer)) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (x0 - 16) >> 4; cx <= (rx + LEN + 48) >> 4; cx++) {
            for (int cz = (rz - 32) >> 4; cz <= (rz + 32) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
            if (cx >= (rx - CARPET2 - 16) >> 4) {
                for (int cz = (rz2 - 32) >> 4; cz <= (rz2 + 32) >> 4; cz++) {
                    cp.loadChunk(cx, cz);
                }
            }
        }
        for (int cx = (rx + 80) >> 4; cx <= (rx + 180) >> 4; cx++) {
            for (int cz = (rz + 190) >> 4; cz <= (rz + 210) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
        cp.loadChunk((rx + 600) >> 4, (rz - 900) >> 4);
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        if (w.getTotalWorldTime() % 100 == 0) {
            loadArea(w);                                            // the whole area stays loaded (the areas' scans, the flights)
        }
        // the stations' antennas and shelters
        for (int[] p : new int[][] {{rx + LEN + 31, rz}, {rx + 27, rz + 15}, {rx + LEN + 31, rz2}, {rx + 27, rz2 + 15}}) {
            cp.loadChunk(p[0] >> 4, p[1] >> 4);
        }
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {plane, cat1, appr, fgs, buggy}) {
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
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {plane, cat1, appr, fgs, buggy}) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        if (x0 != 0 || z0 != 0) {
            int[][] blocks = {{rx, Y, rz}, {rx + LEN, Y, rz}, {rx, Y, rz2}, {rx + LEN, Y, rz2}, {rx + LEN + 30, Y + 1, rz}, {rx + 25, Y + 1, rz + 15},
                {rx + LEN + 30, Y + 1, rz2}, {rx + 25, Y + 1, rz2 + 15}, {rx + LEN + 32, Y + 1, rz + 4}, {rx + 29, Y + 1, rz + 15},
                {rx + LEN + 32, Y + 1, rz2 + 4}, {rx + 29, Y + 1, rz2 + 15}, {rx - 720, Y + 1, rz}, {rx - 105, Y + 1, rz}, {rx - 30, Y + 1, rz},
                {rx + 30, Y + 1, rz - 9}, {rx + 150, Y + 1, rz - 9}, {rx + 270, Y + 1, rz - 9}, {rx + 60, Y + 1, rz - 16}, {rx + 64, Y + 1, rz - 14},
                {rx + 600, Y + 1, rz - 900}, {rx + 99, Y, rz + 200}, {rx + 161, Y, rz + 200}, {rx + 315, Y + 1, rz + 2}, {rx + 15, Y, rz + 10}};
            for (int[] b : blocks) {
                w.setBlock(b[0], b[1], b[2], Blocks.air, 0, 3);
            }
            lay(w, false);
        }
        VehicleRegistrySVA reg = VehicleRegistrySVA.get(w);
        if (reg != null) {
            reg.removeOwner(OWNER);
        }
        phase = 10;
        done = true;
    }

    void abort(World w) {
        try {
            finish(w);
        } catch (Throwable t2) {
            done = true;
        }
    }
}
