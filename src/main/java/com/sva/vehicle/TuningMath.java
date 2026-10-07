package com.sva.vehicle;

/**
 * Wave 2, ★8 tuning: the upgrade kinds («Мотор+ I/II», «Аккумулятор+», «Броня», «Лёгкий корпус», «Большие колёса»,
 * «Люстра фар», «Усиленный бур», «Доп. бак»), which vehicle takes which, what conflicts, and the stat multipliers -
 * pure (no world, no Minecraft classes): the entity, the garage, the HUD and the self-test share it.
 * Kind numbers are the item meta and are saved in the vehicle NBT: APPEND-ONLY.
 *
 * Rules (decided for wave 2):
 * - up to MAX_SLOTS upgrades per vehicle, no kind twice;
 * - one engine upgrade (Мотор+ I or II), Броня and Лёгкий корпус exclude each other;
 * - speed upgrades (Мотор+, Лёгкий корпус) only where the vehicle's own top speed is the limit - wheels, tracks, the
 *   hoverbike (speedTuned). Aircraft have stall / lift / runway balance tied to their top speed, so they take none;
 *   Броня fits everything, its -5% speed applies only where speed upgrades would;
 * - Большие колёса: wheeled ground vehicles only (tracks already step 2 blocks, aircraft have no use);
 * - Усиленный бур: the drill vehicle; Доп. бак: flying types with gas tanks (helicopter... shuttle, ship);
 * - Аккумулятор+ and Люстра фар: every vehicle.
 */
public final class TuningMath {

    public static final int MOTOR_I = 0, MOTOR_II = 1, BATTERY = 2, ARMOR = 3, LIGHT_HULL = 4, BIG_WHEELS = 5, LIGHT_BAR = 6,
            DRILL_PLUS = 7, EXTRA_TANK = 8, KINDS = 9;
    /** Item meta -> lang / icon key (APPEND-ONLY with the kinds). */
    public static final String[] KEYS = {"motor1", "motor2", "battery", "armor", "lightHull", "bigWheels", "lightBar", "drillPlus", "extraTank"};
    public static final int MAX_SLOTS = 4;
    /** canInstall results. */
    public static final int OK = 0, NO_FIT = 1, CONFLICT = 2, FULL = 3, BAD = 4;

    public static final double MOTOR_I_SPEED = 1.15, MOTOR_II_SPEED = 1.30, LIGHT_SPEED = 1.10, ARMOR_SPEED = 0.95;
    public static final double BATTERY_MUL = 1.5, ARMOR_DAMAGE = 0.6, LIGHT_HP = 0.8, LIGHT_RANGE = 2.0, DRILL_MUL = 1.5, TANK_MUL = 1.5;
    public static final float BIG_WHEEL_STEP = 1.5F;

    private TuningMath() {
    }

    public static boolean valid(int kind) {
        return kind >= 0 && kind < KINDS;
    }

    /** Top speed upgrades work on this movement (wheels, tracks, the hoverbike). */
    public static boolean speedTuned(Movement m) {
        return m == Movement.GROUND || m == Movement.CRAWLER || m == Movement.HOVER;
    }

    /** May this kind go into this type at all? */
    public static boolean fits(VehicleType t, int kind) {
        if (t == null || !valid(kind)) {
            return false;
        }
        switch (kind) {
            case MOTOR_I:
            case MOTOR_II:
            case LIGHT_HULL:
                return speedTuned(t.movement);
            case BIG_WHEELS:
                return t.movement == Movement.GROUND;
            case DRILL_PLUS:
                return t.hasDrill;
            case EXTRA_TANK:
                return t.movement.flies && t.gases.any();
            default:
                return true;                                // battery, armour, light bar
        }
    }

    /** Two kinds that can't be installed together (the same kind included). */
    public static boolean conflicts(int a, int b) {
        if (a == b) {
            return true;
        }
        return engine(a) && engine(b) || a == ARMOR && b == LIGHT_HULL || a == LIGHT_HULL && b == ARMOR;
    }

    private static boolean engine(int k) {
        return k == MOTOR_I || k == MOTOR_II;
    }

