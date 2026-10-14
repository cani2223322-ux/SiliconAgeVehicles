package com.sva.client;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerSystems;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 1 (mock-ups veh_liner_11 PFD / ND, 12 E/WD, 13a SD): the SA-320's six big DUs and ISIS with real data,
 * drawn in each screen's own units (LinerPaintSVA.SCREENS: 280 x 280, ISIS 98 x 98; x right, y down - LinerGfxSVA).
 * <ul>
 * <li>PFD: the FMA's five columns (wave 1: «РУЧНОЕ» - the AP is wave 4), the attitude (pitch ladder, bank scale, the
 * aircraft symbol), the speed tape (km/h) and the altitude tape (m = blocks), the vertical speed (m/s), the heading band,
 * QNH; the INS not aligned - the red ПРОСТР / КУРС flags as on the C+ cockpits.</li>
 * <li>ND: ARC (and the other modes drawn as ARC, PLAN north up) with the range from the EFIS selector, the flight plan from
 * the route data (magenta / green), GS / TAS, the active point's bearing, distance and the time; GPS PRIMARY.</li>
 * <li>E/WD: N1 and EGT dials, N2 (its start background) and FF per engine, FOB, the thrust mode, the flaps' S / F; the
 * warnings (red / amber) left, the memos right.</li>
 * <li>SD: ДВИГ, ВСУ, ОТБОР, ЭЛЕКТР; wave 2: ГИДР, УПР, КОЛЁСА (LinerSdPagesSVA); wave 3: ТОПЛ, КОНД, ДВЕРИ, СТАТУС, the
 * checklists and the cruise page (LinerSdPages3SVA); the TAT / SAT / time / gross weight strip.</li>
 * <li>Wave 3: the E/WD is LinerEwdSVA's (the ECAM's messages with action lines, T.O. / LDG MEMO, FU).</li>
 * <li>Wave 2 on the PFD: the flight control law - amber X's on the bank scale in ALTN / DIRECT, «ТРИММ ВРУЧНУЮ» in
 * DIRECT.</li>
 * </ul>
 * No power: dark (LinerSystems.duPowered - on the batteries alone only the captain's PFD and ISIS live).
 */
@SideOnly(Side.CLIENT)
public final class LinerDisplaysSVA {

    static final int BG = 0x06080C, WHT = 0xF2F2F6, GRN = 0x46E87A, AMB = 0xFFA828, RED = 0xEE3324, CYN = 0x5ADCFF, MAG = 0xE66EE6, YEL = 0xFFD228,
            GREY = 0x5A5E66, DARK = 0x2A2E36, SKY = 0x2870C8, EARTH = 0x82542A;
    /** The SD's page keys (LinerControls.SD_PAGES order). */
    static final int P_ENG = 0, P_BLEED = 1, P_ELEC = 3, P_APU = 6;

    private LinerDisplaysSVA() {
    }

    static String tr(String k, Object... a) {
        return LinerSdPagesSVA.lang("sva.liner.du." + k, a);       // wave 4: the preview's lang too
    }

    /** Any lang key (the preview's map when set). */
    static String trAny(String key, Object... a) {
        return LinerSdPagesSVA.lang(key, a);
    }

    /** Draws DU `du` (LinerSystems.DU_*) in its frame (the caller set it), w x h units. */
    static void draw(int du, EntityVehicleSVA v, LinerSystems L, float w, float h, float pt) {
        LinerGfxSVA.resetLayer();
        LinerGfxSVA.rect(0, 0, w, h, L.duPowered(du) ? BG : 0x020304);
        if (!L.duPowered(du)) {
            return;
        }
        LinerGfxSVA.layer();
        switch (du) {
            case LinerSystems.DU_PFD1:
            case LinerSystems.DU_PFD2:
                // wave 4: the PFD with the FMA, FD, bands and the ILS (LinerPfdSVA - the preview draws the same)
                LinerPfdSVA.draw(LinerPfdSVA.fromEntity(v, L, pt, du == LinerSystems.DU_PFD1), L, w, h);
                break;
            case LinerSystems.DU_ND1:
            case LinerSystems.DU_ND2:
                nd(v, L, w, h, pt, du == LinerSystems.DU_ND1);
                break;
            case LinerSystems.DU_EWD:
                ewd(v, L, w, h);
                break;
            case LinerSystems.DU_SD:
                sd(v, L, w, h);
                break;
            default:
                isis(v, w, h, pt);
                break;
        }
    }

    // ---------------------------------------------------------------- the data

    /** Speed over the ground now (km/h): the driver's own physics, else the motion seen. */
    static float kmh(EntityVehicleSVA v) {
        double dx = v.posX - v.prevPosX, dz = v.posZ - v.prevPosZ;
        return (float) (Math.max(Math.abs(v.speed), Math.sqrt(dx * dx + dz * dz)) * 72);
    }

    static float heading(EntityVehicleSVA v, float pt) {
        return (float) AvionicsSVA.heading(v, pt);
    }

    static float alt(EntityVehicleSVA v, float pt) {
        return (float) (v.prevPosY + (v.posY - v.prevPosY) * pt);
    }

    // ---------------------------------------------------------------- PFD

    private static float clampX(float x, float a, float b) {
        return Math.max(a, Math.min(b, x));
    }

    /** A red failure word in a red box (the A320's flags). */
    static void flag(String word, float x, float y, float h) {
        LinerGfxSVA.text(word, x, y, h, RED, 0);
    }

    /** A tape: ticks every `step`, the readout box at cy. */
    static void tape(float x0, float x1, float t0, float t1, float cy, float val, int step, boolean left, float s) {
        LinerGfxSVA.rect(x0, t0, x1, t1, 0x2C3038);
        LinerGfxSVA.layer();
        float ppv = 5.5F * s;
        int v0 = (int) Math.floor((val - (cy - t0) / ppv) / step) * step;
        for (int k = 0; k <= (int) ((t1 - t0) / ppv / step) + 2; k++) {
            int vv = v0 + k * step;
            float y = cy - (vv - val) * ppv;
            if (y < t0 + 5 * s || y > t1 - 5 * s) {
                continue;
            }
            float ex = left ? x1 : x0;
            LinerGfxSVA.line(ex, y, ex + (left ? -6 : 6) * s, y, 1F * s, WHT);
            if (vv % (step * 2) == 0 && vv >= 0) {
                LinerGfxSVA.text(String.valueOf(vv), left ? x1 - 9 * s : x0 + 9 * s, y, 8 * s, WHT, left ? 1 : -1);
            }
        }
        LinerGfxSVA.layer();
        LinerGfxSVA.rect(x0 - 2 * s, cy - 8 * s, x1 + 2 * s, cy + 8 * s, 0x000000);
        LinerGfxSVA.layer();
        LinerGfxSVA.frameRect(x0 - 2 * s, cy - 8 * s, x1 + 2 * s, cy + 8 * s, 1.2F * s, YEL);
        LinerGfxSVA.text(String.valueOf(Math.round(val)), (x0 + x1) / 2, cy, 11 * s, GRN, 0, x1 - x0);
    }

    /** The convex polygon `xy` cut to the half-plane where (p - o) . n >= 0. */
    static float[] clip(float[] xy, float ox, float oy, float nx, float ny) {
        int n = xy.length / 2;
        float[] out = new float[(n + 2) * 2];
        int k = 0;
        for (int i = 0; i < n; i++) {
            float ax = xy[i * 2], ay = xy[i * 2 + 1], bx = xy[(i + 1) % n * 2], by = xy[(i + 1) % n * 2 + 1];
            float da = (ax - ox) * nx + (ay - oy) * ny, db = (bx - ox) * nx + (by - oy) * ny;
            if (da >= 0) {
                out[k++] = ax;
                out[k++] = ay;
            }
            if ((da >= 0) != (db >= 0)) {
                float t = da / (da - db);
                out[k++] = ax + (bx - ax) * t;
                out[k++] = ay + (by - ay) * t;
            }
        }
        return java.util.Arrays.copyOf(out, k);
    }

    // ---------------------------------------------------------------- ND

    static void nd(EntityVehicleSVA v, LinerSystems L, float w, float h, float pt, boolean captain) {
        float s = w / 280F;
        int mode = L.at(captain ? LinerControls.ND_MODE_L : LinerControls.ND_MODE_R);
        int rangeI = L.at(captain ? LinerControls.ND_RANGE_L : LinerControls.ND_RANGE_R);
        float range = Integer.parseInt(LinerControls.ND_RANGES[rangeI]) * 10F;            // blocks to the outer ring
        boolean plan = mode == 4;
        if (mode == 0 && v.insReady()) {
            // wave 4: ROSE ILS - the course pointer and the deviations (LinerPfdSVA)
            LinerPfdSVA.roseIls(LinerPfdSVA.fromEntity(v, L, pt, captain), L, w, h, range);
            return;
        }
        float spd = kmh(v);
        LinerGfxSVA.text("GS", 6 * s, 10 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(String.valueOf(Math.round(spd)), 22 * s, 10 * s, 9 * s, GRN, -1);
        LinerGfxSVA.text("TAS", 52 * s, 10 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(String.valueOf(Math.round(spd)), 72 * s, 10 * s, 9 * s, GRN, -1);
        if (!v.insReady()) {
            flag(tr("flag.hdg"), w / 2, 50 * s, 11 * s);
            LinerGfxSVA.text(tr("noMap"), w / 2, h / 2, 12 * s, RED, 0);
            String sub = GlassCockpitSVA.insSub(v);
            if (sub != null) {
                LinerGfxSVA.text(sub, w / 2, h / 2 + 18 * s, 8 * s, WHT, 0);
            }
            LinerGfxSVA.text("GPS PRIMARY", w / 2, 270 * s, 8 * s, WHT, 0);
            return;
        }
        float hdg = heading(v, pt);
        float cx = w / 2, cy = plan ? h / 2 : 240 * s, R = plan ? 110 * s : 200 * s;
        float rot = plan ? 0 : hdg;                                  // ARC: track up; PLAN: north up
        // the compass arc / rose
        if (plan) {
            LinerGfxSVA.arc(cx, cy, R, 0, 360, 1.2F * s, WHT);
        } else {
            LinerGfxSVA.arc(cx, cy, R, -55, 55, 1.2F * s, WHT);
        }
        LinerGfxSVA.layer();
        for (int k = 0; k < 360; k += 5) {
            float a = k - rot;
            a = ((a + 540) % 360) - 180;
            if (!plan && Math.abs(a) > 55) {
                continue;
            }
            boolean big = k % 10 == 0;
            LinerGfxSVA.line(LinerGfxSVA.px(cx, R, a), LinerGfxSVA.py(cy, R, a), LinerGfxSVA.px(cx, R - (big ? 8 : 4) * s, a),
                    LinerGfxSVA.py(cy, R - (big ? 8 : 4) * s, a), 1F * s, WHT);
            if (k % 30 == 0) {
                LinerGfxSVA.text(String.valueOf(k / 10), LinerGfxSVA.px(cx, R + 9 * s, a), LinerGfxSVA.py(cy, R + 9 * s, a), 9 * s, WHT, 0);
            }
        }
        // range ring (half), its label
        if (plan) {
            LinerGfxSVA.arc(cx, cy, R / 2, 0, 360, 0.8F * s, GREY);
        } else {
            LinerGfxSVA.arc(cx, cy, R / 2, -55, 55, 0.8F * s, GREY);
        }
        LinerGfxSVA.text(String.valueOf(Math.round(range / 2)), LinerGfxSVA.px(cx, R / 2 + 6 * s, -60), LinerGfxSVA.py(cy, R / 2 + 6 * s, -60), 7 * s,
                CYN, 0);
        // the flight plan: lines from the aircraft through the points, the active one white
        RouteMath.Plan p = v.plan();
        float lastX = cx, lastY = cy;
        int nearest = -1;
        double nd = 0;
        if (p != null && !p.isEmpty()) {
            for (int i = Math.max(0, p.active); i < p.size(); i++) {
                int[] pt0 = p.pts.get(i);
                double dx = pt0[0] + 0.5 - v.posX, dz = pt0[1] + 0.5 - v.posZ;
                double br = Math.toDegrees(Math.atan2(dx, -dz));          // compass bearing: north (-z) 0, east (+x) 90
                double d = Math.sqrt(dx * dx + dz * dz);
                float a = (float) (br - rot);
                float r = (float) (d / range * R);
                float x = LinerGfxSVA.px(cx, r, a), y = LinerGfxSVA.py(cy, r, a);
                if (r > R * 1.3F) {
                    x = LinerGfxSVA.px(cx, R * 1.3F, a);
                    y = LinerGfxSVA.py(cy, R * 1.3F, a);
                }
                LinerGfxSVA.line(lastX, lastY, x, y, 1.6F * s, GRN);
                LinerGfxSVA.layer();
                LinerGfxSVA.frameRect(x - 3 * s, y - 3 * s, x + 3 * s, y + 3 * s, 1.2F * s, i == p.active ? WHT : GRN);
                LinerGfxSVA.text(String.valueOf(i + 1), x + 6 * s, y, 8 * s, i == p.active ? WHT : GRN, -1);
                if (L.on(LinerControls.EFIS_PB[captain ? 0 : 5])) {
                    // wave 4, ОГР (CSTR): the point's altitude constraint, magenta
                    LinerGfxSVA.text("+" + pt0[2], x + 6 * s, y + 9 * s, 7 * s, MAG, -1);
                }
                if (i == p.active) {
                    nearest = i;
                    nd = d;
                    LinerGfxSVA.text(String.format("%03d", Integer.valueOf((int) Math.round((br + 360) % 360))), 274 * s, 10 * s, 9 * s, GRN, 1);
                }
                lastX = x;
                lastY = y;
            }
        }
        if (nearest >= 0) {
            LinerGfxSVA.text(LinerGfxSVA.num(nd / 1000, 1) + " " + tr("km"), 274 * s, 22 * s, 9 * s, GRN, 1);
            LinerGfxSVA.text("T" + (nearest + 1), 230 * s, 10 * s, 9 * s, WHT, 1);
        }
        long tod = v.worldObj.getWorldTime() % 24000;
        int hh = (int) ((tod / 1000 + 6) % 24), mm = (int) (tod % 1000 * 60 / 1000);
        LinerGfxSVA.text(String.format("%02d:%02d", Integer.valueOf(hh), Integer.valueOf(mm)), 274 * s, 34 * s, 9 * s, GRN, 1);
        // wave 4, АЭР (ARPT): the tuned ILS' runway - its threshold and the landing course (a white bar, the ident)
        com.sva.vehicle.LinerIls.Station ils = L.afs.ils;
        if (ils != null && L.on(LinerControls.EFIS_PB[captain ? 4 : 9])) {
            double dx = ils.tx() - v.posX, dz = ils.tz() - v.posZ;
            double br = Math.toDegrees(Math.atan2(dx, -dz));
            float r = (float) (Math.sqrt(dx * dx + dz * dz) / range * R);
            if (r < R * 1.2F) {
                float a = (float) (br - rot);
                float x = LinerGfxSVA.px(cx, r, a), y = LinerGfxSVA.py(cy, r, a);
                float c = (float) (ils.course - rot), len = (float) (com.sva.vehicle.LinerIls.LOC_DIST / range * R);
                LinerGfxSVA.layer();
                LinerGfxSVA.line(x, y, LinerGfxSVA.px(x, Math.max(3 * s, len), c), LinerGfxSVA.py(y, Math.max(3 * s, len), c), 2.4F * s, WHT);
                LinerGfxSVA.text(ils.ident, x + 6 * s, y + 8 * s, 8 * s, WHT, -1);
            }
        }
        // the aircraft (yellow) and the heading pointer
        LinerGfxSVA.layer();
        float ac = plan ? hdg : 0;
        LinerGfxSVA.line(LinerGfxSVA.px(cx, 12 * s, ac), LinerGfxSVA.py(cy, 12 * s, ac), LinerGfxSVA.px(cx, -10 * s, ac), LinerGfxSVA.py(cy, -10 * s, ac),
                2.4F * s, YEL);
        LinerGfxSVA.line(LinerGfxSVA.px(cx, 3, ac - 90) + 0, LinerGfxSVA.py(cy, 9 * s, ac - 90), LinerGfxSVA.px(cx, 9 * s, ac + 90),
                LinerGfxSVA.py(cy, 9 * s, ac + 90), 2.4F * s, YEL);
        if (!plan) {
            LinerGfxSVA.rect(cx - 2 * s, cy - R - 8 * s, cx + 2 * s, cy - R + 2 * s, YEL);
        }
        LinerGfxSVA.layer();
        LinerGfxSVA.rect(90 * s, 262 * s, 190 * s, 276 * s, 0x000000);
        LinerGfxSVA.layer();
        LinerGfxSVA.frameRect(90 * s, 262 * s, 190 * s, 276 * s, 1F * s, WHT);
        LinerGfxSVA.text("GPS PRIMARY", w / 2, 269 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(Lang.tr("sva.liner.pos." + LinerControls.ND_MODES[mode]), 6 * s, 269 * s, 7 * s, GRN, -1);
        LinerGfxSVA.text(tr("range", Integer.valueOf(Math.round(range))), 274 * s, 269 * s, 7 * s, CYN, 1);
    }

    // ---------------------------------------------------------------- E/WD

    static void ewd(EntityVehicleSVA v, LinerSystems L, float w, float h) {
        // wave 3: LinerEwdSVA draws it from the systems alone (the preview draws the same); the entity gives the levers
        LinerEwdSVA.draw(L, w, h, v.getThrottle(), v.linerAnim.flap, v.linerAnim.slat, L.at(LinerControls.FLAPS), v.anim.height < 0.6,
                (int) Math.round(L.batteryMinutes(estimateBat1(v, L))));
    }

    /** АКБ 1's charge on a client: from its volts (the server syncs the volts, not the EU). */
    static double estimateBat1(EntityVehicleSVA v, LinerSystems L) {
        double f = (L.bat1V - LinerSystems.V_EMPTY) / (LinerSystems.V_FULL - LinerSystems.V_EMPTY);
        return Math.max(0, Math.min(1, f)) * com.sva.vehicle.BatteryMath.capacity(VehicleType.LINER);
    }

    static float n1Ang(float n1) {
        return -125 + Math.max(0, Math.min(110, n1)) / 110F * 210;
    }

    static float egtAng(float egt) {
        return -90 + Math.max(0, Math.min(1000, egt)) / 1000F * 180;
    }

    // ---------------------------------------------------------------- SD

    static void sd(EntityVehicleSVA v, LinerSystems L, float w, float h) {
        float s = w / 280F;
        int page = L.autoPage();
        LinerGfxSVA.text(Lang.tr("sva.liner.sd." + com.sva.vehicle.LinerEcam.PAGE_KEYS[page]), 8 * s, 12 * s, 11 * s, WHT, -1);
        LinerGfxSVA.line(8 * s, 19 * s, 8 * s + 44 * s, 19 * s, 1F * s, WHT);
        switch (page) {
            case P_ENG:
                sdEng(L, s);
                break;
            case P_APU:
                sdApu(L, s);
                break;
            case P_BLEED:
                sdBleed(L, s);
                break;
            case P_ELEC:
                sdElec(L, s);
                break;
            default:
                if (!LinerSdPagesSVA.draw(page, L, s)) {
                    LinerGfxSVA.text(tr("later"), w / 2, 120 * s, 9 * s, GREY, 0, w * 0.9F);
                }
                break;
        }
        // the bottom strip: TAT / SAT, the time, the gross weight
        LinerGfxSVA.line(0, 244 * s, w, 244 * s, 1F * s, WHT);
        LinerGfxSVA.line(90 * s, 244 * s, 90 * s, h, 1F * s, WHT);
        LinerGfxSVA.line(190 * s, 244 * s, 190 * s, h, 1F * s, WHT);
        int sat = (int) Math.round(15 - Math.max(0, v.posY - 64) * 0.0065 * 10);
        int tat = sat + (int) Math.round(kmh(v) / 72 * 4);
        LinerGfxSVA.text("TAT", 6 * s, 254 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text((tat >= 0 ? "+" : "") + tat + " C", 84 * s, 254 * s, 8 * s, GRN, 1);
        LinerGfxSVA.text("SAT", 6 * s, 268 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text((sat >= 0 ? "+" : "") + sat + " C", 84 * s, 268 * s, 8 * s, GRN, 1);
        long tod = v.worldObj.getWorldTime() % 24000;
        int hh = (int) ((tod / 1000 + 6) % 24), mm = (int) (tod % 1000 * 60 / 1000);
        LinerGfxSVA.text(String.format("%02d " + tr("h") + " %02d", Integer.valueOf(hh), Integer.valueOf(mm)), 140 * s, 261 * s, 11 * s, GRN, 0);
        LinerGfxSVA.text(tr("gw"), 196 * s, 254 * s, 8 * s, CYN, -1);
        int kg = (int) Math.round(16000 + L.tanks() / 48000.0 * 2600 + v.ridersNow() * 80);
        LinerGfxSVA.text(com.sva.FormatSVA.group(kg) + " " + tr("kg"), 276 * s, 268 * s, 9 * s, GRN, 1);
    }

    private static void sdEng(LinerSystems L, float s) {
        float[] xs = {70 * s, 210 * s};
        LinerGfxSVA.text(tr("eng.used"), 140 * s, 40 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("eng.oil"), 140 * s, 80 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("eng.psi"), 140 * s, 120 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("eng.temp"), 140 * s, 150 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("eng.vib"), 140 * s, 175 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("eng.starter"), 140 * s, 215 * s, 8 * s, WHT, 0);
        for (int i = 0; i < 2; i++) {
            LinerSystems.Eng g = L.eng[i];
            float x = xs[i];
            double k = Math.min(1, g.n2 / LinerSystems.IDLE_N2);
            LinerGfxSVA.text(com.sva.FormatSVA.group(Math.round(L.fuel.used[i])), x, 40 * s, 9 * s, GRN, 0);   // wave 3: the real FU
            LinerGfxSVA.text(LinerGfxSVA.num(15.5 - (g.running() ? 0.3 : 0), 1), x, 80 * s, 9 * s, GRN, 0);
            LinerGfxSVA.text(String.valueOf(Math.round(42 * k)), x, 120 * s, 9 * s, GRN, 0);
            LinerGfxSVA.text(String.valueOf(Math.round(15 + 63 * k)), x, 150 * s, 9 * s, GRN, 0);
            LinerGfxSVA.text(LinerGfxSVA.num(g.running() ? 0.8 + 0.1 * i : 0, 1), x, 175 * s, 9 * s, GRN, 0);
            // the starter valve: a circle with the bar along (open) / across (shut), the duct's psi
            float cy = 215 * s, cx = x + (i == 0 ? -20 : 20) * s;
            LinerGfxSVA.arc(cx, cy, 7 * s, 0, 360, 1.4F * s, GRN);
            LinerGfxSVA.layer();
            if (g.starter) {
                LinerGfxSVA.line(cx, cy - 7 * s, cx, cy + 7 * s, 1.4F * s, GRN);
            } else {
                LinerGfxSVA.line(cx - 7 * s, cy, cx + 7 * s, cy, 1.4F * s, GRN);
            }
            LinerGfxSVA.text(Math.round(L.duct(i)) + " psi", x + (i == 0 ? -20 : 20) * s, cy + 15 * s, 8 * s, GRN, 0);
        }
    }

    private static void sdApu(LinerSystems L, float s) {
        if (L.apu == LinerSystems.A_AVAIL || L.apu == LinerSystems.A_COOL) {
            LinerGfxSVA.text(tr("apu.avail"), 160 * s, 14 * s, 10 * s, GRN, 0);
        }
        // the APU GEN box
        LinerGfxSVA.frameRect(14 * s, 34 * s, 104 * s, 96 * s, 1.2F * s, L.apuGenOn ? WHT : AMB);
        LinerGfxSVA.text(tr("apu.gen"), 59 * s, 44 * s, 8 * s, WHT, 0);
        if (L.apuGenOn) {
            LinerGfxSVA.text(L.loadApu + " %", 59 * s, 58 * s, 9 * s, GRN, 0);
            LinerGfxSVA.text("115 " + tr("v"), 59 * s, 72 * s, 9 * s, GRN, 0);
            LinerGfxSVA.text("400 " + tr("hz"), 59 * s, 86 * s, 9 * s, GRN, 0);
        } else {
            LinerGfxSVA.text(Lang.tr("sva.liner.pos.off"), 59 * s, 68 * s, 9 * s, WHT, 0);
        }
        // the bleed: the valve, its psi
        float bx = 210 * s;
        LinerGfxSVA.line(bx, 36 * s, bx, 96 * s, 1.4F * s, GRN);
        LinerGfxSVA.arc(bx, 70 * s, 7 * s, 0, 360, 1.4F * s, GRN);
        LinerGfxSVA.layer();
        if (L.apuBleedOpen) {
            LinerGfxSVA.line(bx, 63 * s, bx, 77 * s, 1.4F * s, GRN);
        } else {
            LinerGfxSVA.line(bx - 7 * s, 70 * s, bx + 7 * s, 70 * s, 1.4F * s, GRN);
        }
        LinerGfxSVA.text(Math.round(L.apuPsi) + " psi", bx + 12 * s, 50 * s, 9 * s, GRN, -1);
        LinerGfxSVA.text(tr("apu.bleed"), bx, 106 * s, 8 * s, WHT, 0);
        // N and EGT dials
        dial(80 * s, 160 * s, 30 * s, (float) L.apuN, 0, 107, 101, "N %", Math.round(L.apuN) + "", s);
        dial(200 * s, 160 * s, 30 * s, (float) L.apuEgt, 0, 1000, 760, "EGT C", Math.round(L.apuEgt) + "", s);
        LinerGfxSVA.text(tr("apu.flap"), 20 * s, 222 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(Lang.tr(L.apuFlap > 0.95 ? "sva.liner.pos.open" : "sva.liner.pos.shut"), 90 * s, 222 * s, 8 * s, GRN, -1);
        LinerGfxSVA.text(tr("apu.fuel"), 20 * s, 234 * s, 7 * s, GREY, -1);
        if (L.apuFault) {
            LinerGfxSVA.text(Lang.tr("sva.liner.msg.apuFault"), 200 * s, 226 * s, 9 * s, AMB, 0);
        }
    }

    /** A half dial (-120..120 deg) with its red line, the needle, the value and the label. */
    static void dial(float cx, float cy, float R, float val, float lo, float hi, float red, String label, String value, float s) {
        LinerGfxSVA.arc(cx, cy, R, -120, 120, 1.3F * s, WHT);
        LinerGfxSVA.layer();
        float ar = -120 + (red - lo) / (hi - lo) * 240;
        LinerGfxSVA.arc(cx, cy, R, ar, 120, 2.2F * s, RED);
        float a = -120 + (Math.max(lo, Math.min(hi, val)) - lo) / (hi - lo) * 240;
        LinerGfxSVA.line(cx, cy, LinerGfxSVA.px(cx, R, a), LinerGfxSVA.py(cy, R, a), 1.6F * s, GRN);
        LinerGfxSVA.text(value, cx + 12 * s, cy + 14 * s, 10 * s, GRN, 0);
        LinerGfxSVA.text(label, cx, cy + 30 * s, 8 * s, WHT, 0);
    }

    private static void sdBleed(LinerSystems L, float s) {
        // packs on top, the duct across, the valves: ENG 1 / X BLEED / APU / ENG 2
        float yDuct = 120 * s;
        LinerGfxSVA.line(40 * s, yDuct, 240 * s, yDuct, 1.6F * s, GRN);
        for (int i = 0; i < 2; i++) {
            float x = (i == 0 ? 70 : 210) * s;
            boolean on = L.on(i == 0 ? LinerControls.PACK1 : LinerControls.PACK2);
            LinerGfxSVA.frameRect(x - 26 * s, 30 * s, x + 26 * s, 72 * s, 1.2F * s, WHT);
            LinerGfxSVA.text(tr("pack", Integer.valueOf(i + 1)), x, 40 * s, 8 * s, WHT, 0);
            LinerGfxSVA.text(on ? "102 %" : Lang.tr("sva.liner.pos.off"), x, 58 * s, 9 * s, on ? GRN : AMB, 0);
            LinerGfxSVA.line(x, 72 * s, x, yDuct, 1.4F * s, GRN);
            valve(x, 96 * s, on, s);
            // engine bleed: a pipe down to the engine, the valve, psi
            float ex = (i == 0 ? 40 : 240) * s;
            LinerGfxSVA.line(ex, yDuct, ex, 200 * s, 1.4F * s, GRN);
            valve(ex, 170 * s, i == 0 ? L.bleed1Open : L.bleed2Open, s);
            LinerGfxSVA.text(Math.round(i == 0 ? L.ductL : L.ductR) + " psi", ex + (i == 0 ? 12 : -12) * s, 145 * s, 9 * s, GRN, i == 0 ? -1 : 1);
            LinerGfxSVA.text(tr("eng", Integer.valueOf(i + 1)), ex, 212 * s, 8 * s, WHT, 0);
        }
        valve(140 * s, yDuct, L.xbleedOpen, s);
        LinerGfxSVA.text(tr("xbleed"), 140 * s, yDuct - 14 * s, 7 * s, WHT, 0);
        LinerGfxSVA.line(110 * s, yDuct, 110 * s, 190 * s, 1.4F * s, GRN);
        valve(110 * s, 160 * s, L.apuBleedOpen, s);
        LinerGfxSVA.text(Lang.tr("sva.liner.sd.apu"), 110 * s, 200 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("xbleed.mode", Lang.tr("sva.liner.pos." + LinerControls.XBLEED.pos[L.at(LinerControls.XBLEED)])), 140 * s, 232 * s, 7 * s,
                GREY, 0);
    }

    static void valve(float x, float y, boolean open, float s) {
        LinerGfxSVA.disc(x, y, 7 * s, BG);
        LinerGfxSVA.layer();
        LinerGfxSVA.arc(x, y, 7 * s, 0, 360, 1.4F * s, GRN);
        if (open) {
            LinerGfxSVA.line(x, y - 7 * s, x, y + 7 * s, 1.4F * s, GRN);
        } else {
            LinerGfxSVA.line(x - 7 * s, y, x + 7 * s, y, 1.4F * s, AMB);
        }
    }

    private static void sdElec(LinerSystems L, float s) {
        // АКБ 1 / 2 over the ШИНА АКБ
        box(16 * s, 28 * s, 70 * s, 64 * s, tr("bat", Integer.valueOf(1)), L.bat1V > 0 ? LinerGfxSVA.num(L.bat1V, 1) + " " + tr("v") : "",
                L.bat1V > 0 ? signed(L.bat1A) + " " + tr("a") : "", L.bat1On, s);
        box(210 * s, 28 * s, 264 * s, 64 * s, tr("bat", Integer.valueOf(2)), L.bat2V > 0 ? LinerGfxSVA.num(L.bat2V, 1) + " " + tr("v") : "",
                L.bat2V > 0 ? signed(L.bat2A) + " " + tr("a") : "", L.bat2On, s);
        boolean dc = L.bat1On || L.bat2On || L.acPowered();
        busBox(100 * s, 38 * s, 180 * s, 52 * s, tr("batbus"), dc, s);
        LinerGfxSVA.line(70 * s, 45 * s, 100 * s, 45 * s, 1.2F * s, L.bat1On ? GRN : GREY);
        LinerGfxSVA.line(180 * s, 45 * s, 210 * s, 45 * s, 1.2F * s, L.bat2On ? GRN : GREY);
        // DC 1 / 2, TR 1 / 2, AC 1 / 2
        boolean ac1 = L.ac1Src != LinerSystems.S_NONE, ac2 = L.ac2Src != LinerSystems.S_NONE;
        busBox(16 * s, 80 * s, 80 * s, 94 * s, tr("dc", Integer.valueOf(1)), ac1, s);
        busBox(200 * s, 80 * s, 264 * s, 94 * s, tr("dc", Integer.valueOf(2)), ac2, s);
        box(26 * s, 104 * s, 70 * s, 130 * s, tr("tr", Integer.valueOf(1)), ac1 ? "28 " + tr("v") : "", "", ac1, s);
        box(210 * s, 104 * s, 254 * s, 130 * s, tr("tr", Integer.valueOf(2)), ac2 ? "28 " + tr("v") : "", "", ac2, s);
        busBox(16 * s, 142 * s, 80 * s, 156 * s, tr("ac", Integer.valueOf(1)), ac1, s);
        busBox(200 * s, 142 * s, 264 * s, 156 * s, tr("ac", Integer.valueOf(2)), ac2, s);
        LinerGfxSVA.text(tr("tie"), 140 * s, 142 * s, 7 * s, WHT, 0);
        valve(140 * s, 152 * s, L.on(LinerControls.BUS_TIE) && (ac1 || ac2), s);
        if (L.galleyShed) {
            LinerGfxSVA.text(Lang.tr("sva.liner.memo.galley"), 140 * s, 168 * s, 8 * s, AMB, 0);
        }
        // the sources: ГЕН 1, ГЕН ВСУ, ВНЕШН. ПИТ, ГЕН 2
        src(10 * s, 180 * s, 66 * s, 236 * s, tr("gen", Integer.valueOf(1)), L.gen1On, L.loadGen1, s);
        src(72 * s, 180 * s, 130 * s, 236 * s, tr("apugen"), L.apuGenOn, L.loadApu, s);
        LinerGfxSVA.frameRect(136 * s, 180 * s, 206 * s, 206 * s, 1.2F * s, L.extOn ? WHT : GREY);
        LinerGfxSVA.text(tr("ext"), 171 * s, 188 * s, 7 * s, WHT, 0);
        LinerGfxSVA.text(L.extOn ? L.loadExt + " %" : L.extAvail ? Lang.tr("sva.liner.pos.avail") : tr("none"), 171 * s, 199 * s, 8 * s,
                L.extOn || L.extAvail ? GRN : WHT, 0);
        src(212 * s, 180 * s, 270 * s, 236 * s, tr("gen", Integer.valueOf(2)), L.gen2On, L.loadGen2, s);
        LinerGfxSVA.text(tr("rat"), 171 * s, 224 * s, 7 * s, GREY, 0);
    }

    static String signed(double a) {
        long r = Math.round(a);
        return (r > 0 ? "+" : "") + r;
    }

    static void box(float x0, float y0, float x1, float y1, String title, String l1, String l2, boolean on, float s) {
        LinerGfxSVA.frameRect(x0, y0, x1, y1, 1.2F * s, on ? WHT : GREY);
        LinerGfxSVA.text(title, (x0 + x1) / 2, y0 + 8 * s, 7 * s, WHT, 0);
        LinerGfxSVA.text(l1, (x0 + x1) / 2, y0 + 19 * s, 8 * s, GRN, 0);
        LinerGfxSVA.text(l2, (x0 + x1) / 2, y0 + 29 * s, 8 * s, GRN, 0);
    }

    static void busBox(float x0, float y0, float x1, float y1, String name, boolean live, float s) {
        LinerGfxSVA.frameRect(x0, y0, x1, y1, 1.2F * s, live ? GRN : AMB);
        LinerGfxSVA.text(name, (x0 + x1) / 2, (y0 + y1) / 2, 7.5F * s, live ? GRN : AMB, 0, x1 - x0 - 4 * s);
    }

    static void src(float x0, float y0, float x1, float y1, String name, boolean on, int load, float s) {
        LinerGfxSVA.frameRect(x0, y0, x1, y1, 1.2F * s, on ? WHT : GREY);
        LinerGfxSVA.text(name, (x0 + x1) / 2, y0 + 8 * s, 7 * s, WHT, 0);
        if (on) {
            LinerGfxSVA.text(load + " %", (x0 + x1) / 2, y0 + 20 * s, 8 * s, GRN, 0);
            LinerGfxSVA.text("115 " + tr("v"), (x0 + x1) / 2, y0 + 32 * s, 8 * s, GRN, 0);
            LinerGfxSVA.text("400 " + tr("hz"), (x0 + x1) / 2, y0 + 44 * s, 8 * s, GRN, 0);
        } else {
            LinerGfxSVA.text(Lang.tr("sva.liner.pos.off"), (x0 + x1) / 2, y0 + 28 * s, 8 * s, WHT, 0);
        }
    }

    // ---------------------------------------------------------------- ISIS

    static void isis(EntityVehicleSVA v, float w, float h, float pt) {
        float s = w / 98F;
        float pitch = v.prevRenderPitch + (v.renderPitch - v.prevRenderPitch) * pt;
        float roll = v.prevRenderRoll + (v.renderRoll - v.prevRenderRoll) * pt;
        float cx = 49 * s, cy = 50 * s, per = 1.4F * s;
        double a = Math.toRadians(roll);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float ox = cx + sa * pitch * per, oy = cy + ca * pitch * per;
        float[] area = {22 * s, 10 * s, 76 * s, 10 * s, 76 * s, 90 * s, 22 * s, 90 * s};
        LinerGfxSVA.poly(clip(area, ox, oy, sa, -ca), SKY);
        LinerGfxSVA.poly(clip(area, ox, oy, -sa, ca), EARTH);
        LinerGfxSVA.layer();
        LinerGfxSVA.line(cx - 14 * s, cy, cx - 5 * s, cy, 2F * s, YEL);
        LinerGfxSVA.line(cx + 5 * s, cy, cx + 14 * s, cy, 2F * s, YEL);
        LinerGfxSVA.rect(1 * s, 40 * s, 21 * s, 60 * s, 0x000000);
        LinerGfxSVA.rect(77 * s, 40 * s, 97 * s, 60 * s, 0x000000);
        LinerGfxSVA.layer();
        LinerGfxSVA.text(String.valueOf(Math.round(kmh(v))), 11 * s, 50 * s, 8 * s, WHT, 0, 19 * s);
        LinerGfxSVA.text(String.valueOf(Math.round(alt(v, pt))), 87 * s, 50 * s, 8 * s, WHT, 0, 19 * s);
        LinerGfxSVA.text(String.valueOf(Math.round(heading(v, pt)) % 360), cx, 94 * s, 7 * s, WHT, 0);
    }
}
