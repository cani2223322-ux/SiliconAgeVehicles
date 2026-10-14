package com.sva.client;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerTexMath;

/**
 * Liner wave 1 (plan-next §7, mock-ups veh_liner_6 / 7 / 8 / 9 / 10): the SA-320's textures painted procedurally from ONE
 * description - the port of the concept's painters (scratchpad liner/interior/itex.py, icabtex.py; liner/liner3d.py's
 * livery) to Java2D. Pure Java2D (no Minecraft / LWJGL classes): client/LinerTexSVA bakes it off the render thread and
 * uploads it, and the standalone preview (debug/LinerPreviewMain) paints the very same pixels.
 * <p>
 * <b>Units.</b> Every panel is drawn in its own units (1 unit ~ 1 mm; LinerControls.PANEL_W / H) into its rect of the
 * atlas (LinerTexMath.RECTS - the 4096-unit layout) at size / 4096 px a unit: 4K = 1 px a unit, LOW (512) = 1/8 - the
 * tiers are the same picture at another sharpness (mock-up 10).
 * <p>
 * <b>What is baked, what is not.</b> The panels' static furniture - modules with seams and Dzus fasteners, the white group
 * lines and titles, the push buttons' bodies with their dim lamp words, the selectors' marks, the toggles' nuts and
 * guards, the windows' black glass, the screens' bezels, the MCDU keys, the placards. Everything that changes with the
 * systems is drawn at run time over it from the same control table (client/LinerCockpitSVA): the lit lamp halves, the
 * selector bars, the toggle levers, the window numbers, the 3D levers. The GLOW image (half size) is the integral panel
 * lighting: the labels and the group lines in the warm lit colour, blurred - added at night by the panel brightness.
 * <p>
 * <b>Livery.</b> bakeLivery paints the exterior's textured parts (the EV «Гражданская» livery of the concept: navy over
 * EV red cheatline sweeping up to the fin, the SA emblem, the title, the windows, the doors, the board number) at a fixed
 * 80 px a block into a 2048 x 1024 image; the cabin windows / the L1 door at their wave-1 places (the door moved aft to
 * x 5.00..5.60, the windows over the cabin's rows).
 */
public final class LinerPaintSVA {

    // ---------------------------------------------------------------- palette (itex.py)

    static final Color PANEL = rgb(90, 106, 121), LINE = rgb(236, 238, 240), LIT = rgb(240, 216, 160);
    static final Color AMB = rgb(255, 170, 40), WHT = rgb(244, 244, 236), BLU = rgb(70, 170, 255), GRN = rgb(60, 232, 110), RED = rgb(238, 46, 34),
            CYN = rgb(90, 220, 255);
    static final Color WALL = rgb(232, 230, 224), SKY = rgb(120, 170, 225);

    static Color rgb(int r, int g, int b) {
        return new Color(r, g, b);
    }

    static Color mul(Color c, double k) {
        return new Color(clamp((int) (c.getRed() * k)), clamp((int) (c.getGreen() * k)), clamp((int) (c.getBlue() * k)));
    }

    static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    /** A lamp word's colour unlit (itex.dim). */
    static Color dim(Color c) {
        if (c.equals(AMB)) {
            return rgb(92, 70, 40);
        }
        if (c.equals(WHT)) {
            return rgb(86, 88, 88);
        }
        if (c.equals(BLU)) {
            return rgb(46, 66, 92);
        }
        if (c.equals(GRN)) {
            return rgb(44, 84, 58);
        }
        if (c.equals(RED)) {
            return rgb(92, 40, 36);
        }
        return mul(c, 0.35);
    }

    // ---------------------------------------------------------------- fonts

