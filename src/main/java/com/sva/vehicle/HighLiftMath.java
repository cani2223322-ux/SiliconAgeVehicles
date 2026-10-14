package com.sva.vehicle;

/**
 * Aircraft stage A (★1 flaps, ★2 slats, ★3 spoilers / airbrakes, ★26 their effect on flight; mock-ups
 * docs/mockups/veh_air2_1_highlift.png, veh_air2_2_jet_tilt.png) - pure rules and numbers, checked by the dedicated
 * server's self-test (debug/SelfTestAirSVA). The server owns the state (EntityVehicleSVA: flap setting, the pilot's
 * airbrake, the ground spoilers, the slats - synced in one data watcher int, pack()), the driver's client flies with the
 * same derived PlaneParams (params()), so the stall, the take-off run and the server's flying-move check agree.
 * <ul>
 * <li>Flaps: «Закрылки +» / «Закрылки −» step through the type's settings (light plane 0 / 10 / 20 / 35°, jet 0 / 20 /
 * 40°, tiltrotor flaperons 0 / 15 / 25°), 1.5 s a step. Each setting lowers the stall and the lift-off speed (a
 * shorter run) and adds drag and EU (the light plane: stall 36 / 32 / 30 / 29 km/h, run 100 / 82 / 74 / 70 %, drag
 * +12 / +30 / +60 %, EU x1.1 / x1.25 / x1.5 - the mock-up's table; lift +15 / +25 / +32 % is what lowers the stall).
 * Protection: a setting doesn't extend over its speed (light plane 160 / 140 / 120 km/h) and blows back to the highest
 * one allowed when the airspeed passes it - the «V>120 ЗАКР» warning and a beep.</li>
 * <li>Slats (light plane, jet; automatic - «Предкрылки: АВТО»): out under slatOut or over slatAoa of angle of attack,
 * in over slatIn (hysteresis), always out with the flaps down; out they lower the stall by slatStall of the clean one
 * (light plane 3 km/h). They don't change the lift-off speed - flaps 0 takes off exactly as before.</li>
 * <li>Airbrake: in the air «Воздушный тормоз» - the spoilers (the jet: its dorsal speed brake) up: more drag, a steep
 * descent that gains no speed; retracts by itself over AIRBRAKE_RETRACT throttle. On the ground the spoilers come up by
 * themselves on a touch-down at low throttle: the lift is dumped, the wheel brakes grip - the roll-out x0.6.</li>
 * </ul>
 * Flaps 0, slats in, spoilers down = the type's own PlaneParams object (params() returns it): the flight is exactly as
 * before. Speeds in blocks/tick (72 km/h = 1 b/t).
 */
public final class HighLiftMath {

    /** Spoilers / airbrake retract over this throttle; the ground spoilers come up at a touch-down at or under GROUND_THROTTLE. */
    public static final double AIRBRAKE_RETRACT = 0.5, GROUND_THROTTLE = 0.25;
    /** The ground spoilers go down again under this speed (taxiing). */
    public static final double GROUND_STOP = 0.05;
    /** The roll-out with the ground spoilers up: wheel brakes and rolling friction x 1 / ROLLOUT (the run x ROLLOUT). */
    public static final double ROLLOUT = 0.6;
    /** Airbrake in the air: no keys = sink at SINK x the dive rate, «down» = DIVE x it, Space climbs at CLIMB x the capacity. */
    public static final double AIRBRAKE_SINK = 0.5, AIRBRAKE_DIVE = 1.4, AIRBRAKE_CLIMB = 0.5;
    /** Angle of attack at the clean stall (deg) - the slats' AoA rule: aoa = STALL_AOA x (stall / speed)^2 + the climb angle. */
    public static final double STALL_AOA = 15;
    /** A flap step takes this many ticks (1.5 s); the slats 0.5 s; spoilers / brake 0.3 s. */
    public static final int FLAP_TICKS = 30, SLAT_TICKS = 10, SPOILER_TICKS = 6;
    /** The overspeed warning («V>120 ЗАКР») stays this many ticks after a refused / blown-back setting. */
    public static final int WARN_TICKS = 60;
    /** flapStep results: the new setting (>= 0) or refused. */
    public static final int REFUSED_SPEED = -1, REFUSED_END = -2, REFUSED_OFF = -3;

