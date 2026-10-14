package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Liner wave 4 (plan-next §7, mock-ups veh_liner_8 FCU, 11 PFD / FMA, 12 E/WD): the SA-320's auto flight system - the FCU,
 * the autopilots and flight directors, the autothrust and the thrust levers' detents, the flight envelope protections,
 * the ILS tuning, the autoland's flare / roll-out and the callouts - ONE server-authoritative state machine of pure logic
 * (LinerSystems ticks it before the engines, which spool to its thrust; the self-test drives it tick by tick). Clients get
 * its words in the liner's sync string; the physics on either side turns the synced modes into stick commands
 * (guide() -> LinerGuide's laws), so the autopilot flies through the same inputs as the pilot.
 * <p>
 * <b>FCU</b> (the A320's): SPD (km/h, 5 a click, Shift 1), HDG (5°, Shift 1), ALT (Y = metres: the 10 / 50 step selector,
 * Shift 1), V/S (0,5 m/s, Shift 0,1; FPA 0,1°); a knob PUSHED = managed (the window's dashes and the white dot: the speed
 * by the flight's phase / NAV by the route / CLB / DES to the FCU altitude respecting the route's constraint), PULLED =
 * selected (the window's value: the set speed / HDG / OP CLB / OP DES at fixed thrust / V/S); V/S pushed levels off (V/S
 * 0). Turning a managed window shows its value for PREVIEW ticks. AP 1 / AP 2 / A/THR / LOC / APPR / EXPED; HDG-V/S /
 * TRK-FPA; the EFIS: QNH / STD (the altitude shown = Y + (QNH - 1013) x QNH_M), FD, LS, the ND's mode / range / options.
 * <p>
 * <b>Modes</b> (FMA: column 1 thrust, 2 vertical, 3 lateral, 4 approach capability, 5 AP / FD / A/THR; armed modes blue
 * under the active green ones, a changed mode boxed for BOX_TICKS): lateral RWY, RWY TRK, NAV, HDG / TRACK, LOC*, LOC,
 * LAND, ROLL OUT, GA TRK; vertical SRS, CLB, OP CLB, ALT* / ALT (CST), DES, OP DES, V/S / FPA, G/S*, G/S, LAND, FLARE,
 * EXP CLB / DES. Take-off: the levers at FLX / TOGA - MAN FLX / MAN TOGA, SRS (V2 + 10 km/h), RWY; at the acceleration
 * height (vehicles.linerAccelHeight over the runway) SRS -> CLB (OP CLB) and «LVR CLB» flashes until the levers come back
 * to CL (then the A/THR is active: THR CLB / SPEED / THR IDLE). Approaching the FCU altitude: ALT* -> ALT. APPR arms LOC +
 * G/S (blue), LOC* -> LOC, G/S* -> G/S, LAND under LAND_H, FLARE at FLARE_H, ROLL OUT on the ground (the centre line by
 * the nose wheel); «RETARD» at RETARD_H. The levers to TOGA on an approach (flaps out): GO AROUND - SRS / GA TRK, the AP
 * stays.
 * <p>
 * <b>Engagement</b>: an AP needs the INS aligned, NORMAL or ALTN law, AC power, an ADR and an IR (no AP fault) and the
 * aircraft in the air (AP_MIN_H over the ground, or airborne AP_MIN_TICKS); AP 1 + AP 2 together only with the approach
 * armed / captured (CAT 3 DUAL - AUTOLAND; one AP: CAT 1, it lets go at SINGLE_MIN over the ground). Off: the sidestick's
 * red button (instinctive: the «cavalry charge» once, the red «АВТОПИЛОТ ОТКЛ» until pressed again or WARN_TICKS), the
 * FCU's button, an override (a stick key held OVERRIDE_TICKS) or a failure (the aural repeats until the red button).
 * A/THR: armed on the ground by the levers to FLX / TOGA (or its button), active with the levers in IDLE..CL; off by the
 * levers' instinctive button, its FCU button or the levers to IDLE - amber «А/ТЯГА ОТКЛ»; levers under CL in flight -
 * «А/ТЯГА ОГРАНИЧ.»; at take-off thrust on the ground the levers off the detents - «РУД НЕ НА УПОРАХ».
 * <p>
 * <b>Protections</b> (NORMAL law, an ADR valid): alpha floor - under V_AFLOOR in the air (over AFLOOR_MIN_H) the A/THR
 * forces TOGA («A.FLOOR», then «TOGA LK» until its button); overspeed - over VMO a nose-up bias (LinerGuide); the bank
 * protection (LinerGuide). The PFD's bands: VLS (from the configuration's stall and the mass), alpha-prot, alpha-max, VMO.
 * <p>
 * <b>Thrust levers</b> 0 (IDLE) .. CL .. FLX/MCT .. TOGA (1), and the reverse zone (rev 0..1: REV IDLE .. REV MAX - on the
 * ground only, the «Снижение» key at idle or a click; the translating cowls on Blue (engine 1) / Yellow (engine 2) take
 * REV_TICKS to deploy, then the engine spools to REV_N1). The W / S keys stop at each detent for DETENT_HOLD ticks.
 */
public final class LinerAfs {

    // ---------------------------------------------------------------- numbers

    /** FCU limits and steps: speed (km/h), heading (deg), altitude (Y), V/S (tenths of m/s), FPA (tenths of a degree). */
    public static final int SPD_MIN = 70, SPD_MAX = 175, SPD_STEP = 5, HDG_STEP = 5, ALT_MIN = 10, ALT_MAX = 250, VS_MAX = 100, VS_STEP = 5,
            FPA_MAX = 99, FPA_STEP = 1, QNH_MIN = 945, QNH_MAX = 1050;
    /** The ALT selector's steps (blocks): 10 / 50. */
    public static final int[] ALT_STEPS = {10, 50};
    /** The QNH's metres a hectopascal (the altitude shown). */
    public static final double QNH_M = 8.3;
    /** A managed window shows the turned value this long (ticks). */
    public static final int PREVIEW = 60;
    /** The thrust levers' detents (the thrust share: N1 idle..TOGA) and the keys' stop at each. */
    public static final double DET_IDLE = 0, DET_CL = 0.85, DET_FLX = 0.93, DET_TOGA = 1, DET_EPS = 0.012, LEVER_STEP = 0.05;
    public static final int DETENT_HOLD = 6;
    /** The levers' detents in order (the clicks step through them). */
    public static final double[] DETENTS = {DET_IDLE, DET_CL, DET_FLX, DET_TOGA};
    /** The FLEX temperature until the MCDU's PERF page (wave 5) sets one. */
    public static final int FLEX_TEMP = 45;
    /** Reversers: the cowls' travel (ticks), REV MAX's N1 (%), the reverse thrust's share of the forward (efficiency), the lever's step. */
    public static final int REV_TICKS = 40;
    public static final double REV_N1 = 70, REV_EFF = 0.55, REV_STEP = 0.05, REV_IDLE = 0.02;
    /** Heights over the ground (blocks): the AP's least, LAND, FLARE, RETARD, the single AP's limit, alpha floor's floor. */
    public static final double AP_MIN_H = 30, LAND_H = 40, FLARE_H = 10, RETARD_H = 3, SINGLE_MIN = 15, AFLOOR_MIN_H = 30;
    public static final int AP_MIN_TICKS = 100, OVERRIDE_TICKS = 8, WARN_TICKS = 180, CAVALRY = 24, CAVALRY_MIN = 30, BOX_TICKS = 200;
    /** Speeds (b/t): VMO (150 km/h), the managed climb (110) / cruise (125); the bands' factors on the configuration's stall. */
    public static final double VMO = 150 / 72.0, SPD_CLB = 110 / 72.0, SPD_CRZ = 125 / 72.0, K_AMAX = 1.03, K_AFLOOR = 1.07, K_APROT = 1.12,
            K_VLS = 1.18, K_VLS_MASS = 0.08, V_APP_ADD = 7 / 72.0, V_SRS_ADD = 10 / 72.0;
    /** The mass (kg): empty-ish and the full share's range (the PFD's GW: 16 000 + fuel + riders). */
    public static final double MASS0 = 16000, MASS_SPAN = 3400;
    /** A/THR's speed loop: thrust share per b/t, its integral a tick, the integral's limit. */
    public static final double ATHR_P = 3.0, ATHR_I = 0.01, ATHR_I_MAX = 0.3;
    /** The ILS's capture band under the beam (blocks) and the turn radius' speed (b/t) per deg of AP turn rate. */
    public static final double GS_BAND = 1.5;

    // ---------------------------------------------------------------- modes

