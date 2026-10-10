package com.sva.vehicle;

/**
 * Wave 4 (★15 рекуперация): regenerative braking and energy recovery - pure math, the server applies it once a tick
 * (EntityVehicleSVA.regenTick), the self-test (SelfTestRegenSVA) checks the bounds.
 * <p>
 * The physics has no real mass, so the energy "stored" in the motion is modelled from the cost formula itself:
 * <ul>
 * <li>KINETIC: the EU the motor spent to reach speed v from standstill at the type's accel (VehicleMath.cost integrated
 *   over the run-up): K(v) = (idle x v + speedCoef x (1 + cargo) x v^3 / 3) / accel x consumption. The buggy: K(top) =
 *   183 EU (25 ticks at 1..20 EU/t).</li>
 * <li>POTENTIAL: a block of height is worth POT_TICKS ticks at the cruise cost (the helicopter's CLIMB_EXTRA - 20 EU/t
 *   for 4 ticks a block - is ~0.9 of its cruise tick; the buggy: 20 EU a block).</li>
 * </ul>
 * Ground vehicles (wheels, tracks, the hoverbike) recover share x the kinetic energy they lose while BRAKING (S against
 * the motion, Space - the hoverbike: S only, its Space holds it higher) and share x the potential energy while they roll
 * DOWNHILL (moving, on the ground - a drop of FALL or more in one tick is a fall, not a slope). Aircraft recover only
 * height, by windmilling: in a glide / autorotation (the motor off) or a descent with the motor idle (plane: throttle
 * at idle; helicopter: collective down).
 * <p>
 * NO ENERGY FROM NOTHING: every EU the motor spends goes into a per-vehicle POOL (capped at the kinetic energy at top
 * speed + POOL_BLOCKS of height); a tick's recovery takes regen / share out of it. So over any drive the recovered EU
 * is at most share x the EU spent (share <= MAX_EFF = 0.5), whatever the measured speeds and heights do (bunched
 * packets, teleports). Plus: at most TICK_CAP x the cruise cost a tick, never past the buffer.
 */
public final class RegenMath {

    /** vehicles.regenEfficiency range; aircraft (windmilling props / rotors) never more than AIR_MAX. */
    public static final double MAX_EFF = 0.5, AIR_MAX = 0.3;
    /** Share of the efficiency by kind: wheels, tracks (track friction), the hoverbike (field coils, no wheels). */
    public static final double S_WHEELS = 1.0, S_TRACKS = 0.8, S_HOVER = 0.5;
    /** ... and aircraft: a propeller windmills well, a rotor fair, the jet's turbine poorly. */
    public static final double S_PROP = 1.0, S_ROTOR = 0.75, S_JET = 0.6;
    /** EU a block of height is worth, in ticks of the cruise cost. */
    public static final double POT_TICKS = 1.0;
    /** The pool: K(top) + this many blocks of height (ground) / of height (aircraft - long glides). */
    public static final double POOL_BLOCKS = 32, POOL_BLOCKS_AIR = 128;
    /** At most this x the cruise cost a tick. */
    public static final double TICK_CAP = 1.0;
    /** Ground: a drop of this or more in one tick is a fall (no recovery); moving less than MIN_MOVE a tick is standing. */
    public static final double FALL = 1.0, MIN_MOVE = 0.02;
    /** Aircraft: the height counted a tick is capped here (a dive is not a windmill). */
    public static final double AIR_DY_CAP = 0.5;
    /** Plane throttle at or under this counts as idle (windmilling). */
    public static final double IDLE_THROTTLE = 0.05;

    private RegenMath() {
    }

    /** The config's efficiency, sanitised to 0..MAX_EFF (NaN -> 0). */
    public static double eff(double cfg) {
        if (Double.isNaN(cfg) || cfg <= 0) {
            return 0;
        }
        return Math.min(MAX_EFF, cfg);
    }

    /** The share of recovered energy for this type (planeMode: the tiltrotor in airplane mode), efficiency eff (0..MAX_EFF). */
    public static double share(VehicleType t, boolean planeMode, double eff) {
        double e = eff(eff);
        switch (t.movement) {
            case GROUND:
                return e * S_WHEELS;
            case CRAWLER:
                return e * S_TRACKS;
            case HOVER:
                return e * S_HOVER;
            case PLANE:
                return Math.min(AIR_MAX, e * (t.gases.boostGas != null ? S_JET : S_PROP));
            case TILT:
                return Math.min(AIR_MAX, e * (planeMode ? S_PROP : S_ROTOR));
            case HELI:
            default:
                return Math.min(AIR_MAX, e * S_ROTOR);
        }
    }

