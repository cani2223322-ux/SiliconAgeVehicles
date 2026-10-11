package com.sva.client;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AutopilotMath;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HoverMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/**
 * Wave 1, ★4 on the driver's client: the «Круиз» function (VehicleFn.CRUISE: key O by default, set in the «Техника»
 * menu - VehicleKeyBindsSVA fires toggleFor), the state (on,
 * the set speed / heading / height), the wheel's set-speed steps, and the HUD panel (mock-up veh_more_2_camera_cruise.png).
 * <p>
 * The state lives here; every tick ClientProxySVA.driverInput passes the keys through apply(): it switches the state off
 * by the same rule the physics uses (EntityVehicleSVA.cruiseBlocked - S / Space for cruise, any W/S/A/D, low charge, a
 * stall or the ground for the autopilot), writes the targets into the vehicle and adds IN_CRUISE - the Input packet
 * carries flag and targets to the server, which judges them again. W while cruising accelerates; letting go raises
 * the set speed to what it reached.
 * <p>
 * Aircraft stage C (★17): the autopilot's modes and set values (AutopilotMath) live here too - the «Панель автопилота»
 * screen (GuiAutopilotSVA) and the glare-shield panel's digits read them; they go to the server with the targets. The O
 * key engages КУРС + ВЫС at the current heading / height (as before); the panel's «АП» engages the modes chosen on it.
 * The mouse wheel while the autopilot holds turns the last knob touched on the panel (КУРС at first).
 */
public final class CruiseClientSVA {

    /** How long the «switched off» line stays on the HUD (ms). */
    private static final long OFF_SHOWN = 3000;

    private static int vehicleId = -1, kind;
    private static boolean on, wHeld;
    private static double setSpeed, yaw, alt;
    /** Stage C: the autopilot's modes, set vertical speed (blocks/s), the knob the wheel turns (AutopilotMath.G_*), set for which vehicle. */
    private static int modes = AutopilotMath.DEFAULT, knob = AutopilotMath.G_HDG, presetFor = -1;
    private static double vs;
    private static int offReason;
    private static long offTime;

    private CruiseClientSVA() {
    }

    public static boolean isOn() {
        return on;
    }

    public static String keyName() {
        return VehicleKeyBindsSVA.name(com.sva.vehicle.VehicleFn.CRUISE);
    }

    /** The vehicle the local player drives (the driver's seat), or null. */
    static EntityVehicleSVA driven(Minecraft mc) {
        if (mc.thePlayer == null || !(mc.thePlayer.ridingEntity instanceof EntityVehicleSVA)) {
            return null;
        }
        EntityVehicleSVA v = (EntityVehicleSVA) mc.thePlayer.ridingEntity;
        return v.isDead ? null : v;
    }

    /** Client tick: off when the driver left. */
    public static void tick(Minecraft mc) {
        EntityVehicleSVA v = driven(mc);
        if (on && (v == null || v.getEntityId() != vehicleId)) {
            on = false;                                        // got out: nothing to tell
        }
    }

    /** The «Круиз» key or the menu's button (the driver of v): on / off, or why it can't. */
    public static void toggleFor(EntityVehicleSVA v) {
        if (on) {
            off(0);
            return;
        }
        int k = v.cruiseKind();
        if (k == CruiseMath.NONE) {
            off(CruiseMath.OFF_MODE);
            return;
        }
        int why = v.cruiseBlocked(0);
        if (why != 0) {
            kind = k;
            off(why);
            return;
        }
        engage(v, k, true);
    }

    /** Engaged on v: `fresh` - the O key: КУРС + ВЫС at the current heading / height / speed; else the panel's set values. */
    private static void engage(EntityVehicleSVA v, int k, boolean fresh) {
        VehicleType t = v.getVehicleType();
        kind = k;
        vehicleId = v.getEntityId();
        if (fresh || presetFor != vehicleId) {
            preset(v);
            if (fresh) {
                modes = AutopilotMath.DEFAULT;
            }
        }
        if (k == CruiseMath.CRUISE) {
            setSpeed = CruiseMath.engageSpeed(Math.max(0, v.speed), top(v, t));
        }
        wHeld = false;
        on = true;
        offReason = 0;
    }

