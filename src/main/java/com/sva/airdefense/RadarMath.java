package com.sva.airdefense;

import com.sva.vehicle.TuningMath;
import com.sva.vehicle.VehicleType;

/**
 * Air-defence wave (mock-up veh_ad_2_radars): the three radar kinds, how far they see each vehicle and what hides it -
 * pure, the self-test checks it.
 * <p>
 * Detection follows the radar equation: the range grows with the 4th root of the radar cross-section (RCS, «ЭПР») -
 * 16 times less RCS, half the range. RANGE is each radar's reach on the reference RCS 40 (the Singular Ship); a light
 * plane (4) is seen by the surveillance radar at 600 x (4/40)^0.25 = 337 blocks. Tuning: «Радиопоглощающее покрытие»
 * x0.6 of the range, «Гладкий корпус» x0.9 (both: x0.54); rain x0.85, a thunderstorm x0.7. The Singular Ship's
 * invisibility (it burns SM) hides it from radars altogether. Ground vehicles (and anything lower than MIN_AGL over the
 * ground - the hoverbike's cushion) are lost in the ground clutter.
 * <p>
 * Terrain: the beam from the antenna to the target is walked block by block (losClear) - stone, earth, metal, wood stop
 * it; leaves, glass, snow, water and plants do not (the caller's Probe decides per block). Flying low behind a hill hides.
 * <p>
 * The numbers (balanced from the mock-up's proposals): surveillance 600 blocks on RCS 40, one sweep in 6 s, +-8 blocks,
 * no altitude, every target in the circle, 256 EU/t (HV); tracking 360, 2 times a second, +-0.5 block + altitude, two
 * targets at once (the missile system's guidance), 256 EU/t (HV); height finder 400, every 3 s, altitude +-1, every target
 * in range, 64 EU/t (MV).
 */
public final class RadarMath {

    public static final int SURVEY = 0, TRACK = 1, HEIGHT = 2, KINDS = 3;
    /** Lang / texture keys. */
    public static final String[] KEYS = {"survey", "track", "height"};
    /** Range on REF_RCS (blocks). */
    public static final double[] RANGE = {600, 360, 400};
    public static final double REF_RCS = 40;
    /** Ticks between scans; the position error (blocks); targets held at once (0: all); EU a tick; altitude known. */
    public static final int[] INTERVAL = {120, 10, 60};
    public static final double[] ERROR = {8, 0.5, 1};
    public static final int[] CHANNELS = {0, 2, 0};
    public static final int[] EU_PER_TICK = {256, 256, 64};
    public static final boolean[] ALTITUDE = {false, true, true};
    /** Below this height over the ground a vehicle is lost in the ground clutter. */
    public static final double MIN_AGL = 4;
    /** Tuning range multipliers; the weather's. */
    public static final double RAM_MUL = 0.6, SMOOTH_MUL = 0.9, RAIN_MUL = 0.85, STORM_MUL = 0.7;
    /** A contact nobody has seen for this long leaves the picture (10 s). */
    public static final int TRACK_TIMEOUT = 200;
    /**
     * RWR threat kinds (CockpitDataSVA.rwrThreat): surveillance, tracking lock, missile, height finder. Audit 2026-10-22
     * (the RWR's gaps): T_LAUNCHER - a launcher's own lock (its fire-control: drawn as a square «ПУ», not the tracking
     * radar's diamond «С»); T_LAUNCH - the site a missile in the air at this vehicle came from (the scope's circle round it).
     */
    public static final int T_SEARCH = 0, T_LOCK = 1, T_MISSILE = 2, T_HEIGHT = 3, T_LAUNCHER = 4, T_LAUNCH = 5, T_KINDS = 6;
    /** The RWR scope's full scale (blocks). */
    public static final double RWR_RANGE = 600;

    private RadarMath() {
    }

    public static boolean validKind(int k) {
        return k >= 0 && k < KINDS;
    }

    /**
     * The radar cross-section («ЭПР») of a type: the Singular Ship 40, the shuttle 12, the tiltrotor 8, the helicopter 6,
     * the light plane 4, the jet 3, the hoverbike 0.5 (the cushion keeps it in the clutter anyway); ground vehicles 0.
     * Wave A: the SA-320 liner (wave 7 came after the air defence and had none - no radar ever saw it) 20, a big airliner.
     */
    public static double rcs(VehicleType t) {
        if (t == null) {
            return 0;
        }
        switch (t) {
            case SHIP:
                return 40;
            case LINER:
                return 20;
            case SHUTTLE:
                return 12;
            case TILT:
                return 8;
            case HELI:
                return 6;
            case PLANE:
                return 4;
            case JET:
                return 3;
            case HOVER:
                return 0.5;
            default:
                return 0;
        }
    }

