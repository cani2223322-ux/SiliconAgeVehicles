package com.sva.vehicle;

/**
 * Aircraft round 2, stage D2 (mock-ups docs/mockups/veh_air3_2_start_fail.png, veh_air3_4_cockpit.png): the aircraft's
 * board systems - the overhead switches (★17), the engine start (★6) and the failures that come with wear (★7). Pure
 * logic (the self-test SelfTestAirD2SVA runs it on the dedicated server); EntityVehicleSVA keeps the state on the server
 * and syncs it in one data watcher word (pack / unpack), client/OverheadPanelSVA and the HUD draw it.
 * <p>
 * Which vehicles: the light plane, the jet and the tiltrotor (applies: Movement.PLANE / TILT). The helicopter, the
 * spacecraft and the ground vehicles keep their always-ready motor and always-on lights.
 * <p>
 * <b>Switches</b> (SW_*, one bit each; a new or old vehicle has them all ON - nothing changes for a player who never
 * touches them): АКБ (the battery master: off = the whole board dark, the engine stops, no start), ГЕН (the board's
 * converter from the main EU cells - it keeps the battery charged; off = the battery runs the board alone and is flat
 * in BAT_FLIGHT_TICKS of flying, then the instruments and lights go dark - the engine keeps running), НАВ. ОГНИ (the
 * red / green / white nav lights), СТРОБ (the white strobes), МАЯЧОК (the red beacon), ПОС. ФАРЫ (the landing lights:
 * ON = they work as before - out with the flaps / on the approach, beams at night; OFF = in and dark), ОБОГРЕВ (the
 * pitot / windscreen heat: in the cold - over COLD_Y or in falling snow - it costs HEAT_EU_SHARE of the cruise EU;
 * off in the cold the windscreen frosts over and after ICE_TICKS the airspeed indicator freezes - «ПРИБОР»).
 * <p>
 * <b>Engine</b> (OFF -> CRANK -> WARM -> RUN): «ЗАПУСК» with АКБ on, charge in the cells and the battery over
 * BAT_START cranks the starter (the propeller turns slowly; the jet's turbine spools with a rising whine) for
 * crankTicks - 1.5 s (the jet 3 s; a third of it with the airflow already turning it in the air) - then it catches
 * (a puff of smoke, a bang) and warms up for warmTicks (3 s; the jet 2 s) with the power ramping WARM_POWER -> 1, then
 * RUN. A worn engine (over WEAR_MIN) may not catch at the first try: the misfire chance is MISFIRE_K x the failure
 * chance per minute. The engine stops: АКБ off, the main cells empty, «Стоп», or no pilot for IDLE_STOP ticks.
 * Pressing forward (the tiltrotor's Space too) with the engine off and АКБ on starts it by itself (autoStart).
 * <p>
 * <b>Failures</b> (F_*): over WEAR_MIN wear the chance per minute of flight is CHANCE_MAX x ((wear - 0.6) / 0.4)^2
 * (0 at 60 %, 0.5 % at 70, 2 % at 80, 4.5 % at 90, 8 % at 100 - the mock-up's curve); a roll a minute in the air
 * with the engine running, two at most at once: the engine (45 %: power x ENGINE_POWER, smoke from the cowling,
 * «ОТКАЗ ДВИГ.»), a flap jammed (25 %, aircraft with flaps: the setting can't change, «ЗАКР. ЗАКЛИН.»), an instrument
 * (30 %: one dial freezes under a red-striped «ОТКАЗ» flag, «ПРИБОР»); «ВНИМАНИЕ» blinks while any is on. The garage's
 * repair clears them (the station too once the hull is whole again).
 */
public final class SystemsMath {

    // ---------------------------------------------------------------- the overhead switches

