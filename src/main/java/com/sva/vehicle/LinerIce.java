package com.sva.vehicle;

/**
 * Liner wave 6 (plan-next §7: «WING / ENG A.ICE от отбора, PROBE / WINDOW HEAT, обледенение, паки»; mock-ups veh_liner_7
 * group 8 «ПОС», veh_liner_12 the memos, veh_liner_13b «КОНД»): the SA-320's anti-ice, the icing and the air conditioning's
 * zones - pure, server-authoritative logic (LinerSystems ticks it; the amounts go to the clients in the sync string: the
 * ice on the model, the frost on the windscreen, the airspeed an iced probe shows).
 * <p>
 * <b>The air</b> (the stage D2 icing logic - SystemsMath.cold: high up or in falling snow - made an A320's: icing needs
 * cold AND visible moisture): the outside air temperature oat() from the biome's temperature at sea level (plains 15 °C,
 * a snowy biome -5) falling LAPSE °C a block above Y 64 (a game's lapse: the cloud layer at Y 128 is near freezing over
 * the plains); visible moisture - in the cloud layer (CLOUD_Y +- CLOUD_BAND), in rain / snow over Y 90, in a snowy biome
 * over Y 100. Icing between ICING_MIN and ICING_MAX °C, worst around -8 (severity()).
 * <p>
 * <b>Accretion</b> (amounts 0..1): the wing's leading edge (in flight, WING A.ICE not working) at ICE_RATE x severity,
 * each engine's nacelle lip (running or windmilling, its ENG A.ICE not working), each probe (in flight, the probe heat
 * off) at PROBE_RATE x severity; the windscreen's frost (the window heat off, oat under 0) at FROST_RATE. Shedding: the
 * anti-ice on removes it at AI_SHED (a few seconds - the event E_ICE_SHED: chunks fly off), warm air (oat over MELT_T)
 * melts it at MELT_RATE.
 * <p>
 * <b>Anti-ice</b>: WING A.ICE needs the bleed - either duct at AI_MIN_PSI or more (X BLEED joins them); on the ground only a
 * WING_TEST_TICKS test (30 s), then its valves close by themselves until the next take-off; no pressure - «ПОС КРЫЛА ОТКАЗ»
 * (the push button's ОТКАЗ). ENG N A.ICE: the engine's own bleed (running); not running in flight - «ПОС ДВ N ОТКАЗ».
 * PROBE / WINDOW HEAT (АВТО / ВКЛ): on with an engine running or in flight (АВТО) or always (ВКЛ) - on the AC buses; wanted
 * and no AC - «ОБОГРЕВ ПВД ОТКАЗ».
 * <p>
 * <b>The costs and the effects</b>: an engine's anti-ice takes ENG_AI_N1 % off its N1 limit and adds ENG_AI_FF of fuel
 * flow; the wing's takes WING_AI_N1 off both and adds WING_AI_FF. Wing ice raises the stall speed (STALL_K x ice), the
 * lift-off speed, the drag (DRAG_K) and the mass (MASS_ICE kg); engine ice shakes it (the vibration, an N1 fluctuation of
 * up to JITTER %) and at FLAMEOUT_ICE it flames out; an iced probe freezes its airspeed (the captain's PFD probe 1, the
 * F/O's probe 2, the standby 3) - one of the pilots' iced: «НЕДОСТ. СКОРОСТЬ» (the A/THR and the AP let go - simplified
 * until the ADIRS wave).
 * <p>
 * <b>Air conditioning</b>: the zone temperature selectors (КАБИНА ЭКИП. / САЛОН, 18..30 °C), the packs' flow (% - 0 off,
 * 100 with their duct's pressure; one pack alone 120 %), the zones drift to the outside air without them; the cabin cold
 * under COLD_T (the passengers' «замерзание» after COLD_TICKS - EntityVehicleSVA, config). The cabin altitude over
 * CAB_ALT_WARN - «ВЫСОТА КАБИНЫ» (red).
 */
public final class LinerIce {

    // ---------------------------------------------------------------- the air

