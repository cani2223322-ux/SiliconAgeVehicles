package com.sva.vehicle;

import com.sva.entity.EntityVehicleSVA;

/**
 * The vehicle functions that have a key (the «Техника» menu, GuiVehicleSVA; the keys: client.VehicleKeyBindsSVA) -
 * pure, so the dedicated server's self-test checks the table.
 * <p>
 * Fix 2026-10 (the user's screenshot of Options > Controls): the eleven addon KeyBindings had long names that ran off
 * the left edge of the 1.7.10 controls list, and ten of their letters were red - taken by other mods. In 1.7.10
 * KeyBinding.hash maps a key code to ONE binding, so of two bindings on a letter only one ever gets the press: the
 * vehicle's function could silently not work. Now only «Техника (меню)» (key.sva.menu, CONTROLS) is a KeyBinding; the
 * functions' keys live in the player's own file, are set in the menu (any key with Ctrl / Shift / Alt, or a middle /
 * side mouse button) and are read raw from the keyboard - only while riding a vehicle with no screen open, so another
 * mod's binding on the same letter no longer matters and nothing is taken from anyone on foot.
 * <p>
 * Key codes are LWJGL's (org.lwjgl.input.Keyboard - written as numbers: this class is loaded on the dedicated server,
 * which has no LWJGL). The old KeyBinding ids (oldId: options.txt "key_key.sva." + oldId) are kept for the one-time
 * migration of the player's own letters.
 */
public enum VehicleFn {

    /** «Форсаж»: the hoverbike's boost, the jet's / tiltrotor's / shuttle's / ship's afterburner. Held. F. */
    BOOST("boost", "boost", 33, true, true),
    /** «Снижение»: aircraft down / nose down (the sprint key works too). Held. X. */
    DOWN("down", "down", 45, true, true),
    /** «Бур»: the drill vehicle's drill on / off. B. */
    DRILL("drill", "drill", 48, false, true),
    /** «Режим»: the drill's mode; the tiltrotor's vertical / airplane; the shuttle's / ship's long-range mode. N. */
    MODE("mode", "drillMode", 49, false, true),
    /** The Singular Ship: «Прыжок» V, «Цель прыжка» Y, «Якорь» Z, «Невидимость» C. */
    JUMP("jump", "jump", 47, false, true),
    TARGET("target", "jumpTarget", 21, false, true),
    ANCHOR("anchor", "anchor", 44, false, true),
    CLOAK("cloak", "cloak", 46, false, true),
    /** «Круиз / автопилот» (the driver's client state, CruiseClientSVA). O. */
    CRUISE("cruise", "cruise", 24, false, true),
    /** «Багажник»: the cargo / bunker screen from any seat (owner / the «Багажник» right - the server checks). L. */
    CARGO("cargo", "cargo", 38, false, false),
    /** «Карта техники» (GuiMapSVA). M. */
    MAP("map", "map", 50, false, false),
    /**
     * Aircraft stage A (HighLiftMath; the light plane, the jet, the tiltrotor's flaperons): «Закрылки −» [ retracts a
     * step, «Закрылки +» ] extends one (refused over the next setting's speed), «Воздушный тормоз» U - the spoilers / the
     * jet's dorsal brake (not the tiltrotor). [ ] U: free in vanilla, the core (K R P H J G) and the old letters.
     */
    FLAPS_UP("flapsUp", "flapsUp", 26, false, true),
    FLAPS_DOWN("flapsDown", "flapsDown", 27, false, true),
    AIRBRAKE("airbrake", "airbrake", 22, false, true),
    /**
     * Aircraft stage C (★17): «Панель автопилота» - the autopilot's modes and knobs (client.GuiAutopilotSVA; a screen,
     * like the map: no packet - the modes ride in the Input packet). I: free in vanilla, the core and the old letters.
     * A passenger may open it too (read-only).
     */
    AP_PANEL("apPanel", "apPanel", 23, false, false),
    /**
     * Aircraft round 2, D2 (★6, SystemsMath): «Запуск двигателя» - the engine off: start (АКБ on: the starter, the catch,
     * the warm-up); turning: stop. The light plane, the jet, the tiltrotor. ; (Ж on a Russian keyboard): the last key
     * free in vanilla, the core and the old letters.
     */
    ENGINE("engine", "engine", 39, false, true),
    /**
     * Cockpit wave (plan-next §5 / §6): «Страница MFD» - the next page of the C+ cockpits' MFD (the light plane: ДВИГ / КАРТА /
     * МАРШ / СПО, the helicopter: ДВИГ / КАРТА / ГРУЗ / СПО). The cockpits have no mouse picking, so the page keys under the
     * screen are this key or the «Техника» menu's row; the page is the client's own (no packet), remembered per vehicle
     * (client.MfdPagesSVA). Any rider may flip his own MFD. ' (Э on a Russian keyboard): free in vanilla, the core and the
     * old letters.
     */
    MFD_PAGE("mfdPage", "mfdPage", 40, false, false);

