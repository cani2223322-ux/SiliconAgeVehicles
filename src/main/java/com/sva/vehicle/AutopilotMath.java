package com.sva.vehicle;

/**
 * Aircraft stage C (★17 the glare-shield autopilot panel, ★19 the HSI; mock-up docs/mockups/veh_air2_5_cockpit.png): the
 * autopilot's selectable modes and the HSI's geometry - pure control laws and maths, simulated by the dedicated server's
 * self-test (debug/SelfTestAirCSVA). Speeds in blocks/tick, heights in world Y, headings as a compass (0 = north = -Z,
 * 90 = east = +X; CruiseMath.heading), vertical speeds shown in blocks/s («м/с»).
 * <p>
 * The autopilot of wave 1 (CruiseMath: key O holds the heading and the height it was switched on at) is the «АП» master;
 * the modes under it, each with its own setting knob on the panel (the «Панель автопилота» screen: buttons and +/-):
 * <ul>
 * <li>КУРС - turn to the set heading and hold it (CruiseMath.headingStep); off: no turning (wings level);</li>
 * <li>ВЫС - hold the set height (CruiseMath.wantedVy); with ВЕРТ too: climb / descend to it at the set vertical speed and
 * hold it there (a real autopilot's «VS to the selected altitude»);</li>
 * <li>ВЕРТ alone - hold the set vertical speed; neither: level flight as with no keys;</li>
 * <li>СКОР - hold the set airspeed with the throttle (a PI law on the throttle; it never lights the jet's afterburner -
 * the throttle stays under EntityVehicleSVA.AFTERBURNER_THROTTLE); off: the throttle stays where the pilot left it;</li>
 * <li>ЗАХВ - runway capture: the nearest strip of «Взлётно-посадочная полоса» blocks within RunwayMath.SCAN blocks
 * (RunwayMath) - the heading is steered onto its centre line in the direction nearest the current heading (an intercept
 * of up to MAX_INTERCEPT degrees, CAP_GAIN per block off the line); once ALIGNED it descends on a GLIDE slope to the
 * threshold no faster than the set vertical speed (or DESCENT when ВЕРТ is not set downward), and under OFF_HEIGHT blocks
 * over the strip the autopilot lets go (CruiseMath.OFF_LANDING): the pilot lands. No runway near: the mode waits.</li>
 * </ul>
 * The O key keeps its old meaning: «АП» on with КУРС + ВЫС at the current heading and height (DEFAULT).
 * <p>
 * Stage D1 (★1, mock-up docs/mockups/veh_air3_1_airfield.png): with a PAPI row (AirfieldMath.findPapi) serving the
 * captured end, ЗАХВ goes on in phases - the panel's ЗАХВ window shows them: ВПП (PH_RWY: onto the centre line, the height
 * held; over the PAPI slope it comes down to it no faster than the stage C descent, under it it stays level), ГЛИС
 * (PH_GLIDE: entered in the «2 white + 2 red» sector - it holds the PAPI slope: the slope's own sink at this speed plus
 * GLIDE_GAIN per block off it - more white: steeper, more red: shallower) and ВЫРАВН. (PH_FLARE: under FLARE_START blocks
 * over the strip and within FLARE_LEAD of the threshold - the throttle closed to FLARE_THR but never under FLARE_MARGIN x the stall; still on the slope, its
 * sink eased over the last FLARE_FULL blocks to FLARE_SINK), until the wheels touch: the autopilot lets go on the ground (CruiseMath.OFF_GROUND). Without a PAPI it works
 * as in stage C.
 */
public final class AutopilotMath {

    /** Mode bits (the Input packet with IN_CRUISE, the synced panel - EntityVehicleSVA.DW_AP). */
    public static final int M_HDG = 1, M_ALT = 2, M_SPD = 4, M_VS = 8, M_CAP = 16, M_ALL = 31;
    /** The O key: КУРС + ВЫС at the current values. */
    public static final int DEFAULT = M_HDG | M_ALT;
    /** The panel's groups left -> right: АП, КУРС, ВЫС, СКОР, ВЕРТ, ЗАХВ (0 = the master «АП»). */
    public static final int G_AP = 0, G_HDG = 1, G_ALT = 2, G_SPD = 3, G_VS = 4, G_CAP = 5, GROUPS = 6;
    /** Lang key part per group: sva.ap.group.KEY. */
    public static final String[] GROUP_KEYS = {"ap", "hdg", "alt", "spd", "vs", "cap"};

