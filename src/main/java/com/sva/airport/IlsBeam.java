package com.sva.airport;

import com.sva.vehicle.LinerIls;

/**
 * Airport wave B1 (plan-next §9, items 15-16, 19; mock-up veh_airport_impl_*): the REAL beam of a FULL ILS station - a
 * localizer array beyond the far end of the runway and a glide slope mast beside its touchdown zone (block.TileEntityIlsSVA
 * pairs them, airport.IlsPairing). Pure geometry: the server's AFS, every client's PFD / ND and the self-test run the same
 * code, so nothing but the tuned station has to be synced. The scale is AptScale's (1 block = 10 m-equivalent, real
 * angles).
 * <ul>
 * <li><b>LOC</b>: the beam's centre line runs along the landing course THROUGH THE ARRAY (an array set off the runway's
 * centre line shifts the whole course - the category and the flight inspection see it). Full scale is LOC_FULL either
 * side (2 dots, a dot LOC_DOT); beyond it the needle stays pegged (the clearance signal) out to the coverage: within
 * LOC_NARROW deg to LOC_NARROW_RANGE blocks (25 km), within LOC_WIDE deg to LOC_WIDE_RANGE (18 km). Behind the array -
 * the BACK COURSE: the same signal, so a pilot flying the reciprocal sees it reversed; no glide slope there, and the
 * SA-320 (an A320) has no back-course mode - its LOC* never captures one.</li>
 * <li><b>G/S</b>: the null-reference glide slope's difference in depth of modulation versus the elevation angle e seen
 * from the mast's foot is a sine: ddm(e) = -(θ/π) sin(π e / θ) (deg-equivalent; = e - θ near θ). It is zero - a «path» -
 * at θ, 2θ and 3θ: the 6° one (for θ = 3°) with its sense REVERSED (fly-up above it, a repelling path: an autopilot
 * slides off it), the 9° one with the normal sense - an AP capturing from above can lock onto it and fly a 9° descent
 * (abnormal pitch and sink on the PFD; LinerIls.Dev.lobe tells which). Full scale is GS_FULL (2 dots of GS_DOT); the
 * coverage: GS_LAT deg either side of the course line seen from the mast, GS_RANGE blocks, from GS_LO θ (under it - no
 * usable signal) to GS_HI θ (above the third lobe).</li>
 * <li><b>Bends</b>: a block or a vehicle in a critical area (IlsCat) bends the beam - the station's amplitudes (deg)
 * oscillate with BEND_PERIOD ticks of world time (the LOC and the G/S a quarter period apart, the station's phase), the
 * same on every side.</li>
 * <li><b>DME</b>: the slant distance to the threshold (the A320 shows the ILS DME bottom left on the PFD) - km-equivalent
 * on the displays (AptScale.kmText).</li>
 * <li><b>Morse</b>: the ident (Latin or Cyrillic letters, digits) keyed at MORSE_UNIT ticks a dot, repeated every
 * MORSE_PERIOD ticks; the cockpit hears it with LS pressed (the entity plays a vanilla note at each element).</li>
 * </ul>
 */
public final class IlsBeam {

    /** LOC: full scale (deg either side, 2 dots), a dot, the needle's peg, the coverage (deg / blocks). */
    public static final double LOC_FULL = 2.5, LOC_DOT = LOC_FULL / 2, LOC_SAT = LOC_FULL * 1.2, LOC_NARROW = 10, LOC_NARROW_RANGE = 2500,
            LOC_WIDE = 35, LOC_WIDE_RANGE = 1800;
    /** G/S: full scale (deg, 2 dots), a dot, the lateral coverage (deg), the range (blocks), the vertical coverage (x θ). */
    public static final double GS_FULL = 0.7, GS_DOT = GS_FULL / 2, GS_LAT = 8, GS_RANGE = 1800, GS_LO = 0.3, GS_HI = 3.5;
    /** The beam bends' period (ticks) - a block or a vehicle standing in a critical area. */
    public static final double BEND_PERIOD = 160;

    private IlsBeam() {
    }

    // ---------------------------------------------------------------- the glide slope's lobes

    /** The glide slope's indicated deviation (deg-equivalent, + above / fly down) at elevation e (deg) for the angle θ. */
    public static double gsDdm(double e, double theta) {
        double t = Math.max(0.5, theta);
        return -(t / Math.PI) * Math.sin(Math.PI * e / t);
    }

