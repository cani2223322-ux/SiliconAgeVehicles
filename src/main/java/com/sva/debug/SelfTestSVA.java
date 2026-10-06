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

    private static void check(boolean ok, String name) {
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
                && h.seatOffsets.length == 2 && h.modelScale == 1.5F, "heli: HV, 3M EU, 2 seats, 9 cargo, x2.5 sprint, model x1.5");
        check(p.tier == Tier.HV && p.buffer == 4000000 && p.seats == 3 && p.cargoSlots == 9 && near(p.topSpeed, 1.12, 1e-9)
                && p.seatOffsets.length == 3 && p.modelScale == 2.3F, "plane: HV, 4M EU, 3 seats, 9 cargo, x4 sprint, model x2.3");
        check(h.platformSize == PlatformSize.P5x5 && p.platformSize == PlatformSize.P5x5, "aircraft: 5x5 charging platform");
        check(h.gases.capacity(com.sc.util.ArmorGasSC.Gas.HELIUM) == 1000 && h.gases.coolant == com.sc.util.ArmorGasSC.Gas.HELIUM
                && h.gases.liftGas == null && p.gases.coolant == com.sc.util.ArmorGasSC.Gas.HELIUM, "aircraft: helium tank 1000 mB for cooling, no lift gas");
        // a seated player fits: cushion top (seatY + 0.25) and seats shoulder-wide / staggered
        check(near(h.seatY + 0.25, 0.66 * 1.5, 0.02) && Math.abs(h.seatOffsets[0][1] - h.seatOffsets[1][1]) >= 0.75
                && near(p.seatY + 0.25, 0.64 * 2.3, 0.02) && p.seatOffsets[0][0] - p.seatOffsets[1][0] >= 1.0,
                "aircraft: seats on the model's cushions (heli 0.75 apart, plane staggered 1.1+)");
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
        check(near(cushion, 0.64 * j.modelScale, 0.02) && 1.036 * j.modelScale - cushion >= 0.95 && 6.0 * j.modelScale < 16,
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
        check(near(cushion, 0.53 * t.modelScale, 0.02) && 1.03 * t.modelScale - cushion >= 1.05
                && Math.abs(t.seatOffsets[0][1] - t.seatOffsets[1][1]) >= 0.55, "tilt: model x" + t.modelScale + " - cushion " + cushion
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
        check(near(cs, 0.85 * s.modelScale, 0.02) && 1.71 * s.modelScale - cs >= 1.05 && Math.abs(s.seatOffsets[0][1] - s.seatOffsets[1][1]) >= 0.6
                && Math.abs(s.seatOffsets[2][0] - s.seatOffsets[4][0]) >= 0.75 && Math.abs(s.seatOffsets[2][1] - s.seatOffsets[3][1]) >= 0.75,
                "shuttle: model x" + s.modelScale + " - cushion " + cs + ", ceiling " + Math.round((1.71 * s.modelScale - cs) * 100) / 100.0
                        + " over it, rows 0.78 apart, seats shoulder-wide");
        check(near(cp, 0.57 * sh.modelScale, 0.02) && 1.10 * sh.modelScale - cp >= 1.0 && Math.abs(sh.seatOffsets[0][1] - sh.seatOffsets[1][1]) >= 0.75
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
}
