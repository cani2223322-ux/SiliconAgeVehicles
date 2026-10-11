package com.sva.client;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.sc.manual.Lang;
import com.sc.util.ArmorGasSC.Gas;
import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.CockpitDials;
import com.sva.vehicle.CockpitLamps;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.SkinMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

/**
 * ★14 live cockpit instruments (mock-up veh_design_6_gauges.png): over the model's emissive dial / screen / lamp pieces
 * (found by vehicle/CockpitLayout, what each shows: vehicle/CockpitDials) the riders of a vehicle see real readings -
 * dials get a dark face, tick marks, scale numbers and a needle that follows its value smoothly; screens get text lines
 * with small bars (charge, hull, gases, mode, landing gear), one big reading, a vertical bar or - aircraft - an attitude
 * indicator; the warning lamps stay dim and blink red on a stall / autorotation / overheating / an empty or low buffer.
 * Cockpit stage 2 (★11, vehicle/CockpitLamps): every lamp has a role, colour and a tiny label (turn signals, headlights,
 * handbrake, overheating, wear; the heli's annunciators; the drill's mode lamps; the plane's gear lamps), the main screen
 * shows the events (afterburner, «ДАЙ ОСТЫТЬ», autopilot, a full hold, the range blinking on low charge), the plane's
 * attitude screen a stall flag, the drill's console the «ДОБЫЧА» page; a quiet beep / siren for the riders (config
 * vehicles.cockpitSounds); boarding as the driver runs the startup sequence (vehicles.cockpitStartup). ★8: at night the
 * dial marks, texts and screen edges take the tier's colour with a soft glow round dials, screens and lit lamps, and a
 * light strip runs along the floor (vehicles.nightLighting, vehicles.cabinLightLevel).
 * Drawn right after the model in the same matrix (model space; RenderVehicleSVA), a hair in front of each piece
 * (glPolygonOffset + a small lift along its normal), full bright, no lighting. Only for the vehicle the local player
 * rides (driver or passenger), never for a wreck. Config vehicles.liveInstruments.
 */
@SideOnly(Side.CLIENT)
public final class CockpitInstrumentsSVA {

    /** Per vehicle: the needles' shown values and smoothed readings (client side only). */
    private static final WeakHashMap<EntityVehicleSVA, State> STATES = new WeakHashMap<EntityVehicleSVA, State>();
    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

    /** Lifts over a piece's front surface (model units): face, marks / text, needle, hub. */
    static final float LIFT_FACE = 0.0015F, LIFT_MARK = 0.0025F, LIFT_NEEDLE = 0.0035F, LIFT_HUB = 0.0045F;

    private static final class State {
        float[] shown = new float[0];
        float speed, vy, pitch, roll, turn;
        long lastNs;
        int gearTick = Integer.MIN_VALUE;
        boolean gearDown = true, air;
        int airTick = Integer.MIN_VALUE;
        /** Stage 2: the startup sequence's start (nanoTime, 0 = none), the dug count when the local player got in. */
        long startNs;
        int dugAtStart;
        /** Stage 2: the next overheating beep / siren note (ms) and the siren's step. */
        long nextBeep, nextSiren;
        int sirenStep;
    }

    /** Stage 2, this frame: the night glow 0..1 and the tier's colour (★8); the startup phase (★11). */
    static float night;
    static int glowRgb;
    static int phase = CockpitLamps.PH_NORMAL;
    static final CockpitLamps.Snapshot SNAP = new CockpitLamps.Snapshot();
    /** Stage 2, clientTick: the vehicle the local player rode / drove last tick and whether it had power. */
    private static EntityVehicleSVA lastRidden, lastDriven;
    private static boolean lastPowered;

    private CockpitInstrumentsSVA() {
    }

    private static State state(EntityVehicleSVA v) {
        State s = STATES.get(v);
        if (s == null) {
            s = new State();
            STATES.put(v, s);
        }
        return s;
    }

