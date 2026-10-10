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
 * ТН-5: «Сменить замок» works only with Shift held (like the tank's «Вылить»). ТО-6: «Передать» (the owner only) - pick a
 * player within EntityVehicleSVA.TRANSFER_RANGE, confirm (with «Сменить замок» on by default), AE_TRANSFER, the screen
 * closes. ТИ-2: on a screen smaller than the window it is drawn scaled down (GuiFitSVA).
 */
public class GuiAccessSVA extends GuiScreen {

    private static final int W = 320, H = 212, ROW_Y = 34, ROW_H = 12, VISIBLE = 8;
    private static final int[] COL_X = {136, 180, 224, 268};
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, PANEL = 0xFF232833, GOLD = 0xF2A040, CYAN = 0x5EC8F0, TEXT = 0xD8DCE4,
            MUTED = 0x8A93A3, GREEN = 0x62E06A, RED = 0xF06060;
    private static final int B_ADD = 0, B_REMOVE = 1, B_ISSUE = 2, B_OPEN = 3, B_FRIENDS = 4, B_OWNER = 5, B_RELOCK = 6;
    /** ТО-6: «Передать» and its dialog's «Передать» / «Отмена». */
    private static final int B_TRANSFER = 7, B_T_YES = 8, B_T_NO = 9;
    /** ТО-6 dialog steps: none, picking a player, confirming. */
    private static final int T_NONE = 0, T_PICK = 1, T_CONFIRM = 2;
    /** The transfer button's place (bottom right, beside the mode hint and the lock line). */
    private static final int TR_X = 248, TR_Y = 188, TR_W = 66;
    /** ТО-6: at most this many nearby players listed (the rows stay above the dialog buttons). */
    private static final int PICK_ROWS = 6;

    private NetSVA.AccessInfo info;
    private GuiTextField nameField;
    private int left, top, scroll;
    /** The selected player row's name ("" none). */
    private String selected = "";
    /** ТИ-2: the drawing scale (1 - the window fits). */
    private float fit = 1F;
    /** ТО-6: the dialog step, the nearby players found, the one picked, «Сменить замок» with it (on by default). */
    private int transferStep = T_NONE;
    private final List<String> candidates = new ArrayList<String>();
    private String transferTo = "";
    private boolean transferRelock = true;

    private GuiAccessSVA(NetSVA.AccessInfo info) {
        this.info = info;
    }

    /** Opens the screen (info.open), or refreshes it when it is already open for that vehicle (client thread). */
    public static void show(NetSVA.AccessInfo info) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GuiAccessSVA && ((GuiAccessSVA) mc.currentScreen).info.entity == info.entity) {
            GuiAccessSVA g = (GuiAccessSVA) mc.currentScreen;
            g.info = info;
            g.clampScroll();
            return;
        }
        if (!info.open) {
            return;                                                     // a late reply to an edit: the screen was closed
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
        buttonList.add(new TextFitSC.Button(B_TRANSFER, left + TR_X, top + TR_Y, TR_W, 16, Lang.tr("sva.access.transfer")));
        buttonList.add(new TextFitSC.Button(B_T_YES, left + W / 2 - 68, top + 118, 64, 16, Lang.tr("sva.access.transfer.yes")));
        buttonList.add(new TextFitSC.Button(B_T_NO, left + W / 2 + 4, top + 118, 64, 16, Lang.tr("sva.access.transfer.no")));
        updateButtons();
    }

    /** ТИ-2: a screen smaller than the window lays it out in a bigger virtual screen, drawn scaled down. */
    @Override
    public void setWorldAndResolution(Minecraft mc, int w, int h) {
        fit = GuiFitSVA.fit(w, h, W, H);
        super.setWorldAndResolution(mc, GuiFitSVA.virtual(w, fit), GuiFitSVA.virtual(h, fit));
    }

    /** ТО-6: the vehicle's owner is the player looking at the screen (the server checks it again by the UUID). */
    private boolean isOwner() {
        return mc.thePlayer != null && info.owner.length() > 0 && info.owner.equalsIgnoreCase(mc.thePlayer.getCommandSenderName());
    }

    /** ТО-6: the other players within EntityVehicleSVA.TRANSFER_RANGE of the vehicle, nearest first. */
    private void findCandidates() {
        candidates.clear();
        net.minecraft.entity.Entity v = mc.theWorld == null ? null : mc.theWorld.getEntityByID(info.entity);
        if (v == null) {
            return;
        }
        final net.minecraft.entity.Entity veh = v;
        List<net.minecraft.entity.player.EntityPlayer> near = new ArrayList<net.minecraft.entity.player.EntityPlayer>();
        double r2 = EntityVehicleSVA.TRANSFER_RANGE * EntityVehicleSVA.TRANSFER_RANGE;
        for (Object o : mc.theWorld.playerEntities) {
            if (o instanceof net.minecraft.entity.player.EntityPlayer && o != mc.thePlayer
                    && ((net.minecraft.entity.player.EntityPlayer) o).getDistanceSqToEntity(veh) <= r2) {
                near.add((net.minecraft.entity.player.EntityPlayer) o);
            }
        }
        java.util.Collections.sort(near, new java.util.Comparator<net.minecraft.entity.player.EntityPlayer>() {
            @Override
            public int compare(net.minecraft.entity.player.EntityPlayer a, net.minecraft.entity.player.EntityPlayer b) {
                return Double.compare(a.getDistanceSqToEntity(veh), b.getDistanceSqToEntity(veh));
            }
        });
        for (int i = 0; i < near.size() && candidates.size() < PICK_ROWS; i++) {
            candidates.add(near.get(i).getCommandSenderName());
        }
    }

    /** Enables / shows the buttons for the current state (the dialog covers the main buttons). */
    private void updateButtons() {
        boolean dialog = transferStep != T_NONE;
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.id == B_ADD) {
                b.enabled = !dialog && AccessMath.validName(nameField.getText().trim()) && info.names.size() < AccessMath.MAX_FRIENDS;
            } else if (b.id == B_REMOVE || b.id == B_ISSUE) {
                b.enabled = !dialog && target().length() > 0;
            } else if (b.id >= B_OPEN && b.id <= B_OWNER) {
                b.enabled = !dialog && info.mode != b.id - B_OPEN;
            } else if (b.id == B_RELOCK) {
                b.enabled = !dialog;
            } else if (b.id == B_TRANSFER) {
                b.visible = isOwner();
                b.enabled = !dialog;
            } else if (b.id == B_T_YES) {
                b.visible = transferStep == T_CONFIRM;
            } else if (b.id == B_T_NO) {
                b.visible = dialog;
            }
        }
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
        net.minecraft.entity.Entity e = mc.theWorld == null ? null : mc.theWorld.getEntityByID(info.entity);
        if (!(e instanceof EntityVehicleSVA)
                || !((EntityVehicleSVA) e).carries(mc.thePlayer) && mc.thePlayer.getDistanceSqToEntity(e) >= 64) {
            mc.displayGuiScreen(null);                                  // walked away (the server's edit range) / the vehicle is gone
            return;
        }
        if (transferStep != T_NONE && !isOwner()) {
            transferStep = T_NONE;                                      // no longer the owner (a refresh said so)
        }
        updateButtons();
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
            if (!isShiftKeyDown()) {
                return;                                                 // ТН-5: only with Shift (its tooltip says so)
            }
            send(EntityVehicleSVA.AE_RELOCK, "", 0);
        } else if (b.id == B_TRANSFER) {
            findCandidates();
            transferTo = "";
            transferRelock = true;
            transferStep = T_PICK;
            updateButtons();
        } else if (b.id == B_T_NO) {
            transferStep = T_NONE;
            updateButtons();
        } else if (b.id == B_T_YES && transferStep == T_CONFIRM && transferTo.length() > 0) {
            NetSVA.CHANNEL.sendToServer(new NetSVA.AccessEdit(info.entity, EntityVehicleSVA.AE_TRANSFER, transferTo, transferRelock ? 1 : 0));
            mc.displayGuiScreen(null);                                  // no longer the owner: the server's refresh won't reopen it
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (transferStep != T_NONE) {
            if (key == Keyboard.KEY_ESCAPE) {
                transferStep = T_NONE;                                  // Esc closes the dialog first
                updateButtons();
            }
            return;
        }
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
        int step = transferStep;                                        // a dialog button may close it during super
        super.mouseClicked(mx, my, button);
        if (step != T_NONE) {
            if (transferStep == step) {
                transferClicked(mx, my);
            }
            return;
        }
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

    /** ТО-6 dialog: a click on a nearby player picks him; on the confirm step the «Сменить замок» box toggles. */
    private void transferClicked(int mx, int my) {
        if (transferStep == T_PICK) {
            for (int i = 0; i < candidates.size(); i++) {
                int ry = top + ROW_Y + i * ROW_H;
                if (my >= ry && my < ry + ROW_H && mx >= left + 6 && mx < left + W - 6) {
                    transferTo = candidates.get(i);
                    transferStep = T_CONFIRM;
                    updateButtons();
                    click();
                    return;
                }
            }
        } else if (transferStep == T_CONFIRM) {
            int bx = left + 10, by = top + 62;
            if (mx >= bx - 2 && mx < left + W - 10 && my >= by - 2 && my < by + 12) {
                transferRelock = !transferRelock;
                click();
            }
        }
    }

    private void click() {
        mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
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
        GuiFitSVA.begin(fit);                                           // ТИ-2
        drawFitted(GuiFitSVA.mouse(mx, fit), GuiFitSVA.mouse(my, fit), pt);
        GuiFitSVA.end(fit);
    }

    private void drawFitted(int mx, int my, float pt) {
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
        boolean ownerView = isOwner();
        int lineW = ownerView ? TR_X - 12 : W - 16;                         // ТО-6: «Передать» sits right of the two lines
        String modeHint = Lang.tr("sva.access.mode." + info.mode + ".hint");
        TextFitSC.draw(fontRendererObj, modeHint, x + 8, y + 186, lineW, TEXT, 0, 0);
        String lockLine = Lang.tr("sva.access.lockLine", Integer.valueOf(info.lock), info.owner);
        TextFitSC.draw(fontRendererObj, lockLine, x + 8, y + 197, lineW, info.mode == AccessMath.OPEN ? MUTED : RED, 0, 0);
        if (transferStep != T_NONE) {
            drawTransfer(x, y, mx, my);
        }
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
        if (transferStep == T_NONE) {
            TextFitSC.hover(x + 4, y + 18, W - 8, 14, tip);
            List<String> relockTip = new ArrayList<String>();
            relockTip.add(Lang.tr("sva.access.relock.tip"));
            if (!isShiftKeyDown()) {
                relockTip.add(Lang.tr("sva.access.relock.shift"));   // ТН-5
            }
            TextFitSC.hover(x + 248, y + 166, 66, 16, relockTip);
            TextFitSC.hover(x + 248, y + 146, 66, 16, Lang.tr("sva.access.issue.tip"));
            if (ownerView) {
                TextFitSC.hover(x + TR_X, y + TR_Y, TR_W, 16, Lang.tr("sva.access.transfer.tip", Integer.valueOf((int) EntityVehicleSVA.TRANSFER_RANGE)));
            }
        }
        List<String> hover = TextFitSC.hoverAt(mx, my);
        if (hover != null && !hover.isEmpty()) {
            drawHoveringText(hover, mx, my, fontRendererObj);
        }
    }

    /** ТО-6: the transfer dialog over the player list - the nearby players to pick, then the confirmation. */
    private void drawTransfer(int x, int y, int mx, int my) {
        frame(x + 4, y + 18, W - 8, 122, 0xFFE08A30, PANEL);
        if (transferStep == T_PICK) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.access.transfer.tip", Integer.valueOf((int) EntityVehicleSVA.TRANSFER_RANGE)),
                    x + 8, y + 22, W - 16, CYAN, 0, 0);
            if (candidates.isEmpty()) {
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.access.transfer.none"), x + 9, y + ROW_Y + 2, W - 18, MUTED, 0, 0);
            }
            for (int i = 0; i < candidates.size(); i++) {
                int ry = y + ROW_Y + i * ROW_H;
                boolean over = mx >= x + 6 && mx < x + W - 6 && my >= ry && my < ry + ROW_H;
                rect(x + 6, ry, W - 12, ROW_H - 1, over ? 0xFF2E4A66 : i % 2 == 0 ? 0xFF262C38 : 0xFF222733);
                TextFitSC.draw(fontRendererObj, candidates.get(i), x + 9, ry + 2, W - 18, TEXT, 0, 0);
            }
        } else {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.access.transfer"), x + 8, y + 22, W - 16, CYAN, 0, 0);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.access.transfer.confirm", transferTo), x + 10, y + 40, W - 20, GOLD, 0, 0);
            box(x + 10, y + 62, transferRelock, false);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.access.transfer.relock"), x + 24, y + 63, W - 34, TEXT, 0, 0);
        }
    }
}
