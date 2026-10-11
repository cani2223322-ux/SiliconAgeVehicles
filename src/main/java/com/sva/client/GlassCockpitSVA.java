package com.sva.client;

import java.util.WeakHashMap;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.GlassMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

/**
 * Cockpit wave (docs/plan-next.md §5 / §6; mock-ups veh_cab2_C2.png, veh_cab3_heli_C2.png - the 2D art the concept sheets
 * were drawn with: scratchpad cab2/cab2_art.py, c2.py, heli_art.py): the C+ cockpits' glass and round faces, painted on
 * the named panels veh_real12.py puts in the light plane and the helicopter (CockpitLayout.PANELS), in
 * CockpitInstrumentsSVA's GL state and frame, with its night tint / glow and its startup phases:
 * <ul>
 * <li>'pfd' - attitude (sky / ground, pitch ladder, bank scale), the speed and altitude tapes, the vertical speed bar, the
 * heading strip; the plane: the AP modes on top, the МАРШ leg and the stall flag; the heli: the mode and the radar
 * altitude on top, the РВ box bottom left;</li>
 * <li>'mfd' (+ the plane's 'mfdkeys') - four pages flipped by «Страница MFD» (MfdPagesSVA): ДВИГ (charge / throttle or
 * engine rpm / power, the mode, the gear, H2 + minutes, the battery's volts / amps, four lamps), КАРТА (north up: the
 * flight plan, the base, the vehicle), МАРШ (the flight plan's rows - the same RouteMath.Plan the autopilot's МАРШ flies)
 * / ГРУЗ (the hold and the trailer; the cable and the swing are placeholders - no sling yet), СПО (the scope and the
 * transponder);</li>
 * <li>'lampbar' - the lamp strip (CockpitLamps.strip: 13 / 9 roles), 'xpdr' - the transponder (code, mode, ИДЕНТ / ОТВЕТ),
 * 'rwr' - the radar-warning scope;</li>
 * <li>the heli's 'tach2' (rotor = long white needle, engine = short amber one, green arc 95..105), 'torque' (the power,
 * red line at 100 %), 'hoverind' (the drift vector, rings 1 / 2 m/s), 'radalt' (blocks over the ground on a compressed
 * scale, the decision bug at 5) and the callouts «50.. 40.. 30..» on the descent (config vehicles.radarAltCallouts).</li>
 * </ul>
 * The hydrogen, battery, radar warning and transponder come from CockpitDataSVA (the radar warning / transponder are
 * placeholders until the air defence wave). Config
 * vehicles.glassCockpit (off: the faces stay dark).
 */
@SideOnly(Side.CLIENT)
final class GlassCockpitSVA {

    private static final int WHITE = 0xEBEDF2, DIMT = 0x6E7480, AMB = 0xFFAC28, GRN = 0x46EB78, CYN = 0x5AD7FF, YEL = 0xFFD63C, MAG = 0xEC5ADC,
            RED = 0xFF4636, BAR_BG = 0x0E121A, TRACK = 0x1C1E24;
    private static final float Z0 = 0.0002F, Z1 = CockpitInstrumentsSVA.LIFT_MARK - CockpitInstrumentsSVA.LIFT_FACE,
            Z2 = CockpitInstrumentsSVA.LIFT_NEEDLE - CockpitInstrumentsSVA.LIFT_FACE, Z3 = CockpitInstrumentsSVA.LIFT_HUB - CockpitInstrumentsSVA.LIFT_FACE;

    /** This frame's smoothed readings (CockpitInstrumentsSVA fills them before draw). */
    static final class Readings {
        /** Ground speed and vertical speed (blocks / tick), pitch / roll (deg), the gear down. */
        float speed, vy, pitch, roll;
        boolean gearDown = true;
    }

    /** Per vehicle (client): blocks over the ground (once a tick), the last callout height, the callout flashed and until when. */
    private static final class State {
        int aglTick = Integer.MIN_VALUE;
        float agl = 99F, lastAgl = Float.NaN;
        int flash = -1;
        long flashUntil;
    }

    private static final WeakHashMap<EntityVehicleSVA, State> STATES = new WeakHashMap<EntityVehicleSVA, State>();
    /** The callout flashed on the HUD now (HudVehicleSVA): the number, until (ms); -1 none. */
    private static int hudFlash = -1;
    private static long hudFlashUntil;
    /** How long a callout stays on the HUD (ms). */
    private static final long FLASH_MS = 900;

    private GlassCockpitSVA() {
    }

    private static State state(EntityVehicleSVA v) {
        State s = STATES.get(v);
        if (s == null) {
            s = new State();
            STATES.put(v, s);
        }
        return s;
    }

    // ---------------------------------------------------------------- readings shared with the lamps

    /** Blocks of air under the vehicle's centre (a column scan, once a tick; 99 = nothing within 64). */
    static float agl(EntityVehicleSVA v) {
        State s = state(v);
        if (s.aglTick != v.ticksExisted) {
            s.aglTick = v.ticksExisted;
            int x = MathHelper.floor_double(v.posX), z = MathHelper.floor_double(v.posZ);
            int y0 = MathHelper.floor_double(v.boundingBox.minY - 0.01);
            float h = 99F;
            for (int y = y0; y >= Math.max(0, y0 - 64); y--) {
                Material m = v.worldObj.getBlock(x, y, z).getMaterial();
                if (m.isSolid() || m.isLiquid()) {
                    h = (float) Math.max(0, v.boundingBox.minY - (y + 1));
                    break;
                }
            }
            s.agl = h;
        }
        return s.agl;
    }

    /** The heli's rotor rpm % (100 = the powered spin, EntityVehicleSVA.rotorRate 42 deg/t). */
    static float rotorPct(EntityVehicleSVA v) {
        return Math.abs(v.rotorRate()) * 100F / 42F;
    }

    /** The heli's power % (GlassMath.power: the collective, the load, the climb). */
    static float powerPct(EntityVehicleSVA v, float vy) {
        int cap = v.cargoCapacity();
        float fill = cap <= 0 ? 0F : v.getCargoUsed() / (float) cap;
        return v.powered() ? GlassMath.power((float) Math.abs(v.getThrottle()), fill, vy) : 0F;
    }

    // ---------------------------------------------------------------- entry

