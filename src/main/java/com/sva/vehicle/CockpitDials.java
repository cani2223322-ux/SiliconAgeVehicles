package com.sva.vehicle;

import java.util.List;

/**
 * ★14 live instruments: WHAT the dials and screens found by CockpitLayout show (mock-up veh_design_6_gauges.png). Pure
 * logic (the self-test runs it): the readings, their scales, the needle angle, the per-type dial table and which screen
 * gets which job. The client side (client/CockpitInstrumentsSVA) reads the values and draws.
 */
public final class CockpitDials {

    // readings
    public static final int SPEED = 0, ALT = 1, VSPEED = 2, CHARGE = 3, RPM = 4, DRILL = 5, DEPTH = 6, CARGO = 7, CORE = 8,
            SHIELD = 9, HEAT = 10, HULL = 11, THROTTLE = 12, GAS = 13,
            /** Cockpit stage 1, the plane's six-pack: turn rate (deg/s, centred) and heading (0..360, 0 = north). */
            TURN = 14, HEADING = 15;
    /** Lang key part per reading: sva.cockpit.dial.KEY (the label) and sva.cockpit.unit.KEY (the unit line). */
    public static final String[] KEYS = {"speed", "alt", "vs", "charge", "rpm", "drill", "depth", "cargo", "core", "shield", "heat",
            "hull", "throttle", "gas", "turn", "heading"};

    // screen jobs
    /** The largest screen: a few text lines (charge bar, hull, gas, mode, gear...). */
    public static final int SCREEN_MAIN = 0;
    /** A small screen: one reading, big (label + value). */
    public static final int SCREEN_SMALL = 1;
    /** A tall narrow strip (narrower than 0.6 of its height): a vertical bar. */
    public static final int SCREEN_BAR = 2;
    /** Aircraft: the most square screen of at least ATT_MIN a side - the attitude indicator (pitch / bank). */
    public static final int SCREEN_ATTITUDE = 3;
    public static final float ATT_MIN = 0.07F;
    /**
     * Cockpit stage 2, the drill: the console right of the cluster («ДОБЫЧА», mock-up veh_cab_1_car.png): mode, blocks
     * dug this trip, the bunker, the bore head. Given to the widest of two or more text screens (the console strip 0.200 x
     * 0.081 beside the left computer 0.144 x 0.119).
     */
    public static final int SCREEN_MINING = 4;
    /** A screen of at least this area (model units^2) gets the text lines. */
    public static final float MAIN_AREA = 0.015F;

    /** Needle sweep: 270 degrees, min at -135 (lower left), max at +135 (lower right), clockwise as the pilot sees it. */
    public static final float SWEEP = 270F;

    private CockpitDials() {
    }

    /**
     * Dials left -> right as the pilot sees them (CockpitLayout's order); a table may be longer than the model's dial
     * count (the rest goes unused), never shorter (self-test). Counts measured from the OBJs (cockpit stage 1, 2026-10):
     * buggy / truck 2 (the cluster: speed, charge), drill 4 (the cluster + the drill console right of it: power, depth),
     * heli 5 (the co-pilot's cluster on the left: vertical speed, charge; the pilot's panel: altitude, speed, [the attitude
     * screen], rotor rpm), plane 5 (the six-pack column by column, left -> right, top -> bottom: speed / turn, [attitude] /
     * heading, altitude / vertical speed), jet 1 (the small disc right of the glass panels), tilt 4, shuttle 4, ship 4
     * (the console under the canopy), hoverbike 0 (one flat screen).
     */
    public static int[] table(VehicleType t) {
        switch (t) {
            case BUGGY:
                return new int[] {SPEED, CHARGE};
            case HOVER:
                return new int[] {SPEED, CHARGE};
            case HELI:
                return new int[] {VSPEED, CHARGE, ALT, SPEED, RPM};
            case PLANE:
                return new int[] {SPEED, TURN, HEADING, ALT, VSPEED};
            case TRUCK:
                return new int[] {SPEED, CHARGE};
            case DRILL:
                return new int[] {SPEED, CHARGE, DRILL, DEPTH};
            case JET:
                return new int[] {THROTTLE, SPEED};
            case TILT:
                return new int[] {SPEED, ALT, VSPEED, RPM};
            case SHUTTLE:
                return new int[] {SPEED, ALT, VSPEED, CHARGE};
            case SHIP:
                return new int[] {CORE, SHIELD, SPEED, ALT};
            default:
                return new int[] {SPEED, CHARGE};
        }
    }

    /** What the small screens / bars show, in turn (the first small screen gets the first one...). */
    public static int[] smallTable(VehicleType t) {
        switch (t) {
            case BUGGY:
            case TRUCK:
                return new int[] {CARGO, HEAT};
            case DRILL:
                return new int[] {CHARGE, CARGO};
            case HELI:
            case PLANE:
            case JET:
            case TILT:
            case SHUTTLE:
                return new int[] {CHARGE, GAS, HEAT};
            case SHIP:
                return new int[] {CHARGE, CORE};
            default:
                return new int[] {CHARGE, HEAT};
        }
    }

