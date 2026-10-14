package com.sva.vehicle;

/**
 * Liner wave 2 (plan-next §7, mock-ups veh_liner_7 «ГИДР», 13a «ГИДР»): the SA-320's three hydraulic systems as pure,
 * server-authoritative logic (LinerSystems owns one and ticks it after the engines and the electrics; the client mirror
 * gets the synced pressures, quantities and pump states; SelfTestLinerHydSVA drives it tick by tick).
 * <ul>
 * <li><b>Green</b> (ЗЕЛ): the engine-driven pump (EDP) on engine 1 - it gives pressure from N2 EDP_N2 and full at
 * EDP_FULL_N2 (an engine at idle: 3000 psi).</li>
 * <li><b>Yellow</b> (ЖЁЛТ): the EDP on engine 2 and an electric pump on AC bus 2 (ЭЛ. НАСОС ЖЁЛТАЯ - works on the ground
 * without the engines, needs AC: ground power, the APU or a generator). It charges the brake ACCUMULATOR (the parking
 * and alternate brakes: it holds the parking brake without any pump while it has pressure).</li>
 * <li><b>Blue</b> (СИН): an electric pump on AC bus 1 (АВТО: runs with an engine running or in flight) and the RAT
 * (deploys by itself in flight when every generator is lost - or by the guarded RAT ВЫПУСК; its turbine gives Blue
 * pressure by the airspeed and, over RAT_ELEC_PSI, the emergency electrics).</li>
 * <li><b>PTU</b> between Green and Yellow (no fluid crosses): it runs when they differ by over PTU_START psi (and the
 * driving side has PTU_MIN) and stops under PTU_STOP - with one engine running it cycles: the A320's «barking»
 * (an event per burst - the entity plays it).</li>
 * </ul>
 * Pressures rise toward their pumps' output at the pump's rate (EDP fast, the electric pumps and the RAT slower) and
 * bleed off at DECAY psi a tick without one. Faults: a LEAK (config chance per hour of pressurised flight / ground run)
 * drains the reservoir; under LOW_QTY of the normal quantity the pumps cavitate - no pressure, «ГИДР x НИЗК УРОВ»; a
 * pressure under LO_PSI while it should have it - «ГИДР x НИЗК ДАВЛ»; the fluid over TEMP_HOT - «ГИДР x ПЕРЕГРЕВ»
 * (it heats with a pump on a low reservoir; cools under TEMP_OK). A surface actuator or the gear works over POWER_PSI.
 * Service: «Гидрожидкость» refills all three and stops the leaks (refill()).
 */
public final class LinerHyd {

    /** The systems (the SD's order G - B - Y). */
    public static final int G = 0, B = 1, Y = 2, SYS = 3;
    /** Bit masks of the systems (LinerFctl's actuators). */
    public static final int MG = 1, MB = 2, MY = 4;
    /** Lang key parts per system (sva.liner.hyd.KEY: «ЗЕЛ / СИН / ЖЁЛТ»; the messages «ГИДР З ...»). */
    public static final String[] KEYS = {"g", "b", "y"};

