package com.sva.client;

import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.LinerTexMath;
import com.sva.vehicle.PanelViewMath;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

/**
 * Liner wave 1: «Пульты» - the flat fallback of the 3D cockpit clicking (the «Техника» menu's row). Tabs: the overhead
 * panel, the pedestal, the glare shield (FCU / EFIS) and the main panel, drawn with the same control table as the cockpit
 * (LinerCockpitSVA.drawPanel - the lit lamps, the selector bars, the toggles, the window digits) plus the levers' positions.
 * LMB / RMB / the wheel act as in the cockpit and go to the server the same way (NetSVA.LinerControl - the pilots only; a
 * passenger sees the panels read-only). A tooltip names the control under the mouse.
 * <p>
 * Wave 7 (the user: «менее размытые на 2К / 4К, реальное состояние дисплеев»): the screen works in DEVICE pixels
 * (PanelViewMath) - each tab's panel is baked by the painter (LinerPaintSVA.bakePanel, on the baker thread) at exactly the
 * scale it is shown at, so a texel is a pixel at any resolution and GUI scale (re-baked on a zoom / resize, the old
 * texture freed; the cockpit atlas stands in while it bakes); every text keeps PanelViewMath.MIN_TEXT_PX; a tab opens
 * readable and pans (drag with LMB / RMB on an empty spot) where it is larger than the screen; zoom with the - / + buttons,
 * the - / + keys, Ctrl + the wheel or the wheel off the controls (kept per tab for the session). The main panel's tab draws
 * the six DUs and the ISIS live (LinerDisplaysSVA - the very code of the 3D cockpit, its power and brightness rules),
 * clipped to their glass.
 */
@SideOnly(Side.CLIENT)
public class GuiLinerPanelsSVA extends GuiScreen {

