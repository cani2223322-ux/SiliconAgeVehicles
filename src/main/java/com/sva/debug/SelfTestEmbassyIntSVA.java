package com.sva.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.sva.airdefense.EmbassyAdMath;
import com.sva.airport.AtisMath;
import com.sva.airport.AutoTower;
import com.sva.airport.Cpdlc;
import com.sva.airport.EmbassyAirportMath;
import com.sva.airport.EmbassyAtcMath;
import com.sva.airport.EmbassyLightsMath;
import com.sva.airport.RunwayLayout;
import com.sva.airport.TaxiGraph;
import com.sva.block.AirportDataSVA;
import com.sva.block.AtcServerSVA;
import com.sva.block.EmbassyParkingDataSVA;
import com.sva.vehicle.EmbassyServiceMath;
import com.sva.vehicle.LinerIls;

import net.minecraft.init.Blocks;

/**
 * The integrator's self-test of «Лётное поле посольства» (2026-10-27): the packages' own pure rules that SelfTestEmbassySVA (§6.1 of the
 * plan) leaves out - the places on the core's field (airport.EmbassyAirportMath: the clients' "Ext" / "Ils" / "EmbPapi", taxiway «А»'s
 * holding point), the tower's screen of a request (airport.EmbassyAtcMath.screen / tellRefusal / holdTaxi / ext), the automatic tower on
 * the physical runway (AutoTower: Rwy.ext, Params.blocked, Params.taxiHold), the ATIS remarks, the PAPI's boxes and step, the approach
 * lights' flag of a player's runway (unchanged), the service window's numbers (EmbassyServiceMath: largest, wear, levels, days, the lot's
 * places) and the air defence's HUD seconds / the zone editor's outline (EmbassyAdMath). SelfTestEmbassySVA.run calls it last. No world,
 * no core class. Review 2026-10-27 (review()): the towing's new areas, the logbook's flight in, a stay's days carried over in the stands'
 * books (and their NBT), the taxi graph's grid build against the full search.
 */
final class SelfTestEmbassyIntSVA {

    private SelfTestEmbassyIntSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() {
        airportPlaces();
        atcScreen();
        autoTower();
        lights();
        services();
        airDefence();
        review();
    }

    // ---------------------------------------------------------------- the review's fixes (2026-10-27)

