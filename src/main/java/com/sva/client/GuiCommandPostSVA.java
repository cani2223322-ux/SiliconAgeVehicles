package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sva.airdefense.CodeOnlyMath;
import com.sva.airdefense.CodeOnlyUiMath;
import com.sva.airdefense.RadarMath;
import com.sva.airdefense.RoeMath;
import com.sva.airdefense.XpdrMath;
import com.sva.airdefense.ZoneMath;
import com.sva.net.NetADSVA;
import com.sva.vehicle.MapMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Air-defence wave (mock-ups veh_ad_4_command, veh_ad_5_zones_rules): the command post's screen - no container; the server
 * sends the picture every 10 ticks while it is open (TileEntityCommandPostSVA.snapshot), commands go back as NetADSVA
 * KP_CMD. Three tabs:
 * <ul>
 * <li>«Карта» - the tactical map (terrain, the zones, the radars, the launchers, every track: green own / friend, yellow
 * foreign, red violator, flashing red 7700, with its data block «code board / altitude speed course»; LMB selects, RMB
 * opens the menu: assign a launcher / «Авто (ближняя ПУ)» / «Только вести» / «Свой на 10 минут» / «Сообщение пилоту»;
 * the wheel zooms, dragging pans), the selected track's form, the track list, the log, ТРЕВОГА / Авто / «Карты кодов:
 * печать», the message to the selected pilot.</li>
 * <li>«Зоны» - the zone list and editor (name, rectangle / circle, type, coordinates, the height band, the aerodrome's
 * codes, the corridor's course, «показывать пилотам», «тревога при входе»); LMB on the map - the circle's centre / the
 * rectangle's first corner, RMB - its second corner.</li>
 * <li>«Настройки» - the post's name, the day key and its secret (the owner), the own codes (up to 8), the Mode S white list
 * («23:Имя, 07»), the access list (the owner).</li>
 * <li>«Опознавание» (code-only wave, proposal-ad-strict-iff items 24, 27, 32): ОБЫЧНОЕ / ПО КОДУ (switching on opens the
 * confirmation window - the summary «ПУ: 2 - по нарушителям, 1 - по всем; в воздухе без кода: 3, из них ваших: 1», «Все ПУ:
 * по всем без кода», «Включить» - the second step), the cards' term 1 / 3 / 7 periods, the options «Свои коды действуют» /
 * «Ручной набор кода» / «Коды аэродрома - свои» / «Доверить ПУ списку доступа», «Сменить ключ» in two presses, the readiness
 * line; the owner's alone.</li>
 * <li>«Оборудование» (upgrades wave) - not a page here: it asks the server to open the post's equipment screen
 * (GuiAdEquipSVA - a container: energy, tier, the upgrade slots, the inventory), whose «К экрану КП» comes back.</li>
 * </ul>
 * Code-only wave on the map (items 38-40): the header plate «ПО КОДУ - смена ключа через 12 мин» / «ВКЛЮЧЕНИЕ ЧЕРЕЗ 45 с» /
 * forbidden, the readiness line under the map, each track's tag («КОД ОК», «НЕТ КАРТЫ», «НЕТ ОТВ», «ПРОВ 1/2», «7700 1:20»,
 * «ПОДБОР» - words: the font has no tick or cross) with the grey ВЛ / ДР, the form's «Опознав.» row (status - reason; whose
 * board), the 7700 decision rows beside the 7600 ones (AdLayoutMath.nrButtons), «7700: свой / проверить / враждебный» and
 * «Снять ПОДБОР» in the track's menu, the new log lines coloured (CodeOnlyUiMath - pure, self-tested).
 */
public class GuiCommandPostSVA extends GuiScreen {

    private static final int W = 420, H = 266, MAP_X = 6, MAP_Y = 32, MAP = 204, RX = 216, RW = 198;
    /** Review 2026-10-27 (batch 3): the readiness line's row under the map - below the zoom buttons (MAP + 3, 12 high). */
    static final int READY_DY = 17;
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, LINE = 0xFF3A4150, PANEL = 0xFF14171D, GOLD = 0xF2A040, TEXT = 0xD8DCE4,
            MUTED = 0x8A93A3, AMBER = 0xF0B040, RED = 0xF06060, GREEN = 0x62E06A, CYAN = 0x5EC8F0, UNKNOWN = 0xFF101318;
    private static final int B_TAB = 1, B_ALARM = 10, B_AUTO = 11, B_PRINT = 12, B_SEND = 13, B_ZOOM_OUT = 14, B_ZOOM_IN = 15,
            B_Z_SAVE = 20, B_Z_NEW = 21, B_Z_DEL = 22, B_Z_SHAPE = 23, B_Z_TYPE = 24, B_Z_SHOW = 28, B_Z_ALARM = 29, B_Z_HERE = 30,
            B_S_SAVE = 40, B_S_KEY = 41, B_S_SECRET = 42, B_S_NEWSECRET = 43,
            /** Wave A: the coverage map's height, the zone editor's snap / copy / paste / undo. */
            B_COV = 50, B_Z_SNAP = 51, B_Z_COPY = 52, B_Z_PASTE = 53, B_Z_UNDO = 54,
            /** Code-only wave: the «Опознавание» tab - the mode (ОБЫЧНОЕ / ПО КОДУ), the cards' term (3), the options (4), the key, all launchers. */
            B_CO_NORMAL = 60, B_CO_CODE = 61, B_CO_PER = 62, B_CO_OPT = 65, B_CO_REKEY = 69, B_CO_ALLROE = 70;
    /** Wave A: the zone the editor copied (kept while the game runs - pasted into another post's screen too). */
    private static ZoneMath.Zone clipboard;
    /** Wave A: the coverage map's height on the screens (-1 off; kept between openings). */
    private static int cov = -1;

    private static NBTTagCompound last;
    private static GuiCommandPostSVA open;

    private final int kx, ky, kz;
    private int tab, zoom = 2, left, top, selected = -1, listScroll, zoneSel = -1, zoneScroll;
    private double cx, cz;
    private boolean dragging, centred;
    private int dragX, dragY, pressX, pressY;
    private DynamicTexture terrain;
    private double builtX = Double.NaN, builtZ, builtScale;
    private int builtAt = -1000;
    /** The right-click menu: its place, the track it is for, its rows {label, op, launcher key}. */
    private int menuX = -1, menuY, menuTrack;
    private final List<String[]> menu = new ArrayList<String[]>();
    // the map tab
    private GuiTextField msgField;
    // the zone editor
    private final ZoneMath.Zone edit = new ZoneMath.Zone();
    private GuiTextField zName, zA, zB, zC, zD, zLo, zHi, zCodeLo, zCodeHi, zCourse, zRot;
    /** Wave A: the handle being dragged (ZoneEditMath.H_*), the zone and the mouse's world point when it went down; snap. */
    private int dragHandle = com.sva.airdefense.ZoneEditMath.H_NONE, covAsked = -1000;
    private ZoneMath.Zone dragOrig;
    private double dragSX, dragSZ;
    private boolean snap;
    /**
     * Wave A: the editor's undo stack. Audit 2026-10-22 (bug 13): each entry knows its zone's index (an undo goes back to
     * that zone), choosing another zone clears it, and the fields typed in / the type, show and alarm switches go in too.
     */
    private final com.sva.airdefense.ZoneEditMath.History hist = new com.sva.airdefense.ZoneEditMath.History();
    /** Bug 13: the zone field typed into since the last entry (its first change made one); null after any other edit. */
    private GuiTextField typed;
    /** Wave A: the 7600 decision panel's click areas {x0, y0, x1, y1, track id, 1 friend / 2 check}. */
    private final List<int[]> nrBtns = new ArrayList<int[]>();
    // settings
    private GuiTextField sName, sSecret, sWhite, sAccess, sTrust;
    private final GuiTextField[] sCodes = new GuiTextField[8];
    private boolean settingsLoaded, dayKey;
    private final List<GuiTextField> fields = new ArrayList<GuiTextField>();
    /**
     * Code-only wave: the confirmation window is open (item 27 - the second step of switching on); «Сменить ключ» pressed once
     * at (the player's ticks; -1 not); the options / the cards' term sent last and when (shown until the server's copy follows).
     */
    private boolean dialog;
    private long rekeyAt = -1, optsAt = -1000, perAt = -1000;
    private int optsSent, perSent;

    private GuiCommandPostSVA(int x, int y, int z) {
        kx = x;
        ky = y;
        kz = z;
        cx = x + 0.5;
        cz = z + 0.5;
    }

    /** The picture from the server: opens the screen ("Open") or refreshes the open one. */
    public static void receive(Minecraft mc, NBTTagCompound t) {
        int[] p = t.getIntArray("Pos");
        if (p.length != 3) {
            return;
        }
        if (t.getBoolean("Open")) {
            last = t;
            open = new GuiCommandPostSVA(p[0], p[1], p[2]);
            mc.displayGuiScreen(open);
            return;
        }
        if (t.getBoolean("Shut")) {
            // audit 2026-10-22: taken off the post's access list - the screen closes (no more pictures come)
            if (open != null && mc.currentScreen == open && open.kx == p[0] && open.ky == p[1] && open.kz == p[2]) {
                mc.displayGuiScreen(null);
            }
            return;
        }
        if (open != null && mc.currentScreen == open && open.kx == p[0] && open.ky == p[1] && open.kz == p[2]) {
            last = t;
        }
    }

    private void cmd(NBTTagCompound d) {
        NetADSVA.cmd(NetADSVA.KP_CMD, kx, ky, kz, 0, d);
    }

    private void cmd(String op, int id) {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", op);
        d.setInteger("Id", id);
        cmd(d);
    }

