package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sc.energy.Tier;
import com.sc.init.ModItems;
import com.sva.block.TileEntityChargerSVA;
import com.sva.block.TileEntitySupercapSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ItemStationModuleSVA;
import com.sva.platform.PlatformMath;
import com.sva.vehicle.PlatformSize;
import com.sva.vehicle.VehicleType;
import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Fast charging self-tests (2026-10-10) - pure logic, run by SelfTestSVA on the dedicated server ([SVA-TEST] lines, the
 * same counters): the rate with the fast-charge module / boost / universal input / overclockers, stations adding up,
 * the shared vehicle pick and the plates' master, 121 plates and the 7x7 bonus, the storage pull, the supercapacitor's
 * in / out, the boost heat (overheat time, cooling, helium), the SV station, NBT, recipes.
 */
final class SelfTestFastChargeSVA {

    private SelfTestFastChargeSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    static void run() {
        rates();
        sharing();
        bigPlatforms();
        storagePull();
        supercap();
        boostHeat();
        stations();
        content();
    }

    private static void rates() {
        check(PlatformMath.multiplier(false, false) == 1 && PlatformMath.multiplier(true, false) == 2 && PlatformMath.multiplier(false, true) == 3
                && PlatformMath.multiplier(true, true) == 6, "fast charge: multipliers - module x2, boost x3, both x6");
        check(PlatformMath.rate(Tier.MV, 0, 0, 1, false, false) == 128 && PlatformMath.rate(Tier.MV, 0, 0, 1, true, false) == 256
                && PlatformMath.rate(Tier.MV, 0, 0, 1, false, true) == 384 && PlatformMath.rate(Tier.MV, 0, 0, 1, true, true) == 768,
                "fast charge: MV 128 -> module 256, boost 384, both 768 EU/t");
        check(PlatformMath.rate(Tier.MV, 2, 2.0, 1, true, true) == 5898 && PlatformMath.rate(Tier.MV, 2, 2.0, 1) == 983,
                "fast charge: 2 overclockers + 200% plates: 983 -> x6 = 5 898 EU/t");
        check(PlatformMath.rate(Tier.max(), 0, 0, 1, true, false) == 262144 && PlatformMath.rate(Tier.max(), 0, 2.0, 1, true, true) == 2359296,
                "fast charge: universal transformer (SV 131 072) x2 = 262 144; +200% plates and boost: 2 359 296 EU/t");
        check(PlatformMath.rate(Tier.max(), 16, 2.0, 100, true, true) == Integer.MAX_VALUE && PlatformMath.rate(Tier.MV, 0, Double.NaN, 1, true, true) >= 1,
                "fast charge: rate clamps to an int, NaN-safe");
    }

    private static void sharing() {
        check(PlatformMath.sum(128, 128) == 256 && PlatformMath.sum(262144, 262144, 768) == 525056 && PlatformMath.sum(Integer.MAX_VALUE, 5) == Integer.MAX_VALUE
                && PlatformMath.sum(-5, 10) == 10 && PlatformMath.sum() == 0, "stations add up: 128 + 128 = 256, clamped, negatives ignored");
        boolean same = true;
        int[] seen = new int[3];
        for (long t = 1000; t < 1030; t++) {
            int a = PlatformMath.sharedPick(t, 3), b = PlatformMath.sharedPick(t, 3);
            same &= a == b && a >= 0 && a < 3;
            seen[a]++;
        }
        check(same && seen[0] == 10 && seen[1] == 10 && seen[2] == 10 && PlatformMath.sharedPick(5, 0) == -1 && PlatformMath.sharedPick(-7, 2) >= 0,
                "stations add up: the same vehicle picked by every station in a tick, each one in turn (10/10/10 in 30 ticks)");
        List<int[]> peersOfA = new ArrayList<int[]>(), peersOfB = new ArrayList<int[]>();
        peersOfA.add(new int[] {14, 64, 0});
        peersOfB.add(new int[] {10, 64, 0});
        boolean a = PlatformMath.isMaster(10, 64, 0, peersOfA), b = PlatformMath.isMaster(14, 64, 0, peersOfB);
        List<int[]> tie = new ArrayList<int[]>();
        tie.add(new int[] {10, 64, -3});
        check(a && !b && !PlatformMath.isMaster(10, 64, 0, tie) && PlatformMath.isMaster(10, 64, 0, new ArrayList<int[]>()),
                "stations add up: exactly one master shows the plates (lowest x, then y, then z); a lone station is its own");
    }