    public static final int L_NONE = 0, L_RWY = 1, L_RWY_TRK = 2, L_NAV = 3, L_HDG = 4, L_LOC_CAP = 5, L_LOC = 6, L_LAND = 7, L_ROLLOUT = 8,
            L_GA_TRK = 9;
    public static final String[] L_NAMES = {"", "RWY", "RWY TRK", "NAV", "HDG", "LOC*", "LOC", "LAND", "ROLL OUT", "GA TRK"};
    public static final int V_NONE = 0, V_SRS = 1, V_CLB = 2, V_OP_CLB = 3, V_ALT_CAP = 4, V_ALT = 5, V_DES = 6, V_OP_DES = 7, V_VS = 8,
            V_GS_CAP = 9, V_GS = 10, V_LAND = 11, V_FLARE = 12, V_EXP_CLB = 13, V_EXP_DES = 14;
    public static final String[] V_NAMES = {"", "SRS", "CLB", "OP CLB", "ALT*", "ALT", "DES", "OP DES", "V/S", "G/S*", "G/S", "LAND", "FLARE",
            "EXP CLB", "EXP DES"};
    /** Armed bits: lateral NAV / LOC; vertical CLB / DES / ALT / G/S. */
    public static final int AL_NAV = 1, AL_LOC = 2, AV_CLB = 1, AV_DES = 2, AV_ALT = 4, AV_GS = 8;
    public static final int T_NONE = 0, T_MAN_TOGA = 1, T_MAN_FLX = 2, T_MAN_MCT = 3, T_MAN_THR = 4, T_SPEED = 5, T_THR_CLB = 6, T_THR_IDLE = 7,
            T_AFLOOR = 8, T_TOGA_LK = 9, T_RETARD = 10;
    public static final String[] T_NAMES = {"", "MAN TOGA", "MAN FLX", "MAN MCT", "MAN THR", "SPEED", "THR CLB", "THR IDLE", "A.FLOOR", "TOGA LK",
            "RETARD"};
    /** Approach capability (column 4). */
    public static final int CAP_NONE = 0, CAP_CAT1 = 1, CAP_CAT3_DUAL = 2;
    /** Callout codes past the heights: RETARD, V1. */
    public static final int CALL_RETARD = 1000, CALL_V1 = 1001;
    public static final int[] CALLOUTS = {100, 50, 40, 30, 20, 10};
    /** The FMA's message line (lang sva.liner.fma.KEY): none, man pitch trim, flaps (go-around), set the levers. */
    public static final int MSG_NONE = 0, MSG_TRIM = 1, MSG_GA_FLAPS = 2, MSG_DECEL = 3;
    public static final String[] MSG_KEYS = {"", "trim", "gaFlaps", "decel"};

    // ---------------------------------------------------------------- state: the FCU

    public int spd = 130, hdg = 0, alt = 150, vs10, fpa10, qnh = 1013;
    public boolean spdMan = true, hdgMan = true, trk, std;
    /** Turned managed windows show their value this many ticks more (SPD, HDG, V/S). */
    public int spdPrev, hdgPrev, vsPrev;

    // ---------------------------------------------------------------- state: AP / FD / A/THR

    public boolean ap1, ap2, athr;
    public int lat, latArm, ver, verArm, thr;
    /** The lateral mode's held track (RWY TRK / GA TRK, compass), the altitude target is a route constraint (ALT CST). */
    public double latHold;
    public boolean cst, exped;
    /** Ticks since each FMA column changed (the boxes), capped. */
    public int boxThr = BOX_TICKS, boxVer = BOX_TICKS, boxLat = BOX_TICKS, boxCap = BOX_TICKS, boxAp = BOX_TICKS;
    /** AP OFF: the red warning's ticks left (-1: until the red button), the cavalry charge's ticks left (-1 until pressed), its phase. */
    public int apWarn, cavalry, cavPhase;
    /** A/THR OFF caution ticks left (-1 until the button); A/THR LIMITED; levers not set; alpha floor; TOGA LK; LVR CLB. */
    public int athrWarn;
    public boolean athrLimited, leversNotSet, aFloor, togaLk, lvrClb;
    /** The FMA's message (MSG_*), its ticks. */
    public int msg, gaFlapsTicks;
    /** The target speed now (b/t), the altitude target (Y, the FCU's or a constraint), the V/S target (b/t). */
    public double spdT = SPD_CRZ, altT = 150, vsT;
    /** The speed bands (b/t): VLS, alpha-prot, alpha floor, alpha-max; V1 / VR / V2 (0: none yet). */
    public double vls, aProt, aFloorV, aMax, v1, vr, v2;
    /** The thrust levers (0..1), the reverse lever (0..1, > 0: the reverse zone), the cowls (0..1 per engine). */
    public double lever, rev;
    public final double[] revPos = new double[2];
    /** The thrust commanded (share 0..1 of idle..TOGA N1) - the engines spool to it; reversing: REV N1. */
    public double thrCmd;
    /** Callouts: the last one (a height, CALL_*), its sequence number (the PFD flashes on a change). */
    public int call, callSeq;
    /** The ILS tuned (null none), the hand-picked station's key (empty: automatic), the stations near (the «Пульты» list). */
    public LinerIls.Station ils;
    public String ilsChosen = "";
    public final List<LinerIls.Station> ilsNear = new ArrayList<LinerIls.Station>();

    // server-only working values
    private int keyHold, override, airTicks, athrDisArm;
    private double athrI, lastRa = Double.NaN, lastSpeed;
    private boolean retardDone, v1Done, wasGround = true, apIntent;
    /** Sound events this tick (the entity plays them): a callout's code, the cavalry charge's note (-1 none). */
    public int soundCall;
    public int cavNote = -1;

    // ---------------------------------------------------------------- the FCU's clicks

    /** A knob clicked (LinerControls.A_*): push / pull / turn. @return changed */
    public boolean knob(LinerSystems L, LinerControls.Ctl c, int action, Air a) {
        int dir = action == LinerControls.A_WHEEL_UP || action == LinerControls.A_FINE_UP ? 1
                : action == LinerControls.A_WHEEL_DOWN || action == LinerControls.A_FINE_DOWN ? -1 : 0;
        boolean fine = action == LinerControls.A_FINE_UP || action == LinerControls.A_FINE_DOWN;
        boolean push = action == LinerControls.A_PRIMARY, pull = action == LinerControls.A_SECONDARY;
        if (c == LinerControls.FCU_SPD) {
            if (dir != 0) {
                if (spdMan && spdPrev <= 0) {
                    spd = clampI((int) Math.round(spdT * 72), SPD_MIN, SPD_MAX);
                }
                spd = clampI(spd + dir * (fine ? 1 : SPD_STEP), SPD_MIN, SPD_MAX);
                if (spdMan) {
                    spdPrev = PREVIEW;
                }
                return true;
            }
            if (push) {
                spdMan = true;
                spdPrev = 0;
                return true;
            }
            if (pull) {
                if (spdMan && spdPrev <= 0) {
                    spd = clampI((int) Math.round(spdT * 72), SPD_MIN, SPD_MAX);   // the A320 syncs to the speed flown
                }
                spdMan = false;
                spdPrev = 0;
                return true;
            }
            return false;
        }
        if (c == LinerControls.FCU_HDG) {
            if (dir != 0) {
                if (hdgMan && hdgPrev <= 0 && lat != L_HDG) {
                    hdg = (int) Math.round(a == null ? hdg : a.hdg) % 360;
                }
                hdg = ((hdg + dir * (fine ? 1 : HDG_STEP)) % 360 + 360) % 360;
                if (lat != L_HDG) {
                    hdgPrev = PREVIEW;
                }
                return true;
            }
            if (push) {
                hdgMan = true;
                hdgPrev = 0;
                if (a != null && a.hasRoute && !a.ground && lat != L_LOC && lat != L_LAND && lat != L_ROLLOUT) {
                    setLat(L_NAV);
                } else {
                    latArm |= AL_NAV;                               // NAV armed: it engages with a route, in the air
                }
                return true;
            }
            if (pull) {
                if (hdgMan && hdgPrev <= 0 && lat != L_HDG && a != null) {
                    hdg = (int) Math.round(a.hdg) % 360;
                }
                hdgMan = false;
                hdgPrev = 0;
                latArm &= ~(AL_NAV | AL_LOC);
                verArm &= ~AV_GS;
                if (a != null && !a.ground && fdOrAp(L)) {
                    setLat(L_HDG);
                }
                return true;
            }
            return false;
        }
        if (c == LinerControls.FCU_ALT) {
            if (dir != 0) {
                int step = fine ? 1 : ALT_STEPS[Math.max(0, Math.min(1, L.at(LinerControls.FCU_ALT_STEP)))];
                alt = clampI(dir > 0 ? (alt / step + 1) * step : ((alt + step - 1) / step - 1) * step, ALT_MIN, ALT_MAX);
                return true;
            }
            if (a == null) {
                return push || pull;
            }
            double y = a.y;
            if (a.ground) {
                if (push && alt > y + 2) {
                    verArm |= AV_CLB;                               // CLB armed for the take-off
                }
                return push || pull;
            }
            if (push || pull) {
                exped = false;
                if (alt > y + 2) {
                    setVer(push ? V_CLB : V_OP_CLB);
                } else if (alt < y - 2) {
                    setVer(push ? V_DES : V_OP_DES);
                }
                return true;
            }
            return false;
        }
        if (c == LinerControls.FCU_VS) {
            if (dir != 0) {
                if (trk) {
                    fpa10 = clampI(fpa10 + dir * FPA_STEP, -FPA_MAX, FPA_MAX);
                } else {
                    vs10 = clampI(vs10 + dir * (fine ? 1 : VS_STEP), -VS_MAX, VS_MAX);
                }
                if (ver != V_VS) {
                    vsPrev = PREVIEW;
                }
                return true;
            }
            if (a == null || a.ground) {
                return push || pull;
            }
            if (pull) {
                if (vsPrev <= 0 && ver != V_VS) {
                    vs10 = clampI((int) Math.round(a.vy * 200), -VS_MAX, VS_MAX);   // b/t -> tenths of m/s: the rate flown
                    fpa10 = clampI((int) Math.round(Math.toDegrees(Math.atan2(a.vy, Math.max(0.1, a.speed))) * 10), -FPA_MAX, FPA_MAX);
                }
                exped = false;
                setVer(V_VS);
                vsPrev = 0;
                return true;
            }
            if (push) {
                vs10 = 0;                                           // the A320: pushed - level off
                fpa10 = 0;
                exped = false;
                setVer(V_VS);
                vsPrev = 0;
                return true;
            }
            return false;
        }
        if (c == LinerControls.BARO_L || c == LinerControls.BARO_R) {
            if (dir != 0) {
                qnh = clampI(qnh + dir, QNH_MIN, QNH_MAX);
                return true;
            }
            if (pull) {
                std = true;
                return true;
            }
            if (push) {
                std = false;
                return true;
            }
        }
        return false;
    }

