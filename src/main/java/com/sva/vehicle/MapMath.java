package com.sva.vehicle;

/**
 * Wave 3, ★19 «Моя техника» (mock-up docs/mockups/veh_more_7_map.png): who sees which vehicle and the map panel's
 * projection (pure - the self-test checks it).
 * <ul>
 * <li>Visibility: the owner always; anyone else only when the vehicle's access list names him (a row with Водить,
 * Багажник or Ремонт) or he carries a valid key card with one of those - an «Открыта» lock or the «Все остальные» row
 * do not put a stranger's vehicle on his map; «Только владелец» hides it from everyone else; vehicles.locks off: the
 * owner alone.</li>
 * <li>The map: north (-z) up, east (+x) right, `scale` blocks a map pixel round a centre (the player or the station);
 * a vehicle off the panel is pinned to its edge on the line from the centre, with an arrow and its distance.</li>
 * </ul>
 */
public final class MapMath {

    /** A loaded vehicle refreshes its registry card this often (ticks); the open map asks again this often (ticks). */
    public static final int UPDATE_EVERY = 40, REFRESH_EVERY = 100;
    /** Blocks a map pixel, the zoom steps (the mouse wheel / buttons). */
    public static final int[] ZOOMS = {1, 2, 4, 8, 16};
    /** At most this many vehicles go to one player (the list scrolls). */
    public static final int MAX_LIST = 64;
    /** Charge under this share: «мало заряда!». */
    public static final double LOW_CHARGE = 0.2;
    /** Seen within this many seconds: the vehicle is loaded now (the position is live). */
    public static final int LIVE_SECONDS = 10;

    private MapMath() {
    }

    /** May this player see the vehicle on his map? (listed / card: AccessMath rights, -1 none) */
    public static boolean visible(boolean owner, boolean locks, int mode, int listed, int card) {
        if (owner) {
            return true;
        }
        if (!locks || mode == AccessMath.OWNER) {
            return false;
        }
        int useful = AccessMath.DRIVE | AccessMath.CARGO | AccessMath.REPAIR;
        return listed >= 0 && (AccessMath.norm(listed) & useful) != 0 || card >= 0 && (AccessMath.norm(card) & useful) != 0;
    }

    /** World (x, z) -> map pixels from the panel's centre {px right, py down}. */
    public static double[] toMap(double wx, double wz, double cx, double cz, double scale) {
        double s = scale > 0 ? scale : 1;
        return new double[] {(wx - cx) / s, (wz - cz) / s};
    }

    /** Map pixels from the centre -> world (x, z) (a click on the panel). */
    public static double[] toWorld(double px, double py, double cx, double cz, double scale) {
        double s = scale > 0 ? scale : 1;
        return new double[] {cx + px * s, cz + py * s};
    }

    /** Inside the square panel of half-size `half` (pixels from the centre)? */
    public static boolean inside(double px, double py, double half) {
        return Math.abs(px) <= half && Math.abs(py) <= half;
    }

    /**
     * A point off the panel pinned to its edge along the line from the centre: {px, py, angle} (angle in radians,
     * atan2(py, px): 0 = east, pi/2 = south - the arrow's direction). A point inside comes back as it is.
     */
    public static double[] edge(double px, double py, double half) {
        double m = Math.max(Math.abs(px), Math.abs(py));
        double a = Math.atan2(py, px);
        if (m <= half || m == 0) {
            return new double[] {px, py, a};
        }
        double k = half / m;
        return new double[] {px * k, py * k, a};
    }

    /** Horizontal distance in whole blocks. */
    public static int distance(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2, dz = z1 - z2;
        double d = Math.sqrt(dx * dx + dz * dz);
        return Double.isNaN(d) ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.round(d));
    }

    /** The zoom step after `steps` wheel notches (+ = farther out), clamped to ZOOMS. */
    public static int zoomStep(int index, int steps) {
        return Math.max(0, Math.min(ZOOMS.length - 1, index + steps));
    }

    /** Seconds since `seen` (world ticks), never negative. */
    public static int ageSeconds(long now, long seen) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (now - seen) / 20));
    }

    public static boolean low(double charge) {
        return charge < LOW_CHARGE;
    }
}
