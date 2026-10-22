package com.sva.client;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sva.airdefense.XpdrMath;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetADSVA;
import com.sva.net.NetSVA;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Air-defence wave (mock-up veh_ad_1_transponder, «Панель ответчика»): the transponder of the vehicle the player drives -
 * the four octal digits (the buttons under each: +1 / -1, or type 0..7 - the cursor moves on), the mode switch ВЫКЛ /
 * ДЕЖ / A / C / S, «Ключ дня» (the code from the code card in the inventory), IDENT, the quick codes 7000 / 7600 / 7700.
 * Opened from the «Техника» menu's «Ответчик» row (the liner: also a click on the pedestal's transponder, either pilot);
 * each change goes to the server at once (NetADSVA XPDR_SET).
 * <p>
 * Audit 2026-10-22 (bug 12): a change sends only the field it touched ("Ch": XpdrMath.CH_*), and the screen keeps its own
 * value only for the field it just sent - the others follow the server. It used to send all three from a copy up to 19
 * ticks old: a mode pressed right after «Ключ дня» put the old code back and switched the day key off; a tower's code or
 * a 7600 set from the command post was reverted the same way.
 */
public class GuiTransponderSVA extends GuiScreen {

    private static final int W = 230, H = 172, BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, LINE = 0xFF3A4150, GOLD = 0xF2A040, TEXT = 0xD8DCE4,
            MUTED = 0x8A93A3, AMBER = 0xF0B040, RED = 0xF06060, GREEN = 0x62E06A;
    private static final int B_UP = 10, B_DOWN = 20, B_MODE = 30, B_KEY = 40, B_IDENT = 41, B_VFR = 42, B_NORDO = 43, B_EMERG = 44;

    private final EntityVehicleSVA vehicle;
    private int code, mode, cursor;
    private boolean dayKey;
    private int left, top;
    /** When each field (mode, code, day key) was last sent (the player's ticks; it keeps its own value that long). */
    private final long[] sentAt = {-100, -100, -100};
    /** Ticks a sent field waits for the server's copy (two of its 10-tick rounds). */
    private static final int HOLD = 20;

    public GuiTransponderSVA(EntityVehicleSVA v) {
        vehicle = v;
        code = AirDefenseClientSVA.xpdrCode(v);
        mode = AirDefenseClientSVA.xpdrMode(v);
        dayKey = AirDefenseClientSVA.xpdrDayKey(v);
    }

