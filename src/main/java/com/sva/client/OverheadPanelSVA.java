package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.SystemsMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.FontRenderer;

/**
 * Aircraft round 2, stage D2 (★17, mock-up docs/mockups/veh_air3_4_cockpit.png, the big panels A / B): the light plane's
 * overhead switch panel, painted on its 'ovhd' face (veh_real10.py; CockpitLayout.OVERHEAD) in CockpitInstrumentsSVA's
 * GL state and frame. Two rows, as the 3D view of the mock-up: near the pilot ПИТАНИЕ (АКБ, ГЕН - under red guards,
 * closed while off), ОБОГРЕВ, ДВИГАТЕЛЬ (the ГОТОВ lamp, the guarded red ПУСК button - lit while the starter turns);
 * farther СВЕТ (НАВ. ОГНИ, СТРОБ, МАЯЧОК, ПОС. ФАРЫ) and the four failure annunciators (ОТКАЗ ДВИГ., ЗАКР. ЗАКЛИН.,
 * ПРИБОР, ВНИМАНИЕ - CockpitLamps roles). Each toggle is drawn with its lever up (ВКЛ) or down (ОТКЛ) - the switches
 * themselves are set from the «Техника» menu («Пульт» row) and «Запуск двигателя» (EntityVehicleSVA.A_SWITCH /
 * A_ENGINE). A lamp over each switch: lit while the switch is on and the board has power (ГЕН: while it charges); the
 * startup's lamp test lights them all. The painted labels are not lit (they read by day, at night by the glow).
 * <p>
 * Cockpit wave (plan-next §5 / §6, veh_real12.py): the light plane's overhead console is gone - its switches are on the
 * panel on the left wall ('swpanel', turned toward the eye: ПИТАНИЕ / ОБОГРЕВ / ДВИГАТЕЛЬ over СВЕТ - wall()); the
 * helicopter got the big overhead panel ('ovhd' 3.0 x 5.0 under the roof - heli()): the six status lamps, ПИТАНИЕ (АКБ,
 * ГЕН under red guards), ДВИГАТЕЛЬ (ЗАПУСК under a red guard + ГОТОВ), ТОПЛИВО Н2 (КРАН, НАСОС), ОБОГРЕВ / ПОС, СВЕТ
 * (НАВ. ОГНИ, СТРОБ, МАЯЧОК, ФАРА, ПРОЖЕКТОР ВНИЗ), ГРУЗ (ЗАМОК ТРОСА, СБРОС under a guard) and the transponder. There is
 * no mouse picking in the cockpits: the plane's switches are still set from the «Техника» menu's «Пульт» row; the
 * helicopter has no board systems (SystemsMath.applies) - its panel SHOWS the state of what exists (the board powered,
 * the rotor, the nav lights' config, the headlights, a hitched trailer) and draws the rest (КРАН / НАСОС H2, ОБОГРЕВ,
 * ПРОЖЕКТОР, СБРОС) as visual items until their waves give them a game side.
 * <p>
 * Stage D5: the ИНС switch (SystemsMath.SW_INS) under an A320 ADIRS-style annunciator - ВЫСТ. (white: aligning, flashing
 * after a restart) over ОТКАЗ (amber: on without the board's power) - in a НАВИГАЦИЯ group on all three panels; on the
 * helicopter it is a REAL switch (the menu's «Пульт» row sets it), the rest of its panel stays display-only.
 */
@SideOnly(Side.CLIENT)
final class OverheadPanelSVA {

    private static final int PANEL = 0x2A2C32, FRAME = 0x55585F, LABEL = 0xC9CCD3, DIM = 0x3A3C42, GUARD = 0xC8281E;
    private static final float Z1 = CockpitInstrumentsSVA.LIFT_MARK - CockpitInstrumentsSVA.LIFT_FACE,
            Z2 = CockpitInstrumentsSVA.LIFT_NEEDLE - CockpitInstrumentsSVA.LIFT_FACE, Z3 = CockpitInstrumentsSVA.LIFT_HUB - CockpitInstrumentsSVA.LIFT_FACE;
    /** The slots across the face (the mock-up's 8 columns) and where each switch sits: {switch, row (0 near / 1 far), slot}. */
    private static final float SLOTS = 8F;
    private static final float[][] CELLS = {{SystemsMath.SW_BAT, 0, 0.7F}, {SystemsMath.SW_GEN, 0, 1.7F}, {SystemsMath.SW_HEAT, 0, 2.95F},
            {SystemsMath.SW_INS, 0, 4.15F},
            {SystemsMath.SW_NAV, 1, 0.55F}, {SystemsMath.SW_STROBE, 1, 1.4F}, {SystemsMath.SW_BEACON, 1, 2.25F}, {SystemsMath.SW_LAMP, 1, 3.1F}};

