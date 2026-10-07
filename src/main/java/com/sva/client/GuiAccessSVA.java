package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.AccessMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

/**
 * Wave 2, ★10: the vehicle access screen (mock-up docs/mockups/veh_more_4_keycard.png, in the addon's dark style),
 * opened by Shift+RMB on your own vehicle with its bound key card. The rows: the owner (everything, fixed), the players
 * on the list, «Все остальные»; the columns Водить / Пассажир / Багажник / Ремонт are click-to-toggle boxes. A name field
 * with «+ Добавить», «Удалить» and «Выдать ключ-карту» (a blank card from your inventory becomes that player's card),
 * the lock modes Открыта / Друзья / Владелец and «Сменить замок» (old cards stop working). Every change goes to the
 * server (NetSVA.AccessEdit), which checks it and sends the list back (NetSVA.AccessInfo -> show()).
 */
public class GuiAccessSVA extends GuiScreen {

    private static final int W = 320, H = 212, ROW_Y = 34, ROW_H = 12, VISIBLE = 8;
    private static final int[] COL_X = {136, 180, 224, 268};
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, GOLD = 0xF2A040, CYAN = 0x5EC8F0, TEXT = 0xD8DCE4,
            MUTED = 0x8A93A3, GREEN = 0x62E06A, RED = 0xF06060;
    private static final int B_ADD = 0, B_REMOVE = 1, B_ISSUE = 2, B_OPEN = 3, B_FRIENDS = 4, B_OWNER = 5, B_RELOCK = 6;

    private NetSVA.AccessInfo info;
    private GuiTextField nameField;
    private int left, top, scroll;
    /** The selected player row's name ("" none). */
    private String selected = "";

    private GuiAccessSVA(NetSVA.AccessInfo info) {
        this.info = info;
    }

