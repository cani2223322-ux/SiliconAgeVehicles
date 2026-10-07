package com.sva.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.KeyBindMath;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleType;

/**
 * Fix 2026-10, the «Техника» menu and its keys (VehicleFn, KeyBindMath; client.VehicleKeyBindsSVA / GuiVehicleSVA) -
 * pure checks for the dedicated server: the bind format, the defaults per vehicle type, the options.txt migration,
 * edge detection and modifier priority, and that the addon puts only the menu key into Options > Controls.
 */
final class SelfTestKeysSVA {

    /** LWJGL codes the defaults must keep clear of: vanilla movement / inventory / chat / hotbar, the core's K R P H J G. */
    private static final int[] RESERVED = {17, 30, 31, 32, 57, 42, 29, 18, 16, 20, 53, 15, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1,
            37, 19, 25, 35, 36, 34};

    private SelfTestKeysSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws Exception {
        format();
        defaults();
        perType();
        migration();
        edges();
        controls();
    }

    private static boolean eq(int[] a, int[] b) {
        return Arrays.equals(a, b);
    }

    private static void format() {
        boolean round = true;
        int[][] binds = {{33, 0}, {49, KeyBindMath.CTRL}, {21, KeyBindMath.CTRL | KeyBindMath.SHIFT | KeyBindMath.ALT}, {-98, KeyBindMath.ALT},
                {-96, 0}, {-85, KeyBindMath.SHIFT}, {255, 0}};
        for (int[] b : binds) {
            round &= eq(KeyBindMath.parse(KeyBindMath.format(b)), b);
        }
        check(round && "49:1".equals(KeyBindMath.format(new int[] {49, 1})) && "-98:4".equals(KeyBindMath.format(new int[] {-98, 4})),
                "keys: key:mods round trip - letters, Ctrl / Shift / Alt, the middle (-98) and side (-97, -96) mouse buttons");
        check(KeyBindMath.parse("") == null && KeyBindMath.parse(null) == null && KeyBindMath.parse("0:0") == null && KeyBindMath.parse("300:0") == null
                        && KeyBindMath.parse("-50:0") == null && KeyBindMath.parse("-84:0") == null && KeyBindMath.parse("33:9") == null
                        && KeyBindMath.parse("abc") == null && KeyBindMath.parse("33:-1") == null && "".equals(KeyBindMath.format(null))
                        && eq(KeyBindMath.parse(" 33 "), new int[] {33, 0}) && eq(KeyBindMath.parse("33 : 2"), new int[] {33, 2}),
                "keys: empty / none / a code no keyboard or mouse has / bad modifiers -> unbound (never a code isKeyDown throws on)");
    }

    private static void defaults() {
        Set<Integer> seen = new HashSet<Integer>();
        StringBuilder bad = new StringBuilder();
        for (VehicleFn f : VehicleFn.values()) {
            if (!KeyBindMath.validKey(f.defaultKey) || f.defaultKey <= 0 || !seen.add(Integer.valueOf(f.defaultKey))
                    || f.defaultKey == VehicleFn.MENU_DEFAULT) {
                bad.append(' ').append(f.key);
            }
            for (int r : RESERVED) {
                if (f.defaultKey == r) {
                    bad.append(' ').append(f.key).append("=reserved");
                }
            }
        }
        for (int r : RESERVED) {
            if (VehicleFn.MENU_DEFAULT == r) {
                bad.append(" menu=reserved");
            }
        }
        check(bad.length() == 0 && VehicleFn.values().length == 11 && VehicleFn.MENU_DEFAULT == 41,
                "keys: 11 functions, every default a real key, all different, none the menu's ` and none vanilla's / the core's" + bad);
        int[] old = {33, 45, 48, 49, 47, 21, 44, 46, 24, 38, 50};       // F X B N V Y Z C O L M - the old KeyBindings' defaults
        boolean same = true;
        for (VehicleFn f : VehicleFn.values()) {
            same &= f.defaultKey == old[f.ordinal()];
        }
        check(same && VehicleFn.BOOST.hold && VehicleFn.DOWN.hold && !VehicleFn.MODE.hold && !VehicleFn.CARGO.driverOnly
                        && !VehicleFn.MAP.driverOnly && VehicleFn.JUMP.driverOnly && VehicleFn.byKey("mode") == VehicleFn.MODE
                        && "drillMode".equals(VehicleFn.MODE.oldId) && "jumpTarget".equals(VehicleFn.TARGET.oldId),
                "keys: the defaults are the old letters F X B N V Y Z C O L M; boost / down held; cargo / map for passengers too");
    }

