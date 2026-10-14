package com.sva.vehicle;

/**
 * Liner wave 4 (plan-next §7): the SA-320's flight model additions and the autopilot / flight director's guidance laws -
 * pure, the same code in the physics on the driving client and on the server (entity.LinerPhysicsSVA), in the PFD's FD bars
 * and in the self-test. The AFS (LinerAfs) decides the MODES on the server; this class turns the synced modes and targets
 * into STICK commands - the same inputs the pilot's keys give (no teleporting): a roll stick (roll rate) and a pitch stick
 * (the vertical speed the physics approaches), and a nose-wheel command on the roll-out.
 * <p>
 * <b>Bank</b> (the A320's normal law): the stick commands a roll RATE (ROLL_RATE x the surfaces' authority); released, the
 * bank holds up to BANK_HOLD (33°) and returns to it from beyond; full stick reaches BANK_MAX (67°). ALTN / DIRECT: no
 * protection, the bank holds where left (up to BANK_ABS). A coordinated turn: the heading changes by
 * TURN_K x tan(bank) x the airspeed's share over the stall - at 33° exactly the type's old manual turn rate, at the AP's
 * 25° ~0.8°/tick, never over TURN_CAP x it.
 * <p>
 * <b>Lateral laws</b>: a wanted heading (HDG: the FCU's; NAV: RouteMath's; LOC: the course - CROSS_GAIN° a block off the
 * centre line, at most INTERCEPT°; RWY / GA TRK: the heading held) -> a bank (HDG_GAIN° a degree off, at most the AP's
 * limit) -> the roll stick (P on the bank, full at ROLL_P° off).
 * <p>
 * <b>Vertical laws</b> (the wanted vertical speed, b/t): the SPEED ON PITCH modes (SRS, CLB, OP CLB, DES, OP DES, EXPED)
 * - the climb the thrust can pay for at this speed (the physics' own energy term: thrust x (thr - drag x r^2) =
 * vy x CLIMB_DRAG) plus SPD_GAIN x the speed error; ALT* - a constant-deceleration level-off (sqrt(2 a e), CAP_ACC); ALT -
 * ALT_GAIN x the error; V/S - the set rate; G/S - the beam's descent at this ground speed + GS_GAIN x the height off it;
 * FLARE - the sink shrinking with the height (FLARE_K x h) down to FLARE_SINK.
 */
public final class LinerGuide {

    /** Bank: the roll rate at full stick (deg/tick, 15°/s), the hold / protection limit, the normal-law and absolute maxima. */
    public static final double ROLL_RATE = 0.75, BANK_HOLD = 33, BANK_MAX = 67, BANK_ABS = 80, BANK_RETURN = 0.5;
    /** The turn rate's cap (x the type's rate). */
    public static final double TURN_CAP = 1.8;
    /** The autopilot's bank limit (deg) and its roll gains. */
    public static final double AP_BANK = 25, AP_BANK_MAX = 30, HDG_GAIN = 1.2, ROLL_P = 3;
    /** LOC: deg of intercept a block off the centre line, the most; G/S: b/t of vertical speed a block off the beam. */
    public static final double CROSS_GAIN = 1.2, CROSS_DAMP = 40, INTERCEPT = 30, GS_GAIN = 0.03;
    /** Speed on pitch: b/t of climb per b/t of speed error; SRS's climb limits (b/t). */
    public static final double SPD_GAIN = 0.6, SRS_MIN = 0.02, SRS_MAX = 0.2;
    /** ALT / ALT*: the hold gain (1/tick), the level-off's deceleration (b/t a tick), the fastest hold correction. */
    public static final double ALT_GAIN = 0.05, CAP_ACC = 0.004, ALT_MAX_VY = 0.25;
    /** FLARE: the sink falls with the height (1/tick) down to the touch-down's (b/t). */
    public static final double FLARE_K = 0.02, FLARE_SINK = 0.035;
    /** The roll-out: nose-wheel command a degree off the wanted heading, the wanted heading's degrees a block off the centre line. */
    public static final double STEER_GAIN = 0.15, ROLLOUT_CROSS = 2.0;
    /** The physics' climb drag (PlaneMath.CLIMB_DRAG - the energy term the speed-on-pitch law balances). */
    public static final double CLIMB_DRAG = PlaneMath.CLIMB_DRAG;
    /** Overspeed (normal law): b/t of climb added per b/t over VMO. */
    public static final double OVERSPEED_PITCH = 1.0;

    private LinerGuide() {
    }

    // ---------------------------------------------------------------- the bank model

    /**
     * The bank after a tick (deg, + right): `stick` -1..1 (the roll rate share), `auth` the surfaces' authority x the law's
     * gain, `protect` the normal law (the bank protection).
     */
    public static double bankStep(double bank, double stick, double auth, boolean protect) {
        double s = Math.max(-1, Math.min(1, stick));
        double b = bank + s * ROLL_RATE * Math.max(0, auth);
        if (protect) {
            if (Math.abs(s) < 0.05 && Math.abs(b) > BANK_HOLD) {
                b = Math.signum(b) * Math.max(BANK_HOLD, Math.abs(b) - BANK_RETURN);      // released beyond 33: back to 33
            }
            b = Math.max(-BANK_MAX, Math.min(BANK_MAX, b));
        } else {
            b = Math.max(-BANK_ABS, Math.min(BANK_ABS, b));
        }
        return b;
    }

