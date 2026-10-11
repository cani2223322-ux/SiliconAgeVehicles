package com.sva.client;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.RunwayMath;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/**
 * Aircraft stage C (★17, mock-up docs/mockups/veh_air2_5_cockpit.png): «Панель автопилота» - the glare-shield panel as a
 * screen (the «Техника» menu's row, key I by default): six groups АП / КУРС / ВЫС / СКОР / ВЕРТ / ЗАХВ, each a window with
 * its set value over a button with a lamp; КУРС..ВЕРТ have their knob as - / + buttons (Shift: x5) - the mouse wheel over
 * the screen (and in flight while the autopilot holds) turns the last knob touched. ЗАХВ's line says which runway it
 * would capture. The state is the driver's client's (CruiseClientSVA) and goes to the server with the Input packet; a
 * passenger sees the synced panel, read-only. Does not pause the game (you fly on with it open).
 */
public class GuiAutopilotSVA extends GuiScreen {

    private static final int W = 324, H = 132, COL = 52;
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, LINE = 0xFF3A4150, WIN = 0xFF07080A, GOLD = 0xF2A040, TEXT = 0xD8DCE4,
            MUTED = 0x8A93A3, GREEN = 0x62E06A, AMBER = 0xFFAC28, DIM_AMBER = 0x7A5A20, CYAN = 0x5EC8F0;
    private static final int B_MODE = 10, B_MINUS = 20, B_PLUS = 30;

    private EntityVehicleSVA vehicle;
    private boolean driver;
    private int left, top;

    @Override
    public void initGui() {
        vehicle = mc.thePlayer == null ? null : HudVehicleSVA.vehicleOf(mc.thePlayer);
        if (vehicle == null || vehicle.isDead || vehicle.cruiseKind() == CruiseMath.NONE && !planeType(vehicle)) {
            mc.displayGuiScreen(null);
            return;
        }
        driver = mc.thePlayer.ridingEntity == vehicle;
        if (driver) {
            CruiseClientSVA.prepare(vehicle);
        }
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();
        for (int g = 0; g < AutopilotMath.GROUPS; g++) {
            int x = colX(g);
            GuiCargoSVA.Dark b = new GuiCargoSVA.Dark(B_MODE + g, x + 4, top + 54, COL - 8, 16, Lang.tr("sva.ap.group." + AutopilotMath.GROUP_KEYS[g]), null);
            b.enabled = driver;
            buttonList.add(b);
            if (g >= AutopilotMath.G_HDG && g <= AutopilotMath.G_VS) {
                GuiCargoSVA.Dark m = new GuiCargoSVA.Dark(B_MINUS + g, x + 4, top + 74, (COL - 10) / 2, 13, "-", null);
                GuiCargoSVA.Dark p = new GuiCargoSVA.Dark(B_PLUS + g, x + 6 + (COL - 10) / 2, top + 74, (COL - 10) / 2, 13, "+", null);
                m.enabled = p.enabled = driver;
                buttonList.add(m);
                buttonList.add(p);
            }
        }
    }

    private static boolean planeType(EntityVehicleSVA v) {
        return CruiseMath.kind(v.getVehicleType().movement, true) == CruiseMath.AUTOPILOT;
    }

    private int colX(int g) {
        return left + 6 + g * COL;
    }

    private AutopilotMath.Panel panel() {
        return driver ? CruiseClientSVA.panel(vehicle) : vehicle.apPanel();
    }