    /** psi: the nominal pressure, the low-pressure warning, the least that moves an actuator (a slow one). */
    public static final double NOMINAL = 3000, LO_PSI = 1450, POWER_PSI = 1000;
    /** Reservoirs (litres, the A320's normal fill) and the low-level share. */
    public static final double[] QTY_FULL = {14.5, 6.5, 12.5};
    public static final double LOW_QTY = 0.3;
    /** psi a tick: an EDP's / an electric pump's / the RAT's / the PTU's rise; the bleed-off without a pump. */
    public static final double EDP_RISE = 250, ELEC_RISE = 120, RAT_RISE = 90, PTU_RISE = 70, DECAY = 12;
    /** An EDP starts giving pressure at this N2 (%), full from EDP_FULL_N2 (an engine at idle is over it). */
    public static final double EDP_N2 = 20, EDP_FULL_N2 = 40;
    /** The RAT: its pressure at speed, the airspeed (b/t) it starts / gives full pressure at, the emergency generator's psi. */
    public static final double RAT_PSI = 2500, RAT_V0 = 0.5, RAT_V1 = 1.1, RAT_ELEC_PSI = 1500;
    /** The RAT deploys by itself only over this airspeed (b/t) in the air with every generator lost. */
    public static final double RAT_AUTO_V = 0.3;
    /** PTU: starts over this G / Y difference, stops under PTU_STOP, needs PTU_MIN on its driving side; keeps the other this much under. */
    public static final double PTU_START = 500, PTU_STOP = 200, PTU_MIN = 1500, PTU_GAP = 150;
    /** The brake accumulator (Yellow): max, its nitrogen pre-charge, the charge rate (psi a tick), the parking brake's least. */
    public static final double ACCU_MAX = 3000, ACCU_PRE = 1000, ACCU_CHARGE = 60, PARK_MIN = 1000;
    /** psi the accumulator loses a tick of alternate braking on it alone, a tick parked (the A320 holds ~12 h: next to nothing). */
    public static final double ACCU_USE = 4, ACCU_LEAK = 0.002;
    /** Fluid temperature (°C): ambient, with a pump running, the overheat, back to normal under TEMP_OK; the time constant. */
    public static final double TEMP_AMB = 15, TEMP_RUN = 45, TEMP_HOT = 95, TEMP_OK = 70, TEMP_K = 0.0015;
    /** A leak (litres a tick at full pressure) - a 14.5 l reservoir is low in ~35 s. */
    public static final double LEAK_RATE = 0.0115;

    /** Pump-fault lamp bits (the overhead's ОТКАЗ halves): ДВ 1 насос, эл. насос синяя, PTU, эл. насос жёлтая, ДВ 2 насос. */
    public static final int F_EDP1 = 1, F_ELEC_B = 2, F_PTU = 4, F_ELEC_Y = 8, F_EDP2 = 16;

    // ---------------------------------------------------------------- state

    public final double[] psi = new double[SYS], qty = QTY_FULL.clone(), temp = {TEMP_AMB, TEMP_AMB, TEMP_AMB}, leak = new double[SYS];
    /** Overheat latched (until TEMP_OK). */
    public final boolean[] hot = new boolean[SYS];
    /** The brake accumulator (psi). A new liner's is charged from its last flight. */
    public double accu = 2900;
    /** Pumps driving now, the RAT out / turning, the PTU running, the RAT's emergency generator on. */
    public boolean edp1, edp2, elecB, elecY, rat, ptu, ratElec;
    /** Pump-fault lamps (F_*). */
    public int faults;
    /** Events this tick: a PTU burst started (the bark), the RAT came out. */
    public boolean bark, ratOut;
    /** Ticks the PTU has been running (the bark's rhythm). */
    public int ptuTicks;

    /** Inputs of one tick (LinerSystems fills it from its own state and the entity's env). */
    public static final class In {
        public double n2L, n2R;
        public boolean runL, runR;
        /** AC bus 1 / 2 powered (the electric pumps: Blue on 1, Yellow on 2). */
        public boolean ac1, ac2;
        /** Every generator lost (none of ГЕН 1 / 2 / ВСУ / ВНЕШН. online). */
        public boolean gensLost;
        public boolean ground = true;
        public double airspeed;
        /** The push buttons: ДВ 1 / ДВ 2 насос, эл. насос синяя (АВТО) / жёлтая (ВКЛ), PTU (АВТО), RAT ВЫПУСК. */
        public boolean pbEdp1 = true, pbEdp2 = true, pbBlue = true, pbYellow, pbPtu = true, pbRat;
        /** A random 0..1 and the config's leak chance per hour of pressurised running; world / self tests: at once. */
        public double random = 0.5, leakChance;
        public boolean instant;
    }

    // ---------------------------------------------------------------- the tick