    public static final int SW_BAT = 0, SW_GEN = 1, SW_NAV = 2, SW_STROBE = 3, SW_BEACON = 4, SW_LAMP = 5, SW_HEAT = 6, SWITCHES = 7;
    /** Every switch on: the default of a new vehicle and of one saved before D2. */
    public static final int ALL_ON = (1 << SWITCHES) - 1;
    /** Lang key part per switch: sva.sys.sw.KEY (the panel's label, the menu's button), sva.sys.sw.KEY.tip. */
    public static final String[] SW_KEYS = {"bat", "gen", "nav", "strobe", "beacon", "lamp", "heat"};
    /** Under a red guard on the panel (the mock-up: АКБ and ГЕН - not switched off by accident). */
    public static final boolean[] GUARDED = {true, true, false, false, false, false, false};
    /** A switch's lamp colour when lit (0xRRGGBB; the mock-up: power / nav green, strobe / landing white, beacon red, heat amber). */
    public static final int[] SW_COLOUR = {0x46E07A, 0x46E07A, 0x46E07A, 0xF4F4EC, 0xFF4636, 0xF4F4EC, 0xFFB43A};

    private SystemsMath() {
    }

    /** The board systems apply: the light plane, the jet, the tiltrotor. */
    public static boolean applies(VehicleType t) {
        return t != null && (t.movement == Movement.PLANE || t.movement == Movement.TILT);
    }

    public static boolean on(int sw, int i) {
        return i >= 0 && i < SWITCHES && (sw & 1 << i) != 0;
    }

    public static int toggle(int sw, int i) {
        return i >= 0 && i < SWITCHES ? (sw ^ 1 << i) & ALL_ON : sw & ALL_ON;
    }

    /** Saved bits sanitised (an old save without the key: all on). */
    public static int sanitize(int sw) {
        return sw & ALL_ON;
    }

    /** The switch a light of this kind (VehicleLights.*) hangs on, or -1 (brake glow, amber beacons, markers: none). */
    public static int lightSwitch(int kind) {
        switch (kind) {
            case VehicleLights.RED:
            case VehicleLights.GREEN:
            case VehicleLights.WHITE:
                return SW_NAV;
            case VehicleLights.STROBE:
                return SW_STROBE;
            case VehicleLights.BEACON:
                return SW_BEACON;
            case VehicleLights.HEAD:
                return SW_LAMP;
            default:
                return -1;
        }
    }

    /** A light of this kind may shine (other vehicles: always - as before D2). */
    public static boolean lightOn(boolean applies, int sw, int kind) {
        int s = lightSwitch(kind);
        return !applies || s < 0 || on(sw, s);
    }

    // ---------------------------------------------------------------- the battery

    /** Battery units (full) and the least for a start. */
    public static final double BAT_MAX = 1000, BAT_START = 50;
    /** ГЕН off: the board flattens a full battery in this many ticks of flying (10 min). */
    public static final int BAT_FLIGHT_TICKS = 12000;
    /** Per tick: the starter's draw (3 % in 1.5 s), ГЕН's charge (empty -> full in 25 s), the charging station's. */
    public static final double BAT_CRANK = 1, BAT_CHARGE = 2, BAT_STATION = 5;
    /** EU per battery unit ГЕН charges (it takes it from the main cells). */
    public static final double BAT_EU = 2;

    /**
     * The battery one tick on: ГЕН (with АКБ on) charges it from the main cells while they have charge; without ГЕН the
     * board (someone aboard or the engine turning) drains it; the starter draws on top; a charging station tops it up.
     */
    public static double batteryStep(double bat, int sw, boolean cells, boolean busy, boolean cranking, boolean station) {
        double b = bat;
        boolean master = on(sw, SW_BAT);
        if (master && on(sw, SW_GEN) && cells) {
            b += BAT_CHARGE;
        } else if (master && busy) {
            b -= BAT_MAX / BAT_FLIGHT_TICKS;
        }
        if (cranking) {
            b -= BAT_CRANK;
        }
        if (station) {
            b += BAT_STATION;
        }
        return b < 0 ? 0 : b > BAT_MAX ? BAT_MAX : b;
    }