    @Override
    public void updateScreen() {
        if (mc.thePlayer == null || HudVehicleSVA.vehicleOf(mc.thePlayer) != vehicle || vehicle.isDead) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (!driver) {
            return;
        }
        int steps = GuiScreen.isShiftKeyDown() ? 5 : 1;
        if (b.id == B_MODE + AutopilotMath.G_AP) {
            CruiseClientSVA.apButton(vehicle);
        } else if (b.id >= B_MODE && b.id < B_MODE + AutopilotMath.GROUPS) {
            CruiseClientSVA.modeButton(vehicle, b.id - B_MODE);
        } else if (b.id >= B_MINUS && b.id < B_MINUS + AutopilotMath.GROUPS) {
            CruiseClientSVA.adjust(vehicle, b.id - B_MINUS, -steps);
        } else if (b.id >= B_PLUS && b.id < B_PLUS + AutopilotMath.GROUPS) {
            CruiseClientSVA.adjust(vehicle, b.id - B_PLUS, steps);
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0 && driver && vehicle != null) {
            CruiseClientSVA.adjust(vehicle, CruiseClientSVA.knob(), d > 0 ? 1 : -1);
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        int menu = VehicleKeyBindsSVA.KEY_MENU.getKeyCode();
        if (key == Keyboard.KEY_ESCAPE || key != 0 && key == menu || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        if (vehicle == null) {
            return;
        }
        TextFitSC.beginFrame();
        drawRect(left - 1, top - 1, left + W + 1, top + H + 1, EDGE);
        drawRect(left, top, left + W, top + H, BG);
        drawRect(left + 3, top + 15, left + W - 3, top + 16, 0xFFE08A30);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ap.title"), left + 6, top + 4, W - 12 - 60, GOLD, false, 0, 0);
        String role = Lang.tr(driver ? "sva.vmenu.driver" : "sva.vmenu.passenger");
        fontRendererObj.drawString(role, left + W - 6 - fontRendererObj.getStringWidth(role), top + 4, driver ? CYAN : MUTED);
        AutopilotMath.Panel p = panel();
        boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
        for (int g = 0; g < AutopilotMath.GROUPS; g++) {
            int x = colX(g), wx0 = x + 3, wx1 = x + COL - 3, wy0 = top + 22, wy1 = top + 46;
            drawRect(wx0 - 1, wy0 - 1, wx1 + 1, wy1 + 1, LINE);
            drawRect(wx0, wy0, wx1, wy1, WIN);
            boolean held = g == AutopilotMath.G_AP ? p.on : p.mode(AutopilotMath.bit(g));
            String v = value(g, p);
            int col = g == AutopilotMath.G_AP ? (p.on ? GREEN : MUTED) : g == AutopilotMath.G_CAP ? (held && p.runway ? (p.aligned || blink ? GREEN : 0x2E6A34) : MUTED)
                    : held ? AMBER : DIM_AMBER;
            GL11.glPushMatrix();
            int tw = fontRendererObj.getStringWidth(v);
            float k = Math.min(1.6F, (wx1 - wx0 - 4) / (float) Math.max(1, tw));
            GL11.glTranslatef((wx0 + wx1) / 2F - tw * k / 2, (wy0 + wy1) / 2F - 4 * k, 0);
            GL11.glScalef(k, k, 1);
            fontRendererObj.drawString(v, 0, 0, col);
            GL11.glPopMatrix();
            // the button's lamp
            int lx = x + COL / 2 - 8;
            drawRect(lx, top + 49, lx + 16, top + 52, held ? 0xFF46E07A : 0xFF1E2A22);
            if (g == CruiseClientSVA.knob() && g >= AutopilotMath.G_HDG && g <= AutopilotMath.G_VS) {
                drawRect(x + 4, top + 89, x + COL - 4, top + 90, 0xFFFFD24D);    // the wheel's knob
            }
        }
        // ЗАХВ's runway line, the hints
        RunwayMath.Runway r = vehicle.runway();
        String rw;
        if (r == null) {
            rw = Lang.tr("sva.ap.rwy.none", Integer.valueOf(RunwayMath.SCAN));
        } else {
            AutopilotMath.Capture c = vehicle.capture();
            rw = Lang.tr("sva.ap.rwy.line", String.format("%03d", Integer.valueOf((int) Math.round(c.course) % 360)), AutopilotMath.km(Math.max(0, -c.along)),
                    String.valueOf(r.width), String.valueOf((int) r.length()));
            if (c.papi != null) {
                // stage D1: the PAPI serving that end - its angle and the white units seen now
                rw += " | " + Lang.tr("sva.ap.papi", Integer.valueOf(c.papi.nominal), c.whites < 0 ? "-" : String.valueOf(c.whites));
            }
        }
        TextFitSC.draw(fontRendererObj, rw, left + 6, top + 95, W - 12, r == null ? MUTED : GREEN, false, 0, 0);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ap.hint.wheel", Lang.tr("sva.ap.group." + AutopilotMath.GROUP_KEYS[CruiseClientSVA.knob()])),
                left + 6, top + 107, W - 12, MUTED, false, 0, 0);
        TextFitSC.draw(fontRendererObj, Lang.tr(driver ? "sva.ap.hint.keys" : "sva.vmenu.driverOnly", CruiseClientSVA.keyName()), left + 6, top + 118,
                W - 12, MUTED, false, 0, 0);
        for (Object o : buttonList) {
            GuiCargoSVA.Dark b = (GuiCargoSVA.Dark) o;
            if (b.id >= B_MODE && b.id < B_MODE + AutopilotMath.GROUPS) {
                int g = b.id - B_MODE;
                b.active = g == AutopilotMath.G_AP ? p.on : p.mode(AutopilotMath.bit(g));
                b.activeColor = 0xFF62E06A;
            }
        }
        super.drawScreen(mx, my, pt);
        for (Object o : buttonList) {
            GuiCargoSVA.Dark b = (GuiCargoSVA.Dark) o;
            if (b.id >= B_MODE && b.id < B_MODE + AutopilotMath.GROUPS) {
                TextFitSC.hover(b.xPosition, b.yPosition, b.width, b.height, Lang.tr("sva.ap.tip." + AutopilotMath.GROUP_KEYS[b.id - B_MODE]));
            }
        }
        java.util.List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty()) {
            java.util.List<String> wrapped = new java.util.ArrayList<String>();
            for (String line : tip) {
                wrapped.addAll(fontRendererObj.listFormattedStringToWidth(line, 220));
            }
            drawHoveringText(wrapped, mx, my, fontRendererObj);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** A group's window text: ВКЛ / ОТКЛ, the heading, height, speed, vertical speed, ВПП / ----. */
    static String value(int g, AutopilotMath.Panel p) {
        switch (g) {
            case AutopilotMath.G_AP:
                return Lang.tr(p.on ? "sva.ap.on" : "sva.ap.off");
            case AutopilotMath.G_HDG:
                return String.format("%03d", Integer.valueOf(p.hdg));
            case AutopilotMath.G_ALT:
                return String.format("%04d", Integer.valueOf(p.alt));
            case AutopilotMath.G_SPD:
                return String.format("%03d", Integer.valueOf(p.kmh));
            case AutopilotMath.G_VS:
                return CruiseClientSVA.vsText(p.vs);
            default:
                return p.mode(AutopilotMath.M_CAP) && p.runway ? phaseText(p) : "----";
        }
    }

    /** Stage D1: ЗАХВ's window - ВПП, then with a PAPI ГЛИС and ВЫРАВН (AutopilotMath.PH_*). */
    static String phaseText(AutopilotMath.Panel p) {
        int ph = Math.max(0, Math.min(AutopilotMath.PHASE_KEYS.length - 1, p.phase));
        return Lang.tr("sva.ap.phase." + AutopilotMath.PHASE_KEYS[ph]);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