    /** One type's high-lift devices. */
    public static final class Spec {
        /** Flap deflection per setting (deg; setting 0 = 0). */
        public final float[] angles;
        /** Per setting: stall / lift-off speed factors, drag on the r^2 term, EU factor, highest airspeed (b/t). */
        public final double[] stallMul, liftOffMul, dragMul, costMul, maxSpeed;
        /** Slats: out under slatOut / over slatAoa deg, in over slatIn (b/t); lower the stall by slatStall x the clean one. */
        public final boolean slats;
        public double slatOut, slatIn, slatAoa, slatStall;
        /** Airbrake (spoilers, the jet's dorsal brake): extra drag in the air (on the r^2 term); the brake opens only to
         * brakeFastDeg over brakeFastSpeed (b/t) - 0 = no limit; its full deflection brakeDeg (the model's). */
        public final boolean airbrake;
        public double airDrag, brakeFastSpeed;
        public float brakeDeg, brakeFastDeg;
        /** Tiltrotor: flaperons - droop to the last setting's angle by themselves in the vertical mode, ailerons +-ailMix. */
        public boolean flaperons;
        public float ailMix;

        Spec(float[] angles, double[] stallMul, double[] liftOffMul, double[] dragMul, double[] costMul, double[] maxKmh, boolean slats,
             boolean airbrake) {
            this.angles = angles;
            this.stallMul = stallMul;
            this.liftOffMul = liftOffMul;
            this.dragMul = dragMul;
            this.costMul = costMul;
            this.maxSpeed = new double[maxKmh.length];
            for (int i = 0; i < maxKmh.length; i++) {
                maxSpeed[i] = maxKmh[i] / 72.0;
            }
            this.slats = slats;
            this.airbrake = airbrake;
        }

        /** Number of settings (4: 0..3, 3: 0..2). */
        public int settings() {
            return angles.length;
        }

        /** The last setting. */
        public int last() {
            return angles.length - 1;
        }

        /** Deflection (deg) at a fractional setting position (the animation: 1.5 s a step, linear between settings). */
        public float angleAt(float pos) {
            if (pos <= 0) {
                return 0F;
            }
            if (pos >= last()) {
                return angles[last()];
            }
            int i = (int) Math.floor(pos);
            float f = pos - i;
            return angles[i] + (angles[i + 1] - angles[i]) * f;
        }
    }

    private static final double INF = Double.POSITIVE_INFINITY;
    /**
     * The light plane - the mock-up's table: stall 36 / 32 / 30 / 29 km/h (-3 with the slats), lift-off 40 / 36.4 /
     * 34.7 / 33.1 km/h (with the drag: run 100 / 82 / 74 / 70 % - scratchpad airA lift-off fit), max 160 / 140 / 120 km/h;
     * slats out < 70 km/h or AoA > 10 deg, in > 90; spoilers 50 deg (+0.8 drag in the air).
     */
    public static final Spec PLANE = new Spec(new float[] {0, 10, 20, 35}, new double[] {1, 32 / 36.0, 30 / 36.0, 29 / 36.0},
            new double[] {1, 0.9104, 0.8676, 0.8287}, new double[] {1, 1.12, 1.30, 1.60}, new double[] {1, 1.1, 1.25, 1.5},
            new double[] {INF, 160, 140, 120}, true, true);
    /**
     * The jet (veh_air2_2: slats along the span, plain flaps, the dorsal speed brake 45 deg): flaps 0 / 20 / 40 deg - the
     * stall x0.79 / x0.71 (the mock-up's 120 -> 95 -> 85 km/h ratios: 65 -> 51 -> 46 km/h), run 85 / 75 %, drag +30 / +60 %,
     * EU x1.25 / x1.5, max 170 / 140 km/h; slats out < 180 km/h or AoA > 12 deg, in > 220, -8 %; the brake doubles the
     * drag in the air and opens only to 25 deg over 180 km/h.
     */
    public static final Spec JET = new Spec(new float[] {0, 20, 40}, new double[] {1, 0.79, 0.71}, new double[] {1, 0.9157, 0.8515},
            new double[] {1, 1.30, 1.60}, new double[] {1, 1.25, 1.5}, new double[] {INF, 170, 140}, true, true);
    /**
     * The tiltrotor's flaperons 0 / 15 / 25 deg (airplane mode only): stall x0.89 / x0.83, run 82 / 70 % (the mock-up's
     * «разбег короче на 30 %»), drag +15 / +30 %, EU x1.1 / x1.25, max 140 / 120 km/h; no slats, no airbrake.
     */
    public static final Spec TILT = new Spec(new float[] {0, 15, 25}, new double[] {1, 32 / 36.0, 30 / 36.0}, new double[] {1, 0.9057, 0.8379},
            new double[] {1, 1.15, 1.30}, new double[] {1, 1.1, 1.25}, new double[] {INF, 140, 120}, false, false);

