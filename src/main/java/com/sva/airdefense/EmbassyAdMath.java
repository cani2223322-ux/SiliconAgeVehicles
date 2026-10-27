package com.sva.airdefense;

/**
 * «ПВО посольства» (docs/plan-embassy-airfield-addon.md §3.10; the user's choice 6 of 2026-10-26 and the coordinator's a-f): the PURE
 * rules of the Tuners' embassy's own air defence - what it does with one machine at one look. No Minecraft classes (the dedicated
 * server's self-test runs the whole matrix: airborne x in the zone x hold of any one aboard x the warning's stage x unmanned / owner).
 * The server side (EmbassyAdSVA) gathers a Look from the world and the core's API (EmbassyCompatSVA: adZoneAt, holdFire, requestWarn,
 * requestPass) and acts on the verdict.
 * <ul>
 * <li><b>Only machines in the air</b> (EntityVehicleSVA.airborneForAtc) <b>in the zone</b> (adZoneAt: 256 across from the core, any
 * height, never over a player's base) are judged at all - never a player on foot, a player flying in armour or a jetpack (no vehicle),
 * nothing on the ground: A_NONE.</li>
 * <li><b>Hold</b> (A_HOLD): anyone aboard - pilot, crew, passenger - makes the machine no target (the core's holdFire: admitted
 * «Знакомый»+ and not its «Враг», an admitted squad mate near, his 7700 pass running, over a player's base). A machine with nobody aboard
 * is held while its owner is admitted (or the unmanned rule is off - the caller says so through ownerHold).</li>
 * <li><b>Warning</b> (A_WARN): the first look of a machine in the zone not held - the caller asks the core's ledger for each one aboard
 * (requestWarn; a machine's seconds end at the EARLIEST of its riders' answers - turnEnd / earliest), says AD_WARN in the chat and on the
 * radio, and keeps the distance and the height of the moment (dist0, h0).</li>
 * <li><b>Turn-away</b> (A_TURN) until the end, unless (A_FIRE at once - why()): the end was already past at the warning (warned today -
 * a re-entry: WHY_REENTRY), it closes on the core by more than closeBlocks than at the warning (WHY_CLOSE), or it descends below
 * minHeight over the field and lower than at the warning (WHY_DESCEND; level flight's noise DESCEND_TOL) - a landing during the warning
 * is no way past the guns.</li>
 * <li><b>Fire</b> (A_FIRE) once the seconds are over and it is still in the zone, airborne and not held. Out of the zone the caller
 * forgets it (AD_LEFT); a re-entry the same game day gets no new seconds (the core's ledger - WHY_REENTRY).</li>
 * </ul>
 * Written whole by the coordinator (the numbers come from ConfigSVA: airdefense.embassyWarnSeconds, embassyCloseBlocks, embassyMinHeight,
 * embassyWarnPerDay, embassyUnmanned - all TODO(balance)).
 */
public final class EmbassyAdMath {

    /** The verdicts. */
    public static final int A_NONE = 0, A_HOLD = 1, A_WARN = 2, A_TURN = 3, A_FIRE = 4;
    public static final String[] A_KEYS = {"none", "hold", "warn", "turn", "fire"};
    /** Why a warned machine is a target (WHY_NONE: not yet). */
    public static final int WHY_NONE = 0, WHY_TIME = 1, WHY_CLOSE = 2, WHY_DESCEND = 3, WHY_REENTRY = 4;
    public static final String[] WHY_KEYS = {"none", "time", "close", "descend", "reentry"};
    /** A descent within this of the height at the warning is level flight's noise (blocks). */
    public static final double DESCEND_TOL = 2;
    /** Ticks a second. */
    public static final int TICKS = 20;

    private EmbassyAdMath() {
    }

    /** One machine at one look (the server fills it). */
    public static final class Look {
        /** In the air (airborneForAtc), in the embassy's air defence zone (adZoneAt == the embassy). */
        public boolean airborne, inZone;
        /** A player aboard (pilot, crew, passenger); any one of them holds the fire (holdFire); nobody aboard: the owner holds it. */
        public boolean manned, anyHold, ownerHold;
        /** A warning is open for this machine (the caller's memory) - when it was given and when its seconds end (world ticks; 0 none). */
        public boolean warned;
        public long warnedAt, end, now;
        /** Across from the core (blocks) and the height over the field (blocks) - at the warning (0) and now. */
        public double dist0, dist, h0, h;
        /** The rule's numbers (ConfigSVA.adEmbassyCloseBlocks / adEmbassyMinHeight). */
        public double closeBlocks = 32, minHeight = 40;
    }

    /** The verdict of a look (see the class comment). */
    public static int verdict(Look k) {
        if (k == null || !k.inZone || !k.airborne) {
            return A_NONE;
        }
        if (k.manned ? k.anyHold : k.ownerHold) {
            return A_HOLD;
        }
        if (!k.warned) {
            return A_WARN;
        }
        return why(k) != WHY_NONE ? A_FIRE : A_TURN;
    }

