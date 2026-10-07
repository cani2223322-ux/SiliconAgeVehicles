package com.sva.workbench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sc.energy.Tier;
import com.sva.item.ItemVehicleSVA;
import com.sva.vehicle.VehicleState;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * A shaped Engineering Workbench recipe on the 5x5 grid. The pattern is trimmed to its used cells and matched
 * wherever it fits (so a 3x3 works anywhere, a full 5x5 only in place), plain or mirrored. Ingredients:
 * ItemStack (damage may be the wildcard; NBT ignored) or an ore-dictionary name. Each cell takes one item.
 * eu == 0 and ticks == 0: instant (taken from the output like a crafting table); else assembled over time.
 */
public final class WorkbenchRecipeSVA {

    public static final int GRID = 5, CELLS = GRID * GRID;

    public enum Category {
        COMPONENTS, VEHICLES, BLOCKS, MODULES
    }

    public final String id;
    public final Category category;
    private final ItemStack output;
    public final Tier tier;
    public final long eu;
    public final int ticks;
    public final int width, height;
    /** Row-major width x height, null = must stay empty. */
    private final Object[] cells;

    public WorkbenchRecipeSVA(String id, Category category, ItemStack output, Tier tier, long eu, int ticks, Object... pattern) {
        this.id = id;
        this.category = category;
        this.output = output;
        this.tier = tier;
        this.eu = Math.max(0, eu);
        this.ticks = Math.max(0, ticks);
        // rows first, then char / ingredient pairs (as GameRegistry.addRecipe)
        List<String> rows = new ArrayList<String>();
        int i = 0;
        while (i < pattern.length && pattern[i] instanceof String) {
            rows.add((String) pattern[i]);
            i++;
        }
        Map<Character, Object> keys = new HashMap<Character, Object>();
        for (; i + 1 < pattern.length; i += 2) {
            keys.put((Character) pattern[i], ingredient(pattern[i + 1]));
        }
        int h0 = rows.size(), w0 = 0;
        for (String r : rows) {
            w0 = Math.max(w0, r.length());
        }
        if (h0 == 0 || h0 > GRID || w0 > GRID) {
            throw new IllegalArgumentException("workbench recipe " + id + ": pattern must be 1..5 rows of 1..5");
        }
        Object[][] raw = new Object[h0][w0];
        int minX = w0, maxX = -1, minY = h0, maxY = -1;
        for (int y = 0; y < h0; y++) {
            String r = rows.get(y);
            for (int x = 0; x < r.length(); x++) {
                char c = r.charAt(x);
                if (c == ' ') {
                    continue;
                }
                Object ing = keys.get(Character.valueOf(c));
                if (ing == null) {
                    throw new IllegalArgumentException("workbench recipe " + id + ": no ingredient for '" + c + "'");
                }
                raw[y][x] = ing;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) {
            throw new IllegalArgumentException("workbench recipe " + id + ": empty pattern");
        }
        width = maxX - minX + 1;
        height = maxY - minY + 1;
        cells = new Object[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cells[y * width + x] = raw[minY + y][minX + x];
            }
        }
    }

    /** Generic materials (ingots, rubber) become their shared ore name, as the core's own recipes do. */
    private static Object ingredient(Object o) {
        if (o instanceof Item) {
            return new ItemStack((Item) o);
        }
        if (o instanceof net.minecraft.block.Block) {
            return new ItemStack((net.minecraft.block.Block) o);
        }
        if (o instanceof ItemStack) {
            try {
                return com.sc.init.OreRecipes.oreName(((ItemStack) o).copy());
            } catch (Throwable t) {
                return ((ItemStack) o).copy();
            }
        }
        if (o instanceof String) {
            return o;
        }
        throw new IllegalArgumentException("bad workbench ingredient " + o);
    }

    public boolean instant() {
        return eu <= 0 && ticks <= 0;
    }