    /** The detection range x this from the vehicle's tuning (tuning off on the server: 1). */
    public static double stealthMul(int[] tuning, boolean tuningOn) {
        if (!tuningOn) {
            return 1;
        }
        double m = 1;
        if (TuningMath.has(tuning, TuningMath.RAM_COAT)) {
            m *= RAM_MUL;
        }
        if (TuningMath.has(tuning, TuningMath.SMOOTH_HULL)) {
            m *= SMOOTH_MUL;
        }
        return m;
    }

    /**
     * Wave A (plan-next §9 A3): what sticks out of the airframe now - on top of the coating / hull (the same detection
     * range multipliers): the gear extended (retracting gear only - a fixed gear is in the type's RCS already) or stores on
     * the pylons (the «Доп. бак» on an aircraft) +20 % each, the liner's cargo doors open +10 %, and for a moment after the
     * thrust reverser deployed / the afterburner lit (BUMP_TICKS) +15 %.
     */
    public static final double GEAR_MUL = 1.2, STORES_MUL = 1.2, DOORS_MUL = 1.1, BUMP_MUL = 1.15;
    /** How long the reverser's / afterburner's bump lasts after it was last seen (ticks; 3 s). */
    public static final int BUMP_TICKS = 60;

    /** The extras' range multiplier (all off: 1). */
    public static double extrasMul(boolean gearOut, boolean stores, boolean doorsOpen, boolean bump) {
        return (gearOut ? GEAR_MUL : 1) * (stores ? STORES_MUL : 1) * (doorsOpen ? DOORS_MUL : 1) * (bump ? BUMP_MUL : 1);
    }

    /**
     * A retracting gear is out as the client's animation has it (AnimMath.gearTarget without its memory): below
     * EXTEND_HEIGHT over the ground or slower than EXTEND_SPEED; a type with fixed gear: false (no extra).
     */
    public static boolean gearOut(boolean retracts, double agl, double speed) {
        return retracts && (agl < com.sva.vehicle.AnimMath.EXTEND_HEIGHT || speed < com.sva.vehicle.AnimMath.EXTEND_SPEED);
    }

    /** The bump is still on: seen within BUMP_TICKS. */
    public static boolean bumpOn(long lastSeen, long now) {
        return lastSeen >= 0 && now >= lastSeen && now - lastSeen <= BUMP_TICKS;
    }

    /** The weather's range multiplier at the radar. */
    public static double weatherMul(boolean rain, boolean thunder) {
        return thunder ? STORM_MUL : rain ? RAIN_MUL : 1;
    }

    /**
     * Audit 2026-10-22: the weather where the radar stands - the world's rain / storm counts only in a biome that has
     * precipitation (rain or snow; a desert or a savanna under the world's storm stays clear).
     */
    public static double weatherAt(boolean worldRain, boolean worldThunder, boolean biomeWet) {
        return biomeWet ? weatherMul(worldRain, worldThunder && worldRain) : 1;
    }

    /** How far a radar of `kind` sees a target of this RCS (0: never). */
    public static double detectRange(int kind, double rcs, double stealthMul, double weatherMul) {
        if (!validKind(kind) || !(rcs > 0)) {
            return 0;
        }
        return RANGE[kind] * Math.pow(rcs / REF_RCS, 0.25) * Math.max(0, stealthMul) * Math.max(0, weatherMul);
    }

    /** The target can be seen at all: airborne high enough, not cloaked, a type with an RCS. */
    public static boolean visible(double rcs, double agl, boolean cloaked) {
        return rcs > 0 && agl >= MIN_AGL && !cloaked;
    }

    /** One block on the beam's way: does it stop the beam? */
    public interface Probe {
        boolean blocks(int x, int y, int z);
    }

