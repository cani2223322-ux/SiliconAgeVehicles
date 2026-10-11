package com.sva.debug;

import java.util.Set;

import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleFn;
import com.sva.vehicle.VehicleLights;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Aircraft round 2, stage D2 self-tests (★6 the engine start, ★7 failures with wear, ★17 the overhead switch panel;
 * mock-ups docs/mockups/veh_air3_2_start_fail.png, veh_air3_4_cockpit.png) - pure logic on the dedicated server: the
 * switches and their light groups, the engine's state machine and timings (plane / jet, in the air), the auto-start, the
 * battery (ГЕН, the board without it, the starter), the heat and the ice, the failure chance curve, the pick and the
 * effects, the synced word, the state's NBT (old saves: all on), the lamps, the «Запуск двигателя» key, the overhead
 * panel in the light plane's model, the lang lines.
 */
final class SelfTestAirD2SVA {

    private SelfTestAirD2SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        switches();
        engine();
        autoStart();
        battery();
        heat();
        failures();
        packing();
        nbt();
        lamps();
        key();
        panel();
        lang();
    }

    // ---------------------------------------------------------------- switches

    private static void switches() {
        int all = SystemsMath.ALL_ON;
        boolean on = all == 127 && SystemsMath.on(all, SystemsMath.SW_BAT) && SystemsMath.on(all, SystemsMath.SW_HEAT)
                && !SystemsMath.on(SystemsMath.toggle(all, SystemsMath.SW_NAV), SystemsMath.SW_NAV)
                && SystemsMath.toggle(SystemsMath.toggle(all, SystemsMath.SW_NAV), SystemsMath.SW_NAV) == all && SystemsMath.sanitize(-1) == all
                && SystemsMath.toggle(all, 9) == all;
        check(on && SystemsMath.SW_KEYS.length == SystemsMath.SWITCHES && SystemsMath.GUARDED.length == SystemsMath.SWITCHES
                        && SystemsMath.SW_COLOUR.length == SystemsMath.SWITCHES && SystemsMath.GUARDED[SystemsMath.SW_BAT] && SystemsMath.GUARDED[SystemsMath.SW_GEN]
                        && !SystemsMath.GUARDED[SystemsMath.SW_NAV],
                "D2 switches: seven (АКБ, ГЕН under guards; НАВ. ОГНИ, СТРОБ, МАЯЧОК, ПОС. ФАРЫ, ОБОГРЕВ), all on by default, a toggle flips one");
        check(SystemsMath.lightSwitch(VehicleLights.RED) == SystemsMath.SW_NAV && SystemsMath.lightSwitch(VehicleLights.GREEN) == SystemsMath.SW_NAV
                        && SystemsMath.lightSwitch(VehicleLights.WHITE) == SystemsMath.SW_NAV && SystemsMath.lightSwitch(VehicleLights.STROBE) == SystemsMath.SW_STROBE
                        && SystemsMath.lightSwitch(VehicleLights.BEACON) == SystemsMath.SW_BEACON && SystemsMath.lightSwitch(VehicleLights.HEAD) == SystemsMath.SW_LAMP
                        && SystemsMath.lightSwitch(VehicleLights.BRAKE) < 0 && SystemsMath.lightSwitch(VehicleLights.AMBER) < 0
                        && SystemsMath.lightSwitch(VehicleLights.MARKER) < 0,
                "D2 switches -> lights: НАВ. ОГНИ = red / green / white, СТРОБ = strobes, МАЯЧОК = beacon, ПОС. ФАРЫ = landing lights; brake / amber / markers none");
        int noNav = SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_NAV), noLamp = SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_LAMP);
        check(!SystemsMath.lightOn(true, noNav, VehicleLights.RED) && SystemsMath.lightOn(true, noNav, VehicleLights.STROBE)
                        && !SystemsMath.lightOn(true, noLamp, VehicleLights.HEAD) && SystemsMath.lightOn(true, 0, VehicleLights.BRAKE)
                        && SystemsMath.lightOn(false, 0, VehicleLights.RED) && SystemsMath.lightOn(false, 0, VehicleLights.HEAD)
                        && SystemsMath.lightOn(true, SystemsMath.ALL_ON, VehicleLights.BEACON),
                "D2 switches -> lights: a light off only with its switch off, on an aircraft; other vehicles' lights always as before");
        check(SystemsMath.applies(VehicleType.PLANE) && SystemsMath.applies(VehicleType.JET) && SystemsMath.applies(VehicleType.TILT)
                        && !SystemsMath.applies(VehicleType.HELI) && !SystemsMath.applies(VehicleType.BUGGY) && !SystemsMath.applies(VehicleType.SHUTTLE)
                        && !SystemsMath.applies(VehicleType.SHIP) && !SystemsMath.applies(null),
                "D2: the board systems - the light plane, the jet, the tiltrotor (not the helicopter, the spacecraft, the ground vehicles)");
    }

    // ---------------------------------------------------------------- the engine

    /** Ticks a machine started now takes to {catch, run} (-1 never), stepping with the given inputs. */
    private static int[] runTimes(int crank, int warm, double bat) {
        SystemsMath.Engine e = new SystemsMath.Engine();
        e.start(SystemsMath.ALL_ON, bat, true);
        int c = -1, r = -1;
        for (int i = 1; i <= 400 && r < 0; i++) {
            int res = e.tick(SystemsMath.ALL_ON, bat, true, crank, warm, 0.5, 0);
            if (res == SystemsMath.R_CATCH) {
                c = i;
            }
            if (res == SystemsMath.R_READY) {
                r = i;
            }
        }
        return new int[] {c, r};
    }

    private static void engine() {
        VehicleType p = VehicleType.PLANE, j = VehicleType.JET;
        int[] tp = runTimes(SystemsMath.crankTicks(p, false), SystemsMath.warmTicks(p), SystemsMath.BAT_MAX);
        int[] tj = runTimes(SystemsMath.crankTicks(j, false), SystemsMath.warmTicks(j), SystemsMath.BAT_MAX);
        check(SystemsMath.crankTicks(p, false) == 30 && SystemsMath.warmTicks(p) == 60 && tp[0] == 30 && tp[1] == 90
                        && SystemsMath.crankTicks(j, false) == 60 && SystemsMath.warmTicks(j) == 40 && tj[0] == 60 && tj[1] == 100
                        && SystemsMath.crankTicks(p, true) == 10 && SystemsMath.crankTicks(VehicleType.TILT, false) == 30,
                "D2 engine: the light plane catches at 1.5 s and runs at 4.5 s (" + tp[0] + " / " + tp[1] + " ticks), the jet's spool-up 3 s, idle at 5 s ("
                        + tj[0] + " / " + tj[1] + "); in the air the windmilling propeller catches 3x sooner");
        check(SystemsMath.power(SystemsMath.OFF, 0, 60) == 0F && SystemsMath.power(SystemsMath.CRANK, 20, 60) == 0F
                        && Math.abs(SystemsMath.power(SystemsMath.WARM, 0, 60) - SystemsMath.WARM_POWER) < 1e-6
                        && Math.abs(SystemsMath.power(SystemsMath.WARM, 30, 60) - 0.65F) < 1e-6 && SystemsMath.power(SystemsMath.RUN, 0, 60) == 1F
                        && !SystemsMath.lifts(SystemsMath.OFF) && !SystemsMath.lifts(SystemsMath.CRANK) && SystemsMath.lifts(SystemsMath.WARM)
                        && SystemsMath.lifts(SystemsMath.RUN),
                "D2 engine: no thrust off / cranking, the warm-up ramps the power 0.3 -> 1 (0.65 half way), 1 running");
        float last = -1;
        boolean mono = true;
        int[][] seq = {{SystemsMath.OFF, 0}, {SystemsMath.CRANK, 0}, {SystemsMath.CRANK, 15}, {SystemsMath.CRANK, 30}, {SystemsMath.WARM, 0},
                {SystemsMath.WARM, 30}, {SystemsMath.WARM, 60}, {SystemsMath.RUN, 0}};
        for (int[] q : seq) {
            float f = SystemsMath.progress(q[0], q[1], 30, 60);
            mono &= f >= last;
            last = f;
        }
        check(mono && last == 1F && SystemsMath.progress(SystemsMath.OFF, 5, 30, 60) == 0F,
                "D2 engine: the start's progress (HUD, the jet's whine) rises 0 -> 1 across the crank and the warm-up");
        SystemsMath.Engine e = new SystemsMath.Engine();
        int noMaster = e.start(SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_BAT), SystemsMath.BAT_MAX, true);
        int lowBat = e.start(SystemsMath.ALL_ON, SystemsMath.BAT_START - 1, true);
        int noCells = e.start(SystemsMath.ALL_ON, SystemsMath.BAT_MAX, false);
        int ok = e.start(SystemsMath.ALL_ON, SystemsMath.BAT_MAX, true);
        int again = e.start(SystemsMath.ALL_ON, SystemsMath.BAT_MAX, true);
        check(noMaster == SystemsMath.R_NO_MASTER && lowBat == SystemsMath.R_LOW_BATTERY && noCells == SystemsMath.R_NO_CHARGE && ok == SystemsMath.R_STARTED
                        && again == SystemsMath.R_STARTED && e.state == SystemsMath.CRANK,
                "D2 engine: «ЗАПУСК» - without АКБ a click only, a flat battery (under 5 %) or empty cells don't crank; else the starter turns");
        int stall = e.tick(SystemsMath.ALL_ON, 0, true, 30, 60, 0.5, 0);
        SystemsMath.Engine m = new SystemsMath.Engine();
        m.start(SystemsMath.ALL_ON, SystemsMath.BAT_MAX, true);
        int mis = SystemsMath.R_NONE;
        for (int i = 0; i < 30; i++) {
            mis = m.tick(SystemsMath.ALL_ON, SystemsMath.BAT_MAX, true, 30, 60, 0.1, 0.2);
        }
        SystemsMath.Engine r = new SystemsMath.Engine();
        r.run();
        int off = r.tick(SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_BAT), SystemsMath.BAT_MAX, true, 30, 60, 0.5, 0);
        SystemsMath.Engine q = new SystemsMath.Engine();
        q.run();
        int empty = q.tick(SystemsMath.ALL_ON, SystemsMath.BAT_MAX, false, 30, 60, 0.5, 0);
        SystemsMath.Engine f = new SystemsMath.Engine();
        f.run();
        int flat = f.tick(SystemsMath.ALL_ON, 0, true, 30, 60, 0.5, 0);
        check(stall == SystemsMath.R_STALLED && e.state == SystemsMath.OFF && mis == SystemsMath.R_MISFIRE && m.state == SystemsMath.OFF
                        && off == SystemsMath.R_STOPPED && r.state == SystemsMath.OFF && empty == SystemsMath.R_STOPPED && q.state == SystemsMath.OFF
                        && flat == SystemsMath.R_NONE && f.state == SystemsMath.RUN && r.stop() == SystemsMath.R_NONE,
                "D2 engine: a flat battery stalls the starter; a worn engine may misfire (back to off); АКБ off or empty cells stop it; a running"
                        + " engine keeps running on a flat board battery");
    }

    private static void autoStart() {
        int all = SystemsMath.ALL_ON, dark = SystemsMath.toggle(all, SystemsMath.SW_BAT);
        check(SystemsMath.autoStart(SystemsMath.OFF, all, true, false, false) && SystemsMath.autoStart(SystemsMath.OFF, all, false, true, true)
                        && !SystemsMath.autoStart(SystemsMath.OFF, all, false, true, false) && !SystemsMath.autoStart(SystemsMath.OFF, all, false, false, true)
                        && !SystemsMath.autoStart(SystemsMath.RUN, all, true, false, false) && !SystemsMath.autoStart(SystemsMath.CRANK, all, true, false, false)
                        && !SystemsMath.autoStart(SystemsMath.OFF, dark, true, false, false),
                "D2 auto-start: forward (the tiltrotor in vertical mode: up too) with the engine off and АКБ on starts it; not with АКБ off, not twice");
    }

    // ---------------------------------------------------------------- the battery, the heat

    private static void battery() {
        int all = SystemsMath.ALL_ON, noGen = SystemsMath.toggle(all, SystemsMath.SW_GEN);
        double b = SystemsMath.BAT_MAX;
        int ticks = 0;
        while (b > 0 && ticks < 20000) {
            b = SystemsMath.batteryStep(b, noGen, true, true, false, false);
            ticks++;
        }
        double idle = SystemsMath.batteryStep(500, noGen, true, false, false, false);
        double charged = SystemsMath.batteryStep(500, all, true, true, false, false);
        double eu = SystemsMath.batteryEu(500, charged, all, true, false);
        double full = SystemsMath.batteryStep(SystemsMath.BAT_MAX, all, true, true, false, false);
        double euFull = SystemsMath.batteryEu(SystemsMath.BAT_MAX, full, all, true, false);
        double crank = SystemsMath.batteryStep(500, all, false, false, true, false);
        double masterOff = SystemsMath.batteryStep(500, SystemsMath.toggle(noGen, SystemsMath.SW_BAT), true, true, false, false);
        double station = SystemsMath.batteryStep(500, noGen, true, false, false, true);
        check(Math.abs(ticks - SystemsMath.BAT_FLIGHT_TICKS) <= 1 && idle == 500 && charged == 502 && eu == 4 && full == SystemsMath.BAT_MAX
                        && euFull == 0 && crank == 499 && masterOff == 500 && station == 505,
                "D2 battery: ГЕН off - flat after " + ticks + " ticks (10 min) of flying, not drained parked; ГЕН charges 2 units a tick for 4 EU (none"
                        + " when full); the starter draws 1 a tick; АКБ off - no drain; a station tops it up");
        check(SystemsMath.electric(all, 1) && !SystemsMath.electric(all, 0) && !SystemsMath.electric(SystemsMath.toggle(all, SystemsMath.SW_BAT), 1000),
                "D2 battery: the board has power with АКБ on and charge in the battery");
    }

    private static void heat() {
        int ice = 0, n = 0;
        while (!SystemsMath.iced(ice) && n < 2000) {
            ice = SystemsMath.iceStep(ice, true, false);
            n++;
        }
        int thaw = ice, m = 0;
        while (thaw > 0 && m < 2000) {
            thaw = SystemsMath.iceStep(thaw, true, true);
            m++;
        }
        check(SystemsMath.cold(151, false) && !SystemsMath.cold(150, false) && SystemsMath.cold(64, true) && n == SystemsMath.ICE_TICKS
                        && m == SystemsMath.ICE_TICKS / 2 && SystemsMath.iceStep(0, false, false) == 0 && SystemsMath.frost(0) == 0
                        && SystemsMath.frost(1) == 1 && SystemsMath.frost(SystemsMath.ICE_TICKS) == 3 && SystemsMath.frost(SystemsMath.ICE_TICKS / 2) == 2
                        && SystemsMath.iceStep(SystemsMath.ICE_TICKS, true, false) == SystemsMath.ICE_TICKS,
                "D2 heat: cold over Y 150 or in falling snow; with ОБОГРЕВ off the pitot freezes in " + n + " ticks (30 s), the heat thaws it in " + m
                        + "; the windscreen frost 0..3");
        check(SystemsMath.caution(0, true) && !SystemsMath.caution(0, false) && Math.abs(SystemsMath.HEAT_EU_SHARE * VehicleType.PLANE.cruiseCost
                        - VehicleType.PLANE.cruiseCost / 20.0) < 1e-9,
                "D2 heat: the iced pitot lights «ПРИБОР» / «ВНИМАНИЕ»; the heat costs 5 % of the cruise EU (" + VehicleType.PLANE.cruiseCost / 20.0
                        + " EU/t on the light plane)");
    }

    // ---------------------------------------------------------------- failures

    private static void failures() {
        double[] w = {0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
        double[] want = {0, 0, 0.005, 0.02, 0.045, 0.08};
        boolean curve = true;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            double c = SystemsMath.chancePerMinute(w[i]);
            curve &= Math.abs(c - want[i]) < 1e-9;
            b.append(' ').append(Math.round(w[i] * 100)).append("%=").append(String.format(java.util.Locale.ROOT, "%.1f", c * 100)).append('%');
        }
        boolean mono = true;
        for (int i = 0; i < 100; i++) {
            mono &= SystemsMath.chancePerMinute((i + 1) / 100.0) >= SystemsMath.chancePerMinute(i / 100.0);
        }
        check(curve && mono && SystemsMath.chancePerMinute(Double.NaN) == 0 && SystemsMath.CHECK_TICKS == 1200,
                "D2 failures: the chance a minute of flight - 0 up to 60 % wear, then (w - 0.6)^2 up to 8 % at 100 % (the mock-up's curve):" + b);
        int e = SystemsMath.F_ENGINE, fl = SystemsMath.F_FLAP, in = SystemsMath.F_INSTR;
        check(SystemsMath.pick(0.1, 0, true) == e && SystemsMath.pick(0.5, 0, true) == fl && SystemsMath.pick(0.9, 0, true) == in
                        && SystemsMath.pick(0.5, 0, false) == in && SystemsMath.pick(0.1, e, true) == fl && SystemsMath.pick(0.9, in, true) == e
                        && SystemsMath.pick(0.1, e | fl, true) == 0 && SystemsMath.pick(0.5, e, false) == in && SystemsMath.count(e | in) == 2,
                "D2 failures: engine 45 % / flap 25 % / instrument 30 %; one already on (or no flaps) passes to the next kind; two at most");
        check(SystemsMath.failPower(0) == 1F && SystemsMath.failPower(e) == SystemsMath.ENGINE_POWER && SystemsMath.ENGINE_POWER == 0.6F
                        && SystemsMath.failPower(fl | in) == 1F && SystemsMath.caution(fl, false) && !SystemsMath.caution(0, false),
                "D2 failures: a failed engine gives 60 % power (-40 % thrust), the others none; «ВНИМАНИЕ» while any is on");
        check(Math.abs(SystemsMath.MISFIRE_K * SystemsMath.chancePerMinute(1.0) - 0.32) < 1e-9 && SystemsMath.MISFIRE_K * SystemsMath.chancePerMinute(0.6) == 0,
                "D2 failures: a worn engine misfires at a start (32 % at 100 % wear, never under 60 %)");
    }

    // ---------------------------------------------------------------- sync, NBT

    private static void packing() {
        int w = SystemsMath.pack(SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_GEN), SystemsMath.WARM, SystemsMath.F_ENGINE | SystemsMath.F_INSTR, 5,
                SystemsMath.BAT_MAX * 0.42, 2, SystemsMath.P_CFG_ENGINE | SystemsMath.P_ICED);
        check(SystemsMath.switches(w) == SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_GEN) && SystemsMath.engine(w) == SystemsMath.WARM
                        && SystemsMath.fails(w) == (SystemsMath.F_ENGINE | SystemsMath.F_INSTR) && SystemsMath.instr(w) == 5 && SystemsMath.batteryPct(w) == 42
                        && SystemsMath.frostLevel(w) == 2 && (w & SystemsMath.P_CFG_ENGINE) != 0 && (w & SystemsMath.P_ICED) != 0
                        && (w & SystemsMath.P_CFG_FAILS) == 0 && w > 0 && SystemsMath.batteryPct(SystemsMath.pack(0, 0, 0, 0, SystemsMath.BAT_MAX, 0, 0)) == 100,
                "D2 sync: the board word round trip (switches, engine, failures, instrument, battery %, frost, flags; the sign bit unused)");
    }

    private static void nbt() {
        VehicleState s = new VehicleState(VehicleType.PLANE);
        boolean fresh = s.switches == SystemsMath.ALL_ON && s.fails == 0 && s.battery == SystemsMath.BAT_MAX;
        s.switches = SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_STROBE);
        s.fails = SystemsMath.F_FLAP;
        s.failInstr = 3;
        s.battery = 123;
        NBTTagCompound t = new NBTTagCompound();
        s.writeNBT(t);
        VehicleState r = new VehicleState(VehicleType.PLANE);
        r.readNBT(t);
        NBTTagCompound old = new NBTTagCompound();
        old.setInteger(VehicleState.K_TYPE, VehicleType.PLANE.ordinal());
        VehicleState o = new VehicleState(VehicleType.PLANE);
        o.readNBT(old);
        NBTTagCompound bad = new NBTTagCompound();
        bad.setInteger(VehicleState.K_SWITCHES, -1);
        bad.setInteger(VehicleState.K_FAILS, 255);
        bad.setInteger(VehicleState.K_FAIL_INSTR, 99);
        bad.setDouble(VehicleState.K_BATTERY, 1e9);
        VehicleState x = new VehicleState(VehicleType.PLANE);
        x.readNBT(bad);
        check(fresh && r.switches == s.switches && r.fails == SystemsMath.F_FLAP && r.failInstr == 3 && r.battery == 123
                        && o.switches == SystemsMath.ALL_ON && o.fails == 0 && o.battery == SystemsMath.BAT_MAX
                        && x.switches == SystemsMath.ALL_ON && x.fails == SystemsMath.F_ALL && x.failInstr == SystemsMath.INSTR_SLOTS - 1
                        && x.battery == SystemsMath.BAT_MAX,
                "D2 NBT: the switches, failures, the failed instrument and the battery round trip; a save from before D2 - all on, none, full;"
                        + " bad values clamped");
    }

    // ---------------------------------------------------------------- lamps, the key, the panel, lang

    private static void lamps() {
        CockpitLamps.Snapshot s = new CockpitLamps.Snapshot();
        s.powered = true;
        boolean none = CockpitLamps.state(CockpitLamps.ENG_FAIL, s) == CockpitLamps.OFF && CockpitLamps.state(CockpitLamps.CAUTION, s) == CockpitLamps.OFF
                && !CockpitLamps.master(s);
        s.fails = SystemsMath.F_ENGINE;
        boolean eng = CockpitLamps.state(CockpitLamps.ENG_FAIL, s) == CockpitLamps.ON && CockpitLamps.state(CockpitLamps.CAUTION, s) == CockpitLamps.BLINK
                && CockpitLamps.state(CockpitLamps.FLAP_JAM, s) == CockpitLamps.OFF && CockpitLamps.master(s);
        s.fails = 0;
        s.iced = true;
        boolean ice = CockpitLamps.state(CockpitLamps.INSTR_FAIL, s) == CockpitLamps.ON && CockpitLamps.state(CockpitLamps.CAUTION, s) == CockpitLamps.BLINK;
        s.fails = SystemsMath.F_FLAP;
        s.powered = false;
        boolean dark = CockpitLamps.state(CockpitLamps.FLAP_JAM, s) == CockpitLamps.OFF;
        check(none && eng && ice && dark && CockpitLamps.colour(CockpitLamps.ENG_FAIL) == CockpitLamps.RED
                        && CockpitLamps.colour(CockpitLamps.FLAP_JAM) == CockpitLamps.AMBER && CockpitLamps.colour(CockpitLamps.INSTR_FAIL) == CockpitLamps.AMBER
                        && CockpitLamps.colour(CockpitLamps.CAUTION) == CockpitLamps.RED && CockpitLamps.failureLamps(VehicleType.PLANE).length == 4
                        && CockpitLamps.failureLamps(VehicleType.TILT).length == 4 && CockpitLamps.failureLamps(VehicleType.HELI).length == 0
                        && CockpitLamps.KEYS.length == CockpitLamps.CAUTION + 1,
                "D2 lamps: ОТКАЗ ДВИГ. red, ЗАКР. ЗАКЛИН. / ПРИБОР amber (also the iced pitot), ВНИМАНИЕ red blinking - also the jet's / tiltrotor's"
                        + " master caution; dark unpowered");
    }

    private static void key() {
        VehicleFn f = VehicleFn.ENGINE;
        check(f.defaultKey == 39 && !f.hold && f.driverOnly && f.appliesTo(VehicleType.PLANE) && f.appliesTo(VehicleType.JET) && f.appliesTo(VehicleType.TILT)
                        && !f.appliesTo(VehicleType.HELI) && !f.appliesTo(VehicleType.BUGGY) && f.action(VehicleType.PLANE) == EntityVehicleSVA.A_ENGINE
                        && f.action(VehicleType.HELI) == -1,
                "D2 key: «Запуск двигателя» ; (Ж) - the plane, jet, tiltrotor; the Action packet A_ENGINE, the pilot's");
        boolean pilot = EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_ENGINE, false, true)
                && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_ENGINE, true, false);
        for (int i = 0; i < SystemsMath.SWITCHES; i++) {
            pilot &= EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_SWITCH + i, false, true)
                    && !EntityVehicleSVA.actionAllowed(EntityVehicleSVA.A_SWITCH + i, true, false);
        }
        check(pilot && EntityVehicleSVA.A_SWITCH + SystemsMath.SWITCHES <= 127 && EntityVehicleSVA.A_ENGINE < EntityVehicleSVA.A_SWITCH,
                "D2 key: the start and the seven switches (Action A_SWITCH + i, one byte) are the pilot's alone - not the owner's from outside");
    }

    private static void panel() {
        CockpitLayout l = CockpitLayout.of(VehicleType.PLANE);
        CockpitLayout.Screen s = l.panel(CockpitLayout.OVERHEAD);
        float[] e = CockpitLayout.eye(VehicleType.PLANE);
        boolean ok = s != null;
        String info = "none";
        if (ok) {
            double dx = e[0] - s.c[0], dy = e[1] - s.c[1], dz = e[2] - s.c[2], d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double ang = Math.toDegrees(Math.acos((s.n[0] * dx + s.n[1] * dy + s.n[2] * dz) / d));
            double el = Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
            ok = s.c[1] > e[1] + 0.05F && s.c[0] > e[0] && ang < 30 && el > 15 && el < 40 && s.w > 0.15F && s.w / s.h > 1.6F && s.w / s.h < 2.4F
                    && s.up[1] > 0.5F;
            info = String.format(java.util.Locale.ROOT, "%.3f x %.3f, %.0f deg up from the eye, facing within %.0f deg", s.w, s.h, el, ang);
        }
        boolean others = true;
        for (VehicleType t : VehicleType.values()) {
            others &= t == VehicleType.PLANE || CockpitLayout.of(t).panel(CockpitLayout.OVERHEAD) == null;
        }
        check(ok && others, "D2 panel: the light plane's overhead switch panel found in its model - over and forward of the pilot's eye, "
                + info + "; no other type has one");
    }

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        StringBuilder miss = new StringBuilder();
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (String k : SystemsMath.SW_KEYS) {
            keys.add("sva.sys.sw." + k);
            keys.add("sva.sys.sw." + k + ".short");
            keys.add("sva.sys.sw." + k + ".tip");
        }
        for (String k : SystemsMath.ENGINE_KEYS) {
            keys.add("sva.sys.engine." + k);
            keys.add("sva.cockpit.engine." + k);
        }
        for (String k : new String[] {"engFail", "flapJam", "instrFail", "caution"}) {
            keys.add("sva.cockpit.lamp." + k);
        }
        for (String k : new String[] {"engine", "flap", "instr"}) {
            keys.add("sva.hud.sys.fail." + k);
            keys.add("sva.msg.fail." + k);
        }
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "D2 lang: every switch's name / short / tip, the engine states, the lamps, the failure lines in ru + en" + miss);
    }
}