    /** EU the step took from the main cells (ГЕН's charging; the station's is the station's). */
    public static double batteryEu(double before, double after, int sw, boolean cells, boolean station) {
        if (!(on(sw, SW_BAT) && on(sw, SW_GEN) && cells)) {
            return 0;
        }
        double gain = after - before - (station ? BAT_STATION : 0);
        return gain > 0 ? Math.min(gain, BAT_CHARGE) * BAT_EU : 0;
    }

    /** The board has power: АКБ on and the battery not flat. */
    public static boolean electric(int sw, double bat) {
        return on(sw, SW_BAT) && bat > 0;
    }

    // ---------------------------------------------------------------- the engine

    public static final int OFF = 0, CRANK = 1, WARM = 2, RUN = 3;
    /** Lang key part per state: sva.sys.engine.KEY. */
    public static final String[] ENGINE_KEYS = {"off", "crank", "warm", "run"};
    /** The power at the start of the warm-up (it ramps to 1 by RUN). */
    public static final float WARM_POWER = 0.3F;
    /** The engine stops after this many ticks without a pilot (10 s). */
    public static final int IDLE_STOP = 200;
    /** A worn engine's misfire chance at a start: this x the failure chance per minute (8 % -> 32 % at 100 % wear). */
    public static final double MISFIRE_K = 4;

    /** Start / tick results. */
    public static final int R_NONE = 0, R_STARTED = 1, R_RUNNING = 2, R_NO_MASTER = 3, R_LOW_BATTERY = 4, R_NO_CHARGE = 5, R_CATCH = 6,
            R_READY = 7, R_STOPPED = 8, R_MISFIRE = 9, R_STALLED = 10;

    public static boolean jet(VehicleType t) {
        return t == VehicleType.JET;
    }

    /** The starter's ticks before it catches: 1.5 s (the jet's spool-up 3 s); a third of it windmilling in the air. */
    public static int crankTicks(VehicleType t, boolean windmill) {
        int n = jet(t) ? 60 : 30;
        return windmill ? n / 3 : n;
    }

    /** The warm-up's ticks: 3 s (the jet's light-off to idle 2 s). */
    public static int warmTicks(VehicleType t) {
        return jet(t) ? 40 : 60;
    }

    /** The engine gives lift / thrust (warming up or running). */
    public static boolean lifts(int state) {
        return state == WARM || state == RUN;
    }

    /** The engine's power 0..1: nothing off / cranking, WARM_POWER -> 1 across the warm-up, 1 running. */
    public static float power(int state, int ticks, int warm) {
        if (state == RUN) {
            return 1F;
        }
        if (state != WARM) {
            return 0F;
        }
        float f = warm <= 0 ? 1F : Math.min(1F, Math.max(0F, ticks / (float) warm));
        return WARM_POWER + (1F - WARM_POWER) * f;
    }

    /**
     * The start sequence's progress 0..1 for the HUD / the jet's spool sound: across CRANK 0 -> CRANK_SHARE, across
     * WARM CRANK_SHARE -> 1; RUN 1, OFF 0.
     */
    public static final float CRANK_SHARE = 0.35F;

    public static float progress(int state, int ticks, int crank, int warm) {
        switch (state) {
            case CRANK:
                return CRANK_SHARE * Math.min(1F, ticks / (float) Math.max(1, crank));
            case WARM:
                return CRANK_SHARE + (1F - CRANK_SHARE) * Math.min(1F, ticks / (float) Math.max(1, warm));
            case RUN:
                return 1F;
            default:
                return 0F;
        }
    }

    /** The propeller's spin while the starter turns it (deg / tick: the mock-up's ~0.3 rev / s). */
    public static final float CRANK_SPIN = 6F;

