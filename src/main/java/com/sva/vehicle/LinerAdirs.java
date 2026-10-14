package com.sva.vehicle;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 7 (plan-next §7, mock-ups veh_liner_7 «ADIRS», veh_liner_11 PFD flags / «СКОР РАСХОЖД», veh_liner_6 the
 * pedestal's ИНС КВС / ВОЗД. ДАННЫЕ selectors): the SA-320's air data / inertial reference system - THREE ADIRUs, each an
 * IR (attitude, heading, position) and an ADR (airspeed, altitude, vertical speed), as pure server logic (LinerSystems
 * ticks it; the clients get its words in the liner's sync string). It replaces the single «ИНС» of the light aircraft for
 * the liner only.
 * <p>
 * <b>Mode selectors</b> ИНС 1 / 2 / 3 (ВЫКЛ / НАВ / ПРОСТР): НАВ aligns the IR standing still for alignTicks
 * (vehicles.insAlignSeconds) and then waits for the MCDU's IRS INIT (wave 5's gate, unless vehicles.linerIrsAutoInit);
 * moving restarts it (the ВЫСТ. lamp flashes), in the air it can not align at all (only ПРОСТР). ПРОСТР gives the attitude
 * ATT_TICKS (3 s) after it is selected, the heading only once entered on the IRS INIT page - and it drifts (ATT_DRIFT); no
 * position. An aligned IR drifts DRIFT blocks a tick (~1 block in 10 minutes) - GPS (always on: vehicles.linerGpsAlwaysOn)
 * corrects the FMS' position, so the ND / PROG show GPS PRIMARY.
 * <p>
 * <b>Power</b>: AC, or the batteries' bus - then «НА АКБ» lights (and for BAT_TEST ticks at every selector's start: the
 * A320's battery test); ADIRU 2 drops off the batteries after BAT_2_LIMIT (5 min). The push buttons IR / ADR 1 / 2 / 3
 * (in = on; ОТКАЗ amber with a fault) take the part off line.
 * <p>
 * <b>Switching</b> (the pedestal): ИНС КВС (ATT HDG) and ВОЗД. ДАННЫЕ (AIR DATA) - КВС НА 3 / НОРМ / 2П НА 3: the
 * captain's displays read ADIRU 1, the F/O's 2, the standby 3 replaces either.
 * <p>
 * <b>ADR voting</b>: the three airspeeds (each its own probe - LinerIce's probes 1 / 2 / 3, an iced one frozen) are
 * compared; one off the other two by more than DISAGREE_KMH for VOTE_TICKS is REJECTED («НАВ ADR n ОТКАЗ»); two left
 * that disagree - «НАВ ADR РАСХОЖДЕНИЕ» (the PFDs' amber «СКОР РАСХОЖД», the AP and the A/THR let go, ALTN law). Two or
 * more of the ADRs in use on iced probes - the airspeed is unreliable («НЕДОСТ. СКОРОСТЬ»). Two IRs' headings more than
 * IR_DISAGREE apart (a ПРОСТР heading drifting) - «НАВ IR РАСХОЖДЕНИЕ».
 * <p>
 * <b>Failures</b>: an IR / an ADR may fail at random (config, rare) or by injection (the tests): ОТКАЗ until its selector
 * goes to ВЫКЛ.
 */
public final class LinerAdirs {

    // ---------------------------------------------------------------- numbers

    /** ПРОСТР: the attitude after this many ticks (3 s); the heading's drift (deg a tick: 1.5° a minute). */
    public static final int ATT_TICKS = 60;
    public static final double ATT_DRIFT = 1.5 / 1200;
    /** НАВ: the position's drift (blocks a tick: 1 block in 10 minutes). */
    public static final double DRIFT = 1.0 / 12000;
    /** «НА АКБ» at a selector's start (the battery test, ticks); ADIRU 2's longest on the batteries (ticks, 5 min). */
    public static final int BAT_TEST = 100, BAT_2_LIMIT = 6000;
    /** ADR voting: the disagreement (km/h) and how long it must last (ticks); two IRs' headings apart (deg). */
    public static final double DISAGREE_KMH = 30, IR_DISAGREE = 5;
    public static final int VOTE_TICKS = 20;
    /** The ВЫСТ. lamp flashes this long after a restart (ticks). */
    public static final int FLASH_TICKS = 60;

    /** IR states. */
    public static final int S_OFF = 0, S_ALIGN = 1, S_NAV = 2, S_ATT = 3;
    public static final String[] STATE_KEYS = {"off", "align", "nav", "att"};
    /** Units (ADIRU 1, 2, 3 = 0, 1, 2) and the sides (captain 0, F/O 1). */
    public static final int UNITS = 3;
    /** The pedestal's selectors: КВС НА 3, НОРМ, 2П НА 3. */
    public static final int SW_CAPT3 = 0, SW_NORM = 1, SW_FO3 = 2;

    // ---------------------------------------------------------------- inputs

    /** One tick's inputs (LinerSystems fills them). */
    public static final class In {
        /** The selectors (0 ВЫКЛ, 1 НАВ, 2 ПРОСТР), the push buttons in. */
        public final int[] sel = new int[UNITS];
        public final boolean[] irPb = {true, true, true}, adrPb = {true, true, true};
        /** AC on board, the batteries' bus at least. */
        public boolean ac, ess;
        /** Moving (faster than SystemsMath.ALIGN_SPEED), on the ground. */
        public boolean moving, ground = true;
        /** The alignment's ticks (0 = instant), the IRS INIT gate open (confirmed / automatic), the world tests' instant one. */
        public int total = 200;
        public boolean gate = true, instant;
        /** The probes' airspeeds (km/h, an iced one frozen) and iced; the compass heading (deg). */
        public final double[] kmh = new double[UNITS];
        public final boolean[] iced = new boolean[UNITS];
        public double hdg;
        /** A random number 0..1 and the chance of a random IR / ADR fault this tick (each unit, each part). */
        public double random = 0.5, failChance;
        /** GPS (vehicles.linerGpsAlwaysOn). */
        public boolean gps = true;
    }

    // ---------------------------------------------------------------- state

    public final int[] state = new int[UNITS], align = new int[UNITS], att = new int[UNITS], flash = new int[UNITS], sel = new int[UNITS];
    public final boolean[] irFail = new boolean[UNITS], adrFail = new boolean[UNITS], rejected = new boolean[UNITS], powered = new boolean[UNITS],
            hdgSet = new boolean[UNITS], irOn = new boolean[UNITS], adrOn = new boolean[UNITS];
    /** The ПРОСТР heading's offset from the true heading (deg) - what was entered, then the drift; the НАВ position's drift (blocks). */
    public final double[] hdgOff = new double[UNITS], drift = new double[UNITS];
    /** «НА АКБ», the ADRs disagreeing, the IRs' headings disagreeing, the airspeed unreliable, GPS, waiting for IRS INIT. */
    public boolean onBat, disagree, irDisagree, unreliable, gps = true, waitInit;
    /** The battery test's ticks left, ADIRU 2's ticks on the batteries, the votes' persistence. */
    public int batTest, bat2Ticks;
    private final int[] rejectTicks = new int[UNITS];
    private int disagreeTicks, agreeTicks;
    /** The longest alignment's seconds left (the memo «ИНС ВЫСТ. N С»), the ticks of the alignment (for the seconds). */
    public int secondsLeft;
    /** Events of this tick: an IR aligned (the riders are told). */
    public boolean alignedNow;

    // ---------------------------------------------------------------- the tick

    public void tick(In in) {
        alignedNow = false;
        gps = in.gps;
        boolean anyStart = false;
        // ---- power: AC, or the batteries (ADIRU 2 only for BAT_2_LIMIT)
        boolean onBatt = !in.ac && in.ess;
        bat2Ticks = onBatt && in.sel[1] != 0 ? bat2Ticks + 1 : 0;
        int least = Integer.MAX_VALUE;
        waitInit = false;
        for (int i = 0; i < UNITS; i++) {
            int s = Math.max(0, Math.min(2, in.sel[i]));
            if (s != 0 && sel[i] == 0) {
                anyStart = true;
            }
            if (s == 0) {
                irFail[i] = false;                                  // ВЫКЛ resets the unit (and its faults)
                adrFail[i] = false;
                rejected[i] = false;
            }
            if (s == 2 && sel[i] != 2) {
                att[i] = 0;                                         // ПРОСТР selected: the attitude in 3 s, the heading to enter
                hdgSet[i] = false;
            }
            sel[i] = s;
            boolean pw = s != 0 && (in.ac || in.ess && (i != 1 || bat2Ticks < BAT_2_LIMIT));
            powered[i] = pw;
            irOn[i] = in.irPb[i];
            adrOn[i] = in.adrPb[i];
            if (flash[i] > 0) {
                flash[i]--;
            }
            // ---- random faults (config, rare): powered, the part on
            if (pw && in.failChance > 0) {
                double r = (in.random * 7919 * (i + 1) + i * 0.37) % 1.0;
                if (r < in.failChance) {
                    irFail[i] = true;
                } else if (r > 1 - in.failChance) {
                    adrFail[i] = true;
                }
            }
            // ---- the IR
            if (!pw) {
                state[i] = S_OFF;
                align[i] = 0;
                att[i] = 0;
                hdgSet[i] = false;
                drift[i] = 0;
                continue;
            }
            if (s == 2) {
                state[i] = S_ATT;
                align[i] = 0;
                drift[i] = 0;
                att[i] = Math.min(ATT_TICKS * 100, att[i] + (in.instant ? ATT_TICKS : 1));
                if (hdgSet[i]) {
                    hdgOff[i] += ATT_DRIFT * (i == 1 ? -1 : 1);     // the heading drifts (each its own way)
                }
                continue;
            }
            // НАВ
            if (state[i] == S_NAV) {
                drift[i] += DRIFT;
                continue;
            }
            state[i] = S_ALIGN;
            if (in.instant) {
                align[i] = in.total;
            } else if (!in.ground) {
                align[i] = 0;                                       // no alignment in flight: only ПРОСТР
                flash[i] = Math.max(flash[i], 2);
            } else if (in.moving) {
                if (align[i] > 0) {
                    flash[i] = FLASH_TICKS;                         // moved: the alignment starts over, ВЫСТ. flashes
                }
                align[i] = 0;
            } else if (align[i] < in.total) {
                align[i]++;
            }
            if (align[i] >= in.total && (in.gate || in.instant)) {
                state[i] = S_NAV;
                drift[i] = 0;
                alignedNow = true;
            } else if (align[i] >= in.total) {
                waitInit = true;
            } else {
                least = Math.min(least, in.total - align[i]);
            }
        }
        if (anyStart) {
            batTest = BAT_TEST;                                     // the battery test at a start
        } else if (batTest > 0) {
            batTest--;
        }
        onBat = batTest > 0 && anyOn() || onBatt && anyPowered();
        secondsLeft = least == Integer.MAX_VALUE ? 0 : (least + 19) / 20;
        vote(in);
        // the IRs' headings: two valid ones more than IR_DISAGREE apart
        irDisagree = false;
        for (int a = 0; a < UNITS; a++) {
            for (int b = a + 1; b < UNITS; b++) {
                if (hdgOk(a) && hdgOk(b) && Math.abs(CruiseMath.wrap(hdgOff(a) - hdgOff(b))) > IR_DISAGREE) {
                    irDisagree = true;
                }
            }
        }
    }

    /** ADR voting (see the class comment): rejected units, the disagreement, the unreliable airspeed. */
    private void vote(In in) {
        int n = 0;
        for (int i = 0; i < UNITS; i++) {
            if (!adrValid(i)) {
                rejected[i] = false;                                // off / failed: no longer in the vote
                rejectTicks[i] = 0;
            }
        }
        for (int i = 0; i < UNITS; i++) {
            n += usable(i) ? 1 : 0;
        }
        if (n == 3) {
            for (int i = 0; i < UNITS; i++) {
                int j = (i + 1) % 3, k = (i + 2) % 3;
                boolean off = far(in.kmh[i], in.kmh[j]) && far(in.kmh[i], in.kmh[k]) && !far(in.kmh[j], in.kmh[k]);
                rejectTicks[i] = off ? rejectTicks[i] + 1 : 0;
                if (rejectTicks[i] >= VOTE_TICKS) {
                    rejected[i] = true;
                }
            }
        }
        // what is left in use: two (or three all apart) disagreeing - the disagreement
        int a = -1, b = -1, c = -1;
        for (int i = 0; i < UNITS; i++) {
            if (usable(i)) {
                if (a < 0) {
                    a = i;
                } else if (b < 0) {
                    b = i;
                } else {
                    c = i;
                }
            }
        }
        boolean bad = b >= 0 && far(in.kmh[a], in.kmh[b]) && (c < 0 || far(in.kmh[a], in.kmh[c]) && far(in.kmh[b], in.kmh[c]));
        if (bad) {
            disagreeTicks++;
            agreeTicks = 0;
        } else {
            agreeTicks++;
            disagreeTicks = 0;
        }
        if (disagreeTicks >= VOTE_TICKS) {
            disagree = true;
        } else if (agreeTicks >= VOTE_TICKS) {
            disagree = false;
        }
        // the ADRs in use on iced probes (two or more): unreliable
        int icedUsed = 0;
        for (int i = 0; i < UNITS; i++) {
            icedUsed += adrValid(i) && in.iced[i] ? 1 : 0;
        }
        unreliable = !in.ground && (icedUsed >= 2 || disagree);
    }

    static boolean far(double x, double y) {
        return Math.abs(x - y) > DISAGREE_KMH;
    }

    // ---------------------------------------------------------------- what the units give

    public boolean anyOn() {
        return sel[0] != 0 || sel[1] != 0 || sel[2] != 0;
    }

    public boolean anyPowered() {
        return powered[0] || powered[1] || powered[2];
    }

    /** IR i gives the attitude: on, powered, not failed, aligned (НАВ) or ПРОСТР for ATT_TICKS. */
    public boolean attOk(int i) {
        return powered[i] && irOn[i] && !irFail[i] && (state[i] == S_NAV || state[i] == S_ATT && att[i] >= ATT_TICKS);
    }

    /** IR i gives the heading: НАВ, or ПРОСТР with the heading entered. */
    public boolean hdgOk(int i) {
        return attOk(i) && (state[i] == S_NAV || hdgSet[i]);
    }

    /** IR i gives the position (НАВ). */
    public boolean navOk(int i) {
        return attOk(i) && state[i] == S_NAV;
    }

    /** ADR i works (on, powered, not failed). */
    public boolean adrValid(int i) {
        return powered[i] && adrOn[i] && !adrFail[i];
    }

    /** ADR i is in use: working and not rejected by the vote. */
    public boolean usable(int i) {
        return adrValid(i) && !rejected[i];
    }

    /** The heading IR i shows off the true one (deg): 0 in НАВ, the entered offset with its drift in ПРОСТР. */
    public double hdgOff(int i) {
        return state[i] == S_ATT ? hdgOff[i] : 0;
    }

    /** IRs giving the attitude / ADRs in use (the law's and the AP's counts). */
    public int irCount() {
        int n = 0;
        for (int i = 0; i < UNITS; i++) {
            n += attOk(i) ? 1 : 0;
        }
        return n;
    }

    public int adrCount() {
        int n = 0;
        for (int i = 0; i < UNITS; i++) {
            n += usable(i) ? 1 : 0;
        }
        return n;
    }

    /** IRs in НАВ (the FMS' position). */
    public int navCount() {
        int n = 0;
        for (int i = 0; i < UNITS; i++) {
            n += navOk(i) ? 1 : 0;
        }
        return n;
    }

    /** The unit a side's displays read (side 0 the captain, 1 the F/O) for this pedestal selector (SW_*). */
    public static int source(int side, int sw) {
        if (side == 0) {
            return sw == SW_CAPT3 ? 2 : 0;
        }
        return sw == SW_FO3 ? 2 : 1;
    }

    /** A side's attitude / heading / air data are there (its source unit's). */
    public boolean sideAtt(int side, int attSw) {
        return attOk(source(side, attSw));
    }

    public boolean sideHdg(int side, int attSw) {
        return hdgOk(source(side, attSw));
    }

    public boolean sideAdr(int side, int airSw) {
        return usable(source(side, airSw));
    }

    /** An AP (1 or 2: its side) may fly: two IRs and two ADRs, no disagreement, its own side's attitude, heading and air data. */
    public boolean apOk(int ap, int attSw, int airSw) {
        int side = ap == 2 ? 1 : 0;
        return irCount() >= 2 && adrCount() >= 2 && !disagree && sideAtt(side, attSw) && sideHdg(side, attSw) && sideAdr(side, airSw);
    }

    /** The A/THR: two ADRs and two IRs, no disagreement. */
    public boolean athrOk() {
        return irCount() >= 2 && adrCount() >= 2 && !disagree;
    }

    /** The protections (the normal law's): two ADRs in agreement and two IRs. */
    public boolean protOk() {
        return adrCount() >= 2 && irCount() >= 2 && !disagree;
    }

    /** The single-INS status the rest of the addon reads (SystemsMath.INS_*): a НАВ IR - READY; aligning - ALIGN; on, unpowered - FAULT. */
    public int insStatus() {
        if (navCount() > 0) {
            return SystemsMath.INS_READY;
        }
        boolean on = false, aligning = false, pw = false;
        for (int i = 0; i < UNITS; i++) {
            on |= sel[i] != 0;
            pw |= powered[i];
            aligning |= state[i] == S_ALIGN || state[i] == S_ATT;
        }
        return !on ? SystemsMath.INS_OFF : aligning ? SystemsMath.INS_ALIGN : pw ? SystemsMath.INS_ALIGN : SystemsMath.INS_FAULT;
    }

    /** The IRS INIT state for the MCDU / ECAM (LinerAfs.irsState): 0 nothing, 1 aligning, 2 waiting for the IRS INIT. */
    public int irsState() {
        if (waitInit) {
            return 2;
        }
        for (int i = 0; i < UNITS; i++) {
            if (state[i] == S_ALIGN) {
                return 1;
            }
        }
        return 0;
    }

    /** The FMS' position error (blocks): GPS on - none (GPS PRIMARY); else the best НАВ IR's drift. */
    public double posError() {
        if (gps) {
            return 0;
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < UNITS; i++) {
            if (navOk(i)) {
                best = Math.min(best, drift[i]);
            }
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    /** The ВЫСТ. lamp of IR i: 0 dark, 1 steady (aligning), 2 flashing (moved / in the air / waiting for IRS INIT). */
    public int alignLamp(int i) {
        if (state[i] != S_ALIGN) {
            return 0;
        }
        return flash[i] > 0 || waitInit && align[i] > 0 ? 2 : 1;
    }

    /** The IR / ADR push buttons' ОТКАЗ. */
    public boolean irFault(int i) {
        return irFail[i] && sel[i] != 0;
    }

    public boolean adrFault(int i) {
        return (adrFail[i] || rejected[i]) && sel[i] != 0;
    }

    /** ПРОСТР: the heading entered (deg, compass) for IR i against the true heading `hdg`. @return accepted */
    public boolean enterHeading(int i, double entered, double hdg) {
        if (i < 0 || i >= UNITS || state[i] != S_ATT || !powered[i]) {
            return false;
        }
        hdgOff[i] = CruiseMath.wrap(entered - hdg);
        hdgSet[i] = true;
        return true;
    }

    /** Injects a fault (the tests): unit i, its IR (true) or ADR. */
    public void fail(int i, boolean ir) {
        if (ir) {
            irFail[i] = true;
        } else {
            adrFail[i] = true;
        }
    }

    // ---------------------------------------------------------------- sync / save

    /** Words in pack(). */
    public static final int WORDS = 6;

    public int[] pack() {
        int u = 0;
        for (int i = 0; i < UNITS; i++) {
            int b = state[i] | (attOk(i) ? 4 : 0) | (hdgSet[i] ? 8 : 0) | (irFail[i] ? 16 : 0) | (adrFail[i] ? 32 : 0) | (rejected[i] ? 64 : 0)
                    | (powered[i] ? 128 : 0) | (alignLamp(i) << 8);
            u |= b << (i * 10);
        }
        int f = (onBat ? 1 : 0) | (disagree ? 2 : 0) | (irDisagree ? 4 : 0) | (gps ? 8 : 0) | (waitInit ? 16 : 0) | (unreliable ? 32 : 0)
                | sel[0] << 6 | sel[1] << 8 | sel[2] << 10 | (irOn[0] ? 1 << 12 : 0) | (irOn[1] ? 1 << 13 : 0) | (irOn[2] ? 1 << 14 : 0)
                | (adrOn[0] ? 1 << 15 : 0) | (adrOn[1] ? 1 << 16 : 0) | (adrOn[2] ? 1 << 17 : 0) | Math.min(255, secondsLeft) << 18;
        return new int[] {u, f, (int) Math.round(hdgOff[0] * 10), (int) Math.round(hdgOff[1] * 10), (int) Math.round(hdgOff[2] * 10), 0};
    }

    /** The client's lamps: the ВЫСТ. state as synced (pack's bits). */
    private final int[] lampSynced = new int[UNITS];
    private boolean synced;

    public void unpack(int[] w, int o) {
        int u = w[o], f = w[o + 1];
        synced = true;
        for (int i = 0; i < UNITS; i++) {
            int b = u >> (i * 10) & 1023;
            state[i] = b & 3;
            hdgSet[i] = (b & 8) != 0;
            irFail[i] = (b & 16) != 0;
            adrFail[i] = (b & 32) != 0;
            rejected[i] = (b & 64) != 0;
            powered[i] = (b & 128) != 0;
            lampSynced[i] = b >> 8 & 3;
            att[i] = (b & 4) != 0 ? ATT_TICKS : 0;
            hdgOff[i] = w[o + 2 + i] / 10.0;
        }
        onBat = (f & 1) != 0;
        disagree = (f & 2) != 0;
        irDisagree = (f & 4) != 0;
        gps = (f & 8) != 0;
        waitInit = (f & 16) != 0;
        unreliable = (f & 32) != 0;
        for (int i = 0; i < UNITS; i++) {
            sel[i] = f >> (6 + 2 * i) & 3;
            irOn[i] = (f & 1 << (12 + i)) != 0;
            adrOn[i] = (f & 1 << (15 + i)) != 0;
        }
        secondsLeft = f >> 18 & 255;
        for (int i = 0; i < UNITS; i++) {
            if (state[i] == S_NAV) {
                att[i] = ATT_TICKS;                                 // the attitude follows the state on the client
            }
        }
    }

    /** The ВЫСТ. lamp on a client (the synced one) or the server (computed). */
    public int lamp(int i) {
        return synced ? lampSynced[i] : alignLamp(i);
    }

    public void write(NBTTagCompound t) {
        int[] a = new int[UNITS * 4];
        for (int i = 0; i < UNITS; i++) {
            a[i * 4] = state[i] | (hdgSet[i] ? 4 : 0) | (irFail[i] ? 8 : 0) | (adrFail[i] ? 16 : 0) | sel[i] << 5;
            a[i * 4 + 1] = align[i];
            a[i * 4 + 2] = att[i];
            a[i * 4 + 3] = (int) Math.round(hdgOff[i] * 100);
        }
        t.setIntArray("Adirs", a);
    }

    public void read(NBTTagCompound t) {
        int[] a = t.getIntArray("Adirs");
        if (a.length != UNITS * 4) {
            return;
        }
        for (int i = 0; i < UNITS; i++) {
            state[i] = a[i * 4] & 3;
            hdgSet[i] = (a[i * 4] & 4) != 0;
            irFail[i] = (a[i * 4] & 8) != 0;
            adrFail[i] = (a[i * 4] & 16) != 0;
            sel[i] = a[i * 4] >> 5 & 3;
            align[i] = Math.max(0, a[i * 4 + 1]);
            att[i] = Math.max(0, a[i * 4 + 2]);
            hdgOff[i] = a[i * 4 + 3] / 100.0;
        }
    }
}
