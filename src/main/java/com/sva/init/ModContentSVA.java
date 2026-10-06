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