    @Override
    public void onGuiClosed() {
        NetADSVA.cmd(NetADSVA.KP_CLOSE, kx, ky, kz, 0, null);
        Keyboard.enableRepeatEvents(false);
        CoverClientSVA.release();
        if (terrain != null) {
            terrain.deleteGlTexture();
            terrain = null;
        }
        if (open == this) {
            open = null;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ layout

    private GuiTextField field(int x, int y, int w, int max) {
        GuiTextField f = new GuiTextField(fontRendererObj, x, y, w, 11);
        f.setMaxStringLength(max);
        fields.add(f);
        return f;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = (width - W) / 2;
        top = Math.max(1, (height - H) / 2);
        buttonList.clear();
        fields.clear();
        // code-only wave: five tabs (+ «Опознавание»), their widths by their words (CodeOnlyUiMath.tabs)
        int[][] tx = CodeOnlyUiMath.tabs();
        for (int i = 0; i < CodeOnlyUiMath.TABS; i++) {
            GuiCargoSVA.Dark b = new GuiCargoSVA.Dark(B_TAB + i, left + tx[i][0], top + 18, tx[i][1], 12, Lang.tr("sva.ad.kp.tab." + CodeOnlyUiMath.TAB_KEYS[i]),
                    null);
            b.tab = true;
            buttonList.add(b);
        }
        int rx = left + RX;
        if (CodeOnlyUiMath.mapTab(tab)) {
            // wave A: the coverage map's height (off / 20 / 60 / 120), in the map's top right corner
            buttonList.add(new GuiCargoSVA.Dark(B_COV, left + MAP_X + MAP - 66, top + MAP_Y + 2, 64, 11, "", Lang.tr("sva.ad.cov.tip")));
        }
        if (tab == 0) {
            buttonList.add(new GuiCargoSVA.Dark(B_ZOOM_OUT, left + MAP_X, top + MAP_Y + MAP + 3, 12, 12, "-", null));
            buttonList.add(new GuiCargoSVA.Dark(B_ZOOM_IN, left + MAP_X + 14, top + MAP_Y + MAP + 3, 12, 12, "+", null));
            buttonList.add(new GuiCargoSVA.Dark(B_ALARM, rx, top + 226, 62, 13, Lang.tr("sva.ad.kp.alarm"), Lang.tr("sva.ad.kp.alarm.tip")));
            buttonList.add(new GuiCargoSVA.Dark(B_AUTO, rx + 66, top + 226, 62, 13, "", Lang.tr("sva.ad.kp.auto.tip")));
            buttonList.add(new GuiCargoSVA.Dark(B_PRINT, rx + 132, top + 226, 66, 13, Lang.tr("sva.ad.kp.print"), Lang.tr("sva.ad.kp.print.tip")));
            msgField = field(rx, top + 244, RW - 52, 80);
            buttonList.add(new GuiCargoSVA.Dark(B_SEND, rx + RW - 48, top + 243, 48, 13, Lang.tr("sva.ad.kp.send"), Lang.tr("sva.ad.kp.send.tip")));
        } else if (tab == 1) {
            zName = field(rx + 58, top + 128, 70, 12);
            buttonList.add(new GuiCargoSVA.Dark(B_Z_SHAPE, rx + 132, top + 127, 66, 13, "", null));
            for (int i = 0; i < ZoneMath.TYPES; i++) {
                buttonList.add(new GuiCargoSVA.Dark(B_Z_TYPE + i, rx + i * 50, top + 142, 48, 13, Lang.tr("sva.ad.zone.type." + ZoneMath.TYPE_KEYS[i]), null));
            }
            zA = field(rx + 22, top + 158, 50, 7);
            zB = field(rx + 98, top + 158, 50, 7);
            zC = field(rx + 22, top + 172, 50, 7);
            zD = field(rx + 98, top + 172, 50, 7);
            zLo = field(rx + 58, top + 186, 30, 3);
            zHi = field(rx + 108, top + 186, 30, 3);
            zRot = field(rx + 166, top + 186, 30, 3);
            buttonList.add(new GuiCargoSVA.Dark(B_Z_SNAP, left + MAP_X + 2, top + MAP_Y + 2, 64, 11, "", Lang.tr("sva.ad.zone.snap.tip")));
            zCodeLo = field(rx + 58, top + 200, 30, 4);
            zCodeHi = field(rx + 92, top + 200, 30, 4);
            zCourse = field(rx + 166, top + 200, 30, 3);
            buttonList.add(new GuiCargoSVA.Dark(B_Z_SHOW, rx, top + 214, 96, 13, Lang.tr("sva.ad.zone.show"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_ALARM, rx + 100, top + 214, 98, 13, Lang.tr("sva.ad.zone.alarm"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_SAVE, rx, top + 230, 64, 13, Lang.tr("sva.ad.zone.save"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_NEW, rx + 67, top + 230, 64, 13, Lang.tr("sva.ad.zone.new"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_DEL, rx + 134, top + 230, 64, 13, Lang.tr("sva.ad.zone.delete"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_HERE, rx, top + 246, 48, 13, Lang.tr("sva.ad.zone.here"), Lang.tr("sva.ad.zone.here.tip")));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_COPY, rx + 50, top + 246, 48, 13, Lang.tr("sva.ad.zone.copy"), Lang.tr("sva.ad.zone.copy.tip")));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_PASTE, rx + 100, top + 246, 48, 13, Lang.tr("sva.ad.zone.paste"), Lang.tr("sva.ad.zone.paste.tip")));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_UNDO, rx + 150, top + 246, 48, 13, Lang.tr("sva.ad.zone.undo"), Lang.tr("sva.ad.zone.undo.tip")));
            loadZone(zoneSel);
        } else if (tab == CodeOnlyUiMath.TAB_SETTINGS) {
            sName = field(left + 66, top + 40, 120, 24);
            buttonList.add(new GuiCargoSVA.Dark(B_S_KEY, left + 66, top + 56, 120, 13, "", Lang.tr("sva.ad.set.dayKey.tip")));
            sSecret = field(left + 66, top + 76, 120, 32);
            buttonList.add(new GuiCargoSVA.Dark(B_S_SECRET, left + 190, top + 75, 60, 13, Lang.tr("sva.ad.set.secret.set"), Lang.tr("sva.ad.set.secret.tip")));
            buttonList.add(new GuiCargoSVA.Dark(B_S_NEWSECRET, left + 254, top + 75, 60, 13, Lang.tr("sva.ad.set.secret.new"), null));
            for (int i = 0; i < 8; i++) {
                sCodes[i] = field(left + 66 + (i % 4) * 44, top + 106 + i / 4 * 15, 36, 4);
            }
            sWhite = field(left + 66, top + 150, W - 76, 200);
            sAccess = field(left + 66, top + 180, W - 76, 300);
            sTrust = field(left + 66, top + 230, W - 76, 200);
            buttonList.add(new GuiCargoSVA.Dark(B_S_SAVE, left + W - 106, top + 210, 100, 14, Lang.tr("sva.ad.set.save"), null));
            settingsLoaded = false;
        } else if (tab == CodeOnlyUiMath.TAB_IDENT) {
            initIdent();
        }
    }

    /** Code-only wave (item 24): the «Опознавание» tab's buttons. */
    private void initIdent() {
        int x = left + 74;
        // review 2026-10-27 (batch 2): the arming time and the guessing guard's numbers as the server set them (synced)
        String modeTip = Lang.tr("sva.ad.co.set.mode.tip", Integer.valueOf(com.sva.net.ServerConfigSVA.adCodeOnlyArmSeconds()));
        buttonList.add(new GuiCargoSVA.Dark(B_CO_NORMAL, x, top + 50, 80, 13, Lang.tr("sva.ad.co.set.modeNormal"), modeTip));
        buttonList.add(new GuiCargoSVA.Dark(B_CO_CODE, x + 84, top + 50, 80, 13, Lang.tr("sva.ad.co.set.modeCode"), modeTip));
        String perTip = Lang.tr("sva.ad.co.set.periods.tip", Integer.valueOf(com.sva.net.ServerConfigSVA.adCodeCardMaxPeriods()));
        for (int i = 0; i < CodeOnlyMath.CARD_PERIODS.length; i++) {
            buttonList.add(new GuiCargoSVA.Dark(B_CO_PER + i, x + i * 84, top + 70, 80, 13, Lang.tr("sva.ad.co.set.period." + CodeOnlyMath.CARD_PERIODS[i]),
                    perTip));
        }
        for (int i = 0; i < CodeOnlyMath.OPT_KEYS.length; i++) {
            String k = "sva.ad.co.set.opt." + CodeOnlyMath.OPT_KEYS[i];
            buttonList.add(new GuiCargoSVA.Dark(B_CO_OPT + i, x + i % 2 * 168, top + 90 + i / 2 * 16, 164, 13, Lang.tr(k),
                    Lang.tr(k + ".tip", Integer.valueOf(com.sva.net.ServerConfigSVA.adCodeOnlyProbeCodes()),
                            Integer.valueOf(com.sva.net.ServerConfigSVA.adCodeOnlyProbeMinutes()))));
        }
        buttonList.add(new GuiCargoSVA.Dark(B_CO_REKEY, left + W - 8 - 124, top + 128, 124, 13, "", Lang.tr("sva.ad.co.set.rekey.tip")));
        buttonList.add(new GuiCargoSVA.Dark(B_CO_ALLROE, x, top + 146, 164, 13, Lang.tr("sva.ad.co.set.allRoe"), Lang.tr("sva.ad.co.set.allRoe.tip")));
    }

    private void setTab(int t) {
        tab = t;
        menuX = -1;
        dialog = false;
        rekeyAt = -1;
        initGui();
    }

    // ------------------------------------------------------------------ helpers

    private NBTTagList tracks() {
        return last == null ? new NBTTagList() : last.getTagList("Tracks", 10);
    }

    private NBTTagCompound track(int id) {
        NBTTagList l = tracks();
        for (int i = 0; i < l.tagCount(); i++) {
            if (l.getCompoundTagAt(i).getInteger("Id") == id) {
                return l.getCompoundTagAt(i);
            }
        }
        return null;
    }

    private List<ZoneMath.Zone> zones() {
        List<ZoneMath.Zone> out = new ArrayList<ZoneMath.Zone>();
        if (last != null) {
            NBTTagList l = last.getTagList("Zones", 10);
            for (int i = 0; i < l.tagCount(); i++) {
                ZoneMath.Zone z = new ZoneMath.Zone();
                z.read(l.getCompoundTagAt(i));
                out.add(z);
            }
        }
        return out;
    }

    private double scale() {
        return MapMath.ZOOMS[zoom];
    }

    private static String code(int c) {
        return c < 0 ? "----" : XpdrMath.format(c);
    }

    private static String alt(NBTTagCompound t) {
        return t.getBoolean("Alt") ? String.format("%03d", Integer.valueOf((int) Math.round(t.getDouble("Y")))) : "---";
    }

    private static int clsColour(NBTTagCompound t, long tick) {
        int cls = t.getByte("Cls");
        if (cls == RoeMath.EMERGENCY && tick / 5 % 2 == 0) {
            return 0x801818;
        }
        return RoeMath.CLASS_COLOURS[Math.max(0, Math.min(RoeMath.CLASSES - 1, cls))];
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id >= B_TAB && b.id < B_TAB + CodeOnlyUiMath.TAB_EQUIP) {
            setTab(b.id - B_TAB);
            return;
        }
        if (b.id == B_TAB + CodeOnlyUiMath.TAB_EQUIP) {
            NetADSVA.cmd(NetADSVA.AD_EQUIP, kx, ky, kz, 0, null);     // the server opens the equipment screen (a container)
            return;
        }
        switch (b.id) {
            case B_ZOOM_OUT:
                zoom = MapMath.zoomStep(zoom, 1);
                return;
            case B_ZOOM_IN:
                zoom = MapMath.zoomStep(zoom, -1);
                return;
            case B_COV:
                cov = com.sva.airdefense.CoverageMath.nextAlt(cov);
                covAsked = -1000;
                return;
            case B_Z_SNAP:
                snap = !snap;
                return;
            case B_Z_COPY:
                readZoneFields();
                clipboard = edit.copy();
                return;
            case B_Z_PASTE:
                if (clipboard != null) {
                    readZoneFields();
                    pushEdit();
                    setEdit(com.sva.airdefense.ZoneEditMath.pasteAt(clipboard, cx, cz, snap));
                    zoneSel = -1;                                   // a new zone: «Сохранить» adds it
                    hist.moveTo(-1);
                }
                return;
            case B_Z_UNDO: {
                // bug 13: back to the zone the entry was made for (a paste's undo: the zone it was pasted over)
                com.sva.airdefense.ZoneEditMath.History.Entry u = hist.undoEntry();
                if (u != null) {
                    zoneSel = u.zone < zones().size() ? u.zone : -1;
                    hist.moveTo(zoneSel);
                    setEdit(u.before);
                    typed = null;
                }
                return;
            }
            case B_ALARM:
                cmd("alarm", 0);
                return;
            case B_AUTO:
                cmd("auto", 0);
                return;
            case B_PRINT:
                cmd("print", 0);
                return;
            case B_SEND:
                sendMessage();
                return;
            case B_Z_SHAPE:
                readZoneFields();
                pushEdit();
                edit.rot = 0;
                edit.shape = edit.shape == ZoneMath.RECT ? ZoneMath.CIRCLE : ZoneMath.RECT;
                if (edit.shape == ZoneMath.CIRCLE && edit.r <= 1) {
                    edit.cx = (edit.x0 + edit.x1) / 2;
                    edit.cz = (edit.z0 + edit.z1) / 2;
                    edit.r = Math.max(8, (edit.x1 - edit.x0) / 2);
                }
                writeZoneFields();
                return;
            case B_Z_SHOW:
                readZoneFields();
                pushEdit();                                         // bug 13: the switches go into the history too
                edit.show = !edit.show;
                return;
            case B_Z_ALARM:
                readZoneFields();
                pushEdit();
                edit.alarm = !edit.alarm;
                return;
            case B_Z_HERE:
                readZoneFields();
                pushEdit();
                if (edit.shape == ZoneMath.CIRCLE) {
                    edit.cx = MathHelper.floor_double(cx);
                    edit.cz = MathHelper.floor_double(cz);
                } else {
                    int hw = Math.max(4, (edit.x1 - edit.x0) / 2), hd = Math.max(4, (edit.z1 - edit.z0) / 2);
                    edit.x0 = MathHelper.floor_double(cx) - hw;
                    edit.x1 = MathHelper.floor_double(cx) + hw;
                    edit.z0 = MathHelper.floor_double(cz) - hd;
                    edit.z1 = MathHelper.floor_double(cz) + hd;
                }
                writeZoneFields();
                return;
            case B_Z_SAVE: {
                readZoneFields();
                NBTTagCompound d = new NBTTagCompound();
                d.setString("Op", "zone");
                d.setInteger("Idx", zoneSel);
                NBTTagCompound z = new NBTTagCompound();
                edit.write(z);
                d.setTag("Z", z);
                cmd(d);
                if (zoneSel < 0) {
                    zoneSel = zones().size();
                    hist.saved(zoneSel);                            // bug 13: its entries made while it was new belong to it now
                }
                return;
            }
            case B_Z_NEW:
                zoneSel = -1;
                hist.clear();
                hist.select(-1);
                typed = null;
                loadZone(-1);
                return;
            case B_Z_DEL:
                if (zoneSel >= 0) {
                    NBTTagCompound d = new NBTTagCompound();
                    d.setString("Op", "zoneDel");
                    d.setInteger("Idx", zoneSel);
                    cmd(d);
                    zoneSel = -1;
                    hist.clear();                                   // bug 13: the indices after it move - nothing to go back to
                    hist.select(-1);
                    typed = null;
                    loadZone(-1);
                }
                return;
            case B_S_KEY:
                dayKey = !dayKey;
                return;
            case B_S_SECRET:
            case B_S_NEWSECRET: {
                NBTTagCompound d = new NBTTagCompound();
                d.setString("Op", "secret");
                d.setString("S", b.id == B_S_SECRET ? sSecret.getText() : "");
                cmd(d);
                sSecret.setText("");
                return;
            }
            case B_S_SAVE:
                saveSettings();
                return;
            case B_CO_NORMAL:
            case B_CO_CODE:
                modeButton(b.id == B_CO_CODE);
                return;
            case B_CO_REKEY: {
                // item 24: two presses within CONFIRM_TICKS - the second sends it («Sure»)
                long now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
                if (CodeOnlyUiMath.confirmed(rekeyAt, now)) {
                    NBTTagCompound d = new NBTTagCompound();
                    d.setString("Op", "rekey");
                    d.setBoolean("Sure", true);
                    cmd(d);
                    rekeyAt = -1;
                } else {
                    rekeyAt = now;
                }
                return;
            }
            case B_CO_ALLROE:
                cmd("coAllRoe", 0);
                return;
            default:
                if (b.id >= B_CO_PER && b.id < B_CO_PER + CodeOnlyMath.CARD_PERIODS.length) {
                    NBTTagCompound d = new NBTTagCompound();
                    d.setString("Op", "codeOpts");
                    perSent = CodeOnlyMath.CARD_PERIODS[b.id - B_CO_PER];
                    d.setInteger("Periods", perSent);
                    perAt = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
                    cmd(d);
                    return;
                }
                if (b.id >= B_CO_OPT && b.id < B_CO_OPT + CodeOnlyMath.OPT_KEYS.length) {
                    NBTTagCompound d = new NBTTagCompound();
                    d.setString("Op", "codeOpts");
                    optsSent = coOpts() ^ 1 << b.id - B_CO_OPT;
                    d.setInteger("Opts", optsSent);
                    optsAt = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
                    cmd(d);
                    return;
                }
                if (b.id >= B_Z_TYPE && b.id < B_Z_TYPE + ZoneMath.TYPES && edit.type != b.id - B_Z_TYPE) {
                    readZoneFields();
                    pushEdit();                                     // bug 13: a type change can be taken back
                    edit.type = b.id - B_Z_TYPE;
                }
        }
    }