    public void tick(In in) {
        bark = ratOut = false;
        boolean anyEng = in.runL || in.runR;
        // ---- the RAT: out by itself in the air without generators, or by its guarded button; stowed on the ground, stopped
        if (!rat && (in.pbRat || !in.ground && in.gensLost && in.airspeed > RAT_AUTO_V)) {
            rat = true;
            ratOut = true;
        }
        if (rat && in.ground && in.airspeed < 0.05 && !in.pbRat) {
            rat = false;                                            // the ground crew stows it
        }
        double ratP = rat ? RAT_PSI * clamp((in.airspeed - RAT_V0) / (RAT_V1 - RAT_V0), 0, 1) : 0;
        // ---- the pumps (a reservoir under LOW_QTY: they cavitate)
        boolean lowG = low(G), lowB = low(B), lowY = low(Y);
        edp1 = in.pbEdp1 && in.n2L >= EDP_N2;
        edp2 = in.pbEdp2 && in.n2R >= EDP_N2;
        elecY = in.pbYellow && in.ac2;
        elecB = in.pbBlue && in.ac1 && (anyEng || !in.ground);
        double tG = edp1 && !lowG ? NOMINAL * edpShare(in.n2L) : 0;
        double tY = Math.max(edp2 && !lowY ? NOMINAL * edpShare(in.n2R) : 0, elecY && !lowY ? NOMINAL : 0);
        double tB = Math.max(elecB && !lowB ? NOMINAL : 0, !lowB ? ratP : 0);
        step(G, tG, EDP_RISE, in.instant);
        step(Y, tY, tY > 0 && !(edp2 && edpShare(in.n2R) * NOMINAL >= tY) ? ELEC_RISE : EDP_RISE, in.instant);
        step(B, tB, elecB ? ELEC_RISE : RAT_RISE, in.instant);
        // ---- the PTU (hysteresis: it starts over PTU_START, stops under PTU_STOP - one engine running makes it bark)
        double d = psi[Y] - psi[G];
        boolean can = in.pbPtu && !lowG && !lowY && Math.max(psi[G], psi[Y]) >= PTU_MIN;
        boolean wasPtu = ptu;
        ptu = can && Math.abs(d) > (ptu ? PTU_STOP : PTU_START);
        if (ptu) {
            int lo = d > 0 ? G : Y, hi = d > 0 ? Y : G;
            double cap = psi[hi] - PTU_GAP;
            psi[lo] = Math.min(Math.max(psi[lo], cap), psi[lo] + (in.instant ? NOMINAL : PTU_RISE));
            ptuTicks++;
        } else {
            ptuTicks = 0;
        }
        if (ptu && !wasPtu) {
            bark = true;
        }
        ratElec = rat && !in.ground && psi[B] >= RAT_ELEC_PSI;
        // ---- the brake accumulator: charged from Yellow, holds for hours
        if (psi[Y] > accu + 20) {
            accu = Math.min(Math.min(ACCU_MAX, psi[Y]), accu + (in.instant ? ACCU_MAX : ACCU_CHARGE));
        } else {
            accu = Math.max(0, accu - ACCU_LEAK);
        }
        // ---- leaks, quantity, temperature
        boolean pressurised = psi[G] > 500 || psi[B] > 500 || psi[Y] > 500;
        if (pressurised && in.leakChance > 0 && in.random < in.leakChance / 72000.0) {
            int s = (int) (in.random * 72000.0 / Math.max(1e-9, in.leakChance) * 3) % 3;
            leak[s] = LEAK_RATE;
        }
        boolean[] pumping = {edp1, elecB || rat, edp2 || elecY};
        for (int s = 0; s < SYS; s++) {
            if (leak[s] > 0) {
                qty[s] = Math.max(0, qty[s] - leak[s] * (0.25 + psi[s] / NOMINAL));
            }
            double target = TEMP_AMB + (pumping[s] ? TEMP_RUN - TEMP_AMB : 0) + (ptu && s != B ? 8 : 0) + (pumping[s] && low(s) ? 110 : 0);
            temp[s] += (target - temp[s]) * TEMP_K;
            if (temp[s] > TEMP_HOT) {
                hot[s] = true;
            } else if (temp[s] < TEMP_OK) {
                hot[s] = false;
            }
        }
        // ---- the pump-fault lamps (an EDP's inhibited with its engine stopped)
        int f = 0;
        if (in.pbEdp1 && in.runL && (psi[G] < LO_PSI || lowG || hot[G])) {
            f |= F_EDP1;
        }
        if (in.pbEdp2 && in.runR && (psi[Y] < LO_PSI || lowY || hot[Y])) {
            f |= F_EDP2;
        }
        if (in.pbBlue && (elecB && psi[B] < LO_PSI || lowB || hot[B])) {
            f |= F_ELEC_B;
        }
        if (elecY && psi[Y] < LO_PSI || lowY || hot[Y]) {
            f |= F_ELEC_Y;
        }
        if (lowG || lowY || hot[G] || hot[Y]) {
            f |= F_PTU;
        }
        faults = f;
    }