    /** The lobe at elevation e: 0 under 0.5 θ, else the nearest path's number (1 the real one, 2 = 2θ, 3 = 3θ, 4). */
    public static int lobe(double e, double theta) {
        double t = Math.max(0.5, theta);
        if (e < 0.5 * t) {
            return 0;
        }
        return (int) Math.max(1, Math.min(4, Math.round(e / t)));
    }

    /** The lobe's path is reversed (fly-up above it): the even ones. */
    public static boolean reversed(int lobe) {
        return lobe > 0 && lobe % 2 == 0;
    }

    /** A bend's value now: the amplitude x a sine of world time, the station's phase (0..999) and a channel's offset (0..1). */
    public static double bend(double amp, double time, int phase, double offset) {
        if (amp == 0) {
            return 0;
        }
        return amp * Math.sin(2 * Math.PI * (time / BEND_PERIOD + phase / 1000.0 + offset));
    }

    /** The localizer's coverage at `ang` deg off the course line (seen from the array) and `dist` blocks from it. */
    public static boolean locCover(double ang, double dist, double range) {
        double a = Math.abs(ang);
        double max = a <= LOC_NARROW ? LOC_NARROW_RANGE : a <= LOC_WIDE ? LOC_WIDE_RANGE : -1;
        return dist <= Math.min(max, range);
    }

    // ---------------------------------------------------------------- the geometry

    /** A FULL station's geometry for an aircraft at (x, y, z), world time `time` (LinerIls.dev's FULL branch). */
    public static LinerIls.Dev dev(LinerIls.Station s, double x, double y, double z, double range, double time) {
        LinerIls.Dev d = new LinerIls.Dev();
        d.locDot = LOC_DOT;
        double c = Math.toRadians(s.course);
        double fx = Math.sin(c), fz = -Math.cos(c), rx = Math.cos(c), rz = Math.sin(c);
        double tx = s.tx(), tz = s.tz();
        double dx = x - tx, dz = z - tz;
        d.along = dx * fx + dz * fz;
        double crossGeo = dx * rx + dz * rz;
        d.dist = Math.sqrt(dx * dx + dz * dz + (y - s.elev) * (y - s.elev));
        // LOC: the course line through the array
        double la = (s.lx - tx) * fx + (s.lz - tz) * fz, lc = (s.lx - tx) * rx + (s.lz - tz) * rz;
        double toLoc = la - d.along;
        d.backCourse = toLoc < 0;
        double reach = Math.max(1, Math.abs(toLoc));
        double angTrue = Math.toDegrees(Math.atan2(crossGeo - lc, reach));
        double hl = Math.sqrt(toLoc * toLoc + (crossGeo - lc) * (crossGeo - lc));
        double locRange = d.backCourse ? range / 2 : range;
        d.inCover = locCover(angTrue, hl, locRange);
        boolean off = s.off();
        d.locFail = off || (s.flags & LinerIls.F_LOC_FAIL) != 0;
        double ind = angTrue + bend(s.bendLoc, time, s.phase, 0);
        ind = Math.max(-LOC_SAT, Math.min(LOC_SAT, ind));
        d.loc = ind;
        d.cross = lc + Math.tan(Math.toRadians(ind)) * reach;
        d.locValid = d.inCover && !d.locFail;
        // G/S: the elevation over the mast's foot
        double theta = s.angle;
        double ga = (s.gx - tx) * fx + (s.gz - tz) * fz;
        double beforeGs = ga - d.along;
        double hg = Math.sqrt((x - s.gx) * (x - s.gx) + (z - s.gz) * (z - s.gz));
        double e = Math.toDegrees(Math.atan2(y - s.gy, Math.max(1e-6, hg)));
        d.elevDeg = e;
        d.lobe = lobe(e, theta);
        double g = gsDdm(e, theta) + bend(s.bendGs, time, s.phase, 0.25);
        d.gs = g;
        d.above = hg * Math.tan(Math.toRadians(g));
        d.beamY = s.gy + hg * Math.tan(Math.toRadians(theta));
        boolean present = (s.flags & LinerIls.F_NO_GS) == 0;
        d.gsFail = off || !present || (s.flags & LinerIls.F_GS_FAIL) != 0;
        double lat = Math.toDegrees(Math.atan2(Math.abs(crossGeo), Math.max(1e-6, beforeGs)));
        boolean gsCover = present && !d.backCourse && beforeGs > 1 && lat <= GS_LAT && hg <= Math.min(GS_RANGE, range) && e >= GS_LO * theta
                && e <= GS_HI * theta;
        d.gsValid = gsCover && d.locValid && !d.gsFail;
        return d;
    }

