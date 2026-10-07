package com.sva.vehicle;

/**
 * Wave 3, ★15: the crawler's trailer kinds and their rules (pure - the self-test checks it; mock-up
 * docs/mockups/veh_more_6_trailers.png). The kind is the trailer item's metadata and VehicleState.trailerType
 * (NBT "TrailerType", append-only ids):
 * <ul>
 * <li>CARGO (0) - the old trailer: +54 cargo slots (VehicleType.trailerSlots), the «Прицеп» page of the cargo screen;</li>
 * <li>TANK - TANK_CAP mB of ONE fluid (any Forge fluid; the core's gases are fluids too, ArmorGasSC.Gas.of): filled /
 * emptied through two container slots on the cargo screen's «Прицеп» tab (buckets, cells, IFluidContainerItem); a gas
 * goes into the tanks of vehicles within REFUEL_RADIUS blocks of the trailer («Заправить технику рядом»). More than half
 * full: the crawler's top speed x TANK_SPEED;</li>
 * <li>PLATFORM - carries ONE small vehicle (platformFits: the P3x3 class - the buggy and the hoverbike; the drill is
 * 6.4 blocks long and doesn't fit the 2.3-block deck even at x0.6), kept as its item (the whole state: charge, tuning,
 * access, id); loaded with a vehicle item (RMB on the crawler) or «Погрузить ближайшую» (an empty vehicle of the
 * player's within LOAD_RADIUS blocks of the deck), unloaded beside the crawler; charged slowly from the crawler
 * (CARRY_EU a tick); loaded: top speed x PLATFORM_SPEED;</li>
 * <li>CHARGER - a CHARGER_BUFFER EU battery: the sun gives SOLAR_EU a tick (day, open sky, no rain), the crawler feeds
 * it FEED_EU a tick while driving (never below FEED_FLOOR of its own charge), and it charges vehicles within
 * CHARGE_RADIUS blocks of the trailer at the HV station rate (PlatformMath.rate), one at a time (round robin).</li>
 * </ul>
 * The trailer stands TRAILER_BACK blocks behind the crawler's origin (truck.obj: the trailers' middle at model x -2.3,
 * the model drawn moved back by modelOffsetX 1.055, x1.25).
 */
public final class TrailerMath {

    public static final int CARGO = 0, TANK = 1, PLATFORM = 2, CHARGER = 3, COUNT = 4;
    /** Lang / texture / model keys by kind. */
    public static final String[] KEYS = {"cargo", "tank", "platform", "charger"};
    /** The models drawn for the kinds (CARGO: truck.obj's own trailer part, null here). */
    public static final String[] MODELS = {null, "trailer_tank", "trailer_platform", "trailer_charger"};

    public static final int TANK_CAP = 16000;
    /** Top speed x this: a tank more than half full (-15 %), the platform with a vehicle (-20 %). */
    public static final double TANK_SPEED = 0.85, PLATFORM_SPEED = 0.80;
    /** Blocks from the trailer: vehicles refuelled by the tanker, picked up by the platform, charged by the charger. */
    public static final double REFUEL_RADIUS = 6, LOAD_RADIUS = 5, CHARGE_RADIUS = 4;
    /** The trailer's middle behind the crawler's origin (blocks): (model x -2.3 - modelOffsetX 1.055) x 1.25. */
    public static final double TRAILER_BACK = 4.2;
    /** EU: the charger's buffer; the sun's, the crawler's feed and the carried vehicle's trickle a tick. */
    public static final int CHARGER_BUFFER = 4000000, SOLAR_EU = 32, FEED_EU = 128, CARRY_EU = 32;
    public static final double FEED_FLOOR = 0.2;

    /** The platform's deck in truck.obj's model space (vehicles/w3/veh_trailers.py): top y, x span, half width. */
    public static final float DECK_Y = 0.703F, DECK_X0 = -3.256F, DECK_X1 = -1.381F, DECK_HALF_W = 0.844F;

    private TrailerMath() {
    }