    private static java.util.Set<Long> rect(int x0, int z0, int x1, int z1) {
        java.util.Set<Long> s = new java.util.HashSet<Long>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                s.add(Long.valueOf(PlatformMath.key(x, z)));
            }
        }
        return s;
    }

    private static void bigPlatforms() {
        final java.util.Set<Long> big = rect(-8, -8, 8, 8);
        big.remove(Long.valueOf(PlatformMath.key(0, 0)));
        java.util.Set<Long> got = PlatformMath.floodFill(new PlatformMath.Plates() {
            @Override
            public boolean isPlate(int x, int z) {
                return big.contains(Long.valueOf(PlatformMath.key(x, z)));
            }
        }, 0, 0, PlatformMath.MAX_PLATES);
        check(PlatformMath.MAX_PLATES == 121 && got.size() == 121, "big platforms: at most 121 plates (11x11; 288 laid -> " + got.size() + ")");
        // an 11x11 platform beside the station: 121 plates
        final java.util.Set<Long> sq = rect(1, -5, 11, 5);
        got = PlatformMath.floodFill(new PlatformMath.Plates() {
            @Override
            public boolean isPlate(int x, int z) {
                return sq.contains(Long.valueOf(PlatformMath.key(x, z)));
            }
        }, 0, 0, PlatformMath.MAX_PLATES);
        int[] b = PlatformMath.bounds(got);
        check(got.size() == 121 && b[2] - b[0] + 1 == 11 && b[3] - b[1] + 1 == 11 && PlatformMath.fits(PlatformSize.P7x7, 121, 11, 11),
                "big platforms: an 11x11 platform - 121 plates, the 7x7 class fits");
        check(VehicleType.SHIP.platformSize == PlatformSize.P7x7 && PlatformMath.plateBonus(64, PlatformSize.P7x7) == 1.5
                && PlatformMath.plateBonus(69, PlatformSize.P7x7) == 2.0 && PlatformMath.plateBonus(121, PlatformSize.P7x7) == 2.0
                && near(PlatformMath.plateBonus(59, PlatformSize.P7x7), 1.0, 1e-9),
                "big platforms: the Singular Ship (7x7, 49) - old cap 64 plates gave +150%, now +200% from 69 plates");
        // the ship from empty: SV station (fast built in) on 69+ plates, no boost - 256M / (131 072 x 2 x 3) = 326 ticks
        int r = PlatformMath.rate(Tier.SV, 0, 2.0, 1, true, false);
        double sec = VehicleType.SHIP.buffer / (double) r / 20.0;
        check(r == 786432 && sec > 16 && sec < 17, "big platforms: SV station, +200%: the ship fills in " + Math.round(sec) + " s (" + r + " EU/t)");
    }

    private static void storagePull() {
        check(PlatformMath.pullNeed(1000, 300, 5000) == 700 && PlatformMath.pullNeed(1000, 300, 200.9) == 200 && PlatformMath.pullNeed(1000, 1000, 5000) == 0
                && PlatformMath.pullNeed(1000, 0, -5) == 0 && PlatformMath.pullNeed(128, 0, 1e12) == 128,
                "storage link: takes what the station's rate leaves (700 of 1000), never past the room, never past the rate");
    }

    private static void supercap() {
        check(PlatformMath.CAP_BUFFER == 50000000 && PlatformMath.CAP_DUMP == 500000 && PlatformMath.CAP_INPUT == 2048,
                "supercapacitor: 50M EU, 2 048 EU/t in, 500 000 EU/t out");
        int stored = PlatformMath.CAP_BUFFER, ticks = 0;
        long given = 0;
        while (stored > 0 && ticks < 1000) {
            int n = PlatformMath.capDump(stored, 0, 1e12);
            stored -= n;
            given += n;
            ticks++;
        }
        check(ticks == 100 && given == PlatformMath.CAP_BUFFER, "supercapacitor: 50M into a big vehicle in 100 ticks = 5 s (" + ticks + ")");
        check(PlatformMath.capDump(PlatformMath.CAP_BUFFER, 400000, 1e12) == 100000 && PlatformMath.capDump(PlatformMath.CAP_BUFFER, 0, 1234.7) == 1234
                && PlatformMath.capDump(0, 0, 1e9) == 0 && PlatformMath.capDump(300, 0, 1e9) == 300,
                "supercapacitor: one 500k a tick shared by every station, the room and its charge bound it");
        check(PlatformMath.capInput(5000, 0, 0) == 2048 && PlatformMath.capInput(5000, 0, 2000) == 48 && PlatformMath.capInput(5000, PlatformMath.CAP_BUFFER - 10, 0) == 10
                && PlatformMath.capInput(100, 0, 0) == 100 && PlatformMath.capInput(5000, 0, 3000) == 0,
                "supercapacitor: grid input 2 048 EU/t at most, never past full");
        double minutes = PlatformMath.CAP_BUFFER / (double) PlatformMath.CAP_INPUT / 20 / 60;
        check(minutes > 20 && minutes < 21, "supercapacitor: from empty in ~" + Math.round(minutes) + " min at 2 048 EU/t");
        check(PlatformMath.fillSignal(0, 100) == 0 && PlatformMath.fillSignal(1, 100) == 1 && PlatformMath.fillSignal(50, 100) == 8
                && PlatformMath.fillSignal(100, 100) == 15 && TileEntitySupercapSVA.level(0, 100) == 0 && TileEntitySupercapSVA.level(1, 100) == 1
                && TileEntitySupercapSVA.level(50, 100) == 2 && TileEntitySupercapSVA.level(100, 100) == 4,
                "supercapacitor: comparator = fill, the sides show it in 5 steps");
        TileEntitySupercapSVA cap = new TileEntitySupercapSVA();
        check(cap.acceptsAnyVoltage() && cap.inputTier() == Tier.max() && cap.getMaxEnergyStored() == PlatformMath.CAP_BUFFER && !cap.isEnergySource(),
                "supercapacitor: any voltage, 50M buffer, never a source for the grid");
    }

    private static void boostHeat() {
        int heat = 0, ticks = 0;
        boolean over = false;
        while (!over && ticks < 10000) {
            heat = PlatformMath.heatNext(heat, true, false);
            over = PlatformMath.overheatNext(over, heat);
            ticks++;
        }
        check(ticks == 2400 && PlatformMath.ticksToOverheat(0) == 2400, "boost: overheats after 2 400 ticks = 2 min (" + ticks + ")");
        int cool = 0;
        while (over && cool < 10000) {
            heat = PlatformMath.heatNext(heat, false, false);
            over = PlatformMath.overheatNext(over, heat);
            cool++;
        }
        check(cool == 600 && PlatformMath.ticksToCool(PlatformMath.HEAT_MAX) == 600 && heat == 0, "boost: overheated, paused until cold - 600 ticks = 30 s (" + cool + ")");
        check(PlatformMath.overheatNext(true, 4) && !PlatformMath.overheatNext(false, PlatformMath.HEAT_MAX - 1) && !PlatformMath.overheatNext(true, 0),
                "boost: an overheated station stays paused while it is warm");
        heat = PlatformMath.HEAT_MAX - 100;
        for (int i = 0; i < 20000; i++) {
            heat = PlatformMath.heatNext(heat, true, true);
        }
        check(heat == 0 && PlatformMath.heliumFor(2400) == 120 && PlatformMath.heliumFor(1) == 1 && PlatformMath.heliumFor(0) == 0,
                "boost: helium keeps it cold for ever, 1 mB a second (2 min = 120 mB)");
        // average over a cycle without helium: 2 min at x3, 30 s paused = x2.4
        double avg = 3.0 * 2400 / (2400 + 600);
        check(near(avg, 2.4, 1e-9), "boost: without helium x3 for 2 min then 30 s pause - x2.4 on average");
    }

    private static void stations() {
        TileEntityChargerSVA mv = new TileEntityChargerSVA(Tier.MV);
        TileEntityChargerSVA sv = new TileEntityChargerSVA(Tier.SV);
        check(!mv.hasFastCharge() && sv.hasFastCharge() && sv.inputTier() == Tier.SV && sv.rate() == 262144 && mv.rate() == 128,
                "SV station: SV input without transformers, fast charge built in - 262 144 EU/t");
        mv.setInventorySlotContents(TileEntityChargerSVA.FIRST_MODULE, ModContentSVA.module.stackOf(ItemStationModuleSVA.FAST));
        int one = mv.rate();
        mv.setInventorySlotContents(TileEntityChargerSVA.FIRST_MODULE + 1, ModContentSVA.module.stackOf(ItemStationModuleSVA.FAST));
        check(one == 256 && mv.rate() == 256 && TileEntityChargerSVA.moduleAccepted(ModContentSVA.module.stackOf(ItemStationModuleSVA.FAST)),
                "fast charge module: x2 (128 -> 256), a second one adds nothing");
        boolean cfg = com.sva.ConfigSVA.stationBoost;
        com.sva.ConfigSVA.stationBoost = true;
        mv.toggleSetting(TileEntityChargerSVA.SET_BOOST);
        int boosted = mv.rate();
        mv.setHeatForTest(PlatformMath.HEAT_MAX);
        NBTTagCompound t = new NBTTagCompound();
        mv.writeToNBT(t);
        TileEntityChargerSVA back = new TileEntityChargerSVA(Tier.MV);
        back.readFromNBT(t);
        check(boosted == 768 && back.boostOn() && back.getHeat() == PlatformMath.HEAT_MAX && back.rate() == 768,
                "boost: x3 on top of the module (768 EU/t); the setting and the heat survive a reload");
        com.sva.ConfigSVA.stationBoost = false;
        check(!back.boostOn() && back.rate() == 256, "boost: vehicles.stationBoost off - no boost");
        com.sva.ConfigSVA.stationBoost = cfg;
        NBTTagCompound svt = new NBTTagCompound();
        sv.writeToNBT(svt);
        TileEntityChargerSVA svBack = new TileEntityChargerSVA();
        svBack.readFromNBT(svt);
        check(svBack.getTier() == Tier.SV && svBack.hasFastCharge() && "tile.siliconagevehicles.charger.sv.name".equals(svBack.getInventoryName())
                && "tile.siliconagevehicles.charger.name".equals(new TileEntityChargerSVA(Tier.MV).getInventoryName()),
                "SV station: the tier survives a reload (no-arg tile), its own name");
    }

    private static void content() {
        WorkbenchRecipeSVA fast = WorkbenchRecipesSVA.byId("moduleFast"), svr = WorkbenchRecipesSVA.byId("chargerSV"),
                cap = WorkbenchRecipesSVA.byId("supercap");
        check(fast != null && fast.category == WorkbenchRecipeSVA.Category.MODULES && fast.result().getItem() == ModContentSVA.module
                && ItemStationModuleSVA.kind(fast.result()) == ItemStationModuleSVA.FAST,
                "fast charge content: the module recipe (workbench, «Модули»)");
        check(svr != null && svr.category == WorkbenchRecipeSVA.Category.BLOCKS && svr.result().getItemDamage() == Tier.SV.ordinal()
                && net.minecraft.block.Block.getBlockFromItem(svr.result().getItem()) == ModContentSVA.charger,
                "fast charge content: the SV station recipe (meta SV)");
        check(cap != null && net.minecraft.block.Block.getBlockFromItem(cap.result().getItem()) == ModContentSVA.supercap,
                "fast charge content: the supercapacitor recipe");
        List<WorkbenchRecipeSVA> all = WorkbenchRecipesSVA.all();
        int n = all.size();
        check(n >= 3 && all.get(n - 1) == cap && all.get(n - 2) == svr && all.get(n - 3) == fast,
                "fast charge content: the new recipes appended at the end (sync ids of the old ones unchanged)");
        check(ModItems.upgrade != null && com.sva.block.BlockChargerSVA.TIERS.length == 2 && com.sva.block.BlockChargerSVA.TIERS[1] == Tier.SV,
                "fast charge content: the station block in MV and SV");
    }
}
