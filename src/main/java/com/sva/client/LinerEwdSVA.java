package com.sva.client;

import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerSystems;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 3 (mock-up veh_liner_12): the upper ECAM display (E/WD) in its 280 x 280 units, from the synced systems only -
 * the game (LinerDisplaysSVA.ewd) and the standalone preview (LinerGfxSVA's sink) draw the very same code.
 * <ul>
 * <li>The engines: N1 (its dial, the red line, the amber thrust-limit index of the mode, the cyan thrust-lever circle), EGT,
 * N2 (grey while starting, IGN), FF; the thrust mode with its N1 limit, FOB and the fuel used (FU), the flaps / slats.</li>
 * <li>The memo area, left: the ECAM's messages (red, then amber; each with its cyan action lines «- WHAT ..... SETTING»
 * that vanish when done; «ДАЛЕЕ» when they don't fit), else T.O. MEMO / LDG MEMO (cyan items turn green with a tick),
 * else the engine starts. Right: with failures the STATUS summary (limitations amber, ОТКЛ: inoperative systems), else
 * the memos (green / amber).</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public final class LinerEwdSVA {

    static final int BG = LinerDisplaysSVA.BG, WHT = LinerDisplaysSVA.WHT, GRN = LinerDisplaysSVA.GRN, AMB = LinerDisplaysSVA.AMB,
            RED = LinerDisplaysSVA.RED, CYN = LinerDisplaysSVA.CYN, GREY = LinerDisplaysSVA.GREY;
    /** The N1 limit per thrust mode (%): TOGA, FLX, CL. */
    static final float N1_TOGA = 101.6F, N1_FLX = 95.0F, N1_CL = 89.0F;

    private LinerEwdSVA() {
    }

    static String tr(String k, Object... a) {
        return LinerSdPagesSVA.tr(k, a);
    }

    static String lang(String k, Object... a) {
        return LinerSdPagesSVA.lang(k, a);
    }

    /**
     * The whole E/WD: thr - the thrust levers (0..1), flap / slat - where the high lift is (0..4 / 0..1), lever - the flap
     * lever's setting, ground - on the ground (the memos), batMin - the batteries' minutes (the «НА АКБ» memo).
     */
    public static void draw(LinerSystems L, float w, float h, double thr, float flap, float slat, int lever, boolean ground, int batMin) {
        float s = w / 280F;
        engines(L, s, thr);
        // thrust mode and its N1 limit (wave 4: by the levers' detent - TOGA, FLX 45 / MCT, CL), FOB, FU
        String tm = limitMode(L, thr, ground);
        float lim = limitN1(L, thr);
        LinerGfxSVA.text(tm, 210 * s, 12 * s, 9 * s, CYN, -1, 40 * s);
        LinerGfxSVA.text(LinerGfxSVA.num(lim, 1), 276 * s, 12 * s, 9 * s, GRN, 1);
        LinerGfxSVA.text("%", 276 * s, 21 * s, 6 * s, CYN, 1);
        LinerGfxSVA.text(tr("fob"), 210 * s, 56 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(com.sva.FormatSVA.group(Math.round(L.tanks())), 276 * s, 56 * s, 9 * s, GRN, 1);
        LinerGfxSVA.text(tr("mb"), 276 * s, 65 * s, 6 * s, CYN, 1);
        LinerGfxSVA.text(tr("fu"), 210 * s, 76 * s, 7 * s, WHT, -1);
        LinerGfxSVA.text(com.sva.FormatSVA.group(Math.round(L.fuel.usedTotal())), 276 * s, 76 * s, 8 * s, GRN, 1);
        flaps(210 * s, 108 * s, s, flap, slat, lever);
        // the memo area
        LinerGfxSVA.line(4 * s, 186 * s, 276 * s, 186 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(176 * s, 190 * s, 176 * s, 276 * s, 1F * s, WHT);
        left(L, s, ground);
        right(L, s, ground, batMin);
    }

    /** Wave 4: the thrust limit's mode - TOGA (the TOGA detent, alpha floor), FLX 45 / MCT (the FLX/MCT detent), else CL. */
    static String limitMode(LinerSystems L, double thr, boolean ground) {
        com.sva.vehicle.LinerAfs F = L.afs;
        int det = com.sva.vehicle.LinerAfs.detent(thr);
        if (F.aFloor || F.togaLk || det == 3) {
            return "TOGA";
        }
        if (det == 2) {
            // wave 5: the MCDU's FLEX temperature (none: MCT)
            return (ground || F.thr == com.sva.vehicle.LinerAfs.T_MAN_FLX) && F.flexTemp > 0 ? "FLX " + F.flexTemp : "MCT";
        }
        return "CL";
    }

    /** Wave 4: that mode's N1 limit (%): the detent's thrust on the idle..TOGA N1 scale. */
    static float limitN1(LinerSystems L, double thr) {
        com.sva.vehicle.LinerAfs F = L.afs;
        int det = com.sva.vehicle.LinerAfs.detent(thr);
        double share = F.aFloor || F.togaLk || det == 3 ? 1 : det == 2 ? (F.flexTemp > 0 ? com.sva.vehicle.LinerFms.flexShare(F.flexTemp)
                : com.sva.vehicle.LinerAfs.DET_FLX) : com.sva.vehicle.LinerAfs.DET_CL;
        return (float) (LinerSystems.IDLE_N1 + (LinerSystems.TOGA_N1 - LinerSystems.IDLE_N1) * share);
    }

    /** N1 / EGT / N2 / FF of both engines (wave 1's layout) with the N1 limit index. */
    static void engines(LinerSystems L, float s, double thr) {
        float lim = limitN1(L, thr);
        for (int i = 0; i < 2; i++) {
            LinerSystems.Eng g = L.eng[i];
            float cx = (i == 0 ? 62 : 150) * s;
            boolean dead = g.fault == LinerSystems.F_FLAMEOUT;
            float R = 30 * s, cy = 46 * s;
            LinerGfxSVA.arc(cx, cy, R, -125, 85, 1.4F * s, dead ? AMB : WHT);
            LinerGfxSVA.layer();
            float red = LinerDisplaysSVA.n1Ang(104);
            LinerGfxSVA.line(LinerGfxSVA.px(cx, R - 4 * s, red), LinerGfxSVA.py(cy, R - 4 * s, red), LinerGfxSVA.px(cx, R + 4 * s, red),
                    LinerGfxSVA.py(cy, R + 4 * s, red), 2F * s, RED);
            if (!dead) {
                // the thrust limit index (amber), the needle, the thrust lever's circle (cyan)
                float la = LinerDisplaysSVA.n1Ang(lim);
                LinerGfxSVA.line(LinerGfxSVA.px(cx, R - 3 * s, la), LinerGfxSVA.py(cy, R - 3 * s, la), LinerGfxSVA.px(cx, R + 3 * s, la),
                        LinerGfxSVA.py(cy, R + 3 * s, la), 1.6F * s, AMB);
                float a = LinerDisplaysSVA.n1Ang((float) g.n1);
                LinerGfxSVA.line(cx, cy, LinerGfxSVA.px(cx, R, a), LinerGfxSVA.py(cy, R, a), 1.8F * s, GRN);
                float tl = LinerDisplaysSVA.n1Ang((float) (19.8 + 80.2 * thr));
                LinerGfxSVA.disc(LinerGfxSVA.px(cx, R + 4 * s, tl), LinerGfxSVA.py(cy, R + 4 * s, tl), 1.5F * s, CYN);
            }
            double rp = L.afs.revPos[i];
            if (rp > 0.02) {
                // wave 4: the reverser - «REV» amber while the cowls travel, green deployed
                int rc = rp >= 0.95 ? GRN : AMB;
                LinerGfxSVA.layer();
                LinerGfxSVA.rect(cx - 13 * s, cy - 14 * s, cx + 13 * s, cy - 4 * s, 0x000000);
                LinerGfxSVA.layer();
                LinerGfxSVA.frameRect(cx - 13 * s, cy - 14 * s, cx + 13 * s, cy - 4 * s, 0.8F * s, rc);
                LinerGfxSVA.text("REV", cx, cy - 9 * s, 7 * s, rc, 0);
            }
            LinerGfxSVA.layer();
            LinerGfxSVA.frameRect(cx, cy + 6 * s, cx + 36 * s, cy + 20 * s, 1F * s, GREY);
            LinerGfxSVA.text(dead ? "XX" : LinerGfxSVA.num(g.n1, 1), cx + 34 * s, cy + 13 * s, 10 * s, dead ? AMB : GRN, 1);
            float R2 = 20 * s, cy2 = 108 * s;
            LinerGfxSVA.arc(cx, cy2, R2, -90, 90, 1.3F * s, dead ? AMB : WHT);
            LinerGfxSVA.layer();
            float el = LinerDisplaysSVA.egtAng((float) LinerSystems.EGT_START_LIMIT);
            LinerGfxSVA.line(LinerGfxSVA.px(cx, R2 - 3 * s, el), LinerGfxSVA.py(cy2, R2 - 3 * s, el), LinerGfxSVA.px(cx, R2 + 4 * s, el),
                    LinerGfxSVA.py(cy2, R2 + 4 * s, el), 2F * s, RED);
            if (!dead) {
                float a2 = LinerDisplaysSVA.egtAng((float) g.egt);
                LinerGfxSVA.line(cx, cy2, LinerGfxSVA.px(cx, R2, a2), LinerGfxSVA.py(cy2, R2, a2), 1.6F * s,
                        g.egt > LinerSystems.EGT_START_LIMIT && g.state != LinerSystems.E_RUN ? RED : GRN);
            }
            LinerGfxSVA.text(dead ? "XX" : String.valueOf(Math.round(g.egt)), cx, cy2 + 12 * s, 9 * s, dead ? AMB : GRN, 0);
            boolean starting = g.state == LinerSystems.E_CRANK || g.state == LinerSystems.E_LIGHT;
            if (starting) {
                LinerGfxSVA.rect(cx - 18 * s, 140 * s, cx + 18 * s, 152 * s, 0x3A3E46);
                LinerGfxSVA.layer();
            }
            LinerGfxSVA.text(dead ? "XX" : LinerGfxSVA.num(g.n2, 1), cx, 146 * s, 10 * s, dead ? AMB : GRN, 0);
            if (g.ign) {
                LinerGfxSVA.text("IGN " + (i == 0 ? "A" : "B"), cx, 134 * s, 7 * s, WHT, 0);
            }
            LinerGfxSVA.text(dead ? "XX" : String.valueOf(Math.round(g.ff)), cx, 166 * s, 10 * s, dead ? AMB : GRN, 0);
        }
        LinerGfxSVA.text("N1", 106 * s, 34 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text("%", 106 * s, 44 * s, 7 * s, CYN, 0);
        LinerGfxSVA.text("EGT", 106 * s, 100 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text("C", 106 * s, 110 * s, 7 * s, CYN, 0);
        LinerGfxSVA.text("N2", 106 * s, 146 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text("FF", 106 * s, 162 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("mbmin"), 106 * s, 171 * s, 6 * s, CYN, 0);
    }

    /** The A320's flap / slat indicator: S left, F right, the lever's setting in cyan. */
    static void flaps(float x, float y, float s, float flapPos, float slat, int lever) {
        LinerGfxSVA.text("S", x, y, 8 * s, WHT, 0);
        LinerGfxSVA.text("F", x + 60 * s, y, 8 * s, WHT, 0);
        LinerGfxSVA.rect(x + 22 * s, y - 3 * s, x + 38 * s, y + 3 * s, WHT);
        LinerGfxSVA.layer();
        float sl = slat * 14 * s, fl = flapPos / 4F * 18 * s;
        LinerGfxSVA.line(x + 22 * s, y, x + 22 * s - sl, y + sl * 0.5F, 3F * s, GRN);
        LinerGfxSVA.line(x + 38 * s, y, x + 38 * s + fl, y + fl * 0.5F, 3F * s, GRN);
        String name = lever == 0 ? (slat > 0.5F ? "1" : "") : lever == 4 ? "FULL" : lever == 1 ? "1+F" : String.valueOf(lever);
        LinerGfxSVA.text(name, x + 30 * s, y + 16 * s, 9 * s, CYN, 0);
    }

    /** Line height and the area's bottom (units). */
    static final float LH = 9.6F, TOP = 196, BOTTOM = 274;

    /** The left part: the messages and their actions; else T.O. / LDG MEMO; else the starts. */
    static void left(LinerSystems L, float s, boolean ground) {
        LinerEcam ec = L.ecam;
        int[] list = ec.list();
        float y = TOP;
        if (list.length > 0) {
            boolean more = false;
            for (int k = 0; k < list.length; k++) {
                int id = list[k];
                int[] acts = ec.openActions(id);
                if (y + LH * (1 + (k == 0 ? acts.length : 0)) > BOTTOM + 0.5F && k > 0) {
                    more = true;
                    break;
                }
                int col = LinerEcam.LEVEL[id] == LinerEcam.WARN ? RED : AMB;
                String t = lang("sva.liner.msg." + LinerEcam.KEYS[id]);
                LinerGfxSVA.text(t, 6 * s, y * s, 8 * s, col, -1, 166 * s);
                y += LH;
                for (int a : acts) {
                    if (y > BOTTOM) {
                        more = true;
                        break;
                    }
                    action(lang("sva.liner.act." + LinerEcam.ACT_KEYS[a]), 10 * s, 170 * s, y * s, s, CYN, false);
                    y += LH;
                }
            }
            if (more) {
                LinerGfxSVA.text(tr("more"), 172 * s, 278 * s, 6.5F * s, GREY, 1);
            }
            return;
        }
        if (ec.toMemoShown(L) || ec.ldgMemoShown()) {
            boolean to = ec.toMemoShown(L);
            String[] keys = to ? LinerEcam.TO_KEYS : LinerEcam.LDG_KEYS;
            int done = to ? ec.toMemo(L) : LinerEcam.ldgMemo(L);
            String title = tr(to ? "toMemo" : "ldgMemo");
            LinerGfxSVA.text(title, 6 * s, y * s, 8 * s, WHT, -1);
            LinerGfxSVA.line(6 * s, (y + 4.5F) * s, (8 + 6 * title.length()) * s, (y + 4.5F) * s, 0.8F * s, WHT);
            y += LH;
            for (int i = 0; i < keys.length && y <= BOTTOM; i++) {
                boolean ok = (done & 1 << i) != 0;
                action(lang("sva.liner." + (to ? "tom." : "ldm.") + keys[i]), 14 * s, 170 * s, y * s, s, ok ? GRN : CYN, ok);
                y += LH;
            }
            return;
        }
        for (int i = 0; i < 2; i++) {
            LinerSystems.Eng g = L.eng[i];
            if ((g.state == LinerSystems.E_CRANK || g.state == LinerSystems.E_LIGHT || g.state == LinerSystems.E_WAIT) && y < BOTTOM) {
                LinerGfxSVA.text(tr("start", Integer.valueOf(i + 1)), 6 * s, y * s, 8 * s, GRN, -1, 166 * s);
                y += LH;
            }
        }
    }

    /**
     * An item / action line «WHAT|SETTING»: done - green «WHAT SETTING» with a tick drawn in front (the mock-up: a line, not a
     * glyph); to do - «- WHAT ...... SETTING» in its colour (the dots fill the gap).
     */
    static void action(String spec, float x0, float x1, float y, float s, int col, boolean tick) {
        int bar = spec.indexOf('|');
        String what = bar < 0 ? spec : spec.substring(0, bar), set = bar < 0 ? "" : spec.substring(bar + 1);
        float hgt = 7.5F * s;
        if (tick) {
            LinerGfxSVA.line(x0 - 7 * s, y, x0 - 5 * s, y + 2.5F * s, 1.3F * s, GRN);
            LinerGfxSVA.line(x0 - 5 * s, y + 2.5F * s, x0 - 1 * s, y - 3 * s, 1.3F * s, GRN);
            LinerGfxSVA.text(what + " " + set, x0, y, hgt, col, -1, x1 - x0);
            return;
        }
        LinerGfxSVA.text(dotted("- " + what, set, LINE_CHARS), x0, y, hgt, col, -1, x1 - x0);
    }

    /** The characters of an action line («- WHAT ... SETTING»: dots fill up to this). */
    static final int LINE_CHARS = 30;

    /** «HEAD ..... TAIL» padded with dots to n characters (at least two dots). */
    static String dotted(String head, String tail, int n) {
        StringBuilder b = new StringBuilder(head).append(' ');
        int dots = Math.max(2, n - head.length() - tail.length() - 2);
        for (int i = 0; i < dots; i++) {
            b.append('.');
        }
        return b.append(' ').append(tail).toString();
    }

    /** The right part: the STATUS summary with failures, else the memos. */
    static void right(LinerSystems L, float s, boolean ground, int batMin) {
        LinerEcam ec = L.ecam;
        float y = TOP;
        int lim = ec.limits(), inop = ec.inop();
        if (ec.top() >= 0 && (lim | inop) != 0) {
            for (int i = 0; i < LinerEcam.LIM_KEYS.length && y <= BOTTOM; i++) {
                if ((lim & 1 << i) != 0) {
                    LinerGfxSVA.text(lang("sva.liner.lim." + LinerEcam.LIM_KEYS[i]), 180 * s, y * s, 7.5F * s, AMB, -1, 96 * s);
                    y += LH;
                }
            }
            if (inop != 0 && y <= BOTTOM - LH) {
                y += 2;
                LinerGfxSVA.text(tr("inop"), 180 * s, y * s, 7.5F * s, WHT, -1, 96 * s);
                y += LH;
                for (int i = 0; i < LinerEcam.INOP_KEYS.length && y <= BOTTOM; i++) {
                    if ((inop & 1 << i) != 0) {
                        LinerGfxSVA.text(lang("sva.liner.inop." + LinerEcam.INOP_KEYS[i]), 180 * s, y * s, 7.5F * s, AMB, -1, 96 * s);
                        y += LH;
                    }
                }
            }
            return;
        }
        int memo = L.memos(ground);
        for (int i = 0; i < LinerSystems.MEMO_KEYS.length; i++) {
            if ((memo & 1 << i) == 0 || y > BOTTOM) {
                continue;
            }
            int bit = 1 << i;
            int c = (bit & LinerSystems.MEMO_AMBER) != 0 ? AMB : bit == LinerSystems.M_STS ? WHT : GRN;
            String t = bit == LinerSystems.M_ON_BAT ? lang("sva.liner.memo.onBat", Integer.valueOf(batMin))
                    : bit == LinerSystems.M_AUTOBRK ? lang("sva.liner.memo.autoBrk", tr("ab." + com.sva.vehicle.LinerGear.AB_KEYS[L.gear.abMode]))
                    : lang("sva.liner.memo." + LinerSystems.MEMO_KEYS[i]);
            LinerGfxSVA.text(t, 180 * s, y * s, 8 * s, c, -1, 96 * s);
            y += LH;
        }
    }
}
