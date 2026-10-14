package com.sva.vehicle;

/**
 * Liner wave 3 (plan-next §7, mock-ups veh_liner_7 «ТОПЛ», 13b «ТОПЛ»): the SA-320's fuel system as pure logic, A320-like.
 * LinerSystems owns one, ticks it (prepare before the engines, draw after them) and keeps the three tank totals in its
 * tankL / tankC / tankR (the vehicle's H2 is the truth - LinerSystems.reconcile); this class holds what is inside them.
 * <ul>
 * <li><b>Tanks</b>: left / centre / right, hydrogen mB. Each wing tank is an INNER and an OUTER cell (OUTER_SHARE of the
 * wing): the engines draw the inner cell; when it falls under INNER_LOW the transfer valves open (latched until the next
 * refuel, as on the A320) and the outer cell drains into the inner at TRANSFER mB a tick. A refuel fills the outer cell
 * first (it spills into the inner), wings first, then the centre (LinerSystems.reconcile's order).</li>
 * <li><b>Pumps</b>: Л1 Л2 / Ц1 Ц2 / П1 П2 (LinerControls PUMP_*), electric - they need AC. The wing pumps run while their
 * push buttons are in; the centre pumps in АВТО (the mode push button out) run only while the centre has fuel, the slats
 * are in and an engine runs - «centre first» - and stop by themselves when it is empty; in ВРУЧН. they run while in. A
 * pump in but not giving pressure (a fault, its tank empty, a manual centre pump on an empty tank) lights its ОТКАЗ.</li>
 * <li><b>Feeding</b>: each engine from its own side - the centre pump of that side (Ц1 left, Ц2 right) when it runs
 * (higher pressure: the wing pumps run but give no flow), else the wing pumps, else GRAVITY from the wing (no pump of that
 * side running): fine under the gravity ceiling (LinerSystems.Env.gravAlt, absolute Y), above it the engine at more than
 * GRAV_THRUST thrust starves. КОЛЬЦЕВ. (crossfeed, the valve travels XFEED_TICKS) joins the two sides: the centre, else every
 * pumped wing, else gravity feeds both engines. An engine with no source for STARVE_TICKS flames out. The APU draws the
 * LEFT wing through its own pump (wave 1).</li>
 * <li><b>Imbalance</b>: L and R apart by more than IMBALANCE - the caution; the heavier wing gives a roll tendency
 * (rollBias, -1..1 at ROLL_FULL apart).</li>
 * <li><b>Accounting</b>: fuel used per engine (FU, reset at an engine start on the ground), the wings' temperatures
 * (cosmetic: they drift toward the outside air).</li>
 * </ul>
 */
public final class LinerFuel {

    // ---------------------------------------------------------------- numbers

    public static final int L = 0, C = 1, R = 2;
    /** Pumps in LinerControls' order: Л1 Л2 Ц1 Ц2 П1 П2. */
    public static final int P_L1 = 0, P_L2 = 1, P_C1 = 2, P_C2 = 3, P_R1 = 4, P_R2 = 5, PUMPS = 6;
    /** A wing tank's outer cell (share of the wing), the inner cell's level that opens the transfer valves (mB). */
    public static final double OUTER_SHARE = 0.15, INNER_LOW = 2000;
    /** Outer -> inner transfer (mB a tick: a full outer cell in ~30 s). */
    public static final double TRANSFER = 4;
    /** Imbalance caution (mB between the wings), full roll bias at ROLL_FULL apart. */
    public static final double IMBALANCE = 1500, ROLL_FULL = 6000;
    /** A wing's low level (mB: «ТОПЛ Л / П МАЛО»), the A320 MODE SEL fault: centre over CTR_SOME while a wing is under WING_LOW. */
    public static final double LOW_LEVEL = 1500, CTR_SOME = 250, WING_LOW = 5000;
    /** Gravity feed above the ceiling holds only up to this thrust (0..1). */
    public static final double GRAV_THRUST = 0.6;
    /** Ticks without a source before an engine flames out; the crossfeed valve's travel. */
    public static final int STARVE_TICKS = 20, XFEED_TICKS = 20;
    /** The «ЗАПРАВКА» indication lasts this long after the tanks last grew (ticks). */
    public static final int REFUEL_SHOW = 60;

