package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.airdefense.XpdrMath;
import com.sva.airport.AtisMath;
import com.sva.airport.AutoTower;
import com.sva.airport.Cpdlc;
import com.sva.airport.IlsCat;
import com.sva.airport.RunwayLayout;
import com.sva.airport.TaxiGraph;
import com.sva.block.TileEntityTowerSVA;
import com.sva.block.TowerScreenSVA;
import com.sva.net.NetAptSVA;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerMcdu;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Airport wave B2 (plan-next §9, items 29-34; mock-up veh_airport_impl_b2_2_tower_gui): the control tower's screen - no
 * container; the picture comes every 10 ticks while it is open (block.TowerScreenSVA.snapshot), the commands go back as
 * NetAptSVA TOWER; a heartbeat every second keeps the viewer (and the controller seat). Tabs:
 * <ul>
 * <li>«ТРАФИК» - the radar scope round the tower (the runways, the approaches' finals, every target: green - its
 * transponder, amber - a primary return of the owner's radars only; the data block «callsign / altitude speed / squawk»),
 * the flight strips of the aircraft talking to the tower (callsign, type, squawk, request, state) and - for the player in
 * the CONTROLLER SEAT («ЗАНЯТЬ ПУЛЬТ») - the clearances to the selected one (taxi, line up, take-off, landing, go around,
 * hold short, standby, unable, climb / descend to, heading, report, free text);</li>
 * <li>«ПОЛОСЫ» - each runway end: its ILS (ident, frequency, category, status, ВКЛ / ВЫКЛ), its RVR, «В ИСПОЛЬЗОВАНИИ»
 * (or AUTO by the wind); the lights' steps (runways, approach, taxiways: AUTO / 0..5) and «СБРОСИТЬ МЕСТНЫЕ»; the weather;</li>
 * <li>«ATIS» - the information letter and its text;</li>
 * <li>«КАРТА» - the airport from above: the runways, the approaches (IF / FAF to the threshold), the taxi lights' graph
 * (clicking two lights links / unlinks them), the follow-me routes, the traffic; the approaches with «ПЕЧАТЬ» (an approach
 * chart, a sheet of paper);</li>
 * <li>«ЖУРНАЛ» - the datalink log;</li>
 * <li>«НАСТРОЙКИ» - the ICAO code, the name, the code range, the access list (the owner), the equipment screen.</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public class GuiTowerSVA extends GuiScreen {

    static final int W = 440, H = 272;
    static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, LINEC = 0xFF3A4150, PANEL = 0xFF14171D, GOLD = 0xF2A040, TEXT = 0xD8DCE4, MUTED = 0x8A93A3,
            AMBER = 0xF0B040, RED = 0xF06060, GREEN = 0x62E06A, CYAN = 0x5EC8F0, MAGENTA = 0xE070E0;
    static final int T_TRAFFIC = 0, T_RWY = 1, T_ATIS = 2, T_MAP = 3, T_LOG = 4, T_SET = 5, TABS = 6;
    static final String[] TAB_KEYS = {"traffic", "rwy", "atis", "map", "log", "set"};
    static final int[] RANGES = {400, 1000, 2500};

    private final TileEntityTowerSVA te;
    private int left, top, tab, ticks, zoom = 1, sel = -1, report, linkA = -1, mapZoom;
    private GuiTextField fAlt, fHdg, fFree, sIcao, sName, sLo, sHi, sAcc, sSqTrust;
    private final List<GuiTextField> fields = new ArrayList<GuiTextField>();
    private boolean settingsLoaded;

    /** A button of the last frame: id, x0, y0, x1, y1, enabled; its argument. */
    static final class Btn {
        final int id, x0, y0, x1, y1;
        final boolean on;
        final String arg;

        Btn(int id, int x0, int y0, int x1, int y1, boolean on, String arg) {
            this.id = id;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.on = on;
            this.arg = arg;
        }
    }

    private final List<Btn> btns = new ArrayList<Btn>();
    static final int B_TAB = 0, B_SEAT = 10, B_UP = 20, B_STRIP = 40, B_ZOOM = 41, B_ILS = 50, B_USE = 51, B_AUTO = 52, B_LT = 53, B_RESET = 54,
            B_PRINT = 60, B_NODE = 61, B_SAVE = 70, B_EQUIP = 71, B_REPORT = 72, B_MAPZOOM = 73,
            /** Wave A: the coverage map's height on the traffic scope. */
            B_COV = 74;
    /** Wave A: the coverage map's height (-1 off; kept between openings) and when it was last asked for. */
    private static int cov = -1;
    private int covAsked = -1000;

    public GuiTowerSVA(TileEntityTowerSVA te) {
        this.te = te;
        AirportClientSVA.towerSnap = null;
    }

    static String tr(String k, Object... a) {
        return Lang.tr("sva.tower.gui." + k, a);
    }

    private NBTTagCompound snap() {
        NBTTagCompound s = AirportClientSVA.towerSnap;
        if (s == null || !s.hasKey("T")) {
            return null;
        }
        int[] b = s.getCompoundTag("T").getIntArray("Blk");
        return b.length == 3 && b[0] == te.xCoord && b[1] == te.yCoord && b[2] == te.zCoord ? s : null;
    }

    private void cmd(NBTTagCompound d) {
        NetAptSVA.cmd(NetAptSVA.TOWER, te.xCoord, te.yCoord, te.zCoord, d);
    }

    private void cmd(String op) {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", op);
        cmd(d);
    }

    private GuiTextField field(int x, int y, int w, int max) {
        GuiTextField f = new GuiTextField(fontRendererObj, x, y, w, 12);
        f.setMaxStringLength(max);
        fields.add(f);
        return f;
    }

    @Override
    public void initGui() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        Keyboard.enableRepeatEvents(true);
        fields.clear();
        fAlt = field(left + 372, top + 194, 30, 3);
        fHdg = field(left + 372, top + 218, 30, 3);
        fFree = field(left + 236, top + 246, 166, Cpdlc.FREE_MAX);
        sIcao = field(left + 120, top + 50, 50, 4);
        sName = field(left + 120, top + 68, 140, 20);
        sLo = field(left + 120, top + 86, 40, 4);
        sHi = field(left + 170, top + 86, 40, 4);
        sAcc = field(left + 120, top + 104, 300, 200);
        sSqTrust = field(left + 120, top + 122, 300, 200);         // audit 2026-10-22: «коды: доверенные»
        settingsLoaded = false;
        cmd("hb");
    }

    @Override
    public void onGuiClosed() {
        cmd("close");
        Keyboard.enableRepeatEvents(false);
        AirportClientSVA.towerSnap = null;
        CoverClientSVA.release();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        ticks++;
        if (ticks % 20 == 0) {
            NBTTagCompound d = new NBTTagCompound();
            d.setString("Op", "hb");
            NBTTagCompound s = snap();
            d.setBoolean("Seat", s != null && s.getCompoundTag("T").getBoolean("Me"));
            cmd(d);
        }
        if (cov > 0 && tab == T_TRAFFIC && ticks - covAsked >= 100) {
            covAsked = ticks;
            NBTTagCompound d = new NBTTagCompound();
            d.setString("Op", "cov");
            d.setInteger("A", cov);
            cmd(d);
        }
        for (GuiTextField f : fields) {
            f.updateCursorCounter();
        }
    }

    // ---------------------------------------------------------------- drawing helpers

    void box(int x0, int y0, int x1, int y1, int c) {
        Gui.drawRect(left + x0, top + y0, left + x1, top + y1, c);
    }

    void text(String s, int x, int y, int c) {
        fontRendererObj.drawString(s, left + x, top + y, c);
    }

    /** A button (id, its argument), its label centred; disabled - muted. */
    void button(int id, String arg, int x0, int y0, int x1, int y1, String label, boolean on, boolean active) {
        box(x0, y0, x1, y1, active ? 0xFF2D5A3C : on ? 0xFF2A303C : 0xFF1E222A);
        box(x0, y0, x1, y0 + 1, on ? 0xFF4A5468 : 0xFF2A2F38);
        String l = fontRendererObj.trimStringToWidth(label, x1 - x0 - 4);
        fontRendererObj.drawString(l, left + (x0 + x1) / 2 - fontRendererObj.getStringWidth(l) / 2, top + (y0 + y1) / 2 - 4, on ? TEXT : MUTED);
        btns.add(new Btn(id, left + x0, top + y0, left + x1, top + y1, on, arg));
    }

    static void line(float x0, float y0, float x1, float y1, int rgb, float w) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(w);
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x1, y1);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    static void circle(float cx, float cy, float r, int rgb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(1F);
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    // ---------------------------------------------------------------- the frame

    @Override
    public void drawScreen(int mx, int my, float pt) {
        btns.clear();
        drawDefaultBackground();
        box(-1, -1, W + 1, H + 1, EDGE);
        box(0, 0, W, H, BG);
        NBTTagCompound s = snap();
        if (s == null) {
            text(tr("waiting"), 10, 10, MUTED);
            super.drawScreen(mx, my, pt);
            return;
        }
        NBTTagCompound h = s.getCompoundTag("T");
        // the header: the airport, online, the controller seat
        text(h.getString("Icao"), 6, 5, GOLD);
        text(h.getString("Name"), 36, 5, TEXT);
        boolean online = h.getBoolean("Online");
        String ctl = h.getString("Ctl");
        text(online ? tr("online") : tr("offline"), 160, 5, online ? GREEN : RED);
        text(ctl.length() > 0 ? tr("controller", ctl) : h.getBoolean("Auto") ? tr("auto") : tr("noAuto"), 220, 5, ctl.length() > 0 ? CYAN : AMBER);
        boolean me = h.getBoolean("Me");
        button(B_SEAT, me ? "0" : "1", 354, 2, 436, 14, me ? tr("leave") : tr("take"), ctl.length() == 0 || me, me);
        for (int i = 0; i < TABS; i++) {
            button(B_TAB, String.valueOf(i), 4 + i * 72, 17, 74 + i * 72, 29, tr("tab." + TAB_KEYS[i]), true, tab == i);
        }
        box(4, 31, W - 4, 32, 0xFFE08A30);
        switch (tab) {
            case T_TRAFFIC:
                traffic(s, h, me);
                break;
            case T_RWY:
                runways(s, h);
                break;
            case T_ATIS:
                atis(h);
                break;
            case T_MAP:
                map(s, h);
                break;
            case T_LOG:
                log(s);
                break;
            default:
                settings(h);
                break;
        }
        for (GuiTextField f : visibleFields()) {
            f.drawTextBox();
        }
        super.drawScreen(mx, my, pt);
    }

    List<GuiTextField> visibleFields() {
        List<GuiTextField> l = new ArrayList<GuiTextField>();
        if (tab == T_TRAFFIC) {
            l.add(fAlt);
            l.add(fHdg);
            l.add(fFree);
        } else if (tab == T_SET) {
            l.add(sIcao);
            l.add(sName);
            l.add(sLo);
            l.add(sHi);
            l.add(sAcc);
            l.add(sSqTrust);
        }
        return l;
    }

    // ---------------------------------------------------------------- the traffic tab

    /** The scope's centre (screen) and its pixels a block. */
    private float scx, scy, sppb;
    private int scR;

    float sx(double x, NBTTagCompound h) {
        return scx + (float) ((x - h.getIntArray("Blk")[0] - 0.5) * sppb);
    }

    float sz(double z, NBTTagCompound h) {
        return scy + (float) ((z - h.getIntArray("Blk")[2] - 0.5) * sppb);
    }

    boolean inScope(float x, float y) {
        return (x - scx) * (x - scx) + (y - scy) * (y - scy) <= scR * scR;
    }

    /** A line clipped to the scope's square (Liang-Barsky); nothing when it lies outside. */
    void scopeLine(float x0, float y0, float x1, float y1, int rgb, float w) {
        float[] p = {-(x1 - x0), x1 - x0, -(y1 - y0), y1 - y0};
        float[] q = {x0 - (scx - scR), scx + scR - x0, y0 - (scy - scR), scy + scR - y0};
        float t0 = 0, t1 = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return;
                }
                continue;
            }
            float r = q[i] / p[i];
            if (p[i] < 0) {
                t0 = Math.max(t0, r);
            } else {
                t1 = Math.min(t1, r);
            }
        }
        if (t0 > t1) {
            return;
        }
        line(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0, x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1, rgb, w);
    }

    private void traffic(NBTTagCompound s, NBTTagCompound h, boolean me) {
        scR = 108;
        scx = left + 6 + scR;
        scy = top + 36 + scR;
        sppb = scR / (float) RANGES[zoom];
        box(6, 36, 6 + 2 * scR, 36 + 2 * scR, 0xFF0A0F0C);
        for (int k = 1; k <= 3; k++) {
            circle(scx, scy, scR * k / 3F, 0x1F4A2A);
        }
        text(tr("range", com.sva.airport.AptScale.kmText(RANGES[zoom], ',')), 10, 38, MUTED);
        // wave A (A9): the coverage of the tower's radars at the chosen height, clipped to the scope's square
        int[] blk = h.getIntArray("Blk");
        if (cov > 0 && blk.length == 3) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            net.minecraft.client.gui.ScaledResolution res = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            int f = res.getScaleFactor();
            GL11.glScissor((left + 6) * f, mc.displayHeight - (top + 36 + 2 * scR) * f, 2 * scR * f, 2 * scR * f);
            NBTTagList rd = s.getTagList("Rd", 10);
            for (int i = 0; i < rd.tagCount(); i++) {
                CoverClientSVA.draw(rd.getCompoundTagAt(i).getString("Key"), cov, scx, scy, blk[0] + 0.5, blk[2] + 0.5, 1 / sppb);
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            text(rd.tagCount() == 0 ? Lang.tr("sva.ad.cov.none") : Lang.tr("sva.ad.cov.legend"), 10, 48, MUTED);
        }
        button(B_ZOOM, "-1", 6, 36 + 2 * scR - 13, 22, 36 + 2 * scR - 1, "-", zoom < RANGES.length - 1, false);
        button(B_ZOOM, "1", 24, 36 + 2 * scR - 13, 40, 36 + 2 * scR - 1, "+", zoom > 0, false);
        button(B_COV, "", 42, 36 + 2 * scR - 13, 112, 36 + 2 * scR - 1, cov > 0 ? Lang.tr("sva.ad.cov.on", Integer.valueOf(cov)) : Lang.tr("sva.ad.cov.off"),
                true, cov > 0);
        // wave A (A10): the IFF alarm - an aircraft near the airport without a proper transponder reply
        if (h.getBoolean("IffAlarm") && ticks / 10 % 2 == 0) {
            text(tr("iffAlarm"), 10, 58, RED);
        }
        NBTTagList il = s.getTagList("Iff", 11);
        int iy = 36 + 2 * scR - 26;
        for (int i = 0; i < il.tagCount() && i < 3; i++) {
            int[] e = il.func_150306_c(i);
            if (e.length < 4 || !com.sva.airdefense.IffMath.intruder(e[1]) && e[1] != com.sva.airdefense.IffMath.ANSWERED) {
                continue;
            }
            String call = "?";
            NBTTagList trl = s.getTagList("Tr", 10);
            for (int k = 0; k < trl.tagCount(); k++) {
                if (trl.getCompoundTagAt(k).getInteger("Id") == e[0]) {
                    call = trl.getCompoundTagAt(k).getString("C");
                }
            }
            String line = tr("iffLine", call.length() > 0 ? call : "?", Lang.tr("sva.iff.reason." + com.sva.airdefense.IffMath.REASON_KEYS[Math.max(0, Math.min(3, e[2]))]),
                    Lang.tr("sva.iff.state." + com.sva.airdefense.IffMath.STATE_KEYS[Math.max(0, Math.min(4, e[1]))]))
                    + (e[3] > 0 ? " " + e[3] + " " + tr("sec") : "");
            text(fontRendererObj.trimStringToWidth(line, 2 * scR - 8), 10, iy, e[1] == com.sva.airdefense.IffMath.ANSWERED ? AMBER : RED);
            iy -= 10;
        }
        runwaysOnScope(s, h);
        // the approaches' finals
        NBTTagList al = s.getTagList("Ap", 10);
        for (int i = 0; i < al.tagCount(); i++) {
            int[] p = al.getCompoundTagAt(i).getIntArray("P");
            if (p.length >= 9) {
                scopeLine(sx(p[0], h), sz(p[1], h), sx(p[6], h), sz(p[7], h), 0x803A80, 1F);
            }
        }
        // the targets
        NBTTagList tl = s.getTagList("Tr", 10);
        for (int i = 0; i < tl.tagCount(); i++) {
            NBTTagCompound e = tl.getCompoundTagAt(i);
            float x = sx(e.getDouble("X"), h), y = sz(e.getDouble("Z"), h);
            if (!inScope(x, y)) {
                continue;
            }
            boolean xp = (e.getInteger("Src") & TowerScreenSVA.SRC_XPDR) != 0;
            int c = xp ? GREEN : AMBER;
            boolean intruder = com.sva.airdefense.IffMath.intruder(e.getByte("Iff"));
            if (intruder) {
                c = RED;                                            // wave A: an IFF intruder
                if (ticks / 5 % 2 == 0) {
                    circle(x, y, 6, RED);
                }
                fontRendererObj.drawString(tr("intruder"), (int) x + 5, (int) y + 18, RED);
            }
            if (e.getInteger("Id") == sel) {
                c = CYAN;
            }
            Gui.drawRect((int) x - 2, (int) y - 2, (int) x + 2, (int) y + 2, 0xFF000000 | c);
            double crs = Math.toRadians(e.getInteger("Crs"));
            line(x, y, x + (float) Math.sin(crs) * 8, y - (float) Math.cos(crs) * 8, c, 1F);
            int alt = e.getInteger("Y");
            String call = e.getString("C").length() > 0 ? e.getString("C") : "?";
            fontRendererObj.drawString(call, (int) x + 5, (int) y - 9, c);
            fontRendererObj.drawString((alt >= 0 ? String.valueOf(alt) : "---") + " " + e.getInteger("Kmh"), (int) x + 5, (int) y, c);
            int sq = e.getInteger("Sq");
            if (sq >= 0) {
                fontRendererObj.drawString(XpdrMath.format(sq), (int) x + 5, (int) y + 9, sq == XpdrMath.EMERGENCY ? RED : MUTED);
            }
        }
        // the strips
        int sx0 = 232;
        text(tr("strips"), sx0, 36, GOLD);
        NBTTagList sl = s.getTagList("Sp", 10);
        boolean selFound = false;
        for (int i = 0; i < Math.min(7, sl.tagCount()); i++) {
            NBTTagCompound e = sl.getCompoundTagAt(i);
            int id = e.getInteger("Id");
            int y = 46 + i * 20;
            boolean on = id == sel;
            selFound |= on;
            box(sx0, y, W - 6, y + 18, on ? 0xFF243848 : PANEL);
            box(sx0, y, sx0 + 3, y + 18, e.getBoolean("Gnd") ? 0xFF8A6A30 : 0xFF3070A0);
            text(e.getString("C"), sx0 + 6, y + 1, TEXT);
            int ty = e.getInteger("Ty");
            String tn = Lang.tr("item.siliconagevehicles.vehicle." + VehicleType.values()[Math.max(0, Math.min(VehicleType.values().length - 1, ty))].key
                    + ".name");
            text(fontRendererObj.trimStringToWidth(tn, 100), sx0 + 6, y + 10, MUTED);
            int sq = e.getInteger("Sq");
            text(sq >= 0 ? XpdrMath.format(sq) : "----", sx0 + 74, y + 1, MUTED);
            int rq = e.getInteger("Rq"), st = e.getInteger("St");
            if (rq != AutoTower.R_NONE) {
                text(tr("req." + rq), sx0 + 110, y + 1, AMBER);
            }
            text(tr("st." + AutoTower.STATE_KEYS[Math.max(0, Math.min(AutoTower.STATE_KEYS.length - 1, st))]), sx0 + 110, y + 10, GREEN);
            if (e.getInteger("Op") > 0) {
                text("*", W - 14, y + 1, AMBER);
            }
            btns.add(new Btn(B_STRIP, left + sx0, top + y, left + W - 6, top + y + 18, true, String.valueOf(id)));
        }
        if (!selFound) {
            sel = sl.tagCount() > 0 && sel < 0 ? sel : selFound ? sel : -1;
        }
        // the clearances (the controller only)
        int by = 190;
        boolean on = me && sel >= 0;
        String[][] up = {{"0", "taxi"}, {"1", "lineup"}, {"2", "takeoff"}, {"3", "land"}, {"4", "goAround"}, {"12", "holdShort"}, {"10", "stby"},
            {"11", "unable"}};
        for (int i = 0; i < up.length; i++) {
            int col = i % 4, row = i / 4;
            button(B_UP, up[i][0], sx0 + col * 34, by + row * 16, sx0 + col * 34 + 33, by + row * 16 + 14, tr("up." + up[i][1]), on, false);
        }
        text(tr("altLbl"), 372, 184, MUTED);
        button(B_UP, "5", 404, 193, 420, 205, "+", on, false);
        button(B_UP, "6", 420, 193, 436, 205, "-", on, false);
        text(tr("hdgLbl"), 372, 208, MUTED);
        button(B_UP, "7", 404, 217, 436, 229, ">", on, false);
        button(B_REPORT, "", sx0, 227, sx0 + 98, 241, tr("rep", Lang.tr("sva.cpdlc.rep." + report)), on, false);
        button(B_UP, "8", sx0 + 100, 227, sx0 + 134, 241, tr("send"), on, false);
        button(B_UP, "9", 404, 245, 436, 259, tr("send"), on, false);
        // audit 2026-10-22 (bug 5): the controller gives the airport's code by hand (any aircraft; the tower on its own only
        // gives it to its owner's, his access list's and its trusted owners' aircraft)
        button(B_UP, String.valueOf(Cpdlc.U_SQUAWK), 372, 231, 436, 243, tr("up.squawk"), on, false);
        if (!me) {
            text(tr("seatHint"), sx0, 262, MUTED);
        }
    }

    private void runwaysOnScope(NBTTagCompound s, NBTTagCompound h) {
        NBTTagList rl = s.getTagList("Rw", 10);
        for (int i = 0; i < rl.tagCount(); i++) {
            NBTTagCompound e = rl.getCompoundTagAt(i);
            float ax = sx(e.getDouble("Ax"), h), az = sz(e.getDouble("Az"), h), bx = sx(e.getDouble("Bx"), h), bz = sz(e.getDouble("Bz"), h);
            scopeLine(ax, az, bx, bz, 0xC8C8C8, Math.max(2F, e.getInteger("W") * sppb));
        }
    }

    // ---------------------------------------------------------------- the runways tab

    static String mode(int m) {
        return m == RunwayLayout.LIGHTS_TOWER ? tr("ltTower") : m == RunwayLayout.LIGHTS_AUTO ? tr("ltAuto") : m == 0 ? tr("ltOff") : String.valueOf(m);
    }

    private void runways(NBTTagCompound s, NBTTagCompound h) {
        NBTTagList rl = s.getTagList("Rw", 10);
        String inUse = h.getString("InUse"), sel0 = h.getString("Sel");
        int y = 36;
        text(tr("rwyHdr"), 8, y, GOLD);
        button(B_AUTO, "", 330, y - 2, 436, y + 10, tr("useAuto"), true, sel0.length() == 0);
        y += 14;
        for (int i = 0; i < rl.tagCount() && y < 160; i++) {
            NBTTagCompound e = rl.getCompoundTagAt(i);
            for (int d = 0; d < 2; d++) {
                String k = e.getString(d == 0 ? "Ka" : "Kb"), n = e.getString(d == 0 ? "Na" : "Nb");
                box(6, y, W - 6, y + 22, PANEL);
                text(n, 10, y + 2, TEXT);
                text(e.getInteger("Len") * 10 + " " + tr("m"), 10, y + 12, MUTED);
                int lm = e.getInteger(d == 0 ? "La" : "Lb");
                if (lm != RunwayLayout.LIGHTS_TOWER) {
                    text(tr("local", mode(lm)), 60, y + 12, AMBER);
                }
                NBTTagCompound st = e.getCompoundTag(d == 0 ? "Ia" : "Ib");
                if (st.hasKey("Id")) {
                    boolean off = st.getBoolean("Off");
                    int cat = st.getInteger("Cat"), fl = st.getInteger("F");
                    boolean dead = cat == 0 || (fl & (LinerIls.F_OFF | LinerIls.F_NO_POWER)) != 0;
                    text("ILS " + st.getString("Id") + " " + LinerIls.freqText(st.getInteger("Fq")).replace('.', ','), 60, y + 2, TEXT);
                    text(off ? tr("ilsOffTower") : dead ? tr("ilsDead") : IlsCat.catText(cat) + ((fl & LinerIls.F_UNCAL) != 0 ? " " + tr("uncal") : ""), 180,
                            y + 2, off || dead ? RED : cat >= 3 ? GREEN : AMBER);
                    button(B_ILS, st.getString("K") + "|" + (off ? "1" : "0"), 180, y + 11, 240, y + 21, off ? tr("ilsOn") : tr("ilsOff"), true, false);
                } else {
                    text(tr("noIls"), 60, y + 2, MUTED);
                }
                int[] rv = e.getIntArray(d == 0 ? "Ra" : "Rb");
                if (rv.length == 3 && rv[0] >= 0) {
                    text("RVR " + rv[0] + (rv[1] >= 0 ? "/" + rv[1] : "") + (rv[2] >= 0 ? "/" + rv[2] : ""), 250, y + 2, CYAN);
                }
                boolean use = k.equals(inUse);
                button(B_USE, k, 330, y + 4, 436, y + 18, use ? tr("inUse") : tr("use"), true, use);
                y += 24;
            }
        }
        // the lights
        int[] lt = h.getIntArray("Lt");
        y = 172;
        text(tr("lights"), 8, y, GOLD);
        String[] lk = {"ltRwy", "ltAppr", "ltTaxi"};
        for (int i = 0; i < 3 && lt.length == 3; i++) {
            int yy = y + 12 + i * 16;
            text(tr(lk[i]), 10, yy + 2, TEXT);
            button(B_LT, i + "|" + (lt[i] - 1), 110, yy, 126, yy + 13, "-", lt[i] > RunwayLayout.LIGHTS_AUTO, false);
            text(mode(lt[i]), 132, yy + 2, CYAN);
            button(B_LT, i + "|" + (lt[i] + 1), 170, yy, 186, yy + 13, "+", lt[i] < RunwayLayout.STEPS, false);
        }
        button(B_RESET, "", 10, 236, 186, 250, tr("reset"), true, false);
        // the weather
        int[] wx = s.getIntArray("Wx");
        text(tr("wx"), 230, y, GOLD);
        if (wx.length == 5 && wx[0] >= 0) {
            text(tr("wind", com.sva.airport.TowerMath.three(wx[0]), wx[1], wx[2]), 232, y + 14, TEXT);
            text(tr("qnh", wx[3]), 232, y + 26, TEXT);
            text(tr("temp", wx[4]), 232, y + 38, TEXT);
        } else {
            text(tr("noWx"), 232, y + 14, MUTED);
        }
    }

    // ---------------------------------------------------------------- ATIS

    static final LinerMcdu.Resolver LANG = new LinerMcdu.Resolver() {
        @Override
        public String tr(String key, String[] args) {
            return Lang.tr(key, (Object[]) args);
        }
    };

    private void atis(NBTTagCompound h) {
        int letter = h.getInteger("Letter");
        if (letter < 0 || h.getString("Atis").length() == 0) {
            text(tr("noAtis"), 10, 40, MUTED);
            return;
        }
        text(tr("atisHdr", h.getString("Icao"), Lang.tr("sva.atis.letter." + letter)), 10, 38, GOLD);
        int y = 54;
        for (String line : AtisMath.render(AtisMath.unpack(h.getString("Atis")), LANG)) {
            for (Object o : fontRendererObj.listFormattedStringToWidth(line, W - 24)) {
                text((String) o, 12, y, TEXT);
                y += 11;
            }
            y += 2;
        }
    }

    // ---------------------------------------------------------------- the map

    private void map(NBTTagCompound s, NBTTagCompound h) {
        scR = 112;
        scx = left + 8 + scR;
        scy = top + 36 + scR;
        int[] rng = {150, 400, 1000};
        sppb = scR / (float) rng[mapZoom];
        box(8, 36, 8 + 2 * scR, 36 + 2 * scR, 0xFF0C1014);
        button(B_MAPZOOM, "-1", 8, 36 + 2 * scR - 13, 24, 36 + 2 * scR - 1, "-", mapZoom < rng.length - 1, false);
        button(B_MAPZOOM, "1", 26, 36 + 2 * scR - 13, 42, 36 + 2 * scR - 1, "+", mapZoom > 0, false);
        runwaysOnScope(s, h);
        NBTTagList al = s.getTagList("Ap", 10);
        for (int i = 0; i < al.tagCount(); i++) {
            int[] p = al.getCompoundTagAt(i).getIntArray("P");
            // IF - FAF - the threshold (magenta), the missed approach and its return / hold fix (blue)
            for (int k = 0; k + 5 < p.length; k += 3) {
                scopeLine(sx(p[k], h), sz(p[k + 1], h), sx(p[k + 3], h), sz(p[k + 4], h), k < 6 ? MAGENTA : 0x4A7AD8, k < 6 ? 1.5F : 1F);
            }
        }
        int[] tn = s.getIntArray("Tn"), te2 = s.getIntArray("Te");
        for (int k = 0; k + 1 < te2.length; k += 2) {
            int a = te2[k], b = te2[k + 1];
            if (a * 3 + 2 < tn.length && b * 3 + 2 < tn.length) {
                scopeLine(sx(tn[a * 3] + 0.5, h), sz(tn[a * 3 + 1] + 0.5, h), sx(tn[b * 3] + 0.5, h), sz(tn[b * 3 + 1] + 0.5, h), 0x406040, 1F);
            }
        }
        NBTTagList rts = s.getTagList("Tr2", 11);
        for (int r = 0; r < rts.tagCount(); r++) {
            int[] ra = rts.func_150306_c(r);
            for (int k = 2; k < ra.length; k++) {
                int a = ra[k - 1], b = ra[k];
                if (a * 3 + 2 < tn.length && b * 3 + 2 < tn.length) {
                    scopeLine(sx(tn[a * 3] + 0.5, h), sz(tn[a * 3 + 1] + 0.5, h), sx(tn[b * 3] + 0.5, h), sz(tn[b * 3 + 1] + 0.5, h), 0x40FF60, 2F);
                }
            }
        }
        NBTTagList keys = s.getTagList("Tk", 8);
        for (int k = 0; k * 3 + 2 < tn.length; k++) {
            float x = sx(tn[k * 3] + 0.5, h), y = sz(tn[k * 3 + 1] + 0.5, h);
            if (Math.abs(x - scx) > scR - 2 || Math.abs(y - scy) > scR - 2) {
                continue;
            }
            int kind = tn[k * 3 + 2];
            int c = kind == TaxiGraph.STOP ? 0xFFF05050 : kind == TaxiGraph.STAND ? 0xFFF0D040 : 0xFF50D060;
            if (k == linkA) {
                c = 0xFF5EC8F0;
            }
            Gui.drawRect((int) x - 1, (int) y - 1, (int) x + 2, (int) y + 2, c);
            btns.add(new Btn(B_NODE, (int) x - 3, (int) y - 3, (int) x + 4, (int) y + 4, true, keys.getStringTagAt(k)));
        }
        NBTTagList tl = s.getTagList("Tr", 10);
        for (int i = 0; i < tl.tagCount(); i++) {
            NBTTagCompound e = tl.getCompoundTagAt(i);
            float x = sx(e.getDouble("X"), h), y = sz(e.getDouble("Z"), h);
            if (Math.abs(x - scx) <= scR && Math.abs(y - scy) <= scR) {
                Gui.drawRect((int) x - 2, (int) y - 2, (int) x + 2, (int) y + 2, 0xFF5EC8F0);
                fontRendererObj.drawString(e.getString("C"), (int) x + 4, (int) y - 4, CYAN);
            }
        }
        text(tr("mapHint"), 8, 262, MUTED);
        // the approaches and their charts
        int x0 = 248;
        text(tr("apprHdr", h.getInteger("Ver")), x0, 36, GOLD);
        for (int i = 0; i < Math.min(8, al.tagCount()); i++) {
            NBTTagCompound e = al.getCompoundTagAt(i);
            int y = 50 + i * 18;
            box(x0, y, W - 6, y + 16, PANEL);
            text(e.getString("Id"), x0 + 4, y + 4, MAGENTA);
            text(IlsCat.catText(e.getInteger("Cat")), x0 + 70, y + 4, MUTED);
            button(B_PRINT, e.getString("Id"), W - 70, y + 2, W - 8, y + 14, tr("print"), true, false);
        }
        if (al.tagCount() == 0) {
            text(tr("noAppr"), x0, 52, MUTED);
        }
        text(tr("paperHint"), x0, 200, MUTED);
    }

    // ---------------------------------------------------------------- the log

    private void log(NBTTagCompound s) {
        NBTTagList ll = s.getTagList("Lg", 10);
        text(tr("logHdr"), 8, 36, GOLD);
        if (ll.tagCount() == 0) {
            text(tr("logNone"), 10, 50, MUTED);
        }
        for (int i = 0; i < ll.tagCount(); i++) {
            Cpdlc.Msg m = TowerScreenSVA.msgOf(ll.getCompoundTagAt(i));
            int y = 48 + i * 13;
            if (y > H - 14) {
                break;
            }
            text(AtisMath.clock(m.sent) + "", 8, y, MUTED);
            text((m.up ? "> " : "< ") + m.call, 44, y, m.up ? CYAN : GREEN);
            String t = Cpdlc.text(m, LANG);
            text(fontRendererObj.trimStringToWidth(t, 270), 110, y, TEXT);
            text(Lang.tr("sva.cpdlc.state." + Cpdlc.STATE_KEYS[Math.max(0, Math.min(Cpdlc.STATE_KEYS.length - 1, m.state))]), 384, y,
                    m.open() ? AMBER : MUTED);
        }
    }

    // ---------------------------------------------------------------- settings

    private void settings(NBTTagCompound h) {
        if (!settingsLoaded) {
            settingsLoaded = true;
            sIcao.setText(h.getString("Icao"));
            sName.setText(h.getString("Name"));
            int[] c = h.getIntArray("Codes");
            if (c.length == 2) {
                sLo.setText(XpdrMath.format(c[0]));
                sHi.setText(XpdrMath.format(c[1]));
            }
            sAcc.setText(h.getString("Acc"));
            sSqTrust.setText(h.getString("SqTrust"));
        }
        boolean owner = h.getBoolean("Owner");
        text(tr("setHdr"), 8, 36, GOLD);
        text(tr("icao"), 10, 52, TEXT);
        text(tr("name"), 10, 70, TEXT);
        text(tr("codes"), 10, 88, TEXT);
        text(tr("access"), 10, 106, TEXT);
        text(tr("sqTrust"), 10, 124, TEXT);
        text(tr("owner", h.getString("Own")), 10, 142, MUTED);
        text(tr("energy", h.getInteger("E"), h.getInteger("EMax"), h.getInteger("Use")), 10, 154, h.getBoolean("Pow") ? TEXT : RED);
        text(tr("elev", h.getInteger("Elev")), 10, 166, MUTED);
        text(tr("charts", h.getInteger("Ver")), 10, 178, MUTED);
        button(B_SAVE, "", 10, 192, 120, 206, tr("save"), owner, false);
        button(B_EQUIP, "", 130, 192, 240, 206, tr("equip"), true, false);
        if (!owner) {
            text(tr("ownerOnly"), 10, 212, AMBER);
        }
        // audit review 2026-10-22: the hints fitted to the panel (the English «коды: доверенные» one ran 20 px past its edge)
        com.sc.inventory.TextFitSC.draw(fontRendererObj, tr("icaoHint"), left + 10, top + 226, W - 20, MUTED, false, 0, 0);
        com.sc.inventory.TextFitSC.draw(fontRendererObj, tr("sqTrustHint"), left + 10, top + 238, W - 20, MUTED, false, 0, 0);
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void mouseClicked(int mx, int my, int b) {
        super.mouseClicked(mx, my, b);
        for (GuiTextField f : visibleFields()) {
            f.mouseClicked(mx, my, b);
        }
        for (Btn x : new ArrayList<Btn>(btns)) {
            if (mx >= x.x0 && mx < x.x1 && my >= x.y0 && my < x.y1 && x.on) {
                click(x);
                mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(
                        new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
                return;
            }
        }
    }

    static int num(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void click(Btn x) {
        NBTTagCompound d = new NBTTagCompound();
        switch (x.id) {
            case B_TAB:
                tab = num(x.arg, 0);
                return;
            case B_SEAT:
                d.setString("Op", "seat");
                d.setBoolean("On", "1".equals(x.arg));
                cmd(d);
                return;
            case B_STRIP:
                sel = num(x.arg, -1);
                return;
            case B_ZOOM:
                zoom = Math.max(0, Math.min(RANGES.length - 1, zoom - num(x.arg, 0)));
                return;
            case B_COV:
                cov = com.sva.airdefense.CoverageMath.nextAlt(cov);
                covAsked = -1000;
                return;
            case B_MAPZOOM:
                mapZoom = Math.max(0, Math.min(2, mapZoom - num(x.arg, 0)));
                return;
            case B_REPORT:
                report = (report + 1) % Cpdlc.REPORTS;
                return;
            case B_UP: {
                int type = num(x.arg, -1);
                d.setString("Op", "up");
                d.setInteger("Ac", sel);
                if (x.arg.equals("5") || x.arg.equals("6")) {
                    d.setInteger("A", num(fAlt.getText(), 0));
                    if (num(fAlt.getText(), 0) <= 0) {
                        return;
                    }
                } else if (type == Cpdlc.U_HDG) {
                    d.setInteger("A", num(fHdg.getText(), -1));
                    if (num(fHdg.getText(), -1) < 0) {
                        return;
                    }
                } else if (type == Cpdlc.U_REPORT) {
                    d.setInteger("A", report);
                } else if (type == Cpdlc.U_FREE) {
                    if (fFree.getText().trim().length() == 0) {
                        return;
                    }
                    d.setString("S", fFree.getText());
                    fFree.setText("");
                }
                d.setInteger("Ty", type);
                cmd(d);
                return;
            }
            case B_ILS: {
                String[] p = x.arg.split("\\|");
                d.setString("Op", "ils");
                d.setString("K", p[0]);
                d.setBoolean("On", p.length > 1 && "1".equals(p[1]));
                cmd(d);
                return;
            }
            case B_USE:
                d.setString("Op", "rwy");
                d.setString("K", x.arg);
                cmd(d);
                return;
            case B_AUTO:
                d.setString("Op", "rwy");
                d.setString("K", "");
                cmd(d);
                return;
            case B_LT: {
                String[] p = x.arg.split("\\|");
                d.setString("Op", "lights");
                d.setInteger("W", num(p[0], 0));
                d.setInteger("M", num(p[1], RunwayLayout.LIGHTS_AUTO));
                cmd(d);
                return;
            }
            case B_RESET:
                cmd("reset");
                return;
            case B_PRINT:
                d.setString("Op", "print");
                d.setString("Ap", x.arg);
                cmd(d);
                return;
            case B_NODE: {
                NBTTagCompound s = snap();
                if (s == null) {
                    return;
                }
                NBTTagList keys = s.getTagList("Tk", 8);
                int k = -1;
                for (int i = 0; i < keys.tagCount(); i++) {
                    if (keys.getStringTagAt(i).equals(x.arg)) {
                        k = i;
                    }
                }
                if (linkA < 0 || linkA >= keys.tagCount()) {
                    linkA = k;
                } else {
                    if (k != linkA && k >= 0) {
                        d.setString("Op", "link");
                        d.setString("A", keys.getStringTagAt(linkA));
                        d.setString("B", x.arg);
                        cmd(d);
                    }
                    linkA = -1;
                }
                return;
            }
            case B_SAVE:
                d.setString("Op", "set");
                d.setString("Icao", sIcao.getText());
                d.setString("Name", sName.getText());
                d.setString("Lo", sLo.getText());
                d.setString("Hi", sHi.getText());
                d.setString("Acc", sAcc.getText());
                d.setString("SqTrust", sSqTrust.getText());
                cmd(d);
                return;
            case B_EQUIP:
                cmd("equip");
                return;
            default:
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        for (GuiTextField f : visibleFields()) {
            if (f.isFocused()) {
                f.textboxKeyTyped(c, key);
                return;
            }
        }
        if (key == 1 || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(null);
        }
    }
}