    static void draw(FontRenderer fr, CockpitLayout l, EntityVehicleSVA v, VehicleType t, float pt, Readings r) {
        if (!ConfigSVA.glassCockpit || l.panels.isEmpty()) {
            return;
        }
        boolean heli = t.movement == Movement.HELI;
        if (heli) {
            callouts(v, agl(v));
        }
        // Merge review (2026-10-12): this code had never run in a client - a face that throws must not leave its frame's
        // matrix pushed (CockpitInstrumentsSVA's finally pops the attributes, not the matrices): unwind to the depth we
        // came in with, skip that face, keep drawing the others; logged once per session.
        int depth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        for (CockpitLayout.Screen s : l.panels) {
            String m = s.mat;
            try {
                face(fr, s, m, v, t, pt, r, heli);
            } catch (RuntimeException e) {
                while (GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) > depth) {
                    GL11.glPopMatrix();
                }
                CockpitInstrumentsSVA.textLift = CockpitInstrumentsSVA.LIFT_MARK - CockpitInstrumentsSVA.LIFT_FACE;
                if (!warned) {
                    warned = true;
                    cpw.mods.fml.common.FMLLog.log(org.apache.logging.log4j.Level.WARN, e, "[SVA] glass cockpit: the '%s' face failed", m);
                }
            }
        }
    }

    /** A face failed once (draw's safety net): logged, not again. */
    private static boolean warned;

    private static void face(FontRenderer fr, CockpitLayout.Screen s, String m, EntityVehicleSVA v, VehicleType t, float pt, Readings r, boolean heli) {
        if ("pfd".equals(m)) {
            pfd(fr, s, v, t, pt, r, heli);
        } else if ("mfd".equals(m)) {
            mfd(fr, s, v, t, pt, r, heli);
        } else if ("mfdkeys".equals(m)) {
            mfdKeys(fr, s, v, t);
        } else if ("lampbar".equals(m)) {
            lampBar(fr, s, t);
        } else if ("xpdr".equals(m)) {
            xpdr(fr, s, v);
        } else if ("rwr".equals(m)) {
            rwrFace(fr, s, v);
        } else if ("tach2".equals(m)) {
            tach(fr, s, v);
        } else if ("torque".equals(m)) {
            torque(fr, s, v, r);
        } else if ("hoverind".equals(m)) {
            hover(fr, s, v, pt);
        } else if ("radalt".equals(m)) {
            radalt(fr, s, v);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int phase() {
        return CockpitInstrumentsSVA.phase;
    }

    private static void col(int rgb) {
        CockpitInstrumentsSVA.glColor(rgb);
    }

    private static int tint(int rgb) {
        return CockpitInstrumentsSVA.tint(rgb);
    }

    private static void rect(float x0, float y0, float x1, float y1, float z) {
        CockpitInstrumentsSVA.rect(x0, y0, x1, y1, z);
    }

    /**
     * Enters a face's frame and paints its startup look: dark, the lamp test's white, the SA logo - false then (the caller
     * only pops); else its dark background (round: a disc) - true, the content follows. The caller pops the matrix.
     */
    private static boolean open(FontRenderer fr, CockpitLayout.Screen s, boolean round) {
        CockpitInstrumentsSVA.frame(s.c, s.n, s.up, s.right, s.front + CockpitInstrumentsSVA.LIFT_FACE);
        float r = Math.min(s.w, s.h) / 2;
        if (CockpitInstrumentsSVA.night > 0F) {
            if (round) {
                CockpitInstrumentsSVA.glowRing(r * 0.95F, r * 1.4F, 0.35F * CockpitInstrumentsSVA.night, -0.0005F);
            } else {
                CockpitInstrumentsSVA.glowRect(s.w, s.h, 0.3F * CockpitInstrumentsSVA.night);
            }
        }
        int ph = phase();
        int bg = ph == CockpitLamps.PH_DARK ? 0x030405 : ph == CockpitLamps.PH_TEST ? 0xCCDBE6 : 0x06080C;
        if (round) {
            col(0x1A1B1F);
            CockpitInstrumentsSVA.disc(0F, 0F, r, 0F);
            col(bg);
            CockpitInstrumentsSVA.disc(0F, 0F, r * 0.92F, Z0);
        } else {
            col(bg);
            rect(-s.w / 2, -s.h / 2, s.w / 2, s.h / 2, 0F);
        }
        if (ph == CockpitLamps.PH_DARK || ph == CockpitLamps.PH_TEST) {
            return false;
        }
        if (ph == CockpitLamps.PH_LOGO) {
            CockpitInstrumentsSVA.text(fr, "SA", 0F, 0F, Math.min(s.w, s.h) * 0.35F, s.w * 0.8F, CockpitInstrumentsSVA.glowRgb);
            return false;
        }
        return true;
    }

    /** Text left-aligned at x, centred on y, `h` tall at most and `maxW` wide at most. */
    private static void textL(FontRenderer fr, String s, float x, float y, float h, float maxW, int color) {
        int px = fr.getStringWidth(s);
        if (px <= 0) {
            return;
        }
        CockpitInstrumentsSVA.drawText(fr, s, x, y, Math.min(h / 8F, maxW / px), color);
    }

    /** Text right-aligned to x. */
    private static void textR(FontRenderer fr, String s, float x, float y, float h, float maxW, int color) {
        int px = fr.getStringWidth(s);
        if (px <= 0) {
            return;
        }
        float k = Math.min(h / 8F, maxW / px);
        CockpitInstrumentsSVA.drawText(fr, s, x - px * k, y, k, color);
    }

    private static void textC(FontRenderer fr, String s, float x, float y, float h, float maxW, int color) {
        CockpitInstrumentsSVA.text(fr, s, x, y, h, maxW, color);
    }

    /** An outlined box: the outline colour, then the fill inset by t. */
    private static void box(float x0, float y0, float x1, float y1, float t, int edge, int fill, float z) {
        col(edge);
        rect(x0, y0, x1, y1, z);
        col(fill);
        rect(x0 + t, y0 + t, x1 - t, y1 - t, z + 0.0001F);
    }

    private static String pctText(float p) {
        return Math.round(Math.max(0, p)) + " %";
    }

    private static String dec1(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", Float.valueOf(v)).replace('.', ',');
    }

    private static String signed(float v) {
        return (v > 0 ? "+" : "") + Math.round(v);
    }

    /** The heading in compass degrees now. */
    private static float heading(EntityVehicleSVA v, float pt) {
        return (float) AvionicsSVA.heading(v, pt);
    }

    /** A dial's arc band (deg clockwise from up, a0 -> a1) at radius r, width w. */
    private static void arc(float r, float a0, float a1, float w, float z) {
        AvionicsSVA.arc(r, a0, a1, w, z);
    }

    // ---------------------------------------------------------------- PFD

    private static void pfd(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v, VehicleType t, float pt, Readings r, boolean heli) {
        if (!open(fr, s, false)) {
            GL11.glPopMatrix();
            return;
        }
        float w = s.w, h = s.h, hw = w / 2, hh = h / 2;
        float yBand = hh - 0.83F * h;                                // the heading band's top
        float cy = hh - 0.44F * h;                                   // the attitude's centre
        float fs = h * 0.042F;
        // attitude: sky over ground in the area above the heading band, the horizon through (0, cy) moved by pitch / bank
        float perDeg = h * 0.0216F;
        double a = Math.toRadians(r.roll);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float ox = sa * r.pitch * perDeg, oy = cy - ca * r.pitch * perDeg;
        float[][] area = {{-hw, yBand}, {hw, yBand}, {hw, hh}, {-hw, hh}};
        col(0x2870C8);
        CockpitInstrumentsSVA.poly(CockpitInstrumentsSVA.clip(area, ox, oy, -sa, ca), Z0);
        col(0x82542A);
        CockpitInstrumentsSVA.poly(CockpitInstrumentsSVA.clip(area, ox, oy, sa, -ca), Z0);
        // the pitch ladder (clipped to the middle of the attitude area)
        col(0xF2F2F6);
        float lx = hw * 0.55F, ly = Math.min(hh - cy, cy - yBand) * 0.92F;
        for (int deg = -20; deg <= 20; deg += 5) {
            float len = deg == 0 ? w * 2 : w * (Math.abs(deg) == 5 ? 0.06F : 0.12F);
            float px = ox - sa * deg * perDeg, py = oy + ca * deg * perDeg - cy;
            float[][] seg = CockpitInstrumentsSVA.clipSegment(px - ca * len, py - sa * len, px + ca * len, py + sa * len, deg == 0 ? hw : lx, ly);
            if (seg != null) {
                CockpitInstrumentsSVA.line(seg[0][0], seg[0][1] + cy, seg[1][0], seg[1][1] + cy, h * (deg == 0 ? 0.008F : 0.005F), Z1);
            }
        }
        // the bank scale (turns with the bank) and the fixed pointer
        float R = h * 0.3F;
        for (int k : new int[] {-45, -30, -20, -10, 0, 10, 20, 30, 45}) {
            double ang = Math.toRadians(k - r.roll);
            float sx = (float) Math.sin(ang), sy = (float) Math.cos(ang);
            CockpitInstrumentsSVA.line(sx * R, cy + sy * R, sx * (R + h * 0.03F), cy + sy * (R + h * 0.03F), h * 0.008F, Z1);
        }
        AvionicsSVA.tri(0F, cy + R - h * 0.005F, -h * 0.022F, cy + R - h * 0.04F, h * 0.022F, cy + R - h * 0.04F, Z1);
        // the aircraft symbol (yellow)
        col(0xFFD228);
        float lw = h * 0.016F;
        CockpitInstrumentsSVA.line(-w * 0.17F, cy, -w * 0.06F, cy, lw, Z2);
        CockpitInstrumentsSVA.line(-w * 0.06F, cy, -w * 0.03F, cy - h * 0.035F, lw, Z2);
        CockpitInstrumentsSVA.line(w * 0.17F, cy, w * 0.06F, cy, lw, Z2);
        CockpitInstrumentsSVA.line(w * 0.06F, cy, w * 0.03F, cy - h * 0.035F, lw, Z2);
        rect(-lw, cy - lw, lw, cy + lw, Z2);
        // the tapes: speed (km/h) left, altitude (Y) right
        float t0 = hh - 0.1F * h, t1 = hh - 0.78F * h;
        float kmh = r.speed * 72F;
        float alt = (float) (v.prevPosY + (v.posY - v.prevPosY) * pt);
        tape(fr, -hw + 0.01F * w, -hw + 0.16F * w, t0, t1, cy, kmh, 10, true, Lang.tr("sva.pfd.spd"), fs);
        tape(fr, hw - 0.21F * w, hw - 0.06F * w, t0, t1, cy, alt, 5, false, Lang.tr("sva.pfd.alt"), fs);
        // the vertical speed bar (+-10 blocks / s)
        float vx0 = hw - 0.05F * w, vx1 = hw - 0.006F * w, vy0 = t1 + 0.1F * h, vy1 = t0 - 0.1F * h, vm = (vy0 + vy1) / 2, span = (vy1 - vy0) / 2;
        col(0x14181F);
        rect(vx0, vy0, vx1, vy1, Z0 * 2);
        col(tint(WHITE));
        for (int k = -10; k <= 10; k += 5) {
            float y = vm + k / 10F * span;
            CockpitInstrumentsSVA.line(vx0, y, vx0 + (vx1 - vx0) * 0.45F, y, h * 0.006F, Z1);
        }
        float vs = r.vy * 20F;
        col(GRN);
        float vyy = vm + Math.max(-1F, Math.min(1F, vs / 10F)) * span;
        CockpitInstrumentsSVA.line(vx0, vyy, vx1, vyy, h * 0.014F, Z2);
        textC(fr, dec1(vs), (vx0 + vx1) / 2, vy0 - h * 0.035F, fs * 0.75F, (vx1 - vx0) * 1.4F, tint(WHITE));
        textC(fr, Lang.tr("sva.pfd.vs"), (vx0 + vx1) / 2, vy1 + h * 0.035F, fs * 0.6F, (vx1 - vx0) * 1.4F, CYN);
        // the heading strip
        col(0x0A0C12);
        rect(-hw, -hh, hw, yBand, Z0 * 2);
        float hdg = heading(v, pt);
        float perHdg = w * 0.011F;
        col(tint(WHITE));
        int h0 = (int) Math.floor(hdg / 5F) * 5;
        for (int k = h0 - 30; k <= h0 + 35; k += 5) {
            float x = (k - hdg) * perHdg;
            if (Math.abs(x) > hw * 0.95F) {
                continue;
            }
            boolean big = ((k % 360) + 360) % 10 == 0;
            CockpitInstrumentsSVA.line(x, yBand, x, yBand - h * (big ? 0.026F : 0.013F), h * 0.005F, Z1);
            if (big) {
                int hv = ((k % 360) + 360) % 360;
                textC(fr, String.format("%02d", Integer.valueOf(hv / 10)), x, yBand - h * 0.05F, fs * 0.8F, perHdg * 8, tint(WHITE));
            }
        }
        col(YEL);
        AvionicsSVA.tri(0F, yBand - h * 0.004F, -h * 0.018F, yBand + h * 0.022F, h * 0.018F, yBand + h * 0.022F, Z2);
        float bx = w * 0.06F, by0 = -hh + h * 0.012F, by1 = yBand - h * 0.075F;
        box(-bx, by0, bx, by1, h * 0.005F, tint(WHITE), 0x000000, Z1);
        textC(fr, String.format("%03d", Integer.valueOf(Math.round(hdg) % 360)), 0F, (by0 + by1) / 2, (by1 - by0) * 0.7F, bx * 1.8F, tint(WHITE));
        // the top line
        String top;
        int topC = GRN;
        if (heli) {
            String[] mode = CockpitInstrumentsSVA.mode(v, t, r.speed);
            top = mode[0] + "   " + Lang.tr("sva.pfd.ra") + " " + Math.round(agl(v)) + "   " + Lang.tr("sva.pfd.vs") + " " + dec1(vs);
            topC = Integer.parseInt(mode[1]);
        } else {
            top = AvionicsSVA.apLine(CockpitInstrumentsSVA.apView(v));
            if (top == null) {
                top = Lang.tr("sva.pfd.apOff");
                topC = DIMT;
            }
        }
        col(0x000000);
        rect(-w * 0.28F, hh - 0.075F * h, w * 0.28F, hh, Z0 * 3);
        textC(fr, top, 0F, hh - 0.0375F * h, fs * 0.85F, w * 0.54F, topC);
        if (heli) {
            // the РВ box bottom left: blocks over the ground, big
            float x0 = -hw + 0.02F * w, x1 = -hw + 0.3F * w, y1 = -hh + 0.155F * h, y0 = -hh + 0.01F * h;
            box(x0, y0, x1, y1, h * 0.004F, 0x5A5C64, 0x000000, Z1);
            textL(fr, Lang.tr("sva.pfd.ra"), x0 + w * 0.02F, (y0 + y1) / 2, fs, w * 0.08F, CYN);
            textR(fr, Math.round(agl(v)) + " " + Lang.tr("sva.pfd.blocks"), x1 - w * 0.02F, (y0 + y1) / 2, fs * 1.4F, w * 0.18F, GRN);
        } else {
            // МАРШ: the active point and its distance (magenta)
            int[] rt = CockpitInstrumentsSVA.apView(v).mode(AutopilotMath.M_ROUTE) ? v.routeTarget() : null;
            if (rt != null && rt[0] < rt[1]) {
                double d = AutopilotMath.distance(v.posX, v.posZ, rt[2] + 0.5, rt[3] + 0.5);
                textC(fr, Lang.tr("sva.pfd.route", Integer.valueOf(rt[0] + 1), Integer.valueOf(rt[1]), AutopilotMath.km(d)), -w * 0.3F,
                        (-hh + yBand) / 2, fs * 0.72F, w * 0.3F, MAG);
            }
            if (v.isStalling() && phase() == CockpitLamps.PH_NORMAL && (System.currentTimeMillis() / 250) % 2 == 0) {
                col(0xD91F14);
                rect(-hw * 0.5F, cy + h * 0.12F, hw * 0.5F, cy + h * 0.22F, Z3);
                CockpitInstrumentsSVA.textLift = Z3 + 0.0006F;
                textC(fr, Lang.tr("sva.cockpit.lamp.stall"), 0F, cy + h * 0.17F, h * 0.08F, hw * 0.9F, 0xFFFFFF);
                CockpitInstrumentsSVA.textLift = Z1;
            }
        }
        GL11.glPopMatrix();
    }

    /** A tape from y t0 (top) to t1 at x0..x1: ticks every `step`, 6 steps over its height, the value's box at cy. */
    private static void tape(FontRenderer fr, float x0, float x1, float t0, float t1, float cy, float val, int step, boolean left, String label, float fs) {
        col(0x14181F);
        rect(x0, t1, x1, t0, Z0 * 2);
        float ppv = (t0 - t1) / (step * 6F);
        int v0 = (int) Math.floor((val - step * 3.5F) / step) * step;
        col(tint(WHITE));
        for (int k = 0; k <= 8; k++) {
            int vv = v0 + k * step;
            float y = cy + (vv - val) * ppv;
            if (y > t0 - fs * 0.6F || y < t1 + fs * 0.6F) {
                continue;
            }
            float tx = left ? x1 : x0;
            CockpitInstrumentsSVA.line(left ? tx - (x1 - x0) * 0.12F : tx, y, left ? tx : tx + (x1 - x0) * 0.12F, y, fs * 0.12F, Z1);
            if (vv >= 0) {
                textC(fr, String.valueOf(vv), (x0 + x1) / 2 + (left ? -1 : 1) * (x1 - x0) * 0.06F, y, fs, (x1 - x0) * 0.7F, tint(WHITE));
            }
        }
        box(x0 - fs * 0.15F, cy - fs * 0.9F, x1 + fs * 0.15F, cy + fs * 0.9F, fs * 0.1F, tint(WHITE), 0x000000, Z2);
        CockpitInstrumentsSVA.textLift = Z2 + 0.0004F;
        textC(fr, String.valueOf(Math.round(val)), (x0 + x1) / 2, cy, fs * 1.25F, (x1 - x0) * 0.9F, 0xFFFFFF);
        CockpitInstrumentsSVA.textLift = Z1;
        textC(fr, label, (x0 + x1) / 2, t0 + fs * 0.8F, fs * 0.8F, (x1 - x0) * 1.3F, CYN);
    }

    // ---------------------------------------------------------------- MFD

    private static void mfd(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v, VehicleType t, float pt, Readings r, boolean heli) {
        if (!open(fr, s, false)) {
            GL11.glPopMatrix();
            return;
        }
        float w = s.w, h = s.h, hw = w / 2, hh = h / 2;
        float fs = Math.min(h * 0.045F, w * 0.042F);
        int page = MfdPagesSVA.page(v);
        if (page < 0) {
            GL11.glPopMatrix();                                         // an 'mfd' face on a type without pages (GlassMath.pages)
            return;
        }
        float top = hh - 0.085F * h, bot = -hh + 0.1F * h;
        // the title bar
        col(BAR_BG);
        rect(-hw, top, hw, hh, Z0 * 2);
        textL(fr, Lang.tr("sva.mfd.title." + GlassMath.PAGE_KEYS[page] + (heli ? ".heli" : "")), -hw + w * 0.03F, (top + hh) / 2, fs * 1.05F, w * 0.62F, CYN);
        if (!heli) {
            AutopilotMath.Panel ap = CockpitInstrumentsSVA.apView(v);
            String apl = ap.on ? apModes(ap) : Lang.tr("sva.pfd.apOff");
            textR(fr, apl, hw - w * 0.03F, (top + hh) / 2, fs * 0.8F, w * 0.34F, ap.on ? GRN : DIMT);
        }
        // the page keys' labels at the bottom, the active one boxed green
        col(BAR_BG);
        rect(-hw, -hh, hw, bot, Z0 * 2);
        int[] pages = GlassMath.pages(t);
        float kw = w / pages.length;
        for (int i = 0; i < pages.length; i++) {
            boolean on = pages[i] == page;
            float x0 = -hw + i * kw, x1 = x0 + kw;
            if (on) {
                box(x0 + kw * 0.06F, -hh + h * 0.008F, x1 - kw * 0.06F, bot - h * 0.008F, h * 0.005F, GRN, BAR_BG, Z0 * 3);
            }
            textC(fr, Lang.tr("sva.mfd.page." + GlassMath.PAGE_KEYS[pages[i]]), (x0 + x1) / 2, (-hh + bot) / 2, fs * 0.95F, kw * 0.84F, on ? GRN : tint(WHITE));
        }
        switch (page) {
            case GlassMath.ENG:
                if (heli) {
                    engHeli(fr, v, r, w, top, bot, fs);
                } else {
                    engPlane(fr, v, r, w, top, bot, fs);
                }
                break;
            case GlassMath.MAP:
                map(fr, v, t, pt, w, top, bot, fs, heli);
                break;
            case GlassMath.ROUTE:
                route(fr, v, r, w, top, fs);
                break;
            case GlassMath.CARGO:
                cargo(fr, v, w, top, bot, fs);
                break;
            default:
                rwrPage(fr, v, w, h, top, fs);
        }
        GL11.glPopMatrix();
    }

    /** «КУРС ВЫС» - the held modes' names. */
    private static String apModes(AutopilotMath.Panel ap) {
        StringBuilder b = new StringBuilder(Lang.tr("sva.mfd.ap"));
        for (int g = AutopilotMath.G_HDG; g <= AutopilotMath.G_ROUTE; g++) {
            if (ap.mode(AutopilotMath.bit(g))) {
                b.append(' ').append(Lang.tr("sva.ap.group." + AutopilotMath.GROUP_KEYS[g]));
            }
        }
        return b.toString();
    }

    /** A round gauge (concept c2._gauge): coloured arcs {from %, to %, rgb}, ticks every 25 %, the needle, the value, the label. */
    private static void gauge(FontRenderer fr, float x, float y, float R, float pct, float[][] arcs, String label, String value, int c) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0F);
        for (float[] a : arcs) {
            col((int) a[2]);
            arc(R, GlassMath.angle(a[0] / 100F), GlassMath.angle(a[1] / 100F), R * 0.12F, Z1);
        }
        col(tint(WHITE));
        for (int k = 0; k <= 100; k += 25) {
            CockpitInstrumentsSVA.ray(GlassMath.angle(k / 100F), R * 0.62F, R * 0.78F, R * 0.04F, Z1);
        }
        col(c);
        CockpitInstrumentsSVA.needle(GlassMath.angle(pct / 100F), R * 0.86F, R * 0.1F, R * 0.05F, Z2);
        col(tint(WHITE));
        CockpitInstrumentsSVA.disc(0F, 0F, R * 0.08F, Z3);
        textC(fr, value, 0F, -R * 0.45F, R * 0.36F, R * 1.4F, c);
        textC(fr, label, 0F, -R * 1.08F, R * 0.26F, R * 2F, CYN);
        GL11.glPopMatrix();
    }

    /** A labelled bar: the label left, the value right (over the bar), the bar's track and its fill. */
    private static void bar(FontRenderer fr, float x, float y, float bw, float bh, float frac, int c, String label, String value, float fs) {
        textL(fr, label, x, y + bh + fs * 0.6F, fs, bw * 0.6F, tint(WHITE));
        textR(fr, value, x + bw, y + bh + fs * 0.6F, fs, bw * 0.6F, c);
        col(TRACK);
        rect(x, y, x + bw, y + bh, Z0 * 3);
        col(c);
        rect(x, y, x + bw * Math.max(0F, Math.min(1F, frac)), y + bh, Z1);
    }

    /** A lamp box (the ДВИГ page's bottom row). */
    private static void lampBox(FontRenderer fr, float x0, float y0, float x1, float y1, String label, int c, boolean on) {
        box(x0, y0, x1, y1, (y1 - y0) * 0.08F, on ? c : 0x50545C, on ? CockpitLamps.mix(c, 0, 0.2F) : 0x06080C, Z0 * 3);
        textC(fr, label, (x0 + x1) / 2, (y0 + y1) / 2, (y1 - y0) * 0.45F, (x1 - x0) * 0.86F, on ? 0x101010 : 0x787A82);
    }

    private static boolean lampOn(int role) {
        return CockpitLamps.lit(CockpitLamps.state(role, CockpitInstrumentsSVA.SNAP), System.currentTimeMillis());
    }

    /** The H2, battery and lamp rows shared by both ДВИГ pages, from y down; returns the next y. */
    private static float powerRows(FontRenderer fr, EntityVehicleSVA v, float x, float bw, float y, float fs, boolean temp) {
        float bh = fs * 0.8F;
        int h2 = CockpitDataSVA.h2Pct(v), min = CockpitDataSVA.h2Minutes(v);
        String h2v = h2 + " %" + (min >= 0 ? "  ~" + Lang.tr("sva.mfd.min", Integer.valueOf(min)) : "");
        bar(fr, x, y - bh, bw, bh, h2 / 100F, h2 < 15 ? AMB : CYN, Lang.tr("sva.mfd.h2"), h2v, fs);
        y -= fs * 2.6F;
        int bp = CockpitDataSVA.batPct(v);
        float amps = CockpitDataSVA.batAmps(v);
        boolean bat = CockpitDataSVA.batPresent(v);
        bar(fr, x, y - bh, bw * 0.46F, bh, bp / 100F, CockpitDataSVA.batLamp(v) ? AMB : GRN, Lang.tr("sva.mfd.bat"),
                bat ? dec1(CockpitDataSVA.batVolts(v)) + " " + Lang.tr("sva.mfd.volt") : Lang.tr("sva.mfd.noBat"), fs);
        bar(fr, x + bw * 0.54F, y - bh, bw * 0.46F, bh, Math.abs(amps) / CockpitDataSVA.ampsScale(v), amps < 0 ? AMB : GRN, Lang.tr("sva.mfd.amps"),
                signed(amps) + " " + Lang.tr("sva.mfd.amp"), fs);
        y -= fs * 2.4F;
        if (temp) {
            // the heli: the engine temperature (the heat reading as degrees: 20 C cold .. 120 C at the cut-out) and ГЕН
            float heat = v.getHeat();
            bar(fr, x, y - bh, bw * 0.46F, bh, heat / 100F, heat > 80 ? RED : GRN, Lang.tr("sva.mfd.temp"), Math.round(20 + heat) + " C", fs);
            boolean gen = CockpitDataSVA.genOn(v);
            textL(fr, Lang.tr("sva.mfd.gen"), x + bw * 0.54F, y + fs * 0.6F - bh, fs, bw * 0.2F, tint(WHITE));
            textR(fr, Lang.tr(gen ? "sva.mfd.on" : "sva.mfd.off"), x + bw, y + fs * 0.6F - bh, fs, bw * 0.2F, gen ? GRN : AMB);
            y -= fs * 2.2F;
        }
        float lw = (bw - 3 * fs * 0.4F) / 4;
        String[] labels = {Lang.tr("sva.mfd.lamp.lowFuel"), Lang.tr("sva.cockpit.lamp.reserve"), Lang.tr("sva.cockpit.lamp.bat"),
                Lang.tr(CockpitDataSVA.genOn(v) ? "sva.mfd.lamp.genOn" : "sva.mfd.lamp.genOff")};
        int[] roles = {CockpitLamps.LOW_FUEL, CockpitLamps.RESERVE, CockpitLamps.BAT, -1};
        for (int i = 0; i < 4; i++) {
            float x0 = x + i * (lw + fs * 0.4F);
            boolean on = roles[i] < 0 ? true : lampOn(roles[i]);
            int c = roles[i] < 0 ? (CockpitDataSVA.genOn(v) ? GRN : AMB) : CockpitLamps.colour(roles[i]);
            // АКБ is wired to the battery (CockpitLamps.state: lit with the board dark on hydrogen); the screen itself needs power
            lampBox(fr, x0, y - fs * 1.6F, x0 + lw, y, labels[i], c, on && CockpitInstrumentsSVA.SNAP.powered);
        }
        return y - fs * 2F;
    }

    private static void engPlane(FontRenderer fr, EntityVehicleSVA v, Readings r, float w, float top, float bot, float fs) {
        float R = (top - bot) * 0.135F;
        float gy = top - R * 1.45F;
        float charge = v.getEnergy() * 100F / (float) Math.max(1.0, v.getMaxEnergy());
        float thr = (float) v.getThrottle() * 100F;
        gauge(fr, -w * 0.23F, gy, R, charge, new float[][] {{0, 15, RED}, {15, 30, AMB}, {30, 100, GRN}}, Lang.tr("sva.mfd.charge"), Math.round(charge) + "%",
                tint(WHITE));
        gauge(fr, w * 0.23F, gy, R, thr, new float[][] {{90, 100, AMB}}, Lang.tr("sva.mfd.throttle"), Math.round(thr) + "%", tint(WHITE));
        float x = -w * 0.44F, bw = w * 0.88F;
        float y = gy - R * 1.55F;
        String[] mode = CockpitInstrumentsSVA.mode(v, v.getVehicleType(), r.speed);
        textL(fr, Lang.tr("sva.mfd.mode"), x, y, fs, bw * 0.4F, tint(WHITE));
        textR(fr, mode[0], x + bw, y, fs, bw * 0.55F, Integer.parseInt(mode[1]));
        y -= fs * 1.6F;
        textL(fr, Lang.tr("sva.mfd.gear"), x, y, fs, bw * 0.4F, tint(WHITE));
        int gc = ConfigSVA.animations ? CockpitLamps.gear(AnimMath.lerp(v.anim.prevGear, v.anim.gear, 1F), v.anim.gearUp) : r.gearDown ? CockpitLamps.GREEN : 0;
        for (int k = 0; k < 3; k++) {
            float x1 = x + bw - k * fs * 1.5F;
            col(gc == 0 ? 0x1E2A22 : gc);
            rect(x1 - fs * 1.1F, y - fs * 0.5F, x1, y + fs * 0.5F, Z1);
        }
        y -= fs * 1.8F;
        powerRows(fr, v, x, bw, y, fs, false);
    }

    private static void engHeli(FontRenderer fr, EntityVehicleSVA v, Readings r, float w, float top, float bot, float fs) {
        float R = (top - bot) * 0.12F;
        float gy = top - R * 1.4F;
        float rot = rotorPct(v), eng = GlassMath.engineRpm(rot, v.isAutorotating()), pw = powerPct(v, r.vy);
        gauge(fr, -w * 0.23F, gy, R, eng, new float[][] {{90, 95, AMB}, {95, 105, GRN}}, Lang.tr("sva.mfd.engRpm"), Math.round(eng) + "%", tint(WHITE));
        gauge(fr, w * 0.23F, gy, R, pw, new float[][] {{0, 85, GRN}, {85, 100, AMB}}, Lang.tr("sva.mfd.power"), Math.round(pw) + "%", pw > 100 ? RED : tint(WHITE));
        powerRows(fr, v, -w * 0.44F, w * 0.88F, gy - R * 1.6F, fs, true);
    }

    /** КАРТА: north up round the vehicle - the flight plan (magenta), the base (cyan), range ring, the vehicle (yellow). */
    private static void map(FontRenderer fr, EntityVehicleSVA v, VehicleType t, float pt, float w, float top, float bot, float fs, boolean heli) {
        float hw = w / 2, x0 = -hw + w * 0.015F, x1 = hw - w * 0.015F, y0 = bot + w * 0.015F, y1 = top - w * 0.015F;
        col(0x0A120E);
        rect(x0, y0, x1, y1, Z0 * 3);
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        float range = heli ? 1000F : 2000F;                            // blocks from the centre to the top edge ("2 / 4 км" across)
        float k = (y1 - y0) / 2 / range;
        col(0x16241C);                                                  // a 500-block grid
        double ox = v.posX, oz = v.posZ;
        for (int g = -8; g <= 8; g++) {
            float gx = cx + (float) ((Math.floor(ox / 500) * 500 + g * 500 - ox) * k);
            if (gx > x0 && gx < x1) {
                CockpitInstrumentsSVA.line(gx, y0, gx, y1, w * 0.003F, Z0 * 4);
            }
            float gz = cy - (float) ((Math.floor(oz / 500) * 500 + g * 500 - oz) * k);
            if (gz > y0 && gz < y1) {
                CockpitInstrumentsSVA.line(x0, gz, x1, gz, w * 0.003F, Z0 * 4);
            }
        }
        col(0x5A6E78);
        AvionicsSVA.ring(cx, cy, (y1 - y0) / 4, w * 0.004F, Z0 * 5);
        textL(fr, AutopilotMath.km(range / 2), cx + (y1 - y0) / 4 * 0.72F, cy + (y1 - y0) / 4 * 0.72F, fs * 0.7F, w * 0.15F, 0x8A9AA2);
        // the flight plan (north = -Z up, east = +X right)
        RouteMath.Plan p = v.plan();
        float[] prev = null;
        for (int i = 0; i < p.size(); i++) {
            int[] q = p.pts.get(i);
            float px = cx + (float) ((q[0] + 0.5 - ox) * k), py = cy - (float) ((q[1] + 0.5 - oz) * k);
            if (prev != null) {
                float[][] seg = CockpitInstrumentsSVA.clipSegment(prev[0] - cx, prev[1] - cy, px - cx, py - cy, (x1 - x0) / 2, (y1 - y0) / 2);
                if (seg != null) {
                    col(i <= p.active ? MAG : 0x8A3A82);
                    CockpitInstrumentsSVA.line(seg[0][0] + cx, seg[0][1] + cy, seg[1][0] + cx, seg[1][1] + cy, w * 0.012F, Z1);
                }
            }
            if (px > x0 && px < x1 && py > y0 && py < y1) {
                float d = w * 0.022F;
                col(tint(WHITE));
                CockpitInstrumentsSVA.poly(new float[][] {{px, py + d}, {px + d, py}, {px, py - d}, {px - d, py}}, Z1 + 0.0001F);
                col(0x0A120E);
                CockpitInstrumentsSVA.poly(new float[][] {{px, py + d * 0.55F}, {px + d * 0.55F, py}, {px, py - d * 0.55F}, {px - d * 0.55F, py}}, Z1 + 0.0002F);
                textL(fr, Lang.tr("sva.ap.route.wp", Integer.valueOf(i + 1)), px + d * 1.4F, py, fs * 0.85F, w * 0.18F, i == p.active ? MAG : tint(WHITE));
            }
            prev = new float[] {px, py};
        }
        int[] base = v.basePoint();
        if (base != null) {
            float bx = cx + (float) ((base[0] + 0.5 - ox) * k), by = cy - (float) ((base[1] + 0.5 - oz) * k);
            if (bx > x0 && bx < x1 && by > y0 && by < y1) {
                col(CYN);
                AvionicsSVA.ring(bx, by, w * 0.02F, w * 0.008F, Z1);
                textL(fr, Lang.tr("sva.hsi.base"), bx + w * 0.03F, by, fs * 0.8F, w * 0.18F, CYN);
            }
        }
        // the vehicle: a yellow arrow along its heading
        double hd = Math.toRadians(heading(v, pt));
        float ux = (float) Math.sin(hd), uy = (float) Math.cos(hd), s = w * 0.035F;
        col(YEL);
        CockpitInstrumentsSVA.poly(new float[][] {{cx + ux * s * 1.4F, cy + uy * s * 1.4F}, {cx - ux * s + uy * s * 0.8F, cy - uy * s - ux * s * 0.8F},
                {cx - ux * s * 0.4F, cy - uy * s * 0.4F}, {cx - ux * s - uy * s * 0.8F, cy - uy * s + ux * s * 0.8F}}, Z2);
        textR(fr, Lang.tr("sva.mfd.north"), x1 - w * 0.02F, y1 - fs, fs, w * 0.12F, tint(WHITE));
        textL(fr, Lang.tr(heli ? "sva.mfd.scale.heli" : "sva.mfd.scale.plane"), x0 + w * 0.02F, y1 - fs, fs * 0.8F, w * 0.3F, 0x8A9AA2);
        int[] rt = v.routeTarget();
        if (rt != null && rt[0] < rt[1]) {
            double d = AutopilotMath.distance(v.posX, v.posZ, rt[2] + 0.5, rt[3] + 0.5);
            textL(fr, Lang.tr("sva.ap.route.wp", Integer.valueOf(rt[0] + 1)) + "  " + AutopilotMath.km(d) + " " + Lang.tr("sva.mfd.km"), x0 + w * 0.02F,
                    y0 + fs, fs, w * 0.6F, MAG);
        }
    }

    /** МАРШ: the plan's rows (№, ТОЧКА, КУРС, КМ, МИН), the active one boxed magenta - RouteMath.Plan, as the autopilot. */
    private static void route(FontRenderer fr, EntityVehicleSVA v, Readings r, float w, float top, float fs) {
        float[] xs = {-0.44F * w, -0.34F * w, -0.03F * w, 0.16F * w, 0.33F * w};
        String[] heads = {Lang.tr("sva.mfd.route.n"), Lang.tr("sva.mfd.route.pt"), Lang.tr("sva.mfd.route.crs"), Lang.tr("sva.mfd.km"), Lang.tr("sva.mfd.route.min")};
        float y = top - fs * 1.4F;
        for (int i = 0; i < 5; i++) {
            textL(fr, heads[i], xs[i], y, fs, w * 0.2F, CYN);
        }
        RouteMath.Plan p = v.plan();
        if (p.isEmpty()) {
            textL(fr, Lang.tr("sva.mfd.route.none"), xs[0], y - fs * 2.5F, fs, w * 0.88F, DIMT);
            textL(fr, Lang.tr("sva.mfd.route.how"), xs[0], y - fs * 4F, fs * 0.85F, w * 0.88F, DIMT);
            return;
        }
        double[][] rows = GlassMath.routeRows(p, v.posX, v.posZ, r.speed);
        int first = Math.max(0, Math.min(p.active - 1, p.size() - 6));
        for (int i = first; i < Math.min(p.size(), first + 6); i++) {
            float yy = top - fs * (3.4F + (i - first) * 2.2F);
            boolean act = i == p.active;
            if (act) {
                box(-0.47F * w, yy - fs * 0.9F, 0.47F * w, yy + fs * 0.9F, fs * 0.12F, MAG, 0x06080C, Z0 * 3);
            }
            int c = act ? MAG : i < p.active ? DIMT : tint(WHITE);
            double[] row = rows[i];
            String crs = Double.isNaN(row[0]) ? "-" : String.format("%03d", Integer.valueOf((int) Math.round(row[0]) % 360));
            String km = Double.isNaN(row[1]) ? "-" : AutopilotMath.km(row[1]);
            String mn = Double.isNaN(row[2]) ? "-" : String.valueOf(Math.max(1, Math.round(row[2])));
            String[] cells = {String.valueOf(i + 1), Lang.tr("sva.ap.route.wp", Integer.valueOf(i + 1)), crs, km, mn};
            for (int k = 0; k < 5; k++) {
                textL(fr, cells[k], xs[k], yy, fs * 1.05F, w * 0.2F, c);
            }
        }
        if (p.done()) {
            textL(fr, Lang.tr("sva.ap.route.end"), xs[0], top - fs * 17F, fs, w * 0.88F, MAG);
        }
        textL(fr, Lang.tr("sva.mfd.route.note"), xs[0], top - fs * 18.6F, fs * 0.85F, w * 0.88F, DIMT);
    }

    /** ГРУЗ (heli): the side view with the load on its cable, the hold, the trailer; the cable / swing are placeholders. */
    private static void cargo(FontRenderer fr, EntityVehicleSVA v, float w, float top, float bot, float fs) {
        float cx = 0F, cy = top - (top - bot) * 0.14F;
        col(0x3C465A);
        rect(cx - w * 0.16F, cy - w * 0.03F, cx + w * 0.16F, cy + w * 0.05F, Z0 * 3);
        col(0x78829A);
        CockpitInstrumentsSVA.line(cx - w * 0.3F, cy + w * 0.07F, cx + w * 0.3F, cy + w * 0.07F, w * 0.008F, Z1);
        int used = v.getCargoUsed(), cap = Math.max(1, v.cargoCapacity());
        float ly = cy - (top - bot) * 0.32F;
        col(tint(WHITE));
        CockpitInstrumentsSVA.line(cx, cy - w * 0.03F, cx, ly + w * 0.05F, w * 0.006F, Z1);
        col(used > 0 ? AMB : 0x50545C);
        rect(cx - w * 0.08F, ly - w * 0.04F, cx + w * 0.08F, ly + w * 0.05F, Z1);
        textC(fr, used + "/" + cap, cx, ly, fs * 1.05F, w * 0.15F, used > 0 ? 0x141414 : tint(WHITE));
        float x = -0.44F * w, bw = 0.88F * w, y = ly - w * 0.12F;
        String trailer = HudVehicleSVA.trailerLine(v);
        String[][] rows = {{Lang.tr("sva.mfd.cargo.hold"), used + " / " + cap + " " + Lang.tr("sva.mfd.cargo.slots")},
                {Lang.tr("sva.mfd.cargo.cable"), Lang.tr("sva.mfd.none")},              // PLACEHOLDER: no sling yet
                {Lang.tr("sva.mfd.cargo.swing"), Lang.tr("sva.mfd.none")},              // PLACEHOLDER
                {Lang.tr("sva.mfd.cargo.trailer"), trailer == null ? Lang.tr("sva.mfd.none") : trailer}};
        int[] cs = {used >= cap ? CYN : GRN, DIMT, DIMT, trailer == null ? DIMT : CYN};
        for (int i = 0; i < rows.length; i++) {
            textL(fr, rows[i][0], x, y - i * fs * 1.6F, fs, bw * 0.35F, tint(WHITE));
            textR(fr, rows[i][1], x + bw, y - i * fs * 1.6F, fs, bw * 0.62F, cs[i]);
        }
    }

    /** СПО page: the scope left, the warning and the transponder's code / mode right. */
    private static void rwrPage(FontRenderer fr, EntityVehicleSVA v, float w, float h, float top, float fs) {
        float R = Math.min(h * 0.24F, w * 0.22F), cx = -w * 0.2F, cy = top - R * 1.25F;
        GL11.glPushMatrix();
        GL11.glTranslatef(cx, cy, 0F);
        scope(fr, v, R);
        GL11.glPopMatrix();
        float x = w * 0.1F, bw = w * 0.38F;
        textL(fr, Lang.tr("sva.mfd.rwr.title"), x, top - fs * 1.5F, fs, bw, tint(WHITE));
        float[][] th = CockpitDataSVA.rwrThreat(v);
        textL(fr, th.length == 0 ? Lang.tr("sva.mfd.rwr.clear") : Lang.tr("sva.mfd.rwr.threats", Integer.valueOf(th.length)), x, top - fs * 3F, fs * 1.05F, bw,
                th.length == 0 ? GRN : 0xFF6E5A);
        textL(fr, Lang.tr("sva.xpdr.title"), x, top - fs * 4.8F, fs, bw, tint(WHITE));
        textL(fr, GlassMath.squawk(CockpitDataSVA.xpdrCode(v)), x, top - fs * 6.6F, fs * 2.1F, bw, AMB);
        textL(fr, Lang.tr("sva.xpdr.mode") + "  " + Lang.tr(CockpitDataSVA.xpdrMode(v)), x, top - fs * 8.6F, fs * 1.05F, bw, GRN);
        boolean reply = CockpitDataSVA.xpdrReply(v);
        col(reply ? GRN : 0x1E2A22);
        CockpitInstrumentsSVA.disc(x + fs * 0.5F, top - fs * 10.2F, fs * 0.5F, Z1);
        textL(fr, Lang.tr("sva.xpdr.reply"), x + fs * 1.4F, top - fs * 10.2F, fs, bw, DIMT);
        textL(fr, Lang.tr("sva.mfd.rwr.note"), -w * 0.44F, cy - R * 1.25F, fs * 0.85F, w * 0.88F, DIMT);
    }

    /** The radar-warning scope at the origin, radius R: rings, ticks, own symbol, the threats (CockpitDataSVA). */
    private static void scope(FontRenderer fr, EntityVehicleSVA v, float R) {
        col(0x050807);
        CockpitInstrumentsSVA.disc(0F, 0F, R, Z0 * 3);
        col(0x287840);
        for (float k : new float[] {0.33F, 0.62F, 0.86F}) {
            AvionicsSVA.ring(0F, 0F, R * k, R * 0.02F, Z1);
        }
        col(0x46C864);
        for (int a = 0; a < 360; a += 30) {
            CockpitInstrumentsSVA.ray(a, R * 0.78F, R * 0.86F, R * 0.03F, Z1);
        }
        col(0x78FF96);
        CockpitInstrumentsSVA.poly(new float[][] {{0F, R * 0.12F}, {-R * 0.1F, -R * 0.08F}, {0F, -R * 0.03F}, {R * 0.1F, -R * 0.08F}}, Z2);
        // air-defence wave: each emitter - a diamond at its bearing (nearer = closer to the centre) with its letter (О обзорная,
        // С сопровождение, В высотомер, Р ракета); a lock is orange, a missile red with the outer ring flashing
        boolean missile = false;
        for (float[] t : CockpitDataSVA.rwrThreat(v)) {
            double a = Math.toRadians(t[0]);
            float rr = R * (float) com.sva.airdefense.RadarMath.rwrRadius(t[1]);
            float x = (float) Math.sin(a) * rr, y = (float) Math.cos(a) * rr, q = R * 0.13F;
            int kind = (int) t[2];
            missile |= kind == com.sva.airdefense.RadarMath.T_MISSILE;
            col(kind == com.sva.airdefense.RadarMath.T_MISSILE ? 0xFF3C32 : kind == com.sva.airdefense.RadarMath.T_LOCK ? 0xFF8C32 : 0xF0C846);
            CockpitInstrumentsSVA.poly(new float[][] {{x, y + q}, {x + q, y}, {x, y - q}, {x - q, y}}, Z2);
            textC(fr, Lang.tr("sva.ad.rwr.letter." + kind), x, y + q * 1.6F, R * 0.12F, R * 0.3F, 0xFFFFFF);
        }
        if (missile && System.currentTimeMillis() / 250 % 2 == 0) {
            col(0xFF3C32);
            AvionicsSVA.ring(0F, 0F, R * 0.95F, R * 0.04F, Z2);
        }
        textC(fr, Lang.tr("sva.mfd.rwr.short"), 0F, -R * 0.5F, R * 0.13F, R * 0.8F, 0x6EE68C);
    }

    // ---------------------------------------------------------------- the plane's MFD keys

    private static void mfdKeys(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v, VehicleType t) {
        CockpitInstrumentsSVA.frame(s.c, s.n, s.up, s.right, s.front + CockpitInstrumentsSVA.LIFT_FACE);
        float w = s.w, h = s.h;
        col(0x1C1D22);
        rect(-w / 2, -h / 2, w / 2, h / 2, 0F);
        int[] pages = GlassMath.pages(t);
        int page = MfdPagesSVA.page(v);
        boolean lit = phase() != CockpitLamps.PH_DARK && phase() != CockpitLamps.PH_LOGO;
        float kw = w / pages.length;
        for (int i = 0; i < pages.length; i++) {
            float a = -w / 2 + i * kw + kw * 0.1F, b = -w / 2 + (i + 1) * kw - kw * 0.1F;
            box(a, -h * 0.4F, b, h * 0.4F, h * 0.06F, 0x6E7078, 0x40424A, Z0);
            boolean on = lit && (phase() == CockpitLamps.PH_TEST || pages[i] == page);
            col(on ? GRN : 0x1E2A22);
            rect(a + (b - a) * 0.3F, h * 0.12F, b - (b - a) * 0.3F, h * 0.28F, Z1);
            textC(fr, Lang.tr("sva.mfd.page." + GlassMath.PAGE_KEYS[pages[i]]), (a + b) / 2, -h * 0.12F, h * 0.3F, (b - a) * 0.86F, tint(0xC8CAD0));
        }
        GL11.glPopMatrix();
    }

    // ---------------------------------------------------------------- the lamp strip

    private static void lampBar(FontRenderer fr, CockpitLayout.Screen s, VehicleType t) {
        CockpitInstrumentsSVA.frame(s.c, s.n, s.up, s.right, s.front + CockpitInstrumentsSVA.LIFT_FACE);
        float w = s.w, h = s.h;
        col(0x0E0E10);
        rect(-w / 2, -h / 2, w / 2, h / 2, 0F);
        int[] roles = CockpitLamps.strip(t);
        int n = Math.max(1, roles.length);
        float cw = w / n;
        int ph = phase();
        long ms = System.currentTimeMillis();
        boolean pw = CockpitInstrumentsSVA.SNAP.powered;
        for (int i = 0; i < roles.length; i++) {
            int role = roles[i];
            float x0 = -w / 2 + i * cw + cw * 0.04F, x1 = x0 + cw * 0.92F, y0 = -h * 0.4F, y1 = h * 0.4F;
            boolean on = ph == CockpitLamps.PH_TEST && pw || ph != CockpitLamps.PH_DARK && ph != CockpitLamps.PH_LOGO
                    && CockpitLamps.lit(CockpitLamps.state(role, CockpitInstrumentsSVA.SNAP), ms);
            int c = CockpitLamps.colour(role);
            col(on ? c : CockpitLamps.mix(c, 0x101012, 0.84F));
            rect(x0, y0, x1, y1, Z0);
            if (ph == CockpitLamps.PH_DARK) {
                continue;
            }
            String label = role == CockpitLamps.FLAP_LIMIT && com.sva.vehicle.HighLiftMath.spec(t) != null ? Lang.tr("sva.cockpit.lamp.flapLimit",
                    String.valueOf(com.sva.vehicle.HighLiftMath.limitKmh(com.sva.vehicle.HighLiftMath.spec(t), 1))) : Lang.tr("sva.cockpit.lamp." + CockpitLamps.KEYS[role]);
            int tc = on ? 0x141008 : CockpitLamps.mix(c, 0x101012, 0.5F);
            twoLines(fr, label, (x0 + x1) / 2, 0F, (y1 - y0) * 0.36F, (x1 - x0) * 0.9F, tc);
        }
        GL11.glPopMatrix();
    }

    /** A label centred at (x, y): one line if it fits at height h, else split at its middle space into two. */
    private static void twoLines(FontRenderer fr, String s, float x, float y, float h, float maxW, int c) {
        int px = fr.getStringWidth(s);
        int sp = s.indexOf(' ', s.length() / 2 - 1);
        if (sp < 0) {
            sp = s.lastIndexOf(' ');
        }
        if (px * h / 8F <= maxW || sp <= 0) {
            textC(fr, s, x, y, h, maxW, c);
            return;
        }
        textC(fr, s.substring(0, sp), x, y + h * 0.62F, h, maxW, c);
        textC(fr, s.substring(sp + 1), x, y - h * 0.62F, h, maxW, c);
    }

    // ---------------------------------------------------------------- the transponder (the plane's vertical one)

    private static void xpdr(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v) {
        CockpitInstrumentsSVA.frame(s.c, s.n, s.up, s.right, s.front + CockpitInstrumentsSVA.LIFT_FACE);
        float w = s.w, h = s.h;
        col(0x1E1F24);
        rect(-w / 2, -h / 2, w / 2, h / 2, 0F);
        int ph = phase();
        textC(fr, Lang.tr("sva.xpdr.title"), 0F, h * 0.42F, h * 0.08F, w * 0.9F, tint(WHITE));
        box(-w * 0.45F, h * 0.12F, w * 0.45F, h * 0.34F, h * 0.01F, 0x5A5C64, 0x040506, Z0);
        if (ph != CockpitLamps.PH_DARK && ph != CockpitLamps.PH_LOGO) {
            textC(fr, ph == CockpitLamps.PH_TEST ? "8888" : GlassMath.squawk(CockpitDataSVA.xpdrCode(v)), 0F, h * 0.23F, h * 0.15F, w * 0.82F, AMB);
        }
        // the modes: ВЫКЛ ДЕЖ A C S, the current one green
        String cur = CockpitDataSVA.xpdrMode(v);
        String[] modes = {"sva.xpdr.mode.off", "sva.xpdr.mode.stby", "sva.xpdr.mode.a", "sva.xpdr.mode.c", "sva.xpdr.mode.s"};
        col(0x0C0C0E);
        CockpitInstrumentsSVA.disc(0F, -h * 0.08F, h * 0.1F, Z0);
        int idx = 1;
        for (int i = 0; i < modes.length; i++) {
            idx = modes[i].equals(cur) ? i : idx;
        }
        for (int i = 0; i < modes.length; i++) {
            double a = Math.toRadians(-100 + i * 50);
            float x = (float) Math.sin(a) * h * 0.2F, y = -h * 0.08F + (float) Math.cos(a) * h * 0.2F;
            textC(fr, Lang.tr(modes[i]), x, y, h * 0.06F, w * 0.3F, i == idx && ph == CockpitLamps.PH_NORMAL ? GRN : tint(WHITE));
        }
        col(0x8C9098);
        CockpitInstrumentsSVA.ray(-100 + idx * 50, 0F, h * 0.09F, h * 0.025F, Z1);
        box(-w * 0.42F, -h * 0.46F, w * 0.12F, -h * 0.3F, h * 0.01F, 0x6E7078, 0x3A3C42, Z0);
        textC(fr, Lang.tr("sva.xpdr.ident"), -w * 0.15F, -h * 0.38F, h * 0.06F, w * 0.48F, tint(0xDCDCD6));
        boolean reply = ph == CockpitLamps.PH_TEST || ph == CockpitLamps.PH_NORMAL && CockpitDataSVA.xpdrReply(v);
        col(reply ? GRN : 0x1E2A22);
        CockpitInstrumentsSVA.disc(w * 0.3F, -h * 0.38F, h * 0.05F, Z1);
        // air-defence wave: IDENT lit while it flashes on the radars; the КЛЮЧ ДНЯ lamp under the code
        boolean ident = ph == CockpitLamps.PH_TEST || ph == CockpitLamps.PH_NORMAL && CockpitDataSVA.xpdrIdent(v);
        col(ident ? 0x5EC8F0 : 0x1E2630);
        rect(-w * 0.3F, -h * 0.33F, w * 0.0F, -h * 0.31F, Z1);
        if (ph == CockpitLamps.PH_NORMAL || ph == CockpitLamps.PH_TEST) {
            boolean key = ph == CockpitLamps.PH_TEST || CockpitDataSVA.xpdrKeyLamp(v);
            textC(fr, Lang.tr("sva.ad.xpdr.dayKey"), w * 0.34F, h * 0.04F, h * 0.045F, w * 0.28F, key ? AMB : 0x3A3020);
        }
        GL11.glPopMatrix();
    }

    // ---------------------------------------------------------------- round faces

    private static void rwrFace(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v) {
        if (open(fr, s, true)) {
            scope(fr, v, Math.min(s.w, s.h) / 2 * 0.9F);
        }
        GL11.glPopMatrix();
    }

    /** ОБОРОТЫ: one 0..120 % scale, the rotor (НВ: long white needle) and the engine (ДВ: short amber one), green 95..105. */
    private static void tach(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v) {
        if (!open(fr, s, true)) {
            GL11.glPopMatrix();
            return;
        }
        float R = Math.min(s.w, s.h) / 2 * 0.92F;
        float[][] arcs = {{0, 90, 0x5A5C64}, {90, 95, AMB}, {95, 105, GRN}, {105, 110, AMB}, {110, 120, RED}};
        for (float[] a : arcs) {
            col((int) a[2]);
            arc(R * 0.82F, GlassMath.angle(GlassMath.tachFrac(a[0])), GlassMath.angle(GlassMath.tachFrac(a[1])), R * 0.07F, Z1);
        }
        scaleMarks(fr, R, 120, 10, 20);
        textC(fr, Lang.tr("sva.gauge.rpm"), 0F, R * 0.3F, R * 0.12F, R * 1.2F, tint(WHITE));
        textC(fr, Lang.tr("sva.gauge.rotor"), -R * 0.22F, -R * 0.5F, R * 0.14F, R * 0.5F, tint(WHITE));
        textC(fr, Lang.tr("sva.gauge.engine"), R * 0.22F, -R * 0.5F, R * 0.14F, R * 0.5F, AMB);
        float rot = rotorPct(v), eng = GlassMath.engineRpm(rot, v.isAutorotating());
        col(AMB);
        CockpitInstrumentsSVA.needle(GlassMath.angle(GlassMath.tachFrac(eng)), R * 0.6F, R * 0.1F, R * 0.05F, Z2);
        col(0xF2F2F6);
        CockpitInstrumentsSVA.needle(GlassMath.angle(GlassMath.tachFrac(rot)), R * 0.8F, R * 0.14F, R * 0.04F, Z2 + 0.0001F);
        col(0x8C9098);
        CockpitInstrumentsSVA.disc(0F, 0F, R * 0.07F, Z3);
        GL11.glPopMatrix();
    }

    /** The scale's ticks (every `minor`, long + a number every `major`) over 0..max. */
    private static void scaleMarks(FontRenderer fr, float R, int max, int minor, int major) {
        col(tint(WHITE));
        for (int v = 0; v <= max; v += minor) {
            float a = GlassMath.angle(v / (float) max);
            boolean big = v % major == 0;
            CockpitInstrumentsSVA.ray(a, R * (big ? 0.73F : 0.79F), R * 0.86F, R * (big ? 0.035F : 0.02F), Z1);
            if (big) {
                double t = Math.toRadians(a);
                textC(fr, String.valueOf(v), (float) Math.sin(t) * R * 0.58F, (float) Math.cos(t) * R * 0.58F, R * 0.13F, R * 0.36F, tint(WHITE));
            }
        }
    }

    /** МОЩНОСТЬ: 0..120 %, green / amber / dark red arcs, the red line at 100, the needle, the digital % under the hub. */
    private static void torque(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v, Readings r) {
        if (!open(fr, s, true)) {
            GL11.glPopMatrix();
            return;
        }
        float R = Math.min(s.w, s.h) / 2 * 0.92F;
        float[][] arcs = {{0, 85, GRN}, {85, 100, AMB}, {100, 120, 0x781E1A}};
        for (float[] a : arcs) {
            col((int) a[2]);
            arc(R * 0.82F, GlassMath.angle(GlassMath.tachFrac(a[0])), GlassMath.angle(GlassMath.tachFrac(a[1])), R * 0.07F, Z1);
        }
        col(RED);
        CockpitInstrumentsSVA.ray(GlassMath.angle(GlassMath.tachFrac(100)), R * 0.62F, R * 0.9F, R * 0.06F, Z1 + 0.0001F);
        scaleMarks(fr, R, 120, 20, 20);
        float pw = powerPct(v, r.vy);
        if (phase() == CockpitLamps.PH_SWEEP) {
            pw = 0F;
        }
        textC(fr, Lang.tr("sva.gauge.power"), 0F, R * 0.3F, R * 0.12F, R * 1.2F, tint(WHITE));
        textC(fr, Math.round(pw) + " %", 0F, -R * 0.42F, R * 0.2F, R * 0.8F, pw > 100 ? RED : GRN);
        col(0xF2F2F6);
        CockpitInstrumentsSVA.needle(GlassMath.angle(GlassMath.tachFrac(pw)), R * 0.8F, R * 0.14F, R * 0.04F, Z2);
        col(0x8C9098);
        CockpitInstrumentsSVA.disc(0F, 0F, R * 0.07F, Z3);
        GL11.glPopMatrix();
    }

    /** ВИСЕНИЕ: the machine at the centre, the drift vector (cyan) in its own frame, rings at 1 and 2 m/s. */
    private static void hover(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v, float pt) {
        if (!open(fr, s, true)) {
            GL11.glPopMatrix();
            return;
        }
        float R = Math.min(s.w, s.h) / 2 * 0.92F;
        float ring = R * 0.7F / 2F;                                     // 1 m/s
        col(0x468258);
        AvionicsSVA.ring(0F, 0F, ring, R * 0.02F, Z1);
        AvionicsSVA.ring(0F, 0F, ring * 2, R * 0.02F, Z1);
        col(0x464A52);
        CockpitInstrumentsSVA.line(-R * 0.8F, 0F, R * 0.8F, 0F, R * 0.015F, Z1);
        CockpitInstrumentsSVA.line(0F, -R * 0.8F, 0F, R * 0.8F, R * 0.015F, Z1);
        textC(fr, Lang.tr("sva.gauge.fwd"), 0F, R * 0.84F, R * 0.1F, R * 0.6F, DIMT);
        textC(fr, Lang.tr("sva.gauge.back"), 0F, -R * 0.84F, R * 0.1F, R * 0.6F, DIMT);
        textC(fr, Lang.tr("sva.gauge.left"), -R * 0.84F, 0F, R * 0.12F, R * 0.2F, DIMT);
        textC(fr, Lang.tr("sva.gauge.right"), R * 0.84F, 0F, R * 0.12F, R * 0.2F, DIMT);
        textC(fr, Lang.tr("sva.gauge.hover"), 0F, -R * 0.55F, R * 0.12F, R * 0.9F, tint(WHITE));
        float yaw = v.prevRotationYaw + MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
        float[] d = GlassMath.clampDrift(GlassMath.drift(v.posX - v.prevPosX, v.posZ - v.prevPosZ, yaw));
        float dx = d[1] * ring, dy = d[0] * ring;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        col(CYN);
        if (len > R * 0.04F && phase() == CockpitLamps.PH_NORMAL) {
            CockpitInstrumentsSVA.line(0F, 0F, dx, dy, R * 0.05F, Z2);
            float ux = dx / len, uy = dy / len, q = R * 0.1F;
            AvionicsSVA.tri(dx + ux * q * 0.4F, dy + uy * q * 0.4F, dx - ux * q + uy * q * 0.5F, dy - uy * q - ux * q * 0.5F, dx - ux * q - uy * q * 0.5F,
                    dy - uy * q + ux * q * 0.5F, Z2);
        }
        CockpitInstrumentsSVA.disc(dx, dy, R * 0.045F, Z2 + 0.0001F);
        col(YEL);
        AvionicsSVA.tri(0F, R * 0.1F, -R * 0.07F, -R * 0.07F, R * 0.07F, -R * 0.07F, Z3);
        GL11.glPopMatrix();
    }

    /** РВ: blocks over the ground on the compressed scale (GlassMath.radaltFrac), red 0..3, amber 3..10, the bug at 5, the window. */
    private static void radalt(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v) {
        if (!open(fr, s, true)) {
            GL11.glPopMatrix();
            return;
        }
        float R = Math.min(s.w, s.h) / 2 * 0.92F;
        col(RED);
        arc(R * 0.82F, GlassMath.angle(0F), GlassMath.angle(GlassMath.radaltFrac(3)), R * 0.07F, Z1);
        col(AMB);
        arc(R * 0.82F, GlassMath.angle(GlassMath.radaltFrac(3)), GlassMath.angle(GlassMath.radaltFrac(10)), R * 0.07F, Z1);
        col(tint(WHITE));
        for (int x : new int[] {0, 2, 4, 6, 8, 10, 20, 30, 40, 50}) {
            float a = GlassMath.angle(GlassMath.radaltFrac(x));
            CockpitInstrumentsSVA.ray(a, R * 0.72F, R * 0.86F, R * 0.03F, Z1);
            if (x == 0 || x == 4 || x == 10 || x == 20 || x == 30 || x == 50) {
                double t = Math.toRadians(a);
                textC(fr, String.valueOf(x), (float) Math.sin(t) * R * 0.57F, (float) Math.cos(t) * R * 0.57F, R * 0.13F, R * 0.3F, tint(WHITE));
            }
        }
        double b = Math.toRadians(GlassMath.angle(GlassMath.radaltFrac(GlassMath.RADALT_BUG)));
        float bx = (float) Math.sin(b), by = (float) Math.cos(b);
        col(MAG);
        AvionicsSVA.tri(bx * R * 0.93F, by * R * 0.93F, bx * R * 0.8F + by * R * 0.06F, by * R * 0.8F - bx * R * 0.06F, bx * R * 0.8F - by * R * 0.06F,
                by * R * 0.8F + bx * R * 0.06F, Z1 + 0.0001F);
        float h = agl(v);
        boolean over = h > GlassMath.RADALT_MAX;
        textC(fr, Lang.tr("sva.gauge.radalt"), 0F, -R * 0.72F, R * 0.11F, R * 1.1F, tint(WHITE));
        box(-R * 0.3F, -R * 0.58F, R * 0.3F, -R * 0.3F, R * 0.02F, 0x5A5C64, 0x000000, Z1);
        textC(fr, over ? "---" : String.valueOf(Math.round(h)), 0F, -R * 0.44F, R * 0.22F, R * 0.5F, GRN);
        col(0xF2F2F6);
        float frac = phase() == CockpitLamps.PH_SWEEP ? 0F : GlassMath.radaltFrac(Math.min(h, GlassMath.RADALT_MAX));
        CockpitInstrumentsSVA.needle(GlassMath.angle(frac), R * 0.8F, R * 0.14F, R * 0.04F, Z2);
        col(0x8C9098);
        CockpitInstrumentsSVA.disc(0F, 0F, R * 0.07F, Z3);
        GL11.glPopMatrix();
    }

    // ---------------------------------------------------------------- the radar altimeter's callouts

    /**
     * «50.. 40.. 30..» (config vehicles.radarAltCallouts / radarAltCalloutStyle): on the descent through each callout height
     * a tone (pitch rising as the ground nears) and / or the number flashed on the HUD (HudVehicleSVA, no chat). Only for
     * this client's riders, never in the startup or paused.
     */
    private static void callouts(EntityVehicleSVA v, float agl) {
        State s = state(v);
        Minecraft mc = Minecraft.getMinecraft();
        float was = s.lastAgl;
        s.lastAgl = agl;
        if (!ConfigSVA.radarAltCallouts || Float.isNaN(was) || mc.isGamePaused() || phase() != CockpitLamps.PH_NORMAL || !v.powered()) {
            return;
        }
        int c = GlassMath.callout(was, agl);
        if (c < 0) {
            return;
        }
        String style = ConfigSVA.radarAltCalloutStyle;
        if (!"flash".equals(style) && mc.thePlayer != null) {
            mc.getSoundHandler().playSound(new PositionedSoundRecord(new ResourceLocation("note.pling"), 0.35F, 0.8F + (50 - c) / 50F,
                    (float) mc.thePlayer.posX, (float) mc.thePlayer.posY, (float) mc.thePlayer.posZ));
        }
        if (!"sound".equals(style)) {
            hudFlash = c;
            hudFlashUntil = System.currentTimeMillis() + FLASH_MS;
        }
    }

    /** HudVehicleSVA: the callout flashed now over the crosshair, big, amber (nothing in the chat). */
    static void drawHudFlash(Minecraft mc, ScaledResolution res) {
        if (hudFlash < 0 || System.currentTimeMillis() > hudFlashUntil) {
            hudFlash = -1;
            return;
        }
        String t = String.valueOf(hudFlash);
        FontRenderer fr = mc.fontRenderer;
        GL11.glPushMatrix();
        GL11.glTranslatef(res.getScaledWidth() / 2F, res.getScaledHeight() / 2F - 30F, 0F);
        GL11.glScalef(2.5F, 2.5F, 1F);
        fr.drawStringWithShadow(t, -fr.getStringWidth(t) / 2, -4, 0xFFC23A);
        GL11.glPopMatrix();
    }
}