    /** Source / flow bits of this tick (the ТОПЛ page's green lines). */
    public static final int S_CTR_L = 1, S_CTR_R = 2, S_WING_L = 4, S_WING_R = 8, S_GRAV_L = 16, S_GRAV_R = 32, S_FLOW_1 = 64, S_FLOW_2 = 128,
            S_FLOW_APU = 256, S_XFEED_FLOW = 512;

    // ---------------------------------------------------------------- state

    /** The tank totals (L, C, R) this tick - copied from / back into LinerSystems' tankL / C / R. */
    public final double[] qty = new double[3];
    /** The outer cells' fuel (part of the wing totals). */
    public double outerL, outerR;
    /** The transfer valves latched open (an inner cell went low; closed by a refuel). */
    public boolean xferL, xferR;
    /** A wing tank's full capacity (mB; FuelMath.tank(LINER) x SHARE_L - set by reconcile). */
    public double wingCap = 16800;
    /** Injected pump faults (bits by P_*: tests, a failure) - the pump gives no pressure. */
    public int faults;
    /** Pumps running / their ОТКАЗ (low pressure) lamps (bits by P_*). */
    public int running, lowPr;
    /** The crossfeed valve (0 shut .. 1 open) and fully open. */
    public double xfeedPos;
    public boolean xfeedOpen;
    /** This tick's sources and flows (S_*). */
    public int src;
    /** Ticks each engine has had no source; fed (false: it flames out). */
    public final int[] starve = new int[2];
    public final boolean[] fed = {true, true};
    /** Fuel used per engine (mB) since its last start on the ground. */
    public final double[] used = new double[2];
    /** The wings' fuel temperatures (°C, cosmetic). */
    public double tempL = 15, tempR = 15;
    /** Ticks left of the «ЗАПРАВКА» indication. */
    public int refuel;
    /** The mode push button's fault (A320 MODE SEL FAULT: centre fuel unused while a wing runs low). */
    public boolean modeFault;

    /** What the systems give the fuel each tick. */
    public static final class In {
        /** The pumps' push buttons in (P_* order), the centre mode ВРУЧН., the crossfeed push button in. */
        public final boolean[] pb = new boolean[PUMPS];
        public boolean manual, xfeed;
        /** AC on board (the pumps), the slats in, the engines running, their thrust (0..1), on the ground. */
        public boolean ac, slatsIn = true, ground = true;
        public final boolean[] run = new boolean[2];
        public final double[] thrust = new double[2];
        /** The altitude (absolute Y) and the gravity-feed ceiling; the outside air (°C). */
        public double alt = 64, gravAlt = 140, sat = 15;
        /** Tests: no valve travel. */
        public boolean instant;
    }

    // ---------------------------------------------------------------- the tick

    /** The wing tank s (L / R) - inner + outer. */
    public double wing(int s) {
        return qty[s == L ? L : R];
    }

    /** The inner cell of wing s. */
    public double inner(int s) {
        return Math.max(0, wing(s) - (s == L ? outerL : outerR));
    }

    /** The outer cell's capacity (mB). */
    public double outerCap() {
        return wingCap * OUTER_SHARE;
    }

    /** The outer cells kept consistent with the wing totals (a test or a save may set the totals alone). */
    void normalise() {
        for (int t = 0; t < 3; t++) {
            if (qty[t] < 0.01) {
                qty[t] = 0;                                         // dust (the H2 bookkeeping's fractions) is a dry tank
            }
        }
        double oc = outerCap(), ic = wingCap - oc;
        outerL = Math.max(Math.max(0, qty[L] - ic), Math.min(outerL, Math.min(oc, qty[L])));
        outerR = Math.max(Math.max(0, qty[R] - ic), Math.min(outerR, Math.min(oc, qty[R])));
    }

    /** Is pump p's push button a centre pump. */
    public static boolean centre(int p) {
        return p == P_C1 || p == P_C2;
    }

    /** The pump's tank. */
    static int tankOf(int p) {
        return p < 2 ? L : p < 4 ? C : R;
    }

