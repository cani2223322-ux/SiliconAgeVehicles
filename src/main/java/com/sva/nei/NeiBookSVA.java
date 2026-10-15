package com.sva.nei;

import net.minecraft.item.ItemStack;

/**
 * Wave C, item 44: the handbook's NEI calls - reached only with NEI loaded (GuiBookSVA checks Loader.isModLoaded first), so
 * nothing of NEI is touched without it (the core's NeiBookSC does the same).
 */
public final class NeiBookSVA {

    private NeiBookSVA() {
    }

    /** NEI's recipe screen for an item (a click on a recipe in the book). @return opened */
    public static boolean recipes(ItemStack s) {
        try {
            return codechicken.nei.recipe.GuiCraftingRecipe.openRecipeGui("item", s.copy());
        } catch (Throwable t) {
            return false;
        }
    }

    /** NEI's usage screen (where an ingredient goes). */
    public static boolean usage(ItemStack s) {
        try {
            return codechicken.nei.recipe.GuiUsageRecipe.openRecipeGui("item", s.copy());
        } catch (Throwable t) {
            return false;
        }
    }
}
