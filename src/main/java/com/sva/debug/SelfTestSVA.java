package com.sva.debug;

import com.sc.energy.Tier;
import com.sc.init.ModItems;
import com.sc.util.Material;
import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.item.ComponentType;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.HoverMath;
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
}
