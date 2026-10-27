package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * PACKAGE S (docs/plan-embassy-airfield-addon.md §3.8, §3.9): the pure rules of the embassies' services for the add-on's machines - which
 * stand a machine takes, the stands a player may hold, the parking days, the emergency's 25%, the towing timers, the service amounts in
 * the core's measures. No Minecraft classes (the self-test runs them). The prices, levels and caps are the CORE's (EmbassyCompatSVA.quote
 * / pay decide); the numbers below only mirror the core's EmbassyAirRulesSC for the window's hints (SelfTestEmbassySVA compares them).
 * Written by the coordinator with working first bodies; S may refine them (the signatures stay). All numbers TODO(balance).
 * <p>
 * Package S's additions: the game day (the core's SettlementSC.day), the wear in %, the largest amount the core still sells today
 * (largest - the window and the payment shrink an amount the core refuses as PAY_LIMIT), the towing areas, the «Отстой» lot's places
 * (lotRow - FULL: a row along the «Аэровокзал»'s side of the apron, outside the stands; padSide - beside the helipad «Н», on its outer
 * sides from the dome), the «Лётная книжка»'s window.
 */
public final class EmbassyServiceMath {

    /** The core's EmbassyAirRulesSC mirrors, in EmbassyCompatSVA.SERVICE_KEYS' order: chips per unit, the unit, per service, per day. */
    public static final int[] BASE = {1, 1, 1, 1, 1, 1, 2, 1, 1};
    public static final int[] UNIT = {1, 1, 2000000, 8000, 4000, 10, 1, 1, 1};
    public static final int[] PER_SERVICE = {0, 0, 16000000, 48000, 0, 60, 0, 0, 0};
    public static final int[] PER_DAY = {0, 1, 32000000, 64000, 16000, 0, 0, 0, 0};
    /**
     * The core's EmbassyAirRulesSC.MIN_LEVEL / MAX_LEVEL mirrors (ReputationSC levels: 0 «Враг» .. 6 «Почётный»): the window offers the
     * emergency service only to the levels it is sold to (the core refuses it to the rest anyway) and names the level a service starts at.
     */
    public static final int[] MIN_LEVEL = {2, 1, 3, 3, 3, 3, 3, 1, 2};
    public static final int[] MAX_LEVEL = {6, 2, 6, 6, 6, 6, 6, 6, 6};
    /** The emergency service fills the tank and the buffer up to this % (the core: EMERGENCY_PCT). */
    public static final int EMERGENCY_PCT = 25;
    /** Free: the first parking day, the day's first tow, the first chart (the core counts the tow's and the chart's itself). */
    public static final int FREE_PARK_DAYS = 1, FREE_TOWS = 1, FREE_CHARTS = 1;
    /** A player holds at most this many stands at one embassy (the core: SPOTS_PER_PLAYER). */
    public static final int SPOTS_PER_PLAYER = 2;
    /** A machine counts as stopped below this speed (blocks a tick). */
    public static final double STOPPED = 0.02;
    /** The window serves the player's machines within this far of the column (blocks; the hangar's repair: in hangarBox). */
    public static final int SERVE_REACH = 16;
    /** A first landing counts when the machine stopped on a stand within this long of being in the air (ticks). */
    public static final int LANDED_WINDOW = 2400;

    /** The stands' kinds (EmbassyCompatSVA.SPOT_*); NONE - no stand fits (a ground vehicle: the «Отстой» lot only). */
    public static final int NONE = -1, SPOT_LINER = 0, SPOT_SMALL = 1, SPOT_HELI = 2;

    /** The services' indexes (EmbassyCompatSVA.S_* - the core's SERVICE_KEYS order). */
    public static final int S_PARK = 0, S_EMERGENCY = 1, S_CHARGE = 2, S_HYDROGEN = 3, S_REPAIR = 5, S_ELECTROLYTE = 6, S_TOW = 7, S_CHART = 8;
    /** The game day's length (the core's SettlementSC.DAY: the world's day time / DAY_TICKS). */
    public static final long DAY_TICKS = 24000L;
    /** More than this many parking days owed: the machine goes to the «Отстой» lot (free; its stand freed). TODO(balance) */
    public static final int DEBT_DAYS = 1;
    /** The towing's areas: none, the runway / its pads / taxiway «А» (aptEmbassyTowMinutes), under the dome (aptEmbassyDomeMinutes). */
    public static final int AREA_NONE = 0, AREA_STRIP = 1, AREA_DOME = 2;
    /** The «Отстой» lot: its places this far apart along the row / the helipad's side, this far in from the apron's edges and off the stands. */
    public static final int LOT_STEP = 8, LOT_INSET = 4, LOT_CLEAR = 2, PAD_STEP = 4, PAD_OUT = 2;
    /** A lot place is taken while a machine stands closer than this to it (blocks across). */
    public static final double LOT_TAKEN = 3;
    /** A booked machine found farther than this from its stand's box (blocks) has left it (its card in the vehicle registry). */
    public static final int LEFT_STAND = 24;