    private OverheadPanelSVA() {
    }

    /** Draws the panels: the plane's wall panel, the heli's overhead one, D2's overhead console (config vehicles.avionics). */
    static void draw(FontRenderer fr, CockpitLayout l, EntityVehicleSVA v, VehicleType t) {
        if (!com.sva.ConfigSVA.avionics) {
            return;
        }
        CockpitLayout.Screen wall = l.panel("swpanel");
        if (wall != null && SystemsMath.applies(t)) {
            wall(fr, wall, v, t);
        }
        CockpitLayout.Screen s = l.panel(CockpitLayout.OVERHEAD);
        if (s != null && t.movement == com.sva.vehicle.Movement.HELI) {
            heli(fr, s, v);
        } else if (s != null && SystemsMath.applies(t)) {
            d2(fr, s, v, t);
        }
    }

    /** Round 2, D2: the light plane's old overhead console (two rows, eight slots). */
    private static void d2(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v, VehicleType t) {
        int phase = CockpitInstrumentsSVA.phase;
        boolean board = v.powered(), dark = phase == CockpitLamps.PH_DARK, test = phase == CockpitLamps.PH_TEST;
        long ms = System.currentTimeMillis();
        CockpitInstrumentsSVA.frame(s.c, s.n, s.up, s.right, s.front + CockpitInstrumentsSVA.LIFT_FACE);
        float w = s.w, h = s.h;
        if (CockpitInstrumentsSVA.night > 0F && board) {
            CockpitInstrumentsSVA.glowRect(w, h, 0.25F * CockpitInstrumentsSVA.night);
        }
        col(PANEL);
        CockpitInstrumentsSVA.rect(-w / 2, -h / 2, w / 2, h / 2, 0F);
        // the group frames and their titles (near row at the top of the face: its "up" points back toward the pilot)
        group(fr, s, 0, 0.15F, 2.25F, "sva.sys.group.power");
        group(fr, s, 0, 2.45F, 3.45F, "sva.sys.group.heat");
        group(fr, s, 0, 3.65F, 4.65F, "sva.sys.group.nav");                  // D5: ИНС
        group(fr, s, 0, 4.85F, 7.85F, "sva.sys.group.engine");
        group(fr, s, 1, 0.1F, 3.55F, "sva.sys.group.lights");
        int sw = v.switches();
        for (float[] c : CELLS) {
            int i = (int) c[0];
            boolean on = SystemsMath.on(sw, i);
            boolean lampOn = i == SystemsMath.SW_GEN ? on && board && v.getEnergy() > 0 : on && board;
            if (i == SystemsMath.SW_HEAT) {
                lampOn = on && board;                               // amber: armed (it heats in the cold - HUD «обогрев»)
            }
            float x = slotX(w, c[2]), y = rowY(h, (int) c[1]);
            if (i == SystemsMath.SW_INS) {
                insAnn(fr, x, y + h * 0.13F, w / SLOTS * 0.8F, h * 0.12F, insAlignLit(v), insFaultLit(v), dark, test);
            } else {
                lamp(x, y + h * 0.13F, h * 0.04F, SystemsMath.SW_COLOUR[i], !dark && (test || lampOn));
            }
            toggle(x, y - h * 0.02F, h, on, SystemsMath.GUARDED[i]);
            CockpitInstrumentsSVA.text(fr, Lang.tr("sva.sys.sw." + SystemsMath.SW_KEYS[i] + ".short"), x, y - h * (c[1] == 0 ? 0.165F : 0.19F), h * 0.055F,
                    w / SLOTS * 0.84F, CockpitInstrumentsSVA.tint(LABEL));
        }
        // ДВИГАТЕЛЬ: the ГОТОВ lamp (green running, amber blinking while it starts) and the guarded ПУСК button
        int es = v.engineState();
        boolean gated = v.engineGated(t);
        boolean ready = gated ? es == SystemsMath.RUN : v.isLifting();
        boolean starting = es == SystemsMath.CRANK || es == SystemsMath.WARM;
        float ex = slotX(w, 5.6F), ey = rowY(h, 0);
        boolean blink = (ms / 300) % 2 == 0;
        lamp(ex, ey + h * 0.08F, h * 0.05F, starting ? CockpitLamps.AMBER : CockpitLamps.GREEN,
                board && !dark && (test || ready || starting && blink));
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.sys.ready"), ex, ey - h * 0.04F, h * 0.055F, w / SLOTS, CockpitInstrumentsSVA.tint(LABEL));
        float bx = slotX(w, 6.75F), by = ey + h * 0.02F, br = h * 0.11F;
        col(GUARD);
        CockpitInstrumentsSVA.disc(bx, by, br * 1.25F, 0F);                         // the guard ring
        col(0x141416);
        CockpitInstrumentsSVA.disc(bx, by, br * 1.05F, Z1 * 0.3F);
        boolean pressed = es == SystemsMath.CRANK;
        col(pressed ? 0xFF5A3A : 0x9A1A14);
        CockpitInstrumentsSVA.disc(bx, by, br, Z1 * 0.6F);
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.sys.start"), bx, by, h * 0.05F, br * 1.6F, 0xF4E8E0);
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.sys.startLabel"), bx, ey - h * 0.165F, h * 0.055F, w / SLOTS * 1.4F, CockpitInstrumentsSVA.tint(LABEL));
        // the failure annunciators: a 2 x 2 block right of the lights
        int[] roles = {CockpitLamps.ENG_FAIL, CockpitLamps.FLAP_JAM, CockpitLamps.INSTR_FAIL, CockpitLamps.CAUTION};
        float ax0 = slotX(w, 3.75F), ax1 = w / 2 - w * 0.02F, ay1 = -h * 0.06F, ay0 = -h / 2 + h * 0.03F;
        float cw = (ax1 - ax0) / 2, chh = (ay1 - ay0) / 2;
        for (int k = 0; k < roles.length; k++) {
            float x0 = ax0 + (k % 2) * cw, y1 = ay1 - (k / 2) * chh;
            int role = roles[k];
            int st = CockpitLamps.state(role, CockpitInstrumentsSVA.SNAP);
            boolean lit = !dark && (test && board || CockpitLamps.lit(st, ms));
            int c = CockpitLamps.colour(role);
            col(0x0C0C0E);
            CockpitInstrumentsSVA.rect(x0 + cw * 0.04F, y1 - chh * 0.92F, x0 + cw * 0.96F, y1 - chh * 0.08F, Z1 * 0.3F);
            col(lit ? c : CockpitLamps.mix(c, 0x101012, 0.82F));
            CockpitInstrumentsSVA.rect(x0 + cw * 0.08F, y1 - chh * 0.86F, x0 + cw * 0.92F, y1 - chh * 0.14F, Z1 * 0.6F);
            CockpitInstrumentsSVA.text(fr, Lang.tr("sva.cockpit.lamp." + CockpitLamps.KEYS[role]), x0 + cw / 2, y1 - chh / 2, chh * 0.42F, cw * 0.78F,
                    lit ? 0x1A1206 : CockpitLamps.mix(c, 0x101012, 0.55F));
        }
        GL11.glPopMatrix();
    }

