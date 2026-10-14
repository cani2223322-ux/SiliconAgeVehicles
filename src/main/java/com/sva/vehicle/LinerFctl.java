package com.sva.vehicle;

/**
 * Liner wave 2 (plan-next §7, mock-ups veh_liner_2 high lift, veh_liner_3 surfaces, 7 «УПР (ВЫЧИСЛИТЕЛИ)», 13b «УПР»): the
 * SA-320's flight controls driven by the hydraulics - pure logic, the same code on the server (the spoiler and high-lift
 * decisions, the handling's authority, the law) and on every client (the surfaces' animation from the stick it sees and the
 * synced pressures - nothing per tick has to be synced for them).
 * <p>
 * <b>Actuators</b> (the A320's map, sheet 3 panel 6): ailerons G + B; spoilers 1 / 5 G, 2 / 4 Y, 3 B; elevator L B + G,
 * R Y + B; THS G + Y (slow, the trim screw); rudder G + B + Y (+ the mechanical back-up); slats G + B; flaps G + Y. A surface
 * is POWERED when one of its systems has POWER_PSI and its computer works: ELAC 1 / 2 the ailerons and the elevator, SEC 1
 * spoilers 3 / 4, SEC 2 spoiler 5, SEC 3 spoilers 1 / 2, SEC 1 / 2 the elevator's back-up; FAC 1 / 2 the yaw damper (the
 * rudder works mechanically without). Powered: it follows the command at RATE x the pressure's factor (low pressure =
 * slow). Unpowered: it DROOPS in DROOP_TICKS (~2 s) - ailerons and elevators trailing edge down 15°, spoilers flat; the
 * rudder trails; the THS stays (in the air the trim wheel turns it, TRIM_MECH).
 * <p>
 * <b>Spoilers</b>: roll on 2..5 of the DOWN-going wing (the user's choice, up to ROLL_SPL°), the speed brake on 2..4 (the
 * lever 1/2 - 20°, full - 40°), the GROUND spoilers all five at 50° - armed (the lever at ВЗВЕД) and a touch-down at idle,
 * or a rejected take-off (idle from take-off power over RTO_SPEED on the ground); they go down when the lever leaves ВЗВЕД
 * or the thrust comes up.
 * <p>
 * <b>Slats / flaps</b> follow the flap lever (the slats also the automatic slat rule) at FLAP_RATE / SLAT_RATE with both
 * of their systems, half with one, not at all with none - then after JAM_TICKS «ЗАКРЫЛКИ ЗАКЛИН» / «ПРЕДКРЫЛКИ ЗАКЛИН».
 * <p>
 * <b>Laws</b> NORMAL / ALTN / DIRECT from the computers, the ADR / IR (the ADIRS selectors and push buttons) and the
 * hydraulics (law()); the handling: rollAuthority / pitchAuthority (the powered surfaces) x lawGain (ALTN / DIRECT - no
 * protections, a more direct response).
 */
public final class LinerFctl {

    // ---------------------------------------------------------------- surfaces

    /** Surfaces: ailerons, elevator halves, rudder, THS, spoilers 1..5 left then right. */
    public static final int AIL_L = 0, AIL_R = 1, ELEV_L = 2, ELEV_R = 3, RUD = 4, THS = 5, SPL_L = 6, SPL_R = 11, N = 16;
    /** Each surface's hydraulic systems (LinerHyd.M*). */
    public static final int[] HYD;
    /** Travel (deg): ailerons / elevator TE down +, rudder TE right +, spoilers up +, THS nose up + (the rig's limits). */
    public static final float AIL_MAX = 25, ELEV_UP = 30, ELEV_DOWN = 17, RUD_MAX = 30, SPL_MAX = 50, THS_UP = 13.5F, THS_DOWN = -4;
    /** The unpowered droop (TE down) of the ailerons and the elevator, its time; spoilers fall flat in the same. */
    public static final float DROOP = 15, DROOP_TICKS = 40;
    /** Full-pressure rates (deg a tick): aileron, elevator, rudder, spoiler, THS (the trim screw); the rudder's / THS's mechanical. */
    public static final float RATE_AIL = 2.0F, RATE_ELEV = 1.6F, RATE_RUD = 1.5F, RATE_SPL = 2.5F, RATE_THS = 0.06F, RUD_MECH = 0.4F,
            TRIM_MECH = 0.025F;
    /** Spoilers: roll (on the down-going wing), the speed brake half / full, the ground spoilers. */
    public static final float ROLL_SPL = 35, SPD_HALF = 20, SPD_FULL = 40, GND_SPL = 50;
    /** The rudder's travel limiter: full up to RUD_V0 b/t, RUD_MIN at RUD_V1 and over. */
    public static final float RUD_V0 = 1.0F, RUD_V1 = 2.0F, RUD_MIN = 10;

