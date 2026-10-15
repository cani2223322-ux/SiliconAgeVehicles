package com.sva.airport;

/**
 * Airport wave B1 (plan-next §9, items 13-26, 39, 40): the ONE scale of the airport's ground infrastructure, so every class
 * of the wave (and the README) quotes the same numbers.
 * <p>
 * <b>1 block = 10 m-equivalent</b> for everything laid out on the ground round a runway - the antennas' places, the
 * markers, the approach lights, the critical areas, the lights' spacing, the coverage of the beams, the DME and the RVR.
 * A real 3000 m runway is a 300-block strip; the outer marker at 7.2 km stands 720 blocks out; the localizer's 18 km
 * coverage is 1800 blocks. ANGLES are real (a 3° glide slope stays 3°), so heights scale the same way: the threshold
 * crossing height of 15 m is 1.5 blocks.
 * <p>
 * The aircraft are NOT rescaled (the SA-320 flies its own blocks = metres on its altimeter and its km/h on the speed
 * tape): the approach is a few times shorter in time than the real one, which keeps a landing playable. What the crew
 * reads off the ground systems is given in the real-equivalent units so the numbers look like the real ones: the DME in
 * km (blocks x 10 / 1000: «7,2» over the outer marker), the RVR in metres (550 = CAT I), the markers' distances.
 * Pure (no Minecraft classes) - the dedicated server's self-test checks it.
 */
public final class AptScale {

    /** Metres-equivalent per block of the airport's layout. */
    public static final double M_PER_BLOCK = 10;

    private AptScale() {
    }

    /** Blocks -> metres-equivalent. */
    public static double metres(double blocks) {
        return blocks * M_PER_BLOCK;
    }

    /** Metres-equivalent -> blocks. */
    public static double blocks(double metres) {
        return metres / M_PER_BLOCK;
    }

    /** Blocks -> km-equivalent (the DME). */
    public static double km(double blocks) {
        return blocks * M_PER_BLOCK / 1000.0;
    }

    /** "7,2" / "7.2": km-equivalent with one decimal and the decimal sign given. */
    public static String kmText(double blocks, char point) {
        long tenths = Math.round(Math.max(0, km(blocks)) * 10);
        return tenths / 10 + String.valueOf(point) + tenths % 10;
    }
}
