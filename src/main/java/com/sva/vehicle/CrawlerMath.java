package com.sva.vehicle;

/**
 * Tracked movement (Movement.CRAWLER: the cargo crawler, the drill vehicle) and the crawler's cargo / trailer rules.
 * Pure - the self-test checks it.
 * <ul>
 * <li>Tracks turn on the spot (no speed needed); reversing turns the other way round, like wheels; without EU the
 * motor still turns them, slowly (x0.3).</li>
 * <li>Steps up 2 blocks (VehicleType.stepHeight = STEP); the server's move check lets a 2-block step through.</li>
 * <li>No slip: the hull moves along its facing (the ground physics never slides).</li>
 * <li>Water: it floats (DRAFT blocks deep) and swims across at WATER_SPEED of its top speed; lava stops it.</li>
 * </ul>
 */
public final class CrawlerMath {

    public static final float STEP = 2.0F;
    /** Swimming speed x top speed; how deep it sits in water; lift a tick while under; the fastest rise. */
    public static final double WATER_SPEED = 0.5, DRAFT = 0.6, BUOYANCY = 0.06, MAX_RISE = 0.08;
    /** Turning without EU (the crawl): x this of the turn rate. */
    public static final double NO_POWER_TURN = 0.3;
    /** Slots on one cargo page (the vanilla large chest: 6 x 9). */
    public static final int PAGE = 54;
    /** Trailer rules: attach / detach results. */
    public static final int OK = 0, NO_HITCH = 1, ALREADY = 2, NOT_OWNER = 3, NONE = 4;

    private CrawlerMath() {
    }

    /**
     * Yaw change (degrees) this tick. steer = right - left; thrust: the motor may work (not wrecked / overheated);
     * energy: EU left. Tracks need no speed to turn.
     */
    public static double turn(double turnRate, int steer, double speed, boolean thrust, boolean energy) {
        if (steer == 0 || !thrust) {
            return 0;
        }
        double dir = speed < -0.01 ? -1 : 1;
        return steer * turnRate * dir * (energy ? 1 : NO_POWER_TURN);
    }

    /** Vertical speed in water: submerged deeper than DRAFT it rises (at most MAX_RISE), else it falls as usual. */
    public static double floatVy(double motionY, boolean submerged) {
        if (submerged) {
            return Math.min(MAX_RISE, motionY * 0.5 + BUOYANCY);
        }
        return (motionY - 0.08) * 0.98;
    }

    /** Highest climb a tick the server accepts (blocks), for a vehicle stepping `step` blocks. */
    public static double climbLimit(float step) {
        return Math.max(1.1, step + 0.1);
    }

    /** Cargo pages (54 slots each). */
    public static int pages(int slots) {
        return Math.max(1, (slots + PAGE - 1) / PAGE);
    }

    /** Slots on `page` (0-based) of `slots`. */
    public static int slotsOn(int slots, int page) {
        return Math.max(0, Math.min(PAGE, slots - page * PAGE));
    }

    /** May a trailer be hitched? trailerSlots: the type's; manage: the owner (or an op). */
    public static int attachCheck(int trailerSlots, boolean hasTrailer, boolean manage) {
        if (trailerSlots <= 0) {
            return NO_HITCH;
        }
        if (!manage) {
            return NOT_OWNER;
        }
        return hasTrailer ? ALREADY : OK;
    }

    /** May the trailer be unhitched (it goes to the player as an item with its cargo)? */
    public static int detachCheck(boolean hasTrailer, boolean manage) {
        if (!hasTrailer) {
            return NONE;
        }
        return manage ? OK : NOT_OWNER;
    }
}
