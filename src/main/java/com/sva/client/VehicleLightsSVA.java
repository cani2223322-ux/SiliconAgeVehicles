package com.sva.client;

import java.nio.FloatBuffer;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.VehicleLights;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

/**
 * ★6 / ★7: a vehicle's lights, drawn by RenderVehicleSVA inside the model's frame (model units) after the model -
 * - headlight / landing-light beams (config vehicles.headlights): soft cones out of each HEAD lamp, additive, no depth
 *   writes, fading to nothing over BEAM_LEN blocks; only at night / underground (by the sky light at the vehicle) and,
 *   for aircraft, only near the ground (landing lights) and with the gear down where the lamp sits on it;
 * - nav lights (config vehicles.navLights): red left / green right steady, the white strobe (one short flash a second),
 *   the red rotating beacon underneath, amber rotating beacons and steady markers on work vehicles - each a small bright
 *   core + a soft halo, billboards facing the camera (eye-space quads from the model-view matrix), fuller at night;
 * - brake lights: a red halo on the tail lamps while braking (the lamps themselves: ObjModelSVA.anim's tail level).
 * Everything only while the vehicle has charge and is not a wreck. Blinking by world time + a per-vehicle phase
 * (AnimMath.strobe / beacon), so it costs no packets.
 */
public final class VehicleLightsSVA {

    /** Beam length / end radius / start radius (blocks) and the alpha at the lamp at full darkness. */
    private static final float BEAM_LEN = 7F, BEAM_R = 1.5F, BEAM_R0 = 0.07F, BEAM_ALPHA = 0.26F;
    /** Aircraft landing lights shine only below this height over the ground (blocks). */
    private static final double LANDING_HEIGHT = 16;
    private static final int SEGMENTS = 16;
    private static final FloatBuffer MV = BufferUtils.createFloatBuffer(16);

    private VehicleLightsSVA() {
    }

    /** 0 (daylight) .. 1 (night / underground): the sky light at the vehicle, darkened by the time of day. */
    public static float darkness(EntityVehicleSVA v) {
        World w = v.worldObj;
        int x = MathHelper.floor_double(v.posX), y = MathHelper.floor_double(v.posY + 1), z = MathHelper.floor_double(v.posZ);
        if (y < 0 || y > 255) {
            return 1F;
        }
        int sky = w.getSavedLightValue(EnumSkyBlock.Sky, x, y, z) - w.skylightSubtracted;
        return AnimMath.clamp((11 - sky) / 8F, 0F, 1F);
    }

    /**
     * Cockpit stage 2: the headlight beams shine now (the same test as render's: config on, charge, dark at the vehicle,
     * aircraft only near the ground, the model has a headlight) - the cockpit's «ФАРЫ» lamp.
     */
    public static boolean headlightsOn(EntityVehicleSVA v) {
        VehicleType t = v.getVehicleType();
        return ConfigSVA.headlights && v.powered() && VehicleLights.hasHead(t.model) && (!t.movement.flies || v.anim.height < LANDING_HEIGHT)
                && darkness(v) > 0.05F;
    }