    /** Count of installed upgrades (-1 = empty slot). */
    public static int count(int[] slots) {
        int n = 0;
        if (slots != null) {
            for (int k : slots) {
                if (valid(k)) {
                    n++;
                }
            }
        }
        return n;
    }

    public static boolean has(int[] slots, int kind) {
        if (slots != null) {
            for (int k : slots) {
                if (k == kind) {
                    return true;
                }
            }
        }
        return false;
    }

    /** May `kind` be added to the installed ones (OK / NO_FIT / CONFLICT / FULL / BAD)? */
    public static int canInstall(VehicleType t, int[] slots, int kind) {
        if (!valid(kind)) {
            return BAD;
        }
        if (!fits(t, kind)) {
            return NO_FIT;
        }
        if (slots != null) {
            for (int k : slots) {
                if (valid(k) && conflicts(k, kind)) {
                    return CONFLICT;
                }
            }
        }
        return count(slots) >= MAX_SLOTS ? FULL : OK;
    }

    /** A fresh slot array (all empty). */
    public static int[] empty() {
        return new int[] {-1, -1, -1, -1};
    }

    /**
     * Cleans a saved / received slot array for this type: unknown kinds, kinds that don't fit, conflicts and extras
     * dropped (first one wins), always MAX_SLOTS long.
     */
    public static int[] sanitize(VehicleType t, int[] raw) {
        int[] out = empty();
        if (raw == null) {
            return out;
        }
        for (int i = 0; i < raw.length && i < MAX_SLOTS; i++) {
            int k = raw[i];
            if (valid(k) && canInstall(t, out, k) == OK) {
                out[i] = k;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ data watcher packing: 4 x 5 bits (kind + 1)

    public static int pack(int[] slots) {
        int p = 0;
        for (int i = 0; slots != null && i < Math.min(MAX_SLOTS, slots.length); i++) {
            int k = valid(slots[i]) ? slots[i] + 1 : 0;
            p |= (k & 31) << (i * 5);
        }
        return p;
    }

    public static int[] unpack(int p) {
        int[] out = empty();
        for (int i = 0; i < MAX_SLOTS; i++) {
            int k = (p >> (i * 5) & 31) - 1;
            out[i] = valid(k) ? k : -1;
        }
        return out;
    }

    // ------------------------------------------------------------------ stats (1 = no change)

    /** Top speed x this (only speedTuned movements; others 1). */
    public static double speedMul(VehicleType t, int[] s) {
        if (t == null || !speedTuned(t.movement)) {
            return 1;
        }
        double m = 1;
        if (has(s, MOTOR_I)) {
            m *= MOTOR_I_SPEED;
        }
        if (has(s, MOTOR_II)) {
            m *= MOTOR_II_SPEED;
        }
        if (has(s, LIGHT_HULL)) {
            m *= LIGHT_SPEED;
        }
        if (has(s, ARMOR)) {
            m *= ARMOR_SPEED;
        }
        return m;
    }

    /** EU buffer x this. */
    public static double bufferMul(int[] s) {
        return has(s, BATTERY) ? BATTERY_MUL : 1;
    }

    /** Hull damage taken x this. */
    public static double damageMul(int[] s) {
        return has(s, ARMOR) ? ARMOR_DAMAGE : 1;
    }

    /** Max hull HP x this. */
    public static double hpMul(int[] s) {
        return has(s, LIGHT_HULL) ? LIGHT_HP : 1;
    }

    /** Headlight beam / lit blocks reach x this. */
    public static double lightMul(int[] s) {
        return has(s, LIGHT_BAR) ? LIGHT_RANGE : 1;
    }

    /** Drill dig speed x this. */
    public static double digMul(int[] s) {
        return has(s, DRILL_PLUS) ? DRILL_MUL : 1;
    }

    /** Gas tank capacity x this. */
    public static double gasMul(int[] s) {
        return has(s, EXTRA_TANK) ? TANK_MUL : 1;
    }

    /** Step height: the type's, or BIG_WHEEL_STEP with Большие колёса on a wheeled vehicle. */
    public static float stepHeight(VehicleType t, int[] s) {
        return t.movement == Movement.GROUND && has(s, BIG_WHEELS) ? Math.max(t.stepHeight, BIG_WHEEL_STEP) : t.stepHeight;
    }
}
