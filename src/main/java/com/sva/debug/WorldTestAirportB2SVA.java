package com.sva.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sva.ConfigSVA;
import com.sva.airdefense.AirDefenseDataSVA;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.airport.ApproachMath;
import com.sva.airport.AtisMath;
import com.sva.airport.AutoTower;
import com.sva.airport.ChartMath;
import com.sva.airport.Cpdlc;
import com.sva.airport.IlsPairing;
import com.sva.airport.TaxiGraph;
import com.sva.block.AirportDataSVA;
import com.sva.block.AtcServerSVA;
import com.sva.block.BlockIlsAntennaSVA;
import com.sva.block.BlockRunwayEndSVA;
import com.sva.block.BlockTowerSVA;
import com.sva.block.IlsDataSVA;
import com.sva.block.NavDataSVA;
import com.sva.block.TileEntityIlsAntennaSVA;
import com.sva.block.TileEntityIlsShelterSVA;
import com.sva.block.TileEntityRunwayEndSVA;
import com.sva.block.TileEntityTowerSVA;
import com.sva.block.TowerScreenSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.entity.LinerLogicSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemApproachChartSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerFms;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerMcdu;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.NavDb;
import com.sva.vehicle.VehicleRegistrySVA;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

/**
 * Airport wave B2 world test (plan-next §9, items 27, 29-34): its own airport (spawn - 6000 / + 10000, a platform at Y 150, its
 * own owner) - a B1 runway «09» / «27» with a localizer, a glide slope mast, their shelters and a weather station, a control
 * tower beside it, a taxiway of «Огонь рулёжки» from two stands to a stop bar at the holding point of 09:
 * <ul>
 * <li>«tower»: the tower names its airport - the runway, the ILS station and the MCDU's database (UBTW with the ILS09
 * approach and the entry fixes), a second tower can't take the same code; the air-defence «аэродром» zone;</li>
 * <li>«ATIS»: information A once the tower is online, fog - B;</li>
 * <li>«taxi»: a light plane at a stand asks for taxi - the automatic tower clears it with a route, the green lights go on
 * ahead of it and off behind as it moves; the stop bar burns at the holding point and goes out for the take-off clearance;</li>
 * <li>«landing»: a liner on the final asks to land by CPDLC - cleared (the uplink, its «ATC MSG», the MCDU's message page,
 * WILCO back to the tower); a plane put on the runway - the liner is sent around below the go-around height;</li>
 * <li>«controller»: a player in the controller seat - the automatic tower stands down;</li>
 * <li>«approach»: an SA-320 whose MCDU inserts the ILS09 approach (LAT REV - ЗАХОДЫ - INSERT): FF09 / CI09 / MA09 with
 * their constraints, the ILS tuned from the plan, the CAT III autoland on it;</li>
 * <li>«chart»: the tower prints the approach chart (paper), its version; the airport renamed - the chart is outdated.</li>
 * </ul>
 */
final class WorldTestAirportB2SVA {

    private static final String OWNER = "00000000-5bad-4e57-0000-0000a1290b02";
    static final int START = 3300;
    private static final int Y = 150, LEN = 301, W = 5, MAX_TICKS = 4200;
    private static final int PILOT = EntityVehicleSVA.IN_CRUISE;

    private final WorldTestSVA t;
    private int phase, t0, x0, z0, rx, rz;
    boolean done;
    private final List<TileEntityIlsShelterSVA> shelters = new ArrayList<TileEntityIlsShelterSVA>();
    private TileEntityTowerSVA tower, tower2;
    private String loc = "", stopKey = "";
    private final List<int[]> taxiCells = new ArrayList<int[]>();
    private int edges;
    private EntityVehicleSVA taxiPlane, l1, blocker, l2;
    private int savedFog;
    // the stages
    private int taxiAt = -1, taxiStep, l1At = -1, l2At = -1, chartAt = -1;
    private boolean taxiDone, landDone, apprDone, chartDone, ctlDone, l1Req, l1Wilco, gaSeen;
    private int land = -1, flare = -1, roll = -1;
    private boolean dual, tuned;
    private List<String> litStart = new ArrayList<String>();
    private Map<String, String> ru;