    static {
        int g = LinerHyd.MG, b = LinerHyd.MB, y = LinerHyd.MY;
        int[] spl = {g, y, b, y, g};
        HYD = new int[N];
        HYD[AIL_L] = HYD[AIL_R] = g | b;
        HYD[ELEV_L] = b | g;
        HYD[ELEV_R] = y | b;
        HYD[RUD] = g | b | y;
        HYD[THS] = g | y;
        for (int i = 0; i < 5; i++) {
            HYD[SPL_L + i] = HYD[SPL_R + i] = spl[i];
        }
    }

    /** Slats / flaps: their systems, the full travel's time (flaps 0 -> FULL ~20 s, slats ~8 s with both). */
    public static final int HYD_SLATS = LinerHyd.MG | LinerHyd.MB, HYD_FLAPS = LinerHyd.MG | LinerHyd.MY;
    public static final double FLAP_RATE = 4.0 / 400, SLAT_RATE = 1.0 / 160;
    /** A lever disagreeing with a device that can't move for this long: jammed («ЗАКЛИН»). */
    public static final int JAM_TICKS = 60;
    /** The rejected take-off: over this ground speed (b/t, 54 km/h) after take-off power (throttle >= TO_THR), to idle. */
    public static final double RTO_SPEED = 0.75, TO_THR = 0.6, IDLE_THR = 0.05, GS_RETRACT_THR = 0.3;

    // ---------------------------------------------------------------- computers and laws

    /** Computers: ELAC 1 / 2, SEC 1 / 2 / 3, FAC 1 / 2 (bits of `comp`). */
    public static final int C_ELAC1 = 1, C_ELAC2 = 2, C_SEC1 = 4, C_SEC2 = 8, C_SEC3 = 16, C_FAC1 = 32, C_FAC2 = 64, COMPUTERS = 7;
    public static final String[] COMP_NAMES = {"ELAC 1", "ELAC 2", "SEC 1", "SEC 2", "SEC 3", "FAC 1", "FAC 2"};
    /** Laws (wave 7: + the mechanical back-up - the rudder and the THS by the trim wheel only). */
    public static final int NORMAL = 0, ALTN = 1, DIRECT = 2, MECH = 3;
    public static final String[] LAW_KEYS = {"normal", "altn", "direct", "mech"};
    /**
     * Wave 7, the handling by the law: ALTN's low-speed stability (b/t of sink a tick per b/t under alpha-prot, the stick
     * released), DIRECT's / MECH's speed stability (b/t of climb per b/t over the trimmed speed - no auto-trim), the trim
     * wheel's step (b/t of the trimmed speed a click: nose up = slower), the normal law's alpha protection (the stick's up
     * command fades from alpha-prot to alpha-max).
     */
    public static final double LOW_SPD_K = 0.25, SPEED_STAB = 0.12, TRIM_STEP = 0.04;

    // ---------------------------------------------------------------- state

    /** Surface angles (deg, conventions above) and the previous tick's (the client's lerp). */
    public final float[] angle = new float[N], prev = new float[N];
    /** Powered surfaces now (bit per surface). */
    public int powered;
    /** Working computers (C_*), the law. */
    public int comp, law = DIRECT;
    /**
     * Wave 7: ALTN 2 (ALTN with one IR / ADR or less, or the ADRs disagreeing: no roll protections at all, the roll direct);
     * the speed the THS is trimmed for (b/t - NORMAL / ALTN follow the speed flown: the auto-trim; DIRECT / MECH keep it, the
     * trim wheel moves it); the trim wheel's THS offset (deg, + nose up) for the pose and the SD.
     */
    public boolean altn2;
    public double trimSpeed;
    public float manTrim;
    /** High lift: the flaps' position (setting units 0..4, continuous) and the slats' (0..1); jam timers / flags. */
    public double flapPos, slatPos;
    public int flapStuck, slatStuck;
    public boolean flapJam, slatJam;
    /** Ground spoilers deployed; take-off power seen on this ground run (the RTO's condition); the RTO itself. */
    public boolean gndSpl, toPower, rto;
    public boolean inited;

