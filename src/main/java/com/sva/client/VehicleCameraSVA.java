package com.sva.client;

import java.lang.reflect.Field;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.CameraMath;

import cpw.mods.fml.relauncher.ReflectionHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Wave 1, ★3 (mock-up veh_more_2_camera_cruise.png): the third-person camera while riding a vehicle.
 * <p>
 * 1.7.10 has no camera event for the distance: EntityRenderer.orientCamera reads the private thirdPersonDistance /
 * thirdPersonDistanceTemp (SRG field_78490_B / field_78491_C) - they are set here once a frame (eased toward
 * CameraMath.distance: 1.5 x the vehicle's length, 6..24 blocks, x the wheel zoom 0.5..2 x vehicles.cameraDistance) and
 * put back to vanilla's 4 when the ride ends. orientCamera's own ray test (8 rays against the blocks) keeps the camera out
 * of walls and the ground. For the back view the rider's pitch is raised by CameraMath.PITCH_OFFSET for the frame (the
 * camera orbits by the look pitch - it goes higher and looks down at the vehicle) and lowered again at the frame's end,
 * so the look itself doesn't change. The follow lag on turns is in ClientProxySVA.onRenderTick (the view's yaw).
 * <p>
 * The mouse wheel while riding in third person (no screen open, cruise not engaged - then it sets the cruise speed)
 * zooms instead of scrolling the hotbar (EventsClient: MouseEvent).
 */
public final class VehicleCameraSVA {

    private static Field dist, distTemp;
    private static boolean reflectFailed, changed;
    /** The wheel's zoom (kept for the session) and the eased distance now. */
    private static double zoom = 1, shown = -1;
    private static long lastNanos;
    /** The pitch offset applied this frame (taken off again at the frame's end). */
    private static float pitchApplied;

    private VehicleCameraSVA() {
    }

    private static boolean reflect() {
        if (dist != null) {
            return true;
        }
        if (reflectFailed) {
            return false;
        }
        try {
            dist = ReflectionHelper.findField(EntityRenderer.class, "thirdPersonDistance", "field_78490_B");
            distTemp = ReflectionHelper.findField(EntityRenderer.class, "thirdPersonDistanceTemp", "field_78491_C");
            return true;
        } catch (Throwable t) {
            reflectFailed = true;                               // another renderer: the vanilla distance stays
            dist = distTemp = null;
            return false;
        }
    }

    private static void set(EntityRenderer r, float d) {
        try {
            dist.setFloat(r, d);
            distTemp.setFloat(r, d);
        } catch (Throwable t) {
            reflectFailed = true;
            dist = distTemp = null;
        }
    }

    /** The vehicle the player rides (any seat), or null. */
    static EntityVehicleSVA ridden(EntityPlayer p) {
        return p == null ? null : HudVehicleSVA.vehicleOf(p);
    }

    /** True while the vehicle camera works this frame (riding, third person, config on). */
    public static boolean active(Minecraft mc) {
        EntityVehicleSVA v = ridden(mc.thePlayer);
        return ConfigSVA.vehicleCamera && v != null && !v.isDead && mc.gameSettings.thirdPersonView > 0;
    }

    /** RenderTickEvent START: the distance and the back view's pitch offset for this frame. */
    public static void frameStart(Minecraft mc) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : (now - lastNanos) / 1e9;
        lastNanos = now;
        EntityPlayer p = mc.thePlayer;
        if (!active(mc) || !reflect()) {
            if (changed && reflect()) {
                set(mc.entityRenderer, CameraMath.VANILLA);
                changed = false;
            }
            shown = -1;
            return;
        }
        EntityVehicleSVA v = ridden(p);
        double target = CameraMath.distance(v.getVehicleType().halfLength, zoom, ConfigSVA.cameraDistance);
        shown = shown < 0 ? target : shown + (target - shown) * CameraMath.follow(dt, CameraMath.DIST_EASE);
        set(mc.entityRenderer, (float) shown);
        changed = true;
        if (mc.gameSettings.thirdPersonView == 1) {
            // vanilla's mouse look clamps the raised pitch to 90: keep half the way down free so looking down isn't stuck
            pitchApplied = Math.max(0F, Math.min(CameraMath.pitchOffset(p.rotationPitch, CameraMath.PITCH_OFFSET),
                    (90F - p.rotationPitch) / 2F));
            p.rotationPitch += pitchApplied;
            p.prevRotationPitch += pitchApplied;
        }
    }

    /** RenderTickEvent END: the look pitch back as the player has it (within vanilla's -90..90). */
    public static void frameEnd(Minecraft mc) {
        if (pitchApplied != 0 && mc.thePlayer != null) {
            EntityPlayer p = mc.thePlayer;
            float real = Math.max(-90F, Math.min(90F, p.rotationPitch - pitchApplied));
            p.prevRotationPitch += real - p.rotationPitch;
            p.rotationPitch = real;
        }
        pitchApplied = 0;
    }

    /** The wheel while riding in third person: zoom. @return true if used */
    public static boolean wheel(Minecraft mc, int notches) {
        if (!active(mc) || notches == 0) {
            return false;
        }
        zoom = CameraMath.wheel(zoom, notches);
        return true;
    }
}
