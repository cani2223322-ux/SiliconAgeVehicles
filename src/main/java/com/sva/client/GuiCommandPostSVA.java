package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
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
 * </ul>
 */
public class GuiCommandPostSVA extends GuiScreen {

    private static final int W = 420, H = 266, MAP_X = 6, MAP_Y = 32, MAP = 204, RX = 216, RW = 198;
    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, LINE = 0xFF3A4150, PANEL = 0xFF14171D, GOLD = 0xF2A040, TEXT = 0xD8DCE4,
            MUTED = 0x8A93A3, AMBER = 0xF0B040, RED = 0xF06060, GREEN = 0x62E06A, CYAN = 0x5EC8F0, UNKNOWN = 0xFF101318;
    private static final int B_TAB = 1, B_ALARM = 10, B_AUTO = 11, B_PRINT = 12, B_SEND = 13, B_ZOOM_OUT = 14, B_ZOOM_IN = 15,
            B_Z_SAVE = 20, B_Z_NEW = 21, B_Z_DEL = 22, B_Z_SHAPE = 23, B_Z_TYPE = 24, B_Z_SHOW = 28, B_Z_ALARM = 29, B_Z_HERE = 30,
            B_S_SAVE = 40, B_S_KEY = 41, B_S_SECRET = 42, B_S_NEWSECRET = 43;

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
    private GuiTextField zName, zA, zB, zC, zD, zLo, zHi, zCodeLo, zCodeHi, zCourse;
    // settings
    private GuiTextField sName, sSecret, sWhite, sAccess;
    private final GuiTextField[] sCodes = new GuiTextField[8];
    private boolean settingsLoaded, dayKey;
    private final List<GuiTextField> fields = new ArrayList<GuiTextField>();

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
        String[] tabs = {"map", "zones", "settings"};
        for (int i = 0; i < 3; i++) {
            GuiCargoSVA.Dark b = new GuiCargoSVA.Dark(B_TAB + i, left + 6 + i * 64, top + 18, 62, 12, Lang.tr("sva.ad.kp.tab." + tabs[i]), null);
            b.tab = true;
            buttonList.add(b);
        }
        int rx = left + RX;
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
            zCodeLo = field(rx + 58, top + 200, 30, 4);
            zCodeHi = field(rx + 92, top + 200, 30, 4);
            zCourse = field(rx + 166, top + 200, 30, 3);
            buttonList.add(new GuiCargoSVA.Dark(B_Z_SHOW, rx, top + 214, 96, 13, Lang.tr("sva.ad.zone.show"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_ALARM, rx + 100, top + 214, 98, 13, Lang.tr("sva.ad.zone.alarm"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_SAVE, rx, top + 230, 64, 13, Lang.tr("sva.ad.zone.save"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_NEW, rx + 67, top + 230, 64, 13, Lang.tr("sva.ad.zone.new"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_DEL, rx + 134, top + 230, 64, 13, Lang.tr("sva.ad.zone.delete"), null));
            buttonList.add(new GuiCargoSVA.Dark(B_Z_HERE, rx, top + 246, RW, 13, Lang.tr("sva.ad.zone.here"), Lang.tr("sva.ad.zone.here.tip")));
            loadZone(zoneSel);
        } else {
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
            buttonList.add(new GuiCargoSVA.Dark(B_S_SAVE, left + W - 106, top + 210, 100, 14, Lang.tr("sva.ad.set.save"), null));
            settingsLoaded = false;
        }
    }

    private void setTab(int t) {
        tab = t;
        menuX = -1;
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
        if (b.id >= B_TAB && b.id < B_TAB + 3) {
            setTab(b.id - B_TAB);
            return;
        }
        switch (b.id) {
            case B_ZOOM_OUT:
                zoom = MapMath.zoomStep(zoom, 1);
                return;
            case B_ZOOM_IN:
                zoom = MapMath.zoomStep(zoom, -1);
                return;
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
                edit.shape = edit.shape == ZoneMath.RECT ? ZoneMath.CIRCLE : ZoneMath.RECT;
                if (edit.shape == ZoneMath.CIRCLE && edit.r <= 1) {
                    edit.cx = (edit.x0 + edit.x1) / 2;
                    edit.cz = (edit.z0 + edit.z1) / 2;
                    edit.r = Math.max(8, (edit.x1 - edit.x0) / 2);
                }
                writeZoneFields();
                return;
            case B_Z_SHOW:
                edit.show = !edit.show;
                return;
            case B_Z_ALARM:
                edit.alarm = !edit.alarm;
                return;
            case B_Z_HERE:
                readZoneFields();
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
                }
                return;
            }
            case B_Z_NEW:
                zoneSel = -1;
                loadZone(-1);
                return;
            case B_Z_DEL:
                if (zoneSel >= 0) {
                    NBTTagCompound d = new NBTTagCompound();
                    d.setString("Op", "zoneDel");
                    d.setInteger("Idx", zoneSel);
                    cmd(d);
                    zoneSel = -1;
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
            default:
                if (b.id >= B_Z_TYPE && b.id < B_Z_TYPE + ZoneMath.TYPES) {
                    edit.type = b.id - B_Z_TYPE;
                }
        }
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
        cmd(d);
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
    }

    @Override
    protected void keyTyped(char c, int key) {
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
                f.textboxKeyTyped(c, key);
                return;
            }
        }
        if (key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(null);
        }
    }

    private boolean overMap(int mx, int my) {
        return tab != 2 && mx >= left + MAP_X && mx < left + MAP_X + MAP && my >= top + MAP_Y && my < top + MAP_Y + MAP;
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

    @Override
    protected void mouseClicked(int mx, int my, int button) {
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
        super.mouseClicked(mx, my, button);
        if (overMap(mx, my)) {
            double s = scale(), ox = left + MAP_X + MAP / 2.0, oy = top + MAP_Y + MAP / 2.0;
            if (tab == 1) {
                readZoneFields();
                double[] w = MapMath.toWorld(mx - ox, my - oy, cx, cz, s);
                int bx = MathHelper.floor_double(w[0]), bz = MathHelper.floor_double(w[1]);
                if (edit.shape == ZoneMath.CIRCLE && button == 0) {
                    edit.cx = bx;
                    edit.cz = bz;
                } else if (edit.shape == ZoneMath.CIRCLE && button == 1) {
                    edit.r = Math.max(1, Math.min(ZoneMath.MAX_HALF, (int) Math.round(Math.hypot(bx - edit.cx, bz - edit.cz))));
                } else if (button == 0) {
                    edit.x0 = bx;
                    edit.z0 = bz;
                } else {
                    edit.x1 = bx;
                    edit.z1 = bz;
                }
                writeZoneFields();
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
        menu.add(new String[] {Lang.tr("sva.ad.kp.menu.msg"), "msg", ""});
        menuX = Math.min(mx, left + W - 132);
        menuY = Math.min(my, top + H - menu.size() * 11 - 16);
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long time) {
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
            fontRendererObj.drawString(mode, left + W - 6 - fontRendererObj.getStringWidth(mode), top + 20, "fire".equals(s.getString("Mode")) ? RED : AMBER);
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
            }
        }
        if (s == null) {
            fontRendererObj.drawString(Lang.tr("sva.ad.l.wait"), left + 10, top + 40, MUTED);
        } else if (tab == 2) {
            drawSettings(s);
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
            int c = "grant".equals(r[1]) ? GREEN : "msg".equals(r[1]) ? CYAN : TEXT;
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
        List<ZoneMath.Zone> zs = tab == 1 ? withEdit() : zones();
        AirDefenseClientSVA.drawZones(fontRendererObj, zs, ox, oy, cx, cz, sc, tab == 1 ? (zoneSel >= 0 ? zoneSel : zs.size() - 1) : -1);
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
            String l1 = code(t.getInteger("Code")) + (t.getInteger("Board") > 0 ? " B-" + t.getInteger("Board") : "");
            String l2 = alt(t) + " " + String.format("%03d", Integer.valueOf(t.getInteger("Spd"))) + " " + String.format("%03d", Integer.valueOf(t.getInteger("Crs")));
            GL11.glPushMatrix();
            GL11.glTranslated(x + 6, y - 9, 0);
            GL11.glScalef(0.75F, 0.75F, 1F);
            fontRendererObj.drawStringWithShadow(l1, 0, 0, c);
            fontRendererObj.drawStringWithShadow(l2, 0, 10, c);
            GL11.glPopMatrix();
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        fontRendererObj.drawString(Lang.tr("sva.ad.kp.scale", Integer.valueOf((int) sc)), mapL + 30, mapT + MAP + 5, MUTED);
        if (tab == 0) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.kp.mapHint"), mapL + 96, mapT + MAP + 5, MAP - 96, MUTED, false, 0, 0);
        } else {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.ad.zone.mapHint"), mapL + 96, mapT + MAP + 5, MAP - 96, MUTED, false, 0, 0);
        }
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
            String[][] rows = {
                    {Lang.tr("sva.ad.f.code"), t.getInteger("Code") < 0 ? Lang.tr("sva.ad.f.noReply") : code(t.getInteger("Code")) + " ("
                            + Lang.tr("sva.xpdr.mode." + XpdrMath.MODE_KEYS[Math.max(0, Math.min(4, mode))]) + ")"},
                    {Lang.tr("sva.ad.f.alt"), t.getBoolean("Alt") ? alt(t) : Lang.tr("sva.ad.f.unknown")},
                    {Lang.tr("sva.ad.f.speed"), Lang.tr("sva.ad.f.speedVal", Integer.valueOf(t.getInteger("Spd")), String.format("%03d", Integer.valueOf(t.getInteger("Crs"))))},
                    {Lang.tr("sva.ad.f.type"), Lang.tr("item.siliconagevehicles.vehicle." + VehicleType.byOrdinal(t.getByte("Type")).key + ".name")
                            + (t.getInteger("Board") > 0 ? " B-" + t.getInteger("Board") : "")},
                    {Lang.tr("sva.ad.f.zone"), t.getString("Zone").length() > 0 ? t.getString("Zone") : "-"},
                    {Lang.tr("sva.ad.f.seen"), seen(t.getByte("Seen"))},
                    {Lang.tr("sva.ad.f.assigned"), assignedText(t)}};
            for (int i = 0; i < rows.length; i++) {
                fontRendererObj.drawString(rows[i][0], rx + 4, y + 14 + i * 9, MUTED);
                TextFitSC.draw(fontRendererObj, rows[i][1], rx + 70, y + 14 + i * 9, RW - 74, i == 6 && t.getByte("St") >= RoeMath.ST_LOCK ? RED : TEXT,
                        false, 0, 0);
            }
        }
        // the launchers' line
        NBTTagList ll = s.getTagList("Launchers", 10);
        StringBuilder lb = new StringBuilder();
        for (int i = 0; i < ll.tagCount(); i++) {
            NBTTagCompound l = ll.getCompoundTagAt(i);
            lb.append(i > 0 ? "  " : "").append(Lang.tr("sva.ad.kp.launcherShort", Integer.valueOf(i + 1))).append(' ')
                    .append(Lang.tr("sva.ad.roe.short." + RoeMath.ROE_KEYS[Math.max(0, Math.min(3, l.getByte("Roe")))])).append(' ').append(l.getInteger("Ammo"));
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
            String line = String.format("%02d", Integer.valueOf(r.getInteger("No"))) + " " + code(r.getInteger("Code")) + " "
                    + (r.getInteger("Board") > 0 ? String.format("B-%02d", Integer.valueOf(r.getInteger("Board"))) : "  -  ") + " " + alt(r) + " " + cls
                    + (r.getInteger("Grant") > 0 ? " " + Lang.tr("sva.ad.kp.grantLeft", Integer.valueOf(r.getInteger("Grant") / 60 + 1)) : "");
            TextFitSC.draw(fontRendererObj, line, rx + 2, ry + 1, RW - 4, clsColour(r, tick), false, 0, 0);
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
            int c = key.contains("alarm") || key.contains("emergency") || key.contains("launch") || key.contains("fire") || key.contains("zoneIn") || key.contains("hit")
                    ? RED : key.contains("ident") || key.contains("grant") ? GREEN : TEXT;
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
        }
        zD.setVisible(!c);
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