    /** Sea level (Y) and the °C at it for a biome of temperature 0.8 (plains); °C per biome temperature unit; °C a block up. */
    public static final double SEA_Y = 64, SEA_T = 15, BIOME_K = 25, LAPSE = 0.2;
    /** The cloud layer (Y) and its half thickness; rain / snow counts over this Y, a snowy biome's air over SNOW_Y. */
    public static final double CLOUD_Y = 128, CLOUD_BAND = 6, RAIN_Y = 90, SNOW_Y = 100;
    /** Icing between these temperatures (°C), the worst at ICING_PEAK; the ice melts over MELT_T. */
    public static final double ICING_MAX = 5, ICING_MIN = -40, ICING_PEAK = -8, MELT_T = 3;

    /** The outside air temperature (°C) at altitude Y for a biome temperature (Minecraft's 0..2; the plains 0.8). */
    public static double oat(double y, double biomeTemp) {
        double t = SEA_T + (Math.max(-0.5, Math.min(2.0, biomeTemp)) - 0.8) * BIOME_K;
        return Math.min(40, t) - Math.max(0, y - SEA_Y) * LAPSE;
    }

    /** Visible moisture: in the cloud layer, in rain / snow over RAIN_Y, a snowy biome's air over SNOW_Y. */
    public static boolean moisture(double y, boolean precipitation, boolean snowyBiome) {
        return Math.abs(y - CLOUD_Y) <= CLOUD_BAND || precipitation && y >= RAIN_Y || snowyBiome && y >= SNOW_Y;
    }

    /** How hard it ices (0..1): 0 out of ICING_MIN..ICING_MAX, 1 at ICING_PEAK. */
    public static double severity(double oat) {
        if (oat > ICING_MAX || oat < ICING_MIN) {
            return 0;
        }
        if (oat >= ICING_PEAK) {
            return 0.35 + 0.65 * (ICING_MAX - oat) / (ICING_MAX - ICING_PEAK);
        }
        return Math.max(0.15, 1 - (ICING_PEAK - oat) / (ICING_PEAK - ICING_MIN) * 0.85);
    }

    // ---------------------------------------------------------------- rates

    /** Per tick at severity 1: the wing / an engine (full in ~45 s), a probe (~15 s), the windscreen frost (~30 s). */
    public static final double ICE_RATE = 1.0 / 900, PROBE_RATE = 1.0 / 300, FROST_RATE = 1.0 / 600;
    /** The engines' lips ice slower than the wing (~2 min to a flame-out at the worst: time to switch ENG A.ICE on). */
    public static final double ENG_RATE_K = 0.4;
    /** Per tick: the anti-ice / heat clearing it (~4 s), warm air melting it (~30 s). */
    public static final double AI_SHED = 1.0 / 80, MELT_RATE = 1.0 / 600;
    /** Ice detected (the lamp, the ECAM) over this; a probe iced (its airspeed frozen) at this. */
    public static final double DETECT = 0.05, PROBE_ICED = 0.999;

    // ---------------------------------------------------------------- anti-ice

    /** WING A.ICE: the least duct pressure (psi), the ground test (ticks). */
    public static final double AI_MIN_PSI = 12;
    public static final int WING_TEST_TICKS = 600;
    /** The costs: N1 limit off (%) and fuel flow added (share) - an engine's own, the wing's (both engines). */
    public static final double ENG_AI_N1 = 2.5, ENG_AI_FF = 0.03, WING_AI_N1 = 2.0, WING_AI_FF = 0.04;
    /** The thrust's share lost with the wing's anti-ice taking bleed (per engine). */
    public static final double WING_AI_THRUST = 0.03;

    // ---------------------------------------------------------------- effects

    /** Wing ice at 1: stall x (1 + STALL_K), lift-off x (1 + LIFTOFF_K), drag x (1 + DRAG_K), + MASS_ICE kg. */
    public static final double STALL_K = 0.22, LIFTOFF_K = 0.15, DRAG_K = 0.35, MASS_ICE = 700;
    /** Engine ice: the N1 fluctuation at 1 (%), the vibration over the normal (units), a flame-out at this. */
    public static final double JITTER = 4, VIB_ICE = 5, FLAMEOUT_ICE = 0.98;

    // ---------------------------------------------------------------- air conditioning

    /** The zone selectors' temperatures (°C) by position (LinerControls.TEMP_POS). */
    public static final double[] TEMP = {18, 20, 22, 24, 26, 28, 30};
    /** The cabin cold (°C) for the passengers, the exposure before «замерзание» (ticks: 2 min). */
    public static final double COLD_T = 8;
    public static final int COLD_TICKS = 2400;
    /** «ВЫСОТА КАБИНЫ» over this cabin altitude (m - LinerSystems.cabAlt's scale: above sea level Y 64). */
    public static final double CAB_ALT_WARN = 120;