    /**
     * Is the beam from (x0, y0, z0) to (x1, y1, z1) clear? Walks the line in half-block steps (each block asked once),
     * leaving out the first and the last 1.5 blocks (the antenna's own block, the target's hull).
     */
    public static boolean losClear(double x0, double y0, double z0, double x1, double y1, double z1, Probe p) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 3 || p == null) {
            return true;
        }
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        for (double d = 1.5; d <= len - 1.5; d += 0.5) {
            double k = d / len;
            int bx = floor(x0 + dx * k), by = floor(y0 + dy * k), bz = floor(z0 + dz * k);
            if (bx == lx && by == ly && bz == lz) {
                continue;
            }
            lx = bx;
            ly = by;
            lz = bz;
            if (by >= 0 && by < 256 && p.blocks(bx, by, bz)) {
                return false;
            }
        }
        return true;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** A repeatable error in [-err, err] for one target, one scan, one axis (the survey blip «trembles» +-8). */
    public static double jitter(int target, long scan, int axis, double err) {
        long h = XpdrMath.mix(target * 0x9E3779B97F4A7C15L ^ scan * 0xC2B2AE3D27D4EB4FL ^ axis * 0x165667B19E3779F9L);
        double u = (h >>> 11) / (double) (1L << 53);
        return (u * 2 - 1) * err;
    }

    /** Compass heading (0 north = -z, 90 east = +x) of the direction (dx, dz). */
    public static double compass(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        return a < 0 ? a + 360 : a;
    }

    /** A vehicle's compass heading from its Minecraft yaw (yaw 180 faces north). */
    public static double headingOfYaw(double yaw) {
        double h = (yaw + 180) % 360;
        return h < 0 ? h + 360 : h;
    }

    /** The bearing of a point seen from (x, z) relative to the nose (clockwise, 0..360), for the RWR scope. */
    public static double relBearing(double x, double z, double yaw, double tx, double tz) {
        double b = compass(tx - x, tz - z) - headingOfYaw(yaw);
        b %= 360;
        return b < 0 ? b + 360 : b;
    }

    /**
     * The RWR's scope radius fraction for a distance: near the centre = close (strong), the outer ring = far (mock-up
     * veh_ad_2: «ближе к центру - сильнее»). Audit 2026-10-22 (bug 11): it was turned over (a radar 20 blocks away sat on
     * the outer ring, one 590 away by the own-ship symbol): 0.33 at 0 blocks, 0.83 at RWR_RANGE and beyond.
     */
    public static double rwrRadius(double dist) {
        return 0.33 + 0.5 * Math.max(0, Math.min(1, dist / RWR_RANGE));
    }

    /** One emitter's paint on a vehicle (the RWR): its RadarMath.T_* kind, where it stands, until when (total world ticks). */
    public static final class Paint {
        public final int kind;
        public final double x, z;
        public final long until;

        public Paint(int kind, double x, double z, long until) {
            this.kind = kind;
            this.x = x;
            this.z = z;
            this.until = until;
        }
    }

    /** Paints kept on one vehicle at most. */
    public static final int PAINT_CAP = 16;

    /**
     * A new paint into a vehicle's list. Audit 2026-10-22 (bug 16): the expired ones go first (until < now), then this
     * emitter's older paint of the same kind; a list still full drops the paint ending soonest - the 17th emitter of a long
     * flight is no longer left off the RWR while one old emitter keeps painting.
     */
    public static void paint(java.util.List<Paint> l, Paint n, long now) {
        for (java.util.Iterator<Paint> it = l.iterator(); it.hasNext();) {
            Paint t = it.next();
            if (t.until < now || t.kind == n.kind && Math.abs(t.x - n.x) < 0.5 && Math.abs(t.z - n.z) < 0.5) {
                it.remove();
            }
        }
        while (l.size() >= PAINT_CAP) {
            int soonest = 0;
            for (int i = 1; i < l.size(); i++) {
                if (l.get(i).until < l.get(soonest).until) {
                    soonest = i;
                }
            }
            l.remove(soonest);
        }
        l.add(n);
    }

    /** The RWR's threat level for the HUD line and the sound: 0 none, 1 seen (search / height), 2 locked, 3 missile (the lamps: rwrLamps). */
    public static int rwrLevel(int[] kinds) {
        int l = 0;
        if (kinds != null) {
            for (int k : kinds) {
                l = Math.max(l, threatLevel(k));
            }
        }
        return l;
    }

    /** One threat kind's level: a missile or its launching site 3, a tracking radar's or a launcher's lock 2, a search 1. */
    public static int threatLevel(int kind) {
        return kind == T_MISSILE || kind == T_LAUNCH ? 3 : kind == T_LOCK || kind == T_LAUNCHER ? 2 : kind == T_SEARCH || kind == T_HEIGHT ? 1 : 0;
    }

    /** Audit 2026-10-22 (mock-up veh_ad_2): the RWR's lamps - ОБЗОР (any radar sees us), ЗАХВАТ (a lock), ПУСК (a missile). */
    public static final int LAMP_SEARCH = 1, LAMP_LOCK = 2, LAMP_LAUNCH = 4;

    /**
     * The lamps lit by these threats (LAMP_* bits): each lamp for its own kinds - a lock with no search radar left lights
     * ЗАХВАТ alone (the mock-up's sheet lights ОБЗОР along because a search radar is there too).
     */
    public static int rwrLamps(int[] kinds) {
        int m = 0;
        if (kinds != null) {
            for (int k : kinds) {
                int l = threatLevel(k);
                m |= l == 1 ? LAMP_SEARCH : l == 2 ? LAMP_LOCK : l == 3 ? LAMP_LAUNCH : 0;
            }
        }
        return m;
    }

    /** The RWR's sounds (mock-up veh_ad_2): none, the lock's continuous tone, the launch's fast warble. */
    public static final int TONE_NONE = 0, TONE_LOCK = 1, TONE_LAUNCH = 2;

    /** The continuous sound for a level (a search radar only pings - rwrPing). */
    public static int rwrTone(int level) {
        return level >= 3 ? TONE_LAUNCH : level == 2 ? TONE_LOCK : TONE_NONE;
    }

    /**
     * The search ping («тихий короткий писк»): once when a search / height radar starts painting - more search emitters
     * than the last time looked - never while a lock or a launch sounds over it.
     */
    public static boolean rwrPing(int searchNow, int searchBefore, int level) {
        return level == 1 && searchNow > searchBefore;
    }
}