    static final int[] TABS = {LinerControls.OVH, LinerControls.PED, LinerControls.GS, LinerControls.MP};
    static final String[] TAB_KEYS = {"sva.liner.gui.tab.ovh", "sva.liner.gui.tab.ped", "sva.liner.gui.tab.fcu", "sva.liner.gui.tab.mp"};
    private static int tab;
    /** Per tab (the session): the scale (device px a unit, 0: not set yet), the origin (device px). */
    private static final double[] K = new double[TABS.length], OX = new double[TABS.length], OY = new double[TABS.length];
    /** The screen size / GUI scale the views were made for (a change: the views start again). */
    private static int lastW, lastH, lastSf;
    private final EntityVehicleSVA v;
    /** This frame's GUI scale and the area under the tab bar (device px). */
    private int sf = 1;
    private double ax, ay, aw, ah;
    /** A drag (pan) in progress and its last device point. */
    private boolean dragging;
    private double dragX, dragY;

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
        int bw = Math.min(110, (width - 90) / TABS.length - 4);
        for (int i = 0; i < TABS.length; i++) {
            buttonList.add(new GuiButton(i, 10 + i * (bw + 4), 6, bw, 18, Lang.tr(TAB_KEYS[i])));
        }
        // wave 7: zoom out / in / the opening scale
        buttonList.add(new GuiButton(B_ZOOM, width - 76, 6, 20, 18, "-"));
        buttonList.add(new GuiButton(B_ZOOM + 1, width - 54, 6, 20, 18, "+"));
        buttonList.add(new GuiButton(B_ZOOM + 2, width - 32, 6, 24, 18, "1:1"));
        // wave 4, the FCU tab: the ILS tuned by hand - АВТО and the stations near (the server's list)
        int iw = Math.min(90, (width - 20) / 5 - 4);
        for (int i = 0; i < 5; i++) {
            GuiButton b = new GuiButton(B_ILS + i, 10 + i * (iw + 4), height - 52, iw, 18, "");
            b.visible = false;
            buttonList.add(b);
        }
    }

    /** Wave 4: the ILS list's buttons (АВТО, then the near stations); wave 7: the zoom buttons. */
    static final int B_ILS = 100, B_ZOOM = 200;

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id >= 0 && b.id < TABS.length) {
            tab = b.id;
        }
        if (b.id >= B_ZOOM && b.id < B_ZOOM + 3) {
            area();
            if (b.id == B_ZOOM + 2) {
                K[tabIndex()] = 0;                                  // back to the opening (readable) scale
            } else {
                zoomBy(b.id == B_ZOOM ? -1 : 1, ax + aw / 2, ay + ah / 2);
            }
            view(panel(), tabIndex());
        }
        if (b.id >= B_ILS && b.id < B_ILS + 5 && LinerClickSVA.pilotSeat(mc.thePlayer, v)) {
            LinerClickSVA.send(v, LinerControls.ILS_SEL, LinerControls.A_SET + (b.id - B_ILS));
        }
    }

    /** Wave 4: the ILS buttons' labels and visibility (the FCU tab only). */
    private void ilsButtons(LinerSystems L) {
        boolean fcu = panel() == LinerControls.GS && L != null;
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.id < B_ILS || b.id >= B_ILS + 5) {
                continue;
            }
            int i = b.id - B_ILS;
            b.visible = fcu && (i == 0 || i <= L.afs.ilsNear.size());
            if (!b.visible) {
                continue;
            }
            boolean on = L.afs.nearChosen == i;
            String t = i == 0 ? Lang.tr("sva.liner.gui.ilsAuto")
                    : L.afs.ilsNear.get(i - 1).ident + " " + com.sva.vehicle.LinerIls.freqText(L.afs.ilsNear.get(i - 1).freq).replace('.', ',');
            b.displayString = (on ? "> " : "") + t;
        }
    }

    private int tabIndex() {
        return Math.max(0, Math.min(TABS.length - 1, tab));
    }

    private int panel() {
        return TABS[tabIndex()];
    }

    // ---------------------------------------------------------------- the view (device px)

    /** The GUI scale and the area under the tab bar / over the bottom lines (device px); a new screen size resets the views. */
    private void area() {
        sf = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        if (mc.displayWidth != lastW || mc.displayHeight != lastH || sf != lastSf) {
            lastW = mc.displayWidth;
            lastH = mc.displayHeight;
            lastSf = sf;
            java.util.Arrays.fill(K, 0);
        }
        ax = 4 * sf;
        ay = 28 * sf;
        aw = mc.displayWidth - 8 * sf;
        ah = mc.displayHeight - ay - 16 * sf;
    }

    /** Tab t's scale and origin made valid (the opening scale the first time). */
    private void view(int p, int t) {
        double pw = LinerControls.PANEL_W[p], ph = LinerControls.PANEL_H[p];
        int max = maxTex();
        if (K[t] <= 0) {
            K[t] = PanelViewMath.initial(pw, ph, aw, ah, max);
            OX[t] = ax;
            OY[t] = ay;
        }
        K[t] = Math.max(PanelViewMath.minScale(pw, ph, aw, ah, max), Math.min(PanelViewMath.maxZoom(pw, ph, aw, ah, max), K[t]));
        double[] o = PanelViewMath.clampPan(OX[t], OY[t], pw * K[t], ph * K[t], ax, ay, aw, ah);
        OX[t] = o[0];
        OY[t] = o[1];
    }

    private void zoomBy(int steps, double mx, double my) {
        int t = tabIndex(), p = panel();
        view(p, t);
        double k0 = K[t];
        double k1 = PanelViewMath.zoom(k0, steps, LinerControls.PANEL_W[p], LinerControls.PANEL_H[p], aw, ah, maxTex());
        double[] o = PanelViewMath.zoomAt(OX[t], OY[t], k0, k1, mx, my);
        K[t] = k1;
        OX[t] = o[0];
        OY[t] = o[1];
        view(p, t);
    }

    private static int maxTexSize = -1;

    static int maxTex() {
        if (maxTexSize < 0) {
            maxTexSize = Math.max(2048, Math.min(8192, GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)));
        }
        return maxTexSize;
    }

    /** The mouse in device px (x right, y down). */
    private double mouseX() {
        return Mouse.getX();
    }

    private double mouseY() {
        return mc.displayHeight - Mouse.getY() - 1;
    }

    // ---------------------------------------------------------------- the panels' own bake

    /** Per panel: the texture shown (0 none yet), its scale key, the bake under way (and its key), the texture's size. */
    private static final int[] TEX = new int[LinerControls.PANELS], TEX_KEY = new int[LinerControls.PANELS], JOB_KEY = new int[LinerControls.PANELS];
    private static final int[] TEX_W = new int[LinerControls.PANELS], TEX_H = new int[LinerControls.PANELS];
    @SuppressWarnings("unchecked")
    private static final Future<BufferedImage>[] JOB = new Future[LinerControls.PANELS];

    /**
     * The panel's texture at scale k (its bake started / polled; the old one kept until the new one is up). @return the id
     * at this scale, minus the id of an older scale's (stretched meanwhile), 0 none
     */
    static int panelTex(int p, double k) {
        int key = PanelViewMath.scaleKey(k);
        if (JOB[p] != null && JOB[p].isDone()) {
            try {
                BufferedImage img = JOB[p].get();
                if (TEX[p] > 0) {
                    GL11.glDeleteTextures(TEX[p]);
                }
                TEX[p] = LinerTexSVA.upload(new byte[][] {LinerTexSVA.mips(img)[0]}, img.getWidth(), img.getHeight(), false);
                TEX_W[p] = img.getWidth();
                TEX_H[p] = img.getHeight();
                TEX_KEY[p] = JOB_KEY[p];
            } catch (Throwable e) {
                HiResSVA.fail("the panels' bake", e);
            }
            JOB[p] = null;
        }
        if ((TEX[p] == 0 || TEX_KEY[p] != key) && JOB[p] == null) {
            final int panel = p;
            final double kk = k;
            JOB_KEY[p] = key;
            JOB[p] = HiResSVA.submit(new Callable<BufferedImage>() {
                @Override
                public BufferedImage call() {
                    return LinerPaintSVA.bakePanel(panel, kk, PanelViewMath.MIN_TEXT_PX);
                }
            });
        }
        return TEX[p] > 0 && TEX_KEY[p] == key ? TEX[p] : TEX[p] > 0 ? -TEX[p] : 0;
    }

    /** Frees the panels' textures (the screen closed). */
    static void freeAll() {
        for (int p = 0; p < TEX.length; p++) {
            if (TEX[p] > 0) {
                GL11.glDeleteTextures(TEX[p]);
            }
            TEX[p] = 0;
            if (JOB[p] != null) {
                JOB[p].cancel(false);
                JOB[p] = null;
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        area();
        int p = panel(), t = tabIndex();
        view(p, t);
        double k = K[t], ox = OX[t], oy = OY[t];
        LinerSystems L = v.liner();
        float pw = LinerControls.PANEL_W[p], ph = LinerControls.PANEL_H[p];
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_SCISSOR_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);                          // the cockpit's drawing code assumes no culling (the pop restores it)
        GL11.glPushMatrix();
        GL11.glScalef(1F / sf, 1F / sf, 1F);                       // device px from here
        scissor(ax, ay, aw, ah);
        int tex = panelTex(p, k);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        if (tex > 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);            // the panel's own bake: a texel on a pixel
            quad((float) ox, (float) oy, TEX_W[p], TEX_H[p], 0, 0, 1, 1);
        } else if (tex < 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, -tex);           // the last bake, stretched while the new one bakes
            quad((float) ox, (float) oy, (float) (pw * k), (float) (ph * k), 0, 0, 1, 1);
        } else {
            int[] atlas = LinerTexSVA.atlas();
            if (atlas != null && atlas[0] > 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas[0]);
                quad((float) ox, (float) oy, (float) (pw * k), (float) (ph * k), LinerTexMath.u(p, 0), LinerTexMath.v(p, 0), LinerTexMath.u(p, pw),
                        LinerTexMath.v(p, ph));
            } else {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                LinerGfxSVA.col(0x5A6A79);
                quad((float) ox, (float) oy, (float) (pw * k), (float) (ph * k), 0, 0, 1, 1);
            }
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        if (L != null) {
            GL11.glPushMatrix();
            GL11.glTranslated(ox, oy, 0);
            GL11.glScaled(k, k, 1);
            float lift = LinerGfxSVA.lift;
            LinerGfxSVA.lift = 0F;
            LinerGfxSVA.bright = 1F;
            LinerGfxSVA.unitPx = (float) k;
            LinerGfxSVA.minTextPx = (float) PanelViewMath.MIN_TEXT_PX;
            HiResSVA.beginFrame();
            try {
                if (p == LinerControls.MP) {
                    displays(L, pt, ox, oy, k);
                    scissor(ax, ay, aw, ah);
                }
                LinerCockpitSVA.drawPanel(p, -1, v, L, v.powered());
                levers(p, L);
            } finally {
                LinerGfxSVA.unitPx = 0F;
                LinerGfxSVA.minTextPx = 0F;
                LinerGfxSVA.lift = lift;
                LinerGfxSVA.bright = 1F;
            }
            GL11.glPopMatrix();
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
        ilsButtons(L);
        if (panel() == LinerControls.GS && L != null) {
            com.sva.vehicle.LinerIls.Station st = L.afs.ils;
            String s = st == null ? Lang.tr("sva.liner.gui.ilsNone") : Lang.tr("sva.liner.gui.ilsTuned", st.ident,
                    com.sva.vehicle.LinerIls.freqText(st.freq).replace('.', ','), String.format("%03d", Integer.valueOf((int) Math.round(st.course) % 360)));
            drawString(fontRendererObj, s, 10, height - 64, 0xE6A0E6);
        }
        drawString(fontRendererObj, Math.round(k * 100) + " %", width - 112, 11, 0xA0A8B0);
        super.drawScreen(mx, my, pt);
        LinerControls.Ctl c = hit(mouseX(), mouseY());
        LinerCockpitSVA.hovered = c == null ? -1 : c.index;
        if (c != null && L != null && !dragging) {
            func_146283_a(java.util.Arrays.asList(LinerClickSVA.tooltip(c, L)), mx, my);
        }
        if (!LinerClickSVA.pilotSeat(mc.thePlayer, v)) {
            drawCenteredString(fontRendererObj, Lang.tr("sva.liner.gui.readOnly"), width / 2, height - 12, 0xF0B040);
        } else {
            drawCenteredString(fontRendererObj, Lang.tr("sva.liner.gui.panHint"), width / 2, height - 12, 0x8890A0);
        }
    }

    /** A textured quad (device px). */
    private static void quad(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u0, v1);
        GL11.glVertex2f(x, y + h);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(u1, v0);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }

    /** A scissor box (device px, y down) - GL's from the bottom. */
    private void scissor(double x, double y, double w, double h) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), x1 = (int) Math.ceil(x + w), y1 = (int) Math.ceil(y + h);
        GL11.glScissor(x0, mc.displayHeight - y1, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    /**
     * Wave 7: the main panel's six DUs and the ISIS live - the 3D cockpit's own code (LinerDisplaysSVA.draw: dark without
     * power, the brightness knobs), each clipped to its glass (in the panel's frame: the DU's units are the panel's).
     */
    private void displays(LinerSystems L, float pt, double ox, double oy, double k) {
        // the display code winds its fans / strips for the cockpit (which draws with culling off): the GUI's culling
        // would drop the attitude's sky / earth, the arcs and the roses - off here too, restored after
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);
        try {
            displaysUnculled(L, pt, ox, oy, k);
        } finally {
            if (cull) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            }
        }
    }

    private void displaysUnculled(LinerSystems L, float pt, double ox, double oy, double k) {
        for (int du = 0; du < LinerSystems.DUS; du++) {
            float[] s = LinerPaintSVA.SCREENS[du];
            double[] a = PanelViewMath.toDevice(s[0], s[1], ox, oy, k), b = PanelViewMath.toDevice(s[2], s[3], ox, oy, k);
            double cx0 = Math.max(a[0], ax), cy0 = Math.max(a[1], ay), cx1 = Math.min(b[0], ax + aw), cy1 = Math.min(b[1], ay + ah);
            if (cx1 <= cx0 || cy1 <= cy0) {
                continue;
            }
            scissor(cx0, cy0, cx1 - cx0, cy1 - cy0);
            GL11.glPushMatrix();
            GL11.glTranslatef(s[0], s[1], 0F);
            LinerGfxSVA.resetLayer();
            LinerGfxSVA.bright = LinerCockpitSVA.brightness(L, du);
            try {
                LinerDisplaysSVA.draw(du, v, L, s[2] - s[0], s[3] - s[1], pt);
            } catch (Throwable e) {
                // a display's failure leaves its glass dark
            }
            GL11.glPopMatrix();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        LinerGfxSVA.bright = 1F;
        LinerGfxSVA.resetLayer();
    }

    /** The levers' positions on the flat panels (the cockpit draws them in 3D). */
    private void levers(int p, LinerSystems L) {
        if (p == LinerControls.PED) {
            float ty = LinerCockpitSVA.leverY(L);                   // wave 4: the detents and the reverse
            for (float x : new float[] {330, 430}) {
                LinerGfxSVA.rect(x - 46, ty - 18, x + 46, ty + 18, 0x2E3034);
                LinerGfxSVA.rect(x - 42, ty - 15, x + 42, ty - 6, 0x5A5C62);
            }
            float fy = LinerPaintSVA.FLAP_Y[Math.max(0, Math.min(4, L.at(LinerControls.FLAPS)))];
            LinerGfxSVA.rect(615, fy - 16, 700, fy + 16, 0xD2D4D8);
            float[] sb = {642, 678, 780, 890};
            float sy = sb[Math.max(0, Math.min(3, L.at(LinerControls.SPD_BRK)))];
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

    // ---------------------------------------------------------------- input (device px)

    private LinerControls.Ctl hit(double dx, double dy) {
        area();
        int t = tabIndex(), p = panel();
        view(p, t);
        if (dx < ax || dy < ay || dx > ax + aw || dy > ay + ah) {
            return null;
        }
        double[] q = PanelViewMath.toPanel(dx, dy, OX[t], OY[t], K[t]);
        return LinerControls.hit(p, (float) q[0], (float) q[1]);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        super.mouseClicked(mx, my, btn);
        if (my < 28) {
            return;                                                 // the tab bar
        }
        LinerControls.Ctl c = hit(mouseX(), mouseY());
        if (c != null && (btn == 0 || btn == 1) && LinerClickSVA.pilotSeat(mc.thePlayer, v)) {
            LinerClickSVA.send(v, c, btn == 0 ? LinerControls.A_PRIMARY : LinerControls.A_SECONDARY);
        } else if (c == null && (btn == 0 || btn == 1)) {
            dragging = true;                                        // an empty spot: pan
            dragX = mouseX();
            dragY = mouseY();
        }
    }

    @Override
    protected void mouseClickMove(int mx, int my, int btn, long since) {
        if (!dragging) {
            return;
        }
        int t = tabIndex();
        double x = mouseX(), y = mouseY();
        OX[t] += x - dragX;
        OY[t] += y - dragY;
        dragX = x;
        dragY = y;
        area();
        view(panel(), t);
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        super.mouseMovedOrUp(mx, my, which);
        if (which >= 0) {
            dragging = false;
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int w = Mouse.getEventDWheel();
        if (w != 0) {
            double dx = Mouse.getEventX(), dy = mc.displayHeight - Mouse.getEventY() - 1;
            LinerControls.Ctl c = hit(dx, dy);
            boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
            int a = c == null || ctrl ? -1 : LinerClickSVA.wheelAction(c, w, isShiftKeyDown());
            if (a >= 0 && LinerClickSVA.pilotSeat(mc.thePlayer, v)) {
                LinerClickSVA.send(v, c, a);
            } else if (a < 0) {
                zoomBy(w > 0 ? 1 : -1, dx, dy);                     // wave 7: the wheel off the controls (or with Ctrl) zooms there
            }
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        super.keyTyped(ch, key);
        if (ch == '+' || ch == '=' || key == Keyboard.KEY_ADD) {
            area();
            zoomBy(1, ax + aw / 2, ay + ah / 2);
        } else if (ch == '-' || key == Keyboard.KEY_SUBTRACT) {
            area();
            zoomBy(-1, ax + aw / 2, ay + ah / 2);
        }
    }

    @Override
    public void onGuiClosed() {
        LinerCockpitSVA.hovered = -1;
        freeAll();
    }

    @Override
    public void updateScreen() {
        if (v.isDead || HudVehicleSVA.vehicleOf(mc.thePlayer) != v) {
            mc.displayGuiScreen(null);
        }
    }
}