    /** Knob steps: heading 5 deg, height 5 blocks, speed 5 km/h, vertical speed 0.5 blocks/s. */
    public static final int HDG_STEP = 5, ALT_STEP = 5, SPD_STEP = 5;
    public static final double VS_STEP = 0.5;
    /** Set value limits: height 1..FlightMath.CEILING, speed MIN_KMH..the type's top, vertical speed +-VS_MAX blocks/s. */
    public static final int MIN_KMH = 20;
    public static final double VS_MAX = 10;

    /** СКОР: throttle change a tick per b/t of speed error (P on the error's change, I on the error) and its cap. */
    public static final double SPD_KP = 3.0, SPD_KI = 0.08, THR_RATE = 0.03;
    /** СКОР never pushes the throttle into the jet's afterburner range. */
    public static final double SPD_MAX_THROTTLE = 0.89;

    /** ЗАХВ: heading off the centre line per block (deg), the largest intercept angle, the ALIGNED limits. */
    public static final double CAP_GAIN = 4, MAX_INTERCEPT = 35, ALIGN_BLOCKS = 1.5, ALIGN_DEG = 10;
    /** ЗАХВ: the glide slope (blocks down per block along, ~4.6 deg), the height over the threshold it aims at. */
    public static final double GLIDE = 0.08, FLARE_H = 1.5;
    /** ЗАХВ: the descent when ВЕРТ is not set downward (blocks/tick: 2 blocks/s), the height it lets go at. */
    public static final double DESCENT = 0.1, OFF_HEIGHT = 4;
    /**
     * ЗАХВ: the descent allowed, as a multiple of what the glide slope needs at the speed flown - twice: captured high
     * (the runway found late, close in) it still gets down onto the slope before the threshold.
     */
    public static final double GLIDE_MARGIN = 2.0;
    /** ЗАХВ: how far before the threshold it captures (blocks along the strip's axis). */
    public static final double CAP_RANGE = 160;

    /** Stage D1, ЗАХВ's phases with a PAPI: ВПП (the centre line), ГЛИС (the PAPI slope), ВЫРАВН. (the flare). */
    public static final int PH_RWY = 0, PH_GLIDE = 1, PH_FLARE = 2;
    /** Lang key part per phase: sva.ap.phase.KEY. */
    public static final String[] PHASE_KEYS = {"rwy", "gs", "flare"};
    /**
     * ВПП with a PAPI ahead, over its slope: down at up to this per block flown (~12 deg) - the strip is found only
     * RunwayMath.SCAN blocks out, where a 3 deg slope is ~5 blocks up; caught high, it must still reach the slope in time.
     */
    public static final double PAPI_DOWN = 0.21;
    /** ВПП with a PAPI ahead: vertical speed per block over the slope (b/t; firmer than ВЫС's ALT_GAIN - into the sector quickly). */
    public static final double CAPTURE_GAIN = 0.15;
    /** ГЛИС: vertical speed per block off the slope (b/t), the most it climbs back toward it (b/t). */
    public static final double GLIDE_GAIN = 0.06, GLIDE_UP = 0.03;
    /**
     * ВЫРАВН.: starts this high over the strip (blocks; the throttle closes from here), it still follows the slope but the
     * sink eases over the last FLARE_FULL blocks down to FLARE_SINK at the touch (b/t: 0.5 blocks/s).
     */
    public static final double FLARE_START = 6, FLARE_FULL = 2, FLARE_SINK = 0.025;
    /**
     * ВЫРАВН.: and no further out than this before the threshold (blocks) - on a 3 deg slope 6 blocks up is ~100 blocks
     * out, the whole final would be the flare; the slope crosses the threshold ~1.5 - 2 blocks up (the PAPI ~15 in).
     */
    public static final double FLARE_LEAD = 20;
    /** ВЫРАВН.: the throttle it closes to, never under this x the stall speed (then it opens again). */
    public static final double FLARE_THR = 0.2, FLARE_MARGIN = 1.2;

    /** HSI: blocks a course deviation dot stands for, and the dots each side. */
    public static final double DOT_BLOCKS = 4, DOTS = 2;

    private AutopilotMath() {
    }

    // ---------------------------------------------------------------- set values

    /** A compass heading 0..359. */
    public static int heading(double deg) {
        int h = (int) Math.round(deg) % 360;
        return h < 0 ? h + 360 : h;
    }

    /** The set heading after `steps` knob notches (HDG_STEP each, snapped to the step, wraps round). */
    public static int adjustHeading(int hdg, int steps) {
        int snapped = Math.round(hdg / (float) HDG_STEP) * HDG_STEP;
        return heading(snapped + steps * HDG_STEP);
    }

