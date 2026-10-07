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
        // ids are append-only
        EntityRegistry.registerModEntity(EntityVehicleSVA.class, "vehicle", 0, SVAMod.instance, 128, 1, false);
        EntityRegistry.registerModEntity(EntitySeatSVA.class, "seat", 1, SVAMod.instance, 128, 20, false);
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

    /** The core's steel ingot (temporary repair item). */
    public static boolean isSteelIngot(ItemStack s) {
        if (s == null || ModItems.ingot == null) {
            return false;
        }
        ItemStack steel = ModItems.ingot.stackOf(Material.STEEL);
        return s.getItem() == steel.getItem() && s.getItemDamage() == steel.getItemDamage();
    }
}