    private static void perType() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        StringBuilder bad = new StringBuilder(), miss = new StringBuilder();
        for (VehicleType t : VehicleType.values()) {
            Set<Integer> keys = new HashSet<Integer>();
            for (VehicleFn f : VehicleFn.values()) {
                if (!f.appliesTo(t)) {
                    if (f.action(t) != -1) {
                        bad.append(' ').append(t.key).append('/').append(f.key).append(" acts without the function");
                    }
                    continue;
                }
                if (!keys.add(Integer.valueOf(f.defaultKey))) {
                    bad.append(' ').append(t.key).append('/').append(f.key).append(" default clash");
                }
                boolean packet = !f.hold && f != VehicleFn.CRUISE && f != VehicleFn.MAP;
                if (packet != (f.action(t) >= 0)) {
                    bad.append(' ').append(t.key).append('/').append(f.key).append(" action ").append(f.action(t));
                }
                for (String k : new String[] {f.nameKey(t), f.tipKey(t)}) {
                    if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                        miss.append(' ').append(k);
                    }
                }
            }
            if (!VehicleFn.CARGO.appliesTo(t) || !VehicleFn.MAP.appliesTo(t) || VehicleFn.DRILL.appliesTo(t) != t.hasDrill
                    || VehicleFn.JUMP.appliesTo(t) != t.singular) {
                bad.append(' ').append(t.key).append(" table");
            }
        }
        for (VehicleFn f : VehicleFn.values()) {
            for (String k : new String[] {f.nameKey(null), f.tipKey(null)}) {
                if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                    miss.append(' ').append(k);
                }
            }
        }
        check(bad.length() == 0, "keys: every vehicle type - its functions' defaults differ, each press sends an Action, cargo / map on all" + bad);
        check(miss.length() == 0, "keys: a short name and a hover explanation for every function of every type (and on foot) in ru and en" + miss);
        check(VehicleFn.MODE.action(VehicleType.DRILL) == EntityVehicleSVA.A_DRILL_MODE && VehicleFn.DRILL.action(VehicleType.DRILL) == EntityVehicleSVA.A_DRILL_TOGGLE
                        && VehicleFn.MODE.action(VehicleType.TILT) == EntityVehicleSVA.A_FLIGHT_MODE
                        && VehicleFn.MODE.action(VehicleType.SHUTTLE) == EntityVehicleSVA.A_LONG_RANGE && VehicleFn.MODE.action(VehicleType.SHIP) == EntityVehicleSVA.A_LONG_RANGE
                        && VehicleFn.JUMP.action(VehicleType.SHIP) == EntityVehicleSVA.A_JUMP && VehicleFn.TARGET.action(VehicleType.SHIP) == EntityVehicleSVA.A_JUMP_TARGET
                        && VehicleFn.ANCHOR.action(VehicleType.SHIP) == EntityVehicleSVA.A_ANCHOR && VehicleFn.CLOAK.action(VehicleType.SHIP) == EntityVehicleSVA.A_INVIS
                        && VehicleFn.CARGO.action(VehicleType.BUGGY) == EntityVehicleSVA.A_CARGO && VehicleFn.JUMP.action(VehicleType.SHUTTLE) == -1
                        && VehicleFn.MODE.action(VehicleType.BUGGY) == -1,
                "keys: the menu's buttons and the keys send the old packets (drill 0/1, tiltrotor 5, long range 6, jump 7..10, cargo 11)");
        check(VehicleFn.BOOST.appliesTo(VehicleType.HOVER) && VehicleFn.BOOST.appliesTo(VehicleType.JET) && !VehicleFn.BOOST.appliesTo(VehicleType.BUGGY)
                        && VehicleFn.DOWN.appliesTo(VehicleType.HELI) && VehicleFn.DOWN.appliesTo(VehicleType.SHIP) && !VehicleFn.DOWN.appliesTo(VehicleType.HOVER)
                        && VehicleFn.CRUISE.appliesTo(VehicleType.TRUCK) && VehicleFn.CRUISE.appliesTo(VehicleType.TILT) && !VehicleFn.CRUISE.appliesTo(VehicleType.HELI)
                        && "sva.fn.mode.drill".equals(VehicleFn.MODE.nameKey(VehicleType.DRILL)) && "sva.fn.mode.long.tip".equals(VehicleFn.MODE.tipKey(VehicleType.SHIP)),
                "keys: per type - boost on the hoverbike / jet, down on aircraft, cruise on ground / planes / tiltrotor, the Mode named per type");
    }

    private static void migration() {
        List<String> lines = Arrays.asList("version:1614", "key_key.attack:-100", "key_key.sva.drillMode:49", "key_key.sva.cargo:0",
                "key_key.sva.boost:-98", "key_key.sva.jumpTarget:22", "key_key.sva.map:abc", "key_key.sva.cloak:999", "key_key.sva.:5",
                "lastServer:", "key_key.sc.armor:37");
        Map<String, Integer> m = KeyBindMath.parseOptions(lines);
        check(m.size() == 4 && m.get("drillMode").intValue() == 49 && m.get("cargo").intValue() == 0 && m.get("boost").intValue() == -98
                        && m.get("jumpTarget").intValue() == 22 && !m.containsKey("map") && !m.containsKey("cloak") && KeyBindMath.parseOptions(null).isEmpty(),
                "keys: options.txt migration reads only the addon's key_key.sva.* lines with a pressable code");
        check(eq(KeyBindMath.migrated(VehicleFn.MODE, m), new int[] {49, 0}) && KeyBindMath.migrated(VehicleFn.CARGO, m) == null
                        && eq(KeyBindMath.migrated(VehicleFn.BOOST, m), new int[] {-98, 0}) && eq(KeyBindMath.migrated(VehicleFn.TARGET, m), new int[] {22, 0})
                        && eq(KeyBindMath.migrated(VehicleFn.MAP, m), new int[] {50, 0}) && eq(KeyBindMath.migrated(VehicleFn.JUMP, null), new int[] {47, 0}),
                "keys: migration keeps the player's moved letters (unbound stays unbound, a mouse button stays), the rest get the defaults");
    }

    private static void edges() {
        KeyBindMath.Edge e = new KeyBindMath.Edge();
        boolean[] in = {false, true, true, false, true, true};
        boolean[] want = {false, true, false, false, true, false};
        boolean ok = true;
        for (int i = 0; i < in.length; i++) {
            ok &= e.fire(in[i]) == want[i];
        }
        KeyBindMath.Edge h = new KeyBindMath.Edge();
        h.hold(true);                                                   // held while a screen was open
        boolean held = !h.fire(true) && !h.fire(false) && h.fire(true);
        check(ok && held, "keys: a press fires once on the rising edge; a key held while a screen was open doesn't fire on closing");
        int[] n = {49, 0}, ctrlN = {49, KeyBindMath.CTRL}, f = {33, 0};
        List<int[]> all = new ArrayList<int[]>(Arrays.asList(n, ctrlN, f));
        check(KeyBindMath.owns(ctrlN, true, KeyBindMath.CTRL, all) && !KeyBindMath.owns(n, true, KeyBindMath.CTRL, all)
                        && KeyBindMath.owns(n, true, 0, all) && !KeyBindMath.owns(ctrlN, true, 0, all)
                        && KeyBindMath.owns(f, true, KeyBindMath.CTRL | KeyBindMath.SHIFT, all) && !KeyBindMath.owns(f, false, 0, all)
                        && !KeyBindMath.owns(null, true, 0, all) && KeyBindMath.same(n, new int[] {49, 0}) && !KeyBindMath.same(n, ctrlN),
                "keys: Ctrl+N wins over N with Ctrl held, N alone with none; F still fires with Ctrl (sprint) held");
    }

    private static void controls() throws java.io.IOException {
        String prefix = "key.sva.";
        Set<String> ids = LangKeysSVA.constants(prefix);
        if (ids != null) {
            ids.remove(prefix);                                         // this very constant
        }
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        boolean lang = ru != null && en != null && ru.contains("key.sva.menu") && en.contains("key.sva.menu") && ru.contains("key.categories.sva");
        List<String> dead = new ArrayList<String>();
        if (ru != null) {
            for (String k : ru) {
                if (k.startsWith("key.sva.") && !Arrays.asList(VehicleFn.CONTROLS).contains(k)) {
                    dead.add(k);
                }
            }
        }
        check(VehicleFn.CONTROLS.length == 1 && "key.sva.menu".equals(VehicleFn.CONTROLS[0]) && ids != null
                        && new HashSet<String>(Arrays.asList(VehicleFn.CONTROLS)).containsAll(ids) && lang && dead.isEmpty(),
                "keys: Options > Controls gets only key.sva.menu - no other key.sva.* id in the mod's classes " + ids + " or lang" + (dead.isEmpty() ? "" : " " + dead));
    }
}