    /** Why a warned machine is a target now (WHY_NONE: still turning away, or not warned). */
    public static int why(Look k) {
        if (k == null || !k.warned) {
            return WHY_NONE;
        }
        if (k.end <= 0 || k.end <= k.warnedAt) {
            return WHY_REENTRY;                                     // no seconds: warned already today (or no warnings at all)
        }
        if (k.now >= k.end) {
            return WHY_TIME;
        }
        if (k.dist < k.dist0 - k.closeBlocks) {
            return WHY_CLOSE;
        }
        if (k.h < k.minHeight && k.h < k.h0 - DESCEND_TOL) {
            return WHY_DESCEND;
        }
        return WHY_NONE;
    }

    /**
     * The machine's turn-away end from its riders' requestWarn answers: the EARLIEST of them (0 - an admitted one / none - skipped);
     * none at all: 0.
     */
    public static long earliest(long[] ends) {
        long best = 0;
        if (ends == null) {
            return 0;
        }
        for (long e : ends) {
            if (e > 0 && (best == 0 || e < best)) {
                best = e;
            }
        }
        return best;
    }

    /**
     * The add-on's end of the seconds from the core's answer `coreEnd` at `now`: a future end (the day's first warning) - `seconds` from
     * now (airdefense.embassyWarnSeconds); a past one (warned today) - kept as it is (a re-entry: no new seconds); 0 - 0.
     */
    public static long turnEnd(long coreEnd, long now, int seconds) {
        if (coreEnd <= 0) {
            return 0;
        }
        return coreEnd > now ? now + Math.max(1, seconds) * (long) TICKS : coreEnd;
    }

    /**
     * airdefense.embassyWarnPerDay: one more warning may be given today when the core's ledger already gave one (`coreWarned`) - the
     * add-on's own extra count `extraToday` against `perDay` (1: the core's ledger only; 0: never any).
     */
    public static boolean extraWarning(boolean coreWarned, int extraToday, int perDay) {
        if (perDay <= 0) {
            return false;
        }
        return coreWarned && extraToday + 1 < perDay;
    }

    /** No warning at all is given (airdefense.embassyWarnPerDay 0): the first look in the zone is AD_FIRE. */
    public static boolean noWarnings(int perDay) {
        return perDay <= 0;
    }

    /**
     * A machine with nobody aboard is held (Look.ownerHold): the unmanned rule off, or its owner online and admitted (an owner offline is
     * not admitted - the caller warns nobody and counts the seconds from the first look).
     */
    public static boolean ownerHolds(boolean unmannedRule, boolean ownerOnline, boolean ownerAdmitted) {
        return !unmannedRule || ownerOnline && ownerAdmitted;
    }

    /** The core's 7700 answer (EmbassyCompatSVA.PASS_*) -> the line to say (AD_PASS / AD_PASS_USED; -1 none). */
    public static int passLine(int passAnswer) {
        return passAnswer == 0 ? 2 : passAnswer == 2 ? 3 : -1;     // PASS_GRANTED -> AD_PASS, PASS_USED -> AD_PASS_USED
    }

    /**
     * The post that fires: the nearest of `posts` ({x, y, z} guns) within `range` of the target (tx, ty, tz) whose line of fire is clear
     * (los[i]); -1 none.
     */
    public static int pickPost(double[][] posts, boolean[] los, double tx, double ty, double tz, double range) {
        int best = -1;
        double bd = range * range;
        for (int i = 0; posts != null && i < posts.length; i++) {
            if (posts[i] == null || los == null || i >= los.length || !los[i]) {
                continue;
            }
            double dx = posts[i][0] - tx, dy = posts[i][1] - ty, dz = posts[i][2] - tz, d = dx * dx + dy * dy + dz * dz;
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ package E's helpers (appended; the rules above unchanged)

    /** The whole seconds left until world tick `end` at `now` (rounded up; 0 when past) - the HUD lines' number. */
    public static int secondsLeft(long end, long now) {
        return end <= now ? 0 : (int) Math.min(Integer.MAX_VALUE, (end - now + TICKS - 1) / TICKS);
    }

    /**
     * The points along a closed outline `closed` ({x, z} corners, the last joined to the first - ZoneMath.outline) at most `step` apart,
     * the corners among them (the zone editor asks each one: under an embassy's neutral sky?). Empty for none.
     */
    public static double[][] alongOutline(double[][] closed, double step) {
        java.util.List<double[]> out = new java.util.ArrayList<double[]>();
        int n = closed == null ? 0 : closed.length;
        double s = step > 0.5 ? step : 0.5;
        for (int i = 0; i < n; i++) {
            double[] a = closed[i], b = closed[(i + 1) % n];
            if (a == null || b == null || a.length < 2 || b.length < 2) {
                continue;
            }
            int parts = (int) Math.min(4096, Math.max(1, Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) / s)));
            for (int k = 0; k < parts; k++) {
                double f = (double) k / parts;
                out.add(new double[] {a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f});
            }
        }
        return out.toArray(new double[out.size()][]);
    }
}
