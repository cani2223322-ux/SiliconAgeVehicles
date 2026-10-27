package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import com.sc.manual.Lang;
import com.sva.FormatSVA;
import com.sva.net.NetAptSVA;
import com.sva.vehicle.SkinMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.IChatComponent;

/**
 * PACKAGE S (docs/plan-embassy-airfield-addon.md §3.8): the embassy column's service window «Обслуживание» - opened by the server's
 * NetAptSVA D_EMB (block.EmbassyServicesSVA.openService / tabAction: the snapshot of the player's machines, the prices, the stands),
 * refreshed by the next D_EMB while open; its buttons send NetAptSVA Cmd EMB (x - the embassy's id, y - the column, -1 from the tab).
 * No container, no GUI id (the core's column is no block of ours). Tones and colours as the add-on's other screens (GuiTowerSVA).
 * <p>
 * Stub by the coordinator: receive() and the class are fixed (AirportClientSVA hands the packet here), the screen is S's.
 * <p>
 * Package S's screen: the header (the embassy, its ICAO, the player's level there); the player's machines in reach on the left (type,
 * board, stand / lot / hangar); the selected one's stores (charge, hydrogen, wear, the battery) and its services on the right - each with
 * its amount and its price button («N КЧ», «бесплатно») or, refused, the reason in grey (the core's sc.airfield.pay.* line or the
 * window's own: only in the hangar, no paper, aboard, no free stand); at the bottom his stands (the days owed - «Оплатить»), his machines
 * on the «Отстой» lot and the approach charts of the embassy's tower; «Обновить», «Закрыть». A pressed button waits for the server's
 * new snapshot (no second payment by a double click); the snapshot is asked again every REFRESH ticks while the window is open.
 * Ops (EmbassyServicesSVA.OP_*): refresh, buy (E, K, A), park (S), tow (E, S), chart (Ap), close.
 */
@SideOnly(Side.CLIENT)
public class GuiEmbassyServiceSVA extends GuiScreen {

    /** The last snapshot (the network thread writes it, the screen reads it). */
    static volatile NBTTagCompound snap;

    static final int W = 400, H = 250, REFRESH = 100, WAIT = 60;
    static final int B_SEL = 0, B_BUY = 1, B_PARK = 2, B_CHART = 3, B_REFRESH = 4, B_CLOSE = 5;

    private int left, top, ticks, sel, selId = -1, waitTicks;
    /** The snapshot a pressed button waits to be replaced (null: none). */
    private NBTTagCompound waitFor;
    private float fit = 1F;
    private final List<GuiTowerSVA.Btn> btns = new ArrayList<GuiTowerSVA.Btn>();

