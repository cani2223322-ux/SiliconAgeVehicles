package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AirfieldMath;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CockpitView;
import com.sva.vehicle.HudMath;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;

/**
 * Aircraft round 2, stage D3 (★18, mock-up docs/mockups/veh_air3_4_cockpit.png, the HUD half): the jet's head-up display,
 * drawn by RenderVehicleSVA in the pilot's own first-person view, in the model frame after the model (the airframe is
 * drawn level there, so the symbols stay on the aircraft's axis when the pilot looks round). The symbols lie on a plane
 * HudMath.DIST ahead of the eye (HudMath: conformal angles), clipped by four GL clip planes to the canopy's window box
 * (VehicleType.cabWindows - the box the cockpit view cuts the shell with), depth-tested without depth writes: the
 * instrument panel's coaming and the canopy frame hide what lies behind them. One green, additive, dimmer at night.
 * Contents (left to right, top to bottom): the heading tape (true degrees, the set heading's bug with КУРС on), the
 * speed tape (km/h, digits blink over HudMath.SPEED_BLINK), the altitude tape (blocks, Y), the pitch ladder every 5 deg
 * with the horizon (turned with the bank), the airframe's axis (W), the flight-path vector, «СВАЛИВАНИЕ», the load factor
 * and the vertical speed; the bottom row: the throttle («ФОРС» on the afterburner), the autopilot's modes (as the panel),
 * the distance to the runway ЗАХВ flies to - or, from stage D4, to the flight plan's waypoint
 * (EntityVehicleSVA.hudWaypoint, HudMath.target).
 */
@SideOnly(Side.CLIENT)
final class HudJetSVA {

    /** The symbols' green; line width and text heights (deg on the plane). */
    private static final float R = 0.36F, G = 1F, B = 0.52F;
    private static final float LINE_DEG = 0.13F, TEXT_DEG = 1.15F, BIG_DEG = 1.5F;
    private static final java.nio.DoubleBuffer EQ = org.lwjgl.BufferUtils.createDoubleBuffer(4);

    private HudJetSVA() {
    }