    /** Stage C: the set values from the aircraft now (heading, height, speed; vertical speed 0). */
    private static void preset(EntityVehicleSVA v) {
        VehicleType t = v.getVehicleType();
        presetFor = v.getEntityId();
        setSpeed = CruiseMath.engageSpeed(Math.max(0, v.speed), top(v, t));
        yaw = v.rotationYaw;
        alt = Math.min(v.posY, FlightMath.CEILING - 2);
        vs = 0;
    }

    // ------------------------------------------------------------------ stage C: the autopilot panel

    /** The panel screen opened for v: the set values start from the aircraft's own the first time. */
    public static void prepare(EntityVehicleSVA v) {
        if (presetFor != v.getEntityId() && !(on && vehicleId == v.getEntityId())) {
            preset(v);
            modes = AutopilotMath.DEFAULT;
        }
    }

    /** The panel's «АП» button: engage with the chosen modes / let go. */
    public static void apButton(EntityVehicleSVA v) {
        if (on) {
            off(0);
            return;
        }
        int k = v.cruiseKind();
        if (k != CruiseMath.AUTOPILOT) {
            kind = CruiseMath.AUTOPILOT;
            off(CruiseMath.OFF_MODE);
            return;
        }
        int why = v.cruiseBlocked(0);
        if (why != 0) {
            kind = k;
            off(why);
            return;
        }
        engage(v, k, false);
    }

    /** A mode button (AutopilotMath.G_HDG..G_CAP): on / off; its knob becomes the wheel's. */
    public static void modeButton(EntityVehicleSVA v, int group) {
        prepare(v);
        modes ^= AutopilotMath.bit(group);
        if (group != AutopilotMath.G_CAP) {
            knob = group;
        }
    }

    /** A knob's - / + (steps notches) on the panel or the wheel. */
    public static void adjust(EntityVehicleSVA v, int group, int steps) {
        prepare(v);
        VehicleType t = v.getVehicleType();
        switch (group) {
            case AutopilotMath.G_HDG:
                yaw = AutopilotMath.adjustHeading(CruiseMath.heading(yaw), steps) - 180;
                break;
            case AutopilotMath.G_ALT:
                alt = AutopilotMath.adjustAlt((int) Math.round(alt), steps, (int) FlightMath.CEILING - 2);
                break;
            case AutopilotMath.G_SPD: {
                int top = VehicleMath.kmh(t.topSpeed);         // СКОР holds what the throttle reaches (never the afterburner)
                setSpeed = CruiseMath.fromKmh(AutopilotMath.adjustKmh(VehicleMath.kmh(setSpeed), steps, top));
                break;
            }
            case AutopilotMath.G_VS:
                vs = AutopilotMath.adjustVs(vs, steps);
                break;
            default:
                return;
        }
        knob = group;
    }

    /** The knob the mouse wheel turns now (AutopilotMath.G_*). */
    public static int knob() {
        return knob;
    }

    /** The panel as the driver's client knows it (on, modes, set values; ЗАХВ's runway / aligned from the vehicle). */
    public static AutopilotMath.Panel panel(EntityVehicleSVA v) {
        boolean mine = presetFor == v.getEntityId() || on && vehicleId == v.getEntityId();
        if (!mine) {
            return v.apPanel();
        }
        AutopilotMath.Panel p = new AutopilotMath.Panel();
        p.on = on && vehicleId == v.getEntityId() && kind == CruiseMath.AUTOPILOT;
        p.modes = modes;
        p.hdg = CruiseMath.heading(yaw);
        p.alt = (int) Math.round(alt);
        p.kmh = VehicleMath.kmh(setSpeed);
        p.vs = vs;
        p.runway = (modes & AutopilotMath.M_CAP) != 0 && v.runway() != null;
        p.aligned = p.on && v.apPanel().aligned;
        return p;
    }

    private static double top(EntityVehicleSVA v, VehicleType t) {
        if (t.movement == Movement.HOVER) {
            return HoverMath.maxSpeed(t, true, false) * v.tuneSpeed();
        }
        return VehicleMath.maxSpeed(t, v.getEnergy()) * v.roadSpeedMul(t) * v.tuneSpeed();
    }

    private static void off(int reason) {
        on = false;
        offReason = reason;
        offTime = System.currentTimeMillis();
    }

