package com.sva.debug;

import com.sc.energy.Tier;
import com.sc.init.ModItems;
import com.sc.util.Material;
import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ComponentType;
import com.sva.item.ItemVehicleSVA;
import com.sva.platform.PlatformMath;
import com.sva.vehicle.AutoDelivery;
import com.sva.vehicle.CrawlerMath;
import com.sva.vehicle.DrillMath;
import com.sva.vehicle.HeliMath;
import com.sva.vehicle.HoverMath;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlatformSize;
import com.sva.vehicle.SkinMath;
import com.sva.vehicle.TiltMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;
import com.sva.workbench.FillPlannerSVA;
import com.sva.workbench.WorkbenchMath;
import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.NBTTagCompound;

/** -Dsva.selftest=true: pure checks of the stage 1 formulas and NBT, printed as [SVA-TEST] lines. */
public final class SelfTestSVA {

    private static int passed, failed;

    private SelfTestSVA() {
    }

    /** One [SVA-TEST] line (package-visible: SelfTestWave1SVA reports through it). */
    static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println("[SVA-TEST] " + (ok ? "PASS " : "FAIL ") + name);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    public static void run() {
        passed = failed = 0;
        try {
            consumption();
            range();
            crawl();
            heat();
            wear();
            limit();
            nbt();
            charger();
            config();
            moves();
            recipes();
            workbenchMatching();
            workbenchRecipes();
            workbenchUpgrades();
            workbenchPlanner();
            neiLayout();
            components();
            gasTanks();
            helium();
            hover();
            hoverType();
            pickUp();
            platform();
            aircraftTypes();
            heli();
            plane();
            flightMoves();
            aircraftContent();
            crawlerTypes();
            crawlerMoves();
            cargoTrailer();
            autoDelivery();
            drillGeometry();
            drillRules();
            stageCContent();
            jetType();
            jetFlight();
            planeUnchanged();
            tiltType();
            tiltRules();
            fastMoves();
            stageDContent();
            spaceTypes();
            spaceRules();
            jumpMath();
            spaceState();
            stageEContent();
            glyphsAndCargoKey();
            riderFit();
            ownView();
            cockpitVisibility();
            langKeys();
            cockpitWindows();
            cargoV2();
            skins();
        cockpitInstruments();
            animParts();
            animLogic();
            cockpitStage1();
            lightTable();
            cockpitStage2();
            SelfTestWave1SVA.run();                         // wave 1: sounds, camera, cruise / autopilot, roads
            SelfTestWave2SVA.run();                         // wave 2: garage, tuning, key cards / access
            SelfTestWave3SVA.run();                         // wave 3: trailer kinds, the vehicle registry / map
            SelfTestRegenSVA.run();                         // wave 4: regenerative braking / energy recovery
            SelfTestKeysSVA.run();                          // the «Техника» menu: function keys, migration, Controls
            SelfTestFastChargeSVA.run();                    // fast charging: module, boost, shared platforms, storages, supercap
            SelfTestDriveSVA.run();                         // 2026-10-10 fixes: the stall flag in level flight
            SelfTestAirSVA.run();                           // aircraft stage A: flaps, slats, spoilers, the model's tagged parts
            SelfTestAirCSVA.run();                          // aircraft stage C: autopilot modes, runway capture, HSI, cockpit panels
            SelfTestAirBSVA.run();                          // aircraft stage B: weathering by wear, markings, stencil font
        } catch (Throwable t) {
            failed++;
            System.out.println("[SVA-TEST] FAIL exception " + t);
            t.printStackTrace(System.out);
        }
        System.out.println("[SVA-TEST] SUMMARY passed=" + passed + " failed=" + failed);
    }

    private static void consumption() {
        VehicleType b = VehicleType.BUGGY;
        check(near(VehicleMath.cost(b, b.topSpeed, 0, true, 1), 20, 1e-9), "consumption: full throttle cruise = 20 EU/t");
        check(near(VehicleMath.cost(b, 0, 0, true, 1), 1, 1e-9), "consumption: idle = 1 EU/t");
        check(VehicleMath.cost(b, 0.5, 1, false, 1) == 0, "consumption: motor off = 0");
        check(near(VehicleMath.cost(b, b.topSpeed, 1, true, 1), 39, 1e-9), "consumption: full cargo doubles the speed part (39 EU/t)");
        check(near(VehicleMath.cost(b, b.topSpeed, 0, true, 2), 40, 1e-9), "consumption: multiplier x2 = 40 EU/t");
        check(near(VehicleMath.cost(b, b.topSpeed / 2, 0, true, 1), 1 + 19 / 4.0, 1e-9), "consumption: v^2 law (half speed = 5.75 EU/t)");
        check(VehicleMath.cost(b, 0.5, 5, true, 1) == VehicleMath.cost(b, 0.5, 1, true, 1), "consumption: cargo fill clamped to 1");
    }

    private static void range() {
        VehicleType b = VehicleType.BUGGY;
        check(near(VehicleMath.range(b, b.buffer, 0, 1), 10000, 1e-6), "range: full buggy ~10 000 blocks at top speed");
        check(near(VehicleMath.range(b, b.buffer / 2.0, 0, 1), 5000, 1e-6), "range: half buffer = half range");
        check(VehicleMath.range(b, 0, 0, 1) == 0, "range: empty = 0 (crawl only)");
        check(VehicleMath.range(b, 100, 0, 0) == Double.POSITIVE_INFINITY, "range: consumption 0 = endless");
        check(VehicleMath.kmh(0.5) == 36, "hud: 0.5 b/t = 36 km/h");
    }

    private static void crawl() {
        VehicleType b = VehicleType.BUGGY;
        check(near(VehicleMath.maxSpeed(b, 0), b.topSpeed * 0.1, 1e-12), "crawl: empty buffer = 10% speed");
        check(VehicleMath.maxSpeed(b, 1) == b.topSpeed, "crawl: any charge = full speed");
        check(VehicleMath.maxSpeed(b, 0) > 0, "crawl: never a dead stop");
    }

    private static void heat() {
        double h = 0;
        boolean stop = false;
        int t;
        for (t = 0; t < 20000 && !stop; t++) {
            h = VehicleMath.heatStep(h, 2, 0);
            stop = VehicleMath.heatStopped(stop, h);
        }
        check(stop && h >= 100, "heat: full cargo at top speed overheats (" + t + " ticks)");
        double h1 = 0;
        for (int i = 0; i < 20000; i++) {
            h1 = VehicleMath.heatStep(h1, 1, 0);
        }
        check(h1 < 100 && h1 > 50, "heat: empty cruise settles below 100% (" + Math.round(h1) + "%)");
        check(VehicleMath.heatStopped(true, 60), "heat: stays stopped above 50%");
        check(!VehicleMath.heatStopped(true, 50), "heat: restarts at 50%");
        check(!VehicleMath.heatStopped(false, 99.9), "heat: runs below 100%");
        check(VehicleMath.heatStopped(false, 100), "heat: stops at 100%");
        check(VehicleMath.heatStep(50, 0, 0) < 50, "heat: cools when idle");
        check(VehicleMath.heatStep(50, 0, 1) < VehicleMath.heatStep(50, 0, 0), "heat: cooling hook adds cooling");
        check(VehicleMath.heatStep(0, 0, 0) == 0, "heat: never below 0");
    }

    private static void wear() {
        check(VehicleMath.damage(200, 50) == 150, "wear: damage subtracts");
        check(VehicleMath.damage(10, 50) == 0 && VehicleMath.wrecked(0), "wear: 0 HP = wrecked, never negative");
        check(VehicleMath.damage(100, Float.NaN) == 100 && VehicleMath.damage(100, -5) == 100, "wear: NaN / negative damage ignored");
        check(VehicleMath.repair(0, 200) == 20, "wear: steel ingot repairs 10%");
        check(VehicleMath.repair(195, 200) == 200, "wear: repair capped at max");
        check(!VehicleMath.wrecked(VehicleMath.repair(0, 200)), "wear: repair lifts the wreck");
        check(near(VehicleMath.hitDamage(0.5), 24, 1e-4) && VehicleMath.hitDamage(0.15) == 0, "wear: wall hit 24 HP at top speed, none slow");
    }

    private static void limit() {
        check(VehicleMath.limitAllows(1000, 0), "limit: 0 = unlimited");
        check(VehicleMath.limitAllows(2, 3), "limit: under the limit");
        check(!VehicleMath.limitAllows(3, 3), "limit: at the limit refused");
        check(VehicleMath.limitAllows(5, -1), "limit: negative = unlimited");
    }