    // ---------------------------------------------------------------- loads (% of one generator)

    /** The window heat (both windscreens), the probe heat (3 probes). */
    public static final int LOAD_WINDOW = 6, LOAD_PROBE = 2;

    // ---------------------------------------------------------------- state

    /** Ice amounts 0..1: the wing, the engines' lips, the probes (captain, F/O, standby), the windscreen's frost. */
    public double wing, frost;
    public final double[] eng = new double[2], probe = new double[3];
    /** What a probe's airspeed reads frozen (km/h) - set the tick it ices. */
    public final double[] frozenKmh = new double[3];
    /** The anti-ice working now: the wing's valves, the engines' valves, the probe / window heat. */
    public boolean wingOn, probeHeat;
    public final boolean[] engOn = new boolean[2];
    /** Faults now (the push buttons' ОТКАЗ, the ECAM): wing, engines, probe heat. */
    public boolean wingFault, probeFault;
    public final boolean[] engFault = new boolean[2];
    /** WING A.ICE's ground test: ticks run (WING_TEST_TICKS - its valves closed until airborne again). */
    public int wingTest;
    /** The air now: °C, moisture, icing (0..1). */
    public double oatNow = SEA_T, icing;
    public boolean wet;
    /** The pack flows (%), the passengers' cold exposure (ticks). */
    public int pack1Flow, pack2Flow, cold;
    /** The N1 shake (%) each engine carries now (LinerSystems adds the change a tick: no drift). */
    public final double[] jit = new double[2];
    /** An anti-ice / heat came on over ice this tick (chunks shed: E_ICE_SHED). */
    public boolean shed;

    /** The environment of a tick (LinerSystems.Env carries it). */
    public static final class Air {
        /** °C outside (NaN: the old sat() of LinerSystems), moisture, the icing model on (config). */
        public double oat = Double.NaN;
        public boolean moisture, icing = true;
    }

    /**
     * One server tick (after the bleed and the engines): the valves, the faults, the accretion and the shedding.
     * kmh - the true airspeed now (what a probe freezes at).
     */
    public void tick(LinerSystems L, LinerSystems.Env e, Air a, double kmh) {
        boolean ground = e.ground, ac = L.acPowered();
        oatNow = Double.isNaN(a.oat) ? LinerSystems.sat(e.alt) : a.oat;
        wet = a.moisture;
        icing = a.icing && wet ? severity(oatNow) : 0;
        shed = false;
        // WING A.ICE: the bleed from either duct; on the ground a 30 s test
        boolean wingPb = L.on(LinerControls.AI_WING);
        boolean press = Math.max(L.ductL, L.ductR) >= AI_MIN_PSI;
        if (!wingPb || !ground) {
            wingTest = 0;
        }
        boolean wantWing = wingPb && (!ground || wingTest < WING_TEST_TICKS);
        boolean wasWing = wingOn;
        wingOn = wantWing && press;
        if (wingOn && ground) {
            wingTest++;
        }
        wingFault = wantWing && !press && L.powered();
        // ENG A.ICE: the engine's own bleed
        for (int i = 0; i < 2; i++) {
            boolean pb = L.on(i == 0 ? LinerControls.AI_ENG1 : LinerControls.AI_ENG2);
            boolean was = engOn[i];
            boolean bleed = L.eng[i].running() && L.eng[i].n2 >= LinerSystems.ENG_BLEED_N2;
            engOn[i] = pb && bleed;
            engFault[i] = pb && !bleed && !ground && L.powered();
            if (engOn[i] && !was && eng[i] > DETECT) {
                shed = true;
            }
        }
        // PROBE / WINDOW HEAT: АВТО with an engine running or in flight, ВКЛ always - on AC
        boolean want = L.on(LinerControls.AI_PROBE) || L.anyRunning() || !ground;
        probeHeat = want && ac;
        probeFault = want && !ac && L.powered() && (L.anyRunning() || !ground);
        if (wingOn && !wasWing && wing > DETECT) {
            shed = true;
        }
        // accretion and shedding
        double melt = oatNow > MELT_T ? MELT_RATE : 0;
        double r = ICE_RATE * icing;
        wing = step(wing, !ground && !wingOn ? r : 0, wingOn ? AI_SHED : melt);
        for (int i = 0; i < 2; i++) {
            boolean turning = L.eng[i].n2 > 5;
            eng[i] = step(eng[i], turning && !engOn[i] ? r * ENG_RATE_K : 0, engOn[i] ? AI_SHED : melt);
        }
        for (int p = 0; p < 3; p++) {
            boolean before = probe[p] >= PROBE_ICED;
            probe[p] = step(probe[p], !ground && !probeHeat ? PROBE_RATE * icing : 0, probeHeat ? AI_SHED * 2 : melt);
            if (!before && probe[p] >= PROBE_ICED) {
                frozenKmh[p] = kmh;                                 // the needle stops where it was
            }
        }
        boolean frosty = oatNow < 0 && (wet || a.icing && oatNow < -10);
        frost = step(frost, a.icing && frosty && !probeHeat ? FROST_RATE : 0, probeHeat ? AI_SHED : oatNow > MELT_T ? MELT_RATE * 2 : 0);
        // the packs' flow, the cabin's cold
        boolean p1 = L.on(LinerControls.PACK1) && L.ductL > 0, p2 = L.on(LinerControls.PACK2) && L.ductR > 0;
        pack1Flow = p1 ? (p2 ? 100 : 120) : 0;
        pack2Flow = p2 ? (p1 ? 100 : 120) : 0;
        double coldest = Math.min(L.cabT[1], L.cabT[2]);
        cold = coldest < COLD_T ? Math.min(COLD_TICKS * 2, cold + 1) : Math.max(0, cold - 4);
    }