    /** The turn's K (deg/tick at tan(bank) = 1): the type's old manual rate at the 33° hold. */
    public static double turnK(double typeRate) {
        return typeRate / Math.tan(Math.toRadians(BANK_HOLD));
    }

    /** The heading change a tick (deg, + right) at this bank and airspeed. */
    public static double turnRate(double bank, double speed, double stall, double typeRate) {
        double k = turnK(typeRate) * Math.max(0, Math.min(1, speed / Math.max(1e-6, stall)));
        double r = k * Math.tan(Math.toRadians(Math.max(-85, Math.min(85, bank))));
        double cap = typeRate * TURN_CAP;
        return Math.max(-cap, Math.min(cap, r));
    }

    /** The bank that gives this turn rate (the other clients' and the PFD's attitude from the synced yaw). */
    public static double bankOf(double rate, double speed, double stall, double typeRate) {
        double k = turnK(typeRate) * Math.max(0, Math.min(1, speed / Math.max(1e-6, stall)));
        if (k < 1e-6) {
            return 0;
        }
        return Math.toDegrees(Math.atan(rate / k));
    }

    // ---------------------------------------------------------------- lateral

    /** The bank wanted to turn onto `want` (compass) from `hdg`, within the limit. */
    public static double bankFor(double want, double hdg, double limit) {
        double e = CruiseMath.wrap(want - hdg);
        return Math.max(-limit, Math.min(limit, e * HDG_GAIN));
    }

    /** The roll stick that takes the bank to `bankCmd`. */
    public static double rollStick(double bankCmd, double bank) {
        return Math.max(-1, Math.min(1, (bankCmd - bank) / ROLL_P));
    }

    /**
     * LOC: the heading wanted for this cross-track offset (+ right) on this course - a PD on the offset: CROSS_GAIN° a block
     * and CROSS_DAMP° per b/t of drift across it (the ground speed x sin of the heading off the course), at most INTERCEPT°.
     */
    public static double locHeading(double course, double cross, double hdg, double gs) {
        double rate = gs * Math.sin(Math.toRadians(CruiseMath.wrap(hdg - course)));
        return LinerIls.wrap360(course - Math.max(-INTERCEPT, Math.min(INTERCEPT, cross * CROSS_GAIN + rate * CROSS_DAMP)));
    }

    /** The roll-out's nose-wheel command (-1..1) toward the centre line (the same PD, at most 10° off the course). */
    public static double rolloutSteer(double course, double cross, double hdg, double gs) {
        double rate = gs * Math.sin(Math.toRadians(CruiseMath.wrap(hdg - course)));
        double want = course - Math.max(-10, Math.min(10, cross * ROLLOUT_CROSS + rate * CROSS_DAMP));
        return Math.max(-1, Math.min(1, CruiseMath.wrap(want - hdg) * STEER_GAIN));
    }

    // ---------------------------------------------------------------- vertical

    /** The thrust's acceleration term (b/t a tick): the physics' thrust x (thr - drag x r^2). */
    public static double thrustAcc(double thrustK, double thr, double drag, double speed, double top) {
        double r = speed / Math.max(1e-6, top);
        return thrustK * (thr - drag * r * r);
    }

    /** Speed on pitch: the vertical speed that holds `target` with this thrust term. */
    public static double speedOnPitch(double thrAcc, double speed, double target) {
        return thrAcc / CLIMB_DRAG + SPD_GAIN * (speed - target);
    }

    /** ALT*: the vertical speed of a smooth level-off at `err` blocks to go (+ = the target above). */
    public static double capture(double err) {
        double v = Math.sqrt(2 * CAP_ACC * Math.abs(err));
        v = Math.min(v, Math.abs(err) * ALT_GAIN * 2.5);
        return Math.signum(err) * v;
    }

    /** ALT: the hold. */
    public static double hold(double err) {
        return Math.max(-ALT_MAX_VY, Math.min(ALT_MAX_VY, err * ALT_GAIN));
    }

    /** ALT* starts this far from the target (blocks) at this vertical speed: the level-off's stopping distance + a block. */
    public static double captureDistance(double vy) {
        return vy * vy / (2 * CAP_ACC) + 1;
    }

    /** G/S: the beam's descent at this ground speed and the correction for the height over it. */
    public static double glide(double gs, double angle, double above) {
        return -gs * Math.tan(Math.toRadians(angle)) - GS_GAIN * above;
    }

    /** FLARE: the sink at this radio height (never more than the glide's). */
    public static double flare(double ra, double glideVy) {
        double sink = Math.max(FLARE_SINK, Math.min(-glideVy, FLARE_K * ra));
        return -sink;
    }

    /** The pitch stick for a wanted vertical speed: + up to the climb capacity, - down to the dive. */
    public static double pitchStick(double vyWant, double cap, double dive) {
        if (vyWant >= 0) {
            return cap > 1e-6 ? Math.min(1, vyWant / cap) : 0;
        }
        return Math.max(-1, vyWant / Math.max(1e-6, dive));
    }

    /** The vertical speed a pitch stick asks for (the physics' side of pitchStick). */
    public static double stickVy(double stick, double cap, double dive) {
        return stick >= 0 ? stick * cap : stick * dive;
    }

    /** The compass heading (0 north, 90 east) of a Minecraft yaw, continuous. */
    public static double compass(double yaw) {
        return LinerIls.wrap360(yaw + 180.0);
    }
}