    WorldTestAirportB2SVA(WorldTestSVA t) {
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
            if (!s.isInvalid()) {
                s.fillForTest(s.getMaxEnergyStored());
            }
        }
        if (tower != null && !tower.isInvalid()) {
            tower.fillForTest();
        }
        if (tower2 != null && !tower2.isInvalid()) {
            tower2.fillForTest();
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

    // ---------------------------------------------------------------- the run

    private void run(World w, int n) {
        AirportDataSVA d = AirportDataSVA.get(w);
        if (d == null || n > 40 && tower == null) {
            check(false, "airport B2 world: no airport data / no tower");
            phase = 9;
            return;
        }
        if (n == 15) {
            // the ILS flight-checked already (B1's inspection has its own test): CAT III before the tower opens
            com.sva.airport.FlightCheck fc = d.check(loc);
            fc.state = com.sva.airport.FlightCheck.DONE;
            fc.result = 3;
            fc.hint = -1;
        }
        if (n == 40) {
            // the airport is built (its ILS monitored, powered): now the towers
            tower = BlockTowerSVA.place(w, rx + 150, Y + 1, rz - 40, 0, "WorldTestAirportB2");
            tower2 = BlockTowerSVA.place(w, rx + 150, Y + 1, rz - 1700, 0, "WorldTestAirportB2");
            check(tower != null && tower2 != null, "airport B2 world «build»: two towers placed (" + (tower != null) + " / " + (tower2 != null) + ")");
            return;
        }
        if (tower == null) {
            return;
        }
        AirportDataSVA.Tower tw = d.tower(tower.key());
        long now = w.getTotalWorldTime();
        if (n == 45) {
            name(w, d);
        }
        if (n == 100) {
            towerChecks(w, d, tw);
        }
        if (n == 170) {
            atisA(w, d, tw);
            zoneCheck(w);
            savedFog = ConfigSVA.aptFog;
            ConfigSVA.aptFog = 2;                                   // the weather turns: fog
        }
        if (n == 300) {
            int letter = tw == null ? -1 : tw.letter;
            check(letter == 1 && tw.atis != null && tw.atis.vis < 1000,
                    "airport B2 world «ATIS»: fog - a new information: " + (letter >= 0 ? "" + (char) ('A' + letter) : "-") + " (visibility "
                            + (tw == null || tw.atis == null ? -1 : tw.atis.vis) + " m-eq)");
            ConfigSVA.aptFog = savedFog;
            taxiAt = n;
            spawnTaxiPlane(w);
        }
        if (taxiAt > 0 && !taxiDone) {
            taxi(w, d, tw, n, now);
        }
        if (taxiDone && l1At < 0) {
            l1At = n;
            l1 = flyer(w, rx - 1000, rz - 30, Y + 1 + 45, 1.3, 3, 110, true);
        }
        if (l1At > 0 && !landDone) {
            landing(w, d, tw, n, now);
        }
        if (landDone && !ctlDone) {
            controller(w, d, tw, n, now);
        }
        if (ctlDone && l2At < 0) {
            l2At = n;
            l2 = flyer(w, rx - 1000, rz - 30, Y + 1 + 45, 1.3, 3, 110, true);
        }
        if (l2At > 0 && !apprDone) {
            approach(w, d, tw, n, now);
        }
        if (apprDone && !chartDone) {
            chart(w, d, tw, n);
        }
        if (chartDone) {
            phase = 9;
        }
    }

    // ---------------------------------------------------------------- build

    private void setup(World w) {
        ChunkCoordinates sp = w.getSpawnPoint();
        x0 = sp.posX - 6000;
        z0 = sp.posZ + 10000;
        rx = x0 + 1300;
        rz = z0;
        loadArea(w);
        lay(w, true);
        end(w, rx, rz, 90);
        end(w, rx + LEN, rz, 270);
        TileEntityIlsAntennaSVA l = BlockIlsAntennaSVA.place(w, rx + LEN + 30, Y + 1, rz, BlockIlsAntennaSVA.LOC, 1, "IBT", 10970, 3);
        TileEntityIlsAntennaSVA g = BlockIlsAntennaSVA.place(w, rx + 25, Y + 1, rz + 15, BlockIlsAntennaSVA.GS, 1, "", 0, 3);
        loc = l == null ? "" : l.key();
        shelter(w, rx + LEN + 32, Y + 1, rz + 4);
        shelter(w, rx + 29, Y + 1, rz + 15);
        w.setBlock(rx + 60, Y + 1, rz - 16, ModContentSVA.aptDevice, AirportDataSVA.K_WX, 3);
        TileEntity te = w.getTileEntity(rx + 60, Y + 1, rz - 16);
        if (te instanceof com.sva.block.TileEntityAptDeviceSVA) {
            ((com.sva.block.TileEntityAptDeviceSVA) te).register();
        }
        // the taxiway: two stands (east), the centre line west along rz - 30, then south to the stop bar at the holding point
        taxi(w, rx + 66, rz - 30, com.sva.block.BlockTaxiLightSVA.STAND);
        taxi(w, rx + 96, rz - 30, com.sva.block.BlockTaxiLightSVA.STAND);
        for (int x = rx + 93; x >= rx + 12; x -= 3) {
            if (x != rx + 66) {
                taxi(w, x, rz - 30, com.sva.block.BlockTaxiLightSVA.CENTRE);
            }
        }
        for (int z = rz - 27; z <= rz - 13; z += 3) {
            taxi(w, rx + 12, z, com.sva.block.BlockTaxiLightSVA.CENTRE);
        }
        taxi(w, rx + 12, rz - 10, com.sva.block.BlockTaxiLightSVA.STOP);
        stopKey = AirportDataSVA.key(w, rx + 12, Y + 1, rz - 10);
        for (int x = rx + 20; x <= rx + 60; x += 8) {
            taxi(w, x, rz - 32, com.sva.block.BlockTaxiLightSVA.EDGE);
            edges++;
        }
        check(l != null && g != null && shelters.size() == 2 && taxiCells.size() > 30,
                "airport B2 world «build»: a runway with its ILS, its shelters, a weather station, " + taxiCells.size() + " taxi lights");
    }

    private void taxi(World w, int x, int z, int kind) {
        w.setBlock(x, Y + 1, z, ModContentSVA.taxiLight, kind, 3);
        com.sva.block.BlockTaxiLightSVA.register(w, x, Y + 1, z);
        taxiCells.add(new int[] {x, z});
    }

    private void end(World w, int x, int z, double course) {
        w.setBlock(x, Y, z, ModContentSVA.runwayEnd, 0, 3);
        TileEntity te = w.getTileEntity(x, Y, z);
        if (te instanceof TileEntityRunwayEndSVA) {
            BlockRunwayEndSVA.setup(w, x, Y, z, (TileEntityRunwayEndSVA) te, course);
            ((TileEntityRunwayEndSVA) te).set(course, W, com.sva.airport.RunwayLayout.LIGHTS_TOWER, course == 90 ? 1 : 0);
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

    /** The platform (stone) round the runway and the taxiway, the runway's blocks; on false: air again. */
    private void lay(World w, boolean on) {
        Block stone = on ? Blocks.stone : Blocks.air, rw = on ? ModContentSVA.runway : Blocks.air;
        for (int x = rx - 1100; x < rx; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.setBlock(x, Y, rz + dz, stone, 0, 2);
            }
        }
        for (int x = rx + 1; x < rx + LEN; x++) {
            for (int dz = -W / 2; dz <= W / 2; dz++) {
                w.setBlock(x, Y, rz + dz, rw, 0, 2);
            }
        }
        for (int x = rx + 8; x <= rx + 100; x++) {
            for (int z = rz - 34; z <= rz - 26; z++) {
                w.setBlock(x, Y, z, stone, 0, 2);
            }
        }
        for (int x = rx + 8; x <= rx + 16; x++) {
            for (int z = rz - 26; z <= rz - 3; z++) {
                w.setBlock(x, Y, z, stone, 0, 2);
            }
        }
    }

    // ---------------------------------------------------------------- the tower

    private net.minecraftforge.common.util.FakePlayer fake(World w) {
        net.minecraftforge.common.util.FakePlayer fp = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft((net.minecraft.world.WorldServer) w);
        fp.setPosition(rx + 150, Y + 2, rz - 38);
        return fp;
    }

    private void set(World w, TileEntityTowerSVA te, String icao, String name) {
        net.minecraftforge.common.util.FakePlayer fp = fake(w);
        te.setOwnerName(fp.getCommandSenderName());
        NBTTagCompound c = new NBTTagCompound();
        c.setString("Op", "set");
        c.setString("Icao", icao);
        c.setString("Name", name);
        TowerScreenSVA.command(fp, te, c);
    }

    private void name(World w, AirportDataSVA d) {
        set(w, tower, "UBTW", "Test");
        set(w, tower2, "UBTW", "");
        String refused = d.tower(tower2.key()).icao;
        set(w, tower2, "ZZAB", "");
        String refusedZz = d.tower(tower2.key()).icao;
        set(w, tower2, "UBTX", "Second");
        NBTTagCompound c = new NBTTagCompound();
        c.setString("Op", "rwy");
        c.setString("K", AirportDataSVA.key(w, rx, Y, rz));                // the runway in use: 09 (not by the wind)
        TowerScreenSVA.command(fake(w), tower, c);
        check("UBTW".equals(d.tower(tower.key()).icao) && !"UBTW".equals(refused) && !"ZZAB".equals(refusedZz) && "UBTX".equals(d.tower(tower2.key()).icao),
                "airport B2 world «tower»: the first tower is UBTW; the second can't take UBTW (" + refused + ") nor a ZZ placeholder (" + refusedZz
                        + "), takes UBTX");
    }

    private void towerChecks(World w, AirportDataSVA d, AirportDataSVA.Tower tw) {
        IlsPairing.Rwy r = null;
        for (IlsPairing.Rwy x : d.runways()) {
            if (Math.abs(x.a.z - (rz + 0.5)) < 1 && (Math.abs(x.a.x - (rx + 0.5)) < 2 || Math.abs(x.b.x - (rx + 0.5)) < 2)) {
                r = x;
            }
        }
        LinerIls.Station s = IlsDataSVA.get(w).get(loc);
        NavDb db = NavDataSVA.get(w).db(w, w.provider.dimensionId, null);
        NavDb.Airport a = db.airport("UBTW");
        ApproachMath.Approach ap = a == null ? null : a.approach("ILS09");
        int entries = 0;
        for (String sfx : ApproachMath.ENTRY_SFX) {
            entries += db.wpt("UBTW" + sfx) != null ? 1 : 0;
        }
        check(r != null && "UBTW".equals(r.apt) && s != null && "UBTW".equals(s.apt) && a != null && a.src == NavDb.SRC_TOWER && "Test".equals(a.name)
                        && ap != null && entries == 4 && tw != null && tw.rwys.size() == 1,
                "airport B2 world «tower»: the runway / the ILS / the MCDU's database name the airport UBTW (" + (r == null ? "-" : r.apt) + " / "
                        + (s == null ? "-" : s.apt) + " / " + (a == null ? "-" : a.ident + " " + NavDb.SRC_KEYS[a.src]) + "), its approach ILS09 "
                        + (ap != null) + ", 4 entry fixes (" + entries + ")");
    }

    private void zoneCheck(World w) {
        NavDb db = NavDataSVA.get(w).db(w, w.provider.dimensionId, null);
        ZoneMath.Zone z = null;
        for (ZoneMath.Zone x : AirDefenseDataSVA.get(w).all()) {
            if ("UBTW".equals(x.name)) {
                z = x;
            }
        }
        check(z != null && z.type == ZoneMath.AERODROME && z.codeLo == 04100 && z.codeHi == 04177 && z.r >= 96
                        && Math.hypot(z.cx - (rx + 150), z.cz - rz) < 3 && !db.airports.isEmpty() && db.airport("UBTW").src == NavDb.SRC_TOWER,
                "airport B2 world «zone»: the air defence got the «аэродром» zone UBTW round the runway (r " + (z == null ? -1 : z.r) + ", codes "
                        + (z == null ? "-" : XpdrMath.format(z.codeLo) + "-" + XpdrMath.format(z.codeHi)) + "), the MCDU keeps the tower's airport");
    }

    private void atisA(World w, AirportDataSVA d, AirportDataSVA.Tower tw) {
        boolean online = tw != null && tw.online(w.getTotalWorldTime());
        String text = tw == null ? "" : tw.atisText;
        check(online && tw.letter == 0 && text.contains("sva.atis.rwy\u000109") && text.contains("sva.atis.apprIls"),
                "airport B2 world «ATIS»: the tower online - information A, runway 09, ILS approach (" + (tw == null ? "-" : tw.letter + " " + tw.inUse) + ")");
    }

    // ---------------------------------------------------------------- the taxi

    private void spawnTaxiPlane(World w) {
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st);
        taxiPlane = ItemVehicleSVA.spawn(w, s, rx + 66.5, Y + 1.2, rz - 29.5, 90F, OWNER, "WorldTestAirportB2");
        if (taxiPlane != null) {
            taxiPlane.state.xpdr.mode = XpdrMath.C;
        }
    }

    @SuppressWarnings("unchecked")
    private void taxi(World w, AirportDataSVA d, AirportDataSVA.Tower tw, int n, long now) {
        int k = n - taxiAt;
        AtcServerSVA.Session s = taxiPlane == null ? null : AtcServerSVA.INSTANCE.session(taxiPlane.getEntityId());
        if (k == 40) {
            boolean sent = s != null && AtcServerSVA.INSTANCE.downlink(w, d, taxiPlane, Cpdlc.D_TAXI, 0, "", now);
            if (!sent) {
                check(false, "airport B2 world «taxi»: no session / downlink for the taxiing plane (" + (s != null) + ")");
                taxiDone = true;
            }
            return;
        }
        if (k == 80) {
            Cpdlc.Msg up = null;
            if (s != null) {
                for (Cpdlc.Msg m : s.log.msgs) {
                    if (m.up && m.type == Cpdlc.U_TAXI) {
                        up = m;
                    }
                }
            }
            litStart = AtcServerSVA.INSTANCE.litNow(w, d, now);
            check(up != null && up.s.equals("09") && up.s2.length() > 0 && s.route != null && s.route.size() > 15 && litStart.size() >= 5
                            && litStart.size() <= ConfigSVA.aptTaxiAhead + 1 && AtcServerSVA.INSTANCE.stopLitNow(w, d, stopKey, now),
                    "airport B2 world «taxi»: asked to taxi - cleared «" + (up == null ? "-" : up.s + " " + up.s2 + " m") + "» with a route of "
                            + (s == null || s.route == null ? 0 : s.route.size()) + " lights; " + litStart.size() + " lit ahead; the stop bar burns");
            taxiStep = 0;
            return;
        }
        if (k > 80 && s != null && s.route != null && k % 6 == 0) {
            // the plane moved along its route node by node (taxiing)
            taxiStep++;
            Object[] g = d.taxi(w.provider.dimensionId);
            List<TaxiGraph.Node> nodes = (List<TaxiGraph.Node>) g[0];
            if (taxiStep < s.route.size() - 2) {
                int i = TaxiGraph.index(nodes, s.route.get(taxiStep));
                if (i >= 0) {
                    taxiPlane.setPosition(nodes.get(i).x + 0.5, Y + 1.2, nodes.get(i).z + 0.5);
                    taxiPlane.motionX = taxiPlane.motionZ = 0;
                }
                if (taxiStep == 8) {
                    List<String> lit = AtcServerSVA.INSTANCE.litNow(w, d, now);
                    String first = s.route.get(0), ahead = s.route.get(Math.min(s.route.size() - 1, 8 + ConfigSVA.aptTaxiAhead / 2));
                    check(!lit.contains(first) && lit.contains(ahead) && lit.contains(s.route.get(8)) && litStart.contains(first),
                            "airport B2 world «taxi»: 8 lights on - the ones behind went out, the next ones ahead lit (" + lit.size() + " lit)");
                }
                return;
            }
        }
        if (k > 80 && s != null && s.route != null && taxiStep >= s.route.size() - 2) {
            // two lights before the stop bar: still red; then up to the holding point and ready
            boolean red = AtcServerSVA.INSTANCE.stopLitNow(w, d, stopKey, now);
            taxiPlane.setPosition(rx + 12.5, Y + 1.2, rz - 9.5);
            check(red, "airport B2 world «taxi»: the stop bar burns as the plane comes up to the holding point");
            AtcServerSVA.INSTANCE.downlink(w, d, taxiPlane, Cpdlc.D_READY, 0, "09", now);
            s.route = null;
            taxiStep = 1000;
            return;
        }
        if (taxiStep >= 1000) {
            taxiStep++;
            Cpdlc.Msg to = null;
            for (Cpdlc.Msg m : s == null ? new ArrayList<Cpdlc.Msg>() : s.log.msgs) {
                if (m.up && m.type == Cpdlc.U_TAKEOFF) {
                    to = m;
                }
            }
            if (to != null || taxiStep > 1100) {
                boolean red = AtcServerSVA.INSTANCE.stopLitNow(w, d, stopKey, now);
                check(to != null && !red && s.ac.st == AutoTower.S_TAKEOFF,
                        "airport B2 world «taxi»: ready at the holding point - ЗАНИМАЙТЕ ПОЛОСУ / ВЗЛЁТ РАЗРЕШАЮ " + (to == null ? "-" : to.s)
                                + "; the stop bar went out (" + !red + ")");
                taxiPlane.setDead();
                taxiDone = true;
            }
        }
    }

    // ---------------------------------------------------------------- the landing, the go-around

    /** A liner in the air (B1's flyer). */
    private EntityVehicleSVA flyer(World w, double x, double z, double y, double speed, int flaps, double hdg, boolean xpdr) {
        VehicleState st = new VehicleState(VehicleType.LINER);
        st.fillAllGases();
        st.energy = st.maxEnergy();
        ItemStack s = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.LINER.ordinal()), st);
        EntityVehicleSVA v = ItemVehicleSVA.spawn(w, s, x + 0.5, y, z + 0.5, (float) (hdg - 180), OWNER, "WorldTestAirportB2");
        if (v == null) {
            return null;
        }
        v.rotationYaw = v.prevRotationYaw = (float) (hdg - 180);
        LinerSystems Ls = L(v);
        Ls.set(LinerControls.BAT1, 1);
        Ls.set(LinerControls.BAT2, 1);
        Ls.set(LinerControls.ADIRS1, 1);
        Ls.set(LinerControls.ADIRS2, 1);
        Ls.set(LinerControls.ADIRS3, 1);
        Ls.set(LinerControls.PARK_BRK, 0);
        Ls.set(LinerControls.FLAPS, flaps);
        Ls.fctl.flapPos = flaps;
        Ls.fctl.slatPos = flaps > 0 ? 1 : 0;
        Ls.set(LinerControls.GEAR, 1);
        Ls.gear.pos = 0;
        Ls.set(LinerControls.SPD_BRK, 0);
        Ls.set(LinerControls.LS_L, 1);
        v.state.xpdr.mode = xpdr ? XpdrMath.C : XpdrMath.OFF;
        v.debugLinerAir = false;
        v.speed = speed;
        v.motionY = 0;
        v.throttle = LinerAfs.DET_CL;
        Ls.afs.alt = (int) Math.round(y);
        v.debugDrive(PILOT);
        return v;
    }

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

    LinerMcdu.Resolver resolver() {
        if (ru == null) {
            try {
                ru = LangKeysSVA.map(LangKeysSVA.RU);
            } catch (java.io.IOException e) {
                ru = new java.util.HashMap<String, String>();
            }
        }
        return new LinerMcdu.Resolver() {
            @Override
            public String tr(String key, String[] args) {
                String v = ru.get(key);
                if (v == null) {
                    return "?" + key;
                }
                try {
                    return String.format(v, (Object[]) args);
                } catch (RuntimeException ex) {
                    return v;
                }
            }
        };
    }

    private void landing(World w, AirportDataSVA d, AirportDataSVA.Tower tw, int n, long now) {
        int k = n - l1At;
        if (l1 == null) {
            check(false, "airport B2 world «landing»: no liner");
            landDone = true;
            return;
        }
        engage(l1, k);
        AtcServerSVA.Session s = AtcServerSVA.INSTANCE.session(l1.getEntityId());
        if (!l1Req && s != null && k > 12) {
            l1Req = true;
            LinerMcdu.Env e = LinerLogicSVA.mcduEnv(l1, L(l1));
            // the MCDU: СВЯЗЬ - ЗАПРОС - ЗАПРОС ПОСАДКИ
            LinerLogicSVA.mcduKeyServer(l1, 0, LinerMcdu.K_ATC);
            LinerLogicSVA.mcduKeyServer(l1, 0, LinerMcdu.K_L1 + 2);
            LinerLogicSVA.mcduKeyServer(l1, 0, LinerMcdu.K_L1 + 2);
            boolean sent = s.log.openRequest(Cpdlc.D_LANDING) != null;
            check(sent && e.atc != null && "UBTW".equals(e.atc.unit()), "airport B2 world «landing»: the liner's MCDU (СВЯЗЬ - ЗАПРОС) asks UBTW to land ("
                    + sent + ", unit " + (e.atc == null ? "-" : e.atc.unit()) + ")");
            return;
        }
        if (l1Req && !l1Wilco && s != null) {
            Cpdlc.Msg land0 = null;
            for (Cpdlc.Msg m : s.log.msgs) {
                if (m.up && m.type == Cpdlc.U_LAND) {
                    land0 = m;
                }
            }
            if (land0 != null) {
                l1Wilco = true;
                int unread = L(l1).atcUnread;
                LinerLogicSVA.mcduKeyServer(l1, 0, LinerMcdu.K_ATC);
                LinerLogicSVA.mcduKeyServer(l1, 0, LinerMcdu.K_R1 + 1);       // СООБЩЕНИЯ>
                LinerMcdu.Env e = LinerLogicSVA.mcduEnv(l1, L(l1));
                String screen = L(l1).mcdu.render(L(l1), 0, e).plain(resolver());
                LinerLogicSVA.mcduKeyServer(l1, 0, LinerMcdu.K_L1 + 4);       // <ПРИНЯЛ
                Cpdlc.Msg tm = null;
                for (Cpdlc.Msg m : AtcServerSVA.INSTANCE.towerLog(tw.key).msgs) {
                    if (!m.up && m.type == Cpdlc.D_WILCO && m.aircraft == l1.getEntityId()) {
                        tm = m;
                    }
                }
                check(unread > 0 && screen.contains("ПОСАДКУ РАЗРЕШАЮ") && land0.state == Cpdlc.S_WILCO && tm != null && s.ac.st == AutoTower.S_LAND,
                        "airport B2 world «landing»: the automatic tower clears it to land (" + (k) + " ticks), «ATC MSG» lit (" + unread
                                + " unread), the MCDU shows «ПОСАДКУ РАЗРЕШАЮ ПОЛОСА 09», ПРИНЯЛ reaches the tower");
                if (!screen.contains("ПОСАДКУ РАЗРЕШАЮ")) {
                    System.out.println("[SVA-WORLDTEST] B2 MCDU screen:\n" + screen);
                }
                // a plane on the runway: the arrival must go around
                VehicleState st = new VehicleState(VehicleType.PLANE);
                st.energy = st.maxEnergy();
                ItemStack is = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.PLANE.ordinal()), st);
                blocker = ItemVehicleSVA.spawn(w, is, rx + 120.5, Y + 1.2, rz + 0.5, -90F, OWNER, "WorldTestAirportB2");
            }
        }
        if (l1Wilco && s != null && !gaSeen) {
            for (Cpdlc.Msg m : s.log.msgs) {
                gaSeen |= m.up && m.type == Cpdlc.U_GOAROUND;
            }
            if (gaSeen) {
                double h = l1.posY - (Y + 1);
                check(h < ConfigSVA.aptGoAroundHeight + 2 && h > 3 && s.ac.st == AutoTower.S_GOAROUND,
                        "airport B2 world «go-around»: the plane on the runway - the liner sent around at " + f(h) + " over it (УХОДИТЕ НА ВТОРОЙ КРУГ)");
                landDone = true;
                l1.dismountAll();
                l1.setDead();
                if (blocker != null) {
                    blocker.setDead();
                }
                return;
            }
        }
        if (k > 1500 || l1.isDead || l1.onGround && k > 100) {
            check(false, "airport B2 world «landing»: no clearance / go-around (req " + l1Req + ", wilco " + l1Wilco + ", ga " + gaSeen + ", y "
                    + f(l1.posY - Y - 1) + ")");
            landDone = true;
            l1.dismountAll();
            l1.setDead();
            if (blocker != null) {
                blocker.setDead();
            }
        }
    }

    // ---------------------------------------------------------------- the controller seat

    private int ctlAt = -1;

    private void controller(World w, AirportDataSVA d, AirportDataSVA.Tower tw, int n, long now) {
        if (ctlAt < 0) {
            ctlAt = n;
            tower.debugController("WorldTestController");
            return;
        }
        if (n - ctlAt == 25) {
            net.minecraftforge.common.util.FakePlayer fp = fake(w);
            NBTTagCompound snap = TowerScreenSVA.snapshot(w, d, tower, tw, fp, now);
            boolean auto = AtcServerSVA.automatic(tw);
            check(!auto && "WorldTestController".equals(snap.getCompoundTag("T").getString("Ctl")) && !snap.getCompoundTag("T").getBoolean("Auto")
                            && snap.hasKey("Rw") && snap.getTagList("Rw", 10).tagCount() == 1 && snap.getTagList("Ap", 10).tagCount() == 1
                            && snap.getIntArray("Tn").length / 3 == taxiCells.size() - edges,
                    "airport B2 world «controller»: a player in the controller seat - the automatic tower stands down; the screen's picture: the runway,"
                            + " the approach, " + snap.getIntArray("Tn").length / 3 + " taxi nodes");
            tower.debugController("");
            ctlDone = true;
        }
    }

    // ---------------------------------------------------------------- the approach from the MCDU, the autoland

    private void approach(World w, AirportDataSVA d, AirportDataSVA.Tower tw, int n, long now) {
        int k = n - l2At;
        if (l2 == null) {
            check(false, "airport B2 world «approach»: no liner");
            apprDone = true;
            return;
        }
        engage(l2, k);
        LinerSystems S = L(l2);
        if (k == 2) {
            // INIT: UBTX/UBTW (the second tower's airport to this one); F-PLN: LAT REV of the destination - ЗАХОДЫ - ILS09 via no transition - INSERT; ВСТАВИТЬ
            LinerMcdu.Env e = LinerLogicSVA.mcduEnv(l2, S);
            for (char c : "UBTX/UBTW".toCharArray()) {
                S.mcdu.key(S, 0, LinerMcdu.K_CHAR + c, e);
            }
            S.mcdu.key(S, 0, LinerMcdu.K_R1, e);
            S.mcdu.key(S, 0, LinerMcdu.K_FPLN, e);
            int destLine = -1;
            List<LinerMcdu.Line> ls = LinerMcdu.lines(S.fms);
            for (int i = 0; i < ls.size(); i++) {
                if (ls.get(i).idx >= 0 && S.fms.shown().get(ls.get(i).idx).kind == LinerFms.K_DEST) {
                    destLine = i;
                }
            }
            S.mcdu.key(S, 0, LinerMcdu.K_L1 + destLine, e);
            S.mcdu.key(S, 0, LinerMcdu.K_R1 + 1, e);                   // ЗАХОДЫ>
            S.mcdu.key(S, 0, LinerMcdu.K_L1, e);                       // ILS09
            S.mcdu.key(S, 0, LinerMcdu.K_R1 + 5, e);                   // ВСТАВИТЬ* (the TMPY)
            S.mcdu.key(S, 0, LinerMcdu.K_R1 + 5, e);                   // F-PLN's ВСТАВИТЬ
            LinerFms f = S.fms;
            LinerFms.Wp faf = null, cif = null, ma = null, dest = f.dest();
            for (LinerFms.Wp wp : f.pts) {
                faf = "FF09".equals(wp.id) ? wp : faf;
                cif = "CI09".equals(wp.id) && wp.kind == LinerFms.K_WPT ? wp : cif;
                ma = "MA09".equals(wp.id) ? wp : ma;
            }
            int fafAlt = ApproachMath.gsAlt((int) Math.round(Y + 1.0), 3, ApproachMath.FAF_DIST);
            check(f.tmp == null && "ILS09".equals(f.appr) && faf != null && faf.cst == LinerFms.C_AT && faf.alt == fafAlt && cif != null
                            && cif.cst == LinerFms.C_ABOVE && ma != null && ma.kind == LinerFms.K_MISSED && dest != null && "09".equals(dest.rwy)
                            && dest.ils.equals(loc),
                    "airport B2 world «approach»: the MCDU inserts ILS09 - CI09 (+" + (cif == null ? -1 : cif.alt) + "), FF09 (at " + (faf == null ? -1 : faf.alt)
                            + "), the threshold 09 with its ILS, MA09 (missed, +" + (ma == null ? -1 : ma.alt) + ")");
            return;
        }
        LinerAfs F = S.afs;
        if ((F.thr == LinerAfs.T_RETARD || F.call == LinerAfs.CALL_RETARD) && l2.throttle > 0) {
            l2.throttle = 0;
        }
        tuned |= F.ils != null && F.ils.key().equals(loc) && F.ilsChosen.length() == 0 && (F.lat == LinerAfs.L_LOC || F.lat == LinerAfs.L_LAND);
        dual |= F.capability() == LinerAfs.CAP_CAT3_DUAL;
        land = land < 0 && F.lat == LinerAfs.L_LAND ? k : land;
        flare = flare < 0 && F.ver == LinerAfs.V_FLARE ? k : flare;
        roll = roll < 0 && F.lat == LinerAfs.L_ROLLOUT ? k : roll;
        boolean stopped = roll > 0 && l2.onGround && l2.speed < 0.01;
        if (stopped || k > 1900 || l2.isDead) {
            check(stopped && tuned && dual && land > 0 && flare > land && Math.abs(l2.posZ - rz - 0.5) < 2 && l2.posX > rx && l2.posX < rx + LEN,
                    "airport B2 world «approach»: the plan's ILS tuned by itself (" + tuned + "), LOC / G/S, CAT 3 DUAL, LAND " + land + ", FLARE " + flare
                            + ", ROLL OUT " + roll + ", stopped on 09 (" + f(l2.posX - rx) + " past the threshold)");
            l2.dismountAll();
            l2.setDead();
            apprDone = true;
            chartAt = n;
        }
    }

    // ---------------------------------------------------------------- the chart

    private void chart(World w, AirportDataSVA d, AirportDataSVA.Tower tw, int n) {
        int k = n - chartAt;
        net.minecraftforge.common.util.FakePlayer fp = fake(w);
        if (k == 1) {
            fp.inventory.clearInventory(null, -1);
            fp.capabilities.isCreativeMode = false;
            boolean noPaper = !TowerScreenSVA.print(fp, tower, d, tw, "ILS09");
            fp.inventory.addItemStackToInventory(new ItemStack(net.minecraft.init.Items.paper, 1));
            boolean printed = TowerScreenSVA.print(fp, tower, d, tw, "ILS09");
            ItemStack c = null;
            for (ItemStack s : fp.inventory.mainInventory) {
                c = s != null && s.getItem() == ModContentSVA.approachChart ? s : c;
            }
            ChartMath.Chart ch = ItemApproachChartSVA.chart(c);
            ChartMath.CFix faf = ch == null ? null : ch.fix(ApproachMath.F_FAF);
            boolean paperGone = true;
            for (ItemStack s : fp.inventory.mainInventory) {
                paperGone &= s == null || s.getItem() != net.minecraft.init.Items.paper;
            }
            check(noPaper && printed && paperGone && ch != null && "UBTW".equals(ch.icao) && "09".equals(ch.rwy) && ch.version == tw.chartVer
                            && faf != null && "IBT".equals(ch.ils) && ch.freq == 10970 && !ChartMath.outdated(ch.version, tw.chartVer),
                    "airport B2 world «chart»: no paper - no chart; with a sheet the tower prints «ILS 09» of UBTW, version " + (ch == null ? -1 : ch.version)
                            + ", FAF " + (faf == null ? "-" : faf.id + " " + faf.alt) + ", IBT 109,70");
            heldVersion = ch == null ? -1 : ch.version;
            set(w, tower, "UBTW", "Test Renamed");
        }
        if (k == 30) {
            check(heldVersion > 0 && tw.chartVer == heldVersion + 1 && ChartMath.outdated(heldVersion, tw.chartVer),
                    "airport B2 world «chart»: the airport renamed - the charts' version " + heldVersion + " -> " + tw.chartVer + ", the printed chart is outdated");
            chartDone = true;
        }
    }

    private int heldVersion = -1;

    // ---------------------------------------------------------------- chunks, cleanup

    private void loadArea(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer)) {
            return;
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (int cx = (rx - 1150) >> 4; cx <= (rx + LEN + 48) >> 4; cx++) {
            for (int cz = (rz - 48) >> 4; cz <= (rz + 32) >> 4; cz++) {
                cp.loadChunk(cx, cz);
            }
        }
        cp.loadChunk((rx + 150) >> 4, (rz - 1700) >> 4);
    }

    private void keepLoaded(World w) {
        if (!(w instanceof net.minecraft.world.WorldServer) || x0 == 0 && z0 == 0) {
            return;
        }
        if (w.getTotalWorldTime() % 100 == 0) {
            loadArea(w);
        }
        net.minecraft.world.gen.ChunkProviderServer cp = ((net.minecraft.world.WorldServer) w).theChunkProviderServer;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {taxiPlane, l1, blocker, l2}) {
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
        ConfigSVA.aptFog = savedFog;
        for (EntityVehicleSVA v : new EntityVehicleSVA[] {taxiPlane, l1, blocker, l2}) {
            if (v != null && !v.isDead) {
                v.dismountAll();
                v.setDead();
            }
        }
        if (x0 != 0 || z0 != 0) {
            for (int[] c : taxiCells) {
                w.setBlock(c[0], Y + 1, c[1], Blocks.air, 0, 3);
            }
            int[][] blocks = {{rx, Y, rz}, {rx + LEN, Y, rz}, {rx + LEN + 30, Y + 1, rz}, {rx + 25, Y + 1, rz + 15}, {rx + LEN + 32, Y + 1, rz + 4},
                {rx + 29, Y + 1, rz + 15}, {rx + 60, Y + 1, rz - 16}, {rx + 150, Y + 1, rz - 40}, {rx + 150, Y + 1, rz - 1700}};
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