    /** One tick's inputs. */
    public static final class In {
        /** Stick and pedals -1..1 (roll + = right, pitch + = nose up, yaw + = right). */
        public float roll, pitch, yaw;
        /** The levers: speed brake (0 ВЗВЕД, 1 УБР, 2 1/2, 3 ПОЛН), flaps (0..4); the automatic slats out. */
        public int spdBrk = 1, flaps;
        public boolean slatsAuto;
        public boolean ground = true, touchdown;
        public double throttle, speed;
        /** The THS's wanted angle (deg nose up: the auto-trim). */
        public float trim;
        /** Push buttons of the computers in (ELAC1, ELAC2, SEC1, SEC2, SEC3, FAC1, FAC2) and their buses (ESS / AC). */
        public boolean[] pb = {true, true, true, true, true, true, true};
        public boolean ess, ac;
        /** ADR / IR working (count 0..3), the gear down (ALTN -> DIRECT with the gear down); wave 7: the ADRs disagreeing. */
        public int adr = 3, ir = 3;
        public boolean adrDisagree;
        public boolean gearDown = true;
        /** The hydraulics' state. */
        public LinerHyd hyd;
        /** The server: the spoiler / high-lift decisions; a client mirror: only the surfaces (the rest is synced). */
        public boolean server = true;
        public boolean instant;
    }

    // ---------------------------------------------------------------- the tick

    public void tick(In in) {
        System.arraycopy(angle, 0, prev, 0, N);
        LinerHyd h = in.hyd;
        int hm = h.mask();
        // ---- computers: ELAC 1 / SEC 1 / FAC 1 on the essential bus, the others on AC
        int c = 0;
        boolean[] bus = {in.ess, in.ac, in.ess, in.ac, in.ac, in.ess, in.ac};
        for (int i = 0; i < COMPUTERS; i++) {
            if (in.pb[i] && bus[i]) {
                c |= 1 << i;
            }
        }
        comp = c;
        // wave 7: the law from the real state - the ADIRS' counts and their disagreement, the computers, the hydraulics, the
        // elevators' pressure (none on either half: the mechanical back-up)
        boolean elevHyd = (HYD[ELEV_L] & hm) != 0 || (HYD[ELEV_R] & hm) != 0;
        law = compute(c, in.adr, in.ir, h.lost(), in.gearDown && !in.ground, in.adrDisagree, elevHyd || in.ground);   // MECH: in flight
        altn2 = law == ALTN && (in.adr <= 1 || in.ir <= 1 || in.adrDisagree);
        // ---- the spoilers' modes (the server decides; the clients get gndSpl synced)
        if (in.server) {
            spoilerLogic(in);
        }
        // ---- each surface
        boolean elac = (c & (C_ELAC1 | C_ELAC2)) != 0, secPitch = (c & (C_SEC1 | C_SEC2)) != 0;
        int pw = 0;
        float rudLim = rudderLimit(in.speed);
        for (int s = 0; s < N; s++) {
            boolean hyd = (HYD[s] & hm) != 0;
            boolean ctl;
            if (s == AIL_L || s == AIL_R) {
                ctl = elac;
            } else if (s == ELEV_L || s == ELEV_R || s == THS) {
                ctl = elac || secPitch;
            } else if (s == RUD) {
                ctl = true;                                         // mechanical: the FACs only damp and limit it
            } else {
                int i = (s - SPL_L) % 5;
                ctl = (c & (i == 0 || i == 1 ? C_SEC3 : i == 4 ? C_SEC2 : C_SEC1)) != 0;
            }
            boolean on = hyd && ctl;
            if (on) {
                pw |= 1 << s;
            }
            float want, rate;
            if (on) {
                want = command(s, in, rudLim);
                rate = baseRate(s) * (float) h.rate(HYD[s]);
            } else {
                want = droop(s, in);
                rate = droopRate(s, in);
            }
            angle[s] = in.instant || !inited ? want : approach(angle[s], want, rate);
        }
        powered = pw;
        if (!inited) {
            System.arraycopy(angle, 0, prev, 0, N);
        }
        inited = true;
        // ---- slats and flaps (the server; clients get the positions synced)
        if (in.server) {
            highLift(in);
        }
    }