    private EmbassyServiceMath() {
    }

    /** The stand kind a machine of type `t` belongs on first: the liner МС-1, the vertical ones «Н», the planes МС-2..4, the rest none. */
    public static int spotKindFor(VehicleType t) {
        if (t == null) {
            return NONE;
        }
        if (t == VehicleType.LINER) {
            return SPOT_LINER;
        }
        if (t.movement == Movement.HELI || t.movement == Movement.TILT || t.movement == Movement.HOVER || t.bridgeDock) {
            return SPOT_HELI;
        }
        return t.movement.flies ? SPOT_SMALL : NONE;
    }

    /** A machine of type `t` may stand on a stand of kind `spot` (the liner only on МС-1; «Н» also takes what hovers; МС-2..4 any small). */
    public static boolean fits(VehicleType t, int spot) {
        int k = spotKindFor(t);
        if (k == NONE) {
            return false;
        }
        if (k == SPOT_LINER) {
            return spot == SPOT_LINER;
        }
        if (spot == SPOT_LINER) {
            return false;
        }
        return spot == SPOT_SMALL || k == SPOT_HELI;
    }

    /** The owner may take one more stand at this embassy (he holds `held` there now). */
    public static boolean mayPark(int held) {
        return held < SPOTS_PER_PLAYER;
    }

    /** The parking days owed (game days) for a stand taken on `sinceDay`, paid through `paidDay` (-1 none), today `today`: the first day free. */
    public static int parkDaysDue(long sinceDay, long paidDay, long today) {
        long from = Math.max(sinceDay + FREE_PARK_DAYS, paidDay + 1);
        return today < from ? 0 : (int) Math.min(Integer.MAX_VALUE, today - from + 1);
    }

    /** The parking owed `due` days sends the machine to the «Отстой» lot (more than DEBT_DAYS). */
    public static boolean debtToLot(int due) {
        return due > DEBT_DAYS;
    }

    /** The stand is still in its free first day(s) (taken on `sinceDay`). */
    public static boolean freeDay(long sinceDay, long today) {
        return today < sinceDay + FREE_PARK_DAYS;
    }

    /** The emergency fills a store of `capacity` holding `have` up to EMERGENCY_PCT (0: it holds that much already). */
    public static long emergencyAmount(long have, long capacity) {
        long want = capacity * EMERGENCY_PCT / 100;
        return Math.max(0, want - Math.max(0, have));
    }

    /** A machine left (nobody aboard) since `since` (world ticks) is due to be towed after `minutes` (ConfigSVA.aptEmbassyTowMinutes / DomeMinutes). */
    public static boolean towDue(long since, long now, int minutes) {
        return since >= 0 && minutes >= 0 && now >= since && now - since >= minutes * 1200L;
    }

    /** The minutes an area's tow waits (AREA_STRIP: `stripMinutes`, AREA_DOME: `domeMinutes`; AREA_NONE: never - -1). */
    public static int towMinutes(int area, int stripMinutes, int domeMinutes) {
        return area == AREA_STRIP ? stripMinutes : area == AREA_DOME ? domeMinutes : -1;
    }

    /** The amount of a service the window offers: what is missing, within the per-service cap (0 - none), in the service's unit. */
    public static long offer(int service, long missing) {
        if (service < 0 || service >= PER_SERVICE.length || missing <= 0) {
            return 0;
        }
        long cap = PER_SERVICE[service];
        return cap > 0 ? Math.min(missing, cap) : missing;
    }

    /** The chips a service's amount would cost at the base price (the core adds the level's factor and its ±10%) - the window's hint only. */
    public static long hint(int service, long amount) {
        if (service < 0 || service >= UNIT.length || amount <= 0) {
            return 0;
        }
        return BASE[service] * ((amount + UNIT[service] - 1) / UNIT[service]);
    }

    // ------------------------------------------------------------------ package S's additions

    /** The core's game day (SettlementSC.day: the world's day time / DAY_TICKS; a negative time: day 0). */
    public static long day(long worldTime) {
        return Math.max(0L, worldTime) / DAY_TICKS;
    }

    /** The service is sold at `level` (the mirrors MIN_LEVEL..MAX_LEVEL; a bad service or level: false). */
    public static boolean levelSells(int service, int level) {
        return service >= 0 && service < MIN_LEVEL.length && level >= MIN_LEVEL[service] && level <= MAX_LEVEL[service];
    }

