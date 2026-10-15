package com.sva.airport;

/**
 * Airport wave B1 (plan-next §9, item 19): the marker beacons OM / MM / IM - small blocks on the extended centre line
 * before a threshold (block «Маркерный маяк»). Pure: the type by the place, the fan-shaped coverage over the block, the
 * aural keying and the PFD lamp's colour.
 * <ul>
 * <li>Places (AptScale): the real 7.2 km / 1.05 km / 300 m - NOMINAL 720 / 105 / 30 blocks before the threshold; a marker
 * block takes the type of the window it stands in (classify), within LATERAL of the centre line.</li>
 * <li>Coverage: a vertical fan - over the block its half length along the course grows with the height by FAN_TAN (the
 * real ±40°), its half width across by WIDE_TAN (the fan is wide across), plus a minimum footprint near the ground.</li>
 * <li>Aural (the real 400 Hz dashes 2/s, 1300 Hz dot-dash, 3000 Hz dots 6/s - approximated with vanilla note pitches,
 * PITCH / SOUND): keyAt(type, tick) - 0 silence, 1 a tone starts, 2 a tone goes on.</li>
 * <li>Lamps on the PFD: OM cyan (the real blue), MM amber, IM white - lit while the tone is on.</li>
 * </ul>
 */
public final class MarkerMath {

    public static final int NONE = -1, OM = 0, MM = 1, IM = 2;
    public static final String[] NAMES = {"OM", "MM", "IM"};
    /** Nominal distances before the threshold (blocks) and the windows a marker block is classified by. */
    public static final double[] NOMINAL = {720, 105, 30};
    public static final double[][] WINDOW = {{450, 1000}, {60, 200}, {12, 59.9}};
    /** Off the centre line at most (blocks). */
    public static final double LATERAL = 6;
    /** The fan: tan of its half angle along (40°) and across, the footprint near the ground (blocks). */
    public static final double FAN_TAN = 0.84, WIDE_TAN = 2.5, MIN_ALONG = 2, MIN_ACROSS = 4, MAX_HEIGHT = 300;
    /** The tones' vanilla note sounds and pitches (OM low, MM middle, IM high). */
    public static final String[] SOUND = {"note.bass", "note.harp", "note.pling"};
    public static final float[] PITCH = {0.7F, 1.2F, 2.0F};

    private MarkerMath() {
    }

    /** The type of a marker `before` blocks before a threshold and `lateral` off the centre line, or NONE. */
    public static int classify(double before, double lateral) {
        if (Math.abs(lateral) > LATERAL) {
            return NONE;
        }
        for (int t = OM; t <= IM; t++) {
            if (before >= WINDOW[t][0] && before <= WINDOW[t][1]) {
                return t;
            }
        }
        return NONE;
    }

    /** An aircraft `h` blocks over the marker, `da` along the course and `dc` across from it, is in its fan. */
    public static boolean inFan(double h, double da, double dc) {
        if (h < 0 || h > MAX_HEIGHT) {
            return false;
        }
        double along = MIN_ALONG + h * FAN_TAN, across = MIN_ACROSS + h * WIDE_TAN;
        double u = da / along, v = dc / across;
        return u * u + v * v <= 1;
    }

    /**
     * The keying at world tick `tick` (20 a second): OM dashes 2 a second (on 6 ticks of 10), MM dot-dash (dash 6, gap 2,
     * dot 2, gap 2 - 12 ticks), IM dots 6 a second (on 1 of 3). 0 silence, 1 a tone starts, 2 it goes on.
     */
    public static int keyAt(int type, long tick) {
        int t;
        switch (type) {
            case OM:
                t = (int) (tick % 10);
                return t == 0 ? 1 : t < 6 ? 2 : 0;
            case MM:
                t = (int) (tick % 12);
                return t == 0 || t == 8 ? 1 : t < 6 || t == 9 ? 2 : 0;
            case IM:
                t = (int) (tick % 3);
                return t == 0 ? 1 : 0;
            default:
                return 0;
        }
    }

    /** The PFD lamp's colour (0xRRGGBB). */
    public static int lampColour(int type) {
        return type == OM ? 0x46C8FF : type == MM ? 0xF0B040 : 0xF4F4F4;
    }
}