    /**
     * Before the engines tick: the pumps, the crossfeed valve, the transfer, which sources each side has and whether each
     * engine is fed (its starve counter).
     */
    public void prepare(In in) {
        normalise();
        // ---- the pumps
        running = 0;
        lowPr = 0;
        boolean anyRun = in.run[0] || in.run[1];
        for (int p = 0; p < PUMPS; p++) {
            if (!in.pb[p] || !in.ac) {
                continue;
            }
            boolean ok = (faults & 1 << p) == 0;
            double q = qty[tankOf(p)];
            if (centre(p) && !in.manual) {
                // АВТО: centre first - while it has fuel, the slats are in and an engine runs; stops by itself when empty
                if (q > 0.01 && in.slatsIn && anyRun) {
                    if (ok) {
                        running |= 1 << p;
                    } else {
                        lowPr |= 1 << p;
                    }
                }
            } else if (ok && q > 0.01) {
                running |= 1 << p;
            } else {
                lowPr |= 1 << p;                                    // in, but no pressure: a fault or its tank dry
            }
        }
        // ---- the crossfeed valve
        double step = in.instant ? 1 : 1.0 / XFEED_TICKS;
        xfeedPos = Math.max(0, Math.min(1, xfeedPos + (in.xfeed && in.ac ? step : -step)));
        xfeedOpen = xfeedPos >= 1;
        // ---- the transfer valves: open when the inner cell runs low; the outer drains into the inner
        if (inner(L) < INNER_LOW && outerL > 0) {
            xferL = true;
        }
        if (inner(R) < INNER_LOW && outerR > 0) {
            xferR = true;
        }
        if (xferL) {
            outerL = Math.max(0, outerL - (in.instant ? outerL : TRANSFER));
        }
        if (xferR) {
            outerR = Math.max(0, outerR - (in.instant ? outerR : TRANSFER));
        }
        modeFault = !in.manual && anyRun && qty[C] > CTR_SOME && (qty[L] < WING_LOW || qty[R] < WING_LOW);
        // ---- the sources, then each engine fed or starving
        src = sources(in);
        for (int i = 0; i < 2; i++) {
            boolean ok = feedable(i);
            if (in.run[i] && !ok) {
                starve[i]++;
            } else {
                starve[i] = 0;
            }
            fed[i] = starve[i] < STARVE_TICKS;
        }
        // ---- cosmetic: the wings' fuel warms / cools toward the air (+5 on the ground in the sun)
        double target = in.sat + (in.ground ? 5 : 0);
        tempL += (target - tempL) * 0.0005;
        tempR += (target - tempR) * 0.0005;
        if (refuel > 0) {
            refuel--;
        }
    }

    /** The sources of each side this tick (S_* bits; the flows are added by draw). */
    int sources(In in) {
        int s = 0;
        boolean ctrL = (running & 1 << P_C1) != 0 && qty[C] > 0.01, ctrR = (running & 1 << P_C2) != 0 && qty[C] > 0.01;
        boolean wL = (running & (1 << P_L1 | 1 << P_L2)) != 0 && qty[L] > 0.01, wR = (running & (1 << P_R1 | 1 << P_R2)) != 0 && qty[R] > 0.01;
        boolean pumpL = (running & (1 << P_L1 | 1 << P_L2)) != 0, pumpR = (running & (1 << P_R1 | 1 << P_R2)) != 0;
        // gravity: no pump of the side running; above the ceiling only at low thrust
        boolean gL = !pumpL && qty[L] > 0.01 && (in.alt < in.gravAlt || in.thrust[0] <= GRAV_THRUST);
        boolean gR = !pumpR && qty[R] > 0.01 && (in.alt < in.gravAlt || in.thrust[1] <= GRAV_THRUST);
        if (xfeedOpen) {
            // one manifold: the centre, else every pumped wing, else gravity (judged by the hungrier engine's thrust)
            if (ctrL || ctrR) {
                s |= S_CTR_L | S_CTR_R;
            } else if (wL || wR) {
                s |= (wL ? S_WING_L : 0) | (wR ? S_WING_R : 0);
            } else {
                double t = Math.max(in.thrust[0], in.thrust[1]);
                boolean ceil = in.alt < in.gravAlt || t <= GRAV_THRUST;
                s |= (qty[L] > 0.01 && ceil ? S_GRAV_L : 0) | (qty[R] > 0.01 && ceil ? S_GRAV_R : 0);
            }
            return s;
        }
        s |= ctrL ? S_CTR_L : wL ? S_WING_L : gL ? S_GRAV_L : 0;
        s |= ctrR ? S_CTR_R : wR ? S_WING_R : gR ? S_GRAV_R : 0;
        return s;
    }

    /** Engine i has a source now (a start may give it fuel; a running engine stays lit). */
    public boolean feedable(int i) {
        if (xfeedOpen) {
            return (src & (S_CTR_L | S_CTR_R | S_WING_L | S_WING_R | S_GRAV_L | S_GRAV_R)) != 0;
        }
        return i == 0 ? (src & (S_CTR_L | S_WING_L | S_GRAV_L)) != 0 : (src & (S_CTR_R | S_WING_R | S_GRAV_R)) != 0;
    }

