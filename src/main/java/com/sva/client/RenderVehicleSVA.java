package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sva.entity.EntityVehicleSVA;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

/** Draws a vehicle's OBJ model (display lists, see ObjModelSVA), rotated by the interpolated yaw. */
public class RenderVehicleSVA extends Render {

    private static final ResourceLocation NONE = new ResourceLocation("textures/blocks/iron_block.png");
    /** Aircraft pitch / bank about this model height (model units). */
    private static final float ATTITUDE_Y = 0.7F;

    public RenderVehicleSVA() {
        shadowSize = 1.2F;
    }

    @Override
    public void doRender(Entity e, double x, double y, double z, float yaw, float pt) {
        EntityVehicleSVA v = (EntityVehicleSVA) e;
        ObjModelSVA m = ObjModelSVA.get(v.getVehicleType().model);
        if (m == null) {
            return;
        }
        float ry = v.prevRotationYaw + MathHelper.wrapAngleTo180_float(v.rotationYaw - v.prevRotationYaw) * pt;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT);
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-ry - 90F, 0F, 1F, 0F);         // model +x -> the entity's facing
        float s = v.getVehicleType().modelScale;
        GL11.glScalef(s, s, s);
        GL11.glTranslatef(-v.getVehicleType().modelOffsetX, 0F, 0F);   // the crawler sits forward of its trailer in truck.obj
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        // the local rider in first person: no glass at all (the clearest view); everyone else sees it see-through
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        boolean ownView = mc.gameSettings.thirdPersonView == 0 && HudVehicleSVA.vehicleOf(mc.thePlayer) == v;
        if (v.getVehicleType().movement.flies && !ownView) {
            // aircraft attitude (pitch nose up +, bank right +) about the body's middle; the own cockpit view stays level
            float pitch = v.prevRenderPitch + (v.renderPitch - v.prevRenderPitch) * pt;
            float roll = v.prevRenderRoll + (v.renderRoll - v.prevRenderRoll) * pt;
            GL11.glTranslatef(0F, ATTITUDE_Y, 0F);
            GL11.glRotatef(pitch, 0F, 0F, 1F);
            GL11.glRotatef(roll, 1F, 0F, 0F);
            GL11.glTranslatef(0F, -ATTITUDE_Y, 0F);
        }
        float shade = v.isWrecked() ? 0.45F : 1F;
        m.render(shade, !ownView);
        java.util.List<ObjModelSVA.Part> parts = m.parts();
        if (!parts.isEmpty()) {
            // rotor / propeller: turned by the synced spin; a faint blur disc while it spins fast
            float a = v.rotorAngle(pt), rate = Math.abs(v.rotorRate());
            float blur = rate > 20F ? 0.05F + 0.07F * Math.min(1F, (rate - 20F) / 20F) : 0F;
            for (ObjModelSVA.Part p : parts) {
                if (p.kind == ObjModelSVA.Part.TRAILER) {
                    if (v.hasTrailer()) {
                        m.renderPart(p, 0F, shade);
                    }
                } else if (p.kind == ObjModelSVA.Part.BORE) {
                    m.renderPart(p, v.drillAngle(pt) * p.speed, shade);
                } else {
                    m.renderPart(p, a * p.speed, shade);
                    ObjModelSVA.renderDisc(p, blur);
                }
            }
        }
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity e) {
        return NONE;
    }
}