    /** The set height after `steps` notches (ALT_STEP, snapped), within 1..ceiling. */
    public static int adjustAlt(int alt, int steps, int ceiling) {
        int snapped = Math.round(alt / (float) ALT_STEP) * ALT_STEP;
        return Math.max(1, Math.min(ceiling, snapped + steps * ALT_STEP));
    }

    /** The set speed (km/h) after `steps` notches (SPD_STEP, snapped), within MIN_KMH..top. */
    public static int adjustKmh(int kmh, int steps, int topKmh) {
        int snapped = Math.round(kmh / (float) SPD_STEP) * SPD_STEP;
        return Math.max(MIN_KMH, Math.min(Math.max(MIN_KMH, topKmh), snapped + steps * SPD_STEP));
    }

    /** The set vertical speed (blocks/s) after `steps` notches (VS_STEP, snapped), within +-VS_MAX. */
    public static double adjustVs(double vs, int steps) {
        double snapped = Math.round(vs / VS_STEP) * VS_STEP;
        return VehicleMath.clamp(snapped + steps * VS_STEP, -VS_MAX, VS_MAX);
    }

    /** A mode bit toggled (ЗАХВ, КУРС...); G_AP is not a mode bit (0). */
    public static int bit(int group) {
        switch (group) {
            case G_HDG:
                return M_HDG;
            case G_ALT:
                return M_ALT;
            case G_SPD:
                return M_SPD;
            case G_VS:
                return M_VS;
            case G_CAP:
                return M_CAP;
            default:
                return 0;
        }
    }

    // ---------------------------------------------------------------- laws

    /**
     * The vertical speed the modes want (blocks/tick), NaN = no vertical mode (level flight as without keys). ВЫС alone:
     * CruiseMath.wantedVy; ВЕРТ alone: the set vertical speed; both: toward the height no faster than |set|.
     * vs: the set vertical speed in blocks/tick; climb / dive: what the airspeed allows.
     */
    public static double wantedVy(int modes, double y, double alt, double vs, double climb, double dive) {
        boolean a = (modes & M_ALT) != 0, v = (modes & M_VS) != 0;
        if (a) {
            double w = CruiseMath.wantedVy(alt, y, climb, dive);
            if (v && Math.abs(vs) > 1e-6) {
                w = VehicleMath.clamp(w, -Math.abs(vs), Math.abs(vs));
            }
            return w;
        }
        if (v) {
            return VehicleMath.clamp(vs, -dive, Math.max(0, climb));
        }
        return Double.NaN;
    }

    /**
     * СКОР, one tick: the throttle after the PI step toward the set speed (b/t) - P on this tick's change of the error
     * (speed - prevSpeed: damps the overshoot), I on the error itself, at most THR_RATE a tick, within 0..max.
     */
    public static double speedThrottle(double thr, double speed, double prevSpeed, double set, double max) {
        double err = set - speed, dErr = -(speed - prevSpeed);
        double step = VehicleMath.clamp(SPD_KI * err + SPD_KP * dErr, -THR_RATE, THR_RATE);
        return VehicleMath.clamp(thr + step, 0, max);
    }

    /** ЗАХВ's guidance this tick (capture()). */
    public static final class Capture {
        /** The runway's course flown (compass deg), the wanted heading (compass deg). */
        public double course, heading;
        /** Off the centre line (blocks, + = right of it looking along the course); along the strip from the threshold (+ = past it). */
        public double cross, along;
        /** On the line and its heading: descending on the glide slope. */
        public boolean aligned;
        /** The wanted vertical speed (b/t) while aligned, else NaN (the other modes hold the height). */
        public double vy = Double.NaN;
        /** Low over the strip: the autopilot lets go (OFF_LANDING). */
        public boolean done;
        /** Past the far end or too far out: nothing to capture. */
        public boolean out;
        /** Stage D1: the phase (PH_RWY / PH_GLIDE / PH_FLARE), the PAPI row flown by (null: none), its white units (-1: not seen). */
        public int phase;
        public AirfieldMath.Papi papi;
        public int whites = -1;
        /** Stage D1: the viewing angle of the PAPI (deg; NaN without one). */
        public double papiAngle = Double.NaN;
    }

    /** The runway's course (compass deg) flown in direction dir (+1 = toward +X / +Z). */
    public static double course(RunwayMath.Runway r, int dir) {
        return r.alongX ? (dir > 0 ? 90 : 270) : (dir > 0 ? 180 : 0);
    }

    /** The direction along the strip whose course is nearer the heading (+1 / -1). */
    public static int direction(RunwayMath.Runway r, double heading) {
        double a = Math.abs(CruiseMath.wrap(course(r, 1) - heading)), b = Math.abs(CruiseMath.wrap(course(r, -1) - heading));
        return a <= b ? 1 : -1;
    }

