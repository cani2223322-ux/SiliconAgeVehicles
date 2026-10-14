package com.sva.debug;

import java.util.Map;

import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerIce;
import com.sva.vehicle.LinerLights;
import com.sva.vehicle.LinerSystems;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 6 self-tests (plan-next §7: the SA-320's lights, anti-ice, icing, air conditioning) - pure logic on the
 * dedicated server: the exterior lights' switch logic (STROBE АВТО, NOSE with the gear, the landing lights' travel, NAV /
 * LOGO, no power), the signs' АВТО and their chime, the emergency lighting (ВЗВЕД on the AC's loss, its batteries), the
 * interior's light levels, the anti-ice's bleed dependence and the 30 s ground test, the N1 / fuel / thrust costs, the
 * icing (the air, the accretion and shedding rates, the stall / drag / mass effects, the engines' shaking and flame-out,
 * the probes freezing the airspeed and the heat keeping them clear, the frost), the cabin's temperatures and altitude,
 * the electrical loads and the cabin lighting's reduction with the galley shed, the ECAM's messages and memos, the sync
 * and the save, the lang keys.
 */
final class SelfTestLinerW6SVA {

    private SelfTestLinerW6SVA() {
    }

    private static void check(boolean ok, String name) {
        SelfTestSVA.check(ok, name);
    }

    static void run() throws java.io.IOException {
        exterior();
        landing();
        signs();
        emergency();
        interior();
        antiIce();
        costs();
        air();
        icing();
        probes();
        engines();
        cabin();
        loads();
        ecam();
        sync();
        lang();
    }

    // ---------------------------------------------------------------- helpers

    static LinerSystems cold() {
        return SelfTestLinerSVA.cold();
    }

    static LinerSystems.Env env() {
        return SelfTestLinerSVA.env();
    }

    static int run(LinerSystems L, LinerSystems.Env e, int n) {
        return SelfTestLinerSVA.run(L, e, n, null);
    }

