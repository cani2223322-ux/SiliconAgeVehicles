package com.sva.client;

import java.awt.image.BufferedImage;
import java.util.Map;

import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.LinerTexMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 4: the standalone preview's door into the SA-320's faces (docs/mockups/veh_liner_impl_w4_*.png): the
 * preview program sets LinerGfxSVA's sink (a Java2D target) and the lang map, then draws the very code the game draws -
 * the PFD (LinerPfdSVA), the ND's ROSE ILS, the E/WD, the FCU's windows over the painted glare shield, the ILS beacon's
 * screen. Nothing of it runs in the game.
 */
@SideOnly(Side.CLIENT)
public final class LinerPreviewHookSVA {

    private LinerPreviewHookSVA() {
    }

    public static void lang(Map<String, String> m) {
        LinerSdPagesSVA.previewLang = m;
    }

    public static void sink(LinerGfxSVA.Sink s) {
        LinerGfxSVA.sink = s;
        LinerGfxSVA.bright = 1F;
    }

    public static void pfd(LinerPfdSVA.Data d, LinerSystems L, float w, float h) {
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.rect(0, 0, w, h, LinerDisplaysSVA.BG);
        LinerGfxSVA.layer();
        LinerPfdSVA.draw(d, L, w, h);
    }

    public static void rose(LinerPfdSVA.Data d, LinerSystems L, float w, float h, float range) {
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.rect(0, 0, w, h, LinerDisplaysSVA.BG);
        LinerGfxSVA.layer();
        LinerPfdSVA.roseIls(d, L, w, h, range);
    }

    public static void ewd(LinerSystems L, float w, float h, double thr, boolean ground) {
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.rect(0, 0, w, h, LinerDisplaysSVA.BG);
        LinerGfxSVA.layer();
        LinerEwdSVA.draw(L, w, h, thr, (float) L.fctl.flapPos, (float) L.fctl.slatPos, L.at(LinerControls.FLAPS), ground, 17);
    }

    /** Wave 6: the ЭЛЕКТР page (280 x 280 units) with its title - the loads. */
    public static void elec(LinerSystems L, float w, float h) {
        float s = w / 280F;
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.rect(0, 0, w, h, LinerDisplaysSVA.BG);
        LinerGfxSVA.layer();
        LinerGfxSVA.text(LinerSdPagesSVA.lang("sva.liner.sd.elec"), 8 * s, 12 * s, 11 * s, LinerDisplaysSVA.WHT, -1);
        LinerGfxSVA.line(8 * s, 19 * s, 52 * s, 19 * s, 1F * s, LinerDisplaysSVA.WHT);
        LinerDisplaysSVA.sdElec(L, s);
    }

    /** Wave 6: an SD page (LinerEcam.P_*) through the pages' preview. */
    public static void sd(int page, LinerSystems L, float w, float h) {
        LinerSdPagesSVA.preview(page, L, w, h);
    }

    /** Wave 5: the MCDU's keyboard screen (GuiMcduSVA's panel, W x H units) with this screen. */
    public static void mcduPanel(int side, com.sva.vehicle.LinerMcdu.Screen sc) {
        LinerGfxSVA.resetLayer();
        McduPanelSVA.paintPanel(side, true, sc, -1, -1, null, false);
    }

    /** Wave 5: an MCDU screen alone (w x h). */
    public static void mcduScreen(com.sva.vehicle.LinerMcdu.Screen sc, float w, float h) {
        LinerGfxSVA.resetLayer();
        McduScreenSVA.draw(sc, 0, 0, w, h, true);
    }

