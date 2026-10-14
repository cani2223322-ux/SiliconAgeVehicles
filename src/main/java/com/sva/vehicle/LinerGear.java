package com.sva.vehicle;

/**
 * Liner wave 2 (plan-next §7, mock-ups veh_liner_4 gear, 8 pedestal «АВАР. ВЫПУСК», 13b «КОЛЁСА»): the SA-320's landing
 * gear, wheel brakes and nose-wheel steering - pure, server-authoritative (LinerSystems ticks it; the physics on the driving
 * client and on the server read the synced mode through the same functions: decel(), steerAngle(), turnRate()).
 * <ul>
 * <li><b>Gear</b> on GREEN: the lever moves it (pos 0 down .. 1 up over GEAR_TICKS at full pressure - the door sequence of
 * LinerAnim; slower with less) only with Green over POWER_PSI and never on the ground. The gravity extension: the crank on
 * the pedestal, 3 turns (3 clicks) - the uplocks open, the gear falls down in GRAV_TICKS without the doors' sequence (they
 * stay open) and can't be retracted while the crank is out.</li>
 * <li><b>Brakes</b>: NORMAL on Green with the anti-skid; ALTERNATE on Yellow (auto on Green's loss or the anti-skid
 * switched off) with a partial anti-skid; on the ACCUMULATOR alone - no anti-skid; the parking brake on the Yellow
 * accumulator (it holds without the engines while it has pressure). Max deceleration BRK_MAX on a dry surface; the
 * surface's grip mu (ice / snow / water - the entity reads the blocks) caps it - the anti-skid keeps all of it (partial:
 * 90 %), locked wheels only lockedGrip(mu) (worse the slipperier). The triple indicator: the accumulator, L / R alternate
 * pressure.</li>
 * <li><b>Autobrake</b> ТОРМОЖ. МАЛ / СРЕД / МАКС: armed by its push button with the normal brakes; LO / MED decelerate at
 * AB_DECEL after the touch-down's ground spoilers (LO after AB_LO_DELAY), MAX on a rejected take-off (the ground spoilers'
 * RTO); the pedals (S at idle) disarm it; ТОРМОЖ. (green) lights at 80 % of its rate.</li>
 * <li><b>Brake temperature</b> per wheel (1, 2 left, 3, 4 right): heated by the braking power (HEAT x v x a x
 * sqrt(a / A_REF)), cooled toward the ambient (half-life COOL_HALF, x FAN_MUL with ВЕНТ. ТОРМ. on AC, x2 gear down in the
 * air, x0.5 in the wells). Over HOT_C «ТОРМ ГОРЯЧ» (+ the take-off config warning), over FUSE_C the fuse plug melts - that
 * tyre is flat (the wheel sags, more rolling drag) until the garage repairs it.</li>
 * <li><b>Steering</b> on YELLOW: the tiller (A / D slow) up to ±TILLER_DEG, the pedals ±PEDAL_DEG at speed; no Yellow or
 * the tow switch (БУКСИР) - none: differential braking only.</li>
 * </ul>
 */
public final class LinerGear {

    /** The gear's travel with full Green (10 s - LinerAnim's door sequence), the gravity drop; the disagreement caution's delay. */
    public static final int GEAR_TICKS = 200, GRAV_TICKS = 80, DISAGREE_TICKS = 60;
    /** Crank turns for the gravity extension. */
    public static final int CRANK_TURNS = 3;

    /** Brake modes. */
    public static final int B_NORM = 0, B_ALTN = 1, B_ACCU = 2, B_NONE = 3;
    public static final String[] MODE_KEYS = {"norm", "altn", "accu", "none"};
    /** b/t a tick: the brakes' maximum (dry, anti-skid), the autobrake LO / MED / MAX, the parking brake's hold. */
    public static final double BRK_MAX = 0.018, PARK_DECEL = 0.06;
    public static final double[] AB_DECEL = {0, 0.005, 0.008, 0.016};
    public static final String[] AB_KEYS = {"off", "lo", "med", "max"};
    /** Capability per mode (NORM / ALTN / ACCU / NONE). */
    public static final double[] CAP = {1.0, 0.9, 0.7, 0};
    /** LO starts this many ticks after the ground spoilers (MED 2 s, MAX at once). */
    public static final int AB_LO_DELAY = 80, AB_MED_DELAY = 40;
    /** Surface grip: dry, wet (water / rain), snow, ice. */
    public static final double MU_DRY = 1.0, MU_WET = 0.55, MU_SNOW = 0.35, MU_ICE = 0.12;

