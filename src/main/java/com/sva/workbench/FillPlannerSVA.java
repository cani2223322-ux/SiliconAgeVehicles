package com.sva.workbench;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

/**
 * «Разложить»: which available stack feeds each grid cell of a recipe layout (pure - no world, no inventory
 * changes; the self-test checks it). One item per cell.
 */
public final class FillPlannerSVA {

    public static final int EMPTY = -2, MISSING = -1;

    private FillPlannerSVA() {
    }

    /**
     * @param cells     the layout (WorkbenchRecipeSVA.layout()), null = empty cell
     * @param available stacks to take from (nulls allowed); only their counts are read
     * @return per cell: the index in `available` it takes one item from, MISSING, or EMPTY
     */
    public static int[] plan(Object[] cells, ItemStack[] available) {
        int[] left = new int[available.length];
        for (int i = 0; i < available.length; i++) {
            left[i] = available[i] == null ? 0 : available[i].stackSize;
        }
        int[] out = new int[cells.length];
        for (int c = 0; c < cells.length; c++) {
            if (cells[c] == null) {
                out[c] = EMPTY;
                continue;
            }
            out[c] = MISSING;
            for (int i = 0; i < available.length; i++) {
                if (left[i] > 0 && WorkbenchRecipeSVA.matches(cells[c], available[i])) {
                    left[i]--;
                    out[c] = i;
                    break;
                }
            }
        }
        return out;
    }

    public static boolean complete(int[] plan) {
        for (int p : plan) {
            if (p == MISSING) {
                return false;
            }
        }
        return true;
    }

    /** One row of the «Нужно для сборки» list: an ingredient, how many the recipe needs and how many are there. */
    public static final class Need {
        public final Object ingredient;
        public int need, have;

        Need(Object ingredient) {
            this.ingredient = ingredient;
        }

        public boolean ok() {
            return have >= need;
        }
    }

    /** Ingredients in layout order (the same ingredient object = one row) with need / have from a plan. */
    public static List<Need> needs(Object[] cells, int[] plan) {
        List<Need> l = new ArrayList<Need>();
        for (int c = 0; c < cells.length; c++) {
            if (cells[c] == null) {
                continue;
            }
            Need n = null;
            for (Need e : l) {
                if (e.ingredient == cells[c] || e.ingredient.equals(cells[c]) && cells[c] instanceof String) {
                    n = e;
                    break;
                }
            }
            if (n == null) {
                n = new Need(cells[c]);
                l.add(n);
            }
            n.need++;
            if (plan[c] >= 0) {
                n.have++;
            }
        }
        return l;
    }

    /** The rows still short. */
    public static List<Need> missing(List<Need> needs) {
        List<Need> l = new ArrayList<Need>();
        for (Need n : needs) {
            if (!n.ok()) {
                l.add(n);
            }
        }
        return l;
    }
}