    /** The geometric cross-track offset (blocks, + right of the runway's centre line) - no beam, no bend (tests, the planner). */
    public static double crossGeo(LinerIls.Station s, double x, double z) {
        double c = Math.toRadians(s.course);
        return (x - s.tx()) * Math.cos(c) + (z - s.tz()) * Math.sin(c);
    }

    /** The DME: the slant distance (blocks) to the threshold. */
    public static double dme(LinerIls.Station s, double x, double y, double z) {
        double dx = x - s.tx(), dz = z - s.tz(), dy = y - s.elev;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The threshold crossing height (blocks) of a glide slope mast `setback` blocks along the runway from the threshold and
     * `offset` blocks off its centre line, angle θ: the path is a cone round the mast, so over the centre line at the
     * threshold it is tan θ x the distance to the mast.
     */
    public static double tch(double setback, double offset, double theta) {
        return Math.tan(Math.toRadians(theta)) * Math.sqrt(setback * setback + offset * offset);
    }

    // ---------------------------------------------------------------- Morse

    /** Ticks per Morse unit (a dot), the ident's repeat period (ticks). */
    public static final int MORSE_UNIT = 2, MORSE_PERIOD = 200;
    private static final String LATIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String[] LATIN_CODE = {".-", "-...", "-.-.", "-..", ".", "..-.", "--.", "....", "..", ".---", "-.-", ".-..", "--", "-.",
        "---", ".--.", "--.-", ".-.", "...", "-", "..-", "...-", ".--", "-..-", "-.--", "--..", "-----", ".----", "..---", "...--", "....-",
        ".....", "-....", "--...", "---..", "----."};
    private static final String CYR = "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯЁ";
    private static final String[] CYR_CODE = {".-", "-...", ".--", "--.", "-..", ".", "...-", "--..", "..", ".---", "-.-", ".-..", "--", "-.",
        "---", ".--.", ".-.", "...", "-", "..-", "..-.", "....", "-.-.", "---.", "----", "--.-", "--.--", "-.--", "-..-", "..-..", "..--",
        ".-.-", "."};

    /** A letter's code ("" unknown). */
    public static String morse(char ch) {
        char u = Character.toUpperCase(ch);
        int i = LATIN.indexOf(u);
        if (i >= 0) {
            return LATIN_CODE[i];
        }
        i = CYR.indexOf(u);
        return i >= 0 ? CYR_CODE[i] : "";
    }

    /**
     * The ident keyed: one entry a tick over MORSE_PERIOD - 0 silence, 1 a dot's start, 2 a dash's start, 3 a tone going
     * on (the rest of an element). Dot 1 unit, dash 3, 1 between elements, 3 between letters; the rest silence. An ident
     * too long for the period is cut there.
     */
    public static int[] keying(String ident) {
        int[] k = new int[MORSE_PERIOD];
        int t = 0;
        String id = ident == null ? "" : ident;
        for (int i = 0; i < id.length(); i++) {
            String code = morse(id.charAt(i));
            for (int j = 0; j < code.length(); j++) {
                boolean dash = code.charAt(j) == '-';
                int len = (dash ? 3 : 1) * MORSE_UNIT;
                for (int q = 0; q < len && t + q < MORSE_PERIOD; q++) {
                    k[t + q] = q == 0 ? (dash ? 2 : 1) : 3;
                }
                t += len + MORSE_UNIT;
            }
            t += 2 * MORSE_UNIT;
        }
        return k;
    }

    /** The keying at world tick `tick` (0 silence, 1 / 2 an element starts, 3 a tone on). */
    public static int keyAt(String ident, long tick) {
        int[] k = keying(ident);
        int i = (int) (((tick % MORSE_PERIOD) + MORSE_PERIOD) % MORSE_PERIOD);
        return k[i];
    }
}