    /** Brake temperatures (°C): ambient, hot, the fuse plugs; the heating constant, its reference decel, the cooling. */
    public static final double T_AMB = 15, HOT_C = 300, FUSE_C = 800, HEAT = 420, A_REF = 0.009;
    /** Cooling half-life (ticks) without the fans; the fans' and the airflow's factors. */
    public static final double COOL_HALF = 3600, FAN_MUL = 4, AIR_DOWN_MUL = 2, WELL_MUL = 0.5;
    /** Extra rolling drag per flat tyre (share of the rolling friction), the sag of a wheel group (model units). */
    public static final double FLAT_DRAG = 0.4;
    public static final float FLAT_SAG = 0.05F;

    /** Steering: the tiller's / pedals' travel (deg), the speeds (b/t) it blends between, the wheelbase (blocks: nose to mains). */
    public static final double TILLER_DEG = 75, PEDAL_DEG = 6, TILLER_V = 0.25, PEDAL_V = 0.6, WHEELBASE = 7.1;
    /** The tightest taxi turn (x the type's turn rate) and differential braking's share of a normal turn. */
    public static final double TAXI_TURN = 1.6, DIFF_SHARE = 0.3, DIFF_DRAG = 0.0015;

    // ---------------------------------------------------------------- state

    /** Gear: 0 down (locked) .. 1 up (locked); the gravity extension's crank turns, out by gravity. */
    public double pos;
    public int crank;
    public boolean gravity;
    public int disagree;
    /** Brakes: the mode, the anti-skid working, the autobrake (armed mode 0..3, active, its decel lamp), the pressures. */
    public int mode = B_NONE;
    public boolean antiskid, abActive, abDecel, parkHold;
    public int abMode, abTicks;
    public double brakeL, brakeR;
    /** Brake temperatures (°C) of wheels 1..4 and the flat tyres (bit per wheel). */
    public final double[] temp = {T_AMB, T_AMB, T_AMB, T_AMB};
    public int flat;
    /** Nose-wheel steering available. */
    public boolean nws;
    /** Events: a fuse plug just melted (bits), the gear started moving / locked. */
    public int fuseEvent;

    /** One tick's inputs. */
    public static final class In {
        public boolean leverUp, ground = true, touchdown;
        public double speed, throttle, mu = MU_DRY;
        /** S at idle on the ground (the pedals); the parking brake; the switches: anti-skid, ВЕНТ. ТОРМ., БУКСИР. */
        public boolean pedal, park, antiskidSw = true, fan, tow;
        /** AC power (the fans), the ground spoilers deployed (and the RTO's moment), the steering command (-1..1). */
        public boolean ac, gndSpl, rto;
        public double steer;
        public LinerHyd hyd;
        public boolean instant;
    }

    // ---------------------------------------------------------------- the tick