    /** The commanded angle of a powered surface. */
    float command(int s, In in, float rudLim) {
        switch (s) {
            case AIL_L:
                return clamp(in.roll, -1, 1) * AIL_MAX;                 // a right roll: the left one down
            case AIL_R:
                return -clamp(in.roll, -1, 1) * AIL_MAX;
            case ELEV_L:
            case ELEV_R:
                return in.pitch >= 0 ? -in.pitch * ELEV_UP : -in.pitch * ELEV_DOWN;
            case RUD:
                return clamp(in.yaw, -1, 1) * rudLim;
            case THS:
                return clamp(in.trim, THS_DOWN, THS_UP);
            default: {
                int i = (s - SPL_L) % 5 + 1;
                boolean right = s >= SPL_R;
                float a = 0;
                if (gndSpl) {
                    a = GND_SPL;
                } else {
                    if (i >= 2 && i <= 4) {
                        a = in.spdBrk == 2 ? SPD_HALF : in.spdBrk >= 3 ? SPD_FULL : 0;
                    }
                    if (i >= 2) {
                        float down = right ? in.roll : -in.roll;        // the right wing goes down in a right roll
                        a = Math.max(a, Math.max(0, down) * ROLL_SPL);
                    }
                }
                return Math.min(SPL_MAX, a);
            }
        }
    }

    /** Where an unpowered surface goes: ailerons / elevator droop, spoilers flat, the rudder trails, the THS stays. */
    float droop(int s, In in) {
        switch (s) {
            case AIL_L:
            case AIL_R:
            case ELEV_L:
            case ELEV_R:
                return DROOP;
            case RUD:
                return clamp(in.yaw, -1, 1) * rudderLimit(in.speed);     // the mechanical back-up
            case THS:
                return in.ground ? angle[THS] : clamp(in.trim, THS_DOWN, THS_UP);   // the trim wheel (in the air)
            default:
                return 0;
        }
    }

    float droopRate(int s, In in) {
        if (s == RUD) {
            return RUD_MECH;
        }
        if (s == THS) {
            return TRIM_MECH;
        }
        return (s >= SPL_L ? SPL_MAX * 0.5F : DROOP) / DROOP_TICKS;
    }

    static float baseRate(int s) {
        switch (s) {
            case AIL_L:
            case AIL_R:
                return RATE_AIL;
            case ELEV_L:
            case ELEV_R:
                return RATE_ELEV;
            case RUD:
                return RATE_RUD;
            case THS:
                return RATE_THS;
            default:
                return RATE_SPL;
        }
    }

    /** The rudder's travel at a speed (b/t): the FAC's limiter. */
    public static float rudderLimit(double speed) {
        double k = clamp((speed - RUD_V0) / (RUD_V1 - RUD_V0), 0, 1);
        return (float) (RUD_MAX - (RUD_MAX - RUD_MIN) * k);
    }

    /**
     * The ground spoilers (the server): armed = the lever at ВЗВЕД; out at a touch-down at idle, or a rejected take-off (on
     * the ground, take-off power seen, now idle over RTO_SPEED); in when the lever leaves ВЗВЕД or the thrust comes up.
     */
    void spoilerLogic(In in) {
        boolean armed = in.spdBrk == 0;
        rto = false;
        if (!in.ground) {
            toPower = false;
        } else if (in.throttle >= TO_THR) {
            toPower = true;
        }
        if (in.ground && in.speed < 0.05) {
            toPower = false;
        }
        if (armed && in.ground && in.throttle <= IDLE_THR) {
            if (in.touchdown) {
                gndSpl = true;
            } else if (toPower && in.speed >= RTO_SPEED && !gndSpl) {
                gndSpl = true;
                rto = true;
            }
        }
        if (!armed || in.throttle > GS_RETRACT_THR || !in.ground) {
            gndSpl = false;
        }
    }