    /** The hull's wear in % (missing HP of `max`, rounded up; 0 whole or a bad max). */
    public static int wearPct(double hp, double max) {
        if (!(max > 0) || !(hp < max)) {
            return 0;
        }
        double m = (max - Math.max(0, hp)) / max * 100;
        return (int) Math.max(0, Math.min(100, Math.ceil(m - 1e-6)));
    }

    /** The HP a repair of `pct` % of wear gives back to a hull of `max`. */
    public static double repairHp(int pct, double max) {
        return max > 0 && pct > 0 ? max * pct / 100.0 : 0;
    }

    /** One step of largest(): may the core sell `amount`? */
    public interface Probe {
        boolean ok(long amount);
    }

    /**
     * The largest amount within 0..`hi` the probe still accepts (the core's caps: PAY_LIMIT refuses every amount past what is left today,
     * so the answers are true up to some amount, false past it) - a binary search, at most ~64 probes; 0: none (or `hi` <= 0).
     */
    public static long largest(long hi, Probe p) {
        if (hi <= 0 || p == null) {
            return 0;
        }
        if (p.ok(hi)) {
            return hi;
        }
        long lo = 0, top = hi;                              // ok(lo) taken for granted (nothing), !ok(top)
        while (top - lo > 1) {
            long mid = lo + (top - lo) / 2;
            if (p.ok(mid)) {
                lo = mid;
            } else {
                top = mid;
            }
        }
        return lo;
    }

    /**
     * The towing's area of a column: under the dome (`dome`) - AREA_DOME; on the strip, a turning pad or taxiway «А» - AREA_STRIP; else
     * AREA_NONE (the apron, the stands, the helipad, the grass).
     */
    public static int area(boolean dome, boolean strip, boolean pad, boolean taxi) {
        return dome ? AREA_DOME : strip || pad || taxi ? AREA_STRIP : AREA_NONE;
    }

    /** The «Лётная книжка»: in the air at `lastAir` (world ticks; 0 never), stopped now on a stand with the transponder answering. */
    public static boolean landed(long lastAir, long now, boolean xpdr) {
        return xpdr && lastAir > 0 && now >= lastAir && now - lastAir <= LANDED_WINDOW;
    }

    // ------------------------------------------------------------------ review 2026-10-27 (appended; the rules above unchanged)

    /**
     * The towing's areas past the first three: AREA_SPOT - on a stand it does not hold (not its kind, or another machine's booking: a
     * machine there would block the stand for everyone), AREA_APRON - left on the apron off the stands (FULL; not in the hangar, not on
     * the lot). Both are towed: AREA_SPOT after the strip's minutes, AREA_APRON after airport.embassyApronMinutes.
     */
    public static final int AREA_SPOT = 3, AREA_APRON = 4;

    /** The towing's area with the review's two (see AREA_SPOT): the dome / strip first, then a stand it does not hold, then the apron. */
    public static int area(boolean dome, boolean strip, boolean pad, boolean taxi, boolean wrongSpot, boolean apron) {
        int a = area(dome, strip, pad, taxi);
        return a != AREA_NONE ? a : wrongSpot ? AREA_SPOT : apron ? AREA_APRON : AREA_NONE;
    }

    /** The minutes an area's tow waits (AREA_STRIP / AREA_SPOT: `stripMinutes`, AREA_DOME: `domeMinutes`, AREA_APRON: `apronMinutes`; none: -1). */
    public static int towMinutes(int area, int stripMinutes, int domeMinutes, int apronMinutes) {
        if (area == AREA_SPOT) {
            return stripMinutes;
        }
        return area == AREA_APRON ? apronMinutes : towMinutes(area, stripMinutes, domeMinutes);
    }

    /**
     * The «Лётная книжка»'s flight in (review 2026-10-27): the last moment a machine was seen in the air OUTSIDE the field's protected
     * zone's box (`arrivedAt`, world ticks; 0 never), kept through the approach while it stays in the air (`airborne` now; LANDED_WINDOW
     * from the last one) - a hop of a machine set down on the field from the inventory is no arrival. @return the new arrivedAt
     */
    public static long arrival(long arrivedAt, boolean airborne, boolean outside, long now) {
        if (airborne && outside) {
            return now;
        }
        return airborne && arrivedAt > 0 && now >= arrivedAt && now - arrivedAt <= LANDED_WINDOW ? now : arrivedAt;
    }

    /** The middle block {x, z} of a box {minX, minZ, maxX, maxZ}. */
    public static int[] centre(int[] box) {
        return new int[] {(box[0] + box[2]) / 2, (box[1] + box[3]) / 2};
    }