    private static float slotX(float w, float slot) {
        return -w / 2 + w * slot / SLOTS;
    }

    /** A row's centre line: the near row (0) in the upper half, the far one (1) in the lower. */
    private static float rowY(float h, int row) {
        return row == 0 ? h * 0.2F : -h * 0.27F;
    }

    private static void col(int rgb) {
        CockpitInstrumentsSVA.glColor(rgb);
    }

    /** A group's thin frame across slots a..b of a row, its title on the top edge. */
    private static void group(FontRenderer fr, CockpitLayout.Screen s, int row, float a, float b, String key) {
        float w = s.w, h = s.h, x0 = slotX(w, a), x1 = slotX(w, b), yc = rowY(h, row);
        float y0 = yc - h * 0.215F, y1 = yc + h * (row == 0 ? 0.27F : 0.215F), t = h * 0.008F;
        col(FRAME);
        CockpitInstrumentsSVA.rect(x0, y0, x1, y0 + t, Z1 * 0.2F);
        CockpitInstrumentsSVA.rect(x0, y1 - t, x1, y1, Z1 * 0.2F);
        CockpitInstrumentsSVA.rect(x0, y0, x0 + t, y1, Z1 * 0.2F);
        CockpitInstrumentsSVA.rect(x1 - t, y0, x1, y1, Z1 * 0.2F);
        String title = Lang.tr(key);
        float th = h * 0.055F, tw = Math.min((x1 - x0) * 0.8F, HiFontSVA.width(fr, title) * th / 8F);
        col(PANEL);
        CockpitInstrumentsSVA.rect(-tw / 2 - th * 0.4F + (x0 + x1) / 2, y1 - th * 0.7F, tw / 2 + th * 0.4F + (x0 + x1) / 2, y1 + th * 0.7F, Z1 * 0.4F);
        CockpitInstrumentsSVA.text(fr, title, (x0 + x1) / 2, y1, th, (x1 - x0) * 0.8F, CockpitInstrumentsSVA.tint(LABEL));
    }

