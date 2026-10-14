package com.sva.client;

import com.sva.entity.EntityVehicleSVA;
import com.sva.entity.LinerPhysicsSVA;
import com.sva.vehicle.LinerAfs;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerFctl;
import com.sva.vehicle.LinerFlight;
import com.sva.vehicle.LinerIls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.PlaneParams;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 4 (mock-up veh_liner_11): the SA-320's PFD with the auto flight system - drawn from a Data snapshot (the
 * entity's in the game - fromEntity; the standalone preview builds one from a simulated flight) and the synced systems:
 * <ul>
 * <li>the FMA in five columns: thrust (MAN TOGA / MAN FLX 45 / THR CLB / SPEED / A.FLOOR..., «LVR CLB» flashing under it),
 * vertical and lateral (active green, armed blue below), the approach capability (CAT 1 / CAT 3 DUAL), AP1+2 / 1FD2 /
 * A/THR (blue when armed); a new mode boxed for 10 s; the message line under (ТРИММ ВРУЧНУЮ, the go-around's flaps);</li>
 * <li>the flight director's green bars (the bank / vertical speed the active modes want, LinerAfs.guide on this client);</li>
 * <li>the speed tape's bands - VLS (amber), alpha-prot (amber / black), alpha-max (red), VMO (red / black) - the target
 * speed (magenta managed / cyan selected), V1 «1» and VR «R» on the take-off roll; the altitude tape's FCU target, QNH /
 * STD;</li>
 * <li>LS: the LOC / G/S scales with the magenta diamonds, the ILS' ident / frequency / course / distance bottom left; the
 * radio height, the callouts flashed, V1.</li>
 * </ul>
 * ND ROSE ILS (roseIls): a full rose heading up with the course pointer and its deviation bar, the G/S diamond.
 */
@SideOnly(Side.CLIENT)
public final class LinerPfdSVA {

    static final int BG = LinerDisplaysSVA.BG, WHT = LinerDisplaysSVA.WHT, GRN = LinerDisplaysSVA.GRN, AMB = LinerDisplaysSVA.AMB,
            RED = LinerDisplaysSVA.RED, CYN = LinerDisplaysSVA.CYN, MAG = LinerDisplaysSVA.MAG, YEL = LinerDisplaysSVA.YEL,
            GREY = LinerDisplaysSVA.GREY, DARK = LinerDisplaysSVA.DARK, SKY = LinerDisplaysSVA.SKY, EARTH = LinerDisplaysSVA.EARTH;

    private LinerPfdSVA() {
    }

    /** What a PFD shows (the aircraft; the systems come separately). */
    public static final class Data {
        public float pitch, roll, kmh, y, vs, hdg;
        public boolean ins = true, stall, ground = true, ls, fd = true, captain = true;
        /** Radio height (blocks; NaN none), position (the ILS' geometry). */
        public double ra = Double.NaN, x, z;
        /** The FD's wanted bank (deg) / vertical speed (b/t) - NaN: no bar; the vertical speed (b/t). */
        public double fdBank = Double.NaN, fdVy = Double.NaN, vy;
        /** A callout to flash now (LinerAfs.CALL_* / a height; 0 none), the clock (ms) for the flashing. */
        public int flash;
        public long now;
        /** INS alignment's sub-line (null none). */
        public String insSub;
    }

    // ---------------------------------------------------------------- the entity's data

    private static final java.util.Map<Integer, int[]> SEEN = new java.util.HashMap<Integer, int[]>();

    static Data fromEntity(EntityVehicleSVA v, LinerSystems L, float pt, boolean captain) {
        Data d = new Data();
        d.captain = captain;
        d.pitch = v.prevRenderPitch + (v.renderPitch - v.prevRenderPitch) * pt;
        d.roll = v.prevRenderRoll + (v.renderRoll - v.prevRenderRoll) * pt;
        d.kmh = LinerDisplaysSVA.kmh(v);
        d.y = LinerDisplaysSVA.alt(v, pt);
        d.vs = (float) ((v.posY - v.prevPosY) * 20);
        d.vy = v.posY - v.prevPosY;
        d.hdg = LinerDisplaysSVA.heading(v, pt);
        d.ins = v.insReady();
        d.insSub = GlassCockpitSVA.insSub(v);
        d.stall = v.isStalling();
        d.ground = v.anim.height < 0.6;
        d.ra = v.anim.height;
        d.x = v.posX;
        d.z = v.posZ;
        d.ls = L.on(captain ? LinerControls.LS_L : LinerControls.LS_R);
        d.fd = L.on(captain ? LinerControls.FD_L : LinerControls.FD_R);
        d.now = System.currentTimeMillis();
        // the FD bars: the synced modes' commands for this aircraft as this client sees it
        if (d.fd && (L.afs.lat != LinerAfs.L_NONE || L.afs.ver != LinerAfs.V_NONE)) {
            PlaneParams p = v.flightParams(VehicleType.LINER);
            LinerFlight.Body b = LinerPhysicsSVA.body(v, L);
            b.bank = d.roll;
            b.motionY = d.vy;
            b.ra = d.ra;
            LinerAfs.Cmd c = L.afs.guide(LinerFlight.guideIn(b, VehicleType.LINER, L, p, d.kmh / 72.0));
            d.fdBank = c.bank;
            d.fdVy = c.vy;
        }
        // a callout flashes 1.5 s after its sequence number changed
        int[] seen = SEEN.get(Integer.valueOf(v.getEntityId()));
        if (seen == null) {
            if (SEEN.size() > 64) {
                SEEN.clear();                                       // liners long gone
            }
            seen = new int[] {L.afs.callSeq, 0, 0};
            SEEN.put(Integer.valueOf(v.getEntityId()), seen);
        }
        long t = d.now;
        if (seen[0] != L.afs.callSeq) {
            seen[0] = L.afs.callSeq;
            seen[1] = L.afs.call;
            seen[2] = (int) (t / 10 % 1000000);
        }
        long since = (t / 10 % 1000000) - seen[2];
        if (since < 0) {
            since += 1000000;
        }
        d.flash = seen[1] != 0 && since < 150 ? seen[1] : 0;
        return d;
    }

    // ---------------------------------------------------------------- the PFD

    public static void draw(Data d, LinerSystems L, float w, float h) {
        float s = w / 280F;
        LinerAfs F = L.afs;
        fma(d, L, w, s);
        float cx = 140 * s, cy = 132 * s;
        float ax0 = 62 * s, ax1 = 218 * s, ay0 = 48 * s, ay1 = 222 * s;
        if (d.ins) {
            float per = 3.2F * s;
            double a = Math.toRadians(d.roll);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float ox = cx + sa * d.pitch * per, oy = cy + ca * d.pitch * per;
            float[] area = {ax0, ay0, ax1, ay0, ax1, ay1, ax0, ay1};
            LinerGfxSVA.poly(LinerDisplaysSVA.clip(area, ox, oy, sa, -ca), SKY);
            LinerGfxSVA.poly(LinerDisplaysSVA.clip(area, ox, oy, -sa, ca), EARTH);
            LinerGfxSVA.layer();
            for (int deg = -20; deg <= 20; deg += 5) {
                float len = deg == 0 ? 90 * s : (Math.abs(deg) % 10 == 0 ? 22 : 11) * s;
                float px = ox - sa * deg * per, py = oy - ca * deg * per;
                float x0 = px - ca * len, y0 = py + sa * len, x1 = px + ca * len, y1 = py - sa * len;
                if (py > ay0 + 6 * s && py < ay1 - 6 * s) {
                    LinerGfxSVA.line(cl(x0, ax0, ax1), y0, cl(x1, ax0, ax1), y1, (deg == 0 ? 1.6F : 1.1F) * s, WHT);
                    if (deg != 0 && deg % 10 == 0) {
                        LinerGfxSVA.text(String.valueOf(Math.abs(deg)), px - ca * (len + 9 * s), py + sa * (len + 9 * s), 8 * s, WHT, 0);
                    }
                }
            }
            float R = 70 * s;
            for (int k : new int[] {-45, -30, -20, -10, 0, 10, 20, 30, 45}) {
                LinerGfxSVA.line(LinerGfxSVA.px(cx, R, k), LinerGfxSVA.py(cy, R, k), LinerGfxSVA.px(cx, R + (k % 30 == 0 ? 9 : 5) * s, k),
                        LinerGfxSVA.py(cy, R + (k % 30 == 0 ? 9 : 5) * s, k), 1.2F * s, WHT);
            }
            LinerGfxSVA.tri(cx, cy - R - 1 * s, cx - 5 * s, cy - R - 9 * s, cx + 5 * s, cy - R - 9 * s, YEL);
            if (L.fctl.law != LinerFctl.NORMAL) {
                for (int k : new int[] {-50, 50}) {
                    float xx = LinerGfxSVA.px(cx, R + 4 * s, k), yy = LinerGfxSVA.py(cy, R + 4 * s, k);
                    LinerGfxSVA.line(xx - 4 * s, yy - 4 * s, xx + 4 * s, yy + 4 * s, 1.4F * s, AMB);
                    LinerGfxSVA.line(xx - 4 * s, yy + 4 * s, xx + 4 * s, yy - 4 * s, 1.4F * s, AMB);
                }
            } else {
                // wave 4: the bank protection's green marks at 67°
                for (int k : new int[] {-67, 67}) {
                    LinerGfxSVA.line(LinerGfxSVA.px(cx, R + 2 * s, k), LinerGfxSVA.py(cy, R + 2 * s, k), LinerGfxSVA.px(cx, R + 8 * s, k),
                            LinerGfxSVA.py(cy, R + 8 * s, k), 1.3F * s, GRN);
                }
            }
            LinerGfxSVA.tri(LinerGfxSVA.px(cx, R - 2 * s, -d.roll), LinerGfxSVA.py(cy, R - 2 * s, -d.roll), LinerGfxSVA.px(cx, R - 10 * s, -d.roll - 4),
                    LinerGfxSVA.py(cy, R - 10 * s, -d.roll - 4), LinerGfxSVA.px(cx, R - 10 * s, -d.roll + 4), LinerGfxSVA.py(cy, R - 10 * s, -d.roll + 4),
                    Math.abs(d.roll) > 35 ? AMB : YEL);
            LinerGfxSVA.layer();
            // the flight director's bars (green), then the aircraft symbol over them
            if (d.fd && !d.ground && (!Double.isNaN(d.fdBank) || !Double.isNaN(d.fdVy))) {
                if (!Double.isNaN(d.fdBank)) {
                    float off = (float) Math.max(-40, Math.min(40, (d.fdBank - d.roll) * 1.6)) * s;
                    LinerGfxSVA.line(cx + off, cy - 38 * s, cx + off, cy + 38 * s, 1.8F * s, GRN);
                }
                if (!Double.isNaN(d.fdVy)) {
                    float off = (float) Math.max(-40, Math.min(40, -(d.fdVy - d.vy) * 20 * 8)) * s;
                    LinerGfxSVA.line(cx - 38 * s, cy + off, cx + 38 * s, cy + off, 1.8F * s, GRN);
                }
                LinerGfxSVA.layer();
            }
            LinerGfxSVA.rect(cx - 52 * s, cy - 2.5F * s, cx - 20 * s, cy + 2.5F * s, 0x000000);
            LinerGfxSVA.rect(cx + 20 * s, cy - 2.5F * s, cx + 52 * s, cy + 2.5F * s, 0x000000);
            LinerGfxSVA.layer();
            LinerGfxSVA.frameRect(cx - 52 * s, cy - 2.5F * s, cx - 20 * s, cy + 2.5F * s, 1.3F * s, YEL);
            LinerGfxSVA.frameRect(cx + 20 * s, cy - 2.5F * s, cx + 52 * s, cy + 2.5F * s, 1.3F * s, YEL);
            LinerGfxSVA.frameRect(cx - 3 * s, cy - 3 * s, cx + 3 * s, cy + 3 * s, 1.3F * s, YEL);
            // the radio height (green, under the aircraft symbol) under 150 blocks in the air
            if (!d.ground && !Double.isNaN(d.ra) && d.ra < 150) {
                LinerGfxSVA.text(String.valueOf(Math.round(d.ra)), cx, ay1 - 14 * s, 11 * s, d.ra < 10 ? AMB : GRN, 0);
            }
        } else {
            LinerDisplaysSVA.flag(LinerDisplaysSVA.tr("flag.att"), cx, cy, 12 * s);
            if (d.insSub != null) {
                LinerGfxSVA.text(d.insSub, cx, cy + 20 * s, 8 * s, WHT, 0, 140 * s);
            }
        }
        LinerGfxSVA.layer();
        // the tapes
        speedTape(d, L, s, ay0, ay1, cy);
        float shown = (float) F.shownAlt(d.y);
        LinerDisplaysSVA.tape(226 * s, 262 * s, ay0, ay1, cy, shown, 10, false, s);
        LinerGfxSVA.text(LinerDisplaysSVA.tr("kmh"), 29 * s, ay1 + 9 * s, 7 * s, WHT, 0);
        // the FCU altitude: the number over the tape (cyan selected / magenta managed), a mark on it
        int fcuAlt = F.alt;
        boolean altMan = F.ver == LinerAfs.V_CLB || F.ver == LinerAfs.V_DES || F.cst;
        LinerGfxSVA.text(String.valueOf(fcuAlt), 244 * s, ay0 - 6 * s, 9 * s, altMan ? MAG : CYN, 0);
        float ty = cy - (fcuAlt - shown) * 5.5F * s;
        if (ty > ay0 && ty < ay1) {
            LinerGfxSVA.layer();
            LinerGfxSVA.rect(226 * s, ty - 1.5F * s, 232 * s, ty + 1.5F * s, altMan ? MAG : CYN);
        }
        // the vertical speed (m/s)
        float vx = 268 * s, span = 70 * s;
        LinerGfxSVA.rect(264 * s, cy - span, 278 * s, cy + span, DARK);
        LinerGfxSVA.layer();
        float vy = cy - Math.max(-1F, Math.min(1F, d.vs / 6F)) * span;
        LinerGfxSVA.line(279 * s, cy, vx - 4 * s, vy, 1.6F * s, GRN);
        if (Math.abs(d.vs) > 0.4F) {
            LinerGfxSVA.text(LinerGfxSVA.num(Math.abs(d.vs), 0), 271 * s, vy + (d.vs > 0 ? -8 : 8) * s, 7 * s, GRN, 0);
        }
        // the heading band with the selected heading (cyan) and the ILS course (magenta)
        float by0 = 236 * s, by1 = 258 * s;
        LinerGfxSVA.rect(70 * s, by0, 210 * s, by1, DARK);
        LinerGfxSVA.layer();
        if (d.ins) {
            float per = 3.5F * s;
            int h0 = (int) Math.floor(d.hdg / 5) * 5;
            for (int k = h0 - 20; k <= h0 + 25; k += 5) {
                float x = cx + (k - d.hdg) * per;
                if (x < 72 * s || x > 208 * s) {
                    continue;
                }
                boolean big = ((k % 360) + 360) % 10 == 0;
                LinerGfxSVA.line(x, by0, x, by0 + (big ? 6 : 3) * s, 1F * s, WHT);
                if (big) {
                    LinerGfxSVA.text(String.valueOf(((k % 360) + 360) % 360 / 10), x, by0 + 13 * s, 8 * s, WHT, 0);
                }
            }
            LinerGfxSVA.layer();
            if (F.lat == LinerAfs.L_HDG || F.hdgPrev > 0 || !F.hdgMan) {
                float hx = cx + (float) com.sva.vehicle.CruiseMath.wrap(F.hdg - d.hdg) * per;
                hx = cl(hx, 72 * s, 208 * s);
                LinerGfxSVA.tri(hx, by0, hx - 4 * s, by0 - 7 * s, hx + 4 * s, by0 - 7 * s, CYN);
            }
            if (d.ls && F.ils != null) {
                float ix = cx + (float) com.sva.vehicle.CruiseMath.wrap(F.ils.course - d.hdg) * per;
                if (ix > 72 * s && ix < 208 * s) {
                    LinerGfxSVA.line(ix, by0 - 2 * s, ix, by0 + 9 * s, 2F * s, MAG);
                }
            }
            LinerGfxSVA.line(cx, by0 - 6 * s, cx, by0 + 2 * s, 2F * s, YEL);
        } else {
            LinerDisplaysSVA.flag(LinerDisplaysSVA.tr("flag.hdg"), cx, (by0 + by1) / 2, 9 * s);
        }
        LinerGfxSVA.text(F.std ? "STD" : "QNH", 228 * s, 247 * s, 7 * s, CYN, -1);
        if (!F.std) {
            LinerGfxSVA.text(String.valueOf(F.qnh), 248 * s, 247 * s, 9 * s, CYN, -1);
        } else {
            LinerGfxSVA.frameRect(226 * s, 240 * s, 246 * s, 254 * s, 0.8F * s, YEL);
        }
        // the ILS: the scales and diamonds (LS), the station's words bottom left
        if (d.ls && F.ils != null && d.ins) {
            ils(d, F, s, cx, cy, ay1);
        }
        if (d.stall && (d.now / 250) % 2 == 0) {
            LinerGfxSVA.layer();
            LinerGfxSVA.text(LinerDisplaysSVA.tr("stall"), cx, cy + 40 * s, 14 * s, RED, 0);
        }
        // the callouts flashed (a height, RETARD, V1)
        if (d.flash != 0) {
            LinerGfxSVA.layer();
            String t = d.flash == LinerAfs.CALL_RETARD ? "RETARD" : d.flash == LinerAfs.CALL_V1 ? "V1" : d.flash == LinerAfs.CALL_MINIMUM ? "MINIMUM"
                    : String.valueOf(d.flash);
            LinerGfxSVA.text(t, cx, cy + 56 * s, 13 * s, d.flash == LinerAfs.CALL_RETARD ? WHT : AMB, 0);
        }
    }

    /** The FMA's five columns (see the class comment). */
    static void fma(Data d, LinerSystems L, float w, float s) {
        LinerAfs F = L.afs;
        for (int i = 1; i < 5; i++) {
            LinerGfxSVA.line(i * w / 5, 4 * s, i * w / 5, 36 * s, 1.2F * s, GREY);
        }
        float cw = w / 5;
        boolean flash = (d.now / 400) % 2 == 0;
        // column 1: thrust
        String t = F.thrText();
        int tc = F.thr == LinerAfs.T_MAN_TOGA || F.thr == LinerAfs.T_MAN_FLX || F.thr == LinerAfs.T_MAN_MCT || F.thr == LinerAfs.T_MAN_THR || F.thr == LinerAfs.T_RETARD
                ? WHT : GRN;
        if (t.startsWith("MAN ")) {
            LinerGfxSVA.text("MAN", cw * 0.5F, 11 * s, 8.5F * s, tc, 0, cw - 4 * s);
            LinerGfxSVA.text(t.substring(4), cw * 0.5F, 21 * s, 8.5F * s, tc, 0, cw - 4 * s);
            LinerGfxSVA.frameRect(cw * 0.5F - 24 * s, 5 * s, cw * 0.5F + 24 * s, 27 * s, 0.9F * s, WHT);
        } else if (t.length() > 0) {
            LinerGfxSVA.text(t, cw * 0.5F, 11 * s, 9 * s, tc, 0, cw - 4 * s);
            if (F.thr == LinerAfs.T_AFLOOR || F.thr == LinerAfs.T_TOGA_LK) {
                LinerGfxSVA.frameRect(4 * s, 4 * s, cw - 4 * s, 18 * s, 1F * s, flash ? AMB : GRN);
            }
        }
        if (F.lvrClb && flash) {
            LinerGfxSVA.text("LVR CLB", cw * 0.5F, 32 * s, 8 * s, WHT, 0, cw - 4 * s);
        }
        if (F.boxThr < LinerAfs.BOX_TICKS && t.length() > 0 && !t.startsWith("MAN ") && F.thr != LinerAfs.T_AFLOOR && F.thr != LinerAfs.T_TOGA_LK) {
            LinerGfxSVA.frameRect(4 * s, 4 * s, cw - 4 * s, 18 * s, 0.8F * s, WHT);
        }
        // columns 2 / 3: vertical, lateral (LAND / FLARE / ROLL OUT span both)
        boolean span = F.lat == LinerAfs.L_LAND && (F.ver == LinerAfs.V_LAND) || F.lat == LinerAfs.L_ROLLOUT;
        if (span) {
            String m = F.lat == LinerAfs.L_ROLLOUT ? "ROLL OUT" : "LAND";
            LinerGfxSVA.text(m, cw * 2, 11 * s, 9 * s, GRN, 0, cw * 2 - 8 * s);
            if (F.boxLat < LinerAfs.BOX_TICKS) {
                LinerGfxSVA.frameRect(cw + 4 * s, 4 * s, cw * 3 - 4 * s, 18 * s, 0.8F * s, WHT);
            }
        } else {
            String vt = F.verText(), lt = F.latText();
            if (F.ver == LinerAfs.V_FLARE) {
                vt = "FLARE";
            }
            LinerGfxSVA.text(vt, cw * 1.5F, 11 * s, 9 * s, GRN, 0, cw - 4 * s);
            LinerGfxSVA.text(lt, cw * 2.5F, 11 * s, 9 * s, GRN, 0, cw - 4 * s);
            if (F.boxVer < LinerAfs.BOX_TICKS && vt.length() > 0) {
                LinerGfxSVA.frameRect(cw + 4 * s, 4 * s, cw * 2 - 4 * s, 18 * s, 0.8F * s, WHT);
            }
            if (F.boxLat < LinerAfs.BOX_TICKS && lt.length() > 0) {
                LinerGfxSVA.frameRect(cw * 2 + 4 * s, 4 * s, cw * 3 - 4 * s, 18 * s, 0.8F * s, WHT);
            }
        }
        LinerGfxSVA.text(F.verArmText(), cw * 1.5F, 23 * s, 7.5F * s, CYN, 0, cw - 4 * s);
        LinerGfxSVA.text(F.latArmText(), cw * 2.5F, 23 * s, 7.5F * s, CYN, 0, cw - 4 * s);
        // the message line (amber / white, across the vertical and lateral columns)
        if (F.msg != LinerAfs.MSG_NONE) {
            LinerGfxSVA.text(LinerDisplaysSVA.trAny("sva.liner.fma." + LinerAfs.MSG_KEYS[F.msg]), cw * 2, 33 * s, 7 * s,
                    F.msg == LinerAfs.MSG_TRIM ? AMB : WHT, 0, cw * 2 - 6 * s);
        }
        // column 4: the approach capability
        int cap = F.capability();
        if (cap != LinerAfs.CAP_NONE) {
            LinerGfxSVA.text(cap == LinerAfs.CAP_CAT3_DUAL ? "CAT 3" : "CAT 1", cw * 3.5F, 11 * s, 8.5F * s, WHT, 0, cw - 4 * s);
            if (cap == LinerAfs.CAP_CAT3_DUAL) {
                LinerGfxSVA.text("DUAL", cw * 3.5F, 21 * s, 8.5F * s, WHT, 0, cw - 4 * s);
            }
            if (F.boxCap < LinerAfs.BOX_TICKS) {
                LinerGfxSVA.frameRect(cw * 3 + 4 * s, 4 * s, cw * 4 - 4 * s, 27 * s, 0.8F * s, WHT);
            }
            // wave 5: the MCDU's minimums under the capability (DH radio / MDA baro)
            if (F.dh >= 0 || F.mda >= 0) {
                LinerGfxSVA.text(F.dh >= 0 ? "DH " + F.dh : "MDA " + F.mda, cw * 3.5F, 31 * s, 8F * s, CYN, 0, cw - 4 * s);
            }
        }
        // column 5: AP / FD / A/THR
        LinerGfxSVA.text(F.apText(), cw * 4.5F, 11 * s, 8.5F * s, WHT, 0, cw - 4 * s);
        boolean fd1 = L.on(LinerControls.FD_L), fd2 = L.on(LinerControls.FD_R);
        String fd = fd1 || fd2 ? (fd1 ? "1" : "-") + "FD" + (fd2 ? "2" : "-") : "";
        LinerGfxSVA.text(fd, cw * 4.5F, 21 * s, 8.5F * s, WHT, 0, cw - 4 * s);
        if (F.athr || F.aFloor || F.togaLk) {
            LinerGfxSVA.text("A/THR", cw * 4.5F, 31 * s, 8.5F * s, F.athrActive() || F.aFloor || F.togaLk ? WHT : CYN, 0, cw - 4 * s);
        }
        if (F.boxAp < LinerAfs.BOX_TICKS && F.apOn()) {
            LinerGfxSVA.frameRect(cw * 4 + 4 * s, 4 * s, w - 4 * s, 17 * s, 0.8F * s, WHT);
        }
    }

    /** The speed tape (km/h) with the bands, the target and the V-speeds. */
    static void speedTape(Data d, LinerSystems L, float s, float ay0, float ay1, float cy) {
        LinerAfs F = L.afs;
        float x0 = 8 * s, x1 = 50 * s;
        LinerDisplaysSVA.tape(x0, x1, ay0, ay1, cy, d.kmh, 10, true, s);
        float ppv = 5.5F * s;
        LinerGfxSVA.layer();
        LinerGfxSVA.layer();
        if (!d.ground && F.vls > 0) {
            // VLS (amber line down to alpha-prot), alpha-prot (amber / black), alpha-max (red), from the bottom
            float yVls = yOf(F.vls * 72, d.kmh, cy, ppv), yProt = yOf(F.aProt * 72, d.kmh, cy, ppv), yMax = yOf(F.aMax * 72, d.kmh, cy, ppv);
            band(x1 - 4 * s, yVls, x1, yProt, ay0, ay1, AMB);
            stripes(x1 - 4 * s, yProt, x1, yMax, ay0, ay1, AMB, s);
            band(x1 - 4 * s, yMax, x1, ay1, ay0, ay1, RED);
        }
        // VMO: red / black from VMO up
        float yVmo = yOf(LinerAfs.VMO * 72, d.kmh, cy, ppv);
        stripes(x1 - 4 * s, ay0, x1, yVmo, ay0, ay1, RED, s);
        // the target speed: a triangle at the tape's edge (magenta managed, cyan selected); off the tape - the number
        double tgt = F.ver == LinerAfs.V_SRS && F.v2 > 0 ? F.spdT : F.spdT;
        if (tgt > 0 && (F.athr || F.ver != LinerAfs.V_NONE || !F.spdMan)) {
            int c = F.spdMan ? MAG : CYN;
            float ty = yOf(tgt * 72, d.kmh, cy, ppv);
            if (ty < ay0) {
                LinerGfxSVA.text(String.valueOf(Math.round(tgt * 72)), (x0 + x1) / 2, ay0 - 6 * s, 9 * s, c, 0);
            } else if (ty > ay1) {
                LinerGfxSVA.text(String.valueOf(Math.round(tgt * 72)), (x0 + x1) / 2, ay1 + 18 * s, 9 * s, c, 0);
            } else {
                LinerGfxSVA.tri(x1 + 1 * s, ty, x1 + 8 * s, ty - 4 * s, x1 + 8 * s, ty + 4 * s, c);
            }
        }
        // V1 «1» / VR «R» on the take-off run (wave 5: the MCDU's V-speeds - none entered, no marks)
        if (d.ground && F.v1 > 0 && F.vsEntered) {
            float y1 = yOf(F.v1 * 72, d.kmh, cy, ppv), yr = yOf(F.vr * 72, d.kmh, cy, ppv);
            if (y1 > ay0 && y1 < ay1) {
                LinerGfxSVA.text("1", x1 + 7 * s, y1, 8 * s, CYN, 0);
            }
            if (yr > ay0 && yr < ay1) {
                LinerGfxSVA.text("R", x1 + 7 * s, yr, 8 * s, CYN, 0);
            }
        }
        LinerGfxSVA.layer();
        LinerGfxSVA.line(x1, cy, x1 + 10 * s, cy, 2F * s, YEL);
    }

    static float yOf(double kmh, double now, float cy, float ppv) {
        return (float) (cy - (kmh - now) * ppv);
    }

    static void band(float x0, float y0, float x1, float y1, float lo, float hi, int c) {
        float a = Math.max(lo, Math.min(y0, y1)), b = Math.min(hi, Math.max(y0, y1));
        if (b > a) {
            LinerGfxSVA.rect(x0, a, x1, b, c);
        }
    }

    static void stripes(float x0, float y0, float x1, float y1, float lo, float hi, int c, float s) {
        float a = Math.max(lo, Math.min(y0, y1)), b = Math.min(hi, Math.max(y0, y1));
        for (float y = a; y < b; y += 6 * s) {
            LinerGfxSVA.rect(x0, y, x1, Math.min(b, y + 3 * s), c);
        }
    }

    /** The LOC (under the attitude) and G/S (beside it) scales with the magenta diamonds; the station's words. */
    static void ils(Data d, LinerAfs F, float s, float cx, float cy, float ay1) {
        LinerIls.Dev dv = LinerIls.dev(F.ils, d.x, d.y, d.z, 1e9);
        LinerGfxSVA.layer();
        float ly = ay1 + 7 * s, gx = 222 * s;
        for (int k = -2; k <= 2; k++) {
            if (k != 0) {
                LinerGfxSVA.disc(cx + k * 20 * s, ly, 1.6F * s, WHT);
                LinerGfxSVA.disc(gx, cy + k * 20 * s, 1.6F * s, WHT);
            }
        }
        LinerGfxSVA.line(cx, ly - 5 * s, cx, ly + 5 * s, 1.4F * s, YEL);
        LinerGfxSVA.line(gx - 4 * s, cy, gx + 4 * s, cy, 1.4F * s, YEL);
        LinerGfxSVA.layer();
        if (dv.locValid) {
            // the aircraft right of the centre line: the diamond to the left (fly left)
            float lx = cx - (float) LinerIls.locDots(dv) * 20 * s;
            diamond(lx, ly, 4.5F * s, MAG);
        }
        if (dv.gsValid) {
            float gy = cy + (float) LinerIls.gsDots(dv) * 20 * s;   // above the beam: the diamond (the beam) below
            diamond(gx, gy, 4.5F * s, MAG);
        }
        // the station: ident, frequency, course, distance (km) - bottom left, magenta
        LinerGfxSVA.text(F.ils.ident + "  " + String.format("%03d", Integer.valueOf((int) Math.round(F.ils.course) % 360)), 4 * s, 264 * s, 7.5F * s, MAG,
                -1, 62 * s);
        LinerGfxSVA.text(LinerIls.freqText(F.ils.freq).replace('.', ',') + "  " + LinerGfxSVA.num(dv.dist / 1000, 1) + " " + LinerDisplaysSVA.tr("km"),
                4 * s, 274 * s, 7 * s, MAG, -1, 62 * s);
    }

    static void diamond(float x, float y, float r, int c) {
        LinerGfxSVA.poly(new float[] {x, y - r, x + r * 0.7F, y, x, y + r, x - r * 0.7F, y}, c);
    }

    // ---------------------------------------------------------------- ND ROSE ILS

    /** The ND in ROSE ILS: the rose heading up, the course pointer with the LOC deviation bar, the G/S diamond, the station. */
    public static void roseIls(Data d, LinerSystems L, float w, float h, float range) {
        float s = w / 280F;
        LinerAfs F = L.afs;
        float cx = w / 2, cy = 140 * s, R = 100 * s;
        LinerGfxSVA.text("GS", 6 * s, 10 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(String.valueOf(Math.round(d.kmh)), 22 * s, 10 * s, 9 * s, GRN, -1);
        LinerGfxSVA.text("TAS", 52 * s, 10 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(String.valueOf(Math.round(d.kmh)), 72 * s, 10 * s, 9 * s, GRN, -1);
        LinerGfxSVA.arc(cx, cy, R, 0, 360, 1.2F * s, WHT);
        LinerGfxSVA.layer();
        float rot = d.hdg;
        for (int k = 0; k < 360; k += 5) {
            float a = k - rot;
            boolean big = k % 10 == 0;
            LinerGfxSVA.line(LinerGfxSVA.px(cx, R, a), LinerGfxSVA.py(cy, R, a), LinerGfxSVA.px(cx, R - (big ? 8 : 4) * s, a),
                    LinerGfxSVA.py(cy, R - (big ? 8 : 4) * s, a), 1F * s, WHT);
            if (k % 30 == 0) {
                LinerGfxSVA.text(String.valueOf(k / 10), LinerGfxSVA.px(cx, R + 10 * s, a), LinerGfxSVA.py(cy, R + 10 * s, a), 9 * s, WHT, 0);
            }
        }
        LinerGfxSVA.rect(cx - 2 * s, cy - R - 8 * s, cx + 2 * s, cy - R + 2 * s, YEL);
        if (F.ils != null) {
            LinerIls.Dev dv = LinerIls.dev(F.ils, d.x, d.y, d.z, 1e9);
            float a = (float) (F.ils.course - rot);
            // the course pointer: the arrow through the rose's centre, the deviation bar off it (2 dots = 40 units)
            float r0 = R - 12 * s;
            LinerGfxSVA.line(LinerGfxSVA.px(cx, r0, a), LinerGfxSVA.py(cy, r0, a), LinerGfxSVA.px(cx, 30 * s, a), LinerGfxSVA.py(cy, 30 * s, a), 2.2F * s, MAG);
            LinerGfxSVA.line(LinerGfxSVA.px(cx, -30 * s, a), LinerGfxSVA.py(cy, -30 * s, a), LinerGfxSVA.px(cx, -r0, a), LinerGfxSVA.py(cy, -r0, a), 2.2F * s,
                    MAG);
            LinerGfxSVA.tri(LinerGfxSVA.px(cx, r0 + 2 * s, a), LinerGfxSVA.py(cy, r0 + 2 * s, a), LinerGfxSVA.px(cx, r0 - 10 * s, a - 4),
                    LinerGfxSVA.py(cy, r0 - 10 * s, a - 4), LinerGfxSVA.px(cx, r0 - 10 * s, a + 4), LinerGfxSVA.py(cy, r0 - 10 * s, a + 4), MAG);
            double ra = Math.toRadians(a);
            float px = (float) Math.cos(ra), py = (float) Math.sin(ra);    // the perpendicular (right of the course) in screen units
            for (int k = -2; k <= 2; k++) {
                if (k != 0) {
                    LinerGfxSVA.disc(cx + px * k * 20 * s, cy + py * k * 20 * s, 1.6F * s, WHT);
                }
            }
            if (dv.locValid) {
                float off = -(float) LinerIls.locDots(dv) * 20 * s;
                float bx = cx + px * off, by = cy + py * off;
                LinerGfxSVA.line(LinerGfxSVA.px(bx, 26 * s, a), LinerGfxSVA.py(by, 26 * s, a), LinerGfxSVA.px(bx, -26 * s, a),
                        LinerGfxSVA.py(by, -26 * s, a), 2.2F * s, MAG);
            }
            // the G/S scale on the right
            float gx = 268 * s;
            for (int k = -2; k <= 2; k++) {
                if (k != 0) {
                    LinerGfxSVA.disc(gx, cy + k * 20 * s, 1.6F * s, WHT);
                }
            }
            LinerGfxSVA.line(gx - 4 * s, cy, gx + 4 * s, cy, 1.4F * s, YEL);
            if (dv.gsValid) {
                diamond(gx, cy + (float) LinerIls.gsDots(dv) * 20 * s, 4.5F * s, MAG);
            }
            LinerGfxSVA.text(F.ils.ident, 274 * s, 10 * s, 9 * s, MAG, 1);
            LinerGfxSVA.text(LinerIls.freqText(F.ils.freq).replace('.', ','), 274 * s, 22 * s, 8 * s, MAG, 1);
            LinerGfxSVA.text("CRS " + String.format("%03d", Integer.valueOf((int) Math.round(F.ils.course) % 360)), 274 * s, 34 * s, 8 * s, MAG, 1);
            LinerGfxSVA.text(LinerGfxSVA.num(dv.dist / 1000, 1) + " " + LinerDisplaysSVA.tr("km"), 274 * s, 46 * s, 8 * s, MAG, 1);
        } else {
            LinerGfxSVA.text(LinerDisplaysSVA.tr("noIls"), cx, cy + 40 * s, 9 * s, AMB, 0);
        }
        // the aircraft
        LinerGfxSVA.layer();
        LinerGfxSVA.line(cx, cy - 12 * s, cx, cy + 10 * s, 2.4F * s, YEL);
        LinerGfxSVA.line(cx - 9 * s, cy - 3 * s, cx + 9 * s, cy - 3 * s, 2.4F * s, YEL);
        LinerGfxSVA.text("ROSE ILS", 6 * s, 269 * s, 7 * s, GRN, -1);
        LinerGfxSVA.text(LinerDisplaysSVA.tr("range", Integer.valueOf(Math.round(range))), 274 * s, 269 * s, 7 * s, CYN, 1);
    }

    static float cl(float x, float a, float b) {
        return Math.max(a, Math.min(b, x));
    }
}
