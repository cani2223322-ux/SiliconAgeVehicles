package com.sva.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.net.NetSVA;
import com.sva.vehicle.KeyBindMath;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.config.Configuration;

/**
 * The vehicle function keys (VehicleFn), set in the «Техника» menu (GuiVehicleSVA) and kept in the player's own file
 * config/SiliconAgeVehicles_keys.cfg - like the core's ArmorKeyBindsSC: any key with Ctrl / Shift / Alt, or a middle /
 * side mouse button.
 * <p>
 * They are read raw (Keyboard.isKeyDown / Mouse.isButtonDown) and ONLY while the player rides a vehicle with no screen
 * open: KeyBinding.hash's one-binding-a-key no longer loses the press to another mod, and on foot nothing is taken from
 * anyone. Presses fire on the rising edge (KeyBindMath.Edge); «Форсаж» / «Снижение» use the held state. The only
 * KeyBinding is the menu key (KEY_MENU, rebindable in Options > Controls) - read raw too, for the same reason. The menu
 * and «Карта» open from the key EVENT (Taps), on foot too.
 * <p>
 * Migration: when the file does not exist yet, the player's own letters of the old KeyBindings are taken from
 * options.txt (KeyBindMath.parseOptions) - a key moved there stays where it was.
 */
public final class VehicleKeyBindsSVA {

    /** «Техника (меню)» - the one addon key in Options > Controls (VehicleFn.CONTROLS). */
    public static final KeyBinding KEY_MENU = new KeyBinding(VehicleFn.CONTROLS[0], VehicleFn.MENU_DEFAULT, "key.categories.sva");

    private static final Map<VehicleFn, int[]> BINDS = new EnumMap<VehicleFn, int[]>(VehicleFn.class);
    private static final Map<VehicleFn, KeyBindMath.Edge> EDGES = new EnumMap<VehicleFn, KeyBindMath.Edge>(VehicleFn.class);
    private static Configuration config;
    /** The held functions' state this tick (boost / down), read by ClientProxySVA.driverInput. */
    private static boolean boostHeld, downHeld;

    private VehicleKeyBindsSVA() {
    }

    /** Client init: the file (or, the first time, options.txt's old letters). */
    public static void load(File configDir, File mcDir) {
        File file = new File(configDir, "SiliconAgeVehicles_keys.cfg");
        boolean first = !file.exists();
        java.util.Map<String, Integer> old = first ? KeyBindMath.parseOptions(readLines(new File(mcDir, "options.txt"))) : null;
        config = new Configuration(file);
        config.load();
        for (VehicleFn f : VehicleFn.values()) {
            EDGES.put(f, new KeyBindMath.Edge());
            String def = KeyBindMath.format(new int[] {f.defaultKey, 0});
            if (first) {
                config.get("keys", f.key, def, comment()).set(KeyBindMath.format(KeyBindMath.migrated(f, old)));
            }
            int[] b = KeyBindMath.parse(config.get("keys", f.key, def, comment()).getString());
            if (b != null) {
                BINDS.put(f, b);
            }
        }
        if (first || config.hasChanged()) {
            config.save();
        }
    }

