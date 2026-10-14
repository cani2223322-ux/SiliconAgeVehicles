package com.sva.client;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

/**
 * Liner wave 1: the 2D drawing kit of the SA-320's faces - its displays (LinerDisplaysSVA), the panels' run-time
 * overlays (LinerCockpitSVA: lit lamp halves, selector bars, toggle levers, window digits) and the flat «Пульты» screen
 * (GuiLinerPanelsSVA). Everything is drawn in a face's own units, x right and y DOWN (the texture painter's convention),
 * after the caller set a frame: in the cockpit a matrix from the face's units onto its place in the model (frame()),
 * on the screen a plain 2D one. Fixed-function GL only; text through HiFontSVA (the anti-aliased glyph atlas, the vanilla
 * font until it is baked). A brightness factor (the PFD / ND knobs, the panel lighting) scales every colour.
 */
@SideOnly(Side.CLIENT)
public final class LinerGfxSVA {

    /** The colour scale (0..1) of what is drawn now. */
    static float bright = 1F;
    /** The depth lift a layer (model units in the cockpit frame; 0 on the flat screen). */
    static float lift = 0.0004F;
    private static float z;
    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

    private LinerGfxSVA() {
    }

    /**
     * Enters a face's frame: its units (x right, y down) onto the plane through `tl` (the face's top-left corner, the frame
     * the caller's matrix is in) spanned by u (x: units -> along u) and v (y down), lifted along the normal toward the viewer.
     * The caller pops the matrix.
     */
    static void frame(float[] tl, float[] u, float[] v, float unitsW, float unitsH, float liftBy) {
        float[] ux = {u[0] / unitsW, u[1] / unitsW, u[2] / unitsW}, vy = {v[0] / unitsH, v[1] / unitsH, v[2] / unitsH};
        float[] n = cross(v, u);
        float l = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        n = new float[] {n[0] / l, n[1] / l, n[2] / l};
        GL11.glPushMatrix();
        MAT.clear();
        MAT.put(ux[0]).put(ux[1]).put(ux[2]).put(0F);
        MAT.put(vy[0]).put(vy[1]).put(vy[2]).put(0F);
        MAT.put(n[0]).put(n[1]).put(n[2]).put(0F);
        MAT.put(tl[0] + n[0] * liftBy).put(tl[1] + n[1] * liftBy).put(tl[2] + n[2] * liftBy).put(1F);
        MAT.flip();
        GL11.glMultMatrix(MAT);
        z = 0F;
    }

    /** The next layer goes LIFT nearer the viewer. */
    static void layer() {
        z += lift;
    }

    static void resetLayer() {
        z = 0F;
    }

    static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static void col(int rgb) {
        GL11.glColor4f((rgb >> 16 & 255) / 255F * bright, (rgb >> 8 & 255) / 255F * bright, (rgb & 255) / 255F * bright, 1F);
    }

    static void col(int rgb, float a) {
        GL11.glColor4f((rgb >> 16 & 255) / 255F * bright, (rgb >> 8 & 255) / 255F * bright, (rgb & 255) / 255F * bright, a);
    }

    static void rect(float x0, float y0, float x1, float y1, int rgb) {
        col(rgb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(x0, y0, z);
        GL11.glVertex3f(x0, y1, z);
        GL11.glVertex3f(x1, y1, z);
        GL11.glVertex3f(x1, y0, z);
        GL11.glEnd();
    }

    static void frameRect(float x0, float y0, float x1, float y1, float w, int rgb) {
        rect(x0, y0, x1, y0 + w, rgb);
        rect(x0, y1 - w, x1, y1, rgb);
        rect(x0, y0, x0 + w, y1, rgb);
        rect(x1 - w, y0, x1, y1, rgb);
    }

    static void line(float x0, float y0, float x1, float y1, float w, int rgb) {
        float dx = x1 - x0, dy = y1 - y0, l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l < 1e-6F) {
            return;
        }
        float nx = -dy / l * w / 2, ny = dx / l * w / 2;
        col(rgb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(x0 + nx, y0 + ny, z);
        GL11.glVertex3f(x0 - nx, y0 - ny, z);
        GL11.glVertex3f(x1 - nx, y1 - ny, z);
        GL11.glVertex3f(x1 + nx, y1 + ny, z);
        GL11.glEnd();
    }

    static void poly(float[] xy, int rgb) {
        col(rgb);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        for (int i = 0; i + 1 < xy.length; i += 2) {
            GL11.glVertex3f(xy[i], xy[i + 1], z);
        }
        GL11.glEnd();
    }

    static void tri(float x0, float y0, float x1, float y1, float x2, float y2, int rgb) {
        poly(new float[] {x0, y0, x1, y1, x2, y2}, rgb);
    }

    static void disc(float cx, float cy, float r, int rgb) {
        col(rgb);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex3f(cx, cy, z);
        for (int i = 0; i <= 32; i++) {
            double a = 2 * Math.PI * i / 32;
            GL11.glVertex3f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, z);
        }
        GL11.glEnd();
    }

    /** An arc band from a0 to a1 (deg clockwise from up), radius r, width w. */
    static void arc(float cx, float cy, float r, float a0, float a1, float w, int rgb) {
        int n = Math.max(4, (int) (Math.abs(a1 - a0) / 6));
        col(rgb);
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(a0 + (a1 - a0) * i / n);
            float s = (float) Math.sin(a), c = (float) -Math.cos(a);
            GL11.glVertex3f(cx + s * (r - w / 2), cy + c * (r - w / 2), z);
            GL11.glVertex3f(cx + s * (r + w / 2), cy + c * (r + w / 2), z);
        }
        GL11.glEnd();
    }

    /** A point at angle a (deg clockwise from up) and radius r. */
    static float px(float cx, float r, float a) {
        return cx + (float) Math.sin(Math.toRadians(a)) * r;
    }

    static float py(float cy, float r, float a) {
        return cy - (float) Math.cos(Math.toRadians(a)) * r;
    }

    /** Text: align -1 left / 0 centre / 1 right at x, its line centred on y, `h` tall (cap ~ 0.7 h), at most maxW wide. */
    static void text(String s, float x, float y, float h, int rgb, int align, float maxW) {
        if (s == null || s.length() == 0) {
            return;
        }
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        float wpx = HiFontSVA.width(fr, s);
        if (wpx <= 0) {
            return;
        }
        float k = h / 8F;
        if (maxW > 0 && wpx * k > maxW) {
            k = maxW / wpx;
        }
        float w = wpx * k;
        float x0 = align < 0 ? x : align == 0 ? x - w / 2 : x - w;
        GL11.glPushMatrix();
        GL11.glTranslatef(x0, y, z + lift * 0.5F);
        GL11.glScalef(k, k, 1F);
        int c = (int) ((rgb >> 16 & 255) * bright) << 16 | (int) ((rgb >> 8 & 255) * bright) << 8 | (int) ((rgb & 255) * bright);
        boolean tex = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        HiFontSVA.render(fr, s, 0F, c | 0xFF000000);
        if (!tex) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        GL11.glPopMatrix();
    }

    static void text(String s, float x, float y, float h, int rgb, int align) {
        text(s, x, y, h, rgb, align, 0);
    }

    /** A number with a decimal comma and `dec` decimals. */
    static String num(double v, int dec) {
        return String.format(java.util.Locale.ROOT, "%." + dec + "f", Double.valueOf(v)).replace('.', ',');
    }
}