    /** An amount one tick on: + grow, - clear (0..1). */
    static double step(double v, double grow, double clear) {
        double n = v + grow - (grow > 0 ? 0 : clear);
        return n < 0 ? 0 : n > 1 ? 1 : n;
    }

    // ---------------------------------------------------------------- what the others read

    /** Ice detected (the overhead's «ЛЁД», the E/WD, the ECAM): on the wing or an engine. */
    public boolean detected() {
        return wing > DETECT || eng[0] > DETECT || eng[1] > DETECT;
    }

    /** A probe iced (its airspeed frozen). */
    public boolean iced(int p) {
        return probe[p] >= PROBE_ICED;
    }

    /** The airspeed (km/h) a side's PFD shows: the captain's probe 1, the F/O's 2, the standby 3 - frozen when iced. */
    public double indicated(int p, double kmh) {
        return iced(p) ? frozenKmh[p] : kmh;
    }

    /** The airspeed is unreliable: one of the pilots' probes iced. */
    public boolean unreliable() {
        return iced(0) || iced(1);
    }

    /** The N1 limit off (%) for engine i: its own anti-ice, the wing's. */
    public double n1Loss(int i) {
        return (engOn[i] ? ENG_AI_N1 : 0) + (wingOn ? WING_AI_N1 : 0);
    }

    /** The fuel flow's factor for engine i (1 + the anti-ice's extra). */
    public double ffFactor(int i) {
        return 1 + (engOn[i] ? ENG_AI_FF : 0) + (wingOn ? WING_AI_FF : 0);
    }

    /** The thrust's factor (the wing's anti-ice takes bleed off both engines). */
    public double thrustFactor() {
        return wingOn ? 1 - WING_AI_THRUST : 1;
    }

    /** Engine i's N1 fluctuation (%) at a tick: its ice shaking it. */
    public double jitter(int i, long tick) {
        double k = eng[i];
        if (k < 0.2) {
            return 0;
        }
        return JITTER * (k - 0.2) / 0.8 * Math.sin(tick * 0.9 + i * 1.7) * Math.cos(tick * 0.23);
    }

    /** Engine i's vibration (the cruise page's units): 0.8 running + VIB_ICE x its ice. */
    public double vib(int i, boolean running) {
        return running ? 0.8 + 0.1 * i + VIB_ICE * eng[i] : 0;
    }

    /** Engine i's ice flames it out. */
    public boolean flameout(int i) {
        return eng[i] >= FLAMEOUT_ICE;
    }

    /** The stall's factor (wing ice). */
    public double stallFactor() {
        return 1 + STALL_K * wing;
    }

    public double liftOffFactor() {
        return 1 + LIFTOFF_K * wing;
    }

    public double dragFactor() {
        return 1 + DRAG_K * wing;
    }

    /** The ice's mass (kg). */
    public double mass() {
        return MASS_ICE * wing;
    }

