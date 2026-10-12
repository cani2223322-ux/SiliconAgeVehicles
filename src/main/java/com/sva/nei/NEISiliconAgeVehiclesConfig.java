package com.sva.nei;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import com.sva.ReferenceSVA;
import com.sva.client.GuiWorkbenchSVA;

import codechicken.nei.api.API;
import codechicken.nei.api.IConfigureNEI;
import codechicken.nei.recipe.TemplateRecipeHandler;
import net.minecraft.client.gui.inventory.GuiContainer;

/**
 * NEI plugin entry point - NEI finds it by its class name (NEI*Config) in any loaded jar, so it is only ever
 * touched when NEI is installed (nothing else in the addon refers to com.sva.nei). Registers the Engineering
 * Workbench page, the workbench screen's arrow as the «recipes» click area, and the «?» ghost overlay / fill.
 * Hidden: the air-defence structures' invisible part block (multiblock wave; the seats are entities without an item).
 */
public class NEISiliconAgeVehiclesConfig implements IConfigureNEI {

    @Override
    public void loadConfig() {
        WorkbenchRecipeHandlerSVA handler = new WorkbenchRecipeHandlerSVA();
        API.registerRecipeHandler(handler);
        API.registerUsageHandler(handler);
        // «?» on a page opened from the workbench: click = ghost items on the grid, Shift + click = «Разложить»
        WorkbenchOverlayHandlerSVA overlay = new WorkbenchOverlayHandlerSVA();
        API.registerGuiOverlay(GuiWorkbenchSVA.class, WorkbenchRecipeHandlerSVA.ID, overlay);
        API.registerGuiOverlayHandler(GuiWorkbenchSVA.class, overlay, WorkbenchRecipeHandlerSVA.ID);
        // Clicking the progress arrow on the workbench screen lists every workbench recipe. NEI measures these
        // rects from (guiLeft + 5, guiTop + 11) on a screen with no registered offset (RecipeInfo.getGuiOffset).
        List<Class<? extends GuiContainer>> guis = new ArrayList<Class<? extends GuiContainer>>();
        guis.add(GuiWorkbenchSVA.class);
        List<TemplateRecipeHandler.RecipeTransferRect> rects = new ArrayList<TemplateRecipeHandler.RecipeTransferRect>();
        rects.add(new TemplateRecipeHandler.RecipeTransferRect(new Rectangle(GuiWorkbenchSVA.ARROW_X - 5, GuiWorkbenchSVA.ARROW_Y - 11,
                GuiWorkbenchSVA.ARROW_W, GuiWorkbenchSVA.ARROW_H), WorkbenchRecipeHandlerSVA.ID));
        TemplateRecipeHandler.RecipeTransferRectHandler.registerRectsToGuis(guis, rects);
        if (com.sva.init.ModContentSVA.adPart != null) {
            API.hideItem(new net.minecraft.item.ItemStack(com.sva.init.ModContentSVA.adPart, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE));
        }
    }

    @Override
    public String getName() {
        return "Silicon Age: Vehicles";
    }

    @Override
    public String getVersion() {
        return ReferenceSVA.VERSION;
    }
}