    /**
     * Code-only wave (items 16, 27): «ПО КОДУ» while off opens the confirmation window (the server wants «Sure» - the second
     * step is its «Включить»); «ОБЫЧНОЕ» while on / arming switches it off at once.
     */
    private void modeButton(boolean code) {
        int co = last == null ? CodeOnlyMath.CO_OFF : last.getByte("Co");
        if (code && co == CodeOnlyMath.CO_OFF) {
            dialog = true;
            menuX = -1;
        } else if (!code && co != CodeOnlyMath.CO_OFF) {
            NBTTagCompound d = new NBTTagCompound();
            d.setString("Op", "codeOnly");
            d.setBoolean("On", false);
            cmd(d);
        }
    }

    /** Code-only wave: the post's options as shown - the ones just sent until the server's picture has them (30 ticks). */
    private int coOpts() {
        long now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
        return now - optsAt <= 30 ? optsSent : last == null ? CodeOnlyMath.DEFAULT_OPTS : last.getInteger("CoOpts");
    }

    /** Code-only wave: the cards' term as shown (the one just sent for 30 ticks, then the server's). */
    private int coPeriods() {
        long now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
        return now - perAt <= 30 ? perSent : last == null ? CodeOnlyMath.DEFAULT_CARD_PERIODS : last.getInteger("CardPeriods");
    }

    /** Bug 13: an undo entry of the zone as it is now, for the zone being edited (its list index; -1 a new one). */
    private void pushEdit() {
        hist.push(zoneSel, edit);
        typed = null;
    }

    private void sendMessage() {
        if (selected < 0 || msgField == null || msgField.getText().trim().length() == 0) {
            return;
        }
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", "msg");
        d.setInteger("Id", selected);
        d.setString("T", msgField.getText().trim());
        cmd(d);
        msgField.setText("");
    }

    private void saveSettings() {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("Op", "set");
        d.setString("Name", sName.getText());
        d.setBoolean("DayKey", dayKey);
        int[] codes = new int[8];
        for (int i = 0; i < 8; i++) {
            codes[i] = XpdrMath.parse(sCodes[i].getText());
        }
        d.setIntArray("Codes", codes);
        List<Integer> wb = new ArrayList<Integer>();
        NBTTagList wo = new NBTTagList();
        for (String e : sWhite.getText().split(",")) {
            String[] p = e.trim().split(":");
            try {
                int b = Integer.parseInt(p[0].trim().replace("B-", "").replace("Б-", ""));
                if (b > 0 && b < 100) {
                    wb.add(Integer.valueOf(b));
                    wo.appendTag(new NBTTagString(p.length > 1 ? p[1].trim() : ""));
                }
            } catch (NumberFormatException ex) {
                // not a board number - skipped
            }
        }
        int[] wa = new int[wb.size()];
        for (int i = 0; i < wa.length; i++) {
            wa[i] = wb.get(i).intValue();
        }
        d.setIntArray("WB", wa);
        d.setTag("WO", wo);
        if (last != null && last.getBoolean("Owner")) {
            NBTTagList al = new NBTTagList();
            for (String n : sAccess.getText().split(",")) {
                if (n.trim().length() > 0) {
                    al.appendTag(new NBTTagString(n.trim()));
                }
            }
            d.setTag("Access", al);
        }
        d.setString("Trust", sTrust.getText());
        cmd(d);
    }

    /** Wave A: the editor takes a zone (its NBT read - sanitised as the server will), the fields follow. */
    private void setEdit(ZoneMath.Zone z) {
        NBTTagCompound t = new NBTTagCompound();
        z.write(t);
        edit.read(t);
        writeZoneFields();
    }

    private void loadZone(int i) {
        List<ZoneMath.Zone> zs = zones();
        if (i >= 0 && i < zs.size()) {
            ZoneMath.Zone z = zs.get(i);
            NBTTagCompound t = new NBTTagCompound();
            z.write(t);
            edit.read(t);
        } else {
            NBTTagCompound t = new NBTTagCompound();
            new ZoneMath.Zone().write(t);
            edit.read(t);
            edit.name = "Z" + (zs.size() + 1);
            edit.cx = MathHelper.floor_double(cx);
            edit.cz = MathHelper.floor_double(cz);
            edit.r = 64;
            edit.x0 = edit.cx - 64;
            edit.x1 = edit.cx + 64;
            edit.z0 = edit.cz - 48;
            edit.z1 = edit.cz + 48;
        }
        writeZoneFields();
    }

    private void writeZoneFields() {
        if (zName == null) {
            return;
        }
        zName.setText(edit.name);
        boolean c = edit.shape == ZoneMath.CIRCLE;
        zA.setText(String.valueOf(c ? edit.cx : edit.x0));
        zB.setText(String.valueOf(c ? edit.cz : edit.z0));
        zC.setText(String.valueOf(c ? edit.r : edit.x1));
        zD.setText(c ? "" : String.valueOf(edit.z1));
        zLo.setText(String.valueOf(edit.lo));
        zHi.setText(String.valueOf(edit.hi));
        zCodeLo.setText(XpdrMath.format(edit.codeLo));
        zCodeHi.setText(XpdrMath.format(edit.codeHi));
        zCourse.setText(String.format("%03d", Integer.valueOf(edit.course)));
        zRot.setText(String.valueOf(edit.rot));
    }

