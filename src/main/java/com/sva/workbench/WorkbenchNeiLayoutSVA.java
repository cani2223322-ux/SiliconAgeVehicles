package com.sva.workbench;

import java.util.ArrayList;
import java.util.List;

import com.sva.inventory.ContainerWorkbenchSVA;

/**
 * Where a workbench recipe's cells go on its NEI page (pure, no NEI classes - the self-test checks it and the NEI
 * handler com.sva.nei.WorkbenchRecipeHandlerSVA draws from it). The page shows the same centred 5x5 layout as the
 * workbench screen's ghost items (WorkbenchRecipeSVA.layout()), on an 18-pixel grid, so NEI's ghost overlay maps
 * each page slot onto the workbench grid slot by a fixed offset (GHOST_DX / GHOST_DY).
 */
public final class WorkbenchNeiLayoutSVA {

    /** Page coordinates (relative to NEI's recipe origin, 166 wide) of the grid's top-left item and the output. */
    public static final int GRID_X = 8, GRID_Y = 4, STEP = 18, OUT_X = 127, OUT_Y = GRID_Y + 2 * STEP,
            STATION_X = 127, STATION_Y = GRID_Y + 4 * STEP, ARROW_X = 101, ARROW_Y = OUT_Y + 4, ARROW_W = 22;
    /** Page item position -> workbench screen slot position. */
    public static final int GHOST_DX = ContainerWorkbenchSVA.GRID_X - GRID_X, GHOST_DY = ContainerWorkbenchSVA.GRID_Y - GRID_Y;

    private WorkbenchNeiLayoutSVA() {
    }

    /** One used grid cell: its index 0..24, column / row in the 5x5 grid, page position and the ingredient. */
    public static final class Cell {
        public final int index, col, row, x, y;
        public final Object ingredient;

        Cell(int index, Object ingredient) {
            this.index = index;
            this.col = index % WorkbenchRecipeSVA.GRID;
            this.row = index / WorkbenchRecipeSVA.GRID;
            this.x = GRID_X + col * STEP;
            this.y = GRID_Y + row * STEP;
            this.ingredient = ingredient;
        }
    }

    /** The used cells of `r` in layout order (row-major), centred like the screen (a 5x5 fills the grid). */
    public static List<Cell> cells(WorkbenchRecipeSVA r) {
        Object[] l = r.layout();
        List<Cell> out = new ArrayList<Cell>();
        for (int i = 0; i < l.length; i++) {
            if (l[i] != null) {
                out.add(new Cell(i, l[i]));
            }
        }
        return out;
    }

    /** Top-left grid cell {col, row} of the centred pattern. */
    public static int[] origin(WorkbenchRecipeSVA r) {
        return new int[] {(WorkbenchRecipeSVA.GRID - r.width) / 2, (WorkbenchRecipeSVA.GRID - r.height) / 2};
    }

    /** Number of non-empty cells in the recipe's own (trimmed) pattern. */
    public static int patternCount(WorkbenchRecipeSVA r) {
        int n = 0;
        for (int y = 0; y < r.height; y++) {
            for (int x = 0; x < r.width; x++) {
                if (r.cell(x, y) != null) {
                    n++;
                }
            }
        }
        return n;
    }
}