    /** NetAptSVA D_EMB (the network thread): kept, the screen opened / refreshed on the client thread. */
    public static void receive(final NBTTagCompound t) {
        if (t == null) {
            return;
        }
        snap = t;
        final Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(new Runnable() {
            @Override
            public void run() {
                if (t.getBoolean("Open") && !(mc.currentScreen instanceof GuiEmbassyServiceSVA)) {
                    mc.displayGuiScreen(new GuiEmbassyServiceSVA());
                }
            }
        });
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    static String tr(String k, Object... a) {
        return Lang.tr("sva.emb.gui." + k, a);
    }

    // ---------------------------------------------------------------- the server

    private void cmd(NBTTagCompound d) {
        NBTTagCompound s = snap;
        if (s == null) {
            return;
        }
        NetAptSVA.cmd(NetAptSVA.EMB, s.getInteger("E"), s.getInteger("Col"), 0, d);
    }

    private void cmd(String op) {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", op);
        cmd(d);
    }

    /** A paying button pressed: its command sent, the buttons wait for the answer's snapshot. */
    private void send(NBTTagCompound d) {
        waitFor = snap;
        waitTicks = WAIT;
        cmd(d);
    }

    private boolean waiting() {
        return waitFor != null && waitFor == snap && waitTicks > 0;
    }

    @Override
    public void initGui() {
        left = (width - W) / 2;
        top = (height - H) / 2;
    }

    @Override
    public void setWorldAndResolution(Minecraft mc, int w, int h) {
        fit = GuiFitSVA.fit(w, h, W, H);
        super.setWorldAndResolution(mc, GuiFitSVA.virtual(w, fit), GuiFitSVA.virtual(h, fit));
    }

    @Override
    public void onGuiClosed() {
        cmd("close");
    }

    @Override
    public void updateScreen() {
        ticks++;
        if (waitTicks > 0) {
            waitTicks--;
        }
        if (ticks % REFRESH == 0 && !waiting()) {
            cmd("refresh");
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(c, key);
    }

    // ---------------------------------------------------------------- drawing helpers (GuiTowerSVA's look)

    void box(int x0, int y0, int x1, int y1, int c) {
        Gui.drawRect(left + x0, top + y0, left + x1, top + y1, c);
    }

    void text(String s, int x, int y, int c) {
        fontRendererObj.drawString(s, left + x, top + y, c);
    }

    /** Text cut to `w` pixels. */
    void text(String s, int x, int y, int w, int c) {
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(s, w), left + x, top + y, c);
    }

    void right(String s, int x1, int y, int c) {
        fontRendererObj.drawString(s, left + x1 - fontRendererObj.getStringWidth(s), top + y, c);
    }

    /** A button (id, its argument), its label centred; disabled - muted (GuiTowerSVA.button). */
    void button(int id, String arg, int x0, int y0, int x1, int y1, String label, boolean on, boolean active) {
        box(x0, y0, x1, y1, active ? 0xFF2D5A3C : on ? 0xFF2A303C : 0xFF1E222A);
        box(x0, y0, x1, y0 + 1, on ? 0xFF4A5468 : 0xFF2A2F38);
        String l = fontRendererObj.trimStringToWidth(label, x1 - x0 - 4);
        fontRendererObj.drawString(l, left + (x0 + x1) / 2 - fontRendererObj.getStringWidth(l) / 2, top + (y0 + y1) / 2 - 4,
                on ? GuiTowerSVA.TEXT : GuiTowerSVA.MUTED);
        btns.add(new GuiTowerSVA.Btn(id, left + x0, top + y0, left + x1, top + y1, on, arg));
    }

    static String typeName(int ordinal) {
        if (ordinal < 0 || ordinal >= VehicleType.values().length) {
            return "?";
        }
        return Lang.tr("item.siliconagevehicles.vehicle." + VehicleType.byOrdinal(ordinal).key + ".name");
    }

    /** «Самолёт 12» (the board unknown: the type alone). */
    static String machine(int type, int board) {
        return board < 0 ? typeName(type) : tr("machine", typeName(type), SkinMath.boardText(board));
    }

    static String unit(String u, long n) {
        return Lang.tr("sva.emb.gui.unit." + u, FormatSVA.group(n));
    }

    /** A service's amount in its measure. */
    static String amount(String key, NBTTagCompound o) {
        long a = o.getLong("A");
        if ("charge".equals(key)) {
            return unit("eu", a);
        }
        if ("hydrogen".equals(key)) {
            return unit("mb", a);
        }
        if ("repair".equals(key)) {
            return unit("pct", a);
        }
        if ("park".equals(key)) {
            return unit("day", a);
        }
        if ("emergency".equals(key)) {
            return tr("emergencyAmount");
        }
        if ("tow".equals(key)) {
            return o.hasKey("To") ? tr("towTo", Lang.tr(o.getString("To"))) : "";
        }
        return unit("one", a);
    }

    /** The price button's label: «N КЧ» / «бесплатно». */
    static String price(int p) {
        return p == 0 ? tr("free") : tr("chips", Integer.valueOf(p));
    }

    // ---------------------------------------------------------------- the frame

    @Override
    public void drawScreen(int mx, int my, float pt) {
        GuiFitSVA.begin(fit);
        drawFitted(GuiFitSVA.mouse(mx, fit), GuiFitSVA.mouse(my, fit), pt);
        GuiFitSVA.end(fit);
    }

    private void drawFitted(int mx, int my, float pt) {
        btns.clear();
        drawDefaultBackground();
        box(-1, -1, W + 1, H + 1, GuiTowerSVA.EDGE);
        box(0, 0, W, H, GuiTowerSVA.BG);
        NBTTagCompound s = snap;
        if (s == null) {
            text(tr("waiting"), 10, 10, GuiTowerSVA.MUTED);
            button(B_CLOSE, "", W - 74, H - 16, W - 4, H - 3, tr("close"), true, false);
            super.drawScreen(mx, my, pt);
            return;
        }
        // the header: the service, the embassy, its ICAO, the player's level there
        text(tr("title"), 6, 5, GuiTowerSVA.GOLD);
        int x = 8 + fontRendererObj.getStringWidth(tr("title"));
        String name = name(s);
        text(name, x, 5, 190, GuiTowerSVA.TEXT);
        x += Math.min(190, fontRendererObj.getStringWidth(name)) + 6;
        text(s.getString("Icao"), x, 5, GuiTowerSVA.CYAN);
        int lvl = s.getInteger("Lvl");
        if (lvl >= 0) {
            right(tr("level", Lang.tr("sc.settle.rep." + levelKey(lvl))), W - 6, 5, lvl <= 0 ? GuiTowerSVA.RED : lvl < 3 ? GuiTowerSVA.AMBER
                    : GuiTowerSVA.GREEN);
        }
        box(4, 16, W - 4, 17, 0xFFE08A30);
        boolean wait = waiting();
        if (s.hasKey("Refuse")) {
            String r = Lang.tr(s.getString("Refuse"));
            fontRendererObj.drawSplitString(r, left + 20, top + 60, W - 40, GuiTowerSVA.RED);
        } else {
            machines(s, wait);
            bottom(s, wait);
        }
        if (wait) {
            text(tr("busy"), 6, H - 13, GuiTowerSVA.AMBER);
        }
        button(B_REFRESH, "", W - 150, H - 16, W - 78, H - 3, tr("refresh"), !wait, false);
        button(B_CLOSE, "", W - 74, H - 16, W - 4, H - 3, tr("close"), true, false);
        super.drawScreen(mx, my, pt);
    }

    static String levelKey(int lvl) {
        String[] k = {"enemy", "violator", "stranger", "acquaintance", "partner", "own", "honoured"};
        return k[Math.max(0, Math.min(k.length - 1, lvl))];
    }

    static String name(NBTTagCompound s) {
        try {
            IChatComponent c = IChatComponent.Serializer.func_150699_a(s.getString("Name"));
            return c == null ? "" : c.getFormattedText();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The machines (left) and the selected one's stores and services (right). */
    private void machines(NBTTagCompound s, boolean wait) {
        NBTTagList ml = s.getTagList("M", 10);
        text(tr("machines"), 6, 21, GuiTowerSVA.GOLD);
        box(150, 20, 151, 166, GuiTowerSVA.LINEC);
        if (ml.tagCount() == 0) {
            fontRendererObj.drawSplitString(tr("noMachines"), left + 6, top + 34, 140, GuiTowerSVA.MUTED);
            fontRendererObj.drawSplitString(tr("hint"), left + 158, top + 34, W - 166, GuiTowerSVA.MUTED);
            return;
        }
        sel = 0;                                                    // the selected machine kept by its entity id (the order may change)
        for (int i = 0; i < ml.tagCount(); i++) {
            if (ml.getCompoundTagAt(i).getInteger("Id") == selId) {
                sel = i;
            }
        }
        selId = ml.getCompoundTagAt(sel).getInteger("Id");
        for (int i = 0; i < ml.tagCount() && i < 10; i++) {
            NBTTagCompound m = ml.getCompoundTagAt(i);
            int y = 32 + i * 13;
            button(B_SEL, String.valueOf(i), 4, y, 146, y + 12, machine(m.getInteger("T"), m.getInteger("B")) + where(m), true, i == sel);
        }
        NBTTagCompound m = ml.getCompoundTagAt(sel);
        int y = 21;
        text(machine(m.getInteger("T"), m.getInteger("B")), 158, y, 150, GuiTowerSVA.GOLD);
        String w = where(m);
        if (w.length() > 0) {
            right(w.trim(), W - 6, y, GuiTowerSVA.CYAN);
        }
        y += 12;
        text(tr("charge", FormatSVA.group(m.getLong("En")), FormatSVA.group(m.getLong("EnMax"))), 158, y, W - 166, GuiTowerSVA.TEXT);
        y += 10;
        if (m.getBoolean("Hyd") && m.getInteger("H2Max") > 0) {
            text(tr("h2", FormatSVA.group(m.getInteger("H2")), FormatSVA.group(m.getInteger("H2Max"))), 158, y, W - 166, GuiTowerSVA.TEXT);
            y += 10;
        }
        int wear = m.getInteger("Wear");
        text(tr("wear", Integer.valueOf(wear)), 158, y, W - 166, wear > 50 ? GuiTowerSVA.RED : wear > 0 ? GuiTowerSVA.AMBER : GuiTowerSVA.TEXT);
        y += 10;
        if (m.getBoolean("Bat")) {
            int bh = m.getInteger("Bh");
            text(tr("battery", Integer.valueOf(bh), Integer.valueOf(m.getInteger("Bl"))), 158, y, W - 166, bh < 80 ? GuiTowerSVA.AMBER
                    : GuiTowerSVA.TEXT);
            y += 10;
        }
        if (m.getBoolean("Crew")) {
            text(tr("crew"), 158, y, W - 166, GuiTowerSVA.MUTED);
            y += 10;
        }
        y += 3;
        box(156, y - 2, W - 4, y - 1, GuiTowerSVA.LINEC);
        NBTTagList sl = m.getTagList("S", 10);
        if (sl.tagCount() == 0) {
            text(tr("nothing"), 158, y + 2, W - 166, GuiTowerSVA.MUTED);
        }
        for (int i = 0; i < sl.tagCount() && y < 152; i++) {
            NBTTagCompound o = sl.getCompoundTagAt(i);
            String key = o.getString("K");
            text(Lang.tr("sc.airfield.service." + key), 158, y + 2, 120, GuiTowerSVA.TEXT);
            text(amount(key, o), 282, y + 2, 56, GuiTowerSVA.MUTED);
            int p = o.getInteger("P");
            if (p >= 0) {
                button(B_BUY, sel + ":" + i, 340, y, W - 6, y + 12, price(p), !wait, false);
            } else {
                String r = o.hasKey("R") ? Lang.tr(o.getString("R")) : "";
                String cut = fontRendererObj.trimStringToWidth(r, W - 166);
                y += 11;
                text(cut, 162, y + 2, GuiTowerSVA.RED);
            }
            y += 14;
        }
    }

    /** «МС-2» / «Отстой» / «Ангар» after a machine's name ("" none). */
    static String where(NBTTagCompound m) {
        String sp = m.getString("Sp");
        if (sp.length() > 0) {
            return " - " + Lang.tr(sp);
        }
        if (m.getBoolean("Lot")) {
            return " - " + tr("lot");
        }
        return m.getBoolean("Hg") ? " - " + tr("hangar") : "";
    }

    /** His stands (the days owed), his machines on the lot, the approach charts. */
    private void bottom(NBTTagCompound s, boolean wait) {
        int y = 168;
        box(4, y, W - 4, y + 1, GuiTowerSVA.LINEC);
        y += 3;
        NBTTagList st = s.getTagList("St", 10), lt = s.getTagList("Lt", 10), ap = s.getTagList("Ap", 10);
        text(tr("stands"), 6, y, GuiTowerSVA.GOLD);
        int ys = y + 11;
        if (st.tagCount() == 0 && lt.tagCount() == 0) {
            text(tr("noStands"), 6, ys, 190, GuiTowerSVA.MUTED);
        }
        for (int i = 0; i < st.tagCount() && ys < H - 30; i++) {
            NBTTagCompound e = st.getCompoundTagAt(i);
            int due = e.getInteger("Due"), free = e.getInteger("Free");
            String state = free == 2 ? tr("talon") : due > 0 ? tr("due", unit("day", due)) : free == 1 ? tr("freeDay") : tr("paid");
            text(Lang.tr(e.getString("K")) + ": " + machine(e.getInteger("T"), e.getInteger("B")), 6, ys, 120, GuiTowerSVA.TEXT);
            text(state, 128, ys, 70, due > 0 ? GuiTowerSVA.AMBER : GuiTowerSVA.MUTED);
            if (due > 0) {
                int p = e.hasKey("P") ? e.getInteger("P") : -1;
                if (p >= 0) {
                    button(B_PARK, String.valueOf(e.getInteger("S")), 128, ys + 9, 196, ys + 20, tr("pay", price(p)), !wait, false);
                    ys += 11;
                } else if (e.hasKey("R")) {
                    ys += 10;
                    text(Lang.tr(e.getString("R")), 10, ys, 186, GuiTowerSVA.RED);
                }
            }
            ys += 11;
        }
        for (int i = 0; i < lt.tagCount() && ys < H - 30; i++) {
            NBTTagCompound e = lt.getCompoundTagAt(i);
            int due = e.getInteger("Due");
            text(tr("onLot", machine(e.getInteger("T"), e.getInteger("B"))), 6, ys, due > 0 ? 120 : 190, GuiTowerSVA.MUTED);
            if (due > 0) {
                text(tr("due", unit("day", due)), 128, ys, 70, GuiTowerSVA.AMBER);   // paid with the tow off the lot
            }
            ys += 11;
        }
        // the approach charts
        box(200, y, 201, H - 20, GuiTowerSVA.LINEC);
        text(tr("charts"), 206, y, GuiTowerSVA.GOLD);
        int yc = y + 11;
        if (ap.tagCount() == 0) {
            text(tr("noCharts"), 206, yc, W - 214, GuiTowerSVA.MUTED);
        }
        for (int i = 0; i < ap.tagCount() && yc < H - 30; i++) {
            NBTTagCompound e = ap.getCompoundTagAt(i);
            text(e.getString("Id"), 206, yc + 2, 70, GuiTowerSVA.TEXT);
            int p = e.getInteger("P");
            if (p >= 0) {
                button(B_CHART, e.getString("Id"), 280, yc, W - 6, yc + 12, tr("print", price(p)), !wait, false);
            } else {
                text(e.hasKey("R") ? Lang.tr(e.getString("R")) : "", 280, yc + 2, W - 286, GuiTowerSVA.RED);
            }
            yc += 14;
        }
    }

    // ---------------------------------------------------------------- the mouse

    @Override
    protected void mouseClicked(int mx, int my, int b) {
        super.mouseClicked(mx, my, b);
        for (GuiTowerSVA.Btn x : new ArrayList<GuiTowerSVA.Btn>(btns)) {
            if (mx >= x.x0 && mx < x.x1 && my >= x.y0 && my < x.y1 && x.on) {
                click(x);
                mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                        new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
                return;
            }
        }
    }

    private void click(GuiTowerSVA.Btn x) {
        NBTTagCompound s = snap;
        NBTTagCompound d = new NBTTagCompound();
        if ((x.id == B_BUY || x.id == B_PARK || x.id == B_CHART) && waiting()) {
            return;                                                 // review 2026-10-27: a second click in the same frame pays once only
        }
        switch (x.id) {
            case B_SEL:
                if (s != null) {
                    NBTTagList l = s.getTagList("M", 10);
                    int i = GuiTowerSVA.num(x.arg, 0);
                    if (i >= 0 && i < l.tagCount()) {
                        selId = l.getCompoundTagAt(i).getInteger("Id");
                    }
                }
                return;
            case B_REFRESH:
                cmd("refresh");
                return;
            case B_CLOSE:
                mc.displayGuiScreen(null);
                return;
            case B_PARK:
                d.setString("Op", "park");
                d.setInteger("S", GuiTowerSVA.num(x.arg, -1));
                send(d);
                return;
            case B_CHART:
                d.setString("Op", "chart");
                d.setString("Ap", x.arg);
                send(d);
                return;
            case B_BUY:
                if (s == null) {
                    return;
                }
                int c = x.arg.indexOf(':');
                int mi = GuiTowerSVA.num(x.arg.substring(0, Math.max(0, c)), -1), oi = GuiTowerSVA.num(x.arg.substring(c + 1), -1);
                NBTTagList ml = s.getTagList("M", 10);
                if (mi < 0 || mi >= ml.tagCount()) {
                    return;
                }
                NBTTagCompound m = ml.getCompoundTagAt(mi);
                NBTTagList sl = m.getTagList("S", 10);
                if (oi < 0 || oi >= sl.tagCount()) {
                    return;
                }
                NBTTagCompound o = sl.getCompoundTagAt(oi);
                d.setInteger("E", m.getInteger("Id"));
                if ("tow".equals(o.getString("K"))) {
                    d.setString("Op", "tow");
                    if (o.hasKey("S")) {
                        d.setInteger("S", o.getInteger("S"));
                    }
                } else {
                    d.setString("Op", "buy");
                    d.setString("K", o.getString("K"));
                    d.setLong("A", o.getLong("A"));
                }
                send(d);
                return;
            default:
                return;
        }
    }
}
