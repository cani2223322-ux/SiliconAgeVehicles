package com.sva.nei;

import java.util.ArrayList;

import com.sva.client.GuiWorkbenchSVA;
import com.sva.workbench.WorkbenchNeiLayoutSVA;

import codechicken.nei.PositionedStack;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.api.IStackPositioner;
import codechicken.nei.recipe.IRecipeHandler;
import net.minecraft.client.gui.inventory.GuiContainer;

/**
 * NEI's «?» on a workbench recipe page, opened from the workbench screen. NEI calls the positioner for a plain
 * click (ghost items over the grid) and this handler for Shift + click (or when no ghost overlay is registered):
 * the recipe is selected in the screen's list and laid out by the «Разложить» packet (NetSVA.Workbench FILL with
 * the recipe id - the server plans from the player's inventory; ServerQueueSVA caps pending tasks per player).
 */
public class WorkbenchOverlayHandlerSVA implements IOverlayHandler, IStackPositioner {

    @Override
    public void overlayRecipe(GuiContainer gui, IRecipeHandler handler, int recipe, boolean shift) {
        if (gui instanceof GuiWorkbenchSVA && handler instanceof WorkbenchRecipeHandlerSVA) {
            ((GuiWorkbenchSVA) gui).selectFromNei(((WorkbenchRecipeHandlerSVA) handler).recipeAt(recipe), true);
        }
    }

    /** Page positions -> the workbench grid slots (the page uses the screen's 18-pixel grid and centring). */
    @Override
    public ArrayList<PositionedStack> positionStacks(ArrayList<PositionedStack> stacks) {
        ArrayList<PositionedStack> out = new ArrayList<PositionedStack>();
        for (PositionedStack s : stacks) {
            PositionedStack c = s.copy();
            c.relx += WorkbenchNeiLayoutSVA.GHOST_DX;
            c.rely += WorkbenchNeiLayoutSVA.GHOST_DY;
            out.add(c);
        }
        return out;
    }
}
