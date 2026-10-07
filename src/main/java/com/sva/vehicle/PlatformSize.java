package com.sva.vehicle;

/**
 * Charging-platform size classes (plan §9): the smallest platform a vehicle fits on. APPEND-ONLY (ordinal synced).
 * 3x3 hoverbike / buggy, 3x5 truck / drill, 5x5 helicopter / plane / tiltrotor, 7x7 shuttle / Singular ship.
 */
public enum PlatformSize {

    P3x3(3, 3), P3x5(3, 5), P5x5(5, 5), P7x7(7, 7);

    public final int w, h;

    PlatformSize(int w, int h) {
        this.w = w;
        this.h = h;
    }

    /** Plates a vehicle of this class needs at least (the speed bonus counts plates above it). */
    public int minPlates() {
        return w * h;
    }

    public String label() {
        return w + "x" + h;
    }
}