    private static int num(GuiTextField f, int def) {
        try {
            return Integer.parseInt(f.getText().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void readZoneFields() {
        if (zName == null) {
            return;
        }
        edit.name = zName.getText().trim();
        if (edit.shape == ZoneMath.CIRCLE) {
            edit.cx = num(zA, edit.cx);
            edit.cz = num(zB, edit.cz);
            edit.r = Math.max(1, Math.min(ZoneMath.MAX_HALF, num(zC, edit.r)));
        } else {
            int x0 = num(zA, edit.x0), z0 = num(zB, edit.z0), x1 = num(zC, edit.x1), z1 = num(zD, edit.z1);
            edit.x0 = Math.min(x0, x1);
            edit.x1 = Math.max(x0, x1);
            edit.z0 = Math.min(z0, z1);
            edit.z1 = Math.max(z0, z1);
        }
        edit.lo = Math.max(0, Math.min(ZoneMath.ALT_MAX, num(zLo, edit.lo)));
        edit.hi = Math.max(edit.lo, Math.min(ZoneMath.ALT_MAX, num(zHi, edit.hi)));
        int cl = XpdrMath.parse(zCodeLo.getText()), ch = XpdrMath.parse(zCodeHi.getText());
        edit.codeLo = cl >= 0 ? cl : edit.codeLo;
        edit.codeHi = Math.max(edit.codeLo, ch >= 0 ? ch : edit.codeHi);
        edit.course = ((num(zCourse, edit.course) % 360) + 360) % 360;
        edit.rot = edit.shape == ZoneMath.RECT ? ((num(zRot, edit.rot) % 360) + 360) % 360 : 0;
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (dialog) {
            if (key == Keyboard.KEY_ESCAPE) {
                dialog = false;                                     // code-only wave: Esc closes the confirmation window first
            }
            return;
        }
        for (GuiTextField f : fields) {
            if (f.isFocused()) {
                if (key == Keyboard.KEY_ESCAPE) {
                    f.setFocused(false);
                    return;
                }
                if (key == Keyboard.KEY_RETURN && f == msgField) {
                    sendMessage();
                    return;
                }
                if (tab == 1 && f != msgField && zoneField(f)) {
                    // bug 13: a field's first change since the last entry makes one (the zone as it was before the typing)
                    readZoneFields();
                    ZoneMath.Zone pre = edit.copy();
                    String was = f.getText();
                    f.textboxKeyTyped(c, key);
                    if (!was.equals(f.getText()) && f != typed) {
                        hist.push(zoneSel, pre);
                        typed = f;
                    }
                    return;
                }
                f.textboxKeyTyped(c, key);
                return;
            }
        }
        if (key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(null);
        }
    }

    /** One of the zone editor's fields. */
    private boolean zoneField(GuiTextField f) {
        return f != null && (f == zName || f == zA || f == zB || f == zC || f == zD || f == zLo || f == zHi || f == zCodeLo || f == zCodeHi
                || f == zCourse || f == zRot);
    }

    private boolean overMap(int mx, int my) {
        return CodeOnlyUiMath.mapTab(tab) && mx >= left + MAP_X && mx < left + MAP_X + MAP && my >= top + MAP_Y && my < top + MAP_Y + MAP;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mx = Mouse.getEventX() * width / mc.displayWidth, my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            if (overMap(mx, my)) {
                zoom = MapMath.zoomStep(zoom, wheel > 0 ? -1 : 1);
            } else if (tab == 0) {
                listScroll = Math.max(0, listScroll + (wheel > 0 ? -1 : 1));
            } else if (tab == 1) {
                zoneScroll = Math.max(0, zoneScroll + (wheel > 0 ? -1 : 1));
            }
        }
    }

    /** A button under the mouse (the map's corner buttons lie over the map: they take the click). */
    private boolean onButton(int mx, int my) {
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.visible && mx >= b.xPosition && my >= b.yPosition && mx < b.xPosition + b.width && my < b.yPosition + b.height) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        if (dialog) {
            dialogClick(mx, my, button);                            // code-only wave: the confirmation window takes every click
            return;
        }
        if (tab == 0 && button == 0) {
            for (int[] b : nrBtns) {
                if (mx >= b[0] && mx < b[2] && my >= b[1] && my < b[3]) {
                    // wave A: the 7600 decision (+ «враждебный»); code-only wave: the 7700 one (op = row kind x 10 + button)
                    cmd(CodeOnlyUiMath.DEC_OPS[b[5] / 10][b[5] % 10], b[4]);
                    return;
                }
            }
        }
        if (menuX >= 0) {
            int i = (my - menuY - 2) / 11;
            if (mx >= menuX && mx < menuX + 130 && i >= 0 && i < menu.size() && my >= menuY + 2) {
                String[] row = menu.get(i);
                if ("assign".equals(row[1])) {
                    NBTTagCompound d = new NBTTagCompound();
                    d.setString("Op", "assign");
                    d.setInteger("Id", menuTrack);
                    d.setString("L", row[2]);
                    cmd(d);
                } else if ("msg".equals(row[1])) {
                    selected = menuTrack;
                    if (msgField != null) {
                        msgField.setFocused(true);
                    }
                } else {
                    cmd(row[1], menuTrack);
                }
            }
            menuX = -1;
            return;
        }
        for (GuiTextField f : fields) {
            f.mouseClicked(mx, my, button);
        }
        boolean onBtn = onButton(mx, my);
        super.mouseClicked(mx, my, button);
        if (overMap(mx, my) && !onBtn) {
            double s = scale(), ox = left + MAP_X + MAP / 2.0, oy = top + MAP_Y + MAP / 2.0;
            if (tab == 1) {
                readZoneFields();
                double[] w = MapMath.toWorld(mx - ox, my - oy, cx, cz, s);
                int bx = MathHelper.floor_double(w[0]), bz = MathHelper.floor_double(w[1]);
                // wave A: a handle under the mouse - dragged (corners, edges, the centre, the turn knob, the radius)
                int h = button == 0 ? com.sva.airdefense.ZoneEditMath.pick(edit, w[0], w[1], 5 * s) : com.sva.airdefense.ZoneEditMath.H_NONE;
                if (h != com.sva.airdefense.ZoneEditMath.H_NONE) {
                    pushEdit();
                    dragHandle = h;
                    dragOrig = edit.copy();
                    dragSX = w[0];
                    dragSZ = w[1];
                    return;
                }
                pushEdit();
                bx = com.sva.airdefense.ZoneEditMath.snap(bx, snap);
                bz = com.sva.airdefense.ZoneEditMath.snap(bz, snap);
                if (edit.shape == ZoneMath.CIRCLE && button == 0) {
                    edit.cx = bx;
                    edit.cz = bz;
                } else if (edit.shape == ZoneMath.CIRCLE && button == 1) {
                    edit.r = Math.max(1, Math.min(ZoneMath.MAX_HALF, (int) Math.round(Math.hypot(bx - edit.cx, bz - edit.cz))));
                } else if (button == 0) {
                    edit.x0 = bx;
                    edit.z0 = bz;
                    edit.rot = 0;
                } else {
                    edit.x1 = bx;
                    edit.z1 = bz;
                    edit.rot = 0;
                }
                setEdit(edit.copy());                               // sanitised (corners in order)
                return;
            }
            int hit = trackAt(mx, my);
            if (button == 0) {
                if (hit >= 0) {
                    selected = hit;
                } else {
                    dragging = true;
                    dragX = pressX = mx;
                    dragY = pressY = my;
                }
            } else if (button == 1 && hit >= 0) {
                openMenu(hit, mx, my);
            }
            return;
        }
        if (tab == 0) {
            int ly = top + 126, rx = left + RX;
            if (mx >= rx && mx < rx + RW && my >= ly && my < ly + 6 * 10) {
                int i = (my - ly) / 10 + listScroll;
                NBTTagList l = tracks();
                if (i >= 0 && i < l.tagCount()) {
                    selected = l.getCompoundTagAt(i).getInteger("Id");
                    if (button == 1) {
                        openMenu(selected, mx, my);
                    }
                }
            }
        } else if (tab == 1) {
            int ly = top + 34, rx = left + RX;
            if (mx >= rx && mx < rx + RW && my >= ly && my < ly + 8 * 11) {
                int i = (my - ly) / 11 + zoneScroll;
                if (i >= 0 && i < zones().size()) {
                    zoneSel = i;
                    hist.select(i);                                 // bug 13: another zone - its own history (the old one cleared)
                    typed = null;
                    loadZone(i);
                }
            }
        }
    }

    private void openMenu(int id, int mx, int my) {
        menu.clear();
        menuTrack = id;
        if (last != null) {
            NBTTagList ll = last.getTagList("Launchers", 10);
            for (int i = 0; i < ll.tagCount(); i++) {
                menu.add(new String[] {Lang.tr("sva.ad.kp.menu.assign", Integer.valueOf(i + 1)), "assign", ll.getCompoundTagAt(i).getString("Key")});
            }
        }
        menu.add(new String[] {Lang.tr("sva.ad.kp.menu.auto"), "assign", "auto"});
        menu.add(new String[] {Lang.tr("sva.ad.kp.menu.track"), "track", ""});
        menu.add(new String[] {Lang.tr("sva.ad.kp.menu.grant"), "grant", ""});
        NBTTagCompound tr = track(id);
        if (tr != null && tr.getByte("NR") >= 0) {
            menu.add(new String[] {Lang.tr("sva.ad.kp.menu.nrFriend"), "nrFriend", ""});    // wave A: 7600
            menu.add(new String[] {Lang.tr("sva.ad.kp.menu.nrCheck"), "nrCheck", ""});
            menu.add(new String[] {Lang.tr("sva.ad.kp.menu.nrHostile"), "nrHostile", ""});   // audit 2026-10-22 (bug 8)
        }
        if (tr != null && tr.getBoolean("Co") && tr.getByte("EM") >= 0) {
            // code-only wave (item 7): 7700 decisions - kept with its grace for all the owner's posts
            menu.add(new String[] {Lang.tr("sva.ad.kp.menu.emFriend"), "emFriend", ""});
            menu.add(new String[] {Lang.tr("sva.ad.kp.menu.emCheck"), "emCheck", ""});
            menu.add(new String[] {Lang.tr("sva.ad.kp.menu.emHostile"), "emHostile", ""});
        }
        if (tr != null && tr.getBoolean("Co") && tr.getByte("Idr") == RoeMath.ID_PROBE) {
            menu.add(new String[] {Lang.tr("sva.ad.kp.menu.unlock"), "unlock", ""});   // code-only wave (item 11): lift the block
        }
        menu.add(new String[] {Lang.tr("sva.ad.kp.menu.msg"), "msg", ""});
        menuX = Math.min(mx, left + W - 132);
        menuY = Math.min(my, top + H - menu.size() * 11 - 16);
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long time) {
        if (dragHandle != com.sva.airdefense.ZoneEditMath.H_NONE && button == 0 && dragOrig != null) {
            double s = scale(), ox = left + MAP_X + MAP / 2.0, oy = top + MAP_Y + MAP / 2.0;
            double[] w = MapMath.toWorld(mx - ox, my - oy, cx, cz, s);
            setEdit(com.sva.airdefense.ZoneEditMath.drag(dragOrig, dragHandle, dragSX, dragSZ, w[0], w[1], snap));
            return;
        }
        if (dragging && button == 0) {
            double s = scale();
            cx -= (mx - dragX) * s;
            cz -= (my - dragY) * s;
            dragX = mx;
            dragY = my;
        }
        super.mouseClickMove(mx, my, button, time);
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int button) {
        if (button == 0) {
            dragging = false;
            dragHandle = com.sva.airdefense.ZoneEditMath.H_NONE;
            dragOrig = null;
        }
        super.mouseMovedOrUp(mx, my, button);
    }

    /** The track under the mouse on the map (its entity id) or -1. */
    private int trackAt(int mx, int my) {
        double s = scale(), ox = left + MAP_X + MAP / 2.0, oy = top + MAP_Y + MAP / 2.0;
        NBTTagList l = tracks();
        int best = -1;
        double bd = 49;
        for (int i = 0; i < l.tagCount(); i++) {
            NBTTagCompound t = l.getCompoundTagAt(i);
            double[] p = MapMath.toMap(t.getDouble("X"), t.getDouble("Z"), cx, cz, s);
            double d = (ox + p[0] - mx) * (ox + p[0] - mx) + (oy + p[1] - my) * (oy + p[1] - my);
            if (d < bd) {
                bd = d;
                best = t.getInteger("Id");
            }
        }
        return best;
    }

    @Override
    public void updateScreen() {
        for (GuiTextField f : fields) {
            f.updateCursorCounter();
        }
        if (mc.thePlayer == null || mc.thePlayer.getDistanceSq(kx + 0.5, ky + 0.5, kz + 0.5) > 100) {
            mc.displayGuiScreen(null);
            return;
        }
        if (tab == 2 && !settingsLoaded && last != null) {
            settingsLoaded = true;
            sName.setText(last.getString("Name"));
            dayKey = last.getBoolean("DayKey");
            int[] c = last.getIntArray("Codes");
            for (int i = 0; i < 8; i++) {
                sCodes[i].setText(i < c.length && c[i] >= 0 ? XpdrMath.format(c[i]) : "");
            }
            int[] wb = last.getIntArray("WB");
            NBTTagList wo = last.getTagList("WO", 8);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < wb.length; i++) {
                b.append(b.length() > 0 ? ", " : "").append(String.format("%02d", Integer.valueOf(wb[i])));
                String o = i < wo.tagCount() ? wo.getStringTagAt(i) : "";
                if (o.length() > 0) {
                    b.append(':').append(o);
                }
            }
            sWhite.setText(b.toString());
            NBTTagList al = last.getTagList("Access", 8);
            StringBuilder a = new StringBuilder();
            for (int i = 0; i < al.tagCount(); i++) {
                a.append(a.length() > 0 ? ", " : "").append(al.getStringTagAt(i));
            }
            sAccess.setText(a.toString());
            sAccess.setEnabled(last.getBoolean("Owner"));
            sSecret.setEnabled(last.getBoolean("Owner"));
            sTrust.setText(last.getString("Trust"));
        }
        // wave A: the coverage maps asked for (again every 5 s - the server answers from its cache, recomputes when stale)
        int now = mc.thePlayer.ticksExisted;
        if (cov > 0 && CodeOnlyUiMath.mapTab(tab) && now - covAsked >= 100) {
            covAsked = now;
            NBTTagCompound d = new NBTTagCompound();
            d.setString("Op", "cov");
            d.setInteger("A", cov);
            cmd(d);
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int mx, int my, float pt) {
        TextFitSC.beginFrame();
        drawDefaultBackground();
        drawRect(left - 1, top - 1, left + W + 1, top + H + 1, EDGE);
        drawRect(left, top, left + W, top + H, BG);
        drawRect(left + 1, top + 1, left + W - 1, top + 2, LINE);
        drawRect(left + 3, top + 15, left + W - 3, top + 16, 0xFFE08A30);
        long tick = mc.theWorld == null ? 0 : mc.theWorld.getTotalWorldTime();
        NBTTagCompound s = last;
        String name = s == null || s.getString("Name").length() == 0 ? Lang.tr("sva.ad.kp") : s.getString("Name");
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.kp.title", name), left + 6, top + 4, 170, GOLD, false, 0, 0);
        if (s != null) {
            int nr = s.getTagList("Radars", 10).tagCount(), nl = s.getTagList("Launchers", 10).tagCount();
            String head = Lang.tr("sva.ad.kp.head", String.valueOf(s.getLong("Day")), s.getString("Time"), code(s.getInteger("DayCode")),
                    Integer.valueOf(nr), Integer.valueOf(nl));
            int hw = fontRendererObj.getStringWidth(head);
            fontRendererObj.drawString(head, left + W - 6 - Math.min(hw, 236), top + 4, s.getBoolean("Powered") ? MUTED : RED);
            String mode = Lang.tr("sva.ad.l.mode." + s.getString("Mode"));
            // code-only wave: five tabs - the server's mode takes the room right of them (squeezed, cut with a hover if need be)
            int room = W - 6 - CodeOnlyUiMath.tabsRight() - 4, mw = Math.min(room, fontRendererObj.getStringWidth(mode));
            TextFitSC.draw(fontRendererObj, mode, left + W - 6 - mw, top + 20, room, "fire".equals(s.getString("Mode")) ? RED : AMBER, false, 0, 0);
        }
        for (Object o : buttonList) {
            GuiCargoSVA.Dark b = (GuiCargoSVA.Dark) o;
            b.active = b.id == B_TAB + tab || b.id == B_ALARM && s != null && s.getBoolean("Alarm") || b.id == B_AUTO && s != null && s.getBoolean("Auto")
                    || b.id == B_Z_SHOW && edit.show || b.id == B_Z_ALARM && edit.alarm || b.id == B_Z_TYPE + edit.type || b.id == B_S_KEY && dayKey;
            b.activeColor = b.id == B_ALARM ? RED : b.id >= B_Z_TYPE && b.id < B_Z_TYPE + ZoneMath.TYPES ? ZoneMath.COLOURS[b.id - B_Z_TYPE] : 0;
            if (b.id == B_AUTO) {
                b.displayString = Lang.tr(s != null && s.getBoolean("Auto") ? "sva.ad.kp.autoOn" : "sva.ad.kp.autoOff");
            } else if (b.id == B_Z_SHAPE) {
                b.displayString = Lang.tr(edit.shape == ZoneMath.CIRCLE ? "sva.ad.zone.circle" : "sva.ad.zone.rect");
            } else if (b.id == B_S_KEY) {
                b.displayString = Lang.tr(dayKey ? "sva.ad.set.dayKeyOn" : "sva.ad.set.dayKeyOff");
            } else if (b.id == B_S_SECRET || b.id == B_S_NEWSECRET) {
                b.enabled = s != null && s.getBoolean("Owner");
            } else if (b.id == B_COV) {
                b.displayString = cov > 0 ? Lang.tr("sva.ad.cov.on", Integer.valueOf(cov)) : Lang.tr("sva.ad.cov.off");
                b.active = cov > 0;
            } else if (b.id == B_Z_SNAP) {
                b.displayString = Lang.tr(snap ? "sva.ad.zone.snapOn" : "sva.ad.zone.snapOff", Integer.valueOf(com.sva.airdefense.ZoneEditMath.SNAP));
                b.active = snap;
            } else if (b.id == B_Z_PASTE) {
                b.enabled = clipboard != null;
            } else if (b.id == B_Z_UNDO) {
                b.enabled = hist.size() > 0;
            } else if (b.id >= B_CO_NORMAL && b.id <= B_CO_ALLROE) {
                identButton(b, s);
            }
        }
        if (s == null) {
            fontRendererObj.drawString(Lang.tr("sva.ad.l.wait"), left + 10, top + 40, MUTED);
        } else if (tab == 2) {
            drawSettings(s);
        } else if (tab == CodeOnlyUiMath.TAB_IDENT) {
            drawIdent(s);
        } else {
            drawMap(s, tick);
            if (tab == 0) {
                drawRight(s, tick);
            } else {
                drawZoneList();
            }
        }
        for (GuiTextField f : fields) {
            f.drawTextBox();
        }
        super.drawScreen(mx, my, pt);
        if (menuX >= 0) {
            drawMenu(mx, my);
        }
        for (Object o : buttonList) {
            GuiCargoSVA.Dark b = (GuiCargoSVA.Dark) o;
            if (b.tip != null && b.visible) {
                TextFitSC.hover(b.xPosition, b.yPosition, b.width, b.height, b.tip);
            }
        }
        if (dialog) {
            drawDialog(s, mx, my);                                  // code-only wave (item 27): over everything, no hover under it
            GL11.glColor4f(1F, 1F, 1F, 1F);
            return;
        }
        List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty() && menuX < 0) {
            List<String> wrapped = new ArrayList<String>();
            for (String l : tip) {
                wrapped.addAll(fontRendererObj.listFormattedStringToWidth(l, 220));
            }
            drawHoveringText(wrapped, mx, my, fontRendererObj);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void drawMenu(int mx, int my) {
        int h = menu.size() * 11 + 4;
        drawRect(menuX - 1, menuY - 1, menuX + 131, menuY + h + 1, 0xFF5EC8F0);
        drawRect(menuX, menuY, menuX + 130, menuY + h, 0xF0101418);
        for (int i = 0; i < menu.size(); i++) {
            int y = menuY + 2 + i * 11;
            boolean hov = mx >= menuX && mx < menuX + 130 && my >= y && my < y + 11;
            if (hov) {
                drawRect(menuX + 1, y, menuX + 129, y + 11, 0xFF2E3542);
            }
            String[] r = menu.get(i);
            int c = "grant".equals(r[1]) || "emFriend".equals(r[1]) ? GREEN : "msg".equals(r[1]) || "unlock".equals(r[1]) ? CYAN
                    : "emHostile".equals(r[1]) || "nrHostile".equals(r[1]) ? RED : TEXT;
            TextFitSC.draw(fontRendererObj, r[0], menuX + 4, y + 2, 122, hov ? 0xFFFFA0 : c, false, 0, 0);
        }
    }

    private void drawMap(NBTTagCompound s, long tick) {
        int mapL = left + MAP_X, mapT = top + MAP_Y;
        drawRect(mapL - 1, mapT - 1, mapL + MAP + 1, mapT + MAP + 1, LINE);
        drawRect(mapL, mapT, mapL + MAP, mapT + MAP, UNKNOWN);
        int now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
        if (terrain == null || builtX != cx || builtZ != cz || builtScale != scale() || now - builtAt >= 60) {
            buildTerrain(now);
        }
        if (terrain != null) {
            GL11.glColor4f(0.75F, 0.75F, 0.75F, 1F);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, terrain.getGlTextureId());
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            t.addVertexWithUV(mapL, mapT + MAP, 0, 0, 1);
            t.addVertexWithUV(mapL + MAP, mapT + MAP, 0, 1, 1);
            t.addVertexWithUV(mapL + MAP, mapT, 0, 1, 0);
            t.addVertexWithUV(mapL, mapT, 0, 0, 0);
            t.draw();
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        net.minecraft.client.gui.ScaledResolution res = new net.minecraft.client.gui.ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int f = res.getScaleFactor();
        GL11.glScissor(mapL * f, mc.displayHeight - (mapT + MAP) * f, MAP * f, MAP * f);
        double sc = scale(), ox = mapL + MAP / 2.0, oy = mapT + MAP / 2.0;
        // wave A: the radars' coverage at the chosen height under everything else
        if (cov > 0) {
            NBTTagList cr = s.getTagList("Radars", 10);
            for (int i = 0; i < cr.tagCount(); i++) {
                CoverClientSVA.draw(cr.getCompoundTagAt(i).getString("Key"), cov, ox, oy, cx, cz, sc);
            }
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
        List<ZoneMath.Zone> zs = tab == 1 ? withEdit() : zones();
        AirDefenseClientSVA.drawZones(fontRendererObj, zs, ox, oy, cx, cz, sc, tab == 1 ? (zoneSel >= 0 ? zoneSel : zs.size() - 1) : -1);
        if (tab == 1) {
            drawHandles(ox, oy, sc);
        }
        // the radars' circles (on a plane's RCS), the radars and the launchers
        NBTTagList rl = s.getTagList("Radars", 10);
        for (int i = 0; i < rl.tagCount(); i++) {
            NBTTagCompound r = rl.getCompoundTagAt(i);
            double[] p = MapMath.toMap(r.getInteger("X") + 0.5, r.getInteger("Z") + 0.5, cx, cz, sc);
            double rr = RadarMath.detectRange(r.getByte("K"), RadarMath.rcs(VehicleType.PLANE), 1, 1) / sc;
            int n = 64;
            for (int k = 0; k < n; k += 2) {
                double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
                AirDefenseClientSVA.edge(ox + p[0] + Math.cos(a0) * rr, oy + p[1] + Math.sin(a0) * rr, ox + p[0] + Math.cos(a1) * rr, oy + p[1] + Math.sin(a1) * rr,
                        0.8, 0x4A5A6A, false);
            }
            int c = r.getBoolean("P") ? CYAN : 0x5A6070;
            tri(ox + p[0], oy + p[1], 4, c);
            fontRendererObj.drawStringWithShadow(Lang.tr("sva.ad.kp.radarShort", Integer.valueOf(i + 1)), (int) (ox + p[0]) + 5, (int) (oy + p[1]) - 3, c);
        }
        NBTTagList ll = s.getTagList("Launchers", 10);
        for (int i = 0; i < ll.tagCount(); i++) {
            NBTTagCompound l = ll.getCompoundTagAt(i);
            double[] p = MapMath.toMap(l.getInteger("X") + 0.5, l.getInteger("Z") + 0.5, cx, cz, sc);
            int c = l.getBoolean("P") ? AMBER : 0x5A6070;
            drawRect((int) (ox + p[0]) - 3, (int) (oy + p[1]) - 3, (int) (ox + p[0]) + 3, (int) (oy + p[1]) + 3, 0xFF000000 | c);
            fontRendererObj.drawStringWithShadow(Lang.tr("sva.ad.kp.launcherShort", Integer.valueOf(i + 1)), (int) (ox + p[0]) + 5, (int) (oy + p[1]) - 3, c);
        }
        double[] kp = MapMath.toMap(kx + 0.5, kz + 0.5, cx, cz, sc);
        drawRect((int) (ox + kp[0]) - 3, (int) (oy + kp[1]) - 3, (int) (ox + kp[0]) + 3, (int) (oy + kp[1]) + 3, 0xFFFFFFFF);
        drawRect((int) (ox + kp[0]) - 2, (int) (oy + kp[1]) - 2, (int) (ox + kp[0]) + 2, (int) (oy + kp[1]) + 2, 0xFF101418);
        // the tracks: the symbol by class, the speed vector, the data block (x0.75)
        NBTTagList tl = tracks();
        for (int i = 0; i < tl.tagCount(); i++) {
            NBTTagCompound t = tl.getCompoundTagAt(i);
            double[] p = MapMath.toMap(t.getDouble("X"), t.getDouble("Z"), cx, cz, sc);
            double x = ox + p[0], y = oy + p[1];
            int c = clsColour(t, tick);
            int cls = t.getByte("Cls");
            double crs = Math.toRadians(t.getInteger("Crs")), v = Math.min(30, t.getInteger("Spd") / 10.0);
            AirDefenseClientSVA.edge(x, y, x + Math.sin(crs) * v, y - Math.cos(crs) * v, 1, c, false);
            if (cls == RoeMath.OWN || cls == RoeMath.FRIEND) {
                ring(x, y, 3.5, c);
            } else if (cls == RoeMath.VIOLATOR || cls == RoeMath.EMERGENCY) {
                diamondOutline(x, y, 4.5, c);
            } else {
                drawRect((int) x - 3, (int) y - 3, (int) x + 4, (int) y + 4, 0xFF000000 | c);
                drawRect((int) x - 2, (int) y - 2, (int) x + 3, (int) y + 3, 0xFF14171D);
            }
            if (t.getBoolean("Ident") && tick / 4 % 2 == 0) {
                ring(x, y, 7, 0xFFFFFF);
            }
            if (t.getInteger("Id") == selected) {
                ring(x, y, 9, 0xFFFFFF);
            }
            int iff = t.getByte("Iff");
            if (com.sva.airdefense.IffMath.intruder(iff) && tick / 5 % 2 == 0) {
                ring(x, y, 6, 0xF05050);                              // wave A: a tower's IFF intruder
            }
            String l1 = code(t.getInteger("Code")) + (t.getInteger("Board") > 0 ? " B-" + t.getInteger("Board") : "");
            String l2 = alt(t) + " " + String.format("%03d", Integer.valueOf(t.getInteger("Spd"))) + " " + String.format("%03d", Integer.valueOf(t.getInteger("Crs")));
            GL11.glPushMatrix();
            GL11.glTranslated(x + 6, y - 9, 0);
            GL11.glScalef(0.75F, 0.75F, 1F);
            fontRendererObj.drawStringWithShadow(l1, 0, 0, c);
            fontRendererObj.drawStringWithShadow(l2, 0, 10, c);
            String l3 = mark(t);
            if (l3 != null) {
                fontRendererObj.drawStringWithShadow(l3, 0, 20, t.getByte("NR") >= 0 ? 0xF09040 : 0xF05050);
            }
            // code-only wave (item 39): the tag («НЕТ КАРТЫ», «ПРОВ 1/2», ...) in its colour, the grey ВЛ / ДР after it
            String tg = coTag(t), rel = relTag(t);
            int ty = l3 != null ? 30 : 20;
            if (tg != null) {
                fontRendererObj.drawStringWithShadow(tg, 0, ty, tagColour(t, tick));
            }
            if (rel != null) {
                fontRendererObj.drawStringWithShadow(rel, tg == null ? 0 : fontRendererObj.getStringWidth(tg) + 4, ty, 0x9AA0AA);
            }
            GL11.glPopMatrix();
        }
        if (cov > 0) {
            covLegend(s, mapL, mapT);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        nrBtns.clear();
        if (tab == 0) {
            drawNoRadio(mapL, mapT, tick);
            drawPlate(s, mapL, mapT, tick);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
        fontRendererObj.drawString(Lang.tr("sva.ad.kp.scale", Integer.valueOf((int) sc)), mapL + 30, mapT + MAP + 5, MUTED);
        if (tab == 0) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.kp.mapHint"), mapL + 96, mapT + MAP + 5, MAP - 96, MUTED, false, 0, 0);
            if (s.getByte("Co") != CodeOnlyMath.CO_OFF) {
                // code-only wave (item 32): the readiness line while the mode is on (or arming) - review 2026-10-27 (batch 3): on
                // its own row under the zoom buttons, the map's whole width (in the hint's 108 px the reason was cut off: «НЕТ -
                // сервер: только...»); a text still too long shows whole on hover (TextFitSC)
                int r = ready(s);
                TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.co.ready." + CodeOnlyMath.READY_KEYS[r]), mapL, mapT + MAP + READY_DY, MAP,
                        r == CodeOnlyMath.R_READY ? GREEN : AMBER, false, 0, 0);
            }
        } else {
            // audit 2026-10-22 (bug 1): the server's limit on the zone's distance from this post comes with the picture
            int far = last == null ? 0 : last.getInteger("ZoneMax");
            int bad = com.sva.airdefense.ZoneEditMath.check(edit, kx, kz, far);
            TextFitSC.draw(fontRendererObj, bad == com.sva.airdefense.ZoneEditMath.OK ? Lang.tr("sva.ad.zone.mapHint2")
                    : Lang.tr("sva.ad.zone.check." + com.sva.airdefense.ZoneEditMath.CHECK_KEYS[bad], Integer.valueOf(far)), mapL + 96, mapT + MAP + 5, MAP - 96,
                    bad == com.sva.airdefense.ZoneEditMath.OK ? MUTED : RED, false, 0, 0);
        }
    }

    /** Wave A: a track's third data line - «НЕТ СВЯЗИ 1:45» (7600 waiting) / «ПРОВЕРКА» / the IFF mark; null none. */
    private static String mark(NBTTagCompound t) {
        int nr = t.getByte("NR");
        if (nr == com.sva.airdefense.NoRadioMath.S_PENDING) {
            int sec = t.getInteger("NRt");
            return Lang.tr("sva.ad.nr.mark", String.format("%d:%02d", Integer.valueOf(sec / 60), Integer.valueOf(sec % 60)));
        }
        if (nr == com.sva.airdefense.NoRadioMath.S_CHECK) {
            return Lang.tr("sva.ad.nr.checkMark");
        }
        if (nr == com.sva.airdefense.NoRadioMath.S_HOSTILE) {
            return Lang.tr("sva.ad.nr.hostileMark");
        }
        if (t.getBoolean("Hold")) {
            return Lang.tr("sva.ad.kp.holdMark");                   // audit 2026-10-22: «только вести» - its launchers hold fire
        }
        int iff = t.getByte("Iff");
        if (iff == com.sva.airdefense.IffMath.WARNED || iff == com.sva.airdefense.IffMath.HANDED) {
            return Lang.tr("sva.ad.iff.mark." + com.sva.airdefense.IffMath.STATE_KEYS[iff]);
        }
        return t.getString("Apt").length() > 0 ? Lang.tr("sva.ad.apt.mark", t.getString("Apt")) : null;
    }

    /**
     * Wave A (plan-next §9 A5, mock-up veh_ad_impl_a_4_7600): the 7600 decision list at the map's bottom - each waiting track
     * with its timer and the answers «Свой» / «Проверить» / «Враг» (audit 2026-10-22: «враждебный» - no grace; without one in
     * time: the usual rules).
     */
    private void drawNoRadio(int mapL, int mapT, long tick) {
        // code-only wave (items 7, 39): the 7700 tracks in their grace on a code-only post join the 7600 ones - the same rows
        List<NBTTagCompound> l = new ArrayList<NBTTagCompound>();
        List<Integer> kinds = new ArrayList<Integer>();
        NBTTagList tl = tracks();
        boolean em = false;
        for (int i = 0; i < tl.tagCount() && l.size() < CodeOnlyUiMath.DEC_ROWS; i++) {
            NBTTagCompound c = tl.getCompoundTagAt(i);
            int k = CodeOnlyUiMath.decision(c.getByte("NR"), c.getBoolean("Co"), c.getByte("EM"));
            if (k != CodeOnlyUiMath.DEC_NONE) {
                l.add(c);
                kinds.add(Integer.valueOf(k));
                em |= k == CodeOnlyUiMath.DEC_EMERGENCY;
            }
        }
        if (l.isEmpty()) {
            return;
        }
        int h = 12 + l.size() * 13, y0 = mapT + MAP - h - 2, x0 = mapL + 2, x1 = mapL + MAP - 2;
        drawRect(x0 - 1, y0 - 1, x1 + 1, y0 + h + 1, tick / 10 % 2 == 0 ? 0xFFF09040 : 0xFF7A4A20);
        drawRect(x0, y0, x1, y0 + h, 0xF0101418);
        fontRendererObj.drawString(Lang.tr(em ? "sva.ad.co.decTitle" : "sva.ad.nr.title"), x0 + 3, y0 + 2, 0xF09040);
        for (int i = 0; i < l.size(); i++) {
            NBTTagCompound t = l.get(i);
            int kind = kinds.get(i).intValue();
            int y = y0 + 12 + i * 13, sec = kind == CodeOnlyUiMath.DEC_EMERGENCY ? t.getInteger("EMt") : t.getInteger("NRt");
            String line = String.format("%02d", Integer.valueOf(t.getInteger("No"))) + " " + code(t.getInteger("Code"))
                    + (sec >= 0 ? " " + CodeOnlyUiMath.clock(sec) : "");
            fontRendererObj.drawString(line, x0 + 3, y + 2, kind == CodeOnlyUiMath.DEC_EMERGENCY ? 0xF07050 : TEXT);
            // audit review 2026-10-22: the buttons right of the text - x1 - 150 put «Свой» over the countdown
            int[][] b = com.sva.airdefense.AdLayoutMath.nrButtons(x0 + 3 + fontRendererObj.getStringWidth(line), x1);
            nrButton(b[0][0], y, b[0][1], Lang.tr("sva.ad.nr.friend"), GREEN, t.getInteger("Id"), kind * 10);
            nrButton(b[1][0], y, b[1][1], Lang.tr("sva.ad.nr.check"), AMBER, t.getInteger("Id"), kind * 10 + 1);
            nrButton(b[2][0], y, b[2][1], Lang.tr("sva.ad.nr.hostile"), RED, t.getInteger("Id"), kind * 10 + 2);
        }
    }

    /**
     * Code-only wave (item 38): the map's header plate in its top left corner - «ПО КОДУ - смена ключа через 12 мин» on red,
     * «ВКЛЮЧЕНИЕ ЧЕРЕЗ 45 с» flashing amber, «ПО КОДУ: запрещено сервером» / «нет ключа дня» grey; its hover says what it means.
     */
    private void drawPlate(NBTTagCompound s, int mapL, int mapT, long tick) {
        int pl = CodeOnlyUiMath.plate(s.getByte("Co"));
        if (pl == CodeOnlyUiMath.PL_NONE) {
            return;
        }
        String text = pl == CodeOnlyUiMath.PL_ON ? Lang.tr("sva.ad.co.plate.on", Integer.valueOf(CodeOnlyUiMath.minutes(s.getInteger("KeyLeft"))))
                : pl == CodeOnlyUiMath.PL_ARMING ? Lang.tr("sva.ad.co.plate.arming", Integer.valueOf(s.getInteger("CoArm")))
                        : Lang.tr("sva.ad.co.plate." + CodeOnlyUiMath.PL_KEYS[pl]);
        int x0 = mapL + 2, x1 = mapL + MAP - 70, y0 = mapT + 2;
        boolean blink = tick / 10 % 2 == 0;
        int bg = pl == CodeOnlyUiMath.PL_ON ? 0xF0902020 : pl == CodeOnlyUiMath.PL_ARMING ? (blink ? 0xF08A6018 : 0xF05A4010) : 0xF0303640;
        int fg = pl == CodeOnlyUiMath.PL_ON ? 0xFFE8E0 : pl == CodeOnlyUiMath.PL_ARMING ? 0xFFF0C0 : 0xC0C4CC;
        drawRect(x0, y0, x1, y0 + 11, bg);
        drawRect(x0, y0 + 11, x1, y0 + 12, 0xFF000000);
        TextFitSC.draw(fontRendererObj, text, x0 + 3, y0 + 2, x1 - x0 - 6, fg, false, 0, 0);
        // review 2026-10-27 (batch 3): the plate's own text first in its hover - registered last, the hover hid the full text
        // TextFitSC keeps for a cut one, so «...key change in 12 ...» could not be read anywhere
        List<String> tip = new ArrayList<String>();
        tip.add(text);
        tip.add(Lang.tr("sva.ad.co.plate.tip"));
        TextFitSC.hover(x0, y0, x1 - x0, 12, tip);
    }

    /**
     * Review 2026-10-27 (batch 3): why the switch to «ПО КОДУ» would be refused (CodeOnlyUiMath.EL_*) - the server's dayKey too
     * (it was offered, then refused with «coNoKey» in the chat); an older server without «CoSrvKey» - allowed.
     */
    private static int enableLimit(NBTTagCompound s) {
        return CodeOnlyUiMath.enableLimit(s.getBoolean("CoServer"), !s.hasKey("CoSrvKey") || s.getBoolean("CoSrvKey"), s.getBoolean("DayKey"));
    }

    /** The picture's readiness line (CodeOnlyMath.R_*), kept in range. */
    private static int ready(NBTTagCompound s) {
        return Math.max(0, Math.min(CodeOnlyMath.READY_KEYS.length - 1, s.getByte("CoReady")));
    }

    /** Code-only wave (item 39): a track's tag («НЕТ КАРТЫ», «ПРОВ 1/2», «7700 1:20», ...), null - none. */
    private static String coTag(NBTTagCompound t) {
        int tag = CodeOnlyUiMath.tag(t.getBoolean("Co"), t.getByte("Idr"));
        if (tag == CodeOnlyUiMath.TAG_NONE) {
            return null;
        }
        if (tag == CodeOnlyUiMath.TAG_CHECK) {
            return Lang.tr("sva.ad.co.tag.check", Integer.valueOf(CodeOnlyUiMath.checkCount(t.getByte("Cnf"))));
        }
        if (tag == CodeOnlyUiMath.TAG_EMERGENCY) {
            int sec = t.getInteger("EMt");
            return sec >= 0 ? Lang.tr("sva.ad.co.tag.emergency", CodeOnlyUiMath.clock(sec)) : Lang.tr("sva.ad.co.tag.emergencyFree");
        }
        return Lang.tr("sva.ad.co.tag." + CodeOnlyUiMath.TAG_KEYS[tag]);
    }

    /** Code-only wave (item 33): the grey ВЛ / ДР of the owner's / his list's machines, null - none. */
    private static String relTag(NBTTagCompound t) {
        int r = CodeOnlyUiMath.relTag(t.getBoolean("Co"), t.getByte("Rel"));
        return r == CodeOnlyUiMath.REL_NONE ? null : Lang.tr("sva.ad.co.rel." + CodeOnlyUiMath.REL_KEYS[r]);
    }

    /** A tag's colour: a match green, «ПРОВ» / 7700 in its grace amber (7700 flashing), a miss red. */
    private static int tagColour(NBTTagCompound t, long tick) {
        int tag = CodeOnlyUiMath.tag(t.getBoolean("Co"), t.getByte("Idr"));
        if (tag == CodeOnlyUiMath.TAG_EMERGENCY && tick / 5 % 2 == 0) {
            return 0xFFFFFF;
        }
        return kindColour(CodeOnlyUiMath.tagColour(tag));
    }

    private static int kindColour(int kind) {
        return kind == CodeOnlyUiMath.TC_OK ? GREEN : kind == CodeOnlyUiMath.TC_WAIT ? AMBER : kind == CodeOnlyUiMath.TC_MISS ? RED : TEXT;
    }

    /**
     * Code-only wave (item 39): the form's «Опознав.» row - «не опознан - нет карты; борт: Bob (ваш)», «проверка 1/2 - нет
     * ответа; ...», «опознан - код ключа; ...», «авария 7700 1:20; ...».
     */
    private static String identText(NBTTagCompound t) {
        int st = CodeOnlyUiMath.formStatus(t.getBoolean("Co"), t.getByte("Cls"), t.getByte("Idr"));
        String s = st == CodeOnlyUiMath.FS_CHECK ? Lang.tr("sva.ad.co.fs.check", Integer.valueOf(CodeOnlyUiMath.checkCount(t.getByte("Cnf"))))
                : Lang.tr("sva.ad.co.fs." + CodeOnlyUiMath.FS_KEYS[st]);
        int why = CodeOnlyUiMath.formReason(st, t.getByte("Idr"), t.getByte("IdM"));
        if (why > RoeMath.ID_NONE && why < RoeMath.IDS) {
            s = Lang.tr("sva.ad.co.formReason", s, Lang.tr("sva.ad.idr." + RoeMath.ID_KEYS[why]));
        }
        if (st == CodeOnlyUiMath.FS_EMERGENCY && t.getInteger("EMt") >= 0) {
            s += " " + CodeOnlyUiMath.clock(t.getInteger("EMt"));
        }
        String own = t.getString("Own");
        int rel = Math.max(0, Math.min(2, t.getByte("Rel")));
        return own.length() == 0 ? Lang.tr("sva.ad.co.formNoOwner", s) : Lang.tr("sva.ad.co.form", s, own, Lang.tr("sva.ad.co.relWord." + CodeOnlyUiMath.REL_KEYS[rel]));
    }

    private void nrButton(int x, int y, int w, String label, int c, int id, int op) {
        drawRect(x, y, x + w, y + 12, 0xFF2A303C);
        drawRect(x, y, x + w, y + 1, 0xFF000000 | c);
        TextFitSC.draw(fontRendererObj, label, x + 3, y + 2, w - 6, c, false, 0, 0);
        nrBtns.add(new int[] {x, y, x + w, y + 12, id, op});
    }

    /** Wave A: the coverage map's legend (and the computing progress) under its button. */
    private void covLegend(NBTTagCompound s, int mapL, int mapT) {
        NBTTagList cr = s.getTagList("Radars", 10);
        int busy = -1;
        for (int i = 0; i < cr.tagCount(); i++) {
            int p = CoverClientSVA.percent(cr.getCompoundTagAt(i).getString("Key"), cov);
            if (p >= 0 && p < 100) {
                busy = Math.max(busy, p);
            }
        }
        GL11.glPushMatrix();
        GL11.glTranslated(mapL + MAP - 66, mapT + 15, 0);
        GL11.glScalef(0.75F, 0.75F, 1F);
        fontRendererObj.drawStringWithShadow(Lang.tr("sva.ad.cov.seen"), 0, 0, 0x62E06A);
        fontRendererObj.drawStringWithShadow(Lang.tr("sva.ad.cov.dead"), 0, 10, 0xF06060);
        if (busy >= 0) {
            fontRendererObj.drawStringWithShadow(Lang.tr("sva.ad.cov.busy", Integer.valueOf(busy)), 0, 20, AMBER);
        } else if (cr.tagCount() == 0) {
            fontRendererObj.drawStringWithShadow(Lang.tr("sva.ad.cov.none"), 0, 20, MUTED);
        }
        GL11.glPopMatrix();
    }

    /** Wave A: the edited zone's handles - squares at the corners / edges / centre, the turn knob (a ring on its stalk). */
    private void drawHandles(double ox, double oy, double sc) {
        ZoneMath.Zone e = edit.copy();
        double[] m = ZoneMath.middle(e);
        for (double[] h : com.sva.airdefense.ZoneEditMath.handles(e)) {
            double x = ox + (h[0] - cx) / sc, y = oy + (h[1] - cz) / sc;
            int type = (int) h[2];
            boolean on = type == dragHandle;
            if (type == com.sva.airdefense.ZoneEditMath.H_ROTATE) {
                double[] top0 = ZoneMath.world(e, m[0], e.z0);
                AirDefenseClientSVA.edge(ox + (top0[0] - cx) / sc, oy + (top0[1] - cz) / sc, x, y, 1, CYAN, false);
                ring(x, y, 3.5, on ? 0xFFFFFF : CYAN);
                continue;
            }
            int c = on ? 0xFFFFFF00 : type == com.sva.airdefense.ZoneEditMath.H_CENTRE ? 0xFFFFFFFF : 0xFFE0E4EC;
            drawRect((int) x - 2, (int) y - 2, (int) x + 3, (int) y + 3, 0xFF000000);
            drawRect((int) x - 1, (int) y - 1, (int) x + 2, (int) y + 2, c);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /** The zones with the one being edited in place of its saved copy (or added). */
    private List<ZoneMath.Zone> withEdit() {
        List<ZoneMath.Zone> zs = zones();
        readZoneFields();
        ZoneMath.Zone e = edit.copy();
        if (zoneSel >= 0 && zoneSel < zs.size()) {
            zs.set(zoneSel, e);
        } else {
            zs.add(e);
        }
        return zs;
    }

    private void drawRight(NBTTagCompound s, long tick) {
        int rx = left + RX, y = top + 34;
        // the selected track's form
        NBTTagCompound t = selected < 0 ? null : track(selected);
        drawRect(rx - 1, y - 1, rx + RW + 1, y + 81, t != null && t.getByte("Cls") == RoeMath.VIOLATOR ? 0xFFF05050 : LINE);
        drawRect(rx, y, rx + RW, y + 80, PANEL);
        if (t == null) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.kp.noSel"), rx + 4, y + 4, RW - 8, MUTED, false, 0, 0);
        } else {
            int c = clsColour(t, tick);
            String cls = Lang.tr("sva.ad.cls." + RoeMath.CLASS_KEYS[Math.max(0, Math.min(RoeMath.CLASSES - 1, t.getByte("Cls")))]);
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.kp.trackTitle", String.format("%02d", Integer.valueOf(t.getInteger("No"))), cls), rx + 4, y + 3,
                    RW - 8, c, false, 0, 0);
            int mode = t.getByte("Mode");
            boolean co = t.getBoolean("Co");
            String[][] rows0 = {
                    {Lang.tr("sva.ad.f.code"), t.getInteger("Code") < 0 ? Lang.tr("sva.ad.f.noReply") : code(t.getInteger("Code")) + " ("
                            + Lang.tr("sva.xpdr.mode." + XpdrMath.MODE_KEYS[Math.max(0, Math.min(4, mode))]) + ")"},
                    {Lang.tr("sva.ad.f.alt"), t.getBoolean("Alt") ? alt(t) : Lang.tr("sva.ad.f.unknown")},
                    {Lang.tr("sva.ad.f.speed"), Lang.tr("sva.ad.f.speedVal", Integer.valueOf(t.getInteger("Spd")), String.format("%03d", Integer.valueOf(t.getInteger("Crs"))))},
                    {Lang.tr("sva.ad.f.type"), Lang.tr("item.siliconagevehicles.vehicle." + VehicleType.byOrdinal(t.getByte("Type")).key + ".name")
                            + (t.getInteger("Board") > 0 ? " B-" + t.getInteger("Board") : "")},
                    {Lang.tr("sva.ad.f.zone"), t.getString("Zone").length() > 0 ? t.getString("Zone") : "-"},
                    {Lang.tr("sva.ad.f.seen"), seen(t.getByte("Seen"))},
                    {Lang.tr("sva.ad.f.assigned"), assignedText(t)}};
            // code-only wave (item 39): the «Опознав.» row after the code (8 rows 8 px apart - the form keeps its size)
            List<String[]> rows = new ArrayList<String[]>();
            for (int i = 0; i < rows0.length; i++) {
                rows.add(rows0[i]);
                if (i == 0 && co) {
                    rows.add(new String[] {Lang.tr("sva.ad.f.ident"), identText(t), "ident"});
                }
            }
            int step = co ? 8 : 9;
            for (int i = 0; i < rows.size(); i++) {
                String[] r = rows.get(i);
                int ry = y + (co ? 13 : 14) + i * step;
                fontRendererObj.drawString(r[0], rx + 4, ry, MUTED);
                int rc = r == rows0[6] && t.getByte("St") >= RoeMath.ST_LOCK ? RED : TEXT;
                if (r.length > 2) {
                    rc = kindColour(CodeOnlyUiMath.statusColour(CodeOnlyUiMath.formStatus(true, t.getByte("Cls"), t.getByte("Idr"))));
                    TextFitSC.hover(rx + 70, ry, RW - 74, 8, r[1]);
                }
                TextFitSC.draw(fontRendererObj, r[1], rx + 70, ry, RW - 74, rc, false, 0, 0);
            }
        }
        // the launchers' line
        NBTTagList ll = s.getTagList("Launchers", 10);
        StringBuilder lb = new StringBuilder();
        // review 2026-10-27 (batch 3, item 12): in the mode (and arming) the rules' short labels say «без кода», not «чужие» -
        // the owner's own machines without the code are no safer
        boolean strict = CodeOnlyUiMath.strictLabels(s.getByte("Co"));
        for (int i = 0; i < ll.tagCount(); i++) {
            NBTTagCompound l = ll.getCompoundTagAt(i);
            lb.append(i > 0 ? "  " : "").append(Lang.tr("sva.ad.kp.launcherShort", Integer.valueOf(i + 1))).append(' ')
                    .append(Lang.tr("sva.ad.roe.short." + CodeOnlyUiMath.roeKey(l.getByte("Roe"), strict))).append(' ').append(l.getInteger("Ammo"));
        }
        TextFitSC.draw(fontRendererObj, ll.tagCount() == 0 ? Lang.tr("sva.ad.kp.noLaunchers") : lb.toString(), rx, y + 84, RW, AMBER, false, 0, 0);
        // the track list
        int ly = top + 126;
        fontRendererObj.drawString(Lang.tr("sva.ad.kp.tracks"), rx, ly - 10 + 0, CYAN);
        NBTTagList tl = tracks();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, tl.tagCount() - 6)));
        drawRect(rx, ly, rx + RW, ly + 60, PANEL);
        for (int i = 0; i < 6 && i + listScroll < tl.tagCount(); i++) {
            NBTTagCompound r = tl.getCompoundTagAt(i + listScroll);
            int ry = ly + i * 10;
            if (r.getInteger("Id") == selected) {
                drawRect(rx, ry, rx + RW, ry + 10, 0xFF2E3542);
            }
            String cls = Lang.tr("sva.ad.cls." + RoeMath.CLASS_KEYS[Math.max(0, Math.min(RoeMath.CLASSES - 1, r.getByte("Cls")))]);
            String mk = mark(r);
            String line = String.format("%02d", Integer.valueOf(r.getInteger("No"))) + " " + code(r.getInteger("Code")) + " "
                    + (r.getInteger("Board") > 0 ? String.format("B-%02d", Integer.valueOf(r.getInteger("Board"))) : "  -  ") + " " + alt(r) + " " + cls
                    + (r.getInteger("Grant") > 0 ? " " + Lang.tr("sva.ad.kp.grantLeft", Integer.valueOf(r.getInteger("Grant") / 60 + 1)) : "")
                    + (mk != null ? " " + mk : "");
            // code-only wave (item 39): the tag right-aligned in its colour, the grey ВЛ / ДР before it; the line takes the rest
            String tg = coTag(r), rl = relTag(r);
            int tw = tg == null ? 0 : Math.min(fontRendererObj.getStringWidth(tg), 70), rw = rl == null ? 0 : fontRendererObj.getStringWidth(rl);
            int right = rx + RW - 2;
            if (tg != null) {
                TextFitSC.draw(fontRendererObj, tg, right - tw, ry + 1, tw, tagColour(r, tick), false, 0, 0);
                right -= tw + 3;
            }
            if (rl != null) {
                fontRendererObj.drawString(rl, right - rw, ry + 1, 0x9AA0AA);
                right -= rw + 3;
            }
            TextFitSC.draw(fontRendererObj, line, rx + 2, ry + 1, right - rx - 2, clsColour(r, tick), false, 0, 0);
        }
        // the log
        int gy = top + 190;
        fontRendererObj.drawString(Lang.tr("sva.ad.kp.log"), rx, gy - 3, CYAN);
        drawRect(rx, gy + 6, rx + RW, gy + 34, PANEL);
        NBTTagList lg = s.getTagList("Log", 10);
        for (int i = 0; i < 3 && i < lg.tagCount(); i++) {
            NBTTagList e = lg.getCompoundTagAt(i).getTagList("L", 8);
            if (e.tagCount() < 2) {
                continue;
            }
            Object[] args = new Object[e.tagCount() - 2];
            for (int k = 0; k < args.length; k++) {
                args[k] = e.getStringTagAt(k + 2);
            }
            String key = e.getStringTagAt(1);
            int c = kindColour(CodeOnlyUiMath.logKind(key));       // code-only wave (item 40): the new lines too
            TextFitSC.draw(fontRendererObj, e.getStringTagAt(0) + " " + Lang.tr(key, args), rx + 2, gy + 8 + i * 9, RW - 4, c, false, 0, 0);
        }
        if (selected >= 0 && t != null) {
            fontRendererObj.drawString(Lang.tr("sva.ad.kp.to", String.format("%02d", Integer.valueOf(t.getInteger("No")))), rx, top + 236, MUTED);
        }
    }

