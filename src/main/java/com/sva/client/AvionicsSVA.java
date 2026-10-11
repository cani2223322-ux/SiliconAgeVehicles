package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.HighLiftMath;
import com.sva.vehicle.RouteMath;
import com.sva.vehicle.RunwayMath;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.MathHelper;

/**
 * Aircraft stage C (★17 / ★18 / ★19, mock-up docs/mockups/veh_air2_5_cockpit.png; the 2D art scratchpad air2_art.py): the
 * avionics faces CockpitInstrumentsSVA draws on the named panels CockpitLayout finds (veh_real8.py's emissive faces), in
 * its GL state and frame, with its night tint / glow and its startup phases:
 * <ul>
 * <li>'apanel' - the glare-shield autopilot panel: six windows (АП ВКЛ / ОТКЛ, КУРС, ВЫС, СКОР, ВЕРТ, ЗАХВ ВПП) over six
 * buttons with lamps (the four knobs between them are the model's), the annunciator column on the right (CockpitLamps
 * annunciators: ЗАКР. ВЫП, ПРЕДКР., ВОЗД. ТОРМ, V>120 ЗАКР, СВАЛ.). The values: the driver's own CruiseClientSVA state, a
 * passenger's the synced panel (EntityVehicleSVA.apPanel). Round 2, D4: a seventh window / button МАРШ (the flight plan's
 * active point «Т3», magenta) on the left of the annunciator column, narrowed for it (the six stay over the model's knobs);</li>
 * <li>'hsi' (and the jet's SCREEN_HSI side screen) - the HSI: the compass card turning with the heading, the set-heading
 * bug, the double cyan pointer to «База» (EntityVehicleSVA.basePoint: the pilot's bed / the spawn), the green course
 * pointer and deviation bar onto the nearest runway (EntityVehicleSVA.runway - the same strip ЗАХВ flies to), windows:
 * the distance to the base, the runway's course and the distance to its threshold. D4, МАРШ flying a leg: the course
 * pointer and the bar magenta on the leg (the bar = the cross-track), the top windows the active point's name / distance
 * and the leg's course;</li>
 * <li>'flapind' - the flap position: 0 / 10 / 20 / 35 over a 140 deg arc, the needle on the animated flap angle
 * (AnimMath.State.flapPos), the slats' cyan mark;</li>
 * <li>'trimind' - the pitch trim strip НОС ВНИЗ / ВЗЛ / НОС ВВЕРХ: the cosmetic trim AnimMath.trim (the planes' physics has
 * no trim: it follows the climb and the flaps, see there);</li>
 * <li>'flapgate' - the flap lever's marks plate (beside the lever, tilted to the pilot): the slot and the settings' marks,
 * the current setting amber.</li>
 * </ul>
 * Config vehicles.avionics (off: the faces stay dark).
 */
@SideOnly(Side.CLIENT)
final class AvionicsSVA {

    private static final int WHITE = 0xEBEDF2, DIMT = 0x6E7480, AMB = 0xFFAC28, AMB_DIM = 0x6A4A18, GRN = 0x46EB78, GRN_DIM = 0x2E5A3A,
            CYN = 0x5AD7FF, YEL = 0xFFD63C, MAGENTA = 0xEC5ADC;
    /** The mock-up's AP strip in its art pixels (air2_art.ap_layout: 1680 x 220). */
    private static final float AP_W = 1680F, AP_H = 220F;

    private AvionicsSVA() {
    }