    /** Slats / flaps toward their targets at their hydraulic rate; the jam flags. */
    void highLift(In in) {
        LinerHyd h = in.hyd;
        double fT = Math.max(0, Math.min(4, in.flaps)), sT = in.flaps >= 1 || in.slatsAuto ? 1 : 0;
        double fr = FLAP_RATE * share(h, HYD_FLAPS), sr = SLAT_RATE * share(h, HYD_SLATS);
        if (in.instant) {
            flapPos = fT;
            slatPos = sT;
        } else {
            flapPos = approach(flapPos, fT, fr);
            slatPos = approach(slatPos, sT, sr);
        }
        flapStuck = Math.abs(flapPos - fT) > 1e-6 && fr <= 0 ? flapStuck + 1 : 0;
        slatStuck = Math.abs(slatPos - sT) > 1e-6 && sr <= 0 ? slatStuck + 1 : 0;
        flapJam = flapStuck >= JAM_TICKS;
        slatJam = slatStuck >= JAM_TICKS;
    }

    /** A two-system device's speed: 1 with both, 0.5 with one, 0 with none. */
    static double share(LinerHyd h, int bits) {
        int n = 0;
        for (int s = 0; s < LinerHyd.SYS; s++) {
            if ((bits & 1 << s) != 0 && h.powered(s)) {
                n++;
            }
        }
        return n >= 2 ? 1 : n == 1 ? 0.5 : 0;
    }

    /**
     * The law: DIRECT with no ADR or no IR left, or neither an ELAC nor SEC 1 / 2 for the pitch, or ALTN with the gear down
     * in flight (the A320's reversion for the landing); ALTN without both ELACs, with one ADR / IR left, or two hydraulic
     * systems lost; else NORMAL.
     */
    public static int law(int comp, int adr, int ir, int hydLost, boolean gearDownAir) {
        boolean elac = (comp & (C_ELAC1 | C_ELAC2)) != 0, sec = (comp & (C_SEC1 | C_SEC2)) != 0;
        if (adr <= 0 || ir <= 0 || !elac && !sec) {
            return DIRECT;
        }
        if (!elac || adr < 2 || ir < 2 || hydLost >= 2) {
            return gearDownAir ? DIRECT : ALTN;
        }
        return NORMAL;
    }

    /**
     * Wave 7: the law from the whole state - law() (the computers, the ADR / IR counts, the hydraulics, the gear) with the
     * ADRs disagreeing (NORMAL -> ALTN) and the elevators without any pressure in flight (the mechanical back-up: MECH).
     */
    public static int compute(int comp, int adr, int ir, int hydLost, boolean gearDownAir, boolean adrDisagree, boolean elevHyd) {
        if (!elevHyd) {
            return MECH;
        }
        int l = law(comp, adr, ir, hydLost, gearDownAir);
        if (l == NORMAL && adrDisagree) {
            l = gearDownAir ? DIRECT : ALTN;
        }
        return l;
    }

    /** The law has no protections and no auto-trim (DIRECT / MECH). */
    public boolean manualTrim() {
        return law == DIRECT || law == MECH;
    }

    /**
     * Wave 7: the vertical speed's law terms for the physics (b/t, added to the stick's target): NORMAL - none (the path
     * holds, the alpha protection is in alphaStick); ALTN - the low-speed stability (the stick released under alpha-prot: the
     * nose drops); DIRECT / MECH - the speed stability (no auto-trim: faster than trimmed climbs, slower sinks).
     */
    public double lawVy(double speed, double aProt, double pitchStick) {
        if (law == ALTN) {
            return Math.abs(pitchStick) < 0.05 && speed < aProt ? -(aProt - speed) * LOW_SPD_K : 0;
        }
        if (manualTrim() && trimSpeed > 0) {
            return (speed - trimSpeed) * SPEED_STAB;
        }
        return 0;
    }