    /**
     * A start asked for (ЗАПУСК, the menu, auto-start): whether it may crank now. Already warming / running: R_RUNNING;
     * cranking: R_STARTED (no change).
     */
    public static int canStart(int state, int sw, double bat, boolean cells) {
        if (state != OFF) {
            return state == CRANK ? R_STARTED : R_RUNNING;
        }
        if (!on(sw, SW_BAT)) {
            return R_NO_MASTER;
        }
        if (!cells) {
            return R_NO_CHARGE;
        }
        if (bat < BAT_START) {
            return R_LOW_BATTERY;
        }
        return R_STARTED;
    }

    /** Auto-start: the engine off, the board on, the pilot pushes forward (the tiltrotor in vertical mode: up too). */
    public static boolean autoStart(int state, int sw, boolean fwd, boolean up, boolean tiltVertical) {
        return state == OFF && on(sw, SW_BAT) && (fwd || up && tiltVertical);
    }

    /** The engine's state machine (the server's; the self-test drives it). */
    public static final class Engine {
        public int state = OFF, ticks;

        /** «ЗАПУСК»: cranks if it may (canStart); returns canStart's result. */
        public int start(int sw, double bat, boolean cells) {
            int r = canStart(state, sw, bat, cells);
            if (r == R_STARTED && state == OFF) {
                state = CRANK;
                ticks = 0;
            }
            return r;
        }

        /** «Стоп» / the master off. @return R_STOPPED if it was turning */
        public int stop() {
            boolean was = state != OFF;
            state = OFF;
            ticks = 0;
            return was ? R_STOPPED : R_NONE;
        }

        /** Straight to RUN (world tests; vehicles.engineStart off). */
        public void run() {
            state = RUN;
            ticks = 0;
        }

        /**
         * One tick: stops without the master / charge in the cells; a crank with a flat battery stalls; the crank catches
         * after `crank` ticks unless `roll` (0..1) falls under `misfire` (back to OFF); the warm-up ends in RUN.
         */
        public int tick(int sw, double bat, boolean cells, int crank, int warm, double roll, double misfire) {
            if (state == OFF) {
                return R_NONE;
            }
            if (!on(sw, SW_BAT) || !cells) {
                return stop();
            }
            ticks++;
            if (state == CRANK) {
                if (bat <= 0) {
                    stop();
                    return R_STALLED;
                }
                if (ticks >= crank) {
                    if (roll < misfire) {
                        stop();
                        return R_MISFIRE;
                    }
                    state = WARM;
                    ticks = 0;
                    return R_CATCH;
                }
            } else if (state == WARM && ticks >= warm) {
                state = RUN;
                ticks = 0;
                return R_READY;
            }
            return R_NONE;
        }
    }

    // ---------------------------------------------------------------- the heat (ОБОГРЕВ)

    /** Cold air over this height (blocks) - or in falling snow. */
    public static final double COLD_Y = 150;
    /** ОБОГРЕВ's EU in the cold: this share of the type's cruise EU. */
    public static final double HEAT_EU_SHARE = 0.05;
    /** Ticks of the cold with the heat off until the pitot freezes (30 s); it thaws twice as fast. */
    public static final int ICE_TICKS = 600;

    public static boolean cold(double y, boolean snowing) {
        return y > COLD_Y || snowing;
    }

    /** Ice one tick on: grows in the cold without heat (while the board is on or someone aboard), thaws otherwise. */
    public static int iceStep(int ice, boolean cold, boolean heating) {
        int n = cold && !heating ? ice + 1 : ice - 2;
        return n < 0 ? 0 : n > ICE_TICKS ? ICE_TICKS : n;
    }

    /** The frost on the windscreen 0..3 (synced; the client's overlay). */
    public static int frost(int ice) {
        return ice <= 0 ? 0 : Math.min(3, 1 + ice * 3 / ICE_TICKS);
    }

    public static boolean iced(int ice) {
        return ice >= ICE_TICKS;
    }

    // ---------------------------------------------------------------- failures (★7)