    /** A fresh product (a vehicle gets its full default state). */
    public ItemStack result() {
        ItemStack s = output.copy();
        if (s.getItem() instanceof ItemVehicleSVA) {
            ItemVehicleSVA.withState(s, new VehicleState(ItemVehicleSVA.typeOf(s)));
        }
        return s;
    }

    /** The product as shown (no fresh state). */
    public ItemStack display() {
        return output.copy();
    }

    public Object cell(int x, int y) {
        return cells[y * width + x];
    }

    // ------------------------------------------------------------------ matching

    /**
     * Where this recipe sits in the grid: {offsetX, offsetY, mirrored 0/1}, or null. The used cells' bounding
     * box must be exactly the pattern's.
     */
    public int[] match(ItemStack[] grid) {
        int minX = GRID, maxX = -1, minY = GRID, maxY = -1;
        for (int i = 0; i < CELLS; i++) {
            if (grid[i] != null) {
                int x = i % GRID, y = i / GRID;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0 || maxX - minX + 1 != width || maxY - minY + 1 != height) {
            return null;
        }
        for (int m = 0; m < 2; m++) {
            if (fits(grid, minX, minY, m == 1)) {
                return new int[] {minX, minY, m};
            }
        }
        return null;
    }

    private boolean fits(ItemStack[] grid, int ox, int oy, boolean mirror) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Object want = cell(mirror ? width - 1 - x : x, y);
                ItemStack have = grid[(oy + y) * GRID + ox + x];
                if (want == null ? have != null : !matches(want, have)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Grid slots that hold an ingredient for placement `m` (from match). */
    public int[] usedSlots(int[] m) {
        List<Integer> l = new ArrayList<Integer>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (cell(m[2] == 1 ? width - 1 - x : x, y) != null) {
                    l.add(Integer.valueOf((m[1] + y) * GRID + m[0] + x));
                }
            }
        }
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = l.get(i).intValue();
        }
        return r;
    }

    /** The 25 grid cells as the GUI lays the recipe out (centred; a 5x5 fills the grid). */
    public Object[] layout() {
        Object[] out = new Object[CELLS];
        int ox = (GRID - width) / 2, oy = (GRID - height) / 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[(oy + y) * GRID + ox + x] = cell(x, y);
            }
        }
        return out;
    }

    public static boolean matches(Object want, ItemStack have) {
        if (have == null || have.getItem() == null || want == null) {
            return false;
        }
        if (have.getItem() instanceof com.sva.item.ItemTrailerSVA && com.sva.item.ItemTrailerSVA.hasContents(have)) {
            return false;                                   // ★15: a trailer with cargo / contents is never an ingredient
        }
        if (want instanceof ItemStack) {
            return OreDictionary.itemMatches((ItemStack) want, have, false);
        }
        if (want instanceof String) {
            for (ItemStack o : OreDictionary.getOres((String) want)) {
                if (OreDictionary.itemMatches(o, have, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A stack to show for an ingredient (an ore name: the Silicon Age item first). */
    public static ItemStack displayOf(Object ing) {
        if (ing instanceof ItemStack) {
            ItemStack s = ((ItemStack) ing).copy();
            if (s.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                s.setItemDamage(0);
            }
            s.stackSize = 1;
            return s;
        }
        if (ing instanceof String) {
            List<ItemStack> ores = OreDictionary.getOres((String) ing);
            ItemStack first = null;
            for (ItemStack o : ores) {
                if (o == null || o.getItem() == null) {
                    continue;
                }
                if (first == null) {
                    first = o;
                }
                GameRegistry.UniqueIdentifier u = GameRegistry.findUniqueIdentifierFor(o.getItem());
                if (u != null && "SiliconAge".equals(u.modId)) {
                    first = o;
                    break;
                }
            }
            if (first != null) {
                ItemStack s = first.copy();
                if (s.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                    s.setItemDamage(0);
                }
                s.stackSize = 1;
                return s;
            }
        }
        return null;
    }
}
