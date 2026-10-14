package com.sva.client;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.LinerTexMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/**
 * Liner wave 1: «Пульты» - the flat fallback of the 3D cockpit clicking (the «Техника» menu's row). Tabs: the overhead
 * panel, the pedestal, the glare shield (FCU / EFIS) and the main panel; each tab draws its panel from the same baked
 * atlas and the same control table as the cockpit (LinerCockpitSVA.drawPanel - the lit lamps, the selector bars, the
 * toggles, the window digits) plus the levers' positions, scaled to the screen. LMB / RMB / the wheel act as in the
 * cockpit and go to the server the same way (NetSVA.LinerControl - the pilots only; a passenger sees the panels
 * read-only). A tooltip names the control under the mouse.
 */
@SideOnly(Side.CLIENT)
public class GuiLinerPanelsSVA extends GuiScreen {

    static final int[] TABS = {LinerControls.OVH, LinerControls.PED, LinerControls.GS, LinerControls.MP};
    static final String[] TAB_KEYS = {"sva.liner.gui.tab.ovh", "sva.liner.gui.tab.ped", "sva.liner.gui.tab.fcu", "sva.liner.gui.tab.mp"};
    private static int tab;
    private final EntityVehicleSVA v;
    private float x0, y0, k;

    public GuiLinerPanelsSVA(EntityVehicleSVA v) {
        this.v = v;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int bw = Math.min(110, (width - 20) / TABS.length - 4);
        for (int i = 0; i < TABS.length; i++) {
            buttonList.add(new GuiButton(i, 10 + i * (bw + 4), 6, bw, 18, Lang.tr(TAB_KEYS[i])));
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id >= 0 && b.id < TABS.length) {
            tab = b.id;
        }
    }

    private int panel() {
        return TABS[Math.max(0, Math.min(TABS.length - 1, tab))];
    }

