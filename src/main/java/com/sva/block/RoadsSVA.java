package com.sva.block;

import com.sva.init.ModContentSVA;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

/**
 * Wave 1, ★11: the road blocks' shared bits - the render id of client/RenderRoadSVA (signs, traffic light, guard rail;
 * set by the client proxy, unused on a server) and the crafting-table recipes (vanilla items only, plan §8: simple
 * blocks on the vanilla crafting table).
 */
public final class RoadsSVA {

    /** client/RenderRoadSVA's ISimpleBlockRenderingHandler id (the client proxy sets it before anything renders). */
    public static int renderId = -1;

    private RoadsSVA() {
    }

    public static ItemStack road(int variant, int n) {
        return new ItemStack(ModContentSVA.road, n, variant);
    }

    public static void recipes() {
        ItemStack coal = new ItemStack(Items.coal, 1, OreDictionary.WILDCARD_VALUE);   // coal or charcoal: the binder
        ItemStack boneMeal = new ItemStack(Items.dye, 1, 15);
        ItemStack asphalt = road(BlockRoadSVA.ASPHALT, 1);
        // asphalt x8: gravel x4, sand x2, coal x3 (the bitumen)
        GameRegistry.addRecipe(new ShapedOreRecipe(road(BlockRoadSVA.ASPHALT, 8), "GSG", "CCC", "GSG",
                'G', Blocks.gravel, 'S', Blocks.sand, 'C', coal));
        // concrete slab x8: stone x4, sand x4, a water bucket (the bucket comes back)
        GameRegistry.addRecipe(new ShapedOreRecipe(road(BlockRoadSVA.CONCRETE, 8), "TST", "SWS", "TST",
                'T', Blocks.stone, 'S', Blocks.sand, 'W', Items.water_bucket));
        // markings: asphalt + bone meal (white paint) - edge x1, dashed x2, solid x3, arrow x4 (shapeless, by count)
        GameRegistry.addRecipe(new ShapelessOreRecipe(road(BlockRoadSVA.EDGE, 1), asphalt, boneMeal));
        GameRegistry.addRecipe(new ShapelessOreRecipe(road(BlockRoadSVA.DASH, 2), asphalt, asphalt, boneMeal));
        GameRegistry.addRecipe(new ShapelessOreRecipe(road(BlockRoadSVA.SOLID, 3), asphalt, asphalt, asphalt, boneMeal));
        GameRegistry.addRecipe(new ShapelessOreRecipe(road(BlockRoadSVA.ARROW, 4), asphalt, asphalt, asphalt, asphalt, boneMeal));
        // curb x6: concrete slab x3 in a row
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(ModContentSVA.curb, 6), "CCC", 'C', road(BlockRoadSVA.CONCRETE, 1)));
        // road signs x2: iron ingot, red dye (the paint), stick (the post); a sign alone turns into the next picture
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(ModContentSVA.roadSign, 2, 0), "D", "I", "S",
                'D', new ItemStack(Items.dye, 1, 1), 'I', Items.iron_ingot, 'S', Items.stick));
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(ModContentSVA.roadSignArrow, 2, 0), "D", "I", "S",
                'D', new ItemStack(Items.dye, 1, 4), 'I', Items.iron_ingot, 'S', Items.stick));
        BlockRoadSignSVA[] signs = {ModContentSVA.roadSign, ModContentSVA.roadSignArrow};
        for (int s = 0; s < 2; s++) {
            for (int i = 0; i < BlockRoadSignSVA.PICTURES; i++) {
                ItemStack next = i + 1 < BlockRoadSignSVA.PICTURES ? new ItemStack(signs[s], 1, i + 1) : new ItemStack(signs[s], 1, 0);
                GameRegistry.addRecipe(new ShapelessOreRecipe(next, new ItemStack(signs[s], 1, i)));
            }
        }
        // traffic light: iron x4, red / yellow / green dye, glowstone dust, redstone
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(ModContentSVA.trafficLight), "IRI", "YGE", "IXI",
                'I', Items.iron_ingot, 'R', new ItemStack(Items.dye, 1, 1), 'Y', new ItemStack(Items.dye, 1, 11),
                'E', new ItemStack(Items.dye, 1, 2), 'G', Items.glowstone_dust, 'X', Items.redstone));
        // guard rail x8: iron ingot x7
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(ModContentSVA.guardRail, 8), "I I", "III", "I I", 'I', Items.iron_ingot));
    }
}