    /** The column (x, z) lies in `box` grown by `g` blocks on every side (null: never). */
    public static boolean inGrown(int[] box, int x, int z, int g) {
        return box != null && box.length >= 4 && x >= box[0] - g && x <= box[2] + g && z >= box[1] - g && z <= box[3] + g;
    }

    /**
     * FULL's «Отстой» lot: one row of places in the apron along its edge nearest `terminal` (the «Аэровокзал»), LOT_INSET in from that
     * edge and from the row's ends, every LOT_STEP - each kept only when LOT_CLEAR or more off every `avoid` box (the stands, the
     * monument). {x, z} blocks, in order along the row. Empty without an apron.
     */
    public static List<int[]> lotRow(int[] apron, int[] terminal, int[][] avoid) {
        List<int[]> out = new ArrayList<int[]>();
        if (apron == null || apron.length < 4) {
            return out;
        }
        // the edge nearest the terminal (one it lies beside first): 0 minX, 1 maxX, 2 minZ, 3 maxZ
        int edge = 2;
        if (terminal != null && terminal.length >= 4) {
            double tx = (terminal[0] + terminal[2]) / 2.0, tz = (terminal[1] + terminal[3]) / 2.0;
            double[] d = {Math.abs(tx - apron[0]), Math.abs(tx - apron[2]), Math.abs(tz - apron[1]), Math.abs(tz - apron[3])};
            boolean inZ = tz >= apron[1] && tz <= apron[3], inX = tx >= apron[0] && tx <= apron[2];
            boolean[] beside = {inZ, inZ, inX, inX};
            double best = Double.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                double v = d[i] + (beside[i] ? 0 : 1000);
                if (v < best) {
                    best = v;
                    edge = i;
                }
            }
        }
        boolean alongZ = edge < 2;                          // an X edge: the row runs along Z
        int fixed = edge == 0 ? apron[0] + LOT_INSET : edge == 1 ? apron[2] - LOT_INSET : edge == 2 ? apron[1] + LOT_INSET : apron[3] - LOT_INSET;
        int from = (alongZ ? apron[1] : apron[0]) + LOT_INSET, to = (alongZ ? apron[3] : apron[2]) - LOT_INSET;
        for (int c = from; c <= to; c += LOT_STEP) {
            int x = alongZ ? fixed : c, z = alongZ ? c : fixed;
            if (!near(avoid, x, z, LOT_CLEAR)) {
                out.add(new int[] {x, z});
            }
        }
        return out;
    }

    /**
     * The «Отстой» lot beside the helipad «Н» (SHORT / HELI, and FULL's next places): PAD_OUT outside its two edges farthest from the core
     * (the outer sides from the dome), every PAD_STEP along each - the caller keeps those in the field's zone. {x, z} blocks.
     */
    public static List<int[]> padSide(int[] pad, int coreX, int coreZ) {
        List<int[]> out = new ArrayList<int[]>();
        if (pad == null || pad.length < 4) {
            return out;
        }
        double cx = (pad[0] + pad[2]) / 2.0, cz = (pad[1] + pad[3]) / 2.0;
        // each edge's distance from the core at its middle: 0 minX, 1 maxX, 2 minZ, 3 maxZ
        double[] d = {Math.hypot(pad[0] - coreX, cz - coreZ), Math.hypot(pad[2] - coreX, cz - coreZ), Math.hypot(cx - coreX, pad[1] - coreZ),
            Math.hypot(cx - coreX, pad[3] - coreZ)};
        int first = 0;
        for (int i = 1; i < 4; i++) {
            if (d[i] > d[first]) {
                first = i;
            }
        }
        int second = -1;
        for (int i = 0; i < 4; i++) {
            if ((first < 2 ? i >= 2 : i < 2) && (second < 0 || d[i] > d[second])) {
                second = i;                                 // the farther of the two edges across the first
            }
        }
        for (int e : new int[] {first, second}) {
            if (e < 0) {
                continue;
            }
            boolean alongZ = e < 2;
            int fixed = e == 0 ? pad[0] - PAD_OUT : e == 1 ? pad[2] + PAD_OUT : e == 2 ? pad[1] - PAD_OUT : pad[3] + PAD_OUT;
            int from = (alongZ ? pad[1] : pad[0]) + 1, to = (alongZ ? pad[3] : pad[2]) - 1;
            for (int c = from; c <= to; c += PAD_STEP) {
                out.add(alongZ ? new int[] {fixed, c} : new int[] {c, fixed});
            }
        }
        return out;
    }

    private static boolean near(int[][] boxes, int x, int z, int g) {
        if (boxes != null) {
            for (int[] b : boxes) {
                if (inGrown(b, x, z, g)) {
                    return true;
                }
            }
        }
        return false;
    }
}