    private static void review() {
        // the towing's areas: a stand it does not hold, the apron - after the strip's / the apron's minutes; the old ones as they were
        boolean area = EmbassyServiceMath.area(false, false, false, false, true, false) == EmbassyServiceMath.AREA_SPOT
                && EmbassyServiceMath.area(false, false, false, false, false, true) == EmbassyServiceMath.AREA_APRON
                && EmbassyServiceMath.area(true, false, false, false, true, true) == EmbassyServiceMath.AREA_DOME
                && EmbassyServiceMath.area(false, true, false, false, true, true) == EmbassyServiceMath.AREA_STRIP
                && EmbassyServiceMath.area(false, false, false, false, false, false) == EmbassyServiceMath.AREA_NONE
                && EmbassyServiceMath.towMinutes(EmbassyServiceMath.AREA_SPOT, 2, 5, 10) == 2 && EmbassyServiceMath.towMinutes(EmbassyServiceMath.AREA_APRON, 2, 5, 10) == 10
                && EmbassyServiceMath.towMinutes(EmbassyServiceMath.AREA_DOME, 2, 5, 10) == 5 && EmbassyServiceMath.towMinutes(EmbassyServiceMath.AREA_NONE, 2, 5, 10) == -1;
        // the logbook's flight in: outside the field's box - now; inside, still flying within the window - kept on; a hop from the ground - none
        long w = EmbassyServiceMath.LANDED_WINDOW;
        boolean arrival = EmbassyServiceMath.arrival(0, true, true, 100) == 100 && EmbassyServiceMath.arrival(100, true, false, 150) == 150
                && EmbassyServiceMath.arrival(0, true, false, 150) == 0 && EmbassyServiceMath.arrival(100, false, false, 150) == 100
                && EmbassyServiceMath.arrival(100, true, false, 101 + w) == 100;
        // a stay's days: off the stand and on again the same day - the same stay (no new free day); to the lot and back - the same; gone
        // past STAY_GAP_DAYS - a new stay; another embassy - a new stay; a machine given away - the new owner's
        EmbassyParkingDataSVA d = new EmbassyParkingDataSVA(EmbassyParkingDataSVA.NAME);
        String o1 = "00000000-0000-0000-0000-0000000000b1", o2 = "00000000-0000-0000-0000-0000000000b2";
        d.take(7, 1, "v-1", o1, 100L, 3L);
        d.leave("v-1", 4L);
        EmbassyParkingDataSVA.Stand back = d.take(7, 2, "v-1", o1, 200L, 4L);
        boolean stay = back.sinceDay == 3L && back.paidDay == -1L && d.leftCount() == 0 && EmbassyServiceMath.parkDaysDue(back.sinceDay, back.paidDay, 4L) == 1;
        d.paid(back, 4L);
        EmbassyParkingDataSVA.Stand lot = d.toLot(7, "v-1", o1, 300L, 5L);
        stay &= lot.sinceDay == 3L && lot.paidDay == 4L && d.standOf("v-1") == null;
        EmbassyParkingDataSVA.Stand again = d.take(7, 3, "v-1", o1, 400L, 6L);
        stay &= again.sinceDay == 3L && again.paidDay == 4L && d.lotOf("v-1") == null;
        d.leave("v-1", 6L);
        d.pruneLeft(8L);
        EmbassyParkingDataSVA.Stand fresh = d.take(7, 1, "v-1", o1, 500L, 8L);
        stay &= fresh.sinceDay == 8L && fresh.paidDay == -1L;
        d.leave("v-1", 8L);
        EmbassyParkingDataSVA.Stand other = d.take(9, 1, "v-1", o1, 600L, 8L);
        stay &= other.sinceDay == 8L && other.embassy == 9;
        d.setOwner(other, o2);
        stay &= o2.equals(d.standOf("v-1").owner) && d.standsOf(9, o2).size() == 1 && d.standsOf(9, o1).isEmpty();
        EmbassyParkingDataSVA.Stand unknown = d.toLot(9, "v-2", o1, 700L);
        stay &= unknown.sinceDay == -1L && d.take(9, 2, "v-2", o1, 800L, 9L).sinceDay == 9L;
        // their NBT: the lot's days and the "Left" memory there and back (appended keys); an old lot entry without them - an unknown stay
        d.leave("v-2", 9L);
        d.toLot(9, "v-3", o2, 900L, 9L);
        net.minecraft.nbt.NBTTagCompound t = new net.minecraft.nbt.NBTTagCompound();
        d.writeToNBT(t);
        EmbassyParkingDataSVA r = new EmbassyParkingDataSVA(EmbassyParkingDataSVA.NAME);
        r.readFromNBT(t);
        boolean nbt = r.leftCount() == 1 && r.lotOf("v-3") != null && r.lotOf("v-3").sinceDay == 9L && r.take(9, 3, "v-2", o1, 950L, 10L).sinceDay == 9L
                && t.getTagList("Left", 10).tagCount() == 1;
        net.minecraft.nbt.NBTTagCompound old = new net.minecraft.nbt.NBTTagCompound(), e = new net.minecraft.nbt.NBTTagCompound();
        net.minecraft.nbt.NBTTagList l = new net.minecraft.nbt.NBTTagList();
        e.setInteger("E", 4);
        e.setString("V", "v-9");
        e.setString("O", o1);
        l.appendTag(e);
        old.setTag("Lot", l);
        EmbassyParkingDataSVA x = new EmbassyParkingDataSVA(EmbassyParkingDataSVA.NAME);
        x.readFromNBT(old);
        nbt &= x.lotOf("v-9") != null && x.lotOf("v-9").sinceDay == -1L && x.leftCount() == 0;
        check(area && arrival && stay && nbt, "embassy stands (review): a machine on a stand it does not hold / left on the apron is towed (AREA_SPOT"
                + " after the strip's minutes, AREA_APRON after its own) " + area + "; the «Лётная книжка» wants a flight in from outside the field's box "
                + arrival + "; a stay's days go with the machine (off and on, the lot and back - no new free day; past a day away, another embassy - a"
                + " new stay; given away - the new owner's) " + stay + "; the lot's days and the «Left» memory in the NBT, an old lot entry - unknown " + nbt);
        // the taxi graph: the grid build gives the full search's links (random spread-out nodes, ties, the tower's links and cuts)
        Random rnd = new Random(20261027L);
        boolean same = true;
        for (int round = 0; round < 6 && same; round++) {
            List<TaxiGraph.Node> nodes = new ArrayList<TaxiGraph.Node>();
            int n = 40 + round * 30;
            for (int i = 0; i < n; i++) {
                int x0 = (round % 2 == 0 ? rnd.nextInt(80) : rnd.nextInt(400)) - 40, z0 = rnd.nextInt(60) - 30;
                nodes.add(new TaxiGraph.Node("n" + i, x0, 64 + rnd.nextInt(3), z0, rnd.nextInt(3)));
            }
            Set<String> extra = new HashSet<String>(), cut = new HashSet<String>();
            extra.add(TaxiGraph.link("n0", "n" + (n - 1)));
            cut.add(TaxiGraph.link("n1", "n2"));
            List<List<Integer>> a = TaxiGraph.build(nodes, extra, cut), b = fullBuild(nodes, extra, cut);
            for (int i = 0; i < n && same; i++) {
                same = new HashSet<Integer>(a.get(i)).equals(new HashSet<Integer>(b.get(i)));
            }
        }
        check(same, "taxi graph (review): the grid build (each node asks its 3 x 3 cells of LINK) links exactly as the full search over every pair");
    }

