package com.sva.vehicle;

/**
 * Liner wave 7 (deferred from waves 2 / 3; mock-up veh_liner_7 «ПОЖАР»): the SA-320's engine and APU fire protection - pure
 * server logic (LinerSystems ticks it; synced in its words). Kept simple: a fire starts only by injection (the tests, a
 * command) or an extreme overheat (an engine's EGT over EGT_FIRE, the APU's over APU_EGT_FIRE).
 * <p>
 * Detection: the loops see it at once - the red «ДВ 1 ПОЖАР» / «ВСУ ПОЖАР» (the continuous repetitive chime), the handle
 * and the pedestal's ПОЖАР lamp light red. The FIRE handle (the A320's FIRE push button: pulled here) cuts the fuel,
 * the bleed, the generator and arms the squibs (the agent buttons' white «ПИРОПАТР.»); an AGENT button discharges its
 * bottle (amber «РАЗРЯЖ.»): AGENT_TICKS later the fire is out with the chance EXT_CHANCE (the second bottle always puts
 * it out). The APU on the ground shuts itself down and discharges its agent after APU_AUTO ticks. An engine left burning
 * for BURN_FAIL ticks is lost (a flame-out that no relight brings back until the fire is out). TEST: the handle and the
 * warning for TEST_TICKS.
 */
public final class LinerFire {

    /** Zones (the overhead's order: ДВ 1, ВСУ, ДВ 2). */
    public static final int ENG1 = 0, APU = 1, ENG2 = 2, ZONES = 3;
    /** Bottles per zone (an engine 2, the APU 1). */
    public static final int[] BOTTLES = {2, 1, 2};
    /** EGT (°C) over which an engine / the APU catches fire. */
    public static final double EGT_FIRE = 1000, APU_EGT_FIRE = 900;
    /** Ticks: an agent's effect, the APU's automatic discharge on the ground, the test, an engine burning until it is lost. */
    public static final int AGENT_TICKS = 60, APU_AUTO = 60, TEST_TICKS = 60, BURN_FAIL = 1200;
    /** The first bottle's chance to put the fire out. */
    public static final double EXT_CHANCE = 0.7;

    public final boolean[] burning = new boolean[ZONES], pulled = new boolean[ZONES], lost = new boolean[ZONES];
    /** Bottles discharged (bit per bottle), ticks since the last discharge (-1 none), burning ticks, test ticks. */
    public final int[] disch = new int[ZONES], since = {-1, -1, -1}, burnTicks = new int[ZONES], test = new int[ZONES];
    /** This tick: a fire started (the riders are told), went out; the APU's automatic shut-down latched. */
    public boolean startedNow, outNow, apuAuto;

    /** A fire starts in zone z (injection / overheat). */
    public void ignite(int z) {
        if (!burning[z]) {
            burning[z] = true;
            burnTicks[z] = 0;
            startedNow = true;
        }
    }

    /**
     * One tick: the handles (pulled), the overheat, the agents' effect, the APU's automatic action. `random` 0..1 decides
     * the first bottle; `ground` on the ground; `egt` the engines' and the APU's EGT; `dc` the squibs' power.
     */
    public void tick(boolean[] handles, double egt1, double egtApu, double egt2, boolean ground, boolean dc, double random) {
        startedNow = outNow = false;
        double[] egt = {egt1, egtApu, egt2};
        for (int z = 0; z < ZONES; z++) {
            pulled[z] = handles[z] || z == APU && apuAuto;
            if (test[z] > 0) {
                test[z]--;
            }
            if (egt[z] > (z == APU ? APU_EGT_FIRE : EGT_FIRE)) {
                ignite(z);
            }
            if (since[z] >= 0) {
                since[z]++;
            }
            if (!burning[z]) {
                continue;
            }
            burnTicks[z]++;
            if (z != APU && burnTicks[z] >= BURN_FAIL) {
                lost[z] = true;
            }
            // the APU on the ground: shut down and its bottle out by itself
            if (z == APU && ground && burnTicks[z] >= APU_AUTO && disch[z] == 0 && dc) {
                apuAuto = true;
                pulled[z] = true;
                disch[z] = 1;
                since[z] = 0;
            }
            if (since[z] >= AGENT_TICKS) {
                int n = Integer.bitCount(disch[z]);
                if (n >= 2 || n == BOTTLES[z] || random < EXT_CHANCE) {
                    burning[z] = false;
                    outNow = true;
                    if (z == APU) {
                        apuAuto = false;
                    }
                }
                since[z] = -1;
            }
        }
    }

    /** An AGENT button pressed: zone z's bottle b (the handle pulled - the squib armed - and power). @return discharged */
    public boolean discharge(int z, int b, boolean dc) {
        if (!pulled[z] || !dc || b >= BOTTLES[z] || (disch[z] & 1 << b) != 0) {
            return false;
        }
        disch[z] |= 1 << b;
        since[z] = 0;
        return true;
    }

    public boolean discharged(int z, int b) {
        return (disch[z] & 1 << b) != 0;
    }

    /** The handle's red light / the pedestal's ПОЖАР lamp: burning or tested. */
    public boolean lamp(int z) {
        return burning[z] || test[z] > 0;
    }

    /** The agent button's white «ПИРОПАТР.» (armed, not yet fired). */
    public boolean squib(int z, int b) {
        return pulled[z] && !discharged(z, b);
    }

    /** All quiet: the save / a new flight. */
    public void reset() {
        for (int z = 0; z < ZONES; z++) {
            burning[z] = lost[z] = false;
            apuAuto = false;
            disch[z] = burnTicks[z] = test[z] = 0;
            since[z] = -1;
        }
    }

    /** The sync word: burning (3 bits), lost (3), discharged (2 + 1 + 2), tests (3). */
    public int pack() {
        int w = 0;
        for (int z = 0; z < ZONES; z++) {
            w |= (burning[z] ? 1 : 0) << z | (lost[z] ? 1 : 0) << (3 + z) | (test[z] > 0 ? 1 : 0) << (6 + z) | disch[z] << (9 + 2 * z);
        }
        return w;
    }

    public void unpack(int w) {
        for (int z = 0; z < ZONES; z++) {
            burning[z] = (w >> z & 1) != 0;
            lost[z] = (w >> (3 + z) & 1) != 0;
            test[z] = (w >> (6 + z) & 1) != 0 ? 1 : 0;
            disch[z] = w >> (9 + 2 * z) & 3;
        }
    }
}
