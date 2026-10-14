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
