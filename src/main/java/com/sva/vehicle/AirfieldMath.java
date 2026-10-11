package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * Aircraft round 2, stage D1 (★1 PAPI, ★2 approach / centre-line / threshold lights and the threshold designator, ★14 the
 * retractable landing lights; mock-up docs/mockups/veh_air3_1_airfield.png): the airfield's pure logic - no Minecraft
 * classes, the dedicated server's self-test (debug/SelfTestAirD1SVA) runs all of it.
 * <ul>
 * <li>PAPI («Огни ПАПИ», block.BlockPapiSVA): a row of up to 4 units beside the runway, opposite the touchdown point. Each
 * unit is white above its own angle and red under it: nominal + 0.5 / + 0.2 / - 0.2 / - 0.5 deg from the unit NEAREST the
 * runway to the farthest (3 deg nominal: 3.5 / 3.2 / 2.8 / 2.5) - on the slope two white (the outer) + two red (the inner).
 * The angle is the viewer's elevation over the unit's lens (atan of the height over the horizontal distance). The nominal
 * angle is the block's metadata (3 / 4 / 5 deg, RMB cycles the row). A unit is seen only from the approach side.</li>
 * <li>findPapi: the row serving one end of a RunwayMath strip - the band beside both edges over the strip's half nearer
 * that threshold (PAPI_BAND blocks out, a few levels round the surface); the row with the most units wins.</li>
 * <li>The approach lights' sequenced flasher («бегущий огонь»): FLASH_PERIOD ticks a run, the flash travels toward the
 * threshold over SEQ_LEN blocks - each pole's phase by world time and its distance before the threshold, no packets.</li>
 * <li>The runway's painted / lit marks (client.RunwayLightsSVA draws them): centre-line lights every CL_STEP blocks (the
 * last RED_ALT of the strip red / white alternating, the last RED_END red), threshold lights green across the near end and
 * red across the far one, the threshold «зебра» and the designator (the course / 10 rounded: 268 -> «27», 0 -> «36»).</li>
 * <li>Lights on: at night (the sun under SUN_NIGHT) or in rain; a block wired to redstone (a lever, a wire, a button,
 * a torch, a repeater... next to it) follows its signal instead (lightsOn).</li>
 * <li>★14 the landing light under the wing: out with flaps of LAMP_FLAPS deg or more, or under LAMP_AGL blocks over the
 * ground descending (an approach), or with its switch (stage D2's overhead panel - `switchOn`); in LAMP_TICKS.</li>
 * </ul>
 */
public final class AirfieldMath {

    // ---------------------------------------------------------------- PAPI

    /** Nominal glide angles a PAPI row can be set to (deg; the block's metadata 0..2). */
    public static final int[] NOMINALS = {3, 4, 5};
    /** Each unit's angle over the nominal, the unit nearest the runway first (deg). */
    public static final double[] UNIT_OFFSET = {0.5, 0.2, -0.2, -0.5};
    public static final int UNITS = 4;
    /** How far out from the runway's edge a PAPI row is looked for (blocks), and the levels round the surface. */
    public static final int PAPI_BAND = 10, PAPI_DOWN = 3, PAPI_UP = 1;
    /** The lens's height over the block's bottom (block units). */
    public static final double LENS_Y = 0.42;
    /** Sector with two white: within this of the nominal (deg) - the inner units' offsets. */
    public static final double ON_SLOPE = 0.2;

    private AirfieldMath() {
    }

    /** The nominal angle (deg) of a metadata value (0..2; anything else -> 3). */
    public static int nominal(int meta) {
        int i = meta & 3;
        return i < NOMINALS.length ? NOMINALS[i] : NOMINALS[0];
    }

    /** RMB: the next nominal's metadata value (3 -> 4 -> 5 -> 3). */
    public static int nextNominal(int meta) {
        return ((meta & 3) + 1) % NOMINALS.length;
    }

    /** The elevation (deg) of a point dy over and `dist` away horizontally. */
    public static double angle(double dy, double dist) {
        return Math.toDegrees(Math.atan2(dy, Math.max(1e-6, dist)));
    }

    /** Unit `index` (0 = nearest the runway .. 3) shows white at this viewing angle. */
    public static boolean white(double angleDeg, double nominal, int index) {
        int i = Math.max(0, Math.min(UNITS - 1, index));
        return angleDeg > nominal + UNIT_OFFSET[i];
    }

    /** How many of the 4 units show white (0 = far too low .. 4 = far too high; 2 = on the slope). */
    public static int whites(double angleDeg, double nominal) {
        int n = 0;
        for (int i = 0; i < UNITS; i++) {
            if (white(angleDeg, nominal, i)) {
                n++;
            }
        }
        return n;
    }

    /** What the AP's pilot reads from the PAPI: -2 far too low, -1 slightly low, 0 on the slope, 1 slightly high, 2 far too high. */
    public static int signal(int whites) {
        return whites - 2;
    }

    /** A unit's index in its row: how many units stand between it and the runway (0..3). */
    public static int unitIndex(int unitsCloserToRunway) {
        return Math.max(0, Math.min(UNITS - 1, unitsCloserToRunway));
    }

    /** A PAPI row serving one end of a runway strip. */
    public static final class Papi {
        /** The landing direction it serves (AutopilotMath.direction's +1 / -1: the threshold at a0 / a1). */
        public final int dir;
        /** The row's position along the strip's axis and across it (world, block centres), its lens height (world Y). */
        public final double along, cross, y;
        /** Its nominal angle (deg) and units found. */
        public final int nominal, units;

        public Papi(int dir, double along, double cross, double y, int nominal, int units) {
            this.dir = dir;
            this.along = along;
            this.cross = cross;
            this.y = y;
            this.nominal = nominal;
            this.units = units;
        }

        /** The row's distance from the threshold it serves (blocks along the axis). */
        public double fromThreshold(RunwayMath.Runway r) {
            return dir > 0 ? along - r.a0 : r.a1 - along;
        }

        /** Horizontal distance from (x, z) to the row's centre. */
        public double distance(RunwayMath.Runway r, double x, double z) {
            double a = r.alongX ? x : z, k = r.alongX ? z : x;
            double da = a - along, dk = k - cross;
            return Math.sqrt(da * da + dk * dk);
        }

        /** (x, z) is on the approach side: before the row in the direction it serves. */
        public boolean ahead(RunwayMath.Runway r, double x, double z) {
            double a = r.alongX ? x : z;
            return dir * (a - along) < -0.5;
        }

        /** The viewing angle (deg) of a point over the row. */
        public double angle(RunwayMath.Runway r, double x, double y, double z) {
            return AirfieldMath.angle(y - this.y, distance(r, x, z));
        }

        /** The glide slope's height (world Y) over (x, z): the lens height + tan(nominal) x the distance. */
        public double slopeY(RunwayMath.Runway r, double x, double z) {
            return y + Math.tan(Math.toRadians(nominal)) * distance(r, x, z);
        }
    }

    /** What findPapi reads: the PAPI unit at a block (its metadata) or -1. */
    public interface Blocks {
        int papi(int x, int y, int z);
    }

    /**
     * The PAPI row serving the end of r approached in direction dir (+1: the threshold at a0), or null: the bands beside
     * both edges (PAPI_BAND blocks out) along the strip's half nearer that threshold, PAPI_DOWN levels under the surface ..
     * PAPI_UP over it. Units at the same position along the axis on one side make a row; the row with the most units wins
     * (a tie: the one nearer the threshold). Only loaded blocks are read (the Blocks view answers -1 for the rest).
     */
    public static Papi findPapi(Blocks w, RunwayMath.Runway r, int dir) {
        int len = (int) Math.round(r.length());
        int first = (int) Math.floor(r.a0), last = (int) Math.floor(r.a1) - 1;
        int from = dir > 0 ? first : last - len / 2, to = dir > 0 ? first + len / 2 : last;
        double half = r.width / 2.0;
        int kHi = (int) Math.floor(r.c + half), kLo = (int) Math.floor(r.c - half) - 1;   // the first block outside each edge
        int ySurf = (int) Math.round(r.y);
        Papi best = null;
        for (int side = -1; side <= 1; side += 2) {
            for (int a = from; a <= to; a++) {
                int n = 0, nom = -1, yAt = 0;
                double kSum = 0;
                for (int j = 0; j < PAPI_BAND; j++) {
                    int k = side > 0 ? kHi + j : kLo - j;
                    for (int y = ySurf - PAPI_DOWN; y <= ySurf + PAPI_UP; y++) {
                        int m = r.alongX ? w.papi(a, y, k) : w.papi(k, y, a);
                        if (m >= 0) {
                            n++;
                            kSum += k + 0.5;
                            if (nom < 0) {
                                nom = nominal(m);
                                yAt = y;
                            }
                            break;
                        }
                    }
                }
                if (n == 0) {
                    continue;
                }
                Papi p = new Papi(dir, a + 0.5, kSum / n, yAt + LENS_Y, nom, Math.min(n, UNITS));
                if (best == null || p.units > best.units || p.units == best.units && p.fromThreshold(r) < best.fromThreshold(r)) {
                    best = p;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- approach lights: the sequenced flasher

    /** A run of the flash every FLASH_PERIOD ticks (2 a second); it crosses SEQ_LEN blocks; each pole lit FLASH_FRAC of a run. */
    public static final int FLASH_PERIOD = 10;
    public static final double SEQ_LEN = 40, FLASH_FRAC = 0.16;
    /** Approach poles are looked for this far before a threshold (blocks) and this far off the centre line beyond the edge. */
    public static final int APPROACH_MAX = 64;

    /**
     * The flash's brightness (0..1) on a pole `dist` blocks before the threshold at time `t` (ticks, fractional): the run
     * starts SEQ_LEN out and reaches the threshold FLASH_PERIOD ticks later; the pole flares at once and fades over
     * FLASH_FRAC of a run.
     */
    public static double flash(double t, double dist) {
        double ph = (t + Math.max(0, dist) * FLASH_PERIOD / SEQ_LEN) / FLASH_PERIOD;
        ph -= Math.floor(ph);
        return ph < FLASH_FRAC ? 1 - ph / FLASH_FRAC : 0;
    }

    /** The time (ticks into a run, 0..FLASH_PERIOD) the flash is at `dist` blocks before the threshold. */
    public static double flashAt(double dist) {
        double p = FLASH_PERIOD - Math.max(0, dist) * FLASH_PERIOD / SEQ_LEN;
        p %= FLASH_PERIOD;
        return p < 0 ? p + FLASH_PERIOD : p;
    }

    /**
     * An approach pole at (x, z) in line with r: the landing direction it leads into (+1: the threshold at a0) and its
     * distance before that threshold, or null - off the extended centre line (more than half the width + 1 to the side)
     * or not before either end within APPROACH_MAX.
     */
    public static double[] approach(RunwayMath.Runway r, double x, double z) {
        double a = r.alongX ? x : z, k = r.alongX ? z : x;
        if (Math.abs(k - r.c) > r.width / 2.0 + 1) {
            return null;
        }
        if (a < r.a0 && r.a0 - a <= APPROACH_MAX) {
            return new double[] {1, r.a0 - a};
        }
        if (a > r.a1 && a - r.a1 <= APPROACH_MAX) {
            return new double[] {-1, a - r.a1};
        }
        return null;
    }

    // ---------------------------------------------------------------- runway marks

    /** Centre-line lights every CL_STEP blocks; the last RED_ALT of the length red / white, the last RED_END red. */
    public static final double CL_STEP = 2, RED_ALT = 0.30, RED_END = 0.10;
    public static final int WHITE = 0, RED = 1, GREEN = 2;

    /**
     * A centre-line light's colour for a pilot landing toward the far end: `toEnd` blocks before the strip's far end on a
     * strip `len` long, `index` its number along (the alternation): WHITE, then RED / WHITE alternating over the last
     * RED_ALT of the length, RED over the last RED_END.
     */
    public static int centreColour(double len, double toEnd, int index) {
        double f = toEnd / Math.max(1e-6, len);
        if (f < RED_END) {
            return RED;
        }
        if (f < RED_ALT) {
            return (index & 1) == 0 ? RED : WHITE;
        }
        return WHITE;
    }

    /** The centre-line lights' positions along the strip (world, from a0): every CL_STEP from a0 + 1 while inside. */
    public static List<Double> centreLights(RunwayMath.Runway r) {
        List<Double> l = new ArrayList<Double>();
        for (double a = r.a0 + 1; a <= r.a1 - 0.99; a += CL_STEP) {
            l.add(Double.valueOf(a));
        }
        return l;
    }

    /** The threshold designator for a course (compass deg): the course / 10 rounded, 1..36 as two digits (268 -> "27", 4 -> "36"). */
    public static String designator(double course) {
        long n = Math.round(((course % 360) + 360) % 360 / 10.0) % 36;
        if (n == 0) {
            n = 36;
        }
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /** The designator painted at the end approached in direction dir. */
    public static String designator(RunwayMath.Runway r, int dir) {
        return designator(AutopilotMath.course(r, dir));
    }

    /** 3 x 5 digit glyphs, top row first ('1' = paint). */
    public static final String[][] DIGITS = {
        {"111", "101", "101", "101", "111"}, {"010", "110", "010", "010", "111"}, {"111", "001", "111", "100", "111"},
        {"111", "001", "111", "001", "111"}, {"101", "101", "111", "001", "001"}, {"111", "100", "111", "001", "111"},
        {"111", "100", "111", "101", "111"}, {"111", "001", "001", "010", "010"}, {"111", "101", "111", "101", "111"},
        {"111", "101", "111", "001", "111"}};

    /** The threshold marks' layout (blocks from the threshold, along / across). */
    public static final double ZEBRA_V0 = 0.7, ZEBRA_LEN = 3.0, NUMBER_GAP = 1.0, STRIPE_W = 0.3, STRIPE_STEP = 0.7;

    /**
     * The threshold «зебра»: the stripes' centres across the strip (u, + = right looking along the landing direction):
     * every STRIPE_STEP from 0.35 inside each edge, none within 0.4 of the centre line.
     */
    public static List<Double> zebra(int width) {
        List<Double> l = new ArrayList<Double>();
        double half = width / 2.0 - 0.35;
        for (double u = 0.55; u <= half + 1e-9; u += STRIPE_STEP) {
            l.add(Double.valueOf(u));
            l.add(Double.valueOf(-u));
        }
        return l;
    }

    /**
     * The designator's paint as cells {u0, v0, u1, v1} (u across, + right; v along from the threshold, + into the strip):
     * two 3 x 5 digits a cell apart, 7 cells over 80 % of the width (a cell at most 0.6 across, 1.6 x that along), the
     * glyphs' top rows farthest from the threshold (read by the pilot landing), starting after the «зебра».
     */
    public static List<double[]> numberCells(String num, int width) {
        List<double[]> l = new ArrayList<double[]>();
        double cw = Math.min(0.6, width * 0.8 / 7), ch = cw * 1.6;
        double v0 = ZEBRA_V0 + ZEBRA_LEN + NUMBER_GAP;
        for (int d = 0; d < num.length() && d < 2; d++) {
            int digit = num.charAt(d) - '0';
            if (digit < 0 || digit > 9) {
                continue;
            }
            String[] g = DIGITS[digit];
            double ux = -3.5 * cw + d * 4 * cw;
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if (g[row].charAt(col) == '1') {
                        double v = v0 + (4 - row) * ch;
                        l.add(new double[] {ux + col * cw, v, ux + (col + 1) * cw, v + ch});
                    }
                }
            }
        }
        return l;
    }

    /** Runway-local (u across, v along from the threshold of direction dir) to world {x, z}. */
    public static double[] world(RunwayMath.Runway r, int dir, double u, double v) {
        double a = dir > 0 ? r.a0 + v : r.a1 - v;
        double k = r.c + (r.alongX ? u * dir : -u * dir);
        return r.alongX ? new double[] {a, k} : new double[] {k, a};
    }

    // ---------------------------------------------------------------- lights on

    /** The sun's height (cos of the celestial angle) under which it is night for the lights. */
    public static final double SUN_NIGHT = 0.2;
    /** Manual control: none (automatic), forced off, forced on. */
    public static final int AUTO = -1, OFF = 0, ON = 1;

    /** Night by the celestial angle (0 = noon .. 0.5 = midnight). */
    public static boolean night(float celestialAngle) {
        return Math.cos(celestialAngle * Math.PI * 2) < SUN_NIGHT;
    }

    /** The lights burn: manual ON / OFF (a block wired to redstone: its signal), else at night or in rain. */
    public static boolean lightsOn(float celestialAngle, boolean rain, int manual) {
        if (manual != AUTO) {
            return manual == ON;
        }
        return rain || night(celestialAngle);
    }

    /** Manual state of a block: not wired to redstone - AUTO; wired - ON with a signal, OFF without. */
    public static int manual(boolean wired, boolean powered) {
        return !wired ? AUTO : powered ? ON : OFF;
    }

    // ---------------------------------------------------------------- ★14 the landing light under the wing

    /** Out with flaps of this many deg or more; under this height (blocks over the ground) descending; travel ticks (1 s). */
    public static final double LAMP_FLAPS = 10, LAMP_AGL = 30, LAMP_SINK = -0.02;
    public static final int LAMP_TICKS = 20;
    /** The door's swing (deg about its rear hinge) and the beam's tilt below forward when out. */
    public static final float LAMP_DEG = 105F, LAMP_PITCH = 15F;

    /**
     * The landing light wanted out: its switch on (stage D2), flaps >= LAMP_FLAPS, or airborne under LAMP_AGL descending
     * (vy < LAMP_SINK b/t) - once out, it stays out on the approach while under LAMP_AGL + 2 and not climbing (no flapping).
     */
    public static boolean lampOut(boolean out, boolean switchOn, double flapDeg, boolean airborne, double agl, double vy) {
        if (switchOn || flapDeg >= LAMP_FLAPS) {
            return true;
        }
        if (!airborne) {
            return false;
        }
        if (out) {
            return agl < LAMP_AGL + 2 && vy < 0.03;
        }
        return agl < LAMP_AGL && vy < LAMP_SINK;
    }

    /** One tick of the door's travel toward out (1) / in (0), 1 / LAMP_TICKS a tick; animate off: it jumps. */
    public static float lampStep(float level, boolean out, boolean animate) {
        if (!animate) {
            return out ? 1F : 0F;
        }
        float s = 1F / LAMP_TICKS, n = out ? level + s : level - s;
        return n >= 0.999F ? 1F : n <= 0.001F ? 0F : n;          // snapped at the ends (float steps)
    }
}
