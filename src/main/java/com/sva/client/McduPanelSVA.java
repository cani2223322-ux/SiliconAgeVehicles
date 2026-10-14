package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.LinerMcdu;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 5: the MCDU's keyboard panel - its keys (the A320's layout of mock-up veh_liner_14: 6 + 6 line keys, the
 * function keys in Russian, the arrows, the Cyrillic letters, the digits, «/», «+/-», СБР, ПРОЛ, ПРОБ) and its drawing in
 * the panel's own units (W x H) with LinerGfxSVA - the keyboard screen's (GuiMcduSVA) and the preview's.
 */
@SideOnly(Side.CLIENT)
public final class McduPanelSVA {

    private McduPanelSVA() {
    }

    public static final float W = 380, H = 590;
    /** The screen's place in the panel's units. */
    static final float SX0 = 52, SY0 = 14, SX1 = 328, SY1 = 230;
    /** Brightness per MCDU (ЯРК / ТУСК; the client's own). */
    static final float[] BRIGHT = {1F, 1F};

    /** A key: its box (panel units), the code it sends, its label (a lang key "sva..." or the text), triangle direction. */
    static final class Key {
        final float x0, y0, x1, y1;
        final int code;
        final String label;
        final int arrow;

        Key(float x0, float y0, float x1, float y1, int code, String label, int arrow) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.code = code;
            this.label = label;
            this.arrow = arrow;
        }
    }

    static final List<Key> KEYS = new ArrayList<Key>();

    static {
        float ch = (SY1 - SY0) / LinerMcdu.ROWS;
        for (int k = 0; k < 6; k++) {
            float cy = SY0 + (2 * (k + 1) + 0.5F) * ch;
            KEYS.add(new Key(10, cy - 7, 42, cy + 7, LinerMcdu.K_L1 + k, "-", 0));
            KEYS.add(new Key(338, cy - 7, 370, cy + 7, LinerMcdu.K_R1 + k, "-", 0));
        }
        int[][] fn = {{LinerMcdu.K_DIR, LinerMcdu.K_PROG, LinerMcdu.K_PERF, LinerMcdu.K_INIT, LinerMcdu.K_DATA, -1, LinerMcdu.K_BRT},
                {LinerMcdu.K_FPLN, LinerMcdu.K_RAD, LinerMcdu.K_FUEL, LinerMcdu.K_SEC, LinerMcdu.K_ATC, LinerMcdu.K_MENU, LinerMcdu.K_DIM},
                {LinerMcdu.K_AIRPORT, -1, LinerMcdu.K_PREV, LinerMcdu.K_UP, LinerMcdu.K_NEXT, LinerMcdu.K_DOWN, -1}};
        String[][] fl = {{"dir", "prog", "perf", "init", "data", "", "brt"}, {"fpln", "rad", "fuel", "sec", "atc", "menu", "dim"},
                {"airport", "", "", "", "", "", ""}};
        int[][] ar = {{0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 0}, {0, 0, 3, 1, 4, 2, 0}};
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 7; c++) {
                if (fn[r][c] < 0) {
                    continue;
                }
                float x = 16 + c * 50, y = 246 + r * 32;
                KEYS.add(new Key(x, y, x + 44, y + 25, fn[r][c], fl[r][c].length() > 0 ? "sva.mcdu.key." + fl[r][c] : "", ar[r][c]));
            }
        }
        String digits = "123456789,0";
        for (int i = 0; i < 12; i++) {
            float x = 16 + (i % 3) * 38, y = 352 + (i / 3) * 40;
            if (i == 11) {
                KEYS.add(new Key(x, y, x + 32, y + 32, LinerMcdu.K_PLUSMINUS, "+/-", 0));
            } else {
                char d = digits.charAt(i);
                KEYS.add(new Key(x, y, x + 32, y + 32, LinerMcdu.K_CHAR + d, String.valueOf(d), 0));
            }
        }
        String letters = "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЫЭЮЯ";
        for (int i = 0; i < letters.length(); i++) {
            float x = 140 + (i % 6) * 38, y = 344 + (i / 6) * 35;
            KEYS.add(new Key(x, y, x + 33, y + 30, LinerMcdu.K_CHAR + letters.charAt(i), letters.substring(i, i + 1), 0));
        }
        int[] last = {LinerMcdu.K_CHAR + '/', LinerMcdu.K_SP, LinerMcdu.K_OVFY, LinerMcdu.K_CLR, LinerMcdu.K_CHAR + '-', LinerMcdu.K_CHAR + '.'};
        String[] ll = {"/", "sva.mcdu.key.sp", "sva.mcdu.key.ovfy", "sva.mcdu.key.clr", "-", "."};
        for (int i = 0; i < last.length; i++) {
            float x = 140 + i * 38, y = 344 + 5 * 35;
            KEYS.add(new Key(x, y, x + 33, y + 30, last[i], ll[i], 0));
        }
    }

    /** Also the preview's: the whole MCDU in panel units. */
    public static void paintPanel(int side, boolean powered, LinerMcdu.Screen sc, float mx, float my, Object pressedKey, boolean flash) {
        LinerGfxSVA.rect(0, 0, W, H, 0x262A30);
        LinerGfxSVA.layer();
        LinerGfxSVA.rect(3, 3, W - 3, H - 3, 0x3A3F47);
        LinerGfxSVA.layer();
        // the screen's bezel and glass
        LinerGfxSVA.rect(SX0 - 7, SY0 - 7, SX1 + 7, SY1 + 7, 0x16171A);
        LinerGfxSVA.layer();
        float b = LinerGfxSVA.bright;
        LinerGfxSVA.bright = b * BRIGHT[side];
        McduScreenSVA.draw(sc, SX0, SY0, SX1 - SX0, SY1 - SY0, powered);
        LinerGfxSVA.bright = b;
        LinerGfxSVA.layer();
        LinerGfxSVA.text(LinerSdPagesSVA.lang(side == 0 ? "sva.mcdu.gui.mcdu1" : "sva.mcdu.gui.mcdu2"), 70, 341, 10, 0xD8DCE2, 0, 100);
        for (Key key : KEYS) {
            boolean hover = mx >= key.x0 && mx <= key.x1 && my >= key.y0 && my <= key.y1;
            boolean down = flash && key == pressedKey;
            int face = down ? 0x5C626C : hover ? 0x4A5058 : 0x2E3238;
            LinerGfxSVA.rect(key.x0, key.y0, key.x1, key.y1, 0x15171A);
            LinerGfxSVA.layer();
            LinerGfxSVA.rect(key.x0 + 1, key.y0 + 1, key.x1 - 1, key.y1 - 1, face);
            LinerGfxSVA.rect(key.x0 + 1, key.y0 + 1, key.x1 - 1, key.y0 + 2.5F, 0x5A606A);
            LinerGfxSVA.layer();
            float cx = (key.x0 + key.x1) / 2, cy = (key.y0 + key.y1) / 2;
            if (key.arrow > 0) {
                float r = 6;
                switch (key.arrow) {
                    case 1:
                        LinerGfxSVA.tri(cx - r, cy + r * 0.6F, cx + r, cy + r * 0.6F, cx, cy - r * 0.7F, 0xF0F0F0);
                        break;
                    case 2:
                        LinerGfxSVA.tri(cx - r, cy - r * 0.6F, cx, cy + r * 0.7F, cx + r, cy - r * 0.6F, 0xF0F0F0);
                        break;
                    case 3:
                        LinerGfxSVA.tri(cx + r * 0.6F, cy - r, cx + r * 0.6F, cy + r, cx - r * 0.7F, cy, 0xF0F0F0);
                        break;
                    default:
                        LinerGfxSVA.tri(cx - r * 0.6F, cy - r, cx + r * 0.7F, cy, cx - r * 0.6F, cy + r, 0xF0F0F0);
                        break;
                }
            } else {
                String t = key.label.startsWith("sva.") ? LinerSdPagesSVA.lang(key.label) : key.label;
                boolean fn = key.label.startsWith("sva.");
                LinerGfxSVA.text(t, cx, cy, fn ? 8.5F : 12F, 0xF0F0F0, 0, key.x1 - key.x0 - 4);
            }
            LinerGfxSVA.layer();
        }
        // the annunciators: ОТК / ФМ / ИНД / МЕНЮ (static here)
        LinerGfxSVA.text(LinerSdPagesSVA.lang("sva.mcdu.gui.fm"), 26, 240, 7, 0x6A6E76, 0, 30);
    }

}
