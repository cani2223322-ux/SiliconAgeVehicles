package com.sva.client;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.HiResMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Hi-res cockpit (2K / 4K wave): the Java2D side - no GL, no Minecraft classes, so it runs on the background baking thread
 * (HiResSVA) and in the standalone preview (docs/mockups/veh_cab_hires_preview.png). Paints, anti-aliased:
 * - the glyph atlas: printable ASCII + Cyrillic (+ ° № « » - –) of a bold sans with Cyrillic, white on transparent, in
 *   HiResMath's cells (the vanilla 8-unit line box in a 10-unit cell), as luminance + alpha mips;
 * - a static face: radial marks and text lines (grey levels) over [-1, 1] x [-1, 1], the same mips.
 * The font: the first installed of a few condensed sans families that can show Cyrillic, else the logical "SansSerif"
 * (Java maps it to Arial / DejaVu Sans / ... with Cyrillic). Client only; the dedicated server never loads this class.
 */
@SideOnly(Side.CLIENT)
public final class HiResPaintSVA {

    /** Preferred families (condensed reads better on narrow cockpit cells), then the logical fallback. */
    private static final String[] FAMILIES = {"Roboto Condensed", "DejaVu Sans Condensed", "Arial Narrow", "Liberation Sans Narrow",
            "PT Sans Narrow", "SansSerif"};
    private static final String CYRILLIC_PROBE = "АБВЖЙЩЫЭЮЯабвжйщыэюяЁё";
    /** The atlas' characters beyond ASCII 32..126 and А..я. */
    private static final char[] EXTRA = {'Ё', 'ё', '°', '№', '«', '»', '–', '—', '·'};
    /** The char -> glyph table covers chars below this. */
    static final int TABLE = 0x2120;

    private static Font base;
    private static float cap;

    private HiResPaintSVA() {
    }