    /** From driverInput: the keys -> the keys + IN_CRUISE while engaged (targets written into the vehicle). */
    public static int apply(EntityVehicleSVA v, int in) {
        if (!on || v.getEntityId() != vehicleId) {
            return in;
        }
        if (v.cruiseKind() != kind) {
            off(CruiseMath.OFF_MODE);                          // the tiltrotor left airplane mode, a config changed
            return in;
        }
        if (kind == CruiseMath.CRUISE) {
            boolean fwd = (in & EntityVehicleSVA.IN_FWD) != 0;
            if (wHeld && !fwd) {
                setSpeed = Math.max(setSpeed, CruiseMath.engageSpeed(v.speed, top(v, v.getVehicleType())));   // «resume / accel»
            }
            wHeld = fwd;
        }
        v.apModes = kind == CruiseMath.AUTOPILOT ? modes : AutopilotMath.DEFAULT;     // stage C (ЗАХВ's landing check)
        v.apVs = vs / 20;
        int why = v.cruiseBlocked(in | EntityVehicleSVA.IN_CRUISE);
        if (why != 0) {
            off(why);
            return in;
        }
        v.cruiseSpeed = setSpeed;
        v.apYaw = yaw;
        v.apAlt = alt;
        return in | EntityVehicleSVA.IN_CRUISE;
    }

    /** The mouse wheel while cruising: the set speed +-STEP_KMH a notch. @return true if used (the hotbar keeps still) */
    public static boolean wheel(Minecraft mc, int notches) {
        EntityVehicleSVA v = driven(mc);
        if (on && kind == CruiseMath.AUTOPILOT && v != null && notches != 0 && v.getEntityId() == vehicleId) {
            adjust(v, knob, notches);                           // stage C: the panel's last knob
            return true;
        }
        if (!on || kind != CruiseMath.CRUISE || v == null || notches == 0) {
            return false;
        }
        setSpeed = CruiseMath.adjust(setSpeed, notches, top(v, v.getVehicleType()));
        return true;
    }

    // ------------------------------------------------------------------ HUD

    private static final int BG = 0xD0161A22, EDGE = 0xFF3A4150, TEXT = 0xE0E0E0, DIM = 0x9A9AA6, AMBER = 0xFFD24D, WARN = 0xFF5A4A,
            BOX_ON = 0xFF2E9E52, BOX_OFF = 0xFF2A2F3A;

