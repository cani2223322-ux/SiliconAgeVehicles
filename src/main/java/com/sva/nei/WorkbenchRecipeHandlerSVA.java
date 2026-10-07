package com.sva.nei;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sc.energy.Tier;
import com.sc.manual.Lang;
import com.sva.FormatSVA;
import com.sva.init.ModContentSVA;
import com.sva.workbench.WorkbenchMath;
import com.sva.workbench.WorkbenchNeiLayoutSVA;
import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import codechicken.lib.gui.GuiDraw;
import codechicken.nei.NEIServerUtils;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.TemplateRecipeHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * NEI page «Инженерный верстак»: one Engineering Workbench recipe per page - the centred 5x5 grid (the workbench
 * screen's own layout, WorkbenchNeiLayoutSVA), the output, the workbench block, and under them the cost
 * («200 тыс. EU · 30 с» + the input tier and transformers, or «Мгновенно, без EU»). R on a product, U on an
 * ingredient (ore-dictionary ones cycle) or on the workbench itself; the arrow lists every workbench recipe.
 * Only NEISiliconAgeVehiclesConfig touches this class, so it is never loaded without NEI.
 */
public class WorkbenchRecipeHandlerSVA extends TemplateRecipeHandler {

    /** Overlay / transfer-rect id shared with the workbench screen's registration. */
    static final String ID = "sva.workbench";

    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, SLOT = 0xFF2B313C, SLOT_USED = 0xFF343C4A,
            SLOT_DARK = 0xFF0D1015, CYAN = 0xFF5EC8F0, GREEN = 0x62E06A, TEXT = 0xD8DCE4, MUTED = 0x8A93A3, YELLOW = 0xF0D060;
    private static final int PAGE_H = 118, LINE1_Y = 98, LINE2_Y = 108;

    @Override
    public void loadTransferRects() {
        transferRects.add(new RecipeTransferRect(new Rectangle(WorkbenchNeiLayoutSVA.ARROW_X, WorkbenchNeiLayoutSVA.ARROW_Y - 3,
                WorkbenchNeiLayoutSVA.ARROW_W + 4, 14), ID));
    }

    @Override
    public String getRecipeName() {
        return Lang.tr("sva.nei.workbench");
    }

    @Override
    public String getGuiTexture() {
        return "textures/gui/container/crafting_table.png";     // never drawn: drawBackground paints the page
    }

    @Override
    public String getOverlayIdentifier() {
        return ID;
    }

    @Override
    public int recipiesPerPage() {
        return 1;
    }

    /** The workbench screen's own arrow is registered once in NEISiliconAgeVehiclesConfig (another layout). */
    @Override
    public List<Class<? extends GuiContainer>> getRecipeTransferRectGuis() {
        return new ArrayList<Class<? extends GuiContainer>>();
    }

    /** The recipe shown as `recipe` on this handler's pages (the overlay handler fills the grid from it). */
    WorkbenchRecipeSVA recipeAt(int recipe) {
        return recipe >= 0 && recipe < arecipes.size() ? ((CachedWorkbenchRecipe) arecipes.get(recipe)).recipe : null;
    }

    private static ItemStack workbench() {
        return new ItemStack(ModContentSVA.workbench);
    }

    // ------------------------------------------------------------------ lookups

    @Override
    public void loadCraftingRecipes(String outputId, Object... results) {
        if (ID.equals(outputId)) {
            for (WorkbenchRecipeSVA r : WorkbenchRecipesSVA.all()) {
                arecipes.add(new CachedWorkbenchRecipe(r));
            }
        } else {
            super.loadCraftingRecipes(outputId, results);
        }
    }

    @Override
    public void loadCraftingRecipes(ItemStack result) {
        if (result == null) {
            return;
        }
        for (WorkbenchRecipeSVA r : WorkbenchRecipesSVA.all()) {
            if (NEIServerUtils.areStacksSameTypeCrafting(r.display(), result)) {
                arecipes.add(new CachedWorkbenchRecipe(r));
            }
        }
    }

    @Override
    public void loadUsageRecipes(ItemStack ingredient) {
        if (ingredient == null) {
            return;
        }
        if (NEIServerUtils.areStacksSameTypeCrafting(ingredient, workbench())) {
            loadCraftingRecipes(ID);
            return;
        }
        for (WorkbenchRecipeSVA r : WorkbenchRecipesSVA.all()) {
            for (WorkbenchNeiLayoutSVA.Cell c : WorkbenchNeiLayoutSVA.cells(r)) {
                if (WorkbenchRecipeSVA.matches(c.ingredient, ingredient)) {
                    CachedWorkbenchRecipe cr = new CachedWorkbenchRecipe(r);
                    cr.setIngredientPermutation(cr.ingredients, ingredient);
                    arecipes.add(cr);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private static void rect(int x, int y, int w, int h, int c) {
        if (w > 0 && h > 0) {
            Gui.drawRect(x, y, x + w, y + h, c);
        }
    }

    private static void frame(int x, int y, int w, int h, int border, int fill) {
        rect(x, y, w, h, border);
        rect(x + 1, y + 1, w - 2, h - 2, fill);
    }

    /** The workbench screen's dark look: panel, 25 slot pockets (used ones lighter), arrow, framed output. */
    @Override
    public void drawBackground(int recipe) {
        CachedWorkbenchRecipe r = (CachedWorkbenchRecipe) arecipes.get(recipe);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        frame(0, 0, 166, PAGE_H, EDGE, BG);
        int gx = WorkbenchNeiLayoutSVA.GRID_X, gy = WorkbenchNeiLayoutSVA.GRID_Y, st = WorkbenchNeiLayoutSVA.STEP;
        frame(gx - 4, gy - 3, 5 * st + 6, 5 * st + 5, EDGE, PANEL);
        boolean[] used = new boolean[WorkbenchRecipeSVA.CELLS];
        for (WorkbenchNeiLayoutSVA.Cell c : WorkbenchNeiLayoutSVA.cells(r.recipe)) {
            used[c.index] = true;
        }
        for (int i = 0; i < WorkbenchRecipeSVA.CELLS; i++) {
            frame(gx + (i % 5) * st - 1, gy + (i / 5) * st - 1, 18, 18, SLOT_DARK, used[i] ? SLOT_USED : SLOT);
        }
        // the arrow: fills over the assembly's time (a fixed beat for instant recipes)
        int ax = WorkbenchNeiLayoutSVA.ARROW_X, ay = WorkbenchNeiLayoutSVA.ARROW_Y, aw = WorkbenchNeiLayoutSVA.ARROW_W - 6;
        int colour = r.recipe.instant() ? 0xFF62E06A : CYAN;
        frame(ax, ay, aw + 2, 8, EDGE, 0xFF0E1116);
        rect(ax + 1, ay + 1, (cycleticks % 40) * aw / 40, 6, colour);
        for (int i = 0; i < 6; i++) {
            rect(ax + aw + 2 + i, ay - 2 + i, 1, 12 - 2 * i, colour);
        }
        int ox = WorkbenchNeiLayoutSVA.OUT_X, oy = WorkbenchNeiLayoutSVA.OUT_Y;
        rect(ox - 5, oy - 5, 26, 26, CYAN);
        rect(ox - 4, oy - 4, 24, 24, 0xFF151920);
        frame(ox - 1, oy - 1, 18, 18, SLOT_DARK, SLOT);
        // tier chip over the output
        Tier t = r.recipe.tier;
        String tn = t.name();
        int cw = Minecraft.getMinecraft().fontRenderer.getStringWidth(tn) + 4;
        rect(ox + 8 - cw / 2, oy - 17, cw, 9, tierColour(t));
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    static int tierColour(Tier t) {
        switch (t) {
            case LV: return 0xFFB0B8C0;
            case MV: return 0xFFF0B838;
            case HV: return 0xFFF08A30;
            case EV: return 0xFFB070F0;
            case IV: return 0xFF8090A0;
            case QV: return 0xFF4AB8F0;
            case XV: return 0xFF50E0B0;
            default: return 0xFFF060D0;
        }
    }

    @Override
    public void drawExtras(int recipe) {
        WorkbenchRecipeSVA r = ((CachedWorkbenchRecipe) arecipes.get(recipe)).recipe;
        int ox = WorkbenchNeiLayoutSVA.OUT_X, oy = WorkbenchNeiLayoutSVA.OUT_Y;
        String tn = r.tier.name();
        int cw = Minecraft.getMinecraft().fontRenderer.getStringWidth(tn) + 4;
        GuiDraw.drawString(tn, ox + 8 - cw / 2 + 2, oy - 16, 0x1A1A1A, false);
        if (r.instant()) {
            drawFit(Lang.tr("sva.nei.instant"), 4, LINE1_Y, 158, GREEN);
            drawFit(Lang.tr("sva.nei.category", Lang.tr("sva.wb.cat." + r.category.ordinal())), 4, LINE2_Y, 158, MUTED);
        } else {
            drawFit(Lang.tr("sva.nei.cost", FormatSVA.eu(r.eu), Integer.valueOf(FormatSVA.seconds(r.ticks))), 4, LINE1_Y, 158, TEXT);
            int n = WorkbenchMath.transformersFor(r.tier);
            String need = WorkbenchMath.requiredInput(r.tier).name();
            drawFit(n > 0 ? Lang.tr("sva.nei.input", need, Integer.valueOf(n)) : Lang.tr("sva.nei.input.base", need), 4, LINE2_Y, 158,
                    n > 0 ? YELLOW : MUTED);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** A line scaled down (never up) to fit maxW. */
    private static void drawFit(String s, int x, int y, int maxW, int color) {
        int w = Minecraft.getMinecraft().fontRenderer.getStringWidth(s);
        if (w <= maxW) {
            GuiDraw.drawString(s, x, y, color, false);
            return;
        }
        float k = maxW / (float) w;
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y + 4F * (1F - k), 0F);
        GL11.glScalef(k, k, 1F);
        GuiDraw.drawString(s, 0, 0, color, false);
        GL11.glPopMatrix();
    }

    // ------------------------------------------------------------------ one page

    public class CachedWorkbenchRecipe extends CachedRecipe {

        final WorkbenchRecipeSVA recipe;
        final List<PositionedStack> ingredients = new ArrayList<PositionedStack>();
        final List<PositionedStack> others = new ArrayList<PositionedStack>();
        final PositionedStack result;

        CachedWorkbenchRecipe(WorkbenchRecipeSVA recipe) {
            this.recipe = recipe;
            for (WorkbenchNeiLayoutSVA.Cell c : WorkbenchNeiLayoutSVA.cells(recipe)) {
                Object shown = alternatives(c.ingredient);
                if (shown != null) {
                    ingredients.add(new PositionedStack(shown, c.x, c.y));
                }
            }
            result = new PositionedStack(recipe.display(), WorkbenchNeiLayoutSVA.OUT_X, WorkbenchNeiLayoutSVA.OUT_Y);
            others.add(new PositionedStack(workbench(), WorkbenchNeiLayoutSVA.STATION_X, WorkbenchNeiLayoutSVA.STATION_Y));
        }

        @Override
        public PositionedStack getResult() {
            return result;
        }

        @Override
        public List<PositionedStack> getIngredients() {
            return getCycledIngredients(cycleticks / 20, ingredients);
        }

        @Override
        public List<PositionedStack> getOtherStacks() {
            return others;
        }
    }

    /** An ingredient as NEI shows it: a stack (NEI expands a wildcard damage), or every ore-dictionary item of a name. */
    private static Object alternatives(Object ing) {
        if (ing instanceof ItemStack) {
            ItemStack s = ((ItemStack) ing).copy();
            s.stackSize = 1;
            return s;
        }
        if (ing instanceof String) {
            List<ItemStack> list = new ArrayList<ItemStack>();
            ItemStack first = WorkbenchRecipeSVA.displayOf(ing);   // the Silicon Age item first, as the screen shows it
            if (first != null) {
                list.add(first);
            }
            for (ItemStack o : OreDictionary.getOres((String) ing)) {
                if (o != null && o.getItem() != null && (first == null || !o.isItemEqual(first))) {
                    ItemStack c = o.copy();
                    c.stackSize = 1;
                    list.add(c);
                }
            }
            return list.isEmpty() ? null : list;
        }
        return null;
    }
}