    /** The KeyBindings the addon registers in Options > Controls: only the menu key (the self-test holds this list). */
    public static final String[] CONTROLS = {"key.sva.menu"};
    /** The menu key's default: ` / Ё (LWJGL KEY_GRAVE) - free in vanilla 1.7.10, the core (K R P H J G) and the old letters. */
    public static final int MENU_DEFAULT = 41;
    /** The options.txt line prefix of the old KeyBindings ("key_" + the binding's id). */
    public static final String OPTIONS_PREFIX = "key_key.sva.";

    /** The config / lang id ("sva.fn." + key); the old KeyBinding id; the default key; held (not pressed); only the driver. */
    public final String key, oldId;
    public final int defaultKey;
    public final boolean hold, driverOnly;

    VehicleFn(String key, String oldId, int defaultKey, boolean hold, boolean driverOnly) {
        this.key = key;
        this.oldId = oldId;
        this.defaultKey = defaultKey;
        this.hold = hold;
        this.driverOnly = driverOnly;
    }

    /** Does this vehicle type have the function (the same rules the keys always had)? */
    public boolean appliesTo(VehicleType t) {
        switch (this) {
            case BOOST:
                return t.movement == Movement.HOVER || t.gases.boostGas != null;
            case DOWN:
                return t.movement == Movement.HELI || t.movement == Movement.PLANE || t.movement == Movement.TILT;
            case DRILL:
                return t.hasDrill;
            case MODE:
                return t.hasDrill || t.movement == Movement.TILT || t.longRange;
            case JUMP:
            case TARGET:
            case ANCHOR:
            case CLOAK:
                return t.singular;
            case CRUISE:
                return CruiseMath.kind(t.movement, false) != CruiseMath.NONE || CruiseMath.kind(t.movement, true) != CruiseMath.NONE;
            case FLAPS_UP:
            case FLAPS_DOWN:
                return HighLiftMath.spec(t) != null;
            case AIRBRAKE:
                return HighLiftMath.spec(t) != null && HighLiftMath.spec(t).airbrake;
            case AP_PANEL:
                return CruiseMath.kind(t.movement, true) == CruiseMath.AUTOPILOT;
            case ENGINE:
                return SystemsMath.applies(t);
            case MFD_PAGE:
                return GlassMath.hasMfd(t);
            default:
                return true;                                        // cargo, map: every vehicle
        }
    }

    /**
     * The Action packet a press sends on this type (EntityVehicleSVA.A_*), or -1: held functions go in the Input
     * packet, cruise is the driver's client state, the map and the autopilot panel are screens, the MFD page the client's own.
     */
    public int action(VehicleType t) {
        if (!appliesTo(t)) {
            return -1;
        }
        switch (this) {
            case DRILL:
                return EntityVehicleSVA.A_DRILL_TOGGLE;
            case MODE:
                return t.hasDrill ? EntityVehicleSVA.A_DRILL_MODE : t.movement == Movement.TILT ? EntityVehicleSVA.A_FLIGHT_MODE
                        : EntityVehicleSVA.A_LONG_RANGE;
            case JUMP:
                return EntityVehicleSVA.A_JUMP;
            case TARGET:
                return EntityVehicleSVA.A_JUMP_TARGET;
            case ANCHOR:
                return EntityVehicleSVA.A_ANCHOR;
            case CLOAK:
                return EntityVehicleSVA.A_INVIS;
            case CARGO:
                return EntityVehicleSVA.A_CARGO;
            case FLAPS_UP:
                return EntityVehicleSVA.A_FLAPS_UP;
            case FLAPS_DOWN:
                return EntityVehicleSVA.A_FLAPS_DOWN;
            case AIRBRAKE:
                return EntityVehicleSVA.A_AIRBRAKE;
            case ENGINE:
                return EntityVehicleSVA.A_ENGINE;
            default:
                return -1;
        }
    }

    /** The menu's name of the function on this type (lang key): «Режим» says what it switches. */
    public String nameKey(VehicleType t) {
        if (this == MODE && t != null) {
            return t.hasDrill ? "sva.fn.mode.drill" : t.movement == Movement.TILT ? "sva.fn.mode.tilt" : t.longRange ? "sva.fn.mode.long" : "sva.fn.mode";
        }
        if ((this == FLAPS_UP || this == FLAPS_DOWN) && t == VehicleType.TILT) {
            return "sva.fn." + key + ".tilt";                    // «Флапероны −» / «+»
        }
        return "sva.fn." + key;
    }

    /** The hover explanation (lang key), per type for «Режим». */
    public String tipKey(VehicleType t) {
        return nameKey(t) + ".tip";
    }

    public static VehicleFn byKey(String k) {
        for (VehicleFn f : values()) {
            if (f.key.equals(k)) {
                return f;
            }
        }
        return null;
    }
}