    /** The chosen font (bold, size 100) - looked up once. */
    static synchronized Font baseFont() {
        if (base == null) {
            Font pick = null;
            java.util.Set<String> have = new java.util.HashSet<String>();
            try {
                for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
                    have.add(f);
                }
            } catch (Throwable t) {
                // a broken font config: only the logical font then
            }
            for (String fam : FAMILIES) {
                if (!"SansSerif".equals(fam) && !have.contains(fam)) {
                    continue;
                }
                Font f = new Font(fam, Font.BOLD, 100);
                if (f.canDisplayUpTo(CYRILLIC_PROBE) == -1 && f.canDisplayUpTo("0123456789%") == -1) {
                    pick = f;
                    break;
                }
            }
            base = pick != null ? pick : new Font("Dialog", Font.BOLD, 100);
            cap = capRatio(base);
        }
        return base;
    }

    /** Cap height / font size ('H' measured), for sizing a font by the cap height. */
    private static float capRatio(Font f) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        Rectangle2D b = f.createGlyphVector(frc, "H").getVisualBounds();
        float r = (float) (b.getHeight() / f.getSize2D());
        return r > 0.3F && r < 1.2F ? r : 0.72F;
    }

    static synchronized float cap() {
        baseFont();
        return cap;
    }

    private static Graphics2D smooth(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** ARGB pixels -> luminance + alpha (the luminance where alpha is 0: white, so edges never darken). */
    private static byte[] toLA(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] la = new byte[w * h * 2];
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24;
            la[i * 2] = (byte) (a == 0 ? 255 : (px[i] >> 8 & 255));
            la[i * 2 + 1] = (byte) a;
        }
        return la;
    }

    private static byte[][] mips(byte[] la, int w, int h, int levels) {
        byte[][] out = new byte[levels][];
        out[0] = la;
        for (int i = 1; i < levels; i++) {
            out[i] = HiResMath.downLA(out[i - 1], Math.max(1, w >> (i - 1)), Math.max(1, h >> (i - 1)));
        }
        return out;
    }

    // ------------------------------------------------------------------ the glyph atlas

    /** The atlas on the CPU side: the mips and per glyph its metrics (font units) and texture rectangle. */
    static final class Atlas {
        int level, glyphPx, w, h;
        byte[][] mips;
        /** char -> glyph index + 1 (0: not in the atlas). */
        final short[] index = new short[TABLE];
        float[] adv, offX, quadW, u0, v0, u1, v1;
        /** The quad's top / bottom in font units (y down; the cell -5..+5 plus the padding). */
        float top, bottom;
        /** Set by the render thread once uploaded. */
        int tex;

        boolean has(char ch) {
            return ch < TABLE && index[ch] != 0;
        }

        /** Width in font units, NaN when a char is missing (the caller then uses the vanilla font). */
        float width(String s) {
            float wsum = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch >= TABLE || index[ch] == 0) {
                    return Float.NaN;
                }
                wsum += adv[index[ch] - 1];
            }
            return wsum;
        }
    }

    static Atlas buildAtlas(int level) {
        int g = HiResMath.glyphPx(level), pad = HiResMath.pad(g);
        float size = HiResMath.CAP_UNITS / HiResMath.CELL_UNITS * g / cap();
        Font f = baseFont().deriveFont(size);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        List<Character> chars = new ArrayList<Character>();
        for (char c = 32; c < 127; c++) {
            chars.add(Character.valueOf(c));
        }
        for (char c = 'А'; c <= 'я'; c++) {
            chars.add(Character.valueOf(c));
        }
        for (char c : EXTRA) {
            chars.add(Character.valueOf(c));
        }
        List<GlyphVector> gvs = new ArrayList<GlyphVector>();
        List<Character> kept = new ArrayList<Character>();
        List<int[]> ink = new ArrayList<int[]>();
        List<Float> advs = new ArrayList<Float>();
        for (Character c : chars) {
            if (!f.canDisplay(c.charValue())) {
                continue;
            }
            GlyphVector gv = f.createGlyphVector(frc, String.valueOf(c.charValue()));
            float adv = gv.getGlyphMetrics(0).getAdvanceX();
            Rectangle2D vb = gv.getVisualBounds();
            int left = (int) Math.floor(Math.min(0, vb.getMinX())), right = (int) Math.ceil(Math.max(adv, vb.getMaxX()));
            if (vb.isEmpty()) {
                left = 0;
                right = (int) Math.ceil(adv);
            }
            gvs.add(gv);
            kept.add(c);
            ink.add(new int[] {left, right});
            advs.add(Float.valueOf(adv));
        }
        int n = kept.size(), cellH = g + 2 * pad;
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) {
            widths[i] = ink.get(i)[1] - ink.get(i)[0] + 2 * pad;
        }
        int[] lay = HiResMath.pack(widths, cellH, HiResMath.atlasWidth(g));
        if (lay == null) {
            throw new IllegalStateException("glyph wider than the atlas");
        }
        Atlas a = new Atlas();
        a.level = level;
        a.glyphPx = g;
        a.w = lay[0];
        a.h = lay[1];
        a.adv = new float[n];
        a.offX = new float[n];
        a.quadW = new float[n];
        a.u0 = new float[n];
        a.v0 = new float[n];
        a.u1 = new float[n];
        a.v1 = new float[n];
        float u = HiResMath.unitsPerPx(g);
        a.top = HiResMath.CELL_TOP - pad * u;
        a.bottom = HiResMath.CELL_TOP + HiResMath.CELL_UNITS + pad * u;
        BufferedImage img = new BufferedImage(a.w, a.h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = smooth(img);
        g2.setColor(Color.WHITE);
        float base = HiResMath.baselinePx(g);
        for (int i = 0; i < n; i++) {
            int x0 = lay[2 + i * 2], y0 = lay[3 + i * 2], left = ink.get(i)[0];
            g2.drawGlyphVector(gvs.get(i), x0 + pad - left, y0 + pad + base);
            a.index[kept.get(i).charValue()] = (short) (i + 1);
            a.adv[i] = advs.get(i).floatValue() * u;
            a.offX[i] = (left - pad) * u;
            a.quadW[i] = widths[i] * u;
            a.u0[i] = x0 / (float) a.w;
            a.u1[i] = (x0 + widths[i]) / (float) a.w;
            a.v0[i] = y0 / (float) a.h;
            a.v1[i] = (y0 + cellH) / (float) a.h;
        }
        g2.dispose();
        a.mips = mips(toLA(img), a.w, a.h, HiResMath.atlasLevels(g));
        return a;
    }

    // ------------------------------------------------------------------ static faces

    /** A static face to bake over [-1, 1] x [-1, 1] (y up): radial marks and text lines, grey levels 0..255. */
    static final class Face {
        /** {deg clockwise from up, r0, r1, width, grey}. */
        final List<float[]> rays = new ArrayList<float[]>();
        final List<String> texts = new ArrayList<String>();
        /** {x, y (the line box' centre), height (8 units), maxW, grey}. */
        final List<float[]> textBoxes = new ArrayList<float[]>();

        Face ray(float deg, float r0, float r1, float w, int grey) {
            rays.add(new float[] {deg, r0, r1, w, grey});
            return this;
        }

        /** Centred text like CockpitInstrumentsSVA.text: `h` model units for the 8-unit line, never wider than maxW. */
        Face text(String s, float x, float y, float h, float maxW, int grey) {
            texts.add(s);
            textBoxes.add(new float[] {x, y, h, maxW, grey});
            return this;
        }
    }

    /** Text width in font units at the atlas metrics (font size for a 7-unit cap). */
    static float widthUnits(Font f100, String s) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        float adv = (float) f100.getStringBounds(s, frc).getWidth();
        return adv / (f100.getSize2D() * cap() / HiResMath.CAP_UNITS);
    }

    /** Paints a face px x px and returns its luminance + alpha mips. */
    static byte[][] paintFace(Face face, int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        float p = px / 2F;                                  // pixels per model unit (the face spans 2 units)
        for (float[] r : face.rays) {
            double a = Math.toRadians(r[0]);
            float sx = (float) Math.sin(a), cy = (float) Math.cos(a);
            float x0 = sx * r[1], y0 = cy * r[1], x1 = sx * r[2], y1 = cy * r[2];
            float nx = -cy * r[3] / 2, ny = sx * r[3] / 2;
            Path2D.Float q = new Path2D.Float();
            q.moveTo((x0 + nx + 1) * p, (1 - (y0 + ny)) * p);
            q.lineTo((x0 - nx + 1) * p, (1 - (y0 - ny)) * p);
            q.lineTo((x1 - nx + 1) * p, (1 - (y1 - ny)) * p);
            q.lineTo((x1 + nx + 1) * p, (1 - (y1 + ny)) * p);
            q.closePath();
            int gr = (int) r[4];
            g.setColor(new Color(gr, gr, gr));
            g.fill(q);
        }
        Font f100 = baseFont();
        for (int i = 0; i < face.texts.size(); i++) {
            String s = face.texts.get(i);
            float[] b = face.textBoxes.get(i);
            float wu = widthUnits(f100, s);
            if (s.length() == 0 || wu <= 0) {
                continue;
            }
            float k = HiResMath.fit(wu, b[2], b[3]);                     // model units per font unit
            float size = HiResMath.CAP_UNITS * k * p / cap();
            g.setFont(f100.deriveFont(size));
            int gr = (int) b[4];
            g.setColor(new Color(gr, gr, gr));
            float x = b[0] - wu * k / 2, baseY = b[1] - HiResMath.BASELINE * k;
            g.drawString(s, (x + 1) * p, (1 - baseY) * p);
        }
        g.dispose();
        return mips(toLA(img), px, px, HiResMath.faceLevels(px));
    }

    // ------------------------------------------------------------------ preview helpers (the standalone mock-up)

    /** A white-on-transparent ARGB image of the atlas' base level (preview / debugging). */
    static BufferedImage atlasImage(Atlas a) {
        BufferedImage img = new BufferedImage(a.w, a.h, BufferedImage.TYPE_INT_ARGB);
        byte[] la = a.mips[0];
        for (int y = 0; y < a.h; y++) {
            for (int x = 0; x < a.w; x++) {
                int i = (y * a.w + x) * 2, l = la[i] & 255, al = la[i + 1] & 255;
                img.setRGB(x, y, al << 24 | l << 16 | l << 8 | l);
            }
        }
        return img;
    }
}