    public void tick(In in) {
        fuseEvent = 0;
        LinerHyd h = in.hyd;
        // ---- the gear
        gravity = crank >= CRANK_TURNS;
        double target = in.leverUp && !in.ground && !gravity ? 1 : 0;
        if (gravity) {
            pos = in.instant ? 0 : Math.max(0, pos - 1.0 / GRAV_TICKS);
        } else if (in.instant) {
            pos = h.powered(LinerHyd.G) ? target : pos;
        } else {
            double r = h.rate(LinerHyd.MG) / GEAR_TICKS;
            pos = pos < target ? Math.min(target, pos + r) : Math.max(target, pos - r);
        }
        double wanted = in.leverUp && !in.ground ? 1 : 0;
        disagree = Math.abs(pos - wanted) > 1e-6 && (gravity || h.rate(LinerHyd.MG) <= 0) ? disagree + 1 : 0;
        // ---- the brakes' mode
        boolean g = h.powered(LinerHyd.G), y = h.powered(LinerHyd.Y);
        if (g && in.antiskidSw) {
            mode = B_NORM;
        } else if (y) {
            mode = B_ALTN;
        } else if (h.accu > LinerHyd.ACCU_PRE + 200) {
            mode = B_ACCU;
        } else {
            mode = B_NONE;
        }
        antiskid = in.antiskidSw && (mode == B_NORM || mode == B_ALTN);
        parkHold = in.park && h.parkHolds();
        nws = y && !in.tow;
        // ---- the autobrake: disarmed by the pedals, by losing the normal brakes; active after the ground spoilers
        if (abMode != 0 && (in.pedal || mode != B_NORM || !in.ground && abActive)) {
            disarm();
        }
        if (abMode != 0 && in.ground && in.gndSpl) {
            abTicks++;
            int delay = abMode == 1 ? AB_LO_DELAY : abMode == 2 ? AB_MED_DELAY : 0;
            abActive = abActive || abTicks > delay;                 // MAX at once (the RTO; after a landing too)
        } else if (!in.gndSpl) {
            abTicks = 0;
            if (in.throttle > LinerFctl.GS_RETRACT_THR) {
                abActive = false;
            }
        }
        double a = in.ground ? decel(in.pedal, in.speed, in.mu, in.park) : 0;
        abDecel = abActive && abMode > 0 && in.speed > 0.02 && a >= AB_DECEL[abMode] * 0.8;
        // ---- the alternate system's pressures (the triple indicator) and the accumulator's use
        if (parkHold) {
            brakeL = brakeR = Math.min(2000, Math.max(h.accu, h.psi[LinerHyd.Y]));
        } else if ((mode == B_ALTN || mode == B_ACCU) && a > 0) {
            double p = Math.min(3000 * a / BRK_MAX, mode == B_ACCU ? h.accu : h.psi[LinerHyd.Y]);
            brakeL = brakeR = p;
        } else {
            brakeL = brakeR = 0;
        }
        if (mode == B_ACCU && a > 0 && !parkHold) {
            h.accu = Math.max(0, h.accu - LinerHyd.ACCU_USE * a / BRK_MAX);
        }
        // ---- brake temperatures: the braking power heats, the air cools
        double heat = 0;
        if (in.ground && in.speed > 0.005 && a > 0) {
            double ab = Math.min(a, BRK_MAX * 1.2);
            heat = HEAT * in.speed * ab * Math.sqrt(ab / A_REF);
        }
        double diff = 0;
        if (in.ground && !nws && Math.abs(in.steer) > 0.1 && in.speed > 0.01 && mode != B_NONE) {
            diff = HEAT * in.speed * DIFF_DRAG * 2 * Math.abs(in.steer);    // differential braking: the inner side's wheels
        }
        double cool = Math.log(2) / COOL_HALF * (in.fan && in.ac ? FAN_MUL : 1) * (in.ground ? 1 : pos >= 1 ? WELL_MUL : AIR_DOWN_MUL);
        for (int w = 0; w < 4; w++) {
            boolean left = w < 2;
            double d = heat + (diff > 0 && (in.steer < 0) == left ? diff : 0);
            temp[w] += d - (temp[w] - T_AMB) * cool;
            if (temp[w] > FUSE_C && (flat & 1 << w) == 0) {
                flat |= 1 << w;                                     // the fuse plug melts: the tyre deflates
                fuseEvent |= 1 << w;
            }
        }
    }

    /** The autobrake off (its push buttons out - LinerSystems keeps them in step). */
    public void disarm() {
        abMode = 0;
        abActive = false;
        abDecel = false;
        abTicks = 0;
    }

    // ---------------------------------------------------------------- the physics' functions (both sides, the synced state)