    /** The panel's place on the screen: fitted under the tabs, its aspect kept. */
    private void layout() {
        int p = panel();
        float pw = LinerControls.PANEL_W[p], ph = LinerControls.PANEL_H[p];
        float aw = width - 20, ah = height - 40;
        k = Math.min(aw / pw, ah / ph);
        x0 = (width - pw * k) / 2;
        y0 = 30 + (ah - ph * k) / 2;
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        layout();
        int p = panel();
        LinerSystems L = v.liner();
        int[] atlas = LinerTexSVA.atlas();
        float pw = LinerControls.PANEL_W[p], ph = LinerControls.PANEL_H[p];
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        if (atlas != null && atlas[0] > 0) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas[0]);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            float u0 = LinerTexMath.u(p, 0), v0 = LinerTexMath.v(p, 0), u1 = LinerTexMath.u(p, pw), v1 = LinerTexMath.v(p, ph);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(u0, v0);
            GL11.glVertex2f(x0, y0);
            GL11.glTexCoord2f(u0, v1);
            GL11.glVertex2f(x0, y0 + ph * k);
            GL11.glTexCoord2f(u1, v1);
            GL11.glVertex2f(x0 + pw * k, y0 + ph * k);
            GL11.glTexCoord2f(u1, v0);
            GL11.glVertex2f(x0 + pw * k, y0);
            GL11.glEnd();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        } else {
            drawRect((int) x0, (int) y0, (int) (x0 + pw * k), (int) (y0 + ph * k), 0xFF5A6A79);
            drawCenteredString(fontRendererObj, Lang.tr("sva.liner.gui.baking"), width / 2, height / 2, 0xFFFFFF);
        }
        if (L != null) {
            GL11.glPushMatrix();
            GL11.glTranslatef(x0, y0, 0F);
            GL11.glScalef(k, k, 1F);
            float lift = LinerGfxSVA.lift;
            LinerGfxSVA.lift = 0F;
            LinerGfxSVA.bright = 1F;
            HiResSVA.beginFrame();
            LinerCockpitSVA.drawPanel(p, -1, v, L, v.powered());
            levers(p, L);
            LinerGfxSVA.lift = lift;
            GL11.glPopMatrix();
        }
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
        super.drawScreen(mx, my, pt);
        LinerControls.Ctl c = hit(mx, my);
        LinerCockpitSVA.hovered = c == null ? -1 : c.index;
        if (c != null && L != null) {
            func_146283_a(java.util.Arrays.asList(LinerClickSVA.tooltip(c, L)), mx, my);
        }
        if (!LinerClickSVA.pilotSeat(mc.thePlayer, v)) {
            drawCenteredString(fontRendererObj, Lang.tr("sva.liner.gui.readOnly"), width / 2, height - 12, 0xF0B040);
        }
    }

    /** The levers' positions on the flat panels (the cockpit draws them in 3D). */
    private void levers(int p, LinerSystems L) {
        if (p == LinerControls.PED) {
            double thr = v.getThrottle();
            float ty = (float) (LinerPaintSVA.TL_IDLE - thr * (LinerPaintSVA.TL_IDLE - LinerPaintSVA.TL_TOGA));
            for (float x : new float[] {330, 430}) {
                LinerGfxSVA.rect(x - 46, ty - 18, x + 46, ty + 18, 0x2E3034);
                LinerGfxSVA.rect(x - 42, ty - 15, x + 42, ty - 6, 0x5A5C62);
            }
            float fy = LinerPaintSVA.FLAP_Y[Math.max(0, Math.min(4, L.at(LinerControls.FLAPS)))];
            LinerGfxSVA.rect(615, fy - 16, 700, fy + 16, 0xD2D4D8);
            float[] sb = {678, 780, 890};
            float sy = sb[Math.max(0, Math.min(2, L.at(LinerControls.SPD_BRK)))];
            LinerGfxSVA.rect(46, sy - 14, 114, sy + 14, 0xCDD0D4);
            for (int i = 0; i < 2; i++) {
                float x = i == 0 ? 210 : 550;
                float my = L.at(i == 0 ? LinerControls.ENG_MASTER1 : LinerControls.ENG_MASTER2) == 1 ? 1137 : 1183;
                LinerGfxSVA.rect(x - 30, my - 13, x + 30, my + 13, 0x3C3E42);
                LinerGfxSVA.rect(x - 26, my - 10, x + 26, my - 4, 0x6E7076);
            }
            if (L.on(LinerControls.PARK_BRK)) {
                LinerGfxSVA.rect(134, 1225, 146, 1285, 0x46484C);
            } else {
                LinerGfxSVA.rect(110, 1249, 170, 1261, 0x46484C);
            }
        } else if (p == LinerControls.MP) {
            float gy = L.at(LinerControls.GEAR) == 0 ? 352 : 578;
            LinerGfxSVA.rect(1206, gy - 16, 1252, gy + 16, 0xE2E4E6);
        }
    }

    private LinerControls.Ctl hit(int mx, int my) {
        layout();
        float tx = (mx - x0) / k, ty = (my - y0) / k;
        return LinerControls.hit(panel(), tx, ty);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        super.mouseClicked(mx, my, btn);
        LinerControls.Ctl c = hit(mx, my);
        if (c != null && (btn == 0 || btn == 1) && LinerClickSVA.pilotSeat(mc.thePlayer, v)) {
            LinerClickSVA.send(v, c, btn == 0 ? LinerControls.A_PRIMARY : LinerControls.A_SECONDARY);
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int w = Mouse.getEventDWheel();
        if (w != 0) {
            int mx = Mouse.getEventX() * width / mc.displayWidth, my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            LinerControls.Ctl c = hit(mx, my);
            if (c != null && LinerClickSVA.pilotSeat(mc.thePlayer, v)
                    && (c.kind == LinerControls.ROT || c.kind == LinerControls.LEV || c.kind == LinerControls.TOG)) {
                LinerClickSVA.send(v, c, w > 0 ? LinerControls.A_WHEEL_UP : LinerControls.A_WHEEL_DOWN);
            }
        }
    }

    @Override
    public void onGuiClosed() {
        LinerCockpitSVA.hovered = -1;
    }

    @Override
    public void updateScreen() {
        if (v.isDead || HudVehicleSVA.vehicleOf(mc.thePlayer) != v) {
            mc.displayGuiScreen(null);
        }
    }
}