    /**
     * ЗАХВ guidance for an aircraft at (x, y, z) heading `heading` (compass) flying `speed` b/t over runway r: the course
     * nearest the heading, the intercept heading onto its centre line, aligned / the glide slope's vertical speed (down no
     * faster than `descent` b/t - a positive number - or what the slope needs at this speed, x GLIDE_MARGIN, whichever is
     * more: a fast jet would otherwise arrive high), let go under OFF_HEIGHT over the strip.
     */
    public static Capture capture(RunwayMath.Runway r, double x, double y, double z, double heading, double descent, double speed) {
        return capture(r, x, y, z, heading, descent, speed, PH_RWY);
    }

    /**
     * Stage D1: ЗАХВ's guidance in phase `phase` (last tick's; the result's `phase` is this tick's - the caller keeps it).
     * Without a PAPI serving the captured end: stage C's guidance, PH_RWY.
     */
    public static Capture capture(RunwayMath.Runway r, double x, double y, double z, double heading, double descent, double speed, int phase) {
        Capture c = new Capture();
        int dir = direction(r, heading);
        c.course = course(r, dir);
        double a = r.alongX ? x : z, k = r.alongX ? z : x;
        double len = r.a1 - r.a0;
        c.along = dir > 0 ? a - r.a0 : r.a1 - a;
        // right of the course: heading +90 (compass); along +X (90) right is +Z, along +Z (180) right is -X
        double off = k - r.c;
        c.cross = r.alongX ? off * dir : -off * dir;
        c.out = c.along > len || c.along < -CAP_RANGE;
        c.heading = c.course - VehicleMath.clamp(CAP_GAIN * c.cross, -MAX_INTERCEPT, MAX_INTERCEPT);
        c.heading = (c.heading % 360 + 360) % 360;
        c.aligned = !c.out && Math.abs(c.cross) < ALIGN_BLOCKS && Math.abs(CruiseMath.wrap(heading - c.course)) < ALIGN_DEG;
        AirfieldMath.Papi p = c.out ? null : r.papi(dir);
        double down = Math.max(Math.abs(descent), GLIDE * Math.max(0, speed) * GLIDE_MARGIN);
        if (p == null) {
            if (c.aligned) {
                double path = r.y + FLARE_H + Math.max(0, -c.along) * GLIDE;
                c.vy = VehicleMath.clamp(CruiseMath.ALT_GAIN * (path - y), -down, 0);
                c.done = y - r.y < OFF_HEIGHT;
            }
            return c;
        }
        // stage D1: the PAPI's slope
        c.papi = p;
        boolean ahead = p.ahead(r, x, z);
        c.papiAngle = p.angle(r, x, y, z);
        c.whites = ahead ? AirfieldMath.whites(c.papiAngle, p.nominal) : -1;
        double h = y - r.y, sink = Math.max(0, speed) * Math.tan(Math.toRadians(p.nominal));
        int ph = phase < PH_RWY || phase > PH_FLARE ? PH_RWY : phase;
        if (ph == PH_RWY && c.aligned && c.whites == 2) {
            ph = PH_GLIDE;                                   // in the «2 white + 2 red» sector: on the slope
        }
        if (ph == PH_GLIDE && h < FLARE_START && c.along > -FLARE_LEAD) {
            ph = PH_FLARE;
        }
        c.phase = ph;
        if (ph == PH_GLIDE || ph == PH_FLARE) {
            // past the row the slope has reached its lens height: on down to the strip from there
            double path = ahead ? p.slopeY(r, x, z) : p.y;
            c.vy = VehicleMath.clamp(-sink + GLIDE_GAIN * (path - y), -(2 * sink + 0.02), GLIDE_UP);
            if (ph == PH_FLARE) {
                double ease = VehicleMath.clamp(h / FLARE_FULL, 0, 1);
                c.vy = Math.max(c.vy, -(FLARE_SINK + Math.max(0, sink - FLARE_SINK) * ease));
            }
        } else if (c.aligned) {
            // ВПП: over the slope come down to it (no faster than stage C's descent; the slope's own sink added, else it
            // would trail the moving slope by sink / ALT_GAIN and never reach the «2 + 2» sector), under it stay level
            double path = ahead ? p.slopeY(r, x, z) : r.y + FLARE_H;
            double cap = ahead ? Math.max(down, PAPI_DOWN * Math.max(0, speed)) : down;
            c.vy = VehicleMath.clamp(ahead ? -sink + CAPTURE_GAIN * (path - y) : CruiseMath.ALT_GAIN * (path - y), -cap, 0);
            c.done = !ahead && h < OFF_HEIGHT;               // never got onto the slope: stage C's let-go over the strip
        }
        return c;
    }

