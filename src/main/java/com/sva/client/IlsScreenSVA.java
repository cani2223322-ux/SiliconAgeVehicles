package com.sva.client;

import com.sva.vehicle.LinerIls;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 4: «Курсо-глиссадный маяк»'s screen as drawing only (GuiIlsSVA is the GuiScreen around it): the values, the
 * buttons' rectangles and paint() with the liner's 2D kit (LinerGfxSVA) - the standalone preview draws it without
 * Minecraft's screen classes.
 */
@SideOnly(Side.CLIENT)
final class IlsScreenSVA {

    private IlsScreenSVA() {
    }

    static final int W = 264, H = 182;
    static final int BG = 0x1B1F27, EDGE = 0x0B0D11, LINE = 0x3A4150, GOLD = 0xF2A040, TEXT = 0xD8DCE4, MUTED = 0x8A93A3, VALUE = 0x46E87A,
            BTN = 0x2E3542, BTN_EDGE = 0x5A6476, CYAN = 0x5ADCFF;
    /** The screen's values (the preview builds one too). */
    static final class View {
        int course, angle10 = 30, freq = LinerIls.FREQ_DEFAULT, offset, elev;
        String ident = "";
        boolean identFocus;
        double range = 4000;
    }

    /** Buttons: id, x0, y0, x1, y1 (screen units from the panel's corner). */
    static final int B_CRS_DN = 0, B_CRS_UP = 1, B_LOOK = 2, B_ANG_DN = 3, B_ANG_UP = 4, B_IDENT = 5, B_FRQ_DN = 6, B_FRQ_UP = 7, B_OFF_DN = 8,
            B_OFF_UP = 9, B_SAVE = 10, B_CANCEL = 11;
    static final int[][] BUTTONS = {{B_CRS_DN, 112, 26, 130, 42}, {B_CRS_UP, 196, 26, 214, 42}, {B_LOOK, 218, 26, 256, 42},
            {B_ANG_DN, 112, 50, 130, 66}, {B_ANG_UP, 196, 50, 214, 66}, {B_IDENT, 112, 74, 214, 90}, {B_FRQ_DN, 112, 98, 130, 114},
            {B_FRQ_UP, 196, 98, 214, 114}, {B_OFF_DN, 112, 122, 130, 138}, {B_OFF_UP, 196, 122, 214, 138}, {B_SAVE, 60, 158, 134, 176},
            {B_CANCEL, 140, 158, 214, 176}};

    static String tr(String k, Object... a) {
        return LinerDisplaysSVA.trAny("sva.ils.gui." + k, a);
    }

    /** The whole screen in its own units (0..W x 0..H), the mouse at (mx, my) for the hover. */
    static void paint(View v, float mx, float my) {
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.rect(-1, -1, W + 1, H + 1, EDGE);
        LinerGfxSVA.rect(0, 0, W, H, BG);
        LinerGfxSVA.layer();
        LinerGfxSVA.rect(3, 17, W - 3, 18, 0xE08A30);
        LinerGfxSVA.text(tr("title"), 8, 9, 9, GOLD, -1, W - 70);
        LinerGfxSVA.text(v.ident.length() > 0 ? v.ident : "---", W - 8, 9, 9, CYAN, 1, 60);
        String[] labels = {tr("course"), tr("angle"), tr("ident"), tr("freq"), tr("offset")};
        String[] values = {String.format("%03d", Integer.valueOf(v.course)), LinerGfxSVA.num(v.angle10 / 10.0, 1), "",
                LinerIls.freqText(v.freq).replace('.', ','), (v.offset > 0 ? "+" : "") + v.offset + " " + tr("blocks")};
        for (int i = 0; i < 5; i++) {
            float y = 34 + i * 24;
            LinerGfxSVA.text(labels[i], 10, y, 8, TEXT, -1, 98);
            if (i != 2) {
                LinerGfxSVA.rect(132, y - 8, 194, y + 8, 0x050607);
                LinerGfxSVA.layer();
                LinerGfxSVA.text(values[i] + (i == 0 || i == 1 ? " " + tr("deg") : ""), 163, y, 9, VALUE, 0, 58);
            }
        }
        // the course's arrow: the landing direction on a little compass (north up)
        float cx = 236, cy = 58, r = 9;
        LinerGfxSVA.arc(cx, cy, r, 0, 360, 1F, LINE);
        LinerGfxSVA.layer();
        float a = v.course;
        LinerGfxSVA.line(LinerGfxSVA.px(cx, r - 1, a + 180), LinerGfxSVA.py(cy, r - 1, a + 180), LinerGfxSVA.px(cx, r - 1, a), LinerGfxSVA.py(cy, r - 1, a),
                1.4F, VALUE);
        LinerGfxSVA.tri(LinerGfxSVA.px(cx, r + 1, a), LinerGfxSVA.py(cy, r + 1, a), LinerGfxSVA.px(cx, r - 4, a - 25), LinerGfxSVA.py(cy, r - 4, a - 25),
                LinerGfxSVA.px(cx, r - 4, a + 25), LinerGfxSVA.py(cy, r - 4, a + 25), VALUE);
        LinerGfxSVA.text("N", cx, cy - r - 4, 6, MUTED, 0);
        for (int[] b : BUTTONS) {
            boolean hov = mx >= b[1] && mx < b[3] && my >= b[2] && my < b[4];
            String t;
            switch (b[0]) {
                case B_LOOK:
                    t = tr("look");
                    break;
                case B_IDENT:
                    t = v.ident + (v.identFocus && (System.currentTimeMillis() / 400) % 2 == 0 ? "_" : "");
                    break;
                case B_SAVE:
                    t = tr("save");
                    break;
                case B_CANCEL:
                    t = tr("cancel");
                    break;
                default:
                    t = b[0] == B_CRS_DN || b[0] == B_ANG_DN || b[0] == B_FRQ_DN || b[0] == B_OFF_DN ? "-" : "+";
                    break;
            }
            int face = b[0] == B_IDENT ? 0x050607 : hov ? 0x3A4252 : BTN;
            int edge = b[0] == B_IDENT && v.identFocus ? CYAN : hov ? 0x7A8496 : BTN_EDGE;
            LinerGfxSVA.rect(b[1], b[2], b[3], b[4], edge);
            LinerGfxSVA.layer();
            LinerGfxSVA.rect(b[1] + 1, b[2] + 1, b[3] - 1, b[4] - 1, face);
            LinerGfxSVA.layer();
            LinerGfxSVA.text(t, (b[1] + b[3]) / 2F, (b[2] + b[4]) / 2F, b[0] == B_IDENT ? 9 : 8, b[0] == B_IDENT ? CYAN : TEXT, 0, b[3] - b[1] - 4);
        }
        LinerGfxSVA.text(tr("elev", Integer.valueOf(v.elev), Integer.valueOf((int) v.range)), 10, 150, 7, MUTED, -1, W - 20);
        LinerGfxSVA.text(tr("hint"), 222, 82, 6, MUTED, -1, 38);
    }

}
