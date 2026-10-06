package com.sva.block;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side look of the charging plates (plan §9): the station's description packet carries its plate list and
 * state (off / charging / full); the station writes them here and asks for a re-render of its platform only when
 * either changes. A plate reads its entry while its chunk is rendered - no tile entity per plate, no block
 * updates. Cleared when the client world unloads. Render thread only (1.7.10 renders on the main thread).
 */
public final class PlateStatesSVA {

    public static final int OFF = 0, CHARGING = 1, FULL = 2;

    private static final Map<Long, Integer> STATES = new HashMap<Long, Integer>();

    private PlateStatesSVA() {
    }

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int get(int x, int y, int z) {
        Integer s = STATES.get(Long.valueOf(key(x, y, z)));
        return s == null ? OFF : s.intValue();
    }

    public static void put(int x, int y, int z, int state) {
        if (state == OFF) {
            STATES.remove(Long.valueOf(key(x, y, z)));
        } else {
            STATES.put(Long.valueOf(key(x, y, z)), Integer.valueOf(state));
        }
    }

    public static void clear() {
        STATES.clear();
    }

    public static int size() {
        return STATES.size();
    }
}