    static void render(EntityVehicleSVA v, VehicleType t, float pt) {
        double[] eye = CockpitView.eye(t, 0);
        float[] box = HudMath.glass(t.cabWindows, eye);
        if (box == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc.fontRenderer;
        float bright = HudMath.brightness(VehicleLightsSVA.darkness(v));
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_TRANSFORM_BIT);
        try {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            // only through the glass: the window box's y / z faces as clip planes (model space, set before the HUD's frame)
            double[][] planes = HudMath.clipPlanes(box);
            for (int i = 0; i < 4; i++) {
                EQ.clear();
                EQ.put(planes[i]).flip();
                GL11.glClipPlane(GL11.GL_CLIP_PLANE0 + i, EQ);
                GL11.glEnable(GL11.GL_CLIP_PLANE0 + i);
            }
            CockpitInstrumentsSVA.frame(new float[] {HudMath.planeX(eye), (float) eye[1], (float) eye[2]}, new float[] {-1F, 0F, 0F},
                    new float[] {0F, 1F, 0F}, new float[] {0F, 0F, 1F}, 0F);
            GL11.glColor4f(R * bright, G * bright, B * bright, 0.9F);
            draw(fr, v, t, pt, bright);
            GL11.glPopMatrix();
            for (int i = 0; i < 4; i++) {
                GL11.glDisable(GL11.GL_CLIP_PLANE0 + i);
            }
        } finally {
            GL11.glDepthMask(true);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GL11.glPopAttrib();
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }

    private static float a(double deg) {
        return HudMath.at(deg);
    }

    private static void line(double x0, double y0, double x1, double y1) {
        CockpitInstrumentsSVA.line(a(x0), a(y0), a(x1), a(y1), a(LINE_DEG), 0F);
    }

    private static void lineAt(float x0, float y0, float x1, float y1) {
        CockpitInstrumentsSVA.line(x0, y0, x1, y1, a(LINE_DEG), 0F);
    }

    private static void text(FontRenderer fr, String s, double xDeg, double yDeg, float hDeg, int rgb) {
        CockpitInstrumentsSVA.text(fr, s, a(xDeg), a(yDeg), a(hDeg), a(30), rgb);
    }

    private static void textAt(FontRenderer fr, String s, float x, float y, float hDeg, int rgb) {
        CockpitInstrumentsSVA.text(fr, s, x, y, a(hDeg), a(30), rgb);
    }

    private static void colour(float bright) {
        GL11.glColor4f(R * bright, G * bright, B * bright, 0.9F);
    }

    private static void draw(FontRenderer fr, EntityVehicleSVA v, VehicleType t, float pt, float bright) {
        int rgb = (int) (R * 255 * bright) << 16 | (int) (G * 255 * bright) << 8 | (int) (B * 255 * bright);
        boolean blinkOn = System.currentTimeMillis() / 250 % 2 == 0;
        float pitch = v.prevRenderPitch + (v.renderPitch - v.prevRenderPitch) * pt;
        float roll = v.prevRenderRoll + (v.renderRoll - v.prevRenderRoll) * pt;
        double alt = v.prevPosY + (v.posY - v.prevPosY) * pt;
        double hdg = AvionicsSVA.heading(v, pt);
        int kmh = VehicleMath.kmh(Math.max(0, v.speed));
        // ---- the airframe's axis (W)
        line(-2.6, 0, -1.2, 0);
        line(-1.2, 0, -0.6, -0.7);
        line(-0.6, -0.7, 0, 0);
        line(0, 0, 0.6, -0.7);
        line(0.6, -0.7, 1.2, 0);
        line(1.2, 0, 2.6, 0);
        // ---- the pitch ladder, turned with the bank (a bank right tilts the horizon's right end up)
        GL11.glPushMatrix();
        GL11.glRotatef(roll, 0F, 0F, 1F);
        for (int r : HudMath.rungs(pitch)) {
            float y = HudMath.ladderY(r, pitch);
            if (r == 0) {
                lineAt(a(-20), y, a(-3), y);
                lineAt(a(3), y, a(20), y);
                continue;
            }
            float tick = a(r > 0 ? -0.7 : 0.7);
            for (int sd = -1; sd <= 1; sd += 2) {
                float x0 = a(sd * 3.2), x1 = a(sd * 9);
                if (r > 0) {
                    lineAt(x0, y, x1, y);
                } else {
                    for (int k = 0; k < 4; k++) {                         // below the horizon: dashed
                        float f0 = k / 4F, f1 = f0 + 0.16F;
                        lineAt(x0 + (x1 - x0) * f0, y, x0 + (x1 - x0) * f1, y);
                    }
                }
                lineAt(x1, y, x1, y + tick);
                textAt(fr, String.valueOf(Math.abs(r)), a(sd * 10.6), y, TEXT_DEG, rgb);
            }
        }
        GL11.glPopMatrix();
        colour(bright);
        // ---- the flight-path vector (where it goes), not turned with the bank
        double[] bd = HudMath.body(v.posX - v.prevPosX, v.posZ - v.prevPosZ, v.rotationYaw);
        float[] f = HudMath.fpv(bd[0], bd[1], v.posY - v.prevPosY, pitch);
        if (f != null) {
            float rr = a(0.6);
            ring(f[0], f[1], rr);
            lineAt(f[0] - rr * 2.6F, f[1], f[0] - rr, f[1]);
            lineAt(f[0] + rr, f[1], f[0] + rr * 2.6F, f[1]);
            lineAt(f[0], f[1] + rr, f[0], f[1] + rr * 2F);
        }
        // ---- the heading tape (true degrees across), the box, the set heading's bug
        float hy = a(HudMath.HDG_Y);
        lineAt(a(-22), hy, a(22), hy);
        for (int m = (int) Math.floor(hdg / 5) * 5 - 25; m <= hdg + 25; m += 5) {
            float x = HudMath.headingX(m, hdg);
            if (Float.isNaN(x)) {
                continue;
            }
            boolean ten = ((m % 10) + 10) % 10 == 0;
            lineAt(x, hy, x, hy + a(ten ? 0.9 : 0.5));
            if (ten) {
                int n = ((m % 360) + 360) % 360 / 10;
                textAt(fr, (n < 10 ? "0" : "") + n, x, hy + a(1.9), TEXT_DEG, rgb);
            }
        }
        lineAt(0F, hy, -a(0.5), hy - a(0.8));                           // the caret under the tape's centre
        lineAt(0F, hy, a(0.5), hy - a(0.8));
        String h = String.format(java.util.Locale.ROOT, "%03d", Math.round(hdg) % 360);
        frameBox(0F, hy - a(2.0), a(2.6), a(0.95));
        textAt(fr, h, 0F, hy - a(2.0), BIG_DEG, rgb);
        AutopilotMath.Panel ap = CockpitInstrumentsSVA.apView(v);
        if (ap != null && ap.on && ap.mode(AutopilotMath.bit(AutopilotMath.G_HDG))) {
            float x = HudMath.headingX(ap.hdg, hdg);
            if (!Float.isNaN(x)) {
                lineAt(x - a(0.6), hy - a(0.1), x, hy + a(0.9));
                lineAt(x + a(0.6), hy - a(0.1), x, hy + a(0.9));
            }
        }
        // ---- the speed tape (left) and the altitude tape (right)
        tape(fr, kmh, HudMath.SPEED_X, HudMath.SPEED_DEG, 10, -1, rgb, kmh <= HudMath.SPEED_BLINK || blinkOn, Lang.tr("sva.jethud.kmh"));
        tape(fr, (int) Math.round(alt), HudMath.ALT_X, HudMath.ALT_DEG, 10, 1, rgb, true, Lang.tr("sva.jethud.alt"));
        // ---- the stall warning
        boolean air = v.airborneAt(v.posX, v.posY, v.posZ);
        if (HudMath.stallWarn(air, v.isStalling(), Math.max(0, v.speed), v.stallSpeed()) && blinkOn) {
            text(fr, Lang.tr("sva.jethud.stall"), 0, -5.5, BIG_DEG, rgb);
        }
        // ---- row 1: the load factor (under the speed tape), the vertical speed in m/s (under the altitude tape)
        text(fr, Lang.tr("sva.jethud.g") + " " + one(HudMath.g(roll)), HudMath.SPEED_X - 1, HudMath.ROW1_Y, TEXT_DEG, rgb);
        text(fr, Lang.tr("sva.jethud.vs") + " " + one((v.posY - v.prevPosY) * 20), HudMath.ALT_X, HudMath.ROW1_Y, TEXT_DEG, rgb);
        // ---- the autopilot's modes (row 1, centre); row 2: the throttle, the distance to the runway / waypoint
        boolean ab = v.isBoosting() && t.gases.boostGas != null;
        text(fr, Lang.tr("sva.jethud.thr") + " " + (ab ? Lang.tr("sva.jethud.ab") : Math.round(v.getThrottle() * 100) + "%"), HudMath.SPEED_X - 1, HudMath.ROW2_Y,
                TEXT_DEG, rgb);
        if (ap != null && ap.on) {
            StringBuilder b = new StringBuilder(Lang.tr("sva.ap.group.ap"));
            for (int g = AutopilotMath.G_HDG; g <= AutopilotMath.G_ROUTE; g++) {
                if (ap.mode(AutopilotMath.bit(g))) {
                    b.append("  ").append(Lang.tr("sva.ap.group." + AutopilotMath.GROUP_KEYS[g]));
                }
            }
            text(fr, b.toString(), 0, HudMath.ROW1_Y, TEXT_DEG, rgb);         // the centre of row 1 (row 2's sides are full)
        }
        AutopilotMath.Capture c = v.capture();
        HudMath.Target tg = HudMath.target(v.hudWaypoint(), v.posX, v.posZ, c == null || c.out ? null : AirfieldMath.designator(c.course),
                c == null || c.out ? Double.NaN : c.along);
        if (tg.kind != HudMath.T_NONE) {
            String[] d = HudMath.distance(tg.dist);
            String k = tg.kind == HudMath.T_WPT ? "sva.jethud.wpt" : "sva.jethud.rwy";
            text(fr, Lang.tr(k) + " " + tg.label + "  " + d[0] + " " + Lang.tr(d[1]), HudMath.ALT_X - 1, HudMath.ROW2_Y, TEXT_DEG, rgb);
        }
    }

    /** A vertical tape at xDeg (side -1: labels to its left - the speed; +1 to its right - the altitude) with the value's box. */
    private static void tape(FontRenderer fr, int value, float xDeg, float degPer, int step, int side, int rgb, boolean digits, String unit) {
        float x = a(xDeg), half = HudMath.TAPE_HALF;
        lineAt(x, a(-half), x, a(half));
        for (int m = (value / step - 6) * step; m <= value + 6 * step; m += step) {
            float y = HudMath.tapeY(m, value, degPer);
            if (Float.isNaN(y)) {
                continue;
            }
            lineAt(x, y, x + side * a(0.8), y);
            if (Math.abs(m - value) > step * 0.6 && m >= 0) {
                textAt(fr, String.valueOf(m), x + side * a(3.0), y, TEXT_DEG, rgb);
            }
        }
        textAt(fr, unit, x + side * a(1.5), a(half + 1.6), TEXT_DEG, rgb);
        float cx = x + side * a(3.2);
        frameBox(cx, 0F, a(2.7), a(1.05));
        lineAt(x, 0F, x + side * a(0.5), 0F);
        if (digits) {
            textAt(fr, String.valueOf(value), cx, 0F, BIG_DEG, rgb);
        }
    }

    /** An outlined box centred at (x, y), half sizes hw / hh (model units). */
    private static void frameBox(float x, float y, float hw, float hh) {
        lineAt(x - hw, y - hh, x + hw, y - hh);
        lineAt(x + hw, y - hh, x + hw, y + hh);
        lineAt(x + hw, y + hh, x - hw, y + hh);
        lineAt(x - hw, y + hh, x - hw, y - hh);
    }

    private static void ring(float x, float y, float r) {
        float px = x + r, py = y;
        for (int k = 1; k <= 16; k++) {
            double ang = k * Math.PI * 2 / 16;
            float nx = x + (float) Math.cos(ang) * r, ny = y + (float) Math.sin(ang) * r;
            lineAt(px, py, nx, ny);
            px = nx;
            py = ny;
        }
    }

    private static String one(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
