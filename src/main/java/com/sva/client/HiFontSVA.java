package com.sva.client;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.lwjgl.opengl.GL11;

import com.sva.vehicle.HiResMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.FontRenderer;

/**
 * Hi-res cockpit text (2K / 4K wave): an anti-aliased glyph atlas (HiResPaintSVA.buildAtlas: a bold sans with Cyrillic,
 * 32 / 64 / 128 px glyphs by the quality level, mipmapped + anisotropic - HiResSVA) standing in for the vanilla
 * FontRenderer on every cockpit face. It keeps the vanilla font's units - an 8-unit line box (cap top -4, baseline +3,
 * y down), widths in "font pixels" - so the callers' layout maths (height / 8, k = min(h / 8, maxW / width)) is
 * untouched; only the pixels get finer.
 * Falls back to the vanilla font, string by string, while the atlas is still baking, when vehicles.cockpitHiRes is off,
 * after a failure, or for a string with a char the atlas lacks (only ASCII + Cyrillic are in it - GlyphCheckSVA).
 */
@SideOnly(Side.CLIENT)
public final class HiFontSVA {

    private static HiResPaintSVA.Atlas atlas;
    private static Future<HiResPaintSVA.Atlas> pending;
    private static int pendingGen;

    private HiFontSVA() {
    }

    /** HiResSVA.beginFrame: starts the bake for the current level, uploads it when done. */
    static void poll() {
        final int lv = HiResSVA.level();
        if (atlas != null && atlas.level == lv) {
            return;
        }
        if (pending == null) {
            pendingGen = HiResSVA.generation;
            pending = HiResSVA.submit(new Callable<HiResPaintSVA.Atlas>() {
                @Override
                public HiResPaintSVA.Atlas call() {
                    return HiResPaintSVA.buildAtlas(lv);
                }
            });
            return;
        }
        if (!pending.isDone()) {
            return;
        }
        Future<HiResPaintSVA.Atlas> f = pending;
        pending = null;
        try {
            HiResPaintSVA.Atlas a = f.get();
            if (pendingGen != HiResSVA.generation || a.level != lv) {
                return;                                     // baked for an older level: the next poll starts again
            }
            a.tex = HiResSVA.upload(a.w, a.h, a.mips);
            a.mips = null;
            atlas = a;
        } catch (Throwable t) {
            HiResSVA.fail("the glyph atlas", t);
        }
    }

    /** Frees the atlas (quality change, resource reload). */
    static void reset() {
        if (atlas != null) {
            HiResSVA.delete(atlas.tex);
            atlas = null;
        }
        pending = null;
    }

    private static HiResPaintSVA.Atlas current() {
        return HiResSVA.on() ? atlas : null;
    }

    /** The width in vanilla font units (FontRenderer.getStringWidth's), from the atlas when it draws this string. */
    public static float width(FontRenderer fr, String s) {
        HiResPaintSVA.Atlas a = current();
        if (a != null) {
            float w = a.width(s);
            if (!Float.isNaN(w)) {
                return w;
            }
        }
        return fr.getStringWidth(s);
    }

    /**
     * Draws s from x (font units) in the current frame scaled like the vanilla font's (y down, the 8-unit line from -4
     * to +4); the colour as FontRenderer.drawString's (no alpha byte = opaque). Leaves 2D texturing off and the colour set,
     * as the old drawText did.
     */
    public static void render(FontRenderer fr, String s, float x, int color) {
        HiResPaintSVA.Atlas a = current();
        if (a == null || Float.isNaN(a.width(s))) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            fr.drawString(s, Math.round(x), -4, color);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            return;
        }
        HiResSVA.beginTextured(a.tex);
        float alpha = (color & 0xFC000000) == 0 ? 1F : (color >>> 24) / 255F;
        GL11.glColor4f((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, alpha);
        GL11.glBegin(GL11.GL_QUADS);
        float pen = x, top = a.top, bot = a.bottom;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            int i = a.index[ch] - 1;
            if (ch != ' ') {
                float x0 = pen + a.offX[i], x1 = x0 + a.quadW[i];
                GL11.glTexCoord2f(a.u0[i], a.v0[i]);
                GL11.glVertex3f(x0, top, 0F);
                GL11.glTexCoord2f(a.u0[i], a.v1[i]);
                GL11.glVertex3f(x0, bot, 0F);
                GL11.glTexCoord2f(a.u1[i], a.v1[i]);
                GL11.glVertex3f(x1, bot, 0F);
                GL11.glTexCoord2f(a.u1[i], a.v0[i]);
                GL11.glVertex3f(x1, top, 0F);
            }
            pen += a.adv[i];
        }
        GL11.glEnd();
        HiResSVA.endTextured();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    /** Face space: s left-aligned at x, its line box centred on y and `height` tall (y up, model units). */
    public static void drawString(FontRenderer fr, String s, float x, float y, float height, int colour) {
        float k = height / HiResMath.LINE_UNITS;
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0F);
        GL11.glScalef(k, -k, k);
        render(fr, s, 0F, colour);
        GL11.glPopMatrix();
    }

    /** Face space: the width of s drawn `height` tall. */
    public static float stringWidth(FontRenderer fr, String s, float height) {
        return width(fr, s) * height / HiResMath.LINE_UNITS;
    }
}
