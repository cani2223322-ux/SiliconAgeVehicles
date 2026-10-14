package com.sva.init;

import com.sc.energy.Tier;
import com.sc.init.ModItems;
import com.sc.machine.UpgradeType;
import com.sc.util.Material;
import com.sc.util.SiliconMaterial;
import com.sva.ReferenceSVA;
import com.sva.SVAMod;
import com.sva.block.BlockChargerSVA;
import com.sva.block.BlockWorkbenchSVA;
import com.sva.block.ItemBlockWorkbenchSVA;
import com.sva.block.TileEntityWorkbenchSVA;
import com.sva.block.ItemBlockChargerSVA;
import com.sva.block.TileEntityChargerSVA;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.item.ItemComponentSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.ShapedOreRecipe;

/** Items, blocks, tiles, entities, recipes. Registry names are what worlds keep - never rename. */
public final class ModContentSVA {

    public static ItemVehicleSVA vehicle;
    public static BlockChargerSVA charger;
    public static ItemComponentSVA component;
    public static BlockWorkbenchSVA workbench;
    public static com.sva.block.BlockGasColumnSVA gasColumn;
    public static com.sva.block.BlockChargePlateSVA chargePlate;
    public static com.sva.item.ItemStationModuleSVA module;
    public static com.sva.block.BlockRunwaySVA runway;
    public static com.sva.item.ItemTrailerSVA trailer;
    // wave 1, ★11: roads
    public static com.sva.block.BlockRoadSVA road;
    public static com.sva.block.BlockCurbSVA curb;
    public static com.sva.block.BlockRoadSignSVA roadSign, roadSignArrow;
    public static com.sva.block.BlockTrafficLightSVA trafficLight;
    public static com.sva.block.BlockGuardRailSVA guardRail;
    // wave 2: ★7 the repair garage and its floor, ★8 tuning upgrades, ★10 the key card
    public static com.sva.block.BlockGarageSVA garage;
    public static com.sva.block.BlockGaragePlateSVA garagePlate;
    public static com.sva.item.ItemTuningSVA tuning;
    public static com.sva.item.ItemKeyCardSVA keyCard;
    // fast charging (2026-10-10): the station's supercapacitor
    public static com.sva.block.BlockSupercapSVA supercap;
    // aircraft round 2, stage D1: ★1 PAPI, ★2 approach lights
    public static com.sva.block.BlockPapiSVA papi;
    public static com.sva.block.BlockApproachLightSVA approachLight;
    /** Liner wave 4: «Курсо-глиссадный маяк» (the ILS beacon). */
    public static com.sva.block.BlockIlsSVA ils;
    // hydrogen wave: the vehicles' removable batteries, the electrolyte / service kit / jump leads
    public static com.sva.item.ItemBatterySVA battery;
    public static com.sva.item.ItemBatteryToolSVA batteryTool;
    // air-defence wave: radars, launchers, the command post; ammunition, the code card
    public static com.sva.block.BlockAirDefenseSVA radar, launcher, commandPost;
    /** Multiblock wave: the invisible parts of the air-defence structures (no item of its own in the tab). */
    public static com.sva.block.BlockAdPartSVA adPart;
    public static com.sva.item.ItemAmmoSVA ammo;
    public static com.sva.item.ItemCodeCardSVA codeCard;

    public static final CreativeTabs TAB = new CreativeTabs(ReferenceSVA.ASSETS) {
        @Override
        public Item getTabIconItem() {
            return vehicle;
        }
    };

    private ModContentSVA() {
    }

