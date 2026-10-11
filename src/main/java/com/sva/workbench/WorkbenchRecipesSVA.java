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

    /** The core quarry's drill head of a kind - any wear (ТБ-6: a head that has worked in a quarry fits too). */
    private static ItemStack drillHead(com.sc.item.ItemDrillHeadSC.Kind k) {
        for (com.sc.item.ItemDrillHeadSC h : ModItems.DRILL_HEADS) {
            if (h.kind == k) {
                return new ItemStack(h, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE);
            }
        }
        throw new IllegalStateException("Silicon Age drill head missing: " + k);
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
        // stage C: «Расширитель бура» (the drill vehicle's 5x5 bore): two of the core's tungsten drill heads either side
        // of a controller, copper coils, titanium frames and a titanium casing
        component(ComponentType.DRILL_WIDENER, 1, "HCH", "KXK", "FFF", 'H', drillHead(com.sc.item.ItemDrillHeadSC.Kind.TUNGSTEN),
                'C', controller, 'K', coil, 'X', tiCasing, 'F', tiFrame);

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

        // Light Helicopter (plan §4.2: hull II, electric motor II, HV energy block, flight computer, main rotor x2, cabin):
        // the rotors on the top corners over an aluminium frame, the cabin / computer / hull row, the motor between
        // copper coils and polymer plates, the energy block in the belly, steel skids. HV - any workbench builds it.
        ItemStack hull2 = part(ComponentType.HULL_II), motor2 = part(ComponentType.MOTOR_II), ebHV = part(ComponentType.ENERGY_BLOCK_HV),
                computer = part(ComponentType.FLIGHT_COMPUTER), cabin = part(ComponentType.CABIN);
        vehicle(VehicleType.HELI, 1500000L, 1800,
                "RAAAR", "ICFHI", "PKMKP", "SIEIS", " S S ",
                'R', part(ComponentType.ROTOR), 'A', alFrame, 'I', al, 'C', cabin, 'F', computer, 'H', hull2, 'P', polymer,
                'K', coil, 'M', motor2, 'S', steel, 'E', ebHV);
        // Light Plane (plan §4.2: hull II, electric motor II, HV energy block, flight computer, wing x2, wheel x3, cabin):
        // the cabin on top, the wings either side of the computer, motor - hull - energy block across, three wheels below.
        vehicle(VehicleType.PLANE, 1500000L, 1800,
                " ACA ", "WIFIW", "KMHEK", " AIA ", "T T T",
                'A', alFrame, 'C', cabin, 'W', part(ComponentType.WING), 'I', al, 'F', computer, 'K', coil, 'M', motor2,
                'H', hull2, 'E', ebHV, 'T', part(ComponentType.WHEEL));

        // Cargo Crawler (plan §4.2: hull II, electric motor II, HV energy block, track x2, chest x3, gas tank; 1M EU):
        // the chests on top (its bed), aluminium frames round the hull / motor / energy block row, the gas tank between
        // aluminium and copper coils, a track each side over steel. HV - any workbench builds it.
        ItemStack chest = new ItemStack(Blocks.chest), track = part(ComponentType.TRACK);
        vehicle(VehicleType.TRUCK, 1000000L, 1200,
                " CCC ", "AHMEA", "KIGIK", "T S T", " SSS ",
                'C', chest, 'A', alFrame, 'H', hull2, 'M', motor2, 'E', ebHV, 'K', coil, 'I', al, 'G', part(ComponentType.GAS_TANK),
                'T', track, 'S', steel);
        // Trailer (plan §4.2: hull II, wheel x4, chest x3) - an item, hitched by a right-click on the crawler
        add(new WorkbenchRecipeSVA("trailer", Category.VEHICLES, new ItemStack(ModContentSVA.trailer), Tier.HV, 100000L, 300,
                new Object[] {"CCC", "AHA", "WSW", "W W", 'C', chest, 'A', alFrame, 'H', hull2, 'W', part(ComponentType.WHEEL), 'S', steel}));
        // Drill Vehicle (plan §4.2: hull III, turbo engine, EV energy block, track x2, the core's diamond drill head, gas
        // tank x2; 4M EU): tanks on top, titanium plates round hull / turbo / energy block, the diamond head in front
        // between titanium casings and coils, tracks over titanium. EV: the bare MV workbench builds it (WorkbenchMath).
        vehicle(VehicleType.DRILL, 4000000L, 2400,
                " G G ", "PHTEP", "KXDXK", "R I R", " III ",
                'G', part(ComponentType.GAS_TANK), 'P', tiPlate, 'H', part(ComponentType.HULL_III), 'T', part(ComponentType.TURBO_ENGINE),
                'E', part(ComponentType.ENERGY_BLOCK_EV), 'K', coil, 'X', tiCasing, 'D', drillHead(com.sc.item.ItemDrillHeadSC.Kind.DIAMOND),
                'R', track, 'I', ti);

        // ---- stage D
        // Jet (plan §4.2: hull IV, turbo engine x2, QV energy block, flight computer, wing x2, wheel x3, gas tank x2; 12M EU):
        // the H2 / He tanks either side of the computer on top, the wings outboard of the two turbos round the hull, the QV
        // energy block between W-Ti plates and copper coils, titanium casings / ingot, three wheels below. QV: 1 transformer.
        ItemStack hull4 = part(ComponentType.HULL_IV), gasTank = part(ComponentType.GAS_TANK), wing = part(ComponentType.WING);
        vehicle(VehicleType.JET, 12000000L, 4800,
                " GFG ", "WTHTW", "KPEPK", " XIX ", "R R R",
                'G', gasTank, 'F', computer, 'W', wing, 'T', part(ComponentType.TURBO_ENGINE), 'H', hull4, 'K', coil, 'P', wTi,
                'E', part(ComponentType.ENERGY_BLOCK_QV), 'X', tiCasing, 'I', ti, 'R', part(ComponentType.WHEEL));
        // Tiltrotor (plan §4.2: hull IV, antigrav engine + main rotor x2, XV energy block, wing x2, gas tank x2; the brief adds
        // the flight computer and a cabin; 30M EU): a rotor on each wing tip either side of the cabin, the tanks round the
        // computer between W-Ti plates, the antigrav engine / hull IV / XV energy block between coils, titanium below, skids.
        // XV: 2 transformers.
        vehicle(VehicleType.TILT, 30000000L, 7200,
                "RWCWR", "PGFGP", "KAHEK", " XIX ", " S S ",
                'R', part(ComponentType.ROTOR), 'W', wing, 'C', cabin, 'P', wTi, 'G', gasTank, 'F', computer, 'K', coil,
                'A', part(ComponentType.ANTIGRAV_ENGINE), 'H', hull4, 'E', part(ComponentType.ENERGY_BLOCK_XV), 'X', tiCasing,
                'I', ti, 'S', steel);

        // ---- stage E
        // Transport Shuttle (plan §4.2: hull IV, antigrav engine x2, XV energy block, cabin IV, the core's fusion reactor
        // core, gas tank x3; the brief adds the flight computer; 60M EU): the cabin between two gas tanks on top, the
        // antigrav engines either side of the computer between W-Ti plates, hull IV / fusion core / third tank between
        // copper coils, the XV energy block in titanium casings, steel skids. XV: 2 transformers.
        vehicle(VehicleType.SHUTTLE, 60000000L, 12000,
                " GCG ", "PAFAP", "KHRGK", " XEX ", " S S ",
                'G', gasTank, 'C', cabin, 'P', wTi, 'A', part(ComponentType.ANTIGRAV_ENGINE), 'F', computer, 'K', coil,
                'H', hull4, 'R', c("fusionCore"), 'X', tiCasing, 'E', part(ComponentType.ENERGY_BLOCK_XV), 'S', steel);
        // Singular Ship (plan §4.2: hull V, singular engine, SV energy block, cabin V, the core's bridge link module, gas
        // tank x4; the brief adds the flight computer and singular clots; 500M EU): clots at the corners, the cabin between
        // tanks, the computer between two more, the singular engine / hull V / bridge link module between gravity coils,
        // the SV energy block in Nb3Sn plates. SV: 3 transformers.
        vehicle(VehicleType.SHIP, 500000000L, 24000,
                "QGCGQ", "PGFGP", "KSHBK", " PEP ", " Q Q ",
                'Q', clot, 'G', gasTank, 'C', cabin, 'P', nbPlate, 'F', computer, 'K', gravCoil,
                'S', part(ComponentType.SINGULAR_ENGINE), 'H', part(ComponentType.HULL_V), 'B', new ItemStack(ModItems.bridgeLinkModule),
                'E', part(ComponentType.ENERGY_BLOCK_SV));

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
        // ---- the runway (plan §4.3: smooth stone x6, glowstone, redstone, steel ingot -> x8); lit edge: runway x4 + glowstone dust
        ItemStack runway = new ItemStack(ModContentSVA.runway, 1, com.sva.block.BlockRunwaySVA.PLAIN);
        block("runway", new ItemStack(ModContentSVA.runway, 8, com.sva.block.BlockRunwaySVA.PLAIN), Tier.HV,
                "TTT", "GRS", "TTT", 'T', Blocks.stone, 'G', Blocks.glowstone, 'R', Items.redstone, 'S', steel);
        block("runwayLit", new ItemStack(ModContentSVA.runway, 4, com.sva.block.BlockRunwaySVA.LIT), Tier.HV,
                " P ", "PDP", " P ", 'P', runway, 'D', Items.glowstone_dust);
        // stage D1, ★1: PAPI x4 (a row) - steel ingot x2 (housings), glass x2 (lenses), glowstone, red dye (the filters),
        // controller (the angle setting), iron x2 (the legs)
        block("papi", new ItemStack(ModContentSVA.papi, 4), Tier.HV, "SGS", "LCD", "I I",
                'S', steel, 'G', Blocks.glass, 'L', Blocks.glowstone, 'C', controller, 'D', new ItemStack(Items.dye, 1, 1), 'I', Items.iron_ingot);
        com.sva.item.ItemStationModuleSVA mod = ModContentSVA.module;
        module("moduleGas", mod.stackOf(com.sva.item.ItemStationModuleSVA.GAS), Tier.HV, "XQX", "RCR", "XBX",
                'X', casing, 'Q', quartzChamber, 'R', rubber, 'C', controller, 'B', Items.bucket);
        module("moduleRepair", mod.stackOf(com.sva.item.ItemStationModuleSVA.REPAIR), Tier.MV, "SAS", "ICI", "SXS",
                'S', steel, 'A', Blocks.anvil, 'I', iron, 'C', controller, 'X', casing);
        module("moduleAccess", mod.stackOf(com.sva.item.ItemStationModuleSVA.ACCESS), Tier.MV, "PMP", "GCG", "PRP",
                'P', Items.paper, 'M', memory, 'G', Items.gold_ingot, 'C', controller, 'R', Items.redstone);

        // ---- wave 2 (appended: the list index is the sync id)
        // ★7 the repair garage: an anvil over the controller between copper coils, steel casings, HV energy cell and steel
        block("garage", new ItemStack(ModContentSVA.garage), Tier.HV, "XAX", "KCK", "SES",
                'X', casing, 'A', Blocks.anvil, 'K', coil, 'C', controller, 'S', steel, 'E', cellHV);
        // the floor: steel round a piston (the lift) with yellow dye and coal (the hazard stripes) -> 8 lift pads
        ItemStack pad = new ItemStack(ModContentSVA.garagePlate, 1, com.sva.block.BlockGaragePlateSVA.PAD);
        block("garagePad", new ItemStack(ModContentSVA.garagePlate, 8, com.sva.block.BlockGaragePlateSVA.PAD), Tier.HV,
                "SYS", "CPC", "SYS", 'S', steel, 'Y', new ItemStack(Items.dye, 1, 11), 'C', Items.coal, 'P', Blocks.piston);
        block("garageLamp", new ItemStack(ModContentSVA.garagePlate, 2, com.sva.block.BlockGaragePlateSVA.LAMP), Tier.HV,
                "PGP", 'P', pad, 'G', Blocks.glowstone);
        // ★8 tuning upgrades (category «Модули»)
        com.sva.item.ItemTuningSVA tu = ModContentSVA.tuning;
        module("tuningMotor1", tu.stackOf(com.sva.vehicle.TuningMath.MOTOR_I), Tier.MV, "KNK", "CMC", "KNK",
                'K', coil, 'N', nd, 'C', controller, 'M', part(ComponentType.MOTOR_I));
        module("tuningMotor2", tu.stackOf(com.sva.vehicle.TuningMath.MOTOR_II), Tier.HV, " C ", "NUN", " M ",
                'C', controller, 'N', nbCoil, 'U', tu.stackOf(com.sva.vehicle.TuningMath.MOTOR_I), 'M', part(ComponentType.MOTOR_II));
        module("tuningBattery", tu.stackOf(com.sva.vehicle.TuningMath.BATTERY), Tier.HV, "XBX", "CEC", "XBX",
                'X', casing, 'B', battery(2), 'C', cellHV, 'E', part(ComponentType.ENERGY_BLOCK_MV));
        module("tuningArmor", tu.stackOf(com.sva.vehicle.TuningMath.ARMOR), Tier.HV, "TST", "SXS", "TST",
                'T', tiPlate, 'S', steel, 'X', casing);
        module("tuningLightHull", tu.stackOf(com.sva.vehicle.TuningMath.LIGHT_HULL), Tier.HV, "AIA", "IPI", "AIA",
                'A', alFrame, 'I', al, 'P', polymer);
        module("tuningBigWheels", tu.stackOf(com.sva.vehicle.TuningMath.BIG_WHEELS), Tier.MV, "WRW", "RSR", "WRW",
                'W', part(ComponentType.WHEEL), 'R', rubber, 'S', steel);
        module("tuningLightBar", tu.stackOf(com.sva.vehicle.TuningMath.LIGHT_BAR), Tier.MV, "GGG", "SRS",
                'G', Blocks.glowstone, 'S', steel, 'R', Items.redstone);
        module("tuningDrillPlus", tu.stackOf(com.sva.vehicle.TuningMath.DRILL_PLUS), Tier.EV, "KHK", "XCX",
                'K', coil, 'H', drillHead(com.sc.item.ItemDrillHeadSC.Kind.DIAMOND), 'X', tiCasing, 'C', controller);
        module("tuningExtraTank", tu.stackOf(com.sva.vehicle.TuningMath.EXTRA_TANK), Tier.HV, "FGF", "GXG", "FGF",
                'F', alFoil, 'G', part(ComponentType.GAS_TANK), 'X', casing);
        // ★10 the key card: paper round a memory chip, gold and redstone -> 2 blank cards
        module("keyCard", new ItemStack(ModContentSVA.keyCard, 2), Tier.MV, "PGP", "RMR", "PGP",
                'P', Items.paper, 'G', Items.gold_nugget, 'R', Items.redstone, 'M', memory);

        // ---- wave 3 (appended: the list index is the sync id), ★15 the trailer kinds - made from an EMPTY cargo trailer
        // (WorkbenchRecipeSVA.matches refuses a trailer with cargo / contents, so nothing in it is ever lost)
        ItemStack trailer = new ItemStack(ModContentSVA.trailer, 1, com.sva.vehicle.TrailerMath.CARGO);
        // the tanker: four gas tanks round the trailer, steel casings for the cradle, quartz chambers for the valves
        add(new WorkbenchRecipeSVA("trailerTank", Category.VEHICLES, new ItemStack(ModContentSVA.trailer, 1, com.sva.vehicle.TrailerMath.TANK),
                Tier.HV, 150000L, 400, new Object[] {"GXG", "GTG", "QSQ", 'G', gasTank, 'X', casing, 'T', trailer, 'Q', quartzChamber, 'S', steel}));
        // the car platform: a steel-casing deck, hazard stripes (yellow dye), the winch (copper coil), a second axle
        add(new WorkbenchRecipeSVA("trailerPlatform", Category.VEHICLES, new ItemStack(ModContentSVA.trailer, 1, com.sva.vehicle.TrailerMath.PLATFORM),
                Tier.HV, 120000L, 300, new Object[] {"XXX", "YTY", "WKW", 'X', casing, 'Y', new ItemStack(Items.dye, 1, 11), 'T', trailer,
                        'W', part(ComponentType.WHEEL), 'K', coil}));
        // the mobile charger: four of the core's silicon solar panels (8 EU/t each = its 32 EU/t), two HV energy blocks
        // (2M EU each = its 4M buffer), copper coils for the cable
        ItemStack solar = com.sc.init.ModBlocks.generatorStack(com.sc.energy.GeneratorType.SOLAR_SI, 1);
        add(new WorkbenchRecipeSVA("trailerCharger", Category.VEHICLES, new ItemStack(ModContentSVA.trailer, 1, com.sva.vehicle.TrailerMath.CHARGER),
                Tier.HV, 400000L, 600, new Object[] {"PEP", "KTK", "PEP", 'P', solar, 'E', ebHV, 'K', coil, 'T', trailer}));

        // ---- fast charging (2026-10-10; appended: the list index is the sync id)
        // «Быстрая зарядка» module (x2): two of the core's overclocker upgrades either side of the controller, Nb3Sn coils
        // for the heavier current, HV energy cells, steel casings
        ItemStack oc = ModItems.upgrade.stackOf(com.sc.machine.UpgradeType.OVERCLOCKER);
        module("moduleFast", mod.stackOf(com.sva.item.ItemStationModuleSVA.FAST), Tier.EV, "XNX", "OCO", "EKE",
                'X', casing, 'N', nbCoil, 'O', oc, 'C', controller, 'E', cellHV, 'K', coil);
        // «Сингулярная зарядная станция» (SV): the MV station in the middle, gravity coils and singular clots round it,
        // Nb3Sn plates, the core's singular core battery and the SV energy block below
        block("chargerSV", new ItemStack(ModContentSVA.charger, 1, Tier.SV.ordinal()), Tier.SV, "QGQ", "PSP", "BEB",
                'Q', clot, 'G', gravCoil, 'P', nbPlate, 'S', new ItemStack(ModContentSVA.charger, 1, Tier.MV.ordinal()), 'B', battery(6),
                'E', part(ComponentType.ENERGY_BLOCK_SV));
        // «Суперконденсатор станции» (50M EU): the core's exo-core batteries (XV) round the XV energy block, Nb3Sn plates,
        // He loop modules (the cooling), a controller
        block("supercap", new ItemStack(ModContentSVA.supercap), Tier.XV, "PBP", "HEH", "PCP",
                'P', nbPlate, 'B', battery(5), 'H', heLoop, 'E', part(ComponentType.ENERGY_BLOCK_XV), 'C', controller);
    }
}