    /**
     * After the engines: takes this tick's burn - engine i's `eng[i]` mB from its side's sources (the centre, the pumped
     * wings, gravity; a source running dry passes the rest to the next), the APU's `apu` from the left wing. @return mB
     * actually taken (the engines', the APU's)
     */
    public double[] draw(double[] eng, double apu) {
        double taken = 0;
        src &= ~(S_FLOW_1 | S_FLOW_2 | S_FLOW_APU | S_XFEED_FLOW);
        for (int i = 0; i < 2; i++) {
            if (eng[i] <= 0) {
                continue;
            }
            double need = eng[i];
            int[] order = order(i);
            for (int k = 0; k < order.length && need > 1e-9; k++) {
                need -= takeFrom(order[k], need);
            }
            double got = eng[i] - need;
            used[i] += got;
            taken += got;
            if (got > 0) {
                src |= i == 0 ? S_FLOW_1 : S_FLOW_2;
                if (xfeedOpen && (i == 0 ? (src & (S_CTR_L | S_WING_L | S_GRAV_L)) == 0 : (src & (S_CTR_R | S_WING_R | S_GRAV_R)) == 0)) {
                    src |= S_XFEED_FLOW;                            // fed across the crossfeed from the other side
                }
            }
        }
        double a = Math.min(qty[L], Math.max(0, apu));
        takeWing(L, a);
        if (a > 0) {
            src |= S_FLOW_APU;
        }
        return new double[] {taken, a};
    }

    /** The tanks engine i draws, in order (C centre, L / R wings). */
    int[] order(int i) {
        boolean ctr = (src & (xfeedOpen ? S_CTR_L | S_CTR_R : i == 0 ? S_CTR_L : S_CTR_R)) != 0;
        int own = i == 0 ? L : R, other = i == 0 ? R : L;
        int ownBits = own == L ? S_WING_L | S_GRAV_L : S_WING_R | S_GRAV_R, otherBits = other == L ? S_WING_L | S_GRAV_L : S_WING_R | S_GRAV_R;
        int n = 0;
        int[] o = new int[3];
        if (ctr) {
            o[n++] = C;
        }
        if ((src & ownBits) != 0) {
            o[n++] = own;
        }
        if (xfeedOpen && (src & otherBits) != 0) {
            o[n++] = other;
        }
        int[] r = new int[n];
        System.arraycopy(o, 0, r, 0, n);
        return r;
    }

    private double takeFrom(int tank, double mb) {
        if (tank == C) {
            double t = Math.min(qty[C], mb);
            qty[C] = qty[C] - t < 1e-9 ? 0 : qty[C] - t;            // dry is exactly dry (no float dust keeping a pump alive)
            return t;
        }
        return takeWing(tank, mb);
    }

    /** From a wing: the inner cell first (the outer only through its transfer - or the tank's last). */
    double takeWing(int s, double mb) {
        double t = Math.min(qty[s], mb);
        qty[s] = qty[s] - t < 1e-9 ? 0 : qty[s] - t;
        if (s == L) {
            outerL = Math.min(outerL, qty[L]);
        } else {
            outerR = Math.min(outerR, qty[R]);
        }
        return t;
    }

    /** The tanks just grew by these (mB, a refuel): the outer cells fill first, the transfer valves shut, «ЗАПРАВКА» shown. */
    public void refuelled(double addL, double addR) {
        double oc = outerCap();
        outerL = Math.min(oc, outerL + Math.max(0, addL));
        outerR = Math.min(oc, outerR + Math.max(0, addR));
        if (inner(L) >= INNER_LOW) {
            xferL = false;
        }
        if (inner(R) >= INNER_LOW) {
            xferR = false;
        }
        refuel = REFUEL_SHOW;
    }

    /** The wings' imbalance (mB, + = the right heavier). */
    public double imbalance() {
        return qty[R] - qty[L];
    }

    /** The roll tendency of an uneven load: -1 (left wing heavy, rolls left) .. 1. */
    public double rollBias() {
        return Math.max(-1, Math.min(1, imbalance() / ROLL_FULL));
    }

    /** Fuel used by both engines (mB). */
    public double usedTotal() {
        return used[0] + used[1];
    }

    /** Pump p runs (P_*). */
    public boolean runs(int p) {
        return (running & 1 << p) != 0;
    }
}
