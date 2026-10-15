package com.sva.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sva.ReferenceSVA;
import com.sva.airport.MarkerMath;
import com.sva.airport.PlannerMath;
import com.sva.airport.RunwayLayout;
import com.sva.book.BookSchematics;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.IIcon;

/**
 * Wave C, item 43: the handbook's schematic pictures - an isometric view of a structure's cells (the real block textures
 * from the block atlas, three shaded faces a cube; the concrete pads as thin slabs), built up layer by layer: the layers
 * under the one shown dimmed, the one shown full, those above hidden; and the ILS runway as a plan from above (the strip,
 * the planner tablet's items in its colours, the critical / sensitive areas, the far markers as arrows). GUI pixels; the
 * caller sets the scissor box.
 */
@SideOnly(Side.CLIENT)
public final class BookDrawSVA {

    /** The cube's height against the tile's half width (a slightly tall isometric cube reads better at 6-12 px). */
    static final float VH = 1.15F;

    private BookDrawSVA() {
    }

    /** The isometric picture of `s` in (x, y, w, h); `upTo`: the highest layer shown (an index into s.layers(); -1 all). */
    public static void iso(BookSchematics.Schem s, int upTo, int x, int y, int w, int h) {
        int[] layers = s.layers();
        int maxY = upTo < 0 || upTo >= layers.length ? Integer.MAX_VALUE : layers[upTo];
        int curY = upTo < 0 || upTo >= layers.length ? Integer.MIN_VALUE : layers[upTo];
        int[] b = s.bounds();
        // the projected extent at unit tile half width
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minYp = Float.MAX_VALUE, maxYp = -Float.MAX_VALUE;
        for (int cx = b[0]; cx <= b[3] + 1; cx += Math.max(1, b[3] + 1 - b[0])) {
            for (int cz = b[2]; cz <= b[5] + 1; cz += Math.max(1, b[5] + 1 - b[2])) {
                for (int cy = b[1]; cy <= b[4] + 1; cy += Math.max(1, b[4] + 1 - b[1])) {
                    float px = cx - cz, py = (cx + cz) * 0.5F - cy * VH;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minYp = Math.min(minYp, py);
                    maxYp = Math.max(maxYp, py);
                }
            }
        }
        float k = Math.min((w - 8) / Math.max(1F, maxX - minX), (h - 8) / Math.max(1F, maxYp - minYp));
        k = Math.min(k, 22F);
        float ox = x + w / 2F - (minX + maxX) / 2F * k, oy = y + h / 2F - (minYp + maxYp) / 2F * k;
        List<BookSchematics.Cell> cells = new ArrayList<BookSchematics.Cell>(s.cells);
        Collections.sort(cells, new Comparator<BookSchematics.Cell>() {
            public int compare(BookSchematics.Cell a, BookSchematics.Cell c) {
                int d = (a.x + a.z) - (c.x + c.z);
                return d != 0 ? d : a.y - c.y;
            }
        });
        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        TextureMap map = (TextureMap) mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (BookSchematics.Cell c : cells) {
            if (c.y > maxY) {
                continue;
            }
            float dim = upTo >= 0 && c.y < curY ? 0.55F : 1F;
            float hh = c.role == BookSchematics.PAD ? 0.125F : 1F;
            IIcon top = map.getAtlasSprite(ReferenceSVA.ASSETS + ":" + c.top), side = map.getAtlasSprite(ReferenceSVA.ASSETS + ":" + c.side);
            float tint = c.role == BookSchematics.MASTER ? 1F : 0.92F;
            // +z face (left-down), +x face (right-down), top
            face(ox, oy, k, side, new float[] {c.x, c.y, c.z + 1, c.x + 1, c.y, c.z + 1, c.x + 1, c.y + hh, c.z + 1, c.x, c.y + hh, c.z + 1},
                    0.62F * dim * tint, hh);
            face(ox, oy, k, side, new float[] {c.x + 1, c.y, c.z + 1, c.x + 1, c.y, c.z, c.x + 1, c.y + hh, c.z, c.x + 1, c.y + hh, c.z + 1},
                    0.8F * dim * tint, hh);
            face(ox, oy, k, top, new float[] {c.x, c.y + hh, c.z, c.x + 1, c.y + hh, c.z, c.x + 1, c.y + hh, c.z + 1, c.x, c.y + hh, c.z + 1},
                    1F * dim * tint, 1F);
            if (c.role == BookSchematics.MASTER) {
                // the block placed by hand: a cyan frame round its top
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glColor4f(0.25F, 0.85F, 1F, 1F);
                GL11.glLineWidth(2F);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                float[][] t = {{c.x, c.z}, {c.x + 1, c.z}, {c.x + 1, c.z + 1}, {c.x, c.z + 1}};
                for (float[] p : t) {
                    GL11.glVertex2f(ox + (p[0] - p[1]) * k, oy + ((p[0] + p[1]) * 0.5F - (c.y + hh) * VH) * k);
                }
                GL11.glEnd();
                GL11.glLineWidth(1F);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            }
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** One textured face: 4 corners {x, y, z...}, the shade, the face's height share (a slab's side shows its lower part). */
    static void face(float ox, float oy, float k, IIcon ic, float[] p, float shade, float vShare) {
        GL11.glColor4f(shade, shade, shade, 1F);
        float u0 = ic.getMinU(), u1 = ic.getMaxU(), v0 = ic.getMinV(), v1 = ic.getMaxV();
        float vt = v1 - (v1 - v0) * vShare;
        float[][] uv = {{u0, v1}, {u1, v1}, {u1, vt}, {u0, vt}};
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < 4; i++) {
            float X = p[i * 3], Y = p[i * 3 + 1], Z = p[i * 3 + 2];
            GL11.glTexCoord2f(uv[i][0], uv[i][1]);
            GL11.glVertex2f(ox + (X - Z) * k, oy + ((X + Z) * 0.5F - Y * VH) * k);
        }
        GL11.glEnd();
    }