    /** EU the motor spent to reach |v| from standstill (see the class comment); 0 without accel or consumption. */
    public static double kinetic(VehicleType t, double v, double cargoFill, double mul) {
        if (t.accel <= 0 || !(mul > 0)) {
            return 0;
        }
        double s = Math.min(Math.abs(v), t.topSpeed * 2);
        double fill = VehicleMath.clamp(cargoFill, 0, 1);
        return (t.idleCost * s + t.speedCoef() * (1 + fill) * s * s * s / 3) / t.accel * mul;
    }

    /** EU `dh` blocks of height are worth at this cruise cost (EU/t, config applied). */
    public static double potential(double cruise, double dh) {
        return Math.max(0, dh) * POT_TICKS * Math.max(0, cruise);
    }

    /** The pool's cap: kTop (K at top speed; 0 for aircraft) + POOL_BLOCKS (air: POOL_BLOCKS_AIR) of height. */
    public static double poolCap(double cruise, double kTop, boolean air) {
        return Math.max(0, kTop) + potential(cruise, air ? POOL_BLOCKS_AIR : POOL_BLOCKS);
    }

    /** The pool after the motor spent `spent` EU this tick (never negative, never over the cap). */
    public static double bank(double pool, double spent, double cap) {
        double p = (pool > 0 ? pool : 0) + (spent > 0 ? spent : 0);
        return Math.min(p, Math.max(0, cap));
    }

    /** Is this a braking input on the ground (moving at `speed`, + forward)? The hoverbike's Space is not a brake. */
    public static boolean braking(Movement m, boolean fwd, boolean back, boolean brakeKey, double speed) {
        if (m.flies) {
            return false;
        }
        boolean against = back && !fwd && speed > 0 || fwd && !back && speed < 0;
        return against || brakeKey && m != Movement.HOVER && speed != 0;
    }

    /**
     * Ground: the energy this tick could give back (before the share): the kinetic energy lost braking (vBefore ->
     * vAfter, measured speeds) + the height lost rolling downhill (dy < 0, moving, not falling).
     */
    public static double groundSource(VehicleType t, double vBefore, double vAfter, boolean braking, double dy, double moved,
            double cargoFill, double mul, double cruise) {
        double src = 0;
        if (braking && Math.abs(vAfter) < Math.abs(vBefore)) {
            src += kinetic(t, vBefore, cargoFill, mul) - kinetic(t, vAfter, cargoFill, mul);
        }
        if (dy < 0 && dy > -FALL && moved > MIN_MOVE) {
            src += potential(cruise, -dy);
        }
        return Math.max(0, src);
    }

    /** Aircraft: the height lost windmilling this tick (in the air, the motor off / idle), capped at AIR_DY_CAP a tick. */
    public static double airSource(double dy, boolean airborne, boolean windmill, double cruise) {
        if (!airborne || !windmill || !(dy < 0)) {
            return 0;
        }
        return potential(cruise, Math.min(-dy, AIR_DY_CAP));
    }

    /** EU recovered this tick: share x source, but no more than share x pool, TICK_CAP x cruise and the buffer's room. */
    public static double recover(double share, double source, double pool, double cruise, double room) {
        if (!(share > 0) || !(source > 0) || !(pool > 0) || !(room > 0) || !(cruise > 0)) {
            return 0;
        }
        double r = Math.min(share * source, share * pool);
        r = Math.min(r, TICK_CAP * cruise);
        return Math.max(0, Math.min(r, room));
    }

    /** The pool after `regen` EU came back at this share. */
    public static double drain(double pool, double regen, double share) {
        if (!(share > 0) || !(regen > 0)) {
            return pool;
        }
        return Math.max(0, pool - regen / share);
    }

    // ---- the synced word (DataWatcher): bits 0-6 the efficiency % (0 = off on the server), 7-31 tenths of EU/t

    private static final int PCT_BITS = 7, RATE_MAX = (1 << 24) - 1;

    public static int pack(double regenPerTick, double eff, boolean on) {
        int pct = on ? (int) Math.round(eff(eff) * 100) : 0;
        long r = Math.round(Math.max(0, Double.isNaN(regenPerTick) ? 0 : regenPerTick) * 10);
        int rate = (int) Math.min(RATE_MAX, r);
        return rate << PCT_BITS | Math.min(127, pct);
    }

    /** EU/t recovered (tenths kept). */
    public static double rate(int packed) {
        return (packed >>> PCT_BITS) / 10.0;
    }

    /** The server's efficiency in % (0: recovery off). */
    public static int pct(int packed) {
        return packed & ((1 << PCT_BITS) - 1);
    }

    /** "3,4" under 10 EU/t, "27" above (the HUD / cockpit line). */
    public static String fmt(double rate) {
        if (rate < 10) {
            long t = Math.round(rate * 10);
            return (t / 10) + "," + (t % 10);
        }
        return String.valueOf(Math.round(rate));
    }
}
