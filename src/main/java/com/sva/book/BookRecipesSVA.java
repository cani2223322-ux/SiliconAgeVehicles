package com.sva.book;

import java.util.List;

import com.sva.workbench.WorkbenchRecipeSVA;
import com.sva.workbench.WorkbenchRecipesSVA;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

/**
 * Wave C, item 44: the handbook's live recipes - a @recipe ref resolved against the recipes REGISTERED in this game (so the
 * book never shows a recipe that differs from the real one): «wb:ID» the Engineering Workbench's (WorkbenchRecipesSVA.byId -
 * its 5 x 5 pattern, EU, time, input tier), «craft:mod:name[:meta]» the crafting table's first recipe making that item (3 x 3,
 * shaped or shapeless, ore names kept as their lists). Common code: the server's self-test and the exporter resolve the same
 * refs the client draws.
 */
public final class BookRecipesSVA {

    private BookRecipesSVA() {
    }

    /** A resolved recipe: the grid (w x w cells: ItemStack, a List of ItemStacks, or null), its output and the workbench's data. */
    public static final class Grid {
        public final String ref;
        public boolean workbench, shapeless;
        public int w = 3;
        public Object[] cells = new Object[9];
        public ItemStack out;
        public long eu;
        public int ticks;
        public String tier = "";
        public String id = "";

        Grid(String ref) {
            this.ref = ref;
        }
    }

    /** The item of a ref "mod:name[:meta]" (a block's item too), or null. */
    public static ItemStack item(String ref) {
        String[] p = ref.split(":");
        if (p.length < 2) {
            return null;
        }
        int meta = 0;
        if (p.length > 2) {
            try {
                meta = Integer.parseInt(p[2]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String mod = modId(p[0]);
        Item it = GameRegistry.findItem(mod, p[1]);
        if (it == null) {
            Block b = GameRegistry.findBlock(mod, p[1]);
            it = b == null ? null : Item.getItemFromBlock(b);
        }
        return it == null ? null : new ItemStack(it, 1, meta);
    }

    /** The block of a ref "mod:name" (the book writes the mod's id in lower case), or null. */
    public static Block block(String ref) {
        int c = ref.indexOf(':');
        if (c < 0) {
            return null;
        }
        return GameRegistry.findBlock(modId(ref.substring(0, c)), ref.substring(c + 1));
    }

    /** A loaded mod's id as registered ("siliconagevehicles" -> "SiliconAgeVehicles"); "minecraft" and unknown ones as given. */
    public static String modId(String lower) {
        if ("minecraft".equals(lower)) {
            return lower;
        }
        if (lower.equalsIgnoreCase(com.sva.ReferenceSVA.MODID)) {
            return com.sva.ReferenceSVA.MODID;
        }
        for (String id : cpw.mods.fml.common.Loader.instance().getIndexedModList().keySet()) {
            if (id.equalsIgnoreCase(lower)) {
                return id;
            }
        }
        return lower;
    }

    /** The recipe of a ref, or null when it does not resolve. */
    public static Grid resolve(String ref) {
        if (ref == null) {
            return null;
        }
        if (ref.startsWith("wb:")) {
            WorkbenchRecipeSVA r = WorkbenchRecipesSVA.byId(ref.substring(3));
            if (r == null) {
                return null;
            }
            Grid g = new Grid(ref);
            g.workbench = true;
            g.w = WorkbenchRecipeSVA.GRID;
            g.cells = r.layout().clone();
            for (int i = 0; i < g.cells.length; i++) {
                g.cells[i] = cell(g.cells[i]);
            }
            g.out = r.display();
            g.eu = r.eu;
            g.ticks = r.ticks;
            g.tier = r.tier == null ? "" : r.tier.name();
            g.id = r.id;
            return g;
        }
        if (ref.startsWith("craft:")) {
            ItemStack want = item(ref.substring(6));
            if (want == null) {
                return null;
            }
            boolean anyMeta = ref.substring(6).split(":").length < 3;
            for (Object o : CraftingManager.getInstance().getRecipeList()) {
                IRecipe r = (IRecipe) o;
                ItemStack out = r.getRecipeOutput();
                if (out == null || out.getItem() != want.getItem() || !anyMeta && out.getItemDamage() != want.getItemDamage()) {
                    continue;
                }
                Grid g = grid(r, ref);
                if (g != null) {
                    return g;
                }
            }
        }
        return null;
    }

    /** The crafting table's grid of a recipe (null: an odd recipe class). */
    static Grid grid(IRecipe r, String ref) {
        Grid g = new Grid(ref);
        g.out = r.getRecipeOutput().copy();
        try {
            if (r instanceof ShapedRecipes) {
                ShapedRecipes s = (ShapedRecipes) r;
                for (int i = 0; i < s.recipeItems.length; i++) {
                    g.cells[(i / s.recipeWidth) * 3 + i % s.recipeWidth] = cell(s.recipeItems[i]);
                }
            } else if (r instanceof ShapedOreRecipe) {
                Object[] in = ((ShapedOreRecipe) r).getInput();
                int w = ((Integer) cpw.mods.fml.relauncher.ReflectionHelper.getPrivateValue(ShapedOreRecipe.class, (ShapedOreRecipe) r, "width")).intValue();
                for (int i = 0; i < in.length; i++) {
                    g.cells[(i / w) * 3 + i % w] = cell(in[i]);
                }
            } else if (r instanceof ShapelessRecipes) {
                List<?> in = ((ShapelessRecipes) r).recipeItems;
                g.shapeless = true;
                for (int i = 0; i < in.size() && i < 9; i++) {
                    g.cells[i] = cell(in.get(i));
                }
            } else if (r instanceof ShapelessOreRecipe) {
                List<?> in = ((ShapelessOreRecipe) r).getInput();
                g.shapeless = true;
                for (int i = 0; i < in.size() && i < 9; i++) {
                    g.cells[i] = cell(in.get(i));
                }
            } else {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        return g;
    }

    /** A cell as the book keeps it: an ItemStack (any-meta -> 0), a List of ItemStacks (an ore name), or null. */
    static Object cell(Object o) {
        if (o instanceof ItemStack) {
            ItemStack s = ((ItemStack) o).copy();
            if (s.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                s.setItemDamage(0);
            }
            s.stackSize = 1;
            return s;
        }
        if (o instanceof String) {
            List<ItemStack> l = OreDictionary.getOres((String) o);
            return l.isEmpty() ? null : l;
        }
        if (o instanceof List && !((List<?>) o).isEmpty()) {
            return o;
        }
        return null;
    }

    /** The stack a cell shows at time `ms` (a list cycles once a second), or null. */
    public static ItemStack pick(Object o, long ms) {
        if (o instanceof ItemStack) {
            return (ItemStack) o;
        }
        if (o instanceof List && !((List<?>) o).isEmpty()) {
            List<?> l = (List<?>) o;
            Object s = l.get((int) (ms / 1000L % l.size()));
            if (s instanceof ItemStack) {
                ItemStack c = ((ItemStack) s).copy();
                if (c.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                    c.setItemDamage(0);
                }
                return c;
            }
        }
        return null;
    }

    /** The box a recipe takes in the book {w, h} (GUI px): a caption line, the grid of 18 px slots, the workbench's info line. */
    public static int[] size(Grid g) {
        return g.workbench ? new int[] {g.w * 18 + 46, 10 + g.w * 18 + 14} : new int[] {3 * 18 + 46, 10 + 3 * 18 + 4};
    }
}