    /** A momentary FCU / pedestal button pressed. @return handled */
    public boolean press(LinerSystems L, LinerControls.Ctl c, Air a) {
        if (c == LinerControls.FCU_AP1 || c == LinerControls.FCU_AP2) {
            boolean one = c == LinerControls.FCU_AP1;
            boolean on = one ? ap1 : ap2;
            if (on) {
                apOff(false);                                       // the FCU's button: not instinctive - the aural repeats
                return true;
            }
            if (a == null || !apAvailable(L, a)) {
                return true;
            }
            boolean both = approachArmedOrOn();
            if (one) {
                ap1 = true;
                ap2 = ap2 && both;
            } else {
                ap2 = true;
                ap1 = ap1 && both;
            }
            apWarn = 0;
            cavalry = 0;
            boxAp = 0;
            engageDefaults(a);
            return true;
        }
        if (c == LinerControls.FCU_ATHR) {
            if (athr) {
                athrOff(false);
            } else if (a != null && L.anyRunning()) {
                athr = true;
                athrWarn = 0;
                boxAp = 0;
            }
            return true;
        }
        if (c == LinerControls.FCU_LOC) {
            if (lat == L_LOC_CAP || lat == L_LOC) {
                revertLateral(a);
            } else if ((latArm & AL_LOC) != 0 && (verArm & AV_GS) == 0) {
                latArm &= ~AL_LOC;
            } else {
                latArm |= AL_LOC;
                verArm &= ~AV_GS;
                dropSecondAp();
            }
            return true;
        }
        if (c == LinerControls.FCU_APPR) {
            boolean armed = (latArm & AL_LOC) != 0 && (verArm & AV_GS) != 0;
            if (ver == V_GS || ver == V_GS_CAP || ver == V_LAND || ver == V_FLARE) {
                revertLateral(a);
                revertVertical(a);
            } else if (armed) {
                latArm &= ~AL_LOC;
                verArm &= ~AV_GS;
                dropSecondAp();
            } else {
                latArm |= AL_LOC;
                verArm |= AV_GS;
            }
            boxCap = 0;
            return true;
        }
        if (c == LinerControls.FCU_EXPED) {
            if (exped) {
                exped = false;
                if (ver == V_EXP_CLB) {
                    setVer(V_OP_CLB);
                } else if (ver == V_EXP_DES) {
                    setVer(V_OP_DES);
                }
            } else if (a != null && !a.ground) {
                if (alt > a.y + 2) {
                    exped = true;
                    setVer(V_EXP_CLB);
                } else if (alt < a.y - 2) {
                    exped = true;
                    setVer(V_EXP_DES);
                }
            }
            return true;
        }
        if (c == LinerControls.FCU_TRK) {
            trk = !trk;
            return true;
        }
        if (c == LinerControls.AP_DISC_L || c == LinerControls.AP_DISC_R) {
            apDisc();
            return true;
        }
        if (c == LinerControls.ATHR_DISC_L || c == LinerControls.ATHR_DISC_R) {
            athrDisc();
            return true;
        }
        return false;
    }

    /** The sidestick's red button (the key, the 3D button): AP off (instinctive), or - with the warning up - the warning cancelled. */
    public void apDisc() {
        if (ap1 || ap2) {
            apOff(true);
        } else {
            apWarn = 0;
            cavalry = 0;                                            // the second press: the warning and the aural off
        }
    }

    /** The levers' instinctive button: A/THR off (alpha floor's TOGA LK too), or - its caution up - cancelled. */
    public void athrDisc() {
        if (athr || aFloor || togaLk) {
            athrOff(true);
        } else {
            athrWarn = 0;
        }
    }

    void apOff(boolean instinctive) {
        if (!ap1 && !ap2) {
            return;
        }
        ap1 = ap2 = false;
        boxAp = 0;
        apIntent = instinctive;
        apWarn = instinctive ? WARN_TICKS : -1;
        cavalry = instinctive ? CAVALRY_MIN : -1;
        cavPhase = 0;
        override = 0;
    }

    void athrOff(boolean instinctive) {
        boolean was = athr || aFloor || togaLk;
        athr = false;
        aFloor = false;
        togaLk = false;
        athrI = 0;
        boxAp = 0;
        if (was) {
            athrWarn = instinctive ? WARN_TICKS : -1;
        }
    }

    /** Only one AP once the approach is no longer armed / captured. */
    private void dropSecondAp() {
        if (ap1 && ap2 && !approachArmedOrOn()) {
            ap2 = false;
            boxAp = 0;
        }
    }

    public boolean approachArmedOrOn() {
        return (latArm & AL_LOC) != 0 || (verArm & AV_GS) != 0 || lat == L_LOC_CAP || lat == L_LOC || lat == L_LAND || lat == L_ROLLOUT
                || ver == V_GS || ver == V_GS_CAP || ver == V_LAND || ver == V_FLARE;
    }

    public boolean apOn() {
        return ap1 || ap2;
    }

    static boolean fdOrAp(LinerSystems L) {
        return L.on(LinerControls.FD_L) || L.on(LinerControls.FD_R) || L.afs.apOn();
    }

    /** An AP may engage: INS aligned, NORMAL / ALTN law, AC, an ADR and an IR, in the air. */
    public boolean apAvailable(LinerSystems L, Air a) {
        return a.insReady && L.fctl.law != LinerFctl.DIRECT && L.acPowered() && L.adrCount() > 0 && L.irCount() > 0 && !a.ground
                && (a.ra >= AP_MIN_H || airTicks >= AP_MIN_TICKS);
    }

    /** An AP's failure (it drops off with the aural) - the conditions that keep one engaged (the height once flying is no matter). */
    boolean apFault(LinerSystems L, Air a) {
        return !a.insReady || L.fctl.law == LinerFctl.DIRECT || !L.acPowered() || L.adrCount() == 0 || L.irCount() == 0;
    }

    /** Engaging an AP (or the FD in the air) without modes: HDG (the heading flown) and V/S (the rate flown) - or ALT when level. */
    void engageDefaults(Air a) {
        if (lat == L_NONE || lat == L_RWY) {
            if (lat == L_RWY) {
                latHold = a.hdg;
                setLat(L_RWY_TRK);
            } else {
                hdg = (int) Math.round(a.hdg) % 360;
                hdgMan = false;
                setLat(L_HDG);
            }
        }
        if (ver == V_NONE) {
            if (Math.abs(a.vy) < 0.02) {
                altT = a.y;
                setVer(V_ALT);
            } else {
                vs10 = clampI((int) Math.round(a.vy * 200), -VS_MAX, VS_MAX);
                setVer(V_VS);
            }
        }
    }