    /**
     * The panel right, above the bottom-right panel (bottom = its top): «КРУИЗ 54 км/ч» + the hints, or «АВТОПИЛОТ» with
     * КУРС / ВЫСОТА (held, green) and СКОР. (not held), or for a few seconds why it switched off.
     */
    public static void drawHud(Minecraft mc, FontRenderer fr, int sw, int bottom, EntityVehicleSVA v) {
        long now = System.currentTimeMillis();
        boolean showOff = !on && offTime > 0 && now - offTime < OFF_SHOWN;
        if (!on && !showOff || v == null) {
            return;
        }
        int pw = 170;
        if (showOff) {
            String title = Lang.tr(kind == CruiseMath.AUTOPILOT ? "sva.hud.cruise.apOff" : "sva.hud.cruise.off");
            String why = offReason == 0 ? "" : Lang.tr("sva.hud.cruise.why." + offReason);
            int ph = why.length() > 0 ? 30 : 18;
            int px = sw - pw - 4, py = bottom - ph - 6;
            frame(px, py, pw, ph);
            fit(fr, title, px + 6, py + 5, pw - 12, offReason == CruiseMath.OFF_CHARGE || offReason == CruiseMath.OFF_STALL ? WARN : AMBER, true);
            if (why.length() > 0) {
                fit(fr, why, px + 6, py + 17, pw - 12, DIM, false);
            }
            return;
        }
        if (kind == CruiseMath.CRUISE) {
            int ph = 62, px = sw - pw - 4, py = bottom - ph - 6;
            frame(px, py, pw, ph);
            fit(fr, Lang.tr("sva.hud.cruise.title"), px + 6, py + 5, pw - 12, AMBER, true);
            String sp = String.valueOf(VehicleMath.kmh(setSpeed));
            org.lwjgl.opengl.GL11.glPushMatrix();
            org.lwjgl.opengl.GL11.glTranslatef(px + 6, py + 16, 0);
            org.lwjgl.opengl.GL11.glScalef(2F, 2F, 1F);
            fr.drawStringWithShadow(sp, 0, 0, 0xFFFFFF);
            org.lwjgl.opengl.GL11.glPopMatrix();
            int ux = px + 10 + fr.getStringWidth(sp) * 2;
            fit(fr, Lang.tr("sva.hud.kmh"), ux, py + 22, Math.max(0, px + pw - 6 - ux), DIM, false);
            fit(fr, Lang.tr("sva.hud.cruise.hint1", keyName()), px + 6, py + 36, pw - 12, DIM, false);
            fit(fr, Lang.tr("sva.hud.cruise.hint2"), px + 6, py + 47, pw - 12, DIM, false);
            return;
        }
        // stage C: the five modes in boxes (lit = held) with their set values, the wheel's knob marked
        int ph = 58, px = sw - pw - 4, py = bottom - ph - 6;
        frame(px, py, pw, ph);
        fit(fr, Lang.tr("sva.hud.ap.title"), px + 6, py + 5, pw - 12, AMBER, true);
        String[] labels = {Lang.tr("sva.hud.ap.heading"), Lang.tr("sva.hud.ap.alt"), Lang.tr("sva.hud.ap.speed"), Lang.tr("sva.hud.ap.vs"),
                Lang.tr("sva.hud.ap.cap")};
        int[] bits = {AutopilotMath.M_HDG, AutopilotMath.M_ALT, AutopilotMath.M_SPD, AutopilotMath.M_VS, AutopilotMath.M_CAP};
        int[] groups = {AutopilotMath.G_HDG, AutopilotMath.G_ALT, AutopilotMath.G_SPD, AutopilotMath.G_VS, AutopilotMath.G_CAP};
        AutopilotMath.Panel ap = panel(v);
        String[] values = {String.format("%03d", Integer.valueOf(ap.hdg)), String.valueOf(ap.alt), String.valueOf(ap.kmh), vsText(ap.vs),
                ap.runway ? Lang.tr("sva.ap.rwy") : "--"};
        int bw = 30;
        for (int i = 0; i < 5; i++) {
            boolean held = (modes & bits[i]) != 0;
            int bx = px + 6 + i * (bw + 2);
            Gui.drawRect(bx, py + 16, bx + bw, py + 26, held ? BOX_ON : BOX_OFF);
            if (groups[i] == knob && i < 4) {
                Gui.drawRect(bx, py + 26, bx + bw, py + 27, 0xFFFFD24D);
            }
            fit(fr, labels[i], bx + Math.max(1, (bw - fr.getStringWidth(labels[i])) / 2), py + 17, bw - 2, held ? 0x0E1A12 : DIM, false);
            fit(fr, values[i], bx + Math.max(0, (bw - fr.getStringWidth(values[i])) / 2), py + 30, bw, held ? 0xFFFFFF : DIM, true);
        }
        fit(fr, Lang.tr("sva.hud.ap.hint2", Lang.tr("sva.ap.group." + AutopilotMath.GROUP_KEYS[knob])), px + 6, py + 44, pw - 12, DIM, false);
    }

    /** A vertical speed for the panel: one decimal with a comma and its sign («-1,5», «+0,0»). */
    public static String vsText(double vs) {
        long t = Math.round(Math.abs(vs) * 10);
        return (vs < -0.05 ? "-" : "+") + t / 10 + "," + t % 10;
    }

    /** A panel line in at most maxW px: scaled down to 3/4, then cut with "..." (no tooltips in the HUD - as HudVehicleSVA.fit). */
    private static void fit(FontRenderer fr, String s, int x, int y, int maxW, int color, boolean shadow) {
        int w = fr.getStringWidth(s);
        if (w <= maxW) {
            fr.drawString(s, x, y, color, shadow);
            return;
        }
        float k = Math.max(0.75F, maxW / (float) w);
        if (w * 3 > maxW * 4) {                                     // too wide even at 3/4 (int test: no float round-off cut)
            s = fr.trimStringToWidth(s, Math.max(0, (int) (maxW / k) - fr.getStringWidth("..."))) + "...";
        }
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(x, y + (1 - k) * fr.FONT_HEIGHT / 2F, 0);
        org.lwjgl.opengl.GL11.glScalef(k, k, 1F);
        fr.drawString(s, 0, 0, color, shadow);
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    private static void frame(int px, int py, int pw, int ph) {
        Gui.drawRect(px - 1, py - 1, px + pw + 1, py + ph + 1, EDGE);
        Gui.drawRect(px, py, px + pw, py + ph, BG);
    }
}