    /** The taxi graph's old build (every pair) - the reference the grid build must match. */
    private static List<List<Integer>> fullBuild(List<TaxiGraph.Node> nodes, Set<String> extra, Set<String> cut) {
        int n = nodes.size();
        List<List<Integer>> adj = new ArrayList<List<Integer>>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<Integer>());
        }
        int[][] best = new int[n][8];
        for (int i = 0; i < n; i++) {
            TaxiGraph.Node a = nodes.get(i);
            double[] bd = new double[8];
            Arrays.fill(best[i], -1);
            Arrays.fill(bd, Double.MAX_VALUE);
            for (int j = 0; j < n; j++) {
                TaxiGraph.Node c = nodes.get(j);
                if (j == i || Math.abs(c.y - a.y) > 3) {
                    continue;
                }
                double dd = Math.sqrt((double) (a.x - c.x) * (a.x - c.x) + (double) (a.z - c.z) * (a.z - c.z) + 0.25 * (a.y - c.y) * (a.y - c.y));
                if (dd > TaxiGraph.LINK) {
                    continue;
                }
                double ang = Math.toDegrees(Math.atan2(c.x - a.x, -(c.z - a.z)));
                int sct = (int) Math.floor(((ang + 22.5) % 360 + 360) % 360 / 45) & 7;
                if (dd < bd[sct]) {
                    bd[sct] = dd;
                    best[i][sct] = j;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int sc = 0; sc < 8; sc++) {
                int j = best[i][sc];
                if (j < 0) {
                    continue;
                }
                TaxiGraph.Node a = nodes.get(i), c = nodes.get(j);
                double ang = Math.toDegrees(Math.atan2(a.x - c.x, -(a.z - c.z)));
                int back = (int) Math.floor(((ang + 22.5) % 360 + 360) % 360 / 45) & 7;
                if (best[j][back] == i) {
                    link(adj, i, j);
                }
            }
        }
        for (String e : extra) {
            int[] ij = pair(nodes, e);
            if (ij != null) {
                link(adj, ij[0], ij[1]);
            }
        }
        for (String e : cut) {
            int[] ij = pair(nodes, e);
            if (ij != null) {
                adj.get(ij[0]).remove(Integer.valueOf(ij[1]));
                adj.get(ij[1]).remove(Integer.valueOf(ij[0]));
            }
        }
        return adj;
    }

    private static void link(List<List<Integer>> adj, int i, int j) {
        if (!adj.get(i).contains(Integer.valueOf(j))) {
            adj.get(i).add(Integer.valueOf(j));
        }
        if (!adj.get(j).contains(Integer.valueOf(i))) {
            adj.get(j).add(Integer.valueOf(i));
        }
    }

    private static int[] pair(List<TaxiGraph.Node> nodes, String link) {
        int bar = link.indexOf('|');
        int a = TaxiGraph.index(nodes, link.substring(0, bar)), b = TaxiGraph.index(nodes, link.substring(bar + 1));
        return a < 0 || b < 0 || a == b ? null : new int[] {a, b};
    }

    // ---------------------------------------------------------------- package A: the add-on's places on the core's field

    private static void airportPlaces() {
        boolean icao = EmbassyAirportMath.uniqueIcao("X1AA", new ArrayList<String>()).length() == 0
                && EmbassyAirportMath.uniqueIcao("xtaa", new ArrayList<String>()).equals("XTAA")
                && !EmbassyAirportMath.playerIcaoAllowed("xabc") && EmbassyAirportMath.playerIcaoAllowed("zabc");
        int f1 = EmbassyAirportMath.locFreq(7, true), f2 = EmbassyAirportMath.locFreq(7, false), f3 = EmbassyAirportMath.locFreq(Integer.MIN_VALUE, true);
        boolean freq = f1 != f2 && f1 == LinerIls.clampFreq(f1) && f2 == LinerIls.clampFreq(f2) && f3 == LinerIls.clampFreq(f3);
        boolean ext = EmbassyAirportMath.ext(new int[] {-100, 64, 5}, new int[] {-80, 64, 5}) == 20
                && EmbassyAirportMath.ext(new int[] {5, 64, 46}, new int[] {5, 64, 45}) == 1 && EmbassyAirportMath.ext(null, new int[] {0, 0, 0}) == 0;
        boolean ils = EmbassyAirportMath.ilsBits(true, true, false) == 1 && EmbassyAirportMath.ilsBits(false, true, false) == 2
                && EmbassyAirportMath.ilsBits(true, true, true) == 3 && EmbassyAirportMath.ilsBits(false, false, false) == 0;
        int[] p90 = EmbassyAirportMath.papi(new int[] {0, 64, 0}, 90, 9, 3.0), p360 = EmbassyAirportMath.papi(new int[] {0, 64, 0}, 360, 9, 3.0);
        boolean papi = Arrays.equals(p90, new int[] {18, 64, -8, 900, 30}) && Arrays.equals(p360, new int[] {-8, 64, -18, 3600, 30})
                && EmbassyAirportMath.papi(null, 90, 9, 3.0) == null;
        boolean hold = Arrays.equals(EmbassyAirportMath.holdPoint(new int[] {80, 100, 100, 111}, new int[] {94, 103, 127, 109}, true, 1),
                new int[] {102, 106}) && EmbassyAirportMath.holdPoint(null, new int[] {94, 103, 127, 109}, true, 1) == null
                && Arrays.equals(EmbassyAirportMath.middle(new int[] {-5, -4, -2, -1}), new int[] {-4, -3});
        check(icao && freq && ext && ils && papi && hold, "embassy airport places (package A): a proposal that is no ICAO code - \"\", lower case taken as"
                + " upper, a player's xabc refused; the localizers' frequencies on LinerIls' grid, the two ends apart, any id safe " + f1 + "/" + f2
                + "; Ext FULL 20 / SHORT 1; Ils bits 1 / 2; EmbPapi " + Arrays.toString(p90) + " " + Arrays.toString(p360) + " (left of the landing,"
                + " 18 past the threshold); taxiway «А»'s holding point 2 past the pad, middles of negative boxes" + SelfTestLinerFmsSVA.flags(icao, freq,
                ext, ils, papi, hold));
        boolean land = AirportDataSVA.naturalGround(Blocks.grass) && AirportDataSVA.naturalGround(Blocks.dirt) && AirportDataSVA.naturalGround(Blocks.sand)
                && AirportDataSVA.naturalGround(Blocks.gravel) && AirportDataSVA.naturalGround(Blocks.mycelium) && AirportDataSVA.naturalGround(Blocks.snow_layer)
                && !AirportDataSVA.naturalGround(Blocks.stone) && !AirportDataSVA.naturalGround(Blocks.sandstone) && !AirportDataSVA.naturalGround(Blocks.clay)
                && !AirportDataSVA.naturalGround(Blocks.hardened_clay) && !AirportDataSVA.naturalGround(Blocks.stained_hardened_clay)
                && !AirportDataSVA.naturalGround(Blocks.snow) && !AirportDataSVA.naturalGround(Blocks.ice) && !AirportDataSVA.naturalGround(Blocks.packed_ice)
                && !AirportDataSVA.naturalGround(Blocks.cobblestone) && !AirportDataSVA.naturalGround(Blocks.planks) && !AirportDataSVA.naturalGround(Blocks.log)
                && !AirportDataSVA.naturalGround(Blocks.leaves) && !AirportDataSVA.naturalGround(Blocks.stonebrick) && !AirportDataSVA.naturalGround(Blocks.air);
        // the lip: the top one / two blocks of plain ground at the runway's level or one over it - never higher, never under a solid block
        boolean lip = AirportDataSVA.naturalLip(0, true, true, false, false) && AirportDataSVA.naturalLip(1, true, true, false, false)
                && AirportDataSVA.naturalLip(0, true, false, true, true) && !AirportDataSVA.naturalLip(1, true, false, true, true)
                && !AirportDataSVA.naturalLip(2, true, true, false, false) && !AirportDataSVA.naturalLip(-1, true, true, false, false)
                && !AirportDataSVA.naturalLip(0, true, false, false, true) && !AirportDataSVA.naturalLip(0, true, false, true, false)
                && !AirportDataSVA.naturalLip(0, false, true, false, false);
        check(land && lip, "embassy ILS areas (review): only the land's lip (grass, dirt, mycelium, sand, gravel, a snow layer - the top one or two"
                + " blocks at the runway's level or one over it) does not lower a virtual station's category past the field " + lip + "; stone, sandstone,"
                + " clay, terracotta, snow blocks, ice and what is built or grows there (cobblestone, planks, logs, leaves, stone bricks) do " + land);
    }

    // ---------------------------------------------------------------- package C: the tower's screen of a request

    private static void atcScreen() {
        boolean emergency = true;
        for (int i = 0; i < 128; i++) {
            emergency &= EmbassyAtcMath.screen((i & 1) != 0, (i & 2) != 0, true, (i & 4) != 0, (i & 8) != 0, (i & 16) != 0, (i & 32) != 0,
                    (i & 64) != 0) == EmbassyAtcMath.REFUSE_NONE;
        }
        boolean ground = EmbassyAtcMath.screen(false, true, false, true, true, false, true, false) == EmbassyAtcMath.REFUSE_ENEMY
                && EmbassyAtcMath.screen(false, false, false, true, true, false, true, false) == EmbassyAtcMath.REFUSE_NONE;
        boolean enemy = EmbassyAtcMath.screen(true, true, false, false, false, false, true, false) == EmbassyAtcMath.REFUSE_ENEMY
                && EmbassyAtcMath.screen(true, true, false, false, false, false, false, true) == EmbassyAtcMath.REFUSE_ENEMY;
        boolean liner = EmbassyAtcMath.screen(true, false, false, true, true, false, false, true) == EmbassyAtcMath.REFUSE_LINER
                && EmbassyAtcMath.screen(true, false, false, true, true, true, false, true) == EmbassyAtcMath.REFUSE_NONE
                && EmbassyAtcMath.screen(true, false, false, true, false, false, false, true) == EmbassyAtcMath.REFUSE_NONE;
        boolean dome = EmbassyAtcMath.screen(true, false, false, false, false, false, true, false) == EmbassyAtcMath.REFUSE_DOME
                && EmbassyAtcMath.screen(true, false, false, false, false, false, true, true) == EmbassyAtcMath.REFUSE_NONE
                && EmbassyAtcMath.screen(true, false, false, false, false, false, false, false) == EmbassyAtcMath.REFUSE_NONE;
        int gap = EmbassyAtcMath.REFUSE_GAP;
        boolean tell = EmbassyAtcMath.tellRefusal(true, 0, 1000, 0, 1010, gap) && !EmbassyAtcMath.tellRefusal(false, 0, 1000, 0, 1010, gap)
                && EmbassyAtcMath.tellRefusal(false, 0, 1000, 1, 1010, gap) && EmbassyAtcMath.tellRefusal(false, 0, 1000, 0, 1000 + gap, gap)
                && !EmbassyAtcMath.tellRefusal(false, 0, 1000, 0, 999 + gap, gap) && EmbassyAtcMath.tellRefusal(false, 0, 1000, 0, 500, gap);
        double win = EmbassyAtcMath.TAXI_HOLD_WINDOW;
        boolean taxi = !EmbassyAtcMath.holdTaxi(true, -5, win) && EmbassyAtcMath.holdTaxi(true, -4.9, win) && EmbassyAtcMath.holdTaxi(true, win - 1, win)
                && !EmbassyAtcMath.holdTaxi(true, win, win) && !EmbassyAtcMath.holdTaxi(false, 100, win);
        boolean ext = Math.abs(EmbassyAtcMath.ext(new int[] {-100, 64, 5}, new int[] {-80, 64, 5}) - 20) < 1e-9
                && EmbassyAtcMath.ext(null, new int[] {0, 64, 0}) == 0;
        boolean atis = AtisMath.embassyRemarks(true, false, false) == (AtisMath.RMK_EMB_DOME | AtisMath.RMK_EMB_BRAKE)
                && AtisMath.embassyRemarks(false, true, false) == (AtisMath.RMK_EMB_DOME | AtisMath.RMK_EMB_SHORT)
                && AtisMath.embassyRemarks(false, true, true) == AtisMath.RMK_EMB_DOME && AtisMath.RMK_KEYS.length == 10
                && AtisMath.RMK_KEYS[7].equals("embDome") && AtisMath.RMK_KEYS[9].equals("embBrake");
        boolean keys = AtcServerSVA.keyArg("#sva.cpdlc.refused.enemy") && AtcServerSVA.keyArg("#sva.cpdlc.embAd.warn")
                && !AtcServerSVA.keyArg("sva.cpdlc.embAd.warn") && !AtcServerSVA.keyArg(null);
        check(emergency && ground && enemy && liner && dome && tell && taxi && ext && atis && keys, "embassy tower's screen (package C): 7700 - nothing"
                + " refused (128 combinations, the dome neither); taxi / take-off - only the «Враг»; a landing - the «Враг» (over the dome too),"
                + " the liner on SHORT unless allowed, over the dome off the final ПОСАДКА В КУПОЛЕ ЗАПРЕЩЕНА; a refusal retold only when asked,"
                + " another or " + gap + " ticks later (a clock gone back too); the taxi hold from -5 to " + (int) win + " before the threshold;"
                + " ext 20; the ATIS remarks (dome always, SHORT, the autobrake on FULL) appended; the «#» arguments" + SelfTestLinerFmsSVA.flags(
                emergency, ground, enemy, liner, dome, tell, taxi, ext, atis, keys));
    }

    // ---------------------------------------------------------------- package C: the automatic tower on the physical runway

    private static AutoTower.Ac ac(int id, double x, double z, double h, double gs, boolean ground, int req) {
        return SelfTestAirportB2SVA.ac(id, x, z, h, gs, ground, req);
    }

    private static int act(List<AutoTower.Act> l, int id) {
        return SelfTestAirportB2SVA.act(l, id);
    }

    private static List<AutoTower.Ac> list(AutoTower.Ac... a) {
        return new ArrayList<AutoTower.Ac>(Arrays.asList(a));
    }

    private static void autoTower() {
        // a FULL-like runway: the virtual ends 160 apart along +x, paving 20 on past each (the displaced thresholds)
        AutoTower.Rwy w0 = new AutoTower.Rwy(SelfTestAirportB2SVA.rwy("e", 0.5, 0.5, 160), 1);
        AutoTower.Rwy w20 = new AutoTower.Rwy(SelfTestAirportB2SVA.rwy("e", 0.5, 0.5, 160), 1, 20);
        AutoTower.Ac disp = ac(9, -14.5, 0.5, 0, 0, true, AutoTower.R_NONE);
        boolean same = true;
        for (double x = -30; x <= 190; x += 3.5) {
            for (double z = -8; z <= 8; z += 1.5) {
                AutoTower.Ac a = ac(8, x, z, 1, 0, true, AutoTower.R_NONE);
                same &= AutoTower.onRunway(w0, a) == (w0.r.onRunway(x, z, AutoTower.RWY_MARGIN) && a.y - w0.r.elev < AutoTower.ON_RWY_H);
            }
        }
        boolean ext = same && !AutoTower.onRunway(w0, disp) && AutoTower.onRunway(w20, disp) && !AutoTower.onRunway(w20, ac(9, -23.5, 0.5, 0, 0, true, 0));
        // a machine in the displaced part: an arrival waits on the embassy's runway (the old rule would clear it)
        List<AutoTower.Act> r0 = AutoTower.decide(list(ac(1, -800, 0.5, 40, 1.3, false, AutoTower.R_LAND), ac(9, -14.5, 0.5, 0, 0, true, 0)), w0,
                new AutoTower.Mem(), 1000, new AutoTower.Params());
        List<AutoTower.Act> r20 = AutoTower.decide(list(ac(1, -800, 0.5, 40, 1.3, false, AutoTower.R_LAND), ac(9, -14.5, 0.5, 0, 0, true, 0)), w20,
                new AutoTower.Mem(), 1000, new AutoTower.Params());
        boolean displaced = act(r0, 1) == Cpdlc.U_LAND && act(r20, 1) == Cpdlc.U_STBY;
        // somebody on foot / a mob on the physical runway (Params.blocked): ОЖИДАЙТЕ, ОЖИДАЙТЕ ПЕРЕД ПОЛОСОЙ, a low arrival goes around
        AutoTower.Params pb = new AutoTower.Params();
        pb.blocked = true;
        AutoTower.Mem mb = new AutoTower.Mem();
        AutoTower.Ac a1 = ac(1, -800, 0.5, 40, 1.3, false, AutoTower.R_LAND);
        List<AutoTower.Ac> tb = list(a1);
        List<AutoTower.Act> rb = AutoTower.decide(tb, w20, mb, 1000, pb);
        boolean landWait = act(rb, 1) == Cpdlc.U_STBY && a1.st != AutoTower.S_LAND;
        pb.blocked = false;
        List<AutoTower.Act> rb2 = AutoTower.decide(tb, w20, mb, 1020, pb);
        boolean landAfter = act(rb2, 1) == Cpdlc.U_LAND && a1.st == AutoTower.S_LAND;
        pb.blocked = true;
        a1.x = -100;
        a1.y = 64 + 10;
        List<AutoTower.Act> rb3 = AutoTower.decide(tb, w20, mb, 1040, pb);
        boolean goAround = act(rb3, 1) == Cpdlc.U_GOAROUND && a1.st == AutoTower.S_GOAROUND;
        AutoTower.Ac d1 = ac(3, 10, -10, 0, 0, true, AutoTower.R_TAKEOFF);
        List<AutoTower.Act> rd = AutoTower.decide(list(d1), w20, new AutoTower.Mem(), 1000, pb);
        boolean holdShort = act(rd, 3) == Cpdlc.U_HOLDSHORT && d1.st == AutoTower.S_HOLD;
        // a landing over the high end near (Params.taxiHold): taxiway «А» ОЖИДАЙТЕ once, one still on the runway cleared, later cleared
        AutoTower.Params ph = new AutoTower.Params();
        ph.taxiHold = true;
        AutoTower.Mem mh = new AutoTower.Mem();
        AutoTower.Ac lnd = ac(1, -300, 0.5, 30, 1.3, false, AutoTower.R_NONE), tx = ac(4, 60, -30, 0, 0, true, AutoTower.R_TAXI),
                onRw = ac(5, 100, 0.5, 0, 0, true, AutoTower.R_TAXI);
        lnd.st = AutoTower.S_LAND;
        lnd.stAt = 900;
        List<AutoTower.Ac> th = list(lnd, tx, onRw);
        List<AutoTower.Act> h1 = AutoTower.decide(th, w20, mh, 1000, ph);
        List<AutoTower.Act> h2 = AutoTower.decide(th, w20, mh, 1020, ph);
        boolean held = act(h1, 4) == Cpdlc.U_STBY && act(h1, 5) == Cpdlc.U_TAXI && act(h2, 4) == -1 && tx.st != AutoTower.S_TAXI;
        lnd.x = -500;
        List<AutoTower.Act> h3 = AutoTower.decide(th, w20, mh, 1040, ph);
        boolean released = act(h3, 4) == Cpdlc.U_TAXI && tx.st == AutoTower.S_TAXI;
        AutoTower.Ac lnd2 = ac(1, -300, 0.5, 30, 1.3, false, AutoTower.R_NONE), tx2 = ac(4, 60, -30, 0, 0, true, AutoTower.R_TAXI);
        lnd2.st = AutoTower.S_LAND;
        lnd2.stAt = 900;
        boolean noFlag = act(AutoTower.decide(list(lnd2, tx2), w20, new AutoTower.Mem(), 1000, new AutoTower.Params()), 4) == Cpdlc.U_TAXI;
        check(ext && displaced && landWait && landAfter && goAround && holdShort && held && released && noFlag, "embassy automatic tower (package C):"
                + " ext 0 is the old «on the runway» everywhere, 15 into the displaced part on the runway only with ext 20; a machine there - the"
                + " arrival ОЖИДАЙТЕ; somebody on the physical runway - ОЖИДАЙТЕ, then ПОСАДКУ РАЗРЕШАЮ when gone, a low arrival УХОДИТЕ НА ВТОРОЙ"
                + " КРУГ, a departure ОЖИДАЙТЕ ПЕРЕД ПОЛОСОЙ; a landing over the high end 300 out - taxiway «А» ОЖИДАЙТЕ once (one on the runway"
                + " still cleared to vacate), at 500 cleared, without the hold at once" + SelfTestLinerFmsSVA.flags(ext, displaced, landWait,
                landAfter, goAround, holdShort, held, released, noFlag));
    }

    // ---------------------------------------------------------------- package L: the PAPI, a player's approach lights

    private static void lights() {
        String[] want = {"RRRR", "RRRW", "RRWW", "RWWW", "WWWW"};
        boolean boxes = true;
        for (int n = 0; n <= 4; n++) {
            StringBuilder s = new StringBuilder();
            for (int k = 0; k < 4; k++) {
                s.append(EmbassyLightsMath.papiWhite(k, n) ? 'W' : 'R');
            }
            boxes &= s.toString().equals(want[n]);
        }
        boolean step = EmbassyLightsMath.papiStep(RunwayLayout.LIGHTS_AUTO, 0) == EmbassyLightsMath.PAPI_DAY_STEP
                && EmbassyLightsMath.papiStep(RunwayLayout.LIGHTS_TOWER, 0) == EmbassyLightsMath.PAPI_DAY_STEP && EmbassyLightsMath.papiStep(0, 0) == 0
                && EmbassyLightsMath.papiStep(RunwayLayout.LIGHTS_AUTO, 2) == 2 && EmbassyLightsMath.papiStep(4, 99) == RunwayLayout.STEPS;
        boolean angle = EmbassyLightsMath.angleOf(30) == 3.0 && EmbassyLightsMath.angleOf(0) == EmbassyLightsMath.PAPI_ANGLE
                && Math.abs(EmbassyLightsMath.elevation(1, 1 / Math.tan(Math.toRadians(3))) - 3) < 1e-6;
        double[][] pb = EmbassyLightsMath.papiBoxes(0, 0, 9);
        boolean out = Math.abs(pb[0][0] - EmbassyLightsMath.PAPI_ALONG) < 1e-9 && Math.abs(pb[0][1] + 4.5 + EmbassyLightsMath.PAPI_OFF) < 1e-9
                && pb[3][1] < pb[0][1];
        boolean flag = true;
        int n = 0;
        for (int dir = -1; dir <= 1; dir += 2) {
            for (int als = 0; als < 4; als++) {
                for (RunwayLayout.Light l : RunwayLayout.lights(300, 5, dir, (als & 1) != 0, (als & 2) != 0)) {
                    flag &= l.approach == (l.v < -0.5 || l.v > 300 + 0.5);
                    n++;
                }
            }
        }
        check(boxes && step && angle && out && flag && n > 0, "embassy PAPI and lights (package L): RRRR / RRRW / RRWW / RWWW / WWWW (the outer boxes"
                + " white first); the PAPI's step - AUTO / ВЫШКА by day 3, «off» 0, the runway's step else (5 at most); the angle tenths; a point on"
                + " the threshold goes 18 in and out of the paving to the left; a player's runway: the approach flag = the old «out past an end» rule"
                + " (" + n + " lights)" + SelfTestLinerFmsSVA.flags(boxes, step, angle, out, flag));
    }

    // ---------------------------------------------------------------- package S: the window's numbers, the lot

    private static void services() {
        boolean amounts = EmbassyServiceMath.emergencyAmount(0, 48000) == 12000 && EmbassyServiceMath.emergencyAmount(20000, 48000) == 0
                && EmbassyServiceMath.offer(EmbassyServiceMath.S_CHARGE, 40000000L) == 16000000L && EmbassyServiceMath.offer(EmbassyServiceMath.S_REPAIR, 75) == 60
                && EmbassyServiceMath.hint(EmbassyServiceMath.S_ELECTROLYTE, 1) == 2;
        boolean tow = EmbassyServiceMath.towDue(0, 2400, 2) && !EmbassyServiceMath.towDue(0, 2399, 2) && !EmbassyServiceMath.towDue(0, 999999, -1)
                && EmbassyServiceMath.towMinutes(EmbassyServiceMath.AREA_STRIP, 2, 5) == 2 && EmbassyServiceMath.towMinutes(EmbassyServiceMath.AREA_DOME, 2, 5) == 5
                && EmbassyServiceMath.towMinutes(EmbassyServiceMath.AREA_NONE, 2, 5) == -1
                && EmbassyServiceMath.area(true, true, false, false) == EmbassyServiceMath.AREA_DOME
                && EmbassyServiceMath.area(false, false, false, true) == EmbassyServiceMath.AREA_STRIP
                && EmbassyServiceMath.area(false, false, false, false) == EmbassyServiceMath.AREA_NONE;
        boolean wear = EmbassyServiceMath.wearPct(62, 100) == 38 && EmbassyServiceMath.wearPct(100, 100) == 0 && EmbassyServiceMath.wearPct(99.5, 100) == 1
                && EmbassyServiceMath.wearPct(50, 0) == 0 && Math.abs(EmbassyServiceMath.repairHp(10, 200) - 20) < 1e-9;
        boolean levels = EmbassyServiceMath.levelSells(EmbassyServiceMath.S_EMERGENCY, 1) && EmbassyServiceMath.levelSells(EmbassyServiceMath.S_EMERGENCY, 2)
                && !EmbassyServiceMath.levelSells(EmbassyServiceMath.S_EMERGENCY, 3) && !EmbassyServiceMath.levelSells(EmbassyServiceMath.S_CHARGE, 2)
                && EmbassyServiceMath.levelSells(EmbassyServiceMath.S_CHARGE, 3) && !EmbassyServiceMath.levelSells(-1, 3);
        long big = EmbassyServiceMath.largest(16000000L, new EmbassyServiceMath.Probe() {
            @Override
            public boolean ok(long amount) {
                return amount <= 3500000L;
            }
        });
        long none = EmbassyServiceMath.largest(16000000L, new EmbassyServiceMath.Probe() {
            @Override
            public boolean ok(long amount) {
                return false;
            }
        });
        boolean search = big == 3500000L && none == 0 && EmbassyServiceMath.largest(0, null) == 0;
        long w = EmbassyServiceMath.LANDED_WINDOW;
        boolean days = EmbassyServiceMath.day(-5) == 0 && EmbassyServiceMath.day(23999) == 0 && EmbassyServiceMath.day(24000) == 1
                && !EmbassyServiceMath.debtToLot(1) && EmbassyServiceMath.debtToLot(2) && EmbassyServiceMath.freeDay(10, 10) && !EmbassyServiceMath.freeDay(10, 11)
                && EmbassyServiceMath.parkDaysDue(10, -1, 10) == 0 && EmbassyServiceMath.parkDaysDue(10, -1, 11) == 1
                && EmbassyServiceMath.parkDaysDue(10, -1, 12) == 2 && EmbassyServiceMath.parkDaysDue(10, 11, 12) == 1
                && EmbassyServiceMath.landed(100, 100 + w, true) && !EmbassyServiceMath.landed(100, 101 + w, true) && !EmbassyServiceMath.landed(100, 200, false)
                && !EmbassyServiceMath.landed(0, 200, true);
        // the lot: a row along the apron's edge by the terminal, 4 in, every 8, none within 2 of a stand; beside «Н» outside, away from the core
        List<int[]> row = EmbassyServiceMath.lotRow(new int[] {100, 20, 120, 40}, new int[] {100, 0, 120, 10}, new int[][] {{110, 22, 114, 30}});
        boolean lot = row.size() == 1 && row.get(0)[0] == 104 && row.get(0)[1] == 24 && EmbassyServiceMath.lotRow(null, null, null).isEmpty();
        int[] pad = {0, 0, 10, 10};
        List<int[]> side = EmbassyServiceMath.padSide(pad, -100, 5);
        boolean outside = side.size() == 6;
        for (int[] c : side) {
            outside &= !EmbassyServiceMath.inGrown(pad, c[0], c[1], 0) && (c[0] > pad[2] || c[1] < pad[1] || c[1] > pad[3]);
        }
        check(amounts && tow && wear && levels && search && days && lot && outside, "embassy services' numbers (package S): the emergency's 25% (12000"
                + " of 48000), the offers within a service's cap (16M EU, 60%), the electrolyte's hint 2; towing after 2 min exactly (never with -1),"
                + " the areas; the hull's wear rounded up; who is sold what (emergency «Нарушитель» / «Чужак» only, charging from «Знакомый»); the"
                + " day's cap found by halving (" + big + "); game days, the debt, the first free day, the logbook's window " + w + " with the"
                + " transponder; the «Отстой» row " + row.size() + " place(s), 6 beside «Н» away from the core" + SelfTestLinerFmsSVA.flags(amounts,
                tow, wear, levels, search, days, lot, outside));
    }

    // ---------------------------------------------------------------- package E: the HUD's seconds, the zone editor's outline

    private static void airDefence() {
        boolean secs = EmbassyAdMath.secondsLeft(100, 100) == 0 && EmbassyAdMath.secondsLeft(101, 100) == 1 && EmbassyAdMath.secondsLeft(120, 100) == 1
                && EmbassyAdMath.secondsLeft(121, 100) == 2 && EmbassyAdMath.secondsLeft(300, 100) == 10 && EmbassyAdMath.secondsLeft(50, 100) == 0;
        double[][] sq = EmbassyAdMath.alongOutline(new double[][] {{0, 0}, {16, 0}, {16, 16}, {0, 16}}, 8);
        boolean corners = false, far = false;
        for (double[] p : sq) {
            corners |= p[0] == 16 && p[1] == 16;
        }
        for (int i = 0; i < sq.length; i++) {
            double[] a = sq[i], b = sq[(i + 1) % sq.length];
            far |= Math.hypot(b[0] - a[0], b[1] - a[1]) > 8 + 1e-9;
        }
        boolean outline = sq.length == 8 && corners && !far && EmbassyAdMath.alongOutline(null, 8).length == 0;
        check(secs && outline, "embassy AD helpers (package E): the HUD's seconds rounded up (0 past the end, 10 for 200 ticks); a zone's outline"
                + " walked every 8 with its corners (" + sq.length + " points of a 16 square)" + SelfTestLinerFmsSVA.flags(secs, outline));
    }
}