    /** An EDP's share of the nominal pressure at this N2 (0 under EDP_N2, 1 from EDP_FULL_N2). */
    public static double edpShare(double n2) {
        return clamp((n2 - EDP_N2) / (EDP_FULL_N2 - EDP_N2), 0, 1);
    }

    private void step(int s, double target, double rise, boolean instant) {
        if (instant) {
            psi[s] = target;
        } else if (psi[s] < target) {
            psi[s] = Math.min(target, psi[s] + rise);
        } else {
            psi[s] = Math.max(target, psi[s] - DECAY);
        }
    }

    // ---------------------------------------------------------------- queries

    /** The reservoir under LOW_QTY of its normal fill. */
    public boolean low(int s) {
        return qty[s] < QTY_FULL[s] * LOW_QTY;
    }

    /** The system can move an actuator (over POWER_PSI). */
    public boolean powered(int s) {
        return psi[s] >= POWER_PSI;
    }

    /** The systems with pressure as bits (MG / MB / MY). */
    public int mask() {
        return (powered(G) ? MG : 0) | (powered(B) ? MB : 0) | (powered(Y) ? MY : 0);
    }

    /** How many of the three are lost (under POWER_PSI). */
    public int lost() {
        return 3 - Integer.bitCount(mask());
    }

    /** An actuator's speed factor for the best of its systems (bits): 0 none, 0.35 at POWER_PSI .. 1 at the nominal. */
    public double rate(int bits) {
        double best = 0;
        for (int s = 0; s < SYS; s++) {
            if ((bits & 1 << s) != 0) {
                best = Math.max(best, psi[s]);
            }
        }
        return best < POWER_PSI ? 0 : 0.35 + 0.65 * clamp((best - POWER_PSI) / (NOMINAL - POWER_PSI), 0, 1);
    }

    /** The parking brake holds (Yellow or the accumulator over PARK_MIN). */
    public boolean parkHolds() {
        return psi[Y] >= PARK_MIN || accu >= PARK_MIN;
    }

    /** Does any reservoir need fluid (under 95 %) or leak? (the service item / the garage). */
    public boolean needsFluid() {
        for (int s = 0; s < SYS; s++) {
            if (qty[s] < QTY_FULL[s] * 0.95 || leak[s] > 0) {
                return true;
            }
        }
        return false;
    }

    /** «Гидрожидкость»: all three reservoirs full, the leaks stopped, the overheat reset. */
    public void refill() {
        for (int s = 0; s < SYS; s++) {
            qty[s] = QTY_FULL[s];
            leak[s] = 0;
            hot[s] = false;
            temp[s] = Math.min(temp[s], TEMP_RUN);
        }
    }

    /** A client mirror's copy of the synced part (pack / unpack go through LinerSystems). */
    void copyFrom(LinerHyd o) {
        System.arraycopy(o.psi, 0, psi, 0, SYS);
        System.arraycopy(o.qty, 0, qty, 0, SYS);
        System.arraycopy(o.temp, 0, temp, 0, SYS);
        System.arraycopy(o.leak, 0, leak, 0, SYS);
        System.arraycopy(o.hot, 0, hot, 0, SYS);
        accu = o.accu;
        edp1 = o.edp1;
        edp2 = o.edp2;
        elecB = o.elecB;
        elecY = o.elecY;
        rat = o.rat;
        ptu = o.ptu;
        ratElec = o.ratElec;
        faults = o.faults;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