    public static void preInit() {
        vehicle = new ItemVehicleSVA();
        vehicle.setCreativeTab(TAB);
        GameRegistry.registerItem(vehicle, "vehicle");
        charger = new BlockChargerSVA();
        charger.setCreativeTab(TAB);
        GameRegistry.registerBlock(charger, ItemBlockChargerSVA.class, "charger");
        GameRegistry.registerTileEntity(TileEntityChargerSVA.class, ReferenceSVA.MODID + ":charger");
        component = new ItemComponentSVA();
        component.setCreativeTab(TAB);
        GameRegistry.registerItem(component, "component");
        workbench = new BlockWorkbenchSVA();
        workbench.setCreativeTab(TAB);
        GameRegistry.registerBlock(workbench, ItemBlockWorkbenchSVA.class, "workbench");
        GameRegistry.registerTileEntity(TileEntityWorkbenchSVA.class, ReferenceSVA.MODID + ":workbench");
        gasColumn = new com.sva.block.BlockGasColumnSVA();
        gasColumn.setCreativeTab(TAB);
        GameRegistry.registerBlock(gasColumn, com.sva.block.ItemBlockGasColumnSVA.class, "gasColumn");
        GameRegistry.registerTileEntity(com.sva.block.TileEntityGasColumnSVA.class, ReferenceSVA.MODID + ":gasColumn");
        chargePlate = new com.sva.block.BlockChargePlateSVA();
        chargePlate.setCreativeTab(TAB);
        GameRegistry.registerBlock(chargePlate, com.sva.block.ItemBlockChargePlateSVA.class, "chargePlate");
        module = new com.sva.item.ItemStationModuleSVA();
        module.setCreativeTab(TAB);
        GameRegistry.registerItem(module, "stationModule");
        runway = new com.sva.block.BlockRunwaySVA();
        runway.setCreativeTab(TAB);
        GameRegistry.registerBlock(runway, com.sva.block.ItemBlockRunwaySVA.class, "runway");
        trailer = new com.sva.item.ItemTrailerSVA();
        trailer.setCreativeTab(TAB);
        GameRegistry.registerItem(trailer, "trailer");
        // wave 1, ★11: road blocks (crafting table, vanilla items - block/RoadsSVA.recipes)
        road = new com.sva.block.BlockRoadSVA();
        road.setCreativeTab(TAB);
        GameRegistry.registerBlock(road, com.sva.block.ItemBlockRoadSVA.class, "road");
        curb = new com.sva.block.BlockCurbSVA();
        curb.setCreativeTab(TAB);
        GameRegistry.registerBlock(curb, com.sva.block.ItemBlockRoadPartSVA.class, "curb");
        roadSign = new com.sva.block.BlockRoadSignSVA(0);
        roadSign.setCreativeTab(TAB);
        GameRegistry.registerBlock(roadSign, com.sva.block.ItemBlockRoadPartSVA.class, "roadSign");
        roadSignArrow = new com.sva.block.BlockRoadSignSVA(1);
        roadSignArrow.setCreativeTab(TAB);
        GameRegistry.registerBlock(roadSignArrow, com.sva.block.ItemBlockRoadPartSVA.class, "roadSignArrow");
        trafficLight = new com.sva.block.BlockTrafficLightSVA();
        trafficLight.setCreativeTab(TAB);
        GameRegistry.registerBlock(trafficLight, com.sva.block.ItemBlockRoadPartSVA.class, "trafficLight");
        guardRail = new com.sva.block.BlockGuardRailSVA();
        guardRail.setCreativeTab(TAB);
        GameRegistry.registerBlock(guardRail, com.sva.block.ItemBlockRoadPartSVA.class, "guardRail");
        // wave 2 (Engineering Workbench recipes - WorkbenchRecipesSVA)
        garage = new com.sva.block.BlockGarageSVA();
        garage.setCreativeTab(TAB);
        GameRegistry.registerBlock(garage, com.sva.block.ItemBlockGarageSVA.class, "garage");
        GameRegistry.registerTileEntity(com.sva.block.TileEntityGarageSVA.class, ReferenceSVA.MODID + ":garage");
        garagePlate = new com.sva.block.BlockGaragePlateSVA();
        garagePlate.setCreativeTab(TAB);
        GameRegistry.registerBlock(garagePlate, com.sva.block.ItemBlockGaragePlateSVA.class, "garagePlate");
        tuning = new com.sva.item.ItemTuningSVA();
        tuning.setCreativeTab(TAB);
        GameRegistry.registerItem(tuning, "tuning");
        keyCard = new com.sva.item.ItemKeyCardSVA();
        keyCard.setCreativeTab(TAB);
        GameRegistry.registerItem(keyCard, "keyCard");
        // fast charging (2026-10-10; Engineering Workbench recipe - WorkbenchRecipesSVA)
        supercap = new com.sva.block.BlockSupercapSVA();
        supercap.setCreativeTab(TAB);
        GameRegistry.registerBlock(supercap, com.sva.block.ItemBlockSupercapSVA.class, "supercap");
        GameRegistry.registerTileEntity(com.sva.block.TileEntitySupercapSVA.class, ReferenceSVA.MODID + ":supercap");
        // aircraft round 2, stage D1 (PAPI: Engineering Workbench; approach lights: crafting table - block/RoadsSVA.recipes)
        papi = new com.sva.block.BlockPapiSVA();
        papi.setCreativeTab(TAB);
        GameRegistry.registerBlock(papi, com.sva.block.ItemBlockAirfieldSVA.class, "papi");
        approachLight = new com.sva.block.BlockApproachLightSVA();
        approachLight.setCreativeTab(TAB);
        GameRegistry.registerBlock(approachLight, com.sva.block.ItemBlockAirfieldSVA.class, "approachLight");
        GameRegistry.registerTileEntity(com.sva.block.TileEntityAirfieldSVA.class, ReferenceSVA.MODID + ":airfieldLight");
        // hydrogen wave (crafting table: core items + vanilla - recipes())
        battery = new com.sva.item.ItemBatterySVA();
        battery.setCreativeTab(TAB);
        GameRegistry.registerItem(battery, "vehicleBattery");
        batteryTool = new com.sva.item.ItemBatteryToolSVA();
        batteryTool.setCreativeTab(TAB);
        GameRegistry.registerItem(batteryTool, "batteryTool");
        // air-defence wave (radars / launchers / the command post: Engineering Workbench; ammunition, code card: crafting table)
        radar = new com.sva.block.BlockAirDefenseSVA(com.sva.block.BlockAirDefenseSVA.RADAR);
        radar.setCreativeTab(TAB);
        GameRegistry.registerBlock(radar, com.sva.block.ItemBlockAirDefenseSVA.class, "radar");
        GameRegistry.registerTileEntity(com.sva.block.TileEntityRadarSVA.class, ReferenceSVA.MODID + ":radar");
        launcher = new com.sva.block.BlockAirDefenseSVA(com.sva.block.BlockAirDefenseSVA.LAUNCHER);
        launcher.setCreativeTab(TAB);
        GameRegistry.registerBlock(launcher, com.sva.block.ItemBlockAirDefenseSVA.class, "launcher");
        GameRegistry.registerTileEntity(com.sva.block.TileEntityLauncherSVA.class, ReferenceSVA.MODID + ":launcher");
        commandPost = new com.sva.block.BlockAirDefenseSVA(com.sva.block.BlockAirDefenseSVA.COMMAND_POST);
        commandPost.setCreativeTab(TAB);
        GameRegistry.registerBlock(commandPost, com.sva.block.ItemBlockAirDefenseSVA.class, "commandPost");
        GameRegistry.registerTileEntity(com.sva.block.TileEntityCommandPostSVA.class, ReferenceSVA.MODID + ":commandPost");
        adPart = new com.sva.block.BlockAdPartSVA();
        GameRegistry.registerBlock(adPart, "adPart");
        GameRegistry.registerTileEntity(com.sva.block.TileEntityAdPartSVA.class, ReferenceSVA.MODID + ":adPart");
        ammo = new com.sva.item.ItemAmmoSVA();
        ammo.setCreativeTab(TAB);
        GameRegistry.registerItem(ammo, "adAmmo");
        codeCard = new com.sva.item.ItemCodeCardSVA();
        codeCard.setCreativeTab(TAB);
        GameRegistry.registerItem(codeCard, "codeCard");
        // liner wave 4: the ILS beacon (Engineering Workbench - WorkbenchRecipesSVA)
        ils = new com.sva.block.BlockIlsSVA();
        ils.setCreativeTab(TAB);
        GameRegistry.registerBlock(ils, com.sva.block.ItemBlockIlsSVA.class, "ils");
        GameRegistry.registerTileEntity(com.sva.block.TileEntityIlsSVA.class, ReferenceSVA.MODID + ":ils");
        // ids are append-only
        EntityRegistry.registerModEntity(EntityVehicleSVA.class, "vehicle", 0, SVAMod.instance, 128, 1, false);
        EntityRegistry.registerModEntity(EntitySeatSVA.class, "seat", 1, SVAMod.instance, 128, 20, false);
        // WAILA (vehicles, station, garage, gas column): it finds the plugin through IMC and loads the class itself -
        // nothing of WAILA's is touched when it isn't installed (the core's WailaSC does the same)
        cpw.mods.fml.common.event.FMLInterModComms.sendMessage("Waila", "register", "com.sva.WailaSVA.callbackRegister");
    }