    /** A zone's target (°C): the cockpit's selector for zone 0, the cabin's for the forward / aft zones. */
    public static double zoneTarget(LinerSystems L, int zone) {
        int p = L.at(zone == 0 ? LinerControls.TEMP_CK : LinerControls.TEMP_CAB);
        return TEMP[Math.max(0, Math.min(TEMP.length - 1, p))];
    }

    /** The passengers have been in the cold long enough («замерзание»). */
    public boolean freezing() {
        return cold >= COLD_TICKS;
    }

    /** The heaters' load (% of one generator). */
    public int load() {
        return probeHeat ? LOAD_WINDOW + LOAD_PROBE : 0;
    }

    // ---------------------------------------------------------------- sync / save

    /** Flags: valves, faults, heat, wet, the shed. */
    int flags() {
        return (wingOn ? 1 : 0) | (engOn[0] ? 2 : 0) | (engOn[1] ? 4 : 0) | (probeHeat ? 8 : 0) | (wingFault ? 16 : 0) | (engFault[0] ? 32 : 0)
                | (engFault[1] ? 64 : 0) | (probeFault ? 128 : 0) | (wet ? 256 : 0) | (cold >= COLD_TICKS ? 512 : 0);
    }

    /** The sync words: flags, wing, eng 1 / 2, probes 1..3, frost (thousandths), the frozen airspeeds, oat (x10), the packs' flows. */
    public int[] pack() {
        return new int[] {flags(), r(wing), r(eng[0]), r(eng[1]), r(probe[0]), r(probe[1]), r(probe[2]), r(frost), (int) Math.round(frozenKmh[0]),
                (int) Math.round(frozenKmh[1]), (int) Math.round(frozenKmh[2]), (int) Math.round(oatNow * 10), pack1Flow, pack2Flow,
                (int) Math.round(icing * 100), wingTest};
    }

    public static final int WORDS = 16;

    public void unpack(int[] w, int o) {
        int f = w[o];
        wingOn = (f & 1) != 0;
        engOn[0] = (f & 2) != 0;
        engOn[1] = (f & 4) != 0;
        probeHeat = (f & 8) != 0;
        wingFault = (f & 16) != 0;
        engFault[0] = (f & 32) != 0;
        engFault[1] = (f & 64) != 0;
        probeFault = (f & 128) != 0;
        wet = (f & 256) != 0;
        cold = (f & 512) != 0 ? COLD_TICKS : 0;
        wing = w[o + 1] / 1000.0;
        eng[0] = w[o + 2] / 1000.0;
        eng[1] = w[o + 3] / 1000.0;
        for (int p = 0; p < 3; p++) {
            probe[p] = w[o + 4 + p] / 1000.0;
            frozenKmh[p] = w[o + 8 + p];
        }
        frost = w[o + 7] / 1000.0;
        oatNow = w[o + 11] / 10.0;
        pack1Flow = w[o + 12];
        pack2Flow = w[o + 13];
        icing = w[o + 14] / 100.0;
        wingTest = w[o + 15];
    }

    private static int r(double v) {
        return (int) Math.round(v * 1000);
    }

    public void write(net.minecraft.nbt.NBTTagCompound t) {
        t.setIntArray("Ice", new int[] {r(wing), r(eng[0]), r(eng[1]), r(probe[0]), r(probe[1]), r(probe[2]), r(frost), wingTest, cold});
        t.setIntArray("IceKmh", new int[] {(int) Math.round(frozenKmh[0]), (int) Math.round(frozenKmh[1]), (int) Math.round(frozenKmh[2])});
    }

    public void read(net.minecraft.nbt.NBTTagCompound t) {
        int[] a = t.getIntArray("Ice");
        if (a.length == 9) {
            wing = c(a[0]);
            eng[0] = c(a[1]);
            eng[1] = c(a[2]);
            for (int p = 0; p < 3; p++) {
                probe[p] = c(a[3 + p]);
            }
            frost = c(a[6]);
            wingTest = Math.max(0, a[7]);
            cold = Math.max(0, a[8]);
        }
        int[] k = t.getIntArray("IceKmh");
        for (int p = 0; p < 3 && k.length == 3; p++) {
            frozenKmh[p] = Math.max(0, k[p]);
        }
    }

    private static double c(int v) {
        return Math.max(0, Math.min(1, v / 1000.0));
    }
}