    private static Font narrow, mono, arial;
    private static boolean narrowReal;
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private static synchronized void fonts() {
        if (narrow != null) {
            return;
        }
        java.util.Set<String> have = new java.util.HashSet<String>();
        try {
            for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
                have.add(f);
            }
        } catch (Throwable t) {
            // a broken font config: the logical fonts
        }
        String cyr = "АБВЯж";
        Font n = have.contains("Arial Narrow") ? new Font("Arial Narrow", Font.BOLD, 100) : null;
        narrowReal = n != null && n.canDisplayUpTo(cyr) == -1;
        narrow = narrowReal ? n : new Font(have.contains("Arial") ? "Arial" : Font.SANS_SERIF, Font.BOLD, 100);
        Font m = have.contains("Consolas") ? new Font("Consolas", Font.BOLD, 100) : null;
        mono = m != null && m.canDisplayUpTo(cyr) == -1 ? m : new Font(Font.MONOSPACED, Font.BOLD, 100);
        arial = new Font(have.contains("Arial") ? "Arial" : Font.SANS_SERIF, Font.BOLD, 100);
    }

    /** A font of kind n (narrow bold), r (narrow), m (mono bold), a (arial bold) at `px` pixels. */
    static Font font(char kind, double px) {
        fonts();
        Font base = kind == 'm' ? mono : kind == 'a' ? arial : narrow;
        float sz = (float) Math.max(1, px);
        if ((kind == 'n' || kind == 'r') && !narrowReal) {
            return base.deriveFont(AffineTransform.getScaleInstance(sz / 100.0 * 0.82, sz / 100.0));      // a condensed sans
        }
        Font f = base.deriveFont(sz);
        return kind == 'r' ? f.deriveFont(Font.PLAIN) : f;
    }

    // ---------------------------------------------------------------- the painter (itex.Pnt)

    /**
     * Paints one panel in its units into its rect of the day image (and the glow image): y down, (0, 0) = the panel's
     * top-left. `night` only picks the label colour of the GLOW (the lit colour); the day image always has the white labels.
     */
    static final class P {
        final Graphics2D g, gl;
        final double k, x0, y0, gk, gx0, gy0, wu, hu;
        final Color lab = LINE;
        Color base = PANEL;

        P(BufferedImage day, BufferedImage glow, double ux, double uy, double wu, double hu, double k, Color bg, long seed, double matte) {
            this.k = k;
            this.wu = wu;
            this.hu = hu;
            x0 = ux * k;
            y0 = uy * k;
            g = smooth(day.createGraphics());
            g.setClip(new Rectangle2D.Double(x0, y0, wu * k, hu * k));
            double gs = glow == null ? 0 : glow.getWidth() / (double) day.getWidth();
            gk = k * gs;
            gx0 = ux * gk;
            gy0 = uy * gk;
            gl = glow == null ? null : smooth(glow.createGraphics());
            if (gl != null) {
                gl.setClip(new Rectangle2D.Double(gx0, gy0, wu * gk, hu * gk));
                gl.setColor(Color.BLACK);
                gl.fill(new Rectangle2D.Double(gx0, gy0, wu * gk, hu * gk));
            }
            if (bg != null) {
                base = bg;
                g.setColor(bg);
                g.fill(new Rectangle2D.Double(x0, y0, wu * k, hu * k));
                if (matte > 0) {
                    matte(seed, matte);
                }
            }
        }

        void dispose() {
            g.dispose();
            if (gl != null) {
                gl.dispose();
            }
        }

        /** The concept's matte: a soft low-frequency noise of +-matte brightness (a 7-unit grid, bilinear). */
        private void matte(long seed, double amount) {
            int nw = Math.max(2, (int) (wu / 7)), nh = Math.max(2, (int) (hu / 7));
            BufferedImage n = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            Random r = new Random(seed);
            for (int y = 0; y < nh; y++) {
                for (int x = 0; x < nw; x++) {
                    double v = r.nextGaussian() * amount;
                    int a = clamp((int) (Math.abs(v) * 255 * 0.9));
                    n.setRGB(x, y, a << 24 | (v > 0 ? 0xFFFFFF : 0));
                }
            }
            Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(n, (int) Math.round(x0), (int) Math.round(y0), (int) Math.round(wu * k), (int) Math.round(hu * k), null);
            if (hint != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
            }
        }

        double X(double u) {
            return x0 + u * k;
        }

        double Y(double v) {
            return y0 + v * k;
        }

        double GX(double u) {
            return gx0 + u * gk;
        }

        double GY(double v) {
            return gy0 + v * gk;
        }

        Shape rshape(double ax, double ay, double bx, double by, double r, boolean glow) {
            double xa = glow ? GX(ax) : X(ax), ya = glow ? GY(ay) : Y(ay), xb = glow ? GX(bx) : X(bx), yb = glow ? GY(by) : Y(by);
            double kk = glow ? gk : k;
            double rx = Math.min(xa, xb), ry = Math.min(ya, yb), w = Math.abs(xb - xa), h = Math.abs(yb - ya);
            return r > 0 ? new RoundRectangle2D.Double(rx, ry, w, h, 2 * r * kk, 2 * r * kk) : new Rectangle2D.Double(rx, ry, w, h);
        }

        void rect(double ax, double ay, double bx, double by, Color fill, Color outline, double w, double r, Color glow) {
            if (fill != null) {
                g.setColor(fill);
                g.fill(rshape(ax, ay, bx, by, r, false));
            }
            if (outline != null) {
                g.setColor(outline);
                g.setStroke(new BasicStroke((float) Math.max(0.6, w * k)));
                double h = w / 2;
                g.draw(rshape(ax + h, ay + h, bx - h, by - h, Math.max(0, r - h), false));
            }
            if (glow != null && gl != null) {
                gl.setColor(glow);
                gl.fill(rshape(ax, ay, bx, by, r, true));
            }
        }

        void rect(double ax, double ay, double bx, double by, Color fill) {
            rect(ax, ay, bx, by, fill, null, 0, 0, null);
        }

        void rect(double ax, double ay, double bx, double by, Color fill, double r) {
            rect(ax, ay, bx, by, fill, null, 0, r, null);
        }

        void ell(double cx, double cy, double r, Color fill, Color outline, double w, double ry, Color glow) {
            double rr = ry > 0 ? ry : r;
            if (fill != null) {
                g.setColor(fill);
                g.fill(new Ellipse2D.Double(X(cx - r), Y(cy - rr), 2 * r * k, 2 * rr * k));
            }
            if (outline != null) {
                g.setColor(outline);
                g.setStroke(new BasicStroke((float) Math.max(0.6, w * k)));
                g.draw(new Ellipse2D.Double(X(cx - r + w / 2), Y(cy - rr + w / 2), (2 * r - w) * k, (2 * rr - w) * k));
            }
            if (glow != null && gl != null) {
                gl.setColor(glow);
                gl.fill(new Ellipse2D.Double(GX(cx - r), GY(cy - rr), 2 * r * gk, 2 * rr * gk));
            }
        }

        void ell(double cx, double cy, double r, Color fill) {
            ell(cx, cy, r, fill, null, 0, 0, null);
        }

        GeneralPath path(double[] pts, boolean glow, boolean close) {
            GeneralPath p = new GeneralPath();
            for (int i = 0; i + 1 < pts.length; i += 2) {
                double x = glow ? GX(pts[i]) : X(pts[i]), y = glow ? GY(pts[i + 1]) : Y(pts[i + 1]);
                if (i == 0) {
                    p.moveTo((float) x, (float) y);
                } else {
                    p.lineTo((float) x, (float) y);
                }
            }
            if (close) {
                p.closePath();
            }
            return p;
        }

        void line(double[] pts, Color col, double w, Color glow) {
            g.setColor(col);
            g.setStroke(new BasicStroke((float) Math.max(0.5, w * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(path(pts, false, false));
            if (glow != null && gl != null) {
                gl.setColor(glow);
                gl.setStroke(new BasicStroke((float) Math.max(0.5, w * 1.6 * gk), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                gl.draw(path(pts, true, false));
            }
        }

        void line(double[] pts, Color col, double w) {
            line(pts, col, w, null);
        }

        void poly(double[] pts, Color fill, Color outline, double w) {
            if (fill != null) {
                g.setColor(fill);
                g.fill(path(pts, false, true));
            }
            if (outline != null) {
                g.setColor(outline);
                g.setStroke(new BasicStroke((float) Math.max(0.5, w * k)));
                g.draw(path(pts, false, true));
            }
        }

        /** Text at (x, y) - anchor "mm" centre / "lm" left / "rm" right on the cap middle; "rb" right on the baseline. */
        void text(double x, double y, String t, double size, Color col, String anchor, char kind, Color glow) {
            if (t == null || t.length() == 0) {
                return;
            }
            draw(g, x0, y0, k, x, y, t, size, col, anchor, kind);
            if (glow != null && gl != null) {
                draw(gl, gx0, gy0, gk, x, y, t, size, glow, anchor, kind);
            }
        }

        void text(double x, double y, String t, double size, Color col) {
            text(x, y, t, size, col, "mm", 'n', null);
        }

        private static void draw(Graphics2D gg, double ox, double oy, double kk, double x, double y, String t, double size, Color col,
                String anchor, char kind) {
            Font f = font(kind, size * kk);
            Rectangle2D b = f.getStringBounds(t, FRC);
            double w = b.getWidth();
            double cap = f.createGlyphVector(FRC, "H").getVisualBounds().getHeight();
            char h = anchor.charAt(0), v = anchor.length() > 1 ? anchor.charAt(1) : 'm';
            double px = ox + x * kk - (h == 'm' ? w / 2 : h == 'r' ? w : 0);
            double py = oy + y * kk + (v == 'm' ? cap / 2 : v == 't' ? cap : 0);
            gg.setFont(f);
            gg.setColor(col);
            gg.drawString(t, (float) px, (float) py);
        }

        /** A white label (the panel's lettering) - lit in the glow image. */
        void label(double x, double y, String t, double size, String anchor) {
            text(x, y, t, size, lab, anchor, 'n', mul(LIT, 0.9));
        }

        void label(double x, double y, String t, double size) {
            label(x, y, t, size, "mm");
        }

        /** A soft drop shadow (itex.shadow) under a rect: a few spreading translucent layers. */
        void shadow(double ax, double ay, double bx, double by, double ox, double oy, double blur, double a, boolean ellipse) {
            Composite c = g.getComposite();
            int n = 4;
            for (int i = n; i >= 1; i--) {
                double grow = blur * i / n;
                float al = (float) (a / n * 1.4);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1F, al)));
                g.setColor(Color.BLACK);
                if (ellipse) {
                    g.fill(new Ellipse2D.Double(X(ax + ox - grow), Y(ay + oy - grow), (bx - ax + 2 * grow) * k, (by - ay + 2 * grow) * k));
                } else {
                    g.fill(rshape(ax + ox - grow, ay + oy - grow, bx + ox + grow, by + oy + grow, grow, false));
                }
            }
            g.setComposite(c);
        }

        // ------------------------------------------------------------ furniture

        /** A panel module: a dark seam, a light bevel, Dzus fasteners at the corners. */
        void module(double ax, double ay, double bx, double by, boolean screws) {
            Color sh = mul(base, 0.45), hi = mul(base, 1.22);
            rect(ax, ay, bx, by, null, sh, 2.2, 0, null);
            line(new double[] {ax + 2, by - 2, ax + 2, ay + 2, bx - 2, ay + 2}, hi, 0.9);
            if (screws) {
                double[][] s = {{ax + 9, ay + 9}, {bx - 9, ay + 9}, {ax + 9, by - 9}, {bx - 9, by - 9}};
                for (double[] q : s) {
                    screw(q[0], q[1], 4.2, ((int) (q[0] * 7 + q[1] * 13)) % 180);
                }
            }
        }

        void module(double ax, double ay, double bx, double by) {
            module(ax, ay, bx, by, true);
        }

        void screw(double x, double y, double r, double ang) {
            Color c = rgb(150, 156, 162);
            ell(x + 0.6, y + 0.8, r, rgb(60, 70, 80));
            ell(x, y, r, c, mul(c, 0.6), 0.7, 0, null);
            double a = Math.toRadians(ang), dx = Math.cos(a) * r * 0.75, dy = Math.sin(a) * r * 0.75;
            line(new double[] {x - dx, y - dy, x + dx, y + dy}, mul(c, 0.45), 1.1);
        }

        double tw(String t, double size) {
            Font f = font('n', size * k);
            return f.getStringBounds(t, FRC).getWidth() / k;
        }

        void group(double ax, double ay, double bx, double by, String title, double size) {
            double w = tw(title, size) + 14, cx = (ax + bx) / 2;
            line(new double[] {cx - w / 2, ay, ax, ay, ax, by, bx, by, bx, ay, cx + w / 2, ay}, lab, 2.0, LIT);
            label(cx, ay, title, size);
        }

        void group(double ax, double ay, double bx, double by, String title) {
            group(ax, ay, bx, by, title, 17);
        }

        void sub(double ax, double bx, double y, String title, double size) {
            double w = tw(title, size) + 8, cx = (ax + bx) / 2;
            line(new double[] {ax, y + 6, ax, y, cx - w / 2, y}, lab, 1.3, LIT);
            line(new double[] {cx + w / 2, y, bx, y, bx, y + 6}, lab, 1.3, LIT);
            label(cx, y, title, size);
        }

        // ------------------------------------------------------------ controls (their static look)

        /** An A320 two-part push button: the body with both halves' words dim (lit at run time); the label over it. */
        void pb(double cx, double cy, String label, String top, Color topC, String bot, Color botC, double B, boolean guard, double lsize,
                double tsize) {
            double h = B / 2;
            if (guard) {
                Color gc = rgb(120, 18, 16);
                rect(cx - h - 9, cy - h - 9, cx + h + 9, cy + h + 9, null, gc, 3.2, 4, null);
                for (int i = -3; i < 4; i++) {
                    double o = (i + 3) * (B + 18) / 6;
                    line(new double[] {cx - h - 9, cy - h - 9 + o, cx - h - 3, cy - h - 3 + o}, gc, 1.2);
                }
            }
            shadow(cx - h, cy - h, cx + h, cy + h, 1.6, 2.4, 2.2, 0.55, false);
            rect(cx - h, cy - h, cx + h, cy + h, rgb(26, 27, 29), 3.5);
            line(new double[] {cx - h + 1.5, cy + h - 3, cx - h + 1.5, cy - h + 1.5, cx + h - 3, cy - h + 1.5}, rgb(70, 72, 76), 1.0);
            double i = 5.5;
            rect(cx - h + i, cy - h + i, cx + h - i, cy + h - i, rgb(12, 13, 14), 2);
            double ts = tsize > 0 ? tsize : B * 0.22;
            if (top != null) {
                text(cx, cy - h / 2 + 1.5, top, fit(top, ts), dim(topC), "mm", 'n', null);
            }
            if (bot != null) {
                text(cx, cy + h / 2 - 1.5, bot, fit(bot, ts), dim(botC), "mm", 'n', null);
            }
            if (label != null) {
                String[] ls = label.split("\n");
                for (int j = 0; j < ls.length; j++) {
                    label(cx, cy - h - 10 - (ls.length - 1 - j) * (lsize + 1), ls[j], lsize);
                }
            }
        }

        double fit(String t, double ts) {
            return ts * (t.length() <= 6 ? 1 : t.length() > 7 ? 6.6 / t.length() : 0.92);
        }

        void pb(double cx, double cy, String label, String top, Color topC, String bot, Color botC) {
            pb(cx, cy, label, top, topC, bot, botC, 54, false, 13.5, 0);
        }

        /** A dark lamp window with its word dim (lit at run time). */
        void lamp(double cx, double cy, String t, Color col, double w, double h, double size) {
            rect(cx - w / 2 - 3, cy - h / 2 - 3, cx + w / 2 + 3, cy + h / 2 + 3, rgb(26, 27, 29), 2.5);
            rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, rgb(12, 13, 14));
            text(cx, cy, t, size, dim(col));
        }

        /** A digital window: frame and black glass; `t` drawn only for the static ones (else the numbers come at run time). */
        void window(double cx, double cy, String t, double w, double h, Color col, double size, char kind) {
            rect(cx - w / 2 - 4, cy - h / 2 - 4, cx + w / 2 + 4, cy + h / 2 + 4, rgb(30, 31, 33), 3);
            rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, rgb(6, 7, 8));
            if (t != null) {
                text(cx, cy + 0.5, t, size > 0 ? size : h * 0.62, col, "mm", kind, null);
            }
        }

        /** A knurled round knob seen from the front (FCU style); pointer: its index line (false: drawn at run time). */
        void knob(double cx, double cy, double r, double idx, boolean knurl, Color cap, boolean pointer) {
            shadow(cx - r, cy - r, cx + r, cy + r, 2.5, 3.5, r * 0.18 + 1, 0.6, true);
            ell(cx, cy, r, rgb(16, 16, 18));
            if (knurl) {
                int n = Math.max(24, (int) (r * 2.2));
                for (int i = 0; i < n; i++) {
                    double a = 2 * Math.PI * i / n;
                    int lv = 70 + (int) (40 * Math.max(0, -Math.sin(a + 0.6)));
                    line(new double[] {cx + Math.cos(a) * r * 0.8, cy + Math.sin(a) * r * 0.8, cx + Math.cos(a) * r * 0.985, cy + Math.sin(a) * r * 0.985},
                            rgb(lv, lv, lv + 4), Math.max(0.6, r * 0.045));
                }
            }
            ell(cx, cy, r * 0.76, cap);
            ell(cx - r * 0.18, cy - r * 0.22, r * 0.45, new Color(clamp(cap.getRed() + 10), clamp(cap.getGreen() + 10), clamp(cap.getBlue() + 10)));
            ell(cx, cy, r * 0.5, cap);
            if (pointer) {
                double a = Math.toRadians(idx - 90);
                line(new double[] {cx + Math.cos(a) * r * 0.25, cy + Math.sin(a) * r * 0.25, cx + Math.cos(a) * r * 0.72, cy + Math.sin(a) * r * 0.72},
                        WHT, Math.max(1, r * 0.09));
            }
        }

        /** A bar-knob selector's marks and words on its arc and the dark base (the bar is drawn at run time). */
        void rotary(double cx, double cy, String[] positions, String label, double r, double span, double lsize) {
            int n = positions.length;
            for (int i = 0; i < n; i++) {
                double a = n > 1 ? -span / 2 + span * i / (n - 1) : 0;
                double ar = Math.toRadians(a - 90);
                line(new double[] {cx + Math.cos(ar) * (r + 3), cy + Math.sin(ar) * (r + 3), cx + Math.cos(ar) * (r + 9), cy + Math.sin(ar) * (r + 9)},
                        lab, 1.6, LIT);
                label(cx + Math.cos(ar) * (r + 22), cy + Math.sin(ar) * (r + 17), positions[i], lsize * 0.92);
            }
            shadow(cx - r, cy - r, cx + r, cy + r, 2, 3, 3, 0.5, true);
            ell(cx, cy, r, rgb(28, 29, 31), rgb(62, 64, 68), 1.2, 0, null);
            if (label != null) {
                label(cx, cy + r + 18, label, lsize);
            }
        }

        /** A bat toggle's words beside it, its nut and (guarded) its red guard; the lever is drawn at run time. */
        void toggle(double cx, double cy, String[] positions, String label, boolean guard, double lsize, boolean right, double z) {
            int n = positions.length;
            double[] ys = n == 2 ? new double[] {cy - 20 * z, cy + 20 * z} : new double[] {cy - 22 * z, cy, cy + 22 * z};
            for (int i = 0; i < n; i++) {
                if (right) {
                    label(cx + 16 * z, ys[i], positions[i], lsize * 0.9, "lm");
                } else {
                    label(cx - 16 * z, ys[i], positions[i], lsize * 0.9, "rm");
                }
            }
            if (guard) {
                rect(cx - 13 * z, cy - 30 * z, cx + 13 * z, cy + 30 * z, null, rgb(130, 22, 18), 2.6, 3, null);
            }
            ell(cx, cy, 10.5 * z, rgb(108, 112, 118), rgb(40, 42, 46), 1, 0, null);
            ell(cx, cy, 6.5 * z, rgb(150, 154, 160));
            if (label != null) {
                String[] ls = label.split("\n");
                for (int j = 0; j < ls.length; j++) {
                    label(cx + (right ? 14 : -14) * z, cy - 40 * z - (ls.length - 1 - j) * (lsize + 1), ls[j], lsize);
                }
            }
        }

        void fireHandle(double cx, double cy, String name) {
            double w = 118, h = 54;
            shadow(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 3, 5, 4, 0.6, false);
            Color col = rgb(150, 22, 20);
            rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, col, 8);
            rect(cx - w / 2 + 6, cy - h / 2 + 5, cx + w / 2 - 6, cy - h / 2 + 12, mul(col, 1.25), 3);
            text(cx, cy - 7, name, 15, rgb(255, 240, 230), "mm", 'n', mul(LIT, 0.8));
            text(cx, cy + 12, "ПОЖАР", 13.5, rgb(60, 8, 8));
            label(cx, cy + h / 2 + 11, "ТЯНУТЬ / ПОВЕРНУТЬ", 10);
        }

        void key(double ax, double ay, double bx, double by, String t, double size, Color col, Color tcol) {
            shadow(ax, ay, bx, by, 1.5, 2.5, 1.8, 0.6, false);
            rect(ax, ay, bx, by, mul(col, 0.7), 2.5);
            rect(ax + 0.8, ay + 0.6, bx - 0.8, by - 2.2, col, 2.2);
            line(new double[] {ax + 2, ay + 1.4, bx - 2, ay + 1.4}, mul(col, 1.5), 0.9);
            if (t != null && t.length() > 0) {
                text((ax + bx) / 2, (ay + by) / 2 - 0.8, t, size, tcol, "mm", 'n', mul(LIT, 0.7));
            }
        }

        void key(double ax, double ay, double bx, double by, String t, double size, Color col) {
            key(ax, ay, bx, by, t, size, col, WHT);
        }
    }

    static Graphics2D smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    static final Color O = AMB;

    // ---------------------------------------------------------------- OVERHEAD (1800 x 1400)

    static void overhead(P p) {
        double[][] mods = {{10, 10, 650, 400}, {650, 10, 1230, 400}, {1230, 10, 1790, 400}, {10, 400, 570, 770}, {570, 400, 1190, 770},
                {1190, 400, 1790, 770}, {10, 770, 650, 1090}, {650, 770, 970, 1090}, {970, 770, 1250, 1090}, {1250, 770, 1790, 1090}, {10, 1090, 1790, 1390}};
        for (double[] m : mods) {
            p.module(m[0], m[1], m[2], m[3]);
        }
        // ADIRS
        p.group(40, 40, 620, 380, "ADIRS  (ИНЕРЦ. СИСТЕМА)");
        p.lamp(105, 100, "НА АКБ", AMB, 76, 26, 12.5);
        p.window(105, 175, null, 76, 28, GRN, 15, 'm');
        p.label(105, 150, "РЕЖИМ", 11);
        double[] xs = {250, 385, 520};
        String[] nm = {"1", "3", "2"};
        for (int i = 0; i < 3; i++) {
            double x = xs[i];
            p.pb(x, 110, "IR " + nm[i], "ОТКАЗ", O, "ВЫКЛ", WHT);
            p.pb(x, 220, "ADR " + nm[i], "ОТКАЗ", O, "ВЫКЛ", WHT);
            p.lamp(x + 58, 352, "ВЫСТ.", WHT, 40, 18, 9.5);
            p.rotary(x + 6, 318, new String[] {"ВЫКЛ", "НАВ", "ПРОСТР"}, null, 19, 100, 11.5);
            p.label(x + 6, 365, "ИНС " + nm[i], 12.5);
        }
        // FIRE (wave 3: the fire system; drawn)
        p.group(680, 40, 1200, 380, "ПОЖАР");
        double[][] fire = {{770, 2}, {940, 1}, {1110, 2}};
        String[] fn = {"ДВ 1", "ВСУ", "ДВ 2"};
        for (int i = 0; i < 3; i++) {
            double x = fire[i][0];
            int ag = (int) fire[i][1];
            p.fireHandle(x, 115, fn[i]);
            for (int j = 0; j < ag; j++) {
                double ax = ag == 1 ? x : x - 34 + 68 * j;
                p.pb(ax, 245, "АГЕНТ" + (ag == 1 ? "" : " " + (j + 1)), "РАЗРЯЖ.", AMB, "ПИРОПАТР.", WHT, 50, false, 13.5, 8.4);
            }
            p.pb(x, 335, null, null, null, "ТЕСТ", WHT, 38, false, 13.5, 9);
        }
        // F/CTL computers
        p.group(1260, 40, 1760, 380, "УПР  (ВЫЧИСЛИТЕЛИ)");
        double[] fx1 = {1340, 1460, 1580};
        String[] fn1 = {"ELAC 1", "SEC 1", "FAC 1"};
        for (int i = 0; i < 3; i++) {
            p.pb(fx1[i], 120, fn1[i], "ОТКАЗ", O, "ВЫКЛ", WHT);
        }
        double[] fx2 = {1320, 1430, 1540, 1660};
        String[] fn2 = {"ELAC 2", "SEC 2", "SEC 3", "FAC 2"};
        for (int i = 0; i < 4; i++) {
            p.pb(fx2[i], 270, fn2[i], "ОТКАЗ", O, "ВЫКЛ", WHT);
        }
        p.window(1670, 350, "НОРМ", 80, 22, GRN, 13, 'm');
        p.label(1555, 350, "ЗАКОН:", 12);
        // HYD
        p.group(40, 430, 540, 740, "ГИДР");
        double[] hx = {100, 205, 300, 395, 490};
        Color[] hc = {rgb(60, 200, 90), rgb(70, 140, 230), null, rgb(230, 200, 60), rgb(230, 200, 60)};
        for (int i = 0; i < 5; i++) {
            if (hc[i] != null) {
                p.line(new double[] {hx[i], 595, hx[i], 640}, hc[i], 3);
            }
        }
        p.line(new double[] {100, 640, 490, 640}, p.lab, 1.4, LIT);
        p.pb(hx[0], 540, "ДВ 1\nНАСОС", "ОТКАЗ", O, "ВЫКЛ", WHT);
        p.pb(hx[1], 540, "ЭЛ. НАСОС\nСИНЯЯ", "ОТКАЗ", O, "АВТО", WHT);
        p.pb(hx[2], 540, "PTU", "ОТКАЗ", O, "АВТО", WHT);
        p.pb(hx[3], 540, "ЭЛ. НАСОС\nЖЁЛТАЯ", "ОТКАЗ", O, "ВКЛ", BLU);
        p.pb(hx[4], 540, "ДВ 2\nНАСОС", "ОТКАЗ", O, "ВЫКЛ", WHT);
        p.label(100, 660, "ЗЕЛ", 11);
        p.label(205, 660, "СИН", 11);
        p.label(442, 660, "ЖЁЛТ", 11);
        p.pb(300, 700, "RAT  ВЫПУСК", null, null, "ВРУЧН.", WHT, 40, true, 13.5, 9.5);
        // FUEL
        p.group(600, 430, 1160, 740, "ТОПЛ");
        p.pb(880, 500, "КОЛЬЦЕВ.", "ОТКР", GRN, "ВКЛ", BLU);
        p.pb(1090, 500, "РЕЖИМ", "ЦЕНТР", AMB, "РУЧН", WHT);
        p.line(new double[] {680, 500, 845, 500}, p.lab, 1.4, LIT);
        p.line(new double[] {915, 500, 1040, 500}, p.lab, 1.4, LIT);
        p.sub(640, 760, 600, "ЛЕВ. БАК", 12.5);
        p.sub(820, 940, 600, "ЦЕНТР. БАК", 12.5);
        p.sub(1000, 1120, 600, "ПРАВ. БАК", 12.5);
        double[] px = {670, 730, 850, 910, 1030, 1090};
        String[] pn = {"1", "2", "1", "2", "1", "2"};
        for (int i = 0; i < 6; i++) {
            p.pb(px[i], 682, "НАСОС " + pn[i], "ОТКАЗ", O, "ВЫКЛ", WHT, 48, false, 11, 0);
        }
        // ELEC
        p.group(1220, 430, 1760, 740, "ЭЛЕКТР");
        p.window(1300, 490, null, 84, 30, GRN, 19, 'm');
        p.window(1440, 490, null, 84, 30, GRN, 19, 'm');
        p.pb(1300, 575, "АКБ 1", "ОТКАЗ", O, "ВЫКЛ", WHT);
        p.pb(1440, 575, "АКБ 2", "ОТКАЗ", O, "ВЫКЛ", WHT);
        p.label(1370, 490, "АКБ", 12);
        p.pb(1650, 545, "ВНЕШН. ПИТ", "ДОСТУП", GRN, "ВКЛ", BLU);
        p.pb(1265, 692, "ГЕН 1", "ОТКАЗ", O, "ВЫКЛ", WHT, 54, false, 12, 0);
        p.pb(1375, 692, "СВЯЗЬ ШИН", null, null, "ВЫКЛ", WHT, 54, false, 12, 0);
        p.pb(1485, 692, "ГЕН ВСУ", "ОТКАЗ", O, "ВЫКЛ", WHT, 54, false, 12, 0);
        p.pb(1595, 692, "ГЕН 2", "ОТКАЗ", O, "ВЫКЛ", WHT, 54, false, 12, 0);
        p.pb(1705, 692, "БУФЕТ", "ОТКАЗ", O, "ВЫКЛ", WHT, 54, false, 12, 0);
        // BLEED
        p.group(40, 800, 620, 1060, "ОТБОР ВОЗДУХА");
        p.line(new double[] {110, 1000, 550, 1000}, p.lab, 1.4, LIT);
        p.line(new double[] {330, 905, 330, 1000}, p.lab, 1.4, LIT);
        p.pb(110, 880, "СКВ 1", "ОТКАЗ", O, "ВЫКЛ", WHT);
        p.rotary(330, 880, new String[] {"ЗАКР", "АВТО", "ОТКР"}, null, 22, 110, 12.5);
        p.label(330, 930, "КОЛЬЦ. ОТБОРА", 12);
        p.pb(550, 880, "СКВ 2", "ОТКАЗ", O, "ВЫКЛ", WHT);
        p.pb(110, 1015, "ДВ 1", "ОТКАЗ", O, "ВЫКЛ", WHT);
        p.pb(330, 1015, "ВСУ", "ОТКАЗ", O, "ВКЛ", BLU);
        p.pb(550, 1015, "ДВ 2", "ОТКАЗ", O, "ВЫКЛ", WHT);
        p.window(220, 1050, null, 64, 18, GRN, 12, 'm');
        // ANTI ICE
        p.group(680, 800, 940, 1060, "ПОС");
        p.pb(745, 880, "КРЫЛО", "ОТКАЗ", O, "ВКЛ", BLU);
        p.pb(875, 880, "ПВД /\nСТЁКЛА", null, null, "ВКЛ", BLU);
        p.pb(745, 1005, "ДВ 1", "ОТКАЗ", O, "ВКЛ", BLU);
        p.pb(875, 1005, "ДВ 2", "ОТКАЗ", O, "ВКЛ", BLU);
        // APU
        p.group(1000, 800, 1220, 1060, "ВСУ");
        p.pb(1060, 920, "ГЛАВН.\nВЫКЛ.", "ОТКАЗ", O, "ВКЛ", BLU);
        p.pb(1165, 920, "ЗАПУСК", "ГОТОВ", GRN, "ВКЛ", BLU);
        p.window(1110, 1015, null, 120, 22, GRN, 13, 'm');
        // INT LIGHTS / SIGNS
        p.group(1280, 800, 1760, 1060, "СВЕТ ВНУТР. / ТАБЛО");
        p.toggle(1325, 895, new String[] {"ЯРКО", "ТУСКЛО", "ВЫКЛ"}, "ПЛАФОН", false, 12.5, true, 1);
        p.knob(1450, 895, 22, 40, true, rgb(34, 35, 38), false);
        p.label(1450, 935, "ЯРК. ПАНЕЛИ", 11.5);
        p.label(1418, 868, "MIN", 9);
        p.label(1484, 868, "MAX", 9);
        p.toggle(1555, 895, new String[] {"ВКЛ", "АВТО", "ВЫКЛ"}, "РЕМНИ", false, 12.5, true, 1);
        p.toggle(1680, 895, new String[] {"ВКЛ", "АВТО", "ВЫКЛ"}, "НЕ КУРИТЬ", false, 12.5, true, 1);
        p.toggle(1325, 1010, new String[] {"ВКЛ", "ВЗВЕД.", "ВЫКЛ"}, null, true, 12.5, true, 1);
        p.label(1340, 1050, "АВАР. ВЫХОД", 11.5);
        p.lamp(1395, 975, "ВЫКЛ", AMB, 46, 18, 10);
        p.rotary(1520, 1005, new String[] {"НОЧЬ", "1", "2", "3"}, "САЛОН", 18, 120, 11);
        p.toggle(1660, 1010, new String[] {"ТЕСТ", "ЯРК", "ТУСК"}, null, false, 12.5, true, 1);
        p.label(1680, 1050, "ТАБЛО", 11.5);
        // EXT LIGHTS (+ the quick start under its guard)
        p.group(40, 1120, 1630, 1365, "СВЕТ ВНЕШН.");
        String[] ln = {"СТРОБ", "МАЯК", "КРЫЛО", "АНО И ЛОГО", "РУЛЁЖ. БОК.", "ПОСАД. Л", "ПОСАД. П", "НОС"};
        String[][] lp = {{"ВКЛ", "АВТО", "ВЫКЛ"}, {"ВКЛ", "ВЫКЛ"}, {"ВКЛ", "ВЫКЛ"}, {"1", "ВЫКЛ", "2"}, {"ВКЛ", "ВЫКЛ"}, {"ВКЛ", "ВЫКЛ", "УБРАНА"},
                {"ВКЛ", "ВЫКЛ", "УБРАНА"}, {"ВЗЛЁТ", "РУЛЁЖ", "ВЫКЛ"}};
        for (int i = 0; i < 8; i++) {
            double x = 120 + i * 212;
            p.toggle(x - 20, 1265, lp[i], ln[i], false, 16, true, 1.6);
        }
        p.group(1650, 1120, 1760, 1365, "ЗАПУСК", 13);
        p.toggle(1700, 1265, new String[] {"ПУСК", "ВЫКЛ"}, "БЫСТРЫЙ", true, 12, false, 1.6);
        p.text(1780, 1385, "SA-320", 11, p.lab, "rb", 'n', LIT);
    }

    // ---------------------------------------------------------------- GLARE SHIELD (2000 x 180): EFIS L | WARN | FCU | WARN | EFIS R

    static void efis(P p, double x0) {
        p.module(x0, 6, x0 + 400, 174);
        p.window(x0 + 70, 46, "1013", 74, 28, AMB, 20, 'm');
        p.label(x0 + 70, 18, "ДАВЛ.  гПа", 11);
        p.knob(x0 + 70, 118, 26, 20, true, rgb(34, 35, 38), true);
        p.label(x0 + 70, 160, "СТД  (тянуть)", 10);
        String[] e = {"ОГР", "ППМ", "VOR", "NDB", "АЭР"};
        for (int i = 0; i < 5; i++) {
            p.pb(x0 + 150 + i * 46, 46, null, null, null, e[i], GRN, 34, false, 13.5, 9);
        }
        p.pb(x0 + 150, 120, "ДК", null, null, "ВКЛ", GRN, 34, false, 11, 9);
        p.pb(x0 + 196, 120, "КГС", null, null, "ВКЛ", GRN, 34, false, 11, 9);
        p.rotary(x0 + 270, 124, new String[] {"ILS", "VOR", "НАВ", "ДУГА", "ПЛАН"}, null, 17, 150, 10);
        p.rotary(x0 + 350, 124, new String[] {"10", "20", "40", "80", "160", "320"}, null, 17, 170, 10);
    }

    static void glareshield(P p) {
        efis(p, 120);
        efis(p, 1480);
        for (double x0 : new double[] {525, 1405}) {
            p.module(x0, 6, x0 + 70, 174, false);
            p.pb(x0 + 35, 42, null, "ГЛАВН.", RED, "ОПАСН.", RED, 46, false, 13.5, 9.5);
            p.pb(x0 + 35, 100, null, "ГЛАВН.", AMB, "ВНИМ.", AMB, 46, false, 13.5, 9.5);
            p.pb(x0 + 35, 150, null, null, null, "ПРИОРИТ.", GRN, 34, false, 13.5, 7.5);
        }
        fcu(p, 600);
    }

    static void fcu(P p, double x0) {
        p.rect(x0, 4, x0 + 800, 176, rgb(70, 84, 97), rgb(30, 34, 40), 2, 4, null);
        p.module(x0, 4, x0 + 800, 176, true);
        sect(p, x0 + 85, "СКОР", "М", "---");
        p.pb(x0 + 30, 160, null, null, null, "СКОР/М", WHT, 26, false, 13.5, 6.5);
        sect(p, x0 + 245, "КУРС", "ПУ", "---");
        p.pb(x0 + 310, 155, "КРМ", null, null, "", GRN, 34, false, 13.5, 0);
        p.rect(x0 + 296, 159, x0 + 324, 164, rgb(30, 50, 36));
        double[][] ap = {{x0 + 375, 60}, {x0 + 425, 60}, {x0 + 400, 128}};
        String[] an = {"АП 1", "АП 2", "А/ТЯГА"};
        for (int i = 0; i < 3; i++) {
            double cx = ap[i][0], cy = ap[i][1];
            p.shadow(cx - 22, cy - 18, cx + 22, cy + 18, 2, 3, 2, 0.55, false);
            p.rect(cx - 22, cy - 18, cx + 22, cy + 18, rgb(28, 29, 31), 3);
            p.rect(cx - 13, cy - 11, cx + 13, cy - 6, rgb(30, 50, 36));
            p.label(cx, cy + 6, an[i], 11);
        }
        sect(p, x0 + 545, "ВЫС", "", "35000");
        p.label(x0 + 610, 118, "100", 10);
        p.label(x0 + 610, 140, "1000", 10);
        p.pb(x0 + 485, 155, "УСКОР", null, null, "", GRN, 34, false, 13.5, 0);
        p.rect(x0 + 471, 159, x0 + 499, 164, rgb(30, 50, 36));
        p.label(x0 + 690, 20, "В/С", 12.5);
        p.label(x0 + 740, 20, "УН", 12.5);
        p.window(x0 + 715, 52, "-----", 104, 32, AMB, 24, 'm');
        p.knob(x0 + 715, 120, 30, 0, true, rgb(34, 35, 38), true);
        p.pb(x0 + 650, 155, "ЗАХОД", null, null, "", GRN, 34, false, 13.5, 0);
        p.rect(x0 + 636, 159, x0 + 664, 164, rgb(30, 50, 36));
        p.pb(x0 + 170, 160, null, null, null, "КУРС-В/С", WHT, 26, false, 13.5, 5.5);
    }

    private static void sect(P p, double cx, String l1, String l2, String val) {
        p.label(cx - 30, 20, l1, 12.5);
        p.label(cx + 30, 20, l2, 12.5);
        p.ell(cx - 31, 30, 3, p.lab);
        p.window(cx, 52, val, 104, 32, AMB, 24, 'm');
        p.ell(cx + 60, 52, 4.5, WHT, null, 0, 0, LIT);
        p.knob(cx, 120, 30, 0, true, rgb(34, 35, 38), true);
    }

    // ---------------------------------------------------------------- MAIN PANEL (2000 x 620)

    /** The DUs on the main panel (panel units): PFD 1, ND 1, E/WD, SD, ND 2, PFD 2, ISIS - LinerSystems.DU_* order. */
    public static final float[][] SCREENS = {{170, 20, 450, 300}, {465, 20, 745, 300}, {860, 20, 1140, 300}, {860, 320, 1140, 600},
            {1255, 20, 1535, 300}, {1550, 20, 1830, 300}, {754, 30, 852, 128}};

    static void mainPanel(P p) {
        Color recess = rgb(40, 44, 50);
        p.rect(10, 330, 742, 614, recess);
        p.line(new double[] {10, 330, 742, 330}, rgb(120, 132, 144), 2);
        p.rect(1258, 330, 1990, 614, recess);
        p.line(new double[] {1258, 330, 1990, 330}, rgb(120, 132, 144), 2);
        p.module(10, 6, 852, 326);
        p.module(742, 6, 1258, 614);
        p.module(1148, 6, 1990, 326);
        for (int i = 0; i < SCREENS.length; i++) {
            float[] s = SCREENS[i];
            double b = i == 6 ? 6 : 10;
            p.shadow(s[0] - b, s[1] - b, s[2] + b, s[3] + b, 2, 3, 3, 0.5, false);
            p.rect(s[0] - b, s[1] - b, s[2] + b, s[3] + b, rgb(30, 32, 35), 4);
            p.rect(s[0], s[1], s[2], s[3], rgb(4, 5, 6));
            p.ell(s[0] - b + 5, s[3] + b - 5, 2.2, rgb(70, 72, 76));
        }
        p.label(803, 142, "ISIS", 10);
        p.ell(803, 205, 40, rgb(14, 15, 16), rgb(80, 82, 86), 1.5, 0, null);
        p.window(803, 205, null, 52, 16, WHT, 11, 'm');
        p.label(803, 256, "ЧАСЫ", 10);
        p.ell(803, 345, 40, rgb(14, 15, 16), rgb(80, 82, 86), 1.5, 0, null);
        for (int a = -60; a <= 60; a += 30) {
            double ar = Math.toRadians(a - 90);
            p.line(new double[] {803 + Math.cos(ar) * 32, 345 + Math.sin(ar) * 32, 803 + Math.cos(ar) * 38, 345 + Math.sin(ar) * 38}, WHT, 1.2);
        }
        p.label(803, 396, "ТОРМ.", 10);
        p.toggle(790, 460, new String[] {"ВКЛ", "ВЫКЛ"}, null, false, 9, true, 1);
        p.label(803, 505, "ПРОТИВОЮЗ", 9.5);
        String[] ab = {"МАЛ", "СРЕД", "МАКС"};
        for (int i = 0; i < 3; i++) {
            p.pb(1175, 360 + i * 70, null, "ТОРМОЖ.", GRN, "ВКЛ", BLU, 42, false, 13.5, 7.5);
            p.label(1175, 360 + i * 70 + 30, ab[i], 9.5);
        }
        p.label(1175, 330, "АВТОТОРМОЗ", 8.5);
        p.rect(1212, 330, 1246, 600, rgb(24, 25, 27), 6);
        p.rect(1222, 338, 1236, 592, rgb(8, 8, 9), 4);
        p.label(1229, 316, "ШАССИ", 9.5);
        p.label(1262, 350, "УБР", 9, "lm");
        p.label(1262, 580, "ВЫП", 9, "lm");
        p.label(1000, 610, "ECAM", 10);
    }

    // ---------------------------------------------------------------- PEDESTAL (760 x 1660)

    /** The thrust levers' detents (y, pedestal units) and the flap lever's. */
    public static final float TL_TOGA = 650, TL_FLX = 712, TL_CL = 790, TL_IDLE = 905, TL_REV = 950, TL_MREV = 1010;
    public static final float[] FLAP_Y = {668, 730, 792, 854, 916};

    static void mcdu(P p, double x0, double y0, int which) {
        double w = 360, h = 470;
        p.shadow(x0, y0, x0 + w, y0 + h, 3, 5, 5, 0.55, false);
        p.rect(x0, y0, x0 + w, y0 + h, rgb(54, 60, 68), 6);
        p.module(x0, y0, x0 + w, y0 + h, true);
        double sx0 = x0 + 62, sy0 = y0 + 18, sx1 = x0 + 298, sy1 = y0 + 200;
        p.rect(sx0 - 6, sy0 - 6, sx1 + 6, sy1 + 6, rgb(22, 23, 25), 3);
        p.rect(sx0, sy0, sx1, sy1, rgb(3, 4, 5));
        for (int i = 0; i < 6; i++) {
            double y = sy0 + 26 + i * 27;
            p.key(x0 + 14, y - 8, x0 + 46, y + 8, "-", 10, rgb(62, 65, 70));
            p.key(x0 + 314, y - 8, x0 + 346, y + 8, "-", 10, rgb(62, 65, 70));
        }
        String[][] rows = {{"НАПР", "ПРОГ", "ПАРАМ", "ИНИЦ", "ДАННЫЕ", ""}, {"ПЛАН", "РАДИО", "ТОПЛ", "РЕЗ.ПЛ", "СВЯЗЬ", "МЕНЮ"}};
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 6; c++) {
                double xx = x0 + 18 + c * 55, yy = y0 + 214 + r * 30;
                p.key(xx, yy, xx + 50, yy + 24, rows[r][c], 8.6, rgb(70, 74, 80));
            }
        }
        double yy = y0 + 274;
        p.key(x0 + 18, yy, x0 + 68, yy + 24, "АЭРОП", 8.6, rgb(70, 74, 80));
        String[] ar = {"<", ">", "^", "v"};
        for (int c = 0; c < 4; c++) {
            p.key(x0 + 80 + c * 32, yy, x0 + 108 + c * 32, yy + 24, ar[c], 11, rgb(70, 74, 80));
        }
        String digits = "123456789.0-";
        for (int i = 0; i < digits.length(); i++) {
            int r = i / 3, c = i % 3;
            double xx = x0 + 18 + c * 40, y = y0 + 312 + r * 38;
            String t = digits.substring(i, i + 1);
            p.key(xx, y, xx + 34, y + 32, "-".equals(t) ? "+/-" : t, 12, rgb(58, 61, 66));
        }
        String[] letters = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X",
                "Y", "Z", "_", "/", "СБР", "ПРОЛ"};
        for (int i = 0; i < letters.length; i++) {
            int r = i / 5, c = i % 5;
            double xx = x0 + 150 + c * 40, y = y0 + 304 + r * 27;
            if (y + 23 > y0 + h - 6) {
                break;
            }
            p.key(xx, y, xx + 35, y + 23, letters[i], letters[i].length() == 1 ? 10.5 : 7.5, rgb(82, 86, 92));
        }
        p.lamp(x0 + 30, y0 + 200, "ОТКАЗ", AMB, 38, 10, 7);
        p.label(x0 + w - 30, y0 + 200, "MCDU " + which, 9);
    }

    static void pedestal(P p) {
        mcdu(p, 15, 12, 1);
        mcdu(p, 385, 12, 2);
        // ECAM control panel
        p.module(15, 492, 745, 600);
        String[] pages = {"ДВИГ", "ОТБОР", "КОНД", "ЭЛЕКТР", "ГИДР", "ТОПЛ", "ВСУ", "УПР", "ДВЕРИ", "КОЛЁСА", "ВСЕ", "СТАТУС"};
        for (int i = 0; i < pages.length; i++) {
            int r = i / 6, c = i % 6;
            double x = 150 + c * 80, y = 522 + r * 48;
            p.key(x - 34, y - 15, x + 34, y + 15, pages[i], 10, rgb(46, 48, 52));
            p.rect(x - 20, y + 9, x + 20, y + 12, rgb(30, 50, 36));
        }
        p.key(40, 512, 100, 540, "СБРОС", 9.5, rgb(46, 48, 52));
        p.key(40, 560, 100, 588, "ОТМЕНА", 9.5, rgb(46, 48, 52));
        p.key(655, 512, 730, 540, "ВЫЗОВ ПРОЦ.", 7.5, rgb(46, 48, 52));
        p.key(655, 560, 730, 588, "ТО (TO CONF)", 7, rgb(46, 48, 52));
        // throttle quadrant
        p.module(15, 608, 745, 1050);
        p.text(380, 628, "РУД", 15, p.lab, "mm", 'n', LIT);
        double[] lx = {330, 430};
        for (double x : lx) {
            p.rect(x - 9, 640, x + 9, 1030, rgb(10, 10, 12), 8);
        }
        float[] ty = {TL_TOGA, TL_FLX, TL_CL, TL_IDLE, TL_REV, TL_MREV};
        String[] tn = {"ВЗЛЁТ/УХОД", "ГИБК/НОМ", "НАБОР", "МГ", "РЕВ. МГ", "РЕВ. МАКС"};
        for (int i = 0; i < 6; i++) {
            double y = ty[i];
            p.line(new double[] {292, y, 304, y}, p.lab, 1.6, LIT);
            p.line(new double[] {456, y, 468, y}, p.lab, 1.6, LIT);
            p.label(288, y, tn[i], tn[i].length() < 9 ? 10.5 : 9.5, "rm");
            p.label(472, y, tn[i], tn[i].length() < 9 ? 10.5 : 9.5, "lm");
            if (i == 1 || i == 2 || i == 3) {
                for (double x : lx) {
                    p.rect(x - 14, y - 2, x + 14, y + 2, rgb(170, 172, 176));
                }
            }
        }
        p.rect(300, TL_IDLE + 18, 460, TL_IDLE + 22, rgb(200, 160, 40));
        // speed brake
        p.text(80, 640, "ИНТЕРЦ.", 12, p.lab, "mm", 'n', LIT);
        p.rect(74, 670, 86, 900, rgb(10, 10, 12), 6);
        p.label(62, 678, "УБР", 10.5, "rm");
        p.label(62, 780, "1/2", 10.5, "rm");
        p.label(62, 890, "ПОЛН", 10.5, "rm");
        // trim wheels (edge-on)
        for (double x : new double[] {150, 575}) {
            p.rect(x - 10, 650, x + 10, 1020, rgb(30, 31, 34), 8);
            for (int i = 0; i < 34; i++) {
                double y = 660 + i * 10.6;
                p.line(new double[] {x - 9, y, x + 9, y}, rgb(70, 72, 76), 1.4);
            }
            p.rect(x - 10, 828, x + 10, 842, rgb(235, 235, 235));
        }
        p.label(150, 1036, "ТРИММ СТАБ.", 9.5);
        p.label(575, 1036, "ТРИММ СТАБ.", 9.5);
        String[] tt = {"ВВЕРХ 4", "2", "0", "2", "ВНИЗ 4"};
        for (int i = 0; i < 5; i++) {
            p.label(166, 690 + i * 75, tt[i], 8.5, "lm");
        }
        p.poly(new double[] {164, 835, 174, 830, 174, 840}, WHT, null, 0);
        // flaps
        p.text(670, 628, "ЗАКРЫЛКИ", 12, p.lab, "mm", 'n', LIT);
        String[] fl = {"0", "1", "2", "3", "ПОЛН"};
        double[] gate = new double[fl.length * 4];
        for (int i = 0; i < fl.length; i++) {
            double y = FLAP_Y[i], gx = 655 + (i % 2) * 6;
            gate[i * 4] = gx;
            gate[i * 4 + 1] = y - 14;
            gate[i * 4 + 2] = gx;
            gate[i * 4 + 3] = y + 14;
            p.label(700, y, fl[i], 12, "lm");
        }
        p.line(gate, rgb(10, 10, 12), 10);
        p.rect(598, 946, 742, 1040, rgb(232, 228, 210), 3);
        String[] pl = {"V макс, км/ч", "1     130", "2     120", "3     110", "ПОЛН  100"};
        for (int j = 0; j < pl.length; j++) {
            p.text(606, 958 + j * 18, pl[j], j > 0 ? 10 : 9.5, rgb(30, 30, 30), "lm", 'm', null);
        }
        // engine masters + mode
        p.module(15, 1050, 745, 1210);
        p.text(380, 1068, "ДВИГАТЕЛИ", 13, p.lab, "mm", 'n', LIT);
        for (int e = 0; e < 2; e++) {
            double x = e == 0 ? 210 : 550;
            p.lamp(x - 38, 1098, "ПОЖАР", RED, 60, 20, 10);
            p.lamp(x + 38, 1098, "ОТКАЗ", AMB, 60, 20, 10);
            p.rect(x - 14, 1120, x + 14, 1196, rgb(10, 10, 12), 6);
            p.label(x - 48, 1134, "ВКЛ", 10.5, "rm");
            p.label(x - 48, 1186, "ВЫКЛ", 10.5, "rm");
            p.label(x + 48, 1160, "ДВ " + (e + 1), 13, "lm");
        }
        p.rotary(380, 1160, new String[] {"КРАНК", "НОРМ", "ЗАПУСК"}, "РЕЖИМ ДВ", 20, 110, 11);
        // parking brake, the ADIRS switching, the rudder trim
        p.module(15, 1210, 745, 1300);
        p.label(140, 1222, "СТОЯН. ТОРМОЗ", 11);
        p.label(185, 1240, "ВКЛ", 10, "lm");
        p.label(185, 1270, "ВЫКЛ", 10, "lm");
        p.ell(140, 1255, 30, rgb(30, 31, 34), rgb(70, 72, 76), 1.5, 0, null);
        String[] sw = {"ИНС КВС", "ВОЗД. ДАННЫЕ"};
        for (int i = 0; i < 2; i++) {
            p.rotary(380 + i * 120, 1262, new String[] {"КВС НА 3", "НОРМ", "2П НА 3"}, null, 14, 100, 8.5);
            p.label(380 + i * 120, 1290, sw[i], 9);
            drawBar(p, 380 + i * 120, 1262, 14, 0);
        }
        p.window(660, 1255, "Н 0.0", 80, 22, AMB, 14, 'm');
        p.label(660, 1228, "ТРИММ РН", 10);
        // radio + transponder (decorative in wave 1)
        p.module(15, 1300, 745, 1470);
        p.label(200, 1318, "РАДИО  (RMP 1)", 12);
        p.window(110, 1352, "118.700", 140, 30, AMB, 20, 'm');
        p.label(110, 1378, "АКТИВН.", 9.5);
        p.window(290, 1352, "121.500", 140, 30, AMB, 20, 'm');
        p.label(290, 1378, "РЕЗЕРВ", 9.5);
        p.key(185, 1340, 215, 1364, "<>", 10, rgb(62, 65, 70));
        p.knob(330, 1425, 22, 0, true, rgb(34, 35, 38), true);
        String[] vhf = {"УКВ 1", "УКВ 2", "ВЫКЛ"};
        for (int i = 0; i < 3; i++) {
            p.key(40 + i * 70, 1408, 100 + i * 70, 1436, vhf[i], 9.5, rgb(46, 48, 52));
        }
        p.rect(50, 1430, 90, 1433, GRN, null, 0, 0, null);
        p.label(570, 1318, "ОТВЕТЧИК", 12);
        p.window(520, 1352, "7000", 100, 30, AMB, 21, 'm');
        p.rotary(660, 1356, new String[] {"ОЖИД", "АВТО", "ВКЛ"}, null, 15, 100, 9);
        drawBar(p, 660, 1356, 15, 0);
        for (int i = 0; i < 8; i++) {
            p.key(450 + i * 34, 1394, 478 + i * 34, 1418, String.valueOf(i), 11, rgb(58, 61, 66));
        }
        p.key(450, 1428, 516, 1452, "СБР", 9.5, rgb(58, 61, 66));
        p.key(526, 1428, 596, 1452, "ОПОЗН.", 9, rgb(58, 61, 66));
        p.label(668, 1440, "ВЫСОТА", 9, "rm");
        // the gravity gear extension crank (wave 2)
        p.module(15, 1470, 745, 1650);
        p.rect(250, 1500, 510, 1630, rgb(14, 15, 17), 10);
        p.shadow(300, 1545, 466, 1590, 5, 8, 5, 0.6, false);
        p.poly(new double[] {300, 1560, 460, 1545, 466, 1575, 306, 1590}, rgb(205, 40, 34), null, 0);
        p.ell(300, 1575, 22, rgb(170, 30, 28));
        p.ell(463, 1560, 14, rgb(30, 30, 32));
        p.ell(300, 1575, 9, rgb(230, 232, 236));
        p.rect(30, 1500, 236, 1630, rgb(232, 228, 210), 3);
        String[] gp = {"АВАР. ВЫПУСК", "ШАССИ:", "вытянуть ручку,", "3 оборота", "по часовой"};
        for (int j = 0; j < gp.length; j++) {
            p.text(42, 1518 + j * 24, gp[j], j < 2 ? 13 : 12, j < 2 ? rgb(150, 20, 20) : rgb(30, 30, 30), "lm", 'n', null);
        }
        p.lamp(630, 1530, "ШАССИ", RED, 90, 22, 11);
        p.label(630, 1600, "ВЫПУСК ГРАВИТ.", 10);
    }

    /** A static selector bar (the decorative selectors that are no control yet): the bar at position `i` of 3 over 100 deg. */
    static void drawBar(P p, double cx, double cy, double r, int i) {
        double a = Math.toRadians(-50 + 50 * (i + 1) - 90), ca = Math.cos(a), sa = Math.sin(a), wv = r * 0.32;
        p.poly(new double[] {cx + ca * r * 1.05 - sa * wv, cy + sa * r * 1.05 + ca * wv, cx + ca * r * 1.05 + sa * wv, cy + sa * r * 1.05 - ca * wv,
                cx - ca * r * 0.95 + sa * wv, cy - sa * r * 0.95 - ca * wv, cx - ca * r * 0.95 - sa * wv, cy - sa * r * 0.95 + ca * wv},
                rgb(18, 18, 20), rgb(78, 80, 84), 1);
        p.line(new double[] {cx, cy, cx + ca * r * 0.95, cy + sa * r * 0.95}, WHT, Math.max(1.2, r * 0.1));
    }

    // ---------------------------------------------------------------- LATERAL CONSOLES (500 x 1400)

    static void sideConsole(P p, boolean right) {
        String who = right ? "2П" : "КВС";
        p.module(10, 10, 490, 300);
        p.module(10, 300, 490, 700);
        p.module(10, 700, 490, 1000);
        p.module(10, 1000, 490, 1390);
        p.group(30, 34, 470, 285, "ПУЛЬТ " + who, 14);
        double[] kx = {95, 250, 405};
        String[] kn = {"ЯРК. PFD", "ЯРК. ND", "ГРОМК."};
        for (int i = 0; i < 3; i++) {
            double x = right ? 500 - kx[i] : kx[i];
            p.knob(x, 100, 24, 0, true, rgb(34, 35, 38), false);
            p.label(x, 142, kn[i], 11.5);
        }
        double xp = right ? 390 : 110, xt = right ? 200 : 300;
        p.pb(xp, 222, "PFD/ND", null, null, "СМЕНА", WHT, 42, false, 11, 8.5);
        p.toggle(xt, 225, new String[] {"ЯРКО", "ТУСКЛ", "ВЫКЛ"}, null, false, 10, !right, 1);
        p.label(xt, 182, "ПОДСВ. ПУЛЬТА / ПОЛА", 10.5);
        double xtil = right ? 360 : 140, xs = right ? 130 : 370;
        p.label(xtil, 330, "РУЛЁЖНОЕ КОЛЕСО", 11);
        p.ell(xtil, 470, 110, rgb(30, 31, 34));
        p.ell(xtil, 470, 92, rgb(70, 82, 94));
        p.label(xtil, 600, "+-75 град", 10);
        p.label(xs, 330, "РУЧКА УПР.", 11);
        p.rect(xs - 70, 400, xs + 70, 600, rgb(40, 42, 46), 50);
        p.label(xs, 640, "ПРИОРИТЕТ - кнопка", 9.5);
        double xo = 250;
        p.rect(xo - 170, 735, xo + 170, 965, rgb(196, 196, 190), 10);
        p.rect(xo - 150, 755, xo + 150, 945, rgb(170, 170, 164), 8);
        p.text(xo, 790, "O2 МАСКА", 22, rgb(40, 40, 40));
        p.text(xo, 830, "НАЖАТЬ И ТЯНУТЬ", 14, rgb(170, 24, 20));
        p.rect(xo - 120, 870, xo - 20, 920, rgb(200, 40, 34), 6);
        p.text(xo - 70, 895, "ПРОВ.", 12, rgb(255, 255, 255));
        p.window(xo + 70, 895, null, 70, 30, GRN, 0, 'm');
        p.ell(xo + 70, 895, 8, rgb(240, 220, 40));
        p.label(xo, 985, "ПОТОК (жёлтый крест)", 9.5);
        p.rect(40, 1030, 460, 1290, rgb(150, 150, 146), 8);
        p.rect(60, 1050, 440, 1270, null, rgb(110, 110, 106), 2, 6, null);
        p.text(250, 1150, "СТОЛИК", 20, rgb(60, 60, 60));
        p.text(250, 1185, "выдвинуть к себе", 12, rgb(80, 80, 80), "mm", 'r', null);
        double xc = right ? 390 : 110, xm = right ? 170 : 330;
        p.ell(xc, 1340, 34, rgb(20, 20, 22), rgb(90, 92, 96), 2, 0, null);
        p.label(xc, 1384, "ПОДСТАКАННИК", 9);
        p.rect(xm - 90, 1310, xm + 90, 1370, rgb(30, 31, 34), 6);
        p.label(xm, 1384, "КАРМАН КАРТ", 9);
    }

    // ---------------------------------------------------------------- CABIN (icabtex.py)

    static void fabric(P p, double ax, double ay, double bx, double by, Color base) {
        p.rect(ax, ay, bx, by, base);
        double s = 26;
        Color dk = mul(base, 0.78), lt = new Color(clamp((int) (base.getRed() * 1.25 + 8)), clamp((int) (base.getGreen() * 1.25 + 8)),
                clamp((int) (base.getBlue() * 1.25 + 8)));
        int j = 0;
        for (double y = ay + 8; y < by; y += s * 0.87, j++) {
            for (double x = ax + (j % 2 == 1 ? s / 2 : 0) + 6; x < bx; x += s) {
                double[] hx = new double[14];
                for (int k = 0; k <= 6; k++) {
                    double a = Math.toRadians(60 * (k % 6) + 30);
                    hx[k * 2] = x + 7 * Math.cos(a);
                    hx[k * 2 + 1] = y + 7 * Math.sin(a);
                }
                p.line(hx, lt, 0.9);
                p.ell(x, y, 1.6, dk);
            }
        }
        for (double yy = ay; yy < by; yy += 3) {
            p.line(new double[] {ax, yy, bx, yy}, dk, 0.35);
        }
    }

    /** The seat back's front (450 x 900): EV blue moquette, the white headrest cover with the SA emblem, red piping. */
    static void seat(P p) {
        fabric(p, 10, 10, 440, 590, rgb(30, 66, 150));
        p.rect(14, 14, 436, 586, null, rgb(196, 40, 40), 5, 40, null);
        p.rect(50, 30, 400, 230, rgb(240, 240, 234), 30);
        p.rect(50, 30, 400, 230, null, rgb(205, 205, 198), 3, 30, null);
        hexSA(p, 225, 120, 30, rgb(30, 66, 150), rgb(240, 240, 240), 26);
        p.text(225, 190, "SILICON AGE", 17, rgb(130, 134, 140), "mm", 'a', null);
        for (double y : new double[] {330, 450, 560}) {
            p.line(new double[] {40, y, 410, y}, rgb(22, 48, 112), 3);
        }
        // the seat's back shell (the row behind sees it): tray table, pocket
        p.rect(30, 600, 420, 890, rgb(70, 74, 82), 14);
        p.rect(50, 620, 400, 760, rgb(110, 114, 122), 12);
        p.rect(190, 630, 260, 650, rgb(60, 62, 68), 6);
        p.text(225, 700, "СТОЛИК", 18, rgb(150, 154, 162));
        p.rect(50, 780, 400, 880, rgb(52, 54, 60), 12);
        p.text(225, 830, "ПАМЯТКА / ЖИЛЕТ ПОД КРЕСЛОМ", 15, rgb(170, 172, 178));
    }

    static void hexSA(P p, double cx, double cy, double r, Color fill, Color txt, double size) {
        double[] hx = new double[12];
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(60 * k + 30);
            hx[k * 2] = cx + r * Math.cos(a);
            hx[k * 2 + 1] = cy + r * Math.sin(a);
        }
        p.poly(hx, fill, null, 0);
        p.text(cx, cy + 1, "SA", size, txt, "mm", 'a', null);
    }

    /** Carpet tile (600 x 600): blue-grey loop pile with a darker chevron. */
    static void carpet(P p) {
        Color c = rgb(60, 68, 92);
        for (int i = -600; i < 600; i += 48) {
            p.line(new double[] {i, 0, i + 300, 300, i, 600}, c, 6);
        }
    }

    /** One window bay of the sidewall (1000 x 700): the plastic reveal, the window with the sky, the shade half down. */
    static void wall(P p) {
        double cx = 500, cy = 330;
        p.rect(cx - 150, cy - 200, cx + 150, cy + 200, rgb(214, 212, 206), 90);
        p.rect(cx - 112, cy - 158, cx + 112, cy + 158, rgb(196, 194, 188), 70);
        p.rect(cx - 92, cy - 136, cx + 92, cy + 136, SKY, 58);
        p.rect(cx - 92, cy + 30, cx + 92, cy + 136, rgb(200, 214, 226), 58);
        p.line(new double[] {cx - 60, cy - 110, cx + 10, cy - 60}, rgb(220, 236, 250), 6);
        p.rect(cx - 96, cy - 140, cx + 96, cy - 60, rgb(236, 234, 228), 50);
        p.rect(cx - 30, cy - 76, cx + 30, cy - 64, rgb(170, 168, 162), 5);
        p.rect(0, 600, 1000, 700, rgb(150, 152, 156));                  // the dado strip
        for (int x = 40; x < 1000; x += 40) {
            p.rect(x, 640, x + 22, 670, rgb(120, 122, 126), 4);
        }
    }

    /** Bin doors (1600 x 360): two doors with latches; the PSU strip under them (reading lights, gaspers, the signs). */
    static void bins(P p) {
        for (int i = 0; i < 2; i++) {
            double x0 = i * 800;
            p.rect(x0 + 8, 8, x0 + 792, 232, rgb(226, 226, 222), null, 0, 14, null);
            p.rect(x0 + 8, 8, x0 + 792, 232, null, rgb(184, 184, 180), 4, 14, null);
            p.rect(x0 + 310, 180, x0 + 490, 222, rgb(140, 142, 146), 18);
            p.rect(x0 + 330, 190, x0 + 470, 212, rgb(60, 62, 66), 10);
        }
        p.rect(0, 240, 1600, 360, rgb(206, 206, 202));
        for (int i = 0; i < 4; i++) {
            double x = 140 + i * 400;
            p.ell(x, 290, 26, rgb(170, 170, 166));
            p.ell(x, 290, 18, rgb(236, 236, 230), null, 0, 0, rgb(120, 110, 80));
            p.ell(x + 80, 290, 20, rgb(120, 122, 126));
            p.ell(x + 80, 290, 8, rgb(40, 40, 44));
        }
        p.rect(1180, 262, 1380, 340, rgb(30, 32, 36), 10);
        p.text(1230, 301, "РЕМНИ", 18, rgb(255, 255, 255), "mm", 'n', rgb(160, 160, 150));
        p.text(1330, 301, "НЕ КУР.", 16, rgb(255, 255, 255), "mm", 'n', rgb(160, 160, 150));
    }

    /** The front bulkhead, cabin side (1100 x 1000): the cockpit door, the signs, EXIT, the attendant's seat panel. */
    static void bulkhead(P p) {
        p.rect(400, 125, 700, 1000, rgb(200, 192, 176), rgb(120, 116, 108), 3, 6, null);
        p.ell(550, 350, 9, rgb(30, 30, 34));
        p.rect(650, 525, 670, 600, rgb(150, 152, 156), 5);
        p.rect(470, 430, 630, 460, rgb(180, 172, 160), 3);
        p.text(550, 445, "КАБИНА ЭКИПАЖА", 17, rgb(110, 106, 98));
        p.rect(410, 30, 690, 110, rgb(24, 26, 30), 8);
        p.text(480, 62, "ПРИСТЕГНУТЬ", 20, rgb(255, 255, 255), "mm", 'n', rgb(200, 200, 190));
        p.text(480, 88, "РЕМНИ", 15, rgb(255, 255, 255), "mm", 'n', rgb(200, 200, 190));
        p.line(new double[] {550, 40, 550, 100}, rgb(70, 72, 76), 2);
        p.ell(620, 70, 27, null, rgb(255, 70, 60), 4, 0, rgb(160, 40, 30));
        p.line(new double[] {601, 51, 639, 89}, rgb(255, 70, 60), 4);
        p.rect(602, 66, 638, 74, rgb(255, 255, 255));
        for (double x : new double[] {225, 875}) {
            p.rect(x - 75, 167, x + 75, 217, rgb(10, 120, 50), null, 0, 5, rgb(20, 140, 60));
            p.text(x, 192, "ВЫХОД", 27, rgb(255, 255, 255), "mm", 'n', rgb(200, 255, 210));
        }
        p.rect(75, 450, 310, 750, rgb(196, 188, 172), 10);
        p.text(192, 600, "МЕСТО Б/П", 17, rgb(120, 116, 108));
        p.rect(790, 450, 980, 575, rgb(60, 62, 68), 7);
        p.text(885, 512, "ТЕЛЕФОН", 14, rgb(200, 200, 200));
        p.rect(800, 625, 940, 800, rgb(200, 40, 34), 7);
        p.text(870, 712, "ОГНЕТУШ.", 15, rgb(255, 255, 255));
    }

    /** The ceiling (1000 x 600): panels with seams, air outlets. */
    static void ceiling(P p) {
        for (double y : new double[] {0, 300}) {
            p.line(new double[] {0, y, 1000, y}, rgb(210, 210, 206), 4);
        }
        p.ell(500, 150, 20, rgb(200, 200, 196));
        p.ell(500, 450, 20, rgb(200, 200, 196));
        p.rect(0, 280, 1000, 320, rgb(236, 232, 218), null, 0, 0, rgb(120, 110, 90));     // the cove light strip
    }

    /** The galley front (700 x 1000): ovens, coffee makers, the worktop, two carts. */
    static void galley(P p) {
        Color st = rgb(170, 174, 180), dk = rgb(110, 114, 120);
        p.rect(20, 40, 680, 210, st, 4);
        for (int i = 0; i < 3; i++) {
            p.rect(30 + i * 215, 50, 225 + i * 215, 200, dk, 4);
            p.rect(40 + i * 215, 65, 215 + i * 215, 180, rgb(40, 44, 52), 3);
        }
        p.text(350, 25, "ДУХОВКИ", 15, rgb(90, 90, 90));
        p.rect(20, 230, 680, 380, st, 4);
        for (int i = 0; i < 2; i++) {
            p.rect(70 + i * 315, 250, 290 + i * 315, 360, dk, 5);
            p.ell(180 + i * 315, 305, 25, rgb(60, 62, 68));
        }
        p.rect(15, 420, 685, 440, rgb(140, 144, 150));
        for (int i = 0; i < 2; i++) {
            double x0 = 40 + i * 320;
            p.rect(x0, 480, x0 + 300, 980, rgb(120, 124, 132), 5);
            p.rect(x0 + 15, 495, x0 + 285, 530, dk, 3);
            p.rect(x0 + 105, 560, x0 + 195, 580, rgb(200, 40, 34), 3);
            for (int k = 0; k < 4; k++) {
                p.line(new double[] {x0 + 20, 625 + k * 80, x0 + 280, 625 + k * 80}, dk, 2);
            }
        }
        p.text(350, 460, "ТЕЛЕЖКИ", 13, rgb(90, 90, 90));
    }

    /** The lining (600 x 600): the cockpit's / cabin's plain wall panel with a faint seam. */
    static void lining(P p) {
        p.line(new double[] {0, 300, 600, 300}, rgb(196, 198, 196), 2);
        p.line(new double[] {300, 0, 300, 600}, rgb(196, 198, 196), 2);
    }

    // ---------------------------------------------------------------- the atlas

    /** A baked atlas: the day image (size x size) and the integral lighting's glow (half size, blurred). */
    public static final class Baked {
        public final int size;
        public final BufferedImage day, glow;

        Baked(int size, BufferedImage day, BufferedImage glow) {
            this.size = size;
            this.day = day;
            this.glow = glow;
        }
    }

    /** Paints the cockpit / cabin atlas at `size` px (LinerTexMath.size: 512 .. 4096). */
    public static Baked bake(int size) {
        BufferedImage day = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        BufferedImage glow = new BufferedImage(size / 2, size / 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D bg = day.createGraphics();
        bg.setColor(rgb(60, 64, 70));
        bg.fillRect(0, 0, size, size);
        bg.dispose();
        double k = LinerTexMath.scale(size);
        int[][] R = LinerTexMath.RECTS;
        P p;
        p = panel(day, glow, R[LinerTexMath.T_OVH], k, PANEL, 11, 0.04);
        overhead(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_GS], k, rgb(64, 76, 88), 5, 0.04);
        glareshield(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_MP], k, PANEL, 3, 0.04);
        mainPanel(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_PED], k, rgb(80, 95, 108), 7, 0.04);
        pedestal(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_SCL], k, rgb(80, 95, 108), 17, 0.04);
        sideConsole(p, false);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_SCR], k, rgb(80, 95, 108), 18, 0.04);
        sideConsole(p, true);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_SEAT], k, rgb(24, 26, 30), 21, 0);
        seat(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_CARPET], k, rgb(52, 58, 78), 45, 0.10);
        carpet(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_WALL], k, WALL, 31, 0.015);
        wall(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_BINS], k, rgb(226, 226, 222), 35, 0.015);
        bins(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_BULK], k, rgb(214, 206, 190), 47, 0.02);
        bulkhead(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_CEIL], k, rgb(236, 236, 232), 53, 0.01);
        ceiling(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_FABRIC], k, rgb(24, 26, 30), 43, 0);
        fabric(p, 0, 0, 480, 480, rgb(30, 66, 150));
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_GALLEY], k, rgb(200, 202, 204), 49, 0.03);
        galley(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_LINING], k, rgb(176, 180, 182), 57, 0.02);
        lining(p);
        p.dispose();
        p = panel(day, glow, R[LinerTexMath.T_PLAIN], k, rgb(255, 255, 255), 0, 0);
        p.dispose();
        blurGlow(glow);
        return new Baked(size, day, glow);
    }

    private static P panel(BufferedImage day, BufferedImage glow, int[] r, double k, Color bg, long seed, double matte) {
        return new P(day, glow, r[0], r[1], r[2], r[3], k, bg, seed, matte);
    }

    /** The glow: the sharp lit lettering + a soft halo (three box blurs ~ a Gaussian) - itex.Pnt.done's bloom. */
    static void blurGlow(BufferedImage g) {
        int w = g.getWidth(), h = g.getHeight();
        int[] src = g.getRGB(0, 0, w, h, null, 0, w);
        int[] a = src.clone();
        int rad = Math.max(1, w / 1024);
        int[] tmp = new int[a.length];
        for (int pass = 0; pass < 3; pass++) {
            box(a, tmp, w, h, rad, true);
            box(tmp, a, w, h, rad, false);
        }
        for (int i = 0; i < src.length; i++) {
            int s = src[i], b = a[i];
            int r = Math.min(255, (s >> 16 & 255) * 7 / 10 + (b >> 16 & 255) * 2), gg = Math.min(255, (s >> 8 & 255) * 7 / 10 + (b >> 8 & 255) * 2),
                    bb = Math.min(255, (s & 255) * 7 / 10 + (b & 255) * 2);
            src[i] = r << 16 | gg << 8 | bb;
        }
        g.setRGB(0, 0, w, h, src, 0, w);
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horiz) {
        int n = 2 * r + 1;
        for (int line = 0; line < (horiz ? h : w); line++) {
            int sr = 0, sg = 0, sb = 0;
            int len = horiz ? w : h;
            for (int i = -r; i <= r; i++) {
                int c = in[idx(line, Math.max(0, Math.min(len - 1, i)), w, horiz)];
                sr += c >> 16 & 255;
                sg += c >> 8 & 255;
                sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                out[idx(line, i, w, horiz)] = (sr / n) << 16 | (sg / n) << 8 | (sb / n);
                int ca = in[idx(line, Math.min(len - 1, i + r + 1), w, horiz)], cb = in[idx(line, Math.max(0, i - r), w, horiz)];
                sr += (ca >> 16 & 255) - (cb >> 16 & 255);
                sg += (ca >> 8 & 255) - (cb >> 8 & 255);
                sb += (ca & 255) - (cb & 255);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horiz) {
        return horiz ? line * w + i : i * w + line;
    }

    // ---------------------------------------------------------------- the exterior livery (liner3d.py build_fus_tex / build_fin_tex)

    /** The livery image's size and scale (px a block). */
    public static final int LIV_W = 2048, LIV_H = 1024;
    public static final double LIV_PX = 80;
    /** The fuselage texture's x range (blocks) and height (theta 0 top .. 180 bottom). */
    public static final double FX0 = -9.3, FX1 = 9.3;
    public static final int FUS_W = (int) ((FX1 - FX0) * LIV_PX), FUS_H = 304;
    /** Livery regions {x, y}: fuselage right / left (with the L1 doorway) / left without it (the door's own skin), fin right / left. */
    public static final int[] R_FUS_R = {0, 0}, R_FUS_L = {0, 308}, R_DOOR = {0, 616}, R_FIN_R = {1496, 0}, R_FIN_L = {1496, 268};
    public static final double FIN_X0 = -8.2, FIN_X1 = -4.0, FIN_Y0 = 2.6, FIN_Y1 = 5.9;
    public static final int FIN_W = (int) ((FIN_X1 - FIN_X0) * LIV_PX), FIN_H = (int) ((FIN_Y1 - FIN_Y0) * LIV_PX);
    /** The nacelle's colour strip (u along x -0.2..2.2), the white pylon / doors patch. */
    public static final int[] R_NAC = {1496, 540, 256, 16}, R_WHITE = {1760, 540, 16, 16};
    public static final double NAC_X0 = -0.2, NAC_X1 = 2.2;
    /** The cabin windows on the wave-1 exterior (over icabin.py's rows) and the L1 door. */
    public static final double[] WINDOWS = {4.63, 3.93, 3.23, 2.53, 1.83};
    public static final double DOOR_X0 = 5.00, DOOR_X1 = 5.60;

    static final Color NAVY = rgb(24, 44, 104), EV = rgb(225, 70, 70), SKIN = rgb(238, 240, 244), BELLY = rgb(196, 200, 208);

    /** The cheatline's centre angle (deg from the top) along x: level under the windows, sweeping up to the fin aft. */
    static double stripe(double x) {
        if (x > -1.2) {
            return 92.0;
        }
        double k = Math.min(1.0, (-1.2 - x) / 5.1);
        return 92.0 - 70.0 * Math.pow(k, 1.5);
    }

    /** The exterior livery for a board number (two digits, SkinMath.boardNumber). */
    public static BufferedImage bakeLivery(int board) {
        BufferedImage img = new BufferedImage(LIV_W, LIV_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = smooth(img.createGraphics());
        g.setColor(SKIN);
        g.fillRect(0, 0, LIV_W, LIV_H);
        String reg = "СА-32" + (board < 10 ? "0" : "") + Math.max(0, Math.min(99, board));
        fuselage(g, R_FUS_R[0], R_FUS_R[1], 1, true, reg);
        fuselage(g, R_FUS_L[0], R_FUS_L[1], -1, true, reg);
        fuselage(g, R_DOOR[0], R_DOOR[1], -1, false, reg);
        fin(g, R_FIN_R[0], R_FIN_R[1], 1, board);
        fin(g, R_FIN_L[0], R_FIN_L[1], -1, board);
        // the nacelle strip: white, a navy and an EV band behind the lip, the bare-metal anti-ice lip at the front
        for (int i = 0; i < R_NAC[2]; i++) {
            double x = NAC_X0 + (NAC_X1 - NAC_X0) * (i + 0.5) / R_NAC[2];
            g.setColor(x > 1.9 ? rgb(170, 174, 182) : x < 1.62 && x > 1.5 ? NAVY : x < 1.47 && x > 1.42 ? EV : SKIN);
            g.fillRect(R_NAC[0] + i, R_NAC[1], 1, R_NAC[3]);
        }
        g.setColor(SKIN);
        g.fillRect(R_WHITE[0], R_WHITE[1], R_WHITE[2], R_WHITE[3]);
        g.dispose();
        return img;
    }

    private static void fuselage(Graphics2D g0, int ox, int oy, int side, boolean doorway, String reg) {
        Graphics2D g = (Graphics2D) g0.create();
        g.clipRect(ox, oy, FUS_W, FUS_H);
        g.translate(ox, oy);
        Fus f = new Fus(side);
        g.setColor(BELLY);
        g.fill(new Rectangle2D.Double(0, f.v(118), FUS_W, FUS_H - f.v(118)));
        g.setColor(rgb(214, 217, 222));
        g.setStroke(new BasicStroke(1));
        for (double xx = -8; xx <= 8.6; xx += 1.0) {
            g.draw(new java.awt.geom.Line2D.Double(f.u(xx), f.v(10), f.u(xx), f.v(170)));
        }
        for (double th : new double[] {30, 118, 150}) {
            g.draw(new java.awt.geom.Line2D.Double(f.u(-8.6), f.v(th), f.u(8.2), f.v(th)));
        }
        // the cheatline: navy over EV red, swept up to the fin aft
        double[][] bands = {{-6, 1.5}, {2.5, 9.5}};
        Color[] bc = {NAVY, EV};
        for (int b = 0; b < 2; b++) {
            GeneralPath p = new GeneralPath();
            int n = 240;
            double o0 = bands[b][0], o1 = bands[b][1];
            p.moveTo((float) f.u(7.75), (float) f.v(92 + (o0 + o1) / 2));
            for (int i = 1; i < n; i++) {
                double x = 7.35 + (-9.1 - 7.35) * i / (n - 1.0);
                double s = 1 + 0.25 * Math.max(0, -1.2 - x);
                p.lineTo((float) f.u(x), (float) f.v(stripe(x) + o0 * s));
            }
            for (int i = n - 1; i >= 1; i--) {
                double x = 7.35 + (-9.1 - 7.35) * i / (n - 1.0);
                double s = 1 + 0.25 * Math.max(0, -1.2 - x);
                p.lineTo((float) f.u(x), (float) f.v(stripe(x) + o1 * s));
            }
            p.closePath();
            g.setColor(bc[b]);
            g.fill(p);
        }
        // the radome
        g.setColor(rgb(228, 230, 234));
        g.fill(new Rectangle2D.Double(Math.min(f.u(8.22), f.u(9.3)), 0, Math.abs(f.u(9.3) - f.u(8.22)), FUS_H));
        g.setColor(rgb(170, 174, 180));
        g.setStroke(new BasicStroke(2));
        g.draw(new java.awt.geom.Line2D.Double(f.u(8.22), 0, f.u(8.22), FUS_H));
        // cockpit windows: windscreen half + two side windows
        Color gl = rgb(22, 30, 42), fr = rgb(60, 64, 72);
        double[][][] panes = {{{8.32, 3}, {7.96, 3}, {7.86, 37}, {8.22, 43}}, {{7.8, 44}, {7.45, 44}, {7.42, 68}, {7.82, 64}},
                {{7.35, 47}, {7.08, 47}, {7.08, 66}, {7.33, 66}}};
        for (double[][] pn : panes) {
            GeneralPath p = new GeneralPath();
            for (int i = 0; i < pn.length; i++) {
                if (i == 0) {
                    p.moveTo((float) f.u(pn[i][0]), (float) f.v(pn[i][1]));
                } else {
                    p.lineTo((float) f.u(pn[i][0]), (float) f.v(pn[i][1]));
                }
            }
            p.closePath();
            g.setColor(gl);
            g.fill(p);
            g.setColor(fr);
            g.setStroke(new BasicStroke(3));
            g.draw(p);
        }
        g.setColor(rgb(18, 18, 20));
        g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Double(f.u(8.18), f.v(37), f.u(8.27), f.v(12)));
        // cabin windows over the rows + the overwing exits
        for (double xw : WINDOWS) {
            f.rrect(g, xw - 0.13, xw + 0.13, 56, 73, 14, gl, rgb(150, 156, 164), 3);
        }
        f.rrect(g, 0.25, 0.85, 50, 104, 8, null, rgb(150, 156, 164), 3);
        f.rrect(g, 0.42, 0.68, 56, 73, 14, gl, rgb(150, 156, 164), 3);
        // the forward (L1 / R1) and aft (L2 / R2) passenger doors with their windows
        double[][] doors = {{DOOR_X0, DOOR_X1}, {-3.95, -3.42}};
        for (double[] d : doors) {
            f.rrect(g, d[0], d[1], 41, 107, 12, null, rgb(140, 146, 154), 3);
            double c = (d[0] + d[1]) / 2;
            f.rrect(g, c - 0.09, c + 0.09, 54, 66, 8, gl, null, 0);
        }
        if (side < 0 && doorway) {
            f.rrect(g, DOOR_X0 + 0.01, DOOR_X1 - 0.01, 42, 106, 11, rgb(40, 42, 48), null, 0);       // the open doorway
            f.rrect(g, DOOR_X0, DOOR_X1, 41, 107, 12, null, rgb(140, 146, 154), 3);
        }
        if (side > 0) {
            f.rrect(g, 3.45, 4.15, 112, 150, 8, null, rgb(150, 154, 162), 3);
            f.rrect(g, -2.95, -2.25, 112, 150, 8, null, rgb(150, 154, 162), 3);
            f.rrect(g, -7.35, -7.0, 118, 132, 3, rgb(150, 156, 164), null, 0);
        }
        g.setColor(rgb(40, 46, 44));
        g.fill(new Rectangle2D.Double(Math.min(f.u(6.5), f.u(7.86)), f.v(165), Math.abs(f.u(7.86) - f.u(6.5)), FUS_H - f.v(165)));
        // static ports
        f.rrect(g, 6.88, 7.12, 92, 101, 3, rgb(222, 224, 228), rgb(150, 156, 164), 2);
        for (int k = 0; k < 3; k++) {
            double cx = f.u(6.93 + k * 0.07);
            g.setColor(rgb(30, 30, 34));
            g.fill(new Ellipse2D.Double(cx - 4, f.v(96.5) - 4, 8, 8));
        }
        // the title over the windows, the SA emblem and the registration on the rear fuselage, the EV tag
        g.setColor(NAVY);
        Font t = font('m', 44);
        g.setFont(t);
        String title = "Silicon Age";
        double tw = t.getStringBounds(title, FRC).getWidth();
        g.drawString(title, (float) (f.u(3.3) - tw / 2), (float) f.v(47));
        hex(g, f.u(-4.45), f.v(84), 24, NAVY, Color.WHITE);
        Font rf = font('m', 40);
        g.setFont(rf);
        g.setColor(rgb(30, 34, 44));
        double rw = rf.getStringBounds(reg, FRC).getWidth();
        g.drawString(reg, (float) (side > 0 ? f.u(-4.85) - rw : f.u(-4.85) + 4), (float) (f.v(84) + 14));
        f.rrect(g, 6.55, 6.9, 76, 83, 5, EV, null, 0);
        Font ef = font('m', 21);
        g.setFont(ef);
        g.setColor(rgb(20, 20, 20));
        double ew = ef.getStringBounds("EV", FRC).getWidth();
        g.drawString("EV", (float) ((f.u(6.55) + f.u(6.9)) / 2 - ew / 2), (float) (f.v(79.5) + 7));
        g.dispose();
    }

    private static void hex(Graphics2D g, double cx, double cy, double r, Color fill, Color txt) {
        GeneralPath p = new GeneralPath();
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(60 * k + 30);
            if (k == 0) {
                p.moveTo((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)));
            } else {
                p.lineTo((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)));
            }
        }
        p.closePath();
        g.setColor(fill);
        g.fill(p);
        Font f = font('m', r);
        g.setFont(f);
        g.setColor(txt);
        double w = f.getStringBounds("SA", FRC).getWidth();
        g.drawString("SA", (float) (cx - w / 2), (float) (cy + r * 0.35));
    }

    /** The fuselage texture's (x, theta) -> px mapping of one side (+1 right: u = x - X0; -1 left: u = X1 - x). */
    static final class Fus {
        final int side;

        Fus(int side) {
            this.side = side;
        }

        double u(double x) {
            return side > 0 ? (x - FX0) * LIV_PX : (FX1 - x) * LIV_PX;
        }

        double v(double th) {
            return th / 180.0 * FUS_H;
        }

        void rrect(Graphics2D g, double xa, double xb, double t0, double t1, double r, Color fill, Color outline, double w) {
            double a = Math.min(u(xa), u(xb)), b = Math.max(u(xa), u(xb));
            RoundRectangle2D s = new RoundRectangle2D.Double(a, v(t0), b - a, v(t1) - v(t0), 2 * r, 2 * r);
            if (fill != null) {
                g.setColor(fill);
                g.fill(s);
            }
            if (outline != null) {
                g.setColor(outline);
                g.setStroke(new BasicStroke((float) w));
                g.draw(s);
            }
        }
    }

    private static void fin(Graphics2D g0, int ox, int oy, int side, int board) {
        Graphics2D g = (Graphics2D) g0.create();
        g.clipRect(ox, oy, FIN_W, FIN_H);
        g.translate(ox, oy);
        g.setColor(NAVY);
        g.fillRect(0, 0, FIN_W, FIN_H);
        GeneralPath p = new GeneralPath();
        p.moveTo((float) fu(-4.0, side), (float) fv(2.6));
        p.lineTo((float) fu(-4.0, side), (float) fv(3.15));
        p.lineTo((float) fu(-8.2, side), (float) fv(4.25));
        p.lineTo((float) fu(-8.2, side), (float) fv(2.6));
        p.closePath();
        g.setColor(EV);
        g.fill(p);
        GeneralPath q = new GeneralPath();
        q.moveTo((float) fu(-4.0, side), (float) fv(3.25));
        q.lineTo((float) fu(-4.0, side), (float) fv(3.33));
        q.lineTo((float) fu(-8.2, side), (float) fv(4.43));
        q.lineTo((float) fu(-8.2, side), (float) fv(4.35));
        q.closePath();
        g.setColor(rgb(240, 242, 246));
        g.fill(q);
        double cx = fu(-6.05, side), cy = fv(4.55);
        GeneralPath h = new GeneralPath();
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(60 * k + 30);
            double r = 66;
            if (k == 0) {
                h.moveTo((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)));
            } else {
                h.lineTo((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)));
            }
        }
        h.closePath();
        g.setColor(rgb(245, 246, 250));
        g.fill(h);
        g.setColor(EV);
        g.setStroke(new BasicStroke(8));
        g.draw(h);
        Font f = font('m', 64);
        g.setFont(f);
        g.setColor(NAVY);
        double w = f.getStringBounds("SA", FRC).getWidth();
        g.drawString("SA", (float) (cx - w / 2), (float) (cy + 22));
        String n = (board < 10 ? "0" : "") + Math.max(0, Math.min(99, board));
        Font nf = font('m', 36);
        g.setFont(nf);
        g.setColor(rgb(240, 242, 246));
        double nw = nf.getStringBounds(n, FRC).getWidth();
        g.drawString(n, (float) (fu(-6.05, side) - nw / 2), (float) (fv(3.92) + 12));
        g.dispose();
    }

    static double fu(double x, int side) {
        return side > 0 ? (x - FIN_X0) * LIV_PX : (FIN_X1 - x) * LIV_PX;
    }

    static double fv(double y) {
        return (FIN_Y1 - y) * LIV_PX;
    }

    /** The spinner's spiral and the reverser cascade: two small repeat patterns (64 x 64). */
    public static BufferedImage pattern(boolean spinner) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                double u = (x + 0.5) / 64, v = (y + 0.5) / 64;
                int c;
                if (spinner) {
                    c = u < 0.14 ? 0xF0F0F4 : 0x22242A;
                } else {
                    c = u < 0.45 || v < 0.18 ? 0x141418 : 0x3A3C42;
                }
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    /** Control helper for the overlays: the panel's rect in the atlas (LinerControls.OVH.. -> LinerTexMath.T_*). */
    public static int rectOf(int panel) {
        return panel;                                            // the first six rects are the panels in LinerControls order
    }

    static {
        if (LinerControls.PANELS != 6) {
            throw new IllegalStateException("liner panels");
        }
    }

    private LinerPaintSVA() {
    }
}