    /**
     * Stage D1, ВЫРАВН.: the throttle this tick - closing toward FLARE_THR at THR_RATE a tick while the speed is over
     * FLARE_MARGIN x the stall (+ 10 %), opening again under FLARE_MARGIN x the stall; between: as it is.
     */
    public static double flareThrottle(double thr, double speed, double stall) {
        double lo = FLARE_MARGIN * stall;
        if (speed < lo) {
            return VehicleMath.clamp(thr + THR_RATE, 0, SPD_MAX_THROTTLE);
        }
        if (speed > lo * 1.1 && thr > FLARE_THR) {
            return Math.max(FLARE_THR, thr - THR_RATE);
        }
        return thr;
    }

    /** The descent ЗАХВ uses (b/t, positive): the set vertical speed when ВЕРТ is set downward, else DESCENT. */
    public static double descent(int modes, double vs) {
        return (modes & M_VS) != 0 && vs < -1e-6 ? -vs : DESCENT;
    }

    // ---------------------------------------------------------------- the panel's state (synced: EntityVehicleSVA.DW_AP / DW_AP2)

    /** What the panel shows: engaged, modes, ЗАХВ has a runway / is aligned, the set values (compass deg, Y, km/h, blocks/s). */
    public static final class Panel {
        public boolean on, runway, aligned;
        public int modes, hdg, alt, kmh;
        /** Stage D1: ЗАХВ's phase (PH_RWY / PH_GLIDE / PH_FLARE). */
        public int phase;
        public double vs;

        public boolean mode(int bit) {
            return (modes & bit) != 0;
        }
    }

    /**
     * DW_AP: bit 0 engaged, 1..5 the modes, 6 a runway for ЗАХВ, 7 aligned, 8..16 the heading, 17..26 the height (0..1023),
     * 27..28 ЗАХВ's phase (stage D1).
     */
    public static int pack(Panel p) {
        return (p.on ? 1 : 0) | (p.modes & M_ALL) << 1 | (p.runway ? 64 : 0) | (p.aligned ? 128 : 0) | heading(p.hdg) << 8
                | Math.max(0, Math.min(1023, p.alt)) << 17 | Math.max(0, Math.min(PH_FLARE, p.phase)) << 27;
    }

    /** DW_AP2: bits 0..9 the set speed (km/h, 0..1023), 10..19 the set vertical speed in tenths of blocks/s + 512. */
    public static int pack2(Panel p) {
        int vs = (int) Math.max(0, Math.min(1023, Math.round(p.vs * 10) + 512));
        return Math.max(0, Math.min(1023, p.kmh)) | vs << 10;
    }

    public static Panel unpack(int a, int b) {
        Panel p = new Panel();
        p.on = (a & 1) != 0;
        p.modes = a >> 1 & M_ALL;
        p.runway = (a & 64) != 0;
        p.aligned = (a & 128) != 0;
        p.hdg = a >> 8 & 511;
        p.alt = a >> 17 & 1023;
        p.phase = a >> 27 & 3;
        p.kmh = b & 1023;
        p.vs = ((b >> 10 & 1023) - 512) / 10.0;
        return p;
    }

    // ---------------------------------------------------------------- HSI

    /** Compass bearing (deg 0..360, 0 = north = -Z, 90 = east = +X) from (x0, z0) to (x1, z1). */
    public static double bearing(double x0, double z0, double x1, double z1) {
        double b = Math.toDegrees(Math.atan2(x1 - x0, -(z1 - z0)));
        return (b % 360 + 360) % 360;
    }

    /** Horizontal distance (blocks). */
    public static double distance(double x0, double z0, double x1, double z1) {
        double dx = x1 - x0, dz = z1 - z0;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The course deviation bar's offset in dots (+ = the bar right of the aircraft symbol: the line is to the right),
     * clamped to +-DOTS: a pilot right of the line (cross > 0) sees the bar to the left.
     */
    public static double cdiDots(double cross) {
        return VehicleMath.clamp(-cross / DOT_BLOCKS, -DOTS, DOTS);
    }

    /** A card angle: where a compass direction `deg` stands on the HSI (deg clockwise from the top) with `heading` up. */
    public static double cardAngle(double deg, double heading) {
        return CruiseMath.wrap(deg - heading);
    }

    /** Kilometres with one decimal and a comma, for the windows («3,8»). */
    public static String km(double blocks) {
        long tenths = Math.round(blocks / 100.0);
        return (tenths / 10) + "," + (tenths % 10);
    }
}
