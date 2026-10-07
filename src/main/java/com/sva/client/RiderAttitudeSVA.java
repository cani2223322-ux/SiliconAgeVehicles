package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.AnimMath;
import com.sva.vehicle.Movement;
import com.sva.vehicle.VehicleType;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.client.event.RenderLivingEvent;

/**
 * Riders lean with the body. RenderVehicleSVA pitches / banks an aircraft's model about ATTITUDE_Y (the heli and the
 * tiltrotor dip the nose with speed, everything banks in turns), and tilts / bobs the hover bike - but the riders sat
 * level at their seats, so with the nose down the pilots' heads came out through the cockpit roof (seat 3.5 blocks
 * forward x sin 10 deg = 0.6 blocks). Here every living rider of such a vehicle is drawn through the same transform as
 * the model: Pre pushes it, Post pops it. Same condition as the model: not while the local player sits in that
 * vehicle in first person (then the model stays level too).
 */
public final class RiderAttitudeSVA {

    /** Entities whose Pre pushed a matrix (Post pops only those - a cancelled Pre never gets its Post). */
    private final java.util.Set<EntityLivingBase> pushed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<EntityLivingBase, Boolean>());

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPre(RenderLivingEvent.Pre e) {
        if (e.isCanceled()) {
            return;
        }
        EntityLivingBase r = e.entity;
        EntityVehicleSVA v = HudVehicleSVA.vehicleOf(r);
        if (v == null) {
            return;
        }
        VehicleType vt = v.getVehicleType();
        boolean hover = vt.movement == Movement.HOVER && ConfigSVA.animations;
        if (!vt.movement.flies && !hover) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.thirdPersonView == 0 && HudVehicleSVA.vehicleOf(mc.thePlayer) == v) {
            return;                                     // the own cockpit view: the model is level as well
        }
        float pt = RenderLivingPt.get(mc);
        double px = r.lastTickPosX + (r.posX - r.lastTickPosX) * pt;
        double py = r.lastTickPosY + (r.posY - r.lastTickPosY) * pt;
        double pz = r.lastTickPosZ + (r.posZ - r.lastTickPosZ) * pt;
        // only the world pass (the inventory doll is drawn at 0,0,0 - leave it alone)
        if (Math.abs(e.x - (px - RenderManager.renderPosX)) > 0.05 || Math.abs(e.y - (py - RenderManager.renderPosY)) > 0.05
                || Math.abs(e.z - (pz - RenderManager.renderPosZ)) > 0.05) {
            return;
        }
        double vx = v.lastTickPosX + (v.posX - v.lastTickPosX) * pt - px + e.x;
        double vy = v.lastTickPosY + (v.posY - v.lastTickPosY) * pt - py + e.y;
        double vz = v.lastTickPosZ + (v.posZ - v.lastTickPosZ) * pt - pz + e.z;
        float ry = v.prevRotationYaw + net.minecraft.util.MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
        float s = vt.modelScale, off = vt.modelOffsetX * s;
        GL11.glPushMatrix();
        pushed.add(r);
        // world -> the vehicle's frame (x forward), the same pivots as RenderVehicleSVA.doRender, then back
        GL11.glTranslated(vx, vy, vz);
        GL11.glRotatef(-ry - 90F, 0F, 1F, 0F);
        GL11.glTranslatef(-off, 0F, 0F);
        if (vt.movement.flies) {
            float pitch = v.prevRenderPitch + (v.renderPitch - v.prevRenderPitch) * pt;
            float roll = v.prevRenderRoll + (v.renderRoll - v.prevRenderRoll) * pt;
            float h = RenderVehicleSVA.ATTITUDE_Y * s;
            GL11.glTranslatef(0F, h, 0F);
            GL11.glRotatef(pitch, 0F, 0F, 1F);
            GL11.glRotatef(roll, 1F, 0F, 0F);
            GL11.glTranslatef(0F, -h, 0F);
        } else {
            AnimMath.State an = v.anim;
            float bob = AnimMath.lerp(an.prevBob, an.bob, pt);
            if (v.powered()) {
                bob += RenderVehicleSVA.HOVER_BOB * (float) Math.sin((v.ticksExisted + pt) * RenderVehicleSVA.HOVER_BOB_RATE);
            }
            float h = RenderVehicleSVA.ATTITUDE_Y * 0.5F * s;
            GL11.glTranslatef(0F, h, 0F);
            GL11.glRotatef(AnimMath.lerp(an.prevLean, an.lean, pt), 1F, 0F, 0F);
            GL11.glTranslatef(0F, -h + bob, 0F);
        }
        GL11.glTranslatef(off, 0F, 0F);
        GL11.glRotatef(ry + 90F, 0F, 1F, 0F);
        GL11.glTranslated(-vx, -vy, -vz);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPost(RenderLivingEvent.Post e) {
        if (pushed.remove(e.entity)) {
            GL11.glPopMatrix();
        }
    }

    /** The current frame's partial tick (the event doesn't carry it). */
    static final class RenderLivingPt {
        private static java.lang.reflect.Field timer;

        static float get(Minecraft mc) {
            try {
                if (timer == null) {
                    timer = cpw.mods.fml.relauncher.ReflectionHelper.findField(Minecraft.class, "timer", "field_71428_T");
                }
                return ((net.minecraft.util.Timer) timer.get(mc)).renderPartialTicks;
            } catch (Exception ex) {
                return 1F;
            }
        }
    }
}