    public static void recipes() {
        ItemStack casing = comp("steelCasing"), coil = comp("copperCoil"), cell = comp("energyCellMV");
        ItemStack steel = ModItems.ingot.stackOf(Material.STEEL);
        ItemStack transformer = ModItems.upgrade.stackOf(UpgradeType.TRANSFORMER);
        ItemStack controller = ModItems.siliconMaterial.stackOf(SiliconMaterial.CONTROLLER);
        // plan §4.3: steel casing x3 on top, copper coil x2, MV energy cell, transformer upgrade, steel ingot x2
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(charger, 1, Tier.MV.ordinal()),
                "XXX", "KEK", "STS",
                'X', casing, 'K', coil, 'E', cell, 'S', steel, 'T', transformer));
        // plan §3a: crafting table + steel casing x4 + controller + copper coil x2 + MV energy cell
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(workbench),
                "XCX", "KTK", "XEX",
                'X', casing, 'C', controller, 'K', coil, 'T', net.minecraft.init.Blocks.crafting_table, 'E', cell));
        // plan §4.3: gas tank, steel casing x4, rubber x2 (the hose), controller, bucket
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(gasColumn),
                "XGX", "RCR", "XBX",
                'X', casing, 'G', component.stackOf(com.sva.item.ComponentType.GAS_TANK, 1), 'R', new ItemStack(ModItems.rubber),
                'C', controller, 'B', net.minecraft.init.Items.bucket));
        batteryRecipes(casing, coil);
        airDefenseRecipes(casing, controller, steel);
        com.sva.block.RoadsSVA.recipes();                    // ★11 roads: crafting table, vanilla items
        // components and vehicles: only on the workbench (the stage 1 crafting-table buggy is gone)
        WorkbenchRecipesSVA.register();
    }

    /**
     * Hydrogen wave: a vehicle's battery - the core's battery of its tier (MV / HV / EV), copper coils, steel casings,
     * redstone and a part saying which vehicle it is for (the addon's wheel / track / rotor / wing; a feather - the
     * hoverbike, an iron pickaxe - the drill vehicle, a fire charge - the jet, a blaze rod - the tiltrotor, a compass - the
     * liner); the
     * electrolyte - a water bottle, the core's NaOH bucket, redstone (x2); the service kit and the jump leads.
     */
    private static void batteryRecipes(ItemStack casing, ItemStack coil) {
        Object[][] marks = {
            {com.sva.vehicle.VehicleType.BUGGY, component.stackOf(com.sva.item.ComponentType.WHEEL, 1)},
            {com.sva.vehicle.VehicleType.HOVER, new ItemStack(net.minecraft.init.Items.feather)},
            {com.sva.vehicle.VehicleType.TRUCK, component.stackOf(com.sva.item.ComponentType.TRACK, 1)},
            {com.sva.vehicle.VehicleType.DRILL, new ItemStack(net.minecraft.init.Items.iron_pickaxe)},
            {com.sva.vehicle.VehicleType.HELI, component.stackOf(com.sva.item.ComponentType.ROTOR, 1)},
            {com.sva.vehicle.VehicleType.PLANE, component.stackOf(com.sva.item.ComponentType.WING, 1)},
            {com.sva.vehicle.VehicleType.JET, new ItemStack(net.minecraft.init.Items.fire_charge)},
            {com.sva.vehicle.VehicleType.TILT, new ItemStack(net.minecraft.init.Items.blaze_rod)},
            // liner wave 1: a compass (the airliner's navigation) - the EV quartz capacitor like the jet's
            {com.sva.vehicle.VehicleType.LINER, new ItemStack(net.minecraft.init.Items.compass)},
        };
        for (Object[] m : marks) {
            com.sva.vehicle.VehicleType t = (com.sva.vehicle.VehicleType) m[0];
            Tier tier = com.sva.vehicle.BatteryMath.tier(t);
            ItemStack coreBattery = new ItemStack(ModItems.battery, 1, tier == Tier.MV ? 1 : tier == Tier.HV ? 2 : 3);
            GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(battery, 1, t.ordinal()),
                    " M ", "CBC", "XRX",
                    'M', m[1], 'C', coil, 'B', coreBattery, 'X', casing, 'R', net.minecraft.init.Items.redstone));
        }
        GameRegistry.addRecipe(new net.minecraftforge.oredict.ShapelessOreRecipe(batteryTool.stackOf(com.sva.item.ItemBatteryToolSVA.ELECTROLYTE, 2),
                new ItemStack(net.minecraft.init.Items.potionitem, 1, 0), new ItemStack(ModItems.fluidBucket, 1, NAOH_BUCKET),
                net.minecraft.init.Items.redstone));
        GameRegistry.addRecipe(new ShapedOreRecipe(batteryTool.stackOf(com.sva.item.ItemBatteryToolSVA.KIT, 1),
                "IRI", "XCX",
                'I', net.minecraft.init.Items.iron_ingot, 'R', net.minecraft.init.Items.redstone, 'X', casing, 'C', net.minecraft.init.Blocks.chest));
        GameRegistry.addRecipe(new ShapedOreRecipe(batteryTool.stackOf(com.sva.item.ItemBatteryToolSVA.CABLES, 1),
                "K K", "RDR",
                'K', coil, 'R', new ItemStack(ModItems.rubber), 'D', net.minecraft.init.Items.redstone));
        // liner wave 2: «Гидрожидкость» x2 - a water bottle, the core's compound, a slime ball
        GameRegistry.addRecipe(new net.minecraftforge.oredict.ShapelessOreRecipe(batteryTool.stackOf(com.sva.item.ItemBatteryToolSVA.HYD_FLUID, 2),
                new ItemStack(net.minecraft.init.Items.potionitem, 1, 0), new ItemStack(ModItems.compound), net.minecraft.init.Items.slime_ball));
        // liner wave 7: «Колодки» - wooden wedges with an iron rope and yellow paint (vanilla only)
        GameRegistry.addRecipe(new ShapedOreRecipe(batteryTool.stackOf(com.sva.item.ItemBatteryToolSVA.CHOCKS, 1),
                "Y Y", "WIW",
                'Y', new ItemStack(net.minecraft.init.Items.dye, 1, 11), 'W', "plankWood", 'I', net.minecraft.init.Items.iron_ingot));
    }

    /**
     * Air-defence wave (crafting table, core items + vanilla): «Снаряды ПВО» x16 - iron ingots, gunpowder x2, the core's
     * steel ingot (the mock-up: iron + powder); «Зенитная ракета» - a sensor (the seeker) over a controller between steel
     * casings, gunpowder and blaze powder (the motor); «Карта кодов» x2 - paper x2 and blue dye.
     */
    private static void airDefenseRecipes(ItemStack casing, ItemStack controller, ItemStack steel) {
        GameRegistry.addRecipe(new ShapedOreRecipe(ammo.stackOf(com.sva.item.ItemAmmoSVA.SHELLS, 16),
                "I I", "GSG", "I I",
                'I', net.minecraft.init.Items.iron_ingot, 'G', net.minecraft.init.Items.gunpowder, 'S', steel));
        GameRegistry.addRecipe(new ShapedOreRecipe(ammo.stackOf(com.sva.item.ItemAmmoSVA.MISSILE, 1),
                " N ", "XCX", "GBG",
                'N', comp("sensor"), 'X', casing, 'C', controller, 'G', net.minecraft.init.Items.gunpowder, 'B', net.minecraft.init.Items.blaze_powder));
        GameRegistry.addRecipe(new net.minecraftforge.oredict.ShapelessOreRecipe(new ItemStack(codeCard, 2),
                net.minecraft.init.Items.paper, net.minecraft.init.Items.paper, new ItemStack(net.minecraft.init.Items.dye, 1, 4)));
    }

    /** The core's NaOH bucket (ItemFluidBucketSC.FLUIDS index of "naoh"). */
    public static final int NAOH_BUCKET = java.util.Arrays.asList(com.sc.item.ItemFluidBucketSC.FLUIDS).indexOf("naoh");

    private static ItemStack comp(String name) {
        Item i = ModItems.component(name);
        if (i == null) {
            throw new IllegalStateException("Silicon Age component missing: " + name);
        }
        return new ItemStack(i);
    }

    /** n of the core's steel ingots (the garage world test). */
    public static ItemStack steelStack(int n) {
        ItemStack s = ModItems.ingot.stackOf(Material.STEEL).copy();
        s.stackSize = n;
        return s;
    }

    /** The core's steel ingot (field repair, the garage's metal). */
    public static boolean isSteelIngot(ItemStack s) {
        if (s == null || ModItems.ingot == null) {
            return false;
        }
        ItemStack steel = ModItems.ingot.stackOf(Material.STEEL);
        return s.getItem() == steel.getItem() && s.getItemDamage() == steel.getItemDamage();
    }
}
