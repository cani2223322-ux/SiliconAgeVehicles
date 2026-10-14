package com.sva.debug;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sva.vehicle.BatteryMath;
import com.sva.vehicle.FuelMath;
import com.sva.vehicle.HighLiftMath;
import com.sva.vehicle.HiResMath;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.LinerTexMath;
import com.sva.vehicle.PlaneMath;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleState;
import com.sva.vehicle.VehicleType;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 1 self-tests (plan-next §7, the SA-320) - pure logic on the dedicated server: the type's numbers, the control
 * table's integrity (unique ids, no two hitboxes of a panel overlapping, inside their panels, every row / position word /
 * message in ru + en), the click rules (guards, toggles, levers), ELEC's source priority and the batteries-only
 * essentials, the APU's timing (AVAIL ~15 s, the cool-down), the bleed pressures and the packs' toll, the engine start's
 * sequence (IGN 16 %, fuel 22 %, the starter out at 50 %, idle ~9.5 s) and its faults (no air, hot, hung, an abort clears
 * with the master), the quick start's timeline (~40 s), the fuel tanks, the sync string and the save round trips, the
 * texture tiers / atlas layout and the LOD choice.
 */
final class SelfTestLinerSVA {

    private SelfTestLinerSVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        type();
        table();
        clicks();
        elec();
        apu();
        bleed();
        start();
        faults();
        quick();
        fuel();
        sync();
        textures();
        model();
        lang();
        SelfTestLinerHydSVA.run();                                  // liner wave 2: hydraulics, flight controls, gear, brakes
        SelfTestLinerFuelSVA.run();                                 // liner wave 3: fuel, ECAM, checklists (once: it ran twice before wave 5)
        SelfTestLinerAfsSVA.run();                                  // liner wave 4: FCU, AP / FD, A/THR, levers, ILS, autoland
        SelfTestLinerFmsSVA.run();                                  // liner wave 5: the MCDU, the FMS, the profile, NAV by the plan
        saves();
    }

    // ---------------------------------------------------------------- the model, the rig, the riders

    private static void model() throws java.io.IOException {
        String dir = "/assets/siliconagevehicles/" + com.sva.vehicle.LinerModelData.DIR;
        java.util.Map<String, com.sva.vehicle.LinerModelData.Joint> rig = com.sva.vehicle.LinerModelData.parseRig(
                SelfTestLinerSVA.class.getResourceAsStream(dir + com.sva.vehicle.LinerModelData.RIG));
        int tris0 = 0, tris2 = 0, moving = 0;
        boolean same = true, joints = true;
        java.util.Set<String> g0 = null;
        for (int lod = 0; lod < 3; lod++) {
            com.sva.vehicle.LinerModelData.Model m = com.sva.vehicle.LinerModelData.parse(
                    SelfTestLinerSVA.class.getResourceAsStream(dir + com.sva.vehicle.LinerModelData.objName(lod)));
            if (lod == 0) {
                g0 = m.groups.keySet();
                tris0 = m.triangles();
            } else {
                same &= m.groups.keySet().equals(g0);
            }
            if (lod == 2) {
                tris2 = m.triangles();
            }
        }
        for (com.sva.vehicle.LinerModelData.Joint j : rig.values()) {
            if (j.pivot != null || j.track != null) {
                moving++;
            }
            if (j.axis != null) {
                double l = Math.sqrt(j.axis[0] * j.axis[0] + j.axis[1] * j.axis[1] + j.axis[2] * j.axis[2]);
                joints &= Math.abs(l - 1) < 1e-3 && j.pivot != null;
            }
            joints &= j.parent.length() == 0 || rig.containsKey(j.parent);
        }
        boolean wanted = rig.containsKey("fan_L") && rig.containsKey("mlg_R") && rig.containsKey("nlgsteer") && rig.containsKey("doorL1")
                && rig.containsKey("stairs") && rig.containsKey("apu_inlet") && rig.containsKey("aileron_L") && rig.containsKey("rudder")
                && rig.containsKey("flap2_R") && rig.containsKey("spoiler5_L") && rig.containsKey("slat5_R") && rig.containsKey("elevator_R");
        check(same && joints && wanted && g0 != null && g0.equals(rig.keySet()) && moving >= 55 && tris0 > 25000 && tris2 < tris0 / 2,
                "liner model: 3 LODs with the same " + rig.size() + " rig groups (" + moving + " moving), LOD0 " + tris0 + " / LOD2 " + tris2
                        + " triangles; every hinge a unit axis through a pivot, every parent there");
        // the door's hinge: the pose turns the door about the vertical at its forward edge (aft of the cockpit's rear wall)
        com.sva.vehicle.LinerModelData.Joint door = rig.get("doorL1");
        check(door != null && door.pivot[0] > com.sva.vehicle.LinerInterior.CAB_X - 0.2 && door.pivot[0] < com.sva.vehicle.LinerInterior.CAB_X + 0.1
                        && door.pivot[2] < -1.1 && Math.abs(Math.abs(door.axis[1]) - 1) < 1e-3,
                "liner model: the L1 door aft of the cockpit (hinge x " + (door == null ? 0 : door.pivot[0]) + ", the cabin from "
                        + com.sva.vehicle.LinerInterior.CAB_X + ")");
        // riders inside the skin
        float[][] hull = RiderFitSVA.hull(dir + com.sva.vehicle.LinerModelData.objName(2), new String[] {"fus", "belly", "door", "fin"});
        StringBuilder bad = new StringBuilder();
        VehicleType t = VehicleType.LINER;
        for (int i = 0; hull != null && i < t.seatOffsets.length; i++) {
            int[] r = RiderFitSVA.check(t, hull, i);
            if (r[0] > 0 || r[1] > 0) {
                bad.append(" seat ").append(i).append(": chest ").append(r[0]).append(", head ").append(r[1]).append(';');
            }
        }
        check(hull != null && bad.length() == 0, "liner rider fit: all ten riders' chests and heads inside the fuselage" + bad);
        // the interior: the cockpit and the cabin in triangles, the panels textured at their places
        java.util.List<com.sva.vehicle.LinerInterior.Tri> tr = com.sva.vehicle.LinerInterior.build().tris();
        int ck = 0, cab = 0, glow = 0;
        for (com.sva.vehicle.LinerInterior.Tri x : tr) {
            ck += x.part == com.sva.vehicle.LinerInterior.COCKPIT ? 1 : 0;
            cab += x.part == com.sva.vehicle.LinerInterior.CABIN ? 1 : 0;
            glow += x.glow ? 1 : 0;
        }
        check(ck > 500 && cab > 500 && glow >= 12, "liner interior: the cockpit " + ck + " and the cabin " + cab + " triangles; " + glow
                + " on the lit panels");
    }

    // ---------------------------------------------------------------- helpers

    /** A liner on the ground with both batteries full, cold and dark. */
    static LinerSystems cold() {
        LinerSystems L = new LinerSystems();
        L.tankL = L.tankR = 16800;
        L.tankC = 14400;
        L.bat2.charge = BatteryMath.capacity(VehicleType.LINER);
        return L;
    }

    static LinerSystems.Env env() {
        LinerSystems.Env e = new LinerSystems.Env();
        e.bat1In = true;
        e.bat1Cap = BatteryMath.capacity(VehicleType.LINER);
        e.bat1 = e.bat1Cap;
        e.random = 0.9;
        e.failChance = 0;
        return e;
    }

    /** Ticks until cond (max n), -1 if never; the env's battery 1 follows the systems' draw. */
    interface Cond {
        boolean ok(LinerSystems L);
    }

    static int run(LinerSystems L, LinerSystems.Env e, int n, Cond c) {
        for (int i = 1; i <= n; i++) {
            L.tick(e);
            e.bat1 = Math.max(0, Math.min(e.bat1Cap, e.bat1 - L.bat1Eu));
            if (c != null && c.ok(L)) {
                return i;
            }
        }
        return -1;
    }

    static void click(LinerSystems L, LinerControls.Ctl c) {
        L.control(c.index, LinerControls.A_PRIMARY);
    }

    /** Batteries on, APU started and AVAIL with its bleed on, packs as given; returns the env. */
    static LinerSystems.Env apuUp(LinerSystems L, boolean packs) {
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.APU_MASTER, 1);
        click(L, LinerControls.APU_START);
        run(L, e, 2000, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.apu == LinerSystems.A_AVAIL;
            }
        });
        L.set(LinerControls.APU_BLEED, 1);
        L.set(LinerControls.PACK1, packs ? 1 : 0);
        L.set(LinerControls.PACK2, packs ? 1 : 0);
        run(L, e, 2, null);
        return e;
    }

    // ---------------------------------------------------------------- the type

    private static void type() {
        VehicleType t = VehicleType.LINER;
        check(t.seats == 10 && t.seatOffsets.length == 10 && t.seatOffsets[0][1] < 0 && t.seatOffsets[1][1] > 0 && t.tier == com.sc.energy.Tier.EV
                        && t.plane == PlaneParams.LINER && t.movement == com.sva.vehicle.Movement.PLANE && t.modelScale == 1F,
                "liner: EV, PLANE, 10 seats - the captain left (the driver), the F/O right, then 4 rows of 2");
        double run = PlaneMath.takeoffDistance(t, true), jet = PlaneMath.takeoffDistance(VehicleType.JET, true);
        check(PlaneParams.LINER.stall > PlaneParams.JET.stall && PlaneParams.LINER.liftOff > PlaneParams.JET.liftOff && run > jet * 2.5
                        && run < 200 && t.turnRate < VehicleType.JET.turnRate,
                "liner: heavier than the jet - higher stall / rotation, a run of " + Math.round(run) + " blocks on a runway (jet " + Math.round(jet)
                        + "), a slower turn");
        check(FuelMath.uses(t) && FuelMath.on(t, 0, false) && FuelMath.tank(t) == 48000 && BatteryMath.capacity(t) == 400000
                        && Math.abs(LinerSystems.SHARE_L + LinerSystems.SHARE_C + LinerSystems.SHARE_R - 1) < 1e-9,
                "liner: always on hydrogen, 48 000 mB in L / C / R 35 / 30 / 35 %, an EV battery of 400 000 EU");
        HighLiftMath.Spec s = HighLiftMath.spec(t);
        check(s != null && s.settings() == 5 && s.slats && s.airbrake
                        && HighLiftMath.params(s, t.plane, 4, true, false, true).stall < t.plane.stall,
                "liner: flaps 0 / 1 / 2 / 3 / FULL (five settings - the params cache holds them), slats, spoilers");
        check(SystemsMath.applies(t) && t.platformSize == com.sva.vehicle.PlatformSize.P7x7, "liner: the board systems' words apply; a 7x7 platform");
    }

    // ---------------------------------------------------------------- the control table

    private static void table() {
        Set<String> ids = new HashSet<String>();
        StringBuilder bad = new StringBuilder();
        List<LinerControls.Ctl> all = LinerControls.all();
        for (LinerControls.Ctl c : all) {
            if (!ids.add(c.id)) {
                bad.append(" dup:").append(c.id);
            }
            if (LinerControls.byId(c.id) != c || LinerControls.get(c.index) != c) {
                bad.append(" index:").append(c.id);
            }
            if (c.cx - c.w / 2 < 0 || c.cy - c.h / 2 < 0 || c.cx + c.w / 2 > LinerControls.PANEL_W[c.panel]
                    || c.cy + c.h / 2 > LinerControls.PANEL_H[c.panel]) {
                bad.append(" outside:").append(c.id);
            }
            if (c.def < 0 || c.def >= c.n()) {
                bad.append(" def:").append(c.id);
            }
            if (c.n() > 15) {
                bad.append(" positions:").append(c.id);             // a char of the sync string holds 0..15
            }
        }
        check(bad.length() == 0 && all.size() >= 100, "liner controls: " + all.size() + " rows, ids unique, every hitbox inside its panel" + bad);
        StringBuilder ov = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                LinerControls.Ctl a = all.get(i), b = all.get(j);
                if (a.panel == b.panel && a.kind != LinerControls.VIRT && b.kind != LinerControls.VIRT && Math.abs(a.cx - b.cx) * 2 < a.w + b.w
                        && Math.abs(a.cy - b.cy) * 2 < a.h + b.h) {
                    ov.append(' ').append(a.id).append('/').append(b.id);
                }
            }
        }
        check(ov.length() == 0, "liner controls: no two hitboxes of a panel overlap" + ov);
        // the hit test finds each row at its centre; a panel point maps into the cockpit (overhead above the eye, pedestal low)
        boolean hits = true;
        for (LinerControls.Ctl c : all) {
            hits &= c.kind == LinerControls.VIRT || LinerControls.hit(c.panel, c.cx, c.cy) == c;   // wave 4: VIRT has no place
        }
        float[] o = LinerControls.point(LinerControls.OVH, 900, 700, new float[3]);
        float[] pd = LinerControls.point(LinerControls.PED, 380, 1160, new float[3]);
        float[] mp = LinerControls.point(LinerControls.MP, 1000, 0, new float[3]);
        check(hits && o[1] > 0.3F && Math.abs(o[2]) < 0.01F && pd[1] < -0.8F && pd[0] < 0.37F && mp[0] > 0.7F && Math.abs(mp[2]) < 0.01F,
                "liner controls: hit() finds every row at its centre; the overhead over the head, the pedestal low between the seats,"
                        + " the main panel ahead");
        check(LinerControls.QUICK.guard && LinerControls.RAT.guard && LinerControls.EMER_EXIT.guard && LinerControls.SD_PAGES.length == LinerControls.ECAM.length
                        && LinerControls.FLAPS.n() == HighLiftMath.LINER.settings(),
                "liner controls: the quick start, RAT and the emergency exit lights under guards; an ECAM key per SD page; the flap lever's"
                        + " detents = the flap settings");
    }

    private static void clicks() {
        LinerControls.Ctl q = LinerControls.QUICK;
        int[] r1 = LinerControls.apply(q, 1, false, LinerControls.A_PRIMARY);
        int[] r2 = LinerControls.apply(q, 1, true, LinerControls.A_PRIMARY);
        int[] r3 = LinerControls.apply(LinerControls.RAT, 0, true, LinerControls.A_SECONDARY);
        check(r1[0] == 1 && r1[1] == 1 && r2[0] == 0 && r2[1] == 1 && r3[0] == 0 && r3[1] == 0,
                "liner clicks: a guarded switch - the first click lifts the guard, the second throws it; RMB closes the guard");
        int[] t1 = LinerControls.apply(LinerControls.STROBE, 2, false, LinerControls.A_PRIMARY);
        int[] t2 = LinerControls.apply(LinerControls.STROBE, 0, false, LinerControls.A_PRIMARY);
        int[] f1 = LinerControls.apply(LinerControls.FLAPS, 0, false, LinerControls.A_WHEEL_UP);
        int[] f2 = LinerControls.apply(LinerControls.FLAPS, 4, false, LinerControls.A_PRIMARY);
        int[] f3 = LinerControls.apply(LinerControls.FLAPS, 2, false, LinerControls.A_SET + 4);
        int[] p1 = LinerControls.apply(LinerControls.BAT1, 0, false, LinerControls.A_PRIMARY);
        int[] m1 = LinerControls.apply(LinerControls.APU_START, 0, false, LinerControls.A_PRIMARY);
        int[] i1 = LinerControls.apply(LinerControls.TL1, 0, false, LinerControls.A_PRIMARY);
        check(t1[0] == 1 && t2[0] == 0 && f1[0] == 1 && f2[0] == 4 && f3[0] == 4 && p1[0] == 1 && m1[2] == 1 && m1[0] == 0 && i1[0] == 0 && i1[2] == 0,
                "liner clicks: a toggle steps up (LMB) and stops at the top, a lever steps a detent (wheel too) and stops at the end, a drag"
                        + " sets it, a push button toggles, a momentary one only fires, a thrust lever is a tooltip");
        LinerSystems L = cold();
        L.control(LinerControls.ECAM[5].index, LinerControls.A_PRIMARY);
        int page = L.autoPage();
        L.control(LinerControls.ECAM[5].index, LinerControls.A_PRIMARY);
        check(page == 5 && L.sdPage == 0, "liner clicks: an ECAM key shows its SD page, pressed again back to automatic");
    }

    // ---------------------------------------------------------------- ELEC

    private static void elec() {
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        run(L, e, 5, null);
        boolean dark = !L.powered() && !L.duPowered(LinerSystems.DU_PFD1);
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        run(L, e, 2, null);
        boolean ess = L.essOnly && L.duPowered(LinerSystems.DU_PFD1) && L.duPowered(LinerSystems.DU_ISIS) && !L.duPowered(LinerSystems.DU_ND1)
                && !L.duPowered(LinerSystems.DU_EWD) && !L.duPowered(LinerSystems.DU_PFD2) && L.ac1Src == LinerSystems.S_NONE;
        double min = L.batteryMinutes(e.bat1);
        check(dark && ess && min >= 15 && min <= 20,
                "liner ELEC: cold and dark - nothing; АКБ 1 + 2 - the essentials only (the captain's PFD, ISIS), the other DUs dark; on the"
                        + " batteries " + Math.round(min) + " min (15-20)");
        // external power: available, then connected - both buses; the batteries charge
        e.extAvail = true;
        run(L, e, 2, null);
        boolean avail = L.extAvail && !L.extOn && L.ac1Src == LinerSystems.S_NONE;
        click(L, LinerControls.EXT_PWR);
        double b0 = e.bat1 = e.bat1Cap * 0.5;
        run(L, e, 5, null);
        boolean ext = L.extOn && L.ac1Src == LinerSystems.S_EXT && L.ac2Src == LinerSystems.S_EXT && !L.essOnly && e.bat1 > b0 && L.bat1A > 0;
        check(avail && ext, "liner ELEC: a station - ВНЕШН. ПИТ «ДОСТУП»; pressed - it feeds both buses, the batteries charge");
        // the APU's generator below external power; an engine's generator over both on its side; the bus tie
        e = apuUp(L, true);
        e.extAvail = true;
        run(L, e, 2, null);
        click(L, LinerControls.EXT_PWR);                            // the station went away with the old env: connect again
        run(L, e, 2, null);
        boolean extFirst = L.ac1Src == LinerSystems.S_EXT && L.apuGenOn;
        L.set(LinerControls.EXT_PWR, 0);
        run(L, e, 2, null);
        boolean apuFeeds = L.ac1Src == LinerSystems.S_APU && L.ac2Src == LinerSystems.S_APU;
        L.set(LinerControls.ENG_MODE, 2);
        L.set(LinerControls.PACK1, 0);
        L.set(LinerControls.PACK2, 0);
        L.set(LinerControls.ENG_MASTER2, 1);
        run(L, e, 400, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[1].running();
            }
        });
        run(L, e, 2, null);
        boolean gen2 = L.gen2On && L.ac2Src == LinerSystems.S_GEN2 && L.ac1Src == LinerSystems.S_APU;
        L.set(LinerControls.APU_GEN, 0);
        run(L, e, 2, null);
        boolean tie = L.ac1Src == LinerSystems.S_GEN2 && L.ac2Src == LinerSystems.S_GEN2;
        L.set(LinerControls.BUS_TIE, 0);
        run(L, e, 2, null);
        boolean noTie = L.ac1Src == LinerSystems.S_NONE && L.ac2Src == LinerSystems.S_GEN2 && (L.caut & LinerSystems.C_BUS1) != 0;
        check(extFirst && apuFeeds && gen2 && tie && noTie,
                "liner ELEC: priority - own generator > external power > the APU's > the other generator through СВЯЗЬ ШИН; tie off - bus 1"
                        + " dead (caution)");
        // one generator in flight sheds the galley
        L.set(LinerControls.BUS_TIE, 1);
        e.ground = false;
        run(L, e, 2, null);
        boolean shed = L.galleyShed && (L.memos(false) & LinerSystems.M_GALLEY) != 0;
        L.set(LinerControls.APU_GEN, 1);
        run(L, e, 2, null);
        check(shed && !L.galleyShed && L.loadGen2 > 0 && L.loadApu > 0,
                "liner ELEC: one generator in flight sheds the galley («КУХНЯ ОТКЛ»); two share the load");
        // the generator's caution: running with its push button off
        L.set(LinerControls.GEN2, 0);
        run(L, e, 2, null);
        check((L.caut & LinerSystems.C_GEN2) != 0 && L.masterCaut, "liner ELEC: ГЕН 2 off with the engine running - a caution, MASTER CAUT lit");
        L.control(LinerControls.MC_L.index, LinerControls.A_PRIMARY);
        check(!L.masterCaut && (L.caut & LinerSystems.C_GEN2) != 0, "liner ELEC: MASTER CAUT pressed - the lamp out, the message stays");
    }

    // ---------------------------------------------------------------- APU

    private static void apu() {
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.set(LinerControls.APU_MASTER, 1);
        click(L, LinerControls.APU_START);
        int noPower = run(L, e, 100, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.apu != LinerSystems.A_OFF;
            }
        });
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        final double[] peak = {0};
        int avail = run(L, e, 2000, new Cond() {
            public boolean ok(LinerSystems s) {
                peak[0] = Math.max(peak[0], s.apuEgt);
                return s.apu == LinerSystems.A_AVAIL;
            }
        });
        double secs = avail / 20.0;
        check(noPower < 0 && avail > 0 && secs >= 14 && secs <= 17.5 && peak[0] > 600 && peak[0] < 760 && !L.apuStartLit,
                "liner APU: dead without DC; MASTER + START on the batteries - the flap, N to 100 %, EGT peak " + Math.round(peak[0])
                        + " C, AVAIL (ГОТОВ) after " + secs + " s (~15)");
        L.set(LinerControls.APU_BLEED, 1);
        run(L, e, 2, null);
        boolean bleed = L.apuBleedOpen && L.apuPsi > 30 && L.apuGenOn && L.ac1Src == LinerSystems.S_APU;
        double before = L.tankL, c0 = L.tankC, r0 = L.tankR;
        run(L, e, 1200, null);
        double perMin = before - L.tankL;
        check(bleed && perMin > LinerSystems.APU_FF * 0.9 && perMin < (LinerSystems.APU_FF + LinerSystems.APU_FF_LOAD) * 1.1 && L.tankC == c0 && L.tankR == r0,
                "liner APU: GEN and BLEED available; it burns " + Math.round(perMin) + " mB/min from the LEFT tank only");
        L.set(LinerControls.APU_MASTER, 0);
        int cool = run(L, e, 2000, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.apu == LinerSystems.A_DOWN;
            }
        });
        int off = run(L, e, 2000, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.apu == LinerSystems.A_OFF;
            }
        });
        check(cool / 20.0 >= 29 && cool / 20.0 <= 31 && off > 0, "liner APU: master off - a " + cool / 20.0 + " s cool-down at 100 %, then it spools"
                + " down and shuts");
        // the left tank empty - ВСУ ОТКАЗ
        LinerSystems M = cold();
        M.tankL = 0;
        LinerSystems.Env e2 = env();
        M.set(LinerControls.BAT1, 1);
        M.set(LinerControls.APU_MASTER, 1);
        click(M, LinerControls.APU_START);
        run(M, e2, 200, null);
        check(M.apuFault && (M.caut & LinerSystems.C_APU) != 0 && M.apu != LinerSystems.A_AVAIL, "liner APU: no H2 in the left tank - «ВСУ ОТКАЗ»");
    }

    // ---------------------------------------------------------------- BLEED

    private static void bleed() {
        LinerSystems L = cold();
        LinerSystems.Env e = apuUp(L, false);
        boolean apuOnly = L.apuBleedOpen && L.xbleedOpen && near(L.ductL, LinerSystems.APU_PSI) && near(L.ductR, LinerSystems.APU_PSI);
        L.set(LinerControls.PACK1, 1);
        L.set(LinerControls.PACK2, 1);
        run(L, e, 1, null);
        boolean packs = near(L.ductL, LinerSystems.APU_PSI - 2 * LinerSystems.PACK_PSI);
        L.set(LinerControls.XBLEED, 0);
        run(L, e, 1, null);
        boolean shut = near(L.ductL, LinerSystems.APU_PSI - LinerSystems.PACK_PSI) && L.ductR == 0;
        L.set(LinerControls.XBLEED, 1);
        L.set(LinerControls.APU_BLEED, 0);
        run(L, e, 1, null);
        check(apuOnly && packs && shut && L.ductL == 0 && !L.xbleedOpen,
                "liner BLEED: the APU's 38 psi into both ducts (X BLEED AUTO open with it); each pack takes 4 psi; X BLEED shut - only the left;"
                        + " APU bleed off - no air");
        LinerSystems.Eng g = new LinerSystems.Eng();
        g.n2 = LinerSystems.IDLE_N2;
        double idle = LinerSystems.engPsi(g);
        g.n2 = 99;
        double full = LinerSystems.engPsi(g);
        g.n2 = 20;
        check(near(idle, LinerSystems.ENG_PSI_IDLE) && full > 44 && LinerSystems.engPsi(g) == 0 && LinerSystems.airFactor(36) == 1
                        && LinerSystems.airFactor(12) == 0 && LinerSystems.airFactor(30) < 1,
                "liner BLEED: an engine gives 30 psi at idle, ~46 at full; the start's air factor 0 at 12 psi, 1 at 36");
    }

    // ---------------------------------------------------------------- the engine start

    private static void start() {
        LinerSystems L = cold();
        LinerSystems.Env e = apuUp(L, false);
        L.set(LinerControls.ENG_MODE, 2);
        L.set(LinerControls.ENG_MASTER2, 1);
        final int[] at = {-1, -1, -1, -1};
        final double[] peak = {0};
        int idle = run(L, e, 600, new Cond() {
            int n;

            public boolean ok(LinerSystems s) {
                n++;
                LinerSystems.Eng g = s.eng[1];
                if (at[0] < 0 && g.ign) {
                    at[0] = n;
                }
                if (at[1] < 0 && g.fuel) {
                    at[1] = n;
                }
                if (at[2] < 0 && g.n2 >= LinerSystems.CUTOUT_N2 && !g.starter) {
                    at[2] = n;
                }
                peak[0] = Math.max(peak[0], g.egt);
                return g.running();
            }
        });
        LinerSystems.Eng g = L.eng[1];
        double secs = idle / 20.0;
        check(idle > 0 && secs >= 8 && secs <= 12 && at[0] > 0 && at[1] > at[0] && at[2] > at[1] && peak[0] > 550 && peak[0] < LinerSystems.EGT_START_LIMIT
                        && near(g.n2, 58, 1) && near(g.n1, 19.8, 0.5) && g.egt >= 440 && g.egt <= 470,
                "liner start: ЗАПУСК + MASTER 2 on the APU's air - IGN at " + at[0] / 20.0 + " s (16 %), fuel " + at[1] / 20.0 + " s (22 %),"
                        + " the starter out " + at[2] / 20.0 + " s (50 %), EGT peak " + Math.round(peak[0]) + " C, idle at " + secs + " s: N2 "
                        + Math.round(g.n2) + ", N1 " + g.n1 + ", EGT " + Math.round(g.egt));
        run(L, e, 2, null);
        check(L.gen2On && L.ac2Src == LinerSystems.S_GEN2 && g.ff > 0 && L.eng[0].state == LinerSystems.E_OFF,
                "liner start: ГЕН 2 online at idle, fuel flowing; engine 1 untouched");
        // the thrust lever: N1 follows with the spool's lag; engine 1 out - the yaw
        e.throttle = 1;
        int toga = run(L, e, 400, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[1].n1 > 95;
            }
        });
        check(toga > 40 && toga < 200 && g.ff > 400 && g.egt > 700 && L.thrust() > 0.45 && L.thrust() < 0.55 && L.asymYaw(1) < -0.9,
                "liner engines: TOGA - N1 > 95 % after " + toga / 20.0 + " s, FF " + Math.round(g.ff) + " mB/min, EGT " + Math.round(g.egt)
                        + "; one engine - half the thrust and a yaw toward the dead one");
        // engine 1 from engine 2's air (X BLEED open, the APU's bleed off)
        e.throttle = 0;
        run(L, e, 200, null);
        L.set(LinerControls.APU_BLEED, 0);
        L.set(LinerControls.XBLEED, 2);
        L.set(LinerControls.ENG_MASTER1, 1);
        int one = run(L, e, 900, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[0].running();
            }
        });
        check(one > 0, "liner start: engine 1 from engine 2's bleed through X BLEED (open) - idle after " + one / 20.0 + " s");
        // master off: fuel cut, spool-down
        L.set(LinerControls.ENG_MASTER2, 0);
        int down = run(L, e, 2000, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[1].state == LinerSystems.E_OFF;
            }
        });
        check(down > 0 && L.eng[1].ff == 0 && !L.gen2On, "liner engines: master off - fuel cut, the spool-down in " + down / 20.0 + " s");
    }

    private static void faults() {
        // no air: the start is not attempted, the E/WD message
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.ENG_MODE, 2);
        L.set(LinerControls.ENG_MASTER2, 1);
        run(L, e, 60, null);
        check(L.eng[1].state == LinerSystems.E_WAIT && L.eng[1].n2 == 0 && L.noAir(1) && (L.caut & LinerSystems.C_ENG2_AIR) != 0,
                "liner start: no bleed air - the start not attempted, «ЗАПУСК ДВ 2: НЕТ ДАВЛЕНИЯ ВОЗДУХА»");
        // a rolled hot start - the EGT over the limit, the auto-abort; the master off clears it
        L = cold();
        e = apuUp(L, false);
        e.failChance = 0.5;
        e.random = 0.1;
        L.set(LinerControls.ENG_MODE, 2);
        L.set(LinerControls.ENG_MASTER2, 1);
        final double[] peak = {0};
        run(L, e, 600, new Cond() {
            public boolean ok(LinerSystems s) {
                peak[0] = Math.max(peak[0], s.eng[1].egt);
                return s.eng[1].state == LinerSystems.E_ABORT;
            }
        });
        boolean hot = L.eng[1].fault == LinerSystems.F_HOT && peak[0] > LinerSystems.EGT_START_LIMIT && (L.caut & LinerSystems.C_ENG2_START) != 0
                && !L.eng[1].fuel;
        run(L, e, 200, null);
        L.set(LinerControls.ENG_MASTER2, 0);
        run(L, e, 3, null);
        check(hot && L.eng[1].fault == LinerSystems.F_NONE && (L.caut & LinerSystems.C_ENG2_START) == 0,
                "liner start: a hot start - EGT " + Math.round(peak[0]) + " C over 725, the auto-abort, «ДВ 2 ОТКАЗ ЗАПУСКА»; the master off clears it");
        // a rolled hung start
        L = cold();
        e = apuUp(L, false);
        e.failChance = 0.5;
        e.random = 0.7;
        L.set(LinerControls.ENG_MODE, 2);
        L.set(LinerControls.ENG_MASTER2, 1);
        run(L, e, 900, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[1].state == LinerSystems.E_ABORT;
            }
        });
        check(L.eng[1].fault == LinerSystems.F_HUNG && L.eng[1].n2 < 50, "liner start: a hung start - N2 stuck at " + Math.round(L.eng[1].n2)
                + " %, aborted");
        // packs on: slower; two packs and an engine's air through X BLEED: it hangs
        L = cold();
        e = apuUp(L, true);
        L.set(LinerControls.ENG_MODE, 2);
        L.set(LinerControls.ENG_MASTER2, 1);
        int slow = run(L, e, 900, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[1].running() || s.eng[1].state == LinerSystems.E_ABORT;
            }
        });
        boolean slower = L.eng[1].running() && slow / 20.0 > 10.5;
        L.set(LinerControls.APU_BLEED, 0);
        L.set(LinerControls.XBLEED, 2);
        L.set(LinerControls.ENG_MASTER1, 1);
        run(L, e, 900, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.eng[0].running() || s.eng[0].state == LinerSystems.E_ABORT;
            }
        });
        check(slower && L.eng[0].state == LinerSystems.E_ABORT && (L.eng[0].fault == LinerSystems.F_HUNG || L.eng[0].fault == LinerSystems.F_HOT),
                "liner start: packs on - the start slower (" + slow / 20.0 + " s); a cross-bleed start with the packs on hangs / runs hot");
    }

    // ---------------------------------------------------------------- quick start

    private static void quick() {
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.control(LinerControls.QUICK.index, LinerControls.A_PRIMARY);
        boolean guarded = L.quick == LinerSystems.QUICK_OFF && L.guard[LinerControls.QUICK.index];
        L.control(LinerControls.QUICK.index, LinerControls.A_PRIMARY);
        boolean started = L.quick == LinerSystems.Q_BAT && L.at(LinerControls.QUICK) == 1;
        int done = run(L, e, 2400, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.quick == LinerSystems.QUICK_OFF;
            }
        });
        double secs = done / 20.0;
        check(guarded && started && done > 0 && "done".equals(L.quickWhy) && L.eng[0].running() && L.eng[1].running() && secs >= 35 && secs <= 45
                        && L.at(LinerControls.ENG_MODE) == 1 && !L.on(LinerControls.APU_MASTER) && L.on(LinerControls.PACK1)
                        && L.at(LinerControls.BEACON) == 0,
                "liner quick start: the guarded switch (lift, throw) - batteries, APU, its bleed, packs off, beacon, ENG 2 then ENG 1, NORM,"
                        + " APU off: both engines at idle in " + secs + " s (~40)");
        // stopped by a fault: no fuel for the APU
        LinerSystems M = cold();
        M.tankL = 0;
        M.startQuick();
        run(M, env(), 2400, new Cond() {
            public boolean ok(LinerSystems s) {
                return s.quick == LinerSystems.QUICK_OFF;
            }
        });
        LinerSystems N = cold();
        N.startQuick();
        LinerSystems.Env air = env();
        air.ground = false;
        run(N, air, 3, null);
        LinerSystems Q = cold();
        LinerSystems.Env off = env();
        off.quickAllowed = false;
        Q.startQuick();
        run(Q, off, 3, null);
        check("apu".equals(M.quickWhy) && !M.eng[1].running() && "air".equals(N.quickWhy) && "disabled".equals(Q.quickWhy),
                "liner quick start: stops on a fault (the APU without H2), in the air, and with vehicles.linerQuickStart off");
    }

    // ---------------------------------------------------------------- fuel

    private static void fuel() {
        LinerSystems L = new LinerSystems();
        L.reconcile(48000, 48000);
        boolean full = near(L.tankL, 16800) && near(L.tankR, 16800) && near(L.tankC, 14400);
        LinerSystems M = new LinerSystems();
        M.reconcile(20000, 48000);
        boolean wings = near(M.tankL, 10000) && near(M.tankR, 10000) && M.tankC == 0;
        L.reconcile(46000, 48000);
        boolean centre = near(L.tankC, 12400) && near(L.tankL, 16800);
        L.tankC = 100;
        L.reconcile(L.tanks() - 300, 48000);
        check(full && wings && centre && L.tankC == 0 && near(L.tankL, L.tankR),
                "liner fuel: filled wings first (35 % each) then the centre; taken centre first, then the wings evenly");
    }

    // ---------------------------------------------------------------- sync / save

    private static void sync() {
        LinerSystems L = cold();
        LinerSystems.Env e = apuUp(L, false);
        L.set(LinerControls.ENG_MODE, 2);
        L.set(LinerControls.ENG_MASTER2, 1);
        run(L, e, 100, null);
        L.control(LinerControls.QUICK.index, LinerControls.A_PRIMARY);
        LinerSystems C = new LinerSystems();
        String s = L.pack();
        C.unpack(s);
        String round = C.pack();
        boolean same = round.equals(s) && C.apu == L.apu && C.eng[1].state == L.eng[1].state && near(C.eng[1].n2, L.eng[1].n2, 0.06)
                && C.guard[LinerControls.QUICK.index] && C.at(LinerControls.ENG_MODE) == 2 && C.apuBleedOpen && near(C.ductL, L.ductL, 0.06);
        C.unpack("junk");
        C.unpack("12|1,2");
        if (!same) {
            System.out.println("[SVA-TEST] liner sync: " + s + " / " + round);
        }
        // wave 3 added its ECAM / fuel / cabin part (the bound was 400)
        check(same && s.length() < 720 && C.apu == L.apu, "liner sync: the string (" + s.length() + " chars) round-trips; junk is ignored");
        // the save: positions by id, engines / APU running, tanks, АКБ 2; the vehicle state carries it
        NBTTagCompound t = new NBTTagCompound();
        run(L, e, 400, null);
        L.writeNBT(t);
        LinerSystems R = new LinerSystems();
        R.readNBT(t);
        check(R.eng[1].running() && R.apuRunning() && R.at(LinerControls.ENG_MASTER2) == 1 && near(R.tankL, L.tankL, 1),
                "liner save: the controls by id, the engines / APU running, the tanks");
    }

    /** VehicleState carries the liner's systems (needs FML's classes: last). */
    private static void saves() {
        VehicleState vs = new VehicleState(VehicleType.LINER);
        vs.liner().set(LinerControls.FLAPS, 3);
        vs.liner().bat2In = false;
        NBTTagCompound vt = new NBTTagCompound();
        vs.writeNBT(vt);
        VehicleState back = new VehicleState(VehicleType.LINER);
        back.readNBT(vt);
        VehicleState other = new VehicleState(VehicleType.JET);
        check(back.liner().at(LinerControls.FLAPS) == 3 && !back.liner().bat2In && other.liner() == null,
                "liner save: VehicleState keeps the liner's systems in its NBT (a jet has none)");
    }

    // ---------------------------------------------------------------- textures / LOD

    private static void textures() {
        boolean sizes = LinerTexMath.size(LinerTexMath.LOW, HiResMath.Q4K) == 512 && LinerTexMath.size(LinerTexMath.Q1K, HiResMath.Q4K) == 1024
                && LinerTexMath.size(LinerTexMath.Q2K, HiResMath.Q1K) == 2048 && LinerTexMath.size(LinerTexMath.Q4K, HiResMath.Q1K) == 4096
                && LinerTexMath.size(LinerTexMath.AUTO, HiResMath.Q1K) == 1024 && LinerTexMath.size(LinerTexMath.AUTO, HiResMath.Q2K) == 2048
                && LinerTexMath.size(LinerTexMath.AUTO, HiResMath.Q4K) == 4096 && LinerTexMath.parse("low") == LinerTexMath.LOW
                && LinerTexMath.parse("junk") == LinerTexMath.AUTO && LinerTexMath.levels(4096) == 11 && LinerTexMath.vram(512) < 3L << 20
                && LinerTexMath.vram(4096) < 128L << 20;
        check(sizes, "liner textures: tiers LOW / 1K / 2K / 4K = 512 / 1024 / 2048 / 4096 px (AUTO by cockpitQuality), mips to 4 px, VRAM "
                + (LinerTexMath.vram(4096) >> 20) + " MB at 4K");
        boolean layout = true;
        for (int i = 0; i < LinerTexMath.RECTS.length; i++) {
            int[] a = LinerTexMath.RECTS[i];
            layout &= a[0] >= 0 && a[1] >= 0 && a[0] + a[2] <= LinerTexMath.ATLAS_UNITS && a[1] + a[3] <= LinerTexMath.ATLAS_UNITS;
            for (int j = i + 1; j < LinerTexMath.RECTS.length; j++) {
                layout &= !LinerTexMath.overlap(a, LinerTexMath.RECTS[j], 7);
            }
        }
        for (int p = 0; p < LinerControls.PANELS; p++) {
            layout &= LinerTexMath.RECTS[p][2] == LinerControls.PANEL_W[p] && LinerTexMath.RECTS[p][3] == LinerControls.PANEL_H[p];
        }
        check(layout && LinerTexMath.RECTS.length == LinerTexMath.RECT_COUNT,
                "liner textures: the atlas layout - every panel at its own size, 8 units apart, inside 4096");
        boolean lod = LinerTexMath.lod(10, -1) == 0 && LinerTexMath.lod(100, 0) == 1 && LinerTexMath.lod(300, 1) == 2 && LinerTexMath.lod(66, 0) == 0
                && LinerTexMath.lod(62, 1) == 1 && LinerTexMath.lod(55, 1) == 0 && LinerTexMath.lod(158, 2) == 2 && LinerTexMath.lod(150, 2) == 1;
        check(lod, "liner LOD: full model under 64 blocks, LOD1 to 160, LOD2 beyond; a 6-block band against flicker");
    }

    // ---------------------------------------------------------------- lang

    private static void lang() throws java.io.IOException {
        Set<String> ru = LangKeysSVA.keys(LangKeysSVA.RU), en = LangKeysSVA.keys(LangKeysSVA.EN);
        StringBuilder miss = new StringBuilder();
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (LinerControls.Ctl c : LinerControls.all()) {
            keys.add(c.nameKey());
        }
        for (String w : LinerControls.words()) {
            keys.add("sva.liner.pos." + w);
        }
        for (String k : LinerControls.PANEL_KEYS) {
            keys.add("sva.liner.panel." + k);
        }
        for (String k : LinerSystems.WARN_KEYS) {
            keys.add("sva.liner.msg." + k);
        }
        for (String k : LinerSystems.CAUT_KEYS) {
            keys.add("sva.liner.msg." + k);
        }
        for (String k : LinerSystems.MEMO_KEYS) {
            keys.add("sva.liner.memo." + k);
        }
        for (String k : LinerControls.SD_PAGES) {
            keys.add("sva.liner.sd." + k);
        }
        for (String k : new String[] {"done", "started", "stopped.fault", "stopped.apu", "stopped.air", "stopped.battery", "stopped.fuel",
                "stopped.disabled", "stopped.timeout", "stopped.pilot", "stopped.running"}) {
            keys.add("sva.liner.quick." + k);
        }
        for (String k : new String[] {"sva.liner.msg.useDoor", "sva.liner.msg.belly", "sva.liner.msg.bat2.in", "sva.liner.msg.bat2.out",
                "sva.liner.door.air", "item.siliconagevehicles.vehicle.liner.name", "item.siliconagevehicles.battery.liner.name",
                "sva.fn.gear", "sva.fn.gear.tip", "sva.fn.cursor", "sva.fn.cursor.tip"}) {
            keys.add(k);
        }
        for (String k : keys) {
            if (ru == null || en == null || !ru.contains(k) || !en.contains(k)) {
                miss.append(' ').append(k);
            }
        }
        check(miss.length() == 0, "liner lang: every control, position word, panel, message, memo, SD page and quick-start line in ru and en" + miss);
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }
}