    /** The low end of a reading's scale (vertical speed is centred: -max..+max; the rest start at 0). */
    public static float min(int kind, VehicleType t) {
        return kind == VSPEED || kind == TURN ? -max(kind, t, 0) : 0F;
    }

    /**
     * The high end: speed = the type's fastest (afterburner / boost / tiltrotor plane mode included) rounded up to a
     * round number (niceMax), altitude = the world height 256, vertical speed 10 blocks/s, turn rate = the type's full
     * turn rate in deg/s (niceMax; centred like the vertical speed), heading 360, depth Y 128, cargo = the
     * slots (with a trailer when hitched: `cargoSlots`), the rest 100 %.
     */
    public static float max(int kind, VehicleType t, int cargoSlots) {
        switch (kind) {
            case SPEED: {
                double v = t.topSpeed * Math.max(1, t.gases.boostMul);
                if (t.movement == Movement.PLANE || t.movement == Movement.TILT) {
                    v = Math.max(v, PlaneMath.maxSpeed(t, t.gases.boostMul));
                }
                return niceMax(VehicleMath.kmh(Math.min(v, FlightMath.SPEED_CAP)));
            }
            case ALT:
                return 256F;
            case VSPEED:
                return 10F;
            case TURN:
                return niceMax(t.turnRate * 20);                   // the type's full turn rate, deg/s
            case HEADING:
                return 360F;
            case DEPTH:
                return 128F;
            case CARGO:
                return Math.max(1, cargoSlots);
            default:
                return 100F;
        }
    }

    /** The smallest of 1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8 x 10^k that is >= x (a dial's top mark); 1 for x <= 0. */
    public static float niceMax(double x) {
        if (!(x > 0)) {
            return 1F;
        }
        double p = Math.pow(10, Math.floor(Math.log10(x)));
        double[] steps = {1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10};
        for (double s : steps) {
            if (s * p >= x - 1e-9) {
                return (float) (s * p);
            }
        }
        return (float) (10 * p);
    }

    /** The needle's angle (degrees clockwise from straight up) for `value` on [min, max]: clamped to the sweep. */
    public static float needleAngle(float value, float min, float max) {
        if (!(max > min) || Float.isNaN(value)) {
            return -SWEEP / 2;
        }
        float f = Math.max(0F, Math.min(1F, (value - min) / (max - min)));
        return -SWEEP / 2 + SWEEP * f;
    }

    /**
     * The needle following its reading, frame by frame: an exponential approach with a time constant of 0.15 s (dt in
     * seconds; a long pause or the first frame jumps straight to the value).
     */
    public static float follow(float shown, float target, float dt) {
        if (Float.isNaN(shown) || dt <= 0 || dt > 1F) {
            return target;
        }
        return shown + (target - shown) * (1F - (float) Math.exp(-dt / 0.15F));
    }

    /**
     * Each screen's job (same order as the layout's screens, the largest first): aircraft get the attitude indicator on
     * the most square screen of at least ATT_MIN a side (aspect 0.6..1.7); tall narrow ones (width < 0.6 x height) are
     * bars; the largest and every other one of at least MAIN_AREA (the ship's console strip and its passengers' side panel,
     * the jet's centre panel 0.175 x 0.113) are text screens; the small ones (0.075 x 0.113) show one reading each.
     */
    public static int[] screenRoles(List<CockpitLayout.Screen> screens, boolean aircraft) {
        return screenRoles(screens, aircraft, false);
    }

    /** As above; `drill`: the widest of two or more text screens becomes the SCREEN_MINING console. */
    public static int[] screenRoles(List<CockpitLayout.Screen> screens, boolean aircraft, boolean drill) {
        int n = screens.size();
        int[] roles = new int[n];
        int att = -1;
        float bestSquare = Float.MAX_VALUE;
        if (aircraft) {
            for (int i = 0; i < n; i++) {
                CockpitLayout.Screen s = screens.get(i);
                float aspect = s.w / s.h;
                if (s.w >= ATT_MIN && s.h >= ATT_MIN && aspect >= 0.6F && aspect <= 1.7F) {
                    float off = Math.abs((float) Math.log(aspect));
                    if (off < bestSquare) {
                        bestSquare = off;
                        att = i;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            CockpitLayout.Screen s = screens.get(i);
            if (i == att) {
                roles[i] = SCREEN_ATTITUDE;
            } else if (s.w < 0.6F * s.h) {
                roles[i] = SCREEN_BAR;
            } else if (i == 0 || s.w * s.h >= MAIN_AREA) {
                roles[i] = SCREEN_MAIN;
            } else {
                roles[i] = SCREEN_SMALL;
            }
        }
        if (drill) {
            int mains = 0, widest = -1;
            for (int i = 0; i < n; i++) {
                if (roles[i] == SCREEN_MAIN) {
                    mains++;
                    CockpitLayout.Screen s = screens.get(i);
                    if (widest < 0 || s.w / s.h > screens.get(widest).w / screens.get(widest).h) {
                        widest = i;
                    }
                }
            }
            if (mains >= 2) {
                roles[widest] = SCREEN_MINING;
            }
        }
        return roles;
    }
}
