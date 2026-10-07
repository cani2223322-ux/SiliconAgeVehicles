package com.sva.client;

import org.lwjgl.opengl.GL11;

import com.sva.ConfigSVA;
import com.sva.block.TileEntityGarageSVA;
import com.sva.entity.EntityVehicleSVA;
import com.sva.vehicle.VehicleType;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

/**
 * Wave 2, ★7: while the garage repairs, a cyan hologram ring (like the workbench's hologram: additive, full bright)
 * circles the vehicle and sweeps up and down its height, with a fainter ring at the progress height that climbs as the
 * job advances; a short green flash ring when it is done. Config vehicles.garageHologram. Fixed-function GL only.
 */
public class RenderGarageSVA extends TileEntitySpecialRenderer {

    private static final int SEGMENTS = 48;

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float pt) {
        if (!ConfigSVA.garageHologram || !(te instanceof TileEntityGarageSVA)) {
            return;
        }
        TileEntityGarageSVA g = (TileEntityGarageSVA) te;
        boolean job = g.isClientRepairing(), done = g.isClientDone();
        if (!job && !done) {
            return;
        }
        EntityVehicleSVA v = g.vehicleAny();
        if (v == null) {
            return;
        }
        VehicleType t = v.getVehicleType();
        double vx = v.lastTickPosX + (v.posX - v.lastTickPosX) * pt - te.xCoord + x;
        double vy = v.lastTickPosY + (v.posY - v.lastTickPosY) * pt - te.yCoord + y;
        double vz = v.lastTickPosZ + (v.posZ - v.lastTickPosZ) * pt - te.zCoord + z;
        float r = (float) Math.max(t.width * 0.5, t.halfLength) + 0.4F;
        float h = Math.max(1.2F, t.height);
        long time = te.getWorldObj() == null ? 0 : te.getWorldObj().getTotalWorldTime();
        float tt = time + pt;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_LINE_BIT);
        GL11.glTranslated(vx, vy, vz);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);         // additive: a glow
        GL11.glDepthMask(false);
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        if (job) {
            float sweep = (float) (0.5 - 0.5 * Math.cos(tt * 0.08)) * h + 0.1F;
            ring(r, sweep, 0.12F, 0.25F, 0.85F, 1F, 0.55F);
            float prog = g.getClientProgress() / 100F;
            ring(r * 1.05F, 0.05F + prog * h, 0.05F, 0.25F, 0.85F, 1F, 0.3F);
            ring(r * 1.1F, 0.03F, 0.06F, 0.25F, 0.85F, 1F, 0.35F + 0.1F * (float) Math.sin(tt * 0.3));   // on the floor
        } else {
            float f = 0.4F + 0.2F * (float) Math.sin(tt * 0.5);
            ring(r, h * 0.5F, 0.1F, 0.35F, 1F, 0.45F, f);
        }
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
        GL11.glDepthMask(true);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /** A flat band ring of radius r at height y, `w` tall, fading to its edges. */
    private static void ring(float r, float y, float w, float cr, float cg, float cb, float a) {
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int k = 0; k <= SEGMENTS; k++) {
            double ang = k * Math.PI * 2 / SEGMENTS;
            float c = (float) Math.cos(ang) * r, s = (float) Math.sin(ang) * r;
            GL11.glColor4f(cr, cg, cb, a);
            GL11.glVertex3f(c, y, s);
            GL11.glColor4f(cr, cg, cb, 0F);
            GL11.glVertex3f(c, y + w, s);
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int k = 0; k <= SEGMENTS; k++) {
            double ang = k * Math.PI * 2 / SEGMENTS;
            float c = (float) Math.cos(ang) * r, s = (float) Math.sin(ang) * r;
            GL11.glColor4f(cr, cg, cb, a);
            GL11.glVertex3f(c, y, s);
            GL11.glColor4f(cr, cg, cb, 0F);
            GL11.glVertex3f(c, y - w, s);
        }
        GL11.glEnd();
    }
}