    void revertLateral(Air a) {
        latArm &= ~AL_LOC;
        if (a != null) {
            hdg = (int) Math.round(a.hdg) % 360;
        }
        hdgMan = false;
        if (lat != L_NONE) {
            setLat(L_HDG);
        }
        dropSecondAp();
    }

    void revertVertical(Air a) {
        verArm &= ~AV_GS;
        if (a != null) {
            vs10 = clampI((int) Math.round(a.vy * 200), -VS_MAX, VS_MAX);
        }
        if (ver != V_NONE) {
            setVer(V_VS);
        }
        dropSecondAp();
    }

    void setLat(int m) {
        if (lat != m) {
            lat = m;
            boxLat = 0;
        }
        if (m == L_NAV) {
            latArm &= ~AL_NAV;
        }
    }

    void setVer(int m) {
        if (ver != m) {
            ver = m;
            boxVer = 0;
        }
        if (m == V_CLB || m == V_OP_CLB || m == V_EXP_CLB) {
            verArm &= ~AV_CLB;
        }
        if (m == V_DES || m == V_OP_DES || m == V_EXP_DES) {
            verArm &= ~AV_DES;
        }
    }

    void setThr(int m) {
        if (thr != m) {
            thr = m;
            boxThr = 0;
        }
    }

    // ---------------------------------------------------------------- the aircraft (the AFS' inputs)

    /** What the AFS knows of the aircraft this tick (the server's entity; the self-test's numbers). */
    public static final class Air {
        /** Position, compass heading (= track: no wind), vertical speed (b/t), airspeed (b/t), height over the ground (blocks). */
        public double x, y, z, hdg, vy, speed, ra = 250;
        public boolean ground = true;
        /** The configuration's stall / lift-off speeds, the drag factor, the thrust constant, the top speed, climb / dive (b/t). */
        public double stall = 1.15, liftOff = 1.3, drag = 1, thrustK = 0.012, top = 2.0, cap = 0.3, dive = 0.45;
        /** The gross mass (kg). */
        public double mass = 17500;
        public boolean insReady = true;
        /** A stick key held (the override), the levers' keys (W / S), the reverse key («Снижение»). */
        public boolean stick, leverUp, leverDown, revKey;
        /** NAV: a route, its wanted heading (NaN none) and the active point's altitude (NaN none). */
        public boolean hasRoute;
        public double routeHdg = Double.NaN, routeAlt = Double.NaN;
        /** The ILS stations near (the world data's), the range (blocks); the acceleration / thrust reduction height (blocks). */
        public List<LinerIls.Station> ils;
        public double ilsRange = 4000, accelH = 30;
        /** The callouts on (config). */
        public boolean callouts = true;
    }

    // ---------------------------------------------------------------- the levers

    /**
     * One tick of the thrust lever keys (the server; both levers together): W up / S down at LEVER_STEP a tick, stopping at
     * each detent for DETENT_HOLD ticks; on the ground at idle «Снижение» pulls into the reverse (REV IDLE .. MAX), W brings
     * it back first. In the air the reverse is locked out.
     */
    public void leverKeys(boolean up, boolean down, boolean revKey, boolean ground) {
        if (!ground) {
            rev = 0;
        }
        if (rev > 0 || ground && revKey && lever <= DET_EPS && !up) {
            lever = 0;
            if (up && !revKey) {
                rev = Math.max(0, rev - REV_STEP);
                keyHold = 0;
            } else if (revKey && ground) {
                rev = Math.min(1, Math.max(REV_IDLE, rev + REV_STEP));
            }
            return;
        }
        if (up == down) {
            keyHold = 0;
            return;
        }
        double next = lever + (up ? LEVER_STEP : -LEVER_STEP);
        for (double d : DETENTS) {
            boolean crosses = up ? lever < d - 1e-9 && next >= d - 1e-9 : lever > d + 1e-9 && next <= d + 1e-9;
            if (crosses) {
                next = d;                                           // stop at the detent
                break;
            }
        }
        boolean atDetent = false;
        for (double d : DETENTS) {
            atDetent |= Math.abs(lever - d) < 1e-9;
        }
        if (atDetent && keyHold < DETENT_HOLD && lever > 0 && lever < 1) {
            keyHold++;
            return;                                                 // the gate: hold the key a moment to pass
        }
        keyHold = atDetent ? 0 : keyHold;
        lever = Math.max(0, Math.min(1, next));
        if (!atDetent) {
            keyHold = 0;
        }
    }

    /** A thrust lever clicked: LMB the next detent up (out of the reverse first), RMB the next down (on the ground into REV IDLE, REV MAX). */
    public void leverClick(boolean upward, boolean ground) {
        if (upward) {
            if (rev > 0) {
                rev = rev > REV_IDLE + 1e-9 ? REV_IDLE : 0;
                return;
            }
            for (double d : DETENTS) {
                if (d > lever + DET_EPS) {
                    lever = d;
                    return;
                }
            }
            return;
        }
        if (lever <= DET_EPS) {
            if (ground) {
                rev = rev <= 0 ? REV_IDLE : 1;
            }
            return;
        }
        double best = 0;
        for (double d : DETENTS) {
            if (d < lever - DET_EPS) {
                best = d;
            }
        }
        lever = best;
    }

