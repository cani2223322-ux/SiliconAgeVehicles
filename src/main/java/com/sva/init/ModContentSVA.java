package com.sva.init;

import com.sc.energy.Tier;
import com.sc.init.ModItems;
import com.sc.machine.UpgradeType;
import com.sc.util.Material;
import com.sc.util.SiliconMaterial;
import com.sva.ReferenceSVA;
import com.sva.SVAMod;
import com.sva.block.BlockChargerSVA;
import com.sva.block.ItemBlockChargerSVA;
import com.sva.block.TileEntityChargerSVA;
import com.sva.entity.EntitySeatSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleType;

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
        // ids are append-only
        EntityRegistry.registerModEntity(EntityVehicleSVA.class, "vehicle", 0, SVAMod.instance, 128, 1, false);
        EntityRegistry.registerModEntity(EntitySeatSVA.class, "seat", 1, SVAMod.instance, 128, 20, false);
    }

    public static void recipes() {
        ItemStack casing = comp("steelCasing"), coil = comp("copperCoil"), cell = comp("energyCellMV");
        ItemStack steel = ModItems.ingot.stackOf(Material.STEEL);
        ItemStack transformer = ModItems.upgrade.stackOf(UpgradeType.TRANSFORMER);
        ItemStack controller = ModItems.siliconMaterial.stackOf(SiliconMaterial.CONTROLLER);
        ItemStack rubber = new ItemStack(ModItems.rubber);
        // plan §4.3: steel casing x3 on top, copper coil x2, MV energy cell, transformer upgrade, steel ingot x2
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(charger, 1, Tier.MV.ordinal()),
                "XXX", "KEK", "STS",
                'X', casing, 'K', coil, 'E', cell, 'S', steel, 'T', transformer));
        // TEMPORARY (stage 1, until the Garage assembles vehicles from components): the buggy on a crafting table
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(vehicle, 1, VehicleType.BUGGY.ordinal()),
                "RXR", "KEK", "RCR",
                'R', rubber, 'X', casing, 'K', coil, 'E', cell, 'C', controller));
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
