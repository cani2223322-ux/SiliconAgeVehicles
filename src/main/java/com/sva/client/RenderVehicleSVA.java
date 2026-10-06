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
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        // the local rider in first person: no glass at all (the clearest view); everyone else sees it see-through
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        boolean ownView = mc.gameSettings.thirdPersonView == 0 && HudVehicleSVA.vehicleOf(mc.thePlayer) == v;
        m.render(v.isWrecked() ? 0.45F : 1F, !ownView);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity e) {
        return NONE;
    }
}