    private String seen(int mask) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < RadarMath.KINDS; k++) {
            if ((mask & 1 << k) != 0) {
                b.append(b.length() > 0 ? ", " : "").append(Lang.tr("sva.ad.radar.short." + RadarMath.KEYS[k]));
            }
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    private String assignedText(NBTTagCompound t) {
        int st = t.getByte("St");
        String by = t.getString("By"), asg = t.getString("Asg");
        if (st > RoeMath.ST_NONE && by.length() > 0) {
            return Lang.tr("sva.ad.kp.launcherShort", by) + ": " + Lang.tr("sva.ad.stage." + RoeMath.STAGE_KEYS[Math.min(4, st)])
                    + (st < RoeMath.ST_FIRE && t.getInteger("Tf") > 0 ? " " + Lang.tr("sva.ad.kp.toFire", Integer.valueOf(t.getInteger("Tf"))) : "");
        }
        if (asg.length() > 0) {
            return Lang.tr("sva.ad.kp.launcherShort", asg);
        }
        return t.getInteger("Grant") > 0 ? Lang.tr("sva.ad.kp.grantLeft", Integer.valueOf(t.getInteger("Grant") / 60 + 1)) : "-";
    }

    private void drawZoneList() {
        int rx = left + RX, ly = top + 34;
        List<ZoneMath.Zone> zs = zones();
        fontRendererObj.drawString(Lang.tr("sva.ad.zone.list", Integer.valueOf(zs.size()), Integer.valueOf(ZoneMath.MAX_ZONES)), rx, top + 22, CYAN);
        zoneScroll = Math.max(0, Math.min(zoneScroll, Math.max(0, zs.size() - 8)));
        drawRect(rx, ly, rx + RW, ly + 88, PANEL);
        for (int i = 0; i < 8 && i + zoneScroll < zs.size(); i++) {
            ZoneMath.Zone z = zs.get(i + zoneScroll);
            int y = ly + i * 11;
            if (i + zoneScroll == zoneSel) {
                drawRect(rx, y, rx + RW, y + 11, 0xFF2E3542);
            }
            drawRect(rx + 2, y + 2, rx + 9, y + 9, 0xFF000000 | ZoneMath.COLOURS[z.type]);
            TextFitSC.draw(fontRendererObj, z.name + "  " + Lang.tr("sva.ad.zone.type." + ZoneMath.TYPE_KEYS[z.type]), rx + 12, y + 2, RW - 60, TEXT, false, 0, 0);
            String band = z.lo + "-" + z.hi;
            fontRendererObj.drawString(band, rx + RW - 4 - fontRendererObj.getStringWidth(band), y + 2, MUTED);
        }
        boolean c = edit.shape == ZoneMath.CIRCLE;
        int rxl = rx;
        fontRendererObj.drawString(Lang.tr("sva.ad.zone.name"), rxl, top + 130, MUTED);
        fontRendererObj.drawString(c ? "X" : "X0", rxl, top + 160, MUTED);
        fontRendererObj.drawString(c ? "Z" : "Z0", rxl + 76, top + 160, MUTED);
        fontRendererObj.drawString(c ? "R" : "X1", rxl, top + 174, MUTED);
        if (!c) {
            fontRendererObj.drawString("Z1", rxl + 76, top + 174, MUTED);
            fontRendererObj.drawString(Lang.tr("sva.ad.zone.rot"), rxl + 142, top + 188, MUTED);
        }
        zD.setVisible(!c);
        zRot.setVisible(!c);
        fontRendererObj.drawString(Lang.tr("sva.ad.zone.band"), rxl, top + 188, MUTED);
        fontRendererObj.drawString("-", rxl + 98, top + 188, MUTED);
        fontRendererObj.drawString(Lang.tr("sva.ad.zone.codes"), rxl, top + 202, edit.type == ZoneMath.AERODROME ? TEXT : 0x50565F);
        fontRendererObj.drawString(Lang.tr("sva.ad.zone.course"), rxl + 126, top + 202, edit.type == ZoneMath.CORRIDOR ? TEXT : 0x50565F);
        TextFitSC.hover(rxl, top + 140, RW, 16, Lang.tr("sva.ad.zone.type." + ZoneMath.TYPE_KEYS[edit.type] + ".tip"));
    }

    private void drawSettings(NBTTagCompound s) {
        int x = left + 8;
        fontRendererObj.drawString(Lang.tr("sva.ad.set.name"), x, top + 42, MUTED);
        fontRendererObj.drawString(Lang.tr("sva.ad.set.dayKey"), x, top + 59, MUTED);
        int dc = s.getInteger("DayCode");
        fontRendererObj.drawString(dc >= 0 ? Lang.tr("sva.ad.set.today", code(dc)) : Lang.tr("sva.ad.set.noCode"), left + 192, top + 59, dc >= 0 ? GREEN : MUTED);
        fontRendererObj.drawString(Lang.tr("sva.ad.set.secret"), x, top + 78, MUTED);
        TextFitSC.draw(fontRendererObj, Lang.tr(s.getBoolean("Owner") ? "sva.ad.set.secretHint" : "sva.ad.set.ownerOnly"), left + 66, top + 90, W - 76, MUTED,
                false, 0, 0);
        fontRendererObj.drawString(Lang.tr("sva.ad.set.codes"), x, top + 108, MUTED);
        fontRendererObj.drawString(Lang.tr("sva.ad.set.white"), x, top + 152, MUTED);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.set.whiteHint"), left + 66, top + 164, W - 76, MUTED, false, 0, 0);
        fontRendererObj.drawString(Lang.tr("sva.ad.set.access"), x, top + 182, MUTED);
        TextFitSC.draw(fontRendererObj, Lang.tr(s.getBoolean("Owner") ? "sva.ad.set.accessHint" : "sva.ad.set.ownerOnly"), left + 66, top + 194, W - 76, MUTED,
                false, 0, 0);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.set.owner", s.getString("Own")), x, top + 214, 200, MUTED, false, 0, 0);
        fontRendererObj.drawString(Lang.tr("sva.ad.set.trust"), x, top + 232, MUTED);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.set.trustHint"), left + 66, top + 244, W - 76, MUTED, false, 0, 0);
    }

    // ------------------------------------------------------------------ code-only wave: the «Опознавание» tab and the confirmation window

    /** The «Опознавание» tab's buttons: lit / enabled by the picture (the owner's alone; the server's limits). */
    private void identButton(GuiCargoSVA.Dark b, NBTTagCompound s) {
        boolean owner = s != null && s.getBoolean("Owner");
        int co = s == null ? CodeOnlyMath.CO_OFF : s.getByte("Co");
        boolean manual = s == null || s.getBoolean("CoManual");
        long now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
        if (b.id == B_CO_NORMAL) {
            b.active = co == CodeOnlyMath.CO_OFF;
            b.enabled = owner;
        } else if (b.id == B_CO_CODE) {
            b.active = co != CodeOnlyMath.CO_OFF;
            b.activeColor = RED;
            b.enabled = owner && (co != CodeOnlyMath.CO_OFF || enableLimit(s) == CodeOnlyUiMath.EL_NONE);
        } else if (b.id >= B_CO_PER && b.id < B_CO_PER + CodeOnlyMath.CARD_PERIODS.length) {
            int p = CodeOnlyMath.CARD_PERIODS[b.id - B_CO_PER];
            b.active = coPeriods() == p;
            b.enabled = owner && CodeOnlyUiMath.periodAllowed(p, com.sva.net.ServerConfigSVA.adCodeCardMaxPeriods());
        } else if (b.id >= B_CO_OPT && b.id < B_CO_OPT + CodeOnlyMath.OPT_KEYS.length) {
            int bit = 1 << b.id - B_CO_OPT;
            b.active = CodeOnlyUiMath.optOn(coOpts(), bit, manual);
            b.enabled = CodeOnlyUiMath.optEnabled(bit, owner, manual);
        } else if (b.id == B_CO_REKEY) {
            boolean armed = CodeOnlyUiMath.confirmed(rekeyAt, now);
            b.displayString = Lang.tr(armed ? "sva.ad.co.set.rekeySure" : "sva.ad.co.set.rekey");
            b.active = armed;
            b.activeColor = RED;
            b.enabled = owner;
        } else if (b.id == B_CO_ALLROE) {
            b.enabled = owner && s != null && s.getTagList("Launchers", 10).tagCount() > 0;
        }
    }

    /**
     * Item 24: the «Опознавание» tab - the mode and what it is now (the arming's seconds, forbidden by the server, no day key),
     * the cards' term, the options, the key's generation and the next code's minutes, «Все ПУ», the launchers by their rule,
     * the readiness line, the aircraft without the key's code and the warning: without a card it fires at your machines too.
     */
    private void drawIdent(NBTTagCompound s) {
        int x = left + 8, co = s.getByte("Co");
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.co.set.title"), x, top + 36, 150, CYAN, false, 0, 0);
        String st = co == CodeOnlyMath.CO_ARMING ? Lang.tr("sva.ad.co.state.arming", Integer.valueOf(s.getInteger("CoArm")))
                : Lang.tr("sva.ad.co.state." + CodeOnlyMath.CO_KEYS[Math.max(0, Math.min(CodeOnlyMath.CO_KEYS.length - 1, co))]);
        String now = Lang.tr("sva.ad.co.set.state", st);
        int nw = Math.min(fontRendererObj.getStringWidth(now), W - 170);
        TextFitSC.draw(fontRendererObj, now, left + W - 8 - nw, top + 36, nw, co == CodeOnlyMath.CO_ON ? RED : co == CodeOnlyMath.CO_OFF ? MUTED : AMBER,
                false, 0, 0);
        fontRendererObj.drawString(Lang.tr("sva.ad.co.set.mode"), x, top + 53, MUTED);
        int el = enableLimit(s);
        String lim = el == CodeOnlyUiMath.EL_NONE ? null : Lang.tr("sva.ad.co.set." + CodeOnlyUiMath.EL_KEYS[el]);
        if (lim != null) {
            TextFitSC.draw(fontRendererObj, lim, left + 74 + 168, top + 53, W - 74 - 168 - 8, AMBER, false, 0, 0);
        }
        fontRendererObj.drawString(Lang.tr("sva.ad.co.set.periods"), x, top + 73, MUTED);
        fontRendererObj.drawString(Lang.tr("sva.ad.co.set.opts"), x, top + 93, MUTED);
        if (!s.getBoolean("CoManual")) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.co.set.manualServer"), left + 74, top + 120, W - 74 - 136, AMBER, false, 0, 0);
        }
        String key = Lang.tr("sva.ad.co.set.key", Integer.valueOf(s.getInteger("KeyGen")), Integer.valueOf(CodeOnlyUiMath.minutes(s.getInteger("KeyLeft"))));
        TextFitSC.draw(fontRendererObj, key, x, top + 131, W - 8 - 124 - 12 - 8, MUTED, false, 0, 0);
        // the launchers by their rule (the summary's first line) right of «Все ПУ»
        TextFitSC.draw(fontRendererObj, roesLine(s), left + 74 + 170, top + 149, W - 74 - 170 - 8, TEXT, false, 0, 0);
        int r = ready(s);
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.co.ready." + CodeOnlyMath.READY_KEYS[r]), x, top + 166, W - 16, r == CodeOnlyMath.R_READY ? GREEN
                : AMBER, false, 0, 0);
        int[] nk = CodeOnlyUiMath.withoutCode(s.getIntArray("CoSum"));
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.co.dlg.air", Integer.valueOf(nk[0]), Integer.valueOf(nk[1])), x, top + 178, W - 16, nk[1] > 0 ? AMBER
                : TEXT, false, 0, 0);
        List<String> note = fontRendererObj.listFormattedStringToWidth(Lang.tr("sva.ad.co.set.note"), W - 16);
        for (int i = 0; i < note.size() && i < 4; i++) {
            fontRendererObj.drawString(note.get(i), x, top + 194 + i * 10, i == 0 ? RED : MUTED);
        }
        if (!s.getBoolean("Owner")) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.co.set.ownerOnly"), x, top + H - 14, W - 16, RED, false, 0, 0);
        }
    }

    /** Item 27: «ПУ: 2 - по нарушителям, 1 - по всем» (the picture's CoSum) or «ПУ: нет привязанных». */
    private static String roesLine(NBTTagCompound s) {
        List<int[]> l = CodeOnlyUiMath.summaryRoes(s.getIntArray("CoSum"));
        if (l.isEmpty()) {
            return Lang.tr("sva.ad.co.dlg.noLaunchers");
        }
        StringBuilder b = new StringBuilder();
        for (int[] e : l) {
            b.append(b.length() > 0 ? ", " : "").append(Lang.tr("sva.ad.co.dlg.item", Integer.valueOf(e[1]), Lang.tr("sva.ad.co.dlg.roe." + RoeMath.ROE_KEYS[e[0]])));
        }
        return Lang.tr("sva.ad.co.dlg.roes", b.toString());
    }

    /**
     * Item 27: the confirmation window - the summary (the launchers by their rule, the aircraft without the key's code and of
     * them the owner's / his list's), the warning, the arming, the server's mode; «Включить» (the second step: «Sure»), «Все ПУ:
     * по всем без кода», «Отмена».
     */
    private void drawDialog(NBTTagCompound s, int mx, int my) {
        int[][] d = CodeOnlyUiMath.dialog(left, top, W, H);
        drawRect(0, 0, width, height, 0x90000000);
        drawRect(d[0][0] - 1, d[0][1] - 1, d[0][2] + 1, d[0][3] + 1, 0xFFF06060);
        drawRect(d[0][0], d[0][1], d[0][2], d[0][3], 0xF8141820);
        int x = d[0][0] + 6, y = d[0][1] + 5, w = CodeOnlyUiMath.DLG_W - 12;
        fontRendererObj.drawString(Lang.tr("sva.ad.co.dlg.title"), x, y, GOLD);
        y += 13;
        List<Object[]> lines = new ArrayList<Object[]>();
        if (s != null) {
            int[] nk = CodeOnlyUiMath.withoutCode(s.getIntArray("CoSum"));
            lines.add(new Object[] {roesLine(s), Integer.valueOf(TEXT)});
            lines.add(new Object[] {Lang.tr("sva.ad.co.dlg.air", Integer.valueOf(nk[0]), Integer.valueOf(nk[1])), Integer.valueOf(nk[1] > 0 ? AMBER : TEXT)});
            lines.add(new Object[] {Lang.tr("sva.ad.co.dlg.warn"), Integer.valueOf(RED)});
            lines.add(new Object[] {Lang.tr("sva.ad.co.dlg.arm", Integer.valueOf(com.sva.net.ServerConfigSVA.adCodeOnlyArmSeconds())), Integer.valueOf(MUTED)});
            // the user's request 2026-10-27: the server's line as it will be with the mode on (codeOnlyFire: fire in "warnings" too)
            String cm = s.hasKey("CoMode") ? s.getString("CoMode") : s.getString("Mode");
            lines.add(new Object[] {Lang.tr("sva.ad.l.mode." + cm), Integer.valueOf(CodeOnlyMath.M_FIRE.equals(cm) ? MUTED : AMBER)});
        }
        int bottom = d[1][1] - 3;
        for (Object[] l : lines) {
            for (Object o : fontRendererObj.listFormattedStringToWidth((String) l[0], w)) {
                if (y + 9 <= bottom) {
                    fontRendererObj.drawString((String) o, x, y, ((Integer) l[1]).intValue());
                }
                y += 10;
            }
        }
        String[] labels = {Lang.tr("sva.ad.co.dlg.ok"), Lang.tr("sva.ad.co.set.allRoe"), Lang.tr("sva.ad.co.dlg.cancel")};
        int[] colours = {RED, AMBER, TEXT};
        boolean owner = s != null && s.getBoolean("Owner");
        for (int i = 0; i < labels.length; i++) {
            int[] b = d[1 + i];
            boolean on = owner || i == 2;
            boolean hov = on && mx >= b[0] && my >= b[1] && mx < b[0] + b[2] && my < b[1] + b[3];
            drawRect(b[0], b[1], b[0] + b[2], b[1] + b[3], hov ? 0xFF7A8496 : 0xFF3A4150);
            drawRect(b[0] + 1, b[1] + 1, b[0] + b[2] - 1, b[1] + b[3] - 1, hov ? 0xFF3A4252 : 0xFF2E3542);
            TextFitSC.drawCentered(fontRendererObj, labels[i], b[0] + 2, b[1] + (b[3] - 8) / 2, b[2] - 4, on ? colours[i] : 0x6A7282, false, 0, 0);
        }
    }

    /** The confirmation window's buttons: «Включить» sends the mode with «Sure», «Все ПУ» their rule (stays open), «Отмена» shuts it. */
    private void dialogClick(int mx, int my, int button) {
        if (button != 0) {
            return;
        }
        int[][] d = CodeOnlyUiMath.dialog(left, top, W, H);
        boolean owner = last != null && last.getBoolean("Owner");
        for (int i = 0; i < 3; i++) {
            int[] b = d[1 + i];
            if (mx < b[0] || my < b[1] || mx >= b[0] + b[2] || my >= b[1] + b[3]) {
                continue;
            }
            if (i == 0 && owner) {
                NBTTagCompound c = new NBTTagCompound();
                c.setString("Op", "codeOnly");
                c.setBoolean("On", true);
                c.setBoolean("Sure", true);
                cmd(c);
                dialog = false;
            } else if (i == 1 && owner) {
                cmd("coAllRoe", 0);
            } else if (i == 2) {
                dialog = false;
            }
            mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(new net.minecraft.util.ResourceLocation("gui.button.press"),
                    1F));
            return;
        }
    }

    // ------------------------------------------------------------------ the map's terrain (as GuiMapSVA's, MAP x MAP)

    private void buildTerrain(int now) {
        World w = mc.theWorld;
        if (w == null) {
            return;
        }
        if (terrain == null) {
            terrain = new DynamicTexture(MAP, MAP);
        }
        int[] px = terrain.getTextureData();
        double s = scale();
        for (int y = 0; y < MAP; y++) {
            for (int x = 0; x < MAP; x++) {
                int bx = MathHelper.floor_double(cx + (x - MAP / 2 + 0.5) * s), bz = MathHelper.floor_double(cz + (y - MAP / 2 + 0.5) * s);
                Chunk c = w.getChunkFromBlockCoords(bx, bz);
                if (c == null || c.isEmpty()) {
                    px[y * MAP + x] = ((bx >> 4) + (bz >> 4) & 1) == 0 ? UNKNOWN : 0xFF181C23;
                    continue;
                }
                int yy = Math.max(0, w.getHeightValue(bx, bz) - 1);
                Block b = w.getBlock(bx, yy, bz);
                while (yy > 0 && b.getMaterial() == net.minecraft.block.material.Material.air) {
                    b = w.getBlock(bx, --yy, bz);
                }
                MapColor mcol = b.getMapColor(w.getBlockMetadata(bx, yy, bz));
                int col = mcol == null || mcol.colorValue == 0 ? 0x5A5A5A : mcol.colorValue;
                px[y * MAP + x] = 0xFF000000 | col;
            }
        }
        terrain.updateDynamicTexture();
        builtX = cx;
        builtZ = cz;
        builtScale = s;
        builtAt = now;
    }

    // ------------------------------------------------------------------ shapes

    private static void ring(double x, double y, double r, int rgb) {
        int n = 20;
        for (int k = 0; k < n; k++) {
            double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
            AirDefenseClientSVA.edge(x + Math.cos(a0) * r, y + Math.sin(a0) * r, x + Math.cos(a1) * r, y + Math.sin(a1) * r, 1.2, rgb, false);
        }
    }

    private static void diamondOutline(double x, double y, double r, int rgb) {
        AirDefenseClientSVA.edge(x, y - r, x + r, y, 1.4, rgb, false);
        AirDefenseClientSVA.edge(x + r, y, x, y + r, 1.4, rgb, false);
        AirDefenseClientSVA.edge(x, y + r, x - r, y, 1.4, rgb, false);
        AirDefenseClientSVA.edge(x - r, y, x, y - r, 1.4, rgb, false);
    }

    private static void tri(double x, double y, double r, int rgb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLES);
        t.addVertex(x, y - r, 0);
        t.addVertex(x - r, y + r * 0.8, 0);
        t.addVertex(x + r, y + r * 0.8, 0);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }
}