    public static void render(EntityVehicleSVA v, VehicleType t, float pt, float gear) {
        boolean heads = ConfigSVA.headlights, navs = ConfigSVA.navLights;
        if (!heads && !navs || !v.powered()) {
            return;
        }
        List<VehicleLights.Light> lights = VehicleLights.of(t.model);
        if (lights.isEmpty()) {
            return;
        }
        float dark = darkness(v);
        double time = v.worldObj.getTotalWorldTime() + pt;
        int phase = AnimMath.phase(v.getEntityId());
        boolean landing = !t.movement.flies || v.anim.height < LANDING_HEIGHT;
        float s = t.modelScale;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT);
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        // beams first, in the model frame
        if (heads && dark > 0.05F && landing) {
            for (VehicleLights.Light l : lights) {
                if (l.kind == VehicleLights.HEAD && shown(l, v, gear)) {
                    beam(l, s, dark);
                }
            }
        }
        // then the glows as camera-facing billboards in eye space
        MV.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MV);
        float day = 0.35F + 0.65F * dark;
        for (VehicleLights.Light l : lights) {
            if (!shown(l, v, gear)) {
                continue;
            }
            int rgb = VehicleLights.colour(l.kind);
            switch (l.kind) {
                case VehicleLights.HEAD:
                    if (heads && (landing || l.has(VehicleLights.F_DISC))) {
                        glow(l, rgb, 0.45F, 0.5F * dark + 0.15F, 0.07F);
                    }
                    break;
                case VehicleLights.BRAKE:
                    if (heads && v.anim.braking()) {
                        glow(l, rgb, 0.5F, 0.75F, 0F);
                    }
                    break;
                case VehicleLights.RED:
                case VehicleLights.GREEN:
                case VehicleLights.WHITE:
                    if (navs) {
                        glow(l, rgb, 0.35F, 0.55F * day, 0.06F);
                    }
                    break;
                case VehicleLights.MARKER:
                    if (navs) {
                        glow(l, rgb, 0.25F, 0.45F * day, 0.05F);
                    }
                    break;
                case VehicleLights.STROBE:
                    if (navs && AnimMath.strobe((long) time, phase)) {
                        glow(l, rgb, 1.0F, 0.9F, 0.09F);
                    }
                    break;
                case VehicleLights.BEACON:
                case VehicleLights.AMBER:
                    if (navs) {
                        float b = AnimMath.beacon(time, phase, l.kind == VehicleLights.AMBER ? AnimMath.BEACON_PERIOD * 4 / 5 : AnimMath.BEACON_PERIOD);
                        glow(l, rgb, 0.3F + 0.5F * b, b * (0.4F + 0.6F * day), 0.07F);
                    }
                    break;
                default:
                    break;
            }
        }
        GL11.glDepthMask(true);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        GL11.glPopAttrib();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private static boolean shown(VehicleLights.Light l, EntityVehicleSVA v, float gear) {
        if (l.has(VehicleLights.F_TRAILER) && !v.hasTrailer()) {
            return false;
        }
        return !l.has(VehicleLights.F_GEAR) || gear < 0.5F;
    }

    /**
     * A light cone from the lamp, `pitch` deg below forward (+x): a strip between a small ring at the lamp (alpha
     * BEAM_ALPHA x darkness) and the far ring (alpha 0) - additive, so it fades out with distance; an inner, narrower
     * cone makes the core brighter. Sizes in blocks / the model scale.
     */
    private static void beam(VehicleLights.Light l, float s, float dark) {
        double p = Math.toRadians(l.pitch);
        float dx = (float) Math.cos(p), dy = (float) -Math.sin(p);
        float ux = (float) Math.sin(p), uy = (float) Math.cos(p);       // up, across the beam (in the x-y plane)
        float len = BEAM_LEN / s;
        for (int layer = 0; layer < 2; layer++) {
            float r1 = (layer == 0 ? BEAM_R : BEAM_R * 0.45F) / s, r0 = BEAM_R0 / s;
            float a0 = BEAM_ALPHA * dark * (layer == 0 ? 0.6F : 0.8F);
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int k = 0; k <= SEGMENTS; k++) {
                double ang = k * Math.PI * 2 / SEGMENTS;
                float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
                GL11.glColor4f(1F, 0.95F, 0.82F, a0);
                GL11.glVertex3f(l.x + ux * c * r0, l.y + uy * c * r0, l.z + sn * r0);
                GL11.glColor4f(1F, 0.95F, 0.82F, 0F);
                GL11.glVertex3f(l.x + dx * len + ux * c * r1, l.y + dy * len + uy * c * r1, l.z + sn * r1);
            }
            GL11.glEnd();
        }
    }

    /**
     * A glow at the light: its eye-space position from the model-view matrix (pulled 0.25 blocks toward the camera so the
     * hull does not cut it), then a camera-facing halo (radius `size` blocks, centre alpha `alpha`, edge 0) and a small
     * bright core (radius `core`; 0 = none).
     */
    private static void glow(VehicleLights.Light l, int rgb, float size, float alpha, float core) {
        if (alpha <= 0.01F) {
            return;
        }
        float ex = MV.get(0) * l.x + MV.get(4) * l.y + MV.get(8) * l.z + MV.get(12);
        float ey = MV.get(1) * l.x + MV.get(5) * l.y + MV.get(9) * l.z + MV.get(13);
        float ez = MV.get(2) * l.x + MV.get(6) * l.y + MV.get(10) * l.z + MV.get(14);
        float d = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (d > 0.5F) {
            float f = (d - 0.25F) / d;
            ex *= f;
            ey *= f;
            ez *= f;
        }
        float r = (rgb >> 16 & 255) / 255F, g = (rgb >> 8 & 255) / 255F, b = (rgb & 255) / 255F;
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(ex, ey, ez);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r, g, b, Math.min(1F, alpha));
        GL11.glVertex3f(0F, 0F, 0F);
        GL11.glColor4f(r, g, b, 0F);
        for (int k = 0; k <= SEGMENTS; k++) {
            double a = k * Math.PI * 2 / SEGMENTS;
            GL11.glVertex3f((float) Math.cos(a) * size, (float) Math.sin(a) * size, 0F);
        }
        GL11.glEnd();
        if (core > 0F) {
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glColor4f(Math.min(1F, r + 0.5F), Math.min(1F, g + 0.5F), Math.min(1F, b + 0.5F), 1F);
            GL11.glVertex3f(0F, 0F, 0F);
            GL11.glColor4f(r, g, b, 0.9F);
            for (int k = 0; k <= 8; k++) {
                double a = k * Math.PI * 2 / 8;
                GL11.glVertex3f((float) Math.cos(a) * core, (float) Math.sin(a) * core, 0F);
            }
            GL11.glEnd();
        }
        GL11.glPopMatrix();
    }
}
