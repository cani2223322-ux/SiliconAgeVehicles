package com.sva.vehicle;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 1 (plan-next §7, mock-ups docs/mockups/veh_liner_12, 13a, 15): the SA-320's board systems - electrics,
 * APU, air bleed, the two engines' start and run, the warnings, the quick start - as ONE server-authoritative state
 * machine of pure logic (the dedicated server's self-test SelfTestLinerSVA drives it tick by tick; EntityVehicleSVA
 * owns one per liner, ticks it from serverUpdate and syncs it to every client as a compact string - pack / unpack; the
 * client keeps a mirror instance the displays, the HUD and the cockpit's lamps read).
 * <p>
 * <b>ELEC</b> (sheet 13a): sources АКБ 1 / 2 (the removable batteries - АКБ 1 is the vehicle's own battery slot, АКБ 2
 * lives here), ГЕН 1 / 2 (their engine at idle or over: N2 >= GEN_N2), ГЕН ВСУ (the APU AVAIL), ВНЕШН. ПИТ (a charging
 * station next to the liner - the entity says when one serves it: «ДОСТУП», then the push button connects it). Two AC
 * buses (ПЕР 1 / 2) take, in order, their own generator, external power, the APU generator and - through СВЯЗЬ ШИН (bus
 * tie, AUTO) - the other side's generator. The DC side (ВУ 1 / 2 -> ПОСТ 1 / 2 -> ШИНА АКБ) follows the AC buses, the
 * batteries charge from it. With no AC at all the batteries (push buttons АВТО) keep only the ESSENTIALS: the captain's
 * PFD, ISIS, the transponder and the overhead panel's lights - ESS_LOAD EU/t, two full batteries last ~17 min, one ~9.
 * Loads per bus in % of one generator; one generator in flight (or any over 100 %) sheds the galley («КУХНЯ ОТКЛ»).
 * <p>
 * <b>APU</b>: MASTER SW opens the inlet flap (FLAP_TICKS, needs DC power), START (needs battery voltage >= APU_MIN_V or
 * AC) spools N to 100 % in ~APU_SPOOL ticks with an EGT peak ~700 °C, AVAIL («ГОТОВ» green) APU_AVAIL_DELAY after 95 %;
 * then ГЕН ВСУ and ОТБОР ВСУ work. MASTER off - COOL for APU_COOL ticks at 100 %, then it spools down and the flap shuts.
 * It burns H2 from the LEFT tank (APU_FF mB/min, + bleed); the left tank empty - «ВСУ ОТКАЗ».
 * <p>
 * <b>BLEED</b>: the engines' bleed (N2 over ENG_BLEED_N2: ENG_PSI_IDLE..ENG_PSI_MAX psi), the APU's (APU_PSI) into the
 * LEFT duct; an open APU bleed valve closes the engine valves (A320 logic); X BLEED (ЗАКР / АВТО - open with the APU
 * bleed / ОТКР) joins the ducts. Each pack on takes PACK_PSI of its duct (joined: of the common one) - with the packs on
 * an engine start is slower, may run hot or hang (the checklist's «паки выкл.»).
 * <p>
 * <b>ENGINES</b> (sheet 15): РЕЖИМ ДВ ЗАПУСК + ДВ MASTER ON: with START_PSI in the engine's duct the starter valve opens -
 * N2 rises on the air (MOTOR_RATE x the pressure factor), ignition at 16 %, fuel at 22 %, light-off LIGHT_DELAY later,
 * the EGT peaks, the starter cuts out at 50 %, idle at N2 58 % / N1 19.8 % / EGT ~455 - about 9.5 s. No pressure: the
 * start is not attempted («ЗАПУСК ДВ N: НЕТ ДАВЛЕНИЯ ВОЗДУХА»). The EGT over EGT_START_LIMIT during the start (low
 * pressure, or a config chance) - a HOT start, auto-abort; N2 that stops rising before idle for HUNG_TICKS - a HUNG
 * start, auto-abort; both «ДВ N ОТКАЗ ЗАПУСКА» until the master is switched off. Running: N1 follows the thrust lever
 * (the W / S throttle) from idle to TOGA with the spool's lag; N2, EGT and the fuel flow follow N1. Master off: fuel cut,
 * a spool-down. The beacon off at a start - only a memo.
 * <p>
 * <b>Quick start</b> (vehicles.linerQuickStart): «Быстрый запуск» walks the steps of sheet 15 by itself in ~40 s
 * (QUICK_* steps; a start fault stops it). Units: psi, °C, %, mB/min; ticks of 1/20 s.
 * <p>
 * <b>Wave 3</b>: the fuel system (LinerFuel - pumps, centre first, crossfeed, gravity feed, transfer, FU) feeds the engines
 * from their sides; every warning, caution and memo goes through the ECAM (LinerEcam - priorities, action lines, phases,
 * CLR / RCL / STS / EMER CANC, T.O. CONFIG, the SD's automatic page); the cabin's zones and altitude (cosmetic).
 */
public final class LinerSystems {

    // ---------------------------------------------------------------- numbers

    /** H2 tanks: the share of the whole tank (FuelMath.tank(LINER)) in the left / centre / right tank (35 / 30 / 35 %). */
    public static final double SHARE_L = 0.35, SHARE_C = 0.30, SHARE_R = 0.35;
    /** Battery voltage: empty .. full (V); charging adds CHARGE_V. APU start / its ECB need APU_MIN_V. */
    public static final double V_EMPTY = 23.5, V_FULL = 28.0, CHARGE_V = 0.4, APU_MIN_V = 25.0;
    /** EU/t: the essentials on batteries alone, the batteries' charging from the TRs (each), the APU starter (from the batteries). */
    public static final double ESS_LOAD = 38, BAT_CHARGE = 60, APU_STARTER_EU = 90;
    /** AC load (% of one 90 kVA generator): each bus' base, a pack, the galley (per bus), the cabin, the displays. */
    public static final int LOAD_BUS = 16, LOAD_PACK = 14, LOAD_GALLEY = 26, LOAD_CABIN = 6, LOAD_DU = 8;
    /** A generator comes online at this N2 (%). */
    public static final double GEN_N2 = 55;

    /** APU: inlet flap travel, spool to 100 % (ticks from the flap open), AVAIL after 95 % + this, cool-down at 100 %. */
    public static final int FLAP_TICKS = 60, APU_SPOOL = 260, APU_AVAIL_DELAY = 40, APU_COOL = 600;
    /** APU spool-down after the cool-down (% a tick), its EGT at 100 %, its EGT peak during the start. */
    public static final double APU_DOWN = 0.5, APU_EGT_RUN = 410, APU_EGT_PEAK = 700;
    /** APU fuel (mB/min): running, extra with its bleed / generator loaded. */
    public static final double APU_FF = 40, APU_FF_LOAD = 25;
    /** Bleed pressures (psi): the APU's, an engine's at idle / at full N2, a pack's draw, the low-pressure caution. */
    public static final double APU_PSI = 38, ENG_PSI_IDLE = 30, ENG_PSI_MAX = 46, PACK_PSI = 4, LOW_PSI = 15;
    /** An engine's bleed gives pressure over this N2 (%). */
    public static final double ENG_BLEED_N2 = 52;

    /** Engine start: the least duct pressure for the starter valve to open; the pressure the start's timing is set for. */
    public static final double START_PSI = 20, NOMINAL_PSI = 36, MIN_TURN_PSI = 12;
    /** Start sequence: ignition / fuel N2, the starter's cut-out, idle N2 / N1 / EGT / FF. */
    public static final double IGN_N2 = 16, FUEL_N2 = 22, CUTOUT_N2 = 50, IDLE_N2 = 58, IDLE_N1 = 19.8, IDLE_EGT = 455, IDLE_FF = 60;
    /** N2 %/tick: motoring on the starter at the nominal pressure; after light-off (combustion + starter). */
    public static final double MOTOR_RATE = 0.32, LIGHT_RATE = 0.36;
    /** The starter alone turns the core up to this N2 at the nominal pressure (less with less air). */
    public static final double MOTOR_CEIL = 27;
    /** Ticks from fuel on to light-off; a start with N2 not rising for HUNG_TICKS hangs; no pressure for NO_AIR_TICKS aborts. */
    public static final int LIGHT_DELAY = 10, HUNG_TICKS = 120, NO_AIR_TICKS = 60;
    /** EGT (°C): the start's limit (over it - hot start, auto-abort), its normal peak, the maximum at TOGA. */
    public static final double EGT_START_LIMIT = 725, EGT_START_PEAK = 620, EGT_MAX = 880, EGT_AMBIENT = 15;
    /** Running: N1 at TOGA, the spool's time constant (a share of the gap a tick) and its cap (%/tick), FF at TOGA (mB/min). */
    public static final double TOGA_N1 = 100, SPOOL = 0.04, SPOOL_MAX = 2.2, TOGA_FF = 560;
    /** Spool-down rates (%/tick) of N2 / N1 after the fuel is cut; the EGT cools by COOL_EGT of the gap a tick. */
    public static final double DOWN_N2 = 0.45, DOWN_N1 = 0.5, COOL_EGT = 0.03;
    /** A random start fault (config): a hot start adds this to the EGT; a hung one stops the N2 at this. */
    public static final double HOT_EXTRA = 200, HUNG_N2 = 44;

    /** Quick start: the step order (the server does what the pilot would; sheet 15's 12 steps, shortened). */
    public static final int QUICK_OFF = -1, Q_BAT = 0, Q_APU_MASTER = 1, Q_APU_START = 2, Q_APU_AVAIL = 3, Q_ENG2 = 4, Q_ENG2_IDLE = 5,
            Q_ENG1 = 6, Q_ENG1_IDLE = 7, Q_DONE = 8;
    /** Ticks between the steps (the pilot's hand), the whole sequence's guard (abort over it). */
    public static final int QUICK_STEP = 20, QUICK_MAX = 2400;

    // ---------------------------------------------------------------- the engine

    /** Engine states. */
    public static final int E_OFF = 0, E_WAIT = 1, E_CRANK = 2, E_LIGHT = 3, E_RUN = 4, E_ABORT = 5, E_DOWN = 6;
    /** Engine faults (the start's). */
    public static final int F_NONE = 0, F_HOT = 1, F_HUNG = 2, F_NO_AIR = 3, F_FLAMEOUT = 4;

    /** One engine. */
    public static final class Eng {
        public int state = E_OFF, ticks, fault;
        public double n1, n2, egt = EGT_AMBIENT, ff;
        /** The starter valve is open / ignition on / the fuel valve open. */
        public boolean starter, ign, fuel;
        /** This start's random fault (rolled when the start begins): F_NONE / F_HOT / F_HUNG. */
        int rolled;
        /** The best N2 so far and the ticks since it last rose (hung start). */
        double best;
        int still, noAir;

        /** Running (idle or over): its generator, its bleed. */
        public boolean running() {
            return state == E_RUN;
        }

        /** Making thrust (0..1): N1 over idle up to TOGA; 0 when not running. */
        public double thrust() {
            if (state != E_RUN) {
                return 0;
            }
            return clamp((n1 - IDLE_N1) / (TOGA_N1 - IDLE_N1), 0, 1);
        }

        void copy(Eng o) {
            state = o.state;
            ticks = o.ticks;
            fault = o.fault;
            n1 = o.n1;
            n2 = o.n2;
            egt = o.egt;
            ff = o.ff;
            starter = o.starter;
            ign = o.ign;
            fuel = o.fuel;
        }
    }

    // ---------------------------------------------------------------- the environment of a tick

    /** What the entity knows each tick (inputs). */
    public static final class Env {
        /** АКБ 1 (the vehicle's own slot): in, its charge and capacity (EU). */
        public boolean bat1In;
        public double bat1, bat1Cap;
        /** A charging station serves the liner (external power available). */
        public boolean extAvail;
        /** The thrust levers (the throttle 0..1), on the ground, the airspeed (b/t). */
        public double throttle;
        public boolean ground = true;
        public double airspeed;
        /** Fuel left in the tanks feeding the engines / the APU (mB) - the tanks here are the truth. */
        /** A random number 0..1 for this tick's rolls (a start's fault). */
        public double random = 0.5;
        /** Config: the chance of a random hot / hung start (each), quick start allowed. */
        public double failChance;
        public boolean quickAllowed = true;
        /** World / self tests: the engines and the APU at once (no sequence). */
        public boolean instant;
        // wave 2
        /** The stick and pedals (-1..1: roll + right, pitch + nose up, yaw + right) from the pilot's keys. */
        public float roll, pitch, yaw;
        /** S at idle on the ground (the brake pedals); the ground speed along the heading (b/t); the surface's grip (LinerGear.MU_*). */
        public boolean pedal;
        public double speed, mu = LinerGear.MU_DRY;
        /** The older world tests: the hydraulics, the gear and the high lift at once too (LinerLogicSVA: not for wave 2's own). */
        public boolean instantHyd;
        /** The height over the ground (blocks; the КОЛЁСА page's auto-display), the config's hydraulic leak chance per hour. */
        public double height, leakChance;
        // wave 3
        /** The altitude (absolute Y), the gravity fuel feed's ceiling (Y), LDG MEMO / the approach phase under this height (blocks). */
        public double alt = 64, gravAlt = 160, ldgMemoH = 60;
        // wave 4
        /** The aircraft for the auto flight system (the entity fills it; ground / height / altitude / speed are copied from above). */
        public LinerAfs.Air air = new LinerAfs.Air();
        /** Wave 5: the fuel's consumption multiplier (the config) for the FMS' predictions. */
        public double burn = 1;
    }

    // ---------------------------------------------------------------- state

    /** Control positions and guards (LinerControls rows). */
    public final int[] pos = new int[LinerControls.count()];
    public final boolean[] guard = new boolean[LinerControls.count()];
    public final Eng[] eng = {new Eng(), new Eng()};

    /** APU states. */
    public static final int A_OFF = 0, A_FLAP = 1, A_START = 2, A_AVAIL = 3, A_COOL = 4, A_DOWN = 5;
    public int apu = A_OFF, apuTicks;
    public double apuN, apuEgt = EGT_AMBIENT, apuFlap;
    /** START pressed and waiting for the flap / spooling: the START push button's blue «ВКЛ». */
    public boolean apuStartLit, apuFault, apuBleedUsed;

    /** ELEC: the sources online and the buses fed (S_* - which source feeds each AC bus). */
    public static final int S_NONE = 0, S_GEN1 = 1, S_GEN2 = 2, S_APU = 3, S_EXT = 4;
    public int ac1Src, ac2Src;
    public boolean extAvail, extOn, gen1On, gen2On, apuGenOn, bat1On, bat2On, essOnly, galleyShed;
    /** Battery volts / amps (+ charging), generator loads (%), the board's power state for the displays. */
    public double bat1V, bat1A, bat2V, bat2A;
    public int loadGen1, loadGen2, loadApu, loadExt;

    /** BLEED: the ducts' pressures (psi), the valves, the X BLEED open. */
    public double ductL, ductR, apuPsi;
    public boolean bleed1Open, bleed2Open, apuBleedOpen, xbleedOpen;

    /** АКБ 2 (the liner's second battery slot): in, charge, health / limit / services / cycle / deep (BatteryMath.Battery). */
    public boolean bat2In = true;
    public final BatteryMath.Battery bat2 = new BatteryMath.Battery();

    /** H2 tanks (mB). */
    public double tankL, tankC, tankR;

    /** The SD page (0 automatic, 1.. LinerEcam.P_* + 1: LinerControls.SD_PAGES and the ECAM's own pages). */
    public int sdPage;
    /** Warnings / cautions now (the wave 1-2 W_* / C_* bits - raw conditions, LinerEcam sets them), the master lamps. */
    public int warn, caut;
    public boolean masterWarn, masterCaut;
    /** Sound events of this tick (the entity plays them): E_CHIME_WARN, E_CHIME_CAUT, E_CLICK, E_APU_AVAIL, E_LIGHT_OFF. */
    public int events;
    public static final int E_CHIME_WARN = 1, E_CHIME_CAUT = 2, E_CLICK = 4, E_APU_AVAIL = 8, E_LIGHT_OFF = 16, E_STARTER = 32;

    /** Quick start: the step (QUICK_OFF none), ticks in it, ticks in all. */
    public int quick = QUICK_OFF, quickTicks, quickAll;
    /** Why the last quick start stopped (a lang key part: sva.liner.quick.KEY) - "" none. */
    public String quickWhy = "";

    /** The L1 door (0 shut .. 1 open target), synced; the stairs follow it on the client. */
    public boolean door;

    // ---------------------------------------------------------------- wave 2: hydraulics, flight controls, gear and brakes

    /** The three hydraulic systems, the flight controls, the gear / brakes / steering (LinerHyd, LinerFctl, LinerGear). */
    public final LinerHyd hyd = new LinerHyd();
    public final LinerFctl fctl = new LinerFctl();
    public final LinerGear gear = new LinerGear();
    /** On the ground (the last tick's), the F/CTL check's page timer (ticks) and whether it was shown this ground run. */
    public boolean onGround = true;
    public int fctlCheck;
    private boolean fctlChecked;
    /** The SD's automatic page (the server's judgement, synced: LinerControls.SD_PAGES index). */
    public int autoSd = 3;
    /** The F/CTL check's page stays this long (ticks) after the first stick deflection on the ground. */
    public static final int FCTL_CHECK_TICKS = 600;
    /** КОЛЁСА shows itself under this height (blocks) in the air with the gear lever down. */
    public static final double WHEEL_ALT = 200;
    /** Events of this tick past E_*: the PTU's bark, the RAT out, the gear moving / locked, a tyre's fuse plug. */
    public static final int E_PTU = 64, E_RAT = 128, E_FUSE = 256, E_GEAR = 512;

    // ---------------------------------------------------------------- wave 3: fuel, ECAM, the cabin

    /** The fuel system (the tanks' insides, pumps, crossfeed, feeding) and the ECAM's message engine. */
    public final LinerFuel fuel = new LinerFuel();
    public final LinerEcam ecam = new LinerEcam();
    /** The cabin's zones (cockpit, forward, aft: °C, cosmetic - the packs warm / cool them to CAB_T), the cabin altitude (m) and its rate (m/s). */
    public final double[] cabT = {15, 15, 15};
    public double cabAlt, cabVs;
    /** The packs' zone temperatures (°C): cockpit / forward / aft. */
    public static final double[] CAB_T = {22, 21, 22};
    /** Events of this tick past E_*: the tanks grew (a refuel). */
    public static final int E_REFUEL = 1024;

    // ---------------------------------------------------------------- wave 4: the auto flight system, levers, reversers

    /** The FCU, AP / FD, A/THR, the levers and the reversers, the ILS, the callouts (LinerAfs). */
    public final LinerAfs afs = new LinerAfs();
    /**
     * Wave 5: the FMGC (the plan, INIT, PERF, the profile - the data both MCDUs share) and the two MCDUs (each its own page
     * and scratchpad). Server-authoritative; the clients get the MCDU screens and the ND's plan as their own packets.
     */
    public final LinerFms fms = new LinerFms();
    public final LinerMcdu mcdu = new LinerMcdu();
    /** Wave 5: ticks counted (the FMS' profile runs every few). */
    private int ticks;
    /** The last tick's aircraft (the FCU's clicks between ticks read it; null before the first tick). */
    public LinerAfs.Air lastAir;
    /** Events of this tick past E_*: the AP's cavalry charge note, a callout (the entity plays them - LinerAfs.cavNote / soundCall). */
    public static final int E_AFS = 2048;

    // EU / mB of this tick (outputs for the entity)
    /** EU taken from АКБ 1 this tick (negative: charged into it). */
    public double bat1Eu;
    /** H2 burnt this tick (mB): the engines, the APU. */
    public double burnEng, burnApu;

    public LinerSystems() {
        reset();
    }

    /** Cold and dark: every control at its default, everything off, full tanks by `tank` (mB). */
    public void reset() {
        for (int i = 0; i < pos.length; i++) {
            pos[i] = LinerControls.get(i).def;
            guard[i] = false;
        }
        eng[0] = new Eng();
        eng[1] = new Eng();
        apu = A_OFF;
        apuTicks = 0;
        apuN = apuFlap = 0;
        apuEgt = EGT_AMBIENT;
        apuStartLit = apuFault = false;
        quick = QUICK_OFF;
        sdPage = 0;
        warn = caut = 0;
        bat2.charge = 0;
        autoSd = 3;
    }

    // ---------------------------------------------------------------- controls

    public int at(LinerControls.Ctl c) {
        return pos[c.index];
    }

    public boolean on(LinerControls.Ctl c) {
        return pos[c.index] != 0;
    }

    /**
     * A click on a control (server: the pilot's packet, validated by the entity; the quick start too). Momentary buttons act
     * here (APU START, MASTER WARN / CAUT, the ECAM keys). @return true when anything changed
     */
    public boolean control(int index, int action) {
        LinerControls.Ctl c = LinerControls.get(index);
        if (c == null) {
            return false;
        }
        if (c.kind == LinerControls.KNOB) {
            boolean k = afs.knob(this, c, action, lastAir);         // wave 4: the FCU's knobs
            if (k) {
                events |= E_CLICK;
            }
            return k;
        }
        if (c == LinerControls.TL1 || c == LinerControls.TL2) {
            // wave 4: a thrust lever clicked - the next detent up (LMB / wheel up) or down (into the reverse on the ground)
            double l0 = afs.lever, r0 = afs.rev;
            afs.leverClick(action == LinerControls.A_PRIMARY || action == LinerControls.A_WHEEL_UP, onGround);
            boolean ch = l0 != afs.lever || r0 != afs.rev;
            if (ch) {
                events |= E_CLICK;
            }
            return ch;
        }
        if (c == LinerControls.ILS_SEL) {
            afs.chooseIls(action >= LinerControls.A_SET ? action - LinerControls.A_SET : 0);
            return true;
        }
        int[] r = LinerControls.apply(c, pos[index], guard[index], action);
        boolean changed = r[0] != pos[index] || (r[1] != 0) != guard[index];
        pos[index] = r[0];
        guard[index] = r[1] != 0;
        if (r[2] != 0) {
            changed |= press(c);
        }
        autobrakeButton(c);
        if (changed) {
            events |= E_CLICK;
        }
        if (c == LinerControls.QUICK && pos[index] == 0) {
            pos[index] = 1;                                         // spring-loaded: the switch starts it and drops back
            guard[index] = false;
            startQuick();
        }
        return changed;
    }

    /**
     * The autobrake's push buttons (wave 2): one in arms its mode and lets the others out - only with the normal brakes
     * (Green and the anti-skid; else it pops out); the armed one out disarms.
     */
    private void autobrakeButton(LinerControls.Ctl c) {
        LinerControls.Ctl[] ab = {LinerControls.ABRK_LO, LinerControls.ABRK_MED, LinerControls.ABRK_MAX};
        for (int i = 0; i < 3; i++) {
            if (c != ab[i]) {
                continue;
            }
            if (on(c)) {
                boolean normal = hyd.powered(LinerHyd.G) && at(LinerControls.ANTISKID) == 0;
                if (!normal) {
                    pos[c.index] = 0;
                    return;
                }
                for (LinerControls.Ctl o : ab) {
                    if (o != c) {
                        pos[o.index] = 0;
                    }
                }
                gear.disarm();
                gear.abMode = i + 1;
            } else if (gear.abMode == i + 1) {
                gear.disarm();
            }
        }
    }

    /** Sets a control directly (the quick start, tests). */
    public void set(LinerControls.Ctl c, int p) {
        pos[c.index] = Math.max(0, Math.min(c.n() - 1, p));
    }

    private boolean press(LinerControls.Ctl c) {
        if (afs.press(this, c, lastAir)) {
            return true;                                            // wave 4: the FCU's buttons, the AP / A/THR disconnects
        }
        if (c == LinerControls.APU_START) {
            if (on(LinerControls.APU_MASTER) && (apu == A_FLAP || apu == A_OFF) && !apuFault) {
                apuStartLit = true;
                return true;
            }
            return false;
        }
        // wave 3: the masters, the ECAM control panel - LinerEcam
        if (c == LinerControls.MW_L || c == LinerControls.MW_R) {
            ecam.masterWarn(this);
            return true;
        }
        if (c == LinerControls.MC_L || c == LinerControls.MC_R) {
            masterCaut = false;
            return true;
        }
        if (c == LinerControls.ECAM_CLR) {
            ecam.clr(this);
            return true;
        }
        if (c == LinerControls.ECAM_RCL) {
            ecam.rcl();
            return true;
        }
        if (c == LinerControls.ECAM_EMER) {
            ecam.emerCanc(this);
            return true;
        }
        if (c == LinerControls.TO_CONFIG) {
            ecam.toConfig(onGround);
            return true;
        }
        if (c == LinerControls.ECAM[LinerEcam.P_ALL]) {
            sdPage = LinerEcam.allNext(sdPage);                     // ВСЕ: the next page of the cycle (the checklists among them)
            return true;
        }
        for (int i = 0; i < LinerControls.ECAM.length; i++) {
            if (c == LinerControls.ECAM[i]) {
                sdPage = sdPage == i + 1 ? 0 : i + 1;               // again: back to automatic
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the tick

    /** One server tick. Outputs: bat1Eu (the entity applies it to АКБ 1), burnEng / burnApu (already taken from the tanks). */
    public void tick(Env e) {
        events = 0;
        quickTick(e);
        boolean dcBat1 = e.bat1In && e.bat1 > 0 && on(LinerControls.BAT1);
        boolean dcBat2 = bat2In && bat2.charge > 0 && on(LinerControls.BAT2);
        bat1On = dcBat1;
        bat2On = dcBat2;
        double f1 = e.bat1Cap > 0 ? e.bat1 / e.bat1Cap : 0;
        double cap2 = BatteryMath.capacity(VehicleType.LINER);
        double f2 = cap2 > 0 ? bat2.charge / cap2 : 0;
        double v1 = dcBat1 ? volts(f1) : 0, v2 = dcBat2 ? volts(f2) : 0;
        double batV = Math.max(v1, v2);
        // ---- external power: available from the station; the push button connects it (drops when the station goes)
        extAvail = e.extAvail;
        if (!extAvail && on(LinerControls.EXT_PWR)) {
            set(LinerControls.EXT_PWR, 0);
        }
        extOn = extAvail && on(LinerControls.EXT_PWR);
        // ---- the APU (needs DC: the batteries' bus or AC through the TRs - AC is from the last tick's buses)
        boolean acBefore = ac1Src != S_NONE || ac2Src != S_NONE;
        boolean dc = dcBat1 || dcBat2 || acBefore || extOn;
        apuTick(e, dc, batV >= APU_MIN_V || acBefore || extOn);
        // ---- the generators
        gen1On = eng[0].n2 >= GEN_N2 && eng[0].running() && on(LinerControls.GEN1);
        gen2On = eng[1].n2 >= GEN_N2 && eng[1].running() && on(LinerControls.GEN2);
        apuGenOn = apu == A_AVAIL && on(LinerControls.APU_GEN);
        boolean tie = on(LinerControls.BUS_TIE);
        ac1Src = gen1On ? S_GEN1 : extOn ? S_EXT : apuGenOn ? S_APU : tie && gen2On ? S_GEN2 : S_NONE;
        ac2Src = gen2On ? S_GEN2 : extOn ? S_EXT : apuGenOn ? S_APU : tie && gen1On ? S_GEN1 : S_NONE;
        boolean ac = ac1Src != S_NONE || ac2Src != S_NONE;
        essOnly = !ac && (dcBat1 || dcBat2 || hyd.ratElec);         // wave 2: the RAT's emergency generator keeps the essentials
        // ---- loads: per bus, then per source; one generator in flight (or any over 100 %) sheds the galley
        int l1 = LOAD_BUS + LOAD_DU + (on(LinerControls.PACK1) ? LOAD_PACK : 0) + LOAD_CABIN / 2;
        int l2 = LOAD_BUS + LOAD_DU + (on(LinerControls.PACK2) ? LOAD_PACK : 0) + LOAD_CABIN / 2;
        int galley = on(LinerControls.GALLEY) ? LOAD_GALLEY : 0;
        int sources = (gen1On ? 1 : 0) + (gen2On ? 1 : 0) + (apuGenOn ? 1 : 0) + (extOn ? 1 : 0);
        int[] load = loads(l1 + galley, l2 + galley);
        boolean over = load[S_GEN1] > 100 || load[S_GEN2] > 100 || load[S_APU] > 100 || load[S_EXT] > 100;
        galleyShed = galley > 0 && ac && (over || sources == 1 && !e.ground);
        if (galleyShed) {
            load = loads(l1, l2);
        }
        loadGen1 = load[S_GEN1];
        loadGen2 = load[S_GEN2];
        loadApu = load[S_APU];
        loadExt = load[S_EXT];
        // ---- the batteries: charged from the TRs with AC; the essentials (+ the APU starter) on them without
        double drain1 = 0, drain2 = 0;
        if (ac) {
            double cap1 = e.bat1Cap;
            if (dcBat1 || e.bat1In && on(LinerControls.BAT1)) {
                drain1 = -Math.min(BAT_CHARGE, Math.max(0, cap1 - e.bat1));
            }
            if (bat2In && on(LinerControls.BAT2)) {
                drain2 = -Math.min(BAT_CHARGE, Math.max(0, cap2 - bat2.charge));
            }
        } else if ((dcBat1 || dcBat2) && !hyd.ratElec) {
            double need = ESS_LOAD + (apu == A_START && apuN < 60 ? APU_STARTER_EU : 0);
            int n = (dcBat1 ? 1 : 0) + (dcBat2 ? 1 : 0);
            drain1 = dcBat1 ? need / n : 0;
            drain2 = dcBat2 ? need / n : 0;
        }
        bat1Eu = drain1;
        double before2 = bat2.charge;
        bat2.charge = clamp(bat2.charge - drain2, 0, cap2);
        if (bat2.charge > before2) {
            bat2.cycle += bat2.charge - before2;
        }
        bat1V = e.bat1In ? volts(f1) + (drain1 < 0 ? CHARGE_V : 0) : 0;
        bat2V = bat2In ? volts(f2) + (drain2 < 0 ? CHARGE_V : 0) : 0;
        bat1A = -drain1 * 0.5;
        bat2A = -drain2 * 0.5;
        // ---- bleed, then the engines (their starters read the ducts)
        bleedTick();
        fuelPrepare(e, ac);                                         // wave 3: the pumps, crossfeed, transfer - who feeds each engine
        afsTick(e);                                                 // wave 4: the AFS - the thrust the engines spool to
        burnEng = 0;
        for (int i = 0; i < 2; i++) {
            engineTick(e, i, ac || dcBat1 || dcBat2 || extOn);
        }
        burnFuel();
        wave2Tick(e);
        // wave 3: every warning / caution through the ECAM, then the SD's automatic page; the cabin
        ecam.tick(this, e);
        autoSd = ecam.autoPage(this, e);
        if (autoPage() == LinerEcam.P_STS) {
            ecam.stsSeen = true;
        }
        cabinTick(e);
    }

    /** The loads (%) per source for these bus loads (a source feeding both buses carries both). */
    private int[] loads(int l1, int l2) {
        int[] l = new int[5];
        l[ac1Src] += l1;
        l[ac2Src] += l2;
        l[S_NONE] = 0;
        return l;
    }

    /** Battery volts at charge share f (0..1). */
    public static double volts(double f) {
        return V_EMPTY + (V_FULL - V_EMPTY) * clamp(f, 0, 1);
    }

    // ---------------------------------------------------------------- APU

    private void apuTick(Env e, boolean dc, boolean startPower) {
        boolean master = on(LinerControls.APU_MASTER) && dc;
        apuTicks++;
        if (!on(LinerControls.APU_MASTER)) {
            apuFault = false;                                       // master off resets the fault light
            apuStartLit = false;
        }
        boolean fuel = tankL > 0.5;
        switch (apu) {
            case A_OFF:
                apuFlap = Math.max(0, apuFlap - 1.0 / FLAP_TICKS);
                apuN = Math.max(0, apuN - APU_DOWN);
                if (master && !apuFault) {
                    apu = A_FLAP;
                    apuTicks = 0;
                }
                break;
            case A_FLAP:
                apuFlap = Math.min(1, apuFlap + 1.0 / FLAP_TICKS);
                if (!master) {
                    apu = A_OFF;
                    apuStartLit = false;
                } else if (apuStartLit && apuFlap >= 1 && startPower) {
                    if (!fuel) {
                        apuFail();
                    } else {
                        apu = A_START;
                        apuTicks = 0;
                        events |= E_STARTER;
                    }
                }
                break;
            case A_START: {
                if (!master) {
                    apu = A_DOWN;
                    apuStartLit = false;
                    break;
                }
                if (!fuel) {
                    apuFail();
                    break;
                }
                double k = e.instant ? 1 : Math.min(1, apuTicks / (double) APU_SPOOL);
                // N: an S-curve to 100 %; EGT: a peak ~APU_EGT_PEAK at 45 % then settling to the running EGT
                apuN = 100 * (k < 0.5 ? 2 * k * k : 1 - 2 * (1 - k) * (1 - k));
                double peak = Math.exp(-Math.pow((apuN - 45) / 22, 2));
                double target = apuN < 8 ? EGT_AMBIENT : APU_EGT_RUN * Math.min(1, apuN / 100) + (APU_EGT_PEAK - APU_EGT_RUN * 0.45) * peak;
                apuEgt += (target - apuEgt) * (e.instant ? 1 : 0.15);
                if (e.instant || apuTicks >= apuAvailTicks()) {
                    apu = A_AVAIL;                                  // 95 % + APU_AVAIL_DELAY: «ГОТОВ»
                    apuN = 100;
                    apuTicks = 0;
                    apuStartLit = false;
                    events |= E_APU_AVAIL;
                }
                break;
            }
            case A_AVAIL:
                apuN = 100;
                apuFlap = 1;
                apuEgt += (APU_EGT_RUN + (apuBleedOpen ? 40 : 0) - apuEgt) * 0.05;
                if (!fuel) {
                    apuFail();
                } else if (!on(LinerControls.APU_MASTER)) {
                    apu = A_COOL;
                    apuTicks = 0;
                }
                break;
            case A_COOL:
                apuN = 100;
                apuEgt += (APU_EGT_RUN - 30 - apuEgt) * 0.05;
                if (on(LinerControls.APU_MASTER) && dc) {
                    apu = A_AVAIL;                                  // master on again during the cool-down
                } else if (!fuel) {
                    apuFail();
                } else if (apuTicks >= (e.instant ? 0 : APU_COOL)) {
                    apu = A_DOWN;
                    apuTicks = 0;
                }
                break;
            case A_DOWN:
            default:
                apuN = Math.max(0, apuN - APU_DOWN * (e.instant ? 200 : 1));
                apuEgt += (EGT_AMBIENT - apuEgt) * 0.02;
                if (apuN < 7) {
                    apuFlap = Math.max(0, apuFlap - 1.0 / FLAP_TICKS);
                }
                if (apuN <= 0 && apuFlap <= 0) {
                    apu = A_OFF;
                }
                break;
        }
        if (apu == A_OFF || apu == A_FLAP) {
            apuEgt += (EGT_AMBIENT - apuEgt) * 0.02;
        }
        apuGenOn = apu == A_AVAIL && on(LinerControls.APU_GEN);
    }

    /** Ticks of the S-curve to 95 % N (the AVAIL delay counts from there). */
    private static int spoolTo95() {
        // 100 (1 - 2 (1 - k)^2) = 95 -> k = 1 - sqrt(0.025)
        return (int) Math.ceil(APU_SPOOL * (1 - Math.sqrt(0.025)));
    }

    /** The APU's AVAIL tick after START (with the flap already open): the self-test's timing. */
    public static int apuAvailTicks() {
        return spoolTo95() + APU_AVAIL_DELAY;
    }

    private void apuFail() {
        apuFault = true;
        apuStartLit = false;
        apu = A_DOWN;
        apuTicks = 0;
    }

    /** The APU runs (AVAIL or cooling down at 100 %). */
    public boolean apuRunning() {
        return apu == A_AVAIL || apu == A_COOL;
    }

    // ---------------------------------------------------------------- bleed

    private void bleedTick() {
        apuBleedOpen = apu == A_AVAIL && on(LinerControls.APU_BLEED);
        if (apuBleedOpen) {
            apuBleedUsed = true;
        }
        apuPsi = apuBleedOpen ? APU_PSI : 0;
        bleed1Open = !apuBleedOpen && on(LinerControls.BLEED1) && eng[0].n2 >= ENG_BLEED_N2;
        bleed2Open = !apuBleedOpen && on(LinerControls.BLEED2) && eng[1].n2 >= ENG_BLEED_N2;
        int xb = at(LinerControls.XBLEED);
        xbleedOpen = xb == 2 || xb == 1 && apuBleedOpen;
        double sL = Math.max(bleed1Open ? engPsi(eng[0]) : 0, apuPsi);
        double sR = bleed2Open ? engPsi(eng[1]) : 0;
        int pL = on(LinerControls.PACK1) ? 1 : 0, pR = on(LinerControls.PACK2) ? 1 : 0;
        if (xbleedOpen) {
            double s = Math.max(sL, sR);
            double p = s > 0 ? Math.max(0, s - PACK_PSI * (pL + pR)) : 0;
            ductL = ductR = p;
        } else {
            ductL = sL > 0 ? Math.max(0, sL - PACK_PSI * pL) : 0;
            ductR = sR > 0 ? Math.max(0, sR - PACK_PSI * pR) : 0;
        }
    }

    /** An engine's bleed pressure by its N2 (idle .. full). */
    public static double engPsi(Eng g) {
        if (g.n2 < ENG_BLEED_N2) {
            return 0;
        }
        return ENG_PSI_IDLE + (ENG_PSI_MAX - ENG_PSI_IDLE) * clamp((g.n2 - IDLE_N2) / (100 - IDLE_N2), 0, 1);
    }

    /**
     * The air for a start: the pressure factor 0..1.25 of the duct's psi (0 at MIN_TURN_PSI, 1 at NOMINAL_PSI) - the
     * starter's torque.
     */
    public static double airFactor(double psi) {
        return clamp((psi - MIN_TURN_PSI) / (NOMINAL_PSI - MIN_TURN_PSI), 0, 1.25);
    }

    // ---------------------------------------------------------------- engines

    /** The duct an engine's starter draws from. */
    public double duct(int i) {
        return i == 0 ? ductL : ductR;
    }

    private void engineTick(Env e, int i, boolean power) {
        Eng g = eng[i];
        boolean master = on(i == 0 ? LinerControls.ENG_MASTER1 : LinerControls.ENG_MASTER2);
        int mode = at(LinerControls.ENG_MODE);
        // wave 3: fuel from this engine's side (LinerFuel: centre / wing pumps / gravity / crossfeed), not just any tank
        boolean feedable = fuel.feedable(i), fed = fuel.fed[i];
        g.ticks++;
        if (!master) {
            if (g.state != E_OFF && g.state != E_DOWN) {
                g.state = E_DOWN;
                g.ticks = 0;
            }
            g.fault = F_NONE;                                       // master off clears the start fault
        }
        double psi = duct(i);
        switch (g.state) {
            case E_OFF:
                g.starter = g.ign = g.fuel = false;
                cool(g, e);
                if (master && g.fault == F_NONE) {
                    if (e.instant && power) {
                        run(g);
                        break;
                    }
                    if (mode == 2) {
                        g.state = E_WAIT;                           // ЗАПУСК: the start valve opens when there is air
                        if (e.ground) {
                            fuel.used[i] = 0;                       // wave 3: the fuel used counts from the start
                        }
                        g.ticks = 0;
                        g.noAir = 0;
                        g.rolled = e.random < e.failChance ? F_HOT : e.random < 2 * e.failChance ? F_HUNG : F_NONE;
                    } else if (g.n2 > 15 && feedable) {
                        // a relight (in flight, windmilling - or a master flicked off and on while still spinning)
                        g.state = E_LIGHT;
                        g.ticks = 0;
                        g.fuel = g.ign = true;
                    }
                }
                break;
            case E_WAIT:
                cool(g, e);
                if (!master) {
                    break;
                }
                if (mode != 2) {
                    g.state = E_OFF;                                // the mode left ЗАПУСК before the air came
                    break;
                }
                if (psi >= START_PSI && power) {
                    g.state = E_CRANK;
                    g.ticks = 0;
                    g.best = g.n2;
                    g.still = 0;
                    g.starter = true;
                    events |= E_STARTER;
                }
                break;
            case E_CRANK:
            case E_LIGHT: {
                if (psi < MIN_TURN_PSI && g.n2 < CUTOUT_N2) {
                    if (++g.noAir > NO_AIR_TICKS) {
                        abort(g, F_NO_AIR);                         // the air went away mid-start
                        break;
                    }
                } else {
                    g.noAir = 0;
                }
                double f = airFactor(psi);
                if (g.n2 >= CUTOUT_N2) {
                    g.starter = false;                              // the starter cuts out at 50 % N2
                }
                if (g.n2 >= IGN_N2) {
                    g.ign = true;
                }
                double rate;
                if (g.state == E_CRANK) {
                    // the starter alone: up to a ceiling that sinks with the air (packs on - slower; too little - it hangs)
                    double ceil = MOTOR_CEIL * Math.min(1.1, 0.4 + 0.6 * f);
                    rate = g.starter ? MOTOR_RATE * f * clamp((ceil - g.n2) / 4, 0, 1) : -DOWN_N2;
                    if (g.n2 >= FUEL_N2 && feedable) {
                        g.fuel = true;                              // fuel at 22 %: light-off LIGHT_DELAY later
                        g.state = E_LIGHT;
                        g.ticks = 0;
                    }
                    g.egt += (EGT_AMBIENT - g.egt) * COOL_EGT;
                } else {
                    // after fuel: light-off LIGHT_DELAY later, then combustion + starter accelerate it to idle
                    boolean lit = g.ticks >= LIGHT_DELAY;
                    if (g.ticks == LIGHT_DELAY) {
                        events |= E_LIGHT_OFF;
                    }
                    double help = g.starter ? 0.4 + 0.6 * Math.min(1, f) : 0.75;
                    rate = lit ? LIGHT_RATE * help * clamp((IDLE_N2 + 0.6 - g.n2) / 5, 0.05, 1) : MOTOR_RATE * f * 0.5;
                    double hungAt = g.rolled == F_HUNG ? HUNG_N2 : f < 0.5 ? 30 + 30 * f : 99;
                    if (lit && g.n2 >= hungAt) {
                        rate = 0;
                    }
                    g.ff = lit ? IDLE_FF * 1.6 : IDLE_FF * 0.8;
                    // EGT: rises toward a peak mid-start; a weak start (low pressure) runs hotter; a rolled hot start too
                    double prog = clamp((g.n2 - FUEL_N2) / (IDLE_N2 - FUEL_N2), 0, 1);
                    double nominal = Math.max(EGT_AMBIENT + 150, IDLE_EGT * prog + (EGT_START_PEAK - IDLE_EGT / 2) * Math.sin(Math.PI * prog));
                    double target = lit ? nominal + (1 - Math.min(1, f)) * 300 + (g.rolled == F_HOT ? HOT_EXTRA : 0) : g.egt;
                    g.egt += (target - g.egt) * 0.12;
                    if (g.egt > EGT_START_LIMIT) {
                        abort(g, F_HOT);
                        break;
                    }
                    if (!fed) {
                        abort(g, F_FLAMEOUT);
                        break;
                    }
                }
                g.n2 = clamp(g.n2 + rate, 0, 100);
                if (g.n2 > g.best + 0.05) {
                    g.best = g.n2;
                    g.still = 0;
                } else if ((g.state == E_CRANK || g.ticks > LIGHT_DELAY) && ++g.still > HUNG_TICKS) {
                    abort(g, F_HUNG);
                    break;
                }
                // N1 comes up with the core after light-off
                g.n1 = g.state == E_LIGHT && g.ticks >= LIGHT_DELAY ? IDLE_N1 * clamp((g.n2 - FUEL_N2) / (IDLE_N2 - FUEL_N2), 0, 1)
                        : Math.max(0, g.n1 - DOWN_N1);
                if (g.state == E_LIGHT && g.n2 >= IDLE_N2 - 0.05) {
                    run(g);
                    g.egt = Math.max(g.egt, IDLE_EGT);
                }
                if (!master) {
                    g.state = E_DOWN;
                }
                break;
            }
            case E_RUN: {
                g.starter = false;
                g.ign = false;
                if (!fed) {
                    g.fault = F_FLAMEOUT;                           // its side out of hydrogen (no crossfeed): a flameout
                    g.state = E_DOWN;
                    g.ticks = 0;
                    break;
                }
                // wave 4: the AFS' thrust (the levers' own without the A/THR) or the reverse's N1
                double want = afs.n1Want(i);
                double d = want - g.n1;
                g.n1 += clamp(d * SPOOL, -SPOOL_MAX, SPOOL_MAX);
                double k = clamp((g.n1 - IDLE_N1) / (TOGA_N1 - IDLE_N1), 0, 1);
                g.n2 = IDLE_N2 + (99 - IDLE_N2) * Math.pow(k, 0.6);
                g.egt += (IDLE_EGT + (EGT_MAX - 40 - IDLE_EGT) * Math.pow(k, 1.3) - g.egt) * 0.06;
                g.ff = IDLE_FF + (TOGA_FF - IDLE_FF) * Math.pow(k, 1.4);
                break;
            }
            case E_ABORT:
                // the auto-abort: fuel off, the starter dry-motors briefly, then everything winds down
                g.fuel = g.ign = false;
                g.ff = 0;
                g.starter = g.ticks < 60 && duct(i) >= MIN_TURN_PSI;
                g.n2 = g.starter ? Math.max(0, g.n2 - 0.1) : Math.max(0, g.n2 - DOWN_N2);
                g.n1 = Math.max(0, g.n1 - DOWN_N1);
                g.egt += (EGT_AMBIENT - g.egt) * (g.starter ? 0.06 : COOL_EGT);
                break;
            case E_DOWN:
            default:
                g.fuel = g.ign = g.starter = false;
                g.ff = 0;
                double wind = !e.ground ? clamp(e.airspeed / 2.0, 0, 1) * 18 : 0;     // windmilling in the air
                g.n2 = Math.max(wind, g.n2 - DOWN_N2);
                g.n1 = Math.max(wind * 0.5, g.n1 - DOWN_N1);
                g.egt += (EGT_AMBIENT - g.egt) * COOL_EGT;
                if (g.n2 <= wind + 0.01 && g.n1 <= wind * 0.5 + 0.01) {
                    g.state = E_OFF;
                    g.ticks = 0;
                }
                break;
        }
        if (g.state != E_RUN && g.state != E_LIGHT) {
            g.ff = 0;
        }
        if (g.state == E_LIGHT && !g.fuel) {
            g.ff = 0;
        }
        burnEng += g.ff / 1200.0;
    }

    private static void cool(Eng g, Env e) {
        g.egt += (EGT_AMBIENT - g.egt) * COOL_EGT;
        g.n2 = Math.max(0, g.n2 - DOWN_N2);
        g.n1 = Math.max(0, g.n1 - DOWN_N1);
    }

    private static void run(Eng g) {
        g.state = E_RUN;
        g.ticks = 0;
        g.starter = g.ign = false;
        g.fuel = true;
        g.n2 = Math.max(g.n2, IDLE_N2);
        g.n1 = Math.max(g.n1, IDLE_N1);
        g.egt = Math.max(g.egt, IDLE_EGT);
        g.ff = IDLE_FF;
        g.fault = F_NONE;
    }

    private void abort(Eng g, int why) {
        g.state = E_ABORT;
        g.fault = why;
        g.ticks = 0;
        g.fuel = g.ign = false;
        if (quick != QUICK_OFF) {
            stopQuick("fault");
        }
    }

    /** The engine i's start is waiting for air (master on in ЗАПУСК, no pressure). */
    public boolean noAir(int i) {
        return eng[i].state == E_WAIT;
    }

    // ---------------------------------------------------------------- fuel

    /** The tank total (mB). */
    public double tanks() {
        return tankL + tankC + tankR;
    }

    /**
     * The tanks against the vehicle's H2 (the truth - a station, a cell or the gas column fills it, the item keeps it):
     * a difference over half a mB is filled wings first (35 % each), then the centre; taken centre first, then the wings
     * evenly.
     */
    public void reconcile(double total, double cap) {
        double d = total - tanks();
        if (Math.abs(d) < 0.5) {
            return;
        }
        if (d > 0) {
            double wing = cap * SHARE_L;
            double add = Math.min(d, Math.max(0, wing - tankL) + Math.max(0, wing - tankR));
            double l = Math.min(add / 2 + Math.max(0, add / 2 - Math.max(0, wing - tankR)), Math.max(0, wing - tankL));
            double r = add - l;
            tankL += l;
            tankR += r;
            tankC += d - add;
            fuel.wingCap = wing;
            fuel.qty[LinerFuel.L] = tankL;
            fuel.qty[LinerFuel.R] = tankR;
            fuel.refuelled(l, r);                                   // wave 3: the outer cells first, «ЗАПРАВКА» on the ТОПЛ page
            events |= E_REFUEL;
        } else {
            take(-d);
        }
        tankL = Math.max(0, tankL);
        tankR = Math.max(0, tankR);
        tankC = Math.max(0, tankC);
    }

    /** Takes mB centre first, then the wings evenly (the engines' feed). */
    private void take(double mb) {
        double c = Math.min(tankC, mb);
        tankC -= c;
        double rest = mb - c;
        if (rest > 0) {
            double l = Math.min(tankL, rest / 2), r = Math.min(tankR, rest / 2);
            tankL -= l;
            tankR -= r;
            double more = rest - l - r;
            if (more > 0) {
                double l2 = Math.min(tankL, more);
                tankL -= l2;
                tankR = Math.max(0, tankR - (more - l2));
            }
        }
    }

    /**
     * Wave 3: the fuel system before the engines - the tanks into LinerFuel, its pumps (AC), the centre's mode, the
     * crossfeed, the slats, each engine burning or not and its thrust, the altitude for the gravity feed.
     */
    private void fuelPrepare(Env e, boolean ac) {
        fuel.qty[LinerFuel.L] = tankL;
        fuel.qty[LinerFuel.C] = tankC;
        fuel.qty[LinerFuel.R] = tankR;
        LinerFuel.In f = new LinerFuel.In();
        for (int p = 0; p < LinerFuel.PUMPS; p++) {
            f.pb[p] = on(LinerEcam.pump(p));
        }
        f.manual = on(LinerControls.FUEL_MODE);
        f.xfeed = on(LinerControls.XFEED);
        f.ac = ac;
        f.slatsIn = fctl.slatPos < 0.05;
        f.ground = e.ground;
        for (int i = 0; i < 2; i++) {
            Eng g = eng[i];
            f.run[i] = g.state == E_RUN || g.state == E_LIGHT && g.fuel;
            f.thrust[i] = g.state == E_RUN ? clamp((g.n1 - IDLE_N1) / (TOGA_N1 - IDLE_N1), 0, 1) : 0;
        }
        f.alt = e.alt;
        f.gravAlt = e.gravAlt;
        f.sat = sat(e.alt);
        f.instant = e.instant;
        fuel.prepare(f);
        tankL = fuel.qty[LinerFuel.L];
        tankC = fuel.qty[LinerFuel.C];
        tankR = fuel.qty[LinerFuel.R];
    }

    /** The outside air (°C) at altitude Y: 15 at sea level (Y 64), -6.5 every 100 blocks. */
    public static double sat(double alt) {
        return 15 - Math.max(0, alt - 64) * 0.065;
    }

    /**
     * This tick's burn out of the tanks (wave 3: through LinerFuel - each engine from its side's sources, centre first; the
     * APU from the left wing); burnEng / burnApu what was really taken.
     */
    private void burnFuel() {
        double apuWant = 0;
        if (apu == A_START || apuRunning()) {
            double ff = APU_FF + (apuBleedOpen || apuGenOn && (ac1Src == S_APU || ac2Src == S_APU) ? APU_FF_LOAD : 0);
            apuWant = ff / 1200.0;
        }
        fuel.qty[LinerFuel.L] = tankL;
        fuel.qty[LinerFuel.C] = tankC;
        fuel.qty[LinerFuel.R] = tankR;
        double[] got = fuel.draw(new double[] {eng[0].ff / 1200.0, eng[1].ff / 1200.0}, apuWant);
        burnEng = got[0];
        burnApu = got[1];
        tankL = fuel.qty[LinerFuel.L];
        tankC = fuel.qty[LinerFuel.C];
        tankR = fuel.qty[LinerFuel.R];
    }

    /** The roll tendency of uneven wing tanks (-1 left heavy .. 1; the physics' small roll bias). */
    public double fuelRoll() {
        return fuel.rollBias();
    }

    /**
     * Wave 3, cosmetic (the КОНД / ДВЕРИ pages): each zone warms / cools to its CAB_T while its pack blows (pack 1 the
     * cockpit and the front, pack 2 the aft; one pack does all), else drifts to the outside air; the cabin altitude climbs to
     * ~30 % of the aircraft's with the packs and the door shut (unpressurised: the aircraft's own), its rate in m/s.
     */
    private void cabinTick(Env e) {
        boolean p1 = on(LinerControls.PACK1) && ductL > 0, p2 = on(LinerControls.PACK2) && ductR > 0;
        double out = sat(e.alt) + (e.ground ? 6 : 0);
        for (int z = 0; z < 3; z++) {
            boolean blow = z < 2 ? p1 || p2 : p2 || p1;
            double t = blow ? CAB_T[z] : out;
            cabT[z] += (t - cabT[z]) * (blow ? 0.004 : 0.0005);
        }
        double above = Math.max(0, e.alt - 64);
        double want = e.ground ? 0 : (p1 || p2) && !door ? Math.min(2400, above * 0.3) : above;
        double before = cabAlt;
        cabAlt += clamp(want - cabAlt, -1.5, 1.5);
        cabVs = (cabAlt - before) * 20;
    }

    // ---------------------------------------------------------------- wave 2: hydraulics, flight controls, gear, brakes

    /** The hydraulics' inputs from this tick's engines, buses and switches. */
    private LinerHyd.In hydIn(Env e) {
        LinerHyd.In h = new LinerHyd.In();
        h.n2L = eng[0].n2;
        h.n2R = eng[1].n2;
        h.runL = eng[0].running();
        h.runR = eng[1].running();
        h.ac1 = ac1Src != S_NONE;
        h.ac2 = ac2Src != S_NONE;
        h.gensLost = !gen1On && !gen2On && !apuGenOn && !extOn;
        h.ground = e.ground;
        h.airspeed = e.airspeed;
        h.pbEdp1 = on(LinerControls.HYD_ENG1);
        h.pbEdp2 = on(LinerControls.HYD_ENG2);
        h.pbBlue = on(LinerControls.HYD_BLUE);
        h.pbYellow = on(LinerControls.HYD_YEL);
        h.pbPtu = on(LinerControls.HYD_PTU);
        h.pbRat = on(LinerControls.RAT);
        h.random = e.random;
        h.leakChance = e.leakChance;
        h.instant = e.instantHyd;
        return h;
    }

    /** The flight controls' inputs (server: the keys; a client: what it sees) - the shared part. */
    LinerFctl.In fctlIn(float roll, float pitch, float yaw, double speed, boolean ground, float trimExtra) {
        LinerFctl.In f = new LinerFctl.In();
        f.roll = roll;
        f.pitch = pitch;
        f.yaw = yaw;
        f.spdBrk = at(LinerControls.SPD_BRK);
        f.flaps = at(LinerControls.FLAPS);
        f.ground = ground;
        f.speed = speed;
        f.trim = thsTrim(fctl.flapPos) + trimExtra;
        LinerControls.Ctl[] pb = computers();
        for (int i = 0; i < pb.length; i++) {
            f.pb[i] = on(pb[i]);
        }
        f.ess = powered();
        f.ac = acPowered();
        f.adr = adrCount();
        f.ir = irCount();
        f.gearDown = at(LinerControls.GEAR) == 1;
        f.hyd = hyd;
        return f;
    }

    /** The F/CTL computers' push buttons in LinerFctl.C_* order. */
    public static LinerControls.Ctl[] computers() {
        return new LinerControls.Ctl[] {LinerControls.ELAC1, LinerControls.ELAC2, LinerControls.SEC1, LinerControls.SEC2, LinerControls.SEC3,
                LinerControls.FAC1, LinerControls.FAC2};
    }

    /** The THS's auto-trim (deg nose up): a little nose up, more with the flaps out. */
    public static float thsTrim(double flapPos) {
        return (float) (1.5 + 0.6 * flapPos);
    }

    /** ADRs working: the push button in, the ADIRS selector not off, the board powered. */
    public int adrCount() {
        return adirs(new LinerControls.Ctl[] {LinerControls.ADR1, LinerControls.ADR2, LinerControls.ADR3});
    }

    /** IRs working (the same rule with the IR push buttons). */
    public int irCount() {
        return adirs(new LinerControls.Ctl[] {LinerControls.IR1, LinerControls.IR2, LinerControls.IR3});
    }

    private int adirs(LinerControls.Ctl[] pb) {
        LinerControls.Ctl[] sel = {LinerControls.ADIRS1, LinerControls.ADIRS2, LinerControls.ADIRS3};
        int n = 0;
        for (int i = 0; i < 3; i++) {
            n += on(pb[i]) && at(sel[i]) != 0 && powered() ? 1 : 0;
        }
        return n;
    }

    /** One server tick of wave 2: the hydraulics, the flight controls (the spoilers' and high lift's decisions), the gear. */
    private void wave2Tick(Env e) {
        boolean touchdown = e.ground && !onGround;
        hyd.tick(hydIn(e));
        if (hyd.bark) {
            events |= E_PTU;
        }
        if (hyd.ratOut) {
            events |= E_RAT;
        }
        LinerFctl.In f = fctlIn(e.roll, e.pitch, e.yaw, e.speed, e.ground, 0F);
        f.touchdown = touchdown;
        f.throttle = e.throttle;
        f.instant = e.instantHyd;
        fctl.tick(f);
        LinerGear.In g = new LinerGear.In();
        g.leverUp = at(LinerControls.GEAR) == 0;
        g.ground = e.ground;
        g.touchdown = touchdown;
        g.speed = e.speed;
        g.throttle = e.throttle;
        g.mu = e.mu;
        g.pedal = e.pedal;
        g.park = on(LinerControls.PARK_BRK);
        g.antiskidSw = at(LinerControls.ANTISKID) == 0;
        g.fan = on(LinerControls.BRK_FAN);
        g.tow = at(LinerControls.TOW) == 0;
        g.ac = acPowered();
        g.gndSpl = fctl.gndSpl;
        g.rto = fctl.rto;
        g.steer = e.yaw;
        g.hyd = hyd;
        g.instant = e.instantHyd;
        gear.crank = at(LinerControls.GEAR_GRAV);
        double before = gear.pos;
        gear.tick(g);
        if (before != gear.pos && (before == 0 || before == 1 || gear.pos == 0 || gear.pos == 1)) {
            events |= E_GEAR;
        }
        if (gear.fuseEvent != 0) {
            events |= E_FUSE;
        }
        // the autobrake's buttons follow its mode (the pedals / a fault let them out)
        set(LinerControls.ABRK_LO, gear.abMode == 1 ? 1 : 0);
        set(LinerControls.ABRK_MED, gear.abMode == 2 ? 1 : 0);
        set(LinerControls.ABRK_MAX, gear.abMode == 3 ? 1 : 0);
        // the F/CTL check: the first stick deflection on the ground with pressure shows УПР for FCTL_CHECK_TICKS
        if (!e.ground) {
            fctlCheck = 0;
        } else if (fctlCheck > 0) {
            fctlCheck = e.throttle >= LinerFctl.TO_THR ? 0 : fctlCheck - 1;
        }
        if (e.ground && !fctlChecked && hyd.mask() != 0 && (e.roll != 0 || e.pitch != 0)) {
            fctlChecked = true;
            fctlCheck = FCTL_CHECK_TICKS;
        }
        if (touchdown || e.ground && hyd.mask() == 0) {
            fctlChecked = false;                                    // a new ground run (or the aircraft shut down): check again
        }
        onGround = e.ground;
    }

    /**
     * A client's tick of the surfaces (the server syncs the pressures, the computers' buttons, the spoilers' mode and the
     * high lift; each client moves the surfaces itself from the stick it sees): roll / pitch / yaw -1..1, the ground speed,
     * on the ground, the extra trim (deg).
     */
    public void clientTick(float roll, float pitch, float yaw, double speed, boolean ground, float trimExtra) {
        LinerFctl.In f = fctlIn(roll, pitch, yaw, speed, ground, trimExtra);
        f.server = false;
        int law = fctl.law;
        fctl.tick(f);
        fctl.law = law;
    }

    /** A push button's amber ОТКАЗ / ГОРЯЧ half lit (wave 2's: the hydraulic pumps, the F/CTL computers, the brake fans). */
    public boolean faultLamp(LinerControls.Ctl c) {
        // wave 3: a fuel pump in without pressure; the centre mode's A320 MODE SEL fault (amber «ЦЕНТР»)
        for (int p = 0; p < LinerFuel.PUMPS; p++) {
            if (c == LinerEcam.pump(p)) {
                return (fuel.lowPr & 1 << p) != 0;
            }
        }
        if (c == LinerControls.FUEL_MODE) {
            return fuel.modeFault;
        }
        int f = hyd.faults;
        if (c == LinerControls.HYD_ENG1) {
            return (f & LinerHyd.F_EDP1) != 0;
        }
        if (c == LinerControls.HYD_ENG2) {
            return (f & LinerHyd.F_EDP2) != 0;
        }
        if (c == LinerControls.HYD_BLUE) {
            return (f & LinerHyd.F_ELEC_B) != 0;
        }
        if (c == LinerControls.HYD_YEL) {
            return (f & LinerHyd.F_ELEC_Y) != 0;
        }
        if (c == LinerControls.HYD_PTU) {
            return (f & LinerHyd.F_PTU) != 0;
        }
        if (c == LinerControls.BRK_FAN) {
            return gear.hot();
        }
        LinerControls.Ctl[] pb = computers();
        for (int i = 0; i < pb.length; i++) {
            if (c == pb[i]) {
                return on(c) && (fctl.comp & 1 << i) == 0;          // in, but not working (its bus dead)
            }
        }
        return false;
    }

    /** A push button's green upper half lit: the autobrake's ТОРМОЖ. (decelerating at its rate). */
    public boolean greenLamp(LinerControls.Ctl c) {
        if (c == LinerControls.XFEED) {
            return fuel.xfeedOpen;                                  // wave 3: КОЛЬЦЕВ. «ОТКР» - the valve fully open
        }
        return gear.abDecel && (c == LinerControls.ABRK_LO && gear.abMode == 1 || c == LinerControls.ABRK_MED && gear.abMode == 2
                || c == LinerControls.ABRK_MAX && gear.abMode == 3);
    }

    /** The pedestal's red «ШАССИ» lamp: the gravity extension under way, or the gear disagreeing with its lever. */
    public boolean gearRedLamp() {
        return gear.gravity && !gear.downLocked() || gear.disagree > LinerGear.DISAGREE_TICKS;
    }

    /** The gear's sync words (the client mirror): the brake modes and the steering derive from the synced pressures. */
    private int wave2Flags() {
        return (hyd.edp1 ? 1 : 0) | (hyd.edp2 ? 2 : 0) | (hyd.elecB ? 4 : 0) | (hyd.elecY ? 8 : 0) | (hyd.rat ? 16 : 0) | (hyd.ptu ? 32 : 0)
                | (hyd.ratElec ? 64 : 0) | (gear.gravity ? 128 : 0) | (gear.antiskid ? 256 : 0) | (gear.abActive ? 512 : 0)
                | (gear.abDecel ? 1024 : 0) | (gear.parkHold ? 2048 : 0) | (gear.nws ? 4096 : 0) | (fctl.gndSpl ? 8192 : 0)
                | (fctl.flapJam ? 16384 : 0) | (fctl.slatJam ? 32768 : 0) | (onGround ? 65536 : 0) | (fctlCheck > 0 ? 131072 : 0)
                | (hyd.hot[0] ? 1 << 18 : 0) | (hyd.hot[1] ? 1 << 19 : 0) | (hyd.hot[2] ? 1 << 20 : 0) | (gear.flat & 15) << 21
                | (hyd.leak[0] > 0 ? 1 << 25 : 0) | (hyd.leak[1] > 0 ? 1 << 26 : 0) | (hyd.leak[2] > 0 ? 1 << 27 : 0);
    }

    /** The third part of the sync string (wave 2): flags, pressures (/10), quantities (dl), temps, the gear, brakes, high lift. */
    private int[] wave2Numbers() {
        return new int[] {wave2Flags(), r(hyd.psi[0] / 10), r(hyd.psi[1] / 10), r(hyd.psi[2] / 10), r(hyd.qty[0] * 10), r(hyd.qty[1] * 10),
                r(hyd.qty[2] * 10), r(hyd.temp[0]), r(hyd.temp[1]), r(hyd.temp[2]), r(hyd.accu / 10), hyd.faults, r(gear.pos * 1000), gear.crank,
                gear.mode, gear.abMode, r5(gear.temp[0]), r5(gear.temp[1]), r5(gear.temp[2]), r5(gear.temp[3]), r(gear.brakeL / 10),
                r(gear.brakeR / 10), r(fctl.flapPos * 100), r(fctl.slatPos * 100), fctl.comp, fctl.law, autoSd};
    }

    private void unpackWave2(int[] w) {
        if (w.length < 27) {
            return;
        }
        int f = w[0];
        hyd.edp1 = (f & 1) != 0;
        hyd.edp2 = (f & 2) != 0;
        hyd.elecB = (f & 4) != 0;
        hyd.elecY = (f & 8) != 0;
        hyd.rat = (f & 16) != 0;
        hyd.ptu = (f & 32) != 0;
        hyd.ratElec = (f & 64) != 0;
        gear.gravity = (f & 128) != 0;
        gear.antiskid = (f & 256) != 0;
        gear.abActive = (f & 512) != 0;
        gear.abDecel = (f & 1024) != 0;
        gear.parkHold = (f & 2048) != 0;
        gear.nws = (f & 4096) != 0;
        fctl.gndSpl = (f & 8192) != 0;
        fctl.flapJam = (f & 16384) != 0;
        fctl.slatJam = (f & 32768) != 0;
        onGround = (f & 65536) != 0;
        fctlCheck = (f & 131072) != 0 ? 1 : 0;
        for (int s = 0; s < 3; s++) {
            hyd.hot[s] = (f & 1 << (18 + s)) != 0;
            hyd.leak[s] = (f & 1 << (25 + s)) != 0 ? LinerHyd.LEAK_RATE : 0;
            hyd.psi[s] = w[1 + s] * 10.0;
            hyd.qty[s] = w[4 + s] / 10.0;
            hyd.temp[s] = w[7 + s];
        }
        gear.flat = f >> 21 & 15;
        hyd.accu = w[10] * 10.0;
        hyd.faults = w[11];
        gear.pos = w[12] / 1000.0;
        gear.crank = w[13];
        gear.mode = w[14];
        gear.abMode = w[15];
        for (int i = 0; i < 4; i++) {
            gear.temp[i] = w[16 + i];
        }
        gear.brakeL = w[20] * 10.0;
        gear.brakeR = w[21] * 10.0;
        fctl.flapPos = w[22] / 100.0;
        fctl.slatPos = w[23] / 100.0;
        fctl.comp = w[24];
        fctl.law = w[25];
        autoSd = w[26];
    }

    private static int r(double v) {
        return (int) Math.round(v);
    }

    /** Rounded to 5 (the brake temperatures: a resync every few degrees, not every tick of cooling). */
    private static int r5(double v) {
        return (int) Math.round(v / 5) * 5;
    }

    // ---------------------------------------------------------------- warnings and memos

    /**
     * Warnings (red, W_*) and cautions (amber, C_*) of waves 1-2 - bits of `warn` / `caut` (lang sva.liner.msg.KEY). Wave 3:
     * every message (these and the new ones) lives in LinerEcam's table, which evaluates them and keeps these bits as the
     * raw conditions (LinerEcam.M_* ids 0..6 and 12..38 in this order).
     */
    public static final int W_ENG1_FAIL = 1, W_ENG2_FAIL = 2,
            C_ENG1_START = 1, C_ENG2_START = 2, C_ENG1_AIR = 4, C_ENG2_AIR = 8, C_GEN1 = 16, C_GEN2 = 32, C_APU = 64, C_BLEED = 128,
            C_BUS1 = 256, C_BUS2 = 512;
    /**
     * Wave 2: the red dual hydraulic losses, the take-off configuration with hot brakes, the gear not down for the landing;
     * the amber single systems' low pressure / low level / overheat, hot brakes, jammed flaps / slats, the gear disagreeing
     * with its lever, a flat tyre, the flight control law, a computer lost.
     */
    public static final int W_HYD_GB = 4, W_HYD_GY = 8, W_HYD_BY = 16, W_TO_BRK = 32, W_GEAR_NOT_DOWN = 64,
            C_HYD_G_LO = 1 << 10, C_HYD_B_LO = 1 << 11, C_HYD_Y_LO = 1 << 12, C_HYD_G_QTY = 1 << 13, C_HYD_B_QTY = 1 << 14, C_HYD_Y_QTY = 1 << 15,
            C_HYD_G_HOT = 1 << 16, C_HYD_B_HOT = 1 << 17, C_HYD_Y_HOT = 1 << 18, C_BRK_HOT = 1 << 19, C_FLAP_JAM = 1 << 20, C_SLAT_JAM = 1 << 21,
            C_GEAR_DISAGREE = 1 << 22, C_TYRE = 1 << 23, C_LAW_ALTN = 1 << 24, C_LAW_DIRECT = 1 << 25, C_FCTL_COMP = 1 << 26;
    public static final String[] WARN_KEYS = {"eng1Fail", "eng2Fail", "hydGB", "hydGY", "hydBY", "toBrkHot", "gearNotDown"};
    public static final String[] CAUT_KEYS = {"eng1Start", "eng2Start", "eng1Air", "eng2Air", "gen1", "gen2", "apuFault", "bleedLow", "bus1", "bus2",
            "hydGLo", "hydBLo", "hydYLo", "hydGQty", "hydBQty", "hydYQty", "hydGHot", "hydBHot", "hydYHot", "brkHot", "flapJam", "slatJam",
            "gearDisagree", "tyreFlat", "lawAltn", "lawDirect", "fctlComp"};

    /** Memos (green / amber notes on the E/WD's right): M_* bits. */
    public static final int M_APU_AVAIL = 1, M_APU_BLEED = 2, M_PARK = 4, M_BEACON_OFF = 8, M_EXT = 16, M_DOOR = 32, M_STAIRS = 64, M_GALLEY = 128,
            M_SEATBELTS = 256, M_NO_SMOKING = 512, M_ON_BAT = 1024, M_ENG_START = 2048, M_IDLE = 4096, M_QUICK = 8192,
            // wave 2: the ground spoilers armed, the autobrake armed (its mode in the line), the RAT out, the PTU running, the
            // gravity extension, the brake fans on
            M_SPL_ARM = 16384, M_AUTOBRK = 32768, M_RAT = 65536, M_PTU = 131072, M_GRAVITY = 262144, M_BRK_FAN = 524288,
            // wave 3: refuelling, the crossfeed open, «ТО КОНФИГ НОРМ» (the T.O. CONFIG test passed), RCL's «НОРМА», the fuel's
            // gravity feed, the STATUS reminder
            M_REFUEL = 1 << 20, M_XFEED = 1 << 21, M_TO_NORMAL = 1 << 22, M_NORMAL = 1 << 23, M_GRAV_FEED = 1 << 24, M_STS = 1 << 25,
            // wave 4: the reversers deployed (green «РЕВ»)
            M_REV = 1 << 26,
            // wave 5: the IRS aligning (green «ИНС ВЫСТ.»), waiting for the MCDU's IRS INIT (amber)
            M_IRS_ALIGN = 1 << 27, M_IRS_INIT = 1 << 28;
    public static final String[] MEMO_KEYS = {"apuAvail", "apuBleed", "park", "beaconOff", "ext", "door", "stairs", "galley", "seatBelts", "noSmoking",
            "onBat", "engStart", "idle", "quick", "splArm", "autoBrk", "rat", "ptu", "gravity", "brkFan", "refuel", "xfeed", "toNormal", "normal",
            "gravFeed", "sts", "rev", "irsAlign", "irsInit"};
    /** Memos drawn amber (the rest green). */
    public static final int MEMO_AMBER = M_BEACON_OFF | M_DOOR | M_GALLEY | M_ON_BAT | M_RAT | M_GRAVITY | M_GRAV_FEED | M_IRS_INIT;

    /** The memo bits now (both sides compute them from the synced state). */
    public int memos(boolean ground) {
        int m = 0;
        if (apuRunning()) {
            m |= M_APU_AVAIL;
        }
        if (apuBleedOpen) {
            m |= M_APU_BLEED;
        }
        if (on(LinerControls.PARK_BRK)) {
            m |= M_PARK;
        }
        boolean starting = false;
        for (Eng g : eng) {
            starting |= g.state == E_WAIT || g.state == E_CRANK || g.state == E_LIGHT;
        }
        if (starting && at(LinerControls.BEACON) != 0) {
            m |= M_BEACON_OFF;                                      // «без маячка только памятка»
        }
        if (starting) {
            m |= M_ENG_START;
        }
        if (extOn) {
            m |= M_EXT;
        }
        if (door) {
            m |= M_DOOR | (ground ? M_STAIRS : 0);
        }
        if (galleyShed) {
            m |= M_GALLEY;
        }
        if (at(LinerControls.SEAT_BELTS) == 0) {
            m |= M_SEATBELTS;
        }
        if (at(LinerControls.NO_SMOKING) == 0) {
            m |= M_NO_SMOKING;
        }
        if (essOnly) {
            m |= M_ON_BAT;
        }
        if (quick != QUICK_OFF) {
            m |= M_QUICK;
        }
        if (at(LinerControls.SPD_BRK) == 0) {
            m |= M_SPL_ARM;
        }
        if (gear.abMode != 0) {
            m |= M_AUTOBRK;
        }
        if (hyd.rat) {
            m |= M_RAT;
        }
        if (hyd.ptu) {
            m |= M_PTU;
        }
        if (gear.gravity) {
            m |= M_GRAVITY;
        }
        if (on(LinerControls.BRK_FAN)) {
            m |= M_BRK_FAN;
        }
        if (fuel.refuel > 0) {
            m |= M_REFUEL;
        }
        if (fuel.xfeedOpen) {
            m |= M_XFEED;
        }
        if (ecam.toNormal()) {
            m |= M_TO_NORMAL;
        }
        if (ecam.rclNormal > 0) {
            m |= M_NORMAL;
        }
        if ((fuel.src & (LinerFuel.S_GRAV_L | LinerFuel.S_GRAV_R)) != 0 && anyRunning()) {
            m |= M_GRAV_FEED;
        }
        if (ecam.hasStatus() && autoPage() != LinerEcam.P_STS && ecam.top() < 0) {
            m |= M_STS;
        }
        if (afs.revPos[0] > 0.05 || afs.revPos[1] > 0.05) {
            m |= M_REV;
        }
        if (afs.irsState == 1) {
            m |= M_IRS_ALIGN;
        } else if (afs.irsState == 2) {
            m |= M_IRS_INIT;
        }
        return m;
    }

    /** Minutes the batteries alone keep the essentials (the memo «на АКБ: N мин»). */
    public double batteryMinutes(double bat1) {
        double n = (bat1On ? bat1 : 0) + (bat2On ? bat2.charge : 0);
        return n / ESS_LOAD / 1200.0;
    }

    // ---------------------------------------------------------------- power for the displays

    /** Displays: PFD1, ND1, EWD, SD, ND2, PFD2, ISIS (DU_*). */
    public static final int DU_PFD1 = 0, DU_ND1 = 1, DU_EWD = 2, DU_SD = 3, DU_ND2 = 4, DU_PFD2 = 5, DU_ISIS = 6, DUS = 7;

    /** Is this display powered: everything with AC; on the batteries alone the captain's PFD and ISIS. */
    public boolean duPowered(int du) {
        if (ac1Src != S_NONE || ac2Src != S_NONE) {
            return true;
        }
        return essOnly && (du == DU_PFD1 || du == DU_ISIS);
    }

    /** Any electrical power on board (the batteries' bus at least): the overhead's integral lighting, the transponder. */
    public boolean powered() {
        return essOnly || ac1Src != S_NONE || ac2Src != S_NONE;
    }

    /** AC on board (the generators / external power): everything works. */
    public boolean acPowered() {
        return ac1Src != S_NONE || ac2Src != S_NONE;
    }

    /** The SD page shown: the pilot's key, else the server's automatic page (wave 3: LinerEcam.autoPage, sheet 13a). */
    public int autoPage() {
        return sdPage > 0 ? sdPage - 1 : autoSd;                    // wave 2: the server judges the automatic page (autoSdPage)
    }

    // ---------------------------------------------------------------- quick start

    /** «Быстрый запуск» asked for: refused (a reason in quickWhy) when off by config or already running. */
    public boolean startQuick() {
        quickWhy = "";
        if (eng[0].running() && eng[1].running()) {
            quickWhy = "running";
            return false;
        }
        quick = Q_BAT;
        quickTicks = 0;
        quickAll = 0;
        return true;
    }

    public void stopQuick(String why) {
        quick = QUICK_OFF;
        quickWhy = why;
    }

    /** The quick start's step: the pilot's actions in order (sheet 15), a step every QUICK_STEP ticks once its condition holds. */
    private void quickTick(Env e) {
        if (quick == QUICK_OFF) {
            return;
        }
        if (!e.quickAllowed) {
            stopQuick("disabled");
            return;
        }
        quickTicks++;
        if (++quickAll > QUICK_MAX) {
            stopQuick("timeout");
            return;
        }
        if (!e.ground) {
            stopQuick("air");
            return;
        }
        if (quickTicks < QUICK_STEP) {
            return;
        }
        switch (quick) {
            case Q_BAT:
                if (!(e.bat1In && e.bat1 > e.bat1Cap * 0.1 || bat2In && bat2.charge > BatteryMath.capacity(VehicleType.LINER) * 0.1)) {
                    stopQuick("battery");
                    return;
                }
                if (tanks() <= 1) {
                    stopQuick("fuel");
                    return;
                }
                set(LinerControls.BAT1, 1);
                set(LinerControls.BAT2, 1);
                set(LinerControls.PARK_BRK, 1);
                next(Q_APU_MASTER);
                break;
            case Q_APU_MASTER:
                set(LinerControls.APU_MASTER, 1);
                set(LinerControls.APU_GEN, 1);
                set(LinerControls.ADIRS1, 1);
                set(LinerControls.ADIRS2, 1);
                set(LinerControls.ADIRS3, 1);
                next(Q_APU_START);
                break;
            case Q_APU_START:
                if (apuRunning()) {
                    next(Q_APU_AVAIL);
                } else if (apuFault) {
                    stopQuick("apu");
                } else if (!apuStartLit && apu != A_START) {
                    press(LinerControls.APU_START);
                    if (apuStartLit || apu == A_START) {
                        next(Q_APU_AVAIL);
                    }
                } else {
                    next(Q_APU_AVAIL);
                }
                break;
            case Q_APU_AVAIL:
                if (apuFault) {
                    stopQuick("apu");
                } else if (apuRunning()) {
                    set(LinerControls.APU_BLEED, 1);
                    set(LinerControls.PACK1, 0);                    // the checklist: packs off for the start
                    set(LinerControls.PACK2, 0);
                    set(LinerControls.BEACON, 0);
                    set(LinerControls.NAV_LOGO, 0);
                    set(LinerControls.ENG_MODE, 2);
                    next(Q_ENG2);
                }
                break;
            case Q_ENG2:
                set(LinerControls.ENG_MASTER2, 1);                  // engine 2 first (the yellow system, the parking brake)
                next(Q_ENG2_IDLE);
                break;
            case Q_ENG2_IDLE:
                if (eng[1].running()) {
                    next(Q_ENG1);
                }
                break;
            case Q_ENG1:
                set(LinerControls.ENG_MASTER1, 1);
                next(Q_ENG1_IDLE);
                break;
            case Q_ENG1_IDLE:
                if (eng[0].running()) {
                    set(LinerControls.ENG_MODE, 1);
                    set(LinerControls.APU_BLEED, 0);
                    set(LinerControls.PACK1, 1);
                    set(LinerControls.PACK2, 1);
                    set(LinerControls.APU_MASTER, 0);               // the APU cools down and goes off by itself
                    set(LinerControls.STROBE, 1);
                    next(Q_DONE);
                }
                break;
            default:
                quick = QUICK_OFF;
                quickWhy = "done";
                break;
        }
    }

    private void next(int step) {
        quick = step;
        quickTicks = 0;
        events |= E_CLICK;
    }

    // ---------------------------------------------------------------- the old switch word (lights, INS, the board)

    /**
     * The D2 switch word the rest of the addon reads (SystemsMath.SW_*): АКБ = any battery push button, ГЕН = any
     * generator online, НАВ / СТРОБ / МАЯЧОК / ПОС. ФАРЫ from the exterior lights, ОБОГРЕВ from the probe heat (or the
     * engines running - automatic on the A320), ИНС = ADIRS 1's selector not off.
     */
    public int switchWord() {
        int sw = 0;
        if (powered()) {
            sw |= 1 << SystemsMath.SW_BAT;
        }
        if (gen1On || gen2On || apuGenOn || extOn) {
            sw |= 1 << SystemsMath.SW_GEN;
        }
        if (at(LinerControls.NAV_LOGO) != 1) {
            sw |= 1 << SystemsMath.SW_NAV;
        }
        if (at(LinerControls.STROBE) == 0) {
            sw |= 1 << SystemsMath.SW_STROBE;
        }
        if (at(LinerControls.BEACON) == 0) {
            sw |= 1 << SystemsMath.SW_BEACON;
        }
        if (at(LinerControls.LAND_L) == 0 || at(LinerControls.LAND_R) == 0) {
            sw |= 1 << SystemsMath.SW_LAMP;
        }
        if (on(LinerControls.AI_PROBE) || eng[0].running() || eng[1].running()) {
            sw |= 1 << SystemsMath.SW_HEAT;
        }
        if (at(LinerControls.ADIRS1) != 0) {
            sw |= 1 << SystemsMath.SW_INS;
        }
        return sw;
    }

    /** The thrust the physics sees: both engines' mean (0..1) - wave 4: an engine with its cowls open pushes no more forward. */
    public double thrust() {
        return (fwd(0) + fwd(1)) / 2;
    }

    /** Wave 4: engine i's forward thrust (0 with the reverser's cowls opening). */
    public double fwd(int i) {
        return afs.revPos[i] > 0.05 ? 0 : eng[i].thrust();
    }

    /** Wave 4: the reverse thrust (0..1 of the forward's scale, both engines' mean): the cowls deployed, REV_EFF of the N1's share. */
    public double revThrust() {
        return (rev(0) + rev(1)) / 2;
    }

    public double rev(int i) {
        return afs.revPos[i] >= 0.95 ? LinerAfs.REV_EFF * eng[i].thrust() : 0;
    }

    /** The yaw from uneven thrust (deg a tick, + = to the right): engine 1 (left) stronger yaws right (the reverse the other way). */
    public double asymYaw(double gain) {
        return (fwd(0) - rev(0) - fwd(1) + rev(1)) * gain;
    }

    /** Wave 4: the AFS' tick with this tick's aircraft (ground / height / altitude / speed from the systems' own inputs). */
    private void afsTick(Env e) {
        LinerAfs.Air a = e.air != null ? e.air : new LinerAfs.Air();
        a.ground = e.ground;
        a.ra = e.height;
        a.y = e.alt;
        a.speed = Math.max(e.airspeed, e.speed);
        afs.lever = clamp(e.throttle, 0, 1);
        // wave 5: the FMS first - the constraints, the path, the V-speeds, FLEX, the managed speeds for the AFS
        LinerFms.Ctx c = new LinerFms.Ctx();
        c.x = a.x;
        c.z = a.z;
        c.y = a.y;
        c.gs = a.speed;
        c.ground = a.ground;
        c.fob = tanks();
        c.gw = a.mass;
        c.burn = e.burn;
        fms.tick(this, a, c, ticks++);
        afs.tick(this, e, a);
        lastAir = a;
        if (afs.cavNote >= 0 || afs.soundCall != 0) {
            events |= E_AFS;
        }
    }

    /** Any engine running (the old engine word: the HUD, the sounds, the regen logic). */
    public boolean anyRunning() {
        return eng[0].running() || eng[1].running();
    }

    // ---------------------------------------------------------------- sync (server -> clients)

    /**
     * The compact string every client gets (EntityVehicleSVA's DW_SPACE for the liner): the control positions (a char each:
     * '0' + position, + 16 with the guard lifted), '|', then integers separated by ','.
     */
    public String pack() {
        StringBuilder b = new StringBuilder(LinerControls.count() + 200);
        for (int i = 0; i < pos.length; i++) {
            b.append((char) ('0' + pos[i] + (guard[i] ? 16 : 0)));
        }
        b.append('|');
        int[] v = numbers();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(v[i]);
        }
        b.append('|');                                              // wave 2: hydraulics, gear, brakes, high lift
        int[] w = wave2Numbers();
        for (int i = 0; i < w.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(w[i]);
        }
        b.append('|');                                              // wave 3: the ECAM, the fuel, the cabin
        int[] w3 = wave3Numbers();
        for (int i = 0; i < w3.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(w3[i]);
        }
        b.append('|');                                              // wave 4: the AFS (FCU, modes, A/THR, levers, ILS)
        int[] w4 = afs.pack();
        for (int i = 0; i < w4.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(w4[i]);
        }
        return b.toString();
    }

    /** The fourth part (wave 3): the ECAM's words (LinerEcam.pack), the fuel's flags and cells, FU, temperatures, the cabin. */
    private int[] wave3Numbers() {
        int[] ec = ecam.pack();
        int f = fuel.running | fuel.lowPr << 6 | fuel.src << 12 | (fuel.xferL ? 1 << 22 : 0) | (fuel.xferR ? 1 << 23 : 0) | (fuel.modeFault ? 1 << 24 : 0)
                | (fuel.refuel > 0 ? 1 << 25 : 0) | (fuel.xfeedOpen ? 1 << 26 : 0);
        int[] tail = {f, r(fuel.outerL), r(fuel.outerR), r(fuel.used[0]), r(fuel.used[1]), r(fuel.tempL), r(fuel.tempR), r(cabT[0]), r(cabT[1]),
                r(cabT[2]), r(cabAlt / 10), r(cabVs * 10)};
        int[] all = new int[ec.length + tail.length];
        System.arraycopy(ec, 0, all, 0, ec.length);
        System.arraycopy(tail, 0, all, ec.length, tail.length);
        return all;
    }

    private void unpackWave3(int[] w) {
        int o = LinerEcam.WORDS;
        if (w.length < o + 12) {
            return;
        }
        ecam.unpack(w, 0);
        int f = w[o];
        fuel.running = f & 63;
        fuel.lowPr = f >> 6 & 63;
        fuel.src = f >> 12 & 1023;
        fuel.xferL = (f & 1 << 22) != 0;
        fuel.xferR = (f & 1 << 23) != 0;
        fuel.modeFault = (f & 1 << 24) != 0;
        fuel.refuel = (f & 1 << 25) != 0 ? 1 : 0;
        fuel.xfeedOpen = (f & 1 << 26) != 0;
        fuel.xfeedPos = fuel.xfeedOpen ? 1 : 0;
        fuel.outerL = w[o + 1];
        fuel.outerR = w[o + 2];
        fuel.used[0] = w[o + 3];
        fuel.used[1] = w[o + 4];
        fuel.tempL = w[o + 5];
        fuel.tempR = w[o + 6];
        for (int z = 0; z < 3; z++) {
            cabT[z] = w[o + 7 + z];
        }
        cabAlt = w[o + 10] * 10.0;
        cabVs = w[o + 11] / 10.0;
    }

    private int[] numbers() {
        int flags = (masterWarn ? 1 : 0) | (masterCaut ? 2 : 0) | (extAvail ? 4 : 0) | (extOn ? 8 : 0) | (gen1On ? 16 : 0) | (gen2On ? 32 : 0)
                | (apuGenOn ? 64 : 0) | (bat1On ? 128 : 0) | (bat2On ? 256 : 0) | (essOnly ? 512 : 0) | (galleyShed ? 1024 : 0)
                | (bleed1Open ? 2048 : 0) | (bleed2Open ? 4096 : 0) | (apuBleedOpen ? 8192 : 0) | (xbleedOpen ? 16384 : 0)
                | (apuStartLit ? 32768 : 0) | (apuFault ? 65536 : 0) | (bat2In ? 131072 : 0) | (door ? 262144 : 0);
        return new int[] {flags, ac1Src, ac2Src, apu, (int) Math.round(apuN * 10), (int) Math.round(apuEgt), (int) Math.round(apuFlap * 100),
                eng[0].state | eng[0].fault << 4 | (eng[0].starter ? 256 : 0) | (eng[0].ign ? 512 : 0) | (eng[0].fuel ? 1024 : 0),
                (int) Math.round(eng[0].n1 * 10), (int) Math.round(eng[0].n2 * 10), (int) Math.round(eng[0].egt), (int) Math.round(eng[0].ff),
                eng[1].state | eng[1].fault << 4 | (eng[1].starter ? 256 : 0) | (eng[1].ign ? 512 : 0) | (eng[1].fuel ? 1024 : 0),
                (int) Math.round(eng[1].n1 * 10), (int) Math.round(eng[1].n2 * 10), (int) Math.round(eng[1].egt), (int) Math.round(eng[1].ff),
                (int) Math.round(ductL * 10), (int) Math.round(ductR * 10), (int) Math.round(apuPsi * 10),
                (int) Math.round(bat1V * 10), (int) Math.round(bat1A), (int) Math.round(bat2V * 10), (int) Math.round(bat2A),
                loadGen1, loadGen2, loadApu, loadExt, (int) Math.round(tankL), (int) Math.round(tankC), (int) Math.round(tankR),
                warn, caut, sdPage, quick, (int) Math.round(bat2.charge)};
    }

    /** A client's mirror from the synced string (a malformed one leaves it as it was). */
    public void unpack(String s) {
        if (s == null) {
            return;
        }
        int bar = s.indexOf('|');
        if (bar < 0) {
            return;
        }
        for (int i = 0; i < Math.min(bar, pos.length); i++) {
            int c = s.charAt(i) - '0';
            guard[i] = c >= 16;
            pos[i] = c & 15;
        }
        String[] part = s.substring(bar + 1).split("\\|", -1);
        int[] v = ints(part[0]);
        int[] w = part.length > 1 ? ints(part[1]) : null;
        int[] w3 = part.length > 2 ? ints(part[2]) : null;
        int[] w4 = part.length > 3 ? ints(part[3]) : null;
        if (v == null || v.length < 36) {
            return;
        }
        if (w != null) {
            unpackWave2(w);
        }
        if (w3 != null) {
            unpackWave3(w3);                                        // wave 3: the ECAM, the fuel, the cabin
        }
        if (w4 != null) {
            afs.unpack(w4, 0);                                      // wave 4: the AFS
        }
        int f = v[0];
        masterWarn = (f & 1) != 0;
        masterCaut = (f & 2) != 0;
        extAvail = (f & 4) != 0;
        extOn = (f & 8) != 0;
        gen1On = (f & 16) != 0;
        gen2On = (f & 32) != 0;
        apuGenOn = (f & 64) != 0;
        bat1On = (f & 128) != 0;
        bat2On = (f & 256) != 0;
        essOnly = (f & 512) != 0;
        galleyShed = (f & 1024) != 0;
        bleed1Open = (f & 2048) != 0;
        bleed2Open = (f & 4096) != 0;
        apuBleedOpen = (f & 8192) != 0;
        xbleedOpen = (f & 16384) != 0;
        apuStartLit = (f & 32768) != 0;
        apuFault = (f & 65536) != 0;
        bat2In = (f & 131072) != 0;
        door = (f & 262144) != 0;
        ac1Src = v[1];
        ac2Src = v[2];
        apu = v[3];
        apuN = v[4] / 10.0;
        apuEgt = v[5];
        apuFlap = v[6] / 100.0;
        for (int i = 0; i < 2; i++) {
            int o = 7 + i * 5;
            Eng g = eng[i];
            g.state = v[o] & 15;
            g.fault = v[o] >> 4 & 15;
            g.starter = (v[o] & 256) != 0;
            g.ign = (v[o] & 512) != 0;
            g.fuel = (v[o] & 1024) != 0;
            g.n1 = v[o + 1] / 10.0;
            g.n2 = v[o + 2] / 10.0;
            g.egt = v[o + 3];
            g.ff = v[o + 4];
        }
        ductL = v[17] / 10.0;
        ductR = v[18] / 10.0;
        apuPsi = v[19] / 10.0;
        bat1V = v[20] / 10.0;
        bat1A = v[21];
        bat2V = v[22] / 10.0;
        bat2A = v[23];
        loadGen1 = v[24];
        loadGen2 = v[25];
        loadApu = v[26];
        loadExt = v[27];
        tankL = v[28];
        tankC = v[29];
        tankR = v[30];
        fuel.qty[LinerFuel.L] = tankL;
        fuel.qty[LinerFuel.C] = tankC;
        fuel.qty[LinerFuel.R] = tankR;
        warn = v[31];
        caut = v[32];
        sdPage = v[33];
        quick = v[34];
        bat2.charge = v[35];
    }

    /** Comma-separated integers (null: malformed). */
    private static int[] ints(String s) {
        String[] p = s.split(",");
        int[] v = new int[p.length];
        try {
            for (int i = 0; i < p.length; i++) {
                v[i] = Integer.parseInt(p[i]);
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return v;
    }

    // ---------------------------------------------------------------- save

    public static final String K_POS = "Pos", K_ENG = "Eng", K_APU = "Apu", K_TANKS = "Tanks", K_BAT2_IN = "Bat2In", K_BAT2 = "Bat2", K_DOOR = "Door";
    /** Wave 2: the marker (and its data): hydraulic quantities / leaks / the accumulator, the gear, brake temps, flat tyres, high lift. */
    public static final String K_W2 = "W2", K_HYD_QTY = "HydQty", K_HYD_LEAK = "HydLeak", K_ACCU = "Accu", K_GEAR = "GearPos", K_BRK_T = "BrkT",
            K_FLAT = "Flat", K_FLAPS = "FlapPos", K_SLATS = "SlatPos";
    /** Wave 3: the fuel's outer cells, the fuel used per engine, the transfer valves latched. */
    public static final String K_W3 = "W3Fuel";

    /** Saved: the control positions by id, the engines / APU running or not (a running liner reloads running), tanks, АКБ 2. */
    public void writeNBT(NBTTagCompound t) {
        NBTTagCompound p = new NBTTagCompound();
        for (int i = 0; i < pos.length; i++) {
            p.setByte(LinerControls.get(i).id, (byte) pos[i]);
        }
        t.setTag(K_POS, p);
        t.setByte(K_ENG, (byte) ((eng[0].running() ? 1 : 0) | (eng[1].running() ? 2 : 0)));
        t.setByte(K_APU, (byte) (apuRunning() ? 1 : 0));
        t.setIntArray(K_TANKS, new int[] {(int) Math.round(tankL), (int) Math.round(tankC), (int) Math.round(tankR)});
        t.setBoolean(K_BAT2_IN, bat2In);
        NBTTagCompound b = new NBTTagCompound();
        bat2.write(b);
        t.setTag(K_BAT2, b);
        t.setBoolean(K_DOOR, door);
        t.setBoolean(K_W2, true);
        t.setIntArray(K_HYD_QTY, new int[] {r(hyd.qty[0] * 100), r(hyd.qty[1] * 100), r(hyd.qty[2] * 100)});
        t.setByte(K_HYD_LEAK, (byte) ((hyd.leak[0] > 0 ? 1 : 0) | (hyd.leak[1] > 0 ? 2 : 0) | (hyd.leak[2] > 0 ? 4 : 0)));
        t.setInteger(K_ACCU, r(hyd.accu));
        t.setInteger(K_GEAR, r(gear.pos * 1000));
        t.setIntArray(K_BRK_T, new int[] {r(gear.temp[0]), r(gear.temp[1]), r(gear.temp[2]), r(gear.temp[3])});
        t.setByte(K_FLAT, (byte) gear.flat);
        t.setInteger(K_FLAPS, r(fctl.flapPos * 100));
        t.setInteger(K_SLATS, r(fctl.slatPos * 100));
        t.setIntArray(K_W3, new int[] {r(fuel.outerL), r(fuel.outerR), r(fuel.used[0]), r(fuel.used[1]), (fuel.xferL ? 1 : 0) | (fuel.xferR ? 2 : 0)});
        NBTTagCompound a = new NBTTagCompound();
        afs.writeNBT(a);
        t.setTag(K_W4, a);
        NBTTagCompound f = new NBTTagCompound();
        fms.writeNBT(f);
        t.setTag(K_W5, f);
    }

    /** Wave 5: the FMS (INIT, the fuel, PERF, the plan and its TMPY). */
    public static final String K_W5 = "W5Fms";

    /** Wave 4: the FCU's values, the levers, the hand-picked ILS. */
    public static final String K_W4 = "W4Afs";

    public void readNBT(NBTTagCompound t) {
        if (t == null) {
            return;
        }
        NBTTagCompound p = t.getCompoundTag(K_POS);
        for (int i = 0; i < pos.length; i++) {
            LinerControls.Ctl c = LinerControls.get(i);
            if (p.hasKey(c.id)) {
                pos[i] = Math.max(0, Math.min(c.n() - 1, p.getByte(c.id)));
            }
        }
        int e = t.getByte(K_ENG);
        for (int i = 0; i < 2; i++) {
            if ((e & 1 << i) != 0) {
                run(eng[i]);
            }
        }
        if (t.getByte(K_APU) != 0) {
            apu = A_AVAIL;
            apuN = 100;
            apuFlap = 1;
            apuEgt = APU_EGT_RUN;
        }
        int[] tk = t.getIntArray(K_TANKS);
        if (tk.length == 3) {
            tankL = Math.max(0, tk[0]);
            tankC = Math.max(0, tk[1]);
            tankR = Math.max(0, tk[2]);
        }
        if (t.hasKey(K_BAT2_IN)) {
            bat2In = t.getBoolean(K_BAT2_IN);
        }
        if (t.hasKey(K_BAT2)) {
            bat2.read(t.getCompoundTag(K_BAT2), BatteryMath.capacity(VehicleType.LINER));
        }
        door = t.getBoolean(K_DOOR);
        boolean running = (t.getByte(K_ENG) & 3) != 0;
        if (!t.hasKey(K_W2)) {
            // a wave-1 save: the speed brake lever had no ВЗВЕД - its positions move up one; the flaps where the lever is
            if (p.hasKey(LinerControls.SPD_BRK.id)) {
                set(LinerControls.SPD_BRK, p.getByte(LinerControls.SPD_BRK.id) + 1);
            }
            fctl.flapPos = at(LinerControls.FLAPS);
        } else {
            int[] q = t.getIntArray(K_HYD_QTY);
            for (int s = 0; s < 3 && q.length == 3; s++) {
                hyd.qty[s] = Math.max(0, Math.min(LinerHyd.QTY_FULL[s], q[s] / 100.0));
            }
            int lk = t.getByte(K_HYD_LEAK);
            for (int s = 0; s < 3; s++) {
                hyd.leak[s] = (lk & 1 << s) != 0 ? LinerHyd.LEAK_RATE : 0;
            }
            hyd.accu = Math.max(0, Math.min(LinerHyd.ACCU_MAX, t.getInteger(K_ACCU)));
            gear.pos = Math.max(0, Math.min(1, t.getInteger(K_GEAR) / 1000.0));
            int[] bt = t.getIntArray(K_BRK_T);
            for (int w = 0; w < 4 && bt.length == 4; w++) {
                gear.temp[w] = Math.max(LinerGear.T_AMB, bt[w]);
            }
            gear.flat = t.getByte(K_FLAT) & 15;
            fctl.flapPos = Math.max(0, Math.min(4, t.getInteger(K_FLAPS) / 100.0));
            fctl.slatPos = Math.max(0, Math.min(1, t.getInteger(K_SLATS) / 100.0));
        }
        if (t.hasKey(K_W4)) {
            afs.readNBT(t.getCompoundTag(K_W4));
        }
        if (t.hasKey(K_W5)) {
            fms.readNBT(t.getCompoundTag(K_W5));
        }
        int[] w3 = t.getIntArray(K_W3);
        if (w3.length == 5) {
            fuel.outerL = Math.max(0, Math.min(tankL, w3[0]));
            fuel.outerR = Math.max(0, Math.min(tankR, w3[1]));
            fuel.used[0] = Math.max(0, w3[2]);
            fuel.used[1] = Math.max(0, w3[3]);
            fuel.xferL = (w3[4] & 1) != 0;
            fuel.xferR = (w3[4] & 2) != 0;
        }
        if (running) {
            // a running liner reloads with its pumps' pressure (the engines run again at once)
            for (int s = 0; s < 3; s++) {
                hyd.psi[s] = hyd.low(s) ? 0 : LinerHyd.NOMINAL;
            }
            hyd.accu = Math.max(hyd.accu, hyd.psi[LinerHyd.Y]);
        }
    }

    /** A copy of everything the client mirror needs (the self-test's round trip). */
    public void copyFrom(LinerSystems o) {
        unpack(o.pack());
        for (int i = 0; i < 2; i++) {
            eng[i].copy(o.eng[i]);
        }
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