    /**
     * Stage 2, every client tick (ClientProxySVA): getting into a vehicle notes its dug count (the drill's «за рейс»);
     * taking the wheel of a powered vehicle - or its power coming back while at the wheel - starts the startup sequence
     * (config vehicles.cockpitStartup). Other players' vehicles are never touched.
     */
    public static void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityVehicleSVA rid = mc.thePlayer == null ? null : HudVehicleSVA.vehicleOf(mc.thePlayer);
        EntityVehicleSVA drv = rid != null && rid.riddenByEntity == mc.thePlayer ? rid : null;
        boolean pw = drv != null && drv.powered();
        if (rid != null && rid != lastRidden) {
            state(rid).dugAtStart = rid.getDug();
        }
        if (ConfigSVA.cockpitStartup && ConfigSVA.liveInstruments && pw && (drv != lastDriven || !lastPowered)) {
            state(drv).startNs = System.nanoTime();
        }
        lastRidden = rid;
        lastDriven = drv;
        lastPowered = pw;
    }

    /**
     * Call right after ObjModelSVA.render in RenderVehicleSVA.doRender (same matrix: model space, scaled, the aircraft
     * attitude applied). Leaves the GL state as it found it.
     */
    public static void render(EntityVehicleSVA v, ObjModelSVA m, float pt) {
        if (!ConfigSVA.liveInstruments || m == null || v.isWrecked()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || HudVehicleSVA.vehicleOf(mc.thePlayer) != v) {
            return;
        }
        VehicleType t = v.getVehicleType();
        CockpitLayout l = CockpitLayout.of(t);
        if (l.dials.isEmpty() && l.screens.isEmpty() && l.lamps.isEmpty() && l.panels.isEmpty()) {
            return;
        }
        State s = state(v);
        long now = System.nanoTime();
        float dt = s.lastNs == 0 ? -1F : (now - s.lastNs) / 1e9F;
        s.lastNs = now;
        // ★11 startup (started by clientTick when the local player takes the wheel or the power comes back)
        boolean powered = v.powered();
        float sec = s.startNs == 0 ? -1F : (now - s.startNs) / 1e9F;
        phase = powered ? CockpitLamps.phase(sec) : CockpitLamps.PH_NORMAL;
        if (phase == CockpitLamps.PH_NORMAL) {
            s.startNs = 0;
        }
        // ★8 night glow
        float dark = VehicleLightsSVA.darkness(v);
        night = ConfigSVA.nightLighting ? CockpitLamps.night(dark, powered, ConfigSVA.cabinLightLevel) : 0F;
        glowRgb = CockpitLamps.tierGlow(SkinMath.livery(t.tier));
        double dx = v.posX - v.prevPosX, dz = v.posZ - v.prevPosZ;
        s.speed = CockpitDials.follow(dt < 0 ? Float.NaN : s.speed, (float) Math.sqrt(dx * dx + dz * dz), dt);
        s.vy = CockpitDials.follow(dt < 0 ? Float.NaN : s.vy, (float) (v.posY - v.prevPosY), dt);
        s.turn = CockpitDials.follow(dt < 0 ? Float.NaN : s.turn, MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw), dt);
        s.pitch = v.prevRenderPitch + (v.renderPitch - v.prevRenderPitch) * pt;
        s.roll = v.prevRenderRoll + (v.renderRoll - v.prevRenderRoll) * pt;
        if (t.movement == Movement.PLANE || t.movement == Movement.TILT) {
            if (s.gearTick != v.ticksExisted) {                 // the gear: down on the ground and in the last 5 blocks
                s.gearTick = v.ticksExisted;
                s.gearDown = !v.airborneAt(v.posX, v.posY, v.posZ) || v.heightAboveGround(5) < 5;
            }
        }
        snapshot(v, t, s, pt);
        sounds(mc, v, t, s);

        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_POLYGON_BIT | GL11.GL_TEXTURE_BIT);
        try {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(-1F, -4F);
            FontRenderer fr = mc.fontRenderer;
            int[] table = CockpitDials.table(t);
            if (s.shown.length != l.dials.size()) {
                s.shown = new float[l.dials.size()];
                java.util.Arrays.fill(s.shown, Float.NaN);
            }
            float sweep = CockpitLamps.sweep(sec);
            int dead = deadDial(v, t, l, table);
            for (int i = 0; i < l.dials.size(); i++) {
                int kind = table[i % table.length];
                float min = CockpitDials.min(kind, t), max = CockpitDials.max(kind, t, v.cargoCapacity());
                if (i == dead && !Float.isNaN(s.shown[i])) {
                    // round 2, D2 (★7): a dead instrument (or the iced pitot's airspeed) - the needle stays where it stopped
                } else if (phase == CockpitLamps.PH_NORMAL) {
                    s.shown[i] = CockpitDials.follow(s.shown[i], value(kind, v, t, s, pt), dt);
                } else {
                    s.shown[i] = min + (max - min) * sweep;          // ★11: at rest, then 0 -> max -> 0
                }
                drawDial(fr, l.dials.get(i), kind, s.shown[i], min, max);
                if (i == dead && phase != CockpitLamps.PH_DARK) {
                    failFlag(fr, l.dials.get(i));
                }
            }
            int[] roles = CockpitDials.screenRoles(l.screens, t.movement.flies, t.hasDrill, AvionicsSVA.jetHsi(t));
            int[] small = CockpitDials.smallTable(t);
            int smallN = 0;
            for (int i = 0; i < l.screens.size(); i++) {
                CockpitLayout.Screen sc = l.screens.get(i);
                if (phase != CockpitLamps.PH_NORMAL && phase != CockpitLamps.PH_SWEEP) {
                    drawStartScreen(fr, sc);
                    continue;
                }
                switch (roles[i]) {
                    case CockpitDials.SCREEN_ATTITUDE:
                        drawAttitude(fr, sc, s.pitch, s.roll, v.isStalling() && phase == CockpitLamps.PH_NORMAL);
                        break;
                    case CockpitDials.SCREEN_MAIN:
                        drawMain(fr, sc, mainLines(v, t, s));
                        break;
                    case CockpitDials.SCREEN_MINING:
                        drawMain(fr, sc, miningLines(v, s));
                        break;
                    case CockpitDials.SCREEN_HSI:                       // stage C: the jet's HSI
                        AvionicsSVA.hsi(fr, sc, v, pt, apView(v), false);
                        break;
                    case CockpitDials.SCREEN_BAR: {
                        int kind = small[smallN++ % small.length];
                        drawBar(sc, frac(kind, v, t, s, pt), color(kind));
                        break;
                    }
                    default: {
                        int kind = small[smallN++ % small.length];
                        drawSmall(fr, sc, kind, v, t, s, pt);
                    }
                }
            }
            drawLamps(fr, l, t, v, pt);
            AvionicsSVA.draw(fr, l, v, t, pt, sweep);                  // stage C: the AP panel, HSI, flap / trim, the gate
            OverheadPanelSVA.draw(fr, l, v, t);                         // round 2, D2: the overhead switch panel
            if (night > 0F && l.floor != null) {
                floorStrip(l.floor);
            }
        } finally {
            GL11.glPolygonOffset(0F, 0F);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
            GL11.glPopAttrib();
            GL11.glColor4f(1F, 1F, 1F, 1F);
        }
    }

    // ---------------------------------------------------------------- readings

    /** A reading in its dial's units (km/h, Y, blocks/s, %, slots). */
    static float value(int kind, EntityVehicleSVA v, VehicleType t, State s, float pt) {
        switch (kind) {
            case CockpitDials.SPEED:
                return s.speed * 72F;                              // b/t -> km/h (VehicleMath.kmh: x 20 x 3.6)
            case CockpitDials.ALT:
            case CockpitDials.DEPTH:
                return (float) (v.prevPosY + (v.posY - v.prevPosY) * pt);
            case CockpitDials.VSPEED:
                return s.vy * 20F;
            case CockpitDials.CHARGE:
                return v.getEnergy() * 100F / Math.max(1F, (float) v.getMaxEnergy());
            case CockpitDials.RPM:
                // full = the powered spin: helicopter 42 deg/t, propellers 18 + 50 at full throttle (EntityVehicleSVA)
                return Math.abs(v.rotorRate()) * 100F / (t.movement == Movement.HELI ? 42F : 68F);
            case CockpitDials.DRILL:
                return Math.abs(MathHelper.wrapAngleTo180_float(v.drillAngle - v.prevDrillAngle)) * 100F / 32F;
            case CockpitDials.CARGO:
                return v.getCargoUsed();
            case CockpitDials.CORE:
                return gasPct(v, t, Gas.SINGULAR_MATTER);
            case CockpitDials.SHIELD:
                return t.gases.fieldGas != null ? gasPct(v, t, t.gases.fieldGas) : v.getHp() * 100F / v.getMaxHp();
            case CockpitDials.HEAT:
                return v.getHeat();
            case CockpitDials.HULL:
                return v.getHp() * 100F / v.getMaxHp();
            case CockpitDials.THROTTLE:
                return (float) v.getThrottle() * 100F;
            case CockpitDials.TURN:
                return s.turn * 20F;                               // deg/tick -> deg/s, + = turning right
            case CockpitDials.HEADING: {
                // Minecraft yaw 0 = south: the compass heading (0 north, 90 east) is yaw + 180
                float yaw = v.prevRotationYaw + MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
                return ((yaw + 180F) % 360F + 360F) % 360F;
            }
            case CockpitDials.GAS: {
                Gas g = mainGas(t);
                return g == null ? 0F : gasPct(v, t, g);
            }
            default:
                return 0F;
        }
    }

    /** 0..1 of the reading's scale (bars). */
    private static float frac(int kind, EntityVehicleSVA v, VehicleType t, State s, float pt) {
        float min = CockpitDials.min(kind, t), max = CockpitDials.max(kind, t, v.cargoCapacity());
        return MathHelper.clamp_float((value(kind, v, t, s, pt) - min) / (max - min), 0F, 1F);
    }

    private static float gasPct(EntityVehicleSVA v, VehicleType t, Gas g) {
        int cap = v.getGasCapacity(g);
        return cap <= 0 ? 0F : v.getGas(g) * 100F / cap;
    }

    /** The gas a «ГАЗ» reading shows: the lift gas, else the afterburner's, else the coolant, else the first tank. */
    private static Gas mainGas(VehicleType t) {
        if (t.gases.liftGas != null) {
            return t.gases.liftGas;
        }
        if (t.gases.boostGas != null) {
            return t.gases.boostGas;
        }
        if (t.gases.coolant != null) {
            return t.gases.coolant;
        }
        return t.gases.gases().isEmpty() ? null : t.gases.gases().get(0);
    }

    /** ★11: what the lamps / events read this frame (CockpitLamps.Snapshot). */
    private static void snapshot(EntityVehicleSVA v, VehicleType t, State s, float pt) {
        CockpitLamps.Snapshot n = SNAP;
        n.powered = v.powered();
        n.charge = v.getEnergy() / (float) Math.max(1F, (float) v.getMaxEnergy());
        n.heat = v.getHeat();
        n.heatStopped = v.isHeatStopped();
        n.hull = v.wearOn() ? v.getHp() / v.getMaxHp() : 1F;
        n.steer = AnimMath.lerp(v.anim.prevSteer, v.anim.steer, pt);
        n.speed = s.speed;
        n.vy = s.vy;
        if (s.airTick != v.ticksExisted) {                       // the ground test once a tick
            s.airTick = v.ticksExisted;
            s.air = v.airborneAt(v.posX, v.posY, v.posZ);
        }
        n.airborne = s.air;
        n.onGround = !s.air;
        n.braking = v.anim.braking();
        n.headlights = VehicleLightsSVA.headlightsOn(v);
        n.navLights = ConfigSVA.navLights;
        n.autorotating = v.isAutorotating();
        n.stalling = v.isStalling();
        n.heliMode = t.movement == Movement.HELI || t.movement == Movement.TILT && !v.planeNow();
        int cap = v.cargoCapacity();
        n.cargoFull = cap > 0 && v.getCargoUsed() >= cap;
        n.drillMode = v.getDrillMode();
        boolean held = v.isCruiseOn();
        boolean ap = held && t.movement.flies;
        n.autopilot = ap || t.autoDelivery && v.isAutoOn();       // the crawler's auto-delivery is its autopilot
        n.cruise = held && !ap;
        // stage C: the annunciators (flaps out / travelling, slats, airbrake, the overspeed warning)
        n.flaps = v.getFlaps();
        n.flapsMoving = Math.abs(v.anim.flapPos - n.flaps) > 0.02F;
        n.slatsOut = v.slatsOut();
        n.airbrake = v.spoilersUp();
        n.flapWarning = v.flapWarning();
        n.fails = v.failures();                                   // round 2, D2: the failures, the iced pitot
        n.iced = v.pitotIced();
    }

    /**
     * Round 2, D2 (★7): the dial that has failed - the failure's slot modulo the dials, or (none failed) the airspeed
     * dial while the pitot is iced; -1 when none.
     */
    private static int deadDial(EntityVehicleSVA v, VehicleType t, CockpitLayout l, int[] table) {
        if (l.dials.isEmpty() || !com.sva.vehicle.SystemsMath.applies(t)) {
            return -1;
        }
        if ((v.failures() & com.sva.vehicle.SystemsMath.F_INSTR) != 0) {
            return v.failedInstrument() % l.dials.size();
        }
        if (v.pitotIced()) {
            for (int i = 0; i < l.dials.size(); i++) {
                if (table[i % table.length] == CockpitDials.SPEED) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** D2: the red-and-white striped «ОТКАЗ» flag across a dead dial (the mock-up's), tilted like a real one. */
    private static void failFlag(FontRenderer fr, CockpitLayout.Dial d) {
        float r = d.r;
        frame(d.c, d.n, d.up, d.right, d.front + LIFT_HUB + 0.0005F);
        GL11.glRotatef(18F, 0F, 0F, 1F);
        float w = r * 0.62F, h = r * 0.2F, y0 = -r * 0.42F;
        GL11.glColor4f(0.95F, 0.95F, 0.95F, 1F);
        rect(-w, y0 - h, w, y0 + h, 0F);
        GL11.glColor4f(0.86F, 0.12F, 0.1F, 1F);
        int n = 7;
        for (int k = 0; k < n; k++) {
            float x0 = -w + 2 * w * k / n, x1 = x0 + w / n;
            poly(new float[][] {{x0, y0 - h}, {x1, y0 - h}, {x1 + h * 0.6F, y0 + h}, {x0 + h * 0.6F, y0 + h}}, 0.0002F);
        }
        GL11.glColor4f(0.86F, 0.12F, 0.1F, 1F);
        rect(-w * 0.55F, y0 - h * 0.7F, w * 0.55F, y0 + h * 0.7F, 0.0003F);
        textLift = 0.0006F;
        text(fr, Lang.tr("sva.cockpit.failFlag"), 0F, y0, h * 1.1F, w * 1.0F, 0xFFFFFF);
        textLift = LIFT_MARK - LIFT_FACE;
        GL11.glPopMatrix();
    }

    /** Stage C: the autopilot panel this client shows - the driver's own state, a passenger's the synced one. */
    static com.sva.vehicle.AutopilotMath.Panel apView(EntityVehicleSVA v) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer != null && mc.thePlayer.ridingEntity == v ? CruiseClientSVA.panel(v) : v.apPanel();
    }

    /**
     * ★11 sounds for this client's riders only (nothing goes over the network), quiet: overheating - a high «pling» once a
     * second; a stall - a two-tone siren (harp notes every 0.3 s). Not while paused or during the startup.
     */
    private static void sounds(Minecraft mc, EntityVehicleSVA v, VehicleType t, State s) {
        if (!ConfigSVA.cockpitSounds || mc.isGamePaused() || !SNAP.powered || phase != CockpitLamps.PH_NORMAL || mc.thePlayer == null) {
            return;
        }
        long ms = System.currentTimeMillis();
        if (CockpitLamps.overheat(SNAP)) {
            if (ms >= s.nextBeep) {
                play(mc, "note.pling", 0.25F, 1.9F);
                s.nextBeep = ms + 1000;
            }
        } else {
            s.nextBeep = 0;
        }
        if (SNAP.stalling) {
            if (ms >= s.nextSiren) {
                play(mc, "note.harp", 0.3F, s.sirenStep++ % 2 == 0 ? 1.5F : 1.12F);
                s.nextSiren = ms + 300;
            }
        } else {
            s.sirenStep = 0;
        }
    }

    private static void play(Minecraft mc, String sound, float volume, float pitch) {
        mc.getSoundHandler().playSound(new PositionedSoundRecord(new ResourceLocation(sound), volume, pitch, (float) mc.thePlayer.posX,
                (float) mc.thePlayer.posY, (float) mc.thePlayer.posZ));
    }

    /** ★8: a neutral text / mark colour taken toward the tier's glow at night. */
    static int tint(int rgb) {
        return night > 0F ? CockpitLamps.mix(rgb, glowRgb, night * 0.85F) : rgb;
    }

    static void glColor(int rgb) {
        GL11.glColor4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
    }

    private static float[] color(int kind) {
        switch (kind) {
            case CockpitDials.SPEED:
            case CockpitDials.CARGO:
                return new float[] {1F, 0.75F, 0.25F};
            case CockpitDials.ALT:
            case CockpitDials.GAS:
            case CockpitDials.HEADING:
                return new float[] {0.45F, 0.85F, 1F};
            case CockpitDials.VSPEED:
            case CockpitDials.TURN:
            case CockpitDials.CHARGE:
            case CockpitDials.HULL:
                return new float[] {0.3F, 0.9F, 0.55F};
            case CockpitDials.RPM:
            case CockpitDials.THROTTLE:
            case CockpitDials.HEAT:
                return new float[] {1F, 0.55F, 0.2F};
            case CockpitDials.DRILL:
            case CockpitDials.DEPTH:
                return new float[] {0.95F, 0.3F, 0.3F};
            default:
                return new float[] {0.75F, 0.45F, 1F};           // core, shield
        }
    }

    // ---------------------------------------------------------------- dials

    private static void drawDial(FontRenderer fr, CockpitLayout.Dial d, int kind, float shown, float min, float max) {
        float r = d.r;
        frame(d.c, d.n, d.up, d.right, d.front + LIFT_FACE);
        if (night > 0F) {
            glowRing(r * 0.95F, r * 1.5F, 0.4F * night, -0.0005F);   // ★8: a soft halo round the dial
        }
        GL11.glColor4f(0.30F, 0.32F, 0.36F, 1F);                    // rim
        disc(0F, 0F, r, 0F);
        GL11.glColor4f(0.06F, 0.07F, 0.09F, 1F);                    // face
        disc(0F, 0F, r * 0.9F, 0.0002F);
        if (phase == CockpitLamps.PH_DARK) {
            GL11.glPopMatrix();                                     // ★11: not lit yet
            return;
        }
        glColor(tint(0xEBEDF2));                                      // ticks: 21 marks, every other one long
        for (int k = 0; k <= 20; k++) {
            float a = -CockpitDials.SWEEP / 2 + CockpitDials.SWEEP * k / 20F;
            boolean major = k % 2 == 0;
            ray(a, r * (major ? 0.68F : 0.76F), r * 0.86F, r * (major ? 0.035F : 0.02F), LIFT_MARK - LIFT_FACE);
        }
        // scale numbers at the ends and the middle, the label and unit under the hub
        float th = r * 0.16F;
        for (int k = 0; k <= 2; k++) {
            float a = (float) Math.toRadians(-CockpitDials.SWEEP / 2 + CockpitDials.SWEEP * k / 2F);
            float val = min + (max - min) * k / 2F;
            text(fr, num(val), (float) Math.sin(a) * r * 0.5F, (float) Math.cos(a) * r * 0.5F, th, r * 0.5F, tint(0xD8D8DE));
        }
        String key = CockpitDials.KEYS[kind];
        text(fr, Lang.tr("sva.cockpit.dial." + key), 0F, -r * 0.36F, r * 0.15F, r * 1.3F, tint(0xFFFFFF));
        text(fr, Lang.tr("sva.cockpit.unit." + key), 0F, -r * 0.56F, r * 0.11F, r * 0.9F, tint(0x9A9AA6));
        // the needle
        float[] c = color(kind);
        GL11.glColor4f(c[0], c[1], c[2], 1F);
        float a = CockpitDials.needleAngle(shown, min, max);
        needle(a, r * 0.8F, r * 0.18F, r * 0.04F, LIFT_NEEDLE - LIFT_FACE);
        GL11.glColor4f(0.55F, 0.57F, 0.62F, 1F);
        disc(0F, 0F, r * 0.08F, LIFT_HUB - LIFT_FACE);
        GL11.glPopMatrix();
    }

    private static String num(float v) {
        return String.valueOf(Math.round(v));
    }

    // ---------------------------------------------------------------- screens

    /** One text line of the main screen: text, colour, an optional bar (frac < 0: none) and its colour. */
    private static final class Line {
        final String text;
        final int color;
        final float frac;
        final float[] bar;

        Line(String text, int color, float frac, float[] bar) {
            this.text = text;
            this.color = color;
            this.frac = frac;
            this.bar = bar;
        }
    }

    private static final int TEXT = 0xE0E0E0, DIM = 0x9A9AA6, GREEN = 0x5EE07A, AMBER = 0xFFD24D, WARN = 0xFF5A4A, BLUE = 0x5EC8F0,
            ORANGE = 0xFF8A2A;

    /** The main screen's lines, the most important first (the fitting drops the last ones that don't fit). */
    private static List<Line> mainLines(EntityVehicleSVA v, VehicleType t, State s) {
        List<Line> out = new ArrayList<Line>();
        boolean blink = (System.currentTimeMillis() / 250) % 2 == 0;
        // ★11 events first (the fitting drops lines from the end)
        if (CockpitLamps.overheat(SNAP)) {
            out.add(new Line(Lang.tr("sva.cockpit.ev.cool"), blink ? WARN : 0x803030, -1F, null));
        }
        if (v.isBoosting()) {
            out.add(new Line(Lang.tr("sva.cockpit.ev.boost"), ORANGE, -1F, null));
        }
        double regen = v.getRegenNow();                             // wave 4 (★15): recovering energy
        if (regen > 0) {
            out.add(new Line(Lang.tr("sva.cockpit.ev.regen", com.sva.vehicle.RegenMath.fmt(regen)), GREEN, -1F, null));
        }
        String apl = t.movement.flies && ConfigSVA.avionics ? AvionicsSVA.apLine(apView(v)) : null;
        if (apl != null) {
            out.add(new Line(apl, GREEN, -1F, null));                // stage C: the autopilot's modes and set values
        } else if (SNAP.autopilot) {
            out.add(new Line(Lang.tr("sva.cockpit.ev.autopilot"), GREEN, -1F, null));     // auto-delivery or ★4 autopilot
        } else if (SNAP.cruise) {
            out.add(new Line(Lang.tr("sva.cockpit.ev.cruise"), GREEN, -1F, null));
        }
        if (SNAP.cargoFull) {
            out.add(new Line(Lang.tr("sva.cockpit.ev.cargoFull"), BLUE, -1F, null));
        }
        if (com.sva.vehicle.SystemsMath.applies(t)) {
            // round 2, D2: the engine start («ЗАПУСК 40 %»), the failures as lines (the jet / tiltrotor have no overhead
            // panel: their «ВНИМАНИЕ» is the MASTER lamp)
            long ms = System.currentTimeMillis();
            int es = v.engineState();
            if (v.engineGated(t) && es != com.sva.vehicle.SystemsMath.RUN) {
                int pctS = Math.round(100 * com.sva.vehicle.SystemsMath.progress(es, v.engineTicks(), com.sva.vehicle.SystemsMath.crankTicks(t, false),
                        com.sva.vehicle.SystemsMath.warmTicks(t)));
                out.add(new Line(Lang.tr("sva.cockpit.engine." + com.sva.vehicle.SystemsMath.ENGINE_KEYS[es], Integer.valueOf(pctS)),
                        es == com.sva.vehicle.SystemsMath.OFF ? DIM : AMBER, -1F, null));
            }
            for (int role : CockpitLamps.failureLamps(t)) {
                int st = CockpitLamps.state(role, SNAP);
                if (st == CockpitLamps.OFF) {
                    continue;
                }
                int c = CockpitLamps.colour(role);
                out.add(new Line(Lang.tr("sva.cockpit.lamp." + CockpitLamps.KEYS[role]), CockpitLamps.lit(st, ms) ? c : CockpitLamps.mix(c, 0, 0.5F), -1F, null));
            }
        }
        if (ConfigSVA.avionics && com.sva.vehicle.HighLiftMath.spec(t) != null) {
            // stage C: the lit annunciators as lines (the jet / tiltrotor have no annunciator column)
            long ms = System.currentTimeMillis();
            for (int role : CockpitLamps.annunciators(t)) {
                int st = CockpitLamps.state(role, SNAP);
                if (st == CockpitLamps.OFF || role == CockpitLamps.STALL) {
                    continue;                                       // the stall has its own flag on the attitude screen
                }
                String label = role == CockpitLamps.FLAP_LIMIT
                        ? Lang.tr("sva.cockpit.lamp.flapLimit", String.valueOf(com.sva.vehicle.HighLiftMath.limitKmh(com.sva.vehicle.HighLiftMath.spec(t),
                                Math.min(com.sva.vehicle.HighLiftMath.spec(t).last(), v.getFlaps() + 1))))
                        : role == CockpitLamps.FLAPS_EXT ? Lang.tr("sva.cockpit.flapsLine", Integer.valueOf(Math.round(v.getFlapAngle())))
                        : Lang.tr("sva.cockpit.lamp." + CockpitLamps.KEYS[role]);
                int c = CockpitLamps.colour(role);
                out.add(new Line(label, CockpitLamps.lit(st, ms) || st == CockpitLamps.ON ? c : CockpitLamps.mix(c, 0, 0.5F), -1F, null));
            }
        }
        float e = v.getEnergy() / (float) Math.max(1F, (float) v.getMaxEnergy());
        out.add(new Line(Lang.tr("sva.cockpit.charge", pct(e * 100)), e < 0.1F ? WARN : TEXT, e, e < 0.1F ? new float[] {1F, 0.3F, 0.25F}
                : new float[] {0.3F, 0.9F, 0.55F}));
        float cruise = v.getCruiseCost();
        if (cruise > 0) {
            // the range (the HUD's estimate); blinks amber on low charge
            String range = String.valueOf((long) (v.getEnergy() / cruise * t.topSpeed));
            boolean low = CockpitLamps.lowCharge(SNAP);
            out.add(new Line(Lang.tr("sva.cockpit.range", range), low ? (blink ? AMBER : 0x806020) : DIM, -1F, null));
        }
        String[] mode = mode(v, t, s);
        out.add(new Line(Lang.tr("sva.cockpit.mode", mode[0]), Integer.parseInt(mode[1]), -1F, null));
        String trailer = HudVehicleSVA.trailerLine(v);
        if (trailer != null) {
            out.add(new Line(trailer, BLUE, -1F, null));             // ★15: the hitched trailer's key reading
        }
        if (t.movement == Movement.PLANE || t.movement == Movement.TILT && v.planeNow()) {
            out.add(new Line(Lang.tr(s.gearDown ? "sva.cockpit.gear.down" : "sva.cockpit.gear.up"), s.gearDown ? GREEN : AMBER, -1F, null));
        }
        if (v.wearOn()) {
            float hp = v.getHp() / v.getMaxHp();
            out.add(new Line(Lang.tr("sva.cockpit.hull", pct(hp * 100)), hp < 0.25F ? AMBER : TEXT, hp, hp < 0.25F ? new float[] {1F, 0.76F, 0.23F}
                    : new float[] {0.75F, 0.78F, 0.82F}));
        }
        for (Gas g : t.gases.gases()) {
            float f = gasPct(v, t, g) / 100F;
            int c = 0xFF000000 | g.color;
            out.add(new Line(Lang.tr("sva.gas." + g.key()) + " " + pct(f * 100), TEXT, f,
                    new float[] {(c >> 16 & 255) / 255F, (c >> 8 & 255) / 255F, (c & 255) / 255F}));
        }
        float heat = v.getHeat() / 100F;
        out.add(new Line(Lang.tr("sva.cockpit.heat", pct(heat * 100)), heat > 0.8F ? WARN : TEXT, heat, new float[] {1F, 0.63F, 0.25F}));
        return out;
    }

    /** ★11 the drill console's «ДОБЫЧА» page: mode and size, dug this trip, the bunker, the bore head. */
    private static List<Line> miningLines(EntityVehicleSVA v, State s) {
        List<Line> out = new ArrayList<Line>();
        out.add(new Line(Lang.tr("sva.cockpit.mining"), AMBER, -1F, null));
        String mode = Lang.tr("sva.drill.mode." + v.getDrillMode()) + " " + (v.hasWidener() ? "5x5" : "3x3");
        out.add(new Line(Lang.tr("sva.cockpit.mining.mode", mode), v.isDrillOn() ? GREEN : DIM, -1F, null));
        out.add(new Line(Lang.tr("sva.cockpit.mining.trip", String.valueOf(Math.max(0, v.getDug() - s.dugAtStart))), TEXT, -1F, null));
        int used = v.getCargoUsed(), cap = Math.max(1, v.cargoCapacity());
        float f = used / (float) cap;
        out.add(new Line(Lang.tr("sva.cockpit.mining.bunker", used + "/" + cap), SNAP.cargoFull ? BLUE : TEXT, f, new float[] {0.37F, 0.78F, 0.94F}));
        int left = v.getHeadLeft(), max = v.getHeadMax();
        if (!v.hasHead()) {
            out.add(new Line(Lang.tr("sva.cockpit.mining.noHead"), WARN, -1F, null));
        } else if (left < 0 || max <= 0) {
            out.add(new Line(Lang.tr("sva.cockpit.mining.headForever"), GREEN, -1F, null));
        } else {
            float h = left / (float) max;
            out.add(new Line(Lang.tr("sva.cockpit.mining.head", pct(h * 100)), h < 0.15F ? WARN : TEXT, h, h < 0.15F ? new float[] {1F, 0.3F, 0.25F}
                    : new float[] {0.75F, 0.78F, 0.82F}));
        }
        return out;
    }

    private static String pct(float p) {
        return (int) Math.floor(Math.max(0, Math.min(100, p))) + "%";
    }

    /** {text, colour} of the «РЕЖИМ» line. */
    private static String[] mode(EntityVehicleSVA v, VehicleType t, State s) {
        boolean blink = (System.currentTimeMillis() / 250) % 2 == 0;
        String k;
        int c;
        if (v.isHeatStopped()) {
            k = "overheat";
            c = WARN;
        } else if (v.getEnergy() <= 0) {
            k = "empty";
            c = WARN;
        } else if (t.movement.flies) {
            boolean heli = t.movement == Movement.HELI || t.movement == Movement.TILT && !v.planeNow();
            boolean air = v.airborneAt(v.posX, v.posY, v.posZ);
            if (v.isAutorotating()) {
                k = heli ? "auto" : "glide";
                c = blink ? WARN : AMBER;
            } else if (v.isStalling()) {
                k = "stall";
                c = blink ? WARN : 0x803030;
            } else if (!air) {
                k = "ground";
                c = DIM;
            } else if (heli && s.speed < 0.15F) {
                k = "hover";
                c = AMBER;
            } else {
                k = "fly";
                c = GREEN;
            }
        } else if (t.hasDrill) {
            k = v.isDrillOn() ? "drillOn" : "drillOff";
            c = v.isDrillOn() ? GREEN : DIM;
        } else if (t.movement == Movement.HOVER) {
            k = v.isLifting() ? "lift" : "ground";
            c = v.isLifting() ? BLUE : DIM;
        } else if (t.autoDelivery && v.isAutoOn()) {
            k = "auto.delivery";
            c = BLUE;
        } else {
            k = s.speed > 0.02F ? "drive" : "park";
            c = s.speed > 0.02F ? GREEN : DIM;
        }
        return new String[] {Lang.tr("sva.cockpit.mode." + k), String.valueOf(c)};
    }

    /** Pixels (font units) of a bar after a line's text. */
    private static final int BAR_PX = 26, GAP_PX = 4, LINE_PX = 10;

    /**
     * The main screen: the lines in R rows x C columns, R chosen for the biggest text that fits (a wide strip gets one
     * row of several cells, a square screen one column); lines that don't fit at a readable size (a text line under 1/9
     * of the screen height or 0.01 model units) are left out from the end.
     */
    private static void drawMain(FontRenderer fr, CockpitLayout.Screen sc, List<Line> lines) {
        frame(sc.c, sc.n, sc.up, sc.right, sc.front + LIFT_FACE);
        background(sc.w, sc.h);
        float w = sc.w * 0.9F, h = sc.h * 0.86F;
        int n = lines.size();
        float best = 0;
        int bestRows = 1, bestN = 1;
        for (int count = n; count >= 1; count--) {
            int cellPx = 0;
            for (int i = 0; i < count; i++) {
                Line ln = lines.get(i);
                cellPx = Math.max(cellPx, fr.getStringWidth(ln.text) + (ln.frac >= 0 ? GAP_PX + BAR_PX : 0));
            }
            for (int rows = 1; rows <= count; rows++) {
                int cols = (count + rows - 1) / rows;
                float k = Math.min(w / (cols * cellPx + (cols - 1) * 8), h / (rows * LINE_PX));
                if (k > best) {
                    best = k;
                    bestRows = rows;
                    bestN = count;
                }
            }
            if (best * LINE_PX >= Math.max(sc.h / 9F, 0.01F)) {
                break;                                          // readable with `count` lines: keep them all
            }
        }
        int rows = bestRows, cols = (bestN + rows - 1) / rows;
        float k = best;
        float cellW = w / cols;
        for (int i = 0; i < bestN; i++) {
            Line ln = lines.get(i);
            int col = i / rows, row = i % rows;
            float x0 = -w / 2 + col * cellW;
            float yc = h / 2 - (row + 0.5F) * (h / rows);
            float tw = fr.getStringWidth(ln.text) * k;
            textLeft(fr, ln.text, x0, yc, k * 8F, ln.color == TEXT || ln.color == DIM ? tint(ln.color) : ln.color);
            if (ln.frac >= 0) {
                float bx0 = x0 + tw + GAP_PX * k, bx1 = Math.min(x0 + cellW - 2 * k, bx0 + BAR_PX * k);
                if (bx1 > bx0) {
                    GL11.glColor4f(0.08F, 0.1F, 0.12F, 1F);
                    rect(bx0, yc - 3 * k, bx1, yc + 2 * k, LIFT_MARK - LIFT_FACE);
                    float f = Math.max(0F, Math.min(1F, ln.frac));
                    GL11.glColor4f(ln.bar[0], ln.bar[1], ln.bar[2], 1F);
                    rect(bx0, yc - 3 * k, bx0 + (bx1 - bx0) * f, yc + 2 * k, LIFT_NEEDLE - LIFT_FACE);
                }
            }
        }
        GL11.glPopMatrix();
    }

    /** A small screen: the reading's label over its value (big). */
    private static void drawSmall(FontRenderer fr, CockpitLayout.Screen sc, int kind, EntityVehicleSVA v, VehicleType t, State s, float pt) {
        frame(sc.c, sc.n, sc.up, sc.right, sc.front + LIFT_FACE);
        background(sc.w, sc.h);
        float val = value(kind, v, t, s, pt);
        String vs = kind == CockpitDials.CARGO ? Math.round(val) + "/" + v.cargoCapacity()
                : kind == CockpitDials.SPEED || kind == CockpitDials.ALT || kind == CockpitDials.DEPTH ? num(val)
                : kind == CockpitDials.VSPEED ? String.format("%.1f", Float.valueOf(val)).replace('.', ',') : pct(val);
        float[] c = color(kind);
        int col = (int) (c[0] * 255) << 16 | (int) (c[1] * 255) << 8 | (int) (c[2] * 255);
        text(fr, Lang.tr("sva.cockpit.dial." + CockpitDials.KEYS[kind]), 0F, sc.h * 0.22F, sc.h * 0.2F, sc.w * 0.86F, DIM);
        text(fr, vs, 0F, -sc.h * 0.14F, sc.h * 0.34F, sc.w * 0.86F, col);
        GL11.glPopMatrix();
    }

    /** A tall narrow strip: a bar filling bottom-up. */
    private static void drawBar(CockpitLayout.Screen sc, float frac, float[] c) {
        frame(sc.c, sc.n, sc.up, sc.right, sc.front + LIFT_FACE);
        background(sc.w, sc.h);
        float x0 = -sc.w * 0.32F, x1 = sc.w * 0.32F, y0 = -sc.h * 0.42F, y1 = sc.h * 0.42F;
        GL11.glColor4f(0.08F, 0.1F, 0.12F, 1F);
        rect(x0, y0, x1, y1, LIFT_MARK - LIFT_FACE);
        GL11.glColor4f(c[0], c[1], c[2], 1F);
        rect(x0, y0, x1, y0 + (y1 - y0) * frac, LIFT_NEEDLE - LIFT_FACE);
        GL11.glPopMatrix();
    }

    /**
     * The attitude indicator: sky over ground split by the horizon - tilted by the bank, moved down as the nose
     * comes up (the screen's height = 60 degrees of pitch) - a pitch ladder every 10 degrees and the fixed aircraft mark.
     */
    private static void drawAttitude(FontRenderer fr, CockpitLayout.Screen sc, float pitch, float roll, boolean stall) {
        frame(sc.c, sc.n, sc.up, sc.right, sc.front + LIFT_FACE);
        if (night > 0F) {
            glowRect(sc.w, sc.h, 0.35F * night);
        }
        float hw = sc.w / 2, hh = sc.h / 2;
        float perDeg = sc.h / 60F;
        double a = Math.toRadians(roll);                      // bank right (+): the horizon's right end rises
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        // the horizon through (ox, oy) along (ca, sa); "sky" = the side its normal (-sa, ca) points to
        float ox = sa * pitch * perDeg, oy = -ca * pitch * perDeg;
        float[][] rect = {{-hw, -hh}, {hw, -hh}, {hw, hh}, {-hw, hh}};
        GL11.glColor4f(0.24F, 0.47F, 0.80F, 1F);
        poly(clip(rect, ox, oy, -sa, ca), 0F);
        GL11.glColor4f(0.55F, 0.35F, 0.16F, 1F);
        poly(clip(rect, ox, oy, sa, -ca), 0F);
        GL11.glColor4f(0.95F, 0.95F, 0.97F, 1F);
        for (int deg = -30; deg <= 30; deg += 10) {
            float len = deg == 0 ? hw * 2 : hw * (deg % 20 == 0 ? 0.5F : 0.3F);
            float cx = ox + -sa * deg * perDeg, cy = oy + ca * deg * perDeg;
            float x0 = cx - ca * len / 2, y0 = cy - sa * len / 2, x1 = cx + ca * len / 2, y1 = cy + sa * len / 2;
            float[][] seg = clipSegment(x0, y0, x1, y1, hw * 0.96F, hh * 0.96F);
            if (seg != null) {
                line(seg[0][0], seg[0][1], seg[1][0], seg[1][1], sc.h * (deg == 0 ? 0.025F : 0.015F), LIFT_MARK - LIFT_FACE);
            }
        }
        GL11.glColor4f(1F, 0.82F, 0.2F, 1F);                        // the aircraft: a yellow W and a dot
        float u = hw * 0.18F, lw = sc.h * 0.03F, lift = LIFT_NEEDLE - LIFT_FACE;
        line(-hw * 0.55F, 0F, -u, 0F, lw, lift);
        line(-u, 0F, 0F, -u * 0.6F, lw, lift);
        line(0F, -u * 0.6F, u, 0F, lw, lift);
        line(u, 0F, hw * 0.55F, 0F, lw, lift);
        disc(0F, 0F, lw, LIFT_HUB - LIFT_FACE);
        if (stall && (System.currentTimeMillis() / 250) % 2 == 0) {
            // ★11: the stall flag across the top (the plane has no text screen)
            GL11.glColor4f(0.85F, 0.12F, 0.08F, 1F);
            rect(-hw * 0.7F, hh * 0.5F, hw * 0.7F, hh * 0.92F, LIFT_HUB - LIFT_FACE + 0.0005F);
            textLift = LIFT_HUB - LIFT_FACE + 0.001F;
            text(fr, Lang.tr("sva.cockpit.lamp.stall"), 0F, hh * 0.71F, hh * 0.3F, hw * 1.3F, 0xFFFFFF);
            textLift = LIFT_MARK - LIFT_FACE;
        }
        GL11.glPopMatrix();
    }

    /** Sutherland-Hodgman: the part of convex polygon p on the side of the line through (ox, oy) that (nx, ny) points to. */
    static float[][] clip(float[][] p, float ox, float oy, float nx, float ny) {
        List<float[]> out = new ArrayList<float[]>();
        for (int i = 0; i < p.length; i++) {
            float[] a = p[i], b = p[(i + 1) % p.length];
            float da = (a[0] - ox) * nx + (a[1] - oy) * ny, db = (b[0] - ox) * nx + (b[1] - oy) * ny;
            if (da >= 0) {
                out.add(a);
            }
            if (da >= 0 != db >= 0) {
                float f = da / (da - db);
                out.add(new float[] {a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f});
            }
        }
        return out.toArray(new float[out.size()][]);
    }

    /** A segment cut to the box |x| <= hx, |y| <= hy (Liang-Barsky); null when it misses. */
    static float[][] clipSegment(float x0, float y0, float x1, float y1, float hx, float hy) {
        float t0 = 0, t1 = 1, dx = x1 - x0, dy = y1 - y0;
        float[] p = {-dx, dx, -dy, dy}, q = {x0 + hx, hx - x0, y0 + hy, hy - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return null;
                }
            } else {
                float r = q[i] / p[i];
                if (p[i] < 0) {
                    t0 = Math.max(t0, r);
                } else {
                    t1 = Math.min(t1, r);
                }
            }
        }
        if (t0 > t1) {
            return null;
        }
        return new float[][] {{x0 + dx * t0, y0 + dy * t0}, {x0 + dx * t1, y0 + dy * t1}};
    }

    // ---------------------------------------------------------------- ★11 lamps, ★11 startup screens, ★8 floor strip

    /** Lamp label height (model units) at most, and the share of the room to the next lamp it may be wide. */
    private static final float LABEL_H = 0.0095F, LABEL_ROOM = 0.92F;

    /**
     * The warning lamps by role (CockpitLamps.table: colour, lit / blinking from the snapshot; the lamp test lights them
     * all, the dark and logo phases none) and the plane's gear lamps (CockpitLamps.gear); a lit lamp gets a halo (brighter
     * at night), each a tiny label under it facing the driver's eye.
     */
    private static void drawLamps(FontRenderer fr, CockpitLayout l, VehicleType t, EntityVehicleSVA v, float pt) {
        int[] roles = CockpitLamps.table(t);
        float[] eye = CockpitLayout.eye(t);
        long ms = System.currentTimeMillis();
        for (int i = 0; i < l.lamps.size(); i++) {
            int role = i < roles.length ? roles[i] : CockpitLamps.MASTER;
            boolean lit = phase == CockpitLamps.PH_TEST || phase != CockpitLamps.PH_DARK && phase != CockpitLamps.PH_LOGO
                    && CockpitLamps.lit(CockpitLamps.state(role, SNAP), ms);
            String key = CockpitLamps.KEYS[role];
            drawLamp(fr, l.lamps.get(i), CockpitLamps.colour(role), lit, key.length() == 0 ? null : Lang.tr("sva.cockpit.lamp." + key), eye,
                    CockpitLayout.labelRoom(l.lamps, i));
        }
        if (!l.okLamps.isEmpty()) {
            int rgb;
            if (!SNAP.powered || phase == CockpitLamps.PH_DARK || phase == CockpitLamps.PH_LOGO) {
                rgb = 0;
            } else if (phase == CockpitLamps.PH_TEST) {
                rgb = CockpitLamps.GREEN;
            } else if (ConfigSVA.animations) {
                rgb = CockpitLamps.gear(AnimMath.lerp(v.anim.prevGear, v.anim.gear, pt), v.anim.gearUp);
            } else {
                rgb = state(v).gearDown ? CockpitLamps.GREEN : 0;
            }
            for (int i = 0; i < l.okLamps.size(); i++) {
                // the middle one carries the label «ШАССИ»
                String label = i == l.okLamps.size() / 2 ? Lang.tr("sva.cockpit.lamp.gear") : null;
                drawLamp(fr, l.okLamps.get(i), rgb == 0 ? CockpitLamps.GREEN : rgb, rgb != 0, label, eye, 0.12F);
            }
        }
    }

    private static void drawLamp(FontRenderer fr, CockpitLayout.Lamp p, int rgb, boolean lit, String label, float[] eye, float room) {
        float k = lit ? 1F : SNAP.powered ? 0.2F : 0.12F;          // an unlit lamp: its dark coloured glass
        GL11.glColor4f((rgb >> 16 & 255) / 255F * k, (rgb >> 8 & 255) / 255F * k, (rgb & 255) / 255F * k, 1F);
        lamp(p.c, p.r * 1.12F);
        float[][] f = CockpitLayout.faceFrame(p.c, eye);
        float[] n = f[0], up = f[1], right = f[2];
        if (lit) {
            frame(p.c, n, up, right, p.r * 1.2F);
            int keep = glowRgb;
            glowRgb = rgb;
            // the halo: 3 x the lamp, at most CockpitView.HALO_MAX x its distance from the eye (overhead lamps right over the head)
            float halo = (float) com.sva.vehicle.CockpitView.lampHalo(p.r, CockpitLayout.len(CockpitLayout.sub(p.c, eye)));
            glowRing(Math.min(p.r * 0.5F, halo * 0.5F), halo, 0.3F + 0.4F * night, 0F);
            glowRgb = keep;
            GL11.glPopMatrix();
        }
        if (label != null) {
            float h = Math.min(LABEL_H, room * 0.2F);
            float[] c = {p.c[0] - up[0] * (p.r * 1.15F + h * 0.7F), p.c[1] - up[1] * (p.r * 1.15F + h * 0.7F), p.c[2] - up[2] * (p.r * 1.15F + h * 0.7F)};
            frame(c, n, up, right, p.r * 0.9F);
            text(fr, label, 0F, 0F, h, room * LABEL_ROOM, lit ? rgb : tint(0xA8A8B2));
            GL11.glPopMatrix();
        }
    }

    /** ★11 a screen during the startup: dark, then white-ish (the lamp test), then the SA logo in the tier's colour. */
    private static void drawStartScreen(FontRenderer fr, CockpitLayout.Screen sc) {
        frame(sc.c, sc.n, sc.up, sc.right, sc.front + LIFT_FACE);
        if (phase == CockpitLamps.PH_DARK) {
            GL11.glColor4f(0.012F, 0.014F, 0.02F, 1F);
            rect(-sc.w / 2, -sc.h / 2, sc.w / 2, sc.h / 2, 0F);
        } else if (phase == CockpitLamps.PH_TEST) {
            GL11.glColor4f(0.80F, 0.86F, 0.90F, 1F);
            rect(-sc.w / 2, -sc.h / 2, sc.w / 2, sc.h / 2, 0F);
        } else {
            background(sc.w, sc.h);
            text(fr, "SA", 0F, 0F, sc.h * 0.5F, sc.w * 0.8F, glowRgb);
        }
        GL11.glPopMatrix();
    }

    /**
     * ★8 the floor light strip: a thin glowing line along both sides of the cabin floor ('carpet' box, model units) in the
     * tier's colour, with a soft wider glow - a hair over the floor, both sides inset 0.02.
     */
    private static void floorStrip(float[] fl) {
        float y = fl[3] + 0.002F, x0 = fl[0] + 0.02F, x1 = fl[1] - 0.02F;
        float r = (glowRgb >> 16 & 255) / 255F, g = (glowRgb >> 8 & 255) / 255F, b = (glowRgb & 255) / 255F;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDepthMask(false);
        for (int side = 0; side < 2; side++) {
            float z = side == 0 ? fl[4] + 0.02F : fl[5] - 0.02F, in = side == 0 ? 1F : -1F;
            GL11.glColor4f(r, g, b, 0.9F * night);                  // the strip
            quadXZ(x0, x1, z, z + in * 0.008F, y);
            GL11.glBegin(GL11.GL_QUADS);                            // its glow onto the floor, fading inward
            GL11.glColor4f(r, g, b, 0.35F * night);
            GL11.glVertex3f(x0, y, z + in * 0.008F);
            GL11.glVertex3f(x1, y, z + in * 0.008F);
            GL11.glColor4f(r, g, b, 0F);
            GL11.glVertex3f(x1, y, z + in * 0.09F);
            GL11.glVertex3f(x0, y, z + in * 0.09F);
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }

    private static void quadXZ(float x0, float x1, float z0, float z1, float y) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(x0, y, z0);
        GL11.glVertex3f(x1, y, z0);
        GL11.glVertex3f(x1, y, z1);
        GL11.glVertex3f(x0, y, z1);
        GL11.glEnd();
    }

    /**
     * ★8 a soft additive halo ring in the current frame: alpha `a` at radius r0 fading to 0 at r1, in glowRgb, no depth
     * writes (drawn a hair behind the face at z, so the dial / lamp itself covers the middle).
     */
    static void glowRing(float r0, float r1, float a, float z) {
        float r = (glowRgb >> 16 & 255) / 255F, g = (glowRgb >> 8 & 255) / 255F, b = (glowRgb & 255) / 255F;
        glowOn();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int k = 0; k <= 32; k++) {
            double ang = k * Math.PI * 2 / 32;
            float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
            GL11.glColor4f(r, g, b, Math.min(1F, a));
            GL11.glVertex3f(c * r0, sn * r0, z);
            GL11.glColor4f(r, g, b, 0F);
            GL11.glVertex3f(c * r1, sn * r1, z);
        }
        GL11.glEnd();
        glowOff();
    }

    /** ★8 the same round a w x h screen: a border 0.18 of the smaller side wide, fading outward. */
    static void glowRect(float w, float h, float a) {
        float r = (glowRgb >> 16 & 255) / 255F, g = (glowRgb >> 8 & 255) / 255F, b = (glowRgb & 255) / 255F;
        float e = Math.min(w, h) * 0.18F, x0 = -w / 2, x1 = w / 2, y0 = -h / 2, y1 = h / 2, z = -0.0005F;
        float[][] in = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        float[][] out = {{x0 - e, y0 - e}, {x1 + e, y0 - e}, {x1 + e, y1 + e}, {x0 - e, y1 + e}};
        glowOn();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int k = 0; k <= 4; k++) {
            GL11.glColor4f(r, g, b, Math.min(1F, a));
            GL11.glVertex3f(in[k % 4][0], in[k % 4][1], z);
            GL11.glColor4f(r, g, b, 0F);
            GL11.glVertex3f(out[k % 4][0], out[k % 4][1], z);
        }
        GL11.glEnd();
        glowOff();
    }

    static void glowOn() {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDepthMask(false);
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    static void glowOff() {
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    // ---------------------------------------------------------------- GL helpers (the current frame: x right, y up, z out)

    /** Pushes a matrix with origin c + n * depth and axes right, up, n (model units). */
    static void frame(float[] c, float[] n, float[] up, float[] right, float depth) {
        GL11.glPushMatrix();
        MAT.clear();
        MAT.put(right[0]).put(right[1]).put(right[2]).put(0F);
        MAT.put(up[0]).put(up[1]).put(up[2]).put(0F);
        MAT.put(n[0]).put(n[1]).put(n[2]).put(0F);
        MAT.put(c[0] + n[0] * depth).put(c[1] + n[1] * depth).put(c[2] + n[2] * depth).put(1F);
        MAT.flip();
        GL11.glMultMatrix(MAT);
    }

    static void background(float w, float h) {
        if (night > 0F) {
            glowRect(w, h, 0.35F * night);                          // ★8: a soft halo round the screen
        }
        glColor(night > 0F ? CockpitLamps.mix(0x5ADBFF, glowRgb, night) : 0x5ADBFF);   // a thin lit edge
        rect(-w / 2, -h / 2, w / 2, h / 2, 0F);
        GL11.glColor4f(0.03F, 0.06F, 0.09F, 1F);
        float e = Math.min(w, h) * 0.04F;
        rect(-w / 2 + e, -h / 2 + e, w / 2 - e, h / 2 - e, 0.0002F);
    }

    static void disc(float x, float y, float r, float z) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex3f(x, y, z);
        for (int k = 0; k <= 32; k++) {
            double a = k * Math.PI * 2 / 32;
            GL11.glVertex3f(x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r, z);
        }
        GL11.glEnd();
    }

    static void rect(float x0, float y0, float x1, float y1, float z) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(x0, y0, z);
        GL11.glVertex3f(x1, y0, z);
        GL11.glVertex3f(x1, y1, z);
        GL11.glVertex3f(x0, y1, z);
        GL11.glEnd();
    }

    static void poly(float[][] p, float z) {
        if (p.length < 3) {
            return;
        }
        GL11.glBegin(GL11.GL_POLYGON);
        for (float[] q : p) {
            GL11.glVertex3f(q[0], q[1], z);
        }
        GL11.glEnd();
    }

    /** A thick line segment (a quad) of width w. */
    static void line(float x0, float y0, float x1, float y1, float w, float z) {
        float dx = x1 - x0, dy = y1 - y0, l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l < 1e-6F) {
            return;
        }
        float px = -dy / l * w / 2, py = dx / l * w / 2;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(x0 + px, y0 + py, z);
        GL11.glVertex3f(x0 - px, y0 - py, z);
        GL11.glVertex3f(x1 - px, y1 - py, z);
        GL11.glVertex3f(x1 + px, y1 + py, z);
        GL11.glEnd();
    }

    /** A radial mark at `deg` (clockwise from up) from radius r0 to r1, width w. */
    static void ray(float deg, float r0, float r1, float w, float z) {
        double a = Math.toRadians(deg);
        float sx = (float) Math.sin(a), cy = (float) Math.cos(a);
        line(sx * r0, cy * r0, sx * r1, cy * r1, w, z);
    }

    /** The needle: a thin spike from a short tail through the hub to `len` at `deg`. */
    static void needle(float deg, float len, float tail, float w, float z) {
        double a = Math.toRadians(deg);
        float sx = (float) Math.sin(a), cy = (float) Math.cos(a), px = cy * w, py = -sx * w;
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex3f(-sx * tail + px, -cy * tail + py, z);
        GL11.glVertex3f(sx * len, cy * len, z);
        GL11.glVertex3f(-sx * tail - px, -cy * tail - py, z);
        GL11.glEnd();
    }

    /** A lamp: three crossed discs round its centre (reads as a ball from any seat). */
    private static void lamp(float[] c, float r) {
        float[][] axes = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int i = 0; i < 3; i++) {
            float[] u = axes[(i + 1) % 3], w = axes[(i + 2) % 3];
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glVertex3f(c[0], c[1], c[2]);
            for (int k = 0; k <= 16; k++) {
                double a = k * Math.PI * 2 / 16;
                float cu = (float) Math.cos(a) * r, sw = (float) Math.sin(a) * r;
                GL11.glVertex3f(c[0] + u[0] * cu + w[0] * sw, c[1] + u[1] * cu + w[1] * sw, c[2] + u[2] * cu + w[2] * sw);
            }
            GL11.glEnd();
        }
    }

    /** Centred text, `height` model units for the 8 px font, never wider than maxW. */
    static void text(FontRenderer fr, String s, float x, float y, float height, float maxW, int color) {
        int px = fr.getStringWidth(s);
        if (px <= 0) {
            return;
        }
        float k = Math.min(height / 8F, maxW / px);
        drawText(fr, s, x - px * k / 2, y, k, color);
    }

    /** Left-aligned text starting at x, vertically centred on y. */
    static void textLeft(FontRenderer fr, String s, float x, float y, float height, int color) {
        drawText(fr, s, x, y, height / 8F, color);
    }

    /** Text's lift over the current frame (a flag over the attitude screen raises it). */
    static float textLift = LIFT_MARK - LIFT_FACE;

    static void drawText(FontRenderer fr, String s, float x, float y, float k, int color) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, textLift);
        GL11.glScalef(k, -k, k);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        fr.drawString(s, 0, -4, color);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
    }
}
