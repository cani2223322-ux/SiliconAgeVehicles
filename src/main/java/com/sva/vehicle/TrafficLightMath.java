package com.sva.vehicle;

/**
 * Wave 1, ★11: the traffic light's state machine (block/BlockTrafficLightSVA keeps the state in its metadata and ticks
 * it with a scheduled block update). Redstone power = green, none = red; every change goes through AMBER for
 * AMBER_TICKS (1 s). If the power flips back during the amber, the amber still finishes and the light then shows what the
 * power says now - it never jumps straight from red to green or back.
 */
public final class TrafficLightMath {

    public static final int RED = 0, AMBER = 1, GREEN = 2;
    public static final int AMBER_TICKS = 20;

    private TrafficLightMath() {
    }

    /** Where a steady light goes when the power is (or isn't) on: AMBER if it must change, else stays. */
    public static int onPower(int state, boolean powered) {
        if (state == AMBER) {
            return AMBER;                                       // the scheduled tick decides
        }
        int want = powered ? GREEN : RED;
        return state == want ? state : AMBER;
    }

    /** The end of the amber: what the power says now. */
    public static int amberDone(boolean powered) {
        return powered ? GREEN : RED;
    }

    /** Lamp lit: 0 red (top), 1 amber, 2 green (bottom). */
    public static boolean lit(int state, int lamp) {
        return state == lamp;
    }
}
