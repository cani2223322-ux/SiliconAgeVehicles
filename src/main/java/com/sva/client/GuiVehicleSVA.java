package com.sva.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.sc.inventory.TextFitSC;
import com.sc.manual.Lang;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.entity.EntityVehicleSVA;
import com.sva.init.ModContentSVA;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.KeyBindMath;
import com.sva.vehicle.SkinMath;
import com.sva.net.NetSVA;
import com.sva.vehicle.SpaceMath;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * «Техника» - the vehicle functions menu (key key.sva.menu, ` by default; the addon's one key in Options > Controls),
 * like the core's armour screen (K): a row per function of the vehicle you sit in - short name (the long explanation
 * on hover), its state from the synced vehicle (on / off / mode / ready / cooldown), a button that does what the key
 * does (the same packet), and the key button: click - «нажмите клавишу...» - any key with Ctrl / Shift / Alt or a
 * middle / side mouse button; while waiting Esc cancels and Backspace / Delete unbinds; right-click - the default.
 * Above the rows a status strip (charge, heat, hull, the gases, tuning, the trailer).
 * <p>
 * A passenger sees the same rows; the driver's functions are read-only for him (their buttons off, «только водитель»).
 * On foot: «Сядьте в машину» and every function's key (so they can be set before a ride), the map works.
 * Scales down on small screens: the panel narrows to the screen, the rows get lower.
 */
public class GuiVehicleSVA extends GuiScreen {

    private static final int BG = 0xFF1B1F27, EDGE = 0xFF0B0D11, LINE = 0xFF3A4150, TRACK = 0xFF0E1014, GOLD = 0xF2A040, CYAN = 0x5EC8F0,
            TEXT = 0xD8DCE4, MUTED = 0x8A93A3, GREEN = 0x62E06A, RED = 0xF06060, AMBER = 0xF0B040, VIOLET = 0xC85AFF;
    private static final int MAX_W = 340, MIN_W = 250, KEY_W = 58, ACT_W = 60;
    private static final int B_ACT = 100, B_KEY = 200, B_RESET_ALL = 1,
            /** Hydrogen wave: the «Аккумулятор» row's «Снять». */
            B_BAT = 2,
            /** Air-defence wave: the «Ответчик» row's «Настроить» (the transponder panel, GuiTransponderSVA). */
            B_XPDR = 3,
            /** Round 2, D2 (★17): the overhead switches' buttons (B_SW + SystemsMath.SW_*), a row under the status strip. */
            B_SW = 300;
    /** D2: the switch row's height (aircraft with the board systems). */
    private static final int SW_ROW = 15;

    private EntityVehicleSVA vehicle;
    private VehicleType type;
    private boolean driver;
    private final List<VehicleFn> rows = new ArrayList<VehicleFn>();
    private int left, top, w, h, rowH, listY, stripH;
    private VehicleFn capturing;
    private String sig = "";

    public GuiVehicleSVA() {
    }

    /** What the rows depend on: the vehicle, the seat, the type. */
    private String signature() {
        EntityVehicleSVA v = mc.thePlayer == null ? null : HudVehicleSVA.vehicleOf(mc.thePlayer);
        return v == null || v.isDead ? "-" : v.getEntityId() + ":" + (mc.thePlayer.ridingEntity == v) + ":" + v.getVehicleType().ordinal();
    }

    @Override
    public void initGui() {
        EntityVehicleSVA v = mc.thePlayer == null ? null : HudVehicleSVA.vehicleOf(mc.thePlayer);
        vehicle = v == null || v.isDead ? null : v;
        type = vehicle == null ? null : vehicle.getVehicleType();
        driver = vehicle != null && mc.thePlayer.ridingEntity == vehicle;
        sig = signature();
        rows.clear();
        for (VehicleFn f : VehicleFn.values()) {
            if (type == null || f.appliesTo(type)) {
                rows.add(f);
            }
        }
        w = Math.max(MIN_W, Math.min(MAX_W, width - 8));
        stripH = vehicle == null ? 24 : 36 + (swRow() ? SW_ROW : 0);
        int fixed = 18 + stripH + 12 + 20;                       // title, strip, the column heads, the footer
        int n = rows.size() + (batRow() ? 1 : 0) + (xpdrRow() ? 1 : 0);
        rowH = Math.max(12, Math.min(15, (height - 8 - fixed) / Math.max(1, n)));
        h = fixed + n * rowH + 4;
        left = (width - w) / 2;
        top = Math.max(2, (height - h) / 2);
        listY = top + 18 + stripH + 12;
        buttonList.clear();
        int bh = rowH - 2;
        for (int i = 0; i < rows.size(); i++) {
            VehicleFn f = rows.get(i);
            int y = listY + i * rowH;
            GuiCargoSVA.Dark act = new GuiCargoSVA.Dark(B_ACT + f.ordinal(), left + w - 6 - KEY_W - 4 - ACT_W, y, ACT_W, bh, "", null);
            act.visible = !f.hold && (vehicle != null || f == VehicleFn.MAP);
            buttonList.add(act);
            buttonList.add(new GuiCargoSVA.Dark(B_KEY + f.ordinal(), left + w - 6 - KEY_W, y, KEY_W, bh, "", null));
        }
        buttonList.add(new GuiCargoSVA.Dark(B_RESET_ALL, left + w - 6 - 96, top + h - 17, 96, 13, Lang.tr("sva.vmenu.resetAll"), null));
        if (batRow()) {
            // hydrogen wave: «Аккумулятор» - the last row; «Снять» where the action buttons are, the key column «-»
            buttonList.add(new GuiCargoSVA.Dark(B_BAT, left + w - 6 - KEY_W - 4 - ACT_W, listY + rows.size() * rowH, ACT_W, bh,
                    Lang.tr("sva.vmenu.act.batOut"), null));
        }
        if (xpdrRow()) {
            buttonList.add(new GuiCargoSVA.Dark(B_XPDR, left + w - 6 - KEY_W - 4 - ACT_W, listY + (rows.size() + (batRow() ? 1 : 0)) * rowH, ACT_W, bh,
                    Lang.tr("sva.vmenu.act.setup"), null));
        }
        if (swRow()) {
            // D2: «Пульт» - the seven overhead switches (the same packet as the cockpit's would be: Action A_SWITCH + i)
            int sy = top + 19 + 36, lw = 34, bw = (w - 12 - lw) / SystemsMath.SWITCHES;
            for (int i = 0; i < SystemsMath.SWITCHES; i++) {
                buttonList.add(new GuiCargoSVA.Dark(B_SW + i, left + 6 + lw + i * bw, sy, bw - 2, SW_ROW - 3, "", null));
            }
        }
        refresh();
    }

    /** A key-binding button (B_KEY .. under B_SW). */
    private static boolean isKey(int id) {
        return id >= B_KEY && id < B_SW;
    }

    /** Hydrogen wave: the vehicle runs on hydrogen - the «Аккумулятор» row. */
    private boolean batRow() {
        return vehicle != null && vehicle.onHydrogen();
    }

    /** Air-defence wave: the vehicle has a transponder (everything that flies) - the «Ответчик» row. */
    private boolean xpdrRow() {
        return vehicle != null && com.sva.airdefense.XpdrMath.fitted(type);
    }

    /** D2: the vehicle has the overhead switches (the light plane, the jet, the tiltrotor). */
    private boolean swRow() {
        return vehicle != null && SystemsMath.applies(type);
    }

    // ------------------------------------------------------------------ rows

    private void refresh() {
        for (Object o : buttonList) {
            GuiCargoSVA.Dark b = (GuiCargoSVA.Dark) o;
            if (b.id == B_XPDR) {
                b.enabled = driver;                              // only the pilot sets the transponder
                continue;
            }
            if (b.id == B_BAT) {
                // greyed while the engine runs / there is none (the server checks the right too)
                b.enabled = vehicle != null && vehicle.batteryPresent() && vehicle.engineState() == SystemsMath.OFF;
                continue;
            }
            if (b.id >= B_SW) {
                int i = b.id - B_SW;
                boolean on = vehicle != null && vehicle.switchOn(i);
                b.displayString = Lang.tr("sva.sys.sw." + SystemsMath.SW_KEYS[i] + ".short");
                b.enabled = driver;
                b.active = on;
                b.activeColor = 0xFF000000 | SystemsMath.SW_COLOUR[i];
            } else if (isKey(b.id)) {
                VehicleFn f = VehicleFn.values()[b.id - B_KEY];
                int[] k = VehicleKeyBindsSVA.get(f);
                b.active = f == capturing;
                b.displayString = f == capturing ? "§e" + Lang.tr("sva.vmenu.press")
                        : !VehicleKeyBindsSVA.clashes(f, type).isEmpty() || VehicleKeyBindsSVA.clashesMenu(f) ? "§c" + VehicleKeyBindsSVA.describe(k)
                        : k == null ? "§7-" : VehicleKeyBindsSVA.describe(k);
            } else if (b.id >= B_ACT) {
                VehicleFn f = VehicleFn.values()[b.id - B_ACT];
                b.displayString = actionLabel(f);
                b.enabled = vehicle != null ? !f.driverOnly || driver : f == VehicleFn.MAP;
                b.active = vehicle != null && isOn(f);
                b.activeColor = GREEN | 0xFF000000;
            }
        }
    }

    /** A switch that is on now (the action button lit). */
    private boolean isOn(VehicleFn f) {
        switch (f) {
            case DRILL:
                return vehicle.isDrillOn();
            case ANCHOR:
                return vehicle.isAnchored();
            case CLOAK:
                return vehicle.isInvisible();
            case CRUISE:
                return driver ? CruiseClientSVA.isOn() : vehicle.isCruiseOn();
            case MODE:
                return type.longRange && !type.hasDrill && vehicle.isLongRangeOn();
            case AIRBRAKE:
                return vehicle.spoilersUp();
            case ENGINE:
                return vehicle.engineState() != SystemsMath.OFF;
            default:
                return false;
        }
    }

    private String actionLabel(VehicleFn f) {
        switch (f) {
            case DRILL:
            case ANCHOR:
            case CLOAK:
            case CRUISE:
                return Lang.tr(vehicle != null && isOn(f) ? "sva.vmenu.act.off" : "sva.vmenu.act.on");
            case MODE:
                return Lang.tr(type != null && type.longRange && !type.hasDrill ? (isOn(f) ? "sva.vmenu.act.off" : "sva.vmenu.act.on") : "sva.vmenu.act.switch");
            case JUMP:
                return Lang.tr("sva.vmenu.act.jump");
            case TARGET:
                return Lang.tr("sva.vmenu.act.switch");
            case CARGO:
            case MAP:
            case AP_PANEL:
                return Lang.tr("sva.vmenu.act.open");
            case FLAPS_UP:
                return Lang.tr("sva.vmenu.act.retract");
            case FLAPS_DOWN:
                return Lang.tr("sva.vmenu.act.extend");
            case AIRBRAKE:
                return Lang.tr(vehicle != null && vehicle.isAirbrake() ? "sva.vmenu.act.off" : "sva.vmenu.act.on");
            case ENGINE:
                return Lang.tr(vehicle != null && vehicle.engineState() != SystemsMath.OFF ? "sva.vmenu.act.stop" : "sva.vmenu.act.start");
            case MFD_PAGE:
                return Lang.tr("sva.vmenu.act.next");
            default:
                return "";
        }
    }

    /** The state column: text and colour (null text: nothing to show). */
    private Object[] state(VehicleFn f) {
        EntityVehicleSVA v = vehicle;
        switch (f) {
            case BOOST:
                return v.isBoosting() ? s("sva.vmenu.st.working", AMBER) : s("sva.vmenu.st.hold", MUTED);
            case DOWN:
                return s("sva.vmenu.st.hold", MUTED);
            case DRILL:
                if (!v.hasHead()) {
                    return s("sva.vmenu.st.noHead", RED);
                }
                return v.isDrillOn() ? s("sva.vmenu.st.on", GREEN) : s("sva.vmenu.st.off", MUTED);
            case MODE:
                if (type.hasDrill) {
                    return new Object[] {Lang.tr("sva.drill.mode." + Math.max(0, Math.min(2, v.getDrillMode()))), Integer.valueOf(CYAN)};
                }
                if (type.movement == com.sva.vehicle.Movement.TILT) {
                    double p = v.getTiltPos();
                    return p > 0 && p < 1 ? s("sva.vmenu.st.tiltMoving", AMBER)
                            : v.getTiltTarget() ? s("sva.vmenu.st.tiltPlane", CYAN) : s("sva.vmenu.st.tiltVertical", CYAN);
                }
                return v.isLongRangeActive() ? s("sva.vmenu.st.working", GREEN) : v.isLongRangeOn() ? s("sva.vmenu.st.longWait", AMBER)
                        : s("sva.vmenu.st.off", MUTED);
            case JUMP: {
                String[] info = v.getSpaceInfo().split(";", -1);
                int code = num(info, 1);
                if (v.isPortalOpening()) {
                    return s("sva.vmenu.st.portal", VIOLET);
                }
                if (code == SpaceMath.OK) {
                    return s("sva.vmenu.st.ready", GREEN);
                }
                if (code == SpaceMath.COOLDOWN) {
                    return new Object[] {Lang.tr("sva.vmenu.st.cooldown", Integer.valueOf(num(info, 5))), Integer.valueOf(AMBER)};
                }
                return code == SpaceMath.NO_TARGET ? s("sva.vmenu.st.noTarget", MUTED) : s("sva.vmenu.st.unavailable", RED);
            }
            case TARGET: {
                String[] info = v.getSpaceInfo().split(";", -1);
                if (num(info, 1) == SpaceMath.NO_TARGET) {
                    return s("sva.vmenu.st.none", MUTED);
                }
                if (num(info, 7) == 1) {
                    return s("sva.vmenu.st.home", TEXT);
                }
                String name = info.length > 8 && info[8].length() > 0 ? info[8] : "-";
                return new Object[] {name, Integer.valueOf(TEXT)};
            }
            case ANCHOR:
                return v.isAnchored() ? s("sva.vmenu.st.on", CYAN) : s("sva.vmenu.st.off", MUTED);
            case CLOAK:
                return v.isInvisible() ? s("sva.vmenu.st.on", VIOLET) : s("sva.vmenu.st.off", MUTED);
            case CRUISE: {
                boolean on = driver ? CruiseClientSVA.isOn() : v.isCruiseOn();
                if (on) {
                    return v.cruiseKind() == CruiseMath.AUTOPILOT ? s("sva.vmenu.st.autopilot", GREEN) : s("sva.vmenu.st.cruise", GREEN);
                }
                return v.cruiseKind() == CruiseMath.NONE ? s("sva.vmenu.st.notNow", MUTED) : s("sva.vmenu.st.off", MUTED);
            }
            case CARGO:
                return new Object[] {Lang.tr("sva.vmenu.st.cargo", Integer.valueOf(v.getCargoUsed()), Integer.valueOf(v.cargoCapacity())), Integer.valueOf(TEXT)};
            case FLAPS_UP: {
                // aircraft stage A: the setting; the overspeed warning in red
                com.sva.vehicle.HighLiftMath.Spec hs = com.sva.vehicle.HighLiftMath.spec(type);
                if (v.flapWarning() && hs != null) {
                    return new Object[] {Lang.tr("sva.vmenu.st.flapsWarn", Integer.valueOf(com.sva.vehicle.HighLiftMath.limitKmh(hs, v.getFlaps() + 1))),
                            Integer.valueOf(RED)};
                }
                return new Object[] {Lang.tr("sva.vmenu.st.flaps", Integer.valueOf(Math.round(v.getFlapAngle()))),
                        Integer.valueOf(v.getFlaps() > 0 ? AMBER : MUTED)};
            }
            case FLAPS_DOWN: {
                // «Предкрылки: АВТО» - the slats' state (the tiltrotor has none: where its flaperons work)
                com.sva.vehicle.HighLiftMath.Spec hs = com.sva.vehicle.HighLiftMath.spec(type);
                if (hs == null || !hs.slats) {
                    return s("sva.vmenu.st.flaperons", MUTED);
                }
                return v.slatsOut() ? s("sva.vmenu.st.slatsOut", CYAN) : s("sva.vmenu.st.slatsIn", MUTED);
            }
            case AP_PANEL: {
                // stage C: the modes held, or off
                com.sva.vehicle.AutopilotMath.Panel ap = driver ? CruiseClientSVA.panel(v) : v.apPanel();
                if (!ap.on) {
                    return v.cruiseKind() == CruiseMath.NONE ? s("sva.vmenu.st.notNow", MUTED) : s("sva.vmenu.st.off", MUTED);
                }
                StringBuilder b = new StringBuilder();
                for (int g = com.sva.vehicle.AutopilotMath.G_HDG; g <= com.sva.vehicle.AutopilotMath.G_ROUTE; g++) {
                    if (ap.mode(com.sva.vehicle.AutopilotMath.bit(g))) {
                        b.append(b.length() > 0 ? " " : "").append(Lang.tr("sva.ap.group." + com.sva.vehicle.AutopilotMath.GROUP_KEYS[g]));
                    }
                }
                return new Object[] {b.length() > 0 ? b.toString() : Lang.tr("sva.ap.on"), Integer.valueOf(GREEN)};
            }
            case AIRBRAKE:
                if (v.isAirbrake()) {
                    return s("sva.vmenu.st.on", AMBER);
                }
                return v.groundSpoilersUp() ? s("sva.vmenu.st.rollout", AMBER) : s("sva.vmenu.st.off", MUTED);
            case ENGINE: {
                // D2: «выкл.» / «стартер 20 %» / «прогрев 60 %» / «работает»; a failed engine red
                int es = v.engineState();
                if (!v.engineGated(type)) {
                    return s("sva.vmenu.st.engineAlways", MUTED);
                }
                if ((v.failures() & SystemsMath.F_ENGINE) != 0 && SystemsMath.lifts(es)) {
                    return s("sva.vmenu.st.engineFail", RED);
                }
                int pct = Math.round(100 * SystemsMath.progress(es, v.engineTicks(), v.crankTicksFor(type, false), v.warmTicksFor(type)));
                return new Object[] {Lang.tr("sva.sys.engine." + SystemsMath.ENGINE_KEYS[es], Integer.valueOf(pct)),
                        Integer.valueOf(es == SystemsMath.RUN ? GREEN : es == SystemsMath.OFF ? MUTED : AMBER)};
            }
            case MFD_PAGE: {
                // cockpit wave: the page this client's MFD shows (the keys under the screen in the cockpit); -1: no MFD
                int page = MfdPagesSVA.page(v);
                return page < 0 ? null : new Object[] {Lang.tr("sva.mfd.page." + com.sva.vehicle.GlassMath.PAGE_KEYS[page]), Integer.valueOf(CYAN)};
            }
            default:
                return null;
        }
    }

    private static Object[] s(String key, int color) {
        return new Object[] {Lang.tr(key), Integer.valueOf(color)};
    }

    private static int num(String[] p, int i) {
        try {
            return i < p.length ? Integer.parseInt(p[i].trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int mx, int my, float pt) {
        if (!sig.equals(signature())) {
            initGui();                                           // got in / out, another seat
        }
        refresh();
        TextFitSC.beginFrame();
        drawDefaultBackground();
        drawRect(left - 1, top - 1, left + w + 1, top + h + 1, EDGE);
        drawRect(left, top, left + w, top + h, BG);
        drawRect(left + 1, top + 1, left + w - 1, top + 2, LINE);
        drawRect(left + 3, top + 15, left + w - 3, top + 16, 0xFFE08A30);
        String title = vehicle == null ? Lang.tr("sva.vmenu.title")
                : Lang.tr("sva.vmenu.titleVehicle", Lang.tr("sva.map.name", new ItemStack(ModContentSVA.vehicle, 1, type.ordinal()).getDisplayName(),
                        SkinMath.boardText(vehicle.getBoardNumber())));
        String role = vehicle == null ? "" : Lang.tr(driver ? "sva.vmenu.driver" : "sva.vmenu.passenger");
        int rw = fontRendererObj.getStringWidth(role);
        TextFitSC.draw(fontRendererObj, title, left + 6, top + 4, w - 18 - rw, GOLD, false, 0, 0);
        fontRendererObj.drawString(role, left + w - 6 - rw, top + 4, driver ? CYAN : MUTED);
        int sy = top + 19;
        if (vehicle == null) {
            List<String> l = fontRendererObj.listFormattedStringToWidth(Lang.tr("sva.vmenu.onFoot"), w - 12);
            for (int i = 0; i < Math.min(2, l.size()); i++) {
                fontRendererObj.drawString(l.get(i), left + 6, sy + i * 10, AMBER);
            }
        } else {
            drawStrip(sy);
            if (swRow()) {
                fontRendererObj.drawString(Lang.tr("sva.vmenu.panel"), left + 6, sy + 36 + 2, MUTED);     // D2: «Пульт»
            }
        }
        drawRect(left + 3, listY - 13, left + w - 3, listY - 12, LINE);
        int nameW = nameWidth(), stateX = left + 6 + nameW + 4, stateW = left + w - 6 - KEY_W - 4 - ACT_W - 4 - stateX;
        fontRendererObj.drawString(Lang.tr("sva.vmenu.col.fn"), left + 6, listY - 10, MUTED);
        if (vehicle != null) {
            fontRendererObj.drawString(Lang.tr("sva.vmenu.col.state"), stateX, listY - 10, MUTED);
        }
        String kc = Lang.tr("sva.vmenu.col.key");
        fontRendererObj.drawString(kc, left + w - 6 - KEY_W + (KEY_W - fontRendererObj.getStringWidth(kc)) / 2, listY - 10, MUTED);
        for (int i = 0; i < rows.size(); i++) {
            VehicleFn f = rows.get(i);
            int y = listY + i * rowH, ty = y + (rowH - 2 - 8) / 2;
            if (i % 2 == 1) {
                drawRect(left + 3, y - 1, left + w - 3, y + rowH - 1, 0x18FFFFFF);
            }
            boolean locked = vehicle != null && f.driverOnly && !driver;
            TextFitSC.draw(fontRendererObj, Lang.tr(f.nameKey(type)), left + 6, ty, nameW, locked ? MUTED : TEXT, false, 0, 0);
            TextFitSC.hover(left + 6, y, nameW, rowH - 1, tip(f));
            if (vehicle != null) {
                Object[] st = state(f);
                if (st != null) {
                    TextFitSC.draw(fontRendererObj, (String) st[0], stateX, ty, stateW, ((Integer) st[1]).intValue(), false, 0, 0);
                }
                if (f == VehicleFn.JUMP) {
                    int code = num(vehicle.getSpaceInfo().split(";", -1), 1);
                    if (code != SpaceMath.OK && code != SpaceMath.COOLDOWN && code != SpaceMath.NO_TARGET) {
                        TextFitSC.hover(stateX, y, stateW, rowH - 1, Lang.tr("sva.hud.jump.why." + code, Integer.valueOf(SpaceMath.MIN_JUMP)));
                    }
                }
            }
        }
        if (batRow() && vehicle != null) {
            drawBatteryRow(listY + rows.size() * rowH, rows.size() % 2 == 1, nameW, stateX, stateW);
        }
        if (xpdrRow()) {
            int xi = rows.size() + (batRow() ? 1 : 0);
            drawXpdrRow(listY + xi * rowH, xi % 2 == 1, nameW, stateX, stateW);
        }
        String hint = Lang.tr("sva.vmenu.hint");
        TextFitSC.draw(fontRendererObj, hint, left + 6, top + h - 14, w - 12 - 100, MUTED, false, 0, 0);
        super.drawScreen(mx, my, pt);
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            if (!b.visible) {
                continue;
            }
            if (b.id >= B_SW) {
                if (vehicle != null) {
                    TextFitSC.hover(b.xPosition, b.yPosition, b.width, b.height, switchTip(b.id - B_SW));
                }
            } else if (isKey(b.id)) {
                TextFitSC.hover(b.xPosition, b.yPosition, b.width, b.height, keyTip(VehicleFn.values()[b.id - B_KEY]));
            } else if (b.id >= B_ACT) {
                VehicleFn f = VehicleFn.values()[b.id - B_ACT];
                if (vehicle != null && f.driverOnly && !driver) {
                    TextFitSC.hover(b.xPosition, b.yPosition, b.width, b.height, Lang.tr("sva.vmenu.driverOnly"));
                }
            } else if (b.id == B_RESET_ALL) {
                TextFitSC.hover(b.xPosition, b.yPosition, b.width, b.height, Lang.tr("sva.vmenu.resetAll.tip"));
            } else if (b.id == B_BAT && vehicle != null) {
                TextFitSC.hover(b.xPosition, b.yPosition, b.width, b.height, Lang.tr(vehicle.engineState() != SystemsMath.OFF
                        ? "sva.vmenu.bat.outEngine" : "sva.vmenu.bat.outTip"));
            }
        }
        List<String> tip = TextFitSC.hoverAt(mx, my);
        if (tip != null && !tip.isEmpty()) {
            List<String> wrapped = new ArrayList<String>();
            for (String line : tip) {
                wrapped.addAll(fontRendererObj.listFormattedStringToWidth(line, 220));
            }
            drawHoveringText(wrapped, mx, my, fontRendererObj);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /**
     * Hydrogen wave (mock-up veh_fuel_4_display_wear.png): the «Аккумулятор» row - the drawn arrow (up green: charging,
     * down amber: discharging - not a font glyph), %, EU/t, health «зд.92%»; «НЕТ АКБ» without one; the key column «-».
     */
    private void drawBatteryRow(int y, boolean odd, int nameW, int stateX, int stateW) {
        EntityVehicleSVA v = vehicle;
        int ty = y + (rowH - 2 - 8) / 2;
        if (odd) {
            drawRect(left + 3, y - 1, left + w - 3, y + rowH - 1, 0x18FFFFFF);
        }
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.vmenu.bat"), left + 6, ty, nameW, TEXT, false, 0, 0);
        List<String> tip = new ArrayList<String>();
        tip.add("\u00a7e" + Lang.tr("sva.vmenu.bat"));
        tip.add(Lang.tr("sva.vmenu.bat.tip"));
        if (v.batteryPresent()) {
            tip.add("\u00a77" + Lang.tr("sva.vmenu.bat.tip2", com.sva.FormatSVA.eu((long) v.batteryEu()), com.sva.FormatSVA.eu((long) v.batteryMax()),
                    com.sva.vehicle.RegenMath.fmt(v.generatorPerTick()), Integer.valueOf((int) Math.round(v.batteryHealth() * 100)),
                    Integer.valueOf((int) Math.round(v.batteryLimit() * 100)), Integer.valueOf(v.batteryServices())));
        }
        TextFitSC.hover(left + 6, y, nameW, rowH - 1, tip);
        if (!v.batteryPresent()) {
            TextFitSC.draw(fontRendererObj, Lang.tr("sva.hud.bat.none"), stateX, ty, stateW, RED, false, 0, 0);
        } else {
            int trend = v.batteryTrend();
            int x = stateX;
            if (trend != com.sva.vehicle.BatteryMath.FLAT_TREND) {
                HudVehicleSVA.arrow(x, ty, trend > 0, trend > 0 ? 0xFF000000 | GREEN : 0xFF000000 | AMBER);
                x += 8;
            }
            double h = v.batteryHealth();
            String s = Math.round(v.batteryFrac() * 100) + "% " + HudVehicleSVA.signed(v.batteryNetPerTick()) + Lang.tr("sva.vmenu.bat.perTick")
                    + " " + Lang.tr("sva.vmenu.bat.health", Integer.valueOf((int) Math.round(h * 100)));
            int c = v.batteryFrac() < com.sva.vehicle.BatteryMath.LOW ? RED : com.sva.vehicle.BatteryMath.needsService(h) ? AMBER : trend > 0 ? GREEN : trend < 0 ? AMBER : TEXT;
            TextFitSC.draw(fontRendererObj, s, x, ty, stateW - (x - stateX), c, false, 0, 0);
        }
        String dash = "-";
        fontRendererObj.drawString(dash, left + w - 6 - KEY_W + (KEY_W - fontRendererObj.getStringWidth(dash)) / 2, ty, MUTED);
    }

    /** Air-defence wave: the «Ответчик» row - the mode and the code («C 2471»), КЛЮЧ ДНЯ / IDENT; «Настроить» opens the panel. */
    private void drawXpdrRow(int y, boolean odd, int nameW, int stateX, int stateW) {
        int ty = y + (rowH - 2 - 8) / 2;
        if (odd) {
            drawRect(left + 3, y - 1, left + w - 3, y + rowH - 1, 0x18FFFFFF);
        }
        TextFitSC.draw(fontRendererObj, Lang.tr("sva.vmenu.xpdr"), left + 6, ty, nameW, driver ? TEXT : MUTED, false, 0, 0);
        TextFitSC.hover(left + 6, y, nameW, rowH - 1, Lang.tr("sva.vmenu.xpdr.tip"));
        int mode = AirDefenseClientSVA.xpdrMode(vehicle);
        String st = Lang.tr("sva.xpdr.mode." + com.sva.airdefense.XpdrMath.MODE_KEYS[mode]);
        if (com.sva.airdefense.XpdrMath.replies(mode)) {
            st += " " + com.sva.airdefense.XpdrMath.format(AirDefenseClientSVA.xpdrCode(vehicle));
        }
        if (AirDefenseClientSVA.xpdrKeyLamp(vehicle)) {
            st += " " + Lang.tr("sva.ad.xpdr.dayKey");
        }
        if (AirDefenseClientSVA.xpdrIdent(vehicle)) {
            st += " IDENT";
        }
        int c = AirDefenseClientSVA.xpdrCode(vehicle) == com.sva.airdefense.XpdrMath.EMERGENCY && com.sva.airdefense.XpdrMath.replies(mode) ? RED
                : com.sva.airdefense.XpdrMath.replies(mode) ? GREEN : MUTED;
        TextFitSC.draw(fontRendererObj, st, stateX, ty, stateW, c, false, 0, 0);
        String dash = "-";
        fontRendererObj.drawString(dash, left + w - 6 - KEY_W + (KEY_W - fontRendererObj.getStringWidth(dash)) / 2, ty, MUTED);
    }

    private int nameWidth() {
        return Math.max(70, (int) ((w - 12 - KEY_W - ACT_W - 12) * 0.5));
    }

    /** The status strip: charge / heat / hull bars, the gases, tuning and the trailer. */
    private void drawStrip(int y) {
        EntityVehicleSVA v = vehicle;
        int third = (w - 12) / 3;
        bar(left + 6, y, third - 6, Lang.tr(v.onHydrogen() ? "sva.hud.bat" : "sva.vmenu.charge"), v.getEnergy() / Math.max(1.0, v.getMaxEnergy()),
                v.onHydrogen() ? 0xFF62E06A : 0xFFE05454);
        bar(left + 6 + third, y, third - 6, Lang.tr("sva.hud.heat"), v.getHeat() / 100.0, v.isHeatStopped() ? 0xFFFF4040 : 0xFFFFA040);
        if (v.wearOn()) {
            bar(left + 6 + third * 2, y, third - 6, Lang.tr("sva.hud.hull"), v.getHp() / Math.max(1.0, v.getMaxHp()), 0xFF3CCB6E);
        }
        int gx = left + 6, gy = y + 11;
        List<Gas> gases = v.tankList();
        if (gases.isEmpty()) {
            fontRendererObj.drawString(Lang.tr("sva.vmenu.noGas"), gx, gy, MUTED);
        }
        for (Gas g : gases) {
            int cap = Math.max(1, v.getGasCapacity(g));
            int pct = (int) Math.floor(v.getGas(g) * 100.0 / cap);
            String t = Lang.tr("sva.gas." + g.key()) + " " + pct + "%";
            int tw = fontRendererObj.getStringWidth(t);
            if (gx + tw > left + w - 6) {
                break;
            }
            drawRect(gx, gy + 1, gx + 5, gy + 6, 0xFF000000 | g.color);
            fontRendererObj.drawString(t, gx + 7, gy, pct <= 0 ? RED : pct < 20 ? AMBER : TEXT);
            gx += tw + 14;
        }
        StringBuilder tb = new StringBuilder();
        if (v.tuningOn()) {
            for (int k : v.getTuning()) {
                if (TuningMath.valid(k)) {
                    tb.append(tb.length() > 0 ? ", " : "").append(Lang.tr("sva.tuning.short." + TuningMath.KEYS[k]));
                }
            }
        }
        String tune = Lang.tr("sva.vmenu.tuning", tb.length() > 0 ? tb.toString() : Lang.tr("sva.vmenu.none"));
        int regenPct = v.getRegenPct();                             // wave 4 (★15): recovery on the server, and now
        if (regenPct > 0) {
            double r = v.getRegenNow();
            tune += "  " + (r > 0 ? Lang.tr("sva.vmenu.regenNow", com.sva.vehicle.RegenMath.fmt(r)) : Lang.tr("sva.vmenu.regen", regenPct + "%"));
        }
        String trailer = HudVehicleSVA.trailerLine(v);
        int half = (w - 12) / 2;
        TextFitSC.draw(fontRendererObj, tune, left + 6, gy + 11, trailer == null ? w - 12 : half - 4, MUTED, false, 0, 0);
        if (trailer != null) {
            TextFitSC.draw(fontRendererObj, trailer, left + 6 + half, gy + 11, half, CYAN, false, 0, 0);
        }
    }

    private void bar(int x, int y, int bw, String label, double frac, int color) {
        int lw = Math.min(46, fontRendererObj.getStringWidth(label) + 3);
        TextFitSC.draw(fontRendererObj, label, x, y, lw, MUTED, false, 0, 0);
        String pct = (int) Math.round(Math.max(0, Math.min(1, frac)) * 100) + "%";
        int pw = fontRendererObj.getStringWidth("100%") + 2;
        int tx = x + lw, tw = Math.max(10, bw - lw - pw);
        drawRect(tx, y + 1, tx + tw, y + 7, TRACK);
        int fill = (int) Math.round(tw * Math.max(0, Math.min(1, frac)));
        if (fill > 0) {
            drawRect(tx, y + 1, tx + fill, y + 7, color);
        }
        fontRendererObj.drawString(pct, tx + tw + 2, y, TEXT);
    }

    private List<String> tip(VehicleFn f) {
        List<String> l = new ArrayList<String>();
        l.add("§e" + Lang.tr(f.nameKey(type)));
        if (type == null && f == VehicleFn.MODE) {
            l.add(Lang.tr("sva.fn.mode.drill.tip"));
            l.add(Lang.tr("sva.fn.mode.tilt.tip"));
            l.add(Lang.tr("sva.fn.mode.long.tip"));
        } else {
            l.add(Lang.tr(f.tipKey(type)));
        }
        if (f.hold) {
            l.add("§7" + Lang.tr("sva.vmenu.tip.hold"));
        }
        if (vehicle != null && f.driverOnly && !driver) {
            l.add("§c" + Lang.tr("sva.vmenu.driverOnly"));
        }
        return l;
    }

    /** D2: a switch button's tip - its name and state, what it does, the battery (АКБ / ГЕН), «только водитель». */
    private List<String> switchTip(int i) {
        List<String> l = new ArrayList<String>();
        String k = "sva.sys.sw." + SystemsMath.SW_KEYS[i];
        l.add("\u00a7e" + Lang.tr(k) + ": " + Lang.tr(vehicle.switchOn(i) ? "sva.vmenu.st.on" : "sva.vmenu.st.off"));
        l.add(Lang.tr(k + ".tip"));
        if (i == SystemsMath.SW_BAT || i == SystemsMath.SW_GEN) {
            l.add("\u00a77" + Lang.tr("sva.sys.battery", Integer.valueOf(vehicle.batteryPct())));
        }
        if (!driver) {
            l.add("\u00a7c" + Lang.tr("sva.vmenu.driverOnly"));
        }
        return l;
    }

    private List<String> keyTip(VehicleFn f) {
        List<String> l = new ArrayList<String>();
        l.add("§e" + Lang.tr("sva.vmenu.key.current", VehicleKeyBindsSVA.describe(VehicleKeyBindsSVA.get(f))));
        l.add(Lang.tr("sva.vmenu.key.tip"));
        l.add("§7" + Lang.tr("sva.vmenu.key.tip2", VehicleKeyBindsSVA.describe(new int[] {f.defaultKey, 0})));
        List<VehicleFn> clash = VehicleKeyBindsSVA.clashes(f, type);
        if (!clash.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (VehicleFn o : clash) {
                b.append(b.length() > 0 ? ", " : "").append(Lang.tr(o.nameKey(type)));
            }
            l.add("§c" + Lang.tr("sva.vmenu.key.clash", b.toString()));
        }
        if (VehicleKeyBindsSVA.clashesMenu(f)) {
            l.add("§c" + Lang.tr("sva.vmenu.key.clashMenu"));
        }
        List<String> shared = VehicleKeyBindsSVA.sharedWith(f);
        if (!shared.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (String n : shared) {
                b.append(b.length() > 0 ? ", " : "").append(n);
            }
            l.add("§7" + Lang.tr("sva.vmenu.key.shared", b.toString()));
        }
        return l;
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id >= B_SW) {
            if (vehicle != null && driver) {
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(vehicle.getEntityId(), EntityVehicleSVA.A_SWITCH + b.id - B_SW));
            }
            refresh();
            return;
        }
        if (b.id == B_BAT) {
            if (vehicle != null) {
                NetSVA.CHANNEL.sendToServer(new NetSVA.Action(vehicle.getEntityId(), EntityVehicleSVA.A_BATTERY_OUT));
            }
            return;
        }
        if (b.id == B_XPDR) {
            if (vehicle != null && driver) {
                mc.displayGuiScreen(new GuiTransponderSVA(vehicle));
            }
            return;
        }
        if (b.id == B_RESET_ALL) {
            for (VehicleFn f : VehicleFn.values()) {
                VehicleKeyBindsSVA.reset(f);
            }
            capturing = null;
        } else if (isKey(b.id)) {
            VehicleFn f = VehicleFn.values()[b.id - B_KEY];
            capturing = capturing == f ? null : f;
        } else if (b.id >= B_ACT) {
            VehicleFn f = VehicleFn.values()[b.id - B_ACT];
            capturing = null;
            if (f == VehicleFn.MAP) {
                GuiMapSVA.openAtPlayer(mc);
                return;
            }
            if (f == VehicleFn.AP_PANEL) {
                mc.displayGuiScreen(new GuiAutopilotSVA());
                return;
            }
            if (f == VehicleFn.CARGO) {
                mc.displayGuiScreen(null);                       // the server opens the cargo screen
            }
            VehicleKeyBindsSVA.fire(mc, f, vehicle);
        }
        refresh();
    }

    /** Setting a key: Esc cancels, Backspace / Delete unbinds, a modifier alone waits for the real key. */
    @Override
    protected void keyTyped(char c, int key) {
        if (capturing == null) {
            int menu = VehicleKeyBindsSVA.KEY_MENU.getKeyCode();
            if (key == Keyboard.KEY_ESCAPE || key != 0 && key == menu || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
                mc.displayGuiScreen(null);
            }
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            capturing = null;
        } else if (key == Keyboard.KEY_BACK || key == Keyboard.KEY_DELETE) {
            VehicleKeyBindsSVA.clear(capturing);
            capturing = null;
        } else if (key > 0 && !VehicleKeyBindsSVA.isModifierKey(key)) {
            VehicleKeyBindsSVA.set(capturing, key, VehicleKeyBindsSVA.modifiersDown());
            capturing = null;
        }
        refresh();
    }

    /** Waiting for a key: a middle / side mouse button binds; right-click on a key button - its default. */
    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (capturing != null && button >= 2) {
            VehicleKeyBindsSVA.set(capturing, KeyBindMath.MOUSE_BASE + button, VehicleKeyBindsSVA.modifiersDown());
            capturing = null;
            refresh();
            return;
        }
        if (button == 1) {
            for (Object o : buttonList) {
                GuiButton b = (GuiButton) o;
                if (isKey(b.id) && b.visible && x >= b.xPosition && y >= b.yPosition && x < b.xPosition + b.width && y < b.yPosition + b.height) {
                    VehicleKeyBindsSVA.reset(VehicleFn.values()[b.id - B_KEY]);
                    capturing = null;
                    mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
                    refresh();
                    return;
                }
            }
        }
        if (button == 0 && capturing != null) {
            boolean onKey = false;
            for (Object o : buttonList) {
                GuiButton b = (GuiButton) o;
                onKey |= isKey(b.id) && x >= b.xPosition && y >= b.yPosition && x < b.xPosition + b.width && y < b.yPosition + b.height;
            }
            if (!onKey) {
                capturing = null;                               // a click elsewhere: cancelled
                refresh();
                return;
            }
        }
        super.mouseClicked(x, y, button);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