    /** Both engines running at once (the tests' instant start), batteries on: AC from both generators, bleed from both. */
    static LinerSystems.Env running(LinerSystems L) {
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.ENG_MASTER1, 1);
        L.set(LinerControls.ENG_MASTER2, 1);
        e.instant = true;
        e.instantHyd = true;
        run(L, e, 5);
        e.instant = false;
        return e;
    }

    /** In the air at altitude y (blocks), out of the ground's phases (the cruise). */
    static void air(LinerSystems.Env e, double y) {
        e.ground = false;
        e.height = 500;
        e.alt = y;
        e.airspeed = 3;
        e.speed = 3;
        e.kmh = 216;
    }

    static boolean has(long bits, int id) {
        return (bits & 1L << id) != 0;
    }

    // ---------------------------------------------------------------- exterior lights

    private static void exterior() {
        LinerSystems L = cold();
        boolean dark = LinerLights.exterior(L, false, true, true, 0, 1, 1) == 0;
        L.set(LinerControls.NAV_LOGO, 0);
        L.set(LinerControls.BEACON, 0);
        int x = LinerLights.exterior(L, true, false, true, 0, 0, 0);
        boolean nav = (x & (LinerLights.X_NAV | LinerLights.X_LOGO | LinerLights.X_BEACON)) == (LinerLights.X_NAV | LinerLights.X_LOGO | LinerLights.X_BEACON);
        L.set(LinerControls.NAV_LOGO, 2);
        boolean nav2 = (LinerLights.exterior(L, true, false, true, 0, 0, 0) & LinerLights.X_NAV) != 0;
        L.set(LinerControls.NAV_LOGO, 1);
        boolean navOff = (LinerLights.exterior(L, true, false, true, 0, 0, 0) & (LinerLights.X_NAV | LinerLights.X_LOGO)) == 0;
        check(dark && nav && nav2 && navOff, "liner lights: no power - all dark; НАВ И ЛОГО 1 / 2 - the nav and logo lights, ВЫКЛ none; МАЯК ВКЛ");
        // STROBE: ВКЛ always, АВТО with the gear not compressed, ВЫКЛ never
        L.set(LinerControls.STROBE, 1);
        boolean autoGround = (LinerLights.exterior(L, true, true, true, 0, 0, 0) & LinerLights.X_STROBE) == 0;
        boolean autoAir = (LinerLights.exterior(L, true, true, false, 0, 0, 0) & LinerLights.X_STROBE) != 0;
        L.set(LinerControls.STROBE, 0);
        boolean on = (LinerLights.exterior(L, true, true, true, 0, 0, 0) & LinerLights.X_STROBE) != 0;
        L.set(LinerControls.STROBE, 2);
        boolean off = (LinerLights.exterior(L, true, true, false, 0, 0, 0) & LinerLights.X_STROBE) == 0;
        int flashes = 0;
        for (long t = 0; t < 40; t++) {
            flashes += LinerLights.strobeAt(t, 7) ? 1 : 0;
        }
        check(autoGround && autoAir && on && off && flashes == 4 && LinerLights.strobe(1, false) && !LinerLights.strobe(1, true),
                "liner lights: СТРОБ АВТО - off on the ground, on in the air; ВКЛ / ВЫКЛ; a double flash a second (" + flashes + " in 2 s)");
        // NOSE: taxi / take-off only with the nose gear down; the turn-off lights too
        L.set(LinerControls.NOSE_LT, 1);
        L.set(LinerControls.RWY_TURN, 0);
        int down = LinerLights.exterior(L, true, true, true, 0, 0, 0), up = LinerLights.exterior(L, true, true, false, 0.6, 0, 0);
        L.set(LinerControls.NOSE_LT, 0);
        int to = LinerLights.exterior(L, true, true, true, 0, 0, 0);
        int noAc = LinerLights.exterior(L, true, false, true, 0, 0, 0);
        check((down & LinerLights.X_TAXI) != 0 && (down & LinerLights.X_RTO) != 0 && (up & (LinerLights.X_TAXI | LinerLights.X_RTO)) == 0
                        && (to & LinerLights.X_TO) != 0 && (to & LinerLights.X_TAXI) == 0 && (noAc & (LinerLights.X_TO | LinerLights.X_RTO)) == 0,
                "liner lights: НОС РУЛЁЖ / ВЗЛЁТ and РУЛЁЖ. БОК. only with the nose gear down (up with it), on AC");
        // the beams' geometry
        boolean beams = true;
        for (LinerLights.Lamp l : LinerLights.LAMPS) {
            float[] b = LinerLights.beam(l);
            if (b != null) {
                double n = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
                beams &= Math.abs(n - 1) < 0.02 || l.kind == LinerLights.K_WING;
                beams &= l.kind == LinerLights.K_WING || b[1] < 0 && b[0] > 0.3;
                beams &= l.kind != LinerLights.K_RTO || b[2] * l.side > 0.7;
            }
        }
        check(beams && LinerLights.LAMPS.length == 19, "liner lights: 19 lamps at their places; the beams forward-down, the turn-off lights to their sides");
    }

    private static void landing() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.set(LinerControls.LAND_L, 0);
        run(L, e, LinerLights.LAND_TICKS / 2);
        boolean half = L.lights.landL > 0.4 && L.lights.landL < 0.6 && (L.lights.ext & LinerLights.X_LAND_L) == 0 && L.lights.landR == 0;
        run(L, e, LinerLights.LAND_TICKS);
        boolean out = L.lights.landL == 1 && (L.lights.ext & LinerLights.X_LAND_L) != 0;
        L.set(LinerControls.LAND_L, 1);
        run(L, e, 2);
        boolean outOff = L.lights.landL == 1 && (L.lights.ext & LinerLights.X_LAND_L) == 0;
        L.set(LinerControls.LAND_L, 2);
        run(L, e, LinerLights.LAND_TICKS + 2);
        boolean in = L.lights.landL == 0;
        check(half && out && outOff && in, "liner lights: ПОСАД. Л - out of its well in ~3 s, shines only out and ВКЛ; ВЫКЛ stays out dark; УБРАНА"
                + " back in");
    }

    // ---------------------------------------------------------------- signs, emergency lights, interior

    private static void signs() {
        boolean pure = LinerLights.sign(0, 1, 0) && LinerLights.sign(1, 0, 0) && !LinerLights.sign(1, 1, 0) && LinerLights.sign(1, 1, 2)
                && !LinerLights.sign(2, 0, 3);
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        run(L, e, 2);
        boolean noSmoke = (L.lights.signs & LinerLights.S_NOSMOKE) != 0 && (L.lights.signs & LinerLights.S_BELTS) == 0;   // АВТО / ВЫКЛ, gear down
        L.control(LinerControls.SEAT_BELTS.index, LinerControls.A_PRIMARY);                // ВЫКЛ -> АВТО
        run(L, e, 1);
        boolean chime = (L.events & LinerSystems.E_SIGN) != 0 && (L.lights.signs & LinerLights.S_BELTS) != 0;
        run(L, e, 1);
        boolean once = (L.events & LinerSystems.E_SIGN) == 0;
        check(pure && noSmoke && chime && once, "liner signs: ВКЛ lit, АВТО with the gear not up or the flaps out, ВЫКЛ dark; a change chimes once");
    }

    private static void emergency() {
        boolean pure = !LinerLights.emergency(1, true, true, 100) && LinerLights.emergency(1, true, false, 100) && !LinerLights.emergency(1, false, false, 0)
                && LinerLights.emergency(0, false, false, 10) && !LinerLights.emergency(2, false, false, 100);
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        run(L, e, 20);
        boolean coldDark = (L.lights.signs & LinerLights.S_EMER) == 0;             // never charged: its batteries are flat
        e = running(L);
        run(L, e, 200);
        boolean charged = L.lights.emerBat > 700 && (L.lights.signs & LinerLights.S_EMER) == 0;
        L.set(LinerControls.GEN1, 0);
        L.set(LinerControls.GEN2, 0);
        run(L, e, 3);
        double b0 = L.lights.emerBat;
        boolean lit = !L.acPowered() && (L.lights.signs & LinerLights.S_EMER) != 0;
        run(L, e, 10);
        boolean drains = L.lights.emerBat < b0;
        L.set(LinerControls.EMER_EXIT, 2);
        run(L, e, 1);
        boolean off = (L.lights.signs & LinerLights.S_EMER) == 0;
        check(pure && coldDark && charged && lit && drains && off, "liner emergency lights: ВЗВЕД - lit by themselves on the AC's loss from their"
                + " batteries (charged with AC, draining lit); ВЫКЛ dark");
    }

    private static void interior() {
        LinerSystems L = cold();
        boolean dark = LinerLights.cockpitLight(L, true) == 0 && LinerLights.cockpitLight(L, false) == 0;
        L.set(LinerControls.DOME, 1);
        int dim = LinerLights.cockpitLight(L, true);
        L.set(LinerControls.DOME, 0);
        int bright = LinerLights.cockpitLight(L, true);
        L.set(LinerControls.DOME, 2);
        L.set(LinerControls.FLOOD_R, 4);
        int flood = LinerLights.cockpitLight(L, true);
        check(dark && dim == 170 && bright == 240 && flood == 220 && LinerLights.cockpitLight(L, false) == 0,
                "liner cockpit light: dark with all off (the night's own), ПЛАФОН ТУСКЛО / ЯРКО, the flood knob; none without power");
        boolean steps = LinerLights.cabinStep(L, true, false) == 3 && LinerLights.cabinStep(L, true, true) == 2 && LinerLights.cabinStep(L, false, false) == -1;
        L.set(LinerControls.CABIN_LT, 0);
        steps &= LinerLights.cabinStep(L, true, true) == 0 && LinerLights.cabinLight(0) < LinerLights.cabinLight(1) && LinerLights.cabinLight(-1) == 0;
        LinerLights lt = new LinerLights();
        boolean reading = lt.toggleReading(5) && lt.readingOn(5) && !lt.toggleReading(1) && !lt.readingOn(0) && lt.toggleReading(5) && lt.reading == 0;
        check(steps && reading && LinerLights.screenAuto(1F) < LinerLights.screenAuto(0F),
                "liner cabin light: САЛОН НОЧЬ / 1 / 2 / 3 (one step dimmer with the galley shed, dark without AC); a passenger's reading"
                        + " light his own (the pilots have none); the screens dimmer at night");
    }

    // ---------------------------------------------------------------- anti-ice

    private static void antiIce() {
        // without bleed: refused (its valves shut, ОТКАЗ)
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        L.set(LinerControls.AI_WING, 1);
        run(L, e, 3);
        boolean refused = !L.ice.wingOn && L.ice.wingFault && L.faultLamp(LinerControls.AI_WING) && has(L.ecam.active, LinerEcam.M_WING_AI);
        // with the engines' bleed: on, on the ground only the 30 s test
        L = cold();
        e = running(L);
        L.set(LinerControls.AI_WING, 1);
        run(L, e, 2);
        boolean on = L.ice.wingOn && !L.ice.wingFault && (L.memos(true) & LinerSystems.M_WING_AI) != 0;
        int t = SelfTestLinerSVA.run(L, e, 800, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return !s.ice.wingOn;
            }
        });
        boolean test = t > LinerIce.WING_TEST_TICKS - 20 && t < LinerIce.WING_TEST_TICKS + 20 && !L.ice.wingFault && !L.faultLamp(LinerControls.AI_WING);
        air(e, 150);
        run(L, e, 2);
        boolean flight = L.ice.wingOn;
        check(refused && on && test && flight, "liner WING A.ICE: no bleed - refused (ОТКАЗ, «ПОС КРЫЛА ОТКАЗ»); on the ground a test of "
                + t / 20 + " s, then its valves shut; in flight on (memo «ПОС КРЫЛА»)");
        // ENG A.ICE: the engine's own bleed; an engine not running in flight - ОТКАЗ
        L.set(LinerControls.AI_ENG1, 1);
        L.set(LinerControls.AI_ENG2, 1);
        run(L, e, 2);
        boolean eng = L.ice.engOn[0] && L.ice.engOn[1] && (L.memos(false) & LinerSystems.M_ENG_AI) != 0;
        L.set(LinerControls.ENG_MASTER2, 0);
        run(L, e, 200);
        boolean fault = !L.ice.engOn[1] && L.ice.engFault[1] && L.faultLamp(LinerControls.AI_ENG2) && has(L.ecam.active, LinerEcam.M_ENG2_AI)
                && L.ice.engOn[0];
        check(eng && fault, "liner ENG A.ICE: from the engine's own bleed (memo «ПОС ДВ»); its engine out in flight - ОТКАЗ, «ПОС ДВ 2 ОТКАЗ»");
        // PROBE / WINDOW HEAT: АВТО with an engine running or in flight, on AC
        LinerSystems P = cold();
        LinerSystems.Env pe = env();
        P.set(LinerControls.BAT1, 1);
        run(P, pe, 2);
        boolean autoOff = !P.ice.probeHeat && !P.ice.probeFault;
        pe = running(P);
        boolean autoOn = P.ice.probeHeat;
        check(autoOff && autoOn, "liner PROBE / WINDOW HEAT: АВТО - off cold and dark on the ground, on with an engine running");
    }

    private static void costs() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        e.throttle = 1;
        run(L, e, 400);
        double n1 = L.eng[0].n1, ff = L.eng[0].ff, fwd = L.fwd(0);
        L.set(LinerControls.AI_ENG1, 1);
        L.set(LinerControls.AI_WING, 1);
        air(e, 150);
        run(L, e, 400);
        double n1ai = L.eng[0].n1, ffai = L.eng[0].ff;
        double loss = LinerIce.ENG_AI_N1 + LinerIce.WING_AI_N1;
        boolean limit = SelfTestLinerSVA.near(n1 - n1ai, loss, 0.6);
        double k = Math.max(0, Math.min(1, (n1ai - LinerSystems.IDLE_N1) / (LinerSystems.TOGA_N1 - LinerSystems.IDLE_N1)));
        double base = LinerSystems.IDLE_FF + (LinerSystems.TOGA_FF - LinerSystems.IDLE_FF) * Math.pow(k, 1.4);
        boolean burn = SelfTestLinerSVA.near(ffai, base * (1 + LinerIce.ENG_AI_FF + LinerIce.WING_AI_FF), 0.5);
        double thrust = L.eng[0].thrust() * (1 - LinerIce.WING_AI_THRUST);
        boolean thr = SelfTestLinerSVA.near(L.fwd(0), thrust, 1e-6) && L.fwd(0) < fwd;
        check(limit && burn && thr && L.ice.n1Loss(0) == loss && L.ice.ffFactor(1) == 1 + LinerIce.WING_AI_FF,
                "liner anti-ice costs: N1 limit " + Math.round(n1 * 10) / 10.0 + " -> " + Math.round(n1ai * 10) / 10.0 + " % (ENG + WING),"
                        + " fuel flow x" + (1 + LinerIce.ENG_AI_FF + LinerIce.WING_AI_FF) + ", thrust -" + Math.round(LinerIce.WING_AI_THRUST * 100) + " %");
    }

    // ---------------------------------------------------------------- icing

    private static void air() {
        boolean oat = SelfTestLinerSVA.near(LinerIce.oat(64, 0.8), 15) && SelfTestLinerSVA.near(LinerIce.oat(128, 0.8), 2.2, 0.01)
                && LinerIce.oat(64, 0.0) < 0 && LinerIce.oat(200, 0.8) < -10 && LinerIce.oat(64, 2.0) <= 40;
        boolean wet = LinerIce.moisture(128, false, false) && !LinerIce.moisture(150, false, false) && LinerIce.moisture(150, true, false)
                && !LinerIce.moisture(80, true, false) && LinerIce.moisture(110, false, true) && !LinerIce.moisture(90, false, true);
        boolean sev = LinerIce.severity(10) == 0 && LinerIce.severity(-45) == 0 && SelfTestLinerSVA.near(LinerIce.severity(LinerIce.ICING_PEAK), 1)
                && LinerIce.severity(0) < LinerIce.severity(-5) && LinerIce.severity(-30) < LinerIce.severity(-10) && LinerIce.severity(-39) > 0.1;
        check(oat && wet && sev, "liner icing air: the outside air by biome and height (15 C at sea level over the plains, ~2 C at the cloud"
                + " layer), visible moisture (the clouds, rain / snow high up, a snowy biome's air), icing worst at -8 C");
    }

    private static void icing() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        air(e, 150);
        e.iceAir.oat = LinerIce.ICING_PEAK;
        e.iceAir.moisture = true;
        run(L, e, 450);
        double w = L.ice.wing, g = L.ice.eng[0];
        boolean rate = SelfTestLinerSVA.near(w, 450 * LinerIce.ICE_RATE, 0.02) && SelfTestLinerSVA.near(g, 450 * LinerIce.ICE_RATE * LinerIce.ENG_RATE_K, 0.02)
                && L.ice.probe[0] == 0 && L.ice.frost == 0;
        boolean det = L.ice.detected() && has(L.ecam.active, LinerEcam.M_ICE) && LinerEcam.LEVEL[LinerEcam.M_ICE] == LinerEcam.CAUT;
        boolean effects = SelfTestLinerSVA.near(L.ice.stallFactor(), 1 + LinerIce.STALL_K * w, 1e-9) && L.ice.dragFactor() > 1.1
                && L.ice.mass() > 300 && L.ice.liftOffFactor() > 1;
        check(rate && det && effects, "liner icing: in cloud at -8 C the wing ices " + Math.round(w * 100) + " % and the engines " + Math.round(g * 100)
                + " % in 22 s (the heated probes / windscreen clear); «ЛЁД ОБНАРУЖЕН»; stall x" + Math.round(L.ice.stallFactor() * 100) / 100.0
                + ", drag, mass");
        // the anti-ice on: the ice shed (chunks fly), cleared in seconds; the caution goes
        L.set(LinerControls.AI_WING, 1);
        L.set(LinerControls.AI_ENG1, 1);
        L.set(LinerControls.AI_ENG2, 1);
        L.tick(e);
        boolean shed = (L.events & LinerSystems.E_ICE_SHED) != 0;
        int t = SelfTestLinerSVA.run(L, e, 400, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.ice.wing == 0 && s.ice.eng[0] == 0 && s.ice.eng[1] == 0;
            }
        });
        boolean clear = t > 0 && t < 120 && !has(L.ecam.active, LinerEcam.M_ICE);
        // warm air melts it slowly; dry air does not ice
        LinerIce ic = new LinerIce();
        LinerSystems M = cold();
        LinerSystems.Env me = running(M);
        air(me, 150);
        me.iceAir.oat = -10;
        me.iceAir.moisture = false;
        run(M, me, 200);
        boolean dry = M.ice.wing == 0;
        M.ice.wing = 0.5;
        me.iceAir.oat = 10;
        run(M, me, 150);
        boolean melt = SelfTestLinerSVA.near(M.ice.wing, 0.5 - 150 * LinerIce.MELT_RATE, 0.01) && ic.wing == 0;
        check(shed && clear && dry && melt, "liner icing: the anti-ice sheds it (chunks fly) in " + t / 20.0 + " s; dry air does not ice; warm air"
                + " melts it slowly");
    }

    private static void probes() {
        // on the batteries in flight: the probes unheated - they ice, the airspeed freezes (the A/THR and the AP let go)
        LinerSystems L = cold();
        LinerSystems.Env e = env();
        L.set(LinerControls.BAT1, 1);
        L.set(LinerControls.BAT2, 1);
        run(L, e, 2);
        air(e, 150);
        e.iceAir.oat = LinerIce.ICING_PEAK;
        e.iceAir.moisture = true;
        e.kmh = 300;
        int t = SelfTestLinerSVA.run(L, e, 600, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.ice.unreliable();
            }
        });
        boolean frozen = L.ice.iced(0) && L.ice.indicated(0, 200) == 300 && L.ice.indicated(0, 250) == 300;
        L.tick(e);
        boolean msgs = has(L.ecam.active, LinerEcam.M_IAS) && has(L.ecam.active, LinerEcam.M_PROBE_HEAT) && L.ice.probeFault && L.ice.frost > 0;
        check(t > 250 && t < 400 && frozen && msgs, "liner probes: unheated in icing a probe freezes in " + t / 20.0 + " s - the airspeed stuck at"
                + " 300 km/h; «НЕДОСТ. СКОРОСТЬ», «ОБОГРЕВ ПВД ОТКАЗ» (no AC); the windscreen frosts");
        // heated (АВТО in flight on AC): no ice on the probes, the frost clears
        LinerSystems H = cold();
        LinerSystems.Env he = running(H);
        air(he, 150);
        he.iceAir.oat = LinerIce.ICING_PEAK;
        he.iceAir.moisture = true;
        H.ice.frost = 0.6;
        run(H, he, 600);
        boolean clean = H.ice.probe[0] == 0 && H.ice.probe[1] == 0 && H.ice.probe[2] == 0 && !H.ice.unreliable() && H.ice.frost == 0
                && !has(H.ecam.active, LinerEcam.M_IAS);
        check(clean, "liner probes: the probe / window heat on - the probes stay clear in icing, the windscreen's frost clears");
    }

    private static void engines() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        air(e, 150);
        e.throttle = 0.6;
        run(L, e, 300);
        L.ice.eng[1] = 0.8;
        double lo = 999, hi = -999;
        for (int i = 0; i < 60; i++) {
            L.tick(e);
            lo = Math.min(lo, L.eng[1].n1);
            hi = Math.max(hi, L.eng[1].n1);
        }
        boolean shakes = hi - lo > 0.5 && L.ice.vib(1, true) > 3 && L.eng[1].running();
        L.ice.eng[0] = 1;
        L.tick(e);
        boolean out = L.eng[0].fault == LinerSystems.F_FLAMEOUT && !L.eng[0].running();
        check(shakes && out, "liner engine ice: the N1 fluctuates " + Math.round((hi - lo) * 10) / 10.0 + " %, the vibration up; severe ice flames it out");
    }

    // ---------------------------------------------------------------- the cabin

    private static void cabin() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.set(LinerControls.TEMP_CK, 6);
        run(L, e, 1500);
        boolean zones = L.cabT[0] > 28 && L.cabT[1] < 23 && L.cabT[1] > 20 && SelfTestLinerSVA.near(LinerIce.zoneTarget(L, 0), 30)
                && L.ice.pack1Flow == 100 && L.ice.pack2Flow == 100;
        L.set(LinerControls.PACK2, 0);
        run(L, e, 2);
        boolean one = L.ice.pack1Flow == 120 && L.ice.pack2Flow == 0;
        // the packs off high up: the cabin cools, the passengers freeze; the cabin altitude climbs - «ВЫСОТА КАБИНЫ»
        L.set(LinerControls.PACK1, 0);
        air(e, 250);
        e.iceAir.oat = LinerIce.oat(250, 0.8);
        int t = SelfTestLinerSVA.run(L, e, 400, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return has(s.ecam.active, LinerEcam.M_CAB_ALT);
            }
        });
        boolean warn = t > 0 && L.masterWarn && LinerEcam.LEVEL[LinerEcam.M_CAB_ALT] == LinerEcam.WARN && L.cabAlt > LinerIce.CAB_ALT_WARN;
        int f = SelfTestLinerSVA.run(L, e, 9000, new SelfTestLinerSVA.Cond() {
            public boolean ok(LinerSystems s) {
                return s.ice.freezing();
            }
        });
        boolean cold = f > 0 && L.cabT[1] < LinerIce.COLD_T;
        L.set(LinerControls.PACK1, 1);
        L.set(LinerControls.PACK2, 1);
        run(L, e, 200);
        boolean back = !has(L.ecam.active, LinerEcam.M_CAB_ALT) && L.cabAlt < LinerIce.CAB_ALT_WARN;
        check(zones && one && warn && cold && back, "liner air conditioning: the zones hold their selectors (cockpit 30 C), the packs' flow 100 / 120 %;"
                + " packs off at Y 250 - «ВЫСОТА КАБИНЫ» in " + t / 20.0 + " s, the cabin cold in " + f / 1200 + " min; packs on - it comes down");
    }

    // ---------------------------------------------------------------- loads

    private static void loads() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        run(L, e, 2);
        int heat = L.loadHeat, g1 = L.loadGen1;
        L.set(LinerControls.LAND_L, 0);
        L.set(LinerControls.LAND_R, 0);
        L.set(LinerControls.NOSE_LT, 0);
        run(L, e, LinerLights.LAND_TICKS + 3);
        int lights = L.loadLights, g1b = L.loadGen1;
        boolean more = heat == LinerIce.LOAD_WINDOW + LinerIce.LOAD_PROBE && lights >= 2 * LinerLights.LOAD_LAND + LinerLights.LOAD_NOSE
                && g1b > g1 && g1b - g1 <= (lights + 1) / 2 + 1;
        // one generator in flight: the galley shed and the cabin lights a step dimmer
        L.set(LinerControls.GEN2, 0);
        air(e, 150);
        run(L, e, 3);
        boolean shed = L.galleyShed && LinerLights.cabinStep(L, L.acPowered(), L.galleyShed) == 2;
        check(more && shed, "liner loads: the window / probe heat " + heat + " %, the lights " + lights + " % on the generators (ГЕН 1 " + g1 + " -> "
                + g1b + " %); one generator in flight - the galley shed, the cabin lights a step dimmer");
    }

    // ---------------------------------------------------------------- ECAM, sync, save, lang

    private static void ecam() {
        boolean table = LinerEcam.N == 64 && LinerEcam.KEYS[LinerEcam.M_CAB_ALT] != null && LinerEcam.LEVEL[LinerEcam.M_IAS] == LinerEcam.CAUT
                && LinerEcam.ACT_KEYS.length == LinerEcam.ACTS && LinerEcam.INOP_KEYS.length == 17 && LinerEcam.LIM_KEYS.length == 8
                && LinerSystems.MEMO_KEYS.length == 31 && (LinerSystems.M_ENG_AI & LinerSystems.MEMO_AMBER) == 0;
        // the ice caution's action lines tick off as the anti-ice comes on
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        air(e, 150);
        L.ice.wing = 0.3;
        L.tick(e);
        int[] open = L.ecam.openActions(LinerEcam.M_ICE);
        L.set(LinerControls.AI_ENG1, 1);
        L.set(LinerControls.AI_ENG2, 1);
        L.tick(e);
        int[] left = L.ecam.openActions(LinerEcam.M_ICE);
        boolean acts = open.length == 2 && left.length == 1 && left[0] == LinerEcam.A_WING_AI_ON && L.masterCaut;
        check(table && acts, "liner ECAM wave 6: 7 new messages (the table full: 64), «ЛЁД ОБНАРУЖЕН» with «- ПОС ДВ 1+2 / - ПОС КРЫЛА»"
                + " ticked off as done; the memos «ПОС КРЫЛА» / «ПОС ДВ» green");
    }

    private static void sync() {
        LinerSystems L = cold();
        LinerSystems.Env e = running(L);
        L.set(LinerControls.LAND_R, 0);
        L.set(LinerControls.STROBE, 0);
        L.set(LinerControls.AI_WING, 1);
        L.set(LinerControls.TEMP_CAB, 5);
        L.lights.toggleReading(7);
        air(e, 150);
        e.iceAir.oat = -6;
        e.iceAir.moisture = true;
        run(L, e, 80);
        L.ice.eng[0] = 0.4;
        L.ice.frozenKmh[2] = 280;
        LinerSystems C = new LinerSystems();
        String s = L.pack();
        C.unpack(s);
        boolean same = C.pack().equals(s) && C.lights.ext == L.lights.ext && (C.lights.ext & LinerLights.X_LAND_R) != 0 && C.lights.readingOn(7)
                && C.lights.landR == 1 && C.ice.wingOn && SelfTestLinerSVA.near(C.ice.eng[0], 0.4) && C.ice.frozenKmh[2] == 280 && C.loadHeat == L.loadHeat
                && C.at(LinerControls.TEMP_CAB) == 5 && SelfTestLinerSVA.near(C.ice.oatNow, -6);
        // an older string (no sixth part) leaves the wave-6 words alone
        LinerSystems O = new LinerSystems();
        O.unpack(s.substring(0, s.lastIndexOf('|')));
        boolean old = O.lights.ext == 0 && O.ice.wing == 0;
        System.out.println("[SVA-TEST] liner w6 sync string: " + s.length() + " chars");
        check(same && old, "liner sync wave 6: the lights (every client sees them), the reading lights, the landing lights' travel, the ice, the"
                + " frozen airspeeds, the loads round-trip (" + s.length() + " chars); an older string keeps them");
        // the save
        L.lights.emerBat = 1234;
        NBTTagCompound t = new NBTTagCompound();
        L.writeNBT(t);
        LinerSystems R = new LinerSystems();
        R.readNBT(t);
        boolean saved = R.lights.readingOn(7) && R.lights.emerBat == 1234 && R.lights.landR == 1 && SelfTestLinerSVA.near(R.ice.eng[0], 0.4)
                && R.ice.frozenKmh[2] == 280 && R.at(LinerControls.TEMP_CAB) == 5 && R.at(LinerControls.FLOOD_L) == 0;
        LinerSystems N = new LinerSystems();
        N.readNBT(new NBTTagCompound());
        check(saved && N.lights.reading == 0 && N.ice.wing == 0, "liner save wave 6: the reading lights, the emergency batteries, the landing"
                + " lights, the ice, the zone selectors; an old save starts clean");
    }

    private static void lang() throws java.io.IOException {
        Map<String, String> ru = LangKeysSVA.map(LangKeysSVA.RU), en = LangKeysSVA.map(LangKeysSVA.EN);
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (int id = 0; id < LinerEcam.N; id++) {
            keys.add("sva.liner.msg." + LinerEcam.KEYS[id]);
        }
        for (String k : LinerEcam.ACT_KEYS) {
            keys.add("sva.liner.act." + k);
        }
        for (String k : LinerEcam.INOP_KEYS) {
            keys.add("sva.liner.inop." + k);
        }
        for (String k : LinerEcam.LIM_KEYS) {
            keys.add("sva.liner.lim." + k);
        }
        for (String k : LinerSystems.MEMO_KEYS) {
            keys.add("sva.liner.memo." + k);
        }
        for (String k : new String[] {"belts", "noSmoke", "exit"}) {
            keys.add("sva.liner.sign." + k);
        }
        for (String k : new String[] {"ice", "ai", "loads", "load.heat", "load.light", "load.galley", "cond.oat", "cond.cold"}) {
            keys.add("sva.liner.du." + k);
        }
        keys.add("sva.liner.msg.readingPilot");
        StringBuilder miss = new StringBuilder();
        for (String k : keys) {
            if (ru == null || en == null || !ru.containsKey(k) || !en.containsKey(k)) {
                miss.append(' ').append(k);
            }
        }
        boolean actsSplit = true;
        for (String k : LinerEcam.ACT_KEYS) {
            String v = ru == null ? null : ru.get("sva.liner.act." + k);
            actsSplit &= v != null && v.indexOf('|') > 0;
        }
        check(miss.length() == 0 && actsSplit, "liner lang wave 6: the messages, actions, STATUS lines, memos, signs and the pages' words in ru and en"
                + miss);
    }
}
