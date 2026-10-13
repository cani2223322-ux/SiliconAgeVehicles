package com.sva.debug;

import java.util.Set;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.HighLiftMath;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Stage D5 self-tests (the INS - the «ИНС» switch and its alignment, A320 ADIRS style) - pure logic on the dedicated
 * server: the eighth switch and which vehicles have it, the synced word with the INS status in bits 30..31 (no collision
 * with the switches, the engine, the failures, the instrument, the battery, the frost or the flags), the seconds-left
 * field beside the high-lift bits, old saves (ИНС on), the alignment's state machine and its countdown, the config,
 * the autopilot's new off reason and the Action id, the lang lines.
 */
final class SelfTestAirD5SVA {

    private SelfTestAirD5SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        vehicles();
        packing();
        aux();
        saves();
        alignment();
        config();
        lang();
    }

    // ---------------------------------------------------------------- the switch, the vehicles

    private static void vehicles() {
        boolean ins = SystemsMath.insApplies(VehicleType.PLANE) && SystemsMath.insApplies(VehicleType.JET) && SystemsMath.insApplies(VehicleType.TILT)
                && SystemsMath.insApplies(VehicleType.HELI) && !SystemsMath.insApplies(VehicleType.BUGGY) && !SystemsMath.insApplies(null)
                && !SystemsMath.applies(VehicleType.HELI);
        for (VehicleType t : VehicleType.values()) {
            ins &= !SystemsMath.insApplies(t) || t.movement.flies;
        }
        int noIns = SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_INS), noNav = SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_NAV);
        boolean eff = SystemsMath.effective(VehicleType.PLANE, noNav) == noNav && SystemsMath.effective(VehicleType.HELI, 0) == noIns
                && SystemsMath.effective(VehicleType.HELI, noNav) == SystemsMath.ALL_ON && SystemsMath.effective(VehicleType.BUGGY, 0) == SystemsMath.ALL_ON;
        int a = EntityVehicleSVA.A_SWITCH + SystemsMath.SW_INS;
        boolean act = a == 37 && a < EntityVehicleSVA.A_BATTERY_OUT && a != EntityVehicleSVA.A_IDENT && a != EntityVehicleSVA.A_ENGINE
                && EntityVehicleSVA.actionAllowed(a, false, true) && !EntityVehicleSVA.actionAllowed(a, true, false);
        check(ins && eff && act && SystemsMath.SW_INS == SystemsMath.SWITCHES - 1 && SystemsMath.OLD_SWITCHES == 7,
                "D5 INS: the eighth switch ИНС - the light plane, the jet, the tiltrotor and the helicopter (whose other switches stay all on);"
                        + " Action " + a + " (A_SWITCH + 7), the pilot's alone, free of the other ids");
    }

    // ---------------------------------------------------------------- the synced word

    private static void packing() {
        boolean ok = true;
        int cases = 0;
        int[] flagSets = {0, SystemsMath.P_CFG_ENGINE | SystemsMath.P_ICED, SystemsMath.P_CFG_ENGINE | SystemsMath.P_CFG_FAILS | SystemsMath.P_HEATING
                | SystemsMath.P_CHARGING | SystemsMath.P_COLD | SystemsMath.P_ICED};
        double[] bats = {0, SystemsMath.BAT_MAX * 0.42, SystemsMath.BAT_MAX};
        for (int sw = 0; sw <= SystemsMath.ALL_ON; sw += 7) {
            for (int ins = SystemsMath.INS_OFF; ins <= SystemsMath.INS_READY; ins++) {
                for (int eng = 0; eng < 4; eng++) {
                    for (int fails = 0; fails <= SystemsMath.F_ALL; fails += 3) {
                        for (int k = 0; k < 3; k++) {
                            int instr = (sw + eng) & 7, frost = (sw + k) & 3, flags = flagSets[k];
                            int w = SystemsMath.pack(sw, eng, fails, instr, bats[k], frost, flags, ins);
                            boolean insOn = SystemsMath.on(sw, SystemsMath.SW_INS);
                            int wantIns = insOn ? Math.max(SystemsMath.INS_FAULT, ins) : SystemsMath.INS_OFF;
                            ok &= SystemsMath.switches(w) == sw && SystemsMath.engine(w) == eng && SystemsMath.fails(w) == fails
                                    && SystemsMath.instr(w) == instr && SystemsMath.batteryPct(w) == (int) Math.round(bats[k] * 100 / SystemsMath.BAT_MAX)
                                    && SystemsMath.frostLevel(w) == frost
                                    && (w & (SystemsMath.P_CFG_ENGINE | SystemsMath.P_CFG_FAILS | SystemsMath.P_HEATING | SystemsMath.P_CHARGING
                                            | SystemsMath.P_COLD | SystemsMath.P_ICED)) == flags
                                    && SystemsMath.insStatus(w) == wantIns;
                            cases++;
                        }
                    }
                }
            }
        }
        // the old 7-argument pack: the INS aligned (types without one); the word before the first sync
        int old = SystemsMath.pack(SystemsMath.ALL_ON, 0, 0, 0, 0, 0, 0);
        boolean init = SystemsMath.switches(SystemsMath.INITIAL) == SystemsMath.ALL_ON && SystemsMath.insStatus(SystemsMath.INITIAL) == SystemsMath.INS_READY
                && SystemsMath.insStatus(old) == SystemsMath.INS_READY;
        check(ok && init, "D5 sync: the board word round trip with the INS status in bits 30..31 - " + cases + " combinations of the eight"
                + " switches, engine, failures, instrument, battery %, frost, flags and INS status, none disturbing another; ИНС off reads"
                + " INS_OFF, on at least INS_FAULT; before the first sync all on and aligned");
    }

    private static void aux() {
        int hl = HighLiftMath.pack(HighLiftMath.P_FLAPS, true, true, true, true, true);
        boolean ok = (hl & SystemsMath.INS_AUX_MASK) == 0;
        for (int s = 0; s <= 255; s++) {
            int a = SystemsMath.packInsAux(s, s % 2 == 0) | hl;
            ok &= SystemsMath.insSeconds(a) == s && SystemsMath.insFlash(a) == (s % 2 == 0) && (a & HighLiftMath.P_FLAPS) == HighLiftMath.P_FLAPS;
        }
        ok &= SystemsMath.insSeconds(SystemsMath.packInsAux(999, false)) == 255 && SystemsMath.insSeconds(SystemsMath.packInsAux(-3, false)) == 0
                && SystemsMath.ALIGN_MAX_S <= 255;
        check(ok, "D5 sync: the alignment's seconds left (0..255) and the restart flash in the high-lift word's spare bits 16..24 - clear of"
                + " the flaps / airbrake / spoilers / slats / warnings");
    }

    // ---------------------------------------------------------------- old saves

    private static void saves() {
        int ins = 1 << SystemsMath.SW_INS, nav = 1 << SystemsMath.SW_NAV;
        boolean load = SystemsMath.load(SystemsMath.OLD_SWITCHES == 7 ? 0x7F & ~nav : 0, SystemsMath.OLD_SWITCHES) == (SystemsMath.ALL_ON & ~nav)
                && SystemsMath.load(0, SystemsMath.OLD_SWITCHES) == ins && SystemsMath.load(SystemsMath.ALL_ON & ~ins, SystemsMath.SWITCHES) == (SystemsMath.ALL_ON & ~ins)
                && SystemsMath.load(-1, SystemsMath.OLD_SWITCHES) == SystemsMath.ALL_ON && SystemsMath.load(-1, 99) == SystemsMath.ALL_ON;
        // NBT: a save from D2..D4 (switches, no count) - ИНС comes on, the others as saved; a D5 save keeps ИНС off
        NBTTagCompound d2 = new NBTTagCompound();
        d2.setInteger(VehicleState.K_TYPE, VehicleType.PLANE.ordinal());
        d2.setInteger(VehicleState.K_SWITCHES, 0x7F & ~nav);
        VehicleState o = new VehicleState(VehicleType.PLANE);
        o.readNBT(d2);
        VehicleState s = new VehicleState(VehicleType.PLANE);
        s.switches = SystemsMath.toggle(SystemsMath.ALL_ON, SystemsMath.SW_INS);
        NBTTagCompound t = new NBTTagCompound();
        s.writeNBT(t);
        VehicleState r = new VehicleState(VehicleType.PLANE);
        r.readNBT(t);
        check(load && o.switches == (SystemsMath.ALL_ON & ~nav) && r.switches == s.switches && t.getInteger(VehicleState.K_SW_COUNT) == SystemsMath.SWITCHES,
                "D5 saves: a vehicle saved before ИНС loads with it ON (НАВ. ОГНИ off stays off); a D5 save with ИНС off keeps it off ("
                        + VehicleState.K_SW_COUNT + " = " + SystemsMath.SWITCHES + ")");
    }

    // ---------------------------------------------------------------- the alignment

    private static void alignment() {
        int total = SystemsMath.alignTicks(10);
        SystemsMath.Ins a = new SystemsMath.Ins();
        int readyAt = -1, first = a.tick(true, true, false, total);
        int left0 = SystemsMath.secondsLeft(a.done, total);
        for (int i = 2; i <= 400 && readyAt < 0; i++) {
            if (a.tick(true, true, false, total) == SystemsMath.INS_READY) {
                readyAt = i;
            }
        }
        boolean timing = total == 200 && first == SystemsMath.INS_ALIGN && left0 == 10 && readyAt == 200 && a.ready
                && a.tick(true, true, true, total) == SystemsMath.INS_READY;     // aligned: moving no longer matters
        boolean count = SystemsMath.secondsLeft(0, 200) == 10 && SystemsMath.secondsLeft(1, 200) == 10 && SystemsMath.secondsLeft(20, 200) == 9
                && SystemsMath.secondsLeft(181, 200) == 1 && SystemsMath.secondsLeft(199, 200) == 1 && SystemsMath.secondsLeft(200, 200) == 0
                && SystemsMath.secondsLeft(250, 200) == 0;
        // the switch off / the power lost drop it; back on - the whole alignment again
        SystemsMath.Ins b = new SystemsMath.Ins();
        for (int i = 0; i < 120; i++) {
            b.tick(true, true, false, total);
        }
        int half = SystemsMath.secondsLeft(b.done, total);
        int off = b.tick(false, true, false, total);
        int again = b.tick(true, true, false, total);
        boolean switchReset = half == 4 && off == SystemsMath.INS_OFF && again == SystemsMath.INS_ALIGN && b.done == 1;
        a.tick(true, true, false, total);
        int fault = a.tick(true, false, false, total);
        boolean powerReset = fault == SystemsMath.INS_FAULT && !a.ready && a.tick(true, true, false, total) == SystemsMath.INS_ALIGN;
        // moving while aligning starts it over (the ВЫСТ. flash); standing from the start - no flash
        SystemsMath.Ins m = new SystemsMath.Ins();
        for (int i = 0; i < 50; i++) {
            m.tick(true, true, false, total);
        }
        int moved = m.tick(true, true, true, total);
        boolean restart = moved == SystemsMath.INS_ALIGN && m.done == 0 && m.flash == SystemsMath.RESTART_TICKS;
        m.tick(true, true, true, total);
        restart &= m.flash == SystemsMath.RESTART_TICKS - 1;
        SystemsMath.Ins still = new SystemsMath.Ins();
        restart &= still.tick(true, true, true, total) == SystemsMath.INS_ALIGN && still.flash == 0;
        // 0 s: instant; the setting clamped
        SystemsMath.Ins z = new SystemsMath.Ins();
        boolean instant = z.tick(true, true, false, SystemsMath.alignTicks(0)) == SystemsMath.INS_READY && SystemsMath.alignTicks(-5) == 0
                && SystemsMath.alignTicks(100000) == SystemsMath.ALIGN_MAX_S * 20;
        check(timing && count && switchReset && powerReset && restart && instant,
                "D5 alignment: 10 s = 200 ticks standing still (aligned at tick " + readyAt + ", 10 s shown at the start, rounded up);"
                        + " ИНС off / the power lost - dropped, back on - from the start; moving restarts it with the ВЫСТ. flash; 0 s - instant");
    }

    // ---------------------------------------------------------------- config, lang

    private static void config() {
        check(ConfigSVA.ins && ConfigSVA.insAlignSeconds == 10 && CruiseMath.OFF_INS == 8 && CruiseMath.OFF_INS != CruiseMath.OFF_LANDING,
                "D5 config: vehicles.ins on, vehicles.insAlignSeconds 10 by default; the autopilot's new off reason OFF_INS");
    }

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (String k : SystemsMath.INS_KEYS) {
            keys.add("sva.ins." + k);
            keys.add("sva.ins.screen." + k);
            if (!"align".equals(k)) {
                keys.add("sva.ins.memo." + k);              // aligning: sva.ins.memo with the seconds
            }
        }
        for (String k : new String[] {"sva.sys.sw.ins", "sva.sys.sw.ins.short", "sva.sys.sw.ins.tip", "sva.sys.group.nav", "sva.ins.lamp.align",
                "sva.ins.lamp.fault", "sva.ins.flag.att", "sva.ins.flag.hdg", "sva.ins.mapNa", "sva.ins.memo", "sva.ins.restart", "sva.msg.ins.ready",
                "sva.msg.ins.lost", "sva.msg.ins.always", "sva.hud.cruise.why." + CruiseMath.OFF_INS}) {
            keys.add(k);
        }
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (!ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "D5 lang: the INS lines (switch, annunciator, flags, MAP NOT AVAIL, memo, HUD, chat, the AP's reason) in ru + en"
                + miss);
    }
}