    /**
     * Wave 5: the ND (280 x 280) in ARC (or PLAN, north up) with the FMS' plan - the compass, the range ring, the plan
     * (LinerDisplaysSVA.ndFms: names, constraints, T/C, T/D, TMPY), the aircraft; GS / TAS, GPS PRIMARY - the game's ND
     * without the entity.
     */
    public static void ndFms(com.sva.vehicle.LinerFms.NdView nv, LinerSystems L, double ax, double az, float hdg, float kmh, float range, boolean plan) {
        float w = 280, h = 280, s = 1F;
        int WHT = LinerDisplaysSVA.WHT, GRN = LinerDisplaysSVA.GRN, GREY = LinerDisplaysSVA.GREY, YEL = LinerDisplaysSVA.YEL, CYN = LinerDisplaysSVA.CYN;
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.rect(0, 0, w, h, LinerDisplaysSVA.BG);
        LinerGfxSVA.layer();
        LinerGfxSVA.text("GS", 6, 10, 8, WHT, -1);
        LinerGfxSVA.text(String.valueOf(Math.round(kmh)), 22, 10, 9, GRN, -1);
        LinerGfxSVA.text("TAS", 52, 10, 8, WHT, -1);
        LinerGfxSVA.text(String.valueOf(Math.round(kmh)), 72, 10, 9, GRN, -1);
        float cx = w / 2, cy = plan ? h / 2 : 240, R = plan ? 110 : 200;
        float rot = plan ? 0 : hdg;
        if (plan) {
            LinerGfxSVA.arc(cx, cy, R, 0, 360, 1.2F, WHT);
            LinerGfxSVA.arc(cx, cy, R / 2, 0, 360, 0.8F, GREY);
        } else {
            LinerGfxSVA.arc(cx, cy, R, -55, 55, 1.2F, WHT);
            LinerGfxSVA.arc(cx, cy, R / 2, -55, 55, 0.8F, GREY);
        }
        LinerGfxSVA.layer();
        for (int k = 0; k < 360; k += 5) {
            float a = k - rot;
            a = ((a + 540) % 360) - 180;
            if (!plan && Math.abs(a) > 55) {
                continue;
            }
            boolean big = k % 10 == 0;
            LinerGfxSVA.line(LinerGfxSVA.px(cx, R, a), LinerGfxSVA.py(cy, R, a), LinerGfxSVA.px(cx, R - (big ? 8 : 4), a), LinerGfxSVA.py(cy, R - (big ? 8 : 4), a),
                    1F, WHT);
            if (k % 30 == 0) {
                LinerGfxSVA.text(String.valueOf(k / 10), LinerGfxSVA.px(cx, R + 9, a), LinerGfxSVA.py(cy, R + 9, a), 9, WHT, 0);
            }
        }
        LinerGfxSVA.text(String.valueOf(Math.round(range / 2)), LinerGfxSVA.px(cx, R / 2 + 6, -60), LinerGfxSVA.py(cy, R / 2 + 6, -60), 7, CYN, 0);
        LinerDisplaysSVA.ndFms(nv, L, ax, az, cx, cy, R, range, rot, s, true, true);
        LinerGfxSVA.layer();
        float ac = plan ? hdg : 0;
        LinerGfxSVA.line(LinerGfxSVA.px(cx, 12, ac), LinerGfxSVA.py(cy, 12, ac), LinerGfxSVA.px(cx, -10, ac), LinerGfxSVA.py(cy, -10, ac), 2.4F, YEL);
        LinerGfxSVA.line(LinerGfxSVA.px(cx, 9, ac - 90), LinerGfxSVA.py(cy, 9, ac - 90), LinerGfxSVA.px(cx, 9, ac + 90), LinerGfxSVA.py(cy, 9, ac + 90), 2.4F,
                YEL);
        LinerGfxSVA.layer();
        LinerGfxSVA.rect(90, 262, 190, 276, 0x000000);
        LinerGfxSVA.layer();
        LinerGfxSVA.frameRect(90, 262, 190, 276, 1F, WHT);
        LinerGfxSVA.text("GPS PRIMARY", w / 2, 269, 8, WHT, 0);
    }

    /** The FCU's run-time windows / lamps (glare shield units). */
    public static void fcu(LinerSystems L) {
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.layer();
        LinerCockpitSVA.fcuWindows(L);
    }

    /** The painted glare shield (2000 x 180 units) out of a 4K atlas. */
    public static BufferedImage glareShield() {
        LinerPaintSVA.Baked b = LinerPaintSVA.bake(4096);
        int[] r = LinerTexMath.RECTS[LinerPaintSVA.rectOf(LinerControls.GS)];
        double k = 4096.0 / LinerTexMath.ATLAS_UNITS;
        return b.day.getSubimage((int) Math.round(r[0] * k), (int) Math.round(r[1] * k), (int) Math.round(r[2] * k), (int) Math.round(r[3] * k));
    }

    /** The ILS beacon's screen with these values, the mouse at (mx, my) (screen units). */
    public static void ilsGui(int course, int angle10, String ident, int freq, int offset, int elev, double range, float mx, float my) {
        IlsScreenSVA.View v = new IlsScreenSVA.View();
        v.course = course;
        v.angle10 = angle10;
        v.ident = ident;
        v.freq = freq;
        v.offset = offset;
        v.elev = elev;
        v.range = range;
        LinerGfxSVA.resetLayer();
        IlsScreenSVA.paint(v, mx, my);
    }
}