    /** A round lamp with a dark bezel; lit: its colour (+ a soft glow at night), else a dark glass of it. */
    private static void lamp(float x, float y, float r, int rgb, boolean lit) {
        col(0x101012);
        CockpitInstrumentsSVA.disc(x, y, r * 1.3F, Z1 * 0.3F);
        col(lit ? rgb : CockpitLamps.mix(rgb, 0x0A0A0C, 0.78F));
        CockpitInstrumentsSVA.disc(x, y, r, Z1 * 0.6F);
        if (lit) {
            col(CockpitLamps.mix(rgb, 0xFFFFFF, 0.6F));
            CockpitInstrumentsSVA.disc(x - r * 0.3F, y + r * 0.3F, r * 0.3F, Z1 * 0.8F);    // the highlight
        }
    }

    /**
     * A toggle: a dark round base with a chrome nut, the lever as a tapered bar with a ball tip - up (toward the label
     * «ВКЛ») when on, down when off; ВКЛ / ОТКЛ marks beside it. Guarded: a red guard - closed over the lever while off,
     * flipped up (a red bar above) while on.
     */
    private static void toggle(float x, float y, float h, boolean on, boolean guarded) {
        float r = h * 0.05F, len = h * 0.085F, dir = on ? 1F : -1F;
        col(0x0E0E10);
        CockpitInstrumentsSVA.disc(x, y, r * 1.35F, Z1 * 0.3F);
        col(0x8C9098);
        CockpitInstrumentsSVA.disc(x, y, r * 0.9F, Z1 * 0.5F);
        col(0x5A5E66);
        CockpitInstrumentsSVA.disc(x, y, r * 0.55F, Z1 * 0.7F);
        // the lever (seen from below: it leans toward its end - drawn as a bar from the nut)
        col(0xC6CAD2);
        CockpitInstrumentsSVA.poly(new float[][] {{x - r * 0.32F, y}, {x + r * 0.32F, y}, {x + r * 0.2F, y + dir * len}, {x - r * 0.2F, y + dir * len}}, Z2);
        col(0xE8EAEE);
        CockpitInstrumentsSVA.disc(x, y + dir * len, r * 0.42F, Z2 + 0.0001F);
        col(DIM);
        CockpitInstrumentsSVA.rect(x + r * 1.6F, y + len * 0.7F, x + r * 2.6F, y + len * 0.7F + h * 0.008F, Z1 * 0.4F);     // ВКЛ mark
        CockpitInstrumentsSVA.rect(x + r * 1.6F, y - len * 0.7F, x + r * 2.6F, y - len * 0.7F + h * 0.008F, Z1 * 0.4F);     // ОТКЛ mark
        if (guarded) {
            col(GUARD);
            float gw = r * 1.7F, t = h * 0.012F;
            if (on) {
                CockpitInstrumentsSVA.rect(x - gw, y + len + r * 0.6F, x + gw, y + len + r * 0.6F + t * 2.2F, Z3);   // flipped up
                CockpitInstrumentsSVA.rect(x - gw, y - len * 0.9F, x - gw + t, y + len + r * 0.6F, Z3);
                CockpitInstrumentsSVA.rect(x + gw - t, y - len * 0.9F, x + gw, y + len + r * 0.6F, Z3);
            } else {
                GL11.glColor4f(0.78F, 0.16F, 0.12F, 1F);
                CockpitInstrumentsSVA.rect(x - gw, y - len - r * 0.6F, x + gw, y + len * 0.9F, Z3);                     // closed over it
                col(0x8E1C14);
                for (int k = 0; k < 3; k++) {
                    float yy = y - len * 0.6F + k * len * 0.55F;
                    CockpitInstrumentsSVA.rect(x - gw * 0.8F, yy, x + gw * 0.8F, yy + t, Z3 + 0.0001F);
                }
            }
        }
    }

    // ---------------------------------------------------------------- cockpit wave: the switch panels as rows of groups

    /**
     * A panel item: a toggle (guarded or not), or a guarded push button; its lamp (colour, lit) and its label. D5: INS -
     * a toggle under the A320-style ВЫСТ. / ОТКАЗ annunciator (lampOn = ВЫСТ. lit, pressed = ОТКАЗ lit).
     */
    private static final int TOG = 0, GTOG = 1, BTN = 2, INS = 3;

    private static final class Item {
        final int kind;
        final String label;
        final boolean on;
        final int lamp;
        final boolean lampOn, pressed;

        Item(int kind, String label, boolean on, int lamp, boolean lampOn, boolean pressed) {
            this.kind = kind;
            this.label = label;
            this.on = on;
            this.lamp = lamp;
            this.lampOn = lampOn;
            this.pressed = pressed;
        }
    }