    /** Everything on the type's named panels; the jet's HSI screen goes through hsiScreen. */
    static void draw(FontRenderer fr, CockpitLayout l, EntityVehicleSVA v, VehicleType t, float pt, float sweep) {
        if (!ConfigSVA.avionics || l.panels.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean driver = mc.thePlayer != null && mc.thePlayer.ridingEntity == v;
        AutopilotMath.Panel ap = driver ? CruiseClientSVA.panel(v) : v.apPanel();
        HighLiftMath.Spec spec = HighLiftMath.spec(t);
        float flapPos = AnimMath.lerp(v.anim.prevFlapPos, v.anim.flapPos, pt);
        for (CockpitLayout.Screen s : l.panels) {
            if ("apanel".equals(s.mat)) {
                // cockpit wave (C+): with a lamp strip the annunciators live there - the AP bar is seven equal groups
                apPanel(fr, s, ap, l.panel("lampbar") != null ? new int[0] : CockpitLamps.annunciators(t), spec, v);
            } else if ("hsi".equals(s.mat)) {
                hsi(fr, s, v, pt, ap, true);
            } else if ("flapind".equals(s.mat) && spec != null) {
                float slat = AnimMath.lerp(v.anim.prevSlat, v.anim.slat, pt);
                float deg = CockpitInstrumentsSVA.phase == CockpitLamps.PH_SWEEP ? spec.angles[spec.last()] * sweep : spec.angleAt(flapPos);
                flapInd(fr, s, spec, deg, slat > 0.5F);
            } else if ("trimind".equals(s.mat)) {
                float trim = CockpitInstrumentsSVA.phase == CockpitLamps.PH_SWEEP ? AnimMath.TRIM_MAX * (sweep * 2 - 1)
                        : AnimMath.lerp(v.anim.prevTrim, v.anim.trim, pt);
                trimInd(fr, s, trim);
            } else if ("flapgate".equals(s.mat) && spec != null) {
                gate(fr, s, spec, v.getFlaps());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean dark() {
        int ph = CockpitInstrumentsSVA.phase;
        return ph == CockpitLamps.PH_DARK;
    }

    private static boolean test() {
        return CockpitInstrumentsSVA.phase == CockpitLamps.PH_TEST;
    }

    private static boolean logo() {
        return CockpitInstrumentsSVA.phase == CockpitLamps.PH_LOGO;
    }

    private static void col(int rgb, float k) {
        GL11.glColor4f((rgb >> 16 & 255) / 255F * k, (rgb >> 8 & 255) / 255F * k, (rgb & 255) / 255F * k, 1F);
    }

    private static int tint(int rgb) {
        return CockpitInstrumentsSVA.tint(rgb);
    }

    private static void enter(CockpitLayout.Screen s) {
        CockpitInstrumentsSVA.frame(s.c, s.n, s.up, s.right, s.front + CockpitInstrumentsSVA.LIFT_FACE);
    }

    private static final float Z1 = CockpitInstrumentsSVA.LIFT_MARK - CockpitInstrumentsSVA.LIFT_FACE,
            Z2 = CockpitInstrumentsSVA.LIFT_NEEDLE - CockpitInstrumentsSVA.LIFT_FACE, Z0 = 0.0002F;

    /** Text centred at (x, y), `h` tall at most, `maxW` wide at most, turned `deg` counter-clockwise in the frame. */
    private static void textRot(FontRenderer fr, String s, float x, float y, float h, float maxW, float deg, int color) {
        int px = fr.getStringWidth(s);
        if (px <= 0) {
            return;
        }
        float k = Math.min(h / 8F, maxW / px);
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, CockpitInstrumentsSVA.textLift);
        GL11.glRotatef(deg, 0F, 0F, 1F);
        GL11.glScalef(k, -k, k);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        fr.drawString(s, -px / 2, -4, color);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
    }

    static void tri(float x0, float y0, float x1, float y1, float x2, float y2, float z) {
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex3f(x0, y0, z);
        GL11.glVertex3f(x1, y1, z);
        GL11.glVertex3f(x2, y2, z);
        GL11.glEnd();
    }

    /** A ring of radius r, line width w (segments), in the current colour. */
    static void ring(float x, float y, float r, float w, float z) {
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int k = 0; k <= 24; k++) {
            double a = k * Math.PI * 2 / 24;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            GL11.glVertex3f(x + c * (r - w / 2), y + sn * (r - w / 2), z);
            GL11.glVertex3f(x + c * (r + w / 2), y + sn * (r + w / 2), z);
        }
        GL11.glEnd();
    }

    /** An arc band at radius r (deg clockwise from up, a0 -> a1), width w. */
    static void arc(float r, float a0, float a1, float w, float z) {
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        int n = Math.max(2, (int) Math.ceil(Math.abs(a1 - a0) / 4));
        for (int k = 0; k <= n; k++) {
            double a = Math.toRadians(a0 + (a1 - a0) * k / n);
            float sx = (float) Math.sin(a), cy = (float) Math.cos(a);
            GL11.glVertex3f(sx * (r - w / 2), cy * (r - w / 2), z);
            GL11.glVertex3f(sx * (r + w / 2), cy * (r + w / 2), z);
        }
        GL11.glEnd();
    }

    private static void face(CockpitLayout.Screen s, float glow) {
        if (CockpitInstrumentsSVA.night > 0F) {
            CockpitInstrumentsSVA.glowRect(s.w, s.h, glow * CockpitInstrumentsSVA.night);
        }
    }

    // ---------------------------------------------------------------- ★17 the autopilot panel

    private static float apX(float px, CockpitLayout.Screen s) {
        return (px / AP_W - 0.5F) * s.w;
    }

    private static float apY(float py, CockpitLayout.Screen s) {
        return (0.5F - py / AP_H) * s.h;
    }

    static void apPanel(FontRenderer fr, CockpitLayout.Screen s, AutopilotMath.Panel ap, int[] ann, HighLiftMath.Spec spec, EntityVehicleSVA v) {
        enter(s);
        face(s, 0.3F);
        col(0x26282E, 1F);                                          // the brushed dark panel
        CockpitInstrumentsSVA.rect(-s.w / 2, -s.h / 2, s.w / 2, s.h / 2, 0F);
        // the six groups stand where the model's knobs are; D4's МАРШ takes the left of the annunciator column (narrowed).
        // Cockpit wave (C+, no annunciators: `bar`): seven equal groups across the whole bar (cab2_art.ap_panel lamps='none'),
        // taller windows - the digits 0.3 of the bar's height (>= 19 px at 1920 x 1080) - and the knobs drawn (none modelled)
        boolean bar = ann.length == 0;
        float lampW = bar ? 0F : AP_W * 0.2F, gw = bar ? (AP_W - 20) / 7 : (AP_W - lampW - 30) / 6, lampX = AP_W - lampW - 6, routeW = lampW * 0.48F;
        float wTop = bar ? 10 : 22, wBot = bar ? 104 : 92, bTop = bar ? 114 : 118, bBot = bar ? 206 : 196, digits = bar ? 0.72F : 0.62F;
        boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
        for (int g = 0; g < AutopilotMath.GROUPS; g++) {
            boolean route = g == AutopilotMath.G_ROUTE;
            float gx = route && !bar ? lampX + routeW / 2 : (bar ? 10 : 18) + gw * (g + 0.5F);
            float gwk = route && !bar ? routeW * 0.98F : gw;
            float x0 = apX(gx - gwk * 0.39F, s), x1 = apX(gx + gwk * 0.39F, s), y0 = apY(wBot, s), y1 = apY(wTop, s);
            col(0x101012, 1F);
            CockpitInstrumentsSVA.rect(x0 - s.h * 0.02F, y0 - s.h * 0.02F, x1 + s.h * 0.02F, y1 + s.h * 0.02F, Z0);
            col(0x050607, 1F);
            CockpitInstrumentsSVA.rect(x0, y0, x1, y1, Z0 * 2);
            boolean held = g == AutopilotMath.G_AP ? ap.on : ap.mode(AutopilotMath.bit(g));
            if (!dark()) {
                String txt;
                int c;
                if (test()) {
                    txt = g == AutopilotMath.G_AP || g == AutopilotMath.G_CAP || route ? "888" : "8888";
                    c = g == AutopilotMath.G_AP || g == AutopilotMath.G_CAP ? GRN : route ? MAGENTA : AMB;
                } else if (logo()) {
                    txt = "SA";
                    c = CockpitInstrumentsSVA.glowRgb;
                } else {
                    txt = GuiAutopilotSVA.value(g, ap);
                    if (g == AutopilotMath.G_AP) {
                        c = ap.on ? GRN : GRN_DIM;
                    } else if (g == AutopilotMath.G_CAP) {
                        c = held && ap.runway ? (ap.aligned || blink ? GRN : GRN_DIM) : DIMT;
                    } else if (route) {
                        c = held && ap.routeN > 0 ? MAGENTA : DIMT;          // D4: the active point in magenta
                    } else {
                        c = held ? AMB : AMB_DIM;
                    }
                }
                CockpitInstrumentsSVA.text(fr, txt, (x0 + x1) / 2, (y0 + y1) / 2, (y1 - y0) * digits, (x1 - x0) * 0.9F, c);
            }
            // the button: a dark key with its lamp bar and label (the four knobs beside are the model's)
            boolean knob = g != AutopilotMath.G_AP && g != AutopilotMath.G_CAP && !route;
            float bx = knob ? gx - gw * 0.17F : gx, bw = gw * 0.44F;
            float b0 = apX(bx - bw / 2, s), b1 = apX(bx + bw / 2, s), by0 = apY(bBot, s), by1 = apY(bTop, s);
            if (bar && knob) {
                // the setting knob right of the button (concept ap_cell: a black knob with a steel cap and an index)
                float kx = apX(gx + gw * 0.3F, s), ky = (by0 + by1) / 2, kr = Math.min(apX(gw * 0.14F, s) - apX(0, s), (by1 - by0) * 0.42F);
                col(0x0A0A0C, 1F);
                CockpitInstrumentsSVA.disc(kx, ky, kr, Z0);
                col(0x6E727A, 1F);
                CockpitInstrumentsSVA.disc(kx, ky, kr * 0.62F, Z0 * 2);
                col(0xE8EAEE, 1F);
                GL11.glPushMatrix();
                GL11.glTranslatef(kx, ky, 0F);
                CockpitInstrumentsSVA.ray(-60 + 40 * g, kr * 0.2F, kr * 0.9F, kr * 0.14F, Z1);
                GL11.glPopMatrix();
            }
            col(0x0C0C0E, 1F);
            CockpitInstrumentsSVA.rect(b0 - s.h * 0.015F, by0 - s.h * 0.015F, b1 + s.h * 0.015F, by1 + s.h * 0.015F, Z0);
            col(0x3A3C42, 1F);
            CockpitInstrumentsSVA.rect(b0, by0, b1, by1, Z0 * 2);
            boolean lit = !dark() && !logo() && (test() || held);
            col(lit ? GRN : 0x1E2A22, 1F);
            float lb0 = b0 + (b1 - b0) * 0.28F, lb1 = b1 - (b1 - b0) * 0.28F, ly1 = by1 - (by1 - by0) * 0.12F, ly0 = by1 - (by1 - by0) * 0.27F;
            CockpitInstrumentsSVA.rect(lb0, ly0, lb1, ly1, Z1);
            CockpitInstrumentsSVA.text(fr, Lang.tr("sva.ap.group." + AutopilotMath.GROUP_KEYS[g]), (b0 + b1) / 2, by1 - (by1 - by0) * 0.64F,
                    (by1 - by0) * 0.3F, (b1 - b0) * 0.86F, tint(lit ? 0xFAFAEC : 0xB4B6B0));
        }
        if (bar) {
            GL11.glPopMatrix();
            return;
        }
        // the annunciator column
        float lx0 = apX(lampX + routeW + 8, s), lx1 = apX(AP_W - 22, s);
        col(0x0E0E10, 1F);
        CockpitInstrumentsSVA.rect(lx0 - s.h * 0.03F, apY(AP_H - 16, s), lx1 + s.h * 0.03F, apY(16, s), Z0);
        int n = Math.max(1, ann.length);
        float lh = (AP_H - 44) / n;
        for (int k = 0; k < ann.length; k++) {
            int role = ann[k];
            float ty1 = apY(22 + k * lh, s), ty0 = apY(22 + (k + 1) * lh - 5, s);
            boolean on = test() || !dark() && !logo() && CockpitLamps.lit(CockpitLamps.state(role, CockpitInstrumentsSVA.SNAP), System.currentTimeMillis());
            int rgb = CockpitLamps.colour(role);
            col(on ? rgb : 0x1C1D20, on ? 0.85F : 1F);
            CockpitInstrumentsSVA.rect(lx0, ty0, lx1, ty1, Z0 * 2);
            if (!dark()) {
                String label = role == CockpitLamps.FLAP_LIMIT && spec != null
                        ? Lang.tr("sva.cockpit.lamp.flapLimit", String.valueOf(HighLiftMath.limitKmh(spec, Math.min(spec.last(), v.getFlaps() + 1))))
                        : Lang.tr("sva.cockpit.lamp." + CockpitLamps.KEYS[role]);
                CockpitInstrumentsSVA.text(fr, label, (lx0 + lx1) / 2, (ty0 + ty1) / 2, (ty1 - ty0) * 0.55F, (lx1 - lx0) * 0.9F,
                        on ? 0x101010 : tint(0x585A60));
            }
        }
        GL11.glPopMatrix();
    }

    // ---------------------------------------------------------------- ★19 the HSI

    /** The HSI on a panel (the light plane) or the jet's side screen (`knob`: the model has the course knob in the corner). */
    static void hsi(FontRenderer fr, CockpitLayout.Screen s, EntityVehicleSVA v, float pt, AutopilotMath.Panel ap, boolean knob) {
        enter(s);
        face(s, 0.3F);
        float R = Math.min(s.w, s.h) / 2;
        col(0x1E1F23, 1F);                                          // the square bezel and the round face
        CockpitInstrumentsSVA.rect(-s.w / 2, -s.h / 2, s.w / 2, s.h / 2, 0F);
        col(0x0C0C0E, 1F);
        CockpitInstrumentsSVA.disc(0F, 0F, R * 0.95F, Z0);
        col(0x08090B, 1F);
        CockpitInstrumentsSVA.disc(0F, 0F, R * 0.9F, Z0 * 2);
        if (dark()) {
            GL11.glPopMatrix();
            return;
        }
        if (logo()) {
            CockpitInstrumentsSVA.text(fr, "SA", 0F, 0F, R * 0.5F, R, CockpitInstrumentsSVA.glowRgb);
            GL11.glPopMatrix();
            return;
        }
        float yaw = v.prevRotationYaw + MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
        double hdg = ((yaw + 180F) % 360F + 360F) % 360F;
        float Rc = R * 0.82F;
        CockpitInstrumentsSVA.glColor(tint(WHITE));
        for (int a : new int[] {45, 135, 225, 315}) {                // the fixed 45 deg marks outside the card
            CockpitInstrumentsSVA.ray(a, R * 0.84F, R * 0.9F, R * 0.025F, Z1);
        }
        for (int deg = 0; deg < 360; deg += 5) {                     // the card
            float a = (float) AutopilotMath.cardAngle(deg, hdg);
            boolean major = deg % 10 == 0;
            CockpitInstrumentsSVA.ray(a, Rc * (major ? 0.9F : 0.945F), Rc, R * (major ? 0.022F : 0.012F), Z1);
        }
        for (int deg = 0; deg < 360; deg += 30) {
            float a = (float) AutopilotMath.cardAngle(deg, hdg);
            double r = Math.toRadians(a);
            boolean cardinal = deg % 90 == 0;
            String lab = cardinal ? Lang.tr("sva.hsi.card." + deg / 90) : String.valueOf(deg / 10);
            textRot(fr, lab, (float) Math.sin(r) * Rc * 0.74F, (float) Math.cos(r) * Rc * 0.74F, R * (cardinal ? 0.15F : 0.12F), R * 0.3F, -a,
                    cardinal ? YEL : tint(WHITE));
        }
        // the set-heading bug (amber) on the card's edge
        float bug = (float) Math.toRadians(AutopilotMath.cardAngle(ap.hdg, hdg));
        float ux = (float) Math.sin(bug), uy = (float) Math.cos(bug), vx = uy, vy = -ux;
        CockpitInstrumentsSVA.glColor(AMB);
        float bw = R * 0.06F, bo = Rc * 1.0F, bi = Rc * 0.92F;
        tri(ux * bo + vx * bw, uy * bo + vy * bw, ux * bi, uy * bi, ux * bo - vx * bw, uy * bo - vy * bw, Z2);
        // the bearing pointer to «База» (cyan, double, with its tail)
        int[] base = v.basePoint();
        double baseDist = Double.NaN;
        if (base != null) {
            double b = AutopilotMath.bearing(v.posX, v.posZ, base[0] + 0.5, base[1] + 0.5);
            baseDist = AutopilotMath.distance(v.posX, v.posZ, base[0] + 0.5, base[1] + 0.5);
            float a = (float) Math.toRadians(AutopilotMath.cardAngle(b, hdg));
            ux = (float) Math.sin(a);
            uy = (float) Math.cos(a);
            vx = uy;
            vy = -ux;
            CockpitInstrumentsSVA.glColor(CYN);
            for (int o = -1; o <= 1; o += 2) {
                float ox = vx * R * 0.018F * o, oy = vy * R * 0.018F * o;
                CockpitInstrumentsSVA.line(-ux * Rc * 0.9F + ox, -uy * Rc * 0.9F + oy, ux * Rc * 0.6F + ox, uy * Rc * 0.6F + oy, R * 0.016F, Z1 + 0.0001F);
            }
            tri(ux * Rc * 0.94F, uy * Rc * 0.94F, ux * Rc * 0.58F + vx * R * 0.07F, uy * Rc * 0.58F + vy * R * 0.07F, ux * Rc * 0.58F - vx * R * 0.07F,
                    uy * Rc * 0.58F - vy * R * 0.07F, Z1 + 0.0001F);
        }
        // the course pointer + deviation bar onto the nearest runway (green); round 2, D4: МАРШ flying a leg - onto the leg
        // (magenta: the course from the previous point to the active one, the bar its cross-track; the first leg: direct to)
        RunwayMath.Runway rw = v.runway();
        AutopilotMath.Capture cap = rw == null ? null : AutopilotMath.capture(rw, v.posX, v.posY, v.posZ, hdg, AutopilotMath.DESCENT, 0);
        int[] rt = ap.mode(AutopilotMath.M_ROUTE) ? v.routeTarget() : null;
        boolean leg = rt != null && rt[0] < rt[1];
        double legCourse = 0, legCross = 0, legDist = 0;
        if (leg) {
            double tx = rt[2] + 0.5, tz = rt[3] + 0.5;
            legDist = AutopilotMath.distance(v.posX, v.posZ, tx, tz);
            if (rt[5] == Integer.MIN_VALUE) {
                legCourse = AutopilotMath.bearing(v.posX, v.posZ, tx, tz);
            } else {
                legCourse = RouteMath.course(rt[5] + 0.5, rt[6] + 0.5, tx, tz);
                legCross = RouteMath.crossTrack(rt[5] + 0.5, rt[6] + 0.5, legCourse, v.posX, v.posZ);
            }
        }
        if (cap != null || leg) {
            int cc = leg ? MAGENTA : GRN;
            double crsDeg = leg ? legCourse : cap.course, crossNow = leg ? legCross : cap.cross;
            float a = (float) Math.toRadians(AutopilotMath.cardAngle(crsDeg, hdg));
            ux = (float) Math.sin(a);
            uy = (float) Math.cos(a);
            vx = uy;
            vy = -ux;
            CockpitInstrumentsSVA.glColor(cc);
            float lw = R * 0.05F, zz = Z2;
            CockpitInstrumentsSVA.line(ux * Rc * 0.3F, uy * Rc * 0.3F, ux * Rc * 0.66F, uy * Rc * 0.66F, lw, zz);
            tri(ux * Rc * 0.86F, uy * Rc * 0.86F, ux * Rc * 0.64F + vx * R * 0.08F, uy * Rc * 0.64F + vy * R * 0.08F, ux * Rc * 0.64F - vx * R * 0.08F,
                    uy * Rc * 0.64F - vy * R * 0.08F, zz);
            CockpitInstrumentsSVA.line(-ux * Rc * 0.3F, -uy * Rc * 0.3F, -ux * Rc * 0.7F, -uy * Rc * 0.7F, lw, zz);
            float dot = R * 0.13F;
            CockpitInstrumentsSVA.glColor(tint(WHITE));
            for (int k = -2; k <= 2; k++) {
                if (k != 0) {
                    ring(vx * dot * k, vy * dot * k, R * 0.025F, R * 0.012F, Z1);
                }
            }
            float d = (float) AutopilotMath.cdiDots(crossNow) * dot;
            CockpitInstrumentsSVA.glColor(cc);
            CockpitInstrumentsSVA.line(vx * d - ux * Rc * 0.27F, vy * d - uy * Rc * 0.27F, vx * d + ux * Rc * 0.27F, vy * d + uy * Rc * 0.27F, lw, zz);
            CockpitInstrumentsSVA.glColor(tint(WHITE));                 // the TO triangle
            float tx = ux * R * 0.2F + vx * R * 0.2F, ty = uy * R * 0.2F + vy * R * 0.2F;
            tri(tx + ux * R * 0.05F, ty + uy * R * 0.05F, tx - ux * R * 0.02F + vx * R * 0.04F, ty - uy * R * 0.02F + vy * R * 0.04F,
                    tx - ux * R * 0.02F - vx * R * 0.04F, ty - uy * R * 0.02F - vy * R * 0.04F, Z1);
        }
        // air-defence wave: a forbidden / restricted zone's border ahead within 60 blocks - a dashed line across the card at
        // its distance (the card's rim = 60 blocks), the zone's name and the blocks to it (AirDefenseClientSVA.hsiZone)
        Object[] zb = test() ? null : AirDefenseClientSVA.hsiZone(v);
        if (zb != null) {
            double zd = ((Double) zb[1]).doubleValue();
            int zc = ((Integer) zb[2]).intValue() == com.sva.airdefense.ZoneMath.FORBIDDEN ? 0xF05050 : 0xF0B040;
            float yy = (float) (Rc * 0.85 * Math.min(1, zd / 60)), half = (float) Math.sqrt(Math.max(0, Rc * Rc * 0.81 - yy * yy));
            CockpitInstrumentsSVA.glColor(zc);
            int n = 9;
            for (int k = 0; k < n; k += 2) {
                float a0 = -half + 2 * half * k / n, a1 = -half + 2 * half * (k + 1) / n;
                CockpitInstrumentsSVA.line(a0, yy, a1, yy, R * 0.025F, Z2);
            }
            CockpitInstrumentsSVA.text(fr, zb[0] + "  " + Math.round(zd), 0F, yy - R * 0.09F, R * 0.08F, R * 0.9F, zc);
        }
        // the aircraft symbol (yellow) and the lubber line (amber)
        CockpitInstrumentsSVA.glColor(YEL);
        float zz = CockpitInstrumentsSVA.LIFT_HUB - CockpitInstrumentsSVA.LIFT_FACE;
        CockpitInstrumentsSVA.line(0F, R * 0.15F, 0F, -R * 0.12F, R * 0.035F, zz);
        CockpitInstrumentsSVA.line(-R * 0.17F, R * 0.01F, R * 0.17F, R * 0.01F, R * 0.035F, zz);
        CockpitInstrumentsSVA.line(-R * 0.07F, -R * 0.11F, R * 0.07F, -R * 0.11F, R * 0.03F, zz);
        CockpitInstrumentsSVA.glColor(AMB);
        tri(0F, R * 0.83F, -R * 0.045F, R * 0.93F, R * 0.045F, R * 0.93F, Z2);
        // the corner windows: БАЗА distance, the runway's course, its threshold's distance
        boolean t8 = test();
        if (leg && !t8) {
            // D4: the active point's name and distance, the leg's course (magenta)
            window(fr, s, -1, 1, Lang.tr("sva.ap.route.wp", Integer.valueOf(rt[0] + 1)), AutopilotMath.km(legDist), MAGENTA);
            window(fr, s, 1, 1, Lang.tr("sva.hsi.crs"), String.format("%03d", Integer.valueOf((int) Math.round(legCourse) % 360)), MAGENTA);
        } else {
            window(fr, s, -1, 1, Lang.tr("sva.hsi.base"), t8 ? "8,8" : Double.isNaN(baseDist) ? "--" : AutopilotMath.km(baseDist), CYN);
            window(fr, s, 1, 1, Lang.tr("sva.hsi.crs"), t8 ? "888" : cap == null ? "---" : String.format("%03d", Integer.valueOf((int) Math.round(cap.course) % 360)), GRN);
        }
        window(fr, s, -1, -1, Lang.tr("sva.hsi.rwy"), t8 ? "8,8" : cap == null ? "--" : AutopilotMath.km(Math.max(0, -cap.along)), GRN);
        if (knob) {
            CockpitInstrumentsSVA.text(fr, Lang.tr("sva.hsi.knob"), s.w / 2 - s.w * 0.27F, -s.h / 2 + s.h * 0.05F, s.h * 0.05F, s.w * 0.12F, AMB);
        }
        GL11.glPopMatrix();
    }

    /** A corner window of the HSI (sx, sy = -1 / 1: which corner): its label (dim) and value. */
    private static void window(FontRenderer fr, CockpitLayout.Screen s, int sx, int sy, String label, String value, int c) {
        float ww = s.w * 0.3F, wh = s.h * 0.085F, m = s.w * 0.02F;
        float x0 = sx < 0 ? -s.w / 2 + m : s.w / 2 - m - ww, y1 = sy > 0 ? s.h / 2 - m : -s.h / 2 + m + wh, y0 = y1 - wh;
        col(0x040506, 1F);
        CockpitInstrumentsSVA.rect(x0, y0, x0 + ww, y1, Z1);
        CockpitInstrumentsSVA.textLeft(fr, label, x0 + ww * 0.05F, (y0 + y1) / 2, wh * 0.5F, tint(0x6E7480));
        int px = fr.getStringWidth(value);
        float k = Math.min(wh * 0.7F / 8F, ww * 0.55F / Math.max(1, px));
        CockpitInstrumentsSVA.drawText(fr, value, x0 + ww * 0.95F - px * k, (y0 + y1) / 2, k, c);
    }

    // ---------------------------------------------------------------- ★18 the flap position, the trim, the gate

    /** The flap scale's angle (deg clockwise from up) of a deflection: 0 at -70 (upper left) .. the last setting at +70. */
    static float flapScale(float deg, HighLiftMath.Spec spec) {
        float max = spec.angles[spec.last()];
        return -70F + 140F * Math.max(0F, Math.min(1F, deg / max));
    }

    static void flapInd(FontRenderer fr, CockpitLayout.Screen s, HighLiftMath.Spec spec, float deg, boolean slats) {
        enter(s);
        float r = Math.min(s.w, s.h) / 2;
        if (CockpitInstrumentsSVA.night > 0F) {
            CockpitInstrumentsSVA.glowRing(r * 0.95F, r * 1.4F, 0.35F * CockpitInstrumentsSVA.night, -0.0005F);
        }
        col(0x1A1B1F, 1F);
        CockpitInstrumentsSVA.disc(0F, 0F, r, 0F);
        col(0x08090B, 1F);
        CockpitInstrumentsSVA.disc(0F, 0F, r * 0.9F, Z0);
        if (dark()) {
            GL11.glPopMatrix();
            return;
        }
        float max = spec.angles[spec.last()], amber = spec.angles[Math.max(0, spec.last() - 1)];
        CockpitInstrumentsSVA.glColor(tint(WHITE));
        arc(r * 0.84F, flapScale(0, spec), flapScale(amber, spec), r * 0.045F, Z1);
        CockpitInstrumentsSVA.glColor(AMB);
        arc(r * 0.84F, flapScale(amber, spec), flapScale(max, spec), r * 0.045F, Z1);
        CockpitInstrumentsSVA.glColor(tint(WHITE));
        for (int v = 0; v <= (int) max; v += 5) {
            boolean major = false;
            for (float a : spec.angles) {
                major |= Math.abs(a - v) < 0.5F;
            }
            CockpitInstrumentsSVA.ray(flapScale(v, spec), r * (major ? 0.62F : 0.7F), r * 0.79F, r * (major ? 0.04F : 0.025F), Z1);
        }
        for (float a : spec.angles) {
            double t = Math.toRadians(flapScale(a, spec));
            CockpitInstrumentsSVA.text(fr, String.valueOf(Math.round(a)), (float) Math.sin(t) * r * 0.47F, (float) Math.cos(t) * r * 0.47F, r * 0.16F,
                    r * 0.4F, tint(WHITE));
        }
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.cockpit.flaps"), 0F, -r * 0.3F, r * 0.13F, r * 1.2F, tint(WHITE));
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.cockpit.unit.deg"), 0F, -r * 0.45F, r * 0.1F, r * 0.6F, tint(0x6E7480));
        // the wing section: grey profile, the amber flap at its angle, the cyan slat when out
        float ix = -r * 0.36F, iy = -r * 0.66F, L = r * 0.5F;
        CockpitInstrumentsSVA.glColor(0xC8CCD4);
        CockpitInstrumentsSVA.line(ix, iy, ix + L * 0.72F, iy, r * 0.06F, Z1);
        double fa = Math.toRadians(deg);
        CockpitInstrumentsSVA.glColor(AMB);
        CockpitInstrumentsSVA.line(ix + L * 0.74F, iy, ix + L * 0.74F + L * 0.3F * (float) Math.cos(fa), iy - L * 0.3F * (float) Math.sin(fa), r * 0.05F, Z1);
        if (slats) {
            CockpitInstrumentsSVA.glColor(CYN);
            CockpitInstrumentsSVA.line(ix - L * 0.14F, iy - L * 0.04F, ix - L * 0.02F, iy + L * 0.06F, r * 0.05F, Z1);
        }
        CockpitInstrumentsSVA.glColor(AMB);
        CockpitInstrumentsSVA.needle(flapScale(deg, spec), r * 0.74F, r * 0.16F, r * 0.05F, Z2);
        col(0x3C3E44, 1F);
        CockpitInstrumentsSVA.disc(0F, 0F, r * 0.08F, CockpitInstrumentsSVA.LIFT_HUB - CockpitInstrumentsSVA.LIFT_FACE);
        GL11.glPopMatrix();
    }

    static void trimInd(FontRenderer fr, CockpitLayout.Screen s, float trim) {
        enter(s);
        face(s, 0.3F);
        col(0x1A1B1F, 1F);
        CockpitInstrumentsSVA.rect(-s.w / 2, -s.h / 2, s.w / 2, s.h / 2, 0F);
        float e = s.w * 0.07F;
        col(0x08090B, 1F);
        CockpitInstrumentsSVA.rect(-s.w / 2 + e, -s.h / 2 + e, s.w / 2 - e, s.h / 2 - e, Z0);
        if (dark()) {
            GL11.glPopMatrix();
            return;
        }
        float y0 = s.h / 2 - s.h * 0.17F, y1 = -s.h / 2 + s.h * 0.17F, xs = -s.w * 0.08F, half = (y0 - y1) / 2, mid = (y0 + y1) / 2;
        float m = AnimMath.TRIM_MAX;
        CockpitInstrumentsSVA.glColor(GRN);                         // the take-off band: 0 .. 2 nose up
        CockpitInstrumentsSVA.rect(xs - s.w * 0.05F, mid - 2 / m * half, xs + s.w * 0.05F, mid, Z1);
        CockpitInstrumentsSVA.glColor(tint(WHITE));
        CockpitInstrumentsSVA.line(xs, y0, xs, y1, s.w * 0.025F, Z1);
        for (int v = -5; v <= 5; v++) {
            float y = mid - v / m * half, len = s.w * (v % 5 == 0 ? 0.3F : 0.16F);
            CockpitInstrumentsSVA.line(xs - len, y, xs, y, s.h * 0.008F, Z1);
        }
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.cockpit.trim.down"), 0F, s.h / 2 - s.h * 0.085F, s.h * 0.06F, s.w * 0.86F, tint(WHITE));
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.cockpit.trim.up"), 0F, -s.h / 2 + s.h * 0.085F, s.h * 0.06F, s.w * 0.86F, tint(WHITE));
        CockpitInstrumentsSVA.text(fr, Lang.tr("sva.cockpit.trim.to"), xs + s.w * 0.3F, mid - 1 / m * half, s.h * 0.045F, s.w * 0.4F, GRN);
        float y = mid - Math.max(-m, Math.min(m, trim)) / m * half;
        CockpitInstrumentsSVA.glColor(AMB);
        tri(xs + s.w * 0.04F, y, xs + s.w * 0.36F, y + s.h * 0.04F, xs + s.w * 0.36F, y - s.h * 0.04F, Z2);
        GL11.glPopMatrix();
    }

    /** A setting's mark on the gate plate: along its "up" (forward) from the centre - 0 forward, the last setting aft. */
    static float gateMark(CockpitLayout.Screen s, int setting, int last) {
        return s.h * (0.36F - 0.72F * setting / Math.max(1, last));
    }

    static void gate(FontRenderer fr, CockpitLayout.Screen s, HighLiftMath.Spec spec, int setting) {
        enter(s);
        col(0x22232A, 1F);
        CockpitInstrumentsSVA.rect(-s.w / 2, -s.h / 2, s.w / 2, s.h / 2, 0F);
        if (dark()) {
            GL11.glPopMatrix();
            return;
        }
        // the plate lies beside the lever, tilted to the pilot, "up" = forward: the settings 0 (forward) .. the last (aft) as
        // the lever moves, the slot they're cut along, the current setting amber
        int last = spec.last();
        col(0x060608, 1F);
        CockpitInstrumentsSVA.rect(s.w * 0.28F, gateMark(s, last, last) - s.w * 0.06F, s.w * 0.42F, gateMark(s, 0, last) + s.w * 0.06F, Z0);
        for (int i = 0; i <= last; i++) {
            float y = gateMark(s, i, last);
            int c = i == setting ? AMB : tint(WHITE);
            CockpitInstrumentsSVA.glColor(c);
            CockpitInstrumentsSVA.line(s.w * 0.05F, y, s.w * 0.45F, y, s.w * 0.06F, Z1);
            CockpitInstrumentsSVA.text(fr, String.valueOf(Math.round(spec.angles[i])), -s.w * 0.2F, y, s.w * 0.34F, s.w * 0.4F, c);
        }
        GL11.glPopMatrix();
    }

    // ---------------------------------------------------------------- the jet / tiltrotor: lines for the text screen

    /** The autopilot line for a text screen («АП КУРС 268 ВЫС 0120 ...»), null when the autopilot is off. */
    static String apLine(AutopilotMath.Panel ap) {
        if (!ap.on) {
            return null;
        }
        StringBuilder b = new StringBuilder(Lang.tr("sva.ap.group.ap"));
        for (int g = AutopilotMath.G_HDG; g <= AutopilotMath.G_ROUTE; g++) {
            if (ap.mode(AutopilotMath.bit(g))) {
                b.append(' ').append(Lang.tr("sva.ap.group." + AutopilotMath.GROUP_KEYS[g]));
                if (g == AutopilotMath.G_ROUTE) {
                    b.append(' ').append(GuiAutopilotSVA.value(g, ap));
                } else if (g != AutopilotMath.G_CAP) {
                    b.append(' ').append(GuiAutopilotSVA.value(g, ap));
                } else if (ap.runway) {
                    b.append(' ').append(GuiAutopilotSVA.phaseText(ap));
                }
            }
        }
        return b.toString();
    }

    /** Is the HSI wanted on the jet's side screen (vehicles.avionics)? */
    static boolean jetHsi(VehicleType t) {
        return ConfigSVA.avionics && t == VehicleType.JET;
    }

    /** The heading in compass degrees now (interpolated). */
    static double heading(EntityVehicleSVA v, float pt) {
        float yaw = v.prevRotationYaw + MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
        return CruiseMath.heading(yaw);
    }
}
