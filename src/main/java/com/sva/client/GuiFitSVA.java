package com.sva.client;

import org.lwjgl.opengl.GL11;

/**
 * ТИ-2: a fixed-size window on a screen narrower (or lower) than it - 4:3 / 5:4 monitors with the automatic GUI scale -
 * is drawn smaller as a whole, its layout unchanged. The screen gets a virtual size (the real one / the factor) in
 * setWorldAndResolution: guiLeft / guiTop, buttons and the mouse of handleMouseInput (which divides by this.width)
 * all live in it then; drawScreen's mouse (worked out from the real size) is divided here and the drawing scaled.
 * <pre>
 *   setWorldAndResolution(mc, w, h): fit = GuiFitSVA.fit(w, h, W, H); super(mc, GuiFitSVA.virtual(w, fit), GuiFitSVA.virtual(h, fit))
 *   drawScreen(mx, my, pt): GuiFitSVA.begin(fit); ...draw with GuiFitSVA.mouse(mx, fit)...; GuiFitSVA.end(fit)
 * </pre>
 */
public final class GuiFitSVA {

    /** Free pixels left round a window that had to shrink. */
    public static final int MARGIN = 8;

    private GuiFitSVA() {
    }

    /** The factor (at most 1) that fits a gw x gh window into a w x h screen with MARGIN to spare. */
    public static float fit(int w, int h, int gw, int gh) {
        if (gw <= 0 || gh <= 0 || w <= 0 || h <= 0) {
            return 1F;
        }
        float f = Math.min((w - MARGIN) / (float) gw, (h - MARGIN) / (float) gh);
        return f >= 1F ? 1F : Math.max(0.25F, f);
    }

    /** The virtual size the screen lays its window out in. */
    public static int virtual(int size, float fit) {
        return fit >= 1F ? size : (int) Math.floor(size / fit);
    }

    /** A real-screen mouse coordinate in the virtual screen. */
    public static int mouse(int m, float fit) {
        return fit >= 1F ? m : (int) Math.floor(m / fit);
    }

    public static void begin(float fit) {
        if (fit < 1F) {
            GL11.glPushMatrix();
            GL11.glScalef(fit, fit, 1F);
        }
    }

    public static void end(float fit) {
        if (fit < 1F) {
            GL11.glPopMatrix();
        }
    }
}