    private static void nbt() {
        VehicleState s = new VehicleState(VehicleType.BUGGY);
        s.energy = 12345.5;
        s.hp = 77;
        s.heat = 33;
        s.heatStop = true;
        s.cargo[0] = new ItemStack(Items.diamond, 5);
        s.cargo[8] = new ItemStack(Blocks.stone, 64);
        s.ownerId = "0f1e2d3c-0000-0000-0000-000000000001";
        s.ownerName = "Tester";
        NBTTagCompound t = new NBTTagCompound();
        s.writeNBT(t);
        VehicleState r = new VehicleState(VehicleType.BUGGY);
        r.readNBT(t);
        check(same(s, r), "nbt: state round trip (EU, HP, heat, cargo, owner, id)");
        ItemStack item = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, 0), s);
        VehicleState fromItem = ItemVehicleSVA.stateOf(item);
        check(same(s, fromItem), "nbt: item -> state keeps everything");
        check(item.getTagCompound().hasKey(EntityVehicleSVA.NBT_ROOT), "nbt: item root key " + EntityVehicleSVA.NBT_ROOT);
        VehicleState fresh = ItemVehicleSVA.stateOf(new ItemStack(ModContentSVA.vehicle, 1, 0));
        check(fresh.energy == 0 && fresh.hp == VehicleType.BUGGY.maxHp && fresh.cargoUsed() == 0, "nbt: plain item = empty, full hull");
        NBTTagCompound bad = new NBTTagCompound();
        bad.setDouble(VehicleState.K_ENERGY, Double.NaN);
        bad.setFloat(VehicleState.K_HP, 1e9F);
        VehicleState b = new VehicleState(VehicleType.BUGGY);
        b.readNBT(bad);
        check(b.energy == 0 && b.hp == VehicleType.BUGGY.maxHp, "nbt: NaN / oversized values clamped");
        check(near(s.cargoFill(), (5 / 64.0 + 1) / 9, 1e-9), "cargo: fill by stack limits");
        check(s.addEnergy(1e9) == s.maxEnergy() - 12345.5 && s.energy == s.maxEnergy(), "energy: add capped at buffer");
    }

    private static boolean same(VehicleState a, VehicleState b) {
        if (a.type != b.type || a.energy != b.energy || a.hp != b.hp || a.heat != b.heat || a.heatStop != b.heatStop
                || !a.ownerId.equals(b.ownerId) || !a.ownerName.equals(b.ownerName) || !a.vehId.equals(b.vehId)
                || a.cargo.length != b.cargo.length) {
            return false;
        }
        for (int i = 0; i < a.cargo.length; i++) {
            if (!ItemStack.areItemStacksEqual(a.cargo[i], b.cargo[i])) {
                return false;
            }
        }
        return true;
    }

    private static void charger() {
        check(VehicleMath.chargeTransfer(12800, 128, 1e6) == 128, "charger: MV moves 128 EU/t");
        check(VehicleMath.chargeTransfer(50, 128, 1e6) == 50, "charger: limited by its buffer");
        check(VehicleMath.chargeTransfer(12800, 128, 10.7) == 10, "charger: limited by the vehicle's room");
        check(VehicleMath.chargeTransfer(12800, 128, 0) == 0 && VehicleMath.chargeTransfer(0, 128, 99) == 0, "charger: nothing to move");
    }

    private static void config() {
        check(ConfigSVA.sanitize(Float.NaN, 1F, 0F, 100F) == 1F, "config: NaN -> default");
        check(ConfigSVA.sanitize(Float.POSITIVE_INFINITY, 1F, 0F, 100F) == 1F, "config: infinity -> default");
        check(ConfigSVA.sanitize(-5F, 1F, 0F, 100F) == 0F, "config: negative clamped to min");
        check(ConfigSVA.sanitize(500F, 1F, 0.1F, 100F) == 100F, "config: too big clamped to max");
        check(ConfigSVA.maxPerPlayer >= 0 && ConfigSVA.chargerRate > 0 && ConfigSVA.consumption >= 0, "config: loaded values sane");
    }

    private static void moves() {
        check(EntityVehicleSVA.moveAllowed(0.6, 0, 1, 0.5), "net: a top-speed move is accepted");
        check(!EntityVehicleSVA.moveAllowed(2.0, 0, 1, 0.5), "net: too fast refused");
        check(!EntityVehicleSVA.moveAllowed(12, 0, 10, 5), "net: teleport refused");
        check(EntityVehicleSVA.moveAllowed(0.3, 1.0, 1, 0.5), "net: a 1-block step accepted");
        check(!EntityVehicleSVA.moveAllowed(0.3, 2.0, 1, 0.5), "net: a 2-block jump refused");
        check(EntityVehicleSVA.moveAllowed(2.5, 0, 4, 0.5), "net: bunched packets (4 ticks) accepted");
    }

    private static void recipes() {
        boolean charger = false, buggy = false, workbench = false, component = false;
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            ItemStack out = ((IRecipe) o).getRecipeOutput();
            if (out == null) {
                continue;
            }
            if (out.getItem() == net.minecraft.item.Item.getItemFromBlock(ModContentSVA.charger)) {
                charger = true;
            }
            if (out.getItem() == net.minecraft.item.Item.getItemFromBlock(ModContentSVA.workbench)) {
                workbench = true;
            }
            if (out.getItem() == ModContentSVA.vehicle) {
                buggy = true;
            }
            if (out.getItem() == ModContentSVA.component) {
                component = true;
            }
        }
        check(charger, "recipe: charging station registered");
        check(workbench, "recipe: engineering workbench on the crafting table");
        check(!buggy, "recipe: the temporary crafting-table buggy is gone");
        check(!component, "recipe: no component on the crafting table");
    }

    // ------------------------------------------------------------------ the workbench

    private static ItemStack[] emptyGrid() {
        return new ItemStack[WorkbenchRecipeSVA.CELLS];
    }

    /** Lays a recipe's ingredients (display stacks) at offset (ox, oy), mirrored or not. */
    public static ItemStack[] laid(WorkbenchRecipeSVA r, int ox, int oy, boolean mirror) {
        ItemStack[] g = emptyGrid();
        for (int y = 0; y < r.height; y++) {
            for (int x = 0; x < r.width; x++) {
                Object c = r.cell(mirror ? r.width - 1 - x : x, y);
                if (c != null) {
                    g[(oy + y) * 5 + ox + x] = WorkbenchRecipeSVA.displayOf(c);
                }
            }
        }
        return g;
    }

    private static void workbenchMatching() {
        WorkbenchRecipeSVA t = new WorkbenchRecipeSVA("test", WorkbenchRecipeSVA.Category.COMPONENTS, new ItemStack(Items.stick), Tier.MV, 0, 0,
                "AB", "C ", 'A', Blocks.dirt, 'B', Blocks.stone, 'C', Blocks.cobblestone);
        check(t.width == 2 && t.height == 2, "match: pattern trimmed to 2x2");
        boolean all = true;
        for (int oy = 0; oy <= 3; oy++) {
            for (int ox = 0; ox <= 3; ox++) {
                int[] m = t.match(laid(t, ox, oy, false));
                all &= m != null && m[0] == ox && m[1] == oy && m[2] == 0;
            }
        }
        check(all, "match: a small pattern matches anywhere in the 5x5 grid (16 places)");
        int[] mm = t.match(laid(t, 2, 1, true));
        check(mm != null && mm[2] == 1, "match: mirrored layout matches (flag set)");
        ItemStack[] g = laid(t, 1, 1, false);
        g[24] = new ItemStack(Blocks.dirt);
        check(t.match(g) == null, "match: an extra item elsewhere fails");
        g = laid(t, 1, 1, false);
        g[6] = new ItemStack(Blocks.gravel);
        check(t.match(g) == null, "match: a wrong item fails");
        check(t.match(emptyGrid()) == null, "match: empty grid fails");
        WorkbenchRecipeSVA wheel = WorkbenchRecipesSVA.byId("wheel");
        check(wheel != null && wheel.match(laid(wheel, 1, 1, false)) != null && wheel.match(laid(wheel, 0, 2, false)) != null,
                "match: wheel 3x3 in the centre and in a corner");
        ItemStack[] wg = laid(wheel, 1, 1, false);
        check(wheel.usedSlots(wheel.match(wg)).length == 9, "match: wheel uses 9 cells");
        // ore dictionary: the centre steel ingot is "ingotSteel"; the core's steel fits, iron there does not
        wg[12] = ModItems.ingot.stackOf(Material.STEEL);
        check(wheel.match(wg) != null, "match: the core steel ingot fits the ore-dict cell");
        wg[12] = new ItemStack(Items.iron_ingot);
        check(wheel.match(wg) == null, "match: iron in the steel cell fails");
        WorkbenchRecipeSVA buggy = WorkbenchRecipesSVA.byId("buggy");
        ItemStack[] bg = laid(buggy, 0, 0, false);
        check(buggy.width == 5 && buggy.height == 5 && buggy.match(bg) != null, "match: buggy 5x5 in place");
        check(WorkbenchRecipesSVA.find(bg) == buggy, "match: find() picks the buggy");
        bg[5] = new ItemStack(Blocks.dirt);
        check(buggy.match(bg) == null, "match: buggy with a wrong item fails");
        bg = laid(buggy, 0, 0, false);
        bg[0] = null;
        check(buggy.match(bg) == null, "match: buggy with a missing item fails");
        bg = laid(buggy, 0, 0, false);
        bg[20] = new ItemStack(Blocks.dirt);
        check(buggy.match(bg) == null, "match: buggy with an item in an empty corner fails");
    }

    private static void workbenchRecipes() {
        WorkbenchRecipeSVA b = WorkbenchRecipesSVA.byId("buggy");
        check(b != null && b.eu == 200000L && b.ticks == 600 && !b.instant() && b.category == WorkbenchRecipeSVA.Category.VEHICLES
                && b.tier == Tier.MV, "buggy: 200 000 EU, 600 ticks, MV vehicle");
        ItemStack out = b.result();
        VehicleState st = ItemVehicleSVA.stateOf(out);
        check(out.getItem() == ModContentSVA.vehicle && out.getItemDamage() == VehicleType.BUGGY.ordinal()
                && out.hasTagCompound() && out.getTagCompound().hasKey(EntityVehicleSVA.NBT_ROOT)
                && st.energy == 0 && st.hp == VehicleType.BUGGY.maxHp && st.cargoUsed() == 0, "buggy: product carries the default state NBT");
        check(!ItemVehicleSVA.stateOf(b.result()).vehId.equals(st.vehId), "buggy: each product gets its own id");
        WorkbenchRecipeSVA w = WorkbenchRecipesSVA.byId("wheel");
        check(w.instant() && w.result().stackSize == 4 && w.result().getItem() == ModContentSVA.component, "wheel: instant, x4");
        java.util.Set<String> ids = new java.util.HashSet<String>();
        boolean unique = true;
        for (WorkbenchRecipeSVA r : WorkbenchRecipesSVA.all()) {
            unique &= ids.add(r.id);
        }
        check(unique, "recipes: ids unique (" + ids.size() + " recipes)");
        boolean distinct = true;
        String bad = "";
        for (WorkbenchRecipeSVA r : WorkbenchRecipesSVA.all()) {
            boolean own = WorkbenchRecipesSVA.find(laid(r, (5 - r.width) / 2, (5 - r.height) / 2, false)) == r;
            distinct &= own;
            if (!own) {
                bad += " " + r.id;
            }
        }
        check(distinct, "recipes: every layout finds its own recipe" + bad);
    }

    private static void workbenchUpgrades() {
        check(WorkbenchMath.ticks(600, 0) == 600 && WorkbenchMath.ticks(600, 1) == 420 && WorkbenchMath.ticks(600, 2) == 294,
                "upgrades: overclocker time x0.7 each");
        check(WorkbenchMath.ticks(600, 100) == WorkbenchMath.ticks(600, 16) && WorkbenchMath.ticks(1, 16) == 1, "upgrades: capped at 16, at least 1 tick");
        check(WorkbenchMath.eu(200000, 0, 0) == 200000 && WorkbenchMath.eu(200000, 1, 0) == 224000, "upgrades: overclocker EU x1.12");
        check(WorkbenchMath.eu(200000, 0, 1) == 160000 && WorkbenchMath.eu(200000, 0, 10) == WorkbenchMath.eu(200000, 0, 4),
                "upgrades: economizer x0.8, at most 4");
        check(WorkbenchMath.eu(0, 3, 3) == 0 && WorkbenchMath.ticks(0, 3) == 0, "upgrades: instant recipes stay free");
        check(WorkbenchMath.buffer(0) == 2000000 && WorkbenchMath.buffer(2) == 4000000 && WorkbenchMath.buffer(99) == 18000000,
                "upgrades: buffer 2M + 1M per storage (16 max)");
        check(WorkbenchMath.inputTier(0, false) == Tier.MV && WorkbenchMath.inputTier(1, false) == Tier.HV
                && WorkbenchMath.inputTier(3, false) == Tier.IV && WorkbenchMath.inputTier(0, true) == Tier.max(),
                "upgrades: transformer +1 input tier, universal = any");
        check(WorkbenchMath.requiredInput(Tier.MV) == Tier.MV && WorkbenchMath.requiredInput(Tier.EV) == Tier.MV
                && WorkbenchMath.requiredInput(Tier.QV) == Tier.HV && WorkbenchMath.requiredInput(Tier.XV) == Tier.EV
                && WorkbenchMath.requiredInput(Tier.SV) == Tier.IV, "tier: QV/XV/SV vehicles need HV/EV/IV input");
        check(!WorkbenchMath.canBuild(Tier.QV, WorkbenchMath.inputTier(0, false)) && WorkbenchMath.canBuild(Tier.QV, WorkbenchMath.inputTier(1, false))
                && WorkbenchMath.canBuild(Tier.SV, WorkbenchMath.inputTier(3, false)), "tier: transformers unlock QV-SV");
        long paid = 0;
        for (int p = 0; p < 600; p++) {
            paid += WorkbenchMath.share(200000, paid, 600, p);
        }
        check(paid == 200000, "assembly: shares add up to exactly the cost (" + paid + ")");
        check(WorkbenchMath.comparator(0, 600) == 0 && WorkbenchMath.comparator(1, 600) == 1 && WorkbenchMath.comparator(300, 600) == 7
                && WorkbenchMath.comparator(600, 600) == 15, "comparator: 0..15 by progress");
    }

    private static void workbenchPlanner() {
        WorkbenchRecipeSVA wheel = WorkbenchRecipesSVA.byId("wheel");
        Object[] cells = wheel.layout();
        ItemStack steel = ModItems.ingot.stackOf(Material.STEEL);
        ItemStack[] inv = {new ItemStack(Items.iron_ingot, 10), null, new ItemStack(ModItems.rubber, 4), steel.copy()};
        int[] plan = FillPlannerSVA.plan(cells, inv);
        check(FillPlannerSVA.complete(plan) && plan[0] == FillPlannerSVA.EMPTY && plan[6] == 0 && plan[7] == 2 && plan[12] == 3,
                "planner: wheel laid out from iron x10, rubber x4, steel");
        check(inv[0].stackSize == 10 && inv[2].stackSize == 4, "planner: pure (the inventory is untouched)");
        java.util.List<FillPlannerSVA.Need> needs = FillPlannerSVA.needs(cells, plan);
        check(needs.size() == 3 && needs.get(0).need == 4 && needs.get(1).need == 4 && needs.get(2).need == 1 && FillPlannerSVA.missing(needs).isEmpty(),
                "planner: needs iron 4, rubber 4, steel 1");
        ItemStack[] poor = {new ItemStack(Items.iron_ingot, 4), new ItemStack(ModItems.rubber, 1), new ItemStack(ModItems.rubber, 1)};
        int[] p2 = FillPlannerSVA.plan(cells, poor);
        java.util.List<FillPlannerSVA.Need> miss = FillPlannerSVA.missing(FillPlannerSVA.needs(cells, p2));
        check(!FillPlannerSVA.complete(p2) && miss.size() == 2 && miss.get(0).need - miss.get(0).have == 2 && miss.get(1).need - miss.get(1).have == 1,
                "planner: short of rubber x2 and steel x1");
        int[] p3 = FillPlannerSVA.plan(WorkbenchRecipesSVA.byId("buggy").layout(), new ItemStack[0]);
        int missing = 0;
        for (int v : p3) {
            missing += v == FillPlannerSVA.MISSING ? 1 : 0;
        }
        check(missing == 23, "planner: empty inventory - all 23 buggy cells missing (" + missing + ")");
    }

    /** The NEI page layout (WorkbenchNeiLayoutSVA - pure, the NEI handler draws from it; no NEI class is touched here). */
    private static void neiLayout() {
        int G = WorkbenchRecipeSVA.GRID, S = com.sva.workbench.WorkbenchNeiLayoutSVA.STEP;
        int gx = com.sva.workbench.WorkbenchNeiLayoutSVA.GRID_X, gy = com.sva.workbench.WorkbenchNeiLayoutSVA.GRID_Y;
        boolean inside = true, counts = true, centred = true, same = true, craftable = true, shown = true, ghost = true;
        String bad = "";
        for (WorkbenchRecipeSVA r : WorkbenchRecipesSVA.all()) {
            java.util.List<com.sva.workbench.WorkbenchNeiLayoutSVA.Cell> cells = com.sva.workbench.WorkbenchNeiLayoutSVA.cells(r);
            int[] o = com.sva.workbench.WorkbenchNeiLayoutSVA.origin(r);
            boolean ok = true;
            if (cells.size() != com.sva.workbench.WorkbenchNeiLayoutSVA.patternCount(r)) {
                counts = ok = false;
            }
            // centred: left / right (top / bottom) margins differ by at most one cell
            int left = o[0], right = G - r.width - o[0], top = o[1], bottom = G - r.height - o[1];
            if (left < 0 || right < 0 || top < 0 || bottom < 0 || Math.abs(left - right) > 1 || Math.abs(top - bottom) > 1) {
                centred = ok = false;
            }
            ItemStack[] grid = new ItemStack[WorkbenchRecipeSVA.CELLS];
            java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
            for (com.sva.workbench.WorkbenchNeiLayoutSVA.Cell c : cells) {
                if (c.col < 0 || c.col >= G || c.row < 0 || c.row >= G || c.index != c.row * G + c.col || !seen.add(Integer.valueOf(c.index))
                        || c.x != gx + c.col * S || c.y != gy + c.row * S) {
                    inside = ok = false;
                    continue;
                }
                int px = c.col - o[0], py = c.row - o[1];
                if (px < 0 || px >= r.width || py < 0 || py >= r.height || r.cell(px, py) != c.ingredient) {
                    same = ok = false;
                }
                if (c.x + com.sva.workbench.WorkbenchNeiLayoutSVA.GHOST_DX != com.sva.inventory.ContainerWorkbenchSVA.GRID_X + c.col * 18
                        || c.y + com.sva.workbench.WorkbenchNeiLayoutSVA.GHOST_DY != com.sva.inventory.ContainerWorkbenchSVA.GRID_Y + c.row * 18) {
                    ghost = ok = false;
                }
                ItemStack d = WorkbenchRecipeSVA.displayOf(c.ingredient);
                if (d == null) {
                    shown = ok = false;
                } else {
                    grid[c.index] = d;
                }
            }
            // what the page shows, put into a workbench grid, is that very recipe
            if (shown && WorkbenchRecipesSVA.find(grid) != r) {
                craftable = ok = false;
            }
            if (!ok && bad.length() < 120) {
                bad += " " + r.id;
            }
        }
        int n = WorkbenchRecipesSVA.all().size();
        check(counts, "nei: every page shows as many items as its recipe's pattern (" + n + " recipes)" + bad);
        check(inside, "nei: every cell inside the 5x5 page grid on the 18-px step, no cell twice");
        check(centred && WorkbenchRecipesSVA.byId("wheel") != null
                && java.util.Arrays.equals(com.sva.workbench.WorkbenchNeiLayoutSVA.origin(WorkbenchRecipesSVA.byId("wheel")), new int[] {1, 1})
                && java.util.Arrays.equals(com.sva.workbench.WorkbenchNeiLayoutSVA.origin(WorkbenchRecipesSVA.byId("buggy")), new int[] {0, 0}),
                "nei: small patterns centred (3x3 at 1,1), a 5x5 fills the grid");
        check(same, "nei: each page cell holds the recipe's own ingredient");
        check(shown && craftable, "nei: every page laid into a workbench grid matches its own recipe");
        check(ghost, "nei: «?» ghost offset maps every page cell onto the workbench grid slot");
        check(gx - 4 >= 0 && gx + G * S <= com.sva.workbench.WorkbenchNeiLayoutSVA.ARROW_X
                && com.sva.workbench.WorkbenchNeiLayoutSVA.ARROW_X + com.sva.workbench.WorkbenchNeiLayoutSVA.ARROW_W <= com.sva.workbench.WorkbenchNeiLayoutSVA.OUT_X - 4
                && com.sva.workbench.WorkbenchNeiLayoutSVA.OUT_X + 16 + 4 < 148 && gy + G * S <= 98,
                "nei: page fits NEI's 166-px recipe area (grid | arrow | output, clear of the «?» button, text below)");
        check(WorkbenchMath.transformersFor(Tier.MV) == 0 && WorkbenchMath.transformersFor(Tier.EV) == 0 && WorkbenchMath.transformersFor(Tier.QV) == 1
                && WorkbenchMath.transformersFor(Tier.XV) == 2 && WorkbenchMath.transformersFor(Tier.SV) == 3,
                "nei: transformers needed MV/EV 0, QV 1, XV 2, SV 3");
        check(SelfTestSVA.class.getClassLoader().getResource("com/sva/nei/NEISiliconAgeVehiclesConfig.class") != null,
                "nei: plugin class packed as a resource (never loaded here - NEI loads it by name)");
    }

    private static void components() {
        java.util.Set<String> keys = new java.util.HashSet<String>();
        boolean ok = true;
        for (ComponentType t : ComponentType.values()) {
            ok &= keys.add(t.key) && t.tier != null && ComponentType.byMeta(t.meta()) == t;
            ok &= WorkbenchRecipesSVA.byId(t.key) != null;
        }
        check(ok, "components: " + ComponentType.values().length + " metas / keys unique, tiers set, each has a workbench recipe");
        check(ComponentType.WHEEL.meta() == 0 && ComponentType.ENERGY_BLOCK_SV.meta() == 22, "components: metas fixed (append-only)");
        ItemStack s = ModContentSVA.component.stackOf(ComponentType.HULL_III, 1);
        check(s.getUnlocalizedName().endsWith(".component.hull3"), "components: unlocalized name per key");
    }

    // ------------------------------------------------------------------ stage A: gases, helium, hover, pick-up

    private static void gasTanks() {
        com.sc.util.ArmorGasSC.Gas H = com.sc.util.ArmorGasSC.Gas.HYDROGEN, HE = com.sc.util.ArmorGasSC.Gas.HELIUM,
                AR = com.sc.util.ArmorGasSC.Gas.ARGON;
        VehicleState s = new VehicleState(VehicleType.HOVER);
        check(s.gasCapacity(H) == 4000 && s.gasCapacity(HE) == 2000 && s.gasCapacity(AR) == 0, "gas: hover tanks H2 4000, He 2000, no argon");
        check(new VehicleState(VehicleType.BUGGY).gasCapacity(H) == 0 && !VehicleType.BUGGY.gases.any(), "gas: the buggy has no tanks");
        check(s.fillGas(H, 1500, true) == 1500 && s.gas(H) == 0, "gas: simulate fills nothing");
        check(s.fillGas(H, 3000, false) == 3000 && s.fillGas(H, 3000, false) == 1000 && s.gas(H) == 4000, "gas: fill capped at the tank (3000 + 1000)");
        check(s.fillGas(AR, 100, false) == 0, "gas: no tank - nothing goes in");
        check(s.drainGas(H, 500, false) == 500 && s.gas(H) == 3500, "gas: drain");
        int took = 0;
        for (int i = 0; i < 20; i++) {
            took += s.drainGasFraction(H, 0.1);              // 2 mB/s for one second
        }
        check(took == 2 && s.gas(H) == 3498, "gas: 20 x 0.1 mB fractions = 2 whole mB (" + took + ")");
        s.drainGasFraction(H, 0.25);
        check(s.gasFrac[H.ordinal()] > 0.2 && s.gasFrac[H.ordinal()] < 0.3 && s.gas(H) == 3498, "gas: the part below a mB is kept");
        NBTTagCompound t = new NBTTagCompound();
        s.writeNBT(t);
        VehicleState r = new VehicleState(VehicleType.HOVER);
        r.readNBT(t);
        check(r.gas(H) == 3498 && r.gasFrac[H.ordinal()] == s.gasFrac[H.ordinal()] && r.gas(HE) == 0, "gas: NBT round trip keeps mB and the fraction");
        NBTTagCompound bad = new NBTTagCompound();
        NBTTagCompound g = new NBTTagCompound();
        g.setInteger(H.key(), 999999);
        g.setInteger(AR.key(), 50);
        bad.setTag(VehicleState.K_GASES, g);
        VehicleState b = new VehicleState(VehicleType.HOVER);
        b.readNBT(bad);
        check(b.gas(H) == 4000 && b.gas(AR) == 0, "gas: NBT clamped to the tanks");
        VehicleState e = new VehicleState(VehicleType.HOVER);
        e.fillGas(H, 1, false);
        int got = 0;
        for (int i = 0; i < 30; i++) {
            got += e.drainGasFraction(H, 0.1);
        }
        check(got == 1 && e.gas(H) == 0 && e.gasFrac[H.ordinal()] == 0, "gas: an empty tank keeps no fraction, never below 0");
        ItemStack item = ItemVehicleSVA.withState(new ItemStack(ModContentSVA.vehicle, 1, VehicleType.HOVER.ordinal()), s);
        check(ItemVehicleSVA.stateOf(item).gas(H) == 3498, "gas: the item keeps the gas");
        VehicleState full = new VehicleState(VehicleType.HOVER);
        full.fillAllGases();
        check(full.gas(H) == 4000 && full.gas(HE) == 2000, "gas: fillAllGases (creative full stack)");
    }

    private static void helium() {
        check(VehicleMath.heliumCooling(30, 1000) == 0, "helium: idle below 40% heat");
        check(near(VehicleMath.heliumCooling(80, 1000), 0.05, 1e-12), "helium: full pump 0.05 %/t when hot");
        check(near(VehicleMath.heliumCooling(41, 1000), 0.02, 1e-12), "helium: proportional just above 40% (0.02 %/t at 41%)");
        check(VehicleMath.heliumCooling(80, 0) == 0, "helium: empty tank cools nothing");
        check(near(VehicleMath.heliumUse(0.05), 0.025, 1e-12), "helium: 2 heat-% per mB (full pump = 0.5 mB/s)");
        // boost at top speed (load 1.35^2): overheats without helium, holds with it
        double load = 1.35 * 1.35, h1 = 0, h2 = 0, he = 2000;
        boolean stop1 = false, stop2 = false;
        for (int i = 0; i < 12000; i++) {
            h1 = VehicleMath.heatStep(h1, load, 0);
            stop1 |= VehicleMath.heatStopped(stop1, h1);
            double c = VehicleMath.heliumCooling(h2, he);
            he -= VehicleMath.heliumUse(c);
            h2 = VehicleMath.heatStep(h2, load, c);
            stop2 |= VehicleMath.heatStopped(stop2, h2);
        }
        check(stop1, "helium: boosting without helium overheats");
        check(!stop2 && h2 < 45, "helium: boosting with helium holds " + Math.round(h2) + "% (spent " + Math.round(2000 - he) + " mB in 10 min)");
    }

    private static void hover() {
        double base = HoverMath.targetY(64, 64, false);
        check(near(base, 65.5, 1e-9) && near(HoverMath.targetY(64, Double.NaN, true), 66.8, 1e-9), "hover: holds 1.5 (Space 2.8) over the surface");
        check(near(HoverMath.targetY(64, 70, false), 64 + HoverMath.HOVER_CAP, 1e-9), "hover: a wall under the nose lifts it to the cap only (4 over the centre)");
        check(HoverMath.targetY(Double.NaN, 70, false) == Double.NEGATIVE_INFINITY, "hover: no surface under the centre - nothing to hold");
        boolean never = true;
        for (double surf = 0; surf < 100; surf += 7.3) {
            for (double hi = -5; hi < 50; hi += 3.1) {
                never &= HoverMath.targetY(surf, surf + hi, true) - surf <= HoverMath.HOVER_CAP + 1e-9;
            }
        }
        check(never, "hover: the target is never over the cap");
        check(HoverMath.verticalSpeed(70, 65.5, true, 0) == -HoverMath.SINK && HoverMath.verticalSpeed(60, 65.5, true, 0) == HoverMath.CLIMB,
                "hover: sinks at 0.15 / climbs at 0.2 b/t at most");
        check(near(HoverMath.verticalSpeed(65.4, 65.5, true, 0), 0.02, 1e-9), "hover: eases into the height");
        double y = 65.5, vy = 0;
        int ticks = 0;
        boolean gentle = true;
        for (; ticks < 400 && y > 64; ticks++) {
            vy = HoverMath.verticalSpeed(y, 65.5, false, vy);
            gentle &= vy >= -HoverMath.GLIDE - 1e-12;
            y += vy;
        }
        check(ticks < 400 && gentle, "hover: no lift - glides down 1.5 blocks in " + ticks + " ticks, never faster than 0.12 b/t");
        check(HoverMath.verticalSpeed(80, Double.NEGATIVE_INFINITY, true, 0) == -HoverMath.SINK, "hover: over a ravine it sinks");
        check(HoverMath.climbAllowed(true, 3.5) && !HoverMath.climbAllowed(true, 4.5) && !HoverMath.climbAllowed(false, 1),
                "hover: climbing only with lift under the cap");
        check(!HoverMath.mustDescend(true, 3) && HoverMath.mustDescend(true, 6) && HoverMath.mustDescend(false, 2) && !HoverMath.mustDescend(false, 0.2)
                && HoverMath.mustDescend(true, Double.NaN), "hover: must come down - far over the cap, or in the air without lift");
        VehicleType h = VehicleType.HOVER;
        check(near(h.gases.liftPerTick(false), 0.1, 1e-12) && near(h.gases.liftPerTick(true), 0.25, 1e-12), "hover: H2 2 mB/s, boost 5 mB/s");
        check(near(HoverMath.maxSpeed(h, true, true), 0.84 * 1.35, 1e-9) && HoverMath.maxSpeed(h, true, false) == 0.84
                && HoverMath.maxSpeed(h, false, true) == 0, "hover: x3 sprint (0.84 b/t), boost x1.35, no lift - no thrust");
        check(near(HoverMath.secondsLeft(4000, 2), 2000, 1e-9) && near(HoverMath.secondsLeft(4000, 5), 800, 1e-9),
                "hover: a full H2 tank = 33 min hovering, 13 min on boost");
    }

    private static void hoverType() {
        VehicleType h = VehicleType.HOVER;
        check(h.ordinal() == 1 && h.movement == com.sva.vehicle.Movement.HOVER && VehicleType.BUGGY.movement == com.sva.vehicle.Movement.GROUND,
                "hover: meta 1, HOVER movement (buggy stays GROUND)");
        check(h.tier == Tier.QV && h.buffer == 4000000 && h.seats == 1 && h.cargoSlots == 9 && near(h.topSpeed, 0.84, 1e-9),
                "hover: QV, 4M EU, 1 seat, 9 cargo");
        check(near(VehicleMath.cost(h, h.topSpeed, 0, true, 1), 80, 1e-9) && near(VehicleMath.cost(h, 0, 0, true, 1), 4, 1e-9), "hover: 80 EU/t at speed, 4 hovering");
        check(h.modelScale == 1.15F && near(h.seatY, 0.54, 1e-9) && h.seatOffsets.length == 1, "hover: model x1.15, seat on the saddle");
        WorkbenchRecipeSVA r = WorkbenchRecipesSVA.byId("hover");
        check(r != null && r.eu == 8000000L && r.tier == Tier.QV && r.width == 5 && r.height == 5 && ItemVehicleSVA.typeOf(r.result()) == h,
                "hover: workbench recipe 5x5, 8M EU, QV");
        check(!WorkbenchMath.canBuild(Tier.QV, WorkbenchMath.inputTier(0, false)) && WorkbenchMath.canBuild(Tier.QV, WorkbenchMath.inputTier(1, false)),
                "hover: needs one transformer on the workbench");
        check(WorkbenchRecipesSVA.byId("gasColumn") != null, "gas column: workbench recipe");
        boolean table = false;
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            ItemStack out = ((IRecipe) o).getRecipeOutput();
            table |= out != null && out.getItem() == net.minecraft.item.Item.getItemFromBlock(ModContentSVA.gasColumn);
        }
        check(table, "gas column: crafting-table recipe");
    }

    private static void pickUp() {
        check(EntityVehicleSVA.pickUpCheck(true, false, 4) == EntityVehicleSVA.PICK_OK, "pick-up: owner, empty, near - ok");
        check(EntityVehicleSVA.pickUpCheck(true, true, 4) == EntityVehicleSVA.PICK_OCCUPIED, "pick-up: someone seated - refused");
        check(EntityVehicleSVA.pickUpCheck(false, false, 4) == EntityVehicleSVA.PICK_NOT_OWNER, "pick-up: not the owner - refused");
        check(EntityVehicleSVA.pickUpCheck(true, false, 100) == EntityVehicleSVA.PICK_FAR, "pick-up: further than 8 blocks - refused");
    }

    // ------------------------------------------------------------------ stage B: aircraft (heli, plane, runway)

    private static void aircraftTypes() {
        VehicleType h = VehicleType.HELI, p = VehicleType.PLANE;
        check(h.ordinal() == 2 && p.ordinal() == 3 && h.movement == com.sva.vehicle.Movement.HELI && p.movement == com.sva.vehicle.Movement.PLANE
                && h.movement.flies && p.movement.flies && !com.sva.vehicle.Movement.HOVER.flies, "aircraft: metas 2 / 3, HELI / PLANE movement (flies)");
        check(h.tier == Tier.HV && h.buffer == 3000000 && h.seats == 2 && h.cargoSlots == 9 && near(h.topSpeed, 0.70, 1e-9)
                && h.seatOffsets.length == 2 && h.modelScale == 1.7F, "heli: HV, 3M EU, 2 seats, 9 cargo, x2.5 sprint, model x1.7 (rider fit)");
        check(p.tier == Tier.HV && p.buffer == 4000000 && p.seats == 3 && p.cargoSlots == 9 && near(p.topSpeed, 1.12, 1e-9)
                && p.seatOffsets.length == 3 && p.modelScale == 2.9F, "plane: HV, 4M EU, 3 seats, 9 cargo, x4 sprint, model x2.9 (rider fit)");
        check(h.platformSize == PlatformSize.P5x5 && p.platformSize == PlatformSize.P5x5, "aircraft: 5x5 charging platform");
        check(h.gases.capacity(com.sc.util.ArmorGasSC.Gas.HELIUM) == 1000 && h.gases.coolant == com.sc.util.ArmorGasSC.Gas.HELIUM
                && h.gases.liftGas == null && p.gases.coolant == com.sc.util.ArmorGasSC.Gas.HELIUM, "aircraft: helium tank 1000 mB for cooling, no lift gas");
        // a seated player fits: cushion top (seatY + 0.25) and seats shoulder-wide / staggered
        // (the head / chest inside the hull: riderFit) - here: the hip at most 0.15 into the seat surface, seats apart
        check(h.seatY + 0.203 <= 0.66 * h.modelScale && h.seatY + 0.203 >= 0.66 * h.modelScale - 0.3 - 0.15
                && Math.abs(h.seatOffsets[0][1] - h.seatOffsets[1][1]) >= 0.6 && p.seatOffsets[0][0] - p.seatOffsets[1][0] >= 1.0,
                "aircraft: heli seats side by side (" + Math.abs(h.seatOffsets[0][1] - h.seatOffsets[1][1]) + " apart), plane staggered 1.4");
    }

    private static void heli() {
        VehicleType h = VehicleType.HELI;
        double hover = HeliMath.cost(h, true, 0, 0, 0, true), fwd = HeliMath.cost(h, true, h.topSpeed, 0, 0, true);
        check(near(hover, 110, 1e-9) && near(fwd, 90, 1e-9) && hover > fwd, "heli: hovering 110 EU/t costs more than flying forward 90");
        check(near(HeliMath.cost(h, false, 0, 0, 0, true), 3, 1e-9) && HeliMath.cost(h, true, 0, 0, 0, false) == 0,
                "heli: rotor idling on the ground 3 EU/t, nobody at the controls 0");
        check(HeliMath.cost(h, true, 0, HeliMath.CLIMB, 0, true) > hover && HeliMath.cost(h, true, 0, -HeliMath.DESCEND, 0, true) < hover
                && near(HeliMath.cost(h, true, 0, 0, 1, true), 137.5, 1e-9), "heli: climbing costs more, descending less, full cargo x1.25");
        // the ceiling: holding Space from Y 150
        double y = 150, vy = 0, top = 0;
        for (int i = 0; i < 2000; i++) {
            vy = HeliMath.poweredVy(vy, true, false, y);
            y += vy;
            top = Math.max(top, y);
        }
        check(top <= com.sva.vehicle.FlightMath.CEILING + 0.01 && top > 199, "heli: climbs to the ceiling and no higher (" + Math.round(top * 100) / 100.0 + ")");
        check(HeliMath.targetVy(true, false, 205) < 0 && HeliMath.targetVy(false, false, 120) == 0 && HeliMath.targetVy(false, true, 120) == -HeliMath.DESCEND,
                "heli: over the ceiling it settles down; neutral holds the height; down = 0.3 b/t");
        // autorotation from 100 blocks up
        y = 100;
        vy = 0;
        int t = 0;
        boolean slow = true;
        double first = HeliMath.autoVy(0);
        for (; y > 0 && t < 5000; t++) {
            vy = HeliMath.autoVy(vy);
            slow &= vy >= -HeliMath.AUTO_SINK - 1e-12;
            y += vy;
        }
        check(first <= -HeliMath.AUTO_START && slow && near(vy, -0.15, 1e-9), "heli: autorotation sinks at once, never faster than 0.15 b/t (3 m/s)");
        check(t >= 600, "heli: autorotation from 100 blocks takes " + t + " ticks (" + t / 20 + " s)");
        float hp = h.maxHp;
        check(HeliMath.landingDamage(hp, 0.15, true) <= 0.1 * hp && HeliMath.landingDamage(hp, 3.0, true) <= 0.1 * hp + 1e-9
                && HeliMath.landingDamage(hp, 0.15, true) > 0, "heli: autorotation landing costs the hull " + Math.round(HeliMath.landingDamage(hp, 0.15, true))
                + " HP, never over 10% (" + hp * 0.1 + ")");
        check(HeliMath.landingDamage(hp, HeliMath.DESCEND, false) == 0 && HeliMath.landingDamage(hp, 0.6, false) > 0,
                "heli: a powered landing at the full descent rate is free, a drop is not");
        // helium: hovering (load 1.2) overheats without helium, holds with it; forward flight settles below 100 %
        double l = com.sva.vehicle.FlightMath.load(h, hover), h1 = 0, h2 = 0, h3 = 0, he = 1000;
        boolean st1 = false, st2 = false;
        for (int i = 0; i < 24000; i++) {
            h1 = VehicleMath.heatStep(h1, l, 0);
            st1 |= VehicleMath.heatStopped(st1, h1);
            double c = VehicleMath.heliumCooling(h2, he);
            he -= VehicleMath.heliumUse(c);
            h2 = VehicleMath.heatStep(h2, l, c);
            st2 |= VehicleMath.heatStopped(st2, h2);
            h3 = VehicleMath.heatStep(h3, com.sva.vehicle.FlightMath.load(h, fwd), 0);
        }
        check(near(l, 1.2, 1e-9) && st1 && !st2 && h3 < 100, "heli: a long hover overheats without helium, not with it (He left " + Math.round(he)
                + " mB of 1000 after 20 min); forward flight settles at " + Math.round(h3) + "%");
    }

    private static void plane() {
        VehicleType p = VehicleType.PLANE;
        // lift / stall
        check(PlaneMath.climbCapacity(PlaneMath.STALL) == 0 && near(PlaneMath.climbCapacity(0.75), PlaneMath.CLIMB, 1e-9)
                && PlaneMath.climbCapacity(0.6) > 0 && PlaneMath.climbCapacity(0.6) < PlaneMath.climbCapacity(0.7), "plane: lift grows with airspeed over the stall");
        check(PlaneMath.airVy(0.4, 0, true, false, true, 120) < 0 && PlaneMath.airVy(0.4, 0, false, false, true, 120) <= -PlaneMath.GLIDE_START,
                "plane: below the stall the nose drops even with Space held");
        check(PlaneMath.airVy(0.9, 0, false, false, true, 120) == 0 && PlaneMath.airVy(0.9, 0, true, false, true, 120) > 0
                && PlaneMath.airVy(0.9, 0, false, true, true, 120) < 0, "plane: over the stall - level holds, Space climbs, down dives");
        check(PlaneMath.airAccel(p, 0.4, 0, -0.3, false, true) > 0 && PlaneMath.airAccel(p, 0.4, 0, -0.3, false, false) > 0,
                "plane: a stalled nose-down gains speed (recovery)");
        // it can't hover: full throttle, Space held, from the stall speed - it never stays slow in the air
        double v = PlaneMath.STALL, vy = 0, minV = 9;
        for (int i = 0; i < 600; i++) {
            vy = PlaneMath.airVy(v, vy, true, false, true, 120);
            v += PlaneMath.airAccel(p, v, 1, vy, true, true);
            if (i > 200) {
                minV = Math.min(minV, v);
            }
        }
        check(minV > PlaneMath.STALL && vy > 0.1, "plane: a full-throttle climb settles at " + Math.round(v * 72) + " km/h, " + Math.round(vy * 200) / 10.0 + " m/s up");
        // take-off run
        double ground = PlaneMath.takeoffDistance(p, false), runway = PlaneMath.takeoffDistance(p, true);
        check(ground >= 25 && ground <= 36, "plane: take-off run ~30 blocks (" + Math.round(ground) + ")");
        check(runway / ground > 0.4 && runway / ground < 0.6, "plane: on a runway about half (" + Math.round(runway) + " blocks)");
        // cost: take-off run, cruise, the discount at Y >= 100
        check(near(PlaneMath.cost(p, 1, false, 0, 64, 0, true), 120, 1e-9) && near(PlaneMath.cost(p, 1, true, 0, 80, 0, true), 50, 1e-9)
                && near(PlaneMath.cost(p, 1, true, 0, 120, 0, true), 40, 1e-9), "plane: take-off run 120 EU/t, cruise 50, at Y >= 100 40 (-20%)");
        check(near(PlaneMath.cost(p, 0.5, true, 0, 120, 0, true) / PlaneMath.cost(p, 0.5, true, 0, 80, 0, true), 0.8, 1e-9)
                && PlaneMath.cost(p, 1, true, PlaneMath.CLIMB, 120, 0, true) > PlaneMath.cost(p, 1, true, 0, 120, 0, true),
                "plane: cruise discount x0.8 at any throttle; climbing costs more");
        check(near(com.sva.vehicle.FlightMath.cruiseCost(p, 0, 150), 40, 1e-9) && near(com.sva.vehicle.FlightMath.cruiseCost(p, 0, 70), 50, 1e-9),
                "plane: the HUD's cruise cost follows the height");
        // a 1:8 glide without power
        v = PlaneMath.GLIDE_SPEED;
        vy = 0;
        double y = 150, x = 0, dx = 0, dyy = 0;
        for (int i = 0; i < 600; i++) {
            vy = PlaneMath.airVy(v, vy, false, false, false, y);
            v += PlaneMath.airAccel(p, v, 0, vy, false, false);
            if (i >= 100) {
                dx += v;
                dyy -= vy;
            }
            y += vy;
        }
        check(dyy > 0 && dx / dyy > 7.5 && dx / dyy < 8.5, "plane: unpowered glide 1:" + Math.round(dx / dyy * 10) / 10.0);
        // landings and brakes
        check(PlaneMath.landingDamage(p.maxHp, 0.25, 0.6, true) == 0 && PlaneMath.landingDamage(p.maxHp, 0.1, 0.6, false) >= p.maxHp * 0.05
                && PlaneMath.landingDamage(p.maxHp, 5, 2, false) <= p.maxHp * 0.6, "plane: a wheels landing is free, a belly landing wears the hull (capped)");
        check(PlaneMath.brake(true) < PlaneMath.brake(false) && PlaneMath.throttleStep(0.5, true, false, false) < 0.5
                && PlaneMath.throttleStep(0.98, true, false, true) == 1, "plane: softer brakes on a runway; no power - the throttle winds down");
    }

    private static void flightMoves() {
        VehicleType h = VehicleType.HELI, p = VehicleType.PLANE;
        check(com.sva.vehicle.FlightMath.moveAllowed(h, 0.5, 0.25, 1, 120, true, true, true, 0.5), "flight move: a powered heli climbs 0.25 b/t");
        check(!com.sva.vehicle.FlightMath.moveAllowed(h, 0.2, 0.2, 1, 120, false, true, true, 0.2), "flight move: no power - no climbing");
        check(!com.sva.vehicle.FlightMath.moveAllowed(h, 0.2, 0, 1, 120, false, true, true, 0.2)
                && com.sva.vehicle.FlightMath.moveAllowed(h, 0.2, -0.1, 1, 120, false, true, true, 0.2), "flight move: unpowered in the air it must come down");
        check(com.sva.vehicle.FlightMath.moveAllowed(h, 0.1, 0, 1, 120, true, true, true, 0.1), "flight move: a powered heli may hover");
        check(!com.sva.vehicle.FlightMath.moveAllowed(p, 0.15, 0, 1, 120, true, true, true, 0.15)
                && com.sva.vehicle.FlightMath.moveAllowed(p, 0.9, 0, 1, 120, true, true, true, 0.9), "flight move: a plane can't hang in the air slowly");
        check(!com.sva.vehicle.FlightMath.moveAllowed(p, 0.3, 0.15, 1, 70, true, true, true, 0.3)
                && com.sva.vehicle.FlightMath.moveAllowed(p, 0.8, 0.15, 1, 70, true, true, true, 0.8), "flight move: a plane climbs only with airspeed");
        check(!com.sva.vehicle.FlightMath.moveAllowed(h, 0.2, 0.2, 1, 200.8, true, true, true, 0.2), "flight move: no climbing over the ceiling");
        check(com.sva.vehicle.FlightMath.moveAllowed(p, 0.3, 1.0, 1, 65, false, false, false, 0.3)
                && !com.sva.vehicle.FlightMath.moveAllowed(p, 0.3, 1.0, 1, 65, false, true, true, 0.3), "flight move: a block step from the ground, not in the air");
        check(!com.sva.vehicle.FlightMath.moveAllowed(p, 3.0, 0, 1, 120, true, true, true, 1.0) && !com.sva.vehicle.FlightMath.moveAllowed(h, 12, 0, 10, 120, true, true, true, 0.7)
                && com.sva.vehicle.FlightMath.moveAllowed(p, 1.3, 0, 1, 120, true, true, true, 1.1), "flight move: too fast / teleports refused, a dive's speed accepted");
    }

    private static void aircraftContent() {
        WorkbenchRecipeSVA h = WorkbenchRecipesSVA.byId("heli"), p = WorkbenchRecipesSVA.byId("plane");
        check(h != null && h.eu == 1500000L && h.tier == Tier.HV && h.width == 5 && h.height == 5 && ItemVehicleSVA.typeOf(h.result()) == VehicleType.HELI
                && WorkbenchMath.canBuild(Tier.HV, WorkbenchMath.inputTier(0, false)), "heli: workbench 5x5, 1.5M EU, HV (no transformer needed)");
        check(p != null && p.eu == 1500000L && p.tier == Tier.HV && p.width == 5 && p.height == 5 && ItemVehicleSVA.typeOf(p.result()) == VehicleType.PLANE,
                "plane: workbench 5x5, 1.5M EU, HV");
        WorkbenchRecipeSVA r = WorkbenchRecipesSVA.byId("runway"), rl = WorkbenchRecipesSVA.byId("runwayLit");
        check(r != null && r.result().stackSize == 8 && r.result().getItem() == net.minecraft.item.Item.getItemFromBlock(ModContentSVA.runway)
                && rl != null && rl.result().getItemDamage() == com.sva.block.BlockRunwaySVA.LIT, "runway: x8 plain, lit edge from runway + glowstone dust");
        check(com.sva.block.BlockRunwaySVA.variant(3) == com.sva.block.BlockRunwaySVA.LIT && com.sva.block.BlockRunwaySVA.variant(2) == 0
                && ModContentSVA.runway.damageDropped(3) == 1, "runway: the axis bit is not kept in the item");
        VehicleState st = new VehicleState(VehicleType.PLANE);
        st.fillAllGases();
        NBTTagCompound t = new NBTTagCompound();
        st.writeNBT(t);
        VehicleState back = new VehicleState(VehicleType.PLANE);
        back.readNBT(t);
        check(back.type == VehicleType.PLANE && back.gas(com.sc.util.ArmorGasSC.Gas.HELIUM) == 1000, "plane: NBT keeps the type and helium");
    }

    // ------------------------------------------------------------------ stage C: cargo crawler + trailer, drill vehicle

    private static void crawlerTypes() {
        VehicleType c = VehicleType.TRUCK, d = VehicleType.DRILL;
        com.sc.util.ArmorGasSC.Gas HE = com.sc.util.ArmorGasSC.Gas.HELIUM, AR = com.sc.util.ArmorGasSC.Gas.ARGON;
        check(c.ordinal() == 4 && d.ordinal() == 5 && c.movement == com.sva.vehicle.Movement.CRAWLER && d.movement == com.sva.vehicle.Movement.CRAWLER,
                "crawler: truck / drill appended (4, 5), tracked movement");
        check(c.tier == Tier.HV && c.buffer == 2000000 && c.cruiseCost == 60 && c.idleCost == 2 && near(c.topSpeed, 0.28 * 1.4, 1e-9)
                && c.seats == 2 && c.cargoSlots == 54 && c.trailerSlots == 54 && c.platformSize == PlatformSize.P3x5,
                "crawler: HV, 2M EU, 60 / 2 EU/t, x1.4 sprint, 2 seats, 54 + trailer 54, platform 3x5 (plan §2)");
        check(d.tier == Tier.EV && d.buffer == 8000000 && d.cruiseCost == 120 && near(d.topSpeed, 0.28 * 0.6, 1e-9) && d.seats == 1
                && d.cargoSlots == 108 && d.trailerSlots == 0 && d.hasDrill && d.platformSize == PlatformSize.P3x5,
                "drill: EV, 8M EU, 120 EU/t, x0.6 sprint, 1 seat, 108 cargo, platform 3x5 (plan §2)");
        check(c.gases.capacity(HE) == 2000 && !c.gases.any() == false && d.gases.capacity(HE) == 2000 && d.gases.capacity(AR) == 2000
                && d.gases.fieldGas == AR && near(d.gases.fieldPerSecond, 1, 1e-9) && near(d.gases.fieldReduction, 0.4, 1e-9) && c.gases.fieldGas == null,
                "crawler: He 2000; drill: He 2000 + Ar 2000, argon field 1 mB/s, -40 %");
        check(c.stepHeight == 2.0F && d.stepHeight == 2.0F && VehicleType.BUGGY.stepHeight == 1.0F, "crawler: steps 2 blocks (the buggy 1)");
        // a seated player fits: the crawler's eyes under its cab roof; the drill operator's eyes under a 3x3 tunnel's ceiling
        double eyes = d.seatY + 1.12;
        check(eyes + 0.1 < 3.0 && d.height < 3.0 && d.width < 3.0, "drill: operator's eyes " + Math.round(eyes * 100) / 100.0
                + " < 3 (no suffocation in its own bore), hull box fits a 3x3 tunnel");
        check(near(c.seatY + 0.25, 0.84 * c.modelScale, 0.01) && Math.abs(c.seatOffsets[0][1] - c.seatOffsets[1][1]) >= 0.75
                && 1.70 * c.modelScale - (c.seatY + 0.25) >= 1.05, "crawler: cushion 1.05 up, seats shoulder-wide, 1.07 to the headliner");
    }

    private static void crawlerMoves() {
        double rate = VehicleType.TRUCK.turnRate;
        check(CrawlerMath.turn(rate, 1, 0, true, true) == rate && CrawlerMath.turn(rate, -1, 0, true, true) == -rate,
                "tracks: turn on the spot (no speed needed)");
        check(CrawlerMath.turn(rate, 1, -0.2, true, true) == -rate, "tracks: reversing turns the other way, like wheels");
        check(CrawlerMath.turn(rate, 1, 0.3, false, true) == 0 && near(CrawlerMath.turn(rate, 1, 0, true, false), rate * 0.3, 1e-9),
                "tracks: no turn wrecked / overheated, x0.3 without EU");
        check(CrawlerMath.turn(rate, 0, 0.3, true, true) == 0, "tracks: no steer - no turn");
        check(EntityVehicleSVA.moveAllowed(0.3, 2.0, 1, 0.4, CrawlerMath.climbLimit(2F)) && !EntityVehicleSVA.moveAllowed(0.3, 2.0, 1, 0.4)
                && !EntityVehicleSVA.moveAllowed(0.3, 2.6, 1, 0.4, CrawlerMath.climbLimit(2F)),
                "tracks: the server accepts a 2-block step (wheels: 1), not 2.6");
        check(CrawlerMath.floatVy(0, true) > 0 && CrawlerMath.floatVy(1, true) <= CrawlerMath.MAX_RISE && CrawlerMath.floatVy(0, false) < 0,
                "tracks: floats up in water (capped), falls out of it");
        check(near(CrawlerMath.WATER_SPEED, 0.5, 1e-9), "tracks: swims at half speed");
    }

    private static void cargoTrailer() {
        VehicleState s = new VehicleState(VehicleType.TRUCK);
        check(s.cargoSize() == 54 && s.cargo.length == 54, "cargo: crawler 54 slots");
        s.cargo[3] = new ItemStack(Items.diamond, 5);
        s.setTrailer(true);
        check(s.cargoSize() == 108 && s.cargo.length == 108 && s.cargo[3] != null && s.cargo[3].stackSize == 5,
                "cargo: hitched 108 slots, the body's cargo kept");
        s.cargo[60] = new ItemStack(Items.iron_ingot, 9);
        NBTTagCompound t = new NBTTagCompound();
        s.writeNBT(t);
        VehicleState back = new VehicleState(VehicleType.TRUCK);
        back.readNBT(t);
        check(back.trailer && back.cargo.length == 108 && back.cargo[60] != null && back.cargo[60].stackSize == 9,
                "cargo: NBT keeps the trailer and its slots (item <-> entity)");
        s.setTrailer(false);
        check(s.cargo.length == 54 && s.cargo[3] != null, "cargo: unhitched back to 54");
        VehicleState b = new VehicleState(VehicleType.BUGGY);
        b.setTrailer(true);
        check(!b.trailer && b.cargo.length == 9, "cargo: no hitch on the buggy");
        check(CrawlerMath.pages(54) == 1 && CrawlerMath.pages(108) == 2 && CrawlerMath.pages(9) == 1 && CrawlerMath.slotsOn(108, 1) == 54
                && CrawlerMath.slotsOn(60, 1) == 6 && CrawlerMath.slotsOn(54, 1) == 0, "cargo: 54-slot pages (1 / 2), a short last page");
        check(new VehicleState(VehicleType.DRILL).cargoSize() == 108, "cargo: drill 108");
        // trailer rules
        check(CrawlerMath.attachCheck(54, false, true) == CrawlerMath.OK && CrawlerMath.attachCheck(54, true, true) == CrawlerMath.ALREADY
                && CrawlerMath.attachCheck(54, false, false) == CrawlerMath.NOT_OWNER && CrawlerMath.attachCheck(0, false, true) == CrawlerMath.NO_HITCH,
                "trailer: hitch only to the owner's crawler without one");
        check(CrawlerMath.detachCheck(true, true) == CrawlerMath.OK && CrawlerMath.detachCheck(false, true) == CrawlerMath.NONE
                && CrawlerMath.detachCheck(true, false) == CrawlerMath.NOT_OWNER, "trailer: unhitch by the owner only");
        ItemStack tr = new ItemStack(ModContentSVA.trailer);
        ItemStack[] c = new ItemStack[54];
        c[0] = new ItemStack(Items.coal, 12);
        c[53] = new ItemStack(Items.redstone, 3);
        com.sva.item.ItemTrailerSVA.withCargo(tr, c);
        ItemStack[] r = com.sva.item.ItemTrailerSVA.cargoOf(tr);
        check(r.length == 54 && r[0].stackSize == 12 && r[53].getItem() == Items.redstone && com.sva.item.ItemTrailerSVA.used(tr) == 2,
                "trailer: the item keeps its cargo (NBT)");
        check(!com.sva.item.ItemTrailerSVA.withCargo(new ItemStack(ModContentSVA.trailer), new ItemStack[54]).hasTagCompound(),
                "trailer: an empty trailer is a plain item");
    }

    private static void autoDelivery() {
        int[] a = {0, 64, 0, 0}, b = {20, 64, 0, 0};
        check(AutoDelivery.start(a, b, 0) == AutoDelivery.TO_A && AutoDelivery.start(null, b, 0) == AutoDelivery.STOPPED
                && AutoDelivery.startReason(a, null, 0) == AutoDelivery.R_ROUTE && AutoDelivery.startReason(a, b, -1) == AutoDelivery.R_DIMENSION,
                "auto: starts to A with both points here; no route / another dimension - stopped");
        check(AutoDelivery.onArrive(AutoDelivery.TO_A) == AutoDelivery.LOADING && AutoDelivery.onArrive(AutoDelivery.TO_B) == AutoDelivery.UNLOADING,
                "auto: arrive - load at A, unload at B");
        check(AutoDelivery.afterTransfer(AutoDelivery.LOADING, true, false, false) == AutoDelivery.LOADING
                && AutoDelivery.afterTransfer(AutoDelivery.LOADING, true, false, true) == AutoDelivery.TO_B
                && AutoDelivery.afterTransfer(AutoDelivery.LOADING, false, false, false) == AutoDelivery.TO_B
                && AutoDelivery.afterTransfer(AutoDelivery.LOADING, false, true, false) == AutoDelivery.LOADING,
                "auto: loading - full or the chest empty: off to B; nothing at all: waits at A");
        check(AutoDelivery.afterTransfer(AutoDelivery.UNLOADING, true, true, false) == AutoDelivery.TO_A
                && AutoDelivery.afterTransfer(AutoDelivery.UNLOADING, true, false, false) == AutoDelivery.UNLOADING
                && AutoDelivery.afterTransfer(AutoDelivery.UNLOADING, false, false, false) == AutoDelivery.TO_A,
                "auto: unloading - empty or the chest full: back to A");
        check(AutoDelivery.target(AutoDelivery.TO_A) == 0 && AutoDelivery.target(AutoDelivery.UNLOADING) == 1
                && AutoDelivery.target(AutoDelivery.STOPPED) == -1 && AutoDelivery.target(AutoDelivery.OFF) == -1, "auto: targets");
        int bt = 0;
        for (int i = 0; i < AutoDelivery.BLOCKED_TICKS; i++) {
            bt = AutoDelivery.blockedTicks(bt, true, 0.001);
        }
        check(AutoDelivery.stuck(bt) && AutoDelivery.BLOCKED_TICKS == 200 && AutoDelivery.blockedTicks(150, true, 0.2) == 0
                && AutoDelivery.blockedTicks(150, false, 0) == 0, "auto: stuck after 10 s of no progress; moving / turning resets it");
        // steering (yaw 0 = +z, 90 = -x; right = yaw up)
        check(AutoDelivery.steer(0, 0, 0, 0, 20) == EntityVehicleSVA.IN_FWD, "auto: straight ahead - forward");
        check(AutoDelivery.steer(0, 0, 0, -20, 0) == EntityVehicleSVA.IN_RIGHT, "auto: target 90 deg right - turns on the spot");
        check(AutoDelivery.steer(0, 0, 0, 3, 20) == (EntityVehicleSVA.IN_FWD | EntityVehicleSVA.IN_LEFT), "auto: a bit left - forward + left");
        check(AutoDelivery.steer(0, 0, 0, 1, 1) == EntityVehicleSVA.IN_BRAKE, "auto: arrived - brake");
        check(near(AutoDelivery.bearing(0, 0, 10, 0), -90, 1e-9) && near(AutoDelivery.wrap(350), -10, 1e-9)
                && AutoDelivery.distance(0, 0, 3, 4) == 5, "auto: bearing / wrap / distance");
    }

    private static boolean has(java.util.List<int[]> l, int x, int y, int z) {
        for (int[] b : l) {
            if (b[0] == x && b[1] == y && b[2] == z) {
                return true;
            }
        }
        return false;
    }

    private static void drillGeometry() {
        // a drill on the grid at (0.5, 64, 0.5) facing +x (yaw -90)
        java.util.List<int[]> f = DrillMath.zone(0.5, 64, 0.5, -90, DrillMath.MODE_TUNNEL, false);
        boolean slice3 = true;
        for (int x = 1; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 64; y <= 66; y++) {
                    slice3 &= has(f, x, y, z);
                }
            }
        }
        check(f.size() == 27 && slice3 && !has(f, 1, 67, 0) && !has(f, 1, 64, 2) && !has(f, 0, 64, 0) && !has(f, 4, 64, 0),
                "drill: 3x3 face, 3 slices ahead to the cone's tip (27 blocks), nothing behind / outside");
        check(f.get(0)[0] == 1 && f.get(0)[1] == 66, "drill: nearest slice first, from the top");
        java.util.List<int[]> w = DrillMath.zone(0.5, 64, 0.5, -90, DrillMath.MODE_TUNNEL, true);
        check(w.size() == 75 && has(w, 1, 68, 2) && has(w, 1, 64, -2) && !has(w, 1, 69, 0), "drill: 5x5 with the widener (75)");
        java.util.List<int[]> lv = DrillMath.zone(0.5, 64, 0.5, -90, DrillMath.MODE_LEVEL, false);
        check(lv.size() == 3 * 3 * DrillMath.LEVEL_ROWS && has(lv, 2, 71, 0) && !has(lv, 2, 63, 0) && !has(lv, 2, 72, 0),
                "drill: level mode cuts 8 rows above the floor, never below");
        java.util.List<int[]> ore = DrillMath.zone(0.5, 64, 0.5, -90, DrillMath.MODE_ORE, false);
        check(has(ore, 2, 63, 2) && has(ore, 2, 67, -2) && !has(ore, 2, 68, 0), "drill: ore mode looks one block round the bore");
        java.util.List<int[]> n = DrillMath.zone(0.5, 64, 0.5, 180, DrillMath.MODE_TUNNEL, false);
        check(has(n, 0, 65, -1) && has(n, -1, 65, -3) && !has(n, 0, 65, 1), "drill: facing -z (yaw 180) digs north");
        java.util.List<int[]> off = DrillMath.zone(0.9, 64, 0.5, -90, DrillMath.MODE_TUNNEL, false);
        check(!off.isEmpty() && has(off, 4, 64, 0), "drill: off the grid the window still covers the hull's front");
        java.util.List<int[]> ring = DrillMath.ring(0.5, 64, 0.5, -90, false);
        check(has(ring, 4, 64, 0) && has(ring, 2, 63, 2) && has(ring, 2, 67, 0), "drill: the liquid ring - deeper, wider, floor and ceiling");
    }

    private static void drillRules() {
        int T = DrillMath.MODE_TUNNEL, O = DrillMath.MODE_ORE;
        check(DrillMath.judge(false, false, 1.5F, 30F, false, true, T, false) == DrillMath.DIG, "drill: stone is dug");
        check(DrillMath.judge(false, false, -1F, 100F, false, true, T, false) == DrillMath.SKIP_HARD, "drill: bedrock / unbreakable never");
        check(DrillMath.judge(false, false, 50F, 30F, false, true, T, false) == DrillMath.SKIP_HARD
                && DrillMath.judge(false, false, 50F, 100F, false, true, T, false) == DrillMath.DIG, "drill: obsidian needs a tungsten+ head");
        check(DrillMath.judge(false, false, 2.5F, 100F, true, true, T, false) == DrillMath.SKIP_TILE, "drill: tile entities (chests) never");
        check(DrillMath.judge(false, false, 1.5F, 30F, false, false, T, false) == DrillMath.SKIP_CLAIM, "drill: a claim / private field - skipped");
        check(DrillMath.judge(false, false, 1.5F, 30F, false, true, O, false) == DrillMath.SKIP_FILTER
                && DrillMath.judge(false, false, 3F, 30F, false, true, O, true) == DrillMath.DIG, "drill: ore mode - rock stays, ore goes");
        check(DrillMath.judge(true, false, 0, 30F, false, true, T, false) == DrillMath.SKIP_AIR
                && DrillMath.judge(false, true, 0, 30F, false, true, T, false) == DrillMath.LIQUID, "drill: air skipped, liquids walled up");
        check(DrillMath.allowed(true, false, false, false) && !DrillMath.allowed(false, false, false, false) && !DrillMath.allowed(true, true, false, false)
                && !DrillMath.allowed(true, false, true, false) && !DrillMath.allowed(true, false, false, true),
                "drill: allowed only loaded, no private field, BreakEvent not cancelled, no spawn protection");
        check(com.sc.tileentity.TileEntityQuarrySC.isOre(Blocks.iron_ore, 0) && com.sc.tileentity.TileEntityQuarrySC.isOre(Blocks.coal_ore, 0)
                && !com.sc.tileentity.TileEntityQuarrySC.isOre(Blocks.stone, 0) && !com.sc.tileentity.TileEntityQuarrySC.isOre(Blocks.cobblestone, 0),
                "drill: ore filter = ore dictionary 'ore*' (iron / coal ore yes, stone no)");
        check(DrillMath.euPerBlock(1.5F, false, 1) == 53 && DrillMath.euPerBlock(1.5F, true, 1) == 79 && DrillMath.euPerBlock(3F, false, 2) == 150
                && DrillMath.euPerBlock(-1F, false, 1) == 30, "drill: EU a block = quarry's 30 + 15 x hardness (stone 53, Exo x1.5, config x)");
        check(DrillMath.wear(10, 2000) == 11 && DrillMath.wear(10, 0) == 10 && DrillMath.wornOut(2000, 2000) && !DrillMath.wornOut(5, 0)
                && DrillMath.lifeLeft(150, 2000) == 1850 && DrillMath.lifeLeft(0, 0) == -1, "drill: head wears 1 a block, Exo never");
        check(near(DrillMath.perTick(1), 0.4, 1e-9) && near(DrillMath.perTick(8), 3.2, 1e-9), "drill: speed = head blocks/s x 8 (steel 0.4 b/t, Exo 3.2)");
        check(near(DrillMath.fieldDamage(100F, true, 0.4), 60, 1e-4) && DrillMath.fieldDamage(100F, false, 0.4) == 100F,
                "drill: argon field takes 40 % off hull damage");
        check(DrillMath.torchDue(8, 3) && !DrillMath.torchDue(8, 12) && !DrillMath.torchDue(5, 0), "drill: a torch every 8 blocks in the dark");
        check(DrillMath.nextMode(0) == 1 && DrillMath.nextMode(2) == 0, "drill: modes cycle Tunnel / Ore / Level");
        check(EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_DRILL_TOGGLE, false, true) && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_AUTO_TOGGLE, false, true)
                && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_TRAILER_DETACH, false, false) && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_ROUTE_CLEAR, true, false),
                "actions: the driver may run the drill; auto-delivery / trailer / route - owner only");
        VehicleState s = new VehicleState(VehicleType.DRILL);
        s.drillHead = new ItemStack(com.sc.init.ModItems.DRILL_HEADS.get(0));
        s.drillHead.setItemDamage(37);
        s.drillModule = ModContentSVA.component.stackOf(ComponentType.DRILL_WIDENER, 1);
        s.drillMode = DrillMath.MODE_LEVEL;
        s.dug = 1234;
        s.routeA = new int[] {1, 2, 3, 0};
        NBTTagCompound t = new NBTTagCompound();
        s.writeNBT(t);
        VehicleState b = new VehicleState(VehicleType.DRILL);
        b.readNBT(t);
        check(b.drillHead != null && b.drillHead.getItemDamage() == 37 && b.drillModule != null && b.drillMode == DrillMath.MODE_LEVEL && b.dug == 1234
                && b.routeA != null && b.routeA[2] == 3 && b.routeB == null && !b.autoOn, "drill: NBT keeps head (wear), module, mode, count; route kept");
    }

    private static void stageCContent() {
        WorkbenchRecipeSVA c = WorkbenchRecipesSVA.byId("truck"), d = WorkbenchRecipesSVA.byId("drill"), tr = WorkbenchRecipesSVA.byId("trailer");
        check(c != null && c.eu == 1000000L && c.tier == Tier.HV && ItemVehicleSVA.typeOf(c.result()) == VehicleType.TRUCK,
                "crawler: workbench recipe, 1M EU, HV");
        check(d != null && d.eu == 4000000L && d.tier == Tier.EV && ItemVehicleSVA.typeOf(d.result()) == VehicleType.DRILL
                && WorkbenchMath.canBuild(Tier.EV, WorkbenchMath.inputTier(0, false)), "drill: workbench recipe, 4M EU, EV (the bare MV workbench)");
        check(tr != null && tr.result().getItem() == ModContentSVA.trailer && tr.category == WorkbenchRecipeSVA.Category.VEHICLES,
                "trailer: workbench recipe («Машины»)");
        check(WorkbenchRecipesSVA.byId("drillWidener") != null && ComponentType.DRILL_WIDENER.meta() == 23, "widener: component 23, a recipe");
        check(com.sva.entity.DrillLogicSVA.isHead(new ItemStack(com.sc.init.ModItems.DRILL_HEADS.get(0)))
                && !com.sva.entity.DrillLogicSVA.isHead(new ItemStack(Items.diamond_pickaxe))
                && com.sva.entity.DrillLogicSVA.isWidener(ModContentSVA.component.stackOf(ComponentType.DRILL_WIDENER, 1)),
                "drill: the head slot takes core drill heads, the module slot the widener");
    }

    // ------------------------------------------------------------------ stage D: jet, tiltrotor

    private static void jetType() {
        VehicleType j = VehicleType.JET;
        com.sc.util.ArmorGasSC.Gas H2 = com.sc.util.ArmorGasSC.Gas.HYDROGEN, HE = com.sc.util.ArmorGasSC.Gas.HELIUM;
        check(j.ordinal() == 6 && j.movement == com.sva.vehicle.Movement.PLANE && j.tier == Tier.QV && j.buffer == 16000000
                && j.cruiseCost == 90 && j.peakCost == 250 && near(j.topSpeed, 0.28 * 6, 1e-9) && j.seats == 1 && j.cargoSlots == 9
                && j.platformSize == PlatformSize.P7x7, "jet: meta 6, QV, 16M EU, 90 / 250 EU/t, x6 sprint, 1 seat, 9 cargo, platform 7x7");
        check(j.gases.capacity(H2) == 4000 && j.gases.capacity(HE) == 2000 && j.gases.boostGas == H2 && near(j.gases.boostGasPerSecond, 4, 1e-9)
                && near(j.gases.boostMul, 1.5, 1e-9) && j.gases.liftGas == null && j.gases.coolant == HE,
                "jet: H2 4000 for the afterburner (4 mB/s, x1.5), He 2000 cooling, no lift gas");
        // a seated player fits the cockpit: cushion top 0.64, canopy 1.036 (model units)
        double cushion = j.seatY + 0.25;
        check(near(j.seatY + 0.203, 0.64 * j.modelScale, 0.16) && 1.036 * j.modelScale - cushion >= 0.95 && 6.0 * j.modelScale < 18,
                "jet: model x" + j.modelScale + " - cushion " + cushion + ", canopy " + Math.round((1.036 * j.modelScale - cushion) * 100) / 100.0
                        + " over it, " + 6.0 * j.modelScale + " blocks long");
    }

    private static void jetFlight() {
        VehicleType j = VehicleType.JET, p = VehicleType.PLANE;
        com.sva.vehicle.PlaneParams jp = j.plane;
        check(jp.stall > p.plane.stall && jp.liftOff > jp.stall && PlaneMath.climbCapacity(jp, jp.stall) == 0
                && near(PlaneMath.climbCapacity(jp, jp.stall + jp.climbBand), jp.climb, 1e-9), "jet: stall " + VehicleMath.kmh(jp.stall)
                + " km/h (plane " + VehicleMath.kmh(p.plane.stall) + "), lift grows over it");
        double ground = PlaneMath.takeoffDistance(j, false), runway = PlaneMath.takeoffDistance(j, true);
        check(ground >= 44 && ground <= 56 && runway / ground > 0.4 && runway / ground < 0.6,
                "jet: take-off run " + Math.round(ground) + " blocks, on a runway " + Math.round(runway));
        check(near(PlaneMath.cost(j, 1, false, 0, 64, 0, true), 250, 1e-9) && near(PlaneMath.cost(j, 1, true, 0, 80, 0, true), 90, 1e-9)
                && near(PlaneMath.cost(j, 1, true, 0, 120, 0, true), 72, 1e-9), "jet: take-off run 250 EU/t, cruise 90, at Y >= 100 72");
        // level flight at full throttle settles at the top speed; the afterburner pushes it to the cap
        double v = 1.0, va = 1.0;
        for (int i = 0; i < 3000; i++) {
            v = Math.min(PlaneMath.maxSpeed(j, 1), v + PlaneMath.airAccel(j, v, 1, 0, false, true, 1));
            va = Math.min(PlaneMath.maxSpeed(j, 1.5), va + PlaneMath.airAccel(j, va, 1, 0, false, true, 1.5));
        }
        check(near(v, j.topSpeed, 0.02) && va > v * 1.4 && va <= com.sva.vehicle.FlightMath.SPEED_CAP + 1e-9,
                "jet: level top speed " + VehicleMath.kmh(v) + " km/h, afterburner " + VehicleMath.kmh(va) + " km/h (cap "
                        + VehicleMath.kmh(com.sva.vehicle.FlightMath.SPEED_CAP) + ")");
        check(near(PlaneMath.maxSpeed(j, 1.5), 2.5, 1e-9) && near(PlaneMath.maxSpeed(p, 1), p.topSpeed * PlaneMath.MAX_SPEED_MUL, 1e-9),
                "jet: never over 2.5 b/t (the plane's dive limit unchanged)");
        // full-throttle climb: settles over the stall
        v = jp.stall;
        double vy = 0;
        for (int i = 0; i < 600; i++) {
            vy = PlaneMath.airVy(jp, v, vy, true, false, true, 120);
            v += PlaneMath.airAccel(j, v, 1, vy, true, true);
        }
        check(v > jp.stall && vy > 0.15, "jet: full-throttle climb at " + VehicleMath.kmh(v) + " km/h, " + Math.round(vy * 200) / 10.0 + " m/s");
        // the afterburner's hydrogen: 4 mB/s
        VehicleState st = new VehicleState(j);
        st.fillAllGases();
        for (int i = 0; i < 20 * 10; i++) {
            st.drainGasFraction(j.gases.boostGas, j.gases.boostGasPerSecond / 20.0);
        }
        check(st.gas(j.gases.boostGas) == 4000 - 40, "jet: the afterburner burns 4 mB of H2 a second (40 in 10 s)");
        // heat: climbing on the afterburner overheats without helium, holds with it; cruising on it settles
        double climbLoad = com.sva.vehicle.FlightMath.load(j, 250) * com.sva.vehicle.VehicleGases.AFTERBURNER_HEAT;
        double cruiseLoad = com.sva.vehicle.FlightMath.load(j, 90) * com.sva.vehicle.VehicleGases.AFTERBURNER_HEAT;
        double h1 = 0, h2 = 0, h3 = 0, he = 2000;
        boolean st1 = false, st2 = false;
        for (int i = 0; i < 12000; i++) {
            h1 = VehicleMath.heatStep(h1, climbLoad, 0);
            st1 |= VehicleMath.heatStopped(st1, h1);
            double c = VehicleMath.heliumCooling(h2, he);
            he -= VehicleMath.heliumUse(c);
            h2 = VehicleMath.heatStep(h2, climbLoad, c);
            st2 |= VehicleMath.heatStopped(st2, h2);
            h3 = VehicleMath.heatStep(h3, cruiseLoad, 0);
        }
        check(climbLoad > com.sva.vehicle.FlightMath.load(j, 250) && st1 && !st2 && h3 < 100,
                "jet: afterburner x2 heat - a climb overheats without helium, holds with it (He left " + Math.round(he)
                        + " of 2000 after 10 min); cruising on it settles at " + Math.round(h3) + "%");
    }

    private static void planeUnchanged() {
        VehicleType p = VehicleType.PLANE;
        com.sva.vehicle.PlaneParams l = p.plane;
        check(l == com.sva.vehicle.PlaneParams.LIGHT && l.stall == PlaneMath.STALL && l.liftOff == PlaneMath.LIFT_OFF && l.thrust == PlaneMath.THRUST
                && l.climb == PlaneMath.CLIMB && l.dive == PlaneMath.DIVE && l.glideSpeed == PlaneMath.GLIDE_SPEED && p.gases.boostGas == null
                && p.gases.boostMul == 1, "plane: unchanged - the light plane's parameters are PlaneMath's constants, no afterburner");
        check(near(PlaneMath.takeoffDistance(p, false), 30.94, 0.05) && near(PlaneMath.takeoffDistance(p, true), 15.62, 0.05)
                && near(com.sva.vehicle.FlightMath.maxReach(p), p.topSpeed * com.sva.vehicle.FlightMath.SPEED_MARGIN, 1e-9),
                "plane: take-off 31 / 16 blocks and the server's reach as in stage B");
    }

    private static void tiltType() {
        VehicleType t = VehicleType.TILT;
        com.sc.util.ArmorGasSC.Gas H2 = com.sc.util.ArmorGasSC.Gas.HYDROGEN, HE = com.sc.util.ArmorGasSC.Gas.HELIUM;
        check(t.ordinal() == 7 && t.movement == com.sva.vehicle.Movement.TILT && t.movement.flies && t.tier == Tier.XV && t.buffer == 32000000
                && near(t.topSpeed, 0.28 * 5, 1e-9) && t.cruiseCost == 110 && t.seats == 6 && t.seatOffsets.length == 6 && t.cargoSlots == 27
                && t.platformSize == PlatformSize.P5x5, "tilt: meta 7, XV, 32M EU, x5 sprint, 110 EU/t, 2 pilots + 4 seats, 27 cargo, platform 5x5");
        check(t.gases.capacity(H2) == 3000 && t.gases.capacity(HE) == 2000 && t.gases.boostGas == H2 && t.gases.coolant == HE,
                "tilt: H2 3000 (afterburner in airplane mode) + He 2000");
        double cushion = t.seatY + 0.25;
        check(near(cushion, 0.53 * t.modelScale, 0.02) && 1.03 * t.modelScale - cushion >= 1.05 && t.seatDy(0) < 0 && t.seatDy(2) == 0
                && Math.abs(t.seatOffsets[0][1] - t.seatOffsets[1][1]) >= 0.5, "tilt: model x" + t.modelScale + " - cushion " + cushion
                + ", ceiling " + Math.round((1.03 * t.modelScale - cushion) * 100) / 100.0 + " over it, pilots side by side");
    }

    private static void tiltRules() {
        VehicleType t = VehicleType.TILT;
        double stall = t.plane.stall;
        check(TiltMath.switchCheck(0, true, false, 0, 0, stall) == TiltMath.ON_GROUND, "tilt: no airplane mode on the ground");
        check(TiltMath.switchCheck(0, true, true, 0.1, 3, stall) == TiltMath.TOO_SLOW_LOW, "tilt: airplane mode refused low and slow");
        check(TiltMath.switchCheck(0, true, true, 0.1, 6, stall) == TiltMath.OK && TiltMath.switchCheck(0, true, true, 0.5, 2, stall) == TiltMath.OK,
                "tilt: airplane mode allowed 6 blocks up or at 32 km/h");
        check(TiltMath.switchCheck(1, false, true, stall * 1.5, 20, stall) == TiltMath.TOO_FAST
                && TiltMath.switchCheck(1, false, true, stall * 1.1, 20, stall) == TiltMath.OK
                && TiltMath.switchCheck(1, false, false, 0, 0, stall) == TiltMath.OK, "tilt: back to vertical only at <= stall x1.2 (also landed)");
        check(TiltMath.switchCheck(0.5, false, true, 0, 20, stall) == TiltMath.BUSY && TiltMath.switchCheck(1, true, true, 1, 20, stall) == TiltMath.SAME,
                "tilt: no switching mid-transition");
        double pos = 0;
        int n = 0;
        while (pos < 1 && n < 100) {
            pos = TiltMath.step(pos, TiltMath.PLANE);
            n++;
        }
        check(n == TiltMath.TRANSITION_TICKS && n == 40 && TiltMath.nacelleAngle(0) == 0 && near(TiltMath.nacelleAngle(1), 90, 1e-4)
                && near(TiltMath.nacelleAngle(0.5), 45, 1e-4), "tilt: the nacelles turn 0 -> 90 deg in " + n + " ticks (2 s)");
        // costs per mode
        double hover = HeliMath.vtolCost(t, true, 0, 0, 0, true), fwd = HeliMath.vtolCost(t, true, t.vtolTop, 0, 0, true);
        check(near(hover, 150, 1e-9) && near(fwd, 120, 1e-9) && near(HeliMath.vtolCost(t, false, 0, 0, 0, true), 3, 1e-9),
                "tilt: vertical mode - hovering 150 EU/t, flying 120, on the ground 3");
        check(near(PlaneMath.cost(t, 1, true, 0, 80, 0, true), 110, 1e-9) && near(PlaneMath.cost(t, 1, true, t.plane.climb, 80, 0, true), 160, 1e-9)
                && near(com.sva.vehicle.FlightMath.cruiseCost(t, 0, 80, true), 110, 1e-9)
                && near(com.sva.vehicle.FlightMath.cruiseCost(t, 0, 80, false), 120, 1e-9), "tilt: airplane mode - cruise 110 EU/t, climbing 160");
        // vertical take-off in vertical mode: a straight climb with no airspeed - heli rules accept it, plane rules don't
        double vy = 0, y = 64;
        for (int i = 0; i < 100; i++) {
            vy = HeliMath.poweredVy(vy, true, false, y);
            y += vy;
        }
        check(y > 64 + 20 && com.sva.vehicle.FlightMath.moveAllowed(t, com.sva.vehicle.Movement.HELI, 0, HeliMath.CLIMB, 1, 70, true, true, true, 0)
                && !com.sva.vehicle.FlightMath.moveAllowed(t, com.sva.vehicle.Movement.PLANE, 0, HeliMath.CLIMB, 1, 70, true, true, true, 0),
                "tilt: vertical take-off (+" + Math.round(y - 64) + " blocks in 5 s) - fine in vertical mode, refused under airplane rules");
        check(t.plane.stall * TiltMath.CONVERT_MUL > t.plane.stall && t.plane.stall * TiltMath.CONVERT_MUL <= TiltMath.CONVERT_ACC * TiltMath.TRANSITION_TICKS,
                "tilt: the transition reaches the conversion speed (" + VehicleMath.kmh(t.plane.stall * TiltMath.CONVERT_MUL) + " km/h) from a hover");
        VehicleState st = new VehicleState(t);
        st.tiltPlane = true;
        st.tiltStep = 25;
        NBTTagCompound nbt = new NBTTagCompound();
        st.writeNBT(nbt);
        VehicleState back = new VehicleState(t);
        back.readNBT(nbt);
        VehicleState jet = new VehicleState(VehicleType.JET);
        NBTTagCompound noType = (NBTTagCompound) nbt.copy();
        noType.removeTag(VehicleState.K_TYPE);
        jet.readNBT(noType);
        check(back.tiltPlane && back.tiltStep == 25 && near(back.tiltPos(), 0.625, 1e-9) && !jet.tiltPlane && jet.tiltStep == 0,
                "tilt: NBT keeps the mode and the nacelles (other types ignore them)");
    }

    private static void fastMoves() {
        VehicleType j = VehicleType.JET, p = VehicleType.PLANE;
        check(com.sva.vehicle.FlightMath.moveAllowed(j, 2.4, 0, 1, 120, true, true, true, 2.4)
                && !com.sva.vehicle.FlightMath.moveAllowed(p, 2.4, 0, 1, 120, true, true, true, 2.4), "jet move: 2.4 b/t accepted (the plane: refused)");
        check(!com.sva.vehicle.FlightMath.moveAllowed(j, 3.6, 0, 1, 120, true, true, true, 2.5), "jet move: over the 2.5 b/t cap (+30 %) refused");
        check(com.sva.vehicle.FlightMath.moveAllowed(j, 12, 0, 5, 120, true, true, true, 2.4) && !com.sva.vehicle.FlightMath.moveAllowed(j, 40, 0, 10, 120, true, true, true, 2.4),
                "jet move: a 5-tick packet of 12 blocks accepted, a 40-block jump refused");
        check(!com.sva.vehicle.FlightMath.moveAllowed(j, 0.5, 0, 1, 120, true, true, true, 0.5)
                && !com.sva.vehicle.FlightMath.moveAllowed(j, 1.2, 0.3, 1, 70, true, true, true, 0.6),
                "jet move: no hanging slowly in the air, no climbing without airspeed");
    }

    private static void stageDContent() {
        WorkbenchRecipeSVA j = WorkbenchRecipesSVA.byId("jet"), t = WorkbenchRecipesSVA.byId("tilt");
        check(j != null && j.eu == 12000000L && j.tier == Tier.QV && j.width == 5 && j.height == 5 && ItemVehicleSVA.typeOf(j.result()) == VehicleType.JET
                && !WorkbenchMath.canBuild(Tier.QV, WorkbenchMath.inputTier(0, false)) && WorkbenchMath.canBuild(Tier.QV, WorkbenchMath.inputTier(1, false)),
                "jet: workbench 5x5, 12M EU, QV - one transformer");
        check(t != null && t.eu == 30000000L && t.tier == Tier.XV && ItemVehicleSVA.typeOf(t.result()) == VehicleType.TILT
                && !WorkbenchMath.canBuild(Tier.XV, WorkbenchMath.inputTier(1, false)) && WorkbenchMath.canBuild(Tier.XV, WorkbenchMath.inputTier(2, false)),
                "tilt: workbench 5x5, 30M EU, XV - two transformers");
        check(EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_FLIGHT_MODE, false, true) && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_FLIGHT_MODE, true, false)
                && EntityVehicleSVA.IN_AFTER == 128 && (EntityVehicleSVA.IN_MASK & EntityVehicleSVA.IN_AFTER) != 0,
                "tilt: only the pilot switches the mode; the «Форсаж» input bit fits the Input packet");
    }

    // ------------------------------------------------------------------ stage E: transport shuttle, Singular ship

    private static void spaceTypes() {
        com.sc.util.ArmorGasSC.Gas H2 = com.sc.util.ArmorGasSC.Gas.HYDROGEN, HE = com.sc.util.ArmorGasSC.Gas.HELIUM,
                O2 = com.sc.util.ArmorGasSC.Gas.OXYGEN, D = com.sc.util.ArmorGasSC.Gas.DEUTERIUM, AR = com.sc.util.ArmorGasSC.Gas.ARGON,
                SM = com.sc.util.ArmorGasSC.Gas.SINGULAR_MATTER;
        VehicleType s = VehicleType.SHUTTLE, sh = VehicleType.SHIP;
        check(s.ordinal() == 8 && s.movement == com.sva.vehicle.Movement.HELI && s.tier == Tier.XV && s.buffer == 32000000
                && near(s.topSpeed, 0.28 * 4, 1e-9) && s.cruiseCost == 200 && s.peakCost == 200 && s.idleCost == 8 && s.seats == 8
                && s.seatOffsets.length == 8 && s.cargoSlots == 27 && s.platformSize == PlatformSize.P7x7 && s.sealed && s.longRange
                && s.bridgeDock && !s.singular, "shuttle: meta 8, XV, 32M EU, x4 sprint, 200 / 8 EU/t, 2 pilots + 6 seats, 27 cargo, 7x7, sealed, docks");
        check(s.gases.capacity(H2) == 3000 && s.gases.capacity(HE) == 3000 && s.gases.capacity(O2) == 2000 && s.gases.capacity(D) == 4000
                && s.gases.boostGas == H2 && s.gases.coolant == HE && s.gases.liftGas == null, "shuttle: H2 3000 (boost) + He 3000 + O2 2000 + D 4000");
        check(sh.ordinal() == 9 && sh.movement == com.sva.vehicle.Movement.HELI && sh.tier == Tier.SV && sh.buffer == 256000000
                && near(sh.topSpeed, 0.28 * 6, 1e-9) && sh.cruiseCost == 400 && sh.idleCost == 20 && sh.seats == 6 && sh.seatOffsets.length == 6
                && sh.cargoSlots == 54 && sh.platformSize == PlatformSize.P7x7 && sh.singular && sh.sealed && sh.bridgeDock,
                "ship: meta 9, SV, 256M EU, x6 sprint, 400 / 20 EU/t, 6 seats, 54 cargo, 7x7, jump / anchor / invisibility");
        check(sh.gases.capacity(SM) == 4000 && sh.gases.capacity(H2) == 4000 && sh.gases.capacity(HE) == 4000 && sh.gases.capacity(O2) == 4000
                && sh.gases.capacity(AR) == 2000 && sh.gases.capacity(D) == 4000 && sh.gases.fieldGas == AR,
                "ship: SM 4000 + H2 / He / O2 / D 4000 + Ar 2000 (hull field)");
        check(sh.topSpeed * sh.gases.boostMul <= com.sva.vehicle.FlightMath.SPEED_CAP && sh.topSpeed * sh.gases.boostMul > 2.2
                && com.sva.vehicle.FlightMath.maxReach(sh) <= com.sva.vehicle.FlightMath.SPEED_CAP,
                "ship: x6 sprint and the boost (" + VehicleMath.kmh(sh.topSpeed * sh.gases.boostMul) + " km/h) under the 180 km/h cap");
        // a seated player fits: shuttle cushion top 0.85 under the cabin ceiling 1.71; ship cushion 0.57 under 1.10 (model units)
        double cs = s.seatY + 0.25, cp = sh.seatY + 0.25;
        check(near(cs, 0.85 * s.modelScale, 0.02) && 1.71 * s.modelScale - cs >= 1.05 && Math.abs(s.seatOffsets[0][1] - s.seatOffsets[1][1]) >= 0.5
                && Math.abs(s.seatOffsets[2][0] - s.seatOffsets[4][0]) >= 0.75 && Math.abs(s.seatOffsets[2][1] - s.seatOffsets[3][1]) >= 0.75,
                "shuttle: model x" + s.modelScale + " - cushion " + cs + ", ceiling " + Math.round((1.71 * s.modelScale - cs) * 100) / 100.0
                        + " over it, rows 0.78 apart, seats shoulder-wide");
        check(near(cp, 0.57 * sh.modelScale, 0.02) && Math.abs(sh.seatOffsets[0][1] - sh.seatOffsets[1][1]) >= 0.75
                && Math.abs(sh.seatOffsets[2][0] - sh.seatOffsets[4][0]) >= 0.75, "ship: model x" + sh.modelScale + " - cushion " + cp
                + ", ceiling " + Math.round((1.10 * sh.modelScale - cp) * 100) / 100.0 + " over it, " + Math.round(7.26 * sh.modelScale * 10) / 10.0 + " blocks long");
        // heavy VTOL: its own climb rate - accepted for the ship, refused for the light helicopter
        check(com.sva.vehicle.FlightMath.moveAllowed(sh, 0.5, 0.44, 1, 120, true, true, true, 0.5)
                && !com.sva.vehicle.FlightMath.moveAllowed(VehicleType.HELI, 0.5, 0.44, 1, 120, true, true, true, 0.5)
                && near(HeliMath.targetVy(sh, true, false, 100), 0.45, 1e-9) && near(HeliMath.targetVy(s, false, true, 100), -0.4, 1e-9)
                && near(HeliMath.targetVy(VehicleType.HELI, true, false, 100), HeliMath.CLIMB, 1e-9),
                "shuttle / ship climb 0.35 / 0.45 b/t (the helicopter keeps 0.25)");
        check(near(HeliMath.cost(s, true, 0, 0, 0, true), 200, 1e-9) && near(HeliMath.cost(s, true, s.topSpeed, 0, 0, true), 200, 1e-9)
                && near(HeliMath.cost(s, false, 0, 0, 0, true), 8, 1e-9) && near(HeliMath.cost(sh, true, 1, 0, 0, true), 400, 1e-9),
                "shuttle 200 EU/t hovering or flying, 8 on the ground; ship 400");
    }

    private static void spaceRules() {
        double top = VehicleType.SHUTTLE.topSpeed;
        check(com.sva.vehicle.SpaceMath.longRangeActive(true, 130, top * 0.6, top, 100)
                && !com.sva.vehicle.SpaceMath.longRangeActive(false, 130, top, top, 100)
                && !com.sva.vehicle.SpaceMath.longRangeActive(true, 119.9, top, top, 100)
                && !com.sva.vehicle.SpaceMath.longRangeActive(true, 130, top * 0.4, top, 100)
                && !com.sva.vehicle.SpaceMath.longRangeActive(true, 130, top, top, 0),
                "long-range mode: on + Y >= 120 + >= half the top speed + deuterium (each missing - off)");
        check(near(com.sva.vehicle.SpaceMath.o2Seconds(2000, 2), 1000, 1e-9) && Double.isInfinite(com.sva.vehicle.SpaceMath.o2Seconds(2000, 0))
                && com.sva.vehicle.SpaceMath.o2Seconds(0, 3) == 0 && near(com.sva.vehicle.SpaceMath.O2_PER_SECOND, 1, 1e-9),
                "cabin O2: 1 mB/s a breathing rider - 2000 mB last two riders 1000 s");
        check(com.sva.vehicle.SpaceMath.ANCHOR_EU < VehicleType.SHIP.peakCost / 5 && com.sva.vehicle.SpaceMath.ANCHOR_EU > 0,
                "anchor: " + (int) com.sva.vehicle.SpaceMath.ANCHOR_EU + " EU/t (hovering: 400)");
        check(com.sva.vehicle.SpaceMath.dockMass(new int[] {10, 20}) == 70 && com.sc.bridge.BridgeMathSC.massEu(70, false) == 7000000L
                && com.sc.bridge.BridgeMathSC.massEu(70, true) == 3500000L, "docking: hull 4 + player 1 + mob 2 units = 7M EU (3.5M with the Mass Compensator)");
        check(com.sva.vehicle.SpaceMath.ringExitSide(0, 2) == -1 && com.sva.vehicle.SpaceMath.ringExitSide(0, 3) == 1
                && com.sva.vehicle.SpaceMath.ringExitSide(1, 4) == -1 && com.sva.vehicle.SpaceMath.ringExitSide(1, 5) == 1
                && com.sva.vehicle.SpaceMath.exitYaw(0, 1) == 0F && com.sva.vehicle.SpaceMath.exitYaw(0, -1) == 180F
                && com.sva.vehicle.SpaceMath.exitYaw(1, 1) == -90F && com.sva.vehicle.SpaceMath.exitYaw(1, -1) == 90F,
                "docking: the ring's exit side (as the core) and the yaw facing out of the vortex");
        check(EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_JUMP, false, true) && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_JUMP, true, false)
                && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_ANCHOR, true, false) && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_INVIS, false, true)
                && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_LONG_RANGE, false, true) && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_JUMP_TARGET, true, false),
                "ship keys: only the pilot jumps, anchors, cloaks, switches long-range mode / the target");
    }

    private static void jumpMath() {
        long d = com.sva.vehicle.SpaceMath.distance(0, 0, 200, 0);
        check(d == 200 && com.sva.vehicle.SpaceMath.distance(0, 0, 3, 4) == 5 && com.sva.vehicle.SpaceMath.jumpEu(200, false) == 20400000L
                && com.sva.vehicle.SpaceMath.jumpSm(200, false) == 12 && com.sva.vehicle.SpaceMath.jumpEu(10000, false) == 40000000L
                && com.sva.vehicle.SpaceMath.jumpSm(10000, false) == 110 && com.sva.vehicle.SpaceMath.jumpEu(5, true) == 100000000L
                && com.sva.vehicle.SpaceMath.jumpSm(5, true) == 100, "jump cost: 200 blocks 20.4M EU + 12 mB SM, 10 000 blocks 40M + 110, another dimension 100M + 100");
        check(com.sva.vehicle.SpaceMath.jumpEu(200, false) < com.sc.bridge.BridgeMathSC.GROUND_BURST / 5
                && com.sva.vehicle.SpaceMath.jumpEu(0, true) < com.sc.bridge.BridgeMathSC.SPACE_BURST / 10,
                "jump cost: well under the core bridges' (Ground 200M+, Space 2G)");
        long eu = 256000000L;
        check(com.sva.vehicle.SpaceMath.jumpCheck(false, true, false, 200, 0, eu, 4000) == com.sva.vehicle.SpaceMath.NO_TARGET
                && com.sva.vehicle.SpaceMath.jumpCheck(true, false, true, 0, 0, eu, 4000) == com.sva.vehicle.SpaceMath.NO_DIM
                && com.sva.vehicle.SpaceMath.jumpCheck(true, true, false, 15, 0, eu, 4000) == com.sva.vehicle.SpaceMath.TOO_NEAR
                && com.sva.vehicle.SpaceMath.jumpCheck(true, true, true, 0, 0, eu, 4000) == com.sva.vehicle.SpaceMath.OK
                && com.sva.vehicle.SpaceMath.jumpCheck(true, true, false, 200, 1, eu, 4000) == com.sva.vehicle.SpaceMath.COOLDOWN
                && com.sva.vehicle.SpaceMath.jumpCheck(true, true, false, 200, 0, 20399999, 4000) == com.sva.vehicle.SpaceMath.NO_EU
                && com.sva.vehicle.SpaceMath.jumpCheck(true, true, false, 200, 0, 20400000, 11) == com.sva.vehicle.SpaceMath.NO_SM
                && com.sva.vehicle.SpaceMath.jumpCheck(true, true, false, 200, 0, 20400000, 12) == com.sva.vehicle.SpaceMath.OK,
                "jump check: no target / no dimension / < 16 blocks / cooldown / EU / SM refused, exact amounts accepted");
        int cd = com.sva.vehicle.SpaceMath.JUMP_COOLDOWN, n = 0;
        while (cd > 0 && n < 10000) {
            cd = com.sva.vehicle.SpaceMath.cooldownStep(cd);
            n++;
        }
        check(n == 600 && com.sva.vehicle.SpaceMath.cooldownStep(0) == 0 && com.sva.vehicle.SpaceMath.PORTAL_TICKS == 30,
                "jump cooldown 30 s (" + n + " ticks), the portal opens in 1.5 s");
        final java.util.Set<Long> solid = new java.util.HashSet<Long>();
        com.sva.vehicle.SpaceMath.Solid q = new com.sva.vehicle.SpaceMath.Solid() {
            @Override
            public boolean blocked(int x, int y, int z) {
                return solid.contains(Long.valueOf(((long) x * 1000 + y) * 1000 + z));
            }
        };
        boolean empty = com.sva.vehicle.SpaceMath.freeSpace(q, 100, 70, 100);
        solid.add(Long.valueOf(((long) 105 * 1000 + 75) * 1000 + 95));            // the far top corner of the box
        boolean corner = com.sva.vehicle.SpaceMath.freeSpace(q, 100, 70, 100);
        solid.clear();
        solid.add(Long.valueOf(((long) 106 * 1000 + 70) * 1000 + 100));           // just outside x + 5
        solid.add(Long.valueOf(((long) 100 * 1000 + 69) * 1000 + 100));           // the ground under it
        solid.add(Long.valueOf(((long) 100 * 1000 + 76) * 1000 + 100));           // just over the top
        boolean outside = com.sva.vehicle.SpaceMath.freeSpace(q, 100, 70, 100);
        check(empty && !corner && outside, "free-space check: an 11 x 6 x 11 box - a block in its corner refuses, ground / blocks just outside don't");
    }

    private static void spaceState() {
        VehicleState st = new VehicleState(VehicleType.SHIP);
        st.longRange = true;
        st.jumpHome = true;
        st.jumpCooldown = 123;
        st.anchor = true;
        st.invisible = true;
        NBTTagCompound nbt = new NBTTagCompound();
        st.writeNBT(nbt);
        VehicleState back = new VehicleState(VehicleType.SHIP);
        back.readNBT(nbt);
        NBTTagCompound noType = (NBTTagCompound) nbt.copy();
        noType.removeTag(VehicleState.K_TYPE);
        VehicleState heli = new VehicleState(VehicleType.HELI);
        heli.readNBT(noType);
        VehicleState shuttle = new VehicleState(VehicleType.SHUTTLE);
        shuttle.readNBT(noType);
        check(back.longRange && back.jumpHome && back.jumpCooldown == 123 && back.anchor && back.invisible && !heli.longRange && !heli.anchor
                && shuttle.longRange && !shuttle.anchor && !shuttle.invisible && !shuttle.jumpHome,
                "NBT keeps long-range mode, the jump target / cooldown, anchor, invisibility (other types ignore what they lack)");
    }

    private static void stageEContent() {
        WorkbenchRecipeSVA s = WorkbenchRecipesSVA.byId("shuttle"), sh = WorkbenchRecipesSVA.byId("ship");
        check(s != null && s.eu == 60000000L && s.tier == Tier.XV && s.width == 5 && s.height == 5 && ItemVehicleSVA.typeOf(s.result()) == VehicleType.SHUTTLE
                && !WorkbenchMath.canBuild(Tier.XV, WorkbenchMath.inputTier(1, false)) && WorkbenchMath.canBuild(Tier.XV, WorkbenchMath.inputTier(2, false)),
                "shuttle: workbench 5x5, 60M EU, XV - two transformers");
        check(sh != null && sh.eu == 500000000L && sh.tier == Tier.SV && ItemVehicleSVA.typeOf(sh.result()) == VehicleType.SHIP
                && !WorkbenchMath.canBuild(Tier.SV, WorkbenchMath.inputTier(2, false)) && WorkbenchMath.canBuild(Tier.SV, WorkbenchMath.inputTier(3, false)),
                "ship: workbench 5x5, 500M EU, SV - three transformers");
        boolean fusion = false, link = false, clots = false;
        if (s != null && sh != null) {
            for (Object o : s.layout()) {
                ItemStack in = o == null ? null : WorkbenchRecipeSVA.displayOf(o);
                fusion |= in != null && in.getItem() == ModItems.component("fusionCore");
            }
            int n = 0;
            for (Object o : sh.layout()) {
                ItemStack in = o == null ? null : WorkbenchRecipeSVA.displayOf(o);
                link |= in != null && in.getItem() == ModItems.bridgeLinkModule;
                n += in != null && in.getItem() == ModItems.singularClot ? 1 : 0;
            }
            clots = n == 4;
        }
        check(fusion && link && clots, "shuttle takes the core's fusion reactor core; the ship the bridge link module and 4 singular clots");
    }

    // ------------------------------------------------------------------ the charging platform (plan §9)

    /** A flat field of plates for the flood fill. */
    private static PlatformMath.Plates field(final java.util.Set<Long> cells) {
        return new PlatformMath.Plates() {
            @Override
            public boolean isPlate(int x, int z) {
                return cells.contains(Long.valueOf(PlatformMath.key(x, z)));
            }
        };
    }

    private static java.util.Set<Long> rectCells(int x0, int z0, int x1, int z1) {
        java.util.Set<Long> s = new java.util.HashSet<Long>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                s.add(Long.valueOf(PlatformMath.key(x, z)));
            }
        }
        return s;
    }

    private static void platform() {
        // flood fill from a station at (0, 0)
        java.util.Set<Long> sq = rectCells(1, -1, 3, 1);                 // 3x3 east of the station
        java.util.Set<Long> got = PlatformMath.floodFill(field(sq), 0, 0, 64);
        int[] b = PlatformMath.bounds(got);
        check(got.size() == 9 && b[2] - b[0] + 1 == 3 && b[3] - b[1] + 1 == 3, "platform: 3x3 next to the station = 9 plates, 3x3");
        java.util.Set<Long> withJunk = new java.util.HashSet<Long>(sq);
        withJunk.add(Long.valueOf(PlatformMath.key(6, 0)));               // a gap away
        withJunk.add(Long.valueOf(PlatformMath.key(4, 2)));               // only diagonal to (3, 1)
        withJunk.add(Long.valueOf(PlatformMath.key(-5, -5)));
        check(PlatformMath.floodFill(field(withJunk), 0, 0, 64).size() == 9, "platform: disconnected and diagonal plates ignored");
        java.util.Set<Long> l = rectCells(1, 0, 5, 0);
        l.addAll(rectCells(5, 1, 5, 4));                                   // an L: 5 east, then 4 south
        got = PlatformMath.floodFill(field(l), 0, 0, 64);
        b = PlatformMath.bounds(got);
        check(got.size() == 9 && b[2] - b[0] + 1 == 5 && b[3] - b[1] + 1 == 5, "platform: an L shape - 9 plates, 5x5 bounds");
        check(!got.contains(Long.valueOf(PlatformMath.key(0, 0))), "platform: the station itself is not a plate");
        java.util.Set<Long> big = rectCells(-6, -6, 6, 6);
        big.remove(Long.valueOf(PlatformMath.key(0, 0)));
        check(PlatformMath.floodFill(field(big), 0, 0, 64).size() == 64, "platform: at most 64 plates (168 laid)");
        check(PlatformMath.floodFill(field(rectCells(2, 0, 4, 2)), 0, 0, 64).isEmpty() && PlatformMath.bounds(new java.util.HashSet<Long>()) == null,
                "platform: plates not touching the station - none");
        check(PlatformMath.keyX(PlatformMath.key(-30000000, 29999999)) == -30000000 && PlatformMath.keyZ(PlatformMath.key(-30000000, 29999999)) == 29999999,
                "platform: position key round trip at the world border");
        // fits by size class
        check(VehicleType.BUGGY.platformSize == PlatformSize.P3x3 && VehicleType.HOVER.platformSize == PlatformSize.P3x3
                && PlatformSize.P3x5.minPlates() == 15 && PlatformSize.P7x7.minPlates() == 49, "platform: buggy / hoverbike 3x3; 3x5 = 15, 7x7 = 49 plates");
        check(PlatformMath.fits(PlatformSize.P3x3, 9, 3, 3) && !PlatformMath.fits(PlatformSize.P3x3, 8, 3, 3) && !PlatformMath.fits(PlatformSize.P3x3, 9, 9, 1),
                "platform: 3x3 fits 9 plates in 3x3, not 8, not a 9x1 line");
        check(PlatformMath.fits(PlatformSize.P3x5, 15, 3, 5) && PlatformMath.fits(PlatformSize.P3x5, 15, 5, 3) && !PlatformMath.fits(PlatformSize.P3x5, 15, 4, 4),
                "platform: 3x5 fits either way round, not 4x4");
        check(!PlatformMath.fits(PlatformSize.P5x5, 27, 3, 9) && PlatformMath.fits(PlatformSize.P7x7, 49, 7, 7) && !PlatformMath.fits(PlatformSize.P7x7, 48, 7, 7),
                "platform: 5x5 not on 3x9; 7x7 needs all 49");
        // rate: tier packet x upgrades x plate bonus
        check(PlatformMath.plateBonus(9, PlatformSize.P3x3) == 0 && near(PlatformMath.plateBonus(12, PlatformSize.P3x3), 0.3, 1e-9)
                && PlatformMath.plateBonus(64, PlatformSize.P3x3) == 2.0 && PlatformMath.plateBonus(5, PlatformSize.P5x5) == 0,
                "platform: +10% a plate over the minimum, capped at +200%");
        check(PlatformMath.rate(Tier.MV, 0, 0, 1) == 128 && PlatformMath.rate(Tier.MV, 0, 0.3, 1) == 166 && PlatformMath.rate(Tier.MV, 0, 0.8, 1) == 230,
                "platform: MV 128 EU/t, +30% = 166, +80% = 230");
        check(PlatformMath.rate(Tier.MV, 1, 0, 1) == 205 && PlatformMath.rate(Tier.MV, 2, 2.0, 1) == 983 && PlatformMath.rate(Tier.MV, 0, 5, 1) == 384,
                "platform: overclocker x1.6 each; bonus never over +200%");
        check(PlatformMath.inputTier(Tier.MV, 0) == Tier.MV && PlatformMath.inputTier(Tier.MV, 1) == Tier.HV && PlatformMath.rate(PlatformMath.inputTier(Tier.MV, 1), 0, 0, 1) == 512,
                "platform: a transformer lifts the input to HV (512 EU/t)");
        check(PlatformMath.buffer(Tier.MV, 0) == 12800 && PlatformMath.buffer(Tier.MV, 2) == 212800, "platform: buffer 12 800 + 100k per energy storage");
        check(PlatformMath.rate(Tier.MV, 0, Double.NaN, 1) >= 1 && PlatformMath.rate(Tier.MV, 0, 0, 0.1) == 13, "platform: rate NaN-safe, config multiplier applied");
        // round robin
        int[] seen = new int[3];
        int last = -1;
        for (int t = 0; t < 6; t++) {
            last = PlatformMath.nextIndex(last, 3);
            seen[last]++;
        }
        check(seen[0] == 2 && seen[1] == 2 && seen[2] == 2 && PlatformMath.nextIndex(5, 0) == -1 && PlatformMath.nextIndex(-1, 4) == 0,
                "platform: round robin serves 3 vehicles twice each in 6 ticks");
        // repair: +1% a step, an ingot per 10 steps
        check(PlatformMath.repairStep(100, 200) == 102 && PlatformMath.repairStep(199.5F, 200) == 200, "platform: repair +1% a step, capped");
        check(PlatformMath.ingotsFor(0) == 1 && PlatformMath.ingotsFor(3) == 0, "platform: an ingot only when no step is prepaid");
        check(PlatformMath.ingotsToFull(100, 200) == 5 && PlatformMath.ingotsToFull(199, 200) == 1 && PlatformMath.ingotsToFull(200, 200) == 0,
                "platform: 50% -> full = 50 steps = 5 ingots (as 5 by hand)");
        check(PlatformMath.REPAIR_EVERY == 60 && PlatformMath.STEPS_PER_INGOT == 10, "platform: one step every 3 s, 10 steps an ingot");
        // redstone / comparator / ETA
        check(PlatformMath.fullSignal(true, true) == 15 && PlatformMath.fullSignal(false, true) == 0 && PlatformMath.fullSignal(true, false) == 0,
                "platform: redstone 15 only when full and the option is on");
        check(PlatformMath.comparator(false, 0.5) == 0 && PlatformMath.comparator(true, 0) == 0 && PlatformMath.comparator(true, 0.001) == 1
                && PlatformMath.comparator(true, 0.5) == 8 && PlatformMath.comparator(true, 0.99) == 14 && PlatformMath.comparator(true, 1) == 15,
                "platform: comparator 0 empty / none, 1..14 by charge, 15 full");
        check(near(PlatformMath.etaSeconds(2000, 100), 1, 1e-9) && PlatformMath.etaSeconds(0, 5) == 0
                && Double.isInfinite(PlatformMath.etaSeconds(100, 0)), "platform: ETA from the measured rate");
        // content
        check(WorkbenchRecipesSVA.byId("chargePlate") != null && WorkbenchRecipesSVA.byId("chargePlate").result().stackSize == 8
                && WorkbenchRecipesSVA.byId("chargePlateH") != null && WorkbenchRecipesSVA.byId("chargePlateEdge") != null,
                "platform: plate recipes on the workbench (plain x8, H, edge light)");
        boolean mods = true;
        for (String id : new String[] {"moduleGas", "moduleRepair", "moduleAccess"}) {
            WorkbenchRecipeSVA r = WorkbenchRecipesSVA.byId(id);
            mods &= r != null && r.instant() && r.category == WorkbenchRecipeSVA.Category.MODULES && r.result().getItem() == ModContentSVA.module;
        }
        check(mods, "platform: three module recipes (instant, «Модули»)");
        check(com.sva.block.TileEntityChargerSVA.moduleAccepted(ModContentSVA.module.stackOf(com.sva.item.ItemStationModuleSVA.ACCESS))
                && !com.sva.block.TileEntityChargerSVA.moduleAccepted(new ItemStack(Items.iron_ingot))
                && com.sva.block.TileEntityChargerSVA.upgradeAccepted(ModItems.upgrade.stackOf(com.sc.machine.UpgradeType.OVERCLOCKER))
                && !com.sva.block.TileEntityChargerSVA.upgradeAccepted(ModItems.upgrade.stackOf(com.sc.machine.UpgradeType.EJECTOR)),
                "platform: station slots take modules / overclocker, transformer, storage only");
        com.sva.block.TileEntityChargerSVA te = new com.sva.block.TileEntityChargerSVA(Tier.MV);
        te.setInventorySlotContents(0, ModItems.upgrade.stackOf(com.sc.machine.UpgradeType.TRANSFORMER));
        te.setInventorySlotContents(4, ModContentSVA.module.stackOf(com.sva.item.ItemStationModuleSVA.GAS));
        te.setOwner("id", "Me");
        te.toggleSetting(com.sva.block.TileEntityChargerSVA.SET_SOUND);
        NBTTagCompound t = new NBTTagCompound();
        te.writeToNBT(t);
        com.sva.block.TileEntityChargerSVA back = new com.sva.block.TileEntityChargerSVA(Tier.MV);
        back.readFromNBT(t);
        check(back.inputTier() == Tier.HV && back.rate() == 512 && back.hasModule(com.sva.item.ItemStationModuleSVA.GAS)
                && !back.hasModule(com.sva.item.ItemStationModuleSVA.REPAIR) && "Me".equals(back.getOwnerName())
                && back.getSettings() == (com.sva.block.TileEntityChargerSVA.SET_DEFAULT & ~com.sva.block.TileEntityChargerSVA.SET_SOUND),
                "platform: station NBT keeps upgrades, modules, owner, settings");
        NBTTagCompound old = new NBTTagCompound();
        new com.sva.block.TileEntityChargerSVA(Tier.MV).writeToNBT(old);
        for (String k : new String[] {"ChargerItems", "ChargerFacing", "Owner", "OwnerName", "ChargerSettings", "RepairPaid", "ChargerFull"}) {
            old.removeTag(k);                                               // a station saved before the platform
        }
        com.sva.block.TileEntityChargerSVA legacy = new com.sva.block.TileEntityChargerSVA(Tier.MV);
        legacy.readFromNBT(old);
        check(legacy.getSettings() == com.sva.block.TileEntityChargerSVA.SET_DEFAULT && legacy.getFacing() == 3 && legacy.rate() == 128
                && legacy.getMaxEnergyStored() == 12800, "platform: an old station loads as before (MV 128 EU/t, 12 800 EU)");
    }

    /**
     * Fix pass after the user's drill test: every lang value only uses glyphs the 1.7.10 font shows right (no x-sign,
     * guillemets, no-break spaces, subscripts...: GlyphCheckSVA); numbers grouped by a plain space; the «Багажник» key
     * (A_CARGO) keeps the owner rule of Shift+RMB.
     */
    private static void glyphsAndCargoKey() throws java.io.IOException {
        String[] files = {"/assets/siliconagevehicles/lang/ru_RU.lang", "/assets/siliconagevehicles/lang/en_US.lang"};
        for (String f : files) {
            java.util.List<String> bad = GlyphCheckSVA.scanLang(f);
            check(bad != null && bad.isEmpty(), "glyphs: " + f + " - no characters the vanilla font draws wrong"
                    + (bad == null ? " (file missing)" : bad.isEmpty() ? "" : " " + bad.subList(0, Math.min(8, bad.size()))));
        }
        check(GlyphCheckSVA.forbidden('\u00d7') && GlyphCheckSVA.forbidden('\u00ab') && GlyphCheckSVA.forbidden('\u00a0')
                && GlyphCheckSVA.forbidden('\u202f') && GlyphCheckSVA.forbidden('\u2082') && !GlyphCheckSVA.forbidden('x')
                && !GlyphCheckSVA.forbidden('\u0416') && !GlyphCheckSVA.forbidden('\u2014') && !GlyphCheckSVA.forbidden('\u00a7'),
                "glyphs: x-sign / guillemets / no-break spaces / subscripts forbidden; ASCII, Cyrillic, dash, colour codes allowed");
        String g = com.sva.FormatSVA.group(11195), m = com.sva.FormatSVA.group(1234567);
        check("11 195".equals(g) && "1 234 567".equals(m) && "950".equals(com.sva.FormatSVA.group(950)),
                "numbers: thousands split by a plain space (" + g + ", " + m + ") - not the locale's U+00A0");
        check(EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_CARGO, true, false) && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_CARGO, true, true)
                && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_CARGO, false, true) && EntityVehicleSVA.A_CARGO == 11,
                "cargo key: action 11 opens the cargo screen for the owner / op only (as Shift+RMB), driver or not");
    }

    /**
     * Every seat of every closed vehicle: the seated player's chest and head stay inside the hull (shell + glass of the
     * OBJ at modelScale, seatY + the seat's dy) - nothing pokes out through the roof or the walls (RiderFitSVA).
     */
    private static void riderFit() throws java.io.IOException {
        for (VehicleType t : VehicleType.values()) {
            if (t.openCab) {
                continue;
            }
            float[][] hull = RiderFitSVA.hull(t.model);
            if (hull == null) {
                check(false, "rider fit: " + t.key + " - models/" + t.model + ".obj not found");
                continue;
            }
            StringBuilder bad = new StringBuilder();
            for (int i = 0; i < t.seatOffsets.length; i++) {
                int[] r = RiderFitSVA.check(t, hull, i);
                if (r[0] > 0 || r[1] > 0) {
                    bad.append(" seat ").append(i).append(": chest ").append(r[0]).append(", head ").append(r[1]).append(" / ").append(r[2]).append(" points out;");
                }
            }
            check(bad.length() == 0, "rider fit: " + t.key + " (model x" + t.modelScale + ", seatY " + t.seatY + ") - every rider's chest and head inside the hull"
                    + bad);
        }
    }

    /**
     * Fix 2026-10 (the shuttle pilot's first-person view was one dark grey wall - the camera, 0.1 blocks behind the eye in
     * vanilla, sat in the headrest): every seat of every type, from vanilla's camera (debug/ViewCheckSVA; 70 deg view,
     * near plane 0.05, the own view's eye clip and window / interior cuts) in 7 looks (ahead, 30 up / down, 45 / 90 to
     * both sides) - no surface within 0.15 blocks of the camera covers more than 5 % of the screen; every glow sprite /
     * lamp and dial halo drawn in the own view covers at most 5 % looked at straight (visible parts) and never reaches
     * the near plane. Plus the clamps' math, and the detector itself: the shuttle pilot's old eye (seat x 2.48) without
     * the eye clip is a wall over >90 % of the view.
     */
    private static void ownView() throws java.io.IOException {
        check(com.sva.vehicle.CockpitView.EYE_CLIP == com.sva.vehicle.CockpitView.CAMERA_BACK
                && com.sva.vehicle.CockpitView.EYE_CLIP > com.sva.vehicle.CockpitView.NEAR
                && Math.abs(com.sva.vehicle.CockpitView.ownGlow(1.0, 2.0) - 0.24) < 1e-9 && com.sva.vehicle.CockpitView.ownGlow(0.35, 10) == 0.35
                && Math.abs(com.sva.vehicle.CockpitView.lampHalo(0.02, 0.4) - 0.048) < 1e-9 && Math.abs(com.sva.vehicle.CockpitView.lampHalo(0.02, 1) - 0.06) < 1e-9
                && Math.abs(com.sva.vehicle.CockpitView.lampHalo(0.02, 0.05) - 0.024) < 1e-9,
                "own view: eye clip at the eye (0.1 = vanilla's camera offset, past the near plane 0.05); glow / halo clamps");
        VehicleType sh = VehicleType.SHUTTLE;
        ViewCheckSVA.Mesh shm = ViewCheckSVA.load(sh.model);
        double[] old = {2.48 / sh.modelScale, (sh.seatY + sh.seatDy(0) + com.sva.vehicle.CockpitView.EYE_OVER_SEAT) / sh.modelScale, 0.26 / sh.modelScale};
        double oldWall = shm == null ? 0 : ViewCheckSVA.blockedAt(sh, shm, old, 0, 0, com.sva.vehicle.CockpitView.NEAR, ViewCheckSVA.CLOSE, null);
        double oldClip = shm == null ? 1 : ViewCheckSVA.blockedAt(sh, shm, old, 0, 0, com.sva.vehicle.CockpitView.EYE_CLIP, ViewCheckSVA.CLOSE, null);
        double nowWall = shm == null ? 1 : ViewCheckSVA.blocked(sh, shm, 0, 0, 0, false);
        check(oldWall > 0.9 && oldClip < 0.01 && nowWall < 0.01 && sh.seatOffsets[0][0] == 2.58 && sh.seatOffsets[1][0] == 2.58,
                "own view: the shuttle pilot's old eye (seat x 2.48) looks at the headrest from vanilla's camera (" + pct(oldWall)
                        + " of the view a wall; " + pct(oldClip) + " with the eye clip); at x 2.58 " + pct(nowWall) + " even without it");
        for (VehicleType t : VehicleType.values()) {
            ViewCheckSVA.Mesh m = ViewCheckSVA.load(t.model);
            if (m == null) {
                check(false, "own view: " + t.key + " - models/" + t.model + ".obj not found");
                continue;
            }
            StringBuilder bad = new StringBuilder();
            double worst = 0, worstO = 0;
            for (int i = 0; i < t.seatOffsets.length; i++) {
                for (double[] p : ViewCheckSVA.POSES) {
                    double b = ViewCheckSVA.blocked(t, m, i, p[0], p[1], true);
                    worst = Math.max(worst, b);
                    if (b > ViewCheckSVA.LIMIT) {
                        bad.append(" seat ").append(i).append(" look ").append((int) p[0]).append("/").append((int) p[1]).append(": a wall over ")
                                .append(pct(b)).append(";");
                    }
                }
                double[] cam = ViewCheckSVA.camera(t, i, ViewCheckSVA.dir(0, 0));
                for (ViewCheckSVA.Overlay o : ViewCheckSVA.overlays(t, cam, true)) {
                    double[] r = ViewCheckSVA.overlayShare(t, m, i, o, true);
                    worstO = Math.max(worstO, r[0]);
                    if (r[0] > ViewCheckSVA.LIMIT) {
                        bad.append(" seat ").append(i).append(" ").append(o.what).append(" ").append(pct(r[0])).append(" at ")
                                .append(String.format(java.util.Locale.ROOT, "%.2f", r[1])).append(" blocks;");
                    }
                }
            }
            check(bad.length() == 0, "own view: " + t.key + " - " + t.seatOffsets.length + " seats x 7 looks: walls in front of the face at most "
                    + pct(worst) + ", glows / halos at most " + pct(worstO) + " of the screen (limit 5 %)" + bad);
        }
    }

    /**
     * Fix 2026-10 (the user's first-person screenshots: the heli's glare shield over the pilot's upper dial row, the
     * Singular Ship's dials lying face-up under the console hoods and its upper screen tilted away, the buggy's wheel in
     * the scuttle panel with its hub over the cluster): debug/CockpitCheckSVA on every type - each dial and screen
     * CockpitLayout finds is read from its pilot's camera (vanilla's, looking 20 deg down; the co-pilot's own panel from
     * his seat, a passengers' panel from the nearest rider): at most 10 % of the samples over its face hidden by other
     * geometry, the face within 40 deg of the way to the eye, all of it on the screen looking 20..25 deg down; every
     * moving control (AnimParts CONTROL) swept over its inputs cuts no other triangle off its mount. Plus the detector's
     * maths: a crossing edge is a cut, touching / coplanar faces are not, the turn is right-handed.
     */
    private static void cockpitVisibility() throws java.io.IOException {
        float[] a = {0, 0, 0, 1, 0, 0, 0, 1, 0};                            // in the plane z = 0
        float[] b = {0.2F, 0.2F, -1, 0.2F, 0.2F, 1, 0.3F, 0.25F, 0};         // its first edge goes through a at (0.2, 0.2, 0)
        float[] c = {0, 0, 0, 1, 0, 0, 0, 0, 1};                            // shares a's first edge, at right angles
        float[] d = {0.1F, 0.1F, 0, 0.5F, 0.1F, 0, 0.1F, 0.5F, 0};          // lies on a
        double[] hit = new double[3];
        boolean pierce = CockpitCheckSVA.pierce(b, 0, a, hit) && near(hit[0], 0.2, 1e-6) && near(hit[2], 0, 1e-6);
        boolean touch = true;
        for (int e = 0; e < 3; e++) {
            touch &= !CockpitCheckSVA.pierce(a, e, c, hit) && !CockpitCheckSVA.pierce(c, e, a, hit) && !CockpitCheckSVA.pierce(a, e, d, hit)
                    && !CockpitCheckSVA.pierce(d, e, a, hit);
        }
        double[] r = CockpitCheckSVA.rot(new double[] {1, 0, 0}, new float[] {0, 0, 1}, 90);
        check(pierce && touch && near(r[0], 0, 1e-9) && near(r[1], 1, 1e-9),
                "cockpit view: a crossing edge is a cut, a shared edge / coplanar contact is not; turns are right-handed");
        int nInst = 0, nCtl = 0;
        for (VehicleType t : VehicleType.values()) {
            ViewCheckSVA.Mesh m = ViewCheckSVA.load(t.model);
            if (m == null) {
                check(false, "cockpit view: " + t.key + " - models/" + t.model + ".obj not found");
                continue;
            }
            StringBuilder bad = new StringBuilder();
            double hid = 0, ang = 0, down = 0, side = 0;
            java.util.List<CockpitCheckSVA.Inst> in = CockpitCheckSVA.instruments(t, m);
            for (CockpitCheckSVA.Inst i : in) {
                hid = Math.max(hid, i.blocked);
                ang = Math.max(ang, i.angle);
                down = Math.max(down, i.down);
                side = Math.max(side, i.side);
                if (!i.ok()) {
                    bad.append(' ').append(i).append(';');
                }
            }
            java.util.List<CockpitCheckSVA.Ctl> cl = CockpitCheckSVA.controls(t, m);
            int poses = 0;
            for (CockpitCheckSVA.Ctl k : cl) {
                poses += k.poses;
                if (!k.ok()) {
                    bad.append(' ').append(k).append(';');
                }
            }
            nInst += in.size();
            nCtl += cl.size();
            check(bad.length() == 0, String.format(java.util.Locale.ROOT,
                    "cockpit view: %s - %d dials / screens readable (hidden at most %.0f %%, facing within %.0f deg, %.0f deg down / %.0f aside"
                            + " at most), %d controls clear in %d poses", t.key, Integer.valueOf(in.size()), Double.valueOf(hid * 100),
                    Double.valueOf(ang), Double.valueOf(down), Double.valueOf(side), Integer.valueOf(cl.size()), Integer.valueOf(poses)) + bad);
        }
        check(nInst >= 50 && nCtl == 13, "cockpit view: " + nInst + " instruments and " + nCtl + " moving controls checked (50+ / 13: stage C"
                + " the plane's 5 panels, its flap lever and trim wheel)");
        // the seats the instruments are read from: the heli co-pilot's cluster from his seat, the ship's wall screens
        // from the front-row passengers
        int copilot = 0, passenger = 0;
        ViewCheckSVA.Mesh hm = ViewCheckSVA.load(VehicleType.HELI.model), sm = ViewCheckSVA.load(VehicleType.SHIP.model);
        if (hm != null && sm != null) {
            for (CockpitCheckSVA.Inst i : CockpitCheckSVA.instruments(VehicleType.HELI, hm)) {
                copilot += i.seat == 1 ? 1 : 0;
            }
            for (CockpitCheckSVA.Inst i : CockpitCheckSVA.instruments(VehicleType.SHIP, sm)) {
                passenger += i.seat >= 2 ? 1 : 0;
            }
        }
        check(copilot == 3 && passenger == 2, "cockpit view: the heli co-pilot reads his own 2 dials + screen (" + copilot
                + "), the ship's 2 wall screens face the front-row passengers (" + passenger + ")");
        check(VehicleType.DRILL.cabCuts != null && VehicleType.DRILL.ownViewCuts().length <= com.sva.vehicle.CockpitView.MAX_WINDOWS,
                "cockpit view: the drill cuts its body's top over the cab from the own view (2 windows + 1 cut, 3 clip planes)");
    }

    /**
     * Fix 2026-10 (the user's screenshot: the raw key sva.hud.jump.why.1 in the ship's HUD): every key the code asks for
     * is in both lang files - the literal ones read from the mod's class files (LangKeysSVA), the families built at run
     * time with their ranges: the jump check's codes (SpaceMath; the HUD's status line and the chat message), the cockpit
     * dials, units and lamps.
     */
    private static void langKeys() throws java.io.IOException {
        java.util.Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        if (ru == null || en == null) {
            check(false, "lang keys: ru_RU / en_US missing");
            return;
        }
        java.util.Set<String> lit = LangKeysSVA.literalKeys();
        if (lit == null) {
            check(false, "lang keys: the mod's classes could not be read");
        } else {
            java.util.List<String> mr = LangKeysSVA.missing(lit, ru), me = LangKeysSVA.missing(lit, en);
            check(lit.size() > 300 && mr.isEmpty() && me.isEmpty(), "lang keys: all " + lit.size() + " literal keys of the code in ru_RU and en_US"
                    + (mr.isEmpty() ? "" : " ru missing " + mr) + (me.isEmpty() ? "" : " en missing " + me));
        }
        check(LangKeysSVA.keyLike("sva.hud.jump.why.1") && !LangKeysSVA.keyLike("sva.hud.jump.why.") && !LangKeysSVA.keyLike("sva.a b")
                && !LangKeysSVA.keyLike("tile.x"), "lang keys: a key is sva. + letters / digits / dots, not a prefix ending with a dot");
        StringBuilder miss = new StringBuilder();
        for (int code = com.sva.vehicle.SpaceMath.OK; code <= com.sva.vehicle.SpaceMath.CHUNK; code++) {
            if (code == com.sva.vehicle.SpaceMath.OK || code == com.sva.vehicle.SpaceMath.COOLDOWN) {
                continue;                                                  // the HUD has own lines for these (ready / cooldown)
            }
            for (String k : new String[] {"sva.hud.jump.why." + code, "sva.msg.jump.no." + code}) {
                if (!ru.contains(k) || !en.contains(k)) {
                    miss.append(' ').append(k);
                }
            }
        }
        for (String k : com.sva.vehicle.CockpitDials.KEYS) {
            for (String f : new String[] {"sva.cockpit.dial." + k, "sva.cockpit.unit." + k}) {
                if (!ru.contains(f) || !en.contains(f)) {
                    miss.append(' ').append(f);
                }
            }
        }
        for (String k : com.sva.vehicle.CockpitLamps.KEYS) {
            if (k.length() > 0 && (!ru.contains("sva.cockpit.lamp." + k) || !en.contains("sva.cockpit.lamp." + k))) {
                miss.append(" sva.cockpit.lamp.").append(k);
            }
        }
        check(miss.length() == 0, "lang keys: the HUD's jump status for every jump check code (1..11 but the cooldown), its chat messages,"
                + " every dial label / unit and lamp label in ru_RU and en_US" + miss);
    }

    private static String pct(double f) {
        return String.format(java.util.Locale.ROOT, "%.1f %%", f * 100);
    }

    /**
     * Cockpit windows (VehicleType.cabWindows): the open seats (buggy, hoverbike) have none, every closed type 1..3 boxes;
     * the first (the windscreen) holds the driver's straight-ahead view ray within 2 blocks of the eye; every box lies
     * over the type's glass (glass / canopy, the shuttle's lit "window" strip): its finite sides within the glass bounds
     * +-0.1 (x: +-0.6 - an inset windscreen's box reaches through the shell in front of it). The clip math: a box's outer
     * half-spaces (open sides have none), the shell kept outside and cut inside the boxes.
     */
    private static void cockpitWindows() throws java.io.IOException {
        float[] box = {1F, 2F, 0F, 1F, -VehicleType.OPEN, VehicleType.OPEN};
        float[][] one = {box}, two = {box, {-1F, 0F, 0F, 1F, -0.5F, 0.5F}};
        check(com.sva.vehicle.CockpitView.outerPlanes(box).length == 4 && com.sva.vehicle.CockpitView.outerPlanes(two[1]).length == 6
                && !com.sva.vehicle.CockpitView.shellKept(one, 1.5, 0.5, 40) && com.sva.vehicle.CockpitView.shellKept(one, 2.5, 0.5, 0)
                && com.sva.vehicle.CockpitView.shellKept(one, 1.5, 1.2, 0) && !com.sva.vehicle.CockpitView.shellKept(two, -0.5, 0.5, 0.2)
                && com.sva.vehicle.CockpitView.shellKept(two, -0.5, 0.5, 0.7) && !com.sva.vehicle.CockpitView.shellKept(two, 1.2, 0.3, -9),
                "cockpit windows: clip half-spaces (an open side has none), the shell cut inside the boxes and kept outside");
        for (VehicleType t : VehicleType.values()) {
            if (t.openCab) {
                check(t.cabWindows == null, "cockpit windows: " + t.key + " - an open seat, no window cut (the hull opaque)");
                continue;
            }
            float[][] w = t.cabWindows;
            if (w == null || w.length == 0 || w.length > com.sva.vehicle.CockpitView.MAX_WINDOWS) {
                check(false, "cockpit windows: " + t.key + " - 1.." + com.sva.vehicle.CockpitView.MAX_WINDOWS + " window boxes");
                continue;
            }
            double[] e = com.sva.vehicle.CockpitView.eye(t, 0);
            boolean ray = false;
            for (double d = 0; d <= 2.0 / t.modelScale; d += 0.01) {
                ray |= com.sva.vehicle.CockpitView.inside(w[0], e[0] + d, e[1], e[2]);
            }
            float[] g = RiderFitSVA.bounds(t.model, "glass", "canopy", "window");
            StringBuilder bad = new StringBuilder();
            for (int i = 0; i < w.length && g != null; i++) {
                for (int a = 0; a < 3; a++) {
                    float tol = a == 0 ? 0.6F : 0.1F;
                    float lo = w[i][a * 2], hi = w[i][a * 2 + 1];
                    if (lo >= hi || lo > -VehicleType.OPEN && lo < g[a] - tol || hi < VehicleType.OPEN && hi > g[a + 3] + tol
                            || lo > g[a + 3] || hi < g[a]) {
                        bad.append(" box ").append(i).append(" axis ").append("xyz".charAt(a)).append(" [").append(lo).append(", ").append(hi)
                                .append("] vs glass [").append(g[a]).append(", ").append(g[a + 3]).append("];");
                    }
                }
            }
            check(ray && g != null && bad.length() == 0, "cockpit windows: " + t.key + " - " + w.length + " box(es) over the glass, the driver's eye ("
                    + String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", e[0], e[1], e[2]) + ") looks ahead through the windscreen box"
                    + (ray ? "" : " NOT through it") + (g == null ? " (no glass in the OBJ)" : "") + bad);
        }
    }

    /**
     * Cargo screen v2: «Сорт.» merges equal stacks (item + meta + NBT) and orders them by id / meta, «Мусор» takes the
     * configured throwaway blocks (the drill keeps a stack of cobblestone), the search matcher, the Shift+click
     * priority (head -> head slot, Bore Widener -> module slot, vehicles nowhere), the new actions' permissions.
     */
    private static void cargoV2() {
        net.minecraft.item.Item cob = net.minecraft.item.Item.getItemFromBlock(Blocks.cobblestone);
        net.minecraft.item.Item dirt = net.minecraft.item.Item.getItemFromBlock(Blocks.dirt);
        ItemStack[] c = new ItemStack[10];
        c[0] = new ItemStack(Items.diamond, 5);
        c[2] = new ItemStack(cob, 40);
        c[3] = new ItemStack(Items.coal, 1, 1);                 // charcoal
        c[4] = new ItemStack(cob, 40);
        c[5] = new ItemStack(Items.coal, 10, 0);
        c[7] = new ItemStack(Items.diamond, 3);
        ItemStack named = new ItemStack(Items.diamond, 2);
        named.setStackDisplayName("Special");
        c[9] = named;
        int n = com.sva.inventory.CargoToolsSVA.sortRange(c, 0, c.length, 64);
        int idCob = net.minecraft.item.Item.getIdFromItem(cob), idCoal = net.minecraft.item.Item.getIdFromItem(Items.coal),
                idDia = net.minecraft.item.Item.getIdFromItem(Items.diamond);
        boolean ordered = true;
        for (int i = 0; i + 1 < n; i++) {
            ordered &= com.sva.inventory.CargoToolsSVA.ORDER.compare(c[i], c[i + 1]) <= 0;
        }
        int cobTotal = 0, cobStacks = 0;
        for (ItemStack s : c) {
            if (s != null && s.getItem() == cob) {
                cobTotal += s.stackSize;
                cobStacks++;
            }
        }
        check(n == 6 && ordered && cobTotal == 80 && cobStacks == 2 && c[n] == null && c[c.length - 1] == null && idCob < idCoal && idCoal < idDia
                && c[0].getItem() == cob && c[0].stackSize == 64,
                "cargo sort: 80 cobblestone -> 64 + 16, diamonds merged, the renamed one apart, coal / charcoal apart; ordered by id / meta, gaps at the end ("
                        + n + " stacks)");
        boolean diaMerged = false, namedApart = false;
        for (ItemStack s : c) {
            if (s != null && s.getItem() == Items.diamond && !s.hasDisplayName() && s.stackSize == 8) {
                diaMerged = true;
            }
            if (s != null && s.getItem() == Items.diamond && s.hasDisplayName() && s.stackSize == 2) {
                namedApart = true;
            }
        }
        check(diaMerged && namedApart, "cargo sort: 5 + 3 diamonds -> 8, a renamed diamond keeps its own stack (NBT)");
        ItemStack[] one = new ItemStack[3];
        one[1] = new ItemStack(Items.ender_pearl, 15);
        one[2] = new ItemStack(Items.ender_pearl, 15);
        com.sva.inventory.CargoToolsSVA.sortRange(one, 0, 3, 64);
        check(one[0] != null && one[0].stackSize == 16 && one[1] != null && one[1].stackSize == 14 && one[2] == null,
                "cargo sort: the item's own stack limit (ender pearls 16) is kept");
        // junk
        java.util.Set<String> junk = com.sva.inventory.CargoToolsSVA.parseJunk(ConfigSVA.JUNK_DEFAULT);
        check(junk.size() == 5 && com.sva.inventory.CargoToolsSVA.isJunk("minecraft:cobblestone", 0, junk)
                && com.sva.inventory.CargoToolsSVA.isJunk("Minecraft:Dirt", 2, junk) && com.sva.inventory.CargoToolsSVA.isJunk("minecraft:netherrack", 0, junk)
                && !com.sva.inventory.CargoToolsSVA.isJunk("minecraft:diamond", 0, junk) && !com.sva.inventory.CargoToolsSVA.isJunk(null, 0, junk),
                "junk: the default list - cobblestone, dirt (any meta), gravel, netherrack, stone; diamonds are not junk");
        java.util.Set<String> meta = com.sva.inventory.CargoToolsSVA.parseJunk(new String[] {" minecraft:wool@14 ", "", null});
        check(meta.size() == 1 && com.sva.inventory.CargoToolsSVA.isJunk("minecraft:wool", 14, meta) && !com.sva.inventory.CargoToolsSVA.isJunk("minecraft:wool", 0, meta),
                "junk: \"modid:name@meta\" entries match that meta only, blanks ignored");
        check("minecraft:cobblestone".equals(com.sva.inventory.CargoToolsSVA.registryName(new ItemStack(cob))),
                "junk: an item's registry name is \"minecraft:cobblestone\"");
        ItemStack[] j = {new ItemStack(cob, 64), new ItemStack(dirt, 20), new ItemStack(Items.iron_ingot, 7), new ItemStack(cob, 30),
                new ItemStack(Blocks.gravel, 5)};
        java.util.List<ItemStack> out = com.sva.inventory.CargoToolsSVA.takeJunk(j, 0, j.length, junk, "minecraft:cobblestone", 70);
        int outN = 0;
        for (ItemStack s : out) {
            outN += s.stackSize;
        }
        check(outN == 20 + 24 + 5 && j[0] != null && j[0].stackSize == 64 && j[3] != null && j[3].stackSize == 6 && j[1] == null && j[4] == null
                && j[2] != null && j[2].stackSize == 7,
                "junk: dirt / gravel out, 70 of 94 cobblestone kept (the drill's lava stock), iron stays (" + outN + " out)");
        java.util.List<ItemStack> all = com.sva.inventory.CargoToolsSVA.takeJunk(new ItemStack[] {new ItemStack(cob, 10)}, 0, 1, junk, null, 64);
        check(all.size() == 1 && all.get(0).stackSize == 10, "junk: without a keep rule (not the drill) all the cobblestone goes");
        // search
        check(com.sva.inventory.CargoToolsSVA.matches("", "Железная руда") && com.sva.inventory.CargoToolsSVA.matches("руда", "Железная руда", "minecraft:iron_ore")
                && com.sva.inventory.CargoToolsSVA.matches("IRON", "Железная руда", "minecraft:iron_ore")
                && com.sva.inventory.CargoToolsSVA.matches("ore iron", "x", "oreIron") && !com.sva.inventory.CargoToolsSVA.matches("руда", "Булыжник", "minecraft:cobblestone")
                && !com.sva.inventory.CargoToolsSVA.matches("железная медь", "Железная руда") && com.sva.inventory.CargoToolsSVA.matches("  ", (String) null),
                "search: case-insensitive part of the name / registry name / ore name, every word must match, blank matches all");
        // Shift+click priority
        int h = com.sva.inventory.CargoToolsSVA.transferTarget(true, true, false, true, true, false);
        int hBusy = com.sva.inventory.CargoToolsSVA.transferTarget(true, true, false, false, true, false);
        int m = com.sva.inventory.CargoToolsSVA.transferTarget(true, false, true, true, true, false);
        int mBusy = com.sva.inventory.CargoToolsSVA.transferTarget(true, false, true, true, false, false);
        int noDrill = com.sva.inventory.CargoToolsSVA.transferTarget(false, true, false, true, true, false);
        int veh = com.sva.inventory.CargoToolsSVA.transferTarget(true, false, false, true, true, true);
        check(h == com.sva.inventory.CargoToolsSVA.TO_HEAD && hBusy == com.sva.inventory.CargoToolsSVA.TO_CARGO && m == com.sva.inventory.CargoToolsSVA.TO_MODULE
                && mBusy == com.sva.inventory.CargoToolsSVA.TO_CARGO && noDrill == com.sva.inventory.CargoToolsSVA.TO_CARGO
                && veh == com.sva.inventory.CargoToolsSVA.TO_NONE,
                "shift+click: head -> head slot (taken: cargo), Bore Widener -> module slot, other vehicles -> cargo, vehicle items nowhere");
        // actions
        check(EntityVehicleSVA.A_SORT == 12 && EntityVehicleSVA.A_JUNK == 13 && EntityVehicleSVA.A_MODE_SET == 14
                && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_SORT, true, false) && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_SORT, false, true)
                && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_JUNK, false, true)
                && EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_MODE_SET + DrillMath.MODE_LEVEL, false, true)
                && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_MODE_SET + 1, false, false)
                && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_MODE_SET + DrillMath.MODES, false, true),
                "cargo actions: sort / junk owner or op only; the drill mode buttons owner or driver (as the B / N keys)");
        VehicleState st = new VehicleState(VehicleType.DRILL);
        st.torches = 23;
        NBTTagCompound tag = new NBTTagCompound();
        st.writeNBT(tag);
        VehicleState back = new VehicleState(VehicleType.DRILL);
        back.readNBT(tag);
        check(back.torches == 23, "drill: torches put down are saved (the cargo screen's drill tab counter)");
    }

    /**
     * Skins (design *1-*3, SkinMath): liveries by tier, the board number from the vehicle id, box-projected UVs, the
     * material tiles in the jar (16x16), the livery overlays (hazard band / stripe / glowing lines), the decal (number,
     * emblem, clear rim, mirrored on the left side) and that every model's decal box lies on painted side faces.
     */
    private static void skins() throws java.io.IOException {
        // liveries by tier
        check(SkinMath.livery(Tier.MV) == SkinMath.WORK && SkinMath.livery(Tier.HV) == SkinMath.CIVIL && SkinMath.livery(Tier.EV) == SkinMath.CIVIL
                && SkinMath.livery(Tier.QV) == SkinMath.SILICON && SkinMath.livery(Tier.XV) == SkinMath.SILICON
                && SkinMath.livery(Tier.SV) == SkinMath.SINGULAR, "skins: livery by tier (MV I, HV/EV II, QV/XV III, SV Singular)");
        boolean[] seen = new boolean[4];
        for (VehicleType t : VehicleType.values()) {
            seen[SkinMath.livery(t.tier)] = true;
        }
        check(seen[0] && seen[1] && seen[2] && seen[3], "skins: every livery is worn by some vehicle");
        check(SkinMath.paintColour(SkinMath.SILICON, "paint", "paint", 0xEEAC18) == 0x1E2636
                && SkinMath.paintColour(SkinMath.SINGULAR, "paint2", "paint", 0x1A1A1E) == 0x0C0814
                && SkinMath.paintColour(SkinMath.SILICON, "white", "white", 0xE1E4E8) == 0x1E2636
                && SkinMath.paintColour(SkinMath.WORK, "paint", "paint", 0xEEAC18) == 0xEEAC18
                && SkinMath.paintColour(SkinMath.CIVIL, "paint2", "paint", 0x123456) == 0x123456
                && SkinMath.paintColour(SkinMath.SILICON, "steel", "paint", 0x787C84) == 0x787C84,
                "skins: III / Singular repaint paint (white without paint) and paint2, I / II keep the paint");
        check("paint_smooth".equals(SkinMath.tileFor(SkinMath.SINGULAR, "paint")) && "paint".equals(SkinMath.tileFor(SkinMath.SILICON, "paint"))
                && "paint".equals(SkinMath.tileFor(-1, "paint")) && "steel".equals(SkinMath.tileFor(SkinMath.SINGULAR, "steel")),
                "skins: the Singular paint has no rivets");
        // board number
        String id = java.util.UUID.randomUUID().toString();
        java.util.Set<Integer> nums = new java.util.HashSet<Integer>();
        boolean range = true;
        for (int i = 0; i < 600; i++) {
            int n = SkinMath.boardNumber(java.util.UUID.randomUUID().toString());
            range &= n >= 1 && n <= 99;
            nums.add(Integer.valueOf(n));
        }
        check(range && nums.size() >= 90 && SkinMath.boardNumber(id) == SkinMath.boardNumber(new String(id)) && SkinMath.boardNumber(null) == 1
                && SkinMath.boardNumber("") == 1, "skins: board number 1..99, stable per id, spread (" + nums.size() + " of 99 over 600 ids)");
        check("07".equals(SkinMath.boardText(7)) && "42".equals(SkinMath.boardText(42)) && "99".equals(SkinMath.boardText(150))
                && "01".equals(SkinMath.boardText(0)), "skins: board number as two digits");
        VehicleState st = new VehicleState(VehicleType.BUGGY);
        NBTTagCompound tag = new NBTTagCompound();
        st.writeNBT(tag);
        VehicleState back = new VehicleState(VehicleType.BUGGY);
        back.readNBT(tag);
        check(SkinMath.boardNumber(back.vehId) == SkinMath.boardNumber(st.vehId), "skins: the board number survives the item NBT");
        // UVs: dominant axis, one tile per model unit, sides read upright
        float[] uv = new float[2], uv2 = new float[2];
        SkinMath.uv(2, 0.25F, 1F, 0.5F, uv);
        SkinMath.uv(2, 1.25F, 2F, 0.5F, uv2);
        boolean side = near(uv2[0] - uv[0], 1, 1e-6) && near(uv2[1] - uv[1], -1, 1e-6);
        SkinMath.uv(0, 3F, 1F, 0.5F, uv);
        SkinMath.uv(1, 0.25F, 7F, -0.5F, uv2);
        check(SkinMath.axis(0.1F, 0.2F, -0.9F) == 2 && SkinMath.axis(-0.8F, 0.5F, 0.1F) == 0 && SkinMath.axis(0.3F, 0.9F, 0.3F) == 1 && side
                && near(uv[0], 0.5, 1e-6) && near(uv[1], -1, 1e-6) && near(uv2[0], 0.25, 1e-6) && near(uv2[1], -0.5, 1e-6),
                "skins: box projection along the face's dominant axis, 1 tile (16 texels) per model unit, v down the sides");
        check("dark_plate".equals(SkinMath.tile("dark", 1, 0.95F, false)) && "dark_grille".equals(SkinMath.tile("dark", 0, 0.1F, false))
                && "dark".equals(SkinMath.tile("dark", 2, 0.1F, false)) && SkinMath.tile("screen", 0, 0F, true) == null
                && "lamp".equals(SkinMath.tile("lamp", 0, 0F, true)) && "paint".equals(SkinMath.tile("white", 2, 0F, false))
                && SkinMath.ownColours("hazard") && !SkinMath.ownColours("paint"), "skins: tiles by material and face (floor plate, grille, screens untextured)");
        // every tile is in the jar, 16x16; every groups.txt material maps to one of them (or stays untextured)
        StringBuilder bad = new StringBuilder();
        java.util.Set<String> known = new java.util.HashSet<String>(java.util.Arrays.asList(SkinMath.TILES));
        for (String t : SkinMath.TILES) {
            java.io.InputStream in = SelfTestSVA.class.getResourceAsStream("/assets/siliconagevehicles/textures/models/" + t + ".png");
            java.awt.image.BufferedImage img = in == null ? null : javax.imageio.ImageIO.read(in);
            if (img == null || img.getWidth() != 16 || img.getHeight() != 16) {
                bad.append(' ').append(t);
            }
        }
        java.util.Set<String> models = new java.util.HashSet<String>();
        for (VehicleType t : VehicleType.values()) {
            models.add(t.model);
        }
        for (String m : models) {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    SelfTestSVA.class.getResourceAsStream("/assets/siliconagevehicles/models/" + m + ".groups.txt"), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 5 || p[0].startsWith("#")) {
                        continue;
                    }
                    for (int ax = 0; ax < 3; ax++) {
                        String t = SkinMath.tile(p[0], ax, ax == 1 ? 1F : 0F, "1".equals(p[4]));
                        if (t != null && !known.contains(t)) {
                            bad.append(' ').append(m).append('/').append(p[0]).append("->").append(t);
                        }
                    }
                }
            } finally {
                r.close();
            }
        }
        check(bad.length() == 0, "skins: material tiles in the jar (16x16), every model material maps to one" + bad);
        // overlays on a 4 x 2 unit paint box
        float[] box = {-2F, 0F, 2F, 2F};
        int w = SkinMath.overlayW(box), h = SkinMath.overlayH(box);
        int[] work = SkinMath.overlay(SkinMath.WORK, "paint", "paint", box, 0x2E5288, 0xEEAC18, false);
        boolean yellow = false, black = false, cleanMid = true, stripe = false, rim = true;
        for (int j = 0; j < h && work != null; j++) {
            float hh = (box[3] - (j - 0.5F) / 16F - box[1]) / (box[3] - box[1]);
            for (int i = 0; i < w; i++) {
                int c = work[j * w + i];
                if (i == 0 || j == 0 || i == w - 1 || j == h - 1) {
                    rim &= c == 0;
                } else if (hh < 0.19F) {
                    yellow |= c == 0xFFEBBE1E;
                    black |= c == 0xFF18181A;
                } else if (hh > 0.22F && hh < 0.38F || hh > 0.5F) {
                    cleanMid &= c == 0;
                } else if (hh > 0.42F && hh < 0.46F) {
                    stripe |= c == 0xFF1A1A1E;
                }
            }
        }
        check(w == 66 && h == 34 && work != null && yellow && black && stripe && cleanMid && rim,
                "skins: livery I - black-yellow hazard band on the lowest 20 %, a dark stripe, clear elsewhere and on the rim");
        int[] civil = SkinMath.overlay(SkinMath.CIVIL, "paint", "paint", box, 0x2E5288, 0xEEAC18, false);
        boolean civ = false;
        for (int k = 0; civil != null && k < civil.length; k++) {
            civ |= civil[k] == 0xFF2E5288;
        }
        check(civ && SkinMath.overlay(SkinMath.CIVIL, "white", "paint", box, 0xE1E4E8, 0xE1E4E8, false) == null
                && SkinMath.overlay(SkinMath.CIVIL, "paint", "paint", box, 0x2E5288, 0xEEAC18, true) == null,
                "skins: livery II - a stripe in the accent colour (none on a group of that colour), no glow");
        int[] glow = SkinMath.overlay(SkinMath.SILICON, "paint", "paint", box, 0, 0, true);
        int lit = 0;
        for (int k = 0; glow != null && k < glow.length; k++) {
            lit += glow[k] == (0xFF000000 | SkinMath.glowColour(SkinMath.SILICON)) ? 1 : 0;
        }
        check(glow != null && lit > 40 && lit < glow.length / 4 && SkinMath.overlay(SkinMath.SILICON, "paint", "paint", box, 0, 0, false) == null
                && SkinMath.overlay(SkinMath.SINGULAR, "paint2", "paint", box, 0, 0, true) != null
                && SkinMath.overlay(SkinMath.SINGULAR, "steel", "paint", box, 0, 0, true) == null,
                "skins: III / Singular - glowing lines on paint and paint2 (" + lit + " texels), no stripe layer");
        // decal
        int[] d7 = SkinMath.decal(7, 0xFFFFFF), d8 = SkinMath.decal(8, 0xFFFFFF);
        boolean differ = false, decRim = true;
        for (int k = 0; k < d7.length; k++) {
            differ |= d7[k] != d8[k] && k % SkinMath.DECAL_W >= 18 && k % SkinMath.DECAL_W < 40;
            int x = k % SkinMath.DECAL_W, y = k / SkinMath.DECAL_W;
            if (x == 0 || y == 0 || x == SkinMath.DECAL_W - 1 || y == SkinMath.DECAL_H - 1) {
                decRim &= d7[k] == 0;
            }
        }
        check(differ && decRim && d7[8 * SkinMath.DECAL_W + 8] == 0xFF141418 && d7[8 * SkinMath.DECAL_W + 2] == 0xFFFFFFFF,
                "skins: decal - hexagon emblem (dark core), the number's digits, a clear rim");
        float[] db = {1F, 0F, 3F, 0.5F};
        SkinMath.decalUv(db, 1.5F, 0.4F, 0.9F, uv);
        SkinMath.decalUv(db, 1.5F, 0.4F, -0.9F, uv2);
        check(near(uv[0], 0.25, 1e-6) && near(uv2[0], 0.75, 1e-6) && near(uv[1], 0.2, 1e-6) && near(uv2[1], 0.2, 1e-6),
                "skins: decal UVs mirrored on the left side (the emblem leads, the number reads left to right on both sides)");
        // every model's decal box lies on its painted side faces, nothing in front of it (both sides)
        for (String m : models) {
            float[] b = SkinMath.decalBox(m);
            if (b == null) {
                check(false, "skins: " + m + " has a decal box");
                continue;
            }
            float[][] tris = paintTris(m);
            int[] cov = new int[2];
            int tot = 0;
            for (int xi = 0; xi < 10; xi++) {
                for (int yi = 0; yi < 3; yi++) {
                    float x = b[0] + (b[2] - b[0]) * (0.05F + 0.1F * xi), y = b[1] + (b[3] - b[1]) * (0.1F + 0.4F * yi);
                    tot++;
                    for (int s = 0; s < 2; s++) {
                        float sgn = s == 0 ? 1F : -1F, top = -1e9F;
                        float[] front = null;
                        for (float[] t : tris) {
                            float z = zAt(t, x, y);
                            if (!Float.isNaN(z) && z * sgn > top) {
                                top = z * sgn;
                                front = t;
                            }
                        }
                        if (front != null && front[12] > 0 && front[11] * sgn > SkinMath.SIDE_NZ) {
                            cov[s]++;
                        }
                    }
                }
            }
            check(cov[0] >= tot * 0.85F && cov[1] >= tot * 0.85F, "skins: " + m + " decal box on the outermost painted side faces (right " + cov[0] + "/" + tot
                    + ", left " + cov[1] + "/" + tot + ")");
        }
    }

    /**
     * The triangles of models/NAME.obj but the glass: 9 position floats, the face normal (turned along the smooth normals),
     * then 1 for a painted one.
     */
    private static float[][] paintTris(String model) throws java.io.IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                SelfTestSVA.class.getResourceAsStream("/assets/siliconagevehicles/models/" + model + ".obj"), "UTF-8"), 1 << 16);
        java.util.List<float[]> v = new java.util.ArrayList<float[]>(), vn = new java.util.ArrayList<float[]>(), out = new java.util.ArrayList<float[]>();
        boolean take = false, paint = false;
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ") || line.startsWith("vn ")) {
                    String[] p = line.trim().split("\\s+");
                    (line.startsWith("v ") ? v : vn).add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                } else if (line.startsWith("g ")) {
                    String g = line.substring(2).trim();
                    paint = SkinMath.paintable(g);
                    take = !"glass".equals(g) && !"canopy".equals(g);
                } else if (take && line.startsWith("f ")) {
                    String[] p = line.substring(2).trim().split("\\s+");
                    for (int i = 1; i + 1 < p.length; i++) {
                        int[] idx = {0, i, i + 1};
                        float[] t = new float[13];
                        float sx = 0, sy = 0, sz = 0;
                        for (int k = 0; k < 3; k++) {
                            String[] ix = p[idx[k]].split("/");
                            float[] q = v.get(Integer.parseInt(ix[0]) - 1);
                            System.arraycopy(q, 0, t, k * 3, 3);
                            if (ix.length >= 3 && ix[2].length() > 0) {
                                float[] n = vn.get(Integer.parseInt(ix[2]) - 1);
                                sx += n[0];
                                sy += n[1];
                                sz += n[2];
                            }
                        }
                        float ux = t[3] - t[0], uy = t[4] - t[1], uz = t[5] - t[2], wx = t[6] - t[0], wy = t[7] - t[1], wz = t[8] - t[2];
                        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
                        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (l < 1e-9F) {
                            continue;
                        }
                        float s = nx * sx + ny * sy + nz * sz < 0 ? -1F : 1F;
                        t[9] = s * nx / l;
                        t[10] = s * ny / l;
                        t[11] = s * nz / l;
                        t[12] = paint ? 1F : 0F;
                        out.add(t);
                    }
                }
            }
        } finally {
            r.close();
        }
        return out.toArray(new float[out.size()][]);
    }

    /** The triangle's z over (x, y) in its xy projection, NaN outside it. */
    private static float zAt(float[] t, float x, float y) {
        float det = (t[4] - t[7]) * (t[0] - t[6]) + (t[6] - t[3]) * (t[1] - t[7]);
        if (Math.abs(det) < 1e-12F) {
            return Float.NaN;
        }
        float w0 = ((t[4] - t[7]) * (x - t[6]) + (t[6] - t[3]) * (y - t[7])) / det;
        float w1 = ((t[7] - t[1]) * (x - t[6]) + (t[0] - t[6]) * (y - t[7])) / det;
        float w2 = 1 - w0 - w1;
        return w0 < -1e-6F || w1 < -1e-6F || w2 < -1e-6F ? Float.NaN : w0 * t[2] + w1 * t[5] + w2 * t[8];
    }
    // ---- *14 live cockpit instruments (vehicle/CockpitLayout, vehicle/CockpitDials)

    /** A synthetic OBJ: v lines + groups of faces, built up piece by piece (1-based indices). */
    private static final class ObjOut {
        final StringBuilder v = new StringBuilder(), f = new StringBuilder();
        int n;

        void group(String g) {
            f.append("g ").append(g).append('\n');
        }

        int vert(double x, double y, double z) {
            v.append("v ").append((float) x).append(' ').append((float) y).append(' ').append((float) z).append('\n');
            return ++n;
        }

        void face(int... ix) {
            f.append('f');
            for (int i : ix) {
                f.append(' ').append(i);
            }
            f.append('\n');
        }

        /** export_obj.py's dial: a `sides`-gon cylinder along x (faces -x) at (x, y, z), radius r, caps fanned from centres. */
        void cylinder(double x, double y, double z, double r, double thick, int sides) {
            int c0 = vert(x, y, z), c1 = vert(x + thick, y, z);
            int[] a = new int[sides], b = new int[sides];
            for (int i = 0; i < sides; i++) {
                double t = i * Math.PI * 2 / sides;
                a[i] = vert(x, y + Math.cos(t) * r, z + Math.sin(t) * r);
                b[i] = vert(x + thick, y + Math.cos(t) * r, z + Math.sin(t) * r);
            }
            for (int i = 0; i < sides; i++) {
                int j = (i + 1) % sides;
                face(a[i], a[j], b[j], b[i]);
                face(c0, a[j], a[i]);
                face(c1, b[i], b[j]);
            }
        }

        /** A flattened UV sphere (the round overhead buttons). */
        void sphere(double x, double y, double z, double r) {
            int rings = 6, seg = 12;
            int[][] ring = new int[rings + 1][seg];
            for (int k = 0; k <= rings; k++) {
                double phi = k * Math.PI / rings;
                for (int i = 0; i < seg; i++) {
                    double t = i * Math.PI * 2 / seg;
                    ring[k][i] = vert(x - Math.cos(phi) * r * 0.3, y + Math.sin(phi) * Math.cos(t) * r, z + Math.sin(phi) * Math.sin(t) * r);
                }
            }
            for (int k = 0; k < rings; k++) {
                for (int i = 0; i < seg; i++) {
                    int j = (i + 1) % seg;
                    face(ring[k][i], ring[k][j], ring[k + 1][j], ring[k + 1][i]);
                }
            }
        }

        /** A box (8 corners) - square from the front: the same radius at every corner, but not round. */
        void box(double x, double y, double z, double h) {
            int[] c = new int[8];
            for (int i = 0; i < 8; i++) {
                c[i] = vert(x + ((i & 1) != 0 ? 0.01 : 0), y + ((i & 2) != 0 ? h : -h), z + ((i & 4) != 0 ? h : -h));
            }
            face(c[0], c[2], c[6], c[4]);
            face(c[1], c[3], c[7], c[5]);
            face(c[0], c[1], c[3], c[2]);
            face(c[4], c[5], c[7], c[6]);
            face(c[0], c[1], c[5], c[4]);
            face(c[2], c[3], c[7], c[6]);
        }

        java.io.InputStream stream() throws java.io.UnsupportedEncodingException {
            return new java.io.ByteArrayInputStream((v.toString() + f.toString()).getBytes("UTF-8"));
        }
    }

    private static void cockpitInstruments() throws java.io.IOException {
        // dial clustering on a tiny synthetic dash: the eye at the origin, the dash 0.5 ahead (x)
        ObjOut o = new ObjOut();
        o.group("gauge");
        o.cylinder(0.5, 0, 0.15, 0.06, 0.02, 12);          // right dial
        o.cylinder(0.5, 0, -0.15, 0.06, 0.02, 12);         // left dial
        o.sphere(0.5, -0.12, 0, 0.04);                    // a button of the dial colour (r 0.04 but not a disc)
        o.box(0.5, 0.12, 0, 0.05);                        // a square plate
        o.cylinder(0.51, 0.01, -0.15, 0.04, 0.005, 14);   // a smaller disc behind the left dial
        o.cylinder(2.5, 0, 0, 0.06, 0.02, 12);            // far away (2.5 > MAX_DIST)
        o.group("needle");
        o.box(0.4875, 0.02, 0.15, 0.005);                 // the static needle in front of the right dial
        o.group("holo");
        int[] q = {o.vert(0.55, -0.05, -0.05), o.vert(0.55, -0.05, 0.05), o.vert(0.55, 0.05, 0.05), o.vert(0.55, 0.05, -0.05)};
        o.face(q[0], q[1], q[2], q[3]);
        com.sva.vehicle.CockpitLayout l = com.sva.vehicle.CockpitLayout.parse(o.stream(), new float[] {0, 0, 0});
        check(l.dials.size() == 2, "cockpit: synthetic dash - 2 dials (the button, plate, hidden disc and far dial left out), got " + l.dials.size());
        if (l.dials.size() == 2) {
            com.sva.vehicle.CockpitLayout.Dial a = l.dials.get(0), b = l.dials.get(1);
            check(near(a.c[2], -0.15, 1e-3) && near(b.c[2], 0.15, 1e-3) && near(a.c[0], 0.51, 1e-3) && near(a.r, 0.06, 1e-3),
                    "cockpit: dial centres / radius found, sorted left -> right (z " + a.c[2] + ", " + b.c[2] + ", r " + a.r + ")");
            check(near(a.n[0], -1, 1e-3) && near(a.up[1], 1, 1e-3) && near(a.right[2], 1, 1e-3),
                    "cockpit: dial normal faces the eye (-x), up = +y, right = +z");
            check(near(b.front, 0.0225, 1e-3) && near(a.front, 0.01, 1e-3),
                    "cockpit: the face plane goes over the static needle (" + b.front + ") / the dial front (" + a.front + ")");
        }
        check(l.screens.size() == 1 && near(l.screens.get(0).w, 0.1, 1e-3) && near(l.screens.get(0).h, 0.1, 1e-3),
                "cockpit: the flat holo is a 0.1 x 0.1 screen");
        java.util.List<java.util.List<float[]>> comps = com.sva.vehicle.CockpitLayout.components(
                java.util.Arrays.asList(new float[] {0, 0, 0}, new float[] {1, 0, 0}, new float[] {0, 1, 0}, new float[] {1, 1, 0},
                        new float[] {5, 5, 5}, new float[] {6, 5, 5}, new float[] {5, 6, 5}, new float[] {1F, 1F, 0F}),
                java.util.Arrays.asList(new int[] {0, 1, 2}, new int[] {7, 1, 2}, new int[] {4, 5, 6}));
        check(comps.size() == 2, "cockpit: components join triangles by shared vertex positions (duplicated vertices too), got " + comps.size());
        float[] free = com.sva.vehicle.CockpitLayout.freeRect(new float[] {0, 1, 0, 1},
                java.util.Collections.singletonList(new float[] {-0.2F, 1.2F, -0.1F, 0.6F}));
        check(free != null && near(free[2], 0.6, 1e-6) && near(free[3], 1, 1e-6) && near(free[1] - free[0], 1, 1e-6),
                "cockpit: a screen's free rectangle above the dials covering its lower part");
        // needle angle mapping
        check(com.sva.vehicle.CockpitDials.needleAngle(0, 0, 100) == -135F && com.sva.vehicle.CockpitDials.needleAngle(100, 0, 100) == 135F
                && com.sva.vehicle.CockpitDials.needleAngle(50, 0, 100) == 0F, "cockpit: needle -135 at the bottom of the scale, +135 at the top, 0 mid");
        check(com.sva.vehicle.CockpitDials.needleAngle(500, 0, 100) == 135F && com.sva.vehicle.CockpitDials.needleAngle(-5, 0, 100) == -135F
                && com.sva.vehicle.CockpitDials.needleAngle(Float.NaN, 0, 100) == -135F
                && com.sva.vehicle.CockpitDials.needleAngle(0, -10, 10) == 0F, "cockpit: needle clamped to the sweep (NaN at rest); vertical speed 0 points up");
        check(com.sva.vehicle.CockpitDials.niceMax(36) == 40F && com.sva.vehicle.CockpitDials.niceMax(250) == 250F
                && com.sva.vehicle.CockpitDials.niceMax(81) == 100F && com.sva.vehicle.CockpitDials.niceMax(0) == 1F,
                "cockpit: scale tops are round numbers (36 -> 40, 81 -> 100)");
        float f = com.sva.vehicle.CockpitDials.follow(0F, 100F, 0.15F);
        check(near(f, 100 * (1 - Math.exp(-1)), 0.01) && com.sva.vehicle.CockpitDials.follow(Float.NaN, 42F, 0.02F) == 42F
                && com.sva.vehicle.CockpitDials.follow(10F, 42F, 5F) == 42F, "cockpit: needles ease in (63% in 0.15 s), jump on the first frame / after a pause");
        // the per-type dial tables against the real models
        int[] expect = new int[VehicleType.values().length];
        expect[VehicleType.BUGGY.ordinal()] = 2;
        expect[VehicleType.HOVER.ordinal()] = 0;
        expect[VehicleType.HELI.ordinal()] = 5;
        expect[VehicleType.PLANE.ordinal()] = 4;                  // stage C: the HSI (a panel) took the heading dial's place
        expect[VehicleType.TRUCK.ordinal()] = 2;
        expect[VehicleType.DRILL.ordinal()] = 4;
        expect[VehicleType.JET.ordinal()] = 1;
        expect[VehicleType.TILT.ordinal()] = 4;
        expect[VehicleType.SHUTTLE.ordinal()] = 4;
        expect[VehicleType.SHIP.ordinal()] = 4;
        StringBuilder found = new StringBuilder();
        boolean tablesOk = true, countsOk = true, screensOk = true;
        for (VehicleType t : VehicleType.values()) {
            com.sva.vehicle.CockpitLayout cl = com.sva.vehicle.CockpitLayout.of(t);
            int[] table = com.sva.vehicle.CockpitDials.table(t);
            found.append(t.key).append(' ').append(cl.dials.size()).append('/').append(cl.screens.size()).append(' ');
            tablesOk &= table.length >= Math.max(1, cl.dials.size()) && com.sva.vehicle.CockpitDials.smallTable(t).length > 0;
            for (int k : table) {
                tablesOk &= k >= 0 && k < com.sva.vehicle.CockpitDials.KEYS.length;
            }
            countsOk &= cl.dials.size() == expect[t.ordinal()];
            screensOk &= t == VehicleType.HOVER ? cl.screens.size() == 1 : cl.screens.size() >= 2;
        }
        check(tablesOk, "cockpit: every type's dial table covers all its model's dials (dials/screens: " + found.toString().trim() + ")");
        check(countsOk, "cockpit: dial counts per model as measured (buggy 2, hover 0, heli 5, plane 4, truck 2, drill 4, jet 1, tilt 4, shuttle 4, ship 4)");
        check(screensOk, "cockpit: every cockpit has screens (hoverbike: its one flat screen)");
        int[] pr = com.sva.vehicle.CockpitDials.screenRoles(com.sva.vehicle.CockpitLayout.of(VehicleType.PLANE).screens, true);
        int[] jr = com.sva.vehicle.CockpitDials.screenRoles(com.sva.vehicle.CockpitLayout.of(VehicleType.JET).screens, true);
        check(contains(pr, com.sva.vehicle.CockpitDials.SCREEN_ATTITUDE) && contains(jr, com.sva.vehicle.CockpitDials.SCREEN_ATTITUDE)
                && contains(jr, com.sva.vehicle.CockpitDials.SCREEN_MAIN),
                "cockpit: the plane and the jet get an attitude indicator, the jet a text screen too");
        java.util.List<com.sva.vehicle.CockpitLayout.Screen> syn = new java.util.ArrayList<com.sva.vehicle.CockpitLayout.Screen>();
        float[] z3 = {0, 0, 0};
        syn.add(new com.sva.vehicle.CockpitLayout.Screen("holo", z3, z3, z3, z3, 0.4F, 0.1F, 0));
        syn.add(new com.sva.vehicle.CockpitLayout.Screen("holo", z3, z3, z3, z3, 0.1F, 0.1F, 0));
        syn.add(new com.sva.vehicle.CockpitLayout.Screen("holo", z3, z3, z3, z3, 0.04F, 0.11F, 0));
        int[] r1 = com.sva.vehicle.CockpitDials.screenRoles(syn, true), r2 = com.sva.vehicle.CockpitDials.screenRoles(syn, false);
        check(r1[0] == com.sva.vehicle.CockpitDials.SCREEN_MAIN && r1[1] == com.sva.vehicle.CockpitDials.SCREEN_ATTITUDE
                && r1[2] == com.sva.vehicle.CockpitDials.SCREEN_BAR && r2[1] == com.sva.vehicle.CockpitDials.SCREEN_SMALL,
                "cockpit: screen jobs - wide = text, square = attitude (aircraft) / one reading, narrow = bar");
        // every dial label / unit is translated
        boolean langOk = true;
        for (String file : new String[] {"/assets/siliconagevehicles/lang/ru_RU.lang", "/assets/siliconagevehicles/lang/en_US.lang"}) {
            java.util.Properties props = new java.util.Properties();
            java.io.InputStream in = SelfTestSVA.class.getResourceAsStream(file);
            if (in == null) {
                langOk = false;
                continue;
            }
            try {
                props.load(new java.io.InputStreamReader(in, "UTF-8"));
            } finally {
                in.close();
            }
            for (String k : com.sva.vehicle.CockpitDials.KEYS) {
                langOk &= props.containsKey("sva.cockpit.dial." + k) && props.containsKey("sva.cockpit.unit." + k);
            }
        }
        check(langOk, "cockpit: every dial label and unit is in ru_RU and en_US");
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    // ---- ★6 / ★7 / ★10: lights and part animations (vehicle/AnimParts, AnimMath, VehicleLights)

    /** models/NAME.obj as triangles: {group, float[9] positions, the "# part" tag (stage A) or null}. */
    static java.util.List<Object[]> objTris(String model) throws java.io.IOException {
        java.io.InputStream in = SelfTestSVA.class.getResourceAsStream("/assets/siliconagevehicles/models/" + model + ".obj");
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        if (in == null) {
            return out;
        }
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"), 1 << 16);
        java.util.List<float[]> v = new java.util.ArrayList<float[]>();
        String g = "default", tag = null;
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.trim().split("\\s+");
                    v.add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                } else if (line.startsWith("g ")) {
                    g = line.substring(2).trim();
                    tag = null;
                } else if (line.startsWith("# part ")) {
                    String tg = line.substring(7).trim();
                    tag = "-".equals(tg) ? null : tg;
                } else if (line.startsWith("f ")) {
                    String[] p = line.substring(2).trim().split("\\s+");
                    for (int i = 1; i + 1 < p.length; i++) {
                        float[] t = new float[9];
                        int[] idx = {0, i, i + 1};
                        for (int k = 0; k < 3; k++) {
                            System.arraycopy(v.get(Integer.parseInt(p[idx[k]].split("/")[0]) - 1), 0, t, k * 3, 3);
                        }
                        out.add(new Object[] {g, t, tag});
                    }
                }
            }
        } finally {
            r.close();
        }
        return out;
    }

    /**
     * Every animated part's box catches its triangles in its model (cut parts: clips a piece out), wheels turn about their
     * real axle (the caught vertices lie within the radius of the pivot, the farthest at ~ the radius), surfaces hinge on
     * a face of their box, the table's flags; and the clipping keeps every bit of a triangle.
     */
    private static void animParts() throws java.io.IOException {
        java.util.Map<String, java.util.List<Object[]>> cache = new java.util.HashMap<String, java.util.List<Object[]>>();
        int defs = 0;
        for (com.sva.vehicle.AnimParts.Def d : com.sva.vehicle.AnimParts.all()) {
            java.util.List<Object[]> tris = cache.get(d.model);
            if (tris == null) {
                tris = objTris(d.model);
                cache.put(d.model, tris);
            }
            int n = 0;
            float far = 0;
            for (Object[] o : tris) {
                String g = (String) o[0];
                float[] t = (float[]) o[1];
                float[] a = {t[0], t[1], t[2]}, b = {t[3], t[4], t[5]}, c = {t[6], t[7], t[8]};
                boolean in;
                if (!d.takesTag((String) o[2])) {
                    in = false;                                     // stage A: a tagged piece is only its rows'
                } else if (d.cut) {
                    in = d.takes(g) && d.overlaps(a, b, c)
                            && com.sva.vehicle.AnimParts.clipBox(new float[][] {a, b, c}, d.min, d.max, new java.util.ArrayList<float[][]>()) != null;
                } else {
                    in = d.holds(g, (t[0] + t[3] + t[6]) / 3F, (t[1] + t[4] + t[7]) / 3F, (t[2] + t[5] + t[8]) / 3F);
                }
                if (in) {
                    n++;
                    if (d.kind == com.sva.vehicle.AnimParts.WHEEL) {
                        for (int k = 0; k < 3; k++) {
                            float dx = t[k * 3] - d.pivot[0], dy = t[k * 3 + 1] - d.pivot[1];
                            far = Math.max(far, (float) Math.sqrt(dx * dx + dy * dy));
                        }
                    }
                }
            }
            defs++;
            check(n >= d.minTris, "anim parts: " + d.model + " " + d.name + " catches " + n + " triangles (>= " + d.minTris + ")");
            if (d.kind == com.sva.vehicle.AnimParts.WHEEL && d.radius > 0) {
                check(far >= d.radius * 0.85F && far <= d.radius * 1.2F, "anim parts: " + d.model + " " + d.name + " spins about its axle (farthest "
                        + far + " ~ r " + d.radius + ")");
            }
            if (d.kind == com.sva.vehicle.AnimParts.SURFACE && d.cut) {
                // a cut surface's box face is its hinge (stage A's tagged pieces hinge inside their box: the nose ahead of it)
                boolean hinge = d.axis == 1 || d.axis == 2 ? d.pivot[0] == d.min[0] || d.pivot[0] == d.max[0] : false;
                check(hinge && Math.abs(d.gain) == 1F, "anim parts: " + d.model + " " + d.name + " hinges on its box face x " + d.pivot[0]);
            }
        }
        check(defs >= 60, "anim parts: table has " + defs + " parts");
        // flags: the buggy steers with its front wheels only, the truck's trailer wheels go with the trailer, the gear
        boolean steerOk = true, trailerOk = false, gearOk = true;
        for (com.sva.vehicle.AnimParts.Def d : com.sva.vehicle.AnimParts.of("buggy")) {
            steerOk &= d.steer == d.name.contains("F");
        }
        for (com.sva.vehicle.AnimParts.Def d : com.sva.vehicle.AnimParts.of("truck")) {
            trailerOk |= d.trailer && d.pivot[0] < -1.25F;
            steerOk &= !d.steer;
        }
        for (String m : new String[] {"plane", "jet", "shuttle"}) {
            int g = 0;
            for (com.sva.vehicle.AnimParts.Def d : com.sva.vehicle.AnimParts.of(m)) {
                g += d.gearPivot != null ? 1 : 0;
            }
            gearOk &= g >= 3;
        }
        check(steerOk && trailerOk && gearOk, "anim parts: flags (buggy front wheels steer, trailer wheels with the trailer, gear on plane/jet/shuttle)");
        // ailerons move opposite ways, elevators the same way
        for (String m : new String[] {"plane", "jet", "tilt"}) {
            float l = 0, r = 0, el = 0;
            int ne = 0, nr = 0;
            for (com.sva.vehicle.AnimParts.Def d : com.sva.vehicle.AnimParts.of(m)) {
                // stage A: the tiltrotor's flaperons (DEVICE + ailMix) are its ailerons; the plane's elevator wicks ride along
                boolean flaperon = d.kind == com.sva.vehicle.AnimParts.DEVICE && d.ailMix != 0F;
                if (d.kind != com.sva.vehicle.AnimParts.SURFACE && !flaperon || d.name.contains("Wicks")) {
                    continue;
                }
                if (d.control == com.sva.vehicle.AnimParts.AILERON) {
                    if (d.min[2] > 0) {
                        r = d.gain;
                    } else {
                        l = d.gain;
                    }
                } else if (d.control == com.sva.vehicle.AnimParts.ELEVATOR) {
                    el += d.gain;
                    ne++;
                } else {
                    nr++;
                }
            }
            check(l == -r && l != 0 && Math.abs(el) == ne && ne > 0 && nr > 0, "anim parts: " + m + " ailerons opposite (L " + l + " R " + r
                    + "), " + ne + " elevator(s) together, " + nr + " rudder(s)");
        }
        // clipping: a triangle across a box splits into pieces whose areas add up, the inside piece within the box
        float[] mn = {0F, 0F, -1F}, mx = {1F, 1F, 1F};
        float[][] tri = {{-0.5F, 0.2F, 0F, 0F, 1F, 0F}, {1.5F, 0.4F, 0F, 0F, 1F, 0F}, {0.3F, 1.8F, 0F, 0F, 1F, 0F}};
        java.util.List<float[][]> outside = new java.util.ArrayList<float[][]>();
        float[][] inside = com.sva.vehicle.AnimParts.clipBox(tri, mn, mx, outside);
        float sum = inside == null ? 0F : com.sva.vehicle.AnimParts.area(inside);
        for (float[][] o : outside) {
            sum += com.sva.vehicle.AnimParts.area(o);
        }
        boolean within = inside != null;
        if (inside != null) {
            for (float[] p : inside) {
                within &= p[0] >= -1e-5F && p[0] <= 1.00001F && p[1] >= -1e-5F && p[1] <= 1.00001F && Math.abs(p[4] - 1F) < 1e-4F;
            }
        }
        check(within && near(sum, com.sva.vehicle.AnimParts.area(tri), 1e-4) && outside.size() >= 2,
                "anim parts: clipBox keeps the area (" + sum + " of " + com.sva.vehicle.AnimParts.area(tri) + ", " + outside.size() + " outside pieces)");
    }

    /**
     * Cockpit stage 1: the new dials' scales (turn rate centred, heading 0..360) and the six-pack / heli / drill tables
     * against the models' dial order (the attitude screen in the plane's and heli's panels, the plane's 5 round dials
     * sorted left -> right, top -> bottom); the trim by tier (SkinMath.trimTile / trimColour: seats, floor, inserts,
     * lining; belts and the rest untouched, the tiles in the jar); the cabin controls - one steering wheel per ground
     * vehicle, the heli's cyclic / collective / pedals, the plane's yoke / throttle / pedals - and their input easing.
     */
    private static void cockpitStage1() throws java.io.IOException {
        VehicleType p = VehicleType.PLANE;
        int turn = com.sva.vehicle.CockpitDials.TURN, heading = com.sva.vehicle.CockpitDials.HEADING;
        check(com.sva.vehicle.CockpitDials.min(turn, p) == -com.sva.vehicle.CockpitDials.max(turn, p, 0)
                && com.sva.vehicle.CockpitDials.max(turn, p, 0) == 40F && com.sva.vehicle.CockpitDials.max(heading, p, 0) == 360F
                && com.sva.vehicle.CockpitDials.min(heading, p) == 0F, "cockpit stage 1: turn rate centred +-40 deg/s (plane 1.8 deg/t), heading 0..360");
        int[] pt = com.sva.vehicle.CockpitDials.table(p);
        // stage C: the HSI (a named panel) took the lower centre heading dial's place
        check(pt.length == 4 && pt[0] == com.sva.vehicle.CockpitDials.SPEED && pt[1] == turn
                && pt[2] == com.sva.vehicle.CockpitDials.ALT && pt[3] == com.sva.vehicle.CockpitDials.VSPEED,
                "cockpit stage 1 / C: the plane's six-pack table speed / turn, [HSI], altitude / vertical speed");
        com.sva.vehicle.CockpitLayout pl = com.sva.vehicle.CockpitLayout.of(p);
        boolean cols = pl.dials.size() == 4 && Math.abs(pl.dials.get(0).c[2] - pl.dials.get(1).c[2]) < 0.01F && pl.dials.get(0).c[1] > pl.dials.get(1).c[1]
                && Math.abs(pl.dials.get(2).c[2] - pl.dials.get(3).c[2]) < 0.01F && pl.dials.get(2).c[1] > pl.dials.get(3).c[1];
        check(cols, "cockpit stage 1: the six-pack's dials come in columns (top before bottom)");
        int att = 0;
        for (VehicleType t : new VehicleType[] {VehicleType.HELI, VehicleType.PLANE}) {
            att += contains(com.sva.vehicle.CockpitDials.screenRoles(com.sva.vehicle.CockpitLayout.of(t).screens, true),
                    com.sva.vehicle.CockpitDials.SCREEN_ATTITUDE) ? 1 : 0;
        }
        com.sva.vehicle.CockpitLayout dl = com.sva.vehicle.CockpitLayout.of(VehicleType.DRILL);
        check(att == 2 && dl.dials.size() == 4 && dl.dials.get(3).c[2] > dl.dials.get(1).c[2]
                && com.sva.vehicle.CockpitDials.table(VehicleType.DRILL)[2] == com.sva.vehicle.CockpitDials.DRILL,
                "cockpit stage 1: heli and plane attitude screens; the drill console's dials right of the cluster (power, depth)");
        int lamps = 0;
        for (VehicleType t : new VehicleType[] {VehicleType.BUGGY, VehicleType.TRUCK, VehicleType.HELI}) {
            lamps += com.sva.vehicle.CockpitLayout.of(t).lamps.size() >= 6 ? 1 : 0;
        }
        check(lamps == 3, "cockpit stage 1: the warning lamp rows are found (buggy / truck 6, heli 8 annunciators)");
        // trim by tier
        String[] mats = {"fabric", "leather", "carpet", "trim", "headliner"};
        boolean differ = true;
        for (String m : mats) {
            java.util.Set<String> tiles = new java.util.HashSet<String>();
            for (int lv = SkinMath.WORK; lv <= SkinMath.SINGULAR; lv++) {
                tiles.add(SkinMath.trimTile(lv, m, SkinMath.tile(m, 1, 1F, false)));
            }
            differ &= tiles.size() >= 3;
        }
        check(differ && "leather".equals(SkinMath.trimTile(SkinMath.CIVIL, "fabric", "fabric"))
                && "alcantara".equals(SkinMath.trimTile(SkinMath.SILICON, "leather", "leather"))
                && "velvet".equals(SkinMath.trimTile(SkinMath.SINGULAR, "fabric", "fabric")) && "mat".equals(SkinMath.trimTile(SkinMath.WORK, "carpet", "carpet"))
                && "wood".equals(SkinMath.trimTile(SkinMath.CIVIL, "trim", "rubber")) && "carbon".equals(SkinMath.trimTile(SkinMath.SILICON, "carpet", "carpet"))
                && "fabric".equals(SkinMath.trimTile(-1, "fabric", "fabric")) && "fabric".equals(SkinMath.trimTile(SkinMath.SINGULAR, "belt", "fabric"))
                && SkinMath.trimColour(SkinMath.SINGULAR, "steel", 0x787C84) == 0x787C84 && SkinMath.trimColour(-1, "carpet", 0x202024) == 0x202024
                && SkinMath.trimColour(SkinMath.CIVIL, "leather", 0) == 0x6E3E24,
                "cockpit stage 1: trim by tier - I fabric / mats, II leather / carpet / wood, III alcantara / carbon, Singular velvet / dark glass; "
                        + "belts, steel and trim off untouched");
        StringBuilder missing = new StringBuilder();
        java.util.Set<String> known = new java.util.HashSet<String>(java.util.Arrays.asList(SkinMath.TILES));
        for (String m : mats) {
            for (int lv = SkinMath.WORK; lv <= SkinMath.SINGULAR; lv++) {
                String tl = SkinMath.trimTile(lv, m, "x");
                if (!known.contains(tl) || SelfTestSVA.class.getResource("/assets/siliconagevehicles/textures/models/" + tl + ".png") == null) {
                    missing.append(' ').append(tl);
                }
            }
        }
        check(missing.length() == 0, "cockpit stage 1: every trim tile is listed and in the jar" + missing);
        // the cabin controls
        int[] steering = new int[3];
        String[] ground = {"buggy", "truck", "drill"};
        for (int i = 0; i < ground.length; i++) {
            for (com.sva.vehicle.AnimParts.Def d : com.sva.vehicle.AnimParts.of(ground[i])) {
                if (d.kind == com.sva.vehicle.AnimParts.CONTROL && d.in1 == com.sva.vehicle.AnimParts.STEER) {
                    steering[i]++;
                }
            }
        }
        java.util.Set<String> heli = new java.util.HashSet<String>(), plane = new java.util.HashSet<String>();
        boolean axes = true;
        for (com.sva.vehicle.AnimParts.Def d : com.sva.vehicle.AnimParts.all()) {
            if (d.kind != com.sva.vehicle.AnimParts.CONTROL) {
                continue;
            }
            if ("heli".equals(d.model)) {
                heli.add(d.name);
            } else if ("plane".equals(d.model)) {
                plane.add(d.name);
            }
            for (float[] a : new float[][] {d.axis1, d.axis2}) {
                axes &= a == null || Math.abs(a[0] * a[0] + a[1] * a[1] + a[2] * a[2] - 1F) < 0.01F;
            }
            axes &= (d.in1 >= 0) == (d.axis1 != null) && (d.inMove >= 0) == (d.move != null);
        }
        check(steering[0] == 1 && steering[1] == 1 && steering[2] == 1 && heli.containsAll(java.util.Arrays.asList("cyclic", "collective", "pedalL", "pedalR"))
                && plane.containsAll(java.util.Arrays.asList("yoke", "throttle", "pedalL", "pedalR")) && axes,
                "cockpit stage 1: one steering wheel each (buggy / truck / drill), heli cyclic / collective / pedals, plane yoke / throttle / pedals, unit axes");
        com.sva.vehicle.AnimMath.State s = new com.sva.vehicle.AnimMath.State();
        float maxP = 0;
        for (int i = 0; i < 40; i++) {
            com.sva.vehicle.AnimMath.controls(s, 3F, -1F, 0.5F, 0F, 1F, true);
            maxP = Math.max(maxP, s.ctlPitch);
        }
        boolean eased = maxP <= 1F && s.ctlPitch > 0.99F && s.ctlRoll < -0.99F && near(s.ctlYaw, 0.5, 1e-3) && s.ctlThrottle > 0.99F
                && near(s.control(com.sva.vehicle.AnimParts.ROLL, 1F), -1, 0.01);
        com.sva.vehicle.AnimMath.controls(s, 1F, 1F, 1F, 1F, 1F, false);
        check(eased && s.ctlPitch == 0F && s.ctlThrottle == -1F && s.control(com.sva.vehicle.AnimParts.STEER, 1F) == 0F,
                "cockpit stage 1: control inputs ease to their clamped targets (throttle 0..1 -> -1..1), animations off = at rest");
    }

    /** Gear retract / extend, surface and steering deflection, blink timing, brake detection, config off = at rest. */
    private static void animLogic() {
        // gear: retracts high and fast, stays in the band between, extends low or slow; the travel takes 1 s
        check(com.sva.vehicle.AnimMath.gearTarget(false, 10, 0.8) && com.sva.vehicle.AnimMath.gearTarget(true, 5, 0.8)
                && !com.sva.vehicle.AnimMath.gearTarget(false, 5, 0.8) && !com.sva.vehicle.AnimMath.gearTarget(true, 3, 0.8)
                && !com.sva.vehicle.AnimMath.gearTarget(true, 10, 0.2), "anim: gear retracts > 6 blocks and fast, extends < 4 or slow (hysteresis)");
        com.sva.vehicle.AnimMath.State s = new com.sva.vehicle.AnimMath.State();
        s.height = 10;
        int up = -1;
        for (int i = 0; i < 40; i++) {
            com.sva.vehicle.AnimMath.tick(s, 0.8F, 0F, 0F, 2F, 3F, false, Float.NaN, false, true, true, false, false, Double.NaN, true);
            if (up < 0 && s.gear >= 1F) {
                up = i + 1;
            }
        }
        s.height = 2;
        int down = -1;
        for (int i = 0; i < 40; i++) {
            com.sva.vehicle.AnimMath.tick(s, 0.8F, 0F, 0F, 2F, 3F, true, Float.NaN, false, true, true, false, false, Double.NaN, true);
            if (down < 0 && s.gear <= 0F) {
                down = i + 1;
            }
        }
        check(Math.abs(up - com.sva.vehicle.AnimMath.GEAR_TICKS) <= 1 && Math.abs(down - com.sva.vehicle.AnimMath.GEAR_TICKS) <= 1,
                "anim: gear travel up " + up + " / down " + down + " ticks (" + com.sva.vehicle.AnimMath.GEAR_TICKS + ")");
        // control surfaces: full turn -> full deflection, clamped at 20 deg, signs; elevator from the climb
        float M = com.sva.vehicle.AnimMath.MAX_SURFACE;
        check(com.sva.vehicle.AnimMath.aileronTarget(1.8F, 1.8F) == M && com.sva.vehicle.AnimMath.aileronTarget(-50F, 1.8F) == -M
                && com.sva.vehicle.AnimMath.aileronTarget(0F, 1.8F) == 0F && near(com.sva.vehicle.AnimMath.aileronTarget(0.9F, 1.8F), M / 2, 1e-4)
                && com.sva.vehicle.AnimMath.rudderTarget(99F, 1F) == M && com.sva.vehicle.AnimMath.elevatorTarget(0.5F, 1F) > 0F
                && com.sva.vehicle.AnimMath.elevatorTarget(-2F, 1F) == -M && com.sva.vehicle.AnimMath.elevatorTarget(0F, 1F) == 0F,
                "anim: control surface mapping and clamping (+-" + M + " deg)");
        // smoothing reaches the target without overshoot
        s = new com.sva.vehicle.AnimMath.State();
        float maxA = 0;
        for (int i = 0; i < 40; i++) {
            com.sva.vehicle.AnimMath.tick(s, 1F, 0F, 5F, 1.8F, 3F, false, Float.NaN, false, false, true, false, false, Double.NaN, true);
            maxA = Math.max(maxA, s.aileron);
        }
        check(maxA <= M && s.aileron > M * 0.99F && s.rudder > 0 && s.gear == 0F, "anim: ailerons ease to " + s.aileron + " (no overshoot, max " + maxA + ")");
        // steering: own keys -> +-25, else from the yaw rate (reversing flips), clamped
        check(com.sva.vehicle.AnimMath.steerTarget(1F, 0F, 4.5F, 0F) == com.sva.vehicle.AnimMath.MAX_STEER
                && com.sva.vehicle.AnimMath.steerTarget(-1F, 0F, 4.5F, 0F) == -com.sva.vehicle.AnimMath.MAX_STEER
                && com.sva.vehicle.AnimMath.steerTarget(Float.NaN, 9F, 4.5F, 0.3F) == com.sva.vehicle.AnimMath.MAX_STEER
                && com.sva.vehicle.AnimMath.steerTarget(Float.NaN, 2.25F, 4.5F, -0.3F) < 0, "anim: steering mapping (keys / yaw rate, reverse)");
        // wheels: rolling forward turns them negative about +z (top forward); a full turn per 2 pi r
        check(com.sva.vehicle.AnimMath.wheelAngle(0.1, 0.5F) < 0 && near(com.sva.vehicle.AnimMath.wheelAngle(Math.PI * 0.5 * 0.5, 0.5F), -90, 1e-3),
                "anim: wheel angle from the distance rolled");
        // blink: the strobe flashes 2 of every 20 ticks; beacons peak at 1, rest at 0.15; phases differ per vehicle
        int on = 0;
        for (long t = 0; t < 200; t++) {
            on += com.sva.vehicle.AnimMath.strobe(t, 7) ? 1 : 0;
        }
        float bmax = 0, bmin = 2;
        for (int t = 0; t < 40; t++) {
            float b = com.sva.vehicle.AnimMath.beacon(t, 0, 20);
            bmax = Math.max(bmax, b);
            bmin = Math.min(bmin, b);
        }
        check(on == 20 && near(bmax, 1, 1e-4) && near(bmin, 0.15, 1e-3)
                && com.sva.vehicle.AnimMath.phase(1) != com.sva.vehicle.AnimMath.phase(2)
                && com.sva.vehicle.AnimMath.phase(-5) >= 0, "anim: blink timing (strobe " + on + "/200 ticks, beacon " + bmin + ".." + bmax + ")");
        // brake lights of someone else's vehicle: slowing down while moving, not reversing / cruising
        check(com.sva.vehicle.AnimMath.braking(0.3F, 0.25F) && !com.sva.vehicle.AnimMath.braking(0.3F, 0.3F)
                && !com.sva.vehicle.AnimMath.braking(0.01F, 0F) && !com.sva.vehicle.AnimMath.braking(0.3F, -0.25F), "anim: braking detection");
        // config off: everything at rest
        s = new com.sva.vehicle.AnimMath.State();
        s.height = 10;
        for (int i = 0; i < 30; i++) {
            com.sva.vehicle.AnimMath.tick(s, 0.8F, 0.3F, 3F, 1.8F, 3F, true, 1F, false, true, true, true, true, 0.5, false);
        }
        check(s.roll == 0 && s.steer == 0 && s.aileron == 0 && s.gear == 0 && s.bob == 0 && s.lean == 0 && s.tilt == 0.5,
                "anim: vehicles.animations = false keeps every part at rest");
        // the bump spring settles back to 0
        s = new com.sva.vehicle.AnimMath.State();
        com.sva.vehicle.AnimMath.tick(s, 0.2F, 0F, 0F, 1F, 3F, true, Float.NaN, false, false, false, false, true, Double.NaN, true);
        com.sva.vehicle.AnimMath.tick(s, 0.2F, -0.4F, 0F, 1F, 3F, true, Float.NaN, false, false, false, false, true, Double.NaN, true);
        com.sva.vehicle.AnimMath.tick(s, 0.2F, 0F, 0F, 1F, 3F, true, Float.NaN, false, false, false, false, true, Double.NaN, true);
        float peak = Math.abs(s.bob);
        for (int i = 0; i < 60; i++) {
            com.sva.vehicle.AnimMath.tick(s, 0.2F, 0F, 0F, 1F, 3F, true, Float.NaN, false, false, false, false, true, Double.NaN, true);
        }
        check(peak > 0.005F && Math.abs(s.bob) < 0.002F && peak <= com.sva.vehicle.AnimMath.BOB_MAX, "anim: suspension bob on a landing (peak " + peak
                + ") settles");
    }

    /**
     * The nav light table: every aircraft has red on the left (z < 0), green on the right, mirrored, a strobe and a beacon
     * under its middle; the truck and the drill amber beacons and markers; every vehicle with front lamps a headlight;
     * every light on (or right at) its model.
     */
    private static void lightTable() throws java.io.IOException {
        String[] air = {"heli", "plane", "jet", "tilt", "shuttle", "ship"};
        for (String m : air) {
            float red = 0, green = 0;
            int strobe = 0, beacon = 0;
            float[] box = objBox(m);
            boolean beaconLow = true;
            for (com.sva.vehicle.VehicleLights.Light l : com.sva.vehicle.VehicleLights.of(m)) {
                if (l.kind == com.sva.vehicle.VehicleLights.RED) {
                    red = l.z;
                } else if (l.kind == com.sva.vehicle.VehicleLights.GREEN) {
                    green = l.z;
                } else if (l.kind == com.sva.vehicle.VehicleLights.STROBE) {
                    strobe++;
                } else if (l.kind == com.sva.vehicle.VehicleLights.BEACON) {
                    beacon++;
                    beaconLow &= l.y < (box[1] + box[4]) / 2;
                }
            }
            check(red < 0 && green > 0 && near(-red, green, 1e-4) && strobe >= 1 && beacon >= 1 && beaconLow,
                    "lights: " + m + " red left z " + red + ", green right z " + green + ", strobe " + strobe + ", beacon underneath " + beacon);
        }
        for (String m : new String[] {"truck", "drill"}) {
            int amber = 0, marker = 0;
            for (com.sva.vehicle.VehicleLights.Light l : com.sva.vehicle.VehicleLights.of(m)) {
                amber += l.kind == com.sva.vehicle.VehicleLights.AMBER ? 1 : 0;
                marker += l.kind == com.sva.vehicle.VehicleLights.MARKER ? 1 : 0;
            }
            check(amber >= 1 && marker >= 4 && marker % 2 == 0, "lights: " + m + " amber beacons " + amber + ", side markers " + marker);
        }
        boolean heads = true;
        for (String m : new String[] {"buggy", "hover", "truck", "drill", "heli", "plane", "jet", "tilt", "shuttle", "ship"}) {
            heads &= com.sva.vehicle.VehicleLights.hasHead(m);
        }
        check(heads, "lights: every vehicle has a headlight / landing light");
        int bad = 0, all = 0;
        String where = "";
        for (String m : com.sva.vehicle.VehicleLights.models()) {
            float[] b = objBox(m);
            for (com.sva.vehicle.VehicleLights.Light l : com.sva.vehicle.VehicleLights.of(m)) {
                all++;
                float e = 0.15F;
                if (l.x < b[0] - e || l.x > b[3] + e || l.y < b[1] - e || l.y > b[4] + e || l.z < b[2] - e || l.z > b[5] + e) {
                    bad++;
                    where += " " + m + "(" + l.x + "," + l.y + "," + l.z + ")";
                }
            }
        }
        check(bad == 0 && all >= 60, "lights: all " + all + " lights sit on their models" + where);
        check(com.sva.vehicle.VehicleLights.colour(com.sva.vehicle.VehicleLights.RED) != com.sva.vehicle.VehicleLights.colour(com.sva.vehicle.VehicleLights.GREEN)
                && com.sva.vehicle.VehicleLights.colour(com.sva.vehicle.VehicleLights.AMBER) == 0xFF9A18, "lights: colours");
    }

    /** The model's bounding box {x0, y0, z0, x1, y1, z1}. */
    private static float[] objBox(String model) throws java.io.IOException {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Object[] o : objTris(model)) {
            float[] t = (float[]) o[1];
            for (int k = 0; k < 9; k++) {
                b[k % 3] = Math.min(b[k % 3], t[k]);
                b[3 + k % 3] = Math.max(b[3 + k % 3], t[k]);
            }
        }
        return b;
    }

    // ---- cockpit stage 2: ★11 reactive lamps + startup, ★8 night lighting (vehicle/CockpitLamps)

    private static void cockpitStage2() throws java.io.IOException {
        // every type's lamp table has exactly one role per lamp of its model; only the plane has gear lamps
        StringBuilder found = new StringBuilder();
        boolean tables = true, ok = true, floors = true;
        for (VehicleType t : VehicleType.values()) {
            com.sva.vehicle.CockpitLayout l = com.sva.vehicle.CockpitLayout.of(t);
            int[] roles = com.sva.vehicle.CockpitLamps.table(t);
            found.append(t.key).append(' ').append(l.lamps.size()).append(' ');
            tables &= roles.length == l.lamps.size();
            for (int r : roles) {
                tables &= r >= 0 && r < com.sva.vehicle.CockpitLamps.KEYS.length;
            }
            ok &= l.okLamps.size() == (t == VehicleType.PLANE ? 3 : 0);
            floors &= (l.floor != null) == (t != VehicleType.HOVER && t != VehicleType.JET);
        }
        check(tables, "cockpit stage 2: every type's lamp role table covers its model's lamps exactly (" + found.toString().trim() + ")");
        check(ok, "cockpit stage 2: the plane's three green gear lamps are found, no other model has 'ok' lamps that small");
        check(floors, "cockpit stage 2: a cabin floor box for the floor strip in every model with a carpet (all but the hoverbike, the jet)");
        // rows: the buggy's 6 lamps first, left -> right on one height; the drill's 3 mode lamps first (the row above)
        java.util.List<com.sva.vehicle.CockpitLayout.Lamp> bl = com.sva.vehicle.CockpitLayout.of(VehicleType.BUGGY).lamps;
        boolean row = bl.size() == 7;
        for (int i = 1; row && i < 6; i++) {
            row = Math.abs(bl.get(i).c[1] - bl.get(0).c[1]) < 0.01F && bl.get(i).c[2] > bl.get(i - 1).c[2];
        }
        java.util.List<com.sva.vehicle.CockpitLayout.Lamp> dl = com.sva.vehicle.CockpitLayout.of(VehicleType.DRILL).lamps;
        check(row && dl.size() == 9 && dl.get(0).c[1] > dl.get(3).c[1] + 0.05F && dl.get(0).c[2] < dl.get(2).c[2],
                "cockpit stage 2: lamps sorted in rows top -> bottom, left -> right (buggy row of 6, drill mode lamps first)");
        int[] heli = com.sva.vehicle.CockpitLamps.table(VehicleType.HELI);
        check(heli[1] == com.sva.vehicle.CockpitLamps.AUTOROT && heli[8] == com.sva.vehicle.CockpitLamps.DOOR
                && com.sva.vehicle.CockpitLamps.table(VehicleType.BUGGY)[0] == com.sva.vehicle.CockpitLamps.TURN_L
                && com.sva.vehicle.CockpitLamps.table(VehicleType.BUGGY)[5] == com.sva.vehicle.CockpitLamps.TURN_R
                && com.sva.vehicle.CockpitLamps.colour(com.sva.vehicle.CockpitLamps.HEAD) == com.sva.vehicle.CockpitLamps.BLUE
                && com.sva.vehicle.CockpitLamps.colour(com.sva.vehicle.CockpitLamps.WEAR) == com.sva.vehicle.CockpitLamps.AMBER
                && com.sva.vehicle.CockpitLamps.colour(com.sva.vehicle.CockpitLamps.GEN) == com.sva.vehicle.CockpitLamps.GREEN,
                "cockpit stage 2: heli annunciators AUTOROT..DOOR, car row turn L..turn R, colours (lights blue, wear amber, gen green)");
        // the startup sequence's timing
        check(com.sva.vehicle.CockpitLamps.phase(-1F) == com.sva.vehicle.CockpitLamps.PH_NORMAL
                && com.sva.vehicle.CockpitLamps.phase(Float.NaN) == com.sva.vehicle.CockpitLamps.PH_NORMAL
                && com.sva.vehicle.CockpitLamps.phase(0F) == com.sva.vehicle.CockpitLamps.PH_DARK
                && com.sva.vehicle.CockpitLamps.phase(0.29F) == com.sva.vehicle.CockpitLamps.PH_DARK
                && com.sva.vehicle.CockpitLamps.phase(0.3F) == com.sva.vehicle.CockpitLamps.PH_TEST
                && com.sva.vehicle.CockpitLamps.phase(0.79F) == com.sva.vehicle.CockpitLamps.PH_TEST
                && com.sva.vehicle.CockpitLamps.phase(0.8F) == com.sva.vehicle.CockpitLamps.PH_LOGO
                && com.sva.vehicle.CockpitLamps.phase(1.19F) == com.sva.vehicle.CockpitLamps.PH_LOGO
                && com.sva.vehicle.CockpitLamps.phase(1.2F) == com.sva.vehicle.CockpitLamps.PH_SWEEP
                && com.sva.vehicle.CockpitLamps.phase(1.79F) == com.sva.vehicle.CockpitLamps.PH_SWEEP
                && com.sva.vehicle.CockpitLamps.phase(1.8F) == com.sva.vehicle.CockpitLamps.PH_NORMAL,
                "cockpit stage 2: startup 0-0.3 s dark, 0.3-0.8 lamp test, 0.8-1.2 SA logo, 1.2-1.8 needle sweep, then normal");
        check(com.sva.vehicle.CockpitLamps.sweep(1.2F) == 0F && near(com.sva.vehicle.CockpitLamps.sweep(1.35F), 0.5, 1e-4)
                && near(com.sva.vehicle.CockpitLamps.sweep(1.5F), 1, 1e-4) && near(com.sva.vehicle.CockpitLamps.sweep(1.65F), 0.5, 1e-4)
                && com.sva.vehicle.CockpitLamps.sweep(1.8F) == 0F && com.sva.vehicle.CockpitLamps.sweep(0.5F) == 0F,
                "cockpit stage 2: the needles sweep 0 -> max (at 1.5 s) -> 0");
        // lamp rules
        check(com.sva.vehicle.CockpitLamps.vortex(true, true, -0.3F, 0.1F) && !com.sva.vehicle.CockpitLamps.vortex(true, true, -0.3F, 0.2F)
                && !com.sva.vehicle.CockpitLamps.vortex(true, true, -0.2F, 0.05F) && !com.sva.vehicle.CockpitLamps.vortex(false, true, -0.5F, 0F)
                && !com.sva.vehicle.CockpitLamps.vortex(true, false, -0.5F, 0F),
                "cockpit stage 2: vortex ring lamp - a rotorcraft in the air sinking > 5 b/s under 3 b/s forward");
        com.sva.vehicle.CockpitLamps.Snapshot sn = new com.sva.vehicle.CockpitLamps.Snapshot();
        sn.powered = true;
        sn.charge = 0.05F;
        int low = com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.LOW_CHARGE, sn);
        int master = com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.MASTER, sn);
        sn.charge = 0.5F;
        int lowOff = com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.LOW_CHARGE, sn);
        int gen = com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.GEN, sn);
        sn.powered = false;
        sn.charge = 0F;
        int genOff = com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.GEN, sn);
        int lowDead = com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.LOW_CHARGE, sn);
        check(low == com.sva.vehicle.CockpitLamps.ON && master == com.sva.vehicle.CockpitLamps.BLINK && lowOff == com.sva.vehicle.CockpitLamps.OFF
                && gen == com.sva.vehicle.CockpitLamps.ON && genOff == com.sva.vehicle.CockpitLamps.OFF && lowDead == com.sva.vehicle.CockpitLamps.OFF,
                "cockpit stage 2: low charge under 10% (amber, master blinks), generator lamp with power, everything dark without");
        sn = new com.sva.vehicle.CockpitLamps.Snapshot();
        sn.powered = true;
        sn.steer = -10F;
        sn.heat = 85F;
        sn.hull = 0.2F;
        sn.drillMode = 1;
        sn.onGround = true;
        check(com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.TURN_L, sn) == com.sva.vehicle.CockpitLamps.BLINK
                && com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.TURN_R, sn) == com.sva.vehicle.CockpitLamps.OFF
                && com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.OVERHEAT, sn) == com.sva.vehicle.CockpitLamps.BLINK
                && com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.WEAR, sn) == com.sva.vehicle.CockpitLamps.ON
                && com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.HANDBRAKE, sn) == com.sva.vehicle.CockpitLamps.ON
                && com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.MODE1, sn) == com.sva.vehicle.CockpitLamps.ON
                && com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.MODE0, sn) == com.sva.vehicle.CockpitLamps.OFF
                && com.sva.vehicle.CockpitLamps.state(com.sva.vehicle.CockpitLamps.DOOR, sn) == com.sva.vehicle.CockpitLamps.OFF
                && com.sva.vehicle.CockpitLamps.lit(com.sva.vehicle.CockpitLamps.BLINK, 1000) && !com.sva.vehicle.CockpitLamps.lit(com.sva.vehicle.CockpitLamps.BLINK, 1300)
                && com.sva.vehicle.CockpitLamps.lit(com.sva.vehicle.CockpitLamps.ON, 1300),
                "cockpit stage 2: turning left blinks the left lamp, hot (>80) blinks, worn hull (>75% wear) amber, parked = handbrake, drill mode lamp");
        check(com.sva.vehicle.CockpitLamps.gear(0F, false) == com.sva.vehicle.CockpitLamps.GREEN
                && com.sva.vehicle.CockpitLamps.gear(0F, true) == com.sva.vehicle.CockpitLamps.RED
                && com.sva.vehicle.CockpitLamps.gear(0.5F, true) == com.sva.vehicle.CockpitLamps.RED
                && com.sva.vehicle.CockpitLamps.gear(1F, false) == com.sva.vehicle.CockpitLamps.RED
                && com.sva.vehicle.CockpitLamps.gear(1F, true) == 0,
                "cockpit stage 2: gear lamps green down, red while moving, off up");
        // ★8 night lighting
        check(com.sva.vehicle.CockpitLamps.tierGlow(com.sva.vehicle.SkinMath.livery(VehicleType.BUGGY.tier)) == 0xFFBE3C
                && com.sva.vehicle.CockpitLamps.tierGlow(com.sva.vehicle.SkinMath.livery(VehicleType.HELI.tier)) == 0xFFE6AA
                && com.sva.vehicle.CockpitLamps.tierGlow(com.sva.vehicle.SkinMath.livery(VehicleType.HOVER.tier)) == 0x5AD2FF
                && com.sva.vehicle.CockpitLamps.tierGlow(com.sva.vehicle.SkinMath.livery(VehicleType.SHIP.tier)) == 0xC878FF,
                "cockpit stage 2: night glow by tier - I amber, II warm white, III cyan, Singular purple");
        check(com.sva.vehicle.CockpitLamps.night(0F, true, 0.8F) == 0F && near(com.sva.vehicle.CockpitLamps.night(1F, true, 0.8F), 0.8, 1e-5)
                && com.sva.vehicle.CockpitLamps.night(1F, false, 0.8F) == 0F && near(com.sva.vehicle.CockpitLamps.night(0.275F, true, 1F), 0.5, 1e-4)
                && com.sva.vehicle.CockpitLamps.night(1F, true, 0F) == 0F && com.sva.vehicle.CockpitLamps.mix(0x000000, 0xFFFFFF, 0.5F) == 0x808080,
                "cockpit stage 2: the night glow comes on with the headlights' darkness, ramps to the configured level, off without power");
        int[] dr = com.sva.vehicle.CockpitDials.screenRoles(com.sva.vehicle.CockpitLayout.of(VehicleType.DRILL).screens, false, true);
        int mi = -1;
        for (int i = 0; i < dr.length; i++) {
            if (dr[i] == com.sva.vehicle.CockpitDials.SCREEN_MINING) {
                mi = i;
            }
        }
        check(mi >= 0 && com.sva.vehicle.CockpitLayout.of(VehicleType.DRILL).screens.get(mi).w > 0.18F
                && !contains(com.sva.vehicle.CockpitDials.screenRoles(com.sva.vehicle.CockpitLayout.of(VehicleType.TRUCK).screens, false, false),
                        com.sva.vehicle.CockpitDials.SCREEN_MINING),
                "cockpit stage 2: the drill console (0.20 wide) gets the «ДОБЫЧА» page, nobody else");
        // every label / event line in both languages
        boolean langOk = true;
        String[] extra = {"sva.cockpit.lamp.gear", "sva.cockpit.ev.cool", "sva.cockpit.ev.boost", "sva.cockpit.ev.autopilot", "sva.cockpit.ev.cargoFull",
                "sva.cockpit.range", "sva.cockpit.mining", "sva.cockpit.mining.mode", "sva.cockpit.mining.trip", "sva.cockpit.mining.bunker",
                "sva.cockpit.mining.head", "sva.cockpit.mining.headForever", "sva.cockpit.mining.noHead"};
        for (String file : new String[] {"/assets/siliconagevehicles/lang/ru_RU.lang", "/assets/siliconagevehicles/lang/en_US.lang"}) {
            java.util.Properties props = new java.util.Properties();
            java.io.InputStream in = SelfTestSVA.class.getResourceAsStream(file);
            if (in == null) {
                langOk = false;
                continue;
            }
            try {
                props.load(new java.io.InputStreamReader(in, "UTF-8"));
            } finally {
                in.close();
            }
            for (String k : com.sva.vehicle.CockpitLamps.KEYS) {
                langOk &= k.length() == 0 || props.containsKey("sva.cockpit.lamp." + k);
            }
            for (String k : extra) {
                langOk &= props.containsKey(k);
            }
        }
        check(langOk, "cockpit stage 2: every lamp label and event line is in ru_RU and en_US");
    }
}