    /**
     * The carried vehicle's scale in truck.obj's model space: its true size (trueScale = its modelScale over the
     * crawler's) when that fits, else at most 125 % of the deck's length (it overhangs the ramps a little, like on a car
     * carrier) / 115 % of its width (len / wid: its model's bounding box; the buggy comes out ~x0.6).
     */
    public static float carriedScale(float trueScale, float len, float wid) {
        float s = trueScale;
        if (len > 0) {
            s = Math.min(s, (DECK_X1 - DECK_X0) * 1.25F / len);
        }
        if (wid > 0) {
            s = Math.min(s, DECK_HALF_W * 2F * 1.15F / wid);
        }
        return Math.max(0.1F, s);
    }

    /** A valid kind (else CARGO - old / garbled saves). */
    public static int valid(int kind) {
        return kind >= 0 && kind < COUNT ? kind : CARGO;
    }

    /** Does this kind add the cargo slots (the old trailer)? */
    public static boolean addsCargo(int kind) {
        return valid(kind) == CARGO;
    }

    /** Can the platform carry a vehicle of this type (the 3x3 platform class: buggy, hoverbike)? */
    public static boolean platformFits(VehicleType t) {
        return t != null && t.platformSize == PlatformSize.P3x3 && t.trailerSlots == 0;
    }

    /** The crawler's top speed x this with the trailer (amount: the tank's mB; carrying: the platform holds one). */
    public static double speedMul(int kind, int tankAmount, boolean carrying) {
        switch (valid(kind)) {
            case TANK:
                return tankAmount * 2 > TANK_CAP ? TANK_SPEED : 1;
            case PLATFORM:
                return carrying ? PLATFORM_SPEED : 1;
            default:
                return 1;
        }
    }

    // ------------------------------------------------------------------ the tank

    /**
     * mB of `fluid` the tank takes now (simulated): an empty tank takes any fluid, else only the one in it.
     * @param have the fluid in the tank (null / "" = empty), amount: its mB
     */
    public static int tankRoom(String have, int amount, String fluid, int offered) {
        if (fluid == null || fluid.length() == 0 || offered <= 0) {
            return 0;
        }
        boolean empty = have == null || have.length() == 0 || amount <= 0;
        if (!empty && !have.equals(fluid)) {
            return 0;
        }
        return Math.max(0, Math.min(offered, TANK_CAP - (empty ? 0 : amount)));
    }

    /** mB that can come out for a request of `want` (any fluid if `fluid` is null, else only that one). */
    public static int tankTake(String have, int amount, String fluid, int want) {
        if (have == null || have.length() == 0 || amount <= 0 || want <= 0) {
            return 0;
        }
        if (fluid != null && !fluid.equals(have)) {
            return 0;
        }
        return Math.min(amount, want);
    }

    /** 0..1 fill of the tank. */
    public static double tankFill(int amount) {
        return Math.max(0, Math.min(1, amount / (double) TANK_CAP));
    }

    // ------------------------------------------------------------------ the mobile charger

    /** EU the sun puts in this tick (the buffer's room bounds it). */
    public static int solar(boolean day, boolean sky, boolean rain, double room) {
        return day && sky && !rain ? (int) Math.max(0, Math.min(SOLAR_EU, Math.floor(room))) : 0;
    }

    /**
     * EU the crawler gives the trailer's buffer (or the carried vehicle) this tick: only while driving, at most `rate`,
     * never taking the crawler under FEED_FLOOR of its buffer, bounded by the room.
     */
    public static int feed(boolean driving, double truckEnergy, double truckMax, double room, int rate) {
        if (!driving || truckMax <= 0 || Double.isNaN(truckEnergy) || Double.isNaN(room)) {
            return 0;
        }
        double spare = truckEnergy - truckMax * FEED_FLOOR;
        return (int) Math.max(0, Math.min(Math.min(rate, Math.floor(spare)), Math.floor(room)));
    }

    /** The carried vehicle's trickle from the crawler: also parked (the platform is wired), the same floor. */
    public static int carryCharge(double truckEnergy, double truckMax, double room) {
        return feed(true, truckEnergy, truckMax, room, CARRY_EU);
    }

    /** Where the trailer stands: {dx, dz} in the crawler's frame (forward x, right z) - TRAILER_BACK behind. */
    public static double[] trailerOffset() {
        return new double[] {-TRAILER_BACK, 0};
    }
}
