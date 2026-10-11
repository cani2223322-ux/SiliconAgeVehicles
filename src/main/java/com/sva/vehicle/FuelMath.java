package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

import com.sc.util.ArmorGasSC.Gas;

/**
 * Hydrogen vehicles (plan-next §2, mock-ups docs/mockups/veh_fuel_1_system.png, veh_fuel_2_refuel.png): the buggy, the
 * hoverbike, the crawler, the drill vehicle, the helicopter, the light plane, the jet and the tiltrotor burn hydrogen in
 * an engine; the engine turns the wheels / rotor / propeller and a generator; the generator charges the removable board
 * battery (BatteryMath) that starts the engine and feeds the electrics. The shuttle and the Singular Ship stay on EU.
 * Pure logic (the self-test SelfTestFuelSVA runs it on the dedicated server).
 * <p>
 * <b>Modes</b> (vehicles.fuelMode): HYDROGEN (the default) - the above; EU - the old behaviour (driving spends the EU
 * buffer, no hydrogen tank, the battery item does nothing). The mode is the server's (ServerConfigSVA on clients).
 * <p>
 * <b>The tank</b>: one H2 tank per type (TANK mB, the mock-up's table); «Доп. бак» +50 % as for every gas tank. It is the
 * core's hydrogen (ArmorGasSC.Gas.HYDROGEN, fluid "hydrogen") - the gas column, the tanker trailer, the station's gas
 * module and core cells / buckets fill it like any other tank. The hover's lift gas and the jet's / tiltrotor's
 * afterburner gas were already hydrogen - it is the same tank now (bigger); the hover's cushion runs on the battery.
 * <p>
 * <b>Burn</b>: cruise = TANK / MINUTES (the mock-up's «полного бака»: minutes at cruise, no cargo), x the multipliers
 * (mul): idle 15 %, cruise 100 %, full throttle 160 % (a ramp by the throttle share), full cargo +15 %, a trailer +30 %,
 * climbing +40 %, over HIGH_Y blocks -10 %, Мотор+ I / II +15 / +30 %, the tiltrotor in vertical mode x2; they multiply.
 * <p>
 * <b>Electrics</b> (EU/t, from the battery's capacity so every type behaves alike): the board (instruments, lights,
 * radios) a full battery in BOARD_TICKS while someone is aboard or the engine turns; the hover's cushion in CUSHION_TICKS
 * (x CUSHION_BOOST on boost); the generator fills an empty battery in GEN_TICKS while the engine runs - more than the
 * board and the cushion take, so a running vehicle charges.
 */
public final class FuelMath {

    /** Config / ServerConfigSVA values of vehicles.fuelMode. */
    public static final String MODE_HYDROGEN = "HYDROGEN", MODE_EU = "EU";
    /**
     * Tests: the suites written before the hydrogen wave check the EU behaviour - they run with this set (the vehicles
     * act as in EU mode) unless a vehicle forces its own mode (VehicleState.fuelForce).
     */
    public static volatile boolean legacyTest;

    public static final Gas H2 = Gas.HYDROGEN;
    /** The mock-up's tank sizes (mB) and minutes on a full tank at cruise; 0: not a hydrogen vehicle. */
    private static final int[] TANK = new int[VehicleType.values().length];
    private static final int[] MINUTES = new int[VehicleType.values().length];

    static {
        set(VehicleType.BUGGY, 4000, 50);
        set(VehicleType.HOVER, 6000, 45);
        set(VehicleType.TRUCK, 16000, 65);
        set(VehicleType.DRILL, 24000, 55);
        set(VehicleType.HELI, 12000, 40);
        set(VehicleType.PLANE, 16000, 65);
        set(VehicleType.JET, 32000, 40);
        set(VehicleType.TILT, 24000, 50);
    }

    private static void set(VehicleType t, int tank, int minutes) {
        TANK[t.ordinal()] = tank;
        MINUTES[t.ordinal()] = minutes;
    }