    /**
     * Liner wave 1 (plan-next §7: flap lever 0 / 1 / 2 / 3 / FULL, LinerControls.FLAPS): flaps 0 / 10 / 15 / 20 / 35 deg -
     * the stall x0.92 / 0.86 / 0.82 / 0.78 (83 -> 76 -> 71 -> 68 -> 65 km/h), lift-off x0.90 / 0.85 / 0.82 / 0.80, drag +10 / +20 /
     * +35 / +60 %, EU x1.05..1.35, max 130 / 120 / 110 / 100 km/h (the A320's VFE ladder scaled to its 144 km/h); slats out
     * < 110 km/h or AoA > 10 deg, in > 125, -6 %; the spoilers 50 deg (+0.9 drag) as the speed brake / ground spoilers.
     */
    public static final Spec LINER = new Spec(new float[] {0, 10, 15, 20, 35}, new double[] {1, 0.92, 0.86, 0.82, 0.78},
            new double[] {1, 0.90, 0.85, 0.82, 0.80}, new double[] {1, 1.10, 1.20, 1.35, 1.60}, new double[] {1, 1.05, 1.1, 1.2, 1.35},
            new double[] {INF, 130, 120, 110, 100}, true, true);

    static {
        LINER.slatOut = 110 / 72.0;
        LINER.slatIn = 125 / 72.0;
        LINER.slatAoa = 10;
        LINER.slatStall = 0.06;
        LINER.airDrag = 0.9;
        LINER.brakeDeg = 50;
        PLANE.slatOut = 70 / 72.0;
        PLANE.slatIn = 90 / 72.0;
        PLANE.slatAoa = 10;
        PLANE.slatStall = 3 / 36.0;
        PLANE.airDrag = 0.8;
        PLANE.brakeDeg = 50;
        JET.slatOut = 180 / 72.0;
        JET.slatIn = 220 / 72.0;
        JET.slatAoa = 12;
        JET.slatStall = 0.08;
        JET.airDrag = 1.0;
        JET.brakeDeg = 45;
        JET.brakeFastDeg = 25;
        JET.brakeFastSpeed = 180 / 72.0;
        TILT.flaperons = true;
        TILT.ailMix = 10;
    }

    private HighLiftMath() {
    }