    private static String comment() {
        return "key:modifiers (LWJGL key code; modifiers 1 Ctrl, 2 Shift, 4 Alt; mouse buttons -100 + button; empty = none)."
                + " Set in the vehicle menu (the 'Vehicle menu' key, default `).";
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<String>();
        if (!f.isFile()) {
            return out;
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    out.add(line);
                }
            } finally {
                r.close();
            }
        } catch (java.io.IOException e) {
            // no old letters then: the defaults
        }
        return out;
    }

    public static int[] get(VehicleFn f) {
        return BINDS.get(f);
    }

    public static void set(VehicleFn f, int key, int mods) {
        int[] b = KeyBindMath.parse(key + ":" + mods);
        if (b == null) {
            clear(f);
            return;
        }
        BINDS.put(f, b);
        save(f, KeyBindMath.format(b));
    }

    public static void clear(VehicleFn f) {
        BINDS.remove(f);
        save(f, "");
    }

    public static void reset(VehicleFn f) {
        set(f, f.defaultKey, 0);
    }

    private static void save(VehicleFn f, String v) {
        if (config != null) {
            config.get("keys", f.key, "").set(v);
            config.save();
        }
    }

    /** Ctrl / Shift / Alt held now. */
    public static int modifiersDown() {
        int m = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
            m |= KeyBindMath.CTRL;
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
            m |= KeyBindMath.SHIFT;
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
            m |= KeyBindMath.ALT;
        }
        return m;
    }

    public static boolean isModifierKey(int key) {
        return key == Keyboard.KEY_LCONTROL || key == Keyboard.KEY_RCONTROL || key == Keyboard.KEY_LSHIFT
                || key == Keyboard.KEY_RSHIFT || key == Keyboard.KEY_LMENU || key == Keyboard.KEY_RMENU;
    }

    /**
     * Keys / mouse buttons that went down since the last client tick (InputEvent, from vanilla's own event queue): a
     * tap shorter than a tick (50 ms) is over before tick() polls Keyboard.isKeyDown - the latch still counts it.
     */
    private static final java.util.Set<Integer> TAPPED = new java.util.HashSet<Integer>();
    private static final org.apache.logging.log4j.Logger KEYLOG = org.apache.logging.log4j.LogManager.getLogger("SiliconAgeVehicles");
    /**
     * Diagnostic lines written so far this session (riding presses are logged only up to a limit). Only with config
     * vehicles.debugKeys (off by default) - for a «the menu does not open» report.
     */
    private static int LOGGED;

    /** FML bus: every key / mouse button press while no screen is open (vanilla posts these only then). */
    public static final class Taps {
        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onKey(cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent e) {
            int k = Keyboard.getEventKey();
            if (!Keyboard.getEventKeyState() || k == 0) {
                return;
            }
            TAPPED.add(Integer.valueOf(k));
            Minecraft mc = Minecraft.getMinecraft();
            int menu = KEY_MENU.getKeyCode();
            boolean riding = mc.thePlayer != null && HudVehicleSVA.vehicleOf(mc.thePlayer) != null;
            if (com.sva.ConfigSVA.debugKeys && (k == menu || riding && LOGGED < 40)) {
                // diagnostics for "the menu does not open": the code this keyboard sends, the menu key, the state
                LOGGED++;
                KEYLOG.info("[SVA-KEYS] key " + k + " '" + Keyboard.getEventCharacter() + "' menuKey " + menu + " riding "
                        + riding + " screen " + (mc.currentScreen == null ? "-" : mc.currentScreen.getClass().getName()));
            }
            if (k == menu) {
                openMenu(mc);
            } else {
                openMap(mc, k);
            }
        }

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onMouse(cpw.mods.fml.common.gameevent.InputEvent.MouseInputEvent e) {
            if (Mouse.getEventButtonState() && Mouse.getEventButton() >= 0) {
                int code = KeyBindMath.MOUSE_BASE + Mouse.getEventButton();
                TAPPED.add(Integer.valueOf(code));
                if (code == KEY_MENU.getKeyCode()) {                 // the menu on a mouse button (vanilla: -100 + button)
                    openMenu(Minecraft.getMinecraft());
                } else {
                    openMap(Minecraft.getMinecraft(), code);
                }
            }
        }
    }

    /**
     * The menu, opened from the key / button EVENT: vanilla posts InputEvent only while no screen is open and after the
     * event is used up, so the press that opens the menu never reaches it (the menu closes on its own key).
     */
    private static void openMenu(Minecraft mc) {
        if (mc.thePlayer != null && mc.currentScreen == null) {
            mc.displayGuiScreen(new GuiVehicleSVA());
            if (com.sva.ConfigSVA.debugKeys) {
                KEYLOG.info("[SVA-KEYS] menu opened");
            }
        }
    }

    /**
     * «Карта» from the key / button EVENT, riding or on foot (like the menu: the opening press never reaches the map,
     * which closes on its own key). With a modifier bound, it must be held; a fuller bind on the same key wins.
     */
    private static void openMap(Minecraft mc, int code) {
        int[] b = BINDS.get(VehicleFn.MAP);
        if (b == null || b[0] != code || mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }
        if (KeyBindMath.owns(b, true, modifiersDown(), new ArrayList<int[]>(BINDS.values()))) {
            GuiMapSVA.openAtPlayer(mc);
        }
    }

    /** Down now, or tapped since the last tick. */
    private static boolean downOrTapped(int k) {
        return k != 0 && (keyDown(k) || TAPPED.contains(Integer.valueOf(k)));
    }

    private static boolean keyDown(int k) {
        if (k == 0) {
            return false;
        }
        if (k < 0) {
            int btn = k - KeyBindMath.MOUSE_BASE;
            return btn < Mouse.getButtonCount() && Mouse.isButtonDown(btn);
        }
        return Keyboard.isKeyDown(k);
    }

    /** "Ctrl+N", "Mouse 4", or a dash for none. */
    public static String describe(int[] b) {
        if (b == null || b[0] == 0) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        if ((b[1] & KeyBindMath.CTRL) != 0) {
            sb.append("Ctrl+");
        }
        if ((b[1] & KeyBindMath.SHIFT) != 0) {
            sb.append("Shift+");
        }
        if ((b[1] & KeyBindMath.ALT) != 0) {
            sb.append("Alt+");
        }
        String k = b[0] < 0 ? Lang.tr("sva.keys.mouse", Integer.valueOf(b[0] - KeyBindMath.MOUSE_BASE + 1)) : Keyboard.getKeyName(b[0]);
        sb.append(k == null ? "?" : k);
        return sb.toString();
    }

    /** The function's current key as shown in hints («Клавиши: L - багажник») and the HUD. */
    public static String name(VehicleFn f) {
        return describe(BINDS.get(f));
    }

    /** The menu key's name ("-" when unbound). */
    public static String menuKeyName() {
        int c = KEY_MENU.getKeyCode();
        return c == 0 ? "-" : net.minecraft.client.settings.GameSettings.getKeyDisplayString(c);
    }

    /** The other functions with exactly this function's key and modifiers (both would fire): a real clash, red in the menu. */
    public static List<VehicleFn> clashes(VehicleFn f, VehicleType t) {
        List<VehicleFn> out = new ArrayList<VehicleFn>();
        int[] b = BINDS.get(f);
        for (VehicleFn o : VehicleFn.values()) {
            if (o != f && KeyBindMath.same(b, BINDS.get(o)) && (t == null || o.appliesTo(t))) {
                out.add(o);
            }
        }
        return out;
    }

    /**
     * The function sits on the menu key with no modifiers: in a vehicle one press would both open the «Техника» menu and
     * fire the function - a clash, red in the menu («совпадает с клавишей меню»).
     */
    public static boolean clashesMenu(VehicleFn f) {
        int[] b = BINDS.get(f);
        int menu = KEY_MENU.getKeyCode();
        return b != null && menu != 0 && b[0] == menu && b[1] == 0;
    }

    /** KeyBindings (vanilla / other mods) on the same key: they fire too, but no longer take the press away (info only). */
    public static List<String> sharedWith(VehicleFn f) {
        List<String> out = new ArrayList<String>();
        int[] b = BINDS.get(f);
        Minecraft mc = Minecraft.getMinecraft();
        if (b == null || mc.gameSettings == null || mc.gameSettings.keyBindings == null) {
            return out;
        }
        for (KeyBinding kb : mc.gameSettings.keyBindings) {
            if (kb != null && kb != KEY_MENU && kb.getKeyCode() == b[0]) {
                out.add(net.minecraft.client.resources.I18n.format(kb.getKeyDescription()));
            }
        }
        return out;
    }

    public static boolean boostHeld() {
        return boostHeld;
    }

    public static boolean downHeld() {
        return downHeld;
    }

    /**
     * Client tick (start): the menu key (any time with no screen open), then - riding, no screen - the functions:
     * presses fire on the edge, holds are latched for driverInput. Otherwise every edge just remembers the state.
     */
    public static void tick(Minecraft mc) {
        // no Display.isActive() here: some window / fullscreen mods keep it false while the game has focus
        boolean free = mc.thePlayer != null && mc.currentScreen == null;
        while (KEY_MENU.isPressed()) {
            // drained: the menu opens from the key EVENT (Taps), never from a poll here - a poll at the tick's start
            // saw the key down, opened the menu, and then the same press was delivered to the open menu, which closed it
        }
        EntityVehicleSVA v = free ? HudVehicleSVA.vehicleOf(mc.thePlayer) : null;
        int held = free ? modifiersDown() : 0;
        List<int[]> all = new ArrayList<int[]>(BINDS.values());
        boolean boost = false, down = false;
        for (VehicleFn f : VehicleFn.values()) {
            int[] b = BINDS.get(f);
            boolean kd = b != null && downOrTapped(b[0]);
            KeyBindMath.Edge e = EDGES.get(f);
            if (v == null || v.isDead || f == VehicleFn.MAP) {
                // «Карта» opens from the key event (Taps.openMap), riding or not
                e.hold(kd);
                continue;
            }
            boolean on = KeyBindMath.owns(b, kd, held, all);
            if (f.hold) {
                e.hold(on);
                if (f == VehicleFn.BOOST) {
                    boost = on;
                } else {
                    down = on;
                }
            } else if (e.fire(on)) {
                fire(mc, f, v);
            }
        }
        boostHeld = boost;
        downHeld = down;
        TAPPED.clear();
    }

    /**
     * A function used (its key, or the menu's button): the same packets the old keys sent. Silent on a type without
     * the function (a key shared with another mod); a passenger is told when a function is the driver's.
     */
    public static void fire(Minecraft mc, VehicleFn f, EntityVehicleSVA v) {
        if (f == VehicleFn.MAP) {
            GuiMapSVA.openAtPlayer(mc);
            return;
        }
        if (v == null || v.isDead || f.hold) {
            return;
        }
        VehicleType t = v.getVehicleType();
        if (!f.appliesTo(t)) {
            return;
        }
        boolean driver = mc.thePlayer.ridingEntity == v;
        if (f.driverOnly && !driver) {
            mc.ingameGUI.func_110326_a(Lang.tr("sva.fn.driverOnly", Lang.tr(f.nameKey(t))), false);
            return;
        }
        if (f == VehicleFn.CRUISE) {
            CruiseClientSVA.toggleFor(v);
            return;
        }
        int a = f.action(t);
        if (a >= 0) {
            NetSVA.CHANNEL.sendToServer(new NetSVA.Action(v.getEntityId(), a));
        }
    }
}
