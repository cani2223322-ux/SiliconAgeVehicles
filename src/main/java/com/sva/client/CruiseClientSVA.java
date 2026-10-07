package com.sva.client;

import com.sc.manual.Lang;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CruiseMath;
import com.sva.vehicle.FlightMath;
import com.sva.vehicle.HoverMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.VehicleMath;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.settings.KeyBinding;

/**
 * Wave 1, ★4 on the driver's client: the «Круиз» key (default O - K is the core armour's; rebindable), the state (on,
 * the set speed / heading / height), the wheel's set-speed steps, and the HUD panel (mock-up veh_more_2_camera_cruise.png).
 * <p>
 * The state lives here; every tick ClientProxySVA.driverInput passes the keys through apply(): it switches the state off
 * by the same rule the physics uses (EntityVehicleSVA.cruiseBlocked - S / Space for cruise, any W/S/A/D, low charge, a
 * stall or the ground for the autopilot), writes the targets into the vehicle and adds IN_CRUISE - the Input packet
 * carries flag and targets to the server, which judges them again. W while cruising accelerates; letting go raises
 * the set speed to what it reached.
 */
public final class CruiseClientSVA {

    /** «Круиз / автопилот». Default O: K is the core's armour screen; O is free in vanilla 1.7.10, the core and this addon. */
    public static final KeyBinding KEY_CRUISE = new KeyBinding("key.sva.cruise", org.lwjgl.input.Keyboard.KEY_O, "key.categories.sva");

    /** How long the «switched off» line stays on the HUD (ms). */
    private static final long OFF_SHOWN = 3000;

    private static int vehicleId = -1, kind;
    private static boolean on, wHeld;
    private static double setSpeed, yaw, alt;
    private static int offReason;
    private static long offTime;

    private CruiseClientSVA() {
    }

    public static boolean isOn() {
        return on;
    }

    public static String keyName() {
        int c = KEY_CRUISE.getKeyCode();
        return c == 0 ? "-" : net.minecraft.client.settings.GameSettings.getKeyDisplayString(c);
    }

    /** The vehicle the local player drives (the driver's seat), or null. */
    static EntityVehicleSVA driven(Minecraft mc) {
        if (mc.thePlayer == null || !(mc.thePlayer.ridingEntity instanceof EntityVehicleSVA)) {
            return null;
        }
        EntityVehicleSVA v = (EntityVehicleSVA) mc.thePlayer.ridingEntity;
        return v.isDead ? null : v;
    }

    /** Client tick: the key, and off when the driver left. */
    public static void tick(Minecraft mc) {
        EntityVehicleSVA v = driven(mc);
        if (on && (v == null || v.getEntityId() != vehicleId)) {
            on = false;                                        // got out: nothing to tell
        }
        if (KEY_CRUISE.isPressed() && mc.currentScreen == null && v != null) {
            toggle(v);
        }
    }

    private static void toggle(EntityVehicleSVA v) {
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
        VehicleType t = v.getVehicleType();
        kind = k;
        vehicleId = v.getEntityId();
        setSpeed = CruiseMath.engageSpeed(Math.max(0, v.speed), top(v, t));
        yaw = v.rotationYaw;
        alt = Math.min(v.posY, FlightMath.CEILING - 2);
        wHeld = false;
        on = true;
        offReason = 0;
    }

    private static double top(EntityVehicleSVA v, VehicleType t) {
        if (t.movement == Movement.HOVER) {
            return HoverMath.maxSpeed(t, true, false);
        }
        return VehicleMath.maxSpeed(t, v.getEnergy()) * v.roadSpeedMul(t);
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
            fr.drawStringWithShadow(title, px + 6, py + 5, offReason == CruiseMath.OFF_CHARGE || offReason == CruiseMath.OFF_STALL ? WARN : AMBER);
            if (why.length() > 0) {
                fr.drawString(why, px + 6, py + 17, DIM);
            }
            return;
        }
        if (kind == CruiseMath.CRUISE) {
            int ph = 62, px = sw - pw - 4, py = bottom - ph - 6;
            frame(px, py, pw, ph);
            fr.drawStringWithShadow(Lang.tr("sva.hud.cruise.title"), px + 6, py + 5, AMBER);
            String sp = String.valueOf(VehicleMath.kmh(setSpeed));
            org.lwjgl.opengl.GL11.glPushMatrix();
            org.lwjgl.opengl.GL11.glTranslatef(px + 6, py + 16, 0);
            org.lwjgl.opengl.GL11.glScalef(2F, 2F, 1F);
            fr.drawStringWithShadow(sp, 0, 0, 0xFFFFFF);
            org.lwjgl.opengl.GL11.glPopMatrix();
            fr.drawString(Lang.tr("sva.hud.kmh"), px + 10 + fr.getStringWidth(sp) * 2, py + 22, DIM);
            fr.drawString(Lang.tr("sva.hud.cruise.hint1", keyName()), px + 6, py + 36, DIM);
            fr.drawString(Lang.tr("sva.hud.cruise.hint2"), px + 6, py + 47, DIM);
            return;
        }
        int ph = 58, px = sw - pw - 4, py = bottom - ph - 6;
        frame(px, py, pw, ph);
        fr.drawStringWithShadow(Lang.tr("sva.hud.ap.title"), px + 6, py + 5, AMBER);
        String[] labels = {Lang.tr("sva.hud.ap.heading"), Lang.tr("sva.hud.ap.alt"), Lang.tr("sva.hud.ap.speed")};
        double dx = v.posX - v.prevPosX, dz = v.posZ - v.prevPosZ;
        String[] values = {String.format("%03d", Integer.valueOf(CruiseMath.heading(yaw))), String.valueOf((int) Math.round(alt)),
                String.valueOf(VehicleMath.kmh(Math.sqrt(dx * dx + dz * dz)))};
        int bw = 50;
        for (int i = 0; i < 3; i++) {
            int bx = px + 6 + i * (bw + 3);
            Gui.drawRect(bx, py + 16, bx + bw, py + 26, i < 2 ? BOX_ON : BOX_OFF);
            fr.drawString(labels[i], bx + (bw - fr.getStringWidth(labels[i])) / 2, py + 17, i < 2 ? 0x0E1A12 : DIM);
            fr.drawStringWithShadow(values[i], bx + (bw - fr.getStringWidth(values[i])) / 2, py + 30, i < 2 ? 0xFFFFFF : DIM);
        }
        fr.drawString(Lang.tr("sva.hud.ap.hint"), px + 6, py + 44, DIM);
    }

    private static void frame(int px, int py, int pw, int ph) {
        Gui.drawRect(px - 1, py - 1, px + pw + 1, py + ph + 1, EDGE);
        Gui.drawRect(px, py, px + pw, py + ph, BG);
    }
}
