package com.sva.client;

import com.sva.vehicle.LinerChecklist;
import com.sva.vehicle.LinerControls;
import com.sva.vehicle.LinerEcam;
import com.sva.vehicle.LinerFuel;
import com.sva.vehicle.LinerSystems;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Liner wave 3 (mock-ups veh_liner_13b «ТОПЛ» / «ДВЕРИ» / «КОНД», 15 «ПОСЛЕ ЗАПУСКА»): the lower ECAM's pages of this wave in
 * the SD's 280 x 280 units, from the synced systems only (the preview draws the same code through LinerGfxSVA's sink):
 * <ul>
 * <li>ТОПЛ: the three tanks with their quantities (the wings' outer cells beside), the six pumps (a bar along = running,
 * across amber = off / no pressure), the crossfeed valve, the APU's line, the engines' feed lines (green while fuel flows),
 * the transfer, the temperatures, FOB, the imbalance, FU per engine, «ЗАПРАВКА» while refuelling, the gravity feed.</li>
 * <li>КОНД: the cockpit / forward / aft zones (the temperature, the target in cyan), the packs, the hot air, the cabin
 * altitude and its rate.</li>
 * <li>ДВЕРИ: the L1 door (amber open), R1, the overwing exits, the cargo and aft doors, the stairs, the slides, oxygen, the
 * cabin's pressure.</li>
 * <li>СТАТУС: the limitations, the inoperative systems, the cancelled cautions («НОРМА» when none).</li>
 * <li>The checklists (the ВСЕ key's cycle): the list for now, its items auto-ticked.</li>
 * <li>The cruise page: the fuel used, the oil, the vibrations; the cabin's air.</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public final class LinerSdPages3SVA {

    static final int BG = LinerDisplaysSVA.BG, WHT = LinerDisplaysSVA.WHT, GRN = LinerDisplaysSVA.GRN, AMB = LinerDisplaysSVA.AMB,
            RED = LinerDisplaysSVA.RED, CYN = LinerDisplaysSVA.CYN, GREY = LinerDisplaysSVA.GREY;

    private LinerSdPages3SVA() {
    }

    static String tr(String k, Object... a) {
        return LinerSdPagesSVA.tr(k, a);
    }

    static String lang(String k, Object... a) {
        return LinerSdPagesSVA.lang(k, a);
    }

    /** Draws a wave-3 page. @return it is one of them */
    static boolean draw(int page, LinerSystems L, float s) {
        switch (page) {
            case LinerEcam.P_FUEL:
                fuel(L, s);
                return true;
            case LinerEcam.P_COND:
                cond(L, s);
                return true;
            case LinerEcam.P_DOOR:
                door(L, s);
                return true;
            case LinerEcam.P_STS:
                status(L, s);
                return true;
            case LinerEcam.P_CHECK:
                checklist(L, s);
                return true;
            case LinerEcam.P_CRZ:
                cruise(L, s);
                return true;
            default:
                return false;
        }
    }

    static String mb(double v) {
        return com.sva.FormatSVA.group(Math.round(v));
    }

    // ---------------------------------------------------------------- ТОПЛ

    /** Engine feed lines' x, the pumps' x (Л1 Л2 Ц1 Ц2 П1 П2), the tank top / bottom. */
    static final float[] EX = {80, 200}, PX = {62, 92, 125, 155, 188, 218};

    static void fuel(LinerSystems L, float s) {
        LinerFuel f = L.fuel;
        int src = f.src;
        // FU top right
        LinerGfxSVA.text(tr("fuel.used"), 196 * s, 14 * s, 8 * s, WHT, 1);
        LinerGfxSVA.text(mb(f.usedTotal()), 266 * s, 14 * s, 9 * s, GRN, 1);
        LinerGfxSVA.text(tr("mb"), 276 * s, 22 * s, 6 * s, CYN, 1);
        // the engines' lines (green while their engine draws fuel), the LP valves (open with the master)
        for (int i = 0; i < 2; i++) {
            float x = EX[i] * s;
            boolean flow = (src & (i == 0 ? LinerFuel.S_FLOW_1 : LinerFuel.S_FLOW_2)) != 0;
            int c = flow ? GRN : WHT;
            LinerGfxSVA.text(tr("eng", Integer.valueOf(i + 1)), x, 34 * s, 9 * s, WHT, 0);
            LinerGfxSVA.line(x, 42 * s, x, 100 * s, 1.4F * s, c);
            LinerDisplaysSVA.valve(x, 58 * s, L.on(i == 0 ? LinerControls.ENG_MASTER1 : LinerControls.ENG_MASTER2), s);
        }
        // the crossfeed across at y 80
        float xy = 80 * s;
        boolean xflow = (src & LinerFuel.S_XFEED_FLOW) != 0;
        LinerGfxSVA.line(EX[0] * s, xy, EX[1] * s, xy, 1.2F * s, xflow ? GRN : f.xfeedOpen ? WHT : GREY);
        LinerDisplaysSVA.valve(140 * s, xy, f.xfeedOpen, s);
        LinerGfxSVA.text(tr("fuel.xfeed"), 140 * s, xy - 12 * s, 7.5F * s, WHT, 0);
        // the APU's line from the left wing
        boolean apu = (src & LinerFuel.S_FLOW_APU) != 0;
        LinerGfxSVA.text(lang("sva.liner.sd.apu"), 22 * s, 34 * s, 8 * s, WHT, 0);
        LinerGfxSVA.line(22 * s, 42 * s, 22 * s, 122 * s, 1.2F * s, apu ? GRN : WHT);
        // the pumps (into the engines' lines at y 100: the left pair to ДВ 1, the right pair to ДВ 2, the centre pair one each)
        LinerGfxSVA.line(PX[0] * s, 100 * s, EX[0] * s + 14 * s, 100 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(PX[2] * s, 100 * s, EX[0] * s, 100 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(EX[1] * s - 14 * s, 100 * s, PX[5] * s, 100 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(EX[1] * s, 100 * s, PX[3] * s, 100 * s, 1.2F * s, WHT);
        for (int p = 0; p < LinerFuel.PUMPS; p++) {
            float x = PX[p] * s;
            LinerGfxSVA.line(x, 100 * s, x, 108 * s, 1.2F * s, WHT);
            pumpBox(x, 115 * s, f.runs(p), (f.lowPr & 1 << p) != 0, s);
        }
        // the tanks: a swept outline, the dividers, the quantities (the outer cells small beside)
        float top = 124 * s, bot = 176 * s;
        LinerGfxSVA.line(6 * s, 168 * s, 40 * s, top + 8 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(40 * s, top + 8 * s, 110 * s, top, 1.2F * s, WHT);
        LinerGfxSVA.line(110 * s, top, 170 * s, top, 1.2F * s, WHT);
        LinerGfxSVA.line(170 * s, top, 240 * s, top + 8 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(240 * s, top + 8 * s, 274 * s, 168 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(6 * s, 168 * s, 110 * s, bot, 1.2F * s, WHT);
        LinerGfxSVA.line(110 * s, bot, 170 * s, bot, 1.2F * s, WHT);
        LinerGfxSVA.line(170 * s, bot, 274 * s, 168 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(110 * s, top, 110 * s, bot, 1F * s, WHT);
        LinerGfxSVA.line(170 * s, top, 170 * s, bot, 1F * s, WHT);
        LinerGfxSVA.line(36 * s, top + 10 * s, 30 * s, 166 * s, 0.8F * s, GREY);
        LinerGfxSVA.line(244 * s, top + 10 * s, 250 * s, 166 * s, 0.8F * s, GREY);
        boolean imb = Math.abs(L.tankR - L.tankL) > LinerFuel.IMBALANCE;
        tankText(L.tankL - f.outerL, 72 * s, 146 * s, L.tankL < LinerFuel.LOW_LEVEL || imb, s);
        tankText(L.tankC, 140 * s, 146 * s, false, s);
        tankText(L.tankR - f.outerR, 208 * s, 146 * s, L.tankR < LinerFuel.LOW_LEVEL || imb, s);
        LinerGfxSVA.text(mb(f.outerL), 22 * s, 156 * s, 7 * s, GRN, 0);
        LinerGfxSVA.text(mb(f.outerR), 258 * s, 156 * s, 7 * s, GRN, 0);
        LinerGfxSVA.text(tr("fuel.left"), 72 * s, 160 * s, 7 * s, WHT, 0);
        LinerGfxSVA.text(tr("fuel.ctr"), 140 * s, 160 * s, 7 * s, WHT, 0);
        LinerGfxSVA.text(tr("fuel.right"), 208 * s, 160 * s, 7 * s, WHT, 0);
        if (f.xferL) {
            LinerSdPagesSVA.arrow(36 * s, 136 * s, 3.5F * s, true, GRN, true, s);
        }
        if (f.xferR) {
            LinerSdPagesSVA.arrow(244 * s, 136 * s, 3.5F * s, false, GRN, true, s);
        }
        // temperatures under the wings
        LinerGfxSVA.text(LinerDisplaysSVA.signed(f.tempL) + " C", 60 * s, 186 * s, 7.5F * s, GRN, 0);
        LinerGfxSVA.text(LinerDisplaysSVA.signed(f.tempR) + " C", 220 * s, 186 * s, 7.5F * s, GRN, 0);
        if (f.xferL || f.xferR) {
            LinerGfxSVA.text(tr("fuel.transfer"), 140 * s, 186 * s, 7 * s, GRN, 0, 90 * s);
        } else if ((src & (LinerFuel.S_GRAV_L | LinerFuel.S_GRAV_R)) != 0 && L.anyRunning()) {
            LinerGfxSVA.text(tr("fuel.gravity"), 140 * s, 186 * s, 7 * s, AMB, 0, 90 * s);
        } else if (f.refuel > 0) {
            LinerGfxSVA.text(tr("fuel.refuel"), 140 * s, 186 * s, 7.5F * s, CYN, 0, 90 * s);
        }
        // FOB, the imbalance, FU per engine
        LinerGfxSVA.text(tr("fuel.fob"), 14 * s, 202 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(mb(L.tanks()) + " " + tr("mb"), 200 * s, 202 * s, 9 * s, GRN, 1);
        LinerGfxSVA.text(tr("fuel.imbal"), 14 * s, 216 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(mb(Math.abs(L.tankR - L.tankL)) + " " + tr("mb"), 200 * s, 216 * s, 9 * s, imb ? AMB : GRN, 1);
        LinerGfxSVA.text(tr("fuel.usedEng", mb(f.used[0]), mb(f.used[1])), 14 * s, 230 * s, 7.5F * s, GRN, -1, 250 * s);
        if (L.on(LinerControls.FUEL_MODE)) {
            LinerGfxSVA.text(tr("fuel.manual"), 266 * s, 202 * s, 7 * s, CYN, 1);
        }
    }

    static void tankText(double q, float x, float y, boolean amber, float s) {
        LinerGfxSVA.text(mb(Math.max(0, q)), x, y, 10 * s, amber ? AMB : GRN, 0);
    }

    /** A pump: a square, a bar along when it runs (green), across when not (amber with low pressure, white when simply off). */
    static void pumpBox(float x, float y, boolean run, boolean low, float s) {
        float r = 6 * s;
        int c = run ? GRN : low ? AMB : WHT;
        LinerGfxSVA.rect(x - r, y - r, x + r, y + r, BG);
        LinerGfxSVA.layer();
        LinerGfxSVA.frameRect(x - r, y - r, x + r, y + r, 1.2F * s, c);
        if (run) {
            LinerGfxSVA.line(x, y - r, x, y + r, 1.3F * s, GRN);
        } else {
            LinerGfxSVA.line(x - r, y, x + r, y, 1.3F * s, c);
        }
    }

    // ---------------------------------------------------------------- КОНД

    static void cond(LinerSystems L, float s) {
        LinerGfxSVA.text(tr("cond.temp"), 230 * s, 14 * s, 8 * s, WHT, 1);
        LinerGfxSVA.text("C", 244 * s, 14 * s, 8 * s, CYN, -1);
        // the fuselage: the nose cone, three zones
        float y0 = 60 * s, y1 = 128 * s;
        LinerGfxSVA.line(20 * s, 94 * s, 42 * s, y0, 1.2F * s, WHT);
        LinerGfxSVA.line(20 * s, 94 * s, 42 * s, y1, 1.2F * s, WHT);
        LinerGfxSVA.line(42 * s, y0, 236 * s, y0, 1.2F * s, WHT);
        LinerGfxSVA.line(42 * s, y1, 236 * s, y1, 1.2F * s, WHT);
        LinerGfxSVA.line(236 * s, y0, 262 * s, 80 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(236 * s, y1, 262 * s, 108 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(262 * s, 80 * s, 262 * s, 108 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(88 * s, y0, 88 * s, y1, 1F * s, WHT);
        LinerGfxSVA.line(170 * s, y0, 170 * s, y1, 1F * s, WHT);
        String[] zone = {tr("cond.ck"), tr("cond.fwd"), tr("cond.aft")};
        float[] zx = {58, 129, 210};
        boolean p1 = L.on(LinerControls.PACK1) && L.ductL > 0, p2 = L.on(LinerControls.PACK2) && L.ductR > 0;
        for (int z = 0; z < 3; z++) {
            float x = zx[z] * s;
            LinerGfxSVA.text(zone[z], x, 72 * s, 7.5F * s, WHT, 0, 60 * s);
            LinerGfxSVA.text(String.valueOf(Math.round(L.cabT[z])), x, 92 * s, 12 * s, GRN, 0);
            LinerGfxSVA.text(String.valueOf(Math.round(LinerSystems.CAB_T[z])), x, 110 * s, 8 * s, CYN, 0);
            // the trim air valves into the hot-air line below
            LinerGfxSVA.line(x, y1, x, 150 * s, 1.2F * s, p1 || p2 ? GRN : WHT);
            LinerDisplaysSVA.valve(x, 142 * s, p1 || p2, s);
        }
        LinerGfxSVA.line(zx[0] * s, 150 * s, 236 * s, 150 * s, 1.2F * s, p1 || p2 ? GRN : WHT);
        LinerDisplaysSVA.valve(244 * s, 150 * s, p1 || p2, s);
        LinerGfxSVA.text(tr("cond.hot"), 262 * s, 164 * s, 7 * s, WHT, 1);
        // packs, the cabin altitude and its rate
        for (int i = 0; i < 2; i++) {
            boolean on = L.on(i == 0 ? LinerControls.PACK1 : LinerControls.PACK2), flow = i == 0 ? p1 : p2;
            float x = (i == 0 ? 20 : 150) * s;
            LinerGfxSVA.text(tr("pack", Integer.valueOf(i + 1)), x, 186 * s, 8 * s, WHT, -1);
            LinerGfxSVA.text(lang(on ? "sva.liner.pos.on" : "sva.liner.pos.off"), x + 50 * s, 186 * s, 8 * s, flow ? GRN : on ? AMB : WHT, -1);
        }
        LinerGfxSVA.text(tr("cond.cabAlt"), 20 * s, 204 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(Math.round(L.cabAlt) + " " + tr("m"), 132 * s, 204 * s, 9 * s, GRN, 1);
        LinerGfxSVA.text(tr("cond.vs"), 150 * s, 204 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text((L.cabVs >= 0 ? "+" : "") + LinerGfxSVA.num(L.cabVs, 1) + " " + tr("ms"), 266 * s, 204 * s, 9 * s, GRN, 1);
        LinerGfxSVA.text(tr("cond.note"), 140 * s, 226 * s, 7 * s, GREY, 0, 260 * s);
    }

    // ---------------------------------------------------------------- ДВЕРИ

    static void door(LinerSystems L, float s) {
        LinerGfxSVA.text(tr("door.oxy"), 230 * s, 14 * s, 8 * s, WHT, 1);
        LinerGfxSVA.text("1850 psi", 266 * s, 26 * s, 9 * s, GRN, 1);
        // the fuselage from above
        float y0 = 80 * s, y1 = 150 * s;
        LinerGfxSVA.line(20 * s, 115 * s, 50 * s, y0, 1.2F * s, WHT);
        LinerGfxSVA.line(20 * s, 115 * s, 50 * s, y1, 1.2F * s, WHT);
        LinerGfxSVA.line(50 * s, y0, 196 * s, y0, 1.2F * s, WHT);
        LinerGfxSVA.line(50 * s, y1, 196 * s, y1, 1.2F * s, WHT);
        LinerGfxSVA.line(196 * s, y0, 262 * s, 104 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(196 * s, y1, 262 * s, 126 * s, 1.2F * s, WHT);
        LinerGfxSVA.line(262 * s, 104 * s, 262 * s, 126 * s, 1.2F * s, WHT);
        LinerGfxSVA.text(tr("door.cockpit"), 78 * s, 115 * s, 7.5F * s, WHT, 0);
        boolean open = L.door, stairs = L.door && L.onGround;
        // L1 (left = top of the plan), R1, the overwing exits, aft, cargo
        doorRect(56 * s, y0, 18 * s, open, s);
        LinerGfxSVA.text(tr("door.l1"), 65 * s, y0 - 9 * s, 7.5F * s, open ? AMB : GRN, 0);
        doorRect(56 * s, y1, 18 * s, false, s);
        LinerGfxSVA.text(tr("door.r1"), 65 * s, y1 + 10 * s, 7.5F * s, GRN, 0);
        doorRect(124 * s, y0, 10 * s, false, s);
        doorRect(138 * s, y0, 10 * s, false, s);
        doorRect(124 * s, y1, 10 * s, false, s);
        doorRect(138 * s, y1, 10 * s, false, s);
        LinerGfxSVA.text(tr("door.emer"), 136 * s, y0 - 9 * s, 7 * s, GRN, 0);
        doorRect(176 * s, y1, 22 * s, false, s);
        LinerGfxSVA.text(tr("door.cargo"), 187 * s, y1 + 10 * s, 7.5F * s, GRN, 0);
        doorRect(198 * s, y0 + 4 * s, 12 * s, false, s);
        LinerGfxSVA.text(tr("door.aft"), 210 * s, y0 - 9 * s, 7 * s, GRN, 0);
        if (stairs) {
            LinerGfxSVA.text(tr("door.stairs"), 60 * s, 52 * s, 8 * s, GRN, 0);
        }
        boolean armed = !open && (L.anyRunning() || !L.onGround);
        LinerGfxSVA.text(tr(armed ? "door.slidesArmed" : "door.slides"), 140 * s, 176 * s, 7.5F * s, armed ? GRN : WHT, 0);
        if (open) {
            LinerGfxSVA.text(lang("sva.liner.memo.door"), 140 * s, 196 * s, 9 * s, AMB, 0);
        }
        LinerGfxSVA.text(tr("door.cabin"), 20 * s, 222 * s, 8 * s, WHT, -1);
        boolean pressed = !L.onGround && !open && L.cabAlt > 0;
        LinerGfxSVA.text(pressed ? tr("door.pressed", Long.valueOf(Math.round(L.cabAlt))) : tr("door.noPress"), 90 * s, 222 * s, 8 * s,
                pressed ? GRN : GRN, -1, 180 * s);
    }

    /** A door on the plan: a small rectangle on the skin line, amber filled when open. */
    static void doorRect(float x, float y, float w, boolean open, float s) {
        float hh = 3 * s;
        if (open) {
            LinerGfxSVA.rect(x, y - hh, x + w, y + hh, AMB);
        } else {
            LinerGfxSVA.rect(x, y - hh, x + w, y + hh, BG);
            LinerGfxSVA.layer();
            LinerGfxSVA.frameRect(x, y - hh, x + w, y + hh, 1F * s, GRN);
        }
    }

    // ---------------------------------------------------------------- СТАТУС

    static void status(LinerSystems L, float s) {
        LinerEcam ec = L.ecam;
        int lim = ec.limits(), inop = ec.inop();
        float y = 36;
        if (lim == 0 && inop == 0 && ec.cancelled == 0) {
            LinerGfxSVA.text(tr("sts.normal"), 140 * s, 110 * s, 11 * s, GRN, 0);
            return;
        }
        for (int i = 0; i < LinerEcam.LIM_KEYS.length; i++) {
            if ((lim & 1 << i) != 0 && y < 232) {
                LinerGfxSVA.text(lang("sva.liner.lim." + LinerEcam.LIM_KEYS[i]), 12 * s, y * s, 8.5F * s, i == 0 ? AMB : CYN, -1, 160 * s);
                y += 11;
            }
        }
        // the inoperative systems on the right
        float ry = 36;
        LinerGfxSVA.line(178 * s, 28 * s, 178 * s, 236 * s, 1F * s, WHT);
        LinerGfxSVA.text(tr("inop"), 186 * s, ry * s, 8 * s, WHT, -1);
        ry += 11;
        for (int i = 0; i < LinerEcam.INOP_KEYS.length; i++) {
            if ((inop & 1 << i) != 0 && ry < 232) {
                LinerGfxSVA.text(lang("sva.liner.inop." + LinerEcam.INOP_KEYS[i]), 186 * s, ry * s, 8 * s, AMB, -1, 88 * s);
                ry += 11;
            }
        }
        if (ec.cancelled != 0) {
            y += 4;
            LinerGfxSVA.text(tr("sts.cancelled"), 12 * s, y * s, 8 * s, WHT, -1, 160 * s);
            y += 11;
            for (int id = 0; id < LinerEcam.N && y < 232; id++) {
                if ((ec.cancelled & 1L << id) != 0) {
                    LinerGfxSVA.text(lang("sva.liner.msg." + LinerEcam.KEYS[id]), 12 * s, y * s, 8 * s, AMB, -1, 160 * s);
                    y += 11;
                }
            }
        }
    }

    // ---------------------------------------------------------------- the checklists

    static void checklist(LinerSystems L, float s) {
        int list = LinerChecklist.current(L);
        int done = LinerChecklist.doneBits(list, L);
        String[] items = LinerChecklist.ITEMS[list];
        String title = lang("sva.liner.chk." + LinerChecklist.LIST_KEYS[list]);
        LinerGfxSVA.text(title, 14 * s, 38 * s, 10 * s, WHT, -1);
        LinerGfxSVA.line(14 * s, 44 * s, (16 + 6.2F * title.length()) * s, 44 * s, 1F * s, WHT);
        LinerGfxSVA.text((list + 1) + " / " + LinerChecklist.LISTS, 266 * s, 38 * s, 8 * s, CYN, 1);
        float y = 60;
        int n = 0;
        for (int i = 0; i < items.length; i++) {
            boolean ok = (done & 1 << i) != 0;
            n += ok ? 1 : 0;
            LinerEwdSVA.action(lang("sva.liner.chk." + items[i]), 24 * s, 262 * s, y * s, s, ok ? GRN : CYN, ok);
            y += 18;
        }
        boolean all = n == items.length;
        LinerGfxSVA.text(all ? tr("chk.complete") : tr("chk.count", Integer.valueOf(n), Integer.valueOf(items.length)), 140 * s, 214 * s, 9 * s,
                all ? GRN : WHT, 0);
        LinerGfxSVA.text(tr("chk.hint"), 140 * s, 232 * s, 6.5F * s, GREY, 0, 260 * s);
    }

    // ---------------------------------------------------------------- the cruise page

    static void cruise(LinerSystems L, float s) {
        LinerGfxSVA.text(tr("crz.eng"), 14 * s, 36 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(tr("fuel.used"), 140 * s, 50 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("eng.oil"), 140 * s, 72 * s, 8 * s, WHT, 0);
        LinerGfxSVA.text(tr("eng.vib"), 140 * s, 92 * s, 8 * s, WHT, 0);
        for (int i = 0; i < 2; i++) {
            float x = (i == 0 ? 60 : 220) * s;
            LinerSystems.Eng g = L.eng[i];
            LinerGfxSVA.text(mb(L.fuel.used[i]), x, 50 * s, 9 * s, GRN, 0);
            LinerGfxSVA.text(LinerGfxSVA.num(15.5 - (g.running() ? 0.3 : 0), 1), x, 72 * s, 9 * s, GRN, 0);
            LinerGfxSVA.text(LinerGfxSVA.num(g.running() ? 0.8 + 0.1 * i : 0, 1), x, 92 * s, 9 * s, GRN, 0);
        }
        LinerGfxSVA.text(mb(L.fuel.usedTotal()) + " " + tr("mb"), 140 * s, 62 * s, 7.5F * s, GRN, 0);
        LinerGfxSVA.line(10 * s, 110 * s, 270 * s, 110 * s, 1F * s, GREY);
        LinerGfxSVA.text(tr("crz.air"), 14 * s, 124 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(tr("cond.cabAlt"), 20 * s, 144 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text(Math.round(L.cabAlt) + " " + tr("m"), 260 * s, 144 * s, 9 * s, GRN, 1);
        LinerGfxSVA.text(tr("cond.vs"), 20 * s, 160 * s, 8 * s, WHT, -1);
        LinerGfxSVA.text((L.cabVs >= 0 ? "+" : "") + LinerGfxSVA.num(L.cabVs, 1) + " " + tr("ms"), 260 * s, 160 * s, 9 * s, GRN, 1);
        String[] zone = {tr("cond.ck"), tr("cond.fwd"), tr("cond.aft")};
        for (int z = 0; z < 3; z++) {
            float x = (50 + z * 90) * s;
            LinerGfxSVA.text(zone[z], x, 186 * s, 7.5F * s, WHT, 0, 80 * s);
            LinerGfxSVA.text(Math.round(L.cabT[z]) + " C", x, 202 * s, 9 * s, GRN, 0);
        }
        LinerGfxSVA.text(tr("fuel.fob") + "  " + mb(L.tanks()) + " " + tr("mb"), 140 * s, 226 * s, 8 * s, GRN, 0, 260 * s);
    }
}