    public static final int F_ENGINE = 1, F_FLAP = 2, F_INSTR = 4, F_ALL = 7;
    /** At most this many at once. */
    public static final int MAX_FAILS = 2;
    /** Wear (1 - hp / max) from which failures may come, the chance per minute at full wear, a roll every CHECK_TICKS. */
    public static final double WEAR_MIN = 0.6, CHANCE_MAX = 0.08;
    public static final int CHECK_TICKS = 1200;
    /** The kinds' shares (engine, flap, instrument - the mock-up's table). */
    public static final double SHARE_ENGINE = 0.45, SHARE_FLAP = 0.25;
    /** A failed engine's power. */
    public static final float ENGINE_POWER = 0.6F;
    /** A landing with a failed engine: wear +5 % of the hull. */
    public static final double LANDING_WEAR = 0.05;
    /** Instrument slots a failure picks from (the client takes it modulo its dials). */
    public static final int INSTR_SLOTS = 8;

    /** The chance of a failure per minute of flight at this wear (0..1). */
    public static double chancePerMinute(double wear) {
        if (!(wear > WEAR_MIN)) {
            return 0;
        }
        double k = Math.min(1, (wear - WEAR_MIN) / (1 - WEAR_MIN));
        return CHANCE_MAX * k * k;
    }

    public static int count(int fails) {
        return Integer.bitCount(fails & F_ALL);
    }

    /**
     * The failure a roll (0..1) picks: by the shares engine / flap / instrument; a kind already failed or not possible
     * (no flaps) passes to the next one; 0 when MAX_FAILS are on already or nothing is left.
     */
    public static int pick(double roll, int fails, boolean flaps) {
        if (count(fails) >= MAX_FAILS) {
            return 0;
        }
        int[] order = {F_ENGINE, F_FLAP, F_INSTR};
        int first = roll < SHARE_ENGINE ? 0 : roll < SHARE_ENGINE + SHARE_FLAP ? 1 : 2;
        for (int k = 0; k < 3; k++) {
            int f = order[(first + k) % 3];
            if ((fails & f) == 0 && (f != F_FLAP || flaps)) {
                return f;
            }
        }
        return 0;
    }

    /** The engine's power factor from the failures (1 = sound). */
    public static float failPower(int fails) {
        return (fails & F_ENGINE) != 0 ? ENGINE_POWER : 1F;
    }

    /** The master caution «ВНИМАНИЕ»: any failure or the pitot iced. */
    public static boolean caution(int fails, boolean iced) {
        return (fails & F_ALL) != 0 || iced;
    }

    // ---------------------------------------------------------------- the synced word

    private static final int P_ENGINE = 7, P_FAILS = 9, P_INSTR = 12, P_BAT = 15, P_FROST = 22;
    public static final int P_CFG_ENGINE = 1 << 24, P_CFG_FAILS = 1 << 25, P_HEATING = 1 << 26, P_CHARGING = 1 << 27, P_COLD = 1 << 28,
            P_ICED = 1 << 29;

    /** switches | engine state | failures | instrument slot | battery % | frost | flags (P_*) - 30 bits, the sign bit unused. */
    public static int pack(int sw, int engine, int fails, int instr, double bat, int frost, int flags) {
        int pct = (int) Math.round(Math.max(0, Math.min(BAT_MAX, bat)) * 100 / BAT_MAX);
        return sw & ALL_ON | (engine & 3) << P_ENGINE | (fails & F_ALL) << P_FAILS | (instr & 7) << P_INSTR | (pct & 127) << P_BAT
                | (frost & 3) << P_FROST | flags & (P_CFG_ENGINE | P_CFG_FAILS | P_HEATING | P_CHARGING | P_COLD | P_ICED);
    }

    public static int switches(int w) {
        return w & ALL_ON;
    }

    public static int engine(int w) {
        return w >> P_ENGINE & 3;
    }

    public static int fails(int w) {
        return w >> P_FAILS & F_ALL;
    }

    public static int instr(int w) {
        return w >> P_INSTR & 7;
    }

    public static int batteryPct(int w) {
        return w >> P_BAT & 127;
    }

    public static int frostLevel(int w) {
        return w >> P_FROST & 3;
    }
}
