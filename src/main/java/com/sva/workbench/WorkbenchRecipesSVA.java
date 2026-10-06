package com.sva.workbench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sc.energy.Tier;
import com.sc.init.ModBlocks;
import com.sc.init.ModItems;
import com.sc.util.Material;
import com.sc.util.SiliconMaterial;
import com.sva.init.ModContentSVA;
import com.sva.item.ComponentType;
import com.sva.vehicle.VehicleType;
import com.sva.workbench.WorkbenchRecipeSVA.Category;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Every Engineering Workbench recipe (plan §3a / §4.1 / §4.2), only Silicon Age + vanilla items. The list order
 * is the GUI's and the sync index - register in the same order on both sides (init does).
 * A new vehicle = one vehicle(...) line.
 */
public final class WorkbenchRecipesSVA {

    private static final List<WorkbenchRecipeSVA> ALL = new ArrayList<WorkbenchRecipeSVA>();

    private WorkbenchRecipesSVA() {
    }

    public static List<WorkbenchRecipeSVA> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static WorkbenchRecipeSVA byIndex(int i) {
        return i >= 0 && i < ALL.size() ? ALL.get(i) : null;
    }

    public static int indexOf(WorkbenchRecipeSVA r) {
        return ALL.indexOf(r);
    }

    public static WorkbenchRecipeSVA byId(String id) {
        for (WorkbenchRecipeSVA r : ALL) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** The recipe the grid holds, or null. */
    public static WorkbenchRecipeSVA find(ItemStack[] grid) {
        for (WorkbenchRecipeSVA r : ALL) {
            if (r.match(grid) != null) {
                return r;
            }
        }
        return null;
    }

    public static WorkbenchRecipeSVA add(WorkbenchRecipeSVA r) {
        if (byId(r.id) != null) {
            throw new IllegalStateException("duplicate workbench recipe " + r.id);
        }
        ALL.add(r);
        return r;
    }

    /** An instant component recipe. */
    public static WorkbenchRecipeSVA component(ComponentType t, int count, Object... pattern) {
        return add(new WorkbenchRecipeSVA(t.key, Category.COMPONENTS, ModContentSVA.component.stackOf(t, count), t.tier, 0, 0, pattern));
    }

    /** A vehicle: EU and ticks of assembly. */
    public static WorkbenchRecipeSVA vehicle(VehicleType t, long eu, int ticks, Object... pattern) {
        return add(new WorkbenchRecipeSVA(t.key, Category.VEHICLES, new ItemStack(ModContentSVA.vehicle, 1, t.ordinal()), t.tier, eu, ticks, pattern));
    }

    public static WorkbenchRecipeSVA block(String id, ItemStack out, Tier tier, Object... pattern) {
        return add(new WorkbenchRecipeSVA(id, Category.BLOCKS, out, tier, 0, 0, pattern));
    }

    /** A charging station module (instant, category «Модули»). */
    public static WorkbenchRecipeSVA module(String id, ItemStack out, Tier tier, Object... pattern) {
        return add(new WorkbenchRecipeSVA(id, Category.MODULES, out, tier, 0, 0, pattern));
    }

    // ------------------------------------------------------------------ the recipes

    private static ItemStack c(String core) {
        Item i = ModItems.component(core);
        if (i == null) {
            throw new IllegalStateException("Silicon Age component missing: " + core);
        }
        return new ItemStack(i);
    }

    private static ItemStack ingot(Material m) {
        return ModItems.ingot.stackOf(m);
    }

    private static ItemStack si(SiliconMaterial m) {
        return ModItems.siliconMaterial.stackOf(m);
    }

    /** Core battery by tier index (lv 0, mv 1 Li battery, hv 2 crystal, ev 3 quartz capacitor, qv 4 quantum cell, xv 5 exo core, sv 6 singular core). */
    private static ItemStack battery(int idx) {
        return new ItemStack(ModItems.battery, 1, idx);
    }

    private static ItemStack part(ComponentType t) {
        return ModContentSVA.component.stackOf(t, 1);
    }

    public static void register() {
        ALL.clear();
        ItemStack steel = ingot(Material.STEEL), iron = new ItemStack(Items.iron_ingot), al = ingot(Material.ALUMINIUM),
                nd = ingot(Material.NEODYMIUM);
        ItemStack casing = c("steelCasing"), coil = c("copperCoil"), alFrame = c("alFrame"), tiFrame = c("tiFrame"), alFoil = new ItemStack(ModItems.alFoil),
                polymer = c("polymerPlate"), tiPlate = c("tiPlate"), wTi = c("wTiPlate"), nbPlate = c("nb3SnPlate"), nbCoil = c("nb3SnCoil"),
                heLoop = c("heLoopModule"), blade = c("turbineBladeTitanium"), chamber = c("combustionChamber"), tiCasing = c("tiCasing"),
                quartzChamber = c("quartzChamber"), sensor = c("sensor"), cellMV = c("energyCellMV"), cellHV = c("energyCellHV");
        ItemStack rubber = new ItemStack(ModItems.rubber), hotRubber = new ItemStack(ModItems.rubberHeatResist);
        ItemStack controller = si(SiliconMaterial.CONTROLLER), transistor = si(SiliconMaterial.TRANSISTOR), memory = si(SiliconMaterial.MEMORY_CHIP);
        ItemStack gravCoil = new ItemStack(ModBlocks.gravityCoil), clot = new ItemStack(ModItems.singularClot);

        // ---- components (plan §4.1): 3x3, instant, anywhere in the grid
        component(ComponentType.WHEEL, 4, "IRI", "RSR", "IRI", 'I', iron, 'R', rubber, 'S', steel);
        component(ComponentType.TRACK, 1, "RRR", "XXX", "WWW", 'R', hotRubber, 'X', casing, 'W', part(ComponentType.WHEEL));
        component(ComponentType.MOTOR_I, 1, "KNK", "TXT", "KNK", 'K', coil, 'N', nd, 'T', transistor, 'X', casing);
        component(ComponentType.MOTOR_II, 1, " C ", "NMN", " A ", 'C', controller, 'N', nbCoil, 'M', part(ComponentType.MOTOR_I), 'A', alFrame);
        component(ComponentType.TURBO_ENGINE, 1, "BHB", "KTK", "BCB", 'B', blade, 'H', chamber, 'K', coil, 'T', tiCasing, 'C', controller);
        component(ComponentType.ANTIGRAV_ENGINE, 1, "PCP", "NQN", "PHP", 'P', nbPlate, 'C', controller, 'N', nbCoil, 'Q', battery(4), 'H', heLoop);
        component(ComponentType.SINGULAR_ENGINE, 1, "GPG", "CSC", "GAG", 'G', gravCoil, 'P', nbPlate, 'C', clot, 'S', battery(6),
                'A', part(ComponentType.ANTIGRAV_ENGINE));
        component(ComponentType.ROTOR, 1, "BSB", "SMS", "BSB", 'B', blade, 'S', steel, 'M', part(ComponentType.MOTOR_I));
        component(ComponentType.WING, 1, "AAA", "FFF", "PPP", 'A', alFrame, 'F', alFoil, 'P', polymer);
        component(ComponentType.FLIGHT_COMPUTER, 1, "GSG", "MCM", "RSR", 'G', Blocks.glass, 'S', sensor, 'M', memory, 'C', controller,
                'R', Items.redstone);
        component(ComponentType.GAS_TANK, 1, "F F", "XQX", "FBF", 'F', alFoil, 'X', casing, 'Q', quartzChamber, 'B', Items.bucket);
        component(ComponentType.CABIN, 1, "GGG", "GHG", "PLP", 'G', Blocks.glass, 'H', part(ComponentType.HULL_I), 'P', polymer, 'L', Items.leather);
        component(ComponentType.HULL_I, 1, "SSS", "XXX", "SSS", 'S', steel, 'X', casing);
        component(ComponentType.HULL_II, 1, "IAI", "AIA", "IAI", 'I', al, 'A', alFrame);
        component(ComponentType.HULL_III, 1, "PFP", "FPF", "PFP", 'P', tiPlate, 'F', tiFrame);
        component(ComponentType.HULL_IV, 1, "WFW", "FWF", "WFW", 'W', wTi, 'F', tiFrame);
        component(ComponentType.HULL_V, 1, "PCP", "PHP", "CPC", 'P', nbPlate, 'C', clot, 'H', part(ComponentType.HULL_IV));
        // energy blocks: two batteries of the tier around the block below it (MV: cells + steel casings)
        component(ComponentType.ENERGY_BLOCK_MV, 1, "CXC", "BXB", "CXC", 'C', cellMV, 'X', casing, 'B', battery(1));
        component(ComponentType.ENERGY_BLOCK_HV, 1, "CAC", "BEB", "CAC", 'C', cellHV, 'A', alFrame, 'B', battery(2),
                'E', part(ComponentType.ENERGY_BLOCK_MV));
        component(ComponentType.ENERGY_BLOCK_EV, 1, "CTC", "BEB", "CTC", 'C', cellHV, 'T', tiFrame, 'B', battery(3),
                'E', part(ComponentType.ENERGY_BLOCK_HV));
        component(ComponentType.ENERGY_BLOCK_QV, 1, "PNP", "BEB", "PNP", 'P', nbPlate, 'N', nbCoil, 'B', battery(4),
                'E', part(ComponentType.ENERGY_BLOCK_EV));
        component(ComponentType.ENERGY_BLOCK_XV, 1, "WHW", "BEB", "WHW", 'W', wTi, 'H', heLoop, 'B', battery(5),
                'E', part(ComponentType.ENERGY_BLOCK_QV));
        component(ComponentType.ENERGY_BLOCK_SV, 1, "PGP", "BEB", "PGP", 'P', nbPlate, 'G', gravCoil, 'B', battery(6),
                'E', part(ComponentType.ENERGY_BLOCK_XV));

        // ---- vehicles (5x5, EU + time). One line each.
        vehicle(VehicleType.BUGGY, 200000L, 600,
                "XXXXX", "WHCHW", "SMFES", "WKTKW", " SSS ",
                'X', casing, 'W', part(ComponentType.WHEEL), 'H', part(ComponentType.HULL_I), 'C', part(ComponentType.CABIN),
                'S', steel, 'M', part(ComponentType.MOTOR_I), 'F', part(ComponentType.FLIGHT_COMPUTER),
                'E', part(ComponentType.ENERGY_BLOCK_MV), 'K', coil, 'T', controller);

        // Hoverbike (mock-up veh_8_workbench_recipes.png): titanium plates on top, gas tanks + hull III + computer,
        // antigrav engines round the QV energy block, coils + controller, titanium ingots below. QV: needs an HV input.
        ItemStack ti = ingot(Material.TITANIUM);
        vehicle(VehicleType.HOVER, 8000000L, 3600,
                " PPP ", "GHFHG", "IAEAI", " KTK ", " III ",
                'P', tiPlate, 'G', part(ComponentType.GAS_TANK), 'H', part(ComponentType.HULL_III), 'F', part(ComponentType.FLIGHT_COMPUTER),
                'I', ti, 'A', part(ComponentType.ANTIGRAV_ENGINE), 'E', part(ComponentType.ENERGY_BLOCK_QV), 'K', coil, 'T', controller);

        // ---- blocks (also on the crafting table)
        block("charger", new ItemStack(ModContentSVA.charger, 1, Tier.MV.ordinal()), Tier.MV, "XXX", "KEK", "STS",
                'X', casing, 'K', coil, 'E', cellMV, 'S', steel, 'T', ModItems.upgrade.stackOf(com.sc.machine.UpgradeType.TRANSFORMER));
        block("gasColumn", new ItemStack(ModContentSVA.gasColumn), Tier.HV, "XGX", "RCR", "XBX",
                'X', casing, 'G', part(ComponentType.GAS_TANK), 'R', rubber, 'C', controller, 'B', Items.bucket);

        // ---- the charging platform (plan §9): plates (instant, workbench only) and the station's modules
        ItemStack plate = new ItemStack(ModContentSVA.chargePlate, 1, com.sva.block.BlockChargePlateSVA.PLAIN);
        block("chargePlate", new ItemStack(ModContentSVA.chargePlate, 8, com.sva.block.BlockChargePlateSVA.PLAIN), Tier.MV,
                "STS", "RKR", "STS", 'S', steel, 'T', Blocks.stone, 'R', Items.redstone, 'K', coil);
        block("chargePlateH", new ItemStack(ModContentSVA.chargePlate, 8, com.sva.block.BlockChargePlateSVA.AVIA), Tier.MV,
                "PPP", "PYP", "PPP", 'P', plate, 'Y', new ItemStack(Items.dye, 1, 11));
        block("chargePlateEdge", new ItemStack(ModContentSVA.chargePlate, 2, com.sva.block.BlockChargePlateSVA.EDGE), Tier.MV,
                "PGP", 'P', plate, 'G', Items.glowstone_dust);
        com.sva.item.ItemStationModuleSVA mod = ModContentSVA.module;
        module("moduleGas", mod.stackOf(com.sva.item.ItemStationModuleSVA.GAS), Tier.HV, "XQX", "RCR", "XBX",
                'X', casing, 'Q', quartzChamber, 'R', rubber, 'C', controller, 'B', Items.bucket);
        module("moduleRepair", mod.stackOf(com.sva.item.ItemStationModuleSVA.REPAIR), Tier.MV, "SAS", "ICI", "SXS",
                'S', steel, 'A', Blocks.anvil, 'I', iron, 'C', controller, 'X', casing);
        module("moduleAccess", mod.stackOf(com.sva.item.ItemStationModuleSVA.ACCESS), Tier.MV, "PMP", "GCG", "PRP",
                'P', Items.paper, 'M', memory, 'G', Items.gold_ingot, 'C', controller, 'R', Items.redstone);
    }
}
