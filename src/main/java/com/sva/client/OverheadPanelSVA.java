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
 */
@SideOnly(Side.CLIENT)
final class OverheadPanelSVA {

    private static final int PANEL = 0x2A2C32, FRAME = 0x55585F, LABEL = 0xC9CCD3, DIM = 0x3A3C42, GUARD = 0xC8281E;
    private static final float Z1 = CockpitInstrumentsSVA.LIFT_MARK - CockpitInstrumentsSVA.LIFT_FACE,
            Z2 = CockpitInstrumentsSVA.LIFT_NEEDLE - CockpitInstrumentsSVA.LIFT_FACE, Z3 = CockpitInstrumentsSVA.LIFT_HUB - CockpitInstrumentsSVA.LIFT_FACE;
    /** The slots across the face (the mock-up's 8 columns) and where each switch sits: {switch, row (0 near / 1 far), slot}. */
    private static final float SLOTS = 8F;
    private static final float[][] CELLS = {{SystemsMath.SW_BAT, 0, 0.75F}, {SystemsMath.SW_GEN, 0, 1.85F}, {SystemsMath.SW_HEAT, 0, 3.5F},
            {SystemsMath.SW_NAV, 1, 0.55F}, {SystemsMath.SW_STROBE, 1, 1.4F}, {SystemsMath.SW_BEACON, 1, 2.25F}, {SystemsMath.SW_LAMP, 1, 3.1F}};

    private OverheadPanelSVA() {
    }

    /** Draws the panel on the layout's 'ovhd' face, if it has one (config vehicles.avionics). */
    static void draw(FontRenderer fr, CockpitLayout l, EntityVehicleSVA v, VehicleType t) {
        CockpitLayout.Screen s = l.panel(CockpitLayout.OVERHEAD);
        if (s == null || !com.sva.ConfigSVA.avionics || !SystemsMath.applies(t)) {
            return;
        }
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
        group(fr, s, 0, 0.15F, 2.45F, "sva.sys.group.power");
        group(fr, s, 0, 2.85F, 4.15F, "sva.sys.group.heat");
        group(fr, s, 0, 4.55F, 7.85F, "sva.sys.group.engine");
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
            lamp(x, y + h * 0.13F, h * 0.04F, SystemsMath.SW_COLOUR[i], !dark && (test || lampOn));
            toggle(x, y - h * 0.02F, h, on, SystemsMath.GUARDED[i]);
            CockpitInstrumentsSVA.text(fr, Lang.tr("sva.sys.sw." + SystemsMath.SW_KEYS[i] + ".short"), x, y - h * (c[1] == 0 ? 0.165F : 0.19F), h * 0.055F,
                    w / SLOTS * 0.84F, CockpitInstrumentsSVA.tint(LABEL));
        }
        // ДВИГАТЕЛЬ: the ГОТОВ lamp (green running, amber blinking while it starts) and the guarded ПУСК button
        int es = v.engineState();
        boolean gated = v.engineGated(t);
        boolean ready = gated ? es == SystemsMath.RUN : v.isLifting();
        boolean starting = es == SystemsMath.CRANK || es == SystemsMath.WARM;
        float ex = slotX(w, 5.15F), ey = rowY(h, 0);
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
        float th = h * 0.055F, tw = Math.min((x1 - x0) * 0.8F, fr.getStringWidth(title) * th / 8F);
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
}