    // multipliers (the mock-up's «Что влияет на расход», 100 % = cruise)
    public static final double IDLE = 0.15, FULL = 1.6, CRUISE_SHARE = 0.75, CARGO = 0.15, TRAILER = 1.3, CLIMB = 1.4, HIGH = 0.9,
            MOTOR_I = 1.15, MOTOR_II = 1.3, TILT_VERTICAL = 2.0;
    /** Over this height (blocks) the thin air: x HIGH. */
    public static final double HIGH_Y = 200;
    /** Climbing faster than this (blocks / tick, 1 b/s): x CLIMB. */
    public static final double CLIMB_VY = 0.05;
    /** The HUD's lamps: «МАЛО ТОПЛИВА» under LOW, «РЕЗЕРВ» (blinking) under RESERVE (shares of the tank). */
    public static final double LOW = 0.15, RESERVE = 0.05;
    /** Lamp states. */
    public static final int LAMP_OK = 0, LAMP_LOW = 1, LAMP_RESERVE = 2, LAMP_EMPTY = 3;

    // electrics (ticks a full battery lasts / takes)
    public static final double BOARD_TICKS = 36000, CUSHION_TICKS = 12000, CUSHION_BOOST = 2.5, GEN_TICKS = 6000;
    /** A start costs this share of the battery (the starter, spread over the crank). */
    public static final double START_SHARE = 0.03;
    /** The engine of a ground vehicle / the hoverbike / the helicopter: crank and warm-up ticks (the aircraft keep D2's). */
    public static final int GROUND_CRANK = 20, GROUND_WARM = 20;
    /** Nobody aboard for this long (30 s): the engine stops by itself (the aircraft keep D2's 10 s). */
    public static final int IDLE_STOP = 600;
    /** The flow shown: an average over ~10 s (EMA factor a tick). */
    public static final double FLOW_EMA = 1 / 200.0;

    private FuelMath() {
    }

    /** The type runs on hydrogen in HYDROGEN mode (the shuttle and the ship never). */
    public static boolean uses(VehicleType t) {
        return t != null && TANK[t.ordinal()] > 0;
    }

    /** The mode string is HYDROGEN (anything else - EU). */
    public static boolean hydrogenMode(String mode) {
        return mode == null || !MODE_EU.equalsIgnoreCase(mode.trim());
    }

    /** Sanitised config value. */
    public static String mode(String s) {
        return hydrogenMode(s) ? MODE_HYDROGEN : MODE_EU;
    }

    /**
     * This type is on hydrogen now: a hydrogen type, and the mode (force: 1 hydrogen, 0 EU, -1 the server's mode unless
     * legacyTest).
     */
    public static boolean on(VehicleType t, int force, boolean serverMode) {
        if (!uses(t)) {
            return false;
        }
        if (force >= 0) {
            return force == 1;
        }
        return serverMode && !legacyTest;
    }

    /** The H2 tank (mB) before «Доп. бак»; 0 for a type without one. */
    public static int tank(VehicleType t) {
        return t == null ? 0 : TANK[t.ordinal()];
    }

    /** Minutes on a full tank at cruise. */
    public static int minutes(VehicleType t) {
        return t == null ? 0 : MINUTES[t.ordinal()];
    }

    /** mB a tick at cruise (100 %). */
    public static double cruisePerTick(VehicleType t) {
        int m = minutes(t);
        return m <= 0 ? 0 : tank(t) / (m * 1200.0);
    }

    /** The tanks a vehicle has now: in hydrogen mode H2 first (added when the type had none), then the type's others. */
    public static List<Gas> gases(VehicleType t, boolean hydrogen) {
        if (!hydrogen || !uses(t)) {
            return t.gases.gases();
        }
        List<Gas> out = new ArrayList<Gas>();
        out.add(H2);
        for (Gas g : t.gases.gases()) {
            if (g != H2) {
                out.add(g);
            }
        }
        return out;
    }

    /** A tank's size before tuning: H2 in hydrogen mode from TANK, else the type's. */
    public static int capacity(VehicleType t, Gas g, boolean hydrogen) {
        if (g == H2 && hydrogen && uses(t)) {
            return tank(t);
        }
        return t.gases.capacity(g);
    }

