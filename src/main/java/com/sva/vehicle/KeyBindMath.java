package com.sva.vehicle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The vehicle function keys (VehicleFn, client.VehicleKeyBindsSVA) - the pure part, checked by the self-test:
 * <ul>
 * <li>a bind is {key, modifiers}: key = an LWJGL key code 1..255, or a mouse button stored like vanilla's (-100 +
 * button, -100..-85), 0 = none; modifiers = CTRL | SHIFT | ALT. Kept in the config as "key:mods" (the core's
 * ArmorKeyBindsSC format), "" = unbound;</li>
 * <li>which bind a key press belongs to: its key down and its modifiers held (more may be held - Ctrl is the vanilla
 * sprint, so Ctrl+F still boosts), and of several binds on that key the one with the most modifiers wins (with
 * N = «Режим» and Ctrl+N = something else, Ctrl+N fires only the latter);</li>
 * <li>presses fire on the rising edge (Edge), held functions read the state;</li>
 * <li>the one-time migration: the player's own letters of the old KeyBindings from options.txt
 * ("key_key.sva.drillMode:49").</li>
 * </ul>
 */
public final class KeyBindMath {

    public static final int CTRL = 1, SHIFT = 2, ALT = 4;
    public static final int MOUSE_BASE = -100, MOUSE_LAST = -85;

    private KeyBindMath() {
    }

    /** A key code Keyboard.isKeyDown / Mouse.isButtonDown accepts (0: none). */
    public static boolean validKey(int k) {
        return k == 0 || k > 0 && k < 256 || k >= MOUSE_BASE && k <= MOUSE_LAST;
    }

    public static boolean isMouse(int k) {
        return k < 0;
    }

    /** "key:mods" for the config; "" for none. */
    public static String format(int[] b) {
        return b == null || b[0] == 0 ? "" : b[0] + ":" + (b[1] & 7);
    }

    /** The config's "key:mods" (or a bare "key") -> {key, mods}; "" / bad / a key no one can press -> null (unbound). */
    public static int[] parse(String v) {
        if (v == null) {
            return null;
        }
        v = v.trim();
        if (v.length() == 0) {
            return null;
        }
        try {
            int c = v.indexOf(':');
            int key = Integer.parseInt((c < 0 ? v : v.substring(0, c)).trim());
            int mods = c < 0 ? 0 : Integer.parseInt(v.substring(c + 1).trim());
            if (key == 0 || !validKey(key) || mods < 0 || mods > 7) {
                return null;                                       // Keyboard.isKeyDown would throw every tick
            }
            return new int[] {key, mods};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Is the bind down: its key down and (at least) its modifiers held? */
    public static boolean matches(int[] b, boolean keyDown, int held) {
        return b != null && b[0] != 0 && keyDown && (held & b[1]) == b[1];
    }

    private static int bits(int m) {
        return (m & 1) + (m >> 1 & 1) + (m >> 2 & 1);
    }

    /**
     * Does bind `mine` own this moment's press: it matches, and no other bind on the same key that also matches has
     * more modifiers (equal ones: both fire - the menu marks such a pair red)?
     */
    public static boolean owns(int[] mine, boolean keyDown, int held, List<int[]> all) {
        if (!matches(mine, keyDown, held)) {
            return false;
        }
        for (int[] o : all) {
            if (o != mine && o != null && o[0] == mine[0] && matches(o, true, held) && bits(o[1]) > bits(mine[1])) {
                return false;
            }
        }
        return true;
    }

    /** Two binds the same key and modifiers (a real clash inside the menu). */
    public static boolean same(int[] a, int[] b) {
        return a != null && b != null && a[0] != 0 && a[0] == b[0] && a[1] == b[1];
    }

    /** Rising-edge detection: fire() is true once a press, on the tick the bind goes down. */
    public static final class Edge {
        private boolean was;

        /** @return true when `down` is a new press */
        public boolean fire(boolean down) {
            boolean f = down && !was;
            was = down;
            return f;
        }

        /** While suppressed (a screen open, not riding): remember the state so a key held then doesn't fire later. */
        public void hold(boolean down) {
            was = down;
        }
    }

    /**
     * The migration: from options.txt's lines, the old binding id -> its key code (only the addon's "key_key.sva."
     * lines with a number). The caller keeps a code only if it differs from the function's default.
     */
    public static Map<String, Integer> parseOptions(List<String> lines) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            if (line == null || !line.startsWith(VehicleFn.OPTIONS_PREFIX)) {
                continue;
            }
            int c = line.indexOf(':');
            if (c <= VehicleFn.OPTIONS_PREFIX.length()) {
                continue;
            }
            try {
                int code = Integer.parseInt(line.substring(c + 1).trim());
                if (validKey(code)) {
                    out.put(line.substring(VehicleFn.OPTIONS_PREFIX.length(), c), Integer.valueOf(code));
                }
            } catch (NumberFormatException e) {
                // a hand-edited line: skip it
            }
        }
        return out;
    }

    /** The first bind of a function after the migration: the player's old code (0 = he had unbound it), else the default. */
    public static int[] migrated(VehicleFn f, Map<String, Integer> old) {
        Integer c = old == null ? null : old.get(f.oldId);
        int key = c == null ? f.defaultKey : c.intValue();
        return key == 0 ? null : new int[] {key, 0};
    }
}
