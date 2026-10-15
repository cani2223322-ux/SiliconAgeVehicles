package com.sva.client;

import java.util.List;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.airport.ChartMath;
import com.sva.item.ItemApproachChartSVA;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;

/**
 * Airport wave B2 (plan-next §9, item 34; mock-up veh_airport_impl_b2_4_chart): the «Карта подхода» in hand - the sheet
 * of airport.ChartMath.layout (the same primitives the preview draws): the header, the info box, the plan view, the
 * profile, the notes, the missed approach; «УСТАРЕЛА» stamped when the airport's tower has moved its charts' version on (or
 * is gone). Fixed-function GL; Esc / the inventory key closes it.
 */
@SideOnly(Side.CLIENT)
public class GuiChartSVA extends GuiScreen {

    private final ChartMath.Chart chart;

    public GuiChartSVA(ItemStack s) {
        chart = ItemApproachChartSVA.chart(s);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == 1 || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        if (chart == null) {
            return;
        }
        boolean old = ChartMath.outdated(chart.version, AirportClientSVA.chartVersion(chart.icao));
        float s = Math.min((width - 20) / ChartMath.W, (height - 20) / ChartMath.H);
        float x0 = (width - ChartMath.W * s) / 2, y0 = (height - ChartMath.H * s) / 2;
        draw(fontRendererObj, ChartMath.layout(chart, old), x0, y0, s);
        super.drawScreen(mx, my, pt);
    }

    static void colour(int rgb) {
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
    }

    /** The primitives at (x0, y0), `s` pixels a layout unit (the tower screen draws its chart box the same way). */
    public static void draw(FontRenderer fr, List<ChartMath.Prim> l, float x0, float y0, float s) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (ChartMath.Prim p : l) {
            float ax = x0 + p.x0 * s, ay = y0 + p.y0 * s, bx = x0 + p.x1 * s, by = y0 + p.y1 * s;
            switch (p.kind) {
                case ChartMath.FILL:
                    colour(p.colour);
                    GL11.glBegin(GL11.GL_QUADS);
                    GL11.glVertex2f(ax, ay);
                    GL11.glVertex2f(ax, by);
                    GL11.glVertex2f(bx, by);
                    GL11.glVertex2f(bx, ay);
                    GL11.glEnd();
                    break;
                case ChartMath.RECT:
                    colour(p.colour);
                    GL11.glLineWidth(1F);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(ax, ay);
                    GL11.glVertex2f(bx, ay);
                    GL11.glVertex2f(bx, by);
                    GL11.glVertex2f(ax, by);
                    GL11.glEnd();
                    break;
                case ChartMath.LINE:
                case ChartMath.DASH:
                    colour(p.colour);
                    GL11.glLineWidth(p.colour == ChartMath.MAG ? 2F : 1.2F);
                    GL11.glBegin(GL11.GL_LINES);
                    if (p.kind == ChartMath.LINE) {
                        GL11.glVertex2f(ax, ay);
                        GL11.glVertex2f(bx, by);
                    } else {
                        double len = Math.hypot(bx - ax, by - ay);
                        int n = Math.max(1, (int) (len / 6));
                        for (int i = 0; i < n; i += 2) {
                            double t0 = i / (double) n, t1 = Math.min(1, (i + 1) / (double) n);
                            GL11.glVertex2f((float) (ax + (bx - ax) * t0), (float) (ay + (by - ay) * t0));
                            GL11.glVertex2f((float) (ax + (bx - ax) * t1), (float) (ay + (by - ay) * t1));
                        }
                    }
                    GL11.glEnd();
                    break;
                case ChartMath.CIRCLE: {
                    colour(p.colour);
                    float cx = (ax + bx) / 2, cy = (ay + by) / 2, r = Math.abs(bx - ax) / 2;
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    for (int i = 0; i < 16; i++) {
                        double a = i * Math.PI / 8;
                        GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
                    }
                    GL11.glEnd();
                    break;
                }
                case ChartMath.TRI: {
                    colour(p.colour);
                    float cx = (ax + bx) / 2;
                    GL11.glBegin(GL11.GL_TRIANGLES);
                    GL11.glVertex2f(cx, Math.min(ay, by));
                    GL11.glVertex2f(Math.min(ax, bx) - (ax == bx ? 3 * s : 0), Math.max(ay, by));
                    GL11.glVertex2f(Math.max(ax, bx) + (ax == bx ? 3 * s : 0), Math.max(ay, by));
                    GL11.glEnd();
                    break;
                }
                case ChartMath.TEXT: {
                    String t = p.key ? Lang.tr(p.text, (Object[]) p.args) : p.text;
                    float k = p.size / 8F * s;
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glPushMatrix();
                    GL11.glTranslatef(ax, ay, 0F);
                    GL11.glScalef(k, k, 1F);
                    int w = fr.getStringWidth(t);
                    int x = p.align == 1 ? -w : p.align == 2 ? -w / 2 : 0;
                    fr.drawString(t, x, 0, p.colour);
                    GL11.glPopMatrix();
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    break;
                }
                default:
                    break;
            }
        }
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }
}