    /** The type's devices, or null (no high-lift devices: everything but the light plane, the jet and the tiltrotor). */
    public static Spec spec(VehicleType t) {
        if (t == null) {
            return null;
        }
        switch (t) {
            case PLANE:
                return PLANE;
            case JET:
                return JET;
            case TILT:
                return TILT;
            case LINER:
                return LINER;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ rules

    /**
     * «Закрылки +» (dir +1) / «−» (-1) at `airspeed`: the new setting, or REFUSED_SPEED (the next setting's limit is under
     * the airspeed - the protection), REFUSED_END (already at the end). Retracting is always allowed.
     */
    public static int flapStep(Spec s, int setting, int dir, double airspeed) {
        int to = setting + (dir > 0 ? 1 : -1);
        if (to < 0 || to > s.last()) {
            return REFUSED_END;
        }
        if (dir > 0 && airspeed > s.maxSpeed[to]) {
            return REFUSED_SPEED;
        }
        return to;
    }

    /** The protection's blow-back: the highest setting <= `setting` whose limit the airspeed is under (0 at worst). */
    public static int blowBack(Spec s, int setting, double airspeed) {
        int f = Math.max(0, Math.min(setting, s.last()));
        while (f > 0 && airspeed > s.maxSpeed[f]) {
            f--;
        }
        return f;
    }

    /** The speed limit (km/h) the warning names for a setting: its own, or the next one's at 0 / a refused step. */
    public static int limitKmh(Spec s, int setting) {
        int i = Math.max(1, Math.min(setting, s.last()));
        return (int) Math.round(s.maxSpeed[i] * 72);
    }

    /**
     * Angle of attack estimate (deg) for the slats' rule: level flight needs STALL_AOA at the clean stall speed and
     * falls with the square of the airspeed (lift ~ v^2 CL), plus the climb angle (vy over the airspeed).
     */
    public static double aoa(double cleanStall, double airspeed, double vy) {
        double v = Math.max(airspeed, 0.05);
        double r = cleanStall / v;
        return STALL_AOA * r * r + Math.toDegrees(Math.atan2(Math.max(0, vy), v));
    }

    /** The slats now (hysteresis): out with the flaps down, under slatOut or over slatAoa; in over slatIn; else as they were. */
    public static boolean slats(Spec s, boolean out, double airspeed, double aoa, int flaps) {
        if (s == null || !s.slats) {
            return false;
        }
        if (flaps > 0 || airspeed < s.slatOut || aoa > s.slatAoa) {
            return true;
        }
        if (airspeed > s.slatIn) {
            return false;
        }
        return out;
    }

    /** The pilot's airbrake stays out only up to AIRBRAKE_RETRACT throttle. */
    public static boolean airbrakeHeld(boolean on, double throttle) {
        return on && throttle <= AIRBRAKE_RETRACT;
    }

    /**
     * The ground spoilers after this tick: up at a touch-down (in the air last tick, on the ground now) at or under
     * GROUND_THROTTLE; down again in the air, over AIRBRAKE_RETRACT throttle or under GROUND_STOP speed.
     */
    public static boolean groundSpoilers(Spec s, boolean up, boolean wasAir, boolean air, double throttle, double speed) {
        if (s == null || !s.airbrake) {
            return false;
        }
        if (wasAir && !air && throttle <= GROUND_THROTTLE) {
            up = true;
        }
        return up && !air && throttle <= AIRBRAKE_RETRACT && speed >= GROUND_STOP;
    }

    /** The jet's brake opening (deg) at an airspeed: brakeFastDeg over brakeFastSpeed (0: no limit). */
    public static float brakeDeg(Spec s, double airspeed) {
        return s.brakeFastSpeed > 0 && airspeed > s.brakeFastSpeed ? s.brakeFastDeg : s.brakeDeg;
    }

    // ------------------------------------------------------------------ flight numbers

    /**
     * The PlaneParams to fly with: `base` itself for flaps 0, slats in, nothing out (exactly the old flight); otherwise a
     * derived copy (cached): stall x stallMul - slatStall, lift-off x liftOffMul, drag x dragMul (+ airDrag in the air with
     * the airbrake, + airDrag with the ground spoilers), EU x costMul, wheel brakes / rolling x 1 / ROLLOUT on the ground
     * spoilers, the airbrake's descent.
     */
    public static PlaneParams params(Spec s, PlaneParams base, int flaps, boolean slats, boolean airbrake, boolean ground) {
        if (s == null || base == null) {
            return base;
        }
        int f = Math.max(0, Math.min(flaps, s.last()));
        boolean sl = slats && s.slats, ab = airbrake && s.airbrake, gs = ground && s.airbrake;
        if (f == 0 && !sl && !ab && !gs) {
            return base;
        }
        int key = f << 3 | (sl ? 4 : 0) | (ab ? 2 : 0) | (gs ? 1 : 0);
        synchronized (CACHE) {
            PlaneParams[] arr = CACHE.get(base);
            if (arr == null) {
                arr = new PlaneParams[64];                         // liner wave 1: five flap settings (key up to 4 << 3 | 7)
                CACHE.put(base, arr);
            }
            if (arr[key] == null) {
                double stall = base.stall * (s.stallMul[f] - (sl ? s.slatStall : 0));
                double drag = s.dragMul[f] + (ab ? s.airDrag : 0) + (gs ? s.airDrag : 0);
                arr[key] = base.derive(stall, base.liftOff * s.liftOffMul[f], drag, s.costMul[f], gs ? 1 / ROLLOUT : 1, ab);
            }
            return arr[key];
        }
    }

    private static final java.util.Map<PlaneParams, PlaneParams[]> CACHE = new java.util.IdentityHashMap<PlaneParams, PlaneParams[]>();

    // ------------------------------------------------------------------ sync

    /** Data watcher bits: the setting (2 bits), the pilot's airbrake, the ground spoilers, slats out, the warning, brake limited. */
    public static final int P_FLAPS = 3, P_AIRBRAKE = 4, P_GROUND = 8, P_SLATS = 16, P_WARN = 32, P_FAST = 64;

    public static int pack(int flaps, boolean airbrake, boolean ground, boolean slats, boolean warn, boolean fast) {
        return (flaps & P_FLAPS) | (airbrake ? P_AIRBRAKE : 0) | (ground ? P_GROUND : 0) | (slats ? P_SLATS : 0) | (warn ? P_WARN : 0)
                | (fast ? P_FAST : 0);
    }

    // ------------------------------------------------------------------ animation (client)

    /** A fractional flap position moved toward the setting by one FLAP_TICKS-th a tick. */
    public static float flapPos(float pos, int setting) {
        return (float) AnimMath.approach(pos, setting, 1.0 / FLAP_TICKS);
    }

    /** A 0..1 device level moved toward on / off in `ticks` ticks. */
    public static float level(float lv, boolean on, int ticks) {
        return (float) AnimMath.approach(lv, on ? 1 : 0, 1.0 / ticks);
    }

    /**
     * The tiltrotor's flaperons: their angle at nacelle position `tilt` (0 vertical .. 1 airplane) - drooped to the last
     * setting's angle in the vertical mode (less of the wing in the rotors' wash), the pilot's setting in airplane mode,
     * blended through the transition.
     */
    public static float flaperonAngle(Spec s, float settingAngle, double tilt) {
        double k = Math.max(0, Math.min(1, tilt));
        return (float) (s.angles[s.last()] * (1 - k) + settingAngle * k);
    }
}
