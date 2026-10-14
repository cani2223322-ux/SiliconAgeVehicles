package com.sva.client;

import com.sva.vehicle.LinerMcdu;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 5: draws an MCDU screen (LinerMcdu.Screen - what the server rendered) through LinerGfxSVA, so the keyboard
 * screen (GuiMcduSVA), the 3D cockpit's two MCDU faces (LinerCockpitSVA) and the standalone preview show the same thing:
 * a monospace grid of 24 x 14 cells (LinerMcdu.Screen.cells - each character centred in its cell), labels small, data
 * big, the mandatory fields as amber boxes drawn (not a glyph), the A320's colours.
 */
@SideOnly(Side.CLIENT)
public final class McduScreenSVA {

    /** The MCDU's colours by LinerMcdu.C_*: white, cyan, green, amber, magenta, yellow, the boxes' amber. */
    static final int[] RGB = {0xF2F2F6, 0x5ADCFF, 0x46E87A, 0xFFA828, 0xE66EE6, 0xFFE040, 0xFFA828};

    private McduScreenSVA() {
    }

    /** The client's resolver: the lang file (the preview's map when set). */
    static final LinerMcdu.Resolver LANG = new LinerMcdu.Resolver() {
        @Override
        public String tr(String key, String[] args) {
            return LinerSdPagesSVA.lang(key, (Object[]) args);
        }
    };

    /** The screen at (x0, y0) w x h (the caller's units, y down); null screen: the dark glass with a dash line. */
    public static void draw(LinerMcdu.Screen sc, float x0, float y0, float w, float h, boolean powered) {
        LinerGfxSVA.rect(x0, y0, x0 + w, y0 + h, powered ? 0x030405 : 0x010101);
        if (!powered) {
            return;
        }
        LinerGfxSVA.layer();
        float cw = w / LinerMcdu.COLS, ch = h / LinerMcdu.ROWS;
        if (sc == null) {
            LinerGfxSVA.text("- - -", x0 + w / 2, y0 + h / 2, ch * 0.9F, 0x6A6E76, 0);
            return;
        }
        LinerMcdu.Cells c = sc.cells(LANG);
        for (int r = 0; r < LinerMcdu.ROWS; r++) {
            float cy = y0 + (r + 0.5F) * ch;
            for (int k = 0; k < LinerMcdu.COLS; k++) {
                char t = c.ch[r][k];
                if (t == ' ') {
                    continue;
                }
                int col = c.col[r][k];
                float cx = x0 + (k + 0.5F) * cw;
                if (col == LinerMcdu.C_BOX) {
                    float bw = cw * 0.36F, bh = ch * 0.34F;
                    LinerGfxSVA.frameRect(cx - bw, cy - bh, cx + bw, cy + bh, Math.max(0.6F, cw * 0.09F), RGB[col]);
                    continue;
                }
                boolean small = c.small[r][k];
                float hh = ch * (small ? 0.68F : 0.92F);
                LinerGfxSVA.text(String.valueOf(t), cx, cy, hh, RGB[Math.max(0, Math.min(RGB.length - 1, col))], 0, cw * (small ? 0.85F : 0.98F));
            }
        }
    }
}