    private static Item tog(String key, boolean on, int lamp, boolean lampOn) {
        return new Item(TOG, Lang.tr(key), on, lamp, lampOn, false);
    }

    private static Item gtog(String key, boolean on, int lamp, boolean lampOn) {
        return new Item(GTOG, Lang.tr(key), on, lamp, lampOn, false);
    }

    private static Item btn(String key, int lamp, boolean lampOn, boolean pressed) {
        return new Item(BTN, Lang.tr(key), false, lamp, lampOn, pressed);
    }

    /** D5: the ИНС switch with its annunciator. */
    private static Item insTog(EntityVehicleSVA v) {
        return new Item(INS, Lang.tr("sva.sys.sw.ins.short"), v.switchOn(SystemsMath.SW_INS), 0, insAlignLit(v), insFaultLit(v));
    }

    /**
     * D5, the ИНС annunciator's ВЫСТ. (A320 «ALIGN», white): steady while aligning, flashing for a while after the
     * alignment started over (the aircraft moved), out when aligned.
     */
    static boolean insAlignLit(EntityVehicleSVA v) {
        return v.insStatus() == SystemsMath.INS_ALIGN && (!v.insRestarted() || (System.currentTimeMillis() / 250) % 2 == 0);
    }

    /**
     * D5, its ОТКАЗ (A320 «FAULT», amber): switched on but without the board's power - lit while the battery bus still
     * feeds the lamps (АКБ on and charged, the main cells empty); with АКБ off nothing lights at all.
     */
    static boolean insFaultLit(EntityVehicleSVA v) {
        return v.insStatus() == SystemsMath.INS_FAULT && v.boardOn() && !v.isWrecked();
    }

