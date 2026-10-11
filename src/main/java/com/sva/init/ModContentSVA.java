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
        com.sva.block.RoadsSVA.recipes();                    // ★11 roads: crafting table, vanilla items
        // components and vehicles: only on the workbench (the stage 1 crafting-table buggy is gone)
        WorkbenchRecipesSVA.register();
    }

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
