package com.sva.vehicle;

/**
 * Liner wave 7 (the user's «Пульты» request: sharp at 1080p / 1440p / 2160p and GUI scales 1-4, readable labels): the
 * flat panels screen's layout as pure math in DEVICE pixels - the scale (device px per panel unit: the panels are baked
 * at exactly that size, so a texel is a pixel), the zoom and the pan, the text's floor and the mouse -> panel mapping the
 * clicks use. client.GuiLinerPanelsSVA draws with it; the self-test checks it.
 * <p>
 * The default scale makes the panels' usual small labels (LABEL_UNITS) MIN_TEXT_PX tall - a panel larger than the screen
 * then pans (drag) rather than shrinking its lettering; zoomed out to the fit every text still keeps MIN_TEXT_PX (the bake
 * and the overlays enlarge it). The scale never makes a texture over the GL limit.
 */
public final class PanelViewMath {

    /** The least text height (device px) and the panels' usual small label (panel units). */
    public static final double MIN_TEXT_PX = 12, LABEL_UNITS = 9.5;
    /** A zoom step (x / per click or wheel notch) and the most the user may zoom in past the readable scale. */
    public static final double ZOOM_STEP = 1.25, MAX_OVER = 3;

    private PanelViewMath() {
    }

    /** The scale fitting a panel (pw x ph units) into an area (aw x ah device px). */
    public static double fit(double pw, double ph, double aw, double ah) {
        return Math.max(1e-3, Math.min(aw / pw, ah / ph));
    }

    /** The scale at which LABEL_UNITS text is MIN_TEXT_PX tall. */
    public static double readable() {
        return MIN_TEXT_PX / LABEL_UNITS;
    }

    /** The largest scale whose bake fits a texture of maxTex px. */
    public static double maxScale(double pw, double ph, int maxTex) {
        return Math.min(maxTex / pw, maxTex / ph);
    }

    /** The scale a tab opens at: readable, at least the fit (a large screen fits it all), within the texture's limit. */
    public static double initial(double pw, double ph, double aw, double ah, int maxTex) {
        return clamp(Math.max(fit(pw, ph, aw, ah), readable()), minScale(pw, ph, aw, ah, maxTex), maxScale(pw, ph, maxTex));
    }

    /** The zoom's lower bound: the fit (never above the texture limit). */
    public static double minScale(double pw, double ph, double aw, double ah, int maxTex) {
        return Math.min(fit(pw, ph, aw, ah), maxScale(pw, ph, maxTex));
    }

    /** The zoom's upper bound: MAX_OVER x the readable (or the fit when larger), within the texture limit. */
    public static double maxZoom(double pw, double ph, double aw, double ah, int maxTex) {
        return Math.min(maxScale(pw, ph, maxTex), Math.max(readable() * MAX_OVER, fit(pw, ph, aw, ah)));
    }

    /** `steps` zoom steps (+ in) from k, within the bounds. */
    public static double zoom(double k, int steps, double pw, double ph, double aw, double ah, int maxTex) {
        return clamp(k * Math.pow(ZOOM_STEP, steps), minScale(pw, ph, aw, ah, maxTex), maxZoom(pw, ph, aw, ah, maxTex));
    }

    /** The panel's origin after a zoom k0 -> k1 keeping the panel point under the mouse (mx, my) where it is. */
    public static double[] zoomAt(double ox, double oy, double k0, double k1, double mx, double my) {
        return new double[] {mx - (mx - ox) * k1 / k0, my - (my - oy) * k1 / k0};
    }

    /**
     * The panel's origin kept sensible (device px, whole pixels - a baked texel on a pixel): a panel narrower / lower than
     * the area centred, a larger one never leaving a gap at its edges.
     */
    public static double[] clampPan(double ox, double oy, double cw, double ch, double ax, double ay, double aw, double ah) {
        double x = cw <= aw ? ax + (aw - cw) / 2 : clamp(ox, ax + aw - cw, ax);
        double y = ch <= ah ? ay + (ah - ch) / 2 : clamp(oy, ay + ah - ch, ay);
        return new double[] {Math.round(x), Math.round(y)};
    }

    /** A device point -> the panel's units (the hitboxes' frame). */
    public static double[] toPanel(double mx, double my, double ox, double oy, double k) {
        return new double[] {(mx - ox) / k, (my - oy) / k};
    }

    /** A panel point -> device px. */
    public static double[] toDevice(double tx, double ty, double ox, double oy, double k) {
        return new double[] {ox + tx * k, oy + ty * k};
    }

    /** A text's device height: its own at this scale, never under the floor. */
    public static double textPx(double units, double k, double minPx) {
        return Math.max(units * k, minPx);
    }

    /** A bake's side (px) for `units` at scale k. */
    public static int texSize(double units, double k) {
        return (int) Math.ceil(units * k - 1e-6);
    }

    /** The bake's key: a scale step (a re-bake only when it moved more than 0.5 %). */
    public static int scaleKey(double k) {
        return (int) Math.round(Math.log(k) / Math.log(1.005));
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
