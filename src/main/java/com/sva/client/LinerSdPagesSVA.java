package com.sva.client;

import com.sc.manual.Lang;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerFctl;
import com.sva.vehicle.LinerGear;
import com.sva.vehicle.LinerHyd;
import com.sva.vehicle.LinerSystems;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 2 (mock-ups veh_liner_13a «ГИДР», 13b «УПР» / «КОЛЁСА»): three pages of the lower ECAM, drawn in the SD's
 * 280 x 280 units (s = the scale) from the synced systems only (no entity: the standalone preview draws the very same code
 * through LinerGfxSVA's sink).
 * <ul>
 * <li>ГИДР: ЗЕЛ / СИН / ЖЁЛТ - the psi (amber under LO_PSI), the pressure triangles, the PTU between Green and Yellow (its
 * arrows lit while it runs), the RAT on Blue, the pumps (a bar along = running, across amber = off / low), the fire valves,
 * the reservoirs' quantity with the low mark, overheat / leak, the brake accumulator.</li>
 * <li>УПР: the spoilers 1..5 per wing (an arrow while up; amber when unpowered), ailerons and elevator halves on their
 * scales, the THS in degrees (КАБР / ПИКИР), the rudder's fan, under each its systems G / B / Y (amber - no pressure),
 * ELAC 1 / 2, SEC 1 / 2 / 3, FAC 1 / 2 (amber - lost) and the law.</li>
 * <li>КОЛЁСА: the three legs (green down and locked, red in transit, nothing up; the gravity extension), the brakes'
 * temperatures per wheel with the hottest's arc (amber over LinerGear.HOT_C), flat tyres, the anti-skid, the autobrake,
 * the brake mode (НОРМ / Ж / АККУМ), the brake fans, the parking brake, the nose-wheel steering.</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public final class LinerSdPagesSVA {

    static final int BG = LinerDisplaysSVA.BG, WHT = LinerDisplaysSVA.WHT, GRN = LinerDisplaysSVA.GRN, AMB = LinerDisplaysSVA.AMB,
            RED = LinerDisplaysSVA.RED, CYN = LinerDisplaysSVA.CYN, GREY = LinerDisplaysSVA.GREY;

    /** The preview's translations (null in the game: the lang files). */
    public static java.util.Map<String, String> previewLang;

    private LinerSdPagesSVA() {
    }

    /** sva.liner.du.KEY (the SD's words). */
    static String tr(String k, Object... a) {
        return lang("sva.liner.du." + k, a);
    }

    static String lang(String key, Object... a) {
        if (previewLang != null) {
            String v = previewLang.get(key);
            return v == null ? key : a.length == 0 ? v : String.format(v, a);
        }
        return a.length == 0 ? Lang.tr(key) : Lang.tr(key, a);
    }

    /** The page with its title (the preview's entry; the game's sd() draws the title and the bottom strip itself). */
    public static void preview(int page, LinerSystems L, float w, float h) {
        float s = w / 280F;
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.rect(0, 0, w, h, BG);
        LinerGfxSVA.layer();
        LinerGfxSVA.text(lang("sva.liner.sd." + com.sva.vehicle.LinerEcam.PAGE_KEYS[page]), 8 * s, 12 * s, 11 * s, WHT, -1);
        LinerGfxSVA.line(8 * s, 19 * s, 52 * s, 19 * s, 1F * s, WHT);
        draw(page, L, s);
        LinerGfxSVA.line(0, 244 * s, w, 244 * s, 1F * s, WHT);
    }

    /** Draws a wave-2 page (4 ГИДР, 7 УПР, 9 КОЛЁСА). @return it is one of them */
    static boolean draw(int page, LinerSystems L, float s) {
        switch (page) {
            case 4:
                hyd(L, s);
                return true;
            case 7:
                fctl(L, s);
                return true;
            case 9:
                wheels(L, s);
                return true;
            default:
                return LinerSdPages3SVA.draw(page, L, s);              // wave 3: ТОПЛ, КОНД, ДВЕРИ, СТАТУС, the checklists, cruise
        }
    }

    // ---------------------------------------------------------------- ГИДР

    static final float[] HX = {50, 140, 230};

    static void hyd(LinerSystems L, float s) {
        LinerHyd h = L.hyd;
        String[] names = {tr("hyd.g"), tr("hyd.b"), tr("hyd.y")};
        for (int i = 0; i < 3; i++) {
            float x = HX[i] * s;
            boolean ok = h.psi[i] >= LinerHyd.LO_PSI;
            int c = ok ? GRN : AMB;
            LinerGfxSVA.text(names[i], x, 34 * s, 10 * s, ok ? WHT : AMB, 0);
            LinerGfxSVA.text(Math.round(h.psi[i] / 50) * 50 + " psi", x, 48 * s, 9.5F * s, c, 0);
            // the pressure triangle, the line down to the pumps
            tri(x, 60 * s, 6 * s, c, true);
            LinerGfxSVA.line(x, 68 * s, x, 196 * s, 1.4F * s, c);
            // the fire valve (always open - no fire system yet) and the reservoir
            LinerDisplaysSVA.valve(x, 166 * s, true, s);
            reservoir(h, i, x, s);
        }
        // the PTU: Green <-> Yellow at y 92, its two arrows lit green while it runs
        float py = 92 * s;
        int pc = h.ptu ? GRN : (h.faults & LinerHyd.F_PTU) != 0 ? AMB : WHT;
        LinerGfxSVA.line(HX[0] * s, py, 118 * s, py, 1.2F * s, GRN);
        LinerGfxSVA.line(162 * s, py, HX[2] * s, py, 1.2F * s, GRN);
        arrow(126 * s, py, 6 * s, true, pc, h.ptu, s);
        arrow(154 * s, py, 6 * s, false, pc, h.ptu, s);
        LinerGfxSVA.text("PTU", 140 * s, py + 12 * s, 8 * s, WHT, 0);
        // the RAT on Blue
        float rx = 100 * s, ry = 118 * s;
        LinerGfxSVA.arc(rx, ry, 6 * s, 0, 360, 1.3F * s, h.rat ? GRN : WHT);
        LinerGfxSVA.layer();
        LinerGfxSVA.line(rx - 4 * s, ry - 4 * s, rx + 4 * s, ry + 4 * s, 1.2F * s, h.rat ? GRN : WHT);
        LinerGfxSVA.line(rx - 4 * s, ry + 4 * s, rx + 4 * s, ry - 4 * s, 1.2F * s, h.rat ? GRN : WHT);
        LinerGfxSVA.line(rx + 6 * s, ry, HX[1] * s, ry, 1F * s, h.rat ? GRN : GREY);
        LinerGfxSVA.text("RAT", rx, ry - 12 * s, 7.5F * s, WHT, 0);
        // the pumps: EDP 1 (Green), the electric (Blue), EDP 2 and the electric (Yellow)
        pump(HX[0] * s, 138 * s, h.edp1 && h.psi[0] >= LinerHyd.LO_PSI, (h.faults & LinerHyd.F_EDP1) != 0, tr("hyd.eng", Integer.valueOf(1)), s);
        pump(HX[1] * s, 138 * s, h.elecB && h.psi[1] >= LinerHyd.LO_PSI, (h.faults & LinerHyd.F_ELEC_B) != 0, tr("hyd.elec"), s);
        pump(HX[2] * s, 138 * s, h.edp2 && h.psi[2] >= LinerHyd.LO_PSI, (h.faults & LinerHyd.F_EDP2) != 0, tr("hyd.eng", Integer.valueOf(2)), s);
        float ex = 264 * s;
        LinerGfxSVA.line(HX[2] * s, 112 * s, ex, 112 * s, 1F * s, h.elecY ? GRN : GREY);
        LinerGfxSVA.line(ex, 112 * s, ex, 130 * s, 1F * s, h.elecY ? GRN : GREY);
        pump(ex, 138 * s, h.elecY, (h.faults & LinerHyd.F_ELEC_Y) != 0, tr("hyd.elecShort"), s);
        if (!L.on(LinerControls.HYD_YEL)) {
            LinerGfxSVA.text(lang("sva.liner.pos.off"), ex, 166 * s, 7 * s, WHT, 0);
        }
        // the brake accumulator
        LinerGfxSVA.text(tr("hyd.accu", Integer.valueOf((int) Math.round(h.accu / 50) * 50)), 140 * s, 236 * s, 7.5F * s,
                h.accu >= LinerHyd.PARK_MIN ? GRN : AMB, 0);
    }

    /** A reservoir: the quantity bar (its normal fill at the top), the low mark, the litres, overheat / leak. */
    static void reservoir(LinerHyd h, int i, float x, float s) {
        float top = 178 * s, bot = 222 * s, w = 5 * s;
        double share = h.qty[i] / LinerHyd.QTY_FULL[i];
        boolean low = h.low(i);
        LinerGfxSVA.frameRect(x - w, top, x + w, bot, 1F * s, WHT);
        LinerGfxSVA.layer();
        float lvl = (float) (bot - (bot - top) * Math.max(0, Math.min(1, share)));
        LinerGfxSVA.rect(x - w + 1.5F * s, lvl, x + w - 1.5F * s, bot - 1.5F * s, low ? AMB : GRN);
        float lm = (float) (bot - (bot - top) * LinerHyd.LOW_QTY);
        LinerGfxSVA.rect(x - w - 4 * s, lm - 0.8F * s, x - w, lm + 0.8F * s, AMB);
        LinerGfxSVA.text(LinerGfxSVA.num(h.qty[i], 1) + " " + tr("l"), x + 9 * s, 214 * s, 7 * s, low ? AMB : GRN, -1);
        if (h.hot[i]) {
            LinerGfxSVA.text(tr("hyd.hot"), x, 230 * s, 7 * s, AMB, 0, 60 * s);
        } else if (h.leak[i] > 0) {
            LinerGfxSVA.text(tr("hyd.leak"), x, 230 * s, 7 * s, AMB, 0, 60 * s);
        }
    }

    /** A pump box: a bar along the flow when it runs (green), across when off / low (amber with a fault). */
    static void pump(float x, float y, boolean run, boolean fault, String label, float s) {
        float r = 7 * s;
        int c = run ? GRN : AMB;
        LinerGfxSVA.rect(x - r, y - r, x + r, y + r, BG);
        LinerGfxSVA.layer();
        LinerGfxSVA.frameRect(x - r, y - r, x + r, y + r, 1.3F * s, c);
        if (run) {
            LinerGfxSVA.line(x, y - r, x, y + r, 1.4F * s, GRN);
        } else {
            LinerGfxSVA.line(x - r, y, x + r, y, 1.4F * s, AMB);
        }
        LinerGfxSVA.text(label, x, y + 15 * s, 7.5F * s, fault ? AMB : WHT, 0, 40 * s);
    }

    /** An up (filled when `on`) or down triangle of half-size r at (x, y). */
    static void tri(float x, float y, float r, int c, boolean up) {
        if (up) {
            LinerGfxSVA.line(x - r, y + r * 0.8F, x, y - r, 1.2F * r / 6, c);
            LinerGfxSVA.line(x, y - r, x + r, y + r * 0.8F, 1.2F * r / 6, c);
            LinerGfxSVA.line(x + r, y + r * 0.8F, x - r, y + r * 0.8F, 1.2F * r / 6, c);
        } else {
            LinerGfxSVA.tri(x - r, y - r * 0.8F, x + r, y - r * 0.8F, x, y + r, c);
        }
    }

    /** The PTU's arrow (pointing right when `right`), filled while it runs. */
    static void arrow(float x, float y, float r, boolean right, int c, boolean fill, float s) {
        float d = right ? 1 : -1;
        if (fill) {
            LinerGfxSVA.tri(x - d * r, y - r, x - d * r, y + r, x + d * r, y, c);
        } else {
            LinerGfxSVA.line(x - d * r, y - r, x + d * r, y, 1.2F * s, c);
            LinerGfxSVA.line(x + d * r, y, x - d * r, y + r, 1.2F * s, c);
            LinerGfxSVA.line(x - d * r, y + r, x - d * r, y - r, 1.2F * s, c);
        }
    }

    // ---------------------------------------------------------------- УПР

    static void fctl(LinerSystems L, float s) {
        LinerFctl f = L.fctl;
        int hm = L.hyd.mask();
        LinerGfxSVA.text(tr("fctl.spoilers"), 140 * s, 30 * s, 8 * s, WHT, 0);
        // spoilers: 1 inboard high .. 5 outboard low, per wing
        for (int side = 0; side < 2; side++) {
            for (int i = 1; i <= 5; i++) {
                int sf = (side == 0 ? LinerFctl.SPL_L : LinerFctl.SPL_R) + i - 1;
                float x = (side == 0 ? 122 - (i - 1) * 18 : 158 + (i - 1) * 18) * s, y = (42 + (i - 1) * 4) * s;
                boolean on = f.on(sf);
                int c = on ? GRN : AMB;
                LinerGfxSVA.line(x - 7 * s, y, x + 7 * s, y, 1.6F * s, c);
                float a = f.angle[sf];
                if (a > 2) {
                    float len = 4 * s + a / LinerFctl.SPL_MAX * 12 * s;
                    LinerGfxSVA.line(x, y, x, y - len, 1.3F * s, c);
                    LinerGfxSVA.tri(x - 3 * s, y - len + 1 * s, x + 3 * s, y - len + 1 * s, x, y - len - 4 * s, c);
                }
                LinerGfxSVA.text(String.valueOf(i), x, y + 8 * s, 7 * s, c, 0);
            }
        }
        // ailerons: L at x 24, R at 256 - scales -25 (up) .. +25 (down)
        LinerGfxSVA.text(tr("fctl.ail"), 24 * s, 70 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("fctl.ail"), 256 * s, 70 * s, 8 * s, WHT, 0);
        scale(24 * s, 78 * s, 120 * s, f.angle[LinerFctl.AIL_L] / LinerFctl.AIL_MAX, f.on(LinerFctl.AIL_L), true, s);
        scale(256 * s, 78 * s, 120 * s, f.angle[LinerFctl.AIL_R] / LinerFctl.AIL_MAX, f.on(LinerFctl.AIL_R), false, s);
        letters(24 * s, 132 * s, LinerFctl.HYD[LinerFctl.AIL_L], "GB", hm, s);
        letters(256 * s, 132 * s, LinerFctl.HYD[LinerFctl.AIL_R], "GB", hm, s);
        // the computers and the law
        computer(L, 0, 108 * s, 72 * s, s);
        computer(L, 1, 172 * s, 72 * s, s);
        computer(L, 2, 92 * s, 92 * s, s);
        computer(L, 3, 140 * s, 92 * s, s);
        computer(L, 4, 188 * s, 92 * s, s);
        LinerGfxSVA.text(tr("fctl.law", lang("sva.liner.law." + LinerFctl.LAW_KEYS[f.law])), 140 * s, 110 * s, 8.5F * s, f.law == 0 ? GRN : AMB, 0);
        // elevators: L at x 56, R at 224 - up 30 (top) .. down 17
        LinerGfxSVA.text(tr("fctl.elev"), 56 * s, 146 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("fctl.elev"), 224 * s, 146 * s, 8 * s, WHT, 0);
        elev(56 * s, f.angle[LinerFctl.ELEV_L], f.on(LinerFctl.ELEV_L), true, s);
        elev(224 * s, f.angle[LinerFctl.ELEV_R], f.on(LinerFctl.ELEV_R), false, s);
        letters(56 * s, 214 * s, LinerFctl.HYD[LinerFctl.ELEV_L], "BG", hm, s);
        letters(224 * s, 214 * s, LinerFctl.HYD[LinerFctl.ELEV_R], "YB", hm, s);
        // the THS
        float ths = f.angle[LinerFctl.THS];
        LinerGfxSVA.text(tr("fctl.ths"), 140 * s, 132 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(LinerGfxSVA.num(Math.abs(ths), 1), 128 * s, 145 * s, 9.5F * s, f.on(LinerFctl.THS) ? GRN : AMB, 0);
        LinerGfxSVA.text(tr(ths >= 0 ? "fctl.up" : "fctl.dn"), 150 * s, 145 * s, 7.5F * s, CYN, -1);
        letters(140 * s, 158 * s, LinerFctl.HYD[LinerFctl.THS], "GY", hm, s);
        // the rudder's fan
        float rx = 140 * s, ry = 178 * s, R = 44 * s;
        LinerGfxSVA.arc(rx, ry, R, 150, 210, 1.2F * s, WHT);
        LinerGfxSVA.layer();
        float ra = 180 - f.angle[LinerFctl.RUD] / LinerFctl.RUD_MAX * 30;
        int rc = (hm & LinerFctl.HYD[LinerFctl.RUD]) != 0 ? GRN : AMB;
        float tx = LinerGfxSVA.px(rx, R - 2 * s, ra), ty = LinerGfxSVA.py(ry, R - 2 * s, ra);
        LinerGfxSVA.line(rx, ry + 14 * s, tx, ty, 2F * s, rc);
        LinerGfxSVA.disc(rx, ry + 14 * s, 3 * s, rc);
        LinerGfxSVA.text(tr("fctl.rud"), 112 * s, 232 * s, 8 * s, WHT, 0);
        letters(146 * s, 232 * s, LinerFctl.HYD[LinerFctl.RUD], "GBY", hm, s);
        computer(L, 5, 34 * s, 232 * s, s);
        computer(L, 6, 72 * s, 232 * s, s);
    }

    /** A vertical surface scale (the aileron's): `k` -1 (up, top) .. 1 (down, bottom); the pointer on the outside. */
    static void scale(float x, float y0, float y1, float k, boolean on, boolean left, float s) {
        LinerGfxSVA.line(x, y0, x, y1, 1.2F * s, WHT);
        float y1b = y1;
        float cy = (y0 + y1b) / 2;
        for (float t : new float[] {y0, cy, y1b}) {
            LinerGfxSVA.line(x - 3 * s, t, x + 3 * s, t, 1F * s, WHT);
        }
        float py = cy + Math.max(-1, Math.min(1, k)) * (y1b - y0) / 2;
        float d = left ? 1 : -1;
        LinerGfxSVA.tri(x + d * 3 * s, py, x + d * 10 * s, py - 4 * s, x + d * 10 * s, py + 4 * s, on ? GRN : AMB);
    }

    /** An elevator half's scale: up 30 at the top .. down 17 at the bottom, neutral marked. */
    static void elev(float x, float a, boolean on, boolean left, float s) {
        float y0 = 156 * s, y1 = 204 * s;
        float yn = y0 + (y1 - y0) * LinerFctl.ELEV_UP / (LinerFctl.ELEV_UP + LinerFctl.ELEV_DOWN);
        LinerGfxSVA.line(x, y0, x, y1, 1.2F * s, WHT);
        for (float t : new float[] {y0, yn, y1}) {
            LinerGfxSVA.line(x - 3 * s, t, x + 3 * s, t, 1F * s, WHT);
        }
        float py = yn + Math.max(-LinerFctl.ELEV_UP, Math.min(LinerFctl.ELEV_DOWN, a)) / (LinerFctl.ELEV_UP + LinerFctl.ELEV_DOWN) * (y1 - y0);
        float d = left ? 1 : -1;
        LinerGfxSVA.tri(x + d * 3 * s, py, x + d * 10 * s, py - 4 * s, x + d * 10 * s, py + 4 * s, on ? GRN : AMB);
    }

    /** The hydraulic letters of a surface in boxes: green with its system's pressure, amber without. */
    static void letters(float x, float y, int bits, String order, int mask, float s) {
        float w = 8 * s, x0 = x - order.length() * w / 2;
        for (int i = 0; i < order.length(); i++) {
            char ch = order.charAt(i);
            int b = ch == 'G' ? LinerHyd.MG : ch == 'B' ? LinerHyd.MB : LinerHyd.MY;
            int c = (mask & b) != 0 ? GRN : AMB;
            float cx = x0 + w * i + w / 2;
            LinerGfxSVA.frameRect(cx - w / 2 + 0.5F * s, y - 5 * s, cx + w / 2 - 0.5F * s, y + 5 * s, 0.9F * s, c);
            LinerGfxSVA.text(String.valueOf(ch), cx, y, 7.5F * s, c, 0);
        }
    }

    /** A computer's box: green working, amber lost. */
    static void computer(LinerSystems L, int i, float x, float y, float s) {
        boolean ok = (L.fctl.comp & 1 << i) != 0;
        int c = ok ? GRN : AMB;
        String n = LinerFctl.COMP_NAMES[i];
        float w = (i >= 5 ? 34 : i >= 2 ? 40 : 52) * s;
        if (i < 5) {
            LinerGfxSVA.frameRect(x - w / 2, y - 7 * s, x + w / 2, y + 7 * s, 1.1F * s, c);
        }
        LinerGfxSVA.text(n, x, y, 8 * s, c, 0, w - 4 * s);
    }

    // ---------------------------------------------------------------- КОЛЁСА

    static void wheels(LinerSystems L, float s) {
        LinerGear g = L.gear;
        // the anti-skid and the steering (top corners)
        LinerGfxSVA.text(tr("wheel.antiskid"), 40 * s, 32 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(lang(g.antiskid ? "sva.liner.pos.on" : "sva.liner.pos.off"), 40 * s, 43 * s, 8 * s, g.antiskid ? GRN : AMB, 0);
        LinerGfxSVA.text(tr("wheel.nws"), 240 * s, 32 * s, 8 * s, WHT, 0);
        String nws = g.nws ? tr("wheel.nwsNorm") : L.at(LinerControls.TOW) == 0 ? tr("wheel.nwsTow") : tr("wheel.nwsOff");
        LinerGfxSVA.text(nws, 240 * s, 43 * s, 8 * s, g.nws ? GRN : AMB, 0, 76 * s);
        // the legs
        legSymbol(g, 140 * s, 44 * s, s);
        LinerGfxSVA.text(tr("wheel.nose"), 140 * s, 60 * s, 7.5F * s, WHT, 0);
        legSymbol(g, 70 * s, 76 * s, s);
        legSymbol(g, 210 * s, 76 * s, s);
        LinerGfxSVA.text(tr("wheel.left"), 70 * s, 92 * s, 7.5F * s, WHT, 0);
        LinerGfxSVA.text(tr("wheel.right"), 210 * s, 92 * s, 7.5F * s, WHT, 0);
        String gs = g.downLocked() ? tr("wheel.down") : g.pos >= 1 - 1e-6 ? tr("wheel.up") : tr("wheel.transit");
        LinerGfxSVA.text(gs, 140 * s, 80 * s, 8.5F * s, g.downLocked() ? GRN : g.pos >= 1 - 1e-6 ? WHT : AMB, 0, 70 * s);
        if (g.gravity) {
            LinerGfxSVA.text(tr("wheel.gravity"), 140 * s, 92 * s, 7.5F * s, AMB, 0, 76 * s);
        }
        // the brakes: 1 2 (left) 3 4 (right): the temperature, the arc (the hottest's amber over HOT_C), the box (flat)
        float[] bx = {40, 92, 188, 240};
        int hot = g.hottestWheel();
        LinerGfxSVA.text(" C", 140 * s, 116 * s, 7.5F * s, CYN, 0);
        LinerGfxSVA.text(tr("wheel.brakes"), 140 * s, 128 * s, 8 * s, WHT, 0);
        for (int w = 0; w < 4; w++) {
            float x = bx[w] * s;
            double t = g.temp[w];
            boolean warm = t > LinerGear.HOT_C;
            int c = warm ? AMB : GRN;
            LinerGfxSVA.text(String.valueOf((int) Math.round(t / 5) * 5), x, 108 * s, 9.5F * s, c, 0);
            if (w == hot && t > LinerGear.T_AMB + 10) {
                LinerGfxSVA.arc(x, 128 * s, 10 * s, -50, 50, 1.6F * s, c);
                LinerGfxSVA.layer();
            }
            boolean flat = (g.flat & 1 << w) != 0;
            LinerGfxSVA.frameRect(x - 10 * s, 122 * s, x + 10 * s, 138 * s, 1.1F * s, flat ? AMB : WHT);
            if (flat) {
                LinerGfxSVA.text(tr("wheel.flat"), x, 130 * s, 6.5F * s, AMB, 0, 18 * s);
            }
            LinerGfxSVA.text(String.valueOf(w + 1), x, 146 * s, 7.5F * s, WHT, 0);
        }
        if (g.hot()) {
            LinerGfxSVA.text(lang("sva.liner.msg.brkHot"), 140 * s, 162 * s, 9 * s, AMB, 0);
            LinerGfxSVA.text(tr("wheel.hotLine", Integer.valueOf(hot + 1), Integer.valueOf((int) Math.round(g.hottest())),
                    lang(L.on(LinerControls.BRK_FAN) ? "sva.liner.du.wheel.fansOn" : "sva.liner.du.wheel.fansOff")), 140 * s, 174 * s, 7 * s, GREY, 0,
                    250 * s);
        }
        // the bottom rows: autobrake / brake fans, the alternate brakes / the parking brake
        LinerGfxSVA.text(tr("wheel.autobrk"), 12 * s, 196 * s, 8 * s, WHT, -1);
        String ab = lang("sva.liner.du.ab." + LinerGear.AB_KEYS[g.abMode]);
        LinerGfxSVA.text(g.abDecel ? ab + " " + tr("wheel.decel") : ab, 84 * s, 196 * s, 8 * s, g.abMode == 0 ? WHT : GRN, -1, 60 * s);
        LinerGfxSVA.text(tr("wheel.fans"), 150 * s, 196 * s, 8 * s, WHT, -1);
        boolean fan = L.on(LinerControls.BRK_FAN);
        LinerGfxSVA.text(lang(fan ? "sva.liner.pos.on" : "sva.liner.pos.off"), 236 * s, 196 * s, 8 * s, fan ? GRN : WHT, -1);
        LinerGfxSVA.text(tr("wheel.mode"), 12 * s, 214 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(lang("sva.liner.du.bm." + LinerGear.MODE_KEYS[g.mode]), 84 * s, 214 * s, 8 * s, g.mode == LinerGear.B_NORM ? GRN : AMB, -1,
                62 * s);
        LinerGfxSVA.text(tr("wheel.park"), 150 * s, 214 * s, 8 * s, WHT, -1);
        boolean park = L.on(LinerControls.PARK_BRK);
        LinerGfxSVA.text(lang(park ? "sva.liner.pos.on" : "sva.liner.pos.off"), 236 * s, 214 * s, 8 * s, park ? (g.parkHold ? GRN : AMB) : WHT, -1);
        LinerGfxSVA.text(tr("wheel.accu", Integer.valueOf((int) Math.round(L.hyd.accu / 50) * 50)), 140 * s, 232 * s, 7 * s, GREY, 0);
    }

    /** A leg's symbol: green triangles down and locked, red in transit (or disagreeing), nothing up and locked. */
    static void legSymbol(LinerGear g, float x, float y, float s) {
        boolean down = g.downLocked(), up = g.pos >= 1 - 1e-6;
        if (up && g.disagree <= LinerGear.DISAGREE_TICKS) {
            return;
        }
        int c = down ? GRN : RED;
        LinerGfxSVA.line(x - 12 * s, y - 9 * s, x - 4 * s, y - 9 * s, 1.4F * s, c);
        LinerGfxSVA.line(x + 4 * s, y - 9 * s, x + 12 * s, y - 9 * s, 1.4F * s, c);
        LinerGfxSVA.tri(x - 8 * s, y - 5 * s, x + 8 * s, y - 5 * s, x, y + 6 * s, c);
    }
}