    /**
     * The throttle part of the burn: IDLE at 0, 1 at CRUISE_SHARE of the top, FULL at the top (and over: boost, downhill).
     * `thr` = the share of the top speed / the plane's throttle (0..1+).
     */
    public static double throttleMul(double thr) {
        if (!(thr > 0.01)) {
            return IDLE;
        }
        if (thr <= CRUISE_SHARE) {
            return IDLE + (1 - IDLE) * thr / CRUISE_SHARE;
        }
        return 1 + (FULL - 1) * Math.min(1, (thr - CRUISE_SHARE) / (1 - CRUISE_SHARE));
    }

    /**
     * Every multiplier together: the throttle (throttleMul), the cargo fill 0..1, a trailer, climbing (vy blocks / tick,
     * aircraft only), height y (aircraft only), Мотор+ (tuning slots), the tiltrotor vertical.
     */
    public static double mul(double thr, double fill, boolean trailer, boolean flies, double vy, double y, int[] tuning, boolean tiltVertical) {
        double m = throttleMul(thr);
        m *= 1 + CARGO * Math.max(0, Math.min(1, fill));
        if (trailer) {
            m *= TRAILER;
        }
        if (flies && vy > CLIMB_VY) {
            m *= CLIMB;
        }
        if (flies && y > HIGH_Y) {
            m *= HIGH;
        }
        if (tuning != null && TuningMath.has(tuning, TuningMath.MOTOR_II)) {
            m *= MOTOR_II;
        } else if (tuning != null && TuningMath.has(tuning, TuningMath.MOTOR_I)) {
            m *= MOTOR_I;
        }
        if (tiltVertical) {
            m *= TILT_VERTICAL;
        }
        return m;
    }

    /** mB burnt this tick: cruise x mul x the config's consumption. */
    public static double burn(VehicleType t, double mul, double consumption) {
        return cruisePerTick(t) * Math.max(0, mul) * Math.max(0, consumption);
    }

    /** The lamp for `mb` of `cap`: empty / reserve (< 5 %) / low (< 15 %) / ok. */
    public static int lamp(int mb, int cap) {
        if (mb <= 0) {
            return LAMP_EMPTY;
        }
        double f = cap <= 0 ? 0 : mb / (double) cap;
        return f < RESERVE ? LAMP_RESERVE : f < LOW ? LAMP_LOW : LAMP_OK;
    }

    /** The flow average one tick on (mB / min). */
    public static double flowStep(double ema, double mbThisTick) {
        double now = mbThisTick * 1200;
        return ema + (now - ema) * FLOW_EMA;
    }

    /**
     * Minutes left: by the flow (mB / min) - parked (flow under a tenth of cruise) by the cruise burn, as the mock-up
     * says («на стоянке - по крейсерскому»). Infinity with nothing burnt at all.
     */
    public static double minutesLeft(VehicleType t, int mb, double flowPerMin, double consumption) {
        double cruise = cruisePerTick(t) * 1200 * Math.max(0, consumption);
        double f = flowPerMin < cruise * 0.1 ? cruise : flowPerMin;
        return f <= 0 ? Double.POSITIVE_INFINITY : Math.max(0, mb) / f;
    }

    /** Blocks left at `speed` (blocks / tick; parked: the type's cruise speed, CRUISE_SHARE of the top). */
    public static double blocksLeft(VehicleType t, double minutes, double speed) {
        if (Double.isInfinite(minutes)) {
            return Double.POSITIVE_INFINITY;
        }
        double v = speed > 0.02 ? speed : t.topSpeed * CRUISE_SHARE;
        return minutes * 1200 * v;
    }

    // ---------------------------------------------------------------- electrics

    /** The board's load (EU/t) for a battery of `cap` EU: someone aboard or the engine turning. */
    public static double boardLoad(double cap) {
        return cap / BOARD_TICKS;
    }

    /** The hover's cushion (EU/t) while it holds up (boost: x CUSHION_BOOST). */
    public static double cushionLoad(double cap, boolean boost) {
        return cap / CUSHION_TICKS * (boost ? CUSHION_BOOST : 1);
    }

    /** The generator's output (EU/t) while the engine runs (its warm-up at `power` 0..1). */
    public static double generator(double cap, double power) {
        return cap / GEN_TICKS * Math.max(0, Math.min(1, power));
    }

    /** EU a tick the starter draws while cranking `crankTicks`. */
    public static double starterPerTick(double cap, int crankTicks) {
        return cap * START_SHARE / Math.max(1, crankTicks);
    }
}