    @Override
    public void initGui() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();
        for (int i = 0; i < 4; i++) {
            int x = left + 14 + i * 34;
            buttonList.add(new GuiCargoSVA.Dark(B_UP + i, x, top + 66, 15, 12, "+", null));
            buttonList.add(new GuiCargoSVA.Dark(B_DOWN + i, x + 16, top + 66, 15, 12, "-", null));
        }
        for (int m = 0; m < XpdrMath.MODES; m++) {
            buttonList.add(new GuiCargoSVA.Dark(B_MODE + m, left + 154 + (m % 2) * 36, top + 22 + m / 2 * 16, 34, 14,
                    Lang.tr("sva.xpdr.mode." + XpdrMath.MODE_KEYS[m]), null));
        }
        buttonList.add(new GuiCargoSVA.Dark(B_KEY, left + 10, top + 86, 104, 14, Lang.tr("sva.ad.xpdr.dayKeyBtn"), null));
        buttonList.add(new GuiCargoSVA.Dark(B_IDENT, left + 118, top + 86, 102, 14, "IDENT", null));
        buttonList.add(new GuiCargoSVA.Dark(B_VFR, left + 10, top + 104, 66, 14, "7000", null));
        buttonList.add(new GuiCargoSVA.Dark(B_NORDO, left + 82, top + 104, 66, 14, "7600", null));
        buttonList.add(new GuiCargoSVA.Dark(B_EMERG, left + 154, top + 104, 66, 14, "7700", null));
    }

    /** Who may work this vehicle's transponder here: its driver; the liner's - either pilot (the server's xpdrOperator). */
    static boolean operator(net.minecraft.client.Minecraft mc, EntityVehicleSVA v) {
        if (mc.thePlayer == null || v == null) {
            return false;
        }
        return mc.thePlayer.ridingEntity == v || v.getVehicleType() == com.sva.vehicle.VehicleType.LINER && LinerClickSVA.pilotSeat(mc.thePlayer, v);
    }

    @Override
    public void updateScreen() {
        if (vehicle == null || vehicle.isDead || !operator(mc, vehicle)) {
            mc.displayGuiScreen(null);
            return;
        }
        // the server's copy wins for every field not sent just now (the code card, a tower's code, 7600 from the post)
        long now = mc.thePlayer.ticksExisted;
        if (now - sentAt[0] > HOLD) {
            mode = AirDefenseClientSVA.xpdrMode(vehicle);
        }
        if (now - sentAt[1] > HOLD) {
            code = AirDefenseClientSVA.xpdrCode(vehicle);
        }
        if (now - sentAt[2] > HOLD && now - sentAt[1] > HOLD) {
            dayKey = AirDefenseClientSVA.xpdrDayKey(vehicle);
        }
    }

    /** Sends the fields in `changed` (XpdrMath.CH_*) - only those; the server keeps the rest as it has them. */
    private void send(int changed) {
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("Ch", changed);
        t.setInteger("M", mode);
        t.setInteger("C", code);
        t.setBoolean("D", dayKey);
        NetADSVA.cmd(NetADSVA.XPDR_SET, 0, 0, 0, vehicle.getEntityId(), t);
        long now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
        for (int i = 0; i < 3; i++) {
            if ((changed & 1 << i) != 0) {
                sentAt[i] = now;
            }
        }
    }

    /** A code typed / stepped by hand: the card no longer sets it (the server switches «Ключ дня» off with it). */
    private void handCode(int c) {
        code = c;
        dayKey = false;
        send(XpdrMath.CH_CODE);
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id >= B_UP && b.id < B_UP + 4) {
            cursor = b.id - B_UP;
            handCode(XpdrMath.step(code, cursor, isShiftKeyDown() ? -1 : 1));
        } else if (b.id >= B_DOWN && b.id < B_DOWN + 4) {
            cursor = b.id - B_DOWN;
            handCode(XpdrMath.step(code, cursor, -1));
        } else if (b.id >= B_MODE && b.id < B_MODE + XpdrMath.MODES) {
            mode = b.id - B_MODE;
            send(XpdrMath.CH_MODE);
        } else if (b.id == B_KEY) {
            dayKey = !dayKey;
            send(XpdrMath.CH_KEY);
        } else if (b.id == B_IDENT) {
            NetSVA.CHANNEL.sendToServer(new NetSVA.Action(vehicle.getEntityId(), com.sva.entity.EntityVehicleSVA.A_IDENT));
        } else if (b.id == B_VFR) {
            handCode(XpdrMath.VFR);
        } else if (b.id == B_NORDO) {
            handCode(XpdrMath.NO_RADIO);
        } else if (b.id == B_EMERG) {
            handCode(XpdrMath.EMERGENCY);
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (c >= '0' && c <= '7') {
            int d = c - '0';
            handCode(XpdrMath.step(code, cursor, d - XpdrMath.digit(code, cursor)));
            cursor = (cursor + 1) % 4;
            return;
        }
        if (key == Keyboard.KEY_LEFT) {
            cursor = (cursor + 3) % 4;
        } else if (key == Keyboard.KEY_RIGHT) {
            cursor = (cursor + 1) % 4;
        } else if (key == Keyboard.KEY_ESCAPE || key == VehicleKeyBindsSVA.KEY_MENU.getKeyCode() || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(new GuiVehicleSVA());
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        TextFitSC.beginFrame();
        drawDefaultBackground();
        drawRect(left - 1, top - 1, left + W + 1, top + H + 1, EDGE);
        drawRect(left, top, left + W, top + H, BG);
        drawRect(left + 1, top + 1, left + W - 1, top + 2, LINE);
        drawRect(left + 3, top + 15, left + W - 3, top + 16, 0xFFE08A30);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.xpdr.title", com.sva.vehicle.SkinMath.boardText(vehicle.getBoardNumber())), left + 6, top + 4, W - 12,
                GOLD, false, 0, 0);
        // the code window: four amber digits, the cursor under one
        drawRect(left + 10, top + 22, left + 146, top + 62, 0xFF050607);
        boolean on = XpdrMath.replies(mode);
        boolean emerg = on && code == XpdrMath.EMERGENCY;
        for (int i = 0; i < 4; i++) {
            String d = String.valueOf(XpdrMath.digit(code, i));
            int x = left + 14 + i * 34;
            GL11.glPushMatrix();
            GL11.glTranslatef(x + 6, top + 27, 0F);
            GL11.glScalef(3F, 3F, 1F);
            fontRendererObj.drawString(d, 0, 0, emerg ? RED : on ? AMBER : 0x5A4A2A);
            GL11.glPopMatrix();
            if (i == cursor) {
                drawRect(x + 2, top + 56, x + 29, top + 58, 0xFF5EC8F0);
            }
        }
        // the lamps: ОТВЕТ (green on an interrogation), КЛЮЧ ДНЯ (amber), IDENT (cyan)
        lamp(left + 10, top + 122, AirDefenseClientSVA.xpdrReply(vehicle), GREEN, Lang.tr("sva.xpdr.reply"));
        lamp(left + 82, top + 122, AirDefenseClientSVA.xpdrKeyLamp(vehicle), AMBER, Lang.tr("sva.ad.xpdr.dayKey"));
        for (Object o : buttonList) {
            GuiCargoSVA.Dark b = (GuiCargoSVA.Dark) o;
            b.active = b.id >= B_MODE && b.id < B_MODE + XpdrMath.MODES && b.id - B_MODE == mode || b.id == B_KEY && dayKey
                    || b.id == B_IDENT && AirDefenseClientSVA.xpdrIdent(vehicle) || b.id == B_EMERG && code == XpdrMath.EMERGENCY;
            b.activeColor = b.id == B_EMERG ? RED : 0;
        }
        String hint = Lang.tr(XpdrMath.replies(mode) ? "sva.ad.xpdr.hint" : "sva.ad.xpdr.hintSilent");
        java.util.List<String> lines = fontRendererObj.listFormattedStringToWidth(hint, W - 16);
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            fontRendererObj.drawString(lines.get(i), left + 8, top + 136 + i * 10, MUTED);
        }
        super.drawScreen(mx, my, pt);
        TextFitSC.hover(left + 10, top + 22, 136, 40, Lang.tr("sva.ad.xpdr.codeTip"));
        java.util.List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty()) {
            java.util.List<String> wrapped = new java.util.ArrayList<String>();
            for (String line : tip) {
                wrapped.addAll(fontRendererObj.listFormattedStringToWidth(line, 200));
            }
            drawHoveringText(wrapped, mx, my, fontRendererObj);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void lamp(int x, int y, boolean on, int colour, String label) {
        drawRect(x, y, x + 8, y + 8, 0xFF000000);
        drawRect(x + 1, y + 1, x + 7, y + 7, on ? 0xFF000000 | colour : 0xFF1E2228);
        TextFitSC.draw(fontRendererObj, label, x + 11, y, 60, on ? colour : MUTED, false, 0, 0);
    }
}