    /** The normal law's alpha protection: the up stick's share left between alpha-max (0) and alpha-prot (1). */
    public static double alphaStick(double stick, double speed, double aProt, double aMax) {
        if (stick <= 0 || speed >= aProt || aProt <= aMax) {
            return stick;
        }
        return stick * clamp((speed - aMax) / (aProt - aMax), 0, 1);
    }

    /**
     * Wave 7, the server each tick: the auto-trim (NORMAL / ALTN: the trimmed speed follows the airspeed, no wheel offset);
     * DIRECT / MECH keep the speed they were trimmed at (the trim wheel moves it).
     */
    public void trimTick(double airspeed, boolean ground) {
        if (!manualTrim() || ground) {
            trimSpeed = Math.max(0, airspeed);
            manTrim = 0;
        } else if (trimSpeed <= 0) {
            trimSpeed = Math.max(0, airspeed);
        }
    }

    /** A trim wheel click: nose up (true) slows the trimmed speed by TRIM_STEP (the THS a little up). DIRECT / MECH only. @return moved */
    public boolean trimClick(boolean noseUp) {
        if (!manualTrim()) {
            return false;
        }
        trimSpeed = Math.max(0.2, trimSpeed + (noseUp ? -TRIM_STEP : TRIM_STEP));
        manTrim = clamp(manTrim + (noseUp ? 0.25F : -0.25F), THS_DOWN, THS_UP);
        return true;
    }

    // ---------------------------------------------------------------- the handling

    /** The roll authority 0.2..1: both ailerons 0.7, the roll spoilers 0.3; the rudder alone (mechanical) 0.2. */
    public double rollAuthority() {
        double a = 0;
        for (int s : new int[] {AIL_L, AIL_R}) {
            a += on(s) ? 0.35 : 0;
        }
        for (int i = 1; i < 5; i++) {
            a += on(SPL_L + i) ? 0.0375 : 0;
            a += on(SPL_R + i) ? 0.0375 : 0;
        }
        return Math.max(0.2, Math.min(1, a));
    }

    /** The pitch authority 0.15..1: each elevator half 0.45, the THS powered 0.1 (by the trim wheel alone 0.15 in all). */
    public double pitchAuthority() {
        double a = (on(ELEV_L) ? 0.45 : 0) + (on(ELEV_R) ? 0.45 : 0) + (on(THS) ? 0.1 : 0);
        return Math.max(0.15, Math.min(1, a));
    }

    /** ALTN / DIRECT: no protections and a more direct (livelier) response; MECH: the trim wheel and the rudder only. */
    public double lawGain() {
        return law == NORMAL ? 1 : law == ALTN ? 1.08 : law == DIRECT ? 1.15 : 0.6;
    }

    public boolean on(int s) {
        return (powered & 1 << s) != 0;
    }

    /** The speed brake works (its spoilers 2..4 of both wings: at least two of the six powered). */
    public boolean speedBrakeWorks() {
        int n = 0;
        for (int i = 1; i <= 3; i++) {
            n += (on(SPL_L + i) ? 1 : 0) + (on(SPL_R + i) ? 1 : 0);
        }
        return n >= 2;
    }

    /** The ground spoilers dump the lift (deployed and at least four of the ten panels powered). */
    public boolean groundSpoilersWork() {
        if (!gndSpl) {
            return false;
        }
        int n = 0;
        for (int i = 0; i < 10; i++) {
            n += on(SPL_L + i) ? 1 : 0;
        }
        return n >= 4;
    }

    /** The flaps' setting the aerodynamics see (the whole settings the flaps have reached). */
    public int flapSetting() {
        return (int) Math.floor(flapPos + 0.02);
    }

    /** The slats are out (over half way). */
    public boolean slatsOut() {
        return slatPos > 0.5;
    }

    /** A surface lerped between ticks (the client's pose). */
    public float at(int s, float pt) {
        return prev[s] + (angle[s] - prev[s]) * pt;
    }

    static float approach(float v, float t, float by) {
        return v < t ? Math.min(t, v + by) : Math.max(t, v - by);
    }

    static double approach(double v, double t, double by) {
        return v < t ? Math.min(t, v + by) : Math.max(t, v - by);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