    /** The detent the levers stand in (DETENTS index) or -1 between. */
    public static int detent(double lever) {
        for (int i = 0; i < DETENTS.length; i++) {
            if (Math.abs(lever - DETENTS[i]) <= DET_EPS) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- speeds

    /** The mass' share (0 light .. 1 full). */
    public static double massShare(double mass) {
        return Math.max(0, Math.min(1, (mass - MASS0) / MASS_SPAN));
    }

    /** VLS for this configuration's stall and mass. */
    public static double vls(double stall, double mass) {
        return stall * (K_VLS + K_VLS_MASS * massShare(mass));
    }

    /** V-speeds {V1, VR, V2} (b/t) from the take-off configuration's lift-off / stall and the mass. */
    public static double[] vspeeds(double liftOff, double stall, double mass) {
        double vR = liftOff * (1.0 + 0.04 * massShare(mass));
        double vOne = vR * 0.95;
        double vTwo = Math.max(vR * 1.06, stall * 1.2);
        return new double[] {vOne, vR, vTwo};
    }

    /** The managed speed now (b/t): SRS V2 + 10, the approach VLS + 7, climb / cruise by the phase, within VLS + 5 .. VMO - 7. */
    public double managedSpeed(LinerSystems L, Air a) {
        double floor = vls + 5 / 72.0, ceil = VMO - 7 / 72.0;
        int flaps = L.fctl.flapSetting();
        if (flaps >= 1) {
            ceil = Math.min(ceil, HighLiftMath.LINER.maxSpeed[Math.max(1, Math.min(4, flaps))] - 5 / 72.0);
        }
        double t;
        if (ver == V_SRS) {
            t = Math.max(v2 > 0 ? v2 + V_SRS_ADD : floor, gaSpeed);
        } else if (flaps >= 1 || approachArmedOrOn()) {
            t = vls + V_APP_ADD;
        } else if (ver == V_CLB || ver == V_OP_CLB || ver == V_EXP_CLB || ver == V_SRS) {
            t = ver == V_EXP_CLB ? floor + 5 / 72.0 : SPD_CLB;
        } else if (ver == V_EXP_DES) {
            t = ceil;
        } else {
            t = SPD_CRZ;
        }
        return Math.max(floor, Math.min(ceil, t));
    }

    private double gaSpeed, gaAccelH;

    // ---------------------------------------------------------------- the tick

    /** One server tick (LinerSystems.tick, before the engines): everything above. `a` the aircraft. */
    public void tick(LinerSystems L, LinerSystems.Env e, Air a) {
        soundCall = 0;
        cavNote = -1;
        boolean fd = L.on(LinerControls.FD_L) || L.on(LinerControls.FD_R);
        // ---- timers
        boxThr = Math.min(BOX_TICKS, boxThr + 1);
        boxVer = Math.min(BOX_TICKS, boxVer + 1);
        boxLat = Math.min(BOX_TICKS, boxLat + 1);
        boxCap = Math.min(BOX_TICKS, boxCap + 1);
        boxAp = Math.min(BOX_TICKS, boxAp + 1);
        spdPrev = Math.max(0, spdPrev - 1);
        hdgPrev = Math.max(0, hdgPrev - 1);
        vsPrev = Math.max(0, vsPrev - 1);
        airTicks = a.ground ? 0 : airTicks + 1;
        if (a.ground) {
            gaAccelH = 0;
        }
        // ---- speeds
        vls = vls(a.stall, a.mass);
        aMax = a.stall * K_AMAX;
        aFloorV = a.stall * K_AFLOOR;
        aProt = a.stall * K_APROT;
        if (a.ground && a.speed < 0.3 && ver != V_SRS) {
            double[] vs = vspeeds(a.liftOff, a.stall, a.mass);
            v1 = vs[0];
            vr = vs[1];
            v2 = vs[2];
            v1Done = false;
        }
        // ---- the ILS tuned
        tuneIls(a);
        // ---- AP: failures, the override
        if (apOn() && apFault(L, a)) {
            apOff(false);
        }
        if (apOn() && a.stick) {
            if (++override >= OVERRIDE_TICKS) {
                apOff(false);
            }
        } else {
            override = 0;
        }
        if (apOn() && a.ground && lat != L_ROLLOUT && lat != L_LAND && ver != V_FLARE && ver != V_LAND) {
            apOff(false);
        }
        targets(L, a);
        // ---- modes
        boolean modes = fd || apOn();
        if (a.ground) {
            groundModes(L, a, modes);
        } else {
            airModes(L, a, modes);
        }
        if (!modes && lat != L_ROLLOUT) {
            lat = L_NONE;
            ver = V_NONE;
            latArm = 0;
            verArm = 0;
        }
        // single AP: CAT 1 - it lets go over the runway
        if (!a.ground && apOn() && !(ap1 && ap2) && (lat == L_LAND || ver == V_FLARE || ver == V_GS && a.ra < SINGLE_MIN) && a.ra < SINGLE_MIN) {
            apOff(false);
        }
        targets(L, a);                                              // again with the modes as they now are
        // ---- thrust
        athrTick(L, a);
        leverChecks(L, a);
        // ---- callouts, aurals, warnings' timers
        callouts(L, a);
        if (apWarn > 0) {
            apWarn--;
        }
        if (athrWarn > 0) {
            athrWarn--;
        }
        if (cavalry != 0) {
            cavNote = cavPhase % CAVALRY;
            cavPhase++;
            if (cavalry > 0) {
                cavalry--;
            }
        } else {
            cavPhase = 0;
        }
        msg = L.fctl.law == LinerFctl.DIRECT && !a.ground ? MSG_TRIM : gaFlapsTicks > 0 ? MSG_GA_FLAPS : MSG_NONE;
        if (gaFlapsTicks > 0 && L.fctl.flapSetting() <= 2) {
            gaFlapsTicks = 0;
        }
        gaFlapsTicks = Math.max(0, gaFlapsTicks - 1);
        // ---- reversers: the cowls on Blue (1) / Yellow (2), on the ground with the reverse selected
        for (int i = 0; i < 2; i++) {
            boolean hydOk = L.hyd.powered(i == 0 ? LinerHyd.B : LinerHyd.Y);
            boolean want = rev > 0 && a.ground && L.eng[i].running();
            if (hydOk || e.instantHyd) {
                revPos[i] = Math.max(0, Math.min(1, revPos[i] + (want ? 1.0 : -1.0) / REV_TICKS));
            }
        }
        wasGround = a.ground;
        lastSpeed = a.speed;
    }


    /** The targets now: the speed (managed / selected), the altitude (the FCU's or a route constraint - ALT CST), the V/S (FPA). */
    private void targets(LinerSystems L, Air a) {
        spdT = spdMan ? managedSpeed(L, a) : spd / 72.0;
        if (ver != V_ALT && ver != V_ALT_CAP) {
            double fcuAlt = indicatedToY(alt);
            altT = fcuAlt;
            cst = false;
            boolean managed = ver == V_CLB || ver == V_DES || verArm != 0 && hdgMan;
            if (managed && !Double.isNaN(a.routeAlt)) {
                double c = a.routeAlt;
                if (c > a.y + 1 && c < fcuAlt || c < a.y - 1 && c > fcuAlt) {
                    altT = c;                                       // a route constraint before the FCU altitude: ALT CST
                    cst = true;
                }
            }
        }
        vsT = trk ? Math.tan(Math.toRadians(fpa10 / 10.0)) * Math.max(0.3, a.speed) : vs10 / 200.0;
    }

    /** The altitude shown (QNH: + the pressure's metres; STD: Y) and its inverse. */
    public double shownAlt(double y) {
        return std ? y : y + (qnh - 1013) * QNH_M;
    }

    public double indicatedToY(double ind) {
        return std ? ind : ind - (qnh - 1013) * QNH_M;
    }

    private void groundModes(LinerSystems L, Air a, boolean modes) {
        int det = detent(lever);
        boolean toThrust = (det == 2 || det == 3) && L.anyRunning();
        if (lat == L_ROLLOUT || lat == L_LAND || ver == V_FLARE || ver == V_LAND) {
            // the touch-down: ROLL OUT along the centre line until the AP / FD let go and it stops
            if (lat != L_ROLLOUT) {
                setLat(L_ROLLOUT);
                setVer(V_NONE);
            }
            if (a.speed < 0.05 && !apOn()) {
                setLat(L_NONE);
            }
            if (!modes && a.speed < 0.05) {
                setLat(L_NONE);
            }
            return;
        }
        if (!modes) {
            return;
        }
        if (toThrust) {
            if (ver != V_SRS) {
                setVer(V_SRS);
                gaSpeed = 0;
            }
            if (lat != L_RWY) {
                latHold = a.hdg;
                setLat(L_RWY);
            }
            if (athrAvail(L) && !athr) {
                athr = true;                                        // the levers to FLX / TOGA arm the A/THR
                boxAp = 0;
            }
            if (hdgMan && a.hasRoute) {
                latArm |= AL_NAV;
            }
            if (indicatedToY(alt) > a.y + 2) {
                verArm |= AV_CLB;
            }
        } else if (a.speed < 0.3) {
            if (ver == V_SRS) {
                setVer(V_NONE);
            }
            if (lat == L_RWY) {
                setLat(L_NONE);
            }
        }
    }

    private void airModes(LinerSystems L, Air a, boolean modes) {
        if (!modes || lat == L_ROLLOUT) {
            return;                                                 // ROLL OUT: a bounce / the last inches - the guide keeps it sinking
        }
        // the lift-off: RWY -> RWY TRK; nothing yet (an FD switched on in the air): HDG + V/S
        if (lat == L_RWY) {
            latHold = a.hdg;
            setLat(L_RWY_TRK);
        }
        if (lat == L_NONE || ver == V_NONE) {
            engageDefaults(a);
        }
        // go-around: the levers to TOGA with the flaps out on an approach (G/S, LOC, LAND, FLARE, or low with the gear down)
        boolean onApproach = ver == V_GS || ver == V_GS_CAP || ver == V_LAND || ver == V_FLARE || lat == L_LOC || lat == L_LOC_CAP || lat == L_LAND;
        if (detent(lever) == 3 && L.fctl.flapSetting() >= 1 && onApproach && ver != V_SRS) {
            gaSpeed = Math.max(a.speed, vls + V_APP_ADD);
            gaAccelH = a.ra + a.accelH;
            setVer(V_SRS);
            latHold = a.hdg;
            setLat(L_GA_TRK);
            latArm &= ~AL_LOC;
            verArm = AV_CLB;
            if (hdgMan && a.hasRoute) {
                latArm |= AL_NAV;
            }
            gaFlapsTicks = 300;
            retardDone = false;
            boxCap = 0;
        }
        // SRS -> CLB / OP CLB at the acceleration height (a go-around: that much over where it began)
        if (ver == V_SRS && a.ra >= Math.max(a.accelH, gaAccelH)) {
            double target = indicatedToY(alt);
            if (target > a.y + 2) {
                setVer((verArm & AV_CLB) != 0 || hdgMan ? V_CLB : V_OP_CLB);
            } else {
                altT = target;
                setVer(V_ALT_CAP);
            }
        }
        // NAV engages once airborne over 10 blocks with a route
        if ((latArm & AL_NAV) != 0 && a.hasRoute && a.ra > 10 && lat != L_LOC && lat != L_LOC_CAP && lat != L_LAND) {
            setLat(L_NAV);
        }
        if (lat == L_NAV && !a.hasRoute) {
            hdg = (int) Math.round(a.hdg) % 360;
            hdgMan = false;
            setLat(L_HDG);
        }
        // ALT armed whenever the FCU altitude lies ahead of a climb / descent mode; ALT* on the capture distance
        boolean climbing = ver == V_CLB || ver == V_OP_CLB || ver == V_EXP_CLB || ver == V_VS && vsT > 0.005;
        boolean descending = ver == V_DES || ver == V_OP_DES || ver == V_EXP_DES || ver == V_VS && vsT < -0.005;
        double err = altT - a.y;
        if (climbing && err > 0.3 || descending && err < -0.3) {
            verArm |= AV_ALT;
            if (Math.abs(err) <= LinerGuide.captureDistance(Math.abs(a.vy)) && Math.signum(a.vy) == Math.signum(err)) {
                setVer(V_ALT_CAP);
                exped = false;
            }
        } else {
            verArm &= ~AV_ALT;
            if ((ver == V_CLB || ver == V_OP_CLB || ver == V_EXP_CLB) && err < -0.3 || (ver == V_DES || ver == V_OP_DES || ver == V_EXP_DES) && err > 0.3) {
                setVer(V_ALT_CAP);                                  // the FCU altitude set behind: level off where it is
            }
        }
        if (ver == V_ALT_CAP) {
            verArm &= ~AV_ALT;
            if (Math.abs(err) < 0.6 && Math.abs(a.vy) < 0.03) {
                setVer(V_ALT);
            }
        }
        if (ver == V_ALT && cst && !Double.isNaN(a.routeAlt) && Math.abs(a.routeAlt - altT) > 1) {
            // ALT CST: the route's next constraint (or none in the way) - managed CLB / DES on to the FCU altitude
            cst = false;
            double target = indicatedToY(alt);
            if (target > a.y + 2) {
                setVer(V_CLB);
            } else if (target < a.y - 2) {
                setVer(V_DES);
            }
        }
        if (ver == V_ALT && (verArm & AV_CLB) != 0 && indicatedToY(alt) > a.y + 2 && lat != L_GA_TRK) {
            verArm &= ~AV_CLB;
        }
        // LOC / G/S
        LinerIls.Dev d = ils == null ? null : LinerIls.dev(ils, a.x, a.y, a.z, a.ilsRange);
        if ((latArm & AL_LOC) != 0 && d != null) {
            double rate = LinerGuide.turnRate(LinerGuide.AP_BANK, a.speed, a.stall, 1.1);
            double radius = RouteMath.turnRadius(a.speed, Math.max(0.05, rate));
            if (LinerIls.locCapture(d, ils, a.hdg, radius, a.speed)) {
                latArm &= ~(AL_LOC | AL_NAV);
                setLat(L_LOC_CAP);
            }
        }
        if (lat == L_LOC_CAP && d != null && Math.abs(d.cross) < 1.5 && Math.abs(CruiseMath.wrap(a.hdg - ils.course)) < 6) {
            setLat(L_LOC);
        }
        if ((lat == L_LOC_CAP || lat == L_LOC || lat == L_LAND) && (d == null || !d.locValid && d.along < -50)) {
            revertLateral(a);                                       // the beam lost
        }
        if ((verArm & AV_GS) != 0 && d != null && (lat == L_LOC_CAP || lat == L_LOC) && LinerIls.gsCapture(d, GS_BAND)) {
            verArm &= ~(AV_GS | AV_ALT | AV_CLB | AV_DES);
            setVer(V_GS_CAP);
        }
        if (ver == V_GS_CAP && d != null && Math.abs(d.above) < 0.5 && boxVer > 20) {
            setVer(V_GS);
        }
        if ((ver == V_GS || ver == V_GS_CAP) && d != null && !d.gsValid && d.along < -30) {
            revertVertical(a);
        }
        // LAND, FLARE
        if (lat == L_LOC && ver == V_GS && a.ra < LAND_H) {
            setLat(L_LAND);
            setVer(V_LAND);
        }
        if ((ver == V_LAND || ver == V_GS && a.ra < FLARE_H) && a.ra <= FLARE_H) {
            setVer(V_FLARE);
            if (lat == L_LOC) {
                setLat(L_LAND);
            }
        }
    }

    /** The A/THR may work: AC, an ADR, the engines running. */
    static boolean athrAvail(LinerSystems L) {
        return L.acPowered() && L.adrCount() > 0 && L.anyRunning();
    }

    private void athrTick(LinerSystems L, Air a) {
        int det = detent(lever);
        boolean landing = ver == V_FLARE || lat == L_ROLLOUT || ver == V_LAND && a.ra < RETARD_H * 2;
        if (athr && !athrAvail(L)) {
            athrOff(false);
        }
        // alpha floor (NORMAL law, an ADR, in the air over AFLOOR_MIN_H, not on the landing's last part)
        boolean prot = L.fctl.law == LinerFctl.NORMAL && L.adrCount() >= 1 && !a.ground && a.ra >= AFLOOR_MIN_H && L.anyRunning() && !landing;
        if (prot && a.speed < aFloorV) {
            if (!aFloor) {
                boxThr = 0;
            }
            aFloor = true;
            athr = true;                                            // alpha floor engages the A/THR whatever its state
        } else if (aFloor && (a.speed > aFloorV + 0.05 || !prot)) {
            aFloor = false;
            togaLk = true;                                          // TOGA LK until the instinctive button
        }
        // the levers to idle: A/THR off (silent on the landing)
        if (athr && det == 0 && rev <= 0 && !aFloor && !togaLk) {
            boolean silent = landing || a.ground;
            athr = false;
            athrI = 0;
            boxAp = 0;
            if (!silent) {
                athrWarn = -1;
            }
        }
        double leverThr = lever;
        lvrClb = false;
        int mode;
        double cmd;
        if (aFloor || togaLk) {
            cmd = 1;
            mode = aFloor ? T_AFLOOR : T_TOGA_LK;
        } else if (!athr) {
            cmd = leverThr;
            mode = det == 3 ? T_MAN_TOGA : det == 2 ? (a.ground || ver == V_SRS && lat == L_RWY_TRK ? T_MAN_FLX : T_MAN_MCT) : T_NONE;
        } else if (lever > DET_CL + DET_EPS) {
            cmd = leverThr;                                         // above CL: manual thrust, the A/THR armed (blue)
            mode = det == 3 ? T_MAN_TOGA : det == 2 ? (a.ground || ver == V_SRS && lat == L_RWY_TRK ? T_MAN_FLX : T_MAN_MCT) : T_MAN_THR;
            lvrClb = !a.ground && a.ra >= a.accelH && (det == 2 || det == 3);
        } else {
            double limit = leverThr;
            if (ver == V_FLARE && a.ra <= RETARD_H || lat == L_ROLLOUT) {
                cmd = 0;
                mode = T_RETARD;
            } else if (ver == V_SRS || ver == V_CLB || ver == V_OP_CLB || ver == V_EXP_CLB) {
                cmd = limit;
                mode = T_THR_CLB;
            } else if (ver == V_DES || ver == V_OP_DES || ver == V_EXP_DES) {
                cmd = 0;
                mode = T_THR_IDLE;
            } else {
                // SPEED: the feed-forward of the drag and the climb's toll, a PI on the speed error
                double r = a.speed / Math.max(1e-6, a.top);
                double ff = a.drag * r * r + a.vy * LinerGuide.CLIMB_DRAG / Math.max(1e-6, a.thrustK);
                double ev = spdT - a.speed;
                double raw = ff + ATHR_P * ev + athrI;
                if (raw > 0 && raw < limit || raw >= limit && ev < 0 || raw <= 0 && ev > 0) {
                    athrI = Math.max(-ATHR_I_MAX, Math.min(ATHR_I_MAX, athrI + ATHR_I * ev));   // no wind-up while saturated
                }
                cmd = Math.max(0, Math.min(limit, ff + ATHR_P * ev + athrI));
                mode = T_SPEED;
            }
        }
        setThr(mode);
        thrCmd = Math.max(0, Math.min(1, cmd));
    }

    private void leverChecks(LinerSystems L, Air a) {
        int det = detent(lever);
        boolean active = athr && !aFloor && !togaLk && lever <= DET_CL + DET_EPS;
        athrLimited = active && !a.ground && det != 1 && lever > DET_IDLE + DET_EPS;
        leversNotSet = a.ground && L.anyRunning() && lever >= LinerFctl.TO_THR && det != 2 && det != 3;
    }

    /** The ECAM's: A/THR active (the levers in its range, not a protection). */
    public boolean athrActive() {
        return athr && !aFloor && !togaLk && lever <= DET_CL + DET_EPS && lever > DET_IDLE + DET_EPS;
    }

    private void callouts(LinerSystems L, Air a) {
        double was = lastRa;
        lastRa = a.ra;
        if (!a.ground && !Double.isNaN(was) && a.ra < was && L.gear.downLocked()) {
            for (int c : CALLOUTS) {
                if (was > c && a.ra <= c) {
                    call(c, a);
                }
            }
            // RETARD: the autoland's flare (or a manual landing with the levers still up) through RETARD_H
            if (!retardDone && was > RETARD_H && a.ra <= RETARD_H && (ver == V_FLARE || lever > DET_IDLE + DET_EPS && L.fctl.flapSetting() >= 3)) {
                retardDone = true;
                call(CALL_RETARD, a);
            }
        }
        if (!a.ground && a.ra > FLARE_H * 2) {
            retardDone = false;
        }
        // V1 on the take-off roll
        if (a.ground && v1 > 0 && !v1Done && lastSpeed < v1 && a.speed >= v1 && lever >= LinerFctl.TO_THR) {
            v1Done = true;
            call(CALL_V1, a);
        }
    }

    private void call(int c, Air a) {
        call = c;
        callSeq = (callSeq + 1) & 0xFFFF;
        if (a.callouts) {
            soundCall = c;
        }
    }

    private void tuneIls(Air a) {
        ilsNear.clear();
        if (a.ils != null) {
            List<LinerIls.Station> l = new ArrayList<LinerIls.Station>(a.ils);
            final double x = a.x, z = a.z;
            java.util.Collections.sort(l, new java.util.Comparator<LinerIls.Station>() {
                @Override
                public int compare(LinerIls.Station p, LinerIls.Station q) {
                    return Double.compare(LinerIls.sq(p.tx() - x) + LinerIls.sq(p.tz() - z), LinerIls.sq(q.tx() - x) + LinerIls.sq(q.tz() - z));
                }
            });
            for (int i = 0; i < Math.min(4, l.size()); i++) {
                ilsNear.add(l.get(i));
            }
            LinerIls.Station s = LinerIls.pick(l, ilsChosen, a.x, a.y, a.z, a.hdg, a.ilsRange);
            // keep the tuned one while it stays in range (no hopping between parallel beacons)
            if (ils != null && s != ils && (ilsChosen == null || ilsChosen.length() == 0)) {
                for (LinerIls.Station o : l) {
                    if (o.key().equals(ils.key()) && LinerIls.dev(o, a.x, a.y, a.z, a.ilsRange * 1.2).locValid) {
                        s = o;
                    }
                }
            }
            ils = s;
        }
    }

    /** The «Пульты» list's choice (LinerControls.ILS_SEL, A_SET + n): 0 automatic, 1..4 the near list's stations. */
    public void chooseIls(int n) {
        if (n <= 0 || n > ilsNear.size()) {
            ilsChosen = "";
        } else {
            ilsChosen = ilsNear.get(n - 1).key();
        }
    }

    // ---------------------------------------------------------------- the thrust for the engines

    /** The N1 (%) engine i spools to: the reverse's, or idle .. TOGA by the commanded thrust. */
    public double n1Want(int i) {
        if (rev > 0) {
            return revPos[i] >= 0.95 ? LinerSystems.IDLE_N1 + (REV_N1 - LinerSystems.IDLE_N1) * Math.max(0, (rev - REV_IDLE) / (1 - REV_IDLE))
                    : LinerSystems.IDLE_N1;
        }
        return LinerSystems.IDLE_N1 + (LinerSystems.TOGA_N1 - LinerSystems.IDLE_N1) * thrCmd;
    }

    // ---------------------------------------------------------------- guidance (both sides)

    /** The guidance's inputs (the physics' / the PFD's own aircraft). */
    public static final class GIn {
        public double x, y, z, hdg, speed, gs, vy, bank, ra = 250, stall = 1.15, cap = 0.3, dive = 0.45, thrAcc;
        public double routeHdg = Double.NaN;
        public boolean ground;
    }

    /** The commands: the roll / pitch sticks, the bank / vertical speed wanted (the FD bars), the nose wheel; NaN = no command. */
    public static final class Cmd {
        public double roll = Double.NaN, pitch = Double.NaN, bank = Double.NaN, vy = Double.NaN, steer = Double.NaN;
    }

    /** The modes' commands now (the synced state; pure). */
    public Cmd guide(GIn g) {
        Cmd c = new Cmd();
        LinerIls.Dev d = ils == null ? null : LinerIls.dev(ils, g.x, g.y, g.z, 1e9);
        // lateral
        double want = Double.NaN;
        switch (lat) {
            case L_HDG:
                want = hdg;
                break;
            case L_NAV:
                want = Double.isNaN(g.routeHdg) ? g.hdg : g.routeHdg;
                break;
            case L_RWY:
            case L_RWY_TRK:
            case L_GA_TRK:
                want = latHold;
                break;
            case L_LOC_CAP:
            case L_LOC:
            case L_LAND:
                want = d == null ? g.hdg : LinerGuide.locHeading(ils.course, d.cross, g.hdg, g.gs);
                break;
            case L_ROLLOUT:
                if (d != null) {
                    c.steer = LinerGuide.rolloutSteer(ils.course, d.cross, g.hdg, g.gs);
                }
                if (!g.ground) {
                    // the systems feel the wheels a little before the physics does (or it bounced): keep the flare's sink, wings level
                    c.vy = -LinerGuide.FLARE_SINK;
                    c.pitch = LinerGuide.pitchStick(c.vy, g.cap, g.dive);
                    c.bank = 0;
                    c.roll = LinerGuide.rollStick(0, g.bank);
                }
                break;
            default:
                break;
        }
        if (!Double.isNaN(want) && !g.ground) {
            double lim = lat == L_LAND ? 15 : LinerGuide.AP_BANK;
            c.bank = LinerGuide.bankFor(want, g.hdg, lim);
            c.roll = LinerGuide.rollStick(c.bank, g.bank);
        }
        // vertical
        double vy = Double.NaN;
        switch (ver) {
            case V_SRS:
                vy = Math.max(LinerGuide.SRS_MIN, Math.min(LinerGuide.SRS_MAX, LinerGuide.speedOnPitch(g.thrAcc, g.speed, spdT)));
                break;
            case V_CLB:
            case V_OP_CLB:
            case V_EXP_CLB:
                vy = Math.max(0, Math.min(g.cap, LinerGuide.speedOnPitch(g.thrAcc, g.speed, spdT)));
                vy = Math.min(vy, LinerGuide.capture(altT - g.y) + 0.0);
                vy = Math.max(0, vy);
                break;
            case V_DES:
            case V_OP_DES:
            case V_EXP_DES:
                vy = Math.min(0, Math.max(-g.dive, LinerGuide.speedOnPitch(g.thrAcc, g.speed, spdT)));
                if (ver == V_DES) {
                    vy = Math.max(vy, -0.25);
                }
                vy = Math.max(vy, LinerGuide.capture(altT - g.y));
                vy = Math.min(0, vy);
                break;
            case V_ALT_CAP:
                vy = LinerGuide.capture(altT - g.y);
                break;
            case V_ALT:
                vy = LinerGuide.hold(altT - g.y);
                break;
            case V_VS:
                vy = Math.max(-g.dive, Math.min(g.cap, vsT));
                double e = altT - g.y;
                if ((verArm & AV_ALT) != 0 && Math.abs(e) < LinerGuide.captureDistance(Math.abs(vsT))) {
                    vy = Math.signum(vy) == Math.signum(e) ? vy : 0;
                }
                break;
            case V_GS_CAP:
            case V_GS:
            case V_LAND:
                if (d != null) {
                    double glideVy = LinerGuide.glide(g.gs, ils.angle, d.above);
                    vy = ver == V_GS_CAP ? Math.max(glideVy, -0.25) : glideVy;
                }
                break;
            case V_FLARE:
                if (d != null) {
                    vy = LinerGuide.flare(g.ra, -g.gs * Math.tan(Math.toRadians(ils.angle)));
                } else {
                    vy = LinerGuide.flare(g.ra, -0.06);
                }
                break;
            default:
                break;
        }
        if (!Double.isNaN(vy) && !g.ground) {
            c.vy = vy;
            c.pitch = LinerGuide.pitchStick(vy, g.cap, g.dive);
        }
        return c;
    }

    // ---------------------------------------------------------------- the FMA's words (both sides)

    /** Column 1: the thrust mode's text ("MAN FLX 45" with the temperature). */
    public String thrText() {
        if (thr == T_MAN_FLX) {
            return T_NAMES[thr] + " " + FLEX_TEMP;
        }
        return T_NAMES[Math.max(0, Math.min(T_NAMES.length - 1, thr))];
    }

    public String verText() {
        if (ver == V_VS && trk) {
            return "FPA";
        }
        if ((ver == V_ALT || ver == V_ALT_CAP) && cst) {
            return ver == V_ALT ? "ALT CST" : "ALT CST*";
        }
        return V_NAMES[Math.max(0, Math.min(V_NAMES.length - 1, ver))];
    }

    public String latText() {
        if (lat == L_HDG && trk) {
            return "TRACK";
        }
        return L_NAMES[Math.max(0, Math.min(L_NAMES.length - 1, lat))];
    }

    /** Vertical armed modes (blue), e.g. "ALT G/S". */
    public String verArmText() {
        StringBuilder b = new StringBuilder();
        if ((verArm & AV_CLB) != 0) {
            b.append("CLB ");
        }
        if ((verArm & AV_DES) != 0) {
            b.append("DES ");
        }
        if ((verArm & AV_ALT) != 0) {
            b.append(cst ? "ALT CST " : "ALT ");
        }
        if ((verArm & AV_GS) != 0) {
            b.append("G/S ");
        }
        return b.toString().trim();
    }

    public String latArmText() {
        StringBuilder b = new StringBuilder();
        if ((latArm & AL_NAV) != 0) {
            b.append("NAV ");
        }
        if ((latArm & AL_LOC) != 0) {
            b.append("LOC ");
        }
        return b.toString().trim();
    }

    /** Column 4: the approach capability. */
    public int capability() {
        if (!approachArmedOrOn()) {
            return CAP_NONE;
        }
        return ap1 && ap2 ? CAP_CAT3_DUAL : CAP_CAT1;
    }

    public String apText() {
        return ap1 && ap2 ? "AP1+2" : ap1 ? "AP1" : ap2 ? "AP2" : "";
    }

    // ---------------------------------------------------------------- sync

    /** Words in pack(). */
    public static final int WORDS = 54;

    public int[] pack() {
        int f = (ap1 ? 1 : 0) | (ap2 ? 2 : 0) | (athr ? 4 : 0) | (spdMan ? 8 : 0) | (hdgMan ? 16 : 0) | (trk ? 32 : 0) | (std ? 64 : 0) | (cst ? 128 : 0)
                | (exped ? 256 : 0) | (athrLimited ? 512 : 0) | (leversNotSet ? 1024 : 0) | (aFloor ? 2048 : 0) | (togaLk ? 4096 : 0)
                | (lvrClb ? 8192 : 0) | (spdPrev > 0 ? 16384 : 0) | (hdgPrev > 0 ? 32768 : 0) | (vsPrev > 0 ? 65536 : 0) | (ils != null ? 131072 : 0);
        int[] id = LinerIls.packIdent(ils == null ? "" : ils.ident);
        int[] w = {f, spd, hdg, alt, vs10, fpa10, qnh, lat | latArm << 8, ver | verArm << 8, thr | msg << 8, boxThr | boxVer << 8 | boxLat << 16 | boxCap << 24,
                boxAp, apWarn, athrWarn, r(spdT * 7200), r(altT * 10), r(latHold * 10), r(vls * 7200), r(aProt * 7200), r(aFloorV * 7200), r(aMax * 7200),
                r(v1 * 7200), r(v2 * 7200), r(vr * 7200), r(lever * 1000), r(rev * 1000), r(revPos[0] * 100) | r(revPos[1] * 100) << 8, call, callSeq,
                ils == null ? 0 : r(ils.tx() * 10), ils == null ? 0 : r(ils.tz() * 10), ils == null ? 0 : r(ils.elev * 10),
                ils == null ? 0 : r(ils.course * 10), ils == null ? 0 : r(ils.angle * 100), ils == null ? 0 : ils.freq, id[0], id[1], r(thrCmd * 1000),
                r(vsT * 20000), cavalry != 0 ? 1 : 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        // the stations near (the «Пульты» list: ident, frequency) and which one is hand-picked (0 automatic)
        int chosen = 0;
        for (int i = 0; i < Math.min(4, ilsNear.size()); i++) {
            LinerIls.Station n = ilsNear.get(i);
            int[] ni = LinerIls.packIdent(n.ident);
            w[40 + i * 3] = ni[0];
            w[41 + i * 3] = ni[1];
            w[42 + i * 3] = n.freq;
            if (ilsChosen != null && ilsChosen.equals(n.key())) {
                chosen = i + 1;
            }
        }
        w[52] = Math.min(4, ilsNear.size()) | chosen << 4;
        w[53] = 0;
        return w;
    }

    /** The client's mirror (a malformed / short array: unchanged). */
    public void unpack(int[] w, int o) {
        if (w == null || w.length < o + WORDS) {
            return;
        }
        int f = w[o];
        ap1 = (f & 1) != 0;
        ap2 = (f & 2) != 0;
        athr = (f & 4) != 0;
        spdMan = (f & 8) != 0;
        hdgMan = (f & 16) != 0;
        trk = (f & 32) != 0;
        std = (f & 64) != 0;
        cst = (f & 128) != 0;
        exped = (f & 256) != 0;
        athrLimited = (f & 512) != 0;
        leversNotSet = (f & 1024) != 0;
        aFloor = (f & 2048) != 0;
        togaLk = (f & 4096) != 0;
        lvrClb = (f & 8192) != 0;
        spdPrev = (f & 16384) != 0 ? 1 : 0;
        hdgPrev = (f & 32768) != 0 ? 1 : 0;
        vsPrev = (f & 65536) != 0 ? 1 : 0;
        boolean hasIls = (f & 131072) != 0;
        spd = w[o + 1];
        hdg = w[o + 2];
        alt = w[o + 3];
        vs10 = w[o + 4];
        fpa10 = w[o + 5];
        qnh = w[o + 6];
        lat = w[o + 7] & 255;
        latArm = w[o + 7] >> 8;
        ver = w[o + 8] & 255;
        verArm = w[o + 8] >> 8;
        thr = w[o + 9] & 255;
        msg = w[o + 9] >> 8;
        int b = w[o + 10];
        boxThr = b & 255;
        boxVer = b >> 8 & 255;
        boxLat = b >> 16 & 255;
        boxCap = b >>> 24;
        boxAp = w[o + 11];
        apWarn = w[o + 12];
        athrWarn = w[o + 13];
        spdT = w[o + 14] / 7200.0;
        altT = w[o + 15] / 10.0;
        latHold = w[o + 16] / 10.0;
        vls = w[o + 17] / 7200.0;
        aProt = w[o + 18] / 7200.0;
        aFloorV = w[o + 19] / 7200.0;
        aMax = w[o + 20] / 7200.0;
        v1 = w[o + 21] / 7200.0;
        v2 = w[o + 22] / 7200.0;
        vr = w[o + 23] / 7200.0;
        lever = w[o + 24] / 1000.0;
        rev = w[o + 25] / 1000.0;
        revPos[0] = (w[o + 26] & 255) / 100.0;
        revPos[1] = (w[o + 26] >> 8 & 255) / 100.0;
        call = w[o + 27];
        callSeq = w[o + 28];
        if (hasIls) {
            LinerIls.Station s = ils != null ? ils : new LinerIls.Station();
            s.offset = 0;
            s.course = w[o + 32] / 10.0;
            s.fx = w[o + 29] / 10.0;                                // the threshold point is synced directly
            s.fz = w[o + 30] / 10.0;
            s.elev = w[o + 31] / 10.0;
            s.angle = w[o + 33] / 100.0;
            s.freq = w[o + 34];
            s.ident = LinerIls.unpackIdent(w[o + 35], w[o + 36]);
            ils = s;
        } else {
            ils = null;
        }
        thrCmd = w[o + 37] / 1000.0;
        vsT = w[o + 38] / 20000.0;
        cavalry = w[o + 39] != 0 ? -1 : 0;
        ilsNear.clear();
        int n = w[o + 52] & 15;
        nearChosen = w[o + 52] >> 4 & 15;
        for (int i = 0; i < Math.min(4, n); i++) {
            LinerIls.Station st = new LinerIls.Station();
            st.ident = LinerIls.unpackIdent(w[o + 40 + i * 3], w[o + 41 + i * 3]);
            st.freq = w[o + 42 + i * 3];
            ilsNear.add(st);
        }
    }

    /** A client's mirror: the «Пульты» list's hand-picked station (1..4; 0 automatic). */
    public int nearChosen;

    static int r(double v) {
        return (int) Math.round(v);
    }

    static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // ---------------------------------------------------------------- save

    public void writeNBT(NBTTagCompound t) {
        t.setIntArray("Fcu", new int[] {spd, hdg, alt, vs10, fpa10, qnh, (spdMan ? 1 : 0) | (hdgMan ? 2 : 0) | (trk ? 4 : 0) | (std ? 8 : 0)});
        t.setString("IlsChosen", ilsChosen == null ? "" : ilsChosen);
        t.setDouble("Lever", lever);
    }

    public void readNBT(NBTTagCompound t) {
        int[] f = t.getIntArray("Fcu");
        if (f.length == 7) {
            spd = clampI(f[0], SPD_MIN, SPD_MAX);
            hdg = ((f[1] % 360) + 360) % 360;
            alt = clampI(f[2], ALT_MIN, ALT_MAX);
            vs10 = clampI(f[3], -VS_MAX, VS_MAX);
            fpa10 = clampI(f[4], -FPA_MAX, FPA_MAX);
            qnh = clampI(f[5], QNH_MIN, QNH_MAX);
            spdMan = (f[6] & 1) != 0;
            hdgMan = (f[6] & 2) != 0;
            trk = (f[6] & 4) != 0;
            std = (f[6] & 8) != 0;
        }
        ilsChosen = t.getString("IlsChosen");
        if (t.hasKey("Lever")) {
            double l = t.getDouble("Lever");
            lever = Double.isNaN(l) ? 0 : Math.max(0, Math.min(1, l));
        }
    }
}