    /** D5: the two-segment annunciator (ВЫСТ. over ОТКАЗ) centred at x, y, w x h. */
    private static void insAnn(FontRenderer fr, float x, float y, float w, float h, boolean align, boolean fault, boolean dark, boolean test) {
        col(0x0C0C0E);
        CockpitInstrumentsSVA.rect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, Z1 * 0.3F);
        insSeg(fr, x, y + h / 4, w, h / 2, "sva.ins.lamp.align", SystemsMath.SW_COLOUR[SystemsMath.SW_INS], !dark && (test || align));
        insSeg(fr, x, y - h / 4, w, h / 2, "sva.ins.lamp.fault", CockpitLamps.AMBER, !dark && (test || fault));
    }

    /** One segment: dark glass with its word in the lamp's colour; lit - the A320 way, the word glows on a dim face. */
    private static void insSeg(FontRenderer fr, float x, float y, float w, float h, String key, int rgb, boolean lit) {
        col(lit ? CockpitLamps.mix(rgb, 0x101012, 0.7F) : 0x131316);
        CockpitInstrumentsSVA.rect(x - w * 0.46F, y - h * 0.42F, x + w * 0.46F, y + h * 0.42F, Z1 * 0.6F);
        CockpitInstrumentsSVA.text(fr, Lang.tr(key), x, y, h * 0.55F, w * 0.86F, lit ? rgb : CockpitLamps.mix(rgb, 0x101012, 0.72F));
    }

    /**
     * One row of groups between y0 and y1 across x0..x1 (concepts.rows_switches / cab2_art Canvas.switches: each group's
     * width by its item count): a thin frame with the title on its top edge, per item a lamp, the switch and the label.
     */
    private static void row(FontRenderer fr, float x0, float x1, float y0, float y1, String[] titles, Item[][] groups, boolean dark, boolean test) {
        int n = 0;
        for (Item[] g : groups) {
            n += g.length;
        }
        float gap = (x1 - x0) * 0.025F, cw = (x1 - x0 - gap * (groups.length - 1)) / Math.max(1, n), ch = y1 - y0;
        float x = x0;
        for (int gi = 0; gi < groups.length; gi++) {
            Item[] g = groups[gi];
            float gx1 = x + cw * g.length;
            frame(fr, x, y0, gx1, y1, Lang.tr(titles[gi]), ch * 0.1F);
            for (int i = 0; i < g.length; i++) {
                Item it = g[i];
                float cx = x + cw * (i + 0.5F);
                float lampY = y1 - ch * 0.22F, swY = y0 + ch * 0.52F, labY = y0 + ch * 0.15F;
                if (it.kind == INS) {
                    insAnn(fr, cx, lampY, cw * 0.86F, ch * 0.2F, it.lampOn, it.pressed, dark, test);
                } else if (it.lamp != 0) {
                    lamp(cx, lampY, ch * 0.055F, it.lamp, !dark && (test || it.lampOn));
                }
                if (it.kind == BTN) {
                    float br = Math.min(ch * 0.2F, cw * 0.32F);
                    col(GUARD);
                    CockpitInstrumentsSVA.disc(cx, swY, br * 1.25F, 0F);
                    col(0x141416);
                    CockpitInstrumentsSVA.disc(cx, swY, br * 1.05F, Z1 * 0.3F);
                    col(it.pressed ? 0xFF5A3A : 0x9A1A14);
                    CockpitInstrumentsSVA.disc(cx, swY, br, Z1 * 0.6F);
                } else {
                    toggle(cx, swY, ch * 2.0F, it.on, it.kind == GTOG);
                }
                CockpitInstrumentsSVA.text(fr, it.label, cx, labY, ch * 0.1F, cw * 0.92F, CockpitInstrumentsSVA.tint(LABEL));
            }
            x = gx1 + gap;
        }
    }

    /** A group's thin frame, its title on the top edge over a panel-coloured gap. */
    private static void frame(FontRenderer fr, float x0, float y0, float x1, float y1, String title, float th) {
        float t = th * 0.12F;
        col(FRAME);
        CockpitInstrumentsSVA.rect(x0, y0, x1, y0 + t, Z1 * 0.2F);
        CockpitInstrumentsSVA.rect(x0, y1 - t, x1, y1, Z1 * 0.2F);
        CockpitInstrumentsSVA.rect(x0, y0, x0 + t, y1, Z1 * 0.2F);
        CockpitInstrumentsSVA.rect(x1 - t, y0, x1, y1, Z1 * 0.2F);
        if (title.length() == 0) {
            return;
        }
        float tw = Math.min((x1 - x0) * 0.8F, HiFontSVA.width(fr, title) * th / 8F);
        col(PANEL);
        float xm = (x0 + x1) / 2;
        CockpitInstrumentsSVA.rect(xm - tw / 2 - th * 0.4F, y1 - th * 0.7F, xm + tw / 2 + th * 0.4F, y1 + th * 0.7F, Z1 * 0.4F);
        CockpitInstrumentsSVA.text(fr, title, xm, y1, th, (x1 - x0) * 0.8F, CockpitInstrumentsSVA.tint(LABEL));
    }

    /** The panel's face: the frame, the night glow, the dark plate. Leaves the matrix pushed (rotated by `deg`). */
    private static void plate(CockpitLayout.Screen s, boolean board, float deg) {
        CockpitInstrumentsSVA.frame(s.c, s.n, s.up, s.right, s.front + CockpitInstrumentsSVA.LIFT_FACE);
        if (deg != 0F) {
            GL11.glRotatef(deg, 0F, 0F, 1F);
        }
        if (CockpitInstrumentsSVA.night > 0F && board) {
            CockpitInstrumentsSVA.glowRect(s.w, s.h, 0.25F * CockpitInstrumentsSVA.night);
        }
        col(PANEL);
        CockpitInstrumentsSVA.rect(-s.w / 2, -s.h / 2, s.w / 2, s.h / 2, 0F);
    }

    /**
     * The light plane's wall panel (concept C / C+: the left wall forward of the yoke's horn, turned to the eye): ПИТАНИЕ
     * (АКБ, ГЕН under guards), ОБОГРЕВ, ДВИГАТЕЛЬ (ГОТОВ + ПУСК) over СВЕТ (НАВ. ОГНИ, СТРОБ, МАЯЧОК, ПОС. ФАРЫ) - the D2
     * switches (SystemsMath.SW_*), set from the «Техника» menu's «Пульт» row.
     */
    private static void wall(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v, VehicleType t) {
        boolean board = v.powered();
        int phase = CockpitInstrumentsSVA.phase;
        boolean dark = phase == CockpitLamps.PH_DARK, test = phase == CockpitLamps.PH_TEST && board;
        plate(s, board, 0F);
        float w = s.w, h = s.h, m = h * 0.04F;
        int sw = v.switches();
        boolean gen = SystemsMath.on(sw, SystemsMath.SW_GEN) && board && v.getEnergy() > 0;
        boolean bat = SystemsMath.on(sw, SystemsMath.SW_BAT), heat = SystemsMath.on(sw, SystemsMath.SW_HEAT);
        int es = v.engineState();
        boolean starting = es == SystemsMath.CRANK || es == SystemsMath.WARM, ready = v.engineGated(t) ? es == SystemsMath.RUN : v.isLifting();
        boolean blink = (System.currentTimeMillis() / 300) % 2 == 0;
        Item[][] r1 = {{gtog("sva.sys.sw.bat.short", bat, SystemsMath.SW_COLOUR[0], bat && board),
                gtog("sva.sys.sw.gen.short", SystemsMath.on(sw, SystemsMath.SW_GEN), SystemsMath.SW_COLOUR[1], gen)},
                {tog("sva.sys.sw.heat.short", heat, SystemsMath.SW_COLOUR[6], heat && board)},
                {btn("sva.sys.start", starting ? CockpitLamps.AMBER : CockpitLamps.GREEN, board && (ready || starting && blink), es == SystemsMath.CRANK)}};
        Item[][] r2 = {{lightTog(sw, SystemsMath.SW_NAV, board), lightTog(sw, SystemsMath.SW_STROBE, board), lightTog(sw, SystemsMath.SW_BEACON, board),
                lightTog(sw, SystemsMath.SW_LAMP, board)}, {insTog(v)}};
        float rh = (h - 3 * m) / 2;
        row(fr, -w / 2 + m, w / 2 - m, h / 2 - m - rh, h / 2 - m, new String[] {"sva.sys.group.power", "sva.sys.group.heat", "sva.sys.group.engine"}, r1, dark, test);
        row(fr, -w / 2 + m, w / 2 - m, -h / 2 + m, -h / 2 + m + rh, new String[] {"sva.sys.group.lights", "sva.sys.group.nav"}, r2, dark, test);
        GL11.glPopMatrix();
    }

    private static Item lightTog(int sw, int i, boolean board) {
        boolean on = SystemsMath.on(sw, i);
        return tog("sva.sys.sw." + SystemsMath.SW_KEYS[i] + ".short", on, SystemsMath.SW_COLOUR[i], on && board);
    }

    /**
     * The helicopter's overhead panel (heli_c2.overhead_console, 3.0 x 5.0; its drawing "up" is the pilot's side - the
     * layout's frame is turned 180 deg: CockpitLayout lays a ceiling face's up AWAY from the eye). Top -> bottom: the title,
     * the six status lamps, ПИТАНИЕ + ДВИГАТЕЛЬ, ТОПЛИВО Н2 + ОБОГРЕВ, СВЕТ, СВЕТ + ГРУЗ, the transponder, the note. The
     * state shown: see the class comment (what has no game side yet is a visual item).
     */
    private static void heli(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v) {
        boolean board = v.powered();
        int phase = CockpitInstrumentsSVA.phase;
        boolean dark = phase == CockpitLamps.PH_DARK, test = phase == CockpitLamps.PH_TEST && board;
        plate(s, board, 180F);
        float w = s.w, h = s.h, top = h / 2, m = w * 0.02F;
        float rot = GlassCockpitSVA.rotorPct(v);
        boolean ready = board && rot >= 90F, spinning = board && rot > 5F && rot < 90F, blink = (System.currentTimeMillis() / 300) % 2 == 0;
        boolean bat = CockpitDataSVA.batPresent(v) && board, gen = CockpitDataSVA.genOn(v) && board;
        // КРАН / НАСОС Н2: hydrogen in the tank (EU mode: charge left); still display only - the valves have no switch of their own
        boolean h2 = board && CockpitDataSVA.h2Flowing(v);
        boolean nav = com.sva.ConfigSVA.navLights, head = VehicleLightsSVA.headlightsOn(v), hook = v.hasTrailer();
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.ovhd.title"), 0F, top - h * 0.03F, h * 0.024F, w * 0.8F, CockpitInstrumentsSVA.tint(0xB0B4BE));
        // the status lamps: 2 rows of 3 (ГЕН ОТКЛ, АКБ РАЗРЯД, НАСОС Н2 / ЗАМОК ЗАКР., ПРОЖЕКТОР, ОБОГРЕВ)
        String[] keys = {"genOff", "batLow", "pump", "hook", "search", "heat"};
        int[] cols = {CockpitLamps.AMBER, CockpitLamps.AMBER, CockpitLamps.GREEN, CockpitLamps.GREEN, CockpitLamps.BLUE, CockpitLamps.AMBER};
        boolean[] lit = {board && !CockpitDataSVA.genOn(v), (board || v.onHydrogen()) && CockpitDataSVA.batLamp(v), h2, board && hook, false, false};
        float ly1 = top - h * 0.056F, lh = h * 0.05F, lw = (w - 2 * m - 2 * w * 0.02F) / 3;
        for (int k = 0; k < 6; k++) {
            float x0 = -w / 2 + m + (k % 3) * (lw + w * 0.02F), y1 = ly1 - (k / 3) * (lh + h * 0.01F);
            boolean on = !dark && (test || lit[k]);
            col(0x0C0C0E);
            CockpitInstrumentsSVA.rect(x0, y1 - lh, x0 + lw, y1, Z1 * 0.3F);
            col(on ? cols[k] : CockpitLamps.mix(cols[k], 0x101012, 0.84F));
            CockpitInstrumentsSVA.rect(x0 + lw * 0.03F, y1 - lh * 0.88F, x0 + lw * 0.97F, y1 - lh * 0.12F, Z1 * 0.6F);
            CockpitInstrumentsSVA.text(fr, Lang.tr("sva.ovhd.lamp." + keys[k]), x0 + lw / 2, y1 - lh / 2, lh * 0.4F, lw * 0.86F,
                    on ? 0x141008 : CockpitLamps.mix(cols[k], 0x101012, 0.5F));
        }
        float rh = h * 0.172F, gap = h * 0.01F, y = top - h * 0.168F;
        Item[][] r1 = {{gtog("sva.sys.sw.bat.short", bat, SystemsMath.SW_COLOUR[0], bat), gtog("sva.sys.sw.gen.short", gen, SystemsMath.SW_COLOUR[1], gen)},
                {btn("sva.ovhd.start", spinning ? CockpitLamps.AMBER : CockpitLamps.GREEN, ready || spinning && blink, spinning)}};
        row(fr, -w / 2 + m, w / 2 - m, y - rh, y, new String[] {"sva.sys.group.power", "sva.sys.group.engine"}, r1, dark, test);
        y -= rh + gap;
        Item[][] r2 = {{tog("sva.ovhd.valve", h2, CockpitLamps.GREEN, h2), tog("sva.ovhd.pump", h2, CockpitLamps.GREEN, h2)},
                {tog("sva.ovhd.heat", false, SystemsMath.SW_COLOUR[6], false)}, {insTog(v)}};
        row(fr, -w / 2 + m, w / 2 - m, y - rh, y, new String[] {"sva.ovhd.group.fuel", "sva.sys.group.heat", "sva.sys.group.nav"}, r2, dark, test);
        y -= rh + gap;
        Item[][] r3 = {{tog("sva.sys.sw.nav.short", nav, SystemsMath.SW_COLOUR[2], nav && board), tog("sva.sys.sw.strobe.short", nav, SystemsMath.SW_COLOUR[3], nav && board),
                tog("sva.sys.sw.beacon.short", nav, SystemsMath.SW_COLOUR[4], nav && board)}};
        row(fr, -w / 2 + m, w / 2 - m, y - rh, y, new String[] {"sva.sys.group.lights"}, r3, dark, test);
        y -= rh + gap;
        Item[][] r4 = {{tog("sva.ovhd.lamp", head, SystemsMath.SW_COLOUR[5], head && board), tog("sva.ovhd.searchlight", false, CockpitLamps.BLUE, false)},
                {tog("sva.ovhd.hook", hook, CockpitLamps.GREEN, hook && board), btn("sva.ovhd.release", CockpitLamps.RED, false, false)}};
        row(fr, -w / 2 + m, w / 2 - m, y - rh, y, new String[] {"sva.sys.group.lights", "sva.ovhd.group.cargo"}, r4, dark, test);
        y -= rh + gap;
        // the transponder strip: its title, the code (amber), the mode
        float ty0 = -h / 2 + h * 0.045F;
        col(0x1E1F24);
        CockpitInstrumentsSVA.rect(-w / 2 + m, ty0, w / 2 - m, y, Z1 * 0.3F);
        float tm = (ty0 + y) / 2, th = y - ty0;
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.xpdr.title"), -w * 0.33F, tm + th * 0.22F, th * 0.2F, w * 0.25F, CockpitInstrumentsSVA.tint(LABEL));
        col(0x040506);
        CockpitInstrumentsSVA.rect(-w * 0.46F, tm - th * 0.36F, w * 0.08F, tm + th * 0.02F, Z1 * 0.6F);
        if (!dark && phase != CockpitLamps.PH_LOGO) {
            CockpitInstrumentsSVA.text(fr, test ? "8888" : com.sva.vehicle.GlassMath.squawk(CockpitDataSVA.xpdrCode(v)), -w * 0.19F, tm - th * 0.17F, th * 0.3F,
                    w * 0.5F, CockpitLamps.AMBER);
        }
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.xpdr.mode") + " " + Lang.tr(CockpitDataSVA.xpdrMode(v)), w * 0.28F, tm, th * 0.22F, w * 0.36F,
                board ? CockpitLamps.GREEN : CockpitInstrumentsSVA.tint(LABEL));
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.ovhd.note"), 0F, -h / 2 + h * 0.02F, h * 0.016F, w * 0.9F, CockpitInstrumentsSVA.tint(0x9A9EA8));
        GL11.glPopMatrix();
    }
}