    /**
     * The wheel brakes' deceleration now (b/t a tick, >= 0): the pedals (full) or the autobrake's rate - capped by the mode's
     * capability (a flat tyre takes a share) and by the grip (the anti-skid keeps mu, locked wheels lockedGrip); the parking
     * brake holding - PARK_DECEL.
     */
    public double decel(boolean pedal, double speed, double mu, boolean park) {
        if (park && parkHold) {
            return PARK_DECEL;
        }
        if (speed <= 0 || mode == B_NONE) {
            return 0;
        }
        double demand = pedal ? BRK_MAX : abActive && abMode > 0 ? AB_DECEL[abMode] : 0;
        if (demand <= 0) {
            return 0;
        }
        double cap = CAP[mode] * (1 - 0.15 * Integer.bitCount(flat));
        double grip = antiskid ? (mode == B_NORM ? mu : 0.9 * mu) : lockedGrip(mu);
        return Math.min(demand, BRK_MAX * Math.min(cap, grip));
    }

    /** Locked wheels' share of the grip: 0.75 on a dry surface, worse the slipperier (ice: ~0.44). */
    public static double lockedGrip(double mu) {
        return mu * (0.75 - 0.35 * (1 - Math.max(0, Math.min(1, mu))));
    }

    /** The extra rolling friction factor of the flat tyres. */
    public double rollingMul() {
        return 1 + FLAT_DRAG * Integer.bitCount(flat);
    }

    /** The steering's travel at a speed (b/t): the tiller below TILLER_V, the pedals over PEDAL_V, blended between. */
    public static double maxSteer(double speed) {
        double k = Math.max(0, Math.min(1, (speed - TILLER_V) / (PEDAL_V - TILLER_V)));
        return TILLER_DEG + (PEDAL_DEG - TILLER_DEG) * k;
    }

    /** The nose wheel's angle (deg) for a command -1..1 (0 without the steering). */
    public double steerAngle(double cmd, double speed) {
        return nws ? Math.max(-1, Math.min(1, cmd)) * maxSteer(speed) : 0;
    }

    /**
     * The ground turn (deg a tick) for a command at a speed: with the steering the geometry (v x tan(angle) / wheelbase),
     * at most TAXI_TURN x the type's rate; without it differential braking - DIFF_SHARE of that (no brakes: nothing).
     */
    public double turnRate(double cmd, double speed, double typeRate) {
        if (speed <= 0 || cmd == 0) {
            return 0;
        }
        double geo = Math.min(typeRate * TAXI_TURN, speed * Math.tan(Math.toRadians(maxSteer(speed))) / WHEELBASE * 57.29578);
        if (nws) {
            return Math.signum(cmd) * Math.min(1, Math.abs(cmd)) * geo;
        }
        if (mode == B_NONE) {
            return 0;
        }
        return Math.signum(cmd) * Math.min(1, Math.abs(cmd)) * geo * DIFF_SHARE;
    }

    /** The speed differential braking costs a tick (b/t) when it steers. */
    public double diffDrag(double cmd) {
        return !nws && mode != B_NONE && cmd != 0 ? DIFF_DRAG * Math.min(1, Math.abs(cmd)) : 0;
    }

    /** The gear is down and locked. */
    public boolean downLocked() {
        return pos <= 1e-6;
    }

    /** The hottest brake (°C) and its wheel (0..3). */
    public double hottest() {
        return Math.max(Math.max(temp[0], temp[1]), Math.max(temp[2], temp[3]));
    }

    public int hottestWheel() {
        int w = 0;
        for (int i = 1; i < 4; i++) {
            if (temp[i] > temp[w]) {
                w = i;
            }
        }
        return w;
    }

    public boolean hot() {
        return hottest() > HOT_C;
    }

    /** The garage: brakes cooled, new tyres. */
    public void service() {
        flat = 0;
        for (int w = 0; w < 4; w++) {
            temp[w] = T_AMB;
        }
    }

    /** The stopping distance (blocks) from v0 at a constant deceleration plus the rolling friction (the self-test's yardstick). */
    public static double stopDistance(double v0, double decel, double rolling) {
        double a = decel + rolling;
        return a <= 0 ? Double.POSITIVE_INFINITY : v0 * v0 / (2 * a);
    }
}