    /** The along range of the runway plan (blocks): the approach lights to past the localizer. */
    static final double PLAN_A0 = -RunwayLayout.ALS_LEN - 20, PLAN_A1 = BookSchematics.RWY_LEN + PlannerMath.LOC_BEYOND + 14;
    /** The across range shown (blocks, + right). */
    static final double PLAN_C = 24;

    /** The ILS runway plan in (x, y, w, h): along to the right, across down = right of the landing direction. */
    public static void plan(BookSchematics.Schem s, FontRenderer f, int x, int y, int w, int h) {
        double kx = w / (PLAN_A1 - PLAN_A0), kz = (h - 14) / (2 * PLAN_C);
        double cy = y + (h - 14) / 2.0;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // ground, the strip, its centre line
        rect(x, y, x + w, y + h - 14, 0xFF3C5A34);
        double w2 = BookSchematics.RWY_WIDTH / 2.0;
        rect(px(0, x, kx), cy - w2 * kz, px(BookSchematics.RWY_LEN, x, kx), cy + w2 * kz, 0xFF46484C);
        for (double a = 4; a < BookSchematics.RWY_LEN - 4; a += 10) {
            rect(px(a, x, kx), cy - 0.35, px(a + 5, x, kx), cy + 0.35, 0xFFE8E8E8);
        }
        rect(px(-1, x, kx), cy - w2 * kz, px(0, x, kx), cy + w2 * kz, 0xFF3CD05A);       // the landing threshold's green
        int[] occ = new int[4];
        for (BookSchematics.Cell c : s.cells) {
            if (c.box == null) {
                continue;
            }
            int kind = c.planKind;
            float[] col = AirportPlannerSVA.colour(kind, occ);
            double a0 = Math.max(PLAN_A0, c.box[0]), a1 = Math.min(PLAN_A1, c.box[1]);
            if (a1 < PLAN_A0 || a0 > PLAN_A1) {
                continue;
            }
            int argb = ((int) (Math.min(1F, col[3] * (kind >= PlannerMath.LOC_CRIT ? 2.2F : 2.6F)) * 255) << 24) | ((int) (col[0] * 255) << 16)
                    | ((int) (col[1] * 255) << 8) | (int) (col[2] * 255);
            double c0 = Math.max(-PLAN_C, c.box[2]), c1 = Math.min(PLAN_C, c.box[3]);
            double minW = kind >= PlannerMath.LOC_CRIT ? 0 : 1.2;
            double xa = px(a0, x, kx), xb = Math.max(px(a1, x, kx), xa + minW), ya = cy + c0 * kz, yb = Math.max(cy + c1 * kz, ya + minW);
            rect(xa, ya, xb, yb, argb);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // labels (half size) and the far markers' arrows
        GL11.glPushMatrix();
        GL11.glScalef(0.5F, 0.5F, 1F);
        for (BookSchematics.Cell c : s.cells) {
            if (c.box == null) {
                continue;
            }
            String t = label(c);
            if (t == null) {
                continue;
            }
            double a = (c.box[0] + c.box[1]) / 2, across = (c.box[2] + c.box[3]) / 2;
            if (a < PLAN_A0) {
                t = "< " + t + " " + (int) Math.round(-a);
                a = PLAN_A0 + 2;
            }
            int tx = (int) (px(a, x, kx) * 2), ty = (int) ((cy + across * kz) * 2) + (across >= 0 ? 6 : -14);
            f.drawString(t, tx - f.getStringWidth(t) / 2 + (a <= PLAN_A0 + 3 ? f.getStringWidth(t) / 2 : 0), ty, 0xFFFFFF);
        }
        GL11.glPopMatrix();
        f.drawString(BookSchematics.RWY_LEN + " x " + BookSchematics.RWY_WIDTH, x + 2, y + h - 11, 0x505050);
    }

    static String label(BookSchematics.Cell c) {
        switch (c.planKind) {
            case PlannerMath.LOC:
                return "LOC";
            case PlannerMath.GS:
                return "G/S";
            case PlannerMath.MARKER: {
                double a = -(c.box[0] + c.box[1]) / 2;
                for (int i = 0; i < MarkerMath.NOMINAL.length; i++) {
                    if (Math.abs(MarkerMath.NOMINAL[i] - a) < 1) {
                        return BookSchematics.markerName(i);
                    }
                }
                return "M";
            }
            case PlannerMath.RVR:
                return "RVR";
            case PlannerMath.WEATHER:
                return "WX";
            case PlannerMath.PAPI:
                return (c.box[2] + c.box[3]) / 2 > -BookSchematics.RWY_WIDTH / 2.0 - 3 ? "PAPI" : null;
            case PlannerMath.ALS:
                return "ALS";
            default:
                return null;
        }
    }

    static double px(double along, int x, double kx) {
        return x + (along - PLAN_A0) * kx;
    }

    static void rect(double x0, double y0, double x1, double y1, int argb) {
        GL11.glColor4f(((argb >> 16) & 255) / 255F, ((argb >> 8) & 255) / 255F, (argb & 255) / 255F, ((argb >>> 24) & 255) / 255F);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(x0, y0);
        GL11.glVertex2d(x0, y1);
        GL11.glVertex2d(x1, y1);
        GL11.glVertex2d(x1, y0);
        GL11.glEnd();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }
}