    /** Opens the screen, or refreshes it when it is already open for that vehicle (client thread). */
    public static void show(NetSVA.AccessInfo info) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GuiAccessSVA && ((GuiAccessSVA) mc.currentScreen).info.entity == info.entity) {
            GuiAccessSVA g = (GuiAccessSVA) mc.currentScreen;
            g.info = info;
            g.clampScroll();
            return;
        }
        mc.displayGuiScreen(new GuiAccessSVA(info));
    }

    @Override
    public void initGui() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        Keyboard.enableRepeatEvents(true);
        nameField = new GuiTextField(fontRendererObj, left + 8, top + 147, 108, 14);
        nameField.setMaxStringLength(AccessMath.MAX_NAME);
        nameField.setFocused(true);
        buttonList.clear();
        buttonList.add(new TextFitSC.Button(B_ADD, left + 120, top + 146, 64, 16, Lang.tr("sva.access.add")));
        buttonList.add(new TextFitSC.Button(B_REMOVE, left + 188, top + 146, 56, 16, Lang.tr("sva.access.remove")));
        buttonList.add(new TextFitSC.Button(B_ISSUE, left + 248, top + 146, 66, 16, Lang.tr("sva.access.issue")));
        buttonList.add(new TextFitSC.Button(B_OPEN, left + 52, top + 166, 62, 16, Lang.tr("sva.access.mode.0")));
        buttonList.add(new TextFitSC.Button(B_FRIENDS, left + 118, top + 166, 62, 16, Lang.tr("sva.access.mode.1")));
        buttonList.add(new TextFitSC.Button(B_OWNER, left + 184, top + 166, 60, 16, Lang.tr("sva.access.mode.2")));
        buttonList.add(new TextFitSC.Button(B_RELOCK, left + 248, top + 166, 66, 16, Lang.tr("sva.access.relock")));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void send(int op, String name, int value) {
        NetSVA.CHANNEL.sendToServer(new NetSVA.AccessEdit(info.entity, op, name, value));
    }

    /** Rows: -1 the owner, 0..n-1 the list, n «Все остальные». */
    private int rowCount() {
        return info.names.size() + 2;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, rowCount() - VISIBLE));
        if (selected.length() > 0 && !info.names.contains(selected)) {
            selected = "";
        }
    }

    @Override
    public void updateScreen() {
        nameField.updateCursorCounter();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !(mc.theWorld.getEntityByID(info.entity) instanceof EntityVehicleSVA)
                || mc.thePlayer.getDistanceSqToEntity(mc.theWorld.getEntityByID(info.entity)) > 100) {
            mc.displayGuiScreen(null);                                  // walked away / the vehicle is gone
            return;
        }
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.id == B_ADD) {
                b.enabled = AccessMath.validName(nameField.getText().trim()) && info.names.size() < AccessMath.MAX_FRIENDS;
            } else if (b.id == B_REMOVE || b.id == B_ISSUE) {
                b.enabled = target().length() > 0;
            } else if (b.id >= B_OPEN && b.id <= B_OWNER) {
                b.enabled = info.mode != b.id - B_OPEN;
            }
        }
    }

    /** The player the buttons act on: the selected row, else a listed name typed in the field. */
    private String target() {
        if (selected.length() > 0) {
            return selected;
        }
        String t = nameField.getText().trim();
        for (String n : info.names) {
            if (n.equalsIgnoreCase(t)) {
                return n;
            }
        }
        return "";
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == B_ADD) {
            send(EntityVehicleSVA.AE_ADD, nameField.getText().trim(), 0);
            selected = nameField.getText().trim();
            nameField.setText("");
        } else if (b.id == B_REMOVE) {
            send(EntityVehicleSVA.AE_REMOVE, target(), 0);
            selected = "";
        } else if (b.id == B_ISSUE) {
            send(EntityVehicleSVA.AE_ISSUE, target(), 0);
        } else if (b.id >= B_OPEN && b.id <= B_OWNER) {
            send(EntityVehicleSVA.AE_MODE, "", b.id - B_OPEN);
        } else if (b.id == B_RELOCK) {
            send(EntityVehicleSVA.AE_RELOCK, "", 0);
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (nameField.textboxKeyTyped(c, key)) {
            return;
        }
        if (key == Keyboard.KEY_RETURN && AccessMath.validName(nameField.getText().trim())) {
            actionPerformed((GuiButton) buttonList.get(B_ADD));
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) {
            scroll += d > 0 ? -1 : 1;
            clampScroll();
        }
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        nameField.mouseClicked(mx, my, button);
        for (int i = 0; i < VISIBLE; i++) {
            int row = scroll + i - 1;
            if (row >= info.names.size() + 1) {
                break;
            }
            int ry = top + ROW_Y + i * ROW_H;
            if (my < ry || my >= ry + ROW_H) {
                continue;
            }
            if (row < 0) {
                return;                                             // the owner: fixed
            }
            boolean others = row == info.names.size();
            for (int c = 0; c < COL_X.length; c++) {
                if (mx >= left + COL_X[c] - 2 && mx < left + COL_X[c] + 12) {
                    int bit = 1 << c;
                    int cur = others ? info.others : info.rights.get(row).intValue();
                    int next = AccessMath.norm(cur ^ bit);
                    if (bit == AccessMath.PASSENGER && (cur & AccessMath.PASSENGER) != 0) {
                        next = cur & ~(AccessMath.PASSENGER | AccessMath.DRIVE);   // no seat: no driving either
                    }
                    if (others) {
                        send(EntityVehicleSVA.AE_OTHERS, "", next);
                    } else {
                        send(EntityVehicleSVA.AE_SET, info.names.get(row), next);
                    }
                    mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                            new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
                    return;
                }
            }
            if (!others && mx >= left + 6 && mx < left + 128) {
                selected = info.names.get(row).equals(selected) ? "" : info.names.get(row);
            }
            return;
        }
    }

    // ------------------------------------------------------------------ drawing

    private void rect(int x, int y, int w, int h, int c) {
        if (w > 0 && h > 0) {
            drawRect(x, y, x + w, y + h, c);
        }
    }

    private void frame(int x, int y, int w, int h, int border, int fillC) {
        rect(x, y, w, h, border);
        rect(x + 1, y + 1, w - 2, h - 2, fillC);
    }

    private void box(int x, int y, boolean on, boolean fixed) {
        frame(x, y, 10, 10, fixed ? 0xFF3A4150 : 0xFF6A7488, 0xFF0E1116);
        if (on) {
            rect(x + 2, y + 2, 6, 6, fixed ? 0xFF3C8A50 : 0xFF62E06A);
        }
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        TextFitSC.beginFrame();
        drawDefaultBackground();
        GL11.glColor4f(1F, 1F, 1F, 1F);
        int x = left, y = top;
        frame(x, y, W, H, EDGE, BG);
        rect(x + 1, y + 1, W - 2, 1, 0xFF3A4150);
        rect(x + 4, y + 15, W - 8, 1, 0xFFE08A30);
        frame(x + 4, y + 18, W - 8, 122, EDGE, PANEL);
        frame(x + 4, y + 142, W - 8, 66, EDGE, PANEL);
        VehicleType t = VehicleType.byOrdinal(info.type);
        String title = Lang.tr("sva.access.title", Lang.tr("item.siliconagevehicles.vehicle." + t.key + ".name"), Integer.valueOf(info.board));
        TextFitSC.draw(fontRendererObj, title, x + 7, y + 5, W - 14, GOLD, 0, 0);
        fontRendererObj.drawString(Lang.tr("sva.access.col.player"), x + 8, y + 22, CYAN);
        String[] cols = {"drive", "passenger", "cargo", "repair"};
        for (int c = 0; c < cols.length; c++) {
            TextFitSC.drawCentered(fontRendererObj, Lang.tr("sva.access.right." + cols[c] + ".col"), x + COL_X[c] - 15, y + 22, 40, CYAN, false, 0, 0);
        }
        for (int i = 0; i < VISIBLE; i++) {
            int row = scroll + i - 1;
            if (row > info.names.size()) {
                break;
            }
            int ry = y + ROW_Y + i * ROW_H;
            boolean owner = row < 0, others = row == info.names.size();
            String name = owner ? Lang.tr("sva.access.ownerRow", info.owner) : others ? Lang.tr("sva.access.othersRow") : info.names.get(row);
            boolean sel = !owner && !others && name.equals(selected);
            rect(x + 6, ry, W - 12, ROW_H - 1, sel ? 0xFF2E4A66 : i % 2 == 0 ? 0xFF262C38 : 0xFF222733);
            TextFitSC.draw(fontRendererObj, name, x + 9, ry + 2, 120, owner ? GOLD : others ? MUTED : TEXT, 0, 0);
            int r = owner ? AccessMath.ALL : others ? info.others : info.rights.get(row).intValue();
            for (int c = 0; c < COL_X.length; c++) {
                box(x + COL_X[c], ry + 1, (r & 1 << c) != 0, owner);
            }
        }
        if (rowCount() > VISIBLE) {
            int track = VISIBLE * ROW_H - 2, th = Math.max(8, track * VISIBLE / rowCount());
            int ty = (track - th) * scroll / Math.max(1, rowCount() - VISIBLE);
            rect(x + W - 9, y + ROW_Y, 3, track, 0xFF151920);
            rect(x + W - 9, y + ROW_Y + ty, 3, th, 0xFF6A7488);
        }
        nameField.drawTextBox();
        if (nameField.getText().length() == 0 && !nameField.isFocused()) {
            fontRendererObj.drawString(Lang.tr("sva.access.namePrompt"), x + 12, y + 150, MUTED);
        }
        fontRendererObj.drawString(Lang.tr("sva.access.lock"), x + 8, y + 170, CYAN);
        String modeHint = Lang.tr("sva.access.mode." + info.mode + ".hint");
        TextFitSC.draw(fontRendererObj, modeHint, x + 8, y + 186, W - 16, TEXT, 0, 0);
        String lockLine = Lang.tr("sva.access.lockLine", Integer.valueOf(info.lock), info.owner);
        TextFitSC.draw(fontRendererObj, lockLine, x + 8, y + 197, W - 16, info.mode == AccessMath.OPEN ? MUTED : RED, 0, 0);
        super.drawScreen(mx, my, pt);
        // the selected mode button framed green
        GuiButton active = (GuiButton) buttonList.get(B_OPEN + info.mode);
        int ax = active.xPosition, ay = active.yPosition;
        rect(ax - 1, ay - 1, active.width + 2, 1, 0xFF62E06A);
        rect(ax - 1, ay + active.height, active.width + 2, 1, 0xFF62E06A);
        rect(ax - 1, ay, 1, active.height, 0xFF62E06A);
        rect(ax + active.width, ay, 1, active.height, 0xFF62E06A);
        List<String> tip = new ArrayList<String>();
        tip.add(Lang.tr("sva.access.tip1"));
        tip.add("§7" + Lang.tr("sva.access.tip2"));
        tip.add("§7" + Lang.tr("sva.access.tip3"));
        TextFitSC.hover(x + 4, y + 18, W - 8, 14, tip);
        TextFitSC.hover(x + 248, y + 166, 66, 16, Lang.tr("sva.access.relock.tip"));
        TextFitSC.hover(x + 248, y + 146, 66, 16, Lang.tr("sva.access.issue.tip"));
        List<String> hover = TextFitSC.hoverAt(mx, my);
        if (hover != null && !hover.isEmpty()) {
            drawHoveringText(hover, mx, my, fontRendererObj);
        }
    }
}
