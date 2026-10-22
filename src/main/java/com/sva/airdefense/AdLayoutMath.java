package com.sva.airdefense;

/**
 * Audit review 2026-10-22: where a few of the air defence's screen pieces go - pure, the self-test checks it (the client
 * classes draw with it and the dedicated server cannot load them).
 * <ul>
 * <li>the command post's 7600 panel: the three decision buttons (Свой / Проверить / Враждебный) take the room right of the
 * row's text («01 7600 1:59») - moved left for the third button, they covered the countdown, the very thing the operator
 * reads there;</li>
 * <li>the transponder line on the HUD (third person, the live instruments off) of the shuttle and the Singular ship goes
 * under their space panel (oxygen, jump, anchor) in the top right corner - it was drawn over its rows.</li>
 * </ul>
 */
public final class AdLayoutMath {

    /** The 7600 buttons' shares of the room (as their old widths 40 / 62 / 42) and the gap between them. */
    public static final int[] NR_PARTS = {40, 62, 42};
    public static final int NR_GAP = 2;

    private AdLayoutMath() {
    }

    /**
     * The 7600 row's buttons: {x, width} each, from 4 pixels right of the row's text (`textRight`) to the panel's right edge
     * `x1`, the room split by NR_PARTS (the last one to the edge).
     */
    public static int[][] nrButtons(int textRight, int x1) {
        int sum = 0;
        for (int p : NR_PARTS) {
            sum += p;
        }
        int x = textRight + 4, room = Math.max(0, x1 - x - NR_GAP * (NR_PARTS.length - 1));
        int[][] out = new int[NR_PARTS.length][];
        for (int i = 0; i < NR_PARTS.length; i++) {
            int w = i == NR_PARTS.length - 1 ? Math.max(0, x1 - x) : room * NR_PARTS[i] / sum;
            out[i] = new int[] {x, w};
            x += w + NR_GAP;
        }
        return out;
    }

    /** The shuttle's / ship's space panel (HudVehicleSVA.drawSpace): its rows - oxygen and riders, the dock, the ship's five. */
    public static int spaceRows(boolean dock, boolean ship) {
        return 2 + (dock ? 1 : 0) + (ship ? 5 : 0);
    }

    /** The space panel's lowest pixel row: it starts at y 4, rows x 11 + 8 tall, a 1-pixel edge round it. */
    public static int spacePanelBottom(boolean dock, boolean ship) {
        return 4 + spaceRows(dock, ship) * 11 + 8 + 1;
    }

    /** The transponder line's y (its box from y - 2): below a panel ending at `panelBottom` (0 - none). */
    public static int xpdrLineY(int y, int panelBottom) {
        return panelBottom <= 0 ? y : Math.max(y, panelBottom + 4);
    }
}
